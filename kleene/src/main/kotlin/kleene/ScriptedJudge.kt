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
 *     choose("team", "billing", p = 0.88)            // the rest spread evenly over the other labels as asked
 *     score("clarity", 0.1, 0.3, 0.6)
 *     requirements(uploadError, "Says the upload failed" to 0.97)
 * }
 * ```
 */
class ScriptedJudge(block: Builder.() -> Unit) : Judge {

    /**
     * Collects the scripted answer for each question name. Each name is scripted once: a name scripted again, by any
     * of [feels], [choose], [score] or [requirements], throws [IllegalArgumentException].
     */
    class Builder internal constructor() {
        internal val answers = mutableMapOf<String, (WireQuestion) -> Raw>()

        private fun script(name: String, answer: (WireQuestion) -> Raw) {
            require(name !in answers) { "question \"$name\" scripted twice" }
            answers[name] = answer
        }

        /** Answers the feels question [name] with p(true) = [p]. */
        fun feels(name: String, p: Double) {
            script(name) { Raw.Noul(p) }
        }

        /**
         * Answers each requirement of [contract], found by its text, with p(true), as [check] asks it.
         *
         * @throws IllegalArgumentException if the contract has no requirement, or more than one, with a given text.
         */
        fun requirements(contract: Contract, vararg pTrue: Pair<String, Double>) {
            val texts = pTrue.map { it.first }
            require(texts.distinct().size == texts.size) { "requirement text scripted twice: ${texts.groupBy { it }.filterValues { it.size > 1 }.keys}" }
            for ((text, p) in pTrue) {
                val matches = contract.requirements.withIndex().filter { it.value == text }
                require(matches.size == 1) { "contract \"${contract.name}\" has ${matches.size} requirements with the text \"$text\"" }
                feels(requirementName(contract.name, matches.single().index), p)
            }
        }

        /** Answers the choose question [name] with a probability per option label. */
        fun choose(name: String, vararg probabilities: Pair<String, Double>, confidence: Double? = null) {
            val answer = Raw.Choice(probabilities.toMap(), confidence)
            script(name) { answer }
        }

        /**
         * Answers the choose question [name] with [p] on [label] and the rest spread evenly over the other labels
         * of the question as asked. A [label] the question does not ask fails the ask ([IllegalStateException]).
         */
        fun choose(name: String, label: String, p: Double = 1.0, confidence: Double? = null) {
            script(name) { question ->
                check(label in question.labels) { "question \"$name\" asks ${question.labels}, not the scripted label \"$label\"" }
                val rest = (1 - p) / (question.labels.size - 1)
                Raw.Choice(question.labels.associateWith { if (it == label) p else rest }, confidence)
            }
        }

        /** Answers the score question [name] with a probability per level; the expected level is Σ i·pᵢ. */
        fun score(name: String, vararg probabilities: Double, confidence: Double? = null) {
            val expected = probabilities.withIndex().sumOf { (level, p) -> level * p }
            val answer = Raw.Score(expected, probabilities.toList(), confidence)
            script(name) { answer }
        }
    }

    private val answers: Map<String, (WireQuestion) -> Raw> = Builder().apply(block).answers.toMap()
    private val recorded = CopyOnWriteArrayList<Request>()

    override val id: String get() = "scripted"

    /** Every request received so far, in order: one per ask. */
    val requests: List<Request> get() = recorded.toList()

    /** @throws IllegalStateException if a question's name has no scripted answer, or a scripted label is not asked. */
    override suspend fun evaluate(request: Request): Response {
        recorded += request
        val byId = request.questions.associate { question ->
            val answer = answers[question.name] ?: error("no scripted answer for question \"${question.name}\"")
            question.id to answer(question)
        }
        return Response(model = "scripted", answers = byId)
    }
}
