package com.limpu.style

import android.content.ContextWrapper
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.limpu.style.testing.IsolatedPreferencesContext
import com.limpu.style.testing.ThemeTestActivity
import com.limpu.style.testing.onMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeToolsInstrumentedTest {
    @Test
    fun modesStylesAndPresetsPersistIndependentlyAcrossContextWrappers() {
        IsolatedPreferencesContext().use { context ->
            val freshContext = ContextWrapper(context)
            ThemeTools.setThemeMode(context, ThemeTools.MODE.DARK)
            ThemeTools.setColorPreset(context, ThemeTools.COLOR_PRESET.FOCUS)
            val styles = mapOf(
                ThemeTools.STYLE.CLASSIC to "classic", ThemeTools.STYLE.APPLE_GLASS to "apple_glass",
                ThemeTools.STYLE.CYBER to "cyber", ThemeTools.STYLE.SORA_CLOUD to "sora_cloud",
                ThemeTools.STYLE.P5 to "p5", ThemeTools.STYLE.DEEP_SPACE to "deep_space",
                ThemeTools.STYLE.SUMI to "sumi"
            )
            for ((style, stored) in styles) {
                ThemeTools.setThemeStyle(context, style)
                assertEquals(style, ThemeTools.getThemeStyle(freshContext))
                assertEquals(stored, ThemeTools.getThemePreferences(freshContext).getString(ThemeTools.KEY_STYLE, null))
                assertEquals(ThemeTools.MODE.DARK, ThemeTools.getThemeMode(freshContext))
                assertEquals(ThemeTools.COLOR_PRESET.FOCUS, ThemeTools.getColorPreset(freshContext))
            }
            for ((preset, stored) in mapOf(
                ThemeTools.COLOR_PRESET.CLASSIC to "classic", ThemeTools.COLOR_PRESET.FRESH to "fresh",
                ThemeTools.COLOR_PRESET.FOCUS to "focus", ThemeTools.COLOR_PRESET.HIGH_CONTRAST to "high_contrast"
            )) {
                ThemeTools.setColorPreset(context, preset)
                assertEquals(preset, ThemeTools.getColorPreset(freshContext))
                assertEquals(stored, ThemeTools.getThemePreferences(freshContext).getString(ThemeTools.KEY_COLOR_PRESET, null))
                assertEquals(ThemeTools.STYLE.SUMI, ThemeTools.getThemeStyle(freshContext))
                assertEquals(ThemeTools.MODE.DARK, ThemeTools.getThemeMode(freshContext))
            }
            for ((mode, stored) in mapOf(
                ThemeTools.MODE.DARK to "dark", ThemeTools.MODE.LIGHT to "light", ThemeTools.MODE.FOLLOW to "follow"
            )) {
                ThemeTools.setThemeMode(context, mode)
                assertEquals(mode, ThemeTools.getThemeMode(freshContext))
                assertEquals(stored, ThemeTools.getThemePreferences(freshContext).getString(ThemeTools.KEY_MODE, null))
                assertEquals(ThemeTools.STYLE.SUMI, ThemeTools.getThemeStyle(freshContext))
                assertEquals(ThemeTools.COLOR_PRESET.HIGH_CONTRAST, ThemeTools.getColorPreset(freshContext))
            }
        }
    }

    @Test
    fun legacyPaletteStylesMigrateOnceIntoIndependentPresetStorage() {
        for ((legacy, preset) in mapOf(
            "fresh" to ThemeTools.COLOR_PRESET.FRESH,
            "focus" to ThemeTools.COLOR_PRESET.FOCUS,
            "high_contrast" to ThemeTools.COLOR_PRESET.HIGH_CONTRAST
        )) {
            IsolatedPreferencesContext().use { context ->
                val preferences = ThemeTools.getThemePreferences(context)
                assertTrue(preferences.edit().putString(ThemeTools.KEY_STYLE, legacy)
                    .putString(ThemeTools.KEY_MODE, "dark").commit())
                assertEquals(ThemeTools.STYLE.CLASSIC, ThemeTools.getThemeStyle(context))
                assertEquals("classic", preferences.getString(ThemeTools.KEY_STYLE, null))
                assertEquals(preset, ThemeTools.getColorPreset(context))
                assertEquals(ThemeTools.MODE.DARK, ThemeTools.getThemeMode(context))
                val afterMigration = preferences.all.toMap()
                assertEquals(ThemeTools.STYLE.CLASSIC, ThemeTools.getThemeStyle(ContextWrapper(context)))
                assertEquals(afterMigration, preferences.all)
                ThemeTools.setColorPreset(context, ThemeTools.COLOR_PRESET.CLASSIC)
                assertEquals(ThemeTools.STYLE.CLASSIC, ThemeTools.getThemeStyle(context))
                assertEquals(ThemeTools.COLOR_PRESET.CLASSIC, ThemeTools.getColorPreset(context))
            }
        }
    }

    @Test
    fun legacyStyleWinsItsExistingPresetConflictButModernStylePreservesPreset() {
        IsolatedPreferencesContext().use { context ->
            val preferences = ThemeTools.getThemePreferences(context)
            assertTrue(preferences.edit().putString(ThemeTools.KEY_STYLE, "fresh")
                .putString(ThemeTools.KEY_COLOR_PRESET, "focus").commit())
            assertEquals(ThemeTools.STYLE.CLASSIC, ThemeTools.getThemeStyle(context))
            assertEquals(ThemeTools.COLOR_PRESET.FRESH, ThemeTools.getColorPreset(context))
            assertTrue(preferences.edit().putString(ThemeTools.KEY_STYLE, "cyber")
                .putString(ThemeTools.KEY_COLOR_PRESET, "focus").commit())
            assertEquals(ThemeTools.STYLE.CYBER, ThemeTools.getThemeStyle(context))
            assertEquals(ThemeTools.COLOR_PRESET.FOCUS, ThemeTools.getColorPreset(context))
        }
    }

    @Test
    fun absentAndUnknownSettingsFallBackWithoutOverwritingStoredValues() {
        IsolatedPreferencesContext().use { context ->
            assertDefaults(context)
            val preferences = ThemeTools.getThemePreferences(context)
            assertTrue(preferences.all.isEmpty())
            assertTrue(preferences.edit().putString(ThemeTools.KEY_MODE, "unknown")
                .putString(ThemeTools.KEY_STYLE, "unknown").putString(ThemeTools.KEY_COLOR_PRESET, "unknown").commit())
            val before = preferences.all.toMap()
            assertDefaults(context)
            assertEquals(before, preferences.all)
        }
    }

    @Test
    fun realActivityThemeSwitchCyclesPreferencesAndAppCompatNightMode() {
        val originalNightMode = AppCompatDelegate.getDefaultNightMode()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        try {
            ActivityScenario.launch<ThemeTestActivity>(
                Intent(testContext, ThemeTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ).use { scenario ->
                scenario.onActivity { activity ->
                    ThemeTools.setThemeMode(activity, ThemeTools.MODE.DARK)
                    for ((mode, delegateMode) in listOf(
                        ThemeTools.MODE.LIGHT to AppCompatDelegate.MODE_NIGHT_NO,
                        ThemeTools.MODE.FOLLOW to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                        ThemeTools.MODE.DARK to AppCompatDelegate.MODE_NIGHT_YES
                    )) {
                        ThemeTools.switchTheme(activity)
                        assertEquals(mode, ThemeTools.getThemeMode(activity))
                        assertEquals(delegateMode, AppCompatDelegate.getDefaultNightMode())
                    }
                }
            }
        } finally { onMain { AppCompatDelegate.setDefaultNightMode(originalNightMode) } }
    }

    private fun assertDefaults(context: IsolatedPreferencesContext) {
        assertEquals(ThemeTools.MODE.FOLLOW, ThemeTools.getThemeMode(context))
        assertEquals(ThemeTools.STYLE.CLASSIC, ThemeTools.getThemeStyle(context))
        assertEquals(ThemeTools.COLOR_PRESET.CLASSIC, ThemeTools.getColorPreset(context))
    }
}
