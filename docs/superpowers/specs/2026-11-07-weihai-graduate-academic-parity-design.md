# 威海研究生教务能力对齐设计

## 目标与工作区

用户明确要求：威海研究生登录后应能正常显示研究生课表、查询成绩，并接入参考端已实现的其他教务功能。此前“只接认证，其余暂不支持”的边界被本设计覆盖，不能继续作为完成标准。

继续使用上一轮分支 `feat/weihai-graduate-grader-todos`，基点 `f8d7d6c`。工作区为 `HITA_Android/.worktrees/weihai-graduate-completion`，不切换或修改主工作区的 `feat/optional-tabs-and-vpn-resume` 及用户未提交文件。Grader 与上一轮待办功能保留，不重新设计。不启用 TDD，不构建 APK、不推送，最终在同一功能分支提交。

## 已查证的对齐清单

|能力|参考端事实|Android 交付结果|
|---|---|---|
|学期|iOS 当前学期及完整学期列表|统一 TermItem，当前接口覆盖陈旧当前标记，支持当前及历史学期选择|
|个人课表|iOS 总课表、已选课元数据、作息、校历完整链路|登录后沿现有自动导入与课表展示链路工作，手动导入和刷新也可用|
|成绩|iOS 研究生分页成绩查询|现有成绩页显示真实公布文本、数值、学分及课程信息，不以隐藏最终成绩替换公布成绩|
|考试|iOS 研究生分页考试查询|现有考试页显示日期、时间、地点、类型；未公布钟点不伪造，不能误导入为已知时间日程|
|教学楼|iOS 研究生教学楼列表|空教室页使用独立研究生楼宇接口|
|空教室|iOS 左侧完整房间分页与右侧稀疏占用|现有查询和详情正确显示周次、节次、排课/考试/借用及组合占用|
|只读选课辅助|iOS 已选、动态类别、候选、筛选及本地预览|Android 对研究生真正可达，按服务端动态类别查询，支持已选和本地预览/冲突与跟课能力|
|全校研究生课表|iOS 查询、筛选、分页及同学期作息校历|Android 目录入口可查询研究生全校课表，不强制深圳登录或本科培养层次|

证据来源为 `HITA-Aura/Packages/HITAAcademicKernel/Sources/HITAAcademicConnectors/` 下 `WeihaiGraduateWebVPNClient.swift`、`WeihaiGraduateWebVPNTimetable.swift`、`WeihaiGraduateWebVPNRecords.swift`、`WeihaiGraduateCoursePlanning.swift`、`WeihaiGraduateSchoolTimetable.swift`，以及对应 `WeihaiGraduateTimetableTests`、`WeihaiGraduateRecordsTests`、`WeihaiGraduateCatalogPlanningTests` 的合成样例。产品装配见 `Shared/Academic/AcademicKernelAssembly.swift`、`HITAAura/AcademicKernel/AuraAppAssembly.swift` 与两个课程网关。

Harmony 威海入口仍固定本科，成绩和考试对非深圳研究生明确拒绝，不能将其深圳实现当作已验证威海研究生协议。iOS 文档已有历史只读核验记录，但不是本轮实际验证结果。

## 非目标与安全边界

参考 iOS 研究生服务未接入远端选课提交、退课、购物车写入、培养要求或毕业进度，本轮不推造这些接口。候选列表中的选择窗口、selectionRequestID 或页面写入字段不代表提交授权。研究生选课辅助仅查询学校数据及改变本地预览/跟课，必须阻止研究生数据进入现有深圳自动提交任务。

本科、本部、深圳已有功能保持原行为；不重构无关模块，不修改签名和发布配置。参考仓库只读。不会把上一轮只授权用于 Grader 的账号密码拿去尝试教务认证，也不会使用文档或日志中可能存在的凭据。没有真实威海研究生授权会话时，真实服务器与设备验收明确保留给用户，不将合成样例称为现场验证。

## 统一数据与请求约束

沿用 EASService、Repository、有限协程查询、既有本地课表写入和缓存/会话代次守卫；不绕过 Repository 直接写 Room。补全现有研究生数据源全部业务方法，移除已被真实实现替代的 unsupported 返回及过时文档。

固定研究生代理根：`https://webvpn.hitwh.edu.cn/https/77726476706e69737468656265737421e9fd529b2b7e60596a1f81e29d51367b6311`。

公共请求使用 `RoleCode: 02`、`X-Requested-With: XMLHttpRequest` 及对应研究生 Referer。只携带研究生网关会话，绝不回退到本科网站。HTTP 3xx/401/403 或实际可见认证页面表示会话失效；5xx、传输错误、业务失败、无效数据不能冒充未登录。认证分类移除 script/style/template 后再检查 HTML，避免休眠重登模板误报。

