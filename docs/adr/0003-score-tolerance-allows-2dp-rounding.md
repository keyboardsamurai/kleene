---
status: accepted
date: 2026-09-21
---

# The score expected-level tolerance allows 2-dp rounding

Both tested System One servers, TypeSafe and Kev, round every number to 2 decimal places. Each rounded
number can be off by up to 0.005, and the errors add up in Σ i·pᵢ. With the tolerance `0.006 × K`, real
score responses throw `Malformed` from K = 4.

Core allows `|expected − Σ i·pᵢ| ≤ 0.006 × (1 + K(K−1)/2)`: 0.006 for `expected` and 0.006 × i for each
pᵢ. The sum tolerance `|Σp − 1| ≤ 0.006 × K` does not change, because it already covers the rounding.

## Considered options

- Keep `0.006 × K` (rejected: real responses fail from K = 4).
- Remove the check and keep only "finite and in [0, K−1]" (rejected: the range check does not
  reliably find a 1-based expected level).

## Consequences

Correct responses that are rounded to 2 decimal places pass validation at all K. An expected level that
is off by 1.0 (1-based) still throws `Malformed`, because the tolerance is 0.276 at the maximum of 10
levels. Smaller errors in the expected level can pass. Core still never repairs a response.
