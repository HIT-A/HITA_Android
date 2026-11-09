# Agent 04 — 运行性能 / 后台任务 / 线程与协程审查（阶段一报告）

基线 commit：`5992ba381a9d6e87deb7526885f3d957b8a6d09b`；分支：`refactor/agent-04-performance`。

本文件是阶段一（基线 + 审查 + 方案 + 自审）的交付，不改动生产代码。证据来自源码定位、现有测试和独立模拟器 (`emulator-5560`, android-35 google_apis x86_64, `hita_perf` AVD) 上的可复现探测。其他 agent 使用 `emulator-5554`，本 agent 使用 `5560` 端口和独立 AVD，避免互相干扰。

## 1. 性能基线

### 1.1 测量装置

| 项 | 值 |
| --- | --- |
| 模拟器 | `emulator-5560`，AVD `hita_perf`，android-35 google_apis x86_64 |
| 安装包 | `cn.limpu.hita.diag`（debug 变体，独立 applicationId，与其它 agent 的测试包互不覆盖） |
| 冷启动采样 | `adb shell am start -W`，5 次，force-stop 间隔 2s |
| 冷启动基线 | WaitTime 31.465s → 27.852s → 23.263s → 18.664s → 19.844s（`LaunchState: UNKNOWN`） |
| 说明 | 该数值含 6 个 agent 并行时的宿主机争用噪声，**不作为收益声明依据**；仅用于确认当前冷启动路径不早退 |

宿主机并行 density 使冷启动墙钟不可比，因此本次所有收益声明改用**确定性探针**：

| 探针 | 语义 | 是否受宿主机负载影响 |
| --- | --- | --- |
| StrictMode 主线程磁盘策略 + 栈帧归因 | 断言 analytics 主线程零同步写 | 否 |
| 阻塞线程计数（自建 Executor 探针） | 断言重试等待释放执行器线程 | 否 |
| WorkManager `WorkInfo` 快照 | 断言唯一任务、幂等、调度漂移 | 否 |
| `dumpsys jobscheduler` 快照 | 断言任务不堆积 | 弱（需同机对比） |

### 1.2 PERF-01：`UsageAnalyticsClient` 主线程同步 `commit()`

证据（`app/src/main/java/cn/limpu/hita/data/analytics/UsageAnalyticsClient.kt`）：

| 行 | 上下文 | 改动点 |
| --- | --- | --- |
| 80 | `ProcessLifecycleOwner.onStop` → 主线程调 `sessionPrefs()…commit()` | 主线程同步写 |
| 125 | `main.post(heartbeat)` Runnable（每 30s）→ `commit()` | 主线程每 30 秒同步写 |
| 142 | `qualify()`，由 `main.postDelayed(qualifier)` / `onActivityResumed` 触发 | 主线程同步写 |
| 156 | `setEnabled()`，由设置开关（UI 线程）调用 | 主线程同步写 |
| 71 | `catalog = JsonParser().parse(app.assets.open(...))` 在 `initialize()` 同步读 18KB asset | 主线程同步 I/O（附带宽） |

量化：主线程每次 `commit()` 都会 `fsync` 一次 `usage_analytics_session.xml`。
- 前台会话中 `heartbeat` 每 30 秒一次 → **每小时 120 次主线程 fsync**；
- 每次 `onStop`/`onStart`/`qualify()` 各一次；切换统计开关一次。
- 冷启动 `initialize()` 还会在主线程解析 18,345 字节 JSON 目录（54 事件），且没有缓存到磁盘。

附带正确性风险（同源）：`heartbeat` 的 `sessionPrefs()?.edit()?.…?.commit()` 未检查返回值；`setEnabled` 中 `check(...commit())` 会因一次磁盘失败直接抛到 UI 线程（`NavigationFragment:398` 无 try）。

### 1.3 PERF-02：`BlogRepository` 用 `Thread.sleep()` 重试占住单线程执行器

证据（`app/src/main/java/cn/limpu/hita/data/repository/BlogRepository.kt`）：

