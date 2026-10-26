package com.cheraz.memcard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

/** Flavor btOnly: sin internet ni codigo del drive. */
object Flavor {
    const val HAS_DRIVE = false

    @Composable
    fun Drive(modifier: Modifier, onBack: () -> Unit) {
        LaunchedEffect(Unit) { onBack() }
    }
}
