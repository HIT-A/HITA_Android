package cn.limpu.hita.ui.main

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import cn.limpu.hita.utils.WallpaperColorAnalyzer
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import java.io.File

internal interface WallpaperImageSource {
    fun load(path: String, ready: (Bitmap) -> Unit, cleared: () -> Unit): AutoCloseable
}

/** Owns both the bitmap and its colors; stale callbacks cannot update either. */
internal class WallpaperLoader(
    private val images: WallpaperImageSource,
    private var publish: ((Bitmap?, Boolean, Int, Int) -> Unit)?,
) : AutoCloseable {
    constructor(activity: Activity, publish: (Bitmap?, Boolean, Int, Int) -> Unit) :
        this(GlideWallpaperImageSource(Glide.with(activity)), publish)

    private var generation = 0L
    private var closed = false
    private var request: AutoCloseable? = null
    private var lastPath: String? = null

    fun load(path: String?) {
        if (closed || path == lastPath) return
        lastPath = path
        val expected = ++generation
        request?.close()
        request = null
        publish?.invoke(null, false, Color.WHITE, Color.WHITE)
        if (path.isNullOrBlank()) return
        request = images.load(path, ready = { resource ->
            if (!closed && expected == generation) {
                publish?.invoke(
                    resource, true,
                    WallpaperColorAnalyzer.sampleRegion(resource, 0f, 0f, 1f, 0.12f),
                    WallpaperColorAnalyzer.sampleRegion(resource, 0f, 0.12f, 0.08f, 0.88f),
                )
            }
        }, cleared = {
            if (!closed && expected == generation) publish?.invoke(null, false, Color.WHITE, Color.WHITE)
        })
    }

    override fun close() {
        closed = true
        generation++
        publish = null
        request?.close()
        request = null
    }
}

/** Glide requests are Activity-scoped, and every target has an explicit clear handle. */
private class GlideWallpaperImageSource(private val requests: RequestManager) : WallpaperImageSource {
    override fun load(path: String, ready: (Bitmap) -> Unit, cleared: () -> Unit): AutoCloseable {
        val target = object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) = ready(resource)
            override fun onLoadCleared(placeholder: Drawable?) = cleared()
        }
        requests.asBitmap().load(File(path.removePrefix("local://"))).centerCrop().into(target)
        return AutoCloseable { requests.clear(target) }
    }
}
