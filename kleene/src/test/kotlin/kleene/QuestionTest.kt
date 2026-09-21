package kleene

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class QuestionTest {

    private val judge = ScriptedJudge {
        feels("urgent", 0.9)
        feels("explicit", 0.9)
        choose("route", "billing" to 0.9, "technical" to 0.1)
    }
    private val ai = Kleene(judge)

    // Naming

    @Test
    fun `a delegated question takes its name from the property`() {
        val urgent by ai.feels("Needs a response today")

        assertEquals("urgent", urgent.name)
    }

    @Test
    fun `a question without a delegate takes the explicit name`() {
        val question = ai.feels("Needs a response today", name = "explicit")

        assertEquals("explicit", question.name)
    }

    @Test
    fun `the delegate name wins over the explicit name`() {
        val urgent by ai.feels("Needs a response today", name = "explicit")

        assertEquals("urgent", urgent.name)
    }

    @Test
    fun `a question with neither delegate nor name throws on first ask, not at definition`() = runTest {
        val unnamed = ai.feels("Needs a response today")

        assertFailsWith<IllegalStateException> { unnamed("The server is down") }
        assertTrue(judge.requests.isEmpty())
    }

    // Definition-time validation

    @Test
    fun `blank instructions are rejected at definition`() {
        assertFailsWith<KleeneException.InvalidRequest> { ai.feels("  ", name = "q") }
        assertFailsWith<KleeneException.InvalidRequest> { ai.choose("", "a" to 1, "b" to 2, name = "q") }
        assertFailsWith<KleeneException.InvalidRequest> { ai.score("\n", "low", "high", name = "q") }
    }

    @Test
    fun `choose needs at least two options`() {
        assertFailsWith<KleeneException.InvalidRequest> { ai.choose("Pick", "only" to 1, name = "q") }
    }

    @Test
    fun `choose accepts 255 options and rejects 256`() {
        val options = { n: Int -> (1..n).map { "option $it" to it }.toTypedArray() }

        ai.choose("Pick", *options(255), name = "q")
        assertFailsWith<KleeneException.InvalidRequest> { ai.choose("Pick", *options(256), name = "q") }
    }

    @Test
    fun `choose rejects duplicate option labels`() {
        assertFailsWith<KleeneException.InvalidRequest> { ai.choose("Pick", "a" to 1, "a" to 2, name = "q") }
    }

    @Test
    fun `choose rejects duplicate mapped values`() {
        assertFailsWith<KleeneException.InvalidRequest> { ai.choose("Pick", "a" to 1, "b" to 1, name = "q") }
    }

    @Test
    fun `score needs at least two levels`() {
        assertFailsWith<KleeneException.InvalidRequest> { ai.score("Rate", "only", name = "q") }
    }

    @Test
    fun `score accepts 10 levels and rejects 11`() {
        val levels = { n: Int -> (1..n).map { "level $it" }.toTypedArray() }

        ai.score("Rate", *levels(10), name = "q")
        assertFailsWith<KleeneException.InvalidRequest> { ai.score("Rate", *levels(11), name = "q") }
    }

    @Test
    fun `score rejects duplicate levels`() {
        assertFailsWith<KleeneException.InvalidRequest> { ai.score("Rate", "low", "low", name = "q") }
    }

    // Wire id

    @Test
    fun `wire id is the name plus the first 16 hex chars of sha256 over kind, instructions and labels`() = runTest {
        val urgent by ai.feels("Needs a response today")
        val route by ai.choose("Which team?", "billing" to 1, "technical" to 2)

        ai.ask("The server is down", urgent, route)

        val (feels, choose) = judge.requests.single().questions
        assertEquals("urgent.3173dac6eec83f01", feels.id)
        assertEquals("route.80b5d675d8c566d6", choose.id)
    }

    @Test
    fun `wire id is stable across equal definitions`() = runTest {
        ai.feels("Needs a response today", name = "urgent")("a")
        ai.feels("Needs a response today", name = "urgent")("b")

        val (first, second) = judge.requests
        assertEquals(first.questions.single().id, second.questions.single().id)
    }

    @Test
    fun `renaming an option label creates a new wire id`() = runTest {
        val before = ScriptedJudge { choose("route", "billing" to 0.9, "technical" to 0.1) }
        val after = ScriptedJudge { choose("route", "billing" to 0.9, "tech" to 0.1) }

        Kleene(before).choose("Which team?", "billing" to 1, "technical" to 2, name = "route")("a")
        Kleene(after).choose("Which team?", "billing" to 1, "tech" to 2, name = "route")("a")

        assertNotEquals(before.requests.single().questions.single().id, after.requests.single().questions.single().id)
    }
}
