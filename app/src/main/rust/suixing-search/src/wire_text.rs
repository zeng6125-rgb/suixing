//! Batched wire replay descriptors and summary text with borrowed JVM UTF-16 leaves.
//! Kotlin retains protocol DTOs, attachment references and source-span ownership.
use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer};
use std::ffi::c_void;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::{ptr, slice};

const MAGIC: u32 = 0x3157_5853;
const COMPATIBILITY: i32 = 4;
const CALLBACK_FAILED: i32 = 5;
const MAX_PROTOCOL: usize = 4 * 1024 * 1024;
const MAX_RESPONSE: usize = 16 * 1024 * 1024;
const MAX_POOL: usize = 65_536;
const MAX_REPLAY: usize = 1024;
const MAX_SUMMARY: usize = 4096;
const MAX_CALLS: usize = 65_536;
const NONE: u32 = u32::MAX;

type Consume = unsafe extern "C" fn(*mut c_void, *const u16, usize) -> i32;
type Resolve = unsafe extern "C" fn(*mut c_void, u32, Consume, *mut c_void) -> i32;

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
    fn reference(&mut self, pool: u32) -> Result<u32, i32> {
        let value = self.integer()?;
        if value >= pool {
            return Err(STATUS_REQUEST_ERROR);
        }
        Ok(value)
    }
    fn nullable(&mut self, pool: u32) -> Result<u32, i32> {
        let value = self.integer()?;
        if value != NONE && value >= pool {
            return Err(STATUS_REQUEST_ERROR);
        }
        Ok(value)
    }
}

struct Replay {
    role: u32,
    flags: u32,
    text: u32,
    name: u32,
    id: u32,
    output: u32,
}
struct Summary {
    role: u32,
    text: u32,
    image: bool,
    calls: Vec<(u32, u32)>,
}
enum Request {
    Replay(Vec<Replay>),
    Summary(Vec<Summary>),
}
impl Request {
    /// Validate the complete metadata stream before invoking the String resolver.
    fn parse(bytes: &[u8], pool: u32) -> Result<Self, i32> {
        if bytes.len() > MAX_PROTOCOL || pool as usize > MAX_POOL {
            return Err(COMPATIBILITY);
        }
        if bytes.len() < 16 || pool == 0 {
            return Err(STATUS_REQUEST_ERROR);
        }
        let mut input = Input { bytes, at: 0 };
        if input.integer()? != MAGIC {
            return Err(STATUS_REQUEST_ERROR);
        }
        let mode = input.integer()?;
        if input.integer()? != pool {
            return Err(STATUS_REQUEST_ERROR);
        }
        let count = input.integer()? as usize;
        let request = match mode {
            1 => {
                if count > MAX_REPLAY {
                    return Err(COMPATIBILITY);
                }
                let mut records = Vec::new();
                records.try_reserve(count).map_err(|_| COMPATIBILITY)?;
                for _ in 0..count {
                    let role = input.integer()?;
                    let flags = input.integer()?;
                    if role > 4 || flags > 7 || flags & 4 != 0 && flags & 2 == 0 {
                        return Err(STATUS_REQUEST_ERROR);
                    }
                    records.push(Replay {
                        role,
                        flags,
                        text: input.reference(pool)?,
                        name: input.nullable(pool)?,
                        id: input.nullable(pool)?,
                        output: input.nullable(pool)?,
                    });
                }
                Self::Replay(records)
            }
            2 => {
                if count > MAX_SUMMARY {
                    return Err(COMPATIBILITY);
                }
                let mut records = Vec::new();
                records.try_reserve(count).map_err(|_| COMPATIBILITY)?;
                let mut total_calls = 0usize;
                for _ in 0..count {
                    let role = input.reference(pool)?;
                    let text = input.reference(pool)?;
                    let image = input.integer()?;
                    if image > 1 {
                        return Err(STATUS_REQUEST_ERROR);
                    }
                    let count = input.integer()? as usize;
                    total_calls = total_calls.checked_add(count).ok_or(COMPATIBILITY)?;
                    if total_calls > MAX_CALLS {
                        return Err(COMPATIBILITY);
                    }
                    let mut calls = Vec::new();
                    calls.try_reserve(count).map_err(|_| COMPATIBILITY)?;
                    for _ in 0..count {
                        calls.push((input.reference(pool)?, input.reference(pool)?));
                    }
                    records.push(Summary {
                        role,
                        text,
                        image: image != 0,
                        calls,
                    });
                }
                Self::Summary(records)
            }
            _ => return Err(STATUS_REQUEST_ERROR),
        };
        if input.at != bytes.len() {
            return Err(STATUS_REQUEST_ERROR);
        }
        Ok(request)
    }
}

