#!/usr/bin/env bash
# Run the extension repo's mock backend UNCHANGED (phase-a3 §a3.1 requires it
# verbatim). Serves the config endpoints on http://localhost:8787 by default.
#
#   scripts/mock-backend.sh                       # example.com study origins
#   scripts/mock-backend.sh --origins example.com,foo.dev
#   curl -X POST localhost:8787/__end             # kill switch on
#   curl -X POST localhost:8787/__revive          # kill switch off
#
# Point the app at it via the DEBUG settings screen (apiBaseOverride, M2):
#   http://10.0.2.2:8787/api/v1/extension-config           (emulator → host)
#   http://<your-mac-LAN-ip>:8787/api/v1/extension-config  (physical device, M6)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXT="${MOVEO_EXT_REPO:-$ROOT/../../chrome-extension/moveo_one_chrome_extension}"
MOCK="$EXT/scripts/mock-backend.mjs"

if [[ ! -f "$MOCK" ]]; then
  echo "error: mock backend not found at $MOCK (set MOVEO_EXT_REPO to override)" >&2
  exit 1
fi

exec node "$MOCK" "$@"
