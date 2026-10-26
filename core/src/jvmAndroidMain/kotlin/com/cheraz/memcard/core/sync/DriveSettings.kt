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
) {
    val configured get() = server.isNotBlank() && apiKey.isNotBlank()

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun load(file: File, defaults: DriveSettings): DriveSettings = try {
            if (file.isFile) json.decodeFromString(serializer(), file.readText()) else defaults
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
