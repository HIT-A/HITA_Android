# 功能—模块—测试用例映射（阶段一）

数据来源：生产类符号 × 测试源码字符串匹配 + JUnit XML 实测身份。

## 主流程功能映射

| 功能 | 生产入口 | 模块 | 现有测试 | 覆盖度 |
|---|---|---|---|---|
| 应用启动 / 主界面 | `HApplication`、`MainActivity` | app | `MainActivityInstrumentedTest`、`MainStartupCoordinatorTest`、`MainNavigationStateTest`、`MainTabVisibilityTest` | 中（无 UI 元素断言） |
| 主界面导航 | `MainTabHost`、`MainDrawerMapper` | app | `MainDrawerMapperTest`、`MainNavigationStateTest` | 中（纯 JVM） |
| 登录（教务） | `PopUpLoginEAS`、`WebViewLoginActivity`、`EasWebCredentialPolicy` | app | `EasWebCredentialPolicyTest`、`WebLoginSuccessPolicyTest`、`BenbuLoginValidationInstrumentedTest` | **低**（WebView 链无测试） |
| 会话恢复 | `EasSessionRecoveryState`、`EasSessionCookiePersistence` | app | `EasSessionRecoveryStateTest`、`EasSessionCookiePersistenceTest` | 中 |
| 课表加载/切换 | `TimetableFragment`、`DisplayScopeCoordinator` | app | `TimetableHostInstrumentedTest`、`TimetableComponentInstrumentedTest`、`DisplayScopeCoordinatorTest` | 中 |
| 课表导入（EAS） | `EASRepository`、`EasTimetableImportGuard`、`TimetableImportFlowHarness` | app | `TimetableImportFlowInstrumentedTest`、`EasTimetableImportGuardTest`、`EasImportIdentityTest` | 高 |
| 课表写入/修改/恢复 | `TimetableWriteUseCase`、`TimetableScheduleWriteAtomicityTest` | app | `TimetableWriteUseCaseTest`、`TimetableWriteStoreInstrumentedTest`、`TimetableRecoveryProtocolInstrumentedTest` | 高 |
| 好友课表 / 分享 | `TimetableSharingRepository`、`FriendTimetableRepository` | app + core | `FriendTimetableInstrumentedTest`、`FriendImportConfirmationInstrumentedTest`、core 6 个协议测试 | 高 |
| 课表对比 | `TimetableComparisonEngine` | core | `TimetableComparisonEngineTest` | 高（纯 JVM） |
| 成绩查询 | `ScoreInquiryActivity`、`ScoreQueryFlowInstrumentedTest` | app | `ScoreQueryFlowInstrumentedTest`、`ShenzhenAcademicScoreContractTest` | 高 |
| 考试查询 | `ExamViewModel`、`ExamQueryContractHarness` | app | 12 个 exam 契约测试 | 高 |
| 空教室 | `EmptyClassroomViewModel`、`LegacyEmptyClassroom*` | app | 14 个 contract test | 高 |
| 课程资料（Subject） | `SubjectReadmeAgentEngine`、`SubjectResourceCoordinator` | app | `SubjectResourceCoordinatorTest` + 3 个仪器测试 | 中 |
| AI Agent 会话 | `AgentChatViewModel`、`ChatSessionDelivery`、`LlmChatService` | app | `AgentChatViewModelTest`、`ChatSessionDeliveryTest`、`LlmChatServiceTest`、`AgentChatLifecycleInstrumentedTest` | 中 |
| 聊天附件 | `ChatAttachmentProcessor`、`DocumentTextParser` | app | `ChatAttachmentProcessorTest`、`ChatDocumentProcessorInstrumentedTest`、`PdfResourceInstrumentedTest` | 高 |
| 提醒/通知 Worker | `CourseReminderWorker`、`ScoreReminderWorker` | app | `ScoreReminderSessionTest`（仅会话） | **极低** |
| Widget | `WidgetRefreshWorker`、`TodayUtils`、`WidgetUtils` | app | 无 | **无** |
| 直播课程 | `LiveCourseScheduler` | app | 无 | **无** |
| 应用更新 | `UpdateFlowCoordinator` | app | `MainUpdatePresenterInstrumentedTest`（仅 Presenter） | 低 |
| 评分/作业（Grader） | `GraderRepository`、`GraderStore` | app | `GraderStoreTest`、`GraderRepositoryCancellationTest`、`GraderLoginActivity` | 中 |
| 学术日历 | `PublicAcademicCalendarParser`、`AcademicCalendarRefreshPolicy` | app | `PublicAcademicCalendarParserTest`、`AcademicCalendarRefreshPolicyTest` | 高 |
| 用户资料 | `UserPreferenceSource`、`ProfileWebSource` | hitauser | `UserPreferenceSourceInstrumentedTest`、`TypeConverters*Test` | 中 |
| 数据库迁移 | `AppDatabase` (v1→18) | app | `AppDatabaseMigrationChainTest`（JVM）+ `AppDatabaseUpgradePathTest`（设备） | 高 |
| 分析队列 | `UsageQueueDatabase` | app | `UsageQueueInstrumentedTest` | 中 |
| 主题/样式 | `ThemeTools` | style | `ThemeToolsInstrumentedTest`、`SelectableAdaptersInstrumentedTest` | 中 |

## 数据层映射

| 层 | 类 | 测试 |
|---|---|---|
| DAO | `TimetableDao`、`TimetableGroupDao`、`TimetableShareDao`、`FriendTimetableDao`、`SubjectDao`、`EventItemDao`、`ChatSessionDao` | 分散于 androidTest 各 InstrumentedTest |
| Repository | 20+ | 覆盖良好 |
| Web 解析 | `BenbuEASWebSource`、`WeihaiEASWebSource`、33 个 `*ContractTest` | 覆盖最强（MockWebServer） |
| 序列化 | `TimetableShareCodec`、`StrictShareJson`、Gson | 覆盖强 |

## 零覆盖清单（必须补测）

1. `CourseReminderWorker` / `CourseReminderScheduler`
2. `WidgetRefreshWorker` / `WidgetRefreshScheduler` / `WidgetUtils` / `TodayUtils`
3. `LiveCourseScheduler`
4. `AppConstants.Notification`（通知常量；实际通知构建由 Worker 内部完成，已由新增 Worker 测试覆盖）
5. `UpdateFlowCoordinator`（全链路）
6. `PopUpLoginEAS` / `WebViewLoginActivity`（登录 WebView 链）
7. `CourseSelectionAlarmScheduler`
8. 全部 UI 渲染与交互（Espresso/Compose/UI Automator 断言为 0）
