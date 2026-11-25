//! Chunked reads on the committed descriptor. No seek position or storage mutation.
use crate::{STATUS_CANCELLED, STATUS_PANIC, STATUS_REQUEST_ERROR};
use memchr::{memchr, memchr_iter};
use std::{
    collections::VecDeque,
    ffi::c_void,
    panic::{AssertUnwindSafe, catch_unwind},
    sync::atomic::AtomicBool,
};
type Consume = unsafe extern "C" fn(*mut c_void, *const u8, usize) -> i32;
type Checkpoint = unsafe extern "C" fn(*mut c_void) -> i32;

#[cfg(unix)]
fn check(callback: Option<Checkpoint>, context: *mut c_void) -> Result<(), i32> {
    if callback.is_some_and(|checkpoint| unsafe { checkpoint(context) } != 0) {
        return Err(STATUS_CANCELLED);
    }
    Ok(())
}

/// Find a page using validated message metadata before materializing messages.
fn window_start(
    end: u64,
    want: usize,
    mut read_at: impl FnMut(&mut [u8], u64) -> Result<(), i32>,
    mut checkpoint: impl FnMut() -> Result<(), i32>,
) -> Result<u64, i32> {
    const INITIAL_WINDOW_BYTES: u64 = 256 * 1024;
    let cancelled = AtomicBool::new(false);
    let mut buffer = vec![0; 65536];
    let mut line = Vec::new();
    let mut budget = INITIAL_WINDOW_BYTES;
    let mut parsed_from = end;
    let mut known = 0;
    loop {
        checkpoint()?;
        let raw_from = end.saturating_sub(budget);
        let from = if raw_from == 0 {
            0
        } else {
            let size = (end - raw_from).min(buffer.len() as u64) as usize;
            read_at(&mut buffer[..size], raw_from)?;
            memchr(b'\n', &buffer[..size])
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
            checkpoint()?;
            let size = (parsed_from - pos).min(buffer.len() as u64) as usize;
            read_at(&mut buffer[..size], pos)?;
            let mut start = 0;
            for i in memchr_iter(b'\n', &buffer[..size]) {
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
            line.extend_from_slice(&buffer[start..size]);
            if line.len() > 32 * 1024 * 1024 {
                return Err(4);
            }
            pos += size as u64;
        }
        checkpoint()?;
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
        // Try 512 KiB before 1 MiB when the original probe wouldn't reach the head.
        let growth = if end > INITIAL_WINDOW_BYTES * 4 && budget < INITIAL_WINDOW_BYTES * 4 {
            2
        } else {
            4
        };
        budget = (budget * growth).min(end);
    }
}

#[cfg(test)]
mod window_tests {
    use super::{read_content, window_start};
    use crate::STATUS_CANCELLED;
    use std::cell::Cell;
    use std::ffi::c_void;

    fn stale_window_fixture() -> Vec<u8> {
        let mut log = Vec::new();
        let text = "x".repeat(180_000);
        for turn in 0..36 {
            let user = format!(
                "{{\"id\":\"u{turn}\",\"role\":\"user\",\"timestamp\":{turn},\"text\":\"query\"}}\n"
            );
            let reply = format!(
                "{{\"id\":\"a{turn}\",\"role\":\"assistant\",\"timestamp\":{turn},\"text\":\"{text}\"}}\n"
            );
            log.extend_from_slice(user.as_bytes());
            log.extend_from_slice(reply.as_bytes());
        }
        log
    }

    #[test]
    fn a_twelve_turn_page_can_fit_before_the_megabyte_probe() {
        let reply = "资料🙂 ".repeat(2048);
        let mut log = Vec::new();
        let mut expected = 0;
        for turn in 0..64 {
            if turn == 52 {
                expected = log.len() as u64;
            }
            log.extend_from_slice(format!(
                "{{\"id\":\"u{turn}\",\"role\":\"user\",\"timestamp\":{turn}}}\n\
                 {{\"id\":\"a{turn}\",\"role\":\"assistant\",\"timestamp\":{turn},\"text\":\"{reply}\"}}\n"
            ).as_bytes());
        }
        let mut bytes_read = 0;
        let start = window_start(
            log.len() as u64,
            12,
            |out, offset| {
                let from = offset as usize;
                out.copy_from_slice(&log[from..from + out.len()]);
                bytes_read += out.len();
                Ok(())
            },
            || Ok(()),
        )
        .unwrap();
        assert_eq!(expected, start);
        assert!(bytes_read <= 640 * 1024, "read {bytes_read} bytes");
    }

    #[test]
    fn stale_open_scan_workload() {
        let log = stale_window_fixture();
        let mut calls = 0;
        let mut bytes_read = 0;
        let offset = window_start(log.len() as u64, 12, |out, at| {
            let start = at as usize;
            calls += 1;
            bytes_read += out.len();
            out.copy_from_slice(&log[start..start + out.len()]);
            Ok(())
        }, || Ok(())).unwrap();
        assert!(offset > 0);
        let cancelled_calls = Cell::new(0);
        let mut cancelled_bytes = 0;
        let stopped = window_start(
            log.len() as u64, 12,
            |out, at| {
                let start = at as usize;
                cancelled_calls.set(cancelled_calls.get() + 1);
                cancelled_bytes += out.len();
                out.copy_from_slice(&log[start..start + out.len()]);
                Ok(())
            },
            || if cancelled_calls.get() >= 2 { Err(STATUS_CANCELLED) } else { Ok(()) },
        );
        assert_eq!(Err(STATUS_CANCELLED), stopped);
        assert_eq!(2, cancelled_calls.get());
        assert!(cancelled_bytes < bytes_read);
        println!("reply window baseline calls={calls} bytes_read={bytes_read}, cancelled bytes_read={cancelled_bytes}");
    }

    #[test]
    fn cancelling_materialization_stops_reading_before_the_next_chunk() {
        unsafe extern "C" fn consume(context: *mut c_void, _: *const u8, _: usize) -> i32 {
            let batches = unsafe { &mut *context.cast::<usize>() };
            *batches += 1;
            0
        }
        let log = stale_window_fixture();
        let bytes_read = Cell::new(0);
        let mut batches = 0_usize;
        let result = read_content(
            0, log.len() as u64, consume, (&raw mut batches).cast(),
            |out, at| {
                let start = at as usize;
                out.copy_from_slice(&log[start..start + out.len()]);
                bytes_read.set(bytes_read.get() + out.len());
                Ok(())
            },
            || if bytes_read.get() >= 65_536 { Err(STATUS_CANCELLED) } else { Ok(()) },
        );
        assert_eq!(Err(STATUS_CANCELLED), result);
        assert_eq!(65_536, bytes_read.get());
        assert_eq!(0, batches);
    }

    #[test]
    #[ignore = "manual cold opening benchmark"]
    fn cold_open_workloads() {
        use std::fmt::Write;
        use std::hint::black_box;
        use std::time::Instant;

        for (name, body) in [
            ("short_ascii", "summary ".repeat(32)),
            ("long_ascii", "tool output ".repeat(16_384)),
            ("long_unicode", "检索记录🙂 ".repeat(8192)),
            ("long_escapes", r"line\n tab\t slash\\ ".repeat(8192)),
        ] {
            let mut log = String::new();
            let mut expected = 0;
            for turn in 0..36 {
                if turn == 24 {
                    expected = log.len();
                }
                writeln!(
                    log,
                    "{{\"id\":\"u{turn}\",\"role\":\"user\",\"timestamp\":{turn},\"text\":\"query\"}}"
                )
                .unwrap();
                writeln!(
                    log,
                    "{{\"id\":\"a{turn}\",\"role\":\"assistant\",\"timestamp\":{turn},\"text\":\"{body}\"}}"
                )
                .unwrap();
            }
            let mut times = Vec::new();
            let mut read = 0;
            for index in 0..32 {
                read = 0;
                let bytes = black_box(log.as_bytes());
                let started = Instant::now();
                let offset = window_start(bytes.len() as u64, 12, |out, from| {
                    let start = from as usize;
                    read += out.len();
                    out.copy_from_slice(&bytes[start..start + out.len()]);
                    Ok(())
                }, || Ok(()))
                .unwrap();
                let elapsed = started.elapsed().as_nanos();
                assert_eq!(offset, expected as u64);
                if index >= 8 {
                    times.push(elapsed);
                }
            }
            times.sort_unstable();
            println!("{name} {} ns, {read} bytes read", times[times.len() / 2]);
        }
    }

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
            }, || Ok(()))
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
            }, || Ok(()))
            .is_err()
        );
    }
}

