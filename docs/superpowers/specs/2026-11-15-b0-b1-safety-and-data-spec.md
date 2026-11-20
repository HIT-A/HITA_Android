# B0/B1 安全与数据正确性 spec

日期：2026-11-15
分支：`feat/ios-parity-migration`
主设计：[2026-11-15-ios-parity-migration-design.md](2026-11-15-ios-parity-migration-design.md)
对应 plan：[2026-11-15-b0-b1-safety-and-data.md](../plans/2026-11-15-b0-b1-safety-and-data.md)

## 范围

| ID | 内容 |
|---|---|
| J-06 | Agent 写操作确认与结果未知回执 |
| F-03 | 投稿幂等键（两条路径统一） |
| C-06 | 手动教务导入改为差异预览 |

## 一、J-06 确认与回执

### 现状（证据）

- `agent/tools/AddActivityTool.kt:33-44`：解析成功后直接 `ToolHelper.runTimetableTool(...)` 写入，无确认。
- `agent/tools/SubmitReviewTool.kt:83-88`：`previewSync` 后立即 `submitSync`，无确认。
- `agent/tools/ReActToolRegistry.kt:26-43`：`var outcome = "unknown"` 从未被成功路径改写；只有抛异常时变 `failure`/`cancelled`。因此「非抛错」一律记为 `unknown`，无法区分成功与业务失败。
- iOS 对照：`Application/Core/PolicyEngine.swift:66-67` 要求变更必须先经本机确认；结果未知名禁止重放。

### 目标行为

1. 工具结果携带结构化状态：`SUCCEEDED` / `FAILED` / `OUTCOME_UNKNOWN` / `CANCELLED`。
2. 写类工具（`add_activity`、`submit_review`）执行前进入「待确认」：不落盘、不提交，先把拟执行内容交给 UI 确认。
3. `OUTCOME_UNKNOWN` 不自动重试；`FAILED` 允许模型换参数重试。
4. 用户取消 → `CANCELLED`，对话中说明未执行。

### 设计

**类型（新增）** `agent/tools/ReActToolOutcome.kt`：

```kotlin
sealed interface ReActToolOutcome {
    val observation: String          // 回给模型的文本
    data class Succeeded(override val observation: String) : ReActToolOutcome
    data class Failed(override val observation: String, val retryable: Boolean) : ReActToolOutcome
    data class OutcomeUnknown(override val observation: String) : ReActToolOutcome
    data class Cancelled(override val observation: String) : ReActToolOutcome
    data class NeedsConfirmation(
        override val observation: String,
        val request: ConfirmationRequest,
    ) : ReActToolOutcome
}

data class ConfirmationRequest(
    val title: String,
    val detailLines: List<String>,
    val confirmLabel: String,
    val cancelLabel: String,
)
```

**接口扩展**：`SuspendingReActTool` 增加可选返回 `ReActToolOutcome` 的重载；保留现有 `String?` 返回的工具不动（默认映射为 `Succeeded`，但**仅当**调用方显式声明 `declaresOutcome = true`，否则保持 `OutcomeUnknown` 以免误报成功）。

**注册表**：`ReActToolRegistry.observed` 改为从返回值推导状态，而不是先验 `unknown`。异常仍映射 `failure`。

**确认流程**：`LlmChatService` 在 `toolRegistry.get(...)` 调用后检查 `NeedsConfirmation`：

- 发 `ChatConfirmationRequest` 事件 → `AgentChatViewModel` 暴露 `pendingConfirmation` StateFlow → `AgentChatFragment` 渲染确认卡片（复用现有 `AlertDialog` 风格）。
- 确认 → 以同一 action/input 再次调用工具并带 `confirmed = true` 标记；取消 → 产出 `Cancelled` 观察文本继续对话。

**无确认时的降级**：若 UI 未订阅（如 headless 场景），写类工具直接返回 `NeedsConfirmation` 的观察文本「需要用户确认后才会执行」，不落盘。这是安全默认。

### 验收

- 单测：`ReActToolRegistryTest` 覆盖四种状态推导；`AddActivityToolTest` 覆盖未确认不写库、确认后写库、取消不写库。
- 单测：`SubmitReviewToolTest` 覆盖未确认不调用 `submitSync`。
- 不新增 instrumentation 测试。

## 二、F-03 投稿幂等

### 现状（证据）

两条独立路径，都不是内容稳定的幂等键：