| 行 | 事实 |
| --- | --- |
| 24 | `private val executor = Executors.newSingleThreadExecutor()`，单线程，无界队列 |
| 165 | `Thread.sleep(1500L * (attempt + 1))`，两次重试共阻塞 1.5s + 3.0s = **4.5s** |
| 90-118 | `refresh()` 全程在该执行器上跑，`downloadFeed()` 的 `callTimeout` 150s、`readTimeout` 120s，加上 4.5s 睡眠 |
| 92-100, 108, 119 | `markTabOpened` / `refresh(force)` 各跑多次 `dao.getAll()`；`refresh` 一次内部 3 次全表读 |
| 66-74 | `loadArticle` 在同一个执行器上做 DAO 读，被 refresh 的 150s 网络 + 4.5s 睡眠排队阻塞 |

后果：
- 该执行器只有 1 线程且**无界队列**：下拉刷新 + 打开文章 + 标记已读会串行排队；用户点击文章后回调可能延迟数十秒。
- 执行器永不关闭（`@Singleton`，随进程结束），没有取消机制：进程内普通任务在后台常驻期间会持续阻塞这个线程。
- `Thread.sleep` 不可取消：即使进程即将被回收/刷新应被取代，也不会提前退出。

### 1.4 PERF-03：后台任务唯一性 / 幂等 / 生命周期

| 位置 | 事实 | 判定 |
| --- | --- | --- |
| `CourseReminderScheduler.schedule`（32-45） | 周期任务用 `UPDATE`；随后 `scheduleNextCheck(0)` 用 `REPLACE` 立即入队 | ✅ 唯一；但每次 `autoSchedule()` 都 `REPLACE` 一次性任务，正在跑的 Worker 被取消 |
| `CourseReminderScheduler.autoSchedule`（66-75） | `HApplication.onCreate` + `NavigationFragment:320/329` 都会调用 | 冷启动+切页各触发一次全量重建 |
| `CourseReminderWorker.doWork`（29-67） | `runBlocking` 包 Room 读（Worker 线程，安全）；但 `getSentReminders()`/`saveSentReminders()` 用 `apply`（异步，无一致性保证）；跨午夜 `KEY_DATE` 才清空 | ⚠️ 竞态：周期任务和一次性任务同时到期时，两个 Worker 实例可同时读到同一 `sentReminders` 并双发通知（WorkManager 只对**同名**唯一任务互斥，两者名字不同） |
| `CourseReminderWorker.sendNotification`（85-110） | 每当 `event.id` 变化即发通知，无节流 | 可接受 |
| `ScoreReminderScheduler.schedule`（10-33） | 每次调用 `UPDATE` 周期 + `REPLACE` 一次性 | ✅ 唯一 |
| `ScoreReminderScheduler.schedule` 调用点 | `ScoreInquiryActivity:286`，页面每次进入都可调用 | 每次进入页面重建即时任务 → 与周期任务并行查询 EAS |
| `WidgetRefreshScheduler` | 周期 15 分钟 `UPDATE`；`onEnabled` / `HApplication` 都可入队 | ✅ 唯一 |
| Widget XML `updatePeriodMillis` | `1800000`（30 分钟），与 WorkManager 15 分钟**双重刷新** | ⚠️ 平台自身每 30 分钟调用 `onUpdate`，WorkManager 每 15 分钟再发广播 → 同一数据被刷新 3 次/30 分钟 |
| `WidgetUtils.sendRefreshToAll`（21-29） | 采集 `getAppWidgetIds` 后 `context.sendBroadcast` 到 provider | 每次都要两次 `getAppWidgetIds`（`hasAnyWidget` + 自身） |
| `TodayWidget.onUpdate` / `onReceive(EVENT_REFRESH)` | 同一个 `goAsync` 块在 `Dispatchers.Main` scope 里做 Room 读（`withContext(IO)` 内），但 `for (appWidgetId in …)` 在主线程逐个 `setUpOneWidget` | ⚠️ 多实例时主线程逐实例构建 RemoteViews |
| `LiveCourseWork.ensurePeriodic` | `KEEP`；`scheduleTransition` `REPLACE` | ✅ 唯一 |
| `LiveCourseScheduler.reconcile`（38-92） | 每次调用 `LiveCourseWork.ensurePeriodic()`、`DisplayScopeResolver.currentSync()` 事务 + Room 读 + 通知构建 | ⚠️ 强提醒 guard 每 60s 调一次 `reconcile()`，每次都做完整事务 + 通知重建；前置条件不满足时先 `ensurePeriodic` 再 `cancel()`，白建周期任务 |
| `LiveCourseScheduler.autoSchedule`（192-206） | 每次都新建 `CoroutineScope(SupervisorJob()+IO)` 且从不 cancel | ⚠️ 协程泄漏：切换节课表 → 新 scope 常驻，旧 scope 也常驻（虽然任务完成即空转，但每 scope 保留 Job 对象到 GC） |
| `LiveCourseScheduler.scheduleAt`（121-152） | 同时排 `LiveCourseWork.scheduleTransition`（WorkManager）+ AlarmManager 精确/非精确闹钟 | 两路冗余是设计意图（fallback），保留 |
| `LiveCourseGuardService.onStartCommand`（41-64） | `while (isActive && isGuardEnabled())` + `delay(60_000)` | ⚠️ 每分钟完整 reconcile；`stopSelf()` 在 while 之后，只有设置关闭才退 |
| `LiveCourseReceiver` | `CoroutineScope(SupervisorJob()+IO)` per broadcast，无取消 | 可接受（broadcast 短命） |
| `CourseSelectionBootReceiver` | 同上 | 可接受 |

