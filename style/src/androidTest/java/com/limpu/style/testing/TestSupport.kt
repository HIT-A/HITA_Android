package com.limpu.style.testing

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.util.UUID

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

class StyleTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Establish a public, restorable value; AppCompat's initial UNSPECIFIED
        // sentinel cannot be restored using setDefaultNightMode.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }
}

class ThemeTestActivity : Activity() {
    private lateinit var preferencesContext: IsolatedPreferencesContext
    override fun onCreate(savedInstanceState: Bundle?) {
        preferencesContext = IsolatedPreferencesContext(super.getApplicationContext())
        super.onCreate(savedInstanceState)
    }
    override fun getApplicationContext(): Context =
        if (::preferencesContext.isInitialized) preferencesContext else super.getApplicationContext()
    override fun onDestroy() {
        preferencesContext.close()
        super.onDestroy()
    }
}
