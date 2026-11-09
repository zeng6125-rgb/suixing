package com.suixing.agent.agent

import com.suixing.agent.core.NativeCore
import java.io.IOException

internal object NativeLoopPolicyJni {
    external fun nativePlan(reason: Int, retries: Int): IntArray
}

/** Slots are executed in order; preparing a cancellation can stop before all subsequent effects. */
internal data class LoopTerminalPlan(
    val prepare: Int,
    val message: Int,
    val event: Int,
    val state: Int,
    val throwAction: Int,
)

/** Rust selects the terminal actions; Kotlin retains the original callbacks and exception objects. */
internal object NativeLoopPolicy {
    const val DONE = 1
    const val GOAL_STOPPED = 2
    const val DIRECT_TOOL_REJECTED = 3
    const val STEP_LIMIT = 4
    const val MISSING_GOAL_SUMMARY = 5
    const val CANCELLED = 6
    const val CALLER_CANCELLED = 7
    const val FAILED = 8

    const val PREPARE_NONE = 0
    const val PREPARE_CANCELLED = 1
    const val PREPARE_CALLER = 2
    const val MESSAGE_NONE = 0
    const val MESSAGE_STEP_LIMIT = 1
    const val MESSAGE_MISSING_SUMMARY = 2
    const val MESSAGE_ERROR = 3
    const val MESSAGE_ERROR_WITH_RETRIES = 4
    const val EVENT_NONE = 0
    const val EVENT_FINISHED_TEXT = 1
    const val EVENT_FINISHED_EMPTY = 2
    const val EVENT_FAILED = 3
    const val STATE_DONE = 1
    const val STATE_CANCELLED = 2
    const val STATE_FAILED = 3
    const val THROW_NONE = 0
    const val THROW_CALLER = 1
    const val THROW_ERROR = 2
    const val THROW_WRAPPED_ERROR = 3
    private const val MAGIC = 0x3150584c

    fun plan(reason: Int, retries: Int): LoopTerminalPlan {
        if (!NativeCore.available) return kotlinPlan(reason, retries)
        val raw = try {
            NativeLoopPolicyJni.nativePlan(reason, retries)
        } catch (_: UnsatisfiedLinkError) {
            return kotlinPlan(reason, retries)
        }
        fun valid(condition: Boolean) {
            if (!condition) throw IOException("原生运行终态动作协议无效")
        }
        valid(raw.size == 8 && raw[0] == MAGIC && raw[1] == reason && raw[2] == retries)
        val plan = LoopTerminalPlan(raw[3], raw[4], raw[5], raw[6], raw[7])
        // Validate before any callbacks/events. Fixed slots enforce ordering and these checks prevent
        // a malformed plan from casting a different exit or publishing a false terminal state.
        valid(plan.prepare in PREPARE_NONE..PREPARE_CALLER && plan.message in MESSAGE_NONE..MESSAGE_ERROR_WITH_RETRIES &&
            plan.event in EVENT_NONE..EVENT_FAILED && plan.state in STATE_DONE..STATE_FAILED &&
            plan.throwAction in THROW_NONE..THROW_WRAPPED_ERROR)
        val success = plan.prepare == PREPARE_NONE && plan.message == MESSAGE_NONE &&
            plan.state == STATE_DONE && plan.throwAction == THROW_NONE
        val failureEvent = plan.prepare == PREPARE_NONE && plan.event == EVENT_FAILED &&
            plan.state == STATE_FAILED && plan.throwAction == THROW_NONE
        valid(when (reason) {
            DONE -> success && plan.event == EVENT_FINISHED_TEXT && retries == 0
            GOAL_STOPPED, DIRECT_TOOL_REJECTED -> success && plan.event == EVENT_FINISHED_EMPTY && retries == 0
            STEP_LIMIT -> failureEvent && plan.message == MESSAGE_STEP_LIMIT && retries == 0
            MISSING_GOAL_SUMMARY -> failureEvent && plan.message == MESSAGE_MISSING_SUMMARY && retries == 0
            CANCELLED -> plan.prepare == PREPARE_CANCELLED && plan.message == MESSAGE_NONE &&
                plan.event == EVENT_FINISHED_EMPTY && plan.state == STATE_CANCELLED && plan.throwAction == THROW_NONE && retries == 0
            CALLER_CANCELLED -> plan.prepare == PREPARE_CALLER && plan.message == MESSAGE_NONE &&
                plan.event == EVENT_NONE && plan.state == STATE_CANCELLED && plan.throwAction == THROW_CALLER && retries == 0
            FAILED -> plan.prepare == PREPARE_NONE && plan.event == EVENT_NONE && plan.state == STATE_FAILED &&
                (plan.message == MESSAGE_ERROR && plan.throwAction == THROW_ERROR && retries <= 0 ||
                    plan.message == MESSAGE_ERROR_WITH_RETRIES && plan.throwAction == THROW_WRAPPED_ERROR && retries > 0)
            else -> false
        })
        return plan
    }

    /** Library absence keeps the same pure rules, rather than suppressing terminal publication. */
    private fun kotlinPlan(reason: Int, retries: Int): LoopTerminalPlan {
        require(reason == FAILED || retries == 0) { "运行终态的重试参数无效" }
        return when (reason) {
            DONE -> LoopTerminalPlan(PREPARE_NONE, MESSAGE_NONE, EVENT_FINISHED_TEXT, STATE_DONE, THROW_NONE)
            GOAL_STOPPED, DIRECT_TOOL_REJECTED -> LoopTerminalPlan(PREPARE_NONE, MESSAGE_NONE, EVENT_FINISHED_EMPTY, STATE_DONE, THROW_NONE)
            STEP_LIMIT -> LoopTerminalPlan(PREPARE_NONE, MESSAGE_STEP_LIMIT, EVENT_FAILED, STATE_FAILED, THROW_NONE)
            MISSING_GOAL_SUMMARY -> LoopTerminalPlan(PREPARE_NONE, MESSAGE_MISSING_SUMMARY, EVENT_FAILED, STATE_FAILED, THROW_NONE)
            CANCELLED -> LoopTerminalPlan(PREPARE_CANCELLED, MESSAGE_NONE, EVENT_FINISHED_EMPTY, STATE_CANCELLED, THROW_NONE)
            CALLER_CANCELLED -> LoopTerminalPlan(PREPARE_CALLER, MESSAGE_NONE, EVENT_NONE, STATE_CANCELLED, THROW_CALLER)
            FAILED -> if (retries > 0) LoopTerminalPlan(PREPARE_NONE, MESSAGE_ERROR_WITH_RETRIES, EVENT_NONE, STATE_FAILED, THROW_WRAPPED_ERROR)
                else LoopTerminalPlan(PREPARE_NONE, MESSAGE_ERROR, EVENT_NONE, STATE_FAILED, THROW_ERROR)
            else -> throw IllegalArgumentException("运行终态原因无效")
        }
    }
}
