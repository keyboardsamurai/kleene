package kleene

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SystemOneJudgeTest {

    private enum class Team { Billing, Technical, Other }

    private val ticket = "The upload failed and nothing was saved."

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/systemone/$name")) { "missing fixture $name" }.readText()

    /** [fixture] with only the answers to [ids], so a smaller ask stays valid. */
    private fun answersFrom(fixture: String, vararg ids: String): String {
        val root = Json.parseToJsonElement(resource(fixture)).jsonObject
        val answers = root.getValue("answers").jsonObject
        return buildJsonObject {
            put("model", root.getValue("model"))
            putJsonObject("answers") { ids.forEach { put(it, answers.getValue(it)) } }
        }.toString()
    }

    private class Questions(ai: Kleene) {
        val urgent by ai.feels("Needs a response today")
        val route by ai.choose(
            "Which team should handle this?",
            "billing" to Team.Billing,
            "technical" to Team.Technical,
            "other" to Team.Other,
        )
        val clarity by ai.score("How clearly is the problem described?", "Unclear", "Partly clear", "Clear")
    }

    private fun judge(stub: StubServer, apiKey: String? = null) =
        SystemOneJudge(baseUrl = stub.baseUrl, model = "jev-1.13.0", apiKey = apiKey)

    @Test
    fun `feels request matches the golden file byte for byte`() = runBlocking {
        StubServer(Reply(200, answersFrom("response-typesafe-captured.json", "urgent.3173dac6eec83f01"))).use { stub ->
            val q = Questions(Kleene(judge(stub)))

            q.urgent(ticket)

            val request = stub.received.single()
            assertEquals("POST", request.method)
            assertEquals("/v1/systemone", request.path)
            assertEquals("application/json", request.header("Content-Type"))
            assertEquals(resource("request-feels.json"), request.body)
        }
    }

    @Test
    fun `choose request matches the golden file byte for byte`() = runBlocking {
        StubServer(Reply(200, answersFrom("response-typesafe-captured.json", "route.baada429096e81f4"))).use { stub ->
            val q = Questions(Kleene(judge(stub)))

            q.route(ticket)

            assertEquals(resource("request-choose.json"), stub.received.single().body)
        }
    }

    @Test
    fun `score request matches the golden file byte for byte`() = runBlocking {
        StubServer(Reply(200, answersFrom("response-typesafe-captured.json", "clarity.158a008ffc043dfa"))).use { stub ->
            val q = Questions(Kleene(judge(stub)))

            q.clarity(ticket)

            assertEquals(resource("request-score.json"), stub.received.single().body)
        }
    }

    private class Outcomes(val urgent: Evidence<Boolean>, val route: Evidence<Team>, val clarity: Rating)

    private fun outcomesFrom(fixture: String): Outcomes = runBlocking {
        StubServer(Reply(200, resource(fixture))).use { stub ->
            val ai = Kleene(judge(stub))
            val q = Questions(ai)
            val answers = ai.ask(ticket, q.urgent, q.route, q.clarity)
            Outcomes(answers[q.urgent].evidence, answers[q.route].evidence, answers[q.clarity])
        }
    }

    @Test
    fun `TypeSafe and Kev captures give the same evidence shapes`() {
        val typesafe = outcomesFrom("response-typesafe-captured.json")
        val kev = outcomesFrom("response-kev-captured.json")

        assertSameShape(typesafe.urgent, kev.urgent)
        assertSameShape(typesafe.route, kev.route)
        assertEquals(listOf(Team.Billing, Team.Technical, Team.Other), kev.route.options)
        assertEquals(typesafe.clarity.levels, kev.clarity.levels)
        assertEquals(typesafe.clarity.probabilities.size, kev.clarity.probabilities.size)
        assertEquals(typesafe.clarity.confidence == null, kev.clarity.confidence == null)
    }

    @Test
    fun `a captured TypeSafe response is kept exactly as received`() {
        val live = outcomesFrom("response-typesafe-captured.json")

        assertEquals(listOf(0.6, 1.0 - 0.6), live.urgent.probabilities)
        assertEquals(listOf(0.0, 1.0, 0.0), live.route.probabilities)
        assertEquals(1.0, live.route.confidence)
        assertEquals(listOf(0.0, 0.21, 0.79), live.clarity.probabilities)
        assertEquals(1.79, live.clarity.expected)
        assertEquals("jev-1.13.0", live.clarity.model)
    }

    @Test
    fun `a captured Kev response is kept exactly as received`() {
        val live = outcomesFrom("response-kev-captured.json")

        assertEquals(listOf(0.42, 1.0 - 0.42), live.urgent.probabilities)
        assertEquals(listOf(0.03, 0.71, 0.26), live.route.probabilities)
        assertEquals(0.56, live.route.confidence)
        assertEquals(listOf(0.14, 0.19, 0.67), live.clarity.probabilities)
        assertEquals(1.53, live.clarity.expected)
        assertEquals(0.77, live.clarity.confidence)
        assertEquals("kev-4b", live.clarity.model)
    }

    private fun <T : Any> assertSameShape(expected: Evidence<T>, actual: Evidence<T>) {
        assertEquals(expected.kind, actual.kind)
        assertEquals(expected.options, actual.options)
        assertEquals(expected.probabilities.size, actual.probabilities.size)
        assertEquals(expected.confidence == null, actual.confidence == null)
    }

    @Test
    fun `answers carry the resolved model, usage, request id and judge id`() = runBlocking {
        val reply = Reply(200, resource("response-typesafe-captured.json"), mapOf("x-typesafe-request-id" to "req_0123456789abcdef0123456789abcdef"))
        StubServer(reply).use { stub ->
            val ai = Kleene(SystemOneJudge(baseUrl = stub.baseUrl, model = "jev-latest"))
            val q = Questions(ai)

            val answers = ai.ask(ticket, q.urgent, q.route, q.clarity)

            assertEquals("jev-1.13.0", answers.model)
            assertEquals(Usage(inputTokens = 355, outputTokens = 109), answers.usage)
            assertEquals("req_0123456789abcdef0123456789abcdef", answers.requestId)
            assertEquals("127.0.0.1/jev-latest", answers.judge)
            assertEquals("127.0.0.1/jev-latest", answers[q.urgent].evidence.judge)
        }
    }

    @Test
    fun `Authorization header is sent only when there is an API key`() = runBlocking {
        StubServer(Reply(200, answersFrom("response-typesafe-captured.json", "urgent.3173dac6eec83f01"))).use { stub ->
            Questions(Kleene(judge(stub, apiKey = "sk-test"))).urgent(ticket)
            Questions(Kleene(judge(stub, apiKey = null))).urgent(ticket)

            assertEquals("Bearer sk-test", stub.received[0].header("Authorization"))
            assertNull(stub.received[1].header("Authorization"))
        }
    }

    /** What asking `urgent` throws when the server sends [reply]. */
    private fun <T : Throwable> failureFrom(type: KClass<T>, reply: Reply): T =
        StubServer(reply).use { stub ->
            assertFailsWith(type) { runBlocking { Questions(Kleene(judge(stub))).urgent(ticket) } }
        }

    @Test
    fun `401 is an Authentication error with the server message`() {
        val body = """{"detail":{"error_type":"authentication_error","message":"Invalid API key"}}"""
        val error = failureFrom(KleeneException.Authentication::class, Reply(401, body))

        assertEquals(401, error.status)
        assertContains(error.message!!, "Invalid API key")
    }

    @Test
    fun `403 is an Authentication error`() {
        val body = """{"detail":{"error_type":"permission_error","message":"Missing API key"}}"""
        val error = failureFrom(KleeneException.Authentication::class, Reply(403, body))

        assertEquals(403, error.status)
    }

    @Test
    fun `422 is an InvalidRequest with the server validation message`() {
        val body = """{"detail":[{"type":"missing","loc":["body","questions","urgent.3173dac6eec83f01","criteria"],"msg":"Field required","input":{}}]}"""
        val error = failureFrom(KleeneException.InvalidRequest::class, Reply(422, body))

        assertContains(error.message!!, "questions.urgent.3173dac6eec83f01.criteria: Field required")
    }

    @Test
    fun `400 is an InvalidRequest with the server message`() {
        val error = failureFrom(KleeneException.InvalidRequest::class, Reply(400, """{"detail":"Unknown model: jev-9"}"""))

        assertContains(error.message!!, "Unknown model: jev-9")
    }

    @Test
    fun `an unparsable body is Malformed`() {
        failureFrom(KleeneException.Malformed::class, Reply(200, "<html>Bad gateway</html>"))
    }

    @Test
    fun `a body of the wrong wire shape is Malformed`() {
        val shapes = listOf(
            """[]""",
            """{"answers":{}}""",
            """{"model":"m","answers":[]}""",
            """{"model":"m","answers":{"urgent.3173dac6eec83f01":{"type":"noul","noul":"high"}}}""",
            """{"model":"m","answers":{"urgent.3173dac6eec83f01":{"type":"verdict","noul":0.9}}}""",
            """{"model":"m","answers":{"clarity.158a008ffc043dfa":{"type":"score","score":1.0,"probabilities":{"0":0.5,"2":0.5}}}}""",
            """{"model":"m","answers":{"urgent.3173dac6eec83f01":{"type":"noul","noul":0.9}},"usage":{"input_tokens":"many"}}""",
        )
        shapes.forEach { body -> failureFrom(KleeneException.Malformed::class, Reply(200, body)) }
    }

    @Test
    fun `numbers may be integers or use exponents`() = runBlocking {
        val body = """{"model":"m","answers":{"urgent.3173dac6eec83f01":{"type":"noul","noul":9.5E-1}},"usage":{"input_tokens":1,"output_tokens":0}}"""
        StubServer(Reply(200, body)).use { stub ->
            val verdict = Questions(Kleene(judge(stub))).urgent(ticket)

            assertEquals(listOf(0.95, 1 - 0.95), verdict.evidence.probabilities)
        }
    }

    @Test
    fun `a JSON state is sent as the element itself`() = runBlocking {
        StubServer(Reply(200, answersFrom("response-typesafe-captured.json", "urgent.3173dac6eec83f01"))).use { stub ->
            val state = buildJsonObject { put("ticket", ticket) }

            Questions(Kleene(judge(stub))).urgent(state)

            assertEquals(state, Json.parseToJsonElement(stub.received.single().body).jsonObject["state"])
        }
    }

    @Test
    fun `choices and scores outside System One limits are Unsupported before sending`() = runBlocking {
        StubServer(Reply(200, "{}")).use { stub ->
            val tooFewOrTooMany = listOf(
                WireQuestion("route.0", "route", Kind.CHOOSE, "Which team?", listOf("billing")),
                WireQuestion("route.1", "route", Kind.CHOOSE, "Which team?", List(256) { "team $it" }),
                WireQuestion("clarity.0", "clarity", Kind.SCORE, "How clear?", listOf("Clear")),
                WireQuestion("clarity.1", "clarity", Kind.SCORE, "How clear?", List(11) { "level $it" }),
            )
            tooFewOrTooMany.forEach { question ->
                assertFailsWith<KleeneException.Unsupported> { judge(stub).evaluate(Request(State.Text(ticket), listOf(question))) }
            }
            assertTrue(stub.received.isEmpty())
        }
    }

    @Test
    fun `retryable statuses map to Timeout, RateLimited and Overloaded`() {
        val expected = mapOf(
            408 to KleeneException.Timeout::class,
            429 to KleeneException.RateLimited::class,
            500 to KleeneException.Overloaded::class,
            529 to KleeneException.Overloaded::class,
        )
        expected.forEach { (status, type) ->
            StubServer(Reply(status, """{"detail":"busy"}""")).use { stub ->
                val judge = SystemOneJudge(stub.baseUrl, "jev-1.13.0", maxRetries = 0)
                val error = assertFailsWith(type) { runBlocking { Questions(Kleene(judge)).urgent(ticket) } }
                assertContains(error.message!!, "busy")
            }
        }
    }

    @Test
    fun `an attempt slower than the timeout is a Timeout`() {
        val slow = Reply(200, answersFrom("response-typesafe-captured.json", "urgent.3173dac6eec83f01"), delay = 500.milliseconds)
        StubServer(slow).use { stub ->
            val judge = SystemOneJudge(stub.baseUrl, "jev-1.13.0", timeout = 50.milliseconds, maxRetries = 0)

            assertFailsWith<KleeneException.Timeout> { runBlocking { Questions(Kleene(judge)).urgent(ticket) } }
        }
    }

    @Test
    fun `an unreachable server is Unavailable with the I-O error as cause`() {
        val port = ServerSocket(0).use { it.localPort }
        val judge = SystemOneJudge("http://127.0.0.1:$port", "jev-1.13.0", maxRetries = 0)

        val error = assertFailsWith<KleeneException.Unavailable> { runBlocking { Questions(Kleene(judge)).urgent(ticket) } }

        assertIs<IOException>(error.cause)
    }

    @Test
    fun `cancelling the caller aborts the HTTP exchange`(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val received = CountDownLatch(1)
            val closed = CountDownLatch(1)
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    input.read()
                    received.countDown()
                    try {
                        while (input.read() != -1) Unit
                    } catch (_: IOException) {
                    }
                    closed.countDown()
                }
            }
            val judge = SystemOneJudge("http://127.0.0.1:${server.localPort}", "jev-1.13.0", timeout = 30.seconds)
            val q = Questions(Kleene(judge))

            val call = launch(Dispatchers.IO) { q.urgent(ticket) }
            assertTrue(received.await(5, TimeUnit.SECONDS), "the request never arrived")
            call.cancelAndJoin()

            assertTrue(call.isCancelled)
            assertTrue(closed.await(5, TimeUnit.SECONDS), "the HTTP exchange was not aborted")
            server.soTimeout = 1_000
            assertFailsWith<SocketTimeoutException>("a cancelled ask was retried") { server.accept() }
        }
    }

    @Test
    fun `fromEnv reads the three variables`() {
        val env = mapOf("KLEENE_BASE_URL" to "http://127.0.0.1:8009", "KLEENE_MODEL" to "kev-latest", "KLEENE_API_KEY" to "sk-test")

        val judge = SystemOneJudge.fromEnv(env::get)

        assertEquals("http://127.0.0.1:8009", judge.baseUrl)
        assertEquals("kev-latest", judge.model)
        assertEquals("sk-test", judge.apiKey)
        assertEquals("127.0.0.1/kev-latest", judge.id)
    }

    @Test
    fun `fromEnv defaults to the TypeSafe cloud without a key`() {
        val judge = SystemOneJudge.fromEnv(mapOf("KLEENE_MODEL" to "jev-1.13.0")::get)

        assertEquals("https://api.typesafe.ai", judge.baseUrl)
        assertNull(judge.apiKey)
        assertEquals("api.typesafe.ai/jev-1.13.0", judge.id)
    }

    @Test
    fun `fromEnv without KLEENE_MODEL throws IllegalStateException`() {
        val error = assertFailsWith<IllegalStateException> { SystemOneJudge.fromEnv(mapOf("KLEENE_BASE_URL" to "http://127.0.0.1:8009")::get) }

        assertContains(error.message!!, "KLEENE_MODEL")
    }
}
