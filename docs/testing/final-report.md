# 测试执行报告（最终）

**实测时间**：2026-11-08 · **基线** `5992ba3` · **分支** `refactor/agent-05-testing`

| 类别 | 用例 | 通过 | 失败 | 错误 | 跳过 | 耗时 |
|---|---|---|---|---|---|---|
| JVM（4 模块 + core） | 1007 | 1006 | 0 | 0 | 1（既有可选 HOA 联网） | 16.9s（用例）+ 3m8s（构建） |
| 仪器（app，全量） | **863** | **863** | 0 | 0 | 0 | 402.6s（7m35s 总；`notClass=MainThemeInstrumentedTest`，见 I-1） |
| 仪器（component） | 7 | 7 | 0 | 0 | 0 | 1.4s |
| 仪器（hitauser） | 11 | 11 | 0 | 0 | 0 | 1.6s |
| 仪器（style） | 8 | 8 | 0 | 0 | 0 | 5.7s |
| 性能专项（perf） | 2 | 2 | 0 | 0 | 0 | 23.2s（首启 4.78s / 再次 14.18s） |
| 模拟机 E2E（`hita_test` AVD） | 11 | 11 | 0 | 0 | 0 | 60.7s（1m33s 总） |
| 模拟机 E2E（`hita_agent05` 私有 AVD） | 11 | 11 | 0 | 0 | 0 | 113s |

> 未通过实际执行的一律不记入本表。XML 见 `app/build/test-results/`、`app/build/outputs/androidTest-results/`。


> 数据来源：实际存在的 Gradle JUnit XML；未执行的任务不出现在统计中（不做推断）。

| 测试集 | XML 数 | 用例 | 通过 | 失败 | 错误 | 跳过 | 耗时(s) |
|---|---|---|---|---|---|---|---|
| app-unit | 160 | 939 | 938 | 0 | 0 | 1 | 4.9 |
| app-instrumented | 1 | 856 | 856 | 0 | 0 | 0 | 712.3 |
| core | 6 | 57 | 57 | 0 | 0 | 0 | 0.8 |
| component-unit | 1 | 7 | 7 | 0 | 0 | 0 | 0.1 |
| component-instrumented | 0 | — | — | — | — | — | — |
| hitauser-unit | 2 | 4 | 4 | 0 | 0 | 0 | 0.0 |
| hitauser-instrumented | 0 | — | — | — | — | — | — |
| style-instrumented | 0 | — | — | — | — | — | — |
| **合计** | 170 | 1863 | 1862 | 0 | 0 | 1 | 718.2 |

## 重复测试身份：0


## 最慢用例（前 20）

| 耗时(s) | 用例 |
|---|---|
| 307.57 | `cn.limpu.hita.ui.main.MainThemeInstrumentedTest.allSevenThemesRenderDrawerAndPageWithoutReplacingTheActiveFragment` |
| 20.46 | `cn.limpu.hita.e2e.NavigationAndPersistenceE2eTest.bottomNavigationShowsAtLeastOneLocalLabel` |
| 15.65 | `cn.limpu.hita.e2e.NavigationAndPersistenceE2eTest.relaunchFromRecentsKeepsAppUsable` |
| 9.37 | `cn.limpu.hita.e2e.NavigationAndPersistenceE2eTest.rotationKeepsActivityOnSameScreen` |
| 9.15 | `cn.limpu.hita.ui.main.homework.HomeworkUiInstrumentedTest.dailyScreenFiltersDatesAndSupportsEditingCompletionAndLegacyTasks` |
| 7.55 | `cn.limpu.hita.ui.main.MainUpdatePresenterInstrumentedTest.realUpdatePopupRecreationRebindsTextAndCurrentHostSkipAction` |
| 6.83 | `cn.limpu.hita.ui.main.MainActivityInstrumentedTest.homeworkTabMountsAndSurvivesActivityRecreation` |
| 6.31 | `cn.limpu.hita.ui.main.timetable.TimetableHostInstrumentedTest.mainTabDestroyViewClearsBatchAndOldCompositionBeforeViewRecreation` |
| 6.22 | `cn.limpu.hita.data.source.web.eas.LegacyEmptyClassroomSuspendContractTest.weihaiOnceMatchesFullExhaustionForAllNon200Earlier200AndAllEmptyHtml` |
| 5.97 | `cn.limpu.hita.data.source.web.eas.ShenzhenEmptyClassroomSuspendContractTest.apiOnceMatchesLegacyOverviewTwentySixWeekLimitAndNoEmptyCaching` |
| 5.86 | `cn.limpu.hita.ui.main.timetable.TimetableComponentInstrumentedTest.readOnlyWeekHandlesRealCardTapAndSingleSwipeThenClearsOldConflictSheet` |
| 5.83 | `cn.limpu.hita.e2e.StartupNavigationE2eTest.applicationStaysForegroundWithoutCrash` |
| 5.81 | `cn.limpu.hita.ui.main.timetable.TimetableBatchUiInstrumentedTest.failedRealWriteKeepsFormAndRetryCommitsBeforeClosingAndRoomRefresh` |
| 5.56 | `cn.limpu.hita.ui.main.timetable.TimetableBatchUiInstrumentedTest.oldRenderedEditAndDeleteActionsCannotMutateOrDismissReplacementSelection` |
| 5.51 | `cn.limpu.hita.data.source.web.eas.ShenzhenEmptyClassroomSuspendContractTest.apiOnceMatchesLegacyMatrixFallbacksAndInvalidDateFailure` |
| 5.49 | `cn.limpu.hita.data.source.web.eas.ShenzhenEmptyClassroomSuspendContractTest.apiOnceMatchesLegacyOverviewCacheIsolationKeyAndMatrixPriority` |
| 5.46 | `cn.limpu.hita.ui.main.MainActivityInstrumentedTest.realMainRecreationPreservesSelectedIdentityAndRestoredFragmentArguments` |
| 4.44 | `cn.limpu.hita.ui.main.MainActivityInstrumentedTest.retainedViewModelRejectsEffectsAddressedToDetachedOrReplacedHosts` |
| 4.31 | `cn.limpu.hita.ui.main.timetable.TimetableComponentInstrumentedTest.coursePlanningPreviewActuallyUsesSharedWeekAndOpensItsEventDialog` |
| 4.23 | `cn.limpu.hita.ui.main.MainUpdatePresenterInstrumentedTest.skippedUpdateIsConsumedWithoutCreatingDialog` |

## 失败/错误用例：0


## 跳过用例：1

- `cn.limpu.hita.data.source.web.HoaMultiProjectCompatibilityTest.all indexed multi project repositories support structured preview ops`
