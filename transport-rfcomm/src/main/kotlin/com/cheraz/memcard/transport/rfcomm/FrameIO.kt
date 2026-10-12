package com.cheraz.memcard.transport.rfcomm

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Tipos de mensaje del protocolo de los laboratorios. El protocolo de sync real los reemplaza en M3. */
object FrameType {
    const val HELLO = 1
    const val PING = 2
    const val PONG = 3
    const val DATA = 4
    const val DATA_END = 5
    const val DATA_ACK = 6
    const val BYE = 7
}

class Frame(val type: Int, val payload: ByteArray)

/**
 * Mensajes sobre un stream de bytes: [tipo: 1 byte][largo: 4 bytes big-endian][payload].
 * El largo tiene tope ([MAX_PAYLOAD]) para que un mensaje corrupto no pida gigas de memoria.
 */
class FrameIO(input: InputStream, output: OutputStream) {
    private val din = DataInputStream(BufferedInputStream(input, BUFFER))
    private val dout = DataOutputStream(BufferedOutputStream(output, BUFFER))

    /** [flush] false permite encadenar varios frames grandes y mandarlos juntos. */
    fun write(type: Int, payload: ByteArray = EMPTY, flush: Boolean = true) {
        require(type in 0..255) { "Tipo fuera de rango: $type" }
        require(payload.size <= MAX_PAYLOAD) { "Payload demasiado grande: ${payload.size}" }
        dout.writeByte(type)
        dout.writeInt(payload.size)
        dout.write(payload)
        if (flush) dout.flush()
    }

    fun flush() = dout.flush()

    /** Bloquea hasta leer un frame completo. EOFException si el otro lado cerro a la mitad. */
    fun read(): Frame {
        val type = din.readUnsignedByte()
        val length = din.readInt()
        if (length < 0 || length > MAX_PAYLOAD) throw IOException("Frame invalido: largo $length")
        val payload = ByteArray(length)
        din.readFully(payload)
        return Frame(type, payload)
    }

    companion object {
        const val MAX_PAYLOAD = 1024 * 1024
        private const val BUFFER = 64 * 1024
        private val EMPTY = ByteArray(0)
    }
}
