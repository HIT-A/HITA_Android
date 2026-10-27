package cn.limpu.hita.agent.attachment

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

class ChatAttachmentTooLargeException(val maxBytes: Int) : IOException("Attachment exceeds $maxBytes bytes")

class CachedChatAttachment(val file: File, val byteCount: Int) : Closeable {
    override fun close() {
        file.delete()
    }
}

/** Owns the input stream. Size comes from bytes actually read, never available(). */
fun cacheChatAttachment(
    input: InputStream,
    directory: File,
    maxBytes: Int,
    checkCancelled: () -> Unit = {},
): CachedChatAttachment {
    var temporaryFile: File? = null
    var count = 0L
    try {
        input.use { source ->
            checkCancelled()
            require(maxBytes >= 0)
            val file = File.createTempFile("hita-chat-attachment-", ".tmp", directory)
            temporaryFile = file
            file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    checkCancelled()
                    // Read at most one byte beyond the limit, then reject without writing it.
                    val requested = minOf(buffer.size.toLong(), maxBytes.toLong() - count + 1L).toInt()
                    val read = source.read(buffer, 0, requested)
                    checkCancelled()
                    if (read < 0) break
                    if (read == 0) {
                        // Some content providers temporarily return zero for a bulk read.
                        val singleByte = source.read()
                        checkCancelled()
                        if (singleByte < 0) break
                        count++
                        if (count > maxBytes) throw ChatAttachmentTooLargeException(maxBytes)
                        output.write(singleByte)
                    } else {
                        count += read
                        if (count > maxBytes) throw ChatAttachmentTooLargeException(maxBytes)
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        checkCancelled()
        return CachedChatAttachment(requireNotNull(temporaryFile), count.toInt())
    } catch (error: Throwable) {
        // Also handles a provider throwing on close after an otherwise successful copy.
        temporaryFile?.delete()
        throw error
    }
}
