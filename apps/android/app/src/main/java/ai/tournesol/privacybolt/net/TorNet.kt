package ai.tournesol.privacybolt.net

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Bridges the Element Call WebView (which Chromium won't let touch .onion) to the
 * box's onion services over the embedded Tor. The WebView only talks to
 * 127.0.0.1 over PLAIN http/ws (allowed from the https EC page via mixed-content),
 * so there's no local-cert problem; TLS happens only on the onion side. We rewrite
 * .onion URLs in responses (well-known rtc_foci, lk-jwt's SFU url) to localhost.
 */
object TorNet {
    private const val TAG = "PpTorNet"

    // Cold-onion resilience: the FIRST fetch after a call starts often hits a Tor
    // circuit to the box's onion that's still building (10-30s for a fresh hidden
    // service). A single hard failure there makes Element Call receive an error for the
    // SFU JWT, destructure an undefined config, and crash the whole call ("first call
    // fails, retry works"). Retry connection failures a few times so the JWT arrives.
    private const val UPSTREAM_ATTEMPTS = 4
    private const val UPSTREAM_RETRY_MS = 2000L

    private val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
    private fun trustAllCtx() = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }

    /** OkHttp over Tor's HTTP tunnel (CONNECT => remote DNS). [trustAllCerts] is for
     *  the box's self-signed .onion endpoints only — the .onion IS the auth there.
     *  For CLEARNET hosts (call.element.io, reached over a Tor circuit whose exit is
     *  an untrusted MITM) we keep NORMAL CA validation, so a hostile exit can't swap
     *  the Element Call bundle. */
    private fun torClient(httpProxyPort: Int, trustAllCerts: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpProxyPort)))
            .retryOnConnectionFailure(true)
            .followRedirects(false)
            .followSslRedirects(false)
        if (trustAllCerts) {
            b.sslSocketFactory(trustAllCtx().socketFactory, trustAll[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
        }
        return b.build()
    }

    private fun isOnion(s: String): Boolean {
        val uri = runCatching { java.net.URI(s) }.getOrNull() ?: return false
        return uri.scheme in setOf("http", "https") && uri.userInfo == null
            && uri.host?.matches(Regex("[a-z2-7]{56}\\.onion")) == true
    }

    private val started = java.util.concurrent.ConcurrentHashMap<Int, Any>()
    private val activeSockets = java.util.concurrent.ConcurrentHashMap<Int, MutableSet<Socket>>()
    private val acceptThreads = java.util.concurrent.ConcurrentHashMap<ServerSocket, Thread>()
    private fun track(port: Int, owner: ServerSocket, socket: Socket): Boolean = synchronized(this) {
        // A port may already have been reopened for a new call/account. Only its
        // original listener may register a connection, including pending SOCKS handshakes.
        if (started[port] !== owner) { socket.close(); return false }
        activeSockets.getOrPut(port) { HashSet() }.add(socket)
        true
    }

    private class HttpProxy(val server: NanoHTTPD, val client: OkHttpClient) {
        @Volatile var stopped = false
        var listener: ServerSocket? = null
        val listenerStopped = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()
    }

    /** One-shot GET of an onion URL over Tor, with response-body rewrites — used by
     *  the WebView's shouldInterceptRequest to serve .onion requests Chromium would
     *  otherwise block (e.g. server-name /.well-known discovery). */
    fun fetchRewritten(url: String, httpProxyPort: Int, rewrites: Map<String, String>): Triple<String, Int, ByteArray> {
        val client = torClient(httpProxyPort, isOnion(url))
        client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            var text = resp.body?.string() ?: ""
            for ((a, b) in rewrites) text = text.replace(a, b)
            val ct = (resp.header("content-type") ?: "application/json").substringBefore(";").trim()
            Log.i(TAG, "fetchRewritten $url -> ${resp.code} (${text.length}b)")
            return Triple(ct, resp.code, text.toByteArray())
        }
    }

    /** Plain-HTTP local proxy: http://127.0.0.1:localPort -> https://onion:port (over Tor),
     *  rewriting `rewrites` in response bodies. If [injectHtml] is set, it is inserted
     *  into HTML responses (right after <head>) — used to patch the Element Call app
     *  in-page (e.g. force WebRTC media through a localhost TURN bridge over Tor). */
    @Synchronized fun startHttpProxy(localPort: Int, onionBase: String, httpProxyPort: Int, rewrites: Map<String, String>, injectHtml: String? = null) {
        if (started.containsKey(localPort)) return
        // CA-validate clearnet (call.element.io); trust-all only for the self-signed onion.
        val client = torClient(httpProxyPort, isOnion(onionBase))
        lateinit var proxy: HttpProxy
        val server = object : NanoHTTPD("127.0.0.1", localPort) {
            override fun createServerRunnable(timeout: Int): ServerRunnable = object : ServerRunnable(timeout) {
                override fun run() {
                    try { super.run() } finally { proxy.listenerStopped.countDown() }
                }
            }
            override fun serve(session: IHTTPSession): Response {
                if (proxy.stopped) return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Call connection closed")
                val origin = session.headers["origin"]
                val expected = "http://127.0.0.1:${ai.tournesol.privacybolt.ElementCallActivity.EC_LOCAL}"
                if ((origin != null && origin != expected) || session.headers["sec-fetch-site"] == "cross-site")
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Untrusted origin")
                if (session.method == Method.OPTIONS) {
                    val r = newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""); cors(r); return r
                }
                return try {
                    val url = onionBase + session.uri + (session.queryParameterString?.let { "?$it" } ?: "")
                    Log.i(TAG, "serve $localPort ${session.method} ${session.uri}")
                    val reqB = Request.Builder().url(url)
                    session.headers.forEach { (k, v) -> if (k.lowercase() !in HOP) reqB.addHeader(k, v) }
                    val m = session.method.name
                    if (m == "POST" || m == "PUT" || m == "PATCH") {
                        val files = HashMap<String, String>(); session.parseBody(files)
                        val body = files["postData"] ?: ""
                        reqB.method(m, body.toRequestBody(session.headers["content-type"]?.toMediaTypeOrNull()))
                    } else reqB.method(m, null)
                    // Retry the upstream fetch on a cold-circuit connection failure (see
                    // UPSTREAM_ATTEMPTS): a failed JWT fetch would otherwise crash the call.
                    val builtReq = reqB.build()
                    var upstreamErr: java.io.IOException? = null
                    var built: Response? = null
                    for (attempt in 1..UPSTREAM_ATTEMPTS) {
                      if (proxy.stopped) throw java.io.IOException("Connection closed")
                      try {
                        val call = synchronized(this@TorNet) {
                            if (proxy.stopped) throw java.io.IOException("Connection closed")
                            client.newCall(builtReq).also { proxy.calls.add(it) }
                        }
                        built = try { call.execute().use { resp ->
                        Log.i(TAG, "  upstream ${session.uri} -> ${resp.code}")
                        val ctType = resp.header("content-type") ?: "application/octet-stream"
                        val status = object : Response.IStatus {
                            override fun getDescription() = "" + resp.code
                            override fun getRequestStatus() = resp.code
                        }
                        // Only TEXT responses get the onion->localhost rewrite; binary
                        // (fonts, audio, wasm, images) MUST pass through byte-exact.
                        val textual = ctType.contains("json") || ctType.contains("text") ||
                            ctType.contains("javascript") || ctType.contains("xml") ||
                            ctType.contains("css") || ctType.contains("html") || ctType.contains("svg")
                        val out = if (textual) {
                            var text = resp.body?.string() ?: ""
                            for ((a, b) in rewrites) text = text.replace(a, b)
                            if (injectHtml != null && ctType.contains("html")) {
                                text = if (text.contains("<head>")) text.replaceFirst("<head>", "<head>$injectHtml")
                                else injectHtml + text
                            }
                            newFixedLengthResponse(status, ctType, text)
                        } else {
                            val bytes = resp.body?.bytes() ?: ByteArray(0)
                            newFixedLengthResponse(status, ctType, java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
                        }
                        val perCallHtml = injectHtml != null && ctType.contains("html")
                        resp.headers.forEach { (k, v) ->
                            val lk = k.lowercase()
                            // strip CSP / framing / CORP so the localhost-served EC can
                            // frame + connect to our other localhost bridges.
                            if (!(perCallHtml && lk in setOf("cache-control", "etag", "last-modified", "expires")) &&
                                lk !in HOP && lk != "content-type" && !lk.startsWith("access-control-") &&
                                lk != "content-security-policy" && lk != "content-security-policy-report-only" &&
                                lk != "x-frame-options" && lk != "cross-origin-embedder-policy" &&
                                lk != "cross-origin-opener-policy" && lk != "cross-origin-resource-policy"
                            ) out.addHeader(k, v)
                        }
                        // The transformed HTML contains this call's media policy and
                        // bridge configuration, not the upstream cacheable document.
                        if (perCallHtml) out.addHeader("Cache-Control", "no-store")
                        cors(out); out
                        } } finally { proxy.calls.remove(call) }
                        break
                      } catch (io: java.io.IOException) {
                        upstreamErr = io
                        Log.d(TAG, "upstream $localPort ${session.uri} attempt $attempt/$UPSTREAM_ATTEMPTS: ${io.message}")
                        if (attempt < UPSTREAM_ATTEMPTS) Thread.sleep(UPSTREAM_RETRY_MS)
                      }
                    }
                    built ?: throw (upstreamErr ?: java.io.IOException("upstream unreachable"))
                } catch (t: Throwable) {
                    Log.e(TAG, "proxy $localPort error", t)
                    newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Box connection unavailable")
                }
            }
        }
        proxy = HttpProxy(server, client)
        server.serverSocketFactory = NanoHTTPD.ServerSocketFactory {
            ServerSocket().also { proxy.listener = it }
        }
        try { server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        catch (error: Exception) { proxy.stopped = true; closeListener(proxy); throw error }
        started[localPort] = proxy
        Log.i(TAG, "http proxy 127.0.0.1:$localPort -> $onionBase  rewrites=${rewrites.size}")
    }

    /** Plain-TCP local listener (ws://127.0.0.1:localPort) bridged to the onion's
     *  wss endpoint: accept plaintext, open a TLS connection to onion:port over Tor
     *  SOCKS5 (domain => remote DNS), and pipe. Carries the SFU WebSocket. */
    @Synchronized fun startTlsForwarder(localPort: Int, onionHost: String, onionPort: Int, socksPort: Int) {
        require(onionHost.matches(Regex("[a-z2-7]{56}\\.onion")))
        if (started.containsKey(localPort)) return
        val ss = ServerSocket(); ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort))
        started[localPort] = ss
        thread(start = false, name = "fwd-$localPort") {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Throwable) { break }
                if (track(localPort, ss, client)) thread { bridge(client, onionHost, onionPort, socksPort, localPort, ss) }
            }
        }.also { acceptThreads[ss] = it; it.start() }
        Log.i(TAG, "tls forwarder ws 127.0.0.1:$localPort -> wss $onionHost:$onionPort")
    }

    /** Plain-TCP local listener bridged to an onion:port over Tor SOCKS5 (no TLS).
     *  Carries TURN-over-TCP to the box's coturn so WebRTC media can ride Tor. The
     *  destination onion is resolved per-connection via [host] — the joiner only
     *  learns the call's focus box (where coturn lives) after the call state arrives,
     *  so we point this at it dynamically. */
    @Synchronized fun startTcpForwarder(localPort: Int, host: () -> String, onionPort: Int, socksPort: Int) {
        if (started.containsKey(localPort)) return
        val ss = ServerSocket(); ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort))
        started[localPort] = ss
        thread(start = false, name = "tcpfwd-$localPort") {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Throwable) { break }
                if (track(localPort, ss, client)) thread { tcpBridge(client, host, onionPort, socksPort, localPort, ss) }
            }
        }.also { acceptThreads[ss] = it; it.start() }
        Log.i(TAG, "tcp forwarder 127.0.0.1:$localPort -> :$onionPort (dynamic onion)")
    }

    /** Tear down every local bridge/forwarder started for a call. Called when the
     *  call activity is destroyed so the loopback listeners + their Tor circuits
     *  don't linger (and so the next call starts them fresh). */
    fun stopPort(port: Int) {
        val (item, sockets) = synchronized(this) {
            val item = started.remove(port)
            if (item is HttpProxy) item.stopped = true
            item to activeSockets.remove(port)?.toList().orEmpty()
        }
        sockets.forEach { runCatching { it.close() } }
        closeListener(item)
    }

    fun stopAll() {
        val (items, sockets) = synchronized(this) {
            val items = started.values.toList()
            items.filterIsInstance<HttpProxy>().forEach { it.stopped = true }
            started.clear()
            val sockets = activeSockets.values.flatMap { it.toList() }
            activeSockets.clear()
            items to sockets
        }
        sockets.forEach { runCatching { it.close() } }
        items.forEach { closeListener(it) }
        Log.i(TAG, "stopAll: tore down ${items.size} bridges")
    }

    private fun closeListener(item: Any?) {
        when (item) {
            is HttpProxy -> {
                // Release the listening port before any pooled upstream cleanup can fail.
                // Connected-socket cleanup runs off Android's main thread; a StrictMode
                // exception must never skip stopping the HTTP listener for the next call.
                runCatching { item.listener?.close() }
                // close() can return before a blocked accept() has released its OS
                // descriptor. Wait for that reader before allowing immediate rebind.
                item.listenerStopped.await(2, java.util.concurrent.TimeUnit.SECONDS)
                thread(name = "call-proxy-cleanup", isDaemon = true) {
                    runCatching { item.server.stop() }
                    item.calls.forEach { runCatching { it.cancel() } }
                    runCatching { item.client.dispatcher.cancelAll() }
                    runCatching { item.client.connectionPool.evictAll() }
                }
            }
            is ServerSocket -> {
                runCatching { item.close() }
                acceptThreads.remove(item)?.join(2_000)
            }
        }
    }

    /** Pre-build the Tor circuit to an onion service so the first real connection
     *  (e.g. WebRTC's TURN allocation) doesn't pay the 10-30s circuit-build cost and
     *  time out. Tor caches the circuit, so subsequent streams to the same onion attach
     *  fast. Fire-and-forget with a couple of retries; warms a few seconds before use. */
    fun prewarm(host: String, port: Int, socksPort: Int) {
        thread(name = "prewarm-$host-$port") {
            repeat(4) { attempt ->
                try {
                    val s = socks5(host, port, socksPort)
                    Thread.sleep(800)            // hold briefly so the circuit settles
                    runCatching { s.close() }
                    Log.i(TAG, "prewarmed Tor circuit to :$port (attempt ${attempt + 1})")
                    return@thread
                } catch (t: Throwable) {
                    Log.d(TAG, "prewarm :$port attempt ${attempt + 1} failed: ${t.message}")
                    Thread.sleep(1500)
                }
            }
            Log.w(TAG, "prewarm :$port gave up after retries")
        }
    }

    private fun tcpBridge(client: Socket, host: () -> String, port: Int, socksPort: Int, localPort: Int, owner: ServerSocket) {
        var raw: Socket? = null
        try {
            val remote = socks5(host(), port, socksPort) { raw = it; track(localPort, owner, it) }
            if (client.isClosed) return
            val t1 = thread { copy(client.getInputStream(), remote.getOutputStream()); runCatching { remote.shutdownOutput() } }
            copy(remote.getInputStream(), client.getOutputStream())
            t1.join(200)
        } catch (_: java.io.IOException) {
            // Closed bridge / unavailable circuit; no credential-bearing diagnostics.
        } finally {
            runCatching { client.close() }; runCatching { raw?.close() }
            synchronized(this) { activeSockets[localPort]?.remove(client); raw?.let { activeSockets[localPort]?.remove(it) } }
        }
    }

    private fun bridge(client: Socket, host: String, port: Int, socksPort: Int, localPort: Int, owner: ServerSocket) {
        var raw: Socket? = null
        var tls: SSLSocket? = null
        try {
            raw = socks5(host, port, socksPort) { raw = it; track(localPort, owner, it) }
            tls = trustAllCtx().socketFactory.createSocket(raw, host, port, true) as SSLSocket
            tls.soTimeout = 60_000
            tls.startHandshake()
            tls.soTimeout = 0
            val remote = tls
            val t1 = thread { copy(client.getInputStream(), remote.getOutputStream()) }
            copy(tls.getInputStream(), client.getOutputStream())
            t1.join(200)
        } catch (_: java.io.IOException) {
            // Unavailable/closed circuit. Do not include private endpoints in errors.
        } finally {
            runCatching { client.close() }; runCatching { tls?.close() }; runCatching { raw?.close() }
            synchronized(this) { activeSockets[localPort]?.remove(client); raw?.let { activeSockets[localPort]?.remove(it) } }
        }
    }

    private fun socks5(host: String, port: Int, socksPort: Int, register: (Socket) -> Boolean = { true }): Socket {
        val tor = Socket()
        try {
        if (!register(tor)) throw java.io.IOException("Connection closed")
        tor.soTimeout = 60_000
        tor.connect(InetSocketAddress("127.0.0.1", socksPort), 15000)
        val ti = tor.getInputStream(); val to = tor.getOutputStream()
        to.write(byteArrayOf(5, 1, 0)); to.flush()
        val greeting = ByteArray(2); readFully(ti, greeting, 2)
        if (greeting[0].toInt() != 5 || greeting[1].toInt() != 0) throw java.io.IOException("SOCKS authentication unavailable")
        val h = host.toByteArray(); val req = ByteArrayOutputStream()
        if (h.isEmpty() || h.size > 255 || port !in 1..65535) throw java.io.IOException("Invalid destination")
        req.write(byteArrayOf(5, 1, 0, 3)); req.write(h.size); req.write(h)
        req.write((port ushr 8) and 0xff); req.write(port and 0xff)
        to.write(req.toByteArray()); to.flush()
        val head = ByteArray(4); readFully(ti, head, 4)
        if (head[0].toInt() != 5 || head[1].toInt() != 0 || head[2].toInt() != 0) throw java.io.IOException("SOCKS connection failed")
        val skip = when (head[3].toInt()) { 1 -> 6; 4 -> 18; 3 -> { val l = ByteArray(1); readFully(ti, l, 1); (l[0].toInt() and 0xff) + 2 }; else -> throw java.io.IOException("Invalid SOCKS reply") }
        if (skip > 0) readFully(ti, ByteArray(skip), skip)
        tor.soTimeout = 0
        return tor
        } catch (t: Throwable) { runCatching { tor.close() }; throw t }
    }

    private fun copy(inp: java.io.InputStream, out: java.io.OutputStream) {
        val buf = ByteArray(16 * 1024)
        try { while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); out.flush() } } catch (_: Throwable) {}
    }
    private fun readFully(inp: java.io.InputStream, b: ByteArray, n: Int) {
        var off = 0; while (off < n) { val r = inp.read(b, off, n - off); if (r < 0) throw java.io.EOFException(); off += r }
    }
    private fun cors(r: NanoHTTPD.Response) {
        r.addHeader("Access-Control-Allow-Origin", "http://127.0.0.1:${ai.tournesol.privacybolt.ElementCallActivity.EC_LOCAL}")
        r.addHeader("Vary", "Origin")
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        r.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Requested-With")
        // Chromium Private Network Access: a secure public page (call.element.io)
        // calling 127.0.0.1 is blocked unless we opt in.
        r.addHeader("Access-Control-Allow-Private-Network", "true")
        r.addHeader("Access-Control-Max-Age", "86400")
    }
    private val HOP = setOf("host", "content-length", "connection", "accept-encoding", "transfer-encoding", "keep-alive", "proxy-connection", "te", "trailer", "upgrade")
}
