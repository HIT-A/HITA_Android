package cn.limpu.hita.data.repository

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.source.dao.EventItemDao
import cn.limpu.hita.data.source.dao.ScoreCacheDao
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.EntryPoint
import dagger.hilt.components.SingletonComponent
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface EasRepositoryWiringEntryPoint {
    fun easRepository(): EASRepository
    fun appDatabase(): AppDatabase
    fun eventItemDao(): EventItemDao
    fun scoreCacheDao(): ScoreCacheDao
    fun timetableSnapshotStore(): TimetableSnapshotStore
    fun timetableMutationLock(): TimetableMutationLock
}

@RunWith(AndroidJUnit4::class)
class EasRepositoryWiringInstrumentedTest {
    @Test
    fun repositoryAndStorageDependenciesComeFromOneApplicationGraph() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val first = EntryPointAccessors.fromApplication(
            application, EasRepositoryWiringEntryPoint::class.java,
        )
        val second = EntryPointAccessors.fromApplication(
            application, EasRepositoryWiringEntryPoint::class.java,
        )

        assertSame(first.easRepository(), second.easRepository())
        assertSame(first.appDatabase(), second.appDatabase())
        assertSame(first.eventItemDao(), second.eventItemDao())
        assertSame(first.scoreCacheDao(), second.scoreCacheDao())
        assertSame(first.timetableSnapshotStore(), second.timetableSnapshotStore())
        assertSame(first.timetableMutationLock(), second.timetableMutationLock())
    }
}
