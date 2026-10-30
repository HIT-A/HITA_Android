package cn.limpu.hita.data.repository.homework

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeworkPersistenceInstrumentedTest {
    private val base = ApplicationProvider.getApplicationContext<Application>()
    private val name = "homework-test-" + UUID.randomUUID()
    private val app = object : Application() {
        init { attachBaseContext(base) }
        override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
            base.getSharedPreferences(name, mode)
    }

    @After fun tearDown() { base.deleteSharedPreferences(name) }

    @Test fun actualPreferencesKeepPendingAndCompletedItemsAcrossRepositoryRecreation() = runBlocking {
        val original = HomeworkRepository(app)
        assertTrue(original.add("完成实验报告"))
        assertTrue(original.add("完成实验报告"))
        val initial = state(original).items
        assertEquals(2, initial.size)
        assertNotEquals(initial[0].id, initial[1].id)
        assertTrue(original.setCompleted(initial[0].id, true))

        val restored = HomeworkRepository(app)
        assertTrue(restored.reload())
        assertEquals(1, state(restored).items.count { it.completed })
        assertEquals(1, state(restored).items.count { !it.completed })
        assertTrue(restored.setCompleted(initial[0].id, false))
        assertTrue(restored.setCompleted(initial[1].id, true))
        assertTrue(restored.clearCompleted())

        val next = HomeworkRepository(app)
        assertTrue(next.reload())
        assertEquals(listOf(initial[0]), state(next).items)
    }

    private fun state(repository: HomeworkRepository): HomeworkState {
        var result: HomeworkState? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = repository.state.value }
        return requireNotNull(result)
    }
    @Test fun plannedDaysAndReschedulingPersistWithoutMovingOtherTasks() = runBlocking {
        val original = HomeworkRepository(app)
        assertTrue(original.add("今天", "2026-10-30"))
        assertTrue(original.add("明天", "2026-10-31"))
        val initial = state(original).items
        assertTrue(original.edit(initial[0].id, "改到下月", "2026-11-01"))
        assertTrue(original.setCompleted(initial[1].id, true))
        val restored = HomeworkRepository(app)
        assertTrue(restored.reload())
        assertEquals(listOf("2026-11-01", "2026-10-31"), state(restored).items.map { it.plannedDate })
        assertTrue(state(restored).items[1].completed)
        assertTrue(restored.delete(initial[0].id))
        val next = HomeworkRepository(app)
        assertTrue(next.reload())
        assertEquals(initial[1].id, state(next).items.single().id)
        assertEquals("2026-10-31", state(next).items.single().plannedDate)
    }

}