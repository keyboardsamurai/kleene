# Kleene

A Kotlin/JVM library for typed, three-valued semantic judgments: a model supplies evidence, application code supplies the policy, and uncertainty survives as a first-class outcome. Named after Kleene's three-valued logic (K3).

## Language

### Judging

**Judge**:
A decision model that answers typed questions about a State with probability distributions, never generated text.
_Avoid_: JevClient, provider, model client, LLM

**Writer**:
A text-generation model that produces prose. Never judges; never the same thing as a Judge.
_Avoid_: TextGenerator, llm, generator

**Kleene** (the runtime):
The configured entry point that binds a Judge and a default Policy and exposes the judgment verbs.
_Avoid_: Jev, Probably, ai (as a type name)

**State**:
The explicit input a judgment is made about. Text or structured data, supplied by the caller; never captured from surrounding scope.
_Avoid_: input, context, prompt

**Question**:
A reusable, typed judgment definition (a feels, choose, or score) that can be asked of any State. Its name comes from the Kotlin property it is bound to.
_Avoid_: Semantic, semantic function, judgment (as a type), prompt

**ask**:
Evaluate one or more Questions against one State in exactly one Judge request. Calling a Question directly is asking it alone.
_Avoid_: inspect, evaluate, batch, run

**Answers**:
What one ask returns: the Verdict or Rating for each Question asked, retrieved by the Question itself.
_Avoid_: Evaluation, Inspection, results map

### Judgment kinds

**feels**:
A yes/no judgment about a State. Its outcome is a Truth.
_Avoid_: noul, binary, boolean question

**choose**:
A judgment that picks one value from a fixed set of domain values.
_Avoid_: match, classify, choice question

**score**:
A judgment placing a State on an ordered rubric of levels. Its outcome is a Rating.
_Avoid_: rate, grade, rubric question

### Outcomes

**Truth**:
The three-valued result of a feels judgment: TRUE, FALSE, or UNKNOWN. UNKNOWN means the Policy was not met; it is never an error.
_Avoid_: YES/NO/UNSURE, Boolean, maybe, uncertain (as a value)

**Verdict**:
The outcome of a judgment after a Policy is applied: either Accepted with a value, or Unknown. Carries its Evidence and the Policy that produced it.
_Avoid_: Decision, Answer, result, Abstained

**Rating**:
The outcome of a score judgment: a distribution over levels plus an expected level. Has no accept/unknown split; the caller reads the distribution.
_Avoid_: ScoreAnswer, grade, score (as a noun for the result)

**Evidence**:
What the Judge returned for one question, before any Policy: the probability distribution and any provider-reported metric. Immutable; inspecting it never calls a model.
_Avoid_: raw, response, distribution (as a type name)

**Policy**:
The thresholds that turn Evidence into a Verdict. Belongs to application code, never to the Judge.
_Avoid_: threshold (alone), config, settings

**Unknown** (as an outcome):
The Judge answered, but the Evidence did not meet the Policy. Distinct from an error, which means the Judge did not answer.
_Avoid_: unsure, uncertain, abstain, null

### Operations on outcomes

**reapply**:
Apply a different Policy to existing Evidence. Zero model calls.
_Avoid_: re-decide, recompute, replay

**replay**:
Return previously recorded Evidence instead of calling a Judge. Strict: a missing or mismatched recording fails, never falls through to a live call.
_Avoid_: cache, reapply, mock

**reevaluate**:
Ask a Judge again for fresh Evidence. Costs a model call.
_Avoid_: retry, replay, re-run

### Benchmarks (demos)

**Label set**:
The fixed list of labels a benchmark scores, each with its source. In the ICD bench, 51 ICD-10-CM categories with their official titles.
_Avoid_: taxonomy, classes, code list

**Fixture**:
The committed, static set of documents or positions a benchmark asks about, the same for every Judge.
_Avoid_: dataset, corpus, test set

**Gold labels**:
The labels a fixture item is scored against, fixed before any Judge runs. Not a Judge output.
_Avoid_: ground truth, expected answers, annotations

### Checking (later)

**Contract**:
A named, reusable set of Requirements and Rules an output must satisfy.
_Avoid_: rubric, spec, assertion set

**Requirement**:
One natural-language condition inside a Contract, judged by a Judge as a feels Question.
_Avoid_: assertion, semantic check, criterion

**Rule**:
One deterministic condition inside a Contract, evaluated by ordinary code with no model call.
_Avoid_: check (inside a contract), local check, predicate

**check**:
Evaluate an existing output against a Contract, producing a Report. Never modifies the output.
_Avoid_: validate, assert, satisfy

**Report**:
The per-requirement outcome of a check: each requirement PASS, FAIL, or UNKNOWN, with its Evidence. Never a single aggregate score.
_Avoid_: CheckReport, result, score
