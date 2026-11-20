package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.resource.ExternalCourseItem
import cn.limpu.hita.data.model.resource.ResourceSource
import cn.limpu.hita.data.model.resource.ResourceSourceStatus
import cn.limpu.hita.data.model.resource.SearchSource
import cn.limpu.hita.data.model.resource.UnifiedResourceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceSourceReportTest {
    @Test
    fun `one failed source still returns the successful items and marks only itself failed`() {
        val result = ExternalResourceSearch.merge(
            listOf(
                source(ResourceSource.HITCS, listOf(course("编译原理", ResourceSource.HITCS))),
                source(ResourceSource.FIREWORKS, failure = IllegalStateException("offline")),
            )
        )

        assertEquals(listOf("编译原理"), result.items.map { it.courseName })
        assertEquals(ResourceSourceStatus.OK, result.report(SearchSource.HITCS).status)
        assertEquals(ResourceSourceStatus.FAILED, result.report(SearchSource.FIREWORKS).status)
        assertEquals(0, result.report(SearchSource.FIREWORKS).count)
    }

    @Test
    fun `both failed sources still return an empty result instead of throwing`() {
        val result = ExternalResourceSearch.merge(
            listOf(
                source(ResourceSource.HITCS, failure = IllegalStateException("hitcs")),
                source(ResourceSource.FIREWORKS, failure = IllegalStateException("fireworks")),
            )
        )

        assertTrue(result.items.isEmpty())
        assertEquals(ResourceSourceStatus.FAILED, result.report(SearchSource.HITCS).status)
        assertEquals(ResourceSourceStatus.FAILED, result.report(SearchSource.FIREWORKS).status)
    }

    @Test
    fun `unified search keeps successful courses when one family fails`() {
        val external = ExternalResourceSearch.merge(
            listOf(
                source(ResourceSource.HITCS, failure = IllegalStateException("down")),
                source(ResourceSource.FIREWORKS, emptyList()),
            )
        )
        val result = UnifiedResourceSearch.merge(
            listOf(
                UnifiedSourceOutcome.Success(
                    items = listOf(hoa("操作系统")),
                    reports = listOf(report(SearchSource.HOA, ResourceSourceStatus.OK, 1)),
                ),
                UnifiedSourceOutcome.Failed(external.reports),
            )
        )

        assertEquals(listOf("操作系统"), result.items.map { it.displayName })
        assertEquals(ResourceSourceStatus.OK, result.report(SearchSource.HOA).status)
        assertEquals(ResourceSourceStatus.FAILED, result.report(SearchSource.HITCS).status)
        assertEquals(ResourceSourceStatus.EMPTY, result.report(SearchSource.FIREWORKS).status)
    }

    private fun source(
        source: ResourceSource,
        items: List<ExternalCourseItem> = emptyList(),
        failure: Exception? = null,
    ) = ExternalSourceOutcome(source, items, failure)

    private fun course(name: String, source: ResourceSource) = ExternalCourseItem(
        courseName = name,
        source = source,
        path = name,
    )

    private fun hoa(name: String) = UnifiedResourceItem.HoaCourse(
        repoName = name,
        courseName = name,
        courseCode = "",
        repoType = "normal",
        teachers = emptyList(),
    )

    private fun report(source: SearchSource, status: ResourceSourceStatus, count: Int) =
        cn.limpu.hita.data.model.resource.ResourceSourceReport(source, status, count)

    private fun cn.limpu.hita.data.model.resource.ExternalSearchResult.report(source: SearchSource) =
        reports.single { it.source == source }

    private fun cn.limpu.hita.data.model.resource.UnifiedSearchResult.report(source: SearchSource) =
        reports.single { it.source == source }
}
