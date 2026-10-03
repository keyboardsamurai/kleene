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
 * A definition without either is a [Question.Unnamed], which cannot be asked.
 */
sealed class Question<A>(
    internal val kleene: Kleene,
    internal val kind: Kind,
    internal val instructions: String,
    internal val labels: List<String>,
    val name: String,
) {
    init {
        invalidUnless(instructions.isNotBlank()) { "instructions must not be blank" }
    }

    private val fingerprint =
        sha256Hex(JsonArray(listOf(JsonPrimitive(kind.name), JsonPrimitive(instructions), JsonArray(labels.map(::JsonPrimitive))))).take(16)

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

    /**
     * A question definition that has no name yet, so it cannot be asked. Bind it to a property with
     * `val x by ai.feels(...)` (the property name becomes the [Question.name]), or pass `name = ...` instead.
     * It is validated at definition, like a named Question.
     */
    class Unnamed<A> internal constructor(private val definition: Question<A>) {
        /** Names the question after the delegated property. */
        operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadOnlyProperty<Any?, Question<A>> {
            val named = definition.named(property.name)
            return ReadOnlyProperty { _, _ -> named }
        }
    }
}

/** The name an unnamed definition ([Question.Unnamed], [Contract.Unnamed]) holds until a property names it; never sent to a judge. */
internal const val UNNAMED = "unnamed"

/** A yes/no question about a [State]; its [Verdict.truth] is a [Truth]. Bind it with `val x by`. */
fun Kleene.feels(instructions: String): Question.Unnamed<Verdict<Boolean>> = Question.Unnamed(feels(instructions, UNNAMED))

/** [feels] under the explicit [name]. */
fun Kleene.feels(instructions: String, name: String): Question<Verdict<Boolean>> = Feels(this, instructions, name)

/** A question that picks one domain value; each option maps a wire label to its value. Bind it with `val x by`. */
fun <T : Any> Kleene.choose(instructions: String, vararg options: Pair<String, T>): Question.Unnamed<Verdict<T>> =
    choose(instructions, options.asList())

/** [choose] under the explicit [name]. */
fun <T : Any> Kleene.choose(instructions: String, vararg options: Pair<String, T>, name: String): Question<Verdict<T>> =
    choose(instructions, options.asList(), name)

/** [choose] over a computed list of options; the list order is the label order, and so part of the wire id. */
fun <T : Any> Kleene.choose(instructions: String, options: List<Pair<String, T>>): Question.Unnamed<Verdict<T>> =
    Question.Unnamed(choose(instructions, options, UNNAMED))

/** [choose] over a computed list of options under the explicit [name]. */
fun <T : Any> Kleene.choose(instructions: String, options: List<Pair<String, T>>, name: String): Question<Verdict<T>> =
    Choose(this, instructions, options.toList(), name)

/** [choose] over [values] in iteration order; [label] gives the wire label of each value. */
fun <T : Any> Kleene.choose(instructions: String, values: Iterable<T>, label: (T) -> String): Question.Unnamed<Verdict<T>> =
    choose(instructions, values.map { label(it) to it })

/** [choose] over [values] in iteration order under the explicit [name]. */
fun <T : Any> Kleene.choose(instructions: String, values: Iterable<T>, name: String, label: (T) -> String): Question<Verdict<T>> =
    choose(instructions, values.map { label(it) to it }, name)

/** [choose] over every constant of [E] in declaration order; the wire label is [label], by default the constant name. */
inline fun <reified E : Enum<E>> Kleene.choose(
    instructions: String,
    noinline label: (E) -> String = { it.name },
): Question.Unnamed<Verdict<E>> = choose(instructions, enumValues<E>().asList(), label)

/** [choose] over every constant of [E] under the explicit [name]. */
inline fun <reified E : Enum<E>> Kleene.choose(
    instructions: String,
    name: String,
    noinline label: (E) -> String = { it.name },
): Question<Verdict<E>> = choose(instructions, enumValues<E>().asList(), name, label)

/** A question that places a [State] on an ordered rubric of [levels], lowest first. Bind it with `val x by`. */
fun Kleene.score(instructions: String, vararg levels: String): Question.Unnamed<Rating> = score(instructions, levels.asList())

/** [score] under the explicit [name]. */
fun Kleene.score(instructions: String, vararg levels: String, name: String): Question<Rating> =
    score(instructions, levels.asList(), name)

/** [score] over a computed list of [levels], lowest first. */
fun Kleene.score(instructions: String, levels: List<String>): Question.Unnamed<Rating> =
    Question.Unnamed(score(instructions, levels, UNNAMED))

/** [score] over a computed list of [levels] under the explicit [name]. */
fun Kleene.score(instructions: String, levels: List<String>, name: String): Question<Rating> =
    Score(this, instructions, levels.toList(), name)

private class Feels(kleene: Kleene, instructions: String, name: String) :
    Question<Verdict<Boolean>>(kleene, Kind.FEELS, instructions, emptyList(), name) {

    override fun named(name: String) = Feels(kleene, instructions, name)

    override fun answer(raw: Raw, model: String): Verdict<Boolean> {
        val p = (raw as Raw.Noul).p
        return Evidence.feels(p, kleene.judge.id, model).decide(kleene.policy)
    }
}

/** System One's limits, enforced for every judge (spec §3.4). */
internal val CHOOSE_OPTIONS = 2..255
internal val SCORE_LEVELS = 2..10

private class Choose<T : Any>(kleene: Kleene, instructions: String, private val options: List<Pair<String, T>>, name: String) :
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

private class Score(kleene: Kleene, instructions: String, levels: List<String>, name: String) :
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
