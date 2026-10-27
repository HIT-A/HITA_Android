package cn.limpu.hita.di

import android.content.Context
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.repository.DefaultTimetableImportGrouping
import cn.limpu.hita.data.repository.EasRepositoryServiceFactories
import cn.limpu.hita.data.repository.TimetableImportGrouping
import cn.limpu.hita.data.repository.TimetableSnapshotStore
import cn.limpu.hita.data.repository.DisplayScopeSelector
import cn.limpu.hita.feature.timetable.display.DisplayScopeCoordinator
import cn.limpu.hita.data.source.preference.*
import cn.limpu.hita.data.source.dao.ClassroomCacheDao
import cn.limpu.hita.data.source.dao.EventItemDao
import cn.limpu.hita.data.source.dao.ScoreCacheDao
import cn.limpu.hita.data.source.dao.SubjectDao
import cn.limpu.hita.data.source.dao.TimetableDao
import cn.limpu.hita.data.source.dao.TimetableGroupDao
import com.google.gson.Gson
import cn.limpu.hita.data.source.web.eas.BenbuEASWebSource
import cn.limpu.hita.data.source.web.eas.EASWebSource
import cn.limpu.hita.data.source.web.eas.WeihaiEASWebSource
import cn.limpu.hita.data.source.web.GitHubWebSource
import cn.limpu.hita.data.source.web.StaticWebSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    internal fun provideEventItemDao(database: AppDatabase): EventItemDao = database.eventItemDao()

    @Provides
    @Singleton
    internal fun provideTimetableDao(database: AppDatabase): TimetableDao = database.timetableDao()

    @Provides
    @Singleton
    internal fun provideSubjectDao(database: AppDatabase): SubjectDao = database.subjectDao()

    @Provides
    @Singleton
    internal fun provideClassroomCacheDao(database: AppDatabase): ClassroomCacheDao = database.classroomCacheDao()

    @Provides
    @Singleton
    internal fun provideScoreCacheDao(database: AppDatabase): ScoreCacheDao = database.scoreCacheDao()

    @Provides
    @Singleton
    internal fun provideTimetableGroupDao(database: AppDatabase): TimetableGroupDao = database.timetableGroupDao()

    @Provides
    @Singleton
    internal fun provideGson(): Gson = Gson()

    @Provides
    @Singleton
    internal fun provideTimetableSnapshotStore(
        @ApplicationContext context: Context,
        database: AppDatabase,
    ): TimetableSnapshotStore = TimetableSnapshotStore(context, database)

    @Provides
    @Singleton
    internal fun provideTimetableImportGrouping(
        implementation: DefaultTimetableImportGrouping,
    ): TimetableImportGrouping = implementation

    @Provides
    @Singleton
    internal fun provideEasRepositoryServiceFactories(): EasRepositoryServiceFactories =
        EasRepositoryServiceFactories(
            shenzhen = { onTokenRefreshed, credentialProvider ->
                EASWebSource(
                    onTokenRefreshed = onTokenRefreshed,
                    credentialProvider = credentialProvider,
                )
            },
            benbu = { onTokenRefreshed -> BenbuEASWebSource(onTokenRefreshed) },
            weihai = { onTokenRefreshed -> WeihaiEASWebSource(onTokenRefreshed) },
        )

    @Provides
    fun provideDisplayScopeSelector(coordinator: DisplayScopeCoordinator): DisplayScopeSelector = coordinator

    @Provides
    @Singleton
    fun provideScoreReminderStore(@ApplicationContext context: Context): ScoreReminderStore = ScoreReminderStore(context)

    @Provides
    @Singleton
    fun provideCourseReminderStore(@ApplicationContext context: Context): CourseReminderStore = CourseReminderStore(context)

    @Provides
    @Singleton
    fun provideCreditGoalStore(@ApplicationContext context: Context): CreditGoalStore = CreditGoalStore(context)

    @Provides
    @Singleton
    fun provideTimetablePreferenceSource(
        @ApplicationContext context: Context,
        easPreferenceSource: EasPreferenceSource
    ): TimetablePreferenceSource = TimetablePreferenceSource(context, easPreferenceSource)

    @Provides
    @Singleton
    fun provideEasPreferenceSource(@ApplicationContext context: Context): EasPreferenceSource = EasPreferenceSource(context)

    @Provides
    @Singleton
    fun provideEasCredentialStore(@ApplicationContext context: Context): EasCredentialStore =
        EasCredentialStore(context)

    @Provides
    @Singleton
    fun provideBenbuStartDatePreferenceSource(@ApplicationContext context: Context): BenbuStartDatePreferenceSource = BenbuStartDatePreferenceSource(context)

    @Provides
    @Singleton
    fun provideStaticWebSource(@ApplicationContext context: Context): StaticWebSource = StaticWebSource(context)

    @Provides
    @Singleton
    fun provideGitHubWebSource(@ApplicationContext context: Context): GitHubWebSource = GitHubWebSource(context)
}
