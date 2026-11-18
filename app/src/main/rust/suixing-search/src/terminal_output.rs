use std::collections::VecDeque;

pub(crate) const HISTORY_UNITS: usize = 256 * 1024;
pub(crate) const READ_UNITS: usize = 16 * 1024;

/// The cursor counts UTF-16 units, including output that has been cleared or evicted.
pub(crate) struct TerminalOutput {
    units: VecDeque<u16>,
    base: i64,
    limit: usize,
    pending: [u8; 4],
    pending_len: usize,
}

impl TerminalOutput {
    pub(crate) fn new(limit: usize) -> Self {
        Self {
            units: VecDeque::new(),
            base: 0,
            limit,
            pending: [0; 4],
            pending_len: 0,
        }
    }

    pub(crate) fn truncated(&self) -> bool {
        self.base > 0
    }

    pub(crate) fn clear(&mut self) {
        self.base += self.units.len() as i64;
        self.units.clear();
    }

    pub(crate) fn append(&mut self, mut bytes: &[u8], eof: bool) {
        // Join only an incomplete scalar to the next read, rather than copying the whole chunk.
        while self.pending_len != 0 && !bytes.is_empty() {
            let mut prefix = self.pending;
            prefix[self.pending_len] = bytes[0];
            let len = self.pending_len + 1;
            self.pending_len = 0;
            bytes = &bytes[1..];
            self.decode(&prefix[..len], false);
        }
        if eof && self.pending_len != 0 {
            self.pending_len = 0;
            self.push(0xfffd);
        }
        self.decode(bytes, eof);
        if self.units.front().is_some_and(|&unit| low_surrogate(unit)) {
            self.units.pop_front();
            self.base += 1;
        }
    }

    fn push(&mut self, unit: u16) {
        if self.units.len() == self.limit {
            self.units.pop_front();
            self.base += 1;
        }
        self.units.push_back(unit);
    }

    fn extend(&mut self, text: &str) {
        if text.is_ascii() {
            let keep = text.len().min(self.limit);
            let skip = text.len() - keep;
            let drop = self.units.len().saturating_sub(self.limit - keep);
            self.units.drain(..drop);
            self.base += (drop + skip) as i64;
            self.units
                .extend(text.as_bytes()[skip..].iter().map(|&byte| u16::from(byte)));
            return;
        }
        for unit in text.encode_utf16() {
            self.push(unit);
        }
    }

    fn decode(&mut self, mut bytes: &[u8], eof: bool) {
        while !bytes.is_empty() {
            let tail = match std::str::from_utf8(bytes) {
                Ok(text) => {
                    self.extend(text);
                    return;
                }
                Err(error) => {
                    let (valid, tail) = bytes.split_at(error.valid_up_to());
                    // Utf8Error identifies the prefix already validated by from_utf8.
                    self.extend(unsafe { std::str::from_utf8_unchecked(valid) });
                    (tail, error.error_len())
                }
            };
            let (invalid, mut width) = tail;
            if matches!(invalid, [0xed, 0xa0..=0xbf, ..]) {
                // The JVM groups an encoded surrogate into a single malformed sequence.
                width = match invalid.get(2) {
                    Some(0x80..=0xbf) => Some(3),
                    Some(_) => Some(2),
                    None => None,
                };
            }
            if let Some(width) = width {
                self.push(0xfffd);
                bytes = &invalid[width..];
            } else {
                if eof {
                    self.push(0xfffd);
                } else {
                    self.pending[..invalid.len()].copy_from_slice(invalid);
                    self.pending_len = invalid.len();
                }
                return;
            }
        }
    }

