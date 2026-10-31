package cn.limpu.hita.data.repository

import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogSource
import cn.limpu.hita.ui.eas.catalog.ShenzhenCourseCatalogQuery
import cn.limpu.hita.ui.eas.catalog.ShenzhenCourseCatalogViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TermRetryMetadataInstrumentedTest : TermRepositoryContractHarness() {
    @Test fun catalogRetryUsesFreshTermMetadataAndPreservesTheOriginalQueryFields() {
        val fixture = fixture()
        val store = ViewModelStore()
        lateinit var model: ShenzhenCourseCatalogViewModel
        val submitted = mutableListOf<ShenzhenCourseCatalogQuery>()
        val observer = Observer<ShenzhenCourseCatalogQuery?> { if (it != null) submitted += it }
        main {
            model = fixture.catalogPage()
            store.put("catalog", model)
            model.queryLiveData.observeForever(observer)
        }
        try {
            main {
                val cached = remoteTerm(label = "cached metadata")
                model.selectTerm(cached)
                model.selectSource(ShenzhenCourseCatalogSource.SCHOOL)
                model.selectPool(model.pools.last())
                model.selectStudentType("2")
                model.search("compiler")
                // An already selected result page is part of the request that retry must retain.
                model.queryLiveData.value = requireNotNull(model.queryLiveData.value).copy(page = 4)
                val original = requireNotNull(model.queryLiveData.value)
                val submissionsBeforeMetadata = submitted.size
                val remote = remoteTerm(label = "fresh metadata").apply {
                    id = "remote-id"
                    name = "remote display name"
                    isCurrent = true
                }
                model.reconcileTerms(listOf(remote))
                assertSame("Metadata replay must not resubmit the query", original, model.queryLiveData.value)
                assertEquals(submissionsBeforeMetadata, submitted.size)
                assertTrue(model.retry())
                val retried = requireNotNull(model.queryLiveData.value)
                assertSame(remote, retried.term)
                assertEquals(original.source, retried.source)
                assertSame(original.pool, retried.pool)
                assertEquals(original.studentType, retried.studentType)
                assertEquals(original.keyword, retried.keyword)
                assertEquals(original.page, retried.page)
                assertEquals(submissionsBeforeMetadata + 1, submitted.size)
                assertEquals("cached metadata", original.term.termName)
            }
        } finally { main { model.queryLiveData.removeObserver(observer); store.clear() } }
    }
}
