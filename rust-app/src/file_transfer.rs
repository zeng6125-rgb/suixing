//! Outbound file effects remain owned until the real worker and cleanup finish.
//! A provider can ignore CancellationSignal. Deadline then means cancellation
//! requested, not that a blocked Binder/write/fsync/close call has terminated.
use crate::{file_export::Snapshot, network::CancelToken, platform::permissions::OperationKey};
use std::{
    collections::{BTreeMap, VecDeque},
    path::PathBuf,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, AtomicU8, AtomicU64, Ordering},
        mpsc::{self, SyncSender},
    },
    time::{Duration, Instant},
};

const CAPACITY: usize = 16;
const IO_DEADLINE: Duration = Duration::from_secs(120);
const PICKER_DEADLINE: Duration = Duration::from_secs(600);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Kind {
    Share,
    Open,
    Save,
}
#[derive(Clone, Debug)]
pub(crate) struct Request {
    pub operation: OperationKey,
    pub kind: Kind,
    /// The actor replaces this with its current stable workspace before dispatch.
    pub workspace: PathBuf,
    pub path: String,
    pub history: Option<crate::conversation_export::Source>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum CleanupDisposition {
    NoSnapshot,
    Revoked,
    RetainedUntilExpiry,
}
#[derive(Clone, Debug)]
pub(crate) struct ResultEvent {
    pub operation: OperationKey,
    pub kind: Kind,
    pub result: Result<String, String>,
    pub snapshot: Option<Snapshot>,
    pub cancelled: bool,
    /// Set immediately before openFileDescriptor("wt"); a failed call may truncate.
    pub target_open_requested: bool,
    pub bytes_written: u64,
    pub cancellation_signal: Option<Result<(), String>>,
    pub cleanup: Result<CleanupDisposition, String>,
}
#[derive(Clone, Debug)]
pub(crate) enum Event {
    WaitingForTerminal {
        operation: OperationKey,
        kind: Kind,
        reason: String,
        target_open_requested: bool,
        bytes_written: u64,
    },
    Finished(ResultEvent),
}
#[derive(Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
enum Phase {
    QueuedPrepare,
    Preparing,
    LaunchQueued,
    Launching,
    Picker,
    QueuedCopy,
    Opening,
    Writing,
    Closing,
    Completing,
    Cleaning,
    Done,
}
#[cfg(target_os = "android")]
type Object = Arc<jni::refs::Global<jni::objects::JObject<'static>>>;
#[cfg(target_os = "android")]
#[derive(Default)]
struct SignalState {
    object: Option<Object>,
    scheduled: bool,
    finished: bool,
    result: Option<Result<(), String>>,
}
struct Control {
    cancel: Arc<CancelToken>,
    phase: AtomicU8,
    deadline: Mutex<Instant>,
    reason: Mutex<Option<String>>,
    target_open_requested: AtomicBool,
    bytes_written: AtomicU64,
    launch_started: AtomicBool,
    cleanup_scheduled: AtomicBool,
    #[cfg(target_os = "android")]
    signal: Mutex<SignalState>,
}
impl Control {
    fn new() -> Self {
        Self {
            cancel: Arc::new(CancelToken::new()),
            phase: AtomicU8::new(Phase::QueuedPrepare as u8),
            deadline: Mutex::new(Instant::now() + IO_DEADLINE),
            reason: Mutex::new(None),
            target_open_requested: AtomicBool::new(false),
            bytes_written: AtomicU64::new(0),
            launch_started: AtomicBool::new(false),
            cleanup_scheduled: AtomicBool::new(false),
            #[cfg(target_os = "android")]
            signal: Mutex::new(SignalState::default()),
        }
    }
    fn is(&self, phase: Phase) -> bool {
        self.phase.load(Ordering::Acquire) == phase as u8
    }
    fn claim(&self, from: Phase, to: Phase) -> bool {
        self.phase
            .compare_exchange(from as u8, to as u8, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
    }
    fn deadline(&self, duration: Duration) {
        *self.deadline.lock().unwrap_or_else(|e| e.into_inner()) = Instant::now() + duration;
    }
    fn expired(&self) -> bool {
        Instant::now() >= *self.deadline.lock().unwrap_or_else(|e| e.into_inner())
    }
    fn check(&self) -> Result<(), String> {
        if self.expired() {
            self.request_cancel("文件操作超过截止时间，正在等待实际执行与清理结束");
        }
        if self.cancel.is_cancelled() {
            Err(self
                .reason
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone()
                .unwrap_or_else(|| "文件操作已请求取消".into()))
        } else {
            Ok(())
        }
    }
    fn request_cancel(&self, reason: &str) {
        let mut previous = self.reason.lock().unwrap_or_else(|e| e.into_inner());
        if previous.is_none() {
            *previous = Some(reason.into());
        }
        self.cancel.cancel();
    }
}
struct Pending {
    request: Request,
    control: Arc<Control>,
    snapshot: Option<Snapshot>,
    result: Option<Result<String, String>>,
    notice: Option<String>,
    notice_delivered: bool,
}
#[cfg(target_os = "android")]
struct Picker {
    operation: OperationKey,
    control: Arc<Control>,
    cancelled: bool,
    activity: Option<Object>,
}
#[derive(Default)]
struct Registry {
    pending: BTreeMap<String, Pending>,
    results: VecDeque<ResultEvent>,
    /// One picker may coexist with independent copying operations. Cancelled
    /// codes stay owned until an actual result or the owning Activity ends.
    #[cfg(target_os = "android")]
    pickers: BTreeMap<i32, Picker>,
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(Default::default)
}
fn exact<'a>(
    state: &'a Registry,
    key: &OperationKey,
    control: &Arc<Control>,
) -> Option<&'a Pending> {
    state
        .pending
        .get(&key.id)
        .filter(|p| p.request.operation == *key && Arc::ptr_eq(&p.control, control))
}
fn exact_mut<'a>(
    state: &'a mut Registry,
    key: &OperationKey,
    control: &Arc<Control>,
) -> Option<&'a mut Pending> {
    state
        .pending
        .get_mut(&key.id)
        .filter(|p| p.request.operation == *key && Arc::ptr_eq(&p.control, control))
}
enum Work {
    Prepare {
        request: Request,
        control: Arc<Control>,
    },
    #[cfg(target_os = "android")]
    Copy {
        operation: OperationKey,
        control: Arc<Control>,
        snapshot: Snapshot,
        uri: Object,
    },
}
struct CleanupWork {
    operation: OperationKey,
    control: Arc<Control>,
    snapshot: Snapshot,
    revoke_grant: bool,
}
#[cfg(target_os = "android")]
struct CancelWork {
    operation: OperationKey,
    control: Arc<Control>,
    signal: Object,
}
struct Workers {
    io: SyncSender<Work>,
    cleanup: SyncSender<CleanupWork>,
    #[cfg(target_os = "android")]
    cancellation: SyncSender<CancelWork>,
}
static WORKERS: OnceLock<Result<Workers, String>> = OnceLock::new();
fn workers() -> Result<&'static Workers, String> {
    WORKERS
        .get_or_init(|| {
            let (io, receive) = mpsc::sync_channel::<Work>(CAPACITY);
            let receive = Arc::new(Mutex::new(receive));
            for index in 0..2 {
                let receive = receive.clone();
                std::thread::Builder::new()
                    .name(format!("rust-file-io-{index}"))
                    .spawn(move || {
                        loop {
                            let work = receive.lock().unwrap_or_else(|e| e.into_inner()).recv();
                            let Ok(work) = work else {
                                break;
                            };
                            match work {
                                Work::Prepare { request, control } => {
                                    if !control.claim(Phase::QueuedPrepare, Phase::Preparing) {
                                        continue;
                                    }
                                    let key = request.operation.clone();
                                    let result = std::panic::catch_unwind(
                                        std::panic::AssertUnwindSafe(|| {
                                            prepare(request, control.clone())
                                        }),
                                    )
                                    .unwrap_or_else(|_| {
                                        Err("文件准备异常，已开始的外部效果需要核对".into())
                                    });
                                    if let Err(error) = result {
                                        complete(&key, &control, Err(error));
                                    }
                                }
                                #[cfg(target_os = "android")]
                                Work::Copy {
                                    operation,
                                    control,
                                    snapshot,
                                    uri,
                                } => {
                                    if !control.claim(Phase::QueuedCopy, Phase::Opening) {
                                        continue;
                                    }
                                    let result = std::panic::catch_unwind(
                                        std::panic::AssertUnwindSafe(|| {
                                            device::copy_to_uri(
                                                &operation, &control, &snapshot, uri,
                                            )
                                        }),
                                    )
                                    .unwrap_or_else(|_| {
                                        Err("保存工作异常，目标可能部分写入，请核对".into())
                                    });
                                    complete(&operation, &control, result);
                                }
                            }
                        }
                    })
                    .map_err(|_| "文件I/O线程不可用".to_owned())?;
            }
            let (cleanup, receive) = mpsc::sync_channel::<CleanupWork>(CAPACITY);
            std::thread::Builder::new()
                .name("rust-file-cleanup".into())
                .spawn(move || {
                    while let Ok(work) = receive.recv() {
                        if !work.control.claim(Phase::Completing, Phase::Cleaning) {
                            continue;
                        }
                        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                            #[cfg(target_os = "android")]
                            if work.revoke_grant {
                                device::revoke_grant(&work.snapshot)?;
                            }
                            #[cfg(target_os = "android")]
                            let cache = crate::platform::android::paths()
                                .ok_or("文件清理平台路径未初始化")?
                                .cache_dir;
                            #[cfg(not(target_os = "android"))]
                            let cache = PathBuf::new();
                            crate::file_export::revoke(&cache, &work.snapshot)?;
                            Ok(CleanupDisposition::Revoked)
                        }))
                        .unwrap_or_else(|_| {
                            Err("本操作快照清理异常，不能确认权限或缓存已撤销".into())
                        });
                        terminal(&work.operation, &work.control, result);
                    }
                })
                .map_err(|_| "文件清理线程不可用".to_owned())?;
            #[cfg(target_os = "android")]
            let cancellation = {
                let (sender, receive) = mpsc::sync_channel::<CancelWork>(CAPACITY);
                std::thread::Builder::new()
                    .name("rust-file-cancellation".into())
                    .spawn(move || {
                        while let Ok(work) = receive.recv() {
                            // The original pending slot remains retained while cancel()
                            // itself is blocked; no replacement thread is created.
                            let belongs = exact(
                                &registry().lock().unwrap_or_else(|e| e.into_inner()),
                                &work.operation,
                                &work.control,
                            )
                            .is_some();
                            let result = if belongs {
                                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                                    device::cancel_signal(&work.signal)
                                }))
                                .unwrap_or_else(|_| {
                                    Err("文档提供方取消调用异常，实际I/O终态仍需等待".into())
                                })
                            } else {
                                Err("取消回调所属操作已结束".into())
                            };
                            let mut signal = work
                                .control
                                .signal
                                .lock()
                                .unwrap_or_else(|e| e.into_inner());
                            signal.finished = true;
                            signal.result = Some(result);
                            drop(signal);
                            schedule_cleanup(&work.operation, &work.control);
                        }
                    })
                    .map_err(|_| "文件取消线程不可用".to_owned())?;
                sender
            };
            std::thread::Builder::new()
                .name("rust-file-watchdog".into())
                .spawn(|| {
                    loop {
                        watchdog();
                        std::thread::sleep(Duration::from_millis(100));
                    }
                })
                .map_err(|_| "文件截止时间监控线程不可用".to_owned())?;
            Ok(Workers {
                io,
                cleanup,
                #[cfg(target_os = "android")]
                cancellation,
            })
        })
        .as_ref()
        .map_err(Clone::clone)
}

