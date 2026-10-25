package cn.limpu.hita.ui.timetable.manager

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.repository.*
import cn.limpu.hita.feature.timetable.display.DisplayScopeCoordinator
import cn.limpu.hita.feature.timetableshare.protocol.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimetableManagerImportInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun snapshot(): SharedTimetable {
        val start = 1788105600000L
        return SharedTimetable("5fb93366-071b-4bd6-a658-ff402a25311b", "朋友", ShareMode.FULL,
            SharedTerm("分享课表", "2026 秋季", start, start + 120 * 86400000L),
            listOf(SharedPeriod(480, 600)), listOf("数学"),
            listOf(SharedOccurrence(start + 8 * 3600000L, start + 9 * 3600000L, 0, "A101")))
    }

    private fun fixture(action: (TimetableManagerViewModel, AppDatabase) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val store = ViewModelStore()
        val resolver = DisplayScopeResolver(app, db)
        val selector = DisplayScopeCoordinator(resolver::persistSelection, {}, {})
        val repository = TimetableRepository(app, db, selector)
        lateinit var model: TimetableManagerViewModel
        instrumentation.runOnMainSync {
            model = TimetableManagerViewModel(repository, selector,
                TimetableSharingRepository(db), TimetableExportRepository(db, app), FriendTimetableRepository(db))
            store.put("manager", model)
        }
        try { action(model, db) }
        finally {
            instrumentation.runOnMainSync { store.clear() }
            val flushed = java.util.concurrent.CountDownLatch(1)
            repository.actionRenameTimetable("missing-barrier", "barrier") { flushed.countDown() }
            try {
                assertTrue("Repository work did not finish", flushed.await(10, java.util.concurrent.TimeUnit.SECONDS))
            } finally { db.close() }
        }
    }

    private fun await(predicate: () -> Boolean) = runBlocking {
        withTimeout(10000) { while (!predicate()) delay(10) }
    }

    @Test fun editedOrFailedTokenNeverImportsThePreviousPreview() = fixture { model, db ->
        instrumentation.runOnMainSync {
            model.tokenSheetOpen = true
            model.editTokenInput(TimetableShareCodec().encode(snapshot()))
            model.parseToken(model.tokenForm.input)
        }
        await { model.tokenForm.canConfirm }
        instrumentation.runOnMainSync {
            model.editTokenInput("HITA1:broken")
            assertNull(model.tokenForm.preview)
            model.parseToken(model.tokenForm.input)
        }
        await { model.tokenForm.errorResource != null }
        instrumentation.runOnMainSync { model.importTokenAsFriend(); model.importTokenAsMine() }
        assertNull(db.friendTimetableDao().getSync(snapshot().shareId))
        assertTrue(db.timetableDao().getTimetablesSync().all {
            db.eventItemDao().getEventsOfTimetableSync(it.id).none { event -> event.source == EventItem.SOURCE_SHARE_IMPORT }
        })
        assertFalse(model.tokenForm.canConfirm)
    }

    @Test fun personalTokenImportCompletesWithoutAnActivityObserverAndIgnoresDoubleSave() = fixture { model, db ->
        instrumentation.runOnMainSync {
            model.showFriendConfirmation(FriendImportPreview(snapshot(), ImportKind.NEW, null, null))
            model.importTokenAsMine()
            model.importTokenAsMine()
        }
        await { model.pendingScopeImport != null }
        val importedId = model.pendingScopeImport!!.first
        assertEquals("分享课表", db.timetableDao().getTimetableByIdSync(importedId)!!.name)
        assertEquals("A101", db.eventItemDao().getEventsOfTimetableSync(importedId).single().place)
        assertEquals(1, db.timetableDao().getTimetablesSync().count {
            db.eventItemDao().getEventsOfTimetableSync(it.id).any { event -> event.source == EventItem.SOURCE_SHARE_IMPORT }
        })
        assertFalse(model.tokenSheetOpen)
        assertFalse(model.tokenForm.saving)
        assertNull(db.friendTimetableDao().getSync(snapshot().shareId))
    }

    @Test fun personalIcsImportRetainsScopeChoiceWithoutAnActivityObserver() = fixture { model, db ->
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//HITA//EN\r\n" +
            "BEGIN:VEVENT\r\nUID:course\r\nDTSTAMP:20261101T000000Z\r\n" +
            "DTSTART:20261102T003000Z\r\nDTEND:20261102T021500Z\r\nSUMMARY:数学\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
        instrumentation.runOnMainSync { model.importFromICSAsNewTimetable(ics.byteInputStream(), "朋友.ics") }
        await { model.pendingScopeImport != null }
        val result = model.pendingScopeImport!!
        assertEquals("朋友", result.second)
        assertEquals(EventItem.SOURCE_ICS_IMPORT, db.eventItemDao().getEventsOfTimetableSync(result.first).single().source)
        assertEquals("数学", db.eventItemDao().getEventsOfTimetableSync(result.first).single().name)
    }
}
