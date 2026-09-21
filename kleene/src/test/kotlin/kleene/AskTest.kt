package kleene

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskTest {

    private enum class Team { Billing, Technical, General }

    private val judge = ScriptedJudge {
        feels("urgent", 0.9)
        feels("unsure", 0.5)
        choose("route", "technical" to 0.1, "billing" to 0.88, "other" to 0.02, confidence = 0.7)
        score("clarity", 0.1, 0.2, 0.7)
    }
    private val ai = Kleene(judge)

    private val urgent by ai.feels("Needs a response today")
    private val unsure by ai.feels("Mentions a refund")
    private val route by ai.choose(
        "Which team should handle this?",
        "billing" to Team.Billing,
        "technical" to Team.Technical,
        "other" to Team.General,
    )
    private val clarity by ai.score("How clearly is the problem described?", "Unclear", "Partly clear", "Clear")

    @Test
    fun `asking three questions makes exactly one request`() = runTest {
        val answers = ai.ask("I was charged twice", urgent, route, clarity)

        val request = judge.requests.single()
        assertEquals(State.Text("I was charged twice"), request.state)
        assertEquals(listOf("urgent", "route", "clarity"), request.questions.map { it.name })
        assertIs<Verdict.Accepted<Boolean>>(answers[urgent])
        assertIs<Verdict.Accepted<Team>>(answers[route])
        assertEquals(1, judge.requests.size)
    }

    @Test
    fun `reading answers for a question that was not asked throws`() = runTest {
        val answers = ai.ask("I was charged twice", urgent)

        assertFailsWith<IllegalArgumentException> { answers[route] }
    }

    @Test
    fun `reapplying a policy changes the outcome with zero judge calls`() = runTest {
        val verdict = route("I was charged twice")
        assertIs<Verdict.Accepted<Team>>(verdict)

        val stricter = verdict.at(0.9)

        assertIs<Verdict.Unknown<Team>>(stricter)
        assertEquals(1, judge.requests.size)
    }

    @Test
    fun `feels at exactly trueAt is TRUE end to end`() = runTest {
        val ai = Kleene(ScriptedJudge { feels("urgent", 0.85) })
        val urgent by ai.feels("Needs a response today")

        assertEquals(Truth.TRUE, urgent("The server is down").truth)
    }

    @Test
    fun `feels at 0_5 is UNKNOWN end to end`() = runTest {
        assertEquals(Truth.UNKNOWN, unsure("Where is my invoice?").truth)
    }

    @Test
    fun `feels evidence carries p and 1 - p, no confidence, the judge id and the model`() = runTest {
        val evidence = urgent("The server is down").evidence

        assertEquals(Evidence(Kind.FEELS, listOf(true, false), listOf(0.9, 1 - 0.9), null, "scripted", "scripted"), evidence)
    }

    @Test
    fun `choose maps labels to domain values in option order`() = runTest {
        val verdict = route("I was charged twice")

        assertEquals(Verdict.Accepted(Team.Billing, verdict.evidence, ai.policy), verdict)
        assertEquals(listOf(Team.Billing, Team.Technical, Team.General), verdict.evidence.options)
        assertEquals(listOf(0.88, 0.1, 0.02), verdict.evidence.probabilities)
        assertEquals(0.7, verdict.evidence.confidence)
    }

    @Test
    fun `score answers become a rating`() = runTest {
        val rating = clarity("It crashes when I click save")

        assertEquals(
            Rating(listOf("Unclear", "Partly clear", "Clear"), listOf(0.1, 0.2, 0.7), 0.1 * 0 + 0.2 * 1 + 0.7 * 2, null, "scripted", "scripted"),
            rating,
        )
    }

    @Test
    fun `json state is sent as is`() = runTest {
        val ticket = buildJsonObject { put("subject", "Refund") }

        ai.ask(ticket, urgent)
        urgent(JsonPrimitive("text in json"))

        assertEquals(listOf(State.Json(ticket), State.Json(JsonPrimitive("text in json"))), judge.requests.map { it.state })
    }

    @Test
    fun `answers report the model, judge id, usage and request id`() = runTest {
        val custom = Kleene(Judge { request ->
            Response("m-1", mapOf(request.questions.single().id to Raw.Noul(0.9)), Usage(12, 1), "req-7")
        })
        val question = custom.feels("Needs a response today", name = "urgent")

        val answers = custom.ask("The server is down", question)

        assertEquals("m-1", answers.model)
        assertEquals("custom", answers.judge)
        assertEquals(Usage(12, 1), answers.usage)
        assertEquals("req-7", answers.requestId)
    }

    @Test
    fun `scripted answers carry no usage or request id`() = runTest {
        val answers = ai.ask("The server is down", urgent)

        assertEquals("scripted", answers.model)
        assertEquals("scripted", answers.judge)
        assertNull(answers.usage)
        assertNull(answers.requestId)
    }

    @Test
    fun `asking no questions is an invalid request`() = runTest {
        assertFailsWith<KleeneException.InvalidRequest> { ai.ask("The server is down") }
        assertTrue(judge.requests.isEmpty())
    }

    @Test
    fun `asking the same question twice is an invalid request`() = runTest {
        assertFailsWith<KleeneException.InvalidRequest> { ai.ask("The server is down", urgent, urgent) }
        assertTrue(judge.requests.isEmpty())
    }

    @Test
    fun `asking two questions with the same wire id is an invalid request`() = runTest {
        val twin = ai.feels("Needs a response today", name = "urgent")

        assertFailsWith<KleeneException.InvalidRequest> { ai.ask("The server is down", urgent, twin) }
        assertTrue(judge.requests.isEmpty())
    }

    @Test
    fun `asking a question created by a different Kleene is an invalid request`() = runTest {
        val other = Kleene(judge).feels("Needs a response today", name = "other")

        assertFailsWith<KleeneException.InvalidRequest> { ai.ask("The server is down", urgent, other) }
        assertTrue(judge.requests.isEmpty())
    }

    @Test
    fun `a scripted judge without an answer for the question throws IllegalStateException`() = runTest {
        val unscripted by ai.feels("Mentions a competitor")

        assertFailsWith<IllegalStateException> { unscripted("We are moving to another vendor") }
    }
}
