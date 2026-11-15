package cn.limpu.hita.data.source.web.campus

import cn.limpu.hita.data.model.campus.LibraryPeoples
import cn.limpu.hita.data.model.campus.LibraryRoomNums
import org.json.JSONObject

/**
 * 智慧墙接口的解析器。
 *
 * 信封是 `{success, errorCode, errorMsg, model}`。三条容易被误判的边界都收在这里：
 * - `success=false` 是业务错误，必须把服务端文案透传，而不是当成空数据；
 * - `curInNum` / `haveUse` 等数字服务端**有时返回字符串**（"123"），按 Int 直读会抛异常；
 * - 502 来自 aTrust 网关，响应体是 HTML 错误页而非 JSON，报错文案要说「网关不可用」。
 *
 * 全部函数是纯函数：输入响应文本，输出数据类或 [LibrarySmartWallException]。
 */
internal object LibrarySmartWallParser {

    /** 网关 502 页面的特征标题；命中即判网关不可用。 */
    private const val GATEWAY_MARKER = "aTrust"

    fun parsePeoples(body: String): LibraryPeoples {
        val model = model(body)
        return LibraryPeoples(
            currentInLibrary = int(model, "curInNum"),
            todayVisitors = int(model, "todayInNum"),
        )
    }

    fun parseRoomNums(body: String): LibraryRoomNums {
        val model = model(body)
        return LibraryRoomNums(
            occupied = int(model, "haveUse"),
            available = int(model, "useAble"),
        )
    }

    /**
     * 判定 HTTP 502 响应体是否为 aTrust 网关错误页。
     *
     * 网关错误页也可能带着 `aTrust` 字样出现在 200 里（部分部署把页面塞进既有状态码），
     * 所以调用方应在解析失败前先用这个函数判一次。
     */
    fun isGatewayErrorPage(body: String): Boolean =
        body.contains(GATEWAY_MARKER) || body.contains("应用服务器连接建立失败")

    private fun model(body: String): JSONObject {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) throw LibrarySmartWallException("图书馆接口返回了空响应")
        if (isGatewayErrorPage(trimmed)) {
            throw LibrarySmartWallException("图书馆网关暂时不可用（502），请稍后重试")
        }
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            throw LibrarySmartWallException("图书馆服务返回了无法识别的响应")
        }
        if (!root.optBoolean("success", false)) {
            val message = root.optString("errorMsg").trim()
            val code = root.optString("errorCode").trim()
            throw LibrarySmartWallException(
                when {
                    message.isNotEmpty() -> message
                    code.isNotEmpty() -> "图书馆服务返回错误码 $code"
                    else -> "图书馆服务拒绝了这次查询"
                }
            )
        }
        return root.optJSONObject("model")
            ?: throw LibrarySmartWallException("图书馆接口未返回有效数据")
    }

    /**
     * 读取一个「数字或数字字符串」字段。
     *
     * 服务端把整型序列化成字符串是已知行为；"12.0" 这类浮点写法也出现过多媒体接口，
     * 这里一并接受，但只保留整数部分，避免把小数当成两个不同的值。
     */
    private fun int(root: JSONObject, key: String): Int {
        if (!root.has(key) || root.isNull(key)) {
            throw LibrarySmartWallException("图书馆接口缺少字段 $key")
        }
        val raw = root.opt(key)
        val text = when (raw) {
            is Int -> return raw
            is Long -> return raw.toInt()
            is Number -> return raw.toInt()
            is String -> raw.trim()
            else -> throw LibrarySmartWallException("图书馆接口字段 $key 类型无法识别")
        }
        if (text.isEmpty()) throw LibrarySmartWallException("图书馆接口字段 $key 为空")
        text.toIntOrNull()?.let { return it }
        text.toDoubleOrNull()?.let { return it.toInt() }
        throw LibrarySmartWallException("图书馆接口字段 $key 不是数字：$text")
    }
}

/** 智慧墙查询失败：文案已经可读，UI 直接展示 [message]。 */
internal class LibrarySmartWallException(message: String) : Exception(message)
