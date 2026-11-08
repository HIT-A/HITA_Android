# Agent 03 — UI 架构、页面实现与交互专项审查（阶段一）

- 基线：`5992ba381a9d6e87deb7526885f3d957b8a6d09b`
- 分支：`refactor/agent-03-ui`
- 独立 worktree：`.worktrees/agent-03-ui`
- 独立模拟机：AVD `hita_agent03`，`emulator-5560`
- 审查日期：2026-11-08
- 范围：`app/src/main/java/**` 下 UI 层（Activity/Fragment/Composable/UI 状态持有者）、对应 UI 测试
- 排除：三个登录 Activity（Agent 01）、`data/**` 与后台任务（Agent 02/04）、Agent 05 公共测试框架、Agent 06 CI 配置

## 1. 页面清单（UI 层实际规模）

`app/src/main/java` 共 587 个 Kotlin 文件；`ui/` 下 242 个，约 21,465 行；92 个文件含 `@Composable`；44 处 `by viewModels()`；27 个 Activity、10 个 Fragment 基类派生。

| 模块 | 关键文件 | 行数 | 现状职责 |
|---|---|---|---|
| 主界面壳 | `ui/main/MainActivity.kt` | 401 | 主题/系统栏、Tab 宿主装配、壁纸、通知、更新、静默续期、登录弹窗、返回键 |
| | `ui/main/MainScreen.kt` | 429 | 抽屉动画、壁纸层、TopBar/TabBar 编排 |
| | `ui/main/MainViewModel.kt` | 348 | UiState、effect 队列、启动协调 |
| | `ui/main/MainTabHost.kt` | 144 | Fragment 容器生命周期（已从 Compose 重组中隔离） |
| | `ui/main/DockTabBar.kt` | 695 | Dock 手势/动画 |
| 今日/时间线 | `ui/main/timeline/FragmentTimeLine.kt` | 1139 | 时间线列表 + 头部状态机 + 交错入场动画 + 10s 时钟 |
| | `ui/main/timeline/FragmentTimelineViewModel.kt` | 55 | 三条 switchMap 查询、显示目标联动 |
| 课表 | `ui/main/timetable/TimetableFragment.kt` | 221 | 宿主、菜单锚点、批次作用域弹窗、标题回传 |
| | `TimetableScreen.kt` | 323 | Route（13 处 observeAsState）+ Screen 纯渲染 |
| | `TimetableEventCards.kt` | 820 | 课程卡绘制 |
| | `TimetableWeekView.kt` | 389 | 周视图网格 |
| | `TimetableViewModel.kt` | 216 | 窗口数据、显示目标、批次协调 |
| 课程详情 | `ui/subject/SubjectActivity.kt` | 142 | Compose 宿主 + 链接/贡献/加课入口 |
| | `ui/subject/SubjectScreen.kt` | 1115 | 详情/资源/候选/批编辑全在一个文件 |
| | `ui/subject/SubjectViewModel.kt` | ~230 | LiveData 观察者拼装 UiState |
| 深圳课程浏览 | `ui/eas/catalog/ShenzhenCourseCatalogActivity.kt` | 2185 | 权限、14 条 LiveData 绑定、状态机、29 个 Composable |
| | `ShenzhenCourseCatalogViewModel.kt` | 874 | 查询、草稿、选课任务、冲突计算 |
| 成绩 | `ui/eas/score/ScoreInquiryActivity.kt` | 769 | 筛选、摘要、提醒开关 |
| | `ui/eas/score/ScoreInquiryViewModel.kt` | ~? | SavedStateHandle 已用于学期/类型 |
| 考试 | `ui/eas/exam/ExamActivity.kt` | 703 | 列表、备忘录、导入 |
| 学分统计 | `ui/credit/CreditStatsActivity.kt` | 1137 | 图表 + 列表 |
| 深圳成绩分析 | `ui/eas/grade/ShenzhenGradeAnalysisActivity.kt` | 872 | 分析 + 明细 |
| 首页导航 | `ui/main/navigation/NavigationFragment.kt` | 787 | 功能网格 + 登录态门控 |
| 资讯 | `ui/main/blog/BlogFragment.kt` | 650 | 列表 + 日期格式化 |
| 助手 | `ui/main/agent/AgentChatFragment.kt` | 961 | 会话渲染 + 附件 |
| 课表管理 | `ui/timetable/manager/TimetableManagerActivity.kt` | 964 | 导入/分组/学期 |
| 公共 | `ui/base/*`、`ui/design/*` | — | Hilt 基类、Compose 主题、7 套视觉风格 |

