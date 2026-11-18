//! Independent foreground lease for explicitly started long-running jobs.
use crate::jobs::{JobKey, Manager};
use std::sync::Arc;
pub const SERVICE_CLASS: &str = "com.suixing.agent.rust.platform.RustJobService";
pub const ACTION_REFRESH: &str = "com.suixing.agent.rust.action.JOB_REFRESH";
pub const ACTION_STOP: &str = "com.suixing.agent.rust.action.JOB_STOP";
pub const ACTION_OPEN: &str = "com.suixing.agent.rust.action.JOB_OPEN";
#[derive(Clone, Debug)]
pub enum JobServiceEvent {
    Initialized,
    ForegroundRequested,
    ForegroundConfirmed,
    StopRequested,
    Stopped,
    CancelRequested { key: JobKey, accepted: bool },
    ProtectionLost { keys: Vec<JobKey> },
    NotificationRestricted,
    Failed(String),
}
#[derive(Clone, Debug, Default)]
pub struct JobServiceStatus {
    pub initialized: bool,
    pub foreground: bool,
    pub notifications_allowed: Option<bool>,
    pub last_error: Option<String>,
}
pub(crate) fn validate_key(key: &JobKey) -> Result<(), String> {
    key.validate()
}
pub(crate) fn tag(key: &JobKey) -> String {
    use sha2::{Digest, Sha256};
    let mut hash = Sha256::new();
    hash.update(key.process_token.as_bytes());
    hash.update([0]);
    hash.update(key.id.as_bytes());
    format!("{:x}", hash.finalize())
}
pub(crate) fn stop_uri(key: &JobKey) -> String {
    format!("jagent-job://stop/{}", tag(key))
}
#[cfg(target_os = "android")]
mod device;
#[cfg(target_os = "android")]
mod framework;
#[cfg(target_os = "android")]
pub use device::{drain_events, handle_service, request_refresh, start, status};
#[cfg(not(target_os = "android"))]
pub fn start(_jobs: Arc<Manager>) -> Result<(), String> {
    Err("当前平台没有Android作业前台服务".into())
}
