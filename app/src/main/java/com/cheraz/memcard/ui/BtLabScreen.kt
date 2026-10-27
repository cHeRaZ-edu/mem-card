package com.cheraz.memcard.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cheraz.memcard.lab.BtLab
import com.cheraz.memcard.lab.LabSession

@Composable
fun BtLabScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val lab = remember { LabSession.bt(context) }
    BackHandler(onBack = onBack)
    KeepScreenOn()

    var permTick by remember { mutableIntStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    val hasPerm = remember(permTick) { lab.hasPermission() }
    val idle = lab.mode == BtLab.Mode.IDLE

    fun refresh() { devices = lab.bondedDevices() }
    androidx.compose.runtime.LaunchedEffect(permTick) { refresh() }
    val dev = devices.firstOrNull { it.address == selected }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Volver") }
            Text("Laboratorio Bluetooth", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!lab.available) Text("Este equipo no tiene Bluetooth")
            else if (!lab.enabled) Text("Bluetooth está apagado")
            if (!hasPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Button(onClick = { permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Dar permiso de Bluetooth") }
            }
            Text("Dispositivos emparejados (empareja los celulares en Ajustes primero):", style = MaterialTheme.typography.bodySmall)
            devices.forEach { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = d.address == selected, onClick = { selected = d.address })
                    Text(lab.describe(d))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { refresh() }) { Text("Refrescar") }
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Ajustes BT") }
            }
            Text("Este celular como servidor:", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = idle && hasPerm, onClick = { lab.startServer() }) { Text("Esperar") }
                OutlinedButton(enabled = !idle, onClick = { lab.stop() }) { Text("Detener") }
            }
            Text("Este celular como cliente (el otro en «Esperar»):", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = idle && dev != null, onClick = { lab.ping(dev!!) }) { Text("Ping×100") }
                OutlinedButton(enabled = idle && dev != null, onClick = { lab.stability(dev!!) }) { Text("Estab.×20") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 20).forEach { mb ->
                    OutlinedButton(enabled = idle && dev != null, onClick = { lab.throughput(dev!!, mb) }) { Text("$mb MB") }
                }
            }
        }
        LogPanel(LabSession.btLog, Modifier.weight(1f).fillMaxWidth())
    }
}
