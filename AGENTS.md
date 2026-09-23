# AGENTS.md

## Decisions

Log architectural decisions as numbered ADRs in `docs/adr/`, ISO 42010, ASD-STE100 language, as short as the decision allows. `docs/adr/` is the **only** place that carries ADR text — `AGENTS.md` carries the boundaries, the invariants and the working rules and points here. A decision that dies is deleted and its number is listed under `## Withdrawn` in `docs/adr/README.md`, never reused and never renumbered. `CONTEXT.md` is the glossary; use its terms in ADRs, docs and messages.

## Sub-Agent Context Budget

When orchestrating sub-agents (swarms, workflows, parallel Agent calls), keep each sub-agent's context under ~150k tokens — performance degrades noticeably beyond that. If a task would push an agent past this, don't just let it run long: restructure the orchestration to break the work into smaller, sustainable units (more agents with narrower scopes, pipeline stages, or sequential handoffs), and pass only the distilled context each sub-agent actually needs (summaries, file lists, structured findings) rather than raw accumulated output.

## Status

v1 is implemented (Maven build, `kleene` module, tests). Source of truth, in priority order:

1. `docs/spec.md`: the v1 API, semantics, wire mapping, and required tests. Build against this.
2. `docs/adr/`: decisions with trade-offs. Don't re-open them without a new ADR.
3. `CONTEXT.md`: the glossary. Every type, function, and doc term must use its words; each entry lists synonyms to avoid.

`samples/` (gitignored) holds earlier prototypes under the old names "Probably" and "Jev". Don't copy their names or their API shape (e.g. `fold(yes=, no=)`, `jev(...)`, chat-style writers).

## Fixed constraints

- Maven only, never Gradle. Parent pom plus the library module `kleene` and the unpublished `demo` module (ADR-0005). Coordinates `com.antonioagudo.libs:kleene`, package `kleene`.
- MIT license. Never add Apache headers.
- JVM only, JDK 17, Kotlin 2.x. In `kleene`, runtime deps are limited to `kotlinx-coroutines-core` and `kotlinx-serialization-json`. HTTP goes through `java.net.http.HttpClient` (no Ktor or OkHttp).
- Tests use JUnit 5 and `kotlin-test`. Live tests are tagged `live` and excluded from default surefire.
- Ship order: **1 Core → 3 Local judges (SystemOneJudge) → 2 Check**. Spec sections follow this order.
- Spec §0 lists what is out of v1. Don't build it.

## Commands

```sh
mvn test                                        # unit tests; `live` tag excluded
mvn test -pl kleene -Dtest=EvidenceTest#name    # single test
KLEENE_BASE_URL=http://127.0.0.1:8009 KLEENE_MODEL=... mvn test -Dgroups=live   # live smoke tests
scripts/kev.sh                                  # start a local Kev judge on :8009
scripts/laya.sh                                 # start a local Laya judge on :8010 (Apple Silicon)
mvn -q -pl demo exec:java -Dexec.args="..."     # run a demo (after mvn -q -DskipTests install); see demo/README.md, demo/kalah.md
```

## Local judges

A local judge filled the RAM of this 128 GiB Mac (no swap) on 2026-09-23 and the Mac had to be reset. Obey these rules:

- Start a local judge only with `scripts/kev.sh` or `scripts/laya.sh`. They run the server under `scripts/memguard.py`, which kills the whole server process tree when the tree uses more than `MEMGUARD_MAX_GB` or the system has less than `MEMGUARD_MIN_FREE_GB` free (default 16 GiB).
- Caps: Laya 16 GiB (measured peak 3 GiB), plus an MLX cache limit of 1 GiB. Kev 40 GiB, plus a PyTorch MPS limit of ~32 GiB (`PYTORCH_MPS_HIGH_WATERMARK_RATIO=0.3`). To change a cap, set the env var before the script. Do not remove the guard.
- Run one local judge server at a time. Do not run a judge server and an MLX or PyTorch script at the same time. The scripts refuse to start when `:8009` or `:8010` listens.
- To stop a run, stop the server (Ctrl-C or SIGTERM to its script), not only the client. Then make sure that `ps` shows no server process.
- If a server dies by SIGKILL or the guard kills it, do not restart it. Stop and report to the user.
- For a local judge, give the client a timeout much longer than one request. On a timeout the client sends the request again, and the server still computes the first one.

## Architecture

The pipeline is **Question → ask → Judge → validate → Evidence → Policy → Verdict/Rating**.

