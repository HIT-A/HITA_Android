package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EASTimetableCodeTest {
    private val term = TermItem("2026-2027", "2026-2027", "1", "秋季")

    @Test fun graduateAndUndergraduateWeihaiCodesNeverCollide() {
        val undergraduate = EASTimetableCode.of(EASToken.Campus.WEIHAI, term, EASToken.TYPE.UNDERGRAD)
        val graduate = EASTimetableCode.of(EASToken.Campus.WEIHAI, term, EASToken.TYPE.GRAD)
        assertEquals("WEIHAI:2026-20271", undergraduate)
        assertEquals("WEIHAI_GRAD:2026-20271", graduate)
        assertNotEquals(undergraduate, graduate)
    }

    @Test fun otherCampusesKeepExistingFormat() {
        assertEquals(
            "BENBU:2026-20271",
            EASTimetableCode.of(EASToken.Campus.BENBU, term, EASToken.TYPE.GRAD),
        )
        assertEquals(
            "SHENZHEN:2026-20271",
            EASTimetableCode.of(EASToken.Campus.SHENZHEN, term, EASToken.TYPE.UNDERGRAD),
        )
    }

    @Test fun candidatesPreferCurrentStageThenLegacyCode() {
        val candidates = EASTimetableCode.candidates(term, EASToken.Campus.WEIHAI, EASToken.TYPE.GRAD)
        assertEquals(listOf("WEIHAI_GRAD:2026-20271", "2026-20271"), candidates)
        assertTrue(
            EASTimetableCode.candidates(term, EASToken.Campus.WEIHAI, EASToken.TYPE.GRAD)
                .none { it == "WEIHAI:2026-20271" },
        )
    }
}
