# System One wire fixtures

Hand-built from documented shapes and server source code. Not captured from a live server.

- `request-*.json`: exact body Kleene sends for a single-question ask (golden inputs, model `jev-1.13.0`). Compact, no trailing newline.
- `response-typesafe.json`: full-precision numbers; choice/score confidence `(K·pmax−1)/(K−1)` (TypeSafe score formula is unpublished, so that value is only illustrative).
- `response-kev.json`: Kev 2-dp rounding (choice sum 1.01, score sum 0.99, `score` 0.01 off Σ i·pᵢ), `model` echoed, extra `latency_ms`, score confidence `1 − E|i−mode|/(L−1)`.

Sources: https://docs.typesafe.ai/api.md, https://docs.typesafe.ai/confidence.md,
PyPI `typesafe-sdk` 0.7.0 (`_core/errors.py`, `_core/retry.py`),
https://github.com/jaredpalmer/kev (`kev/serve.py`, `kev/api.py`),
https://github.com/razorback16/openjev (`openjev/api.py`).
