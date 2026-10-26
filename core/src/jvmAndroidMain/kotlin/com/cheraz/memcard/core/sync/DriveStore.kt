package com.cheraz.memcard.core.sync

import com.cheraz.memcard.core.history.GameManifest
import java.io.File

/** Manifiesto remoto y la version del archivo en el drive (para `expected_version`). */
data class RemoteManifest(val manifest: GameManifest, val version: Int)

/** Otro dispositivo escribio primero (409 del drive). Se vuelve a leer, se mezcla y se reintenta. */
class DriveConflictException(message: String) : Exception(message)

/** Cualquier otro error del drive (red, llave, permisos...), con un mensaje para mostrar. */
class DriveException(message: String) : Exception(message)

/**
 * Lo minimo que el motor necesita de la nube. Desktop lo implementa con `muve.exe`; Android lo
 * hara con el .aar. Organizacion en el drive (opcion A):
 *
 * - `blobs/<hash>.zip`: snapshots inmutables, nombrados por hash de contenido.
 * - `heads/<gameId>.json`: manifiesto de cada juego, escrito con expected_version.
 */
interface DriveStore {
    /** null si el juego aun no existe en la nube. */
    fun readManifest(gameId: String): RemoteManifest?

    /**
     * [expectVersion] null = crear (falla con [DriveConflictException] si ya existe);
     * si no, sobrescribir solo si la version actual es esa.
     */
    fun writeManifest(gameId: String, manifest: GameManifest, expectVersion: Int?)

    /** Idempotente: si el blob ya existe no es error. */
    fun uploadBlob(hash: String, file: File)

    fun downloadBlob(hash: String, dest: File)

    fun listGameIds(): List<String>

    companion object {
        fun manifestPath(gameId: String) = "heads/$gameId.json"
        fun blobPath(hash: String) = "blobs/$hash.zip"
    }
}
