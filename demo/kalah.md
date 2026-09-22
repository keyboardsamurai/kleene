# Kalah bench

A demo that scores judges against each other on a game with exact truth. Each position of Kalah(6,4) gets one
`ask` with 14 fixed questions. An engine gives the truth for every question, so no hand labels are necessary.
`log` stores the `Evidence` as JSONL. `rank` and `html` then reapply a `Policy` at any `acceptAt` with zero
model calls.

Code: `kleene.demo.kalah` (`Kalah.kt` is the engine, `Bench.kt` the positions, questions, `log` and
`leaderboard`, `Html.kt` plus `board.html` the page, `Main.kt` the CLI).

## Rules

Kalah(6,4): each side has 6 pits and a store, and each pit starts with 4 seeds.

- You pick one of your pits. An empty pit cannot be sown.
- You sow its seeds one per pit, counter-clockwise: your higher pits, your store, the opponent pits from the one
  opposite your pit 6 to the one opposite your pit 1, then your pit 1 again. The opponent store is skipped.
- If the last seed lands in your store, you move again.
- If the last seed lands in an empty pit of yours and the opposite pit has seeds, you capture: that seed and the
  opposite seeds go to your store.
- When all 6 pits of one side are empty, the game ends. Each side adds the seeds left in its pits to its own store.

One edge case: a sowing that ends in your store and also ends the game counts as "another turn" for `againN`,
although nobody moves after it.

## State

The judge sees one JSON `State` per position. The rules text (`RULES` in `Bench.kt`, about 170 tokens) is in the
state once, not in each question:

```json
{
  "rules": "Kalah. You and the opponent each have 6 pits and a store. ...",
  "you": { "pits": [4, 4, 0, 5, 5, 5], "store": 1 },
  "opponent": { "pits_opposite_yours": [4, 4, 4, 4, 4, 4], "store": 0 }
}
```

`pits_opposite_yours[i]` is the opponent pit opposite your pit `i + 1`. The board is always seen from the side to
move.

### Hints

`log --hints` adds the key `if_sown` to the state and one sentence to the rules text (`IF_SOWN_RULE` in
`Bench.kt`). `if_sown` has 6 strings in pit order. Each gives the facts of sowing that pit now: the seeds into your
store (capture and end sweep included), the capture, the extra turn, the game end, and else the most seeds the
opponent can then put into their store with one sowing. Seed-1 position 0:

```json
"if_sown": [
  "pit 1: empty, cannot be sown",
  "pit 2: 3 seeds into your store, including a capture of 2 seeds; no extra turn; then the opponent can gain at most 1",
  "pit 3: 0 seeds into your store; no extra turn; then the opponent can gain at most 1",
  "pit 4: 1 seed into your store; no extra turn; then the opponent can gain at most 1",
  "pit 5: empty, cannot be sown",
  "pit 6: empty, cannot be sown"
]
```

These are one-ply facts plus the opponent's one-ply reply. Nothing comes from the search. With them, the judge
reads and weighs stated facts to choose a move; it does not simulate a sowing. `againN` and `takesN` can be read
directly. The questions, their wire ids and the engine answers do not change, and without `--hints` the state is
the one above, so earlier runs stay reproducible. The largest hinted state of the 200 seed-1 positions is 1552
characters, 429 tokens with the ModernBERT tokenizer of Laya's English checkpoint; the longest request with its
instructions and options is 472 of the 512 tokens that checkpoint reads, so nothing is cut.

## Questions

14 questions per position, in one `ask`. The labels never change, so the wire ids are the same for every position.

| name | kind | instructions | truth from the engine |
|---|---|---|---|
| `move` | choose, `pit 1` .. `pit 6` | Which pit should you sow to end the game with the most seeds? | the pits tied for the best value |
| `again1` .. `again6` | feels | Sowing pit N gives you another turn | the last seed lands in your store |
| `takes1` .. `takes6` | feels | Sowing pit N captures seeds | the sowing captures |
| `lead` | score, `clearly behind` .. `clearly ahead` | How far ahead are you with best play? | the bucket of the best value |

An empty pit stays in the `move` options. It is a trap: accepting it counts as illegal. For an empty pit, `againN`
and `takesN` are FALSE. The `lead` buckets are, in seeds: `<=-6`, `-5..-2`, `-1..1`, `2..5`, `>=6`.

## Positions

`positions(seed = 1, count = 200)` is deterministic. It plays 0 to 40 random plies from the start and starts again
when a game ends. It keeps a position only if it has at least 2 legal pits and at least 2 seeds between the best
and the worst pit. It drops duplicates. A position id is its index, so every judge sees the same 200 boards.

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

or a local Kev (`scripts/kev.sh` starts one on `:8009`):

```sh
export KLEENE_BASE_URL=http://127.0.0.1:8009
export KLEENE_MODEL=kev-4b
```

or a local Laya (`scripts/laya.sh` starts one on `:8010`, Apple Silicon only). The default checkpoint is
multilingual; `LAYA_MODEL=aac6fef/laya-mlx scripts/laya.sh` starts the English one:

```sh
export KLEENE_BASE_URL=http://127.0.0.1:8010
export KLEENE_MODEL=laya-mlx
```

Then, from `demo/`, log one file per judge. The file name is the label in the leaderboard, because both Laya
checkpoints report the same judge id:

