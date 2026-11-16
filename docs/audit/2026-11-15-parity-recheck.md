# 已迁移功能 vs iOS 实现：复核差异清单

日期：2026-11-15
分支：`feat/ios-parity-migration`
对照对象：`D:/project/HITA/HITA-Aura`（iOS）已实现代码，不是审计报告的自述。
执行记录见 [2026-11-15-migration-execution.md](2026-11-15-migration-execution.md)。

本文件只记录**实际读过两端代码后确认**的差异。每条给出 iOS 与 Android 的具体位置、差异性质、以及「是否值得在本轮修」。

## 结论汇总

| 编号 | 功能 | 差异性质 | 本轮处理 |
|---|---|---|---|
| R-01 | 课程提醒的时间点模型 | Android 只有「课前」，iOS 有「课前 + 下课前」双档 | **已修**：`endLeads` + 双 phase 候选 + 设置页 |
| R-02 | 软隐藏的粒度 | Android 按单条事件，iOS 另有课程组级隐藏与恢复入口 | 记录，不修（影响面小，改 DAO 需升库版本） |
| R-03 | 编辑历史的可见性 | Android 已写历史但无任何 UI 入口 | **已补齐（2026-11-16）**：时间表页顶栏增加历史图标、逐课次恢复及状态提示；拒绝重复恢复和覆盖后续改动 |
| R-04 | 快照保留策略 | Android 固定 12 条，iOS 可选 5/10/不限 | **已修**：上限持久化，写快照按用户选择裁剪 |
| R-05 | 考试提醒的范围 | iOS 有独立的 `examRemindersEnabled` 总开关 | 记录，不修（Android 已有 `examLeads` 控制，空列表即关闭） |
| R-06 | 提醒的天气附加 | iOS 课前提醒可附加天气 | 不修（A-03 天气本就在禁止项） |

---

## R-01 课程提醒缺少「下课前」提醒

**iOS** `HITAAura/Support/CourseReminders/CourseReminderStore.swift:124-190`

```swift
enum CourseReminderPoint {
    case beforeStart(minutes: Int)
    case beforeEnd(minutes: Int)
```

`CourseReminderPreferences` 同时持有 `remindsBeforeStart` / `remindsBeforeEnd` 与两个独立分钟数，默认各 5 分钟。UI（`CourseReminderSettingsView.swift:20-21`）提供两组预设：

```swift
private static let beforeStartPresets = [0, 5, 10, 15, 30]
private static let beforeEndPresets = [0, 5, 10]
```

**Android** `app/src/main/java/cn/limpu/hita/data/work/ReminderRuleSet.kt:8-22`

```kotlin
data class ReminderRuleSet(
    val leads: List<Int> = listOf(DEFAULT_LEAD_MINUTES),
    val periodOverrides: Map<Int, Int> = emptyMap(),
    val examLeads: List<Int> = emptyList(),
)
```

只有「提前几分钟」，**没有任何下课前提醒的表达能力**。`grep -c "beforeEnd|下课前" app/src/main/java/cn/limpu/hita/data/work/` 返回 0。

**影响**：用户无法收到「快下课了」的提醒。iOS 的 `beforeEnd` 默认开启，因此这是默认行为差异，不只是可选项缺失。

**方案**：`ReminderRuleSet` 增加 `endLeads: List<Int>`，`ReminderEvent` 已有 `to` 时间戳可用；`ReminderRules.normalize` 同步裁剪；Worker 在计算 `nextDue` 时同时产出 start 与 end 两类到期项，key 前缀区分（`event#start#15` / `event#end#5`）。默认值保持 iOS 的 5 分钟。

**验收**：`CourseReminderScheduleTest` 覆盖「下课前提醒按 to - lead 触发」与「start/end 同分钟不互相覆盖」。

---

## R-02 软隐藏缺少课程组级粒度与恢复入口

**iOS** `HITAAura/Models/Timetable/EditableLocalTimetableDocument.swift:60-62`

```swift
var hiddenSessionKeys: [String]
var hiddenCourseKeys: [String]
```

