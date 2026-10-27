package com.cheraz.memcard.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.cheraz.memcard.core.app.GameUi
import com.cheraz.memcard.core.app.PartidaAsk
import com.cheraz.memcard.core.app.SyncController
import com.cheraz.memcard.core.app.SyncController.Companion.describe
import com.cheraz.memcard.core.app.SyncController.Companion.time
import com.cheraz.memcard.core.history.GameManifest
import com.cheraz.memcard.core.history.SaveVersion
import java.io.File
import javax.swing.JFileChooser

@Composable
fun App(c: AppController) {
    var showSettings by remember { mutableStateOf(!c.settings.configured) }
    var showAdd by remember { mutableStateOf(false) }
    var showCloud by remember { mutableStateOf(false) }
    var partidasFor by remember { mutableStateOf<String?>(null) }
    val games by c.sync.games.collectAsState()
    val ask by c.sync.ask.collectAsState()
    val log by c.sync.log.collectAsState()

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("mem-card", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Este equipo: ${c.settings.device}" + if (!c.settings.configured) " · sin configurar" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showAdd = true }, enabled = c.settings.configured) { Text("Agregar juego") }
                    OutlinedButton(onClick = { showCloud = true }, enabled = c.settings.configured) { Text("Desde la nube") }
                    TextButton(onClick = { showSettings = true }) { Text("Ajustes") }
                }
            }
            Spacer(Modifier.height(12.dp))

            val list = games.values.sortedBy { it.link.name.lowercase() }
            Box(Modifier.weight(1f)) {
                if (list.isEmpty()) {
                    Text(
                        "Agrega un juego eligiendo la carpeta donde el emulador guarda sus partidas, " +
                            "o enlaza uno que ya subiste desde otro equipo con «Desde la nube».",
                        Modifier.padding(24.dp),
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.link.gameId }) { g -> GameCard(c, g, onPartidas = { partidasFor = g.link.gameId }) }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SelectionContainer {
                Column(Modifier.heightIn(max = 110.dp)) {
                    log.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

    if (showSettings) SettingsDialog(c) { showSettings = false }
    if (showAdd) AddGameDialog(c) { showAdd = false }
    if (showCloud) CloudDialog(c) { showCloud = false }
    partidasFor?.let { id -> PartidasWindow(c, id) { partidasFor = null } }
    ask?.let { AskDialog(c.sync, it) }
}

/** ¿Lo que guardaste es una partida nueva? */
@Composable
fun AskDialog(sync: SyncController, ask: PartidaAsk) {
    var name by remember(ask.version.id) { mutableStateOf("") }
    val why = when {
        ask.tip == null -> "En la nube ya hay partidas de «${ask.gameName}» y en este equipo aun no eliges una."
        else -> "La partida «${ask.currentPartida ?: "?"}» siguio en otro lado o cargaste una version vieja: " +
            "la ultima es ${describe(ask.tip)}."
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("¿Es una partida nueva?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$why\n\nPara no pisar nada, guarda lo tuyo como partida nueva. Si no, se queda solo en este equipo.")
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nombre de la partida nueva") })
            }
        },
        confirmButton = { Button(onClick = { sync.answerAsk(name) }, enabled = name.isNotBlank()) { Text("Guardar como partida nueva") } },
        dismissButton = { TextButton(onClick = { sync.answerAsk(null) }) { Text("Dejarla solo aqui") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameCard(c: AppController, g: GameUi, onPartidas: () -> Unit) {
    val link = g.link
    val st = g.state
    val sync = c.sync
    var confirm by remember { mutableStateOf<SaveVersion?>(null) }
    val err = MaterialTheme.colorScheme.error

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(link.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(link.folder, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onPartidas) {
                    Text("Partida: ${st?.current?.partida?.name ?: "sin elegir"}")
                }
            }
            when {
                g.busy != null -> Text("⏳ ${g.busy}…")
                g.error != null -> Text(g.error!!, color = err)
                st == null -> Text("Sin revisar todavia")
                st.localError != null -> Text("No se pudo leer la carpeta: ${st.localError}", color = err)
                st.current == null && st.partidas.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("En la nube hay ${st.partidas.size} partida(s). Elige cual cargar antes de jugar.", Modifier.weight(1f))
                    Button(onClick = onPartidas) { Text("Elegir partida") }
                }
                st.current == null -> Text("Aun no se ha subido. Juega y guarda, o usa «Subir ahora».")
                st.newer != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Esta partida avanzo en otro equipo: ${describe(st.newer)}", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    Button(onClick = { confirm = st.newer }) { Text("Bajar") }
                }
                else -> Text("Al dia · ${describe(st.head)}")
            }
            if (st?.localChanged == true && st.head != null && g.busy == null) {
                Text("Hay cambios en la carpeta sin subir.", style = MaterialTheme.typography.bodySmall)
            }
            link.pending?.let { p -> Text("Hay una partida guardada solo en este equipo (${time(p.createdAt)}). Puedes subirla desde «Partida».") }

            g.askLabel?.let { v -> LabelRow(sync, link.gameId, v) }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { sync.uploadNow(link.gameId) }, enabled = g.busy == null) { Text("Subir ahora") }
                OutlinedButton(onClick = { sync.refresh(link.gameId) }, enabled = g.busy == null) { Text("Revisar nube") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(link.autoUpload, { sync.setAuto(link.gameId, upload = it) })
                    Text("Subir solo")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(link.autoDownload, { sync.setAuto(link.gameId, download = it) })
                    Text("Bajar solo")
                }
                TextButton(onClick = { c.removeGame(link.gameId) }) { Text("Quitar de este equipo") }
            }
        }
    }

    confirm?.let { v -> LoadConfirm(v, onYes = { sync.restore(link.gameId, v.id); confirm = null }, onNo = { confirm = null }) }
}

