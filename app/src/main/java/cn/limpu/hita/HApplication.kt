package cn.limpu.hita

import android.app.Application
import com.google.android.material.color.DynamicColors
import cn.limpu.hita.utils.LogUtils
import dagger.hilt.android.HiltAndroidApp
import androidx.annotation.WorkerThread
import com.google.gson.Gson
import com.google.gson.JsonObject
import cn.limpu.hita.data.AppDatabase
import androidx.room.InvalidationTracker
import cn.limpu.hita.data.model.GsonBuilderUtil
import cn.limpu.hita.data.model.timetable.EventItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.Timetable
import com.limpu.hitauser.data.repository.LocalUserRepository
import javax.inject.Inject
import cn.limpu.hita.agent.remote.AgentBackendClient
import cn.limpu.hita.data.analytics.UsageAnalyticsClient
import cn.limpu.hita.data.work.CourseReminderScheduler
import cn.limpu.hita.data.work.WidgetRefreshScheduler
import cn.limpu.hita.feature.livecourse.scheduler.LiveCourseScheduler
import cn.limpu.hita.ui.widgets.WidgetUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@HiltAndroidApp
class HApplication : Application() {

    @Inject
    lateinit var localUserRepository: LocalUserRepository

    // 应用级别的协程作用域，生命周期与应用一致
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // 启用 Material You 动态配色（Android 12+ 自动跟随系统壁纸颜色）
        DynamicColors.applyToActivitiesIfAvailable(this)

        // PDFBox uses the assets bundled in its AAR; initialize before any parser can run.
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)

        try {
            applicationScope.launch(Dispatchers.IO) {
                try {
                    val database = AppDatabase.getDatabase(this@HApplication)
                    database.timetableDao()
                    database.subjectDao()
                    database.eventItemDao()
                    // 一次性回填已导入课表中作废的作息结构（幂等）。
                    try {
                        cn.limpu.hita.data.repository.TimetableScheduleStructureMigration.run(
                            this@HApplication,
                            database.timetableDao()
                        )
                    } catch (e: Exception) {
                        LogUtils.e("Timetable schedule structure migration failed", e)
                    }
                    database.invalidationTracker.addObserver(object : InvalidationTracker.Observer("events") {
                        override fun onInvalidated(tables: Set<String>) {
                            applicationScope.launch(Dispatchers.IO) {
                                try {
                                    LiveCourseScheduler.autoSchedule(this@HApplication)
                                } catch (e: Exception) {
                                    LogUtils.e("Live Course timetable reschedule failed", e)
                                }
                            }
                        }
                    })
                } catch (e: Exception) {
                    LogUtils.e("Database initialization failed", e)
                }
            }
        } catch (e: Exception) {
            LogUtils.e("Failed to launch database initialization", e)
        }

        // 注意：在生产环境中应该移除或修改SSL设置
        // handleSSLHandshake()

        // 初始化课程提醒（根据用户设置自动调度或取消）
        try {
            CourseReminderScheduler.autoSchedule(this)
        } catch (e: Exception) {
            LogUtils.e("CourseReminderScheduler.autoSchedule failed", e)
        }

        try {
            LiveCourseScheduler.autoSchedule(this)
        } catch (e: Exception) {
            LogUtils.e("LiveCourseScheduler.autoSchedule failed", e)
        }

        try {
            if (WidgetUtils.hasAnyWidget(this)) {
                WidgetRefreshScheduler.schedule(this)
            }
        } catch (e: Exception) {
            LogUtils.e("WidgetRefreshScheduler.schedule failed", e)
        }

        try {
            UsageAnalyticsClient.initialize(this)
        } catch (e: Exception) {
            LogUtils.e("UsageAnalytics init failed", e)
        }
    }

    /**
     * 设置SSL握手处理
     *
     * ⚠️ 警告：此方法信任所有SSL证书，仅用于开发/调试环境。
     * 在生产环境中使用此配置会使应用容易受到中间人攻击。
     * 建议：在生产环境中移除此方法，使用正确的SSL证书验证。
     */
    @Suppress("DEPRECATION", "UNUSED")
    private fun handleSSLHandshake() {
        try {
            // 注意：信任所有证书是不安全的，仅用于特定的校园网络环境
            // 在正式环境中应该使用正确的证书验证
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate?> {
                    return arrayOfNulls(0)
                }

                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            })
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, trustAllCerts, SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            LogUtils.e( "SSL握手设置失败", e)
        }
    }

}
