package cn.limpu.hita.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 设置的存储策略必须保证：Key 只存在于加密偏好或内存，历史明文只在迁移时被读一次。
 * 这里锁定判定逻辑（文件里是密文 / 历史明文 / 空），真实 Keystore 行为由设备测试覆盖。
 */
class AiSettingsStoragePolicyTest {

    private val encryptedKey = "__androidx_security_crypto_encrypted_prefs_key_encryption_keys"

    @Test
    fun `file with encrypted marker is treated as already encrypted`() {
        val policy = AiSettingsLegacyStoragePolicy.inspect(
            mapOf<String, Any>(encryptedKey to "ciphertext", "custom_deepseek_api_key" to "sk-legacy")
        )
        assertEquals(AiSettingsLegacyStoragePolicy.EncryptedExisting, policy)
    }

    @Test
    fun `plaintext api keys are detected for migration`() {
        val policy = AiSettingsLegacyStoragePolicy.inspect(
            mapOf<String, Any>(
                "chat_provider" to "custom_deepseek",
                "custom_deepseek_api_key" to "sk-plain",
                "custom_zhipu_api_key" to "zp-plain"
            )
        )
        assertTrue(policy is AiSettingsLegacyStoragePolicy.Plaintext)
        val migrated = (policy as AiSettingsLegacyStoragePolicy.Plaintext).values
        assertEquals("sk-plain", migrated["custom_deepseek_api_key"])
        assertEquals("zp-plain", migrated["custom_zhipu_api_key"])
        assertEquals("custom_deepseek", migrated["chat_provider"])
    }

    @Test
    fun `empty file needs no migration`() {
        assertEquals(AiSettingsLegacyStoragePolicy.Empty, AiSettingsLegacyStoragePolicy.inspect(emptyMap<String, Any>()))
    }

    @Test
    fun `unrelated plaintext entries are ignored`() {
        val policy = AiSettingsLegacyStoragePolicy.inspect(mapOf<String, Any>("some_other_key" to "value"))
        assertEquals(AiSettingsLegacyStoragePolicy.Empty, policy)
    }

    @Test
    fun `non string api key values are not migrated as secrets`() {
        val policy = AiSettingsLegacyStoragePolicy.inspect(
            mapOf<String, Any>("custom_deepseek_api_key" to 42, "chat_provider" to "builtin_deepseek")
        )
        assertTrue(policy is AiSettingsLegacyStoragePolicy.Plaintext)
        val migrated = (policy as AiSettingsLegacyStoragePolicy.Plaintext).values
        assertEquals(null, migrated["custom_deepseek_api_key"])
        assertEquals("builtin_deepseek", migrated["chat_provider"])
    }

    @Test
    fun `already migrated file is recognized as encrypted so the old plaintext file is not reused`() {
        // 迁移后旧文件被删除，只剩 v2；inspect(v2) 必须是 EncryptedExisting，
        // 否则 open() 会回到空文件导致 Key 丢失。
        val migrated = AiSettingsLegacyStoragePolicy.inspect(
            mapOf<String, Any>(encryptedKey to "ciphertext")
        )
        assertEquals(AiSettingsLegacyStoragePolicy.EncryptedExisting, migrated)
        assertEquals(
            AiSettingsLegacyStoragePolicy.Empty,
            AiSettingsLegacyStoragePolicy.inspect(mapOf<String, Any>())
        )
    }
}
