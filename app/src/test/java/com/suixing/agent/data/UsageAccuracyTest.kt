package com.suixing.agent.data

import com.suixing.agent.ui.settings.usageHeatLevel
import com.suixing.agent.ui.settings.usageHeatWeeks
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class UsageAccuracyTest {
    private val utc = ZoneId.of("UTC")
    private fun message(id: String, date: String, prompt: Int, model: String = "model") = StoredMessage(
        id, Roles.ASSISTANT, promptTokens = prompt, model = model,
        timestamp = Instant.parse("${date}T12:00:00Z").toEpochMilli())
    private fun snapshot(vararg messages: StoredMessage) = accumulate(UsageSnapshot(),
        listOf(Conversation("chat", "chat", messages = messages.toList())), utc)

    @Test fun calendarRangeIncludesGapsAndExcludesOlderAndFutureDates() {
        val result = usageWindow(snapshot(message("old", "2026-10-01", 800),
            message("a", "2026-11-01", 10), message("b", "2026-11-07", 20), message("future", "2026-11-08", 90)),
            7, LocalDate.parse("2026-11-07"))
        assertEquals(7, result.days.size)
        assertEquals("2026-11-01", result.days.first().date)
        assertEquals("2026-11-07", result.days.last().date)
        assertEquals(5, result.days.count { it.reported + it.unreported == 0L })
        assertEquals(30L, result.usage.tokens.total)
        assertEquals(30L, result.models.sumOf { it.tokens.total })
    }

    @Test fun unknownResponsesHaveCoverageButNeverFabricateTokenUsage() {
        val result = usageWindow(snapshot(message("known", "2026-11-07", 100),
            message("unknown", "2026-11-07", 0, "other")), 7, LocalDate.parse("2026-11-07"))
        assertEquals(100L, result.usage.tokens.total)
        assertEquals(1L, result.usage.reported)
        assertEquals(1L, result.usage.unreported)
        assertEquals(2L, result.usage.requests)
        assertEquals(1L, result.models.single { it.model == "other" }.unreported)
    }

    @Test fun rewritesReplaceDailyAndModelTotalsIncludingNowUnknownUsage() {
        val old = accumulateSlices(UsageSnapshot(), listOf(UsageSlice("chat",
            listOf(message("a", "2026-11-01", 100, "old")), 0, listOf(100L), 1)), utc)
        val next = accumulateSlices(old, listOf(UsageSlice("chat",
            listOf(message("a", "2026-11-07", 40, "new")), 0, listOf(90L), 2)), utc)
        assertEquals(setOf("2026-11-07"), next.days.keys)
        assertEquals(setOf("new"), next.models.keys)
        assertEquals(40L, next.totals.total)
        val unknown = accumulateSlices(next, listOf(UsageSlice("chat",
            listOf(message("a", "2026-11-07", 0, "new")), 0, listOf(85L), 3)), utc)
        assertEquals(0L, unknown.totals.total)
        assertEquals(1L, unknown.coverage.unreported)
        assertEquals(0L, unknown.coverage.reported)
    }

    @Test fun duplicateRefreshAndChildSourcesDoNotDoubleCount() {
        val main = UsageSlice("parent", listOf(message("a", "2026-11-07", 100)), 0, listOf(100L), 1)
        val child = UsageSlice("child/parent/agent", listOf(message("b", "2026-11-07", 40)), 0, null, -2, "live:1")
        val first = accumulateSlices(UsageSnapshot(), listOf(main, child), utc)
        val again = accumulateSlices(first, listOf(main, child), utc)
        assertEquals(first.coverage, again.coverage)
        assertEquals(140L, again.totals.total)
        val removed = accumulateSlices(again, listOf(main), utc)
        assertEquals(100L, removed.totals.total)
    }

    @Test fun toolAndSystemEstimatesNeverBecomeBillableAssistantUsage() {
        val fake = message("s", "2026-11-07", 900).copy(role = Roles.SYSTEM)
        assertEquals(0L, snapshot(fake).totals.total)
        assertEquals(0L, snapshot(fake).coverage.requests)
    }

    @Test fun totalsUseLongAndDoNotAddCacheOrReasoningAgain() {
        val original = message("a", "2026-11-07", Int.MAX_VALUE).copy(completionTokens = 50,
            cachedTokens = 100, reasoningTokens = 20)
        val result = snapshot(original, original.copy(id = "b"))
        assertEquals(2L * Int.MAX_VALUE + 100, result.totals.total)
        assertEquals(200L, result.totals.cached)
        assertEquals(40L, result.totals.reasoning)
    }

    @Test fun heatmapPadsWeekdaysWithoutInventingDatesOrFutureActivity() {
        val days = usageWindow(UsageSnapshot(), 90, LocalDate.parse("2026-11-07")).days
        val weeks = usageHeatWeeks(days)
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(days, weeks.flatten().filterNotNull())
        assertEquals(0, usageHeatLevel(0, 100))
        assertEquals(0, usageHeatLevel(0, 0))
        assertEquals(1, usageHeatLevel(1, 100))
        assertEquals(4, usageHeatLevel(100, 100))
    }
}
