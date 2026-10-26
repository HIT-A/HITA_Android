package cn.limpu.hita.feature.timetableshare.protocol

enum class ImportKind { NEW, SAME, UPDATE }

data class FriendImportPreview(
    val snapshot: SharedTimetable,
    val kind: ImportKind,
    val expectedDigest: String?,
    val existingRemark: String?
)

sealed interface FriendSaveResult {
    data class Saved(val shareId: String) : FriendSaveResult
    data class Unchanged(val shareId: String) : FriendSaveResult
    data class NeedsConfirmation(val preview: FriendImportPreview) : FriendSaveResult
}

object FriendImportPolicy {
    fun classify(incomingDigest: String, existingDigest: String?): ImportKind = when {
        existingDigest == null -> ImportKind.NEW
        incomingDigest == existingDigest -> ImportKind.SAME
        else -> ImportKind.UPDATE
    }
}
