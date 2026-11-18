//! Shizuku 13 Binder protocol, implemented in Rust. The generated JVM classes
//! carry only Binder/Parcelable callbacks. Connection, request ownership and
//! permission verification belong to this module, never an Intent package name.
use super::permissions::OperationKey;
use std::{
    collections::VecDeque,
    sync::{Mutex, OnceLock},
};

pub const MANAGER_PACKAGE: &str = "moe.shizuku.privileged.api";
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Backend {
    Shizuku,
    Sui,
}
#[derive(Clone, Debug, Default)]
pub struct Status {
    pub installed: bool,
    pub connecting: bool,
    pub connected: bool,
    pub generation: u64,
    pub server_uid: Option<i32>,
    pub server_version: Option<i32>,
    pub backend: Option<Backend>,
    pub sui_probe_error: Option<String>,
    /// Last explicit query only. Every privileged invocation queries again.
    pub granted: bool,
    pub settings_required: bool,
    pub permission_pending: bool,
    pub error: Option<String>,
}
#[derive(Debug)]
pub struct PermissionResult {
    pub operation: OperationKey,
    pub granted: bool,
    pub cancelled: bool,
    pub error: Option<String>,
}
#[derive(Default)]
struct Shared {
    status: Status,
    results: VecDeque<PermissionResult>,
}
static SHARED: OnceLock<Mutex<Shared>> = OnceLock::new();
fn shared() -> &'static Mutex<Shared> {
    SHARED.get_or_init(|| Mutex::new(Shared::default()))
}
pub fn status() -> Status {
    shared()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .status
        .clone()
}
pub fn drain_results(limit: usize) -> Vec<PermissionResult> {
    let mut state = shared().lock().unwrap_or_else(|e| e.into_inner());
    let count = limit.min(state.results.len());
    state.results.drain(..count).collect()
}
#[cfg(target_os = "android")]
mod protocol;
#[cfg(target_os = "android")]
pub use protocol::{
    RemoteProcess, cancel_permission, on_binder_dead, on_client_transaction, provider_created,
    receive_binder, refresh, request_permission, retry_cleanup, spawn, spawn_frozen,
};
#[cfg(not(target_os = "android"))]
pub fn refresh() -> Result<Status, String> {
    Err("当前平台没有Shizuku Binder后端".into())
}
#[cfg(not(target_os = "android"))]
pub fn request_permission(_operation: OperationKey) -> Result<(), String> {
    Err("当前平台没有Shizuku授权入口".into())
}
#[cfg(not(target_os = "android"))]
pub fn cancel_permission(_id: &str) {}
