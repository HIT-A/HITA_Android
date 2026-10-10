# HITA 课表主页入口与双人对比 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从课表主页完成分享和好友管理，只允许相同作息结构的双方按完整节次对比，在原周课表展示共同空闲及同节有课冲突，排除午休与课间。

**Architecture:** 纯 Kotlin 模块先比较双方完整作息的有序起止分钟列表，再逐日逐节判定占用。只读 Repository 观察现有 Room 数据，独立 ComparisonViewModel 保存配置并取消过期计算，原周课表使用独立覆盖层绘制完整节次；进入对比只显示所选个人课表课程，退出恢复原显示。

**Tech Stack:** Kotlin、Jetpack Compose Material 3、Hilt、Room 15、Coroutines/Flow、SavedStateHandle、JUnit4，沿用现有依赖。

**Spec:** [已确认的书面设计](../specs/2026-10-09-timetable-comparison-design.md)。基线 `3846021`，worktree `C:/Users/Rinat/.codex/worktrees/timetable-sharing/HITA_Agent`，分支 `feat/timetable-sharing`。

## Global Constraints

- 默认每天 08:30–22:30，可调整；同日范围必须开始小于结束，只纳入完整被范围包含的节次。
- 双方作息项数、顺序与每项起止分钟完全相同才比较；不兼容不能自动换算，空/无效结构不能用默认结构补齐。
- 每个scheduleStructure/periods项是一节，不自动拆分；默认六项作息中的午休、课间无比较结果。
- 双人比较：一份个人课表 + 一位已保存好友；FULL/BUSY 均可用。
- 只取选定课表 CLASS，排除考试与个人活动；好友 BUSY 不展示/推断课程详情。
- 课程与节次有正时长重叠即整节有课；同节双方均有课为冲突，均无课为共同空闲，不再求实际课程时间交集。
- 半开时间区间，校园时区 Asia/Shanghai；按实际日期对齐，不按双方第 N 周对齐。
- 整节必须完全在双方有效期内才判断；部分超出即整节未知，不能显示为空闲。
- 结果不写个人课程、日程、提醒，不生成分享身份；不修改 HITA1 或数据库 schema/version。
- 所有新增文案放 strings.xml；页面全程离线可用；用户已授权主代理完成后 commit 并 push origin/feat/timetable-sharing；工作代理不提交或推送，关机要求已撤销。
- 使用现有隔离 worktree，不创建重复 checkout。所有 Gradle 验证串行、单 worker，保留既有临时内存策略，不提交机器配置。

## Review Focus

1. 主页面聚合多份课表：只选择其中一份作为占用和课程显示来源，退出后恢复聚合显示；Task 2/5 测试。
2. 新一周计算晚于旧一周：旧响应不得覆盖新窗口，加载/错误时隐藏旧区域；Task 3 测试。
3. 好友有效期在节次中途结束：整节未知，不能截出半节空闲；更新导致结构不同立即清除旧结果；Task 1/3 测试。
4. BUSY 与 FULL 相同占用但不同字典：计算结果一致，BUSY 对话框不能访问好友名称/地点；Task 1/5 测试。
5. 同节中的短安排与不同作息：短安排使整节有课，同项数但时间不同不可比较；午休和课间绝无绿色块；Task 1/4/5 测试。

## 文件职责和任务依赖

新建文件均位于 app；不拆解整个 MainActivity/TimetableFragment。

- `feature/timetablecompare/ComparisonModels.kt`：计算输入/输出和值对象。
- `feature/timetablecompare/TimetableComparisonEngine.kt`：作息校验与完全一致判定、逐日生成整节、课程占用判定。
- `data/repository/TimetableComparisonRepository.kt`：只读一致数据、观察更新、对象消失/异常分类。
- `ui/main/timetable/compare/TimetableComparisonViewModel.kt`：保存配置、观察数据、周切换与任务失效。
- `ui/main/timetable/compare/ComparisonUiState.kt`：UI 状态及显示策略。
- `ui/main/timetable/compare/ComparisonConfigSheet.kt`：对象/时间范围/显示开关选择。
- `ui/main/timetable/compare/TimetableSocialSheet.kt`：主页四个入口。
- `ui/main/timetable/compare/ComparisonOverlay.kt`：共享时间坐标的绘制层及区域点击。
- `ui/main/timetable/compare/ComparisonDetails.kt`：每日摘要、精确区间和隐私安全详情。
- 修改 `EventItemDao.kt` 只增加 CLASS 观察接口；不改已有全局 SQL。
- 修改 MainActivity/TimetableFragment 只连接入口、状态和绘制层；修改 FriendTimetableActivity 增加一次性打开导入的 intent。
- 对应纯 JVM 测试放 `app/src/test/java/cn/limpu/hita/feature/timetablecompare` 与 `ui/main/timetable/compare`；数据库测试放 `app/src/androidTest/java/cn/limpu/hita/data/timetablecompare`。

