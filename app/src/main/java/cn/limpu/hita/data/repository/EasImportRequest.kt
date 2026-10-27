package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.TermItem

/** Mutable DTOs never remain shared with controls after an operation is started. */
internal fun TermItem.copyForQuery() = TermItem(yearCode, yearName, termCode, termName).also {
    it.id = id
    it.name = name
    it.isCurrent = isCurrent
}
