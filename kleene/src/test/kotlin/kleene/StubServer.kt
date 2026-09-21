package kleene

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration

/** A canned HTTP reply, sent after [delay]. */
data class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap(), val delay: Duration = Duration.ZERO)

/** An HTTP request as the stub received it. Header names are lower-case. */
data class Received(val method: String, val path: String, val headers: Map<String, List<String>>, val body: String) {
    fun header(name: String): String? = headers[name.lowercase()]?.single()
}

/**
 * A local System One server on an ephemeral port. Serves [replies] in order (the last one repeats)
 * and records every request as it arrives. Requests are handled concurrently, so a slow reply does not hold back the next.
 */
class StubServer(vararg replies: Reply) : AutoCloseable {
    private val replies = replies.toList()
    private val served = AtomicInteger()
    val received: MutableList<Received> = CopyOnWriteArrayList()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            received += Received(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestHeaders.mapKeys { it.key.lowercase() },
                exchange.requestBody.readAllBytes().decodeToString(),
            )
            val reply = this@StubServer.replies[minOf(served.getAndIncrement(), this@StubServer.replies.lastIndex)]
            Thread.sleep(reply.delay.inWholeMilliseconds)
            reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = reply.body.encodeToByteArray()
            exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}

/**
 * A raw HTTP server that answers every request with 200 headers and the first bytes of a 100-byte body,
 * then stalls with the connection open until [close].
 */
class StallingServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val connections: MutableList<Socket> = CopyOnWriteArrayList()

    /** How many connections the server has accepted. */
    val accepted: Int get() = connections.size

    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true) {
            while (true) {
                val connection = try {
                    server.accept()
                } catch (e: IOException) {
                    return@thread
                }
                connections += connection
                thread(isDaemon = true) { answerPartially(connection) }
            }
        }
    }

    private fun answerPartially(connection: Socket) = try {
        val request = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        while (request.readLine().orEmpty().isNotEmpty()) Unit // the request line and headers
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n"
        connection.getOutputStream().apply { write((head + """{"model":""").encodeToByteArray()) }.flush()
    } catch (e: IOException) {
        Unit // closed by the client or by close()
    }

    override fun close() {
        server.close()
        connections.forEach { it.close() }
    }
}
