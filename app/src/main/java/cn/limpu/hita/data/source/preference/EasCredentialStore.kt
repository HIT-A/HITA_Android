package cn.limpu.hita.data.source.preference

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.utils.LogUtils

data class EasCredential(
    val campus: EASToken.Campus,
    val username: String,
    val password: String
)

data class LegacyEasCredentialFields(
    val campus: EASToken.Campus,
    val username: String?,
    val password: String?
)

object LegacyEasCredentialPolicy {
    fun migratableCredential(
        campus: EASToken.Campus,
        username: String?,
        password: String?
    ): EasCredential? {
        if (campus != EASToken.Campus.SHENZHEN) return null
        val normalizedUsername = username?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("{") }
            ?: return null
        val usablePassword = password?.takeIf { it.isNotEmpty() } ?: return null
        return EasCredential(campus, normalizedUsername, usablePassword)
    }
}

object EasCredentialStoreKeyPolicy {
    fun key(campus: EASToken.Campus, username: String): String =
        "${campus.name}:${username.trim()}"
}

/**
 * 已保存的教务账号密码。
 *
 * 契约：密码只落在 EncryptedSharedPreferences。Keystore 密钥或偏好文件损坏时，
 * 退回进程内内存偏好——自动填充本次运行仍可用，但不会把密码写进明文文件；
 * 同时删除无法解密的旧文件，避免每次启动都重复失败。
 */
class EasCredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences by lazy { openPreferences() }

    private fun openPreferences(): SharedPreferences {
        fun create() = EncryptedSharedPreferences.create(
            PREFERENCE_NAME,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        return runCatching { create() }.getOrElse { error ->
            LogUtils.e("credential store unreadable, recreating encrypted prefs", error)
            runCatching { appContext.deleteSharedPreferences(PREFERENCE_NAME) }
            runCatching { create() }.getOrElse { retryError ->
                LogUtils.e(
                    "credential store unavailable; saved passwords stay in memory only",
                    retryError
                )
                InMemorySharedPreferences()
            }
        }
    }

    @Synchronized
    fun get(campus: EASToken.Campus, username: String? = null): EasCredential? {
        val normalizedUsername = username?.trim()?.takeIf(String::isNotEmpty)
            ?: preferences.getString(lastUsernameKey(campus), null)
            ?: return null
        val storageKey = EasCredentialStoreKeyPolicy.key(campus, normalizedUsername)
        val storedUsername = preferences.getString(usernameKey(storageKey), null) ?: return null
        val password = preferences.getString(passwordKey(storageKey), null)
            ?.takeIf(String::isNotEmpty)
            ?: return null
        if (storedUsername != normalizedUsername) return null
        return EasCredential(campus, storedUsername, password)
    }

    @Synchronized
    fun save(campus: EASToken.Campus, username: String, password: String) {
        val normalizedUsername = username.trim().takeIf { it.isNotEmpty() && !it.startsWith("{") }
            ?: return
        if (password.isEmpty()) return
        val storageKey = EasCredentialStoreKeyPolicy.key(campus, normalizedUsername)
        runCatching {
            preferences.edit()
                .putString(usernameKey(storageKey), normalizedUsername)
                .putString(passwordKey(storageKey), password)
                .putString(lastUsernameKey(campus), normalizedUsername)
                .apply()
        }
    }

    @Synchronized
    fun remove(campus: EASToken.Campus, username: String) {
        val normalizedUsername = username.trim().takeIf(String::isNotEmpty) ?: return
        val storageKey = EasCredentialStoreKeyPolicy.key(campus, normalizedUsername)
        runCatching {
            val editor = preferences.edit()
                .remove(usernameKey(storageKey))
                .remove(passwordKey(storageKey))
            if (preferences.getString(lastUsernameKey(campus), null) == normalizedUsername) {
                editor.remove(lastUsernameKey(campus))
            }
            editor.apply()
        }
    }

    @Synchronized
    fun migrateLegacyIfNeeded(legacy: EasPreferenceSource): Boolean {
        if (preferences.getBoolean(LEGACY_MIGRATION_COMPLETE, false)) return true
        val legacyFields = runCatching { legacy.getLegacyCredentialFields() }.getOrNull() ?: return false
        val candidate = LegacyEasCredentialPolicy.migratableCredential(
            legacyFields.campus,
            legacyFields.username,
            legacyFields.password
        )
        if (candidate != null && !saveIfSuccessful(candidate)) return false
        if (!runCatching { legacy.clearLegacyPassword() }.getOrDefault(false)) return false
        return runCatching {
            preferences.edit().putBoolean(LEGACY_MIGRATION_COMPLETE, true).commit()
        }.getOrDefault(false)
    }

    private fun saveIfSuccessful(credential: EasCredential): Boolean {
        val storageKey = EasCredentialStoreKeyPolicy.key(credential.campus, credential.username)
        return runCatching {
            preferences.edit()
                .putString(usernameKey(storageKey), credential.username)
                .putString(passwordKey(storageKey), credential.password)
                .putString(lastUsernameKey(credential.campus), credential.username)
                .commit()
        }.getOrDefault(false)
    }

    private fun usernameKey(storageKey: String) = "${storageKey}_username"
    private fun passwordKey(storageKey: String) = "${storageKey}_password"
    private fun lastUsernameKey(campus: EASToken.Campus) = "${campus.name}_last_username"

    private companion object {
        const val PREFERENCE_NAME = "local_eas_credentials"
        const val LEGACY_MIGRATION_COMPLETE = "legacy_shenzhen_credentials_migrated"
    }
}
