package com.cheraz.memcard.core.sync

import com.cheraz.memcard.core.engine.SaveRestorer
import com.cheraz.memcard.core.engine.SnapshotService
import com.cheraz.memcard.core.history.GameManifest
import com.cheraz.memcard.core.history.History
import com.cheraz.memcard.core.history.Partida
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
 * @param partida la partida en curso EN ESTE dispositivo (null = aun no se elige).
 * @param head version a la que corresponde la carpeta ahorita (la ultima que se subio o cargo).
 * @param pending snapshot local que no se subio porque iria a una partida que ya avanzo en otro
 *   lado, y el usuario no quiso guardarlo como partida nueva (o aun no contesta).
 */
@Serializable
data class GameLink(
    val gameId: String,
    val name: String,
    val folder: String,
    val partida: String? = null,
    val head: String? = null,
    val headHash: String? = null,
    val pending: SaveVersion? = null,
    val pendingDeclined: Boolean = false,
    val autoUpload: Boolean = true,
    val autoDownload: Boolean = false,
)

/** Una partida con su ultima version, para mostrarla. */
data class PartidaInfo(val partida: Partida, val tip: SaveVersion?, val count: Int)

/** Foto del estado de un juego para la UI. */
data class GameState(
    val manifest: GameManifest?,
    /** Todas las partidas: la en curso primero, luego la jugada mas recientemente. */
    val partidas: List<PartidaInfo>,
    val current: PartidaInfo?,
    val head: SaveVersion?,
    /** La partida en curso avanzo en otro dispositivo despues de [head]: se puede bajar. */
    val newer: SaveVersion?,
    /** La carpeta ya no es igual a [head] (cambios sin subir). null = no se pudo leer. */
    val localChanged: Boolean?,
    val localError: String? = null,
)

sealed interface CommitOutcome {
    val link: GameLink

    /** La carpeta es igual a lo ultimo subido. */
    data class Unchanged(override val link: GameLink) : CommitOutcome

    /** @param newPartida si la version abrio una partida nueva. */
    data class Uploaded(override val link: GameLink, val version: SaveVersion, val newPartida: Partida?) : CommitOutcome

    /**
     * No se sabe a que partida va: la partida en curso ya avanzo despues de [GameLink.head] ([tip]),
     * o este dispositivo aun no elige partida y en la nube ya hay. Hay que preguntar si es una partida
     * nueva. El snapshot ya quedo en [GameLink.pending].
     */
    data class NeedsPartida(override val link: GameLink, val version: SaveVersion, val tip: SaveVersion?) : CommitOutcome

    /** Ya se habia dicho que no a la partida nueva: se actualizo el snapshot local sin subir. */
    data class KeptLocal(override val link: GameLink, val version: SaveVersion) : CommitOutcome
}

/** La partida avanzo en otro dispositivo mientras se subia. */
private class PartidaMovedException(val tip: SaveVersion?) : Exception()

