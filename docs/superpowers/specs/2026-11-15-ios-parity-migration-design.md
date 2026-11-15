# iOS → Android 功能对齐：主设计

日期：2026-11-15
分支：`feat/ios-parity-migration`
上游审计：[docs/audit/2026-11-14-ios-android-feature-diff.md](../../audit/2026-11-14-ios-android-feature-diff.md)、[迁移方案与决策清单](../../audit/2026-11-14-migration-options.md)

## 一、范围

用户批准的 26 项，按批次组织。批次编号用于提交/评审粒度，全部落在同一分支。

| 批次 | 内容 | 项数 |
|---|---|---|
| B0 | J-06 危险操作确认与回执 | 1 |
| B1 | F-03 投稿幂等、C-06 导入差异预览 | 2 |
| B2 | G-05 双弹去重、D-05 成绩分布、I-06 身份可见性、B-02 周号跳转、F-04 外链白名单 | 5 |
| B3 | A-07 提醒多档、D-07 考试提醒、F-01 分源报告与搜索历史 | 3 |
| B4 | F-02 资源预览与复制、H-02 作业字段、C-03 快照管理 | 3 |
| B5 | C-02 软隐藏与编辑历史、C-08 系统日历、I-07 一键重置 | 3 |
| B6 | A-02 mini 课程条、A-04 小组件深链与配置 | 2 |
| B7 | 扩展栏框架 + E-01 校园卡、E-02 静音仓、E-03 大学城图书馆、E-04 实验中心 | 5 |
| B8 | D-06 培养方案三校区、C-09 高校课表协议导入 | 2 |

不做（用户明确或审计判定）：D-02 教职工身份、G-06 教师账号博客、A-03 天气、E-05 校园地图、I-08 变现、J 系列 Agent 架构改造，以及审计表「不迁移」清单。

## 二、总体架构决策

### 2.1 分层约束（沿用现有架构，不新造抽象）

```
Compose/View  →  ViewModel  →  Repository  →  数据源（Room / SharedPreferences / Web / 系统服务）
```

- 纯逻辑（协议编解码、差异计算、可见性判定、签名、分档）放进可 JVM 单测的纯函数对象，不依赖 Android 类型。
- 网络与系统调用（CalendarContract、NotificationManager、WebView cookie）留在 Repository/Service 层。
- 新 UI 复用既有设计系统：`HitaTheme.tokens`、`hitaGlassCardColors`、`hitaGlassCardBorder`、`hitaStyleCardShape`、`NavigationGroup`/`NavigationRow`/`CircleIcon`。

### 2.2 数据持久化策略

**默认不升 Room 版本。** 逐项核对后，只有两处可能触及 Room：

| 项 | 是否需 Room 变更 | 决策 |
|---|---|---|
| C-02 软隐藏与编辑历史 | 是（`events` 加列 + 新表） | 升到 v19，补 `MIGRATION_18_19` 与 `AppDatabaseUpgradePathTest` 覆盖 |
| H-02 作业字段 | 否（作业存 Gson 快照在 SharedPreferences） | 只改 `HomeworkItem` + `HomeworkStore` 校验，旧数据缺字段用默认值 |

其余项一律用既有 SharedPreferences / 文件 / 内存结构，不新增数据库。

### 2.3 后台与系统能力

- 提醒类（A-07、D-07）扩展现有 `CourseReminderWorker` + `WorkManager` 单链，不新增并发链。
- 系统日历（C-08）新增 `CalendarSyncRepository`，用 `CalendarContract`，权限 `READ_CALENDAR`/`WRITE_CALENDAR`，写入幂等键 = 课表ID+课程ID+开始时间。
- 小组件（A-04）扩展现有 `AppWidgetProvider`，深链用显式 `Intent` extra，不引入新协议。

### 2.4 AI 工具边界（J-06）

不重写 Agent 架构（J-01~J-07 不做）。只做两件事：

1. `ReActTool` 返回值从纯文本扩展为「文本 + 状态」结构，`ReActToolRegistry` 记录 `succeeded/failed/outcomeUnknown`，替代当前一律 `unknown`。
2. 写类工具（`add_activity`、`submit_review`）在 `LlmChatService` 分发点插入确认步骤：状态为「待确认」时先向 UI 发确认事件，用户确认后才执行。

## 三、「扩展」栏（B7 核心）

### 3.1 位置与形态

挂载点：`NavigationFragment`（底部「更多」页）的 `NavigationScreen`，位于 `ShortcutCard`（课表管理）之后、`NavigationGroup(navi_jw_title)` 之前（`NavigationFragment.kt:520-543`）。这一处正对应用户描述的「更多界面，课表管理下面加一栏扩展」。

注意：**不是** `TimetableManagerActivity` 内部。该 Activity 全文件无 `EASToken`、无校区信息，强行接入需要新增依赖传递；而 `NavigationScreen` 已持有 `easToken`（`NavigationFragment.kt:157-162` 通过 `observeEasToken()` 获得），是唯一既满足位置要求又自然具备校区判定能力的位置。

形态：复用同包 `NavigationGroup(title = "扩展")` 包裹若干 `NavigationRow`（`NavigationWidgets.kt:40-42,79-84`），与「教务」「课程资源」分组视觉完全一致。每行 = 图标 + 名称 + 副标题。

整栏隐藏沿用同文件既有写法（`NavigationFragment.kt:583-585` 用 `if (entries.isNotEmpty()) NavigationGroup { … }`），不渲染空标题。

### 3.2 可见性规则

纯函数 `ExtensionCatalog`：

