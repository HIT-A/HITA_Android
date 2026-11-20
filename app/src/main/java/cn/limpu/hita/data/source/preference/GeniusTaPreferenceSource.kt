package cn.limpu.hita.data.source.preference

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** No plaintext fallback. Credentials are limited to the encrypted session snapshot. */
internal class GeniusTaPreferenceSource(context: Context) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            "geniusta_secure_v1",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun read(): String? = preferences.getString("snapshot", null)
    fun write(snapshot: String): Boolean = preferences.edit().putString("snapshot", snapshot).commit()
}
