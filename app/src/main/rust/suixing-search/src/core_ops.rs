//! Pure shared domain operations. Android effects and typed data ownership stay at the bridge.
use crate::{
    STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer, check_cancelled, java_whitespace,
    task,
};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::{ptr, slice};

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_core_tokens(text: *const u16, len: usize, result: *mut i32) -> i32 {
    if result.is_null()
        || (len != 0 && text.is_null())
        || len > i32::MAX as usize
        || len > isize::MAX as usize / 2
    {
        return STATUS_REQUEST_ERROR;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        let text = if len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(text, len) }
        };
        let mut cjk = 0i32;
        let mut other = 0i32;
        for &unit in text {
            if char::from_u32(unit as u32).is_some_and(java_whitespace) {
                continue;
            }
            other = other.wrapping_add(1);
            if (0x2e80..=0x9fff).contains(&unit)
                || (0xf900..=0xfaff).contains(&unit)
                || (0xff00..=0xffef).contains(&unit)
            {
                cjk = cjk.wrapping_add(1);
            }
        }
        let tenths = cjk
            .wrapping_mul(110)
            .wrapping_add(other.wrapping_sub(cjk).wrapping_mul(39));
        unsafe {
            result.write(tenths.wrapping_add(99) / 100);
        }
    })) {
        Ok(()) => STATUS_OK,
        Err(_) => STATUS_PANIC,
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn sx_core_retry(
    status: i32,
    present: bool,
    attempt: i32,
    max: i32,
    received: bool,
    cancelled: bool,
    mid_stream: bool,
) -> bool {
    !cancelled
        && (!received || mid_stream)
        && attempt <= max
        && (!present || status == 429 || (500..=599).contains(&status))
}

/// Process a newest-first batch, preserving wrapping Int budget arithmetic and USER alignment.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_core_trim_chunk(
    costs: *const i32,
    users: *const u8,
    len: usize,
    budget: i32,
    used: i32,
    kept: i32,
    output: *mut i32,
) -> i32 {
    if output.is_null()
        || len == 0
        || len > 32
        || costs.is_null()
        || users.is_null()
        || budget <= 0
        || kept < 0
    {
        return STATUS_REQUEST_ERROR;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        let costs = unsafe { slice::from_raw_parts(costs, len) };
        let users = unsafe { slice::from_raw_parts(users, len) };
        let mut total = used;
        let mut accepted = 0i32;
        let mut stop = 0i32;
        let mut oldest_user = -1i32;
        for (index, &cost) in costs.iter().enumerate() {
            let next = total.wrapping_add(cost);
            if (kept > 0 || accepted > 0) && next > budget {
                stop = 1;
                break;
            }
            accepted += 1;
            total = next;
            if users[index] != 0 {
                oldest_user = index as i32;
            }
        }
        for (index, value) in [total, accepted, stop, oldest_user].into_iter().enumerate() {
            unsafe {
                output.add(index).write(value);
            }
        }
    })) {
        Ok(()) => STATUS_OK,
        Err(_) => STATUS_PANIC,
    }
}

/// Object parsing shares SSE's guarded exact-number tree ABI; compatibility leaves Kotlin parsing.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_core_json(
    id: u64,
    text: *const u16,
    len: usize,
    output: *mut SearchBuffer,
) -> i32 {
    if output.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    unsafe {
        output.write(SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        });
    }
    if (len != 0 && text.is_null()) || len > 1024 * 1024 {
        return 4;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        let cancelled = task(id).ok_or(STATUS_REQUEST_ERROR)?;
        check_cancelled(&cancelled)?;
        let units = if len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(text, len) }
        };
        let payload = String::from_utf16(units).map_err(|_| 4)?;
        let tree = crate::sse_stream::decode_value(&payload, &cancelled, false)?.ok_or(4)?;
        check_cancelled(&cancelled)?;
        // The existing pure Kotlin decoder consumes this envelope without creating stream state.
        let mut bytes = Vec::with_capacity(20 + tree.len());
        for value in [0x3153_5853u32, 0, 0, 1, tree.len() as u32] {
            bytes.extend_from_slice(&value.to_le_bytes());
        }
        bytes.extend_from_slice(&tree);
        let owned = bytes.into_boxed_slice();
        let len = owned.len();
        unsafe {
            output.write(SearchBuffer {
                data: Box::into_raw(owned) as *mut u8,
                len,
            });
        }
        Ok::<(), i32>(())
    })) {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(e)) => e,
        Err(_) => STATUS_PANIC,
    }
}
