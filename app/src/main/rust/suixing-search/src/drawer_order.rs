//! Pure drawer partition/order/prune plans over numeric IDs and source indices.
use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer};
use std::collections::{HashMap, HashSet};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::{ptr, slice};

const MAGIC: u32 = 0x314f_5853;
const COMPATIBILITY: i32 = 4;
const MAX_PROTOCOL: usize = 4 * 1024 * 1024;
const MAX_RESPONSE: usize = 4 * 1024 * 1024;
const MAX_ITEMS: usize = 65_536;
const MAX_IDS: usize = 131_072;

struct Input<'a> {
    bytes: &'a [u8],
    at: usize,
}
impl Input<'_> {
    fn integer(&mut self) -> Result<u32, i32> {
        let end = self.at.checked_add(4).ok_or(STATUS_REQUEST_ERROR)?;
        let bytes = self.bytes.get(self.at..end).ok_or(STATUS_REQUEST_ERROR)?;
        self.at = end;
        Ok(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }
    fn id(&mut self, pool: u32) -> Result<u32, i32> {
        let id = self.integer()?;
        if id >= pool {
            return Err(STATUS_REQUEST_ERROR);
        }
        Ok(id)
    }
    fn ids(&mut self, pool: u32, count: usize) -> Result<Vec<u32>, i32> {
        let mut ids = Vec::new();
        ids.try_reserve(count).map_err(|_| COMPATIBILITY)?;
        for _ in 0..count {
            ids.push(self.id(pool)?);
        }
        Ok(ids)
    }
    fn timestamp(&mut self) -> Result<i64, i32> {
        let low = self.integer()? as u64;
        let high = self.integer()? as u64;
        Ok(((high << 32) | low) as i64)
    }
}
struct Conversation {
    id: u32,
    flags: u32,
    updated: i64,
}
enum Sources {
    Sections(Vec<Conversation>),
    Known(Vec<u32>),
}
struct Request {
    sources: Sources,
    pinned: Vec<u32>,
    normal: Vec<u32>,
}
impl Request {
    fn parse(bytes: &[u8]) -> Result<Self, i32> {
        if bytes.len() > MAX_PROTOCOL {
            return Err(COMPATIBILITY);
        }
        if bytes.len() < 24 {
            return Err(STATUS_REQUEST_ERROR);
        }
        let mut input = Input { bytes, at: 0 };
        if input.integer()? != MAGIC {
            return Err(STATUS_REQUEST_ERROR);
        }
        let mode = input.integer()?;
        if mode != 1 && mode != 2 {
            return Err(STATUS_REQUEST_ERROR);
        }
        let pool = input.integer()?;
        let count = input.integer()? as usize;
        let pin_count = input.integer()? as usize;
        let normal_count = input.integer()? as usize;
        if pool as usize > MAX_IDS
            || pin_count > MAX_ITEMS
            || normal_count > MAX_ITEMS
            || count > (if mode == 1 { MAX_ITEMS } else { MAX_IDS })
        {
            return Err(COMPATIBILITY);
        }
        let sources = if mode == 1 {
            let mut records = Vec::new();
            records.try_reserve(count).map_err(|_| COMPATIBILITY)?;
            for _ in 0..count {
                let id = input.id(pool)?;
                let flags = input.integer()?;
                if flags > 3 {
                    return Err(STATUS_REQUEST_ERROR);
                }
                records.push(Conversation {
                    id,
                    flags,
                    updated: input.timestamp()?,
                });
            }
            Sources::Sections(records)
        } else {
            Sources::Known(input.ids(pool, count)?)
        };
        let pinned = input.ids(pool, pin_count)?;
        let normal = input.ids(pool, normal_count)?;
        if input.at != bytes.len() {
            return Err(STATUS_REQUEST_ERROR);
        }
        Ok(Self {
            sources,
            pinned,
            normal,
        })
    }
}

