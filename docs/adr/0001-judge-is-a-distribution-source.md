---
status: accepted
date: 2026-09-21
---

# A Judge returns probability distributions, never generated text

Kleene's `Judge` SPI is `State + typed questions → distributions + metadata`, modelled on TypeSafe's
System One wire format. We deliberately do not ship, and will reject PRs that add, a judge that
prompts a chat model to emit JSON and parses a self-reported `"confidence": 0.95`. A generated number
is not the same kind of evidence as a distribution read from model scores; treating both as
`Evidence` would make every Policy threshold meaningless the moment the judge is swapped.

## Considered options

- Chat-completion adapter behind the same `Judge` interface (rejected: impersonates probabilistic evidence).
- A second, weaker SPI advertising "claimed" confidence (deferred: no consumer yet; if it comes, it is a
  distinct type, not a `Judge`).

## Consequences

Only System One–compatible servers (TypeSafe, Kev, openjev) and future in-process scorers qualify.
Text generation stays behind the separate `Writer` interface and never judges.
