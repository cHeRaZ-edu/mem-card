package com.cheraz.memcard.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cheraz.memcard.core.engine.SourceChangedException
import com.cheraz.memcard.core.history.GameManifest
import com.cheraz.memcard.core.history.SaveVersion
import com.cheraz.memcard.core.sync.CommitOutcome
import com.cheraz.memcard.core.sync.GameLink
import com.cheraz.memcard.core.sync.GameState
import com.cheraz.memcard.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Lo que la UI muestra de cada juego. */
data class GameUi(
    val link: GameLink,
    val state: GameState? = null,
    val busy: String? = null,
    val error: String? = null,
    /** Version recien subida a la que aun no se le pone etiqueta ("¿que paso en esta sesion?"). */
    val askLabel: SaveVersion? = null,
)

data class ForkAsk(val gameId: String, val name: String, val version: SaveVersion)

/**
 * Cerebro de la app: guarda la config, vigila carpetas, sube cambios cuando el emulador termina de
 * escribir y revisa la nube cada [AppSettings.pollMinutes]. Todo el trabajo pesado corre en un solo
 * hilo de fondo ([io]); el estado para la UI se cambia en el hilo de Swing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppController(private val store: SettingsStore = SettingsStore()) {
    var settings by mutableStateOf(store.load())
        private set
    val games = mutableStateMapOf<String, GameUi>()
    var forkAsk by mutableStateOf<ForkAsk?>(null)
        private set
    val log = mutableStateListOf<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val io = Dispatchers.IO.limitedParallelism(1)
    val muve = MuveCli({ settings }, File(AppPaths.home, "muve"))
    private var engine = newEngine()
    private val dirty = ConcurrentHashMap<String, Long>()
    private val watcher = FolderWatcher { id -> dirty[id] = System.currentTimeMillis() }
    private var loops: Job? = null

    private fun newEngine() = SyncEngine(muve, AppPaths.cache, settings.device)

    fun start() {
        watcher.start()
        settings.games.forEach { g ->
            games[g.gameId] = GameUi(g)
            watcher.watch(g.gameId, File(g.folder))
        }
        loops = scope.launch {
            launch { quietLoop() }
            launch { pollLoop() }
        }
    }

    fun stop() {
        watcher.close()
        scope.cancel()
    }

    // ---------- configuracion ----------

    fun updateSettings(s: AppSettings) {
        val deviceChanged = s.device != settings.device
        settings = s
        store.save(s)
        if (deviceChanged) engine = newEngine()
    }

    fun testConnection(onResult: (String) -> Unit) {
        scope.launch {
            val msg = try {
                withContext(io) { "Conectado (${muve.version()}). Juegos en la nube: ${muve.check()}" }
            } catch (e: Exception) {
                "Error: ${e.message ?: e}"
            }
            onResult(msg)
        }
    }

    // ---------- juegos ----------

    fun addGame(name: String, folder: File) {
        val link = engine.createGame(name, folder)
        saveLink(link)
        watcher.watch(link.gameId, folder)
        note("Juego agregado: ${link.name}. Se sube en cuanto lo guardes (o con «Subir ahora»).")
    }

    fun linkFromCloud(manifest: GameManifest, folder: File) {
        val link = engine.linkFromCloud(manifest, folder)
        saveLink(link)
        watcher.watch(link.gameId, folder)
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

    /** Solo lo quita de este dispositivo; la carpeta y la nube quedan igual. */
    fun removeGame(gameId: String) {
        watcher.unwatch(gameId)
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
        saveLinkFromIo(updated)
        dirty.remove(gameId)
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
        note("${ask.name}: la partida se queda solo en esta PC (no se abrio otro camino).")
    }

    fun manifest(gameId: String): GameManifest? = engine.cachedManifest(gameId)

    // ---------- bucles ----------

    /** Sube cuando la carpeta lleva [QUIET_MS] sin cambios (el emulador ya termino de escribir). */
    private suspend fun quietLoop() {
        var lastScan = 0L
        while (true) {
            delay(2_000)
            val now = System.currentTimeMillis()
            if (now - lastScan > SCAN_MS) {
                lastScan = now
                scanForChanges()
            }
            for ((id, t) in dirty.entries.toList()) {
                if (now - t < QUIET_MS) continue
                dirty.remove(id, t)
                val g = games[id] ?: continue
                // Sin version base (recien enlazado de la nube) no se sube solo: primero hay que decidir.
                if (!g.link.autoUpload || g.link.head == null || g.busy != null || forkAsk?.gameId == id) continue
                uploadNow(id)
            }
        }
    }

    /** Respaldo por si el watcher se pierde un evento: compara el hash de cada carpeta. */
    private suspend fun scanForChanges() {
        val links = games.values.map { it.link }.filter { it.autoUpload && it.head != null }
        val changed = withContext(io) {
            links.filter { l -> runCatching { engine.localHash(l) != l.headHash }.getOrDefault(false) }
        }
        changed.forEach { dirty.putIfAbsent(it.gameId, 0L) }
    }

    private suspend fun pollLoop() {
        delay(1_000)
        while (true) {
            if (settings.configured) games.keys.toList().forEach { refresh(it)?.join() }
            delay(settings.pollMinutes.coerceAtLeast(1) * 60_000L)
        }
    }

    private suspend fun maybeAutoDownload(link: GameLink, st: GameState) {
        if (!link.autoDownload || st.newer.size != 1 || st.localChanged != false) return
        val v = st.newer.first()
        val updated = engine.restore(link, v.id)
        saveLinkFromIo(updated)
        dirty.remove(link.gameId)
        update(link.gameId) { it.copy(state = engine.state(updated, remote = false)) }
        withContext(Dispatchers.Main) { note("${link.name}: se bajo sola la version de ${v.device}.") }
    }

    // ---------- internos ----------

    private suspend fun handleCommit(out: CommitOutcome): String? {
        saveLinkFromIo(out.link)
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

    /**
     * Corre [block] en el hilo de fondo con el link mas reciente del juego, marcando el juego ocupado.
     * Si [block] regresa un texto, va al registro.
     */
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
                dirty[gameId] = System.currentTimeMillis() // el emulador sigue escribiendo: se reintenta luego
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

    private suspend fun saveLinkFromIo(link: GameLink) = withContext(Dispatchers.Main) { saveLink(link) }

    private fun saveLink(link: GameLink) {
        val list = settings.games.filter { it.gameId != link.gameId } + link
        games[link.gameId] = (games[link.gameId] ?: GameUi(link)).copy(link = link)
        updateSettings(settings.copy(games = list.sortedBy { it.name.lowercase() }))
    }

    private fun note(msg: String) {
        log.add(0, "${TIME.format(Instant.now())}  $msg")
        while (log.size > 200) log.removeAt(log.lastIndex)
    }

    companion object {
        const val QUIET_MS = 6_000L
        const val SCAN_MS = 60_000L
        private val TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").withZone(ZoneId.systemDefault())

        fun describeTime(ms: Long): String = DATE.format(Instant.ofEpochMilli(ms))

        fun describe(v: SaveVersion?): String =
            if (v == null) "?" else buildString {
                append(describeTime(v.createdAt)).append(" · ").append(v.device)
                if (v.label.isNotBlank()) append(" · ").append(v.label)
            }
    }
}
