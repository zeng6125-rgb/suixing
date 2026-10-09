package com.suixing.agent.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The roll-up is the part that can silently go wrong in production — a chat counted
 * twice, a message filed under the wrong day, an unpriced turn inflating the total.
 * [accumulate] and its companions are pure and zone-injectable, so every one of
 * those properties is checked here without a device or a real clock.
 */
class UsageStoreTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private val day1 = Instant.parse("2026-03-01T10:00:00Z").toEpochMilli()
    private val day2 = Instant.parse("2026-03-02T09:00:00Z").toEpochMilli()

    private fun msg(
        id: String,
        prompt: Int = 0,
        completion: Int = 0,
        reasoning: Int = 0,
        cached: Int = 0,
        model: String = "",
        ts: Long = day1,
    ) = StoredMessage(
        id = id,
        role = Roles.ASSISTANT,
        promptTokens = prompt,
        completionTokens = completion,
        reasoningTokens = reasoning,
        cachedTokens = cached,
        model = model,
        timestamp = ts,
    )

    private fun convo(id: String, messages: List<StoredMessage> = emptyList()) =
        Conversation(id = id, title = id, messages = messages)

    @Test
    fun `a single scan sums the four buckets into one day`() {
        val snapshot = accumulate(
            UsageSnapshot(),
            listOf(convo("c1", listOf(msg("m1", prompt = 100, completion = 40, reasoning = 10, cached = 5)))),
            utc,
        )
        val tally = snapshot.days.getValue("2026-03-01")
        assertEquals(100L, tally.prompt)
        assertEquals(40L, tally.completion)
        assertEquals(10L, tally.reasoning)
        assertEquals(5L, tally.cached)
        assertEquals(140L, tally.total)
    }

    @Test
    fun `messages on different calendar days land in different buckets`() {
        val snapshot = accumulate(
            UsageSnapshot(),
            listOf(
                convo(
                    "c1",
                    listOf(
                        msg("m1", prompt = 100, ts = day1),
                        msg("m2", prompt = 200, ts = day2),
                    ),
                ),
            ),
            utc,
        )
        assertEquals(100L, snapshot.days.getValue("2026-03-01").prompt)
        assertEquals(200L, snapshot.days.getValue("2026-03-02").prompt)
        assertEquals(300L, snapshot.totals.prompt)
    }

    @Test
    fun `a re-scan of the same chats does not double-count`() {
        val chats = listOf(convo("c1", listOf(msg("m1", prompt = 100, completion = 40))))
        val first = accumulate(UsageSnapshot(), chats, utc)
        // Same conversations, but now starting from the first snapshot's watermarks:
        // nothing is newer than what was already counted, so the totals must not move.
        val second = accumulate(first, chats, utc)
        assertEquals(first.totals, second.totals)
        assertEquals(140L, second.totals.total)
    }

    @Test
    fun `a re-scan picks up messages appended since last time`() {
        val first = accumulate(
            UsageSnapshot(),
            listOf(convo("c1", listOf(msg("m1", prompt = 100, ts = day1)))),
            utc,
        )
        val grown = listOf(
            convo(
                "c1",
                listOf(
                    msg("m1", prompt = 100, ts = day1),
                    msg("m2", prompt = 50, ts = day2),
                ),
            ),
        )
        val second = accumulate(first, grown, utc)
        // The old message is not re-counted; only the new one adds 50.
        assertEquals(150L, second.totals.prompt)
        assertEquals(50L, second.days.getValue("2026-03-02").prompt)
    }

    @Test
    fun `unpriced messages are skipped rather than counted as zero`() {
        val snapshot = accumulate(
            UsageSnapshot(),
            listOf(
                convo(
                    "c1",
                    listOf(msg("m1"), StoredMessage(id = "u", role = Roles.USER, text = "hi", timestamp = day1)),
                ),
            ),
            utc,
        )
        assertTrue(snapshot.days.isEmpty())
    }

    @Test
    fun `messages are bucketed by their own model tag`() {
        val snapshot = accumulate(
            UsageSnapshot(),
            listOf(
                convo("a", listOf(msg("m1", prompt = 100, model = "deepseek-chat"))),
                convo("b", listOf(msg("m2", prompt = 50, model = "deepseek-chat"))),
                convo("c", listOf(msg("m3", prompt = 30, model = "gpt"))),
            ),
            utc,
        )
        val models = byModelOf(snapshot).associate { it.model to it.tokens.total }
        assertEquals(150L, models.getValue("deepseek-chat"))
        assertEquals(30L, models.getValue("gpt"))
    }

    @Test
    fun `one chat split across models counts each message under its own`() {
        // A chat that switched persona mid-way must not be attributed to a single model.
        val snapshot = accumulate(
            UsageSnapshot(),
            listOf(
                convo(
                    "a",
                    listOf(
                        msg("m1", prompt = 100, model = "deepseek-chat", ts = day1),
                        msg("m2", prompt = 40, model = "gpt", ts = day2),
                    ),
                ),
            ),
            utc,
        )
        val models = byModelOf(snapshot).associate { it.model to it.tokens.prompt }
        assertEquals(100L, models.getValue("deepseek-chat"))
        assertEquals(40L, models.getValue("gpt"))
    }

    @Test
    fun `a blank model tag falls into the history bucket`() {
        // Transcripts written before per-message tagging carry model = "".
        val snapshot = accumulate(
            UsageSnapshot(),
            listOf(convo("old", listOf(msg("m1", prompt = 70, model = "")))),
            utc,
        )
        assertEquals(70L, byModelOf(snapshot).single().tokens.prompt)
        assertEquals(UNKNOWN_MODEL_LABEL, byModelOf(snapshot).single().model)
        // The unknown bucket is still part of the totals, so day chart and byModel agree.
        assertEquals(snapshot.totals.prompt, byModelOf(snapshot).sumOf { it.tokens.prompt })
    }

    @Test
    fun `byModel is sorted heaviest first`() {
        val snapshot = accumulate(
            UsageSnapshot(),
            listOf(
                convo("a", listOf(msg("m1", prompt = 10, model = "light"))),
                convo("b", listOf(msg("m2", prompt = 900, model = "heavy"))),
            ),
            utc,
        )
        assertEquals(listOf("heavy", "light"), byModelOf(snapshot).map { it.model })
    }

    @Test
    fun `byDay is oldest-first and honours the limit`() {
        // Build four days of usage; a limit of 2 must keep the two most recent, oldest-first.
        // Plain arithmetic rather than Instant.plusDays: the unit-test classpath uses the
        // android.jar java.time stub, which ships toEpochMilli but not the plus* helpers.
        val baseMs = Instant.parse("2026-03-01T12:00:00Z").toEpochMilli()
        val dayMs = 86_400_000L
        val chats = (0 until 4).map { offset ->
            convo("c$offset", listOf(msg("m$offset", prompt = 10, ts = baseMs + offset * dayMs)))
        }
        val snapshot = accumulate(UsageSnapshot(), chats, utc)
        val days = byDayOf(snapshot, limit = 2)
        assertEquals(listOf("2026-03-03", "2026-03-04"), days.map { it.date })
    }

    @Test
    fun `dayKey respects the zone, not just UTC`() {
        // 23:30 UTC on the 1st is already the 2nd in +08:00.
        val lateUtc = Instant.parse("2026-03-01T23:30:00Z").toEpochMilli()
        assertEquals("2026-03-01", dayKey(lateUtc, ZoneId.of("UTC")))
        assertEquals("2026-03-02", dayKey(lateUtc, ZoneId.of("Asia/Shanghai")))
    }

    @Test
    fun `deleting a chat gives its tokens back`() {
        val kept = convo("b", listOf(msg("m2", prompt = 40, model = "gpt")))
        val both = accumulate(
            UsageSnapshot(),
            listOf(convo("a", listOf(msg("m1", prompt = 100, model = "deepseek-chat"))), kept),
            utc,
        )
        assertEquals(140L, both.totals.prompt)

        // The user deleted chat "a": its day, its model and the total must all drop it,
        // or the header ends up reporting chats that no longer exist.
        val afterDelete = accumulate(both, listOf(kept), utc)
        assertEquals(40L, afterDelete.totals.prompt)
        assertEquals(40L, afterDelete.days.getValue("2026-03-01").prompt)
        assertEquals(listOf("gpt"), byModelOf(afterDelete).map { it.model })
    }

    @Test
    fun `rebuilding from an empty cache matches an incremental scan`() {
        // Deleting usage.json is a supported repair path, so a cold rebuild has to land
        // on exactly the same numbers as the incremental one.
        val firstDayOnly = listOf(convo("c1", listOf(msg("m1", prompt = 100, ts = day1))))
        val both = listOf(
            convo("c1", listOf(msg("m1", prompt = 100, ts = day1), msg("m2", prompt = 50, ts = day2))),
        )
        val incremental = accumulate(accumulate(UsageSnapshot(), firstDayOnly, utc), both, utc)
        val fromScratch = accumulate(UsageSnapshot(), both, utc)
        assertEquals(fromScratch, incremental)
        assertEquals(150L, fromScratch.totals.prompt)
    }

    @Test
    fun `an old flat cache decodes and rebuilds to the same numbers`() {
        // What every installed 0.8.10 upgrades from: one flat aggregate plus timestamp
        // watermarks and no per-chat detail. It has to decode (unknown keys ignored) and
        // then rebuild from the chats, because the chats are the only source of truth.
        val legacy = Json { ignoreUnknownKeys = true }.decodeFromString(
            UsageSnapshot.serializer(),
            """{"days":{"2026-03-01":{"prompt":100,"completion":40}},""" +
                """"models":{"deepseek-chat":{"prompt":100,"completion":40}},""" +
                """"lastScanned":{"c1":1772000000000}}""",
        )
        assertEquals(UsageSnapshot(), legacy)
        val rebuilt = accumulate(
            legacy,
            listOf(convo("c1", listOf(msg("m1", prompt = 100, completion = 40, model = "deepseek-chat")))),
            utc,
        )
        assertEquals(140L, rebuilt.totals.total)
        assertEquals(140L, byModelOf(rebuilt).single().tokens.total)
    }

    @Test
    fun `a turn that gets priced after the scan is counted on the next scan`() {
        // The newest assistant message is saved while its usage is still missing. The
        // watermark must not step over it, or that turn's tokens are lost for good.
        val midFlight = listOf(convo("c1", listOf(msg("m1", prompt = 100), msg("m2"))))
        val first = accumulate(UsageSnapshot(), midFlight, utc)
        assertEquals(100L, first.totals.prompt)

        val priced = listOf(convo("c1", listOf(msg("m1", prompt = 100), msg("m2", prompt = 25))))
        val second = accumulate(first, priced, utc)
        assertEquals(125L, second.totals.prompt)
    }

    @Test
    fun `a message appended after the clock jumps backwards is still counted`() {
        // The watermark is a message count, so a clock that jumps back (NTP correction,
        // the user changing the date) can no longer hide the new turn behind it.
        val first = accumulate(
            UsageSnapshot(),
            listOf(convo("c1", listOf(msg("m1", prompt = 100, ts = day2)))),
            utc,
        )
        val second = accumulate(
            first,
            listOf(convo("c1", listOf(msg("m1", prompt = 100, ts = day2), msg("m2", prompt = 50, ts = day1)))),
            utc,
        )
        assertEquals(150L, second.totals.prompt)
        // ...and the new turn is filed under the day its own timestamp says.
        assertEquals(50L, second.days.getValue("2026-03-01").prompt)
    }
}
