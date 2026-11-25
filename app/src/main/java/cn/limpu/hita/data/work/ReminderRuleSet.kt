package cn.limpu.hita.data.work

import cn.limpu.hita.data.source.preference.PreferenceStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Course and exam reminder offsets.
 *
 * @param leads 上课前的提前分钟数
 * @param endLeads 下课前的提前分钟数；空列表表示不发下课前提醒
 * @param periodOverrides 按节次的课前提前量覆盖
 * @param examLeads 考前的提前分钟数，取值限定在 [EXAM_PRESETS]
 */
data class ReminderRuleSet(
    val leads: List<Int> = listOf(DEFAULT_LEAD_MINUTES),
    val periodOverrides: Map<Int, Int> = emptyMap(),
    val examLeads: List<Int> = emptyList(),
    val endLeads: List<Int> = DEFAULT_END_LEADS,
) {
    fun leadsFor(section: Int?): List<Int> {
        val override = section?.let(periodOverrides::get)
        return if (override == null) leads else listOf(ReminderRules.clipLead(override))
    }

    companion object {
        const val DEFAULT_LEAD_MINUTES = 15

        /** 与 iOS 一致：默认在下课前 5 分钟提醒一次。 */
        val DEFAULT_END_LEADS = listOf(5)
        val EXAM_PRESETS = listOf(24 * 60, 12 * 60, 60, 30)
    }
}

data class ReminderEvent(
    val id: String,
    val startMillis: Long,
    val section: Int?,
    /** 下课时间；为 null 表示来源没有结束时间，此时不发下课前提醒。 */
    val endMillis: Long? = null,
    /** 通知正文用的课程名；空串表示来源未提供名称。 */
    val name: String = "",
    /** 通知正文用的地点；空串表示未提供。 */
    val place: String = "",
)

/** 提醒相对于课程的位置：上课前或下课前。 */
enum class ReminderPhase { START, END }

data class ReminderDue(
    val eventId: String,
    val lead: Int,
    val fireAtMillis: Long,
    val phase: ReminderPhase = ReminderPhase.START,
) {
    /**
     * 去重键必须带阶段：同一门课「课前 5 分钟」和「下课前 5 分钟」是两个不同提醒，
     * 只用 `event#lead` 会让后一个被前一个挡掉。
     */
    val key: String get() = "$eventId#${phase.name.lowercase()}#$lead"

    fun delayMs(now: Long): Long = (fireAtMillis - now).coerceAtLeast(0L)
}

object ReminderRules {
    const val MAX_LEADS = 3
    private const val MIN_LEAD = 0
    private const val MAX_LEAD = 120

    fun normalize(raw: ReminderRuleSet): ReminderRuleSet? {
        val leads = raw.leads.map(::clipLead).distinct().sorted()
        if (leads.isEmpty() || leads.size > MAX_LEADS) return null
        val overrides = raw.periodOverrides
            .filterKeys { it > 0 }
            .mapValues { (_, lead) -> clipLead(lead) }
        val examLeads = raw.examLeads
            .filter { it in ReminderRuleSet.EXAM_PRESETS }
            .distinct()
            .sorted()
        // 下课前提醒允许为空（用户可关闭），非空时同样受条数与范围约束。
        val endLeads = raw.endLeads.map(::clipLead).distinct().sorted()
        if (endLeads.size > MAX_LEADS) return null
        return ReminderRuleSet(leads, overrides, examLeads, endLeads)
    }

    fun clipLead(minutes: Int): Int = minutes.coerceIn(MIN_LEAD, MAX_LEAD)

    fun encode(rules: ReminderRuleSet): String {
        val overrides = JSONObject()
        rules.periodOverrides.toSortedMap().forEach { (section, lead) ->
            overrides.put(section.toString(), lead)
        }
        return JSONObject()
            .put("leads", JSONArray(rules.leads))
            .put("periodOverrides", overrides)
            .put("examLeads", JSONArray(rules.examLeads))
            .put("endLeads", JSONArray(rules.endLeads))
            .toString()
    }

    fun decode(raw: String?): ReminderRuleSet? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            val leads = json.getJSONArray("leads").toIntList()
            val overridesJson = json.optJSONObject("periodOverrides") ?: JSONObject()
            val overrides = overridesJson.keys().asSequence().mapNotNull { key ->
                key.toIntOrNull()?.let { section -> section to overridesJson.getInt(key) }
            }.toMap()
            val examLeads = json.optJSONArray("examLeads")?.toIntList().orEmpty()
            // 旧快照没有 endLeads：用默认值补齐，与 iOS 的默认行为一致。
            val endLeads = json.optJSONArray("endLeads")?.toIntList()
                ?: ReminderRuleSet.DEFAULT_END_LEADS
            normalize(ReminderRuleSet(leads, overrides, examLeads, endLeads))
        }.getOrNull()
    }

    private fun JSONArray.toIntList(): List<Int> = (0 until length()).map { getInt(it) }
}

class CourseReminderRules(private val preferences: PreferenceStore) {
    fun rules(): ReminderRuleSet {
        ReminderRules.decode(preferences.getString(KEY_RULES))?.let { return it }
        val migrated = ReminderRuleSet(
            leads = listOf(
                ReminderRules.clipLead(
                    preferences.getInt(KEY_MINUTES, ReminderRuleSet.DEFAULT_LEAD_MINUTES)
                )
            )
        )
        saveRules(migrated)
        return migrated
    }

