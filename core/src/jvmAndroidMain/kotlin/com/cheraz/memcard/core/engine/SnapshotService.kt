package com.cheraz.memcard.core.engine

import com.cheraz.memcard.core.source.SaveSource
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** El save cambio mientras se leia (el emulador seguia escribiendo): el snapshot no es confiable. */
class SourceChangedException(message: String) : Exception(message)

/**
 * @param hash SHA-256 (hex) del contenido del save.
 * @param zipSize bytes escritos al zip (0 si no se pidio zip).
 */
data class SnapshotResult(
    val hash: String,
    val zipSize: Long,
    val fileCount: Int,
    val totalBytes: Long,
)

/**
 * Snapshot de un save en UNA sola pasada: calcula el hash y escribe el zip a la vez, asi el hash
 * siempre corresponde al zip. Si un archivo no mide lo que dijo el listado, falla con
 * [SourceChangedException] en vez de guardar algo a medias.
 *
 * Formato del hash (v1): archivos ordenados por ruta (orden de String de Kotlin); por cada uno,
 * ruta en UTF-8, un byte 0, tamano en 8 bytes big-endian y el contenido. Es del contenido, no del zip.
 */
class SnapshotService {

    /** [zipOut] null = solo calcular el hash. No cierra [zipOut]. */
    fun snapshot(source: SaveSource, zipOut: OutputStream?): SnapshotResult {
        val files = source.list().sortedBy { it.path }
        require(files.map { it.path }.toSet().size == files.size) { "El save tiene rutas repetidas" }

        val digest = MessageDigest.getInstance("SHA-256")
        val counting = zipOut?.let { CountingOutputStream(it) }
        // ZipOutputStream no siempre acepta cero entradas (depende de la version de Android): zip vacio a mano.
        val zip = if (counting != null && files.isNotEmpty()) {
            ZipOutputStream(counting).apply { setLevel(Deflater.BEST_COMPRESSION) }
        } else {
            null
        }
        val buffer = ByteArray(BUFFER_SIZE)
        var totalBytes = 0L

        for (file in files) {
            digest.update(file.path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(file.size.toBigEndian())

            zip?.putNextEntry(ZipEntry(file.path).apply { time = ZIP_ENTRY_TIME })
            var read = 0L
            source.open(file.path).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    read += n
                    if (read > file.size) {
                        throw SourceChangedException("${file.path} crecio durante la lectura (se esperaban ${file.size} bytes)")
                    }
                    digest.update(buffer, 0, n)
                    zip?.write(buffer, 0, n)
                }
            }
            if (read != file.size) {
                throw SourceChangedException("${file.path}: se esperaban ${file.size} bytes y se leyeron $read")
            }
            zip?.closeEntry()
            totalBytes += read
        }

        if (zip != null) {
            zip.finish()
        } else {
            counting?.write(EMPTY_ZIP)
        }
        counting?.flush()

        return SnapshotResult(
            hash = digest.digest().toHex(),
            zipSize = counting?.count ?: 0L,
            fileCount = files.size,
            totalBytes = totalBytes,
        )
    }

    private fun Long.toBigEndian(): ByteArray = ByteArray(8) { i -> ((this ushr (56 - i * 8)) and 0xFF).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val BUFFER_SIZE = 32 * 1024
        const val ZIP_ENTRY_TIME = 315532800000L // 1980-01-01 UTC

        /** Fin de directorio central sin entradas: un zip valido y vacio. */
        val EMPTY_ZIP = byteArrayOf(0x50, 0x4B, 0x05, 0x06) + ByteArray(18)
    }
}

internal class CountingOutputStream(private val out: OutputStream) : OutputStream() {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }

    override fun flush() = out.flush()
}
