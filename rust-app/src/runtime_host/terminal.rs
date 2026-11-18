//! Terminal ownership and stop signals are separate from chat run identities.
use super::{Publisher, RuntimeHost, StatusObserver, Wake, identifier, observer_id};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, Ordering},
    },
    time::{SystemTime, UNIX_EPOCH},
};
type Cancel = Arc<dyn Fn() + Send + Sync + 'static>;
const MAX_TERMINALS: usize = 4;
const MAX_OBSERVERS: usize = 8;
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct TerminalKey {
    pub process_token: String,
    pub session_id: u64,
}
impl TerminalKey {
    pub fn validate(&self) -> Result<(), String> {
        identifier(&self.process_token)?;
        if self.session_id == 0 {
            return Err("终端会话标识不能为空".into());
        }
        Ok(())
    }
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TerminalSnapshot {
    pub revision: u64,
    pub runtime_alive: bool,
    pub running: Vec<TerminalKey>,
}
impl Default for TerminalSnapshot {
    fn default() -> Self {
        Self {
            revision: 0,
            runtime_alive: true,
            running: Vec::new(),
        }
    }
}
struct Entry {
    cancel: Cancel,
    requested: bool,
}
#[derive(Default)]
struct Registry {
    entries: HashMap<TerminalKey, Entry>,
    observers: HashMap<u64, StatusObserver>,
    snapshot: TerminalSnapshot,
}
static REGISTRY: OnceLock<Arc<Mutex<Registry>>> = OnceLock::new();
static PROCESS_TOKEN: OnceLock<String> = OnceLock::new();
fn registry() -> Arc<Mutex<Registry>> {
    REGISTRY
        .get_or_init(|| Arc::new(Mutex::new(Registry::default())))
        .clone()
}
fn process_token() -> &'static str {
    PROCESS_TOKEN.get_or_init(|| {
        format!(
            "p-{:x}-{:x}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos()
        )
    })
}
fn revise(state: &mut Registry) -> Vec<Wake> {
    state.snapshot.revision = state.snapshot.revision.saturating_add(1);
    state.snapshot.running = state.entries.keys().cloned().collect();
    state.snapshot.running.sort_by_key(|key| key.session_id);
    state
        .observers
        .retain(|_, observer| observer.alive.load(Ordering::Acquire));
    state
        .observers
        .values()
        .map(|observer| observer.wake.clone())
        .collect()
}
fn wake(observers: Vec<Wake>) {
    for observer in observers {
        observer();
    }
}
pub struct TerminalSubscription {
    id: u64,
    registry: Arc<Mutex<Registry>>,
    seen: Option<u64>,
    alive: Arc<AtomicBool>,
}
impl TerminalSubscription {
    pub fn poll(&mut self) -> Option<TerminalSnapshot> {
        if !self.alive.load(Ordering::Acquire) {
            return None;
        }
        let state = self.registry.lock().unwrap_or_else(|e| e.into_inner());
        if self.seen == Some(state.snapshot.revision) {
            return None;
        }
        self.seen = Some(state.snapshot.revision);
        Some(state.snapshot.clone())
    }
}
impl Drop for TerminalSubscription {
    fn drop(&mut self) {
        self.alive.store(false, Ordering::Release);
        self.registry
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .observers
            .remove(&self.id);
    }
}
impl RuntimeHost {
    pub fn attach_terminal_service(
        &self,
        callback: impl Fn() + Send + Sync + 'static,
    ) -> Result<TerminalSubscription, String> {
        let registry = registry();
        let mut state = registry.lock().unwrap_or_else(|e| e.into_inner());
        state
            .observers
            .retain(|_, observer| observer.alive.load(Ordering::Acquire));
        if state.observers.len() >= MAX_OBSERVERS {
            return Err("终端服务订阅超过预算".into());
        }
        let id = observer_id()?;
        let alive = Arc::new(AtomicBool::new(true));
        state.observers.insert(
            id,
            StatusObserver {
                wake: Arc::new(callback),
                alive: alive.clone(),
            },
        );
        drop(state);
        Ok(TerminalSubscription {
            id,
            registry,
            seen: None,
            alive,
        })
    }
    pub fn cancel_terminal(&self, key: &TerminalKey) -> bool {
        self.publisher().cancel_terminal(key)
    }
}
impl Publisher {
    pub fn terminal_key(&self, session_id: u64) -> TerminalKey {
        TerminalKey {
            process_token: process_token().into(),
            session_id,
        }
    }
    pub fn register_terminal(&self, key: TerminalKey, cancel: Cancel) -> Result<(), String> {
        key.validate()?;
        if key.process_token != process_token() || self.stopped.load(Ordering::Acquire) {
            cancel();
            return Err("终端宿主已结束或身份不匹配".into());
        }
        let registry = registry();
        let mut state = registry.lock().unwrap_or_else(|e| e.into_inner());
        if !state.snapshot.runtime_alive || self.stopped.load(Ordering::Acquire) {
            drop(state);
            cancel();
            return Err("终端运行宿主已结束".into());
        }
        if let Some(old) = state.entries.get(&key) {
            if old.requested {
                drop(state);
                cancel();
                return Err("此终端已请求停止".into());
            }
            return Ok(());
        }
        if state.entries.len() >= MAX_TERMINALS {
            drop(state);
            cancel();
            return Err("终端会话数量超过4个预算".into());
        }
        state.entries.insert(
            key,
            Entry {
                cancel,
                requested: false,
            },
        );
        let observers = revise(&mut state);
        drop(state);
        wake(observers);
        Ok(())
    }
    pub fn remove_terminal(&self, key: &TerminalKey) {
        let registry = registry();
        let mut state = registry.lock().unwrap_or_else(|e| e.into_inner());
        if state.entries.remove(key).is_none() {
            return;
        }
        let observers = revise(&mut state);
        drop(state);
        wake(observers);
    }
    pub fn cancel_terminal(&self, key: &TerminalKey) -> bool {
        if key.validate().is_err() || key.process_token != process_token() {
            return false;
        }
        let registry = registry();
        let mut state = registry.lock().unwrap_or_else(|e| e.into_inner());
        let Some(entry) = state.entries.get_mut(key) else {
            return false;
        };
        entry.requested = true;
        let cancel = entry.cancel.clone();
        drop(state);
        cancel();
        true
    }
}
pub(super) fn stopped() {
    let registry = registry();
    let mut state = registry.lock().unwrap_or_else(|e| e.into_inner());
    state.snapshot.runtime_alive = false;
    let entries = std::mem::take(&mut state.entries);
    let observers = revise(&mut state);
    drop(state);
    for entry in entries.values() {
        (entry.cancel)();
    }
    wake(observers);
}
