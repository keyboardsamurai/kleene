# ICD bench facts

acceptAt 0.85: feels TRUE if p >= 0.85, FALSE if p <= 0.15, else UNKNOWN; principal decided if top p >= 0.85.
Written by a python script over `out/icd/*.jsonl` and `fixture.jsonl`; zero model calls.

## (a) Trap codes on the 20 hard documents

Trap cell = a code the keyword baseline (any synonym, any language, case-insensitive whole word) marks TRUE on the document but is not in the gold codes.
For `no-code` documents the gold set is empty, so every TRUE code is wrong: the table adds the codes the judge itself marks TRUE (column `codes TRUE (not keyword traps)`) and the principal decision.

| hard kind | docs | trap cells | judge | TRUE | UNKNOWN | FALSE |
| --- | --- | --- | --- | --- | --- | --- |
| family | 3 | 7 | jev-1.13.0 | 0 | 2 | 5 |
| family | 3 | 7 | kev-4b | 1 | 2 | 4 |
| family | 3 | 7 | laya | 6 | 1 | 0 |
| family | 3 | 7 | laya-en | 2 | 3 | 2 |
| history | 4 | 7 | jev-1.13.0 | 0 | 0 | 7 |
| history | 4 | 7 | kev-4b | 0 | 1 | 6 |
| history | 4 | 7 | laya | 5 | 2 | 0 |
| history | 4 | 7 | laya-en | 2 | 4 | 1 |
| negation | 5 | 7 | jev-1.13.0 | 0 | 1 | 6 |
| negation | 5 | 7 | kev-4b | 0 | 1 | 6 |
| negation | 5 | 7 | laya | 4 | 3 | 0 |
| negation | 5 | 7 | laya-en | 1 | 4 | 2 |
| no-code | 5 | 1 | jev-1.13.0 | 0 | 0 | 1 |
| no-code | 5 | 1 | kev-4b | 0 | 1 | 0 |
| no-code | 5 | 1 | laya | 0 | 1 | 0 |
| no-code | 5 | 1 | laya-en | 0 | 1 | 0 |
| suspected | 3 | 6 | jev-1.13.0 | 0 | 2 | 4 |
| suspected | 3 | 6 | kev-4b | 0 | 3 | 3 |
| suspected | 3 | 6 | laya | 1 | 5 | 0 |
| suspected | 3 | 6 | laya-en | 2 | 3 | 1 |

Trap codes per document (keyword FP):

