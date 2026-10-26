package cn.limpu.hita.ui.eas

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import cn.limpu.hita.data.model.eas.TermItem

/** TermItem equality only compares id; a scalar snapshot makes metadata changes visible to Compose. */
internal data class TermSelectionDisplay(
    val yearCode: String,
    val yearName: String,
    val termCode: String,
    val termName: String,
    val name: String,
    val id: String,
    val isCurrent: Boolean,
) {
    fun toTermItem() = TermItem(yearCode, yearName, termCode, termName).also {
        it.name = name
        it.id = id
        it.isCurrent = isCurrent
    }

    companion object {
        fun from(term: TermItem) = TermSelectionDisplay(
            term.yearCode, term.yearName, term.termCode, term.termName, term.name, term.id, term.isCurrent,
        )
    }
}

/** Display metadata can advance without publishing another selection/request event. */
internal class TermSelectionDisplaySource(
    private val selected: LiveData<out TermItem?>,
) : MediatorLiveData<TermSelectionDisplay?>() {
    private var options: List<TermItem> = emptyList()

    init { addSource(selected) { publish() } }

    fun updateOptions(terms: List<TermItem>) {
        options = terms
        publish()
    }

    fun updateTerm(term: TermItem) {
        options = options.filterNot { sameTermKey(it, term) } + term
        publish()
    }

    fun current(): TermItem? = selected.value?.let { current ->
        options.firstOrNull { sameTermKey(it, current) } ?: current
    }

    private fun publish() {
        val next = current()?.let(TermSelectionDisplay::from)
        if (value != next) value = next
    }
}

internal fun sameTermKey(first: TermItem?, second: TermItem?): Boolean =
    first?.yearCode == second?.yearCode && first?.termCode == second?.termCode
