package kleene

import kotlinx.serialization.json.JsonElement
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
 * A definition without either is a [Contract.Unnamed], which cannot be checked.
 */
class Contract internal constructor(
    val name: String,
    val requirements: List<String>,
    val rules: List<Rule>,
) {
    /**
     * A contract definition that has no name yet, so it cannot be checked. Bind it to a property with
     * `val x by contract { ... }` (the property name becomes the [Contract.name]), or use `contract(name) { ... }`.
     */
    class Unnamed internal constructor(private val definition: Contract) {
        /** Names the contract after the delegated property. */
        operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadOnlyProperty<Any?, Contract> {
            val named = Contract(property.name, definition.requirements, definition.rules)
            return ReadOnlyProperty { _, _ -> named }
        }
    }
}

/** One deterministic condition in a [Contract]: [test] runs on the output with no model call. */
data class Rule(val label: String, val test: (String) -> Boolean)

/**
 * Defines a [Contract]: `+"text"` adds a requirement, `rule(label) { ... }` adds a rule. Bind it with `val x by`.
 *
 * @throws KleeneException.InvalidRequest if the contract has no requirement and no rule, or a requirement text is blank.
 */
fun contract(block: ContractBuilder.() -> Unit): Contract.Unnamed = Contract.Unnamed(contract(UNNAMED, block))

/**
 * [contract] under the explicit [name].
 *
 * @throws KleeneException.InvalidRequest if it has no requirement and no rule, or a requirement text is blank.
 */
fun contract(name: String, block: ContractBuilder.() -> Unit): Contract = ContractBuilder().apply(block).build(name)

/** Collects the requirements and rules of a [Contract], in declaration order. */
class ContractBuilder internal constructor() {
    private val requirements = mutableListOf<String>()
    private val rules = mutableListOf<Rule>()

    /**
     * Adds this text as a requirement, judged verbatim as a feels question.
     *
     * @throws KleeneException.InvalidRequest if the text is blank.
     */
    operator fun String.unaryPlus() {
        if (isBlank()) throw KleeneException.InvalidRequest("a requirement text must not be blank")
        requirements += this
    }

    /** Adds a deterministic rule, evaluated with no model call. */
    fun rule(label: String, test: (String) -> Boolean) {
        rules += Rule(label, test)
    }

    internal fun build(name: String): Contract {
        if (requirements.isEmpty() && rules.isEmpty()) {
            throw KleeneException.InvalidRequest("a contract needs a requirement or a rule: add one with +\"text\" or rule(label) { ... }")
        }
        return Contract(name, requirements.toList(), rules.toList())
    }
}

/** The outcome of one rule or requirement in a [Report], and of the whole Report. */
enum class Outcome { PASS, FAIL, UNKNOWN }

/** What a [Report] found for one rule ([OfRule]) or one requirement ([OfRequirement]). */
sealed interface Finding {
    /** The rule label, or the requirement text. */
    val label: String
    val outcome: Outcome

    /** A rule ran: PASS if it [passed], else FAIL. */
    data class OfRule(val rule: Rule, val passed: Boolean) : Finding {
        override val label: String get() = rule.label
        override val outcome: Outcome get() = if (passed) Outcome.PASS else Outcome.FAIL
    }

    /** A requirement was judged: the [verdict] under the Kleene's policy, with its evidence and, if UNKNOWN, its reason. */
    data class OfRequirement(val requirement: String, val verdict: Verdict<Boolean>) : Finding {
        override val label: String get() = requirement
        override val outcome: Outcome get() = verdict.truth.outcome
    }
}

/**
 * Evaluates an existing [output] against a [Contract]; never modifies it. Runs every rule in order, all of them,
 * then asks every requirement in one [ask] about `{"candidate": output, "source": source}`, decided by this
 * Kleene's policy. A contract without requirements makes no model call.
 *
 * @throws KleeneException if the judge fails: a failure is never reported as UNKNOWN.
 */
suspend fun Kleene.check(output: String, against: Contract, source: State? = null): Report {
    val rules = against.rules.map { Finding.OfRule(it, it.test(output)) }
    if (against.requirements.isEmpty()) return Report(against, rules, emptyList(), judge.id, "", policy)

    val questions = against.requirements.mapIndexed { i, text -> feels(text, name = requirementName(against.name, i)) }
    val answers = ask(candidate(output, source), questions)
    val requirements = against.requirements.zip(questions) { text, question -> Finding.OfRequirement(text, answers[question]) }
    return Report(against, rules, requirements, answers.judge, answers.model, policy)
}

