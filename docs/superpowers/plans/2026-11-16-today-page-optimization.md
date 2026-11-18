# 今日页优化代码实施计划

日期：2026-11-16

分支：`feat/ios-parity-migration`；代码基线：`321cd9ed`。

依据：[今日页信息层级与明日准备设计](../specs/2026-11-16-today-page-optimization-design.md)。本文件是实施方案，不是业务代码或已完成的测试记录。下列新增类型、方法名均为拟实现接口。

实施进度（2026-11-17）：Task 1–2 已实现并通过 38 项 JVM、26 项设备回归及 Debug/测试 APK 构建，见[日期窗口与投影基础记录](../../audit/2026-11-17-today-foundation.md)；Task 3–4 已实现并通过包含前批回归的 90 项 JVM、本批 67 项设备回归及构建，见[作业与考试摘要记录](../../audit/2026-11-17-today-summaries.md)。Task 5–7 已接入新今日页，最终完整 app JVM 1433 项中 1432 通过、1 跳过，33 项设备回归全部通过；已安装诊断版并确认实际首屏，见[界面接入验证记录](../../audit/2026-11-17-today-ui.md)。全部主题、真实服务及首期设备验收清单中的剩余组合场景尚未全部验收。

补充进度（2026-11-18）：按确认后的[视觉重做方案](../specs/2026-11-18-today-visual-redesign.md)完成布局重做与第二期 A 的考试/作业摘要卡；48 项相关 JVM、36 项设备回归通过，并已安装诊断版检查当前手机主题下的完整页面，见[实施记录](../../audit/2026-11-18-today-redesign.md)。

## 1. 实施边界与代码审查结论

第一期完成顶部状态、今日重点文字摘要、接下来/全部安排、明日准备和显式读取状态；第二期 A 增加考试、作业卡片。第二期 B 的资讯、资源和 Agent 入口在本计划末单列，不阻塞第一期。

保持 Room v21、现有权限、教务只读规则与主题系统。今日页不产生课表写入、不联网轮询，不创建新的定时任务。保留 Hilt、Repository、ViewModel、Compose 的既有依赖方向。

审查现有代码发现的具体问题及处理决定：

| 现状 | 实施决定 |
|---|---|
| `DisplayScopeResolver.resolved` 为可空 LiveData，null 同时可能表示尚未解析和无课表 | 在现有解析链上增加显式读取状态，不复制课表选择算法 |
| 今日查询只清零小时和分钟，未清零秒与毫秒 | 使用 `LocalDate.atStartOfDay(zone)` 构建半开窗口，日历加一天，不加固定 24 小时 |
| `EventItemDao` 已有隐藏过滤及区间相交查询；Repository 已有显示去重 | 复用其语义，新增可捕获查询错误的 Flow 读取接口，不另写一套显示去重 |
| `weekEventsLiveData` 是后续四项，不是完整本周 | 移除今日页对其依赖，以完整今日/明日窗口替代 |
| `TimelineListProjection` 的结束判定是 `< now`，mini 条使用 `< end` | 共用半开区间判断，统一结束瞬间与稳定排序 |
| `GraderState.tasks` 发布时已过滤逾期、无期限、已完成项 | 增加不含凭据的摘要读取模型，不能从 tasks 反推完整统计 |
| `ExamEventMapper` 拒绝缺少日期/时间的考试；`ExamMemoStore` 仅存个人备忘 | 区分已导入日程和可用的原始考试结果；没有原始结果时不声称掌握全部教务考试 |
| `GraderRepository` 的 generation 防陈旧写入，不等于刷新去重 | 今日页仅加载本地快照；共享一次加载协调，不让今日页和作业页并发 reload/refresh |
| `MainTabHost` 将隐藏页保持在 STARTED | 今日时钟和主动加载由 RESUMED 驱动，不能仅依赖 Compose 是否还在树中 |

## 2. 数据流与拟新增模型

```text
DisplayScopeResolver.readState + 明确的日期窗口
    → TimetableRepository.observeTimelineWindow(window, ids)
    → 已去重、可见的两天安排与读取状态

HomeworkRepository.state + GraderRepository 摘要状态
    → TodayHomeworkProjection（来源、期限和账号明确）

Room EXAM + 已成功接收且范围可核对的考试读取结果
    → TodayExamProjection（覆盖范围与时间完整性明确）

上述输入 + 单个 now + 当前目标作息
    → FragmentTimelineViewModel.uiState
    → TodayScreen / Header / Agenda / TomorrowPreparation
```

