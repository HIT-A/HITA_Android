# 覆盖率与变异测试结果

## 覆盖率（JaCoCo）

**当前状态：未产出真实数据。**

- 项目未配置 JaCoCo，`build.gradle` 中无 `jacoco` 插件或 `testCoverageEnabled`。
- 本轮未引入 JaCoCo：它会改变所有 4 个模块的构建任务图，并与其它 4 个 agent 正在进行的构建改造冲突；属于需要用户确认的构建级变更。
- 因此本报告**不提供任何覆盖率百分比**——没有实测数据就不写数字。

可供后续落地的命令（已核对与当前 AGP 8.10.1 兼容）：

```bash
./gradlew :app:testDebugUnitTest jacocoTestReport
```

需要在 `app/build.gradle` 增加 `apply plugin: 'jacoco'`、`jacoco { toolVersion = "0.8.12" }` 与
`jacocoTestReport { reports { xml.required = true; html.required = true } }`。

## 变异测试（手工选择性变异，已实测）

项目无 PIT 依赖。为验证断言是否真的有效，在唯一纯 JVM 模块 `timetable-core`（57 项测试、
0 Android 依赖）上手工注入 2 个语义变异，逐个执行 `:timetable-core:test` 后恢复源码。

| # | 变异 | 位置 | 结果 | 证据 |
|---|---|---|---|---|
| M1 | `monday.dayOfWeek == MONDAY && …` 改为 `\|\|`（放宽周一校验） | `TimetableComparisonEngine.compareAll` | **被杀死** | `57 tests completed, 1 failed` |
| M2 | `ShareLimits.TOKEN_CHARS` 65536 → 131072（放宽 token 上限） | `ShareLimits` | **被杀死** | `57 tests completed, 1 failed` |

杀死率 2/2（本样本）。源码已全部恢复（`git status` 干净，命令输出见下）：

```
$ git status --short
（空）
```

判据依据任务书：不做机械 100% 覆盖率/变异分数追求，只抽验断言有效性。
样本量小，结论仅说明这两处断言确实承载了真实约束，不代表全模块变异质量。

## 与既有约束的一致性

- `docs/refactor-handoff.md` 记录 CI 只用 Gradle 原生 XML，不引入额外覆盖率门禁。
- 引入 JaCoCo 属于构建改造，需与 CI 同步；本轮不改 CI（属禁改范围）。
