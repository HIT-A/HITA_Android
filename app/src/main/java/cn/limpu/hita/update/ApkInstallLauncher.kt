package cn.limpu.hita.update

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import cn.limpu.hita.R
import cn.limpu.hita.utils.FileProviderUtils
import cn.limpu.hita.utils.LogUtils
import java.io.File

/**
 * 更新安装的启动器：负责"把已下载的 APK 交给系统安装器"。
 *
 * 设计要点（对应线上反馈的两个问题）：
 * 1. **不再从广播里启动设置页**。Android 10+ 的后台启动限制（以及国产 ROM 的
 *    "后台弹出界面"管控）会静默丢弃 `BroadcastReceiver.startActivity`，用户在
 *    未授权"安装未知应用"时只会看到一句 Toast。现在授权改为下载前在 Activity 里引导。
 * 2. **App 在后台时不直接拉起安装器**，而是发一条高优先级通知，由用户点击后再安装。
 *    点击通知属于用户发起的操作，不受后台启动限制，也不会被 ROM 拦截。
 * 3. 安装用 DownloadManager 的 content uri（宿主必有读权限）+ 读权限授权，
 *    不再把几十 MB 的 APK 复制进 PackageInstaller 会话（避免广播主线程超时被系统杀掉）。
 */
object ApkInstallLauncher {

    const val APK_MIME = "application/vnd.android.package-archive"
    private const val CHANNEL_ID = "app_update"
    private const val NOTIFICATION_ID_READY = 0x5550
    private const val NOTIFICATION_ID_PERMISSION = 0x5551

    /** Android 8+ 需要用户为 HITA 开启"安装未知应用"。 */
    fun canInstallPackages(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    /** 打开"安装未知应用"授权页；必须在 Activity 或用户点击场景下调用。 */
    fun openUnknownSourceSettings(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            LogUtils.e("ApkInstallLauncher: 打开安装未知应用设置页失败", e)
            false
        }
    }

    /**
     * 构造"安装已下载 APK"的 Intent。
     *
     * 优先用 DownloadManager 的 content uri（系统授予宿主读权限，Android 11+ 分区存储下依然可读），
     * 拿不到时退回 FileProvider（file_paths 已包含 external-path）。
     */
    fun buildInstallIntent(
        context: Context,
        dm: DownloadManager,
        downloadId: Long,
        fileName: String?
    ): Intent? {
        val uri = downloadManagerUri(dm, downloadId) ?: fileUri(context, fileName) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun downloadManagerUri(dm: DownloadManager, downloadId: Long): Uri? {
        return try {
            dm.getUriForDownloadedFile(downloadId)
        } catch (e: Exception) {
            LogUtils.e("ApkInstallLauncher: 获取下载文件 uri 失败", e)
            null
        }
    }

    private fun fileUri(context: Context, fileName: String?): Uri? {
        if (fileName.isNullOrBlank()) return null
        return try {
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            if (file.exists() && file.length() > 0) {
                FileProviderUtils.getUriForFile(context, file)
            } else {
                null
            }
        } catch (e: Exception) {
            LogUtils.e("ApkInstallLauncher: 构造安装包 file uri 失败", e)
            null
        }
    }

    /** App 在前台（有可见界面）时才能安全地直接启动安装器。 */
    fun isAppInForeground(): Boolean {
        return try {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        } catch (e: Exception) {
            LogUtils.e("ApkInstallLauncher: 读取前台状态失败", e)
            false
        }
    }

    /**
     * 直接拉起安装器（仅前台或用户点击后调用）。
     * @return 是否成功启动；失败时调用方应回退到通知。
     */
    fun launchInstall(context: Context, installIntent: Intent): Boolean {
        return try {
            context.startActivity(installIntent)
            true
        } catch (e: Exception) {
            LogUtils.e("ApkInstallLauncher: 启动安装器失败", e)
            false
        }
    }

    /** 后台场景：发通知，用户点击后再安装。 */
    fun postInstallReadyNotification(context: Context, versionName: String, installIntent: Intent) {
        ensureChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_READY,
            installIntent,
            pendingIntentFlags()
        )
        val text = context.getString(
            R.string.update_notification_ready_text,
            versionName.ifBlank { context.getString(R.string.update_notification_version_fallback) }
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_cloud_download_24)
            .setContentTitle(context.getString(R.string.update_notification_ready_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        notifySafely(context, NOTIFICATION_ID_READY, notification)
    }

    /** 后台场景：用户中途撤销了"安装未知应用"授权时，引导去设置（点击通知即可）。 */
    fun postUnknownSourceNotification(context: Context) {
        ensureChannel(context)
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_PERMISSION,
            settingsIntent,
            pendingIntentFlags()
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_cloud_download_24)
            .setContentTitle(context.getString(R.string.update_notification_permission_title))
            .setContentText(context.getString(R.string.update_notification_permission_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        notifySafely(context, NOTIFICATION_ID_PERMISSION, notification)
    }

    private fun pendingIntentFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }

    private fun notifySafely(context: Context, id: Int, notification: android.app.Notification) {
        try {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                LogUtils.w("ApkInstallLauncher: 通知被禁用，无法提示安装")
            }
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: Exception) {
            LogUtils.e("ApkInstallLauncher: 发送更新通知失败", e)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.update_notification_channel_desc)
            }
        )
    }
}
