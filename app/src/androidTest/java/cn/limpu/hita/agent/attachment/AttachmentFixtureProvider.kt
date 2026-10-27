package cn.limpu.hita.agent.attachment

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** A synthetic test APK provider: reported size is deliberately different from streamed content. */
class AttachmentFixtureProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "text/plain"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf(if (uri.lastPathSegment == "download") "课程.PDF.download.MHT" else "notes.txt", 0L))
        }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = File.createTempFile("chat-provider-fixture-", ".txt", requireNotNull(context).cacheDir)
        file.writeText("hello local fixture")
        return try { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) } finally { file.delete() }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only fixture")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only fixture")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only fixture")
}
