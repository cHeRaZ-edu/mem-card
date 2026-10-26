package com.cheraz.memcard.core.sync

import com.cheraz.memcard.core.engine.SaveRestorer
import com.cheraz.memcard.core.engine.SnapshotService
import com.cheraz.memcard.core.history.GameManifest
import com.cheraz.memcard.core.history.History
import com.cheraz.memcard.core.history.SaveVersion
import com.cheraz.memcard.core.history.Ulid
import com.cheraz.memcard.core.source.FolderSource
import com.cheraz.memcard.core.source.SaveFolder
import com.cheraz.memcard.core.source.ZipFileSource
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Un juego enlazado a una carpeta de este dispositivo. Lo guarda quien use el motor.
 *
 * @param folder donde vive el save en este dispositivo: ruta en desktop, URI de arbol SAF en Android
 *   (lo interpreta el `folderOf` del [SyncEngine]).
 * @param head version a la que corresponde la carpeta ahorita (la ultima que se subio o restauro).
 * @param pending snapshot local que no se subio porque abriria otro camino y el usuario dijo que no
 *   (o aun no contesta). Se queda solo en este dispositivo.
 */
@Serializable
data class GameLink(
    val gameId: String,
    val name: String,
    val folder: String,
    val head: String? = null,
    val headHash: String? = null,
    val pending: SaveVersion? = null,
    val pendingDeclined: Boolean = false,
    val autoUpload: Boolean = true,
    val autoDownload: Boolean = false,
)

/** Foto del estado de un juego para la UI. */
data class GameState(
    val manifest: GameManifest?,
    val head: SaveVersion?,
    /** Puntas que salen de [head] (alguien jugo despues): se pueden bajar. La mas nueva primero. */
    val newer: List<SaveVersion>,
    /** Puntas de otros caminos que no salen de [head]. */
    val otherPaths: List<SaveVersion>,
    /** La carpeta ya no es igual a [head] (cambios sin subir). null = no se pudo leer. */
    val localChanged: Boolean?,
    val localError: String? = null,
)

sealed interface CommitOutcome {
    val link: GameLink

    /** La carpeta es igual a lo ultimo subido. */
    data class Unchanged(override val link: GameLink) : CommitOutcome

    data class Uploaded(override val link: GameLink, val version: SaveVersion) : CommitOutcome

    /** Subir abriria otro camino: hay que preguntar. El snapshot ya quedo en [GameLink.pending]. */
    data class NeedsFork(override val link: GameLink, val version: SaveVersion) : CommitOutcome

    /** Ya se habia dicho que no al camino nuevo: se actualizo el snapshot local sin subir. */
    data class KeptLocal(override val link: GameLink, val version: SaveVersion) : CommitOutcome
}

/**
 * Motor de sync contra el drive. Todo es sincrono y bloqueante (llamar desde un hilo de fondo);
 * las operaciones se serializan con un candado porque comparten la cache local.
 *
 * Cache local ([cacheDir]): `blobs/<hash>.zip` (lo subido y lo bajado), `manifests/<id>.json`
 * (ultima copia mezclada) y `backups/<id>/` (lo que habia antes de cada restauracion).
 */
