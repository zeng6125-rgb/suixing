//! Chunked reads on the committed descriptor. No seek position or storage mutation.
use crate::{STATUS_PANIC, STATUS_REQUEST_ERROR};
use std::{
    collections::VecDeque,
    ffi::c_void,
    panic::{AssertUnwindSafe, catch_unwind},
    sync::atomic::AtomicBool,
};
type Consume = unsafe extern "C" fn(*mut c_void, *const u8, usize) -> i32;

/// Find a page using validated message metadata before materializing messages.
fn window_start(
    end: u64,
    want: usize,
    mut read_at: impl FnMut(&mut [u8], u64) -> Result<(), i32>,
) -> Result<u64, i32> {
    let cancelled = AtomicBool::new(false);
    let mut buffer = vec![0; 65536];
    let mut line = Vec::new();
    let mut budget = 256 * 1024;
    let mut parsed_from = end;
    let mut known = 0;
    loop {
        let raw_from = end.saturating_sub(budget);
        let from = if raw_from == 0 {
            0
        } else {
            let size = (end - raw_from).min(buffer.len() as u64) as usize;
            read_at(&mut buffer[..size], raw_from)?;
            buffer[..size]
                .iter()
                .position(|&byte| byte == b'\n')
                .map_or(parsed_from, |i| (raw_from + i as u64 + 1).min(parsed_from))
        };
        if parsed_from - from > i32::MAX as u64 {
            return Err(4);
        }
        let remaining = want - known;
        let mut starts = VecDeque::new();
        let mut count = 0;
        let mut mark = |bytes: &[u8], offset: u64| -> Result<(), i32> {
            if !bytes.is_empty() && crate::usage_projection::message_starts_turn(bytes, &cancelled)?
            {
                if starts.len() == remaining {
                    starts.pop_front();
                }
                starts.push_back(offset);
                count += 1;
            }
            Ok(())
        };
        let mut pos = from;
        let mut offset = from;
        while pos < parsed_from {
            let size = (parsed_from - pos).min(buffer.len() as u64) as usize;
            read_at(&mut buffer[..size], pos)?;
            let mut start = 0;
            for (i, &byte) in buffer[..size].iter().enumerate() {
                if byte == b'\n' {
                    if line.is_empty() {
                        mark(&buffer[start..i], offset)?;
                    } else {
                        line.extend_from_slice(&buffer[start..i]);
                        mark(&line, offset)?;
                        line.clear();
                    }
                    start = i + 1;
                    offset = pos + start as u64;
                }
            }
            line.extend_from_slice(&buffer[start..size]);
            if line.len() > 32 * 1024 * 1024 {
                return Err(4);
            }
            pos += size as u64;
        }
        mark(&line, offset)?;
        line.clear();
        if from == 0 && count <= remaining {
            return Ok(0);
        }
        if count >= remaining {
            return Ok(starts[0]);
        }
        known += count;
        parsed_from = from;
        budget = (budget * 4).min(end);
    }
}

#[cfg(test)]
mod window_tests {
    use super::window_start;

    #[test]
    fn skips_steers_and_keeps_prelude_when_the_entire_log_fits() {
        let mut bytes = Vec::new();
        let mut offset = 0;
        for (id, role, steer) in [
            (0, "assistant", false),
            (1, "user", false),
            (2, "user", true),
            (3, "user", false),
        ] {
            if id == 3 {
                offset = bytes.len() as u64;
            }
            let row = serde_json::json!({"id":id.to_string(),"role":role,"steer":steer,"timestamp":0,
                "text": if id == 2 { "x".repeat(290_000) } else { String::new() }});
            bytes.extend(row.to_string().bytes());
            bytes.push(b'\n');
        }
        let plan = |want| {
            window_start(bytes.len() as u64, want, |out, at| {
                out.copy_from_slice(&bytes[at as usize..at as usize + out.len()]);
                Ok(())
            })
            .unwrap()
        };
        assert_eq!(plan(1), offset);
        assert_eq!(plan(2), 0);
    }

