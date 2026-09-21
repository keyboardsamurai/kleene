---
status: accepted
date: 2026-09-21
---

# Errors throw; UNKNOWN is a value and never an error

Infrastructure failures (auth, rate limit, timeout, malformed response, budget) throw a
`KleeneException`. Policy misses produce `Verdict.Unknown` / `Truth.UNKNOWN`. The two never convert
into each other: a timeout must not become UNKNOWN (it would silently route to human review and hide
an outage), and UNKNOWN must not become `false` or an exception (it would erase the third value the
library exists to preserve).

## Considered options

- One sealed result type carrying both outcomes and errors (rejected: every call site would have to
  match error branches it cannot handle, and Kotlin's coroutine cancellation already uses exceptions).
- Returning UNKNOWN on timeout with a flag (rejected: fail-open by construction).

## Consequences

`Verdict` has exactly two cases. `minConfidence` requested but absent in the response is `Malformed`,
not Unknown (fail closed). Callers who want a sealed error type wrap `Judge` themselves.
