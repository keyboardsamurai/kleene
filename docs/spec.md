# Kleene — Specification

Kotlin/JVM library for typed, three-valued semantic judgments. A Judge returns probability
distributions; application code owns the Policy; UNKNOWN is a value, never an error.

Vocabulary: `CONTEXT.md`. Decisions with trade-offs: `docs/adr/`. 

Ship order: **1 Core → 3 Local judges → 2 Check**. All three ship. Sections below follow ship order.

## 0. Fixed constraints

| | |
|---|---|
| Build | Maven, parent pom + child `kleene`. A second child `demo` holds demo programs and is not published (ADR-0005). |
| Coordinates | `com.antonioagudo.libs:kleene`, package `kleene` |
| License | MIT |
| Platform | JVM only, JDK 17, Kotlin 2.x |
| Deps | `kotlinx-coroutines-core`, `kotlinx-serialization-json`. HTTP via `java.net.http.HttpClient`. Nothing else at runtime. Applies to `kleene`; `demo` may add its own (ADR-0005). |
| Tests | JUnit 5 + `kotlin-test`. Live tests tagged `live`, excluded from default `mvn test`. |
| Not in v1 | Writer adapters, `write().satisfy()`, `inspect{}`, `partition`, debugger, toml/profiles/lockfile, `doctor`, compat report, `describe()`, Kleene-shipped Python bridges (Laya/SemIf), `kleene serve`, blocking facade, KMP. |

---

## 1. Core

### 1.1 Runtime

```kotlin
class Kleene(val judge: Judge, val policy: Policy = Policy())
```

No global state. Questions are bound to the `Kleene` that created them. Swapping judge = new `Kleene`.

### 1.2 Policy

```kotlin
class Policy(
    val acceptAt: Double = 0.85,            // choose: top option p ≥ acceptAt
    val trueAt: Double = acceptAt,          // feels: p(true) ≥ trueAt → TRUE
    val falseAt: Double = 1.0 - acceptAt,   // feels: p(true) ≤ falseAt → FALSE; mirror computed in decimal (0.9 → 0.1 exactly)
    val minConfidence: Double? = null,      // choose only; provider-specific, non-portable
)
```

Not a data class: `equals`, `hashCode` and `toString` are written by hand and there is no `copy`. `trueAt` and
`falseAt` take their defaults from `acceptAt` at construction, so `copy(acceptAt = 0.95)` would keep the old band
with no diagnostic. To change a threshold, construct a new Policy.

Invariants (checked in `init`, `IllegalArgumentException`):
`0.5 < acceptAt ≤ 1`, `0 ≤ falseAt < 0.5 < trueAt ≤ 1`, `minConfidence` in `[0,1]` if set.

`acceptAt > 0.5` guarantees an accepted top option is unique. There is no tie concept.
p exactly 0.5 for feels → UNKNOWN always.

### 1.3 State

```kotlin
sealed interface State {
    data class Text(val value: String) : State
    data class Json(val value: JsonElement) : State
}
```

Every asking entry point has `String` and `JsonElement` overloads that wrap into `State`.
Nothing is ever captured from surrounding scope.

### 1.4 Questions

```kotlin
sealed class Question<A> {
    val name: String                       // from the delegated property, else the explicit `name`
    internal val wireId: String            // "$name.${sha256(kind, instructions, labels).hex.take(16)}"
    suspend operator fun invoke(state: State): A      // = kleene.ask(state, this)[this]
    suspend operator fun invoke(text: String): A
    suspend operator fun invoke(json: JsonElement): A

    class Unnamed<A> {                     // a definition without a name; it cannot be asked
        operator fun provideDelegate(thisRef: Any?, prop: KProperty<*>): ReadOnlyProperty<Any?, Question<A>>
    }
}

// Each definition is a pair: without `name` → Question.Unnamed (bind it with `val x by`), with `name` → Question.
fun Kleene.feels(instructions: String): Question.Unnamed<Verdict<Boolean>>
fun Kleene.feels(instructions: String, name: String): Question<Verdict<Boolean>>
fun <T : Any> Kleene.choose(instructions: String, vararg options: Pair<String, T>): Question.Unnamed<Verdict<T>>
fun <T : Any> Kleene.choose(instructions: String, vararg options: Pair<String, T>, name: String): Question<Verdict<T>>
fun <T : Any> Kleene.choose(instructions: String, options: List<Pair<String, T>>): Question.Unnamed<Verdict<T>>
fun <T : Any> Kleene.choose(instructions: String, options: List<Pair<String, T>>, name: String): Question<Verdict<T>>
fun <T : Any> Kleene.choose(instructions: String, values: Iterable<T>, label: (T) -> String): Question.Unnamed<Verdict<T>>
fun <T : Any> Kleene.choose(instructions: String, values: Iterable<T>, name: String, label: (T) -> String): Question<Verdict<T>>
inline fun <reified E : Enum<E>> Kleene.choose(instructions: String, noinline label: (E) -> String = { it.name }): Question.Unnamed<Verdict<E>>
inline fun <reified E : Enum<E>> Kleene.choose(instructions: String, name: String, noinline label: (E) -> String = { it.name }): Question<Verdict<E>>
fun Kleene.score(instructions: String, vararg levels: String): Question.Unnamed<Rating>
fun Kleene.score(instructions: String, vararg levels: String, name: String): Question<Rating>
fun Kleene.score(instructions: String, levels: List<String>): Question.Unnamed<Rating>
fun Kleene.score(instructions: String, levels: List<String>, name: String): Question<Rating>
```

