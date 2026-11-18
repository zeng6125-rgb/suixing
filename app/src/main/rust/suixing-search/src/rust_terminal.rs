//! Safe Rust ownership over the existing Android PTY kernel. No JVM or C++ bridge is used.
//! Sessions belong to a background terminal actor: stopping/reaping may block that actor.

use std::{
    io,
    path::Path,
    ptr, slice,
    sync::{Arc, atomic::AtomicBool},
};

use crate::{SearchBuffer, pty_android, sx_search_free};

const REQUEST_MAGIC: u32 = 0x3152_5850;
const MAX_READ_BYTES: usize = 16 * 1024;
const MAX_REQUEST_BYTES: usize = 8 * 1024 * 1024;

#[derive(Debug)]
pub enum ReadOutcome {
    Bytes(Vec<u8>),
    Timeout,
    End,
}

fn status(code: i32, error: i32) -> io::Result<()> {
    match code {
        0 => Ok(()),
        2 => Err(io::Error::new(io::ErrorKind::InvalidInput, "无效 PTY 参数")),
        3 => Err(io::Error::other("PTY 内核操作异常")),
        6 => Err(io::Error::new(io::ErrorKind::Interrupted, "PTY 操作已停止")),
        _ if error != 0 => Err(io::Error::from_raw_os_error(error)),
        _ => Err(io::Error::other("PTY 操作失败")),
    }
}

fn integer(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}
fn string(bytes: &mut Vec<u8>, value: &str) -> io::Result<()> {
    if value.as_bytes().contains(&0)
        || value.len() > MAX_REQUEST_BYTES.saturating_sub(bytes.len() + 4)
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "PTY 参数含 NUL 或超过大小上限",
        ));
    }
    integer(bytes, value.len() as u32);
    bytes.extend_from_slice(value.as_bytes());
    Ok(())
}

/// A response allocated by the shared Rust kernel has exactly one allocator owner,
/// including error and early-return paths.
struct OwnedResponse {
    value: SearchBuffer,
}
impl OwnedResponse {
    fn new() -> Self {
        Self {
            value: SearchBuffer {
                data: ptr::null_mut(),
                len: 0,
            },
        }
    }
    fn copy(&self) -> io::Result<Vec<u8>> {
        if self.value.len > MAX_READ_BYTES || (self.value.len > 0 && self.value.data.is_null()) {
            return Err(io::Error::other("PTY 返回无效读取缓冲区"));
        }
        if self.value.len == 0 {
            return Ok(Vec::new());
        }
        // SAFETY: sx_pty_read allocated this exact readable length in this same kernel.
        Ok(unsafe { slice::from_raw_parts(self.value.data, self.value.len) }.to_vec())
    }
}
impl Drop for OwnedResponse {
    fn drop(&mut self) {
        // SAFETY: the response is owned once and freed by its allocating kernel.
        unsafe { sx_search_free(self.value.data, self.value.len) };
    }
}

/// Shared solely so another thread may cancel a blocked writer. Actor writes remain serialized.
pub struct WriteToken {
    id: u64,
}
impl WriteToken {
    pub fn cancel(&self) {
        pty_android::sx_pty_cancel_write(self.id);
    }
    pub fn write(&self, bytes: &[u8]) -> io::Result<usize> {
        if bytes.len() > MAX_READ_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "PTY 输入超过 16 KiB 上限",
            ));
        }
        let mut count = 0;
        let mut error = 0;
        // SAFETY: the slice and writable scalar outputs live for this synchronous call.
        let code = unsafe {
            pty_android::sx_pty_write(self.id, bytes.as_ptr(), bytes.len(), &mut count, &mut error)
        };
        status(code, error)?;
        let count = usize::try_from(count).map_err(|_| io::Error::other("PTY 返回无效写入长度"))?;
        if count > bytes.len() {
            return Err(io::Error::other("PTY 返回无效写入长度"));
        }
        Ok(count)
    }
    /// Pure Rust additive API: the borrowed flag lives for the synchronous
    /// writer and is checked in its existing poll loop. No per-input thread or
    /// callback is created, and legacy C exports retain their signatures.
    pub fn write_scoped(&self, bytes: &[u8], cancel: &AtomicBool) -> io::Result<usize> {
        if bytes.len() > MAX_READ_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "PTY 输入超过16 KiB上限",
            ));
        }
        pty_android::write_scoped(self.id, bytes, cancel)
    }
}
impl Drop for WriteToken {
    fn drop(&mut self) {
        pty_android::sx_pty_release_write(self.id);
    }
}