执行顺序 1 → 2 → 3 → 4 → 5 → 6。Task 4 修改主页连接，Task 5 修改周内容，禁止两个执行者同时写 MainActivity/TimetableFragment/strings.xml。

## Task 1: 作息兼容性和逐节占用判定

**Files:** 新建 ComparisonModels.kt、TimetableComparisonEngine.kt；新建 `feature/timetablecompare/TimetableComparisonEngineTest.kt`。

**Interfaces:** 后续任务使用以下签名；时间均为 epoch milliseconds。

```kotlin
data class TimeSpan(val startMillis: Long, val endMillis: Long)
data class ComparisonPeriod(val startMinute: Int, val endMinute: Int)
data class ComparisonSchedule(val valid: TimeSpan, val periods: List<ComparisonPeriod>,
    val occupied: List<TimeSpan>)
enum class PeriodStatus { FREE, OWN_ONLY, FRIEND_ONLY, CONFLICT, UNKNOWN }
data class ComparedPeriod(val index: Int, val span: TimeSpan, val status: PeriodStatus)
data class ComparisonRequest(
    val mondayMillis: Long,
    val startMinute: Int = 510,
    val endMinute: Int = 1350,
)
data class ComparisonDay(
    val date: java.time.LocalDate,
    val slots: List<ComparedPeriod>,
) {
    val windows get() = slots.filter { it.status != PeriodStatus.UNKNOWN }.map { it.span }
    val free get() = slots.filter { it.status == PeriodStatus.FREE }.map { it.span }
    val conflicts get() = slots.filter { it.status == PeriodStatus.CONFLICT }.map { it.span }
}
data class ComparisonResult(val days: List<ComparisonDay>)
class IncompatiblePeriodsException : IllegalArgumentException()
object PeriodStructure {
    fun isValid(periods: List<ComparisonPeriod>): Boolean
    fun isCompatible(own: List<ComparisonPeriod>, friend: List<ComparisonPeriod>): Boolean
}
class TimetableComparisonEngine {
    fun compare(own: ComparisonSchedule, friend: ComparisonSchedule,
        request: ComparisonRequest): ComparisonResult
}
```

- [x] **Step 1: 写固定预期的红测试**。测试帮助方法 `at(hour, minute)` 返回 2026-10-05 校园时间毫秒，`span` 构造 TimeSpan，`schedule` 设置有效期为当天零点至次日零点，periods为已核实的六项默认结构；不要以计算实现生成期望值。

```kotlin
@Test fun wholePeriodsWithoutRestGaps() {
    val own = schedule(listOf(span(9, 0, 10, 0)))
    val friend = schedule(listOf(span(9, 30, 10, 30)))
    val day = TimetableComparisonEngine().compare(
        own, friend, ComparisonRequest(at(0, 0))).days.first()
    assertEquals(listOf(span(8, 30, 10, 15)), day.conflicts)
    assertEquals(listOf(span(10, 30, 12, 15), span(14, 0, 15, 45),
        span(16, 0, 17, 45), span(18, 45, 20, 30), span(20, 45, 22, 30)), day.free)
}
```

