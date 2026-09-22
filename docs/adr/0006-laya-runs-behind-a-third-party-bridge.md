---
status: accepted
date: 2026-09-22
---

# Laya runs behind a third-party bridge

laya-mlx is a Python library and a CLI. It has no HTTP server. phaser/laya-server puts it behind
`POST /v1/systemone`, and `SystemOneJudge` reads its responses with no change.

Decision: Laya qualifies as a Judge only through an external System One bridge. Kleene ships no
Python bridge and no second `Judge` adapter. The tier is "Wire-compatible, untested" (spec
section 3.5). `scripts/laya.sh` pins the bridge commit (`ad2b426`) and the laya-mlx version
(`0.2.0`). The default checkpoint is the multilingual `aac6fef/laya-multilingual-mlx`.

## Considered options

- A second `Judge` adapter for Laya (rejected: it breaks the rule "one adapter, no dialects").
- rimusz/localjev-mlx as the bridge (rejected: it returns 503 for every error, and it pins
  `laya-mlx<0.2`).
- A bridge that Kleene ships (rejected: spec section 0 excludes it from v1).

## Consequences

- State, instructions and options share 1024 tokens on the multilingual checkpoint and 512 on the
  English checkpoint. The server drops the excess silently and still answers 200.
- The multilingual checkpoint has no fitted calibration: every kind is a softmax at temperature 1.
  On the English checkpoint (`aac6fef/laya-mlx`), only a choice with 11 or more options is
  uncalibrated.
- `KLEENE_MODEL` must be `laya-mlx`, the checkpoint id, `jev-latest`, `jev-preview` or
  `jev-1.13.0`. Other names get 404, and Kleene throws `InvalidRequest`. The response `model` is
  always `laya-mlx`.
- One request holds at most 128 questions. The server runs one inference at a time.
- An inference failure that is not a `ValueError` returns 500. Kleene retries it, then throws
  `Overloaded`.
- The wire is sound, but the verdicts are not. In the demo run (`demo/README.md`, section Laya),
  both checkpoints are wrong on most cells that they decide, and chunking makes the errors worse.
  Do not use Laya for `check` without a labelled evaluation of your own.
- This decision extends the server list in the Consequences of ADR-0001.