#[cfg(unix)]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_chat_log_window_start(
    fd: i32, end: i64, want: i32, checkpoint: Option<Checkpoint>, context: *mut c_void,
) -> i64 {
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
        }, || check(checkpoint, context))
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
fn read(
    fd: i32, start: u64, end: u64, consume: Consume, context: *mut c_void,
    checkpoint: Option<Checkpoint>, check_context: *mut c_void,
) -> Result<(), i32> {
    use std::{
        fs::File,
        mem::ManuallyDrop,
        os::{fd::FromRawFd, unix::fs::FileExt},
    };
    let file = ManuallyDrop::new(unsafe { File::from_raw_fd(fd) });
    if file.metadata().map_err(|_| STATUS_REQUEST_ERROR)?.len() < end {
        return Err(STATUS_REQUEST_ERROR);
    }
    read_content(
        start, end, consume, context,
        |buffer, position| file.read_exact_at(buffer, position).map_err(|_| STATUS_REQUEST_ERROR),
        || check(checkpoint, check_context),
    )
}

#[cfg(any(test, unix))]
fn read_content(
    start: u64, end: u64, consume: Consume, context: *mut c_void,
    mut read_at: impl FnMut(&mut [u8], u64) -> Result<(), i32>,
    mut checkpoint: impl FnMut() -> Result<(), i32>,
) -> Result<(), i32> {
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
        checkpoint()?;
        let size = (end - position).min(buffer.len() as u64) as usize;
        read_at(&mut buffer[..size], position)?;
        let mut from = 0;
        for i in memchr_iter(b'\n', &buffer[..size]) {
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
        line.extend_from_slice(&buffer[from..size]);
        position += size as u64;
    }
    checkpoint()?;
    emit(&line, offset)?;
    drop(emit);
    flush(&mut output, &mut count, consume, context)
}
#[cfg(not(unix))]
fn read(
    _: i32, _: u64, _: u64, _: Consume, _: *mut c_void, _: Option<Checkpoint>, _: *mut c_void,
) -> Result<(), i32> {
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
    checkpoint: Option<Checkpoint>,
    check_context: *mut c_void,
) -> i32 {
    if fd < 0 || start > end {
        return STATUS_REQUEST_ERROR;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        read(fd, start, end, consume, context, checkpoint, check_context)
    })) {
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
    locate_lines(from, end, [needle], |bytes, offset| {
        file.read_exact_at(bytes, offset)
            .map_err(|_| STATUS_REQUEST_ERROR)
    })
    .map(|found| found[0])
}

