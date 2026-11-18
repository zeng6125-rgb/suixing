//! Explicit user root checks. Remembered Kotlin facts are presentation only;
//! executable authority consists of a live generation confirmed by UID0.
use super::permissions::OperationKey;
use crate::{
    shell_environment::ShellEnvironment,
    shell_runner::{FrozenRequest, Outcome, PrivilegedExecutor, RootSuExecutor, Route},
};
use std::{
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{SystemTime, UNIX_EPOCH},
};
static NEXT_GENERATION: AtomicU64 = AtomicU64::new(1);
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase {
    NotChecked,
    Checking,
    Ready,
    Unavailable,
    Cancelled,
    Uncertain,
}
#[derive(Clone, Debug)]
pub struct Status {
    pub phase: Phase,
    pub generation: Option<u64>,
    pub checked_at: i64,
    pub remembered_available: bool,
    pub remembered_checked_at: i64,
    pub primary_exit_confirmed: bool,
    pub quarantined: bool,
    pub error: Option<String>,
}
struct Pending {
    operation: OperationKey,
    generation: u64,
    cancel: Arc<AtomicBool>,
}
struct Shared {
    status: Status,
    pending: Option<Pending>,
    executor: Option<Arc<RootSuExecutor>>,
}
#[derive(Clone)]
pub struct RootAuthority(Arc<Mutex<Shared>>);
pub struct ProbeTask {
    operation: OperationKey,
    generation: u64,
    environment: ShellEnvironment,
    cancel: Arc<AtomicBool>,
}
pub struct ProbeResult {
    pub operation: OperationKey,
    generation: u64,
    checked_at: i64,
    executor: Option<Arc<RootSuExecutor>>,
    outcome: Option<Outcome>,
    error: Option<String>,
    cancelled: bool,
}
impl ProbeResult {
    pub fn was_cancelled(&self) -> bool {
        self.cancelled
    }
}
impl RootAuthority {
    pub fn publish_remembered(&self, available: bool, checked_at: i64) {
        let mut shared = self.0.lock().unwrap_or_else(|e| e.into_inner());
        shared.status.remembered_available = available;
        shared.status.remembered_checked_at = checked_at.max(0);
    }
    pub fn new(remembered_available: bool, remembered_checked_at: i64) -> Self {
        Self(Arc::new(Mutex::new(Shared {
            status: Status {
                phase: Phase::NotChecked,
                generation: None,
                checked_at: 0,
                remembered_available,
                remembered_checked_at: remembered_checked_at.max(0),
                primary_exit_confirmed: false,
                quarantined: false,
                error: None,
            },
            pending: None,
            executor: None,
        })))
    }
    pub fn status(&self) -> Status {
        self.0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .status
            .clone()
    }
    pub fn begin(
        &self,
        operation: OperationKey,
        environment: ShellEnvironment,
        cancel: Arc<AtomicBool>,
    ) -> Result<ProbeTask, String> {
        operation.validate()?;
        let mut shared = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if shared.pending.is_some() || shared.status.quarantined {
            return Err("上一Root检测尚未结束或主进程清理未确认，未重复启动su".into());
        }
        if shared
            .executor
            .as_ref()
            .is_some_and(|executor| Arc::strong_count(executor) > 1)
        {
            return Err("已有准备或执行中的Root操作，请等待结束后再检测".into());
        }
        let generation = NEXT_GENERATION
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |v| v.checked_add(1))
            .map_err(|_| "Root运行代次用尽")?;
        shared.executor = None;
        shared.status.phase = Phase::Checking;
        shared.status.generation = None;
        shared.status.error = None;
        shared.pending = Some(Pending {
            operation: operation.clone(),
            generation,
            cancel: cancel.clone(),
        });
        Ok(ProbeTask {
            operation,
            generation,
            environment,
            cancel,
        })
    }
    pub fn cancel(&self, operation: &OperationKey) -> bool {
        let shared = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(pending) = shared
            .pending
            .as_ref()
            .filter(|p| p.operation == *operation)
        {
            pending.cancel.store(true, Ordering::Release);
            true
        } else {
            false
        }
    }
    /// Only for a queue send that rejected ProbeTask before a worker received it.
    pub fn abort_undispatched(&self, operation: &OperationKey) {
        let mut shared = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if shared
            .pending
            .as_ref()
            .is_some_and(|pending| pending.operation == *operation)
        {
            if let Some(pending) = shared.pending.take() {
                pending.cancel.store(true, Ordering::Release);
            }
            shared.executor = None;
            shared.status.generation = None;
            shared.status.phase = Phase::Unavailable;
            shared.status.error = Some("Root检测队列拒绝请求，命令未派发".into());
        }
    }
    pub fn forget(&self) {
        let mut shared = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(pending) = &shared.pending {
            pending.cancel.store(true, Ordering::Release);
        }
        shared.executor = None;
        shared.status.generation = None;
        if !shared.status.quarantined && shared.pending.is_none() {
            shared.status.phase = Phase::NotChecked;
        }
    }
    pub fn finish(&self, result: ProbeResult) -> Result<Status, String> {
        let mut shared = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if !shared
            .pending
            .as_ref()
            .is_some_and(|p| p.operation == result.operation && p.generation == result.generation)
        {
            return Err("Root检测结果不属于当前操作代次".into());
        }
        let cancelled = result.cancelled
            || shared
                .pending
                .as_ref()
                .is_some_and(|pending| pending.cancel.load(Ordering::Acquire));
        shared.pending = None;
        shared.status.checked_at = result.checked_at;
        shared.status.primary_exit_confirmed = result
            .outcome
            .as_ref()
            .is_some_and(|o| o.primary_exit_confirmed);
        shared.status.quarantined = result
            .outcome
            .as_ref()
            .is_some_and(|o| o.started && !o.primary_exit_confirmed);
        let verified = result
            .outcome
            .as_ref()
            .is_some_and(|o| o.succeeded() && o.stdout.trim() == "0")
            && result.executor.is_some()
            && !cancelled;
        if verified {
            shared.status.phase = Phase::Ready;
            shared.status.generation = Some(result.generation);
            shared.executor = result.executor;
            shared.status.error = None;
        } else {
            shared.executor = None;
            shared.status.generation = None;
            shared.status.phase = if shared.status.quarantined {
                Phase::Uncertain
            } else if cancelled {
                Phase::Cancelled
            } else {
                Phase::Unavailable
            };
            shared.status.error = result
                .error
                .or_else(|| result.outcome.as_ref().and_then(|o| o.error.clone()))
                .or_else(|| Some("Root UID0与进程终态未同时确认".into()));
        }
        if !cancelled {
            shared.status.remembered_available = verified;
            shared.status.remembered_checked_at = result.checked_at;
        }
        Ok(shared.status.clone())
    }
    pub fn executor(&self, generation: u64) -> Option<Arc<dyn PrivilegedExecutor>> {
        let shared = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if shared.status.phase != Phase::Ready || shared.status.generation != Some(generation) {
            return None;
        }
        let inner = shared.executor.clone()?;
        Some(Arc::new(LiveExecutor {
            authority: self.clone(),
            generation,
            inner,
        }))
    }
    fn invalidate(&self, generation: u64, error: &str, quarantine: bool) {
        let mut shared = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if shared.status.generation == Some(generation) {
            shared.status.generation = None;
            shared.status.quarantined |= quarantine;
            shared.status.phase = if shared.status.quarantined {
                Phase::Uncertain
            } else {
                Phase::Unavailable
            };
            if quarantine {
                shared.status.primary_exit_confirmed = false;
            }
            shared.status.error = Some(error.into());
            shared.executor = None;
        }
    }
}
struct LiveExecutor {
    authority: RootAuthority,
    generation: u64,
    inner: Arc<RootSuExecutor>,
}
impl PrivilegedExecutor for LiveExecutor {
    fn execute(&self, request: &FrozenRequest, cancel: Arc<AtomicBool>) -> Outcome {
        let status = self.authority.status();
        if status.phase != Phase::Ready
            || status.generation != Some(self.generation)
            || request.route
                != (Route::Root {
                    generation: self.generation,
                })
        {
            return Outcome::not_started(
                request.route,
                "冻结Root授权代次已失效，未改用其它执行路线",
            );
        }
        let outcome = self.inner.execute(request, cancel);
        let quarantine = outcome.started && !outcome.primary_exit_confirmed;
        if !outcome.identity_confirmed || quarantine {
            self.authority.invalidate(
                self.generation,
                if quarantine {
                    "Root命令主进程退出未确认，隔离该权限代次且不重新启动su"
                } else {
                    "Root执行UID0或冻结程序身份未确认，请重新检测"
                },
                quarantine,
            );
        }
        outcome
    }
}
pub fn perform(task: ProbeTask) -> ProbeResult {
    let checked_at = || {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis()
            .min(i64::MAX as u128) as i64
    };
    let mut result = ProbeResult {
        operation: task.operation.clone(),
        generation: task.generation,
        checked_at: 0,
        executor: None,
        outcome: None,
        error: None,
        cancelled: false,
    };
    if task.cancel.load(Ordering::Acquire) {
        result.cancelled = true;
        result.error = Some("Root检测已取消，未启动su".into());
        result.checked_at = checked_at();
        return result;
    }
    #[cfg(not(target_os = "android"))]
    {
        result.error = Some("当前平台没有Android Root能力检测".into());
    }
    #[cfg(target_os = "android")]
    {
        let selected = (|| {
            let busybox_plan = task
                .environment
                .interactive(crate::shell_environment::Backend::Busybox)?;
            // ShellEnvironment has already checked bundled executable ownership.
            for candidate in [
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/su/bin/su",
                "/debug_ramdisk/su",
                "/data/adb/ksu/bin/su",
            ] {
                if let Ok(executor) = RootSuExecutor::freeze(
                    PathBuf::from(candidate),
                    busybox_plan.executable.clone(),
                    task.generation,
                ) {
                    return Ok(Arc::new(executor));
                }
            }
            Err("没有找到已核对所有者与执行权限的su程序".to_string())
        })();
        match selected {
            Err(error) => result.error = Some(error),
            Ok(executor) => {
                let request = FrozenRequest {
                    request_key: task.operation.id.clone(),
                    argv: vec!["/system/bin/id".into(), "-u".into()],
                    environment: Vec::new(),
                    cwd: PathBuf::from("/"),
                    route: Route::Root {
                        generation: task.generation,
                    },
                    timeout_ms: 10_000,
                    max_output_bytes: 4096,
                    observe_paths: Vec::new(),
                    root_use: None,
                };
                let outcome = executor.execute(&request, task.cancel.clone());
                result.cancelled = task.cancel.load(Ordering::Acquire) || outcome.cancel_requested;
                result.outcome = Some(outcome);
                result.executor = Some(executor);
            }
        }
    }
    result.checked_at = checked_at();
    result
}
