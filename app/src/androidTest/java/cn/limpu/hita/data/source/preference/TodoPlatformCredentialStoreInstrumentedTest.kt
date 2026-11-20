package cn.limpu.hita.data.source.preference

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodoPlatformCredentialStoreInstrumentedTest {
    @Test fun restartsRecoverSeparateDeviceCredentialsAndForgetOnlyOnePlatform() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Application>()
        val prefix = "todo-credentials-${UUID.randomUUID()}"
        val isolated = object : Application() {
            init { attachBaseContext(base) }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("$prefix-$name", mode)
        }
        val fileName = "$prefix-todo_platform_credentials_v1"
        val grader = TodoLoginCredential("same-login", "grader-test-password", "id-grader")
        val genius = TodoLoginCredential("same-login", "genius-test-password", "id-genius")
        try {
            val store = TodoPlatformCredentialStore(isolated)
            assertTrue(store.save(TodoLoginProvider.GRADER, grader))
            assertTrue(store.save(TodoLoginProvider.GENIUSTA, genius))
            val raw = base.getSharedPreferences(fileName, Context.MODE_PRIVATE).all
            assertTrue(raw.isNotEmpty())
            assertFalse(raw.values.any { it == grader.password || it == genius.password || it == grader.username })

            val reopened = TodoPlatformCredentialStore(isolated)
            assertEquals(grader.password, reopened.read(TodoLoginProvider.GRADER)?.password)
            assertEquals(genius.accountId, reopened.read(TodoLoginProvider.GENIUSTA)?.accountId)
            assertTrue(reopened.forget(TodoLoginProvider.GRADER))

            val afterForget = TodoPlatformCredentialStore(isolated)
            assertNull(afterForget.read(TodoLoginProvider.GRADER))
            assertEquals(genius.password, afterForget.read(TodoLoginProvider.GENIUSTA)?.password)
        } finally {
            base.deleteSharedPreferences(fileName)
        }
    }
}
