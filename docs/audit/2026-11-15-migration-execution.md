# iOS → Android 迁移：执行记录

分支：`feat/ios-parity-migration`
主设计：[2026-11-15-ios-parity-migration-design.md](../superpowers/specs/2026-11-15-ios-parity-migration-design.md)

本文件记录实际落地的范围、偏离规格之处，以及未完成与验证受限的项。状态以构建与测试实际输出为准。

## 批次进度

| 批次 | 内容 | 状态 |
|---|---|---|
| B0 | J-06 确认与回执 | 已完成：工具状态模型、注册表状态推导、两个写工具、共享决策点；确认通道已贯通 `LlmChatService` → `ChatRequestRunner` → `ReActToolInput`，无订阅时拒绝落盘 |
| B1 | F-03 幂等 / C-06 导入预览 | 已完成：两条投稿路径改为内容派生键；C-06 由 `TimetableChangeStore` + `TimetableChangeDialog` 承担（决策项 + 暂挂批次），UI 已接 |
| B2 | G-05 / D-05 / I-06 / B-02 / F-04 | 已完成：公告与更新统一按「被提示版本号」claim；顶栏标题点击与名称芯片共用显示范围菜单；资源分源报告已接 |
| B3 | A-07 / D-07 / F-01 | 已完成：多档提醒规则、考试提醒排程、搜索历史（上限 20、重复前移、清空）已接搜索页 |
| B5 | C-02 / C-03 / C-08 / I-07 | 已完成：Room 18→19 与软隐藏；快照备注 / 删除 / 保留上限；系统日历同步（计划层 + 写入层 + 考试页入口 + 权限）；一键重置（分量清理 + 回读 + 影响清单 + 二次确认） |
| B7 | 扩展栏 + E-01~E-04 | 扩展栏（可见性判定 + 单测）已完成；E-01~E-04 见「阻塞」 |
| B4 / B6 / B8 | 其余 | 定位 A-02 迷你课程条（解析器 + UI + 分钟刷新）、A-04 小组件深链解析与逐实例配置（含配置页、`onDeleted` 清理）、H-02 作业提交字段（模型 + 兼容读取 + 列表展示）、D-05 成绩分布卡并入成绩页、F-02 README 文本可选中复制 |

## 阻塞（无外部凭据，仓库内无 fixture）

以下四项需要各自校园门户的**独立 CAS 会话**与真实响应样本，仓库内不存在可用的 fixture。
按既定停止条件，交付「页面壳 + 明确前提说明 + 状态模型」，不臆造接口、不展示伪造数据：

- **E-01 校园卡**（`dxcdp.hitsz.edu.cn`，请求头 `token`）
- **E-02 静音仓预约**（`utszlib` 门户）
- **E-03 大学城图书馆**（`utszlib` 在馆人数）

**D-06 培养方案不是阻塞项**：`ShenzhenAcademicWebSource` 已实现培养方案抓取，`CreditStatsActivity` 展示完成度，入口在「更多」页的 `onCreditStats`，本轮无需改动。

复现条件：拿到任一门户的真实登录态与响应后，按 `CampusServiceUiState` 的四种状态接入抓取层即可，页面壳与失败分支已就位。

## 与计划的偏离

1. **扩展栏位置**：规格原写「课表管理页内」，实现改为「更多页 `NavigationScreen` 内、课表管理卡之后」。原因是 `TimetableManagerActivity` 无 `EASToken`，而用户描述的位置正是「更多界面、课表管理下面」。
2. **四个校园扩展只交付壳与前提说明**：`dxcdp` / `utszlib` / `lab.hitsz.edu.cn` 需要各自门户的独立会话，现有教务 cookie 不覆盖。按规格的分层策略只交付可无凭据验证的部分，页面显式说明前提，不展示伪造数据。
3. **`ReActToolInput` 新增 `confirmationGate`**：默认 `unavailable`，没有 UI 订阅时写工具不得落盘。

## 已知缺陷（待修）

