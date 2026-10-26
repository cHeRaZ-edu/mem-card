package com.cheraz.memcard.drive

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cheraz.memcard.core.history.GameManifest
import com.cheraz.memcard.core.history.History
import com.cheraz.memcard.core.history.SaveVersion
import com.cheraz.memcard.ui.shareText

private sealed interface Page {
    data object List : Page
    data object Settings : Page
    data object Add : Page
    data object Cloud : Page
    data class History(val gameId: String) : Page
}

@Composable
fun DriveScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val c = remember { DriveController.get(context) }
    var page by remember { mutableStateOf<Page>(if (c.settings.configured) Page.List else Page.Settings) }

    DisposableEffect(Unit) {
        c.resume()
        onDispose { c.pause() }
    }
    BackHandler { if (page == Page.List) onBack() else page = Page.List }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val p = page) {
            Page.List -> GameList(c, onBack, onPage = { page = it })
            Page.Settings -> SettingsPage(c) { page = Page.List }
            Page.Add -> AddPage(c) { page = Page.List }
            Page.Cloud -> CloudPage(c) { page = Page.List }
            is Page.History -> HistoryPage(c, p.gameId) { page = Page.List }
        }
    }

    c.forkAsk?.let { ask ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("¿Crear otro camino?") },
            text = {
                Text(
                    "${ask.name}: guardaste partiendo de una version que ya no es la ultima en la nube. " +
                        "Si creas otro camino se suben las dos y puedes cambiar en el historial. " +
                        "Si no, esta partida se queda solo en este celular.",
                )
            },
            confirmButton = { Button(onClick = { c.acceptFork() }) { Text("Crear camino") } },
            dismissButton = { TextButton(onClick = { c.declineFork() }) { Text("Dejarla local") } },
        )
    }
}

@Composable
private fun ColumnScope.GameList(c: DriveController, onBack: () -> Unit, onPage: (Page) -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Partidas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Este equipo: ${c.settings.device}", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { onPage(Page.Settings) }) { Text("Ajustes") }
        TextButton(onClick = onBack) { Text("Salir") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onPage(Page.Add) }, enabled = c.settings.configured) { Text("Agregar juego") }
        OutlinedButton(onClick = { onPage(Page.Cloud) }, enabled = c.settings.configured) { Text("Desde la nube") }
    }
    val list = c.games.values.sortedBy { it.link.name.lowercase() }
    if (list.isEmpty()) {
        Text("Agrega un juego eligiendo la carpeta donde el emulador guarda sus partidas, o enlaza uno ya subido con «Desde la nube».")
    }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.link.gameId }) { g -> GameCard(c, g) { onPage(Page.History(g.link.gameId)) } }
        item {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Registro", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { shareText(context, c.log.joinToString("\n")) }, enabled = c.log.isNotEmpty()) { Text("Exportar") }
            }
            SelectionContainer {
                Column { c.log.take(15).forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameCard(c: DriveController, g: GameUi, onHistory: () -> Unit) {
    val link = g.link
    val st = g.state
    var confirm by remember { mutableStateOf<SaveVersion?>(null) }
    val err = MaterialTheme.colorScheme.error

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(link.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(DriveController.folderLabel(link.folder), style = MaterialTheme.typography.bodySmall)
            when {
                g.busy != null -> Text("⏳ ${g.busy}…")
                g.error != null -> Text(g.error, color = err)
                st == null -> Text("Sin revisar todavia")
                st.localError != null -> Text("No se pudo leer la carpeta: ${st.localError}", color = err)
                st.head == null && st.otherPaths.isNotEmpty() -> {
                    Text("En la nube ya hay partidas. Baja una antes de jugar, o sube la tuya como otro camino.")
                    Button(onClick = { confirm = st.otherPaths.first() }) { Text("Bajar la mas reciente") }
                }
                st.head == null -> Text("Aun no se ha subido. Juega y guarda, o usa «Subir ahora».")
                st.newer.isNotEmpty() -> {
                    Text("Version mas nueva: ${DriveController.describe(st.newer.first())}", color = MaterialTheme.colorScheme.primary)
                    Button(onClick = { confirm = st.newer.first() }) { Text("Bajar") }
                }
                else -> Text("Al dia · ${DriveController.describe(st.head)}")
            }
            if (st?.localChanged == true && st.head != null && g.busy == null) {
                Text("Hay cambios en la carpeta sin subir.", style = MaterialTheme.typography.bodySmall)
            }
            link.pending?.let { p ->
                Text("Partida solo en este celular (${DriveController.describeTime(p.createdAt)}).")
                OutlinedButton(onClick = { c.uploadNow(link.gameId, allowFork = true) }) { Text("Subir como camino nuevo") }
            }
            if (st != null && st.head != null && st.otherPaths.isNotEmpty()) {
                Text("Hay ${st.otherPaths.size} camino(s) mas en el historial.", style = MaterialTheme.typography.bodySmall)
            }
            g.askLabel?.let { v -> LabelRow(c, link.gameId, v) }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { c.uploadNow(link.gameId) }, enabled = g.busy == null) { Text("Subir ahora") }
                OutlinedButton(onClick = { c.refresh(link.gameId) }, enabled = g.busy == null) { Text("Revisar") }
                OutlinedButton(onClick = onHistory) { Text("Historial") }
            }
            FlowRow {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(link.autoUpload, { c.setAuto(link.gameId, upload = it) })
                    Text("Subir solo")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(link.autoDownload, { c.setAuto(link.gameId, download = it) })
                    Text("Bajar solo")
                }
                TextButton(onClick = { c.removeGame(link.gameId) }) { Text("Quitar") }
            }
        }
    }
    confirm?.let { v -> RestoreConfirm(v, { c.restore(link.gameId, v.id); confirm = null }, { confirm = null }) }
}

