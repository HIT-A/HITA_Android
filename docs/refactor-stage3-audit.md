# 阶段 3 剩余范围审计（2026-10-26）

本审计只读当前代码；没有运行 Gradle、修改仓库或操作 Git。执行时按 refactor-stage345-plan.md 的台账逐项附上 PR、代码及实际验证记录；不能以几个 Once 方法存在或 Thread 数下降作为完成证明。

## 1. 完成定义与边界

阶段 3 的已约定四项：教务适合的一次性请求协程化、EASRepository 依赖注入、聊天会话/附件拆分、AgentToolExecutor 结果语义。当前已完成楼/考试的页面 suspend 路径、空教室远端 suspend 与页面 cache→remote Flow。旧兼容入口仍被 AI 和自动导入使用。因此阶段 3.1 不能仅增加学期 Once 后就标记完成。

- 真正单次远端请求：底层直接返回 suspend 结果；阻塞 HTTP/解析在 IO；不允许新 suspend 实现只是 observeForever/CountDownLatch 包裹旧 LiveData。
- 多次有限结果：使用可取消 Flow 编排，保留已存在的中间成功结果、缓存来源和后续失败策略。不能用 first()/单一 DataState 丢失已有渐进语义。
- 长驻观察：保持 Room 的可观察数据、登录 token/sessionExpired、本地课表默认周、显示范围等状态流。可适当从 LiveData 迁 Flow，但无需为了“全 suspend”改成快照。
- 页面负责选择、激活、取消与展示；Repository 不接受 UI 的 MediatorLiveData 作为输出参数；WebSource 不需要 LifecycleOwner。
- 兼容包装可暂时保留以保护已有测试，但生产主调用链必须走新能力，且最终审计应列出每个保留包装的真实用途；不能让生产继续在旧 thread/observer 链上运行来宣称完成。
- UI/Worker/AI 都应正确传播取消，取消不能变成 FETCH_FAILED 或触发重试。现有同步 Jsoup 调用在 socket 返回前不能保证即时取消；需在每次后续 HTTP、写入和交付前检查取消，并如实记载界限。

## 2. 教务请求完整候选清单

基础路径：`app/src/main/java/cn/limpu/hita/data/source/web/service/EASService.kt`；实现：`eas/EASWebSource.kt`、`AbstractEASWebSource.kt`、`BenbuEASWebSource.kt`、`WeihaiEASWebSource.kt`、深圳子实现 `ShenzhenAcademicWebSource.kt` / `ShenzhenTimetableWebSource.kt`。

| 能力 | 当前形态/调用者 | 验收要求 |
|---|---|---|
| getAllTerms（深圳 app+web、本部本科+研究生、威海） | 远端 LiveData；EASRepository.getAllTerms 有账户成绩学期缓存；Import/Score/Grade/Exam/Classroom/Credit 页面、AI、自动导入、ScoreReminderWorker 使用 | 单次远端 suspend + Repository cache→remote Flow；三校区排序/合并/current/空结果/鉴权/回退不变；请求捕获；缓存事务与异常处理；所有上述生产调用者迁移 |
| getStartDate | 三校区单次；本部/威海校历→推算回退，深圳 app/web 分支；导入页、自动导入 | suspend；取消不进入推算失败包装；保存本部用户校准；选学期后旧日期不得覆盖新学期/手工编辑；Calendar 捕获复制 |
| getScheduleStructure | 三校区单次或串联课程读取；导入页、空教室详情、自动导入、选课规划 | suspend；本科/研究生/威海默认结构与 maxPeriod 截短一致；尊重已保存用户作息；新选择取消旧请求 |
| getSubjectsOfTerm | 深圳课程元信息；本部/威海明确不支持；导入 fetchSelectedSubjectMeta 同步等回调 | suspend；课程教师/学分/性质映射不变，不再 main observer+latch；不支持仍明确失败 |
| getTimetableOfTerm | 深圳线程池多周/日请求与 web 回退、本部/威海课程解析；手工/自动导入 | suspend 编排；保留全部课程合并、相邻节次、重复课程、实验课、回退解析；并行子请求归属同一父作业；取消后不再新增子请求；不使用额外自建线程池绕过父 Job |
| getPersonalScores | 三校区单次，但通常被 WithSummary 包装使用 | 直接单次能力；testType、校区和 term 捕获；错误策略一致 |
| getPersonalScoresWithSummary | **深圳远端自身多次结果**：items→旧 cumulative→official summary/保留 items 的鉴权状态；本部/威海单结果；Repository 还叠加缓存 | Flow；保留成绩先可见、汇总后补及会话失效时已取成绩；不能 await 第一个 SUCCESS 就用于页面终态；成绩通知可明确只取首个有效 items 结果 |
| login / loginCheck / getSafePersonalInfo | 每次本身有限一次请求；Repository enrich 有重试 Handler，authEpoch 防账号切换；LoginEASViewModel、PopUpLoginEAS、EASViewModel 使用 | suspend 请求/可取消登录操作；保留三校区用户名密码/Cookie登录、凭据保存、token刷新和登录信息补全回退；重试使用可取消 delay；logout/新登录后旧响应不写 token |
| getTeachingBuildings / queryEmptyClassroom / getExamItems 旧接口 | 已有新 suspend/Flow，但 AI/自动导入还用旧 LiveData | 迁移调用者并验证生产不再需要旧阻塞等待；保护已有 #65–#68 回归断言 |

