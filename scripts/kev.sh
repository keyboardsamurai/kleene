#!/usr/bin/env bash
# Starts a local Kev System One judge on http://127.0.0.1:8009.
# Run it from a Kev checkout (https://github.com/jaredpalmer/kev) after `uv sync --extra serve`, then:
#   KLEENE_BASE_URL=http://127.0.0.1:8009 KLEENE_MODEL=kev-4b mvn test -Dgroups=live   (Kev echoes any model name)
set -euo pipefail
export KEV_DTYPE="${KEV_DTYPE:-bf16}"
exec uv run --extra serve python -m kev.serve --run jaredpalmer/kev-4b --port 8009
