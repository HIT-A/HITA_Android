package cn.limpu.hita.ui.main.homework

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val graderDeadlineFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    .withZone(ZoneId.of("Asia/Shanghai"))

internal fun formatGraderDeadline(dueAt: Long): String = graderDeadlineFormat.format(Instant.ofEpochMilli(dueAt))
