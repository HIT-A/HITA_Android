# B4/B5 内容层与系统能力 spec

日期：2026-11-15
分支：`feat/ios-parity-migration`
主设计：[2026-11-15-ios-parity-migration-design.md](2026-11-15-ios-parity-migration-design.md)
对应 plan：[2026-11-15-b4-b5-content-and-system.md](../plans/2026-11-15-b4-b5-content-and-system.md)

## 范围

| ID | 内容 | 批 |
|---|---|---|
| F-02 | 资源文件应用内预览与复制 | B4 |
| H-02 | 作业字段扩展 | B4 |
| C-03 | 课表快照管理补齐 | B4 |
| C-02 | 软隐藏与编辑历史 | B5 |
| C-08 | 系统日历同步 | B5 |
| I-07 | 一键回归初始状态 | B5 |

## 一、F-02 资源预览与复制

### 现状

- `CourseReadmeActivity.kt:321-333` 用 Markwon 渲染 Markdown，图片走 Glide。
- 非 Markdown 资源在 `UnifiedResourceSearchActivity.kt:239-264` 直接下载，无应用内预览。
- 无代码块复制、无文件分享（grep `复制代码|copyCode|shareFile` 无相关实现）。
- PDFBox 已在依赖（`gradle/libs.versions.toml:104`），但仅用于 Agent 附件解析。

### 目标

- 应用内预览 PDF（复用 PDFBox 渲染为位图，不引入新库）。
- Markdown 代码块长按复制；链接长按复制。
- 资源文件可保存到下载目录或通过系统分享。

### 设计

**PDF 预览**：新增 `ui/resource/PdfPreviewActivity`，用 `PDFRenderer`（`android.graphics.pdf.PDFRenderer`，API 21+，无需 PDFBox）逐页渲染到 `Bitmap` 并放进 `LazyColumn`。选择 `PDFRenderer` 而非 PDFBox 的理由：系统 API 无额外体积、无字体资源初始化负担；PDFBox 继续只服务文本抽取。

**复制**：在 Markwon 的 `MarkwonPlugin` 链中加自定义 `CodeCopyPlugin`（长按代码块 → `ClipboardManager`），链接长按走现有 `LinkMovementMethod` 扩展。

**分享/保存**：新增 `ResourceExportCoordinator`，用现有 `FileProvider`（`MFileProvider` 已存在）生成 `content://` URI，通过 `Intent.ACTION_SEND` / `ACTION_CREATE_DOCUMENT`。

### 验收

- 单测：`ExternalLinkPolicy`（见 B2 spec）覆盖链接校验；`ResourceExportGuard`（文件大小上限、MIME 推导）纯函数测试。
- 手工：打开一份 PDF 资源可翻页；长按代码块复制成功；分享面板能弹出。

## 二、H-02 作业字段扩展

### 现状

`data/model/homework/HomeworkItem.kt:3-15` 只有 `id/title/createdAt/completed/plannedDate/plannedTime`。存储为 SharedPreferences 中的 Gson 快照（`HomeworkRepository.kt:37-41`），**不涉及 Room**。

iOS `HomeworkItem` 有 `subject/content/deadline/submissionMethod/submissionDetail/submissionFormat/attachments`。

### 目标

补齐科目、提交方式、提交信息、提交格式四个字段；附件暂不做（需要 FileProvider + 清理策略，超出本批）。

### 设计

```kotlin
data class HomeworkItem(
    val id: String,
    val title: String,
    val createdAt: Long,
    val completed: Boolean = false,
    val plannedDate: String? = null,
    val plannedTime: String? = null,
    // 新增，全部可空，旧数据缺省为 null/默认值
    val subject: String? = null,
    val submissionMethod: HomeworkSubmissionMethod = HomeworkSubmissionMethod.UNSPECIFIED,
    val submissionDetail: String? = null,
    val submissionFormat: HomeworkSubmissionFormat? = null,
)

enum class HomeworkSubmissionMethod { UNSPECIFIED, IN_CLASS, EMAIL, ONLINE_PLATFORM, FILE_UPLOAD, OTHER }
enum class HomeworkSubmissionFormat { TEXT, DOCUMENT, PDF, IMAGE, CODE, OTHER }
```

