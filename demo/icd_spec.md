# ICD-10-CM coding bench: implementation spec

## Problem Statement

The demos show `check` on terms of service (promises) and `feels`/`choose`/`score` on a game with exact truth
(Kalah). No demo shows Kleene on a real classification task: a text in, one or more labels out, with UNKNOWN
where the evidence is thin. Medical coding is the canonical case. A clinical text gets one principal diagnosis
and zero or more secondary diagnoses from ICD-10. Real texts differ in language, in document type, in how
complete they are and in who wrote them. A developer who wants to know whether a Judge can code such texts,
and how UNKNOWN helps, has no fixture, no gold labels and no numbers.

## Solution

A new demo, the ICD bench, in the `demo` module. It follows the Kalah bench pattern.

- A committed **label set** of about 50 ICD-10-CM 3-character categories with their official English titles
  (CMS/CDC, public domain). Each code also carries multilingual demo synonyms, written for this demo and
  marked as not official.
- A committed **fixture** of 100 synthetic multilingual clinical documents. Each document has gold labels:
  one principal code (or none), 0–4 codes in total, and the full ICD-10-CM subcode next to each category.
  The fixture is stratified over language, document type, completeness and provenance, and about 20% of it
  is hard cases.
- `log` asks each Judge once per document and stores the `Evidence` as JSONL. `rank` reapplies a `Policy`
  at any `acceptAt` with zero model calls and prints the scores, per axis and against no-model baselines.
- A report, `demo/icd.md`, holds the results for jev-1.13.0, kev-4b, Laya multilingual and Laya English.

## User Stories

1. As a Kleene user, I want a demo that codes clinical text into ICD-10-CM, so that I can see how Kleene
   fits a multi-label classification task.
2. As a Kleene user, I want each document classified in one `ask`, so that I can see the one-ask-one-call
   rule hold for a classification with about 50 labels.
3. As a Kleene user, I want one `feels` question per code, so that each code is TRUE, FALSE or UNKNOWN on
   its own.
4. As a Kleene user, I want one `choose` question for the principal diagnosis in the same `ask`, so that I
   see `feels` and `choose` work together.
5. As a Kleene user, I want the principal `choose` to have a "none of these" option, so that a document
   with no codable diagnosis has a correct answer.
6. As a Kleene user, I want the stored `Evidence` to be reapplied at many `acceptAt` values with zero model
   calls, so that I can see the trade-off between coverage and accuracy without paying for new runs.
7. As a Kleene user, I want UNKNOWN counted as an abstention and not as an error, so that the report
   shows where UNKNOWN saves a wrong code.
8. As a demo reader, I want the label set to include confusable sibling codes (E10/E11, I20/I21, J44/J45,
   N17/N18, F32/F33 and more), so that the scores measure discrimination and not topic spotting.
9. As a demo reader, I want the label set to include history, family-history and symptom codes (for
   example Z87, Z80, R07, R50), so that the fixture can test the meaning of a mention and not only its
   presence.
10. As a demo reader, I want documents in EN, DE, ES, FR, IT, PT and NL plus some code-switched ones, so
    that I can see how each Judge handles languages other than English.
11. As a demo reader, I want documents of about ten types (discharge summary, GP note, ER triage,
    radiology report, pathology report, referral letter, lab report, nursing note, patient message,
    insurance claim note), so that the bench is not tuned to one genre.
12. As a demo reader, I want documents that are full, terse, truncated or without a stated diagnosis, so
    that I can see how completeness changes the scores.
13. As a demo reader, I want documents written by a clinician, dictated (with ASR errors), scanned (with
    OCR noise), self-reported by a patient in lay words, or machine-translated, so that I can see how
    provenance changes the scores.
14. As a demo reader, I want negated findings ("pneumonia ruled out"), so that I can see whether a Judge
    codes a condition that the text excludes.
15. As a demo reader, I want history-of and family-history mentions, so that I can see whether a Judge
    tells a past or a relative's condition from a current one.
16. As a demo reader, I want suspected ("rule out") diagnoses, so that I can see whether a Judge codes an
    unconfirmed condition.
