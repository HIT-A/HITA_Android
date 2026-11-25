package cn.limpu.hita.ui.main.blog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlogExpansionPolicyTest {

    @Test
    fun `series auto expands on unread until the user toggles it`() {
        assertTrue(expanded("series-1", autoExpand = true))
        assertFalse(expanded("series-1", autoExpand = false))
    }

    @Test
    fun `manual collapse beats auto expand even when unread appears later`() {
        val (expandedIds, collapsedIds) =
            BlogExpansionPolicy.toggle("series-1", expand = false, emptySet(), emptySet())

        assertFalse(expanded("series-1", autoExpand = true, expandedIds, collapsedIds))
        assertFalse(expanded("series-1", autoExpand = false, expandedIds, collapsedIds))
    }

    @Test
    fun `manual expand wins over a default collapsed row`() {
        val (expandedIds, collapsedIds) =
            BlogExpansionPolicy.toggle("series-1", expand = true, emptySet(), emptySet())

        assertTrue(expanded("series-1", autoExpand = false, expandedIds, collapsedIds))
        assertTrue(collapsedIds.isEmpty())
    }

    @Test
    fun `toggling one id leaves the others untouched`() {
        val (expandedIds, collapsedIds) = BlogExpansionPolicy.toggle(
            id = "series-b",
            expand = true,
            expandedIds = setOf("series-a"),
            collapsedIds = setOf("series-c"),
        )

        assertEquals(setOf("series-a", "series-b"), expandedIds)
        assertEquals(setOf("series-c"), collapsedIds)
    }

    @Test
    fun `an id never stays in both sets`() {
        val expandedFirst = BlogExpansionPolicy.toggle("series-a", expand = true, emptySet(), setOf("series-a"))
        assertFalse("series-a" in expandedFirst.second)

        val collapsedAgain = BlogExpansionPolicy.toggle("series-a", expand = false, setOf("series-a"), emptySet())
        assertFalse("series-a" in collapsedAgain.first)
    }

    @Test
    fun `plain articles never expand`() {
        assertFalse(
            BlogExpansionPolicy.isExpanded(
                id = "article-1",
                isSeries = false,
                autoExpand = true,
                expandedIds = setOf("article-1"),
                collapsedIds = emptySet(),
            )
        )
    }

    private fun expanded(
        id: String,
        autoExpand: Boolean,
        expandedIds: Set<String> = emptySet(),
        collapsedIds: Set<String> = emptySet(),
    ): Boolean = BlogExpansionPolicy.isExpanded(
        id = id,
        isSeries = true,
        autoExpand = autoExpand,
        expandedIds = expandedIds,
        collapsedIds = collapsedIds,
    )
}
