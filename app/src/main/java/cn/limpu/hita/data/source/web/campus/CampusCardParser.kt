package cn.limpu.hita.data.source.web.campus

import cn.limpu.hita.data.model.campus.CampusCardSpending
import cn.limpu.hita.data.model.campus.CampusCardSummary
import cn.limpu.hita.data.model.campus.CampusCardTransaction
import cn.limpu.hita.data.model.campus.CampusCardTransactions
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/** 门户响应结构无法识别，或返回了明确业务错误。 */
internal open class CampusCardParseException(message: String) : Exception(message)

/** 门户判定 token 缺失/失效：调用方应重新走统一身份认证。 */
internal class CampusCardSignInRequiredException(message: String) : CampusCardParseException(message)

/** 门户明确返回的非鉴权类错误。 */
internal class CampusCardRemoteException(message: String) : CampusCardParseException(message)

/**
 * 深圳信息门户 `portal-api` 三个响应的纯解析器。
 *
 * 全部输入为原始 JSON 字符串，不接触网络与 Android 框架，便于用脱敏 fixture 做 JVM 单测。
 * 字段名严格对齐 iOS `CampusCardPortalClient` 的 DTO，不臆造字段：
 * - `index/getBillSummaryBySno` → `cardbalance` / `flagname` / `procdatetime`
 * - `index/getCountYkt` → `yesterdayAmount` / `weekAmount` / `monthAmount`
 * - `index/getBillDetailList` → `rows[].tranamt/effectdate/mercname/tranname` + `total`
 */
internal object CampusCardParser {

