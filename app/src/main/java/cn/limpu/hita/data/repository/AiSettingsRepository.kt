package cn.limpu.hita.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import cn.limpu.hita.data.source.preference.InMemorySharedPreferences
import cn.limpu.hita.utils.LogUtils

/** 当前加密偏好名。历史版本在同一文件名里写过明文，见 [AiSettingsLegacyStoragePolicy]。 */
private const val SP_NAME_AI_SETTINGS = "ai_settings"

/** 从历史明文迁移出来时使用的新文件名，避免与旧明文文件同名互相覆盖。 */
private const val SP_NAME_AI_SETTINGS_MIGRATED = "ai_settings_secure_v2"
private const val KEY_CHAT_PROVIDER = "chat_provider"
private const val KEY_CUSTOM_DEEPSEEK_API_KEY = "custom_deepseek_api_key"
private const val KEY_CUSTOM_ZHIPU_API_KEY = "custom_zhipu_api_key"
private const val ENCRYPTED_PREFS_KEY_PREFIX = "__androidx_security_crypto_encrypted_prefs"

enum class AiChatProvider(val value: String) {
    BUILTIN_DEEPSEEK("builtin_deepseek"),
    CUSTOM_DEEPSEEK("custom_deepseek");

    companion object {
        fun from(value: String?): AiChatProvider {
            return entries.firstOrNull { it.value == value } ?: BUILTIN_DEEPSEEK
        }
    }
}

data class AiSettings(
    val chatProvider: AiChatProvider,
    val customDeepSeekApiKey: String,
    val customZhipuApiKey: String,
)

/**
 * AI 设置与用户自定义 API Key 的存储。
 *
 * 契约：
 * - 加密偏好可用时，Key 只以 EncryptedSharedPreferences 形式落盘。
 * - 加密偏好不可用（Keystore 失效、厂商 ROM 限制、文件损坏）时退回**进程内内存偏好**，
 *   Key 不写磁盘。宁可让用户重新填写，也不静默降级为明文 SharedPreferences。
 * - 历史版本曾把 Key 写入同名明文偏好；首次识别到明文时迁移到独立的加密文件并删除明文。
 */
class AiSettingsRepository(context: Context) {
    private val storage: AiSettingsStorage = AiSettingsStorage.open(context.applicationContext)

    fun getSettings(): AiSettings {
        val preference = storage.preferences
        return AiSettings(
            chatProvider = AiChatProvider.from(preference.getString(KEY_CHAT_PROVIDER, null)),
            customDeepSeekApiKey = preference.getString(KEY_CUSTOM_DEEPSEEK_API_KEY, "").orEmpty(),
            customZhipuApiKey = preference.getString(KEY_CUSTOM_ZHIPU_API_KEY, "").orEmpty(),
        )
    }

    fun saveSettings(settings: AiSettings) {
        storage.preferences.edit()
            .putString(KEY_CHAT_PROVIDER, settings.chatProvider.value)
            .putString(KEY_CUSTOM_DEEPSEEK_API_KEY, settings.customDeepSeekApiKey.trim())
            .putString(KEY_CUSTOM_ZHIPU_API_KEY, settings.customZhipuApiKey.trim())
            .apply()
    }

    fun getCustomDeepSeekApiKey(): String {
        return storage.preferences.getString(KEY_CUSTOM_DEEPSEEK_API_KEY, "").orEmpty().trim()
    }

    fun getCustomZhipuApiKey(): String {
        return storage.preferences.getString(KEY_CUSTOM_ZHIPU_API_KEY, "").orEmpty().trim()
    }

    /** 加密存储是否可用；不可用时 UI 应提示 Key 仅本次运行有效。 */
    fun isEncryptedStorageAvailable(): Boolean = storage.encrypted
}

/** 历史 AI 设置文件的形态判定，纯函数，便于 JVM 单测。 */
internal sealed interface AiSettingsLegacyStoragePolicy {
    /** 文件里已是加密条目：继续使用原文件名，保留既有 Key。 */
    data object EncryptedExisting : AiSettingsLegacyStoragePolicy

    /** 文件里是历史明文条目：迁移到独立的加密文件，然后删除明文文件。 */
    data class Plaintext(val values: Map<String, String>) : AiSettingsLegacyStoragePolicy

    data object Empty : AiSettingsLegacyStoragePolicy

