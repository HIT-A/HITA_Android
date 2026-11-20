package cn.limpu.hita.data.source.web.hoa

import java.security.MessageDigest

/**
 * 投稿幂等键：由内容派生，不含时间与随机数。
 *
 * 契约：
 * - 相同校区、课程码与规范化 ops 必然得到相同键，因此客户端超时重试不会产生重复 PR。
 * - 任一输入变化都会改变键，让「改了内容再投」成为一次新提交。
 * - 当前实现不设过期窗口；服务端按自己的去重窗口决定保留多久。
 */
object SubmissionIdempotencyKey {

    private const val PREFIX = "android-v1-"
    private const val HEX_RADIX = 16

    fun derive(campus: String, courseCode: String, opsCanonical: String): String {
        val material = buildString {
            append(normalize(campus))
            append('\u0000')
            append(normalize(courseCode))
            append('\u0000')
            append(opsCanonical.trim())
        }
        return PREFIX + sha256Hex(material)
    }

    /**
     * ops 的规范化文本：每个元素按 JSON 文本排序后拼接。
     *
     * 使用排序而非原始顺序，避免「同一组操作、不同排列」产生不同键。
     * 调用方需保证每个元素本身是稳定序列化结果（字段顺序固定）。
     */
    fun canonicalOps(elements: List<String>): String =
        elements.map { it.trim() }.sorted().joinToString(separator = "\u0001")

    private fun normalize(raw: String): String = raw
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(Character.forDigit(value shr 4, HEX_RADIX))
            out.append(Character.forDigit(value and 0x0F, HEX_RADIX))
        }
        return out.toString()
    }
}
