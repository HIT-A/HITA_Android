# HITA 阶段 4 执行审计

审计日期：2026-10-26。只读源码审计，未运行 Gradle、未修改仓库、未执行 Git 写入。
快照：工作树 HEAD `6ae7c7011eb53468771494789c1e8fc2d00607b1`；现有未提交文件为 `docs/refactor-handoff.md`。后续实施须以最新合并 main 重新核对。
用户已全权授权阶段 3/4/5 的实施、提交、PR、审核与合并；旧文档中的逐批确认约定不能阻断已授权工作。

## 结论与完成定义

阶段 4 的四个目标文件合计 9,102 行：MainActivity 2,272；TimetableFragment 2,040；WebViewLoginActivity 2,664；SubjectActivity 2,126。约半数是 Compose，但真正问题是宿主同时持有领域判断、异步任务、可变页面状态及平台对象。只把私有方法搬到 extension 或新建一个等大的 Controller 不足以完成阶段 4。

最终验收采用职责和依赖方向，行数仅作辅助：

1. 四个宿主只负责 Android 生命周期、绑定状态、平台导航/权限/结果与窄适配器装配。不得继续直接编排 HTTP、读写文件、计算课程匹配、维护多个请求与观察者集合。
2. 每个新协作对象有明确生命周期与输入/输出；状态/策略可用假依赖独立执行，既不需要 MainActivity/SubjectActivity，又不持有 Activity 或 WebView 跨重建。
3. Compose 展示组件接收状态和事件；数据准备在 Route/ViewModel，通用组件不反查 Activity、仓库或 ViewModel。
4. 原入口、Intent extras、三校区策略、只读课表调用者、写入用例、副作用顺序继续成立。已识别行为修复需单独基线证据，不能通过删旧断言变绿。
5. 生命周期与乱序回调得到实际运行测试；完整既有 testcase 保留，新测试身份清点并实际执行，构建/仓库检查/PR CI 实际报告均过后合并。

建议按下面顺序形成 8 个可审核实现 PR（每个必要的缺陷基线先独立提交；可以在同 PR 中先红后绿）：4.1A → 4.1B → 4.2A → 4.2B → 4.3A → 4.3B → 4.4A → 4.4B。不为拆文件另开无边界价值的小 PR。

## 4.1 MainActivity

### 当前责任与调用者

- `MainActivity.kt:199` 开始：6 个注入仓库/选择器、tab 与 drawer 状态、onStart 刷新用户/自动导课/博客/更新；onStop 移除 token observer。
- `:240` token observer 同时刷新 drawer、决定可见 tab、重置自动导课、触发博客同步。
- `:472–639` 公告优先级、展示/标记已读/analytics、10 分钟自动导课节流与会话过期提示。
- `:641–748` 本地用户与教务用户 profile 投影、退出登录与重新登录。
- `:749–876` 主题、壁纸文件 IO、Glide 加载与颜色采样；`saveWallpaperLocally` 仍用裸 Thread，Glide 回调会写入共享偏好。
- `:905` scope 选择后再计算目标周、查找 TimetableFragment 导航。
- `:947` 后为屏幕/顶部栏/抽屉/背景/tab bar；`:1638` MainFragmentPager 在 Compose 中把 context 强转 MainActivity、直接操作 FragmentManager。
- Pager 当前每次容器新建时移除恢复的同 tag Fragment，再 `commitNowAllowingStateLoss` 重建；恢复契约不可按经验假设正确，应先基线记录再决定修复。
- 外部入口：ActivitySplash、TodayUtils、LiveCourseScheduler、LiveCourseNotificationManager、CourseReminderWorker 都构造 MainActivity Intent。FragmentTimeLine 与 TimetableFragment 各有 MainPageController，MainActivity 实现两者；无须改这些外部 Intent 目标。

### PR 4.1A：页面状态与启动用例

目标结构：

