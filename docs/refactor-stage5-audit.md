# 阶段 5 只读审计与完整验收边界

审计日期：2026-10-26。工作树 `/home/rinat/projects/HITA`。本次只读源码/构建配置/缓存依赖，未运行 Gradle、未操作 Git、未改仓库文件。用户已授权阶段 3–5 自主提交、PR、审核、合并。本审计不要求再次授权。

## 1. 权威范围与实际缺口

`docs/refactor-handoff.md` 第 28 行列出阶段 5 六项：版本目录统一、mavenLocal 开关、恢复 style 的 verifyReleaseResources、子模块测试、纯 Kotlin 模块提取、sync 模块清理。第 105/212 行另外保留 3 条 Worker→UI 平台依赖，宜在阶段 3–5 总目标结束前清零。

目前事实：

- 没有 `gradle/libs.versions.toml`；根及 4 个模块 Gradle 均散布版本号。
- 根 `build.gradle` buildscript repositories 和 `settings.gradle` dependency repositories 均无条件 `mavenLocal()`，位于远端仓库前。
- `style/build.gradle` 的 afterEvaluate 块把 `verifyReleaseResources.enabled = false`。
- `component/src`、`hitauser/src`、`style/src` 没有任何 test/androidTest 源码。style 声明 JUnit 4.12 和 Jupiter 5.8.1，但没有 useJUnitPlatform，Jupiter 声明本身不能证明执行。
- CI JVM 仅跑 `:app:testDebugUnitTest`，设备仅跑 `:app:connectedDebugAndroidTest`；目前子模块不受真实行为测试保护。
- 纯协议/比较代码都在 app，Gradle 没有 JVM-only 模块。`ShareSnapshotBuilder` 虽置于 protocol，直接依赖 Room 实体 `Timetable/EventItem/TimeInDay`，不能原样放进纯模块。
- `sync/` 不在 settings、不被当前模块源码调用，但目录与 8 条 ProGuard keep 仍存在。
- 三条分层例外仍是 CourseReminderWorker→MainActivity、ScoreReminderWorker→ScoreInquiryActivity、WidgetRefreshWorker→WidgetUtils。

完成标准不能以“新增检查器、检查器测试通过”替代上述真实配置、生产调用链、模块和执行测试。

## 2. PR A：统一版本目录并让本地 Maven 显式选择

### 改动边界

- 新建 `gradle/libs.versions.toml`，统一 Kotlin/Compose 插件、AGP、Hilt、库、测试依赖版本，版本被 BOM 管理的库使用无版本 alias；保留 Compose BOM。
- 根与 app/component/hitauser/style 构建脚本改用 aliases；根使用 plugins apply false，或在必要处使用 catalog 的 library alias，避免 buildscript 阶段无法访问 libs 的时序问题。
- settings 增加插件仓库解析（若改用 plugins DSL），保留现有中国镜像可达性。不能只搬库版本、把插件版本继续散落。
- 同一个 `-PuseMavenLocal=true` 开关控制 pluginManagement（若存在）、buildscript（若保留）和 dependencyResolutionManagement 的本地 Maven；默认 false。README_DEV 写出默认与本地调试命令。
- 删除 component 重复 core-ktx、style 重复 appcompat/material，以及失效 compose_version 变量和误导 JDK17 注释。
- 不同时升级 AGP/Kotlin/Room/Hilt/Compose BOM/PDFBox/POI，不改应用 versionName/code。core-ktx 当前 app=1.17.0、库=1.13.1；不要盲目统一到 1.17 导致 compileSdk35 的库报 AAR metadata。合理保留两个命名清晰的 catalog 版本，或另审计 SDK 同步；“目录统一”不等于所有独立模块必须同一版本。
- Retrofit 当前 app=2.9.0，component/hitauser=2.4.0，converter=2.3.0；最终 app 已被解析到 2.9.0。若统一为 2.9.0，要明确这是子模块独立 runtime 的变更并用下一 PR 的子模块契约保护。最小风险方案先保留 legacy alias 与版本，目录完整后单独消除历史差异。

### 风险与验收

- catalog accessor 的 Groovy plugin alias、kapt 和 Hilt plugin id 必须真配置成功。
- 显式依赖 Gson（纯模块所需）前先检查 resolved 版本；本地缓存存在多个 Gson，不能从缓存文件存在推断当前 selected 版本。
- 保存前后 app debugRuntimeClasspath、androidTestRuntimeClasspath 与三个子模块 runtime 的依赖报告，核对真实 resolved 变化，不只比较声明文本。
- 默认与开启 local Maven 两种 settings 配置均可执行，CI 无需 ~/.m2 私产。可用临时 init script 读取 repository URLs 验证 local 缺席/出现，不添加永久大检查框架。
- 执行 `./gradlew :app:dependencies --configuration debugRuntimeClasspath` 及相关 dependencyInsight；`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`；必要时选定 full device suite（构建依赖变更应最终跑一次全部）。

