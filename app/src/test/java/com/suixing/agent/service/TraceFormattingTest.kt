package com.suixing.agent.service

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class TraceFormattingTest {
    @Test fun `shared formatter preserves timestamps across zones and daylight saving`() {
        val events = listOf(
            "2026-03-08T06:59:59.123Z",
            "2026-03-08T07:00:00.456Z",
            "2026-10-27T15:59:59.999Z",
        )
        for (zone in listOf("UTC", "Asia/Shanghai", "America/New_York")) {
            val previous = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).apply {
                timeZone = TimeZone.getTimeZone(zone)
            }
            for (event in events) {
                val at = Instant.parse(event).toEpochMilli()
                assertEquals(previous.format(Date(at)), traceStamp(at, ZoneId.of(zone)))
            }
        }
    }
}
