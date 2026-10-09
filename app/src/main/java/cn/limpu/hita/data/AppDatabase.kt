package cn.limpu.hita.data

import android.content.Context

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

import com.limpu.hitauser.data.model.UserProfile
import cn.limpu.hita.data.model.chat.ChatMessageEntity
import cn.limpu.hita.data.model.chat.ChatSession
import cn.limpu.hita.data.model.classroom.ClassroomCacheEntity
import cn.limpu.hita.data.model.eas.ScoreCacheEntity
import cn.limpu.hita.data.model.eas.ScoreDetailCacheEntity
import cn.limpu.hita.data.model.eas.ScoreTermCacheEntity
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.model.timetable.share.ShareIdentityEntity
import cn.limpu.hita.data.model.timetable.share.FriendTimetableEntity
import cn.limpu.hita.data.source.dao.TimetableShareDao
import cn.limpu.hita.data.source.dao.FriendTimetableDao
import cn.limpu.hita.data.model.blog.BlogArticle
import cn.limpu.hita.data.model.notice.CampusNotice
import cn.limpu.hita.data.source.dao.ChatMessageDao
import cn.limpu.hita.data.source.dao.ChatSessionDao
import cn.limpu.hita.data.source.dao.ClassroomCacheDao
import cn.limpu.hita.data.source.dao.EventItemDao
import cn.limpu.hita.data.source.dao.ScoreCacheDao
import cn.limpu.hita.data.source.dao.SubjectDao
import cn.limpu.hita.data.source.dao.TimetableDao
import cn.limpu.hita.data.source.dao.BlogArticleDao
import cn.limpu.hita.data.source.dao.CampusNoticeDao
import com.limpu.hitauser.data.source.dao.UserProfileDao

@Database(
    entities = [EventItem::class, TermSubject::class, Timetable::class, ChatSession::class, ChatMessageEntity::class, ClassroomCacheEntity::class, ScoreCacheEntity::class, ScoreTermCacheEntity::class, ScoreDetailCacheEntity::class, BlogArticle::class, CampusNotice::class, ShareIdentityEntity::class, FriendTimetableEntity::class],
    version = 15
)
@androidx.room.TypeConverters(TypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun timetableShareDao(): TimetableShareDao
    abstract fun friendTimetableDao(): FriendTimetableDao
    abstract fun eventItemDao(): EventItemDao
    abstract fun subjectDao(): SubjectDao
    abstract fun timetableDao(): TimetableDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun classroomCacheDao(): ClassroomCacheDao
    abstract fun scoreCacheDao(): ScoreCacheDao
    abstract fun blogArticleDao(): BlogArticleDao
    abstract fun campusNoticeDao(): CampusNoticeDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private const val DB_NAME = "hita"

        @JvmStatic
        fun getDatabase(context: Context): AppDatabase {
            INSTANCE?.let { return it }
            synchronized(AppDatabase::class.java) {
                INSTANCE?.let { return it }
                val opened = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                ).fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = opened
                return opened
            }
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

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_message ADD COLUMN resourceCardsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS score_cache (ownerKey TEXT NOT NULL, termYearCode TEXT NOT NULL, termTermCode TEXT NOT NULL, testType TEXT NOT NULL, scoresJson TEXT NOT NULL, summaryJson TEXT, cachedAt INTEGER NOT NULL, PRIMARY KEY(ownerKey, termYearCode, termTermCode, testType))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_score_cache_ownerKey_cachedAt ON score_cache(ownerKey, cachedAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS score_term_cache (ownerKey TEXT NOT NULL, termYearCode TEXT NOT NULL, yearName TEXT NOT NULL, termTermCode TEXT NOT NULL, termName TEXT NOT NULL, isCurrent INTEGER NOT NULL, cachedAt INTEGER NOT NULL, PRIMARY KEY(ownerKey, termYearCode, termTermCode))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_score_term_cache_ownerKey_cachedAt ON score_term_cache(ownerKey, cachedAt)")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS score_detail_cache (ownerKey TEXT NOT NULL, cacheKey TEXT NOT NULL, payloadJson TEXT NOT NULL, cachedAt INTEGER NOT NULL, PRIMARY KEY(ownerKey, cacheKey))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_score_detail_cache_ownerKey_cachedAt ON score_detail_cache(ownerKey, cachedAt)")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS blog_article (guid TEXT NOT NULL, title TEXT NOT NULL, link TEXT NOT NULL, pubDateMillis INTEGER NOT NULL, description TEXT NOT NULL, htmlContent TEXT NOT NULL, path TEXT NOT NULL, PRIMARY KEY(guid))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_blog_article_pubDateMillis ON blog_article(pubDateMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_blog_article_path ON blog_article(path)")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS campus_notice (id TEXT NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL, pubDateMillis INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_campus_notice_pubDateMillis ON campus_notice(pubDateMillis)")
            }
        }
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS timetable_share_identity (timetableId TEXT NOT NULL, termKey TEXT NOT NULL, shareId TEXT NOT NULL, PRIMARY KEY(timetableId, termKey))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_timetable_share_identity_shareId ON timetable_share_identity(shareId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS friend_timetable (shareId TEXT NOT NULL, nickname TEXT NOT NULL, remark TEXT, termName TEXT NOT NULL, startMillis INTEGER NOT NULL, endMillis INTEGER NOT NULL, mode TEXT NOT NULL, snapshotJson TEXT NOT NULL, contentDigest TEXT NOT NULL, importedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(shareId))")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS campus_notice_new (campus TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL, pubDateMillis INTEGER NOT NULL, PRIMARY KEY(campus, id))")
                db.execSQL("INSERT INTO campus_notice_new (campus, id, title, url, pubDateMillis) SELECT 'SHENZHEN', id, title, url, pubDateMillis FROM campus_notice")
                db.execSQL("DROP TABLE campus_notice")
                db.execSQL("ALTER TABLE campus_notice_new RENAME TO campus_notice")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_campus_notice_pubDateMillis ON campus_notice(pubDateMillis)")
            }
        }
    }
}
