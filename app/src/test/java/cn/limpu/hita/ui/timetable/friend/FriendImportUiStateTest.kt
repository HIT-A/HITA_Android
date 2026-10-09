package cn.limpu.hita.ui.timetable.friend

import cn.limpu.hita.feature.timetableshare.protocol.*
import org.junit.Assert.*
import org.junit.Test

class FriendImportUiStateTest {
    private fun preview(kind: ImportKind = ImportKind.NEW) = FriendImportPreview(
        SharedTimetable("id", "昵称", ShareMode.BUSY, SharedTerm("表", "学期", 1, 2), emptyList(), emptyList(), emptyList()), kind, null, null)

    @Test fun changedInputInvalidatesPreviewAndLateResults() {
        val initial = FriendImportUiState("old", preview())
        val changed = initial.edit("new")
        assertNull(changed.preview)
        assertNull(changed.accept(initial.revision, preview()).preview)
        assertNull(changed.fail(initial.revision, 99).errorResource)
    }
    @Test fun currentResultEndsLoadingAndCanConfirm() {
        val state = FriendImportUiState("message").begin()
        assertTrue(state.loading)
        assertFalse(state.canConfirm)
        val accepted = state.accept(state.revision, preview())
        assertFalse(accepted.loading)
        assertTrue(accepted.canConfirm)
    }
    @Test fun refreshedPreviewRequiresAnotherConfirmation() {
        val state = FriendImportUiState("message", preview()).beginSave()
        assertFalse(state.canConfirm)
        val refreshed = state.accept(state.revision, preview(ImportKind.UPDATE))
        assertTrue(refreshed.canConfirm)
        assertEquals(ImportKind.UPDATE, refreshed.preview?.kind)
    }
    @Test fun remarkUsesUnicodeCodepointsAndTrims() {
        assertTrue(friendRemarkValid("😀".repeat(40)))
        assertFalse(friendRemarkValid("😀".repeat(41)))
        assertNull(normalizeFriendRemark("  "))
        assertEquals("备注", normalizeFriendRemark(" 备注 "))
    }
}