## 2. 问题 ID 清单

严重度：**S1** 用户可见错误/崩溃风险；**S2** 明确性能或体验缺陷；**S3** 结构/一致性债务。

### 2.1 状态所有权与数据流（UI 层与 ViewModel/Repository 边界）

| ID | 文件:行 | 症状 | 影响 | 严重度 |
|---|---|---|---|---|
| UI-001 | `ShenzhenCourseCatalogActivity.kt:123-155` | Activity 自己持有 `uiState`、`terms`、`attachmentDialog`、`historicalFailureDialog`、`isShowingCoursePlanPreview`、`notificationPermissionGranted` 六个 `mutableStateOf`，并由 `bindLiveData()` 在 `initViews` 中用 14 个 `observe(this)` 手工拼装 | 状态所有权在 Activity，ViewModel 只提供碎片 LiveData；旋转/进程重建后全部丢失，无法单测 | S2 |
| UI-002 | `ShenzhenCourseCatalogActivity.kt:296-512` | 216 行 `bindLiveData()` 把 `DataState` 的 SUCCESS/NOT_LOGGED_IN/FETCH_FAILED/NOTHING/else 五路分支 × 6 个数据源写成 30+ 个 `when` 分支 | 状态机无法复用、无法测试；新增数据源需继续扩张该函数 | S2 |
| UI-003 | `SubjectActivity.kt:63-64` + `SubjectViewModel.kt` | ViewModel 内部通过 `Observer` 手工合成 `SubjectScreenState`，Activity 再 `collectAsState()` | 中间层 LiveData 与 StateFlow 双轨；`screen` 的字段更新散落在两个匿名 Observer 中 | S3 |
| UI-004 | `TimetableScreen.kt:74-105` | `TimetableRoute` 传 `viewModel` 进 Composable，内部 13 处 `observeAsState`；`TimetableScreen` 才是纯渲染 | Route/Screen 已分离（好），但 Route 仍耦合 VM，无法对 Route 做预览/测试 | S3 |
| UI-005 | `ExamActivity.kt:315-336` | `ExamScreen(viewModel: ExamViewModel, …)` 在 Composable 内 3 处 `observeAsState` | 与 UI-004 同类；`tests` 无法用假状态驱动 | S3 |
| UI-006 | `FragmentTimeLine.kt:166-182` | `TimelineScreen(viewModel: FragmentTimelineViewModel, …)`，Composable 内 3 处 `observeAsState` | 同上 | S3 |
| UI-007 | `MainActivity.kt:174-183` | Activity 直接 `observe(this)` 5 个 repository/preference LiveData 并向 ViewModel 回写 | Activity 承担了本应在 VM 的投影/过滤职责（分层未见违规，但职责重复） | S3 |
| UI-008 | `ShenzhenCourseCatalogViewModel.kt:776-780` | ~~composition 中逐卡拉取 `coursePlanConflict()`~~ → 已改为页面级 `remember` 预计算 map，卡片查表 | **已修复** | S2 |
| UI-009 | `MainViewModel.kt:73-96` | `MainUiState` 单一大对象，任意字段变化都会整棵 `MainScreen` 重组 | 抽屉/壁纸/角标均触发全树重组 | S2 |
| UI-010 | `FragmentTimeLine.kt:203-215` | ~~10s 无限 ticker 无条件重组~~ → 已收敛为 `repeatOnLifecycle(RESUMED)`；行状态也已下沉为纯函数 | 已缓解（`headerState` 仍为唯一 ticker 消费者）；S2 → **已修复** |

