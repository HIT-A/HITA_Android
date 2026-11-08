# 测试前后耗时对比（阶段一基线）

## 基线（本 agent 实测）

| 场景 | 耗时 | 备注 |
|---|---|---|
| 首次冷构建 + 全量 JVM | 849s | `assembleDebug` + `testDebugUnitTest`，115 task 全执行 |
| app 单测（热态、限 2 worker） | 147s | 97 task，仅 2 执行 |
| JVM 用例纯执行 | 16.9s | 1007 项，XML `time` 求和 |
| core 模块 | 0.8s | 57 项 |
| component 模块 | 0.1s | 7 项 |
| hitauser 模块 | 0.0s | 4 项 |

## 结论

- **用例执行不是瓶颈**（16.9s），**编译与 Gradle 启动**占 849s 的绝大部分。
- 因此速度优化的重点是构建配置，不是测试数量。
- 仪器测试耗时未在本轮取得（被并行环境的资源竞争阻断），不填入臆测数字。

## 阶段二实测补充

| 测试集 | 实测 | 备注 |
|---|---|---|
| full（JVM 全量） | 189s（含 `assembleDebug`，热态） | 1007 项，用例本身 16.9s |
| instrumentation（全量 app） | **712.3s / 12m46s** | 856 项，0 失败 |
| instrumentation（Worker 子集） | 4m22s | 15 项 |
| instrumentation（LiveCourse 子集） | 33s | 4 项 |
| e2e（UI Automator） | **62.1s**（构建 4m16s） | 5 项，0 失败 |
| debug APK 构建 | 241s（3m57s） | 47.5 MB |

结论：**全量仪器测试 12m46s 是最大的固定成本**，用例层面全部很快。
分层执行（fast/e2e 子集）能把反馈时间压到分钟级。
