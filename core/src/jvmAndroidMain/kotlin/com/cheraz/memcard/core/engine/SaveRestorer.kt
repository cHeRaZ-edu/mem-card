package com.cheraz.memcard.core.engine

import com.cheraz.memcard.core.source.SaveFile
import com.cheraz.memcard.core.source.SaveSink
import java.io.InputStream
import java.util.zip.ZipInputStream

data class RestoreResult(val written: Int, val deleted: Int, val bytes: Long)

/**
 * Deja un destino igual al contenido de un zip hecho por [SnapshotService]: escribe todo lo del zip
 * y borra lo que ya estaba en el destino ([existing]) y no viene en el zip.
 *
 * No es atomico: si falla a la mitad el destino queda mezclado. Quien lo use tiene que haber
 * guardado antes un snapshot de lo que habia (regla del dominio: nunca sobrescribir sin respaldo).
 */
class SaveRestorer {

    /** No cierra [zip]. */
    fun restore(zip: InputStream, existing: List<SaveFile>, sink: SaveSink): RestoreResult {
        val zis = ZipInputStream(zip)
        val kept = HashSet<String>()
        var bytes = 0L

        while (true) {
            val entry = zis.nextEntry ?: break
            if (entry.isDirectory) continue
            val name = safePath(entry.name)
            val counting = CountingInputStream(zis)
            sink.write(name, counting)
            bytes += counting.count
            kept += name
        }

        var deleted = 0
        for (file in existing) {
            if (file.path !in kept) {
                sink.delete(file.path)
                deleted++
            }
        }
        return RestoreResult(written = kept.size, deleted = deleted, bytes = bytes)
    }

    /** Protege contra rutas tipo "../x" (zip slip) o absolutas dentro del zip. */
    internal fun safePath(name: String): String {
        val ok = name.isNotEmpty() &&
            !name.startsWith("/") &&
            !name.contains('\\') &&
            name.split('/').none { it.isEmpty() || it == "." || it == ".." }
        require(ok) { "Ruta no permitida en el zip: '$name'" }
        return name
    }
}

/** Cuenta bytes y no cierra el stream de abajo (el zip sigue con la siguiente entrada). */
internal class CountingInputStream(private val input: InputStream) : InputStream() {
    var count = 0L
        private set

    override fun read(): Int {
        val b = input.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = input.read(b, off, len)
        if (n > 0) count += n
        return n
    }

    override fun close() {
        // a proposito vacio
    }
}