### 2.2 Compose 重组与渲染性能（C 系列，已逐条核对源码）

| ID | 文件:行 | 模式 | 代价 |
|---|---|---|---|
| UI-020 | `TimetableScreen.kt:100` | ~~每次重组新建 `IntArray` 作 `remember` key~~ → 已改为 `remember(paletteColors)` 内用索引构造 | **已修复**（首版把 `@Composable hitaCoursePalette()` 结果当 key 并配方法引用，导致 `TimetableHostInstrumentedTest` 进程崩溃；二分定位后修正） |
| UI-021 | `ShenzhenCourseCatalogActivity.kt:1059` | 在 `items{}` 内联调用 `onCoursePlanConflict(item)` | 每可见卡每帧一次冲突计算（n² 会议交集） |
| UI-022 | `MainScreen.kt:128` | ~~动画值在组合期读取~~ → 收敛为 `drawerFollow`（`graphicsLayer` 内读 `rememberUpdatedState`）+ 遮罩 `derivedStateOf`/`drawBehind` | **已修复** |
| UI-023 | `MainScreen.kt:245` | 主内容与 TabBar 已复用同一 `drawerFollow`；`tabContent` 的 modifier 仍在调用处创建（低风险遗留） | 部分修复 |
| UI-024 | `FragmentTimeLine.kt:272-277` | ~~items 内 `indexOfFirst/indexOfLast`~~ → 已由 `TimelineListProjection.timelineBounds` 一次求值 | **已修复（S1）** |
| UI-025 | `FragmentTimeLine.kt:310-311` | ~~组合期读墙钟~~ → 改为传入 `ticker` | **已修复（S1）** |
| UI-026 | `FragmentTimeLine.kt:1037` | ~~progress lambda 内取时钟~~ → 改为传入已算好的 `progress` | **已修复（S1）** |
| UI-027 | `AgentChatFragment.kt:517` | ~~filter 未 remember~~ → `remember(messages)` | **已修复** |
| UI-028 | `AgentChatFragment.kt:537` | ~~key 含 index~~ → `timestampMs + role + text.hashCode()` | **已修复** |
| UI-029 | `CoursePlanPreviewScreen.kt:112,124,146,380,393` | `DataState<MutableList<…>>`、`DataState<Calendar>` 作参数；`distinctBy` 在组合期 | 参数不稳定 → 预览页永不跳过；每帧去重 |
| UI-030 | `ActivityLecture.kt:107,176,201` | `mutableStateListOf<Map<String,String>>` 直接作 item 与参数；`itemsIndexed` 无 key | 卡片永不跳过；追加加载重建全部可见项 |
| UI-031 | `HomeworkScreen.kt:81-83,200,205,232,328` | ~~3 条 filter/count + 逐次 formatter~~ → 合并为 `partitionHomeworkDay` 一次分区，3 个 formatter 提升到文件作用域；行内 lambda 仍每行新建（遗留） | 大部分修复 |
| UI-032 | `EmptyClassroomActivity.kt:347,494` | ~~每卡片每帧现算~~ → 按教室一次性求值成 map | **已修复** |
| UI-033 | `ComparisonGrid.kt:176` | ~~每天组合期重排~~ → `remember(result.days, showFree, showConflicts)` | **已修复** |
| UI-034 | `ScoreInquiryActivity.kt:400` | ~~单 item 内全量渲染~~ → `itemsIndexed` 虚拟化 + `ScoreRowSurface` 保持原卡片视觉 | **已修复** |
| UI-035 | `ExamActivity.kt:336` | ~~无 key 的 `remember`~~ → 由 Activity 传入 `showTypeFilter` | **已修复（S1）** |
| UI-036 | `SubjectScreen.kt:1097-1099` | `Pattern.compile` + `DecimalFormat` 每次重组 | 详情头卡每帧正则编译 |
| UI-037 | `ActivityAbout.kt:471` | `HtmlCompat.fromHtml` 在 `AndroidView.update` 内 | 关于页每次重组重解析 HTML |
| UI-038 | `BlogFragment.kt:647` | 每行 `SimpleDateFormat` 分配 | 资讯/公告每行每帧分配 |
| UI-039 | `MainScreen.kt:161, TimetableScreen.kt:128` | `collectAsState()` 非生命周期感知（6 处） | 停止态仍收集并尝试重组 |
| UI-040 | `TimetableScreen.kt:103` | 见 UI-020 的 `remember` key 抖动 | 同上 |
| UI-041 | `CoursePlanPreviewScreen.kt:120` | `BackHandler` 已用于预览返回（正向样板） | — |

