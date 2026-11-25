# HITA Android 体验与动效实施计划

**状态：已有分支实现，未通过全部验收。**以 [报告](report.md) 和 [规格](spec.md) 为依据，在 `feat/ux-motion-audit` 上工作。先保证状态和操作正确，再调整动效，不以动画、Toast 或自动重试掩盖不明确的结果。

## 顺序与共用验证

1. **先做安全修复 T-01、T-03 的确认归属部分。**它们各有明确的退出条件，不依赖新动效或其它 UI 重构。T-02 接在 T-01 后，共用课表动作规则；T-03 剩余部分保留会话归属接口。T-04、T-05 可按独立业务域拆交付，涉及同一页面时先完成 T-04 的读取状态，再接 T-05 的写入。T-06 不改主壳动画，T-07 在状态契约稳定后接入。T-08 贯穿采集基线，最后统一验收。
2. **验证现有行为，再补确有缺口的用例。**保留现有测试的约束，不改测试以迁就不正确的结果。常用命令：`gradlew.bat :app:testDebugUnitTest --tests "*ClassName"`、`gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<全限定类名>`；在 POSIX 环境使用 `./gradlew`。下文测试类是现有入口，不表示已覆盖新增契约；先实现再验证，涉及无账号边界用受控数据，不往真实服务写测试记录。
3. **提交、设备包和发布是不同的动作。**通过本轮全部放行检查后才提交工作区改动并替换根目录 `app-debug.apk`。达到复验上限仍失败时保留改动和可复现证据，不提交未验收改动或替换诊断包。约束、持久化结构、外部接口或安全策略需要调整时，先报告冲突，不绕过原验收。本轮不合并、不推送、不发布。

## 工作项

