package com.cheraz.memcard.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cheraz.memcard.lab.LabSession
import com.cheraz.memcard.lab.SafLab

@Composable
fun SafLabScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(Unit) { LabSession.load(context) }
    BackHandler(onBack = onBack)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) LabSession.setTree(context, uri)
    }
    val log = LabSession.safLog
    val tree = LabSession.treeUri
    val busy = LabSession.busy
    val snap = LabSession.lastSnapshot
    val backup = LabSession.lastBackup
    var allowReal by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Volver") }
            Text("Laboratorio SAF", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tree?.let { Uri.decode(it.toString()) } ?: "Sin carpeta elegida", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { picker.launch(null) }, enabled = !busy) { Text("Elegir carpeta de saves") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = tree != null && !busy, onClick = {
                    LabSession.runSaf { SafLab.list(context, tree!!, log) }
                }) { Text("Listar") }
                OutlinedButton(enabled = tree != null && !busy, onClick = {
                    LabSession.runSaf { SafLab.snapshot(context, tree!!, log)?.let { LabSession.setSnapshot(it) } }
                }) { Text("Snapshot") }
            }
            Text(snap?.let { "Snapshot: ${it.fileCount} archivos, ${it.totalBytes} B, ${it.hash.take(12)}…" } ?: "Sin snapshot")
            OutlinedButton(enabled = tree != null && snap != null && !busy, onClick = {
                LabSession.runSaf { SafLab.restoreToLabFolder(context, tree!!, snap!!, log) }
            }) { Text("Restaurar a carpeta de prueba") }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = allowReal, onCheckedChange = { allowReal = it })
                Text("Permitir escribir en la carpeta real")
            }
            if (allowReal) {
                Button(enabled = tree != null && snap != null && !busy, onClick = { confirm = "restore" }) {
                    Text("Restaurar en carpeta real")
                }
                OutlinedButton(enabled = tree != null && backup != null && !busy, onClick = { confirm = "revert" }) {
                    Text("Revertir al respaldo")
                }
            }
        }
        LogPanel(log, Modifier.weight(1f).fillMaxWidth())
    }

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Escribir en la carpeta real") },
            text = { Text("Cierra el emulador antes. Se hace un respaldo del estado actual y después se deja la carpeta igual al snapshot.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    val t = tree ?: return@TextButton
                    if (action == "restore") {
                        snap?.let { s -> LabSession.runSaf { SafLab.restoreToReal(context, t, s, log) } }
                    } else {
                        backup?.let { b -> LabSession.runSaf { SafLab.revertToBackup(context, t, b, log) } }
                    }
                }) { Text("Continuar") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
        )
    }
}
