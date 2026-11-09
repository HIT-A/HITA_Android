# 四类测试体系设计（阶段一）

## 分层与命令

| 层级 | 覆盖对象 | 命令 | 目标耗时 |
|---|---|---|---|
| L1 基本（静态+JVM） | 编译、类型、Lint、纯逻辑 | `:timetable-core:test` `:app:testDebugUnitTest` `:component:testDebugUnitTest` `:hitauser:testDebugUnitTest` | < 3 min（热态） |
| L2 数据 | Room/DAO/迁移/解析/序列化/文件 | `:app:testDebugUnitTest --tests '*data*'` + 设备侧 `connectedDebugAndroidTest` 的 data 包 | < 8 min |
| L3 仪器 | Activity/Fragment/Compose/权限/生命周期/WorkManager/WebView | 模块 `connectedDebugAndroidTest` | < 15 min |
| L4 模拟机 E2E | APK 安装、启动、真实用户流程黑盒 | `uiautomator` 脚本 + `adb` | < 10 min |

## 六个测试集

| 集合 | 内容 | 命令 |
|---|---|---|
| fast | 无 Room/无仪器的高频单测 | `:timetable-core:test` + `:app:testDebugUnitTest --tests '*ui.main*'` |
| full | 全部 JVM 单测 | `testDebugUnitTest`（4 模块）+ `:timetable-core:test` |
| data | 数据层专项 | `:app:testDebugUnitTest --tests '*data*'` + 设备 data 包 |
| instrumentation | 全部 androidTest | 4 模块 `connectedDebugAndroidTest` |
| e2e | 模拟机黑盒 | `scripts/e2e/run-e2e.sh` |
| perf | 启动耗时/关键路径 | `scripts/e2e/measure-startup.sh` |

## 第四类（模拟机 E2E）方案

- 环境：私有 AVD `hita_agent05`（Pixel 6 / API 35 / x86_64），端口 5570，`-read-only`，与其他 agent 的 5560/5574 隔离。
- 技术选型：**UI Automator**（新增 `androidTestImplementation 'androidx.test.uiautomator:uiautomator:2.3.0'`）。
  - 理由：黑盒、可跨 Activity、不依赖 Compose 语义树；Espresso 只能同进程。
  - 备选（否决）：Compose `createAndroidComposeRule` 需 `ui-test-junit4` 且要求在同一进程内，不能验证 APK 安装后的真实行为。
- 流程集（8 组）：
  1. 启动与主导航（MainActivity 启动 → 底部 4 tab 切换）
  2. 登录模拟（MockWebServer 提供 CAS/教务响应）
  3. 课表加载/切换/导入/修改/恢复
  4. 成绩、考试、课程查询
  5. AI Agent 会话与附件
  6. 通知、Widget、后台任务
  7. 网络错误、空数据、异常恢复
  8. Activity 重建、进程重启、持久化
- 判据：每组脚本输出 `PASS/FAIL` + `logcat` 片段 + 截图，写入 `e2e-results/`。

## 并行安全规则

- Gradle：每 agent 独立 `GRADLE_USER_HOME` 或至少 `--max-workers=2`；`:app:testDebugUnitTest` 不与其它模块并发。
- 模拟器：一 agent 一 AVD 一端口，禁止共享 `emulator-5554`。
- 文件：不在共享工作树写 `build/`，各 worktree 独立。
