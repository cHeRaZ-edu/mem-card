package com.cheraz.memcard.lab

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Un snapshot guardado en el almacenamiento de la app (zip) con los datos que reporto el motor. */
data class SnapshotInfo(
    val zipPath: String,
    val hash: String,
    val zipSize: Long,
    val fileCount: Int,
    val totalBytes: Long,
)

/**
 * Estado de los laboratorios. Vive mientras viva el proceso, asi las pruebas siguen aunque se rote
 * la pantalla o se salga del laboratorio. Lo importante (carpeta y snapshots) tambien se guarda en disco.
 */
object LabSession {
    val safLog = LabLog()
    val btLog = LabLog()

    var treeUri: Uri? by mutableStateOf(null)
        private set
    var lastSnapshot: SnapshotInfo? by mutableStateOf(null)
        private set
    var lastBackup: SnapshotInfo? by mutableStateOf(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var prefs: SharedPreferences? = null
    private var bt: BtLab? = null

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("lab", Context.MODE_PRIVATE)
        prefs = p
        treeUri = p.getString("tree", null)?.let { Uri.parse(it) }
        lastSnapshot = readInfo(p, "snap")
        lastBackup = readInfo(p, "backup")
    }

    fun setTree(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            safLog.add("No se pudo guardar el permiso persistente: ${e.message}")
        }
        prefs?.edit()?.putString("tree", uri.toString())?.apply()
        treeUri = uri
        safLog.add("Carpeta elegida: ${Uri.decode(uri.toString())}")
    }

    /** El snapshot anterior se borra del disco: solo interesa el ultimo. */
    fun setSnapshot(info: SnapshotInfo) {
        val old = lastSnapshot
        if (old != null && old.zipPath != info.zipPath) File(old.zipPath).delete()
        lastSnapshot = info
        prefs?.let { writeInfo(it, "snap", info) }
    }

    /** Los respaldos NO se borran solos: son la red de seguridad al restaurar en la carpeta real. */
    fun setBackup(info: SnapshotInfo) {
        lastBackup = info
        prefs?.let { writeInfo(it, "backup", info) }
    }

    /** Corre [block] fuera del hilo principal, una operacion SAF a la vez. */
    fun runSaf(block: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Throwable) {
                safLog.add("Error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun bt(context: Context): BtLab = bt ?: BtLab(context, btLog).also { bt = it }

    private fun writeInfo(p: SharedPreferences, key: String, info: SnapshotInfo) {
        p.edit()
            .putString("$key.path", info.zipPath)
            .putString("$key.hash", info.hash)
            .putLong("$key.zip", info.zipSize)
            .putInt("$key.files", info.fileCount)
            .putLong("$key.bytes", info.totalBytes)
            .apply()
    }

    private fun readInfo(p: SharedPreferences, key: String): SnapshotInfo? {
        val path = p.getString("$key.path", null) ?: return null
        if (!File(path).exists()) return null
        return SnapshotInfo(
            zipPath = path,
            hash = p.getString("$key.hash", "") ?: "",
            zipSize = p.getLong("$key.zip", 0),
            fileCount = p.getInt("$key.files", 0),
            totalBytes = p.getLong("$key.bytes", 0),
        )
    }
}