    pub(crate) fn read(
        &self,
        cursor: Option<i64>,
        budget: usize,
        output: &mut [u16],
    ) -> (usize, i64, bool) {
        let budget = budget.clamp(1, READ_UNITS);
        let len = self.units.len();
        let end = self.base + len as i64;
        let (start, stop, truncated) = if let Some(cursor) = cursor {
            let mut start = (cursor.clamp(self.base, end) - self.base) as usize;
            if start < len && low_surrogate(self.units[start]) {
                start += 1;
            }
            let mut stop = start;
            let mut bytes = 0;
            while stop < len {
                let (width, units) = utf8_width(self.units[stop]);
                if bytes + width > budget {
                    break;
                }
                bytes += width;
                stop += units;
            }
            (start, stop, cursor < self.base)
        } else {
            let mut start = len;
            let mut bytes = 0;
            while start > 0 {
                let unit = self.units[start - 1];
                let (width, units) = if low_surrogate(unit) {
                    (4, 2)
                } else {
                    utf8_width(unit)
                };
                if bytes + width > budget {
                    break;
                }
                bytes += width;
                start -= units;
            }
            (start, len, self.truncated() || start > 0)
        };
        for (dst, &unit) in output.iter_mut().zip(self.units.range(start..stop)) {
            *dst = unit;
        }
        (stop - start, self.base + stop as i64, truncated)
    }
}

fn low_surrogate(unit: u16) -> bool {
    (0xdc00..=0xdfff).contains(&unit)
}

fn utf8_width(unit: u16) -> (usize, usize) {
    match unit {
        0..=0x7f => (1, 1),
        0x80..=0x7ff => (2, 1),
        0xd800..=0xdbff => (4, 2),
        _ => (3, 1),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn read(ring: &TerminalOutput, cursor: Option<i64>, budget: usize) -> (String, i64, bool) {
        let mut output = [0; READ_UNITS];
        let (len, cursor, truncated) = ring.read(cursor, budget, &mut output);
        (
            String::from_utf16(&output[..len]).unwrap(),
            cursor,
            truncated,
        )
    }

    #[test]
    fn split_utf8_and_malformed_eof_keep_the_original_cursor_contract() {
        let mut ring = TerminalOutput::new(HISTORY_UNITS);
        for byte in "a中🙂".bytes().chain([0xed, 0xa0, 0x80, 0xe4, 0xb8]) {
            ring.append(&[byte], false);
        }
        assert_eq!(read(&ring, None, 64), ("a中🙂�".into(), 5, false));
        assert_eq!(read(&ring, Some(3), 8), ("�".into(), 5, false));
        ring.append(&[], true);
        assert_eq!(read(&ring, Some(5), 3), ("�".into(), 6, false));
    }

    #[test]
    fn eviction_wrap_clear_and_byte_budgets_preserve_character_boundaries() {
        let mut ring = TerminalOutput::new(6);
        ring.append("🙂甲乙z".as_bytes(), false);
        ring.append("a字".as_bytes(), false);
        assert_eq!(read(&ring, Some(0), 6), ("甲乙".into(), 4, true));
        assert_eq!(read(&ring, None, 4), ("a字".into(), 7, true));
        ring.clear();
        ring.append("🙂x".as_bytes(), false);
        assert_eq!(read(&ring, Some(7), 3), ("".into(), 7, false));
        assert_eq!(read(&ring, Some(7), 5), ("🙂x".into(), 10, false));
        assert_eq!(read(&ring, Some(8), 1), ("x".into(), 10, false));
    }

    #[test]
    fn reads_a_large_history_in_order_after_wrapping() {
        let mut ring = TerminalOutput::new(HISTORY_UNITS);
        let line = "abc\n";
        let text = line.repeat(100_000);
        for chunk in text.as_bytes().chunks(4096) {
            ring.append(chunk, false);
        }
        let start = text.len() - HISTORY_UNITS;
        let mut cursor = start as i64;
        let mut result = String::new();
        while cursor < text.len() as i64 {
            let (piece, next, truncated) = read(&ring, Some(cursor), READ_UNITS);
            assert!(!truncated);
            result.push_str(&piece);
            cursor = next;
        }
        assert_eq!(result, text[start..]);
    }
}
