# HITA Timetable Sharing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Execution method awaits user selection. No automatic commits or pushes.

**Goal:** 实现 PRD v1.0 的离线课表分享、好友课表导入/管理及确认更新完整流程。

**Architecture:** 分享快照按实际课程出现时间编码为 HITA1 文本。AppDatabase 新增身份及好友快照表；个人表完全隔离。好友界面复用 ReadOnlyTimetableWeek，明确时区并禁止编辑。

**Tech Stack:** Kotlin 2.2.21、JDK 17、Compose Material 3、Hilt、Room 2.8.4、已有 Gson、java.util.zip、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-10-09-timetable-sharing-design.md`（已确认）。

## Global Constraints

- 不新增后端、账号关系、联网解析、实时同步、撤回、二维码或共同无课时间计算。
- 所有新增界面文案进入 `strings.xml`。
- 文本结构：`HITA1:` + 无 padding 的 Base64URL(GZIP(UTF-8 紧凑 JSON))。
- 时间使用整数毫秒；v1 固定校园时区 Asia/Shanghai。
- 整段粘贴文本 131072 字符；单个口令 65536 字符（含前缀）；解压输出 1048576 字节。
- 课程字典 512 项；安排 4096 项；作息 48 项；昵称 1–40 个 Unicode 码点。
- 课表名和学期名各 80 个 Unicode 码点；课程名和地点各 120 个 Unicode 码点。
- 作息分钟范围 0–1440、开始小于结束，作息依次有序且不重叠。
- 时间范围为 2000-01-01 至 2100-01-01（不含）；学期跨度不超过 370 天；单次安排不超过 24 小时。
- AppDatabase 升级 14 → 15，只新增表和索引，保留历史 schema 及迁移链。
- 不修改原有未跟踪文件，不顺带重构 EAS 或提醒逻辑；不改变原 ScheduleCodec 接口。
- commit 和 push 按 AGENTS.md 等待用户明确授权。计划每个任务以审查及验证结束，不执行自动 commit。
- 实施前使用 using-git-worktrees 技能确认隔离工作区；当前主目录有用户未跟踪产物。优先复用本聊天已附加的适用 worktree，否则为已授权功能分支创建隔离 worktree，不移动或清理用户产物。
- 编解码、数据库及快照构建在 Dispatchers.IO 上执行，使用 viewModelScope，禁用原始口令日志。

## Review Focus

1. 设备处于海外时区或夏令时：日期、星期、周次和卡片位置仍按 Asia/Shanghai（Task 5）。
2. 导入预览后另一次导入/删除已改变数据：必须重新确认，不能覆盖未确认状态（Task 3）。
3. 表单变化或切换课表时后台生成返回：不能显示上一份口令（Task 4）。
4. 字典和事件排序变了但内容没变：不能重复更新；忙闲同时间不应透露名称分组数量（Task 1–2）。
5. 旧数据库升级或个人查询无课表限定：好友导入和删除必须保持个人数据完全不变（Task 3）。

## 文件结构和接口约定

以下源码路径均相对 `app/src/main/java/cn/limpu/hita/`；JVM 测试在对应 `app/src/test/java/cn/limpu/hita/`，设备测试在 `app/src/androidTest/java/cn/limpu/hita/`。实际写入时使用完整路径。

协议纯 Kotlin 包：`feature/timetableshare/protocol/`，不依赖 Context、Room 或 EventItem。数据库沿用 `data/model/timetable/share/` 和 `data/source/dao/`；Repository 在 `data/repository/`；界面在 `ui/timetable/share/` 和 `ui/timetable/friend/`。

统一协议模型（Task 1 实现）：

```kotlin
enum class ShareMode { FULL, BUSY }
data class SharedPeriod(val startMinute: Int, val endMinute: Int)
data class SharedTerm(
    val timetableName: String, val termName: String,
    val startMillis: Long, val endMillis: Long,
    val zoneId: String = "Asia/Shanghai"
)
data class SharedOccurrence(
    val startMillis: Long, val endMillis: Long,
    val courseIndex: Int? = null, val place: String? = null
)
data class SharedTimetable(
    val shareId: String, val nickname: String, val mode: ShareMode,
    val term: SharedTerm, val periods: List<SharedPeriod>,
    val courses: List<String>, val occurrences: List<SharedOccurrence>
)
enum class ShareError {
    NO_TOKEN, MULTIPLE_TOKENS, UNSUPPORTED_VERSION, TOO_LONG,
    CORRUPT_DATA, DECOMPRESSED_TOO_LARGE, INVALID_FIELDS, EMPTY_SCHEDULE
}
class ShareFormatException(val error: ShareError) : IllegalArgumentException(error.name)
class TimetableShareCodec {
    fun encode(snapshot: SharedTimetable): String
    fun decodeMessage(message: String): SharedTimetable
    fun canonicalJson(snapshot: SharedTimetable): String
    fun decodeJson(json: String): SharedTimetable
    fun digest(snapshot: SharedTimetable): String
}
```

上述是接口约定而非待提交的无实现类；实现时一次加入模型及对应方法体。所有非法协议输入统一抛出 ShareFormatException，ViewModel 转成 string resource；意外 I/O 错误单独处理，不吞掉协程 CancellationException。

### Task 1: 严格协议编解码和稳定摘要

**Files:** Create `feature/timetableshare/protocol/SharedTimetable.kt`, `ShareLimits.kt`, `TimetableShareCodec.kt`, `StrictShareJson.kt`。Test `feature/timetableshare/protocol/TimetableShareCodecTest.kt`, `StrictShareJsonTest.kt`, `ShareFixtures.kt`。

**Consumes:** 无数据库依赖。**Produces:** 上述完整协议接口。

- [x] 创建可复用测试 fixture 和首个失败测试，数据基于真实日期而非单周节次：

```kotlin
internal fun fullFixture() = SharedTimetable(
    "11111111-1111-4111-8111-111111111111", "小林", ShareMode.FULL,
    SharedTerm("秋季课表", "2026年秋季", 1788105600000L, 1798646400000L),
    listOf(SharedPeriod(510, 615)), listOf("高等数学"),
    listOf(SharedOccurrence(1788136200000L, 1788142500000L, 0, "A101"))
)
@Test fun roundTripFromWholeChatMessage() {
    val codec = TimetableShareCodec()
    val input = fullFixture()
    assertEquals(input, codec.decodeMessage("这是我的课表：\n${codec.encode(input)}\n复制到HITA"))
}
@Test fun unsupportedVersionHasSpecificError() {
    val error = assertThrows(ShareFormatException::class.java) {
        TimetableShareCodec().decodeMessage("HITA2:AAAA")
    }
    assertEquals(ShareError.UNSUPPORTED_VERSION, error.error)
}
```

- [x] 执行 `.\gradlew.bat :app:testDebugUnitTest --tests "cn.limpu.hita.feature.timetableshare.protocol.*" --console=plain`，确认因新类缺失而失败。
- [x] 实现模型、限制和候选提取：先检查整段消息长度，再收集 HITA 数字版本前缀；多个前缀返回 MULTIPLE_TOKENS。对唯一候选执行字符/长度检查，版本不是 1 返回 UNSUPPORTED_VERSION。不能通过过滤非法字符把坏口令修成好口令。
- [x] 实现流式 GZIP 解压上限和 UTF-8 严格解码；GZIP 完整 EOF 校验 CRC，拒绝尾随垃圾和多 member。编码也先校验并检查解压前 JSON 及最终口令长度。CRC 只检测损坏，不承诺身份认证。
- [x] 用 JsonReader 逐 token 构造 JSON 树，每个 object 使用 Set 检查重复键、限制嵌套深度为 8；禁止 lenient 模式、尾随 token 和非整数时间。按设计白名单读取固定数组长度和模式字段。时间以 Asia/Shanghai 的自然日边界校验。
- [x] 实现规范化：完整课程分组按名称及其排序后的出现记录确定顺序，重编号；完全相同分组可合并。事件按开始、结束、课程编号及地点排序；重复完整记录仍保留。忙闲按同日重叠/相邻区间合并。固定字段顺序序列化，SHA-256 返回小写十六进制，不使用 GZIP 字节作为摘要。
- [x] 添加真实失败用例代码：

```kotlin
@Test fun orderingDoesNotChangeDigest() {
    val codec = TimetableShareCodec()
    val first = fullFixture().copy(occurrences = listOf(
        SharedOccurrence(1788136200000L, 1788142500000L, 0, "A101"),
        SharedOccurrence(1788741000000L, 1788747300000L, 0, "B202")
    ))
    assertEquals(codec.digest(first), codec.digest(first.copy(occurrences = first.occurrences.reversed())))
}
@Test fun invalidInputNeverReturnsPartialData() {
    val codec = TimetableShareCodec()
    listOf("", "HITA1:!!!", "HITA1:AAAA HITA1:AAAA", "x".repeat(131073))
        .forEach { assertThrows(ShareFormatException::class.java) { codec.decodeMessage(it) } }
}
```

- [x] StrictShareJsonTest 用固定原始 JSON 验证重复 v/n、未知键、缺失键、字符串时间、浮点时间、索引 -1/超字典、空字典和安排、busy 中含 c、跨日超 24h、作息逆序、日期上下界、课程/安排数量及字符串码点边界。构建大重复字节 GZIP 验证解压上限；修改 trailer 字节验证 CRC；给合法流追加字节验证尾随数据拒绝。
- [x] 固定一个可解析的 HITA1 样本保存在测试源码，不用 encode 生成预期值；复跑 Task 1 测试，记录结果。

### Task 2: 个人课程白名单快照和学期身份

**Files:** Create `feature/timetableshare/protocol/ShareSnapshotBuilder.kt`, `ShareTermResolver.kt`。Test `feature/timetableshare/protocol/ShareSnapshotBuilderTest.kt`, `ShareTermResolverTest.kt`。

**Consumes:** SharedTimetable/ShareMode、Timetable、List<EventItem>。**Produces:**

```kotlin
data class ShareTermIdentity(val key: String, val displayName: String)
object ShareTermResolver {
    fun resolve(code: String?, startMillis: Long): ShareTermIdentity
}
class ShareSnapshotBuilder {
    fun build(timetable: Timetable, events: List<EventItem>, shareId: String,
              nickname: String, mode: ShareMode): SharedTimetable
}
```

- [x] 编写失败测试：

```kotlin
@Test fun onlyClassEventsAreSharedIncludingManualCourses() {
    val tt = Timetable().apply {
        name = "秋季课表"
        startTime = java.sql.Timestamp(1788105600000L)
        endTime = java.sql.Timestamp(1798646400000L)
    }
    val events = EventItem.TYPE.values().map { type -> EventItem().apply {
        this.type = type; timetableId = tt.id; name = "数学"
        source = EventItem.SOURCE_MANUAL
        from = java.sql.Timestamp(1788136200000L)
        to = java.sql.Timestamp(1788142500000L)
    } }
    val result = ShareSnapshotBuilder().build(tt, events,
        "11111111-1111-4111-8111-111111111111", "小林", ShareMode.FULL)
    assertEquals(1, result.occurrences.size)
    assertEquals(listOf("数学"), result.courses)
}
```

- [x] 运行 Task 1 的测试命令，确认新增 builder 测试失败。
- [x] 实现按 timetableId 和 CLASS 白名单筛选；所有匹配记录必须合法，不跳过坏记录。subjectId 只做本地分组，输出连续匿名编号；只输出设计的白名单。busy 构建时不生成课程字典，place/courseIndex 为 null，课表名为固定忙闲名称，再规范化合并。
- [x] 学期 code 解析兼容 `BENBU:`/`WEIHAI:`/`SHENZHEN:` 前缀、`YYYY-YYYY1`（TermItem.getCode 实际格式）及 `YYYY-YYYY-1`；校验后一年度等于前一年+1，1/2/3 映射秋/春/夏。只把正常化学年学期作为 termKey，原始 code 不出协议。手动格式使用 Shanghai 日期年份及 1–7 月春/8–12 月秋。
- [x] 增加忙闲隐私测试，编码后解压 JSON 断言不包含课程名、地点、subjectId、teacher、code；验证更换名称分组仍输出同样 busy 时段。复杂周次测试列出第 1/3/7 周及临时改为星期六的真实时间，assertEquals 完整 occurrence 列表，不用 encode/decode 自己构造预期。
- [x] ShareTermResolverTest 覆盖三校区、旧 code、夏季、非法格式回退、同学期调整开始日期 key 不变、跨学期 key 改变。运行 targeted 单测至通过，保留现有 ScheduleCodec 测试结果。

### Task 3: Room 迁移、分享身份与确认导入事务

**Files:** Create `data/model/timetable/share/ShareIdentityEntity.kt`, `FriendTimetableEntity.kt`, `data/source/dao/TimetableShareDao.kt`, `data/source/dao/FriendTimetableDao.kt`, `data/repository/TimetableSharingRepository.kt`, `FriendTimetableRepository.kt`, `feature/timetableshare/protocol/FriendImportPolicy.kt`。
Modify `data/AppDatabase.kt`, `app/build.gradle`（仅设备测试 Room 依赖及 schema assets）、生成 `app/schemas/cn.limpu.hita.data.AppDatabase/15.json`。
Test `feature/timetableshare/protocol/FriendImportPolicyTest.kt`, `data/timetableshare/FriendTimetableInstrumentedTest.kt`, `TimetableShareMigrationTest.kt`。

**Consumes:** Task 1–2 接口、AppDatabase 及其 timetable/event DAO。**Produces:**

```kotlin
enum class ImportKind { NEW, SAME, UPDATE }
data class FriendImportPreview(val snapshot: SharedTimetable, val kind: ImportKind,
    val expectedDigest: String?, val existingRemark: String?)