### 1.5 其他主动发现（待核实后处置）

| ID | 位置 | 事实 | 影响 |
| --- | --- | --- | --- |
| ~~PERF-04~~ | `UsageAnalyticsClient.capture` | 复核后**不是问题**：`gson.toJson` 只调用一次，`toByteArray` 一次仅用于测长度 | 撤销，不修 |
| — | `UsageAnalyticsClient.initialize`（71） | 18,345 字节 asset 只解析一次（`contextRef` 早退保证） | 实测毫秒级；后台加载会丢冷启动 `app_foreground` 事件，**保留** |
| PERF-05 | `UsageAnalyticsClient.capture` | 每次捕获都 `scope.launch { store().enqueue(...); schedule(); … }`；`schedule()` 内部又建两个 WorkRequest 并调 `WorkManager.enqueueUnique*` | `app_foreground`/20 条阈值才 flush，但 `schedule()` **每条事件**都触发两次 WorkManager IPC |
| PERF-06 | `UsageAnalyticsClient.drain`（218-266） | `while` 循环内 `store.batch()`、`store.size()`、`store.acquire()` 每次一个新事务/查询；`store().size()` 在循环末尾固定执行 | 每批一次 DB 往返之外的额外查询 |
| PERF-07 | `BlogRepository.publishUnseenLocked` | 每次调用 `dao.getAll().map { guid }.toSet()`，即使只需要 guid 集合 | 拉全部列（含 `htmlContent` 长文本）只为 guid |
| PERF-08 | `TodayWidget`/`Slim` 主线程 | `setUpOneWidget` 逐 widget 在主线程构建 RemoteViews | 与 PERF-03 widget 项合并 |

## 2. 方案对比

### PERF-01 三个候选