两级：单次课（session）与整门课（course group）。并且有对称的恢复命令（`TimetableEditCommands.swift:62-64`、`124-126`）：

```swift
try session.restoreHiddenSession(in: document.id, sessionKey: key)
try session.restoreHiddenGroup(in: document.id, groupKey: key)
```

**Android** `app/src/main/java/cn/limpu/hita/data/model/timetable/EventItem.kt:48-49`

```kotlin
/** 1 means a teaching-source course was hidden instead of physically deleted. */
var hidden: Int = 0
```

只有单条事件级标志。删除整门课时逐条置位，没有「整门课」这个概念，也没有「查看已隐藏项并恢复」的入口——`EventEditHistoryStore.restore()` 存在，但 `grep -rln "EventEditHistoryStore" app/src/main/java/cn/limpu/hita/ui` 无结果。

**影响**：用户隐藏一门课后，想撤回必须靠版本快照整体回滚，粒度太粗。

**为何本轮不修**：课程组级隐藏需要引入 group key 概念并改 Room 表结构（升 DB 版本），属于「改变已批准方案」的范畴，按停止条件应停下记录而非静默扩大改动。粒度可先由 R-03（编辑历史 UI）缓解——单条恢复可用后，「整门课恢复」可以逐条操作。

---

## R-03 编辑历史已落库但无 UI 入口

**2026-11-16 补充**：下文为原复核时的缺口描述。现已在时间表页顶栏通过版本历史样式图标接入「删除历史」，与分享、换肤、添加日程并列，按课次展示名称、课程时间、删除时间及恢复状态；恢复经统一写队列执行，在同一 Room 事务中完成内容校验、课程写入、删除记录标记与恢复历史追加。已恢复、已有后续变化及无效记录禁止恢复；成功后刷新小组件。复用 v19 历史表，不新增数据库字段。

**Android** `app/src/main/java/cn/limpu/hita/data/repository/EventEditHistoryStore.kt`

已实现：`recordCreate` / `recordUpdate` / `delete`（教务课软隐藏、手动课物理删除）/ `restore(historyId)` / `history(timetableId)`，并写入 `event_edit_history` 表。

但：
- `grep -rln "EventEditHistoryStore" app/src/main/java/cn/limpu/hita/ui` → 无结果
- `grep "编辑历史" app/src/main/res/values/strings.xml` → 无结果

即：数据完整，用户完全看不到，也点不到恢复。

**iOS** 有对应能力（`TimetableEditCommands.swift:60-140` 的删除/恢复命令，以及 `TimetableEditQueries.swift:309` 的 `hiddenEdits` 查询）。

**方案**：在课表管理页加「编辑历史」入口，列出 `history(timetableId)`，每条显示动作、时间、课程名，提供「撤销」按钮调用 `restore(historyId)`。这是把已有能力接出来，不改数据结构。

---

## R-04 快照保留策略不可配置

**iOS** `HITAAura/Models/Timetable/TimetableVersionSnapshot.swift:7-23`

```swift
enum TimetableSnapshotRetentionLimit {
    case five, ten, unlimited
    static let defaultValue: Self = .five
```

**Android** `TimetableSnapshotStore.kt` 的 `MAX_PER_TERM = 12` 是私有常量，虽然本轮加了 `trim(limit)` 与 UI 的四档选择（5/12/30/100），但**选择没有持久化**——下次写入快照时 `publishCommitted` 仍按 12 裁剪，用户选的 100 会被立刻缩回。

**方案**：把保留上限存进偏好（`PreferenceStore`），`publishCommitted` 读取它而不是硬编码常量。

---

## R-05 考试提醒缺少独立总开关

**iOS** `CourseReminderPreferences.examRemindersEnabled: Bool`（默认 true），与课程通知分开控制。

**Android** `ReminderRuleSet.examLeads` 存在（本轮已接），但没有独立开关——只能通过清空 `examLeads` 表达「关闭」。

**影响**：清空列表会让用户丢失已选的提前量，重新开启时无法恢复。属可用性瑕疵，不是功能缺失。