```kotlin
object ExtensionAvailability {
    fun available(
        token: EASToken?,
        hasShenzhenWebSession: Boolean,
    ): List<ExtensionEntry>
}
```

规则：

| 扩展 | 可见条件 |
|---|---|
| 校园卡 | 深圳校区 + 已登录（需 web session） |
| 静音仓 | 深圳校区 + 已登录 |
| 大学城图书馆 | 深圳校区 + 已登录 |
| 实验中心 | 深圳校区 + 已登录（本部已有实验课并入，不重复入口） |

`available()` 返回空列表时，整个「扩展」栏不渲染（不是渲染空标题）。这一条必须有单测覆盖三种情况：未登录、其他校区、深圳已登录。

### 3.3 主题适配

禁止硬编码颜色。所有颜色取自：

- 容器：`hitaGlassCardColors(glassAlpha = 0.46f)` + `hitaGlassCardBorder(alpha = 0.28f)`（与 `NavigationGroup` 完全一致）
- 文本：`MaterialTheme.colorScheme.onSurface` / `onSurfaceVariant`
- 强调/图标底色：`colorScheme.primaryContainer` + `colorScheme.primary`（`CircleIcon` 既有做法）
- 圆角：`hitaStyleCardShape(tokens.radius.lg, 14.dp)`，让 7 套风格各自生效

亮/暗对比依靠 `colorScheme` 令牌，不写 `if (isDark) Color(...) else Color(...)`。新增的扩展详情页同样只使用令牌与既有组件。

### 3.4 会话前提（关键约束）

四项扩展都需要**各自门户的独立会话**，现有教务 cookie 不足：

| 扩展 | 域名 | 会话来源 | 现有实现能否复用 |
|---|---|---|---|
| 校园卡 E-01 | `dxcdp.hitsz.edu.cn` | CAS `ids.hit.edu.cn` → 重定向取 `t` → `ssologin` 后请求头带 `token` | 否。`WebViewLoginActivity.collectCookies` 只探教务域名与当前页，不访问 dxcdp |
| 静音仓 E-02 | `www.utszlib.edu.cn`（网关 `www-utszlib-edu-cn-s.hitsz.edu.cn`） | cookie `pima.space-reservation-fg.sid` | 否，需独立 WebView 会话 |
| 图书馆 E-03 | `www.utszlib.edu.cn/smart-wall_api` | 公网接口可匿名；精确数据需 space cookie | 部分：在馆/空间数可匿名取 |
| 实验中心 E-04 | `lab.hitsz.edu.cn`（GvSun LIMS） | `.labCenter` cookie | 否。本部 eelab 是另一套（JWT + `eelabinfo-...vpn.hit.edu.cn`），不可复用 |

因此 B7 的实现策略定为：**先做可无凭据验证的部分**——可用性纯函数、签名器、解析器、UI 壳与错误态；真实抓取用可注入的 `CampusServiceSession` 接口承接，仓库内无 fixture 时标注「未验证」。不臆造字段，不把未连通的入口伪装成可用。

## 四、分批理由

- **B0 单独成批**：改动触及 Agent 工具返回契约，影响面最大，先做可与后续隔离验证。
- **B1 数据正确性**：两处都会造成重复 PR / 覆盖本地数据，优先级最高、改动面小。
- **B2 低成本横切**：五项均无数据库迁移，可合并一次回归。
- **B3 提醒**：共用 `CourseReminderWorker`，同批做避免两次改同一处。
- **B4 内容层**：都改资源/作业/快照的读取与展示，无系统权限。
- **B5 高风险系统能力**：Room 迁移 + CalendarContract 权限 + 不可逆重置，需要更谨慎的回归。
- **B6 UI 层**：mini 课程条与小组件，纯展示，风险可控但需多主题验证。
- **B7 扩展栏**：五件事共用同一栏与同一可用性判定，必须同批。
- **B8 外部协议**：培养方案依赖教务接口覆盖，协议导入依赖 WebView 注入，二者都可能被动转为「阻塞」。

## 五、验证策略

每批结束执行：

```bash
JAVA_HOME=<JDK21> gradlew.bat :app:assembleDebug
JAVA_HOME=<JDK21> gradlew.bat :app:testDebugUnitTest
python scripts/check_repository.py
```

判定：`assembleDebug` 退出 0；测试 `failures=0, errors=0` 且 `tests >= 1071`（基线）。新增测试必须能捕获真实缺陷，不写断言恒真的测试。

B7 额外要求 `--tests *ExtensionAvailability*` 全绿，并在亮/暗两套主题下人工核对（模拟器或截图对比）。

## 六、交接说明

若中途交接：先读本文件 → 再读对应批次的 spec → 再读该批次 plan。plan 中每个任务给出精确文件路径、期望失败的测试、最小实现与验收命令，无需本会话上下文即可执行。

## 七、风险

| 风险 | 触发条件 | 处置 |
|---|---|---|
| 校园门户接口不可达或需真实凭据 | B7 的 E-01/E-02/E-03 | 保留可测的签名/解析纯函数与假数据测试；真实抓取标注「未验证」，不臆造字段 |
| CalendarContract 在部分 OEM 上无默认日历 | B5 的 C-08 | 先查可写日历，无则提示「未找到可写日历」，不静默失败 |
| Room v19 迁移遗漏 | B5 的 C-02 | 迁移链测试 + 已发布版本升级路径测试 |
| 多主题下新增 UI 对比度不足 | B6/B7 | 只用令牌取色；对新增文案沿用现有 `onSurface`/`onSurfaceVariant` 层级 |
