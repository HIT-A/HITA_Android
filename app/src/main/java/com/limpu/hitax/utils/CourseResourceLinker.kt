package com.limpu.hitax.utils

import androidx.appcompat.app.AlertDialog
import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.limpu.component.data.DataState
import com.limpu.hitax.data.model.resource.CourseResourceItem
import com.limpu.hitax.data.repository.HoaRepository
import com.limpu.hitax.data.source.web.HoaResourceSource

object CourseResourceLinker {
    fun openReadme(
        context: Context,
        owner: LifecycleOwner,
        courseCodeRaw: String?,
        courseNameRaw: String?,
        campus: String? = null,
    ) {
        val queryContext = buildQueryContext(courseCodeRaw, courseNameRaw)
        if (queryContext.queries.isEmpty()) {
            openFallback(
                context,
                queryContext.normalizedCode,
                queryContext.normalizedName,
                courseCodeRaw,
                courseNameRaw,
            )
            return
        }

        searchSequentially(
            context,
            owner,
            queryContext.queries,
            0,
            mutableListOf(),
            queryContext.normalizedCode,
            queryContext.normalizedName,
            courseCodeRaw,
            courseNameRaw,
            campus,
        )
    }

    fun resolveBestMatch(
        owner: LifecycleOwner,
        courseCodeRaw: String?,
        courseNameRaw: String?,
        campus: String? = null,
        onResolved: (CourseResourceItem?) -> Unit,
    ) {
        resolveCandidates(owner, courseCodeRaw, courseNameRaw, campus) { candidates ->
            onResolved(selectBestMatch(candidates, CourseCodeUtils.normalize(courseCodeRaw) ?: CourseCodeUtils.normalize(courseNameRaw), CourseNameUtils.normalize(courseNameRaw)))
        }
    }

