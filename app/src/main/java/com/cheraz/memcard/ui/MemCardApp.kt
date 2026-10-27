package com.cheraz.memcard.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cheraz.memcard.Flavor

enum class Screen { HOME, DRIVE, SAF_LAB, BT_LAB }

@Composable
fun MemCardApp() {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val m = Modifier.safeDrawingPadding().padding(16.dp)
        when (screen) {
            Screen.HOME -> HomeScreen(m, onOpen = { screen = it })
            Screen.DRIVE -> Flavor.Drive(m, onBack = { screen = Screen.HOME })
            Screen.SAF_LAB -> SafLabScreen(m, onBack = { screen = Screen.HOME })
            Screen.BT_LAB -> BtLabScreen(m, onBack = { screen = Screen.HOME })
        }
    }
}