@Composable
private fun LabelRow(sync: SyncController, gameId: String, v: SaveVersion) {
    var text by remember(v.id) { mutableStateOf(v.label) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(text, { text = it }, Modifier.weight(1f), label = { Text("¿Que paso en esta sesion?") }, singleLine = true)
        Button(onClick = { sync.setLabel(gameId, v.id, text) }, enabled = text.isNotBlank()) { Text("Guardar") }
        TextButton(onClick = { sync.skipLabel(gameId) }) { Text("Omitir") }
    }
}

@Composable
private fun LoadConfirm(v: SaveVersion, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text("¿Cargar esta version?") },
        text = { Text("${describe(v)}\n\nCierra el emulador antes. Lo que hay ahora en la carpeta se guarda como respaldo.") },
        confirmButton = { Button(onClick = onYes) { Text("Cargar") } },
        dismissButton = { TextButton(onClick = onNo) { Text("Cancelar") } },
    )
}

/** Partidas del juego: cambiar la partida en curso, renombrar, crear una nueva y ver su historial. */
@Composable
private fun PartidasWindow(c: AppController, gameId: String, onClose: () -> Unit) {
    val games by c.sync.games.collectAsState()
    val g = games[gameId] ?: return onClose()
    val sync = c.sync
    val manifest = remember(g.state) { sync.manifest(gameId) }
    var confirm by remember { mutableStateOf<SaveVersion?>(null) }
    var switchTo by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var openHistory by remember { mutableStateOf(g.link.partida) }

    DialogWindow(onCloseRequest = onClose, title = "Partidas · ${g.link.name}", state = rememberDialogState(size = DpSize(780.dp, 680.dp))) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (g.busy != null) Text("⏳ ${g.busy}…")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), singleLine = true, label = { Text("Nueva partida con lo que hay en la carpeta") })
                    Button(onClick = { sync.newPartida(gameId, newName); newName = "" }, enabled = newName.isNotBlank() && g.busy == null) { Text("Crear") }
                }
                val partidas = g.state?.partidas.orEmpty()
                if (partidas.isEmpty()) Text("Todavia no hay partidas en la nube. Se crea «Principal» al subir por primera vez.")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(partidas, key = { it.partida.id }) { info ->
                        val p = info.partida
                        val isCurrent = p.id == g.link.partida
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name + if (isCurrent) "  · en curso" else "", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                    Text("${info.count} version(es)", style = MaterialTheme.typography.bodySmall)
                                }
                                Text("Ultima: ${describe(info.tip)}", style = MaterialTheme.typography.bodySmall)
                                if (renaming == p.id) {
                                    var text by remember(p.id) { mutableStateOf(p.name) }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, label = { Text("Nombre") })
                                        Button(onClick = { sync.renamePartida(gameId, p.id, text); renaming = null }, enabled = text.isNotBlank()) { Text("Guardar") }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!isCurrent) Button(onClick = { switchTo = p.id }, enabled = g.busy == null) { Text("Jugar esta") }
                                    TextButton(onClick = { renaming = if (renaming == p.id) null else p.id }) { Text("Renombrar") }
                                    TextButton(onClick = { openHistory = if (openHistory == p.id) null else p.id }) {
                                        Text(if (openHistory == p.id) "Ocultar historial" else "Historial")
                                    }
                                }
                                if (openHistory == p.id && manifest != null) {
                                    VersionList(sync, gameId, manifest, p.id, g.link.head, g.busy == null) { confirm = it }
                                }
                            }
                        }
                    }
                }
            }
        }
        confirm?.let { v -> LoadConfirm(v, onYes = { sync.restore(gameId, v.id); confirm = null }, onNo = { confirm = null }) }
        switchTo?.let { pid ->
            AlertDialog(
                onDismissRequest = { switchTo = null },
                title = { Text("¿Cambiar de partida?") },
                text = {
                    Text(
                        "Se sube lo que hayas guardado en la partida actual y se carga la ultima version de " +
                            "«${manifest?.partida(pid)?.name}». Cierra el emulador antes.",
                    )
                },
                confirmButton = { Button(onClick = { sync.switchPartida(gameId, pid); switchTo = null }) { Text("Cambiar") } },
                dismissButton = { TextButton(onClick = { switchTo = null }) { Text("Cancelar") } },
            )
        }
    }
}

