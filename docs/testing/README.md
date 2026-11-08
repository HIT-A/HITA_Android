# 测试体系总览

本目录记录 HITA_Android 的测试分层、测试集、执行方式与质量约束。基线 commit `5992ba3`。

## 四类测试

| 类 | 范围 | 命令 |
|---|---|---|
| 基本 | 编译、类型、纯逻辑 JVM 单测 | `:timetable-core:test` `:app:testDebugUnitTest` `:component:testDebugUnitTest` `:hitauser:testDebugUnitTest` |
| 数据 | Room/DAO/迁移/解析/序列化/文件读写 | `:app:testDebugUnitTest --tests 'cn.limpu.hita.data.*'` + 设备 data 包 |
| 仪器 | Activity/Fragment/Compose/权限/生命周期/WorkManager/WebView | 各模块 `connectedDebugAndroidTest` |
| 模拟机 | 真实 APK 安装后的黑盒用户流程 | `scripts/test-suites.sh e2e` |

## 六个测试集

`scripts/test-suites.sh <fast|full|data|instrumentation|e2e|perf|all>`，耗时写入 `build/test-reports/suites.jsonl`。

## 报告

`python3 scripts/test-report.py --root .` 聚合 Gradle JUnit XML；只统计实际存在的 XML，不推断未执行的测试。

## 硬约束

1. 未经实际执行不得记为通过；静态测试数不能代替 XML。
2. 不删除测试来变绿；每个删除项必须记录原目的、理由、重复验证、替代位置、替代结果。
3. 不使用 `room-testing` 的 `MigrationTestHelper`（kotlinx-serialization 1.8 冲突）；用 `createLegacyAppDatabase`。
4. `style` 的 JVM 源集不存在，其 `testDebugUnitTest` 恒为 NO-SOURCE，不计作测试通过。
5. 多 agent 并行时各自使用独立 AVD 与端口，不要共享 `emulator-5554`。