### 2.3 主线程阻塞 / 线程与生命周期

| ID | 文件:行 | 症状 | 影响 |
|---|---|---|---|
| UI-050 | `ui/widgets/today/normal/ListRemoteViewsFactory.kt:92`、`slim/ListRemoteViewsSlimFactory.kt:83` | `runBlocking(Dispatchers.IO)`（AppWidget 提供者，属 UI 层 widget） | 阻塞提供者主线程（已有 IO 派发，风险低但不可取消） |
| UI-051 | `FragmentTimeLine.kt:207` | ~~无条件 ticker~~ → 生命周期门控 | **已修复**（同 UI-010） |
| UI-052 | `TimetableManagerActivity.kt:406,424` | `contentResolver.openInputStream(uri)` 在 UI 类内直接调用 | 需确认调用点所在线程（导入路径） |
| UI-053 | `MainScreen.kt:158-170` | `WindowInsets.ime/navigationBars.getBottom(density)` 每帧读取 | 每帧 layout 期读取，随抽屉/壁纸动画放大成本 |
| UI-054 | `WallpaperLoader.kt:44-66` | Glide + `WallpaperColorAnalyzer.sampleRegion` 回调在主线程对整图两次采样 | 大图壁纸切换出现掉帧 |

### 2.4 生命周期、导航与状态恢复

| ID | 文件:行 | 症状 | 影响 | 严重度 |
|---|---|---|---|---|
| UI-060 | `viewLifecycleOwner` 用在 0 处；`.observe(this)` 51 处全部在 14 个 Activity（Fragment 内 0 处）；`observeAsState` 189 处 | Activity 里的 `observe(this)` 作用域正确。Fragment 侧不走 LiveData 观察者，而是把 LiveData 直接 `observeAsState` 进 Composable；据 `FragmentTimeLine.kt:198`、`TimetableScreen.kt:74`、`ExamActivity.kt:333` 的形态，这些收集不随 `viewLifecycle` 停止 | Tab 隐藏时容器 `STARTED`、视图仍存在，收集继续触发重组尝试；视图销毁后到 Fragment 销毁之间的窗口无隔离 | S2 |
| UI-061 | `MainActivity.kt:246-252` | 仅保存 `selected_tab`/`selected_tab_name` 与 tab host id | 抽屉/滚动/壁纸选择在进程重建后丢失 | S3 |
| UI-062 | `ShenzhenCourseCatalogActivity.kt:159-161` | `onCreate` 只调 `super`，状态全在字段 | 旋转后回到 Loading；进程重建丢失筛选/分页/草稿视图态 | S2 |
| UI-063 | `ShenzhenCourseCatalogActivity.kt`（原 668-679） | ~~`CatalogUiState` 为 Activity 私有 sealed interface~~ → 已提升为顶层 `ui/eas/catalog/CatalogUiState.kt`，可复用、可单测 | **已修复** | S3 |
| UI-064 | 仅 5 个 ViewModel 使用 `SavedStateHandle`（catalog/score/comparison/friend） | 课表、时间线、考试、成绩分析、学分统计等无恢复 | 见 UI-062 类问题普及 | S2 |
| UI-065 | `FragmentTimeLine.kt:247` | `LazyColumn` 未 hoist 滚动位置 | Tab 切换（隐藏→显示）与旋转后回到顶部 | S3 |
| UI-066 | `TimetableFragment.kt:97-99` | `viewModel.startRefresh()` 在 `onStart` 无条件执行 | 每次返回前台全量查库（含学术日历远端刷新） | S2 |
| UI-067 | `AndroidManifest.xml:119-121` | `MainActivity` `launchMode=singleTop`、无 `configChanges` | 旋转走完整重建（正确但成本高，且与 UI-061/064 叠加丢失状态） | S3 |
| UI-068 | `MainActivity.kt:224-236` | 返回键回调：抽屉开→关；否则直接 `ACTION_MAIN` 回桌面，永不 `finish()` | 非首个 Tab 时返回直接退到桌面，不符合常见返回栈预期 | S2 |
| UI-069 | `ui/event/FragmentTimeInfoSheet.kt:119` | `commitAllowingStateLoss()` | 保存态后提交，可能丢失 | S3 |