- `MainUiState` / `MainNavigationState`：可见 tab、选中 tab 的稳定 identity、drawer 状态与标题。恢复优先 tab name，旧 index extra 保持兼容。
- `MainStartupCoordinator`（或注入 MainViewModel 的等价用例）：登录 transition、自动导课及成功后节流时间、博客首次可见同步、更新检查节流；依赖 clock、读取设置、导课/同步动作，输出一次性 effect，不依赖 Activity。
- `MainNoticePolicy`：critical > high-version > unseen 的选择；`MainNoticePresenter` 仅接受选择结果与回调、负责 Dialog/analytics/已读时机。策略不触达 UI。
- Drawer 信息由 pure mapper 生成：本地用户优先，次选教务资料、session-expired 标记，最后未登录默认文案参数。
- Host 执行导航/弹窗；用户事件交给 ViewModel/协调器；注销继续清理本地与教务账户。

验收与测试：

- 同账号 token replay、不登录→登录、换账号、到期后登录的调用次数；停启不会重复本次已尝试请求，成功才写节流时间，失败不会写成功时间。
- 新增 blog tab 才重置首次同步机会；当前 tab 不可见时回到首项，可见时保留 identity。
- critical/version/unseen 排序和已读时机：critical/version 展示即已读，普通公告 dismiss 才已读。
- scope 保存与副作用仍通过 DisplayScopeSelector；目标周计算可以独立测试学期内/外边界，不直接重新实现选择副作用。
- destroyed/cleared 宿主不展示迟到导课/公告结果；Activity 再创建不让旧实例收到新 effect。

### PR 4.1B：平台资源与 Fragment 宿主、展示边界

- `MainScreen.kt`、`MainDrawer.kt`、`MainTopBar.kt`、`MainTabBar.kt` 分担 Compose，接收状态和动作；主题公共 glass primitive 可放独立文件。
- `MainTabHost` 接受 FragmentManager/FragmentFactory 或窄 host adapter，负责 tab tag、容器 id、恢复与显隐。Compose 不再 `LocalContext.current as MainActivity`。
- `WallpaperStore` 用应用 context + IO 执行复制/替换/删除；`WallpaperLoader` 是 Activity/生命周期限定的 Glide adapter，close 清理 target；请求 generation 防止 A 的迟到颜色覆盖 B；错误恢复不丢现有有效壁纸。
- Avatar picking 注册仍在 Activity，但 callback 交给明确的动作服务/VM；UI 消息绑定当前宿主。

验收与测试：

- Activity/host 重建、切 tab、visible tabs 动态增减、连续重组不会创建重复 tag 或叠加 Fragment；timeline 激活次数按用户切换契约记录，非动画帧副作用。
- 宿主保存状态后收到选中变更的安全处理有测试；不通过随意 allowingStateLoss 掩盖重建丢失。
- 取消图片选择无写入；复制失败保留前一张；慢 A/快 B 与 destroy 后无状态更新；临时文件清理和权限异常可注入。
- `LiquidGlassSurfaceTest` 反射固定 `cn.limpu.hita.ui.main.MainActivityKt.liquidGlassSurface`。保留同 signature forwarding facade 或经明确兼容审阅后调整入口，不能丢掉 CornerBasedShape 防崩溃断言。
- 各主题、drawer/back、课表标题/壁纸、Insets 保持；至少真机替代的 API35 启动/导航 smoke 和 renderer/host 测试，截图/交互证据与校园账号证据分别标记。

## 4.2 TimetableFragment

### 当前责任与调用者

