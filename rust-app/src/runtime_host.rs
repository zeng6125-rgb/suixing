//! Process-owned runtime and detachable observers. Display delivery never owns
//! task cancellation or persistence acknowledgements. Android services must
//! still be connected before this provides OS background protection.
use crate::{
    events,
    network::CancelToken,
    state::{Action, Event, RuntimeBridge},
};
mod background_work;
mod terminal;
pub use background_work::BackgroundWorkLease;
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc::{self, Receiver, Sender},
    },
    thread::{self, JoinHandle},
};
pub use terminal::{TerminalKey, TerminalSnapshot, TerminalSubscription};

const MAX_UI_OBSERVERS: usize = 4;
const MAX_SERVICE_OBSERVERS: usize = 8;
const MAX_SERVICE_RUNS: usize = 128;

#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct RunKey {
    pub run_id: String,
    pub conversation_id: String,
}

impl RunKey {
    pub fn validate(&self) -> Result<(), String> {
        identifier(&self.run_id)?;
        identifier(&self.conversation_id)
    }
}

#[derive(Clone, Debug)]
pub enum ObserverFilter {
    Ui,
    Conversation(String),
}

type Wake = Arc<dyn Fn() + Send + Sync + 'static>;

#[derive(Clone)]
struct UiObserver {
    filter: ObserverFilter,
    sender: events::Sender,
    wake: Wake,
    alive: Arc<AtomicBool>,
}

