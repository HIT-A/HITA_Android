package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.utils.LogUtils
import cn.limpu.hita.data.model.eas.CourseItem
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import org.jsoup.Jsoup

object BenbuScheduleParser {
    private const val DEBUG_WEEK = 7
    private val DEBUG_DOW = setOf(5, 6)
    private val WEEK_PATTERN = Regex("""\[([^\]]+)\](?:单|双)?周(?:\((?:单|双)\))?""")

    fun parseScheduleHtml(html: String): List<CourseItem> {
        val result = mutableListOf<CourseItem>()
        try {
            val doc = Jsoup.parse(html)
            val table = doc.select("table.addlist_01").firstOrNull() ?: return result
            val rows = table.select("tr")

            for (rowIndex in 1 until rows.size) {
                val row = rows[rowIndex]
                val cells = row.select("td,th")
                if (cells.size < 3) continue

                val periodInfo = cells.getOrNull(1)?.text()?.trim().orEmpty()

                for (cellIndex in 2 until cells.size) {
                    val cell = cells[cellIndex]
                    val cellHtml = cell.html()
                    val cellText = cell.text().replace("\u00A0", " ").trim()
                    if (cellText.isBlank() || cellText.length < 2) continue

                    val dow = cellIndex - 1
                    val courses = parseCellCourses(cellHtml, dow, rowIndex, periodInfo)
                    result.addAll(courses)
                }
            }
        } catch (e: Exception) {
            LogUtils.e("parseScheduleHtml: failed", e)
            throw e
        }
        return result
    }

    private fun parseCellCourses(cellHtml: String, dow: Int, periodHint: Int, periodInfo: String): List<CourseItem> {
        val courses = mutableListOf<CourseItem>()

        var cleanedHtml = cellHtml.replace(Regex("<a[^>]*>.*?</a>", RegexOption.IGNORE_CASE), "")
        cleanedHtml = cleanedHtml.replace(Regex("<(?!/?br)[^>]+>", RegexOption.IGNORE_CASE), "")

        val parts = cleanedHtml.split(Regex("""<\s*/?\s*br\s*/?\s*>""", RegexOption.IGNORE_CASE))
            .map { it.replace(Regex("<[^>]+>"), "").replace("\u00A0", " ").trim() }
            .filter { it.isNotBlank() && it != "&nbsp;" }

        if (parts.isEmpty()) return courses

        var i = 0
        while (i < parts.size) {
            val line = parts[i]

            if (WEEK_PATTERN.containsMatchIn(line)) {
                i++
                continue
            }

            val courseName = line
            i++

            val teacherLocationLines = mutableListOf<String>()
            while (i < parts.size) {
                val nextLine = parts[i]
                if (WEEK_PATTERN.containsMatchIn(nextLine)) {
                    teacherLocationLines.add(nextLine)
                    i++
                } else if (
                    teacherLocationLines.isNotEmpty() &&
                    !parts.getOrNull(i + 1).orEmpty().let { WEEK_PATTERN.containsMatchIn(it) }
                ) {
                    teacherLocationLines.add(nextLine)
                    i++
                    break
                } else {
                    break
                }
            }

            if (teacherLocationLines.isNotEmpty()) {
                val combined = teacherLocationLines.joinToString("")
                val elements = splitTeacherLocationPairs(combined)
                val parsedForCourse = mutableListOf<CourseItem>()

                for (element in elements) {
                    val courseList = parseCourseElement(courseName, element, dow, periodHint, periodInfo)
                    parsedForCourse.addAll(courseList)
                }
                courses.addAll(normalizeCourseElementParts(parsedForCourse))
            }
        }

        return courses
    }

    private fun splitTeacherLocationPairs(text: String): List<String> {
        val elements = mutableListOf<String>()
        val current = StringBuilder()
        var bracketDepth = 0

        for (char in text) {
            when (char) {
                '[' -> bracketDepth++
                ']' -> bracketDepth--
                ',', '，' -> {
                    if (bracketDepth == 0) {
                        if (current.isNotEmpty()) {
                            elements.add(current.toString().trim())
                            current.clear()
                        }
                        continue
                    }
                }
            }
            current.append(char)
        }

        if (current.isNotEmpty()) {
            elements.add(current.toString().trim())
        }

        return elements
    }

