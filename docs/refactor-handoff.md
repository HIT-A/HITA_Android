# 全仓优化交接（2026-10-26，#74 合并后）

本文记录分阶段优化的已交付结果、证据和剩余范围。项目约定见 [AGENTS.md](../AGENTS.md)，阶段 3–5 的完整验收台账见 [refactor-stage345-plan.md](refactor-stage345-plan.md)。历史测试数对应各 PR head，不代表合并后的 main 已另行验证。

**阶段 0、1、2 已完成。阶段 3.1 已合并教学楼查询 #65、考试查询 #66、空教室远端查询 #67、空教室 Repository 与页面迁移 #68，以及学期远端协程能力 #70。阶段 5.V/M/R 构建改造已合并 #71，5.T 子模块测试与兼容修复已合并 #72；Subject 整链已合并 #73；纯 JVM 核心/旧 sync 清理已合并 #74，学期缓存/全部消费端已完成本地整链验证，其余功能链继续实施；阶段 3 其余范围、阶段 4 与阶段 5 尚未完成。**

## 1. 当前状态

- 用户于 `2026-10-26 16:00:18 GMT+8` 合并 [PR #68](https://github.com/HIT-A/HITA_Android/pull/68)，合并提交 `fd17e5e773b1ad6f16cd9a267227c595d34c73fa`；已 fetch 验证。#68 修复 head 为 `6ae7c7011eb53468771494789c1e8fc2d00607b1`，提交树与该 main 相同。
- #68 [CI 40288044464](https://github.com/HIT-A/HITA_Android/actions/runs/40288044464) 两个 job 成功。实际设备 XML 独立核实 481 项全部通过：原 378 + 40 项兼容基线 + 5 项原失败回归 + 58 项新增能力测试；无失败、错误、跳过、重复或缺漏。
- #68 本地 JVM 795 项中 794 通过、1 项既有可选 HOA 联网测试跳过；Debug 与 androidTest APK 构建、仓库/分层/health/consistency 检查通过。CI 的 JVM 步骤成功，但 CI 未上传 JVM XML，不能把本地精确计数说成 CI XML 结果。
- [PR #70](https://github.com/HIT-A/HITA_Android/pull/70) 已合并为 `b8a4cea55faab714baccceb1586ecc652cb3f46f`。head `72751747ba5acccfb8abf4240d3b6f1700445e66` 的 [CI 40299381154](https://github.com/HIT-A/HITA_Android/actions/runs/40299381154) 两个 job 成功，原始设备 XML 独立核实 607 项全部通过；这是 PR head 的结果，不是合并后的 main 另跑一次验证。
- 阶段 3.1 已交付上述查询能力；学期缓存与全部消费端、其余教务请求、Repository 内部依赖注入、聊天拆分及 Agent 执行语义改造仍须按台账逐项实施与验证。
- 完整验收文档已通过 #69 合并为 `f48cc4b`；对应 head `d89c58d` 的 [CI 40292512701](https://github.com/HIT-A/HITA_Android/actions/runs/40292512701) 两个 job 成功，下载的实际设备 XML 为原 481 项全部通过。后续每批从最新 `origin/main` 新建分支，不在已合并功能分支追加工作。
- 用户此前保留的交接文档改动已备份到 `/tmp/hita-stage345-skdpckp3/handoff-before-update.md`，本次明确要求“补齐之后继续”，因此将既有文档更新与 #66–#68 补充一并纳入文档 PR。
- **当前目标的新增授权**：用户明确要求“你自己开 PR、自己审核、自己合并，不要打扰我，直到做完 3、4、5”。这覆盖本目标内的方案、实现、文档、commit、origin push、PR、审核、必要分支同步及合并；不再逐批请求确认。它覆盖本目标，并非修改其他任务的默认授权规则。仍不发布 release、不推 fork。
- 远端 `origin` = `HIT-A/HITA_Android`，`fork` = `Chami537/HITA_Agent`。实现按批次独立审查，完整验证后合并；存在缺陷时先验证失败基线，禁止删改旧断言来变绿。
- 本地测试使用 API 35 模拟器及受控 HTTP/数据源，尚未进行真实校园账号联调；设备与隔离 ADB 的运行状态以当前检查为准。

## 2. 总体方案与进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 固定基线、修复数据写入失败窗口、升级路径测试、CI 设备测试 | **完成** |
| 1 | 依赖方向检查、共享模型归位、深圳教务拆分类接入 | **1.1–1.4 全部合并**（#56–#61） |
| 2 | 显示目标副作用、普通课表写入用例、考试导入入口 | **2.1–2.3 全部合并**（#62–#64），本地及 PR CI 验证完成 |
| 3 | 教务一次性请求改 suspend、EASRepository 注入、聊天会话/附件拆分、AgentToolExecutor 结果语义 | **进行中**：#65–#68、#70 已合并；完整剩余范围见验收台账 |
| 4 | 拆 MainActivity → TimetableFragment → WebViewLoginActivity → SubjectActivity | Subject 已合并 #73 并核验完整 CI；Main 链实现与生命周期修复中，其余入口待做 |
| 5 | 版本目录统一、mavenLocal 开关、恢复 style 的 verifyReleaseResources、子模块测试、纯 Kotlin 模块提取、sync 模块清理 | **进行中**：5.V/M/R 已合并 #71，5.T 已合并 #72；纯 JVM 模块/旧 sync 清理合批已合并 #74，最终审计待做；其余进度见台账 |

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

阶段 1–3.1 已交付记录（下列各项均已合并）：

| 项 | PR / 功能提交 / 合并提交 | 要点 |
|---|---|---|
| 1.1 分层依赖检查 | [#56](https://github.com/HIT-A/HITA_Android/pull/56) / `e0d1cda` / `42e3029` | 精确路径与引用例外；PR 对照 base 只准删减；检查器 21 项测试 |
| 1.2 共享模型迁出 UI | [#57](https://github.com/HIT-A/HITA_Android/pull/57) / `7e4628e` / `1f66f31` | 共享模型和周时长常量移入 data/model；例外 33 → 4 |
| 1.3 深圳课表旧入口基线 | [#58](https://github.com/HIT-A/HITA_Android/pull/58) / `09169a7` / `0337cb9` | 新增 20 项 MockWebServer 契约，设备总计 93 项通过 |
| 1.3 深圳课表委托 | [#59](https://github.com/HIT-A/HITA_Android/pull/59) / `5aece74` / `39d259f` | 7 个入口委托给 ShenzhenTimetableWebSource；原 93 项全通过 |
| 1.4 深圳学业旧入口基线 | [#60](https://github.com/HIT-A/HITA_Android/pull/60) / `f2c8b73` / `a71cee4` | 新增 46 项契约；原 93 + 新 46，共 139 项通过 |
| 1.4 深圳学业委托 | [#61](https://github.com/HIT-A/HITA_Android/pull/61) / `61999ae` / `501e989` | 16 个入口委托给 ShenzhenAcademicWebSource；同一组 139 项通过 |
| 2.1 显示目标副作用集中 | [#62](https://github.com/HIT-A/HITA_Android/pull/62) / `6ece7d7` / `03f175f` | 5 个选择入口统一到 Coordinator；例外 4 → 3；设备 153 项全通过 |
| 2.2 普通课次/活动写入 | [#63](https://github.com/HIT-A/HITA_Android/pull/63) / 基线 `3451c89`、修复 `b24a13f` / `4f5b183` | 统一写入及成功后刷新；真实 CI 五项先红后绿，最终设备 170 项全通过 |
| 2.3 考试导入入口统一 | [#64](https://github.com/HIT-A/HITA_Android/pull/64) / `9d3c784` / `4ca2093` | 三条链共享映射/去重/写入；本地构建与 JVM 通过，CI 两个 job 成功，设备 183 项全通过 |
| 3.1 首批教学楼单次查询 | [#65](https://github.com/HIT-A/HITA_Android/pull/65) / `7263896` / `2bc9fcb` | 三校区 suspend 入口与旧接口共享请求核心；ViewModel 管理取消；本地及 PR CI 设备 227 项全通过 |
| 3.1 考试查询与页面生命周期 | [#66](https://github.com/HIT-A/HITA_Android/pull/66) / 基线 `791a5ca`、修复 `645da38` / `d4fa6a6` | 三校区共享请求核心，页面取消与代次保护；CI 三项先红后绿，最终设备 298 项全通过 |
| 3.1 空教室远端单次查询 | [#67](https://github.com/HIT-A/HITA_Android/pull/67) / `8b3786f` / `126b801` | 三校区空教室 suspend 入口、请求快照和取消边界；本地及 PR CI 设备 378 项全通过 |
| 3.1 空教室缓存与页面协调 | [#68](https://github.com/HIT-A/HITA_Android/pull/68) / 基线 `00a2c77`、修复 `6ae7c70` / `fd17e5e` | 冷 Flow、事务缓存、选择/默认周/重试/取消协调；CI 五项先红后绿，最终设备 481 项全通过 |
| 3.Q1 学期远端单次查询 | [#70](https://github.com/HIT-A/HITA_Android/pull/70) / 基线 `70a0210`、实现 `7275174` / `b8a4cea` | 三校区普通/研究生与深圳 Web 共用请求核心；取消及参数快照契约；本地及 PR CI 设备 607 项全通过 |

## 3. 工作约定与本目标授权

用户已于 2026-10-26 调整为完整功能链交付、分层验证、独立工作并行及集中记录；具体执行规则见 [验收台账](refactor-stage345-plan.md)。已通过的检查按实际源码和覆盖范围复用，最终交付标准与全部阶段范围不变。

- 中文沟通。一般任务先给方案再实施、提交推送合并另行确认；**当前阶段 3–5 目标已有用户完整授权，执行时不再重复询问**。每批仍须先记录范围、兼容契约、验收方法及边界，然后实现、独立审查、验证、提交、PR 和合并。
- 从最新 `origin/main` 开独立分支，一批一个明确职责。允许为并行工作建立隔离 worktree，根 Agent 统一协调构建、提交和依赖顺序，避免多个任务修改同一工作树。
- commit message 使用 conventional commits；提交与 PR 描述不加 `Co-Authored-By` 或 AI 署名。只推 origin；不发 release、不推 fork。
- 缺陷修复先红后绿：测试基线应在旧实现准确复现，本地定向确认失败原因后保留业务断言修复；最终 PR CI 全量验证，涉及 CI 环境差异时才额外复现失败；纯重构的兼容基线正常通过，不制造假失败。
- 保留原测试身份和断言。harness 适配单独审查；所有新测试必须实际执行。CI 有失败就下载 artifact 排查，不跳过或删除用例，不以静态测试数代替实际 XML。
- 每个 PR 创建后附加到当前聊天；审核和 CI 通过后由 Agent 合并。每批更新台账中实际 SHA、PR、验证及边界；最终逐项核验阶段 3、4、5 全部验收项，不能用少量测试或目录拆分代替目标完成。
- 修改 AGENTS.md 或 CLAUDE.md 时同步另一份；修改项目结构、CI、构建时同步 README_DEV 等说明。保留历史 Room schemas，不做破坏性迁移。
- 阻塞网络不能保证即时中断；构建/模拟器/受控 HTTP 证据不能代替真实账号或设备验收。不虚报未执行的检查。

## 4. 已验证的技术事实与陷阱

- **数据库版本**：`cn.limpu.hita` 发布过 9（v2.4.1–v2.5.3）、11（v2.5.4–2.8.0）、14（v3.0.0–v3.0.3）、17（v3.1.0 起）；更早版本是旧包名，不会升级到本包。导出 schema 的 identityHash 与各 tag 一致。升版本时：改 `AppDatabase.VERSION`、在 `ALL_MIGRATIONS` 注册、导出 JSON；发版时把新版本加入 `AppDatabaseUpgradePathTest.SHIPPED_VERSIONS`。
- **不要用 room-testing 的 `MigrationTestHelper`**：它需要 kotlinx-serialization 1.8，应用经 lifecycle 固定在 core 1.7.3（androidTest 被约束为 strictly 1.7.3），设备上必然 `AbstractMethodError`。用 androidTest 里的 `createLegacyAppDatabase(name, version)`（`cn.limpu.hita.data.LegacyAppDatabase.kt`）建旧库。`room-testing` 依赖目前无人使用。
- **写入失败注入**：在内存 Room 上建 `CREATE TRIGGER ... BEFORE INSERT|UPDATE ON events BEGIN SELECT RAISE(ABORT, '...'); END`，可加 `WHEN NEW.timetableId = '...'`。示例见 `TimetableScheduleWriteAtomicityTest`、`TimetablePendingConfirmationRecoveryTest`。
- **隔离文件存储**：`TimetableChangeStore`、`TimetableSnapshotStore` 写 `filesDir` 下 JSON；测试用 `IsolatedApplication` 覆盖 `getFilesDir()`（见 `TimetableManualSessionProtectionTest`）。
- **锁**：`TimetableMutationLock` 的锁在 companion 里，`TimetableMutationLock()` 手动 new 与注入共用同一把；可重入（synchronized）。所有调用方均为「先锁后事务」，新代码保持该顺序。
- **测试环境**：JVM 侧使用 JUnit，没有 Robolectric、coroutines-test、MockWebServer、mockk。MockWebServer 4.12.0 已在阶段 1.3 加入 androidTest，覆盖深圳 HTTP/LiveData 契约；Room/LiveData.postValue 测试也放 androidTest。阶段 3.1 首批复用现有依赖，未增加 coroutines-test；后续按具体方案确认。
- **CI artifact 解析**：优先直接用 `ElementTree` 解析实际 XML，并核对 testcase 完整身份、failure/error/skipped、重复项及 suite 计数。历史报告日志曾含 XML 1.0 非法字符引用；仅在必要时修复日志字符并记录修复，不改变身份或状态。#65 的本地与 CI XML 均可直接解析，无须修复。
- **等待 CI 的脚本**：`gh run list --json ... --jq` 输出多行 JSON 时不要接 `head -1`，用 `--jq '[...][0] | "\(.status) \(.conclusion)"'` 这类单行输出再判断，否则循环永远不结束。
- **GitHub 工具权限**：此前连接器创建 PR 返回 403 `Resource not accessible by integration`，改用本机 `gh pr create --body-file` 成功；当前常用本机 gh 写入；连接器权限以后按实际结果判断。
- **旧版 gh 编辑 PR**：本机 `gh pr edit` 因 GraphQL 的 `repository.pullRequest.projectCards` 废弃报错。成功替代方式为 `gh api --method PATCH repos/HIT-A/HITA_Android/pulls/<number> --input <json-file>`，JSON 文件形如 `{"body":"实际正文"}`，用脚本序列化真实换行，不把正文拼进 shell。不要因为 CLI 编辑失败而重复创建 PR。

## 5. 阶段 1 交付与证据

### 1.1 `chore/layer-dependency-check`

已在 `scripts/check_repository.py` 加规则：`app/src/main/java/cn/limpu/hita/data/**`、`feature/timetableshare/protocol/**`、`feature/timetablecompare/**` 的 Kotlin/Java 主源码中禁止出现 `cn.limpu.hita.ui.` 引用（后两者相对 `app/src/main/java/cn/limpu/hita/`；import 与完全限定名都查）。源码文本检查也覆盖注释和字符串，不做语言语义或间接依赖分析。

用户确认的补强：`scripts/layer_allowlist.txt` 按「文件路径 + 完整引用」精确豁免；不绑定行号。同文件新增其他引用、重复/无效/过期例外均报错。PR CI 使用 base SHA 比较例外集合，只能删减，不能新增或替换；首次建立名单时从基线源码核验已有引用，不能直接放行当前源码中的新增依赖。基线读取失败即检查失败。本地用 `--base-ref origin/main` 启用相同比较，普通检查仅校验当前状态。

已在 `scripts/test_check_repository.py` 补检查器用例，同步 CI、README_DEV 和 AGENTS/CLAUDE；本项未改 Android 业务代码。8 个文件共新增 398 行、删除 13 行，规则与测试同一提交，commit/push/PR 均已获用户确认并完成。具体运行命令见 [开发指南](../README_DEV.md)。

本地验证（2026-10-25）：Python 测试 21 项通过（原有 4 项 + 新增 17 项）；带 `--base-ref origin/main` 的仓库检查、`project_health_check.sh`、`code_consistency_check.sh` 通过；修改的 CI YAML 可解析、Bash 片段语法通过。`./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain` 成功，125 个任务均为 `UP-TO-DATE`，复用了现有单测/构建结果，没有重新执行 JVM 测试。

PR CI 验证：[运行 40177793793](https://github.com/HIT-A/HITA_Android/actions/runs/40177793793)，head SHA 为 `e0d1cdab57c88af6fbc43e03387b21498a38d4f1`。`build-and-test` 成功（约 6 分钟），包含新增基线获取、Python 测试/仓库检查、JVM 单测和 Debug 构建；`device-tests` 成功（约 7 分钟）。下载 `device-test-results` 后从 XML 核对：73 个 testcase，0 failure、0 error、0 skipped。报告本会话下载到 `/tmp/hita-layer-ci-40177793793`，临时目录可能清理；需要时从该 run 重新下载。PR 描述已补最终验证结果，未加任何 AI 署名。

历史基线（2026-10-25 在 `e13da72` 核对，以下不是当前剩余依赖）：共 23 个文件、33 处引用，其中 22 个文件含 32 条 import，另 1 个文件使用完全限定名。初始名单为 33 条精确例外。

- import：`data/model/timetable/Timetable.kt`、`data/repository/{EASRepository,ScheduleEventCreator,SubjectRepository,TeacherInfoRepository,TimetableRepository,TimetableStyleRepository}.kt`、`data/source/dao/EventItemDao.kt`、`data/source/web/TeacherWebSource.kt`、`data/source/web/eas/{AbstractEASWebSource,BenbuClassroomParser,BenbuEASWebSource,EASWebSource,ShenzhenAcademicWebSource,ShenzhenWebAcademicParser,WeihaiEASWebSource}.kt`、`data/source/web/service/{AdditionalService,EASService,TeacherService}.kt`、`data/work/{CourseReminderWorker,ScoreReminderWorker,WidgetRefreshWorker}.kt`。
- 完全限定名：`data/repository/DisplayScopeResolver.kt` 调用 `ui.widgets.WidgetUtils` 与 `feature.livecourse...LiveCourseScheduler`（后者不在 ui 包；这两项副作用已在阶段 2.1 迁出）。
- `feature/timetableshare`、`feature/timetablecompare` 在该基线没有直接引用 ui。

### 1.2 共享模型迁出 UI（已合并）

`ClassroomItem`、`BuildingItem` → `data/model/classroom`；`TeacherSearched` → `data/model/teacher`；`TeacherInfo`、`CourseTime`、`TimetableStyleSheet`（含 `CourseBubbleStyle`）与 `WEEK_MILLS` → `data/model/timetable`。PR #57 为 46 文件、+70/-85；主要是模型位置与 import 修改，移除对应 29 条分层例外。

本项完成后名单为 4 条；阶段 2.1 又移除 Resolver 例外。当前剩余的 3 条精确例外为 `CourseReminderWorker → MainActivity`、`ScoreReminderWorker → ScoreInquiryActivity`、`WidgetRefreshWorker → WidgetUtils`。它们属于后续平台边界工作，不得加宽例外来绕过检查。

### 1.3 深圳课表委托（已合并）

先 #58 建立 20 项旧入口行为契约（5 份合成响应夹具，API/Web 独立 MockWebServer 地址），再 #59 以同一组断言验证委托。`EASWebSource` 的 7 个课表入口委托给 `ShenzhenTimetableWebSource`，HTTP、Cookie、token 仍由宿主提供；该阶段 `EASWebSource` 从 4657 行降到 3131 行。

- 基线 CI [40188586640](https://github.com/HIT-A/HITA_Android/actions/runs/40188586640)，head `09169a7`：两个 job 通过，下载报告核实 93 项全通过。
- 实现 CI [40190891280](https://github.com/HIT-A/HITA_Android/actions/runs/40190891280)，head `5aece74`：两个 job 通过，同一组 93 项身份与结果一致，失败/错误/跳过均为 0。
- 55 个方法的静态差异与必要回调适配已逐项整理进 #59 PR；临时记录 `/tmp/hita-shenzhen-timetable-46ait7j3/final-method-comparison.md`。不要重新运行该目录的历史生成脚本。

### 1.4 深圳学业委托（已合并）

先 #60 新增 46 项旧入口契约：目录 14、年级课程 3、成绩 15、培养方案/学分 14。复用现有 `CourseSelectionExecutorTest` 的 24 项一次性提交、取消、超时、重定向与 Cookie 行为测试。多阶段 LiveData 测试用请求锁与持续观察，不用只读取最后一项的方式漏掉中间状态。

#61 将 16 个公开学业入口委托给 `ShenzhenAcademicWebSource`。只改两份生产文件，+458/-1947；`EASWebSource` 3131 → 1281 行，Academic 1529 → 1890 行。29 个业务方法中 14 个 token 完全相同，15 个仅必要回调/默认参数/override 适配；HTTP/登录/鉴权、教室/考试/个人信息及 7 个课表委托保持。会话与 Cookie 合并留宿主，Academic 只接收窄函数能力，不持有整个 EASWebSource。

- 基线 CI [40193994324](https://github.com/HIT-A/HITA_Android/actions/runs/40193994324)，head `f2c8b73`：两个 job 通过，139 项设备测试全通过。
- 实现 CI [40195578625](https://github.com/HIT-A/HITA_Android/actions/runs/40195578625)，head `61999ae`：两个 job 通过，139 项身份和状态与基线完全相同，失败/错误/跳过均为 0。
- 本地实现验证：JVM 755 项中 754 通过、1 项既有 opt-in `HoaMultiProjectCompatibilityTest` 跳过；Debug 与测试 APK 构建、Python 检查器 21 项、仓库/分层/health/consistency 检查通过。
- 证据：`/tmp/hita-shenzhen-academic-source-6fmkggcq/verification.md`、`final-method-comparison.md`、`device-comparison.json`；基线记录 `/tmp/hita-academic-baseline-vr3sgzjl/verification.md`。

这两项完成的是职责和实现拆分；Android LiveData、EASToken、Jsoup 仍在，不是纯 Kotlin 模块抽取。`EASRepository` 仍直接构造 `EASWebSource`，依赖注入留阶段 3。

## 6. 阶段 2 验证结果与收尾

### 2.1 显示目标副作用集中（已合并）

`DisplayScopeResolver` 负责持久化选择、解析范围和回退；`DisplayScopeSelector` 由 `DisplayScopeCoordinator` 实现，保持保存选择 → Widget 刷新 → LiveCourse 调度的顺序、线程和异常传播。MainActivity、两个 ViewModel、Repository 自动分组共 5 个选择入口已接入；手动构造 Repository 通过窄 EntryPoint 延迟获取接口。

PR #62 为 17 文件、+692/-22，例外 4 → 3。本地 JVM 760 项中 759 通过、1 项既有可选测试跳过；新增 Coordinator 5 项均通过，Debug 与测试 APK 构建通过。

CI [40198232504](https://github.com/HIT-A/HITA_Android/actions/runs/40198232504)，head `6ece7d73b453bcc608fdbaf7e81dca684023eebf`：两个 job 成功。下载 API 35 报告逐项核实原 139 + 新 14，共 153 项全部通过，无失败、错误或跳过。证据在 `/tmp/hita-display-scope-implementation-3h__x_wy/verification.md` 与 `device-comparison.json`。上述均为 PR head 结果，不代表合并后的 main 另跑了一次 CI。

### 2.2 普通课次/活动写入（已合并，先红后绿完成）

已合并实现：

- `TimetableWriteStore` 提供同步持久化与原 Repository 入队能力；`TimetableWriteUseCase` 编排写入完成、Widget 刷新和结果回调，Factory 装配 Android 平台能力。
- 5 个 ViewModel 和 Agent 使用统一普通写入入口。普通 UI 写入复用已有 TimetableRepository 队列；整门课删除显式复用同一个 SubjectRepository 队列，防止与“读课程 → REPLACE 保存”的颜色重置交错而恢复已删除课程。
- 新增课程/活动时，subject、events 和必要的课表延长在同一锁与 Room 事务内；重读数据库当前课表，保留最新名称/groupId/已有更长结束范围，不修改调用方旧对象。
- 删除 Popup 1 处、TimetableFragment 3 处入队即刷新的调用，改为真实写入完成后刷新。刷新失败只记日志，不把已落库结果改为失败；Agent 同步等落库完成后回应。
- 保留 UPDATE/ABORT、整门课删除 subject → events 的既有非事务语义；起点/作息目标不存在返回 false、不刷新；弹窗关闭/Toast 交互未扩大为完整保存状态重构。HApplication 的 events 观察继续负责 LiveCourse，不重复添加调度。

本地验证：完整实现 18 文件；Gradle `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` 成功（4m23s，26 executed / 132 up-to-date）。JVM 776 项中 775 通过、1 项既有跳过；新增 UseCase 16 项全通过。仓库/分层基线、health、consistency、diff 检查通过。

真实 PR CI 验证：

- 测试基线 `3451c895a1fa4ae95bfc78f692b63cba747005e4` 的 [CI 40201120033](https://github.com/HIT-A/HITA_Android/actions/runs/40201120033)：build-and-test 成功，device-tests 按预期失败。下载 XML 核实 164 项中 159 通过、正好 5 项失败，0 错误/跳过；原 153 项全部保留且通过。
- 5 项预期失败分别为：`extensionFailureRollsBackNewSubjectAndAllEvents`、`extensionFailureRestoresPreviouslySavedSubject`、`extensionFailureLeavesCallerObjectsUntouched`、`extensionKeepsNameAndGroupChangedAfterCallerLoaded`、`staleCallerCannotShortenAnAlreadyExtendedTimetable`。
- 修复 `b24a13ffec701862bafc79bfd5481ef1f5d70299` 的 [CI 40201764772](https://github.com/HIT-A/HITA_Android/actions/runs/40201764772)：两个 job 成功。实际 XML 为 170 项全通过，0 失败/错误/跳过，原 153 项与新增 17 项完整保留；上述五项均转绿。
- 结果对应两个 PR head，不能表述为合并后的 main 另跑 CI。用户随后合并 #63，合并提交 `4f5b1832d81d219596a58cce97759a62d86c5577`，时间 `2026-10-25 22:27:15 GMT+8`。

证据目录 `/tmp/hita-stage22-implementation-kk25pevr/` 中有 `verification.md`、`submission-state.json`、`baseline-device-comparison.json`、`final-device-comparison.json`；设备报告分别在 `/tmp/hita-stage22-baseline-ci-40201120033` 与 `/tmp/hita-stage22-final-ci-40201764772`。两次提交仅推 origin，交接文档未夹带，合并由用户操作。

### 2.3 考试导入入口统一（PR #64，已合并）

已批准方案在 `/tmp/hita-stage2-completion-plan-u0x0ixw6/plan.md`。提交 `9d3c784809e5f49fd63feba8d8f9b2bc743cc41e` 实现如下，独立静态 review 未发现剩余问题：

- 新增 `ExamImportRepository`，统一详情单条、考试页批量与教务自动导入的 mapper → 身份键去重 → 逐条插入。手动入口 `importIntoTimetable`，自动入口 `importAutomatically`，去重范围用命名 enum 表达。
- 手动入口事务内重新校验目标，按目标课表去重；保留单条/批量原成功、重复、无效、缺失目标提示及统计。手动第二条失败须整批回滚。
- 自动入口按所有课表去重，保留 tokenStateLock → TimetableMutationLock、会话校验、完成回调与现有非批次事务；第二条失败应保留已写第一条。
- 备忘录经手动入口仍保留 EXAM + EAS_IMPORT。不得套用普通新增日程的课表延长，不新增原本没有的 Widget 刷新或取消语义。
- ViewModel 保留现有 ImportResult 的 total/success/skipped 接口；详情保留原 Thread、host 有效性检查、Toast 与 dismiss 行为，缺失目标异常在 UI 映射回原中文提示。仅把触碰到的 3 条提示原样移入 strings，没有改其他 UI。
- 新增 `ExamImportRepositoryInstrumentedTest` 的 13 项 Room 测试，覆盖目标内/全局去重差异、批次重复、无效计数、单条结果、旧名称与空白规范化、手动整批回滚/自动保留首条、缺失目标（含空批次）、空列表、当前目标名称及不延长课表、备忘录映射、非 EXAM 事件不参与去重。现有 mapper JVM 测试保留。

实际改动 6 文件、+420/-103（5 个生产/资源文件 + 1 个 androidTest 文件）。本地验证已完成：

- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain` 成功，4m19s；158 个任务中 27 executed、131 up-to-date。
- JVM 776 项：775 通过、0 失败/错误、1 项既有 opt-in `HoaMultiProjectCompatibilityTest` 跳过；现有 `ExamEventMapperTest` 3 项通过。
- 仓库检查（含 `--base-ref origin/main`）、health、consistency、`git diff --check` 通过；13 项新增 Room 测试编译通过。本地无设备，尚无本阶段本地设备执行结果。

用户确认后已提交、推送 origin 并创建 PR #64。CI [40203439337](https://github.com/HIT-A/HITA_Android/actions/runs/40203439337) 对应上述 head `9d3c784`，`build-and-test` 与 `device-tests` 均成功。已下载 API 35 模拟器的 `device-test-results`，直接解析 XML 并独立复核：183 项全部通过，0 失败/错误/跳过；原 170 项完整保留且状态一致，新增 13 项与冻结源码清单精确匹配，无重复、缺失或额外项。PR 描述已补实际结果并转为可评审状态。用户随后于 `2026-10-25 22:51:29 GMT+8` 合并，合并提交为 `4ca2093eb68746c35093d614e608a9f9796d3fa9`。上述测试证据对应 PR head，不表示合并后 main 的 CI 已另外核验。

证据目录 `/tmp/hita-stage23-implementation-0ef3_pw_/`：`build.log`、`verification.md`、`review.md`、`device-comparison.json`、`independent-device-audit.json` 与 `submission-state.json`；原始报告位于 `/tmp/hita-stage23-ci-40203439337`。状态文件已更新为 PR 64、run 40203439337、`draft=false`、`verified=true`、`merged=true`。

2.2/2.3 均不包含 EAS 全量导入/快照采纳、ICS/口令导入、关注教学班/选课计划及课表分组管理的全面统一，不应宣称全仓所有写入和副作用已集中。

阶段 2 的实现、独立 PR、本地检查、各自 CI 报告核验及用户合并均已完成。随后阶段 3.1 分批实施，#65–#68 的交付记录见下文；当前按用户新增授权继续阶段 3–5。

## 7. 阶段 3.1 首批：教学楼单次协程查询（PR #65，已合并）

已批准方案在 `/tmp/hita-stage31-plan-vspjy2ce/plan.md`。本批仅迁移无缓存的教学楼列表查询；学期、成绩及空教室的缓存/多阶段发布仍保留。提交 `7263896e2449a9a49e33a13ed0b19ef7df911f38` 的实现：

- `EASService`/`EASRepository` 增加 `getTeachingBuildingsOnce`；三校区提取共享同步请求核心，suspend 入口在 `Dispatchers.IO` 执行，并传播 `CancellationException`。
- 旧 `getTeachingBuildings` LiveData 入口供同步 `SearchEmptyClassroomTool` 继续使用，保留 eager Thread/Executor、校区初始状态、等待方式及原有异常终态映射。两种入口共用解析和请求逻辑，不通过 observeForever 等待新入口。
- `TeachingBuildingsRequest` 捕获同一次 token、校区 service 与初始状态；未登录不发请求。`TeachingBuildingsLiveData` 保留 switchMap 激活/重放时机，以 `viewModelScope` 管理 Job，刷新取消旧任务，取消检查及代次保护避免晚到结果覆盖，ViewModel 清理后停止交付。
- 深圳保留 API/Web 路由与 Web 初始化顺序、字段、Cookie 回调及错误映射；本部保留 1→2 顺序和原有重复/空 id；威海保留 01→1/2 回退、空 id 过滤和去重。测试专用 host override 仅作用于本部/威海教学楼 HTTP 地址。
- 取消发生后不交付结果，不开始下一教学楼初始化/跨校区步骤；现有 Jsoup 阻塞 socket 不保证立即终止，`authedFormPost` 内部同步重登/重试链仍可能完成后才检查取消。不得扩大宣传为每个认证子请求都可即时取消。

验收顺序与实际结果：

- 先在旧实现上新增 18 项契约（深圳 10、本部/威海 8），编译 APK 后在本地 API 35 模拟器实际运行，18 项全通过并冻结报告；随后才应用协程迁移。
- 实现新增 suspend HTTP/取消 15、UI 生命周期 11、JVM 请求 7，连同基线共新增 51 项测试，无新增 Gradle 依赖。UI 测试在轻量测试 ViewModel 中执行生产 helper；实际 EmptyClassroomViewModel 接入通过静态复核和编译，未做真实账号页面手工验收。
- 本地 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` 成功（4m14s）；JVM 783 项中 782 通过，1 项原有可选 HOA 联网测试未启用而跳过。仓库/分层基线、health、consistency 与 diff 检查通过。
- 本地 `:app:connectedDebugAndroidTest` 实际成功（34s），227 项全部通过；独立解析原始 XML，原 183 + 基线 18 + suspend 15 + UI 11 精确匹配，零失败/错误/跳过/重复/缺漏。HTTP 响应全部为合成夹具，未联系真实教务账号。
- 用户确认实际 diff 后已 commit、push origin、创建 PR #65；[CI 40257115987](https://github.com/HIT-A/HITA_Android/actions/runs/40257115987) 对应 head `7263896`，两个 job 均成功。下载 [device-test-results](https://github.com/HIT-A/HITA_Android/actions/runs/40257115987/artifacts/12623386625) 并独立核对原始 XML，同样 227 项全通过、身份精确匹配。结果已写入 PR 描述。
- 用户于 `2026-10-26 10:10:04 GMT+8` 合并 #65，合并提交 `2bc9fcb7c6ce7d3adfa9216a8b8dd4f845b4e6b0`。上述 CI 是 PR head 的证据，不是合并后 main 的另一次验证。

证据目录 `/tmp/hita-stage31-implementation-hu3093pc/`：`verification.md`、`state.json`、`final-change-inventory.json`、`baseline-device-results/`、`final-jvm-audit.json`、`final-independent-device-audit.json`、`submission-independent-audit.json`、`ci-40257115987/device-independent-audit.json` 与原始设备报告。本地模拟器的启动/清理证据在 `emulator/`。临时目录可能清理，PR 与 Actions 是持久证据入口。

## 8. 阶段 3.1 后续已交付：#66–#68

### #66 考试查询与页面生命周期

三校区考试入口共用原请求/解析核心，新增 suspend 能力；实际 ExamViewModel 拥有查询 Job，刷新/clear 取消旧查询，并在真正 Main 交付时校验请求代次。覆盖同步重入、备忘录变化及排队旧结果。自动课表导入保留旧入口和八秒等待，不能宣称所有考试调用链已取消化。

基线 `791a5ca` 的 [CI 40261668427](https://github.com/HIT-A/HITA_Android/actions/runs/40261668427) 为 269 通过、准确 3 项回归失败；修复 `645da38` 的 [CI 40262491974](https://github.com/HIT-A/HITA_Android/actions/runs/40262491974) 两个 job 成功，实际设备 XML 298 项全部通过。本地 JVM 792 通过、1 项既有跳过。PR 合并为 `d4fa6a6f5a9cc1a7dfc8e139dfaf6e30a34c8fce`。

### #67 空教室远端单次查询

三校区旧入口与 `queryEmptyClassroomOnce` 复用请求核心，完整捕获学期七字段、教学楼和周次。保留 API/Web、初始化顺序、Cookie/认证、解析与部分结果行为；取消异常传播，不启动取消后的下一独立请求。该批未改变 Repository 缓存或页面。

功能 head `8b3786f4a5baf87fd52a3e9bd6168f960b572955`，合并为 `126b8019d2b687901839c30db6c7843881badbab`。[CI 40266501059](https://github.com/HIT-A/HITA_Android/actions/runs/40266501059) 两个 job 成功，实际设备 XML 378 项全通过；本地 JVM 794 通过、1 项既有跳过。证据 `/tmp/hita-classroom-suspend-implementation-esstdjd9/`。

### #68 空教室 Repository 与页面

`EmptyClassroomQueryRequest` 在创建时捕获校区服务、token 和选择，收集时按缓存读取 → 远端查询 → 尝试事务保存 → 发布远端执行。缓存异常降级但取消异常传播。`ClassroomQueryCache` 先转换实体，Room 事务内执行删除、插入、过期清理；拿到事务后、删除前再次检查取消。已经开始的写入可整批完成，但取消后不交付结果。

真实 EmptyClassroomViewModel 负责学期/默认周关联、手选周优先、同条件去重、显式 retry/refresh、观察激活与取消。Coordinator 逐条排队 Main 交付并校验代次，覆盖已完成 Flow 的有效排队结果、替换/clear 后旧结果及重入。旧 AI LiveData 编排保留，复用事务缓存写入。

基线 `00a2c776` 的 [CI 40284404402](https://github.com/HIT-A/HITA_Android/actions/runs/40284404402) 精确为 418 通过、5 项预定失败；修复 `6ae7c70` 的 [CI 40288044464](https://github.com/HIT-A/HITA_Android/actions/runs/40288044464) 两个 job 成功，481 项设备测试全部通过，五项相同断言转绿。原 201 个测试/资源、4 个基线断言文件字节级保留；harness 适配独立审核。下载报告 ZIP SHA256 `fb29f90aa6f834637825c5dad9f17ca0a035ae42ffd8c4dbed76e10e31cdd435` 与 GitHub 一致。证据 `/tmp/hita-classroom-page-implementation-6jp98xvf/`，包含本地与 CI 独立签核和原始 XML。

未解决的边界：AI 同步等待全链路、缓存账户/校区隔离、不同调用者同 key 的后发请求优先，以及页面元数据整体协调。这些不能算在 #68 已完成范围内。

### 3.Q1 学期远端单次查询（PR #70，已合并且验证）

三校区新增 `getAllTermsOnce`，深圳另有强制 Web 路由的 `getShenzhenWebTermsOnce`。旧 LiveData 与新 suspend 入口共用请求/解析核心；新入口在切换 IO 之前捕获查询路由与培养类型，保留原 token 引用和 Cookie 回调。父 Job 取消后不继续下一业务请求，也不交付终态；原同步 socket、内部重定向和深圳认证 helper 不保证即时中断，已经完成的 Cookie 更新不回滚。

本批不接入 Repository 学期缓存或生产页面、AI、自动导入、Worker；这些调用者仍属于 3.Q2 及关联后续条目。

- 旧入口基线提交 `70a0210`：新增深圳 27、本部 16、威海 16、研究生 8，共 67 项契约；本地 API 35 完整 548 项全部通过后冻结原 213 份测试/资源。
- 新增正常/失败对照 31 项、取消/快照 28 项；本地 API 35 完整 607 项全部通过，原始 XML 身份精确匹配，无失败、错误、跳过、重复或缺漏。生产代码和新测试分别经过独立静态审查；Debug、测试 APK 构建通过，本地 JVM 795 项中 794 通过、1 项既有可选联网跳过。
- 首轮设备执行中，新增测试的一处表达式体被推导为列表返回值，导致整个 31 项测试类初始化失败；其余 576 项通过。仅显式补上该方法的 `Unit` 返回类型，全部断言及旧测试不变，修正经过独立审查；随后定向 31 项和完整 607 项均通过。这是测试代码修正，不是生产缺陷先红后绿证据。
- head `72751747ba5acccfb8abf4240d3b6f1700445e66` 的 [CI 40299381154](https://github.com/HIT-A/HITA_Android/actions/runs/40299381154) 两个 job 成功。下载 artifact `12640231824`，ZIP SHA256 `e8c2539d0c125eca439c428e84d8881177a03d3e6d76f50c68842107a57cfe43` 与 GitHub digest 一致；独立解析原始 XML，607 项全部通过，精确对应旧 548 + 新 59，无失败、错误、跳过、重复或缺漏。CI 未上传 JVM XML，795 的精确计数仍是本地证据。
- #70 已合并为 `b8a4cea55faab714baccceb1586ecc652cb3f46f`；证据目录 `/tmp/hita-stage345-skdpckp3/terms/` 保留原基线、独立审查、首次失败报告、签名修正、本地和 CI 结果，以及 `q1-pr70-independent-signoff.md/json`。上述 CI 对应 PR head，不表示合并后 main 另跑了一次测试。

### 5.V/M/R 构建依赖与资源校验（#71 已合并且验证）

插件、库与测试依赖统一引用 `gradle/libs.versions.toml`，保留原作用域、BOM、版本及仓库顺序；`-Phita.useMavenLocal=true` 显式开启两个仓库域的 Maven Local，默认关闭。恢复 style Release 资源校验，并由 CI 强制执行资源校验与 AAR 构建。

111 配置严格对照、三种 Maven Local 模式及冷缓存构建通过；整合 #70 后 Debug/测试 APK 构建、JVM 794 通过 + 1 既有跳过、设备 607 项全通过。随后合入的资源校验实际执行成功，30 项 Release 任务全执行，新 style AAR 与原字节一致；没有发现需要修改资源的 AAPT 错误。整合前后测试边界、旧基线制品比较和原始证据路径集中记录在 [验收台账](refactor-stage345-plan.md)，最终 [PR #71](https://github.com/HIT-A/HITA_Android/pull/71) 的 CI 40305741411 两 job 成功，设备 607 项全通过，资源任务实际执行；合并 `f4e809d` 已 fetch 核实与已审提交树相同。

## 9. 遗留事项与阶段 3–5 路线

- 当前按 [完整验收台账](refactor-stage345-plan.md) 自主推进。#70 学期远端能力已合并，学期缓存与全部十个消费端整链已通过独审和本地分层验证、最终完整 CI 待验；后续覆盖全部适合的一次性请求及渐进结果流、Repository 装配、聊天/附件、AI 调用链和执行语义，以及四个大型页面及构建/模块整理。
- `TimetableSourceApplier.applyHeldBatch` 在 Room 事务内调用 `changeStore.clearTerm` 写文件；提交失败仍存在恢复风险，需要独立失败基线与修复。
- `getShenzhenCreditProgress` 初始解析支持 summary 404，但后续合并漏传 `allowMissingSummary`，详情可能丢失；重构不能把旧缺陷冒充兼容目标，需单独先红后绿。
- `AgentChatViewModel.ioExecutor` 缺少生命周期关闭，与聊天会话/附件拆分一并解决。
- 剩余三条 Worker 平台依赖例外为 CourseReminderWorker→MainActivity、ScoreReminderWorker→ScoreInquiryActivity、WidgetRefreshWorker→WidgetUtils；台账列明迁移与验证，不新增或加宽例外。
- 本目标新增工作不能只凭目录移动、接口存在或构建成功宣称完成。必须验证真实调用者接入、缓存/认证/线程/生命周期行为及模块依赖方向。
- `/tmp` 证据可能清理，已合并 PR 与 Actions 为持久入口。不要重跑绑定旧快照的 prepare/generator 脚本。历史统计只适用于对应 SHA，不是当前度量。

## 10. 下一会话恢复提示词

```text
继续 /home/rinat/projects/HITA 阶段 3、4、5 的自主重构目标。
先读 AGENTS.md、本交接及 docs/refactor-stage345-plan.md；以当前 Git、PR、CI、源码为准，不把历史说明当最新状态。
阶段0/1/2已完成；3.1已合并#65教学楼、#66考试、#67空教室远端、#68空教室Repository/页面、#70学期远端能力。#70合并为b8a4cea，head7275174的CI40299381154实际设备607全通过，本地JVM794通过+1既有可选跳过；学期缓存和全部消费端留3.Q2。
5.V/M/R合为构建改造批次：三模式/冷缓存111配置对照通过；V/M整合b8a4cea后607设备全通过，R实际资源校验/AAR通过；最终#71 CI40305741411两job通过、607设备全绿，已合并f4e809d；5.T已合并#72，最终CI JVM 805通过+1既有跳过、633设备全绿；Subject已合并#73，head127e945的CI40314264518实际JVM827通过+1旧跳过、647设备全绿；纯JVM核心与旧sync清理已合并#74，CI40317563827实际JVM827通过+1旧跳过、647设备全绿，合并51819ca；学期缓存/十消费者整链本地通过，最终CI待验。精确状态和证据统一读验收台账。阶段3其余、阶段4与阶段5都未完成。
用户已明确授权阶段3/4/5内自主方案、实现、commit、origin push、PR、审核及合并，不再逐项询问；不发release、不推fork。
维护完整验收台账，不把已完成局部替代完整目标。独立审查、合适构建/真实测试报告、原用例完整保留、缺陷先红后绿仍须执行。
从最新origin/main新建每批分支，完成真实调用者迁移与证据后提交PR/合并，再更新台账继续；最终逐项核实全部范围后才结束目标。
```
