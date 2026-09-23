#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["laya-mlx==0.2.0"]
# ///
"""Counts, per ICD document, the tokens of the longest request a Laya checkpoint sees, and writes the ids that
do not fit (laya-mlx cuts the state silently) to --out, one per line. Uses laya-mlx's own prompt builder and
tokenizer, so the count is the one the server makes: [CLS] type instructions [SEP] [MASK] opt ... [SEP] state [SEP].

  scripts/icd-laya-cut.py <fixture.jsonl> <labels.json> --model aac6fef/laya-mlx --out demo/out/icd/cut-laya-en.txt
"""
import argparse, json
from pathlib import Path

from huggingface_hub import snapshot_download
from laya_mlx.agent import Agent
from laya_mlx.common import build_prefix, render_options, serialize_state
from laya_mlx.tokenizer import Tokenizer

p = argparse.ArgumentParser()
p.add_argument("fixture"), p.add_argument("labels")
p.add_argument("--model", default="aac6fef/laya-multilingual-mlx")
p.add_argument("--out", required=True)
a = p.parse_args()

model = Path(snapshot_download(a.model, allow_patterns=["tokenizer/*", "rl_agent_config.json"]))
cfg = json.loads((model / "rl_agent_config.json").read_text())
max_len, head = cfg["max_len"], cfg["head_max_len"]
tok = Tokenizer(model / "tokenizer")

# The wire questions of Bench.kt `Questions`, as SystemOneJudge sends them.
labels = [f"{c['code']} {c['title']}" for c in json.loads(Path(a.labels).read_text())["codes"]]
feels = {l: {"type": "noul", "instructions": f"The document supports {l} as a current diagnosis of this patient"} for l in labels}
choose = {"type": "choice", "instructions": "Which is the principal diagnosis of this patient in the document?",
          "criteria": dict.fromkeys(labels + ["none of these"])}
prefix = lambda q: len(build_prefix(tok, Agent._to_internal(q), head)[0])
longest_feels = max(prefix(q) for q in feels.values())
principal = prefix(choose)
# build_prefix clips every option to the same length when the options overflow the head budget.
opts = [min(49, len(tok(" " + o)["input_ids"]) + 1) for o in render_options(Agent._to_internal(choose))]
per = max(4, (head - 16) // len(opts)) if sum(opts) > head - 16 else None

docs = [json.loads(line) for line in Path(a.fixture).read_text().splitlines() if line.strip()]
state = lambda d: len(tok(serialize_state({"document": d["text"]}).replace(tok.mask_token, " "))["input_ids"])
need = {d["id"]: state(d) + 1 for d in docs}  # state tokens plus the closing [SEP]
cut = sorted(i for i, n in need.items() if max(principal, longest_feels) + n > max_len)
Path(a.out).parent.mkdir(parents=True, exist_ok=True)
Path(a.out).write_text("".join(i + "\n" for i in cut))

print(f"{a.model}: window {max_len}, head {head}, {len(docs)} docs, max state {max(need.values()) - 1}")
for name, n in (("choose", principal), ("longest feels", longest_feels)):
    print(f"  {name}: prefix {n}, longest request {n + max(need.values())}, cut {sum(n + m > max_len for m in need.values())}")
if per: print(f"  choose options: {sum(opts)} tokens > head {head}, each option clipped to {per} tokens incl. [MASK], "
             f"{sum(n > per for n in opts)} of {len(opts)} options clipped")
print(f"  cut (longest request > {max_len}): {len(cut)} -> {a.out}")
