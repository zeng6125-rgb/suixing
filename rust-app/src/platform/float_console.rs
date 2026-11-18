//! Floating console rendering/inputs consume bounded actor-owned projections.
//! Application foreground always hides the window. Native framework callbacks
//! carry exact run/operation identities and never own persistence or approvals.
use crate::{
    runtime_host::{RunKey, RuntimeHost},
    state::{Event, SettingsStamp},
};
use std::sync::Arc;
pub const SERVICE_CLASS: &str = "com.suixing.agent.rust.platform.RustFloatConsoleService";
pub const ACTION_REFRESH: &str = "com.suixing.agent.rust.action.FLOAT_CONSOLE_REFRESH";
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Run {
    pub key: RunKey,
    pub title: String,
    pub state: String,
    pub revision: u64,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Snapshot {
    pub stamp: SettingsStamp,
    pub resident: bool,
    pub while_running: bool,
    pub width_dp: i32,
    pub height_dp: i32,
    pub theme: i32,
    pub font_scale: i32,
    pub immediately: bool,
    pub runs: Vec<Run>,
    pub selected: Option<RunKey>,
    pub conversation: Option<String>,
    pub title: String,
    pub preview: Arc<String>,
}
#[derive(Clone, Debug)]
pub(crate) struct Resize {
    pub request_id: String,
    pub expected: SettingsStamp,
    pub width_dp: i32,
    pub height_dp: i32,
}
#[cfg(target_os = "android")]
mod device;
#[cfg(target_os = "android")]
pub(crate) use device::{
    accessibility, configure, conversation, draw, event, handle_service, selection, start,
    take_errors, take_resizes, touch,
};
#[cfg(not(target_os = "android"))]
pub(crate) fn start(_host: Arc<RuntimeHost>) -> Result<(), String> {
    Ok(())
}
#[cfg(not(target_os = "android"))]
pub(crate) fn configure(_snapshot: Snapshot) -> Result<(), String> {
    Ok(())
}
#[cfg(not(target_os = "android"))]
pub(crate) fn event(_event: &Event) {}
#[cfg(not(target_os = "android"))]
pub(crate) fn selection() -> Option<Option<RunKey>> {
    None
}
#[cfg(not(target_os = "android"))]
pub(crate) fn conversation() -> Option<String> {
    None
}
#[cfg(not(target_os = "android"))]
pub(crate) fn take_resizes() -> Vec<Resize> {
    Vec::new()
}
#[cfg(not(target_os = "android"))]
pub(crate) fn take_errors() -> Vec<String> {
    Vec::new()
}
