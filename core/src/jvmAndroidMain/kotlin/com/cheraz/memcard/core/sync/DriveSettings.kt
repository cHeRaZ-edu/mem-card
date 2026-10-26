package com.cheraz.memcard.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Configuracion del modo drive en Android (desktop tiene la suya con mas opciones). */
@Serializable
data class DriveSettings(
    val server: String = "",
    val apiKey: String = "",
    val app: String = "memcard",
    val device: String = "",
    val pollMinutes: Int = 3,
    val games: List<GameLink> = emptyList(),
    /** Formato de [games]; los de un formato viejo se descartan al cargar (se enlazan de nuevo). */
    val gamesFormat: Int = 0,
) {
    val configured get() = server.isNotBlank() && apiKey.isNotBlank()

    companion object {
        /** Sube cuando cambie GameLink de forma incompatible (2 = partidas). */
        const val GAMES_FORMAT = 2

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun load(file: File, defaults: DriveSettings): DriveSettings = try {
            val s = if (file.isFile) json.decodeFromString(serializer(), file.readText()) else defaults
            if (s.gamesFormat == GAMES_FORMAT) s else s.copy(games = emptyList(), gamesFormat = GAMES_FORMAT)
        } catch (e: Exception) {
            file.copyTo(File(file.parentFile, "${file.name}.bad-${System.currentTimeMillis()}"), overwrite = true)
            defaults
        }

        fun save(file: File, s: DriveSettings) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer(), s))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}
