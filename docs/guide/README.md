# Kleene guide

This guide introduces Kleene one step at a time, from your first yes/no judgment to picking a Policy from stored
Evidence. Each page builds on the previous one. It is for Kotlin developers who want a model's judgment behind a
`when`, with doubt kept as a value and failures kept as exceptions.

| Page | You learn to |
|---|---|
| [1. Getting started](01-getting-started.md) | Install Kleene, configure a Judge, ask your first `feels` question |
| [2. Questions](02-questions.md) | Define `feels`, `choose` and `score` questions and read a `Verdict` or a `Rating` |
| [3. Asking several questions](03-asking-several-questions.md) | Ask many Questions in one request and read the `Answers` |
| [4. Policy and Evidence](04-policy-and-evidence.md) | Set a Policy, handle UNKNOWN, and reapply a Policy with zero model calls |
| [5. Judges](05-judges.md) | Use the cloud or a local Judge, set timeouts and retries, and handle errors |
| [6. Checking outputs](06-checking-outputs.md) | Hold any text against a `Contract` and read the `Report` |
| [7. Testing](07-testing.md) | Test without a model: `ScriptedJudge`, record and replay |
| [8. Picking a Policy with Sweep](08-picking-a-policy-with-sweep.md) | Pick an `acceptAt` from labeled Evidence |

## Words used in this guide

The guide uses the terms of the glossary, [CONTEXT.md](../../CONTEXT.md). The most important ones:

| Term | Meaning |
|---|---|
| Judge | A decision model that returns a probability for each possible answer, never generated text |
| State | The text or JSON that a judgment is about |
| Question | A reusable `feels`, `choose` or `score` definition |
| ask | Send one or more Questions about one State to the Judge in one request |
| Evidence | The probabilities the Judge returned, before any Policy |
| Policy | The thresholds your code sets to turn Evidence into a Verdict |
| Verdict | `Accepted` with a value, or `Unknown` |
| UNKNOWN | The Judge answered, but not clearly enough for your Policy. A value, never an error |

## Reference

- [Specification](../spec.md): the normative API and semantics.
- [Architecture decision records](../adr/README.md): the decisions and their trade-offs.