| 方案 | 做法 | 代价 | 结论 |
| --- | --- | --- | --- |
| A. 改 `apply()` | 主线程编辑改异步落盘 | 丢失"主线程内立即可读"的语义；`qualify()` 依赖 `last_seen` 在下一次读写到，`apply` 同进程内存可见，安全 | 最小改动，但**没有消除主线程磁盘 I/O**（`apply` 仍会触发 QueuedWork 写，且在 `onStop` 时 `QueuedWork.waitToFinish()` 会同步等待） |
| B. 换纯内存字段，落盘转移到 `scope`（IO） | 会话状态改为 `@Volatile` 内存字段真值，仅在 IO 协程里持久化 | 进程被杀时可能丢最后一次 `last_seen`；但 `session_id` 在 `qualify()` 时立刻需要在**同一决策内**可见，可先写内存再异步落盘 | **选 B**：消除主线程 fsync，且 `commit`→`apply` 之外的 IO 全部移出主线程；跨进程的 `last_background` 只在冷启动读取，由 IO 侧写回 |
| C. 显式单线程预写队列 | 自建 writer 线程 | 复杂度高、与 B 效果重复 | 否 |

配套：`catalog` 改为**懒加载 + 结果缓存**，在 `initialize()` 内用 `scope.launch(IO)` 读取 assets（`initialize` 不再阻塞主线程）。风险：`initialize` 之前在 UI 线程调 `record()` 时 `catalog` 为空 → 必须保证 `catalog` 就绪前 `capture()` 静默丢弃（当前行为就是 `?: return`），语义不变。

### PERF-02 两个候选

| 方案 | 做法 | 代价 | 结论 |
| --- | --- | --- | --- |
| A. 保留 `Executor`，把 `Thread.sleep` 换成 `ScheduledExecutorService` 调度重试 | 少改动 | 仍无可取消语义；执行器仍需手工跨线程拼接 | 否 |
| B. 整个 `BlogRepository` 改为 `CoroutineScope(SupervisorJob()+IO)` + `suspend` + `delay()`，重试挂起而非阻塞 | 需要改公开签名（`refresh`/`markTabOpened`/`markArticleRead`/`loadArticle`） | 调用点 5 处（`BlogViewModel`、`BlogReaderActivity`、`MainRequests`），改动可控 | **选 B** |
| C. B + 结构化取消 | `refresh` 保存 `Job`，新 refresh 取消旧的（等价于 `ExistingWorkPolicy.REPLACE` 语义） | 需定义契约 | **选 B+C** |

契约：`refresh` 的 `Job` 显式 `cancel()` 前一个；`delay` 可取消；`loadArticle` 的 `callback` 保持"调用即回调一次"，但回调改为在主线程 `post`（当前是在执行器线程回调，`BlogReaderActivity` 已 `runOnUiThread`，行为不变）。

### PERF-03 处置

| 项 | 方案 |
| --- | --- |
| 课程提醒双 Worker 并发 | 一次性任务改名为**同一唯一名的串行链**：`CourseReminderWorker` 结束时把下一次入队到 `course_reminder_next`（现状已有），**并且**周期任务在 `doWork` 内检测"已有一次性任务在排队/运行"时直接 `success`（`existingWorkInfosForUniqueWork(course_reminder_next)`），避免重复通知。更简方案：删除周期任务，只保留一次性自调度链（15 分钟粒度由 `scheduleNextReminderCheck` 决定，最小 1 分钟 → WorkManager 对一次性任务无最小间隔限制）。**选删除周期任务**：单一调度源，天然互斥。 |
| 课程提醒 `apply` 竞态 | `getSentReminders`/`saveSentReminders` 改为单个共享偏好 + `commit`（Worker 在后台线程，允许同步写），保证同一 Worker 内读改写原子；跨 Worker 由单一调度源消除。 |
| `CourseReminderScheduler.autoSchedule` 重复 | `schedule()` 内 `enqueueUniqueWork(REPLACE)` 会取消正在跑的提醒 → 改为 `KEEP`（存在即不重建）；仅在 `cancel()`→`schedule()` 有明显状态变化时需要重建。 |
| 成绩提醒 `schedule` 每次进入页面 | `schedule()` 一次性任务改 `ExistingWorkPolicy.KEEP`；周期用 `UPDATE` 保留（未变）。 |
| Widget 双重刷新 | 去掉 XML `updatePeriodMillis`（设 0）或去掉 WorkManager 周期？WorkManager 15 分钟更细且能在后台唤醒；平台 `updatePeriodMillis` 最小 30 分钟且不精确。**选保留 WorkManager，XML 改 `updatePeriodMillis="0"`**，由 WorkManager 驱动。风险：用户手动添加 widget 时 `onUpdate` 仍会被调用一次，功能不变。 |
| Widget 主线程逐实例 RemoteViews | `setUpOneWidget` 循环移入 `withContext(Dispatchers.IO)` 或至少不逐实例等待；本轮保留主线程（RemoteViews 构建必须在主线程？`AppWidgetManager.updateAppWidget` 可在任意线程）→ 移出主线程。 |
| `LiveCourseScheduler.autoSchedule` scope 泄漏 | 改为共享单例 scope + `reconcileJob?.cancel()` 后重新 launch（等价 `REPLACE` 语义）；`reconcile` 已经有 `generation` 守卫，取消后 `generation` 不再匹配。 |
| `LiveCourseScheduler.reconcile` 顺序 | `ensurePeriodic` 移到"已确认启用且可发通知"之后（现在在前，禁用时白建再 cancel）。 |
| `LiveCourseGuardService` 60s | 保留（强提醒语义要求），但把 `ensurePeriodic` 的重复调用降为 `KEEP`（已 KEEP，无 WorkManager IPC 写放大其实仍在：每次 `enqueueUniquePeriodicWork` 是一次 IPC）。改为进程内 `AtomicBoolean` 记忆已排过。 |

