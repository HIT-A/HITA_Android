# B2/B3 低成本对齐与提醒 spec

日期：2026-11-15
分支：`feat/ios-parity-migration`
主设计：[2026-11-15-ios-parity-migration-design.md](2026-11-15-ios-parity-migration-design.md)
对应 plan：[2026-11-15-b2-b3-quick-wins-and-reminders.md](../plans/2026-11-15-b2-b3-quick-wins-and-reminders.md)

## 范围

| ID | 内容 | 批 |
|---|---|---|
| G-05 | 公告与更新双弹去重 | B2 |
| D-05 | 成绩分布并入成绩页 | B2 |
| I-06 | 身份可见性开关 | B2 |
| B-02 | 课表周号跳转 | B2 |
| F-04 | 课程资源外链白名单 | B2 |
| A-07 | 课程提醒多档提前 + 分时段规则 | B3 |
| D-07 | 考试提醒（考前多档） | B3 |
| F-01 | 资源搜索分源报告 + 搜索历史 | B3 |

## 一、G-05 双弹去重

### 现状

- `MainViewModel.kt:123-128` `start()` 先 `execute(startup...)`（内部会 `checkUpdate`）再 `requestNotices(host)`，两条独立通道。
- `MainNoticePolicy.select` 会把「版本公告」优先返回（`MainNoticePolicy.kt:41-44`），`MainUpdatePresenter` 也会为同一版本弹更新对话框（`MainUpdatePresenter.kt:22-31`，内部有 skip/cooldown）。
- 结果：同一版本可能先后弹「版本公告」与「发现新版本」。

### 目标

同一次启动内，同一版本号最多提示一次；公告优先，更新对话框不再重复弹。

### 设计

新增 `ui/main/StartupPromptLedger`（进程内，不持久化）：

```kotlin
internal class StartupPromptLedger {
    private val shownVersions = mutableSetOf<Long>()
    /** 返回 true 表示本次是首次提示该版本。 */
    fun claimVersionPrompt(versionCode: Long): Boolean
}
```

- `MainViewModel` 持有实例，在发 `MainEffect.Notice(Version)` 前 `claimVersionPrompt(BuildConfig.VERSION_CODE)`；`checkUpdate` 发 `MainEffect.Update` 前同样 claim。
- 谁先 claim 谁弹，另一方降级为静默（更新仍可下次启动提示，公告仍进列表页）。
- 不改 `MainNoticePolicy` 与跳过/冷却策略。

### 验收

- 单测 `StartupPromptLedgerTest`：同版本第二次返回 false；不同版本各自 true。
- 单测 `MainViewModel` 层面的效果发射：构造公告与更新同时命中，断言只发一个 `MainEffect`。若 `MainViewModel` 难以单测，退化为纯函数测试 + 代码评审标记，不写假测试。

## 二、D-05 成绩分布

### 现状

- Android 分布仅在深圳独立页 `ShenzhenGradeAnalysisActivity.kt:604-620`（`analysis.bands`，按班级统计）。
- 成绩页 `ScoreInquiryActivity.kt` 只展示 `ScoreSummaryCard`（均分/GPA/排名），无课程维度分档。
- iOS `Application/Academic/Grades/ScoreDistribution.swift:54-79` 从课程成绩列表派生出四档分布，所有校区可见。

### 目标

成绩页展示「本学期我的课程分数分布」四档条形，非深圳校区同样可用；等级制/通过制课程排除并计数。

### 设计

新增纯函数 `ui/eas/score/ScoreDistribution.kt`：

```kotlin
data class ScoreBucket(val label: String, val count: Int)

data class ScoreDistribution(
    val buckets: List<ScoreBucket>,   // 固定四档，含 0 计数
    val scoredCount: Int,
    val excludedCount: Int,
) {
    val isRenderable: Boolean get() = scoredCount > 0
}

object ScoreDistributionCalculator {
    fun calculate(items: List<CourseScoreItem>): ScoreDistribution
}
```

分档与 iOS 对齐含义：90-100 / 80-89 / 70-79 / <70；非百分制（等级、通过）入 `excludedCount`。

UI：在 `ScoreSummaryCard` 下方加 `ScoreDistributionBlock`，用 `MaterialTheme.colorScheme.primary` 系列色与 `surfaceVariant` 轨道；空态不渲染（不占位）。不使用硬编码色。

### 验收

