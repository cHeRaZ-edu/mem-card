package com.cheraz.memcard.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(modifier: Modifier, onOpen: (Screen) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("MemCard", style = MaterialTheme.typography.headlineMedium)
        Text("Laboratorios M1: miden si SAF y Bluetooth sirven en este celular.")
        Text("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onOpen(Screen.SAF_LAB) }, modifier = Modifier.fillMaxWidth()) { Text("Laboratorio SAF (carpetas)") }
        Button(onClick = { onOpen(Screen.BT_LAB) }, modifier = Modifier.fillMaxWidth()) { Text("Laboratorio Bluetooth") }
    }
}