sealed interface FriendSaveResult {
    data class Saved(val shareId: String) : FriendSaveResult
    data class Unchanged(val shareId: String) : FriendSaveResult
    data class NeedsConfirmation(val preview: FriendImportPreview) : FriendSaveResult
}
class TimetableSharingRepository @Inject constructor(db: AppDatabase) {
    fun observePersonalTimetables(): LiveData<List<Timetable>>
    suspend fun generate(timetableId: String, nickname: String, mode: ShareMode): String
}
class FriendTimetableRepository @Inject constructor(db: AppDatabase) {
    fun observeFriends(): LiveData<List<FriendTimetableEntity>>
    fun observeFriend(shareId: String): LiveData<FriendTimetableEntity?>
    suspend fun preview(message: String): FriendImportPreview
    suspend fun confirm(preview: FriendImportPreview): FriendSaveResult
    suspend fun rename(shareId: String, remark: String)
    suspend fun delete(shareId: String)
}
object FriendImportPolicy {
    fun classify(incomingDigest: String, existingDigest: String?): ImportKind
}
```

- [x] 添加失败的纯策略测试：

```kotlin
@Test fun classifiesNewDuplicateAndUpdate() {
    assertEquals(ImportKind.NEW, FriendImportPolicy.classify("a", null))
    assertEquals(ImportKind.SAME, FriendImportPolicy.classify("a", "a"))
    assertEquals(ImportKind.UPDATE, FriendImportPolicy.classify("b", "a"))
}
```

- [x] 运行 `.\gradlew.bat :app:testDebugUnitTest --tests "cn.limpu.hita.feature.timetableshare.protocol.FriendImportPolicyTest" --console=plain` 确认失败。
- [x] 实现身份 entity/DAO：主键 timetableId+termKey，unique shareId，INSERT IGNORE 后读取已有 ID。好友 entity 字段固定为 shareId、nickname、remark（nullable）、termName、startMillis、endMillis、mode、snapshotJson、contentDigest、importedAt、updatedAt。列表按 updatedAt 降序及 shareId 次级排序。
- [x] MIGRATION_14_15 使用与 Room entity 完全一致的 CREATE TABLE/INDEX，暴露迁移供测试；entities 及 abstract DAO 注册、版本 15、迁移链新增。新增 `androidTestImplementation 'androidx.room:room-testing:2.8.4'`，androidTest assets 指向 schemas；保留 1–14 JSON。
- [x] generate 使用 `withContext(Dispatchers.IO)` 与 `db.runInTransaction` 读取选定 Timetable/CLASS 事件及创建身份，锁外执行 codec；不存在的课表返回可展示错误。先验证快照，避免空/坏课表创建不必要身份。
- [x] preview 严格解码及查询，无写入；confirm 在一个 Room 事务中核对 expectedDigest/当前存在性，再更新或返回 NeedsConfirmation，保留当前 remark 和 importedAt。相同内容返回 Unchanged，不能调用 REPLACE 更新时间。好友 Repository 不调用个人 DAO。
- [x] 添加设备测试的基本隔离断言：

```kotlin
@Test fun importDoesNotTouchPersonalEvents() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    try {
        val before = db.eventItemDao().getEventsDuringSync(0, Long.MAX_VALUE)
        val repo = FriendTimetableRepository(db)
        val preview = repo.preview(TimetableShareCodec().encode(fullFixture()))
        assertTrue(repo.confirm(preview) is FriendSaveResult.Saved)
        assertEquals(before, db.eventItemDao().getEventsDuringSync(0, Long.MAX_VALUE))
    } finally { db.close() }
}
```

设备测试独立建立同内容 fixture，不从 JVM test 源集导入 fullFixture；context 使用 ApplicationProvider.getApplicationContext。新增实体/Repository 不依赖 UI，可直接初始化。

- [x] 设备用例先保存个人 Timetable/Subject/EventItem，再验证导入、备注、重复、更新、删除前后的个人主键及字段逐项不变；更新保留 remark/importedAt，重复保留 updatedAt；预览 A 后导入 B、预览后删除、同时新建、修改备注后确认均验证正确结果。两次身份生成必须返回相同 UUID。
- [x] MigrationTest 使用 MigrationTestHelper 从历史 schema 创建 v14，插入个人三张表数据，执行 MIGRATION_14_15 并 validateDroppedTables=true 验证 v15；核对个人记录及新表可写。迁移测试必须真实执行，不能只比 SQL 字符串。
- [x] 运行 targeted JVM tests 和 `.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`；可用设备运行 `.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=cn.limpu.hita.data.timetableshare --console=plain`。无设备标记“编译通过，设备测试未运行”。

### Task 4: 分享页面及异步结果防串用

**Files:** Create `ui/timetable/share/TimetableShareActivity.kt`, `TimetableShareViewModel.kt`, `TimetableShareScreen.kt`, `ShareFormState.kt`。
Modify `ui/timetable/detail/TimetableDetailActivity.kt`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`。
Test `ui/timetable/share/ShareFormStateTest.kt`。