@Composable
private fun VersionList(
    sync: SyncController,
    gameId: String,
    manifest: GameManifest,
    partidaId: String,
    head: String?,
    enabled: Boolean,
    onLoad: (SaveVersion) -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        manifest.versionsOf(partidaId).asReversed().forEach { v -> key(v.id) {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${time(v.createdAt)} · ${v.device}" + if (v.id == head) "  · cargada aqui" else "",
                        fontWeight = if (v.id == head) FontWeight.Bold else FontWeight.Normal,
                    )
                    if (v.label.isNotBlank()) Text("“${v.label}”")
                    Text("${v.files} archivo(s), ${human(v.bytes)}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { onLoad(v) }, enabled = enabled && v.id != head) { Text("Cargar") }
                TextButton(onClick = { editing = if (editing == v.id) null else v.id }) { Text("Etiqueta") }
            }
            if (editing == v.id) {
                var text by remember(v.id) { mutableStateOf(v.label) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, label = { Text("Etiqueta") })
                    Button(onClick = { sync.setLabel(gameId, v.id, text); editing = null }) { Text("Guardar") }
                }
            }
        } }
    }
}

@Composable
private fun SettingsDialog(c: AppController, onClose: () -> Unit) {
    var s by remember { mutableStateOf(c.settings) }
    var poll by remember { mutableStateOf(c.settings.pollMinutes.toString()) }
    var result by remember { mutableStateOf("") }
    fun saved() = s.copy(pollMinutes = poll.toIntOrNull()?.coerceIn(1, 120) ?: 3)

    DialogWindow(onCloseRequest = onClose, title = "Ajustes", state = rememberDialogState(size = DpSize(620.dp, 640.dp))) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Conexion a muve drive", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(s.server, { s = s.copy(server = it) }, Modifier.fillMaxWidth(), label = { Text("Servidor (ej. https://tudominio.com/drive-api)") }, singleLine = true)
                OutlinedTextField(s.app, { s = s.copy(app = it) }, Modifier.fillMaxWidth(), label = { Text("App en el drive") }, singleLine = true)
                OutlinedTextField(
                    s.apiKey,
                    { s = s.copy(apiKey = it) },
                    Modifier.fillMaxWidth(),
                    label = { Text("Llave (API key)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(s.device, { s = s.copy(device = it) }, Modifier.fillMaxWidth(), label = { Text("Nombre de este equipo") }, singleLine = true)
                OutlinedTextField(poll, { poll = it.filter(Char::isDigit) }, Modifier.width(220.dp), label = { Text("Revisar la nube cada (min)") }, singleLine = true)
                OutlinedTextField(
                    s.muvePath,
                    { s = s.copy(muvePath = it) },
                    Modifier.fillMaxWidth(),
                    label = { Text("Ruta de muve (vacio = el que trae la app)") },
                    singleLine = true,
                )
                Text("Datos de mem-card: ${AppPaths.home}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { c.updateSettings(saved()); onClose() }) { Text("Guardar") }
                    OutlinedButton(onClick = {
                        c.updateSettings(saved())
                        result = "Probando…"
                        c.testConnection { result = it }
                    }) { Text("Guardar y probar") }
                    TextButton(onClick = onClose) { Text("Cerrar") }
                }
                if (result.isNotEmpty()) SelectionContainer { Text(result) }
            }
        }
    }
}

@Composable
private fun AddGameDialog(c: AppController, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val dir = File(expandEnv(folder.trim()))

    DialogWindow(onCloseRequest = onClose, title = "Agregar juego", state = rememberDialogState(size = DpSize(620.dp, 400.dp))) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nombre del juego (ej. Monster Hunter XX)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(folder, { folder = it }, Modifier.weight(1f), label = { Text("Carpeta de saves (acepta %APPDATA%)") }, singleLine = true)
                    OutlinedButton(onClick = { chooseFolder("Carpeta de saves", folder)?.let { folder = it.absolutePath } }) { Text("Elegir…") }
                }
                Text(
                    "Una carpeta por juego. Las distintas partidas del mismo juego se manejan adentro, " +
                        "con «Partida», no como juegos separados.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (folder.isNotBlank() && !dir.isDirectory) Text("Esa carpeta no existe.", color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        error = c.addGame(name, dir)
                        if (error == null) onClose()
                    }, enabled = name.isNotBlank() && dir.isDirectory) { Text("Agregar") }
                    TextButton(onClick = onClose) { Text("Cancelar") }
                }
            }
        }
    }
}

