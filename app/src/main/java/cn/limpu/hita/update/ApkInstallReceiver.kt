package cn.limpu.hita.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import cn.limpu.hita.R
import cn.limpu.hita.utils.LogUtils

/**
 * 应用内更新：下载完成后接力安装。
 *
 * 为什么用 Manifest 静态注册：下载大文件时国产 ROM 经常杀掉应用进程，
 * 运行时 receiver 会跟着消失，而 `ACTION_DOWNLOAD_COMPLETE` 是定向广播，
 * 静态 receiver 即使进程被杀也能被系统唤醒。
 *
 * 为什么不在广播里直接 startActivity：Android 10+ 的后台启动限制会把
 * receiver 里的 startActivity 静默丢弃（国产 ROM 的"后台弹出界面"同理）。
 * 因此这里只分两种情况：
 * - App 在前台：直接拉起安装器（有可见界面，允许）；
 * - App 在后台：发高优先级通知，用户点击后再安装（用户发起，不受限制）。
 */
class ApkInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        handleDownloadComplete(context, intent)
    }

    private fun handleDownloadComplete(context: Context, intent: Intent) {
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pendingId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        // 文件名/版本号要在 clearPendingDownload 之前读取：apply() 会同步更新内存副本
        val fileName = prefs.getString(KEY_FILE_NAME, null)
        val versionName = prefs.getString(KEY_VERSION_NAME, "").orEmpty()
        if (downloadId == -1L || downloadId != pendingId) return
        clearPendingDownload(context)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm == null) {
            LogUtils.e("ApkInstallReceiver: DownloadManager 不可用")
            return
        }
        if (!isDownloadSuccessful(dm, downloadId)) {
            LogUtils.e("ApkInstallReceiver: 下载未成功，忽略本次安装")
            return
        }

        val installIntent = ApkInstallLauncher.buildInstallIntent(context, dm, downloadId, fileName)
        if (installIntent == null) {
            LogUtils.e("ApkInstallReceiver: 下载完成但无法读取安装包")
            Toast.makeText(context, R.string.install_apk_unreadable, Toast.LENGTH_LONG).show()
            return
        }

        // 授权理论上已在下载前引导过，这里只处理"下载期间被撤销"的边缘情况
        if (!ApkInstallLauncher.canInstallPackages(context)) {
            ApkInstallLauncher.postUnknownSourceNotification(context)
            Toast.makeText(
                context,
                R.string.install_unknown_source_required,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val launched = ApkInstallLauncher.isAppInForeground() &&
            ApkInstallLauncher.launchInstall(context, installIntent)
        if (launched) {
            Toast.makeText(context, R.string.install_starting, Toast.LENGTH_SHORT).show()
        } else {
            // 后台无法直接启动安装器：交给通知，用户点击后再装
            ApkInstallLauncher.postInstallReadyNotification(context, versionName, installIntent)
        }
    }

    private fun isDownloadSuccessful(dm: DownloadManager, downloadId: Long): Boolean {
        return try {
            dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                cursor.moveToFirst() &&
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                    DownloadManager.STATUS_SUCCESSFUL
            } == true
        } catch (e: Exception) {
            LogUtils.e("ApkInstallReceiver: 查询下载状态失败", e)
            false
        }
    }

    companion object {
        private const val PREFS = "hita_update_prefs"
        private const val KEY_DOWNLOAD_ID = "pending_download_id"
        private const val KEY_FILE_NAME = "pending_file_name"
        private const val KEY_VERSION_NAME = "pending_version_name"

        /** 发起下载时记录，供下载完成广播里核对（进程被杀后内存里的 id 会丢，只能走持久化）。 */
        fun savePendingDownload(
            context: Context,
            downloadId: Long,
            fileName: String,
            versionName: String
        ) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_DOWNLOAD_ID, downloadId)
                .putString(KEY_FILE_NAME, fileName)
                .putString(KEY_VERSION_NAME, versionName)
                .apply()
        }

        fun clearPendingDownload(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_DOWNLOAD_ID)
                .remove(KEY_FILE_NAME)
                .remove(KEY_VERSION_NAME)
                .apply()
        }
    }
}
