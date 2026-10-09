package cn.limpu.hita.ui.timetable.friend

import cn.limpu.hita.feature.timetableshare.protocol.FriendImportPreview

data class FriendImportUiState(
    val input: String = "", val preview: FriendImportPreview? = null,
    val loading: Boolean = false, val revision: Long = 0,
    val saving: Boolean = false, val errorResource: Int? = null
) {
    val canConfirm get() = preview != null && !loading && !saving
    fun edit(text: String) = FriendImportUiState(text, revision = revision + 1)
    fun begin() = copy(preview = null, loading = true, errorResource = null, revision = revision + 1)
    fun beginSave() = copy(saving = true, errorResource = null)
    fun accept(generationRevision: Long, result: FriendImportPreview) =
        if (generationRevision == revision) copy(preview = result, loading = false, saving = false, errorResource = null) else this
    fun fail(generationRevision: Long, error: Int) =
        if (generationRevision == revision) copy(loading = false, saving = false, errorResource = error) else this
}

fun normalizeFriendRemark(text: String): String? = text.trim().takeIf { it.isNotEmpty() }
fun friendRemarkValid(text: String): Boolean = text.trim().let { it.codePointCount(0, it.length) <= 40 }
