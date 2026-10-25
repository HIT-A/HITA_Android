# 全仓优化交接（2026-10-25）

给接手的 Agent：本文档记录分阶段优化方案的进度、已验证的事实、工作约定和下一步。先读 [AGENTS.md](../AGENTS.md)，本文只补充它没有的内容。

## 1. 当前状态

- `main` 位于 `b0187a1`，阶段 0 全部完成，工作区干净。
- 远端：`origin` = `HIT-A/HITA_Android`（PR 与 CI 在这里），`fork` = `Chami537/HITA_Agent`（只在发布时推送）。本机没有 `upstream`。
- CI（`.github/workflows/android-ci.yml`）有两个 job：`build-and-test`（仓库检查 + JVM 单测 + Debug 构建）、`device-tests`（API 35 模拟器上跑全部 `:app` androidTest，约 7 分钟，目前 73 项全过）。
- **本机没有设备/模拟器**，设备测试只能靠 CI；结果在 artifact `device-test-results`。

## 2. 总体方案与进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 固定基线、修复数据写入失败窗口、升级路径测试、CI 设备测试 | **完成** |
| 1 | 依赖方向检查（data 不依赖 ui）、共享模型归位、深圳教务拆分类接入 | 待开始（下一步） |
| 2 | 显示目标副作用集中（DisplayScopeCoordinator）、课表写入用例入口、考试导入入口统一 | 未开始 |
| 3 | 教务一次性请求改 suspend、EASRepository 注入、聊天会话/附件拆分、AgentToolExecutor 结果语义 | 未开始 |
| 4 | 拆 MainActivity → TimetableFragment → WebViewLoginActivity → SubjectActivity | 未开始 |
| 5 | 版本目录统一、mavenLocal 开关、恢复 style 的 verifyReleaseResources、子模块测试、纯 Kotlin 模块提取、sync 模块清理 | 未开始 |

阶段 0 落地记录：

| 项 | PR / 提交 | 要点 |
|---|---|---|
| 合入他人 PR 与分享修复 | #47 #48 #50 | #48 已修附件按实际字节限长、ExamViewModel 学期观察者、会话结果归属 |
| CI 跑全部设备测试 | #51 | 顺带修了从未通过的 `ManualCourseCreationInstrumentedTest`（ViewModel 须在主线程构造） |
| 改学期起点/作息原子写入 | #52 | `TimetableRepository.change*Sync`：持 `TimetableMutationLock` → 事务 → 重读库中课表 → 写入；不再改调用方对象；失败只记日志 |
| 采纳待确认变更可恢复 | #53 | 所有课表同一事务；成功后 `clearPendingUpdates(revision)`，再记住忽略；`pendingRevision` 单调递增 |
| 已发布版本升级路径测试 | #54 | `AppDatabase.VERSION` / `ALL_MIGRATIONS`；JVM 迁移链连续性测试；设备测试 9/11/14 → 17 |
| 移除破坏性迁移回退 | #55 | 删除 `fallbackToDestructiveMigration(true)` |
| 文档纠错 | `b0187a1` | 删除不存在的 `agent/document`；会话开始改为 fetch origin；发布推 origin + fork |

## 3. 工作约定（用户确认过的偏好）

- 用中文沟通。**每项先讲方案、等用户确认再写代码**；commit、push、开 PR 前分别展示 diff 摘要和提交信息并等确认（用户可一次确认整套步骤）。
- 一项一个分支（`fix/` `refactor/` `test/` `chore/` `docs/`），基于最新 `origin/main`；合并由用户在 GitHub 上做（squash）。用户偶尔会要求纯文档改动直接推 main，以其当次指示为准。
- **提交信息与 PR 描述不加 `Co-Authored-By` 或任何 AI 署名。** Conventional commits，正文写「做了什么、为什么」。
- 修 bug 走「先红后绿」：第一个提交只放测试（必要时加不改行为的测试入口），push 后确认 CI 失败的正是预期用例，再提交修复。补现有行为的测试只需一个提交。
- CI 失败时先下载 artifact 查原因、报告用户，不跳过或删除测试，不在当前 PR 顺手修无关问题。
- AGENTS.md 与 CLAUDE.md 内容需保持同步；改 CI/流程时一并更新 README_DEV 等相关文档；`python3 scripts/check_repository.py` 和 `./project_health_check.sh` 需通过。

## 4. 已验证的技术事实与陷阱