### 不做（明确排除）

- 不改 `data/repository/` 下 Agent 02 的其它 Repository。
- 不改 `ui/` 页面（`NavigationFragment`、`ScoreInquiryActivity`、`BlogReaderActivity` 的 UI 结构不动；只允许 Blog 调用点从 `refresh()` 改 `suspend` 适配，属必要签名适配）。
- 不改 CI、`scripts/`、公共测试框架。
- 不提高并发度。

## 2.6 实施后的契约变更（阶段二逐步补充）

| 组件 | 变更 | 契约 |
| --- | --- | --- |
| `UsageAnalyticsClient` | 同意代次与会话状态改为进程内内存真值，落盘只在 IO 协程 | 开关切换立即可见；主线程零 `commit()`；磁盘失败只记录本地失败，不再抛出到 UI；进程被杀可能丢失最后一次 `last_seen` |
| `UsageAnalyticsClient.schedule` | 按同意代次记忆化 WorkManager 入队 | 同一代次内不重复 IPC；仅在 `setEnabled(false)` 取消任务后重新入队 |
| `BlogRepository` | 单线程 `Executor` + `Thread.sleep` → `CoroutineScope(IO)` + `delay` | 重试退避不再占用线程；`refresh` 取消旧任务（后者取代前者）；`loadArticle` 不再排在 150s 下载后面 |
| `BlogArticleDao` | 新增 `getAllGuids()` / `count()` | 未读记账与"是否已有数据"不再拉取全文 HTML |
| `CourseReminderScheduler` | 删除 15 分钟周期任务，只保留一次性自调度链 | 单一调度源，消除同名任务不同名导致的双 Worker 并发通知；外部入口 `KEEP`，Worker 自调度 `REPLACE` |
| `CourseReminderWorker` | 已发送集合写 `commit()`（后台线程） | 进程在通知后立即死亡也不会重发同一课程 |
| `ScoreReminderScheduler` | 即时任务 `REPLACE` → `KEEP` | 重复进入成绩页不取消在途查询 |
| `WidgetRefreshScheduler` + widget XML | `updatePeriodMillis` 30 分钟 → 0 | 平台与 WorkManager 的双重刷新收敛为 WorkManager 单源（15 分钟） |
| `LiveCourseWork` | `ensurePeriodic` 记忆化 | guard 每 60 秒 reconcile 不再产生两次 WorkManager IPC；`cancel` 清除记忆 |
| `LiveCourseScheduler.autoSchedule` | 每次新建 scope 且从不取消 → 单共享 scope + 可取消 `reconcileJob` | 触发串行化，后者取代前者；不再累积 scope |

