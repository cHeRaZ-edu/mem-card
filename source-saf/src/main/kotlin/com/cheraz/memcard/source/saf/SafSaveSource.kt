package com.cheraz.memcard.source.saf

import android.content.Context
import android.net.Uri
import com.cheraz.memcard.core.source.SaveFile
import com.cheraz.memcard.core.source.SaveSource
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Lee un save desde una carpeta elegida con el selector del sistema (ACTION_OPEN_DOCUMENT_TREE).
 *
 * @param treeUri el arbol que regreso el selector.
 * @param subPath subcarpeta dentro del arbol que es la raiz del save ("" = el arbol completo).
 */
class SafSaveSource(
    context: Context,
    private val treeUri: Uri,
    private val subPath: String = "",
) : SaveSource {

    private val resolver = context.applicationContext.contentResolver
    private val index = HashMap<String, String>() // ruta relativa -> id de documento

    override fun list(): List<SaveFile> {
        index.clear()
        val rootId = SafDocs.resolveDir(resolver, treeUri, subPath)
            ?: throw FileNotFoundException("No existe la carpeta '$subPath'")
        val out = ArrayList<SaveFile>()
        walk(rootId, "", out)
        return out
    }

    override fun open(path: String): InputStream {
        val id = index[path] ?: throw FileNotFoundException("$path no esta en el ultimo listado")
        return resolver.openInputStream(SafDocs.docUri(treeUri, id))
            ?: throw FileNotFoundException("El proveedor no pudo abrir $path")
    }

    private fun walk(parentId: String, prefix: String, out: MutableList<SaveFile>) {
        for (child in SafDocs.children(resolver, treeUri, parentId)) {
            val path = prefix + child.name
            if (child.isDir) {
                walk(child.id, "$path/", out)
            } else {
                val size = child.size ?: throw IOException("El proveedor no informo el tamano de $path")
                index[path] = child.id
                out += SaveFile(path, size)
            }
        }
    }
}