深圳特有公开查询也属于“其余适合的一次性请求”，不可因不在 EASService 接口而漏掉：

- `getShenzhenWebTerms`（目录学期不同于普通历史学期，保留下一学期下拉表优先级）；
- `queryShenzhenAvailableCourses` / `queryShenzhenSchoolCourses`（列表分页、选课池初始化、关键词等参数）；
- `getShenzhenSelectedCourses`；
- `getShenzhenCoursePlanningStartDate` / `getShenzhenCoursePlanningScheduleStructure`；
- `getShenzhenCourseRecommendations`（已选→逐选课池→分页→推荐；最终一次结果，取消须停后续页）；
- `getShenzhenRecommendationTracks`（由学分需求得到推荐轨道；需审计实际初步/最终结果选择）；
- `getShenzhenCourseAttachments`；
- `getShenzhenGradeCourses` / `getShenzhenGradeAnalysis` / `getShenzhenHistoricalTeacherFailureRates`（详情缓存 + 多课程历史聚合）；
- `getShenzhenTrainingPlans` / `getShenzhenTrainingPlanCourses`；
- `getShenzhenCreditProgress`：**渐进 Flow**，summary→分类→组→课程记录；options 控制 includeDetails/includeCourseRecords，不能统一压成一次返回。

对应生产页面集中在 `ui/eas/catalog/ShenzhenCourseCatalogViewModel.kt`、`ShenzhenCourseRecommendationViewModel.kt`、`ui/eas/grade/ShenzhenGradeAnalysisViewModel.kt`、`ui/eas/training/ShenzhenTrainingPlanViewModel.kt`、`ui/credit/CreditStatsViewModel.kt`。需逐项列出已接入的请求方法、生命周期和取消测试。

选课提交 `beginExecution/endExecution/submitOnce/selectedRequestIds` 已经是 suspend，且有执行 token 保护；属于有副作用操作。迁移通用请求工具不得给它添加通用自动重试。`downloadShenzhenCourseAttachment` 返回 DownloadManager ID，属于平台提交任务，无需伪装为状态流；保留下载流程与登录头。

## 3. Repository 缓存、导入与后台调用者

### 缓存

当前位置 `EASRepository.kt:370`（学期）、`:531`（成绩）、`EASShenzhenRepository.kt:290`（成绩详情）、`ClassroomQueryCache.kt`（空教室已事务化）。

