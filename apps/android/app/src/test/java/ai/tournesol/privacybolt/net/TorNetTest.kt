package ai.tournesol.privacybolt.net

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TorNetTest {
    private val onion = "a".repeat(56) + ".onion"
    private fun listener() = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 3000 }
    private fun port() = listener().use { it.localPort }

    @After fun cleanup() { TorNet.stopAll() }

    private fun stalledHandshake(tls: Boolean, all: Boolean) {
        listener().use { socks ->
            val local = port()
            if (tls) TorNet.startTlsForwarder(local, onion, 443, socks.localPort)
            else TorNet.startTcpForwarder(local, { onion }, 80, socks.localPort)
            Socket("127.0.0.1", local).use { browser ->
                browser.soTimeout = 3000
                socks.accept().use { upstream ->
                    upstream.soTimeout = 3000
                    // The SOCKS server deliberately never answers its greeting.
                    val input = upstream.getInputStream()
                    assertEquals(5, input.read()); assertEquals(1, input.read()); assertEquals(0, input.read())
                    if (all) TorNet.stopAll() else TorNet.stopPort(local)
                    assertEquals("Accepted browser connection must close", -1, browser.getInputStream().read())
                    assertEquals("Pending SOCKS connection must close", -1, input.read())
                }
            }
        }
    }

    @Test fun closesPendingTcpHandshakeOnActivityExit() = stalledHandshake(tls = false, all = false)
    @Test fun closesPendingTlsHandshakeOnAccountShutdown() = stalledHandshake(tls = true, all = true)

    @Test fun httpListenerClosesAndImmediatelyReopensWithActiveBrowserConnection() {
        val local = port()
        repeat(3) {
            TorNet.startHttpProxy(local, "http://$onion", port(), emptyMap())
            Socket("127.0.0.1", local).use { browser ->
                browser.soTimeout = 3000
                // An idle keep-alive browser connection must not retain the listening port.
                browser.getOutputStream().write("OPTIONS / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                val reader = browser.getInputStream().bufferedReader()
                assertTrue(reader.readLine().contains("204"))
                while (!reader.readLine().isNullOrEmpty()) { }
                TorNet.stopPort(local)
                // Binding must succeed immediately, before asynchronous pool cleanup ends.
                TorNet.startHttpProxy(local, "http://$onion", port(), emptyMap())
                assertEquals(-1, reader.read())
                TorNet.stopPort(local)
            }
        }
    }

    @Test fun perCallHtmlIsNotCachedButStaticAssetsRemainCacheable() {
        listener().use { upstream ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val responses = executor.submit {
                    for (type in listOf("text/html", "application/javascript")) {
                        upstream.accept().use { socket ->
                            socket.soTimeout = 3000
                            val reader = socket.getInputStream().bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) { }
                            val body = "<html><head></head><body>fixture</body></html>"
                            val headers = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                                "Content-Length: ${body.length}\r\nConnection: close\r\n" +
                                "Cache-Control: public, max-age=86400\r\nETag: fixture\r\n\r\n"
                            socket.getOutputStream().write((headers + body).toByteArray())
                        }
                    }
                }
                val local = port()
                TorNet.startHttpProxy(local, "http://$onion", upstream.localPort, emptyMap(), "<script>callPolicy()</script>")
                for (path in listOf("/room/?pp_session=fresh", "/assets/app.js")) {
                    val connection = java.net.URL("http://127.0.0.1:$local$path")
                        .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                    connection.connectTimeout = 3000; connection.readTimeout = 3000
                    try {
                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        if (path.startsWith("/room/")) {
                            assertEquals("no-store", connection.getHeaderField("Cache-Control"))
                            assertNull(connection.getHeaderField("ETag"))
                            assertTrue(body.contains("callPolicy()"))
                        } else {
                            assertEquals("public, max-age=86400", connection.getHeaderField("Cache-Control"))
                            assertEquals("fixture", connection.getHeaderField("ETag"))
                            assertFalse(body.contains("callPolicy()"))
                        }
                    } finally { connection.disconnect() }
                }
                responses.get(5, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun establishedStreamClosesAndPortCanBeReused() {
        listener().use { socks ->
            val local = port()
            val executor = Executors.newSingleThreadExecutor()
            try {
                repeat(2) {
                    TorNet.startTcpForwarder(local, { onion }, 80, socks.localPort)
                    val finished = executor.submit<Boolean> {
                        socks.accept().use { upstream ->
                            upstream.soTimeout = 3000
                            val input = java.io.DataInputStream(upstream.getInputStream())
                            input.readFully(ByteArray(3))
                            upstream.getOutputStream().write(byteArrayOf(5, 0))
                            val header = ByteArray(5); input.readFully(header)
                            input.readFully(ByteArray((header[4].toInt() and 255) + 2))
                            upstream.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
                            val payload = input.read()
                            upstream.getOutputStream().write(payload)
                            input.read() == -1
                        }
                    }
                    Socket("127.0.0.1", local).use { browser ->
                        browser.soTimeout = 3000
                        browser.getOutputStream().write(42)
                        assertEquals(42, browser.getInputStream().read())
                        TorNet.stopPort(local)
                        assertEquals(-1, browser.getInputStream().read())
                        assertTrue(finished.get(4, TimeUnit.SECONDS))
                    }
                }
            } finally { executor.shutdownNow() }
        }
    }
}
