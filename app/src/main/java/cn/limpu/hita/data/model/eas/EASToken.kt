package cn.limpu.hita.data.model.eas

import java.util.*

class EASToken {

    companion object {
        fun hasShenzhenWebSessionCookies(cookies: Map<String, String>): Boolean {
            val hasDirectSession = cookies["JSESSIONID"].orEmpty().isNotBlank() &&
                cookies["route"].orEmpty().isNotBlank()
            val hasProxySession = cookies["SESSION"].orEmpty().isNotBlank()
            return hasDirectSession || hasProxySession
        }
    }

    enum class TYPE { UNDERGRAD, GRAD }

    enum class Campus {
        SHENZHEN,
        BENBU,
        WEIHAI
    }

    // --- 新 API (mjw.hitsz.edu.cn/incoSpringBoot) bearer token 认证 ---
    var accessToken: String? = null
    var refreshToken: String? = null
    // route / JSESSIONID 由 Jsoup session 自动管理，同时存一份供跨请求复用
    var cookies = HashMap<String, String>()

    // --- 深圳 Web 教务 (jw.hitsz.edu.cn) 独立 Cookie 会话 ---
    // 直连旧会话使用 JSESSIONID/route；网页代理新会话使用 SESSION，并通常同时携带
    // sdp_user_token。它们都必须与 mjw App API 的 Cookie 分开保存，禁止互相覆盖。
    var webCookies = HashMap<String, String>()
    // 校外通过 hitsz.edu.cn 网页代理访问时记录代理根地址；旧会话为空时仍使用直连地址。
    var webBaseUrl: String? = null
    // Repository-managed generation used to reject responses from requests started before logout.
    var sessionGeneration: Long = 0L

    // --- 本部电子实验中心 JWT token (用于 eelabinfo-hit-edu-cn.vpn.hit.edu.cn) ---
    var electronicExpToken: String? = null

    var campus: Campus = Campus.SHENZHEN
    var username: String? = null
    var password: String? = null
    var name: String? = null
    var stutype: TYPE = TYPE.UNDERGRAD // 培养类型，1本科生，其他研究生
    var picture: String? = null //照片
    var id: String? = null //学生id
    var stuId: String? = null //学号
    var school: String? = null // 学院
    var major: String? = null //专业
    var grade: String? = null //年级
    var className: String? = null //班级
    var sfxsx: String? = null
    var email: String? = null //邮箱
    var phone: String? = null //电话

    fun getStudentType(): String {
        return if (stutype == TYPE.UNDERGRAD) "1" else "2"
    }

    fun isBenbuCampus(): Boolean {
        return campus == Campus.BENBU
    }

    fun isLogin(): Boolean {
        return !accessToken.isNullOrEmpty() || cookies.isNotEmpty() || webCookies.isNotEmpty()
    }

    fun hasShenzhenWebSession(): Boolean {
        return campus == Campus.SHENZHEN && hasShenzhenWebSessionCookies(webCookies)
    }

    override fun toString(): String {
        return "EASToken(campus=$campus, accessToken=${accessToken?.take(10)}..., hasWebSession=${hasShenzhenWebSession()}, electronicExpToken=${electronicExpToken?.take(10)}..., username=$username, name=$name, stutype=${getStudentType()}, stuId=$stuId, school=$school)"
    }


}