- `:164–418` Fragment：parent controller 绑定、导航、编辑/删除 Popup、显示范围到课表归属、批次选择 IO。
- `:346` showBatchScopePicker 裸 Thread 查询课程/课表，activity?.runOnUiThread 后开启会话；没有请求代数，无法证明旧请求不会重开已关闭的编辑 UI。
- `:420` TimetableScreen 直接观察大量 ViewModel 数据、拼颜色/style/schedule、展示变更决策和批量编辑。
- `:637` 标题映射已有 TimetableCalendarTitle 可复用；`:658` 周滚动/手势；`:1168–1363` 网格/碰撞/卡片坐标；`:1960` 后视觉工具、时段 hit test、周一计算。
- `TimetableViewModel.kt:28` 反向 import `TimetableFragment.Companion.WINDOW_SIZE`，应迁到 ViewModel/窗口策略自己的常量。
- `ReadOnlyTimetableWeek(:943)` 被 `CoursePlanPreviewScreen` 与 `FriendTimetableDetailScreen` 调用，跨时区与 expandEventHourRange 都有语义，不是 Fragment 私有 UI。
- 既有 TimetableOverlapLayout、TimetableCardTextScale、TimetableDisplayTime、TimetableEventPolicy、applySubjectBatchEdit 已有边界与测试，继续复用，避免另写一套。

### PR 4.2A：页面状态、事件用例与宿主生命周期

- `TimetableRoute` 订阅 VM、输出 `TimetableScreenState`（week/标题/style/事件/作息/变更摘要/批次）；`TimetableScreen` 只消费 state + actions。
- 当前周课表归属和批次 THIS/SLOT/ALL 目标选择集中到可测试 selector；批次加载由 VM/scope 管理取消与 generation，不把线程放新 helper 就算完成。
- Fragment 仅执行 popup/navigation，结果观察使用 viewLifecycleOwner，View composition 与 view 生命周期一致；清理 Popup anchor/view callback。
- title 输出保留 MainPageController 或统一窄 parent title 接口；`navigateToWeek` 改调 VM 方法，避免外部写 MutableLiveData。
- WINDOW_SIZE 与可写 currentPageStartDate 归 VM/窗口模型所有；UI 不读取/修改中间环形窗口索引。

验收与测试：

- 显示范围内多个课表，学期内取 startTime 最早者；无范围/无命中周的添加引导不变。
- THIS/SLOT/ALL、非 CLASS 过滤、空回退、只读投影禁止编辑、目标课表缺失；选课程后迅速切换/关闭/销毁，迟到批次结果不显示。
- attach/detach/recreate view 不重复 observer；旧 view 的 failure Toast/anchor 回调无效。
- 所有新增/编辑/删除仍经 TimetableWriteUseCase；成功后刷新顺序、失败结果不得被 UI “已更新 N 项”假提示掩盖（如需修复先基线）。
- 变更确认/记住忽略、整批采纳/保留保持阶段 2 原子性测试。

### PR 4.2B：复用周视图与布局策略

- `TimetableWeekView.kt` 包含复用可交互/只读周组件；`TimetableEventCards.kt`/`TimetableHeaders.kt` 仅负责展示。
- 可提取 `TimetableGridGeometry`：输入分钟范围、px-per-minute、列宽与已有 overlap 输出，返回中性坐标与时段命中；不读取 Context、VM、repository。
- 时间标签密度、压缩模式、晚课提示条件、节次间空白区命中可纯测。不要改已有 conflict cluster 归并算法。
- 清晰区分 selection state（当前周/冲突 sheet 随输入变化清空）与 animation state（Compose remember）；只读接口仍禁 long click/add。

验收与测试：

- 日边界、午夜/夏令时的 displayTimeZone、课程超出作息的 hourRange；现有读图两个调用者编译并实际展示。
- 0 尺寸、压缩模式、最小 15 分钟卡片、同日重叠 cluster、7 列定位；事件/周/时区变化同步关闭旧冲突 sheet。
- 手势阈值、一次滑动只切周一次、动画取消恢复零 offset；当前周按钮与标题一致。
- 原 layout/text/color/title tests 全保留；至少一项实际组件交互检查接通新边界，不仅测独立 helper。

## 4.3 WebViewLoginActivity

### 当前责任与关键边界

