package cn.limpu.hita.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomCacheEntity
import cn.limpu.hita.data.model.classroom.ClassroomItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import com.limpu.component.data.DataState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real Room transactions, including cancellation while a write waits for the database lock. */
@RunWith(AndroidJUnit4::class)
class ClassroomQueryCacheTransactionInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var cache: ClassroomQueryCache
    private val term = TermItem("2026-2027", "2026-2027", "1", "秋季")
    private val building = BuildingItem().apply { id = "building"; name = "正心楼" }
    private val week = 6
    @Volatile private var onQuery: (String) -> Unit = {}

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).setQueryCallback({ sql, _ -> onQuery(sql) }, { command -> command.run() }).build()
        cache = ClassroomQueryCache(db.classroomCacheDao())
    }

    @After
    fun tearDown() {
        onQuery = {}
        db.close()
    }

    @Test
    fun conversionFailureLeavesThePreviousBatchUntouched() {
        val original = listOf(row("old-one"), row("old-two"))
        db.classroomCacheDao().saveAllSync(original)
        val brokenBatch = object : AbstractList<ClassroomItem>() {
            override val size = 2
            override fun get(index: Int): ClassroomItem = if (index == 0) {
                room("first-converted")
            } else throw IllegalArgumentException("injected conversion failure")
        }

        val failure = runCatching { cache.save(term, building, week, brokenBatch) }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("injected conversion failure"))
        assertEquals(original.toSet(), cache.readRows(term, building, week).toSet())
    }

    @Test
    fun cleanupFailureRollsBackReplacementAndRetainsExpiredRows() {
        val original = listOf(row("old-one"), row("old-two"))
        val expired = row("expired-other").copy(buildingId = "other", cachedAt = 1L)
        db.classroomCacheDao().saveAllSync(original + expired)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_classroom_cleanup BEFORE DELETE ON classroom_cache " +
                "WHEN OLD.name = 'expired-other' BEGIN SELECT RAISE(ABORT, 'injected cleanup failure'); END"
        )

        val failure = runCatching {
            cache.save(term, building, week, listOf(room("new-one"), room("new-two")))
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("injected cleanup failure"))
        assertEquals((original + expired).toSet(), db.classroomCacheDao().getAllSync().toSet())
    }

    @Test
    fun cancellationAfterConversionButBeforeEnteringRoomDoesNotDeleteExistingRows() {
        val original = listOf(row("old-one"), row("old-two"))
        db.classroomCacheDao().saveAllSync(original)

        val failure = runCatching {
            cache.save(term, building, week, listOf(room("new"))) {
                throw CancellationException("cancelled before transaction")
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(original.toSet(), cache.readRows(term, building, week).toSet())
    }

    @Test
    fun writeGuardRunsInsideTheAcquiredTransactionBeforeAnyDeletion() {
        val original = listOf(row("old-one"), row("old-two"))
        db.classroomCacheDao().saveAllSync(original)
        var enteredTransaction = false

        val failure = runCatching {
            db.classroomCacheDao().replaceByQuerySync(
                building.id, term.yearCode, term.termCode, week, listOf(row("new")), 0L,
                beforeReplace = {
                    enteredTransaction = db.inTransaction()
                    assertEquals(original.toSet(), cache.readRows(term, building, week).toSet())
                    throw CancellationException("cancelled after obtaining transaction")
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(enteredTransaction)
        assertEquals(original.toSet(), cache.readRows(term, building, week).toSet())
    }

    @Test
    fun cancellingWhileWaitingForRoomLockPreventsBothReplacementAndEmptyClear() = runBlocking {
        for (rooms in listOf(listOf(room("new")), emptyList())) {
            val original = listOf(row("old-one"), row("old-two"))
            db.classroomCacheDao().saveAllSync(original)
            val lockAcquired = CountDownLatch(1)
            val releaseLock = CountDownLatch(1)
            val conversionCompleted = CountDownLatch(1)
            val checkedInsideTransaction = AtomicBoolean(false)
            val holder = Executors.newSingleThreadExecutor()
            val lock = holder.submit {
                db.runInTransaction {
                    lockAcquired.countDown()
                    releaseLock.await(10, TimeUnit.SECONDS)
                }
            }
            val states = CopyOnWriteArrayList<DataState<List<ClassroomItem>>>()
            var job: kotlinx.coroutines.Job? = null
            try {
                assertTrue("competing transaction must own the write lock", lockAcquired.await(5, TimeUnit.SECONDS))
                val request = request(rooms) { capturedTerm, capturedBuilding, capturedWeek, classrooms ->
                    val context = currentCoroutineContext()
                    cache.save(capturedTerm, capturedBuilding, capturedWeek, classrooms) {
                        if (db.inTransaction()) checkedInsideTransaction.set(true)
                        context.ensureActive()
                        conversionCompleted.countDown()
                    }
                }
                job = launch(Dispatchers.Default) { request.states().collect { states += it } }
                assertTrue("replacement must reach Room while its lock is held", conversionCompleted.await(5, TimeUnit.SECONDS))
                job.cancel()
                releaseLock.countDown()
                lock.get(5, TimeUnit.SECONDS)
                job.join()

                assertTrue(job.isCancelled)
                assertTrue("guard must be rechecked after obtaining the transaction", checkedInsideTransaction.get())
                assertTrue(states.isEmpty())
                assertEquals(original.toSet(), cache.readRows(term, building, week).toSet())
            } finally {
                releaseLock.countDown()
                job?.cancelAndJoin()
                lock.get(5, TimeUnit.SECONDS)
                holder.shutdownNow()
            }
        }
    }

    @Test
    fun concurrentReplacementsCommitWholeBatchesWithoutMixingRooms() {
        db.classroomCacheDao().saveAllSync(listOf(row("old")))
        val firstInsideTransaction = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondConverted = CountDownLatch(1)
        val secondSaw = CopyOnWriteArrayList<String>()
        val workers = Executors.newFixedThreadPool(2)
        val first = workers.submit {
            cache.save(term, building, week, listOf(room("first-one"), room("first-two"))) {
                if (db.inTransaction()) {
                    firstInsideTransaction.countDown()
                    releaseFirst.await(10, TimeUnit.SECONDS)
                }
            }
        }
        var second: java.util.concurrent.Future<*>? = null
        try {
            assertTrue(firstInsideTransaction.await(5, TimeUnit.SECONDS))
            second = workers.submit {
                cache.save(term, building, week, listOf(room("second-one"), room("second-two"))) {
                    if (db.inTransaction()) {
                        secondSaw += cache.readRows(term, building, week).map { it.name }
                    } else secondConverted.countDown()
                }
            }
            assertTrue(secondConverted.await(5, TimeUnit.SECONDS))
            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)

            assertEquals(setOf("first-one", "first-two"), secondSaw.toSet())
            assertEquals(setOf("second-one", "second-two"), cache.readRows(term, building, week).map { it.name }.toSet())
        } finally {
            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
            second?.get(5, TimeUnit.SECONDS)
            workers.shutdownNow()
        }
    }

    @Test
    fun cancellationAfterDeleteLetsTheTransactionFinishButPreventsRemoteDelivery() = runBlocking {
        db.classroomCacheDao().saveAllSync(listOf(row("old-one"), row("old-two")))
        val beforeInsert = CountDownLatch(1)
        val releaseInsert = CountDownLatch(1)
        val firstInsert = AtomicBoolean(true)
        onQuery = { sql ->
            if (sql.contains("INSERT", ignoreCase = true) && sql.contains("classroom_cache") &&
                firstInsert.compareAndSet(true, false)
            ) {
                beforeInsert.countDown()
                releaseInsert.await(10, TimeUnit.SECONDS)
            }
        }
        val request = request(listOf(room("new-one"), room("new-two"))) { t, b, w, classrooms ->
            val context = currentCoroutineContext()
            cache.save(t, b, w, classrooms) { context.ensureActive() }
        }
        val states = CopyOnWriteArrayList<DataState<List<ClassroomItem>>>()
        val job = launch(Dispatchers.Default) { request.states().collect { states += it } }
        try {
            assertTrue("write must pass its delete and reach insert", beforeInsert.await(5, TimeUnit.SECONDS))
            job.cancel()
            releaseInsert.countDown()
            job.join()

            assertTrue(job.isCancelled)
            assertTrue(states.isEmpty())
            assertEquals(setOf("new-one", "new-two"), cache.readRows(term, building, week).map { it.name }.toSet())
        } finally {
            releaseInsert.countDown()
            job.cancelAndJoin()
        }
    }

    private fun request(
        classrooms: List<ClassroomItem>,
        save: suspend (TermItem, BuildingItem, Int, List<ClassroomItem>) -> Unit,
    ) = EmptyClassroomQueryRequest.forToken(
        EASToken().apply { accessToken = "synthetic-session" }, term, building, week, useCache = false,
        readCache = { _, _, _ -> error("cache read is disabled") },
        saveCache = save,
        queryRemote = { _, _, _, _ -> DataState(classrooms) },
    )

    private fun room(name: String) = ClassroomItem().apply { this.name = name; id = name; capacity = 60 }
    private fun row(name: String) = ClassroomCacheEntity(
        building.id, building.name.orEmpty(), term.yearCode, term.termCode, week,
        name, 60, null, "[]", System.currentTimeMillis(),
    )
}
