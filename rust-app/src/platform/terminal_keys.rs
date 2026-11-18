//! Native keypad facts have one exact focused terminal owner. The bounded UI
//! queue cannot carry an event across Activity, focus, process or session epochs.
use crate::{
    runtime_host::TerminalKey,
    vt::{Input, Key, Modifiers},
};
use eframe::egui;
use std::{
    collections::{HashMap, VecDeque},
    sync::{Mutex, OnceLock},
};
const MAX_EVENTS: usize = 128;
const MAX_PRESSED: usize = 32;

#[derive(Clone, Copy)]
pub(super) struct Facts {
    pub key_code: i32,
    pub action: i32,
    pub meta_state: i32,
    pub flags: i32,
    pub device_id: i32,
    pub repeat: i32,
    pub down_time: i64,
    pub event_time: i64,
    pub source: i32,
}
#[derive(Clone)]
struct Owner {
    key: TerminalKey,
    epoch: u64,
    id: egui::Id,
    context: egui::Context,
}
struct Captured {
    key: TerminalKey,
    epoch: u64,
    facts: Facts,
}
#[derive(Default)]
struct State {
    epoch: u64,
    owner: Option<Owner>,
    events: VecDeque<Captured>,
    pressed: HashMap<(i32, i32), i64>,
}
static STATE: OnceLock<Mutex<State>> = OnceLock::new();
fn state() -> &'static Mutex<State> {
    STATE.get_or_init(|| Mutex::new(State::default()))
}
fn retire(state: &mut State) {
    state.owner = None;
    state.events.clear();
    state.pressed.clear();
}

pub(crate) struct FocusLease {
    key: TerminalKey,
    epoch: u64,
    id: egui::Id,
}
impl FocusLease {
    pub(crate) fn current(&self, key: &TerminalKey, id: egui::Id) -> bool {
        if &self.key != key || self.id != id {
            return false;
        }
        state()
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .owner
            .as_ref()
            .is_some_and(|owner| {
                owner.epoch == self.epoch && owner.key == self.key && owner.id == id
            })
    }
    pub(crate) fn drain(&self, limit: usize) -> Vec<Input> {
        let mut state = state().lock().unwrap_or_else(|error| error.into_inner());
        if !state
            .owner
            .as_ref()
            .is_some_and(|owner| owner.epoch == self.epoch && owner.key == self.key)
        {
            return Vec::new();
        }
        let count = limit.min(MAX_EVENTS).min(state.events.len());
        state
            .events
            .drain(..count)
            .filter(|event| event.epoch == self.epoch && event.key == self.key)
            .filter_map(|event| input(event.facts))
            .collect()
    }
}
impl Drop for FocusLease {
    fn drop(&mut self) {
        let mut state = state().lock().unwrap_or_else(|error| error.into_inner());
        if state
            .owner
            .as_ref()
            .is_some_and(|owner| owner.epoch == self.epoch && owner.key == self.key)
        {
            retire(&mut state);
        }
    }
}
pub(crate) fn bind(key: TerminalKey, id: egui::Id, context: egui::Context) -> Option<FocusLease> {
    key.validate().ok()?;
    let mut state = state().lock().unwrap_or_else(|error| error.into_inner());
    let epoch = state.epoch.checked_add(1)?;
    retire(&mut state);
    state.epoch = epoch;
    state.owner = Some(Owner {
        key: key.clone(),
        epoch,
        id,
        context,
    });
    Some(FocusLease { key, epoch, id })
}
pub(crate) fn invalidate() {
    let context = {
        let mut state = state().lock().unwrap_or_else(|error| error.into_inner());
        let context = state.owner.as_ref().map(|owner| owner.context.clone());
        retire(&mut state);
        context
    };
    if let Some(context) = context {
        context.request_repaint();
    }
}
fn keypad(key_code: i32) -> Option<char> {
    Some(match key_code {
        144..=153 => char::from(b'0' + (key_code - 144) as u8),
        154 => '/',
        155 => '*',
        156 => '-',
        157 => '+',
        158 => '.',
        159 => ',',
        160 => '\n',
        161 => '=',
        _ => return None,
    })
}
fn input(facts: Facts) -> Option<Input> {
    let modifiers = Modifiers {
        shift: facts.meta_state & 1 != 0,
        alt: facts.meta_state & 2 != 0,
        control: facts.meta_state & 4096 != 0,
    };
    if facts.key_code == 143 {
        Some(Input::Key {
            key: Key::NumLock,
            modifiers,
            toolbar: false,
        })
    } else {
        Some(Input::HardwareKeypad {
            key: keypad(facts.key_code)?,
            num_lock: facts.meta_state & 2097152 != 0,
            modifiers,
        })
    }
}
fn capture(facts: Facts) -> bool {
    if facts.flags & 2 != 0
        || facts.source & 0x101 != 0x101
        || facts.device_id < 0
        || !(0..=1).contains(&facts.action)
        || facts.repeat < 0
        || facts.down_time < 0
        || facts.event_time < facts.down_time
        || input(facts).is_none()
    {
        return false;
    }
    let mut state = state().lock().unwrap_or_else(|error| error.into_inner());
    let Some(owner) = state.owner.clone() else {
        return false;
    };
    if !owner.context.input(|input| input.focused)
        || !owner.context.memory(|memory| memory.has_focus(owner.id))
    {
        retire(&mut state);
        return false;
    }
    let physical = (facts.device_id, facts.key_code);
    if facts.action == 1 {
        return state.pressed.remove(&physical) == Some(facts.down_time);
    }
    if state.events.len() >= MAX_EVENTS
        || (!state.pressed.contains_key(&physical) && state.pressed.len() >= MAX_PRESSED)
    {
        state.pressed.remove(&physical);
        return false;
    }
    state.pressed.insert(physical, facts.down_time);
    state.events.push_back(Captured {
        key: owner.key,
        epoch: owner.epoch,
        facts,
    });
    drop(state);
    owner.context.request_repaint();
    true
}