pub struct PtySession {
    handle: i32,
    pid: i32,
    closed: bool,
    exit: Option<i32>,
}
impl PtySession {
    /// Start /system/bin/sh as the app UID with an explicit, key-free environment.
    pub fn spawn(workspace: &Path, columns: u16, rows: u16) -> io::Result<Self> {
        Self::spawn_with_term(workspace, columns, rows, "dumb")
    }

    /// A caller with a VT screen engine can advertise cursor and color support.
    pub fn spawn_vt(workspace: &Path, columns: u16, rows: u16) -> io::Result<Self> {
        Self::spawn_with_term(workspace, columns, rows, "xterm-256color")
    }

    fn spawn_with_term(workspace: &Path, columns: u16, rows: u16, term: &str) -> io::Result<Self> {
        if !(1..=1000).contains(&columns) || !(1..=1000).contains(&rows) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "PTY 行列数超出范围",
            ));
        }
        let workspace = std::fs::canonicalize(workspace)?;
        if !workspace.is_dir() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "PTY 工作目录必须是目录",
            ));
        }
        let cwd = workspace.to_str().ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "PTY 工作目录必须是 UTF-8")
        })?;
        let temporary = workspace.join("tmp");
        std::fs::create_dir_all(&temporary)?;
        let temporary = temporary.to_str().ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "PTY 临时目录必须是 UTF-8")
        })?;
        let argv = ["/system/bin/sh", "-i"];
        let environment = [
            "PATH=/system/bin:/system/xbin".to_owned(),
            "SHELL=/system/bin/sh".to_owned(),
            format!("TERM={term}"),
            "LANG=C.UTF-8".to_owned(),
            "PS1=$ ".to_owned(),
            format!("HOME={cwd}"),
            format!("TMPDIR={temporary}"),
        ];
        let argv: Vec<_> = argv.iter().map(|argument| (*argument).to_owned()).collect();
        Self::spawn_argv(&workspace, &argv, &environment, columns, rows)
    }

    /// Launch a caller-frozen app-UID executable with explicit argv/environment.
    /// The existing spawn/spawn_vt paths keep their original behavior.
    pub fn spawn_argv(
        workspace: &Path,
        argv: &[String],
        environment: &[String],
        columns: u16,
        rows: u16,
    ) -> io::Result<Self> {
        if argv.is_empty()
            || argv.len() > 128
            || environment.len() > 128
            || !(1..=1000).contains(&columns)
            || !(1..=1000).contains(&rows)
            || !Path::new(&argv[0]).is_absolute()
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "PTY启动参数无效",
            ));
        }
        let workspace = std::fs::canonicalize(workspace)?;
        if !workspace.is_dir() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "PTY工作目录必须是目录",
            ));
        }
        let cwd = workspace
            .to_str()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "PTY工作目录必须是UTF-8"))?;
        for value in environment {
            let Some((name, _)) = value.split_once('=') else {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "PTY环境字段无效",
                ));
            };
            if name.is_empty()
                || !name
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
            {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "PTY环境变量名无效",
                ));
            }
        }
        let mut request = Vec::new();
        for value in [
            REQUEST_MAGIC,
            argv.len() as u32,
            environment.len() as u32,
            columns as u32,
            rows as u32,
        ] {
            integer(&mut request, value);
        }
        string(&mut request, cwd)?;
        for argument in argv {
            string(&mut request, argument)?;
        }
        for value in environment {
            string(&mut request, value)?;
        }
        let mut handle = 0;
        let mut pid = 0;
        let mut error = 0;
        // SAFETY: bounded immutable request and valid scalar outputs are live through this call.
        let code = unsafe {
            pty_android::sx_pty_open(
                request.as_ptr(),
                request.len(),
                &mut handle,
                &mut pid,
                &mut error,
            )
        };
        status(code, error)?;
        Ok(Self {
            handle,
            pid,
            closed: false,
            exit: None,
        })
    }

    fn active(&self) -> io::Result<()> {
        if self.closed {
            Err(io::Error::new(io::ErrorKind::BrokenPipe, "PTY 已关闭"))
        } else {
            Ok(())
        }
    }

    pub fn read(&self, max: usize, timeout_ms: u32) -> io::Result<ReadOutcome> {
        self.active()?;
        if !(1..=MAX_READ_BYTES).contains(&max) || timeout_ms > 10000 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "无效 PTY 读取范围",
            ));
        }
        let mut output = OwnedResponse::new();
        let mut error = 0;
        // SAFETY: writable buffer/scalar storage and validated bounds are live through the call.
        let code = unsafe {
            pty_android::sx_pty_read(
                self.handle,
                max as i32,
                timeout_ms as i32,
                0,
                &mut output.value,
                &mut error,
            )
        };
        if code == 1 {
            return Ok(ReadOutcome::Timeout);
        }
        status(code, error)?;
        let bytes = output.copy()?;
        Ok(if bytes.is_empty() {
            ReadOutcome::End
        } else {
            ReadOutcome::Bytes(bytes)
        })
    }

    pub fn begin_write(&self) -> io::Result<Arc<WriteToken>> {
        self.active()?;
        let mut id = 0;
        let mut error = 0;
        // SAFETY: scalar output storage is valid for the synchronous operation.
        let code = unsafe { pty_android::sx_pty_begin_write(self.handle, &mut id, &mut error) };
        status(code, error)?;
        if id == 0 {
            return Err(io::Error::other("PTY 写入句柄无效"));
        }
        Ok(Arc::new(WriteToken { id }))
    }

    pub fn resize(&self, columns: u16, rows: u16) -> io::Result<()> {
        self.active()?;
        let mut error = 0;
        // SAFETY: the output storage is valid; kernel validates row/column bounds.
        let code = unsafe {
            pty_android::sx_pty_resize(self.handle, columns as i32, rows as i32, &mut error)
        };
        status(code, error)
    }

    pub fn signal(&self, value: i32) -> io::Result<()> {
        self.active()?;
        let mut error = 0;
        // SAFETY: handle+pid identify this owned process; the kernel validates allowed signals.
        let code = unsafe { pty_android::sx_pty_signal(self.handle, self.pid, value, &mut error) };
        status(code, error)
    }

    fn wait(&mut self, timeout_ms: i32) -> io::Result<Option<i32>> {
        if let Some(code) = self.exit {
            return Ok(Some(code));
        }
        self.active()?;
        let mut code = -1;
        let mut error = 0;
        // SAFETY: scalar storage is valid; the owned handle prevents signalling/reaping a reused PID.
        let result = unsafe {
            pty_android::sx_pty_wait(self.handle, self.pid, timeout_ms, &mut code, &mut error)
        };
        status(result, error)?;
        if code == -2 {
            return Ok(None);
        }
        self.exit = Some(code);
        Ok(self.exit)
    }
    pub fn try_wait(&mut self, timeout_ms: u32) -> io::Result<Option<i32>> {
        if timeout_ms > 5000 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "PTY 等待超出范围",
            ));
        }
        self.wait(timeout_ms as i32)
    }
    pub fn peek_exit(&self) -> io::Result<bool> {
        self.active()?;
        if self.exit.is_some() {
            return Ok(true);
        }
        pty_android::owned_exit_ready(self.handle, self.pid).map_err(io::Error::from_raw_os_error)
    }

    /// Stop the whole process session, reap the child, then release the master owner.
    pub fn stop(&mut self) -> io::Result<i32> {
        if self.closed {
            return Ok(self.exit.unwrap_or(-1));
        }
        let mut failure = None;
        if let Err(error) = self.peek_exit() {
            // The kernel records ECHILD as already reaped and drops its PID registry
            // owner. Never signal that PID afterwards: the OS may have reused it.
            if error.raw_os_error() == Some(libc::ECHILD) {
                self.exit = Some(-1);
            }
            failure = Some(error);
        }
        if self.exit.is_none() && failure.is_none() {
            if let Err(error) = self.signal(libc::SIGTERM) {
                if failure.is_none() {
                    failure = Some(error);
                }
            }
            let deadline = std::time::Instant::now() + std::time::Duration::from_millis(300);
            while std::time::Instant::now() < deadline {
                match self.peek_exit() {
                    Ok(true) => break,
                    Ok(false) => std::thread::park_timeout(std::time::Duration::from_millis(10)),
                    Err(error) => {
                        failure = Some(error);
                        break;
                    }
                }
            }
            // Even a naturally exited leader remains waitable here, pinning the
            // group/session while ordinary remaining members are signalled.
            if let Err(error) = self.signal(libc::SIGKILL) {
                if failure.is_none() {
                    failure = Some(error);
                }
            }
            match self.wait(-1) {
                Ok(_) => (),
                Err(error) => {
                    if error.raw_os_error() == Some(libc::ECHILD) {
                        self.exit = Some(-1);
                    }
                    if failure.is_none() {
                        failure = Some(error);
                    }
                }
            }
        }
        let mut error = 0;
        // SAFETY: this owner closes its registry handle exactly once.
        let closed = unsafe { pty_android::sx_pty_close(self.handle, &mut error) };
        self.closed = true;
        if failure.is_none() {
            if let Err(error) = status(closed, error) {
                failure = Some(error);
            }
        }
        match failure {
            Some(error) => Err(error),
            None => self
                .exit
                .ok_or_else(|| io::Error::other("PTY 进程未完成回收")),
        }
    }
}
impl Drop for PtySession {
    fn drop(&mut self) {
        let _ = self.stop();
    }
}