    fun resolveCandidates(
        owner: LifecycleOwner,
        courseCodeRaw: String?,
        courseNameRaw: String?,
        campus: String? = null,
        onResolved: (List<CourseResourceItem>) -> Unit,
    ) {
        val queryContext = buildQueryContext(courseCodeRaw, courseNameRaw)
        if (queryContext.queries.isEmpty()) {
            onResolved(emptyList())
            return
        }

        val collected = mutableListOf<CourseResourceItem>()
        val latch = java.util.concurrent.CountDownLatch(queryContext.queries.size)
        val lock = Object()

        for (query in queryContext.queries) {
            LogUtils.d("CourseResourceLinker resolveCandidates parallel: query=$query")
            HoaResourceSource.searchCoursesDirect(query, campus) { value ->
                if (value.state == DataState.STATE.SUCCESS) {
                    synchronized(lock) {
                        collected.addAll(value.data.orEmpty())
                    }
                } else {
                    LogUtils.d("CourseResourceLinker resolveCandidates parallel: query=$query failed state=${value.state}")
                }
                latch.countDown()
            }
        }

        Thread {
            latch.await()
            LogUtils.d("CourseResourceLinker resolveCandidates all done: total=${collected.size}")
            val result = rankCandidates(collected, queryContext.normalizedCode, queryContext.normalizedName)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResolved(result) }
        }.start()
    }

    private data class QueryContext(
        val normalizedCode: String?,
        val normalizedName: String?,
        val queries: List<String>,
    )

    private fun buildQueryContext(
        courseCodeRaw: String?,
        courseNameRaw: String?,
    ): QueryContext {
        val normalizedCode = CourseCodeUtils.normalize(courseCodeRaw)
            ?: CourseCodeUtils.normalize(courseNameRaw)
        val normalizedName = CourseNameUtils.normalize(courseNameRaw)

        val queries = mutableListOf<String>()
        if (!normalizedCode.isNullOrBlank()) queries.add(normalizedCode)
        if (!normalizedName.isNullOrBlank() && normalizedName != normalizedCode) queries.add(normalizedName)
        return QueryContext(normalizedCode, normalizedName, queries)
    }


    private fun searchSequentially(
        context: Context,
        owner: LifecycleOwner,
        queries: List<String>,
        index: Int,
        collected: MutableList<CourseResourceItem>,
        normalizedCode: String?,
        normalizedName: String?,
        courseCodeRaw: String?,
        courseNameRaw: String?,
        campus: String?,
    ) {
        val query = queries.getOrNull(index)
        if (query.isNullOrBlank()) {
            openFromCandidates(
                context,
                collected,
                normalizedCode,
                normalizedName,
                courseCodeRaw,
                courseNameRaw,
            )
            return
        }
        val liveData = HoaRepository().searchCourses(query, campus)
        val observer = object : Observer<DataState<List<CourseResourceItem>>> {
            override fun onChanged(value: DataState<List<CourseResourceItem>>) {
                liveData.removeObserver(this)
                if (value.state == DataState.STATE.SUCCESS) {
                    collected.addAll(value.data.orEmpty())
                }
                if (index + 1 < queries.size) {
                    searchSequentially(
                        context,
                        owner,
                        queries,
                        index + 1,
                        collected,
                        normalizedCode,
                        normalizedName,
                        courseCodeRaw,
                        courseNameRaw,
                        campus,
                    )
                } else {
                    openFromCandidates(
                        context,
                        collected,
                        normalizedCode,
                        normalizedName,
                        courseCodeRaw,
                        courseNameRaw,
                    )
                }
            }
        }
        liveData.observe(owner, observer)
    }

    private fun openFromCandidates(
        context: Context,
        items: List<CourseResourceItem>,
        normalizedCode: String?,
        normalizedName: String?,
        courseCodeRaw: String?,
        courseNameRaw: String?,
    ) {
        val deduped = items
            .distinctBy { item -> "${item.repoType}|${item.repoName}" }
            .sortedByDescending { scoreCandidate(it, normalizedCode, normalizedName) }

        val code = normalizedCode?.trim().orEmpty()
        val exactCodeMatches = if (code.isBlank()) {
            emptyList()
        } else {
            deduped.filter {
                it.courseCode.equals(code, ignoreCase = true) ||
                    it.repoName.equals(code, ignoreCase = true)
            }
        }

        if (exactCodeMatches.size == 1) {
            openReadmeFor(
                context,
                exactCodeMatches.first(),
                normalizedCode,
                normalizedName,
                courseCodeRaw,
                courseNameRaw,
            )
            return
        }

        if (deduped.size > 1) {
            showCandidateChooser(
                context,
                deduped.take(8),
                normalizedCode,
                normalizedName,
                courseCodeRaw,
                courseNameRaw,
            )
            return
        }

        if (deduped.size == 1) {
            openReadmeFor(context, deduped.first(), normalizedCode, normalizedName, courseCodeRaw, courseNameRaw)
            return
        }

        openFallback(context, normalizedCode, normalizedName, courseCodeRaw, courseNameRaw)
    }


    private fun showCandidateChooser(
        context: Context,
        candidates: List<CourseResourceItem>,
        normalizedCode: String?,
        normalizedName: String?,
        courseCodeRaw: String?,
        courseNameRaw: String?,
    ) {
        val labels = candidates.map {
            val code = it.courseCode.ifBlank { it.repoName }
            val name = it.courseName.ifBlank { code }
            "$code  $name"
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("找到多个课程资源，请选择")
            .setItems(labels) { _, which ->
                openReadmeFor(
                    context,
                    candidates[which],
                    normalizedCode,
                    normalizedName,
                    courseCodeRaw,
                    courseNameRaw,
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openReadmeFor(
        context: Context,
        match: CourseResourceItem,
        normalizedCode: String?,
        normalizedName: String?,
        courseCodeRaw: String?,
        courseNameRaw: String?,
    ) {
        val displayName = match.courseName.ifBlank {
            match.courseCode.ifBlank { normalizedName ?: courseNameRaw ?: match.repoName }
        }
        val displayCode = match.courseCode.ifBlank {
            normalizedCode ?: courseCodeRaw ?: match.repoName
        }
        ActivityUtils.startCourseReadmeActivity(
            context,
            repoName = match.repoName,
            courseName = displayName,
            courseCode = displayCode,
            repoType = match.repoType.ifBlank { "normal" },
        )
    }

    private fun rankCandidates(
        items: List<CourseResourceItem>,
        normalizedCode: String?,
        normalizedName: String?,
    ): List<CourseResourceItem> {
        return items
            .distinctBy { item -> "${item.repoType}|${item.repoName}" }
            .sortedByDescending { scoreCandidate(it, normalizedCode, normalizedName) }
    }

    private fun scoreCandidate(
        item: CourseResourceItem,
        normalizedCode: String?,
        normalizedName: String?,
    ): Int {
        var score = 0
        val code = normalizedCode?.trim().orEmpty()
        if (code.isNotBlank()) {
            if (item.courseCode.equals(code, ignoreCase = true)) score += 100
            if (item.repoName.equals(code, ignoreCase = true)) score += 80
        }
        val nameKey = CourseNameUtils.normalizeKey(normalizedName)
        if (nameKey.isNotBlank()) {
            val itemName = CourseNameUtils.normalizeKey(item.courseName)
            if (itemName == nameKey) score += 60
            if (item.aliases.any { CourseNameUtils.normalizeKey(it) == nameKey }) score += 40
        }
        return score
    }


    private fun selectBestMatch(
        items: List<CourseResourceItem>,
        normalizedCode: String?,
        normalizedName: String?,
    ): CourseResourceItem? {
        val code = normalizedCode?.trim()?.lowercase()
        val nameKey = CourseNameUtils.normalizeKey(normalizedName)
        fun nameMatches(raw: String?): Boolean {
            if (nameKey.isBlank()) return false
            val key = CourseNameUtils.normalizeKey(raw)
            if (key.isBlank()) return false
            return key == nameKey || key.contains(nameKey) || nameKey.contains(key)
        }
        if (!code.isNullOrBlank()) {
            items.firstOrNull { it.courseCode.equals(code, ignoreCase = true) }?.let { return it }
            items.firstOrNull { it.repoName.equals(code, ignoreCase = true) }?.let { return it }
        }
        if (nameKey.isNotBlank()) {
            items.firstOrNull { nameMatches(it.courseName) }?.let { return it }
            items.firstOrNull { it.aliases.any { alias -> nameMatches(alias) } }
                ?.let { return it }
        }
        return if (items.size == 1) items.first() else null
    }

    private fun openFallback(
        context: Context,
        normalizedCode: String?,
        normalizedName: String?,
        courseCodeRaw: String?,
        courseNameRaw: String?,
    ) {
        val displayCode = normalizedCode ?: courseCodeRaw ?: ""
        val displayName = normalizedName ?: courseNameRaw ?: displayCode
        val repoName = when {
            displayCode.isNotBlank() && displayName.isNotBlank() -> {
                if (displayName.equals(displayCode, ignoreCase = true)) {
                    displayCode
                } else {
                    "${displayCode}-${displayName}"
                }
            }
            displayCode.isNotBlank() -> displayCode
            else -> displayName.ifBlank { "new-course" }
        }
        ActivityUtils.startCourseReadmeActivity(
            context,
            repoName = repoName,
            courseName = displayName,
            courseCode = displayCode.ifBlank { repoName },
            repoType = "normal",
        )
    }
}
