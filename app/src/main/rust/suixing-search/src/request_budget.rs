//! Pure request-image budget selection. Only carrier indices and Base64 character lengths cross
//! this ABI; image contents, transcript objects and offload notes remain owned by Kotlin.

use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::slice;

const MAGIC: u32 = 0x3142_5853;
const STATUS_COMPATIBILITY: i32 = 4;
const MAX_CARRIERS: usize = 65_536;
const MAX_IMAGES: usize = 262_144;

/// Select the oldest complete screenshot carriers until the retained total fits the budget.
/// Offsets delimit each carrier's lengths; empty images still belong to their original carrier.
/// Checked 64-bit totals intentionally fix the old Int sum overflow that could skip offloading.
///
/// # Safety
/// `output` must be writable and aligned for SearchBuffer. `offsets` contains carriers + 1 aligned
/// i32 values. Nonempty indices/lengths contain their declared aligned immutable readable values,
/// and all pointers remain valid until this synchronous call exits.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_request_budget_select(
    indices: *const i32,
    offsets: *const i32,
    lengths: *const i32,
    carriers: usize,
    images: usize,
    budget: i64,
    output: *mut SearchBuffer,
) -> i32 {
    if output.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    // SAFETY: caller provides writable output storage by the ABI contract.
    unsafe {
        output.write(SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        })
    };
    if budget <= 0 || carriers > MAX_CARRIERS || images > MAX_IMAGES {
        return STATUS_COMPATIBILITY;
    }
    if offsets.is_null() || (carriers > 0 && indices.is_null()) || (images > 0 && lengths.is_null())
    {
        return STATUS_REQUEST_ERROR;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: each nonempty slice was checked; declared counts are bounded above.
        let indices = if carriers == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(indices, carriers) }
        };
        let offsets = unsafe { slice::from_raw_parts(offsets, carriers + 1) };
        let lengths = if images == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(lengths, images) }
        };
        let bytes = select(indices, offsets, lengths, budget)?;
        let owned = bytes.into_boxed_slice();
        let len = owned.len();
        let data = Box::into_raw(owned) as *mut u8;
        // SAFETY: caller still owns this output for the duration of the call.
        unsafe { output.write(SearchBuffer { data, len }) };
        Ok(())
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

fn select(indices: &[i32], offsets: &[i32], lengths: &[i32], budget: i64) -> Result<Vec<u8>, i32> {
    if offsets.first() != Some(&0) || offsets.last().copied() != Some(lengths.len() as i32) {
        return Err(STATUS_REQUEST_ERROR);
    }
    let mut total = 0i64;
    let mut costs = Vec::with_capacity(indices.len());
    let mut previous = -1;
    for (carrier, &index) in indices.iter().enumerate() {
        let from = offsets[carrier];
        let end = offsets[carrier + 1];
        if index <= previous || from < 0 || end <= from || end as usize > lengths.len() {
            return Err(STATUS_REQUEST_ERROR);
        }
        previous = index;
        let mut cost = 0i64;
        for &length in &lengths[from as usize..end as usize] {
            if length < 0 {
                return Err(STATUS_REQUEST_ERROR);
            }
            cost = cost
                .checked_add(i64::from(length))
                .ok_or(STATUS_REQUEST_ERROR)?;
        }
        total = total.checked_add(cost).ok_or(STATUS_REQUEST_ERROR)?;
        costs.push(cost);
    }
    let mut remaining = total;
    let mut selected = 0usize;
    for cost in costs {
        if remaining <= budget {
            break;
        }
        remaining = remaining.checked_sub(cost).ok_or(STATUS_REQUEST_ERROR)?;
        selected += 1;
    }
    let mut output = Vec::with_capacity(32 + selected * 4);
    for value in [
        MAGIC,
        indices.len() as u32,
        lengths.len() as u32,
        selected as u32,
    ] {
        output.extend_from_slice(&value.to_le_bytes());
    }
    output.extend_from_slice(&total.to_le_bytes());
    output.extend_from_slice(&remaining.to_le_bytes());
    for &index in &indices[..selected] {
        output.extend_from_slice(&index.to_le_bytes());
    }
    Ok(output)
}
