package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.eas.TermItem

/** Captured when the suspend entry runs, before dispatching its blocking work to IO. */
internal class EmptyClassroomQuerySnapshot(
    term: TermItem,
    building: BuildingItem,
    weeks: List<String>,
) {
    val term = TermItem(term.yearCode, term.yearName, term.termCode, term.termName).apply {
        id = term.id
        name = term.name
        isCurrent = term.isCurrent
    }
    val building = BuildingItem().apply {
        id = building.id
        name = building.name
    }
    val weeks = weeks.toList()
}
