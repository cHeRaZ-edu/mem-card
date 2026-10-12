package com.cheraz.memcard.source.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException

/** Consultas a DocumentsContract: mucho mas rapidas que DocumentFile, que hace una consulta por dato. */
internal object SafDocs {
    class Child(val id: String, val name: String, val isDir: Boolean, val size: Long?)

    private val COLUMNS = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
    )

    fun rootId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

    fun docUri(treeUri: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    fun children(resolver: ContentResolver, treeUri: Uri, parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val out = ArrayList<Child>()
        val cursor = resolver.query(uri, COLUMNS, null, null, null)
            ?: throw IOException("El proveedor de archivos no respondio al listar")
        cursor.use { c ->
            while (c.moveToNext()) {
                val size = if (c.isNull(3)) null else c.getLong(3)
                out += Child(
                    id = c.getString(0),
                    name = c.getString(1),
                    isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                    size = size,
                )
            }
        }
        return out
    }

    /** Id del documento en [subPath] (relativo a la raiz del arbol), o null si no existe. */
    fun resolveDir(resolver: ContentResolver, treeUri: Uri, subPath: String): String? {
        var id = rootId(treeUri)
        for (segment in subPath.split('/').filter { it.isNotEmpty() }) {
            val next = children(resolver, treeUri, id).firstOrNull { it.name == segment && it.isDir } ?: return null
            id = next.id
        }
        return id
    }
}
