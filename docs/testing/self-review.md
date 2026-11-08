# 阶段一自审记录

## 审阅方式

主审由本 agent 亲自完成（旁路 subagent 审阅因停滞已终止）。核对手段：逐项比对源码符号、重算统计数字、检查断言强度。

## 核对项与结论

| # | 核对项 | 结论 | 证据 |
|---|---|---|---|
| 1 | 新增测试引用的生产类/方法真实存在且签名匹配 | ✅ 全部通过 | `persistSelection`（`DisplayScopeResolver.kt:104`，`internal`，同模块可访问）；`WidgetUtils.widgets/hasAnyWidget/sendRefreshToAll/EVENT_REFRESH`（`WidgetUtils.kt:11-21`）；`TimetableDao.saveTimetableSync:127`/`deleteTimetablesInIdsSync:136`；`EventItemDao.insertEventSync:173`/`deleteEventsFromTimetablesSync:102`；`LiveCourseScheduler.reconcile/cancel`；`LiveCourseSettings.isEnabled/setEnabled` |
| 2 | `uiautomator` / `work-testing` 已正确声明 | ✅ 通过 | `libs.versions.toml` 新增 `uiautomator = "2.3.0"` + `workTesting`；`app/build.gradle` 两条 `androidTestImplementation`；**实测依赖解析成功**（`debugAndroidTestRuntimeClasspath` 出现 `androidx.test.uiautomator:uiautomator:2.3.0`、`androidx.work:work-testing:2.9.1`） |
| 3 | 无断言/恒真断言 | ✅ 已修正 | 修正 `WidgetRefreshWorkerInstrumentedTest` 一个命名与断言不符的用例（原名 `workerReturnsSuccessEvenWithoutInstalledWidgets` 未验证前提），改为断言「无已安装 Widget」前提 + 不广播 + Result.success |
| 4 | 文档数字与仓库实际是否一致 | ✅ 已修正 | 台账原写 androidTest 124 文件/823 测试（基线值），本轮新增 5 文件/17 用例后重算为 128/840，已标注「基线 → 当前」两列；有效性报告已注明统计口径为基线树 |
| 5 | 是否违反约束 3/4/5 | ✅ 未违反 | 未使用 `MigrationTestHelper`；未修改 `style` 的 JVM 口径（仅在文档澄清）；生产代码、CI、其它 agent 的测试文件均未改动（`git status` 只有本 agent 文件） |
| 6 | `test-report.py` 是否会把未执行误报为通过 | ✅ 已实测 | 运行输出：`app-instrumented | 0 | — | — | — | — | — | — |`；未执行模块显示为「—」不计入合计通过数 |

## 修正清单（审阅中发现并已修复）

| 问题 | 位置 | 修复 |
|---|---|---|
| **虚构类名** `NotificationHelper` 在生产代码中不存在 | `feature-test-mapping.md:49`、`test-cleanup-plan.md:53`、`test-effectiveness-review.md:40` | 改为真实的 `AppConstants.Notification`，并注明通知渠道由 Worker 内部创建 |
| 弱断言/命名不符 | `WidgetRefreshWorkerInstrumentedTest` | 重写为断言前提 + 行为 |
| 统计数字过时 | `test-ledger.md` | 更新为基线→当前增量 |
| 统计口径未声明 | `test-effectiveness-review.md` | 增加口径说明 |

## 未通过的核对项

| 项 | 原因 | 处置 |
|---|---|---|
| 编译验证 | 系统内存被其它 5 个 agent 的 Gradle/模拟器占满（最低 4 MB 可用），构建多次中断 | 正在重试（`final-compile.log`）；只有实际编译通过才记为通过 |
| 仪器/E2E 执行 | 依赖编译产物 | 同上 |

## 结论

阶段一文档的事实准确性已核对通过（发现 1 处虚构、1 处弱断言、1 处数字过期并已全部修复）；约束合规性通过。
**阶段二的"已通过"结论不成立**，因为仪器与 E2E 测试尚未实际执行——文档中已如实标注为未验证。
