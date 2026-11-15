# iOS（HITA Aura）→ Android（HITA）功能差异审计

审计日期：2026-11-14
两端基线：Android `HITA_Android` @ `5992581`（main，工作区干净）；iOS `HITA-Aura` @ `26b06d0`（main，工作区干净）

## 一、口径与限制

- 本报告是**静态代码审计**结果。iOS 端无 macOS/Xcode 环境，未编译、未运行、未请求学校服务；Android 端未构建（本轮仅审计，不改代码）。
- 每条结论给仓库相对 `路径:行号`。凡未亲自读到的，标 `[推断]`；无法确认的单列「待核实」。
- 「完全缺失」判定前，Android 侧至少执行过两组不同关键词 grep（中文文案 + 类名/API）；仅在 `strings.xml` 存在文案、或 UI 未接线的，标注为死文案/未接线。
- 只判功能与行为，不判代码风格。实现方式不同但结果等效 → 「基本一致/完全一致」。

## 二、技术与基线对照

| 项 | iOS HITA Aura | Android HITA |
|---|---|---|
| 语言/UI | Swift 5.x + SwiftUI，869 个 `.swift` | Kotlin 2.2.21 + Compose/ViewBinding 混合，592 个 `.kt` |
| 最低系统 | iOS 18.0（部分功能 iOS 26 起） | minSdk 26 / target 35 / compile 36 |
| 模块 | 单 target + `HITAAuraTodayWidgetExtension` + SwiftPM `HITAAcademicKernel` | `app` / `component` / `hitauser` / `style` / `timetable-core` |
| 状态管理 | Observation `@Observable` + 聚焦 owner | ViewModel + LiveData/StateFlow + Hilt |
| 数据持久化 | UserDefaults / Application Support / 文件 + WAL 日志 | Room（`AppDatabase` v18）+ EncryptedSharedPreferences + 文件 |
| 教务接入 | 4 种 Connector（深圳本科/深圳教职工/本部/威海）+ ApplePlatform Web 登录 | WebView 登录 + Jsoup 解析；深圳另有客户端 Authorization 与 CAS 自动登录 |
| Agent | 注册式 Operation（122 个）+ 通用适配器 + 策略/确认/回执 | ReAct 文本工具（15 个）+ 本地推理循环 |
| 系统集成 | WidgetKit、ActivityKit、AlarmKit、WeatherKit、EventKit、StoreKit、MapKit | AppWidgetProvider、promoted ongoing notification、AlarmManager/WorkManager、无天气/日历/内购 |

## 三、差异总表

### A. 今日页与系统展示

| ID | 功能 | 类型 | iOS 实现与证据 | Android 现状与证据 | 差异要点 | 影响 | 难度 | 建议 |
|---|---|---|---|---|---|---|---|---|
| A-01 | 今日页聚合（当天/进行中/下一节/空档） | 基本一致 | `Views/Today/TodayHomeView.swift:361-470`、`ViewModels/TodayScheduleViewModel.swift:348` | `ui/main/timeline/FragmentTimeLine.kt:342-470`、`TimelineListProjection.kt:20-57` | 两端都按选中课表投影当日；问候语阈值与饭点判断来源不同（iOS 依节次结构，Android 硬编码 12:15/17:10/23:00） | 低 | 低 | 可对齐阈值（低成本） |
| A-02 | 底部 mini 课程条 | 完全缺失 | `Views/Today/MiniCourseBar.swift`、`BottomCourseStripView.swift`、`MainTabView.swift:1312-1358` | grep `MiniCourse\|课程条\|coursestrip\|正在上课` 均无命中 | iOS 有跨 tab 悬浮的当前/下一节课程条，可点击进详情；Android 无 | 中 | 中 | 建议迁移（Android 用 Compose 底部条） |
| A-03 | 今日天气（当前/逐时/详情/按课程建议） | 完全缺失 | `Support/Weather/WeatherClient.swift:104`、`Views/Today/TodayWeatherDetailView.swift`、`TodayWeatherInsightResolver.swift:17-77` | grep `天气\|气温\|预报\|weatherkit\|qweather` 无结果；AndroidManifest 无定位权限 | iOS 用 WeatherKit + 一次性定位，按下一节课/课后/末节课给穿衣带伞建议；Android 无任何天气代码 | 高 | 高 | 方案待定（需第三方天气 API 与定位权限） |
| A-04 | 桌面小组件样式与深链 | 部分缺失 | `HITAAuraTodayWidgetExtension/TodayWidgetExtension.swift:465`、`WidgetSupport/TodayWidgetSupport.swift:169-196`（courseTitle/date/startMinute 深链） | `res/xml/today_widget_info.xml`、`today_slim_widget_info.xml`（`android:configure` 被注释）、`ui/widgets/today/TodayUtils.kt:86-106`、`TodayWidget.kt:66-71`（`EVENT_CLICK` 分支为空实现） | Android 有 2 种小尺寸小组件并随主题明暗配色，但无配置页、无样式/背景选择，**点课程只打开 App，不定位课程** | 中 | 中 | 建议迁移（配置页 + 深链） |
| A-05 | Live Activity / 实时课程 | 平台专属 | `Support/CourseLiveActivity/CourseLiveActivityCoordinator.swift:121-293`（ActivityKit，含天气与 staleDate） | `feature/livecourse/notification/LiveCourseNotificationManager.kt:57-113`（`setRequestPromotedOngoing`）、`LiveCourseGuardService.kt:27`、`AndroidManifest.xml` POST_PROMOTED | iOS 灵动岛/锁屏卡片；Android 提升型常驻通知 + 守护前台服务，课前窗 10 分钟，无天气 | 低 | — | 不迁移（平台专属，Android 已有等效） |
| A-06 | 今日卡片群（考试/作业/校园通知/收藏/Agent） | 部分缺失 | `Views/Today/TodayHomeView.swift:491-556` | `FragmentTimeLine.kt:447-468` 仅考试倒计时 + 考试行；无作业环、无校园通知卡、无收藏、无 Agent 入口 | iOS 今日页是「课程 + 5 张发现卡」的信息聚合；Android 今日页只有课程时间线与考试信息 | 中 | 中 | 按需迁移（推荐先做作业环 + 通知卡） |
| A-07 | 课程提醒规则（提前量/分时段/按课程覆盖/考前提醒/课程闹钟） | 部分缺失 | `Support/CourseReminders/CourseReminderStore.swift:38-64,204-290`（periodRules/courseOverrides/examOverrides）、`Views/Settings/CourseReminderSettingsView.swift:96-136`、`CourseAlarmService.swift:8`（AlarmKit） | `data/source/preference/CourseReminderStore.kt:13-45`（仅 enabled + 单一 minutes，默认 15）、`CourseReminderWorker.kt:27-83`、UI 仅一个开关 `NavigationFragment.kt:589-596`；grep `考前\|examReminder\|考试提醒` 无结果 | iOS 可配分时段规则、单课程/单考试覆盖、考前 24h/12h/1h/30min、系统闹钟；Android 只有全局 15 分钟 | 高 | 高 | 建议迁移（分阶段：多提前点 → 按课程覆盖 → 考试提醒） |
| A-08 | 会话失效提醒的隐藏/关闭 | 部分缺失 | `Application/Today/TodaySessionNoticeOwner.swift:45-66`、`Application/Settings/SessionNotice/SessionNoticeOperations.swift` | `ui/main/MainActivity.kt:302-308` 模态弹窗（重新登录/稍后）、`MainDrawerMapper.kt:73` | Android 是一次性模态弹窗；iOS 区分「仅本次隐藏」与「永久关闭」，且内联在今日页 | 中 | 低 | 按需迁移（可仅加永久关闭偏好） |

