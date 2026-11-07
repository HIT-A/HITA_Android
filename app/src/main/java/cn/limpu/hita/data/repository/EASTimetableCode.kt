package cn.limpu.hita.data.repository

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem

object EASTimetableCode {
    /**
     * 威海本科与研究生共用校区代码时，同一学期切换培养层次会互相覆盖课表。
     * 研究生使用独立前缀，本科保持既有格式不变。
     */
    fun of(
        campus: EASToken.Campus,
        term: TermItem,
        studentType: EASToken.TYPE = EASToken.TYPE.UNDERGRAD,
    ): String {
        val prefix = if (campus == EASToken.Campus.WEIHAI && studentType == EASToken.TYPE.GRAD) {
            "WEIHAI_GRAD"
        } else {
            campus.name
        }
        return "$prefix:${term.getCode()}"
    }

    fun candidates(
        term: TermItem,
        campus: EASToken.Campus? = null,
        studentType: EASToken.TYPE = EASToken.TYPE.UNDERGRAD,
    ): List<String> {
        val preferred = campus?.let { of(it, term, studentType) }
        return listOfNotNull(preferred)
            .plus(term.getCode())
            .distinct()
    }
}