- AppCompatActivity 同时含校园配置、浏览器 setup/client、页面分类、Cookie 汇总与 HTTP、深圳 UA/跳转/自动点击、MFA 识别与原生表单桥、凭据桥、诊断、Compose。
- `WebViewLoginViewModel` 当前空类；把 Activity 全部字段转移其中会错误持有 WebView/Activity。
- `:318–615` WebViewClient 对事件排序敏感：深圳 UA 切换提前 return；MFA generation 更新；eelab 分支；forceWeb/reauth/UA；portal/auth/success/poll fallback。
- `:2005` cookie 轮询 generation + 30 次/500ms；静默模式 18 秒；本部电子实验 token 导航 10 秒兜底。
- `:2131` eelab Jsoup POST 与 `:2278` 威海 Cookie API 均裸 Thread；后者主线程捕获 URL/header，回调再 post WebView。
- `:1940–1997` credential bridge 严格 origin + main-frame + login-page gate，深圳不安装；只成功后 commit，取消/destroy discard。
- `:2404` 外部返回协议：RESULT_OK 与 `cookies`，深圳 `web_base_url`，可选 `electronic_exp_token`；back 始终 RESULT_CANCELED，不能恢复 WebView.goBack 行为。
- `:2468` remove message listener / detach / stop / replace clients / destroy；这套顺序须保留并覆盖 popup WebView。
- 唯一当前主调用者 `PopUpLoginEAS`，传入 `silent_mode/campus/student_type`；错误输入回本部，深圳 student type 归一化。
- 已有 `WebLoginSuccessPolicy`、`ShenzhenWebAutoLogin`、`EasWebCredentialPolicy` 与 JVM tests；应作为核心策略继续用，不复制或扩大 whitelist。

### PR 4.3A：校园会话状态机与网络适配器

- `CampusWebLoginConfig`：URL/probe/origin/UA 配置。
- `WebLoginSession`：显式状态与输入事件（navigation start/finish、cookie snapshot、HTTP completion、timeout、back、close），输出窄命令（load URL、poll、fetch enrichment、success/cancel）。无 WebView/Activity 依赖。
- `WebLoginCookies`：parse、ordered merge、URL JSESSIONID、大小写策略；不读取 CookieManager。
- `WebLoginHttpSource`：eelab/vpn suspend，参数为已捕获 cookies/URL；on IO，明确 request timeout、finally disconnect；cancel 不变为业务失败，不往已关闭 session 投递。
- CookieManager 仅在 platform adapter 捕获；all result publication 在 Main 且核对 session generation；成功/取消至多一次。

验收与测试：

- 三校区成功页/仅 cookie 成功/失败页、portal→JWTS、认证中止轮询、silent 用户交互取消、30 次上限、10 秒 eelab 回退；重复 pageFinished 不重复完成。
- Benbu 必需 cookie 与 URL session、Weihai ticket prefix/JSESSIONID 大小写与补充优先级、Shenzhen direct/proxy base 与学生类型；既有 policy 测试全保留。
- 完成 HTTP 与 timeout/back/destroy 竞争，只有当前代数一次 finish；返回后请求取消/结果丢弃，模拟慢 HTTP 实测。
- 请求 URL/method/headers/form/解析与旧实现等价的 MockWebServer fixture；网络失败依原语义回退已有教务 cookies，不丢登录成功。
- 静态已发现明文日志：VPN cookie API response 及 parsed cookie value。抽 source 时改为键/数量与状态，测试禁止合成敏感值进入 diagnostics。

### PR 4.3B：浏览器、脚本、MFA 与显示边界

- `WebLoginBrowserAdapter`：Activity 生命周期限定，创建配置/销毁 WebView、clients、popup forwarding；callback 发给 session，执行 session 命令。禁止跨 Activity 重建持有旧浏览器。
- `WebLoginCredentialBridge` 独立拥有 install/capture/commit/discard/remove，继续调用已有 policy。
- `WebLoginPageScripts` 作为纯 script builders；`WebLoginMfaController` / `ShenzhenNavigationController` 各自拥有 delayed callbacks 与 navigation generation，可关闭、可替换 scheduler/JS executor。不能把所有 1,000+ 行脚本与状态换名聚到一个 god controller。
- `WebLoginDiagnostics` 仅处理脱敏、debug metrics；`WebViewLoginScreen` 展示传入 overlay/progress，不计算校区策略。
- 现有 MFA_NATIVE_OVERLAY_ENABLED=false 保留；抽离不顺便启用。

