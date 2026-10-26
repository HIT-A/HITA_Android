package com.limpu.component.testing

import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal fun onMain(action: () -> Unit) =
    InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

internal class ObservedValues<T>(private val source: LiveData<T>) : Closeable {
    private data class Event<T>(val value: T?)
    private val events = LinkedBlockingQueue<Event<T>>()
    private val observer = Observer<T> { events.add(Event(it)) }

    init { onMain { source.observeForever(observer) } }

    fun next(): T? = (events.poll(5, TimeUnit.SECONDS)
        ?: throw AssertionError("LiveData did not deliver an event")).value

    fun assertNoQueuedValues() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        check(events.isEmpty()) { "Unexpected LiveData events: $events" }
    }

    override fun close() = onMain { source.removeObserver(observer) }
}
