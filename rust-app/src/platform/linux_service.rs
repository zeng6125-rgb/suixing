//! Independent foreground lease for explicitly started long-running operations.
use crate::linux::{Key, Manager};
use std::sync::Arc;
pub const SERVICE_CLASS: &str = "com.suixing.agent.rust.platform.RustLinuxService";
pub const ACTION_REFRESH: &str = "com.suixing.agent.rust.action.LINUX_REFRESH";
pub const ACTION_STOP: &str = "com.suixing.agent.rust.action.LINUX_STOP";
pub const ACTION_OPEN: &str = "com.suixing.agent.rust.action.LINUX_OPEN";
#[derive(Clone, Debug)]
pub enum LinuxServiceEvent {
    Initialized,
    ForegroundRequested,
    ForegroundConfirmed,
    StopRequested,
    Stopped,
    CancelRequested { key: Key, accepted: bool },
    ProtectionLost { keys: Vec<Key> },
    NotificationRestricted,
    Failed(String),
}
#[derive(Clone, Debug, Default)]
pub struct LinuxServiceStatus {
    pub initialized: bool,
    pub foreground: bool,
    pub notifications_allowed: Option<bool>,
    pub last_error: Option<String>,
}
pub(crate) fn validate_key(key: &Key) -> Result<(), String> {
    key.validate()
}
pub(crate) fn tag(key: &Key) -> String {
    use sha2::{Digest, Sha256};
    let mut hash = Sha256::new();
    hash.update(key.process_token.as_bytes());
    hash.update([0]);
    hash.update(key.id.as_bytes());
    hash.update([0]);
    hash.update(key.generation.to_be_bytes());
    format!("{:x}", hash.finalize())
}
pub(crate) fn stop_uri(key: &Key) -> String {
    format!("jagent-linux://stop/{}", tag(key))
}
pub(crate) fn refresh_uri(key: &Key) -> String {
    format!("jagent-linux://refresh/{}", tag(key))
}
#[cfg(target_os = "android")]
mod device;
#[cfg(target_os = "android")]
mod framework;
#[cfg(target_os = "android")]
pub use device::{drain_events, handle_service, request_refresh, start, status};
#[cfg(not(target_os = "android"))]
pub fn start(_operations: Arc<Manager>) -> Result<(), String> {
    Err("当前平台没有AndroidLinux操作前台服务".into())
}
