# 6. Checking outputs

Previous: [5. Judges](05-judges.md) | [Guide index](README.md) | Next: [7. Testing](07-testing.md)

`check` holds an existing output (a template, a translation, a generated reply) against a `Contract` and returns a
`Report` with PASS, FAIL or UNKNOWN for each condition. A Contract has two kinds of condition:

- A **Requirement** is a plain-language condition. The Judge decides it as a `feels` Question.
- A **Rule** is ordinary Kotlin code. It runs with no model call.

`check` never modifies, rewrites or regenerates the output.

## Define a Contract

```kotlin
val uploadError by contract {
    +"Says the upload failed"
    +"Makes clear that no files were saved"
    +"Tells the user to retry"
    rule("Fits the error banner") { it.length <= 180 }
}
```

`+"text"` adds a Requirement; `rule(label) { output -> Boolean }` adds a Rule. A Contract takes its name like a
Question: from the property (`val uploadError by contract { }`) or explicitly (`contract("uploadError") { }`).

A Contract with no Requirement and no Rule throws `KleeneException.InvalidRequest` when you define it. This catches
a forgotten `+`, as in `contract { "Says the upload failed" }`. One forgotten `+` among other Requirements is not
caught. A blank Requirement text (`+" "`) also throws `InvalidRequest` when you define the Contract.

## Check an output

```kotlin
val report = ai.check(bannerText, uploadError, source = "disk full")
println(report)
```

```text
contract "uploadError": FAIL (judge scripted, model scripted)
  PASS     Fits the error banner
  PASS     Says the upload failed                p(true)=0.97
  FAIL     Makes clear that no files were saved  p(true)=0.05
  UNKNOWN  Tells the user to retry               p(true)=0.62
```

`source` is optional. It gives the Judge the material the output was made from, as a `String`, a `JsonElement` or a
`State`. The Judge sees the State `{"candidate": <output>, "source": <source>}`.

What `check` does:

1. It runs every Rule, in order, all of them, even after one fails.
2. It asks every Requirement as a `feels` Question in one ask: one model call for the whole Contract. A Contract
   without Requirements makes no model call, and its heading says `(no model call)` instead of the Judge and model:
   `contract "x": PASS (no model call)`. Its `toJson()` writes `"model": null`.
3. It decides each Requirement with the `Kleene`'s Policy: `TRUE` is PASS, `FALSE` is FAIL, `UNKNOWN` is UNKNOWN.

If the Judge fails, `check` throws the `KleeneException`. A failure is never reported as UNKNOWN.

## Read the Report

| Member | Meaning |
|---|---|
| `outcome` | FAIL if any Finding fails; else UNKNOWN if any is UNKNOWN; else PASS |
| `rules` | One `Finding.OfRule` per Rule, in Contract order |
| `requirements` | One `Finding.OfRequirement` per Requirement, in Contract order |
| `findings` | `rules` followed by `requirements` |
| `judge`, `model`, `policy` | Where the decisions came from |
| `toString()` | The heading and one row per Finding, as above |
| `toJson()` | The same as compact JSON, Findings under `findings` |
| `assertPassed()` | Returns on PASS, else throws `AssertionError` |

A Report is never one aggregate score: FAIL and UNKNOWN Findings coexist.

Each Finding has a `label` (the Rule label or the Requirement text) and an `outcome`. A requirement Finding keeps
its whole `verdict`, so you can read the Evidence or reapply a Policy with zero model calls:

```kotlin
report.requirements[2].verdict.evidence.pTrue                      // 0.62
(report.requirements[2].verdict as Verdict.Unknown).reason         // "p(true)=0.62 between falseAt=0.15 and trueAt=0.85"
report.requirements.map { it.verdict.at(Policy(0.95)).truth }      // [TRUE, FALSE, UNKNOWN]
```

There is no `Report.at(policy)` and no lookup by Requirement text, because two Requirements may have the same text.

## Assert in a test

`assertPassed()` throws `java.lang.AssertionError` with one row per Finding. On UNKNOWN the message says
"is inconclusive"; on FAIL it says "failed":

```text
java.lang.AssertionError: contract "uploadError" failed (judge scripted, model scripted)
  PASS     Fits the error banner
  PASS     Says the upload failed                p(true)=0.97
  FAIL     Makes clear that no files were saved  p(true)=0.05
  UNKNOWN  Tells the user to retry               p(true)=0.62
```

Kleene has no JUnit dependency. To run checks against a real Judge only on demand, tag the tests:

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

To keep a tag out of the default `mvn test`, exclude it in surefire (`excludedGroups`) and drop the exclusion when
`-Dgroups` is given. This repository's parent `pom.xml` does that for `live` with a property-activated profile.

For checks that run in every build without a model, see [7. Testing](07-testing.md).

## Next

[7. Testing](07-testing.md) shows how to test code that uses Kleene without calling a model.

## Reference

Spec [§2.1 Contract](../spec.md#21-contract), [§2.2 Check](../spec.md#22-check).
