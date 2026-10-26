package com.cheraz.memcard.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cheraz.memcard.core.app.SyncController
import com.cheraz.memcard.core.app.SyncHost
import com.cheraz.memcard.core.history.GameManifest
import com.cheraz.memcard.core.sync.DriveSettings
import com.cheraz.memcard.core.sync.GameLink
import com.cheraz.memcard.core.sync.MuveCli
import com.cheraz.memcard.core.sync.SyncEngine
import com.cheraz.memcard.source.saf.SafSaveFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lo propio de Android: config, muve dentro del APK (libmuve.so) con su proxy para DNS, y carpetas
 * de SAF. La logica de juegos y partidas vive en [SyncController] (compartido con desktop). Sin
 * watcher: SAF no avisa cambios, asi que mientras la pantalla esta abierta revisa cada 30 s.
 */
class DriveController private constructor(private val app: Context) {
    private val settingsFile = File(app.filesDir, "drive-settings.json")
    var settings by mutableStateOf(DriveSettings.load(settingsFile, DriveSettings(device = Build.MODEL ?: "android")))
        private set

    private val proxy by lazy { ConnectProxy() }
    val muve = MuveCli({ muveConfig() }, File(app.filesDir, "muve"))

    private val host = object : SyncHost {
        override val configured get() = settings.configured
        override val pollMinutes get() = settings.pollMinutes

        override fun saveGames(games: List<GameLink>) {
            val s = settings.copy(games = games)
            settings = s
            DriveSettings.save(settingsFile, s)
        }
    }

    val sync = SyncController(
        host = host,
        initialGames = settings.games,
        engineFactory = {
            SyncEngine(
                drive = muve,
                cacheDir = File(app.filesDir, "cache"),
                device = settings.device.ifBlank { "android" },
                folderOf = { SafSaveFolder(app, Uri.parse(it.folder)) },
            )
        },
        main = Dispatchers.Main,
        scanMs = 30_000,
        quietMs = 5_000,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun muveConfig(): MuveCli.MuveConfig {
        val s = settings
        val home = app.filesDir.absolutePath
        return MuveCli.MuveConfig(
            executable = File(app.applicationInfo.nativeLibraryDir, "libmuve.so").absolutePath,
            server = s.server,
            apiKey = s.apiKey,
            app = s.app,
            env = mapOf(
                "HOME" to home,
                "XDG_CONFIG_HOME" to "$home/.config",
                "XDG_DATA_HOME" to "$home/.local/share",
                "XDG_CACHE_HOME" to app.cacheDir.absolutePath,
                "TMPDIR" to app.cacheDir.absolutePath,
                "HTTPS_PROXY" to proxy.url,
                "HTTP_PROXY" to proxy.url,
                "SSL_CERT_DIR" to "/system/etc/security/cacerts",
            ),
        )
    }

    /** La pantalla esta visible: revisar carpetas y nube. */
    fun resume() = sync.start()

    fun pause() = sync.stop()

    fun updateSettings(s: DriveSettings) {
        val deviceChanged = s.device != settings.device
        val merged = s.copy(games = settings.games, gamesFormat = settings.gamesFormat)
        settings = merged
        DriveSettings.save(settingsFile, merged)
        if (deviceChanged) sync.reloadEngine()
    }

    fun testConnection(onResult: (String) -> Unit) {
        scope.launch {
            val msg = try {
                withContext(Dispatchers.IO) {
                    val v = muve.run("version").let { if (it.code == 0) it.out.trim() else it.err }
                    "Conectado ($v). Juegos en la nube: ${muve.listGameIds().size}"
                }
            } catch (e: Exception) {
                "Error: ${e.message ?: e}"
            }
            onResult(msg)
        }
    }

    /** Guarda el permiso de la carpeta para que siga sirviendo despues de reiniciar el celular. */
    private fun keepPermission(uri: Uri) {
        app.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    fun addGame(name: String, tree: Uri): String? {
        keepPermission(tree)
        return sync.addGame(name, tree.toString())
    }

    fun linkFromCloud(manifest: GameManifest, tree: Uri): String? {
        keepPermission(tree)
        return sync.linkFromCloud(manifest, tree.toString())
    }

    companion object {
        @Volatile
        private var instance: DriveController? = null

        fun get(context: Context): DriveController =
            instance ?: synchronized(this) {
                instance ?: DriveController(context.applicationContext).also { instance = it }
            }

        /** "content://...tree/primary%3APSP%2FSAVEDATA" → "PSP/SAVEDATA" */
        fun folderLabel(uri: String): String = runCatching {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(uri))
            id.substringAfter(':').ifEmpty { id }
        }.getOrDefault(uri)
    }
}
