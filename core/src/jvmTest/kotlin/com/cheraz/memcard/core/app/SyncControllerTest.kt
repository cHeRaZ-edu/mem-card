package com.cheraz.memcard.core.app

import com.cheraz.memcard.core.sync.FakeDrive
import com.cheraz.memcard.core.sync.GameLink
import com.cheraz.memcard.core.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SyncControllerTest {
    private val tmp = Files.createTempDirectory("memcard-ctl").toFile()
    private val drive = FakeDrive()
    private var saved: List<GameLink> = emptyList()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun controller(name: String) = SyncController(
        host = object : SyncHost {
            override val configured = true
            override val pollMinutes = 60
            override fun saveGames(games: List<GameLink>) {
                saved = games
            }
        },
        initialGames = emptyList(),
        engineFactory = { SyncEngine(drive, File(tmp, "cache-$name"), name) },
        main = Dispatchers.Default,
        scanMs = 60_000,
        quietMs = 1_000,
    )

    @Test
    fun oneGamePerFolderAndSwitchPartidas() = runBlocking {
        val c = controller("pc")
        val dir = File(tmp, "mhxx").apply { mkdirs() }
        File(dir, "user").writeText("cazador")

        assertNull(c.addGame("MHXX", dir.absolutePath))
        assertNotNull(c.addGame("Otro", dir.absolutePath), "la misma carpeta no puede ser de dos juegos")
        val id = c.gameIds().single()

        c.uploadNow(id)!!.join()
        val cazador = c.games.value.getValue(id).link.partida!!
        assertEquals(1, saved.size)

        File(dir, "user").writeText("cuenta recuperada")
        c.newPartida(id, "Cuenta recuperada")!!.join()
        val g = c.games.value.getValue(id)
        assertEquals(2, g.state!!.partidas.size)
        assertEquals("Cuenta recuperada", g.state!!.current!!.partida.name)

        c.switchPartida(id, cazador)!!.join()
        assertEquals("cazador", File(dir, "user").readText())
        assertEquals(cazador, c.games.value.getValue(id).link.partida)
        assertNull(c.ask.value)
        c.stop()
    }
}
