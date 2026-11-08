# Agent 03 — UI 重构交付报告

- 基线：`5992ba381a9d6e87deb7526885f3d957b8a6d09b`
- 分支：`refactor/agent-03-ui`
- Worktree：`.worktrees/agent-03-ui`
- 独立模拟机：AVD `hita_agent03` / `emulator-5560`（与并行 agent 的 5570/5574 隔离）
- JDK：`~/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2`（21.0.10；本机默认 JDK 25 无法配置 AGP 8.10.1 测试任务）

## 1. 架构对比

详见 `docs/agent-03-ui-review.md` §3。结论：

| 方案 | 概要 | 取舍 |
|---|---|---|
| A 就地状态收敛 | 每页把已有 `mutableStateOf` + `bindLiveData()` 收敛为单一不可变页面状态，Composable 只接 state + 回调 | 与现有 MVVM 一致、无新依赖、可增量；风险低 |
| B 抽离 UI 状态层 + 事件通道 | 引入 `PageStateHolder` + `UiEffect` 单向事件，Activity 退化为宿主 | 可完整单测，但一次性改动大、与并行数据层改动耦合 |

**采用 A**，并对 `MainActivity`（已有 `MainEffect`）与 catalog 的 Toast 链做局部事件化。

## 2. 页面改造清单

（见 §4 提交清单与 `docs/agent-03-ui-review.md` §10 实施记录）

## 3. 功能保持

未改动：导航图、Activity/Fragment 结构、视觉样式、Repository/DAO 契约、数据库版本、字符串以外资源。

行为等价性检查点：
- catalog 状态机分支从 `when` 收敛为投影函数，`NOTHING` 仍表示"不改变现状"，课程失败仍落 `Ready(message)`（与改前一致）。
- 时间线首末行、进行中/即将考试判定改为纯函数，输入同为事件列表与当前时刻。
- 成绩列表由 Card 内 Column 改为逐行 Surface，首末圆角与水平留白保持一致。

## 4. 提交清单

| 提交 | 内容 |
|---|---|
| `ed4cc3f` | `refactor(ui): 收敛时间线/课表/课程浏览的 UI 状态与重组开销` —— 本次全部改动（11 个既有文件修改、5 个新增文件、2 篇文档） |

分支：`refactor/agent-03-ui`（基于 `5992ba3`），未推送、未合并。

## 5. 测试结果

### JVM 单测

`./gradlew :app:testDebugUnitTest`（JDK 21）→ **BUILD SUCCESSFUL**

| 项 | 基线（5992ba3） | 本次 |
|---|---|---|
| 用例总数 | 939 | **960** |
| 失败 | 0 | **0** |
| 跳过 | 1（既有可选联网测试） | **1**（同一个） |

新增 21 项：

| 测试类 | 项数 | 覆盖 |
|---|---|---|
| `TimelineListProjectionTest` | 7 | 首末索引跳过提示卡、全提示卡返回 -1、逐行 isFirst/isLast、进行中唯一、进度随传入时钟推进、考试倒计时随时钟失效、空列表不崩溃 |
| `CatalogUiStateProjectionTest` | 10 | 学期/课程的成功、失败、未登录、NOTHING、不可用、空列表、后端文案优先、同值可比较（可跳过重组） |
| `HomeworkDayPartitionsTest` | 4 | 有日期分区、无日期分区、空日仍保留全局无日期计数、重复调用确定性 |

红→绿证据：`CatalogUiStateProjectionTest` 首轮 2 项 FAILED（学期失败被错误折叠为 `Ready`），修正投影后转绿 —— 说明新测试确实约束了行为而非空跑。

### 仪器测试（`am instrument` 直连 `emulator-5560`，避免多 AVD 干扰）

