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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cheraz.memcard.core.app.GameUi
import com.cheraz.memcard.core.app.PartidaAsk
import com.cheraz.memcard.core.app.SyncController
import com.cheraz.memcard.core.app.SyncController.Companion.describe
import com.cheraz.memcard.core.app.SyncController.Companion.time
import com.cheraz.memcard.core.history.GameManifest
import com.cheraz.memcard.core.history.SaveVersion
import com.cheraz.memcard.ui.shareText

private sealed interface Page {
    data object Games : Page
    data object Settings : Page
    data object Add : Page
    data object Cloud : Page
    data class Partidas(val gameId: String) : Page
}

@Composable
fun DriveScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val c = remember { DriveController.get(context) }
    var page by remember { mutableStateOf<Page>(if (c.settings.configured) Page.Games else Page.Settings) }
    val ask by c.sync.ask.collectAsState()

    DisposableEffect(Unit) {
        c.resume()
        onDispose { c.pause() }
    }
    BackHandler { if (page == Page.Games) onBack() else page = Page.Games }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val p = page) {
            Page.Games -> GameList(c, onBack, onPage = { page = it })
            Page.Settings -> SettingsPage(c) { page = Page.Games }
            Page.Add -> AddPage(c) { page = Page.Games }
            Page.Cloud -> CloudPage(c) { page = Page.Games }
            is Page.Partidas -> PartidasPage(c.sync, p.gameId) { page = Page.Games }
        }
    }
    ask?.let { AskDialog(c.sync, it) }
}

@Composable
private fun AskDialog(sync: SyncController, ask: PartidaAsk) {
    var name by remember(ask.version.id) { mutableStateOf("") }
    val why = if (ask.tip == null) {
        "En la nube ya hay partidas de «${ask.gameName}» y en este celular aun no eliges una."
    } else {
        "La partida «${ask.currentPartida ?: "?"}» siguio en otro lado o cargaste una version vieja: la ultima es ${describe(ask.tip)}."
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("¿Es una partida nueva?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$why\n\nPara no pisar nada, guarda lo tuyo como partida nueva. Si no, se queda solo en este celular.")
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nombre de la partida nueva") })
            }
        },
        confirmButton = { Button(onClick = { sync.answerAsk(name) }, enabled = name.isNotBlank()) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = { sync.answerAsk(null) }) { Text("Solo aqui") } },
    )
}

@Composable
private fun ColumnScope.GameList(c: DriveController, onBack: () -> Unit, onPage: (Page) -> Unit) {
    val context = LocalContext.current
    val games by c.sync.games.collectAsState()
    val log by c.sync.log.collectAsState()
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
    val list = games.values.sortedBy { it.link.name.lowercase() }
    if (list.isEmpty()) {
        Text("Agrega un juego eligiendo la carpeta donde el emulador guarda sus partidas, o enlaza uno ya subido con «Desde la nube».")
    }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.link.gameId }) { g -> GameCard(c.sync, g) { onPage(Page.Partidas(g.link.gameId)) } }
        item {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Registro", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { shareText(context, log.joinToString("\n")) }, enabled = log.isNotEmpty()) { Text("Exportar") }
            }
            SelectionContainer {
                Column { log.take(15).forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameCard(sync: SyncController, g: GameUi, onPartidas: () -> Unit) {
    val link = g.link
    val st = g.state
    var confirm by remember { mutableStateOf<SaveVersion?>(null) }
    val err = MaterialTheme.colorScheme.error

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(link.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(DriveController.folderLabel(link.folder), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onPartidas) { Text("Partida: ${st?.current?.partida?.name ?: "sin elegir"}") }
            when {
                g.busy != null -> Text("⏳ ${g.busy}…")
                g.error != null -> Text(g.error!!, color = err)
                st == null -> Text("Sin revisar todavia")
                st.localError != null -> Text("No se pudo leer la carpeta: ${st.localError}", color = err)
                st.current == null && st.partidas.isNotEmpty() -> {
                    Text("En la nube hay ${st.partidas.size} partida(s). Elige cual cargar antes de jugar.")
                    Button(onClick = onPartidas) { Text("Elegir partida") }
                }
                st.current == null -> Text("Aun no se ha subido. Juega y guarda, o usa «Subir ahora».")
                st.newer != null -> {
                    Text("Esta partida avanzo en otro equipo: ${describe(st.newer)}", color = MaterialTheme.colorScheme.primary)
                    Button(onClick = { confirm = st.newer }) { Text("Bajar") }
                }
                else -> Text("Al dia · ${describe(st.head)}")
            }
            if (st?.localChanged == true && st.head != null && g.busy == null) {
                Text("Hay cambios en la carpeta sin subir.", style = MaterialTheme.typography.bodySmall)
            }
            link.pending?.let { p -> Text("Hay una partida guardada solo en este celular (${time(p.createdAt)}). Puedes subirla desde «Partida».") }
            g.askLabel?.let { v -> LabelRow(sync, link.gameId, v) }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { sync.uploadNow(link.gameId) }, enabled = g.busy == null) { Text("Subir ahora") }
                OutlinedButton(onClick = { sync.refresh(link.gameId) }, enabled = g.busy == null) { Text("Revisar") }
            }
            FlowRow {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(link.autoUpload, { sync.setAuto(link.gameId, upload = it) })
                    Text("Subir solo")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(link.autoDownload, { sync.setAuto(link.gameId, download = it) })
                    Text("Bajar solo")
                }
                TextButton(onClick = { sync.removeGame(link.gameId) }) { Text("Quitar") }
            }
        }
    }
    confirm?.let { v -> LoadConfirm(v, { sync.restore(link.gameId, v.id); confirm = null }, { confirm = null }) }
}

