package com.cheraz.memcard.core.sync

import com.cheraz.memcard.core.history.GameManifest
import com.cheraz.memcard.core.history.History
import com.cheraz.memcard.core.history.SaveVersion
import com.cheraz.memcard.core.history.Ulid
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drive en memoria con la misma semantica que el API: versiones por archivo y 409 con expected_version. */
class FakeDrive : DriveStore {
    val files = HashMap<String, Pair<ByteArray, Int>>()
    var uploads = 0

    /** Se corre justo antes de escribir un manifiesto (para simular que otro dispositivo gano la carrera). */
    var beforeManifestWrite: (() -> Unit)? = null

    override fun readManifest(gameId: String): RemoteManifest? =
        files[DriveStore.manifestPath(gameId)]?.let { (b, v) -> RemoteManifest(GameManifest.fromJson(b.decodeToString()), v) }

    override fun writeManifest(gameId: String, manifest: GameManifest, expectVersion: Int?) {
        beforeManifestWrite?.also { beforeManifestWrite = null }?.invoke()
        val path = DriveStore.manifestPath(gameId)
        val cur = files[path]
        if (expectVersion == null && cur != null) throw DriveConflictException("ya existe")
        if (expectVersion != null && cur?.second != expectVersion) throw DriveConflictException("version ${cur?.second}")
        files[path] = manifest.toJson().encodeToByteArray() to ((cur?.second ?: 0) + 1)
    }

    override fun uploadBlob(hash: String, file: File) {
        val path = DriveStore.blobPath(hash)
        if (path !in files) {
            files[path] = file.readBytes() to 1
            uploads++
        }
    }

    override fun downloadBlob(hash: String, dest: File) {
        dest.writeBytes(files.getValue(DriveStore.blobPath(hash)).first)
    }

    override fun listGameIds(): List<String> =
        files.keys.filter { it.startsWith("heads/") }.map { it.removePrefix("heads/").removeSuffix(".json") }
}

class SyncEngineTest {
    private val tmp = Files.createTempDirectory("memcard-test").toFile()
    private val drive = FakeDrive()
    private var now = 1_000_000L

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun device(name: String) = SyncEngine(drive, File(tmp, "cache-$name"), name, clock = { now++ })

    private fun folder(name: String) = File(tmp, "saves-$name").apply { mkdirs() }

