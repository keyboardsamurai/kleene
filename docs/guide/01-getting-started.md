# 1. Getting started

[Guide index](README.md) | Next: [2. Questions](02-questions.md)

On this page you install Kleene, connect it to a Judge, and ask one yes/no question. A Judge is a decision model,
such as TypeSafe's Jev or a local Kev. It returns probabilities read from model scores, not generated text.

## Install

Add the dependency from Maven Central to your project:

```xml
<dependency>
    <groupId>com.antonioagudo.libs</groupId>
    <artifactId>kleene</artifactId>
    <version>0.2.0</version>
</dependency>
```

Kleene needs JDK 17 and Kotlin 2.x. Its runtime dependencies are Kotlin's standard library,
`kotlinx-coroutines-core`, and `kotlinx-serialization-json`. All asking functions are `suspend` functions,
so call them from a coroutine.

## Configure a Judge

`SystemOneJudge.fromEnv()` reads three environment variables:

| Variable | Meaning |
|---|---|
| `KLEENE_BASE_URL` | Server URL. Default `https://api.typesafe.ai` (TypeSafe cloud) |
| `KLEENE_API_KEY` | Optional. Sent as `Authorization: Bearer <key>` |
| `KLEENE_MODEL` | Required, for example `jev-1.13.0` |

```sh
export KLEENE_API_KEY=...
export KLEENE_MODEL=jev-1.13.0
```

Without `KLEENE_MODEL`, `fromEnv()` throws `IllegalStateException`. To use a local Judge instead, see
[5. Judges](05-judges.md).

## Ask your first question

```kotlin
import kleene.*
import kleene.Truth.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val ai = Kleene(SystemOneJudge.fromEnv())
    val urgent by ai.feels("Needs a response today")

    val message = "The checkout page returns 500 for every customer since 9:00."
    when (urgent(message).truth) {
        TRUE -> println("flag it")
        FALSE -> println("queue it")
        UNKNOWN -> println("send it to a person")
    }
}
```

What happens:

1. `Kleene(judge)` is the runtime. It binds one Judge to a default `Policy` and holds no global state.
2. `val urgent by ai.feels(...)` defines a yes/no Question. Its name, `urgent`, comes from the property.
3. `urgent(message)` asks the Question about the message: exactly one request to the Judge.
4. `.truth` is a `Truth`: `TRUE`, `FALSE` or `UNKNOWN`.

## UNKNOWN is an answer, not an error

`UNKNOWN` means the Judge answered, but its probability was not clear enough for your Policy. With the default
Policy, p(true) must be at least 0.85 for `TRUE` and at most 0.15 for `FALSE`. Anything between is `UNKNOWN`.
Handle it like any other value, for example by sending the case to a person.

A failure is different. A timeout, an HTTP 5xx or a malformed response throws a `KleeneException`. It never
becomes `UNKNOWN`, and `UNKNOWN` never becomes `false`.

## Next

[2. Questions](02-questions.md) shows the three kinds of Question and what each returns.

## Reference

Spec [§1.1 Runtime](../spec.md#11-runtime), [§1.3 State](../spec.md#13-state),
[§3.1 Adapter](../spec.md#31-adapter). Decisions: [ADR-0001](../adr/0001-judge-is-a-distribution-source.md),
[ADR-0002](../adr/0002-errors-throw-unknown-is-a-value.md).
