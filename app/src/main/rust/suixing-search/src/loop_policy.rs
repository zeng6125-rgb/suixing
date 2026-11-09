//! Portable terminal-action policy. Platform callbacks, localized text, statistics, events and
//! Throwable identity never cross this boundary. The five action slots have a fixed execution order.

use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::slice;

const MAGIC: i32 = 0x3150_584c;
const PLAN_INTS: usize = 8;

// Slots: prepare, message, event, state, throw.
// Prepare: none=0, cancelled-and-return-if-false=1, caller-cancelled=2.
// Message: none=0, step-limit=1, missing-summary=2, error=3, error-with-retries=4.
// Event: none=0, finished-text=1, finished-empty=2, failed-message=3.
// State: done=1, cancelled=2, failed=3. Throw: none=0, caller=1, error=2, wrapped=3.
fn actions(reason: i32, retries: i32) -> Option<[i32; 5]> {
    if reason != 8 && retries != 0 {
        return None;
    }
    Some(match reason {
        1 => [0, 0, 1, 1, 0],     // Done
        2 | 3 => [0, 0, 2, 1, 0], // GoalStopped / DirectToolRejected
        4 => [0, 1, 3, 3, 0],     // StepLimit
        5 => [0, 2, 3, 3, 0],     // MissingGoalSummary
        6 => [1, 0, 2, 2, 0],     // Cancelled: prepare=false stops the plan before event/state.
        7 => [2, 0, 0, 2, 1],     // CallerCancelled
        8 if retries > 0 => [0, 4, 0, 3, 3],
        8 => [0, 3, 0, 3, 2], // Failed: existing caller publishes the error event.
        _ => return None,
    })
}

/// Produce a fixed eight-int plan: magic, reason, retries, then the five ordered action slots.
///
/// # Safety
/// `output` must contain output_len aligned writable i32 elements, retained for this synchronous
/// call. Exactly eight elements are required; no output pointer or platform owner is retained.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_loop_policy_plan(
    reason: i32,
    retries: i32,
    output: *mut i32,
    output_len: usize,
) -> i32 {
    if output.is_null() || output_len != PLAN_INTS {
        return STATUS_REQUEST_ERROR;
    }
    // SAFETY: the output is writable and correctly sized by the ABI contract.
    unsafe { slice::from_raw_parts_mut(output, PLAN_INTS).fill(0) };
    let result = catch_unwind(AssertUnwindSafe(|| {
        let actions = actions(reason, retries).ok_or(STATUS_REQUEST_ERROR)?;
        let plan = [
            MAGIC, reason, retries, actions[0], actions[1], actions[2], actions[3], actions[4],
        ];
        // SAFETY: output stays writable until this synchronous call returns.
        unsafe { slice::from_raw_parts_mut(output, PLAN_INTS).copy_from_slice(&plan) };
        Ok(())
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}
