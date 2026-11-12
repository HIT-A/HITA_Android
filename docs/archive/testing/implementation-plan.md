# 测试体系实施计划（阶段二）

**目标**：补齐四类测试，建立六集合运行器，消除零覆盖后台任务，产出可复核的耗时与执行证据。
**架构**：不改生产代码；新增最小依赖；E2E 独立源集；执行环境私有隔离。

## Task 1：测试依赖最小化增补

**文件**：
- 修改 `gradle/libs.versions.toml`：新增 `uiautomator`、`workTesting`
- 修改 `app/build.gradle`：`androidTestImplementation(libs.uiautomator)`、`androidTestImplementation(libs.workTesting)`

**验收**：`:app:assembleDebugAndroidTest` 编译通过；`./gradlew :app:dependencies --configuration debugAndroidTestRuntimeClasspath` 含两依赖。

## Task 2：后台任务测试（Worker/Widget/通知）

**文件**：新建 `app/src/androidTest/java/cn/limpu/hita/data/work/CourseReminderWorkerInstrumentedTest.kt` 等 4 个文件
**步骤**：用 `WorkManagerTestInitHelper` + `TestDriver` 断言 `Result.success/failure`、重试策略、约束；`NotificationManager` 通过 `ShadowNotification` 不可用 → 用 `dumpsys`/`NotificationListenerService` 替代或在 UI Automator 层断言。

**验收**：新测试在 `emulator-5570` 上实际 PASS，XML 可解析。

## Task 3：UI Automator E2E 源集

**文件**：新建 `app/src/e2eTest/java/cn/limpu/hita/e2e/`（8 个脚本）+ `scripts/e2e/run.sh`
**改造**：`app/build.gradle` 增 `e2eTest` sourceSet，指向独立 runner。

**验收**：`./gradlew :app:e2eTestConnected` 真机跑通，输出 PASS/FAIL 与截图。

## Task 4：测试集运行器

**文件**：新建 `scripts/test-suites.sh`（fast/full/data/instrumentation/e2e/perf）
**验收**：每集合输出耗时并写 `build/test-reports/suite-<name>.json`。

## Task 5：Flaky 治理

**文件**：修改 11 处 sleep → `UiDevice.wait(Until...)` 或 latch；42 处真实时钟 → 注入 `Clock`。
**验收**：连续 3 次执行同测试全通过。

## Task 6：报告与耗时

**文件**：新建 `scripts/test-report.py` 聚合 XML → Markdown（模块/类型/耗时/失败/跳过）。
**验收**：生成报告内容与 XML 数值逐项一致。

## Task 7：清理

删除 `room-testing`（按 CL-1），修正 style JVM 口径文档。

## 依赖顺序

Task 1 → (Task 2, Task 3 并行) → Task 4 → Task 5 → Task 6 → Task 7
