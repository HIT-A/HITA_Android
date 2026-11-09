# 模拟机 E2E 测试方案（阶段一）

## 环境（隔离，避免与其它 4 个 agent 冲突）

| 项 | 值 |
|---|---|
| AVD | `hita_agent05`（私有，已创建） |
| 规格 | Pixel 6 / API 35 / x86_64 / 1080x2400 |
| 设备序列号 | `emulator-5570` |
| 启动命令 | `emulator -avd hita_agent05 -port 5570 -no-window -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect -read-only` |
| 加速 | WHPX 可用（实测 `accel: 0`） |
| 隔离证据 | 其它 agent 使用 `emulator-5554` / `5560` / `5574` 及 AVD `hita_test` / `hita_ci_agent06` |

## 被测 APK

- `app/build/outputs/apk/debug/app-debug.apk`，包名 `cn.limpu.hita.diag`（诊断版，与其它 agent 的 `cn.limpu.hita` 安装互不冲突）。
- 安装：`adb -s emulator-5570 install -r -t app-debug.apk`

## 技术栈选择

**UI Automator 2.3.0**（新增依赖，仅 `androidTest`/e2e 源集）。
- 理由：黑盒、跨进程、可验证真实安装 APK；不依赖生产代码的测试注入。
- 明确不用 Espresso（同进程，需改测试装配）与 Compose rule（需 `ui-test-junit4` + 同进程语义树）。

## 8 组流程与断言

| # | 流程 | 关键断言 | 依赖 |
|---|---|---|---|
| 1 | 启动与主导航 | 启动页消失、主界面出现、4 个 tab 可切换且内容切换 | 无 |
| 2 | 登录模拟 | 点登录 → WebView 打开 → MockWebServer 返回 CAS 成功 → 回到主界面且显示已登录 | MockWebServer（需可注入 URL） |
| 3 | 课表加载/切换/导入/修改/恢复 | 课表网格出现课程文本；切换学期后内容变化；导入后条目数增加；修改后文本变化；重启后仍保留 | 本地 Room |
| 4 | 成绩/考试/课程查询 | 进入成绩页 → 列表非空/空态正确 | MockWebServer |
| 5 | AI Agent 会话与附件 | 打开聊天 → 发送文本 → 出现用户消息气泡 | 无网络（离线也应显示错误） |
| 6 | 通知/Widget/后台任务 | 触发提醒后 `dumpsys notification` 出现通知；Widget 存在 | adb dumpsys |
| 7 | 网络错误/空数据/异常恢复 | 断网后进入页面显示错误态而非崩溃；恢复网络后可重试 | `svc wifi/data` 或 MockWebServer 500 |
| 8 | Activity 重建/进程重启/持久化 | 旋转/后台后状态保留；`am force-stop` 后重启数据仍在 | adb |

## 判据与产物

- 每组：`PASS`/`FAIL` + 失败时截图 + `logcat` 关键字片段。
- 产物目录：`.agent05/e2e/<flow>-<timestamp>.{md,png,log}`
- 只有实际执行并产出 PASS 的流程才在报告中记为通过。

## 风险与对策

| 风险 | 对策 |
|---|---|
| 登录依赖真实校园账号 | 一律 MockWebServer + 可控 URL 注入；若生产 URL 不可注入则记为**不可测**并报告，不伪造 |
| 与其它 agent 抢设备 | 私有 AVD + 独立端口 + `-read-only` |
| 首次启动慢/ANR | 用 `UiDevice.wait(Until.hasObject(...), 10s)`，不用 `sleep` |
