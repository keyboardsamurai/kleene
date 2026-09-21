# Kleene

Typed, three-valued semantic judgments for Kotlin/JVM. You ask a question about some text or JSON, a
Judge returns probability distributions from model scores, and your application's `Policy` turns them
into a `Verdict`: TRUE, FALSE or UNKNOWN for `feels`, an accepted option or UNKNOWN for `choose`, and a
`Rating` for `score`. UNKNOWN is a value you handle, never an error. The model never decides for you.

## Install

```xml
<dependency>
    <groupId>com.antonioagudo.libs</groupId>
    <artifactId>kleene</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

JDK 17, Kotlin 2.x. Runtime deps: `kotlinx-coroutines-core`, `kotlinx-serialization-json`.

## Usage

```kotlin
import kleene.*
import kleene.Truth.*

val ai = Kleene(SystemOneJudge.fromEnv())

val urgent by ai.feels("Needs a response today")
val route by ai.choose("Which team should handle this?",
    "billing" to Queue.Billing,
    "technical" to Queue.Technical,
    "other" to Queue.General,
)
val clarity by ai.score("How clearly is the problem described?", "Unclear", "Partly clear", "Clear")

val queue: Queue = route(message).orElse { Queue.Review }
when (urgent(message).truth) {
    TRUE -> Priority.Now; FALSE -> Priority.Normal; UNKNOWN -> Priority.Review
}
```

Each call is one request to the judge. To ask several questions in one request, use
`val answers = ai.ask(message, urgent, route, clarity)` and read `answers[urgent]`. A question takes its
name from the property. Renaming an option label creates a new question.

## Evidence and reapply

Every `Verdict` keeps the probabilities exactly as the judge returned them, so you can reapply a different policy
without another model call:

```kotlin
val verdict = route(message)
verdict.evidence.probabilityOf(Queue.Billing)   // also: top, margin, normalizedEntropy, judge, model
val strict = verdict.at(acceptAt = 0.95)         // same evidence, stricter policy, zero model calls
```

`acceptAt` and `margin` work the same for every judge. `confidence` is the provider's own metric and
you cannot compare it across judges.

## Cloud or local judge

`SystemOneJudge` sends `POST {baseUrl}/v1/systemone`. `SystemOneJudge.fromEnv()` reads three variables:

| Variable | |
|---|---|
| `KLEENE_BASE_URL` | default `https://api.typesafe.ai` (TypeSafe) |
| `KLEENE_API_KEY` | optional; sent as `Authorization: Bearer` |
| `KLEENE_MODEL` | required, e.g. `jev-1.13.0` |

For a local [Kev](https://github.com/jaredpalmer/kev) judge, run `scripts/kev.sh` inside a Kev checkout
and set `KLEENE_BASE_URL=http://127.0.0.1:8009`. There is no fallback from a local URL to the cloud.

## Check an output against a contract

```kotlin
val uploadError by contract {
    +"Says the upload failed"
    +"Makes clear that no files were saved"
    +"Tells the user to retry"
    rule("Fits the error banner") { it.length <= 180 }
}

val report = ai.check(bannerText, uploadError)
report.outcome        // FAIL if any result fails, else UNKNOWN if any is unknown, else PASS
report.assertPassed() // throws AssertionError with one row per result; says "inconclusive" on UNKNOWN
```

`check` runs every rule, then asks all requirements as `feels` questions in one request. It never
changes the output. `report.toJson()` gives the same results as JSON.

## Semantic tests with JUnit

Tag tests that call a real judge, and run them on demand:

```kotlin
@Tag("semantic")
class UploadErrorTest {
    private val ai = Kleene(SystemOneJudge.fromEnv())

    @Test
    fun `banner meets the contract`(): Unit = runBlocking {
        ai.check(renderUploadError(), uploadError).assertPassed()
    }
}
```

```sh
mvn test -Dgroups=semantic
```

To keep a tag out of the default `mvn test`, exclude it in surefire and drop the exclusion when
`-Dgroups` is given. This repo's parent `pom.xml` does that for `live` with a property-activated profile.

For unit tests without a model, use `ScriptedJudge`. It answers by question name and records every
request in `requests`:

```kotlin
val judge = ScriptedJudge { feels("urgent", 0.93) }
```

## Build

```sh
mvn test                                                                          # unit tests; live excluded
KLEENE_BASE_URL=http://127.0.0.1:8009 KLEENE_MODEL=kev-4b mvn test -Dgroups=live # live smoke tests
```

## Errors vs UNKNOWN

UNKNOWN means the judge answered but not clearly enough for your `Policy`: handle it with `when` or `orElse`.
Failures throw `KleeneException` (`Authentication`, `InvalidRequest`, `RateLimited`, `Overloaded`,
`Unavailable`, `Timeout`, `Malformed`, `Unsupported`) and never become UNKNOWN. Cancellation propagates.

## License

MIT. See `LICENSE`.