/** [check] with a text [source], sent as a JSON string. */
suspend fun Kleene.check(output: String, against: Contract, source: String): Report = check(output, against, State.Text(source))

/** [check] with a JSON [source], sent as the element. */
suspend fun Kleene.check(output: String, against: Contract, source: JsonElement): Report = check(output, against, State.Json(source))

/** The name of the feels question for requirement [index] (from 0) of the contract [contract]: `"<contract>.<index + 1>"`. */
internal fun requirementName(contract: String, index: Int): String = "$contract.${index + 1}"

/**
 * What one [check] found: one [Finding] per rule ([rules], index-aligned with `contract.rules`) and per requirement
 * ([requirements], index-aligned with `contract.requirements`). Never an aggregate score: FAIL and UNKNOWN
 * findings coexist. For a contract without requirements no model was called, and [model] is `""`.
 */
class Report internal constructor(
    val contract: Contract,
    val rules: List<Finding.OfRule>,
    val requirements: List<Finding.OfRequirement>,
    val judge: String,
    val model: String,
    val policy: Policy,
) {
    /** Every finding: the rules, then the requirements. */
    val findings: List<Finding> get() = rules + requirements

    /** FAIL if any finding is FAIL; else UNKNOWN if any is UNKNOWN; else PASS. */
    val outcome: Outcome = when {
        findings.any { it.outcome == Outcome.FAIL } -> Outcome.FAIL
        findings.any { it.outcome == Outcome.UNKNOWN } -> Outcome.UNKNOWN
        else -> Outcome.PASS
    }

    /** Returns on PASS; else throws [AssertionError] (`java.lang`) with one line per finding. */
    fun assertPassed() {
        if (outcome == Outcome.PASS) return
        val status = if (outcome == Outcome.FAIL) "failed" else "is inconclusive"
        throw AssertionError("contract \"${contract.name}\" $status ${source()}\n${table()}")
    }

    /**
     * The heading line (contract, outcome, judge and model, or `(no model call)` for a contract without
     * requirements), then one row per finding. Never throws.
     */
    override fun toString(): String =
        "contract \"${contract.name}\": $outcome ${source()}\n${table()}".trimEnd()

    /** The report as compact JSON: contract, outcome, judge, model (null when no model was called), policy, and each finding (under `findings`). */
    fun toJson(): String = buildJsonObject {
        put("contract", contract.name)
        put("outcome", outcome.name)
        put("judge", judge)
        put("model", if (requirements.isEmpty()) null else model)
        putJsonObject("policy") {
            put("acceptAt", policy.acceptAt)
            put("trueAt", policy.trueAt)
            put("falseAt", policy.falseAt)
            put("minConfidence", policy.minConfidence)
        }
        putJsonArray("findings") {
            for (finding in findings) addJsonObject {
                put("label", finding.label)
                put("kind", if (finding is Finding.OfRule) "rule" else "requirement")
                put("outcome", finding.outcome.name)
                if (finding is Finding.OfRequirement) put("pTrue", finding.verdict.evidence.pTrue)
            }
        }
    }.toString()

    private fun source(): String = if (requirements.isEmpty()) "(no model call)" else "(judge $judge, model $model)"

    private fun table(): String {
        val width = findings.maxOfOrNull { it.label.length } ?: 0
        return findings.joinToString("\n") { finding ->
            val pTrue = if (finding is Finding.OfRequirement) "p(true)=${finding.verdict.evidence.pTrue.show()}" else ""
            "  ${finding.outcome.name.padEnd(7)}  ${finding.label.padEnd(width)}  $pTrue".trimEnd()
        }
    }
}

private fun candidate(output: String, source: State?): State = State.Json(
    buildJsonObject {
        put("candidate", output)
        source?.let { put("source", it.toJson()) }
    },
)

private val Truth.outcome: Outcome
    get() = when (this) {
        Truth.TRUE -> Outcome.PASS
        Truth.FALSE -> Outcome.FAIL
        Truth.UNKNOWN -> Outcome.UNKNOWN
    }