| 测试类 | 结果 |
|---|---|
| `TimelineProjectionInstrumentedTest`（新增） | **OK (1 test)** —— 真实 Room 写入三段事件后，投影给出正确首末/进行中/进度 |
| `MainActivityInstrumentedTest`（既有） | **OK (6 tests)** —— Tab 切换、recreate、状态保存、返回键、effect 归属、待办 Tab 重挂载 |
| `TimetableHostInstrumentedTest`（既有） | **OK (1 test)** —— 真实 Tab 挂载、detach/attach、`viewOwner` 失效、Activity 重建 |
| `TimetableComponentInstrumentedTest`（既有） | **OK (3 tests)** |
| `TimetableBatchUiInstrumentedTest`（既有） | **OK (2 tests)** |
| `SubjectViewModelInstrumentedTest`（既有） | **OK (7 tests)** |
| `SubjectActivityInstrumentedTest`（既有） | **OK (1 test)** |

### 一处由本次改动引入、已定位并修复的回归

首版把 `@Composable hitaCoursePalette()` 的返回值直接当 `remember` key 并配方法引用做转换，导致 `TimetableHostInstrumentedTest`（真实主界面 + 课表 Tab 挂载）**进程级崩溃**（无 Java 栈，`INSTRUMENTATION_RESULT: shortMsg=Process crashed`）。

定位过程（严格二分，非猜测）：
1. 暂存全部改动 → 基线代码跑该用例 → **OK**（排除环境/测试自身）。
2. 仅保留 `MainScreen.kt` 改动 → **OK**（排除抽屉重构）。
3. 仅回退 `TimetableScreen.kt` → **OK**（锁定该文件）。
4. 改写为 `remember(paletteColors) { IntArray(size) { i -> hitaColorToArgb(paletteColors[i]) } }` → **OK**，随后上述全部用例转绿。

说明：先前的 `Process crashed` 里也混入了外部干扰——本机 `connectedDebugAndroidTest` 会把测试下发到所有在线 AVD，日志中出现过不在本仓库的 `cn.limpu.hita.perf.BackgroundScheduleUniquenessTest`，那是其他 agent 的测试跑到了我的实例上。因此最终一律用 `adb -s emulator-5560 shell am instrument` 定向执行。

### 模拟机实测（AVD `hita_agent03` / `emulator-5560`，API 35 Pixel 6）

| 场景 | 结果 |
|---|---|
| 冷启动 | 完成，无 `FATAL EXCEPTION`/`ANR in cn.limpu.hita.diag` |
| 今日（时间线） | 「11月8日 周日 / 今日空闲 / 今天暂无课程或日程安排」正常 |
| 课表 | 周网格、日期行、时间轴、底栏正常 |
| 横屏（`user_rotation 1`） | 网格重排正常，无崩溃 |
| 进程重建（`am kill` + 重启） | 恢复到原 Tab（课表），Tab 状态保持 |
| 暗色（`cmd uimode night yes`） | 课表页正常渲染 |
| 字体 1.3×（`font_scale 1.3`） | 布局未破，元素坐标按比例放大 |

截图/UI dump 位于 `D:/project/HITA/evidence/new_*.xml|png`（仓库外）。

### 说明

`connectedDebugAndroidTest` 在本机会把测试下发到所有在线 AVD（本次因其他 agent 的 `hita_agent01/05`、`ci_agent06` 同时在线，出现 "Process crashed"），因此改用 `adb -s emulator-5560 shell am instrument` 只在本 agent 的隔离实例上执行。

## 6. 性能数据

测量环境限制：本机 AVD 使用 SwiftShader 软件渲染，绝对帧时间不代表真机，仅用于同机同 AVD 的前后对比与"是否还做无谓计算"的定性判断。