- **数据库版本**：`cn.limpu.hita` 发布过 9（v2.4.1–v2.5.3）、11（v2.5.4–2.8.0）、14（v3.0.0–v3.0.3）、17（v3.1.0 起）；更早版本是旧包名，不会升级到本包。导出 schema 的 identityHash 与各 tag 一致。升版本时：改 `AppDatabase.VERSION`、在 `ALL_MIGRATIONS` 注册、导出 JSON；发版时把新版本加入 `AppDatabaseUpgradePathTest.SHIPPED_VERSIONS`。
- **不要用 room-testing 的 `MigrationTestHelper`**：它需要 kotlinx-serialization 1.8，应用经 lifecycle 固定在 core 1.7.3（androidTest 被约束为 strictly 1.7.3），设备上必然 `AbstractMethodError`。用 androidTest 里的 `createLegacyAppDatabase(name, version)`（`cn.limpu.hita.data.LegacyAppDatabase.kt`）建旧库。`room-testing` 依赖目前无人使用。
- **写入失败注入**：在内存 Room 上建 `CREATE TRIGGER ... BEFORE INSERT|UPDATE ON events BEGIN SELECT RAISE(ABORT, '...'); END`，可加 `WHEN NEW.timetableId = '...'`。示例见 `TimetableScheduleWriteAtomicityTest`、`TimetablePendingConfirmationRecoveryTest`。
- **隔离文件存储**：`TimetableChangeStore`、`TimetableSnapshotStore` 写 `filesDir` 下 JSON；测试用 `IsolatedApplication` 覆盖 `getFilesDir()`（见 `TimetableManualSessionProtectionTest`）。
- **锁**：`TimetableMutationLock` 的锁在 companion 里，`TimetableMutationLock()` 手动 new 与注入共用同一把；可重入（synchronized）。所有调用方均为「先锁后事务」，新代码保持该顺序。
- **JVM 测试只有 junit**：没有 Robolectric、coroutines-test、MockWebServer、mockk。涉及 Room/LiveData.postValue 的测试放 androidTest；阶段 1.3 需要加 MockWebServer，阶段 3 需要加 coroutines-test。
- **CI artifact 解析**：设备测试 XML 可能含非法字符引用（指纹里的 `\u001f`），`ElementTree` 会报错，用正则提取 `<testcase>`/`<failure>`。
- **等待 CI 的脚本**：`gh run list --json ... --jq` 输出多行 JSON 时不要接 `head -1`，用 `--jq '[...][0] | "\(.status) \(.conclusion)"'` 这类单行输出再判断，否则循环永远不结束。

## 5. 下一步：阶段 1 具体方案（尚未向用户讲解确认）

### 1.1 `chore/layer-dependency-check`

在 `scripts/check_repository.py` 加规则：`app/src/main/java/cn/limpu/hita/data/**`、`feature/timetableshare/protocol/**`、`feature/timetablecompare/**` 中禁止出现 `cn.limpu.hita.ui.`（import 与完全限定名都查）。例外名单放 `scripts/layer_allowlist.txt`；名单中的文件若已无违规引用也判失败，保证名单只减不增。在 `scripts/test_check_repository.py` 补用例。

当前违规（2026-10-25 核对）：

- import：`data/model/timetable/Timetable.kt`、`data/repository/{EASRepository,ScheduleEventCreator,SubjectRepository,TeacherInfoRepository,TimetableRepository,TimetableStyleRepository}.kt`、`data/source/dao/EventItemDao.kt`、`data/source/web/TeacherWebSource.kt`、`data/source/web/eas/{AbstractEASWebSource,BenbuClassroomParser,BenbuEASWebSource,EASWebSource,ShenzhenAcademicWebSource,ShenzhenWebAcademicParser,WeihaiEASWebSource}.kt`、`data/source/web/service/{AdditionalService,EASService,TeacherService}.kt`、`data/work/{CourseReminderWorker,ScoreReminderWorker,WidgetRefreshWorker}.kt`。
- 完全限定名：`data/repository/DisplayScopeResolver.kt` 调用 `ui.widgets.WidgetUtils` 与 `feature.livecourse...LiveCourseScheduler`（后者不在 ui 包，但属同类副作用，阶段 2.1 处理）。
- `feature/timetableshare`、`feature/timetablecompare` 目前没有直接引用 ui。

### 1.2 `refactor/data-models-out-of-ui`

被 data 引用的 ui 符号：`ui.eas.classroom.ClassroomItem`（10 处）、`BuildingItem`（9）、`ui.search.teacher.TeacherSearched`（3）、`ui.timetable.detail.TeacherInfo`（2）、`TimetableFragment.Companion.WEEK_MILLS`（2）、`ui.main.timetable.TimetableStyleSheet`、`CourseBubbleStyle`、`ui.event.add.CourseTime` 各 1。纯模型和常量移到 `data/model/...`，只改包名与 import。Worker 对 `MainActivity`、`ScoreInquiryActivity`、`WidgetUtils` 的引用属平台边缘，留在例外名单。移动前检查 `app/proguard-rules.pro` 是否按包名 keep、Gson/Room/Parcelable 是否依赖类名。

### 1.3 / 1.4 深圳教务拆分类接入

- `ShenzhenTimetableWebSource`（约 1650 行，57 个方法）与 `ShenzhenAcademicWebSource`（约 1530 行，21 个方法）来自 `de20418`，全仓无调用；生产只用 `EASWebSource`（约 4650 行）。拆分后 `EASWebSource` 有 2 次提交、拆分类 1 次，漂移较小。
- 步骤：一次性脚本逐方法对比两份实现并把差异贴进 PR → 将 `EASWebSource` 新修复补到拆分类 → 引入 MockWebServer，以脱敏的深圳响应做新旧对照测试 → `EASWebSource` 持有会话并委托给拆分类、删除重复实现。课表、学业各一个 PR。
- `EASRepository` 直接 `new EASWebSource(...)`（含 token 刷新与凭据回调），接入时不改构造方式，注入留到阶段 3。动 EAS 必须同时跑本部、威海的解析测试。

## 6. 遗留事项（未排期）

- `TimetableSourceApplier.applyHeldBatch` 在事务内调用 `changeStore.clearTerm`（写文件），仅在事务提交本身失败时有问题，建议单独记 issue。
- `AgentChatViewModel` 的 `ioExecutor` 没有 `onCleared`/shutdown（阶段 3.3）。
- `fix/audit-stability`、`fix/experiment-course-name` 等已合并的远端分支可按「合完就删」清理（需用户确认）。
- 原方案文档中的数量统计（86 处 `Thread {`、22 个 data→ui 文件等）是 2026-10-25 的快照，开工前重新核对。
