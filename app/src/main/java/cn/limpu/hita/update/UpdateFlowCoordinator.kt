package cn.limpu.hita.update

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cn.limpu.hita.R
import cn.limpu.hita.utils.ActivityUtils
import cn.limpu.hita.utils.LogUtils
import com.limpu.hitauser.data.model.CheckUpdateResult

/**
 * 更新流程的"上下文保持"：用户点「去授权」跳到系统设置后，返回应用时自动继续下载。
 *
 * 背景：系统设置页是另一个 Activity，用户授权返回后原本没有任何回调，
 * 更新弹窗已经消失，用户必须重新进「关于」页手动检查更新——这一步完全没有必要。
 *
 * 实现方式：全局注册 `ActivityLifecycleCallbacks`，在任意页面 `onResume` 时检查
 * 是否存在"等待授权"的更新；若已获得权限就继续下载，若仍未授权则轻提示一次。
 * 用全局回调而不是改基类，是因为更新入口既有 [cn.limpu.hita.ui.main.MainActivity]
 * （继承 HiltBaseActivity）也有 [cn.limpu.hita.ui.about.ActivityAbout]（直接继承 AppCompatActivity）。
 */
object UpdateFlowCoordinator {

    /** 等待授权的最长时间：超过后丢弃，避免很久以后回到应用突然开始下载。 */
    private const val PENDING_TTL_MS = 5 * 60 * 1000L

    private var pendingUpdate: CheckUpdateResult? = null
    private var pendingAt: Long = 0L
    private var awaitingReturnFromSettings = false
    private var installed = false

    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) = handleResumed(activity)
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }

    /** 用户选择「去授权」时调用，记住这次更新，等返回后继续。 */
    fun awaitInstallPermission(cr: CheckUpdateResult) {
        pendingUpdate = cr
        pendingAt = System.currentTimeMillis()
        awaitingReturnFromSettings = true
        LogUtils.d("UpdateFlowCoordinator: 等待用户授予安装未知应用权限")
    }

    fun cancel() {
        pendingUpdate = null
        pendingAt = 0L
        awaitingReturnFromSettings = false
    }

    private fun handleResumed(activity: Activity) {
        val cr = pendingUpdate ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        if (System.currentTimeMillis() - pendingAt > PENDING_TTL_MS) {
            LogUtils.d("UpdateFlowCoordinator: 等待授权超时，丢弃本次更新")
            cancel()
            return
        }
        if (!ApkInstallLauncher.canInstallPackages(activity)) {
            if (awaitingReturnFromSettings && activity is AppCompatActivity) {
                // 从设置页返回但仍未授权：提示一次，保持 pending，稍后授权还能继续
                awaitingReturnFromSettings = false
                Toast.makeText(
                    activity,
                    R.string.update_permission_still_denied,
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        pendingUpdate = null
        pendingAt = 0L
        awaitingReturnFromSettings = false
        if (activity !is AppCompatActivity) {
            LogUtils.w("UpdateFlowCoordinator: 前台页面不是 AppCompatActivity，放弃自动继续")
            return
        }
        LogUtils.d("UpdateFlowCoordinator: 已获得安装权限，继续下载 ${cr.latestVersionName}")
        ActivityUtils.resumeUpdateDownload(activity, cr)
    }
}
