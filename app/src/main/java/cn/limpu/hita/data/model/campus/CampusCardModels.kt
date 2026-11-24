package cn.limpu.hita.data.model.campus

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 深圳信息门户校园卡（`dxcdp.hitsz.edu.cn/portal-api`）的数据模型。
 *
 * 字段只覆盖页面真正展示的部分：证件号、银行卡号等敏感字段不解码、不落模。
 * 金额一律以「分」为单位保存，展示前才换算成元。
 */

/** `index/getBillSummaryBySno` + `index/getCountYkt` 的合并结果。 */
data class CampusCardSummary(
    /** 卡内余额，单位：分。 */
    val balanceCents: Int,
    /** 卡状态文本，例如「正常」。 */
    val statusText: String?,
    /** 一卡通系统数据处理时间（原样文本）。 */
    val processedAtText: String?,
    /** 消费汇总。该接口失败时为 null，不影响余额展示。 */
    val spending: CampusCardSpending?,
)

/** `index/getCountYkt` 的昨日 / 本周 / 本月消费汇总。 */
data class CampusCardSpending(
    val yesterdayText: String?,
    val weekText: String?,
    val monthText: String?,
)

/** `index/getBillDetailList` 的单条流水。 */
data class CampusCardTransaction(
    /** 列表内稳定标识：分页 + 序号 + 时间 + 金额。 */
    val id: String,
    /** 交易金额，单位：分；保留接口原始符号（消费为负、入账为正）。 */
    val amountCents: Int,
    /** 接口原始时间文本，解析失败也照原样展示。 */
    val occurredAtText: String,
    /** 解析后的时间戳；格式不认识时为 null。 */
    val occurredAtMillis: Long?,
    val merchant: String,
    val method: String,
)

data class CampusCardTransactions(
    val items: List<CampusCardTransaction>,
    val total: Int,
)

/** 页面一次性展示的完整快照。 */
data class CampusCardPage(
    val summary: CampusCardSummary,
    val transactions: CampusCardTransactions,
    /** 流水单独失败时为 true：余额与汇总仍然有效。 */
    val transactionsFailed: Boolean = false,
    val transactionsLoading: Boolean = false,
    val spendingLoading: Boolean = false,
    val transactionsUpdatedAt: Long? = null,
    val historyPage: Int = 1,
    val summaryCached: Boolean = false,
    val historySyncing: Boolean = false,
    val initialHistoryComplete: Boolean = true,
    val historySavedCount: Int = 0,
    val historyRemoteTotal: Int = 0,
    val historyYears: List<Int> = emptyList(),
    val periodSpending: String? = null,
    val requiresSignIn: Boolean = false,
)

/** 分 → 元的定点文本，固定两位小数（不做本地化分节，避免与金额解析混淆）。 */
fun campusCardYuan(cents: Int): String =
    BigDecimal(cents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP).toPlainString()
