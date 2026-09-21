package kleene

import kotlinx.serialization.json.JsonElement

/**
 * The runtime: binds one [Judge] to a default [Policy] and creates the [Question]s that are asked through it.
 * Holds no global state. To swap the judge, create a new `Kleene`.
 */
class Kleene(val judge: Judge, val policy: Policy = Policy())

/** The explicit input a judgment is made about. Never captured from surrounding scope. */
sealed interface State {
    data class Text(val value: String) : State
    data class Json(val value: JsonElement) : State
}