@Composable
private fun LabelRow(c: DriveController, gameId: String, v: SaveVersion) {
    var text by remember(v.id) { mutableStateOf(v.label) }
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("¿Que paso en esta sesion?") }, singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { c.setLabel(gameId, v.id, text) }, enabled = text.isNotBlank()) { Text("Guardar") }
        TextButton(onClick = { c.skipLabel(gameId) }) { Text("Omitir") }
    }
}

@Composable
private fun RestoreConfirm(v: SaveVersion, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text("¿Restaurar esta partida?") },
        text = { Text("${DriveController.describe(v)}\n\nCierra el emulador antes. Lo que hay en la carpeta se guarda como respaldo.") },
        confirmButton = { Button(onClick = onYes) { Text("Restaurar") } },
        dismissButton = { TextButton(onClick = onNo) { Text("Cancelar") } },
    )
}

@Composable
private fun HistoryPage(c: DriveController, gameId: String, onDone: () -> Unit) {
    val g = c.games[gameId] ?: return onDone()
    val manifest = remember(g.state) { c.manifest(gameId) }
    var confirm by remember { mutableStateOf<SaveVersion?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Historial · ${g.link.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onDone) { Text("Volver") }
    }
    val versions = manifest?.versions.orEmpty().sortedByDescending { it.createdAt }
    if (manifest == null || versions.isEmpty()) {
        Text("Todavia no hay versiones en la nube.")
        return
    }
    val history = History(manifest.versions)
    val tips = history.tips().map { it.id }.toSet()
    val head = g.link.head
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(versions, key = { it.id }) { v ->
            val tags = buildList {
                if (v.id == head) add("actual")
                if (v.id in tips) add("punta")
                if (head != null && !history.isAncestorOrSelf(v.id, head) && !history.isAncestorOrSelf(head, v.id)) add("otro camino")
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${DriveController.describeTime(v.createdAt)} · ${v.device}",
                        fontWeight = if (v.id == head) FontWeight.Bold else FontWeight.Normal,
                    )
                    if (tags.isNotEmpty()) Text(tags.joinToString(" · "), color = MaterialTheme.colorScheme.primary)
                    Text("${v.files} archivo(s), ${human(v.bytes)}", style = MaterialTheme.typography.bodySmall)
                    if (editing == v.id) {
                        var text by remember(v.id) { mutableStateOf(v.label) }
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Etiqueta") })
                        Button(onClick = { c.setLabel(gameId, v.id, text); editing = null }) { Text("Guardar") }
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
    confirm?.let { v -> RestoreConfirm(v, { c.restore(gameId, v.id); confirm = null }, { confirm = null }) }
}

@Composable
private fun SettingsPage(c: DriveController, onDone: () -> Unit) {
    var s by remember { mutableStateOf(c.settings) }
    var poll by remember { mutableStateOf(c.settings.pollMinutes.toString()) }
    var result by remember { mutableStateOf("") }
    fun saved() = s.copy(pollMinutes = poll.toIntOrNull()?.coerceIn(1, 120) ?: 3, games = c.settings.games)

    Text("Conexion a muve drive", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(s.server, { s = s.copy(server = it) }, Modifier.fillMaxWidth(), label = { Text("Servidor") }, singleLine = true)
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
    OutlinedTextField(poll, { poll = it.filter(Char::isDigit) }, label = { Text("Revisar la nube cada (min)") }, singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { c.updateSettings(saved()); onDone() }) { Text("Guardar") }
        OutlinedButton(onClick = {
            c.updateSettings(saved())
            result = "Probando…"
            c.testConnection { result = it }
        }) { Text("Guardar y probar") }
    }
    if (result.isNotEmpty()) SelectionContainer { Text(result) }
}

@Composable
private fun AddPage(c: DriveController, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var tree by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) tree = uri }

    Text("Agregar juego", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nombre (ej. MH Freedom Unite)") }, singleLine = true)
    OutlinedButton(onClick = { pick.launch(null) }) { Text(if (tree == null) "Elegir carpeta de saves…" else "Cambiar carpeta") }
    tree?.let { Text(DriveController.folderLabel(it.toString())) }
    Text(
        "Elige la carpeta del juego, no la de todo el emulador (ej. la del juego dentro de PSP/SAVEDATA). " +
            "Todo lo que este adentro se guarda como una sola partida.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { c.addGame(name, tree!!); onDone() }, enabled = name.isNotBlank() && tree != null) { Text("Agregar") }
        TextButton(onClick = onDone) { Text("Cancelar") }
    }
}

@Composable
private fun CloudPage(c: DriveController, onDone: () -> Unit) {
    var list by remember { mutableStateOf<List<GameManifest>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf<GameManifest?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val m = chosen
        if (uri != null && m != null) {
            c.linkFromCloud(m, uri)
            onDone()
        }
    }
    LaunchedEffect(Unit) { c.listCloudGames { l, e -> list = l; error = e } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Juegos en la nube", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onDone) { Text("Volver") }
    }
    when {
        error != null -> SelectionContainer { Text(error!!, color = MaterialTheme.colorScheme.error) }
        list == null -> Text("Buscando…")
        list!!.isEmpty() -> Text("No hay juegos en la nube que no esten ya en este celular.")
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list.orEmpty(), key = { it.gameId }) { m ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(m.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${m.versions.size} version(es) · ultima: ${DriveController.describe(m.versions.maxByOrNull { it.createdAt })}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { chosen = m; pick.launch(null) }) { Text("Enlazar carpeta…") }
                }
            }
        }
    }
}

private fun human(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