pub(crate) fn rejected(request: &Request, error: String) -> Event {
    Event::Finished(ResultEvent {
        operation: request.operation.clone(),
        kind: request.kind,
        result: Err(error),
        snapshot: None,
        cancelled: false,
        target_open_requested: false,
        bytes_written: 0,
        cancellation_signal: None,
        cleanup: Ok(CleanupDisposition::NoSnapshot),
    })
}
pub(crate) fn request(request: Request) -> Result<(), String> {
    request.operation.validate()?;
    if request.history.as_ref().is_some_and(|history| {
        request.kind != Kind::Save
            || request.operation.conversation_id.as_deref()
                != Some(history.0.conversation.id.as_str())
    }) {
        return Err("会话导出来源与保存票据不一致".into());
    }
    if request.path.is_empty()
        || request.path.len() > 4096
        || request.path.contains('\0')
        || !request.workspace.is_absolute()
    {
        return Err("文件导出路径无效".into());
    }
    let workers = workers()?;
    let control = Arc::new(Control::new());
    let key = request.operation.clone();
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    if state.pending.len() + state.results.len() >= CAPACITY
        || state.pending.contains_key(&key.id)
        || state
            .results
            .iter()
            .any(|result| result.operation.id == key.id)
    {
        return Err("文件导出队列已满或身份重复".into());
    }
    state.pending.insert(
        key.id.clone(),
        Pending {
            request: request.clone(),
            control: control.clone(),
            snapshot: None,
            result: None,
            notice: None,
            notice_delivered: false,
        },
    );
    // No blocking send and no disk/Binder work runs on the actor.
    if workers
        .io
        .try_send(Work::Prepare {
            request,
            control: control.clone(),
        })
        .is_err()
    {
        state.pending.remove(&key.id);
        return Err("文件导出队列不可用，未开始".into());
    }
    Ok(())
}
pub(crate) fn cancel(operation: &OperationKey) -> bool {
    let control = {
        let state = registry().lock().unwrap_or_else(|e| e.into_inner());
        let control = state
            .pending
            .get(&operation.id)
            .filter(|pending| pending.request.operation == *operation)
            .map(|p| p.control.clone());
        if let Some(control) = control.as_ref() {
            control.request_cancel("用户已请求取消，正在等待实际执行与清理结束");
        }
        control
    };
    let Some(control) = control else {
        return false;
    };
    cancel_control(
        operation,
        &control,
        "用户已请求取消，正在等待实际执行与清理结束",
    );
    true
}
pub(crate) fn cancel_conversation(conversation: &str) {
    let owned: Vec<_> = registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .pending
        .values()
        .filter(|p| p.request.operation.conversation_id.as_deref() == Some(conversation))
        .map(|p| (p.request.operation.clone(), p.control.clone()))
        .collect();
    for (key, control) in owned {
        cancel_control(
            &key,
            &control,
            "所属会话已删除，正在撤销本操作并等待实际结束",
        );
    }
}
fn cancel_control(key: &OperationKey, control: &Arc<Control>, reason: &str) {
    control.request_cancel(reason);
    let queued = control.claim(Phase::QueuedPrepare, Phase::Completing)
        || control.claim(Phase::LaunchQueued, Phase::Completing)
        || control.claim(Phase::QueuedCopy, Phase::Completing)
        || control.claim(Phase::Picker, Phase::Completing);
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    #[cfg(target_os = "android")]
    state.pickers.retain(|_, picker| {
        if picker.operation != *key || !Arc::ptr_eq(&picker.control, control) {
            return true;
        }
        picker.cancelled = true;
        control.launch_started.load(Ordering::Acquire)
    });
    if let Some(pending) = exact_mut(&mut state, key, control) {
        if queued && pending.result.is_none() {
            pending.result = Some(Err("文件操作已取消；未向保存目标写入".into()));
        }
        if pending.notice.is_none() {
            pending.notice = Some(reason.into());
        }
    }
    drop(state);
    schedule_signal(key, control);
    if queued {
        schedule_cleanup(key, control);
    }
}
#[cfg(target_os = "android")]
fn schedule_signal(key: &OperationKey, control: &Arc<Control>) {
    if !control.cancel.is_cancelled() {
        return;
    }
    let Some(Ok(workers)) = WORKERS.get() else {
        return;
    };
    let mut state = control.signal.lock().unwrap_or_else(|e| e.into_inner());
    if state.scheduled || state.finished {
        return;
    }
    let Some(signal) = state.object.clone() else {
        return;
    };
    // Mark under the same lock as publication; cancellation cannot miss a
    // signal installed concurrently with a watchdog/user request.
    state.scheduled = true;
    if workers
        .cancellation
        .try_send(CancelWork {
            operation: key.clone(),
            control: control.clone(),
            signal,
        })
        .is_err()
    {
        state.scheduled = false; // bounded watchdog retry, not a new worker
    }
}
#[cfg(not(target_os = "android"))]
fn schedule_signal(_key: &OperationKey, _control: &Arc<Control>) {}
fn complete(key: &OperationKey, control: &Arc<Control>, result: Result<String, String>) {
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    let Some(pending) = exact_mut(&mut state, key, control) else {
        return;
    };
    if matches!(control.phase.load(Ordering::Acquire), p if p == Phase::Cleaning as u8 || p == Phase::Done as u8)
        || pending.result.is_some()
    {
        return;
    }
    // A late cancellation does not overwrite a real completed write/close.
    pending.result = Some(result);
    #[cfg(target_os = "android")]
    {
        // The real I/O (including PFD close) has returned. Cancel only an
        // already admitted cancellation call; do not initiate a fresh Binder
        // cancellation for a descriptor whose actual worker has ended.
        let mut signal = control.signal.lock().unwrap_or_else(|e| e.into_inner());
        if !signal.scheduled {
            signal.object = None;
        }
    }
    control
        .phase
        .store(Phase::Completing as u8, Ordering::Release);
    drop(state);
    schedule_signal(key, control);
    schedule_cleanup(key, control);
}
fn schedule_cleanup(key: &OperationKey, control: &Arc<Control>) {
    if !control.is(Phase::Completing) {
        return;
    }
    #[cfg(target_os = "android")]
    {
        let signal = control.signal.lock().unwrap_or_else(|e| e.into_inner());
        if signal.scheduled && !signal.finished {
            return;
        }
        if control.cancel.is_cancelled() && signal.object.is_some() && !signal.finished {
            return;
        }
    }
    let state = registry().lock().unwrap_or_else(|e| e.into_inner());
    let Some(pending) = exact(&state, key, control) else {
        return;
    };
    let Some(result) = pending.result.as_ref() else {
        return;
    };
    let Some(snapshot) = pending.snapshot.clone() else {
        drop(state);
        terminal(key, control, Ok(CleanupDisposition::NoSnapshot));
        return;
    };
    let retain =
        pending.request.kind != Kind::Save && result.is_ok() && !control.cancel.is_cancelled();
    if retain {
        drop(state);
        terminal(key, control, Ok(CleanupDisposition::RetainedUntilExpiry));
        return;
    }
    let revoke_grant =
        pending.request.kind != Kind::Save && control.launch_started.load(Ordering::Acquire);
    drop(state);
    if control.cleanup_scheduled.swap(true, Ordering::AcqRel) {
        return;
    }
    match WORKERS.get() {
        Some(Ok(workers)) => {
            if workers
                .cleanup
                .try_send(CleanupWork {
                    operation: key.clone(),
                    control: control.clone(),
                    snapshot,
                    revoke_grant,
                })
                .is_err()
            {
                control.cleanup_scheduled.store(false, Ordering::Release);
            }
        }
        _ => terminal(
            key,
            control,
            Err("本操作快照尚未回收：清理线程不可用".into()),
        ),
    }
}
fn terminal(
    key: &OperationKey,
    control: &Arc<Control>,
    cleanup: Result<CleanupDisposition, String>,
) {
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    if exact(&state, key, control).is_none() {
        return;
    }
    if cleanup == Ok(CleanupDisposition::RetainedUntilExpiry) && control.cancel.is_cancelled() {
        // Cancellation admitted before this terminal publication must revoke
        // the exact successful Share/Open grant instead of retaining it.
        drop(state);
        schedule_cleanup(key, control);
        return;
    }
    let pending = state
        .pending
        .remove(&key.id)
        .expect("verified owned pending");
    control.phase.store(Phase::Done as u8, Ordering::Release);
    #[cfg(target_os = "android")]
    let cancellation_signal = control
        .signal
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .result
        .clone();
    #[cfg(not(target_os = "android"))]
    let cancellation_signal = None;
    state.results.push_back(ResultEvent {
        operation: pending.request.operation,
        kind: pending.request.kind,
        result: pending
            .result
            .unwrap_or_else(|| Err("文件工作实际结果缺失".into())),
        snapshot: pending.snapshot,
        cancelled: control.cancel.is_cancelled(),
        target_open_requested: control.target_open_requested.load(Ordering::Acquire),
        bytes_written: control.bytes_written.load(Ordering::Acquire),
        cancellation_signal,
        cleanup,
    });
}
fn watchdog() {
    let pending: Vec<_> = registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .pending
        .values()
        .map(|p| (p.request.operation.clone(), p.control.clone()))
        .collect();
    for (key, control) in pending {
        if control.expired() {
            cancel_control(
                &key,
                &control,
                "文件操作超过截止时间，正在等待真实I/O、关闭或清理终态",
            );
        } else if control.cancel.is_cancelled() {
            schedule_signal(&key, &control);
        }
        schedule_cleanup(&key, &control);
    }
}
pub(crate) fn poll(limit: usize) -> Vec<Event> {
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    let count = limit.min(CAPACITY * 2).min(state.results.len());
    let mut events: Vec<_> = state.results.drain(..count).map(Event::Finished).collect();
    for pending in state.pending.values_mut() {
        if events.len() >= limit.min(CAPACITY * 2) {
            break;
        }
        if !pending.notice_delivered {
            if let Some(reason) = pending.notice.as_ref() {
                pending.notice_delivered = true;
                events.push(Event::WaitingForTerminal {
                    operation: pending.request.operation.clone(),
                    kind: pending.request.kind,
                    reason: reason.clone(),
                    target_open_requested: pending
                        .control
                        .target_open_requested
                        .load(Ordering::Acquire),
                    bytes_written: pending.control.bytes_written.load(Ordering::Acquire),
                });
            }
        }
    }
    events
}
#[cfg(not(target_os = "android"))]
fn prepare(_request: Request, _control: Arc<Control>) -> Result<(), String> {
    Err("当前平台没有Android分享与文档保存入口".into())
}