### T-01
**解除退出、误删与口令泄露风险。**规格 [S-02](spec.md#s-02)、[S-04](spec.md#s-04)；证据 [UX-05](report.md#ux-05)、[UX-06](report.md#ux-06)、[UX-08](report.md#ux-08)、[UX-09](report.md#ux-09)。依赖：无。

- 入口：`ui/profile/ProfileActivity.kt`、`ui/main/navigation/NavigationFragment.kt`、`ui/main/MainActivity.kt` 和 `MainViewModel.kt` 的 logout；`ui/timetable/{manager,detail,history,share}`、`ui/event/EventItemFragment.kt`、`ui/main/timetable/TimetableFragment.kt`、`TimetableRepository`/课表写入存储。统一退出对象和本地数据边界，移除资料退出自动清空的路径。将业务动作权限放到可被网格、详情和写入层复用的现有课表边界；确认与结果回执在 UI，不改版本代际约束。批量口令使用已有 FULL/BUSY 编码，不另做协议。
- 退出条件：查退出和取消后的实际行数及可见账号内容；多选删、隐藏、恢复按夹具检验目标对象、失败与重复确认；同一投影从三处进入均无绕行；解码单表与组合不同口令验证字段。复用 `TimetableWriteStoreInstrumentedTest`、`TimetableRecoveryProtocolInstrumentedTest`、`DeletionHistoryDialogInstrumentedTest`、`TimetableManagerSelectionInstrumentedTest`、`ShareSnapshotBuilderTest` 和 `:timetable-core:test`。

### T-02
**课表空态、表单提交与个人待办。**规格 [S-01](spec.md#s-01) 的课表部分、[S-03](spec.md#s-03)、[S-06](spec.md#s-06)；证据 [UX-01](report.md#ux-01)、[UX-07](report.md#ux-07)、[UX-10](report.md#ux-10)。依赖：T-01 的写入规则。

- 入口：`TodayAgendaList.kt`、`FragmentTimeLine.kt`、`TimetableViewModel`、`TimetableManagerViewModel` 与 `DisplayScopeResolver`；把历史重复清理和创建默认表拆开，勿通过只读管理访问触发新增。保留现有明确的 Agent 新建、导入和初次保存行为。`PopupAddEvent`、`PopUpTimePeriodPicker`、`AddEventViewModel` 以合法时间输入和提交回执决定保存与关闭。`HomeworkScreen`/`HomeworkBoard`/`HomeworkViewModel` 分离全列表和日期默认值、各源加载与可撤销操作，平台错误不占掉撤销通道。
- 退出条件：空安装取消导入/进管理/按返回均为零写入，首表正确选日；逆序/等长输入、拒绝和连点储存实测；完成两条待办后到来平台错误，分别撤销并检查 database 与计数。复用 `TodayScreenInstrumentedTest`、`TimetableManagerImportInstrumentedTest`、`TimetableScheduleWriteAtomicityTest`、`HomeworkUiInstrumentedTest`、`HomeworkBoardTest`。

### T-03
**锁住助手会话授权与输入，不让后台请求接管前台。**规格 [S-05](spec.md#s-05)；证据 [UX-11](report.md#ux-11)、[UX-12](report.md#ux-12)、[UX-21](report.md#ux-21)。依赖：无；短窗与输入布局接口给 T-07。

- `AgentChatViewModel`、`ChatConfirmationGate`、`ChatSessionDelivery`、`AgentChatFragment`：先绑定不可变的请求和会话身份，前台根据该身份显示，不改工具授权范围。再把接纳/释放输入、附件和错误复用固定到会话，接已有取消请求能力；停止与拒绝由运行请求标识，不由「当前页」直接推断。换成稳定消息身份和底部跟随判据；让欢迎与列表获取真实输入栏/IME可用空间，保留 Markdown 与资源卡。
- 退出条件：用虚拟请求控制 A 延迟确认、B 完成普通请求、A 确认或取消/停止、会话删除失败；验证 UI 授权只在 A、不会重放写入。接纳拒绝与附件失败守住草稿，长列表历史不跳，320 dp 大字和 IME 不遮挡。复用 `ChatConfirmationGateTest`、`ChatSessionDeliveryTest`、`AgentChatViewModelTest`、`ChatAttachmentProcessorTest`、`AgentChatLifecycleInstrumentedTest`。

### T-04
**对齐每次读取的标题、列表、操作资格与恢复。**规格 [S-08](spec.md#s-08)；证据 [UX-15](report.md#ux-15)、[UX-16](report.md#ux-16)、[UX-17](report.md#ux-17)。依赖：涉及课表范围的部分等 T-02 的范围约束。

- 先完成 `TimetableViewModel/TimetableScreen`、`UnifiedResourceSearchActivity/ViewModel` 与 `ExternalResourceRepository`：每次选择产生可对照的请求键；更新期间保留同键缓存，异键旧行禁用；分开逐源结果。顺带让 `TimetableComparisonViewModel/Activity` 在无来源时只保留有效的调整动作。然后在 `ui/eas/{score,classroom,catalog,exam,training}`、`ui/credit`、`ui/campus` 的 Activity/ViewModel 逐项接入正确的状态与重试入口；不造基类。校区或账号变化后迟到结果不能提交可见状态或污染缓存；重新登录成功再续接，对取消不发假刷新。
- 退出条件：在 UI 观察 A/B 乱序、成绩取消登录、无缓存断网、存在缓存断网、逐源失败、课表换周双击、同一学期刷新；检查原页有真正能用的动作，旧数据不可触发新业务。复用 `TimetableSnapshotFlowInstrumentedTest`、`TimetableHostInstrumentedTest`、`MainRequestAdaptersInstrumentedTest`；其余用能注入顺序/错误的源码边界补行为场景。

### T-05
**手动提交与服务写入往返。**规格 [S-09](spec.md#s-09)；证据 [UX-13](report.md#ux-13)、[UX-14](report.md#ux-14)。依赖：同页读取契约 T-04；授权行为不等动效。

- `CourseContributionActivity/ViewModel`、`SubjectResourceCoordinator` 保证显示与提交用同一份目标、表单和预览；重建无有效预览则回草稿而不是无声失败。`SilentPodActivity/ViewModel`、`ShenzhenCourseCatalogActivity/ViewModel` 跟踪待提交参数和状态，区分本地取消与服务已写；不通过重发消除未知结果。复用已存在的会话校验和选课任务重确认机制。
- 退出条件：预览后改变仓库/重建、失败或重复确认准确给出终态；模拟远端接受而返回超时，仅核对未重发；拒绝及允许精确闹钟许可后同一任务的继续/取消可恢复。复用 `CourseSelectionExecutorTest`、`CourseSelectionAlarmPolicyTest`、`ShenzhenCourseSelectionUiPolicyTest`、`SilentPodSessionPolicyTest`；真实提交只用获准的测试账号及可清理记录。

### T-06
**后台提示、阅读返回、通知与小组件。**规格 [S-07](spec.md#s-07)、[S-10](spec.md#s-10)、[S-11](spec.md#s-11)；证据 [UX-02](report.md#ux-02)、[UX-18](report.md#ux-18)、[UX-19](report.md#ux-19)、[UX-20](report.md#ux-20)。依赖：T-04 的读取状态；T-07 只复用其终态和锚点。

- 分三组做窄改动。① `MainViewModel`/两个 Presenter、`ActivityAbout`、`BlogFragment`/`InfoPortalLoginActivity`、`InstallPermissionGate`：前台占用期间延迟重要提示，普通提示入口常驻；账号更新和同版本交付不靠弹窗存在推断。② `SearchActivity`/教师结果、`BlogFragment`、`SubjectScreen`、`ActivityAbout`、`InternalWebActivity/NewsDetailActivity/MarkdownViewerActivity`：保留查询、滚动、展开偏好，Web 历史先消费 Back，读失败不默默离开。③ `WidgetConfigureActivity`、两种 Today widget/factory、`TodayUtils`、`WidgetDeepLink`/`MainActivity` 与 `CourseReminderWorker`/`ReminderRuleSet`/`EventItemDao`：配置按实例读取，显式范围/背景和受限目标解析；时间窗口、去重与目标链接一起验。
- 退出条件：排队公告、未处理/已处理升级、不成功续期、浏览器/系统返回和进程重建；长内容展开来回、Web 重定向后返回；两款组件各做冷、暖、无效事件，通知规则用可控时钟。复用 `MainNoticePolicyTest`、`MainNoticePresenterInstrumentedTest`、`MainUpdatePresenterInstrumentedTest`、`CourseReminderWorkerInstrumentedTest`、`ReminderRuleSetTest`、`WidgetConfigCodecTest`、`WidgetDeepLinkTest`、`WidgetRefreshWorkerInstrumentedTest`，另做启动器实际手点。

### T-07
**统一动效语义、可交互属性和适配。**规格 [S-01](spec.md#s-01) 的入口语义部分、[S-12](spec.md#s-12)、[S-13](spec.md#s-13)；证据 [UX-03](report.md#ux-03)、[UX-04](report.md#ux-04)、[UX-21](report.md#ux-21)、[UX-22](report.md#ux-22)、[UX-23](report.md#ux-23)。依赖：前述页面有正确、可独立观察的状态后才为其加运动。

- 从 `MainScreen`/`MainTabHost`/`TabSwipe`/`MainDrawer`/`DockTabBar`、`TimetableWeekView`、`MainTopBar`、`HiltBaseActivity` 与在用 `res/anim` 入手。集中 Compose 自定义用到的时长/曲线与减少运动判定；现有 XML 只调整实际使用的转场，避免两套同时驱动。同一参数集由主题共享，不移除原生 IME、滚动/返回、视图 lifecycle 的语义。事件/筛选/加载/数量/展开先选择不动或轻量反馈，再有必要才加位置过渡。连续手势只有一个位移所有者；切换在目标内容就绪后更新，不留下可点击空层。
- 用真实约束保住窄宽/大字和输入法焦点；图标、勾选、下拉展开、数量/错误通过语义暴露，TalkBack 可用与触屏等价的动作。玻璃、赛博、深空只在 traces 确认后优化；先移除重叠的重绘/循环来源，不以固定 FPS 或不透明遮盖冒充性能提升。
- 退出条件：在手势释放的 0%、50%、90% 分别反向拖动或点他页，目标最多提交一次且不吞下一次有效操作；取消、切主题/尺寸、旋转、0 与 1 倍率前后都无空白仍可点层。对 `MainSwipeInstrumentedTest`、`MainThemeInstrumentedTest`、`MainWallpaperInstrumentedTest`、`MainBottomBarGeometryInstrumentedTest`、`TodayLayoutInstrumentedTest`、`TimetableHostInstrumentedTest` 跑实际检查，人工复核 [S-13](spec.md#s-13) 矩阵。运行实际表面，不以语义节点单独证明没有遮挡。

### T-08
**采集真机性能与发布证据。**规格 [S-12](spec.md#s-12)、[S-13](spec.md#s-13)；证据 [UX-21](report.md#ux-21)、[UX-22](report.md#ux-22)、[UX-23](report.md#ux-23)。依赖：基线在调整动效前固定；候选待 T-01 至 T-07。

- 只取得对真机和性能构建配置的授权后建立独立可分析的基准目标（推荐 `:macrobenchmark` 的 `com.android.test`，不使用正式签名或 Debug 样本作为门槛）。测试类 `UxJourneysBenchmark` 用 `MacrobenchmarkRule.measureRepeated(FrameTimingMetric())`。每次由确定性种子准备完成初始屏，脚本使用稳定操作目标，不把准备、应用安装和 UI 树 dump 混进帧区间。实现后的本地入口（`com.android.test` 模块只有 `benchmark` 变体，`connectedCheck` 不会解析它，必须用带构建类型的任务）：

```bash
gradlew.bat :app:assembleBenchmark
gradlew.bat :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=cn.limpu.hita.benchmark.UxJourneysBenchmark
```

单个旅程可加 `#classicTabs` 等后缀。准备阶段处理启动公告，采样配置显式设置 `androidx.benchmark.suppressErrors=EMULATOR`。经典和玻璃主题的页签与抽屉取得帧样本；切周、滚动待办和滚动会话未通过准备阶段，详见 [UX-23-B](report.md#ux-23-b)。`:style`、`:component`、`:hitauser` 声明同名构建类型供该变体解析；目标包与测试 APK 使用 debug 签名，目标包保留种子入口类名。
- 依次运行基线和候选、每设备每旅程至少 10 次；使用基线和候选一致的编译、数据量、刷新率和主题，在报告保存环境、samples/JSON/Perfetto 路径、P95/P99/超时帧和失败的 UI 序列。按 [S-13](spec.md#s-13) 逐行判定，不用跨设备平均遮掩失败。图层、布局、主线程/RenderThread 或系统其他负载要分别归因；没有 trace 不下归因结论。必要时把装饰降为静态，不改变已通过的基本交互。
- 本轮放行：按 [Android CI](../../.github/workflows/android-ci.yml) 执行 Debug、JVM、核心 JAR、style Release 资源与打包、API 35 四模块仪器测试和仓库检查。真机性能、三校区登录及真实服务写入未覆盖，不作为本轮本地验收的前置条件；模拟器帧数据不能代替真机数据。本轮不合并、不推送、不发布。

## 本轮实施与验收结果

**已落地的代表性改动（不代表各项退出条件全部通过）**：T-01 统一退出、批量口令披露、详情批量删除确认；T-02 空态不建表、表单提交回执、待办行内撤销；T-03 确认按会话与请求身份绑定；T-04 `TimetableWindowKey` / `ResourceSearchKey`；T-05 预览与提交同源、静音舱写入跟踪；T-06 公告入口、删除历史入口；T-07 `HitaMotion` 集中时长和系统倍率；T-08 独立基准模块和部分旅程样本。逐项退出条件仍以本计划对应段落为准。

以下为在 `feat/ux-motion-audit` 上实际执行的结果，测试命令与结论一一对应；未列出的设备或账号场景仍属未验证。

- 构建与 JVM：最新 Debug APK、`:app|component|hitauser:testDebugUnitTest`、`:timetable-core:test` / `:jar` 的 Gradle 命令成功；`:style:verifyReleaseResources :style:assembleRelease --rerun-tasks --no-build-cache` 通过。`style` 的 JVM 任务为 `NO-SOURCE`；App JVM 报告 1487 通过，1 个要求 `HOA_INTEGRATION_TEST=1` 的既有线上兼容性测试按默认配置未执行。
- 仓库检查：`python scripts/check_repository.py --base-ref origin/main` 已通过；检查器自测在 WSL Ubuntu 通过 22 个用例，原生 Windows 缺少创建符号链接权限（WinError 1314）。
- 模拟器（API 35，`hita_test`，1080×2400/420 dpi）：验证空态不建表、建表后两页周次一致、表单必填与保存落库、详情页批量删除确认、待办完成与行内撤销、公告列表与抽屉入口。证据见 [报告 §7](report.md#7-本轮修复后的复测)。
- 本轮三项续接：选课排程的目标快照与 `CourseSelectionScheduleContinuity` 由 `ShenzhenCourseSelectionUiPolicyTest` 覆盖（9 用例通过）；资讯展开优先级抽为 `BlogExpansionPolicy` 并由 `BlogExpansionPolicyTest` 覆盖（6 用例通过）；更新分发顺序与 `InstallPermissionGate` 改动已编译，模拟器未走授权往返；历史版本展开偏好经模拟器旋转复验保持，已写入 [报告 §7](report.md#7-本轮修复后的复测)。全量 JVM 为 App 1493（1 跳过）、component 14、hitauser 8、timetable-core 57，均无失败。
- 仪器测试：最新工作区在 API 35 串行执行 App 999、component 7、hitauser 11、style 8，均通过且无跳过。小组件固定范围、过时提醒不补发、WebView 历史与重试均包含在最新 App 结果中。见 [报告 §7](report.md#7-本轮修复后的复测)。
- Macrobenchmark：额外复验达到上限，四个旅程有样本及 40 条 Perfetto 轨迹；长会话启动失败，切周、长待办没有完成结果。见 [UX-23-B](report.md#ux-23-b)。
- 未验证：真机性能门槛、三校区真实登录、真实服务写入、TalkBack/键盘/分屏全矩阵，以及 Macrobenchmark 实机采样。
- 交付状态：本次补充验收没有新增提交；未验收改动和文档保留在工作区。根目录 `app-debug.apk` 仍为之前的 `cn.limpu.hita.diag`，SHA-256 `929b78c5c6d124e647d1b2d954b5ad6cb4c83ce2cffb5b2959ca25319a0b74ad`，与最新 Debug 构建不同，未替换。

## 本轮文档检查

`python scripts/check_repository.py`；另检查 `report.md` 的发现编号唯一，`spec.md` 每条验收、`plan.md` 每项工作均链接到既有报告编号，工作项到验收项无孤项。本轮报告在模拟器观察到的终态和 `gfxinfo` 样本仅供复现与排序，不是上述实施验收。