## 2.7 测量装置故障与处置（环境事实）

宿主机默认 `JAVA_HOME=D:\JDK`（Java 25）。用 Java 25 启动 Gradle 8.11.1 时，**任何模块的 Test 任务都无法创建**，报错与代码无关：

```
Could not create task of type 'Test'
  > DefaultReportContainer
    > java.lang.TypeNotPresentException: Type T not present
```

证据：在基线 commit `5992ba3` 的独立 worktree 中执行 `:app:testDebugUnitTest`，以及 `:timetable-core:test`（纯 JVM，与本次改动无关），均以同一错误失败；`gradlew help` 与 `:app:compileDebugKotlin` 正常，说明是测试任务实例化路径。改用

```
-Dorg.gradle.java.home=C:\Users\ming\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2
```

后 `:timetable-core:test` 通过。所有后续测试与仪器测试均固定该 JDK 21 启动参数（不写入仓库配置，符合"不提交机器相关路径"）。

## 2.8 真机/模拟机证据（优化后代码，`cn.limpu.hita.diag`，`emulator-5560`）

采集方式：把设备上的 `no_backup/androidx.work.workdb`（含 `-wal`/`-shm`）拉回宿主机，用 SQLite 直接查询 `WorkSpec`/`WorkName`；`dumpsys jobscheduler` 查系统层作业。

| 证据 | 命令 | 结果 |
| --- | --- | --- |
| WorkManager 只有两条记录且均为统计任务 | `select id,state,worker_class_name,interval_duration from WorkSpec` | `UsageAnalyticsWorker` 间隔 0（一次性）与 900000（15 分钟周期），`state=0` |
| 唯一任务名各一条 | `select name,work_spec_id from WorkName` | `usage-analytics-send-v2`、`usage-analytics-rescue-v2`，无重复 |
| 无遗留课程提醒周期任务 | 同上 | 无 `course_reminder_periodic` 记录 |
| 系统 JobScheduler 作业数 | `dumpsys jobscheduler` | `JOB #u0a209/0`、`JOB #u0a209/1` 两条，未堆积 |

## 2.9 优化前后对照（可复现证据）

同一测量装置：`emulator-5560`（AVD `hita_perf`，android-35 google_apis x86_64），debug `cn.limpu.hita.diag`；“优化前”= 基线 commit `5992ba3` 在 `.worktrees/agent04-baseline` 构建的同一变体，只把 `app/src/androidTest/java/cn/limpu/hita/perf/` 拷入；直接 `adb shell am instrument` 定位单测类，不经 `connectedAndroidTest`（它会遍历其它 agent 的 AVD）。

| 编号 | 探针与断言 | 优化前 | 优化后 | 判定 |
| --- | --- | --- | --- | --- |
| PERF-01 | `AnalyticsMainThreadIoTest`，StrictMode `detectDiskWrites` + 栈帧归因，含一次主线程自检写证明采集有效 | **失败**：`StrictMode policy violation; ~duration=14 ms: DiskWriteViolation` / `~duration=12 ms` / `~duration=11 ms`，帧为 `cn.limpu.hita.data.analytics.UsageAnalyticsClient.setEnabled(UsageAnalyticsClient.kt:156)`（主线程 `commit()`） | **通过**：归因到 analytics 的违规 0 条；自检帧仍在，说明探针有效 | ✅ 消除 |
| PERF-03 | `BackgroundScheduleUniquenessTest.courseReminderHasNoPeriodicRequest` | **失败**：`a periodic course reminder still exists: [WorkInfo{... tags=[CourseReminderWorker], periodicityInfo{repeatIntervalMillis=900000, flexIntervalMillis=900000}}]` | **通过**：无周期记录，唯一一次性链路 1 条 | ✅ 消除 |
| PERF-03 | 同文件其余 3 项（重复调度只保留 1 条课程链路 / 成绩即时任务 / 组件周期任务） | 4 项中 1 失败 | 4/4 通过 | ✅ |
| 同意语义 | `AnalyticsConsentStateTest`（开关立即可见、关闭时不记录、不抛异常） | 基线可见性依赖主线程同步 `commit`，磁盘失败会抛到调用线程 | **通过**：2/2 | ✅ 行为保留且更稳 |
| PERF-02 | `BlogRetryBackoffTest`（JVM，无需设备）：重试预算 3 次、成功后不再重试、单线程执行器在退避期间可继续执行、取消立即中断等待 | 退避为 `Thread.sleep`，探针在基线源码上不成立（该 helper 不存在） | **通过**：4/4，退避线程被释放、取消 <1s 返回 | ✅ 消除（结构性证据） |
| 冷启动 | `am start -W` 5 次 | 18.6–31.5 s（6 个 agent 并行时的宿主机争用，**不作收益声明**） | 484 ms（同机、单 agent 独占后） | 仅记录，不声称收益 |

