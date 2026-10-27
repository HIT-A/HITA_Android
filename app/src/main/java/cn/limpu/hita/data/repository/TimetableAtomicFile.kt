package cn.limpu.hita.data.repository

import android.system.Os
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/** Publish a synced complete successor with one same-directory rename, including on API 26. */
internal class TimetableAtomicFile(private val file: File) {
    private val temporary = File(file.path + ".new")
    private val legacyTemporary = File(file.path + ".tmp")
    private val backup = File(file.path + ".bak")

    fun readOrNull(): String? {
        // If the process stopped before the successor was renamed, restore the backup only when
        // the base is absent. When both exist, the base is the complete successor and the backup
        // is the stale pre-image; restoring it would silently roll back a committed publication.
        if (!file.exists()) {
            when {
                backup.exists() -> Os.rename(backup.path, file.path)
                // Versions before TimetableAtomicFile used a same-directory .tmp file.
                // Promote it when the base disappeared during the old writer's rename
                // window so an upgrade cannot silently discard pending state.
                legacyTemporary.exists() -> Os.rename(legacyTemporary.path, file.path)
            }
        } else {
            if (backup.exists()) backup.delete()
            if (legacyTemporary.exists()) legacyTemporary.delete()
        }
        return try {
            file.inputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (missing: FileNotFoundException) {
            if (file.exists()) throw missing
            null
        }
    }

    fun write(text: String) {
        val directory = file.parentFile ?: throw IOException("Missing timetable storage directory")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create timetable storage directory")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            // Android's older AtomicFile can log rename failure and truncate the original file.
            // Os.rename reports failure and leaves the original complete file in place.
            Os.rename(temporary.path, file.path)
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
    }
}
