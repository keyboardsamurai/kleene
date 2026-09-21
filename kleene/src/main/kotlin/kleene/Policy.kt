package kleene

/**
 * The thresholds that turn [Evidence] into a [Verdict]. Owned by application code, never by the [Judge].
 *
 * @property acceptAt choose: the top option is accepted when its probability is at least this.
 * @property trueAt feels: p(true) at or above this is TRUE.
 * @property falseAt feels: p(true) at or below this is FALSE.
 * @property minConfidence choose only: minimum provider-reported confidence. Not portable across judges.
 */
data class Policy(
    val acceptAt: Double = 0.85,
    val trueAt: Double = acceptAt,
    val falseAt: Double = 1.0 - acceptAt,
    val minConfidence: Double? = null,
) {
    init {
        require(acceptAt > 0.5 && acceptAt <= 1.0) { "acceptAt must be in (0.5, 1], was $acceptAt" }
        require(trueAt > 0.5 && trueAt <= 1.0) { "trueAt must be in (0.5, 1], was $trueAt" }
        require(falseAt >= 0.0 && falseAt < 0.5) { "falseAt must be in [0, 0.5), was $falseAt" }
        require(minConfidence == null || minConfidence in 0.0..1.0) { "minConfidence must be in [0, 1], was $minConfidence" }
    }
}