```sh
mvn -q exec:java -Dexec.mainClass=kleene.demo.kalah.MainKt -Dexec.args="log --out out/kalah/jev.jsonl"
mvn -q exec:java -Dexec.mainClass=kleene.demo.kalah.MainKt -Dexec.args="log --hints --out out/kalah/jev-hints.jsonl"
```

Score and draw every run side by side:

```sh
mvn -q exec:java -Dexec.mainClass=kleene.demo.kalah.MainKt -Dexec.args="rank out/kalah/jev.jsonl out/kalah/kev-4b.jsonl"
mvn -q exec:java -Dexec.mainClass=kleene.demo.kalah.MainKt -Dexec.args="html out/kalah/jev.jsonl out/kalah/kev-4b.jsonl --out out/kalah/kalah.html"
```

Options:

```text
log [--out out/kalah/kalah.jsonl] [--count 200] [--seed 1] [--timeout 60] [--hints]
rank <jsonl>... [--accept-at 0.95,0.85,0.75,0.60]
html <jsonl>... [--out out/kalah/kalah.html]
```

`log` computes the positions (a few seconds), then asks once per position and prints one line per position. A
rerun resumes: it skips position ids already in the file. It stops if a logged board is not the regenerated one
(one file never mixes two seeds), if a logged record has another judge id (one file never mixes two judges; the
two Laya checkpoints share one judge id, so give each its own file) or if it has the other `hints` flag (one file
never mixes two states). `log` never catches a judge error: a timeout, a 5xx or a `Malformed` response stops the
run, and you rerun it. `--timeout` is the per-attempt limit in seconds.

The page shows the leaderboard, a run selector and an `acceptAt` slider. Each card shows the opponent row on top,
opposite your pits, with the stores at the sides. Under each of your pits: a bar for p(pit), the engine value,
★ on a best pit, ✗ on an empty pit, and the `again` and `takes` verdicts (green right, red wrong, grey UNKNOWN).

## Metrics

`rank` prints two tables. The main table has one row per run, scored over its own records, and `n` is the number
of records. If two runs cover different positions, compare them with care.

- **best move**: the share of positions where the legal pit with the highest p is a best pit. Illegal pits are
  masked here, and the first pit wins a tie.
- **regret**: the mean seeds that this pit loses against the best pit.
- **illegal mass**: the mean p on empty pits.
- **lead MAE**: the mean absolute distance between the judge's expected `lead` level and the true bucket.
- **moves decided/wrong/illegal**: `move` reapplied at the `acceptAt`, with no masking. Decided is Accepted. Wrong
  is an accepted pit that is not a best pit, illegal ones included. Illegal is an accepted empty pit.
- **again/takes decided/wrong**: the 12 `againN` and `takesN` feels per position, reapplied at the `acceptAt`. Wrong
  is an accepted TRUE or FALSE that is not the engine answer. Most of these answers are FALSE, so compare with
  `always FALSE`.

The main table shows the decided counts at the library default `acceptAt` 0.85. The second table shows moves
decided/wrong/illegal and again/takes decided/wrong at each `--accept-at`. Thresholds apply only in `Evidence.decide`.

Six baseline rows play no judge and fill only their own columns. They cover every distinct board across the runs:

- `random`: the exact expectation of a pit picked uniformly from the legal pits.
- `greedy`: the legal pit with the highest immediate store gain (capture and end sweep included), the first on a tie.
- `gain minus threat`: the legal pit with the highest gain minus the opponent's best immediate gain after it (0 on
  an extra turn or at the game end), the first on a tie. A perfect reader of `if_sown` reaches it with this
  one-ply rule.
- `always even`: `lead` is always `even`.
- `stores only`: `lead` is the bucket of the current store difference.
- `always FALSE`: every `againN` and `takesN` is FALSE. It decides all 12 per position, and it is wrong on each TRUE one.

A judge that does not beat `random` on best move and regret does not read the board. A judge that does not beat
`greedy` does not look ahead. With `--hints`, a judge below `gain minus threat` does not weigh what it reads.

## The ceiling of the truth

The engine is negamax with alpha-beta at a fixed depth of 12 plies (`DEPTH` in `Kalah.kt`), and a sowing with an
extra turn counts as a ply. At the horizon it uses the store difference. The values are exact only when the game
ends inside the horizon. In an early midgame, "best" and `lead` are the engine's best estimate, not a proof. A
judge that plays deeper than 12 plies can be right and still count as wrong. `againN` and `takesN` are exact: they
need one sowing.

## Record format

One JSON line per position per run. Every probability is stored as received:

```jsonc
{
  "position": 0,
  "board": [4, 4, 0, 5, 5, 5, 1, 4, 4, 4, 4, 4, 4, 0],  // your pits 1..6, your store, their pits in sowing order, their store
  "judge": "api.typesafe.ai/jev-1.13.0",
  "model": "jev-1.13.0",
  "move": [0.05, 0.05, 0.7, 0.1, 0.05, 0.05],   // p(pit 1) .. p(pit 6)
  "again": [0.1, 0.1, 0.9, 0.1, 0.1, 0.1],      // p(true) of again1 .. again6
  "takes": [0.2, 0.2, 0.2, 0.2, 0.2, 0.2],      // p(true) of takes1 .. takes6
  "lead": [0.0, 0.1, 0.8, 0.1, 0.0],            // p per lead level
  "leadExpected": 2.0
}
```

