package com.cheraz.memcard.core.source

import java.io.InputStream

/** Un archivo dentro de un save. [path] es relativo a la raiz del save y usa '/' como separador. */
data class SaveFile(val path: String, val size: Long)

/**
 * De donde se lee un save. En Android lo implementa SAF (carpetas de otras apps);
 * en desktop sera una carpeta normal.
 */
interface SaveSource {
    /** Todos los archivos regulares del save (recursivo). El orden no importa: el snapshot ordena. */
    fun list(): List<SaveFile>

    /** Abre [path] (uno de los que regreso [list]). Quien llama cierra el stream. */
    fun open(path: String): InputStream
}

/** A donde se escribe un save al restaurarlo. */
interface SaveSink {
    /** Crea o sobrescribe [path] con lo que lea de [content]. No cierra [content]. */
    fun write(path: String, content: InputStream)

    /** Borra [path]. */
    fun delete(path: String)
}

/** Una carpeta de save que se puede leer y escribir (carpeta normal en desktop, arbol de SAF en Android). */
interface SaveFolder : SaveSource, SaveSink {
    /** Crea la carpeta si no existe (antes de restaurar). */
    fun ensureExists() {}
}
