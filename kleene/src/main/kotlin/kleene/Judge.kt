package kleene

/**
 * A decision model that answers typed questions about a [State] with probability distributions read from
 * model scores, never with generated text (ADR-0001).
 *
 * Core validates every [Response] after any `Judge`; a Judge never needs to (and must not) repair its output.
 */
fun interface Judge {
    /** Tag recorded in [Evidence.judge], [Rating.judge] and [Answers.judge]. */
    val id: String get() = "custom"

    suspend fun evaluate(request: Request): Response
}

/** One ask: a single [State] and every question asked about it. */
data class Request(val state: State, val questions: List<WireQuestion>)

/**
 * A [Question] as sent to a [Judge]. [labels] are the option labels (choose), the levels (score), or empty (feels).
 * [id] is `"<name>.<16 hex chars of sha256(kind, instructions, labels)>"`.
 */
data class WireQuestion(val id: String, val name: String, val kind: Kind, val instructions: String, val labels: List<String>)

/** What a [Judge] returned, keyed by [WireQuestion.id]. [model] is the model the judge resolved. */
data class Response(
    val model: String,
    val answers: Map<String, Raw>,
    val usage: Usage? = null,
    val requestId: String? = null,
)

/** One unvalidated answer as the judge reported it. */
sealed interface Raw {
    /** feels: probability that the statement is true. */
    data class Noul(val p: Double) : Raw

    /** choose: probability per option label, plus the provider's own confidence metric if any. */
    data class Choice(val probabilities: Map<String, Double>, val confidence: Double?) : Raw

    /** score: expected level index, probability per level (index-aligned), plus the provider's confidence if any. */
    data class Score(val expected: Double, val probabilities: List<Double>, val confidence: Double?) : Raw
}

/** Token usage reported by the judge. */
data class Usage(val inputTokens: Long, val outputTokens: Long)
