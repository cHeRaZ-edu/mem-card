package com.cheraz.memcard.desktop

import com.cheraz.memcard.core.sync.CommitOutcome
import com.cheraz.memcard.core.sync.DriveConflictException
import com.cheraz.memcard.core.sync.SyncEngine
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Prueba MuveCli contra un muve falso (script de bash) que imita la salida y los errores del real
 * (textos copiados de sdk/cmd/muve y del API). Solo corre en Linux/macOS.
 */
class MuveCliTest {
    private val tmp = Files.createTempDirectory("muvecli").toFile()
    private val store = File(tmp, "store").apply { mkdirs() }
    private val script = File(tmp, "muve").apply {
        writeText(fakeScript().replace("@STORE@", store.absolutePath))
        setExecutable(true)
    }
    private val windows = System.getProperty("os.name").lowercase().contains("win")

    private fun fakeScript() = javaClass.getResource("/fake-muve.sh")!!.readText()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun cli(name: String) = MuveCli(
        { AppSettings(server = "https://x.test/api", apiKey = "k", device = name, muvePath = script.absolutePath) },
        File(tmp, "work-$name"),
    )

    @Test
    fun manifestRoundTripAndConflict() {
        if (windows) return
        val pc = cli("pc")
        val engine = SyncEngine(pc, File(tmp, "cache-pc"), "pc")
        assertNull(pc.readManifest("NOPE"))

        val dir = File(tmp, "saves").apply { mkdirs() }
        File(dir, "DATA.BIN").writeText("slot")
        val up = engine.commit(engine.createGame("MHFU", dir), label = "Rathalos")
        assertIs<CommitOutcome.Uploaded>(up)

        val remote = pc.readManifest(up.link.gameId)!!
        assertEquals(1, remote.version)
        assertEquals("Rathalos", remote.manifest.versions.single().label)
        assertEquals(listOf(up.link.gameId), pc.listGameIds())

        // expected_version vieja → conflicto; crear algo que ya existe → conflicto.
        assertFailsWith<DriveConflictException> { pc.writeManifest(up.link.gameId, remote.manifest, 7) }
        assertFailsWith<DriveConflictException> { pc.writeManifest(up.link.gameId, remote.manifest, null) }

        // Otro equipo enlaza, baja y sube: el blob se reutiliza y el manifiesto pasa a v2.
        val lapCli = cli("lap")
        val lap = SyncEngine(lapCli, File(tmp, "cache-lap"), "lap")
        val lapDir = File(tmp, "saves-lap")
        var link = lap.linkFromCloud(lap.listCloudGames().single(), lapDir)
        link = lap.restore(link, up.version.id)
        assertEquals("slot", File(lapDir, "DATA.BIN").readText())
        File(lapDir, "DATA.BIN").writeText("slot 2")
        assertIs<CommitOutcome.Uploaded>(lap.commit(link))
        assertEquals(2, pc.readManifest(up.link.gameId)!!.version)
        assertTrue(engine.state(up.link).newer.isNotEmpty())
    }

    @Test
    fun parsesLsAndErrors() {
        val out = "   1.2 KB  v1   26 oct 10:00  heads/01ABC.json\n*L  3.0 MB  v12  1 ene 09:00  blobs/ff.zip\n"
        assertEquals(listOf("heads/01ABC.json", "blobs/ff.zip"), MuveCli.parseLs(out))
        assertTrue(MuveCli.isConflict("muve: La version actual es la 3, no la 2. No se piso nada.\n      (version actual en la nube: 3)"))
        assertTrue(MuveCli.isNotFound("muve: No existe ese archivo."))
        assertEquals("No existe ese archivo.", MuveCli.clean("muve: No existe ese archivo.\n"))
    }
}
