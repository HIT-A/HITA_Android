# 测试执行报告

> 数据来源：实际存在的 Gradle JUnit XML；未执行的任务不出现在统计中（不做推断）。

| 测试集 | XML 数 | 用例 | 通过 | 失败 | 错误 | 跳过 | 耗时(s) |
|---|---|---|---|---|---|---|---|
| app-unit | 160 | 939 | 938 | 0 | 0 | 1 | 16.0 |
| app-instrumented | 0 | — | — | — | — | — | — |
| core | 6 | 57 | 57 | 0 | 0 | 0 | 0.8 |
| component-unit | 1 | 7 | 7 | 0 | 0 | 0 | 0.1 |
| component-instrumented | 0 | — | — | — | — | — | — |
| hitauser-unit | 2 | 4 | 4 | 0 | 0 | 0 | 0.0 |
| hitauser-instrumented | 0 | — | — | — | — | — | — |
| style-instrumented | 0 | — | — | — | — | — | — |
| **合计** | 169 | 1007 | 1006 | 0 | 0 | 1 | 16.9 |

## 重复测试身份：0


## 最慢用例（前 20）

| 耗时(s) | 用例 |
|---|---|
| 1.67 | `cn.limpu.hita.data.repository.SharedTimetableImporterTest.oversized friend ICS is rejected before unbounded parsing and closes stream` |
| 0.82 | `cn.limpu.hita.data.source.web.eas.BenbuExamAuthenticationTest.unrelated course table does not mask a login response` |
| 0.81 | `cn.limpu.hita.data.repository.FriendIcsMapperTest.untrustedUnicodeNamesStayInsideExportDirectory` |
| 0.69 | `cn.limpu.hita.data.source.web.HoaMultiProjectCompatibilityTest.all indexed multi project repositories support structured preview ops` |
| 0.68 | `cn.limpu.hita.agent.attachment.ChatAttachmentProcessorTest.office total expanded limit spans multiple individually valid entries` |
| 0.41 | `cn.limpu.hita.data.repository.FriendIcsMapperTest.bulkTokenTextFallsBackLosslesslyAtMessageBoundary` |
| 0.27 | `cn.limpu.hita.feature.timetableshare.protocol.ShareSnapshotBuilderTest.sourceLimitsApplyBeforeBusyMerging` |
| 0.26 | `cn.limpu.hita.feature.timetableshare.protocol.ShareTokenSizeTest.realisticSyntheticTermsPreserveEveryOccurrenceInBothModes` |
| 0.25 | `cn.limpu.hita.feature.timetableshare.protocol.TimetableShareCodecTest.encodingRejectsOversizedSourceInsteadOfTruncating` |
| 0.24 | `cn.limpu.hita.data.repository.FriendIcsMapperTest.sameNamedExportsKeepBothCalendarsAndPreviouslySharedContent` |
| 0.23 | `cn.limpu.hita.ui.design.HitaFontSchemeTest.typographyUsesTheSchemeForAllMaterialTextRoles` |
| 0.22 | `cn.limpu.hita.agent.attachment.ChatAttachmentProcessorTest.office expanded bytes and entry counts are limited independently of compressed size` |
| 0.20 | `cn.limpu.hita.ui.main.timetable.TimetableBatchCoordinatorTest.oldWriteAndQueuedNoticeCannotReachReplacementViewOrSelection` |
| 0.19 | `cn.limpu.hita.data.source.web.eas.PublicAcademicCalendarParserTest.missingVacationLabelDoesNotInventVacation` |
| 0.18 | `cn.limpu.hita.ui.main.WallpaperFilesTest.newerSelectionWinsWhenOlderCopyFinishesLast` |
| 0.15 | `cn.limpu.hita.agent.llm.ChatCompletionUsageTest.missingUsageFieldsRemainUnknownInsteadOfBecomingZero` |
| 0.14 | `cn.limpu.hita.agent.attachment.ChatAttachmentProcessorTest.actual media and document byte limits reject one extra byte and close source` |
| 0.14 | `cn.limpu.hita.data.repository.CourseSelectionExecutorTest.transport cancellation is rethrown` |
| 0.13 | `cn.limpu.hita.data.repository.CourseSelectionExecutorTest.one execution token preserves concurrent response cookie deltas` |
| 0.12 | `cn.limpu.hita.agent.llm.CancellableRetrofitCallTest.callbackResponseCompletesTheSuspension` |

## 失败/错误用例：0


## 跳过用例：1

- `cn.limpu.hita.data.source.web.HoaMultiProjectCompatibilityTest.all indexed multi project repositories support structured preview ops`