- 单测：全百分制、混合等级制、全非百分制（不可渲染）、空列表。
- 单测：四档始终存在（含 0 计数）。
- 与 `ShenzhenGradeAnalysisActivity` 的班级统计互不影响（那是另一数据源，不复用）。

## 三、I-06 身份可见性

### 现状

抽屉与更多页展示学号/姓名/学院（`MainDrawerMapper.kt:37-60`、`NavigationFragment.kt:660-700`）。grep 无任何隐藏开关。

### 目标

设置项「隐藏身份信息」开启后，抽屉与更多页用户卡只显示「已登录」与校区，不显示学号/姓名/学院。

### 设计

新增 `data/source/preference/IdentityVisibilityStore.kt`（SharedPreferences，键 `hide_identity`，默认 false）。

`MainDrawerMapper.map(...)` 增加参数 `hideIdentity: Boolean`；为 true 时 `title` 用通用文案、`subtitle` 只保留校区。纯函数化，便于单测。

UI 入口：`NavigationFragment` 的设置组新增一行 `Switch`（与现有「使用统计」开关同款）。

### 验收

- 单测 `IdentityVisibilityTest`：隐藏时不输出学号/姓名/学院字符串；不隐藏时保持原输出。
- 切换后抽屉立即刷新（复用 `viewModel.refreshUser()`）。

## 四、B-02 周号跳转

### 现状

课表只能前后翻周（`TimetableScreen.kt:204`）与横滑。iOS 可按周号直接跳。

### 目标

课表顶栏提供周号选择，选中即跳转。

### 设计

- `TimetableViewModel` 已有 `navigateToWeek(mondayMillis)`（`:90`）。新增 `weekOptions(): List<WeekOption>`（周号 + 对应周一），基于当前课表起始时间与总周数。
- `TimetableScreen` 顶栏标题改为可点击，弹出 `AlertDialog` 单选列表（复用现有对话框风格），选中调用 `navigateToWeek`。
- 越界周（超出学期）不列出。

### 验收

- 单测：`weekOptions` 在给定起始时间与周数下生成正确周一列表；0 周或空课表返回空。
- 手工：选第 8 周后网格标题与列日期同步变化。

## 五、F-04 外链白名单

### 现状

README 内任意 `http(s)` 链直接 `ACTION_VIEW`（`CourseReadmeActivity.kt:175`），`SubjectResourceLauncher.kt:31` 同样无限制。iOS 只允许 `github.com`、`fireworks.jwyihao.top`（`CourseResourceQueries.swift:434-435`）。

### 目标

课程资源相关的外跳只允许白名单域名与 `https`；其余提示「不支持的外部链接」并保留复制能力。

### 设计

新增纯函数 `ui/subject/ExternalLinkPolicy.kt`：

```kotlin
object ExternalLinkPolicy {
    /**
     * @return 允许打开则返回规范 URL，否则 null
     */
    fun allowed(raw: String): String?
}
```

规则：scheme 必须 `https`（或显式允许的 `http` 用于校园内网站点，列出精确 host）；host 必须命中白名单（含子域匹配）；拒绝 `javascript:`、`data:`、`file:`。

白名单最小集：`github.com`、`raw.githubusercontent.com`、`fireworks.jwyihao.top`、`hoa.moe`、`api.hita.limpu.cn`。校园内网与实用网址页**不**走此策略（那些是用户主动点开的链接集合，另有既有行为）。

替换点：`CourseReadmeActivity` 的链接点击、`SubjectResourceLauncher.browser/download`。

### 验收

- 单测：白名单内通过、子域通过、非白名单拒绝、`http` 非白名单拒绝、`javascript:` 拒绝。
- 手工：README 里非白名单链接点击给出提示，不跳出应用。

## 六、A-07 课程提醒多档

### 现状

- `CourseReminderStore.kt:9-45`：只有 `enabled` + `minutes`（默认 15）。`setReminderMinutes` 无任何 UI 调用方。
- UI 仅开关（`NavigationFragment.kt:589-596` 文案写死「上课前15分钟提醒」）。
- `CourseReminderWorker.kt:65-85`：单事件取最近一节，按 `minutes` 计算延迟。

### 目标

- 支持多档提前点（例如 15 / 5 / 开始时刻），每档可独立开关。
- 支持按节次时段配置不同提前量（如上午课 15 分钟、下午课 5 分钟）。
- 通知去重按事件 + 档位，同一节课多档各提一次。

### 设计

