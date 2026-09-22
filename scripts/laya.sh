#!/usr/bin/env bash
# Starts a local Laya System One judge on http://127.0.0.1:8010 (laya-mlx behind phaser/laya-server).
# Apple Silicon only, macOS 14+. The first start downloads the weights; GET /healthz returns 200 when the server is ready.
# Set LAYA_MODEL to change the checkpoint (default: multilingual, 1024-token limit). Then:
#   KLEENE_BASE_URL=http://127.0.0.1:8010 KLEENE_MODEL=laya-mlx mvn test -Dgroups=live
set -euo pipefail
exec uvx --python 3.11 --with 'laya-mlx==0.2.0' \
  --from 'git+https://github.com/phaser/laya-server@ad2b426' \
  laya-server --host 127.0.0.1 --port 8010 --model "${LAYA_MODEL:-aac6fef/laya-multilingual-mlx}"
