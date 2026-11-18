//! Agent foreground protection. Initialization and start requests are not
//! foreground confirmations. Only successful framework promotion sets that
//! state; the process runtime continues independently of its UI observers.
use crate::runtime_host::{RunKey, RuntimeHost};
use std::sync::Arc;

pub const SERVICE_CLASS: &str = "com.suixing.agent.rust.platform.RustAgentService";
pub const ACTION_REFRESH: &str = "com.suixing.agent.rust.action.AGENT_SERVICE_REFRESH";
pub const ACTION_CANCEL: &str = "com.suixing.agent.rust.action.CANCEL_RUN";
pub const ACTION_OPEN: &str = "com.suixing.agent.rust.action.OPEN_RUN";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BackgroundEventKind {
    Initialized,
    ForegroundRequested,
    ForegroundConfirmed,
    StopRequested,
    Stopped,
    CancelRequested,
    NotificationRestricted,
    WakeLockAcquired,
    WakeLockReleased,
    Failed,
}

#[derive(Clone, Debug)]
pub struct BackgroundEvent {
    pub kind: BackgroundEventKind,
    pub key: Option<RunKey>,
    pub message: String,
}

#[derive(Clone, Debug, Default)]
pub struct BackgroundStatus {
    pub initialized: bool,
    pub foreground: bool,
    pub wake_lock_held: bool,
    /// A framework error prevented confirming the most recent lease change.
    pub wake_lock_uncertain: bool,
    pub notifications_allowed: Option<bool>,
    pub last_error: Option<String>,
}

#[cfg(target_os = "android")]
mod device;
#[cfg(target_os = "android")]
mod framework;
#[cfg(target_os = "android")]
pub use device::{drain_events, handle_service, request_refresh, start, status};

#[cfg(not(target_os = "android"))]
pub fn start(_host: Arc<RuntimeHost>) -> Result<(), String> {
    Err("当前平台没有 Android 前台服务".into())
}
#[cfg(not(target_os = "android"))]
pub fn drain_events(_limit: usize) -> Vec<BackgroundEvent> {
    Vec::new()
}
#[cfg(not(target_os = "android"))]
pub fn request_refresh() {}
#[cfg(not(target_os = "android"))]
pub fn status() -> BackgroundStatus {
    BackgroundStatus::default()
}

pub(crate) fn cancel_uri(key: &RunKey) -> String {
    format!("jagent-run://cancel/{}", notification_tag(key))
}

pub(crate) fn notification_tag(key: &RunKey) -> String {
    use sha2::{Digest, Sha256};
    let mut digest = Sha256::new();
    digest.update(key.conversation_id.as_bytes());
    digest.update([0]);
    digest.update(key.run_id.as_bytes());
    format!("{:x}", digest.finalize())
}
