# HITA Android 体验与动效审查报告

**审查基线：**`d1def433`，诊断版 3.3.1。**状态：**发现已登记；本轮没有修复。优先级：P0（数据丢失、误写或未明确授权的披露）、P1（任务中断、状态错误或常用路径障碍）、P2（反馈、语义、动效或适配缺口）。「实测」指在模拟器观察到所述结果，不代表整条业务或所有配置通过；「源码」只确认分支及其前提；「待验证」不计作通过。

优先处理退出登录清空课表、批量删除、跨会话写入确认和批量口令披露。动效不应掩盖这些错误，也不应盖住用户正在操作的内容。模拟器已复现窄屏助手遮挡、窄屏课表标题截断、无壁纸时仍可移除、课表引导无写入操作却改变状态，以及断网资源搜索显示为无结果。Debug 模拟器帧数据提示玻璃风格值得单独测量，不足以判断真机帧率或定位根因。

## 1. 状态覆盖与实测边界

从 [Manifest](../../app/src/main/AndroidManifest.xml#L71-L209)、[主容器路由](../../app/src/main/java/cn/limpu/hita/ui/main/MainActivity.kt#L124-L177)、[底栏可见规则](../../app/src/main/java/cn/limpu/hita/ui/main/MainTab.kt#L19-L51) 和 [更多入口](../../app/src/main/java/cn/limpu/hita/ui/main/navigation/NavigationFragment.kt#L144-L231) 向下追踪 ViewModel、Fragment/Activity、Compose 状态及系统交接，再按状态变化归类。覆盖范围包含应用控制的前台界面、通知与桌面组件入口；第三方网页、选档器和系统设置只审查进入、取消和返回边界。源码覆盖是业务入口和代表性状态路径，不是所有布局、数据组合都被运行或逐帧检查。

| 编号 | 触发 → 观察到的结果 | 证据层级与未覆盖 |
|---|---|---|
| CV-01 | 冷启动 → 「新公告」挡在今日前；确认后见到无课表引导。今日→选课表→返回变成第 1 周，无新增确认。账号抽屉用返回关闭；关于页打开和返回，返回后仍是原抽屉。 | 实测；公告到达时恰在编辑/阅读、系统边缘返回全程、进程恢复未实测。 |
| CV-02 | 时间表：分享与好友打开/返回；设置展开有移除壁纸；本地表点教务版本未打开版本列表；右上加号进入课时表单，返回到网格。关闭抽屉后，今日向左滑到时间表，再向左滑，时间表日期从 23–29 变成 30–6，留在时间表。 | 实测空表与表单入口；有效/无效保存、批量删除、已填充跨范围、教务导入及版本、模板复用、口令/ICS 往返、好友导入和对比仅查源码。 |
| CV-03 | 待办：后一天→今天恢复日期。输入 `UX-audit-item` 后列表出现、待完成 0→1、默认截止当天 23:59；完成后 1→0、进入折叠区并出现撤销，展开后再次勾选恢复。编辑→删除确认→返回保留编辑器。宽屏今日摘要可见该项。 | 实测本地创建、完成、反向勾选与取消删除；未点击限时撤销、未实际删除，远程平台加载、连接、撤销竞争仅查源码。 |
| CV-04 | 更多→考试先出现认证弹层；成绩、空教室也出现认证，取消后分别留在只有占位数值的查询页、显示「加载失败」的空教室页。未输入凭证。更多→课程提醒可见「下课时」。 | 实测这些入口/返回，未验证成绩、空教室、考试业务结果或发通知；课表/考试导入、选课池/排程、培养方案/学分、校区和学籍分支仅查源码。 |
| CV-05 | 更多→更多功能→开启助手，返回底栏多一项。助手欢迎、快捷提问、输入、AI 设置打开和取消可见；未配置 API Key。一次点击附件后没有取得系统文件选择器的证据。 | 实测可见性与设置往返；附件选取结果、请求/确认/中止/删除持久化、助手与课表长数据未实测。 |
| CV-06 | 更多→查询资源，关闭模拟器 Wi-Fi/移动数据后提交 `physics` → 「没有找到相关课程资源」；恢复网络后返回更多。关于显示历史版本加载失败。 | 实测；未验证资源服务正常返回、逐源失败、教师搜索、深链、课程资料 PR、阅读器历史、更新安装。 |
| CV-07 | 在默认 1080×2400、420 dpi（约 411 dp 宽）浅色运行；840×1800（320 dp）深色、字号 1.3、三个动画倍率 0：底栏可切换，助手快捷提问被输入栏/导航重叠，课表「非所选学期」肉眼见「非所选…」。2268×1700（864 dp）浅色、字号 1、倍率 1：今日摘要和时间表可见。 | 实测截图和节点树；为显示尺寸覆盖，不等同分屏、折叠、TalkBack、键盘/鼠标悬停、逐帧或所有主题通过。 |
| CV-08 | 已登录后状态：深圳 API/补 Web、深圳资讯门户、GeniusTA/Grader、四项深圳校园服务；本部 Web/校区通知/开学日期校准，威海本研/直连与校外路径。 | 仅源码：[EAS 登录](../../app/src/main/java/cn/limpu/hita/ui/eas/login/PopUpLoginEAS.kt#L150-L260)、[Web 分支](../../app/src/main/java/cn/limpu/hita/ui/eas/login/WebViewLoginActivity.kt#L338-L429)、[校园入口](../../app/src/main/java/cn/limpu/hita/ui/main/navigation/CampusExtension.kt#L18-L49)、[资讯](../../app/src/main/java/cn/limpu/hita/ui/main/blog/BlogFragment.kt#L137-L172)。没有真实账号，未触发服务写入。 |
| CV-09 | 小组件普通/精简、配置、点击和刷新；通知权限、课程状态、下载/安装回调；构建图中旧 Welcome、旧资源/讲座/教师资料及搜索深链。 | 仅源码：[Manifest](../../app/src/main/AndroidManifest.xml#L71-L130)、[Welcome](../../app/src/main/java/cn/limpu/hita/ui/welcome/WelcomeActivity.kt#L61-L168)、[Activity 入口](../../app/src/main/AndroidManifest.xml#L157-L209)。旧声明不等于当前菜单可达；未强行启动非公开 Activity，未装真实设备。 |

## 2. 入口出现、切换与消失

### UX-01
**P1 · 访问空课表 → 悄悄创建默认表。实测 CV-01/02。**「添加你的第一张课表」同时给「选择课表」「导入课表」。点击选择、未确认任何写入并返回，已存在「默认课表1」，今日入口消失；时间表对同一日期又显示「非所选学期」，横滑下一周后显示「第2周」。[管理 ViewModel](../../app/src/main/java/cn/limpu/hita/ui/timetable/manager/TimetableManagerViewModel.kt#L168) 和 [时间表 ViewModel](../../app/src/main/java/cn/limpu/hita/ui/main/timetable/TimetableViewModel.kt#L134-L155) 会触发 [准备列表写入](../../app/src/main/java/cn/limpu/hita/data/repository/TimetableRepository.kt#L1009-L1051)。无目标、有一个空表、有导入或既有表，应该是三个清楚的入口状态，不应让浏览冒充创建，也不应让用户重选系统唯一能判定的表。

### UX-02
**P1 · 通知到达或登录过期 → 打断未完成任务。实测 CV-01，竞态仅源码。** 普通「新公告」进应用即出现；[通知 Presenter](../../app/src/main/java/cn/limpu/hita/ui/main/MainNoticePresenter.kt#L23-L74) 把普通公告也做成模态，且 dismiss 会标 seen。[主 Activity](../../app/src/main/java/cn/limpu/hita/ui/main/MainActivity.kt#L205-L228) 在 `RESUMED` 消费多个提示，但不检查前台是否正在输入、编辑或读别的弹层。深圳资讯 `NEED_LOGIN` 在无可用凭证时会直接拉起交互登录；静默门户对已识别 MFA/缺凭证有出口，对卡住或不识别的导航缺全程结束条件。[资讯状态](../../app/src/main/java/cn/limpu/hita/ui/main/blog/BlogFragment.kt#L251-L310)、[门户登录](../../app/src/main/java/cn/limpu/hita/ui/notice/InfoPortalLoginActivity.kt#L46-L119)。保留真正关键公告与用户主动认证；把非必要结果放回原任务上下文。

### UX-03
**P2 · 打开抽屉/切换页面 → 手势与返回交接不一致。实测 CV-01/02，抢占仅源码。** 抽屉按 Back 能关闭，关于返回仍显示抽屉。[主屏](../../app/src/main/java/cn/limpu/hita/ui/main/MainScreen.kt#L105-L127) 经典风格缩放整页至 0.8 并移出 260 dp，玻璃风格仅移 10 dp 并缩小 0.8%；scrim 使用无点击语义的 `pointerInput`。两种外观表达的是同一个抽屉操作。[主 Activity](../../app/src/main/java/cn/limpu/hita/ui/main/MainActivity.kt#L220-L228) 总是截获根 Back，抽屉关闭时手动发 HOME；预测式返回、焦点回到触发按钮、抽屉背后是否仍可从辅助服务操作，需设备验证。底栏点击即换内容、滑动两段各 150 ms；[TabSwipe](../../app/src/main/java/cn/limpu/hita/ui/main/TabSwipe.kt#L179-L269) 和 [切周](../../app/src/main/java/cn/limpu/hita/ui/main/timetable/TimetableWeekView.kt#L141-L198) 明确忽略收尾期间再拖动，不是跟手接管。保留子控件优先消费、课表滑动专属换周及 [Fragment 仅一页可见](../../app/src/main/java/cn/limpu/hita/ui/main/MainTabHost.kt#L73-L108) 的约束；不要重引入透明但可点的页面。

### UX-04
**P2 · 不适用的入口仍出现，或有效入口没有语义。实测 CV-02/07，读屏仅源码。** 无壁纸也显示「移除壁纸」；自定义课表仍显示「教务版本」，点击无列表，实际分支仅发短 Toast；新增课时 `ToolbarIcon` 有图形和触摸处理，但没有按钮角色或名称。胶囊式底栏以颜色、指示器表达选中，普通 `clickable` 未提供选中语义；Dock 已有 `Role.Tab/selected/onClick`，可沿用。未读点不是可读的状态。证据：[工具栏](../../app/src/main/java/cn/limpu/hita/ui/main/MainTopBar.kt#L406-L505)、[版本过滤](../../app/src/main/java/cn/limpu/hita/ui/main/timetable/TimetableFragment.kt#L57-L80)、[Pill](../../app/src/main/java/cn/limpu/hita/ui/main/MainTabBar.kt#L215-L265)、[Dock](../../app/src/main/java/cn/limpu/hita/ui/main/DockTabBar.kt#L451-L498)。图形和触觉可以辅助，但不能替代文本、焦点和选中状态；鼠标悬停不作为唯一发现方式。

## 3. 值变化、提交与撤销

### UX-05
**P0 · 退出账号 → 不同入口产生不同数据后果。源码。** 个人资料仅提示「确定退出？」；随后退出 HITA、异步清空课表、课程、事件、组合、教务版本和来源等并关页。更多菜单直接退出两类会话，却不清这批本地数据。入口没有把「退出哪种账号」「删除本机数据」分开。[资料页](../../app/src/main/java/cn/limpu/hita/ui/profile/ProfileActivity.kt#L106-L115)、[清理事务](../../app/src/main/java/cn/limpu/hita/data/repository/TimetableRepository.kt#L1450-L1460)、[更多账号菜单](../../app/src/main/java/cn/limpu/hita/ui/main/navigation/NavigationFragment.kt#L271-L297)。

### UX-06
**P0 · 多选删除/隐藏 → 没看见影响范围就提交。源码。** 管理页删除立即排队并清除选择；课表删除事务连带事件和课程，不写逐事件恢复。详情批量操作也会直接删除个人课时或隐藏教学课时，语义不同；组合页已有确认，不能只给部分入口做保护。[管理](../../app/src/main/java/cn/limpu/hita/ui/timetable/manager/TimetableManagerActivity.kt#L166-L182)、[删除事务](../../app/src/main/java/cn/limpu/hita/data/repository/TimetableRepository.kt#L448-L460)、[课表详情](../../app/src/main/java/cn/limpu/hita/ui/timetable/detail/TimetableDetailActivity.kt#L150-L167)、[组合](../../app/src/main/java/cn/limpu/hita/ui/timetable/manager/group/TimetableGroupActivity.kt#L243-L255)。

### UX-07
**P1 · 按保存 → 表单消失但不保证写入。源码；仅实测进入和退出表单。** 课时表单就绪时才用动画加入勾；点后发起创建/更新并关闭，不观察提交回执。时间选择可提交等长或反向范围，末端建模才过滤非正时长，因而存在点勾却没保存的路径。[表单](../../app/src/main/java/cn/limpu/hita/ui/event/add/PopupAddEvent.kt#L326-L336)、[就绪和写入](../../app/src/main/java/cn/limpu/hita/ui/event/add/AddEventViewModel.kt#L350-L381)、[时间选择](../../app/src/main/java/cn/limpu/hita/ui/widgets/PopUpTimePeriodPicker.kt#L154-L158)、[构建](../../app/src/main/java/cn/limpu/hita/data/repository/ScheduleEventCreator.kt#L52-L62)。

### UX-08
**P1 · 删除/隐藏后的恢复入口、可编辑权限取决于从哪儿进入。源码。** 个人删除有历史记录和恢复实现，但 [删除记录 Dialog](../../app/src/main/java/cn/limpu/hita/ui/timetable/history/DeletionHistoryDialog.kt#L27) 未找到生产调用；[时间表入口](../../app/src/main/java/cn/limpu/hita/ui/main/timetable/TimetableFragment.kt#L57-L80) 打开的是教务版本、排除自定义表。网格长按对投影只读，点开通用详情后仍可能有删除入口；冲突叠卡打开详情的编辑可达性又不同。[课时详情](../../app/src/main/java/cn/limpu/hita/ui/event/EventItemFragment.kt#L322-L373)、[删除派发](../../app/src/main/java/cn/limpu/hita/data/repository/TimetableRepository.kt#L1262-L1286)、[冲突卡](../../app/src/main/java/cn/limpu/hita/ui/main/timetable/TimetableEventCards.kt#L128-L189)。保留教务版本变更预览、版本校验和教学隐显的独立语义，不以教务版本还原冒充个人撤销。

### UX-09
**P0 · 批量口令生成 → 内容披露范围被默认选定。源码。** 单个分享可以选「完整 / 仅忙闲」，管理页口令分支对单表和分组固定 FULL；分组包含课程名、地点、教师，结果可立即复制/分享。[单表分享](../../app/src/main/java/cn/limpu/hita/ui/timetable/share/TimetableShareScreen.kt#L62-L95)、[管理导出](../../app/src/main/java/cn/limpu/hita/ui/timetable/manager/TimetableManagerActivity.kt#L186-L224)、[管理生成](../../app/src/main/java/cn/limpu/hita/ui/timetable/manager/TimetableManagerActivity.kt#L358-L370)、[快照](../../app/src/main/java/cn/limpu/hita/data/repository/TimetableSharingRepository.kt#L78-L100)。

### UX-10
**P1 · 勾选、日期和数量更新 → 有时消失快于用户能撤回。部分实测 CV-03。** 本地完成有明确计数与撤销，已完成可以展开重开；保留这条路径。源码中远程失败或下一次完成会替换同一 Snackbar；个人存储就绪就能渲染「暂无待办」，不等待已连接的平台。日期条实际只设新增默认日期与计数，列表仍包含所有日期；当前说明与较显眼的日期控件容易让人误以为是筛选。证据：[待办状态与 Snackbar](../../app/src/main/java/cn/limpu/hita/ui/main/homework/HomeworkScreen.kt#L94-L175)、[待办列表](../../app/src/main/java/cn/limpu/hita/ui/main/homework/HomeworkScreen.kt#L353-L455)、[板模型](../../app/src/main/java/cn/limpu/hita/ui/main/homework/HomeworkBoard.kt#L55-L98)。

### UX-11
**P0 · 后台助手请求 → 写入确认出现在错的对话。源码。** 请求回复按会话交付，但共享门在请求确认时读取当时 *选中* 的会话，把 A 的工具请求贴上 B 的标识；屏幕不校验请求归属即弹窗。切换不应取消所有读请求，但确认、拒绝、停止必须指向原请求。[请求](../../app/src/main/java/cn/limpu/hita/ui/main/agent/AgentChatViewModel.kt#L279-L297)、[门绑定](../../app/src/main/java/cn/limpu/hita/ui/main/agent/AgentChatViewModel.kt#L58-L72)、[确认状态](../../app/src/main/java/cn/limpu/hita/ui/main/agent/ChatConfirmationGate.kt#L33-L50)、[弹层](../../app/src/main/java/cn/limpu/hita/ui/main/agent/AgentChatFragment.kt#L344-L372)。

### UX-12
**P1 · 发送、附件失败、停止或删除 → 草稿/回复状态不可预测。源码；欢迎和设置见 CV-05。** 清空输入在发送接纳之前，附件待选状态不是按会话保存；加载只有禁用和忙碌标记，没有停止；附件不能就地撤掉，复制的数量说明与单附件状态不一致。删除提示早于持久化；思考过程是状态快照而非逐字流，行键依赖文本 hash、自动滚动随列表条数变化，更新可能重挂载气泡或把历史阅读拉回底部。[发送与删除](../../app/src/main/java/cn/limpu/hita/ui/main/agent/AgentChatFragment.kt#L159-L186)、[草稿/列表和输入](../../app/src/main/java/cn/limpu/hita/ui/main/agent/AgentChatFragment.kt#L419-L665)、[请求 runner](../../app/src/main/java/cn/limpu/hita/agent/llm/ChatRequestRunner.kt#L37-L56)、[持久化协调](../../app/src/main/java/cn/limpu/hita/ui/main/agent/AgentChatViewModel.kt#L116-L194)。跳动与恢复效果待动态验证。

### UX-13
**P1 · 提交课程资料预览 → 重建后可能还显示旧授权。源码。** `pendingOps` 在组合内，预览响应在 ViewModel。重建后可以看到预览，但确认拿不到操作，直接返回。另一路从课程页提交的目标取最后展开的候选，折叠后不清除，该按钮没有说明要修改哪个仓库。[贡献页状态](../../app/src/main/java/cn/limpu/hita/ui/resource/CourseContributionActivity.kt#L172-L191)、[确认](../../app/src/main/java/cn/limpu/hita/ui/resource/CourseContributionActivity.kt#L586-L655)、[课程资源目标](../../app/src/main/java/cn/limpu/hita/ui/subject/SubjectResourceCoordinator.kt#L57-L67)。

### UX-14
**P1 · 提交预约/取消/选课排程 → 尚未知道结果就失去操作上下文。源码。** 静音舱确认立即关闭，无对外写入忙碌态；刷新或新操作会取消客户端 Job，远端是否执行不确定，不能用本地取消当成远端未写。认证失败清会话却只发消息。[预约 Activity](../../app/src/main/java/cn/limpu/hita/ui/campus/SilentPodActivity.kt#L714-L733)、[预约 ViewModel](../../app/src/main/java/cn/limpu/hita/ui/campus/SilentPodViewModel.kt#L204-L260)。选课排程返回通知权限后可继续，精确闹钟分支却丢失该步骤；等待任务可一键取消。保留二次确认、提交前再校验、真实逐项结果。[选课页](../../app/src/main/java/cn/limpu/hita/ui/eas/catalog/ShenzhenCourseCatalogActivity.kt#L749-L778)、[任务状态](../../app/src/main/java/cn/limpu/hita/ui/eas/catalog/ShenzhenCourseCatalogViewModel.kt#L467-L579)。

## 4. 内容替换、等待、失败与恢复

### UX-15
**P1 · 换周/显示范围/目录 → 新标题下面可能留下可操作的旧内容。源码，具体时序待验证。** 时间表的周和范围先驱动标题，异步窗口结果不携带相同的身份；目录进入/返回分别更新栈和加载，列表在期间仍接受点击。先保证 `查询键 → 内容及操作资格` 一致，再决定如何过渡。[课表数据](../../app/src/main/java/cn/limpu/hita/ui/main/timetable/TimetableViewModel.kt#L134-L152)、[课表消费](../../app/src/main/java/cn/limpu/hita/ui/main/timetable/TimetableScreen.kt#L75-L99)、[目录](../../app/src/main/java/cn/limpu/hita/ui/resource/UnifiedResourceSearchActivity.kt#L278-L319)、[旧行可点](../../app/src/main/java/cn/limpu/hita/ui/resource/UnifiedResourceSearchActivity.kt#L449-L455)。对比页会等到周次匹配，不把旧周换标题；缺来源时却仍提供无效 Retry，应突出「调整」而非重复同一个请求。[对比](../../app/src/main/java/cn/limpu/hita/ui/main/timetable/compare/TimetableComparisonActivity.kt#L141-L146)。

### UX-16
**P1 · 网络失败、未登录、真实空结果看起来相同。部分实测 CV-04/06。** 断网资源搜索显示无结果；成绩取消登录后留在无会话提示的占位页，空教室只显示通用失败。[资源聚合](../../app/src/main/java/cn/limpu/hita/ui/resource/UnifiedResourceSearchViewModel.kt#L59-L82) 合并逐源结果时隐藏部分不可用；[空教室查询](../../app/src/main/java/cn/limpu/hita/ui/eas/classroom/EmptyClassroomActivity.kt#L338-L368) 对零结果和无缓存失败可落到相同网格；深圳官方学分页的学期失败/空列表可使进度留在 `NOTHING`，重试没触发学期前置请求。[学分状态](../../app/src/main/java/cn/limpu/hita/ui/credit/CreditStatsViewModel.kt#L43-L104)、[成绩](../../app/src/main/java/cn/limpu/hita/ui/eas/score/ScoreInquiryActivity.kt#L766-L791)。考试只在成功后画空态且保留缓存提示，可复用该语义而非复制页面。[考试](../../app/src/main/java/cn/limpu/hita/ui/eas/exam/ExamActivity.kt#L520-L546)。

### UX-17
**P1 · 再刷新、重新登录或从系统返回 → 请求没刷到目标或彼此覆盖。源码。** 课程目录刷新（含过期横幅）重取学期，同学期短路，没有重取当前页。大学城图书馆可重复点刷新且无最新请求保护；实验中心防止旧结果覆盖显示，但缓存写入在检查之前；一卡通保留账单时，将重登需求折叠为普通同步失败。提示强度应与当下有用的恢复操作一致。[目录刷新](../../app/src/main/java/cn/limpu/hita/ui/eas/catalog/ShenzhenCourseCatalogActivity.kt#L486-L488)、[查询重试](../../app/src/main/java/cn/limpu/hita/ui/eas/catalog/ShenzhenCourseCatalogViewModel.kt#L406-L440)、[图书馆](../../app/src/main/java/cn/limpu/hita/ui/campus/UniversityTownLibraryActivity.kt#L89-L136)、[实验中心](../../app/src/main/java/cn/limpu/hita/ui/campus/LabCenterActivity.kt#L143-L174)、[校园卡](../../app/src/main/java/cn/limpu/hita/ui/campus/CampusCardActivity.kt#L188-L239)。

### UX-18
**P2 · 搜索、展开、阅读再返回 → 丢上下文或隐藏常用动作。源码。** 教师搜索 `onStart` 把初始参数写回输入框，返回可能丢编辑状态；点老师条目开 README，教师主页在长按。资讯已有展开/折叠动画，但以行局部 `remember` 保存手动展开；课程资料和历史版本直接插入/移除，长内容中锚点、焦点、图标语义需统一。WebView 用当前 URL 判断是否要重载入口 URL，重组可能撤销重定向或用户跳转；返回未优先处理网页历史。Markdown 请求失败没有原位重试，旧讲座首载缺可读的等待反馈。[搜索](../../app/src/main/java/cn/limpu/hita/ui/search/SearchActivity.kt#L79-L148)、[教师结果](../../app/src/main/java/cn/limpu/hita/ui/search/teacher/FragmentSearchTeacher.kt#L70-L149)、[资讯展开](../../app/src/main/java/cn/limpu/hita/ui/main/blog/BlogFragment.kt#L534-L607)、[课程展开](../../app/src/main/java/cn/limpu/hita/ui/subject/SubjectScreen.kt#L664-L690)、[关于](../../app/src/main/java/cn/limpu/hita/ui/about/ActivityAbout.kt#L522-L577)、[Web](../../app/src/main/java/cn/limpu/hita/ui/resource/InternalWebActivity.kt#L101-L119)、[新闻](../../app/src/main/java/cn/limpu/hita/ui/news/NewsDetailActivity.kt#L148-L170)、[Markdown](../../app/src/main/java/cn/limpu/hita/ui/resource/MarkdownViewerActivity.kt#L148-L171)。

### UX-19
**P1 · 更新提示重建/权限返回 → 同一版本漏提示或重复提示。源码。** 启动结果发出前已占版本键，宿主重绑时再次 claim，待处理结果可能不再交付；关于页以保留结果驱动一次性对话框，重建又可能执行。安装权限续接使用短时内存请求与通用 Resume，而不是明确的返回任务；只有浏览器分发的更新也先检查安装权限。必须分离版本去重、待交付、用户已处理和权限返回。[主状态](../../app/src/main/java/cn/limpu/hita/ui/main/MainViewModel.kt#L96-L104)、[更新检查](../../app/src/main/java/cn/limpu/hita/ui/main/MainViewModel.kt#L212-L252)、[关于提示](../../app/src/main/java/cn/limpu/hita/ui/about/ActivityAbout.kt#L164-L212)、[安装续接](../../app/src/main/java/cn/limpu/hita/update/UpdateFlowCoordinator.kt#L51-L92)、[下载分支](../../app/src/main/java/cn/limpu/hita/utils/ActivityUtils.kt#L488-L505)。

### UX-20
**P1 · 系统提醒、小组件的选项已选中 → 实际结果并未对应。配置入口部分实测 CV-04，执行仅源码。**「下课时」候选要求 `end <= now`，[Worker](../../app/src/main/java/cn/limpu/hita/data/work/CourseReminderWorker.kt#L36-L57) 选择同一 `now` 拿仍重叠的事件，[DAO](../../app/src/main/java/cn/limpu/hita/data/source/dao/EventItemDao.kt#L67-L68) 要求 `end > now`，候选被排除；点通知不带课程日期。[规则](../../app/src/main/java/cn/limpu/hita/data/work/ReminderRuleSet.kt#L157-L193)。小组件保存「固定」哨兵与背景选项，却读取当前范围、按主题渲染；列表行使用 fill-in 参数但模板不可变，主 Activity 只在暖 `onNewIntent` 处理且只选今日。证据：[组件配置](../../app/src/main/java/cn/limpu/hita/ui/widgets/WidgetConfigureActivity.kt#L104-L141)、[行数据](../../app/src/main/java/cn/limpu/hita/ui/widgets/today/normal/ListRemoteViewsFactory.kt#L60-L101)、[主题与模板](../../app/src/main/java/cn/limpu/hita/ui/widgets/today/TodayUtils.kt#L54-L106)、[主入口](../../app/src/main/java/cn/limpu/hita/ui/main/MainActivity.kt#L93-L125)。

## 5. 移动、尺寸、按下与动效成本

### UX-21
**P1 · 改宽度、字号或 IME → 文字仍在语义树上，画面已挡住。实测 CV-07。** 320 dp、字号 1.3、深色，助手欢迎卡后半段快捷问句与输入栏/底栏重叠（截图可见输入栏盖住下一行），课表顶栏截断关键状态，未显示完整课表范围名称。助手以固定 `inputBottomPadding + 64.dp` 留列表空间，不从实际输入栏高度取得；课表标题宽度最多 112 dp，配一排固定动作。基线窗口变更后仍能切换页，但不能把控件有节点当作可见、可触达。证据：[输入布局](../../app/src/main/java/cn/limpu/hita/ui/main/agent/AgentChatFragment.kt#L415-L424)、[课表布局](../../app/src/main/java/cn/limpu/hita/ui/main/MainTopBar.kt#L271-L329)、[胶囊宽度](../../app/src/main/java/cn/limpu/hita/ui/main/MainTabBar.kt#L80-L91)。宽窗口采用实际可用宽度，重要信息优先；不要求为宽屏增加无用分栏。

### UX-22
**P2 · 同类状态变化使用了互不相干的节奏，壁纸退出动画不成立。源码。** Activity helper 是 450/200 ms 淡入出，部分页面直接 `startActivity`；XML 底部弹层 300 ms，日期详情 Fragment 是 500 ms；主层抽屉按主题选 tween/弹簧，周滑和 Tab 两段各自定义。不是所有不同都要抹平：系统导航、手势、模态层、行内状态各有意义。壁纸 alpha 目标可降到 0，但一旦位图不适用就移除渲染节点，无法正常绘制退场；又不能把课表壁纸留到其他 Tab。应按变化目的定义何时瞬切、短淡化或按手势位移，而非全局加转场。[Activity 淡入](../../app/src/main/java/cn/limpu/hita/ui/base/HiltBaseActivity.kt#L149-L157)、[底部入场](../../app/src/main/res/anim/slide_in_bottom.xml)、[详情事务](../../app/src/main/java/cn/limpu/hita/ui/event/FragmentTimeInfoSheet.kt#L100-L115)、[壁纸](../../app/src/main/java/cn/limpu/hita/ui/main/MainScreen.kt#L135-L176)。

### UX-23
**P1（测量优先，原因未定）· 停留/滑动/频繁点击 → 渲染持续工作和掉帧。模拟器采样、源码风险。** `gfxinfo` 不是 Macrobenchmark；不能把一次 Debug 样本等同于用户设备。相同 AVD、1080×2400、420 dpi、浅色、倍率 1、字号 1，预先打开五页（本地仅一条待办，资讯隐藏），每风格一次 `dumpsys gfxinfo cn.limpu.hita.diag reset`；经 `adb shell` 连续点击「今日→时间表→待办→助手→更多」8 轮，点击间 `sleep 0.18`；随后 `dumpsys gfxinfo cn.limpu.hita.diag`。检查玻璃结果结束在更多。样本含 Compose/View 以及 AVD 软件渲染开销，无真实长列表、无物理刷新率对照：

| Style | 渲染帧 | Janky frames | 帧统计 P95 | 帧统计 P99 |
|---|---:|---:|---:|---:|
| 经典搭配 | 429 | 152（35.43%） | 93 ms | 200 ms |
| 玻璃艺术 | 271 | 220（81.18%） | 129 ms | 250 ms |

[玻璃捕获](../../app/src/main/java/cn/limpu/hita/ui/main/MainScreen.kt#L129-L150)、[Dock 编辑图层](../../app/src/main/java/cn/limpu/hita/ui/main/DockTabBar.kt#L538-L574) 的 `saveLayer` 和双绘制、[赛博描边](../../app/src/main/java/cn/limpu/hita/ui/design/HitaCyberSurfaces.kt#L102-L133) 的三重绘制、[深空背景](../../app/src/main/java/cn/limpu/hita/ui/design/HitaDeepSpaceSurfaces.kt#L121-L165) 的全屏循环，是待追踪路径，不是已证实的瓶颈。素材风格不应改变业务手势。Compose 支持系统倍率，但应用未找到统一的自定义动效决策；倍率 0 只实测了最终页面可用，没有证明循环装饰、View 转场与所有手势均按预期收敛。保留页滑在绘制层读位移的现有做法；不为数字跳动或高频勾选驱动列表整体测量。

### UX-23-B
**已实测（模拟器）· 页签切换与抽屉的帧时长。** 本条与上面的 `gfxinfo` 不是同一口径：下面的数据来自 `:macrobenchmark` 的 `UxJourneysBenchmark`，指标为 `FrameTimingMetric()`，通过 `BenchmarkSeedActivity` 预置 160 个课表事件、220 条待办、100 条会话消息，10 次迭代，`CompilationMode.None()`，同一 `hita_test` AVD（API 35，x86_64，1080×2400 / 420 dpi，60 Hz，软件渲染）。

| 旅程（风格） | 帧数 min/median/max | `frameDurationCpuMs` P50/P90/P95/P99 | `frameOverrunMs` P50/P90/P95/P99 |
|---|---|---|---|
| `classicTabs` | 163/181/190 | 54.8 / 68.5 / 76.0 / 94.8 | 56.7 / 78.2 / 88.9 / 122.3 |
| `glassTabs` | 81/174/181 | 57.9 / 186.2 / 203.2 / 270.3 | 62.3 / 244.7 / 290.4 / 357.4 |
| `classicDrawer` | 132/135/142 | 51.7 / 61.7 / 66.0 / 79.4 | 48.5 / 72.4 / 75.0 / 90.6 |
| `glassDrawer` | 123/132/141 | 56.6 / 74.2 / 81.9 / 108.5 | 59.8 / 88.3 / 95.2 / 126.6 |

命令：

```bash
gradlew.bat :app:assembleBenchmark
gradlew.bat :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=cn.limpu.hita.benchmark.UxJourneysBenchmark
```

`glassTabs` 的 P90 起明显高于 `classicTabs`（`frameOverrunMs` P95 290.4 ms 对 88.9 ms，约 3.3 倍），与上面 `gfxinfo` 里玻璃风格 Janky 比例更高方向一致；两次采样口径不同，只能说明“玻璃风格在模拟器上更重”这一方向，不能给出真机结论。

**限制**：Macrobenchmark 在本机构建中被显式抑制了 `EMULATOR` 检查，AVD 用软件渲染且峰值刷新率 60 Hz，`frameOverrunMs` 各旅程 P50 均为帧预算 16.7 ms 的 3 倍以上，属模拟器开销。`switchWeeks`、`scrollLongAgenda`、`scrollLongConversation` 三个旅程在准备阶段失败：点击底栏「时间表」（坐标 392,2266）后，`By.desc("时间表").selected(true)` 在 25 s 内始终不出现，重试一次与改用坐标点击均未改变结果（同一验证项已尝试三次，按上限停止重试）。失败发生在进入时间表/待办/助手页之前，应用未崩溃，日志中无 `SecurityException` 或 `FATAL`；失败原因未定位，可能是模拟器上底栏 `selected` 语义在该路径不刷新。这一项记为**未验证**，不作“无掉帧”推断。

其余未覆盖：玻璃/深空/赛博的全量风格矩阵（已测玻璃与经典的页签/抽屉，未测赛博/深空）、窄/宽窗口、字号 1.3/2.0、倍率 0/2、真机门。

## 6. 建议保留的行为与后续证据

- 保留今日按日期和范围保存展开态、重验旧事件和显示缓存失败；保留本地待办的默认截止时间、完成后可撤、脏表单/删除确认。不要用弹层替代常用路径。
- 保留课表导入校准及数据版本保护、教学隐藏可恢复、好友口令预览与版本确认、对比不渲染上周结果、重要系统许可。免登录仍可维护个人日程和待办；认证只加在确实依赖账号的操作上。
- 后续必须实测：三校区及威海本研、深圳 API/Web 变体及门户过期/MFA；有数据的长课表、重叠卡和长 Markdown；真实服务返回/超时/拒绝/迟到；旋转、进程重建、无网和权限撤回；小组件冷暖启动、日期解析；TalkBack、字大 2.0、键鼠/焦点、分屏、手势和三键导航、所有风格的深浅色和系统减少动态效果。真机帧门槛见 [规格](spec.md)，不可由上表代替。
- 可复现环境：`hita_test`，API 35，包 `cn.limpu.hita.diag`，1080×2400 / 420 dpi。Windows JDK 21 `gradlew.bat assembleDebug testDebugUnitTest --console=plain` 返回 `BUILD SUCCESSFUL`；本轮修复后重跑 `:app:testDebugUnitTest :component:testDebugUnitTest :hitauser:testDebugUnitTest :style:testDebugUnitTest :timetable-core:test :timetable-core:jar` 通过，`python scripts/check_repository.py --base-ref origin/main` 通过。通过 `adb install -r app/build/outputs/apk/debug/app-debug.apk` 安装；冷启动用 `am start -n cn.limpu.hita.diag/cn.limpu.hita.ui.main.MainActivity`，走查用 `input tap/swipe`、`uiautomator dump` 和 `screencap`。

本轮 device 侧 `:app:connectedDebugAndroidTest` 指定 `TimetableBatchUIInstrumentedTest` 时在 runner 初始化阶段失败（`ClassNotFoundException`，测试类实际存在于 `app-debug-androidTest.apk` 的 dex 中，重装 APK 后结果相同），因此设备测试**本轮未取得任何通过证据**；这条失败与图形/动效改动无直接关联，但也不能当作已通过。

## 7. 本轮修复后的复测

以下为实施同一分支后重新观察到的终态，**只覆盖免登录、单机、模拟器可复现部分**；真机、真实账号、三校区登录与性能门槛仍未验证。

| 编号 | 复测触发 → 结果 | 判定 |
|---|---|---|
| UX-01 | 清空本地课表后冷启动：今日显示「添加你的第一张课表」三入口，未建表；点「新建空课表」进入管理页空态，选择面板确认后才出现「默认课表1」。新表起止为周一 00:00:00.000 起 20 周（`1795392000000`–`1807488000000`）。返回后今日「第1周 周二」，时间表「第1周 · 默认课表1」——两页此前一边「第1周」一边「非所选学期」，现一致。 | 已修复（关键路径） |
| UX-06 | 详情页长按科目 →「已选择 1 项」→ 删除 → 弹出「删除个人课时或隐藏教务课时（1 门）？」并列出课程名、说明个人课时删除与教务课时仅隐藏；确认后 `subject`/`events` 均为 0。 | 已修复（详情入口）；管理页多选删除沿用同一确认组件 |
| UX-07 | 表单：未选时间时行内显示「请选择开始和结束时间」，保存置灰；选「第1-1节 (08:30 - 10:15)」与「11月24日」后保存可用，提交后 `subject`/`events` 各 1 条。 | 已修复（必填校验与保存回执）；失败回执仅源码复核 |
| UX-09 | 管理页导出改由 `runExport(items, requireNotNull(mode))` 驱动，口令分支不再存在默认 FULL 的调用。 | 已修复（源码）；未做真实编码往返 |
| UX-10 | 待办：新增「ux-audit-check」后计数 0→1、进入待完成；勾选后 1→0 且折叠区出现「已完成（1 项）」；展开后行内「撤销」可见并点击可回到待完成。 | 已修复（本地路径）；远程平台与 Snackbar 竞争未实测 |
| UX-20 | [下课时规则](../../app/src/main/java/cn/limpu/hita/data/work/ReminderRuleSet.kt#L157-L193) 已要求结束锚点仍在未来，`endMillis` 缺失时跳过而非用开始时间推测。 | 已修复（源码）；通知实际触发未实测 |

| UX-07（写失败） | `TimetableWriteUseCase` 在持久化未提交时返回失败回执而非抛异常，表单保留编辑并可重试；Agent 的 `addSync` 仍对未提交抛错，不静默成功。 | 已修复（源码 + 仪器测试重跑） |

**未通过项（已尝试 3 次，未声称通过）**：`HomeworkUiInstrumentedTest.dailyScreenSupportsEditingCompletionAndLegacyTasks` 在 `ACTION_SET_TEXT` 后保存的标题未在列表生效，等待超时。手动在同一 AVD 上按相同路径（点行→改文本→取消→继续编辑→保存）能落库并显示新标题，故判为测试注入方式与 Compose 文本字段语义不一致，而不是产线路径缺失。`TimetableBatchUiInstrumentedTest.failedRealWriteKeepsFormAndRetryCommitsBeforeClosingAndRoomRefresh` 已通过——其根因是新增的时长校验拒绝了夹具中的零时长事件，已移除该过度校验。

新增回归测试：`TimetableCalendarTitleTest.mondayBoundaryDropsSecondsAndMillis`（周起点必须归零秒/毫秒）与 `secondPrecisionOnTableStartStillCountsFirstWeek`（残留秒不得让表被判表外），`TimetableCalendarTitleTest.personalTimetablePastItsEndStaysOutsideTerm`（表尾之外仍非学期）。
- 参照：[Apple HIG Motion](https://developer.apple.com/design/human-interface-guidelines/motion) 的少动、可中断与反馈；[Compose 无障碍默认行为](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)、[窗口宽度分级](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes)、[系统动画时长倍率](https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale)、[预测式返回](https://developer.android.com/develop/ui/compose/system/predictive-back-setup) 和 [FrameTimingMetric](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics#frametimingmetric)。
