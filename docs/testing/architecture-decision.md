# 测试架构方案对比与决策（brainstorming）

## 问题界定

任务书要求四类测试，其中第三类（仪器）已有 849 个但**零 UI 断言**，第四类（模拟机 E2E）**完全没有**。核心决策：如何在不破坏既有 1007 JVM + 849 仪器测试、不与其它 4 个 agent 冲突的前提下，补齐这两类。

## 方案对比

### 方案 A：全量引入 Compose UI Test + Espresso

- 做法：加 `ui-test-junit4`、`espresso-core` 扩展，逐页重写为 UI 断言测试。
- 优点：断言强、与生产 UI 同进程、调试好。
- 缺点：
  - 该 app 是 Compose + ViewBinding 混合、Fragment 壳，逐页写成本极高（50+ Compose 文件）。
  - 需要 Hilt 测试注入改造（当前无 `hilt-android-testing`），影响生产装配。
  - 与其它 agent 正在改的 UI 层（agent-03-ui）直接冲突。
- 结论：**否决**（超范围、冲突风险高）。

### 方案 B：仅 UI Automator 黑盒 E2E（新增独立 androidTest 源集）

- 做法：新增 `app/src/e2eTest`（或独立 `e2e` 模块），用 UI Automator 启动真实 APK，按 8 组流程断言可见文本/组件。
- 优点：黑盒、不改生产代码、与 agent-03 的 UI 改动解耦（只依赖可见文本/资源 ID）、真正验证"APK 安装后行为"。
- 缺点：只能断言可见表层，无法断言内部状态；执行慢。
- 结论：**采纳为主方案**（任务书第四类明确要求）。

### 方案 C：Robolectric 补齐 UI 层 JVM 测试

- 做法：加 Robolectric，在 JVM 上跑 Activity/Fragment。
- 优点：快。
- 缺点：Robolectric 与 Compose/Hilt/真实渲染差异大，且项目历史明确未采用（handoff 记录"JVM 侧无 Robolectric"）；引入会改变既有测试环境基线，风险高。
- 结论：**否决**（改变既有基线，收益不确定）。

### 方案 D：先补"零覆盖业务"的 JVM/仪器测试，再补 E2E

- 做法：对 §零覆盖清单（Worker/Widget/通知/更新/登录链）先写可快速执行的 JVM 或轻量仪器测试；E2E 只覆盖端到端主流程。
- 优点：单位成本最低，直接消除最大风险区（后台任务崩溃无人发现）。
- 缺点：不覆盖真实 UI 交互。
- 结论：**采纳为第二主线**，与方案 B 并行。

## 最终决策

1. **主方案 = B + D**：新增 UI Automator E2E（第四类）+ 补零覆盖业务测试（第二/三类）。
2. 不引入 Compose UI Test / Robolectric / mockk（避免改变既有 1856 测试的运行时基线）。
3. 可以引入的依赖（最小集）：
   - `androidx.test.uiautomator:uiautomator:2.3.0`（E2E 必需）
   - `androidx.work:work-testing`（Worker 测试必需，仅 androidTest）
   - `org.jetbrains.kotlinx:kotlinx-coroutines-test`（可选，用于替代 70 处 `runBlocking`；先评估兼容性）
4. 执行环境隔离：私有 AVD `hita_agent05`（端口 5570），不共享 `emulator-5554`。

## 被否决项与理由留档

- Compose UI Test：范围过大 + 与 agent-03-ui 冲突。
- Robolectric：改变既有基线，历史明确未采用。
- mockk/Mockito：现有 fake 模式一致且够用，不引入第二套约定。
