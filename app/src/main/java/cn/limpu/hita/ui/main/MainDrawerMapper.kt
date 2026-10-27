package cn.limpu.hita.ui.main

import cn.limpu.hita.data.model.eas.EASToken
import com.limpu.hitauser.data.model.UserLocal

data class DrawerUserState(
    val title: String = "",
    val subtitle: String = "",
    val avatar: String? = null,
    val loggedInLocalUser: Boolean = false,
)

data class MainDrawerText(
    val notLoggedInTitle: String,
    val notLoggedInSubtitle: String,
    val sessionExpiredShort: String,
)

/** Profile values only: no mutable account model or credentials are retained. */
data class MainLocalUserSnapshot(
    val valid: Boolean,
    val username: String?,
    val nickname: String?,
    val avatar: String?,
) {
    companion object {
        fun from(user: UserLocal) = MainLocalUserSnapshot(user.isValid(), user.username, user.nickname, user.avatar)
    }
}

data class MainEasUserSnapshot(
    val loggedIn: Boolean,
    val name: String?,
    val studentId: String?,
    val username: String?,
    val school: String?,
    val major: String?,
    val grade: String?,
    val className: String?,
) {
    companion object {
        fun from(token: EASToken) = MainEasUserSnapshot(
            token.isLogin(), token.name, token.stuId, token.username,
            token.school, token.major, token.grade, token.className,
        )
    }
}

object MainDrawerMapper {
    fun map(
        local: MainLocalUserSnapshot,
        eas: MainEasUserSnapshot,
        sessionExpired: Boolean,
        text: MainDrawerText,
    ): DrawerUserState {
        if (local.valid) return DrawerUserState(
            title = local.username.orEmpty(),
            subtitle = local.nickname.orEmpty(),
            avatar = local.avatar,
            loggedInLocalUser = true,
        )
        if (!eas.loggedIn) return DrawerUserState(text.notLoggedInTitle, text.notLoggedInSubtitle)
        val subtitle = buildString {
            append(eas.studentId?.trim().orEmpty())
            val secondary = listOf(eas.school, eas.major, eas.grade, eas.className)
                .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                .joinToString(" · ")
            if (secondary.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(secondary)
            }
        }.ifBlank { eas.username.orEmpty() }.let { base ->
            if (sessionExpired) base + "\n" + text.sessionExpiredShort else base
        }
        return DrawerUserState(
            title = eas.name?.ifBlank { eas.studentId?.ifBlank { eas.username } }
                ?: eas.studentId?.ifBlank { eas.username }
                ?: eas.username
                ?: text.notLoggedInTitle,
            subtitle = subtitle,
            avatar = local.avatar,
        )
    }
}