### B. 课表浏览与呈现

| ID | 功能 | 类型 | iOS | Android | 差异要点 | 影响 | 难度 | 建议 |
|---|---|---|---|---|---|---|---|---|
| B-01 | 整周网格（分钟轴/非整点/跨周/冲突） | 行为不一致 | `Views/Timetable/TimetableTimeAxis.swift:51-60,142-159`、`TimetableWeekRenderModelBuilder.swift:260-271`（重叠分列） | `ui/main/timetable/TimetableGridGeometry.kt:10-13`（分钟坐标）、`views/TimetableOverlapLayout.kt:43-74`（相交合并为一张「课程冲突」卡） | 节次/非整点/跨周等效；冲突呈现不同（iOS 并排分列，Android 叠卡 + 弹列表） | 中 | 中 | 不建议强改（Android 叠卡是既有设计，且 README 已宣传） |
| B-02 | 周次/学期导航 | 部分缺失 | `TimetableHomeView.swift:2696`（周号跳转） | `TimetableWeekView.kt:153-194`（跟手横滑 + 阈值翻页）、`TimetableScreen.kt:204`（前后周） | 两端都能翻周/切学期；Android 缺「按周号直接跳」 | 低 | 低 | 建议迁移（成本极低） |
| B-03 | 多课表展示模型 | 行为不一致 | `TimetableCollectionSelector.swift:36-39`（单选当前，另单日并排） | `TimetableManagerActivity.kt:97-98`、`TimetableManagerViewModel.kt:126-136`（`DisplayScope.Group` 组合同屏） | Android 可多表同屏叠加；iOS 一次只显示一张 | 高 | — | Android 独有能力，必须保护 |
| B-04 | 展示偏好（节次/地点/颜色/隐藏周末/背景亮度） | 行为不一致 | `TimetablePreferencesOwner.swift:8-16`、`TimetableBackgroundLuminanceSampler.swift:15`（决定卡片字色） | `TimetableStyleRepository.kt:22-53`、`WallpaperLoader.kt:43-44`（只采样顶栏与左轴）、`TimetableScreen.kt:102-119`（色板分配） | 两端都无「隐藏周末」（grep `hideWeekend\|fiveDay\|隐藏周末` 均空）；亮度采样用途不同 | 中 | 中 | 按需迁移（壁纸上的卡片字色自适应） |
| B-05 | 课程详情/上下文菜单/批量编辑 | 行为不一致 | `TimetableCourseDetailView.swift:592-733`、`TimetableCourseScopeEditor.swift:112-154`（按时间组勾选课次） | `TimetableFragment.kt:146-198`（长按菜单 + 作用域选择）、`TimetableBatchCoordinator.kt:22-32`（本次/同时段/全部） | 都有详情、菜单、批量；作用域粒度不同（iOS 按时间组多选，Android 三档） | 中 | 中 | 按需迁移（多选时间组） |
| B-06 | 课表截图分享 | 完全缺失 | `AuraOperationAssembly.swift:187`（`PresentTimetableScreenshotShare`）、`TimetableWeekScreenshot.swift` | grep `截图分享\|shareBitmap\|captureWeek\|PixelCopy\|drawToBitmap` 于 `app/src/main` 无结果 | iOS 可把当前周渲染成品牌图并分享；Android 无 | 中 | 中 | 建议迁移（Compose 离屏渲染 + FileProvider） |
| B-07 | 单日对比（自己的多张课表并排） | 完全缺失 | `TimetableDayComparisonSheet.swift`、`TimetableDayComparisonModels.swift:60-77`（重叠且同地点高亮） | grep `单日对比\|dayCompare\|DayComparison` 无结果 | iOS 可任选多张本地课表按单日并排比对；Android 只有好友课表对比（不同功能） | 中 | 中 | 按需迁移（依赖 B-03 的组合同屏，价值被部分覆盖） |
| B-08 | 课表分享口令与比较 | 平台专属 | 全库 grep `HITA1\|课表口令\|忙闲\|好友课表` 无结果 | `TimetableSharingRepository.kt:49`、`ShareSnapshotBuilder.kt:27-32`（FULL/BUSY）、`TimetableExportRepository.kt:107-117`（超长回退 `.txt`）、`TimetableComparisonEngine` | Android 独有：HITA1 口令、FULL/BUSY、好友导入确认、共同空闲/冲突比较 | 高 | — | Android 独有能力，必须保护 |
| B-09 | 左右滑切页动线 | 基本一致 | `TimetableGrid.swift:401`（分页滚动） | `TimetableWeekView.kt:153-194` | 都是横滑换周，阈值与手感不同 | 低 | 低 | 不建议改 |

### C. 课表编辑、快照、导入导出与系统日历

| ID | 功能 | 类型 | iOS | Android | 差异要点 | 影响 | 难度 | 建议 |
|---|---|---|---|---|---|---|---|---|
| C-01 | 画布拖拽改时间 / 拉伸改节数 | 完全缺失 | `TimetableCourseDragProjection.swift:8`、`TimetableCourseResizeProjection.swift:34` | 无画布拖拽；改时间走对话框（`app/src/main/res/values/strings.xml:1609`「同时修改星期与节次」）、批量作用域 `TimetableBatchCoordinator.kt:22-32` | iOS 直接在网格拖动/拉伸并预览冲突；Android 必须开对话框 | 中 | 高 | 按需迁移（手势与既有 TabSwipe 冲突风险高） |
| C-02 | 删除（单次/系列）与编辑历史恢复 | 行为不一致 | `TimetableEditCommands.swift:60-140`（教务课软隐藏可恢复；手动课物理删除） | `EventItemDao` 物理删除；grep `editHistory\|softDelete\|undoEdit` 无课表编辑恢复 | iOS 误删可从编辑历史找回；Android 只能靠版本快照整体恢复 | 高 | 高 | 建议迁移（软隐藏 + 编辑历史） |
| C-03 | 版本快照管理（备注/保留策略/删除/统计） | 部分缺失 | `TimetableSnapshotCommands.swift:26-36`、`TimetableSnapshotQueries.swift:95-97,175-187` | `TimetableSnapshotStore.kt:16-30`（四类自动快照）、`ImportTimetableActivity.kt:484-510`（仅列出并恢复） | Android 能恢复刷新前/导入前快照，但不能加备注、改保留策略、删除、看统计 | 中 | 中 | 建议迁移（补齐管理页交互） |
| C-04 | 教务刷新冲突评审与「记住忽略」 | 行为不一致 | `TimetableRefreshConflictReviewSheet.swift:21-23`、`TimetableRefreshConflictPolicy.swift:65`（默认勾选保留本地） | `TimetableChangeDialog.kt:40-42`、`TimetableChangeStore.kt:88-202`（忽略指纹 + revision）、`EASRepository.kt:1103-1107`（跨重登保留） | Android 的忽略记忆与 outbox 恢复更强；iOS 的评审页覆盖面更全 | 中 | 中 | Android 更强，不建议回退 |
| C-05 | 关注教学班变更复核 | 部分缺失 | `FollowedTeachingSectionReviewSheet.swift:1-70`、`TimetableWaveBOperations.swift:98-101` | `FollowedTeachingSectionStore.kt:16-151`、`ShenzhenCourseCatalogActivity.kt:398-1861`（关注即同步，无变更复核） | iOS 有「关注班上游改课」的批量复核；Android 只静默重投影 | 低 | 中 | 按需迁移 |
| C-06 | 教务课表导入（差异预览 vs 全量替换） | 行为不一致 | `AcademicTimetableImportOperations.swift:82-84`（prepare 差异 → commit） | `ImportTimetableActivity.kt:464-468`（手动导入是全量替换确认） | Android 手动导入覆盖，自动刷新才走合并（不盖本地保留课）；iOS 手动导入也先出差异 | 中 | 中 | 建议对齐（低风险，复用现有 merge policy） |
| C-07 | ICS 导入预览（逐条有损确认） | 部分缺失 | `ICSImportPreviewView.swift:3,197-200` | `TimetableManagerActivity.kt:130-140,411`（选文件后直接导入或转好友快照） | Android 缺逐条有损/跳过预览 | 低 | 低 | 建议迁移 |
| C-08 | 系统日历同步（课表 + 考试） | 完全缺失 | `EventKitCalendarSyncGateway.swift:42-62,136-168`、`CalendarSyncOperations.swift:45-48`、`AuraAppAssembly.swift:561-614` | grep `CalendarContract\|WRITE_CALENDAR\|READ_CALENDAR` 于 `app/src/main` 无结果 | iOS 可把课表/考试写入系统日历，有授权、按项回读、部分失败；Android 完全无 | 高 | 高 | 建议迁移（Android 用 CalendarContract + 权限） |
| C-09 | 高校课表协议导入（.js 网页提取 / .json） | 完全缺失 | `TimetableProtocolImportPreviewView.swift`、`TimetableProtocolWebExtractorView.swift`、`docs/reference/timetable-import-protocol.md` | grep `WebExtractor\|网页提取\|auraschool` 无结果；Android 的粘贴口令是 HITA1，语义不同 | iOS 支持任意高校适配脚本在 WebView 内提取课表；Android 不支持 | 中 | 高 | 按需迁移（可用性取决于外校用户规模） |

