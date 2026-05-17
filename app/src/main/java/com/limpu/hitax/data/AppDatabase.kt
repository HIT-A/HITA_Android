package com.limpu.hitax.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.limpu.hitauser.data.model.UserProfile
import com.limpu.hitax.data.model.chat.ChatMessageEntity
import com.limpu.hitax.data.model.chat.ChatSession
import com.limpu.hitax.data.model.classroom.ClassroomCacheEntity
import com.limpu.hitax.data.model.timetable.EventItem
import com.limpu.hitax.data.model.timetable.TermSubject
import com.limpu.hitax.data.model.timetable.Timetable
import com.limpu.hitax.data.source.dao.ChatMessageDao
import com.limpu.hitax.data.source.dao.ChatSessionDao
import com.limpu.hitax.data.source.dao.ClassroomCacheDao
import com.limpu.hitax.data.source.dao.EventItemDao
import com.limpu.hitax.data.source.dao.SubjectDao
import com.limpu.hitax.data.source.dao.TimetableDao
import com.limpu.hitauser.data.source.dao.UserProfileDao

@Database(
    entities = [EventItem::class, TermSubject::class, Timetable::class, ChatSession::class, ChatMessageEntity::class, ClassroomCacheEntity::class],
    version = 8
)
@androidx.room.TypeConverters(TypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventItemDao(): EventItemDao
    abstract fun subjectDao(): SubjectDao
    abstract fun timetableDao(): TimetableDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun classroomCacheDao(): ClassroomCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        @JvmStatic
        fun getDatabase(context: Context): AppDatabase {
            if (INSTANCE == null) {
                synchronized(AppDatabase::class.java) {
                    if (INSTANCE == null) {
                        INSTANCE = Room.databaseBuilder(
                            context.applicationContext,
                            AppDatabase::class.java, "hita"
                        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                            .fallbackToDestructiveMigration()
                            .build()
                    }
                }
            }
            return INSTANCE!!
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE subject ADD COLUMN selectCategory TEXT")
                db.execSQL("ALTER TABLE subject ADD COLUMN nature TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN source TEXT NOT NULL DEFAULT 'EAS_IMPORT'")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_events_timetableId_type_source ON events(timetableId, type, source)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS chat_session (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS chat_message (id TEXT PRIMARY KEY NOT NULL, sessionId TEXT NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, timestampMs INTEGER NOT NULL, FOREIGN KEY(sessionId) REFERENCES chat_session(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_message_sessionId ON chat_message(sessionId)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS classroom_cache (buildingId TEXT NOT NULL, buildingName TEXT NOT NULL, termYearCode TEXT NOT NULL, termTermCode TEXT NOT NULL, week INTEGER NOT NULL, name TEXT NOT NULL, capacity INTEGER NOT NULL, specialClassroom TEXT, scheduleJson TEXT NOT NULL, cachedAt INTEGER NOT NULL, PRIMARY KEY(buildingId, termYearCode, termTermCode, week, name))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_classroom_cache_termYearCode_termTermCode_week ON classroom_cache(termYearCode, termTermCode, week)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_classroom_cache_cachedAt ON classroom_cache(cachedAt)")
            }
        }
    }
}