@Composable
private fun LabelRow(sync: SyncController, gameId: String, v: SaveVersion) {
    var text by remember(v.id) { mutableStateOf(v.label) }
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("¿Que paso en esta sesion?") }, singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { sync.setLabel(gameId, v.id, text) }, enabled = text.isNotBlank()) { Text("Guardar") }
        TextButton(onClick = { sync.skipLabel(gameId) }) { Text("Omitir") }
    }
}

@Composable
private fun LoadConfirm(v: SaveVersion, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text("¿Cargar esta version?") },
        text = { Text("${describe(v)}\n\nCierra el emulador antes. Lo que hay en la carpeta se guarda como respaldo.") },
        confirmButton = { Button(onClick = onYes) { Text("Cargar") } },
        dismissButton = { TextButton(onClick = onNo) { Text("Cancelar") } },
    )
}

/** Partidas del juego: cambiar la en curso, renombrar, crear nueva y ver historial. */
@Composable
private fun PartidasPage(sync: SyncController, gameId: String, onDone: () -> Unit) {
    val games by sync.games.collectAsState()
    val g = games[gameId] ?: return onDone()
    val manifest = remember(g.state) { sync.manifest(gameId) }
    var confirm by remember { mutableStateOf<SaveVersion?>(null) }
    var switchTo by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var openHistory by remember { mutableStateOf(g.link.partida) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(g.link.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onDone) { Text("Volver") }
    }
    if (g.busy != null) Text("⏳ ${g.busy}…")
    OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nueva partida con lo que hay en la carpeta") })
    Button(onClick = { sync.newPartida(gameId, newName); newName = "" }, enabled = newName.isNotBlank() && g.busy == null) { Text("Crear partida") }

    val partidas = g.state?.partidas.orEmpty()
    if (partidas.isEmpty()) Text("Todavia no hay partidas en la nube. Se crea «Principal» al subir por primera vez.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(partidas, key = { it.partida.id }) { info ->
            val p = info.partida
            val isCurrent = p.id == g.link.partida
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name + if (isCurrent) "  · en curso" else "", fontWeight = FontWeight.SemiBold)
                    Text("${info.count} version(es) · ultima: ${describe(info.tip)}", style = MaterialTheme.typography.bodySmall)
                    if (renaming == p.id) {
                        var text by remember(p.id) { mutableStateOf(p.name) }
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nombre") })
                        Button(onClick = { sync.renamePartida(gameId, p.id, text); renaming = null }, enabled = text.isNotBlank()) { Text("Guardar") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!isCurrent) Button(onClick = { switchTo = p.id }, enabled = g.busy == null) { Text("Jugar esta") }
                        TextButton(onClick = { renaming = if (renaming == p.id) null else p.id }) { Text("Renombrar") }
                        TextButton(onClick = { openHistory = if (openHistory == p.id) null else p.id }) {
                            Text(if (openHistory == p.id) "Ocultar" else "Historial")
                        }
                    }
                    if (openHistory == p.id && manifest != null) {
                        VersionList(sync, gameId, manifest, p.id, g.link.head, g.busy == null) { confirm = it }
                    }
                }
            }
        }
    }
    confirm?.let { v -> LoadConfirm(v, { sync.restore(gameId, v.id); confirm = null }, { confirm = null }) }
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        manifest.versionsOf(partidaId).asReversed().forEach { v ->
            key(v.id) {
                HorizontalDivider()
                Text(
                    "${time(v.createdAt)} · ${v.device}" + if (v.id == head) "  · cargada aqui" else "",
                    fontWeight = if (v.id == head) FontWeight.Bold else FontWeight.Normal,
                )
                if (v.label.isNotBlank()) Text("“${v.label}”")
                Text("${v.files} archivo(s), ${human(v.bytes)}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { onLoad(v) }, enabled = enabled && v.id != head) { Text("Cargar") }
                    TextButton(onClick = { editing = if (editing == v.id) null else v.id }) { Text("Etiqueta") }
                }
                if (editing == v.id) {
                    var text by remember(v.id) { mutableStateOf(v.label) }
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Etiqueta") })
                    Button(onClick = { sync.setLabel(gameId, v.id, text); editing = null }) { Text("Guardar") }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(c: DriveController, onDone: () -> Unit) {
    var s by remember { mutableStateOf(c.settings) }
    var poll by remember { mutableStateOf(c.settings.pollMinutes.toString()) }
    var result by remember { mutableStateOf("") }
    fun saved() = s.copy(pollMinutes = poll.toIntOrNull()?.coerceIn(1, 120) ?: 3)

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
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) tree = uri }

    Text("Agregar juego", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Nombre del juego (ej. Monster Hunter XX)") }, singleLine = true)
    OutlinedButton(onClick = { pick.launch(null) }) { Text(if (tree == null) "Elegir carpeta de saves…" else "Cambiar carpeta") }
    tree?.let { Text(DriveController.folderLabel(it.toString())) }
    Text(
        "Una carpeta por juego. Las distintas partidas del mismo juego se manejan adentro, con «Partida».",
        style = MaterialTheme.typography.bodySmall,
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            error = c.addGame(name, tree!!)
            if (error == null) onDone()
        }, enabled = name.isNotBlank() && tree != null) { Text("Agregar") }
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
            error = c.linkFromCloud(m, uri)
            if (error == null) onDone()
        }
    }
    LaunchedEffect(Unit) { c.sync.listCloudGames { l, e -> list = l; error = e } }

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
                    Text("${m.partidas.size} partida(s): ${m.partidas.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall)
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
