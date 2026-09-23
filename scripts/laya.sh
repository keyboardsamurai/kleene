#!/usr/bin/env bash
# Starts a local Laya System One judge on http://127.0.0.1:8010 (laya-mlx behind phaser/laya-server).
# Apple Silicon only, macOS 14+. The first start downloads the weights; GET /healthz returns 200 when the server is ready.
# Set LAYA_MODEL to change the checkpoint (default: multilingual, 1024-token limit). Then:
#   KLEENE_BASE_URL=http://127.0.0.1:8010 KLEENE_MODEL=laya-mlx mvn test -Dgroups=live
# The server runs under scripts/memguard.py (MEMGUARD_MAX_GB, default 16 here) and with the MLX buffer cache capped:
# by default MLX keeps freed GPU buffers up to ~100 GB, and every new padded length makes new ones (incident 2026-09-23).
set -euo pipefail
if [ -n "$(lsof -t -iTCP:8009 -iTCP:8010 -sTCP:LISTEN 2>/dev/null)" ]; then
  echo "laya.sh: a judge already listens on :8009 or :8010; stop it first (one local judge at a time)" >&2; exit 1
fi
export MEMGUARD_MAX_GB="${MEMGUARD_MAX_GB:-16}"
exec "$(dirname "$0")/memguard.py" uvx --python 3.11 --with 'laya-mlx==0.2.0' \
  --from 'git+https://github.com/phaser/laya-server@ad2b426' python -c '
import sys, mlx.core as mx
mx.set_memory_limit(8 << 30)  # soft: MLX frees its cache before it goes past this
mx.set_cache_limit(1 << 30)   # freed buffers kept for reuse
# ponytail: batch size stays 16 (same records as before) and no clear_cache per request; the cap holds it at ~3 GiB.
from laya_server.__main__ import main
main(sys.argv[1:])' \
  --host 127.0.0.1 --port 8010 --model "${LAYA_MODEL:-aac6fef/laya-multilingual-mlx}"