#[derive(Clone)]
struct StatusObserver {
    wake: Wake,
    alive: Arc<AtomicBool>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ServiceRunState {
    Running,
    WaitingApproval,
    WaitingStorage,
    Cancelling,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ServiceRunOrigin {
    Chat,
    Goal,
    Schedule,
    ProfileSummary,
}

/// Deliberately separate from Event and Settings: service observers cannot
/// receive connections, keys, tool arguments, prompts or transcripts.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ServiceRunSummary {
    pub key: RunKey,
    pub title: String,
    pub state: ServiceRunState,
    pub origin: ServiceRunOrigin,
    pub pending_approvals: usize,
    pub active_children: usize,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ServiceSnapshot {
    pub revision: u64,
    pub runtime_alive: bool,
    pub runs: Vec<ServiceRunSummary>,
}

impl Default for ServiceSnapshot {
    fn default() -> Self {
        Self {
            revision: 0,
            runtime_alive: true,
            runs: Vec::new(),
        }
    }
}

struct Observers {
    ui: HashMap<u64, UiObserver>,
    status: HashMap<u64, StatusObserver>,
    snapshot: ServiceSnapshot,
}

impl Default for Observers {
    fn default() -> Self {
        Self {
            ui: HashMap::new(),
            status: HashMap::new(),
            snapshot: ServiceSnapshot::default(),
        }
    }
}

struct CancellationScope {
    background: Option<ServiceRunSummary>,
    parent: Arc<CancelToken>,
    children: HashMap<String, Arc<CancelToken>>,
}

type Scopes = Arc<Mutex<HashMap<RunKey, CancellationScope>>>;
type Hub = Arc<Mutex<Observers>>;

/// A strong reference is kept in PROCESS until process termination. Dropping
/// an Activity lease cannot disconnect the actor's action receiver.
pub struct RuntimeHost {
    data: PathBuf,
    action_tx: Sender<Action>,
    hub: Hub,
    scopes: Scopes,
    stopped: Arc<AtomicBool>,
    actor: Mutex<Option<JoinHandle<()>>>,
}

static PROCESS: OnceLock<Mutex<Option<Arc<RuntimeHost>>>> = OnceLock::new();
static NEXT_OBSERVER: AtomicU64 = AtomicU64::new(1);

impl RuntimeHost {
    pub fn get_or_start(
        data: PathBuf,
        runner: impl FnOnce(PathBuf, Receiver<Action>, Publisher) + Send + 'static,
    ) -> Result<Arc<Self>, String> {
        // Context supplies an absolute private path; Store performs filesystem
        // validation on the actor. Bootstrap must not perform disk IO on main.
        if !data.is_absolute()
            || data
                .components()
                .any(|part| matches!(part, std::path::Component::ParentDir))
        {
            return Err("运行宿主需要绝对数据目录".into());
        }
        let mut process = PROCESS
            .get_or_init(|| Mutex::new(None))
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        if let Some(host) = process.as_ref() {
            if host.data != data {
                return Err("进程已有不同数据目录的运行宿主，未重复打开存储".into());
            }
            if host.stopped.load(Ordering::Acquire) {
                return Err("运行宿主已经结束，请重新打开应用进程".into());
            }
            return Ok(host.clone());
        }
        let (action_tx, action_rx) = mpsc::channel();
        let host = Arc::new(Self {
            data: data.clone(),
            action_tx,
            hub: Arc::new(Mutex::new(Observers::default())),
            scopes: Arc::new(Mutex::new(HashMap::new())),
            stopped: Arc::new(AtomicBool::new(false)),
            actor: Mutex::new(None),
        });
        let publisher = host.publisher();
        let actor_publisher = publisher.clone();
        let actor = thread::Builder::new()
            .name("rust-app-runtime".into())
            .spawn(move || {
                let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    runner(data, action_rx, actor_publisher)
                }));
                publisher.stopped.store(true, Ordering::Release);
                // Actor failure is different from subscriber failure: no owner is
                // left to persist or approve more work, so stop its owned scopes.
                publisher.stop_scopes();
                publisher.mark_runtime_stopped();
                publisher.close_ui_observers(if result.is_err() {
                    "应用运行线程异常结束；已保存检查点保留"
                } else {
                    "应用运行宿主已结束；已保存内容保留"
                });
            })
            .map_err(|_| "无法创建应用运行宿主线程")?;
        *host.actor.lock().unwrap_or_else(|e| e.into_inner()) = Some(actor);
        *process = Some(host.clone());
        Ok(host)
    }

    pub fn publisher(&self) -> Publisher {
        Publisher {
            hub: self.hub.clone(),
            scopes: self.scopes.clone(),
            stopped: self.stopped.clone(),
        }
    }

    pub fn submit(&self, action: Action) -> Result<(), String> {
        if self.stopped.load(Ordering::Acquire) {
            return Err("应用运行宿主已经结束".into());
        }
        self.action_tx
            .send(action)
            .map_err(|_| "运行命令连接已结束".into())
    }

    pub fn attach_ui(
        self: &Arc<Self>,
        wake: impl Fn() + Send + Sync + 'static,
    ) -> Result<RuntimeBridge, String> {
        self.attach(ObserverFilter::Ui, Arc::new(wake))
    }

    pub fn attach_conversation(
        self: &Arc<Self>,
        conversation_id: String,
        wake: impl Fn() + Send + Sync + 'static,
    ) -> Result<RuntimeBridge, String> {
        identifier(&conversation_id)?;
        self.attach(
            ObserverFilter::Conversation(conversation_id),
            Arc::new(wake),
        )
    }

    fn attach(
        self: &Arc<Self>,
        filter: ObserverFilter,
        wake: Wake,
    ) -> Result<RuntimeBridge, String> {
        if self.stopped.load(Ordering::Acquire) {
            return Err("应用运行宿主已经结束".into());
        }
        let (sender, event_rx) = events::channel();
        let id = observer_id()?;
        let alive = Arc::new(AtomicBool::new(true));
        let mut hub = self.hub.lock().unwrap_or_else(|e| e.into_inner());
        if self.stopped.load(Ordering::Acquire) {
            return Err("应用运行宿主已经结束".into());
        }
        prune_ui(&mut hub);
        if hub.ui.len() >= MAX_UI_OBSERVERS {
            return Err("界面订阅已达到4个上限".into());
        }
        hub.ui.insert(
            id,
            UiObserver {
                filter,
                sender,
                wake,
                alive: alive.clone(),
            },
        );
        drop(hub);
        Ok(RuntimeBridge {
            action_tx: self.action_tx.clone(),
            event_rx,
            host: self.clone(),
            observer: ObserverLease {
                id,
                hub: self.hub.clone(),
                alive,
            },
        })
    }

    pub fn attach_service(
        &self,
        wake: impl Fn() + Send + Sync + 'static,
    ) -> Result<ServiceSubscription, String> {
        let id = observer_id()?;
        let alive = Arc::new(AtomicBool::new(true));
        let mut hub = self.hub.lock().unwrap_or_else(|e| e.into_inner());
        hub.status
            .retain(|_, observer| observer.alive.load(Ordering::Acquire));
        if hub.status.len() >= MAX_SERVICE_OBSERVERS {
            return Err("服务摘要订阅已达到8个上限".into());
        }
        hub.status.insert(
            id,
            StatusObserver {
                wake: Arc::new(wake),
                alive: alive.clone(),
            },
        );
        Ok(ServiceSubscription {
            id,
            hub: self.hub.clone(),
            alive,
            seen: None,
        })
    }

    pub fn cancel_run(&self, key: &RunKey) -> bool {
        self.publisher().cancel_run(key)
    }
    pub fn cancel_child(&self, key: &RunKey, child_id: &str) -> bool {
        self.publisher().cancel_child(key, child_id)
    }
    pub fn is_running(&self) -> bool {
        !self.stopped.load(Ordering::Acquire)
    }
}

