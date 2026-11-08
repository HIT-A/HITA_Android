# 测试有效性审查报告（阶段一）

基线 `5992ba3` · 1896 个 `@Test`（文本计数）/ 1856 个可解析测试体

> 统计口径为**基线 commit 时的树**，不含本轮新增的 17 个用例（见 [test-ledger.md](test-ledger.md) 增量列）。

## 统计口径

对 1856 个 `@Test` 函数体做静态扫描，**逐类累计**断言数（含父类 harness 中的 `assert*`），避免把"单行委托到 harness"误判为零断言。

| 指标 | 值 |
|---|---|
| 直接断言 0 条、但通过 harness/helper 断言 | 48 |
| 直接断言 0 条且全类无任何断言 | **0** |
| 仅 1 条断言 | 336（18.1%） |
| 每测试断言中位数 | 3 |
| 每测试断言均值 | 3.67 |

结论：**不存在"完全无断言"的伪测试**；但存在**弱断言**结构，主要是单断言 + 依赖 harness 内部实现。

## 问题清单（带 ID）

### A 类：断言与验证方式

| ID | 问题 | 证据 | 影响 |
|---|---|---|---|
| T-A1 | 48 个测试用父类 harness 断言（如 `TermQueryCancellationContractTest` 23 项、`StrictShareJsonTest` 8 项委托 `invalid()`/`assertEquals`） | 静态扫描 `assert_v2.json` | 断言隐藏于基类，失败信息定位差，子类新增用例可能"继承"到错误语义 |
| T-A2 | 336 个单断言测试，多数只校验一个终态值 | `assert_v2.json` | 无法发现中间态错误（如 LiveData 只取最后一项） |
| T-A3 | 823 个仪器测试中 **0 个 Espresso/Compose UI 断言** | `grep onView`/`createComposeRule` 全仓为空 | 声明的 "UI 测试" 实际只验证 ViewModel/契约，**界面渲染与交互完全未验证** |
| T-A4 | 11 个文件用 `ActivityScenario.launch` 但无 UI 元素断言 | 仅 12 处 launch | Activity 能否启动、崩溃、重建均未真正断言 |

### B 类：覆盖缺口（生产类被测试引用计数）

| ID | 缺失能力 | 生产类 | 测试引用 |
|---|---|---|---|
| T-B1 | 课表提醒 Worker | `CourseReminderWorker` / `CourseReminderScheduler` | 0 |
| T-B2 | 成绩提醒 Worker | `ScoreReminderWorker` / `ScoreReminderScheduler` | 1（仅 `ScoreReminderSessionTest` 间接） |
| T-B3 | Widget 刷新链 | `WidgetRefreshWorker` / `WidgetRefreshScheduler` / `WidgetUtils` / `TodayUtils` | 0 |
| T-B4 | 直播课程调度 | `LiveCourseScheduler` | 0 |
| T-B5 | 应用内更新协调 | `UpdateFlowCoordinator` | 0 |
| T-B6 | 登录模拟链（WebView/CAS） | `PopUpLoginEAS` / `WebViewLoginActivity` | 0 |
| T-B7 | 通知构建（常量与渠道） | `AppConstants.Notification` | 0（渠道创建由 `CourseReminderWorker` 内部完成，已由新增 Worker 测试覆盖） |
| T-B8 | 选课闹钟调度 | `CourseSelectionAlarmScheduler`（1 处 harness 偶然引用，无断言） | ≈0 |

> 说明：B 类缺口与任务书"通知、Widget 和后台任务"要求直接对应；当前**完全没有**覆盖。

### C 类：Flaky / 非确定性

| ID | 问题 | 证据 |
|---|---|---|
| T-C1 | 11 处真实 `Thread.sleep`/`SystemClock.sleep`（含 `SystemClock.sleep(350)`） | `UsageQueueInstrumentedTest:56,59`、`TimetableComponentInstrumentedTest:81,200,216`、`HomeworkUiInstrumentedTest:205` 等 |
| T-C2 | 132 个测试依赖 `CountDownLatch`/`await`，243 处 latch 使用在 androidTest | 静态扫描 |
| T-C3 | 42 个测试依赖 `System.currentTimeMillis()`/`LocalDate.now()` 等真实时钟 | 静态扫描 |
| T-C4 | 15 个测试使用 `Random`/`UUID.randomUUID` | 静态扫描 |
| T-C5 | 72 处测试直接引用 `http://`/`URL(`/socket，若未全部走 MockWebServer 则联网依赖 | 待逐处核实 |

### D 类：等价重复 / 可清理

| ID | 问题 | 证据 |
|---|---|---|
| T-D1 | `room-testing` 依赖在 4 个模块声明但**无人使用**（文档也确认 `MigrationTestHelper` 不可用） | `app/build.gradle:166` + handoff 文档 |
| T-D2 | `style/src/test` 源集不存在，但 build 声明 `testImplementation(junit)` 且 CI 跑 `:style:testDebugUnitTest`（恒为 NO-SOURCE） | `style/build.gradle:52` |
| T-D3 | `app/src/androidTest/.../eas/` 下 30+ 个 `*ContractTest` + 14 个 `*Harness`，存在大量同构样板 | 目录统计 |
| T-D4 | `LegacyAppDatabase` 与升级测试自建 helper，绕过 `room-testing` | 属既有决策，保留 |

### E 类：测试基础设施

| ID | 问题 | 影响 |
|---|---|---|
| T-E1 | JVM 侧 70 处 `runBlocking`，无 `coroutines-test` | 无法虚拟时间、无法断言调度顺序、真延时拖慢 |
| T-E2 | 无 `mockk`/`mockito`/`truth` | 只能手写 fake，重复代码多 |
| T-E3 | 无 JaCoCo / 无覆盖率数据 | 任务书第 8 项"覆盖率结果"当前无法产出 |
| T-E4 | 无变异测试工具（PIT） | 任务书第 8 项"变异测试"需引入 |
| T-E5 | 无 UI Automator / 无 benchmark 模块 | 第四类"模拟机黑盒 E2E"与"性能专项"完全没有基础设施 |
| T-E6 | 无统一测试报告聚合与耗时报告 | 只有 Gradle 原生 XML/HTML，无跨模块汇总 |

### F 类：环境与并行

| ID | 问题 | 证据 |
|---|---|---|
| T-F1 | 多 agent 并行时 `:app:testDebugUnitTest` 会因 Gradle test-worker 端口争用崩溃（`Gradle Test Executor 4` 非零退出） | 本会话实测，849s 后失败 |
| T-F2 | 共享 `emulator-5554` 上同时运行多个 agent 的 app（检测到 `cn.limpu.hita.diag` 进程） | `adb shell ps` |
| T-F3 | 默认 Gradle 守护进程无隔离，6 个并发 Gradle 构建 | `Get-CimInstance java.exe` |

## 与任务书要求的差距矩阵

| 任务书要求 | 现状 | 结论 |
|---|---|---|
| 第一类 基本测试 | JVM 单测 1007 个，Lint/类型检查由编译承担 | 基本具备，缺 lint 任务显式化 |
| 第二类 数据测试 | Room/DAO/迁移/解析/序列化较完整（迁移链+升级路径+触发注入） | 基本具备，缺 JSON 边界与历史兼容扩展 |
| 第三类 仪器测试 | 849 个，但 0 个 UI 断言 | **部分具备**，UI 层未验证 |
| 第四类 模拟机测试 | **完全没有**（无 UI Automator、无黑盒脚本） | **缺失** |