    private fun parseCourseElement(courseName: String, element: String, dow: Int, periodHint: Int, periodInfo: String): List<CourseItem> {
        // 匹配格式：教师[周数]周或[周数单/双]周或(单/双)，地点
        val weekPattern = Regex("""([^,，\[]*)\[([^\]]+?)\](?:(单|双)周)?(?:\((单|双)\))?([^,，]*)""")
        val matches = weekPattern.findAll(element).toList()

        if (matches.isEmpty()) {
            debugLog(dow, emptyList(), "parseCourseElement no week match course=$courseName element=$element")
            return emptyList()
        }

        val result = mutableListOf<CourseItem>()

        for (match in matches) {
            val teacherPart = match.groupValues[1].trim()
            var weeksStr = match.groupValues[2].trim()
            val oddEvenOutside = match.groupValues[3].trim()  // 括号外：单/双
            val oddEvenInside = match.groupValues[4].trim()  // 括号内：(单)/(双)
            var locationPart = match.groupValues[5].trim()

            // 修复：去除地点开头可能残留的"周"字（如"单周A302"中的"周"）
            locationPart = locationPart.removePrefix("周").trim()

            // 确定单双周标识：括号外 > 括号内 > weeksStr内部
            val oddEvenHint = when {
                oddEvenOutside.isNotBlank() -> oddEvenOutside + "周"  // "单周" or "双周"
                oddEvenInside.isNotBlank() -> oddEvenInside + "周"
                else -> ""  // 从 weeksStr 内部提取（如 "1-8单"）
            }

            val weeks = parseWeeksString(weeksStr, oddEvenHint)
            val periodResolved = resolvePeriod(periodInfo, periodHint)
            val teacherResolved = sanitizeTeacher(courseName, teacherPart)

            debugLog(
                dow,
                weeks,
                "parseCourseElement parsed course=$courseName teacherRaw=${teacherPart.ifBlank { "<blank>" }} " +
                    "teacherResolved=${teacherResolved ?: "<null>"} " +
                    "weeks=$weeks period=${periodResolved.first}-${periodResolved.first + periodResolved.second - 1} " +
                    "location=${locationPart.ifBlank { "<blank>" }} raw=$element"
            )

            result.add(CourseItem().apply {
                code = null
                name = courseName
                this.weeks = weeks
                teacher = teacherResolved
                classroom = locationPart.ifBlank { null }
                this.dow = dow
                this.begin = periodResolved.first
                last = periodResolved.second
            })
        }

        return result
    }

    private fun normalizeCourseElementParts(items: List<CourseItem>): List<CourseItem> {
        if (items.size <= 1) return items

        val teacherFallback = items.firstOrNull { !it.teacher.isNullOrBlank() }?.teacher
        val classrooms = items.mapNotNull { it.classroom?.takeIf { room -> room.isNotBlank() } }
            .distinct()
        val classroomFallback = classrooms.singleOrNull()

        items.forEach { item ->
            if (item.teacher.isNullOrBlank() && !teacherFallback.isNullOrBlank()) {
                item.teacher = teacherFallback
            }
            if (item.classroom.isNullOrBlank() && !classroomFallback.isNullOrBlank()) {
                item.classroom = classroomFallback
            }
        }
        return items
    }

    private fun resolvePeriod(periodInfo: String, periodHint: Int): Pair<Int, Int> {
        val normalized = periodInfo
            .replace("\u00A0", "")
            .replace("第", "")
            .replace("节", "")
            .replace("，", ",")
            .trim()

        val range = Regex("""(\d+)\s*[-,]\s*(\d+)""").find(normalized)
        if (range != null) {
            val begin = range.groupValues[1].toIntOrNull()
            val end = range.groupValues[2].toIntOrNull()
            if (begin != null && end != null && end >= begin) {
                return begin to (end - begin + 1)
            }
        }

        val single = Regex("""(\d+)""").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (single != null && single > 0) {
            return single to 1
        }

        return periodHint to 2
    }

