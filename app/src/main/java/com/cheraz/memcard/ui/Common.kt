package com.cheraz.memcard.ui

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cheraz.memcard.lab.LabLog

@Composable
fun KeepScreenOn() {
    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Exportar log"))
}

@Composable
fun LogPanel(log: LabLog, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state = rememberLazyListState()
    val lines = log.lines
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { shareText(context, log.asText()) }) { Text("Exportar") }
            OutlinedButton(onClick = { log.clear() }) { Text("Limpiar") }
        }
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(lines.toList()) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}