### 2.5 无障碍、字体缩放、暗色与 Insets

| ID | 文件:行 | 症状 | 影响 |
|---|---|---|---|
| UI-070 | `ExamActivity.kt:337`、`ScoreInquiryActivity.kt`、`SubjectScreen.kt:154` | ~~仅 statusBars~~ → 三页补 `navigationBarsPadding()`；`imePadding` 仍缺（这些页无文本输入） | **已修复** |
| UI-071 | `FragmentTimeLine.kt` 原 10 处 `contentDescription = null` | 展开/收起箭头已补 `timeline_expand_hint`/`timeline_collapse_hint`；其余纯装饰性图标保持 null（符合 a11y 规范） | 部分修复 |
| UI-072 | `FragmentTimeLine.kt:1005-1020` | 地点文本按字符数 14/12/10/9sp 阶梯缩放 | 用户调大系统字号时仍被强制缩到 9sp |
| UI-073 | `TimetableEventCards.kt:470,482` | `((if (compressed) 8f else 12f) * style.fontScale).sp` | 忽略系统字体缩放，仅按内部比例 |
| UI-074 | `ShenzhenCourseCatalogActivity.kt:1721` 等 | 多处 `Icon(size = 18.dp)` 配 `clickable`/`IconButton` 小于 48dp 触控区 | 触控精度差 |
| UI-075 | `FragmentTimeLine.kt:520-560` | banner 上 `Color.White` 硬编码，靠 `scrimForWhiteText` 压暗兜底 | 依赖运行期对比度计算，逐帧重算 |
| UI-076 | 27 个 Activity 主题在 `HiltBaseActivity.setWindowParams` 用 `systemUiVisibility` 旧 API | 与 `WindowCompat.setDecorFitsSystemWindows` 混用 | 深色主题下状态栏图标可能不一致 |

### 2.6 空/加载/错误状态与文案

| ID | 文件:行 | 症状 | 影响 |
|---|---|---|---|
| UI-080 | `ShenzhenCourseCatalogActivity.kt:340,425,489` 等 | `FETCH_FAILED` 直接把 `state.message` 当用户文案，且大量 `?: "课程数据加载失败"` 硬编码中文 | 中文硬编码未入 `strings.xml`（全 UI 层 585 处中文字面量） |
| UI-081 | 中文字面量 top：catalog 88、grade 68、recommendation 61、credit 57、agent 28 | 违反项目 `strings.xml` 规则 | i18n/统一文案不可维护 |
| UI-082 | `ExamActivity.kt:436` | `examState?.message?.takeIf{…}` 红字提示，无重试按钮 | 失败后只能靠顶栏刷新图标 |
| UI-083 | `SubjectScreen.kt`、`CreditStatsActivity.kt` | 加载/空/错误分支存在但无统一组件 | 各页重复实现 |
| UI-084 | `BlogFragment.kt`、`NavigationFragment.kt` | 错误仅 Toast | 无就地反馈与重试 |

### 2.7 交互与导航一致性

