package cn.limpu.hita.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 迁移链必须从 1 连续到当前版本：只升版本号、漏写迁移时在 JVM 测试即失败，不依赖破坏性回退。 */
class AppDatabaseMigrationChainTest {
    @Test fun migrationsCoverEveryVersionStepUpToCurrent() {
        val steps = AppDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals((1 until AppDatabase.VERSION).map { it to it + 1 }, steps)
    }
}