**兼容**：`HomeworkStore.load()` 的校验放宽——新字段缺失或非法时回退默认值而不是抛错（保持现有 `require(!id.isNullOrBlank() && !title.isNullOrBlank())` 等硬约束不变）。快照版本号写入新键 `schema = 2`，读取时忽略旧值。

**UI**：`HomeworkScreen` 的添加/编辑对话框增加四个输入项（提交方式与格式为下拉）。

### 验收

- 单测：旧格式快照（无新字段）能正常读取；新格式往返一致；非法枚举值回退默认。
- 单测：`HomeworkStore` 上限与去重校验未被破坏（既有测试不修改断言）。
- 手工：新建带科目的作业，重启后字段保留。

## 三、C-03 快照管理补齐

### 现状

- `TimetableSnapshotStore.kt:16-30` 保存四类快照。
- `ImportTimetableActivity.kt:484-510` 只能列出并恢复，无备注、保留策略、删除、统计。

### 目标

在既有快照之上增加：备注、保留上限设置、单条删除、课程/课次统计展示。不改快照本体格式（新增字段带默认值）。

### 设计

**模型**：`TimetableVersionSnapshot` 增加 `note: String = ""`。

**已核实**：快照存于文件 `filesDir/timetable_snapshots/v1.json`（`TimetableSnapshotStore.kt:102-104`），不是 Room 表。因此**无需数据库迁移**，只需 Gson 字段带默认值，旧文件读取时 `note` 为 `""`。

**保留策略**：`SnapshotRetention(limit: Int)`（默认 20，范围 5..100），保存时超出则删最旧。

**UI**：`ImportTimetableActivity` 的快照列表改为可长按菜单（备注/删除），设置区加保留上限选择；每条显示「课程数 · 课次数 · 时间」。

### 验收

- 单测：保留上限裁剪（保留最新的 N 条）；备注写入与读取；删除单条不影响其他。
- 若涉及迁移：`AppDatabaseUpgradePathTest` 覆盖新链。

## 四、C-02 软隐藏与编辑历史

### 现状

- `EventItemDao` 物理删除（`deleteEventsInIdsSync`）。
- 无软隐藏、无编辑历史（grep `softDelete|editHistory|undoEdit` 无命中）。
- 现有能力是**整体**快照恢复，无法找回单条误删。

### 目标

- 教务来源课程删除改为软隐藏（可恢复）；手动课保持物理删除。
- 记录编辑操作（新增/修改/删除/恢复），课表管理页提供「编辑历史」入口，可恢复单条。

### 设计

**Room v19 迁移**（C-03 不经数据库，本迁移只服务 C-02）：

```sql
ALTER TABLE events ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0;
CREATE TABLE event_edit_history (
    id TEXT NOT NULL PRIMARY KEY,
    timetableId TEXT NOT NULL,
    eventId TEXT NOT NULL,
    action TEXT NOT NULL,          -- CREATE / UPDATE / DELETE / RESTORE
    beforeJson TEXT,
    afterJson TEXT,
    createdAt INTEGER NOT NULL
);
CREATE INDEX index_event_edit_history_timetableId ON event_edit_history(timetableId);
```

**查询改动（本批最大风险）**：`EventItemDao.kt` 共 40 处涉及 `events` 的 `@Query`。其中所有**读取**查询必须加 `AND hidden = 0`；写入/删除/计数类查询按语义处理。plan 中逐个列出并标注处理方式，不遗漏。

用 Room 的 `@Query` 无法复用条件，必须逐条改；漏改的后果是已软隐藏的课程在某个入口重新出现。

**删除语义**：
- `source == EAS_IMPORT` 或 `FOLLOWED_*` → `UPDATE events SET hidden = 1`，并写 edit history。
- 手动创建 → 物理删除 + 写 history（history 里保留 `beforeJson` 以便恢复）。

**恢复**：从 history 取 `beforeJson` 重建行（或 `hidden = 0`），写一条 `RESTORE` 记录。

**UI**：`TimetableManagerActivity`（或详情页）新增「编辑历史」入口，列表展示时间/动作/课程名，单条可恢复。

