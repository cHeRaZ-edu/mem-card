package com.cheraz.memcard.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cheraz.memcard.core.engine.SourceChangedException
import com.cheraz.memcard.core.history.GameManifest
import com.cheraz.memcard.core.history.SaveVersion
import com.cheraz.memcard.core.sync.CommitOutcome
import com.cheraz.memcard.core.sync.DriveSettings
import com.cheraz.memcard.core.sync.GameLink
import com.cheraz.memcard.core.sync.GameState
import com.cheraz.memcard.core.sync.MuveCli
import com.cheraz.memcard.core.sync.SyncEngine
import com.cheraz.memcard.source.saf.SafSaveFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GameUi(
    val link: GameLink,
    val state: GameState? = null,
    val busy: String? = null,
    val error: String? = null,
    val askLabel: SaveVersion? = null,
)

data class ForkAsk(val gameId: String, val name: String, val version: SaveVersion)

/**
 * Lo mismo que el AppController de desktop, para Android: muve va dentro del APK (libmuve.so) y
 * las carpetas son arboles de SAF. Sin watcher (SAF no avisa cambios): mientras la pantalla esta
 * abierta revisa las carpetas cada [SCAN_MS] y la nube cada [DriveSettings.pollMinutes].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveController private constructor(private val app: Context) {
    private val settingsFile = File(app.filesDir, "drive-settings.json")
    var settings by mutableStateOf(DriveSettings.load(settingsFile, DriveSettings(device = Build.MODEL ?: "android")))
        private set
    val games = mutableStateMapOf<String, GameUi>()
    var forkAsk by mutableStateOf<ForkAsk?>(null)
        private set
    val log = mutableStateListOf<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val proxy by lazy { ConnectProxy() }
    val muve = MuveCli({ muveConfig() }, File(app.filesDir, "muve"))
    private var engine = newEngine()
    private var loops: Job? = null

    init {
        settings.games.forEach { games[it.gameId] = GameUi(it) }
    }

    private fun muveConfig(): MuveCli.MuveConfig {
        val s = settings
        val exe = File(app.applicationInfo.nativeLibraryDir, "libmuve.so").absolutePath
        val home = app.filesDir.absolutePath
        return MuveCli.MuveConfig(
            executable = exe,
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

    private fun newEngine() = SyncEngine(
        drive = muve,
        cacheDir = File(app.filesDir, "cache"),
        device = settings.device.ifBlank { "android" },
        folderOf = { SafSaveFolder(app, Uri.parse(it.folder)) },
    )

    /** La pantalla esta visible: revisar carpetas y nube. */
    fun resume() {
        if (loops?.isActive == true) return
        loops = scope.launch {
            launch { scanLoop() }
            launch { pollLoop() }
        }
    }

    fun pause() {
        loops?.cancel()
        loops = null
    }

    // ---------- configuracion ----------

    fun updateSettings(s: DriveSettings) {
        val deviceChanged = s.device != settings.device
        settings = s
        DriveSettings.save(settingsFile, s)
        if (deviceChanged) engine = newEngine()
    }

    fun testConnection(onResult: (String) -> Unit) {
        scope.launch {
            val msg = try {
                withContext(io) {
                    val v = muve.run("version").let { if (it.code == 0) it.out.trim() else it.err }
                    "Conectado ($v). Juegos en la nube: ${muve.listGameIds().size}"
                }
            } catch (e: Exception) {
                "Error: ${e.message ?: e}"
            }
            onResult(msg)
        }
    }

    // ---------- juegos ----------

    /** Guarda el permiso de la carpeta para que siga sirviendo despues de reiniciar el celular. */
    fun keepPermission(uri: Uri) {
        app.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    fun addGame(name: String, tree: Uri) {
        keepPermission(tree)
        val link = engine.createGame(name, tree.toString())
        saveLink(link)
        note("Juego agregado: ${link.name}. Se sube al guardar (revisa cada ${SCAN_MS / 1000} s con la app abierta) o con «Subir ahora».")
    }

    fun linkFromCloud(manifest: GameManifest, tree: Uri) {
        keepPermission(tree)
        val link = engine.linkFromCloud(manifest, tree.toString())
        saveLink(link)
        refresh(link.gameId)
    }

    fun listCloudGames(onResult: (List<GameManifest>?, String?) -> Unit) {
        scope.launch {
            try {
                val list = withContext(io) { engine.listCloudGames() }
                onResult(list.filter { it.gameId !in games.keys }, null)
            } catch (e: Exception) {
                onResult(null, e.message ?: e.toString())
            }
        }
    }

    fun removeGame(gameId: String) {
        games.remove(gameId)
        updateSettings(settings.copy(games = settings.games.filter { it.gameId != gameId }))
    }

    fun setAuto(gameId: String, upload: Boolean? = null, download: Boolean? = null) {
        val link = games[gameId]?.link ?: return
        saveLink(link.copy(autoUpload = upload ?: link.autoUpload, autoDownload = download ?: link.autoDownload))
    }

    fun refresh(gameId: String) = gameOp(gameId, "revisando") { link ->
        val st = engine.state(link)
        update(gameId) { it.copy(state = st) }
        maybeAutoDownload(link, st)
        null
    }

    fun uploadNow(gameId: String, allowFork: Boolean = false) = gameOp(gameId, "subiendo") { link ->
        handleCommit(engine.commit(link, allowFork = allowFork))
    }

    fun restore(gameId: String, versionId: String) = gameOp(gameId, "restaurando") { link ->
        val updated = engine.restore(link, versionId)
        saveLinkMain(updated)
        update(gameId) { it.copy(state = engine.state(updated, remote = false)) }
        "Restaurado: ${link.name} (${describe(engine.cachedManifest(gameId)?.find(versionId))})"
    }

    fun setLabel(gameId: String, versionId: String, label: String) = gameOp(gameId, "guardando etiqueta") { link ->
        engine.setLabel(link, versionId, label)
        update(gameId) { it.copy(askLabel = null, state = engine.state(link, remote = false)) }
        null
    }

    fun skipLabel(gameId: String) {
        games[gameId]?.let { games[gameId] = it.copy(askLabel = null) }
    }

    fun acceptFork() {
        val ask = forkAsk ?: return
        forkAsk = null
        gameOp(ask.gameId, "subiendo camino nuevo") { link -> handleCommit(engine.uploadPending(link)) }
    }

    fun declineFork() {
        val ask = forkAsk ?: return
        forkAsk = null
        val link = games[ask.gameId]?.link ?: return
        saveLink(engine.declinePending(link))
        note("${ask.name}: la partida se queda solo en este celular (no se abrio otro camino).")
    }

    fun manifest(gameId: String): GameManifest? = engine.cachedManifest(gameId)

    // ---------- bucles ----------

    private suspend fun scanLoop() {
        while (true) {
            delay(SCAN_MS)
            val links = games.values.filter { it.busy == null && forkAsk?.gameId != it.link.gameId }
                .map { it.link }.filter { it.autoUpload && it.head != null }
            val changed = withContext(io) {
                links.filter { l -> runCatching { engine.localHash(l) != l.headHash }.getOrDefault(false) }
            }
            // Esperar un poco por si el emulador sigue escribiendo; el snapshot ademas falla si algo cambia a medias.
            if (changed.isEmpty()) continue
            delay(STABLE_MS)
            changed.forEach { uploadNow(it.gameId)?.join() }
        }
    }

    private suspend fun pollLoop() {
        while (true) {
            if (settings.configured) games.keys.toList().forEach { refresh(it)?.join() }
            delay(settings.pollMinutes.coerceAtLeast(1) * 60_000L)
        }
    }

    private suspend fun maybeAutoDownload(link: GameLink, st: GameState) {
        if (!link.autoDownload || st.newer.size != 1 || st.localChanged != false) return
        val v = st.newer.first()
        val updated = engine.restore(link, v.id)
        saveLinkMain(updated)
        update(link.gameId) { it.copy(state = engine.state(updated, remote = false)) }
        withContext(Dispatchers.Main) { note("${link.name}: se bajo sola la version de ${v.device}.") }
    }

    // ---------- internos ----------

    private suspend fun handleCommit(out: CommitOutcome): String? {
        saveLinkMain(out.link)
        val id = out.link.gameId
        return when (out) {
            is CommitOutcome.Unchanged -> {
                update(id) { it.copy(state = engine.state(out.link, remote = false)) }
                null
            }
            is CommitOutcome.Uploaded -> {
                update(id) { it.copy(askLabel = out.version, state = engine.state(out.link, remote = false)) }
                "${out.link.name}: subida la partida de ${describeTime(out.version.createdAt)}"
            }
            is CommitOutcome.NeedsFork -> {
                withContext(Dispatchers.Main) { forkAsk = ForkAsk(id, out.link.name, out.version) }
                null
            }
            is CommitOutcome.KeptLocal -> {
                update(id) { it.copy(state = engine.state(out.link, remote = false)) }
                null
            }
        }
    }

    private fun gameOp(gameId: String, what: String, block: suspend (GameLink) -> String?): Job? {
        val g = games[gameId] ?: return null
        if (g.busy != null) return null
        games[gameId] = g.copy(busy = what, error = null)
        return scope.launch {
            try {
                val msg = withContext(io) { block(games[gameId]?.link ?: g.link) }
                msg?.let { note(it) }
                update(gameId) { it.copy(error = null) }
            } catch (e: SourceChangedException) {
                // el emulador sigue escribiendo: el siguiente escaneo lo intenta de nuevo
            } catch (e: Exception) {
                update(gameId) { it.copy(error = e.message ?: e.toString()) }
                note("${g.link.name}: ${e.message ?: e}")
            } finally {
                update(gameId) { it.copy(busy = null) }
            }
        }
    }

    private suspend fun update(gameId: String, f: (GameUi) -> GameUi) {
        withContext(Dispatchers.Main) { games[gameId]?.let { games[gameId] = f(it) } }
    }

    private suspend fun saveLinkMain(link: GameLink) = withContext(Dispatchers.Main) { saveLink(link) }

    private fun saveLink(link: GameLink) {
        val list = settings.games.filter { it.gameId != link.gameId } + link
        games[link.gameId] = (games[link.gameId] ?: GameUi(link)).copy(link = link)
        updateSettings(settings.copy(games = list.sortedBy { it.name.lowercase() }))
    }

    private fun note(msg: String) {
        log.add(0, "${TIME.format(Date())}  $msg")
        while (log.size > 200) log.removeAt(log.lastIndex)
    }

    companion object {
        const val SCAN_MS = 30_000L
        const val STABLE_MS = 5_000L
        private val TIME = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val DATE = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault())

        @Volatile
        private var instance: DriveController? = null

        fun get(context: Context): DriveController =
            instance ?: synchronized(this) {
                instance ?: DriveController(context.applicationContext).also { instance = it }
            }

        fun describeTime(ms: Long): String = DATE.format(Date(ms))

        fun describe(v: SaveVersion?): String =
            if (v == null) "?" else buildString {
                append(describeTime(v.createdAt)).append(" · ").append(v.device)
                if (v.label.isNotBlank()) append(" · ").append(v.label)
            }

        /** "content://...tree/primary%3APSP%2FSAVEDATA" → "PSP/SAVEDATA" */
        fun folderLabel(uri: String): String = runCatching {
            val id = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(uri))
            id.substringAfter(':').ifEmpty { id }
        }.getOrDefault(uri)
    }
}
