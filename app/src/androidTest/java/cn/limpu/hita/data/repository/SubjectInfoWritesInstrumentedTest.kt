package cn.limpu.hita.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.TermSubject
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubjectInfoWritesInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: SubjectRepository
    private val pending = ArrayDeque<Runnable>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java).build()
        repository = SubjectRepository(db, Executor { pending.addLast(it) })
    }
    @After fun tearDown() {
        try { pending.clear() } finally { db.close() }
    }

    @Test fun queuedCreditAndBackfillPreserveBothFieldsInEitherOrder() {
        listOf(false, true).forEach { codeFirst ->
            val subject = insert()
            val credit = SubjectInfoUpdate.Credit(4f)
            val code = SubjectInfoUpdate.FillCourseCode(subject.name, "COMP1001")
            repository.actionSaveSubjectInfo(subject.id, if (codeFirst) code else credit)
            repository.actionSaveSubjectInfo(subject.id, if (codeFirst) credit else code)
            drain()
            val actual = read(subject.id)
            assertEquals(4f, actual.credit, 0f)
            assertEquals("COMP1001", actual.code)
            assertEquals("original field", actual.field)
        }
    }

    @Test fun backfillRechecksExistingCodeAndNameInsideTransaction() {
        val coded = insert()
        repository.actionSaveSubjectInfo(coded.id, SubjectInfoUpdate.FillCourseCode(coded.name, "COMP1001"))
        coded.code = "already-imported"
        db.subjectDao().saveSubjectSync(coded)
        val renamed = insert()
        repository.actionSaveSubjectInfo(renamed.id, SubjectInfoUpdate.FillCourseCode(renamed.name, "COMP1001"))
        renamed.name = "changed after request"
        db.subjectDao().saveSubjectSync(renamed)
        drain()
        assertEquals("already-imported", read(coded.id).code)
        assertNull(read(renamed.id).code)
    }

    @Test fun deletedSubjectIsNotRecreatedAndUnrelatedNewFieldsSurviveCreditEdit() {
        val deleted = insert()
        repository.actionSaveSubjectInfo(deleted.id, SubjectInfoUpdate.FillCourseCode(deleted.name, "COMP1001"))
        db.subjectDao().deleteSubjectsInIdsSync(listOf(deleted.id))
        val current = insert()
        repository.actionSaveSubjectInfo(current.id, SubjectInfoUpdate.Credit(5f))
        current.field = "new field"
        current.code = "latest-code"
        db.subjectDao().saveSubjectSync(current)
        drain()
        assertTrue(db.subjectDao().getSubjectsInIdsSync(listOf(deleted.id)).isEmpty())
        assertEquals("new field", read(current.id).field)
        assertEquals("latest-code", read(current.id).code)
        assertEquals(5f, read(current.id).credit, 0f)
    }

    private fun insert() = TermSubject().apply {
        name = "程序设计"; credit = 1f; field = "original field"
        db.subjectDao().saveSubjectSync(this)
    }
    private fun read(id: String) = db.subjectDao().getSubjectsInIdsSync(listOf(id)).single()
    private fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
}
