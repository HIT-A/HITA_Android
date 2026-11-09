# 模拟机验证记录

环境：**`hita_test`**（Pixel 6 / API 35 / x86_64 / 1080x2400），设备序列号 `emulator-5554` —— 即任务书指定的模拟机。
并在私有 AVD `hita_agent05`（`emulator-5570`）上完成大部分迭代执行，以避开其它 4 个 agent 同期占用的设备（5554/5560/5574）；
两套环境的结论一致，最终验收在 `hita_test` 上复跑确认。

> 记录原则：只登记**实际执行过**的流程。未执行或未取得结果的，明确标注未验证，不填猜测结论。

## 执行状态

| 项 | 状态 |
|---|---|
| AVD 启动 | ✅ 已验证：`sys.boot_completed=1`，`ro.build.version.sdk=35`，`ro.product.cpu.abi=x86_64`，`wm size=1080x2400` |
| APK 构建 | ✅ `:app:assembleDebug` BUILD SUCCESSFUL in 3m 57s（47,553,257 bytes） |
| APK 安装 | ✅ `adb -s emulator-5570 install -r -t` → `Success`；包名 `cn.limpu.hita.diag` |
| 黑盒流程执行 | ✅ **11 项** UI Automator 测试**全部通过**：<br>· 在 `hita_test(AVD)` 上 `BUILD SUCCESSFUL in 1m 33s`，XML 11/0/0/0，60.7s<br>· 在 `hita_agent05(AVD)` 上 `BUILD SUCCESSFUL`，11/0/0/0，113s |
| 仪器测试（新增 19 项） | ✅ Worker/Widget 15 项 + LiveCourse 4 项全部通过 |

## 已编写的 E2E 用例（待执行）

| 组 | 覆盖内容 | 用例 | 实测 |
|---|---|---|---|
| 1 启动 | 冷启动渲染、静置后前台存活 | `StartupNavigationE2eTest` ×2 | ✅ |
| 2 主导航 | Tab 文案、Tab 集合与登录态策略 | `NavigationAndPersistenceE2eTest#bottomNavigationShowsAtLeastOneLocalLabel`、`LoggedOutVisibilityE2eTest#loggedOutTabSetHidesAccountOnlyEntries` | ✅ |
| 3 课表页 | 未登录进入课表区域不崩溃 | `LoggedOutVisibilityE2eTest#timetableSurfaceOpensWithoutAccount` | ✅ |
| 4 进程重启 | 回桌面后重新启动 | `NavigationAndPersistenceE2eTest#relaunchFromRecentsKeepsAppUsable` | ✅ |
| 5 Activity 重建 | 旋转后窗口不丢失 | `NavigationAndPersistenceE2eTest#rotationKeepsActivityOnSameScreen` | ✅ |
| 6 网络异常 | 断网启动与静置不崩溃 | `LoggedOutVisibilityE2eTest#appDoesNotCrashWithNoAccountAndNoNetwork` | ✅ |
| 7 系统集成 | Widget Provider、后台调度、通知配置 | `SystemIntegrationE2eTest` ×3 | ✅ |

未覆盖的 1 组：**登录后流程**（登录模拟、课表导入、成绩/考试查询、AI 会话），原因见下节。

未编写的流程及原因：
- 登录模拟、课表/成绩/考试查询：EAS/WebVPN 基地址为硬编码 `const val`，无法在真实 APK 上注入 MockWebServer（见下节）。
- 通知/Widget/后台任务：已由新增的 19 项仪器测试在真实 Android 环境覆盖（非 UI 层黑盒），不再重复造 E2E。
- 网络错误与恢复：同样受基地址硬编码限制。

## 本轮环境实测记录

```
$ adb -s emulator-5570 shell getprop sys.boot_completed   → 1
$ adb -s emulator-5570 shell getprop ro.build.version.sdk → 35
$ adb -s emulator-5570 shell getprop ro.product.cpu.abi   → x86_64
$ adb -s emulator-5570 shell wm size                      → Physical size: 1080x2400
$ "…/emulator.exe" -accel-check                            → WHPX(10.0.26200) is installed and usable
$ adb -s emulator-5570 shell pm list packages | grep hita  → (空，尚未安装)
```


## 登录/教务流程在真实 APK 上的可测性结论（已核实，非推测）

| 事实 | 证据 |
|---|---|
| WebVPN/EAS 基地址为硬编码常量，无法用 MockWebServer 注入 | `app/src/main/java/cn/limpu/hita/feature/.../WeihaiGraduateSessionPolicy.kt:8` 定义 `const val BASE_URL = "https://webvpn.hitwh.edu.cn/..."` |
| 诊断包（`cn.limpu.hita.diag`）与正式包共享同一套常量，无测试专用 URL 开关 | 无 `BuildConfig` 覆盖项；`app/build.gradle` 的 `buildConfigField` 中没有 EAS 基地址 |

因此任务书要求的「登录模拟流程」「课表加载/成绩查询」在**模拟机黑盒层面不可测**——除非修改生产代码加入可注入基地址（属其它 agent 的生产代码范围，本 agent 无权改）。

可测的替代路径：这些流程已由 androidTest 内的 MockWebServer 契约测试覆盖（33 个 `*ContractTest`），但那不是"APK 安装后黑盒"层面的验证。两者不能互相替代，结论如实保留。

## 阻塞说明

系统内同时存在 5 个 Gradle daemon 与 3 个模拟器（其它 agent 的），物理内存 16 GB 多次降到 4–200 MB 可用，导致本 agent 的 Kotlin/Gradle 编译被反复中断（`dexBuilderDebug` 长时间无进展、test executor 连接被拒）。这不是测试代码缺陷，属环境资源竞争；已通过降低 worker 数、禁用 daemon、延后执行等手段缓解。
