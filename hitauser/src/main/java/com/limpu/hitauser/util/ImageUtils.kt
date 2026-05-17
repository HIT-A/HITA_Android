package com.limpu.hitauser.util

import android.app.Activity
import android.content.Context
import android.text.TextUtils
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import com.limpu.hitauser.R
import com.limpu.hitauser.data.source.preference.UserPreferenceSource

object ImageUtils {
    fun loadAvatarInto(context: Context, imageId: String?, target: ImageView) {
        if (TextUtils.isEmpty(imageId)) {
            target.setImageResource(R.drawable.place_holder_avatar)
        } else if (imageId!!.startsWith("local://")) {
            val file = java.io.File(imageId.removePrefix("local://"))
            if (context is Activity) {
                if (context.isDestroyed) return
            }
            Glide.with(context).load(file)
                .apply(RequestOptions.bitmapTransform(CircleCrop()))
                .placeholder(R.drawable.place_holder_avatar)
                .into(target)
        } else {
            val glideUrl = GlideUrl(
                "https://hita.store:39999/profile/avatar?imageId=" +
                        imageId, LazyHeaders.Builder().addHeader("device-type", "android").build()
            )
            if (context is Activity) {
                if (context.isDestroyed) {
                    return
                }
            }
            Glide.with(context).load(
                glideUrl
            ).apply(RequestOptions.bitmapTransform(CircleCrop()))
                .placeholder(R.drawable.place_holder_avatar).into(target)

        }
    }

    fun dp2px(context: Context, dipValue: Float): Int {
        val scale = context.resources.displayMetrics.density
        return (dipValue * scale + 0.5f).toInt()
    }
}