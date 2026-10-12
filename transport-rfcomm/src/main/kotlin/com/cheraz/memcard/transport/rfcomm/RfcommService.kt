package com.cheraz.memcard.transport.rfcomm

import java.util.UUID

/**
 * Datos del servicio Bluetooth clasico (RFCOMM / SDP) de MemCard.
 * Los dos dispositivos tienen que usar el mismo UUID para encontrarse.
 */
object RfcommService {
    const val NAME = "MemCard"
    val SERVICE_UUID: UUID = UUID.fromString("6d656d63-6172-642d-7266-636f6d6d0001")
}
