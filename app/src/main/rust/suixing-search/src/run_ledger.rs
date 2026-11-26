//! Bounded run-local repetition and progress state. Only tool tokens and SHA-256 bytes enter.
use crate::sha256::Sha256;
use crate::{STATUS_OK, STATUS_PANIC, STATUS_REQUEST_ERROR};
use std::collections::{BTreeMap, BTreeSet, HashMap, VecDeque};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::slice;
use std::sync::{Mutex, OnceLock};

const STATUS_COMPATIBILITY: i32 = 4;
const MAX_PAYLOAD_UNITS: usize = 16 * 1024 * 1024;
const MAX_LINE_SCAN_UNITS: usize = 16 * 1024 * 1024;

#[derive(Clone, Copy)]
struct Entry {
    step: i32,
    tool: u64,
    ok: bool,
    #[allow(dead_code)]
    args: [u8; 32],
    #[allow(dead_code)]
    result: [u8; 32],
    #[allow(dead_code)]
    at: i64,
}
type Key = (u64, [u8; 32]);
struct Repeated {
    result: [u8; 32],
    count: i32,
    notified: i32,
    order: u128,
}
#[derive(Clone, Copy)]
struct Reminder {
    tool: u64,
    level: i32,
    count: i32,
}
struct Ledger {
    limit: usize,
    threshold: i32,
    entries: VecDeque<Entry>,
    repeated: HashMap<Key, Repeated>,
    order: BTreeMap<u128, Key>,
    polls: HashMap<u64, [u8; 32]>,
    poll_order: VecDeque<u64>,
    reminders: VecDeque<Reminder>,
    sequence: u128,
    total: i32,
    failed: i32,
}
impl Ledger {
    fn new(threshold: i32, limit: usize) -> Self {
        Self {
            limit,
            threshold,
            entries: VecDeque::new(),
            repeated: HashMap::new(),
            order: BTreeMap::new(),
            polls: HashMap::new(),
            poll_order: VecDeque::new(),
            reminders: VecDeque::new(),
            sequence: 0,
            total: 0,
            failed: 0,
        }
    }
    fn progress(&mut self) {
        for entry in self.repeated.values_mut() {
            entry.count = 0;
            entry.notified = 0;
        }
        self.reminders.clear();
    }
    fn record(&mut self, entry: Entry, polling: bool) -> Result<(), i32> {
        // Reserve a never-reused order before changing state. Counters intentionally wrap as JVM Int.
        let sequence = self.sequence.checked_add(1).ok_or(STATUS_REQUEST_ERROR)?;
        self.entries.push_back(entry);
        while self.entries.len() > self.limit {
            self.entries.pop_front();
        }
        self.total = self.total.wrapping_add(1);
        if !entry.ok {
            self.failed = self.failed.wrapping_add(1);
        }
        if polling {
            let previous = self.polls.insert(entry.tool, entry.result);
            if previous.is_none() {
                self.poll_order.push_back(entry.tool);
            }
            if previous.is_some_and(|previous| previous != entry.result) {
                self.progress();
            }
            while self.polls.len() > self.limit {
                if let Some(tool) = self.poll_order.pop_front() {
                    self.polls.remove(&tool);
                }
            }
            return Ok(());
        }
        self.sequence = sequence;
        let key = (entry.tool, entry.args);
        let previous = self.repeated.remove(&key);
        if let Some(ref previous) = previous {
            self.order.remove(&previous.order);
        }
        let mut value = match previous {
            Some(previous) if previous.result == entry.result => Repeated {
                count: previous.count.wrapping_add(1),
                order: sequence,
                ..previous
            },
            _ => {
                self.progress();
                Repeated {
                    result: entry.result,
                    count: 1,
                    notified: 0,
                    order: sequence,
                }
            }
        };
        let level = if i64::from(value.count) >= i64::from(self.threshold) * 2 {
            2
        } else if value.count >= self.threshold {
            1
        } else {
            0
        };
        if level > value.notified {
            value.notified = level;
            self.reminders.push_back(Reminder {
                tool: entry.tool,
                level,
                count: value.count,
            });
            while self.reminders.len() > 2 {
                self.reminders.pop_front();
            }
        }
        self.repeated.insert(key, value);
        self.order.insert(sequence, key);
        while self.repeated.len() > self.limit {
            if let Some((_, oldest)) = self.order.pop_first() {
                self.repeated.remove(&oldest);
            }
        }
        Ok(())
    }
    fn retained(&self) -> BTreeSet<u64> {
        self.entries
            .iter()
            .map(|e| e.tool)
            .chain(self.repeated.keys().map(|k| k.0))
            .chain(self.polls.keys().copied())
            .chain(self.reminders.iter().map(|r| r.tool))
            .collect()
    }
}

