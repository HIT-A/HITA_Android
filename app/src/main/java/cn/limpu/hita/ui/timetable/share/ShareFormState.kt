package cn.limpu.hita.ui.timetable.share

import cn.limpu.hita.feature.timetableshare.protocol.ShareMode

data class ShareFormState(
    val timetableId: String,
    val nickname: String,
    val mode: ShareMode = ShareMode.FULL,
    val revision: Long = 0,
    val token: String? = null,
    val generating: Boolean = false,
) {
    val nicknameValid: Boolean
        get() = nickname.isNotBlank() && nickname.codePointCount(0, nickname.length) in 1..40

    fun edit(id: String = timetableId, name: String = nickname, selectedMode: ShareMode = mode) =
        copy(timetableId = id, nickname = name, mode = selectedMode, revision = revision + 1,
            token = null, generating = false)

    fun accept(generationRevision: Long, result: String) =
        if (generationRevision == revision) copy(token = result, generating = false) else this
}