| ID | 文件:行 | 症状 | 影响 |
|---|---|---|---|
| UI-090 | `MainActivity.kt:224` | 见 UI-068 | 返回栈行为 |
| UI-091 | `ShenzhenCourseCatalogActivity.kt:545-560` | 通知权限 `awaitingNotificationPermission` + `LaunchedEffect` 串起“日期→时间→确认”三段弹窗 | 权限被拒后流程无回退提示 |
| UI-092 | `TimetableFragment.kt:158-195` | 长按菜单在 `android.R.id.content` 上插入 1×1 隐形 View 作锚点 | 与 Compose 覆盖层叠时定位可能偏移；父容器被外部清理时静默失败 |
| UI-093 | `CoursePlanPreviewScreen.kt:120` | `BackHandler` 覆盖系统返回 | 与宿主 Activity 返回键共存行为需实测 |

## 3. 架构方案对比（至少两套）

两项共同前提：① 不改导航图与视觉；② 不改 Repository/DAO 契约。

### 方案 A — 就地状态收敛（保守，推荐先做）

把每个页面已存在的 `mutableStateOf` 字段与 `bindLiveData()` 收进该页 ViewModel 的单一 `StateFlow<PageState>`，Composable 只接收不可变 state + 回调。

- 改动面：`ShenzhenCourseCatalogActivity`(2185)、`ShenzhenCourseCatalogViewModel`(874)、`SubjectActivity`+`SubjectScreen`、`ExamActivity`、`ScoreInquiryActivity`。
- 优点：与现有 MVVM 一致；无新依赖（`lifecycle-runtime-compose` 需新增，但这是唯一新增）；可先按页面增量落地；测试可只测 VM。
- 缺点：先要拆 `bindLiveData()` 的 30+ 分支；`MainUiState` 仍偏大。
- 关键约束：`CatalogUiState` 必须提升为 internal 可见并带 `equals`，否则状态对比无法测试。

### 方案 B — 抽离 UI 状态层 + 事件通道（激进）

为每个页面引入 `PageStateHolder`（无 LiveData，纯 Kotlin + `StateFlow`），Activity/Fragment 退化为“宿主 + 权限 + 导航”，并用 `UiEffect` 单向事件通道替代直接 Toast/Dialog 调用。

- 改动面：上述页面 + 新增 `ui/*/state/` 目录；`MainActivity` 的 6 个 presenter 也纳入。
- 优点：权限/导航/副作用集中，UI 可完整单测（含权限拒绝、进程重建）。
- 缺点：一次性改动大，回归面广，需要模拟机逐页复核；与并行 agent 的 `data/**` 改动耦合风险高。

### 选择

**先做方案 A 的模块 1–3**（catalog 状态收敛、时间线 S1 修复、课表配色/重组修复），因为：

1. 三处的缺陷已有确定性证据（UI-020/024/025/026/035 可通过单测复现）；
2. 不触碰 Repository、导航与视觉；
3. 收益/风险比最高：S1 全部集中在时间线与考试过滤，S2 主要是 catalog 与课表重组。

方案 B 的 `UiEffect` 通道仅在 `MainActivity`（已有 `MainEffect`）与 catalog 的 Toast 链上做局部引入，不整体重写。

## 4. 责任划分（改造后）

| 层 | 负责 | 不负责 |
|---|---|---|
| Activity/Fragment | 宿主装配、权限请求、系统栏、导航、生命周期转发 | 数据投影、状态机分支、文案拼接 |
| ViewModel | 唯一 `StateFlow<PageState>`、`UiEffect` 通道、SavedStateHandle 恢复 | 持有 View/Activity、Toast、Dialog |
| Composable Screen | 纯渲染 `state` + 回调；`remember`/`derivedStateOf` 派生 | 直接调用 Repository/VM 业务方法 |
| Route Composable | 桥接 VM→Screen（`collectAsStateWithLifecycle`） | 业务逻辑 |

## 5. 修改文件范围

**允许**：`app/src/main/java/cn/limpu/hita/ui/**`（含 `ui/main/**`、`ui/eas/**`、`ui/subject/**`、`ui/timetable/**`、`ui/widgets/**` 的 composable 部分）；`app/src/test/java/cn/limpu/hita/ui/**`；`app/src/androidTest/java/cn/limpu/hita/ui/**`；`docs/agent-03-ui-*.md`。

