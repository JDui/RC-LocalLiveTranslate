package com.jd.rc.localtranslate

import com.jd.rc.localtranslate.data.SavedSession
import com.jd.rc.localtranslate.data.SessionTitle
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SessionTitleTest {
    private val start = Instant.parse("2026-10-12T15:26:00Z").toEpochMilli()
    @Test fun titleUsesCalendarYearLocalStartMinuteAndCity() {
        assertEquals("2026-10-12-23:26-[上海]", SessionTitle.create(start, "上海", ZoneId.of("Asia/Shanghai")))
        assertEquals("2026-10-13-00:26-[東京]", SessionTitle.create(start, "東京", ZoneId.of("Asia/Tokyo")))
        val yearEnd = Instant.parse("2020-12-31T23:59:00Z").toEpochMilli()
        assertEquals("2020-12-31-23:59-[未设置]", SessionTitle.create(yearEnd, "", ZoneId.of("UTC")))
    }
    @Test fun missingCityIsExplicitAndLegacyCustomTitlesArePreserved() {
        val old = SavedSession("old", "本地对话", start, 10, false, "[]")
        assertEquals(SessionTitle.create(start, ""), SessionTitle.display(old))
        assertEquals("自定义记录", SessionTitle.display(old.copy(title = "自定义记录")))
        assertEquals("東京", SessionTitle.normalizeCity(" [東京]\\/\n "))
    }
    @Test fun exportReplacesUnsupportedColonWithoutChangingDisplayedTitle() {
        val title = SessionTitle.create(start, "上海", ZoneId.of("Asia/Shanghai"))
        val session = SavedSession("new", title, start, 10, false, "[]")
        assertEquals("2026-10-12-23-26-[上海]", SessionTitle.exportStem(session))
        assertEquals(title, SessionTitle.display(session))
    }
}
