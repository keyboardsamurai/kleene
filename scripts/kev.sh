#!/usr/bin/env bash
# Starts a local Kev System One judge on http://127.0.0.1:8009.
# Run it from a Kev checkout (https://github.com/jaredpalmer/kev) after `uv sync --extra serve`, then:
#   KLEENE_BASE_URL=http://127.0.0.1:8009 KLEENE_MODEL=kev-4b mvn test -Dgroups=live   (Kev echoes any model name)
# The server runs under memguard.py (MEMGUARD_MAX_GB, default 40 here). The MPS watermark makes torch raise OOM
# (HTTP 500) at 0.3 x the 107.5 GiB Metal working set (~32 GiB); the torch default of 1.7 x is more than the RAM.
set -euo pipefail
if [ -n "$(lsof -t -iTCP:8009 -iTCP:8010 -sTCP:LISTEN 2>/dev/null)" ]; then
  echo "kev.sh: a judge already listens on :8009 or :8010; stop it first (one local judge at a time)" >&2; exit 1
fi
export KEV_DTYPE="${KEV_DTYPE:-bf16}"
export PYTORCH_MPS_HIGH_WATERMARK_RATIO="${PYTORCH_MPS_HIGH_WATERMARK_RATIO:-0.3}"
export PYTORCH_MPS_LOW_WATERMARK_RATIO="${PYTORCH_MPS_LOW_WATERMARK_RATIO:-0.25}"
export MEMGUARD_MAX_GB="${MEMGUARD_MAX_GB:-40}"
exec "$(dirname "$0")/memguard.py" uv run --extra serve python -m kev.serve --run jaredpalmer/kev-4b --port 8009
