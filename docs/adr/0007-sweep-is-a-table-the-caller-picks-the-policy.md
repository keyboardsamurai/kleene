---
status: accepted
date: 2026-10-02
---

# `Sweep` is in `kleene`; it is a table, and the caller picks the Policy

The default `acceptAt = 0.85` is a guess. Kleene keeps the Evidence, so a caller can reapply many
Policies with zero model calls. The `kalah` and `icd` demos counted accepted and wrong answers per
`acceptAt` with the same hand-written loop.

Decision: `kleene` has `Labeled<T>(evidence, gold)` and `Sweep<T>(labeled)`. `Sweep.at(policy)`
gives one `Row`: n, accepted, wrong, unknown, coverage and risk. Risk is NaN when nothing is
accepted. `Sweep.at(acceptAts)` and `Sweep.table(acceptAts)` give one row per `acceptAt`. All
Evidence must come from one judge and one model, because calibration differs across judges.

`Sweep` does not pick a Policy and does not install one. It has no default grid. The caller reads
the rows, applies its own minimum count and maximum risk, and builds its own `Kleene`.

## Considered options

- Keep the loop in `demo` (rejected: a caller that has Gold labels needs the same loop). Both demos
  call `Sweep.at(policy)` for their headline rows, with no change in output. The per-`acceptAt`
  tables (`perAcceptAt` in `kalah/Bench.kt`, `sweepTable` in `icd/Score.kt`) still use hand loops,
  because they show more metrics than a `Row` holds.
- Add `widest(maxRisk)` that returns the Policy (rejected: it fits the `acceptAt` on the same items
  that estimate the risk, and spec section 5 says "No automatic threshold tuning").
- A default `acceptAt` grid (rejected: the demos use different grids).

## Consequences

Spec section 1.11 specifies `Sweep`. Spec section 5 stays true. `CONTEXT.md` has a Sweep entry.