## 3. PR B：恢复 style Release 资源检查

### 改动边界

- 删除禁用 `verifyReleaseResources` 的 afterEvaluate 块。
- 实际运行 `:style:verifyReleaseResources --rerun-tasks`，根据真实 AAPT 错误修资源归属，不能预设旧注释就是真原因，更不能换成 onlyIf/-x/ignoreFailures。
- 已静态核对 style XML，直接引用的 attrs/colors 均在 style 声明；主题父类在本地 Material 1.12 AAR 中实际存在，包含 `Widget.Material3.BottomSheet`。目前没有证据需要移动任何 app 资源；旧禁用可能已经过时。
- style 不能通过 implementation(app) 或复制整个 app 资源树来“修复”；公共资源放 style，app 仅提供主题值覆盖。
- CI 加显式 `:style:verifyReleaseResources :style:assembleRelease`，README_DEV/AGENTS/CLAUDE 同步实情。

### 验收

`./gradlew :style:verifyReleaseResources --rerun-tasks :style:assembleRelease :app:assembleDebug --console=plain`

必须看到 verifyReleaseResources 的执行结果，不能 SKIPPED；检查任务 enabled。若迁动资源，增加设备上相关主题/Dialog inflate 回归（light/dark）并运行；仅恢复检查且 AAPT 通过时无需制造镜像实现的单测。

## 4. PR C：子模块有实际行为测试并纳入 CI

### 最低有意义覆盖

- component JVM：Result/DataState 的 success/error/loading 映射、保留异常与默认消息、map 不对 error 调用 transform；这些是被全应用使用的跨层契约。若验证 safeApiCall，取消应传播（当前 catch(Exception) 会吞 CE，是潜在独立 bug，先红后绿，不在测试建立时偷偷修）。
- component 设备：LiveDataCallAdapter 的 response/null/failure 与 factory 泛型/非 LiveData拒绝，SharedPreferenceLiveData 注册/注销与值变化；Android行为放实际设备，不启用 unitTests.returnDefaultValues 掩盖。
- hitauser JVM：TypeConverters 时间戳 null/往返、枚举边界等；更主要的设备覆盖 UserPreferenceSource 登录状态读写/观察和 UserDatabase profile CRUD/replace 持久化，避免只测 DTO getter 就宣称模块被覆盖。
- hitauser 网络映射可用 MockWebServer 或注入 service，覆盖成功但无 token、错误用户名、错误密码、未知码与空响应，保留字段 message/msg 差异。
- style 设备：ThemeTools 的旧风格迁移到 color preset、迁移后幂等、现有 style/preset 不丢失、mode 持久化；实际 RecyclerView 选择状态或 Dialog主题inflate可再覆盖关键公共 UI 行为。ThemeTools 大量 SharedPreferences 行为比单纯 enum 常量测试更有价值。
- 统一 JUnit4 4.13.2；删除未使用 Jupiter 声明，除非确实配置并使用 JUnit Platform（无必要引入第二测试体系）。各模块有需要的 androidx.test.ext/runner/core 与 MockWebServer catalog 依赖。

### 真实已知构建障碍

hitauser 声明 minSdk21，但固定的 Room 2.8.4 AAR `room-runtime-android/2.8.4/AndroidManifest.xml` 实际 `<uses-sdk android:minSdkVersion="23" />`。app minSdk26 能合并，独立 hitauser androidTest APK 会暴露冲突。这是源码/缓存 manifest 证据，尚未本轮运行 Gradle复现。应把 hitauser 最低 API 提到真实依赖要求23（主 app 最低26不变），或有充足理由时单独 test minSdk23；不要 tools:overrideLibrary 无视底层最低要求。component/style 可以保留21，不能因为统一 catalog 无意提升全部模块到26。

### CI / 命令 / 证据