拟新增类型按职责分文件，不创建通用页面框架：

- `feature/timetable/display/EventTimePolicy.kt`：`phase(start, end, now)`、稳定排序、距边界的剩余分钟；纯 Kotlin，不取系统时间、不依赖 Android 或 UI。
- `ui/main/timeline/TodayUiState.kt`：`TodayUiState`、`TodayAgendaState`、`TodayHeaderState`、`TomorrowPreparationState`；只保存值，不保存 Context、Bitmap、LiveData 或资源渲染结果。
- `ui/main/timeline/TodayProjection.kt`：由已就绪的输入计算完整今日、默认可见集合、冲突组、完成状态及明日第一组。
- `ui/main/timeline/TodayDateWindow.kt`：`TodayDateWindow(date, zoneId, start, tomorrowStart, dayAfterStart)`，由显式 Instant/ZoneId 创建。
- `data/repository/today/TodayReadState.kt`：局部只读状态 `Loading / Ready<T> / Failed<T>(retained, failure)`；没有跨业务状态框架改造。

核心接口草案：

```kotlin
// 示意契约，不作为已存在的可编译代码。
fun projectToday(
    window: TodayDateWindow,
    events: List<EventItem>,
    now: Long,
): TodayAgendaState

fun observeTimelineWindow(
    from: Long,
    to: Long,
    timetableIds: List<String>,
): Flow<TodayReadState<List<EventItem>>>
```

`TodayAgendaState` 提供 `allToday`、`active`、`nextGroup`、`defaultVisible`、`completedCount` 和 `tomorrow`。UI 只切换集合，不再各自计算时间、排序或查询数据库。

## 3. Task 1：补齐课表解析与两天读取状态

**修改：**

- `app/src/main/java/cn/limpu/hita/data/repository/DisplayScopeResolver.kt`
- `app/src/main/java/cn/limpu/hita/data/source/dao/EventItemDao.kt`
- `app/src/main/java/cn/limpu/hita/data/repository/TimetableRepository.kt`

**新增：** `data/repository/today/TodayReadState.kt`、`ui/main/timeline/TodayDateWindow.kt`。

步骤：

1. 给现有 Resolver 添加 `readState`：初始 Loading；现有 Room 关系查询已发出结果后，发布 `Ready(resolvedScope)`，其中 null 才表示确实无目标。`resolved` 继续保留，现有消费者无需同时迁移。解析和回落必须共用一次计算结果。
2. DAO 新增 `observeEventsOverlappingForTimetables(from, to, ids): Flow<List<EventItem>>`，SQL 继续使用 `from < end && to > start && hidden = 0 && timetableId IN (...)`。
3. Repository 新方法包装 Loading、去重后的 Ready 和 Failed；保持 `CancellationException` 传播。重试通过重订阅同一查询实现，不把 SQL 错误当成空表。
4. 一次读取 `[todayStart, dayAfterStart)`，然后用相交规则分为今日和明日。两天来自同一结果，避免两个查询回调拼出不同修订的状态。
5. 目标变化后立刻清除另一目标内容。相同目标查询失败可以保留最近成功快照，但必须有失败标记；成员或日期窗口变化时不得继续冒用旧快照。
6. 提供查询键 `(scopeType, scopeId, sortedMemberIds, localDate, zoneId)`，`distinctUntilChanged` 后才切换 Room 查询。分钟变化只重新投影，不重新查询。

**验证：** 扩展现有 `DisplayScopeSelectionWiringInstrumentedTest`、`TimelineProjectionInstrumentedTest`；新增 `TodayDateWindowTest`。覆盖冷启动加载/真空表、组成员变更、旧目标迟到回调、隐藏、显示去重、跨午夜以及失败重试。

## 4. Task 2：统一时间状态与今日投影

**新增：** `feature/timetable/display/EventTimePolicy.kt`、`ui/main/timeline/TodayUiState.kt`、`TodayProjection.kt`。

**修改：** `TimelineListProjection.kt`、`ui/main/MiniCourseResolver.kt`。

实现规则：

