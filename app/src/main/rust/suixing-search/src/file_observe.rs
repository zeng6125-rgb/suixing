//! Bounded filesystem snapshot, SHA-256 change detection, rename verification,
//! and logical line diff counts for shell tool observation.

use crate::sha256::Sha256;
use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR, SearchBuffer};
use std::collections::{HashMap, HashSet};
use std::fs::{self, File};
use std::io::{ErrorKind, Read};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;
use std::ptr;
use std::slice;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

const MAGIC: u32 = 0x314F_5853; // SX O 1
const MAX_REQUEST_BYTES: usize = 256 * 1024;
const MAX_RESPONSE_BYTES: usize = 16 * 1024 * 1024;
const MAX_ROOTS: usize = 12;
const MAX_DEPTH: usize = 12;
const DEADLINE_MS: u64 = 300;
const MAX_HASH_FILE: u64 = 4 * 1024 * 1024;
const MAX_HASH_TOTAL: u64 = 16 * 1024 * 1024;
const MAX_TEXT_FILE: usize = 128 * 1024;
const MAX_TEXT_TOTAL: usize = 2 * 1024 * 1024;
const ACTIVITY_LINE_LIMIT: usize = 4_000;
const STATUS_COMPATIBILITY: i32 = 4;

const KIND_CREATED: u32 = 0;
const KIND_MODIFIED: u32 = 1;
const KIND_DELETED: u32 = 2;
const KIND_MOVED: u32 = 3;

#[derive(Clone, Copy, PartialEq, Eq)]
struct FileIdentity {
    dev: u64,
    ino: u64,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum MtimeStamp {
    #[cfg(unix)]
    Unix(i64, i64),
    #[cfg(not(unix))]
    System(Option<std::time::SystemTime>),
}

#[cfg(unix)]
fn file_identity(meta: &fs::Metadata) -> Option<FileIdentity> {
    use std::os::unix::fs::MetadataExt;
    Some(FileIdentity {
        dev: meta.dev(),
        ino: meta.ino(),
    })
}

#[cfg(not(unix))]
fn file_identity(_meta: &fs::Metadata) -> Option<FileIdentity> {
    None
}

#[cfg(unix)]
fn mtime_stamp(meta: &fs::Metadata) -> MtimeStamp {
    use std::os::unix::fs::MetadataExt;
    MtimeStamp::Unix(meta.mtime(), meta.mtime_nsec())
}

#[cfg(not(unix))]
fn mtime_stamp(meta: &fs::Metadata) -> MtimeStamp {
    MtimeStamp::System(meta.modified().ok())
}

struct FileState {
    size: u64,
    digest: Option<[u8; 32]>,
    text_utf8: Option<Vec<u8>>,
    identity: Option<FileIdentity>,
}

struct Snapshot {
    order: Vec<String>,
    files: HashMap<String, FileState>,
    complete_roots: HashSet<String>,
    limited: bool,
}

struct Observation {
    roots: Vec<String>,
    max_entries: usize,
    before: Snapshot,
}

#[derive(Default)]
struct Registry {
    last_id: u64,
    entries: HashMap<u64, Observation>,
}

static OBSERVATIONS: OnceLock<Mutex<Registry>> = OnceLock::new();

fn registry() -> &'static Mutex<Registry> {
    OBSERVATIONS.get_or_init(|| Mutex::new(Registry::default()))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_file_obs_begin(
    request: *const u8,
    len: usize,
    max_entries: i32,
    force_limited: u8,
    out_handle: *mut u64,
) -> i32 {
    if out_handle.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    // SAFETY: Caller provides writable 8-byte out handle storage.
    unsafe { out_handle.write(0) };
    if max_entries <= 0 || (len > 0 && request.is_null()) || !(8..=MAX_REQUEST_BYTES).contains(&len)
    {
        return STATUS_COMPATIBILITY;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: Validated non-null `request` slice of `len` bytes for this call.
        let bytes = unsafe { slice::from_raw_parts(request, len) };
        let roots = decode_roots(bytes)?;
        let mut before = take_snapshot(&roots, max_entries as usize);
        if force_limited != 0 || roots.is_empty() {
            before.limited = true;
        }
        let mut reg = registry()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let Some(id) = reg
            .last_id
            .checked_add(1)
            .filter(|id| *id <= i64::MAX as u64)
        else {
            return Err(STATUS_COMPATIBILITY);
        };
        reg.last_id = id;
        reg.entries.insert(
            id,
            Observation {
                roots,
                max_entries: max_entries as usize,
                before,
            },
        );
        // SAFETY: `out_handle` was validated writable at entry.
        unsafe { out_handle.write(id) };
        Ok(())
    })) {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn sx_file_obs_release(id: u64) {
    if id == 0 || id > i64::MAX as u64 {
        return;
    }
    let _ = catch_unwind(|| {
        registry()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .entries
            .remove(&id);
    });
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_file_obs_finish(
    id: u64,
    overlapping: u8,
    output: *mut SearchBuffer,
) -> i32 {
    if output.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    // SAFETY: Caller provides writable SearchBuffer storage.
    unsafe {
        output.write(SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        });
    }
    if id == 0 || id > i64::MAX as u64 {
        return STATUS_REQUEST_ERROR;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        let Some(obs) = registry()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .entries
            .remove(&id)
        else {
            return Err(STATUS_REQUEST_ERROR);
        };
        let bytes = if overlapping != 0 {
            encode_changes(&[], 2)?
        } else {
            let after = take_snapshot(&obs.roots, obs.max_entries);
            let (activities, limited) = compute_changes(&obs.roots, &obs.before, &after);
            let notice = if limited { 1 } else { 0 };
            encode_changes(&activities, notice)?
        };
        if bytes.len() > MAX_RESPONSE_BYTES {
            return Err(STATUS_REQUEST_ERROR);
        }
        let owned = bytes.into_boxed_slice();
        let len = owned.len();
        let data = Box::into_raw(owned) as *mut u8;
        // SAFETY: Caller owns output storage and frees via `sx_search_free`.
        unsafe { output.write(SearchBuffer { data, len }) };
        Ok(())
    })) {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

fn decode_roots(bytes: &[u8]) -> Result<Vec<String>, i32> {
    if bytes.len() < 8 {
        return Err(STATUS_COMPATIBILITY);
    }
    let magic = u32::from_le_bytes(bytes[0..4].try_into().unwrap());
    let count = u32::from_le_bytes(bytes[4..8].try_into().unwrap()) as usize;
    if magic != MAGIC || count > MAX_ROOTS {
        return Err(STATUS_COMPATIBILITY);
    }
    let mut at = 8usize;
    let mut roots = Vec::with_capacity(count);
    for _ in 0..count {
        if at + 4 > bytes.len() {
            return Err(STATUS_COMPATIBILITY);
        }
        let unit_len = u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap()) as usize;
        at += 4;
        let byte_len = unit_len.checked_mul(2).ok_or(STATUS_COMPATIBILITY)?;
        if at + byte_len > bytes.len() {
            return Err(STATUS_COMPATIBILITY);
        }
        let units: Vec<u16> = (0..unit_len)
            .map(|i| u16::from_le_bytes([bytes[at + i * 2], bytes[at + i * 2 + 1]]))
            .collect();
        at += byte_len;
        let root = String::from_utf16(&units).map_err(|_| STATUS_COMPATIBILITY)?;
        roots.push(root);
    }
    if at != bytes.len() {
        return Err(STATUS_COMPATIBILITY);
    }
    Ok(roots)
}

