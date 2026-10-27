package cn.limpu.hita.ui.main

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.limpu.hitauser.data.model.CheckUpdateResult
import com.limpu.style.databinding.DialogBottomUpdateBinding
import com.limpu.style.widgets.PopUpUpdate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainUpdatePresenterInstrumentedTest {
    @Test fun realUpdatePopupRecreationRebindsTextAndCurrentHostSkipAction() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val preferences = app.getSharedPreferences("update_skip", Context.MODE_PRIVATE)
        val code = System.currentTimeMillis()
        val key = code.toString()
        val result = CheckUpdateResult().apply {
            shouldUpdate = true
            latestVersionCode = code
            latestVersionName = "Main restore fixture"
            updateLog = "main-update-restore-body"
            latestUrl = "https://example.invalid/main-update-fixture"
        }
        var presenter: MainUpdatePresenter? = null
        var oldCompletions = 0
        var newCompletions = 0
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    presenter = MainUpdatePresenter(activity) { oldCompletions++ }.also { it.show(result) }
                    val popup = requireNotNull(activity.supportFragmentManager.findFragmentByTag("update") as? PopUpUpdate)
                    assertTrue(DialogBottomUpdateBinding.bind(requireNotNull(popup.view)).text.text.contains(result.updateLog))
                    assertNotNull(popup.onActionListener)
                    presenter?.close()
                    assertNull(popup.onActionListener)
                    assertEquals(0L, preferences.getLong("dismissed_at_$key", 0))
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    presenter = MainUpdatePresenter(activity) { newCompletions++ }.also { it.show(result) }
                    val popup = requireNotNull(activity.supportFragmentManager.findFragmentByTag("update") as? PopUpUpdate)
                    assertTrue(DialogBottomUpdateBinding.bind(requireNotNull(popup.view)).text.text.contains(result.updateLog))
                    assertNotNull(popup.onActionListener)
                    DialogBottomUpdateBinding.bind(requireNotNull(popup.view)).skip.performClick()
                    activity.supportFragmentManager.executePendingTransactions()
                    assertEquals(0, oldCompletions)
                    assertEquals(1, newCompletions)
                    assertTrue(preferences.getBoolean(key, false))
                    assertEquals(0L, preferences.getLong("dismissed_at_$key", 0))
                    presenter?.close()
                }
            }
        } finally {
            preferences.edit().remove(key).remove("dismissed_at_$key").commit()
        }
    }

    @Test fun skippedUpdateIsConsumedWithoutCreatingDialog() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val preferences = app.getSharedPreferences("update_skip", Context.MODE_PRIVATE)
        val code = System.currentTimeMillis()
        val key = code.toString()
        val result = CheckUpdateResult().apply { shouldUpdate = true; latestVersionCode = code }
        preferences.edit().putBoolean(key, true).commit()
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    var finished = 0
                    MainUpdatePresenter(activity) { finished++ }.use { presenter ->
                        presenter.show(result)
                        assertEquals(1, finished)
                        assertNull(activity.supportFragmentManager.findFragmentByTag("update"))
                    }
                }
            }
        } finally { preferences.edit().remove(key).commit() }
    }
}
