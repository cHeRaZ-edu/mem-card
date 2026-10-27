package com.cheraz.memcard.lab

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cheraz.memcard.transport.rfcomm.FrameType
import com.cheraz.memcard.transport.rfcomm.RfcommClient
import com.cheraz.memcard.transport.rfcomm.RfcommConnection
import com.cheraz.memcard.transport.rfcomm.RfcommServer
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread

/** Laboratorio Bluetooth: un celular espera (servidor) y el otro mide (cliente). */
@SuppressLint("MissingPermission")
class BtLab(context: Context, private val log: LabLog) {
    enum class Mode { IDLE, SERVING, RUNNING }

    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    var mode by mutableStateOf(Mode.IDLE)
        private set

    @Volatile private var cancelled = false
    @Volatile private var server: RfcommServer? = null
    @Volatile private var active: RfcommConnection? = null

    val available: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun bondedDevices(): List<BluetoothDevice> =
        if (adapter == null || !hasPermission()) emptyList() else adapter.bondedDevices.orEmpty().sortedBy { it.name ?: it.address }

    fun describe(d: BluetoothDevice): String = "${d.name ?: "(sin nombre)"} · ${d.address}"

    private val deviceInfo: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    // ---------- servidor ----------

    fun startServer() {
        val ad = adapter ?: return log.add("Este equipo no tiene Bluetooth")
        if (mode != Mode.IDLE) return
        if (!hasPermission()) return log.add("Falta el permiso de Bluetooth")
        if (!ad.isEnabled) return log.add("Bluetooth está apagado")
        cancelled = false
        val srv = RfcommServer(ad)
        try {
            srv.open()
        } catch (e: IOException) {
            return log.add("No pude abrir el servidor: ${e.message}")
        }
        server = srv
        mode = Mode.SERVING
        log.add("Esperando conexiones… (${deviceInfo})")
        thread(name = "bt-lab-server") {
            while (!cancelled) {
                try {
                    val conn = srv.accept()
                    active = conn
                    log.add("Cliente conectado")
                    try {
                        serveSession(conn)
                    } catch (e: IOException) {
                        log.add("Sesión cortada: ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        conn.close()
                        active = null
                    }
                } catch (e: IOException) {
                    if (!cancelled) log.add("Servidor detenido: ${e.message}")
                    break
                }
            }
            srv.close()
            server = null
            mode = Mode.IDLE
            log.add("Servidor detenido")
        }
    }

    private fun serveSession(conn: RfcommConnection) {
        val f = conn.frames
        var digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        var expected = 0L
        var ok = true
        while (true) {
            val fr = f.read()
            when (fr.type) {
                FrameType.HELLO -> {
                    log.add("HELLO de: ${String(fr.payload, Charsets.UTF_8)}")
                    f.write(FrameType.HELLO, deviceInfo.toByteArray(Charsets.UTF_8))
                }
                FrameType.PING -> f.write(FrameType.PONG, fr.payload)
                FrameType.DATA -> {
                    val n = ByteBuffer.wrap(fr.payload).long
                    if (n != expected) ok = false
                    expected++
                    received += fr.payload.size
                    digest.update(fr.payload)
                }
                FrameType.DATA_END -> {
                    val sent = fr.payload
                    val mine = digest.digest()
                    val match = ok && sent.contentEquals(mine)
                    log.add("Datos: $received B, ${if (match) "hash OK" else "HASH/ORDEN MAL"}")
                    f.write(FrameType.DATA_ACK, byteArrayOf(if (match) 1 else 0))
                    digest = MessageDigest.getInstance("SHA-256")
                    received = 0; expected = 0; ok = true
                }
                FrameType.BYE -> {
                    log.add("Cliente se despidió")
                    return
                }
                else -> log.add("Frame desconocido: ${fr.type}")
            }
        }
    }

    fun stop() {
        cancelled = true
        try { server?.close() } catch (_: Exception) {}
        active?.close()
    }

    // ---------- cliente ----------

    fun ping(device: BluetoothDevice) = launchTest(device, "Ping ×100") { conn ->
        val times = ArrayList<Long>()
        val payload = ByteArray(16)
        for (i in 0 until 100) {
            if (cancelled) break
            val t = System.nanoTime()
            conn.frames.write(FrameType.PING, payload)
            conn.frames.read()
            times.add((System.nanoTime() - t) / 1000)
        }
        LabStats.summarize(times)?.let {
            log.add(String.format(Locale.US, "RTT µs: min %d · prom %.0f · p95 %d · max %d", it.min, it.avg, it.p95, it.max))
        }
    }

    fun throughput(device: BluetoothDevice, mb: Int) = launchTest(device, "Throughput $mb MB") { conn ->
        sendBlob(conn, mb.toLong() * 1024 * 1024)
    }

    /** Manda [total] bytes en frames de 32 KiB y mide hasta recibir el ACK. Devuelve false si falla. */
    private fun sendBlob(conn: RfcommConnection, total: Long): Boolean {
        val chunk = ByteArray(32 * 1024)
        java.util.Random(42).nextBytes(chunk)
        val digest = MessageDigest.getInstance("SHA-256")
        var sent = 0L
        var counter = 0L
        val t0 = System.nanoTime()
        while (sent < total && !cancelled) {
            val n = minOf(chunk.size.toLong(), total - sent).toInt()
            val buf = if (n == chunk.size) chunk else chunk.copyOf(n)
            ByteBuffer.wrap(buf).putLong(counter++)
            digest.update(buf)
            conn.frames.write(FrameType.DATA, buf, flush = false)
            sent += n
        }
        conn.frames.write(FrameType.DATA_END, digest.digest())
        val ack = conn.frames.read()
        val nanos = System.nanoTime() - t0
        val ok = ack.type == FrameType.DATA_ACK && ack.payload.firstOrNull() == 1.toByte()
        log.add(
            String.format(
                Locale.US, "%d B en %d ms → %.1f KB/s · %s",
                sent, nanos / 1_000_000, LabStats.kbPerSec(sent, nanos), if (ok) "integridad OK" else "INTEGRIDAD MAL",
            ),
        )
        return ok
    }

    fun stability(device: BluetoothDevice, cycles: Int = 20) {
        val ad = adapter ?: return log.add("Este equipo no tiene Bluetooth")
        if (mode != Mode.IDLE) return log.add("Ocupado")
        if (!hasPermission()) return log.add("Falta el permiso de Bluetooth")
        mode = Mode.RUNNING
        cancelled = false
        thread(name = "bt-lab-stability") {
            log.add("— Estabilidad ×$cycles → ${describe(device)} —")
            val connectMs = ArrayList<Long>()
            var failures = 0
            for (i in 1..cycles) {
                if (cancelled) break
                val t = System.nanoTime()
                try {
                    val conn = RfcommClient.connect(ad, device)
                    active = conn
                    try {
                        connectMs.add((System.nanoTime() - t) / 1_000_000)
                        handshakeQuiet(conn)
                        if (!sendBlob(conn, 256 * 1024)) failures++
                        conn.frames.write(FrameType.BYE)
                    } finally {
                        conn.close()
                        active = null
                    }
                } catch (e: Exception) {
                    failures++
                    log.add("Ciclo $i falló: ${e.javaClass.simpleName}: ${e.message}")
                }
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
            LabStats.summarize(connectMs)?.let {
                log.add(String.format(Locale.US, "Conexión ms: min %d · prom %.0f · p95 %d · max %d", it.min, it.avg, it.p95, it.max))
            }
            log.add("Fallos: $failures de $cycles")
            mode = Mode.IDLE
        }
    }

    private fun launchTest(device: BluetoothDevice, name: String, body: (RfcommConnection) -> Unit) {
        val ad = adapter ?: return log.add("Este equipo no tiene Bluetooth")
        if (mode != Mode.IDLE) return log.add("Ocupado")
        if (!hasPermission()) return log.add("Falta el permiso de Bluetooth")
        mode = Mode.RUNNING
        cancelled = false
        thread(name = "bt-lab-client") {
            log.add("— $name → ${describe(device)} —")
            try {
                withConnection(ad, device, body)
            } catch (e: Exception) {
                log.add("Falló: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                mode = Mode.IDLE
            }
        }
    }

    private fun withConnection(ad: BluetoothAdapter, dev: BluetoothDevice, body: (RfcommConnection) -> Unit) {
        val t0 = System.nanoTime()
        val conn = RfcommClient.connect(ad, dev)
        active = conn
        try {
            log.add("Conectado en ${(System.nanoTime() - t0) / 1_000_000} ms")
            handshake(conn)
            body(conn)
            conn.frames.write(FrameType.BYE)
        } finally {
            conn.close()
            active = null
        }
    }

    private fun handshake(conn: RfcommConnection) {
        conn.frames.write(FrameType.HELLO, deviceInfo.toByteArray(Charsets.UTF_8))
        val r = conn.frames.read()
        log.add("Servidor: ${String(r.payload, Charsets.UTF_8)}")
    }
}

private fun BtLab.handshakeQuiet(conn: RfcommConnection) {
    conn.frames.write(FrameType.HELLO, ByteArray(0))
    conn.frames.read()
}
