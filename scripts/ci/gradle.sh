#!/usr/bin/env bash
set -euo pipefail

if [[ $# -eq 0 ]]; then
  echo "usage: scripts/ci/gradle.sh <gradle task or option>..." >&2
  exit 2
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

exec ./gradlew "$@" --console=plain --no-daemon --stacktrace
