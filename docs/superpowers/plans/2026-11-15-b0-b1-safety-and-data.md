# B0/B1 安全与数据正确性 实施计划

**Goal:** 让 Agent 写操作经确认、投稿幂等键稳定、手动教务导入先出差异再落库。
**Architecture:** 新增 `ReActToolOutcome` 承载工具状态并在 `LlmChatService` 分发点插入确认；幂等键抽成纯函数供两条投稿路径共用；导入复用既有 `TimetableRefreshMergePolicy` 生成预览。
**Spec:** [2026-11-15-b0-b1-safety-and-data-spec.md](../specs/2026-11-15-b0-b1-safety-and-data-spec.md)

---

## Task 1: 工具状态模型

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/agent/tools/ReActToolOutcome.kt`
- Test: `app/src/test/java/cn/limpu/hita/agent/tools/ReActToolOutcomeTest.kt`

- [ ] 测试：四种状态的 `observation` 透传，`NeedsConfirmation` 携带请求体

```kotlin
@Test fun `outcome carries observation`() {
    assertEquals("ok", ReActToolOutcome.Succeeded("ok").observation)
    assertEquals("no", ReActToolOutcome.Failed("no", retryable = false).observation)
}
```

- [ ] 运行 `--tests *ReActToolOutcomeTest` → FAIL（类不存在）
- [ ] 实现 sealed interface + `ConfirmationRequest`
- [ ] 运行 → PASS

## Task 2: 注册表按返回值推导状态

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/agent/tools/ReActToolRegistry.kt`
- Modify: `app/src/main/java/cn/limpu/hita/agent/tools/ReActTool.kt`（加可选 `executeWithOutcome`）
- Test: `app/src/test/java/cn/limpu/hita/agent/tools/ReActToolRegistryTest.kt`

- [ ] 测试：声明 outcome 的工具返回 `Succeeded` 时统计 outcome 为 `succeeded`；未声明的工具仍记 `unknown`；抛异常记 `failure`
- [ ] 运行 → FAIL
- [ ] 实现：`observed()` 改为读取 `ReActToolOutcome`，未声明者保持 `unknown`
- [ ] 运行 → PASS

## Task 3: AddActivityTool 接入确认

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/agent/tools/AddActivityTool.kt`
- Test: `app/src/test/java/cn/limpu/hita/agent/tools/AddActivityToolTest.kt`

- [ ] 测试：`confirmed = false` → 返回 `NeedsConfirmation` 且不调用 `ToolHelper.runTimetableTool`；`confirmed = true` → 调用并返回 `Succeeded`
- [ ] 运行 → FAIL
- [ ] 实现：`executeWithOutcome(input, confirmed)`；`confirmed` 由 `ReActToolInput` 新字段承载
- [ ] 运行 → PASS

## Task 4: SubmitReviewTool 接入确认

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/agent/tools/SubmitReviewTool.kt`
- Test: `app/src/test/java/cn/limpu/hita/agent/tools/SubmitReviewToolTest.kt`

- [ ] 测试：未确认时不调用 `PrServerClient.submitSync`；确认后调用；失败返回 `Failed`
- [ ] 运行 → FAIL
- [ ] 实现
- [ ] 运行 → PASS

## Task 5: 确认事件贯通到 UI

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/agent/llm/LlmChatService.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/agent/AgentChatViewModel.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/agent/AgentChatFragment.kt`
- Test: `app/src/test/java/cn/limpu/hita/agent/llm/ChatConfirmationFlowTest.kt`

- [ ] 测试：`NeedsConfirmation` 时产出一条待确认项且不继续推理；取消产出 `Cancelled` 观察文本并继续
- [ ] 运行 → FAIL
- [ ] 实现：`LlmChatService` 暴露 `confirmationRequests` 流；VM 聚合；Fragment 渲染对话框
- [ ] 运行 → PASS

## Task 6: 幂等键纯函数

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/data/source/web/hoa/SubmissionIdempotencyKey.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/source/web/hoa/SubmissionIdempotencyKeyTest.kt`

- [ ] 测试：同输入相等；ops 顺序不同相等；内容变则不等；courseCode 归一后相等
- [ ] 运行 → FAIL
- [ ] 实现 SHA-256 派生，前缀 `android-v1-`
- [ ] 运行 → PASS

## Task 7: 两条投稿路径改用幂等键

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/data/source/web/HoaResourceSource.kt:409`
- Modify: `app/src/main/java/cn/limpu/hita/agent/remote/PrServerClient.kt:26-32`
- Test: `app/src/test/java/cn/limpu/hita/data/source/web/HoaResourceSourceKeyTest.kt`、`app/src/test/java/cn/limpu/hita/agent/remote/PrServerClientKeyTest.kt`

- [ ] 测试：同内容两次提交请求体 key 相同；不同内容 key 不同
- [ ] 运行 → FAIL
- [ ] 实现：删除 `UUID.randomUUID()` 与 `System.nanoTime()`；两处调用 `SubmissionIdempotencyKey.derive`
- [ ] 运行 → PASS

## Task 8: 导入预览（只读）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/ui/eas/imp/ImportTimetableViewModel.kt`
- Test: `app/src/test/java/cn/limpu/hita/ui/eas/imp/ImportTimetablePreviewTest.kt`

- [ ] 测试：`prepareImport()` 调用 merge policy 且不触发任何 DAO 写；返回计划非空
- [ ] 运行 → FAIL
- [ ] 实现：新增 `prepareImport()`，内部调 `TimetableRefreshMergePolicy.plan(...)`
- [ ] 运行 → PASS

## Task 9: 导入确认 UI 与 MERGE 提交

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/ui/eas/imp/ImportTimetableActivity.kt`
- Modify: `ImportTimetableViewModel.kt`（`startImportTimetable(mode)`）
- Test: `ImportTimetablePreviewTest`（扩展）

- [ ] 测试：取消预览不调用 repository；确认后以 `MERGE` 调用；显式全量替换路径以 `REPLACE` 调用
- [ ] 运行 → FAIL
- [ ] 实现：确认对话框复用 `TimetableChangeDialog` 展示分区
- [ ] 运行 → PASS

## Task 10: 批次验收

- [ ] `gradlew.bat :app:assembleDebug` → 0
- [ ] `gradlew.bat :app:testDebugUnitTest` → failures=0, errors=0, tests ≥ 1071
- [ ] `python scripts/check_repository.py` → 0
