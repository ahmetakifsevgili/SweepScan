package com.sweepscan.server

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A very small HTTP/1.1 server for the shop's own Wi-Fi: one request per connection, JSON or a
 * static page back. Enough for a few phones and a browser on the shop PC; no TLS (the network is
 * the shop's, and every call needs the depot's PIN).
 */
class MiniHttp(private val port: Int, private val handler: (Request) -> Response) {

    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
        val remote: String,
    ) {
        val text: String get() = String(body, Charsets.UTF_8)
        fun header(name: String) = headers[name.lowercase()]
    }

    class Response(val status: Int, val type: String, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
        companion object {
            fun json(status: Int, json: String) = Response(status, "application/json; charset=utf-8", json.toByteArray())
            fun text(status: Int, text: String) = Response(status, "text/plain; charset=utf-8", text.toByteArray())
        }
    }

    @Volatile private var server: ServerSocket? = null
    private var pool: ExecutorService? = null

    val running: Boolean get() = server?.isClosed == false

    fun start() {
        if (running) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        val p = Executors.newFixedThreadPool(4)
        pool = p
        Thread({
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                p.execute { serve(socket) }
            }
        }, "depot-http").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        pool?.shutdownNow()
        pool = null
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 10_000
            val input = BufferedInputStream(s.getInputStream())
            val response = runCatching {
                val req = read(input, s.inetAddress?.hostAddress ?: "") ?: return
                handler(req)
            }.getOrElse { Response.text(500, "error: ${it.message}") }
            write(s.getOutputStream(), response)
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun read(input: InputStream, remote: String): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val target = parts[1]
        val q = target.indexOf('?')
        val path = if (q < 0) target else target.substring(0, q)
        val query = if (q < 0) emptyMap() else parseQuery(target.substring(q + 1))
        return Request(parts[0].uppercase(), path, query, headers, body, remote)
    }

    private fun write(out: OutputStream, r: Response) {
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
            .append("Content-Type: ").append(r.type).append("\r\n")
            .append("Content-Length: ").append(r.body.size).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            // No CORS headers: the panel is served from here, so no other web page may call the API.
            .append("Connection: close\r\n")
        r.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray())
        out.write(r.body)
        out.flush()
    }

    companion object {
        private const val MAX_BODY = 64 * 1024

        fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
            val i = it.indexOf('=')
            if (i < 0) URLDecoder.decode(it, "UTF-8") to ""
            else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }

        private fun reason(code: Int) = when (code) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 429 -> "Too Many Requests"; 503 -> "Service Unavailable"; else -> "Error"
        }
    }
}