The `vararg` forms delegate to the `List` forms. An `Iterable` gives its options in iteration order, an enum
in declaration order. There is no `Map` overload: a Map drops an option with an equal label silently (the
other forms throw `InvalidRequest`), and it gives no order promise, but the label order is part of the wire id.

Usage:

```kotlin
val ai = Kleene(SystemOneJudge.fromEnv())

val urgent by ai.feels("Needs a response today")
val route by ai.choose("Which team should handle this?", Queue.entries - Queue.Review) { it.name.lowercase() }
val clarity by ai.score("How clearly is the problem described?", "Unclear", "Partly clear", "Clear")
val move by ai.choose("Which pit should you sow?", 0..5) { "pit ${it + 1}" }

val queue: Queue = route(message).orElse { Queue.Review }
when (urgent(message).truth) {
    TRUE -> Priority.Now; FALSE -> Priority.Normal; UNKNOWN -> Priority.Review
}
```

Definition-time validation (`InvalidRequest`): blank instructions; choose < 2 or > 255 options;
duplicate option labels; duplicate mapped values; score < 2 or > 10 levels; duplicate levels.
An unnamed definition is validated at definition too, not when it is bound.

Option label = wire option name = identity in a recording (§1.12). Renaming a label is a new question.
A `Question.Unnamed` cannot be asked: `val q = ai.feels("...")` (`=` in place of `by`) and then `q(message)` or
`ai.ask(message, q)` does not compile. `val x by ai.feels("...", name = "y")` does not compile either, because
the named form returns a `Question`, which has no delegate.

### 1.5 Asking

```kotlin
suspend fun Kleene.ask(state: State, questions: Collection<Question<*>>): Answers   // the one implementation
suspend fun Kleene.ask(state: State, vararg questions: Question<*>): Answers           // = ask(state, questions.asList())
// + String / JsonElement overloads of both

class Answers internal constructor(...) {
    operator fun <A> get(q: Question<A>): A       // IllegalArgumentException if q was not in this ask
    val model: String                              // resolved model reported by the judge
    val judge: String                              // judge id tag
    val usage: Usage?                              // input/output tokens if reported
    val requestId: String?                         // x-typesafe-request-id if present
}
```

**One `ask` = exactly one `Judge.evaluate` call.** Asking is eager. Reading an `Answers`, `Verdict`,
`Evidence`, or `Rating` never calls a model. Duplicate question in one ask → `InvalidRequest`.
All answers are validated before `Answers` is returned; a malformed answer fails the whole ask.

### 1.6 Outcomes

