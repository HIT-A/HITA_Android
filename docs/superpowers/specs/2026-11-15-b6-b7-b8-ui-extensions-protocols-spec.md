# B6/B7/B8 UI、扩展栏与协议 spec

日期：2026-11-15
分支：`feat/ios-parity-migration`
主设计：[2026-11-15-ios-parity-migration-design.md](2026-11-15-ios-parity-migration-design.md)
对应 plan：[2026-11-15-b6-b7-b8-ui-extensions-protocols.md](../plans/2026-11-15-b6-b7-b8-ui-extensions-protocols.md)

## 范围

| ID | 内容 | 批 |
|---|---|---|
| A-02 | 底部 mini 课程条 | B6 |
| A-04 | 小组件深链与配置 | B6 |
| — | 「扩展」栏框架 | B7 |
| E-01 | 校园卡 | B7 |
| E-02 | 静音仓预约 | B7 |
| E-03 | 大学城图书馆 | B7 |
| E-04 | 深圳实验中心 | B7 |
| D-06 | 培养方案三校区 | B8 |
| C-09 | 高校课表协议导入 | B8 |

## 一、A-02 底部 mini 课程条

### 现状

`MainScreen.kt:259-260` 有 `DockTabBar`；无跨 tab 的课程条（grep `MiniCourse|课程条|coursestrip` 无相关实现）。

### 目标

在主壳底部（Dock 之上）显示当前/下一节课的紧凑条，点击进入课程详情；无课或已结束时不显示。

### 设计

新增 `ui/main/MiniCourseBar.kt`，作为 `MainScreen` 内 `Box` 的独立层，位于 `DockTabBar` 上方，仅在 `selectedTab` 属于内容型 tab 时显示。

数据：复用 `FragmentTimelineViewModel` 的今日投影。为避免跨 Fragment 依赖，抽出纯函数：

```kotlin
data class MiniCourseState(val title: String, val place: String?, val timeText: String, val progress: Float?, val phase: Phase)
enum class Phase { ONGOING, UPCOMING, NONE }

object MiniCourseResolver {
    fun resolve(events: List<EventItem>, now: Long): MiniCourseState
}
```

`MainViewModel` 订阅课表数据并产出 `MiniCourseState`，`MainScreen` 仅渲染——不新增数据源。

**主题**：容器用 `hitaGlassCardColors(glassAlpha = 0.5f)` + `hitaGlassCardBorder(0.3f)`；文字 `onSurface`/`onSurfaceVariant`；进度环用 `colorScheme.primary`。所有 7 套风格通过同一套令牌生效。

**交互**：点击 → 与今日页课程卡同一路径打开课程详情（复用现有详情入口，不新建）。

**避让**：键盘弹出时隐藏；课表页与更多页也显示（用户已要求跨 tab），但全屏对话框时不显示。

### 验收

- 单测：`MiniCourseResolver` 覆盖进行中/即将开始/无课/已结束、跨两个事件边界。
- 手工：在今日、时间表、待办各 tab 下观察条的存在与点击跳转；亮/暗两种模式确认对比度。

## 二、A-04 小组件深链与配置

### 现状

- `today_widget_info.xml:12` 与 `today_slim_widget_info.xml:12` 的 `android:configure` 均被注释（且指向外部天气组件）。
- `TodayWidget.kt:66-71` 的 `EVENT_CLICK` 分支为空实现；只把 `eventId` 塞进 extra。
- `MainActivity` 无 `getIntent`/`onNewIntent` 解析（grep 已确认）。

### 目标

- 点小组件中的课程 → 打开 App 并定位到该课程详情。
- 小组件可配置：展示哪份课表（沿用 `DisplayScopeResolver` 已有范围）与背景样式。

### 设计

**深链**：`TodayUtils.setUpOneWidget` 里对列表项设置 `PendingIntent` 模板（已存在），改用 `fillIn` 携带 `eventId` + `date`。`MainActivity.onCreate`/`onNewIntent` 解析这两个 extra，转给 `viewModel` 的既有「打开课程详情」能力。

**配置页**：新增 `ui/widgets/today/WidgetConfigureActivity`，在 `today_widget_info.xml` 与 slim 版本启用 `android:configure`。配置项：
- 显示范围：复用 `DisplayScopeResolver` 的 `ScopeOption` 列表（与主课表顶栏同一来源）。
- 背景：两档（跟随主题 / 深色）。不做图片背景（需 AppWidget 位图缓存与清理，超出本批）。

