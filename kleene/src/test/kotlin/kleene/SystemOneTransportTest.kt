package kleene

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ServerSocket
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/** Retries, backoff and cancellation of [SystemOneJudge] (spec §3.3). `runTest` skips backoff waits in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemOneTransportTest {

    private val busy = """{"detail":"busy"}"""

    /** A valid answer to the `urgent` question that [ask] sends. */
    private val ok = Reply(200, """{"model":"jev-1.13.0","answers":{"urgent.3173dac6eec83f01":{"type":"noul","noul":0.9}}}""")

    private fun judge(stub: StubServer, maxRetries: Int = 2, timeout: Duration = 10.seconds) =
        SystemOneJudge(stub.baseUrl, "jev-1.13.0", timeout = timeout, maxRetries = maxRetries)

    private suspend fun ask(judge: SystemOneJudge): Verdict<Boolean> =
        Kleene(judge).feels("Needs a response today", name = "urgent")("The upload failed and nothing was saved.")

    @Test
    fun `429 with Retry-After is retried, then succeeds`() = runTest {
        StubServer(Reply(429, busy, mapOf("Retry-After" to "0")), ok).use { stub ->
            val verdict = ask(judge(stub))

            assertIs<Verdict.Accepted<Boolean>>(verdict)
            assertEquals(2, stub.received.size)
            assertEquals(0, currentTime, "Retry-After: 0 means no wait")
        }
    }

    @Test
    fun `408, 429 and 5xx are retried`() = runTest {
        listOf(408, 429, 500, 503, 529).forEach { status ->
            StubServer(Reply(status, busy), ok).use { stub ->
                assertIs<Verdict.Accepted<Boolean>>(ask(judge(stub)), "HTTP $status")
                assertEquals(2, stub.received.size, "HTTP $status")
            }
        }
    }

    @Test
    fun `client errors and malformed bodies are sent once`() = runTest {
        listOf(Reply(400, busy), Reply(401, busy), Reply(403, busy), Reply(422, busy), Reply(200, "not json")).forEach { reply ->
            StubServer(reply).use { stub ->
                assertFailsWith<KleeneException> { ask(judge(stub)) }
                assertEquals(1, stub.received.size, "HTTP ${reply.status} was retried")
            }
        }
    }

    @Test
    fun `the server sets the wait with Retry-After in seconds or retry-after-ms`() = runTest {
        val seconds = Reply(429, busy, mapOf("Retry-After" to "2"))
        val millis = Reply(503, busy, mapOf("retry-after-ms" to "250"))
        StubServer(seconds, millis, ok).use { stub ->
            ask(judge(stub))

            assertEquals(2_250, currentTime)
        }
    }

    @Test
    fun `without a server hint, waits double from 0,5s up to 5s with 25 percent jitter`() = runTest {
        StubServer(Reply(503, busy)).use { stub ->
            assertFailsWith<KleeneException.Overloaded> { ask(judge(stub, maxRetries = 6)) }

            assertEquals(7, stub.received.size)
            // 0.5 + 1 + 2 + 4 + 5 + 5 = 17.5 s, ± 25%
            assertTrue(currentTime in 13_125..21_875, "waited ${currentTime}ms")
        }
    }

    @Test
    fun `backoff doubles from 0,5s, caps at 5s and jitters by at most 25 percent`() {
        assertEquals(500.milliseconds, backoff(0, jitter = 0.0))
        assertEquals(4.seconds, backoff(3, jitter = 0.0))
        assertEquals(5.seconds, backoff(4, jitter = 0.0))
        assertEquals(5.seconds, backoff(2_000, jitter = 0.0))
        assertEquals(375.milliseconds, backoff(0, jitter = -0.25))
        assertEquals(6.25.seconds, backoff(9, jitter = 0.25))
        repeat(1_000) { assertTrue(backoff(0) in 375.milliseconds..625.milliseconds) }
    }

    @Test
    fun `429 on every attempt is RateLimited with the last Retry-After`() = runTest {
        StubServer(Reply(429, busy, mapOf("Retry-After" to "1"))).use { stub ->
            val error = assertFailsWith<KleeneException.RateLimited> { ask(judge(stub, maxRetries = 2)) }

            assertEquals(3, stub.received.size)
            assertEquals(1.seconds, error.retryAfter)
            assertContains(error.message!!, "busy")
        }
    }

    @Test
    fun `529 on every attempt is Overloaded`() = runTest {
        StubServer(Reply(529, busy)).use { stub ->
            val error = assertFailsWith<KleeneException.Overloaded> { ask(judge(stub, maxRetries = 2)) }

            assertEquals(3, stub.received.size)
            assertEquals(529, error.status)
        }
    }

    @Test
    fun `every attempt timing out is a Timeout`() = runTest {
        StubServer(ok.copy(delay = 2.seconds)).use { stub ->
            assertFailsWith<KleeneException.Timeout> { ask(judge(stub, maxRetries = 1, timeout = 200.milliseconds)) }

            assertEquals(2, stub.received.size)
        }
    }

    @Test
    fun `an unreachable server is retried, then Overloaded`() = runTest {
        val port = ServerSocket(0).use { it.localPort }
        val judge = SystemOneJudge("http://127.0.0.1:$port", "jev-1.13.0", maxRetries = 2)

        val error = assertFailsWith<KleeneException.Overloaded> { ask(judge) }

        assertNull(error.status)
        assertIs<IOException>(error.cause)
        assertContains(error.message!!, "unreachable")
        assertTrue(currentTime in 1_125..1_875, "expected two backoff waits, waited ${currentTime}ms")
    }

    @Test
    fun `cancelling the caller during a backoff wait stops retrying`() = runBlocking {
        StubServer(Reply(503, busy, mapOf("Retry-After" to "30"))).use { stub ->
            var thrown: Throwable? = null
            val call = launch(Dispatchers.IO) {
                try {
                    ask(judge(stub))
                } catch (e: Throwable) {
                    thrown = e
                    throw e
                }
            }
            withTimeout(5.seconds) { while (stub.received.isEmpty()) delay(10) }
            delay(300) // the 503 arrives; the judge now waits 30 s

            val took = measureTime { call.cancelAndJoin() }

            assertIs<CancellationException>(thrown)
            assertTrue(took < 5.seconds, "cancellation took $took")
            assertEquals(1, stub.received.size)
        }
    }
}
