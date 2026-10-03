package kleene

import java.math.BigDecimal

/**
 * The thresholds that turn [Evidence] into a [Verdict]. Owned by application code, never by the [Judge].
 *
 * @property acceptAt choose: the top option is accepted when its probability is at least this.
 * @property trueAt feels: p(true) at or above this is TRUE.
 * @property falseAt feels: p(true) at or below this is FALSE.
 * @property minConfidence choose only: minimum provider-reported confidence. Not portable across judges.
 *
 * Not a data class: [trueAt] and [falseAt] default from [acceptAt] at construction, so a `copy(acceptAt = ...)`
 * would keep the old band. Build a new Policy instead.
 */
class Policy(
    val acceptAt: Double = 0.85,
    val trueAt: Double = acceptAt,
    val falseAt: Double = mirror(acceptAt),
    val minConfidence: Double? = null,
) {
    init {
        require(acceptAt > 0.5 && acceptAt <= 1.0) { "acceptAt must be in (0.5, 1], was $acceptAt" }
        require(trueAt > 0.5 && trueAt <= 1.0) { "trueAt must be in (0.5, 1], was $trueAt" }
        require(falseAt >= 0.0 && falseAt < 0.5) { "falseAt must be in [0, 0.5), was $falseAt" }
        require(minConfidence == null || minConfidence in 0.0..1.0) { "minConfidence must be in [0, 1], was $minConfidence" }
    }

    override fun equals(other: Any?): Boolean = other is Policy && thresholds == other.thresholds

    override fun hashCode(): Int = thresholds.hashCode()

    /** Boxed, so equals and hashCode agree as in a data class (-0.0 differs from 0.0). */
    private val thresholds: List<Double?> get() = listOf(acceptAt, trueAt, falseAt, minConfidence)

    override fun toString(): String = "Policy(acceptAt=$acceptAt, trueAt=$trueAt, falseAt=$falseAt, minConfidence=$minConfidence)"
}

/** `1 - acceptAt` in decimal, so 0.9 mirrors to exactly 0.1 and not to 0.09999999999999998. */
private fun mirror(acceptAt: Double): Double =
    if (acceptAt.isFinite()) (BigDecimal.ONE - BigDecimal.valueOf(acceptAt)).toDouble() else Double.NaN