- 明确每类 useCache=false 的既有读/写语义、离线旧账号缓存读取策略、cache hit 后远端错误抑制策略、null/empty SUCCESS 策略，按基线合同处理；不把空教室规则生搬到学期/成绩。
- 读取/解码失败作为可记录 miss，写缓存失败不吞远端成功，CancellationException 重抛；缓存→远端顺序交付，不通过 postValue 合并状态。
- 学期当前 `deleteTermsByOwnerSync` 后独立 `saveTermsSync` 存在非原子替换；转换先于删除，删除+插入+必要清理同事务，失败留旧批；事务获取之后、DELETE之前检查取消。
- owner key / campus / session / term / query参数在启动前捕获；注销/切账号后旧写入不能污染另一个 owner。
- 空教室缓存的账户/校区隔离是已知单独待办，当前表未带 owner/campus；不能把“捕获 token”当成隔离完成证明。若纳入本次全阶段收尾需显式计划 schema/migration/历史缓存处置。
- `getShenzhenCreditProgress` summary 404 时后续 parseProgress 未传 allowMissingSummary 的旧 bug 已在交接记录；需独立先失败后修复用例，不能当兼容迁移顺便改掉而没有覆盖。

### 导入（`EASTimetableImportRepository.kt`）

当前手工导入接受 `MediatorLiveData<DataState<Boolean>>`，类级 `timetableWebLiveData`；自动导入 thread 内依次 awaitLiveData(terms/startDate/schedule/exam)，再 observeForever 手工导入 result，用 latch 等待 25+5 秒；fetchSelectedSubjectMeta 也独立 latch。快照 list/restore 是 thread + MutableLiveData。

- 将远端阶段与原有导入纯转换/本地原子提交分开，以可取消 suspend/Flow 返回结果；UI 不向 Repository 传 MediatorLiveData。
- 保留 authEpoch/owner、单自动导入防重复、90 秒守卫、开始提交之后不能伪称取消回滚、本地 mutation lock、REPLACE/MERGE、考试导入去重和用户重命名/group保留。
- 结果归属具体操作，不依赖类级共享 web source；并发手工/自动导入不互相移除观察源。
- 超时/取消发生在事务前不得写入；已经启动原子提交应完成并给确定结果，不双发终态。测试必须模拟等待 SQLite 锁、提交阶段、账号切换和超时。
- 快照读取/恢复改 suspend IO，页面有清晰 Job 生命周期；恢复成功刷新仍发生一次。
- `TimetableSourceApplier.applyHeldBatch` 事务内写 changeStore 文件的跨资源恢复风险是独立遗留项，不能由“协程化”证明已解决；若全阶段收尾包含该风险，需 failure injection 验证 DB commit失败后持久恢复状态。

### 后台成绩通知

`data/work/ScoreReminderWorker.kt` 手工 new EASRepository，并用自身 latch/observer 等学期/成绩。迁为 CoroutineWorker 或等效可取消执行，Hilt/入口获取共享依赖；保留 owner baseline、首次不通知、跨学期去重、部分失败 retry、取消不写错误基线。通知 Intent 平台路由拆分同时可消除现存 Worker→UI 例外之一，相关目标见阶段 4/5。

### AI 同步等待

`SearchEmptyClassroomTool.kt`：terms→TimetableRepository.getCurrentWeekOfTimetable（本地可观察查询）→buildings→逐楼查询均以 CountDownLatch+observeForever 等待。`ToolHelper.kt` 的 runTimetableToolSync 创建 AgentSession 后 latch 等30秒，且没有 finally dispose。

- ReActTool 执行入口须具备 suspend 能力，LlmChatService 的工具调用直达新接口；不能只在旧 execute 外包 withContext(IO)，那仍阻塞等待且没有整链取消。
- AI 默认周应增加本地一次性 DAO/Repository读取供工具使用，**页面仍保留 getCurrentWeekOfTimetable 的 DB观察行为**。
- terms/buildings/逐楼查询和 typed工具桥接全链取消，不遗留 observeForever、latch或已取消 session；明确 per-step/overall timeout；当前描述文本、筛选、排序、结果格式保留。
- `LlmChatService.kt` 的 catch(Exception)、模型请求重试和附件路径要 CancellationException优先重抛；取消不能再发失败回答、继续下轮工具或重试网络。
- ReActToolRegistry 的 analytics `withinOperation` 是同步线程上下文；改 suspend 时必须维护 parent operation传播，而非把 suspend 放到不能挂起的lambda里或跨线程误归属。
- 外部 Jsoup同步工具可先以可取消 IO边界接入，取消后不再链式请求/回调；不能声称已中断不可取消底层 socket。

