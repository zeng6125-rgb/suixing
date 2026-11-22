package com.suixing.agent.data

import com.suixing.agent.ui.settings.prepareUsageViewData
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

    @Test fun preparedViewRefreshesForNewUsageOrCalendarDay() {
        val today = LocalDate.parse("2026-11-07")
        val first = message("a", "2026-11-01", 100)
        val original = snapshot(first)
        val view = prepareUsageViewData(original, today, null)

        assertSame(view, prepareUsageViewData(original, today, view))
        assertEquals(100L, view.windows.getValue(7).usage.tokens.total)

        val nextDay = prepareUsageViewData(original, today.plusDays(1), view)
        assertEquals(0L, nextDay.windows.getValue(7).usage.tokens.total)
        assertEquals(100L, nextDay.heatmap.usage.tokens.total)

        val updated = snapshot(first, message("b", "2026-11-07", 50, "other"))
        val next = prepareUsageViewData(updated, today, view)
        assertEquals(150L, next.windows.getValue(7).usage.tokens.total)
        assertEquals(150L, next.all.tokens.total)
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

    @Test fun batchedWindowsPreserveBoundariesAndModelRanking() {
        val end = LocalDate.parse("2026-11-21")
        val days = (0..120).map { end.minusDays(it.toLong()).toString() }
        val tracked = UsageBucket(TokenTally(prompt = 100, completion = 40, reasoning = 2, cached = 1), 1, 0)
        val unreported = UsageBucket(unreported = 1)
        val snapshot = UsageSnapshot(chats = mapOf(
            "main" to ChatUsage(dailyModels = mapOf(
                days[0] to mapOf("z" to tracked, "unknown" to unreported),
                days[6] to mapOf("a" to tracked),
                days[7] to mapOf("w" to tracked),
                days[29] to mapOf("x" to unreported),
                days[30] to mapOf("x" to tracked),
                days[89] to mapOf("edge" to tracked),
                days[90] to mapOf("outside" to tracked),
            )),
            "child/main/agent" to ChatUsage(dailyModels = mapOf(days[0] to mapOf("unknown" to unreported))),
            "deleted" to ChatUsage(deletedAt = 123L, dailyModels = mapOf(days[0] to mapOf("a" to unreported))),
            "legacy" to ChatUsage(days = mapOf(days[0] to TokenTally(90)), models = mapOf("old" to TokenTally(90))),
        ))
        val counts = listOf(7, 30, 90)
        assertEquals(counts.associateWith { usageWindow(snapshot, it, end) }, usageWindows(snapshot, counts, end))
        assertEquals(counts.associateWith { usageWindow(UsageSnapshot(), it, end) }, usageWindows(UsageSnapshot(), counts, end))
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