class SyncEngine(
    private val drive: DriveStore,
    private val cacheDir: File,
    private val device: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxBackups: Int = 10,
    /** Como abrir la carpeta de un juego. Por defecto [GameLink.folder] es una ruta normal. */
    private val folderOf: (GameLink) -> SaveFolder = { FolderSource(File(it.folder)) },
) {
    private val lock = Any()
    private val snapshots = SnapshotService()
    private val restorer = SaveRestorer()

    // ---------- juegos ----------

    fun createGame(name: String, folder: String): GameLink =
        GameLink(gameId = Ulid.next(clock()), name = name.trim(), folder = folder)

    /** Juegos que ya estan en la nube (para enlazarlos en este dispositivo). */
    fun listCloudGames(): List<GameManifest> = synchronized(lock) {
        drive.listGameIds().mapNotNull { id -> pull(id) }.sortedBy { it.name.lowercase() }
    }

    fun linkFromCloud(manifest: GameManifest, folder: String): GameLink =
        GameLink(gameId = manifest.gameId, name = manifest.name, folder = folder)

    fun cachedManifest(gameId: String): GameManifest? = synchronized(lock) { readCached(gameId) }

    // ---------- estado ----------

    /** Baja el manifiesto (si [remote]) y compara la carpeta contra [GameLink.head]. */
    fun state(link: GameLink, remote: Boolean = true): GameState = synchronized(lock) {
        val manifest = if (remote) pull(link.gameId) else readCached(link.gameId)
        val history = History(manifest?.versions.orEmpty())
        val head = history.get(link.head)
        val tips = history.tips()
        val newer = if (head == null) emptyList() else tips.filter { it.id != head.id && history.isAncestorOrSelf(head.id, it.id) }
        val others = tips.filter { t -> t.id != head?.id && t !in newer }
        val (changed, err) = try {
            (localHash(link) != link.headHash) to null
        } catch (e: Exception) {
            null to (e.message ?: e.toString())
        }
        GameState(manifest, head, newer, others, changed, err)
    }

    /** Hash del contenido actual de la carpeta (sin zip). */
    fun localHash(link: GameLink): String = snapshots.snapshot(folderOf(link), null).hash

    // ---------- subir ----------

    /**
     * Toma un snapshot de la carpeta y lo sube como version nueva, hija de [GameLink.head].
     * Si eso abriria otro camino (alguien ya subio algo despues de [GameLink.head], o la carpeta
     * nunca se enlazo a una version y en la nube ya hay partidas), no sube salvo [allowFork].
     */
    fun commit(link: GameLink, label: String = "", allowFork: Boolean = false): CommitOutcome =
        synchronized(lock) { commitLocked(link, label, allowFork) }

    private fun commitLocked(link: GameLink, label: String, allowFork: Boolean): CommitOutcome {
        val tmp = tempFile()
        try {
            val snap = FileOutputStream(tmp).use { snapshots.snapshot(folderOf(link), it) }
            if (snap.hash == link.headHash) {
                return CommitOutcome.Unchanged(link.copy(pending = null, pendingDeclined = false))
            }
            val pending = link.pending
            if (pending != null && pending.hash == snap.hash) {
                return if (link.pendingDeclined && !allowFork) {
                    CommitOutcome.KeptLocal(link, pending)
                } else if (!allowFork) {
                    CommitOutcome.NeedsFork(link, pending)
                } else {
                    upload(link, pending)
                }
            }
            storeBlob(snap.hash, tmp)
            val now = clock()
            val version = SaveVersion(
                id = Ulid.next(now),
                parent = link.head,
                hash = snap.hash,
                zipSize = snap.zipSize,
                files = snap.fileCount,
                bytes = snap.totalBytes,
                device = device,
                createdAt = now,
                label = label.trim(),
                labelAt = if (label.isBlank()) 0 else now,
            )
            val manifest = pull(link.gameId)
            val history = History(manifest?.versions.orEmpty())
            val forks = when {
                history.versions.isEmpty() -> false
                link.head == null -> true
                else -> history.childrenOf(link.head).isNotEmpty()
            }
            return when {
                !forks || allowFork -> upload(link, version)
                link.pendingDeclined -> CommitOutcome.KeptLocal(link.copy(pending = version), version)
                else -> CommitOutcome.NeedsFork(link.copy(pending = version, pendingDeclined = false), version)
            }
        } finally {
            tmp.delete()
        }
    }

    /** El usuario acepto el camino nuevo: sube [GameLink.pending]. */
    fun uploadPending(link: GameLink): CommitOutcome = synchronized(lock) {
        link.pending?.let { upload(link, it) } ?: CommitOutcome.Unchanged(link)
    }

    /** El usuario no quiso otro camino: el snapshot se queda local y no vuelve a preguntar. */
    fun declinePending(link: GameLink): GameLink = link.copy(pendingDeclined = true)

    private fun upload(link: GameLink, version: SaveVersion): CommitOutcome.Uploaded {
        val blob = blobFile(version.hash)
        check(blob.isFile) { "Falta el zip local de ${version.hash}" }
        drive.uploadBlob(version.hash, blob)
        writeWithRetry(link.gameId, link.name) { it.withVersion(version) }
        val updated = link.copy(head = version.id, headHash = version.hash, pending = null, pendingDeclined = false)
        return CommitOutcome.Uploaded(updated, version)
    }

    fun setLabel(link: GameLink, versionId: String, label: String): GameManifest = synchronized(lock) {
        writeWithRetry(link.gameId, link.name) { it.withLabel(versionId, label.trim(), clock()) }
    }

    // ---------- restaurar ----------

    /**
     * Deja la carpeta igual a [versionId]. Antes guarda un respaldo de lo que habia en
     * `backups/<gameId>/`, y verifica el zip (hash de contenido) antes de tocar nada.
     */
    fun restore(link: GameLink, versionId: String): GameLink = synchronized(lock) {
        val manifest = readCached(link.gameId) ?: pull(link.gameId)
        val version = manifest?.find(versionId) ?: throw IllegalArgumentException("No existe la version $versionId")
        val blob = ensureBlob(version.hash)

        val source = folderOf(link)
        source.ensureExists()
        backup(link, source)

        FileInputStream(blob).use { restorer.restore(it, source.list(), source) }
        val after = snapshots.snapshot(source, null).hash
        check(after == version.hash) {
            "La carpeta quedo distinta a la version (¿el emulador estaba abierto?). Hay respaldo en ${backupDir(link.gameId)}"
        }
        link.copy(head = version.id, headHash = version.hash, pending = null, pendingDeclined = false)
    }

    fun backupDir(gameId: String) = File(cacheDir, "backups/$gameId")

    private fun backup(link: GameLink, source: SaveFolder) {
        val dir = backupDir(link.gameId).apply { mkdirs() }
        val tmp = tempFile()
        val snap = FileOutputStream(tmp).use { snapshots.snapshot(source, it) }
        if (snap.fileCount == 0) {
            tmp.delete()
            return
        }
        val name = "%d-%s.zip".format(clock(), snap.hash.take(12))
        if (!tmp.renameTo(File(dir, name))) tmp.copyTo(File(dir, name), overwrite = true).also { tmp.delete() }
        dir.listFiles { f -> f.name.endsWith(".zip") }?.sortedByDescending { it.name }?.drop(maxBackups)?.forEach { it.delete() }
    }

    /** Baja el blob si no esta en cache y comprueba que su contenido de verdad tenga ese hash. */
    private fun ensureBlob(hash: String): File {
        val f = blobFile(hash)
        if (f.isFile && zipHash(f) == hash) return f
        val tmp = tempFile()
        try {
            drive.downloadBlob(hash, tmp)
            val got = zipHash(tmp)
            if (got != hash) throw DriveException("El zip bajado no coincide con su hash ($got)")
            storeBlob(hash, tmp)
        } finally {
            tmp.delete()
        }
        return f
    }

    private fun zipHash(f: File): String = ZipFile(f).use { snapshots.snapshot(ZipFileSource(it), null).hash }

    // ---------- manifiestos ----------

    /** Lee el remoto, lo mezcla con la cache y guarda el resultado. */
    private fun pull(gameId: String): GameManifest? {
        val remote = drive.readManifest(gameId)?.manifest
        val merged = GameManifest.merge(readCached(gameId), remote)
        if (merged != null) writeCached(merged)
        return merged
    }

    private fun writeWithRetry(gameId: String, name: String, mutate: (GameManifest) -> GameManifest): GameManifest {
        var last: Exception? = null
        repeat(MAX_RETRIES) {
            val remote = drive.readManifest(gameId)
            val base = GameManifest.merge(readCached(gameId), remote?.manifest)
                ?: GameManifest(gameId = gameId, name = name, nameAt = clock())
            val updated = mutate(base)
            try {
                drive.writeManifest(gameId, updated, remote?.version)
                writeCached(updated)
                return updated
            } catch (e: DriveConflictException) {
                last = e
            }
        }
        throw DriveException("Otro dispositivo sigue escribiendo el historial; intenta de nuevo. (${last?.message})")
    }

    private fun manifestFile(gameId: String) = File(cacheDir, "manifests/$gameId.json")

    private fun readCached(gameId: String): GameManifest? =
        manifestFile(gameId).takeIf { it.isFile }?.let { GameManifest.fromJson(it.readText()) }

    private fun writeCached(m: GameManifest) {
        val f = manifestFile(m.gameId)
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(m.toJson())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    // ---------- blobs ----------

    fun blobFile(hash: String) = File(cacheDir, "blobs/$hash.zip")

    private fun storeBlob(hash: String, from: File) {
        val dest = blobFile(hash)
        if (dest.isFile) return
        dest.parentFile.mkdirs()
        from.copyTo(dest, overwrite = true)
    }

    private fun tempFile(): File {
        val dir = File(cacheDir, "tmp").apply { mkdirs() }
        return File.createTempFile("snap", ".zip", dir)
    }

    private companion object {
        const val MAX_RETRIES = 5
    }
}