## 4. EASRepository 注入验收

当前 `EASRepository.kt:77–95` 直接构造三校区服务、5个 DAO经AppDatabase.getDatabase取得、TimetableSnapshotStore、Gson；子Repository使用这些实参手工构建。`di/DatabaseModule.kt` 只提供 AppDatabase，未提供 DAO。`EventItemFragment.kt:74` 和 `ScoreReminderWorker.kt:38` 还手工构造 EASRepository/TimetableMutationLock。Repository 实例各自创建，但 TimetableMutationLock 的底层锁已由 companion 共享，不能把手工构造描述为现存独立锁缺陷。

- EASRepository 构造注入服务选择器/三校区能力、需要的DAO/数据库事务边界、snapshot/cache store等；主类不直接 new 三校区 WebSource、不取 AppDatabase静态单例；替身可独立测试请求路由/缓存/注销。
- 不以 `(Application)->EASRepository` 工厂将原来硬编码整体藏起来宣称 DI完成。
- 处理 token refresh callback 对 Repository 的循环依赖：使用独立注入的 session/token coordinator 或显式 factory+回调；保持authEpoch、credentialScopeGeneration、refresh拒收和三校区路由。
- 深圳专有能力有明确接口边界，通用成绩汇总通过接口而非 `when(service is EASWebSource/Benbu/Weihai)`类型分支；不暴露 Jsoup/具体实现给UI。
- EventItemFragment 使用已有 @AndroidEntryPoint 注入；Worker使用共享注入入口；统一 Repository 与 mutation lock 的装配，并保持现有共享锁语义。
- 注入生命周期测试证明同一数据库与 mutation lock被手工/自动/Worker路径共享；fake service可测试无需创建真网络源；Hilt Debug/release编译校验。

## 5. 聊天会话与附件拆分验收

当前 `ui/main/agent/AgentChatViewModel.kt` 约900行：直接数据库+DAO、Executors.newSingleThreadExecutor无shutdown、Handler交付、session maps、LLM编排、文件名/MIME/缓存/文档解析都在类内。现有 `ChatSessionDelivery` 已保护切会话/删除归属；这些规则必须保留。

### 会话

- 建立数据层ChatSessionRepository / store，负责sessions观察、创建、删除（messages+session事务）、消息保存+首条user标题事务、恢复列表；ViewModel不再直接AppDatabase/DAO。
- repository使用数据模型，不能 import UI AgentChatMessage；必要共享模型搬到data/domain，UI mapper在上层。
- 保留 serial写入顺序：创建session先于message保存；删除与pending保存的竞争不会复活session；恢复read后有新消息时重读，不覆盖刚收到回复。
- session ID/request ID captured，切会话期间后台回复只归旧session；删除后迟到回复丢弃；快速切换/恢复ticket/多请求/附件返回均有测试。
- 明确已有消息写入在ViewModel清除后的语义（持久化完成 vs 明确取消），不能简单cancel全部IO丢用户刚发消息。去掉未管理Executor，或由store显式管理有限生命周期/关闭；queued Main交付清除后不更新UI。
- LLM/附件有可取消Job，删除session取消相关任务（否则迟到网络继续工作），会话切换不应无故取消允许后台完成的原请求；current UI加载/placeholder与后台状态分离。
- `ChatSessionDao.getAll`、`ChatMessageDao.getBySession`是合法持续DB观察，保留。

### 附件

- 独立 attachment processor负责元信息/真实字节限长/临时文件/解析/编码，返回typed结果；ViewModel只协调UI和发送。纯解析不访问聊天会话或LiveData；Android URI适配与解析器分层。
- `cacheChatAttachment` 的真实读取字节限长、零字节read、超限不落最后byte、输入/临时文件清理原测试完整保留；不要退回 available()/仅metadata size。
- PDF/DOCX/XLSX/PPTX/TXT、图片/视频各路由、空文件、不支持类型、大小边界、filename特殊mht、截断规则/提示内容有覆盖。
- PDF保留PDFBoxResourceLoader和GB1/CNS1 CMap设备回归；不反射缓存、不重复资产。POI与PDF流/parser必须 use/finally异常也关闭；取消时关闭并删除临时文件。
- XML/ZIP展开限制、Office解压风险需显式处理/记录，不以20MiB压缩输入就证明内存安全；不能无界readText后才截5000字。可分独立可审查修复并先回归。
- CancellationException优先传播；取消不是“附件处理失败”。测试真实processor错误/关闭行为，而非只有ViewModel测试名字下三个纯helper用例（当前AgentChatViewModelTest仅覆盖删除后session ID选择）。

