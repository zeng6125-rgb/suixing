//! Windowed text scan: hash all original UTF-16 units, allocate only requested logical lines.
use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer, check_cancelled, task};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::sync::atomic::AtomicBool;
const COMPAT: i32 = 4;
const MAGIC: u32 = 0x3154_5853;
const MAX_BYTES: usize = 4 * 1024 * 1024;

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_text_read(
    id: u64,
    fd: i32,
    offset: u32,
    limit: u32,
    max_bytes: u32,
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
    if fd < 0
        || offset == 0
        || !(1..=5000).contains(&limit)
        || max_bytes == 0
        || max_bytes as usize > MAX_BYTES
    {
        return STATUS_REQUEST_ERROR;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        let cancelled = task(id).ok_or(STATUS_REQUEST_ERROR)?;
        check_cancelled(&cancelled)?;
        let bytes = read(fd, max_bytes as usize, &cancelled)?;
        let text = std::str::from_utf8(&bytes).map_err(|_| COMPAT)?;
        let mut lines: Vec<Vec<u16>> = Vec::new();
        let mut current = Vec::new();
        let mut number = 1u32;
        let mut length = 0u32;
        let mut hash = 0u32;
        let mut previous_cr = false;
        let selected = |n: u32| n >= offset && (n as u64) < offset as u64 + limit as u64;
        for (index, ch) in text.chars().enumerate() {
            if index % 1024 == 0 {
                check_cancelled(&cancelled)?;
            }
            let mut units = [0u16; 2];
            let encoded = ch.encode_utf16(&mut units);
            for &unit in encoded.iter() {
                length += 1;
                hash = hash.wrapping_mul(31).wrapping_add(unit as u32);
            }
            if ch == '\n' && previous_cr {
                previous_cr = false;
                continue;
            }
            previous_cr = ch == '\r';
            if ch == '\r' || ch == '\n' {
                if selected(number) {
                    lines.push(std::mem::take(&mut current));
                }
                number += 1;
            } else if selected(number) {
                current.extend_from_slice(encoded);
            }
        }
        if selected(number) {
            lines.push(current);
        }
        check_cancelled(&cancelled)?;
        let mut response = Vec::new();
        for value in [MAGIC, number, lines.len() as u32, length, hash] {
            response.extend_from_slice(&value.to_le_bytes());
        }
        for line in lines {
            check_cancelled(&cancelled)?;
            response.extend_from_slice(&(line.len() as u32).to_le_bytes());
            for (index, unit) in line.into_iter().enumerate() {
                if index % 1024 == 0 {
                    check_cancelled(&cancelled)?;
                }
                response.extend_from_slice(&unit.to_le_bytes());
            }
        }
        check_cancelled(&cancelled)?;
        let owned = response.into_boxed_slice();
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

#[cfg(unix)]
fn read(fd: i32, max: usize, cancelled: &AtomicBool) -> Result<Vec<u8>, i32> {
    use std::fs::File;
    use std::mem::ManuallyDrop;
    use std::os::fd::FromRawFd;
    use std::os::unix::fs::FileExt;
    let file = ManuallyDrop::new(unsafe { File::from_raw_fd(fd) });
    let meta = file.metadata().map_err(|_| COMPAT)?;
    if !meta.is_file() || meta.len() > max as u64 {
        return Err(COMPAT);
    }
    let mut bytes = Vec::new();
    let mut block = [0u8; 8192];
    loop {
        check_cancelled(cancelled)?;
        let n = match file.read_at(&mut block, bytes.len() as u64) {
            Ok(n) => n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => return Err(COMPAT),
        };
        if n == 0 {
            break;
        }
        if bytes.len() + n > max {
            return Err(COMPAT);
        }
        bytes.extend_from_slice(&block[..n]);
    }
    Ok(bytes)
}
#[cfg(not(unix))]
fn read(_fd: i32, _max: usize, cancelled: &AtomicBool) -> Result<Vec<u8>, i32> {
    check_cancelled(cancelled)?;
    Err(COMPAT)
}
