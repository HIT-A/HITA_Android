package cn.limpu.hita.data.model.eas

/** Immutable ownership; refreshed cookies never change a query's account or selected route. */
internal data class EasQueryScope(
    val campus: EASToken.Campus,
    val sessionGeneration: Long,
    val ownerKey: String?,
    val grade: String?,
    val studentType: EASToken.TYPE,
    val loggedIn: Boolean,
    val hasWebSession: Boolean,
    val hasBearer: Boolean,
) {
    companion object {
        fun from(token: EASToken) = EasQueryScope(
            token.campus, token.sessionGeneration, termCacheOwnerKey(token), token.grade.orEmpty(),
            token.stutype, token.isLogin(), token.hasShenzhenWebSession(), !token.accessToken.isNullOrBlank(),
        )

        fun termCacheOwnerKey(token: EASToken): String? {
            val identity = sequenceOf(token.stuId, token.id, token.username)
                .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull() ?: return null
            return "${token.campus.name}:$identity"
        }
    }
}

internal data class EasTermKey(val yearCode: String, val termCode: String) {
    companion object {
        fun of(term: TermItem) = EasTermKey(term.yearCode, term.termCode)
    }
}