```kotlin
enum class Truth {
    TRUE, FALSE, UNKNOWN;
    infix fun and(o: Truth): Truth   // Kleene K3: FALSE if either FALSE; TRUE if both TRUE; else UNKNOWN
    infix fun or(o: Truth): Truth    // K3: TRUE if either TRUE; FALSE if both FALSE; else UNKNOWN
    operator fun not(): Truth        // TRUE↔FALSE, UNKNOWN stays
}

sealed class Verdict<T : Any> {
    abstract val evidence: Evidence<T>
    abstract val policy: Policy
    data class Accepted<T : Any>(val value: T, override val evidence: Evidence<T>, override val policy: Policy) : Verdict<T>()
    data class Unknown<T : Any>(override val evidence: Evidence<T>, override val policy: Policy, val reason: String) : Verdict<T>()

    fun at(policy: Policy): Verdict<T>               // reapply: zero model calls
    fun at(acceptAt: Double): Verdict<T>             // = at(Policy(acceptAt, minConfidence = policy.minConfidence)): band [1 - acceptAt, acceptAt]
}
inline fun <T : Any> Verdict<T>.orElse(fallback: (Verdict.Unknown<T>) -> T): T
val Verdict<Boolean>.truth: Truth                    // Accepted(true)→TRUE, Accepted(false)→FALSE, Unknown→UNKNOWN

data class Rating(
    val levels: List<String>,
    val probabilities: List<Double>,                 // index-aligned with levels
    val expected: Double,                            // provider-reported expected level, 0..levels.size-1
    val confidence: Double?,                         // provider metric, semantics per judge
    val judge: String,
    val model: String,
) {
    val mode: Int
    fun probabilityAtOrAbove(level: Int): Double
}
```

Rating has no policy. The caller's comparison is the policy. No `fold`; use `when (v.truth)`.

Never: `Boolean` from feels, probability arithmetic across questions, `.value` on `Verdict` base type.

### 1.7 Evidence

```kotlin
enum class Kind { FEELS, CHOOSE, SCORE }

@ConsistentCopyVisibility
data class Evidence<T : Any> internal constructor(   // core builds it; callers use the factories
    val kind: Kind,
    val options: List<T>,                            // feels: [true, false]
    val probabilities: List<Double>,                 // as received; never renormalized
    val confidence: Double?,                         // provider metric; null for feels always
    val judge: String,                               // "<host>/<model>" or explicit id
    val model: String,
) {
    fun probabilityOf(v: T): Double
    val distribution: Map<T, Double>                 // each option with its probability, in option order, as received
    val top: T
    val topProbability: Double
    val margin: Double                               // top − second (0 for feels on p=0.5)
    val normalizedEntropy: Double                    // H(p)/ln K, 0..1; provider-neutral
    fun decide(policy: Policy): Verdict<T>

    companion object {
        fun feels(pTrue: Double, judge: String, model: String): Evidence<Boolean>   // [true, false], [p, 1 − p]
        fun <T : Any> choose(distribution: Map<T, Double>, judge: String, model: String, confidence: Double? = null): Evidence<T>
    }
}
val Evidence<Boolean>.pTrue: Double                  // = probabilityOf(true)
```

The factories build Evidence again from stored probabilities (a log, a database), so that a caller can
reapply a Policy with zero model calls; no caller passes a `Kind`. `feels` builds the same Evidence as core.
`choose` keeps the iteration order of `distribution`. Both throw `IllegalArgumentException` for a probability
outside [0, 1].

`decide`:

| kind | rule |
|---|---|
| FEELS | `p ≥ trueAt` → Accepted(true); `p ≤ falseAt` → Accepted(false); else Unknown |
| CHOOSE | `policy.minConfidence != null && confidence == null` → **throw `Malformed`** (fail closed, checked first per ADR-0002); `topProbability < acceptAt` → Unknown; `confidence < minConfidence` → Unknown; else Accepted(top) |
| SCORE | n/a (Rating) |

`minConfidence` is documented as non-portable across judges; `acceptAt` and `margin` are the portable gates.

### 1.8 Errors

```kotlin
sealed class KleeneException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class Authentication(...)                       // 401, 403
    class InvalidRequest(...)                       // 422, client-side validation, 400
    class RateLimited(val retryAfter: Duration?)    // 429 after retries exhausted
    class Overloaded(message, status)               // HTTP 5xx (including 529) after retries exhausted
    class Unavailable(...)                          // connection refused, DNS, reset after retries exhausted (ADR-0004)
    class Timeout(...)                              // per-attempt timeout after retries exhausted
    class Malformed(...)                            // unparsable body, missing/extra answers, wrong type, non-finite, out of [0,1], bad sum, unknown option name, missing required metric
    class Unsupported(...)                          // adapter refuses (limits) before sending
}
```

Errors always throw. `Verdict.Unknown` is never produced by an error. Coroutine cancellation
propagates (`CancellationException` is never wrapped) and cancels the in-flight HTTP request.

### 1.9 Judge SPI

