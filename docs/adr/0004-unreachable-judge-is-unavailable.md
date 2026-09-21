---
status: accepted
date: 2026-09-21
---

# An unreachable judge is `Unavailable`, not `Overloaded`

The connection to the judge can fail. Examples are a refused connection, a DNS failure and a
connection reset. Each is an `IOException` that is not a timeout. The adapter retries these failures.
When no retry is left, it throws `KleeneException.Unavailable`. HTTP 5xx, including 529, stays
`Overloaded`. A timeout stays `Timeout`. With a local judge, the usual cause is that Kev does not run
or that the base URL is wrong. The remedy is configuration, not back-off.

## Considered options

- Keep `Overloaded(status = null)` (rejected: it tells the caller to back off, but the server did
  not answer at all).
- Reuse `Timeout` (rejected: the attempt failed at once and did not time out).

## Consequences

`KleeneException` has one more sealed case. Caller code with an exhaustive `when` over
`KleeneException` must add a branch. `Overloaded` always carries an HTTP status and no cause.
