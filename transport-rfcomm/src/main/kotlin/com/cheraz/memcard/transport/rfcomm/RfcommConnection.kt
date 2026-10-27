package com.cheraz.memcard.transport.rfcomm

import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.io.IOException

/** Una conexion RFCOMM abierta, ya con framing encima. */
class RfcommConnection(private val socket: BluetoothSocket) : Closeable {
    val frames = FrameIO(socket.inputStream, socket.outputStream)

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
            // ya estaba cerrado
        }
    }
}
