//! Exact Kotlin JsonPrimitive quoting followed by UTF-8 encoding, with bounded output chunks.
use crate::{STATUS_CANCELLED, STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR};
use std::ffi::c_void;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::slice;
pub(super) type Write = unsafe extern "C" fn(*mut c_void, *const u8, usize) -> bool;
const CHUNK: usize = 32 * 1024;

pub(super) struct Encoder {
    callback: Option<Write>,
    context: *mut c_void,
    buffer: Vec<u8>,
    bytes: u64,
    chars: u64,
}
impl Encoder {
    pub(super) fn new(callback: Option<Write>, context: *mut c_void) -> Self {
        Self {
            callback,
            context,
            buffer: if callback.is_some() {
                Vec::with_capacity(CHUNK)
            } else {
                Vec::new()
            },
            bytes: 0,
            chars: 0,
        }
    }
    fn flush(&mut self) -> Result<(), i32> {
        if let Some(write) = self.callback {
            if !self.buffer.is_empty()
                && !unsafe { write(self.context, self.buffer.as_ptr(), self.buffer.len()) }
            {
                return Err(STATUS_CANCELLED);
            }
            self.buffer.clear();
        }
        Ok(())
    }
    fn emit(&mut self, token: &[u8], chars: usize) -> Result<(), i32> {
        self.bytes = self
            .bytes
            .checked_add(token.len() as u64)
            .ok_or(STATUS_REQUEST_ERROR)?;
        self.chars = self
            .chars
            .checked_add(chars as u64)
            .ok_or(STATUS_REQUEST_ERROR)?;
        if self.callback.is_some() {
            if self.buffer.len() + token.len() > CHUNK {
                self.flush()?;
            }
            self.buffer.extend_from_slice(token);
        }
        Ok(())
    }
    pub(super) fn ascii(&mut self, byte: u8) -> Result<(), i32> {
        self.emit(&[byte], 1)
    }
    /// Fragments escape without quotes; raw literals neither escape nor quote.
    pub(super) fn string(
        &mut self,
        units: &[u16],
        quoted: bool,
        escaping: bool,
    ) -> Result<(), i32> {
        if quoted {
            self.ascii(b'"')?;
        }
        let mut index = 0;
        while index < units.len() {
            let unit = units[index];
            let escaped = if escaping {
                match unit {
                    8 => Some(b'b'),
                    9 => Some(b't'),
                    10 => Some(b'n'),
                    12 => Some(b'f'),
                    13 => Some(b'r'),
                    34 => Some(b'"'),
                    92 => Some(b'\\'),
                    _ => None,
                }
            } else {
                None
            };
            if let Some(ch) = escaped {
                self.emit(&[b'\\', ch], 2)?;
                index += 1;
            } else if escaping && unit < 32 {
                const HEX: &[u8; 16] = b"0123456789abcdef";
                self.emit(
                    &[
                        b'\\',
                        b'u',
                        b'0',
                        b'0',
                        HEX[(unit >> 4) as usize],
                        HEX[(unit & 15) as usize],
                    ],
                    6,
                )?;
                index += 1;
            } else {
                let (cp, count) = if (0xd800..=0xdbff).contains(&unit) {
                    if index + 1 < units.len() && (0xdc00..=0xdfff).contains(&units[index + 1]) {
                        (
                            0x10000
                                + (((unit as u32 - 0xd800) << 10)
                                    | (units[index + 1] as u32 - 0xdc00)),
                            2,
                        )
                    } else {
                        (b'?' as u32, 1)
                    }
                } else if (0xdc00..=0xdfff).contains(&unit) {
                    (b'?' as u32, 1)
                } else {
                    (unit as u32, 1)
                };
                let mut utf8 = [0u8; 4];
                let text = char::from_u32(cp)
                    .ok_or(STATUS_REQUEST_ERROR)?
                    .encode_utf8(&mut utf8);
                self.emit(text.as_bytes(), count)?;
                index += count;
            }
        }
        if quoted {
            self.ascii(b'"')?;
        }
        Ok(())
    }
    pub(super) fn finish(mut self) -> Result<(u64, u64), i32> {
        self.flush()?;
        Ok((self.bytes, self.chars))
    }
}

pub(super) fn encode(
    units: &[u16],
    quoted: bool,
    callback: Option<Write>,
    context: *mut c_void,
) -> Result<(u64, u64), i32> {
    let mut encoder = Encoder::new(callback, context);
    encoder.string(units, quoted, true)?;
    encoder.finish()
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_json_measure(
    text: *const u16,
    len: usize,
    bytes: *mut u64,
    chars: *mut u64,
) -> i32 {
    if bytes.is_null()
        || chars.is_null()
        || (len != 0 && text.is_null())
        || len > i32::MAX as usize
        || len > isize::MAX as usize / 2
    {
        return STATUS_REQUEST_ERROR;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        let units = if len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(text, len) }
        };
        let (b, c) = encode(units, true, None, std::ptr::null_mut())?;
        unsafe {
            bytes.write(b);
            chars.write(c);
        }
        Ok::<(), i32>(())
    })) {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(e)) => e,
        Err(_) => STATUS_PANIC,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_json_write(
    text: *const u16,
    len: usize,
    quoted: bool,
    callback: Option<Write>,
    context: *mut c_void,
) -> i32 {
    if callback.is_none()
        || (len != 0 && text.is_null())
        || len > i32::MAX as usize
        || len > isize::MAX as usize / 2
    {
        return STATUS_REQUEST_ERROR;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        let units = if len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(text, len) }
        };
        encode(units, quoted, callback, context).map(|_| ())
    })) {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(e)) => e,
        Err(_) => STATUS_PANIC,
    }
}
