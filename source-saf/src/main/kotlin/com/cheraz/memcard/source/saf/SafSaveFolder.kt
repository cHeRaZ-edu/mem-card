package com.cheraz.memcard.source.saf

import android.content.Context
import android.net.Uri
import com.cheraz.memcard.core.source.SaveFile
import com.cheraz.memcard.core.source.SaveFolder
import java.io.InputStream

/** Carpeta de save elegida con el selector del sistema, para leer (snapshot) y escribir (restaurar). */
class SafSaveFolder(context: Context, treeUri: Uri) : SaveFolder {
    private val source = SafSaveSource(context, treeUri)
    private val sink = SafSaveSink(context, treeUri)

    override fun list(): List<SaveFile> = source.list()

    override fun open(path: String): InputStream = source.open(path)

    override fun write(path: String, content: InputStream) = sink.write(path, content)

    override fun delete(path: String) = sink.delete(path)
}