1. 所有真实日程按 `(from, to, id)` 稳定排序；TAG 提示单独输入，不参与数量、完成状态或明日门槛。
2. `end <= now` 为结束，`start <= now && now < end` 为进行中。非法 `end <= start` 不编造进度，显示可诊断的异常条目，不让它永久阻止明日准备。
3. 默认集合是所有 active 加上最早未来开始时刻的整组；同一事件按 ID 去重，重叠/同刻多项保留全部并标冲突。
4. 距开始/结束的正剩余分钟向上取整，边界到达时重新分类，避免还有 20 秒却显示 0 分钟。进度只在有效时间范围内计算并限制在 0..1。
5. 明日准备在页面日期对应的当地 20:30 后，且今日所有有效真实日程均结束时产生。明日第一组按其与明日窗口相交后的有效开始顺序计算：跨午夜仍进行中的安排优先，不显示负倒计时。
6. 今日与明日各以区间相交决定归属；跨午夜事件可在相邻两天出现，但同一天只出现一次，持续时间仍使用原始起止时间。
7. mini 条继续只处理 CLASS，并共享时间边界和排序。它没有显示考试/个人日程是既有范围差异，不能为了对齐状态强行扩展为所有事件；多个课程冲突时沿用稳定选择并在今日页展示全组。

**测试：** 新增 `TodayProjectionTest`、`EventTimePolicyTest`；扩展 `TimelineListProjectionTest` 和 `MiniCourseResolverTest`。重点测试开始/结束瞬间、冲突集合、20:29/20:30、空日程、跨周/月/年和跨午夜。

## 5. Task 3：提供作业摘要读取模型

**新增：**

- `data/model/grader/GraderSummaryItem.kt`：只含账号标识、作业 ID、标题、课程、可空 deadline、完成/已提交结果。
- `data/repository/grader/GraderReadCoordinator.kt`：当前可见期的本地加载 single-flight。
- `ui/main/timeline/TodayHomeworkProjection.kt`。

**修改：** `GraderStore.kt`、`GraderRepository.kt`、`ui/main/homework/HomeworkViewModel.kt`；依赖均来自现有 data 层，今日页不引用 HomeworkViewModel。

步骤：

1. `GraderSnapshot` 新增纯函数摘要投影：只包含当前账号已发布作业，完成状态取本地 completed 与远端 submitted 的并集；不要提前丢弃逾期或无期限项。原 `visibleTasks(now)` 和作业页列表语义保持不变。
2. `GraderState` 增加摘要字段并在同一次 publish 中更新；状态中不暴露 Cookie、存储 JSON 或历史账号全集。退出或切换账号时先清除摘要，沿用 generation 拒绝迟到结果。
3. Coordinator 仅协调已有 repo 的本地 reload，合并今日/作业页同一可见期请求；以当前账号/连接代次记录已加载状态，in-flight 请求共用同一个结果，失败只在显式重试或下一可见期重试。作业页既有网络刷新在加载完成后按原条件触发。今日页不主动 refresh，不新建过期网络重试策略。
4. 本地手写作业复用 `HomeworkRepository.state/reload()`；无日期项不计入今日截止或逾期，旧有日期无时间按 23:59；格式异常标记未知，不猜日期。
5. Grader dueAt 是绝对时间戳，按其既有 `Asia/Shanghai` 展示日归类；手写作业仍按设备本地日期规则。两来源分别计数并保留标识，不用课程名或标题合并身份。
6. `TodayHomeworkSummary` 包含各来源的 readiness、error、dueToday、overdue、undated 和最早截止条目。失败但仍有已知数据时可展示带“本地记录”标记的数值；未知来源不能拼成“总共 0 项”。

**测试：** 扩展 `GraderStoreTest`、`GraderRepositoryCancellationTest`；新增 `TodayHomeworkProjectionTest`、`GraderReadCoordinatorTest`。验证断连/换号、已提交、逾期、无期限、未发布、重复加载和手写旧数据兼容。

## 6. Task 4：考试覆盖范围与不完整时间

**新增：** `data/repository/ExamReadStateStore.kt`、`ui/main/timeline/TodayExamProjection.kt`。

**修改：** `EASRepository.kt` 的 `createExamQueryRequest/getExamInfo` 接收结果处、`EASTimetableImportRepository.kt` 的可选考试查询接收处；必要的 Hilt 构造注入同步修改 `RepositoryModule.kt`。

