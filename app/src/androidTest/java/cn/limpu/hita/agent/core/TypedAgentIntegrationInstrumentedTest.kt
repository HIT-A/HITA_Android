package cn.limpu.hita.agent.core

import android.app.Application
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.agent.subject.SubjectReadmeAgentEngine
import cn.limpu.hita.agent.subject.SubjectReadmeAgentInput
import cn.limpu.hita.agent.subject.SubjectReadmeAgentOutput
import cn.limpu.hita.agent.timetable.*
import cn.limpu.hita.agent.tools.ToolHelper
import cn.limpu.hita.data.model.resource.CourseResourceItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.PriorityQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class TypedAgentIntegrationInstrumentedTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun addEngineRetainsZeroRetryEvenIfRegisteredToolClaimsIdempotence() {
        val clock = Clock()
        var calls = 0
        var cancelled = 0
        var late: ((AgentToolResult<TimetableAgentOutput>) -> Unit)? = null
        val registry = AgentToolRegistry().apply { register(object : AgentTool<TimetableAgentInput, TimetableAgentOutput> {
            override val name = "add_timetable_arrangement"
            override val retrySafety = AgentRetrySafety.IDEMPOTENT
            override fun execute(input: TimetableAgentInput, onResult: (AgentToolResult<TimetableAgentOutput>) -> Unit): AgentCancellation {
                calls++; late = onResult; return AgentCancellation { cancelled++ }
            }
        }) }
        val input = TimetableAgentInput(app, TimetableAgentInput.Action.ADD_TIMETABLE_ARRANGEMENT)
        val results = mutableListOf<AgentToolResult<TimetableAgentOutput>>()
        TimetableAgentEngine(registry, AgentToolExecutor(clock)).run(input, {}, results::add)
        clock.advance(20_000)
        late?.invoke(AgentToolResult.success(TimetableAgentOutput(input.action, "id", "name", addedEventIds = listOf("committed"))))
        clock.advance(0)
        assertEquals(1, calls)
        assertEquals(1, cancelled)
        assertEquals(AgentToolErrorType.TIMEOUT, results.single().errorType)
        assertEquals(1, results.single().attempt)
    }

    @Test fun subjectEngineAndProviderPreserveTypedTimeoutAndAttempt() {
        val clock = Clock()
        var calls = 0
        val registry = AgentToolRegistry().apply { register(object : AgentTool<SubjectReadmeAgentInput, List<CourseResourceItem>> {
            override val name = "resolve_course_candidates"
            override val retrySafety = AgentRetrySafety.IDEMPOTENT
            override fun execute(input: SubjectReadmeAgentInput, onResult: (AgentToolResult<List<CourseResourceItem>>) -> Unit): AgentCancellation {
                calls++; return AgentCancellation.NONE
            }
        }) }
        lateinit var owner: LifecycleOwner
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
            }
        }
        val session = OrchestratorAgentSession(AgentOrchestrator(SubjectReadmeAgentEngine(registry, AgentToolExecutor(clock))), clock)
        val result = mutableListOf<AgentToolResult<SubjectReadmeAgentOutput>>()
        session.run(SubjectReadmeAgentInput(owner, "subject", "code", "name", "SZ"), {}, result::add)
        clock.advance(30_000)
        assertEquals(2, calls)
        assertEquals(AgentToolErrorType.TIMEOUT, result.single().errorType)
        assertEquals(2, result.single().attempt)
    }

    @Test fun timetableEngineProviderAndHelperKeepFailureTypeThroughTextBoundary() = runBlocking {
        val registry = AgentToolRegistry().apply { register(object : AgentTool<TimetableAgentInput, TimetableAgentOutput> {
            override val name = "get_local_timetable"
            override fun execute(input: TimetableAgentInput, onResult: (AgentToolResult<TimetableAgentOutput>) -> Unit): AgentCancellation {
                onResult(AgentToolResult.timeout(5, 1)); return AgentCancellation.NONE
            }
        }) }
        val provider = SimpleAgentProvider {
            OrchestratorAgentSession(AgentOrchestrator(TimetableAgentEngine(registry)))
        }
        val result = ToolHelper.runTimetableTool(
            TimetableAgentInput(app, TimetableAgentInput.Action.GET_LOCAL_TIMETABLE), provider, {},
        )
        assertEquals(AgentToolErrorType.TIMEOUT, result.errorType)
        assertTrue(ToolHelper.formatTimetableResult(result).contains("[TIMEOUT]"))
        assertFalse(ToolHelper.formatTimetableResult(result).contains("没有课程"))
    }

    @Test fun defaultSchedulerDeliversBackgroundToolCallbacksOnMainExactlyOnce() {
        val latch = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        var callbacks = 0
        var onMain = false
        val executor = AgentToolExecutor()
        val tool = object : AgentTool<Unit, String> {
            override val name = "background"
            override fun execute(input: Unit, onResult: (AgentToolResult<String>) -> Unit): AgentCancellation {
                Thread {
                    onResult(AgentToolResult.success("ok"))
                    onResult(AgentToolResult.failure("duplicate"))
                    workerFinished.countDown()
                }.start()
                return AgentCancellation.NONE
            }
        }
        executor.execute(tool, Unit, onTrace = {}) {
            callbacks++; onMain = Looper.myLooper() == Looper.getMainLooper(); latch.countDown()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(workerFinished.await(5, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(onMain)
        assertEquals(1, callbacks)
    }

    @Test fun successfulEmptyReadAndAddedActivityKeepExistingPresentation() {
        val empty = AgentToolResult.success(TimetableAgentOutput(TimetableAgentInput.Action.GET_LOCAL_TIMETABLE, "id", "name"))
        assertEquals("", ToolHelper.formatTimetableResult(empty))
        val added = AgentToolResult.success(TimetableAgentOutput(TimetableAgentInput.Action.ADD_TIMETABLE_ARRANGEMENT, "id", "name", addedEventIds = listOf("one")))
        assertEquals("已成功添加 1 个活动。", ToolHelper.formatTimetableResult(added))
        assertEquals(AgentRetrySafety.NEVER, AddTimetableArrangementTool().retrySafety)
        assertEquals(AgentRetrySafety.IDEMPOTENT, GetLocalTimetableTool().retrySafety)
        assertEquals(AgentRetrySafety.IDEMPOTENT, SearchLocalTimetableTool().retrySafety)
    }

    private class Clock : AgentExecutionScheduler {
        private data class Task(val at: Long, val order: Int, val action: () -> Unit, var cancelled: Boolean = false)
        private val queue = PriorityQueue(compareBy<Task> { it.at }.thenBy { it.order })
        private var now = 0L
        private var sequence = 0
        override fun schedule(delayMs: Long, action: () -> Unit): AgentCancellation {
            val task = Task(now + delayMs, sequence++, action)
            queue += task
            return AgentCancellation { task.cancelled = true }
        }
        fun advance(ms: Long) {
            val end = now + ms
            while (queue.isNotEmpty() && queue.peek().at <= end) {
                val task = queue.remove(); now = task.at
                if (!task.cancelled) task.action()
            }
            now = end
        }
    }
}
