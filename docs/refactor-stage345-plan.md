# 阶段 3、4、5 执行与验收台账

初始基线：2026-10-26，`origin/main = fd17e5e773b1ad6f16cd9a267227c595d34c73fa`（#68）。后续 #70 已合并为 `b8a4cea55faab714baccceb1586ecc652cb3f46f`，#71 构建改造已合并为 `f4e809d1458c52cd5f8b43f317f8f643def9f306`；当前远端 main 已包含 #83，合并提交为 `ecdd736cc027855b1cf461d72d9e28f29b110e64`。本文件是当前长期目标的完整完成条件，每个实现批次更新实际证据。项目进度与历史 PR 见 [交接文档](refactor-handoff.md)。

## 1. 授权、范围与完成规则

用户明确授权：补齐交接之后，Agent 自主实现、提交、推送 origin、创建 PR、审核和合并，直到阶段 3、4、5 完成，不再逐项请求确认。这覆盖为本目标必要的分支同步与隔离 worktree；不包含发 release、推 fork、更改应用发布 versionName/versionCode 或外部账号操作。实现缓存隔离等功能所必需的 AppDatabase.VERSION、迁移与导出 schema 变更在本目标范围内。

以下台账全部条目与三份详细审计共同定义验收，不能把后续任务移出范围来宣称目标完成：

- [阶段 3：能力、调用者与行为边界](refactor-stage3-audit.md)
- [阶段 4：四个页面的职责与生命周期](refactor-stage4-audit.md)
- [阶段 5：构建、真实子模块测试与纯 Kotlin 模块](refactor-stage5-audit.md)

审计中的源码行号、类大小、原调用形式是上述基线的定位信息；实现后以实际生产调用者、依赖图与测试证据重新验收。新增接口、文件变短、扫描器通过或几个 helper 单测均不能独立证明完成。

每批流程：明确契约与边界 → 原行为测试 → 实现 → 独立审查 → 适当的本地构建与真实测试 → commit/push → PR CI 与原始报告 → 合并 → fetch 核验 main → 更新台账。缺陷先在旧实现上定向实测失败，再保留业务断言修复；最终交付统一执行完整 PR CI，只有确需确认 CI 环境差异时才另跑失败基线；纯兼容测试不制造失败。旧测试身份保持，必要 harness/迁移位置变化独立审查。

完成状态使用“待做 / 实施中 / 本地通过 / PR 待 CI / 已合并且验证 / 阻塞（具体外部条件）”。只有最后的逐项审计通过才完成长期目标。不能把等待某个 PR 或临时网络错误视为全目标阻塞。

### 2026-10-26 工作流程调整

按用户最新要求保持全部验收范围与质量，按完整功能链组织 PR：接口、实现、生产调用方和必要测试同批交付；表中的 ID 是验收项，不要求一项一个 PR。5.V/M/R 合为构建改造批次，3.Q2 覆盖缓存到全部十个消费端，兼容基线与缺陷红绿可保留为同一 PR 内的提交。

开发时先运行相关编译与针对性测试；交付前执行仓库要求的完整检查。已有检查按源码、构建输入和验证范围复用，没有相关改动或新疑点不重复执行；最终 PR CI 仍覆盖实际提交。保留原用例，新增测试针对行为、兼容、取消、事务及迁移风险，不用复制实现的断言增加数量。

独立模块在隔离 worktree 并行实现与审查，根 Agent 协调共享文件、重型构建和模拟器。进度集中在本台账更新，交接文档只保留概览并引用已有证据，不逐次复制日志。既有自主提交、origin 推送、审核与合并授权不变；代码完成、本地验证、CI、合并和真实设备/账号验证分别记录。

## 2. 已有交付与验证基线

| 条目 | 已有交付 | 证据 |
|---|---|---|
| 基础安全、分层、写入与导入 | 阶段 0、1、2 完成 | 交接文档记录 #47–#64；原迁移、原子性、分享与写入测试保留 |
| 教学楼查询 | #65：三校区 suspend + 页面 Job | PR CI 227 项设备全通过 |
| 考试查询 | #66：三校区请求核心 + 页面生命周期 | 三项缺陷先红后绿；PR CI 298 项设备全通过 |
| 空教室远端 | #67：单次 suspend、捕获参数与取消 | PR CI 378 项设备全通过 |
| 空教室 Repository/页面 | #68：有序 Flow、缓存事务、选择/默认周/重试/取消 | 五项缺陷先红后绿；PR CI 481 项设备全通过 |
| 学期远端能力 | #70：三校区单次 suspend、普通/研究生及深圳 Web 共用核心 | head `7275174` 的 CI 40299381154 设备 607 项全通过；merge `b8a4cea` |

