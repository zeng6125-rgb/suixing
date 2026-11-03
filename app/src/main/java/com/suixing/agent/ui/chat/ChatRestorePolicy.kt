package com.suixing.agent.ui.chat

/**
 * Merges a queued line back into the *latest* draft, not into the draft that was on screen
 * when the user tapped 修改 (issue #76 F-01).
 *
 * The pictures are decoded before this runs; by then the user may have typed more, so the
 * text is appended to whatever the field holds now. Attachment fields of the latest draft
 * are kept as they are: restoring a queued line must not replace an attachment the user
 * picked while the decode was running.
 */
internal fun mergeRestoredDraft(
    latest: Draft,
    steer: PendingSteer,
    restoredImages: Int,
): Draft {
    val text = if (latest.text.isBlank()) steer.text else "${latest.text.trimEnd()} ${steer.text}"
    return latest.copy(
        text = text,
        imageNames = latest.imageNames + List(restoredImages) { QUEUED_IMAGE_LABEL },
    )
}
