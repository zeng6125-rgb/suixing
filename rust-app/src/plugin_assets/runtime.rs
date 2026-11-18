//! Actor admission with fixed I/O/cancellation workers and exact result owners.
use super::*;
mod stage;
use crate::platform::permissions::OperationKey;
use std::sync::{
    Arc, Mutex, OnceLock,
    atomic::{AtomicBool, AtomicU8, Ordering},
    mpsc::{self, SyncSender},
};
use std::{
    collections::VecDeque,
    time::{Duration, Instant},
};
const CAPACITY: usize = 4;
#[derive(Clone, Debug)]
pub(crate) enum Command {
    Pick,
    PreviewPath(PathBuf),
    Install(String),
    Discard(String),
    Remove { id: String, wipe: bool },
    Cancel,
}
#[derive(Clone, Debug)]
pub(crate) struct Request {
    pub operation: OperationKey,
    pub command: Command,
}
#[derive(Clone, Debug)]
pub(crate) struct Review {
    pub ticket: String,
    pub id: String,
    pub manifest: crate::plugins::PluginManifest,
    pub files: usize,
    pub directories: usize,
    pub bytes: u64,
    pub warnings: Vec<String>,
}
#[derive(Clone, Debug)]
pub(crate) enum State {
    Prepared(Review),
    Installed(String),
    Removed(String),
    Cancelled,
    Failed(String),
    Uncertain { root: PathBuf, message: String },
    WaitingForTerminal(String),
}
#[derive(Clone, Debug)]
pub(crate) struct Update {
    pub operation: OperationKey,
    pub state: State,
}
#[derive(Clone, Debug, PartialEq, Eq)]
struct RootKey {
    path: PathBuf,
    workspace: PathBuf,
    device: u64,
    inode: u64,
}
impl RootKey {
    fn read(path: &Path, workspace: PathBuf) -> Result<Self, String> {
        checked_root(path)?;
        let metadata = fs::metadata(path).map_err(|_| "插件拥有目录无法读取")?;
        #[cfg(unix)]
        let (device, inode) = {
            use std::os::unix::fs::MetadataExt;
            (metadata.dev(), metadata.ino())
        };
        #[cfg(not(unix))]
        let (device, inode) = (0, 0);
        Ok(Self {
            path: path.to_owned(),
            workspace,
            device,
            inode,
        })
    }
    fn validate(&self) -> Result<(), String> {
        if Self::read(&self.path, self.workspace.clone())? != *self {
            Err("插件拥有目录身份已改变，未发布旧候选".into())
        } else {
            Ok(())
        }
    }
}
#[cfg(target_os = "android")]
type Object = Arc<jni::refs::Global<jni::objects::JObject<'static>>>;
struct Control {
    cancel: Arc<CancelToken>,
    phase: AtomicU8,
    deadline: Mutex<Instant>,
    io_done: AtomicBool,
    notice_sent: AtomicBool,
    cleanup_error: Mutex<Option<String>>,
    #[cfg(target_os = "android")]
    signal: Mutex<Option<Object>>,
    #[cfg(target_os = "android")]
    signal_admitted: AtomicBool,
    #[cfg(target_os = "android")]
    signal_done: AtomicBool,
}
impl Control {
    fn new() -> Self {
        Self {
            cancel: Arc::new(CancelToken::new()),
            phase: AtomicU8::new(0),
            deadline: Mutex::new(Instant::now() + Duration::from_secs(120)),
            io_done: AtomicBool::new(false),
            notice_sent: AtomicBool::new(false),
            cleanup_error: Mutex::new(None),
            #[cfg(target_os = "android")]
            signal: Mutex::new(None),
            #[cfg(target_os = "android")]
            signal_admitted: AtomicBool::new(false),
            #[cfg(target_os = "android")]
            signal_done: AtomicBool::new(false),
        }
    }
    fn check(&self) -> Result<(), String> {
        if Instant::now()
            >= *self
                .deadline
                .lock()
                .unwrap_or_else(|error| error.into_inner())
        {
            self.cancel.cancel();
        }
        if self.cancel.is_cancelled() {
            Err("插件导入已取消或超过截止时间，未自动重复".into())
        } else {
            Ok(())
        }
    }
}
struct Pending {
    operation: OperationKey,
    root: RootKey,
    control: Arc<Control>,
    result: Option<State>,
}
#[derive(Clone)]
struct Ticket {
    handle: String,
    operation: OperationKey,
    id: String,
    stage: String,
    root: RootKey,
    layout: Vec<Layout>,
    receipt: stage::Receipt,
    expires: Instant,
}
#[cfg(target_os = "android")]
struct Picker {
    operation: OperationKey,
    control: Arc<Control>,
    root: RootKey,
    activity: Option<Object>,
    cancelled: bool,
    started: bool,
}
#[derive(Default)]
struct Registry {
    pending: BTreeMap<String, Pending>,
    tickets: BTreeMap<String, Ticket>,
    results: VecDeque<Update>,
    claimed_stages: BTreeSet<String>,
    #[cfg(target_os = "android")]
    pickers: BTreeMap<i32, Picker>,
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
// Serialize archive expansion and stage admission across the fixed workers.
static PREPARATION: Mutex<()> = Mutex::new(());
fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(Default::default)
}
fn exact<'a>(
    registry: &'a mut Registry,
    key: &OperationKey,
    control: &Arc<Control>,
) -> Option<&'a mut Pending> {
    registry
        .pending
        .get_mut(&key.id)
        .filter(|pending| pending.operation == *key && Arc::ptr_eq(&pending.control, control))
}
enum Work {
    Preview {
        operation: OperationKey,
        root: RootKey,
        control: Arc<Control>,
        path: PathBuf,
    },
    Install {
        operation: OperationKey,
        control: Arc<Control>,
        ticket: Ticket,
    },
    Discard {
        operation: OperationKey,
        control: Arc<Control>,
        ticket: Ticket,
    },
    Remove {
        operation: OperationKey,
        root: RootKey,
        control: Arc<Control>,
        id: String,
        wipe: bool,
    },
    Cleanup(Ticket),
    #[cfg(target_os = "android")]
    ReadUri {
        operation: OperationKey,
        root: RootKey,
        control: Arc<Control>,
        uri: Object,
    },
}
struct Workers {
    io: SyncSender<Work>,
    #[cfg(target_os = "android")]
    cancellation: SyncSender<(OperationKey, Arc<Control>, Object)>,
}
static WORKERS: OnceLock<Result<Workers, String>> = OnceLock::new();
fn workers() -> Result<&'static Workers, String> {
    WORKERS
        .get_or_init(|| {
            let (sender, receive) = mpsc::sync_channel::<Work>(CAPACITY * 2);
            let receive = Arc::new(Mutex::new(receive));
            for index in 0..2 {
                let receive = receive.clone();
                std::thread::Builder::new()
                    .name(format!("rust-plugin-import-{index}"))
                    .spawn(move || {
                        loop {
                            let work = receive
                                .lock()
                                .unwrap_or_else(|error| error.into_inner())
                                .recv();
                            let Ok(work) = work else {
                                break;
                            };
                            match work {
                                Work::Preview {
                                    operation,
                                    root,
                                    control,
                                    path,
                                } => {
                                    if control
                                        .phase
                                        .compare_exchange(0, 1, Ordering::AcqRel, Ordering::Acquire)
                                        .is_err()
                                    {
                                        continue;
                                    }
                                    let result = std::panic::catch_unwind(
                                        std::panic::AssertUnwindSafe(|| {
                                            control.check()?;
                                            let file = open_package_source(
                                                &root.workspace,
                                                path.to_str().ok_or("插件源路径不是UTF8")?,
                                                &control.cancel,
                                            )?
                                            .1;
                                            read_file(&file, &control).and_then(|bytes| {
                                                prepare(&operation, &root, &control, &bytes)
                                            })
                                        }),
                                    )
                                    .unwrap_or_else(|_| Err("插件包读取异常，未自动安装".into()));
                                    finish(&operation, &control, result);
                                }
                                Work::Install {
                                    operation,
                                    control,
                                    mut ticket,
                                } => {
                                    if control
                                        .phase
                                        .compare_exchange(0, 1, Ordering::AcqRel, Ordering::Acquire)
                                        .is_err()
                                    {
                                        continue;
                                    }
                                    let mut result = std::panic::catch_unwind(
                                        std::panic::AssertUnwindSafe(|| {
                                            install(&mut ticket, &control)
                                        }),
                                    )
                                    .unwrap_or_else(|_| {
                                        Ok(State::Uncertain {
                                            root: ticket.root.path.clone(),
                                            message: "插件发布异常，安装效果需核对，请勿重复"
                                                .into(),
                                        })
                                    });
                                    if result.is_err()
                                        && ticket.root.path.join(&ticket.stage).exists()
                                    {
                                        let cleanup = ticket.receipt.retire(&ticket.root);
                                        if cleanup.is_err() {
                                            result = Err(format!(
                                                "{}；本操作准备目录清理未确认",
                                                result
                                                    .err()
                                                    .unwrap_or_else(|| "插件安装失败".into())
                                            ));
                                        }
                                    }
                                    registry()
                                        .lock()
                                        .unwrap_or_else(|error| error.into_inner())
                                        .claimed_stages
                                        .remove(&ticket.stage);
                                    finish(&operation, &control, result);
                                }
                                Work::Discard {
                                    operation,
                                    control,
                                    ticket,
                                } => {
                                    if control
                                        .phase
                                        .compare_exchange(0, 1, Ordering::AcqRel, Ordering::Acquire)
                                        .is_err()
                                    {
                                        continue;
                                    }
                                    let _preparation = PREPARATION
                                        .lock()
                                        .unwrap_or_else(|error| error.into_inner());
                                    let result = ticket
                                        .receipt
                                        .retire(&ticket.root)
                                        .map(|_| State::Cancelled);
                                    registry()
                                        .lock()
                                        .unwrap_or_else(|error| error.into_inner())
                                        .claimed_stages
                                        .remove(&ticket.stage);
                                    finish(&operation, &control, result);
                                }
                                Work::Remove {
                                    operation,
                                    root,
                                    control,
                                    id,
                                    wipe,
                                } => {
                                    if control
                                        .phase
                                        .compare_exchange(0, 1, Ordering::AcqRel, Ordering::Acquire)
                                        .is_err()
                                    {
                                        continue;
                                    }
                                    let result = std::panic::catch_unwind(
                                        std::panic::AssertUnwindSafe(|| {
                                            root.validate()?;
                                            control.check()?;
                                            trash(&root.path, &id, wipe, &control.cancel)?;
                                            Ok(State::Removed(id))
                                        }),
                                    )
                                    .unwrap_or_else(|_| {
                                        Err("插件移除异常，实际目录效果需核对".into())
                                    });
                                    finish(&operation, &control, result);
                                }
                                Work::Cleanup(ticket) => {
                                    let _preparation = PREPARATION
                                        .lock()
                                        .unwrap_or_else(|error| error.into_inner());
                                    let result = ticket.receipt.retire(&ticket.root);
                                    registry()
                                        .lock()
                                        .unwrap_or_else(|error| error.into_inner())
                                        .claimed_stages
                                        .remove(&ticket.stage);
                                    let state = if result.is_ok() {
                                        State::Failed(
                                            "插件候选已过期并已回收，请重新选择原包".into(),
                                        )
                                    } else {
                                        State::Failed("插件候选已过期，准备目录清理未确认".into())
                                    };
                                    registry()
                                        .lock()
                                        .unwrap_or_else(|error| error.into_inner())
                                        .results
                                        .push_back(Update {
                                            operation: ticket.operation,
                                            state,
                                        });
                                }
                                #[cfg(target_os = "android")]
                                Work::ReadUri {
                                    operation,
                                    root,
                                    control,
                                    uri,
                                } => {
                                    if control
                                        .phase
                                        .compare_exchange(0, 1, Ordering::AcqRel, Ordering::Acquire)
                                        .is_err()
                                    {
                                        continue;
                                    }
                                    let result = std::panic::catch_unwind(
                                        std::panic::AssertUnwindSafe(|| {
                                            device::read_uri(&operation, &control, uri).and_then(
                                                |bytes| {
                                                    prepare(&operation, &root, &control, &bytes)
                                                },
                                            )
                                        }),
                                    )
                                    .unwrap_or_else(|_| Err("插件文档读取异常，未自动安装".into()));
                                    finish(&operation, &control, result);
                                }
                            }
                        }
                    })
                    .map_err(|_| "插件I/O线程不可用")?;
            }
            #[cfg(target_os = "android")]
            let cancellation = {
                let (sender, receive) =
                    mpsc::sync_channel::<(OperationKey, Arc<Control>, Object)>(CAPACITY);
                std::thread::Builder::new()
                    .name("rust-plugin-saf-cancellation".into())
                    .spawn(move || {
                        while let Ok((key, control, signal)) = receive.recv() {
                            let _ = crate::platform::android::invoke(|env| {
                                env.call_method(
                                    signal.as_ref(),
                                    jni::jni_str!("cancel"),
                                    jni::jni_sig!("()V"),
                                    &[],
                                )?;
                                Ok(())
                            });
                            control.signal_done.store(true, Ordering::Release);
                            publish_terminal(&key, &control);
                        }
                    })
                    .map_err(|_| "插件文档取消线程不可用")?;
                sender
            };
            std::thread::Builder::new()
                .name("rust-plugin-import-watchdog".into())
                .spawn(|| {
                    loop {
                        let pending: Vec<_> = registry()
                            .lock()
                            .unwrap_or_else(|error| error.into_inner())
                            .pending
                            .values()
                            .map(|pending| (pending.operation.clone(), pending.control.clone()))
                            .collect();
                        for (key, control) in pending {
                            if control.check().is_err() {
                                cancel_owned(&key, &control);
                            }
                            publish_terminal(&key, &control);
                        }
                        if let Some(Ok(workers)) = WORKERS.get() {
                            let mut state =
                                registry().lock().unwrap_or_else(|error| error.into_inner());
                            let expired: Vec<_> = state
                                .tickets
                                .iter()
                                .filter(|(_, ticket)| Instant::now() >= ticket.expires)
                                .map(|(id, _)| id.clone())
                                .collect();
                            for id in expired {
                                if let Some(ticket) = state.tickets.remove(&id) {
                                    state.claimed_stages.insert(ticket.stage.clone());
                                    if let Err(
                                        mpsc::TrySendError::Full(Work::Cleanup(ticket))
                                        | mpsc::TrySendError::Disconnected(Work::Cleanup(ticket)),
                                    ) = workers.io.try_send(Work::Cleanup(ticket))
                                    {
                                        state.claimed_stages.remove(&ticket.stage);
                                        state.tickets.insert(id, ticket);
                                    }
                                }
                            }
                        }
                        std::thread::sleep(Duration::from_millis(100));
                    }
                })
                .map_err(|_| "插件导入截止时间监控不可用")?;
            Ok(Workers {
                io: sender,
                #[cfg(target_os = "android")]
                cancellation,
            })
        })
        .as_ref()
        .map_err(Clone::clone)
}
#[cfg(unix)]
fn open_package_source(
    workspace: &Path,
    path: &str,
    cancel: &CancelToken,
) -> Result<(PathBuf, std::fs::File), String> {
    crate::tools::file_tools::open_source(workspace, path, cancel)
}
#[cfg(not(unix))]
fn open_package_source(
    _workspace: &Path,
    _path: &str,
    _cancel: &CancelToken,
) -> Result<(PathBuf, std::fs::File), String> {
    Err("当前平台没有插件包目录句柄读取后端".into())
}
pub(crate) fn busy(root: &Path) -> bool {
    registry()
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .pending
        .values()
        .any(|pending| pending.root.path == root)
}
pub(crate) fn dispatch(root: PathBuf, workspace: PathBuf, request: Request) -> Result<(), String> {
    request.operation.validate()?;
    if matches!(request.command, Command::Cancel) {
        let control = registry()
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .pending
            .get(&request.operation.id)
            .filter(|pending| pending.operation == request.operation)
            .map(|pending| pending.control.clone());
        if let Some(control) = control {
            cancel_owned(&request.operation, &control);
        }
        return Ok(());
    }
    let root = RootKey::read(&root, workspace)?;
    let workers = workers()?;
    let control = Arc::new(Control::new());
    let mut state = registry().lock().unwrap_or_else(|error| error.into_inner());
    if state.pending.len() + state.results.len() >= CAPACITY
        || state.pending.contains_key(&request.operation.id)
        || state
            .results
            .iter()
            .any(|update| update.operation.id == request.operation.id)
        || state.tickets.len() >= CAPACITY
            && matches!(request.command, Command::Pick | Command::PreviewPath(_))
    {
        return Err("插件导入队列或候选已满".into());
    }
    state.pending.insert(
        request.operation.id.clone(),
        Pending {
            operation: request.operation.clone(),
            root: root.clone(),
            control: control.clone(),
            result: None,
        },
    );
    let work = match request.command {
        Command::PreviewPath(path) => Some(Work::Preview {
            operation: request.operation.clone(),
            root: root.clone(),
            control: control.clone(),
            path,
        }),
        Command::Install(ticket) => {
            let Some(ticket) = state.tickets.remove(&ticket) else {
                state.pending.remove(&request.operation.id);
                return Err("插件候选不存在或已使用".into());
            };
            if ticket.root != root || Instant::now() >= ticket.expires {
                state.pending.remove(&request.operation.id);
                state.tickets.insert(ticket.handle.clone(), ticket);
                return Err("插件候选所属代次已改变或已过期，未安装".into());
            }
            state.claimed_stages.insert(ticket.stage.clone());
            Some(Work::Install {
                operation: request.operation.clone(),
                control: control.clone(),
                ticket,
            })
        }
        Command::Discard(ticket) => {
            let Some(ticket) = state.tickets.remove(&ticket) else {
                state.pending.remove(&request.operation.id);
                return Err("插件候选不存在或已使用".into());
            };
            if ticket.root != root {
                state.pending.remove(&request.operation.id);
                state.tickets.insert(ticket.handle.clone(), ticket);
                return Err("插件候选所属目录已改变，未回收".into());
            }
            state.claimed_stages.insert(ticket.stage.clone());
            Some(Work::Discard {
                operation: request.operation.clone(),
                control: control.clone(),
                ticket,
            })
        }
        Command::Remove { id, wipe } => Some(Work::Remove {
            operation: request.operation.clone(),
            root: root.clone(),
            control: control.clone(),
            id,
            wipe,
        }),
        Command::Pick => {
            drop(state);
            #[cfg(target_os = "android")]
            let result = device::pick(request.operation.clone(), root, control.clone());
            #[cfg(not(target_os = "android"))]
            let result: Result<(), String> = Err("当前平台没有Android插件包选择器".into());
            if let Err(error) = result {
                finish(&request.operation, &control, Err(error));
            }
            return Ok(());
        }
        Command::Cancel => None,
    };
    if let Some(work) = work {
        if let Err(error) = workers.io.try_send(work) {
            let work = match error {
                mpsc::TrySendError::Full(work) | mpsc::TrySendError::Disconnected(work) => work,
            };
            if let Work::Install { ticket, .. } | Work::Discard { ticket, .. } = work {
                state.claimed_stages.remove(&ticket.stage);
                state.tickets.insert(ticket.handle.clone(), ticket);
            }
            state.pending.remove(&request.operation.id);
            return Err("插件导入队列不可用，未开始".into());
        }
    }
    Ok(())
}
fn cancel_owned(key: &OperationKey, control: &Arc<Control>) {
    {
        let mut state = registry().lock().unwrap_or_else(|error| error.into_inner());
        if exact(&mut state, key, control).is_none() {
            return;
        }
        control.cancel.cancel();
    }
    #[cfg(target_os = "android")]
    {
        let mut state = registry().lock().unwrap_or_else(|error| error.into_inner());
        for picker in state.pickers.values_mut() {
            if picker.operation == *key && Arc::ptr_eq(&picker.control, control) {
                picker.cancelled = true;
            }
        }
        drop(state);
        let signal_guard = control
            .signal
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        let signal = signal_guard.clone();
        if let Some(signal) = signal {
            if !control.signal_admitted.swap(true, Ordering::AcqRel) {
                if let Some(Ok(workers)) = WORKERS.get() {
                    if workers
                        .cancellation
                        .try_send((key.clone(), control.clone(), signal))
                        .is_err()
                    {
                        control.signal_admitted.store(false, Ordering::Release);
                    }
                }
            }
        }
        drop(signal_guard);
    }
    if control
        .phase
        .compare_exchange(4, 5, Ordering::AcqRel, Ordering::Acquire)
        .is_ok()
    {
        finish(key, control, Ok(State::Cancelled));
    } else if !control.notice_sent.swap(true, Ordering::AcqRel) {
        registry()
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .results
            .push_back(Update {
                operation: key.clone(),
                state: State::WaitingForTerminal(
                    "已请求取消，等待实际文档I/O/关闭结束；未假定线程已结束".into(),
                ),
            });
    }
}
fn finish(key: &OperationKey, control: &Arc<Control>, result: Result<State, String>) {
    let result = match control
        .cleanup_error
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .take()
    {
        Some(error) => Err(format!("插件操作未完成；本操作目录清理未确认：{error}")),
        None => result,
    };
    let result = if control.cancel.is_cancelled() {
        match result {
            Ok(State::Prepared(review)) => {
                let ticket = registry()
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .tickets
                    .remove(&review.ticket);
                if let Some(ticket) = ticket {
                    let _preparation = PREPARATION
                        .lock()
                        .unwrap_or_else(|error| error.into_inner());
                    match ticket.receipt.retire(&ticket.root) {
                        Ok(()) => Ok(State::Cancelled),
                        Err(_) => Err("插件导入已取消，本操作准备目录清理未确认".into()),
                    }
                } else {
                    Ok(State::Cancelled)
                }
            }
            other => other,
        }
    } else {
        result
    };
    let mut state = registry().lock().unwrap_or_else(|error| error.into_inner());
    if control.cancel.is_cancelled() && matches!(&result, Ok(State::Prepared(_))) {
        drop(state);
        finish(key, control, result);
        return;
    }
    let Some(pending) = exact(&mut state, key, control) else {
        return;
    };
    if pending.result.is_some() {
        return;
    }
    pending.result = Some(result.unwrap_or_else(State::Failed));
    control.io_done.store(true, Ordering::Release);
    #[cfg(target_os = "android")]
    {
        let mut signal = control
            .signal
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if !control.signal_admitted.load(Ordering::Acquire) {
            *signal = None;
        }
    }
    drop(state);
    publish_terminal(key, control);
}
fn publish_terminal(key: &OperationKey, control: &Arc<Control>) {
    if !control.io_done.load(Ordering::Acquire) {
        return;
    }
    #[cfg(target_os = "android")]
    if control.signal_admitted.load(Ordering::Acquire)
        && !control.signal_done.load(Ordering::Acquire)
    {
        return;
    }
    let mut state = registry().lock().unwrap_or_else(|error| error.into_inner());
    if exact(&mut state, key, control).is_none() {
        return;
    }
    let Some(mut pending) = state.pending.remove(&key.id) else {
        return;
    };
    control.phase.store(6, Ordering::Release);
    state.results.push_back(Update {
        operation: pending.operation,
        state: pending
            .result
            .take()
            .unwrap_or_else(|| State::Failed("插件工作结果缺失".into())),
    });
}
pub(crate) fn poll(limit: usize) -> Vec<Update> {
    let mut state = registry().lock().unwrap_or_else(|error| error.into_inner());
    let count = limit.min(state.results.len());
    state.results.drain(..count).collect()
}
fn read_file(file: &File, control: &Control) -> Result<Vec<u8>, String> {
    let metadata = file.metadata().map_err(|_| "插件源文件状态无法确认")?;
    if !metadata.is_file() || metadata.len() > archive::MAX_COMPRESSED {
        return Err("插件源不是普通文件或超过64 MiB压缩包预算".into());
    }
    let mut file = file;
    let mut bytes = Vec::new();
    let mut chunk = [0; 64 * 1024];
    loop {
        control.check()?;
        let count = file.read(&mut chunk).map_err(|_| "插件源文件读取失败")?;
        if count == 0 {
            break;
        }
        if bytes
            .len()
            .checked_add(count)
            .is_none_or(|count| count as u64 > archive::MAX_COMPRESSED)
        {
            return Err("插件源文件超过64 MiB压缩包预算".into());
        }
        bytes.extend_from_slice(&chunk[..count]);
    }
    let after = file.metadata().map_err(|_| "插件源文件终态无法确认")?;
    if !super::same(&metadata, &after) || bytes.len() as u64 != metadata.len() {
        return Err("插件源文件读取时发生变化".into());
    }
    Ok(bytes)
}
fn prepare(
    key: &OperationKey,
    root: &RootKey,
    control: &Arc<Control>,
    bytes: &[u8],
) -> Result<State, String> {
    let _preparation = PREPARATION
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    root.validate()?;
    control.check()?;
    let recovery_warnings = stage::recover(root, control)?;
    let mut package = archive::read(
        bytes,
        &crate::store::reserved_plugin_names(),
        &control.cancel,
    )?;
    let id = package.manifest.id.clone();
    if root.path.join(&id).exists() {
        return Err("同ID插件已存在，未覆盖原环境变量或状态".into());
    }
    let host_state = serde_json::to_vec(&serde_json::json!({"enabled":false,"installed_at":0}))
        .map_err(|_| "插件初始状态编码失败")?;
    package
        .tree
        .files
        .insert(PathBuf::from("state.json"), host_state.clone());
    package.tree.layout.push(Layout {
        path: PathBuf::from("state.json"),
        directory: false,
        mode: 0o600,
        bytes: host_state.len() as u64,
        sha256: Some(hash(&host_state)),
    });
    let mut stages = 0usize;
    for entry in fs::read_dir(&root.path).map_err(|_| "插件缓存目录无法枚举")? {
        let entry = entry.map_err(|_| "插件缓存目录项无法确认")?;
        if entry.file_name().to_string_lossy().starts_with(".install-")
            && entry
                .file_type()
                .map_err(|_| "插件缓存项类型无法确认")?
                .is_dir()
        {
            stages += 1;
        }
        if stages >= CAPACITY {
            break;
        }
    }
    if stages >= CAPACITY {
        return Err("插件候选缓存已有四份（每份最多256 MiB）；请先取消或安装已准备的候选".into());
    }
    let stage = format!(".install-{}", crate::controller::unique_id());
    let stage_path = root.path.join(&stage);
    fs::create_dir(&stage_path).map_err(|_| "插件唯一准备目录创建失败")?;
    if mode(&stage_path, 0o700).is_err() {
        let _ = fs::remove_dir(&stage_path);
        return Err("插件唯一准备目录私有权限设置失败".into());
    }
    let mut receipt =
        match stage::Receipt::create(root, &stage, &id, key, package.tree.layout.clone()) {
            Ok(receipt) => receipt,
            Err(error) => {
                if fs::remove_dir(&stage_path).is_err() {
                    *control
                        .cleanup_error
                        .lock()
                        .unwrap_or_else(|error| error.into_inner()) =
                        Some("唯一空准备目录回收未确认".into());
                }
                return Err(error);
            }
        };
    let mut stage_guard = stage::Guard::new(root, receipt.clone(), control);
    write_tree(&stage_path, &package.tree, &control.cancel)?;
    receipt.seal(root)?;
    stage_guard.update(&receipt);
    control.check()?;
    let ticket = crate::controller::unique_id();
    let mut manifest = package.manifest;
    for env in &mut manifest.env {
        env.default_value = None;
    }
    let review = Review {
        ticket: ticket.clone(),
        id: id.clone(),
        manifest,
        files: package.tree.files.len(),
        directories: package
            .tree
            .layout
            .iter()
            .filter(|node| node.directory)
            .count(),
        bytes: package.tree.layout.iter().map(|node| node.bytes).sum(),
        warnings: package
            .tree
            .warnings
            .into_iter()
            .chain(recovery_warnings)
            .collect(),
    };
    let mut state = registry().lock().unwrap_or_else(|error| error.into_inner());
    if exact(&mut state, key, control).is_none() || control.cancel.is_cancelled() {
        drop(state);
        return Ok(State::Cancelled);
    }
    state.tickets.insert(
        ticket.clone(),
        Ticket {
            handle: ticket,
            operation: key.clone(),
            id,
            stage,
            root: root.clone(),
            layout: package.tree.layout,
            receipt,
            expires: Instant::now() + Duration::from_secs(24 * 60 * 60),
        },
    );
    stage_guard.release();
    Ok(State::Prepared(review))
}
fn install(ticket: &mut Ticket, control: &Control) -> Result<State, String> {
    let _preparation = PREPARATION
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    ticket.root.validate()?;
    control.check()?;
    ticket.receipt.verify(&ticket.root)?;
    let stage_path = ticket.root.path.join(&ticket.stage);
    let target = ticket.root.path.join(&ticket.id);
    if fs::symlink_metadata(&target).is_ok() {
        return Err("同ID插件已在审阅期间安装，未覆盖".into());
    }
    let temporary = stage_path.join(format!(".state-{}.tmp", crate::controller::unique_id()));
    let bytes = serde_json::to_vec(
        &serde_json::json!({"enabled":false,"installed_at":crate::controller::now_millis()}),
    )
    .map_err(|_| "插件确认状态编码失败")?;
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
    }
    let mut file = options
        .open(&temporary)
        .map_err(|_| "插件确认状态创建失败")?;
    file.write_all(&bytes).map_err(|_| "插件确认状态写入失败")?;
    file.sync_all().map_err(|_| "插件确认状态同步失败")?;
    mode(&temporary, 0o600)?;
    fs::rename(&temporary, stage_path.join("state.json")).map_err(|_| "插件确认状态提交失败")?;
    ticket.receipt.replace_state(&ticket.root, &bytes)?;
    if let Some(node) = ticket
        .layout
        .iter_mut()
        .find(|node| node.path == Path::new("state.json"))
    {
        node.bytes = bytes.len() as u64;
        node.sha256 = Some(hash(&bytes));
    }
    sync_directory(&stage_path)?;
    control.check()?;
    ticket.receipt.verify(&ticket.root)?;
    stage::rename_noreplace(&stage_path, &target)?;
    if sync_directory(&ticket.root.path).is_err() {
        return Ok(State::Uncertain {
            root: ticket.root.path.clone(),
            message: "插件目录已发布，但父目录同步未确认；写入已暂停，请重新打开应用核对".into(),
        });
    }
    if ticket.receipt.finish_publication(&ticket.root).is_err() {
        return Ok(State::Uncertain {
            root: ticket.root.path.clone(),
            message: "插件目录已发布，宿主回执收口未确认；写入已暂停，请重新打开应用核对".into(),
        });
    }
    Ok(State::Installed(ticket.id.clone()))
}