    private fun parseWeeksString(weeksStr: String, oddEvenHint: String = ""): MutableList<Int> {
        val source = weeksStr.replace("\u00A0", " ").replace(" ", "")
        if (source.isBlank()) return mutableListOf()

        // 确定单双周标识：优先使用外部hint，其次从weeksStr中提取
        val oddEven = when {
            oddEvenHint.contains("单") -> "odd"
            oddEvenHint.contains("双") -> "even"
            source.contains("单") -> "odd"
            source.contains("双") -> "even"
            else -> null
        }

        // 清理周数字符串，移除单双周标识
        val normalized = source
            .replace("单周", "")
            .replace("双周", "")
            .replace("单", "")
            .replace("双", "")

        val weeks = linkedSetOf<Int>()
        val segments = normalized.split(Regex("[,，]"))

        segments.forEach { part ->
            val trimmed = part.trim()
            if (trimmed.isBlank()) return@forEach

            if (trimmed.contains("-")) {
                val range = trimmed.split("-")
                val start = range.getOrNull(0)?.toIntOrNull() ?: return@forEach
                val end = range.getOrNull(1)?.toIntOrNull() ?: return@forEach
                if (start > end) return@forEach

                val step = if (oddEven == null) 1 else 2
                var first = start
                if (oddEven == "odd" && first % 2 == 0) first += 1
                if (oddEven == "even" && first % 2 != 0) first += 1
                for (week in first..end step step) {
                    weeks.add(week)
                }
            } else {
                val week = trimmed.toIntOrNull() ?: return@forEach
                if (oddEven == null || (oddEven == "odd" && week % 2 == 1) || (oddEven == "even" && week % 2 == 0)) {
                    weeks.add(week)
                }
            }
        }

        return weeks.toMutableList().sorted().toMutableList()
    }

    private fun sanitizeTeacher(courseName: String, teacherRaw: String?): String? {
        val source = teacherRaw?.trim().orEmpty()
        if (source.isBlank()) return null

        var candidate = source
        if (candidate.startsWith("【") && candidate.contains("】")) {
            val closeIndex = candidate.indexOf('】')
            if (closeIndex >= 0 && closeIndex < candidate.length - 1) {
                candidate = candidate.substring(closeIndex + 1).trimStart('/', '／', ' ', '\t')
            }
        }

        if (candidate.startsWith(courseName)) {
            candidate = candidate.removePrefix(courseName).trimStart('/', '／', ' ', '\t')
        }

        val beforeSlash = candidate.substringBefore('/').trim()
        val cleaned = beforeSlash
            .replace(Regex("^第[一二三四五六七八九十0-9]+批"), "")
            .trimStart('/', '／', ' ', '\t')
            .trim()

        return cleaned.ifBlank { null }
    }

    private fun shouldDebug(dow: Int, weeks: List<Int>): Boolean {
        return dow in DEBUG_DOW && weeks.contains(DEBUG_WEEK)
    }

    private fun debugLog(dow: Int, weeks: List<Int>, message: String) {
        if (!shouldDebug(dow, weeks)) return
        LogUtils.d("[DBG] dow=$dow weeks=$weeks $message")
    }

    /**
     * 解析实验课HTML
     * 实验课接口: /xskb/queryXszkb
     * 返回格式: 课程名称<br>教师 [周次] 节次 上课形式[形式]地点，<span class=red>时间</span>
     */
    fun parseExperimentHtml(html: String): List<CourseItem> {
        val result = mutableListOf<CourseItem>()
        try {
            LogUtils.d("parseExperimentHtml: START")
            val doc = Jsoup.parse(html)
            val table = doc.select("table.dataTable").firstOrNull()
            if (table == null) {
                LogUtils.w("parseExperimentHtml: table.dataTable not found")
                return result
            }

            val rows = table.select("tr")
            LogUtils.d("parseExperimentHtml: table found, rows=${rows.size}")

            // 跳过表头，从第2行开始
            for (rowIndex in 2 until rows.size) {
                val row = rows[rowIndex]
                val cells = row.select("td")
                if (cells.size < 9) continue

                // 获取时间段信息（第1列：上午/下午/晚上，第2列：第X,X节）
                val periodLabel = cells.getOrNull(0)?.text()?.trim().orEmpty()
                val periodRange = cells.getOrNull(1)?.text()?.trim().orEmpty()

                // 解析节次（第3-9列分别对应星期一到星期日）
                for (cellIndex in 2 until cells.size) {
                    val cell = cells[cellIndex]
                    val cellHtml = cell.html()
                    val cellText = cell.text().replace(" ", " ").trim()
                    if (cellText.isBlank() || cellText == "&nbsp;") continue

                    val dow = cellIndex - 1 // 星期几（1-7）
                    LogUtils.d("parseExperimentHtml: row=$rowIndex, col=$cellIndex, dow=$dow, text=$cellText")
                    val courses = parseExperimentCell(cellHtml, dow, periodLabel, periodRange)
                    result.addAll(courses)
                }
            }
            LogUtils.d("parseExperimentHtml: END, total=${result.size}")
        } catch (e: Exception) {
            LogUtils.e("parseExperimentHtml: failed", e)
        }
        return result
    }