/**
 * Motor de sync contra el drive. Todo es sincrono y bloqueante (llamar desde un hilo de fondo);
 * las operaciones se serializan con un candado porque comparten la cache local.
 *
 * Modelo: juego → partidas con nombre → versiones en linea. Cada dispositivo tiene una partida en
 * curso ([GameLink.partida]); lo que guardas se sube a esa. Si esa partida avanzo en otro lado (o
 * cargaste una version vieja del historial), guardar encima no pisa nada: pregunta si es una
 * partida nueva.
 *
 * Cache local ([cacheDir]): `blobs/<hash>.zip` (lo subido y lo bajado), `manifests2/<id>.json`
 * (ultima copia mezclada) y `backups/<id>/` (lo que habia antes de cada carga).
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
        val infos = manifest?.partidas.orEmpty().map { p ->
            PartidaInfo(p, manifest!!.tipOf(p.id), manifest.versionsOf(p.id).size)
        }.sortedWith(
            compareByDescending<PartidaInfo> { it.partida.id == link.partida }
                .thenByDescending { it.tip?.createdAt ?: it.partida.createdAt },
        )
        val current = infos.firstOrNull { it.partida.id == link.partida }
        val head = manifest?.find(link.head)
        val history = History(manifest?.versions.orEmpty())
        val newer = current?.tip?.takeIf { tip -> head != null && tip.id != head.id && history.isAncestorOrSelf(head.id, tip.id) }
        val (changed, err) = try {
            (localHash(link) != link.headHash) to null
        } catch (e: Exception) {
            null to (e.message ?: e.toString())
        }
        GameState(manifest, infos, current, head, newer, changed, err)
    }

    /** Hash del contenido actual de la carpeta (sin zip). */
    fun localHash(link: GameLink): String = snapshots.snapshot(folderOf(link), null).hash

    // ---------- subir ----------

    /**
     * Toma un snapshot de la carpeta y lo sube como version nueva de la partida en curso.
     *
     * - Juego nuevo sin partidas en la nube: crea la partida [DEFAULT_PARTIDA].
     * - [newPartidaName]: lo guarda como partida nueva con ese nombre (aunque no haya cambios).
     * - Si la partida en curso avanzo en otro lado, o este dispositivo no ha elegido partida, no
     *   sube: regresa [CommitOutcome.NeedsPartida].
     */
    fun commit(link: GameLink, label: String = "", newPartidaName: String? = null): CommitOutcome =
        synchronized(lock) { commitLocked(link, label, newPartidaName?.trim()?.takeIf { it.isNotEmpty() }) }

    private fun commitLocked(link: GameLink, label: String, newPartidaName: String?): CommitOutcome {
        val tmp = tempFile()
        try {
            val snap = FileOutputStream(tmp).use { snapshots.snapshot(folderOf(link), it) }
            if (snap.hash == link.headHash && newPartidaName == null) {
                return CommitOutcome.Unchanged(link.copy(pending = null, pendingDeclined = false))
            }
            storeBlob(snap.hash, tmp)
            val now = clock()
            val pending = link.pending?.takeIf { it.hash == snap.hash }
            val version = pending ?: SaveVersion(
                id = Ulid.next(now),
                partida = link.partida ?: "",
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

            if (newPartidaName != null) return uploadAsNewPartida(link, version, newPartidaName)

            val manifest = pull(link.gameId)
            val current = manifest?.partida(link.partida)
            return when {
                manifest == null || manifest.partidas.isEmpty() -> uploadAsNewPartida(link, version, DEFAULT_PARTIDA)
                current == null -> askOrKeep(link, version, tip = null)
                else -> {
                    val tip = manifest.tipOf(current.id)
                    if (tip == null || tip.id == link.head) {
                        try {
                            upload(link, version.copy(partida = current.id), expectTip = tip?.id, newPartida = null)
                        } catch (e: PartidaMovedException) {
                            askOrKeep(link, version, e.tip)
                        }
                    } else {
                        askOrKeep(link, version, tip)
                    }
                }
            }
        } finally {
            tmp.delete()
        }
    }

    private fun askOrKeep(link: GameLink, version: SaveVersion, tip: SaveVersion?): CommitOutcome =
        if (link.pendingDeclined) {
            CommitOutcome.KeptLocal(link.copy(pending = version), version)
        } else {
            CommitOutcome.NeedsPartida(link.copy(pending = version), version, tip)
        }

    private fun uploadAsNewPartida(link: GameLink, version: SaveVersion, name: String): CommitOutcome.Uploaded {
        val now = clock()
        val p = Partida(id = Ulid.next(now), name = name, nameAt = now, createdAt = now, device = device)
        return upload(link, version.copy(partida = p.id), expectTip = null, newPartida = p)
    }

    /** Subir lo que quedo pendiente como partida nueva (el usuario dijo que si y le puso nombre). */
    fun uploadPendingAsNew(link: GameLink, name: String): CommitOutcome = synchronized(lock) {
        val clean = name.trim().ifEmpty { DEFAULT_PARTIDA }
        val p = link.pending
        if (p == null) commitLocked(link, "", clean) else uploadAsNewPartida(link, p, clean)
    }

    /** El usuario no quiso partida nueva: el snapshot se queda local y no vuelve a preguntar. */
    fun declinePending(link: GameLink): GameLink = link.copy(pendingDeclined = true)

    /**
     * @param expectTip la punta que se espera en la partida; si al escribir ya es otra (alguien
     *   subio a la misma partida en medio), no se sube y se pregunta.
     */
    private fun upload(link: GameLink, version: SaveVersion, expectTip: String?, newPartida: Partida?): CommitOutcome.Uploaded {
        val blob = blobFile(version.hash)
        check(blob.isFile) { "Falta el zip local de ${version.hash}" }
        drive.uploadBlob(version.hash, blob)
        writeWithRetry(link.gameId, link.name) { m ->
            if (newPartida == null) {
                val tip = m.tipOf(version.partida)
                if (tip?.id != expectTip) throw PartidaMovedException(tip)
            }
            (newPartida?.let { m.withPartida(it) } ?: m).withVersion(version)
        }
        val updated = link.copy(
            partida = version.partida,
            head = version.id,
            headHash = version.hash,
            pending = null,
            pendingDeclined = false,
        )
        return CommitOutcome.Uploaded(updated, version, newPartida)
    }

    fun setLabel(link: GameLink, versionId: String, label: String): GameManifest = synchronized(lock) {
        writeWithRetry(link.gameId, link.name) { it.withLabel(versionId, label.trim(), clock()) }
    }

    fun renamePartida(link: GameLink, partidaId: String, name: String): GameManifest = synchronized(lock) {
        writeWithRetry(link.gameId, link.name) { it.withPartidaName(partidaId, name.trim(), clock()) }
    }

    // ---------- cargar ----------

    /** Carga la ultima version de otra partida y la deja en curso en este dispositivo. */
    fun switchPartida(link: GameLink, partidaId: String): GameLink = synchronized(lock) {
        val manifest = readCached(link.gameId) ?: pull(link.gameId)
        val tip = manifest?.tipOf(partidaId) ?: throw IllegalArgumentException("Esa partida no tiene versiones")
        restoreLocked(link, tip.id)
    }

    /**
     * Deja la carpeta igual a [versionId] y pone su partida en curso. Antes guarda un respaldo de lo
     * que habia en `backups/<gameId>/` y verifica el zip (hash de contenido) antes de tocar nada.
     */
    fun restore(link: GameLink, versionId: String): GameLink = synchronized(lock) { restoreLocked(link, versionId) }

    private fun restoreLocked(link: GameLink, versionId: String): GameLink {
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
        return link.copy(
            partida = version.partida,
            head = version.id,
            headHash = version.hash,
            pending = null,
            pendingDeclined = false,
        )
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

    private fun manifestFile(gameId: String) = File(cacheDir, "manifests2/$gameId.json")

    private fun readCached(gameId: String): GameManifest? =
        manifestFile(gameId).takeIf { it.isFile }?.let { runCatching { GameManifest.fromJson(it.readText()) }.getOrNull() }

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

    companion object {
        const val DEFAULT_PARTIDA = "Principal"
        private const val MAX_RETRIES = 5
    }
}
