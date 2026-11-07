package cn.limpu.hita.data.source.preference

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** No plaintext fallback: creation, read and commit failures are handled as storage errors. */
internal class GraderPreferenceSource(context: Context) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            "grader_secure_v1",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun read(): String? = preferences.getString("snapshot", null)
    fun write(snapshot: String): Boolean = preferences.edit().putString("snapshot", snapshot).commit()
}