fn take_snapshot(roots: &[String], max_entries: usize) -> Snapshot {
    let mut state = SnapshotBuilder {
        order: Vec::new(),
        files: HashMap::new(),
        visited: HashMap::new(),
        complete_roots: HashSet::new(),
        entries: 0,
        max_entries,
        remaining_hash: MAX_HASH_TOTAL,
        remaining_text: MAX_TEXT_TOTAL,
        limited: false,
        deadline: Instant::now() + Duration::from_millis(DEADLINE_MS),
        scratch: [0u8; 8192],
    };
    for root in roots {
        if state.scan(root, 0) {
            state.complete_roots.insert(root.clone());
        }
    }
    Snapshot {
        order: state.order,
        files: state.files,
        complete_roots: state.complete_roots,
        limited: state.limited,
    }
}

struct SnapshotBuilder {
    order: Vec<String>,
    files: HashMap<String, FileState>,
    visited: HashMap<String, bool>,
    complete_roots: HashSet<String>,
    entries: usize,
    max_entries: usize,
    remaining_hash: u64,
    remaining_text: usize,
    limited: bool,
    deadline: Instant,
    scratch: [u8; 8192],
}

impl SnapshotBuilder {
    fn scan(&mut self, path_str: &str, depth: usize) -> bool {
        if let Some(&cached) = self.visited.get(path_str) {
            return cached;
        }
        self.entries += 1;
        if self.entries > self.max_entries || depth > MAX_DEPTH || Instant::now() > self.deadline {
            self.limited = true;
            return false;
        }
        let scanned = self.scan_uncached(path_str, depth);
        if let Some(scanned) = scanned {
            self.visited.insert(path_str.to_owned(), scanned);
            scanned
        } else {
            true
        }
    }

