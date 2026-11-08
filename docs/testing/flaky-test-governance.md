# Flaky Test 治理报告（阶段一·分析）

## 实测清单

| ID | 位置 | 类型 | 风险 | 处置 |
|---|---|---|---|---|
| F1 | `UsageQueueInstrumentedTest:56` `while (...) Thread.sleep(50)` | 轮询 sleep | 高（5000ms 内不完成即失败） | 建议改为 `Awaitility` 风格的有界轮询 helper（原子条件 + 10ms 递增间隔），属 app data 包，非专有 → **可改** |
| F2 | `UsageQueueInstrumentedTest:59` `Thread.sleep(200)` | 固定等待 | 中（依赖异步落盘时序） | 改为等待库状态可观测条件 |
| F3 | `TermRepositoryContractHarness:342` `Thread.sleep(10)` | 固定等待 | 低-中 | harness 属公共设施 → **可改** |
| F4 | `ManualCourseCreationInstrumentedTest:68` `Thread.sleep(50)` | 固定等待 | 中 | 属 agent-03-ui 范围 → **只记录 T-F4** |
| F5 | `MainActivityInstrumentedTest:173` `Thread.sleep(20)` | 固定等待 | 低 | agent-03 范围 → 记录 |
| F6 | `MainThemeInstrumentedTest:65` `SystemClock.sleep(50)` | 轮询 | 低 | agent-03 范围 → 记录 |
| F7 | `HomeworkUiInstrumentedTest:205` `Thread.sleep(50)` | 固定等待 | 中 | agent-03 范围 → 记录 |
| F8 | `TimetableBatchUiInstrumentedTest:275` `SystemClock.sleep(25)` | 固定等待 | 低 | agent-03 范围 → 记录 |
| F9 | `TimetableComponentInstrumentedTest:81,200,216` `sleep(350/16/25)` | 负向等待 | 中 | agent-03 范围 → 记录 |
| F10 | `TimetableHostInstrumentedTest:156` `sleep(25)` | 固定等待 | 低 | agent-03 范围 → 记录 |
| F11 | `TimetableLegacyBoundaryInstrumentedTest:163` `sleep(25)` | 固定等待 | 低 | agent-03 范围 → 记录 |
| F12 | `CourseSelectionExecutorTest:804` `Thread.sleep(delayMillis)` | 模拟网络延迟 | 中 | 属 app data 包 → **可改为可控调度** |

## 共享状态风险

- 132 个测试使用 `CountDownLatch`：多数必要（跨线程同步证据），**不删除**。
- 42 个测试依赖真实时钟：`Outcome` 类逻辑应注入 `now`（如 `LiveCourseScheduler.reconcile(nowMillis)` 已有良好缝隙）。
- 15 个测试用 `Random`/`UUID.randomUUID`：UUID 用于唯一 ID 属合理用法，`Random` 需核实是否有断言依赖。

## 治理结论

- **可自行修复**：F1、F2、F3、F12（公共设施 / app 数据包测试，非其它 agent 专有）。
- **只记录并通知**：F4–F11（agent-03-ui 的 UI 测试文件）。
- 不引入 `Awaitility` 新依赖；用仓库内既有的 `waitUntil {}` 模式实现有界轮询。