    private fun File.put(path: String, text: String) {
        File(this, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    @Test
    fun firstCommitCreatesManifestAndUnchangedSkips() {
        val pc = device("pc")
        val dir = folder("pc").apply { put("SAVEDATA/ULUS1/DATA.BIN", "slot1") }
        var link = pc.createGame("MHFU", dir)

        val first = pc.commit(link, label = "primer save")
        assertIs<CommitOutcome.Uploaded>(first)
        link = first.link
        assertEquals("primer save", first.version.label)
        assertEquals(1, drive.readManifest(link.gameId)!!.manifest.versions.size)

        assertIs<CommitOutcome.Unchanged>(pc.commit(link))

        dir.put("SAVEDATA/ULUS1/DATA.BIN", "slot1 mas avanzado")
        val second = pc.commit(link)
        assertIs<CommitOutcome.Uploaded>(second)
        assertEquals(link.head, second.version.parent)
        assertEquals(2, drive.uploads)
    }

    @Test
    fun otherDeviceSeesNewerAndRestores() {
        val pc = device("pc")
        val switch = device("switch")
        val pcDir = folder("pc").apply { put("a.sav", "v1") }
        var pcLink = (pc.commit(pc.createGame("RE4", pcDir)) as CommitOutcome.Uploaded).link

        val cloud = switch.listCloudGames().single()
        val swDir = folder("switch")
        var swLink = switch.linkFromCloud(cloud, swDir)
        val st = switch.state(swLink)
        assertNull(st.head)
        assertEquals(1, st.otherPaths.size)

        swLink = switch.restore(swLink, st.otherPaths.first().id)
        assertEquals("v1", File(swDir, "a.sav").readText())
        swDir.put("a.sav", "v2 krauser")
        swLink = (switch.commit(swLink) as CommitOutcome.Uploaded).link

        val pcState = pc.state(pcLink)
        assertEquals(listOf(swLink.head), pcState.newer.map { it.id })
        assertEquals(false, pcState.localChanged)

        pcLink = pc.restore(pcLink, pcState.newer.first().id)
        assertEquals("v2 krauser", File(pcDir, "a.sav").readText())
        assertEquals(swLink.head, pcLink.head)
        assertTrue(pc.backupDir(pcLink.gameId).listFiles()!!.isNotEmpty(), "debe quedar respaldo")
    }

    @Test
    fun playingOldVersionAsksForForkAndDeclineKeepsLocal() {
        val pc = device("pc")
        val lap = device("lap")
        val pcDir = folder("pc").apply { put("a.sav", "base") }
        var pcLink = (pc.commit(pc.createGame("RE3", pcDir)) as CommitOutcome.Uploaded).link

        var lapLink = lap.linkFromCloud(lap.listCloudGames().single(), folder("lap"))
        lapLink = lap.restore(lapLink, pcLink.head!!)
        File(lapLink.folder, "a.sav").writeText("nemesis 1")
        lapLink = (lap.commit(lapLink) as CommitOutcome.Uploaded).link

        // La PC siguio jugando desde "base" sin bajar lo de la laptop: abriria otro camino.
        pcDir.put("a.sav", "otra ruta")
        val ask = pc.commit(pcLink)
        assertIs<CommitOutcome.NeedsFork>(ask)
        pcLink = pc.declinePending(ask.link)

        pcDir.put("a.sav", "otra ruta, mas avanzada")
        val kept = pc.commit(pcLink)
        assertIs<CommitOutcome.KeptLocal>(kept)
        pcLink = kept.link
        assertEquals(2, drive.readManifest(pcLink.gameId)!!.manifest.versions.size, "no se subio nada")

        val up = pc.commit(pcLink, allowFork = true)
        assertIs<CommitOutcome.Uploaded>(up)
        val m = drive.readManifest(pcLink.gameId)!!.manifest
        assertEquals(2, History(m.versions).tips().size)
        val st = pc.state(up.link)
        assertEquals(listOf(lapLink.head), st.otherPaths.map { it.id })
        assertTrue(st.newer.isEmpty())
    }

    @Test
    fun conflictIsMergedAndRetried() {
        val pc = device("pc")
        val dir = folder("pc").apply { put("a.sav", "1") }
        val link = (pc.commit(pc.createGame("DS", dir)) as CommitOutcome.Uploaded).link

        // Justo antes de que la PC escriba, otro dispositivo agrega una etiqueta (gana la carrera).
        drive.beforeManifestWrite = {
            val cur = drive.readManifest(link.gameId)!!
            drive.writeManifest(link.gameId, cur.manifest.withLabel(link.head!!, "de otro lado", 5), cur.version)
        }
        dir.put("a.sav", "2")
        val res = pc.commit(link)
        assertIs<CommitOutcome.Uploaded>(res)
        val m = drive.readManifest(link.gameId)!!.manifest
        assertEquals(2, m.versions.size)
        assertEquals("de otro lado", m.find(link.head)!!.label)
    }

    @Test
    fun linkingToCloudWithLocalSaveAsksBeforeNewPath() {
        val pc = device("pc")
        val lap = device("lap")
        val link = (pc.commit(pc.createGame("FF", folder("pc").apply { put("a", "x") })) as CommitOutcome.Uploaded).link
        val lapDir = folder("lap").apply { put("a", "algo distinto") }
        val lapLink = lap.linkFromCloud(lap.listCloudGames().single(), lapDir)
        assertIs<CommitOutcome.NeedsFork>(lap.commit(lapLink))
        assertEquals(link.gameId, lapLink.gameId)
    }

    @Test
    fun manifestMergeKeepsNewestLabel() {
        val v = SaveVersion("A", null, "h", 1, 1, 1, "pc", 1)
        val a = GameManifest(gameId = "g", name = "x", versions = listOf(v.copy(label = "viejo", labelAt = 1)))
        val b = GameManifest(gameId = "g", name = "y", nameAt = 3, versions = listOf(v.copy(label = "nuevo", labelAt = 2), v.copy(id = "B", parent = "A", createdAt = 2)))
        val m = GameManifest.merge(a, b)!!
        assertEquals("nuevo", m.find("A")!!.label)
        assertEquals("y", m.name)
        assertEquals(listOf("A", "B"), m.versions.map { it.id })
        assertEquals(m, GameManifest.fromJson(m.toJson()))
    }

    @Test
    fun ulidSortsByTime() {
        val a = Ulid.next(1000)
        val b = Ulid.next(2000)
        assertTrue(Ulid.isValid(a) && Ulid.isValid(b))
        assertTrue(a < b)
        assertFalse(Ulid.isValid("x"))
    }
}
