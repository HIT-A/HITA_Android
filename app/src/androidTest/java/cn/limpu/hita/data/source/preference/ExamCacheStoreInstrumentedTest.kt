package cn.limpu.hita.data.source.preference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.model.eas.ExamItem
import cn.limpu.hita.testutil.IsolatedPreferencesContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExamCacheStoreInstrumentedTest {

    private lateinit var context: IsolatedPreferencesContext
    private lateinit var store: ExamCacheStore

    @Before
    fun setUp() {
        context = IsolatedPreferencesContext(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        store = ExamCacheStore(context)
    }

    @After
    fun tearDown() = context.clear()

    @Test
    fun savedRemoteExamsComeBackWithTheirCacheTime() {
        store.save("SHENZHEN:student", "term-1", listOf(exam("final")))

        val snapshot = requireNotNull(store.read("SHENZHEN:student", "term-1"))

        assertEquals(listOf("final"), snapshot.exams.map { it.courseName })
        assertTrue(snapshot.cachedAtMillis > 0L)
    }

    @Test
    fun memosAreNeverStoredAndAnEmptyRemoteListClearsTheSnapshot() {
        store.save("SHENZHEN:student", "term-1", listOf(exam("final"), memo("my memo")))
        assertEquals(listOf("final"), store.read("SHENZHEN:student", "term-1")?.exams?.map { it.courseName })

        // 空列表是一次成功的「本学期没有考试」，旧快照必须被清掉。
        store.save("SHENZHEN:student", "term-1", listOf(memo("only memo")))
        assertNull(store.read("SHENZHEN:student", "term-1"))
    }

    @Test
    fun cacheIsIsolatedByOwnerAndTerm() {
        store.save("SHENZHEN:student", "term-1", listOf(exam("mine")))

        assertNull(store.read("SHENZHEN:other", "term-1"))
        assertNull(store.read("SHENZHEN:student", "term-2"))
        assertNull(store.read("WEIHAI:student", "term-1"))
    }

    @Test
    fun clearDropsEveryOwner() {
        store.save("SHENZHEN:student", "term-1", listOf(exam("mine")))
        store.save("BENBU:student", "", listOf(exam("other")))

        store.clear()

        assertNull(store.read("SHENZHEN:student", "term-1"))
        assertNull(store.read("BENBU:student", ""))
    }

    private fun exam(name: String) = ExamItem().apply {
        courseName = name
        examType = "期末"
        examDate = "2026-11-20"
        examTime = "09:00-11:00"
    }

    private fun memo(name: String) = exam(name).apply { memoId = "memo-$name" }
}
