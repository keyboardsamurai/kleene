package kleene

import kotlin.math.abs

/**
 * Checks a [response] against the [request] it answers, after any [Judge] and before decoding.
 * Never repairs: any violation throws [KleeneException.Malformed] and fails the whole ask.
 */
internal fun validateResponse(request: Request, response: Response) {
    val unasked = response.answers.keys - request.questions.map { it.id }.toSet()
    if (unasked.isNotEmpty()) throw KleeneException.Malformed("answers for ids that were not asked: $unasked")
    request.questions.forEach { question ->
        question.validate(response.answers[question.id] ?: question.malformed("has no answer"))
    }
}

private fun WireQuestion.validate(raw: Raw) = when {
    kind == Kind.FEELS && raw is Raw.Noul -> requireProbability("p", raw.p)
    kind == Kind.CHOOSE && raw is Raw.Choice -> validateChoice(raw)
    kind == Kind.SCORE && raw is Raw.Score -> validateScore(raw)
    else -> malformed("expects a $kind answer, got ${raw::class.simpleName}")
}

private fun WireQuestion.validateChoice(raw: Raw.Choice) {
    if (raw.probabilities.keys != labels.toSet()) {
        malformed("has option keys ${raw.probabilities.keys}, expected exactly $labels")
    }
    raw.probabilities.forEach { (label, p) -> requireProbability("p($label)", p) }
    raw.confidence?.let { requireProbability("confidence", it) }
    requireSumNearOne(raw.probabilities.values)
}

private fun WireQuestion.validateScore(raw: Raw.Score) {
    if (raw.probabilities.size != labels.size) {
        malformed("has ${raw.probabilities.size} level probabilities for ${labels.size} levels")
    }
    raw.probabilities.forEachIndexed { level, p -> requireProbability("p(level $level)", p) }
    raw.confidence?.let { requireProbability("confidence", it) }
    requireSumNearOne(raw.probabilities)
    val mean = raw.probabilities.withIndex().sumOf { (level, p) -> level * p }
    if (!raw.expected.isFinite() || abs(raw.expected - mean) > tolerance) {
        malformed("has expected=${raw.expected}, more than $tolerance from Σ i·pᵢ=$mean")
    }
}

/** Allowed distance from 1 of a distribution's sum: 0.006 per label. */
private val WireQuestion.tolerance: Double get() = 0.006 * labels.size

private fun WireQuestion.requireSumNearOne(probabilities: Collection<Double>) {
    val sum = probabilities.sum()
    if (abs(sum - 1.0) > tolerance) malformed("has probabilities summing to $sum, more than $tolerance from 1")
}

/** NaN and infinities are outside the range too. */
private fun WireQuestion.requireProbability(what: String, value: Double) {
    if (value !in 0.0..1.0) malformed("has $what=$value, not a finite number in [0,1]")
}

private fun WireQuestion.malformed(problem: String): Nothing =
    throw KleeneException.Malformed("question \"$name\" ($id) $problem")
