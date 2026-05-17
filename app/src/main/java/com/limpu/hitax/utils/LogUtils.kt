package com.limpu.hitax.utils

import android.util.Log
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.limpu.hitax.BuildConfig

/**
 * 日志工具类
 * 统一项目的日志记录格式和标准
 */
object LogUtils {

    private const val GLOBAL_TAG = "HITA"
    private val ENABLE_DEBUG = BuildConfig.DEBUG

    /**
     * 获取调用者的类名作为TAG
     * 自动从调用栈中提取类名
     */
    private fun getCallerTag(): String {
        return try {
            val stackTrace = Thread.currentThread().stackTrace
            // 找到第一个不是LogUtils/Thread的方法调用
            val caller = stackTrace.firstOrNull { element ->
                !element.className.contains("LogUtils") &&
                !element.className.contains("Thread") &&
                !element.className.contains("getStackTrace")
            }
            caller?.className?.substringAfterLast('.') ?: GLOBAL_TAG
        } catch (e: Exception) {
            GLOBAL_TAG
        }
    }

    /**
     * 调试日志
     * 使用统一的格式：[TAG] 📍 message
     */
    fun d(message: String, tag: String = getCallerTag()) {
        if (ENABLE_DEBUG) {
            Log.d("[$tag]", "📍 $message")
        }
    }

    /**
     * 信息日志
     * 使用统一的格式：[TAG] ℹ️ message
     */
    fun i(message: String, tag: String = getCallerTag()) {
        if (ENABLE_DEBUG) {
            Log.i("[$tag]", "ℹ️ $message")
        }
    }

    /**
     * 警告日志
     * 使用统一的格式：[TAG] ⚠️ message
     */
    fun w(message: String, tag: String = getCallerTag()) {
        Log.w("[$tag]", "⚠️ $message")
    }

    /**
     * 错误日志
     * 使用统一的格式：[TAG] ❌ message
     */
    fun e(message: String, throwable: Throwable? = null, tag: String = getCallerTag()) {
        if (throwable != null) {
            Log.e("[$tag]", "❌ $message", throwable)
        } else {
            Log.e("[$tag]", "❌ $message")
        }
    }

    /**
     * 成功日志
     * 使用统一的格式：[TAG] ✅ message
     */
    fun success(message: String, tag: String = getCallerTag()) {
        if (ENABLE_DEBUG) {
            Log.i("[$tag]", "✅ $message")
        }
    }

    /**
     * 性能日志
     * 使用统一的格式：[TAG] ⏱️ message
     */
    fun perf(message: String, tag: String = getCallerTag()) {
        if (ENABLE_DEBUG) {
            Log.d("[$tag]", "⏱️ $message")
        }
    }

    /**
     * 网络日志
     * 使用统一的格式：[TAG] 🌐 message
     */
    fun network(message: String, tag: String = getCallerTag()) {
        if (ENABLE_DEBUG) {
            Log.d("[$tag]", "🌐 $message")
        }
    }

    /**
     * 数据库日志
     * 使用统一的格式：[TAG] 💾 message
     */
    fun database(message: String, tag: String = getCallerTag()) {
        if (ENABLE_DEBUG) {
            Log.d("[$tag]", "💾 $message")
        }
    }

    /**
     * 带格式化参数的日志
     */
    fun d(format: String, vararg args: Any, tag: String = getCallerTag()) {
        if (ENABLE_DEBUG) {
            Log.d("[$tag]", "📍 ${String.format(format, *args)}")
        }
    }

    /**
     * 方法进入日志
     */
    fun enterMethod(methodName: String = "", tag: String = getCallerTag()) {
        if (ENABLE_DEBUG) {
            val method = if (methodName.isNotEmpty()) ":: $methodName" else ""
            Log.d("[$tag]", "→️ $method")
        }
    }

    /**
     * 方法退出日志
     */
    fun exitMethod(methodName: String = "", tag: String = getCallerTag()) {
        if (ENABLE_DEBUG) {
            val method = if (methodName.isNotEmpty()) ":: $methodName" else ""
            Log.d("[$tag]", "←️ $method")
        }
    }
}

/**
 * Fragment扩展函数，用于自动记录生命周期
 */
fun Fragment.logLifecycle(event: String) {
    LogUtils.d("${this.javaClass.simpleName}: $event", tag = "FragmentLifecycle")
}

/**
 * Any类的扩展函数，用于记录对象信息
 */
fun <T : Any> T.logInfo(message: String = "") {
    val className = this.javaClass.simpleName
    val msg = if (message.isNotEmpty()) "$className: $message" else className
    LogUtils.i(msg, tag = className)
}