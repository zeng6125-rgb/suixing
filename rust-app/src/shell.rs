//! App-UID subprocess execution. The working directory is not a filesystem sandbox.
//! Shell and its ordinary process group are terminated/reaped before returning a result.
use crate::{network::CancelToken, state::ToolCall};
use serde_json::{Value, json};
use std::path::PathBuf;
use suixing_search::rust_protocol::ToolDefinition;

#[derive(Clone)]
pub struct Prepared {
    pub command: String,
    pub timeout_ms: u64,
}

pub fn definition() -> ToolDefinition {
    ToolDefinition {name:"run_shell".into(),description:"Run Android /system/bin/sh as this application's UID, with the private workspace as cwd. This is not a filesystem sandbox. Root, Shizuku, bundled Bash and detached jobs are not available here. Every command needs explicit per-call approval. Timeout 100–120000 ms; combined raw output bounded to32 KiB. Interrupted commands may already have changed files and must not be blindly repeated.".into(),parameters:json!({"type":"object","properties":{"command":{"type":"string"},"timeout_ms":{"type":"integer","minimum":100,"maximum":120000,"default":30000},"mode":{"type":"string","enum":["local"],"default":"local"},"shell":{"type":"string","enum":["sh"],"default":"sh"}},"required":["command"],"additionalProperties":false})}
}

pub fn prepare(call: &ToolCall) -> Result<Prepared, String> {
    if call.arguments.len() > 128 * 1024 {
        return Err("Shell参数超过128 KiB".into());
    }
    let value: Value = serde_json::from_str(&call.arguments).map_err(|_| "Shell参数不是JSON")?;
    let args = value.as_object().ok_or("Shell参数必须是对象")?;
    if args
        .keys()
        .any(|key| !["command", "timeout_ms", "mode", "shell"].contains(&key.as_str()))
    {
        return Err("Shell含当前版本未接入的参数".into());
    }
    for (key, expected) in [("mode", "local"), ("shell", "sh")] {
        if args
            .get(key)
            .is_some_and(|value| value.as_str() != Some(expected))
        {
            return Err("当前Shell仅支持local/sh，未执行命令".into());
        }
    }
    let command = args
        .get("command")
        .and_then(Value::as_str)
        .filter(|value| {
            !value.trim().is_empty() && value.len() <= 64 * 1024 && !value.contains('\0')
        })
        .ok_or("Shell命令为空、含NUL或超过64 KiB")?
        .to_owned();
    let timeout_ms = args
        .get("timeout_ms")
        .map(|v| {
            v.as_u64()
                .filter(|v| (100..=120000).contains(v))
                .ok_or("Shell超时参数无效")
        })
        .transpose()?
        .unwrap_or(30000);
    Ok(Prepared {
        command,
        timeout_ms,
    })
}

#[cfg(not(any(target_os = "android", target_os = "linux")))]
pub fn execute(_workspace: PathBuf, _prepared: &Prepared, _cancel: &CancelToken) -> Value {
    json!({"ok":false,"error":"当前平台未接入应用UID Shell"})
}