**为何不修**：影响面小，且改偏好 schema 会牵动本轮刚加的迁移逻辑，收益不抵风险。

---

## 本轮未完成（明确遗留）

| 项 | 现状 | 原因 |
|---|---|---|
| ~~E-02 创建/取消预约的页面入口~~ | **已补**：空间卡片可发起预约（选时段 + 填手机号），预约卡片可取消；`SilentPodClient` 支持 POST 与 body 参与签名 | 多人静音仓需同行成员列表，依赖联系人接口，本轮按「单人到访」实现；服务端会拒绝人数不足并给出原因 |
| ~~考试提醒的实际调度~~ | **已补**：`ExamReminderWorker` + `ExamReminderScheduler`，按 iOS 的固定四档 24h/12h/1h/30m 排程，跟随课程提醒总开关 | 不需要独立设置入口——iOS 本身就用固定档位 |
| ~~R-02 课程组级软隐藏~~ | **已补**：按 `subjectId` 整门课隐藏/删除，可恢复；不需要升库版本 | iOS 用 `groupKey`，Android 没有课程组概念，`subjectId` 语义等价 |
| 真实校园门户联调 | 未执行 | 需要真实账号与校园网环境；本轮只用文档里的公开验算样例做了纯函数验证 |

## 复核方法说明

- 每条都读了两端的实际实现文件，未依赖审计报告的自述。
- 「本轮处理」只统计**实际改了代码并有测试或编译验证**的项；仅记录的项在下一轮才可能动。
- 未覆盖：资讯/博客、搜索、成绩、教室、协议导入等已在上轮验证过的域，本轮只抽查了课表与提醒域。

## 模拟器实测发现并修复的两个真实缺陷

1. **启动即崩：`ClassNotFoundException: cn.limpu.hita.HApplication`**
   - 现象：模拟器安装后启动立刻退出，`Hilt_HApplication` 与 `HApplication` 都不在主 dex。
   - 根因：kapt 增量处理在某次中断后留下不完整状态，Hilt 的应用级产物未生成进主 dex；
     同时项目未声明 `multiDexEnabled`，D8 把应用类排到非主 dex 分片。
   - 修复：`app/build.gradle` 显式 `multiDexEnabled true`；必要时 `--rerun-tasks` 重建 kapt 产物。
   - 验证：修复后 `topResumedActivity` 为 `MainActivity`，主界面正常渲染。

2. **提醒设置页文案与回显错误**
   - 下课前档位文案写成「课前 N 分钟（结束）」，语义矛盾 → 改为「下课前 N 分钟」。
   - 从设置页返回后「课程提醒」副标题仍是旧值（`onStart` 只刷新开关，没有重算摘要）→ 补上重算。

## 模拟器实测结论

| 验证项 | 结果 |
|---|---|
| 应用冷启动 | 通过（修复后主界面「今日空闲」正常渲染） |
| 「更多」页提醒行 | 通过（显示「课前 15 分钟提醒 · 下课前 5 分钟提醒」） |
| 提醒设置页 | 通过（两组预设渲染，保存后 `leads` 15→30 已写入 SharedPreferences） |
| WorkManager 排程 | 通过（`CourseReminderWorker` 与 `ExamReminderWorker` 均已 ENQUEUED） |
| 校园扩展入口 | 未登录时整栏隐藏（符合门控契约，`CampusExtensionTest` 覆盖） |
| 四个校园页未登录降级 | **通过**：`CampusExtensionActivityInstrumentedTest` 4 项在 AVD 上零失败 |
| 提醒设置页（instrumented） | **通过**：`CourseReminderSettingsActivityInstrumentedTest` 保存-读回契约成立 |
| 设置页返回后摘要刷新 | 通过（实测 15→30 后返回，副标题同步更新） |
| 下课前档位文案 | 通过（实测显示「下课前 5 分钟 / 下课前 10 分钟」） |

未在模拟器上验证：真实门户数据（需要读者账号与会话）、通知的实际弹出（需要等待到点或伪造时间）。