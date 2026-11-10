package cn.limpu.hita.utils

import android.content.Context

/**
 * 此类封装了各种图形函数
 */
object ImageUtils {

    /**
     * convert dp to its equivalent px
     *
     * 将dp转换为与之相等的px
     */
    fun dp2px(context: Context, dipValue: Float): Int {
        val scale = context.resources.displayMetrics.density
        return (dipValue * scale + 0.5f).toInt()
    }
}