**禁止**：三个登录 Activity（`ui/eas/login/**`、`ui/welcome/**`）、`data/**`、`data/work/**`、`agent/**`、`hitauser/**`、`.github/**`、`scripts/**`、`gradle/libs.versions.toml` 与各 `build.gradle`、Room schema、DB 版本。

**唯一例外待批**：`lifecycle-runtime-compose` 依赖（`collectAsStateWithLifecycle`）。不批则跳过 UI-039，改用 `repeatOnLifecycle` 包装 6 处 `collectAsState()`。

## 6. 测试方案

| 层 | 内容 | 工具 |
|---|---|---|
| 单测（新增） | 时间线 `firstNonTagIndex/lastNonTagIndex` 与 `now` 谓词；课表配色 `remember` key 稳定性；`shouldShowExamTypeFilter` 的 key 语义；catalog 状态机 `DataState×6 源→PageState` 映射（含 `NOT_LOGGED_IN`/`FETCH_FAILED`/空列表） | JUnit4 + 现有 `testFixtures` |
| 确定性数据 | 空列表、单元素、仅 TAG 项、100+ 事件、异常时间戳（负值/未来）、超长地点名 | 同上 |
| 仪器测试 | `ActivityScenario.recreate()` 后 catalog/考试/成绩页状态保持；`MainActivity` Tab 切换与返回键；Compose `Semantics` 选择器 + `waitUntil` 条件等待 | `androidx.test.ext:junit`、`androidx.compose.ui:ui-test-junit4`（需确认已有） |
| 模拟机 | 冷启动 → 今日 → 课表（横竖屏）→ 课程详情 → 成绩 → 考试 → 返回栈 → 进程重建 → 字号 1.3× → 暗色 | `emulator-5560`、`adb shell` |

## 7. 模拟机操作流程（隔离规范）

```
AVD hita_agent03 / emulator-5560（本 agent 专属；其他 agent 使用 5570/5574，不共用）
adb -s emulator-5560 ...（所有命令强制 -s，禁止裸 adb）
包名 cn.limpu.hita.diag（debug applicationIdSuffix=.diag），与正式版共存
```

1. `adb -s emulator-5560 install -r app/build/outputs/apk/debug/app-debug.apk`
2. `am start -W -n cn.limpu.hita.diag/cn.limpu.hita.ui.main.MainActivity`，记录冷启动耗时
3. `uiautomator dump` + `screencap` 逐页采集：今日→待办→时间表→更多
4. 课表页：竖屏/横屏各一次（`settings put system user_rotation 0|1`）
5. 进入课程详情、成绩、考试页，返回栈逐层回退
6. 进程重建：`am kill cn.limpu.hita.diag` → 重启 → 校验状态恢复
7. 字体 1.3×（`settings put system font_scale 1.3`）与暗色（`cmd uimode night yes`）复检布局
8. `logcat -d` 抓 `FATAL`/`ANR`/`Compose` 异常

## 8. 预期性能指标

| 指标 | 基线（`emulator-5560`，SwiftShader） | 目标 |
|---|---|---|
| 冷启动 `TotalTime` | 14,928 ms | 不作为优化目标（软件渲染），仅记录 |
| 时间线空闲重组 | 每 10s 全列表 | 仅头部 1 次（ticker 收敛到 Header） |
| 课表配色计算 | 每次重组全量 | 仅 `events`/调色板变化时 |
| catalog 滚动冲突计算 | 每卡每帧 | 预计算 map，0 次/帧 |
| 抽屉动画重组 | 整树每帧 | 仅 `graphicsLayer`，0 次组合 |
| 成绩列表 | 全量组合 | 虚拟化后按视口 |

## 9. 风险与依赖

1. `collectAsStateWithLifecycle` 需要新依赖（当前 `app/build.gradle` 只有 `lifecycle-runtime-ktx`/`lifecycle-process`）。
2. Repository 模型类无 `@Immutable`、`DataState` 无 `equals`（属 `data/**`/`component/**`，**不在本 agent 权限内**）→ Compose 跳过能力受限，只能在这些层之外的边界做稳定化。
3. 并行 agent 正在改 `data/**` 等；本分支只读其接口，若其签名变化需 rebase 而非改其代码。
4. JDK：本机默认 JDK 25 无法配置 AGP 8.10.1 测试任务，已改用 `~/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2`（21.0.10）。

