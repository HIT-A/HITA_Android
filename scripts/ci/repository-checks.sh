#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

python3 scripts/test_check_repository.py

args=()
if [[ -n "${LAYER_BASE_SHA:-}" ]]; then
  args+=(--base-ref "$LAYER_BASE_SHA")
fi

exec python3 scripts/check_repository.py "${args[@]}"
