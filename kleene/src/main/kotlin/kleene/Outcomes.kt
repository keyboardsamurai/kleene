package kleene

/** The three-valued result of a feels judgment, with Kleene's strong logic (K3). UNKNOWN is never an error. */
enum class Truth {
    TRUE, FALSE, UNKNOWN;

    /** FALSE if either is FALSE; TRUE if both are TRUE; else UNKNOWN. */
    infix fun and(o: Truth): Truth = when {
        this == FALSE || o == FALSE -> FALSE
        this == TRUE && o == TRUE -> TRUE
        else -> UNKNOWN
    }

    /** TRUE if either is TRUE; FALSE if both are FALSE; else UNKNOWN. */
    infix fun or(o: Truth): Truth = when {
        this == TRUE || o == TRUE -> TRUE
        this == FALSE && o == FALSE -> FALSE
        else -> UNKNOWN
    }

    /** Swaps TRUE and FALSE; UNKNOWN stays UNKNOWN. */
    operator fun not(): Truth = when (this) {
        TRUE -> FALSE
        FALSE -> TRUE
        UNKNOWN -> UNKNOWN
    }
}

/**
 * The outcome of a feels or choose after a [Policy] is applied: [Accepted] with a value, or [Unknown].
 * Carries the [Evidence] and the [Policy] that produced it.
 */
sealed class Verdict<T : Any> {
    abstract val evidence: Evidence<T>
    abstract val policy: Policy

    /** The evidence met the policy. */
    data class Accepted<T : Any>(val value: T, override val evidence: Evidence<T>, override val policy: Policy) : Verdict<T>()

    /** The judge answered, but the evidence did not meet the policy. [reason] names the numbers. Never an error. */
    data class Unknown<T : Any>(override val evidence: Evidence<T>, override val policy: Policy, val reason: String) : Verdict<T>()

    /** Reapplies: decides the same evidence under [policy]. Zero model calls. */
    fun at(policy: Policy): Verdict<T> = evidence.decide(policy)

    /** Reapplies with [acceptAt] as the choose threshold and the feels band `[1 - acceptAt, acceptAt]`. */
    fun at(acceptAt: Double): Verdict<T> = at(Policy(acceptAt, minConfidence = policy.minConfidence))
}

/** The accepted value, or [fallback] applied to the [Verdict.Unknown]. */
inline fun <T : Any> Verdict<T>.orElse(fallback: (Verdict.Unknown<T>) -> T): T = when (this) {
    is Verdict.Accepted -> value
    is Verdict.Unknown -> fallback(this)
}

/** Accepted(true) is TRUE, Accepted(false) is FALSE, Unknown is UNKNOWN. */
val Verdict<Boolean>.truth: Truth
    get() = when (this) {
        is Verdict.Accepted -> if (value) Truth.TRUE else Truth.FALSE
        is Verdict.Unknown -> Truth.UNKNOWN
    }

/**
 * The outcome of a score: a distribution over an ordered rubric of [levels]. Has no policy;
 * the caller's comparison is the policy.
 *
 * @property probabilities index-aligned with [levels], exactly as received.
 * @property expected the provider-reported expected level, in `0..levels.size - 1`.
 * @property confidence the provider's own metric, not comparable across judges.
 */
data class Rating(
    val levels: List<String>,
    val probabilities: List<Double>,
    val expected: Double,
    val confidence: Double?,
    val judge: String,
    val model: String,
) {
    /** Index of the first most likely level. */
    val mode: Int get() = probabilities.indexOf(probabilities.max())

    /** Probability of [level] or any level above it. */
    fun probabilityAtOrAbove(level: Int): Double {
        require(level in levels.indices) { "level must be in ${levels.indices}, was $level" }
        return probabilities.subList(level, probabilities.size).sum()
    }
}
