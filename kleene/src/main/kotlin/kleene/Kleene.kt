package kleene

import kotlinx.serialization.json.JsonElement

/**
 * The runtime: binds one [Judge] to a default [Policy] and creates the [Question]s that are asked through it.
 * Holds no global state. To swap the judge, create a new `Kleene`.
 */
class Kleene(val judge: Judge, val policy: Policy = Policy())

/** The explicit input a judgment is made about. Never captured from surrounding scope. */
sealed interface State {
    data class Text(val value: String) : State
    data class Json(val value: JsonElement) : State
}

/**
 * Asks every question about one [state] in exactly one [Judge.evaluate] call. Asking is eager: the returned
 * [Answers] are validated and decided, and reading them never calls a model.
 *
 * @throws KleeneException.InvalidRequest for no questions, a question from another [Kleene], or two questions
 *   with the same wire id; checked before the judge is called.
 * @throws IllegalStateException if a question has no name.
 * @throws KleeneException.Malformed if any answer is malformed: the whole ask fails.
 */
suspend fun Kleene.ask(state: State, vararg questions: Question<*>): Answers {
    val request = Request(state, wireQuestions(questions))
    val response = judge.evaluate(request)
    validateResponse(request, response)
    val outcomes = questions.associateWith { it.answer(response.answers.getValue(it.wireId), response.model) }
    return Answers(outcomes, response.model, judge.id, response.usage, response.requestId)
}

suspend fun Kleene.ask(text: String, vararg questions: Question<*>): Answers = ask(State.Text(text), *questions)

suspend fun Kleene.ask(json: JsonElement, vararg questions: Question<*>): Answers = ask(State.Json(json), *questions)

private fun Kleene.wireQuestions(questions: Array<out Question<*>>): List<WireQuestion> {
    if (questions.isEmpty()) throw KleeneException.InvalidRequest("ask needs at least one question")
    val wire = questions.map { it.toWire() }
    questions.firstOrNull { it.kleene !== this }?.let {
        throw KleeneException.InvalidRequest("question \"${it.name}\" belongs to a different Kleene")
    }
    wire.groupBy { it.id }.values.firstOrNull { it.size > 1 }?.let {
        throw KleeneException.InvalidRequest("question \"${it.first().name}\" (${it.first().id}) is asked more than once")
    }
    return wire
}

/** What one [ask] returns: the outcome of each question asked, read by the question itself. */
class Answers internal constructor(
    private val outcomes: Map<Question<*>, Any?>,
    /** The model the judge resolved and reported. */
    val model: String,
    /** The [Judge.id] of the judge that answered. */
    val judge: String,
    /** Token usage, if the judge reported it. */
    val usage: Usage?,
    /** The provider's request id (`x-typesafe-request-id`), if present. */
    val requestId: String?,
) {
    /** @throws IllegalArgumentException if [question] was not in this ask. */
    operator fun <A> get(question: Question<A>): A {
        require(question in outcomes) { "${question.kind} question \"${question.instructions}\" was not in this ask" }
        @Suppress("UNCHECKED_CAST")
        return outcomes[question] as A
    }
}