- [x] **Step 2: 执行红测试**：`gradlew.bat :app:testDebugUnitTest --tests '*TimetableComparisonEngineTest'`，记录缺失生产接口或具体断言失败。
- [x] **Step 3: 实现计算**。拒绝非法有效期、起止颠倒/零长占用、非周一校园零点请求、时间范围超出 0..1440。结构必须非空、有序、不重叠、分钟合法、每项开始小于结束；完整列表不同抛IncompatiblePeriodsException。先验证所有记录，再对显示周每一天生成被日范围完整包含的节次；整节不在双方有效期内则UNKNOWN。每个节次分别检查双方占用，再映射五种PeriodStatus；重复课程只影响Boolean，不重复统计。7天×最多48节×最多4096安排可直接有界扫描，不做无必要的复杂索引。

```kotlin
private fun occupies(course: TimeSpan, period: TimeSpan): Boolean =
    course.startMillis < period.endMillis && course.endMillis > period.startMillis
// 一个正时长重叠就占整节；不求双方课程的实际时间交集。
```

- [x] **Step 4: 补充边界测试并运行 green**。第一节08:30–10:15，课程10:15开始不占第一节；自己08:30–09:00好友09:30–10:00仍同节CONFLICT；10秒安排占整节；午休12:15–14:00及课间10:15–10:30不存在结果；无课程只输出六个FREE节次。范围09:00–22:30排除第一节而不截断；有效期12:00截止使第二节UNKNOWN；无共同有效期所有入选节次UNKNOWN，free/conflicts为空；跨午夜占用次日整节；重复/嵌套不重复计数。结构时间偏1分钟、项数不同、拆节方式不同均不兼容；空/乱序/重叠结构拒绝，完全相同才通过；日期/调课照实际时间。新增作息外课程不凭空生成节次的测试。
- [x] **Step 5: Review**：核对设计第5节所有规则和5个 Review Focus 的相关覆盖，记录测试证据，不自动 commit。

## Task 2: 只读数据读取与变化观察

**Files:** 新建 TimetableComparisonRepository.kt；修改 `data/source/dao/EventItemDao.kt`；新建 `data/timetablecompare/TimetableComparisonRepositoryTest.kt` 设备测试文件。

**Interfaces:** 消费 Task 1 类型，定义以下输出：

```kotlin
data class ComparisonSource(
    val ownTimetable: cn.limpu.hita.data.model.timetable.Timetable,
    val ownCourses: List<cn.limpu.hita.data.model.timetable.EventItem>,
    val friendRow: cn.limpu.hita.data.model.timetable.share.FriendTimetableEntity,
    val friendSnapshot: cn.limpu.hita.feature.timetableshare.protocol.SharedTimetable,
    val own: ComparisonSchedule,
    val friend: ComparisonSchedule,
)
sealed interface ComparisonSourceState {
    data class Ready(val source: ComparisonSource) : ComparisonSourceState
    data object MissingOwn : ComparisonSourceState
    data object MissingFriend : ComparisonSourceState
    data object Invalid : ComparisonSourceState
    data object Incompatible : ComparisonSourceState
}
// Repository 提供：
fun observe(personalId: String, friendId: String):
    kotlinx.coroutines.flow.Flow<ComparisonSourceState>
```

- [x] **Step 1: 写实际 Room 路径红测试**。内存 AppDatabase，创建两个个人课表各自 CLASS，再插 EXAM/OTHER、好友独立记录；选择A时 ownCourses 只含A的CLASS。保存全部表的行内容，订阅读取后对比，确认个人表及 share_identity 未改变。
- [x] **Step 2: 增加仅选定课表的观察**，保留原方法。

```kotlin
@Query("SELECT * FROM events WHERE timetableId = :id AND type = 'CLASS'")
fun observeClassesFlow(id: String): kotlinx.coroutines.flow.Flow<List<EventItem>>
```