初始设备基线为 481 个 testcase；#70 head 的本地与 CI 已验证完整 607 项全部通过，后续以这份身份集合继续核对。本地 JVM 795 个 testcase（794 通过、1 个既有可选联网跳过）保持；CI 未上传 JVM XML，不能把本地精确计数说成 CI XML 结果。后续新增/移动测试必须按完整身份集合核对，PR head 的结果不能冒充合并 main 或后续构建重构整合后的另一次测试。

## 3. 阶段 3：请求、装配、聊天与工具执行

所有未完成条目初始状态均为“待做”。适合的一次请求使用 suspend，有限渐进结果使用 Flow；Room、会话和登录状态的持续观察保留可观察语义。

| ID | 交付范围 | 必须成立的验收条件 | 状态 / PR / 证据 |
|---|---|---|---|
| 3.Q1 | 三校区学期远端能力 | 普通与深圳 Web 学期入口共享原请求/解析核心；覆盖本部本科/研究生及威海合并/过滤；请求与取消契约实测 | 已合并且验证：[#70](https://github.com/HIT-A/HITA_Android/pull/70)，merge `b8a4cea`；head `7275174` 的 CI 40299381154 设备 607 项全通过，独立签核完成 |
| 3.Q2 | 学期缓存与全部生产消费端 | cache→remote Flow、原子替换与 owner/session 捕获；导入/成绩/学分/年级/考试/空教室页面与后台调用接入；保留中间状态与错误策略；同 key 跨调用者请求代次由 Repository 协调，覆盖手工/后台/AI 并发，旧响应不覆盖较新结果 | 已合并且验证：[#75](https://github.com/HIT-A/HITA_Android/pull/75)，merge `463a598`；head `d7aef7c` 的 CI 40320879654 JVM 827 通过 + 1 旧跳过、设备 726 全通过；原始报告摘要与全部身份、60 文件 hash 在合并前核验，合并树一致 |
| 3.Q3 | 开学日、作息、课程元信息、课表远端 | 三校区 suspend 与真实解析 fixture；同步子请求受父 Job 管理；保留用户校准、作息、实验课、合并与回退；页面选择/手改优先级正确；学期/教学楼/默认周/作息按同一 owner 与选择关联，切换中不混用旧元数据 | 实施中：与 3.I1 合批，三校区四项 Once 远端及所有导入消费端已接通并独审，生产/测试编译通过；本部跨账户缓存缺陷已在原实现实测，部分旧兼容夹具修正后待补验；当前运行验证待完成 |
| 3.Q4 | 成绩单次与成绩汇总渐进结果 | 单次与 Flow 分开；items 先到、summary 后补、部分鉴权失败和缓存语义保留；所有成绩页面/通知消费接通 | 已合并且验证：[#83](https://github.com/HIT-A/HITA_Android/pull/83)，merge `ecdd736`；EASService、三校区委托/深圳 API-Web Flow、Repository cache→remote/ticket/session 保护、ScoreInquiryViewModel 已接通；PR CI 40509648499 设备 851 项全通过，JVM 938 项中 937 通过、1 个既有可选联网跳过，测试身份无重复 |
| 3.Q5 | 深圳目录、选课池、已选课程、规划与推荐 | 单次/串行分页支持取消；保留参数、路由、Cookie、排序、推荐策略；页面可替换/取消请求，写入选课不增加自动重试 | 待做 |
| 3.Q6 | 深圳附件列表、年级分析、历史教师统计、培养方案与学分 | 全部公开查询及调用者迁移；学分 summary→分类→组→课程保持渐进 Flow；含 includeDetails/includeCourseRecords 契约 | 待做 |
| 3.Q7 | 登录、校验、资料补全 | 有限操作用协程；保留三校区认证、凭据保存、补全回退、authEpoch；新登录/注销后旧响应不写 token，取消不变业务失败 | 待做 |
| 3.I1 | 手工/自动教务导入与快照操作 | Repository 不接 UI MediatorLiveData；没有共享请求归属或 latch/observeForever 等待；owner、锁、提交边界、去重、快照采纳/恢复与刷新实测 | 实施中：与 3.Q3 完整导入链合批，生产与测试编译通过；原实现导入 8 项为 4 个准确缺陷失败、4 个兼容通过，考试尾批 2 个准确缺陷失败；新 44 项设备用例待执行，R2 恢复协议独立推进 |
| 3.I2 | 成绩通知后台任务 | 可取消执行、共享注入 Repository；首次基线/跨学期去重/部分失败/取消不污染基线，旧 Worker 恢复可用 | 已合并且验证：与 3.Q4 同批 [#83](https://github.com/HIT-A/HITA_Android/pull/83)，merge `ecdd736`；Worker 改用可取消单次成绩请求并共享 Repository，保留构造器/WorkSpec/通知契约；CI 40509648499 设备 851 项全通过，JVM 938 项中 937 通过、1 个既有可选联网跳过 |
| 3.D1 | EASRepository 内部依赖注入 | 服务选择器、DAO/事务、缓存/快照等明确注入；主类不 new WebSource 或直接取全局 DB；测试可用替身而不建真实网络源 | 已合并：PR #82 / merge `1477dcea`；`EASRepository` 接收数据库、DAO、快照、Gson、考试导入和课表分组能力，服务由 `EasRepositoryServiceFactories` 装配；`EasRepositoryWiringInstrumentedTest` 与 legacy seam 已通过。CI 40497758753 设备 XML 846 项全部通过；本地编译、Debug/AndroidTest APK、仓库检查通过。 |
| 3.D2 | 会话与注入调用者收尾 | token callback 循环由明确 session coordinator/factory 处理；EventItemFragment/Worker 不手工创建 Repository/锁；通用能力无具体源类型判断 | 已合并：PR #82 / merge `1477dcea`；`EventItemFragment` 改为 Hilt 注入，`ScoreReminderWorker` 通过 Hilt EntryPoint 获取生产 Repository/Store 并保留测试替身，导入链显式接收分组/考试依赖，成绩摘要改用能力接口而非具体源类型判断。CI 40497758753 的 `ScoreReminderSessionTest` 与完整设备套件通过；真实账号/真机仍未验证。 |
| 3.A1 | Typed AgentToolExecutor 结果与取消 | success/failure/timeout/cancelled 可区分；同步异常、双/迟到回调、重试、clear/dispose 只一次终态；幂等约束和添加活动零重试保护实测 | 已合并且验证：[#81](https://github.com/HIT-A/HITA_Android/pull/81)，merge `de4306a`；CI 40460133789 两个 job 成功，typed executor/engine/session、写入提交边界和 32 项设备用例已纳入验证；真实账号/物理真机未验证 |
| 3.A2 | ReAct 工具与 AI 空教室完整链 | suspend 分发直接连接新能力；学期→本地周→教学楼→逐楼查询及 typed 工具桥无 latch；取消/超时传播，analytics 上下文与原输出保留 | 实施完成，本地通过，待 PR CI：`GetTimetableTool`、`SearchTimetableTool`、`AddActivityTool` 和 `SearchEmptyClassroomTool` 已接入 `SuspendingReActTool`；typed bridge 改为可取消挂起适配器，空教室本地周改为一次性 Room 读取；7 项 adapter JVM、完整 app JVM、Debug/AndroidTest Kotlin 编译及 APK 构建通过，设备/CI 尚未运行 |
| 3.C1 | ChatSessionRepository 与页面协调 | DB 与会话写入移出 VM；创建/消息/删除事务及顺序、切会话归属/恢复/迟到结果实测；无未管理 Executor，持久化与 UI Job 责任明确 | 已合并且验证：与 C2 同批 [#79](https://github.com/HIT-A/HITA_Android/pull/79)，merge `1776ee4`；CI 40468971225 两个 job 成功，会话生命周期/迟到结果设备用例已通过；真实账号/物理真机未验证 |
| 3.C2 | 独立附件处理器与资源边界 | Android URI 适配、缓存/元信息、各格式解析分离；实际字节/展开资源上限、异常/取消关闭清理、截断与图片视频路由保留；PDF CMap 回归通过 | 已合并且验证：与 C1 同批 [#79](https://github.com/HIT-A/HITA_Android/pull/79)，merge `1776ee4`；CI 40468971225 两个 job 成功，附件格式/资源边界、URI 适配和 PDF 回归已通过；真实账号/物理真机未验证 |
| 3.F | 阶段 3 完成审计 | 按 EASService + 深圳全部公开能力及实际调用者逐项核实；残留 LiveData/thread/latch 有正当用途和证据；全链迁移而非仅新增 API | 待做 |

## 4. 阶段 4：页面职责与平台边界

四个入口全部需要完成。Android 宿主只负责生命周期、平台启动和绑定；ViewModel/用例拥有业务状态及请求；展示组件接收 state/actions。提取后不能产生持有全部旧字段和依赖的新 god controller。

| ID | 交付范围 | 必须成立的验收条件 | 状态 / PR / 证据 |
|---|---|---|---|
| 4.M1 | Main 状态、启动、公告与会话转变 | 登录/换账号/节流/首次博客同步、公告排序与已读时机可测；effect 只交付当前宿主，scope 副作用仍统一 | 本地定向通过：状态/启动/effect/公告/更新/导入归属完整接线，独审发现已修；初轮 28 设备 24 通过、4 个测试夹具失败，保留断言修正后共享夹具 7 项全通过；整合 #75 的 Debug/测试 APK 与 app JVM 810 通过 + 1 旧跳过；最终 CI 待验 |
| 4.M2 | Main 平台资源、Fragment 托管与 Compose | Fragment 恢复/切 tab/动态标签不重复或丢失；Compose 不强转 Activity；壁纸 IO/加载归属与释放实测；原 glass 反射契约保留 | 本地定向通过：壁纸旧文件链 3 兼容通过 / 7 缺陷失败；真实平台旧 seam 3 兼容通过 / 6 缺陷失败，当前对应测试通过；154 JVM 初轮 153 通过、1 个异常副本断言修正后单项通过，整合 #75 后完整 app JVM 811（810 通过 + 1 旧跳过），51 项新增全部保留通过；与 4.M1 合批 |
| 4.T1 | Timetable 状态、编辑用例与 view 生命周期 | 批次选择受 Job/代次管理；目标课表、只读、THIS/SLOT/ALL 规则实测；旧 view 不交付；写入继续统一入口；VM 不反向引用 Fragment 常量 | 已合并且验证：[#77](https://github.com/HIT-A/HITA_Android/pull/77)，merge `59b6cc1`；`TimetableBatchCoordinator`、宿主 Fragment 与状态/生命周期链已接入；CI 40480112502 实际设备 XML 845 项全通过，0 失败/错误/跳过/重复。 |
| 4.T2 | 复用周组件与布局策略 | 朋友课表/规划预览真实接入；几何/命中、重叠、时区、晚课、手势/动画有覆盖；组件 state/actions 与领域算法分离 | 已合并且验证：与 4.T1 同批由 [#77](https://github.com/HIT-A/HITA_Android/pull/77) 交付；`TimetableScreen`、周组件、几何和预览消费链已接入；CI 40480112502 实际设备 XML 845 项全通过，未进行真实账号/真机校园环境验证。 |
| 4.W1 | Web 登录校区会话与 HTTP | 显式事件/状态/一次结果；Cookie 捕获和网络适配分开；三校区/研究生/电子实验/静默/超时回退实测；敏感 Cookie 不写日志 | 待做 |
| 4.W2 | WebView 浏览器、脚本、MFA、凭据桥与展示 | 生命周期拥有浏览器与回调；origin/main-frame 安全约束、成功才保存凭据、取消清理；实际本地 WebView fixture 验证桥接/导航/销毁，不启用原禁用功能 | 待做 |
| 4.S1 | Subject 资源请求与课程归属 | 课程不可变 key、agent→fallback、每卡目录栈/请求代次；迟到结果不能回填其他课程；clear 取消；保存/删除仍统一 | 已合并且验证：[#73](https://github.com/HIT-A/HITA_Android/pull/73)，merge `d4806f7`；5 项归属先红后绿；head `127e945` 的 CI 40314264518 JVM 827 通过 + 1 既有跳过、设备 647 全通过 |
| 4.S2 | Subject Markdown、打开策略与展示 | formatter 输入显式、纯策略可测；Markwon/下载/浏览器为平台适配；相对链接/来源/编码/截段与实际卡片操作契约保持 | 已合并且验证：#73；8 项兼容基线保持，真实 Markwon/启动器/Activity 测试通过；完整 CI 与合并树核验通过 |
| 4.F | 阶段 4 完成审计 | 四个实际宿主与抽取类逐项审核；无直接 HTTP/文件 IO/散乱请求 map；运行真实 host/组件/WebView 生命周期用例和完整 app 回归 | 待做 |

## 5. 阶段 5：构建、测试与模块

| ID | 交付范围 | 必须成立的验收条件 | 状态 / PR / 证据 |
|---|---|---|---|
| 5.V | 版本目录统一 | 插件、库及测试依赖真正引用 catalog；BOM/必要历史版本差异显式；前后 resolved classpath 对照，无夹带依赖升级 | 已合并且验证：[#71](https://github.com/HIT-A/HITA_Android/pull/71)，merge `f4e809d`；head `ae70440` 的 CI 40305741411 两 job 成功、设备 607 全通过 |
| 5.M | Maven Local 显式开关 | 默认所有解析域不使用 mavenLocal；显式属性启用；两种模式实际配置验证，干净 CI 可构建 | 已合并且验证：#71；三模式及冷缓存 111 配置对照通过，476 外部制品冷下载；最终 CI 默认构建通过 |
| 5.R | style Release 资源校验恢复 | verifyReleaseResources enabled 且实际执行成功；修真实 AAPT 归属错误；CI 持久执行，无 skip/ignore 替代 | 已合并且验证：#71；本地资源任务实际执行且新 AAR 等价，最终 CI 日志确认 verifyReleaseResources/assembleRelease 成功，无 skip |
| 5.T | component/hitauser/style 真实测试与 CI | 覆盖公共数据/网络/偏好/Room/主题关键行为；每模块实际 XML、CI 全模块覆盖；解决真实最低 API 依赖冲突而非 overrideLibrary | 已合并且验证：[#72](https://github.com/HIT-A/HITA_Android/pull/72)，merge `d2742e3`；head `5ae5bd7` 的 CI 40311018658 JVM 805 通过 + 1 既有跳过、设备 633 全通过；实际 runtime 保持 |
| 5.K | 纯 Kotlin 领域模块 | 协议/比较真实移入 JVM-only 模块并产 jar，app 消费该 artifact；没有 Android/Room/app 回边；实体 adapter 留 app；原测试移动保持身份，独立模块测试和 app集成都通过 | 已合并且验证：[#74](https://github.com/HIT-A/HITA_Android/pull/74)，merge `51819ca`；head `7e33899` 的 CI 40317563827 JVM 827 通过 + 1 旧跳过、设备 647 全通过；独立 JAR/依赖图与 Release R8 已核实 |
| 5.S | legacy sync 清理 | 删除未使用目录、悬空 keep/说明，确认无有效生产引用丢失；不删除用户设备旧数据库；Debug/R8 与仓库检查通过 | 已合并且验证：#74；删除 15 个未编译旧文件与 7 条过期 keep，独审、Debug/R8 和完整 CI 通过；无设备清库行为 |
| 5.F | 阶段 5 完成审计 | 真实 resolved 依赖、任务输出、各模块 XML、jar 内容与消费关系；不能用新增扫描器或空模块替代交付 | 待做 |

## 6. 与上述阶段相关的遗留项

这些是在开始当前目标前已明确列出的剩余问题，纳入本目标完成审计；实现时与正常兼容迁移分开，保留准确缺陷基线。

| ID | 问题 | 必须成立的验收条件 | 状态 / PR / 证据 |
|---|---|---|---|
| R1 | 空教室缓存缺少账户/校区维度与跨调用者顺序 | 明确 owner/campus/key 与历史匿名缓存处置；迁移保留其他数据，跨账号读取/迟到写入/注销有覆盖；同 key 的手工/页面/AI 并发采用共享请求代次，旧响应不覆盖较新缓存；Room schema 链完整 | 待做 |
| R2 | 课表事务内清文件的恢复风险 | 提交失败不会丢待处理变更；DB 与文件成功/失败/进程恢复路径有可恢复协议及真实故障注入 | 实施中：DB outbox 与文件幂等发布协议，独立 worktree 实现；尚无验证结果 |
| R3 | 学分 summary 404 后详情丢失 | 原实现失败基线；后续渐进解析保留允许缺失 summary 的规则及有效详情，认证/其他错误不误放行 | 待做 |
| R4 | 三条 Worker→UI 平台依赖 | 通知/widget 窄接口 + 应用层装配，保留任务类名/Intent/channel/通知去重；真实 Worker/平台用例通过后精确 allowlist 清零，不能搬目录躲检查 | 待做 |

## 7. 每批证据与全目标完成审计

- 开始时记录 base SHA、工作分支/隔离目录、原测试身份和源码哈希、范围及兼容/修改行为；主 Agent 统一运行 Gradle，避免共享模拟器或输出目录竞争。
- 本地按改动运行 JVM、Debug、AndroidTest APK 与设备回归；使用 JDK21。阶段 5 子模块最低 API 问题修复前只运行 `:app:assembleDebugAndroidTest`，不以根任务失败掩盖当前批次或删除子模块。
- 仓库与分层检查：`python3 scripts/check_repository.py --base-ref origin/main`、`./project_health_check.sh`、`./code_consistency_check.sh`、`git diff --check`；修改检查器时实际运行检查器测试。
- 实际 JUnit XML 核对 class#method 完整集合、失败/错误/跳过、重复/缺漏与 suite 总数；移动纯模块测试按聚合身份对照，不能误称新增覆盖。原有可选联网跳过单独列出，禁止新增无解释 skip。
- 每批独立 review 检查生产真实调用链及测试 seam；PR CI 针对实际 head，下载报告并核验 artifact 身份/摘要。修复只有真实先红后绿才能把缺陷关闭。
- 修改库依赖、模块与 release 包装时运行所涉资源/R8/独立模块测试；设备测试使用合成响应不能代替真实校园账号或真实 WebVPN 联调，报告边界必须准确。
- 合并后 fetch 并核验 main 包含预期提交/文件树。文档跟进该批实际交付；不把“已实现未运行”“本地绿但 CI 未完成”“PR 已开未合并”计为完成。
- 最终逐项复核 3.Q1–3.F、4.M1–4.F、5.V–5.F、R1–R4 与详细审计，提供每项合并 PR 和实现/运行证据。存在任何缺失或不确定项时保持目标 active，继续工作。

## 8. 执行记录

| 批次 | 当前动作 | 证据 |
|---|---|---|
| 交接与范围补齐 | #69 已合并且验证，merge `f48cc4b` | head `d89c58d`；CI 40292512701 两个 job 成功，实际设备 XML 原 481 项全通过 |
| 3.Q1 旧入口合同 | 基线 `70a0210` 随 #70 合并；新增 67 项旧入口契约，本地完整 548 项通过 | `/tmp/hita-stage345-skdpckp3/terms/`；原 209 份及基线后 213 份测试/资源冻结，JVM 原 795 身份保持 |
| 3.Q1 Once 与取消 | #70 已合并且验证，merge `b8a4cea55faab714baccceb1586ecc652cb3f46f`；五个生产文件共用请求核心 | head `72751747ba5acccfb8abf4240d3b6f1700445e66`，CI 40299381154 两 job 成功、设备 607 全通过且独立签核；本地 JVM 794 通过 + 1 既有跳过，Debug/测试 APK 通过；首轮测试签名错误与最小修正证据保留 |
| 5.V/M 配置与冷缓存 | 旧 `f48cc4b` 基线三种模式和冷缓存默认各 111 配置严格对照通过；已整合 `b8a4cea`，最终交付见下方 #71 记录 | `/tmp/hita-stage345-skdpckp3/build/`；`*-comparison-v3.json`、冷缓存准备与 `cold-*` 日志；476 外部制品全在新 home 下载，冷 Debug 114 任务全执行 |
| 5.V/M 旧基线实际构建 | JVM 794 通过 + 1 原跳过，Debug/测试 APK/三库 Release 成功，设备原 481 身份全通过 | `catalog-default-build.log`、`catalog-default-jvm-audit.json`、`catalog-default-device-audit.json`；新 6 AAR+APK 与原基线字节一致，见 `catalog-default-assembled-comparison.json`；不代表整合 #70 后 607 项已通过 |
| 5.V/M 整合 #70 | Debug/测试 APK 与 JVM 构建通过；新原始 XML 为 JVM 795 身份保持、设备 607 身份全通过 | `catalog-q1-integrated-build.log`、`catalog-q1-integrated-{jvm,device}-results/` 与对应 `*-audit.json`；这是 R 合入前的 app 回归证据，最终合并批次由 PR CI 验证 |
| 5.R 合入构建批次 | `verifyReleaseResources` 与 Release AAR 强制实际执行成功，未修改任何资源；CI 增加无条件校验步骤 | `combined-style-release-validation.log` 与 `combined-style-release-archive-audit.json`；新 AAR 与原字节一致，未以删除校验、跳过任务或调整主题规避问题 |
| 5.V/M/R 最终交付 | #71 已合并且验证，merge `f4e809d1458c52cd5f8b43f317f8f643def9f306`，合并树与已审 head 相同 | [CI 40305741411](https://github.com/HIT-A/HITA_Android/actions/runs/40305741411) 两 job 成功；artifact `12643576074` 摘要匹配，原始设备 XML 607 全通过；资源任务日志实际执行。证据 `build/pr71-final-signoff.json`、`build/state.json` 与 `build/ci-40305741411/` |
| 5.T 子模块定向验证 | component JVM 7/设备 7、hitauser JVM 4/设备 11、style 设备 8 全通过；app 原 JVM 795 身份保留（794 通过 + 1 既有跳过）、设备 607 全通过；最终 #72 CI 聚合 JVM 806/设备 633 身份全部核验 | `style/modules-validated-results/`；Room null 写入首次 NPE 与原断言修复后通过，三份历史 schema/生成 identity 不变，无需升数据库版本；`style/modules-final-independent-review.md` 已独审；app 原始 XML/审计见 `modules-app-{jvm,device}-results/` 与 `*-audit.json`，实际生产/测试网络 runtime 见 `modules-runtime-resolution.json` |
| 5.T 最终交付 | #72 已合并，merge `d2742e337f23dd3ea7b1a3121ecd21406e9d49e8`，树与 head `5ae5bd7` 相同 | CI 40311018658 两 job 成功；JVM 806（805 通过 + 1 旧跳过）、设备 633 全通过，两个 artifact 摘要匹配；`style/modules-final-signoff.json`。设备报告下载超时期间误发合并命令，设备 XML 在合并后补齐核验，流程偏差保留记录 |
| 4.S1/S2 本地整链 | 原实现 8 兼容通过 / 5 归属准确失败；修复后 29 Subject JVM 与 14 设备全部通过，独审冻结的 21 文件 hash 保持 | `subject/legacy-baseline-jvm-audit.json`、`implementation-first-jvm-audit.json`、`implementation-first-ui-device-results-audit.json`、`implementation-room-device-audit.json`；包含真实 Activity 重建、VM 生命周期、Markwon 和 Room 字段事务。合入 #72 后的完整组合由最终 PR CI 验证 |
| 4.S1/S2 最终交付 | #73 已合并且验证，merge `d4806f7c39c61961436c279ad6ad85a947f36736`，树与 head `127e945` 相同 | [CI 40314264518](https://github.com/HIT-A/HITA_Android/actions/runs/40314264518) 两 job 成功；JVM 828（827 通过 + 1 旧跳过）、设备 647 全通过，两个 artifact 摘要与全部 testcase 身份在合并前核验；`subject/final-signoff.json`、`subject/ci-40314264518/` |
| 5.K/S 本地整链 | 9 个纯生产文件移入独立 JVM 模块、6 个旧测试类共 57 项原样迁移；app 保留 Room adapter；旧 sync 与 keep 清理 | `core/independent-review.md`、`independent-source-integrity.json`、`standalone-jvm-results-audit.json`、`jar-audit.json`、`resolved-boundary.json`；JAR 36 类为 Java 8 字节码，无 fixture/Room adapter，app 真实消费 JAR |
| 5.K/S app 与缩减验证 | app JVM 760（759 通过 + 1 旧跳过）、Debug/测试 APK、26 项分享/迁移/比较集成全通过；Release R8 实际执行成功，无签名 Release APK 结论；最终 PR CI 待验 | `core/app-integration-and-r8-second.log`、`app-jvm-audit.json`、`integration-device-audit.json`、`r8-audit.json`；跨模块 place 属性局部捕获编译修正经独审，57 项迁移不是新增覆盖；最终完整集合为 JVM 828 / 设备 647 |
| 3.Q2 检查点 | 原 28 兼容与 4 安全红绿、46 核心、92 消费端检查点已验证；其后 6 项真实边界先红后绿，另 10 项显示/会话/注销/真实多 facade/自动导入集成通过 | `term-cache/q2-core-first-device-*`、`q2-chain-first-device-audit.json`、`q2-boundary-red-audit.json`、`q2-boundary-green-audit.json`；各检查点独立，不相加冒充最终完整结果。logout marker 测试为重新构造协调器，不是真实进程杀死；当前最终 CI 待验 |
| 3.Q2 最终本地整链 | 学期 cache→remote Flow/Once、事务替换、持久化注销清理标记，七页面 + AI + Worker + 自动导入全部接线；旧四项缓存安全、三项页面缺陷与后续六项边界均有准确红绿；同 key metadata 更新不重发，显式 retry 使用最新快照 | `term-cache/q2-final-incremental-review.md`、`q2-final-incremental-review-hashes.json`；56 个源码/测试 hash 在重基到 #73/纯核心后保持；`q2-final-app-jvm-audit.json` 为 760（759 通过 + 1 旧跳过），Debug/测试 APK 与最新 retry 实测通过；79 项新增设备测试逐项曾实际执行通过，最终聚合预期 JVM 828 / 设备 726 待 CI 核验 |
| 5.K/S 最终交付 | #74 已合并且验证，merge `51819ca061063e0e7e6a0ff37cd348a913faf935`，树与 head `7e33899` 相同 | [CI 40317563827](https://github.com/HIT-A/HITA_Android/actions/runs/40317563827) 两 job 成功；原始 JVM 828（827 通过 + 1 旧跳过）、设备 647 全通过；artifact 摘要及全部 testcase 身份在合并前核实，style 资源校验实际执行；`core/final-signoff.json`、`core/delivery-state.json`、`core/ci-40317563827/`。阶段 5 六项主体已交付；最终审计与全目标 R4 平台边界仍保留 |
| 3.Q2 最终交付 | #75 已合并且验证，merge `463a5982c937c29b078974f7e5b0ac6540625cad`；树与 head `d7aef7c` 相同 | `term-cache/final-signoff.json`、`term-cache/ci-40320879654/`；JVM 828（827 通过 + 1 旧跳过）、设备 726 全通过；两个 ZIP 摘要和全部原始 testcase 在合并前核验 |
| 4.M1/M2 本地检查点 | 51 项新增 JVM 与 28 项新增设备均在相应检查点通过；初次失败、修正及重跑分别保留，未冒充一次全量执行 | `main/targeted-jvm-first-audit.json`（153/154）与 `callback-correction-jvm-audit.json`（1/1）；`platform-current-first-audit.json`（24/28）与 `platform-correction-second-audit.json`（7/7）；测试夹具修正经独立审查，无生产行为断言降级；`q2-integrated-app-jvm-audit.json` 为整合 #75 后完整 app JVM 811（810 通过 + 1 旧跳过），Debug/测试 APK 通过；最终 CI 待验 |
| 4.T1/T2 页面整链 | #77 已合并，课表批次、页面状态与周组件/预览拆分完成 | merge `59b6cc1`；CI 40480112502 实际设备 XML 845 项全通过，原有断言和新增页面/组件用例均在同一报告核验；未做真实账号或物理真机验证 |
| 3.D1/D2 EASRepository 装配 | PR #82 已合并，CI 已通过 | merge `1477dcea`；分支 `codex/eas-repository-injection`；修复提交 `10e4f94`；本地编译、`assembleDebug`、`assembleDebugAndroidTest` 与仓库检查通过；CI 40497758753 设备 XML app 820、component 7、hitauser 11、style 8，共 846 项全部通过，JVM XML 937 通过 + 1 个既有可选联网跳过；真实账号/真机未验证 |
| 3.Q4/I2 成绩查询与通知整链 | PR #83 已合并，merge `ecdd736` | CI 40509648499 两个 job 成功；设备 XML 851 项全部通过，JVM XML 938 项中 937 通过、1 个既有可选 HOA 联网跳过，测试身份无重复；无 adb 设备，真实账号/WebVPN/物理真机未验证 |
| 3.A2 ReAct 工具与 AI 空教室整链 | 当前分支 `codex/react-empty-classroom-flow`，基线 `ecdd736` | `:app:testDebugUnitTest --tests cn.limpu.hita.agent.tools.TypedAgentSuspendingAdapterTest` 7 项、完整 `:app:testDebugUnitTest`、`:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin` 通过；无 adb 设备，instrumentation/CI/真实账号未验证 |

Main 最终主题 smoke：`main/theme-cache-run.log` 的一次实际 instrumentation 方法覆盖全部七种主题并通过，`main/screenshots-final/main-theme-smoke/` 的七张截图已逐张审阅；这是实际渲染 smoke，不是像素等价或真实账号验证。最后夹具改动只涉及 accessibility 虚节点遍历与缓存刷新，生产/JVM 输入未变，复用上述 app JVM 与构建结果；最终完整 CI 仍待验。
