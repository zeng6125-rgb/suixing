use super::emulator::Emulator;
use super::{TerminalCallbacks, TerminalText};
use std::ffi::c_void;

unsafe extern "C" fn types(_: *mut c_void, _: *const i32, count: usize, output: *mut u8) -> i32 {
    unsafe {
        std::slice::from_raw_parts_mut(output, count).fill(5);
    }
    0
}
unsafe extern "C" fn category(_: *mut c_void, _: i32) -> i32 {
    5
}
unsafe extern "C" fn termcap(
    _: *mut c_void,
    _: *const u16,
    _: usize,
    _: bool,
    _: bool,
    _: *mut TerminalText,
) -> i32 {
    0
}

fn emulator() -> Emulator {
    let mut emulator = Emulator::new(80, 24, 1, 1, 2000, 0, [0; 259]);
    emulator.callbacks = Some(TerminalCallbacks {
        context: std::ptr::null_mut(),
        types,
        category,
        termcap,
    });
    emulator.events.clear();
    emulator
}

fn text(emulator: &Emulator) -> String {
    let screen = emulator.screen();
    String::from_utf16_lossy(&screen.selected(
        0,
        -screen.transcript_rows,
        screen.columns,
        screen.screen_rows,
        true,
        false,
    ))
    .trim()
    .to_owned()
}

fn writes(emulator: &Emulator) -> String {
    emulator
        .events
        .iter()
        .filter(|event| event.kind == 1)
        .map(|event| String::from_utf16_lossy(event.text.as_deref().unwrap()))
        .collect()
}

fn row_delta(emulator: &mut Emulator, from: i32, full: bool) -> (i32, i32) {
    let mut output = crate::SearchBuffer {
        data: std::ptr::null_mut(),
        len: 0,
    };
    // SAFETY: the emulator and output stay alive for the call; the returned buffer is freed below.
    assert_eq!(0, unsafe {
        super::sx_terminal_rows(
            (emulator as *mut Emulator).cast(),
            false,
            from,
            emulator.rows,
            full,
            &mut output,
        )
    });
    // SAFETY: sx_terminal_rows returned the initialized byte buffer and its length.
    let bytes = unsafe { std::slice::from_raw_parts(output.data, output.len) };
    let header = (
        i32::from_le_bytes(bytes[..4].try_into().unwrap()),
        i32::from_le_bytes(bytes[4..8].try_into().unwrap()),
    );
    // SAFETY: this buffer was allocated by sx_terminal_rows and is freed exactly once.
    unsafe { crate::sx_search_free(output.data, output.len) };
    header
}

#[test]
fn viewport_scrolling_reuses_rows_in_both_directions() {
    let mut emulator = emulator();
    for row in 0..80 {
        emulator.append(format!("entry {row}\r\n").as_bytes());
    }
    assert_eq!((24, 0), row_delta(&mut emulator, 0, true));
    assert_eq!((1, 23), row_delta(&mut emulator, -1, false));
    assert_eq!((1, 23), row_delta(&mut emulator, -2, false));
    assert_eq!((1, 1), row_delta(&mut emulator, -1, false));
    assert_eq!((1, 1), row_delta(&mut emulator, 0, false));
    assert_eq!((0, 0), row_delta(&mut emulator, 0, false));
    emulator.append(b"entry 80\r\n");
    assert_eq!((1, 0), row_delta(&mut emulator, -1, false));
}

#[test]
fn sgr_never_appears_as_literal_text() {
    let mut emulator = emulator();
    emulator.append(b"\x1b[31mred\x1b[0m plain");
    assert_eq!("red plain", text(&emulator));
}

#[test]
fn carriage_return_overwrites_without_appending() {
    let mut emulator = emulator();
    emulator.append(b"abcdef\rXY");
    assert_eq!("XYcdef", text(&emulator));
}

#[test]
fn clear_and_home_reset_the_screen() {
    let mut emulator = emulator();
    emulator.append(b"old\x1b[2J\x1b[Hnew");
    assert_eq!("new", text(&emulator));
}

#[test]
fn erase_line_removes_the_remainder() {
    let mut emulator = emulator();
    emulator.append(b"abcdef\rXY\x1b[K");
    assert_eq!("XY", text(&emulator));
}

#[test]
fn escape_and_utf8_can_be_split_at_every_boundary() {
    let mut emulator = emulator();
    for byte in "\x1b[32m中文🙂\x1b[0m".as_bytes() {
        emulator.append(&[*byte]);
    }
    assert_eq!("中文🙂", text(&emulator));
}

#[test]
fn alternate_screen_restores_the_shell_screen() {
    let mut emulator = emulator();
    emulator.append(b"shell\x1b[?1049hfull screen\x1b[?1049l");
    assert_eq!("shell", text(&emulator));
}

#[test]
fn cursor_position_and_backspace_redraw_the_existing_line() {
    let mut emulator = emulator();
    emulator.append(b"abc\x08X\x1b[1;1HY");
    assert_eq!("YbX", text(&emulator));
}

#[test]
fn osc52_cannot_touch_android_clipboard() {
    let mut emulator = emulator();
    emulator.append(b"\x1b]52;c;c2VjcmV0\x07ok");
    assert_eq!("ok", text(&emulator));
    assert!(
        emulator
            .events
            .iter()
            .all(|event| matches!(event.kind, 1..=8))
    );
}

#[test]
fn bracketed_paste_is_wrapped_and_does_not_send_escape_from_clipboard() {
    let mut emulator = emulator();
    emulator.append(b"\x1b[?2004h");
    emulator.paste(&"one\ntwo\x1b".encode_utf16().collect::<Vec<_>>());
    assert_eq!("\x1b[200~one\rtwo\x1b[201~", writes(&emulator));
}

#[test]
fn ordinary_paste_uses_no_bracketed_markers() {
    let mut emulator = emulator();
    emulator.paste(&"hello".encode_utf16().collect::<Vec<_>>());
    assert_eq!("hello", writes(&emulator));
}

#[test]
fn scrollback_is_bounded_while_keeping_recent_output() {
    let mut emulator = emulator();
    let input = (1..=4000)
        .map(|row| format!("row{row}"))
        .collect::<Vec<_>>()
        .join("\r\n");
    emulator.append(input.as_bytes());
    assert!(emulator.screen().transcript_rows <= 2000);
    let text = text(&emulator);
    assert!(text.contains("row4000"));
    assert!(!text.lines().any(|line| line == "row1"));
}

#[test]
fn toolbar_clear_erases_visible_text_and_history_without_disabling_bracketed_paste() {
    let mut emulator = emulator();
    let mut input = (1..=80)
        .map(|row| format!("row{row}"))
        .collect::<Vec<_>>()
        .join("\r\n");
    input.push_str("\x1b[?2004h");
    emulator.append(input.as_bytes());
    assert!(emulator.screen().transcript_rows > 0);
    emulator.clear_screen();
    assert_eq!("", text(&emulator));
    assert_eq!(0, emulator.screen().transcript_rows);
    assert_eq!((0, 0), (emulator.cursor_row, emulator.cursor_col));
    emulator.paste(&"one\ntwo".encode_utf16().collect::<Vec<_>>());
    assert_eq!("\x1b[200~one\rtwo\x1b[201~", writes(&emulator));
}