验收与测试：

- URL 导航变代后旧 JS callback、轮询或自动点击不得再次 load/submit；统一登录/角色各一次，最大 12 次自动推进；MFA 切换轮询上限与取消。
- credential origin/path/main-frame allowlist，subframe/非校园/深圳拒绝，只有验证成功 commit；取消和销毁 discard，异常 Keystore 不阻断手工登录。
- WebView runtime 测试载入本地 fixture，实际 evaluateJavascript 验证字段自动填充/捕获、不自动泄露到其他 origin；至少测试 setup→page event→destroy 的真实 adapter 链。
- 子窗口关闭/未转发即销毁、listener 安装与移除成对，destroy 后无 UI 调用；保留 API29 forceDark 与 API33 algorithmicDarkening 兼容保护。
- 本地模拟器 fixture 与 HTTP evidence 不能声称三校区真实认证已验证。

## 4.4 SubjectActivity

### 当前责任与调用者

- `:140–215` Activity 注入 EAS/Hoa/External repos、new SubjectReadmeAgentFactory provider、多组 observe/remove map、Markwon。
- `:380–458` 课程 identity key、agent session、失败 direct resolver fallback、候选解析及课程码回填；agent 回调 runOnUiThread 未核对 subject key/generation。
- `:460–677` 每张候选卡 README 与外部课程目录的展开、网络结果、目录栈。不同卡请求独立；同一卡换目录移除 observer，但缺少独立请求身份 guard。
- `:697–906` 课程 README 匹配/截段、TOML meta、HTML table、details/LaTeX 与相对链接；大部分可纯测，当前读取 ViewModel 和 string resources。
- `:949–1001` repo raw URL/编码/代理与 DownloadManager/browser fallback。
- `:1053` SubjectScreen 直接拿 VM，内部课程选择/批改对话框；至2126为显示组件。
- `onStart` 用 `subjectId` Intent 刷新；`onDestroy` 清观察者/dispose agent。`ActivityUtils.startSubjectActivity` 是主要入口，EventItemFragment 判断是否已在 SubjectActivity 以避免再开页面。
- 现有 SubjectViewModel 只管理课程基础 LiveData 与统一写入；已有 SubjectBatchEdit/MarkdownPreprocessor/CourseResourceLinker tests 可复用。

### PR 4.4A：课程资源状态与请求会话

- `SubjectResourcesViewModel` 或主 VM 中注入的 `SubjectResourceCoordinator`：subject/campus immutable key、agent→fallback、候选 states、外部目录栈、展开/收起、独立卡片 request generation；观察接入策略统一，clear 释放所有 job/session。
- `SubjectResourceInputs` 暴露窄 resolver/readme/search/list 接口；不能要求 Activity LifecycleOwner。配合阶段 3 的 agent 结果/cancel API；如仍需 legacy LiveData，adapter 在 Main 注册、on cancellation 移除，真实实现保持终态语义。
- `SubjectUiState` 保存课程展开/选中 id/进度/资源状态；重新打开 subject 时 reset 对应状态。raw mutable model 回填经 repository/VM 方法，校验请求 subject identity，避免旧回调回填新课程。
- Activity 只收 effect 执行 contribution/navigation/download；渲染读取 state。

验收与测试：

- 快速 subject A→B、name/code 更新、campus 改变、A agent 迟到与 fallback 迟到：不能覆盖 B 或保存到 B。
- 同 key replay 不重查；agent 失败只 fallback 一次；取消不触发 fallback；唯一 exact name code 才补空 code，已有 code 不覆盖。
- 卡片展开加载、收起移除、同卡再次展开缓存行为；不同卡独立；同卡路径 A→B 的乱序结果只展示 B，back 正确还原 breadcrumb/entries。
- ViewModel clear 停全部 observer/session，stop/recreate 策略明确，无旧 Activity retained；Subject Intent 与 back 清选中优先行为不变。
- 课程保存/删除/编辑继续统一写入；当前所选 id 与实时 classes 交集处理有测试，计数与完成提示基于实际结果。