    fun saveRules(rules: ReminderRuleSet): Boolean {
        val normalized = ReminderRules.normalize(rules) ?: return false
        return preferences.edit(
            mapOf(
                KEY_RULES to ReminderRules.encode(normalized),
                KEY_MINUTES to normalized.leads.first(),
            )
        )
    }

    companion object {
        const val KEY_RULES = "rules_v1"
        const val KEY_MINUTES = "minutes"
    }
}

object CourseReminderSchedule {
    internal const val DUE_WINDOW_MS = 2L * 60L * 1000L

    /**
     * 产出某门课的全部候选到期项：课前若干个 + 下课前若干个。
     *
     * 下课前提醒需要结束时间；来源没有 `endMillis` 时整条跳过，而不是用开始时间猜。
     */
    fun candidatesFor(event: ReminderEvent, rules: ReminderRuleSet): List<ReminderDue> {
        val startItems = rules.leadsFor(event.section).map { lead ->
            ReminderDue(event.id, lead, event.startMillis - lead * 60_000L, ReminderPhase.START)
        }
        val endMillis = event.endMillis ?: return startItems
        val endItems = rules.endLeads.map { lead ->
            ReminderDue(event.id, lead, endMillis - lead * 60_000L, ReminderPhase.END)
        }
        return startItems + endItems
    }

    fun nextDue(
        events: List<ReminderEvent>,
        now: Long,
        rules: ReminderRuleSet,
        sentKeys: Set<String>,
    ): ReminderDue? {
        // Do not schedule a wake just to replay an already expired reminder.
        return events.asSequence()
            .flatMap { event ->
                candidatesFor(event, rules).asSequence().map { candidate -> event to candidate }
            }
            .filter { (event, candidate) ->
                val anchor = if (candidate.phase == ReminderPhase.END) event.endMillis else event.startMillis
                anchor != null && candidate.fireAtMillis >= now - DUE_WINDOW_MS
            }
            .map { (_, candidate) -> candidate }
            .filter { it.key !in sentKeys }
            .minWithOrNull(compareBy<ReminderDue> { it.fireAtMillis }.thenBy { it.key })
    }

    fun dueNow(candidates: List<ReminderDue>, now: Long, sentKeys: Set<String>): List<ReminderDue> {
        return candidates
            .filter { it.key !in sentKeys && it.fireAtMillis in (now - DUE_WINDOW_MS)..now }
            .distinctBy { it.key }
    }
}

object ExamReminderSchedule {

    /** 到期窗口：只补发最近这么久内到期的档位，更早的不再打扰。 */
    private const val DUE_WINDOW_MS = 30L * 60L * 1000L
    fun shouldSchedule(leads: List<Int>): Boolean = leads.isNotEmpty()

    fun nextDue(
        exams: List<ReminderEvent>,
        now: Long,
        leads: List<Int>,
        sentKeys: Set<String>,
    ): ReminderDue? {
        if (!shouldSchedule(leads)) return null
        val rules = ReminderRuleSet(leads = listOf(ReminderRuleSet.DEFAULT_LEAD_MINUTES), examLeads = leads)
        return exams.asSequence()
            // 与课程一致：只丢弃考试时间已过的场次，刚到期的那一档应当立刻发。
            .filter { it.startMillis > now }
            .flatMap { exam ->
                rules.examLeads.asSequence().map { lead ->
                    ReminderDue(
                        eventId = exam.id,
                        lead = lead,
                        fireAtMillis = exam.startMillis - lead * 60_000L,
                        phase = ReminderPhase.START,
                    )
                }
            }
            // 已经错过的档位不能再补发：一次性排程链可能在睡眠/断网后恢复，
            // 补发一条「考试还有 1440 分钟」的通知只会误导。
            .filter { it.fireAtMillis > now }
            .filter { it.key !in sentKeys }
            .minWithOrNull(compareBy<ReminderDue> { it.fireAtMillis }.thenBy { it.key })
    }

    /**
     * 到期即发的考试提醒。
     *
     * [nextDue] 给出下一次检查时间（只取未来档位）；这里给出此刻该发的档位，
     * 两者的过滤条件不同，不能互相替代。
     */
    fun dueNow(
        exams: List<ReminderEvent>,
        now: Long,
        leads: List<Int>,
        sentKeys: Set<String>,
    ): List<ReminderDue> {
        if (!shouldSchedule(leads)) return emptyList()
        return exams.asSequence()
            .filter { it.startMillis > now }
            .flatMap { exam ->
                leads.asSequence().map { lead ->
                    ReminderDue(
                        eventId = exam.id,
                        lead = lead,
                        fireAtMillis = exam.startMillis - lead * 60_000L,
                        phase = ReminderPhase.START,
                    )
                }
            }
            // 与 [nextDue] 同一取舍：已经错过太久的档位不补发。
            // 只放行「刚刚到期」的窗口——错过超过一个检查周期（默认 30 分钟）即视为过期。
            .filter { it.fireAtMillis <= now && now - it.fireAtMillis <= DUE_WINDOW_MS }
            .filter { it.key !in sentKeys }
            .distinctBy { it.key }
            .toList()
    }
}
