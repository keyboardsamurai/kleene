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
        choose("route", "billing", 0.9)
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
    fun `a definition without a name is unnamed until it is bound with by`() {
        val unnamed: Question.Unnamed<Verdict<Boolean>> = ai.feels("Needs a response today")
        val urgent by unnamed

        assertEquals("urgent", urgent.name)
        assertTrue(judge.requests.isEmpty())
    }

    @Test
    fun `an unnamed definition is validated at definition, not when it is bound`() {
        assertFailsWith<KleeneException.InvalidRequest> { ai.feels("  ") }
        assertFailsWith<KleeneException.InvalidRequest> { ai.choose("Pick", "only" to 1) }
        assertFailsWith<KleeneException.InvalidRequest> { ai.choose("Pick", listOf("a" to 1, "a" to 2)) }
        assertFailsWith<KleeneException.InvalidRequest> { ai.choose("Pick", 1..2) { "same" } }
        assertFailsWith<KleeneException.InvalidRequest> { ai.score("Rate", "only") }
        assertFailsWith<KleeneException.InvalidRequest> { ai.score("Rate", listOf("low", "low")) }
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
        val options = { n: Int -> (1..n).map { "option $it" to it } }

        ai.choose("Pick", options(255), name = "q")
        assertFailsWith<KleeneException.InvalidRequest> { ai.choose("Pick", options(256), name = "q") }
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
        val levels = { n: Int -> (1..n).map { "level $it" } }

        ai.score("Rate", levels(10), name = "q")
        assertFailsWith<KleeneException.InvalidRequest> { ai.score("Rate", levels(11), name = "q") }
    }

    @Test
    fun `score rejects duplicate levels`() {
        assertFailsWith<KleeneException.InvalidRequest> { ai.score("Rate", "low", "low", name = "q") }
    }

    // Typed and computed options

    private enum class Team { Billing, Technical }

    @Test
    fun `choose from a List is the same question as choose from varargs`() {
        val listed = ai.choose("Which team?", listOf("billing" to 1, "technical" to 2), name = "route")
        val spread = ai.choose("Which team?", "billing" to 1, "technical" to 2, name = "route")

        assertEquals(spread.wireId, listed.wireId)
    }

    @Test
    fun `choose from an Iterable labels each value in iteration order`() {
        val pit = ai.choose("Which pit?", 0..2, name = "pit") { "pit ${it + 1}" }

        assertEquals(listOf("pit 1", "pit 2", "pit 3"), pit.labels)
    }

    @Test
    fun `choose from an enum labels each constant by its name in declaration order`() {
        val team = ai.choose<Team>("Which team?", name = "team")

        assertEquals(listOf("Billing", "Technical"), team.labels)
    }

    @Test
    fun `a bare choose of an enum type resolves to the enum overload`() {
        val team: Question.Unnamed<Verdict<Team>> = ai.choose<Team>("Which team?")
        val bound by team

        assertEquals(listOf("Billing", "Technical"), bound.labels)
    }

    @Test
    fun `changing the caller's lists after definition does not change the question`() = runTest {
        val options = mutableListOf("billing" to 1, "technical" to 2)
        val levels = mutableListOf("Unclear", "Clear")
        val route by ai.choose("Which team?", options)
        val clarity = ai.score("How clear?", levels, name = "clarity")

        options[0] = "billing" to 9
        options[1] = "other" to 3
        levels[0] = "Vague"

        assertEquals(listOf("billing", "technical"), route.labels)
        assertEquals(listOf("Unclear", "Clear"), clarity.labels)
        assertEquals(listOf(1, 2), route("I was charged twice").evidence.options)
    }

    @Test
    fun `changing the caller's list after any list or Iterable definition does not change the question`() {
        val options = mutableListOf("billing" to 1, "technical" to 2)
        val values = mutableListOf("billing", "technical")
        val levels = mutableListOf("Unclear", "Clear")
        val namedOptions = ai.choose("Which team?", options, name = "route")
        val unnamedValues by ai.choose("Which team?", values) { it }
        val namedValues = ai.choose("Which team?", values, name = "route") { it }
        val unnamedLevels by ai.score("How clear?", levels)

        options[0] = "other" to 3
        values[0] = "other"
        levels[0] = "Vague"

        assertEquals(listOf("billing", "technical"), namedOptions.labels)
        assertEquals(listOf("billing", "technical"), unnamedValues.labels)
        assertEquals(listOf("billing", "technical"), namedValues.labels)
        assertEquals(listOf("Unclear", "Clear"), unnamedLevels.labels)
    }

    @Test
    fun `choose from an enum decodes the label back to the constant in one request`() = runTest {
        val route by ai.choose<Team>("Which team?") { it.name.lowercase() }

        val verdict = route("I was charged twice")

        assertEquals(Verdict.Accepted(Team.Billing, verdict.evidence, ai.policy), verdict)
        assertEquals(1, judge.requests.size)
    }

    @Test
    fun `choose from an Iterable with two equal labels is an invalid request`() {
        assertFailsWith<KleeneException.InvalidRequest> { ai.choose("Pick", 1..2, name = "q") { "same" } }
    }

    @Test
    fun `score from a List is the same question as score from varargs`() {
        val listed = ai.score("Rate", listOf("low", "high"), name = "q")
        val spread = ai.score("Rate", "low", "high", name = "q")

        assertEquals(spread.wireId, listed.wireId)
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
        val before = ScriptedJudge { choose("route", "billing", 0.9) }
        val after = ScriptedJudge { choose("route", "billing", 0.9) }

        Kleene(before).choose("Which team?", "billing" to 1, "technical" to 2, name = "route")("a")
        Kleene(after).choose("Which team?", "billing" to 1, "tech" to 2, name = "route")("a")

        assertNotEquals(before.requests.single().questions.single().id, after.requests.single().questions.single().id)
    }
}
