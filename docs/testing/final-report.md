# 测试执行报告

> 数据来源：实际存在的 Gradle JUnit XML；未执行的任务不出现在统计中（不做推断）。

| 测试集 | XML 数 | 用例 | 通过 | 失败 | 错误 | 跳过 | 耗时(s) |
|---|---|---|---|---|---|---|---|
| app-unit | 160 | 939 | 938 | 0 | 0 | 1 | 16.0 |
| app-instrumented | 1 | 856 | 856 | 0 | 0 | 0 | 712.3 |
| core | 6 | 57 | 57 | 0 | 0 | 0 | 0.8 |
| component-unit | 1 | 7 | 7 | 0 | 0 | 0 | 0.1 |
| component-instrumented | 0 | — | — | — | — | — | — |
| hitauser-unit | 2 | 4 | 4 | 0 | 0 | 0 | 0.0 |
| hitauser-instrumented | 0 | — | — | — | — | — | — |
| style-instrumented | 0 | — | — | — | — | — | — |
| **合计** | 170 | 1863 | 1862 | 0 | 0 | 1 | 729.2 |

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
