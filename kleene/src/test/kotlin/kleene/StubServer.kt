package kleene

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/** A canned HTTP reply, sent after [delay]. */
data class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap(), val delay: Duration = Duration.ZERO)

/** An HTTP request as the stub received it. Header names are lower-case. */
data class Received(val method: String, val path: String, val headers: Map<String, List<String>>, val body: String) {
    fun header(name: String): String? = headers[name.lowercase()]?.single()
}

/**
 * A local System One server on an ephemeral port. Serves [replies] in order (the last one repeats)
 * and records every request it receives.
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
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}