```kotlin
fun interface Judge { suspend fun evaluate(request: Request): Response }

data class Request(val state: State, val questions: List<WireQuestion>)
data class WireQuestion(val id: String, val name: String, val kind: Kind, val instructions: String, val labels: List<String>)
data class Response(val model: String, val answers: Map<String, Raw>, val usage: Usage? = null, val requestId: String? = null)
sealed interface Raw {
    data class Noul(val p: Double) : Raw
    data class Choice(val probabilities: Map<String, Double>, val confidence: Double?) : Raw
    data class Score(val expected: Double, val probabilities: List<Double>, val confidence: Double?) : Raw
}
data class Usage(val inputTokens: Long, val outputTokens: Long)
```

Response validation (in core, after any Judge, before `Answers`):
every asked id present, no extra ids, kind matches, all numbers finite in `[0,1]`,
choice keys == asked labels exactly, score list size == levels,
`|Σp − 1| ≤ 0.006 × K`, score `|expected − Σ i·pᵢ| ≤ 0.006 × (1 + K(K−1)/2)` (2-dp rounding, ADR-0003).
Violations → `Malformed`. Never repaired.

### 1.10 ScriptedJudge (tests)

```kotlin
class ScriptedJudge(block: ScriptedJudge.Builder.() -> Unit) : Judge {
    class Builder {
        fun feels(name: String, p: Double)
        fun choose(name: String, vararg probabilities: Pair<String, Double>, confidence: Double? = null)
        fun choose(name: String, label: String, p: Double = 1.0, confidence: Double? = null)   // rest spread evenly over the other labels as asked
        fun score(name: String, vararg probabilities: Double, confidence: Double? = null)   // expected computed
        fun requirements(contract: Contract, vararg pTrue: Pair<String, Double>)            // by requirement text, for check
    }
    val requests: List<Request>        // for asserting "one ask = one request"
}
```

Matches by question **name**. Unmatched name → `IllegalStateException`. Lives in the main artifact.
Every scripted answer is a distribution, and core validates it as for any Judge.
`requirements` finds each Requirement of the Contract by its text and scripts the name `check` gives it (§2.2);
a text that the Contract holds zero times or more than once → `IllegalArgumentException` when the script is built.
`choose(name, label, …)` resolves the labels at ask time; a `label` that the Question does not ask →
`IllegalStateException` that names the label.
A question name scripted twice, by any of `feels`, `choose`, `score` or `requirements` →
`IllegalArgumentException` (`question "<name>" scripted twice`) when the script is built.


### 1.11 Sweep

```kotlin
data class Labeled<T : Any>(val evidence: Evidence<T>, val gold: Set<T>)    // gold not empty; feels: {true} or {false}

class Sweep<T : Any>(labeled: List<Labeled<T>>) {   // Evidence from exactly one judge and model
    val judge: String
    val model: String
    data class Row(val policy: Policy, val n: Int, val accepted: Int, val wrong: Int) {
        val unknown: Int                             // n − accepted
        val coverage: Double                         // accepted / n
        val risk: Double                             // wrong / accepted; NaN when accepted == 0
    }
    fun at(policy: Policy): Row
    fun at(acceptAts: Iterable<Double>): List<Row>   // one Row per acceptAt, at Policy(acceptAt)
    fun table(acceptAts: Iterable<Double>): String   // heading "judge <id>, model <model>, n=<n>", then one line per Row
}
```

A Sweep applies each Policy to stored Evidence with `decide`: zero model calls. An Accepted value
outside its `gold` set is wrong. An empty list, or Evidence from more than one judge or model, →
`IllegalArgumentException`. A Sweep copies `labeled`: a later change to the caller's list does not change
it. A Sweep has no default grid and never picks or installs a Policy: the
caller reads the rows and builds its own `Kleene` (ADR-0007). `at(policy)` with `minConfidence` set on
choose Evidence without confidence → `Malformed`, as for `decide` (§1.7).

```kotlin
val row = sweep.at(listOf(0.95, 0.85, 0.75)).filter { it.accepted >= 30 && it.risk <= 0.02 }.maxByOrNull { it.coverage }
    ?: error("no acceptAt keeps 2 % risk")
val ai = Kleene(judge, row.policy)
```

### 1.12 Record and replay

```kotlin
fun Judge.recordingTo(path: Path): Judge   // same id; appends one JSONL line per answered Request
class ReplayJudge(path: Path) : Judge      // id = the recorded judge id; never calls a model
```

