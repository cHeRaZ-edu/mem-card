package com.cheraz.memcard.core

/**
 * Punto de partida del modulo core. Aqui van a vivir el motor de versiones, el protocolo de
 * sync y la interfaz Transport (ver el plan en el README).
 */
object MemCardCore {
    /** Sube cuando cambie el formato de los mensajes entre dispositivos. */
    const val PROTOCOL_VERSION = 1
}