### 验收

- 迁移测试：18→19 全链通过；旧库数据 `hidden` 默认 0。
- 单测：软隐藏后按课表查询不返回该事件；恢复后返回。
- 单测：历史条数上限（如 200，超出裁剪最旧）。
- 单测：手动课删除留下可恢复记录。
- **关键回归**：所有既有课表测试在加 `hidden = 0` 后仍通过（不修改断言）。

## 五、C-08 系统日历同步

### 现状

全仓无 `CalendarContract`（grep 已确认），Manifest 无日历权限。

### 目标

- 课表可导入系统日历（可写日历、指定范围、幂等更新）。
- 考试可单独导入系统日历。
- 权限被拒或无可写日历时明确提示，不静默失败。

### 设计

**权限**：`READ_CALENDAR`、`WRITE_CALENDAR`（Manifest + 运行时请求）。

**Repository**：新增 `data/repository/CalendarSyncRepository.kt`：

```kotlin
data class CalendarSyncPlan(
    val calendarId: Long,
    val inserts: Int, val updates: Int, val deletes: Int,
)
sealed interface CalendarSyncResult {
    data class Applied(val inserted: Int, val updated: Int, val failed: Int) : CalendarSyncResult
    data object NoWritableCalendar : CalendarSyncResult
    data object PermissionDenied : CalendarSyncResult
}
```

幂等：每条事件在日历中带 `SYNC_DATA1 = "hita:<timetableId>:<eventId>:<startMs>"`，同步时先查现有实例按此键匹配 → 决定 insert/update。

**分散失败**：逐条写，累计 `failed` 计数，返回部分成功而非整体失败。

**触发器**：`TimetableManagerActivity` 加「加入系统日历」入口（与导出并列），`ExamActivity` 加「考试加入日历」。

### 验收

- 单测（无系统依赖部分）：`CalendarSyncPlan` 计算、幂等键生成、事件 → `ContentValues` 映射。
- 手工：模拟器上授权后导入，日历可见事件；再次导入不重复；撤销权限提示正确。
- `NoWritableCalendar` 分支用注入的假 `CalendarGateway` 单测。

## 六、I-07 一键回归初始状态

### 现状

无任何整体清理入口（grep `factoryReset|resetAll|wipeAll` 无命中）。登出仅删课表（`ProfileActivity.kt:111`）。

### 目标

设置页提供「回归初始状态」，二次确认后按分量清理：数据库、偏好（含主题、提醒规则、作业、搜索历史、身份可见性）、临时文件、小组件数据；逐项回读并在失败时报告哪一项未清理。

### 设计

**分量清单**（`ResetComponent`）：`APP_DATABASE`、`USER_DATABASE`、`USAGE_QUEUE`、`PREFERENCES`、`FILES`、`WIDGETS`。

**协调器**：`data/repository/AppResetCoordinator.kt`

```kotlin
data class ResetOutcome(val cleared: List<ResetComponent>, val failed: List<ResetComponent>)

suspend fun reset(): ResetOutcome
```

- 每分量执行后**回读验证**（如表计数为 0、偏好文件为空），失败记入 `failed` 但继续其余分量。
- 不删 APK、不清理应用外部存储。

**UI**：`NavigationFragment` 设置组底部「回归初始状态」，红色文案，点击 → 影响清单对话框 → 输入确认。

### 验收

- 单测：协调器在假分量实现下，某分量回读失败时其余仍执行且 `failed` 正确。
- 手工：执行后可正常进入欢迎/登录流程，主题回默认。

## 七、风险

| 风险 | 处置 |
|---|---|
| C-02 加 `hidden = 0` 漏改查询导致事件消失 | plan 中列出全部 DAO 查询点，逐条核对；回归测试全跑 |
| Room v19 迁移出错 | 迁移链测试 + 升级路径测试；不提供破坏性回退 |
| C-08 部分 OEM 无默认日历 | 显式 `NoWritableCalendar` 分支与提示 |
| I-07 误触清空全部数据 | 二次确认 + 影响清单 + 红色样式；不做撤销 |
| F-02 PDF 大文件内存溢出 | 逐页渲染、只保留当前页与相邻页位图 |
