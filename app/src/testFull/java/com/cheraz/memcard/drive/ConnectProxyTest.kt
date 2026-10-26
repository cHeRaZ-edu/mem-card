package com.cheraz.memcard.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Base64
import kotlin.concurrent.thread

class ConnectProxyTest {
    @Test
    fun tunnelsBytesOnlyWithPassword() {
        val echo = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            echo.accept().use { s -> s.getInputStream().copyTo(s.getOutputStream()) }
        }
        ConnectProxy(allowedPorts = setOf(echo.localPort)).use { proxy ->
            val u = URI(proxy.url)
            val auth = "Basic " + Base64.getEncoder().encodeToString(u.userInfo.toByteArray())

            Socket("127.0.0.1", u.port).use { s ->
                s.getOutputStream().write("CONNECT localhost:${echo.localPort} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                assertTrue(s.getInputStream().bufferedReader().readLine().contains("407"))
            }

            Socket("127.0.0.1", u.port).use { s ->
                val out = s.getOutputStream()
                out.write("CONNECT localhost:${echo.localPort} HTTP/1.1\r\nProxy-Authorization: $auth\r\n\r\nhola".toByteArray())
                s.shutdownOutput()
                val text = s.getInputStream().readBytes().decodeToString()
                assertTrue(text, text.startsWith("HTTP/1.1 200"))
                assertEquals("hola", text.substringAfter("\r\n\r\n"))
            }

            Socket("127.0.0.1", u.port).use { s ->
                s.getOutputStream().write("CONNECT localhost:22 HTTP/1.1\r\nProxy-Authorization: $auth\r\n\r\n".toByteArray())
                assertTrue(s.getInputStream().bufferedReader().readLine().contains("403"))
            }
        }
        echo.close()
    }
}