响应 Cookie 更新沿既有 source 回调和 Repository 会话代次检查持久化，退出/切换账号后的迟到响应不得恢复旧会话。所有网络与阻塞解析在 IO 执行；Once 请求遵守取消传播、请求前后检查和单一终态约定。不得吞掉取消并返回空结果。

## 个人课表完整链路

- `POST /component/querydangqianxnxq` 与 `/component/queryxnxqdata` 提供权威当前学期及完整选项，归一化为 `yearCode-termCode`。
- `POST /xszykb/querykbsffb` 区分可用、未发布、未评教；未知返回不是成功空课表。`POST /xszykb/queryxszykbzong` 获取整个学期，不拿某一周替代总课表。
- `GET /Xsxk/query/2` 动态解析四个只读配置变量；随后查询模块当前学期和指定学期已选课程。历史查询必须区分请求学期 `p_x*` 与模块当前学期 `p_dq*`，不复制 SQL、身份或 mutation 字段。
- 已选响应 `kbjclist` 按连续无重复 `XJ` 提供真实小节作息，ISO 时钟转为北京时间，不硬编码本科作息。响应学期必须匹配请求。
- 总课表星期取 `KEY` 的 xq 部分，真实节次取 `KSJC/JSJC`，不能把网格 jc 或颜色 XB 当节次。34 位周次位图保留离散周次；代码、教师、地点优先显式字段及已选元数据。Android `CourseItem.last` 必须是持续小节数。
- `POST /Xiaoli/queryMonthList` 的同学期 `xlList` 推导第一周周一，所有有效行必须一致；不使用系统当前日期、月份首日或别的学期日期补造开学日。
- 登录、刷新、手动导入均使用同一个数据源和现有写入链路；尊重用户已有自动导入设置，不擅自开启其关闭的选项。

## 成绩、考试与空教室

成绩请求 `/cjgl/grcjcx/grcjcx` 使用研究生 JSON body（`xn/xq/kcmc/cxbj/pylx/current/pageSize/sffx`），不发送深圳的 `xscjlb`。显示 `xscj`、数值来自公布数值字段 `xszscj`，不使用 `zzzscj`。没有研究生 GPA 汇总协议时不伪造 summary；现有 rows-only 成绩契约仍然有效。

考试请求 `/kscxtj/queryXsksByxhList` 使用 `ppylx/pxn/pxq/pkssjdm/pageNum/pageSize`，拒绝混入本科数据。日期时间未公布时保留未知语义；只有第几节而无钟点时不能生成假的钟点日程。

教学楼使用 `/pksd/queryjxlList`；空教室分别请求 `/cdkb/querycdzyleftzhou` 和 `/cdkb/querycdzyrightzhou`。这里 `zc` 是十进制周次，不是深圳位图。左右过滤一致，房间收齐后再合并成功解码的稀疏占用。排、考、借与组合占用不能丢；失败标记不能变成空闲。

分页校验总数稳定、页码与服务端页大小一致、无重复或截断；只有完整收齐才发布成功结果。权威空页与结构缺失必须区分。测试覆盖第二页和错误中间页，而不只读取第一页。

## 选课辅助与全校课表的端到端接入

不能只补底层函数：现有目录 Activity/ViewModel 的学期通道、候选与全校请求、动态类别、重新登录校区、已选列表、作息校历、本地预览与跟课都要按实际会话分流。

研究生候选使用 `/Xsxk/queryKxrw`，动态类别来自 `xkgzszList`，不能复用深圳静态课程池。全校目录使用 `/Xsxktz/queryRwxxcxList`，固定研究生培养范围，保留关键词、校区及分页；筛选接口 `/Xsxktz/queryXlcxList`、校区与开课院系接口按参考契约接入。课程排课来自 `pkjgmx` 或结构化课次，不把选课操作时间 `xksj` 当上课时间。

界面只显示当前研究生实际支持的操作，不展示可点但注定失败的深圳提交、附件、培养要求或其他未接入操作。已有深圳入口及操作不因此被移除。

## 验收与停止条件

本轮单独最多五轮“修改—验证”，不沿用上一轮已结束目标的轮次计数。验证包括：

