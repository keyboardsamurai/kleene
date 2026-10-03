---
status: proposed
date: 2026-10-03
---

# OpenAI Decisions is a Judge only with a model score for every answer

OpenAI's Decisions API (announced 2026-09-29, GPT-6 Luna) takes a question, caller-defined answers
and a text or image context, and returns one selected answer. It does not speak the System One wire:
the path is `/v1/decisions`, the body is OpenAI's, the request id is `x-request-id`. The schema is
not published, and the project key has no preview access (403 on 2026-10-03).

Decision: `kleene` ships a second HTTP Judge, `OpenAIDecisionsJudge`, with the constructor shape,
transport policy and errors of `SystemOneJudge`, only if the response holds a model-derived score for
every answer in the set. The adapter may apply one documented deterministic transform (`exp`, or
softmax over exactly the K answers) and nothing else: no renormalization, no filled answers, no
smoothing. A winning answer, a winner with one confidence number, a number with no documented
transform, a sparse list, or an abstain answer in the response is not a distribution over the K
answers; then there is no adapter (ADR-0001). `feels` is one question with the constant answers
`"true"` and `"false"`; the adapter checks that the two scores sum to 1 within `0.012` and returns
`Raw.Noul`. `score` is `Unsupported`. If one call takes one question, `evaluate` fans out
concurrently, each POST with its own retries, and fails as a whole.

## Considered options

- A dialect in `SystemOneJudge` (rejected: ADR-0006; the class name would lie).
- An external System One bridge (rejected: none exists; a proxy in front of a cloud API).
- A Responses API judge with an enum schema and logprobs (rejected: ADR-0001; not every answer gets
  a score).
- `score` as a choose over levels with a computed expected level (rejected: the order is not
  conveyed to the model).
- Reading an omitted answer as 0, or dropping an abstain answer before the transform (rejected:
  filling and silent conditioning).

## Consequences

- This decision amends the Consequences of ADR-0001: a server qualifies when it returns a model
  score for every answer, with or without the System One wire. It narrows ADR-0006: "no second
  `Judge` adapter" and "one adapter, no dialects" apply per wire. A server that speaks System One
  still goes through `SystemOneJudge`.
- `OPENAI_API_KEY` and `OPENAI_BASE_URL` configure it, with the OpenAI SDK's meaning: the base URL
  includes `/v1`. `KLEENE_*` stays host-only for System One.
- The transport helpers of `SystemOneJudge` become `internal` in `Transport.kt`; the retry loop is
  copied, not shared, until a third HTTP judge exists. Nothing moves before the schema is published.
- Confidence from OpenAI, if any, is non-portable like every other confidence. Without one, a Policy
  with `minConfidence` throws `Malformed` on every ask (fail closed, unchanged).
- Images stay out: `State` has no image case.
- If the published schema holds no score for every answer, this record keeps its number and is
  rewritten as the refusal.