**Consumes:** TimetableSharingRepository。**Produces:** 内部 Activity intent extra `timetableId`；分享页可选择课表、昵称、模式、生成、复制及系统分享。

- [x] 添加失败测试并定义纯状态接口：

```kotlin
data class ShareFormState(val timetableId: String, val nickname: String = "好友",
    val mode: ShareMode = ShareMode.FULL, val revision: Long = 0,
    val token: String? = null, val generating: Boolean = false) {
    fun edit(id: String = timetableId, name: String = nickname,
             selectedMode: ShareMode = mode): ShareFormState
    fun accept(generationRevision: Long, result: String): ShareFormState
}
@Test fun staleGenerationDoesNotReplaceCurrentForm() {
    val old = ShareFormState("a")
    val edited = old.edit(id = "b")
    assertNull(edited.accept(old.revision, "HITA1:old").token)
    assertEquals(ShareMode.FULL, old.mode)
}
```

- [x] 运行 `.\gradlew.bat :app:testDebugUnitTest --tests "cn.limpu.hita.ui.timetable.share.ShareFormStateTest" --console=plain` 确认失败，然后实现 edit 清空旧 token/revision+1，accept 只接受当前 revision。编辑时取消上次生成 Job，revision 检查仍保留。
- [x] Hilt ViewModel 观察个人列表，用已选 Timetable 及 Task 2 builder 获取完整课程数/安排数，加载统计同样在后台一致读事务中完成；在 `TimetableSharingRepository.kt` 新增 `data class ShareSummary(val name: String, val termName: String, val courseCount: Int, val occurrenceCount: Int)` 和 Repository `suspend fun describe(timetableId: String): ShareSummary`。describe 不创建 shareId。这个步骤还会修改 Task 3 创建的 `TimetableSharingRepository.kt`。
- [x] Compose 页面包含课表下拉选择、名称/学期/计数、昵称 OutlinedTextField、默认完整模式 RadioButton 及忙闲说明、生成 Button。加载/无数据禁用生成；错误使用 ShareError 到 R.string 的显式映射；昵称编辑以码点校验。
- [x] 生成弹窗显示可选择文本并复制到 ClipboardManager；系统分享：

