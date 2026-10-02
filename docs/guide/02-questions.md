# 2. Questions

Previous: [1. Getting started](01-getting-started.md) | [Guide index](README.md) | Next: [3. Asking several questions](03-asking-several-questions.md)

A Question is a reusable judgment definition. You define it once and ask it about any State (text or JSON).
There are three kinds:

| Kind | Asks | Returns |
|---|---|---|
| `feels` | Is this statement true of the State? | `Verdict<Boolean>`, read as a `Truth` |
| `choose` | Which one of these values fits? | `Verdict<T>` with your own type `T` |
| `score` | Where does the State sit on this ordered rubric? | `Rating` |

## Name a Question with `by`

```kotlin
val ai = Kleene(SystemOneJudge.fromEnv())

val urgent by ai.feels("Needs a response today")
val route by ai.choose("Which team should handle this?", Queue.entries - Queue.Review) { it.name.lowercase() }
val clarity by ai.score("How clearly is the problem described?", "Unclear", "Partly clear", "Clear")
```

The property name becomes the Question name (`urgent`, `route`, `clarity`). When you build Questions in a loop,
pass `name` instead:

```kotlin
val topics = listOf("billing", "technical").map { ai.feels("The message is about $it", name = "about_$it") }
```

A definition with neither a property nor a `name` is a `Question.Unnamed`. It cannot be asked: `val q = ai.feels("...")`
(`=` in place of `by`) followed by `q(message)` does not compile.

Kleene identifies a Question on the wire by its name plus a hash of its kind, instructions and labels. Renaming an
option label, or changing the instructions, makes a new Question.

Definitions are checked when you create them. These throw `KleeneException.InvalidRequest`: blank instructions,
`choose` with fewer than 2 or more than 255 options, duplicate labels or values, `score` with fewer than 2 or more
than 10 levels, duplicate levels.

## Ask a Question

Call the Question with a `String`, a `JsonElement` or a `State`. Each call is one request to the Judge:

```kotlin
val verdict = urgent("I was charged twice for my March invoice.")
val ticket = buildJsonObject { put("subject", "Charged twice"); put("body", "...") }
val fromJson = urgent(ticket)
```

To ask several Questions in one request, see [3. Asking several questions](03-asking-several-questions.md).

## feels: yes or no

A `feels` Question returns a `Verdict<Boolean>`. Read its `truth` and branch with `when`:

```kotlin
when (urgent(message).truth) {
    TRUE -> Priority.Now
    FALSE -> Priority.Normal
    UNKNOWN -> Priority.Review
}
```

`Truth` follows Kleene's three-valued logic (K3). `and`, `or` and `not` work on decided `Truth` values:

| Expression | Result |
|---|---|
| `TRUE and UNKNOWN` | `UNKNOWN` |
| `FALSE and UNKNOWN` | `FALSE` |
| `TRUE or UNKNOWN` | `TRUE` |
| `!UNKNOWN` | `UNKNOWN` |

```kotlin
val angry by ai.feels("The writer is angry")
val escalate: Truth = urgent(message).truth and angry(message).truth   // two asks; see page 3 for one
```

There is no probability arithmetic across Questions. To combine probabilities, ask one Question that says what
you mean.

## choose: one of your values

A `choose` Question maps each wire label to one of your values and returns a `Verdict<T>`. Several forms build
the options:

```kotlin
// "label" to value pairs (also a List of pairs)
val tone by ai.choose("What is the tone?", "angry" to Tone.Angry, "calm" to Tone.Calm)

// any Iterable, with a label function
val pit by ai.choose("Which pit should you sow?", 0..5) { "pit ${it + 1}" }

// every constant of an enum; labels are the constant names unless you pass a label function
val team by ai.choose<Queue>("Which team should handle this?")
```

The option order is part of the Question's identity: list order for pairs and `Iterable`s, declaration order for
enums. There is no `Map` overload.

Read the value with `orElse`, which gives a fallback when the Verdict is `Unknown`:

```kotlin
val queue: Queue = route(message).orElse { Queue.Review }
```

## Verdict: Accepted or Unknown

`feels` and `choose` both return a `Verdict<T>`, which has exactly two cases:

```kotlin
when (val v = route(message)) {
    is Verdict.Accepted -> send(v.value)
    is Verdict.Unknown -> log.info(v.reason)   // for example "top p=0.7 < acceptAt=0.85"
}
```

`Unknown.reason` names the numbers that missed the Policy. A feels reason looks like
`p(true)=0.62 between falseAt=0.15 and trueAt=0.85`. Every Verdict also keeps its `evidence` and `policy`; see
[4. Policy and Evidence](04-policy-and-evidence.md).

## score: a Rating over a rubric

A `score` Question places the State on ordered levels, lowest first. It returns a `Rating`, which has no Policy:
your comparison is the Policy.

```kotlin
val rating = clarity(message)
rating.levels[rating.mode]          // the most likely level, for example "Clear"
rating.expected                     // the expected level index reported by the Judge, for example 1.5
rating.probabilityAtOrAbove(1)      // p("Partly clear" or "Clear")

val askForDetails = rating.probabilityAtOrAbove(2) < 0.5
```

`Rating` also holds `probabilities` (aligned with `levels`), `confidence` (the provider's own metric, may be null),
`judge` and `model`. Levels can also be passed as a `List<String>`.

## Next

[3. Asking several questions](03-asking-several-questions.md) asks all three Questions in one request.

## Reference

Spec [§1.4 Questions](../spec.md#14-questions), [§1.6 Outcomes](../spec.md#16-outcomes).