步骤与范围：

1. 首先由当前显示窗口的 Room EXAM 产生可信的“已导入考试”摘要。不能把它称为“全部教务考试”；缺少原始来源时，不因 EXAM 为空宣称教务无考试。
2. 新 Store 是有界的进程内读取状态，不落盘、不替代 `ExamMemoStore`。只保留当前教务账号各学期最新已接受的读取结果与 Loading/Failed 状态；账号切换立即清空，成功空结果和从未查询必须可区分。
3. 统一 publish 契约包含账号、会话代次、学期、请求序号和明确的导入目标（若存在）。沿用 `EasQuerySession` 校验，再按请求序号防同账号同学期旧响应覆盖新响应。LiveData 兼容接口使用可取消的映射/中介观察，不新增永久 observeForever。
4. 自动导入有目标 timetableId，仅在目标仍有效且相应本地考试事务成功后绑定目标发布；独立考试页面的结果仅在现有导入身份规则能严格匹配当前显示目标时参与今日计数。无法绑定目标的结果只作为明确标记的教务考试来源，不混入当前课表数量。
5. 日期明确但时间缺失的原始记录显示“时间待确认”，不生成虚假 EventItem 或参与进行中/结束判断。日期也未知时不计入“今天”。
6. 可完整映射的原始记录与已导入 EAS 考试使用同一 `ExamEventMapper.identityKey` 匹配，且限定账号/学期/目标。个人考试始终保留独立 ID；不按同名推断重复。新原始结果显示之前核对目标及账号仍有效。
7. `ExamMemoStore` 继续仅存个人备忘，不将远端结果写进去。备忘无可靠目标绑定时不合入课表范围的计数，可从现有考试页查看。
8. 进程重启后的原始结果未知是允许状态，继续显示已导入考试及来源范围提示。若未来需要离线保留全部原始考试，另立存储方案，不在此任务悄悄扩表。

**测试：** 新增 `ExamReadStateStoreTest`、`TodayExamProjectionTest`；回归 `ExamQueryRequestTest`、`ExamEventMapperTest`、`AutomaticExamCommitInstrumentedTest` 及三校区查询契约。覆盖成功空结果、时间不全、取消、旧响应、换号及跨目标隔离。

## 7. Task 5：ViewModel、时钟与生命周期

**修改：** `FragmentTimelineViewModel.kt`、`FragmentTimeLine.kt`、`MiniCourseResolver.kt` 的调用边界；必要时最小调整 `MainViewModel.refreshMiniCourse` 的过期发布保护。

**新增：** `ui/main/timeline/TodayHeaderResolver.kt`，聚合问候和摘要；可注入 `Clock` 或通过内部构造参数传入 `nowProvider`，生产默认系统时钟。

步骤：

1. ViewModel 输出单个 `StateFlow<TodayUiState>`；用 `flatMapLatest` 切换窗口查询、`combine` 合成各来源。同一次投影只读一个 now；失效的 queryKey/账号结果不能覆盖当前状态。
2. `onVisible(now)` 加载本地来源并立即派生，`onTimeChanged(now)` 更新时钟；只有日期、时区或目标成员变化时换查询。`onRetry(source)` 仅重试明确失败的来源。
3. Fragment 的 `viewLifecycleOwner.repeatOnLifecycle(RESUMED)` 驱动时钟与可见期加载，收集 uiState；隐藏 Tab 和后台停止时钟。复用既有生命周期依赖，不为状态收集引入重复框架。
4. 下一次唤醒取下一分钟、最近事件起止、20:30、午夜四类边界的最小值；唤醒后重新取系统 now。广播增加 `ACTION_TIMEZONE_CHANGED`，时间/日期/时区变化立即重新投影。
5. 作息来自 `ResolvedDisplayScope.timetables`；所有参与表结构一致时才推导饭点，否则使用普通时段问候。当前/临近安排永远独立显示，不被问候分支遮住。
6. 身份读取遵循 `IdentityVisibilityStore`；首期可继续不显示姓名，避免引入无关身份数据订阅。若显示，只在偏好允许时使用现有已登录身份，账户变更及时清除。
7. mini 条使用相同时间政策。其异步读取在返回时检查目标仍匹配并使用最新 now，避免换目标后旧课覆盖新课；保持只读调用。

