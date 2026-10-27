package com.cheraz.memcard.transport.rfcomm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Build
import java.io.IOException

/** Lado que se conecta a un dispositivo ya emparejado. */
object RfcommClient {

    /**
     * Abre una conexion. Quien lo use tiene que haber verificado BLUETOOTH_CONNECT (Android 12+).
     * Lanza IOException si el otro lado no esta esperando o se corta la conexion.
     */
    @SuppressLint("MissingPermission")
    fun connect(adapter: BluetoothAdapter, device: BluetoothDevice): RfcommConnection {
        // Con una busqueda en curso la conexion va mucho mas lenta. En Android 12+ cancelarla pide otro
        // permiso (SCAN) y esta app no busca dispositivos, asi que solo se hace en versiones viejas.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            try {
                adapter.cancelDiscovery()
            } catch (_: SecurityException) {
                // sin permiso: seguimos
            }
        }
        val socket = device.createRfcommSocketToServiceRecord(RfcommService.SERVICE_UUID)
        try {
            socket.connect()
        } catch (e: IOException) {
            try {
                socket.close()
            } catch (_: IOException) {
                // nada que hacer
            }
            throw e
        }
        return RfcommConnection(socket)
    }
}