## 6. AgentToolExecutor 结果语义

当前 `AgentTool.kt`: `ok:Boolean,data:T?,error:String?`可产生非法组合；Executor声明AgentToolErrorType但未用，所有超时/业务错误最后变字符串failure。`tool.execute`同步抛异常未捕获；每次attempt有AtomicBoolean，整体operation无cancel handle；retry Handler不受session.dispose取消。OrchestratorAgentSession.dispose只屏蔽回调，不停止工具/重试。

**校正：现有 TimetableAgentEngine 已对 ADD_TIMETABLE_ARRANGEMENT 配置 retryCount=0，避免超时重复插入，这一保护应保留并用测试固化，不能写成当前存在自动重复添加缺陷。**

- 结果类型明确区分success、业务failure、timeout、cancelled（可以sealed或受约束兼容模型），支持error kind、attempt等必要元数据；错误文本过滤和trace脱敏仍在。
- 同步异常、callback失败、永不回调、timeout、双回调/迟到回调、立即同步成功、retry延时取消、session重入均有确定且只一次的最终结果。
- 明确 callback线程或改suspend统一结果；外层observer异常不要错当tool failure重试副作用。
- retry次数/延迟/timeout无效参数边界和overflow有约束；区分retryable读取与不可重放写入，添加活动默认0重试；验证一次提交超时不会执行第二次。
- 若保留callback AgentTool，execute应返回取消句柄/通过协程桥接取消；AgentSession.dispose贯穿到executor等待、重试与可取消工具；已经提交本地原子事务只屏蔽过期交付，不能宣称回滚。
- 所有engine/provider/toolhelper调用者保留typed错误，不重新只转成字符串丢kind；聊天ReActTool仍是另一套接口，连接点有明确adapter，不能混为一谈。

## 7. 推荐拆批与证据门槛

1. 学期远端三校区共享同步核心+suspend；冻结现有请求/解析契约，取消测试。随后学期缓存+所有页面消费者（不是只空教室）迁Flow，学期缓存事务。
2. 开学日/作息/课程元信息/课表远端suspend与三校区fixture；同步迁相关导入/目录页面查询选择与手工编辑优先级。
3. 成绩与深圳专属查询按功能拆批：单次读取、渐进成绩、渐进学分、目录/推荐分页、培养方案/历史统计，逐批迁Repository缓存与页面；修复summary404用独立先红用例。
4. 登录/校验/补全协程化，保留authEpoch和凭据安全合同；与DI session coordinator设计配合，避免反复改造。
5. 导入+自动导入+快照端到端协程，保留阶段2写入原子性/账号守卫；ScoreReminderWorker协程/DI接入。
6. EASRepository依赖注入正式收尾（可与上述批次共同先引入接口，但验收全部生产构造点），移除对具体源类型判断。
7. typed AgentToolExecutor语义与取消→ReAct suspend工具链/AI学期-周-楼-空教室查询，保留工具结果呈现。
8. ChatSessionRepository+ViewModel状态编排/生命周期；独立附件处理器/资源清理/解析边界。
9. 阶段3最终audit：枚举每个EASService和深圳公开请求、每个生产调用点、剩余LiveData/thread/latch原因，不依赖grep零命中；每个条目给测试与合并PR链接。

每批的证明：现有测试源码/身份保持（除必要测试harness适配有独立review）；新行为测试先证明触发；JVM、assembleDebug、所涉androidTest+完整设备回归按修改风险运行；CI实际报告核对身份/错误/skip，不能仅看绿灯；仓库/分层检查通过；独立静态review。不能把MockWebServer/Room/模拟器测试描述成校园真实账号验证。