#[cfg(any(target_os = "android", target_os = "linux"))]
pub fn execute(workspace: PathBuf, prepared: &Prepared, cancel: &CancelToken) -> Value {
    use std::{
        io::Read,
        os::{
            fd::AsRawFd,
            unix::process::{CommandExt, ExitStatusExt},
        },
        process::{Child, Command, Stdio},
        time::{Duration, Instant},
    };
    struct Group {
        child: Option<Child>,
    }
    impl Group {
        fn finish(&mut self) -> std::io::Result<std::process::ExitStatus> {
            let mut child = self.child.take().expect("owned child");
            // The leader has not been reaped, so its PID cannot refer to an unrelated
            // process group. waitid(WNOWAIT) below deliberately preserves this identity.
            unsafe {
                libc::kill(-(child.id() as libc::pid_t), libc::SIGKILL);
            }
            let result = child.wait();
            result
        }
    }
    impl Drop for Group {
        fn drop(&mut self) {
            if self.child.is_some() {
                let _ = self.finish();
            }
        }
    }
    fn nonblocking(fd: libc::c_int) -> std::io::Result<()> {
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
        if flags < 0 || unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
            Err(std::io::Error::last_os_error())
        } else {
            Ok(())
        }
    }
    fn drain(
        reader: &mut impl Read,
        output: &mut Vec<u8>,
        remaining: &mut usize,
        discarded: &mut bool,
    ) -> std::io::Result<bool> {
        let mut buffer = [0u8; 8192];
        for _ in 0..32 {
            match reader.read(&mut buffer) {
                Ok(0) => return Ok(true),
                Ok(count) => {
                    let permitted = count.min(*remaining);
                    output.extend_from_slice(&buffer[..permitted]);
                    *remaining -= permitted;
                    *discarded |= permitted < count;
                }
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => return Ok(false),
                Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(error) => return Err(error),
            }
        }
        Ok(false)
    }
    let mut started_effect = false;
    let result: Result<Value, String> = (|| {
        if cancel.is_cancelled() {
            return Ok(json!({"ok":false,"cancelled":true,"started":false}));
        }
        #[cfg(target_os = "android")]
        let executable = "/system/bin/sh";
        #[cfg(target_os = "linux")]
        let executable = "/bin/sh";
        let mut command = Command::new(executable);
        command
            .arg("-c")
            .arg(&prepared.command)
            .current_dir(workspace)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        // SAFETY: setsid is the only operation in the fork-to-exec callback; it does not
        // allocate or acquire Rust locks. Failure prevents the command from executing.
        unsafe {
            command.pre_exec(|| {
                if libc::setsid() < 0 {
                    Err(std::io::Error::last_os_error())
                } else {
                    Ok(())
                }
            });
        }
        let child = command.spawn().map_err(|_| "无法启动应用UID Shell")?;
        started_effect = true;
        let mut group = Group { child: Some(child) };
        let child = group.child.as_mut().ok_or("Shell进程句柄缺失")?;
        let pid = child.id();
        let mut stdout = child.stdout.take().ok_or("Shell输出管道缺失")?;
        let mut stderr = child.stderr.take().ok_or("Shell错误管道缺失")?;
        nonblocking(stdout.as_raw_fd())
            .and_then(|()| nonblocking(stderr.as_raw_fd()))
            .map_err(|_| "无法设置Shell非阻塞输出")?;
        let started = Instant::now();
        let mut out = Vec::new();
        let mut err = Vec::new();
        let mut remaining = 32 * 1024;
        let mut truncated = false;
        let mut cancelled = false;
        let mut timed_out = false;
        loop {
            drain(&mut stdout, &mut out, &mut remaining, &mut truncated)
                .and_then(|_| drain(&mut stderr, &mut err, &mut remaining, &mut truncated))
                .map_err(|_| "读取Shell输出失败，进程已停止；命令可能已产生效果")?;
            let mut info: libc::siginfo_t = unsafe { std::mem::zeroed() };
            let waited = unsafe {
                libc::waitid(
                    libc::P_PID,
                    pid as libc::id_t,
                    &mut info,
                    libc::WEXITED | libc::WNOHANG | libc::WNOWAIT,
                )
            };
            if waited < 0 {
                if std::io::Error::last_os_error().kind() == std::io::ErrorKind::Interrupted {
                    continue;
                }
                return Err("无法读取Shell退出状态，命令效果未知".into());
            }
            if unsafe { info.si_pid() } != 0 {
                break;
            }
            cancelled = cancel.is_cancelled();
            timed_out = started.elapsed() >= Duration::from_millis(prepared.timeout_ms);
            if cancelled || timed_out {
                break;
            }
            std::thread::park_timeout(Duration::from_millis(10));
        }
        let status = group
            .finish()
            .map_err(|_| "无法回收Shell进程，命令效果未知")?;
        let mut output_complete = false;
        for _ in 0..8 {
            let out_done = drain(&mut stdout, &mut out, &mut remaining, &mut truncated)
                .map_err(|_| "读取Shell最终输出失败，命令可能已产生效果")?;
            let err_done = drain(&mut stderr, &mut err, &mut remaining, &mut truncated)
                .map_err(|_| "读取Shell最终错误输出失败，命令可能已产生效果")?;
            if out_done && err_done {
                output_complete = true;
                break;
            }
        }
        Ok(
            json!({"ok":status.success()&&!cancelled&&!timed_out,"started":true,"tier":"local","shell":"sh","exit_code":status.code(),"signal":status.signal(),"stdout":String::from_utf8_lossy(&out),"stderr":String::from_utf8_lossy(&err),"output_truncated":truncated||!output_complete,"cancelled":cancelled,"timed_out":timed_out,"effects_may_have_occurred":true}),
        )
    })();
    result.unwrap_or_else(|error|json!({"ok":false,"error":error,"started":started_effect,"effects_may_have_occurred":started_effect}))
}
