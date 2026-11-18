//! In-process exclusion between frozen Linux launches and rootfs publication.
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{Arc, Mutex, OnceLock},
};
#[derive(Default)]
struct State {
    readers: usize,
    writer: bool,
    uncertain: bool,
}
static REGISTRY: OnceLock<Mutex<HashMap<PathBuf, State>>> = OnceLock::new();
#[derive(Debug)]
pub(crate) struct UseLease {
    path: PathBuf,
}
pub(crate) struct MutationLease {
    path: PathBuf,
}
/// Construct after a process really starts. Failed or panicking cleanup cannot
/// release a distro for publication while its processes remain unknown.
pub(crate) struct ExecutionLease {
    lease: Option<Arc<UseLease>>,
    confirmed: bool,
}
impl ExecutionLease {
    pub(crate) fn new(lease: Option<Arc<UseLease>>) -> Self {
        Self {
            lease,
            confirmed: false,
        }
    }
    pub(crate) fn confirmed(&mut self) {
        self.confirmed = true;
    }
}
impl Drop for ExecutionLease {
    fn drop(&mut self) {
        if !self.confirmed
            && let Some(lease) = &self.lease
        {
            let mut registry = REGISTRY
                .get_or_init(Default::default)
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            registry.entry(lease.path.clone()).or_default().uncertain = true;
        }
    }
}
pub(crate) fn read(path: PathBuf) -> Result<Arc<UseLease>, String> {
    let mut registry = REGISTRY
        .get_or_init(Default::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let state = registry.entry(path.clone()).or_default();
    if state.writer {
        return Err("此Linux环境正在变更，启动未执行".into());
    }
    state.readers = state
        .readers
        .checked_add(1)
        .ok_or("Linux使用租约预算耗尽")?;
    Ok(Arc::new(UseLease { path }))
}
pub(crate) fn write(path: PathBuf) -> Result<MutationLease, String> {
    let mut registry = REGISTRY
        .get_or_init(Default::default)
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let state = registry.entry(path.clone()).or_default();
    if state.uncertain {
        return Err("此Linux环境仍有未确认清理的进程；当前进程不能安全发布或移除rootfs".into());
    }
    if state.writer || state.readers > 0 {
        return Err("此Linux环境仍由终端或作业使用，请先停止所属进程".into());
    }
    state.writer = true;
    Ok(MutationLease { path })
}
impl Drop for UseLease {
    fn drop(&mut self) {
        if let Some(registry) = REGISTRY.get() {
            let mut registry = registry.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(state) = registry.get_mut(&self.path) {
                state.readers = state.readers.saturating_sub(1);
                if state.readers == 0 && !state.writer && !state.uncertain {
                    registry.remove(&self.path);
                }
            }
        }
    }
}
impl Drop for MutationLease {
    fn drop(&mut self) {
        if let Some(registry) = REGISTRY.get() {
            let mut registry = registry.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(state) = registry.get_mut(&self.path) {
                state.writer = false;
                if state.readers == 0 && !state.uncertain {
                    registry.remove(&self.path);
                }
            }
        }
    }
}
