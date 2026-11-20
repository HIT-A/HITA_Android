package cn.limpu.hita.data.source.preference

import android.app.Application
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class TodoLoginProvider { GRADER, GENIUSTA }

/** Account ID is the verified platform identity, not the unverified login name. */
internal class TodoLoginCredential(val username: String, val password: String, val accountId: String)

/** Opt-in device credentials. No plaintext, Android backup, or cross-platform fallback. */
@Singleton
class TodoPlatformCredentialStore @Inject constructor(application: Application) {
    private val preferences: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            "todo_platform_credentials_v1",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            application,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    internal suspend fun read(platform: TodoLoginProvider): TodoLoginCredential? = withContext(Dispatchers.IO) {
        runCatching {
            synchronized(this@TodoPlatformCredentialStore) {
                val prefix = platform.name
                val username = preferences.getString("$prefix.username", null) ?: return@synchronized null
                val password = preferences.getString("$prefix.password", null) ?: return@synchronized null
                val accountId = preferences.getString("$prefix.account_id", null) ?: return@synchronized null
                TodoLoginCredential(username, password, accountId).takeIf(::valid)
            }
        }.getOrNull()
    }

    internal suspend fun save(platform: TodoLoginProvider, credential: TodoLoginCredential): Boolean = withContext(Dispatchers.IO) {
        if (!valid(credential)) return@withContext false
        runCatching {
            synchronized(this@TodoPlatformCredentialStore) {
                val prefix = platform.name
                preferences.edit()
                    .putString("$prefix.username", credential.username.trim())
                    .putString("$prefix.password", credential.password)
                    .putString("$prefix.account_id", credential.accountId)
                    .commit()
            }
        }.getOrDefault(false)
    }

    internal suspend fun forget(platform: TodoLoginProvider): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            synchronized(this@TodoPlatformCredentialStore) {
                val prefix = platform.name
                preferences.edit().remove("$prefix.username").remove("$prefix.password").remove("$prefix.account_id").commit()
            }
        }.getOrDefault(false)
    }

    private fun valid(credential: TodoLoginCredential): Boolean =
        credential.username.isNotBlank() && credential.username.length <= 255 &&
            credential.password.isNotEmpty() && credential.password.length <= 1024 &&
            credential.accountId.isNotBlank() && credential.accountId.length <= 256
}