    companion object {
        private val plaintextKeys = setOf(
            KEY_CHAT_PROVIDER,
            KEY_CUSTOM_DEEPSEEK_API_KEY,
            KEY_CUSTOM_ZHIPU_API_KEY,
        )

        fun inspect(raw: Map<String, *>): AiSettingsLegacyStoragePolicy {
            val hasEncryptedEntries = raw.keys.any { it.startsWith(ENCRYPTED_PREFS_KEY_PREFIX) }
            if (hasEncryptedEntries) return EncryptedExisting
            val plaintext = raw.entries
                .mapNotNull { (key, value) ->
                    val stringValue = value as? String ?: return@mapNotNull null
                    if (key in plaintextKeys) key to stringValue else null
                }
                .toMap()
            return if (plaintext.isEmpty()) Empty else Plaintext(plaintext)
        }
    }
}

internal class AiSettingsStorage(
    val preferences: SharedPreferences,
    val encrypted: Boolean,
) {
    companion object {
        fun open(
            context: Context,
            inMemory: () -> SharedPreferences = { InMemorySharedPreferences() },
        ): AiSettingsStorage {
            // 已经迁移过的设备：迁移目标文件里是密文，必须优先使用它，否则会读到被清空的旧明文文件。
            val migratedInUse = AiSettingsLegacyStoragePolicy.inspect(
                rawPreferences(context, SP_NAME_AI_SETTINGS_MIGRATED).all
            ) is AiSettingsLegacyStoragePolicy.EncryptedExisting

            val legacy = AiSettingsLegacyStoragePolicy.inspect(
                rawPreferences(context, SP_NAME_AI_SETTINGS).all
            )
            val targetName = when {
                migratedInUse -> SP_NAME_AI_SETTINGS_MIGRATED
                legacy is AiSettingsLegacyStoragePolicy.Plaintext -> SP_NAME_AI_SETTINGS_MIGRATED
                else -> SP_NAME_AI_SETTINGS
            }

            val encryptedPreferences = openEncrypted(context, targetName)
            if (encryptedPreferences == null) {
                LogUtils.e("AI settings encrypted storage unavailable; API keys stay in memory only")
                return AiSettingsStorage(inMemory(), encrypted = false)
            }

            if (!migratedInUse && legacy is AiSettingsLegacyStoragePolicy.Plaintext) {
                migrateLegacyPlaintext(context, encryptedPreferences, legacy.values)
            }
            return AiSettingsStorage(encryptedPreferences, encrypted = true)
        }

        private fun rawPreferences(context: Context, name: String): SharedPreferences =
            runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE) }
                .getOrElse { InMemorySharedPreferences() }

        private fun openEncrypted(context: Context, name: String): SharedPreferences? {
            fun create() = EncryptedSharedPreferences.create(
                name,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )

            return runCatching { create() }.getOrElse { error ->
                // 已存在但无法解密（Keystore 密钥丢失/文件损坏）：删掉再重建，只丢弃旧值，
                // 绝不退回明文文件。
                LogUtils.e("AI settings encrypted storage unreadable, recreating", error)
                runCatching { context.deleteSharedPreferences(name) }
                runCatching { create() }
                    .onFailure { LogUtils.e("AI settings encrypted storage unavailable", it) }
                    .getOrNull()
            }
        }

        private fun migrateLegacyPlaintext(
            context: Context,
            encrypted: SharedPreferences,
            values: Map<String, String>,
        ) {
            val editor = encrypted.edit()
            values[KEY_CHAT_PROVIDER]?.let { editor.putString(KEY_CHAT_PROVIDER, it) }
            values[KEY_CUSTOM_DEEPSEEK_API_KEY]
                ?.takeIf { it.isNotBlank() }
                ?.let { editor.putString(KEY_CUSTOM_DEEPSEEK_API_KEY, it) }
            values[KEY_CUSTOM_ZHIPU_API_KEY]
                ?.takeIf { it.isNotBlank() }
                ?.let { editor.putString(KEY_CUSTOM_ZHIPU_API_KEY, it) }
            if (!editor.commit()) {
                LogUtils.e("AI settings plaintext migration failed; keeping legacy file")
                return
            }
            runCatching { context.deleteSharedPreferences(SP_NAME_AI_SETTINGS) }
            LogUtils.d("AI settings migrated from legacy plaintext preferences")
        }
    }
}
