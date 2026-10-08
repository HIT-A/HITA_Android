package cn.limpu.hita.di

import android.content.Context
import cn.limpu.hita.data.source.preference.*
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