结果写入 `SharedPreferences("widget_prefs")`，键为 `appWidgetId`。`onDeleted` 清理。

### 验收

- 单测：`WidgetPrefs` 读写与删除；范围映射纯函数。
- 手工：添加小组件 → 配置 → 选课表与背景 → 点课程进入详情。

## 三、「扩展」栏框架（B7 核心）

### 位置（已核实）

`NavigationFragment.kt` 的 `NavigationScreen`，插入点：`ShortcutCard(课表管理)` 之后、`NavigationGroup(navi_jw_title)` 之前（`:520-543`）。该页持有 `easToken`。

### 可见性（纯函数，必测）

新增 `ui/main/navigation/CampusExtension.kt`：

```kotlin
enum class CampusExtension(target: Int, titleRes: Int, subtitleRes: Int) {
    CAMPUS_CARD, SILENT_POD, LIBRARY, LAB_CENTER;

    companion object {
        fun available(campus: EASToken.Campus?, loggedIn: Boolean): List<CampusExtension>
    }
}
```

规则：`loggedIn && campus == EASToken.Campus.SHENZHEN` 才返回四项；否则返回空列表。`available()` 为空 → **整栏不渲染**。

### 主题

容器与行完全复用 `NavigationGroup` / `NavigationRow` / `CircleIcon`（`NavigationWidgets.kt:40-42,79-84,131`），零硬编码颜色。注意 `CircleIcon` 在 Persona5 风格内有既有硬编码色（`:133-134`），新增行沿用即可，不新增。

### 验收

- 单测 `CampusExtensionTest`：未登录 → 空；本部登录 → 空；威海登录 → 空；深圳未登录 → 空；深圳登录 → 四项。
- 单测（可选，若 compose 测试基础具备）：空列表时 `NavigationGroup` 不被调用。

## 四、E-01 / E-02 / E-03 / E-04

### 共同约束（已核实）

四项都需要**各自门户的独立会话**，现有教务 cookie 不够用：

| 扩展 | 主机 | 会话 | 可复用的既有实现 |
|---|---|---|---|
| 校园卡 | `dxcdp.hitsz.edu.cn` | CAS → `t` → 请求头 `token` | 无 |
| 静音仓 | `www.utszlib.edu.cn`（网关 `www-utszlib-edu-cn-s.hitsz.edu.cn`） | cookie `pima.space-reservation-fg.sid` | 无 |
| 图书馆 | `www.utszlib.edu.cn/smart-wall_api` | 公网可匿名；精确数据需 cookie | 部分（匿名接口） |
| 实验中心 | `lab.hitsz.edu.cn` | `.labCenter` cookie | 无（本部 eelab 是 JWT + 另一个 host，不可复用） |

**因此本批交付分层**：

1. **可完全验证层**（无凭据）：可用性判定、请求签名、响应解析、金额/时间格式化、UI 壳与错误态。
2. **需门户层**（需真实账号）：CAS 登录握手、cookie 获取、真实数据拉取。仓库内**无 fixture**，实现后标注「未在真实门户验证」。

不臆造接口字段。凡未读到的字段一律不写进解析器。

### E-03 图书馆（唯一可匿名验证）

`SmartWallRequestSigner`：`nonce = md5(seed)`，`signature = md5("lang=<lang>nonce=<nonce>wm-receive-confirm")`，query 为 `lang/nonce/signature`（iOS `UniversityTownLibraryClient.swift:68-95`，有 XCTest 对照）。

端点为 `smart-walls/peoples`（在馆人数）与 `smart-walls/room-nums`（静音仓占用/空闲）。Android 实现：
- `data/source/web/campus/SmartWallRequestSigner.kt`（纯函数，MD5 用 `java.security.MessageDigest`）
- `data/repository/UniversityTownLibraryRepository.kt`（OkHttp 拉取 + JSON 解析）
- `ui/campus/UniversityTownLibraryActivity.kt`（卡片展示在馆人数与空间数）

### E-01 校园卡

分层：
- `CampusCardModels.kt`：余额、状态、消费统计、流水条目（字段来源：`index/getBillSummaryBySno` 的 `cardbalance`/`flagname`；`index/getBillDetailList` 的 `status/beginTime/endTime` 分页）。
- `CampusCardRepository`：需 `token` 注入；`CampusServiceSession` 接口负责取 token（实现为 WebView 会话，标注需真实门户验证）。
- `CampusCardActivity`：余额大数字 + 消费统计 + 流水列表（分组：全部/本周/本月）。