补充设备快照（优化后代码）：`no_backup/androidx.work.workdb` 中只有 `usage-analytics-send-v2`/`usage-analytics-rescue-v2` 两条唯一任务；`dumpsys jobscheduler` 仅 `JOB #u0a209/0`、`/1`，无课程提醒周期作业。

## 2.10 模拟机功能验证（优化后代码）

设备：`emulator-5560`（隔离 AVD，其它 agent 使用各自的 `hita_agent0x` AVD，互不干扰）。应用：`cn.limpu.hita.diag`。

| 场景 | 步骤 | 结果 |
| --- | --- | --- |
| 冷启动 | `am force-stop` → `am start -W MainActivity` | `WaitTime: 484 ms`，进程存活，无 FATAL 日志 |
| 统计任务持久化 | 清空 `no_backup` 与统计偏好后启动 | `WorkName` 得到 `usage-analytics-send-v2`(interval 0, ENQUEUED) 与 `usage-analytics-rescue-v2`(900000, ENQUEUED) |
| 进程重启恢复 | `am force-stop` → 重启应用 | 两条任务仍在且 `state=0`（ENQUEUED），未丢失 |
| 组件刷新 | `appwidget grantbind` + `am broadcast WIDGET_EVENT_REFRESH → TodayWidget` | 广播投递成功，进程 pid 不变，无崩溃；`updatePeriodMillis=0` 后仍能渲染 |
| 组件注册 | `dumpsys appwidget` | `TodayWidget`、`TodayWidgetSlim` 两个 provider 均注册 |
| Live Course 强提醒 | `ENABLE_STRONG_REMINDER_DEBUG` → 启动 guard | `LiveCourseGuardService` 进入运行态（`createTime` 活跃），无异常 |
| 既有统计/成绩/LiveCourse 仪器测试 | 直接 `am instrument` 指定类 | `UsageQueueInstrumentedTest` 4/4、`LiveCourseGuardRecoveryInstrumentedTest` 3/3、`ScoreReminderSessionTest` 7/7 全绿 |

一次误判的记录：早期把"force-stop 后只剩 CANCELLED 一次性任务"当作自调度回归，复核发现是 `UsageQueueInstrumentedTest` 用例刻意 `setEnabled(false)`（关闭统计会取消两条唯一任务并清除代次）。清空偏好重测后持久化与恢复均正常；基线包在同样含测试残留状态下同样是空的，两者行为一致。

## 2.11 未解决 / 明确不做