```kotlin
val send = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_TEXT, token)
}
startActivity(Intent.createChooser(send, getString(R.string.timetable_share_action)))
```

copy 使用 ClipData.newPlainText，成功提示来自 strings.xml。禁止对外 ACTION_SEND 接收入口；新 Activity exported=false。详情页通过显式 Intent 传当前课表 ID，保留 ICS。
- [x] 新页面采用 HitaComposeTheme/HiltBaseActivity/ComposeViewBinding；screen 仅接收状态和回调，不持有 DAO。运行 targeted test 及 assembleDebug；手动检查旋转后表单状态、复制内容及系统分享菜单。

### Task 5: 固定时区的只读好友周视图

**Files:** Create `ui/timetable/friend/FriendWeekProjection.kt`, `FriendTimetableDetailActivity.kt`, `FriendTimetableDetailViewModel.kt`, `FriendTimetableDetailScreen.kt`。
Modify `ui/main/timetable/TimetableFragment.kt`, `ui/main/timetable/views/TimetableOverlapLayout.kt`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`。
Test `ui/timetable/friend/FriendWeekProjectionTest.kt`, 回归现有 `TimetableOverlapLayoutTest` 和 `TimetableWeekNumberTest`。

**Consumes:** SharedTimetable、observeFriend。**Produces:**

```kotlin
object FriendWeekProjection {
    fun firstMonday(snapshot: SharedTimetable): Long
    fun weekNumber(snapshot: SharedTimetable, mondayMillis: Long): Int
    fun moveWeek(snapshot: SharedTimetable, mondayMillis: Long, delta: Int): Long
    fun initialMonday(snapshot: SharedTimetable, nowMillis: Long): Long
    fun events(snapshot: SharedTimetable, mondayMillis: Long, busyLabel: String): List<EventItem>
}
```

- [x] 先写失败测试，覆盖默认时区改变而日期不变：

```kotlin
@Test fun projectionIsIndependentOfDeviceZone() {
    val original = TimeZone.getDefault()
    try {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val monday = FriendWeekProjection.firstMonday(fullFixture())
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
        assertEquals(monday, FriendWeekProjection.firstMonday(fullFixture()))
        assertEquals(1, FriendWeekProjection.weekNumber(fullFixture(), monday))
    } finally { TimeZone.setDefault(original) }
}
```

- [x] 执行 `.\gradlew.bat :app:testDebugUnitTest --tests "cn.limpu.hita.ui.timetable.friend.FriendWeekProjectionTest" --console=plain` 确认失败；实现 java.time LocalDate/ZoneId Shanghai 与 plusWeeks、学期边界 clamp、窗口 overlap 筛选。适配 EventItem 的 id/subjectId 仅在内存用 shareId+快照编号组成，busy name 使用传入 stringResource，place 为空。
- [x] ReadOnlyTimetableWeek 增加可选 displayTimeZone（默认系统时区），只读好友调用明确 Shanghai；向 TimetableWeekContent、TimetableDowHeader、TimetableGrid、TimetableEventLayer 和所有日期/分钟/星期计算传递该参数。替换对应链路中 TimeTools.getDow/hour/minute/printTime 的默认时区调用，个人入口保留默认行为。冲突卡片和事件详细时间也使用该时区，不能只改周标题。
- [x] TimetableOverlapLayout 当前 arrange/conflictCards 内部也调用 EventItem.getDow。分别增加参数 `dayOfWeek: (EventItem) -> Int = { it.getDow() }`，两处 groupBy/日期比较改用该回调；好友布局传 Shanghai helper，个人调用保持默认。新增测试用跨午夜 UTC 但同日 Shanghai 的两条重叠事件，验证 overseas 默认时区下仍形成同一冲突组。
- [x] 只读卡片点击用本页 AlertDialog 展示课程名称、Shanghai 日期/时间、地点；busy 不展示课程详情。不调用 EventsUtils，不暴露个人编辑/添加菜单，不启动提醒。显示备注优先标题、学期及周次；列表删掉当前条目时提示已删除并退出。
- [x] 增加实际周次测试：第一周开始是非周一、调课星期六、单双周空周、期末非周日、学期外初始日期；验证 moveWeek 边界及 busy 内存事件没有地点/教师；验证共享组件星期和分钟 helper 在上述两种设备时区下给出相同值。
- [x] 执行 Task 5 targeted tests、已有周布局与周次测试及 assembleDebug。设备上检查三校区不同作息、冲突课程、跨日晚间课程：跨日安排按日分段展示但点击保留原始完整时间，不能丢失第二天占用。

### Task 6: 好友列表、导入预览和管理入口

**Files:** Create `ui/timetable/friend/FriendTimetableActivity.kt`, `FriendTimetableViewModel.kt`, `FriendTimetableScreen.kt`, `FriendImportDialog.kt`。
Modify `ui/timetable/manager/TimetableManagerActivity.kt`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`。
Test `ui/timetable/friend/FriendImportUiStateTest.kt`（纯状态）、扩展 Task 3 设备测试。