### PR 4.4B：Markdown/资源打开策略与展示层

- `SubjectReadmeFormatter`：显式输入 markdown + subject match snapshot + candidate + fallback details title，返回格式化内容；重用 MarkdownPreprocessor，绝不从 ViewModel取隐藏输入。
- `SubjectResourceOpenPolicy`：entry → Browser/MarkdownViewer/Download/NoOp，负责 repo/source、segment encoding、proxy URL；`SubjectResourceLauncher` 平台适配器执行且处理下载失败回退。
- `SubjectMarkdownRenderer` 包装 Markwon 构造与 link callback，保持 view tag source 优先，颜色/字体/插件一致；formatter 与 renderer 不互相持有 Activity。
- `SubjectScreen` / course cards / resource cards 单向 state+actions，不直接观察 repository/VM；batch dialogs 继续复用既有组件。

验收与测试：

- TOML-course metadata 优先、code normalization/name aliases、crossspecialty 排除、层级标题边界、未匹配返回原文；包含多个课程与嵌套标题 fixture。
- HTML table 空列/参差列、details 默认和 closed=true、inline LaTeX、相对/绝对/无效 URI 行为，与旧输出对照。
- FIREWORKS direct browser、其他来源 raw URL/空格/中文/特殊字符逐段编码、.md 大小写、空 path/url；DownloadManager 失败 browser fallback，无 Activity 泄漏。
- Markdown 实际渲染 link 来源使用该卡 source，不被其他卡状态污染；点资源卡/目录返回/选课程/批量编辑的组件路径实际运行。

## 横向验证与证据

- 每 PR 先固定实现前必要行为基线；确认为 bug 的用例独立先红后绿；独立 reviewer 审接口和生命周期、检查覆盖是真实调用链。
- 新策略测试放 JVM；Android 生命周期/LiveData/WebView/Room/Fragment tests 放 instrumentation。已有 app androidTest 包含 junit/espresso/MockWebServer，未看到现成 Compose UI test 依赖或 ActivityScenario 使用；需要新依赖时版本统一且纳入阶段 5，而不是用 helper tests 冒充 UI runtime。
- 构建使用 `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew testDebugUnitTest assembleDebug` 与 `:app:assembleDebugAndroidTest`，设备跑完整 `:app:connectedDebugAndroidTest`；不要用根 assembleDebugAndroidTest（历史 hitauser minSdk 冲突）。
- 对每轮 XML 精确对比既有与新 testcase 完整身份，failure/error/skipped/重复/缺失，不仅总数；PR 实际 CI artifact 独立检查。
- `python3 scripts/check_repository.py --base-ref origin/main`、health、consistency、git diff --check。不新增 data→ui 例外。阶段4结束再次查四个宿主和所有提取类，防止只是将旧依赖链隐藏一层。
- 用户授权自动 merge，但每次使用最新合并 base、新分支、独立 review、green CI；自动 merge 之后重新 fetch 验证 main 含预期最终树再推进后续批。

## 与阶段 3/5 的衔接

- 阶段3 EAS 注入、agent 单次结果/取消及无 LifecycleOwner 请求接口，是 Main startup/Subject 资源调度的前置；先确认真实接口后复用，避免不同抽取 PR 重建两个 scheduler。
- Main 的 update LiveData.awaitValue 私有桥也属于一次性查询迁移范围，取消回调线程与重复完成须审查；不要在阶段4复制。
- 阶段5 pure Kotlin 提取应优先选已经无 Android 边界的 protocol/compare/上述 formatter 或 geometry；提取 UI controller 不是纯模块化。
- 当前未解决的账号/校区缓存隔离、事务内文件恢复风险等不是阶段4四类抽离的替代完成条件，保留 root 的全阶段台账，不用该报告缩减阶段3/5目标。
