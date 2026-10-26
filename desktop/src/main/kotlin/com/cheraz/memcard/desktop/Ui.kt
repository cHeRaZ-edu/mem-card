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
import androidx.compose.runtime.getValue
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
import com.cheraz.memcard.core.history.GameManifest
import com.cheraz.memcard.core.history.History
import com.cheraz.memcard.core.history.SaveVersion
import java.io.File
import javax.swing.JFileChooser

@Composable
fun App(c: AppController) {
    var showSettings by remember { mutableStateOf(!c.settings.configured) }
    var showAdd by remember { mutableStateOf(false) }
    var showCloud by remember { mutableStateOf(false) }
    var historyFor by remember { mutableStateOf<String?>(null) }

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

            val list = c.games.values.sortedBy { it.link.name.lowercase() }
            Box(Modifier.weight(1f)) {
                if (list.isEmpty()) {
                    Text(
                        "Agrega un juego eligiendo la carpeta donde el emulador guarda sus partidas, " +
                            "o enlaza uno que ya subiste desde otro equipo con «Desde la nube».",
                        Modifier.padding(24.dp),
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.link.gameId }) { g -> GameCard(c, g, onHistory = { historyFor = g.link.gameId }) }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SelectionContainer {
                Column(Modifier.heightIn(max = 110.dp)) {
                    c.log.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

    if (showSettings) SettingsDialog(c) { showSettings = false }
    if (showAdd) AddGameDialog(c) { showAdd = false }
    if (showCloud) CloudDialog(c) { showCloud = false }
    historyFor?.let { id -> HistoryWindow(c, id) { historyFor = null } }
    c.forkAsk?.let { ask ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("¿Crear otro camino?") },
            text = {
                Text(
                    "${ask.name}: guardaste partiendo de una version que ya no es la ultima en la nube " +
                        "(otro equipo subio algo despues, o la tomaste del historial).\n\n" +
                        "Si creas otro camino se suben las dos y puedes cambiar entre ellas en el historial. " +
                        "Si no, esta partida se queda solo en este equipo.",
                )
            },
            confirmButton = { Button(onClick = { c.acceptFork() }) { Text("Crear camino") } },
            dismissButton = { TextButton(onClick = { c.declineFork() }) { Text("Dejarla local") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameCard(c: AppController, g: GameUi, onHistory: () -> Unit) {
    val link = g.link
    val st = g.state
    var confirmRestore by remember { mutableStateOf<SaveVersion?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(link.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(link.folder, style = MaterialTheme.typography.bodySmall)

            val err = MaterialTheme.colorScheme.error
            when {
                g.busy != null -> Text("⏳ ${g.busy}…")
                g.error != null -> Text(g.error, color = err)
                st == null -> Text("Sin revisar todavia")
                st.localError != null -> Text("No se pudo leer la carpeta: ${st.localError}", color = err)
                st.head == null && st.otherPaths.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("En la nube ya hay partidas. Baja una antes de jugar, o sube la tuya como otro camino.", Modifier.weight(1f))
                    Button(onClick = { confirmRestore = st.otherPaths.first() }) { Text("Bajar la mas reciente") }
                }
                st.head == null -> Text("Aun no se ha subido. Juega y guarda, o usa «Subir ahora».")
                st.newer.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    val v = st.newer.first()
                    Text("Hay una version mas nueva: ${AppController.describe(v)}", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    Button(onClick = { confirmRestore = v }) { Text("Bajar") }
                }
                else -> Text("Al dia · ${AppController.describe(st.head)}")
            }
            if (st?.localChanged == true && st.head != null && g.busy == null) {
                Text("Hay cambios en la carpeta sin subir.", style = MaterialTheme.typography.bodySmall)
            }
            link.pending?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Partida solo en este equipo (${AppController.describeTime(p.createdAt)}).", Modifier.weight(1f))
                    OutlinedButton(onClick = { c.uploadNow(link.gameId, allowFork = true) }) { Text("Subir como camino nuevo") }
                }
            }
            if (st != null && st.head != null && st.otherPaths.isNotEmpty()) {
                Text("Hay ${st.otherPaths.size} camino(s) mas en el historial.", style = MaterialTheme.typography.bodySmall)
            }

            g.askLabel?.let { v -> LabelRow(c, link.gameId, v) }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { c.uploadNow(link.gameId) }, enabled = g.busy == null) { Text("Subir ahora") }
                OutlinedButton(onClick = { c.refresh(link.gameId) }, enabled = g.busy == null) { Text("Revisar nube") }
                OutlinedButton(onClick = onHistory) { Text("Historial") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(link.autoUpload, { c.setAuto(link.gameId, upload = it) })
                    Text("Subir solo")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(link.autoDownload, { c.setAuto(link.gameId, download = it) })
                    Text("Bajar solo")
                }
                TextButton(onClick = { c.removeGame(link.gameId) }) { Text("Quitar de este equipo") }
            }
        }
    }

    confirmRestore?.let { v ->
        RestoreConfirm(v, onYes = { c.restore(link.gameId, v.id); confirmRestore = null }, onNo = { confirmRestore = null })
    }
}

@Composable
private fun LabelRow(c: AppController, gameId: String, v: SaveVersion) {
    var text by remember(v.id) { mutableStateOf(v.label) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            text,
            { text = it },
            Modifier.weight(1f),
            label = { Text("¿Que paso en esta sesion?") },
            singleLine = true,
        )
        Button(onClick = { c.setLabel(gameId, v.id, text) }, enabled = text.isNotBlank()) { Text("Guardar") }
        TextButton(onClick = { c.skipLabel(gameId) }) { Text("Omitir") }
    }
}