Each line holds `judge` (the `Judge.id`), `request` (`state` as `{"text": …}` or `{"json": …}`, and the
wire questions in order) and `response` (model, raw answers, usage, request id), as received and before
validation. A Judge that throws records nothing. A Response that core then rejects as `Malformed` is
recorded, and its replay throws `Malformed` again. `recordingTo` creates missing parent directories of `path`
when it wraps the Judge; if it can not, it throws `IOException` there. It writes each line after the Judge
answers, so a line that it can not write (the path is a directory or a read-only file, or its parent was
removed later; a State text or a label is not valid Unicode) throws `IOException` from `ask` after the model
call. A non-finite double in a Response is written as the JSON string `"NaN"`, `"Infinity"` or `"-Infinity"`.
Each line is valid JSON when the State is valid JSON; a `State.Json` that holds a non-finite number is written
as it is.

Parsing is strict: `judge`, `model`, `kind` and the request id are JSON strings; `request` is a JSON object;
`p`, `expected`, probabilities and confidence are JSON numbers (or one of the three non-finite strings); `usage`
is an object with JSON integer token counts. A number obeys the JSON grammar, so `0.5f`, `.5` and a bare `NaN`
are not numbers. A missing or null `usage`, request id or confidence is allowed. Any other type makes the line
not a recording; it is never repaired. An unknown field outside `request` is ignored.

`ReplayJudge` returns the recorded Response for an equal Request: same State kind and value, same wire
questions (id, name, kind, instructions, labels) in the same order. Replay is strict (ADR-0008):
a Request with no recording → `IllegalStateException`, never a live call. A missing or unreadable file,
an empty recording, a line that is not a recording (the message names the line), more than one judge id, or
one Request with two different models or answers → `IllegalArgumentException` when it is built. One Request
recorded twice with the same model and answers (usage and request id may differ) replays the first recording.
Core validates a replayed Response as for any Judge, and replayed Evidence equals recorded Evidence.

```kotlin
val ai = Kleene(SystemOneJudge.fromEnv().recordingTo(Path.of("run.jsonl")))   // once, with a model
val ai = Kleene(ReplayJudge(Path.of("run.jsonl")))                            // then in the default mvn test
```

---

## 3. Local judges (System One adapter)

### 3.1 Adapter

```kotlin
class SystemOneJudge(
    val baseUrl: String,                 // "https://api.typesafe.ai" or "http://127.0.0.1:8009"
    val model: String,                   // required; no alias default
    val apiKey: String? = null,          // null → no Authorization header
    val timeout: Duration = 10.seconds,  // per attempt
    val maxRetries: Int = 2,
    val httpClient: HttpClient = HttpClient.newHttpClient(),
    val id: String = "${URI(baseUrl).host}/$model",
) : Judge {
    companion object {
        // KLEENE_BASE_URL (default https://api.typesafe.ai), KLEENE_API_KEY (optional), KLEENE_MODEL (required);
        // the other parameters and their defaults are the constructor's
        fun fromEnv(timeout: Duration = 10.seconds, maxRetries: Int = 2, httpClient: HttpClient = HttpClient.newHttpClient()): SystemOneJudge
    }
}
```

Cloud ↔ local = three env vars or one constructor. No toml, no profiles, no lockfile.

### 3.2 Wire mapping

Request `POST {baseUrl}/v1/systemone`, `Content-Type: application/json`, `Authorization: Bearer` if key.

| Kind | request question | response answer |
|---|---|---|
| FEELS | `{"type":"noul","instructions":s}` | `{"type":"noul","noul":p}` → `Evidence([true,false],[p,1−p], confidence=null)` |
| CHOOSE | `{"type":"choice","instructions":s,"criteria":{label:null,…}}` | `{"type":"choice","choice":_,"probabilities":{label:p},"confidence":c}` → reordered to option order |
| SCORE | `{"type":"score","instructions":s,"criteria":[levels…]}` | `{"type":"score","score":e,"legend":_,"probabilities":{"0":p,…},"confidence":c}` |

Top-level request `{"model", "state", "questions"}`; `state` is the string or the JSON element as-is.
Top-level response `{"model","answers","usage"}`; unknown keys ignored (Kev sends `latency_ms`).
Header `x-typesafe-request-id` captured when present. Response `model` recorded as resolved model.

### 3.3 Transport policy

Retry on 408, 429, 5xx (incl. 529), `IOException`. Not on 400/401/403/422 or `Malformed`.
Backoff 0.5s × 2ⁿ, cap 5s, ±25% jitter; `Retry-After` / `retry-after-ms` honored when present.
After `maxRetries`: 429 → `RateLimited`, 529 → `Overloaded`, timeout → `Timeout`, other 5xx → `Overloaded`,
other `IOException` (connection refused, DNS, reset) → `Unavailable` (ADR-0004).
Never retry because an answer was UNKNOWN.