- [x] **Step 3: 实现一致读取**。combine 个人列表 LiveData.asFlow、选定 CLASS Flow 与好友 LiveData.asFlow，任一变化触发 IO 调度内的 `db.runInTransaction(Callable { ... })` 读取。使用现有 getTimetableByIdSync/getEventsOfTimetableSync/friend getSync；再次筛选 timetableId/type；个人空CLASS允许，非法时间整体 Invalid；好友 JSON 用现有 TimetableShareCodec.decodeJson 完整校验。个人scheduleStructure和好友periods都转换为ComparisonPeriod完整列表，校验后不相同返回Incompatible；不得默认填结构或只检查选定范围。个人模式不调用 generate 或 ShareSnapshotBuilder，不写 shareId。
- [x] **Step 4: 设备测试覆盖修改/删除**。更新课程时间，重订阅/等待新状态并断言时间；好友更新/备注变更重新读取；删除好友返回 MissingFriend；删除个人课表返回 MissingOwn。无效快照或有效期返回 Invalid，不能 Ready(empty)。FULL/BUSY 相同结构与占用进入 engine 得到相同free/conflicts；更新任一方作息导致不兼容后必须返回Incompatible。设备不可用时只编译并准确记录未运行。
- [x] **Step 5: Review**：确认只读事务没有 insert/update/delete，既有查询/数据库schema不变。JVM/AndroidTest compile green，无设备不宣称运行通过。

## Task 3: 配置恢复、周窗口与过期结果控制

**Files:** 新建 ComparisonUiState.kt、TimetableComparisonViewModel.kt；新建 `ui/main/timetable/compare/ComparisonStateTest.kt`。

**Interfaces:** 消费 Task1/2，后续UI消费：

```kotlin
data class ComparisonConfig(val personalId: String, val friendId: String,
    val startMinute: Int = 510, val endMinute: Int = 1350,
    val showFree: Boolean = true, val showConflicts: Boolean = true)
sealed interface ComparisonUiState {
    data object Off : ComparisonUiState
    data object Loading : ComparisonUiState
    data class Ready(val config: ComparisonConfig, val source: ComparisonSource,
        val result: ComparisonResult) : ComparisonUiState
    data class Error(val messageResource: Int) : ComparisonUiState
}
// ViewModel 的入口：
fun enable(config: ComparisonConfig)
fun changeWeek(mondayMillis: Long)
fun disable()
fun retry()
// 公开 state: StateFlow<ComparisonUiState>，config: StateFlow<ComparisonConfig?>。
```

- [x] **Step 1: 红测试覆盖请求身份**。生产纯状态助手保存 `revision`；reload 将 state设为Loading并增加revision；accept只接纳匹配revision、config和monday的返回值。断言旧周的Ready被拒绝，关闭后旧结果被拒绝，失败没有旧Ready，Retry保留配置。
- [x] **Step 2: 实现 ViewModel**。SavedStateHandle 分别保存 primitive ID、分钟数、bool、开启状态；恢复时重新读取Repository，不保存大量课程对象。Job cancel/collectLatest加匹配序号守卫；取消异常继续抛出；计算放 Default，数据库在 IO。缺失对象关闭配置并以Error提示；读取错误/结构不兼容保留配置供Retry或更换对象但清空旧结果，disable回Off。结构不兼容显示专用文案，不混同坏口令。

```kotlin
private fun matches(revision: Long, config: ComparisonConfig, monday: Long): Boolean =
    revision == currentRevision && config == currentConfig && monday == currentMonday
// enable/changeWeek/disable 都使 currentRevision 前进，立即清除旧区域。
```

- [x] **Step 3: 补充恢复与显示策略测试**。SavedStateHandle值恢复相同配置；未开启保持Off；无效范围不能enable；每日范围变更撤销旧结果；personal/friend ID失效停用；Ready之后收到Incompatible清除覆盖结果，恢复配置也重新验证作息。独立生产显示策略函数在Ready返回选定CLASS，在Off返回原events，在Loading/Error开启时不回退到所有个人表。
- [x] **Step 4: green和Review**。运行 `--tests '*ComparisonStateTest'` 及新增纯助手测试，核对加载/异常/删除均不会显示旧结果。

## Task 4: 主页四入口和对比配置面板

**Files:** 新建 TimetableSocialSheet.kt、ComparisonConfigSheet.kt；修改 `ui/main/MainActivity.kt`、TimetableFragment.kt、FriendTimetableActivity.kt、strings.xml；新建 `ui/main/timetable/compare/ComparisonConfigTest.kt`。

**Interfaces:** 消费 ComparisonConfig 和 ViewModel enable；MainActivity操作通过当前 TimetableFragment 的 `openTimetableSocialSheet()` 进入同一个面板，不新建第二个Fragment；FriendTimetableActivity.intent增加 `openImport: Boolean = false`。

