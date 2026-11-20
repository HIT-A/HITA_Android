package cn.limpu.hita.data.source.web.campus

import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

/**
 * 空间预约接口（`x-space-reservation-api`）的请求签名。
 *
 * 规则来自图书馆前端 `PimaRequestSigner`（文档 §4.3），必须逐字对齐：
 * 注入 `lang` / `nonce` / `trackingId`，把所有非空 Query 参数按键的 UTF-8 字典序升序
 * 拼成 `k1=v1k2=v2...`（无分隔符），尾部直接接盐，取 32 位小写 MD5。
 *
 * 这里是纯函数：不碰网络、不碰时间源，随机 nonce 由 `newNonce()` 显式生成，
 * 以便用固定样例在 JVM 上验算。
 */
internal object SilentPodSigner {

    /** 服务端约定的盐，不能改。 */
    const val SALT = "pima_space_reservation_fg"

    /** 空间预约前端固定使用的中文语言参数。 */
    const val DEFAULT_LANG = "zh_CN"

    /** 32 位小写 MD5。 */
    fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return out.toString()
    }

    /**
     * 生成一个 nonce / trackingId。
     *
     * 服务端只要求 32 位小写十六进制；做法与前端一致——对随机串取 MD5。
     */
    fun newNonce(seed: String = UUID.randomUUID().toString()): String = md5Hex(seed)

    /**
     * 计算签名。
     *
     * @param query 业务 Query 参数，同名的多值参数只取第一个（与前端一致）。
     * @param nonce 32 位小写随机串，参与签名。
     * @param trackingId 32 位小写随机串，参与签名。
     */
    fun signatureOf(
        query: List<Pair<String, String>>,
        nonce: String,
        trackingId: String,
        lang: String = DEFAULT_LANG,
        bodyFields: Map<String, String> = emptyMap(),
    ): String = md5Hex(canonicalString(query, nonce, trackingId, lang, bodyFields) + SALT)

    /**
     * 待签名的规范串（不含盐），便于单测直接断言拼接顺序。
     */
    fun canonicalString(
        query: List<Pair<String, String>>,
        nonce: String,
        trackingId: String,
        lang: String = DEFAULT_LANG,
        bodyFields: Map<String, String> = emptyMap(),
    ): String {
        val pairs = linkedMapOf<String, String>()
        // 同名参数只取首个非空值；空值不参与签名。
        for ((key, value) in query) {
            if (key.isEmpty() || value.isEmpty()) continue
            if (!pairs.containsKey(key)) pairs[key] = value
        }
        // POST 的顶层 JSON 字段同样参与签名（文档 §4.3 第 2 条）；
        // 空数组按「[]」参与，null 不参与——这里用空串代表 null。
        for ((key, value) in bodyFields) {
            if (key.isEmpty()) continue
            if (!pairs.containsKey(key)) pairs[key] = value
        }
        // 注入参数由签名器独占，调用方不得覆盖。
        pairs["lang"] = lang
        pairs["nonce"] = nonce
        pairs["trackingId"] = trackingId
        return pairs.entries
            .sortedWith(Comparator { lhs, rhs -> compareUtf8(lhs.key, rhs.key) })
            .joinToString(separator = "") { (key, value) -> "$key=$value" }
    }

    /**
     * 拼出带 `signature` 的完整 URL。
     *
     * @param baseUrl 形如 `https://www.utszlib.edu.cn/space-reservation-fg/x-space-reservation-api`
     * @param path 相对路径，如 `web/spaces`，两端的 `/` 会被忽略。
     */
    fun signedUrl(
        baseUrl: String,
        path: String,
        query: List<Pair<String, String>>,
        nonce: String = newNonce(),
        trackingId: String = newNonce(),
        lang: String = DEFAULT_LANG,
        bodyFields: Map<String, String> = emptyMap(),
    ): String {
        val signature = signatureOf(query, nonce, trackingId, lang, bodyFields)
        val builder = StringBuilder(baseUrl.trimEnd('/'))
        val trimmedPath = path.trim('/')
        if (trimmedPath.isNotEmpty()) builder.append('/').append(trimmedPath)
        val items = ArrayList<Pair<String, String>>(query.size + 4)
        for ((key, value) in query) {
            if (key.isEmpty() || value.isEmpty()) continue
            if (items.any { it.first == key }) continue
            items += key to value
        }
        items += "lang" to lang
        items += "nonce" to nonce
        items += "trackingId" to trackingId
        items += "signature" to signature
        builder.append('?')
        builder.append(items.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" })
        return builder.toString()
    }

    /** percent-encode：服务端按表单编码解析，空格必须写成 `%20` 而不是 `+`。 */
    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** UTF-8 字节序（无符号比较）字典序，避免受平台默认排序规则影响。 */
    private fun compareUtf8(lhs: String, rhs: String): Int {
        val a = lhs.toByteArray(Charsets.UTF_8)
        val b = rhs.toByteArray(Charsets.UTF_8)
        val limit = minOf(a.size, b.size)
        for (index in 0 until limit) {
            val left = a[index].toInt() and 0xff
            val right = b[index].toInt() and 0xff
            if (left != right) return left - right
        }
        return a.size - b.size
    }

    private const val HEX = "0123456789abcdef"
}