/// Progress and repetition state for one agent run.
pub struct RunLedger(Ledger);

impl RunLedger {
    pub fn new(max_rounds: u32) -> Self {
        let threshold = if max_rounds == 0 {
            5
        } else {
            (max_rounds / 10).max(3) as i32
        };
        Self(Ledger::new(threshold, 96))
    }

    pub fn record(
        &mut self,
        tool: u64,
        args: [u8; 32],
        result: [u8; 32],
        ok: bool,
        polling: bool,
    ) -> Result<(), i32> {
        self.0.record(
            Entry {
                step: self.0.total.wrapping_add(1),
                tool,
                ok,
                args,
                result,
                at: 0,
            },
            polling,
        )
    }

    pub fn warning(&mut self) -> Option<(u64, i32, i32)> {
        self.0
            .reminders
            .pop_front()
            .map(|value| (value.tool, value.level, value.count))
    }

    pub fn totals(&self) -> (i32, i32) {
        (self.0.total, self.0.failed)
    }

    pub fn recent(&self) -> impl Iterator<Item = (i32, u64, bool)> + '_ {
        self.0
            .entries
            .iter()
            .skip(self.0.entries.len().saturating_sub(4))
            .map(|entry| (entry.step, entry.tool, entry.ok))
    }

    pub fn retained_tools(&self) -> BTreeSet<u64> {
        self.0.retained()
    }
}

#[derive(Default)]
struct LedgerRegistry {
    last_id: u64,
    entries: HashMap<u64, Ledger>,
}

static LEDGERS: OnceLock<Mutex<LedgerRegistry>> = OnceLock::new();

fn ledgers() -> &'static Mutex<LedgerRegistry> {
    LEDGERS.get_or_init(|| Mutex::new(LedgerRegistry::default()))
}

fn write_utf16_bytes(hasher: &mut Sha256, units: &[u16]) {
    let mut buf = [0u8; 4096];
    let mut used = 0usize;
    let mut scratch = [0u8; 4];
    for decoded in std::char::decode_utf16(units.iter().copied()) {
        let encoded = match decoded {
            Ok(ch) => ch.encode_utf8(&mut scratch).as_bytes(),
            Err(_) => b"?",
        };
        if used + encoded.len() > buf.len() {
            hasher.update(&buf[..used]);
            used = 0;
        }
        buf[used..used + encoded.len()].copy_from_slice(encoded);
        used += encoded.len();
    }
    if used > 0 {
        hasher.update(&buf[..used]);
    }
}

fn write_u16_len_prefix(hasher: &mut Sha256, u16_len: usize) {
    let mut digits = [0u8; 24];
    let text = u16_len.to_string();
    let len = text.len().min(digits.len() - 2);
    digits[0] = b's';
    digits[1..1 + len].copy_from_slice(&text.as_bytes()[..len]);
    digits[1 + len] = b':';
    hasher.update(&digits[..2 + len]);
}

fn write_u16_len_str(hasher: &mut Sha256, units: &[u16]) {
    write_u16_len_prefix(hasher, units.len());
    write_utf16_bytes(hasher, units);
}

#[inline]
fn is_java_pattern_ws(u: u16) -> bool {
    matches!(u, 0x20 | 0x09 | 0x0A | 0x0B | 0x0C | 0x0D)
}

#[inline]
fn is_ascii_word(u: u16) -> bool {
    (b'a' as u16..=b'z' as u16).contains(&u)
        || (b'A' as u16..=b'Z' as u16).contains(&u)
        || (b'0' as u16..=b'9' as u16).contains(&u)
        || u == b'_' as u16
}

fn starts_with_ascii(slice: &[u16], lit: &[u8]) -> bool {
    slice.len() >= lit.len()
        && slice
            .iter()
            .zip(lit.iter())
            .all(|(&u, &b)| u == u16::from(b))
}

