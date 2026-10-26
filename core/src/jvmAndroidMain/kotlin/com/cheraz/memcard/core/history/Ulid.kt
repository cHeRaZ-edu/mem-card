package com.cheraz.memcard.core.history

import java.security.SecureRandom

/**
 * ULID: 48 bits de milisegundos + 80 bits aleatorios en base32 de Crockford (26 caracteres).
 * Ordenan por fecha como texto y no chocan entre dispositivos.
 */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun next(timeMs: Long = System.currentTimeMillis()): String {
        val rnd = ByteArray(10).also { random.nextBytes(it) }
        val out = CharArray(26)
        var t = timeMs
        for (i in 9 downTo 0) {
            out[i] = ALPHABET[(t and 31).toInt()]
            t = t ushr 5
        }
        // 80 bits = 16 caracteres de 5 bits
        var acc = 0L
        var bits = 0
        var pos = 10
        for (b in rnd) {
            acc = (acc shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out[pos++] = ALPHABET[((acc ushr bits) and 31).toInt()]
            }
        }
        return String(out)
    }

    fun isValid(s: String): Boolean = s.length == 26 && s.all { it in ALPHABET }
}