- [x] **Step 1: 配置验证红测试**。起点510终点1350有效，终点等于/小于起点拒绝，0..1440之外拒绝，空ID拒绝；默认开关都true。双方同项数不同起止时间不能确认，空结构不能确认；范围只覆盖课间不含完整节次不能确认。用生产配置/兼容性校验函数，不测试写死常量。
- [x] **Step 2: 增加intent与一次性导入**。复用 FriendTimetableViewModel.showImport；intent触发需在重建时不重复强行打开已经关闭的导入对话框。

```kotlin
fun intent(context: Context, openImport: Boolean = false) =
    Intent(context, FriendTimetableActivity::class.java)
        .putExtra("openImport", openImport)
// 一次性状态由 savedInstanceState 或 SavedStateHandle 保存。
```

- [x] **Step 3: 主页面板连接**。分享Intent指向现有TimetableShareActivity并携带其EXTRA_TIMETABLE_ID，仅当当前选定ID明确且有效时预选。导入intent(true)、好友intent(false)。对比打开对象选择与时间配置，不把“列表里第一项”假装当前选中。使用TimetableController已有选择语义，若只是在多个课表间跳周则按无明确选择处理。
- [x] **Step 4: 配置面板实现**。个人列表取已有课表观察，好友取现有observeFriends，选中用ID；昵称优先备注。好友作息从校验快照取得，选择后列出双方结构并显示兼容性；不兼容禁止Confirm，独立查看好友仍可用。时间输入有格式/范围错误资源提示，范围内没有完整节次也提示调整；空列表提供管理/导入操作。Confirm调用enable并关闭面板，Cancel不改变正在使用的配置。列表更新失去选项时禁止确认。工具栏新增操作有contentDescription，设置菜单保留壁纸长按能力，不丢现有操作。
- [x] **Step 5: focused green与Debug编译**。新界面字符串全部资源化；静态核对无需进入个人详情即可触达四操作；Review不改变ICS、分享口令和好友确认保存逻辑。

## Task 5: 原周课表覆盖层与对比详情

**Files:** 新建 ComparisonOverlay.kt、ComparisonDetails.kt；修改TimetableFragment.kt、strings.xml；新建 `ui/main/timetable/compare/ComparisonProjectionTest.kt`。

**Interfaces:** 消费 Ready/config；覆盖层签名使用时间坐标而非EventItem。

```kotlin
@Composable fun ComparisonOverlay(
    day: ComparisonDay,
    startHour: Int,
    dpPerMinute: androidx.compose.ui.unit.Dp,
    showFree: Boolean,
    showConflicts: Boolean,
    onSelect: (TimeSpan, Boolean) -> Unit,
)
// 每个day对应当前日期列；Boolean=true 为冲突。
data class ComparisonSegmentPosition(val topMinute: Double, val durationMinute: Double)
fun position(span: TimeSpan, date: java.time.LocalDate,
    startHour: Int): ComparisonSegmentPosition
```

- [x] **Step 1: 投影红测试**。完整第一节08:30–10:15在08:00时间轴top=30/duration105；第二节10:30–12:15为top150/duration105，两节中间15分钟不着色；午休不着色。跨午夜终点在前一天底部，UNKNOWN不着色。另写详情模型测试，BUSY只返回占用标签不访问字典，FULL返回与该完整节次相交的实际课程（不是双方课程精确交集）。
- [x] **Step 2: 连接现有布局坐标**。在TimetableWeekContent中增加默认关闭的可选comparison参数；渲染先网格、绿色底层、个人课程、红边框/纹理层。每列共用现有左栏宽度、日期列宽、dpPerMinute、startHour。正常null路径保持原UI；比较读取时显式ZoneId.of("Asia/Shanghai")，当前周转为校园周一起点并使用同一日期导航。

```kotlin
val dayStart = date.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
    .toInstant().toEpochMilli()
val topMinute = (span.startMillis - dayStart) / 60_000.0 - startHour * 60
val durationMinute = (span.endMillis - span.startMillis) / 60_000.0
```

