package cn.limpu.hita.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.system.Os
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.model.timetable.TimetableGroupEntity
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import java.io.Closeable
import java.io.File
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*

/** Real disk Room and JSON stores; no recovery algorithm or production fault hook is copied. */
internal class TimetableRecoveryTestFixture private constructor(
    val application: StorageApplication,
    seed: Boolean,
) : Closeable {
    constructor() : this(StorageApplication(ApplicationProvider.getApplicationContext()), true)

    val statements = CopyOnWriteArrayList<String>()
    val database = Room.databaseBuilder(application, AppDatabase::class.java, File(application.filesDir, "room.sqlite").absolutePath)
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
        .setQueryCallback({ sql, _ -> statements += sql }, { it.run() })
        .build()
    val preferences = EasPreferenceSource(application)
    val changes = TimetableChangeStore(application)
    val snapshots = TimetableSnapshotStore(application, database)
    val term = TermItem("2026-2027", "2026-2027", "1", "秋季")
    val owner: String get() = FollowedTeachingSectionStore.ownerKey(preferences.getEasToken())
    val changeFile get() = File(application.filesDir, "timetable_change/state.json")
    val snapshotFile get() = File(application.filesDir, "timetable_snapshots/v1.json")
    val applier get() = TimetableSourceApplier(application, preferences, changes, TimetableMutationLock(), database)

    init {
        preferences.saveEasToken(EASToken().apply {
            campus = EASToken.Campus.BENBU; username = "recovery-fixture"; stuId = "recovery-fixture"
            sessionGeneration = 1; cookies["JSESSIONID"] = "local-synthetic"
        })
        if (seed) {
            database.timetableGroupDao().saveGroupSync(TimetableGroupEntity().apply { id = "group"; name = "Local group" })
            database.timetableDao().saveTimetableSync(Timetable().apply {
                id = "table"; name = "Local name"; groupId = "group"; code = "BENBU:2026-2027-1"
                startTime = Timestamp(1_800_000_000_000); endTime = Timestamp(startTime.time + 120 * 86_400_000L)
            })
            database.subjectDao().saveSubjectSync(TermSubject().apply {
                id = "subject"; timetableId = "table"; name = "Original"; code = "CS101"; credit = 3f
            })
            database.eventItemDao().insertEventSync(EventItem().apply {
                id = "original"; timetableId = "table"; subjectId = "subject"; name = "Original"; place = "A101"
                from = Timestamp(1_800_000_000_000); to = Timestamp(from.time + 3_600_000L); fromNumber = 1; lastNumber = 1
            })
            database.eventItemDao().insertEventSync(EventItem().apply {
                id = "manual"; timetableId = "table"; subjectId = "subject"; name = "Personal reminder"
                source = EventItem.SOURCE_MANUAL
            })
        }
    }

    fun table() = requireNotNull(database.timetableDao().getTimetableByIdSync("table"))
    fun imported() = database.eventItemDao().getImportedClassEventsOfTimetableSync("table")
    fun incoming(name: String = "Incoming", place: String = "B202") = MergeCourse("subject", name, "CS101", listOf(
        MergeLesson(name, place, "Teacher", 1_800_003_600_000, 1_800_007_200_000, 2, 1, 1),
    ))
    fun held(created: Long = 10, course: MergeCourse = incoming()) = TimetableHeldBatch(
        created, term.id, "table", "Remote name", "BENBU:2026-2027-1", table().startTime.time,
        table().scheduleStructure, 1, 1, 1, listOf(course),
    )

    fun seedPendingState() {
        changes.recordHeldBatch(held())
        changes.recordApplied(TimetableChangeInfo(1, emptyList(), listOf("Original"), emptyList()), listOf(
            TimetableDecisionItem(term.id, "table", "decision", "subject", "Original",
                CourseVetoReason.MISSING_FROM_SOURCE, 1, 1, 3, null),
        ))
        changes.putVetoes(term.id, mapOf("decision" to CourseVetoRecord("decision", "Original", 1, 2, 3)))
    }

    /** A deferred foreign key fails the real outer COMMIT, after every course INSERT succeeded. */
    fun failFinalCommit() {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("PRAGMA foreign_keys=ON")
        sql.query("PRAGMA foreign_keys").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        sql.execSQL("CREATE TABLE recovery_commit_parent (id TEXT PRIMARY KEY)")
        sql.execSQL("CREATE TABLE recovery_commit_child (id TEXT PRIMARY KEY, parentId TEXT REFERENCES recovery_commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        sql.execSQL("CREATE TRIGGER reject_final_commit AFTER INSERT ON events WHEN NEW.name = 'Incoming' BEGIN INSERT INTO recovery_commit_child(id,parentId) VALUES(NEW.id,'missing'); END")
    }

    fun <T> denyDirectoryWrites(directory: File, block: () -> T): T {
        assertTrue(directory.isDirectory || directory.mkdirs())
        Os.chmod(directory.absolutePath, 0b101000000) // owner read/execute, no write (0500)
        try { return block() } finally { Os.chmod(directory.absolutePath, 0b111000000) }
    }

    /** Copy only closed durable bytes to a new path so shared in-process store caches cannot help. */
    fun reconstructFromDurableBytes(): TimetableRecoveryTestFixture {
        database.close()
        val restored = StorageApplication(ApplicationProvider.getApplicationContext())
        application.filesDir.copyRecursively(restored.filesDir, overwrite = true)
        return TimetableRecoveryTestFixture(restored, false)
    }

    override fun close() { database.close(); application.close() }

    internal class StorageApplication(context: Context) : Application(), Closeable {
        private val prefix = "recovery-${UUID.randomUUID()}-"
        private val directory = File(context.cacheDir, prefix).apply { mkdirs() }
        private val names = mutableSetOf<String>()
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names += name
            return super.getSharedPreferences(prefix + name, mode)
        }
        override fun close() {
            names.forEach { super.deleteSharedPreferences(prefix + it) }
            directory.deleteRecursively()
        }
    }
}