A hinted run adds `"hints": true` to each line. A no-hint line does not have the field, so older files read the same.

## Results

200 positions (`--seed 1`), engine depth 12, logged on 2026-09-22 through four judges: cloud TypeSafe
(`jev-1.13.0`), a local Kev (`kev-4b`, bf16) and a local Laya (`laya-mlx` 0.2.0 via laya-server) on its
multilingual and English checkpoints. Kev and Laya ran on one Mac. Each run made 200 model calls, one per
position, with no error and no retry. `rank` and `html` after that made none, and two `rank` runs printed the same
output. At load, the English checkpoint warned that it clamps the temperature of its choice bucket for 11 or more
options. No question here has more than 6 options.

| | `jev-1.13.0` | `kev-4b` | Laya multilingual | Laya English |
|---|---|---|---|---|
| wall clock, 200 positions | about 57 seconds | about 44 minutes | about 25 seconds | about 105 seconds |

### Leaderboard

| run | n | best move | regret | illegal mass | lead MAE | moves decided/wrong/illegal at 0.85 | again/takes decided/wrong at 0.85 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| jev | 200 | 25% | 5.97 | 0.06 | 1.00 | 0/0/0 | 879/31 |
| kev-4b | 200 | 25% | 5.54 | 0.25 | 1.19 | 0/0/0 | 4/4 |
| laya-multilingual | 200 | 26% | 5.70 | 0.26 | 1.59 | 0/0/0 | 2196/2020 |
| laya-english | 200 | 27% | 5.53 | 0.25 | 1.50 | 22/19/5 | 1025/914 |
| baseline: random | 200 | 30% | 5.11 | 0.00 | – | – | – |
| baseline: greedy | 200 | 41% | 3.97 | 0.00 | – | – | – |
| baseline: always even | 200 | – | – | – | 1.39 | – | – |
| baseline: stores only | 200 | – | – | – | 1.03 | – | – |
| baseline: always FALSE | 200 | – | – | – | – | – | 2400/191 |

| acceptAt | jev moves | jev again/takes | kev-4b moves | kev-4b again/takes | laya-multilingual moves | laya-multilingual again/takes | laya-english moves | laya-english again/takes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0.95 | 0/0/0 | 251/0 | 0/0/0 | 0/0 | 0/0/0 | 408/376 | 8/5/0 | 47/42 |
| 0.85 | 0/0/0 | 879/31 | 0/0/0 | 4/4 | 0/0/0 | 2196/2020 | 22/19/5 | 1025/914 |
| 0.75 | 5/4/0 | 1324/98 | 0/0/0 | 286/268 | 0/0/0 | 2375/2186 | 45/38/10 | 1622/1388 |
| 0.60 | 48/35/0 | 1917/236 | 2/2/0 | 1661/1516 | 1/1/1 | 2397/2206 | 72/58/17 | 2110/1719 |

Three cells sit on a rounding tie: 27% is 53 of 200, 41% is 81 of 200 and 1.19 is 1.195. A separate Python
recount from the data in `kalah.html` gave the same counts, and a separate Python depth-12 search gave the same
engine values on all 200 boards.

### Findings

1. **No judge reads the board for the move.** Best move is 50 to 53 of 200 (25% to 27%) and regret is 5.53 to
   5.97 seeds for every judge. That is worse than `random` (30%, 5.11) and far from `greedy` (41%, 3.97). Per
   position, the rank correlation between p(pit) and the engine value of the legal pits is −0.07 to 0.01 on
   average. Each judge puts slightly less p on the best pit than on the mean legal pit.
2. **At 0.85, three judges abstain on every move; one is confidently wrong.** Jev, Kev and Laya multilingual
   decide 0 of 200 moves (mean top p 0.48, 0.33 and 0.32). Laya English decides 22 and 19 of them are wrong,
   5 of them an empty pit. At 0.60, Jev decides 48 moves and 35 are wrong.
3. **Only Jev sees the empty pits.** Its illegal mass is 0.06 and its top pit is never empty. Kev and both Laya
   checkpoints put 0.25 to 0.26 on empty pits, the same as the share of empty pits (0.253), and their top pit is
   empty in 52 to 54 of 200 positions.
4. **No judge knows the sowing rule for again/takes.** Only 191 of 2400 cells are TRUE, so `always FALSE` is
   right on 92.0%. On legal pits, every judge ranks TRUE above FALSE close to chance (AUC 0.41 to 0.57). At 0.85,
   Jev says FALSE on 879 cells, is right on 96.5% and accepts 0 of the 191 TRUE cells. 525 of its 879 FALSEs are
   on empty pits, where the rules give the answer.
5. **Kev abstains, Laya says TRUE.** At 0.85, Kev decides 4 of 2400 cells and all 4 are wrong. At 0.60 it decides
   1661 and 1516 are wrong. Laya multilingual has a mean p(true) of about 0.91 on TRUE and FALSE cells alike: it
   accepts 176 of the 191 TRUE cells but is wrong on 2020 of 2196. Laya English is wrong on 914 of 1025.