| 路径 | 位置 | 当前 key |
|---|---|---|
| UI 投稿 | `data/source/web/HoaResourceSource.kt:409` | `UUID.randomUUID().toString()` |
| Agent 投稿 | `agent/remote/PrServerClient.kt:26-32` | `"android-$normalizedCourseCode-$uniqueSuffix"`，suffix 默认为 `System.nanoTime()` |

后果：任一路径超时后重试都会换 key，服务端视为新请求 → 重复 PR。

### 目标行为

同一份内容（同校区 + 同课程码 + 同 ops 内容）在合理时间窗内重试时产生相同 key；内容变化时 key 变化。

### 设计

新增纯函数 `data/source/web/hoa/SubmissionIdempotencyKey.kt`：

```kotlin
object SubmissionIdempotencyKey {
    /**
     * 内容派生，不含时间与随机数。
     * @param campus 归一化校区名
     * @param courseCode 归一化课程码
     * @param opsCanonical 已规范化排序的 ops JSON 字符串
     */
    fun derive(campus: String, courseCode: String, opsCanonical: String): String
}
```

实现用 SHA-256(小写六十四进制)，前缀 `android-v1-` 便于服务端识别。`opsCanonical` 由现有 ops 列表按固定字段顺序序列化后 `sortedBy(json)` 得到，避免键序差异。

两条路径都改为调用它；`PrServerClient` 删除 `uniqueSuffix` 参数与 `System.nanoTime()` 调用。`HoaResourceSource` 删除 `UUID.randomUUID()`。

**兼容**：服务端已接受任意字符串 key，本变更不改变协议形状，只是取值稳定化。

### 验收

- 单测：同输入两次 `derive` 相等；ops 顺序不同但内容相同 → 相等；内容变一个字符 → 不等；courseCode 大小写/分隔符归一后相等。
- 单测：`PrServerClientTest` 断言请求体 key 不为随机值（同输入复用）。
- 单测：`HoaResourceSourceTest` 同上。

## 三、C-06 手动导入差异预览

### 现状（证据）

- `EASTimetableImportRepository.kt:89` 默认 `importMode = REPLACE`；`ImportTimetableViewModel.kt:218-234` 调 `easRepo.importTimetableOfTerm(session, term, date, schedule)`，未传 mode → REPLACE。
- `EASTimetableImportRepository.kt:178` 仅 `MERGE` 分支走 `TimetableRefreshMergePolicy`。
- `TimetableRefreshMergePolicy.kt:651-655` 注释明确：REPLACE 是手动导入全量替换，MERGE 是自动刷新逐门合并。

后果：用户手动点「导入课表」会覆盖自动刷新此前保留的本地课。

### 目标行为

手动导入前先给出差异清单（新增/变更/保留/删除），用户确认后才提交；默认采用 MERGE 语义，不再隐式全量替换。

### 设计

1. **只读预览**：`ImportTimetableViewModel` 新增 `prepareImport()`，调用现有 merge policy 生成 `TimetableMergePlan`（复用 `TimetableRefreshMergePolicy.plan(...)`），不写库。返回结构直接复用既有 `TimetableChangeState`/`CourseChange`，避免新模型。
2. **UI**：`ImportTimetableActivity` 在导入前插入确认对话框，复用 `TimetableChangeDialog` 的分组展示（待采纳/已保留/待确认）。用户点「导入」才继续。
3. **提交**：`startImportTimetable(mode = MERGE)`；保留一个显式「全量替换」二次确认入口（文案明确写出影响），满足确实想清空的场景。
4. **失败处理**：预览失败不进入确认页，提示原因；提交失败保留原有快照恢复路径。

### 验收

- 单测：`ImportTimetableViewModelTest` 覆盖「预览不写库」「确认后才调用 repository」「取消不调用」。
- 单测：`EASTimetableImportRepository` 既有 MERGE 用例继续通过（不修改断言）。
- 手工：构造「本地手工课 + 远端少课」，确认预览列出保留项且提交后本地课未被删。

## 四、风险

| 风险 | 处置 |
|---|---|
| J-06 改变工具返回契约可能影响所有工具 | 默认不声明 outcome 的工具保持 `OutcomeUnknown`，行为不变；只有两个写工具显式声明 |
| 确认流程阻塞对话 | UI 未订阅时按安全默认直接不执行，观察文本说明原因 |
| 幂等键与服务端去重窗口不匹配 | key 只保证客户端重试不重复；服务端窗口由其实现决定，不在本次范围 |
| C-06 改为 MERGE 可能与用户「就是要重置课表」意图冲突 | 保留显式全量替换入口 + 二次确认 |
