package cn.limpu.hita.ui.main.timetable.views

import cn.limpu.hita.data.model.timetable.EventItem
import kotlin.math.max

object TimetableOverlapLayout {
    data class PositionedEvent(
        val event: EventItem,
        val columnIndex: Int,
        val overlapCount: Int
    )

    private data class Cluster(
        val events: MutableList<EventItem> = mutableListOf(),
        var endTime: Long = Long.MIN_VALUE
    )

    private data class ActiveColumn(
        val columnIndex: Int,
        val endTime: Long
    )

    fun arrange(events: List<EventItem>, dayOfWeek: (EventItem) -> Int = { it.getDow() }): List<PositionedEvent> {
        if (events.isEmpty()) {
            return emptyList()
        }
        val distinctEvents = events.distinctBy { it.id }
        val positioned = mutableListOf<PositionedEvent>()
        distinctEvents.groupBy(dayOfWeek)
            .toSortedMap()
            .values
            .forEach { dayEvents ->
                val sortedEvents = dayEvents.sortedWith(
                    compareBy<EventItem>({ it.from.time }, { it.to.time }, { it.id })
                )
                splitIntoClusters(sortedEvents).forEach { cluster ->
                    positioned.addAll(positionCluster(cluster.events))
                }
            }
        return positioned
    }

    /**
     * Groups events that are on screen at the same time into one conflict card.
     *
     * A chain where A overlaps B and B overlaps C, but A does not overlap C, must not
     * become a single card: C would be hidden even though it does not conflict with A.
     * Events that never share a moment stay ordinary cards, including a chain tail.
     */
    fun conflictCards(arranged: List<PositionedEvent>, dayOfWeek: (EventItem) -> Int = { it.getDow() }): List<Pair<PositionedEvent, List<EventItem>?>> {
        val result = mutableListOf<Pair<PositionedEvent, List<EventItem>?>>()
        var index = 0
        while (index < arranged.size) {
            val current = arranged[index]
            if (current.overlapCount <= 1) {
                result += current to null
                index++
                continue
            }
            val cluster = mutableListOf(current)
            var nextIndex = index + 1
            while (nextIndex < arranged.size) {
                val next = arranged[nextIndex]
                if (dayOfWeek(next.event) != dayOfWeek(current.event) || next.overlapCount <= 1) break
                val overlapsEveryMember = cluster.all { member ->
                    intervalsOverlap(member.event, next.event)
                }
                if (!overlapsEveryMember) break
                cluster += next
                nextIndex++
            }
            result += if (cluster.size == 1) {
                current to null
            } else {
                current to cluster.map { it.event }
            }
            index = nextIndex
        }
        return result
    }

    private fun intervalsOverlap(left: EventItem, right: EventItem): Boolean =
        left.from.time < right.to.time && right.from.time < left.to.time

    private fun splitIntoClusters(events: List<EventItem>): List<Cluster> {
        if (events.isEmpty()) {
            return emptyList()
        }
        val clusters = mutableListOf<Cluster>()
        var current: Cluster? = null
        for (event in events) {
            if (current == null || event.from.time >= current.endTime) {
                current = Cluster()
                clusters.add(current)
            }
            current.events.add(event)
            current.endTime = max(current.endTime, event.to.time)
        }
        return clusters
    }

    private fun positionCluster(events: List<EventItem>): List<PositionedEvent> {
        val positioned = mutableListOf<PositionedEvent>()
        val activeColumns = mutableListOf<ActiveColumn>()
        var maxColumns = 1

        for (event in events) {
            activeColumns.removeAll { it.endTime <= event.from.time }
            val usedColumns = activeColumns.map { it.columnIndex }.toSet()
            var columnIndex = 0
            while (usedColumns.contains(columnIndex)) {
                columnIndex++
            }
            activeColumns.add(ActiveColumn(columnIndex, event.to.time))
            activeColumns.sortBy { it.columnIndex }
            maxColumns = max(maxColumns, activeColumns.size)
            positioned.add(PositionedEvent(event, columnIndex, 1))
        }

        return positioned.map { it.copy(overlapCount = maxColumns) }
    }
}