| hard kind | doc | gold | trap codes | jev-1.13.0 | kev-4b | laya | laya-en |
| --- | --- | --- | --- | --- | --- | --- | --- |
| family | icd-013 | E78, I20, Z82 | I21, R07 | I21 F 0.02, R07 U 0.56 | I21 F 0.02, R07 T 0.88 | I21 T 1.00, R07 T 1.00 | I21 F 0.12, R07 F 0.01 |
| family | icd-022 | E11, J18, Z80 | C34, I48, R50 | C34 F 0.02, I48 F 0.02, R50 F 0.11 | C34 F 0.05, I48 F 0.02, R50 U 0.23 | C34 T 1.00, I48 T 1.00, R50 T 1.00 | C34 T 0.87, I48 U 0.81, R50 U 0.85 |
| family | icd-041 | K35, Z80 | C18, R50 | C18 F 0.02, R50 U 0.16 | C18 F 0.02, R50 U 0.18 | C18 U 0.81, R50 T 0.91 | C18 T 0.91, R50 U 0.17 |
| history | icd-011 | E66, K80, Z87 | R50 | R50 F 0.02 | R50 F 0.04 | R50 U 0.41 | R50 U 0.24 |
| history | icd-014 | I10, M54, Z87 | R50, S72 | R50 F 0.01, S72 F 0.03 | R50 F 0.03, S72 F 0.08 | R50 T 1.00, S72 T 1.00 | R50 T 0.93, S72 T 1.00 |
| history | icd-020 | E66, I10, Z86 | I26 | I26 F 0.04 | I26 F 0.05 | I26 T 0.98 | I26 U 0.22 |
| history | icd-026 | R50, Z87 | J18, R07, R10 | J18 F 0.14, R07 F 0.03, R10 F 0.02 | J18 U 0.43, R07 F 0.05, R10 F 0.02 | J18 U 0.69, R07 T 0.97, R10 T 0.95 | J18 U 0.72, R07 F 0.11, R10 U 0.49 |
| negation | icd-031 | E11, E66, L03 | R50, S72 | R50 U 0.25, S72 F 0.02 | R50 U 0.32, S72 F 0.02 | R50 T 0.99, S72 U 0.48 | R50 U 0.79, S72 T 0.85 |
| negation | icd-032 | E11, E66 | K81 | K81 F 0.02 | K81 F 0.01 | K81 U 0.43 | K81 F 0.11 |
| negation | icd-051 | E66, K35 | K80 | K80 F 0.02 | K80 F 0.07 | K80 T 0.99 | K80 U 0.22 |
| negation | icd-077 | R50, R51 | I63 | I63 F 0.02 | I63 F 0.03 | I63 U 0.40 | I63 F 0.15 |
| negation | icd-095 | R50, R51 | J18, N39 | J18 F 0.02, N39 F 0.03 | J18 F 0.05, N39 F 0.03 | J18 T 0.86, N39 T 0.96 | J18 U 0.83, N39 U 0.78 |
| no-code | icd-037 | - | - | - | - | - | - |
| no-code | icd-049 | - | - | - | - | - | - |
| no-code | icd-060 | - | - | - | - | - | - |
| no-code | icd-083 | - | R50 | R50 F 0.02 | R50 U 0.16 | R50 U 0.33 | R50 U 0.57 |
| no-code | icd-093 | - | - | - | - | - | - |
| suspected | icd-023 | G45, I10 | I48, I63, R51 | I48 F 0.10, I63 F 0.07, R51 F 0.02 | I48 U 0.20, I63 F 0.11, R51 F 0.02 | I48 U 0.78, I63 U 0.63, R51 U 0.21 | I48 T 0.92, I63 T 0.95, R51 F 0.15 |
| suspected | icd-055 | I10, R07, Z82 | I20, I63 | I20 U 0.22, I63 F 0.03 | I20 U 0.16, I63 F 0.08 | I20 U 0.16, I63 U 0.25 | I20 U 0.83, I63 U 0.62 |
| suspected | icd-096 | R51 | G43 | G43 U 0.24 | G43 U 0.28 | G43 T 0.96 | G43 U 0.28 |

(T/U/F = TRUE/UNKNOWN/FALSE at 0.85, then p.)

### no-code documents: extra TRUE codes and principal

| judge | docs | codes TRUE (not keyword traps) | codes UNKNOWN (all 51) | principal none | principal a code | principal UNKNOWN |
| --- | --- | --- | --- | --- | --- | --- |
| jev-1.13.0 | 5 | 0 | 0 of 255 | 5 | 0 | 0 |
| kev-4b | 5 | 0 | 12 of 255 | 0 | 0 | 5 |
| laya | 5 | 31 (icd-037:E05, icd-037:E10, icd-037:E78, icd-037:I48, icd-037:M80, icd-037:M81, icd-037:R51, icd-049:D50, icd-049:E03, icd-049:E05, icd-049:E10, icd-049:E11 ...) | 162 of 255 | 0 | 0 | 5 |
| laya-en | 5 | 6 (icd-060:E78, icd-060:G43, icd-093:D12, icd-093:K35, icd-093:S72, icd-093:Z87) | 216 of 255 | 0 | 1 | 4 |

## (b) UNKNOWN saves

