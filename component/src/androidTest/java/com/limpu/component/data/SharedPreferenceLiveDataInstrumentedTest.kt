package com.limpu.component.data

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.Observer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limpu.component.testing.ObservedValues
import com.limpu.component.testing.onMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SharedPreferenceLiveDataInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "module-contract-${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @After
    fun cleanup() { assertTrue(preferences.edit().clear().commit()) }

    @Test
    fun observesCurrentValueMatchingChangesRemovalAndReactivation() {
        assertTrue(preferences.edit().putString("value", "initial").commit())
        val source = preferences.stringLiveData("value", "default")
        ObservedValues(source).use { values ->
            assertEquals("initial", values.next())
            onMain { assertTrue(preferences.edit().putString("unrelated", "ignored").commit()) }
            values.assertNoQueuedValues()
            onMain { assertTrue(preferences.edit().putString("value", "changed").commit()) }
            assertEquals("changed", values.next())
            onMain { assertTrue(preferences.edit().remove("value").commit()) }
            assertEquals("default", values.next())
        }
        onMain { assertTrue(preferences.edit().putString("value", "while inactive").commit()) }
        assertEquals("default", source.value)
        ObservedValues(source).use { assertEquals("while inactive", it.next()) }
    }

    @Test
    fun listenersBelongToFirstAndLastObserverWithoutAccumulating() {
        val tracked = TrackingPreferences(preferences)
        val source = tracked.intLiveData("number", 7)
        val first = Observer<Int> { }
        val second = Observer<Int> { }
        try {
            onMain {
                source.observeForever(first)
                source.observeForever(second)
                assertEquals(1, tracked.registrations)
                source.removeObserver(first)
                assertEquals(0, tracked.removals)
                source.removeObserver(second)
                assertEquals(1, tracked.removals)
                source.observeForever(first)
                assertEquals(2, tracked.registrations)
                assertEquals(1, tracked.listeners.size)
            }
        } finally {
            onMain { source.removeObserver(first); source.removeObserver(second) }
        }
        assertEquals(2, tracked.removals)
        assertTrue(tracked.listeners.isEmpty())
    }

    @Test
    fun typedValuesRoundTripChangesAndRemovalThroughActualPreferences() {
        val sources = listOf(
            preferences.intLiveData("int", 2), preferences.booleanLiveData("bool", false),
            preferences.floatLiveData("float", 1.5f), preferences.longLiveData("long", 9L),
            preferences.stringSetLiveData("set", setOf("default"))
        )
        val defaults: List<Any> = listOf(2, false, 1.5f, 9L, setOf("default"))
        val expected: List<Any> = listOf(5, true, 2.25f, 1234567890123L, setOf("甲", "乙"))
        val observers = sources.map { ObservedValues(it) }
        try {
            observers.forEachIndexed { index, values -> assertEquals(defaults[index], values.next()) }
            onMain {
                assertTrue(preferences.edit().putInt("int", 5).putBoolean("bool", true)
                    .putFloat("float", 2.25f).putLong("long", 1234567890123L)
                    .putStringSet("set", setOf("甲", "乙")).commit())
            }
            observers.forEachIndexed { index, values -> assertEquals(expected[index], values.next()) }
            onMain {
                assertTrue(preferences.edit().remove("int").remove("bool").remove("float")
                    .remove("long").remove("set").commit())
            }
            observers.forEachIndexed { index, values -> assertEquals(defaults[index], values.next()) }
        } finally { observers.forEach { it.close() } }
    }

    private class TrackingPreferences(private val real: SharedPreferences) : SharedPreferences by real {
        var registrations = 0
        var removals = 0
        val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            registrations++
            assertTrue(listeners.add(listener))
            real.registerOnSharedPreferenceChangeListener(listener)
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            removals++
            assertTrue(listeners.remove(listener))
            real.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
}
