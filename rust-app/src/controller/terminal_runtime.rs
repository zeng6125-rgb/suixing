//! Actor adapter for independent terminal records, full process identities and
//! bounded UI deliveries. RuntimeHost owns the registry and service lifecycle.
use crate::{
    runtime_host::{Publisher, TerminalKey},
    shell_environment::{Backend, FrozenPaths, ShellEnvironment},
    state::Event as StateEvent,
    terminal::{self, Caller, RawWindow, SessionInfo, StartSpec, TerminalBridge},
    vt::{Frame, Geometry, Input},
};
use std::{
    collections::{BTreeMap, VecDeque},
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
};

#[derive(Clone, Debug)]
pub struct BootConfig {
    pub directory: PathBuf,
    pub environment: ShellEnvironment,
}
impl BootConfig {
    pub fn new(directory: PathBuf, environment: ShellEnvironment) -> Result<Self, String> {
        if !directory.is_absolute() || directory.as_os_str().to_string_lossy().contains('\0') {
            return Err("终端元数据需要明确绝对目录".into());
        }
        Ok(Self {
            directory,
            environment,
        })
    }
    pub fn from_paths(directory: PathBuf, paths: FrozenPaths) -> Result<Self, String> {
        Self::new(directory, ShellEnvironment::new(paths)?)
    }
}
#[derive(Clone, Debug)]
pub enum Request {
    Create {
        request_id: String,
        backend: Backend,
        name: String,
        logging: bool,
        geometry: Geometry,
    },
    Select {
        request_id: String,
        key: TerminalKey,
    },
    Rename {
        request_id: String,
        key: TerminalKey,
        name: String,
    },
    Input {
        request_id: String,
        key: TerminalKey,
        input: Input,
    },
    Resize {
        request_id: String,
        key: TerminalKey,
        geometry: Geometry,
    },
    Stop {
        request_id: String,
        key: TerminalKey,
    },
    SetLogging {
        request_id: String,
        key: TerminalKey,
        enabled: bool,
    },
    TakeOver {
        request_id: String,
        key: TerminalKey,
    },
    Resume {
        request_id: String,
        key: TerminalKey,
    },
    ReadRaw {
        request_id: String,
        key: TerminalKey,
        cursor: u64,
        max_bytes: usize,
    },
    Refresh {
        request_id: String,
    },
}
impl Request {
    pub fn request_id(&self) -> &str {
        match self {
            Self::Create { request_id, .. }
            | Self::Select { request_id, .. }
            | Self::Rename { request_id, .. }
            | Self::Input { request_id, .. }
            | Self::Resize { request_id, .. }
            | Self::Stop { request_id, .. }
            | Self::SetLogging { request_id, .. }
            | Self::TakeOver { request_id, .. }
            | Self::Resume { request_id, .. }
            | Self::ReadRaw { request_id, .. }
            | Self::Refresh { request_id } => request_id,
        }
    }
    pub fn key(&self) -> Option<&TerminalKey> {
        match self {
            Self::Create { .. } | Self::Refresh { .. } => None,
            Self::Select { key, .. }
            | Self::Rename { key, .. }
            | Self::Input { key, .. }
            | Self::Resize { key, .. }
            | Self::Stop { key, .. }
            | Self::SetLogging { key, .. }
            | Self::TakeOver { key, .. }
            | Self::Resume { key, .. }
            | Self::ReadRaw { key, .. } => Some(key),
        }
    }
}
#[derive(Clone, Debug)]
pub enum Update {
    Snapshot {
        revision: u64,
        sessions: Vec<SessionInfo>,
        selected: Option<TerminalKey>,
    },
    /// This acknowledges the adapter/control operation. queued=true does not
    /// confirm PTY execution. Stop acceptance is confirmed by later phase data.
    Finished {
        request_id: String,
        key: Option<TerminalKey>,
        result: Result<(), String>,
        queued: bool,
    },
    Raw {
        request_id: String,
        key: TerminalKey,
        result: Result<RawWindow, String>,
    },
    Frame {
        key: TerminalKey,
        frame: Arc<Frame>,
    },
    ResizeRejected {
        key: TerminalKey,
        geometry_version: u64,
        error: String,
    },
}
struct Mailbox {
    frames: BTreeMap<u64, (TerminalKey, Arc<Frame>)>,
    rejects: VecDeque<Update>,
    overflow: bool,
}
pub struct TerminalRuntime {
    bridge: Arc<TerminalBridge>,
    publisher: Publisher,
    selected: Arc<Mutex<Option<TerminalKey>>>,
    mailbox: Arc<Mutex<Mailbox>>,
    dirty: Arc<AtomicBool>,
    revision: AtomicU64,
    emit: Arc<dyn Fn(StateEvent) + Send + Sync>,
}
impl TerminalRuntime {
    /// Store supplies a stable terminal directory and the same frozen platform
    /// environment used for Jobs/Linux. Creating this adapter starts no PTY.
    pub fn spawn(
        config: BootConfig,
        publisher: Publisher,
        emit: impl Fn(StateEvent) + Send + Sync + 'static,
    ) -> Result<Self, String> {
        let selected = Arc::new(Mutex::new(None::<TerminalKey>));
        let mailbox = Arc::new(Mutex::new(Mailbox {
            frames: BTreeMap::new(),
            rejects: VecDeque::new(),
            overflow: false,
        }));
        let dirty = Arc::new(AtomicBool::new(true));
        let emit: Arc<dyn Fn(StateEvent) + Send + Sync> = Arc::new(emit);
        let event_selected = selected.clone();
        let event_mailbox = mailbox.clone();
        let event_dirty = dirty.clone();
        let event_emit = emit.clone();
        let event_publisher = publisher.clone();
        let bridge = TerminalBridge::spawn_config(
            terminal::Config {
                directory: config.directory,
                environment: config.environment,
            },
            publisher.clone(),
            move |event| match &event {
                StateEvent::TerminalFrame(frame) => {
                    let key = event_publisher.terminal_key(frame.session_id);
                    let selected = event_selected
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .clone();
                    let mut mailbox = event_mailbox
                        .lock()
                        .unwrap_or_else(|error| error.into_inner());
                    mailbox
                        .frames
                        .insert(frame.session_id, (key.clone(), frame.clone()));
                    while mailbox.frames.len() > 12 {
                        if let Some(first) = mailbox.frames.keys().next().copied() {
                            mailbox.frames.remove(&first);
                        }
                    }
                    drop(mailbox);
                    if selected.as_ref() == Some(&key) {
                        event_emit(event);
                    }
                }
                StateEvent::TerminalState { session_id, .. } => {
                    event_dirty.store(true, Ordering::Release);
                    let key = event_publisher.terminal_key(*session_id);
                    let selected = event_selected
                        .lock()
                        .unwrap_or_else(|error| error.into_inner())
                        .clone();
                    if selected.as_ref() == Some(&key) {
                        event_emit(event);
                    }
                }
                StateEvent::TerminalResizeRejected {
                    session_id,
                    geometry_version,
                    error,
                } => {
                    let update = Update::ResizeRejected {
                        key: event_publisher.terminal_key(*session_id),
                        geometry_version: *geometry_version,
                        error: error.clone(),
                    };
                    let mut mailbox = event_mailbox
                        .lock()
                        .unwrap_or_else(|error| error.into_inner());
                    if mailbox.rejects.len() >= 32 {
                        mailbox.rejects.pop_front();
                        mailbox.overflow = true;
                    }
                    mailbox.rejects.push_back(update);
                    drop(mailbox);
                    event_emit(event);
                }
                _ => event_emit(event),
            },
        )?;
        let initial = bridge.sessions().first().map(|info| info.key.clone());
        if let Some(key) = &initial {
            bridge.select(key)?;
        }
        *selected.lock().unwrap_or_else(|error| error.into_inner()) = initial;
        Ok(Self {
            bridge: Arc::new(bridge),
            publisher,
            selected,
            mailbox,
            dirty,
            revision: AtomicU64::new(0),
            emit,
        })
    }
    pub fn bridge(&self) -> &TerminalBridge {
        &self.bridge
    }
    pub(crate) fn handle(&self) -> Arc<TerminalBridge> {
        self.bridge.clone()
    }
    pub fn selected(&self) -> Option<TerminalKey> {
        self.selected
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .clone()
    }
    pub fn legacy_key(&self, session_id: u64) -> Result<TerminalKey, String> {
        if session_id == 0 {
            return self.selected().ok_or_else(|| "尚未选择终端".into());
        }
        let key = self.publisher.terminal_key(session_id);
        self.known(&key)?;
        Ok(key)
    }
    fn known(&self, key: &TerminalKey) -> Result<(), String> {
        key.validate()?;
        if !self.bridge.sessions().iter().any(|info| info.key == *key) {
            return Err("终端进程身份已变化或记录不存在".into());
        }
        Ok(())
    }
    pub fn snapshot(&self) -> Option<Arc<Frame>> {
        self.selected()
            .and_then(|key| self.bridge.snapshot_for(&key))
    }
    pub fn status(&self) -> Option<StateEvent> {
        let key = self.selected()?;
        let info = self
            .bridge
            .sessions()
            .into_iter()
            .find(|info| info.key == key)?;
        Some(StateEvent::TerminalState {
            session_id: key.session_id,
            revision: info.revision,
            running: info.phase.active(),
            status: info
                .note
                .unwrap_or_else(|| format!("{} · {:?}", info.name, info.phase)),
        })
    }
    pub fn refresh(&self) {
        self.dirty.store(true, Ordering::Release);
        if let Some(key) = self.selected() {
            if let Some(frame) = self.bridge.snapshot_for(&key) {
                self.mailbox
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .frames
                    .insert(key.session_id, (key, frame.clone()));
                (self.emit)(StateEvent::TerminalFrame(frame));
            }
            if let Some(event) = self.status() {
                (self.emit)(event);
            }
        }
    }
    /// Caller is resolved by the actor from a real active run for Resume. UI
    /// request fields never supply a chat/run grant. Root handles model approval
    /// and claim before accessing bridge() through terminal_tools.
    pub fn dispatch(&self, request: Request, caller: Option<Caller>) -> Update {
        let request_id = request.request_id().to_owned();
        let key = request.key().cloned();
        let valid_id = !request_id.is_empty()
            && request_id.len() <= 128
            && request_id
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'));
        if !valid_id {
            return match request {
                Request::ReadRaw { key, .. } => Update::Raw {
                    request_id,
                    key,
                    result: Err("终端请求身份无效".into()),
                },
                _ => Update::Finished {
                    request_id,
                    key,
                    result: Err("终端请求身份无效".into()),
                    queued: false,
                },
            };
        }
        if let Request::ReadRaw {
            key,
            cursor,
            max_bytes,
            ..
        } = &request
        {
            let result = self
                .known(key)
                .and_then(|()| self.bridge.raw_output(key, *cursor, *max_bytes));
            return Update::Raw {
                request_id,
                key: key.clone(),
                result,
            };
        }
        let queued = matches!(
            request,
            Request::Input { .. } | Request::Resize { .. } | Request::SetLogging { .. }
        );
        let mut finished_key = key;
        let result = (|| -> Result<(), String> {
            if let Some(key) = request.key() {
                self.known(key)?;
            }
            match request {
                Request::Create {
                    backend,
                    name,
                    logging,
                    geometry,
                    ..
                } => {
                    let process = self.publisher.terminal_key(1).process_token;
                    let request_key = format!("terminal-ui-{process}-{request_id}");
                    let key = match self.bridge.start_session(StartSpec {
                        name,
                        backend,
                        geometry,
                        request_key: request_key.clone(),
                        owner_chat_id: None,
                        logging,
                    }) {
                        Ok(key) => key,
                        Err(error) => {
                            finished_key = self
                                .bridge
                                .sessions()
                                .into_iter()
                                .find(|info| info.request_key == request_key)
                                .map(|info| info.key);
                            self.dirty.store(true, Ordering::Release);
                            return Err(error);
                        }
                    };
                    self.bridge.select(&key)?;
                    *self
                        .selected
                        .lock()
                        .unwrap_or_else(|error| error.into_inner()) = Some(key.clone());
                    finished_key = Some(key);
                    self.refresh();
                    Ok(())
                }
                Request::Select { key, .. } => {
                    self.bridge.select(&key)?;
                    *self
                        .selected
                        .lock()
                        .unwrap_or_else(|error| error.into_inner()) = Some(key);
                    self.refresh();
                    Ok(())
                }
                Request::Rename { key, name, .. } => self.bridge.rename(&key, name),
                Request::Input { key, input, .. } => self.bridge.input_for(&key, input),
                Request::Resize { key, geometry, .. } => self.bridge.resize_for(&key, geometry),
                Request::Stop { key, .. } => self.bridge.stop_session(&key),
                Request::SetLogging { key, enabled, .. } => self.bridge.set_logging(&key, enabled),
                Request::TakeOver { key, .. } => self.bridge.take_over(&key),
                Request::Resume { key, .. } => self
                    .bridge
                    .grant_or_resume(&key, caller.ok_or("没有可信活跃任务，终端临时授权未建立")?),
                Request::Refresh { .. } => {
                    self.refresh();
                    Ok(())
                }
                Request::ReadRaw { .. } => unreachable!("handled raw request"),
            }
        })();
        if result.is_ok() {
            self.dirty.store(true, Ordering::Release);
        }
        Update::Finished {
            request_id,
            key: finished_key,
            result,
            queued,
        }
    }
    /// Root forwards each update through StateEvent::Terminal. PTY actors keep
    /// only the newest Arc frame per key; no raw bytes or full history queue.
    pub fn poll(&self, max: usize) -> Vec<Update> {
        let max = max.clamp(1, 64);
        let mut updates = Vec::new();
        if self.dirty.swap(false, Ordering::AcqRel) {
            let revision = self
                .revision
                .fetch_add(1, Ordering::AcqRel)
                .saturating_add(1);
            updates.push(Update::Snapshot {
                revision,
                sessions: self.bridge.sessions(),
                selected: self.selected(),
            });
        }
        let mut mailbox = self
            .mailbox
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        while updates.len() < max {
            if let Some(reject) = mailbox.rejects.pop_front() {
                updates.push(reject);
            } else {
                break;
            }
        }
        while updates.len() < max {
            let Some(id) = mailbox.frames.keys().next().copied() else {
                break;
            };
            if let Some((key, frame)) = mailbox.frames.remove(&id) {
                updates.push(Update::Frame { key, frame });
            }
        }
        let overflow = std::mem::take(&mut mailbox.overflow);
        drop(mailbox);
        if overflow {
            (self.emit)(StateEvent::Error(
                "终端窗口拒绝事件超出有界邮箱；请重新刷新对应窗口状态".into(),
            ));
        }
        updates
    }
    pub fn shutdown(&self) {
        self.bridge.shutdown();
    }
}
