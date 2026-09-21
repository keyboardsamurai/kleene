package kleene

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * A reusable, typed judgment definition (a feels, choose, or score) bound to the [Kleene] that created it.
 * Ask it of any [State] by calling it, or together with other questions through [ask].
 *
 * Its [name] comes from the delegated property (`val urgent by ai.feels(...)`), else from the explicit `name`.
 */
sealed class Question<A>(
    internal val kleene: Kleene,
    internal val kind: Kind,
    internal val instructions: String,
    internal val labels: List<String>,
    private val givenName: String?,
) {
    init {
        invalidUnless(instructions.isNotBlank()) { "instructions must not be blank" }
    }

    private val fingerprint =
        sha256Hex(JsonArray(listOf(JsonPrimitive(kind.name), JsonPrimitive(instructions), JsonArray(labels.map(::JsonPrimitive))))).take(16)

    /** @throws IllegalStateException if the question is neither bound to a property nor given a `name`. */
    val name: String
        get() = givenName ?: throw IllegalStateException(
            "$kind question \"$instructions\" has no name: bind it with `val x by ...` or pass `name = ...`",
        )

    /** `"<name>.<first 16 hex chars of sha256(kind, instructions, labels)>"`: renaming a label is a new question. */
    internal val wireId: String get() = "$name.$fingerprint"

    internal fun toWire() = WireQuestion(wireId, name, kind, instructions, labels)

    /** The same question under [name]. */
    internal abstract fun named(name: String): Question<A>

    /** Decodes a validated [raw] answer from [model] into this question's outcome. */
    internal abstract fun answer(raw: Raw, model: String): A

    /** Asks this question alone: `kleene.ask(state, this)[this]`. */
    suspend operator fun invoke(state: State): A = kleene.ask(state, this)[this]

    suspend operator fun invoke(text: String): A = invoke(State.Text(text))

    suspend operator fun invoke(json: JsonElement): A = invoke(State.Json(json))

    /** Names the question after the delegated property; the property name wins over an explicit `name`. */
    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadOnlyProperty<Any?, Question<A>> {
        val named = named(property.name)
        return ReadOnlyProperty { _, _ -> named }
    }
}

/** A yes/no question about a [State]; its [Verdict.truth] is a [Truth]. */
fun Kleene.feels(instructions: String, name: String? = null): Question<Verdict<Boolean>> = Feels(this, instructions, name)

/** A question that picks one domain value; each option maps a wire label to its value. */
fun <T : Any> Kleene.choose(instructions: String, vararg options: Pair<String, T>, name: String? = null): Question<Verdict<T>> =
    Choose(this, instructions, options.toList(), name)

/** A question that places a [State] on an ordered rubric of [levels], lowest first. */
fun Kleene.score(instructions: String, vararg levels: String, name: String? = null): Question<Rating> =
    Score(this, instructions, levels.toList(), name)

private class Feels(kleene: Kleene, instructions: String, name: String?) :
    Question<Verdict<Boolean>>(kleene, Kind.FEELS, instructions, emptyList(), name) {

    override fun named(name: String) = Feels(kleene, instructions, name)

    override fun answer(raw: Raw, model: String): Verdict<Boolean> {
        val p = (raw as Raw.Noul).p
        return Evidence(kind, listOf(true, false), listOf(p, 1 - p), null, kleene.judge.id, model).decide(kleene.policy)
    }
}

/** System One's limits, enforced for every judge (spec §3.4). */
internal val CHOOSE_OPTIONS = 2..255
internal val SCORE_LEVELS = 2..10

private class Choose<T : Any>(kleene: Kleene, instructions: String, private val options: List<Pair<String, T>>, name: String?) :
    Question<Verdict<T>>(kleene, Kind.CHOOSE, instructions, options.map { it.first }, name) {

    init {
        invalidUnless(options.size in CHOOSE_OPTIONS) { "choose needs $CHOOSE_OPTIONS options, got ${options.size}" }
        invalidUnless(labels.distinct() == labels) { "duplicate option labels in $labels" }
        invalidUnless(options.distinctBy { it.second }.size == options.size) { "duplicate option values in ${options.map { it.second }}" }
    }

    override fun named(name: String) = Choose(kleene, instructions, options, name)

    override fun answer(raw: Raw, model: String): Verdict<T> {
        val choice = raw as Raw.Choice
        val probabilities = labels.map { choice.probabilities.getValue(it) }
        return Evidence(kind, options.map { it.second }, probabilities, choice.confidence, kleene.judge.id, model).decide(kleene.policy)
    }
}

private class Score(kleene: Kleene, instructions: String, levels: List<String>, name: String?) :
    Question<Rating>(kleene, Kind.SCORE, instructions, levels, name) {

    init {
        invalidUnless(levels.size in SCORE_LEVELS) { "score needs $SCORE_LEVELS levels, got ${levels.size}" }
        invalidUnless(levels.distinct() == levels) { "duplicate levels in $levels" }
    }

    override fun named(name: String) = Score(kleene, instructions, labels, name)

    override fun answer(raw: Raw, model: String): Rating {
        val score = raw as Raw.Score
        return Rating(labels, score.probabilities, score.expected, score.confidence, kleene.judge.id, model)
    }
}

private fun invalidUnless(valid: Boolean, message: () -> String) {
    if (!valid) throw KleeneException.InvalidRequest(message())
}

private fun sha256Hex(json: JsonElement): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.toString().toByteArray()))