**测试：** 新增 `TodayHeaderResolverTest`、`TodayViewModelInstrumentedTest`。以可控时钟和伪读取来源验证冷启动、错误保留、恢复、切换目标、日期/时区变化和可见生命周期。保留独立来源失败不会覆盖整个页面的断言。

## 8. Task 6：替换今日 Compose 信息层级

**新增：** `TodayScreen.kt`、`TodayHeader.kt`、`TodayAgenda.kt`、`TomorrowPreparationCard.kt`、`TodayReadStatus.kt`，位于 `ui/main/timeline/`。

**修改：** `FragmentTimeLine.kt`、`strings.xml`；必要时将旧 `TimelineEventRow` 提取到 `TimelineEventCard.kt`，复用现有详情跳转。

步骤：

1. Fragment 只保留宿主、生命周期、广播和动作分发；移除旧 `buildHeaderState`、大图固定占位与顶部展开后续四项的展示。删除确认无消费者的 weekEvents/upcomingExam 旧页面订阅，不改其他使用者的 Repository 方法。
2. 顶部紧凑展示问候、当前状态/剩余分钟、今日考试与作业摘要；多项冲突使用计数与完整列表，不能渲染一个任意标题冒充唯一安排。
3. “接下来/今天的安排”在 defaultVisible 与 allToday 之间切换；使用 item key `event.id`，提示卡单独 key，时间线 rail 根据实际展示集合重新计算边界。已结束项保留时间与点击能力。
4. `rememberSaveable(scopeKey, localDate)` 保存展开状态，`rememberLazyListState` 保存位置；scopeKey 使用类型和 ID，成员变化沿用同一目标状态但立即刷新内容。跨日新 key 回到默认，屏幕旋转和切 Tab 保留状态。
5. 分钟刷新不触发整屏入场动画；移除以日期数据更新或 enterTick 对全部未来条目重新 snapTo 的行为。首次进入或用户显式展开才应用轻量过渡，不跳滚动位置。
6. 明日卡展示具体日期、第一组全部冲突项和地点，点击仍进入当前那条 EventItem 的详情；没有数据、没有目标和读取失败分别展示。
7. 使用 `HitaTheme`、现有卡片形状/颜色和壁纸适配；采用内容驱动高度、可换行文本及至少 48dp 点击目标。所有产品文案与无障碍说明进入 `strings.xml`。
8. 不让 onTick 或摘要状态变化调用 `WidgetUtils.sendRefreshToAll`。现有 todayEvents 更新触发小组件刷新的行为按真实课程内容变化去重；跨日/课程变化仍按既有规则刷新，不为新布局提高刷新频率。

**测试：** 新增 `TodayScreenInstrumentedTest`。覆盖默认首屏、全量展开、冲突完整展示、已结束时间、明日空状态、错误重试、详情点击、大字体、滚动与展开保持；新 UI 不需要逐个测试装饰样式常量。

## 9. Task 7：路由及来源提示

**修改：** `FragmentTimeLine.MainPageController`、`MainActivity.kt`；复用 `MainViewModel.selectTab(MainTab)`。

新增 `ui/main/timeline/TodayNavigationActions.kt`，提供明确的 `TodayAction` 或等价回调，至少包含 `OpenEvent(id)`、`OpenHomework(source)`、`OpenExams`、`OpenTimetableSelection`、`OpenImport`、`Retry(source)`。事件详情仍经 `EventsUtils.showEventItem`。

- 作业入口在 HOMEWORK Tab 可见时选择现有 Tab；被用户隐藏时新增内部 `ui/main/homework/HomeworkActivity.kt`，复用 `HomeworkFragment` 承载原页面，并在 `AndroidManifest.xml` 注册为 `exported=false`。不改用户 Tab 偏好，不传递不可用下标。
- 考试入口使用现有 `ExamActivity`，日期/目标上下文仅在页面已支持时传入；不伪称已经应用筛选。
- 无目标的选择/导入复用当前时间表管理入口，不能创建第二个选择存储。
- 导航执行前核对来源账号、显示目标和事件 ID 仍有效；失效时回到当前列表并提示刷新，不打开上一账号或上一课表的详情。
- 今日摘要只读，不直接添加完成按钮、隐藏按钮或教务编辑入口；这些操作继续由原页面与统一写入口负责。
- 新来源提示使用“已导入考试”“本地记录”“时间待确认”“Grader 未连接”等明确范围，避免产生全量数据已同步的错觉。

