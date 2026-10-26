package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.ExamItem
import com.limpu.component.data.DataState
import kotlinx.coroutines.CancellationException

internal class ExamSessionExpiredException : IllegalStateException()

/** A broken resit page must not discard successfully fetched midterm/final exams. */
internal object BenbuExamQuery {
    fun load(fetch: (String, String) -> List<ExamItem>): DataState<List<ExamItem>> =
        loadInternal(fetch, checkCancelled = null)

    fun loadCancellable(
        checkCancelled: () -> Unit,
        fetch: (String, String) -> List<ExamItem>,
    ): DataState<List<ExamItem>> = loadInternal(fetch, checkCancelled)

    private fun loadInternal(
        fetch: (String, String) -> List<ExamItem>,
        checkCancelled: (() -> Unit)?,
    ): DataState<List<ExamItem>> {
        val exams = mutableListOf<ExamItem>()
        val failures = mutableListOf<String>()
        for ((code, label) in listOf("01" to "期末", "02" to "期中", "03" to "补考")) {
            checkCancelled?.invoke()
            try {
                val batch = fetch(code, label)
                checkCancelled?.invoke()
                exams.addAll(batch)
            } catch (_: ExamSessionExpiredException) {
                return DataState<List<ExamItem>>(exams.toList(), DataState.STATE.NOT_LOGGED_IN).apply {
                    message = "登录已失效，请重新登录"
                }
            } catch (cancelled: CancellationException) {
                // The LiveData entry point historically treats a cancelled category as a failure.
                if (checkCancelled != null) throw cancelled
                failures += label
            } catch (_: Exception) {
                failures += label
            }
        }
        val state = if (failures.isNotEmpty() && exams.isEmpty()) DataState.STATE.FETCH_FAILED else DataState.STATE.SUCCESS
        return DataState<List<ExamItem>>(exams.toList(), state).apply {
            if (failures.isNotEmpty()) message = failures.joinToString("、") + "考试查询失败，请刷新重试"
        }
    }
}