**Consumes:** FriendTimetableRepository/Task 5 Activity。**Produces:** 完整好友列表→导入→预览→保存/更新→只读查看路径，以及修改备注/确认删除。

- [x] 创建纯状态 `FriendImportUiState(input: String, preview: FriendImportPreview? = null, loading: Boolean = false, revision: Long = 0)`；`edit(text)` 清掉预览并增加 revision，`accept(generationRevision, preview)` 只接受当前 revision。失败测试确认改输入后旧解析预览不能用于确认：

```kotlin
@Test fun changedInputInvalidatesPreview() {
    val preview = FriendImportPreview(fullFixture(), ImportKind.NEW, null, null)
    val initial = FriendImportUiState("old", preview)
    val changed = initial.edit("new")
    assertNull(changed.preview)
    assertNull(changed.accept(initial.revision, preview).preview)
}
```

- [x] 运行 `.\gradlew.bat :app:testDebugUnitTest --tests "cn.limpu.hita.ui.timetable.friend.FriendImportUiStateTest" --console=plain` 确认失败，再实现状态和 Hilt ViewModel。所有解析、确认、备注/删除任务用 viewModelScope；不可保存时禁用确认；NeedsConfirmation 替换预览但不自动重试保存。
- [x] 好友列表使用 LazyColumn，名称备注优先、Shanghai 格式更新时间及学期；右上导入按钮、空态提示。每项提供打开、备注 Dialog 和删除确认 Dialog。备注校验 40 码点，清空变 null；delete 只按 shareId 删除好友表。
- [x] 导入多行 TextField + 解析按钮，预览显示模式/昵称/学期/课程或占用列表；NEW“保存课表”、SAME“已导入，打开课表”、UPDATE“更新课表”，说明替换快照并保留备注。最多 4096 安排使用 LazyColumn，不一次布局全部；错误保持输入可编辑，失败不导航。
- [x] TimetableManagerScreen 添加 onOpenFriends 回调与“好友课表”入口，不改变个人列表的数据源。所有新 Activity 注册 exported=false，通过显式 Intent 导航；配置变化后保留输入和状态。
- [x] 运行 targeted JVM test、全量 `:app:testDebugUnitTest` 和 assembleDebug；验证没有新增网络服务或分享表 join 到个人查询。