    /// Returns `None` for `NotFound` (matching Kotlin's early return before `visited[path]`),
    /// otherwise `Some(scanned)`.
    fn scan_uncached(&mut self, path_str: &str, depth: usize) -> Option<bool> {
        let path = Path::new(path_str);
        let meta = match fs::symlink_metadata(path) {
            Ok(meta) => meta,
            Err(err) if err.kind() == ErrorKind::NotFound => return None,
            Err(_) => {
                self.limited = true;
                return Some(false);
            }
        };
        if meta.file_type().is_symlink() {
            self.limited = true;
            return Some(false);
        }
        if meta.is_dir() {
            let Ok(read_dir) = fs::read_dir(path) else {
                self.limited = true;
                return Some(false);
            };
            let mut all = true;
            for entry in read_dir {
                if self.entries >= self.max_entries || Instant::now() > self.deadline {
                    self.limited = true;
                    all = false;
                    break;
                }
                let Ok(entry) = entry else {
                    self.limited = true;
                    return Some(false);
                };
                let child_path = entry.path();
                let Some(child_str) = child_path.to_str() else {
                    self.limited = true;
                    return Some(false);
                };
                if !self.scan(child_str, depth + 1) {
                    all = false;
                }
            }
            return Some(all);
        }
        if meta.is_file() {
            let size = meta.len();
            let start_mtime = mtime_stamp(&meta);
            let start_id = file_identity(&meta);
            let mut digest: Option<[u8; 32]> = None;
            let mut text_utf8: Option<Vec<u8>> = None;

            if size <= MAX_HASH_FILE && size <= self.remaining_hash {
                let Ok(mut file) = File::open(path) else {
                    self.limited = true;
                    return Some(false);
                };
                let mut hasher = Sha256::new();
                let mut capture =
                    if size <= MAX_TEXT_FILE as u64 && (size as usize) <= self.remaining_text {
                        Some(Vec::with_capacity(size as usize))
                    } else {
                        None
                    };
                let mut read_total = 0u64;
                loop {
                    let count = match file.read(&mut self.scratch) {
                        Ok(0) => break,
                        Ok(n) => n,
                        Err(err) if err.kind() == ErrorKind::Interrupted => continue,
                        Err(_) => {
                            self.limited = true;
                            return Some(false);
                        }
                    };
                    read_total += count as u64;
                    if read_total > size
                        || read_total > self.remaining_hash
                        || Instant::now() > self.deadline
                    {
                        self.limited = true;
                        return Some(false);
                    }
                    hasher.update(&self.scratch[..count]);
                    if let Some(buf) = capture.as_mut() {
                        buf.extend_from_slice(&self.scratch[..count]);
                    }
                }
                self.remaining_hash -= read_total;
                if let Ok(end) = fs::symlink_metadata(path)
                    && read_total == size
                    && end.len() == size
                    && mtime_stamp(&end) == start_mtime
                    && file_identity(&end) == start_id
                {
                    digest = Some(hasher.finalize());
                    if let Some(bytes) = capture {
                        self.remaining_text -= bytes.len();
                        if !bytes.contains(&0) && std::str::from_utf8(&bytes).is_ok() {
                            text_utf8 = Some(bytes);
                        }
                    }
                } else {
                    self.limited = true;
                }
            } else {
                self.limited = true;
            }

            if !self.files.contains_key(path_str) {
                self.order.push(path_str.to_owned());
            }
            self.files.insert(
                path_str.to_owned(),
                FileState {
                    size,
                    digest,
                    text_utf8,
                    identity: start_id,
                },
            );
            return Some(true);
        }
        Some(true)
    }
}

struct ActivityRecord {
    path: String,
    kind: u32,
    added_lines: Option<i32>,
    removed_lines: Option<i32>,
    size_bytes: Option<u64>,
    previous_path: Option<String>,
}

fn is_under_complete_root(roots: &[String], path: &str, snapshot: &Snapshot) -> bool {
    roots.iter().any(|root| {
        snapshot.complete_roots.contains(root)
            && (path == root
                || (path.len() > root.len()
                    && path.starts_with(root)
                    && matches!(path.as_bytes().get(root.len()), Some(b'/' | b'\\'))))
    })
}

