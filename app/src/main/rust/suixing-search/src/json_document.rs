//! Structural JSON traversal with borrowed JVM String leaves and one output encoder.
use crate::json_encode::{Encoder, Write};
use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR};
use std::ffi::c_void;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::slice;

const MAGIC: u32 = 0x314a_5853;
const COMPATIBILITY: i32 = 4;
const CALLBACK_FAILED: i32 = 5;
const MAX_PROTOCOL: usize = 4 * 1024 * 1024;
const MAX_NODES: usize = 131_072;
const MAX_DEPTH: usize = 128;

type Consume = unsafe extern "C" fn(*mut c_void, *const u16, usize) -> i32;
type Resolve = unsafe extern "C" fn(*mut c_void, u32, Consume, *mut c_void) -> i32;

struct Input<'a> {
    bytes: &'a [u8],
    at: usize,
}
impl<'a> Input<'a> {
    fn byte(&mut self) -> Result<u8, i32> {
        let value = *self.bytes.get(self.at).ok_or(STATUS_REQUEST_ERROR)?;
        self.at += 1;
        Ok(value)
    }
    fn integer(&mut self) -> Result<u32, i32> {
        let end = self.at.checked_add(4).ok_or(STATUS_REQUEST_ERROR)?;
        let bytes = self.bytes.get(self.at..end).ok_or(STATUS_REQUEST_ERROR)?;
        self.at = end;
        Ok(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }
    fn reference(&mut self, count: u32) -> Result<u32, i32> {
        let value = self.integer()?;
        if value >= count {
            return Err(STATUS_REQUEST_ERROR);
        }
        Ok(value)
    }
    fn count(&mut self) -> Result<usize, i32> {
        let value = self.integer()? as usize;
        if value > MAX_NODES {
            return Err(COMPATIBILITY);
        }
        Ok(value)
    }
}

fn validate_node(
    input: &mut Input<'_>,
    pool: u32,
    depth: usize,
    nodes: &mut usize,
) -> Result<(), i32> {
    *nodes += 1;
    if depth > MAX_DEPTH || *nodes > MAX_NODES {
        return Err(COMPATIBILITY);
    }
    match input.byte()? {
        1 | 2 => {
            input.reference(pool)?;
        }
        3 => {
            for _ in 0..input.count()? {
                input.reference(pool)?;
            }
        }
        4 => {
            for _ in 0..input.count()? {
                validate_node(input, pool, depth + 1, nodes)?;
            }
        }
        5 => {
            for _ in 0..input.count()? {
                input.reference(pool)?;
                validate_node(input, pool, depth + 1, nodes)?;
            }
        }
        _ => return Err(STATUS_REQUEST_ERROR),
    }
    Ok(())
}
fn validate(bytes: &[u8], pool: u32) -> Result<(), i32> {
    if !(12..=MAX_PROTOCOL).contains(&bytes.len()) || pool as usize > MAX_NODES {
        return Err(COMPATIBILITY);
    }
    let mut input = Input { bytes, at: 0 };
    if input.integer()? != MAGIC || input.integer()? != pool {
        return Err(STATUS_REQUEST_ERROR);
    }
    let expected = input.count()?;
    let mut nodes = 0;
    validate_node(&mut input, pool, 0, &mut nodes)?;
    if input.at != bytes.len() || nodes != expected {
        return Err(STATUS_REQUEST_ERROR);
    }
    Ok(())
}

struct Leaf<'a> {
    encoder: &'a mut Encoder,
    quoted: bool,
    escaping: bool,
}
unsafe extern "C" fn consume(context: *mut c_void, units: *const u16, len: usize) -> i32 {
    if context.is_null()
        || (units.is_null() && len != 0)
        || len > i32::MAX as usize
        || len > isize::MAX as usize / 2
    {
        return STATUS_REQUEST_ERROR;
    }
    // Catch here as well: a panic must not unwind through the synchronous C++ resolver frame.
    let result = catch_unwind(AssertUnwindSafe(|| {
        let leaf = unsafe { &mut *context.cast::<Leaf<'_>>() };
        let units = if len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(units, len) }
        };
        leaf.encoder.string(units, leaf.quoted, leaf.escaping)
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}
fn string(
    encoder: &mut Encoder,
    resolve: Resolve,
    context: *mut c_void,
    index: u32,
    quoted: bool,
    escaping: bool,
) -> Result<(), i32> {
    let mut leaf = Leaf {
        encoder,
        quoted,
        escaping,
    };
    // JNI borrows one jstring, invokes consume synchronously, then releases its chars/local ref.
    let status = unsafe { resolve(context, index, consume, (&mut leaf as *mut Leaf<'_>).cast()) };
    if status == STATUS_OK {
        Ok(())
    } else {
        Err(if status < 0 { CALLBACK_FAILED } else { status })
    }
}
fn write_node(
    input: &mut Input<'_>,
    encoder: &mut Encoder,
    resolve: Resolve,
    context: *mut c_void,
) -> Result<(), i32> {
    match input.byte()? {
        1 => string(encoder, resolve, context, input.integer()?, false, false)?,
        2 => string(encoder, resolve, context, input.integer()?, true, true)?,
        3 => {
            encoder.ascii(b'"')?;
            for _ in 0..input.integer()? {
                string(encoder, resolve, context, input.integer()?, false, true)?;
            }
            encoder.ascii(b'"')?;
        }
        4 => {
            encoder.ascii(b'[')?;
            for index in 0..input.integer()? {
                if index > 0 {
                    encoder.ascii(b',')?;
                }
                write_node(input, encoder, resolve, context)?;
            }
            encoder.ascii(b']')?;
        }
        5 => {
            encoder.ascii(b'{')?;
            for index in 0..input.integer()? {
                if index > 0 {
                    encoder.ascii(b',')?;
                }
                string(encoder, resolve, context, input.integer()?, true, true)?;
                encoder.ascii(b':')?;
                write_node(input, encoder, resolve, context)?;
            }
            encoder.ascii(b'}')?;
        }
        _ => return Err(STATUS_REQUEST_ERROR),
    }
    Ok(())
}

/// # Safety
/// Metadata is readable throughout the call; resolver/context synchronously retain one String leaf.
/// The aligned writable counts remain valid until return. No writes occur during measurement.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_json_document_measure(
    protocol: *const u8,
    len: usize,
    pool: u32,
    resolve: Option<Resolve>,
    context: *mut c_void,
    bytes: *mut u64,
    chars: *mut u64,
) -> i32 {
    if protocol.is_null() || bytes.is_null() || chars.is_null() || resolve.is_none() {
        return STATUS_REQUEST_ERROR;
    }
    if len > MAX_PROTOCOL {
        return COMPATIBILITY;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let protocol = unsafe { slice::from_raw_parts(protocol, len) };
        validate(protocol, pool)?;
        let mut encoder = Encoder::new(None, std::ptr::null_mut());
        write_node(
            &mut Input {
                bytes: protocol,
                at: 12,
            },
            &mut encoder,
            resolve.unwrap(),
            context,
        )?;
        let (byte_count, char_count) = encoder.finish()?;
        if byte_count > i64::MAX as u64 || char_count > i64::MAX as u64 {
            return Err(COMPATIBILITY);
        }
        unsafe {
            bytes.write(byte_count);
            chars.write(char_count);
        }
        Ok::<(), i32>(())
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

/// # Safety
/// Resolver and output callbacks/context stay valid on this calling thread. Metadata is immutable
/// and expected counts come from measurement of this same private String pool. Errors after the
/// first output chunk propagate; callers must not replay using a fallback writer.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_json_document_write(
    protocol: *const u8,
    len: usize,
    pool: u32,
    resolve: Option<Resolve>,
    context: *mut c_void,
    write: Option<Write>,
    write_context: *mut c_void,
    expected_bytes: u64,
    expected_chars: u64,
) -> i32 {
    if protocol.is_null() || resolve.is_none() || write.is_none() || len > MAX_PROTOCOL {
        return STATUS_REQUEST_ERROR;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let protocol = unsafe { slice::from_raw_parts(protocol, len) };
        validate(protocol, pool)?;
        let mut encoder = Encoder::new(write, write_context);
        write_node(
            &mut Input {
                bytes: protocol,
                at: 12,
            },
            &mut encoder,
            resolve.unwrap(),
            context,
        )?;
        let measured = encoder.finish()?;
        if measured != (expected_bytes, expected_chars) {
            return Err(STATUS_REQUEST_ERROR);
        }
        Ok::<(), i32>(())
    }));
    match result {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}
