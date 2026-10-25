package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.EASToken
import org.junit.Assert.*
import org.junit.Test

class EasTimetableImportGuardTest {
    private fun token(name: String = "student-a") = EASToken().apply {
        campus = EASToken.Campus.BENBU
        username = name
        cookies["JSESSIONID"] = "test-session"
    }
    private fun started() = EasTimetableImportGuard(token(), 7L, 90_000L).also {
        assertTrue(it.tryStartProcessing())
    }

    @Test fun logoutAndReloginOfSameAccountRejectOldImportGeneration() {
        assertFalse(started().tryBeginCommit(8L, token(), 100L))
        assertFalse(started().tryBeginCommit(7L, EASToken(), 100L))
    }
    @Test fun accountCampusAndStudentTypeChangesRejectOldImport() {
        assertFalse(started().tryBeginCommit(7L, token("student-b"), 100L))
        assertFalse(started().tryBeginCommit(7L, token().apply { campus = EASToken.Campus.WEIHAI }, 100L))
        assertFalse(started().tryBeginCommit(7L, token().apply { stutype = EASToken.TYPE.GRAD }, 100L))
    }
    @Test fun timeoutDuringParsingPreventsLateWritesAndDuplicateCompletion() {
        val guard = started()
        assertTrue(guard.finishWaiting())
        assertFalse(guard.tryBeginCommit(7L, token(), 100L))
        assertFalse(guard.finishProcessing())
    }
    @Test fun delayedTimeoutHandlerDoesNotAllowWritesPastDeadline() {
        assertFalse(started().tryBeginCommit(7L, token(), 90_000L))
        assertFalse(started().tryBeginCommit(7L, token(), 90_001L))
    }
    @Test fun successfulCommitCannotBeOvertakenByTimeoutOrAnotherResponse() {
        val guard = started()
        assertFalse(guard.tryStartProcessing())
        assertTrue(guard.tryBeginCommit(7L, token(), 89_999L))
        assertFalse(guard.finishWaiting())
        assertFalse(guard.tryBeginCommit(7L, token(), 89_999L))
        assertTrue(guard.finishProcessing())
        assertFalse(guard.finishProcessing())
    }
    @Test fun writeFailureCanCompleteOnceAfterCommitWasClaimed() {
        val guard = started()
        assertTrue(guard.tryBeginCommit(7L, token(), 100L))
        assertTrue(guard.finishProcessing())
        assertFalse(guard.finishWaiting())
        assertFalse(guard.tryStartProcessing())
    }
    @Test fun identifierEnrichmentAndCookieRenewalPreserveSameSession() {
        val guard = started()
        val refreshed = token().apply { stuId = "2026001"; id = "internal-id"; cookies["JSESSIONID"] = "renewed" }
        assertTrue(guard.tryBeginCommit(7L, refreshed, 100L))
    }
    @Test fun capturingIdentityDoesNotFollowMutationsOfRequestToken() {
        val request = token()
        val guard = EasTimetableImportGuard(request, 7L, 90_000L)
        request.username = "student-b"
        assertTrue(guard.tryStartProcessing())
        assertFalse(guard.tryBeginCommit(7L, request, 100L))
    }

    @Test fun onlyCommittingOperationCanReceiveCompletionGracePeriod() {
        val guard = started()
        assertFalse(guard.isCommitting())
        assertTrue(guard.tryBeginCommit(7L, token(), 100L))
        assertTrue(guard.isCommitting())
        assertFalse(guard.finishWaiting())
        assertTrue(guard.finishProcessing())
        assertFalse(guard.finishWaiting())
        assertFalse(guard.isCommitting())
    }
}
