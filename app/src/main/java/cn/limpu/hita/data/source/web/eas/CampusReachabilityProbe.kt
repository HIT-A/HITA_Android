package cn.limpu.hita.data.source.web.eas

/**
 * 判断某个入口地址在当前网络下是否可达。
 *
 * 只有一个方法，是因为调用方只需要一个是非答案；超时、重试、是否跟随重定向
 * 都属于实现的内部细节，不该泄漏到这里。
 *
 * 实现必须是**快速失败**的：它跑在登录前的关键路径上，不能让用户等。
 */
fun interface CampusReachabilityProbe {
    /** 返回该 URL 是否可达（能建立连接并得到任意 HTTP 响应即算可达）。 */
    suspend fun isReachable(url: String): Boolean
}
