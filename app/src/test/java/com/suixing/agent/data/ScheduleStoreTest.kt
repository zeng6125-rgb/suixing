package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduleStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A fixed zone and fixed "now": the arithmetic is about the clock, so the test pins it. */
    private val zone: ZoneId = ZoneId.of("UTC")

    private fun at(text: String): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private val now: Long = at("2026-10-09T10:00:00")

    private fun store() = ScheduleStore(File(folder.root, "schedules.json"))

    private fun daily(time: String) =
        Schedule(id = "d1", title = "每天", prompt = "念待办", kind = SCHEDULE_DAILY, timeOfDay = time)

    private fun every(seconds: Long) =
        Schedule(id = "e1", title = "每隔", prompt = "喝水", kind = SCHEDULE_EVERY, everySeconds = seconds)

    // ---- re-arm arithmetic: when does this fire next? ----

    @Test
    fun `a one-shot fires at its moment, and never once that moment has passed`() {
        val future = Schedule(
            id = "a1", title = "开会", prompt = "提醒我", kind = SCHEDULE_AT, atMillis = at("2026-10-09T18:00:00"),
        )
        assertEquals(at("2026-10-09T18:00:00"), future.nextTriggerAfter(now, zone))

        val past = future.copy(atMillis = at("2026-10-09T09:00:00"))
        assertNull(past.nextTriggerAfter(now, zone))
    }

    /** Counted from the firing moment, so a late alarm resets the cadence instead of doubling up. */
    @Test
    fun `a fixed interval counts from now`() {
        assertEquals(now + 600_000L, every(600).nextTriggerAfter(now, zone))
        assertNull(every(0).nextTriggerAfter(now, zone))
    }

    @Test
    fun `a daily time later today fires today`() {
        assertEquals(at("2026-10-09T23:30:00"), daily("23:30").nextTriggerAfter(now, zone))
    }

    @Test
    fun `a daily time already gone fires tomorrow`() {
        assertEquals(at("2026-10-10T07:30:00"), daily("07:30").nextTriggerAfter(now, zone))
    }

    /** Strictly after: firing again at the very moment it just fired would double up. */
    @Test
    fun `a daily time equal to now fires tomorrow`() {
        assertEquals(at("2026-10-10T10:00:00"), daily("10:00").nextTriggerAfter(now, zone))
    }

    @Test
    fun `seconds in a daily time are accepted`() {
        assertEquals(at("2026-10-09T23:30:15"), daily("23:30:15").nextTriggerAfter(now, zone))
    }

    @Test
    fun `an unparseable daily time never fires`() {
        assertNull(daily("晚上七点").nextTriggerAfter(now, zone))
    }

    // ---- the shapes the tool layer parses ----

    @Test
    fun `local moments parse with and without seconds`() {
        assertEquals(at("2026-10-09T07:30:00"), parseLocalMoment("2026-10-09 07:30", zone))
        assertEquals(at("2026-10-09T07:30:05"), parseLocalMoment("2026-10-09 07:30:05", zone))
        assertNull(parseLocalMoment("明天早上", zone))
    }

    // ---- the file ----

    @Test
    fun `round-trips through the file`() {
        val store = store()
        store.upsert(daily("07:30").copy(nextAt = at("2026-10-10T07:30:00")))

        val read = store.read()
        assertEquals(1, read.size)
        assertEquals(SCHEDULE_DAILY, read[0].kind)
        assertEquals("07:30", read[0].timeOfDay)
        assertEquals(at("2026-10-10T07:30:00"), read[0].nextAt)
    }

    @Test
    fun `upsert replaces by id instead of piling up`() {
        val store = store()
        store.upsert(daily("07:30"))
        store.upsert(daily("08:00"))

        val read = store.read()
        assertEquals(1, read.size)
        assertEquals("08:00", read[0].timeOfDay)
    }

    @Test
    fun `delete removes only the named one`() {
        val store = store()
        store.upsert(daily("07:30"))
        store.upsert(every(600))

        store.delete("d1")

        assertEquals(listOf("e1"), store.read().map { it.id })
    }

    @Test
    fun `missing file reads as no schedules`() {
        assertTrue(store().read().isEmpty())
    }

    /** The scheduler keeps exactly one alarm, so it asks the board for the soonest moment. */
    @Test
    fun `nextDueAt picks the soonest enabled schedule`() {
        val store = store()
        store.upsert(every(3600))
        store.upsert(daily("11:00"))
        store.upsert(daily("23:00"))

        assertEquals(at("2026-10-09T11:00:00"), store.nextDueAt(now, zone))
    }

    @Test
    fun `nextDueAt ignores disabled schedules and reports none as zero`() {
        val store = store()
        store.upsert(daily("11:00").copy(enabled = false))

        assertEquals(0L, store.nextDueAt(now, zone))
    }
}
