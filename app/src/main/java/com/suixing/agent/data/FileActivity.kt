package com.suixing.agent.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class FileActivityKind { CREATED, MODIFIED, DOWNLOADED, DELIVERED }

/** Verified file metadata for conversation cards; never retains file contents or a diff. */
@Immutable
@Serializable
data class FileActivity(
    val path: String,
    val kind: FileActivityKind,
    /** Lines in the replaced block after stripping identical leading and trailing lines. */
    val addedLines: Int? = null,
    val removedLines: Int? = null,
    val sizeBytes: Long? = null,
    /** Optional agent-written caption, not a source of filesystem or change statistics. */
    val description: String = "",
)