fn compute_changes(
    roots: &[String],
    old: &Snapshot,
    new: &Snapshot,
) -> (Vec<ActivityRecord>, bool) {
    let mut keys: Vec<&str> = Vec::with_capacity(old.order.len() + new.order.len());
    for k in &old.order {
        keys.push(k.as_str());
    }
    for k in &new.order {
        if !old.files.contains_key(k.as_str()) {
            keys.push(k.as_str());
        }
    }

    let mut activities: Vec<Option<ActivityRecord>> = Vec::new();
    for path in keys {
        let first = old.files.get(path);
        let last = new.files.get(path);
        let kind = match (first, last) {
            (None, Some(_)) if is_under_complete_root(roots, path, old) => KIND_CREATED,
            (Some(_), None) if is_under_complete_root(roots, path, new) => KIND_DELETED,
            (Some(a), Some(b))
                if a.size != b.size
                    || matches!((a.digest, b.digest), (Some(da), Some(db)) if da != db) =>
            {
                KIND_MODIFIED
            }
            _ => continue,
        };
        let before_text = match first {
            None => Some(&[][..]),
            Some(s) => s.text_utf8.as_deref(),
        };
        let after_text = match last {
            None => Some(&[][..]),
            Some(s) => s.text_utf8.as_deref(),
        };
        let counts = activity_line_counts(before_text, after_text);
        activities.push(Some(ActivityRecord {
            path: path.to_owned(),
            kind,
            added_lines: counts.map(|(a, _)| a),
            removed_lines: counts.map(|(_, r)| r),
            size_bytes: last.map(|s| s.size),
            previous_path: None,
        }));
    }

    let deleted_indices: Vec<usize> = activities
        .iter()
        .enumerate()
        .filter_map(|(i, item)| item.as_ref().filter(|r| r.kind == KIND_DELETED).map(|_| i))
        .collect();
    let created_indices: Vec<usize> = activities
        .iter()
        .enumerate()
        .filter_map(|(i, item)| item.as_ref().filter(|r| r.kind == KIND_CREATED).map(|_| i))
        .collect();

    for d_idx in deleted_indices {
        let Some(source_path) = activities[d_idx].as_ref().map(|r| r.path.clone()) else {
            continue;
        };
        let first = &old.files[&source_path];
        let (Some(first_id), Some(first_digest)) = (first.identity, first.digest) else {
            continue;
        };
        let mut deleted_same_id = 0usize;
        for slot in &activities {
            if let Some(rec) = slot.as_ref()
                && rec.kind == KIND_DELETED
                && old.files[&rec.path].identity == Some(first_id)
            {
                deleted_same_id += 1;
            }
        }
        if deleted_same_id != 1 {
            continue;
        }
        let mut matched_target: Option<usize> = None;
        let mut match_count = 0usize;
        for &c_idx in &created_indices {
            let Some(target) = activities[c_idx].as_ref() else {
                continue;
            };
            let next_state = &new.files[&target.path];
            if next_state.identity == Some(first_id) && next_state.digest == Some(first_digest) {
                matched_target = Some(c_idx);
                match_count += 1;
            }
        }
        if match_count != 1 {
            continue;
        }
        let Some(c_idx) = matched_target else {
            continue;
        };
        if activities[d_idx].is_none() || activities[c_idx].is_none() {
            continue;
        }
        activities[d_idx] = None;
        if let Some(target) = activities[c_idx].as_mut() {
            target.kind = KIND_MOVED;
            target.previous_path = Some(source_path);
            target.added_lines = None;
            target.removed_lines = None;
        }
    }

    let final_list: Vec<ActivityRecord> = activities.into_iter().flatten().collect();
    (final_list, old.limited || new.limited)
}

fn activity_line_counts(before: Option<&[u8]>, after: Option<&[u8]>) -> Option<(i32, i32)> {
    let (before, after) = (before?, after?);
    if before.len() > MAX_TEXT_FILE
        || after.len() > MAX_TEXT_FILE
        || before.contains(&0)
        || after.contains(&0)
    {
        return None;
    }
    if before == after {
        return Some((0, 0));
    }
    let old_ends = activity_line_ends(before)?;
    let new_ends = activity_line_ends(after)?;
    let equal_line = |old_idx: usize, new_idx: usize| -> bool {
        let old_start = if old_idx == 0 {
            0
        } else {
            old_ends[old_idx - 1]
        };
        let new_start = if new_idx == 0 {
            0
        } else {
            new_ends[new_idx - 1]
        };
        before[old_start..old_ends[old_idx]] == after[new_start..new_ends[new_idx]]
    };
    let mut first = 0usize;
    while first < old_ends.len() && first < new_ends.len() && equal_line(first, first) {
        first += 1;
    }
    let mut old_last = old_ends.len();
    let mut new_last = new_ends.len();
    while old_last > first && new_last > first && equal_line(old_last - 1, new_last - 1) {
        old_last -= 1;
        new_last -= 1;
    }
    Some(((new_last - first) as i32, (old_last - first) as i32))
}