fn match_job_list_elapsed(line: &[u16]) -> Option<(usize, usize)> {
    // Matches Java regex: ^(- \S+ \[(?:running|done|failed)\]) \d+\.\ds\b
    if line.len() < 2 || line[0] != u16::from(b'-') || line[1] != u16::from(b' ') {
        return None;
    }
    let mut i = 2usize;
    while i < line.len() && !is_java_pattern_ws(line[i]) {
        i += 1;
    }
    if i == 2 {
        return None;
    }
    let rest = &line[i..];
    let (status_with_brackets, tag_len) = if starts_with_ascii(rest, b" [running] ") {
        (10usize, 11usize)
    } else if starts_with_ascii(rest, b" [done] ") {
        (7usize, 8usize)
    } else if starts_with_ascii(rest, b" [failed] ") {
        (9usize, 10usize)
    } else {
        return None;
    };
    let mut p = i + tag_len;
    let d1_start = p;
    while p < line.len() && (b'0' as u16..=b'9' as u16).contains(&line[p]) {
        p += 1;
    }
    if p == d1_start || p >= line.len() || line[p] != u16::from(b'.') {
        return None;
    }
    p += 1;
    let d2_start = p;
    while p < line.len() && (b'0' as u16..=b'9' as u16).contains(&line[p]) {
        p += 1;
    }
    if p == d2_start || p >= line.len() || line[p] != u16::from(b's') {
        return None;
    }
    p += 1;
    if p < line.len() && is_ascii_word(line[p]) {
        return None;
    }
    Some((i + status_with_brackets, p))
}

fn find_job_list_drops(units: &[u16]) -> Result<(Vec<(usize, usize)>, usize), i32> {
    if units.len() > MAX_LINE_SCAN_UNITS {
        return Err(STATUS_COMPATIBILITY);
    }
    if units.iter().any(|&u| matches!(u, 0x0085 | 0x2028 | 0x2029)) {
        return Err(STATUS_COMPATIBILITY);
    }
    let mut drops = Vec::new();
    let mut dropped_units = 0usize;
    let mut line_start = 0usize;
    while line_start <= units.len() {
        let mut idx = line_start;
        while idx < units.len() && units[idx] != 0x000A && units[idx] != 0x000D {
            idx += 1;
        }
        let line_end = idx;
        if let Some((drop_rel_start, drop_rel_end)) =
            match_job_list_elapsed(&units[line_start..line_end])
        {
            let start = line_start + drop_rel_start;
            let end = line_start + drop_rel_end;
            dropped_units += end - start;
            drops.push((start, end));
        }
        if idx == units.len() {
            break;
        }
        if units[idx] == 0x000D {
            idx += 1;
            if idx < units.len() && units[idx] == 0x000A {
                idx += 1;
            }
        } else {
            idx += 1;
        }
        line_start = idx;
    }
    Ok((drops, dropped_units))
}

pub(crate) fn hash_result(
    ok: bool,
    output: &[u16],
    image: Option<&[u16]>,
    is_job_list: bool,
) -> Result<[u8; 32], i32> {
    let mut hasher = Sha256::new();
    hasher.update(if ok { b"ok:" } else { b"failed:" });
    if !is_job_list {
        write_u16_len_str(&mut hasher, output);
    } else {
        let (drops, dropped) = find_job_list_drops(output)?;
        if drops.is_empty() {
            write_u16_len_str(&mut hasher, output);
        } else {
            let kept_len = output.len() - dropped;
            write_u16_len_prefix(&mut hasher, kept_len);
            let mut cursor = 0usize;
            for (drop_start, drop_end) in drops {
                if drop_start > cursor {
                    write_utf16_bytes(&mut hasher, &output[cursor..drop_start]);
                }
                cursor = drop_end;
            }
            if cursor < output.len() {
                write_utf16_bytes(&mut hasher, &output[cursor..]);
            }
        }
    }
    if let Some(img) = image {
        write_u16_len_str(&mut hasher, img);
    }
    Ok(hasher.finalize())
}

