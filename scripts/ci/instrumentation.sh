#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

diagnostics="${HITA_CI_DIAGNOSTICS:-/tmp/hita-ci-diagnostics}"
mkdir -p "$diagnostics"

capture_diagnostics() {
  {
    echo "## adb devices"
    adb devices -l || true
    echo
    echo "## package"
    adb shell pm path cn.limpu.hita.diag || true
    echo
    echo "## logcat"
    adb logcat -d -t 400 || true
  } >"$diagnostics/emulator.txt" 2>&1 || true
}
trap capture_diagnostics EXIT

exec ./gradlew \
  :app:connectedDebugAndroidTest \
  :component:connectedDebugAndroidTest \
  :hitauser:connectedDebugAndroidTest \
  :style:connectedDebugAndroidTest \
  --console=plain \
  --no-daemon \
  --no-parallel \
  --stacktrace
