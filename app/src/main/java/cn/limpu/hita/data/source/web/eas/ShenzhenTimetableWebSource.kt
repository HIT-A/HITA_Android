package cn.limpu.hita.data.source.web.eas

import android.annotation.SuppressLint
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.limpu.hita.data.model.eas.*
import cn.limpu.hita.data.model.eas.CourseItem
import cn.limpu.hita.data.model.eas.CourseScoreItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogItem
import cn.limpu.hita.data.model.eas.ShenzhenCourseCatalogSource
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimeInDay
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.source.web.service.EASService
import cn.limpu.hita.utils.CourseCodeUtils
import cn.limpu.hita.utils.JsonUtils
import cn.limpu.hita.utils.LogUtils
import com.limpu.component.data.DataState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import org.jsoup.Connection

internal class ShenzhenTimetableWebSource(
    private val jwFormPost: (EASToken, String, Map<String, String>, String) -> Connection.Response,
    private val jsonPost: (EASToken, String, String, String) -> Connection.Response,
    private val jwJsonPost: (EASToken, String, String, String) -> Connection.Response,
    private val isJwAuthenticationExpired: (Connection.Response) -> Boolean,
    private val isAuthExpiredResponse: (Connection.Response) -> Boolean,
    private val isAuthExpiredJson: (JSONObject?) -> Boolean,
    private val debugWeek: Int,
    private val debugDow: Int,
) {
    fun getShenzhenCoursePlanningStartDate(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<Calendar>> {
        if (!token.hasShenzhenWebSession()) {
            return MutableLiveData<DataState<Calendar>>(
                DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务")
            )
        }
        val result = MutableLiveData<DataState<Calendar>>()
        Thread {
            try {
                val response = jwFormPost(token, "/component/queryRlZcSj", mapOf("xn" to term.yearCode, "xq" to term.termCode, "djz" to "1"), "/Xsxk/query/1")
                if (isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val date = ShenzhenWebAcademicParser.parseStartDate(response.body())
                if (response.statusCode() != 200 || date == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "未获取到第一教学周日期"))
                    return@Thread
                }
                val calendar = Calendar.getInstance().apply {
                    clear()
                    firstDayOfWeek = Calendar.MONDAY
                    set(date.year, date.monthValue - 1, date.dayOfMonth)
                }
                result.postValue(DataState(calendar, DataState.STATE.SUCCESS))
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    fun getShenzhenCoursePlanningScheduleStructure(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<MutableList<TimePeriodInDay>>> {
        if (!token.hasShenzhenWebSession()) {
            return MutableLiveData<DataState<MutableList<TimePeriodInDay>>>(
                DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务")
            )
        }
        val result = MutableLiveData<DataState<MutableList<TimePeriodInDay>>>()
        Thread {
            try {
                val selectionResponse = requestShenzhenWebSelectedSubjects(token, term)
                if (isJwAuthenticationExpired(selectionResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val selectionSchedule = selectionResponse
                    .takeIf { it.statusCode() == 200 }
                    ?.let { ShenzhenWebAcademicParser.parseScheduleStructure(it.body()) }
                if (!selectionSchedule.isNullOrEmpty()) {
                    result.postValue(DataState(selectionSchedule, DataState.STATE.SUCCESS))
                    return@Thread
                }

                val response = jwFormPost(token, "/component/queryKbjg", mapOf(
                        "xn" to term.yearCode,
                        "xq" to term.termCode,
                        "pylx" to token.getStudentType()
                    ), "/Xsxk/query/1")
                if (isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val schedule = ShenzhenWebAcademicParser.parseScheduleStructure(response.body())
                if (response.statusCode() != 200 || schedule.isNullOrEmpty()) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "节次结构解析失败"))
                } else {
                    result.postValue(DataState(schedule, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    // ================================================================ 已选课程
    fun getSubjectsOfTerm(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<MutableList<TermSubject>>> {
        // Keep the bearer API as the primary source. A remembered Web cookie may already be
        // expired, so it must never replace a working API session for this core query.
        if (ShenzhenCoreDataSourcePolicy.shouldUseWebFallback(token)) {
            return getShenzhenWebSubjects(token, term)
        }
        val res = MutableLiveData<DataState<MutableList<TermSubject>>>()
        Thread {
            val result: MutableList<TermSubject> = ArrayList()
            try {
                val pylxRaw = token.getStudentType()
                val pylxPad = pylxRaw.padStart(2, '0')
                val pylxCandidates = linkedSetOf(pylxRaw, pylxPad)
                val roleCandidates = listOf("01", "06")
                val xnxqCandidates = linkedSetOf(
                    term.getCode(),
                    "${term.yearCode}-${term.termCode}",
                    term.yearCode + term.termCode.padStart(2, '0'),
                    "${term.yearCode}-${term.termCode.padStart(2, '0')}"
                )
                val xkfsCandidates = listOf("yixuan", "")
                var yxkc: org.json.JSONArray? = null
                for (roleHeader in roleCandidates) {
                    for (pylx in pylxCandidates) {
                        for (xnxq in xnxqCandidates) {
                            for (xkfs in xkfsCandidates) {
                                val body =
                                    """{"RoleCode":"$roleHeader","p_pylx":"$pylx","p_xn":"${term.yearCode}","p_xq":"${term.termCode}","p_xnxq":"$xnxq","p_gjz":"","p_kc_gjz":"","p_xkfsdm":"$xkfs"}"""
                                val resp = jsonPost(token, "/app/Xsxk/queryYxkc?_lang=zh_CN", body, roleHeader)
                                val jo = JsonUtils.getJsonObject(resp.body())
                                val list = extractYxkcList(jo)
                                if (list != null && list.length() > 0) {
                                    yxkc = list
                                    break
                                }
                            }
                            if (yxkc != null) break
                        }
                        if (yxkc != null) break
                    }
                    if (yxkc != null) break
                }
                yxkc?.let {
                    for (i in 0 until it.length()) {
                        val subject = it.optJSONObject(i) ?: continue
                        val s = TermSubject()
                        val rawCode = subject.optString("kcdm")
                        s.code = CourseCodeUtils.normalize(rawCode) ?: rawCode
                        s.name = subject.optString("kcmc", "")
                        s.school = subject.optString("kkyxmc")
                        s.teacher = extractSelectedTeacher(subject)
                        s.credit = subject.optString("xf").toFloatOrNull() ?: 0f
                        s.key = subject.optString("id")
                        s.field = optStringFirst(subject, listOf("kclbmc", "KCLBMC"))
                        s.selectCategory = optStringFirst(
                            subject,
                            listOf("rwlxmc", "RWLXMC", "xkfsmc", "XKFSMC", "xklbmc", "XKLBMC")
                        )
                        s.nature = optStringFirst(subject, listOf("kcxzmc", "KCXZMC"))
                        when (subject.optString("kcxzmc")) {
                            "必修" -> s.type = TermSubject.TYPE.COM_A
                            "限选" -> s.type = TermSubject.TYPE.OPT_A
                            "任选" -> s.type = TermSubject.TYPE.OPT_B
                        }
                        val rwlxmc = subject.optString("rwlxmc", "")
                        if (rwlxmc.contains("MOOC", ignoreCase = true))
                            s.type = TermSubject.TYPE.MOOC
                        result.add(s)
                    }
                }
                res.postValue(DataState(result))
            } catch (e: Exception) {
                LogUtils.e("getSubjectsOfTerm: failed, error=${e.message}", e)
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return res
    }

    private fun getShenzhenWebSubjects(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<MutableList<TermSubject>>> {
        val result = MutableLiveData<DataState<MutableList<TermSubject>>>()
        Thread {
            try {
                val response = requestShenzhenWebSelectedSubjects(token, term)
                if (isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val subjects = ShenzhenWebAcademicParser.parseSelectedSubjects(response.body())
                if (response.statusCode() != 200 || subjects == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "已选课程解析失败"))
                } else {
                    result.postValue(DataState(subjects, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    internal fun requestShenzhenWebSelectedSubjects(
        token: EASToken,
        term: TermItem
    ): Connection.Response = jwFormPost(token, "/Xsxk/queryYxkc?sf_request_type=ajax", shenzhenSelectedSubjectsForm(token, term), "/Xsxk/query/1")

    internal fun shenzhenSelectedSubjectsForm(
        token: EASToken,
        term: TermItem
    ): Map<String, String> = mapOf(
            "cxsfmt" to "0",
            "p_pylx" to token.getStudentType(),
            "mxpylx" to token.getStudentType(),
            "p_sfgldjr" to "0",
            "p_sfredis" to "0",
            "p_sfsyxkgwc" to "0",
            "p_xktjz" to "",
            "p_chaxunxh" to "",
            "p_gjz" to "",
            "p_skjs" to "",
            "p_xn" to term.yearCode,
            "p_xq" to term.termCode,
            "p_xnxq" to term.getCode(),
            "p_xkfsdm" to "yixuan",
            "p_xiaoqu" to "",
            "p_kkyx" to "",
            "p_kclb" to "",
            "p_xkxs" to "",
            "p_dyc" to "",
            "p_kkxnxq" to "",
            "p_id" to "",
            "p_sfhlctkc" to "0",
            "p_sfhllrlkc" to "0",
            "p_kxsj_xqj" to "",
            "p_kxsj_ksjc" to "",
            "p_kxsj_jsjc" to "",
            "p_kcdm_js" to "",
            "p_kcdm_cxrw" to "",
            "p_kcdm_cxrw_zckc" to "",
            "p_kc_gjz" to "",
            "p_xzcxtjz_nj" to "",
            "p_xzcxtjz_yx" to "",
            "p_xzcxtjz_zy" to "",
            "p_xzcxtjz_zyfx" to "",
            "p_xzcxtjz_bj" to "",
            "p_sfxsgwckb" to "1",
            "p_skyy" to "",
            "p_sfmxzj" to "",
            "p_chaxunxkfsdm" to "",
            "pageNum" to "1",
            "pageSize" to "200"
        )

    fun getShenzhenSelectedCourses(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<List<ShenzhenCourseCatalogItem>>> {
        val result = MutableLiveData<DataState<List<ShenzhenCourseCatalogItem>>>(
            DataState(DataState.STATE.NOTHING)
        )
        Thread {
            if (!token.hasShenzhenWebSession()) {
                result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "请先连接深圳 Web 教务"))
                return@Thread
            }
            runCatching {
                val response = requestShenzhenWebSelectedSubjects(token, term)
                if (isJwAuthenticationExpired(response)) {
                    error("深圳 Web 会话已失效")
                }
                check(response.statusCode() == 200) { "已选课程读取失败" }
                ShenzhenCourseCatalogParser.parsePage(
                    response.body(),
                    ShenzhenCourseCatalogSource.AVAILABLE,
                    token.getStudentType()
                )?.items ?: error("已选课程解析失败")
            }.onSuccess {
                result.postValue(DataState(it, DataState.STATE.SUCCESS))
            }.onFailure { error ->
                val state = if (error.message?.contains("会话已失效") == true) {
                    DataState.STATE.NOT_LOGGED_IN
                } else {
                    DataState.STATE.FETCH_FAILED
                }
                result.postValue(DataState(state, error.message))
            }
        }.start()
        return result
    }

    // ================================================================ 周课表（按周矩阵：/app/Kbcx/query）
    fun getTimetableOfTerm(
        term: TermItem,
        token: EASToken
    ): LiveData<DataState<List<CourseItem>>> {
        if (token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()) {
            return getShenzhenWebTimetable(token, term)
        }
        val res = MutableLiveData<DataState<List<CourseItem>>>()
        Thread {
            try {
                // 策略1：先尝试 querykbrczong 总览接口
                val zongResult = fetchTimetableFromOverview(token, term)
                if (!zongResult.isNullOrEmpty()) {
                    LogUtils.d("getTimetableOfTerm: using overview result, count=${zongResult.size}")
                    res.postValue(DataState(zongResult, DataState.STATE.SUCCESS))
                    return@Thread
                }

                // 策略2：总览没结果，走 Kbcx/query + querykbrcbyday 周表路径
                LogUtils.d("getTimetableOfTerm: overview empty, falling back to week schedule")
                val merged = linkedMapOf<String, CourseItem>()
                val selectedCourseNameByCode = fetchSelectedCourseNameByCodeSync(token, term)
                val dayScheduleCache = java.util.concurrent.ConcurrentHashMap<String, List<DayScheduleItem>>()
                val pool = java.util.concurrent.Executors.newFixedThreadPool(4)

                // 收集所有需要富化的日期，然后并行请求
                val enrichmentNeeded = java.util.concurrent.ConcurrentLinkedQueue<String>()
                data class WeekData(val week: Int, val kcxxList: org.json.JSONArray, val weekDates: List<String>)
                val allWeekData = mutableListOf<WeekData>()

                // 并行获取所有周数据
                val weekFutures = (1..25).map { week ->
                    pool.submit(java.util.concurrent.Callable<WeekData?> {
                        val kbBody = """{"xn":"${term.yearCode}","xq":"${term.termCode}","zc":"$week","type":"json"}"""
                        val kbResp = jsonPost(token, "/app/Kbcx/query", kbBody, "06")
                        val kbJo = JsonUtils.getJsonObject(kbResp.body()) ?: return@Callable null
                        if (kbJo.optInt("code", -1) != 200) return@Callable null

                        val contentArr = kbJo.optJSONArray("content") ?: return@Callable null
                        val kcxxList = extractKcxxListFromKbcxContent(contentArr)
                        val weekDates = extractWeekDatesFromKbcx(contentArr)
                        if (kcxxList.length() == 0) {
                            return@Callable null
                        }
                        WeekData(week, kcxxList, weekDates)
                    })
                }

                val fetchedWeekData = weekFutures.mapNotNull { it.get() }.sortedBy { it.week }

                // 第一遍：收集需要富化的日期
                for (wd in fetchedWeekData) {
                    val weekDates = wd.weekDates
                    for (i in 0 until wd.kcxxList.length()) {
                        val kc = wd.kcxxList.optJSONObject(i) ?: continue
                        val kbxx = kc.optString("KBXX", "")
                        if (kbxx.contains("...") || kbxx.contains("[") || kbxx.contains("【实验】")) {
                            val dow = kc.optInt("XQJ", -1)
                            val dateForDow = weekDates.getOrNull(dow - 1)
                            if (!dateForDow.isNullOrBlank() && !dayScheduleCache.containsKey(dateForDow)) {
                                enrichmentNeeded.add(dateForDow)
                            }
                        }
                    }
                    allWeekData.add(wd)
                }

                // 并行请求所有需要富化的日期
                if (enrichmentNeeded.isNotEmpty()) {
                    LogUtils.d("getTimetableOfTerm: fetching ${enrichmentNeeded.size} day schedules in parallel")
                    val futures = enrichmentNeeded.distinct().map { date ->
                        pool.submit(java.util.concurrent.Callable<Unit> {
                            dayScheduleCache[date] = fetchDaySchedule(token, date)
                        })
                    }
                    futures.forEach { it.get() }
                }

                // 第二遍：解析课程数据（日课表已在缓存中）
                for (wd in allWeekData) {
                    val week = wd.week
                    val kcxxList = wd.kcxxList
                    val weekDates = wd.weekDates
                    val debugWeekRows = mutableListOf<CourseItem>()
                    for (i in 0 until kcxxList.length()) {
                        val kc = kcxxList.optJSONObject(i) ?: continue
                        val dow = kc.optInt("XQJ", -1)
                        val ksjc = kc.optInt("KSJC", -1)
                        val jsjc = kc.optInt("JSJC", -1)
                        if (dow !in 1..7 || ksjc <= 0) continue

                        val kbxx = kc.optString("KBXX", "")
                        val rawCode = kc.optString("KCDM", "")
                        val code = CourseCodeUtils.normalize(rawCode) ?: rawCode
                        val fallbackName = normalizedCourseName(kbxx)
                        if (fallbackName.isBlank()) continue

                        val classroom = extractClassroomFromKbxx(kbxx) ?: ""
                        val sessionHint = extractSessionHint(kbxx)

                        // 只在 KBXX 包含 ... 或 [] 时才查日课表
                        val needsEnrichment = kbxx.contains("...") || kbxx.contains("[")
                        val dateForDow = weekDates.getOrNull(dow - 1)
                        val canonicalName = if (needsEnrichment && !dateForDow.isNullOrBlank()) {
                            val daySchedules = dayScheduleCache[dateForDow]
                            if (daySchedules != null) bestDayScheduleRawName(daySchedules, dow, ksjc, jsjc, classroom) else null
                        } else null

                        val displayName = composeCourseDisplayName(canonicalName, fallbackName, sessionHint)

                        val teacher = extractTeacher(kc, displayName, kbxx)
                        val last = if (jsjc >= ksjc) jsjc - ksjc + 1 else 1

                        val course = CourseItem().apply {
                            this.code = code
                            this.name = displayName
                            this.rawName = displayName
                            this.teacher = teacher
                            this.classroom = classroom
                            this.dow = dow
                            this.begin = ksjc
                            this.last = last
                            this.weeks = mutableListOf(week)
                        }
                        if (week == debugWeek && dow == debugDow) {
                            debugWeekRows.add(copyCourse(course))
                        }
                        upsertCourse(merged, course)
                    }
                    if (week == debugWeek) {
                        val summary = debugWeekRows
                            .sortedWith(compareBy<CourseItem> { it.dow }.thenBy { it.begin })
                            .joinToString(" || ") { debugCourseIdentity(it) }
                        LogUtils.d("[DBG] raw week=$week dow=$debugDow rows=${debugWeekRows.size} term=${term.getCode()} -> $summary")
                    }
                }
                pool.shutdown()

                val result = merged.values.toMutableList()
                result.forEach { it.weeks = it.weeks.distinct().sorted().toMutableList() }
                result.sortWith(compareBy<CourseItem> { it.dow }.thenBy { it.begin }.thenBy { it.name })
                val debugBeforeMerge = result
                    .filter { it.dow == debugDow && it.weeks.contains(debugWeek) }
                    .sortedWith(compareBy<CourseItem> { it.begin }.thenBy { it.name })
                LogUtils.d(
                    "[DBG] dedup week=$debugWeek dow=$debugDow count=${debugBeforeMerge.size} term=${term.getCode()} -> " +
                        debugBeforeMerge.joinToString(" || ") { debugCourseIdentity(it) }
                )
                val mergedAdjacent = mergeAdjacentCourses(result)
                val debugAfterMerge = mergedAdjacent
                    .filter { it.dow == debugDow && it.weeks.contains(debugWeek) }
                    .sortedWith(compareBy<CourseItem> { it.begin }.thenBy { it.name })
                LogUtils.d(
                    "[DBG] merged week=$debugWeek dow=$debugDow count=${debugAfterMerge.size} term=${term.getCode()} -> " +
                        debugAfterMerge.joinToString(" || ") { debugCourseIdentity(it) }
                )

                if (mergedAdjacent.isEmpty()) {
                    res.postValue(DataState(DataState.STATE.FETCH_FAILED, "未获取到课表数据"))
                } else {
                    res.postValue(DataState(mergedAdjacent, DataState.STATE.SUCCESS))
                }
            } catch (e: Exception) {
                LogUtils.e("getTimetableOfTerm: failed, error=${e.message}", e)
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return res
    }

    private fun getShenzhenWebTimetable(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<List<CourseItem>>> {
        val result = MutableLiveData<DataState<List<CourseItem>>>()
        Thread {
            try {
                val timetableResponse = jwFormPost(token, "/xszykb/queryxszykbzong", mapOf("xn" to term.yearCode, "xq" to term.termCode), "/xszykb/queryxszykb")
                if (isJwAuthenticationExpired(timetableResponse)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val subjectResponse = requestShenzhenWebSelectedSubjects(token, term)
                val subjects = if (isJwAuthenticationExpired(subjectResponse)) {
                    emptyList()
                } else {
                    ShenzhenWebAcademicParser.parseSelectedSubjects(subjectResponse.body()).orEmpty()
                }
                val courses = ShenzhenWebAcademicParser.parseTimetable(timetableResponse.body(), subjects)
                if (timetableResponse.statusCode() != 200 || courses == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "个人课表解析失败"))
                } else {
                    result.postValue(DataState(courses, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    private fun extractKcxxListFromKbcxContent(contentArr: org.json.JSONArray): org.json.JSONArray {
        for (i in 0 until contentArr.length()) {
            val obj = contentArr.optJSONObject(i) ?: continue
            val arr = obj.optJSONArray("kcxxList") ?: continue
            return arr
        }
        return org.json.JSONArray()
    }

    private fun extractWeekDatesFromKbcx(contentArr: org.json.JSONArray): List<String> {
        val dates = mutableListOf<String>()
        for (i in 0 until contentArr.length()) {
            val obj = contentArr.optJSONObject(i) ?: continue
            val rqList = obj.optJSONArray("rqList") ?: continue
            for (j in 0 until rqList.length()) {
                val rq = rqList.optJSONObject(j)?.optString("RQ")?.trim().orEmpty()
                if (rq.isNotBlank()) dates.add(rq)
            }
        }
        return dates
    }

    private fun fetchSelectedCourseNameByCodeSync(
        token: EASToken,
        term: TermItem
    ): Map<String, String> {
        return runCatching {
            val mapping = linkedMapOf<String, String>()
            val pylxRaw = token.getStudentType()
            val pylxPad = pylxRaw.padStart(2, '0')
            val pylxCandidates = linkedSetOf(pylxRaw, pylxPad)
            val roleCandidates = listOf("01", "06")
            val xnxqCandidates = linkedSetOf(
                term.getCode(),
                "${term.yearCode}-${term.termCode}",
                term.yearCode + term.termCode.padStart(2, '0'),
                "${term.yearCode}-${term.termCode.padStart(2, '0')}"
            )
            val xkfsCandidates = listOf("yixuan", "")

            var yxkc: org.json.JSONArray? = null
            for (roleHeader in roleCandidates) {
                for (pylx in pylxCandidates) {
                    for (xnxq in xnxqCandidates) {
                        for (xkfs in xkfsCandidates) {
                            val body =
                                """{"RoleCode":"$roleHeader","p_pylx":"$pylx","p_xn":"${term.yearCode}","p_xq":"${term.termCode}","p_xnxq":"$xnxq","p_gjz":"","p_kc_gjz":"","p_xkfsdm":"$xkfs"}"""
                            val resp = jsonPost(token, "/app/Xsxk/queryYxkc?_lang=zh_CN", body, roleHeader)
                            val jo = JsonUtils.getJsonObject(resp.body())
                            val list = extractYxkcList(jo)
                            if (list != null && list.length() > 0) {
                                yxkc = list
                                break
                            }
                        }
                        if (yxkc != null) break
                    }
                    if (yxkc != null) break
                }
                if (yxkc != null) break
            }

            yxkc?.let { list ->
                for (i in 0 until list.length()) {
                    val subject = list.optJSONObject(i) ?: continue
                    val rawCode = subject.optString("kcdm").trim()
                    val normalizedCode = CourseCodeUtils.normalize(rawCode)
                    val name = subject.optString("kcmc", "").trim()
                    if (name.isBlank()) continue
                    if (!normalizedCode.isNullOrBlank()) {
                        mapping[normalizedCode] = name
                    }
                    if (rawCode.isNotBlank()) {
                        mapping[rawCode] = name
                    }
                }
            }

            mapping
        }.getOrDefault(emptyMap())
    }

    private fun resolveTimetableCourseName(
        rawName: String,
        code: String,
        selectedCourseNameByCode: Map<String, String>
    ): String {
        if (!rawName.contains("...")) return rawName
        val normalizedCode = CourseCodeUtils.normalize(code) ?: code
        if (normalizedCode.isBlank()) return rawName
        return selectedCourseNameByCode[normalizedCode] ?: selectedCourseNameByCode[code] ?: rawName
    }

    private fun extractCourseNameFromKbxx(kbxx: String): String {
        if (kbxx.isBlank()) return ""
        return kbxx
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    internal fun extractClassroomFromKbxx(kbxx: String): String? {
        if (kbxx.isBlank()) return null
        val lines = kbxx.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        val bracket = Regex("\\[([^\\]]+)]")
        for (line in lines.asReversed()) {
            val hit = bracket.find(line)?.groupValues?.getOrNull(1)?.trim()
            if (!hit.isNullOrBlank()) return hit
        }
        return null
    }

    @SuppressLint("SimpleDateFormat")
    private fun getTermStartDateSyncByKbcx(token: EASToken, term: TermItem): Calendar {
        val calendar = Calendar.getInstance()
        calendar.firstDayOfWeek = Calendar.MONDAY
        try {
            val weekBody = """{"xn":"${term.yearCode}","xq":"${term.termCode}","zc":"1","type":"json"}"""
            val weekResp = jsonPost(token, "/app/Kbcx/query", weekBody, "06")
            val weekJo = JsonUtils.getJsonObject(weekResp.body())
            val contentArr = weekJo?.optJSONArray("content")
            val df = SimpleDateFormat("yyyy-MM-dd")
            if (contentArr != null) {
                for (i in 0 until contentArr.length()) {
                    val obj = contentArr.optJSONObject(i) ?: continue
                    val rqList = obj.optJSONArray("rqList") ?: continue
                    val firstDay = rqList.optJSONObject(0) ?: continue
                    val rq = firstDay.optString("RQ")
                    if (rq.isNotEmpty()) {
                        val parsed = df.parse(rq)
                        if (parsed != null) calendar.timeInMillis = parsed.time
                    }
                    break
                }
            }
        } catch (_: Exception) {
        }
        return calendar
    }

    private fun dayOfWeekFromDate(date: String): Int {
        return try {
            val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val c = Calendar.getInstance()
            c.time = df.parse(date) ?: return -1
            val dow = c.get(Calendar.DAY_OF_WEEK)
            if (dow == Calendar.SUNDAY) 7 else dow - 1
        } catch (_: Exception) {
            -1
        }
    }

    private fun weekIndexFromDate(termStart: Calendar, date: String): Int {
        return try {
            val df = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val target = Calendar.getInstance().apply {
                time = df.parse(date) ?: return -1
            }
            val start = Calendar.getInstance().apply {
                timeInMillis = termStart.timeInMillis
            }
            val diffDays = ((target.timeInMillis - start.timeInMillis) / (24L * 60L * 60L * 1000L)).toInt()
            if (diffDays < 0) -1 else diffDays / 7 + 1
        } catch (_: Exception) {
            -1
        }
    }

    private fun parseXszykbArray(raw: String): org.json.JSONArray? {
        if (raw.isBlank()) return null
        JsonUtils.getJsonArray(raw)?.let { return it }
        val jo = JsonUtils.getJsonObject(raw) ?: return null
        val keys = listOf("content", "data", "rows", "list", "result")
        for (key in keys) {
            val arr = jo.optJSONArray(key)
            if (arr != null) return arr
        }
        val dataObj = jo.optJSONObject("data")
        if (dataObj != null) {
            for (key in keys) {
                val arr = dataObj.optJSONArray(key)
                if (arr != null) return arr
            }
        }
        return null
    }

    private fun upsertCourse(target: MutableMap<String, CourseItem>, incoming: CourseItem) {
        val key = listOf(
            incoming.name.orEmpty().trim(),
            incoming.dow.toString(),
            incoming.begin.toString(),
            incoming.last.toString(),
            incoming.teacher.orEmpty().trim(),
            incoming.classroom.orEmpty().trim()
        ).joinToString("|")

        val existing = target[key]
        if (existing == null) {
            target[key] = incoming
            return
        }
        for (w in incoming.weeks) {
            if (!existing.weeks.contains(w)) existing.weeks.add(w)
        }
        if (existing.code.isNullOrBlank() && !incoming.code.isNullOrBlank()) {
            existing.code = incoming.code
        }
    }

    private fun mergeAdjacentCourses(courses: List<CourseItem>): List<CourseItem> {
        if (courses.isEmpty()) return courses

        var current = courses.map { copyCourse(it) }
        repeat(6) {
            val (next, changed) = mergeAdjacentCoursesSinglePass(current)
            current = next
            if (!changed) {
                return current.sortedWith(
                    compareBy<CourseItem> { it.dow }
                        .thenBy { it.begin }
                        .thenBy { normalized(it.name) }
                        .thenBy { it.weeks.sorted().joinToString(",") }
                )
            }
        }

        return current.sortedWith(
            compareBy<CourseItem> { it.dow }
                .thenBy { it.begin }
                .thenBy { normalized(it.name) }
                .thenBy { it.weeks.sorted().joinToString(",") }
        )
    }

    private fun mergeAdjacentCoursesSinglePass(courses: List<CourseItem>): Pair<List<CourseItem>, Boolean> {
        val sorted = courses.sortedWith(
            compareBy<CourseItem> { it.dow }
                .thenBy { it.begin }
                .thenBy { normalized(it.name) }
                .thenBy { normalized(it.teacher) }
                .thenBy { it.weeks.sorted().joinToString(",") }
        )

        if (sorted.isEmpty()) return Pair(emptyList(), false)

        val used = BooleanArray(sorted.size)
        val merged = mutableListOf<CourseItem>()
        val residualCourses = mutableListOf<CourseItem>()
        var changed = false

        for (index in sorted.indices) {
            if (used[index]) continue
            val base = copyCourse(sorted[index])
            used[index] = true

            while (true) {
                val expectedBegin = base.begin + base.last
                var mergedIndex = -1

                for (candidateIndex in (index + 1) until sorted.size) {
                    if (used[candidateIndex]) continue
                    val candidate = sorted[candidateIndex]

                    if (candidate.dow != base.dow) {
                        if (candidate.dow > base.dow) break
                        continue
                    }
                    if (candidate.begin < expectedBegin) continue
                    if (candidate.begin > expectedBegin) break

                    val weekSplitMerged = tryMergeWithWeekSplit(base, candidate, residualCourses)
                    if (weekSplitMerged) {
                        mergedIndex = candidateIndex
                        break
                    }

                    val canMerge = canMergeCourses(base, candidate)
                    if (canMerge) {
                        base.last += candidate.last
                        if (base.classroom.isNullOrBlank()) {
                            base.classroom = candidate.classroom
                        }
                        if (base.code.isNullOrBlank()) {
                            base.code = candidate.code
                        }
                        mergedIndex = candidateIndex
                        break
                    }

                    if (shouldDebug(base, candidate)) {
                        LogUtils.d(
                            "[DBG] no-merge week=$debugWeek dow=$debugDow reason=${mergeBlockReason(base, candidate)} left=${debugCourseIdentity(base)} right=${debugCourseIdentity(candidate)}"
                        )
                    }
                }

                if (mergedIndex == -1) break
                used[mergedIndex] = true
                changed = true
            }

            merged.add(base)
        }

        if (residualCourses.isNotEmpty()) {
            merged.addAll(residualCourses)
            changed = true
        }

        return Pair(merged, changed)
    }


    private fun tryMergeWithWeekSplit(
        left: CourseItem,
        right: CourseItem,
        residualCourses: MutableList<CourseItem>
    ): Boolean {
        if (!canMergeBaseIgnoringWeeks(left, right)) return false

        val leftWeeks = left.weeks.distinct().sorted()
        val rightWeeks = right.weeks.distinct().sorted()
        if (leftWeeks == rightWeeks) return false

        val sharedWeeks = leftWeeks.intersect(rightWeeks.toSet()).sorted()
        if (sharedWeeks.isEmpty()) return false

        val leftOnlyWeeks = leftWeeks.filter { it !in sharedWeeks }
        val rightOnlyWeeks = rightWeeks.filter { it !in sharedWeeks }

        if (leftOnlyWeeks.isNotEmpty()) {
            residualCourses.add(copyCourse(left).apply {
                weeks = leftOnlyWeeks.toMutableList()
            })
        }
        if (rightOnlyWeeks.isNotEmpty()) {
            residualCourses.add(copyCourse(right).apply {
                weeks = rightOnlyWeeks.toMutableList()
            })
        }

        left.weeks = sharedWeeks.toMutableList()
        left.last += right.last
        if (left.classroom.isNullOrBlank()) {
            left.classroom = right.classroom
        }
        if (left.code.isNullOrBlank()) {
            left.code = right.code
        }

        if (shouldDebug(left, right)) {
            LogUtils.d(
                "[DBG] week-split-merge shared=$sharedWeeks leftOnly=$leftOnlyWeeks rightOnly=$rightOnlyWeeks left=${debugCourseIdentity(left)} right=${debugCourseIdentity(right)}"
            )
        }

        return true
    }

    private fun canMergeCourses(left: CourseItem, right: CourseItem): Boolean {
        if (!canMergeBaseIgnoringWeeks(left, right)) return false
        return left.weeks.distinct().sorted() == right.weeks.distinct().sorted()
    }

    private fun canMergeBaseIgnoringWeeks(left: CourseItem, right: CourseItem): Boolean {
        if (left.dow != right.dow) return false
        if (!sameCourseIdentity(left, right)) return false
        if (!teacherCompatible(left, right)) return false

        val leftEndPeriod = left.begin + left.last - 1
        if (leftEndPeriod + 1 != right.begin) return false

        val leftClassroom = normalized(left.classroom)
        val rightClassroom = normalized(right.classroom)
        if (leftClassroom.isNotEmpty() && rightClassroom.isNotEmpty() && leftClassroom != rightClassroom) {
            return false
        }

        val leftCode = normalized(left.code)
        val rightCode = normalized(right.code)
        if (leftCode.isNotEmpty() && rightCode.isNotEmpty() && leftCode != rightCode) {
            return false
        }

        return true
    }

    private fun sameCourseIdentity(left: CourseItem, right: CourseItem): Boolean {
        val leftName = normalized(left.name)
        val rightName = normalized(right.name)
        if (leftName == rightName) return true

        val leftCode = normalized(left.code)
        val rightCode = normalized(right.code)
        if (leftCode.isNotEmpty() && rightCode.isNotEmpty() && leftCode == rightCode) {
            return true
        }

        return false
    }


    private fun teacherCompatible(left: CourseItem, right: CourseItem): Boolean {
        val leftTeacher = normalizedTeacher(left.teacher)
        val rightTeacher = normalizedTeacher(right.teacher)
        if (leftTeacher.isEmpty() || rightTeacher.isEmpty()) return true
        if (leftTeacher == rightTeacher) return true
        if (leftTeacher.contains(rightTeacher) || rightTeacher.contains(leftTeacher)) return true

        val leftCode = normalized(left.code)
        val rightCode = normalized(right.code)
        if (leftCode.isNotEmpty() && rightCode.isNotEmpty() && leftCode == rightCode) {
            return true
        }

        return false
    }

    private fun normalized(value: String?): String {
        return value
            ?.replace("\u00A0", " ")
            ?.replace(Regex("\\s+"), "")
            ?.trim()
            .orEmpty()
    }

    private fun normalizedTeacher(value: String?): String {
        val raw = value
            ?.replace("\u00A0", " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        if (raw.isEmpty()) return ""

        val tokens = raw
            .split(Regex("[、,，/|；;]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { token ->
                token
                    .replace(Regex("^(教师|老师|Teacher)[:： ]*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\(.*?\\)|（.*?）"), "")
                    .replace(Regex("\\p{Cntrl}"), "")
                    .trim()
            }
            .filterNot { token ->
                token.isBlank() ||
                    token.contains("周") ||
                    token.contains("节") ||
                    token.contains("星期") ||
                    token.contains("课程") ||
                    token.contains("上课") ||
                    token.contains("教室") ||
                    token.contains("地点") ||
                    token.any { it.isDigit() }
            }
            .distinct()
            .sorted()

        if (tokens.isNotEmpty()) {
            return tokens.joinToString("|")
        }

        return normalized(raw)
    }

    private fun copyCourse(source: CourseItem): CourseItem {
        return CourseItem().apply {
            code = source.code
            name = source.name
            weeks = source.weeks.toMutableList()
            teacher = source.teacher
            classroom = source.classroom
            dow = source.dow
            begin = source.begin
            last = source.last
        }
    }

    private fun shouldDebug(left: CourseItem, right: CourseItem): Boolean {
        if (left.dow != debugDow || right.dow != debugDow) return false
        if (!left.weeks.contains(debugWeek) || !right.weeks.contains(debugWeek)) return false
        return true
    }

    private fun mergeBlockReason(left: CourseItem, right: CourseItem): String {
        if (left.dow != right.dow) return "dow"
        if (!sameCourseIdentity(left, right)) return "identity"
        if (!teacherCompatible(left, right)) return "teacher"
        if (left.weeks.distinct().sorted() != right.weeks.distinct().sorted()) return "weeks"

        val leftEndPeriod = left.begin + left.last - 1
        if (leftEndPeriod + 1 != right.begin) return "period"

        val leftClassroom = normalized(left.classroom)
        val rightClassroom = normalized(right.classroom)
        if (leftClassroom.isNotEmpty() && rightClassroom.isNotEmpty() && leftClassroom != rightClassroom) {
            return "classroom"
        }

        val leftCode = normalized(left.code)
        val rightCode = normalized(right.code)
        if (leftCode.isNotEmpty() && rightCode.isNotEmpty() && leftCode != rightCode) {
            return "code"
        }

        return "unknown"
    }

    private fun debugCourseIdentity(course: CourseItem): String {
        return "name=${course.name.orEmpty()} begin=${course.begin} last=${course.last} weeks=${course.weeks.sorted()} teacher=${course.teacher.orEmpty()} classroom=${course.classroom.orEmpty()} code=${course.code.orEmpty()}"
    }

    private fun parseXszykbCourseRow(row: JSONObject, weekHint: Int?): CourseItem? {
        val ksjc = row.optInt("KSJC", -1)
        val jsjc = row.optInt("JSJC", -1)
        if (ksjc <= 0) return null
        val key = row.optString("KEY", "")
        val dow = Regex("xq(\\d+)_", RegexOption.IGNORE_CASE)
            .find(key)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: -1
        if (dow !in 1..7) return null

        val sksj = row.optString("SKSJ", "").trim()
        val name = extractXszykbName(sksj)
        if (name.isBlank()) return null

        val (teacher, classroom) = extractTeacherAndClassroomFromSksj(sksj)
        val codeRaw = extractCourseCodeFromRwh(row.optString("RWH", ""))
        val code = CourseCodeUtils.normalize(codeRaw) ?: codeRaw

        val weeks = parseWeeksFromZc(row.optString("ZC", ""), weekHint)
        val effectiveJsjc = if (jsjc >= ksjc) jsjc else ksjc

        return CourseItem().apply {
            this.code = code
            this.name = name
            this.teacher = teacher
            this.classroom = classroom
            this.dow = dow
            this.begin = ksjc
            this.last = (effectiveJsjc - ksjc + 1).coerceAtLeast(1)
            this.weeks = weeks.toMutableList()
        }
    }

    private fun extractXszykbName(sksj: String): String {
        if (sksj.isBlank()) return ""
        return sksj
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?.substringBefore("备注:")
            ?.trim()
            .orEmpty()
    }

    private fun extractTeacherAndClassroomFromSksj(sksj: String): Pair<String?, String?> {
        if (sksj.isBlank()) return Pair(null, null)
        val bracketParts = Regex("\\[([^\\]]+)]")
            .findAll(sksj)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()

        var teacher: String? = null
        var classroom: String? = null
        for (part in bracketParts) {
            val isWeekLike = part.contains("周") || part.contains("Week", ignoreCase = true)
            val isPeriodLike = part.contains("节")
            if (isWeekLike || isPeriodLike) continue
            if (classroom == null && looksLikeClassroom(part)) {
                classroom = part
                continue
            }
            if (teacher == null && !looksLikeClassroom(part)) {
                teacher = part
            }
        }
        return Pair(teacher, classroom)
    }

    private fun looksLikeClassroom(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.contains("田径场") || text.contains("实验室") || text.contains("教室")) return true
        if (text.matches(Regex("^[A-Za-z]\\d{2,}.*$"))) return true
        if (text.matches(Regex("^[TGHKAtghka]?\\d{3,}.*$"))) return true
        if (text.contains("楼") || text.contains("馆") || text.contains("场")) return true
        return false
    }

    private fun extractCourseCodeFromRwh(rwh: String): String {
        if (rwh.isBlank()) return ""
        val parts = rwh.split("-")
        if (parts.size >= 5) return parts[3].trim()
        return ""
    }

    private fun parseWeeksFromZc(zc: String, weekHint: Int?): List<Int> {
        if (zc.isNotBlank()) {
            val weeks = mutableListOf<Int>()
            for (i in 1 until zc.length) {
                if (zc[i] == '1') weeks.add(i)
            }
            if (weeks.isNotEmpty()) return weeks
        }
        return if (weekHint != null) listOf(weekHint) else emptyList()
    }

    private fun extractTeacher(kc: org.json.JSONObject, courseName: String?, kbxx: String): String? {
        val keys = listOf("SKJS", "JSXM", "RKJS", "JS", "JSMC", "JSMC1", "JSMC2")
        for (key in keys) {
            val v = kc.optString(key, "").trim()
            if (v.isNotEmpty()) return v
        }
        val lines = kbxx.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.contains("[") && it.contains("]") }
        val cleaned = if (courseName.isNullOrBlank()) {
            lines
        } else {
            lines.filterNot { it.replace(" ", "") == courseName.replace(" ", "") }
        }
        if (cleaned.isNotEmpty()) {
            val raw = cleaned.joinToString(" / ")
            return raw.replace(Regex("^(教师|老师|Teacher)[:： ]*"), "").trim()
        }
        return null
    }

    private fun extractSelectedTeacher(subject: org.json.JSONObject): String? {
        val keys = listOf(
            "DGJSMC", "dgjsmc",
            "SKJS", "SKJSXM", "SKJSMC", "SKJSXX",
            "JSXM", "JSMC", "RKJS", "JS",
            "skjs", "skjsxm", "skjsmc", "skjsxx",
            "jsxm", "jsmc", "rkjs", "js"
        )
        for (key in keys) {
            val v = subject.optString(key, "").trim()
            if (v.isNotEmpty()) return v
        }
        return null
    }

    private fun optStringFirst(subject: org.json.JSONObject, keys: List<String>): String {
        for (key in keys) {
            val v = subject.optString(key, "").trim()
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun extractYxkcList(jo: JSONObject?): org.json.JSONArray? {
        if (jo == null) return null
        val keys = listOf("yxkcList", "content", "data", "rows", "list")
        for (key in keys) {
            val arr = jo.optJSONArray(key)
            if (arr != null && arr.length() > 0) return arr
        }
        val dataObj = jo.optJSONObject("data")
        if (dataObj != null) {
            for (key in keys) {
                val arr = dataObj.optJSONArray(key)
                if (arr != null && arr.length() > 0) return arr
            }
        }
        val contentObj = jo.optJSONObject("content")
        if (contentObj != null) {
            val it = contentObj.keys()
            while (it.hasNext()) {
                val key = it.next()
                val arr = contentObj.optJSONArray(key) ?: continue
                if (arr.length() == 0) continue
                if (key.contains("yxkc", ignoreCase = true)) {
                    return arr
                }
                val first = arr.optJSONObject(0)
                if (first == null) return arr
                if (first.has("kcmc") || first.has("kcdm") || first.has("dgjsmc") || first.has("DGJSMC")) {
                    return arr
                }
                val scanLimit = minOf(arr.length(), 5)
                for (i in 1 until scanLimit) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (obj.has("kcmc") || obj.has("kcdm") || obj.has("dgjsmc") || obj.has("DGJSMC")) {
                        return arr
                    }
                }
            }
        }
        return null
    }

    internal fun buildTermDisplayName(yearName: String?, termName: String?): String {
        val year = yearName?.trim().orEmpty()
        val term = termName?.trim().orEmpty()
        if (year.isEmpty()) return term
        if (term.isEmpty()) return year
        return if (term.contains(year)) term else "$year $term"
    }

    // ================================================================ 课表时间结构
    @SuppressLint("SimpleDateFormat")
    fun getScheduleStructure(
        term: TermItem,
        isUndergraduate: Boolean?,
        token: EASToken
    ): LiveData<DataState<MutableList<TimePeriodInDay>>> {
        if (token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()) {
            return getShenzhenWebScheduleStructure(token, term)
        }
        val res = MutableLiveData<DataState<MutableList<TimePeriodInDay>>>()
        Thread {
            try {
                val kbBody = """{"xn":"${term.yearCode}","xq":"${term.termCode}","zc":"1","type":"json"}"""
                val kbResp = jsonPost(token, "/app/Kbcx/query", kbBody, "06")
                val kbJo = JsonUtils.getJsonObject(kbResp.body())
                val contentArr = kbJo?.optJSONArray("content")
                val df = SimpleDateFormat("HH:mm", Locale.getDefault())
                val slots: MutableList<TimePeriodInDay?> = mutableListOf()
                if (contentArr != null) {
                    for (i in 0 until contentArr.length()) {
                        val obj = contentArr.optJSONObject(i) ?: continue
                        val jcList = obj.optJSONArray("jcList") ?: continue
                        var maxPeriod = 0
                        for (j in 0 until jcList.length()) {
                            val jc = jcList.optJSONObject(j) ?: continue
                            val jsjc = jc.optInt("JSJC", 0)
                            if (jsjc > maxPeriod) maxPeriod = jsjc
                        }
                        if (maxPeriod <= 0) continue
                        val defaults = defaultScheduleStructure(
                            isUndergraduate ?: (token.stutype == EASToken.TYPE.UNDERGRAD)
                        )
                        if (maxPeriod <= defaults.size) {
                            res.postValue(DataState(defaults.take(maxPeriod).toMutableList()))
                            return@Thread
                        }
                        while (slots.size < maxPeriod) slots.add(null)
                        for (j in 0 until jcList.length()) {
                            val jc = jcList.optJSONObject(j) ?: continue
                            val ks = jc.optInt("KSJC", 0)
                            val js = jc.optInt("JSJC", 0)
                            val count = js - ks + 1
                            val sj = jc.optString("SJ", "")
                            val parts = sj.split("—", "–", "-")
                            if (count <= 0 || parts.size < 2) continue
                            try {
                                val start = df.parse(parts[0].trim()) ?: continue
                                val end = df.parse(parts[1].trim()) ?: continue
                                val totalMinutes = ((end.time - start.time) / 60000L).toInt()
                                if (totalMinutes <= 0) continue
                                val perSlot = totalMinutes / count
                                if (perSlot <= 0) continue
                                for (idx in 0 until count) {
                                    val slotStart = (start.time / 60000L).toInt() + perSlot * idx
                                    val slotEnd = if (idx == count - 1) {
                                        (end.time / 60000L).toInt()
                                    } else {
                                        (start.time / 60000L).toInt() + perSlot * (idx + 1)
                                    }
                                    val from = TimeInDay(slotStart / 60, slotStart % 60)
                                    val to = TimeInDay(slotEnd / 60, slotEnd % 60)
                                    val pos = ks - 1 + idx
                                    if (pos in slots.indices) {
                                        slots[pos] = TimePeriodInDay(from, to)
                                    }
                                }
                            } catch (_: Exception) { LogUtils.w("Failed to parse slot structure") }
                        }
                        if (slots.any { it != null }) break
                    }
                }
                val result = if (slots.isNotEmpty() && slots.all { it != null }) {
                    slots.map { it!! }.toMutableList()
                } else {
                    val defaults = defaultScheduleStructure(
                        isUndergraduate ?: (token.stutype == EASToken.TYPE.UNDERGRAD)
                    )
                    val size = maxOf(slots.size, defaults.size)
                    val filled = MutableList(size) { idx ->
                        slots.getOrNull(idx) ?: defaults.getOrNull(idx) ?: defaults.last()
                    }
                    filled
                }
                res.postValue(DataState(result))
            } catch (e: Exception) {
                LogUtils.e("getScheduleStructure: failed, error=${e.message}", e)
                res.postValue(DataState(defaultScheduleStructure(
                    isUndergraduate ?: (token.stutype == EASToken.TYPE.UNDERGRAD)
                )))
            }
        }.start()
        return res
    }

    private fun getShenzhenWebScheduleStructure(
        token: EASToken,
        term: TermItem
    ): LiveData<DataState<MutableList<TimePeriodInDay>>> {
        val result = MutableLiveData<DataState<MutableList<TimePeriodInDay>>>()
        Thread {
            try {
                val response = jwFormPost(token, "/component/queryKbjg", mapOf(
                        "xn" to term.yearCode,
                        "xq" to term.termCode,
                        "pylx" to token.getStudentType()
                    ), "/authentication/main")
                if (isJwAuthenticationExpired(response)) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                    return@Thread
                }
                val schedule = ShenzhenWebAcademicParser.parseScheduleStructure(response.body())
                if (response.statusCode() != 200 || schedule == null) {
                    result.postValue(DataState(DataState.STATE.FETCH_FAILED, "节次结构解析失败"))
                } else {
                    result.postValue(DataState(schedule, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    @SuppressLint("SimpleDateFormat")
    private fun defaultScheduleStructure(isUndergraduate: Boolean): MutableList<TimePeriodInDay> {
        val slots = if (isUndergraduate) listOf(
            "08:30" to "09:20", "09:25" to "10:15",
            "10:30" to "11:20", "11:25" to "12:15",
            "14:00" to "14:50", "14:55" to "15:45",
            "16:00" to "16:50", "16:55" to "17:45",
            "18:45" to "19:35", "19:40" to "20:30",
            "20:45" to "21:35", "21:40" to "22:30"
        ) else listOf(
            "08:00" to "08:50", "08:55" to "09:45",
            "10:00" to "10:50", "10:55" to "11:45",
            "14:00" to "14:50", "14:55" to "15:45",
            "16:00" to "16:50", "16:55" to "17:45",
            "18:45" to "19:35", "19:40" to "20:30",
            "20:45" to "21:35", "21:40" to "22:30"
        )
        val df = SimpleDateFormat("HH:mm")
        return slots.map { (s, e) ->
            val from = Calendar.getInstance().also { c -> c.timeInMillis = df.parse(s)!!.time }
            val to = Calendar.getInstance().also { c -> c.timeInMillis = df.parse(e)!!.time }
            TimePeriodInDay(TimeInDay(from), TimeInDay(to))
        }.toMutableList()
    }

    // ================================================================ 成绩
    fun getPersonalScores(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType
    ): LiveData<DataState<List<CourseScoreItem>>> {
        if (token.accessToken.isNullOrBlank() && token.hasShenzhenWebSession()) {
            return getShenzhenWebPersonalScores(term, token, testType)
        }
        val res = MutableLiveData<DataState<List<CourseScoreItem>>>()
        Thread {
            val result: MutableList<CourseScoreItem> = ArrayList()
            try {
                val qzqmFlag = when (testType) {
                    EASService.TestType.NORMAL -> "qm"
                    EASService.TestType.RESIT  -> "qz"
                    else -> "qm"
                }
                val body = """{"xn":"${term.yearCode}","xq":"${term.termCode}","qzqmFlag":"$qzqmFlag","type":"json"}"""
                val resp = jsonPost(token, "/app/cjgl/xscjList?_lang=zh_CN", body, "06")
                val jo = JsonUtils.getJsonObject(resp.body())
                if (isAuthExpiredResponse(resp) || isAuthExpiredJson(jo)) {
                    res.postValue(DataState(DataState.STATE.NOT_LOGGED_IN))
                    return@Thread
                }
                val content = jo?.optJSONArray("content")
                content?.let {
                    for (i in 0 until it.length()) {
                        val tp = it.optJSONObject(i) ?: continue
                        val item = CourseScoreItem()
                        item.courseCode = tp.optString("kcdm")
                        item.courseName = tp.optString("kcmc")
                        item.credits = tp.optString("xf").toFloatOrNull() ?: 0f
                        item.finalScoresText = tp.optString("zf").trim().ifBlank { null }
                        item.finalScores = item.finalScoresText?.toIntOrNull() ?: -1
                        item.courseProperty = tp.optString("kcxz")
                        item.courseCategory = tp.optString("kclb", tp.optString("kclbmc", ""))
                        item.termName = tp.optString("xnxq", term.yearCode + term.termCode)
                        item.assessMethod = tp.optString("khfs", "")
                        result.add(item)
                    }
                    res.postValue(DataState(result))
                } ?: run {
                    res.postValue(DataState(DataState.STATE.FETCH_FAILED))
                }
            } catch (e: Exception) {
                LogUtils.e("getPersonalScores: failed, error=${e.message}", e)
                res.postValue(DataState(DataState.STATE.FETCH_FAILED, e.message))
            }
        }.start()
        return res
    }


    private fun getShenzhenWebPersonalScores(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType
    ): LiveData<DataState<List<CourseScoreItem>>> {
        val result = MutableLiveData<DataState<List<CourseScoreItem>>>()
        Thread {
            try {
                val fetched = fetchShenzhenWebScoreItems(term, token, testType)
                if (fetched.authExpired) {
                    result.postValue(DataState(DataState.STATE.NOT_LOGGED_IN, "深圳 Web 会话已失效"))
                } else {
                    result.postValue(DataState(fetched.items, DataState.STATE.SUCCESS))
                }
            } catch (error: Exception) {
                result.postValue(DataState(DataState.STATE.FETCH_FAILED, error.message))
            }
        }.start()
        return result
    }

    private fun extractSessionHint(kbxx: String): String? {
        if (kbxx.isBlank()) return null
        val firstLine = kbxx.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val bracket = Regex("\\[([^\\]]+)]")
        for (match in bracket.findAll(firstLine)) {
            val content = match.groupValues.getOrNull(1)?.trim() ?: continue
            val cleaned = content.removeSuffix(".").removeSuffix("．").trim()
            if (cleaned.isBlank()) continue
            if (!isLikelyClassroomToken(cleaned)) {
                return cleaned
            }
        }
        return null
    }

    private fun composeCourseDisplayName(
        canonicalName: String?,
        fallbackName: String,
        sessionHint: String?
    ): String {
        val baseName = if (!canonicalName.isNullOrBlank()) canonicalName else fallbackName
        if (sessionHint.isNullOrBlank()) return baseName
        // 如果 baseName 已经包含 sessionHint，不再重复拼接
        if (baseName.contains(sessionHint)) return baseName
        return "$baseName [$sessionHint]"
    }

    private fun normalizedCourseName(kbxx: String): String {
        if (kbxx.isBlank()) return ""
        val firstLine = kbxx.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return ""
        // 去掉所有 []
        return firstLine.replace(Regex("\\[[^\\]]*]"), "").trim()
    }

    private fun fetchTimetableFromOverview(
        token: EASToken,
        term: TermItem
    ): List<CourseItem>? {
        return try {
            val body = """{"xn":"${term.yearCode}","xq":"${term.termCode}","type":"json"}"""
            val resp = jsonPost(token, "/app/kbrcbyapp/querykbrczong", body, "06")
            val jo = JsonUtils.getJsonObject(resp.body())
            val content = jo?.optJSONArray("content") ?: return null
            val result = mutableListOf<CourseItem>()
            for (i in 0 until content.length()) {
                val course = content.optJSONObject(i) ?: continue
                val kbxx = course.optString("KBXX", "")
                val rawCode = course.optString("KCDM", "")
                val code = CourseCodeUtils.normalize(rawCode) ?: rawCode
                val xqj = course.optInt("XQJ", -1)
                val dj = course.optInt("DJ", -1)
                val ksjc = course.optInt("KSJC", -1)
                val jsjc = course.optInt("JSJC", -1)
                if (xqj !in 1..7 || dj <= 0) continue

                // 优先 KCMC，其次 KBXX
                val kcmc = course.optString("KCMC", "").trim()
                val kcmcEn = course.optString("KCMC_EN", "").trim()
                val canonicalName = kcmc.takeIf { it.isNotBlank() } ?: kcmcEn.takeIf { it.isNotBlank() }
                val fallbackName = normalizedCourseName(kbxx)
                val sessionHint = extractSessionHint(kbxx)
                val displayName = composeCourseDisplayName(canonicalName, fallbackName, sessionHint)

                val classroom = extractClassroomFromKbxx(kbxx) ?: ""
                val teacher = course.optString("JSXM", "").trim()
                val begin = if (ksjc > 0) ksjc else (dj - 1) * 2 + 1
                val end = if (jsjc >= ksjc) jsjc else begin + 1
                val last = end - begin + 1

                result.add(CourseItem().apply {
                    this.code = code
                    this.name = displayName
                    this.rawName = displayName
                    this.teacher = teacher
                    this.classroom = classroom
                    this.dow = xqj
                    this.begin = begin
                    this.last = last
                    this.weeks = mutableListOf() // 总览接口没有周次信息，需要后续补充
                })
            }
            result.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            LogUtils.e("fetchTimetableFromOverview: failed, error=${e.message}", e)
            null
        }
    }

    private fun fetchDaySchedule(
        token: EASToken,
        date: String
    ): List<DayScheduleItem> {
        return try {
            val body = """{"nyr":"$date"}"""
            val resp = jsonPost(token, "/app/kbrcbyapp/querykbrcbyday", body, "06")
            val jo = JsonUtils.getJsonObject(resp.body())
            val content = jo?.optJSONArray("content") ?: return emptyList()
            val result = mutableListOf<DayScheduleItem>()
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                result.add(DayScheduleItem(
                    kcmc = item.optString("KCMC", "").trim(),
                    cdmc = item.optString("CDMC", "").trim(),
                    ksjc = item.optInt("KSJC", -1),
                    jsjc = item.optInt("JSJC", -1),
                    dj = item.optInt("DJ", -1),
                    xqj = item.optInt("XQJ", -1)
                ))
            }
            result
        } catch (e: Exception) {
            LogUtils.e("fetchDaySchedule: failed, error=${e.message}", e)
            emptyList()
        }
    }

    private fun bestDayScheduleRawName(
        daySchedules: List<DayScheduleItem>,
        dow: Int,
        begin: Int,
        end: Int,
        classroom: String
    ): String? {
        val candidates = daySchedules.filter {
            it.xqj == dow && it.ksjc == begin && it.jsjc == end
        }
        if (candidates.isEmpty()) return null
        // 优先匹配教室
        val withClassroom = candidates.find { it.cdmc == classroom }
        return withClassroom?.kcmc?.takeIf { it.isNotBlank() }
            ?: candidates.firstOrNull { it.kcmc.isNotBlank() }?.kcmc
    }

    private data class DayScheduleItem(
        val kcmc: String,
        val cdmc: String,
        val ksjc: Int,
        val jsjc: Int,
        val dj: Int,
        val xqj: Int
    )

    internal fun fetchShenzhenWebScoreItems(
        term: TermItem,
        token: EASToken,
        testType: EASService.TestType
    ): ShenzhenWebScoreResult {
        val isMidterm = testType == EASService.TestType.RESIT
        val path = if (isMidterm) "/cjgl/grcjcx/qzcjcx" else "/cjgl/grcjcx/grcjcx"
        val body = if (isMidterm) {
            JSONObject()
                .put("xn", term.yearCode)
                .put("xq", term.termCode)
                .put("kcmc", "")
                .put("pylx", token.getStudentType())
                .put("current", 1)
                .put("pageSize", 1000)
                .toString()
        } else {
            JSONObject()
                .put("xn", term.yearCode)
                .put("xq", term.termCode)
                .put("kcmc", JSONObject.NULL)
                .put("cxbj", "-1")
                .put("pylx", token.getStudentType())
                .put("current", 1)
                .put("pageSize", 1000)
                .put("xscjlb", JSONObject.NULL)
                .put("sffx", JSONObject.NULL)
                .toString()
        }
        val response = jwJsonPost(token, path, body, "/cjgl/grcjcx")
        if (isJwAuthenticationExpired(response)) return ShenzhenWebScoreResult(emptyList(), authExpired = true)
        if (response.statusCode() != 200) {
            throw IllegalStateException("成绩接口 HTTP ${response.statusCode()}")
        }
        val parsed = ShenzhenWebScoreParser.parse(response.body(), term)
            ?: throw IllegalStateException("成绩接口返回非 JSON 数据")
        if (parsed.code != 0 && parsed.code != 200) {
            throw IllegalStateException(parsed.message.ifBlank { "成绩查询失败" })
        }
        return ShenzhenWebScoreResult(parsed.items)
    }

    private fun isLikelyClassroomToken(token: String): Boolean {
        val trimmed = token.trim()
        if (trimmed.isBlank()) return false
        // 必须同时包含字母和数字
        val hasLetter = trimmed.any { it.isLetter() && it in '\u0000'..'\u007F' }
        val hasDigit = trimmed.any { it.isDigit() }
        if (!hasLetter || !hasDigit) return false
        // 全部字符必须是 ASCII 字母、数字、-、_
        return trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    internal data class ShenzhenWebScoreResult(
        val items: List<CourseScoreItem>,
        val authExpired: Boolean = false
    )

    private sealed class OfficialWebScoreSummaryResult {
        data class Available(val summary: ScoreSummary) : OfficialWebScoreSummaryResult()
        object AuthExpired : OfficialWebScoreSummaryResult()
        object Unavailable : OfficialWebScoreSummaryResult()
    }
}
