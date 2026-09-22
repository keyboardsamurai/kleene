# Promise tests

A demo of Kleene's `check` on real terms-of-service history: for every git version of a
document, ask whether it keeps a set of user promises, store the `Evidence`, and reapply a
`Policy` to it later at zero model calls.

## What it shows

- **UNKNOWN as a value.** A promise going vague across two versions is a TRUE -> UNKNOWN
  flip, not a TRUE -> FALSE flip. A binary classifier has no third value to land on; Kleene's
  `Truth` does.
- **Reapply.** `log` asks each `Judge` once per version per chunk and stores the resulting
  `Evidence` (`p(true)`) as JSONL. `grid` and the HTML slider then call `Evidence.decide(policy)`
  at any `acceptAt` with **zero model calls** — the stored probabilities never change, only the
  threshold does.
- **Contracts.** The promises are one `Contract`, `userPromises`, checked in one `check()` call
  per chunk:

  ```kotlin
  val userPromises by contract {
      +"Commits to not selling personal data to third parties"
      +"Lets the user delete their account and their data"
      +"Commits to notifying users before the terms change"
      +"Commits to not using user content to train AI models"
      rule("Is not an empty scrape") { it.length > 2_000 }
  }
  ```

  `check()` names the `feels` questions `userPromises.1` .. `userPromises.4`; the length rule is
  a deterministic `Rule`, evaluated with no model call.
- **Swappable Judges.** `SystemOneJudge` is one HTTP adapter that serves both cloud TypeSafe and
  a local Kev, chosen by environment variables. Run `log` twice, once per judge, into two JSONL
  files, and pass both to `grid`/`html` to see where a cloud and a local judge disagree.

## Dataset

