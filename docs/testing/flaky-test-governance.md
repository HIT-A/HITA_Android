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


## 实测发现的 Flaky（本轮新增测试，均已按根因修复）

| ID | 现象 | 根因（诊断证据） | 修复 |
|---|---|---|---|
| F13 | `LiveCourseSchedulerInstrumentedTest#cancelClearsPublishedNotification` 在全量批次中偶发失败（`expected:<0> but was:<1>`），单独运行通过 | 通知由被测进程发布/取消，而测试进程 `getActiveNotifications()` 读到缓存旧视图；且系统清理异步 | 改为解析 `dumpsys notification --noredact` 的 `Notification List:` → `Notification attention state:` 这一段，按 `pkg=<包名>` + `channel=hita_live_course` 精确匹配，并给出 3s 有界轮询窗口 |
| F14 | 同一断言在修复中途误报 `expected:<0> but was:<2>` | `dumpsys` 尾部的历史/统计段落同样包含 channel 名称，纯文本 `split("NotificationRecord")` 会重复计数 | 限定解析区间到活动通知段（同上） |
| F15 | `StartupLatencyPerfTest` 首轮 `Instrumentation run failed due to Process crashed` | 用 `am force-stop <被测包>` 制造冷启动，会连带杀掉同应用组的测试进程 | 不再 force-stop；改测"发出启动 Intent → 主窗口出现"的真实耗时 |
| F16 | `LiveCourseSchedulerInstrumentedTest` 全量首跑 2/4 失败（通知未发布） | 测试进程未初始化 WorkManager，`reconcile()` 内 `WorkManager.getInstance()` 抛异常导致通知路径被跳过 | `@Before` 中 `WorkManagerTestInitHelper.initializeTestWorkManager(app)` |

上述均为**测试侧缺陷**，非生产代码缺陷；诊断通过一次性断言输出真实前置条件（如
`canPost=true enabled=true scope=Single(...) states=[InClass(...)]`）完成，诊断代码已移除。

## 治理结论

- **可自行修复**：F1、F2、F3、F12（公共设施 / app 数据包测试，非其它 agent 专有）。
- **只记录并通知**：F4–F11（agent-03-ui 的 UI 测试文件）。
- 不引入 `Awaitility` 新依赖；用仓库内既有的 `waitUntil {}` 模式实现有界轮询。
