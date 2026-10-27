package cn.limpu.hita.ui.main

import android.app.Application
import android.net.Uri
import cn.limpu.hita.BuildConfig
import cn.limpu.hita.data.notice.AppNotice
import cn.limpu.hita.data.notice.AppNoticeCenter
import cn.limpu.hita.data.repository.AutoImportResult
import cn.limpu.hita.data.repository.BlogRepository
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.UpdateRepository
import com.limpu.component.data.DataState
import com.limpu.hitauser.data.model.CheckUpdateResult
import com.limpu.hitauser.data.repository.LocalUserRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** Finite requests only. Their adapters retain application dependencies, never a screen. */
internal interface MainRequests {
    fun syncBlog()
    suspend fun importTimetable(undergraduate: Boolean): AutoImportResult
    suspend fun checkUpdate(versionCode: Long): DataState<CheckUpdateResult>
    suspend fun notices(): List<AppNotice>
    suspend fun saveAvatar(uri: Uri): Boolean
}

internal class MainPlatformRequests @Inject constructor(
    private val application: Application,
    private val eas: EASRepository,
    private val updates: UpdateRepository,
    private val users: LocalUserRepository,
    private val blog: BlogRepository,
) : MainRequests {
    override fun syncBlog() = blog.syncOnAppOpen()
    override suspend fun importTimetable(undergraduate: Boolean): AutoImportResult =
        awaitMainCallback { callback -> eas.startAutoImportCurrentTimetable(undergraduate, callback) }

    override suspend fun checkUpdate(versionCode: Long): DataState<CheckUpdateResult> =
        updates.checkUpdateWithFallback(
            BuildConfig.VERSION_NAME, versionCode, BuildConfig.UPDATE_VERSION_NAME,
            BuildConfig.UPDATE_VERSION_CODE, BuildConfig.UPDATE_URL, BuildConfig.UPDATE_LOG,
            BuildConfig.UPDATE_ALLOW_PRERELEASE,
        ).awaitMainValue()

    override suspend fun notices(): List<AppNotice> =
        awaitMainCallback { callback -> AppNoticeCenter.fetch(application, callback) }

    override suspend fun saveAvatar(uri: Uri): Boolean =
        awaitMainCallback { callback -> users.saveLocalAvatar(uri, application, callback) }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class MainRequestsModule {
    @Binds abstract fun requests(implementation: MainPlatformRequests): MainRequests
}
