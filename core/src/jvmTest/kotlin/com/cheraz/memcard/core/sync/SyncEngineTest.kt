package com.cheraz.memcard.core.sync

import com.cheraz.memcard.core.history.GameManifest
import com.cheraz.memcard.core.history.Partida
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

// Atajos para las pruebas: carpetas normales.
fun SyncEngine.createGame(name: String, folder: File) = createGame(name, folder.absolutePath)
fun SyncEngine.linkFromCloud(m: GameManifest, folder: File) = linkFromCloud(m, folder.absolutePath)

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
        files.keys.filter { it.startsWith("games/") }.map { it.removePrefix("games/").removeSuffix(".json") }
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

    private fun File.read(path: String) = File(this, path).readText()

    private fun manifest(gameId: String) = drive.readManifest(gameId)!!.manifest

    private fun uploaded(o: CommitOutcome): CommitOutcome.Uploaded {
        assertIs<CommitOutcome.Uploaded>(o)
        return o
    }

    /** PC crea el juego y sube; la laptop lo enlaza y carga la partida. */
    private fun twoDevices(): Triple<GameLink, GameLink, Pair<SyncEngine, SyncEngine>> {
        val pc = device("pc")
        val lap = device("lap")
        val pcLink = uploaded(pc.commit(pc.createGame("RE3", folder("pc").apply { put("a.sav", "base") }))).link
        var lapLink = lap.linkFromCloud(lap.listCloudGames().single(), folder("lap"))
        lapLink = lap.switchPartida(lapLink, pcLink.partida!!)
        return Triple(pcLink, lapLink, pc to lap)
    }

    @Test
    fun firstCommitCreatesDefaultPartidaAndVersionsGoInLine() {
        val pc = device("pc")
        val dir = folder("pc").apply { put("SAVEDATA/DATA.BIN", "slot1") }
        val first = uploaded(pc.commit(pc.createGame("MHFU", dir), label = "primer save"))
        assertEquals(SyncEngine.DEFAULT_PARTIDA, first.newPartida!!.name)
        assertEquals("primer save", first.version.label)
        var link = first.link
        assertEquals(first.newPartida!!.id, link.partida)

        assertIs<CommitOutcome.Unchanged>(pc.commit(link))

        dir.put("SAVEDATA/DATA.BIN", "slot1 mas avanzado")
        val second = uploaded(pc.commit(link))
        assertNull(second.newPartida)
        assertEquals(link.head, second.version.parent)
        assertEquals(link.partida, second.version.partida)
        link = second.link
        val m = manifest(link.gameId)
        assertEquals(1, m.partidas.size)
        assertEquals(link.head, m.tipOf(link.partida!!)!!.id)
    }

    @Test
    fun otherDeviceLoadsPartidaAndSeesNewer() {
        val (pcStart, lapStart, engines) = twoDevices()
        val (pc, lap) = engines
        assertEquals("base", folder("lap").read("a.sav"))
        assertEquals(pcStart.partida, lapStart.partida)

        folder("lap").put("a.sav", "nemesis 1")
        val lapLink = uploaded(lap.commit(lapStart)).link

        val st = pc.state(pcStart)
        assertEquals(lapLink.head, st.newer?.id)
        assertEquals(false, st.localChanged)
        val pcLink = pc.restore(pcStart, st.newer!!.id)
        assertEquals("nemesis 1", folder("pc").read("a.sav"))
        assertTrue(pc.backupDir(pcLink.gameId).listFiles()!!.isNotEmpty(), "debe quedar respaldo")
    }

    @Test
    fun savingOnTopOfAdvancedPartidaAsksForNewPartida() {
        val (pcLink, lapStart, engines) = twoDevices()
        val (pc, lap) = engines
        folder("lap").put("a.sav", "laptop avanzo")
        uploaded(lap.commit(lapStart))

        folder("pc").put("a.sav", "la pc tambien jugo")
        val ask = pc.commit(pcLink)
        assertIs<CommitOutcome.NeedsPartida>(ask)
        assertEquals("lap", ask.tip!!.device)

        val up = uploaded(pc.uploadPendingAsNew(ask.link, "Ruta PC"))
        assertEquals("Ruta PC", up.newPartida!!.name)
        assertEquals(pcLink.head, up.version.parent, "la partida nueva recuerda de donde salio")
        val m = manifest(pcLink.gameId)
        assertEquals(2, m.partidas.size)
        m.partidas.forEach { p -> assertEquals(m.versionsOf(p.id).size, m.versionsOf(p.id).map { it.id }.toSet().size) }

        val st = pc.state(up.link)
        assertEquals("Ruta PC", st.current!!.partida.name)
        assertEquals(2, st.partidas.size)
        assertNull(st.newer)
    }

    @Test
    fun declineKeepsLocalWithoutAskingAgain() {
        val (pcLink, lapStart, engines) = twoDevices()
        val (pc, lap) = engines
        folder("lap").put("a.sav", "x")
        uploaded(lap.commit(lapStart))

        folder("pc").put("a.sav", "y")
        val ask = pc.commit(pcLink)
        assertIs<CommitOutcome.NeedsPartida>(ask)
        var link = pc.declinePending(ask.link)
        folder("pc").put("a.sav", "y mas")
        val kept = pc.commit(link)
        assertIs<CommitOutcome.KeptLocal>(kept)
        link = kept.link
        assertEquals(2, manifest(link.gameId).versions.size, "no se subio nada")
    }

    @Test
    fun oldVersionFromHistoryThenSaveAsks() {
        val pc = device("pc")
        val dir = folder("pc").apply { put("a", "1") }
        var link = uploaded(pc.commit(pc.createGame("FF", dir))).link
        val v1 = link.head!!
        dir.put("a", "2")
        link = uploaded(pc.commit(link)).link
        link = pc.restore(link, v1)
        assertEquals("1", dir.read("a"))
        dir.put("a", "1 otra ruta")
        assertIs<CommitOutcome.NeedsPartida>(pc.commit(link))
    }

    @Test
    fun newPartidaFromCurrentEvenWithoutChanges() {
        val pc = device("pc")
        val dir = folder("pc").apply { put("a", "1") }
        val link = uploaded(pc.commit(pc.createGame("FF", dir))).link
        val copy = uploaded(pc.commit(link, newPartidaName = "Antes del jefe"))
        assertEquals("Antes del jefe", copy.newPartida!!.name)
        assertEquals(link.headHash, copy.version.hash)
        assertEquals(2, manifest(link.gameId).partidas.size)
    }

    @Test
    fun switchPartidaLoadsItsLatestVersion() {
        val pc = device("pc")
        val dir = folder("pc").apply { put("a", "cazador") }
        var link = uploaded(pc.commit(pc.createGame("MHXX", dir))).link
        val cazador = link.partida!!
        dir.put("a", "cuenta recuperada")
        link = uploaded(pc.commit(link, newPartidaName = "Cuenta recuperada")).link
        link = pc.switchPartida(link, cazador)
        assertEquals("cazador", dir.read("a"))
        assertEquals(cazador, link.partida)
        assertIs<CommitOutcome.Unchanged>(pc.commit(link))
    }

    @Test
    fun raceOnSamePartidaBecomesQuestion() {
        val (pcLink, lapStart, engines) = twoDevices()
        val (pc, lap) = engines
        // Justo antes de que la PC escriba, la laptop sube a la misma partida.
        drive.beforeManifestWrite = {
            folder("lap").put("a.sav", "laptop gano")
            uploaded(lap.commit(lapStart))
        }
        folder("pc").put("a.sav", "pc")
        val res = pc.commit(pcLink)
        assertIs<CommitOutcome.NeedsPartida>(res)
        assertEquals("lap", res.tip!!.device)
    }

    @Test
    fun linkingWithLocalSaveAsksBeforeUploading() {
        val pc = device("pc")
        val lap = device("lap")
        uploaded(pc.commit(pc.createGame("FF", folder("pc").apply { put("a", "x") })))
        val lapLink = lap.linkFromCloud(lap.listCloudGames().single(), folder("lap").apply { put("a", "algo distinto") })
        val res = lap.commit(lapLink)
        assertIs<CommitOutcome.NeedsPartida>(res)
        assertNull(res.tip)
    }

    @Test
    fun manifestMergeKeepsNewestLabelAndName() {
        val v = SaveVersion("A", "P", null, "h", 1, 1, 1, "pc", 1)
        val p = Partida("P", "vieja", 1, 1, "pc")
        val a = GameManifest(gameId = "g", name = "x", partidas = listOf(p), versions = listOf(v.copy(label = "viejo", labelAt = 1)))
        val b = GameManifest(
            gameId = "g", name = "y", nameAt = 3,
            partidas = listOf(p.copy(name = "nueva", nameAt = 5)),
            versions = listOf(v.copy(label = "nuevo", labelAt = 2), v.copy(id = "B", parent = "A", createdAt = 2)),
        )
        val m = GameManifest.merge(a, b)!!
        assertEquals("nuevo", m.find("A")!!.label)
        assertEquals("nueva", m.partida("P")!!.name)
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