6. **For lead, Jev reads the stores, not the play.** Its lead MAE is 1.00, against 1.03 for `stores only` and
   1.39 for `always even`. Its expected level correlates 0.90 with the stores bucket and 0.51 with the engine
   bucket. Kev (1.19) beats `always even` but not `stores only`. Both Laya checkpoints lose to both baselines
   (1.59 and 1.50). Laya multilingual answers `behind` on all 200 positions.

### What this does and does not establish

This is 200 positions from one seed and one prompt wording: the `RULES` text and 14 fixed instructions. Another
wording or a state that spells out each sowing could change any row. `best` and `lead` come from a depth-12 engine,
exact only when the game ends inside the horizon. A deeper judge could be right and still count as wrong, but no
judge here beat `random`, so the horizon is not what limits these scores. `againN` and `takesN` are exact. Do not
compare the probabilities across judges: p, mean top p and AUC come from different models. The decided `Truth` at
one `acceptAt` is comparable. The run shows that these four judges, with this prompt, do not simulate a Kalah
sowing. It also shows the UNKNOWN band at work, and its limit: at 0.85, Jev, Kev and Laya multilingual left all 200
moves UNKNOWN, Laya English made 19 wrong moves, and on again/takes the band did not stop Laya multilingual from
2020 wrong TRUEs.

## Results with hints

The same 200 positions (`--seed 1`), engine depth 12, logged on 2026-09-22 with `log --hints` through the same four
judges. Each hinted file has the same boards as its no-hint file, position by position. Each run made 200 model
calls, one per position, with no error and no retry. `rank` and `html` after that made none, and two `rank` runs
printed the same output. laya-mlx 0.2.0 cuts a state that is too long and logs nothing, so the tokens were counted
with each checkpoint's own tokenizer. The largest state was 429 tokens on the English checkpoint and 461 on the
multilingual one. The tightest English question still had 40 tokens of room, and none of the 2800 questions per
checkpoint was cut.

| | `jev-1.13.0` | `kev-4b` | Laya multilingual | Laya English |
|---|---|---|---|---|
| wall clock, 200 positions, hints | about 57 seconds | about 15.5 minutes | about 80 seconds | about 109 seconds |

### Leaderboard with hints

| run | n | best move | regret | illegal mass | lead MAE | moves decided/wrong/illegal at 0.85 | again/takes decided/wrong at 0.85 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| jev-hints | 200 | 57% | 2.43 | 0.00 | 0.88 | 69/17/0 | 1785/0 |
| kev-4b-hints | 200 | 50% | 2.80 | 0.08 | 1.14 | 6/2/0 | 200/4 |
| laya-multilingual-hints | 200 | 24% | 5.16 | 0.23 | 1.41 | 7/6/1 | 2345/2160 |
| laya-english-hints | 200 | 45% | 3.58 | 0.08 | 1.34 | 11/3/0 | 37/27 |
| baseline: random | 200 | 30% | 5.11 | 0.00 | – | – | – |
| baseline: greedy | 200 | 41% | 3.97 | 0.00 | – | – | – |
| baseline: gain minus threat | 200 | 56% | 2.35 | 0.00 | – | – | – |
| baseline: always even | 200 | – | – | – | 1.39 | – | – |
| baseline: stores only | 200 | – | – | – | 1.03 | – | – |
| baseline: always FALSE | 200 | – | – | – | – | – | 2400/191 |

| acceptAt | jev-hints moves | jev-hints again/takes | kev-4b-hints moves | kev-4b-hints again/takes | laya-multilingual-hints moves | laya-multilingual-hints again/takes | laya-english-hints moves | laya-english-hints again/takes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0.95 | 20/5/0 | 1558/0 | 1/0/0 | 113/0 | 0/0/0 | 1464/1358 | 2/0/0 | 0/0 |
| 0.85 | 69/17/0 | 1785/0 | 6/2/0 | 200/4 | 7/6/1 | 2345/2160 | 11/3/0 | 37/27 |
| 0.75 | 109/31/0 | 2120/0 | 17/5/0 | 620/14 | 16/14/3 | 2395/2204 | 26/11/0 | 793/695 |
| 0.60 | 145/45/0 | 2359/0 | 42/19/0 | 2062/37 | 40/36/12 | 2399/2208 | 71/32/0 | 2126/1914 |

Some cells sit on a rounding tie: 50% is 99 of 200, 24% is 47 of 200, 41% is 81 of 200, and 2.43, 2.80, 5.16,
3.58, 2.35 and 1.39 are 2.425, 2.795, 5.155, 3.575, 2.345 and 1.385. `gain minus threat` is 113 of 200 (56.5%)
and prints 56% because the double is just below 0.565. Jev's 57% is 114 of 200. A separate Python recount from the
data in `kalah-hints.html`, with its own sowing code for `greedy` and `gain minus threat`, gave the same counts.

### Hints against no hints

Each cell is no hints → hints. Decided/wrong is at 0.85. TRUE recall is the TRUE cells accepted as TRUE at 0.85,
of 191.

