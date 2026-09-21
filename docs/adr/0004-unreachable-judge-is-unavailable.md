---
status: accepted
date: 2026-09-21
---

# An unreachable judge is `Unavailable`, not `Overloaded`

A connection-level failure (connection refused, DNS failure, connection reset: any `IOException`
that is not a timeout) throws `KleeneException.Unavailable` when no retry is left. The adapter
retries it like any other `IOException`. HTTP 5xx (incl. 529) stays `Overloaded`. A timeout stays
`Timeout`. With a local judge, the usual cause is "Kev is not running" or a wrong base URL. The
remedy is configuration, not back-off.

## Considered options

- Keep `Overloaded(status = null)` (rejected: it tells the caller to back off, but the server did
  not answer at all).
- Reuse `Timeout` (rejected: the attempt did not time out, it failed at once).

## Consequences

`KleeneException` has one more sealed case. Caller code with an exhaustive `when` over
`KleeneException` must add a branch.