#[cfg(any(unix, test))]
fn locate_lines<const N: usize>(
    from: u64,
    end: u64,
    needles: [&[u8]; N],
    mut read_at: impl FnMut(&mut [u8], u64) -> Result<(), i32>,
) -> Result<[i64; N], i32> {
    let overlap = needles
        .iter()
        .map(|needle| needle.len())
        .max()
        .unwrap_or(0)
        .saturating_sub(1);
    let mut found = [-1; N];
    let mut pending = N;
    let mut buffer = Vec::new();
    let mut at = end;
    while at > from {
        let start = at.saturating_sub(65536).max(from);
        let read_start = start.saturating_sub(1);
        let read_end = end.min(at + overlap as u64);
        buffer.resize((read_end - read_start) as usize, 0);
        read_at(&mut buffer, read_start)?;
        for i in ((start - read_start) as usize..(at - read_start) as usize).rev() {
            if buffer[i] != b'{' || !(read_start + i as u64 == 0 || i > 0 && buffer[i - 1] == b'\n')
            {
                continue;
            }
            for (slot, needle) in needles.iter().enumerate() {
                if found[slot] < 0 && buffer.get(i..i + needle.len()) == Some(*needle) {
                    found[slot] = (read_start + i as u64) as i64;
                    pending -= 1;
                    if pending == 0 {
                        return Ok(found);
                    }
                }
            }
        }
        at = start;
    }
    Ok(found)
}