@Composable
private fun CloudDialog(c: AppController, onClose: () -> Unit) {
    var list by remember { mutableStateOf<List<GameManifest>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { c.sync.listCloudGames { l, e -> list = l; error = e } }

    DialogWindow(onCloseRequest = onClose, title = "Juegos en la nube", state = rememberDialogState(size = DpSize(620.dp, 520.dp))) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    error != null -> SelectionContainer { Text(error!!, color = MaterialTheme.colorScheme.error) }
                    list == null -> Text("Buscando…")
                    list!!.isEmpty() -> Text("No hay juegos en la nube que no esten ya en este equipo.")
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list.orEmpty(), key = { it.gameId }) { m ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${m.partidas.size} partida(s): ${m.partidas.joinToString { it.name }}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Button(onClick = {
                                    chooseFolder("Carpeta de saves para ${m.name}", null)?.let {
                                        error = c.linkFromCloud(m, it)
                                        if (error == null) onClose()
                                    }
                                }) { Text("Enlazar carpeta…") }
                            }
                        }
                    }
                }
                TextButton(onClick = onClose) { Text("Cerrar") }
            }
        }
    }
}

private fun chooseFolder(title: String, start: String?): File? {
    val fc = JFileChooser(start?.takeIf { it.isNotBlank() }?.let { File(expandEnv(it)) }).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = title
        isAcceptAllFileFilterUsed = false
    }
    return if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) fc.selectedFile else null
}

/** %APPDATA%\Azahar → C:\Users\...\AppData\Roaming\Azahar */
fun expandEnv(path: String): String =
    Regex("%([A-Za-z0-9_]+)%").replace(path) { m -> System.getenv(m.groupValues[1]) ?: m.value }

fun human(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
