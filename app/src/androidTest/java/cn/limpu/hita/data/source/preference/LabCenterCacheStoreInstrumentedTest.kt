package cn.limpu.hita.data.source.preference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.model.campus.LabCourseProgress
import cn.limpu.hita.data.model.campus.LabSessionItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.testutil.IsolatedPreferencesContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LabCenterCacheStoreInstrumentedTest {

    private lateinit var context: IsolatedPreferencesContext
    private lateinit var store: LabCenterCacheStore

    @Before
    fun setUp() {
        context = IsolatedPreferencesContext(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        store = LabCenterCacheStore(context)
    }

    @After
    fun tearDown() = context.clear()

    @Test
    fun savedProgressComesBackWithSessionsAndFetchTime() {
        store.save("SHENZHEN:student", progress("第 3 周实验"))

        val cached = requireNotNull(store.read("SHENZHEN:student"))

        assertEquals("2026-2027 秋季", cached.termName)
        assertEquals(3, cached.currentWeek)
        assertEquals(listOf("第 3 周实验"), cached.sessions.map { it.courseName })
        assertEquals(1_700_000_000_000L, cached.fetchedAtMillis)
    }

    @Test
    fun cacheIsIsolatedByOwner() {
        store.save("SHENZHEN:student", progress("我的实验"))

        assertNull(store.read("SHENZHEN:other"))
        assertNull(store.read("BENBU:student"))
    }

    @Test
    fun saveReplacesThePreviousSnapshot() {
        store.save("SHENZHEN:student", progress("旧课表"))
        store.save("SHENZHEN:student", progress("新课表"))

        assertEquals(listOf("新课表"), store.read("SHENZHEN:student")?.sessions?.map { it.courseName })
    }

    @Test
    fun clearDropsTheSnapshot() {
        store.save("SHENZHEN:student", progress("我的实验"))

        store.clear()

        assertNull(store.read("SHENZHEN:student"))
    }

    @Test
    fun ownerKeyPrefersStudentIdThenFallsBack() {
        val token = EASToken().apply {
            campus = EASToken.Campus.SHENZHEN
            id = "internal-id"
            stuId = "2025310472"
            username = "name"
        }
        assertEquals("SHENZHEN:2025310472", LabCenterCacheStore.ownerKey(token))

        token.stuId = null
        assertEquals("SHENZHEN:internal-id", LabCenterCacheStore.ownerKey(token))

        token.id = null
        assertEquals("SHENZHEN:name", LabCenterCacheStore.ownerKey(token))

        token.username = null
        assertEquals("SHENZHEN:anonymous", LabCenterCacheStore.ownerKey(token))
    }

    private fun progress(name: String) = LabCourseProgress(
        termName = "2026-2027 秋季",
        currentWeek = 3,
        sessions = listOf(
            LabSessionItem(
                id = "$name-1",
                courseName = name,
                week = 3,
                weekday = 2,
                startSection = 1,
                endSection = 2,
                room = "实验楼 302",
            )
        ),
        fetchedAtMillis = 1_700_000_000_000L,
    )
}
