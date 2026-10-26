package com.cheraz.memcard.desktop

import com.cheraz.memcard.core.sync.GameLink
import com.cheraz.memcard.core.sync.MuveCli
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress

/**
 * Configuracion y juegos enlazados de este dispositivo. Vive en `settings.json` dentro de [AppPaths.home].
 * La llave del drive va en texto plano (igual que en la config de muve); la carpeta solo es de tu usuario.
 */
@Serializable
data class AppSettings(
    val server: String = "",
    val apiKey: String = "",
    val app: String = "memcard",
    val device: String = defaultDevice(),
    /** Vacio = el muve.exe que viene con la app, o el del PATH. */
    val muvePath: String = "",
    /** Cada cuanto revisar la nube, en minutos. */
    val pollMinutes: Int = 3,
    val games: List<GameLink> = emptyList(),
    /** Formato de [games]; los de un formato viejo se descartan al cargar (se enlazan de nuevo). */
    val gamesFormat: Int = 0,
) {
    val configured get() = server.isNotBlank() && apiKey.isNotBlank()

    /** muve a usar: el de Ajustes, el que trae la app, o el del PATH. */
    fun muveExecutable(): String = muvePath.ifBlank {
        AppPaths.bundledMuve()?.absolutePath
            ?: if (System.getProperty("os.name").lowercase().contains("win")) "muve.exe" else "muve"
    }

    fun toMuveConfig() = MuveCli.MuveConfig(muveExecutable(), server, apiKey, app)

    companion object {
        fun defaultDevice(): String =
            System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
                ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
                ?: "pc"
    }
}

object AppPaths {
    /**
     * `MEMCARD_HOME` si esta definida; si junto a la app hay una carpeta `data` (modo portable, ej. en una
     * USB), esa; si no `%APPDATA%\mem-card` en Windows o `~/.config/mem-card` en Linux.
     */
    val home: File by lazy {
        System.getenv("MEMCARD_HOME")?.takeIf { it.isNotBlank() }?.let { return@lazy File(it) }
        appDir()?.let { File(it, "data") }?.takeIf { it.isDirectory }?.let { return@lazy it }
        val appData = System.getenv("APPDATA")
        if (!appData.isNullOrBlank()) File(appData, "mem-card")
        else File(System.getProperty("user.home"), ".config/mem-card")
    }

    val cache: File get() = File(home, "cache")

    /** Carpeta de la app instalada/descomprimida (donde esta mem-card.exe), si se sabe. */
    fun appDir(): File? = System.getProperty("compose.application.resources.dir")
        ?.let { File(it) }?.parentFile?.parentFile?.takeIf { it.isDirectory }

    /** muve.exe que trae la app (resources/windows/muve.exe), si existe. */
    fun bundledMuve(): File? = System.getProperty("compose.application.resources.dir")
        ?.let { dir -> listOf("muve.exe", "muve").map { File(dir, it) }.firstOrNull { it.isFile } }
}

class SettingsStore(private val file: File = File(AppPaths.home, "settings.json")) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun load(): AppSettings = try {
        val s = if (file.isFile) json.decodeFromString(AppSettings.serializer(), file.readText()) else AppSettings()
        val format = com.cheraz.memcard.core.sync.DriveSettings.GAMES_FORMAT
        if (s.gamesFormat == format) s else s.copy(games = emptyList(), gamesFormat = format)
    } catch (e: Exception) {
        // No perder la config danada: se aparta y se empieza de cero.
        file.copyTo(File(file.parentFile, "settings.bad-${System.currentTimeMillis()}.json"), overwrite = true)
        AppSettings()
    }

    @Synchronized
    fun save(s: AppSettings) {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(AppSettings.serializer(), s))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
