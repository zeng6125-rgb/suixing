//! Exact Shizuku adapter for shell_runner's frozen executor contract. Pipes are
//! acquired once, drained nonblocking and bounded. RPC cleanup proves only the
//! primary process; descendant cleanup remains explicitly unconfirmed.
use crate::shell_runner::{FrozenRequest, Outcome, PrivilegedExecutor, Route};
use std::sync::{Arc, atomic::AtomicBool};
pub struct ShizukuExecutor;
impl PrivilegedExecutor for ShizukuExecutor {
    fn execute(&self, request: &FrozenRequest, cancel: Arc<AtomicBool>) -> Outcome {
        #[cfg(target_os = "android")]
        {
            device::execute(request, cancel)
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = cancel;
            Outcome::not_started(request.route, "当前平台没有Shizuku进程执行适配")
        }
    }
}
#[cfg(target_os = "android")]
mod device {
    use super::*;
    use crate::platform::shizuku;
    use std::{
        fs::File,
        io::Read,
        os::fd::AsRawFd,
        sync::atomic::Ordering,
        time::{Duration, Instant},
    };
    fn nonblocking(file: &File) -> Result<(), String> {
        let fd = file.as_raw_fd();
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
        if flags < 0 || unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
            Err("Shizuku管道非阻塞设置失败".into())
        } else {
            Ok(())
        }
    }
    fn drain(
        file: &mut File,
        bytes: &mut Vec<u8>,
        total: &mut u64,
        budget: &mut usize,
        truncated: &mut bool,
    ) -> Result<bool, String> {
        let mut block = [0u8; 8192];
        for _ in 0..32 {
            match file.read(&mut block) {
                Ok(0) => return Ok(true),
                Ok(n) => {
                    *total = total.saturating_add(n as u64);
                    let take = n.min(*budget);
                    bytes.extend_from_slice(&block[..take]);
                    *budget -= take;
                    *truncated |= take < n;
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => return Ok(false),
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(_) => return Err("Shizuku管道读取失败".into()),
            }
        }
        Ok(false)
    }
    pub(super) fn execute(request: &FrozenRequest, cancel: Arc<AtomicBool>) -> Outcome {
        if let Err(error) = request.validate() {
            return Outcome::not_started(request.route, error);
        }
        let Route::Shizuku { generation } = request.route else {
            return Outcome::not_started(request.route, "Shizuku适配拒绝其它身份请求");
        };
        let mut outcome = Outcome::not_started(request.route, "Shizuku操作未开始");
        let result: Result<(), String> = (|| {
            if cancel.load(Ordering::Acquire) {
                outcome.cancel_requested = true;
                return Err("Shizuku操作已取消，未创建进程".into());
            }
            let status = shizuku::refresh()?;
            if !status.connected || !status.granted || status.generation != generation {
                return Err("冻结的Shizuku连接或授权已经变更".into());
            }
            let cwd = request.cwd.to_str().ok_or("Shizuku工作目录不是UTF-8")?;
            let process =
                shizuku::spawn_frozen(generation, &request.argv, &request.environment, cwd)?;
            outcome.started = true;
            outcome.identity_confirmed = process.generation() == generation;
            if !outcome.identity_confirmed {
                return Err("返回进程连接代次与审批不符，清理由拥有句柄处理".into());
            }
            let stdin = process.stdin()?;
            drop(stdin);
            let mut stdout = process.stdout()?;
            let mut stderr = process.stderr()?;
            nonblocking(&stdout)?;
            nonblocking(&stderr)?;
            let mut out = Vec::new();
            let mut err = Vec::new();
            let mut budget = request.max_output_bytes;
            let mut out_done = false;
            let mut err_done = false;
            let mut read_error = None;
            let deadline = Instant::now() + Duration::from_millis(request.timeout_ms);
            loop {
                let read = drain(
                    &mut stdout,
                    &mut out,
                    &mut outcome.stdout_bytes,
                    &mut budget,
                    &mut outcome.truncated,
                )
                .and_then(|done| {
                    out_done |= done;
                    drain(
                        &mut stderr,
                        &mut err,
                        &mut outcome.stderr_bytes,
                        &mut budget,
                        &mut outcome.truncated,
                    )
                    .map(|done| err_done |= done)
                });
                if let Err(error) = read {
                    read_error = Some(error);
                    break;
                }
                match process.try_wait() {
                    Ok(Some(code)) => {
                        outcome.exit_code = Some(code);
                        outcome.primary_exit_confirmed = true;
                        break;
                    }
                    Ok(None) => {}
                    Err(error) => {
                        read_error = Some(error);
                        break;
                    }
                }
                outcome.cancel_requested = cancel.load(Ordering::Acquire);
                outcome.timed_out = Instant::now() >= deadline;
                if outcome.cancel_requested || outcome.timed_out {
                    break;
                }
                std::thread::park_timeout(Duration::from_millis(10));
            }
            if !outcome.primary_exit_confirmed {
                let stopped = process.destroy();
                let cleanup_deadline = Instant::now() + Duration::from_secs(5);
                if stopped.is_ok() {
                    while Instant::now() < cleanup_deadline {
                        match process.try_wait() {
                            Ok(Some(code)) => {
                                outcome.exit_code = Some(code);
                                outcome.primary_exit_confirmed = true;
                                break;
                            }
                            Ok(None) => std::thread::park_timeout(Duration::from_millis(20)),
                            Err(_) => break,
                        }
                    }
                }
                if !outcome.primary_exit_confirmed {
                    read_error = Some("Shizuku主进程结束未确认，句柄清理由隔离预算接续".into());
                }
            }
            for _ in 0..8 {
                let read = drain(
                    &mut stdout,
                    &mut out,
                    &mut outcome.stdout_bytes,
                    &mut budget,
                    &mut outcome.truncated,
                )
                .and_then(|done| {
                    out_done |= done;
                    drain(
                        &mut stderr,
                        &mut err,
                        &mut outcome.stderr_bytes,
                        &mut budget,
                        &mut outcome.truncated,
                    )
                    .map(|done| err_done |= done)
                });
                if read.is_err() {
                    outcome.truncated = true;
                    break;
                }
                if out_done && err_done {
                    break;
                }
                std::thread::park_timeout(Duration::from_millis(5));
            }
            outcome.truncated |= !out_done || !err_done;
            outcome.stdout = String::from_utf8_lossy(&out).into_owned();
            outcome.stderr = String::from_utf8_lossy(&err).into_owned();
            let keep = outcome.stdout.len().min(request.max_output_bytes);
            let mut boundary = keep;
            while !outcome.stdout.is_char_boundary(boundary) {
                boundary -= 1;
            }
            outcome.truncated |= boundary < outcome.stdout.len();
            outcome.stdout.truncate(boundary);
            let keep = outcome
                .stderr
                .len()
                .min(request.max_output_bytes - outcome.stdout.len());
            let mut boundary = keep;
            while !outcome.stderr.is_char_boundary(boundary) {
                boundary -= 1;
            }
            outcome.truncated |= boundary < outcome.stderr.len();
            outcome.stderr.truncate(boundary);
            outcome.cancel_requested |= cancel.load(Ordering::Acquire);
            outcome.group_cleanup_confirmed = false;
            if let Some(error) = read_error {
                return Err(error);
            }
            if outcome.cancel_requested || outcome.timed_out {
                return Err("Shizuku操作取消或超时；已产生的作用与子孙进程结束尚需核对".into());
            }
            Ok(())
        })();
        outcome.error = result.err();
        outcome
    }
}