## 10. 实施记录（阶段二，方案 A 模块 1–3）

| 问题 | 修改 | 验证 |
|---|---|---|
| UI-024/025/026 | 新增 `ui/main/timeline/TimelineListProjection.kt`：首末索引、`isPassed`、`isUpcomingExam`、`isNow`、`progress` 全部改为以传入 `now` 求值的纯函数；`FragmentTimeLine` 不再在组合期读墙钟、不再逐行 `indexOfFirst/indexOfLast` | `TimelineListProjectionTest`（7 项） |
| UI-010/051 | 10s ticker 改为 `repeatOnLifecycle(RESUMED)`，Tab 隐藏/退前台即停表 | 编译 + 模拟机验证 |
| UI-020/040 | `TimetableScreen` 的色板转换改 `remember(palette)`，`colorAssignments` 不再因每次重组失效 | 编译 |
| UI-008/021 | catalog 冲突描述改为 `remember(page, selectedCourses, coursePlanDraft, graduateMode)` 预计算成 `Map<conflictKey, String?>`，卡片只查表 | 编译 |
| UI-001/002/063 | 新增 `ui/eas/catalog/CatalogUiState.kt`：`CatalogUiState` 提升为顶层类型，`CatalogUiStateProjection` 承载学期/课程的 5 分支折叠；`bindLiveData()` 中两处 30+ 行 `when` 收敛为一次投影调用 | `CatalogUiStateProjectionTest`（10 项） |
| UI-035 | `ExamScreen` 的 `remember { viewModel.shouldShowExamTypeFilter() }`（无 key）改为由 Activity 传入 `showTypeFilter` 参数 | 编译 |
| UI-028 | 助手消息 key 去掉 `index`，改用 `timestampMs + role + text.hashCode()` | 编译 |
| UI-027 | `visibleMessages` 改为 `remember(messages)` | 编译 |
| — | `ExamActivity` 的考场列表补稳定 key（`memoId` 或 `termId|courseName|examDate|examTime`） | 编译 |
| UI-031 | 待办页三处派生改一次分区（`HomeworkDayPartitions` 纯函数），三处 `DateTimeFormatter.ofPattern` 提升到文件作用域 | `HomeworkDayPartitionsTest`（4 项） |
| UI-034 | 成绩列表由"单个 `item` 内 `forEach`"改为 LazyColumn `itemsIndexed` 逐行虚拟化；新增 `ScoreRowSurface` 保持原卡片视觉与首末圆角 | 编译 + 模拟机 |
| UI-022 | `MainScreen` 的抽屉位移/缩放收敛为一个 `drawerFollow` 修饰符，值在 `graphicsLayer` 内读 `rememberUpdatedState`；遮罩用 `derivedStateOf` 判可见 + `drawBehind` 读 alpha | 编译 + 模拟机 |
| UI-023 | 主内容 Column 与 TabBar 复用同一 `drawerFollow`，不再每帧重建 modifier | 编译 |
| UI-032 | 空教室占用状态按教室一次性求值成 `Map<roomKey, String>`（同帧内一致，跨时间不缓存） | 编译 |
| UI-033 | 对比网格的 `mergedBlocks` 按天 `remember`，不再每次重组重排 7 天 | 既有 `ComparisonGridLogicTest` |

## 11. 下一步（阶段二剩余）

1. 逐页复核模拟机：今日/课表/课程详情/成绩/考试/返回栈/旋转/进程重建。
2. 处理 Fragment 侧 `observeAsState` 生命周期问题（需先决定是否引入 `lifecycle-runtime-compose`；否则用 `repeatOnLifecycle` + `State` 包装）。
3. 其余 S2/S3（UI-022/023/031/032/033/034、UI-062/064/068）按收益排序继续。
4. 汇总性能测量与交付报告。
