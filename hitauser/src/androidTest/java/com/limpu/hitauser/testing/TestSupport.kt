package com.limpu.hitauser.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal fun onMain(action: () -> Unit) =
    InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

internal class IsolatedPreferencesContext(
    base: Context = InstrumentationRegistry.getInstrumentation().targetContext
) : ContextWrapper(base), Closeable {
    private val prefix = "module-contract-${UUID.randomUUID()}-"
    private val preferences = mutableSetOf<SharedPreferences>()
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences(prefix + name, mode).also { preferences.add(it) }
    override fun close() { preferences.forEach { check(it.edit().clear().commit()) } }
}

internal class ObservedValues<T>(private val source: LiveData<T>) : Closeable {
    private data class Event<T>(val value: T?)
    private val events = LinkedBlockingQueue<Event<T>>()
    private val observer = Observer<T> { events.add(Event(it)) }
    init { onMain { source.observeForever(observer) } }
    fun next(): T? = (events.poll(5, TimeUnit.SECONDS)
        ?: throw AssertionError("LiveData did not deliver an event")).value
    fun awaitMatching(predicate: (T?) -> Boolean): T? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            val event = events.poll((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                ?: throw AssertionError("LiveData did not deliver the expected value")
            if (predicate(event.value)) return event.value
        }
    }
    override fun close() = onMain { source.removeObserver(observer) }
}
