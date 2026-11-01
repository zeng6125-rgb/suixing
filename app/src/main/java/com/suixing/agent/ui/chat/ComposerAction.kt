package com.suixing.agent.ui.chat

internal enum class PrimaryChatAction { DISABLED, SEND, STEER, STOP }

internal fun primaryChatAction(running: Boolean, textPresent: Boolean, attachmentPresent: Boolean): PrimaryChatAction =
    when {
        running && (textPresent || attachmentPresent) -> PrimaryChatAction.STEER
        running -> PrimaryChatAction.STOP
        textPresent || attachmentPresent -> PrimaryChatAction.SEND
        else -> PrimaryChatAction.DISABLED
    }