    #[test]
    fn damaged_records_defer_to_the_existing_diagnostic_reader() {
        let bytes = b"{\"id\":\"x\",\"role\":\"assistant\",\"timestamp\":0,\"text\":7}\n";
        assert!(
            window_start(bytes.len() as u64, 1, |out, at| {
                out.copy_from_slice(&bytes[at as usize..at as usize + out.len()]);
                Ok(())
            })
            .is_err()
        );
    }
}

#[cfg(unix)]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_chat_log_window_start(fd: i32, end: i64, want: i32) -> i64 {
    use std::{
        fs::File,
        mem::ManuallyDrop,
        os::{fd::FromRawFd, unix::fs::FileExt},
    };
    if fd < 0 || end < 0 || want < 1 {
        return -1;
    }
    catch_unwind(|| {
        let file = ManuallyDrop::new(unsafe { File::from_raw_fd(fd) });
        window_start(end as u64, want as usize, |buffer, at| {
            file.read_exact_at(buffer, at)
                .map_err(|_| STATUS_REQUEST_ERROR)
        })
        .map_or(-1, |start| start as i64)
    })
    .unwrap_or(-1)
}
fn flush(
    output: &mut Vec<u8>,
    count: &mut u32,
    consume: Consume,
    context: *mut c_void,
) -> Result<(), i32> {
    if *count == 0 {
        return Ok(());
    }
    output[..4].copy_from_slice(&count.to_le_bytes());
    if unsafe { consume(context, output.as_ptr(), output.len()) } != 0 {
        return Err(5);
    }
    output.clear();
    output.extend_from_slice(&0u32.to_le_bytes());
    *count = 0;
    Ok(())
}

