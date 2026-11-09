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