- [x] **Step 3: 交互/时间轴/汇总**。对比期间只绘制所选CLASS，点击自己的课程可看但屏蔽添加和长按修改。显示状态条、图例、调整/退出、每日逐节列表与节次数/完整节次时长合计；不合并跨过课间。UNKNOWN不着色且提示有效期外未判定；加载/错误/不兼容不能有旧覆盖层。时间轴临时扩展到范围与所选当前周课程，退出恢复偏好。周次变化传给ComparisonVM.changeWeek，滑动和上/下周共用一个入口。
- [x] **Step 4: 详情和隐私**。每日逐节列表显示节次编号和完整作息时间；详情列与该节次相交的双方记录，并说明同节有课不等于实际时间重叠。自己的名称可读，好友BUSY仅占用，FULL显示名称/实际时间。作息外课程照原时间展示，并提示未参与节次对照；覆盖层不产生个人EventItem、不改变现有同人重叠算法。统计以节次为单位，不宣称精确课程重叠时长。
- [x] **Step 5: green、Debug和Review**。focused投影/状态/计算回归；核对主课表与readonly好友页原默认参数均不受影响。设备可用时验证压缩模式、滚动、颜色/图例、午夜及短区间；没有设备明确待验收。

## Task 6: 整体审查、全量验证和交付说明

**Files:** 新建 `docs/timetable-comparison.md`；更新设计/计划任务状态；测试按前五任务修复真实遗漏，不新增镜像实现的测试。

- [x] **Step 1: 全功能Review**。从基线3846021比较最终diff，沿入口→配置→读取→计算→绘制追踪实际数据，重点审查5项Review Focus。修复发现后对变更部分重新检查，不只检查最末任务。
- [x] **Step 2: 串行全量验证**。复用上轮已经验证可用的本地JDK与ignored init，命令如下；不把路径写进项目配置。

```powershell
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xms64m -Xmx1536m -XX:HeapBaseMinAddress=64g -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=256m -Dfile.encoding=UTF-8' -I .superpowers/sdd/2026-10-09-timetable-comparison/test-memory.init.gradle --console=plain
python scripts/check_repository.py
git diff --check
```

- [x] **Step 3: 核验artifact**。汇总最新JVM XML数量、失败/错误/跳过；核对新Debug APK SHA256及应用标识，不能复用上轮hash。adb设备为空则说明数据库与UI设备测试只编译；不自行安装到用户设备。
- [x] **Step 4: 交付文档**。记录入口、相同作息兼容性、整节占用规则、08:30–22:30完整节次过滤、午休/课间排除、有效期/UNKNOWN、作息外课程、仅课程含义、隐私、退出方式、实际测试与设备缺口，链接Debug APK。状态与报告保留在ignored ledger，所有产品文件保持本轮未提交。
- [ ] **Step 5: 回报用户**。提供功能结果和可测试APK，不宣称双设备、生命周期或真实布局已通过未执行验收；仅在用户本轮明确授权后commit/push。

## 自审结果及执行交接

设计1–3节→Task4；设计4节→Task3/4/5；设计5节→Task1/2；设计6节→Task5；设计7节→Task2/3；设计8节→全部focused测试与Task6。UI之间只传明确ID/config/result，不把比较区域当课程存储。

最新修订已同步为节次匹配，旧版实际时间交集/补集方案不再实施。用户已确认修订设计与计划，进入执行阶段。

用户在本会话已选择子代理执行；修订确认后延续该方法，每项实现与独立Review串行推进，末尾全功能Review。任务之间不得并行改共享UI文件；大型Gradle任务同时只允许一个。Tasks 1–5 已实现并经独立 Review（Task 4/5 的 P2 已修复并复审）。Task 6 全量验证、交付说明和整功能 Review 已完成；整功能 Review 发现的设备测试夹具问题已修复并复审。AndroidTest 仅编译，无设备运行。

## 执行状态（2026-10-10）

Tasks 1–5 的勾选表示实现、focused 验证及静态审查步骤已完成；Task 2 Step 4 的 Room 设备用例仅编译，未运行。Task 6 Step 5 待最终用户回报。全量 JVM 527 项、0 失败、0 错误、1 跳过；Debug/AndroidTest 编译通过。详见 [使用与验证说明](../../timetable-comparison.md)。主代理已有本轮 commit/push origin 分支授权，关机要求已撤销。