pub struct ObserverLease {
    id: u64,
    hub: Hub,
    alive: Arc<AtomicBool>,
}

impl ObserverLease {
    pub fn id(&self) -> u64 {
        self.id
    }
    pub fn detach(&self) {
        self.alive.store(false, Ordering::Release);
        self.hub
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .ui
            .remove(&self.id);
    }
    pub fn is_alive(&self) -> bool {
        self.alive.load(Ordering::Acquire)
    }
}

impl Drop for ObserverLease {
    fn drop(&mut self) {
        self.detach();
    }
}

pub struct ServiceSubscription {
    id: u64,
    hub: Hub,
    alive: Arc<AtomicBool>,
    seen: Option<u64>,
}

impl ServiceSubscription {
    /// One bounded latest snapshot, rather than a backlog while Android is idle.
    pub fn poll(&mut self) -> Option<ServiceSnapshot> {
        if !self.alive.load(Ordering::Acquire) {
            return None;
        }
        let hub = self.hub.lock().unwrap_or_else(|e| e.into_inner());
        if self.seen == Some(hub.snapshot.revision) {
            return None;
        }
        self.seen = Some(hub.snapshot.revision);
        Some(hub.snapshot.clone())
    }
}

impl Drop for ServiceSubscription {
    fn drop(&mut self) {
        self.alive.store(false, Ordering::Release);
        self.hub
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .status
            .remove(&self.id);
    }
}

#[derive(Clone)]
pub struct Publisher {
    hub: Hub,
    scopes: Scopes,
    stopped: Arc<AtomicBool>,
}

impl Publisher {
    pub(crate) fn ui_observer_alive(&self, id: u64) -> bool {
        self.hub
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .ui
            .get(&id)
            .is_some_and(|observer| {
                matches!(observer.filter, ObserverFilter::Ui)
                    && observer.alive.load(Ordering::Acquire)
                    && observer.sender.is_alive()
            })
    }

    /// Failed delivery retires only that subscriber. Durable Worker replies do
    /// not pass through this method and cannot be lost with the renderer.
    pub fn emit(&self, event: Event) {
        crate::platform::float_console::event(&event);
        let conversation = event_conversation(&event).map(str::to_owned).or_else(|| {
            let run_id = match &event {
                Event::ApprovalCleared { run_id, .. }
                | Event::ChildConfigPreview { run_id, .. }
                | Event::ChildLaunchResult { run_id, .. } => Some(run_id),
                _ => None,
            }?;
            self.scopes
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .keys()
                .find(|key| &key.run_id == run_id)
                .map(|key| key.conversation_id.clone())
        });
        self.broadcast(conversation.as_deref(), event);
    }