struct Output {
    bytes: Vec<u8>,
}
impl Output {
    fn new(mode: u32, count: usize) -> Result<Self, i32> {
        let mut result = Self { bytes: Vec::new() };
        for value in [MAGIC, mode, count as u32, 0] {
            result.integer(value)?;
        }
        Ok(result)
    }
    fn space(&mut self, additional: usize) -> Result<(), i32> {
        let total = self
            .bytes
            .len()
            .checked_add(additional)
            .ok_or(COMPATIBILITY)?;
        if total > MAX_RESPONSE {
            return Err(COMPATIBILITY);
        }
        self.bytes
            .try_reserve(additional)
            .map_err(|_| COMPATIBILITY)?;
        Ok(())
    }
    fn integer(&mut self, value: u32) -> Result<(), i32> {
        self.space(4)?;
        self.bytes.extend_from_slice(&value.to_le_bytes());
        Ok(())
    }
    fn patch(&mut self, at: usize, value: usize) -> Result<(), i32> {
        let value = u32::try_from(value).map_err(|_| COMPATIBILITY)?;
        let end = at.checked_add(4).ok_or(STATUS_REQUEST_ERROR)?;
        let target = self.bytes.get_mut(at..end).ok_or(STATUS_REQUEST_ERROR)?;
        target.copy_from_slice(&value.to_le_bytes());
        Ok(())
    }
    fn units(&mut self, units: &[u16]) -> Result<(), i32> {
        self.space(units.len().checked_mul(2).ok_or(COMPATIBILITY)?)?;
        for &unit in units {
            self.bytes.extend_from_slice(&unit.to_le_bytes());
        }
        Ok(())
    }
    fn literal(&mut self, text: &str) -> Result<(), i32> {
        self.space(
            text.encode_utf16()
                .count()
                .checked_mul(2)
                .ok_or(COMPATIBILITY)?,
        )?;
        for unit in text.encode_utf16() {
            self.bytes.extend_from_slice(&unit.to_le_bytes());
        }
        Ok(())
    }
    fn row(&mut self, source: usize, kind: u32, id: u32, text_ref: u32) -> Result<(), i32> {
        for value in [source as u32, kind, id, 0, text_ref] {
            self.integer(value)?;
        }
        Ok(())
    }
    fn text_row(&mut self, source: usize, kind: u32) -> Result<usize, i32> {
        for value in [source as u32, kind, NONE, 1] {
            self.integer(value)?;
        }
        let length_at = self.bytes.len();
        self.integer(0)?;
        Ok(length_at)
    }
    fn end_text(&mut self, length_at: usize) -> Result<(), i32> {
        let start = length_at.checked_add(4).ok_or(STATUS_REQUEST_ERROR)?;
        let size = self
            .bytes
            .len()
            .checked_sub(start)
            .ok_or(STATUS_REQUEST_ERROR)?;
        if size % 2 != 0 {
            return Err(STATUS_REQUEST_ERROR);
        }
        self.patch(length_at, size / 2)
    }
}

