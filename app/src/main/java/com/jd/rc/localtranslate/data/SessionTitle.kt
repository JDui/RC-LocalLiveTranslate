package com.jd.rc.localtranslate.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Freeze the device-local start time and user-supplied city in the saved title. */
object SessionTitle {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH:mm", Locale.ROOT)
    private val legacyTitles = setOf("本地对话", "多语言对话 · 演示")
    fun normalizeCity(city: String): String = city.filter { !it.isISOControl() && it !in "[]【】/\\" }.trim().take(32)
    fun create(createdAt: Long, city: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val place = normalizeCity(city).ifBlank { "未设置" }
        return "${formatter.format(Instant.ofEpochMilli(createdAt).atZone(zone))}-[$place]"
    }
    fun display(session: SavedSession): String = if (session.title in legacyTitles) create(session.createdAt, "") else session.title
    fun exportStem(session: SavedSession): String = display(session).map {
        if (it.isISOControl() || it in "<>:\"/\\|?*") '-' else it
    }.joinToString("").trim().trimEnd('.').ifBlank { "RC-session" }
}
