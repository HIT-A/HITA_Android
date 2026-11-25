package cn.limpu.hita.ui.resource

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.model.resource.ResourceSource
import cn.limpu.hita.data.repository.EasRepositoryWiringEntryPoint
import cn.limpu.hita.data.repository.ExternalResourceRepository
import cn.limpu.hita.data.repository.HoaRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnifiedResourceSearchInstrumentedTest {
    @Test fun savedNestedDirectoryTakesPrecedenceOverTheLaunchIntent() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val eas = EntryPointAccessors.fromApplication(app, EasRepositoryWiringEntryPoint::class.java).easRepository()
        val saved = SavedStateHandle()
        val source = ResourceSource.FIREWORKS // Directory entries are local links; no remote request.

        withContext(Dispatchers.Main) {
            val original = UnifiedResourceSearchViewModel(HoaRepository(), ExternalResourceRepository(), eas, saved)
            original.browse("fixture-root", source)
            original.browse("fixture-root/fixture-child", source)

            val restored = UnifiedResourceSearchViewModel(HoaRepository(), ExternalResourceRepository(), eas, saved)
            restored.restore("", ResourceFolder(ResourceFolderKey(source, "old-launch-target"), "old"))
            val nested = withTimeout(5_000) {
                restored.state.first { it.directory?.loading == false }
            }
            assertEquals(listOf("fixture-root", "fixture-root/fixture-child"), nested.stack.map { it.key.path })
            assertEquals("fixture-root/fixture-child", nested.directory?.key?.path)
            assertEquals(source, nested.directory?.entries?.single()?.source)

            restored.back()
            val parent = withTimeout(5_000) {
                restored.state.first { it.directory?.loading == false && it.directory.key.path == "fixture-root" }
            }
            assertEquals(listOf("fixture-root"), parent.stack.map { it.key.path })
            assertFalse(restored.back())
            restored.exitBrowse()
            assertNull(restored.state.value.directory)
        }
    }
}
