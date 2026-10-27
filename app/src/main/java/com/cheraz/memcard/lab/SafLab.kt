package com.cheraz.memcard.lab

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.cheraz.memcard.core.engine.SaveRestorer
import com.cheraz.memcard.core.engine.SnapshotService
import com.cheraz.memcard.core.source.SaveFile
import com.cheraz.memcard.source.saf.SafSaveSink
import com.cheraz.memcard.source.saf.SafSaveSource
import java.io.File

/** Operaciones del laboratorio SAF. Todas bloquean: llamarlas con [LabSession.runSaf]. */
object SafLab {
    /** Subcarpeta que usa la prueba de restauracion. Se borra al terminar. */
    private const val LAB_DIR = "_memcard_lab"

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    fun list(context: Context, tree: Uri, log: LabLog) {
        log.add("— Listar —")
        val t0 = System.nanoTime()
        val files = SafSaveSource(context, tree).list()
        log.add("DocumentsContract: ${files.size} archivos, ${files.sumOf { it.size }} B en ${ms(t0)} ms")

        val t1 = System.nanoTime()
        var count = 0
        var bytes = 0L
        fun walk(dir: DocumentFile) {
            for (f in dir.listFiles()) {
                if (f.isDirectory) {
                    walk(f)
                } else {
                    count++
                    bytes += f.length()
                }
            }
        }
        DocumentFile.fromTreeUri(context, tree)?.let { walk(it) }
        log.add("DocumentFile:      $count archivos, $bytes B en ${ms(t1)} ms")

        files.sortedBy { it.path }.take(40).forEach { log.add("  ${it.path}  (${it.size} B)") }
        if (files.size > 40) log.add("  … y ${files.size - 40} más")
        if (files.any { it.path.startsWith("$LAB_DIR/") }) {
            log.add("Aviso: existe $LAB_DIR dentro de la carpeta (sobra de una prueba cortada); borrala a mano.")
        }
    }

    /** Snapshot de la carpeta + segunda lectura para ver si el hash es estable. */
    fun snapshot(context: Context, tree: Uri, log: LabLog, prefix: String = "snap"): SnapshotInfo? {
        log.add("— ${if (prefix == "snap") "Snapshot" else "Respaldo"} —")
        val dir = File(context.filesDir, "lab").apply { mkdirs() }
        val out = File(dir, "$prefix-${System.currentTimeMillis()}.zip")
        val source = SafSaveSource(context, tree)
        val service = SnapshotService()
        return try {
            val t0 = System.nanoTime()
            val result = out.outputStream().buffered().use { service.snapshot(source, it) }
            val firstMs = ms(t0)
            val t1 = System.nanoTime()
            val again = service.snapshot(source, null)
            val secondMs = ms(t1)

            log.add("${result.fileCount} archivos, ${result.totalBytes} B → zip de ${result.zipSize} B en $firstMs ms")
            log.add("Hash: ${result.hash}")
            log.add(
                if (again.hash == result.hash) {
                    "Segunda lectura ($secondMs ms): mismo hash, estable"
                } else {
                    "Segunda lectura ($secondMs ms): HASH DISTINTO, el save cambió entre lecturas (¿emulador abierto?)"
                },
            )
            SnapshotInfo(out.absolutePath, result.hash, result.zipSize, result.fileCount, result.totalBytes)
        } catch (e: Exception) {
            out.delete()
            log.add("Falló: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Restaura el snapshot en una subcarpeta de prueba, verifica el hash y la borra. No toca los saves. */
    fun restoreToLabFolder(context: Context, tree: Uri, snap: SnapshotInfo, log: LabLog) {
        log.add("— Restaurar en carpeta de prueba ($LAB_DIR) —")
        val sink = SafSaveSink(context, tree, LAB_DIR)
        try {
            sink.reset()
            restoreZip(context, tree, LAB_DIR, snap, emptyList(), log)
        } catch (e: Exception) {
            log.add("Falló: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                sink.deleteRoot()
                log.add("Carpeta de prueba borrada")
            } catch (e: Exception) {
                log.add("No pude borrar $LAB_DIR (${e.message}); borrala a mano.")
            }
        }
    }

    /** Respalda el estado actual y deja la carpeta REAL igual al snapshot. */
    fun restoreToReal(context: Context, tree: Uri, snap: SnapshotInfo, log: LabLog) {
        log.add("— Restaurar en la carpeta REAL —")
        val backup = snapshot(context, tree, log, "backup")
        if (backup == null) {
            log.add("No se pudo respaldar el estado actual: no se tocó nada.")
            return
        }
        LabSession.setBackup(backup)
        log.add("Respaldo guardado (hash ${backup.hash.take(12)}…). Restaurando el snapshot…")
        val existing = SafSaveSource(context, tree).list()
        val ok = restoreZip(context, tree, "", snap, existing, log)
        if (!ok) log.add("Usa «Revertir al respaldo» para volver a como estaba.")
    }

    fun revertToBackup(context: Context, tree: Uri, backup: SnapshotInfo, log: LabLog) {
        log.add("— Revertir al respaldo —")
        val existing = SafSaveSource(context, tree).list()
        restoreZip(context, tree, "", backup, existing, log)
    }

    private fun restoreZip(
        context: Context,
        tree: Uri,
        subPath: String,
        zip: SnapshotInfo,
        existing: List<SaveFile>,
        log: LabLog,
    ): Boolean {
        val sink = SafSaveSink(context, tree, subPath)
        val t0 = System.nanoTime()
        val result = File(zip.zipPath).inputStream().buffered().use { SaveRestorer().restore(it, existing, sink) }
        log.add("Restaurado: ${result.written} escritos, ${result.deleted} borrados, ${result.bytes} B en ${ms(t0)} ms")

        val check = SnapshotService().snapshot(SafSaveSource(context, tree, subPath), null)
        val ok = check.hash == zip.hash
        log.add(
            if (ok) {
                "Verificación OK: el hash coincide (${zip.hash.take(12)}…)"
            } else {
                "VERIFICACIÓN FALLÓ: esperado ${zip.hash.take(12)}… y quedó ${check.hash.take(12)}…"
            },
        )
        return ok
    }
}
