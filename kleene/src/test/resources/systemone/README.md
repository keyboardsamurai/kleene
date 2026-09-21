# System One wire fixtures

`response-typesafe-captured.json` is a real `api.typesafe.ai` response (`jev-1.13.0`, 2026-09-21) to the golden
requests combined into one ask. Note that TypeSafe also rounds every number to 2 dp. All other files are hand-built
from documented shapes and server source code, not captured from a live server.

- `request-*.json`: exact body Kleene sends for a single-question ask (golden inputs, model `jev-1.13.0`). Compact, no trailing newline.
- `response-typesafe.json`: full-precision numbers (illustrative; the live API rounds to 2 dp, see the capture); choice/score confidence `(K·pmax−1)/(K−1)` (TypeSafe score formula is unpublished, so that value is only illustrative).
- `response-kev.json`: Kev 2-dp rounding (choice sum 1.01, score sum 0.99, `score` 0.01 off Σ i·pᵢ), `model` echoed, extra `latency_ms`, score confidence `1 − E|i−mode|/(L−1)`.

Sources: https://docs.typesafe.ai/api.md, https://docs.typesafe.ai/confidence.md,
PyPI `typesafe-sdk` 0.7.0 (`_core/errors.py`, `_core/retry.py`),
https://github.com/jaredpalmer/kev (`kev/serve.py`, `kev/api.py`),
https://github.com/razorback16/openjev (`openjev/api.py`).
