package cn.limpu.hita.ui.main

/** DisplayScopeSelector owns persistence and effects; this only chooses the week to show. */
fun mainScopeTargetMonday(startMillis: Long, endMillis: Long, nowMillis: Long): Long {
    val weekMillis = 7L * 24 * 3600 * 1000
    return if (nowMillis in startMillis..endMillis) {
        val weekOffset = ((nowMillis - startMillis) / weekMillis).coerceAtLeast(0)
        startMillis + weekOffset * weekMillis
    } else {
        startMillis
    }
}
