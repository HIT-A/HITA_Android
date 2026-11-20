# 测试删除与替代依据（完整台账）

本表按任务书第 6 条要求，对**每个删除/替换项**记录五要素。本轮仅一项真实删除。

## CL-1：移除 `room-testing` 依赖

| 要素 | 内容 |
|---|---|
| **原测试目的** | 通过 `androidx.room:room-testing` 的 `MigrationTestHelper`，用导出 schema 驱动真实迁移测试 |
| **删除理由** | 全仓库无任何代码使用该 API（`grep` 全量确认）；且项目已记录该依赖不可用：其传递依赖 `kotlinx-serialization 1.8` 与 app 经 lifecycle 锁定的 core 1.7.3 冲突，设备上必然抛 `AbstractMethodError`。保留它只是增加解析负担与误用风险 |
| **是否重复验证同一行为** | 否——它没有验证任何行为，属零价值依赖 |
| **替代测试位置** | 1) `app/src/test/.../AppDatabaseMigrationChainTest.kt`（JVM，断言迁移链 1→18 连续无缺口）<br>2) `app/src/androidTest/.../AppDatabaseUpgradePathTest.kt`（设备，真实建旧库→升级→逐字段校验数据保留）<br>3) `app/src/androidTest/.../data/LegacyAppDatabase.kt` 的 `createLegacyAppDatabase(name, version)` 自建旧版本库，绕开不可用的 helper |
| **替代测试结果** | `AppDatabaseMigrationChainTest` 在 JVM 全量运行中通过（1007 项 / 0 失败，XML 为证）。`AppDatabaseUpgradePathTest` 属设备测试，**本轮因并行构建资源竞争尚未取得执行结果**，不记为通过 |

## 变更清单

| 文件 | 变更 |
|---|---|
| `gradle/libs.versions.toml` | 删除 `roomTesting` 库条目 |
| `app/build.gradle` | 删除 `androidTestImplementation(libs.roomTesting)` |
| `component/build.gradle`、`hitauser/build.gradle` | 未含该依赖，无需改动（已核实） |

## 未删除但记录为噪声的项

| 项 | 为何保留 |
|---|---|
| `style/src/test` 不存在但声明 `testImplementation(libs.junit)` | 保留无害；风险在**报告口径**（`testDebugUnitTest` 恒为 NO-SOURCE），已在 `README.md` 与 `timing-comparison.md` 明确"不计作测试通过" |
| `ManualAgentScheduler.kt` 位于 `app/src/test` | 是被多个测试使用的有效替身，非死代码 |
| 33 个 `*ContractTest` | 每个覆盖不同校区/端点/错误分支，非重复 |

## 明令禁止的做法（本轮未做，且不会做）

- 未因测试慢而删除任何用例。
- 未因 flaky 而删除任何用例。
- 未降低任何断言强度。
- 未添加 `@Ignore`。

## CL-2：2026-11-20 修复 API 35 设备测试的陈旧断言

基线 `88315a23`。17 项设备测试在 CI（run 44666624777）与上一版 3.3.0（run 44657812316）同时失败，17 项完全相同，说明不是当次代码引入。根因全部是被测契约在 v20/v21 演进后测试未跟随，**没有修改任何生产代码**。

| 编号 | 原测试目的 | 替换理由（生产契约） | 替代断言 | 实测结果 |
|---|---|---|---|---|
| CL-2a | `DeletionHistoryInstrumentedTest` 用 `SOURCE_EAS_IMPORT` 验证删除写历史 | v21 起教务课程 `Delete` 只写 `teaching_visibility`（`TimetableRepository.kt:1281-1308`），不再进删除历史 | 断言隐藏写入可见性表、课程行不变、历史计数为 0；展示后可见性归 0。仍保留“手工课删除可恢复”与原样的“源数据被改后旧删除记录不可覆盖”（直接种入 v19 时代的 `EventEditHistoryStore.delete` 记录） | 通过 |
| CL-2b | 课表批处理/宿主/生命周期用例用默认 `EventItem` 走真实写路径 | `EventItem` 默认 `CLASS + EAS_IMPORT`，已属教务只读课程，批量编辑在设计上被拒 | 夹具显式改为 `CLASS + SOURCE_MANUAL`，仍走真实写、注入失败与生命周期断言 | 通过 |
| CL-2c | `TimetableShareMigrationTest` 只迁移到 18 | `AppDatabase.VERSION = 21`，迁移链须到 21 才代表真实升级 | 改用 `ALL_MIGRATIONS`；v14 期望补 `groupId`(v16) 与 `hidden`(v19) 列；补 outbox 表存在的检查 | 通过 |
| CL-2d | `TodayScreenInstrumentedTest` 等 `today_homework_title` | 独立待办页标题为 `R.string.title_homework`（`HomeworkActivity.kt:63`），两者是不同字符串 | 改断言真实标题资源 | 通过 |
| CL-2e | `TermSessionIntegrationInstrumentedTest` 用 `drain()` 等全部子 Job | `applicationScope` 从 v20 起还常驻考试会话收集器，`joinAll()` 永不返回 | 只等待本次登出/重放新启动的清理 Job，20s 超时不变；新增“登出已尝试删除、失败后旧行仍在”的证据断言 | 通过 |
| CL-2f | `TimetableRecoveryLegacyBoundaryTest` 期望 adopt 后有一条旧快照文件 | `TimetableChangeRecovery.kt:45` 只写 `timetable_version`，不再写旧快照 | 断言旧快照为空，且 `INITIAL`=Original、`SOURCE`=Incoming；手工课、本地改名、组合、heldBatch/decisions/vetoes 断言全部保留 | 通过 |
| CL-2g | `TimetableSnapshotFlowInstrumentedTest` 依赖导入隐式产生旧快照 | `EASTimetableImportRepository.kt:207` 只记版本 | 明确种入兼容快照；取消/锁/提交边界断言不变。恢复用例改为直读 `timetable_version` 表断言 `[SOURCE, INITIAL]` 及两版内容，并断言兼容快照仍为 1 条 | 通过 |
| CL-2h | `CampusSessionCookiesInstrumentedTest` 假设设备上没有残留会话 Cookie | `CookieManager` 在同一设备跨 instrumentation 运行持久化 | 断言前显式删除三条路径上的同名 Cookie；断言本身不变 | 通过 |

`versionNames` 直接读 `timetableVersionDao.summaries/get`，不经 `TimetableVersionStore.list()`：后者在无版本行时会自动补 `INITIAL`（`TimetableVersionStore.kt:131-136`），会使该断言失去证明力。

验证：`emulator-5570`（API 35，`-read-only`）上 `:app:connectedDebugAndroidTest` 979/979 通过，`:component` 7/7、`:hitauser` 11/11、`:style` 8/8 通过；`assembleDebug`、四模块 `testDebugUnitTest`、`:timetable-core:test`、`python scripts/check_repository.py` 均通过。

## 未删除但记录为噪声的项
