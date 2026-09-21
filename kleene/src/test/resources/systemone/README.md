# System One wire fixtures

Real captures, all on 2026-09-21. The first two answer the three golden requests combined into one ask:

- `response-typesafe-captured.json`: `api.typesafe.ai`, model `jev-1.13.0`. Every number rounded to 2 dp.
- `response-kev-captured.json`: local Kev (`scripts/kev.sh`, run `jaredpalmer/kev-4b`, bf16 on mps) at `127.0.0.1:8009`,
  model `kev-4b`. Body saved verbatim. Kev rounds every number to 2 dp, echoes the sent `model`, adds `latency_ms`
  (3248.8, first requests take 3–5 s), and sends no request-id header (`server: uvicorn`). Kev confidence: see spec §3.5.
- `response-kev-captured-10-levels.json`: the same Kev server and model, one score question `effort` with levels
  "1".."10", state "Rename one config key and update its two call sites.". Body saved verbatim. The 2-dp rounding
  drifts: Σ pᵢ = 0.98 and `score` 2.88 against Σ i·pᵢ = 2.78 (0.10 apart, above the old 0.006 × K = 0.06 bound).

Hand-built from documented shapes:

- `request-*.json`: exact body Kleene sends for a single-question ask (golden inputs, model `jev-1.13.0`). Compact, no trailing newline.

Sources: https://docs.typesafe.ai/api.md, https://docs.typesafe.ai/confidence.md,
PyPI `typesafe-sdk` 0.7.0 (`_core/errors.py`, `_core/retry.py`),
https://github.com/jaredpalmer/kev (`kev/serve.py`, `kev/api.py`),
https://github.com/razorback16/openjev (`openjev/api.py`).
