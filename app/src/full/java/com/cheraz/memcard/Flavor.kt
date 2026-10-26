package com.cheraz.memcard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cheraz.memcard.drive.DriveScreen

/** Flavor full: trae el modo muve drive. */
object Flavor {
    const val HAS_DRIVE = true

    @Composable
    fun Drive(modifier: Modifier, onBack: () -> Unit) = DriveScreen(modifier, onBack)
}