### E-02 静音仓

- `SilentPodRequestSigner`：盐 `pima_space_reservation_fg`。
- `SilentPodRepository`：空间列表/详情/联系人/我的预约；写操作（创建/取消）需 cookie。
- `SilentPodActivity`：日期选择 + 空间列表 + 预约详情。**写操作在无真实会话时置灰并提示**，不伪造成功。

### E-04 实验中心

- `LabCenterParser`：解析 `myScheduleInfoAjax` 的二维数组行（`[0]` 班号、`[1]` 课名、`[4]` 星期、`[5-6]` 节次、`[7]` 周次、`[11]` 实验室；跳过含 `[理]` 与 `-paikao` 的行），产出场次统计。
- `LabCenterRepository` + `LabCenterActivity`：本学期实验课场次与列表。

### 验收

- 单测：`SmartWallRequestSigner` 用固定 seed 对照已知 md5（与 iOS XCTest 同值）；`LabCenterParser` 用内联构造的二维数组 fixture；`CampusCard` 金额分转元与状态映射；`SilentPod` 时间区间解析。
- 手工（需门户）：四项各打开一次，验证登录握手与数据展示；失败则记录为「未验证」。
- 手工：未登录/非深圳账号时扩展栏不显示。

## 五、D-06 培养方案三校区

### 现状

`CreditStatsViewModel.kt:39-40` 官方数据仅深圳；iOS `AcademicCoursePlanningGateway.swift:159-186` web + webVPN 双路由，仅排除威海研究生。

### 目标

本部与威海本科生也能读官方培养方案；无数据时明确提示而非空白。

### 设计

扩展 `ShenzhenTrainingPlanParser` 为按校区分派的 `TrainingPlanSource`：
- 深圳：现有实现。
- 本部/威海：走各自 webvpn 入口，解析器按实际情况实现；**若教务无对应页面，返回 `Unavailable` 并提示**，不造假数据。

`CreditStatsViewModel` 去掉 `campus == SHENZHEN` 硬门，改为调用 `TrainingPlanSource.forCampus(campus)`；`Unavailable` 时展示说明卡片。

### 验收

- 单测：`TrainingPlanSource.forCampus` 分派正确；`Unavailable` 分支不产出假数据。
- 手工：威海本科生登录后看到说明或数据，不出现空表格。

## 六、C-09 高校课表协议导入

### 现状

仅支持 HITA1 口令与 ICS。iOS 有 `.js` 网页提取脚本与 `.json` 数据文件（`docs/reference/timetable-import-protocol.md`）。

### 目标

支持 `.json` 课表数据文件导入（本期只做 JSON，不做 JS 脚本执行——在 WebView 中执行第三方脚本风险过高，且无评审通道）。

### 设计

- `TimetableImportProtocol.models.kt`：v1 JSON 结构（校区、学期、节次表、课程数组）。
- `TimetableProtocolParser`：严格校验（长度上限、字段必填、时间范围），失败给具体原因。
- `TimetableProtocolImporter`：解析结果 → `EventItem` 列表 → 走既有导入仓储（复用 `IcsImportBundleBuilder` 的落库路径）。
- UI：课表管理「添加」面板新增「导入协议 JSON」，文件选择后预览确认再落库。

### 验收

- 单测：合法样本往返一致；超长/缺字段/非法时间被拒且错误信息具体。
- 手工：导入一份构造的 JSON 后课表出现对应课程。

## 七、风险

| 风险 | 处置 |
|---|---|
| B7 四项需真实门户会话，无法在无凭据环境验证 | 分层交付：纯函数与解析可测，真实抓取标注「未验证」 |
| 扩展栏位置理解偏差 | 已核实为 `NavigationFragment`，非课表管理页 |
| A-04 配置页在大屏/多实例下状态串扰 | 以 `appWidgetId` 为键，`onDeleted` 清理 |
| A-02 与 Dock/键盘/手势冲突 | 键盘隐藏、对话框隐藏；5 套主题逐一核对 |
| D-06 教务无对应页面时造假数据 | `Unavailable` 分支显式提示 |
| C-09 第三方脚本风险 | 本期只支持 JSON，不支持 JS 执行 |