### D. 教务与学业数据

| ID | 功能 | 类型 | iOS | Android | 差异要点 | 影响 | 难度 | 建议 |
|---|---|---|---|---|---|---|---|---|
| D-01 | 多教务账号保存/切换 | 部分缺失 | `AcademicAccountOperations.swift:33-82`、`HubView+Sections.swift:137,433` | `EasPreferenceSource.kt:129-176`（单 token）、`EasCredentialStore.kt:36-39`；grep `savedAccounts\|已保存账号\|账号列表` 无结果 | Android 只保存可回填密码，不能并列多会话切换 | 中 | 中 | 建议迁移（数据结构需改，影响面大） |
| D-02 | 深圳教职工身份 | 完全缺失 | `ShenzhenBearerAuthentication.swift:208-214,270-276`（staff 且无成绩） | grep `staff\|教职工\|principalKind` 无结果 | Android 把 03/06 角色当学生处理 | 中 | 中 | 按需迁移（端上无教职工用户则不做） |
| D-03 | 登录/session/静默续期 | 基本一致 | `AcademicClient.swift:845-884` | `WebViewLoginActivity.kt:108-300`、`EASActivity.kt:56-77`、`docs/login-session-persistence.md` | 机制等效；Android 另有 aTrust/UEM 分支 | — | 低 | 保持 |
| D-04 | 成绩查询与课程详情 | 基本一致 | `AcademicGradeQueries`、`ScoresOverviewPage.swift` | `ScoreInquiryActivity.kt`、`ScoreDetailFragment.kt`、`ScoreSummary.kt:1-20`（均分/GPA/排名） | 两端都有学期切换、汇总、课程详情 | — | 低 | 保持 |
| D-05 | 成绩分布（课程维度分档） | 部分缺失 | `Application/Academic/Grades/ScoreDistribution.swift:54-79`、`ScoresOverviewPage.swift:572-679`（所有校区可见） | `ShenzhenGradeAnalysisActivity.kt:604-620`（分数分布，**仅深圳**且是独立「个人成绩明细」入口）；`ScoreInquiryActivity.kt` 无分布 | iOS 成绩页内嵌分布；Android 仅深圳独立页有分布 | 中 | 低 | 建议迁移（把分布并入成绩页） |
| D-06 | 培养方案完成度 | 行为不一致 | `AcademicCoursePlanningGateway.swift:159-186`（web + webVPN 双路由，仅排除威海研究生）、`CoursePlanCreditsView.swift:26-58` | `CreditStatsActivity.kt:178-408`、`CreditStatsViewModel.kt:39-40`（官方数据**仅深圳**） | 本部/威海无官方培养方案；iOS 无「未计入原因」的等价展示 | 中 | 中 | 按需迁移（受教务接口能力限制） |
| D-07 | 考试提醒（考前多档） | 完全缺失 | `ExamOverviewPage.swift:1491-1600`、`CourseReminderStore.swift:53,970`（24h/12h/1h/30min + 自定义） | grep `考前\|examReminder\|考试提醒` 无结果 | Android 考试无任何通知 | 中 | 中 | 建议迁移（复用 WorkManager 调度） |
| D-08 | 考试备忘录 | 基本一致 | `Application/Academic/ExamMemos/*`、`ExamOverviewPage` | `ExamMemoEditorFragment.kt`、`ExamMemoStore.kt`、`ExamItem.kt:26-37`（memoId） | 两端都能增删改本地考试备忘 | — | 低 | 保持 |
| D-09 | 空教室查询 | 部分缺失 | `EmptyClassroomQueryView.swift:92-182`、`EmptyClassroomQueries.swift:221-276,435`、`EmptyRoomDateQueryResultView.swift:41-177` | `EmptyClassroomActivity.kt:329-355`（学期/楼栋/周）、`EmptyClassroomViewModel.kt:101` | Android 无按日期节次查询、无「与我的课表对照」、无容量 | 中 | 中 | 建议迁移（对照功能复用 B-03 投影） |
| D-10 | 全校课表 / 教学班详情 | 部分缺失 | `AcademicCourseCatalogQueries.swift:117-195`（教学班详情） | `ShenzhenCourseCatalogActivity.kt:568-571`（点击仅取附件）、`GraduateCourseCatalogRepository.kt` | Android 无独立教学班详情页 | 低 | 低 | 建议迁移 |
| D-11 | 选课预览撤销/重做 | 完全缺失 | `AcademicSelectionPreviewOperations.swift:296-381` | grep `撤销\|undo` 于 `ui/eas/catalog` 无结果 | 误加课程只能手动移除 | 低 | 中 | 按需迁移 |
| D-12 | 选课提交与回执 | 基本一致 | `AcademicShenzhenCoursePlanningService.swift:418-449`、`CourseSelectionReceiptsView.swift` | `ShenzhenAcademicWebSource.kt:334-366`（真实提交）、`CourseSelectionExecutor.kt:60-140`、`CourseSelectionJobStore.kt`（持久化 + 中断恢复）、`app/src/main/res/values/strings.xml:545-569` | 都真实提交；Android 另有定时提交、取消、重查，状态含 UNKNOWN | — | — | Android 独有增强，必须保护 |
| D-13 | MFA / 验证码 | 平台专属 | grep `multifactor\|MFA\|验证码` 于 `Views/Academic`、`AcademicKernel` 均无结果 | `WebViewLoginActivity.kt:91-170`（aTrust/UEM 分支）、`InfoPortalLoginActivity.kt:99-117`（通知门户 MFA 提示 + 180s 超时） | 两端都不做原生 MFA 输入，靠 WebView 交互；Android 通知门户另有显式提示 | 低 | — | 不迁移 |