1. 功能分支独立工作区 Kotlin/Java 编译及现有 JVM 测试，所有命令不得生成 APK。
2. 用参考合成样例驱动实际生产解析器与本地 HTTP 请求链，验证方法、路径、头、body、分页、学期和数据映射；不以复制实现或仅检查源码文字代替行为验证。
3. 覆盖课表当前/历史学期、动态配置、作息时区、离散周次、错误/未发布状态、取消和账号代次；验证数据可被实际导入日期解析及课程预览消费者使用。
4. 覆盖成绩公布文本/数值、考试未知钟点、教学楼/空教室组合占用、分页截断、会话失效与服务端失败分类。
5. 覆盖研究生目录与候选的可达请求、动态类别、培养范围、已选、预览与跟课；研究生不能创建远端选课任务，深圳原行为仍通过相关既有测试。
6. 更新实际能力与验证记录，提交本轮变更到原功能分支；不构建 APK、不推送，然后停止等待用户安排。

遇到参考端未确认的关键协议、必须人工提供的真实研究生会话/验证码、需要越界或破坏性操作时暂停询问；达到五轮上限则报告证据与剩余阻塞。Android 设备界面和真实威海研究生账号验收不作虚假完成声明。

## 实施验证记录（2026-11-07 第二轮）

已落地的研究生数据层：

- `data/source/web/eas/graduate/GraduateHttpTransport.kt`：固定研究生代理根、`RoleCode:02`、`XMLHttpRequest`、研究生 Referer、表单/JSON/GET 发送、响应 Cookie 回写、3xx/401/403 与可见登录页判失效、5xx 与传输失败判上游不可用、休眠重登模板剔除。
- `GraduateTimetableProtocol.kt`：权威当前学期、发布状态（1/0/2）、页面运行配置、模块当前学期、骨架作息与时区换算、34 位周次位图、`KEY` 星期 + `KSJC/JSJC` 真实节次、`SKSJ` 教师/地点、已选元数据关联、校历推导开学日、动态类别、候选项与全校课表分页、筛选。
- `GraduateRecordsProtocol.kt`：分页收集器（总数稳定、中间页完整、重复拒绝）、成绩公布文本与数值、考试未知钟点、教学楼、空教室占用组合与失败标记、纯 JVM 占用视图。
- `WeihaiGraduateEASWebSource.kt`：实现 `EASService` 全部方法与 `EASGraduateCatalogService`，登录校验改为 POST `/user/me`，所有查询经 `GraduateJsoupTransport` 走研究生会话。
- `GraduateCourseCatalogRepository.kt`：会话内挂起查询，卡片来源固定 `SCHOOL`，使研究生候选无法进入深圳提交路径。
- 成绩与考试学期列表对研究生跳过“最近 4 年”裁剪；研究生课表作息来自 `kbjclist`，不被本科 SP 偏好覆盖；全校教学班关注放开到威海研究生。

已实际运行（Eclipse Adoptium JDK 21.0.10，Gradle wrapper 8.11.1）：

```text
:app:compileDebugKotlin
:app:compileDebugJavaWithJavac
:app:testDebugUnitTest :component:testDebugUnitTest :hitauser:testDebugUnitTest
:timetable-core:test :timetable-core:verifyJvmDependencies
:style:verifyReleaseResources
```

结果：主源码编译通过；四个模块 JVM 测试共 983 项通过，1 项既有跳过；资源校验与纯 JVM 依赖校验通过。研究生协议回归 20 项（含课表 10、记录 7、课表编码 3）全部通过，覆盖学期权威性、发布状态、运行配置、作息连续性与时区、周次位图、真实节次映射、校历一致性、已选学期校验、`pkjgmx` 解析、分页完整性与培养层次、成绩公布值、考试未知钟点、占用组合与失败标记、登录页与瞬时故障分类、本科与研究生课表编码隔离。

验证过程中修正的自身缺陷：身份探针由 GET 改为与 iOS 一致的 POST；非数值成绩不再以 0 参与估算而置为未知；研究生作息不再被本科偏好覆盖；威海本科与研究生共用课表编码导致互相覆盖，现研究生使用独立前缀；研究生目录首轮动态类别为空、账号切换残留、卡片缺少“加入预览”、附件误走深圳接口，均已修正。

集成范围：研究生课表导入使用独立超时预算与教务 `kbjclist` 作息，导入失败不污染既有课表；目录页按实际会话分为研究生只读模式（动态类别、可选任务、全校课表、本地预览与跟课），不展示深圳远端提交、附件、培养方案或历史失败率，账号切换会清空研究生状态。

尚未完成：真实威海研究生账号、Android 设备界面与应用内 WebView 全流程未验证；研究生远端选课提交、退课、培养要求、毕业进度未接入（参考端同样未接入），不能据此宣布这些能力可用。