17. As a demo reader, I want 5 documents with no gold code, so that I can see whether a Judge invents a
    code with no evidence.
18. As a demo reader, I want each document tagged on every axis, so that every score can be broken down
    by language, type, completeness, provenance and hard-case kind.
19. As a demo reader, I want the Judge to see only the document text and not the tags, so that the tags
    do not leak the answer.
20. As a demo reader, I want the question text in English for every document, so that the question ids
    stay the same across documents and the cross-lingual gap is a finding.
21. As a demo reader, I want the official ICD-10-CM English titles in the questions, so that the labels
    are the real ones and their source is cited.
22. As a demo reader, I want the gold labels to keep the full subcode, so that a finer evaluation is
    possible later without rewriting the fixture.
23. As a demo reader, I want the gold labels checked by a blind relabel pass, so that I can trust the
    gold set more than "the writer said so".
24. As a demo reader, I want an always-empty baseline, so that I can see what a Judge earns over saying
    nothing.
25. As a demo reader, I want a most-frequent-code baseline, so that I can see what a Judge earns over
    the class prior.
26. As a demo reader, I want a keyword baseline built on the multilingual synonyms, so that I have the
    bar a Judge must clear to be worth a model call.
27. As a demo reader, I want the keyword synonyms written before the fixture and not tuned against it
    afterwards, so that the baseline does not overfit.
28. As a demo reader, I want micro and macro precision, recall and F1 on the codes at each `acceptAt`, so
    that I can compare Judges on decided answers.
29. As a demo reader, I want coverage (the share of decided cells) and accuracy on decided cells, so that
    I can see what abstention buys.
30. As a demo reader, I want AUC per Judge on the `feels` cells, so that I can compare how each Judge
    ranks TRUE over FALSE with no threshold.
31. As a demo reader, I want top-1 accuracy and the abstain rate for the principal diagnosis, so that I
    can judge the `choose` on its own.
32. As a demo reader, I want an `acceptAt` sweep from 0.5 to 0.95, so that I see how the scores change
    with the threshold.
33. As a demo reader, I want the report in the Kalah style (setup, leaderboard, per-axis tables, findings,
    caveats), so that the demos read alike.
34. As a demo reader, I want a caveat that p and confidence cannot be compared across Judges, so that I
    compare decided `Truth` values and not raw probabilities.
35. As a demo operator, I want `log` to resume by document id, so that a stopped run continues without
    asking again.
36. As a demo operator, I want `log` to stop on a Judge error (timeout, 5xx, `Malformed`), so that no
    error is turned into UNKNOWN.
37. As a demo operator, I want `log` to refuse a file written by another Judge, so that one file never
    mixes Judges.
38. As a demo operator, I want `log` to refuse a file logged against another fixture or label set
    version, so that one file never mixes benchmarks.
39. As a demo operator, I want the file name to be the leaderboard label, so that the two Laya
    checkpoints, which report the same judge id, get separate rows.
40. As a demo operator, I want the Judge chosen by the three existing environment variables, so that I
    run the bench exactly as I run the other demos.
41. As a demo operator, I want `log` to record whether the request fitted the Judge's context window, so
    that a Laya result on a cut request is not read as a real answer.
42. As a maintainer, I want the fixture validated by tests (100 documents, the stratification counts,
    every gold code in the label set, exactly one principal per non-empty document, the length range),
    so that a bad edit to the fixture fails the build.
43. As a maintainer, I want the metrics tested against hand-computed examples, so that the numbers in the
    report are right.
44. As a maintainer, I want the demo in its own package and not in `kleene`, so that the library keeps its
    two runtime dependencies (ADR-0005).
45. As a maintainer, I want the label set source and licence stated next to the data, so that the
    committed titles are clearly public domain and the synonyms clearly our own.

## Implementation Decisions

- **Module and package.** The bench lives in the `demo` module in a new package `kleene.demo.icd`. It has
  three parts, as in Kalah: fixture loading and validation, the bench (questions, `log`, scoring,
  `leaderboard`), and a CLI entry point. There is no HTML page. The library module does not change.
