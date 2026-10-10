package cn.limpu.hita.utils

import android.app.Activity
import android.app.DownloadManager
import cn.limpu.hita.BuildConfig
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.setPadding
import com.limpu.style.R as StyleR
import cn.limpu.hita.R
import cn.limpu.hita.update.ApkInstallReceiver
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import cn.limpu.hita.ui.eas.login.PopUpLoginEAS
import cn.limpu.hita.ui.myprofile.MyProfileActivity
import cn.limpu.hita.ui.eas.imp.ImportTimetableActivity
import cn.limpu.hita.ui.resource.CourseContributionActivity
import cn.limpu.hita.ui.resource.CourseReadmeActivity
import cn.limpu.hita.ui.resource.InternalWebActivity
import cn.limpu.hita.ui.resource.UnifiedResourceSearchActivity
import cn.limpu.hita.ui.news.NewsDetailActivity
import cn.limpu.hita.ui.base.HiltBaseActivity
import cn.limpu.hita.ui.profile.ProfileActivity
import cn.limpu.hita.ui.search.SearchActivity
import cn.limpu.hita.ui.subject.SubjectActivity
import cn.limpu.hita.ui.teacher.ActivityTeacherOfficial
import cn.limpu.hita.ui.timetable.detail.TimetableDetailActivity
import cn.limpu.hita.ui.timetable.manager.TimetableManagerActivity
import com.limpu.hitauser.data.model.CheckUpdateResult
import com.limpu.hitauser.data.repository.LocalUserRepository
import com.limpu.style.widgets.PopUpText
import com.limpu.style.widgets.PopUpUpdate
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.net.URLEncoder
import java.nio.charset.StandardCharsets


object ActivityUtils {
    private const val UPDATE_SKIP_PREF = "update_skip"
    private const val UPDATE_DISMISSED_PREFIX = "dismissed_at_"
    private const val UPDATE_DISMISS_COOLDOWN_MS = 7L * 24L * 60L * 60L * 1000L

    fun startOfficialTeacherActivity(from: Context, id: String, url: String, name: String) {
        val i = Intent(from, ActivityTeacherOfficial::class.java)
        i.putExtra("id", id)
        i.putExtra("url", url)
        i.putExtra("name", name)
        HiltBaseActivity.startWithCrossFade(from, i)
    }


    enum class SearchType { TEACHER }

    enum class CourseResourceMode { VIEW, SUBMIT }

    fun searchFor(from: Context, text: String?, type: SearchType) {
        if (text.isNullOrBlank()) return
        val i = Intent(from, SearchActivity::class.java)
        i.putExtra("keyword", text)
        i.putExtra("type", type.name)
        HiltBaseActivity.startWithCrossFade(from, i)
    }

    fun startSearchActivity(from: Context) {
        val i = Intent(from, SearchActivity::class.java)
        HiltBaseActivity.startWithCrossFade(from, i)
    }

    fun startSearchActivity(from: Activity,transition: View) {
        val i = Intent(from, SearchActivity::class.java)
        transition.transitionName = "search"
        val ao = ActivityOptionsCompat.makeSceneTransitionAnimation(from,transition,"search")
        from.startActivity(i,ao.toBundle())
    }
    fun startMyProfileActivity(from: Context) {
        val i = Intent(from, MyProfileActivity::class.java)
        HiltBaseActivity.startWithCrossFade(from, i)
    }

    fun startImportTimetableActivity(from: Context, autoImport: Boolean = false) {
        val i = Intent(from, ImportTimetableActivity::class.java)
        i.putExtra("autoImport", autoImport)
        HiltBaseActivity.startWithCrossFade(from, i)
    }