### E. 校园服务与平台能力

| ID | 功能 | 类型 | iOS | Android | 差异要点 | 影响 | 难度 | 建议 |
|---|---|---|---|---|---|---|---|---|
| E-01 | 校园卡（余额/流水/统计） | 完全缺失 | `Views/Academic/CampusCardDetailView.swift:1-70`、`Network/CampusCardPortalClient.swift` | grep `校园卡\|一卡通\|campusCard` 无结果；`app/src/main/res/values/strings.xml:354 navi_campus_card_p` 为死文案 | iOS 有原生校园卡详情（余额、消费图表、商户占比、流水）；Android 只有网页入口 | 高 | 高 | 按需迁移（依赖 dxcdp 门户接口稳定性） |
| E-02 | 静音仓预约 | 完全缺失 | `Views/Academic/SilentPodReservationView.swift`、`ViewModels/CampusPortal/SilentPodReservationViewModel.swift`、`UniversityTownLibraryClient.swift:16-23`（查/订/取消/联系人） | grep `静音仓\|研讨间\|空间预约` 无结果 | iOS 有完整预约流（空间/日期/联系人/我的预约） | 中 | 高 | 按需迁移（用户规模小） |
| E-03 | 大学城图书馆 | 完全缺失 | `Views/Academic/UniversityTownLibraryCard.swift`、`UniversityTownLibraryRepository.swift` | grep `utszlib\|大学城图书馆` 无结果；`app/src/main/res/values/strings.xml:196` 只是本部图书馆外链 | iOS 有座位/空间快照卡片 | 中 | 中 | 按需迁移 |
| E-04 | 校园内网服务聚合（学费/实验中心/公寓） | 部分缺失 | `CampusIntranetServicesDock.swift`、`CampusTuitionClient.swift`（`lab.hitsz.edu.cn` GvSun LIMS 个人课表 + 场次统计）、`CampusLabCenterClient.swift:5-45`、`CampusServiceSessionTargets.swift:113-175` | 实验课：**已有**——`BenbuEASWebSource.kt:309-330` 拉「电子实验中心」课程并合并进课表（`eelabinfo-...vpn.hit.edu.cn`），但仅本部、只进课表、无场次统计；学费/公寓：grep `学费\|公寓\|缴费` 无实现（`app/src/main/res/values/strings.xml:175` 仅外链） | iOS 有深圳实验中心场次统计卡片 + 学费/公寓 SSO 入口；Android 只有本部实验课并入课表，其余走外链 | 中 | 中 | 按需迁移（仅补深圳实验中心统计与外链化入口） |
| E-05 | 校园地图（楼栋标注/定位/自定义 pin） | 完全缺失 | `Views/CampusMap/*`（MapKit，iOS 26 起）、`docs/features/campus-map.md`、`Support/CampusMap/CampusAtlasDecoder.swift` | grep `geojson\|MapView\|地图 SDK` 无结果；`app/src/main/res/values/strings.xml:194` 是本部地图外链 | iOS 三校区内置地图集 + 教室→楼栋定位 + 自定义地点；Android 无 | 中 | 高 | 按需迁移（需地理数据与地图 SDK） |
| E-06 | 网络抓包/诊断设置页 | 平台专属 | `Views/Settings/NetworkCaptureSettingsView.swift` + `HubView+Sections.swift:121-123`（仅 DEBUG 或 TestFlight 可见，`Support/Diagnostics/NetworkCaptureStore.swift:49-55`） | grep `抓包\|网络诊断` 无结果（`LogUtils` 有日志脱敏） | iOS 仅调试/TestFlight 构建可见；不属于产品功能 | 低 | — | 不迁移（开发者工具） |
| E-07 | 实用网址分组 + 长按复制 | 平台专属 | grep `实用网址\|UsefulLinks` 无结果 | `UsefulLinksActivity.kt:104-215,355-360`（三校区四分组、长按复制） | Android 独有 | — | — | 必须保护 |
| E-08 | 三校区公开校历 / 假期判定 | 平台专属 | 仅 `AcademicWebConnectors.swift:1112` 推断开学日 | `AcademicCalendarRepository.kt:26-80`、`TimetableCalendarTitle.kt:9-35`、`docs/academic-calendar.md` | Android 有官方周次与寒暑假 | — | — | 必须保护 |

### F. 课程资源、搜索与教师

| ID | 功能 | 类型 | iOS | Android | 差异要点 | 影响 | 难度 | 建议 |
|---|---|---|---|---|---|---|---|---|
| F-01 | 三源统一搜索 + 分源报告 + 搜索历史 | 行为不一致 | `CourseResourceQueries.swift:142-177`（`sourceReports`）、`UnifiedCourseResourceSearchViewModel.swift:158-210` | `UnifiedResourceSearchViewModel.kt:46-78`（HOA + 外部合并，单源失败仍 SUCCESS）、`ExternalResourceRepository.kt:26-41`；grep `搜索历史\|SearchHistory` 无结果 | 一源失败时用户会误判「无资料」；Android 无搜索历史 | 中 | 中 | 建议迁移（分源状态 + 历史） |
| F-02 | README 渲染与文件打开 | 部分缺失 | `HoaMarkdownRenderer.swift:80-88`、`HoaMarkdownContentView.swift:176-436`、`UnifiedCourseResourceViews.swift:645-1052`（QuickLook：pdf/office/图片） | `CourseReadmeActivity.kt:321-333`（Markwon Html/Table/Glide/Latex）、`UnifiedResourceSearchActivity.kt:239-264`（直接下载） | Android 应用内无 PDF/文本预览，无代码/链接复制，无分享 | 中 | 中 | 建议迁移 |
| F-03 | 资料投稿（幂等 + PR 历史回读） | 部分缺失 | `CourseResourceContributionOwner.swift:199-262`（固定 idempotencyKey，outcomeUnknown 不重投）、`HoaPullRequestHistoryView.swift` | **两条投稿路径都不是幂等键**：UI 走 `HoaResourceSource.kt:409`（`UUID.randomUUID()`）；Agent 走 `PrServerClient.kt:26-32,109-115`（`buildSubmitIdempotencyKey` = `android-<code>-<System.nanoTime()>`）。`CourseContributionActivity.kt:250-269` 有校验+预览；grep `投稿历史\|PullRequestHistory` 无结果 | 任一路径超时重试都会生成新 key → 重复 PR；无历史回读 | 高 | 高 | 建议迁移（两处统一为内容哈希幂等键 + 历史） |
| F-04 | 外部链接白名单与导出 | 行为不一致 | `CourseResourceQueries.swift:434-435`（仅 github.com、fireworks.jwyihao.top） | README 任意 http(s) `ACTION_VIEW`（`CourseReadmeActivity.kt:175`）；薪火前缀 + ghproxy 下载 | Android 可能任意外跳；无文件保存/分享 | 中 | 中 | 建议迁移（白名单 + 导出） |
| F-05 | 教师搜索 | 行为不一致 | `HoaCourseResourceLinker.swift:83-94`（homepage.hit.edu.cn 拼音检索） | `SearchTeacherViewModel.kt:20-52`、`ActivityTeacherOfficial.kt`、`TeacherWebSource.kt:25`（faculty.hitsz.edu.cn 主页/联系方式） | Android 有深圳教师网联系方式，缺全校拼音检索页 | 中 | 中 | 按需迁移（合并两端入口） |
| F-06 | 课程评价展示与提交 | 基本一致 | README + 投稿 | README + `SubmitReviewTool.kt` | Android 的 Agent 提交无二次确认（见 J-06） | 中 | 中 | 见 J-06 |