#[cfg(unix)]
fn locate_pair(fd: i32, end: u64, first: &[u8], last: &[u8]) -> Result<[i64; 2], i32> {
    use std::{
        fs::File,
        mem::ManuallyDrop,
        os::{fd::FromRawFd, unix::fs::FileExt},
    };
    let file = ManuallyDrop::new(unsafe { File::from_raw_fd(fd) });
    locate_lines(0, end, [first, last], |bytes, offset| {
        file.read_exact_at(bytes, offset)
            .map_err(|_| STATUS_REQUEST_ERROR)
    })
}

#[cfg(not(unix))]
fn locate_pair(_: i32, _: u64, _: &[u8], _: &[u8]) -> Result<[i64; 2], i32> {
    Err(4)
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

/// # Safety
/// The needles must be readable for the supplied lengths, the file descriptor must remain open,
/// and output must be writable for two offsets.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_chat_log_locate_pair(
    fd: i32,
    end: u64,
    first: *const u8,
    first_len: usize,
    last: *const u8,
    last_len: usize,
    output: *mut i64,
) -> i32 {
    if first_len == 0 || last_len == 0 {
        return STATUS_REQUEST_ERROR;
    }
    let first = unsafe { std::slice::from_raw_parts(first, first_len) };
    let last = unsafe { std::slice::from_raw_parts(last, last_len) };
    match catch_unwind(|| locate_pair(fd, end, first, last)) {
        Ok(Ok(found)) => {
            unsafe { output.cast::<[i64; 2]>().write(found) };
            0
        }
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

#[cfg(test)]
mod locate_tests {
    use super::locate_lines;

    fn place(log: &mut [u8], at: usize, needle: &[u8]) {
        if at > 0 {
            log[at - 1] = b'\n';
        }
        log[at..at + needle.len()].copy_from_slice(needle);
    }

    fn find<const N: usize>(log: &[u8], needles: [&[u8]; N]) -> ([i64; N], usize) {
        let mut count = 0;
        let found = locate_lines(0, log.len() as u64, needles, |out, offset| {
            count += out.len();
            out.copy_from_slice(&log[offset as usize..offset as usize + out.len()]);
            Ok(())
        })
        .unwrap();
        (found, count)
    }

    #[test]
    fn finds_the_latest_line_start_even_if_a_needle_crosses_a_block() {
        let mut log = vec![b'x'; 3 * 65536];
        let first = &b"{\"id\":\"first\""[..];
        let last = &b"{\"id\":\"last-long-id\""[..];
        let split = 65536 - 5;
        place(&mut log, 0, first);
        place(&mut log, split, first);
        place(&mut log, 2 * 65536, last);
        let body = 2 * 65536 + 100;
        log[body..body + first.len()].copy_from_slice(first);
        assert_eq!(find(&log, [first, last]).0, [split as i64, 2 * 65536]);
        assert_eq!(find(&log, [first, first]).0, [split as i64; 2]);
        assert_eq!(
            find(&log, [first, b"{\"id\":\"missing\""]).0,
            [split as i64, -1]
        );
    }

    #[test]
    fn cold_checkpoint_anchors_share_the_backward_io() {
        let mut log = vec![b'x'; 32 * 1024 * 1024];
        let first = &b"{\"id\":\"first\""[..];
        let last = &b"{\"id\":\"last\""[..];
        place(&mut log, 0, first);
        place(&mut log, 6 * 1024 * 1024 + 19, last);
        let (old_first, first_bytes) = find(&log, [first]);
        let (old_last, last_bytes) = find(&log, [last]);
        let (pair, pair_bytes) = find(&log, [first, last]);
        assert_eq!(pair, [old_first[0], old_last[0]]);
        let separate = first_bytes + last_bytes;
        assert!(pair_bytes < separate);
        println!("32 MiB cold-anchor fixture: separate={separate} bytes paired={pair_bytes} bytes");
    }
}
