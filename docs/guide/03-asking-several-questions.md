# 3. Asking several questions

Previous: [2. Questions](02-questions.md) | [Guide index](README.md) | Next: [4. Policy and Evidence](04-policy-and-evidence.md)

Calling a Question asks it alone. To ask several Questions about the same State, use `ask`: it sends all of them
in exactly one request to the Judge and returns `Answers`.

## Ask in one request

```kotlin
val answers = ai.ask(message, urgent, route, clarity)

answers[urgent].truth                    // TRUE
answers[route].orElse { Queue.Review }   // Queue.Billing
answers[clarity].mode                    // 2
```

You read each outcome with the Question itself, so `answers[route]` has the type `Verdict<Queue>` and
`answers[clarity]` has the type `Rating`.

`ask` also takes a `Collection` of Questions, which suits Questions built in a loop:

```kotlin
val topics = listOf("billing", "technical").map { ai.feels("The message is about $it", name = "about_$it") }
val answers = ai.ask(message, topics)
topics.map { it.name to answers[it].truth }   // [(about_billing, TRUE), (about_technical, FALSE)]
```

Every form accepts a `String`, a `JsonElement` or a `State` as the State.

## One ask is one model call

- One `ask` makes exactly one call to the Judge, however many Questions it holds.
- Asking is eager: the Judge answers, Kleene validates every answer, then returns `Answers`.
- Reading `Answers`, a `Verdict`, its `Evidence` or a `Rating` never calls a model.

If any answer in the response is malformed, the whole `ask` throws `KleeneException.Malformed`. Kleene never
repairs a response.

## What Answers holds

| Member | Meaning |
|---|---|
| `answers[question]` | The `Verdict` or `Rating` for that Question |
| `model` | The model the Judge resolved and reported |
| `judge` | The id of the Judge that answered, for example `api.typesafe.ai/jev-1.13.0` |
| `usage` | Input and output tokens, if the Judge reported them, else null |
| `requestId` | The provider's request id (`x-typesafe-request-id`), if present |

## Errors from ask

| Mistake | Result |
|---|---|
| No Questions | `KleeneException.InvalidRequest`, before the Judge is called |
| The same Question twice | `InvalidRequest`: `question "urgent" (urgent.3173dac6eec83f01) is asked more than once` |
| A Question created by another `Kleene` | `InvalidRequest` |
| `answers[q]` for a Question that was not in this ask | `IllegalArgumentException` |

A Question belongs to the `Kleene` that created it. To use the same definitions with another Judge, define them
again on the new `Kleene` (see [7. Testing](07-testing.md) for a pattern).

## Next

[4. Policy and Evidence](04-policy-and-evidence.md) explains when an answer is UNKNOWN and how to change that
without another model call.

## Reference

Spec [§1.5 Asking](../spec.md#15-asking), [§1.9 Judge SPI](../spec.md#19-judge-spi) (response validation).
