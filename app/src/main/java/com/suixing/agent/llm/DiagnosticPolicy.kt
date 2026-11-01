package com.suixing.agent.llm

/** Full HTTP dumps require a fresh, explicitly created marker; a marker never lasts forever. */
internal fun fullDiagnosticAllowed(markerModifiedAt: Long, now: Long): Boolean =
    markerModifiedAt > 0L && now >= markerModifiedAt && now - markerModifiedAt <= 15 * 60_000L