### G. 资讯、公告与博客

| ID | 功能 | 类型 | iOS | Android | 差异要点 | 影响 | 难度 | 建议 |
|---|---|---|---|---|---|---|---|---|
| G-01 | 三校区校园通知 | 行为不一致 | 深圳 `https://info.hitsz.edu.cn`、本部 `today.hit.edu.cn`、威海 WebVPN | `CampusNoticeParser.kt:18` 固定 **http**（注释说明 https 会坏）、`InfoPortalLoginActivity.kt:99-117`（MFA 提示 + 180s 超时）、`CampusNoticeRepository.kt:127-149` | 协议与登录路径不同；Android 无通知门户的「长按复制/分享」一致性 | 中 | 中 | 按需迁移（复制/分享） |
| G-02 | 讲座/活动资讯 | 部分缺失 | 无独立讲座列表（grep「讲座」于 iOS 全仓无结果） | `ActivityLecture.kt:68`、`LectureViewModel.kt:13`、`AdditionalRepository.kt:14` — 全仓 grep 仅 Manifest 注册与自身类，无任何 `startActivity` | Android 已实现但入口未接线（用户不可达） | 低 | 低 | 建议接线（低成本，需先确认 `AdditionalService` 线上可用） |
| G-03 | HOA 博客阅读器 | 基本一致 | `NewsArticleHTMLRenderer.swift:60-118` | `BlogHtmlRenderer.kt:17-21`（霞鹜文楷）、`BlogRepository.kt` | 字体与排版不同，能力等效 | 低 | 低 | 保持 |
| G-04 | App 公告（服务/故障/版本/关键阻断） | 基本一致 | `Views/Settings/AppNoticesView.swift`、`AppNoticeFullScreenView.swift:110-170`（whatsNew 分条排版） | `data/notice/AppNoticeCenter.kt:22-37,95-160`（version/service/incident + critical）、`AppNoticesActivity.kt:44-165`、`MainNoticePresenter.kt:46-74` | Android 无 iOS 的 whatsNew 结构化排版与「全文页」；已读粒度更粗 | 中 | 中 | 按需迁移（全文页） |
| G-05 | 更新检查与公告联动 | 基本一致 | 公告 `oncePerVersion` + 独立更新通知中心 | `MainNoticePolicy`、`MainUpdatePresenter.kt:22-31`、`UpdateRepository.kt:18-260` | 两条通道都在，可能双弹 | 中 | 低 | 建议去重 |
| G-06 | 资讯 tab 可见性 | 行为不一致 | `ViewModels/News/NewsTabAvailability.swift:14-28`（登录即显示；博客仅深圳非教师） | `MainTab.kt:25-48`（默认关，需设置开启；`showsBlog` 只看深圳） | 默认可见性与教师账号规则不同 | 低 | 低 | 按需迁移（教师排除） |

### H. 待办、作业与提醒投递

| ID | 功能 | 类型 | iOS | Android | 差异要点 | 影响 | 难度 | 建议 |
|---|---|---|---|---|---|---|---|---|
| H-01 | 待办增删改查、分组、完成撤销 | 基本一致 | `HomeworkManagementView.swift:59-118,396-404` | `HomeworkScreen.kt:57-300`、`HomeworkBoard.kt`、`HomeworkViewModel.kt:66-113` | Android 有日期分组、未安排置底、周条、快捷添加、Snackbar 撤销 | — | — | Android 更强，保持 |
| H-02 | 作业字段（科目/提交方式/格式/附件） | 部分缺失 | `Models/Homework/HomeworkItem.swift`（subject/content/deadline/submissionMethod/submissionDetail/submissionFormat/attachments） | `data/model/homework/HomeworkItem.kt:3-15`（仅 title/createdAt/completed/plannedDate/plannedTime） | Android 只有标题与日期 | 中 | 中 | 建议迁移（含 Room 迁移） |
| H-03 | Grader 作业同步 | 行为不一致 | `Application/Homework/GraderHomeworkSynchronizer.swift:37-76`、`HomeworkOwner.swift:140-200`（合并进待办、`grader-` 前缀去重、已提交即完成） | `GraderRepository.kt:54-190`、`GraderStore.kt:24-60`（按账号隔离）、`GraderParser.kt:80-93`（已提交/过期直接隐藏）、`HomeworkScreen.kt:306-340`（独立卡片） | iOS 合并为待办项并标完成；Android 独立账号隔离、已提交隐藏、不与手写条目去重 | 中 | 中 | 按需迁移（去重 + 完成态） |
| H-04 | 新成绩提醒 | 基本一致 | `Support/Notifications/UpdateNotificationCenter.swift:25`、`UpdateNotificationSettingsView.swift:14-37` | `ScoreReminderWorker.kt:76-160`、`ScoreReminderScheduler.kt:19`（30 分钟）、开关在 `ScoreInquiryActivity.kt:639` | 都是基线快照 + 差集；iOS 可分类型关闭，Android 单开关 | 低 | 低 | 按需迁移（分类型开关） |
| H-05 | 通知渠道/权限/免打扰 | 部分缺失 | `Support/Notifications/*` | `CourseReminderWorker.kt:112-140`、`ScoreReminderWorker.kt:157-170`、`LiveCourseNotificationManager.kt` | Android 三个渠道（课程/成绩/选课/实时课程），无免打扰策略 | 低 | 低 | 按需迁移 |

### I. 身份、外观、设置、隐私与变现