- **Label set.** It has about 50 ICD-10-CM 3-character categories from at least 12 chapters, with at least
  8 confusable pairs, common Z-codes and R-codes. Each entry holds the code, the official English
  ICD-10-CM title, and `synonyms` per language (en, de, es, fr, it, pt, nl). The synonyms are written for
  this demo and marked "demo synonyms, not official titles". No WHO, BfArM, ATIH or Spanish ministry text
  is committed. The label set is a committed JSON resource with a source and licence note (ICD-10-CM,
  CMS/CDC, public domain).
- **Review gate.** The user reviews the proposed label set before any document is written.
- **Fixture.** It is one committed JSONL resource with 100 records. Each record holds the following fields:
  - `id`
  - `text`
  - `language` (one of the 7 languages, or `mixed`)
  - `type` (one of 10 document types)
  - `completeness`: `full`, `terse`, `truncated` or `no-diagnosis`
  - `provenance`: `clinician`, `dictation`, `ocr`, `patient` or `machine-translated`
  - `hard`: none, `negation`, `history`, `family`, `suspected` or `no-code`
  - `principal`: a category or null
  - `codes`: a list of `{category, subcode}`

  Rules:
  - 0–4 codes per document, about 2 on average.
  - The principal category is in `codes`.
  - 5 documents have empty `codes` and a null principal.
  - Text length is 30–350 words.
  - Spread: about 15 EN and 12–13 per other language; each type, completeness level and provenance appears
    in several languages.
- **Fixture authoring.** Sub-agents write the documents from their target codes, so the gold labels are
  known by construction. Then one independent sub-agent relabels every document blind, from the text and
  the label set only. Each disagreement is fixed in the gold set or in the text. The fixture is static
  after that. No `Writer` is used at run time.
- **State.** The Judge sees one JSON `State` per document holding only the document text. The axis tags
  and the gold labels are never sent.
- **Questions.** Each document gets one `ask` with a fixed question set, bound to one `Kleene`:
  - one `feels` per code in the label set, in English, of the form "The document supports <code> <title> as
    a current diagnosis of this patient";
  - one `choose` named for the principal diagnosis, with one option per code (label `<code> <title>`) and a
    `none of these` option.

  The question names are fixed, so the wire ids are the same for every document.
- **Log record.** One JSON line per document holds the following fields:
  - the document id
  - the Judge id and model
  - the raw `feels` p per code, exactly as received
  - the raw `choose` distribution and confidence, exactly as received
  - a fixture version hash (fixture plus label set)
  - a `fits` flag: whether the longest request fitted the Judge's context window (see Further Notes)

  The file name is the label in the leaderboard.
- **`log` behaviour.** It is the same as Kalah:
  - It resumes by document id.
  - It refuses a file from another Judge id or another fixture version.
  - It never catches a Judge error, so an error stops the run and the next run resumes.
  - The Judge comes from `KLEENE_BASE_URL`, `KLEENE_MODEL` and `KLEENE_API_KEY` through `SystemOneJudge`.
- **Scoring.** Scoring works on the decided `Truth` from `Evidence.decide(policy)` for each `acceptAt`, and
  on raw p only for AUC.
  - A `feels` cell is TP, FP, FN or TN on decided values; UNKNOWN is counted apart as abstained.
  - Micro and macro precision, recall and F1 treat UNKNOWN as not predicted.
  - Coverage is decided cells over all cells, and accuracy is taken on decided cells.
  - The principal diagnosis gets top-1 accuracy on decided answers, the abstain rate, and top-1 on all
    documents with abstain counted as wrong.
  - Every metric is also given per axis value.
  - The `acceptAt` grid is 0.5, 0.6, 0.7, 0.8, 0.85, 0.9 and 0.95.
- **Baselines.** They play no Judge and fill only the columns they can:
  - always-empty (all FALSE, principal `none`);
  - most-frequent (the most frequent gold category as the principal and the only code);
  - keyword (a code is TRUE if any of its synonyms, in any language, occurs as a case-insensitive whole
    word or phrase; the principal is the first matched code in label-set order, else `none`).

  The keyword baseline has no negation handling. That is on purpose.
