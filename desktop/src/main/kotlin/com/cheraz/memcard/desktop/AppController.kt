package com.cheraz.memcard.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cheraz.memcard.core.app.SyncController
import com.cheraz.memcard.core.app.SyncHost
import com.cheraz.memcard.core.history.GameManifest
import com.cheraz.memcard.core.sync.GameLink
import com.cheraz.memcard.core.sync.MuveCli
import com.cheraz.memcard.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lo propio de desktop: config en settings.json, muve.exe, y el watcher de carpetas. Toda la logica
 * de juegos y partidas vive en [SyncController] (compartido con Android).
 */
class AppController(private val store: SettingsStore = SettingsStore()) {
    var settings by mutableStateOf(store.load())
        private set

    val muve = MuveCli({ settings.toMuveConfig() }, File(AppPaths.home, "muve"))

    private val host = object : SyncHost {
        override val configured get() = settings.configured
        override val pollMinutes get() = settings.pollMinutes

        override fun saveGames(games: List<GameLink>) {
            val s = settings.copy(games = games)
            settings = s
            store.save(s)
        }
    }

    val sync = SyncController(
        host = host,
        initialGames = settings.games,
        engineFactory = { SyncEngine(muve, AppPaths.cache, settings.device) },
        main = Dispatchers.Main,
        scanMs = 60_000,
        quietMs = 6_000,
    )

    private val watcher = FolderWatcher { id -> sync.markDirty(id) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun start() {
        watcher.start()
        sync.links().forEach { watcher.watch(it.gameId, File(it.folder)) }
        sync.start()
    }

    fun stop() {
        watcher.close()
        sync.stop()
    }

    fun updateSettings(s: AppSettings) {
        val deviceChanged = s.device != settings.device
        val merged = s.copy(games = settings.games, gamesFormat = settings.gamesFormat)
        settings = merged
        store.save(merged)
        if (deviceChanged) sync.reloadEngine()
    }

    fun testConnection(onResult: (String) -> Unit) {
        scope.launch {
            val msg = try {
                withContext(Dispatchers.IO) { "Conectado (${muve.version()}). Juegos en la nube: ${muve.check()}" }
            } catch (e: Exception) {
                "Error: ${e.message ?: e}"
            }
            onResult(msg)
        }
    }

    /** @return un error para mostrar, o null. */
    fun addGame(name: String, folder: File): String? {
        val err = sync.addGame(name, folder.absolutePath)
        if (err == null) watchFolder(folder)
        return err
    }

    fun linkFromCloud(manifest: GameManifest, folder: File): String? {
        val err = sync.linkFromCloud(manifest, folder.absolutePath)
        if (err == null) watchFolder(folder)
        return err
    }

    fun removeGame(gameId: String) {
        watcher.unwatch(gameId)
        sync.removeGame(gameId)
    }

    private fun watchFolder(folder: File) {
        sync.links().firstOrNull { it.folder == folder.absolutePath }?.let { watcher.watch(it.gameId, folder) }
    }
}
