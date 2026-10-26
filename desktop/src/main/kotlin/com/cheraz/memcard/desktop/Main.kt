package com.cheraz.memcard.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.io.RandomAccessFile
import javax.swing.JOptionPane
import javax.swing.UIManager

fun main() {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    // Una sola instancia: dos vigilando las mismas carpetas subirian todo doble.
    AppPaths.home.mkdirs()
    val lock = runCatching { RandomAccessFile(File(AppPaths.home, ".lock"), "rw").channel.tryLock() }.getOrNull()
    if (lock == null) {
        JOptionPane.showMessageDialog(null, "mem-card ya esta abierto.", "mem-card", JOptionPane.INFORMATION_MESSAGE)
        return
    }

    val controller = AppController()
    controller.start()

    application {
        val dark = remember { isWindowsDarkMode() }
        Window(
            onCloseRequest = {
                controller.stop()
                exitApplication()
            },
            title = "mem-card",
            state = rememberWindowState(width = 920.dp, height = 720.dp),
        ) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                App(controller)
            }
        }
    }
}

/** Lee el tema de Windows (AppsUseLightTheme = 0 → oscuro). En otros sistemas, claro. */
private fun isWindowsDarkMode(): Boolean {
    if (!System.getProperty("os.name").lowercase().contains("win")) return false
    return runCatching { readDarkModeKey() }.getOrDefault(false)
}

private fun readDarkModeKey(): Boolean {
    val p = ProcessBuilder(
        "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme",
    ).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    return Regex("""AppsUseLightTheme\s+REG_DWORD\s+0x0\b""").containsMatchIn(out)
}
