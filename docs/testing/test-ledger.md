# 全量测试台账（阶段一）

生成时间：2026-11-08 · 分支 `refactor/agent-05-testing` · 基线 `5992ba381a9d6e87deb7526885f3d957b8a6d09b`

## 1. 测试资产总览（静态统计，来自源码扫描）

| 源集 | 测试文件 | 行数 | `@Test` 数 |
|---|---|---|---|
| app/src/test | 162 | 16305 | 939 |
| app/src/androidTest | 124 → **128**（+4 新增文件） | 23914 → 24900 | 823 → **840**（+17 新增用例） |
| component/src/test | 1 | 104 | 7 |
| component/src/androidTest | 3 | 258 | 7 |
| hitauser/src/test | 2 | 49 | 4 |
| hitauser/src/androidTest | 4 | 416 | 11 |
| style/src/test | 0（不存在） | 0 | 0 |
| style/src/androidTest | 3 | 313 | 8 |
| timetable-core/src/test | 6 | 535 | 57 |
| timetable-core/src/testFixtures | 1 | 13 | 0（仅 `ShareFixtures`） |
| **合计** | **306 → 311** | **41807 → 42900** | **1856 → 1874** |

> 说明：`@Test` 计数为文本计数，含少量被注释掉的用例；实际执行数以 Gradle XML 为准（见 §5）。
> 本轮（agent-05）新增 5 个 androidTest 文件、17 个用例：`CourseReminderWorkerInstrumentedTest`(5)、`WidgetRefreshWorkerInstrumentedTest`(3)、`LiveCourseSchedulerInstrumentedTest`(4)、`StartupNavigationE2eTest`(2)、`NavigationAndPersistenceE2eTest`(3)。

## 2. 类型分布

| 类型 | 数量 | 说明 |
|---|---|---|
| 纯 JVM 单元测试 | 1007（app 939 + core 57 + component 7 + hitauser 4） | 无 Android 运行时 |
| 仪器测试（androidTest） | 849（app 823 + hitauser 11 + style 8 + component 7） | 真机/模拟器 Instrumentation |
| UI 层仪器测试实际驱动 Activity 的 | 12 处 `ActivityScenario.launch`（11 个文件） | 其余为 ViewModel/LiveData/Repository 契约测试 |
| Espresso / Compose 断言 | **0** | 全仓库无 `onView`、`createAndroidComposeRule`、`ui-test-junit4` |
| UI Automator 黑盒 E2E | **0** | 无 `uiautomator` 依赖，无 `UiDevice`/`UiSelector` |

## 3. 执行成本基线

首次全链路冷构建 + JVM 全量：**849s**（`assembleDebug` + `testDebugUnitTest`，含 kapt/Hilt/Room、115 个 task）。
其中 app 模块 `testDebugUnitTest` 因并行 Gradle 实例争用 test-worker 端口首次崩溃（`Gradle Test Executor 4` 非零退出），属**环境竞态**，非测试缺陷；已限 `--max-workers=2` 重跑。

CI 参考：`build-and-test` 约 6 分钟，`device-tests` 约 7–45 分钟（历史记录）。

## 4. 测试夹具与辅助设施

| 设施 | 位置 | 说明 |
|---|---|---|
| `ShareFixtures` | `timetable-core/src/testFixtures` | 跨模块共享快照样例，经 `testFixtures` 消费 |
| `LegacyAppDatabase.createLegacyAppDatabase` | `app/src/androidTest/.../data/LegacyAppDatabase.kt` | 建旧版本库（替代不可用的 `MigrationTestHelper`） |
| `TermQueryContractHarness` 等 14 个 `*Harness.kt` | app/src/androidTest | 契约测试基类，含共享断言 helper |
| `IsolatedPreferencesContext` | `style`、`hitauser` 的 `testing/TestSupport.kt` | 模块内 SharedPreferences 隔离 |
| `IsolatedApplication` | 多个 androidTest | 覆盖 `filesDir()`，隔离 JSON 存储 |
| `ManualAgentScheduler.kt` | app/src/test | 手动调度器替身 |

**缺口**：JVM 侧无 `coroutines-test`（70 处 `runBlocking`）、无 `mockk`/`mockito`、无 `truth`；无 JaCoCo；无 PIT/变异测试工具。

## 5. 实际执行结果

| 任务 | 状态 | 证据 |
|---|---|---|
| `:timetable-core:test` | 通过 | 6 个 XML：`timetable-core/build/test-results/test/TEST-*.xml` |
| `:component:testDebugUnitTest` | 通过 | `component/build/test-results/.../TEST-com.limpu.component.data.ResultContractTest.xml` |
| `:hitauser:testDebugUnitTest` | 通过 | 2 个 XML |
| `:style:testDebugUnitTest` | **NO-SOURCE** | README_DEV 已声明，不计作测试通过 |
| `:app:testDebugUnitTest` | 重跑中（首次环境竞态失败） | 待 `baseline-app-unit.log` |

## 6. 已知约束（来自 docs/refactor-handoff.md）

- 禁用 `room-testing` 的 `MigrationTestHelper`（kotlinx-serialization 1.8 冲突，设备必然 `AbstractMethodError`）；`room-testing` 依赖目前无人使用，属**应清理的死依赖**。
- 不得用静态测试数代替实际 JUnit XML。
- 历史要求：保留原测试身份与断言，不得删改旧断言来变绿。
