package cn.limpu.hita.ui.main

import android.app.Application
import android.content.Context
import android.net.Uri
import cn.limpu.hita.data.repository.KEY_WALLPAPER_PATH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class WallpaperStore @Inject constructor(application: Application) {
    private val context = application.applicationContext
    private val preference = context.getSharedPreferences("timetable_style", Context.MODE_PRIVATE)
    private val files = WallpaperFiles(context.filesDir, object : WallpaperPath {
        override fun read() = preference.getString(KEY_WALLPAPER_PATH, "").orEmpty()
        override fun write(path: String) {
            val previous = read()
            if (!preference.edit().putString(KEY_WALLPAPER_PATH, path).commit()) {
                preference.edit().putString(KEY_WALLPAPER_PATH, previous).commit()
                error("Cannot persist wallpaper selection")
            }
        }
    })

    // Allocate before dispatching: thread scheduling cannot reverse selection order.
    fun beginChange(): Long = files.beginChange()

    suspend fun replace(request: Long, uri: Uri): WallpaperWriteResult = withContext(Dispatchers.IO) {
        files.replace(request, { context.contentResolver.openInputStream(uri) }) { ensureActive() }
    }

    suspend fun remove(request: Long): WallpaperWriteResult = withContext(Dispatchers.IO) {
        ensureActive()
        files.remove(request)
    }

    suspend fun clearMissingSelection(path: String): Boolean = withContext(Dispatchers.IO) {
        files.clearMissingSelection(path)
    }
}
