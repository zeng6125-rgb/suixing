//! App-UID fixed utility/probe process. Only the worker reaps/signals; polling
//! cancellation never carries a raw PID from another process generation.
use super::Control;
use crate::shell_environment::LaunchPlan;
#[cfg(unix)]
use std::os::{fd::AsRawFd, unix::process::CommandExt};
use std::{
    fs::File,
    io::{self, Read},
    path::Path,
    process::{Child, Command, Stdio},
    sync::Arc,
    time::{Duration, Instant},
};
pub(super) struct Decoder {
    child: Option<Child>,
    stdout: std::process::ChildStdout,
    control: Arc<Control>,
    deadline: Instant,
}
impl Decoder {
    pub fn open(
        executable: &Path,
        applet: &str,
        archive: File,
        control: Arc<Control>,
    ) -> Result<Self, String> {
        let mut command = Command::new(executable);
        command
            .args([applet, "-c"])
            .env_clear()
            .env("PATH", "/system/bin")
            .stdin(Stdio::from(archive))
            .stdout(Stdio::piped())
            .stderr(Stdio::null());
        owned_group(&mut command);
        let mut child = command.spawn().map_err(|_| "随包rootfs解压程序启动失败")?;
        let stdout = child.stdout.take().ok_or("rootfs解压管道缺失")?;
        Ok(Self {
            child: Some(child),
            stdout,
            control,
            deadline: Instant::now() + Duration::from_secs(15 * 60),
        })
    }
    pub fn finish(&mut self) -> Result<(), String> {
        let mut trailing = 0u64;
        let mut buffer = [0u8; 8192];
        loop {
            let count = self.read(&mut buffer).map_err(|_| "rootfs解压结束未确认")?;
            if count == 0 {
                break;
            }
            trailing += count as u64;
            if trailing > 8 * 1024 * 1024 {
                return Err("rootfs tar结束后的内容超过预算".into());
            }
        }
        let child = self.child.as_mut().ok_or("解压进程句柄缺失")?;
        while !waitable(child).map_err(|_| "解压进程退出身份未确认")? {
            self.control.check()?;
            if Instant::now() >= self.deadline {
                return Err("解压进程退出超时".into());
            }
            std::thread::park_timeout(Duration::from_millis(10));
        }
        #[cfg(unix)]
        unsafe {
            libc::kill(-(child.id() as libc::pid_t), libc::SIGKILL);
        }
        let result = child.wait().map_err(|_| "解压进程回收失败")?;
        self.child.take();
        if result.success() {
            Ok(())
        } else {
            Err("随包rootfs解压程序失败，归档未发布".into())
        }
    }
}
impl Read for Decoder {
    fn read(&mut self, bytes: &mut [u8]) -> io::Result<usize> {
        #[cfg(unix)]
        {
            let start = Instant::now();
            loop {
                if self
                    .control
                    .cancelled
                    .load(std::sync::atomic::Ordering::Acquire)
                {
                    return Err(io::Error::new(io::ErrorKind::Interrupted, "cancelled"));
                }
                if start.elapsed() > Duration::from_secs(60) || Instant::now() >= self.deadline {
                    return Err(io::Error::new(io::ErrorKind::TimedOut, "decoder deadline"));
                }
                let mut fd = libc::pollfd {
                    fd: self.stdout.as_raw_fd(),
                    events: libc::POLLIN,
                    revents: 0,
                };
                let ready = unsafe { libc::poll(&mut fd, 1, 50) };
                if ready < 0 {
                    let error = io::Error::last_os_error();
                    if error.kind() == io::ErrorKind::Interrupted {
                        continue;
                    }
                    return Err(error);
                }
                if ready == 0 {
                    continue;
                }
                return self.stdout.read(bytes);
            }
        }
        #[cfg(not(unix))]
        {
            let _ = bytes;
            Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "Android/Linux decoder only",
            ))
        }
    }
}
impl Drop for Decoder {
    fn drop(&mut self) {
        if let Some(child) = self.child.as_mut() {
            cleanup(child);
        }
    }
}
fn owned_group(command: &mut Command) {
    #[cfg(unix)]
    unsafe {
        command.pre_exec(|| {
            if libc::setsid() < 0 {
                Err(io::Error::last_os_error())
            } else {
                Ok(())
            }
        });
    }
    #[cfg(not(unix))]
    let _ = command;
}
fn waitable(child: &Child) -> io::Result<bool> {
    #[cfg(unix)]
    {
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
            return Err(io::Error::last_os_error());
        }
        let pid = unsafe { info.si_pid() };
        if pid != 0 && pid != child.id() as libc::pid_t {
            return Err(io::Error::other("child identity mismatch"));
        }
        Ok(pid != 0)
    }
    #[cfg(not(unix))]
    {
        let _ = child;
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "no waitid backend",
        ))
    }
}
fn cleanup(child: &mut Child) {
    #[cfg(unix)]
    {
        if waitable(child).is_ok() {
            unsafe {
                libc::kill(-(child.id() as libc::pid_t), libc::SIGKILL);
            }
            let _ = child.wait();
        }
    }
    #[cfg(not(unix))]
    let _ = child;
}
pub(super) fn probe(plan: LaunchPlan, control: &Arc<Control>) -> Result<(), String> {
    let mut command = Command::new(&plan.executable);
    command
        .args(&plan.arguments)
        .env_clear()
        .envs(&plan.environment)
        .current_dir(&plan.cwd)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    owned_group(&mut command);
    let mut child = command.spawn().map_err(|_| "Linux本机动态程序检查未启动")?;
    let stdout = child.stdout.take().ok_or("Linux检查管道缺失")?;
    let mut decoder = Decoder {
        child: Some(child),
        stdout,
        control: control.clone(),
        deadline: Instant::now() + Duration::from_secs(15),
    };
    let deadline = Instant::now() + Duration::from_secs(15);
    let mut output = Vec::new();
    let mut buffer = [0u8; 8192];
    loop {
        control.check()?;
        if Instant::now() >= deadline {
            return Err("Linux本机动态程序检查超时".into());
        }
        let count = decoder
            .read(&mut buffer)
            .map_err(|_| "Linux本机动态程序输出未确认")?;
        if count == 0 {
            break;
        }
        if output.len() + count > 16 * 1024 {
            return Err("Linux检查输出超过16 KiB预算".into());
        }
        output.extend_from_slice(&buffer[..count]);
    }
    decoder.finish()?;
    let output = String::from_utf8_lossy(&output);
    if !output.contains("suixing-linux-ok") || !output.contains("aarch64") {
        return Err("当前设备未通过Linux shell与包管理器启动检查".into());
    }
    Ok(())
}
