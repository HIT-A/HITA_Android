package cn.limpu.hita.ui.subject

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import cn.limpu.hita.R
import cn.limpu.hita.data.model.resource.ExternalResourceEntry
import cn.limpu.hita.ui.resource.MarkdownViewerActivity

/** Platform effects are kept at the Activity boundary, outside resource request state. */
internal class SubjectResourceLauncher(private val context: Context) {
    fun openLink(url: String) {
        runCatching { browser(url) }
    }

    fun open(entry: ExternalResourceEntry) {
        when (val target = SubjectResourceOpenPolicy.destination(entry)) {
            is SubjectResourceDestination.Browser -> openLink(target.url)
            is SubjectResourceDestination.Markdown -> context.startActivity(Intent(context, MarkdownViewerActivity::class.java).apply {
                putExtra("url", target.url)
                putExtra("title", target.title)
            })
            is SubjectResourceDestination.Download -> download(target.url, target.name)
            null -> Unit
        }
    }

    private fun browser(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun download(url: String, name: String) {
        try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setDescription(context.getString(R.string.subject_resource_downloading, name))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            manager.enqueue(request)
            Toast.makeText(context, context.getString(R.string.subject_resource_download_started, name), Toast.LENGTH_SHORT).show()
        } catch (failure: Exception) {
            runCatching { browser(url) }.onFailure {
                Toast.makeText(context, context.getString(R.string.subject_resource_download_failed, failure.message), Toast.LENGTH_LONG).show()
            }
        }
    }
}
