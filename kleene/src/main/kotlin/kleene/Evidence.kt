package kleene

import java.util.Locale
import kotlin.math.ln

/** The three judgment kinds. */
enum class Kind { FEELS, CHOOSE, SCORE }

/**
 * What the [Judge] returned for one question, before any [Policy]. Immutable; reading it never calls a model.
 *
 * @property options feels: `[true, false]`; choose: the domain values in declaration order.
 * @property probabilities index-aligned with [options], exactly as received; never renormalized.
 * @property confidence the provider's own metric, not comparable across judges; always null for feels.
 * @property judge id of the [Judge] that produced this evidence.
 * @property model the model the judge resolved.
 */
data class Evidence<T : Any>(
    val kind: Kind,
    val options: List<T>,
    val probabilities: List<Double>,
    val confidence: Double?,
    val judge: String,
    val model: String,
) {
    init {
        require(options.size >= 2 && options.size == probabilities.size) {
            "need at least two options index-aligned with probabilities, got $options and $probabilities"
        }
        require(kind != Kind.FEELS || options == listOf(true, false)) { "feels options must be [true, false], got $options" }
    }

    /** The first option with the highest probability. */
    val top: T get() = options[topIndex]

    val topProbability: Double get() = probabilities[topIndex]

    /** [topProbability] minus the second highest probability. Portable across judges, like [Policy.acceptAt]. */
    val margin: Double get() = topProbability - probabilities.sortedDescending()[1]

    /** Shannon entropy of [probabilities] divided by ln K: 0 is certain, 1 is uniform. Portable across judges. */
    val normalizedEntropy: Double
        get() = probabilities.sumOf { p -> if (p > 0.0) -p * ln(p) else 0.0 } / ln(options.size.toDouble())

    private val topIndex: Int get() = probabilities.indexOf(probabilities.max())

    /** The probability received for [value]. Throws [IllegalArgumentException] if [value] is not one of the [options]. */
    fun probabilityOf(value: T): Double {
        val index = options.indexOf(value)
        require(index >= 0) { "$value is not one of $options" }
        return probabilities[index]
    }

    /**
     * Applies [policy]; the only place thresholds apply. Throws [KleeneException.Malformed] when a choose [policy]
     * sets [Policy.minConfidence] but the judge reported no [confidence] (fail closed).
     */
    fun decide(policy: Policy): Verdict<T> = when (kind) {
        Kind.FEELS -> decideFeels(policy)
        Kind.CHOOSE -> decideChoose(policy)
        Kind.SCORE -> error("score has no policy; read its Rating")
    }

    private fun decideFeels(policy: Policy): Verdict<T> {
        val p = probabilities[0]
        return when {
            p >= policy.trueAt -> Verdict.Accepted(options[0], this, policy)
            p <= policy.falseAt -> Verdict.Accepted(options[1], this, policy)
            else -> Verdict.Unknown(
                this, policy, "p(true)=${p.show()} between falseAt=${policy.falseAt.show()} and trueAt=${policy.trueAt.show()}",
            )
        }
    }

    private fun decideChoose(policy: Policy): Verdict<T> {
        val minConfidence = policy.minConfidence
        return when {
            topProbability < policy.acceptAt ->
                Verdict.Unknown(this, policy, "top p=${topProbability.show()} < acceptAt=${policy.acceptAt.show()}")
            minConfidence == null -> Verdict.Accepted(top, this, policy)
            confidence == null ->
                throw KleeneException.Malformed("minConfidence is set but judge '$judge' reported no confidence")
            confidence < minConfidence ->
                Verdict.Unknown(this, policy, "confidence=${confidence.show()} < minConfidence=${minConfidence.show()}")
            else -> Verdict.Accepted(top, this, policy)
        }
    }
}

private fun Double.show(): String = "%.4f".format(Locale.ROOT, this).trimEnd('0').trimEnd('.')
