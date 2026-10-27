package com.cheraz.memcard.lab

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Log de un laboratorio. Se puede escribir desde cualquier hilo; la lista se actualiza en el hilo principal. */
class LabLog {
    val lines = mutableStateListOf<String>()

    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(text: String) {
        val line = synchronized(clock) { "${clock.format(Date())}  $text" }
        main.post {
            lines.add(line)
            while (lines.size > MAX_LINES) lines.removeAt(0)
        }
    }

    fun clear() {
        main.post { lines.clear() }
    }

    fun asText(): String = lines.joinToString("\n")

    private companion object {
        const val MAX_LINES = 2000
    }
}
