---
status: accepted
date: 2026-10-02
---

# Strict record and replay are in `kleene`

Spec section 0 put record/replay out of v1. A semantic test that calls a model cannot run in the
default `mvn test`, and a bench that scores again must ask the judge again. A caller can write both
parts outside `kleene` with the public `Judge`, `Request` and `Response`, but each caller then writes
a different file format and a different match rule.

Decision: `kleene` has `Judge.recordingTo(path)` and `ReplayJudge(path)`. The recording is JSONL,
one line per ask: the judge id, the Request (State and wire questions) and the Response as received.
`ReplayJudge` returns the recorded Response for an equal Request and never calls a model. Its id is
the recorded judge id, so replayed Evidence equals recorded Evidence. Core validates each replayed
Response as for any Judge.

Replay is strict. A Request with no recording throws `IllegalStateException`. It is not a
`KleeneException`, because a missing recording is a test or setup error, not a judge that did not
answer. A recording file that is missing or unreadable, is empty, holds a line that is not a
recording, holds more than one judge id, or holds one Request with two different models or answers
throws `IllegalArgumentException` when `ReplayJudge` is built.

Parsing is strict. A field with the wrong JSON type (for example `"judge": null` or `"p": "0.5"`)
makes the line not a recording; `ReplayJudge` never repairs a line. A non-finite double in a Response
is written as the JSON string `"NaN"`, `"Infinity"` or `"-Infinity"`; on read, these three strings are
the only strings accepted for a number. Each line is valid JSON when the State is valid JSON.

`recordingTo` creates missing parent directories when it wraps the Judge. It writes the line after
the Judge answers, so a line that it can not write (for example, the path is a directory, or a text
is not valid Unicode) throws `IOException` from `ask` after the model call.

## Considered options

- Keep record/replay out of v1 (rejected: each caller writes its own format and match rule).
- Fall through to a live Judge when no recording matches (rejected: `CONTEXT.md` says replay is
  strict; a test must not call a model in secret).
- Throw `KleeneException.Malformed` for a missing recording (rejected: callers handle
  `KleeneException` as a judge failure; the recording is not a judge response).
- Match on the question names only (rejected: a changed instruction or label must not replay an
  old Response).

## Consequences

Spec section 0 no longer lists record/replay. Spec section 1.12 specifies it. Only
`kotlinx-serialization-json` is used; no dependency is added. A Judge that throws records nothing,
so its error can not be replayed. A Response that core rejects as `Malformed` is recorded as
received, and its replay throws `Malformed` again.
