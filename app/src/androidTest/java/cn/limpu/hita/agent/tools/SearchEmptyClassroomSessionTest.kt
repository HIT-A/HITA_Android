package cn.limpu.hita.agent.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.analytics.AnalyticsOperationContext
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.repository.BackgroundTermConsumerHarness
import cn.limpu.hita.data.repository.BackgroundTermConsumerHarness.Companion.term
import com.limpu.component.data.DataState
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchEmptyClassroomSessionTest {
    @Test fun fullQueryPreservesCurrentYearSelectionPartialOutputAndSuccessfulCacheWrite() = runBlocking {
        BackgroundTermConsumerHarness().use { h ->
            val year = Calendar.getInstance().get(Calendar.YEAR)
            val current = term("2", "$year-${year + 1}")
            h.terms = { DataState(listOf(term("1", "1999-2000").apply { isCurrent = true }, current)) }
            h.buildings = { DataState(listOf(building("b", "B楼"), building("a", "A楼"))) }
            h.classrooms = { _, actualTerm, building, weeks ->
                assertEquals(current.id, actualTerm.id)
                assertEquals(listOf("2"), weeks)
                if (building.id == "a") DataState(DataState.STATE.FETCH_FAILED, "local unavailable")
                else DataState(listOf(ClassroomItem().apply { name = "B201"; capacity = 40 }))
            }
            val result = ReActToolRegistry.createDefault().get("search_empty_classroom")!!.execute(h.input())!!
            assertEquals(
                "第 2 周 空闲教室查询结果：\n\n【B楼】\n  B201 (占用0/84节) 容量:40\n\n共 1 间教室\n部分教学楼实时查询失败：A楼：local unavailable",
                result,
            )
            assertEquals(listOf("terms", "buildings", "classrooms:b", "classrooms:a"), h.calls.toList())
            h.tokens.forEach { assertSame(h.tokens.first(), it) }
            assertTrue(h.database.scoreCacheDao().getTermsSync("BENBU:student").isEmpty())
            assertEquals(listOf("B201"), h.database.classroomCacheDao().getAllSync().map { it.name })
        }
    }

    @Test fun accountSwitchDuringTermRequestCancelsToolBeforeBuildings() = runBlocking {
        BackgroundTermConsumerHarness().use { h ->
            val entered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            h.terms = {
                entered.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
            val query = async { ReActToolRegistry.createDefault().get("search_empty_classroom")!!.execute(h.input()) }
            withTimeout(10_000) { entered.await() }
            h.switchAccount()
            try { query.await(); error("A replaced session must cancel") } catch (_: CancellationException) { }
            withTimeout(10_000) { stopped.await() }
            assertEquals(listOf("terms"), h.calls.toList())
            assertTrue(h.database.classroomCacheDao().getAllSync().isEmpty())
        }
    }

    @Test fun callerCancellationDuringFirstBuildingStopsLaterBuildingsAndCacheWrites() = runBlocking {
        BackgroundTermConsumerHarness().use { h ->
            val entered = CompletableDeferred<Unit>()
            h.buildings = { DataState(listOf(building("a", "A楼"), building("b", "B楼"))) }
            h.classrooms = { _, _, _, _ -> entered.complete(Unit); awaitCancellation() }
            val query = launch { SearchEmptyClassroomTool().execute(h.input()) }
            withTimeout(10_000) { entered.await() }
            query.cancelAndJoin()
            assertEquals(listOf("terms", "buildings", "classrooms:a"), h.calls.toList())
            assertTrue(h.database.classroomCacheDao().getAllSync().isEmpty())
        }
    }

    @Test fun cancellationWhileResolvingLocalWeekStopsTheOneShotRead() = runBlocking {
        BackgroundTermConsumerHarness().use { h ->
            val entered = CompletableDeferred<Unit>()
            val tool = SearchEmptyClassroomTool(weekSource = { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            })
            val query = launch { tool.execute(h.input("{}")) }
            withTimeout(10_000) { entered.await() }
            query.cancelAndJoin()
            assertEquals(listOf("terms"), h.calls.toList())
        }
    }

    @Test fun termTimeoutKeepsTheExistingUserMessageAndCancelsRemoteWork() = runBlocking {
        BackgroundTermConsumerHarness().use { h ->
            val stopped = CompletableDeferred<Unit>()
            h.terms = { try { awaitCancellation() } finally { stopped.complete(Unit) } }
            val tool = SearchEmptyClassroomTool(weekSource = { _, _ -> error("No week read") }, queryTimeoutMs = 1_000)
            assertEquals("获取当前学期失败：等待教务系统响应超时", tool.execute(h.input()))
            withTimeout(10_000) { stopped.await() }
            assertEquals(listOf("terms"), h.calls.toList())
        }
    }

    @Test fun registryKeepsOperationContextAcrossSuspensionAndRestoresItOnCancellation() = runBlocking {
        BackgroundTermConsumerHarness().use { h ->
            val registry = ReActToolRegistry()
            val entered = CompletableDeferred<Unit>()
            val outer = AnalyticsOperationContext.Value("outer-test-operation", 1)
            registry.register("probe", SuspendingReActTool {
                val operation = requireNotNull(AnalyticsOperationContext.get())
                assertNotEquals(outer, operation)
                withContext(Dispatchers.Default) { assertEquals(operation, AnalyticsOperationContext.get()) }
                entered.complete(Unit)
                awaitCancellation()
            })
            registry.register("legacy", ReActTool { "unchanged text" })
            withContext(AnalyticsOperationContext.coroutineContext(outer)) {
                assertEquals("unchanged text", registry.get("legacy")!!.execute(h.input()))
                assertEquals(outer, AnalyticsOperationContext.get())
                val query = launch { registry.get("probe")!!.execute(h.input()) }
                withTimeout(10_000) { entered.await() }
                query.cancelAndJoin()
                assertEquals(outer, AnalyticsOperationContext.get())
            }
            assertNull(AnalyticsOperationContext.get())
        }
    }

    private fun building(id: String, name: String) = BuildingItem().apply {
        this.id = id
        this.name = name
    }
}
