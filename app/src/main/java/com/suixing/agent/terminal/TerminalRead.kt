package com.suixing.agent.terminal

import androidx.annotation.Keep

@Keep
internal data class TerminalRead(val output: String, val cursor: Long, val truncated: Boolean)
