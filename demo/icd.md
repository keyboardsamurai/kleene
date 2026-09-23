# ICD bench

A demo of Kleene on a multi-label classification task: code a clinical text into ICD-10-CM categories. Each
document gets one `ask` with 52 fixed questions: one `feels` per code and one `choose` for the principal
diagnosis. `log` stores the `Evidence` as JSONL. `rank` then reapplies a `Policy` at any `acceptAt` with zero
model calls and scores each judge against gold labels and three no-model baselines.

Code: `kleene.demo.icd` (`Fixture.kt` loads and checks the label set and the fixture, `Bench.kt` holds the
questions and `log`, `Score.kt` the metrics, the baselines and `leaderboard`, `Main.kt` the CLI). Spec:
[`icd_spec.md`](icd_spec.md).

## Label set

51 ICD-10-CM 3-character categories from 16 chapters, in `src/main/resources/kleene/demo/icd/labels.json`.
The set has confusable siblings (E10/E11, I20/I21, J44/J45, N17/N18, F32/F33, K80/K81, M80/M81 and more),
symptom codes (R07, R10, R50, R51) and history and family-history codes (Z80, Z82, Z85, Z86, Z87).

Source and licence, as stated in the file:

- Titles: ICD-10-CM tabular list (FY2025), CMS and NCHS/CDC. A US government work, public domain. Only the
  official English titles of the 3-character categories are used.
- Synonyms: "demo synonyms, not official titles". They were written for this demo in 7 languages (en, de, es,
  fr, it, pt, nl). They do not come from WHO ICD-10, ICD-10-GM (BfArM), CIE-10-ES, CIM-10 (ATIH) or any other
  translation. Only the keyword baseline uses them. The judges never see them.

## Fixture

100 synthetic documents in `src/main/resources/kleene/demo/icd/fixture.jsonl`. Each document has gold labels:
one principal category (or none) and 0 to 4 categories in total, each with its full ICD-10-CM subcode. `rank`
scores only the 3-character categories. The texts have 37 to 269 words (242 to 1701 characters, 140 words on
average).

| axis | values (documents) |
|---|---|
| language | en 15, de 13, es 13, fr 13, it 13, nl 13, pt 13, mixed (code-switched) 7 |
| type | 10 each: discharge summary, GP note, ER triage, radiology report, pathology report, referral letter, lab report, nursing note, patient message, insurance claim note |
| completeness | full 40, terse 25, truncated 15, no-diagnosis 20 |
| provenance | clinician 53, OCR 15, machine-translated 12, dictation (with ASR errors) 10, patient (lay words) 10 |
| hard | none 80, negation 5, no-code 5, history 4, family 3, suspected 3 |
| codes per document | 0: 5, 1: 8, 2: 51, 3: 36 (2.18 on average, 218 gold cells) |

All 51 categories occur in the gold labels at least once. The most frequent is I10 (16 documents), then Z87 (13)
and R50 (12).

The gold labels follow these coding conventions:

- A suspected or "rule out" diagnosis is not coded. Its symptoms are coded instead.
- A past condition goes to a personal-history Z code (Z85, Z86, Z87), and a relative's condition to a
  family-history Z code (Z80, Z82). The condition code itself is not coded.
- A negated finding ("pneumonia ruled out") is not coded.

How the fixture was made: sub-agents wrote each document from its target codes, so the gold labels come from the
writing process. The writers got the codes and the official titles only, never the demo synonyms. Then one
independent sub-agent relabelled all 100 documents blind, from the text and the label set only. It disagreed on
0 of 100. The fixture has not changed after that.

## State and questions

The judge sees one JSON `State` per document: `{"document": "<text>"}`. The axis tags and the gold labels are
never sent. The questions are in English for every document, so the wire ids are the same for every document.

| name | kind | instructions | options |
|---|---|---|---|
| `A09` .. `Z87` (51) | feels | The document supports `<code> <title>` as a current diagnosis of this patient | – |
| `principal` | choose | Which is the principal diagnosis of this patient in the document? | `<code> <title>` for each of the 51 codes, and `none of these` |

## Run

Build Kleene once, from the repo root:

```sh
mvn -q -DskipTests install
```

Pick a judge. Cloud TypeSafe (Jev):

```sh
export KLEENE_MODEL=jev-1.13.0
export KLEENE_API_KEY=...
```

or a local Kev (`scripts/kev.sh` starts one on `:8009`) or a local Laya (`scripts/laya.sh` starts one on `:8010`,
Apple Silicon only; `LAYA_MODEL=aac6fef/laya-mlx scripts/laya.sh` starts the English checkpoint). Both scripts
run the server under a memory guard. Start only one local judge at a time, and read "Local judges" in
[`AGENTS.md`](../AGENTS.md#local-judges) first.

```sh
export KLEENE_BASE_URL=http://127.0.0.1:8010   # :8009 for Kev
export KLEENE_MODEL=laya-mlx                   # kev-4b for Kev
```

laya-mlx cuts a request that is too long and does not tell the client. So before a Laya run, count the tokens
with the checkpoint's own tokenizer and list the documents that do not fit. Do this from the repo root, with no
judge server running:

```sh
scripts/icd-laya-cut.py demo/src/main/resources/kleene/demo/icd/fixture.jsonl \
  demo/src/main/resources/kleene/demo/icd/labels.json --model aac6fef/laya-mlx --out demo/out/icd/cut-laya-en.txt
```

(`--model aac6fef/laya-multilingual-mlx --out demo/out/icd/cut-laya.txt` for the multilingual checkpoint.)

Then, from `demo/`, log one file per judge. The file name is the label in the leaderboard, because both Laya
checkpoints report the same judge id:

```sh
mvn -q exec:java -Dexec.mainClass=kleene.demo.icd.MainKt -Dexec.args="log --out out/icd/jev-1.13.0.jsonl"
mvn -q exec:java -Dexec.mainClass=kleene.demo.icd.MainKt -Dexec.args="log --out out/icd/laya.jsonl --cut out/icd/cut-laya.txt --timeout 300"
mvn -q exec:java -Dexec.mainClass=kleene.demo.icd.MainKt -Dexec.args="log --out out/icd/laya-en.jsonl --cut out/icd/cut-laya-en.txt --timeout 300"
mvn -q exec:java -Dexec.mainClass=kleene.demo.icd.MainKt -Dexec.args="log --out out/icd/kev-4b.jsonl --timeout 600"
```

Score every run side by side:

```sh
mvn -q exec:java -Dexec.mainClass=kleene.demo.icd.MainKt -Dexec.args="rank out/icd/jev-1.13.0.jsonl out/icd/kev-4b.jsonl out/icd/laya.jsonl out/icd/laya-en.jsonl"
```

Options:

```text
log [--out out/icd/icd.jsonl] [--timeout 120] [--cut <file of document ids, one per line>]
rank <jsonl>... [--accept-at 0.5,0.6,0.7,0.8,0.85,0.9,0.95]
```

`log` asks once per document and resumes by document id. It refuses a file with another judge id, another
fixture version (a hash of the fixture and the label set) or another cut. It never catches a judge error: a
timeout, a 5xx or a `Malformed` response stops the run, and you run it again. `--cut` does not skip a document:
it marks its record `fits = false`. `--timeout` is the per-attempt limit in seconds. On a timeout, the library
sends the request again while a local server still computes the first one, so give a local judge a timeout much
longer than one request.

## Metrics

Each document has 51 `feels` cells, one per code. `rank` reapplies the `Policy` at each `acceptAt` and counts
each cell on its decided `Truth`:

- **micro P, R, F1**: over all cells. UNKNOWN is not predicted, so it lowers recall, never precision.
- **macro F1**: the mean F1 per code, over the codes with at least one gold cell in the documents of the run.
- **coverage**: decided cells over all cells. **accuracy decided**: right cells over decided cells.
- **AUC**: p(TRUE) of the gold cells against the other cells, with no threshold.
- **principal top-1 decided**: the principal `choose` is right, over the documents where it is decided.
  **principal unknown**: the share of documents where it is UNKNOWN. **principal top-1 all**: right over all
  documents, so UNKNOWN counts as wrong. `none of these` is right on the 5 documents with no gold code.
- **cut**: the records marked `fits = false`.

Three baselines play no judge and fill only their own columns:

- `always empty`: every code FALSE, principal `none of these`.
- `most frequent (I10)`: the most frequent gold category is the principal and the only code.
- `keyword`: a code is TRUE if any of its demo synonyms, in any language, occurs as a case-insensitive whole word
  or phrase. The principal is the first matched code in label-set order, else `none of these`. It has no negation
  handling, on purpose.

## Results

100 documents, logged on 2026-09-23 through four judges: cloud TypeSafe (`jev-1.13.0`), a local Kev (`kev-4b`,
bf16, PyTorch MPS) and a local Laya (`laya-mlx` 0.2.0 behind laya-server@`ad2b426`) on its multilingual
(`aac6fef/laya-multilingual-mlx`) and English (`aac6fef/laya-mlx`) checkpoints. Kev and Laya ran on one 128 GiB
Mac. `rank` made no model call, and two `rank` runs printed the same output, byte for byte.

| | `jev-1.13.0` | `kev-4b` | Laya multilingual | Laya English |
|---|---|---|---|---|
| records | 100 | 4 | 100 | 100 |
| errors / retries | none recorded | HTTP 500 on `icd-005` in 2 runs, each 1 try and 2 retries | 0 / 0 | 0 / 0 |
| time | not recorded | stopped at `icd-005` | 0.25 to 0.7 s per request | 134 s for 100 documents |
| peak memory of the server | – (cloud) | 33.1 GiB | 3.0 GiB | 3.1 GiB |
| documents marked `fits = false` | 0 | 0 | 0 | 62 |

**Kev stopped after 4 documents.** On `icd-005` (1123 characters, not a long document) the Kev server failed
twice with `RuntimeError: MPS backend out of memory (MPS allocated: 29.80-31.94 GiB, max allowed: 32.26 GiB)` in
`torch_chunk_gated_delta_rule`. That limit is the one `scripts/kev.sh` sets
(`PYTORCH_MPS_HIGH_WATERMARK_RATIO=0.3`). A 52-question ICD request needs more than that on Kev. Without a limit
it held 58 to 60 GB. The Kev rows below cover `icd-001` to `icd-004` only, and none of them is a hard document.
They show that Kev runs on this bench, not how well it codes.

**Laya context window.** The state, the instructions and the options share 1024 tokens on the multilingual
checkpoint and 512 on the English one (ADR-0006). `scripts/icd-laya-cut.py` counted them with each checkpoint's
own prompt builder and tokenizer:

| checkpoint | window | longest request | documents cut | cut by |
|---|---|---|---|---|
| multilingual | 1024 | 705 | 0 | – |
| English | 512 | 806 | 62 | the `principal` choose on all 62, a feels question too on 18 |

On both checkpoints, the 52 options of the `principal` choose overflow the head budget, so laya-mlx clips 51 of
the 52 options to 4 tokens each. Laya picks among bare codes, not among codes with their titles.

### Leaderboard

| run | n | micro P | micro R | micro F1 | macro F1 | coverage | accuracy decided | AUC | principal top-1 decided | principal unknown | principal top-1 all | cut |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| jev-1.13.0 | 100 | 0.98 | 0.56 | 0.72 | 0.70 | 96% | 100% | 1.00 | 95% | 9% | 86% | 0 |
| kev-4b | 4 | 1.00 | 0.56 | 0.71 | 0.56 | 90% | 99% | 0.98 | – | 100% | 0% | 0 |
| laya | 100 | 0.06 | 0.45 | 0.10 | 0.11 | 57% | 42% | 0.60 | 0% | 98% | 0% | 0 |
| laya-en | 100 | 0.09 | 0.22 | 0.12 | 0.10 | 43% | 75% | 0.67 | 0% | 90% | 0% | 62 |
| baseline: always empty | 100 | – | 0.00 | 0.00 | 0.00 | 100% | 96% | – | 5% | 0% | 5% | – |
| baseline: most frequent (I10) | 100 | 0.16 | 0.07 | 0.10 | 0.01 | 100% | 94% | – | 2% | 0% | 2% | – |
| baseline: keyword | 100 | 0.67 | 0.73 | 0.70 | 0.68 | 100% | 97% | – | 42% | 0% | 42% | – |

At `acceptAt` 0.85, the library default. `laya` is the multilingual checkpoint, `laya-en` the English one.

### Sweep

| acceptAt | run | micro P | micro R | micro F1 | macro F1 | coverage | accuracy decided | principal top-1 decided | principal unknown | principal top-1 all |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0.50 | jev-1.13.0 | 0.85 | 0.83 | 0.84 | 0.88 | 100% | 99% | 93% | 0% | 93% |
| 0.50 | kev-4b | 0.60 | 0.67 | 0.63 | 0.67 | 100% | 97% | 100% | 0% | 100% |
| 0.50 | laya | 0.05 | 0.75 | 0.10 | 0.11 | 100% | 43% | 6% | 82% | 1% |
| 0.50 | laya-en | 0.07 | 0.66 | 0.13 | 0.12 | 100% | 61% | 9% | 66% | 3% |
| 0.60 | jev-1.13.0 | 0.89 | 0.77 | 0.83 | 0.84 | 99% | 99% | 95% | 2% | 93% |
| 0.60 | kev-4b | 0.67 | 0.67 | 0.67 | 0.67 | 99% | 97% | 100% | 0% | 100% |
| 0.60 | laya | 0.06 | 0.70 | 0.10 | 0.12 | 90% | 42% | 9% | 89% | 1% |
| 0.60 | laya-en | 0.07 | 0.61 | 0.13 | 0.12 | 90% | 62% | 8% | 74% | 2% |
| 0.70 | jev-1.13.0 | 0.94 | 0.72 | 0.81 | 0.81 | 98% | 100% | 95% | 4% | 91% |
| 0.70 | kev-4b | 1.00 | 0.67 | 0.80 | 0.67 | 94% | 99% | 100% | 25% | 75% |
| 0.70 | laya | 0.06 | 0.65 | 0.11 | 0.12 | 78% | 42% | 14% | 93% | 1% |
| 0.70 | laya-en | 0.08 | 0.50 | 0.14 | 0.13 | 77% | 66% | 10% | 79% | 2% |
| 0.80 | jev-1.13.0 | 0.96 | 0.60 | 0.74 | 0.72 | 97% | 100% | 95% | 8% | 87% |
| 0.80 | kev-4b | 1.00 | 0.67 | 0.80 | 0.67 | 93% | 99% | 100% | 75% | 25% |
| 0.80 | laya | 0.06 | 0.54 | 0.11 | 0.12 | 65% | 42% | 25% | 96% | 1% |
| 0.80 | laya-en | 0.09 | 0.34 | 0.14 | 0.12 | 57% | 71% | 0% | 87% | 0% |
| 0.85 | jev-1.13.0 | 0.98 | 0.56 | 0.72 | 0.70 | 96% | 100% | 95% | 9% | 86% |
| 0.85 | kev-4b | 1.00 | 0.56 | 0.71 | 0.56 | 90% | 99% | – | 100% | 0% |
| 0.85 | laya | 0.06 | 0.45 | 0.10 | 0.11 | 57% | 42% | 0% | 98% | 0% |
| 0.85 | laya-en | 0.09 | 0.22 | 0.12 | 0.10 | 43% | 75% | 0% | 90% | 0% |
| 0.90 | jev-1.13.0 | 0.99 | 0.44 | 0.61 | 0.56 | 94% | 100% | 95% | 14% | 82% |
| 0.90 | kev-4b | 1.00 | 0.44 | 0.62 | 0.44 | 87% | 100% | – | 100% | 0% |
| 0.90 | laya | 0.06 | 0.40 | 0.11 | 0.11 | 46% | 42% | – | 100% | 0% |
| 0.90 | laya-en | 0.07 | 0.10 | 0.09 | 0.06 | 26% | 78% | 0% | 94% | 0% |
| 0.95 | jev-1.13.0 | 1.00 | 0.29 | 0.45 | 0.38 | 92% | 100% | 95% | 17% | 79% |
| 0.95 | kev-4b | 1.00 | 0.22 | 0.36 | 0.22 | 75% | 100% | – | 100% | 0% |
| 0.95 | laya | 0.06 | 0.30 | 0.10 | 0.10 | 34% | 42% | – | 100% | 0% |
| 0.95 | laya-en | 0.10 | 0.06 | 0.07 | 0.04 | 10% | 78% | 0% | 96% | 0% |

The baselines decide every cell, so they do not change with `acceptAt`.

### Per axis

Each cell is micro F1 / coverage / principal top-1 all, at 0.85. Kev is left out (4 documents). `rank` prints
the full tables, with every metric per axis value and a row per baseline.

| language | n | jev | laya | laya-en | keyword |
| --- | --- | --- | --- | --- | --- |
| de | 13 | 0.62 / 95% / 69% | 0.11 / 57% / 0% | 0.10 / 35% / 0% | 0.58 / 100% / 31% |
| en | 15 | 0.76 / 96% / 93% | 0.11 / 45% / 0% | 0.08 / 50% / 0% | 0.76 / 100% / 47% |
| es | 13 | 0.79 / 95% / 100% | 0.11 / 58% / 0% | 0.15 / 47% / 0% | 0.67 / 100% / 31% |
| fr | 13 | 0.81 / 98% / 92% | 0.10 / 52% / 0% | 0.18 / 27% / 0% | 0.75 / 100% / 54% |
| it | 13 | 0.63 / 96% / 77% | 0.08 / 69% / 0% | 0.11 / 52% / 0% | 0.80 / 100% / 46% |
| mixed | 7 | 0.71 / 96% / 100% | 0.10 / 57% / 0% | 0.19 / 33% / 0% | 0.73 / 100% / 29% |
| nl | 13 | 0.70 / 94% / 77% | 0.09 / 59% / 0% | 0.15 / 46% / 0% | 0.57 / 100% / 46% |
| pt | 13 | 0.67 / 96% / 85% | 0.11 / 63% / 0% | 0.09 / 45% / 0% | 0.76 / 100% / 46% |

| type | n | jev | laya | laya-en | keyword |
| --- | --- | --- | --- | --- | --- |
| ER triage | 10 | 0.72 / 96% / 90% | 0.11 / 58% / 0% | 0.08 / 47% / 0% | 0.61 / 100% / 50% |
| GP note | 10 | 0.76 / 95% / 100% | 0.09 / 71% / 0% | 0.13 / 55% / 0% | 0.62 / 100% / 50% |
| discharge summary | 10 | 0.74 / 95% / 100% | 0.11 / 53% / 0% | 0.14 / 40% / 0% | 0.64 / 100% / 40% |
| insurance claim note | 10 | 0.64 / 97% / 80% | 0.10 / 54% / 0% | 0.17 / 38% / 0% | 0.80 / 100% / 50% |
| lab report | 10 | 0.65 / 96% / 70% | 0.12 / 55% / 0% | 0.08 / 48% / 0% | 0.70 / 100% / 20% |
| nursing note | 10 | 0.84 / 96% / 100% | 0.13 / 49% / 0% | 0.14 / 54% / 0% | 0.75 / 100% / 10% |
| pathology report | 10 | 0.55 / 95% / 80% | 0.10 / 69% / 0% | 0.13 / 33% / 0% | 0.68 / 100% / 30% |
| patient message | 10 | 0.64 / 95% / 90% | 0.05 / 37% / 0% | 0.00 / 38% / 0% | 0.73 / 100% / 70% |
| radiology report | 10 | 0.81 / 96% / 60% | 0.10 / 66% / 0% | 0.18 / 38% / 0% | 0.81 / 100% / 50% |
| referral letter | 10 | 0.69 / 95% / 90% | 0.07 / 57% / 0% | 0.18 / 36% / 0% | 0.68 / 100% / 50% |

| completeness | n | jev | laya | laya-en | keyword |
| --- | --- | --- | --- | --- | --- |
| full | 40 | 0.72 / 95% / 95% | 0.11 / 56% / 0% | 0.16 / 38% / 0% | 0.66 / 100% / 38% |
| terse | 25 | 0.80 / 97% / 96% | 0.11 / 53% / 0% | 0.15 / 49% / 0% | 0.82 / 100% / 60% |
| truncated | 15 | 0.83 / 95% / 100% | 0.10 / 60% / 0% | 0.13 / 45% / 0% | 0.73 / 100% / 40% |
| no-diagnosis | 20 | 0.35 / 96% / 45% | 0.07 / 60% / 0% | 0.00 / 41% / 0% | 0.58 / 100% / 30% |

| provenance | n | jev | laya | laya-en | keyword |
| --- | --- | --- | --- | --- | --- |
| clinician | 53 | 0.73 / 96% / 85% | 0.10 / 53% / 0% | 0.12 / 43% / 0% | 0.69 / 100% / 38% |
| dictation | 10 | 0.69 / 95% / 90% | 0.09 / 90% / 0% | 0.12 / 58% / 0% | 0.64 / 100% / 50% |
| machine-translated | 12 | 0.72 / 96% / 75% | 0.12 / 53% / 0% | 0.15 / 33% / 0% | 0.75 / 100% / 33% |
| ocr | 15 | 0.72 / 96% / 93% | 0.11 / 67% / 0% | 0.13 / 41% / 0% | 0.69 / 100% / 40% |
| patient | 10 | 0.64 / 95% / 90% | 0.05 / 37% / 0% | 0.00 / 38% / 0% | 0.73 / 100% / 70% |

| hard | n | jev | laya | laya-en | keyword |
| --- | --- | --- | --- | --- | --- |
| none | 80 | 0.71 / 96% / 85% | 0.10 / 58% / 0% | 0.11 / 43% / 0% | 0.73 / 100% / 43% |
| negation | 5 | 0.78 / 95% / 80% | 0.10 / 62% / 0% | 0.18 / 41% / 0% | 0.71 / 100% / 20% |
| history | 4 | 0.71 / 95% / 100% | 0.12 / 64% / 0% | 0.09 / 53% / 0% | 0.62 / 100% / 25% |
| family | 3 | 0.77 / 93% / 100% | 0.12 / 83% / 0% | 0.22 / 61% / 0% | 0.42 / 100% / 0% |
| suspected | 3 | 0.67 / 95% / 67% | 0.00 / 36% / 0% | 0.14 / 53% / 0% | 0.50 / 100% / 67% |
| no-code | 5 | – / 100% / 100% | 0.00 / 36% / 0% | 0.00 / 15% / 0% | 0.00 / 100% / 80% |

On the no-code documents there is no gold cell, so micro F1 is 0.00 for a run with a TRUE cell and "–" for a run
with none.

| fits | run | n | micro P | micro R | micro F1 | coverage | accuracy decided | AUC |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| true | laya-en | 38 | 0.10 | 0.10 | 0.10 | 51% | 92% | 0.74 |
| false | laya-en | 62 | 0.08 | 0.27 | 0.13 | 38% | 61% | 0.63 |

### Trap codes

A trap cell is a code that the keyword baseline marks TRUE on a hard document but that is not in the gold labels:
a negated, past, family or suspected condition. The 20 hard documents have 28 trap cells. The keyword baseline
is wrong on all 28. From `facts.md` (a script over the logs and the fixture, zero model calls), at 0.85:

| hard kind | documents | trap cells | jev TRUE / UNKNOWN / FALSE | laya | laya-en |
|---|---|---|---|---|---|
| negation | 5 | 7 | 0 / 1 / 6 | 4 / 3 / 0 | 1 / 4 / 2 |
| history | 4 | 7 | 0 / 0 / 7 | 5 / 2 / 0 | 2 / 4 / 1 |
| family | 3 | 7 | 0 / 2 / 5 | 6 / 1 / 0 | 2 / 3 / 2 |
| suspected | 3 | 6 | 0 / 2 / 4 | 1 / 5 / 0 | 2 / 3 / 1 |
| no-code | 5 | 1 | 0 / 0 / 1 | 0 / 1 / 0 | 0 / 1 / 0 |
| all | 20 | 28 | 0 / 5 / 23 | 16 / 12 / 0 | 7 / 15 / 6 |

On the 5 no-code documents, Jev marks no code TRUE and chooses `none of these` on all 5. Laya multilingual marks
31 codes TRUE there and Laya English 6. Neither Laya checkpoint chooses `none of these` at 0.85 on any of them.

### What UNKNOWN saves

Between `acceptAt` 0.5 and 0.85, a cell with p > 0.5 either stays TRUE or becomes UNKNOWN:

| judge | documents | wrong TRUE at 0.5 | UNKNOWN at 0.85 | still TRUE at 0.85 | right TRUE at 0.5 | lost to UNKNOWN at 0.85 |
|---|---|---|---|---|---|---|
| jev-1.13.0 | 100 | 31 | 28 | 3 | 180 | 57 |
| kev-4b | 4 | 4 | 4 | 0 | 6 | 1 |
| laya | 100 | 2870 | 1198 | 1672 | 164 | 65 |
| laya-en | 100 | 1934 | 1435 | 499 | 144 | 97 |

### Findings

1. **Only Jev beats the keyword baseline, and at 0.85 only on precision.** At 0.85, Jev has micro F1 0.72 against
   0.70 for `keyword`, with precision 0.98 against 0.67 and recall 0.56 against 0.73. At 0.5 it beats `keyword`
   on all three (0.85 / 0.83 / 0.84). Its micro F1 is highest at 0.5 and falls at each step of the sweep, while its
   precision goes from 0.85 to 1.00. For Jev, the library default is a precision setting.
2. **For Jev, UNKNOWN turns wrong codes into UNKNOWN, at a cost in right ones.** From 0.5 to 0.85, 28 of its 31
   wrong TRUEs become UNKNOWN, and 57 of its 180 right TRUEs do too. None of the 3 wrong TRUEs left at 0.85 is on
   a hard document. In each, the text points to the code but the gold labels leave it out: K35 (appendicitis) on
   `icd-025`, a no-diagnosis triage note whose gold codes are the symptoms; R51 (headache) on `icd-027`, a known
   migraine; M54 (back pain) on `icd-079`, a vertebral fracture.
3. **Jev's lost recall is in the symptom and history codes.** At 0.85, Jev accepts 115 of 152 gold cells of the
   other codes, but 7 of 31 R cells and 1 of 35 Z cells. All gold codes of the 20 no-diagnosis documents are R or
   Z codes, and there Jev has its lowest micro F1 (0.35, `keyword` 0.58) and principal top-1 all (45%). The
   question asks for "a current diagnosis of this patient". A symptom, a past condition or a relative's condition
   does not match those words well. This run cannot tell whether the wording or the model causes the gap.
4. **On the traps, Jev says FALSE; UNKNOWN does little of the work.** The keyword baseline is wrong on all 28 trap
   cells. Jev marks none TRUE: 23 FALSE and 5 UNKNOWN. Only 1 of the 28 had p > 0.5 (R07 on `icd-013`, 0.56), so
   at 0.5 Jev would be wrong on 1. Laya English marks 7 TRUE, and UNKNOWN catches 9 more that had p > 0.5. Laya
   multilingual marks 16 TRUE and none FALSE; UNKNOWN catches 4 of its 20 with p > 0.5.
5. **Jev reads the principal diagnosis; Laya does not.** Jev's top option is right on 93 of 100 documents. At 0.85
   it is right on 95% of the documents where it decides and UNKNOWN on 9%, so principal top-1 all is 86%,
   against 42% for `keyword`. From 0.85 up, no decided Laya principal is right, and principal top-1 all is at
   most 3% (English, at 0.5) at any `acceptAt`. Laya multilingual's top option is `none of these` on 75
   documents. Laya English's top option is E78 on 35 and A09, the first option, on 27. This agrees with the
   option clipping: Laya sees 51 bare codes and no titles.
6. **Laya does not separate the gold codes from the others.** Its AUC is 0.60 (multilingual) and 0.67 (English),
   against 1.00 for Jev. Its micro precision stays between 0.05 and 0.10 at every `acceptAt`, below `most
   frequent (I10)` (0.16), and its micro F1 (0.10, 0.12) is the level of that baseline. At 0.85, UNKNOWN takes
   1198 and 1435 wrong TRUEs out, but 1672 and 499 stay.
7. **Jev shows no English advantage.** Its micro F1 on English documents is 0.76. The other languages go from 0.62
   (de) to 0.81 (fr), and principal top-1 all from 69% (de) to 100% (es, mixed). Jev beats `keyword` in de, es,
   fr and nl, ties it in en, and loses in it, pt and mixed. With 7 to 15 documents per language, each of these
   differences is a few cells. Laya English ranks English documents best (AUC 0.87, against 0.61 to 0.71 for
   the other languages), but its micro F1 there is 0.08. Laya multilingual shows no gap (AUC 0.54 to 0.66).
8. **Noise in the text does not lower Jev; lay words do a little.** On OCR, dictation and machine-translated
   documents, Jev's micro F1 is 0.72, 0.69 and 0.72, against 0.73 on clinician documents. Patient messages give
   0.64 (recall 0.47), where `keyword` reaches 0.73. Both Laya checkpoints are lowest there (0.05, 0.00). On
   dictation, Laya multilingual decides 90% of the cells with 10% accuracy.
9. **For Laya English, a cut request ranks worse, but the window is not what limits Laya.** On its 62 cut documents
   the AUC is 0.63 and accuracy decided 61%. On the 38 that fit, 0.74 and 92%. The multilingual checkpoint
   cut no document and still has micro F1 0.10. The split is not random: the long documents are the cut ones.
10. **Kev cannot run this bench under the memory guard.** It needs more than the 32 GiB MPS limit for one
    52-question request. On its 4 documents it has precision 1.00 and AUC 0.98, and its principal is right on all
    4 at 0.5 but UNKNOWN on all 4 from 0.85 up. That is too few documents for a finding.

### What this does and does not establish

- **Synthetic data.** The documents are synthetic. No real patient data is used, and the bench makes no claim of
  clinical validity.
- **Gold labels.** The gold labels come from the writing process. One blind relabel pass checked them, with 0
  disagreements of 100. The relabeller is of the same model family as the writers, so the agreement shows that
  the texts are unambiguous under the conventions. It is not an independent expert validation, and no certified
  coder checked the fixture.
- **Coding conventions.** The gold labels do not code a suspected diagnosis: they code its symptoms. A past or a
  relative's condition goes to a Z code. Another convention changes the gold labels and the scores. Finding 2
  shows cells where a reader can disagree with them.
- **One wording.** This is one question wording in English for every document, and one run per judge. Finding 3
  shows that the wording can matter.
- **p is not comparable across judges.** p, confidence and AUC come from different models. Compare the decided
  `Truth` at one `acceptAt`, not the probabilities.
- **Laya context window.** Laya English cuts 62 of 100 documents. On both checkpoints, the `principal` options are
  clipped to 4 tokens. The Laya principal scores measure a choice among bare codes.
- **Axis confounds.** All 5 no-code documents are French, and the suspected documents are in 2 languages only
  (es, nl). The hard kinds have 3 to 5 documents each. The per-language tables mix these effects.
- **macro F1** averages only the codes with at least one gold cell in the documents of the run. For Kev, that is
  a few codes of 4 documents.
- **acceptAt 0.5.** `Policy` needs an `acceptAt` above 0.5, so `rank` uses the next double above 0.5. Every `feels`
  cell with p ≠ 0.5 is then decided, but the principal `choose` is still UNKNOWN when its top p is 0.5 or less.
  That is why Laya's principal unknown is 66% to 82% at 0.5.
- **The keyword baseline** uses synonyms that the operator wrote before the fixture documents. The writers never
  saw them: they got the codes and the titles only. The synonyms were not tuned after the runs.
- **Memory incident.** During the first Laya run, the local judges filled the RAM of the Mac and it had to be
  reset. Since then, `scripts/kev.sh` and `scripts/laya.sh` run the server under a memory guard with a cap per
  judge; see "Local judges" in [`AGENTS.md`](../AGENTS.md#local-judges). The Kev stop in these results is that
  cap at work.
