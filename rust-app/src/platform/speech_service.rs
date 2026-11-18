//! Explicit reply-audio leases confirmed by an independent mediaPlayback FGS.
//! A lease never grants microphone or speech-recognition permission.
use super::permissions::OperationKey;
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};

pub const SERVICE_CLASS: &str = "com.suixing.agent.rust.platform.RustSpeechService";
pub const ACTION_START: &str = "com.suixing.agent.rust.action.SPEECH_START";
pub const ACTION_STOP: &str = "com.suixing.agent.rust.action.SPEECH_STOP";
pub const ACTION_OPEN: &str = "com.suixing.agent.rust.action.SPEECH_OPEN";

pub struct Lease {
    operation: OperationKey,
    guard: Arc<AtomicBool>,
    #[cfg(target_os = "android")]
    token: u64,
}
impl Lease {
    pub fn guard(&self) -> Arc<AtomicBool> {
        self.guard.clone()
    }
    pub fn operation(&self) -> &OperationKey {
        &self.operation
    }
    pub fn confirmed(&self) -> bool {
        self.guard.load(Ordering::Acquire)
    }
}
impl Drop for Lease {
    fn drop(&mut self) {
        self.guard.store(false, Ordering::Release);
        #[cfg(target_os = "android")]
        device::release(self.token);
    }
}
#[derive(Clone, Debug)]
pub enum SpeechServiceEvent {
    Ready {
        operation: OperationKey,
        guard: Arc<AtomicBool>,
    },
    Failed {
        operation: Option<OperationKey>,
        error: String,
    },
    Released {
        operation: OperationKey,
    },
    NotificationRestricted,
}
#[derive(Clone, Debug, Default)]
pub struct SpeechServiceStatus {
    pub initialized: bool,
    pub foreground: bool,
    pub active_leases: usize,
    pub notifications_allowed: Option<bool>,
    pub last_error: Option<String>,
}
#[cfg(target_os = "android")]
mod device;
#[cfg(target_os = "android")]
mod framework;
#[cfg(target_os = "android")]
pub use device::{drain_events, handle_service, request, status, stop_all};
#[cfg(not(target_os = "android"))]
pub fn request(operation: OperationKey, opted_in: bool) -> Result<Lease, String> {
    operation.validate()?;
    let _ = opted_in;
    Err("当前平台没有Android语音前台服务".into())
}
#[cfg(not(target_os = "android"))]
pub fn drain_events(_limit: usize) -> Vec<SpeechServiceEvent> {
    Vec::new()
}
#[cfg(not(target_os = "android"))]
pub fn stop_all() {}
#[cfg(not(target_os = "android"))]
pub fn status() -> SpeechServiceStatus {
    SpeechServiceStatus::default()
}
