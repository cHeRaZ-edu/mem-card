package com.cheraz.memcard.drive

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Proxy HTTP CONNECT en 127.0.0.1 para el binario de muve.
 *
 * muve para Android esta compilado sin cgo, y asi Go no sabe resolver DNS en Android: busca
 * /etc/resolv.conf, que no existe. Con HTTPS_PROXY apuntando aqui, muve solo pide "CONNECT host:443"
 * y quien resuelve el nombre y abre el socket es la JVM, con el DNS de Android. El TLS sigue
 * siendo de punta a punta entre muve y el servidor; aqui solo pasan bytes cifrados.
 *
 * Solo acepta el puerto 443 y pide una contrasena aleatoria, para que otras apps del celular no lo usen.
 */
class ConnectProxy(private val allowedPorts: Set<Int> = setOf(443)) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val token = ByteArray(18).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val expectedAuth = "Basic " + Base64.getEncoder().encodeToString("memcard:$token".toByteArray())
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "memcard-proxy").apply { isDaemon = true } }

    /** Para HTTPS_PROXY. */
    val url: String get() = "http://memcard:$token@127.0.0.1:${server.localPort}"

    init {
        pool.execute(::acceptLoop)
    }

    override fun close() {
        runCatching { server.close() }
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            pool.execute { runCatching { handle(client) } }
        }
    }

    private fun handle(client: Socket) {
        client.use { serve(it) }
    }

    private fun serve(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val request = readLine(input) ?: return
        var auth = ""
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Proxy-Authorization:", ignoreCase = true)) auth = line.substringAfter(':').trim()
        }
        val parts = request.split(' ')
        if (auth != expectedAuth) return reply(out, "407 Proxy Authentication Required")
        if (parts.size < 2 || parts[0] != "CONNECT") return reply(out, "405 Method Not Allowed")
        val host = parts[1].substringBeforeLast(':')
        val port = parts[1].substringAfterLast(':').toIntOrNull()
        if (port == null || port !in allowedPorts || host.isEmpty()) return reply(out, "403 Forbidden")

        val upstream = try {
            Socket(host, port)
        } catch (e: IOException) {
            return reply(out, "502 Bad Gateway")
        }
        upstream.use {
            reply(out, "200 Connection established")
            val up = pool.submit {
                pipe(input, upstream.getOutputStream())
                runCatching { upstream.shutdownOutput() }
            }
            pipe(upstream.getInputStream(), out)
            runCatching { client.shutdownOutput() }
            runCatching { up.get() }
        }
    }

    private fun reply(out: OutputStream, status: String) {
        out.write("HTTP/1.1 $status\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
        }
    }

    /** Lee una linea terminada en CRLF (o LF) byte por byte, para no consumir lo que viene despues. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(b.toChar())
        }
    }
}