    private val portalZone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 门户时间文本的已知格式，按 iOS 的尝试顺序排列。 */
    private val dateFormats: List<DateTimeFormatter> = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss",
        "yyyyMMddHHmmss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
    ).map { pattern ->
        // 只带日期的格式缺时分秒，补默认值后才能统一按 LocalDateTime 解析。
        DateTimeFormatterBuilder()
            .appendPattern(pattern)
            .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
            .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
            .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
            .toFormatter(Locale.US)
    }

    /** 解析余额与卡状态。`spending` 由 [spending] 单独合并。 */
    fun billSummary(raw: String): CampusCardSummary {
        val envelope = envelope(raw)
        val code = envelope.codeOrThrow()
        if (code != 0) throw failure(code, envelope.optString("msg", null))
        val data = envelope.optJSONObject("data")
            ?: throw CampusCardRemoteException("门户未返回校园卡余额")
        val balanceText = data.optString("cardbalance", "").trim()
        val balanceCents = centsOrNull(balanceText)
            ?: throw CampusCardRemoteException("门户返回的余额格式无法识别")
        return CampusCardSummary(
            balanceCents = balanceCents,
            statusText = data.optString("flagname", "").trim().ifEmpty { null },
            processedAtText = data.optString("procdatetime", "").trim().ifEmpty { null },
            spending = null,
        )
    }

    /**
     * 解析消费汇总。
     *
     * 该接口只是附加信息：`code != 0`（含 token 失效）时返回 null，
     * 由调用方保留余额展示，不把整体请求判失败。
     */
    fun spending(raw: String): CampusCardSpending? = runCatching {
        val envelope = envelope(raw)
        if (envelope.codeOrThrow() != 0) return@runCatching null
        val data = envelope.optJSONObject("data") ?: return@runCatching null
        CampusCardSpending(
            yesterdayText = data.optString("yesterdayAmount", "").trim().ifEmpty { null },
            weekText = data.optString("weekAmount", "").trim().ifEmpty { null },
            monthText = data.optString("monthAmount", "").trim().ifEmpty { null },
        )
    }.getOrNull()

    /** 解析消费流水页。 */
    fun transactions(raw: String, page: Int): CampusCardTransactions {
        val envelope = envelope(raw)
        val code = envelope.codeOrThrow()
        if (code != 0) throw failure(code, envelope.optString("msg", null))
        val rows: JSONArray = envelope.optJSONArray("rows") ?: JSONArray()
        val items = (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.let { row -> transaction(row, page, index) }
        }
        val total = total(envelope) ?: items.size
        return CampusCardTransactions(items = items, total = total)
    }

    /**
     * 解析 `ssologin` 响应，取出请求头 `token`。
     *
     * iOS 语义：`code == 500` 或 `data` 为空即视为登录失败。
     */
    fun token(raw: String): String {
        val envelope = envelope(raw)
        val code = envelope.codeOrThrow()
        if (code == 500 || code != 0) throw failure(code, envelope.optString("msg", null))
        val token = envelope.optString("data", "").trim()
        if (token.isEmpty()) throw CampusCardRemoteException("门户未返回访问令牌")
        return token
    }

    /** 门户在缺少或失效 token 时返回 `{"code":500,"msg":"参数缺失"}` 或 401。 */
    fun isTokenFailure(code: Int?, message: String?): Boolean {
        if (code == 401) return true
        val text = message.orEmpty()
        return code == 500 && (text.contains("参数缺失") || text.contains("token") || text.contains("登录"))
    }

    /** 金额文本 → 分；无法解析时返回 0（用于流水，单条坏数据不该拖垮整页）。 */
    fun cents(text: String?): Int = centsOrNull(text) ?: 0

    /** 金额文本 → 分；无法解析时返回 null（用于必须存在的余额）。 */
    private fun centsOrNull(text: String?): Int? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        trimmed.toIntOrNull()?.let { return it }
        return runCatching { BigDecimal(trimmed).toInt() }.getOrNull()
    }

    /** 门户时间文本 → 毫秒时间戳（Asia/Shanghai）；格式不认识时返回 null。 */
    fun parsePortalDate(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        for (formatter in dateFormats) {
            val parsed = runCatching { LocalDateTime.parse(trimmed, formatter) }.getOrNull()
            if (parsed != null) {
                return parsed.atZone(portalZone).toInstant().toEpochMilli()
            }
        }
        return null
    }

    private fun transaction(row: JSONObject, page: Int, index: Int): CampusCardTransaction {
        val amountText = amountText(row)
        val occurredText = row.optString("effectdate", "")
        return CampusCardTransaction(
            id = "$page-$index-$occurredText-$amountText",
            amountCents = cents(amountText),
            occurredAtText = occurredText,
            occurredAtMillis = parsePortalDate(occurredText),
            merchant = row.optString("mercname", "").trim(),
            method = row.optString("tranname", "").trim(),
        )
    }

    /** `tranamt` 可能是数字也可能是字符串；数字按四舍五入取整分，与 iOS 一致。 */
    private fun amountText(row: JSONObject): String {
        if (row.isNull("tranamt")) return ""
        return when (val value = row.opt("tranamt")) {
            is Number -> Math.round(value.toDouble()).toString()
            is String -> value
            else -> ""
        }
    }

    private fun total(envelope: JSONObject): Int? {
        if (envelope.isNull("total")) return null
        return when (val value = envelope.opt("total")) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun envelope(raw: String): JSONObject =
        runCatching { JSONObject(raw) }.getOrElse {
            throw CampusCardRemoteException("门户返回的内容不是有效 JSON")
        }

    /** 缺 `code` 字段说明响应结构变了，按无法识别处理，不能当成成功。 */
    private fun JSONObject.codeOrThrow(): Int {
        if (!has("code") || isNull("code")) {
            throw CampusCardRemoteException("门户响应缺少 code 字段")
        }
        return optInt("code", Int.MIN_VALUE)
    }

    private fun failure(code: Int, message: String?): Exception {
        val text = message?.takeIf { it.isNotBlank() }
        return if (isTokenFailure(code, text)) {
            CampusCardSignInRequiredException(text ?: "门户登录已失效")
        } else {
            CampusCardRemoteException(text ?: "综合信息门户返回错误（$code）")
        }
    }
}
