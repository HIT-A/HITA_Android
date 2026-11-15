package cn.limpu.hita.data.source.preference

import android.content.Context
import android.content.SharedPreferences

/** Whether the drawer and More page hide student id, name, and college. Default is visible. */
class IdentityVisibilityStore(context: Context) {
    private val preference: SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    fun isHidden(): Boolean = preference.getBoolean(KEY_HIDE_IDENTITY, false)

    fun setHidden(hidden: Boolean) {
        preference.edit().putBoolean(KEY_HIDE_IDENTITY, hidden).apply()
    }

    private companion object {
        const val SP_NAME = "identity_visibility"
        const val KEY_HIDE_IDENTITY = "hide_identity"
    }
}
