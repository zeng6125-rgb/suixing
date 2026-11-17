// SPDX-License-Identifier: Apache-2.0
// Termux v0.118.3; see THIRD_PARTY_NOTICES.md.

pub(super) const FOREGROUND: usize = 256;
pub(super) const BACKGROUND: usize = 257;
pub(super) const CURSOR: usize = 258;
pub(super) const NORMAL: i64 = (256_i64 << 40) | (257_i64 << 16);

pub(super) fn encode_style(foreground: i32, background: i32, effect: i32) -> i64 {
    let mut result = (effect & 0x1ff) as u64;
    for (color, shift, flag) in [(foreground, 40, 1 << 9), (background, 16, 1 << 10)] {
        if color as u32 & 0xff00_0000 == 0xff00_0000 {
            result |= flag | ((color as u64 & 0xff_ffff) << shift);
        } else {
            result |= (color as u64 & 0x1ff) << shift;
        }
    }
    result as i64
}

pub(super) fn foreground(style: i64) -> i32 {
    if style & (1 << 9) == 0 {
        ((style as u64 >> 40) & 0x1ff) as i32
    } else {
        (0xff00_0000 | ((style as u64 >> 40) & 0xff_ffff)) as i32
    }
}

pub(super) fn background(style: i64) -> i32 {
    if style & (1 << 10) == 0 {
        ((style as u64 >> 16) & 0x1ff) as i32
    } else {
        (0xff00_0000 | ((style as u64 >> 16) & 0xff_ffff)) as i32
    }
}

pub(super) struct Colors {
    pub defaults: [i32; 259],
    pub current: [i32; 259],
}

impl Colors {
    pub fn new(defaults: [i32; 259]) -> Self {
        Self {
            defaults,
            current: defaults,
        }
    }

    pub fn reset(&mut self) {
        self.current = self.defaults;
    }

    pub fn reset_index(&mut self, index: i32) {
        self.current[index as usize] = self.defaults[index as usize];
    }

    pub fn parse_color(&mut self, index: i32, text: &[u16]) {
        if let Some(color) = parse(text) {
            self.current[index as usize] = color;
        }
    }
}

pub(super) fn parse(text: &[u16]) -> Option<i32> {
    let text = String::from_utf16(text).ok()?;
    let (initial, separator) = if text.starts_with('#') {
        (1, 0)
    } else if text.starts_with("rgb:") {
        (4, 1)
    } else {
        return None;
    };
    let count = text.len().checked_sub(initial + separator * 2)?;
    if count % 3 != 0 {
        return None;
    }
    let length = count / 3;
    let multiplier = 255.0 / (2_f64.powi((length * 4) as i32) - 1.0);
    let mut color = 0xff00_0000_u32;
    for (part, shift) in [(0, 16), (1, 8), (2, 0)] {
        let from = initial + part * (length + separator);
        let component = i32::from_str_radix(text.get(from..from + length)?, 16).ok()?;
        color |= ((component as f64 * multiplier) as i32 as u32) << shift;
    }
    Some(color as i32)
}
