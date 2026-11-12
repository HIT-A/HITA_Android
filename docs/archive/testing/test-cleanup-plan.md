# 测试清理计划（阶段一 · 每个删除项五要素记录）

## 删除规则（不可协商）

仅当满足以下**全部**条件才删除：①重复验证同一行为；②有等价替代；③替代测试已实际执行通过；④不降低断言强度；⑤有本表记录。禁止因"慢/不稳/失败"删除。

## 待清理项

### CL-1 `room-testing` 依赖（4 个模块）

| 要素 | 内容 |
|---|---|
| 原测试目的 | 提供 `MigrationTestHelper` 做 Room 迁移测试 |
| 删除理由 | 依赖存在但**无人使用**；项目文档明确该 helper 因 kotlinx-serialization 1.8 冲突而不可用（设备端必然 `AbstractMethodError`） |
| 是否重复验证 | 否——它没有验证任何东西 |
| 替代位置 | `AppDatabaseMigrationChainTest`（JVM，迁移链连续性）；`AppDatabaseUpgradePathTest`（设备，真实升级 + 数据保留）；`createLegacyAppDatabase` 自建旧库 |
| 替代测试结果 | JVM 1007 项全通过（含 `AppDatabaseMigrationChainTest` 1 项）；设备 849 项待本轮实测 |
| 影响文件 | `app/build.gradle:166`、`component/build.gradle`(mockWebServerLegacy 保留)、`hitauser/build.gradle`、`gradle/libs.versions.toml` |

### CL-2 `style/src/test` 的 `testImplementation(junit)` 与 NO-SOURCE 任务

| 要素 | 内容 |
|---|---|
| 原测试目的 | 使 style 模块有 JVM 单测 |
| 删除理由 | `style/src/test` 源集**不存在**，CI 每次跑 `:style:testDebugUnitTest` 恒为 `NO-SOURCE`，README_DEV 也承认"不计作测试通过"——是**虚假信号** |
| 是否重复验证 | 否 |
| 替代位置 | style 的真实测试在 `style/src/androidTest`（`ThemeToolsInstrumentedTest`、`SelectableAdaptersInstrumentedTest`，共 8 项） |
| 替代测试结果 | 待设备实测 |
| 处置 | 保留 `testImplementation(libs.junit)` 无害；**但报告与 CI 说明必须明确 style JVM 无测试**，不得计入覆盖率 |

> 说明：CL-2 只做文档与报告口径修正，不删依赖（避免改 CI，CI 属禁改范围）。

### CL-3 `app/src/test/.../agent/core/ManualAgentScheduler.kt`

| 要素 | 内容 |
|---|---|
| 原测试目的 | 手动推进 Agent 调度的测试替身 |
| 删除理由 | **不删除**——这是被多个测试使用的有效替身。记录在此仅为了说明"非测试文件混在 test 源集"属正常模式 |
| 处置 | 保留 |

## 明确不清理（保留并在报告中说明）

| 项 | 理由 |
|---|---|
| 33 个 `*ContractTest` | 同构样板多，但每个覆盖不同校区/不同端点/不同错误分支，不重复 |
| 14 个 `*Harness` | 共享断言逻辑，删除会削弱断言集中度 |
| `StrictShareJsonTest` 的 `invalid()` 委托模式 | 每行一个边界用例，断言集中在 helper，**不弱** |
| `CountDownLatch` 用法（132 测试） | 多数是**必要的**跨线程同步证据，不能改成 sleep |

## 待补测（阶段二，先补后改）

按影响排序：
1. `CourseReminderWorker` / `WidgetRefreshWorker` / `LiveCourseScheduler`（后台任务零覆盖；均已由本轮新增测试覆盖）
2. 登录 WebView 链
3. UI Automator 8 组 E2E