[Open Terms Archive](https://github.com/OpenTermsArchive/pga-versions) ("Platform governance
archive"), ~27 services, one Markdown file per document, one git commit per version of that
document. Licence, verified 2026-09-21 by fetching
`https://raw.githubusercontent.com/OpenTermsArchive/pga-versions/main/LICENSE`: **ODC-By
1.0** (Open Data Commons Attribution License), not a code licence — it covers the archived
document collection, and it requires attribution to Open Terms Archive when the data is
republished. `Instagram/Privacy Policy.md` is about 135 KB at HEAD.

## Run

Clone the archive once, alongside or anywhere on disk:

```sh
git clone https://github.com/OpenTermsArchive/pga-versions.git
```

Build Kleene once, from the repo root:

```sh
mvn -q -DskipTests install
```

Pick a judge. Cloud TypeSafe:

```sh
export KLEENE_MODEL=jev-1.13.0
export KLEENE_API_KEY=...
```

or a local Kev (`scripts/kev.sh` starts one on `:8009`):

```sh
export KLEENE_BASE_URL=http://127.0.0.1:8009
export KLEENE_MODEL=kev-4b
```

Then, from `demo/` (outputs land under `demo/out/`, gitignored):

```sh
log <repoDir> <path> [--out promises.jsonl] [--chunk <chars>] [--timeout <seconds>]
grid <jsonl>... [--accept-at 0.85]
html <jsonl>... [--out grid.html] [--repo-url URL]
```

For example:

```sh
mvn -q exec:java -Dexec.args="log ../pga-versions 'Instagram/Privacy Policy.md' --out out/cloud.jsonl"
mvn -q exec:java -Dexec.args="grid out/cloud.jsonl --accept-at 0.85"
mvn -q exec:java -Dexec.args="html out/cloud.jsonl --out out/grid.html --repo-url https://github.com/OpenTermsArchive/pga-versions"
```

To compare judges, run `log` a second time under the other judge's environment into a second
file, then pass both files to `grid`/`html`:

```sh
mvn -q exec:java -Dexec.args="grid out/cloud.jsonl out/local.jsonl --accept-at 0.85"
```

`--timeout` is the per-attempt limit for one ask, in seconds, and defaults to 120. The library
default is 10 seconds, which suits a short interactive question but not this workload: one
terms-of-service version is a large `State`, and a local Kev on a laptop takes about 12 seconds
for a 15 KB document. Raise it further for a slower judge or a larger document.

## Long documents

If the judge rejects a version as too long, it throws `KleeneException.InvalidRequest` (HTTP
422). Rerun with `--chunk 12000`: each chunk becomes one `ask` (one model call), and per
requirement the chunks are combined with `Truth.or` — TRUE if any chunk commits, FALSE if every
chunk is FALSE, otherwise UNKNOWN. This **cannot catch a contradiction that sits in a different
section** from the one that made the promise; it only detects a promise breaking within the
section that states it.

A crash mid-run is resumed by rerunning the same `log` command: versions already present in the
output file are skipped. That is the whole error strategy. Judge errors are never recorded as
UNKNOWN — `log` never catches `KleeneException`; a failed run must be rerun, not silently
patched.

## Record format

One JSON line per version per judge run:

```jsonc
{
  "path": "Instagram/Privacy Policy.md",
  "commit": "<sha>",
  "date": "2021-05-03T00:00:00Z",
  "judge": "api.typesafe.ai/jev-1.13.0",
  "model": "jev-1.13.0",
  "requirements": [
    { "label": "Commits to not selling personal data to third parties", "pTrue": [0.92] }
  ],
  "rules": [
    { "label": "Is not an empty scrape", "outcome": "PASS" }
  ]
}
```

`requirements[].pTrue` has one entry per chunk (size 1 when the whole text was judged in one
`ask`). `rules[].outcome` is `"PASS"` or `"FAIL"`, from the deterministic length rule.

## Before publishing findings about a named company

Every flip (a `Truth` that differs from the previous version's `Truth` at the published
`acceptAt`) is **flagged for review**: hand-verify it against the linked commit diff before
citing it. Publish an accuracy number alongside any claim, from a short labelling protocol:
sample about 50 `(version, requirement)` cells stratified over TRUE / FALSE / UNKNOWN at the
published `acceptAt`; two people label each cell TRUE / FALSE / unclear, blind to the grid's
verdict and to each other; compare labels with the grid; report agreement per class (TRUE,
FALSE, UNKNOWN).

## Cost

Tokens sent per version per chunk are roughly `characters / 4`. Total tokens for a run are
approximately `sum over versions of (chunks × requirements × characters_per_chunk / 4)`. No
dollar figure here: rates vary per judge and change over time.

## Not built yet

- Rename following in git history (a document that moves path loses its history).
- Concurrency across versions (`log` runs one version at a time).
- A hosted site (the HTML grid is a single static file, opened locally).
- Per-service contracts (`userPromises` is one fixed contract for any document).
- A readable grid for long histories: the text grid prints one column per version, so 59
  versions is already wider than a terminal. The HTML grid scrolls sideways.

## A real run

`Threads/Privacy Policy.md`, all 59 versions, the same contract run twice: once through a local
Kev (`kev-4b`) and once through cloud TypeSafe (`jev-1.13.0`), both on 2026-09-21. Each run made
59 model calls. Every grid and every slider position after that made none.

| | local `kev-4b` | cloud `jev-1.13.0` |
|---|---|---|
| wall clock, 59 versions | about 10 minutes | 18 seconds |

### Accuracy

This document is a *supplemental* policy that points to the main Meta Privacy Policy. No version
of it mentions selling data, notice before a change, or AI training, and every intact version
describes account deletion. That gives a label for each cell without a model: the three unmentioned
promises are not made (FALSE), and deletion is (TRUE). Scored against those labels, over 236 cells:

| acceptAt | kev decided | kev wrong | jev decided | jev wrong |
|---|---|---|---|---|
| 0.95 | 26 | 0 | 0 | 0 |
| 0.85 | 57 | 0 | 203 | 0 |
| 0.75 | 85 | 1 | 233 | 0 |
| 0.60 | 175 | 16 | 233 | 0 |

Neither judge was ever wrong at the default `acceptAt` of 0.85. They differ in how often they will
commit: Kev decided 57 cells and abstained on 76% of the grid, Jev decided 203. Kev starts making
real errors below 0.75 and is 91% precise at 0.60, where Jev is still clean. At 0.95 the order
inverts and Jev decides nothing, because its `p(true)` never exceeds 0.90 even on the deletion
clause that Kev scores up to 0.98.

### Where the judges disagree

At `acceptAt` 0.85, 148 of 236 cells disagree, and **none of them is a contradiction**: in 147 Kev
is UNKNOWN where Jev says FALSE, and in one the reverse. The two judges never call the same cell
TRUE and FALSE. They agree exactly on all 59 deletion cells, the one promise this document
actually makes. Lower the dial to 0.60 and 13 real contradictions appear, all of them Kev TRUE
against Jev FALSE, and all 13 are cells where the document is silent and Jev is right.

The archive alternates between two captures that differ only by a trailing `Other ways to get help`
heading, 47 characters. On the data-selling requirement that footer moves Kev between `p(true)`
0.20 and 0.63, enough to flap the row at `acceptAt` 0.75. Jev returns 0.14 either way. The
instability is a property of the small local judge, not of the document.

Read the decided `Truth` across judges, never the probabilities: `p(true)` and `confidence` come
from different models and are not comparable. `Evidence.judge` records which judge produced each
cell for exactly this reason.

### What this does and does not establish

The run supports no published claim about Threads, and it was not meant to. It does show the
three-valued default doing its job: at `acceptAt` 0.85 a 0.43 swing caused by boilerplate stayed
inside the UNKNOWN band and never reached a verdict, which is the failure a Boolean would have
published.

The accuracy above is one document, four requirements and two judges, labelled by one person from
the document text rather than by the blind two-person protocol above. The 236 cells are not 236
independent observations: the text barely changes across versions, so the effective sample is
closer to four requirement-level judgements plus the scrape variants. Treat it as a sanity check
on the method, not as a measured accuracy for either judge.
