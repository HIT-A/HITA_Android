package cn.limpu.hita.ui.design

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal val LocalHitaReducedMotion = compositionLocalOf { false }

@Composable
internal fun rememberHitaReducedMotion(): Boolean {
    val resolver = LocalContext.current.applicationContext.contentResolver
    var reduced by remember(resolver) { mutableStateOf(isReducedMotion(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = isReducedMotion(resolver)
            }
        }
        SCALE_KEYS.forEach { key ->
            resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
        }
        reduced = isReducedMotion(resolver)
        onDispose { SCALE_KEYS.forEach { resolver.unregisterContentObserver(observer) } }
    }
    return reduced
}

private val SCALE_KEYS = listOf(
    Settings.Global.ANIMATOR_DURATION_SCALE,
    Settings.Global.TRANSITION_ANIMATION_SCALE,
    Settings.Global.WINDOW_ANIMATION_SCALE,
)

/** 任一系统动画缩放关闭，都应把装饰性动画收成终态；跟手拖动不受影响。 */
private fun isReducedMotion(resolver: ContentResolver): Boolean =
    SCALE_KEYS.any { Settings.Global.getFloat(resolver, it, 1f) == 0f }

internal object HitaMotion {
    fun <T> feedback(reduced: Boolean) = if (reduced) snap<T>() else tween<T>(110, easing = FastOutSlowInEasing)
    fun <T> settle(reduced: Boolean) = if (reduced) snap<T>() else tween<T>(160, easing = LinearOutSlowInEasing)
    fun <T> reveal(reduced: Boolean, entering: Boolean) = if (reduced) snap<T>() else tween<T>(
        durationMillis = if (entering) 220 else 180,
        easing = if (entering) LinearOutSlowInEasing else FastOutLinearInEasing,
    )
}

/** Drag and settle share one position. Cancelling a settle hands its current position to the next drag. */
internal class HorizontalMotion(private val scope: CoroutineScope, initial: Float = 0f) {
    var offset by mutableFloatStateOf(initial)
        private set
    private var settleJob: Job? = null

    fun takeOver(): Float {
        settleJob?.cancel()
        return offset
    }

    fun follow(value: Float) { offset = value }

    fun settle(reduced: Boolean) = settleTo(0f, reduced)

    fun settleTo(target: Float, reduced: Boolean) {
        val from = takeOver()
        if (reduced || from == target) {
            offset = target
        } else {
            settleJob = scope.launch {
                animate(from, target, animationSpec = HitaMotion.settle(false)) { value, _ -> offset = value }
            }
        }
    }

    fun reset() = settle(reduced = true)
}

@Composable
internal fun rememberHorizontalMotion(): HorizontalMotion {
    val scope = rememberCoroutineScope()
    return remember(scope) { HorizontalMotion(scope) }
}
