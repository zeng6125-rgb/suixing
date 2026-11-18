//! Foreground protection for explicitly started interactive terminals. Session
//! cancellation is separate from Agent RunKey cancellation and never starts a PTY.
use crate::runtime_host::{RuntimeHost, TerminalKey};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};

/// Only the real service manager constructs this after the current instance is
/// foreground and the exact process/session claim appears in its snapshot.
pub struct ForegroundGuard {
    key: TerminalKey,
    epoch: u64,
    valid: Arc<AtomicBool>,
}
impl ForegroundGuard {
    pub fn key(&self) -> &TerminalKey {
        &self.key
    }
    pub fn epoch(&self) -> u64 {
        self.epoch
    }
    pub fn confirmed(&self) -> bool {
        self.valid.load(Ordering::Acquire)
    }
}

pub const SERVICE_CLASS: &str = "com.suixing.agent.rust.platform.RustTerminalService";
pub const ACTION_REFRESH: &str = "com.suixing.agent.rust.action.TERMINAL_REFRESH";
pub const ACTION_STOP: &str = "com.suixing.agent.rust.action.TERMINAL_STOP";
pub const ACTION_OPEN: &str = "com.suixing.agent.rust.action.TERMINAL_OPEN";

#[derive(Clone, Debug)]
pub enum TerminalServiceEvent {
    Initialized,
    ForegroundRequested,
    ForegroundConfirmed,
    StopRequested,
    Stopped,
    CancelRequested { key: TerminalKey, accepted: bool },
    ProtectionLost { keys: Vec<TerminalKey> },
    NotificationRestricted,
    Failed(String),
}
#[derive(Clone, Debug, Default)]
pub struct TerminalServiceStatus {
    pub initialized: bool,
    pub foreground: bool,
    pub notifications_allowed: Option<bool>,
    pub last_error: Option<String>,
}
pub(crate) fn validate_key(key: &TerminalKey) -> Result<(), String> {
    if key.session_id == 0
        || key.process_token.is_empty()
        || key.process_token.len() > 128
        || !key
            .process_token
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
    {
        Err("终端身份无效".into())
    } else {
        Ok(())
    }
}
pub(crate) fn tag(key: &TerminalKey) -> String {
    use sha2::{Digest, Sha256};
    let mut digest = Sha256::new();
    digest.update(key.process_token.as_bytes());
    digest.update([0]);
    digest.update(key.session_id.to_be_bytes());
    format!("{:x}", digest.finalize())
}
pub(crate) fn stop_uri(key: &TerminalKey) -> String {
    format!("jagent-terminal://stop/{}", tag(key))
}

#[cfg(target_os = "android")]
mod device;
#[cfg(target_os = "android")]
mod framework;
#[cfg(target_os = "android")]
pub use device::{await_ready, drain_events, handle_service, request_refresh, start, status};
#[cfg(not(target_os = "android"))]
pub fn await_ready(
    _key: &TerminalKey,
    _cancel: impl Fn() -> bool,
) -> Result<ForegroundGuard, String> {
    Err("当前平台没有Android终端前台保护确认".into())
}
#[cfg(not(target_os = "android"))]
pub fn start(_host: Arc<RuntimeHost>) -> Result<(), String> {
    Err("当前平台没有 Android 终端前台服务".into())
}
