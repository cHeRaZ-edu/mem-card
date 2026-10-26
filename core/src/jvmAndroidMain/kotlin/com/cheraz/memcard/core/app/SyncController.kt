package com.cheraz.memcard.core.app

import com.cheraz.memcard.core.engine.SourceChangedException
import com.cheraz.memcard.core.history.GameManifest
import com.cheraz.memcard.core.history.SaveVersion
import com.cheraz.memcard.core.sync.CommitOutcome
import com.cheraz.memcard.core.sync.GameLink
import com.cheraz.memcard.core.sync.GameState
import com.cheraz.memcard.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

/**
 * Hay que preguntar si lo que se guardo es una partida nueva.
 *
 * @param tip la ultima version de la partida en curso (de otro dispositivo, o la que dejaste atras al
 *   cargar una vieja). null = este dispositivo aun no eligio partida y en la nube ya hay.
 */
data class PartidaAsk(val gameId: String, val gameName: String, val version: SaveVersion, val tip: SaveVersion?, val currentPartida: String?)

/** Lo que cada plataforma le da al controlador. */
interface SyncHost {
    val configured: Boolean
    val pollMinutes: Int

    /** Guardar los juegos enlazados de este dispositivo (se llama desde cualquier hilo). */
    fun saveGames(games: List<GameLink>)
}