@Composable
private fun RestoreConfirm(v: SaveVersion, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text("¿Restaurar esta partida?") },
        text = {
            Text(
                "${AppController.describe(v)}\n\nCierra el emulador antes. Lo que hay ahora en la carpeta " +
                    "se guarda como respaldo antes de reemplazarlo.",
            )
        },
        confirmButton = { Button(onClick = onYes) { Text("Restaurar") } },
        dismissButton = { TextButton(onClick = onNo) { Text("Cancelar") } },
    )
}

@Composable
private fun HistoryWindow(c: AppController, gameId: String, onClose: () -> Unit) {
    val g = c.games[gameId] ?: return onClose()
    val manifest = remember(g.state) { c.manifest(gameId) }
    var confirm by remember { mutableStateOf<SaveVersion?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }

    DialogWindow(
        onCloseRequest = onClose,
        title = "Historial · ${g.link.name}",
        state = rememberDialogState(size = DpSize(760.dp, 620.dp)),
    ) {
        Surface(Modifier.fillMaxSize()) {
            val versions = manifest?.versions.orEmpty().sortedByDescending { it.createdAt }
            if (versions.isEmpty()) {
                Text("Todavia no hay versiones en la nube.", Modifier.padding(24.dp))
                return@Surface
            }
            val history = History(manifest!!.versions)
            val tips = history.tips().map { it.id }.toSet()
            val head = g.link.head
            LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(versions, key = { it.id }) { v ->
                    val tags = buildList {
                        if (v.id == head) add("actual")
                        if (v.id in tips) add("punta")
                        if (head != null && !history.isAncestorOrSelf(v.id, head) && !history.isAncestorOrSelf(head, v.id)) add("otro camino")
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${AppController.describeTime(v.createdAt)} · ${v.device}",
                                    Modifier.weight(1f),
                                    fontWeight = if (v.id == head) FontWeight.Bold else FontWeight.Normal,
                                )
                                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), color = MaterialTheme.colorScheme.primary)
                            }
                            Text("${v.files} archivo(s), ${human(v.bytes)}", style = MaterialTheme.typography.bodySmall)
                            if (editing == v.id) {
                                var text by remember(v.id) { mutableStateOf(v.label) }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, label = { Text("Etiqueta") })
                                    Button(onClick = { c.setLabel(gameId, v.id, text); editing = null }) { Text("Guardar") }
                                }
                            } else if (v.label.isNotBlank()) {
                                Text("“${v.label}”")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { confirm = v }, enabled = v.id != head && g.busy == null) { Text("Restaurar") }
                                TextButton(onClick = { editing = if (editing == v.id) null else v.id }) { Text("Etiqueta") }
                            }
                        }
                    }
                }
            }
        }
        confirm?.let { v ->
            RestoreConfirm(v, onYes = { c.restore(gameId, v.id); confirm = null }, onNo = { confirm = null })
        }
    }
}

@Composable
private fun SettingsDialog(c: AppController, onClose: () -> Unit) {
    var s by remember { mutableStateOf(c.settings) }
    var poll by remember { mutableStateOf(c.settings.pollMinutes.toString()) }
    var result by remember { mutableStateOf("") }

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
                    Button(onClick = {
                        c.updateSettings(s.copy(pollMinutes = poll.toIntOrNull()?.coerceIn(1, 120) ?: 3, games = c.settings.games))
                        onClose()
                    }) { Text("Guardar") }
                    OutlinedButton(onClick = {
                        c.updateSettings(s.copy(pollMinutes = poll.toIntOrNull()?.coerceIn(1, 120) ?: 3, games = c.settings.games))
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
    val dir = File(expandEnv(folder.trim()))

    DialogWindow(onCloseRequest = onClose, title = "Agregar juego", state = rememberDialogState(size = DpSize(620.dp, 380.dp))) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nombre (ej. Monster Hunter Freedom Unite)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(folder, { folder = it }, Modifier.weight(1f), label = { Text("Carpeta de saves (acepta %APPDATA%)") }, singleLine = true)
                    OutlinedButton(onClick = { chooseFolder("Carpeta de saves", folder)?.let { folder = it.absolutePath } }) { Text("Elegir…") }
                }
                Text(
                    "Elige la carpeta del juego, no la de todo el emulador: por ejemplo la del juego dentro de " +
                        "PSP\\SAVEDATA. Todo lo que este adentro se guarda como una sola partida.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (folder.isNotBlank() && !dir.isDirectory) Text("Esa carpeta no existe.", color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { c.addGame(name, dir); onClose() }, enabled = name.isNotBlank() && dir.isDirectory) { Text("Agregar") }
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
    LaunchedEffect(Unit) { c.listCloudGames { l, e -> list = l; error = e } }

    DialogWindow(onCloseRequest = onClose, title = "Juegos en la nube", state = rememberDialogState(size = DpSize(620.dp, 520.dp))) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    list == null -> Text("Buscando…")
                    list!!.isEmpty() -> Text("No hay juegos en la nube que no esten ya en este equipo.")
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list.orEmpty(), key = { it.gameId }) { m ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.name, fontWeight = FontWeight.SemiBold)
                                    val last = m.versions.maxByOrNull { it.createdAt }
                                    Text("${m.versions.size} version(es) · ultima: ${AppController.describe(last)}", style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = {
                                    chooseFolder("Carpeta de saves para ${m.name}", null)?.let {
                                        c.linkFromCloud(m, it)
                                        onClose()
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