| ID | 功能 | 类型 | iOS | Android | 差异要点 | 影响 | 难度 | 建议 |
|---|---|---|---|---|---|---|---|---|
| I-01 | 「我的」入口形态与菜单集 | 行为不一致 | `HubView+Sections.swift:17-126`（头像进 Hub：Pro/外观/小组件/提醒/Agent/关于/隐私） | 抽屉（`MainDrawer.kt:211-219`：主题/协议/关于/赞赏）+ 更多页（`NavigationFragment.kt:520-640`：课表管理/教务/资源/设置） | iOS 偏账号与个性化；Android 偏工具入口 | 中 | 中 | 不建议照搬（Android 抽屉是既有导航模型） |
| I-02 | 账号体系 | 行为不一致 | 教务多账号 + 本地昵称（`WelcomeView.swift:29-30`）；`Models/User/UserLocal.swift` 存在但 grep `register\|signup` 无服务实现 | `hitauser` 完整注册/登录/资料（`WelcomeActivity.kt`、`SignUpFragment.kt`、`MyProfileActivity.kt`） | Android 有 HITA 账号服务；iOS 无（模型疑为移植遗留） | 中 | — | Android 独有能力，必须保护 |
| I-03 | 头像（裁剪/历史/动图） | 部分缺失 | `Views/Avatar/AvatarCropEditorView.swift:42`、`AvatarManagementSheet.swift:139-506`、`LocalAvatarStore.swift:211` | `LocalUserRepository.kt:84-106`（Gallery → 512 居中裁剪写单文件）、`MainActivity.kt:269-274` | Android 只能选图自动裁剪，无裁剪编辑器、无历史、无动图、无装饰 | 低 | 中 | 按需迁移（裁剪编辑器优先） |
| I-04 | 外观（亮暗/主题/字体/壁纸/课程色） | 行为不一致 | `AppAppearanceStore.swift:39-70`（系统/浅/深/日出日落）、`HubView.swift:593,653,664`（按页壁纸/应用图标/课程色策略） | `style/.../ThemeTools.kt:9-10`（3 模式 + 7 风格）、`HitaFontScheme.kt`、`WallpaperStore.kt:14`（仅课表壁纸） | Android 在风格/字体/课程色上更强；缺按页壁纸、应用图标切换、日出日落 | 中 | 中 | 按需迁移（仅补日出日落/按页壁纸） |
| I-05 | 小组件设置（尺寸背景/展示范围） | 完全缺失 | `Application/Settings/Widget/WidgetSettingsCommand.swift:1-70`、`AppBackgroundSettingsView.swift:1152-1230` | grep `小组件.*设置\|桌面组件\|AppWidgetConfigure` 无结果 | 见 A-04 | 中 | 中 | 与 A-04 合并 |
| I-06 | 隐私（身份可见性 / 应用内分区清理） | 部分缺失 | `IdentityVisibilityPreferenceModels.swift:31`、`HubView+Sections.swift:526` | 协议齐全（`UserAgreementDialog.kt:141-145`）；`app/src/main/res/values/strings.xml:973 profile_policy_items` 无引用；清数据仅登出删课表（`ProfileActivity.kt:111`） | 无身份可见性开关、无分区清理 | 中 | 中 | 建议迁移（隐私可见性） |
| I-07 | 一键回归初始状态 | 完全缺失 | `Support/AppFactoryResetCoordinator.swift:6-240`（分量日志、断点续跑、Widget App Group 清理） | grep `恢复出厂\|重置全部\|清除所有\|wipe` 无结果 | Android 无整体抹除入口 | 中 | 中 | 建议迁移（含小组件数据清理） |
| I-08 | 变现（Pro 权益 / 内购） | 部分缺失 | `Monetization/MonetizationStore.swift:83-460`、`MonetizationProductIDs.swift:2`、`HITAEntitlementPolicy.swift:21-64`（StoreKit） | grep `会员\|订阅\|内购\|billing\|purchase` 无结果；仅有赞赏码（`MainScreen.kt:334-346`） | Android 无 Pro 权益与内购；自愿支持用赞赏码等效 | 中 | 高 | 按需迁移（需上架政策与 Play Billing 合规） |
| I-09 | 使用统计 | 行为不一致 | `Support/UsageAnalytics/UsageAnalyticsClient.swift:30`；无用户开关 | `UsageAnalyticsClient.kt:81-205`、`docs/analytics-v2.md`、开关 `NavigationFragment.kt:606-618` | Android 有完整 v2 队列 + 开关；iOS 无关闭入口 | — | 低 | Android 更强，保持 |
| I-10 | 欢迎页/引导/演示模式/应用图标 | 部分缺失 | `WelcomeView.swift:29-50`（引导 → 昵称 → 教务登录，含演示模式）、`AppIconManager.swift` | `WelcomeActivity.kt:64`（登录/注册 Tab）；`app/src/main/res/values/strings.xml:88 guide_1p` 无引用；grep `演示\|demo` 无结果 | Android 无引导步骤（文案未接线）、无演示模式、无图标主题 | 低 | 中 | 按需迁移（演示模式价值较高） |

### J. AI 助手 / Agent

| ID | 功能 | 类型 | iOS | Android | 差异要点 | 影响 | 难度 | 建议 |
|---|---|---|---|---|---|---|---|---|
| J-01 | Agent 能力架构与覆盖面 | 部分缺失 | `AuraOperationAssembly.swift`（注册 122 个 Operation）、`Application/Core/AuraOperationRegistry.swift:209-213` | `agent/tools/ReActToolRegistry.kt:1-40`（15 个文本工具）、`LlmChatService.kt:46-50`（本地 ReAct） | iOS 每能力有引用/回执/对账；Android 是自由文本 observation，覆盖差一个量级 | 高 | 高 | 分阶段迁移（先补结构化读能力） |
| J-02 | 多会话、持久化、中断恢复 | 行为不一致 | `AgentConversationStore.swift:25-70,195`（checkpoint + journal，未完成标 failed） | `ChatSessionStore.kt`、`ChatDao.kt`（Room 多会话）、`AgentChatFragment.kt:161,625`（生成中仅禁输入，无停止） | Android 有多会话切换/删除，无运行检查点、无停止按钮 | 中 | 中 | 建议迁移（停止 + 检查点） |
| J-03 | 附件解析与多模态 | 行为不一致 | `Network/AgentAttachmentContent.swift:9-11`（拒 PDF/Office/图/视频，只解文本） | `ChatAttachmentProcessor.kt:36-91`（PDF/DOCX/XLSX/PPTX/TXT + 图/视频走智谱）、`DocumentTextParser.kt` | **Android 明显更完整** | — | — | Android 独有能力，必须保护 |
| J-04 | 模型与密钥 | 行为不一致 | `AgentBYOKConfiguration.swift`（自定义 baseURL + model + key） | `AiSettingsRepository.kt:15-46`（内置/自定义 DeepSeek + 自定义智谱） | iOS 可改 endpoint/model；Android 双 Key 固定端点 | 中 | 中 | 按需迁移 |
| J-05 | 工具集对照 | 部分缺失 | 课表/日程/空教室/资源 search·overview·structure·document/教师/评价；grep `web_search\|rag_search\|crawl_` 无结果 | 15 项含上述 + `web_search`/`rag_search`/`crawl_page\|site\|status`（`agent/tools/*`） | Android 多检索/爬取；少资源分段读与结构化详情 | 高 | 高 | 见 J-01 |
| J-06 | 危险操作确认与回执 | 行为不一致 | `PolicyEngine.swift:66-67`（变更必须本机确认）、`outcome_unknown` 禁止重放 | `AddActivityTool.kt:29-36`、`SubmitReviewTool.kt:83-88` 直接写；`ReActToolRegistry.kt:26-28` 非抛错即 `unknown` | 无确认、无结果未知回执 → 可能误改课表/重复 PR | 高 | 中 | **建议优先迁移**（安全相关） |
| J-07 | 入口与搜索根页 | 行为不一致 | `AgentSearchRootView.swift:3-8`（Agent/搜索切换） | `SearchRoot.kt`（仅取搜索词）；助手 tab 默认关（`MainTab.kt:39-52`） | 入口模型不同 | 低 | 低 | 按需迁移 |

### K. 补漏：Agent 服务端契约与跨页呈现

> 本节由第二轮补漏审计新增，覆盖原「仍可能遗漏」中的 `Server/AgentGateway` 与 `Views/Shell` 两块。