**存储**：`CourseReminderStore` 增加 JSON 结构（键 `rules_v1`），保留旧 `enabled`/`minutes` 作为回退与迁移源。结构：

```kotlin
data class ReminderRuleSet(
    val leads: List<Int>,                 // 分钟偏移，升序，去重，[0, 120]
    val periodOverrides: Map<Int, Int>,   // 节次号 → 提前分钟
)
```

迁移：首次读取若 `rules_v1` 缺失，则以旧 `minutes` 生成 `leads = [minutes]`，写回。

**调度**：`CourseReminderWorker` 改为按「下一个待触发的（事件, 档位）」排程，`sentReminders` 键改为 `${eventId}#${lead}`。一次 WorkManager 只排下一个触发点，保持单链。

**UI**：`NavigationFragment` 的「课程提醒」行改为可点击 → 对话框（多选提前档 + 每档开关）；开关仍保留在行尾。文案改为动态显示当前档位摘要。

### 验收

- 单测：迁移从旧 `minutes` 生成单档；`leads` 去重与边界裁剪；`periodOverrides` 生效优先级。
- 单测：调度计算在给定事件与档位下产出正确延迟；已发送集合按 `eventId#lead` 去重。
- 手工：设置 15 与 5 两档，同一节课收到两条通知。

## 七、D-07 考试提醒

### 现状

考前后无任何通知；`ScoreReminderWorker` 只处理成绩变化。

### 目标

考试前 24h / 12h / 1h / 30min 各提醒一次（可在设置中选档），使用独立通知渠道。

### 设计

- 数据源：`ExamItem`（本地考试与教务考试），取 `from.time`。
- 新增 `data/work/ExamReminderWorker.kt` + `ExamReminderScheduler`，单链 OneTimeWork，与课程提醒互相独立（考试与课程触发时间不重叠时无冲突；重叠时各自渠道）。
- 存储复用 `CourseReminderStore` 的规则集，新增 `examLeads` 字段。
- 通知渠道 `exam_reminder`，点击进 `ExamActivity`。
- 已发送记录键 `${examId}#${lead}`，持久化在 SharedPreferences（与课程提醒同文件不同键）。

### 验收

- 单测：给定考试时间与档位，排程延迟正确；过期考试不排程；已发送不重复。
- 单测：档位为空时不创建 Worker。
- 手工：设置 1h 档，构造明日考试，验证通知与点击跳转。

## 八、F-01 分源报告与搜索历史

### 现状

- `UnifiedResourceSearchViewModel.kt:46-78` 合并 HOA 与外部结果，单源失败仍整体 `SUCCESS`。
- `ExternalResourceRepository.kt:26-41` 单源异常被吞，返回空列表。
- 无搜索历史（grep 无命中）。

### 目标

- 展示每个来源的状态（成功/失败/空），失败来源明确标注，不把「某源失败」显示成「没有资料」。
- 记录最近搜索词，可清空。

### 设计

**模型**（新增 `data/model/resource/ResourceSourceReport.kt`）：

```kotlin
enum class ResourceSourceStatus { OK, EMPTY, FAILED }
data class ResourceSourceReport(val source: ResourceSource, val status: ResourceSourceStatus, val count: Int)
data class UnifiedSearchResult(val items: List<UnifiedResourceItem>, val reports: List<ResourceSourceReport>)
```

`ExternalResourceRepository` 各源独立捕获异常并产出 report，不吞错。

**历史**：新增 `data/source/preference/ResourceSearchHistoryStore.kt`（最多 20 条，去重，最新在前，提供 `record/list/clear`）。

**UI**：搜索结果页顶部按来源显示状态条（失败用 `colorScheme.error`，空用 `onSurfaceVariant`）；搜索框聚焦时展示历史列表，带「清空」按钮。

### 验收

- 单测：一源失败一源成功 → 结果含成功项且 report 标 FAILED；两源失败 → 整体仍返回（不抛），reports 均 FAILED。
- 单测：历史上限、去重、清空。
- 手工：断网或伪造失败源，页面显示「HOA 获取失败」而非空列表。

## 九、风险

| 风险 | 处置 |
|---|---|
| A-07 多档导致通知过多 | 档位数上限 3，UI 明示 |
| D-07 与 A-07 同时触发 | 独立渠道，互不抑制 |
| F-01 历史含隐私词 | 仅存本地、可清空、不参与统计上报 |
| B-02 周数与课表实际周数不符 | 由课表起始时间与总周数推导，不做猜测 |
