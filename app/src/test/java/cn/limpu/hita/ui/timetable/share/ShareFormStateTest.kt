package cn.limpu.hita.ui.timetable.share

import cn.limpu.hita.feature.timetableshare.protocol.ShareMode
import org.junit.Assert.*
import org.junit.Test

class ShareFormStateTest {
    @Test fun staleGenerationDoesNotReplaceCurrentForm() {
        // 显式带上模式：本条只验证代际，不依赖默认披露等级。
        val old = ShareFormState("a", "好友", mode = ShareMode.FULL)
        val edited = old.edit(id = "b")
        assertNull(edited.accept(old.revision, "HITA1:old").token)
        assertEquals(ShareMode.FULL, old.mode)
    }
    @Test fun newTokenDefaultsToBusyUntilFullIsChosenExplicitly() {
        // 新口令默认只披露忙闲；完整版必须由用户主动选择。
        assertEquals(ShareMode.BUSY, ShareFormState("a", "好友").mode)
    }
    @Test fun editingInvalidatesTokenAndStopsGenerating() {
        val old = ShareFormState("a", "好友", token = "old", generating = true)
        val edited = old.edit(name = "新昵称")
        assertNull(edited.token)
        assertFalse(edited.generating)
        assertEquals(old.revision + 1, edited.revision)
    }
    @Test fun currentGenerationIsAcceptedAndNicknameUsesCodePoints() {
        val state = ShareFormState("a", "😀".repeat(40), generating = true)
        assertTrue(state.nicknameValid)
        assertFalse(state.edit(name = "😀".repeat(41)).nicknameValid)
        assertFalse(state.edit(name = " ").nicknameValid)
        assertEquals("token", state.accept(state.revision, "token").token)
        assertFalse(state.accept(state.revision, "token").generating)
    }
}
