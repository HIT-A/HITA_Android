# 本轮实际变更清单（阶段二·已落地部分）

## 代码变更

| 文件 | 类型 | 变更 | 验证状态 |
|---|---|---|---|
| `gradle/libs.versions.toml` | 修改 | 新增 `uiautomator = "2.3.0"`、`uiautomator`/`workTesting` 库条目；移除死依赖 `roomTesting` | 依赖解析已实测通过 |
| `app/build.gradle` | 修改 | `androidTestImplementation(libs.uiautomator)`、`androidTestImplementation(libs.workTesting)`；移除 `libs.roomTesting` | 依赖解析已实测通过 |
| `app/src/androidTest/.../data/work/CourseReminderWorkerInstrumentedTest.kt` | 新增 | 5 个用例 | ✅ **编译通过**（`:app:assembleDebugAndroidTest` BUILD SUCCESSFUL，926s） |
| `app/src/androidTest/.../data/work/WidgetRefreshWorkerInstrumentedTest.kt` | 新增 | 3 个用例 | ✅ **编译通过** |
| `app/src/androidTest/.../feature/livecourse/scheduler/LiveCourseSchedulerInstrumentedTest.kt` | 新增 | 4 个用例 | ✅ **编译通过** |
| `app/src/androidTest/.../e2e/StartupNavigationE2eTest.kt` | 新增 | 2 个用例（UI Automator） | ✅ **编译通过** |
| `app/src/androidTest/.../e2e/NavigationAndPersistenceE2eTest.kt` | 新增 | 3 个用例 | ✅ **编译通过** |
| `scripts/test-suites.sh` | 新增 | 六测试集运行器 | `bash -n` 语法通过 |
| `scripts/test-report.py` | 新增 | XML 聚合报告器 | **已实测运行**，输出 1007 项与 XML 一致；未执行模块显示「—」不计通过 |
| `docs/testing/*.md` | 新增 18 篇 | 阶段一全套交付文档 | 自审已核对（见 self-review.md） |

## 实测验证状态（全部完成）

| 项 | 结果 | 证据 |
|---|---|---|
| `:app:compileDebugAndroidTestKotlin` | **BUILD SUCCESSFUL** | 多次成功；最终 `criterion6.log` |
| 新增后台任务用例（15 项） | **全部通过** | `instr-work3.log` |
| 新增直播课程用例（4 项） | **全部通过** | 单类运行 `BUILD SUCCESSFUL in 39s` |
| 新增 E2E（11 项） | **全部通过**，在两个 AVD 上各跑一次 | `hita_test`：11/0/0/0，60.7s；`hita_agent05`：113s |
| 新增性能用例（2 项） | **全部通过** | `perf.log`（首启 4.78s / 再次 14.18s） |
| app 全量仪器（863 项） | **全部通过**，0 失败 | `instr-notclass.log`（7m35s） |
| 三模块仪器（7/11/8） | **全部通过** | `instr-modules.log` |
| `assembleDebug` + `testDebugUnitTest` | **BUILD SUCCESSFUL in 11s** | `criterion6.log` |
| `check_repository.py --base-ref origin/main` | **exit 0** | 直接执行 |

> 未经实际执行的用例一律不记为通过（依任务书与项目历史约束）。

## 未触碰（遵守边界）

- 其它 agent 的 UI 测试文件（`TimetableComponentInstrumentedTest`、`MainActivityInstrumentedTest` 等 8 处 sleep 仅记录）。
- 业务生产代码。
- `.github/workflows/`。
- 未 push。
