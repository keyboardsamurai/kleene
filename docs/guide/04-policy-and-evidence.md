# 4. Policy and Evidence

Previous: [3. Asking several questions](03-asking-several-questions.md) | [Guide index](README.md) | Next: [5. Judges](05-judges.md)

The Judge supplies Evidence: the probability of each possible answer. Your code supplies the Policy: how sure is
sure enough. A Verdict is the Evidence decided by a Policy. Because every Verdict keeps its Evidence, you can
reapply a different Policy later with zero model calls.

## Set a Policy

Pass a `Policy` to `Kleene`. Every Question created by that `Kleene` uses it:

```kotlin
val ai = Kleene(SystemOneJudge.fromEnv(), Policy(acceptAt = 0.9))
```

| Field | Default | Applies to | Rule |
|---|---|---|---|
| `acceptAt` | `0.85` | choose | The top option is Accepted when its probability is at least `acceptAt` |
| `trueAt` | `acceptAt` | feels | p(true) at or above `trueAt` is `TRUE` |
| `falseAt` | `1 - acceptAt` | feels | p(true) at or below `falseAt` is `FALSE` |
| `minConfidence` | `null` | choose | Also require the provider's confidence to be at least this |

So `Policy(0.9)` means: choose accepts at 0.9, feels is `TRUE` at p(true) >= 0.9 and `FALSE` at p(true) <= 0.1.
Everything in between is `UNKNOWN`. A p(true) of exactly 0.5 is always `UNKNOWN`.

The constructor checks `0.5 < acceptAt <= 1`, `0.5 < trueAt <= 1`, `0 <= falseAt < 0.5` and `minConfidence` in
`[0, 1]`, and throws `IllegalArgumentException` otherwise.

`Policy` is not a data class and has no `copy`: `trueAt` and `falseAt` take their defaults from `acceptAt` when
you construct it. To change a value, construct a new Policy. Two Policies with the same values are equal.

## UNKNOWN is a value

With `Policy(0.9)`, a p(true) of 0.88 is `UNKNOWN`:

```kotlin
val v = urgent(message)
v.truth                  // UNKNOWN
(v as Verdict.Unknown).reason   // "p(true)=0.88 between falseAt=0.1 and trueAt=0.9"
```

Handle it with `when` or `orElse`. Never treat it as `false`, and never treat an error as `UNKNOWN`: errors throw
(see [5. Judges](05-judges.md#handle-errors)).

## Read the Evidence

`verdict.evidence` holds the probabilities exactly as the Judge returned them. Kleene never renormalizes them.

```kotlin
val e = route(message).evidence
e.distribution            // {Billing=0.82, Technical=0.12, Sales=0.06}
e.top                     // Billing
e.topProbability          // 0.82
e.margin                  // 0.7: top minus second
e.normalizedEntropy       // 0.53: 0 is certain, 1 is uniform
e.probabilityOf(Queue.Billing)   // 0.82
e.confidence              // provider metric, or null; always null for feels
e.judge                   // which Judge produced it
e.model                   // which model it resolved

urgent(message).evidence.pTrue   // p(true) of a feels answer, for example 0.88
```

`acceptAt`, `margin` and `normalizedEntropy` mean the same for every Judge. `confidence` is each provider's own
metric: you cannot compare it across Judges, which is why `Evidence.judge` records the source.

## Reapply a Policy with zero model calls

`Verdict.at` decides the same Evidence under another Policy:

```kotlin
val v = urgent(message)          // UNKNOWN at Policy(0.9), p(true) = 0.88
v.at(acceptAt = 0.85).truth      // TRUE: same Evidence, looser band [0.15, 0.85]
v.at(Policy(trueAt = 0.95, falseAt = 0.2))   // Unknown: p(true)=0.88 between falseAt=0.2 and trueAt=0.95
```

`at(acceptAt)` builds `Policy(acceptAt)` and keeps the current `minConfidence`. Neither form calls the Judge.

## Reapply to stored probabilities

To decide stored probabilities again (from a log or a database), build the Evidence with a factory and call
`decide`:

```kotlin
val stored = Evidence.feels(pTrue = 0.88, judge = "api.typesafe.ai/jev-1.13.0", model = "jev-1.13.0")
stored.decide(Policy(0.85)).truth   // TRUE

val choice = Evidence.choose(
    mapOf(Queue.Billing to 0.82, Queue.Technical to 0.12, Queue.Sales to 0.06),
    judge = "api.typesafe.ai/jev-1.13.0", model = "jev-1.13.0", confidence = null,
)
choice.decide(Policy(0.8))   // Accepted(value=Billing, ...)
```

`Evidence.feels` builds the same Evidence that Kleene builds from a live answer. `Evidence.choose` keeps the
iteration order of the map. Both throw `IllegalArgumentException` for a probability outside [0, 1].

## minConfidence fails closed

If a choose Policy sets `minConfidence` and the Judge reported no confidence, `decide` throws
`KleeneException.Malformed` instead of returning `Unknown`:

```kotlin
choice.decide(Policy(minConfidence = 0.5))
// KleeneException.Malformed: minConfidence is set but judge 'api.typesafe.ai/jev-1.13.0' reported no confidence
```

Prefer `acceptAt` and `margin`: they are portable across Judges.

## Next

[5. Judges](05-judges.md) shows how to connect to the cloud or a local Judge. To choose an `acceptAt` from data,
see [8. Picking a Policy with Sweep](08-picking-a-policy-with-sweep.md).

## Reference

Spec [§1.2 Policy](../spec.md#12-policy), [§1.6 Outcomes](../spec.md#16-outcomes),
[§1.7 Evidence](../spec.md#17-evidence). Decision: [ADR-0002](../adr/0002-errors-throw-unknown-is-a-value.md).
