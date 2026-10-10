package cn.limpu.hita.update

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import cn.limpu.hita.R
import cn.limpu.hita.utils.LogUtils
import java.io.File
import java.io.InputStream

/**
 * 应用内更新：下载完成后自动唤起安装。
 *
 * 之所以不用运行时 registerReceiver + ACTION_VIEW，主要有两个坑：
 * 1. 下载耗时较长时，国产 ROM 经常杀掉应用进程，运行时 receiver 随进程一起没了，
 *    广播永远收不到，只能靠 DownloadManager 自己的通知手动点安装。
 *    ACTION_DOWNLOAD_COMPLETE 在系统后台广播豁免名单里，Manifest 静态注册的
 *    receiver 即使进程被杀也会被系统唤醒。
 * 2. 从 receiver 里直接 startActivity 拉安装器，会撞上 Android 10+ 的后台启动限制
 *    以及部分 ROM 的"后台弹出界面"管控。改走 PackageInstaller 会话提交后，
 *    安装确认页由系统直接弹出，不需要应用自己启动 Activity。
 */
class ApkInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> handleDownloadComplete(context, intent)
            ACTION_INSTALL_STATUS -> handleInstallStatus(context, intent)
        }
    }

    private fun handleDownloadComplete(context: Context, intent: Intent) {
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pendingId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (downloadId == -1L || downloadId != pendingId) return
        clearPendingDownload(context)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        if (!isDownloadSuccessful(dm, downloadId)) return

        val apkStream = openApkStream(context, dm, downloadId, prefs.getString(KEY_FILE_NAME, null))
        if (apkStream == null) {
            LogUtils.e("ApkInstallReceiver: 下载完成但无法读取安装包")
            Toast.makeText(context, R.string.install_apk_unreadable, Toast.LENGTH_LONG).show()
            return
        }

        // Android 8+ 需要"安装未知应用"授权；没授权时 ACTION_VIEW 会静默失败，先跳设置页
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            try {
                apkStream.close()
            } catch (_: Exception) {
            }
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            Toast.makeText(context, R.string.install_unknown_source_required, Toast.LENGTH_LONG).show()
            return
        }

        installViaSession(context, dm, downloadId, apkStream)
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

    /**
     * 打开安装包输入流。优先用 DownloadManager 的 content uri（宿主应用一定有读权限），
     * 拿不到时兜底用当初指定的下载目标文件。
     */
    private fun openApkStream(
        context: Context,
        dm: DownloadManager,
        downloadId: Long,
        fileName: String?
    ): InputStream? {
        try {
            val uri = dm.getUriForDownloadedFile(downloadId)
            if (uri != null) {
                val stream = context.contentResolver.openInputStream(uri)
                if (stream != null) return stream
            }
        } catch (e: Exception) {
            LogUtils.e("ApkInstallReceiver: 经 DownloadManager 打开安装包失败", e)
        }
        if (!fileName.isNullOrBlank()) {
            try {
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    fileName
                )
                if (file.exists() && file.length() > 0 && file.canRead()) {
                    return file.inputStream()
                }
            } catch (e: Exception) {
                LogUtils.e("ApkInstallReceiver: 经下载目标文件打开安装包失败", e)
            }
        }
        return null
    }

    private fun queryTotalBytes(dm: DownloadManager, downloadId: Long): Long {
        return try {
            dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                } else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun installViaSession(
        context: Context,
        dm: DownloadManager,
        downloadId: Long,
        apkStream: InputStream
    ) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val totalBytes = queryTotalBytes(dm, downloadId)
        if (totalBytes > 0) params.setSize(totalBytes)
        var sessionId = 0
        try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite(SESSION_WRITE_NAME, 0, -1).use { out ->
                    apkStream.use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(newStatusPendingIntent(context, sessionId).intentSender)
            }
        } catch (e: Exception) {
            LogUtils.e("ApkInstallReceiver: PackageInstaller 提交失败", e)
            try {
                apkStream.close()
            } catch (_: Exception) {
            }
            if (sessionId != 0) {
                try {
                    installer.abandonSession(sessionId)
                } catch (_: Exception) {
                }
            }
            Toast.makeText(context, R.string.install_failed_manual, Toast.LENGTH_LONG).show()
        }
    }

    private fun newStatusPendingIntent(context: Context, sessionId: Int): PendingIntent {
        val statusIntent = Intent(context, ApkInstallReceiver::class.java)
            .setAction(ACTION_INSTALL_STATUS)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
    }

    private fun handleInstallStatus(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 系统授权的安装确认页：后台启动豁免，可直接 startActivity
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                try {
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
                } catch (e: Exception) {
                    LogUtils.e("ApkInstallReceiver: 唤起安装确认页失败", e)
                    Toast.makeText(context, R.string.install_failed_manual, Toast.LENGTH_LONG).show()
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, R.string.install_success, Toast.LENGTH_SHORT).show()
            }

            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                LogUtils.e("ApkInstallReceiver: 安装失败: $msg")
                Toast.makeText(context, R.string.install_failed_manual, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "cn.limpu.hita.update.INSTALL_STATUS"
        private const val SESSION_WRITE_NAME = "hita_update_apk"
        private const val PREFS = "hita_update_prefs"
        private const val KEY_DOWNLOAD_ID = "pending_download_id"
        private const val KEY_FILE_NAME = "pending_file_name"

        /** 发起下载时记录，供下载完成广播里核对（进程被杀后内存里的 id 会丢，只能走持久化）。 */
        fun savePendingDownload(context: Context, downloadId: Long, fileName: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_DOWNLOAD_ID, downloadId)
                .putString(KEY_FILE_NAME, fileName)
                .apply()
        }

        fun clearPendingDownload(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_DOWNLOAD_ID)
                .remove(KEY_FILE_NAME)
                .apply()
        }
    }
}