    pub fn emit_for_conversation(&self, conversation_id: &str, event: Event) {
        self.broadcast(Some(conversation_id), event);
    }

    fn broadcast(&self, conversation_id: Option<&str>, event: Event) {
        let observers: Vec<_> = {
            let hub = self.hub.lock().unwrap_or_else(|e| e.into_inner());
            hub.ui
                .iter()
                .filter(|(_, observer)| match &observer.filter {
                    ObserverFilter::Ui => true,
                    ObserverFilter::Conversation(id) => conversation_id == Some(id.as_str()),
                })
                .map(|(id, observer)| (*id, observer.clone()))
                .collect()
        };
        for (id, observer) in observers {
            self.deliver(id, &observer, event.clone());
        }
    }

    /// The actor supplies authoritative live state after processing its worker
    /// queue. No Store is opened or read by the observer or this publisher.
    pub fn replay(
        &self,
        observer_id: u64,
        events: impl IntoIterator<Item = Event>,
    ) -> Result<(), String> {
        let observer = self
            .hub
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .ui
            .get(&observer_id)
            .cloned()
            .ok_or("界面订阅已经结束")?;
        for event in events {
            if !self.deliver(observer_id, &observer, event) {
                return Err("界面订阅无法接收快照，请重新订阅".into());
            }
        }
        Ok(())
    }