/**
 * Cerebro comun de desktop y Android: estado de cada juego, subir cuando la carpeta lleva
 * [quietMs] sin cambios, revisar la nube cada [SyncHost.pollMinutes], partidas, historial.
 *
 * El trabajo pesado corre en un solo hilo de fondo; el estado se publica en [StateFlow] para que la
 * UI de cada plataforma lo pinte.
 *
 * @param scanMs cada cuanto comparar el hash de cada carpeta (en Android es la unica forma de ver
 *   cambios; en desktop es el respaldo del watcher, que llama a [markDirty]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncController(
    private val host: SyncHost,
    initialGames: List<GameLink>,
    private val engineFactory: () -> SyncEngine,
    main: CoroutineDispatcher,
    private val scanMs: Long,
    private val quietMs: Long,
) {
    private val _games = MutableStateFlow(initialGames.associate { it.gameId to GameUi(it) })
    val games: StateFlow<Map<String, GameUi>> = _games.asStateFlow()

    private val _ask = MutableStateFlow<PartidaAsk?>(null)
    val ask: StateFlow<PartidaAsk?> = _ask.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val io = Dispatchers.IO.limitedParallelism(1)
    @Volatile
    private var engine = engineFactory()
    private val dirty = ConcurrentHashMap<String, Long>()
    private val saveLock = Any()
    private var loops: Job? = null

    /** Arranca los bucles (revisar carpetas y nube). Se puede llamar varias veces. */
    fun start() {
        if (loops?.isActive == true) return
        loops = scope.launch {
            launch { quietLoop() }
            launch { pollLoop() }
        }
    }

    fun stop() {
        loops?.cancel()
        loops = null
    }

    /** El nombre del dispositivo cambio: rehacer el motor. */
    fun reloadEngine() {
        engine = engineFactory()
    }

    /** El watcher vio un cambio en la carpeta del juego. */
    fun markDirty(gameId: String) {
        dirty[gameId] = System.currentTimeMillis()
    }

    fun gameIds(): Set<String> = _games.value.keys

    fun links(): List<GameLink> = _games.value.values.map { it.link }

    // ---------- juegos ----------

    /** @return un error para mostrar, o null si se agrego. */
    fun addGame(name: String, folder: String): String? {
        folderTaken(folder)?.let { return it }
        val link = engine.createGame(name, folder)
        saveLink(link)
        note("Juego agregado: ${link.name}. Se sube en cuanto lo guardes (o con «Subir ahora»).")
        return null
    }

    fun linkFromCloud(manifest: GameManifest, folder: String): String? {
        folderTaken(folder)?.let { return it }
        if (manifest.gameId in _games.value) return "Ese juego ya esta en este equipo."
        val link = engine.linkFromCloud(manifest, folder)
        saveLink(link)
        refresh(link.gameId)
        return null
    }

    private fun folderTaken(folder: String): String? =
        _games.value.values.firstOrNull { it.link.folder == folder }?.let {
            "Esa carpeta ya es de «${it.link.name}». Un juego por carpeta: agrega ahi otra partida."
        }

    fun listCloudGames(onResult: (List<GameManifest>?, String?) -> Unit) {
        scope.launch {
            try {
                val list = withContext(io) { engine.listCloudGames() }
                onResult(list.filter { it.gameId !in _games.value }, null)
            } catch (e: Exception) {
                onResult(null, e.message ?: e.toString())
            }
        }
    }

    /** Solo lo quita de este dispositivo; la carpeta y la nube quedan igual. */
    fun removeGame(gameId: String) {
        _games.update { it - gameId }
        persist()
    }

    fun setAuto(gameId: String, upload: Boolean? = null, download: Boolean? = null) {
        val link = _games.value[gameId]?.link ?: return
        saveLink(link.copy(autoUpload = upload ?: link.autoUpload, autoDownload = download ?: link.autoDownload))
    }

    fun manifest(gameId: String): GameManifest? = engine.cachedManifest(gameId)

    // ---------- acciones ----------

    fun refresh(gameId: String) = gameOp(gameId, "revisando") { link ->
        val st = engine.state(link)
        update(gameId) { it.copy(state = st) }
        maybeAutoDownload(link, st)
        null
    }

    fun uploadNow(gameId: String) = gameOp(gameId, "subiendo") { link -> handleCommit(engine.commit(link)) }

    /** Guarda lo que hay en la carpeta como partida nueva con ese nombre. */
    fun newPartida(gameId: String, name: String) = gameOp(gameId, "creando partida") { link ->
        handleCommit(engine.commit(link, newPartidaName = name))
    }

    fun renamePartida(gameId: String, partidaId: String, name: String) = gameOp(gameId, "renombrando") { link ->
        engine.renamePartida(link, partidaId, name)
        update(gameId) { it.copy(state = engine.state(link, remote = false)) }
        null
    }

    /**
     * Cambia la partida en curso. Primero sube lo que haya cambiado en la actual; si eso necesita una
     * decision (partida nueva o no), pregunta y no cambia todavia.
     */
    fun switchPartida(gameId: String, partidaId: String) = gameOp(gameId, "cambiando de partida") { start ->
        var link = start
        if (link.head != null) {
            val out = engine.commit(link)
            val msg = handleCommit(out)
            msg?.let { noteMain(it) }
            if (out is CommitOutcome.NeedsPartida) return@gameOp "Antes de cambiar, decide que hacer con lo que guardaste."
            link = out.link
        }
        val updated = engine.switchPartida(link, partidaId)
        saveLink(updated)
        dirty.remove(gameId)
        update(gameId) { it.copy(state = engine.state(updated, remote = false)) }
        "${link.name}: partida en curso «${partidaName(updated)}»"
    }

    fun restore(gameId: String, versionId: String) = gameOp(gameId, "cargando") { link ->
        val updated = engine.restore(link, versionId)
        saveLink(updated)
        dirty.remove(gameId)
        update(gameId) { it.copy(state = engine.state(updated, remote = false)) }
        "Cargado: ${link.name} (${describe(engine.cachedManifest(gameId)?.find(versionId))})"
    }

    fun setLabel(gameId: String, versionId: String, label: String) = gameOp(gameId, "guardando etiqueta") { link ->
        engine.setLabel(link, versionId, label)
        update(gameId) { it.copy(askLabel = null, state = engine.state(link, remote = false)) }
        null
    }

    fun skipLabel(gameId: String) = update(gameId) { it.copy(askLabel = null) }

    /** Respuesta a [ask]: con nombre = partida nueva; null = se queda solo en este dispositivo. */
    fun answerAsk(newPartidaName: String?) {
        val ask = _ask.value ?: return
        _ask.value = null
        if (newPartidaName != null) {
            gameOp(ask.gameId, "subiendo partida nueva") { link -> handleCommit(engine.uploadPendingAsNew(link, newPartidaName)) }
        } else {
            val link = _games.value[ask.gameId]?.link ?: return
            saveLink(engine.declinePending(link))
            note("${ask.gameName}: lo guardado se queda solo en este equipo.")
        }
    }

    // ---------- bucles ----------

    private suspend fun quietLoop() {
        var lastScan = System.currentTimeMillis()
        while (true) {
            delay(2_000)
            val now = System.currentTimeMillis()
            if (now - lastScan >= scanMs) {
                lastScan = now
                scanForChanges()
            }
            for ((id, t) in dirty.entries.toList()) {
                if (now - t < quietMs) continue
                dirty.remove(id, t)
                val g = _games.value[id] ?: continue
                // Sin version base (recien enlazado) no se sube solo: primero hay que elegir partida.
                if (!g.link.autoUpload || g.link.head == null || g.busy != null || _ask.value?.gameId == id) continue
                uploadNow(id)?.join()
            }
        }
    }

    /** Compara el hash de cada carpeta contra lo ultimo subido o cargado. */
    private suspend fun scanForChanges() {
        val links = _games.value.values.filter { it.busy == null }.map { it.link }.filter { it.autoUpload && it.head != null }
        val changed = withContext(io) {
            links.filter { l -> runCatching { engine.localHash(l) != l.headHash }.getOrDefault(false) }
        }
        val now = System.currentTimeMillis()
        changed.forEach { dirty.putIfAbsent(it.gameId, now) }
    }

    private suspend fun pollLoop() {
        delay(1_000)
        while (true) {
            if (host.configured) _games.value.keys.toList().forEach { refresh(it)?.join() }
            delay(host.pollMinutes.coerceAtLeast(1) * 60_000L)
        }
    }

    private fun maybeAutoDownload(link: GameLink, st: GameState) {
        val v = st.newer ?: return
        if (!link.autoDownload || st.localChanged != false) return
        val updated = engine.restore(link, v.id)
        saveLink(updated)
        dirty.remove(link.gameId)
        update(link.gameId) { it.copy(state = engine.state(updated, remote = false)) }
        noteMain("${link.name}: se bajo sola la version de ${v.device}.")
    }

    // ---------- internos ----------

    private fun handleCommit(out: CommitOutcome): String? {
        saveLink(out.link)
        val id = out.link.gameId
        return when (out) {
            is CommitOutcome.Unchanged -> {
                update(id) { it.copy(state = engine.state(out.link, remote = false)) }
                null
            }
            is CommitOutcome.Uploaded -> {
                update(id) { it.copy(askLabel = out.version, state = engine.state(out.link, remote = false)) }
                val where = out.newPartida?.let { " como partida nueva «${it.name}»" } ?: " a «${partidaName(out.link)}»"
                "${out.link.name}: subida la partida de ${time(out.version.createdAt)}$where"
            }
            is CommitOutcome.NeedsPartida -> {
                val current = engine.cachedManifest(id)?.partida(out.link.partida)?.name
                _ask.value = PartidaAsk(id, out.link.name, out.version, out.tip, current)
                update(id) { it.copy(state = engine.state(out.link, remote = false)) }
                null
            }
            is CommitOutcome.KeptLocal -> {
                update(id) { it.copy(state = engine.state(out.link, remote = false)) }
                null
            }
        }
    }

    private fun partidaName(link: GameLink) = engine.cachedManifest(link.gameId)?.partida(link.partida)?.name ?: "?"

    /** Corre [block] en el hilo de fondo con el link mas reciente del juego, marcandolo ocupado. */
    private fun gameOp(gameId: String, what: String, block: suspend (GameLink) -> String?): Job? {
        val g = _games.value[gameId] ?: return null
        if (g.busy != null) return null
        update(gameId) { it.copy(busy = what, error = null) }
        return scope.launch {
            try {
                val msg = withContext(io) { block(_games.value[gameId]?.link ?: g.link) }
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

    private fun update(gameId: String, f: (GameUi) -> GameUi) {
        _games.update { m -> m[gameId]?.let { m + (gameId to f(it)) } ?: m }
    }

    private fun saveLink(link: GameLink) {
        _games.update { m -> m + (link.gameId to ((m[link.gameId] ?: GameUi(link)).copy(link = link))) }
        persist()
    }

    private fun persist() = synchronized(saveLock) {
        host.saveGames(_games.value.values.map { it.link }.sortedBy { it.name.lowercase() })
    }

    private fun note(msg: String) {
        val line = "${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}  $msg"
        _log.update { (listOf(line) + it).take(200) }
    }

    private fun noteMain(msg: String) = note(msg)

    companion object {
        fun time(ms: Long): String = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(ms))

        fun describe(v: SaveVersion?): String =
            if (v == null) "?" else buildString {
                append(time(v.createdAt)).append(" · ").append(v.device)
                if (v.label.isNotBlank()) append(" · ").append(v.label)
            }
    }
}
