package cn.limpu.hita.utils

import android.util.Log
import cn.limpu.hita.BuildConfig

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
            // 找到第一个不是LogUtils/Thread/VMStack等内部类的方法调用
            val caller = stackTrace.firstOrNull { element ->
                val cn = element.className
                !cn.contains("LogUtils") &&
                !cn.contains("Thread") &&
                !cn.contains("getStackTrace") &&
                !cn.contains("dalvik.system.VMStack") &&
                !cn.contains("java.lang.reflect.Method") &&
                !cn.contains("kotlin.coroutines")
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
}