    fn deliver(&self, id: u64, observer: &UiObserver, event: Event) -> bool {
        if !observer.alive.load(Ordering::Acquire) || observer.sender.send(event).is_err() {
            self.retire(id, &observer.alive);
            return false;
        }
        if observer.alive.load(Ordering::Acquire)
            && std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| (observer.wake)())).is_err()
        {
            self.retire(id, &observer.alive);
            return false;
        }
        true
    }

    fn retire(&self, id: u64, alive: &AtomicBool) {
        alive.store(false, Ordering::Release);
        self.hub
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .ui
            .remove(&id);
    }

    pub fn register_run(&self, key: RunKey, cancel: Arc<CancelToken>) -> Result<(), String> {
        key.validate()?;
        if self.stopped.load(Ordering::Acquire) {
            return Err("运行宿主已经结束，未登记任务".into());
        }
        let mut scopes = self.scopes.lock().unwrap_or_else(|e| e.into_inner());
        if self.stopped.load(Ordering::Acquire) {
            return Err("运行宿主已经结束，未登记任务".into());
        }
        if let Some(existing) = scopes.get(&key) {
            if existing.background.is_some() {
                return Err("该身份属于独立后台工作，未登记主任务".into());
            }
            return if Arc::ptr_eq(&existing.parent, &cancel) {
                Ok(())
            } else {
                Err("运行身份已登记其它取消范围".into())
            };
        }
        if scopes.iter().any(|(existing, scope)| {
            existing.run_id == key.run_id
                || (scope.background.is_none() && existing.conversation_id == key.conversation_id)
        }) {
            return Err("同一会话或运行身份已有任务，未替换取消范围".into());
        }
        scopes.insert(
            key,
            CancellationScope {
                background: None,
                parent: cancel,
                children: HashMap::new(),
            },
        );
        Ok(())
    }

    pub fn register_child(
        &self,
        key: &RunKey,
        id: &str,
        cancel: Arc<CancelToken>,
    ) -> Result<(), String> {
        identifier(id)?;
        let mut scopes = self.scopes.lock().unwrap_or_else(|e| e.into_inner());
        let scope = scopes
            .get_mut(key)
            .filter(|scope| scope.background.is_none())
            .ok_or("父任务取消范围已结束或属于独立后台工作")?;
        if scope.parent.is_cancelled() || self.stopped.load(Ordering::Acquire) {
            cancel.cancel();
            return Err("父任务已经停止，未登记新的子任务".into());
        }
        if let Some(previous) = scope.children.get(id) {
            if !Arc::ptr_eq(previous, &cancel) {
                return Err("子任务身份已登记其它取消范围".into());
            }
        } else {
            scope.children.insert(id.into(), cancel);
        }
        Ok(())
    }
    pub(crate) fn worker_scope_live(&self, key: &RunKey, child: Option<&str>) -> bool {
        !self.stopped.load(Ordering::Acquire)
            && self
                .scopes
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .get(key)
                .is_some_and(|scope| {
                    scope.background.is_none()
                        && !scope.parent.is_cancelled()
                        && child.is_none_or(|id| {
                            scope
                                .children
                                .get(id)
                                .is_some_and(|cancel| !cancel.is_cancelled())
                        })
                })
    }
    pub(crate) fn remove_child(&self, key: &RunKey, id: &str) {
        if let Some(scope) = self
            .scopes
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .get_mut(key)
        {
            scope.children.remove(id);
        }
    }

    /// Only call after the owner has received Ended and drained child final
    /// records. Removing a scope never rewrites a committed outcome as cancelled.
    pub fn remove_run(&self, key: &RunKey) -> bool {
        self.scopes
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(key)
            .is_some()
    }

    pub fn run_keys(&self) -> Vec<RunKey> {
        let mut keys: Vec<_> = self
            .scopes
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .keys()
            .cloned()
            .collect();
        keys.sort_by(|a, b| a.run_id.cmp(&b.run_id));
        keys
    }

    pub fn cancel_run(&self, key: &RunKey) -> bool {
        let tokens: Option<Vec<_>> = self
            .scopes
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(key)
            .map(|scope| {
                // Set parent cancellation under the registry lock so child
                // registration cannot escape this requested stop.
                scope.parent.cancel();
                scope.children.values().cloned().collect()
            });
        let Some(tokens) = tokens else {
            return false;
        };
        for token in tokens {
            token.cancel();
        }
        true
    }

    pub fn cancel_child(&self, key: &RunKey, id: &str) -> bool {
        let token = self
            .scopes
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(key)
            .and_then(|scope| scope.children.get(id))
            .cloned();
        let Some(token) = token else {
            return false;
        };
        token.cancel();
        true
    }

    pub fn service_snapshot(&self, mut snapshot: ServiceSnapshot) -> Result<(), String> {
        // Independent actual work survives ordinary Ended/remove_run.
        for background in self.background_summaries() {
            if !snapshot.runs.iter().any(|run| run.key == background.key) {
                snapshot.runs.push(background);
            }
        }
        snapshot
            .runs
            .sort_by(|a, b| a.key.run_id.cmp(&b.key.run_id));
        if snapshot.runs.len() > MAX_SERVICE_RUNS {
            return Err("服务摘要超过128个运行预算".into());
        }
        let mut keys = std::collections::HashSet::new();
        for run in &snapshot.runs {
            run.key.validate()?;
            if run.title.len() > 1024
                || run.title.contains('\0')
                || run.pending_approvals > 1024
                || run.active_children > 1024
                || !keys.insert(run.key.clone())
            {
                return Err("服务摘要字段无效或重复".into());
            }
        }
        let observers: Vec<_> = {
            let mut hub = self.hub.lock().unwrap_or_else(|e| e.into_inner());
            snapshot.runtime_alive = !self.stopped.load(Ordering::Acquire);
            snapshot.revision = hub.snapshot.revision;
            if snapshot == hub.snapshot {
                return Ok(());
            }
            snapshot.revision = snapshot
                .revision
                .checked_add(1)
                .ok_or("服务摘要版本已耗尽")?;
            hub.snapshot = snapshot;
            hub.status
                .iter()
                .map(|(id, observer)| (*id, observer.clone()))
                .collect()
        };
        for (id, observer) in observers {
            if observer.alive.load(Ordering::Acquire)
                && std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| (observer.wake)()))
                    .is_err()
            {
                observer.alive.store(false, Ordering::Release);
                self.hub
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .status
                    .remove(&id);
            }
        }
        Ok(())
    }

    fn stop_scopes(&self) {
        let scopes = std::mem::take(&mut *self.scopes.lock().unwrap_or_else(|e| e.into_inner()));
        for (_, scope) in scopes {
            scope.parent.cancel();
            for token in scope.children.values() {
                token.cancel();
            }
        }
    }

    fn mark_runtime_stopped(&self) {
        terminal::stopped();
        let snapshot = self
            .hub
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .snapshot
            .clone();
        let _ = self.service_snapshot(ServiceSnapshot {
            runtime_alive: false,
            ..snapshot
        });
    }

    fn close_ui_observers(&self, error: &str) {
        let observers: Vec<_> = self
            .hub
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .ui
            .iter()
            .map(|(id, observer)| (*id, observer.clone()))
            .collect();
        for (id, observer) in observers {
            self.deliver(id, &observer, Event::Error(error.into()));
            self.retire(id, &observer.alive);
        }
    }
}

