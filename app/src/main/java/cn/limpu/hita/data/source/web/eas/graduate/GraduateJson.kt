package cn.limpu.hita.data.source.web.eas.graduate

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Gson 取值辅助：只接受字符串/数字原始值，空串与 "null" 视为缺失。 */
internal object GraduateJson {
    fun objectOf(element: JsonElement): JsonObject =
        element.takeIf { it.isJsonObject }?.asJsonObject ?: invalid()

    fun requiredText(row: JsonObject, key: String): String? = text(row, key)

    fun text(row: JsonObject, key: String): String? {
        val value = row.get(key) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        val primitive = value.asJsonPrimitive
        if (!primitive.isString && !primitive.isNumber) return null
        return primitive.asString.trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    fun int(row: JsonObject, key: String): Int? = text(row, key)?.toIntOrNull()

    private fun invalid(): Nothing = throw GraduateInvalidResponseException()
}