- JVM：明确运行 `:component:testDebugUnitTest :hitauser:testDebugUnitTest :style:testDebugUnitTest` 加 app 和新纯模块 test。无源码模块任务 NO-SOURCE 不算通过测试。
- 设备：`./gradlew :app:connectedDebugAndroidTest :component:connectedDebugAndroidTest :hitauser:connectedDebugAndroidTest :style:connectedDebugAndroidTest --console=plain`，CI 明确包含每模块。
- artifact 路径包括所有模块的实际 XML 与 HTML（当前只 app）。JVM 报告也上传，便于从 CI 核对身份。
- 基于 XML 核对每个新增 test classname+name 实际执行，无重复/缺失，无意外 skip。app 旧测试身份完整保留，不允许靠只跑新子模块替代整套 app 回归。

## 5. PR D：提取真正的纯 Kotlin 模块

### 推荐边界

新增 `:timetable-core`（名字可定，重要的是 JVM-only 构建和真实调用）：

- Kotlin JVM/java-library plugin，JVM target 与 app 1.8 一致，JDK21 用作工具链；无 com.android.library、Android manifest、Context、LiveData、Room/Compose、app dependency、Android SDK compile classpath。
- 移入原包名的 `SharedTimetable.kt`、`FriendImportPolicy.kt`、`TimetableShareCodec.kt`、`StrictShareJson.kt`、`ShareLimits.kt`、`ShareTermResolver.kt`，及 `ComparisonModels.kt`、`TimetableComparisonEngine.kt`、`ComparisonSelection.kt`。协议与比较同一个自然领域模块，避免 ComparisonSelection→ShareMode 的循环。
- app `implementation(project(':timetable-core'))`，全部真实分享、导入、导出、比较调用解析到模块产物；删除 app 原副本，不用 sourceSets 跨目录包含伪装模块。
- `ShareSnapshotBuilder` 是 Room实体→协议模型映射：移到 app data/repository 附近（可保留兼容包名以减少变更，最好包名反映职责），只依赖模块公共API。它与 `ShareSummaryBuilder` 必须保持同一跨学期过滤规则。
- 内部可见性不是机械搬迁即可：`ShareLimits` 被 app 的 TimetableExportRepository、TimetableComparisonRepository、ComparisonCandidate 及 app tests 用到；`StrictShareJson.validate` 被 SharedTimetableImporter 调用；`invalidFields` 被 ShareSnapshotBuilder用到。推荐保持解析器内部，向 Codec 增公开 validate/canonical入口；确实属于协议契约的 limits 提供稳定公共对象；adapter 用公开 ShareFormatException 构造，不把整个 parser 公开来偷过编译。
- Gson 使用 catalog 显式 dependency；需要前后 resolved 对比，协议合法性/规范化/hash 应保持，别借机升级。

### 原测试移动与边界保持

- 纯协议 test（6类，ShareSnapshotBuilderTest 留 app）及 comparison engine test 移到新模块 test，保留 classname/method/断言和 fixtures；不得删减恶意输入/资源上限/跨日/隐私/时区测试。
- `ShareFixtures` 被 app测试使用时可在 testFixtures 建共享测试库（java-test-fixtures），或 app测试构建自己最小 fixture；不要把测试数据塞进 production API。
- `ShareSnapshotBuilderTest`、`ShareSummaryBuilderTest` 和 app集成/设备分享导入测试仍运行，证明实体 adapter 与纯协议真实串起来。
- scripts/check_repository.py 的 LAYER_ROOTS 和对应测试必须覆盖新模块 main 源码，否则原app路径移空后“检查通过”是假覆盖。应同时限制纯模块引入 Android/Room/app dependencies（Gradle compile classpath强约束优先），但检查器是维护约束，不是模块交付本身。
- ProGuard 在 app 上使用包名保持则规则通常无需变，仍需 release shrink验证跨模块反射模型/序列化没有消失。

### 真实验收

`./gradlew :timetable-core:test :timetable-core:jar :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`

查看 jar entries 确认生产类实际在模块；查看 dependency graph 确认 app消费模块且模块无 Android/Room/app回边；新模块 test 在不运行 app/Android任务时成功；检查最终聚合 testcase集合 = 原集合 + 必要新增，移动测试不当作新增覆盖。

最终全设备 suite和 app release编译/R8（有签名可 assembleRelease，无签名至少相应 minify/包资源任务）需要通过；不能仅凭 JVM tests 声称 Android路径未变。

## 6. PR E：删除 legacy sync 模块和悬空配置

### 改动边界

- 删除整个 `sync/` 未编译旧模块源码/Gradle/consumer rules等；历史可从 Git取回，不创建 docs/archive 第二份死源码。
- 清理 app/proguard-rules.pro 中 `com.limpu.sync.HistoryDatabase{,$*,_Impl}`、data.model、data.source.dao、web接口及全包 keep（实际 8 条）。
- 同步 README_DEV、AGENTS、CLAUDE 的结构描述与 handoff进度；保留文档中有必要的历史事实，但别把模块描述成仍存在。
- 不删除用户设备上的旧 `stupid_sync` 数据库，不增加启动破坏性清库，不恢复远端 sync endpoint调用。