fn activity_line_ends(bytes: &[u8]) -> Option<Vec<usize>> {
    if bytes.is_empty() {
        return Some(Vec::new());
    }
    let mut ends = Vec::new();
    let mut index = 0usize;
    let mut last_end = 0usize;
    while index < bytes.len() {
        let b = bytes[index];
        index += 1;
        if b == b'\r' || b == b'\n' {
            if b == b'\r' && bytes.get(index) == Some(&b'\n') {
                index += 1;
            }
            if ends.len() >= ACTIVITY_LINE_LIMIT {
                return None;
            }
            ends.push(index);
            last_end = index;
        }
    }
    if last_end < bytes.len() {
        if ends.len() >= ACTIVITY_LINE_LIMIT {
            return None;
        }
        ends.push(bytes.len());
    }
    Some(ends)
}

fn put_utf16_str(out: &mut Vec<u8>, s: &str) {
    let units: Vec<u16> = s.encode_utf16().collect();
    out.extend_from_slice(&(units.len() as u32).to_le_bytes());
    for u in units {
        out.extend_from_slice(&u.to_le_bytes());
    }
}

fn put_opt_utf16_str(out: &mut Vec<u8>, s: Option<&str>) {
    match s {
        None => out.extend_from_slice(&u32::MAX.to_le_bytes()),
        Some(text) => put_utf16_str(out, text),
    }
}

fn encode_changes(activities: &[ActivityRecord], notice_code: u32) -> Result<Vec<u8>, i32> {
    let mut out = Vec::with_capacity(64 + activities.len() * 96);
    out.extend_from_slice(&MAGIC.to_le_bytes());
    out.extend_from_slice(&notice_code.to_le_bytes());
    out.extend_from_slice(&(activities.len() as u32).to_le_bytes());
    for item in activities {
        out.extend_from_slice(&item.kind.to_le_bytes());
        out.extend_from_slice(&item.added_lines.unwrap_or(-1).to_le_bytes());
        out.extend_from_slice(&item.removed_lines.unwrap_or(-1).to_le_bytes());
        let size = item.size_bytes.map_or(-1i64, |v| v as i64);
        out.extend_from_slice(&size.to_le_bytes());
        put_utf16_str(&mut out, &item.path);
        put_opt_utf16_str(&mut out, item.previous_path.as_deref());
        if out.len() > MAX_RESPONSE_BYTES {
            return Err(STATUS_REQUEST_ERROR);
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn activity_line_counts_and_changes_capture_writes_edits_deletes_and_limits() {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir = std::env::temp_dir().join(format!("suixing_obs_test_{nanos}"));
        fs::create_dir_all(&dir).unwrap();
        let edited = dir.join("edited.txt");
        let deleted = dir.join("deleted.txt");
        let unchanged = dir.join("unchanged.txt");
        fs::write(&edited, "before\n").unwrap();
        fs::write(&deleted, "remove\n").unwrap();
        fs::write(&unchanged, "same\n").unwrap();

        let roots = vec![dir.to_str().unwrap().to_owned()];
        let before = take_snapshot(&roots, 512);
        fs::write(&edited, "after\nextra\n").unwrap();
        fs::remove_file(&deleted).unwrap();
        fs::write(dir.join("copy.txt"), "same\n").unwrap();
        let after = take_snapshot(&roots, 512);
        let (activities, limited) = compute_changes(&roots, &before, &after);
        assert!(!limited);
        let edited_act = activities
            .iter()
            .find(|a| a.path.ends_with("edited.txt"))
            .unwrap();
        assert_eq!(edited_act.kind, KIND_MODIFIED);
        assert_eq!(edited_act.added_lines, Some(2));
        assert_eq!(edited_act.removed_lines, Some(1));
        assert!(
            activities
                .iter()
                .any(|a| a.path.ends_with("deleted.txt") && a.kind == KIND_DELETED)
        );
        assert!(
            activities
                .iter()
                .any(|a| a.path.ends_with("copy.txt") && a.kind == KIND_CREATED)
        );

        // Bounded scan limit must not emit false additions/deletions.
        let before_small = take_snapshot(&roots, 1);
        fs::write(dir.join("new_under_limit.txt"), "hi").unwrap();
        let after_small = take_snapshot(&roots, 1);
        let (bounded_acts, bounded_limited) = compute_changes(&roots, &before_small, &after_small);
        assert!(bounded_limited);
        assert!(bounded_acts.is_empty());

        let _ = fs::remove_dir_all(&dir);
    }
}
