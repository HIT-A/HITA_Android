#!/usr/bin/env bash
# HITA 测试集运行器：六个测试集，输出耗时与结果摘要。
#
# 用法:
#   scripts/test-suites.sh <fast|full|data|instrumentation|e2e|perf|all>
#
# 环境变量:
#   JAVA_HOME       必须指向 JDK 21（项目要求）
#   ANDROID_SERIAL  指定设备序列号；未设置时使用唯一在线设备
#
# 说明:
#   - 本脚本只负责调度与计时，不修改测试代码。
#   - 结果以 Gradle 生成的 JUnit XML 为准，脚本不伪造通过状态。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${ROOT}/build/test-reports"
mkdir -p "${OUT_DIR}"

GRADLE_ARGS=(--console=plain --no-parallel)
if [[ -n "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME
fi
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  export ANDROID_SERIAL
fi

now_ms() {
  # Windows Git Bash 的 date 支持 %s%3N 不稳定，退化为秒级
  date +%s
}

run_suite() {
  local name="$1"; shift
  local start end elapsed status
  start="$(now_ms)"
  set +e
  "$@" > "${OUT_DIR}/${name}.log" 2>&1
  status=$?
  set -e
  end="$(now_ms)"
  elapsed=$(( end - start ))
  printf '%-16s status=%-3s elapsed=%ss log=%s\n' \
    "${name}" "${status}" "${elapsed}" "${OUT_DIR}/${name}.log"
  printf '{"suite":"%s","status":%s,"elapsedSeconds":%s}\n' \
    "${name}" "${status}" "${elapsed}" >> "${OUT_DIR}/suites.jsonl"
  return "${status}"
}

gradle() {
  "${ROOT}/gradlew.bat" "$@"
}

case "${1:-all}" in
  fast)
    run_suite fast \
      gradle :timetable-core:test :component:testDebugUnitTest :hitauser:testDebugUnitTest \
             :app:testDebugUnitTest --tests 'cn.limpu.hita.ui.main.*' --tests 'cn.limpu.hita.utils.*' \
             "${GRADLE_ARGS[@]}"
    ;;
  full)
    run_suite full \
      gradle :timetable-core:test :component:testDebugUnitTest :hitauser:testDebugUnitTest \
             :app:testDebugUnitTest "${GRADLE_ARGS[@]}"
    ;;
  data)
    run_suite data \
      gradle :app:testDebugUnitTest --tests 'cn.limpu.hita.data.*' \
             "${GRADLE_ARGS[@]}"
    run_suite data-device \
      gradle :app:connectedDebugAndroidTest \
             -Pandroid.testInstrumentationRunnerArguments.package='cn.limpu.hita.data' \
             "${GRADLE_ARGS[@]}"
    ;;
  instrumentation)
    run_suite instrumentation \
      gradle :component:connectedDebugAndroidTest :hitauser:connectedDebugAndroidTest \
             :style:connectedDebugAndroidTest :app:connectedDebugAndroidTest \
             "${GRADLE_ARGS[@]}"
    ;;
  e2e)
    run_suite e2e \
      gradle :app:connectedDebugAndroidTest \
             -Pandroid.testInstrumentationRunnerArguments.class='cn.limpu.hita.e2e.StartupNavigationE2eTest' \
             "${GRADLE_ARGS[@]}"
    ;;
  perf)
    run_suite perf \
      gradle :app:testDebugUnitTest --tests 'cn.limpu.hita.*PerfTest' "${GRADLE_ARGS[@]}"
    ;;
  all)
    "$0" fast
    "$0" full
    "$0" data
    "$0" instrumentation
    "$0" e2e
    ;;
  *)
    echo "未知测试集: $1" >&2
    echo "可用: fast|full|data|instrumentation|e2e|perf|all" >&2
    exit 2
    ;;
esac
