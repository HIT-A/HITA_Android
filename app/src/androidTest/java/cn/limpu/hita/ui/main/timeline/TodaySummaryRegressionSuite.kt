package cn.limpu.hita.ui.main.timeline

import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import cn.limpu.hita.data.repository.AutomaticExamCommitInstrumentedTest
import cn.limpu.hita.data.repository.ExamImportRepositoryInstrumentedTest
import cn.limpu.hita.data.repository.ExamReadRepositoryInstrumentedTest
import cn.limpu.hita.data.source.web.eas.ExamSuspendContractTest
import cn.limpu.hita.data.source.web.eas.LegacyExamQueryContractTest
import cn.limpu.hita.data.source.web.eas.ShenzhenExamQueryContractTest
import cn.limpu.hita.ui.main.homework.HomeworkUiInstrumentedTest
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Suite

/** Explicit nested suite: default test discovery skips nested classes, avoiding duplicate runs. */
object TodaySummaryRegressionSuite {
    @RunWith(Suite::class)
    @Suite.SuiteClasses(
        ExamReadRepositoryInstrumentedTest::class,
        AutomaticExamCommitInstrumentedTest::class,
        ExamImportRepositoryInstrumentedTest::class,
        ExamSuspendContractTest::class,
        LegacyExamQueryContractTest::class,
        ShenzhenExamQueryContractTest::class,
        HomeworkUiInstrumentedTest::class,
    )
    class Foreground {
        companion object {
            private var host: ActivityScenario<ComponentActivity>? = null
            @BeforeClass @JvmStatic fun showIsolatedHost() {
                host = ActivityScenario.launch(ComponentActivity::class.java)
            }
            @AfterClass @JvmStatic fun closeIsolatedHost() {
                host?.close()
                host = null
            }
        }
    }
}