| run | best move | regret | illegal mass | moves decided/wrong | again/takes decided/wrong | TRUE recall | lead MAE |
|---|---|---|---|---|---|---|---|
| jev | 25% → 57% | 5.97 → 2.43 | 0.06 → 0.00 | 0/0 → 69/17 | 879/31 → 1785/0 | 0 → 190 | 1.00 → 0.88 |
| kev-4b | 25% → 50% | 5.54 → 2.80 | 0.25 → 0.08 | 0/0 → 6/2 | 4/4 → 200/4 | 0 → 190 | 1.19 → 1.14 |
| laya-multilingual | 26% → 24% | 5.70 → 5.16 | 0.26 → 0.23 | 0/0 → 7/6 | 2196/2020 → 2345/2160 | 176 → 185 | 1.59 → 1.41 |
| laya-english | 27% → 45% | 5.53 → 3.58 | 0.25 → 0.08 | 22/19 → 11/3 | 1025/914 → 37/27 | 84 → 10 | 1.50 → 1.34 |

From the Python recount, per position over the legal pits (the top pit is the legal pit with the highest p, as for
best move):

| run | rank correlation p(pit) and engine value | top pit is the `greedy` pit | top pit is the `gain minus threat` pit |
|---|---|---|---|
| jev | 0.01 → 0.38 | 97 → 130 | 66 → 166 |
| kev-4b | −0.07 → 0.29 | 41 → 74 | 33 → 96 |
| laya-multilingual | −0.04 → −0.08 | 50 → 57 | 36 → 38 |
| laya-english | −0.04 → 0.19 | 45 → 49 | 46 → 74 |

For scale: `greedy` and `gain minus threat` pick the same pit on 137 of 200 boards, and the net gain itself has a
rank correlation of 0.51 with the engine values (on the 192 boards where it is not constant).

### Findings with hints

1. **Jev ties the one-ply rule, and no judge beats it on regret.** Jev's best move is 114 of 200, one more than
   `gain minus threat` (113), but its regret is higher (2.43 against 2.35). Its top pit is the `gain minus threat`
   pit on 166 of 200 boards and the `greedy` pit on 130, so it weighs the threat and does not only read the biggest
   gain. Kev (50%, 2.80) beats `greedy` (41%, 3.97) but not `gain minus threat`.
2. **Laya English beats `greedy`, but mostly through pit 6.** Its top pit is pit 6 on 129 of 200 boards, and pit 6
   is a best pit on 73. The rule "sow the highest legal pit" reads nothing, and the Python recount (not a `rank`
   row) gives it 95 of 200 best pits with regret 3.435, better than Laya English (45%, 3.58). Laya multilingual
   stays below `random` (24%, 5.16), and its top pit is still empty on 39 of 200 boards.
3. **At 0.85, Jev now decides moves and is mostly right; the others abstain.** Jev decides 69 moves and 52 of them
   are best pits (75%, against 57% for its top pit on all boards). Kev decides 6, Laya English 11 (3 wrong, down
   from 19 of 22) and Laya multilingual 7, of which 6 are wrong and 1 is an empty pit.
4. **When the state says it, Jev and Kev read again/takes, but only Jev decides.** Both rank TRUE above FALSE on
   the legal pits with AUC 1.00 for again and for takes, from 0.41 to 0.55 without hints. At 0.85, Jev decides 1785
   cells with 0 wrong and accepts 190 of 191 TRUE cells; even at 0.60 it decides 2359 with 0 wrong. It says FALSE
   on all 608 empty-pit cells but on only 987 of the 1601 FALSE cells of legal pits, and those make 614 of its 615
   abstentions. Kev also accepts 190 of 191 TRUE cells, but its mean p(true) is 0.35 on FALSE cells of legal pits
   and 0.31 on empty pits, so it decides 6 of 2209 FALSE cells and is wrong on 4 of its 200 decisions.
5. **Laya does not read again/takes, even when the state gives the answer.** Laya multilingual has a mean p(true)
   of 0.94 on TRUE cells, 0.95 on FALSE cells of legal pits and 0.94 on empty pits, and is wrong on 2160 of 2345 at
   0.85 (AUC 0.55 for again, 0.35 for takes). laya-server and laya-mlx do not swap TRUE and FALSE, so the bias
   comes from the checkpoint. Laya English moves toward 0.7 (0.75 on TRUE cells, 0.72 on FALSE cells of legal pits,
   0.64 on empty pits): its wrong count drops from 914 to 27 because it decides only 37 cells, and its TRUE recall
   drops from 84 to 10.
6. **Lead improves a little; the hints do not state it.** Jev's lead MAE is 0.88 and now beats `stores only`
   (1.03). Its expected level correlates 0.61 with the engine bucket (0.51 without hints) and 0.81 with the stores
   bucket (0.90). Kev (1.14) and both Laya checkpoints (1.41, 1.34) stay above `stores only`, and Laya multilingual
   also loses to `always even` (1.39).

### What this does and does not establish

The hints turn part of the task into reading. `againN` and `takesN` are written in `if_sown`, and the move becomes
weighing a stated gain against a stated threat, so these scores measure reading and weighing, not simulating a
sowing; the no-hint results stay the measure of that. `gain minus threat` is what a perfect reader of the hints
reaches with one ply. Jev ties it on best move, and no judge beats it on regret. This is again 200 positions from
one seed and one wording: the `RULES` text, `IF_SOWN_RULE` and 14 fixed instructions. The pit-6 lean of Laya
English shows that a position bias can look like skill, so compare a gain with "sow the highest legal pit" too. Do
not compare the probabilities across judges: Kev's mean p(true) of 0.35 on FALSE cells of legal pits and Jev's 0.13
come from different models, and only the decided `Truth` at one `acceptAt` compares. Both Laya checkpoints report
the same judge id, so only the file name tells the two runs apart.

