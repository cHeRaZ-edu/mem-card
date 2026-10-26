package com.cheraz.memcard.core.source

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

/** Un save que vive en una carpeta normal (desktop, o rutas directas en Android). */
class FolderSource(private val root: File) : SaveSource, SaveSink {

    override fun list(): List<SaveFile> {
        if (!root.isDirectory) throw java.io.FileNotFoundException("No existe la carpeta ${root.path}")
        val out = ArrayList<SaveFile>()
        root.walkTopDown()
            .onEnter { dir -> dir == root || !java.nio.file.Files.isSymbolicLink(dir.toPath()) }
            .filter { it.isFile && !it.name.endsWith(TMP_SUFFIX) }
            .forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                out += SaveFile(rel, f.length())
            }
        return out
    }

    override fun open(path: String): InputStream = FileInputStream(resolve(path))

    /** Escribe a un temporal y lo renombra, para no dejar un archivo a medias si algo falla. */
    override fun write(path: String, content: InputStream) {
        val target = resolve(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}$TMP_SUFFIX")
        FileOutputStream(tmp).use { out -> content.copyTo(out) }
        if (target.exists() && !target.delete()) {
            tmp.delete()
            throw java.io.IOException("No se pudo reemplazar ${target.path} (¿el emulador lo tiene abierto?)")
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw java.io.IOException("No se pudo escribir ${target.path}")
        }
    }

    override fun delete(path: String) {
        val f = resolve(path)
        if (f.exists() && !f.delete()) throw java.io.IOException("No se pudo borrar ${f.path}")
    }

    private fun resolve(path: String): File {
        val f = File(root, path).canonicalFile
        require(f.path.startsWith(root.canonicalFile.path + File.separator)) { "Ruta fuera del save: $path" }
        return f
    }

    companion object {
        const val TMP_SUFFIX = ".memcard-tmp"
    }
}

/** Lee un zip hecho por SnapshotService como si fuera un save (para verificar su hash sin extraerlo). */
class ZipFileSource(private val zip: ZipFile) : SaveSource {
    override fun list(): List<SaveFile> =
        zip.entries().asSequence().filter { !it.isDirectory }.map { SaveFile(it.name, it.size) }.toList()

    override fun open(path: String): InputStream = zip.getInputStream(zip.getEntry(path))
}
