package cn.limpu.hita.ui.main

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.notice.AppNotice
import cn.limpu.hita.data.repository.AutoImportResult
import cn.limpu.hita.data.repository.DisplayScopeSelector
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.EasSettingsRepository
import cn.limpu.hita.data.repository.TimetableChangeStore
import cn.limpu.hita.data.repository.TimetableMutationLock
import cn.limpu.hita.data.source.preference.EasCredentialStore
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import com.limpu.component.data.DataState
import com.limpu.hitauser.data.UserDatabase
import com.limpu.hitauser.data.model.CheckUpdateResult
import com.limpu.hitauser.data.model.UserLocal
import com.limpu.hitauser.data.repository.LocalUserRepository
import com.limpu.hitauser.data.source.preference.UserPreferenceSource
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainViewModelInstrumentedTest {
    @Test fun interruptedUpdateRetriesForLocalUserAndOnlyNewHostReceivesCompletion() = Harness().use { h ->
        h.start()
        assertEquals(1, h.requests.updates.size)
        val firstHost = h.host
        h.replaceHost(start = true)
        assertEquals(2, h.requests.updates.size)
        h.main {
            h.requests.updates[0].complete(DataState(update("old")))
            h.requests.updates[1].complete(DataState(update("new")))
        }
        h.flush()
        assertEquals(listOf("new"), h.accepted.mapNotNull { (it.value as? MainEffect.Update)?.result?.latestVersionName })
        assertTrue(h.accepted.none { it.host == firstHost })
    }

    @Test fun unconsumedUpdateTransfersToReplacementHostAndIsNotRepeatedAfterAction() = Harness().use { h ->
        h.start()
        val result = update("retained")
        h.main { h.requests.updates.single().complete(DataState(result)) }
        h.flush()
        assertEquals(1, h.accepted.filter { it.value is MainEffect.Update }.size)
        h.replaceHost(start = false)
        h.flush()
        assertEquals(2, h.accepted.filter { it.value is MainEffect.Update }.size)
        h.main { h.model.updateFinished(result) }
        h.replaceHost(start = false)
        h.flush()
        assertEquals(2, h.accepted.filter { it.value is MainEffect.Update }.size)
    }

    @Test fun importCompletionAfterHostReplacementPromptsCurrentSessionExactlyOnce() = Harness(loggedInEas = true).use { h ->
        h.start()
        assertEquals(1, h.requests.imports.size)
        h.replaceHost(start = true)
        assertEquals(1, h.requests.imports.size)
        h.main { h.requests.imports.single().complete(AutoImportResult.NOT_LOGGED_IN) }
        h.flush()
        val effects = h.accepted.filter { it.value == MainEffect.SessionExpired }
        assertEquals(1, effects.size)
        assertEquals(h.host, effects.single().host)
        assertTrue(h.eas.isSessionExpired())
        h.main { h.model.sessionExpiredPresented() }
        h.replaceHost(start = true)
        h.flush()
        assertEquals(1, h.accepted.filter { it.value == MainEffect.SessionExpired }.size)
    }

    @Test fun importWithNoAttachedHostRetainsPromptButOldAccountResultCannotMarkNewSession() = Harness(loggedInEas = true).use { h ->
        h.start()
        h.detachHost()
        h.main { h.requests.imports.single().complete(AutoImportResult.NOT_LOGGED_IN) }
        h.flush()
        assertTrue(h.accepted.none { it.value == MainEffect.SessionExpired })
        h.replaceHost(start = false)
        h.flush()
        assertEquals(1, h.accepted.count { it.value == MainEffect.SessionExpired })
        h.main {
            h.model.sessionExpiredPresented()
            h.eas.clearSessionExpired()
            h.eas.saveEasTokenSync(token("second"))
            h.model.accountChanged(h.eas.getEasToken())
            h.eas.saveEasTokenSync(token("third"))
            h.model.accountChanged(h.eas.getEasToken())
            h.requests.imports[1].complete(AutoImportResult.NOT_LOGGED_IN)
        }
        h.flush()
        assertFalse(h.eas.isSessionExpired())
        assertEquals(1, h.accepted.count { it.value == MainEffect.SessionExpired })
    }

    @Test fun noticeSelectionSurvivesHostReplacementUntilActualDismissal() = Harness().use { h ->
        h.requests.noticeResult = listOf(AppNotice("main-fixture", "incident", "critical", "title", "body", null, null, null, null))
        h.start()
        h.flush()
        val selection = (h.accepted.single { it.value is MainEffect.Notice }.value as MainEffect.Notice).selection
        h.replaceHost(start = false)
        h.flush()
        assertEquals(2, h.accepted.count { it.value is MainEffect.Notice })
        h.main { h.model.noticeFinished(selection) }
        h.replaceHost(start = false)
        h.flush()
        assertEquals(2, h.accepted.count { it.value is MainEffect.Notice })
        assertEquals(1, h.requests.noticeCalls)
    }

    @Test fun clearingRealViewModelCancelsRequestsAndRejectsLateCompletions() = Harness(loggedInEas = true).use { h ->
        h.start()
        h.main { h.model.saveAvatar(Uri.parse("content://main-fixture/avatar")); h.store.clear() }
        val count = h.accepted.size
        h.main {
            h.requests.updates.single().complete(DataState(update("late")))
            h.requests.imports.single().complete(AutoImportResult.NOT_LOGGED_IN)
            h.requests.avatars.single().complete(true)
        }
        h.flush()
        assertEquals(count, h.accepted.size)
        assertFalse(h.eas.isSessionExpired())
        assertEquals(0L, h.settings.getLastAutoReimportTs())
    }

    private class Requests : MainRequests {
        val updates = mutableListOf<CompletableDeferred<DataState<CheckUpdateResult>>>()
        val imports = mutableListOf<CompletableDeferred<AutoImportResult>>()
        val avatars = mutableListOf<CompletableDeferred<Boolean>>()
        var noticeResult = emptyList<AppNotice>()
        var noticeCalls = 0
        var blogs = 0
        override fun syncBlog() { blogs++ }
        override suspend fun importTimetable(undergraduate: Boolean): AutoImportResult =
            CompletableDeferred<AutoImportResult>().also(imports::add).await()
        override suspend fun checkUpdate(versionCode: Long): DataState<CheckUpdateResult> =
            CompletableDeferred<DataState<CheckUpdateResult>>().also(updates::add).await()
        override suspend fun notices(): List<AppNotice> { noticeCalls++; return noticeResult }
        override suspend fun saveAvatar(uri: Uri): Boolean = CompletableDeferred<Boolean>().also(avatars::add).await()
    }

    private class Harness(loggedInEas: Boolean = false) : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val app = ApplicationProvider.getApplicationContext<Application>()
        private val isolated = IsolatedApplication(app)
        private val database = Room.inMemoryDatabaseBuilder(app, UserDatabase::class.java).build()
        private val preference = EasPreferenceSource(isolated)
        val eas = EASRepository(app, preference, EasCredentialStore(isolated),
            TimetablePreferenceSource(isolated, preference), TimetableChangeStore(isolated), TimetableMutationLock())
        val settings = EasSettingsRepository(isolated)
        val requests = Requests()
        val store = ViewModelStore()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var effectCollection: Job? = null
        lateinit var model: MainViewModel
        var host = 0L
        val accepted = mutableListOf<MainHostEffect>()

        init {
            val userPreference = UserPreferenceSource(isolated)
            userPreference.saveLocalUser(UserLocal().apply { username = "fixture"; token = "fixture"; publicKey = "fixture" })
            settings.setAutoReimport(loggedInEas)
            settings.setLastAutoReimportTs(0)
            main {
                if (loggedInEas) eas.saveEasTokenSync(token("first"))
                model = MainViewModel(isolated, LocalUserRepository(userPreference, database), eas,
                    requests, settings, WallpaperStore(isolated), DisplayScopeSelector {})
                store.put("main", model)
                model.initialize(null, 0)
            }
        }

        fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun flush() = instrumentation.waitForIdleSync()
        fun start() {
            main { attachHost(start = true) }
            flush()
        }
        fun detachHost() {
            main {
                effectCollection?.cancel()
                effectCollection = null
                model.detachHost(host)
            }
            flush()
        }
        fun replaceHost(start: Boolean) {
            detachHost()
            main { attachHost(start) }
            flush()
        }
        private fun attachHost(start: Boolean) {
            host = model.attachHost()
            if (start) model.start(host, 100)
            // MainActivity attaches in initViews, then collects only while RESUMED.
            // A replacement must stop the old collector before attachHost replays pending effects.
            val collectingHost = host
            effectCollection = scope.launch {
                model.effects.collect { if (model.accepts(it, collectingHost)) accepted += it }
            }
        }
        override fun close() {
            main { store.clear(); scope.cancel() }
            database.close()
            isolated.clean()
        }
    }

    private class IsolatedApplication(base: Application) : Application() {
        private val prefix = "main-vm-${UUID.randomUUID()}-"
        private val preferenceNames = mutableSetOf<String>()
        private val directory = File(base.cacheDir, prefix).apply { mkdirs() }
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            preferenceNames += name
            return baseContext.getSharedPreferences(prefix + name, mode)
        }
        override fun deleteSharedPreferences(name: String): Boolean = baseContext.deleteSharedPreferences(prefix + name)
        fun clean() { preferenceNames.forEach(::deleteSharedPreferences); directory.deleteRecursively() }
    }

    companion object {
        private fun update(version: String) = CheckUpdateResult().apply { shouldUpdate = true; latestVersionName = version }
        private fun token(account: String) = EASToken().apply { username = account; accessToken = "synthetic-$account" }
    }
}