- `Kleene(judge, policy)` is the only runtime and holds no global state. `feels`/`choose`/`score` build `Question`s bound to that `Kleene`. A Question takes its `name` from the delegated property (`val urgent by ai.feels(...)`). The wire id is `name.sha256(kind, instructions, labels)[:16]`, so renaming an option label creates a new question.
- **One `ask` makes exactly one `Judge.evaluate` call.** Reading `Answers`, `Verdict`, `Evidence`, or `Rating` never calls a model. `Verdict.at(policy)` reapplies a Policy with zero model calls.
- `Judge` is a `fun interface` SPI: `Request(state, wireQuestions) → Response(raw distributions)`. Core validates every Response after any Judge and before building `Answers`. Validation checks ids, kinds, finiteness, [0,1], label sets, the sum tolerance `0.006×K`, and for score the expected-level tolerance `0.006×(1+K(K−1)/2)` (ADR-0003). Any violation throws `Malformed`, and core never repairs a response.
- `Evidence` stores probabilities exactly as received (never renormalized). `Evidence.decide(policy)` is the only place thresholds apply. `Rating` (score) has no policy; the caller's comparison acts as the policy.
- `SystemOneJudge` is the single HTTP adapter (`POST {baseUrl}/v1/systemone`). The same adapter serves cloud TypeSafe and local Kev, openjev or Laya (via laya-server), configured by three env vars. It owns the retry and backoff policy. There is no fallback from a local URL to the cloud.
- `ScriptedJudge` ships in the main artifact for tests. It matches questions by name and records `requests` so tests can assert the one-ask-one-request rule.
- `check(output, contract)` runs every deterministic `Rule` and then sends all requirements as `feels` questions in one ask. It returns a per-requirement `Report` (aggregate order: FAIL > UNKNOWN > PASS) and never modifies the output.

## Invariants that are easy to break

- **UNKNOWN is a value; errors throw** (ADR-0002). Never turn a timeout, 5xx, or malformed response into `Unknown`, and never turn `Unknown` into `false` or an exception. `Verdict` has exactly two cases. Never wrap `CancellationException`, and cancelling the caller must cancel the HTTP request.
- **A Judge returns distributions from model scores, never parsed text** (ADR-0001). Reject any chat-completion or "self-reported confidence" judge. Text generation belongs to a separate `Writer` and never judges.
- Choosing with `minConfidence` set when the response has no confidence throws `Malformed` (fail closed); it does not return Unknown.
- `confidence` comes from each provider and can't be compared across judges; `Evidence.judge` records which judge produced it. `acceptAt` and `margin` are the portable gates.
- K3 `and`/`or`/`not` work on decided `Truth` values only. There is no probability arithmetic across questions.
- Spec §4 lists the minimum required tests. Each feature needs its listed tests before it counts as done.


## Implementation Rules

### 1. ALWAYS Start with Tests (TDD)
### 2. Apply SOLID Principles Rigorously
### 3. Write Clean, Human-Readable Code
### 4. Design with Responsibility in Mind
### 5. Manage Complexity Ruthlessly

**Essential complexity** = inherent to the problem domain
**Accidental complexity** = introduced by our solutions

**Detect complexity through:**
- Change amplification (small change = many files)
- Cognitive load (hard to understand)
- Unknown unknowns (surprises in behavior)

**Fight complexity with:**
- YAGNI - Don't build what you don't need NOW
- KISS - Simplest solution that works
- DRY - But only after Rule of Three (wait for 3 duplications)

### 6. Architect for Change

**Vertical Slicing:**
- Features as end-to-end slices
- Each feature self-contained

**Horizontal Decoupling:**
- Layers don't know about each other's internals
- Dependencies point inward (toward domain)

**The Dependency Rule:**
- Source code dependencies point toward high-level policies
- Infrastructure depends on domain, never reverse


### 7. The Four Elements of Simple Design (XP)

In priority order:
1. **Runs all the tests** - Must work correctly
2. **Expresses intent** - Readable, reveals purpose
3. **No duplication** - DRY (but Rule of Three)
4. **Minimal** - Fewest classes, methods possible

### 8. Code Smell Detection

**Stop and refactor when you see:**

| Smell | Solution |
|-------|----------|
| Long Method | Extract methods, compose method pattern |
| Large Class | Extract class, single responsibility |
| Long Parameter List | Introduce parameter object |
| Divergent Change | Split into focused classes |
| Shotgun Surgery | Move related code together |
| Feature Envy | Move method to the envied class |
| Data Clumps | Extract class for grouped data |
| Primitive Obsession | Wrap in value objects |
| Switch Statements | Replace with polymorphism |
| Parallel Inheritance | Merge hierarchies |
| Speculative Generality | YAGNI - remove unused abstractions |