### 3.4 Limits

Client-side, Kleene enforces TypeSafe's documented shape for every server: choice 2–255, score 2–10.
Stricter servers answer 422 → `InvalidRequest` with the server message. No per-dialect table.

### 3.5 Support tiers

| Tier | Server | Notes |
|---|---|---|
| Tested | TypeSafe `api.typesafe.ai`, model `jev-1.13.0` | pin version; `jev-latest` moves |
| Tested | [Kev](https://github.com/jaredpalmer/kev) `127.0.0.1:8009` | Apache-2.0; Mac MPS; 2-dp rounding; `model` echoed verbatim; own confidence formulas: choice `(p_max−1/K)/(1−1/K)`, score `1 − E\|i−mode\|/(L−1)` |
| Wire-compatible, untested | [razorback16/openjev](https://github.com/razorback16/openjev) | model must be in `{openjev-latest, openjev-0.1, jev-latest, jev-preview}`; choice ≤128; response model always `openjev-0.1` |
| Wire-compatible, untested | [laya-mlx](https://pypi.org/project/laya-mlx/) via [phaser/laya-server](https://github.com/phaser/laya-server)@`ad2b426` `127.0.0.1:8010` | ADR-0006; demo verdicts mostly wrong on both checkpoints (`demo/README.md`), the same on upstream Laya 0.3.5, so the cause is the checkpoints (`demo/kalah.md`); float16 by default, within 0.009 of upstream float32; Apple Silicon; model must be `laya-mlx`, the checkpoint id or in `{jev-latest, jev-preview, jev-1.13.0}`; response model always `laya-mlx`; state + instructions + options share 1024 tokens (512 on the English checkpoint), excess dropped silently; multilingual checkpoint uncalibrated (temperature 1, every kind); English checkpoint: choice with 11+ options uncalibrated; ≤128 questions per request; one inference at a time; non-`ValueError` inference failure → 500 → `Overloaded` |
| Omitted | openjev-sglang | no license file; CUDA only |
| Omitted | SemIf | no HTTP server |

Confidence values are never comparable across tiers; `Evidence.judge` tags them.

### 3.6 Tests

- Fixture JSON per tested server (one real Kev response with 2-dp rounding is the key regression), parsed by unit tests, always run.
- `@Tag("live")` smoke test per tier, runs only when `KLEENE_BASE_URL` + `KLEENE_MODEL` set; excluded from default surefire.
- `scripts/kev.sh`: one-line Kev start, run from a Kev checkout after `uv sync --extra serve`:
  `KEV_DTYPE=bf16 uv run --extra serve python -m kev.serve --run jaredpalmer/kev-4b --port 8009`.
- `scripts/laya.sh`: one-line Laya start, no checkout needed, Apple Silicon and macOS 14+; `LAYA_MODEL` overrides the checkpoint:
  `uvx --python 3.11 --with 'laya-mlx==0.2.0' --from 'git+https://github.com/phaser/laya-server@ad2b426' laya-server --host 127.0.0.1 --port 8010 --model aac6fef/laya-multilingual-mlx`.

---

## 2. Check (contracts)

### 2.1 Contract

```kotlin
class Contract internal constructor(val name: String, val requirements: List<String>, val rules: List<Rule>) {
    class Unnamed {                                   // a definition without a name; it cannot be checked
        operator fun provideDelegate(...): ReadOnlyProperty<Any?, Contract>   // name from property
    }
}
data class Rule(val label: String, val test: (String) -> Boolean)

fun contract(block: ContractBuilder.() -> Unit): Contract.Unnamed         // bind it with `val x by`
fun contract(name: String, block: ContractBuilder.() -> Unit): Contract
class ContractBuilder {
    operator fun String.unaryPlus()                   // requirement, judged by feels
    fun rule(label: String, test: (String) -> Boolean) // deterministic, no model call
}
```

```kotlin
val uploadError by contract {
    +"Says the upload failed"
    +"Makes clear that no files were saved"
    +"Tells the user to retry"
    rule("Fits the error banner") { it.length <= 180 }
}
```

The builder block runs at definition. A contract with no requirement and no rule throws `InvalidRequest` there
(e.g. `contract { "Says the upload failed" }`, with the `+` forgotten): its Report could only be PASS.
One forgotten `+` among other requirements stays silent. A blank requirement text (`+" "`) throws
`InvalidRequest` at definition.

### 2.2 Check

```kotlin
suspend fun Kleene.check(output: String, against: Contract, source: State? = null): Report
suspend fun Kleene.check(output: String, against: Contract, source: String): Report        // State.Text(source)
suspend fun Kleene.check(output: String, against: Contract, source: JsonElement): Report   // State.Json(source)

enum class Outcome { PASS, FAIL, UNKNOWN }

sealed interface Finding {
    val label: String              // rule label or requirement text
    val outcome: Outcome
    data class OfRule(val rule: Rule, val passed: Boolean) : Finding                             // PASS / FAIL
    data class OfRequirement(val requirement: String, val verdict: Verdict<Boolean>) : Finding  // verdict.truth → PASS / FAIL / UNKNOWN
}

class Report internal constructor(
    val contract: Contract,
    val rules: List<Finding.OfRule>,                // index-aligned with contract.rules
    val requirements: List<Finding.OfRequirement>,  // index-aligned with contract.requirements
    val judge: String, val model: String, val policy: Policy,
) {
    val findings: List<Finding>    // rules + requirements
    val outcome: Outcome           // FAIL if any FAIL; else UNKNOWN if any UNKNOWN; else PASS
    fun assertPassed()             // PASS → return; else throw AssertionError (java.lang) with per-finding table; UNKNOWN message says "inconclusive"
    fun toJson(): String           // findings under "findings": label, kind ("rule" | "requirement"), outcome, pTrue (requirements only)
    override fun toString(): String // heading (contract, outcome, judge, model), then the per-finding table; never throws
}
```

A Report of a contract without requirements made no model call, so its `model` is `""` and its heading says
`(no model call)` instead of the judge and model: `contract "x": PASS (no model call)`; `assertPassed` throws
`contract "x" failed (no model call)`; `toJson` writes `"model": null` (`judge` stays).

A requirement Finding keeps the whole Verdict, so `Unknown.reason` and the Evidence stay readable, and
`report.requirements.map { it.verdict.at(Policy(0.95)) }` reapplies a Policy with zero model calls. There is no
`Report.at(policy)` and no lookup by text (two requirements with the same text are legal).

Semantics:

1. Run every rule, in order, all of them. Never short-circuit.
2. Run every requirement in **one** ask. State = `Json({"candidate": output, "source": source?})`.
   Each requirement is a `feels` Question named `"<contract>.<n>"` (n counts from 1), instructions = requirement text verbatim.
   Verdict → PASS (TRUE) / FAIL (FALSE) / UNKNOWN, using the `Kleene`'s policy.
3. `check` never modifies, rewrites, or regenerates `output`. There is no `satisfy`.
4. Report is per-requirement, never an aggregate score. Both FAIL and UNKNOWN findings coexist in one Report.
5. Provider errors propagate as exceptions, never as UNKNOWN.

No JUnit dependency: `assertPassed` uses `java.lang.AssertionError`. Tagging (`@Tag("semantic")`) and
surefire `groups` are a README recipe. No HTML report, no baselines, no before/after diff in this version.

---

## 4. Required tests (minimum)

Core
- feels: p = trueAt → TRUE; p = falseAt → FALSE; p = 0.5 → UNKNOWN; p just inside band → UNKNOWN.
- Policy invariants reject `acceptAt ≤ 0.5`, `trueAt ≤ 0.5`, `falseAt ≥ 0.5`. Policy has value equality and no `copy`.
- K3 truth tables: all 9 `and`, 9 `or`, 3 `not`.
- choose: top < acceptAt → Unknown; `minConfidence` set + confidence null → `Malformed`; confidence below → Unknown.
- `Verdict.at()` changes outcome with zero `Judge.evaluate` calls (ScriptedJudge.requests unchanged).
- `ask(s, q1, q2, q3)` → exactly one request; `ask(s, collection)` → exactly one request; `Answers[foreign]` throws.
- choose from a `List`, an `Iterable` with `label`, or an enum, and score from a `List`: same wire id as the `vararg` form; labels in list, iteration or declaration order; an enum label decodes to its constant.
- `Evidence.feels(p, …)` equals the Evidence core builds; `Evidence.choose(map, …)` keeps the map order; both reject p outside [0, 1]; `distribution` and `pTrue` read the probabilities as received.
- Response validation: missing id, extra id, wrong kind, NaN, p > 1, sum off by > tolerance, unknown option key, wrong score length → `Malformed`.
- Sum tolerance: 10-option distribution off by 0.05 passes; off by 0.07 fails.
- Score tolerance: 2-dp rounded score answer at K = 5 passes; expected off by 1.0 fails.
- Delegate naming; explicit `name`; a definition without a name is a `Question.Unnamed` (it cannot be asked: compile-level) and is validated at definition.
- Duplicate labels / values / levels, <2 options, >255 options, >10 levels → `InvalidRequest` at definition.
- Cancellation: cancelling the calling coroutine cancels the HTTP request (observable via a stub server).
- Sweep: rows against hand-counted choose and feels items (accepted, wrong, unknown, coverage, risk); risk NaN when nothing is accepted; one row per acceptAt in order; Evidence from two judges or two models, an empty list, or an empty gold set → `IllegalArgumentException`; an append to the caller's list after construction does not change `n`; `at(policy)` with `minConfidence` on choose Evidence without confidence → `Malformed`.
- Record and replay: ScriptedJudge → `recordingTo` → `ReplayJudge` gives equal Answers and Evidence (judge id, model, usage, request id); a Request with no recording (other State, State kind, questions or instructions) → `IllegalStateException` with no Judge call; core validates a replayed Response (`Malformed`); a missing file, a malformed line, an empty recording, two judge ids, or one Request with two different Responses → `IllegalArgumentException`; one Request recorded twice with different request ids replays the first recording (Evidence, usage, request id); two recordings of one Request with different models → `IllegalArgumentException`; a Judge that throws leaves the file absent or empty; wrong JSON types (`"judge": null`, `"p": "0.5"`, `usage` as a string or with a string token count, a numeric request id) → `IllegalArgumentException`; a recorded NaN is stored as the JSON string `"NaN"` and its replay throws `Malformed`; `recordingTo` creates missing parent directories.

Adapter
- Request JSON matches §3.2 byte-for-byte for each kind (golden files).
- The real TypeSafe and Kev captures parse to the same `Evidence` shapes: kinds, options, levels, list sizes, confidence present or not (values differ, the models differ). Confidence presence is a fact about these two captures, not a promise for other servers: `minConfidence` stays non-portable (§1.7).
- 429 with `Retry-After` retried then succeeds; 401 and 422 not retried; 3 × 429 → `RateLimited`.
- Connection refused, unknown host and a reset mid-body → `Unavailable` after retries (ADR-0004).
- Real Kev 10-level score answer with 2-dp drift passes validation.
- `fromEnv()` without `KLEENE_MODEL` → `IllegalStateException`; `fromEnv(timeout, maxRetries, httpClient)` uses them; without them it has the constructor defaults.

Check
- Rules all run even when the first fails; requirements sent in one request.
- `contract { }` without a name is a `Contract.Unnamed`; a contract with no requirement and no rule, or a blank requirement text → `InvalidRequest` at definition.
- Outcome aggregation: FAIL beats UNKNOWN beats PASS.
- `assertPassed` on UNKNOWN throws with "inconclusive" in the message and the per-finding table.
- `Report.rules` and `Report.requirements` are index-aligned with the Contract; a requirement Finding keeps its Verdict (`Unknown.reason`, reapply with zero model calls); `findings` = rules + requirements.
- `check` leaves `output` untouched (trivially true; documented by test).
- `check` with a `String` or `JsonElement` source sends the same State as with `State.Text` or `State.Json`.
- `Report.toString()` prints the heading and one row per finding; it does not throw for a Report without findings. A Report without requirements says `(no model call)` in `toString` and `assertPassed`, and its `toJson` has the judge and `"model": null`; `toJson` puts the findings under `findings`.
- `ScriptedJudge.requirements`: answers found by text in any order; unknown or duplicate text fails at script build. A question name scripted twice across `feels`/`choose`/`score`/`requirements` fails at script build.
- `ScriptedJudge.choose(name, label, p)`: the rest spread evenly over the asked labels; an unknown label fails and names the label.

## 5. Non-goals (for readers who will ask)

- No chat-completion judge. A judge returns distributions from model scores, not parsed JSON with a claimed confidence (ADR-0001).
- No probabilistic `and`/`or`. K3 operates on decided `Truth` only.
- No sandbox claim. `Kleene` is plain Kotlin; effects are the caller's.
- No implicit cloud fallback from a local `baseUrl`.
- No automatic threshold tuning, no policy publishing. A `Sweep` (§1.11) prints a table; the caller picks the Policy (ADR-0007).
