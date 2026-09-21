package kleene

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ValidationTest {

    private var reply: (Request) -> Map<String, Raw> = { emptyMap() }
    private val ai = Kleene(Judge { request -> Response("test-model", reply(request)) })

    private val urgent by ai.feels("Needs a response today")
    private val route by ai.choose("Which team should handle this?", "billing" to 1, "technical" to 2, "other" to 3)
    private val clarity by ai.score("How clearly is the problem described?", "Unclear", "Partly clear", "Clear")
    private val tenWay by ai.choose("Which of ten?", *(0 until 10).map { "o$it" to it }.toTypedArray())
    private val severity by ai.score("How severe is the problem?", "None", "Low", "Medium", "High")
    private val satisfaction by ai.score("How satisfied is the customer?", *(1..5).map { "s$it" }.toTypedArray())
    private val tenLevel by ai.score("Which of ten levels?", *(0 until 10).map { "l$it" }.toTypedArray())

    private val goodUrgent = "urgent" to Raw.Noul(0.9)
    private val goodRoute = "route" to choice(0.9, 0.08, 0.02)
    private val goodClarity = "clarity" to Raw.Score(1.6, listOf(0.1, 0.2, 0.7), null)

    private fun choice(billing: Double, technical: Double, other: Double, confidence: Double? = null) =
        Raw.Choice(mapOf("billing" to billing, "technical" to technical, "other" to other), confidence)

    /** The judge answers each asked question found in [raws] by name, keyed by its wire id. */
    private fun answerByName(vararg raws: Pair<String, Raw>): (Request) -> Map<String, Raw> = { request ->
        val byName = raws.toMap()
        request.questions.filter { it.name in byName }.associate { it.id to byName.getValue(it.name) }
    }

    private suspend fun assertMalformed(
        vararg asked: Question<*>,
        answers: (Request) -> Map<String, Raw>,
    ): KleeneException.Malformed {
        reply = answers
        return assertFailsWith { ai.ask("I was charged twice", *asked) }
    }

    @Test
    fun `a well-formed response passes validation`() = runTest {
        reply = answerByName(goodUrgent, goodRoute, goodClarity)

        val answers = ai.ask("I was charged twice", urgent, route, clarity)

        assertIs<Verdict.Accepted<Boolean>>(answers[urgent])
        assertIs<Verdict.Accepted<Int>>(answers[route])
        assertIs<Rating>(answers[clarity])
    }

    @Test
    fun `a missing answer is malformed and the message names the question and its id`() = runTest {
        val error = assertMalformed(urgent, route, answers = answerByName(goodUrgent))

        assertContains(error.message!!, "route")
        assertContains(error.message!!, route.wireId)
    }

    @Test
    fun `an answer for an id that was not asked is malformed`() = runTest {
        assertMalformed(urgent) { request ->
            answerByName(goodUrgent)(request) + ("ghost.0123456789abcdef" to Raw.Noul(0.5))
        }
    }

    @Test
    fun `an answer of the wrong kind is malformed`() = runTest {
        assertMalformed(route, answers = answerByName("route" to Raw.Noul(0.9)))
        assertMalformed(urgent, answers = answerByName("urgent" to choice(0.9, 0.08, 0.02)))
    }

    @Test
    fun `a non-finite number is malformed`() = runTest {
        assertMalformed(urgent, answers = answerByName("urgent" to Raw.Noul(Double.NaN)))
        assertMalformed(clarity, answers = answerByName("clarity" to Raw.Score(Double.NaN, listOf(0.1, 0.2, 0.7), null)))
        assertMalformed(clarity, answers = answerByName("clarity" to Raw.Score(Double.POSITIVE_INFINITY, listOf(0.1, 0.2, 0.7), null)))
    }

    @Test
    fun `a probability above one is malformed`() = runTest {
        assertMalformed(urgent, answers = answerByName("urgent" to Raw.Noul(1.2)))
    }

    @Test
    fun `a negative probability is malformed even when the sum is one`() = runTest {
        assertMalformed(route, answers = answerByName("route" to choice(-0.01, 0.51, 0.5)))
        assertMalformed(urgent, answers = answerByName("urgent" to Raw.Noul(-0.1)))
    }

    @Test
    fun `a sum off by more than the tolerance is malformed`() = runTest {
        assertMalformed(route, answers = answerByName("route" to choice(0.5, 0.3, 0.22)))
        assertMalformed(clarity, answers = answerByName("clarity" to Raw.Score(1.64, listOf(0.1, 0.2, 0.72), null)))
    }

    @Test
    fun `the sum tolerance scales with the number of labels`() = runTest {
        reply = answerByName("tenWay" to Raw.Choice((0 until 10).associate { "o$it" to 0.105 }, null))
        ai.ask("I was charged twice", tenWay)

        assertMalformed(tenWay, answers = answerByName("tenWay" to Raw.Choice((0 until 10).associate { "o$it" to 0.107 }, null)))
    }

    @Test
    fun `an unknown option key is malformed`() = runTest {
        val unknown = Raw.Choice(mapOf("billing" to 0.5, "technical" to 0.3, "sales" to 0.2), null)

        assertMalformed(route, answers = answerByName("route" to unknown))
    }

    @Test
    fun `a missing option key is malformed`() = runTest {
        val missing = Raw.Choice(mapOf("billing" to 0.6, "technical" to 0.4), null)

        assertMalformed(route, answers = answerByName("route" to missing))
    }

    @Test
    fun `a score with the wrong number of levels is malformed`() = runTest {
        assertMalformed(clarity, answers = answerByName("clarity" to Raw.Score(0.5, listOf(0.5, 0.5), null)))
    }

    @Test
    fun `a score expected level inconsistent with its distribution is malformed`() = runTest {
        assertMalformed(clarity, answers = answerByName("clarity" to Raw.Score(1.0, listOf(0.1, 0.2, 0.7), null)))
    }

    @Test
    fun `a live TypeSafe score rounded to two decimals passes at four levels`() = runTest {
        reply = answerByName("severity" to Raw.Score(1.43, listOf(0.39, 0.10, 0.20, 0.30), null))

        assertIs<Rating>(ai.ask("I was charged twice", severity)[severity])
    }

    @Test
    fun `a Kev score rounded to two decimals passes at five levels`() = runTest {
        reply = answerByName("satisfaction" to Raw.Score(3.96, listOf(0.0, 0.0, 0.0, 0.0, 0.98), null))

        assertIs<Rating>(ai.ask("I was charged twice", satisfaction)[satisfaction])
    }

    @Test
    fun `a one-based expected level is malformed even at ten levels`() = runTest {
        val uniform = List(10) { 0.1 }

        assertMalformed(tenLevel, answers = answerByName("tenLevel" to Raw.Score(5.5, uniform, null)))
    }

    @Test
    fun `the expected level tolerance grows with the rounded numbers it combines`() = runTest {
        val flat = List(5) { 0.2 }
        reply = answerByName("satisfaction" to Raw.Score(2.06, flat, null))
        ai.ask("I was charged twice", satisfaction)

        assertMalformed(satisfaction, answers = answerByName("satisfaction" to Raw.Score(2.07, flat, null)))
    }

    @Test
    fun `a confidence outside zero to one is malformed`() = runTest {
        assertMalformed(route, answers = answerByName("route" to choice(0.9, 0.08, 0.02, confidence = 1.5)))
        assertMalformed(clarity, answers = answerByName("clarity" to Raw.Score(1.6, listOf(0.1, 0.2, 0.7), -0.1)))
    }

    @Test
    fun `one malformed answer among several fails the whole ask`() = runTest {
        val shortScore = "clarity" to Raw.Score(0.5, listOf(0.5, 0.5), null)

        assertMalformed(urgent, route, clarity, answers = answerByName(goodUrgent, goodRoute, shortScore))
    }
}