| ID | 功能 | 类型 | iOS | Android | 差异要点 | 影响 | 难度 | 建议 |
|---|---|---|---|---|---|---|---|---|
| N-01 | Agent 日配额 / 安装令牌 / Pro 权益令牌 | 完全缺失 | `Server/AgentGateway/server.mjs:68-221`（`/v1/agent/config\|installations\|entitlements:verify\|tasks`）、`quota-store.mjs:29-69`（先预留、provider 2xx 才提交）、429 `daily_quota_exhausted`、`Network/AgentBackendClient.swift:373-382`（`X-HITA-Entitlement`） | `agent/llm/LlmClient.kt:92-94` 直连 `api.deepseek.com`，`LlmChatService.kt:128-142` 把 429 当可重试；grep `daily_quota\|X-HITA-Entitlement\|installation_token` 无命中 | iOS 有免费 50/Pro 500 每日额度、安装令牌、任务租约与错误码；Android 直连 provider，429 反复重试后报「推理失败」 | 高 | 高 | 待定（需后端与商业决策） |
| N-02 | 服务端模型路由与思考档 | 行为不一致 | `Server/AgentGateway/server.mjs:17-23,218-219`（openai/deepseek 白名单）、`adaptive-reasoning.mjs:20-46`（按周次/跨表/冲突升 fast↔deep） | `LlmClient.kt:92-94,178-181` 固定 `deepseek-v4-flash` + 智谱 `glm-4.6v-flash`；`LlmChatService.kt:99-100,404` 本地 30 步硬停 | iOS 由服务端按任务难度换档并保留 reasoning 历史；Android 客户端固定模型 | 中 | 高 | 待定（N-01 的前置） |
| N-03 | 每轮宿主指令（turn directive / host context） | 完全缺失 | `Server/AgentGateway/turn-directive.mjs:1-64`（当前功能、workflow、时区、directive）、`Server/AgentGateway/server.mjs:234-241` 注入 `model:stream` | grep `turnDirective\|turn_directive\|host_context` 于 `agent/` 无命中；仅有 `ReActToolRegistry.kt:50-64` 的 15 个文本工具 | iOS 每轮告知模型当前页面与允许范围；Android 无页面上下文注入 | 中 | 高 | 待定 |
| N-04 | 类型化功能呈现 + 跨页上下文回执 | 部分缺失 | `AuraFeatureCatalog.swift:3-39`（34 个 Feature）、`PresentAppFeature.swift:159-206`（只接受学期/日期/周次/课表/课程/教学班/资源）、`AuraFeaturePresentationCoordinator.swift:119-137`（须 `context_applied`） | 无 shell 级路由：grep `feature_id\|context_applied\|deepLink` 于 `ui/main` 无命中；仅有 Activity 级 extra（`CourseReadmeActivity.kt:92-93`、`CourseResourceSearchActivity.kt:96`）；Manifest 只有 `hita.store/search` 一条外部深链（`app/src/main/AndroidManifest.xml:144-159`）；`hita://` 仅作 PendingIntent 内部标识（`CourseSelectionAlarmPolicy.kt:23`、`LiveCourseScheduler.kt:165`） | iOS 能把「打开第 8 周课表 / 某个教学班 / 某份资料」作为带上下文的呈现请求并回执已应用；Android 只能在页面内传 extra，无法从外部精确落到周次/课程 | 高 | 高 | 按需（与 A-04 深链同源） |
| N-05 | 公告深链（`hita://about\|settings\|notices`） | 完全缺失 | `Server/AdminConsole/server.mjs:51,89-91`（白名单校验）、`AppNoticeModels.swift:135-180`（`deepLink` 字段）、`AppNoticeFullScreenView.swift:223-242` | `AppNoticeCenter.kt:26-36` 无 `deepLink` 字段；grep `deepLink\|hita://\|actionUrl` 于 `data/notice` 无命中 | iOS 公告可携带跳转到关于/设置/公告页；Android 公告纯文本 | 低 | 低 | 建议迁移（与 G-04 同批） |

## 四、Android 独有能力清单（迁移时必须保护）

1. 课表组合同屏（`DisplayScope.Group`）、HITA1 口令 FULL/BUSY、好友导入确认、超长回退 `.txt`。
2. 课表比较（共同空闲/同节冲突）与作息一致性校验。
3. 刷新冲突「记住忽略」指纹 + 变更 outbox 恢复（跨重登保留）。
4. 课表版本快照自动保存与恢复。
5. 晚课「还有更多课程」提示、压缩一屏、壁纸遮罩、气泡样式。
6. 选课真实提交 + 定时提交 + 取消/重查 + 凭证代际隔离。
7. Grader 按账号隔离缓存与已提交过滤。
8. 三校区公开校历与假期判定。
9. 实用网址分组 + 长按复制。
10. 7 套主题风格 + 字体方案 + 课程色分配算法。
11. 待办日期分组/未安排置底/周条/完成撤销。
12. 实时课程提升型通知 + 守护前台服务（OEM 兼容）。
13. 使用统计 v2 队列 + 用户开关 + 暗色对比度审计基线。
14. 资讯/助手/待办可选底栏模块开关。
15. Agent 附件 PDF/Office 解析、图/视频多模态、双 API Key、web/RAG/crawl 工具。
16. 深圳教师网联系方式入口。

## 五、覆盖情况与统计

**已审阅目录**

