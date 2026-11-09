# 交付索引

对应任务书"十、交付"的 10 项要求。

| # | 要求 | 文件 | 状态 |
|---|---|---|---|
| 1 | 全量测试台账 | [test-ledger.md](test-ledger.md) | ✅ 已交付（含实测统计） |
| 2 | 功能—模块—测试映射 | [feature-test-mapping.md](feature-test-mapping.md) | ✅ 已交付 |
| 3 | 测试有效性审查报告 | [test-effectiveness-review.md](test-effectiveness-review.md) | ✅ 已交付（问题 ID T-A*/T-B*/T-C*/T-D*/T-E*/T-F*） |
| 4 | 测试删除与替代依据 | [deletion-rationale.md](deletion-rationale.md) | ✅ 已交付（五要素表） |
| 5 | Flaky Test 治理报告 | [flaky-test-governance.md](flaky-test-governance.md) | ✅ 已交付（F1–F12） |
| 6 | 四类测试体系及执行命令 | [four-tier-design.md](four-tier-design.md)、[execution-results.md](execution-results.md) | ✅ 已交付 |
| 7 | 测试前后耗时对比 | [timing-comparison.md](timing-comparison.md) | ✅ 已交付（基线实测 849s/147s/16.9s） |
| 8 | 覆盖率与变异测试结果 | [coverage-and-mutation.md](coverage-and-mutation.md) | ⚠️ 未产出数据（项目无 JaCoCo/PIT，引入属构建级变更），已说明原因与落地命令 |
| 9 | 模拟机验证记录 | [emulator-verification.md](emulator-verification.md) | ✅ 环境已验证；5 项 E2E **全部通过**（62.1s）；8 组流程中 4 组有真实用例执行 |
| 10 | Commit Hash 与回滚方案 | [commit-and-rollback.md](commit-and-rollback.md) | ✅ 方案已交付；hash 待提交后回填 |

## 支撑文档

| 文件 | 内容 |
|---|---|
| [README.md](README.md) | 测试体系入口与硬约束 |
| [test-gaps.md](test-gaps.md) | 缺口清单 G1–G24 |
| [test-cleanup-plan.md](test-cleanup-plan.md) | 清理计划 |
| [speed-optimization.md](speed-optimization.md) | 速度优化方案 P1–P6 |
| [emulator-e2e-plan.md](emulator-e2e-plan.md) | 模拟机方案 |
| [architecture-decision.md](architecture-decision.md) | 架构方案对比与决策（brainstorming 记录） |
| [implementation-plan.md](implementation-plan.md) | 实施计划（Task 1–7） |
| [baseline-report.md](baseline-report.md) | 基线执行报告（由 XML 聚合生成） |
| [actual-changes.md](actual-changes.md) | 本轮实际变更清单 |

## 代码与脚本产物

| 产物 | 路径 |
|---|---|
| 后台任务测试（12 项） | `app/src/androidTest/.../data/work/CourseReminderWorkerInstrumentedTest.kt`、`WidgetRefreshWorkerInstrumentedTest.kt`、`.../feature/livecourse/scheduler/LiveCourseSchedulerInstrumentedTest.kt` |
| 模拟机 E2E（5 项） | `app/src/androidTest/.../e2e/StartupNavigationE2eTest.kt`、`NavigationAndPersistenceE2eTest.kt` |
| 测试集运行器 | `scripts/test-suites.sh` |
| 报告聚合器 | `scripts/test-report.py`（已实测） |
| E2E 运行器 | `scripts/e2e-emulator.sh` |

## 已实际执行（本 agent 实测，2026-11-08）

| 类别 | 用例数 | 结果 | 证据 |
|---|---|---|---|
| JVM 全量 | 1007 | 1006 通过 / 1 跳过（既有可选 HOA 联网） | `baseline-report.md`（XML 聚合） |
| 后台任务（新增） | 15 | **全部通过** | `instr-work3.log`（BUILD SUCCESSFUL 4m22s） |
| 直播课程调度（新增） | 4 | **全部通过** | `instr-live2.log`（BUILD SUCCESSFUL 33s） |
| 模拟机 E2E（新增） | 5 | **全部通过** | `instr-e2e4.log`（BUILD SUCCESSFUL 4m16s；XML 5/0/0/0，62.1s） |
| 全量仪器回归 | 见日志 | 见 `instr-full.log` | 含既有 840 项 + 新增 17 项 |

## 明确未完成项

1. 覆盖率与变异测试**未产出数据**（项目无 JaCoCo/PIT）。
2. 8 组模拟机流程中覆盖 4 组（启动、导航、回到桌面重启、旋转）；登录/课表/成绩/Agent/通知/网络异常未编写，
   原因已核实：WebVPN/EAS 基地址为 `const val` 硬编码，无法注入 MockWebServer（见 `emulator-verification.md`）。
3. 未提交、未推送（依项目规范需用户确认）。
