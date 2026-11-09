#!/usr/bin/env bash
# 模拟机黑盒 E2E：构建诊断 APK → 安装到私有模拟器 → 执行 UI Automator 流程 → 收集结果。
#
# 前置:
#   - 私有 AVD（默认 hita_agent05，端口 5570），避免与其它并行 agent 共享设备
#   - JAVA_HOME 指向 JDK 21
#
# 用法:
#   scripts/e2e-emulator.sh [run|boot|install|test]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AVD="${HITA_E2E_AVD:-hita_agent05}"
PORT="${HITA_E2E_PORT:-5570}"
SERIAL="emulator-${PORT}"
OUT="${ROOT}/build/e2e"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
mkdir -p "${OUT}"

boot() {
  if adb -s "${SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q 1; then
    echo "设备 ${SERIAL} 已就绪"
    return
  fi
  echo "启动 ${AVD} 于端口 ${PORT}"
  "${SDK}/emulator/emulator" -avd "${AVD}" -port "${PORT}" \
    -no-window -no-snapshot -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -read-only > "${OUT}/emulator.log" 2>&1 &
  for _ in $(seq 1 90); do
    if adb -s "${SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q 1; then
      echo "启动完成"
      return
    fi
    sleep 2
  done
  echo "模拟器启动超时，见 ${OUT}/emulator.log" >&2
  exit 1
}

install_apk() {
  local apk="${ROOT}/app/build/outputs/apk/debug/app-debug.apk"
  if [[ ! -f "${apk}" ]]; then
    echo "缺少 ${apk}；先运行 :app:assembleDebug" >&2
    exit 1
  fi
  adb -s "${SERIAL}" install -r -t "${apk}" | tee "${OUT}/install.log"
}

run_tests() {
  export ANDROID_SERIAL="${SERIAL}"
  "${ROOT}/gradlew.bat" :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.package='cn.limpu.hita.e2e' \
    --console=plain | tee "${OUT}/e2e-run.log"
}

case "${1:-run}" in
  boot) boot ;;
  install) install_apk ;;
  test) run_tests ;;
  run) boot; install_apk; run_tests ;;
  *)
    echo "未知子命令: $1（可用 boot|install|test|run）" >&2
    exit 2
    ;;
esac
