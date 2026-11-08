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

### 六个测试集：脚本执行记录（`build/test-reports/suites.jsonl`）

| 测试集 | 命令 | 状态 | 耗时 | 用例 |
|---|---|---|---|---|
| fast | `scripts/test-suites.sh fast` | **PASS** | 20s | JVM 子集（ui.main + utils） |
| full | `scripts/test-suites.sh full` | **PASS** | 37s | JVM 全量 1007 项 |
| data | `scripts/test-suites.sh data`（JVM） | **PASS** | 11s | `cn.limpu.hita.data.*` |
| data（设备） | 同上第二段 | **PASS** | 239s | 663 项 |
| instrumentation | `scripts/test-suites.sh instrumentation` | 见 `instrumentation.log` | — | app 856 + component 7 + hitauser 11 + style 8 |
| e2e | `scripts/test-suites.sh e2e` | **PASS** | 113s（dumpsys 服务端过滤后，原 332s） | 11 项，0 失败 |
| perf | `scripts/test-suites.sh perf` | **PASS** | 211s | 2 项（首启 4.78s / 再次 14.18s） |

### 速度优化实测效果

| 优化 | 前 | 后 | 收益 |
|---|---|---|---|
| E2E 的 `dumpsys` 改为设备端 `grep` 过滤（避免数百 KB 经 shell 桥传输） | 332s | **113s** | −66% |

### 关键固定成本

| 项 | 实测 |
|---|---|
| 冷构建 + JVM 全量 | 849s（首次，115 task 全执行） |
| 热态 JVM 全量 + assembleDebug | 189s |
| JVM 用例纯执行 | 16.9s（1007 项） |
| app 全量仪器 | 712.3s（856 项） |
| debug APK 构建 | 241s（47.5 MB） |

结论：**全量仪器测试 12m46s 是最大的固定成本**，用例层面全部很快。
分层执行（fast/e2e 子集）能把反馈时间压到分钟级。
