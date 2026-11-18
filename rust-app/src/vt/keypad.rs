//! Only real Android numpad key codes enter this path. NumLock is the event's
//! META_NUM_LOCK_ON fact, never a guessed toggle or a top-row digit heuristic.
use super::{Input, Key, Modes, Modifiers};

pub(super) fn encode_hardware(
    key: char,
    num_lock: bool,
    modifiers: Modifiers,
    modes: Modes,
) -> Result<Vec<u8>, String> {
    let navigation = if !num_lock {
        match key {
            '0' => Some(Key::Insert),
            '1' => Some(Key::End),
            '2' => Some(Key::Down),
            '3' => Some(Key::PageDown),
            '4' => Some(Key::Left),
            '6' => Some(Key::Right),
            '7' => Some(Key::Home),
            '8' => Some(Key::Up),
            '9' => Some(Key::PageUp),
            '.' => Some(Key::Delete),
            _ => None,
        }
    } else {
        None
    };
    if let Some(key) = navigation {
        // The original Termux numpad PgUp/PgDn branch emits fixed CSI 5~/6~.
        let modifiers = if matches!(key, Key::PageUp | Key::PageDown) {
            Modifiers::default()
        } else {
            modifiers
        };
        return super::encode_input(
            &Input::Key {
                key,
                modifiers,
                toolbar: false,
            },
            modes,
        );
    }
    if key == '.' && modes.application_keypad {
        return Ok(b"\x1bOn".to_vec());
    }
    super::encode_input(
        &Input::Key {
            key: Key::Keypad(key),
            modifiers,
            toolbar: false,
        },
        modes,
    )
}