#[cfg(unix)]
fn read(fd: i32, start: u64, end: u64, consume: Consume, context: *mut c_void) -> Result<(), i32> {
    use std::{
        fs::File,
        mem::ManuallyDrop,
        os::{fd::FromRawFd, unix::fs::FileExt},
        sync::atomic::AtomicBool,
    };
    let file = ManuallyDrop::new(unsafe { File::from_raw_fd(fd) });
    if file.metadata().map_err(|_| STATUS_REQUEST_ERROR)?.len() < end {
        return Err(STATUS_REQUEST_ERROR);
    }
    let cancelled = AtomicBool::new(false);
    let mut output = vec![0u8; 4];
    let mut count = 0u32;
    let mut buffer = [0u8; 65536];
    let mut line = Vec::new();
    let mut offset = start;
    let mut position = start;
    let mut emit = |line: &[u8], offset: u64| -> Result<(), i32> {
        if line.is_empty() {
            return Ok(());
        }
        let text = std::str::from_utf8(line).map_err(|_| 4)?;
        let tree = if text.encode_utf16().count() <= 1_048_576 {
            crate::sse_stream::decode_value(text, &cancelled, false)
                .ok()
                .flatten()
        } else {
            None
        };
        output.extend_from_slice(&offset.to_le_bytes());
        output.extend_from_slice(&(line.len() as u32).to_le_bytes());
        output.extend_from_slice(&u32::from(tree.is_none()).to_le_bytes());
        let data = tree.as_deref().unwrap_or(text.as_bytes());
        output.extend_from_slice(&(data.len() as u32).to_le_bytes());
        output.extend_from_slice(data);
        count += 1;
        if count >= 16 || output.len() >= 512 * 1024 {
            flush(&mut output, &mut count, consume, context)?;
        }
        Ok(())
    };
    while position < end {
        let size = (end - position).min(buffer.len() as u64) as usize;
        file.read_exact_at(&mut buffer[..size], position)
            .map_err(|_| STATUS_REQUEST_ERROR)?;
        let mut from = 0;
        for (i, &byte) in buffer[..size].iter().enumerate() {
            if byte == b'\n' {
                if line.is_empty() {
                    emit(&buffer[from..i], offset)?;
                } else {
                    line.extend_from_slice(&buffer[from..i]);
                    emit(&line, offset)?;
                    line.clear();
                }
                from = i + 1;
                offset = position + from as u64;
            }
        }
        line.extend_from_slice(&buffer[from..size]);
        position += size as u64;
    }
    emit(&line, offset)?;
    drop(emit);
    flush(&mut output, &mut count, consume, context)
}
#[cfg(not(unix))]
fn read(_: i32, _: u64, _: u64, _: Consume, _: *mut c_void) -> Result<(), i32> {
    Err(4)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_chat_log_cut(
    flags: *const u8,
    count: usize,
    want: usize,
    at_start: bool,
) -> i32 {
    let flags = if count == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(flags, count) }
    };
    let total = flags.iter().filter(|&&flag| flag != 0).count();
    if at_start && total <= want {
        return 0;
    }
    if total < want {
        return -1;
    }
    let mut remaining = want;
    for (index, &flag) in flags.iter().enumerate().rev() {
        if flag != 0 {
            remaining -= 1;
            if remaining == 0 {
                return index as i32;
            }
        }
    }
    -1
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_chat_log_read(
    fd: i32,
    start: u64,
    end: u64,
    consume: Consume,
    context: *mut c_void,
) -> i32 {
    if fd < 0 || start > end {
        return STATUS_REQUEST_ERROR;
    }
    match catch_unwind(AssertUnwindSafe(|| read(fd, start, end, consume, context))) {
        Ok(Ok(())) => 0,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

#[cfg(unix)]
fn locate(fd: i32, from: u64, end: u64, needle: &[u8]) -> Result<i64, i32> {
    use std::{
        fs::File,
        mem::ManuallyDrop,
        os::{fd::FromRawFd, unix::fs::FileExt},
    };
    let file = ManuallyDrop::new(unsafe { File::from_raw_fd(fd) });
    if needle.is_empty() {
        let mut buffer = [0u8; 65536];
        let mut at = from;
        while at < end {
            let size = (end - at).min(buffer.len() as u64) as usize;
            file.read_exact_at(&mut buffer[..size], at)
                .map_err(|_| STATUS_REQUEST_ERROR)?;
            if let Some(i) = buffer[..size].iter().position(|&byte| byte == b'\n') {
                return Ok((at + i as u64 + 1) as i64);
            }
            at += size as u64;
        }
        return Ok(-1);
    }
    let mut at = end;
    while at > from {
        let start = at.saturating_sub(65536).max(from);
        let read_start = start.saturating_sub(1);
        let read_end = end.min(at + needle.len() as u64 - 1);
        let mut buffer = vec![0; (read_end - read_start) as usize];
        file.read_exact_at(&mut buffer, read_start)
            .map_err(|_| STATUS_REQUEST_ERROR)?;
        let last = ((at - read_start - 1) as usize).min(buffer.len().saturating_sub(needle.len()));
        for i in ((start - read_start) as usize..=last).rev() {
            if buffer.get(i..i + needle.len()) == Some(needle)
                && (read_start + i as u64 == 0 || i > 0 && buffer[i - 1] == b'\n')
            {
                return Ok((read_start + i as u64) as i64);
            }
        }
        at = start;
    }
    Ok(-1)
}
#[cfg(not(unix))]
fn locate(_: i32, _: u64, _: u64, _: &[u8]) -> Result<i64, i32> {
    Err(4)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_chat_log_locate(
    fd: i32,
    start: u64,
    end: u64,
    needle: *const u8,
    len: usize,
    output: *mut i64,
) -> i32 {
    let needle = if len == 0 {
        &[]
    } else {
        unsafe { std::slice::from_raw_parts(needle, len) }
    };
    match catch_unwind(|| locate(fd, start, end, needle)) {
        Ok(Ok(position)) => {
            unsafe {
                output.write(position);
            }
            0
        }
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}
