package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.timetable.DisplayScope

/** Selects a display target and requests its dependent displays to refresh. */
fun interface DisplayScopeSelector {
    fun select(scope: DisplayScope)
}