#[cfg(target_os = "android")]
fn prepare(request: Request, control: Arc<Control>) -> Result<(), String> {
    control.check()?;
    let paths = crate::platform::android::paths().ok_or("平台文件路径未初始化")?;
    let snapshot = if let Some(history) = request.history.as_ref() {
        crate::file_export::prepare_history(&paths.cache_dir, history, &control.cancel)?
    } else {
        crate::file_export::prepare(
            &paths.cache_dir,
            &request.workspace,
            &request.path,
            &control.cancel,
        )?
    };
    {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        exact_mut(&mut state, &request.operation, &control)
            .ok_or("文件导出所属操作已结束")?
            .snapshot = Some(snapshot.clone());
    }
    control.check()?;
    let code = if request.kind == Kind::Save {
        Some(crate::platform::permissions::allocate_request_code()?)
    } else {
        None
    };
    {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        if let Some(code) = code {
            if !state.pickers.is_empty() {
                return Err("已有保存选择器尚未实际结束，请先返回该页面".into());
            }
            state.pickers.insert(
                code,
                Picker {
                    operation: request.operation.clone(),
                    control: control.clone(),
                    cancelled: false,
                    activity: None,
                },
            );
        }
        control.deadline(Duration::from_secs(10));
        control
            .phase
            .store(Phase::LaunchQueued as u8, Ordering::Release);
    }
    let operation = request.operation.clone();
    let callback_key = operation.clone();
    let callback_control = control.clone();
    let result = crate::platform::main_call::run(&operation, control.cancel.clone(), move || {
        if !callback_control.claim(Phase::LaunchQueued, Phase::Launching) {
            return Err("文件页面派发前已取消".into());
        }
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            device::launch(&request, &snapshot, code, &callback_control)
        }))
        .unwrap_or_else(|_| Err("文件页面派发异常，外部效果需要核对".into()));
        complete_launch(&callback_key, &callback_control, code, result.clone());
        result
    });
    if let Err(error) = result {
        // main_call can time out after admission. Only this CAS proves that the
        // actual effect has not started; the closure owns every admitted end.
        if control.claim(Phase::LaunchQueued, Phase::Completing) {
            remove_unlaunched_picker(&operation, &control);
            complete(&operation, &control, Err(error));
        } else if control.is(Phase::Launching) {
            let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
            if let Some(pending) = exact_mut(&mut state, &operation, &control) {
                pending.notice = Some(error);
            }
        }
    }
    Ok(())
}
#[cfg(target_os = "android")]
fn remove_unlaunched_picker(key: &OperationKey, control: &Arc<Control>) {
    if control.launch_started.load(Ordering::Acquire) {
        return;
    }
    registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .pickers
        .retain(|_, p| p.operation != *key || !Arc::ptr_eq(&p.control, control));
}
#[cfg(target_os = "android")]
fn complete_launch(
    key: &OperationKey,
    control: &Arc<Control>,
    code: Option<i32>,
    result: Result<(), String>,
) {
    if let Err(error) = result {
        remove_unlaunched_picker(key, control);
        // A failing startActivity Binder call may already have dispatched. Its
        // request-code tombstone is retained when launch_started is true.
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        for picker in state.pickers.values_mut() {
            if picker.operation == *key && Arc::ptr_eq(&picker.control, control) {
                picker.cancelled = true;
            }
        }
        drop(state);
        complete(key, control, Err(error));
        return;
    }
    if code.is_none() {
        let kind = exact(
            &registry().lock().unwrap_or_else(|e| e.into_inner()),
            key,
            control,
        )
        .map(|p| p.request.kind);
        complete(
            key,
            control,
            Ok(if kind == Some(Kind::Share) {
                "分享选择器已打开；接收方是否读取或发送尚未确认"
            } else {
                "文件打开请求已交给系统；外部应用实际显示尚未确认"
            }
            .into()),
        );
    } else {
        {
            let state = registry().lock().unwrap_or_else(|e| e.into_inner());
            if exact(&state, key, control).is_none() {
                return;
            }
            if !control.claim(Phase::Launching, Phase::Picker) {
                return;
            }
            control.deadline(PICKER_DEADLINE);
        }
        if control.cancel.is_cancelled() {
            cancel_control(key, control, "保存选择器已请求取消；迟到返回不会写入目标");
        }
    }
}
#[cfg(target_os = "android")]
pub(crate) fn on_activity_result(code: i32, result: i32, intent: Option<Object>) -> bool {
    let owned = {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        let Some(picker) = state.pickers.remove(&code) else {
            return false;
        };
        if picker.cancelled || picker.control.cancel.is_cancelled() {
            return true;
        }
        let Some(pending) = exact(&state, &picker.operation, &picker.control) else {
            return true;
        };
        let Some(snapshot) = pending.snapshot.clone() else {
            drop(state);
            complete(
                &picker.operation,
                &picker.control,
                Err("保存快照缺失，未写入目标".into()),
            );
            return true;
        };
        // Android normally calls after startActivityForResult returns. Accept
        // an actual reentrant result only while the effect is owned as well.
        if !picker.control.claim(Phase::Picker, Phase::QueuedCopy)
            && !picker.control.claim(Phase::Launching, Phase::QueuedCopy)
        {
            return true;
        }
        picker.control.deadline(IO_DEADLINE); // includes queue/open/write/fsync/close
        (picker.operation, picker.control, snapshot)
    };
    let (operation, control, snapshot) = owned;
    let result = (|| {
        if result != -1 {
            return Err("已取消选择保存位置；未写入目标文件".to_owned());
        }
        let intent = intent.ok_or("保存位置没有返回内容URI")?;
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
                return Err(jni::errors::Error::NullPtr("document uri"));
            }
            Ok(Arc::new(env.new_global_ref(uri)?))
        })?;
        let Some(Ok(workers)) = WORKERS.get() else {
            return Err("文件保存线程不可用，未写入目标".into());
        };
        workers
            .io
            .try_send(Work::Copy {
                operation: operation.clone(),
                control: control.clone(),
                snapshot,
                uri,
            })
            .map_err(|_| "文件保存队列不可用，未写入目标".to_owned())
    })();
    if let Err(error) = result {
        complete(&operation, &control, Err(error));
    }
    true
}
#[cfg(target_os = "android")]
pub(crate) fn on_result_error(code: i32, error: &str) -> bool {
    let picker = registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .pickers
        .remove(&code);
    let Some(picker) = picker else {
        return false;
    };
    if !picker.cancelled {
        complete(&picker.operation, &picker.control, Err(error.into()));
    }
    true
}
#[cfg(target_os = "android")]
pub(crate) fn activity_destroyed(
    env: &mut jni::Env,
    activity: &jni::objects::JObject,
) -> jni::errors::Result<()> {
    let owned = {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        let mut codes = Vec::new();
        for (code, picker) in &state.pickers {
            if let Some(owner) = picker.activity.as_ref() {
                if env.is_same_object(owner.as_ref(), activity)? {
                    codes.push(*code);
                }
            }
        }
        codes
            .into_iter()
            .filter_map(|code| state.pickers.remove(&code))
            .collect::<Vec<_>>()
    };
    for picker in owned {
        cancel_control(
            &picker.operation,
            &picker.control,
            "保存页面所属Activity已销毁；迟到返回不会开始写入",
        );
    }
    Ok(())
}