- ~~G-05 的 claim 键：公告用 `BuildConfig.VERSION_CODE`、更新用 `latestVersionCode`，两者不同，去重实际失效。~~ 已修：两条通道统一按「被提示的版本号」claim。
- **B-02 周号跳转已落地**：复用 `TimetableViewModel.weekOptions()`（内部 `weekOptions(termMonday, endMillis)` 按课表学期算每周周一），`TimetableFragment.weekOptions()` 委派给它；顶栏范围菜单内新增「按周跳转」，选中即调用 `TimetableFragment.navigateToWeek`。`MainActions.weekOptions` 是 provider，只在展开菜单时求值，不做常驻计算。`MainActions.timetableTitleClick` 仍为空实现——标题点击现在直接展开范围菜单。
- ~~J-06 的 UI 确认对话框未做~~ 已实现：`AgentChatViewModel` 持有单槽位 `ConfirmationGate`，聊天页在 `NeedsConfirmation` 时弹出确认对话框，批准/拒绝经 `ReActToolOutcome.ConfirmationGate.Resolved` 回传；仍有超时与断连回收。
- ~~四个校园扩展页面只显示会话前提~~ 已实现：E-01 校园卡、E-02 静音仓、E-03 图书馆/实验室都走真实门户接口（CAS 换票 + Cookie 会话 + HMAC 签名），四态页面（加载/未登录/失败/就绪）齐全。

## 验证

本轮实测（Windows，JDK 21，Gradle 8.11.1）：

| 检查 | 结果 |
|---|---|
| `:app:assembleDebug` | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | **1289 tests, 0 failures, 0 errors, 1 skipped**（基线 1071/0/0） |
| `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin` | BUILD SUCCESSFUL |
| `python docs/audit/verify_citations.py` | 通过：236 条 `路径:行号` 全部有效 |
| `python scripts/check_repository.py` | 通过 |

本轮新增测试：`SnapshotRetentionTest`、`CalendarSyncPlannerTest`、`MiniCourseResolverTest`、`WidgetDeepLinkTest`、`WidgetConfigCodecTest`、`ScoreDistributionTest`、`HomeworkItemCompatTest`，以及既有的 `CampusExtensionTest`（覆盖未登录 / 校区不匹配 / 深圳已登录三种扩展栏可见性）。

本轮修正的真实缺陷：

1. JVM 单测里 `org.json` 只有桩实现 → 引入 `org.json:json` 测试依赖，让依赖 `JSONObject/JSONArray` 的纯函数可在 JVM 验证。
2. `ScoreDistribution` 对越界 `numericScore` 会回退到默认的 0 分，把脏数据算成「不及格」→ 显式数值越界即判定不可用。
3. `G-05` 公告与更新两条通道 claim 键不一致，去重实际失效 → 统一按「被提示的版本号」claim。

未在本轮执行（超出可验证条件）：instrumentation 测试、真机/模拟器 UI 走查、真实校园门户调用。

## 复核轮追加（2026-11-15 第二轮）

在逐项比对已迁移功能与 iOS 实际实现后，又补了三处真实缺口：

1. **R-01 下课前提醒**：`ReminderRuleSet` 增加 `endLeads`（默认 `[5]`），`CourseReminderSchedule.candidatesFor` 统一产出课前/下课前两类候选，提醒 key 变为 `event#phase#lead`，避免同一提前量互相遮蔽；worker 按 `ReminderPhase` 改写通知文案与锚点时间。旧快照缺 `endLeads` 时补默认值，最多 3 档。
2. **R-04 通知规则的 UI 入口**：新增 `CourseReminderSettingsActivity`（课前 / 下课前各一组档位，课前 `[0,5,10,15,30]`、下课前 `[0,5,10]`），设置行的副标题改为按当前规则生成，不再硬编码「15 分钟」。
3. **C-02 删除可恢复**：`TimetableRepository` 的 `Delete` 分支改走 `EventEditHistoryStore.delete`，教务导入/跟读/培养方案课改为软隐藏，手动课物理删除但保留历史行，均可 `restore(historyId)` 回放。
4. **C-03 保留上限真正生效**：`TimetableSnapshotStore` 把保留条数持久化到 `SharedPreferences`，`trimTimetableSnapshots` 保存用户选择，写快照时按该值裁剪；`SnapshotRetention.MAX_LIMIT = 40` 与设置页最高档对齐。

配套单测：`CourseReminderScheduleTest`（下课前触发、同提前量不遮蔽、无结束时间不产生结束提醒、旧快照兼容、超 3 档拒绝）、`EventEditHistoryStoreTest`（软隐藏可恢复、物理删除可回放、未知 id 不写历史）、`SnapshotRetentionTest`（上限覆盖最高档）。