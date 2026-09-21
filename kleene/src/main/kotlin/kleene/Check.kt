package kleene

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * A named, reusable set of [requirements] (each judged as a feels question) and [rules] (ordinary code, no model
 * call) that an output must satisfy. Evaluate an output against it with [check].
 *
 * Its [name] comes from the delegated property (`val uploadError by contract { ... }`), else from the explicit `name`.
 */
class Contract internal constructor(
    private val givenName: String?,
    val requirements: List<String>,
    val rules: List<Rule>,
) {
    /** @throws IllegalStateException if the contract is neither bound to a property nor given a `name`. */
    val name: String
        get() = givenName ?: throw IllegalStateException(
            "contract has no name: bind it with `val x by contract { ... }` or pass `name = ...`",
        )

    /** Names the contract after the delegated property; the property name wins over an explicit `name`. */
    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadOnlyProperty<Any?, Contract> {
        val named = Contract(property.name, requirements, rules)
        return ReadOnlyProperty { _, _ -> named }
    }
}

/** One deterministic condition in a [Contract]: [test] runs on the output with no model call. */
data class Rule(val label: String, val test: (String) -> Boolean)

/** Defines a [Contract]: `+"text"` adds a requirement, `rule(label) { ... }` adds a rule. */
fun contract(name: String? = null, block: ContractBuilder.() -> Unit): Contract =
    ContractBuilder().apply(block).build(name)

/** Collects the requirements and rules of a [Contract], in declaration order. */
class ContractBuilder internal constructor() {
    private val requirements = mutableListOf<String>()
    private val rules = mutableListOf<Rule>()

    /** Adds this text as a requirement, judged verbatim as a feels question. */
    operator fun String.unaryPlus() {
        requirements += this
    }

    /** Adds a deterministic rule, evaluated with no model call. */
    fun rule(label: String, test: (String) -> Boolean) {
        rules += Rule(label, test)
    }

    internal fun build(name: String?) = Contract(name, requirements.toList(), rules.toList())
}

/** The outcome of one rule or requirement in a [Report], and of the whole Report. */
enum class Outcome { PASS, FAIL, UNKNOWN }

/** One rule or requirement of a [Report]. [evidence] is null for rules. */
data class Result(val label: String, val outcome: Outcome, val evidence: Evidence<Boolean>?)

/**
 * Evaluates an existing [output] against a [Contract]; never modifies it. Runs every rule in order, all of them,
 * then asks every requirement in one [ask] about `{"candidate": output, "source": source}`, decided by this
 * Kleene's policy. A contract without requirements makes no model call.
 *
 * @throws IllegalStateException if the contract has no name.
 * @throws KleeneException if the judge fails: a failure is never reported as UNKNOWN.
 */
suspend fun Kleene.check(output: String, against: Contract, source: State? = null): Report {
    val name = against.name
    val ruleResults = against.rules.map { Result(it.label, if (it.test(output)) Outcome.PASS else Outcome.FAIL, null) }
    if (against.requirements.isEmpty()) return Report(against, ruleResults, judge.id, "", policy)

    val questions = against.requirements.mapIndexed { i, text -> feels(text, name = "$name.${i + 1}") }
    val answers = ask(candidate(output, source), *questions.toTypedArray())
    val requirementResults = against.requirements.zip(questions) { text, question ->
        val verdict = answers[question]
        Result(text, verdict.truth.outcome, verdict.evidence)
    }
    return Report(against, ruleResults + requirementResults, answers.judge, answers.model, policy)
}

/**
 * What one [check] found, per rule and per requirement ([results]: rules first, then requirements, each in
 * declaration order). Never an aggregate score: FAIL and UNKNOWN results coexist.
 */
class Report(val contract: Contract, val results: List<Result>, val judge: String, val model: String, val policy: Policy) {

    /** FAIL if any result is FAIL; else UNKNOWN if any is UNKNOWN; else PASS. */
    val outcome: Outcome = when {
        results.any { it.outcome == Outcome.FAIL } -> Outcome.FAIL
        results.any { it.outcome == Outcome.UNKNOWN } -> Outcome.UNKNOWN
        else -> Outcome.PASS
    }

    /** Returns on PASS; else throws [AssertionError] (`java.lang`) with one line per result. */
    fun assertPassed() {
        if (outcome == Outcome.PASS) return
        val status = if (outcome == Outcome.FAIL) "failed" else "is inconclusive"
        throw AssertionError("contract \"${contract.name}\" $status (judge $judge, model $model)\n${table()}")
    }

    /** The report as compact JSON: contract, outcome, judge, model, policy, and each result. */
    fun toJson(): String = buildJsonObject {
        put("contract", contract.name)
        put("outcome", outcome.name)
        put("judge", judge)
        put("model", model)
        putJsonObject("policy") {
            put("acceptAt", policy.acceptAt)
            put("trueAt", policy.trueAt)
            put("falseAt", policy.falseAt)
            put("minConfidence", policy.minConfidence)
        }
        putJsonArray("results") {
            for (result in results) addJsonObject {
                put("label", result.label)
                put("kind", if (result.evidence == null) "rule" else "requirement")
                put("outcome", result.outcome.name)
                result.evidence?.let { put("pTrue", it.probabilityOf(true)) }
            }
        }
    }.toString()

    private fun table(): String {
        val width = results.maxOf { it.label.length }
        return results.joinToString("\n") { result ->
            val pTrue = result.evidence?.let { "p(true)=${it.probabilityOf(true).show()}" }.orEmpty()
            "  ${result.outcome.name.padEnd(7)}  ${result.label.padEnd(width)}  $pTrue".trimEnd()
        }
    }
}

private fun candidate(output: String, source: State?): State = State.Json(
    buildJsonObject {
        put("candidate", output)
        when (source) {
            is State.Text -> put("source", source.value)
            is State.Json -> put("source", source.value)
            null -> Unit
        }
    },
)

private val Truth.outcome: Outcome
    get() = when (this) {
        Truth.TRUE -> Outcome.PASS
        Truth.FALSE -> Outcome.FAIL
        Truth.UNKNOWN -> Outcome.UNKNOWN
    }
