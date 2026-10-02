# 8. Picking a Policy with Sweep

Previous: [7. Testing](07-testing.md) | [Guide index](README.md)

A higher `acceptAt` gives fewer wrong answers and more UNKNOWN. A `Sweep` shows that trade-off on your own data.
You give it stored Evidence with Gold labels (the answers that count as right, fixed before any Judge runs). It
applies each Policy with zero model calls and prints a table. It never picks or installs a Policy: you read the
rows and build your own `Kleene` ([ADR-0007](../adr/0007-sweep-is-a-table-the-caller-picks-the-policy.md)).

## Build a Sweep

Each item is a `Labeled(evidence, gold)`. A feels item has the gold set `{true}` or `{false}`; a choose item has one
or more right options.

```kotlin
// logged: stored p(true) values with their Gold labels, all from one Judge and model
val logged = listOf(0.97 to true, 0.95 to true, 0.91 to false, 0.88 to true, 0.80 to true,
                    0.60 to false, 0.30 to false, 0.12 to false, 0.08 to true, 0.03 to false)

val sweep = Sweep(logged.map { (pTrue, gold) ->
    Labeled(Evidence.feels(pTrue, judge = "api.typesafe.ai/jev-1.13.0", model = "jev-1.13.0"), gold = setOf(gold))
})
println(sweep.table(listOf(0.95, 0.9, 0.85, 0.75)))
```

```text
judge api.typesafe.ai/jev-1.13.0, model jev-1.13.0, n=10
acceptAt  accepted  unknown  wrong  coverage    risk
    0.95         3        7      0       0.3       0
     0.9         5        5      2       0.5     0.4
    0.85         7        3      2       0.7  0.2857
    0.75         8        2      2       0.8    0.25
```

You can also take the Evidence from live Verdicts (`verdict.evidence`) or from a replay.

## Read a row

| Column | Meaning |
|---|---|
| `accepted` | Items decided (Accepted) at this Policy |
| `unknown` | Items left UNKNOWN: `n - accepted` |
| `wrong` | Accepted items whose value is not in the gold set |
| `coverage` | `accepted / n` |
| `risk` | `wrong / accepted`; `NaN` when nothing is accepted |

`sweep.at(policy)` gives one `Row` for any `Policy`, for example one with its own `trueAt` and `falseAt`.
`sweep.at(acceptAts)` gives one Row per value at `Policy(acceptAt)`, in order.

## Pick the Policy yourself

Filter the rows by your own limits, then build the `Kleene`:

```kotlin
val row = sweep.at(listOf(0.95, 0.9, 0.85, 0.75))
    .filter { it.accepted >= 30 && it.risk <= 0.02 }
    .maxByOrNull { it.coverage }
    ?: error("no acceptAt keeps 2 % risk")
val ai = Kleene(judge, row.policy)
```

Require enough accepted items (here 30) before you trust a risk value. A risk of 0 over 3 items says little.

## Limits

- All items must come from one Judge and one model. Confidence and calibration differ across Judges, so a mixed
  Sweep throws `IllegalArgumentException`. So does an empty list or an empty gold set.
- A Sweep has no default grid: you list the `acceptAt` values.
- `sweep.at(Policy(minConfidence = ...))` on choose Evidence without confidence throws `KleeneException.Malformed`,
  as `decide` does.
- A Sweep does not pick a Policy and does not publish one.

The ICD demo shows a Sweep on a real run ([demo/icd.md](../../demo/icd.md#sweep)).

## Reference

Spec [§1.11 Sweep](../spec.md#111-sweep).
