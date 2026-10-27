package cn.limpu.hita.agent.attachment

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface ChatAttachmentLoader {
    suspend fun load(uri: Uri): ChatAttachmentResult
}

class AndroidChatAttachmentSource @Inject constructor(private val application: Application) {
    fun input(uri: Uri): ChatAttachmentInput {
        val name = fileName(uri)
        return object : ChatAttachmentInput {
            override val displayName = name
            override fun open() = application.contentResolver.openInputStream(uri)
                ?: throw IOException("无法打开文件，请重试")
        }
    }

    private fun fileName(uri: Uri): String {
        val pathName = uri.path?.substringAfterLast('/')
        if (pathName != null && pathName.contains('.') && !pathName.endsWith(".mht", true)) return pathName
        application.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) return normalizeChatAttachmentName(cursor.getString(index))
            }
        }
        return uri.lastPathSegment ?: "文件"
    }
}

internal fun normalizeChatAttachmentName(displayName: String): String {
    if (displayName.endsWith(".mht", true)) {
        val pdf = displayName.indexOf(".pdf", ignoreCase = true)
        if (pdf > 0) return displayName.substring(0, pdf + 4)
    }
    return displayName
}

class AndroidChatAttachmentLoader @Inject constructor(
    private val source: AndroidChatAttachmentSource,
    private val processor: ChatAttachmentProcessor,
) : ChatAttachmentLoader {
    override suspend fun load(uri: Uri): ChatAttachmentResult = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val input = source.input(uri)
        currentCoroutineContext().ensureActive()
        processor.process(input)
    }
}