### Task 7: 整体验证、口令测量与交付说明

**Files:** Create `docs/timetable-sharing.md`, `feature/timetableshare/protocol/ShareTokenSizeTest.kt`（JVM test 路径）。更新设计/计划中的实际执行记录。不要将用户真实课程或生成口令提交到仓库。

**Consumes:** 完整功能。**Produces:** Debug APK、验证证据、设备验收状态、协议兼容说明和已知限制。

- [x] 添加基于不同安排密度的合成测量测试；标记为合成数据，不能代替真实课表长度测量：

```kotlin
@Test fun denseTermFitsTokenLimitWithoutDroppingOccurrences() {
    val codec = TimetableShareCodec()
    val base = fullFixture()
    val dense = base.copy(occurrences = (0 until 100).map { day ->
        SharedOccurrence(1788136200000L + day * 86400000L,
            1788142500000L + day * 86400000L, 0, "A101")
    })
    val token = codec.encode(dense)
    assertTrue(token.length <= 65536)
    assertEquals(100, codec.decodeMessage(token).occurrences.size)
}
```

- [x] 静态审查：rg 搜索新 feature/UI/Repository 的 Log、timetableDao/eventItemDao 写入、网络调用、EventsUtils、GlobalScope、硬编码中文；忙闲原始 JSON 检查详情泄漏；只有分享 Repository 可读取个人 DAO。
- [x] 执行 `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`。使用当前可用 Python 执行 `scripts/check_repository.py`；根据 README_DEV 确认 JDK17/SDK35+36，不提交本地环境配置。
- [x] 检查 adb devices；有设备则执行 Task 3 的 connectedDebugAndroidTest 命令；若不可用记录未运行及原因。只在用户授权安装的可用测试设备上安装 APK进行产品验收，不把构建成功称为跨设备验收成功。
- [x] 真实验收记录三校区和手动课表的来源、完整/忙闲课程数、安排数、JSON 字节数、压缩字节数及口令长度；无真实数据则明确“尚未测量”。两台设备验证微信整段消息、断网导入、更新确认、备注保留、重复与删除隔离。Android 系统时区改为 New York 后检查好友课表仍显示校园时间。
- [x] 将 APK 路径、实际运行的测试计数/失败、未运行的设备项目及风险写进 docs/timetable-sharing.md；用 `git diff --check` 和限定路径 diff 检查范围。向用户汇报可审阅结果，commit/push 仍等待明确指令。

## 自审结果与执行方式

设计 §1–5 对应 Task 1–2/4；§6–7 对应 Task 3；§8 对应 Task 4–6；§9 对应每任务测试及 Task 7；§10 对应隔离与提交纪律。Review Focus 五项分别有状态/时区/事务/隐私/迁移测试。定义的 DTO、Repository 方法和 intent extra 在各任务一致；设备 fixture 独立于 JVM 源集。

推荐当前会话由主助手逐项执行（Native），协议→数据库→UI 的接口依赖较多，保持一个实现上下文便于检查数据隔离。也可选子代理实施与独立逐项审查（Subagent-driven），将产生更多上下文开销。等待用户审阅计划并选择方式；实施前不创建产品代码、依赖或脚手架。
