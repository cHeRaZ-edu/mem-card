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
        val dark = remember { isDarkMode() }
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

/**
 * Tema del sistema. Windows: AppsUseLightTheme = 0. Linux: el esquema de colores de KDE (kdeglobals,
 * el default de CachyOS) o el color-scheme de GNOME. Si no se sabe, claro.
 */
private fun isDarkMode(): Boolean {
    val os = System.getProperty("os.name").lowercase()
    return runCatching {
        when {
            os.contains("win") -> readDarkModeKey()
            os.contains("linux") -> linuxDark()
            else -> false
        }
    }.getOrDefault(false)
}

private fun linuxDark(): Boolean {
    val home = System.getProperty("user.home")
    val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config"
    val kde = File(config, "kdeglobals")
    if (kde.isFile) {
        val lines = kde.readLines()
        // El color de fondo de las ventanas dice mas que el nombre del esquema (ej. temas tipo Nord).
        val section = lines.indexOfFirst { it.trim() == "[Colors:Window]" }
        if (section >= 0) {
            val bg = lines.drop(section + 1).takeWhile { !it.startsWith("[") }
                .firstOrNull { it.startsWith("BackgroundNormal=") }?.substringAfter('=')
                ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            if (bg != null && bg.size >= 3) return bg.take(3).average() < 128
        }
        val scheme = lines.firstOrNull { it.startsWith("ColorScheme=") }?.substringAfter('=')
        if (scheme != null) return scheme.contains("dark", ignoreCase = true)
    }
    val p = ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "color-scheme").redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    return out.contains("dark")
}

private fun readDarkModeKey(): Boolean {
    val p = ProcessBuilder(
        "reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme",
    ).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    return Regex("""AppsUseLightTheme\s+REG_DWORD\s+0x0\b""").containsMatchIn(out)
}
