package com.cheraz.memcard.transport.rfcomm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import java.io.Closeable
import java.io.IOException

/**
 * Lado que espera conexiones. Usa el socket "seguro" (los dos celulares tienen que estar emparejados).
 * Quien lo use tiene que haber verificado BLUETOOTH_CONNECT (Android 12+).
 */
class RfcommServer(private val adapter: BluetoothAdapter) : Closeable {
    @Volatile
    private var server: BluetoothServerSocket? = null

    @SuppressLint("MissingPermission")
    fun open() {
        server = adapter.listenUsingRfcommWithServiceRecord(RfcommService.NAME, RfcommService.SERVICE_UUID)
    }

    /** Bloquea hasta que llega un cliente. Lanza IOException si se cierra el servidor mientras espera. */
    fun accept(): RfcommConnection {
        val s = server ?: throw IOException("El servidor no esta abierto")
        return RfcommConnection(s.accept())
    }

    override fun close() {
        try {
            server?.close()
        } catch (_: IOException) {
            // ya estaba cerrado
        }
        server = null
    }
}
