package cn.limpu.hita.ui.main

import java.io.File
import java.io.InputStream
import java.util.UUID

internal interface WallpaperPath {
    fun read(): String
    /** Publish durably before retiring the previous file. Throw on failure. */
    fun write(path: String)
}

internal enum class WallpaperWriteResult { SAVED, SUPERSEDED }

/** File transaction, independent of Android and of the lifetime of a screen. */
internal class WallpaperFiles(private val directory: File, private val preference: WallpaperPath) {
    private val lock = Any()
    private var generation = 0L

    fun beginChange(): Long = synchronized(lock) { ++generation }

    fun replace(
        request: Long,
        openInput: () -> InputStream?,
        checkActive: () -> Unit = {},
    ): WallpaperWriteResult {
        val name = "timetable_wallpaper_${UUID.randomUUID()}"
        val temporary = File(directory, "$name.tmp")
        val destination = File(directory, name)
        var published = false
        try {
            checkActive()
            val input = openInput() ?: error("Cannot open wallpaper input stream")
            input.use { source ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        checkActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            return synchronized(lock) {
                checkActive()
                if (request != generation) return@synchronized WallpaperWriteResult.SUPERSEDED
                check(temporary.renameTo(destination)) { "Cannot publish wallpaper file" }
                preference.write("local://${destination.absolutePath}")
                published = true
                retireFilesExcept(destination)
                WallpaperWriteResult.SAVED
            }
        } finally {
            temporary.delete()
            if (!published) destination.delete()
        }
    }

    fun remove(request: Long): WallpaperWriteResult = synchronized(lock) {
        if (request != generation) return@synchronized WallpaperWriteResult.SUPERSEDED
        preference.write("")
        retireFilesExcept(null)
        WallpaperWriteResult.SAVED
    }

    fun clearMissingSelection(path: String): Boolean = synchronized(lock) {
        if (path.isBlank() || preference.read() != path || File(path.removePrefix("local://")).exists()) return@synchronized false
        preference.write("")
        true
    }

    private fun retireFilesExcept(keep: File?) {
        directory.listFiles()?.filter {
            it.name.startsWith("timetable_wallpaper") && !it.name.endsWith(".tmp") && it != keep
        }?.forEach { it.delete() }
    }
}