### 验收

全仓跟踪源码检索无生产 import/项目依赖；settings只列真实模块；仓库链接检查通过；Debug构建、相关 JVM和 release R8检查通过。删除的是未使用代码，没有理由写与删除列表镜像的业务测试。

## 7. 剩余三个 Worker→UI 边界

建议独立 PR，阶段3 Repository注入与查询迁移完成后做，避免和构建整理混杂。

不要只是把 Worker 从 data/work 搬到不受检查的新路径，或者把 cn.limpu.hita.ui 字符串拆拼让检查器看不见；真正的边界是数据计算/查询不持有 Activity 或 Widget类。

推荐：

1. 窄接口 `ReminderNavigator`/`ReminderNotificationGateway`（或直接通知sink），数据 Worker只提交课程/成绩通知数据；Android平台实现位于 platform/notification，负责 MainActivity/ScoreInquiryActivity 显式 Intent、channel、PendingIntent 与 NotificationManager。
2. `WidgetRefreshGateway` 平台实现封装既有 WidgetUtils，Worker依赖接口。共享 DisplayScopeCoordinator/TimetableWriteUseCaseFactory 可复用该实现，避免多处手动平台 new。
3. WorkManager通过 Hilt WorkerFactory（若全局配置已具备）或 Application层 factory/entrypoint装配实现，不在 data层静态工厂里间接回指 platform具体实现。若加 Hilt Worker，必须验证 HApplication Configuration.Provider 与既有 WorkManager初始化一致，既有 Worker保持可创建。
4. 保持旧 WorkManager worker类全名（已持久化任务按字符串恢复），可以旧名桥接新实现，避免升级后已有 periodic work ClassNotFound；要删除旧名需明确迁移/重新enqueue证据。
5. 删除对应 allowlist三行后对照 origin/main运行检查。静态零引用不足以证明注入有效，必须运行 Worker测试及通知/广播集成。

验收不变量：课程通知 requestCode=event.id.hashCode、FLAG_UPDATE_CURRENT|IMMUTABLE、MainActivity flags NEW_TASK|CLEAR_TOP；成绩通知requestCode0且目标ScoreInquiryActivity；channel ID与通知ID不变；widget action与只有已安装组件广播规则不变；disabled/not-logged-in不发通知；查询取消后不误记已通知baseline；旧 persisted Worker能被factory创建。可使用 WorkManager TestListenableWorkerBuilder/TestWorkerBuilder，加平台instrumentation检查Intent/通知内容；不需要真实校区账号来证明平台装配。

## 8. 顺序与总体验收矩阵

推荐顺序：A catalog/local repo → B style资源检查 → C子模块真实测试/CI → D纯模块提取 → E sync删除。Worker边界放阶段3末尾或C前，并行审计可提前，实际更改仍一个PR一项。

每PR：相关基线与新测试 → 独立review → build/设备等真实报告 → commit/push → PR CI真报告核对 → merge；文档与结构变更同步。新增正常行为测试不故意制造失败；发现bug按已约定先红后绿。

最终完成审计逐项证据：

| 项目 | 足够证据 | 不足证据 |
|---|---|---|
| 版本目录 | 全部生产/测试/plugin引用真实catalog；resolved依赖受控 | 只新增toml，旧硬编码还在 |
| mavenLocal | 默认解析无local；开关打开可见；干净CI成功 | 写README说默认禁用 |
| style验证 | enabled且实际运行的verifyReleaseResources成功、CI持久执行 | afterEvaluate换写法继续跳过 |
| 子模块测试 | 各模块至少关键真实行为测试、有XML实际执行且CI覆盖 | Gradle任务NO-SOURCE或只 DTO常量断言 |
| 纯模块 | 独立Kotlin JVM artifact、app真实依赖、领域代码移出、独立test及Android集成通过 | 只新建空模块/扫描器或Android library伪称纯Kotlin |
| sync清理 | 目录/生产配置/keep清零、无有效引用丢失、构建验证 | settings原本就不include便称完成 |
| Worker边界 | 接口与平台装配+旧任务兼容实测，allowlist清零 | 更名/搬目录绕过检查 |

以上是计划与静态事实，不是已完成结果。实际实现与测试产物需由后续工作逐项补齐。