    private fun parseExperimentCell(cellHtml: String, dow: Int, periodLabel: String, periodRange: String): List<CourseItem> {
        val courses = mutableListOf<CourseItem>()

        // 移除<a>标签但保留内容
        var cleanedHtml = cellHtml.replace(Regex("<a[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "$1")
        // 移除<span class=red>标签但保留内容
        cleanedHtml = cleanedHtml.replace(Regex("<span[^>]*class=\"red\"[^>]*>(.*?)</span>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "$1")
        // 按<br>分割
        val parts = cleanedHtml.split(Regex("""<\s*/?\s*br\s*/?\s*>""", setOf(RegexOption.IGNORE_CASE)))
            .map { it.replace(Regex("<[^>]+>"), "").replace(" ", " ").trim() }
            .filter { part -> part.isNotBlank() && part != "&nbsp;" }

        if (parts.isEmpty()) return courses

        var index = 0
        while (index < parts.size) {
            val courseName = parts[index].trim()
            if (courseName.isEmpty()) {
                index++
                continue
            }
            index++

            // 收集后续行，直到遇到下一个课程名或结束
            val details = mutableListOf<String>()
            while (index < parts.size) {
                val line = parts[index].trim()
                // 更准确地判断是否是新的课程名：
                // - 包含"上课形式"、"周"、"节"之一的是detail
                // - 或者包含方括号的也是detail
                // - 其他情况（长度>5且不含上述关键词）可能是新课程名
                val isDetail = line.contains("上课形式") || line.contains("周") || line.contains("节") || line.contains("[")
                if (!isDetail && line.length > 5) {
                    break
                }
                details.add(line)
                index++
            }

            // 解析详细信息
            if (details.isNotEmpty()) {
                // 有detail行，正常解析
                for (detail in details) {
                    val course = parseExperimentDetail(courseName, detail, dow, periodLabel, periodRange)
                    if (course != null) {
                        courses.add(course)
                    }
                }
            } else {
                // 没有detail行，可能是courseName本身就包含了完整信息
                // 尝试从courseName中解析
                val course = parseExperimentDetailFromCombined(courseName, dow, periodLabel, periodRange)
                if (course != null) {
                    courses.add(course)
                }
            }
        }

        return courses
    }

    private fun parseExperimentDetail(courseName: String, detail: String, dow: Int, periodLabel: String, periodRange: String): CourseItem? {
        try {
            LogUtils.d("parseExperimentDetail: name=$courseName, detail=$detail")
            // 格式: 教师 [周次] 节次 上课形式[形式]地点，时间
            // 例如: 黄丽 [14周第7，8节] 上课形式[线下]L619，13:00-18:10

            val course = CourseItem()

            // 1. 提取教师名称（第一个空格之前）
            val teacherEndIndex = detail.indexOf(" [")
            if (teacherEndIndex <= 0) {
                LogUtils.d("parseExperimentDetail: teacher not found, detail=$detail")
                return null
            }
            course.teacher = detail.substring(0, teacherEndIndex).trim()

            // 2. 提取周次信息 [14周第7，8节] 或 [9周第5，6节]
            val weekPattern = Regex("\\[(\\d+)周第([\\d，]+)节\\]")
            val weekMatch = weekPattern.find(detail)
            if (weekMatch == null) {
                LogUtils.d("parseExperimentDetail: weeks not found, detail=$detail")
                return null
            }

            val weekNum = weekMatch.groupValues[1].toIntOrNull()
            if (weekNum == null) {
                LogUtils.d("parseExperimentDetail: weeks parse failed, value=${weekMatch.groupValues[1]}")
                return null
            }
            course.weeks = mutableListOf(weekNum)

            // 3. 提取时间信息（实验课使用具体时间，不使用节次）
            val detailAfterBrackets = detail.substringAfter("]")
            val timeMatch = Regex("(\\d{1,2}:\\d{2})-(\\d{1,2}:\\d{2})").find(detailAfterBrackets)

            if (timeMatch != null) {
                // 提取并存储具体时间
                course.startTime = timeMatch.groupValues[1]  // "15:40"
                course.endTime = timeMatch.groupValues[2]      // "18:10"
                course.begin = -1  // 实验课使用自由时间，标记为-1
                course.last = -1
                LogUtils.d("parseExperimentDetail: using free time, startTime=${course.startTime}, endTime=${course.endTime}")
            } else {
                // 回退到使用节次号
                val periodStr = weekMatch.groupValues[2]
                val (begin, duration) = parsePeriodRange(periodStr)
                val endPeriod = begin + duration - 1
                course.begin = begin
                course.last = duration
                LogUtils.d("parseExperimentDetail: using period, begin=$begin, end=$endPeriod")
            }
            course.dow = dow

            // 4. 提取地点信息
            // 查找"上课形式[xxx]地点"或"地点，"
            val locationPattern = Regex("上课形式\\[[^\\]]+\\]([^，，]+)[，，]?|，([^，，]+)[，，]?")
            val locationMatch = locationPattern.find(detail.substringAfter("]"))
            if (locationMatch != null) {
                course.classroom = (locationMatch.groupValues[1].ifBlank { locationMatch.groupValues[2] }).trim()
            }

            // 5. 设置课程名称和其他属性
            course.name = courseName

            val timeDisplay = if (course.startTime != null) "${course.startTime}-${course.endTime}" else "第${course.begin}-${course.begin + course.last - 1}节"
            LogUtils.d("parseExperimentDetail: parsed, name=${course.name}, weeks=${course.weeks}, dow=$dow, time=$timeDisplay, teacher=${course.teacher}, room=${course.classroom}")
            return course
        } catch (e: Exception) {
            LogUtils.e("parseExperimentDetail: failed, courseName=$courseName, detail=$detail", e)
            return null
        }
    }

    // 处理合并格式："课程名 教师 [周次] 节次 上课形式[形式]地点，时间"
    private fun parseExperimentDetailFromCombined(combined: String, dow: Int, periodLabel: String, periodRange: String): CourseItem? {
        try {
            LogUtils.d("parseExperimentDetailFromCombined: combined=$combined")

            // 格式: 用拉伸法测定弹性模量 姚凤凤 [11周第7，8节] 上课形式[线下]L622，15:40-18:10
            // 提取第一个方括号之前的内容，然后分割课程名和教师

            val bracketIndex = combined.indexOf(" [")
            if (bracketIndex <= 0) {
                LogUtils.d("parseExperimentDetailFromCombined: brackets not found, combined=$combined")
                return null
            }

            // 方括号之前的部分：课程名 + 教师
            val beforeBracket = combined.substring(0, bracketIndex).trim()
            val lastSpaceIndex = beforeBracket.lastIndexOf(" ")
            if (lastSpaceIndex <= 0) {
                LogUtils.d("parseExperimentDetailFromCombined: teacher not found, combined=$combined")
                return null
            }

            val courseName = beforeBracket.substring(0, lastSpaceIndex).trim()
            val teacher = beforeBracket.substring(lastSpaceIndex + 1).trim()

            // 方括号之后的部分：周次、节次、地点等
            val detail = combined.substring(bracketIndex + 1) // 去掉前面的空格

            val course = CourseItem()
            course.name = courseName
            course.teacher = teacher

            // 提取周次信息 [11周第7，8节]
            val weekPattern = Regex("(\\d+)周第([\\d，]+)节")
            val weekMatch = weekPattern.find(detail)
            if (weekMatch == null) {
                LogUtils.d("parseExperimentDetailFromCombined: weeks not found, detail=$detail")
                return null
            }

            val weekNum = weekMatch.groupValues[1].toIntOrNull()
            if (weekNum == null) {
                LogUtils.d("parseExperimentDetailFromCombined: weeks parse failed, value=${weekMatch.groupValues[1]}")
                return null
            }
            course.weeks = mutableListOf(weekNum)

            // 提取时间信息（实验课使用具体时间，不使用节次）
            val detailAfterBrackets = detail.substringAfter("]")
            val timeMatch = Regex("(\\d{1,2}:\\d{2})-(\\d{1,2}:\\d{2})").find(detailAfterBrackets)

            if (timeMatch != null) {
                // 提取并存储具体时间
                course.startTime = timeMatch.groupValues[1]  // "15:40"
                course.endTime = timeMatch.groupValues[2]      // "18:10"
                course.begin = -1  // 实验课使用自由时间，标记为-1
                course.last = -1
                LogUtils.d("parseExperimentDetailFromCombined: using free time, startTime=${course.startTime}, endTime=${course.endTime}")
            } else {
                // 回退到使用节次号
                val periodStr = weekMatch.groupValues[2]
                val (begin, duration) = parsePeriodRange(periodStr)
                val endPeriod = begin + duration - 1
                course.begin = begin
                course.last = duration
                LogUtils.d("parseExperimentDetailFromCombined: using period, begin=$begin, end=$endPeriod")
            }
            course.dow = dow

            // 提取地点信息
            val locationPattern = Regex("上课形式\\[[^\\]]+\\]([^，，]+)[，，]?|，([^，，]+)[，，]?")
            val locationMatch = locationPattern.find(detail.substringAfter("]").substringBefore(","))
            if (locationMatch != null) {
                course.classroom = (locationMatch.groupValues[1].ifBlank { locationMatch.groupValues[2] }).trim()
            }

            val timeDisplay = if (course.startTime != null) "${course.startTime}-${course.endTime}" else "第${course.begin}-${course.begin + course.last - 1}节"
            LogUtils.d("parseExperimentDetailFromCombined: parsed, name=${course.name}, weeks=${course.weeks}, dow=$dow, time=$timeDisplay, teacher=${course.teacher}, room=${course.classroom}")
            return course
        } catch (e: Exception) {
            LogUtils.e("parseExperimentDetailFromCombined: failed, combined=$combined", e)
            return null
        }
    }

    private fun parsePeriodRange(periodStr: String): Pair<Int, Int> {
        // 解析"7，8"或"5，6"这样的节次范围
        val parts = periodStr.split("，", ",")
        val first = parts.firstOrNull()?.toIntOrNull() ?: 1
        val second = parts.getOrNull(1)?.toIntOrNull() ?: first
        // 返回(开始节次, 持续多少节)，与普通课表保持一致
        return first to (second - first + 1)
    }

    /**
     * 根据实验课的具体时间（如"15:40-18:10"）反推节次号
     * @param timeStr 时间字符串，格式如"15:40-18:10"
     * @return Pair(开始节次, 持续节数)
     */
    private fun parsePeriodFromTime(timeStr: String): Pair<Int, Int> {
        try {
            // 解析时间范围 "15:40-18:10"
            val timePattern = Regex("(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})")
            val match = timePattern.find(timeStr) ?: return parsePeriodRange("7，8") // 解析失败返回默认值

            val startHour = match.groupValues[1].toInt()
            val startMin = match.groupValues[2].toInt()
            val endHour = match.groupValues[3].toInt()
            val endMin = match.groupValues[4].toInt()

            val courseStartMin = startHour * 60 + startMin
            val courseEndMin = endHour * 60 + endMin

            // 获取默认课表结构
            val schedule = defaultScheduleStructure()

            // 查找开始节次
            var beginPeriod = 1
            var foundStart = false
            for (i in schedule.indices) {
                val period = schedule[i]
                val periodStartMin = period.from.hour * 60 + period.from.minute
                val periodEndMin = period.to.hour * 60 + period.to.minute

                // 如果课程开始时间在这个节次范围内
                if (courseStartMin >= periodStartMin && courseStartMin < periodEndMin) {
                    beginPeriod = i + 1
                    foundStart = true
                    break
                }
            }
            // 如果没找到（课程开始时间在所有节次之前，如13:00），找第一个节次
            if (!foundStart) {
                beginPeriod = 1
            }

            // 查找结束节次
            var endPeriod = beginPeriod
            for (i in schedule.indices) {
                val period = schedule[i]
                val periodStartMin = period.from.hour * 60 + period.from.minute
                val periodEndMin = period.to.hour * 60 + period.to.minute

                // 如果课程结束时间在这个节次范围内
                if (courseEndMin > periodStartMin && courseEndMin <= periodEndMin) {
                    endPeriod = i + 1
                }
            }
            // 如果没找到（课程结束时间在所有节次之后），使用最后一个节次
            if (endPeriod < beginPeriod) {
                endPeriod = beginPeriod
            }

            val duration = endPeriod - beginPeriod + 1
            LogUtils.d("parsePeriodFromTime: timeStr=$timeStr, rangeMin=$courseStartMin-$courseEndMin, period=${beginPeriod}-${endPeriod}, duration=${duration}")
            return beginPeriod to duration
        } catch (e: Exception) {
            LogUtils.e("parsePeriodFromTime: failed, timeStr=$timeStr", e)
            return parsePeriodRange("7，8") // 解析失败返回默认值
        }
    }

    /**
     * 默认课表结构。收敛到本部本科生表，保证实验课节次反推
     * 与导入页显示的作息同源（此前这里自带一份 `10:05/13:30` 的旧值，与
     * `BenbuEASWebSource` 冲突）。
     */
    private fun defaultScheduleStructure(): MutableList<TimePeriodInDay> =
        CampusDefaultSchedule.undergraduate(EASToken.Campus.BENBU)
}
