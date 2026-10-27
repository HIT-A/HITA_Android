package cn.limpu.hita.ui.main.agent

import android.content.Context
import cn.limpu.hita.R
import cn.limpu.hita.agent.attachment.ChatAttachmentResult

internal fun attachmentFailureText(context: Context, failure: ChatAttachmentResult.Failure): String = when (failure.kind) {
    ChatAttachmentResult.Kind.EMPTY -> context.getString(R.string.chat_attachment_empty)
    ChatAttachmentResult.Kind.TOO_LARGE -> context.getString(R.string.chat_attachment_too_large, failure.maxBytes / (1024 * 1024))
    ChatAttachmentResult.Kind.UNSUPPORTED -> context.getString(R.string.chat_attachment_unsupported, failure.fileName)
    ChatAttachmentResult.Kind.EXPANDED_LIMIT -> context.getString(R.string.chat_attachment_expanded_limit)
    ChatAttachmentResult.Kind.READ -> context.getString(R.string.chat_attachment_read_failed, failure.detail.orEmpty())
    ChatAttachmentResult.Kind.MEMORY -> when {
        failure.fileName.endsWith(".pdf", true) -> context.getString(R.string.chat_attachment_parse_failed, context.getString(R.string.chat_attachment_pdf_memory))
        listOf("docx", "xlsx", "pptx").any { failure.fileName.endsWith(".$it", true) } ->
            context.getString(R.string.chat_attachment_parse_failed, context.getString(R.string.chat_attachment_document_memory))
        listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "mp4", "mov", "avi", "mkv", "webm").any { failure.fileName.endsWith(".$it", true) } ->
            context.getString(R.string.chat_attachment_media_memory)
        else -> context.getString(R.string.chat_attachment_memory)
    }
    ChatAttachmentResult.Kind.PARSE -> {
        val detail = when {
            failure.fileName.endsWith(".pdf", true) -> context.getString(R.string.chat_attachment_pdf_error, failure.detail.orEmpty())
            failure.fileName.endsWith(".docx", true) -> context.getString(R.string.chat_attachment_docx_error, failure.detail.orEmpty())
            else -> context.getString(R.string.chat_attachment_document_error, failure.detail.orEmpty())
        }
        context.getString(R.string.chat_attachment_parse_failed, detail)
    }
}
