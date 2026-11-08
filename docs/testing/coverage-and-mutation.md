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

## 变异测试（PIT / 自定义变异）

**当前状态：未执行。**

- 项目无 PIT 依赖，`timetable-core` 是唯一适合做变异测试的纯 JVM 模块（57 项测试，0 依赖 Android）。
- 选择范围（若执行）：`timetable-core` 的 `TimetableShareCodec`、`StrictShareJson`、`TimetableComparisonEngine` 三个类，共约 500 行，规模可控。
- 判据：变异存活率 > 30% 视为断言不足，需补断言；不追求 100% 杀死率（任务书明确不机械追求）。

## 与既有约束的一致性

- `docs/refactor-handoff.md` 记录 CI 只用 Gradle 原生 XML，不引入额外覆盖率门禁。
- 引入 JaCoCo 属于构建改造，需与 CI 同步；本轮不改 CI（属禁改范围）。
