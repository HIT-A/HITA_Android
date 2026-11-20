# B2/B3 低成本对齐与提醒 实施计划

**Goal:** 补齐 8 项低风险功能：公告去重、成绩分布、身份可见性、周号跳转、外链白名单、提醒多档、考试提醒、分源搜索。
**Architecture:** 全部落在既有 ViewModel/Repository/Worker 上，新增的都是纯函数计算器与轻量偏好存储；提醒复用单一 WorkManager 链。
**Spec:** [2026-11-15-b2-b3-quick-wins-and-reminders-spec.md](../specs/2026-11-15-b2-b3-quick-wins-and-reminders-spec.md)

---

## Task 1: 启动提示去重（G-05）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/ui/main/StartupPromptLedger.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/MainViewModel.kt`
- Test: `app/src/test/java/cn/limpu/hita/ui/main/StartupPromptLedgerTest.kt`

- [ ] 测试：同版本二次 claim 返回 false；不同版本各自 true
- [ ] 运行 `--tests *StartupPromptLedgerTest` → FAIL
- [ ] 实现进程内 Set，`claimVersionPrompt(versionCode)` 首次 true
- [ ] 在 `requestNotices` 发 `Notice(Version)` 前与 `checkUpdate` 发 `Update` 前各自 claim
- [ ] 运行 → PASS

## Task 2: 成绩分布纯函数（D-05）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/ui/eas/score/ScoreDistribution.kt`
- Test: `app/src/test/java/cn/limpu/hita/ui/eas/score/ScoreDistributionTest.kt`

- [ ] 测试：全百分制分档正确；混合等级制计入 excluded；全非百分制 `isRenderable=false`；空列表四档计数为 0
- [ ] 运行 → FAIL
- [ ] 实现四档计算（90-100 / 80-89 / 70-79 / <70），非数值分排除
- [ ] 运行 → PASS

## Task 3: 成绩页渲染分布（D-05）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/ui/eas/score/ScoreInquiryActivity.kt`
- Test: 复用 Task 2 的纯函数测试；UI 不单测

- [ ] 在 `ScoreSummaryCard` 下方插入 `ScoreDistributionBlock`
- [ ] 只在 `isRenderable` 时渲染，空态不占位
- [ ] 颜色只用 `colorScheme.primary`/`surfaceVariant`

## Task 4: 身份可见性（I-06）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/data/source/preference/IdentityVisibilityStore.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/MainDrawerMapper.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/navigation/NavigationFragment.kt`
- Test: `app/src/test/java/cn/limpu/hita/ui/main/IdentityVisibilityTest.kt`

- [ ] 测试：`hideIdentity=true` 时映射结果不含学号/姓名/学院
- [ ] 运行 → FAIL
- [ ] 实现 store + mapper 新参数 + 设置项 Switch
- [ ] 运行 → PASS

## Task 5: 周号跳转（B-02）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/timetable/TimetableViewModel.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/timetable/TimetableScreen.kt`
- Test: `app/src/test/java/cn/limpu/hita/ui/main/timetable/WeekOptionsTest.kt`

- [ ] 测试：给定起始周一与总周数，返回正确周一列表；0 周返回空
- [ ] 运行 → FAIL
- [ ] 实现 `weekOptions()`；顶栏标题点击弹单选对话框
- [ ] 运行 → PASS

## Task 6: 外链白名单（F-04）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/ui/subject/ExternalLinkPolicy.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/resource/CourseReadmeActivity.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/subject/SubjectResourceLauncher.kt`
- Test: `app/src/test/java/cn/limpu/hita/ui/subject/ExternalLinkPolicyTest.kt`

- [ ] 测试：白名单 https 通过；子域通过；非白名单拒绝；`javascript:`/`data:`/`file:` 拒绝
- [ ] 运行 → FAIL
- [ ] 实现 `allowed(raw): String?`
- [ ] 替换两处外跳；拒绝时提示且保留复制
- [ ] 运行 → PASS

## Task 7: 提醒规则模型（A-07）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/data/source/preference/CourseReminderStore.kt`
- Create: `app/src/main/java/cn/limpu/hita/data/work/ReminderRuleSet.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/work/ReminderRuleSetTest.kt`

- [ ] 测试：旧 `minutes` 迁移为单档；`leads` 去重并裁剪到 [0,120]；档位超过 3 个被拒
- [ ] 运行 → FAIL
- [ ] 实现 JSON 存储 `rules_v1` + 迁移
- [ ] 运行 → PASS

## Task 8: 提醒调度多档（A-07）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/data/work/CourseReminderWorker.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/work/CourseReminderScheduleTest.kt`

- [ ] 测试：给定事件与档位，下一个触发点取最小延迟；已发送键为 `eventId#lead`
- [ ] 运行 → FAIL
- [ ] 实现按 (event, lead) 排程
- [ ] 运行 → PASS

## Task 9: 提醒设置 UI（A-07）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/navigation/NavigationFragment.kt`

- [ ] 提醒行改为可点击，弹出多选档对话框（最多 3 档）
- [ ] 副标题动态显示当前档位摘要

## Task 10: 考试提醒（D-07）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/data/work/ExamReminderWorker.kt`
- Create: `app/src/main/java/cn/limpu/hita/data/work/ExamReminderScheduler.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/work/ExamReminderScheduleTest.kt`

- [ ] 测试：给定考试时间与档位，延迟正确；过期考试不排程；已发送不重复；档位为空不建 Worker
- [ ] 运行 → FAIL
- [ ] 实现单链 OneTimeWork + 独立渠道 + 已发送记录
- [ ] 运行 → PASS

## Task 11: 分源报告（F-01）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/data/model/resource/ResourceSourceReport.kt`
- Modify: `app/src/main/java/cn/limpu/hita/data/repository/ExternalResourceRepository.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/resource/UnifiedResourceSearchViewModel.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/repository/ResourceSourceReportTest.kt`

- [ ] 测试：一源失败一源成功 → 结果含成功项且 report 标 FAILED；两源均失败仍返回且 reports 均 FAILED
- [ ] 运行 → FAIL
- [ ] 实现 report 模型与逐源捕获
- [ ] 运行 → PASS

## Task 12: 搜索历史（F-01）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/data/source/preference/ResourceSearchHistoryStore.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/resource/UnifiedResourceSearchActivity.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/source/preference/ResourceSearchHistoryStoreTest.kt`

- [ ] 测试：上限 20；重复词前移；清空后为空
- [ ] 运行 → FAIL
- [ ] 实现 store 与 UI（聚焦展示、清空按钮）
- [ ] 运行 → PASS

## Task 13: 批次验收

- [ ] `gradlew.bat :app:assembleDebug` → 0
- [ ] `gradlew.bat :app:testDebugUnitTest` → failures=0, errors=0, tests ≥ 1071
- [ ] `python scripts/check_repository.py` → 0