fn observer_id() -> Result<u64, String> {
    NEXT_OBSERVER
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |id| id.checked_add(1))
        .map_err(|_| "界面订阅身份已耗尽".into())
}

fn identifier(id: &str) -> Result<(), String> {
    if id.is_empty()
        || id.len() > 128
        || !id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    {
        Err("运行或会话身份无效".into())
    } else {
        Ok(())
    }
}

fn prune_ui(hub: &mut Observers) {
    hub.ui.retain(|_, observer| {
        let keep = observer.alive.load(Ordering::Acquire) && observer.sender.is_alive();
        if !keep {
            observer.alive.store(false, Ordering::Release);
        }
        keep
    });
}

fn event_conversation(event: &Event) -> Option<&str> {
    match event {
        Event::ConversationOpened { id, .. } => Some(id),
        Event::HistoryOpened { source, .. }
        | Event::ConversationContextReading { source, .. }
        | Event::HistoryInvalidated { source, .. }
        | Event::HistoryRequestFailed { source, .. } => Some(source.conversation_id()),
        Event::HistoryWindow(page) => Some(page.source.conversation_id()),
        Event::HistoryTurns(page) => Some(page.source.conversation_id()),
        Event::HistoryBody(page) => Some(page.body.source.conversation_id()),
        Event::HistoryFooter { page, .. } => Some(page.source.conversation_id()),
        Event::HistoryImage(page) => Some(page.image.source.conversation_id()),
        Event::ChildSummaries {
            conversation_id, ..
        }
        | Event::ChildSummarySaved {
            conversation_id, ..
        } => Some(conversation_id),
        Event::Conversation { id, .. } | Event::ConversationDeleted(id) => Some(id),
        Event::MessageAdded {
            conversation_id, ..
        }
        | Event::TextDelta {
            conversation_id, ..
        }
        | Event::ThinkingDelta {
            conversation_id, ..
        }
        | Event::QueuedTurns {
            conversation_id, ..
        }
        | Event::Children {
            conversation_id, ..
        }
        | Event::ChildSaved {
            conversation_id, ..
        }
        | Event::ChildDelta {
            conversation_id, ..
        }
        | Event::MessagesUpdated {
            conversation_id, ..
        } => Some(conversation_id),
        Event::ApprovalRequested(request) => Some(&request.conversation_id),
        Event::RunStarted { key }
        | Event::CompactionBusy { key, .. }
        | Event::ContextReading { key, .. }
        | Event::RunEnded { key, .. }
        | Event::RunSteeringMode { key, .. } => Some(&key.conversation_id),
        Event::RunStartRejected {
            conversation_id, ..
        }
        | Event::ConversationModelSelected {
            conversation_id, ..
        } => conversation_id.as_deref(),
        Event::Goal(goal) if !goal.chat_id.is_empty() => Some(&goal.chat_id),
        _ => None,
    }
}
