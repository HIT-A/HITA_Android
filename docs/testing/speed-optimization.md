# 测试速度优化方案（阶段一）

## 实测基线

| 项 | 实测 |
|---|---|
| 冷构建 + JVM 全量 | **849s**（首次，115 task 全执行） |
| 热态 app 单测 | **147s**（97 task，2 executed / 95 up-to-date） |
| JVM 1007 项纯执行时间 | **16.9s**（XML `time` 求和） |
| 最慢单测 | 1.67s（`SharedTimetableImporterTest` oversized ICS） |

结论：**测试本身很快（16.9s），瓶颈在编译与 Gradle 启动**。优化方向不是改测试，而是构建与调度。

## 优化项

| ID | 措施 | 预期收益 | 风险 |
|---|---|---|---|
| P1 | 拆分 fast/full 集：`fast` 仅跑无 Room、无文件 IO 的纯逻辑（约 60% 用例） | 本地反馈 < 30s | 需维护分组 |
| P2 | 测试并行：`maxParallelForks = 2`（app） | 执行时间再降约 40% | 共享静态状态冲突，需先验证 |
| P3 | forkEvery / 内存调优 | 避免 OOM、减少 GC | 需实测 |
| P4 | 禁用 `--no-daemon`（保留 daemon），独立 `GRADLE_USER_HOME` | 省 30–60s 启动 | 多 agent 内存压力 |
| P5 | 仪器测试按包分片串行，避免单进程 OOM | 稳定性 | 时间不变 |
| P6 | 删除 `room-testing` 死依赖、`style:testDebugUnitTest` NO-SOURCE 任务声明 | 减少依赖解析 | 无 |

## 明确不做

- 不因"慢"删除测试（任务书禁止）。
- 不合并不同断言的用例来降数量。