Over every document each judge has. `wrong TRUE@0.5` = cells with p > 0.5 whose code is not gold. `saved` = those that are UNKNOWN at 0.85 (0.15 < p < 0.85); `still TRUE` = p >= 0.85.
`right TRUE@0.5` = cells with p > 0.5 whose code is gold; `lost to UNKNOWN` = those UNKNOWN at 0.85.

| judge | docs | cells | wrong TRUE@0.5 | saved (UNKNOWN@0.85) | still TRUE@0.85 | right TRUE@0.5 | lost to UNKNOWN@0.85 | kept TRUE@0.85 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| jev-1.13.0 | 100 | 5100 | 31 | 28 | 3 | 180 | 57 | 123 |
| kev-4b | 100 | 5100 | 81 | 74 | 7 | 166 | 49 | 117 |
| laya | 100 | 5100 | 2870 | 1198 | 1672 | 164 | 65 | 99 |
| laya-en | 100 | 5100 | 1934 | 1435 | 499 | 144 | 97 | 47 |

## (c) Runs

Wall clock is the `log` client (Maven start included). Peak = tree footprint (ri_phys_footprint, includes Metal buffers) of the memguard tree, sampled every 1 s by a separate reader; the guard itself polls every 0.5 s.
All local servers ran one at a time under `scripts/memguard.py` (floor 16 GiB free). After each run the server was stopped with SIGTERM to the guard and `ps` and `lsof` showed no server process and no listener on :8009/:8010.

| judge | records | where | this session | requests | errors / retries | guard kills | peak tree |
| --- | --- | --- | --- | --- | --- | --- | --- |
| jev-1.13.0 | 100 | cloud TypeSafe | not rerun (complete before the incident) | 100 (earlier) | not recorded | n/a | n/a (cloud) |
| laya (multilingual, `aac6fef/laya-multilingual-mlx`) | 100 | local, laya.sh, cap 16 GiB, MLX memory limit 8 GiB, cache limit 1 GiB | icd-099 and icd-100 in 2 s wall (564 ms and 198 ms server time) | 2 (98 before the incident) | 0 / 0 | 0 | 2.2 GiB this session; 3.0 GiB over the 100-document guarded probe run earlier today (probe-rss.txt); in the incident, without the caps, 78-102 GB |
| laya-en (`aac6fef/laya-mlx`) | 100 (62 flagged `fits=false` from cut-laya-en.txt) | local, laya.sh, same caps | 134 s wall, 132 s server time (max 2.0 s per request) | 100 | 0 / 0 | 0 | 3.1 GiB (idle ~2.2) |
| kev-4b (bf16, torch MPS) | 100 | local, kev.sh, cap 64 GiB, PYTORCH_MPS_HIGH_WATERMARK_RATIO 0.55 (~59 GiB), LOW 0.5 | icd-005 to icd-100 in 2 h 33 min wall (13:44:29-16:17:57, ~96 s per document); icd-001..004 from the earlier run at the 40 GiB cap | 96 POSTs, all HTTP 200 | 0 / 0 | 0 | 58.8 GiB (kev-rss.txt, 5 s samples; 22.5 GiB idle after load; system free never below 70.4 GiB) |

Kev, first run (cap 40 GiB, MPS ratio 0.3 = 32.26 GiB): icd-005 failed twice with HTTP 500 `RuntimeError: MPS backend out of memory (MPS allocated: 29.80-31.94 GiB, max allowed: 32.26 GiB)` in `torch_chunk_gated_delta_rule` (6 POSTs, runs of 99 s and 18 s, peak 33.1 GiB), then the server was stopped. Second run, approved once by the user: guard cap 64 GiB (half the RAM), MPS ratio 0.55, MEMGUARD_MIN_FREE_GB default 16. It resumed after icd-004 and logged the other 96 documents with no error, no retry and no guard kill. While it computed the tree held 36.5-58.8 GiB (median 54.6), 5.2 GiB under the cap at the peak. Afterwards the server was stopped with SIGTERM to the guard; `ps` showed no server process and `lsof` no listener on :8009.
