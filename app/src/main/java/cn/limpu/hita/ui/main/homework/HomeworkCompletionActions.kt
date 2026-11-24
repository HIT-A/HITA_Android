package cn.limpu.hita.ui.main.homework

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class TodoCompletionPhase {
    Saving, Saved, SaveFailed, Undoing, Undone, UndoFailed,
}

internal data class TodoCompletionReceipt(
    val title: String,
    val completed: Boolean,
    val phase: TodoCompletionPhase,
) {
    val busy: Boolean get() = phase == TodoCompletionPhase.Saving || phase == TodoCompletionPhase.Undoing
    val canUndo: Boolean get() = phase == TodoCompletionPhase.Saved || phase == TodoCompletionPhase.UndoFailed
}

/** UI-thread actions, keyed by source, account and item; the board itself comes from storage. */
internal class HomeworkCompletionActions(private val persist: suspend (TodoKey, Boolean) -> Boolean) {
    private val mutableReceipts = MutableStateFlow<Map<TodoKey, TodoCompletionReceipt>>(emptyMap())
    val receipts = mutableReceipts.asStateFlow()

    suspend fun change(row: TodoRow, completed: Boolean) {
        if (row.completed == completed || mutableReceipts.value[row.key]?.busy == true) return
        write(row.key, TodoCompletionReceipt(row.title, completed, TodoCompletionPhase.Saving))
    }

    suspend fun undo(key: TodoKey, receipt: TodoCompletionReceipt) {
        if (mutableReceipts.value[key] !== receipt || !receipt.canUndo) return
        write(key, receipt.copy(phase = TodoCompletionPhase.Undoing))
    }

    fun dismiss(key: TodoKey, receipt: TodoCompletionReceipt) {
        if (mutableReceipts.value[key] === receipt && !receipt.busy) forget(key)
    }

    fun forget(key: TodoKey) {
        mutableReceipts.value = mutableReceipts.value - key
    }

    private suspend fun write(key: TodoKey, attempt: TodoCompletionReceipt) {
        val undoing = attempt.phase == TodoCompletionPhase.Undoing
        mutableReceipts.value = mutableReceipts.value + (key to attempt)
        try {
            val success = persist(key, if (undoing) !attempt.completed else attempt.completed)
            if (mutableReceipts.value[key] !== attempt) return
            val phase = when {
                undoing && success -> TodoCompletionPhase.Undone
                undoing -> TodoCompletionPhase.UndoFailed
                success -> TodoCompletionPhase.Saved
                else -> TodoCompletionPhase.SaveFailed
            }
            mutableReceipts.value = mutableReceipts.value + (key to attempt.copy(phase = phase))
        } finally {
            if (mutableReceipts.value[key] === attempt) forget(key)
        }
    }
}
