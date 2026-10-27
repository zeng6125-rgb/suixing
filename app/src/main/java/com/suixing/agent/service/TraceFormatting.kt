package com.suixing.agent.service

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TRACE_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)

/** Reusable and thread-safe; the event captures its time and zone before queueing the write. */
internal fun traceStamp(at: Long, zone: ZoneId): String =
    TRACE_TIME_FORMAT.format(Instant.ofEpochMilli(at).atZone(zone))