fn set(ids: &[u32]) -> Result<HashSet<u32>, i32> {
    let mut set = HashSet::new();
    set.try_reserve(ids.len()).map_err(|_| COMPATIBILITY)?;
    set.extend(ids.iter().copied());
    Ok(set)
}
fn indices(capacity: usize) -> Result<Vec<usize>, i32> {
    let mut result = Vec::new();
    result.try_reserve(capacity).map_err(|_| COMPATIBILITY)?;
    Ok(result)
}
fn sections(
    source: &[Conversation],
    pinned: &[u32],
    normal: &[u32],
) -> Result<(Vec<usize>, Vec<usize>), i32> {
    // Unknown IDs participate in membership just as they do in the original Kotlin sets.
    let pin_ids = set(pinned)?;
    let normal_ids = set(normal)?;
    let mut by_id = HashMap::new();
    by_id.try_reserve(source.len()).map_err(|_| COMPATIBILITY)?;
    let mut unlisted_pin = indices(source.len())?;
    let mut normal_rows = indices(
        source
            .len()
            .checked_add(normal.len())
            .ok_or(COMPATIBILITY)?,
    )?;
    for (index, conversation) in source.iter().enumerate() {
        if conversation.flags & 2 != 0 {
            continue;
        }
        // Only the last non-archived source with a duplicate ID wins manual-list lookup.
        by_id.insert(conversation.id, index);
        if conversation.flags & 1 != 0 {
            if !pin_ids.contains(&conversation.id) {
                unlisted_pin.push(index);
            }
        } else if !normal_ids.contains(&conversation.id) {
            normal_rows.push(index);
        }
    }
    // Source index breaks equal timestamps in original input order, without sort scratch space.
    let compare = |a: &usize, b: &usize| {
        source[*b]
            .updated
            .cmp(&source[*a].updated)
            .then_with(|| a.cmp(b))
    };
    unlisted_pin.sort_unstable_by(compare);
    normal_rows.sort_unstable_by(compare);
    let mut pinned_rows = indices(
        pinned
            .len()
            .checked_add(unlisted_pin.len())
            .ok_or(COMPATIBILITY)?,
    )?;
    for id in pinned {
        if let Some(&index) = by_id.get(id) {
            if source[index].flags & 1 != 0 {
                pinned_rows.push(index);
            }
        }
    }
    pinned_rows.extend(unlisted_pin);
    for id in normal {
        if let Some(&index) = by_id.get(id) {
            if source[index].flags & 1 == 0 {
                normal_rows.push(index);
            }
        }
    }
    Ok((pinned_rows, normal_rows))
}
fn prune(known: &[u32], pinned: &[u32], normal: &[u32]) -> Result<(Vec<usize>, Vec<usize>), i32> {
    let known = set(known)?;
    let mut pinned_rows = indices(pinned.len())?;
    let mut normal_rows = indices(normal.len())?;
    for (index, id) in pinned.iter().enumerate() {
        if known.contains(id) {
            pinned_rows.push(index);
        }
    }
    for (index, id) in normal.iter().enumerate() {
        if known.contains(id) {
            normal_rows.push(index);
        }
    }
    Ok((pinned_rows, normal_rows))
}
fn encode(mode: u32, pinned: &[usize], normal: &[usize]) -> Result<Vec<u8>, i32> {
    let count = pinned
        .len()
        .checked_add(normal.len())
        .ok_or(COMPATIBILITY)?;
    let length = count
        .checked_mul(4)
        .and_then(|n| n.checked_add(16))
        .ok_or(COMPATIBILITY)?;
    if length > MAX_RESPONSE {
        return Err(COMPATIBILITY);
    }
    let mut bytes = Vec::new();
    bytes.try_reserve(length).map_err(|_| COMPATIBILITY)?;
    for value in [MAGIC, mode, pinned.len() as u32, normal.len() as u32] {
        bytes.extend_from_slice(&value.to_le_bytes());
    }
    for &index in pinned.iter().chain(normal.iter()) {
        bytes.extend_from_slice(&(index as u32).to_le_bytes());
    }
    Ok(bytes)
}

/// # Safety
/// Request points to immutable readable metadata, and output is aligned/writable until return.
/// A successful owned buffer must be released once using sx_search_free; errors return no buffer.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_drawer_order(
    request: *const u8,
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
    if request.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    if len > MAX_PROTOCOL {
        return COMPATIBILITY;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let bytes = unsafe { slice::from_raw_parts(request, len) };
        let request = Request::parse(bytes)?;
        let (mode, pinned, normal) = match request.sources {
            Sources::Sections(source) => {
                let (pinned, normal) = sections(&source, &request.pinned, &request.normal)?;
                (1, pinned, normal)
            }
            Sources::Known(known) => {
                let (pinned, normal) = prune(&known, &request.pinned, &request.normal)?;
                (2, pinned, normal)
            }
        };
        let owned = encode(mode, &pinned, &normal)?.into_boxed_slice();
        let len = owned.len();
        let data = Box::into_raw(owned) as *mut u8;
        unsafe {
            output.write(SearchBuffer { data, len });
        }
        Ok::<(), i32>(())
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}