- iOS：`Views/{Today,Timetable,Academic,Academic/CoursePlanning,Hoa,Settings,News,CampusMap,Agent,Monetization,Appearance,Avatar,Welcome,Shell,Common}`、`Application/{Timetable,Calendar,Today,Academic/**,Homework,Notices,Settings/**,CourseResources,CampusMap,Core,Product}`、`ViewModels/{News,Home}`、`Support/{Weather,CourseLiveActivity,CourseReminders,Notifications,ICS,Calendar,AppNotices,Agent,CampusMap,UsageAnalytics,Avatar,Appearance,Diagnostics,TimetableProtocol}`、`Packages/HITAAcademicKernel`、`Monetization`、`docs/{features,project,reference}/**`。
- Android：`ui/**`（main/timeline、main/timetable、timetable/**、eas/**、resource、subject、search、teacher、news、notice、blog、links、widgets、profile、myprofile、about、welcome、credit、event、design）、`agent/**`、`data/{model,repository,source,work,notice,analytics}/**`、`feature/{livecourse,timetable}`、`timetable-core/**`、`res/values/strings.xml`、`res/xml/*widget*`、`AndroidManifest.xml`、`app/build.gradle`、`gradle/libs.versions.toml`、`docs/**`。

**统计**：确认差异条目 **86 项**（A–J 主表 81 项 + K 补漏表 5 项，按行计）。按类型：完全缺失 19、部分缺失 25、行为不一致 24、基本一致 12、平台专属 6、**待核实 0**（原 3 项已在第二轮结案，见第六节）。

按域分布：A 今日与系统展示 8、B 课表浏览 9、C 课表编辑/导入导出 9、D 教务与学业 13、E 校园服务 8、F 课程资源 6、G 资讯公告 6、H 待办提醒 5、I 身份外观设置 10、J Agent 7、K 补漏 5。

**已验证的方式**：Git 双端状态核对、目录与符号级静态阅读、逐项双关键词 grep、调用链追踪（入口 → VM/owner → repository → 网络/系统能力）。**未执行**：iOS 编译/Simulator、Android 构建/测试/设备运行、真实校园服务请求。

**第二轮已补**：`Server/AgentGateway/*.mjs`（配额/安装令牌/权益/turn directive）、`Server/AdminConsole`（公告深链）、`Views/Shell/Choreography`（确认只记滚动 offset，无跨页上下文契约）、`Application/Product` Feature Catalog（34 项 vs Android 39 个 Activity，见 N-04）。

**仍可能遗漏**：Android `ui/main/timetable/panel` 与 `views` 的渲染细节；两端 i18n 文案与无障碍标签逐条比对；iOS `Views/Common/Markdown` 的富文本边界。

**引用可复现校验**：`python docs/audit/verify_citations.py` 解析本报告与选项文档中全部 `路径:行号` 反引号引用，按仓库真实文件与行数核对。当前 **236 条全部通过**（0 失效）。含 `...` 的省略式引用被跳过。

## 六、原待核实项（已在第二轮全部结案）

| 编号 | 原问题 | 结论 | 证据 |
|---|---|---|---|
| V-01 | 主网格跨午夜课程是否被 DAO 拆两段 | **不拆**。DAO 只按 `from < toT AND to > fromT` 取相交事件，渲染按 `TimetableGridGeometry.card` 的 `durationMinutes` 直接算高度，无跨午夜切分 | `EventItemDao.kt:45-52,140-146`、`TimetableGridGeometry.kt:10-14` → 归入 B-01「跨午夜可能画出列外」 |
| V-02 | 全校课表页是否有「关注班变更复核」 | **无**。工具栏只有返回与刷新；`FollowedTeachingSectionStore` 在 `ui/eas/catalog` 无调用方 | `ShenzhenCourseCatalogActivity.kt:803-833`，grep `复核\|pendingChange` 于该目录无命中 → C-05 保持「部分缺失」 |
| V-03 | 两端公告是否同一后端 | **不是**。iOS 走 `https://hita.strayrn.com/v1/app/config`（`HITA_APP_NOTICE_BASE_URL`）；Android 走 `https://api.hita.limpu.cn/api/notices?app_id=hita-android` | `Configuration/HITAAura.xcconfig:11`、`AppNoticeService.swift:72`、`app/build.gradle:81`、`AppNoticeCenter.kt:179` → G-04 的「容量/字段差异」不能假定同源 |
| V-04 | iOS 是否有 HITA 账号注册服务 | **无**。`Server/` 只有 `AdminConsole` 与 `AgentGateway`，无用户注册端点 | `Server/` 目录、grep `register\|signup\|/api/user` 无命中 → I-02 判定成立 |
| V-05 | 成绩提醒轮询周期 | **30 分钟周期 + 立即一次**，仅 `NetworkType.CONNECTED` 约束 | `ScoreReminderScheduler.kt:13-38` → H-04 结论不变 |
| V-06 | iOS 是否有独立「智能选课推荐」 | **无独立推荐引擎**，只有 `SearchAcademicSelectionCandidates` 等检索/预览能力；`Application/CoursePlanning` 与 `Views/Academic/CoursePlanning` grep `推荐/recommend` 无命中 | → D-11 与「选课助手」域中 Android 的智能推荐属 Android 独有能力 |
| V-07 | 研究生课程目录两端一致性 | iOS kernel 有独立 `graduateConnectorID` + `AcademicWeihaiGraduateSchoolTimetableService`（loadFilterData/query/loadTermContext）；Android 有 `GraduateCourseCatalogRepository` | 两端都有研究生路由，字段级差异未逐字段比对，但功能存在性一致 → D-10 仅「详情页缺失」成立 |
| V-08 | 讲座是否有非 Kotlin 入口 | **无**。全仓（含非 Kotlin）仅 Manifest 注册与自身类，无任何 `startActivity` | `app/src/main/AndroidManifest.xml:181`、`ActivityLecture.kt:68` → G-02 由「待核实」改判「部分缺失（实现存在但不可达）」 |

仍未验证（需真机/联调，非静态可判）：

- iOS `Server/AgentGateway/*.mjs` 的配额/权益字段与 Android 后端契约的字段级差异。
- 两端公告后端是否在业务上共享同一份公告内容（URL 不同不代表内容不同）。
- 研究生课程目录字段名映射是否一致（需真实教务返回样本）。

## 六之二、迁移前基线构建验证（2026-11-14）

| 项 | 命令 | 结果 |
|---|---|---|
| Debug 构建 | `:app:assembleDebug`（JDK 21） | BUILD SUCCESSFUL，41.98s |
| app 单元测试 | `:app:testDebugUnitTest`（清空结果目录后重跑） | 176 个 XML，**1071 项，0 失败，0 错误，1 跳过** |
| 其他模块 JVM 测试 | `:timetable-core:test :component:testDebugUnitTest :hitauser:testDebugUnitTest :style:testDebugUnitTest` | timetable-core 57、component 7、hitauser 4，均 0 失败；style 无 JVM 测试源（与 AGENTS.md 说明一致） |

构建环境：JDK 21（`~/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2`），Gradle 8.11.1，JDK 25 下 Gradle 可启动但按 README_DEV 要求使用 JDK 21。工作区为干净的 `docs/ios-android-audit` 分支，仅新增 `docs/audit/`。

**含义**：迁移前基线健康，后续每批实施都可与这组数字对比（1071 + 68 项）。本次未执行 Debug APK 的真机安装与 instrumentation 测试。

## 七、优先级建议

优先级 = 用户价值 × 覆盖广度 ÷ 成本，且**不因实现简单就推荐**、**不因实现复杂就排除**。

**P0 — 安全/正确性相关，建议优先处理**

- J-06 危险操作确认与结果未知回执（Agent 直接写课表 / 重复投稿 PR）。
- F-03 投稿幂等（重复 PR 会污染上游仓库）。
- C-06 手动教务导入改为差异预览（避免覆盖自动刷新保留的本地课）。

**P1 — 高价值、中等成本，建议纳入近期迁移**

- G-05 公告与更新双弹去重（仅影响体验，非正确性问题，故不入 P0）。
- A-02 mini 课程条、A-04 小组件配置 + 深链、A-07 课程提醒规则。
- C-02 软隐藏 + 编辑历史、C-03 快照管理、C-08 系统日历同步。
- D-05 成绩分布并入成绩页、D-09 空教室日期/对照。
- F-01 分源报告 + 搜索历史、F-02 资源文件预览与复制、F-04 外链白名单。
- H-02 作业字段扩展、I-06 身份可见性、I-07 一键回归初始状态、N-05 公告深链。

**P2 — 价值明确但依赖外部能力或用户规模有限**

- A-03 天气（第三方 API + 定位权限）、B-06 截图分享、B-07 单日对比、C-01 拖拽编辑、C-09 高校协议导入。
- E-01 校园卡、E-02 静音仓、E-03 图书馆、E-04 内网服务、E-05 校园地图。
- I-08 变现（需 Play Billing 与上架合规评估）。
- N-01 Agent 日配额/权益令牌、N-02 服务端模型路由、N-03 每轮宿主指令（三者均需后端计划，互为前置）。
- N-04 类型化功能呈现 + 上下文回执（与 A-04 深链同源）。

**P3 — 低成本可顺手对齐**

- B-02 周号跳转、C-07 ICS 逐条预览、G-02 讲座入口接线、G-06 教师账号博客排除、H-04 分类型开关、J-07 搜索根页。

**不迁移（平台专属或 Android 更强）**

- A-05 Live Activity（Android 通知等效）、B-01 冲突呈现、B-03/B-08/B-09、C-04、D-12、E-07/E-08、I-02/I-09、J-03。