struct Leaf<'a> {
    output: &'a mut Output,
}
unsafe extern "C" fn consume(context: *mut c_void, units: *const u16, len: usize) -> i32 {
    if context.is_null() || units.is_null() && len != 0 {
        return STATUS_REQUEST_ERROR;
    }
    // Check before constructing a slice: on 32-bit ABIs its byte length must fit isize too.
    if len.checked_mul(2).is_none_or(|bytes| bytes > MAX_RESPONSE) {
        return COMPATIBILITY;
    }
    // This separate catch prevents a panic unwinding through the C++ resolver's frame.
    let result = catch_unwind(AssertUnwindSafe(|| {
        let leaf = unsafe { &mut *context.cast::<Leaf<'_>>() };
        let units = if len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(units, len) }
        };
        leaf.output.units(units)
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}
fn string(
    output: &mut Output,
    resolve: Resolve,
    context: *mut c_void,
    index: u32,
) -> Result<(), i32> {
    let mut leaf = Leaf { output };
    let status = unsafe { resolve(context, index, consume, (&mut leaf as *mut Leaf<'_>).cast()) };
    if status == STATUS_OK {
        Ok(())
    } else {
        Err(if status < 0 { CALLBACK_FAILED } else { status })
    }
}
fn nullable_string(
    output: &mut Output,
    resolve: Resolve,
    context: *mut c_void,
    index: u32,
) -> Result<(), i32> {
    if index == NONE {
        Ok(())
    } else {
        string(output, resolve, context, index)
    }
}

fn replay(records: &[Replay], resolve: Resolve, context: *mut c_void) -> Result<Vec<u8>, i32> {
    let mut output = Output::new(1, records.len())?;
    let mut rows = 0usize;
    for (source, record) in records.iter().enumerate() {
        match record.role {
            0 => {}
            1 => {
                let steer = record.flags & 1 != 0;
                let omitted = record.flags & 2 != 0 && record.flags & 4 == 0;
                if steer || omitted {
                    let length_at = output.text_row(source, 1)?;
                    if steer {
                        output.literal("（用户在任务进行中说：")?;
                    }
                    string(&mut output, resolve, context, record.text)?;
                    if steer {
                        output.literal("）")?;
                    }
                    if omitted {
                        output.literal("\n（图片已省略：当前未开启「模型支持图片输入」）")?;
                    }
                    output.end_text(length_at)?;
                } else {
                    output.row(source, 1, NONE, record.text)?;
                }
                rows += 1;
            }
            2 | 3 => {
                output.row(source, record.role, NONE, record.text)?;
                rows += 1;
            }
            4 => {
                // Null falls through to the name; a non-null empty ID must remain empty.
                let id = if record.id != NONE {
                    record.id
                } else if record.name != NONE {
                    record.name
                } else {
                    0
                };
                let text = if record.output == NONE {
                    0
                } else {
                    record.output
                };
                output.row(source, 4, id, text)?;
                rows += 1;
                if record.flags & 2 != 0 {
                    let screenshot = record.flags & 4 != 0;
                    let length_at = output.text_row(source, if screenshot { 5 } else { 1 })?;
                    output.literal(if screenshot {
                        "（这是上一步 "
                    } else {
                        "（上一步 "
                    })?;
                    nullable_string(&mut output, resolve, context, record.name)?;
                    output.literal(if screenshot {
                        " 返回的截图）"
                    } else {
                        " 返回了一张截图，但当前模型未开启图片输入，你看不到图像内容。）"
                    })?;
                    output.end_text(length_at)?;
                    rows += 1;
                }
            }
            _ => return Err(STATUS_REQUEST_ERROR),
        }
    }
    output.patch(12, rows)?;
    Ok(output.bytes)
}
fn summary(records: &[Summary], resolve: Resolve, context: *mut c_void) -> Result<Vec<u8>, i32> {
    let mut output = Output::new(2, records.len())?;
    for (index, record) in records.iter().enumerate() {
        if index > 0 {
            output.literal("\n")?;
        }
        string(&mut output, resolve, context, record.role)?;
        output.literal(": ")?;
        string(&mut output, resolve, context, record.text)?;
        for &(name, arguments) in &record.calls {
            output.literal("\n  [调用工具 ")?;
            string(&mut output, resolve, context, name)?;
            output.literal(" ")?;
            string(&mut output, resolve, context, arguments)?;
            output.literal("]")?;
        }
        if record.image {
            output.literal("\n  （含截图，已省略）")?;
        }
    }
    output.end_text(12)?;
    Ok(output.bytes)
}

/// # Safety
/// Protocol bytes and the aligned writable output stay valid throughout this synchronous call.
/// Resolver invokes consume on this thread, with a borrowed aligned String leaf valid until return.
/// On success the returned buffer must be released once by sx_search_free; errors publish no buffer.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_wire_text(
    protocol: *const u8,
    len: usize,
    pool: u32,
    resolve: Option<Resolve>,
    context: *mut c_void,
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
    if protocol.is_null() || resolve.is_none() {
        return STATUS_REQUEST_ERROR;
    }
    if len > MAX_PROTOCOL || pool as usize > MAX_POOL {
        return COMPATIBILITY;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let bytes = unsafe { slice::from_raw_parts(protocol, len) };
        let request = Request::parse(bytes, pool)?;
        let result = match request {
            Request::Replay(records) => replay(&records, resolve.unwrap(), context)?,
            Request::Summary(records) => summary(&records, resolve.unwrap(), context)?,
        };
        let owned = result.into_boxed_slice();
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
