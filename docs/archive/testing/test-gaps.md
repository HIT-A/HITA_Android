# 测试缺口清单（阶段一）

按任务书五类维度列缺口，每条给出证据与优先级。

## 一、界面渲染与交互（最严重）

| ID | 缺口 | 证据 | 优先级 |
|---|---|---|---|
| G1 | 无任何 Espresso / Compose UI 断言 | 全仓 `onView`、`createAndroidComposeRule`、`ui-test-junit4` 均为 0 | P0 |
| G2 | 无 UI Automator 黑盒测试 | 无 `uiautomator` 依赖、无 `UiDevice` | P0（已补 1 类，见 StartupNavigationE2eTest） |
| G3 | 12 处 `ActivityScenario.launch` 仅验证不崩溃，无元素断言 | 11 个文件均为状态回调观察 | P1 |

## 二、后台任务与系统集成（第二严重）

| ID | 缺口 | 证据 | 优先级 |
|---|---|---|---|
| G4 | `CourseReminderWorker` 零测试 | 全仓仅本 agent 新增文件引用 | P0（**已补 5 项**） |
| G5 | `WidgetRefreshWorker` / `WidgetUtils` 零测试 | 同上 | P0（**已补 3 项**） |
| G6 | `LiveCourseScheduler` 零测试 | 同上 | P0（**已补 4 项**） |
| G7 | `CourseSelectionAlarmScheduler` 无断言 | 仅 harness 偶然引用 | P1 |
| G8 | `UpdateFlowCoordinator`（应用内更新）零测试 | 0 引用 | P1 |
| G9 | WorkManager 约束/重试策略未验证 | 无 `work-testing` 使用 | P0（**依赖已加入**） |

## 三、登录与会话

| ID | 缺口 | 证据 | 优先级 |
|---|---|---|---|
| G10 | `WebViewLoginActivity` / `PopUpLoginEAS` 零测试 | 0 引用 | P0（任务书要求"登录模拟流程"） |
| G11 | WebVPN / CAS 多跳链路无测试 | 无相关测试 | P1 |

## 四、数据与兼容

| ID | 缺口 | 证据 | 优先级 |
|---|---|---|---|
| G12 | Room 迁移链已覆盖，但缺少"降级拒绝"测试 | `AppDatabaseUpgradePathTest` 已覆盖升级，未覆盖 downgrade | P1 |
| G13 | JSON 序列化边界已覆盖（`StrictShareJsonTest`），但 Gson 全局策略无测试 | 无 Gson 配置测试 | P2 |
| G14 | 文件读写错误路径（磁盘满/权限拒绝）无测试 | 无对应注入 | P2 |
| G15 | 历史数据兼容：已覆盖 v9/11/14/17/18 | 良好 | — |

## 五、非功能

| ID | 缺口 | 证据 | 优先级 |
|---|---|---|---|
| G16 | 无覆盖率统计 | 无 JaCoCo | P1（任务书第 8 项） |
| G17 | 无变异测试 | 无 PIT | P2（"必要时"） |
| G18 | 无性能专项测试 | 无 benchmark 模块 | P2 |
| G19 | 无 Flaky 检测（重复执行统计） | 无机制 | P1（已有 11 处 sleep 风险） |

## 六、测试基础设施

| ID | 缺口 | 处置 |
|---|---|---|
| G20 | JVM 无 `coroutines-test` | 维持现状（70 处 `runBlocking` 可工作），仅在需要虚拟时间时引入 |
| G21 | 无统一测试运行器 | **已新增 `scripts/test-suites.sh`** |
| G22 | 无跨模块报告聚合 | **已新增 `scripts/test-report.py`（已验证）** |
| G23 | `room-testing` 死依赖 | **已移除** |
| G24 | CI 无 E2E 阶段 | CI 属禁改范围，记录为移交项 |

## 本轮已实际补齐（本 agent 交付）

| 交付 | 文件 | 用例数 |
|---|---|---|
| 课程提醒 Worker | `app/src/androidTest/.../data/work/CourseReminderWorkerInstrumentedTest.kt` | 5 |
| Widget 刷新 Worker | `app/src/androidTest/.../data/work/WidgetRefreshWorkerInstrumentedTest.kt` | 3 |
| 直播课程调度 | `app/src/androidTest/.../feature/livecourse/scheduler/LiveCourseSchedulerInstrumentedTest.kt` | 4 |
| 启动导航 E2E（UI Automator） | `app/src/androidTest/.../e2e/StartupNavigationE2eTest.kt` | 2 |
| 测试集运行器 | `scripts/test-suites.sh` | — |
| 报告聚合器 | `scripts/test-report.py` | — |
