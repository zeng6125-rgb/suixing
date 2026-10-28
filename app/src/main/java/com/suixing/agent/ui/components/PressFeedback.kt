package com.suixing.agent.ui.components

/** Only a matching, uncancelled release may start a click ripple. */
internal class PressFeedback<T : Any> {
    private var current: T? = null

    fun press(token: T) {
        current = token
    }

    fun release(token: T): Boolean {
        if (current !== token) return false
        clear()
        return true
    }

    fun cancel(token: T) {
        if (current === token) clear()
    }

    fun clear() {
        current = null
    }
}
