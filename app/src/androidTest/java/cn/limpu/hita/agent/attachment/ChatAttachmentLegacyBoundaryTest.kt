package cn.limpu.hita.agent.attachment

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.LegacyEasRepositoryDependencies
import cn.limpu.hita.data.repository.TimetableChangeStore
import cn.limpu.hita.data.repository.TimetableMutationLock
import cn.limpu.hita.data.source.preference.EasCredentialStore
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import cn.limpu.hita.ui.main.agent.AgentChatViewModel
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.jvm.functions.Function0
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only the entry adapter differs: both baselines invoke the real production MIME/parser code. */
@RunWith(AndroidJUnit4::class)
class ChatAttachmentLegacyBoundaryTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val legacyViewModel by lazy {
        val preferences = EasPreferenceSource(application)
        val dependencies = LegacyEasRepositoryDependencies.from(application)
        AgentChatViewModel::class.java.getDeclaredConstructor(Application::class.java, EASRepository::class.java)
            .newInstance(
                application,
                EASRepository(
                    application,
                    preferences,
                    EasCredentialStore(application),
                    TimetablePreferenceSource(application, preferences),
                    TimetableChangeStore(application),
                    TimetableMutationLock(),
                    dependencies.database,
                    dependencies.serviceFactories,
                ),
            )
    }

    @Test fun advertisedBitmapAndVideoFormatsMustReachTheirMediaRoute() {
        val expected = mapOf("bmp" to "image/bmp", "avi" to "video/x-msvideo", "mkv" to "video/x-matroska", "webm" to "video/webm")
        for ((extension, mime) in expected) {
            val actual = try {
                Class.forName("cn.limpu.hita.agent.attachment.ChatAttachmentProcessorKt")
                    .getDeclaredMethod("attachmentMimeType", String::class.java).invoke(null, "fixture.$extension")
            } catch (_: ClassNotFoundException) {
                AgentChatViewModel::class.java.getDeclaredMethod("getMimeType", String::class.java)
                    .apply { isAccessible = true }.invoke(legacyViewModel, "fixture.$extension")
            }
            assertEquals("Advertised $extension must have a media MIME type", mime, actual)
        }
    }

    @Test fun docxMustRejectOversizedExpandedXmlBeforeBuildingTheDocumentString() {
        val file = File.createTempFile("chat-expanded-baseline-", ".docx", application.cacheDir)
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:t>""".toByteArray())
                val block = ByteArray(8192) { 120 }
                repeat(1025) { zip.write(block) }
                zip.write("</w:t></w:document>".toByteArray())
                zip.closeEntry()
            }
            var failure: Throwable? = null
            try {
                val parserClass = try { Class.forName("cn.limpu.hita.agent.attachment.DocumentTextParser") } catch (_: ClassNotFoundException) { null }
                if (parserClass != null) {
                    val method = parserClass.declaredMethods.single {
                        it.name.startsWith("parseDocx") &&
                            !it.name.contains("default") &&
                            !it.isSynthetic &&
                            it.parameterTypes.contentEquals(arrayOf(File::class.java, Function0::class.java))
                    }
                    method.isAccessible = true
                    method.invoke(parserClass.getDeclaredConstructor().newInstance(), file, {})
                } else {
                    AgentChatViewModel::class.java.getDeclaredMethod("extractDocxTextDirectly", File::class.java)
                        .apply { isAccessible = true }.invoke(legacyViewModel, file)
                }
            } catch (error: InvocationTargetException) { failure = error.targetException }
            assertTrue("DOCX expanded limit must be checked before parsing", failure?.javaClass?.simpleName == "AttachmentExpandedLimitException")
        } finally { file.delete() }
    }
}