| 指标 | 基线（`5992ba3`） | 本次 | 性质 |
|---|---|---|---|
| 冷启动 `am start -W TotalTime` | 14,928 ms | 23,911 ms（`Status: timeout`，25s 后已在前台且无崩溃） | 软件渲染 + 同时段另有 4 台 AVD 争抢 CPU；非有效对比数据 |
| 时间线空闲重组 | 每 10s 无条件重组整条列表（`LaunchedEffect(Unit)` + `while(true)`） | 仅 RESUMED 期间触发，且行状态计算从 O(n²) 降为 O(n) | 代码级确定性改进 |
| 课表配色 | 每次重组重建 `IntArray` → `colorAssignments` 永远失效 → 全量重算 | 仅色板（主题/预设/明暗）变化时重算 | 代码级确定性改进 |
| catalog 滚动 | 每个可见卡每帧一次 `coursePlanConflict()`（n² 会议交集） | 每次 page/已选/草稿变化一次，卡片 O(1) 查表 | 代码级确定性改进 |
| 抽屉动画 | 每帧重组 TopBar+Pager+TabBar | 收敛到 `graphicsLayer`/`drawBehind` 绘制期读取；遮罩用 `derivedStateOf` 只在跨阈值时重组 | 代码级确定性改进 |
| 成绩列表 | 单 `item` 内全量组合 | `itemsIndexed` 虚拟化 | 代码级确定性改进 |
| 对比网格 | 每次重组对 7 天各排序一次 | 按天 `remember` | 代码级确定性改进 |

未能给出可信的帧时间/内存对比：本机软件渲染且多 AVD 并发，测量噪声大于改动幅度。需要真机 Macrobenchmark 才能量化，已列为后续项。

## 7. 回滚方案

```
git checkout main
git branch -D refactor/agent-03-ui        # 未合并时
# 或已合并时
git revert <merge-commit>
```

单项回滚：本次改动集中在 UI 层 12 个既有文件 + 4 个新增文件，均可用 `git checkout 5992ba3 -- <path>` 单独还原。

## 8. 未完成与限制

### 未实施（需授权或越过所有权边界）

| 项 | 原因 |
|---|---|
| Fragment 侧 `observeAsState` 改 `collectAsStateWithLifecycle`（UI-060/039） | 需新增 `androidx.lifecycle:lifecycle-runtime-compose` 依赖，`gradle/libs.versions.toml` 与 `app/build.gradle` 不在本 agent 权限内。替代方案（`repeatOnLifecycle` + 本地 `State`）需逐页改写 189 处收集点，风险高于收益，未擅自进行。 |
| Repository 模型加 `@Immutable`、`DataState` 加 `equals`（影响 Compose 跳过能力） | 属 `data/**` 与 `component/**`，明确排除。 |
| 84 处 catalog 硬编码中文提取到 `strings.xml`（UI-080/081） | 纯文案搬迁，收益低、改动面大（跨 5 个文件、约 300 行），且会与并行 agent 的改动产生冲突风险。 |
| 返回键直接回桌面改为优先回首个 Tab（UI-068/090） | 属用户可见交互变更，按约束只在报告提出方案：建议首 Tab 时退到桌面，非首 Tab 时切回时间线。 |
| `MainUiState` 拆分（UI-009）、`SavedStateHandle` 全面覆盖（UI-064） | 属方案 B（抽离 UI 状态层）范围，一次改动过大，已在本报告 §1 评估后推迟。 |
| `MainActivity.tabContent` 的 modifier 记忆化（UI-023 剩余项） | 低风险遗留，收益小于改动风险。 |
| 待办行内 lambda 记忆化（UI-031 剩余项） | 同上。 |
| 真机 Macrobenchmark | 无物理设备；模拟机软件渲染数据不可作为性能结论。 |

### 已知限制

1. 本机默认 JDK 25 无法配置 AGP 8.10.1 的测试任务（`DefaultReportContainer Type T not present`），全部 Gradle 验证均显式指定 `-Dorg.gradle.java.home=~/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2`。
2. 本机 4 台模拟机并发（其他 agent），`connectedDebugAndroidTest` 会把测试广播到全部在线 AVD 并互相打断；所有设备验证均用 `adb -s emulator-5560` 定向执行。
3. 未验证真实校园账号 / WebVPN / 真实教务数据下的页面行为。