## Laya: MLX port against upstream

`laya-mlx` 0.2.0 is an MLX port of upstream Laya ([NandhaKishorM/laya](https://github.com/NandhaKishorM/laya),
PyPI `laya` 0.3.5, PyTorch). To find out if the port causes the Laya results above, upstream Laya ran on
2026-09-22 on the same Mac, on the same benches: this bench with and without hints, and the promises demo
(`demo/README.md`, section Laya).

| | laya-mlx (the runs above) | upstream Laya |
|---|---|---|
| package | `laya-mlx` 0.2.0, Python 3.11 | `laya` 0.3.5 (the same code as git `573e5b6`), torch 2.14.0, transformers 5.17.0, Python 3.12.14 |
| server | laya-server@`ad2b426` on `:8010` | a throwaway bridge on `:8011`, not shipped |
| device, dtype | Apple GPU (MLX), float16 (the laya-server default) | Apple GPU (torch MPS), float32 (upstream forces it on MPS and CPU) |
| checkpoints | `aac6fef/laya-multilingual-mlx`, `aac6fef/laya-mlx` | `convaiinnovations/laya-multilingual`, `convaiinnovations/laya` (English), `convaiinnovations/laya-typed-decisions` |

The bridge is laya-server@`ad2b426` with upstream `laya.load` as the backend: the same `/v1/systemone` schemas
and status codes. Its response `model` names the checkpoint (`laya-english`, `laya-multilingual`,
`laya-typed-decisions`), so each checkpoint has its own judge id. Kleene ships no bridge (ADR-0006, spec section
0). The two `aac6fef` weight files have the same tensors as upstream (206 English, 170 multilingual), and every
tensor has the same values: upstream also ships float16, so the conversion lost nothing. `typed-decisions` has no
MLX conversion, so it ran upstream only.

Each checkpoint made 459 requests (200 + 200 + 59 promise versions), one per position or version. All were HTTP
200, so there was no error and no retry, and no response was `Malformed`. Wall clock, Maven start included:

| | upstream multilingual | upstream English | upstream typed-decisions |
|---|---|---|---|
| 200 positions | 34 seconds | 81 seconds | 79 seconds |
| 200 positions, hints | 57 seconds | 163 seconds | 139 seconds |
| 59 promise versions | 13 seconds | 14 seconds | 28 seconds |

### Parity

Both backends got the same inputs in-process: 20 no-hint states × 14 questions, and the upstream README
quickstart (an English email and a Hindi body × 4 questions). The reference is upstream on MPS in float32.
Upstream on CPU matches it to 7e-6 in the logits. dp is the absolute difference in probability, p(true) for
feels. The candidate is laya-mlx in float16.

| checkpoint | input | kind | n | mean dp | max dp | argmax differs | `Truth` at 0.85 differs |
|---|---|---|---|---|---|---|---|
| English | Kalah | choose | 20 | 0.0007 | 0.0064 | 0 | 0 |
| English | Kalah | feels | 240 | 0.0009 | 0.0091 | 0 | 0 |
| English | Kalah | score | 20 | 0.0004 | 0.0017 | 0 | – |
| English | quickstart | all | 8 | ≤ 0.0004 | 0.0012 | 0 | 0 |
| multilingual | Kalah | choose | 20 | 0.0003 | 0.0023 | 0 | 0 |
| multilingual | Kalah | feels | 240 | 0.0005 | 0.0038 | 0 | 0 |
| multilingual | Kalah | score | 20 | 0.0002 | 0.0008 | 0 | – |
| multilingual | quickstart | all | 8 | ≤ 0.0001 | 0.0005 | 0 | 0 |

The token ids and the marker positions are the same on 288 of 288 questions for both checkpoints. No parity state
was cut (at most 291 of 512 tokens on English, 309 of 1024 on multilingual). With laya-mlx in float32, the max dp
is 1.7e-5 (English) and 3.6e-6 (multilingual), so the float16 default is the only difference. laya-server has
`--dtype float32` for that; the runs above did not use it.

Over the full runs, laya-mlx against upstream:

| run | records | max dp | argmax of `move` or `lead` differs | again/takes `Truth` at 0.85 differs |
|---|---|---|---|---|
| English | 200 | 0.0090 | 1 of 400 | 6 of 2400 |
| English, hints | 200 | 0.0077 | 1 of 400 | 0 of 2400 |
| multilingual | 200 | 0.0038 | 1 of 400 | 1 of 2400 |
| multilingual, hints | 200 | 0.0078 | 1 of 400 | 0 of 2400 |
| promises, English | 59 | 0.0049 | – | – |
| promises, multilingual | 59 | 0.0030 | – | – |

The 4 argmax differences are near ties: the top two answers differ by at most 0.0025 in both runs.

### Promises

The same 59 versions, whole, and the same 236 labelled cells as `demo/README.md`, section Laya. Decided / wrong:

| acceptAt | kev | jev | laya multilingual | upstream multilingual | laya English | upstream English | upstream typed-decisions |
|---|---|---|---|---|---|---|---|
| 0.95 | 26/0 | 0/0 | 227/169 | 227/169 | 0/0 | 0/0 | 0/0 |
| 0.85 | 57/0 | 203/0 | 232/172 | 232/172 | 56/56 | 56/56 | 0/0 |
| 0.75 | 85/1 | 233/0 | 234/174 | 234/174 | 120/117 | 120/117 | 29/26 |
| 0.60 | 175/16 | 233/0 | 235/175 | 235/175 | 192/175 | 192/175 | 133/130 |

On the 56 intact versions, the real promise (deletion) scores above a silent requirement with probability 1.00
for Kev and Jev, 0.76 for multilingual and 0.001 for English (the same on both backends), and 0.000 for
typed-decisions.

### Leaderboard, Laya variants

| run | n | best move | regret | illegal mass | lead MAE | moves decided/wrong/illegal at 0.85 | again/takes decided/wrong at 0.85 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| laya-multilingual | 200 | 26% | 5.70 | 0.26 | 1.59 | 0/0/0 | 2196/2020 |
| laya-english | 200 | 27% | 5.53 | 0.25 | 1.50 | 22/19/5 | 1025/914 |
| laya-upstream-multilingual | 200 | 26% | 5.71 | 0.26 | 1.59 | 0/0/0 | 2195/2019 |
| laya-upstream-english | 200 | 27% | 5.53 | 0.25 | 1.50 | 22/19/5 | 1029/917 |
| laya-upstream-typed-decisions | 200 | 24% | 5.48 | 0.26 | 1.32 | 0/0/0 | 0/0 |
| baseline: random | 200 | 30% | 5.11 | 0.00 | – | – | – |
| baseline: greedy | 200 | 41% | 3.97 | 0.00 | – | – | – |
| baseline: gain minus threat | 200 | 56% | 2.35 | 0.00 | – | – | – |
| baseline: always even | 200 | – | – | – | 1.39 | – | – |
| baseline: stores only | 200 | – | – | – | 1.03 | – | – |
| baseline: always FALSE | 200 | – | – | – | – | – | 2400/191 |

| acceptAt | laya-multilingual moves | laya-multilingual again/takes | laya-english moves | laya-english again/takes | laya-upstream-multilingual moves | laya-upstream-multilingual again/takes | laya-upstream-english moves | laya-upstream-english again/takes | laya-upstream-typed-decisions moves | laya-upstream-typed-decisions again/takes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0.95 | 0/0/0 | 408/376 | 8/5/0 | 47/42 | 0/0/0 | 409/376 | 8/5/0 | 48/43 | 0/0/0 | 0/0 |
| 0.85 | 0/0/0 | 2196/2020 | 22/19/5 | 1025/914 | 0/0/0 | 2195/2019 | 22/19/5 | 1029/917 | 0/0/0 | 0/0 |
| 0.75 | 0/0/0 | 2375/2186 | 45/38/10 | 1622/1388 | 0/0/0 | 2375/2186 | 45/38/10 | 1623/1389 | 0/0/0 | 35/4 |
| 0.60 | 1/1/1 | 2397/2206 | 72/58/17 | 2110/1719 | 1/1/1 | 2398/2207 | 72/58/17 | 2112/1719 | 0/0/0 | 720/208 |

### Leaderboard with hints, Laya variants

| run | n | best move | regret | illegal mass | lead MAE | moves decided/wrong/illegal at 0.85 | again/takes decided/wrong at 0.85 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| jev-hints | 200 | 57% | 2.43 | 0.00 | 0.88 | 69/17/0 | 1785/0 |
| laya-multilingual-hints | 200 | 24% | 5.16 | 0.23 | 1.41 | 7/6/1 | 2345/2160 |
| laya-english-hints | 200 | 45% | 3.58 | 0.08 | 1.34 | 11/3/0 | 37/27 |
| laya-upstream-multilingual-hints | 200 | 24% | 5.10 | 0.23 | 1.41 | 7/6/1 | 2345/2160 |
| laya-upstream-english-hints | 200 | 45% | 3.58 | 0.08 | 1.34 | 11/3/0 | 37/27 |
| laya-upstream-typed-decisions-hints | 200 | 48% | 3.43 | 0.11 | 1.33 | 0/0/0 | 0/0 |
| baseline: random | 200 | 30% | 5.11 | 0.00 | – | – | – |
| baseline: greedy | 200 | 41% | 3.97 | 0.00 | – | – | – |
| baseline: gain minus threat | 200 | 56% | 2.35 | 0.00 | – | – | – |
| baseline: always even | 200 | – | – | – | 1.39 | – | – |
| baseline: stores only | 200 | – | – | – | 1.03 | – | – |
| baseline: always FALSE | 200 | – | – | – | – | – | 2400/191 |

| acceptAt | jev-hints moves | jev-hints again/takes | laya-multilingual-hints moves | laya-multilingual-hints again/takes | laya-english-hints moves | laya-english-hints again/takes | laya-upstream-multilingual-hints moves | laya-upstream-multilingual-hints again/takes | laya-upstream-english-hints moves | laya-upstream-english-hints again/takes | laya-upstream-typed-decisions-hints moves | laya-upstream-typed-decisions-hints again/takes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0.95 | 20/5/0 | 1558/0 | 0/0/0 | 1464/1358 | 2/0/0 | 0/0 | 0/0/0 | 1470/1363 | 2/0/0 | 0/0 | 0/0/0 | 0/0 |
| 0.85 | 69/17/0 | 1785/0 | 7/6/1 | 2345/2160 | 11/3/0 | 37/27 | 7/6/1 | 2345/2160 | 11/3/0 | 37/27 | 0/0/0 | 0/0 |
| 0.75 | 109/31/0 | 2120/0 | 16/14/3 | 2395/2204 | 26/11/0 | 793/695 | 16/14/3 | 2395/2204 | 27/11/0 | 793/695 | 0/0/0 | 0/0 |
| 0.60 | 145/45/0 | 2359/0 | 40/36/12 | 2399/2208 | 71/32/0 | 2126/1914 | 41/37/12 | 2399/2208 | 72/33/0 | 2124/1912 | 3/0/0 | 228/118 |

again/takes with hints, where `if_sown` states each answer. Mean p(true) per cell group, AUC of TRUE against FALSE
on legal pits, and TRUE cells accepted at 0.85 (of 191):

| run | TRUE cells | FALSE cells, legal pits | empty pits | AUC again / takes | TRUE accepted |
|---|---|---|---|---|---|
| jev-hints | 0.98 | 0.13 | 0.02 | 1.00 / 1.00 | 190 |
| laya-multilingual-hints | 0.94 | 0.95 | 0.94 | 0.55 / 0.35 | 185 |
| laya-upstream-multilingual-hints | 0.94 | 0.95 | 0.94 | 0.55 / 0.35 | 185 |
| laya-english-hints | 0.75 | 0.72 | 0.64 | 0.56 / 0.68 | 10 |
| laya-upstream-english-hints | 0.75 | 0.72 | 0.64 | 0.56 / 0.68 | 10 |
| laya-upstream-typed-decisions-hints | 0.57 | 0.54 | 0.47 | 0.60 / 0.85 | 0 |

### Findings, MLX port against upstream

1. **laya-mlx is upstream Laya in float16.** The weights have the same values, the tokens are the same on 288 of
   288 questions, and the max dp is 0.009 (1.7e-5 in float32). On the promises, the decided/wrong counts are the
   same at every `acceptAt`. On Kalah, best move and lead MAE print the same in every run, and only cells near a
   threshold move: at 0.85, again/takes is 1025/914 against 1029/917 (English) and 2196/2020 against 2195/2019
   (multilingual).
2. **The wrong verdicts come from upstream.** At 0.85, upstream multilingual is wrong on 172 of 232 promise cells
   and upstream English on 56 of 56, as laya-mlx. With hints, where the state gives each again/takes answer,
   upstream multilingual has a mean p(true) of 0.94 on TRUE cells and 0.95 on FALSE cells of legal pits and is
   wrong on 2160 of 2345. Jev, on the same states, decides 1785 with 0 wrong.
3. **The ranking is wrong, so no calibration can fix it.** A temperature does not change the order of the
   answers. The real promise ranks above a silent one with probability 0.001 on English and 0.000 on
   typed-decisions, against 1.00 for Kev and Jev. With hints, takes has AUC 0.35 on multilingual. The
   multilingual checkpoint also ships temperature 1 for every kind, so its p is not calibrated either.
4. **typed-decisions runs on the English temperatures and abstains.** Its own per-kind temperatures are 1.01 to
   1.06, but its `temperature_by_options`, which takes precedence, is the English one to the digit (`noul:2`
   1.9834, `score:3-5` 1.2514, `choice:6-10` 1.0000, `choice:11+` 0.1006 clamped to 0.5). Its p(true) stays in
   0.185 to 0.662 on the promises and 0.206 to 0.716 on Kalah, so it decides no cell at 0.85 on either bench. At
   0.60 it decides 133 promise cells and 130 are wrong. With hints it ranks takes with AUC 0.85, the best Laya
   value, but no p reaches 0.85.
5. **With hints, the Laya best move is mostly a lean to pit 6, on both backends.** English (45%) and
   typed-decisions (48%) beat `greedy`, but their top pit is the highest legal pit on 153 and 134 of 200 boards.
   That rule alone gives 95 of 200 best pits with regret 3.435 (the recount in Findings with hints).
   Multilingual stays below `random` (24%) on both backends.
6. **No tested prompt shape changes the result.** A feels question with no criteria gets the upstream default
   options "no, the statement does not hold" and "yes, the statement holds", as the two feels questions of the
   upstream README quickstart do. Upstream also reads the state as JSON (`json.dumps`). In that quickstart, a
   support email that says "or we will cancel our plan", upstream multilingual gives `churn_risk` p(true)
   0.1345, a FALSE at 0.85 (English: 0.8248, UNKNOWN). On the promises, a question-form instruction and true/false
   criteria did not fix the ranking (`demo/README.md`, on laya-mlx, which is the same model). Nobody tried other
   shapes on Kalah, so a better shape is not excluded there.

### Verdict

laya-mlx is not broken: it runs the upstream weights and reproduces upstream Laya to 0.009 in p, with the same
promise verdicts and Kalah verdicts that differ only on a few near-threshold cells. Upstream Laya is not fit to
judge these benches: its checkpoints rank the answers wrongly, even when the state gives the answer, and the
multilingual (temperature 1) and typed-decisions (English temperatures) checkpoints are also not calibrated. The
cause is the checkpoints, not the port; the prompt shapes that were tried did not help, and other shapes on Kalah
stay untested.
