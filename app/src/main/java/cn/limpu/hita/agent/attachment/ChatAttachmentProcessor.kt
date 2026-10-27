package cn.limpu.hita.agent.attachment

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface ChatAttachmentInput {
    val displayName: String
    fun open(): InputStream
}

sealed interface ChatAttachmentResult {
    data class Text(val fileName: String, val content: String) : ChatAttachmentResult
    data class Media(val fileName: String, val mimeType: String, val base64: String) : ChatAttachmentResult
    data class Failure(
        val kind: Kind,
        val fileName: String = "",
        val detail: String? = null,
        val maxBytes: Int = 0,
    ) : ChatAttachmentResult
    enum class Kind { EMPTY, TOO_LARGE, UNSUPPORTED, PARSE, EXPANDED_LIMIT, READ, MEMORY }
}

/** Owns every stream and cached file, while URI access and document libraries have separate adapters. */
class ChatAttachmentProcessor(
    private val directory: File,
    private val parser: DocumentTextParser,
    private val encodeMedia: (ByteArray) -> String,
) {
    suspend fun process(input: ChatAttachmentInput): ChatAttachmentResult = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val name = input.displayName
        val mime = attachmentMimeType(name)
        val isMedia = mime.startsWith("image/") || mime.startsWith("video/")
        val maximum = if (isMedia) MEDIA_MAX_BYTES else DOCUMENT_MAX_BYTES
        try {
            context.ensureActive()
            cacheChatAttachment(input.open(), directory, maximum) { context.ensureActive() }.use { cached ->
                context.ensureActive()
                if (cached.byteCount == 0) return@withContext ChatAttachmentResult.Failure(ChatAttachmentResult.Kind.EMPTY, name)
                val kind = documentKind(name, cached.byteCount)
                if (kind != null) {
                    val text = parser.parse(cached.file, kind) { context.ensureActive() }
                    context.ensureActive()
                    ChatAttachmentResult.Text(name, text)
                } else if (isMedia) {
                    val bytes = cached.file.readBytes() // The cache has already enforced the actual 10 MiB cap.
                    context.ensureActive()
                    val content = encodeMedia(bytes)
                    context.ensureActive()
                    ChatAttachmentResult.Media(name, mime, content)
                } else ChatAttachmentResult.Failure(ChatAttachmentResult.Kind.UNSUPPORTED, name)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (limit: ChatAttachmentTooLargeException) {
            context.ensureActive()
            ChatAttachmentResult.Failure(ChatAttachmentResult.Kind.TOO_LARGE, name, maxBytes = limit.maxBytes)
        } catch (limit: AttachmentExpandedLimitException) {
            context.ensureActive()
            ChatAttachmentResult.Failure(ChatAttachmentResult.Kind.EXPANDED_LIMIT, name)
        } catch (error: AttachmentParseException) {
            context.ensureActive()
            ChatAttachmentResult.Failure(ChatAttachmentResult.Kind.PARSE, name, error.cause?.message ?: error.message)
        } catch (error: OutOfMemoryError) {
            context.ensureActive()
            ChatAttachmentResult.Failure(ChatAttachmentResult.Kind.MEMORY, name)
        } catch (error: Exception) {
            context.ensureActive()
            ChatAttachmentResult.Failure(ChatAttachmentResult.Kind.READ, name, error.message)
        }
    }

    companion object {
        const val MEDIA_MAX_BYTES = 10 * 1024 * 1024
        const val DOCUMENT_MAX_BYTES = 20 * 1024 * 1024
    }
}

internal enum class DocumentKind { TEXT, PDF, DOCX, XLSX, PPTX }

internal fun documentKind(name: String, size: Int): DocumentKind? = when {
    size < 100 * 1024 && listOf("txt", "md", "json", "xml", "csv", "html", "htm").any { name.endsWith(".$it", true) } -> DocumentKind.TEXT
    name.endsWith(".pdf", true) -> DocumentKind.PDF
    name.endsWith(".docx", true) -> DocumentKind.DOCX
    name.endsWith(".xlsx", true) -> DocumentKind.XLSX
    name.endsWith(".pptx", true) -> DocumentKind.PPTX
    else -> null
}

internal fun attachmentMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "mp4" -> "video/mp4"
    "mov" -> "video/quicktime"
    "avi" -> "video/x-msvideo"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    else -> "application/octet-stream"
}
