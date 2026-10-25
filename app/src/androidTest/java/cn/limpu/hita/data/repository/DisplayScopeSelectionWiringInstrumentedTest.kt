package cn.limpu.hita.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.HApplication
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.feature.timetable.display.DisplayScopeCoordinator
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisplayScopeSelectionWiringInstrumentedTest {
    @Test
    fun applicationEntryPointResolvesTheSameCoordinatorSingleton() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(application is HApplication)

        val first = EntryPointAccessors.fromApplication(
            application, DisplayScopeSelectionEntryPoint::class.java,
        ).displayScopeSelector()
        val second = EntryPointAccessors.fromApplication(
            application, DisplayScopeSelectionEntryPoint::class.java,
        ).displayScopeSelector()

        assertTrue(first is DisplayScopeCoordinator)
        assertSame(first, second)
        // Resolve the production graph without selecting or modifying its database/preferences.
    }

    @Test
    fun manualConstructionBridgeResolvesOnlyWhenSelectingAndForwardsOnTheCallerThread() {
        val calls = mutableListOf<DisplayScope>()
        val caller = Thread.currentThread()
        val application = SelectorApplication(DisplayScopeSelector {
            assertSame(caller, Thread.currentThread())
            calls += it
        })
        val bridge = ApplicationDisplayScopeSelector(application)
        assertEquals(0, application.componentReads)

        val first = DisplayScope.Single("table")
        val second = DisplayScope.Group("group")
        bridge.select(first)
        bridge.select(second)

        assertEquals(2, application.componentReads)
        assertEquals(2, calls.size)
        assertSame(first, calls[0])
        assertSame(second, calls[1])
    }

    @Test
    fun manualConstructionBridgePropagatesTheSelectedCoordinatorFailure() {
        val failure = IllegalStateException("selection failed")
        var selections = 0
        val application = SelectorApplication(DisplayScopeSelector {
            selections++
            throw failure
        })
        val bridge = ApplicationDisplayScopeSelector(application)

        val thrown = assertThrows(IllegalStateException::class.java) {
            bridge.select(DisplayScope.Single("table"))
        }

        assertSame(failure, thrown)
        assertEquals(1, application.componentReads)
        assertEquals(1, selections)
    }

    /** Exercises Hilt's real EntryPoint lookup without creating a production repository/DB. */
    private class SelectorApplication(selector: DisplayScopeSelector) :
        Application(), GeneratedComponentManager<Any> {
        var componentReads = 0
            private set

        private val component = object : GeneratedComponent, DisplayScopeSelectionEntryPoint {
            override fun displayScopeSelector(): DisplayScopeSelector = selector
        }

        override fun getApplicationContext(): Context = this

        override fun generatedComponent(): Any {
            componentReads++
            return component
        }
    }
}