- **CLI.**
  - `log --out <file>` runs the configured Judge over the fixture.
  - `rank <files...> [--accept-at list]` prints the leaderboard and the per-axis tables as Markdown.
- **Report.** `demo/icd.md` holds:
  - the setup
  - the label set source
  - the fixture composition
  - the run commands
  - the leaderboard at 0.85 and the sweep
  - the per-axis tables
  - numbered findings
  - caveats: synthetic data; gold labels built from the writing process; p not comparable across
    Judges; the Laya context window
- **Docs.** No ADR (ADR-0005 covers demos). `CONTEXT.md` gets terms such as "fixture", "gold" and "label
  set" only if the report uses them often. The demo README links the new bench.

## Testing Decisions

- A good test drives the public functions of `kleene.demo.icd` with a `ScriptedJudge` and asserts the
  output: the requests sent, the records written and the tables printed. It does not assert private
  helpers or exact formatting beyond the numbers.
- **Seams.** There are two, both existing kinds:
  1. the `Judge` SPI, through `ScriptedJudge`, for `log`;
  2. the pure scoring function, which takes the parsed records and the fixture and returns the
     leaderboard, for `rank`.

  No new seam goes into the library.
- **Fixture tests** run on the committed resources and check:
  - 100 records
  - unique ids
  - every gold category in the label set
  - exactly one principal, and it is in `codes`, for each non-empty document
  - 5 empty documents
  - 30–350 words
  - the stratification minimums per axis
  - no axis tag or gold code text in the `State`
- **Label set tests** check:
  - unique codes
  - at least 12 chapters
  - the listed confusable pairs are present
  - every code has a title and synonyms in all 7 languages
- **`log` tests** check:
  - one `Judge.evaluate` per document
  - the fixed question set, with fixed wire ids across documents
  - probabilities stored as received
  - resume by id
  - refusal of another Judge's file or another fixture version
  - a Judge error propagates and nothing is caught
- **Scoring tests** use small hand-computed examples:
  - TP, FP, FN and TN, and UNKNOWN counted apart
  - micro and macro F1
  - coverage and accuracy on decided cells
  - principal top-1 and abstain rate, `none` included
  - AUC on a known ranking
  - per-axis grouping
  - each baseline on a small document set, including the keyword baseline missing a negation
- **Prior art.** The Kalah bench tests in the demo module cover determinism, the State, `log` with
  `ScriptedJudge`, resume, the refusals and the leaderboard baselines. They also have the Laya
  token-budget test pattern.

## Out of Scope

- An HTML page for the ICD bench.
- Full-subcode scoring (the gold data keeps subcodes, but v1 scores 3-character categories).
- Codes outside the label set, hierarchical or two-stage classification, and the full ICD-10-CM tree.
- Official non-English ICD titles (ICD-10-GM, CIE-10-ES, CIM-10) and WHO ICD-10 data.
- A run-time `Writer` that generates documents.
- Real patient data, or any claim of clinical validity.
- Tuning prompts or synonyms after seeing the Judge results.
- Changes to the `kleene` library or its spec.

## Further Notes

- **Laya context window (decided: keep the 350-word cap, count the tokens, flag the cut requests).**
  ADR-0006 says the state, the instructions and the options share
  1024 tokens on the Laya multilingual checkpoint and 512 on the English one, and laya-mlx cuts a request
  that is too long. A 350-word document is about 450–700 tokens depending on the language. The principal
  `choose` with about 51 options is several hundred tokens more. So Laya English will cut most documents and
  most `choose` requests, and Laya multilingual will cut the longer ones. Before the Laya runs, count the
  tokens with each checkpoint's tokenizer (as the Kalah report did) and store the `fits` flag. The report
  then shows each Laya row split into requests that fit and requests that were cut. The other Judges have no
  such limit, and their `fits` flag is always true.
- The gold labels come from the writing process and were checked by one blind relabel pass, not by a
  certified coder. The report says so.
- The Jev run needs `KLEENE_API_KEY`. If it is not set, ask the user before the Jev run.
- The fixture version hash covers the fixture and the label set, so editing either one makes old logs
  refuse to resume and not mix.
