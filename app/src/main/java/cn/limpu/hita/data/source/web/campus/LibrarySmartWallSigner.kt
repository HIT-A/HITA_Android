package cn.limpu.hita.data.source.web.campus

import java.security.MessageDigest

/**
 * 智慧墙接口的签名规则。
 *
 * 事实来源：`docs/reference/深圳大学城图书馆接口.md` §4.2（脚本确认 / 代理实测）。
 * salt 固定为 `wm-receive-confirm`，签名原文是 `lang=zh_CNnonce=<nonce>wm-receive-confirm`，
 * 其中 `nonce` 本身是 32 位小写 MD5。三个 Query 参数必须一起带上，缺一个服务端就会报签名错误。
 *
 * 全部函数是纯函数，单测用固定种子即可复现同样的 URL，不依赖随机数。
 */
internal object LibrarySmartWallSigner {
    const val SALT = "wm-receive-confirm"
    const val LANG = "zh_CN"

    /** 32 位小写 MD5。 */
    fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(32)
        for (byte in digest) {
            val unsigned = byte.toInt() and 0xFF
            out.append(HEX[unsigned ushr 4]).append(HEX[unsigned and 0x0F])
        }
        return out.toString()
    }

    /** `nonce`：种子串的 MD5，保证 32 位小写。 */
    fun nonce(seed: String): String = md5Hex(seed)

    /** `signature`：`lang=zh_CNnonce=<nonce><salt>` 的 MD5。 */
    fun signature(nonce: String, lang: String = LANG): String =
        md5Hex("lang=$lang" + "nonce=$nonce" + SALT)

    /**
     * 拼出带签名的完整 URL。
     *
     * [baseUrl] 可以是 `https://host/smart-wall_api` 这类不带尾斜杠的基址；[path]
     * 允许带或不带前导斜杠。已有的 Query 一律丢弃：这些接口的既有 Query 只有网关
     * 附加项，保留反而会让服务端校验失败。
     */
    fun signedUrl(baseUrl: String, path: String, seed: String): String {
        val trimmedPath = path.trim('/')
        val endpoint = baseUrl.trimEnd('/') + if (trimmedPath.isEmpty()) "" else "/$trimmedPath"
        val nonce = nonce(seed)
        return "$endpoint?lang=$LANG&nonce=$nonce&signature=${signature(nonce)}"
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