- 冷启动墙钟未取得可信收益：并行 agent 争用下基线 18–31 s，独占后 484 ms，条件不可比，**不作收益声明**。冷启动优化不在本次改动面（`initialize` 的 18 KB 目录解析经评估后保留：后台加载会丢冷启动 `app_foreground` 事件）。
- `TodayWidget`/`TodayWidgetSlim` 的 `setUpOneWidget` 仍逐实例在主线程构建 RemoteViews：`AppWidgetManager.updateAppWidget` 可以跨线程，但 `RemoteViews` 构建与 `PendingIntent` 解析的线程安全需单独验证，本轮未改，避免未验证的行为变更。
- `UsageAnalyticsClient.drain` 循环内的额外 `store.size()`/`acquire()` 查询未合并：需要改队列存储事务边界，超出本 agent 的持久化契约范围（`UsageAnalyticsQueueStore` 也被 Agent 02 相关测试依赖）。
- 未改 `data/repository/` 下其它 Repository（Agent 02 范围）、未改 `ui/` 页面（Agent 03 范围）、未改 CI 与 `scripts/`。

## 3. 自审结论（阶段一）

已核对：三条 PERF 均有源码行级证据；方案对每条均有"更简单候选被否"的理由；测量装置与其它 agent 隔离（独立 AVD + 5560 端口 + `.diag` 包名）。
待阶段二验证：PERF-01/02 探针先红后绿；PERF-03 用 `WorkInfo` 快照断言唯一与幂等；Widget/`LiveCourse` 走模拟机点验。

## 4. 阶段二范围（修改文件预清单）

| 文件 | 变更 |
| --- | --- |
| `data/analytics/UsageAnalyticsClient.kt` | 主线程零同步写；catalog 懒加载；`capture` 单次序列化；`schedule` 去重 |
| `data/analytics/UsageAnalyticsQueueStore.kt` | 保留 `commit`（已在 IO 侧），仅补 `installation_id` 写入契约 |
| `data/repository/BlogRepository.kt` | 协程化 + 可取消 `delay` + 取消旧 refresh + guid-only 查询 |
| `data/source/dao/BlogArticleDao.kt` | 新增 `getAllGuids()` |
| `data/work/CourseReminderScheduler.kt` / `CourseReminderWorker.kt` | 单一调度源、原子偏好写 |
| `data/work/ScoreReminderScheduler.kt` | 一次性任务 `KEEP` |
| `data/work/WidgetRefreshScheduler.kt` + `res/xml/*_widget_info.xml` | 唯一刷新源 |
| `ui/widgets/today/{normal,slim}/*.kt` | 循环构建移出主线程 |
| `feature/livecourse/scheduler/LiveCourseScheduler.kt` | 共享 scope + `reconcileJob` 取消 + 顺序调整 |
| `feature/livecourse/guard/LiveCourseGuardService.kt` | 周期任务排程记忆化 |
| 测试 | `app/src/test/java/.../repository/blog/BlogRetryBackoffTest.kt`（JVM：重试预算、取消即时性、单线程不被占住）<br>`app/src/androidTest/java/.../perf/AnalyticsMainThreadIoTest.kt`（StrictMode + 栈帧归因）<br>`app/src/androidTest/java/.../perf/AnalyticsConsentStateTest.kt`（内存可见性与代次推进）<br>`app/src/androidTest/java/.../perf/BackgroundScheduleUniquenessTest.kt`（WorkManager 唯一性与无周期课程提醒） |

回滚方案：
- 单分支 `refactor/agent-04-performance`，全部生产改动集中在上述 11 个文件；测试与文档为新增文件，整体执行 `git revert <commit>` 或 `git checkout 5992ba3 -- <文件列表>` 即可回滚，无需数据库迁移、无持久化格式变更（`AppDatabase.VERSION` 未动）。
- 单独回滚某一项：PERF-01 只涉及 `UsageAnalyticsClient.kt`；PERF-02 涉及 `BlogRepository.kt` + `BlogArticleDao.kt`；PERF-03 涉及 `data/work/*`、`feature/livecourse/scheduler/*`、`res/xml/*_widget_info.xml`，三组互不依赖。
- 行为副作用的唯一外部可见变化：统计同意与 `last_seen` 的持久化改为异步（进程被系统立即杀死时可能丢失最后一次心跳），以及组件不再由平台每 30 分钟刷新（改由 WorkManager 15 分钟驱动）。回滚任一文件即恢复原语义。
