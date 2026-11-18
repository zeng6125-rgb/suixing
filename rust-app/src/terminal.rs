//! Four independent PTY actors. Metadata and opt-in raw logs survive process
//! death; process handles, input queues and VT writers never do.
use crate::{
    runtime_host::{Publisher, TerminalKey},
    shell_environment::{Backend, ShellEnvironment},
    state::Event,
    vt::{Frame, Geometry, Input},
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, HashMap},
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
        mpsc::{self, SyncSender, TrySendError},
    },
    time::{SystemTime, UNIX_EPOCH},
};
mod output;
mod session;
mod storage;
pub use output::ReadWindow;
pub const MAX_SESSIONS: usize = 4;
pub const MAX_RECORDS: usize = 128;
const MAX_INPUT_BYTES: usize = 16 * 1024;
static NEXT_SESSION: AtomicU64 = AtomicU64::new(1);
pub const fn supported() -> bool {
    cfg!(target_os = "android")
}
pub struct Config {
    pub directory: PathBuf,
    pub environment: ShellEnvironment,
}
#[derive(Clone, Debug)]
pub struct StartSpec {
    pub name: String,
    pub backend: Backend,
    pub geometry: Geometry,
    pub request_key: String,
    pub owner_chat_id: Option<String>,
    pub logging: bool,
}
#[derive(Clone, Copy, Debug, Serialize, Deserialize, Eq, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    Claimed,
    Running,
    Stopping,
    Exited,
    Failed,
    Interrupted,
}
impl Phase {
    pub fn active(self) -> bool {
        matches!(self, Self::Claimed | Self::Running | Self::Stopping)
    }
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct LogInfo {
    pub enabled: bool,
    pub end: u64,
    pub retained_from: u64,
    pub segments: Vec<Segment>,
    pub error: Option<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Segment {
    pub index: u64,
    pub from: u64,
    pub bytes: u64,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SessionInfo {
    pub id: String,
    pub key: TerminalKey,
    pub request_key: String,
    pub name: String,
    pub backend: Backend,
    pub cwd: PathBuf,
    pub shell: PathBuf,
    pub phase: Phase,
    pub started: bool,
    pub owner_chat_id: Option<String>,
    pub created_at: i64,
    pub exit_code: Option<i32>,
    pub note: Option<String>,
    pub revision: u64,
    pub log: LogInfo,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RawWindow {
    pub bytes: Vec<u8>,
    pub from: u64,
    pub next_offset: u64,
    pub retained_from: u64,
    pub end: u64,
    pub has_more: bool,
    pub logging: bool,
    pub error: Option<String>,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Caller {
    pub chat_id: String,
    pub run_id: String,
}
#[derive(Default)]
struct Policy {
    owner: Option<String>,
    revoked: bool,
    grant: Option<Caller>,
    revision: u64,
}
impl Policy {
    fn allow(&self, caller: &Caller) -> bool {
        self.owner
            .as_ref()
            .map_or(self.grant.as_ref() == Some(caller), |owner| {
                owner == &caller.chat_id && !self.revoked
            })
    }
}
enum Command {
    Input {
        input: Input,
        caller: Option<Caller>,
        revision: u64,
        scope_cancel: Option<Arc<AtomicBool>>,
    },
    Resize(Geometry),
    Logging(bool),
}
struct Control {
    key: TerminalKey,
    tx: SyncSender<Command>,
    cancelled: AtomicBool,
    frame: Mutex<Option<Arc<Frame>>>,
    output: Mutex<output::Ring>,
    policy: Mutex<Policy>,
    #[cfg(target_os = "android")]
    write: Mutex<Option<(Arc<suixing_search::rust_terminal::WriteToken>, bool)>>,
}
impl Control {
    fn cancel_write(&self, model_only: bool) {
        #[cfg(target_os = "android")]
        if let Some((write, model)) = self
            .write
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
        {
            if !model_only || *model {
                write.cancel();
            }
        }
    }
    fn take_over(&self) {
        let mut policy = self.policy.lock().unwrap_or_else(|e| e.into_inner());
        policy.revoked = true;
        policy.grant = None;
        policy.revision = policy.revision.wrapping_add(1);
        drop(policy);
        self.cancel_write(true);
    }
    fn authorized(&self, caller: &Caller, revision: Option<u64>) -> bool {
        let policy = self.policy.lock().unwrap_or_else(|e| e.into_inner());
        policy.allow(caller) && revision.is_none_or(|revision| revision == policy.revision)
    }
    fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
        #[cfg(target_os = "android")]
        if let Some((write, _)) = self
            .write
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
        {
            write.cancel();
        }
    }
    fn send(&self, command: Command) -> Result<(), String> {
        if self.cancelled.load(Ordering::Acquire) {
            return Err("此终端已请求停止，输入未执行".into());
        }
        match self.tx.try_send(command) {
            Ok(()) => Ok(()),
            Err(TrySendError::Full(_)) => Err("此终端输入队列已满".into()),
            Err(TrySendError::Disconnected(_)) => Err("此终端已断开".into()),
        }
    }
}
struct State {
    records: BTreeMap<String, SessionInfo>,
    live: HashMap<TerminalKey, Arc<Control>>,
    retained: HashMap<TerminalKey, Arc<Control>>,
    frames: HashMap<TerminalKey, Arc<Frame>>,
    selected: Option<TerminalKey>,
}
struct Shared {
    directory: PathBuf,
    environment: ShellEnvironment,
    publisher: Publisher,
    state: Mutex<State>,
    io: Mutex<()>,
    stopped: AtomicBool,
    raw_reserved: AtomicU64,
    emit: Arc<dyn Fn(Event) + Send + Sync>,
}
pub struct TerminalBridge {
    shared: Arc<Shared>,
}
impl TerminalBridge {
    pub fn spawn_config(
        config: Config,
        publisher: Publisher,
        emit: impl Fn(Event) + Send + Sync + 'static,
    ) -> Result<Self, String> {
        let directory = storage::directory(&config.directory)?;
        let raw_usage = storage::log_usage(&directory)?;
        config.environment.prepare_directories()?;
        let mut records = BTreeMap::new();
        for mut info in storage::load(&directory)? {
            if info.phase.active() {
                info.phase = Phase::Interrupted;
                info.note = Some("应用进程已结束；旧终端未重放，请新建会话".into());
                info.revision = info.revision.saturating_add(1);
                storage::save(&directory, &info)?;
            }
            records.insert(info.id.clone(), info);
        }
        Ok(Self {
            shared: Arc::new(Shared {
                directory,
                environment: config.environment,
                publisher,
                state: Mutex::new(State {
                    records,
                    live: HashMap::new(),
                    retained: HashMap::new(),
                    frames: HashMap::new(),
                    selected: None,
                }),
                io: Mutex::new(()),
                stopped: AtomicBool::new(false),
                raw_reserved: AtomicU64::new(raw_usage),
                emit: Arc::new(emit),
            }),
        })
    }
    pub fn start_session(&self, spec: StartSpec) -> Result<TerminalKey, String> {
        spec.geometry.validate()?;
        if spec.name.len() > 256
            || spec.name.contains('\0')
            || spec.request_key.is_empty()
            || spec.request_key.len() > 1024
            || spec.request_key.contains('\0')
        {
            return Err("终端名称或操作身份无效".into());
        }
        let id = format!("t{:x}", Sha256::digest(spec.request_key.as_bytes()));
        let shared = &self.shared;
        let _io = shared.io.lock().unwrap_or_else(|e| e.into_inner());
        {
            let state = shared.state.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(info) = state.records.get(&id) {
                return Ok(info.key.clone());
            }
            if shared.stopped.load(Ordering::Acquire) {
                return Err("终端运行宿主已结束".into());
            }
            if state.live.len() >= MAX_SESSIONS {
                return Err("最多同时运行4个终端，请先停止一个".into());
            }
            if state.records.len() >= MAX_RECORDS {
                return Err("终端归档达到保留预算，新增进程未启动".into());
            }
        }
        let plan = shared.environment.interactive(spec.backend)?;
        let session_id = NEXT_SESSION
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |id| id.checked_add(1))
            .map_err(|_| "终端会话序号耗尽")?;
        let key = shared.publisher.terminal_key(session_id);
        let info = SessionInfo {
            id: id.clone(),
            key: key.clone(),
            request_key: spec.request_key,
            name: if spec.name.trim().is_empty() {
                format!("{:?} · {session_id}", plan.backend)
            } else {
                spec.name
            },
            backend: plan.backend,
            cwd: plan.cwd.clone(),
            shell: plan.executable.clone(),
            phase: Phase::Claimed,
            started: false,
            owner_chat_id: spec.owner_chat_id.clone(),
            created_at: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_err(|_| "系统时间无效")?
                .as_millis()
                .min(i64::MAX as u128) as i64,
            exit_code: None,
            note: None,
            revision: 1,
            log: LogInfo {
                enabled: spec.logging,
                ..Default::default()
            },
        };
        storage::save(&shared.directory, &info)?;
        let (tx, rx) = mpsc::sync_channel(32);
        let control = Arc::new(Control {
            key: key.clone(),
            tx,
            cancelled: AtomicBool::new(false),
            frame: Mutex::new(None),
            output: Mutex::new(output::Ring::default()),
            policy: Mutex::new(Policy {
                owner: spec.owner_chat_id.clone(),
                ..Default::default()
            }),
            #[cfg(target_os = "android")]
            write: Mutex::new(None),
        });
        {
            let mut state = shared.state.lock().unwrap_or_else(|e| e.into_inner());
            state.records.insert(id.clone(), info);
            state.live.insert(key.clone(), control.clone());
            state.selected = Some(key.clone());
        }
        drop(_io);
        // A durable claim and exact cancellation owner precede both the
        // service request and native process creation.
        let registered = control.clone();
        if let Err(error) = shared
            .publisher
            .register_terminal(key.clone(), Arc::new(move || registered.cancel()))
        {
            shared.finish(&id, &key, None, Some(error.clone()));
            return Err(error);
        }
        #[cfg(target_os = "android")]
        crate::platform::terminal_service::request_refresh();
        shared.publish(&id);
        let worker = shared.clone();
        let actor = control.clone();
        let worker_id = id.clone();
        if std::thread::Builder::new()
            .name(format!("rust-pty-{session_id}"))
            .spawn(move || {
                let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    session::run(&worker, &actor, &worker_id, plan, spec.geometry, rx)
                }));
                if result.is_err() {
                    worker.finish(
                        &worker_id,
                        &actor.key,
                        None,
                        Some("终端线程异常退出，清理结果未确认".into()),
                    );
                }
            })
            .is_err()
        {
            shared.finish(&id, &key, None, Some("无法创建终端线程".into()));
            return Err("无法创建终端线程，已保留未启动记录".into());
        }
        Ok(key)
    }
    pub fn sessions(&self) -> Vec<SessionInfo> {
        let mut records: Vec<_> = self
            .shared
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .records
            .values()
            .cloned()
            .collect();
        records.sort_by_key(|info| std::cmp::Reverse(info.created_at));
        records
    }
    pub fn rename(&self, key: &TerminalKey, name: String) -> Result<(), String> {
        if name.trim().is_empty() || name.len() > 256 || name.contains('\0') {
            return Err("终端名称无效".into());
        }
        let id = self
            .shared
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .records
            .values()
            .find(|info| info.key == *key)
            .map(|info| info.id.clone())
            .ok_or("终端记录不存在")?;
        self.shared.update(&id, |info| info.name = name)
    }
    pub fn select(&self, key: &TerminalKey) -> Result<(), String> {
        let mut state = self.shared.state.lock().unwrap_or_else(|e| e.into_inner());
        if !state.records.values().any(|info| info.key == *key) {
            return Err("没有此终端记录".into());
        }
        state.selected = Some(key.clone());
        Ok(())
    }
    pub fn snapshot_for(&self, key: &TerminalKey) -> Option<Arc<Frame>> {
        self.shared
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .frames
            .get(key)
            .cloned()
    }
    fn control(&self, key: &TerminalKey) -> Result<Arc<Control>, String> {
        key.validate()?;
        self.shared
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .live
            .get(key)
            .cloned()
            .ok_or("终端身份已变化或不再运行".into())
    }
    fn readable_control(&self, key: &TerminalKey) -> Result<Arc<Control>, String> {
        key.validate()?;
        let state = self.shared.state.lock().unwrap_or_else(|e| e.into_inner());
        state
            .live
            .get(key)
            .or_else(|| state.retained.get(key))
            .cloned()
            .ok_or("终端读取缓存不再保留；可读取已启用的原始日志".into())
    }
    pub fn input_for(&self, key: &TerminalKey, input: Input) -> Result<(), String> {
        if matches!(&input, Input::Text{text,..}|Input::Paste(text) if text.len()>MAX_INPUT_BYTES) {
            return Err("终端输入超过16 KiB".into());
        }
        let control = self.control(key)?;
        control.take_over();
        control.send(Command::Input {
            input,
            caller: None,
            revision: 0,
            scope_cancel: None,
        })
    }
    pub fn input_authorized(
        &self,
        key: &TerminalKey,
        input: Input,
        caller: Caller,
    ) -> Result<(), String> {
        self.input_authorized_inner(key, input, caller, None)
    }
    /// The trusted runtime owns this flag. The queued command keeps it alive
    /// after the tool receipt and observes actual run/child cancellation later.
    pub fn input_authorized_scoped(
        &self,
        key: &TerminalKey,
        input: Input,
        caller: Caller,
        scope_cancel: Arc<AtomicBool>,
    ) -> Result<(), String> {
        self.input_authorized_inner(key, input, caller, Some(scope_cancel))
    }
    fn input_authorized_inner(
        &self,
        key: &TerminalKey,
        input: Input,
        caller: Caller,
        scope_cancel: Option<Arc<AtomicBool>>,
    ) -> Result<(), String> {
        if scope_cancel
            .as_ref()
            .is_some_and(|flag| flag.load(Ordering::Acquire))
        {
            return Err("所属任务已取消或结束，终端输入未排队".into());
        }
        if matches!(&input, Input::Text{text,..}|Input::Paste(text) if text.len()>MAX_INPUT_BYTES) {
            return Err("终端输入超过16 KiB".into());
        }
        let control = self.control(key)?;
        let policy = control.policy.lock().unwrap_or_else(|e| e.into_inner());
        if !policy.allow(&caller) {
            return Err("终端未授权此任务或已由用户接管".into());
        }
        let revision = policy.revision;
        drop(policy);
        control.send(Command::Input {
            input,
            caller: Some(caller),
            revision,
            scope_cancel,
        })
    }
    pub fn grant_or_resume(&self, key: &TerminalKey, caller: Caller) -> Result<(), String> {
        let control = self.control(key)?;
        let mut policy = control.policy.lock().unwrap_or_else(|e| e.into_inner());
        if policy
            .owner
            .as_ref()
            .is_some_and(|owner| owner != &caller.chat_id)
        {
            return Err("此终端属于另一个会话".into());
        }
        if policy.owner.is_some() {
            policy.revoked = false;
        } else {
            policy.grant = Some(caller);
        }
        policy.revision = policy.revision.wrapping_add(1);
        control.cancel_write(true);
        Ok(())
    }
    pub fn take_over(&self, key: &TerminalKey) -> Result<(), String> {
        self.control(key)?.take_over();
        Ok(())
    }
    pub fn sessions_for(&self, caller: &Caller) -> Vec<SessionInfo> {
        self.sessions()
            .into_iter()
            .filter(|info| {
                self.readable_control(&info.key)
                    .ok()
                    .is_some_and(|control| control.authorized(caller, None))
            })
            .collect()
    }
    pub fn read_output(
        &self,
        key: &TerminalKey,
        cursor: Option<u64>,
        max_bytes: usize,
        caller: Option<&Caller>,
    ) -> Result<output::ReadWindow, String> {
        let control = self.readable_control(key)?;
        if caller.is_some_and(|caller| !control.authorized(caller, None)) {
            return Err("终端读取未获当前任务授权".into());
        }
        control
            .output
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .read(cursor, max_bytes)
    }
    pub fn stop_authorized(&self, key: &TerminalKey, caller: &Caller) -> Result<(), String> {
        let control = self.control(key)?;
        let policy = control.policy.lock().unwrap_or_else(|e| e.into_inner());
        if !policy.allow(caller) {
            return Err("此终端已由用户接管".into());
        }
        control.cancel();
        Ok(())
    }
    pub fn resize_for(&self, key: &TerminalKey, geometry: Geometry) -> Result<(), String> {
        geometry.validate()?;
        self.control(key)?.send(Command::Resize(geometry))
    }
    pub fn stop_session(&self, key: &TerminalKey) -> Result<(), String> {
        self.control(key)?.cancel();
        Ok(())
    }
    pub fn set_logging(&self, key: &TerminalKey, value: bool) -> Result<(), String> {
        self.control(key)?.send(Command::Logging(value))
    }
    pub fn raw_output(
        &self,
        key: &TerminalKey,
        since: u64,
        max: usize,
    ) -> Result<RawWindow, String> {
        let info = self
            .shared
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .records
            .values()
            .find(|info| info.key == *key)
            .cloned()
            .ok_or("终端记录不存在")?;
        storage::output(&self.shared.directory, &info, since, max)
    }
    pub fn shutdown(&self) {
        self.shared.stopped.store(true, Ordering::Release);
        let live: Vec<_> = self
            .shared
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .live
            .values()
            .cloned()
            .collect();
        for control in live {
            control.cancel();
        }
    }
}
impl Drop for TerminalBridge {
    fn drop(&mut self) {
        self.shutdown();
    }
}
impl Shared {
    fn frame(&self, key: &TerminalKey, frame: Arc<Frame>) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.frames.insert(key.clone(), frame.clone());
        while state.frames.len() > 8 {
            let oldest = state
                .frames
                .keys()
                .filter(|key| !state.live.contains_key(*key))
                .min_by_key(|key| key.session_id)
                .cloned();
            let Some(oldest) = oldest else {
                break;
            };
            state.frames.remove(&oldest);
        }
        drop(state);
        (self.emit)(Event::TerminalFrame(frame));
    }
    fn publish(&self, id: &str) {
        let info = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .records
            .get(id)
            .cloned();
        if let Some(info) = info {
            (self.emit)(Event::TerminalState {
                session_id: info.key.session_id,
                revision: info.revision,
                running: info.phase.active(),
                status: info
                    .note
                    .unwrap_or_else(|| format!("{} · {:?}", info.name, info.phase)),
            });
        }
    }
    fn update(&self, id: &str, edit: impl FnOnce(&mut SessionInfo)) -> Result<(), String> {
        let _io = self.io.lock().unwrap_or_else(|e| e.into_inner());
        let mut info = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .records
            .get(id)
            .cloned()
            .ok_or("终端记录缺失")?;
        edit(&mut info);
        info.revision = info.revision.saturating_add(1);
        let result = storage::save(&self.directory, &info);
        if let Err(error) = &result {
            info.note = Some(format!("元数据提交未确认：{error}"));
            info.log.error = Some(format!("原始日志元数据提交未确认：{error}"));
        }
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .records
            .insert(id.into(), info);
        drop(_io);
        self.publish(id);
        result
    }
    fn finish(&self, id: &str, key: &TerminalKey, exit: Option<i32>, error: Option<String>) {
        let _ = self.update(id, |info| {
            info.phase = if error.is_some() {
                Phase::Failed
            } else {
                Phase::Exited
            };
            info.exit_code = exit;
            info.note = error;
            info.log.enabled = false;
        });
        self.publisher.remove_terminal(key);
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(control) = state.live.remove(key) {
            state.retained.insert(key.clone(), control);
        }
        while state.retained.len() > 8 {
            let oldest = state
                .retained
                .keys()
                .min_by_key(|key| key.session_id)
                .cloned();
            if let Some(oldest) = oldest {
                state.retained.remove(&oldest);
            } else {
                break;
            }
        }
    }
}
