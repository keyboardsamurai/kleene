package kleene

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [Judge] for tests: answers each question by its **name** from a fixed script and records every [Request].
 * It does not validate its own answers; core validates them as for any Judge.
 *
 * ```
 * val judge = ScriptedJudge {
 *     feels("urgent", 0.9)
 *     choose("route", "billing" to 0.8, "technical" to 0.2)
 *     score("clarity", 0.1, 0.3, 0.6)
 * }
 * ```
 */
class ScriptedJudge(block: Builder.() -> Unit) : Judge {

    /** Collects the scripted answer for each question name. */
    class Builder internal constructor() {
        internal val answers = mutableMapOf<String, Raw>()

        /** Answers the feels question [name] with p(true) = [p]. */
        fun feels(name: String, p: Double) {
            answers[name] = Raw.Noul(p)
        }

        /** Answers the choose question [name] with a probability per option label. */
        fun choose(name: String, vararg probabilities: Pair<String, Double>, confidence: Double? = null) {
            answers[name] = Raw.Choice(probabilities.toMap(), confidence)
        }

        /** Answers the score question [name] with a probability per level; the expected level is Σ i·pᵢ. */
        fun score(name: String, vararg probabilities: Double, confidence: Double? = null) {
            val expected = probabilities.withIndex().sumOf { (level, p) -> level * p }
            answers[name] = Raw.Score(expected, probabilities.toList(), confidence)
        }
    }

    private val answers: Map<String, Raw> = Builder().apply(block).answers.toMap()
    private val recorded = CopyOnWriteArrayList<Request>()

    override val id: String get() = "scripted"

    /** Every request received so far, in order: one per ask. */
    val requests: List<Request> get() = recorded.toList()

    /** @throws IllegalStateException if a question's name has no scripted answer. */
    override suspend fun evaluate(request: Request): Response {
        recorded += request
        val byId = request.questions.associate { question ->
            question.id to (answers[question.name] ?: error("no scripted answer for question \"${question.name}\""))
        }
        return Response(model = "scripted", answers = byId)
    }
}