#[cfg(target_os = "android")]
mod device {
    use super::*;
    use crate::platform::android;
    use jni::{JValue, jni_sig, jni_str};
    use std::os::fd::{AsRawFd, FromRawFd};
    pub(super) fn pick(
        key: OperationKey,
        root: RootKey,
        control: Arc<Control>,
    ) -> Result<(), String> {
        let code = crate::platform::permissions::allocate_request_code()?;
        {
            let mut state = registry().lock().unwrap_or_else(|error| error.into_inner());
            if !state.pickers.is_empty() {
                return Err("已有插件包选择器尚未返回".into());
            }
            state.pickers.insert(
                code,
                Picker {
                    operation: key.clone(),
                    control: control.clone(),
                    root,
                    activity: None,
                    cancelled: false,
                    started: false,
                },
            );
        }
        let callback = control.clone();
        let callback_key = key.clone();
        let posted = crate::platform::android::post_to_main(move || {
            if callback
                .phase
                .compare_exchange(0, 3, Ordering::AcqRel, Ordering::Acquire)
                .is_err()
            {
                return;
            }
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                callback.check()?;
                if !android::activity_visible() {
                    return Err("插件包选择需要可见Activity".into());
                }
                let activity = android::current_activity().ok_or("插件包选择Activity已结束")?;
                android::invoke(|env| {
                    let action = env.new_string("android.intent.action.OPEN_DOCUMENT")?;
                    let intent = env.new_object(
                        jni_str!("android/content/Intent"),
                        jni_sig!("(Ljava/lang/String;)V"),
                        &[JValue::Object(&action)],
                    )?;
                    let mime = env.new_string("*/*")?;
                    env.call_method(
                        &intent,
                        jni_str!("setType"),
                        jni_sig!("(Ljava/lang/String;)Landroid/content/Intent;"),
                        &[JValue::Object(&mime)],
                    )?;
                    let category = env.new_string("android.intent.category.OPENABLE")?;
                    env.call_method(
                        &intent,
                        jni_str!("addCategory"),
                        jni_sig!("(Ljava/lang/String;)Landroid/content/Intent;"),
                        &[JValue::Object(&category)],
                    )?;
                    {
                        let mut state =
                            registry().lock().unwrap_or_else(|error| error.into_inner());
                        let picker = state
                            .pickers
                            .get_mut(&code)
                            .ok_or(jni::errors::Error::NullPtr("plugin picker cancelled"))?;
                        if callback.cancel.is_cancelled() {
                            return Err(jni::errors::Error::NullPtr("plugin picker cancelled"));
                        }
                        picker.activity = Some(activity.clone());
                        // Once the framework call is attempted, retain the code
                        // even if JNI reports failure: a late result may exist.
                        picker.started = true;
                    }
                    env.call_method(
                        activity.as_ref(),
                        jni_str!("startActivityForResult"),
                        jni_sig!("(Landroid/content/Intent;I)V"),
                        &[JValue::Object(&intent), JValue::Int(code)],
                    )?;
                    Ok(())
                })?;
                callback.phase.store(4, Ordering::Release);
                *callback
                    .deadline
                    .lock()
                    .unwrap_or_else(|error| error.into_inner()) =
                    Instant::now() + Duration::from_secs(600);
                if callback.cancel.is_cancelled() {
                    cancel_owned(&callback_key, &callback);
                }
                Ok(())
            }))
            .unwrap_or_else(|_| Err("插件选择主线程回调异常，未自动重试".into()));
            if let Err(error) = result {
                forget_unstarted(code, &callback_key, &callback);
                finish(&callback_key, &callback, Err(error));
            }
        });
        if let Err(error) = posted {
            forget_unstarted(code, &key, &control);
            return Err(error);
        }
        Ok(())
    }
    fn forget_unstarted(code: i32, key: &OperationKey, control: &Arc<Control>) {
        let mut state = registry().lock().unwrap_or_else(|error| error.into_inner());
        if state.pickers.get(&code).is_some_and(|picker| {
            !picker.started && picker.operation == *key && Arc::ptr_eq(&picker.control, control)
        }) {
            state.pickers.remove(&code);
        }
    }
    struct Parcel(Option<Object>);
    impl Drop for Parcel {
        fn drop(&mut self) {
            if let Some(pfd) = self.0.take() {
                let _ = android::invoke(|env| {
                    env.call_method(pfd.as_ref(), jni_str!("close"), jni_sig!("()V"), &[])?;
                    Ok(())
                });
            }
        }
    }
    pub(super) fn read_uri(
        key: &OperationKey,
        control: &Arc<Control>,
        uri: Object,
    ) -> Result<Vec<u8>, String> {
        control.check()?;
        let context = android::application_context()?;
        let signal = android::invoke(|env| {
            let signal = env.new_object(
                jni_str!("android/os/CancellationSignal"),
                jni_sig!("()V"),
                &[],
            )?;
            Ok(Arc::new(env.new_global_ref(signal)?))
        })?;
        *control
            .signal
            .lock()
            .unwrap_or_else(|error| error.into_inner()) = Some(signal.clone());
        if control.cancel.is_cancelled() {
            cancel_owned(key, control);
        }
        control.check()?;
        let pfd = android::invoke(|env| {
            let scheme = env
                .call_method(
                    uri.as_ref(),
                    jni_str!("getScheme"),
                    jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?
                .l()?;
            if scheme.is_null() || android::string_value(env, scheme, 32)? != "content" {
                return Err(jni::errors::Error::NullPtr("plugin requires content URI"));
            }
            let resolver = env
                .call_method(
                    context.as_ref(),
                    jni_str!("getContentResolver"),
                    jni_sig!("()Landroid/content/ContentResolver;"),
                    &[],
                )?
                .l()?;
            let mode = env.new_string("r")?;
            let pfd=env.call_method(&resolver,jni_str!("openFileDescriptor"),jni_sig!("(Landroid/net/Uri;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/os/ParcelFileDescriptor;"),&[JValue::Object(uri.as_ref()),JValue::Object(&mode),JValue::Object(signal.as_ref())])?.l()?;
            if pfd.is_null() {
                return Err(jni::errors::Error::NullPtr("plugin source descriptor"));
            }
            match env.new_global_ref(&pfd) {
                Ok(global) => Ok(Arc::new(global)),
                Err(error) => {
                    if env.exception_check() {
                        env.exception_clear();
                    }
                    let _ = env.call_method(&pfd, jni_str!("close"), jni_sig!("()V"), &[]);
                    if env.exception_check() {
                        env.exception_clear();
                    }
                    Err(error)
                }
            }
        })?;
        let mut parcel = Parcel(Some(pfd.clone()));
        let result = (|| {
            let raw = android::invoke(|env| {
                env.call_method(pfd.as_ref(), jni_str!("getFd"), jni_sig!("()I"), &[])?
                    .i()
            })?;
            let raw = unsafe { libc::fcntl(raw, libc::F_DUPFD_CLOEXEC, 0) };
            if raw < 0 {
                return Err("插件源描述符复制失败".into());
            }
            let file = unsafe { File::from_raw_fd(raw) };
            let flags = unsafe { libc::fcntl(raw, libc::F_GETFL) };
            if flags < 0 || unsafe { libc::fcntl(raw, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0
            {
                return Err("插件源不能非阻塞读取".into());
            }
            let mut bytes = Vec::new();
            let mut chunk = [0u8; 64 * 1024];
            loop {
                control.check()?;
                let count =
                    unsafe { libc::read(file.as_raw_fd(), chunk.as_mut_ptr().cast(), chunk.len()) };
                if count > 0 {
                    if bytes
                        .len()
                        .checked_add(count as usize)
                        .is_none_or(|total| total as u64 > archive::MAX_COMPRESSED)
                    {
                        return Err("插件文档超过64 MiB压缩包预算".into());
                    }
                    bytes.extend_from_slice(&chunk[..count as usize]);
                } else if count == 0 {
                    break;
                } else {
                    let error = std::io::Error::last_os_error();
                    if error.kind() == std::io::ErrorKind::Interrupted {
                        continue;
                    }
                    if error.kind() != std::io::ErrorKind::WouldBlock {
                        return Err("插件文档读取失败".into());
                    }
                    let mut poll = libc::pollfd {
                        fd: file.as_raw_fd(),
                        events: libc::POLLIN,
                        revents: 0,
                    };
                    unsafe {
                        libc::poll(&mut poll, 1, 40);
                    }
                }
            }
            drop(file);
            Ok(bytes)
        })();
        let closed = android::invoke(|env| {
            env.call_method(pfd.as_ref(), jni_str!("close"), jni_sig!("()V"), &[])?;
            Ok(())
        });
        closed?;
        parcel.0 = None;
        let mut source_signal = control
            .signal
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if !control.signal_admitted.load(Ordering::Acquire) {
            *source_signal = None;
        }
        drop(source_signal);
        result
    }
}
#[cfg(target_os = "android")]
pub(crate) fn on_activity_result(code: i32, result: i32, intent: Option<Object>) -> bool {
    let picker = registry()
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .pickers
        .remove(&code);
    let Some(picker) = picker else {
        return false;
    };
    if picker.cancelled {
        return true;
    }
    if result != -1 {
        finish(&picker.operation, &picker.control, Ok(State::Cancelled));
        return true;
    }
    let result = (|| {
        let intent = intent.ok_or("插件选择没有返回Intent")?;
        let uri = crate::platform::android::invoke(|env| {
            let uri = env
                .call_method(
                    intent.as_ref(),
                    jni::jni_str!("getData"),
                    jni::jni_sig!("()Landroid/net/Uri;"),
                    &[],
                )?
                .l()?;
            if uri.is_null() {
                return Err(jni::errors::Error::NullPtr("plugin URI"));
            }
            Ok(Arc::new(env.new_global_ref(uri)?))
        })?;
        if picker.control.cancel.is_cancelled() {
            return Err("插件导入已取消".into());
        }
        picker.control.phase.store(0, Ordering::Release);
        *picker
            .control
            .deadline
            .lock()
            .unwrap_or_else(|error| error.into_inner()) = Instant::now() + Duration::from_secs(120);
        workers()?
            .io
            .try_send(Work::ReadUri {
                operation: picker.operation.clone(),
                root: picker.root,
                control: picker.control.clone(),
                uri,
            })
            .map_err(|_| "插件读取队列已满".to_owned())
    })();
    if let Err(error) = result {
        finish(&picker.operation, &picker.control, Err(error));
    }
    true
}
#[cfg(target_os = "android")]
pub(crate) fn on_result_error(code: i32, error: &str) -> bool {
    let picker = registry()
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .pickers
        .remove(&code);
    let Some(picker) = picker else {
        return false;
    };
    if !picker.cancelled {
        finish(&picker.operation, &picker.control, Err(error.into()));
    }
    true
}
#[cfg(target_os = "android")]
pub(crate) fn activity_destroyed(
    env: &mut jni::Env,
    activity: &jni::objects::JObject,
) -> jni::errors::Result<()> {
    let mut state = registry().lock().unwrap_or_else(|error| error.into_inner());
    let mut codes = Vec::new();
    for (code, picker) in &state.pickers {
        if let Some(owner) = picker.activity.as_ref() {
            if env.is_same_object(owner.as_ref(), activity)? {
                codes.push(*code);
            }
        }
    }
    let pickers: Vec<_> = codes
        .into_iter()
        .filter_map(|code| state.pickers.remove(&code))
        .collect();
    drop(state);
    for picker in pickers {
        cancel_owned(&picker.operation, &picker.control);
    }
    Ok(())
}
