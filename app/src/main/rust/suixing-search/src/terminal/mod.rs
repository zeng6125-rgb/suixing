// SPDX-License-Identifier: Apache-2.0
// Termux v0.118.3; see THIRD_PARTY_NOTICES.md.

mod buffer;
mod colors;
mod emulator;
mod row;
#[cfg(test)]
mod tests;
mod width;

use crate::SearchBuffer;
use emulator::Emulator;
use std::ffi::c_void;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::{ptr, slice};

#[repr(C)]
pub struct TerminalText {
    pub data: *mut u16,
    pub len: usize,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct TerminalCallbacks {
    pub context: *mut c_void,
    pub types: unsafe extern "C" fn(*mut c_void, *const i32, usize, *mut u8) -> i32,
    pub category: unsafe extern "C" fn(*mut c_void, i32) -> i32,
    pub termcap:
        unsafe extern "C" fn(*mut c_void, *const u16, usize, bool, bool, *mut TerminalText) -> i32,
}

fn bytes(output: &mut SearchBuffer, data: Vec<u8>) {
    let data = data.into_boxed_slice();
    output.len = data.len();
    output.data = Box::into_raw(data).cast();
}

fn put32(bytes: &mut Vec<u8>, value: i32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn put_terminal_characters(bytes: &mut Vec<u8>, units: &[u16]) {
    #[cfg(target_endian = "little")]
    // The row wire format uses little-endian UTF-16 units.
    bytes.extend_from_slice(unsafe {
        slice::from_raw_parts(units.as_ptr().cast(), std::mem::size_of_val(units))
    });
    #[cfg(target_endian = "big")]
    bytes.extend(units.iter().flat_map(|unit| unit.to_le_bytes()));
}

fn put_terminal_styles(bytes: &mut Vec<u8>, styles: &[i64]) {
    #[cfg(target_endian = "little")]
    // Each style is a little-endian i64 with no padding.
    bytes.extend_from_slice(unsafe {
        slice::from_raw_parts(styles.as_ptr().cast(), std::mem::size_of_val(styles))
    });
    #[cfg(target_endian = "big")]
    bytes.extend(styles.iter().flat_map(|style| style.to_le_bytes()));
}

fn text(bytes: &mut Vec<u8>, value: Option<&[u16]>) {
    put32(bytes, value.map_or(-1, |text| text.len() as i32));
    if let Some(value) = value {
        for unit in value {
            bytes.extend_from_slice(&unit.to_le_bytes());
        }
    }
}

fn state(emulator: &mut Emulator, force_palette: bool) -> Vec<u8> {
    // Palette changes already notify Java through the color event.
    let include_palette = force_palette || emulator.events.iter().any(|event| event.kind == 3);
    let mut bytes = Vec::with_capacity(if include_palette { 1120 } else { 80 });
    for field in [
        emulator.rows,
        emulator.columns,
        emulator.cursor_row,
        emulator.cursor_col,
        emulator.cursor_style,
        emulator.flags,
        emulator.scroll_counter,
        emulator.cell_width,
        emulator.cell_height,
        i32::from(emulator.alternate),
    ] {
        put32(&mut bytes, field);
    }
    for buffer in [&emulator.main, &emulator.alt] {
        for field in [
            buffer.columns,
            buffer.screen_rows,
            buffer.total_rows,
            buffer.transcript_rows,
        ] {
            put32(&mut bytes, field);
        }
    }
    put32(&mut bytes, i32::from(include_palette));
    if include_palette {
        for color in emulator.colors.current {
            put32(&mut bytes, color);
        }
    }
    put32(&mut bytes, emulator.events.len() as i32);
    for event in emulator.events.drain(..) {
        put32(&mut bytes, event.kind);
        text(&mut bytes, event.text.as_deref());
        text(&mut bytes, event.previous.as_deref());
    }
    bytes
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_terminal_create(
    settings: *const i32,
    palette: *const i32,
) -> *mut c_void {
    catch_unwind(|| {
        let settings = unsafe { slice::from_raw_parts(settings, 6) };
        let palette: [i32; 259] = unsafe { slice::from_raw_parts(palette, 259) }
            .try_into()
            .unwrap();
        Box::into_raw(Box::new(Emulator::new(
            settings[0],
            settings[1],
            settings[2],
            settings[3],
            settings[4],
            settings[5],
            palette,
        )))
        .cast()
    })
    .unwrap_or(ptr::null_mut())
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_terminal_release(handle: *mut c_void) {
    unsafe {
        drop(Box::from_raw(handle.cast::<Emulator>()));
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_terminal_step(
    handle: *mut c_void,
    op: i32,
    input: *const u8,
    length: usize,
    args: *const i32,
    colors: *const i32,
    callbacks: TerminalCallbacks,
    output: *mut SearchBuffer,
) -> i32 {
    let emulator = unsafe { &mut *handle.cast::<Emulator>() };
    emulator.callbacks = Some(callbacks);
    let result = catch_unwind(AssertUnwindSafe(|| {
        let input = if length == 0 {
            &[][..]
        } else {
            unsafe { slice::from_raw_parts(input, length) }
        };
        let args = unsafe { slice::from_raw_parts(args, 4) };
        let colors = unsafe { slice::from_raw_parts(colors, 3) };
        emulator.colors.current[colors::FOREGROUND..=colors::CURSOR].copy_from_slice(colors);
        match op {
            0 => emulator.append(input),
            1 => emulator.resize(args[0], args[1], args[2], args[3]),
            2 => emulator.clear_screen(),
            3 => emulator.reset(),
            4 => emulator.process_code_point(args[0]),
            5 => emulator.dec_set(args[0] != 0, args[1]),
            6 => emulator.scroll_counter = 0,
            7 => emulator.set_cursor_style(args[0]),
            10 => emulator.mouse(args[0], args[1], args[2], args[3] != 0),
            _ => {}
        }
        bytes(unsafe { &mut *output }, state(emulator, op == 9));
    }));
    emulator.callbacks = None;
    if result.is_ok() { 0 } else { 1 }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_terminal_rows(
    handle: *mut c_void,
    alternate: bool,
    from: i32,
    count: i32,
    full: bool,
    output: *mut SearchBuffer,
) -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        let emulator = unsafe { &mut *handle.cast::<Emulator>() };
        let buffer = if alternate {
            &mut emulator.alt
        } else {
            &mut emulator.main
        };
        let mut rendered = std::mem::take(&mut buffer.rendered_rows);
        let full = full || rendered.len() != count as usize;
        rendered.truncate(count as usize);
        let mut rotation = 0;
        if !full && count > 0 {
            let scroll =
                (buffer.first_row + from - buffer.rendered_first_row).rem_euclid(buffer.total_rows);
            if scroll < count {
                rotation = scroll;
            } else if buffer.total_rows - scroll < count {
                rotation = count - (buffer.total_rows - scroll);
            }
            rendered.rotate_left(rotation as usize);
        }
        let mut result = vec![0; 4];
        put32(&mut result, rotation);
        let mut updates = 0_i32;
        for (index, external) in (from..from + count).enumerate() {
            let row = buffer.line(external);
            if let Some(previous) = rendered.get_mut(index) {
                if !full && row.same_rendering(previous) {
                    continue;
                }
                previous.clone_from(row);
            } else {
                rendered.push(row.clone());
            }
            updates += 1;
            for field in [
                external,
                row.used as i32,
                i32::from(row.wrap),
                i32::from(row.complex),
            ] {
                put32(&mut result, field);
            }
            put_terminal_characters(&mut result, &row.text[..row.used]);
            put32(&mut result, i32::from(!row.styles.is_empty()));
            if row.styles.is_empty() {
                result.extend_from_slice(&row.uniform_style.to_le_bytes());
            } else {
                put_terminal_styles(&mut result, &row.styles);
            }
            let widths_start = result.len();
            result.resize(widths_start + row.used, if row.complex { 0 } else { 1 });
            if row.complex {
                let mut index = 0;
                while index < row.used {
                    let cp = width::code_point(&row.text, index);
                    result[widths_start + index] = width::width(cp) as u8;
                    index += width::code_point_len(cp);
                }
            }
        }
        buffer.rendered_rows = rendered;
        buffer.rendered_first_row = buffer.first_row + from;
        result[..4].copy_from_slice(&updates.to_le_bytes());
        bytes(unsafe { &mut *output }, result);
    }))
    .map_or(1, |_| 0)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_terminal_selected(
    handle: *mut c_void,
    alternate: bool,
    coords: *const i32,
    joined: bool,
    full: bool,
    output: *mut SearchBuffer,
) -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        let emulator = unsafe { &*handle.cast::<Emulator>() };
        let buffer = if alternate {
            &emulator.alt
        } else {
            &emulator.main
        };
        let coords = unsafe { slice::from_raw_parts(coords, 4) };
        let value = buffer.selected(coords[0], coords[1], coords[2], coords[3], joined, full);
        bytes(
            unsafe { &mut *output },
            value.into_iter().flat_map(u16::to_le_bytes).collect(),
        );
    }))
    .map_or(1, |_| 0)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_terminal_word(
    handle: *mut c_void,
    alternate: bool,
    x: i32,
    y: i32,
    output: *mut SearchBuffer,
) -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        let emulator = unsafe { &*handle.cast::<Emulator>() };
        let buffer = if alternate {
            &emulator.alt
        } else {
            &emulator.main
        };
        bytes(
            unsafe { &mut *output },
            buffer
                .word(x, y)
                .into_iter()
                .flat_map(u16::to_le_bytes)
                .collect(),
        );
    }))
    .map_or(1, |_| 0)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_terminal_valid_cursor(
    handle: *mut c_void,
    alternate: bool,
    x: i32,
    y: i32,
) -> i32 {
    let emulator = unsafe { &*handle.cast::<Emulator>() };
    let buffer = if alternate {
        &emulator.alt
    } else {
        &emulator.main
    };
    buffer.valid_cursor_x(x, y)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_terminal_paste(
    handle: *mut c_void,
    data: *const u16,
    len: usize,
    output: *mut SearchBuffer,
) -> i32 {
    catch_unwind(AssertUnwindSafe(|| {
        let emulator = unsafe { &mut *handle.cast::<Emulator>() };
        emulator.paste(unsafe { slice::from_raw_parts(data, len) });
        // Paste does not take the Java theme colors supplied by step.
        bytes(unsafe { &mut *output }, state(emulator, true));
    }))
    .map_or(1, |_| 0)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_terminal_memory(handle: *mut c_void, output: *mut i64) {
    let emulator = unsafe { &*handle.cast::<Emulator>() };
    let mut memory = [0_i64; 3];
    for buffer in [&emulator.main, &emulator.alt] {
        for row in buffer.lines.iter().flatten() {
            memory[0] += row.text.capacity() as i64 * 2;
            memory[1] += row.styles.capacity() as i64 * 8;
            memory[2] += 1;
        }
    }
    unsafe {
        ptr::copy_nonoverlapping(memory.as_ptr(), output, 3);
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_terminal_text_copy(
    data: *const u16,
    len: usize,
    output: *mut TerminalText,
) {
    let text = unsafe { slice::from_raw_parts(data, len) }
        .to_vec()
        .into_boxed_slice();
    unsafe {
        *output = TerminalText {
            len,
            data: Box::into_raw(text).cast(),
        };
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_terminal_parse_color(data: *const u16, len: usize) -> i32 {
    colors::parse(unsafe { slice::from_raw_parts(data, len) }).unwrap_or(0)
}
