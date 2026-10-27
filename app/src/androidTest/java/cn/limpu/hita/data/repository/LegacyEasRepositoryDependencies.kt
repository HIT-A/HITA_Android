package cn.limpu.hita.data.repository

import android.app.Application
import cn.limpu.hita.data.AppDatabase

/** Explicit database/service wiring for tests that intentionally bypass Hilt. */
internal data class LegacyEasRepositoryDependencies(
    val database: AppDatabase,
    val serviceFactories: EasRepositoryServiceFactories,
) {
    companion object {
        fun from(application: Application): LegacyEasRepositoryDependencies =
            LegacyEasRepositoryDependencies(AppDatabase.getDatabase(application), EasRepositoryServiceFactories())
    }
}
