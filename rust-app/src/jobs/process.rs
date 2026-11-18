use super::{
    JobKey, MAX_LOG_BYTES, ObservedChanges, Phase, manager::Manager, observation::Observation,
};
use crate::shell_environment::LaunchPlan;
use std::{
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    thread::{self, Thread},
    time::{Duration, Instant},
};
pub(crate) struct Control {
    cancelled: AtomicBool,
    started: AtomicBool,
    worker: Mutex<Option<Thread>>,
}
impl Control {
    pub(crate) fn new() -> Arc<Self> {
        Arc::new(Self {
            cancelled: AtomicBool::new(false),
            started: AtomicBool::new(false),
            worker: Mutex::new(None),
        })
    }
    pub(crate) fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
        if let Some(worker) = self
            .worker
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
        {
            worker.unpark();
        }
    }
    pub(crate) fn cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Acquire)
    }
}
pub(crate) struct Final {
    pub phase: Phase,
    pub started: bool,
    pub elapsed_ms: u64,
    pub exit_code: Option<i32>,
    pub signal: Option<i32>,
    pub error: Option<String>,
    pub log_bytes: u64,
    pub truncated: bool,
    pub changes: ObservedChanges,
}
pub(crate) fn spawn(
    manager: Arc<Manager>,
    key: JobKey,
    plan: LaunchPlan,
    roots: Vec<PathBuf>,
    deadline_ms: u64,
    control: Arc<Control>,
) -> Result<(), String> {
    thread::Builder::new()
        .name(format!(
            "job-{}",
            key.id.chars().take(12).collect::<String>()
        ))
        .spawn(move || {
            *control.worker.lock().unwrap_or_else(|e| e.into_inner()) = Some(thread::current());
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                run(&manager, &key, plan, roots, deadline_ms, &control)
            }));
            match result {
                Ok(final_result) => manager.finish(&key, final_result),
                Err(_) => manager.finish(
                    &key,
                    Final {
                        phase: Phase::Failed,
                        started: control.started.load(Ordering::Acquire),
                        elapsed_ms: 0,
                        exit_code: None,
                        signal: None,
                        error: Some("作业线程异常结束；进程效果及文件观察需核对".into()),
                        log_bytes: 0,
                        truncated: true,
                        changes: ObservedChanges {
                            activities: Vec::new(),
                            notice: "作业异常结束，文件变更统计未知".into(),
                        },
                    },
                ),
            }
        })
        .map_err(|_| "无法创建独立作业线程".to_owned())?;
    Ok(())
}
fn not_started(error: Option<String>, cancelled: bool) -> Final {
    Final {
        phase: if cancelled {
            Phase::Cancelled
        } else {
            Phase::Failed
        },
        started: false,
        elapsed_ms: 0,
        exit_code: None,
        signal: None,
        error,
        log_bytes: 0,
        truncated: false,
        changes: ObservedChanges::default(),
    }
}
#[cfg(not(any(target_os = "android", target_os = "linux")))]
fn run(
    _manager: &Manager,
    _key: &JobKey,
    _plan: LaunchPlan,
    _roots: Vec<PathBuf>,
    _deadline_ms: u64,
    _control: &Control,
) -> Final {
    not_started(Some("当前平台未接入应用UID后台进程".into()), false)
}
#[cfg(any(target_os = "android", target_os = "linux"))]
fn run(
    manager: &Manager,
    key: &JobKey,
    plan: LaunchPlan,
    roots: Vec<PathBuf>,
    deadline_ms: u64,
    control: &Control,
) -> Final {
    use std::{
        io::{Read, Write},
        os::{
            fd::AsRawFd,
            unix::process::{CommandExt, ExitStatusExt},
        },
        process::{Child, Command, ExitStatus, Stdio},
    };
    struct Group {
        child: Option<Child>,
    }
    impl Group {
        fn peek(&self) -> std::io::Result<bool> {
            let child = self
                .child
                .as_ref()
                .ok_or_else(|| std::io::Error::other("child no longer owned"))?;
            let mut info: libc::siginfo_t = unsafe { std::mem::zeroed() };
            let result = unsafe {
                libc::waitid(
                    libc::P_PID,
                    child.id() as libc::id_t,
                    &mut info,
                    libc::WEXITED | libc::WNOHANG | libc::WNOWAIT,
                )
            };
            if result < 0 {
                return Err(std::io::Error::last_os_error());
            }
            let observed = unsafe { info.si_pid() };
            if observed != 0 && observed != child.id() as libc::pid_t {
                return Err(std::io::Error::other("child identity differs"));
            }
            Ok(observed != 0)
        }
        fn signal(&self, signal: libc::c_int) -> std::io::Result<()> {
            let _ = self.peek()?;
            let child = self
                .child
                .as_ref()
                .ok_or_else(|| std::io::Error::other("child no longer owned"))?;
            if unsafe { libc::kill(-(child.id() as libc::pid_t), signal) } < 0 {
                let error = std::io::Error::last_os_error();
                if error.raw_os_error() != Some(libc::ESRCH) {
                    return Err(error);
                }
            }
            Ok(())
        }
        fn finish(&mut self) -> std::io::Result<ExitStatus> {
            self.signal(libc::SIGKILL)?;
            self.child
                .take()
                .ok_or_else(|| std::io::Error::other("child no longer owned"))?
                .wait()
        }
    }
    impl Drop for Group {
        fn drop(&mut self) {
            if self.child.is_some() {
                let _ = self.finish();
            }
        }
    }
    fn nonblocking(fd: i32) -> std::io::Result<()> {
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
        if flags < 0 || unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
            Err(std::io::Error::last_os_error())
        } else {
            Ok(())
        }
    }
    fn drain(
        reader: &mut impl Read,
        log: &mut std::fs::File,
        total: &mut u64,
        truncated: &mut bool,
    ) -> std::io::Result<bool> {
        let mut bytes = [0u8; 8192];
        for _ in 0..16 {
            match reader.read(&mut bytes) {
                Ok(0) => return Ok(true),
                Ok(count) => {
                    let retained = (MAX_LOG_BYTES.saturating_sub(*total) as usize).min(count);
                    if retained > 0 {
                        log.write_all(&bytes[..retained])?;
                        *total += retained as u64;
                    }
                    *truncated |= retained < count;
                }
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => return Ok(false),
                Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(error) => return Err(error),
            }
        }
        Ok(false)
    }
    if let Err(error) = manager.await_protection(control) {
        return not_started(Some(error), control.cancelled());
    }
    if control.cancelled() {
        return not_started(None, true);
    }
    let observation = Observation::begin(roots);
    if control.cancelled() {
        return not_started(None, true);
    }
    let mut log = match super::storage::create_log(manager.directory(), &key.id) {
        Ok(log) => log,
        Err(error) => return not_started(Some(error), false),
    };
    let mut command = Command::new(&plan.executable);
    command
        .args(&plan.arguments)
        .env_clear()
        .envs(&plan.environment)
        .current_dir(&plan.cwd)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    // Only async-signal-safe setsid runs between fork and exec. The leader is
    // never reaped until all group signals are finished, preserving its PID.
    unsafe {
        command.pre_exec(|| {
            if libc::setsid() < 0 {
                Err(std::io::Error::last_os_error())
            } else {
                Ok(())
            }
        });
    }
    let child = match command.spawn() {
        Ok(child) => child,
        Err(_) => return not_started(Some("无法启动所选应用UID作业进程".into()), false),
    };
    let mut execution_lease =
        crate::shell_environment::lease::ExecutionLease::new(plan.root_use.clone());
    control.started.store(true, Ordering::Release);
    let started = Instant::now();
    let pid = child.id();
    let mut group = Group { child: Some(child) };
    let mut total = 0u64;
    let mut truncated = false;
    let mut cancelled = false;
    let mut timed_out = false;
    let mut cleanup_error = None;
    let mut reaped_status = None;
    let mut pipes_complete = false;
    let execution = (|| -> Result<ExitStatus, String> {
        let child = group.child.as_mut().ok_or("作业进程句柄缺失")?;
        let mut stdout = child.stdout.take().ok_or("作业stdout管道缺失")?;
        let mut stderr = child.stderr.take().ok_or("作业stderr管道缺失")?;
        nonblocking(stdout.as_raw_fd())
            .and_then(|()| nonblocking(stderr.as_raw_fd()))
            .map_err(|_| "作业非阻塞管道设置失败")?;
        manager.mark_running(key, pid)?;
        let mut termination = None;
        let mut last_save = Instant::now();
        loop {
            drain(&mut stdout, &mut log, &mut total, &mut truncated)
                .and_then(|_| drain(&mut stderr, &mut log, &mut total, &mut truncated))
                .map_err(|_| "作业日志写入或管道读取失败")?;
            let exited = match group.peek() {
                Ok(exited) => exited,
                Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(_) => return Err("作业leader身份或退出状态未确认，未给未知PID发信号".into()),
            };
            if exited {
                break;
            }
            cancelled |= control.cancelled();
            timed_out |= started.elapsed() >= Duration::from_millis(deadline_ms);
            if (cancelled || timed_out) && termination.is_none() {
                group
                    .signal(libc::SIGTERM)
                    .map_err(|_| "作业进程组TERM未确认")?;
                termination = Some(Instant::now());
                manager.mark_cancelling(key);
            }
            if termination.is_some_and(|at: Instant| at.elapsed() >= Duration::from_secs(2)) {
                group
                    .signal(libc::SIGKILL)
                    .map_err(|_| "作业进程组KILL未确认")?;
                break;
            }
            if last_save.elapsed() >= Duration::from_secs(1) {
                log.sync_data().map_err(|_| "作业日志同步失败")?;
                manager.progress(key, total, truncated);
                last_save = Instant::now();
            }
            thread::park_timeout(Duration::from_millis(10));
        }
        let status = group.finish().map_err(|_| "作业leader回收未确认")?;
        reaped_status = Some(status);
        let mut complete = false;
        for _ in 0..16 {
            let out = drain(&mut stdout, &mut log, &mut total, &mut truncated)
                .map_err(|_| "作业最终stdout读取失败")?;
            let err = drain(&mut stderr, &mut log, &mut total, &mut truncated)
                .map_err(|_| "作业最终stderr读取失败")?;
            if out && err {
                complete = true;
                break;
            }
        }
        truncated |= !complete;
        pipes_complete = complete;
        log.sync_all().map_err(|_| "作业最终日志同步失败")?;
        Ok(status)
    })();
    let status = match execution {
        Ok(status) => Some(status),
        Err(error) => {
            cleanup_error = Some(error);
            if group.child.is_some() {
                match group.finish() {
                    Ok(status) => Some(status),
                    Err(_) => {
                        cleanup_error = Some(format!(
                            "{}；进程组清理或leader回收仍未确认",
                            cleanup_error.as_deref().unwrap_or("作业失败")
                        ));
                        None
                    }
                }
            } else {
                reaped_status
            }
        }
    };
    if status.is_some() && pipes_complete {
        execution_lease.confirmed();
    }
    // write_all may have retained a prefix before reporting an error.
    // Length is observed evidence; successful sync is reported separately.
    match log.metadata() {
        Ok(metadata) => total = metadata.len(),
        Err(_) => {
            let error = "最终日志长度未确认";
            cleanup_error = Some(match cleanup_error {
                Some(previous) => format!("{previous}；{error}"),
                None => error.into(),
            });
        }
    }
    let changes = observation.finish();
    Final {
        phase: if cleanup_error.is_some() {
            Phase::Failed
        } else if timed_out {
            Phase::TimedOut
        } else if cancelled {
            Phase::Cancelled
        } else if status.as_ref().is_some_and(ExitStatus::success) {
            Phase::Done
        } else {
            Phase::Failed
        },
        started: true,
        elapsed_ms: started.elapsed().as_millis().min(u64::MAX as u128) as u64,
        exit_code: status.as_ref().and_then(ExitStatus::code),
        signal: status.as_ref().and_then(ExitStatusExt::signal),
        error: cleanup_error,
        log_bytes: total,
        truncated,
        changes,
    }
}