#[cfg(target_os = "android")]
pub(super) fn dispatch(
    env: &mut jni::Env,
    activity: &jni::objects::JObject,
    event: &jni::objects::JObject,
) -> jni::errors::Result<bool> {
    use jni::{jni_sig, jni_str};
    let Some(current) = super::android::current_activity() else {
        return Ok(false);
    };
    if !env.is_same_object(current.as_ref(), activity)? {
        return Ok(false);
    }
    // The generated window-focus callback supplies null solely to revoke the
    // prior focus epoch, including a loss-and-regain without Activity pause.
    if event.is_null() {
        invalidate();
        return Ok(false);
    }
    if !super::android::activity_visible()
        || !env
            .call_method(activity, jni_str!("hasWindowFocus"), jni_sig!("()Z"), &[])?
            .z()?
    {
        invalidate();
        return Ok(false);
    }
    let key_code = env
        .call_method(event, jni_str!("getKeyCode"), jni_sig!("()I"), &[])?
        .i()?;
    if key_code != 143 && keypad(key_code).is_none() {
        return Ok(false);
    }
    let facts = Facts {
        key_code,
        action: env
            .call_method(event, jni_str!("getAction"), jni_sig!("()I"), &[])?
            .i()?,
        meta_state: env
            .call_method(event, jni_str!("getMetaState"), jni_sig!("()I"), &[])?
            .i()?,
        flags: env
            .call_method(event, jni_str!("getFlags"), jni_sig!("()I"), &[])?
            .i()?,
        device_id: env
            .call_method(event, jni_str!("getDeviceId"), jni_sig!("()I"), &[])?
            .i()?,
        repeat: env
            .call_method(event, jni_str!("getRepeatCount"), jni_sig!("()I"), &[])?
            .i()?,
        down_time: env
            .call_method(event, jni_str!("getDownTime"), jni_sig!("()J"), &[])?
            .j()?,
        event_time: env
            .call_method(event, jni_str!("getEventTime"), jni_sig!("()J"), &[])?
            .j()?,
        source: env
            .call_method(event, jni_str!("getSource"), jni_sig!("()I"), &[])?
            .i()?,
    };
    Ok(capture(facts))
}