## 10. 第二期 A：考试与作业卡片

第一期通过后，再新增 `TodaySummaryCards.kt`，消费同一 uiState 中已有 summary，不新增查询/定时器。

考试卡显示最近一项可信时间、地点、其他待确认数量和来源状态；作业卡显示今日截止/逾期分项、最早截止时间及来源状态。点击沿用 Task 7 路由。两卡优先 FlowRow/自适应列，大字体和窄屏回退单列。

新增 `TodaySummaryCardsInstrumentedTest`，只验证数据、错误、点击和自适应，不重复第一期投影测试。卡片不能挤走当前安排；没有可用内容时使用来源状态或简洁空提示。

第二期 B 的资源、资讯、Agent 暂不预建类型和空组件。确认该期范围后再新增 `TodayDiscoveryCards.kt`：资讯复用 `CampusNoticeRepository` 的当前校区缓存观察与原刷新流程，仅预览一条并跳转现有资讯页；资源复用 `CourseResourceLinker` 与 `ActivityUtils.startCourseResourceSearchActivity`，从当前课程打开已有资源搜索，不假设已经存在 iOS 收藏模型；Agent 根据 `MainTab.visibleFor` 与实际入口需要接入。此期不新增收藏存储、资讯后台抓取或跨页面 ViewModel 依赖。

Task 7 新增 `TodayNavigationInstrumentedTest`，覆盖隐藏作业 Tab、真实详情入口、失效来源和登录/重试。第二期卡片与第一期动作复用同一份路由。

## 11. 执行顺序、提交与完成条件

| 顺序 | 建议提交 | 包含任务及停止条件 |
|---|---|---|
| 1 | `feat(today): add scoped day window and agenda projection` | Task 1–2，相关 JVM 与 Room/Resolver 回归通过 |
| 2 | `feat(today): expose source-aware homework and exam summaries` | Task 3–4，账号/查询隔离与来源覆盖测试通过 |
| 3 | `feat(today): present focused agenda and tomorrow preparation` | Task 5–7，构建与界面回归通过；首期完整可用 |
| 4 | `docs(today): record first phase validation` | 记录真实设备、模拟服务、构建证据与未验证项 |
| 5 | `feat(today): add exam and homework summary cards` | 第二期 A，独立构建和相关回归 |

前两步保持旧今日 UI 可编译、可使用；删除旧接口在新 UI 接入后进行，避免提交中间态无法构建。每个功能提交前 Review 和验证，推送按当次授权。本次授权仅提交、推送实施方案，尚不执行本表业务修改。

### 本地验证命令

先运行当前任务对应的 `--tests` 精确过滤，修正后只在必要时重跑。首期最终执行：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/check_repository.py --base-ref origin/main
git diff --check
```

不并行运行 Gradle。测试 APK 构建不代表插桩通过；真实手机用 `adb install -r` 和定向 `am instrument`，不使用可能清理数据的连接测试安装流程。受 ColorOS 冻结影响的合成界面/数据套件使用已有前台测试宿主，操作前确认设备状态。

### 首期设备验收清单

- [ ] 混合教务课程、个人日程、考试与组合目标；隐藏条目不出现，切换不闪旧目标。
- [ ] 进行中与下一组完整显示；与 mini 条的课程时间边界一致。
- [ ] 分钟/跨日/时区/前后台恢复、展开与滚动保持。
- [ ] 明日第一组、20:30 门槛、晚课未结束、周日晚与跨午夜安排。
- [ ] 考试缺少时间与来源未知、Grader 逾期/已提交/断连/换号，失败不冒充零数据。
- [ ] 经典/玻璃/波普、浅深色、壁纸、长文本、大字体、无障碍与路由。
- [ ] 页面展示、展开、刷新前后，教务版本数、教务 metadata 和隐藏偏好未改变。
- [ ] 记录测试类、用例数、结果和日志路径；未做真实服务验证则明确列出。

## 12. 本次方案交付验证

本次只新增本计划并在产品设计中添加链接；检查 Markdown 链接、仓库规则与 diff。没有修改业务代码，不运行与文档无关的构建或设备测试，也不沿用旧测试结果冒充本方案已实现。
