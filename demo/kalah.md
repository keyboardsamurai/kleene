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
```

Score and draw every run side by side:

```sh
mvn -q exec:java -Dexec.mainClass=kleene.demo.kalah.MainKt -Dexec.args="rank out/kalah/jev.jsonl out/kalah/kev-4b.jsonl"
mvn -q exec:java -Dexec.mainClass=kleene.demo.kalah.MainKt -Dexec.args="html out/kalah/jev.jsonl out/kalah/kev-4b.jsonl --out out/kalah/kalah.html"
```

Options:

```text
log [--out out/kalah/kalah.jsonl] [--count 200] [--seed 1] [--timeout 60]
rank <jsonl>... [--accept-at 0.95,0.85,0.75,0.60]
html <jsonl>... [--out out/kalah/kalah.html]
```

`log` computes the positions (a few seconds), then asks once per position and prints one line per position. A
rerun resumes: it skips position ids already in the file. It stops if a logged board is not the regenerated one
(one file never mixes two seeds) or if a logged record has another judge id (one file never mixes two judges; the
two Laya checkpoints share one judge id, so give each its own file). `log` never catches a judge error: a timeout,
a 5xx or a `Malformed` response stops the run, and you rerun it. `--timeout` is the per-attempt limit in seconds.

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

Five baseline rows play no judge and fill only their own columns. They cover every distinct board across the runs:

- `random`: the exact expectation of a pit picked uniformly from the legal pits.
- `greedy`: the legal pit with the highest immediate store gain (capture and end sweep included), the first on a tie.
- `always even`: `lead` is always `even`.
- `stores only`: `lead` is the bucket of the current store difference.
- `always FALSE`: every `againN` and `takesN` is FALSE. It decides all 12 per position, and it is wrong on each TRUE one.

A judge that does not beat `random` on best move and regret does not read the board. A judge that does not beat
`greedy` does not look ahead.

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

## Results

TODO: fill in from a real run (Jev, Kev-4B, Laya multilingual, Laya English).