    fun startWelcomeActivity(from: Context, easRepository: EASRepository) {
        if (from is AppCompatActivity) {
            showEasVerifyWindow<Activity>(
                from = from,
                easRepository = easRepository,
                directTo = null,
                onResponseListener = object : PopUpLoginEAS.OnResponseListener {
                    override fun onSuccess(window: PopUpLoginEAS) {
                        window.dismiss()
                    }

                    override fun onFailed(window: PopUpLoginEAS) {}
                }
            )
        } else {
            Toast.makeText(from, R.string.eas_login_prompt, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 进行教务认证，或直接跳转
     * @param directTo 若存在已登录token，则直接跳转到activity。传null表示忽略
     * @param lock 是否锁定窗口（=true时，若cancel则连带宿主一起销毁）
     * @param onResponseListener 认证监听
     */
    fun <T : Activity> showEasVerifyWindow(
        from: Context,
        easRepository: EASRepository,
        directTo: Class<T>? = null,
        lock: Boolean = false,
        autoLaunchWebLogin: Boolean = false,
        preferredCampus: EASToken.Campus? = null,
        onResponseListener: PopUpLoginEAS.OnResponseListener
    ) {
        LogUtils.d("=== 🔍 showEasVerifyWindow START ===")
        LogUtils.d("Original context type: ${from.javaClass.name}")
        LogUtils.d("Original context class hierarchy: ${getContextHierarchy(from)}")

        // 解包Hilt的Context包装器，获取底层的AppCompatActivity
        val activity = unwrapContextToActivity(from)

        LogUtils.d("Unwrapped activity: ${activity?.javaClass?.name}, is AppCompatActivity=${activity is AppCompatActivity}")

        if (activity is AppCompatActivity) {
            LogUtils.d("✅ Successfully unwrapped to AppCompatActivity: ${activity.javaClass.simpleName}")
            if (easRepository.getEasToken().isLogin()) {
                directTo?.let {
                    LogUtils.d("User already logged in, starting direct activity: ${directTo.simpleName}")
                    val i = Intent(activity, directTo)
                    HiltBaseActivity.startWithCrossFade(activity, i)
                    return
                }
            }
            LogUtils.d("Creating PopUpLoginEAS and showing")
            val window = PopUpLoginEAS()
            window.lock = lock
            window.autoLaunchWebLogin = autoLaunchWebLogin
            window.preferredCampus = preferredCampus
            window.onResponseListener = onResponseListener
            window.show(activity.supportFragmentManager, "verify")
        } else {
            LogUtils.e("❌ showEasVerifyWindow FAILED: unable to extract AppCompatActivity from context")
            LogUtils.e("Original context type: ${from.javaClass.name}")
            LogUtils.e("Context hierarchy: ${getContextHierarchy(from)}")
            LogUtils.e("This usually means the context is not properly initialized or is a wrong type")
        }
    }

    /**
     * 从可能被包装的Context中提取底层的Activity
     * 支持Hilt的FragmentContextWrapper和其他Context包装器
     */
    private fun unwrapContextToActivity(context: Context): AppCompatActivity? {
        var currentContext: Context? = context

        LogUtils.d("=== 🔧 Starting context unwrapping ===")
        LogUtils.d("Initial context: ${currentContext?.javaClass?.name}")

        // 最多解包5层，避免无限循环
        repeat(5) {
            val ctx = currentContext ?: run {
                LogUtils.d("❌ Context became null at iteration $it")
                return null
            }

            LogUtils.d("Iteration $it: context type = ${ctx.javaClass.name}")

            when (ctx) {
                is AppCompatActivity -> {
                    LogUtils.d("✅ Found AppCompatActivity: ${ctx.javaClass.simpleName}")
                    return ctx
                }
                is Activity -> {
                    LogUtils.d("⚠️ Found Activity (not AppCompatActivity): ${ctx.javaClass.simpleName}")
                    // 如果是Activity但不是AppCompatActivity，尝试转换
                    return ctx as? AppCompatActivity
                }
                is android.view.ContextThemeWrapper -> {
                    LogUtils.d("📦 Unwrapping ContextThemeWrapper")
                    currentContext = ctx.baseContext
                    LogUtils.d("   -> baseContext: ${currentContext?.javaClass?.name}")
                }
                else -> {
                    LogUtils.d("🔍 Attempting to unwrap custom wrapper: ${ctx.javaClass.simpleName}")
                    // 尝试多种方式获取baseContext
                    var unwrapped = false

                    // 方法1: 尝试反射获取baseContext字段
                    try {
                        val baseContextField = ctx.javaClass.getDeclaredField("baseContext")
                        baseContextField.isAccessible = true
                        val baseCtx = baseContextField.get(ctx) as? Context
                        if (baseCtx != null && baseCtx != ctx) {
                            LogUtils.d("   -> [reflection] baseContext: ${baseCtx.javaClass.name}")
                            currentContext = baseCtx
                            unwrapped = true
                        }
                    } catch (e: Exception) {
                        LogUtils.d("   -> [reflection] Failed: ${e.message}")
                    }

                    // 方法2: 尝试获取activity字段（针对FragmentContextWrapper）
                    if (!unwrapped) {
                        try {
                            val activityField = ctx.javaClass.getDeclaredField("activity")
                            activityField.isAccessible = true
                            val activity = activityField.get(ctx) as? Activity
                            if (activity != null) {
                                LogUtils.d("   -> [activity field] Found activity: ${activity.javaClass.name}")
                                return activity as? AppCompatActivity
                            }
                        } catch (e: Exception) {
                            LogUtils.d("   -> [activity field] Failed: ${e.message}")
                        }
                    }

                    // 方法3: 尝试通过getActivity方法（如果有）
                    if (!unwrapped) {
                        try {
                            val getActivityMethod = ctx.javaClass.getDeclaredMethod("getActivity")
                            getActivityMethod.isAccessible = true
                            val activity = getActivityMethod.invoke(ctx) as? Activity
                            if (activity != null) {
                                LogUtils.d("   -> [getActivity method] Found activity: ${activity.javaClass.name}")
                                return activity as? AppCompatActivity
                            }
                        } catch (e: Exception) {
                            LogUtils.d("   -> [getActivity method] Failed: ${e.message}")
                        }
                    }

                    if (!unwrapped) {
                        LogUtils.d("❌ Could not unwrap context, stopping")
                        currentContext = null
                    }
                }
            }
        }

        LogUtils.d("❌ Context unwrapping failed after 5 iterations")
        return null
    }

    /**
     * 获取Context的继承层次结构，用于debug
     */
    private fun getContextHierarchy(context: Context?): String {
        if (context == null) return "null"

        val hierarchy = mutableListOf<String>()
        var current: Any? = context
        var depth = 0
        val maxDepth = 10

        while (current != null && depth < maxDepth) {
            hierarchy.add("${current.javaClass.name}")
            when (current) {
                is android.view.ContextThemeWrapper -> {
                    current = current.baseContext
                }
                is Context -> {
                    // 尝试通过反射获取baseContext
                    try {
                        val baseContextField = current.javaClass.getDeclaredField("baseContext")
                        baseContextField.isAccessible = true
                        val baseCtx = baseContextField.get(current) as? Context
                        if (baseCtx != null && baseCtx != current) {
                            current = baseCtx
                        } else {
                            break
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
                else -> break
            }
            depth++
        }

        return hierarchy.joinToString(" -> ")
    }


    fun <T : Activity> startActivity(from: Context, activity: Class<T>) {
        val i = Intent(from, activity)
        HiltBaseActivity.startWithCrossFade(from, i)
    }

    fun startTimetableManager(from: Context) {
        val i = Intent(from, TimetableManagerActivity::class.java)
        HiltBaseActivity.startWithCrossFade(from, i)
    }


    fun startSubjectActivity(from: Context, id: String) {
        val i = Intent(from, SubjectActivity::class.java)
        i.putExtra("subjectId", id)
        HiltBaseActivity.startWithCrossFade(from, i)
    }

    fun startCourseResourceSearchActivity(
        from: Context,
        query: String? = null,
        mode: CourseResourceMode = CourseResourceMode.VIEW,
    ) {
        val i = Intent(from, UnifiedResourceSearchActivity::class.java)
        i.putExtra("query", query)
        i.putExtra("mode", mode.name)
        HiltBaseActivity.startWithCrossFade(from, i)
    }

    fun startCourseReadmeActivity(
        from: Context,
        repoName: String,
        courseName: String,
        courseCode: String,
        repoType: String = "normal",
    ) {
        val i = Intent(from, CourseReadmeActivity::class.java)
        i.putExtra("repoName", repoName)
        i.putExtra("courseName", courseName)
        i.putExtra("courseCode", courseCode)
        i.putExtra("repoType", repoType)
        HiltBaseActivity.startWithCrossFade(from, i)
    }

    fun startCourseContributionActivity(
        from: Context,
        repoName: String,
        courseName: String,
        courseCode: String,
        repoType: String = "normal",
    ) {
        val i = Intent(from, CourseContributionActivity::class.java)
        i.putExtra("repoName", repoName)
        i.putExtra("courseName", courseName)
        i.putExtra("courseCode", courseCode)
        i.putExtra("repoType", repoType)
        HiltBaseActivity.startWithCrossFade(from, i)
    }

    fun startInternalWebActivity(from: Context, title: String, url: String) {
        val i = Intent(from, InternalWebActivity::class.java)
        i.putExtra("title", title)
        i.putExtra("url", url)
        HiltBaseActivity.startWithCrossFade(from, i)
    }

    fun startTeacherHomepageSearch(from: Context, teacherName: String) {
        if (teacherName.isBlank()) return
        val encodedName = URLEncoder.encode(teacherName, StandardCharsets.UTF_8.toString())
        startInternalWebActivity(
            from = from,
            title = teacherName,
            url = "https://homepage.hit.edu.cn/search-teacher-by-phoneticize?condition=$encodedName",
        )
    }

    fun startTimetableDetailActivity(from: Context, id: String) {
        val i = Intent(from, TimetableDetailActivity::class.java)
        i.putExtra("id", id)
        HiltBaseActivity.startWithCrossFade(from, i)
    }

    fun startProfileActivity(from: Context, userId: String?, imageView: ImageView?=null) {
        val i = Intent(from, ProfileActivity::class.java)
        i.putExtra("id", userId)
        imageView?.let {
            val op = ActivityOptionsCompat.makeSceneTransitionAnimation(from as Activity,it,"useravatar")
            from.startActivity(i,op.toBundle())
        }?:run {
            HiltBaseActivity.startWithCrossFade(from, i)
        }
    }

    fun showUpdateNotificationForce(cr:CheckUpdateResult,activity: AppCompatActivity){
        PopUpText().setText(buildUpdateMarkdown(activity, cr))
            .setTitle(R.string.new_version_available)
            .setOnConfirmListener(object : PopUpText.OnConfirmListener {

                override fun OnConfirm() {
                    downloadAndInstall(activity, cr)
                }
            }).show(activity.supportFragmentManager, "update")
    }


    fun showUpdateNotification(cr:CheckUpdateResult,activity: AppCompatActivity){
       val preference: SharedPreferences =
            activity.application.getSharedPreferences(UPDATE_SKIP_PREF, Context.MODE_PRIVATE)
        val versionKey = cr.latestVersionCode.toString()
        if(preference.getBoolean(versionKey,false)) return
        val dismissedAt = preference.getLong(UPDATE_DISMISSED_PREFIX + versionKey, 0L)
        if (System.currentTimeMillis() - dismissedAt < UPDATE_DISMISS_COOLDOWN_MS) return
        PopUpUpdate().setText(buildUpdateMarkdown(activity, cr))
            .setTitle(R.string.new_version_available)
            .setOnActionListener(object : PopUpUpdate.OnActionListener {
                override fun onConfirm() {
                    downloadAndInstall(activity, cr)
                }

                override fun onCancel() {
                    preference.edit()
                        .putLong(UPDATE_DISMISSED_PREFIX + versionKey, System.currentTimeMillis())
                        .apply()
                }

                override fun onSkip() {
                    preference.edit().putBoolean(versionKey,true).apply()
                }
            }).show(activity.supportFragmentManager, "update")
    }

    private fun buildUpdateMarkdown(activity: AppCompatActivity, cr: CheckUpdateResult): CharSequence {
        val markdown = buildString {
            append("版本：${cr.latestVersionName}\n\n")
            if (BuildConfig.DEBUG && cr.downloadCount > 0) {
                append("累计下载：${cr.downloadCount} 次\n\n")
            }
            append("更新内容：\n\n")
            if (cr.updateLog.isBlank()) {
                append("暂无更新说明")
            } else {
                append(cr.updateLog.trim())
            }
            append("\n\n是否下载安装？")
        }
        return Markwon.builder(activity)
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(TablePlugin.create(activity))
            .usePlugin(TaskListPlugin.create(activity))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(HtmlPlugin.create())
            .build()
            .toMarkdown(markdown)
    }

    private var isDownloading: Boolean = false
    private var downloadProgressDialog: AlertDialog? = null
    private var downloadProgressHandler: Handler? = null

    private fun downloadAndInstall(activity: AppCompatActivity, cr: CheckUpdateResult) {
        if (isDownloading) {
            Toast.makeText(activity, R.string.download_already_in_progress, Toast.LENGTH_SHORT).show()
            return
        }
        isDownloading = true
        try {
            val downloadUrl = cr.downloadUrl.ifEmpty { cr.latestUrl }
            val fileName = "HITA_v${cr.latestVersionName}.apk"
            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle("HITA 更新下载")
                .setDescription("正在下载 v${cr.latestVersionName}")
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = dm.enqueue(request)
            // 下载完成后由 Manifest 静态注册的 ApkInstallReceiver 自动唤起安装；
            // 下载 id 必须持久化，进程被杀后内存里的变量会丢
            ApkInstallReceiver.savePendingDownload(activity, downloadId, fileName)
            showDownloadProgressDialog(activity, dm, downloadId, cr.latestVersionName)
            Toast.makeText(activity, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            isDownloading = false
            ApkInstallReceiver.clearPendingDownload(activity)
            LogUtils.e("Failed to start download", e)
            Toast.makeText(activity, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDownloadProgressDialog(
        activity: AppCompatActivity,
        dm: DownloadManager,
        downloadId: Long,
        versionName: String
    ) {
        dismissDownloadProgressDialog()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ImageUtils.dp2px(activity, 24f))
        }
        val message = TextView(activity).apply {
            text = activity.getString(R.string.update_download_progress_waiting)
            setTextColor(resolveColor(activity, StyleR.attr.textColorPrimary))
        }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        container.addView(
            message,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            progress,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = ImageUtils.dp2px(activity, 16f)
            }
        )
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_download_progress_title, versionName))
            .setView(container)
            .setNegativeButton(R.string.button_cancel) { _, _ ->
                dm.remove(downloadId)
                ApkInstallReceiver.clearPendingDownload(activity)
                isDownloading = false
                dismissDownloadProgressDialog()
            }
            .create()
        dialog.setOnDismissListener {
            if (downloadProgressDialog === dialog) {
                downloadProgressHandler?.removeCallbacksAndMessages(null)
            }
        }
        downloadProgressDialog = dialog
        dialog.show()

        val handler = Handler(Looper.getMainLooper())
        downloadProgressHandler = handler
        val poll = object : Runnable {
            override fun run() {
                if (downloadProgressDialog !== dialog || !dialog.isShowing) return
                val status = updateDownloadProgress(dm, downloadId, progress, message, activity)
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    // 下载完成：ApkInstallReceiver 会自动唤起安装，这里只收尾
                    isDownloading = false
                    dismissDownloadProgressDialog()
                    Toast.makeText(activity, R.string.install_starting, Toast.LENGTH_SHORT).show()
                    return
                }
                if (status == DownloadManager.STATUS_FAILED) {
                    isDownloading = false
                    ApkInstallReceiver.clearPendingDownload(activity)
                    dismissDownloadProgressDialog()
                    Toast.makeText(activity, R.string.download_failed, Toast.LENGTH_SHORT).show()
                    return
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(poll)
    }

    private fun updateDownloadProgress(
        dm: DownloadManager,
        downloadId: Long,
        progress: ProgressBar,
        message: TextView,
        context: Context
    ): Int {
        val query = DownloadManager.Query().setFilterById(downloadId)
        dm.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return DownloadManager.STATUS_FAILED
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            when (status) {
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING -> {
                    if (total > 0) {
                        val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                        progress.isIndeterminate = false
                        progress.progress = percent
                        message.text = context.getString(R.string.update_download_progress_percent, percent)
                    } else {
                        progress.isIndeterminate = true
                        message.text = context.getString(R.string.update_download_progress_waiting)
                    }
                }

                DownloadManager.STATUS_SUCCESSFUL -> {
                    progress.isIndeterminate = false
                    progress.progress = 100
                    message.text = context.getString(R.string.update_download_progress_done)
                }

                DownloadManager.STATUS_FAILED -> {
                    progress.isIndeterminate = false
                    message.text = context.getString(R.string.download_failed)
                    isDownloading = false
                }
            }
            return status
        }
        return DownloadManager.STATUS_FAILED
    }

    private fun dismissDownloadProgressDialog() {
        downloadProgressHandler?.removeCallbacksAndMessages(null)
        downloadProgressHandler = null
        downloadProgressDialog?.let {
            if (it.isShowing) it.dismiss()
        }
        downloadProgressDialog = null
    }

    private fun resolveColor(context: Context, attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    fun startNewsActivity(from: Context, url: String, title: String) {
        val i = Intent(from, NewsDetailActivity::class.java)
        i.putExtra("link", url)
        i.putExtra("title", title)
        i.putExtra("mode", "hitsz_news")
        HiltBaseActivity.startWithCrossFade(from, i)
    }
}
