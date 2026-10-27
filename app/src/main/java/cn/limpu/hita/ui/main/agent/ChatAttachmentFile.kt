package cn.limpu.hita.ui.main.agent

import java.io.File
import java.io.InputStream

// Compatibility names retain the original byte-cap regression tests.
internal typealias ChatAttachmentTooLargeException = cn.limpu.hita.agent.attachment.ChatAttachmentTooLargeException
internal typealias CachedChatAttachment = cn.limpu.hita.agent.attachment.CachedChatAttachment

internal fun cacheChatAttachment(input: InputStream, directory: File, maxBytes: Int): CachedChatAttachment =
    cn.limpu.hita.agent.attachment.cacheChatAttachment(input, directory, maxBytes)
