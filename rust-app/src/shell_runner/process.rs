use super::{FrozenRequest, Outcome};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
pub(super) fn bound_output(outcome: &mut Outcome, limit: usize) {
    fn retain(text: &mut String, remaining: &mut usize, truncated: &mut bool) {
        if text.len() > *remaining {
            let mut end = *remaining;
            while !text.is_char_boundary(end) {
                end -= 1;
            }
            text.truncate(end);
            *truncated = true;
        }
        *remaining -= text.len();
    }
    let mut remaining = limit;
    retain(&mut outcome.stdout, &mut remaining, &mut outcome.truncated);
    retain(&mut outcome.stderr, &mut remaining, &mut outcome.truncated);
}
#[cfg(any(target_os = "android", target_os = "linux"))]
mod reaper {
    use std::{
        io,
        process::Child,
        sync::{
            Arc, Condvar, Mutex, OnceLock,
            atomic::{AtomicBool, AtomicUsize, Ordering},
        },
        time::Duration,
    };
    const MAX_OWNED: usize = 16;
    static SHARED: OnceLock<Result<Arc<State>, String>> = OnceLock::new();
    pub(super) struct State {
        pending: Mutex<Vec<(Child, Permit)>>,
        changed: Condvar,
        owned: AtomicUsize,
        failed: AtomicBool,
    }
    pub(super) struct Permit {
        state: Arc<State>,
    }
    impl Drop for Permit {
        fn drop(&mut self) {
            self.state.owned.fetch_sub(1, Ordering::AcqRel);
        }
    }
    pub(super) fn reserve() -> Result<Permit, String> {
        let state = SHARED
            .get_or_init(|| {
                let state = Arc::new(State {
                    pending: Mutex::new(Vec::new()),
                    changed: Condvar::new(),
                    owned: AtomicUsize::new(0),
                    failed: AtomicBool::new(false),
                });
                let worker = state.clone();
                std::thread::Builder::new()
                    .name("rust-shell-reaper".into())
                    .spawn(move || {
                        let result =
                            std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| run(&worker)));
                        if result.is_err() {
                            worker.failed.store(true, Ordering::Release);
                        }
                    })
                    .map_err(|_| "Shell回收工作线程不可用，未启动新命令".to_owned())?;
                Ok(state)
            })
            .as_ref()
            .map_err(Clone::clone)?
            .clone();
        if state.failed.load(Ordering::Acquire) {
            return Err("Shell回收工作线程已失效，未启动新命令".into());
        }
        state
            .owned
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |count| {
                (count < MAX_OWNED).then_some(count + 1)
            })
            .map_err(|_| "Shell已有16个运行或未确认回收的进程，未启动新命令".to_owned())?;
        Ok(Permit { state })
    }
    pub(super) fn retain(child: Child, permit: Permit) {
        let state = permit.state.clone();
        // The pre-spawn permit keeps this queue bounded even if wait stays unknown.
        state
            .pending
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .push((child, permit));
        state.changed.notify_one();
    }
    fn run(state: &State) {
        loop {
            let mut pending = state
                .pending
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            while pending.is_empty() {
                pending = state
                    .changed
                    .wait(pending)
                    .unwrap_or_else(|error| error.into_inner());
            }
            let mut index = 0;
            while index < pending.len() {
                let done = match pending[index].0.try_wait() {
                    Ok(Some(_)) => true,
                    Ok(None) => false,
                    Err(error) if error.raw_os_error() == Some(libc::ECHILD) => true,
                    Err(error) if error.kind() == io::ErrorKind::Interrupted => false,
                    Err(_) => false,
                };
                if done {
                    pending.swap_remove(index);
                } else {
                    index += 1;
                }
            }
            drop(pending);
            std::thread::park_timeout(Duration::from_millis(100));
        }
    }
}
#[cfg(any(target_os = "android", target_os = "linux"))]
pub(super) fn execute(request: &FrozenRequest, cancel: Arc<AtomicBool>) -> Outcome {
    use std::{
        io::{self, Read},
        os::{
            fd::AsRawFd,
            unix::process::{CommandExt, ExitStatusExt},
        },
        process::{Child, Command, ExitStatus, Stdio},
        time::{Duration, Instant},
    };
    struct Group {
        child: Option<Child>,
        permit: Option<reaper::Permit>,
    }
    impl Group {
        fn peek(&self) -> io::Result<bool> {
            let child = self
                .child
                .as_ref()
                .ok_or_else(|| io::Error::other("child no longer owned"))?;
            let mut info: libc::siginfo_t = unsafe { std::mem::zeroed() };
            loop {
                let result = unsafe {
                    libc::waitid(
                        libc::P_PID,
                        child.id() as libc::id_t,
                        &mut info,
                        libc::WEXITED | libc::WNOHANG | libc::WNOWAIT,
                    )
                };
                if result < 0 {
                    let error = io::Error::last_os_error();
                    if error.kind() == io::ErrorKind::Interrupted {
                        continue;
                    }
                    return Err(error);
                }
                let pid = unsafe { info.si_pid() };
                if pid != 0 && pid != child.id() as libc::pid_t {
                    return Err(io::Error::other("child identity mismatch"));
                }
                return Ok(pid != 0);
            }
        }
        fn signal(&self, value: i32) -> io::Result<()> {
            self.peek()?;
            let child = self
                .child
                .as_ref()
                .ok_or_else(|| io::Error::other("child no longer owned"))?;
            if unsafe { libc::kill(-(child.id() as libc::pid_t), value) } < 0 {
                let error = io::Error::last_os_error();
                if error.raw_os_error() != Some(libc::ESRCH) {
                    return Err(error);
                }
            }
            Ok(())
        }
        fn reap(&mut self) -> io::Result<ExitStatus> {
            if !self.peek()? {
                return Err(io::Error::other("leader still running"));
            }
            let status = self
                .child
                .as_mut()
                .ok_or_else(|| io::Error::other("child no longer owned"))?
                .wait()?;
            self.child.take();
            self.permit.take();
            Ok(status)
        }
    }
    impl Drop for Group {
        fn drop(&mut self) {
            if self.child.is_none() {
                return;
            }
            let _ = self.signal(libc::SIGKILL);
            if matches!(self.peek(), Ok(true)) && self.reap().is_ok() {
                return;
            }
            // A privileged launcher or uninterruptible child may remain alive after
            // an accepted signal. Keep a reaper owner without blocking the caller.
            if let (Some(child), Some(permit)) = (self.child.take(), self.permit.take()) {
                reaper::retain(child, permit);
            }
        }
    }
    fn nonblocking(fd: i32) -> io::Result<()> {
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
        if flags < 0 || unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(())
        }
    }
    fn drain(
        reader: &mut impl Read,
        target: &mut Vec<u8>,
        total: &mut u64,
        remaining: &mut usize,
        truncated: &mut bool,
    ) -> io::Result<bool> {
        let mut bytes = [0u8; 8192];
        for _ in 0..16 {
            match reader.read(&mut bytes) {
                Ok(0) => return Ok(true),
                Ok(count) => {
                    *total = total.saturating_add(count as u64);
                    let retained = count.min(*remaining);
                    target.extend_from_slice(&bytes[..retained]);
                    *remaining -= retained;
                    *truncated |= retained < count;
                }
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => return Ok(false),
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                Err(error) => return Err(error),
            }
        }
        Ok(false)
    }
    let mut outcome = Outcome::not_started(request.route.clone(), "Shell尚未启动");
    if cancel.load(Ordering::Acquire) {
        outcome.cancel_requested = true;
        return outcome;
    }
    let permit = match reaper::reserve() {
        Ok(permit) => permit,
        Err(error) => {
            outcome.error = Some(error);
            return outcome;
        }
    };
    let mut command = Command::new(&request.argv[0]);
    command
        .args(&request.argv[1..])
        .env_clear()
        .envs(
            request
                .environment
                .iter()
                .filter_map(|field| field.split_once('=')),
        )
        .current_dir(&request.cwd)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    unsafe {
        command.pre_exec(|| {
            if libc::setsid() < 0 {
                Err(io::Error::last_os_error())
            } else {
                Ok(())
            }
        });
    }
    let mut child = match command.spawn() {
        Ok(child) => child,
        Err(_) => {
            outcome.error =
                Some("冻结Shell可执行程序或工作目录启动失败，未改用其他身份或Shell".into());
            return outcome;
        }
    };
    outcome.started = true;
    outcome.identity_confirmed = matches!(request.route, super::Route::AppUid);
    outcome.error = None;
    let mut execution_lease =
        crate::shell_environment::lease::ExecutionLease::new(request.root_use.clone());
    let stdout = child.stdout.take();
    let stderr = child.stderr.take();
    let mut group = Group {
        child: Some(child),
        permit: Some(permit),
    };
    let mut stdout = match stdout {
        Some(pipe) => pipe,
        None => {
            outcome.error = Some("Shell stdout管道缺失".into());
            return outcome;
        }
    };
    let mut stderr = match stderr {
        Some(pipe) => pipe,
        None => {
            outcome.error = Some("Shell stderr管道缺失".into());
            return outcome;
        }
    };
    if nonblocking(stdout.as_raw_fd())
        .and_then(|()| nonblocking(stderr.as_raw_fd()))
        .is_err()
    {
        outcome.error = Some("Shell非阻塞捕获设置失败".into());
        return outcome;
    }
    let start = Instant::now();
    let deadline = start + Duration::from_millis(request.timeout_ms);
    let mut out = Vec::new();
    let mut err = Vec::new();
    let mut remaining = request.max_output_bytes;
    let mut term = None;
    let mut kill = None;
    loop {
        if let Err(error) = drain(
            &mut stdout,
            &mut out,
            &mut outcome.stdout_bytes,
            &mut remaining,
            &mut outcome.truncated,
        )
        .and_then(|_| {
            drain(
                &mut stderr,
                &mut err,
                &mut outcome.stderr_bytes,
                &mut remaining,
                &mut outcome.truncated,
            )
        }) {
            outcome.error = Some(format!("Shell输出读取失败：{error}"));
            break;
        }
        match group.peek() {
            Ok(true) => break,
            Ok(false) => (),
            Err(_) => {
                outcome.error =
                    Some("Shell leader身份或退出状态未确认，未给未知PID发送信号".into());
                break;
            }
        }
        outcome.cancel_requested |= cancel.load(Ordering::Acquire);
        outcome.timed_out |= Instant::now() >= deadline;
        if (outcome.cancel_requested || outcome.timed_out) && term.is_none() {
            if let Err(error) = group.signal(libc::SIGTERM) {
                outcome.error = Some(format!("Shell TERM未确认：{error}"));
            }
            term = Some(Instant::now());
        }
        if term.is_some_and(|at: Instant| at.elapsed() >= Duration::from_secs(2)) && kill.is_none()
        {
            if let Err(error) = group.signal(libc::SIGKILL) {
                outcome.error = Some(format!("Shell KILL未确认：{error}"));
            }
            kill = Some(Instant::now());
        }
        if kill.is_some_and(|at: Instant| at.elapsed() >= Duration::from_secs(2)) {
            outcome.error = Some("Shell已请求终止，但主进程退出仍未确认；重放可能重复操作".into());
            break;
        }
        std::thread::park_timeout(Duration::from_millis(10));
    }
    // Preserve the waitable leader until the final group signal; never signal a
    // numeric PID after it has been reaped or reported ECHILD.
    if group.peek().is_ok() {
        if let Err(error) = group.signal(libc::SIGKILL) {
            outcome
                .error
                .get_or_insert(format!("Shell进程组清理未确认：{error}"));
        }
    }
    if matches!(group.peek(), Ok(true)) {
        match group.reap() {
            Ok(status) => {
                outcome.primary_exit_confirmed = true;
                outcome.exit_code = status.code();
                outcome.signal = status.signal();
            }
            Err(_) => {
                outcome.error.get_or_insert("Shell leader回收未确认".into());
            }
        }
    }
    let final_deadline = Instant::now() + Duration::from_secs(1);
    let mut complete = false;
    loop {
        let out_done = drain(
            &mut stdout,
            &mut out,
            &mut outcome.stdout_bytes,
            &mut remaining,
            &mut outcome.truncated,
        );
        let err_done = drain(
            &mut stderr,
            &mut err,
            &mut outcome.stderr_bytes,
            &mut remaining,
            &mut outcome.truncated,
        );
        match (out_done, err_done) {
            (Ok(true), Ok(true)) => {
                complete = true;
                break;
            }
            (Err(_), _) | (_, Err(_)) => {
                outcome.error.get_or_insert("Shell最终输出读取失败".into());
                break;
            }
            _ => (),
        }
        if Instant::now() >= final_deadline {
            break;
        }
        std::thread::park_timeout(Duration::from_millis(10));
    }
    if !complete {
        outcome.truncated = true;
        outcome
            .error
            .get_or_insert("Shell输出管道未确认全部结束，捕获结果可能不完整".into());
    }
    outcome.cancel_requested |= cancel.load(Ordering::Acquire);
    outcome.stdout = String::from_utf8_lossy(&out).into_owned();
    outcome.stderr = String::from_utf8_lossy(&err).into_owned();
    bound_output(&mut outcome, request.max_output_bytes);
    // A successful signal and leader wait do not prove every daemon or process
    // that left the original group stopped. Keep this separate from leader exit.
    outcome.group_cleanup_confirmed = false;
    if outcome.primary_exit_confirmed && complete && outcome.group_cleanup_confirmed {
        execution_lease.confirmed();
    }
    outcome
}
#[cfg(not(any(target_os = "android", target_os = "linux")))]
pub(super) fn execute(request: &FrozenRequest, _cancel: Arc<AtomicBool>) -> Outcome {
    Outcome::not_started(
        request.route.clone(),
        "当前平台没有Android/Linux应用UID Shell执行器",
    )
}