#[unsafe(no_mangle)]
pub extern "C" fn sx_run_ledger_create(max_rounds: i32, capacity: i32) -> u64 {
    catch_unwind(|| {
        let threshold = if max_rounds <= 0 {
            5
        } else {
            (max_rounds / 10).max(3)
        };
        let limit = capacity.max(1) as usize;
        let mut registry = ledgers()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let Some(id) = registry
            .last_id
            .checked_add(1)
            .filter(|id| *id <= i64::MAX as u64)
        else {
            return 0;
        };
        registry.last_id = id;
        registry.entries.insert(id, Ledger::new(threshold, limit));
        id
    })
    .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "C" fn sx_run_ledger_release(id: u64) {
    let _ = catch_unwind(|| {
        if id == 0 || id > i64::MAX as u64 {
            return;
        }
        ledgers()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .entries
            .remove(&id);
    });
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_run_ledger_record(
    id: u64,
    step: i32,
    tool_id: u64,
    polling: u8,
    args_hash: *const u8,
    ok: u8,
    output: *const u16,
    output_len: usize,
    image: *const u16,
    image_len: usize,
    has_image: u8,
    is_job_list: u8,
    at_ms: i64,
) -> i32 {
    if id == 0
        || id > i64::MAX as u64
        || args_hash.is_null()
        || output_len > MAX_PAYLOAD_UNITS
        || (output_len != 0 && output.is_null())
        || image_len > MAX_PAYLOAD_UNITS
        || (has_image != 0 && image_len != 0 && image.is_null())
    {
        return STATUS_REQUEST_ERROR;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        let mut args = [0u8; 32];
        unsafe {
            std::ptr::copy_nonoverlapping(args_hash, args.as_mut_ptr(), 32);
        }
        let output_slice = if output_len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(output, output_len) }
        };
        let image_slice = if has_image == 0 {
            None
        } else if image_len == 0 {
            Some(&[][..])
        } else {
            Some(unsafe { slice::from_raw_parts(image, image_len) })
        };
        let result = hash_result(ok != 0, output_slice, image_slice, is_job_list != 0)?;
        let mut registry = ledgers()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let ledger = registry.entries.get_mut(&id).ok_or(STATUS_REQUEST_ERROR)?;
        ledger.record(
            Entry {
                step,
                tool: tool_id,
                ok: ok != 0,
                args,
                result,
                at: at_ms,
            },
            polling != 0,
        )?;
        Ok(())
    })) {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_run_ledger_pop_warning(
    id: u64,
    out_has: *mut i32,
    out_reminder: *mut i64,
) -> i32 {
    if id == 0 || id > i64::MAX as u64 || out_has.is_null() || out_reminder.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        let mut registry = ledgers()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let ledger = registry.entries.get_mut(&id).ok_or(STATUS_REQUEST_ERROR)?;
        if let Some(rem) = ledger.reminders.pop_front() {
            unsafe {
                out_has.write(1);
                out_reminder.add(0).write(rem.tool as i64);
                out_reminder.add(1).write(rem.level as i64);
                out_reminder.add(2).write(rem.count as i64);
            }
        } else {
            unsafe { out_has.write(0) };
        }
        Ok(())
    })) {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_run_ledger_summary(
    id: u64,
    out_totals: *mut i32,
    out_recent: *mut i64,
) -> i32 {
    if id == 0 || id > i64::MAX as u64 || out_totals.is_null() || out_recent.is_null() {
        return STATUS_REQUEST_ERROR;
    }
    match catch_unwind(AssertUnwindSafe(|| {
        let registry = ledgers()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let ledger = registry.entries.get(&id).ok_or(STATUS_REQUEST_ERROR)?;
        let recent_start = ledger.entries.len().saturating_sub(4);
        let recent_count = ledger.entries.len() - recent_start;
        unsafe {
            out_totals.add(0).write(ledger.total);
            out_totals.add(1).write(ledger.failed);
            out_totals.add(2).write(recent_count as i32);
        }
        for (idx, entry) in ledger.entries.iter().skip(recent_start).enumerate() {
            let base = idx * 3;
            unsafe {
                out_recent.add(base).write(entry.step as i64);
                out_recent.add(base + 1).write(entry.tool as i64);
                out_recent.add(base + 2).write(if entry.ok { 1 } else { 0 });
            }
        }
        Ok(())
    })) {
        Ok(Ok(())) => STATUS_OK,
        Ok(Err(status)) => status,
        Err(_) => STATUS_PANIC,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn u16s(s: &str) -> Vec<u16> {
        s.encode_utf16().collect()
    }

    #[test]
    fn finite_unlimited_progress_polling_and_bounded_summary_match_contract() {
        let id = sx_run_ledger_create(20, 96);
        assert!(id > 0);
        let args = crate::sha256::hash_bytes(b"{\"command\":\"echo same\"}");
        let same_out = u16s("same result");
        for step in 1..=2 {
            assert_eq!(
                unsafe {
                    sx_run_ledger_record(
                        id,
                        step,
                        1,
                        0,
                        args.as_ptr(),
                        1,
                        same_out.as_ptr(),
                        same_out.len(),
                        std::ptr::null(),
                        0,
                        0,
                        0,
                        step as i64,
                    )
                },
                STATUS_OK
            );
            let mut has = 0;
            let mut rem = [0i64; 3];
            assert_eq!(
                unsafe { sx_run_ledger_pop_warning(id, &mut has, rem.as_mut_ptr()) },
                STATUS_OK
            );
            assert_eq!(has, 0);
        }
        assert_eq!(
            unsafe {
                sx_run_ledger_record(
                    id,
                    3,
                    1,
                    0,
                    args.as_ptr(),
                    1,
                    same_out.as_ptr(),
                    same_out.len(),
                    std::ptr::null(),
                    0,
                    0,
                    0,
                    3,
                )
            },
            STATUS_OK
        );
        let mut has = 0;
        let mut rem = [0i64; 3];
        assert_eq!(
            unsafe { sx_run_ledger_pop_warning(id, &mut has, rem.as_mut_ptr()) },
            STATUS_OK
        );
        assert_eq!(has, 1);
        assert_eq!(rem, [1, 1, 3]);
        assert_eq!(
            unsafe { sx_run_ledger_pop_warning(id, &mut has, rem.as_mut_ptr()) },
            STATUS_OK
        );
        assert_eq!(has, 0);

        for step in 4..=6 {
            assert_eq!(
                unsafe {
                    sx_run_ledger_record(
                        id,
                        step,
                        1,
                        0,
                        args.as_ptr(),
                        1,
                        same_out.as_ptr(),
                        same_out.len(),
                        std::ptr::null(),
                        0,
                        0,
                        0,
                        step as i64,
                    )
                },
                STATUS_OK
            );
        }
        assert_eq!(
            unsafe { sx_run_ledger_pop_warning(id, &mut has, rem.as_mut_ptr()) },
            STATUS_OK
        );
        assert_eq!(has, 1);
        assert_eq!(rem, [1, 2, 6]);

        // Changed polling result resets repetition progress.
        let poll_out = u16s("new output");
        assert_eq!(
            unsafe {
                sx_run_ledger_record(
                    id,
                    7,
                    2,
                    1,
                    args.as_ptr(),
                    1,
                    same_out.as_ptr(),
                    same_out.len(),
                    std::ptr::null(),
                    0,
                    0,
                    0,
                    7,
                )
            },
            STATUS_OK
        );
        assert_eq!(
            unsafe {
                sx_run_ledger_record(
                    id,
                    8,
                    2,
                    1,
                    args.as_ptr(),
                    1,
                    poll_out.as_ptr(),
                    poll_out.len(),
                    std::ptr::null(),
                    0,
                    0,
                    0,
                    8,
                )
            },
            STATUS_OK
        );
        let mut totals = [0i32; 3];
        let mut recent = [0i64; 12];
        assert_eq!(
            unsafe { sx_run_ledger_summary(id, totals.as_mut_ptr(), recent.as_mut_ptr()) },
            STATUS_OK
        );
        assert_eq!(totals, [8, 0, 4]);
        sx_run_ledger_release(id);
    }

    #[test]
    fn job_list_elapsed_and_unpaired_surrogates_match_kotlin_hash() {
        let j1 = u16s("- j1 [running] 1.2s — echo\n- j2 [done] 12.0s\n- j3 [failed] 0.5sec");
        let j2 = u16s("- j1 [running] 99.9s — echo\n- j2 [done] 0.0s\n- j3 [failed] 0.5sec");
        let h1 = hash_result(true, &j1, None, true).unwrap();
        let h2 = hash_result(true, &j2, None, true).unwrap();
        assert_eq!(h1, h2);

        // Kotlin OutputStreamWriter(UTF_8) writes unpaired surrogates as '?' and counts UTF-16 length.
        let bad = [0xD800u16, b'x' as u16];
        let h_bad = hash_result(true, &bad, None, false).unwrap();
        let mut expected = Sha256::new();
        expected.update(b"ok:s2:?x");
        assert_eq!(h_bad, expected.finalize());
    }
}
