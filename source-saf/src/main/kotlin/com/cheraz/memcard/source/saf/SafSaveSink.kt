package com.cheraz.memcard.source.saf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.cheraz.memcard.core.source.SaveSink
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Escribe un save en una carpeta de SAF. Crea las carpetas que falten y sobrescribe archivos
 * existentes (modo "wt": trunca primero).
 *
 * @param subPath subcarpeta dentro del arbol donde vive el save ("" = el arbol completo).
 */
class SafSaveSink(
    context: Context,
    private val treeUri: Uri,
    private val subPath: String = "",
) : SaveSink {

    private val resolver = context.applicationContext.contentResolver
    private val dirCache = HashMap<String, String>() // ruta de carpeta desde la raiz del arbol -> id

    /** Deja [subPath] vacia y existente: borra lo que hubiera y la vuelve a crear. */
    fun reset() {
        deleteRoot()
        dirId(subPath, create = true)
    }

    /** Borra [subPath] completa (si existe). No hace nada si [subPath] es "" (no borra el arbol del usuario). */
    fun deleteRoot() {
        dirCache.clear()
        if (subPath.isEmpty()) return
        val id = SafDocs.resolveDir(resolver, treeUri, subPath) ?: return
        DocumentsContract.deleteDocument(resolver, SafDocs.docUri(treeUri, id))
    }

    override fun write(path: String, content: InputStream) {
        val full = fullPath(path)
        val slash = full.lastIndexOf('/')
        val dirPath = if (slash < 0) "" else full.substring(0, slash)
        val name = full.substring(slash + 1)

        val parentId = dirId(dirPath, create = true) ?: throw IOException("No se pudo crear la carpeta '$dirPath'")
        val existing = SafDocs.children(resolver, treeUri, parentId).firstOrNull { it.name == name }
        val target: Uri = when {
            existing == null -> SafDocs.docUri(treeUri, createDoc(parentId, FILE_MIME, name))
            existing.isDir -> throw IOException("'$full' es una carpeta y se esperaba un archivo")
            else -> SafDocs.docUri(treeUri, existing.id)
        }
        val out = resolver.openOutputStream(target, "wt")
            ?: throw IOException("No se pudo abrir $full para escribir")
        out.use { content.copyTo(it) }
    }

    override fun delete(path: String) {
        val full = fullPath(path)
        val slash = full.lastIndexOf('/')
        val dirPath = if (slash < 0) "" else full.substring(0, slash)
        val name = full.substring(slash + 1)
        val parentId = dirId(dirPath, create = false) ?: return
        val existing = SafDocs.children(resolver, treeUri, parentId).firstOrNull { it.name == name } ?: return
        if (!DocumentsContract.deleteDocument(resolver, SafDocs.docUri(treeUri, existing.id))) {
            throw IOException("No se pudo borrar $full")
        }
    }

    private fun fullPath(path: String) = if (subPath.isEmpty()) path else "$subPath/$path"

    private fun dirId(fullDir: String, create: Boolean): String? {
        if (fullDir.isEmpty()) return SafDocs.rootId(treeUri)
        dirCache[fullDir]?.let { return it }

        val slash = fullDir.lastIndexOf('/')
        val parentPath = if (slash < 0) "" else fullDir.substring(0, slash)
        val name = fullDir.substring(slash + 1)
        val parentId = dirId(parentPath, create) ?: return null

        val existing = SafDocs.children(resolver, treeUri, parentId).firstOrNull { it.name == name }
        val id = when {
            existing != null && existing.isDir -> existing.id
            existing != null -> throw IOException("'$fullDir' existe y no es una carpeta")
            create -> createDoc(parentId, Document.MIME_TYPE_DIR, name)
            else -> return null
        }
        dirCache[fullDir] = id
        return id
    }

    private fun createDoc(parentId: String, mime: String, name: String): String {
        val created = DocumentsContract.createDocument(resolver, SafDocs.docUri(treeUri, parentId), mime, name)
            ?: throw FileNotFoundException("No se pudo crear '$name'")
        return DocumentsContract.getDocumentId(created)
    }

    private companion object {
        const val FILE_MIME = "application/octet-stream"
    }
}