#[cfg(target_os = "android")]
mod device {
    use super::*;
    use crate::platform::android;
    use jni::{JValue, jni_sig, jni_str};
    use std::{
        io::Read,
        os::fd::{AsRawFd, FromRawFd, IntoRawFd},
    };

    fn export_uri<'local>(
        env: &mut jni::Env<'local>,
        snapshot: &Snapshot,
        package: &str,
    ) -> jni::errors::Result<jni::objects::JObject<'local>> {
        let builder = env.new_object(jni_str!("android/net/Uri$Builder"), jni_sig!("()V"), &[])?;
        for (name, value) in [
            ("scheme", "content".to_owned()),
            ("authority", format!("{package}.rustfiles")),
            ("appendPath", snapshot.token.clone()),
            ("appendPath", snapshot.name.clone()),
        ] {
            let value = env.new_string(value)?;
            env.call_method(
                &builder,
                jni::strings::JNIString::new(name),
                jni_sig!("(Ljava/lang/String;)Landroid/net/Uri$Builder;"),
                &[JValue::Object(&value)],
            )?;
        }
        env.call_method(
            &builder,
            jni_str!("build"),
            jni_sig!("()Landroid/net/Uri;"),
            &[],
        )?
        .l()
    }
    pub(super) fn revoke_grant(snapshot: &Snapshot) -> Result<(), String> {
        let context = android::application_context()?;
        let paths = android::paths().ok_or("撤销分享权限时平台路径不可用")?;
        android::invoke(|env| {
            let uri = export_uri(env, snapshot, &paths.package_name)?;
            env.call_method(
                context.as_ref(),
                jni_str!("revokeUriPermission"),
                jni_sig!("(Landroid/net/Uri;I)V"),
                &[JValue::Object(&uri), JValue::Int(1)],
            )?;
            Ok(())
        })
        .map_err(|_| "本操作内容URI读取权限撤销未确认；快照暂保留".into())
    }
    pub(super) fn cancel_signal(signal: &Object) -> Result<(), String> {
        android::invoke(|env| {
            env.call_method(signal.as_ref(), jni_str!("cancel"), jni_sig!("()V"), &[])?;
            Ok(())
        })
        .map_err(|_| "文档提供方CancellationSignal取消调用失败，仍等待真实I/O结束".into())
    }
    fn begin_launch(
        request: &Request,
        control: &Arc<Control>,
        activity: &Object,
    ) -> Result<(), String> {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        exact(&state, &request.operation, control).ok_or("文件页面所属操作已结束")?;
        control.check()?;
        // Serialized with cancellation's picker-marker retirement.
        control.launch_started.store(true, Ordering::Release);
        for picker in state.pickers.values_mut() {
            if picker.operation == request.operation && Arc::ptr_eq(&picker.control, control) {
                picker.activity = Some(activity.clone());
            }
        }
        Ok(())
    }
    pub(super) fn launch(
        request: &Request,
        snapshot: &Snapshot,
        code: Option<i32>,
        control: &Arc<Control>,
    ) -> Result<(), String> {
        control.check()?;
        if !android::activity_visible() {
            return Err("分享和保存需要可见页面，请重新点击文件卡".into());
        }
        let activity = android::current_activity().ok_or("可见Activity已结束")?;
        let paths = android::paths().ok_or("应用路径未初始化")?;
        android::invoke(|env| {
            let action = env.new_string(match request.kind {
                Kind::Share => "android.intent.action.SEND",
                Kind::Open => "android.intent.action.VIEW",
                Kind::Save => "android.intent.action.CREATE_DOCUMENT",
            })?;
            let intent = env.new_object(
                jni_str!("android/content/Intent"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[JValue::Object(&action)],
            )?;
            let mime = env.new_string(&snapshot.mime)?;
            let label = env.new_string(&snapshot.name)?;
            if request.kind == Kind::Save {
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
                let key = env.new_string("android.intent.extra.TITLE")?;
                env.call_method(
                    &intent,
                    jni_str!("putExtra"),
                    jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;"),
                    &[JValue::Object(&key), JValue::Object(&label)],
                )?;
                begin_launch(request, control, &activity)
                    .map_err(|_| jni::errors::Error::NullPtr("file launch cancelled"))?;
                env.call_method(
                    activity.as_ref(),
                    jni_str!("startActivityForResult"),
                    jni_sig!("(Landroid/content/Intent;I)V"),
                    &[
                        JValue::Object(&intent),
                        JValue::Int(
                            code.ok_or(jni::errors::Error::NullPtr("document request code"))?,
                        ),
                    ],
                )?;
                return Ok(());
            }
            let uri = export_uri(env, snapshot, &paths.package_name)?;
            if request.kind == Kind::Share {
                // ACTION_SEND transports its URI in EXTRA_STREAM/ClipData;
                // ACTION_VIEW uses setDataAndType. Both grants stay read-only.
                env.call_method(
                    &intent,
                    jni_str!("setType"),
                    jni_sig!("(Ljava/lang/String;)Landroid/content/Intent;"),
                    &[JValue::Object(&mime)],
                )?;
                let key = env.new_string("android.intent.extra.STREAM")?;
                env.call_method(
                    &intent,
                    jni_str!("putExtra"),
                    jni_sig!("(Ljava/lang/String;Landroid/os/Parcelable;)Landroid/content/Intent;"),
                    &[JValue::Object(&key), JValue::Object(&uri)],
                )?;
            } else {
                env.call_method(
                    &intent,
                    jni_str!("setDataAndType"),
                    jni_sig!("(Landroid/net/Uri;Ljava/lang/String;)Landroid/content/Intent;"),
                    &[JValue::Object(&uri), JValue::Object(&mime)],
                )?;
            }
            let clip = env
                .call_static_method(
                    jni_str!("android/content/ClipData"),
                    jni_str!("newRawUri"),
                    jni_sig!(
                        "(Ljava/lang/CharSequence;Landroid/net/Uri;)Landroid/content/ClipData;"
                    ),
                    &[JValue::Object(&label), JValue::Object(&uri)],
                )?
                .l()?;
            env.call_method(
                &intent,
                jni_str!("setClipData"),
                jni_sig!("(Landroid/content/ClipData;)V"),
                &[JValue::Object(&clip)],
            )?;
            env.call_method(
                &intent,
                jni_str!("addFlags"),
                jni_sig!("(I)Landroid/content/Intent;"),
                &[JValue::Int(1)],
            )?;
            let chooser = env
                .call_static_method(
                    jni_str!("android/content/Intent"),
                    jni_str!("createChooser"),
                    jni_sig!(
                        "(Landroid/content/Intent;Ljava/lang/CharSequence;)Landroid/content/Intent;"
                    ),
                    &[JValue::Object(&intent), JValue::Object(&label)],
                )?
                .l()?;
            begin_launch(request, control, &activity)
                .map_err(|_| jni::errors::Error::NullPtr("file launch cancelled"))?;
            env.call_method(
                activity.as_ref(),
                jni_str!("startActivity"),
                jni_sig!("(Landroid/content/Intent;)V"),
                &[JValue::Object(&chooser)],
            )?;
            Ok(())
        })
    }

    struct Parcel {
        pfd: Option<Object>,
        control: Arc<Control>,
    }
    impl Parcel {
        fn close(&mut self) -> Result<(), String> {
            self.control
                .phase
                .store(Phase::Closing as u8, Ordering::Release);
            let Some(pfd) = self.pfd.take() else {
                return Ok(());
            };
            android::invoke(|env| {
                env.call_method(pfd.as_ref(), jni_str!("close"), jni_sig!("()V"), &[])?;
                Ok(())
            })
            .map_err(|_| "保存目标close已返回错误，关闭是否成功未确认".into())
        }
    }
    impl Drop for Parcel {
        fn drop(&mut self) {
            let _ = self.close();
        }
    }
    pub(super) fn copy_to_uri(
        key: &OperationKey,
        control: &Arc<Control>,
        snapshot: &Snapshot,
        uri: Object,
    ) -> Result<String, String> {
        control.check()?;
        let paths = android::paths().ok_or("平台文件路径未初始化")?;
        let (record, mut source) = crate::file_export::load(&paths.cache_dir, &snapshot.token)?;
        if record != *snapshot {
            return Err("文件保存快照与原请求不匹配".into());
        }
        let context = android::application_context()?;
        let signal = android::invoke(|env| {
            let signal = env.new_object(
                jni_str!("android/os/CancellationSignal"),
                jni_sig!("()V"),
                &[],
            )?;
            Ok(Arc::new(env.new_global_ref(signal)?))
        })?;
        {
            control
                .signal
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .object = Some(signal.clone());
        }
        schedule_signal(key, control);
        control.check()?;
        let opened = android::invoke(|env| {
            let scheme = env.call_method(uri.as_ref(), jni_str!("getScheme"), jni_sig!("()Ljava/lang/String;"), &[])?.l()?;
            if scheme.is_null() || android::string_value(env, scheme, 32)? != "content" { return Err(jni::errors::Error::NullPtr("save requires content URI")); }
            let resolver = env.call_method(context.as_ref(), jni_str!("getContentResolver"), jni_sig!("()Landroid/content/ContentResolver;"), &[])?.l()?;
            let mode = env.new_string("wt")?;
            control.check().map_err(|_| jni::errors::Error::NullPtr("save cancelled before open"))?;
            control.target_open_requested.store(true, Ordering::Release);
            let pfd = env.call_method(&resolver, jni_str!("openFileDescriptor"), jni_sig!("(Landroid/net/Uri;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/os/ParcelFileDescriptor;"), &[JValue::Object(uri.as_ref()), JValue::Object(&mode), JValue::Object(signal.as_ref())])?.l()?;
            if pfd.is_null() { return Err(jni::errors::Error::NullPtr("document write descriptor")); }
            match env.new_global_ref(&pfd) {
                Ok(global) => Ok(Arc::new(global)),
                Err(error) => {
                    // A returned PFD already owns an actual descriptor even if
                    // creating its global reference fails (for example OOM).
                    if env.exception_check() { env.exception_clear(); }
                    control.phase.store(Phase::Closing as u8, Ordering::Release);
                    let closed = env.call_method(&pfd, jni_str!("close"), jni_sig!("()V"), &[]);
                    if let Err(close) = closed { return Err(close); }
                    Err(error)
                }
            }
        }).map_err(|_| "保存目标打开失败或取消；目标可能已截断，请核对".to_owned());
        let pfd = opened?;
        let mut parcel = Parcel {
            pfd: Some(pfd.clone()),
            control: control.clone(),
        };
        let written = (|| {
            control.check()?;
            let raw = android::invoke(|env| {
                env.call_method(pfd.as_ref(), jni_str!("getFd"), jni_sig!("()I"), &[])?
                    .i()
            })?;
            let duplicate = unsafe { libc::fcntl(raw, libc::F_DUPFD_CLOEXEC, 0) };
            if duplicate < 0 {
                return Err("保存目标描述符复制失败；目标可能已截断".into());
            }
            let output = unsafe { std::fs::File::from_raw_fd(duplicate) };
            let flags = unsafe { libc::fcntl(output.as_raw_fd(), libc::F_GETFL) };
            if flags < 0
                || unsafe {
                    libc::fcntl(output.as_raw_fd(), libc::F_SETFL, flags | libc::O_NONBLOCK)
                } < 0
            {
                return Err("保存目标无法设置有界非阻塞写入".into());
            }
            control.phase.store(Phase::Writing as u8, Ordering::Release);
            let mut digest = sha2::Sha256::new();
            use sha2::Digest;
            let mut buffer = [0u8; 64 * 1024];
            let mut total = 0u64;
            loop {
                control.check()?;
                let count = source.read(&mut buffer).map_err(|_| "保存快照读取失败")?;
                if count == 0 {
                    break;
                }
                digest.update(&buffer[..count]);
                let mut offset = 0;
                while offset < count {
                    control.check()?;
                    let written = unsafe {
                        libc::write(
                            output.as_raw_fd(),
                            buffer[offset..count].as_ptr().cast(),
                            count - offset,
                        )
                    };
                    if written > 0 {
                        offset += written as usize;
                        total = total.checked_add(written as u64).ok_or("保存长度溢出")?;
                        control.bytes_written.store(total, Ordering::Release);
                    } else if written == 0 {
                        return Err("保存目标未接受字节，目标可能部分写入".into());
                    } else {
                        let error = std::io::Error::last_os_error();
                        if error.kind() == std::io::ErrorKind::Interrupted {
                            continue;
                        }
                        if error.kind() != std::io::ErrorKind::WouldBlock {
                            return Err(format!("保存目标写入失败（已提交{total}字节）：{error}"));
                        }
                        let mut poll = libc::pollfd {
                            fd: output.as_raw_fd(),
                            events: libc::POLLOUT,
                            revents: 0,
                        };
                        let status = unsafe { libc::poll(&mut poll, 1, 40) };
                        if status < 0
                            && std::io::Error::last_os_error().kind()
                                != std::io::ErrorKind::Interrupted
                        {
                            return Err("保存目标poll失败，目标可能部分写入".into());
                        }
                        if status > 0
                            && poll.revents & (libc::POLLERR | libc::POLLNVAL | libc::POLLHUP) != 0
                        {
                            return Err("保存目标管道已关闭，目标可能部分写入".into());
                        }
                    }
                }
            }
            if total != snapshot.bytes || format!("{:x}", digest.finalize()) != snapshot.sha256 {
                return Err("快照长度或哈希不匹配，目标可能已写入，不能自动重试".into());
            }
            control.check()?;
            if output
                .metadata()
                .map_err(|_| "保存目标状态未确认")?
                .is_file()
            {
                // Kernel fsync may block. The independent watchdog requests
                // cancellation and retains this worker until it actually returns.
                output
                    .sync_all()
                    .map_err(|_| "保存字节已写入，但目标同步未确认")?;
            }
            control.phase.store(Phase::Closing as u8, Ordering::Release);
            let fd = output.into_raw_fd();
            if unsafe { libc::close(fd) } != 0 {
                return Err("保存字节已写入，但本地描述符close未确认；不自动重试关闭".into());
            }
            Ok(total)
        })();
        // Even an error path must close the real PFD before completing. This
        // fixed worker stays occupied if a malicious provider blocks on close.
        let closed = parcel.close();
        match (written, closed) {
            (Ok(total), Ok(())) => Ok(format!(
                "已向所选文档写入并关闭{total}字节；源快照哈希已核对。云端同步由文档提供方处理"
            )),
            (Err(error), Ok(())) => Err(error),
            (Ok(_), Err(error)) => Err(error),
            (Err(error), Err(close)) => Err(format!("{error}；{close}")),
        }
    }
}
