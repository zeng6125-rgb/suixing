//! Android PTY ownership. Registry handles never expose/reuse a kernel file descriptor.
//! All argv/envp storage is prepared before fork; the child only uses async-signal-safe syscalls.
use crate::SearchBuffer;
use std::collections::HashMap;
use std::ffi::CString;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;
use std::slice;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

const OK: i32 = 0;
const TIMEOUT: i32 = 1;
const ARGUMENT: i32 = 2;
const PANIC: i32 = 3;
const IO: i32 = 5;
const CANCELLED: i32 = 6;
const MAGIC: u32 = 0x3152_5850;

struct Wake {
    read: OwnedFd,
    write: OwnedFd,
}
impl Wake {
    fn new() -> Result<Self, i32> {
        let mut fds = [-1; 2];
        if unsafe { libc::pipe2(fds.as_mut_ptr(), libc::O_CLOEXEC | libc::O_NONBLOCK) } != 0 {
            return Err(errno());
        }
        Ok(Self {
            read: unsafe { OwnedFd::from_raw_fd(fds[0]) },
            write: unsafe { OwnedFd::from_raw_fd(fds[1]) },
        })
    }
    fn notify(&self) {
        let byte = [1u8];
        loop {
            let n = unsafe { libc::write(self.write.as_raw_fd(), byte.as_ptr().cast(), 1) };
            // EAGAIN means an earlier notification already makes the pipe readable.
            if n >= 0 || errno() != libc::EINTR {
                break;
            }
        }
    }
}
struct Master {
    fd: OwnedFd,
    wake: Wake,
    closed: AtomicBool,
}
impl Master {
    fn close(&self) {
        self.closed.store(true, Ordering::Release);
        self.wake.notify();
    }
    fn active(&self) -> Result<(), i32> {
        if self.closed.load(Ordering::Acquire) {
            Err(libc::ECANCELED)
        } else {
            Ok(())
        }
    }
}
struct Process {
    pid: i32,
    master: Mutex<Option<Arc<Master>>>,
    exit: Mutex<Option<i32>>,
}
#[derive(Default)]
struct Processes {
    next: i32,
    handles: HashMap<i32, Arc<Process>>,
    pids: HashMap<i32, Arc<Process>>,
}
static PROCESSES: OnceLock<Mutex<Processes>> = OnceLock::new();
fn processes() -> &'static Mutex<Processes> {
    PROCESSES.get_or_init(|| Mutex::new(Processes::default()))
}
fn lock<T>(m: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}
fn process(handle: i32) -> Result<Arc<Process>, i32> {
    lock(processes())
        .handles
        .get(&handle)
        .cloned()
        .ok_or(libc::EBADF)
}
fn process_pid(pid: i32) -> Result<Arc<Process>, i32> {
    lock(processes())
        .pids
        .get(&pid)
        .cloned()
        .ok_or(libc::ECHILD)
}
fn owned_process(handle: i32, pid: i32) -> Result<Arc<Process>, i32> {
    let p = process(handle)?;
    if p.pid != pid {
        return Err(libc::ECHILD);
    }
    Ok(p)
}
fn master(handle: i32) -> Result<Arc<Master>, i32> {
    lock(&process(handle)?.master)
        .as_ref()
        .cloned()
        .ok_or(libc::EBADF)
}
fn errno() -> i32 {
    io::Error::last_os_error()
        .raw_os_error()
        .unwrap_or(libc::EIO)
}
fn milliseconds(deadline: Instant) -> i32 {
    deadline
        .saturating_duration_since(Instant::now())
        .as_millis()
        .saturating_add(1)
        .min(i32::MAX as u128) as i32
}
fn poll_io(io: &Master, event: i16, timeout: i32, extra: Option<&Wake>) -> Result<bool, i32> {
    io.active()?;
    let deadline = Instant::now() + Duration::from_millis(timeout as u64);
    let mut fds = [
        libc::pollfd {
            fd: io.fd.as_raw_fd(),
            events: event | libc::POLLHUP,
            revents: 0,
        },
        libc::pollfd {
            fd: io.wake.read.as_raw_fd(),
            events: libc::POLLIN,
            revents: 0,
        },
        libc::pollfd {
            fd: extra.map_or(-1, |w| w.read.as_raw_fd()),
            events: libc::POLLIN,
            revents: 0,
        },
    ];
    loop {
        let wait = if timeout == 0 {
            0
        } else {
            milliseconds(deadline)
        };
        for fd in &mut fds {
            fd.revents = 0;
        }
        let n = unsafe { libc::poll(fds.as_mut_ptr(), if extra.is_some() { 3 } else { 2 }, wait) };
        io.active()?;
        if fds[2].revents != 0 {
            return Err(libc::ECANCELED);
        }
        if n >= 0 {
            if fds[0].revents & libc::POLLNVAL != 0 {
                return Err(libc::EBADF);
            }
            return Ok(n > 0 && fds[0].revents != 0);
        }
        let e = errno();
        if e != libc::EINTR {
            return Err(e);
        }
        if Instant::now() >= deadline {
            return Ok(false);
        }
    }
}
fn signal(p: &Process, signal: i32) -> Result<(), i32> {
    if ![libc::SIGINT, libc::SIGTERM, libc::SIGKILL, libc::SIGHUP].contains(&signal) {
        return Err(libc::EINVAL);
    }
    // The same lock surrounds nonblocking waitpid: the session leader cannot be reaped
    // while scanning its job-control process groups, so its session ID cannot be reused.
    let exited = lock(&p.exit);
    if exited.is_some() {
        return Ok(());
    }
    if let Ok(entries) = std::fs::read_dir("/proc") {
        for entry in entries.flatten() {
            let Some(pid) = entry
                .file_name()
                .to_str()
                .and_then(|s| s.parse::<i32>().ok())
            else {
                continue;
            };
            if pid > 1 && pid != p.pid && unsafe { libc::getsid(pid) } == p.pid {
                unsafe {
                    libc::kill(pid, signal);
                }
            }
        }
    }
    if unsafe { libc::kill(-p.pid, signal) } != 0 && errno() != libc::ESRCH {
        return Err(errno());
    }
    Ok(())
}
fn wait(p: &Arc<Process>, timeout: Option<i32>) -> Result<i32, i32> {
    let deadline = timeout.map(|ms| Instant::now() + Duration::from_millis(ms as u64));
    loop {
        {
            let mut exited = lock(&p.exit);
            if let Some(code) = *exited {
                return Ok(code);
            }
            let mut status = 0;
            let result = unsafe { libc::waitpid(p.pid, &mut status, libc::WNOHANG) };
            if result == p.pid {
                let code = if libc::WIFEXITED(status) {
                    libc::WEXITSTATUS(status)
                } else if libc::WIFSIGNALED(status) {
                    128 + libc::WTERMSIG(status)
                } else {
                    -1
                };
                *exited = Some(code);
                // Drop the lock before taking the registry lock (signal follows the same order).
                drop(exited);
                let mut registry = lock(processes());
                if registry.pids.get(&p.pid).is_some_and(|v| Arc::ptr_eq(v, p)) {
                    registry.pids.remove(&p.pid);
                }
                return Ok(code);
            }
            if result < 0 {
                let error = errno();
                if error == libc::ECHILD {
                    // Another reaper has consumed this child. Never retain ownership of a
                    // PID that the kernel may now reuse, even when reporting the error.
                    *exited = Some(-1);
                    drop(exited);
                    let mut registry = lock(processes());
                    if registry.pids.get(&p.pid).is_some_and(|v| Arc::ptr_eq(v, p)) {
                        registry.pids.remove(&p.pid);
                    }
                    return Err(error);
                }
                if error != libc::EINTR {
                    return Err(error);
                }
            }
        }
        if deadline.is_some_and(|d| Instant::now() >= d) {
            return Ok(-2);
        }
        // No managed/native lock is held during the wait. Stop can signal the child concurrently.
        unsafe {
            libc::poll(ptr::null_mut(), 0, 10);
        }
    }
}
fn close(handle: i32) -> Result<(), i32> {
    let p = lock(processes())
        .handles
        .remove(&handle)
        .ok_or(libc::EBADF)?;
    if let Some(io) = lock(&p.master).take() {
        io.close();
    }
    // The PID owner remains until waitpid; legacy close-before-wait callers keep working.
    Ok(())
}
struct Cursor<'a> {
    bytes: &'a [u8],
    at: usize,
}
impl Cursor<'_> {
    fn number(&mut self) -> Option<u32> {
        let next = self.at.checked_add(4)?;
        let v = u32::from_le_bytes(self.bytes.get(self.at..next)?.try_into().ok()?);
        self.at = next;
        Some(v)
    }
    fn string(&mut self) -> Option<CString> {
        let n = self.number()? as usize;
        let end = self.at.checked_add(n)?;
        let s = CString::new(self.bytes.get(self.at..end)?).ok()?;
        self.at = end;
        Some(s)
    }
}
fn open(request: &[u8]) -> Result<(i32, i32), i32> {
    let mut input = Cursor {
        bytes: request,
        at: 0,
    };
    if input.number() != Some(MAGIC) {
        return Err(libc::EINVAL);
    }
    let argc = input.number().ok_or(libc::EINVAL)? as usize;
    let envc = input.number().ok_or(libc::EINVAL)? as usize;
    let columns = input.number().ok_or(libc::EINVAL)?;
    let rows = input.number().ok_or(libc::EINVAL)?;
    if !(1..=128).contains(&argc)
        || envc > 128
        || !(1..=1000).contains(&columns)
        || !(1..=1000).contains(&rows)
    {
        return Err(libc::EINVAL);
    }
    let cwd = input.string().ok_or(libc::EINVAL)?;
    let args = (0..argc)
        .map(|_| input.string().ok_or(libc::EINVAL))
        .collect::<Result<Vec<_>, _>>()?;
    let env = (0..envc)
        .map(|_| input.string().ok_or(libc::EINVAL))
        .collect::<Result<Vec<_>, _>>()?;
    if input.at != request.len()
        || cwd.as_bytes().first() != Some(&b'/')
        || args[0].as_bytes().first() != Some(&b'/')
    {
        return Err(libc::EINVAL);
    }
    let mut argv: Vec<_> = args.iter().map(|s| s.as_ptr()).collect();
    argv.push(ptr::null());
    let mut envp: Vec<_> = env.iter().map(|s| s.as_ptr()).collect();
    envp.push(ptr::null());
    let mut startup = [-1; 2];
    if unsafe { libc::pipe2(startup.as_mut_ptr(), libc::O_CLOEXEC) } != 0 {
        return Err(errno());
    }
    let read_end = unsafe { OwnedFd::from_raw_fd(startup[0]) };
    let write_end = unsafe { OwnedFd::from_raw_fd(startup[1]) };
    let mut size = libc::winsize {
        ws_col: columns as u16,
        ws_row: rows as u16,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };
    let mut fd = -1;
    let pid = unsafe { libc::forkpty(&mut fd, ptr::null_mut(), ptr::null_mut(), &mut size) };
    if pid < 0 {
        return Err(errno());
    }
    if pid == 0 {
        // No formatting, allocation, destructor, panic, JNI or lock is allowed in this child.
        unsafe {
            libc::close(read_end.as_raw_fd());
            if libc::chdir(cwd.as_ptr()) == 0 {
                libc::execve(args[0].as_ptr(), argv.as_ptr(), envp.as_ptr());
            }
            let error = *libc::__errno();
            let bytes = (&error as *const i32).cast::<u8>();
            let mut sent = 0usize;
            while sent < std::mem::size_of::<i32>() {
                let n = libc::write(
                    write_end.as_raw_fd(),
                    bytes.add(sent).cast(),
                    std::mem::size_of::<i32>() - sent,
                );
                if n > 0 {
                    sent += n as usize;
                } else if n == 0 || *libc::__errno() != libc::EINTR {
                    break;
                }
            }
            libc::_exit(127);
        }
    }
    drop(write_end);
    let master_fd = unsafe { OwnedFd::from_raw_fd(fd) };
    let result = (|| {
        let mut error = 0i32;
        loop {
            let n = unsafe { libc::read(read_end.as_raw_fd(), (&mut error as *mut i32).cast(), 4) };
            if n == 0 {
                break;
            }
            if n > 0 {
                return Err(if n == 4 { error } else { libc::EIO });
            }
            if errno() != libc::EINTR {
                return Err(errno());
            }
        }
        if unsafe { libc::fcntl(fd, libc::F_SETFD, libc::FD_CLOEXEC) } < 0 {
            return Err(errno());
        }
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
        if flags < 0 || unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
            return Err(errno());
        }
        Wake::new()
    })();
    let wake = match result {
        Ok(wake) => wake,
        Err(error) => {
            unsafe {
                libc::kill(-pid, libc::SIGKILL);
                let mut status = 0;
                while libc::waitpid(pid, &mut status, 0) < 0 && errno() == libc::EINTR {}
            }
            return Err(error);
        }
    };
    let p = Arc::new(Process {
        pid,
        master: Mutex::new(Some(Arc::new(Master {
            fd: master_fd,
            wake,
            closed: AtomicBool::new(false),
        }))),
        exit: Mutex::new(None),
    });
    let mut registry = lock(processes());
    let Some(handle) = registry.next.checked_add(1) else {
        drop(registry);
        let _ = signal(&p, libc::SIGKILL);
        let _ = wait(&p, None);
        return Err(libc::EMFILE);
    };
    // Reserve before inserting either owner, so allocation failure cannot leave a half registry.
    if registry.handles.try_reserve(1).is_err() || registry.pids.try_reserve(1).is_err() {
        drop(registry);
        let _ = signal(&p, libc::SIGKILL);
        let _ = wait(&p, None);
        return Err(libc::ENOMEM);
    }
    registry.next = handle;
    registry.handles.insert(handle, p.clone());
    registry.pids.insert(pid, p);
    Ok((handle, pid))
}
struct WriteTask {
    io: Arc<Master>,
    wake: Wake,
    cancelled: AtomicBool,
}
#[derive(Default)]
struct Writes {
    next: u64,
    tasks: HashMap<u64, Arc<WriteTask>>,
}
static WRITES: OnceLock<Mutex<Writes>> = OnceLock::new();
fn writes() -> &'static Mutex<Writes> {
    WRITES.get_or_init(|| Mutex::new(Writes::default()))
}
fn write_task(id: u64) -> Result<Arc<WriteTask>, i32> {
    lock(writes()).tasks.get(&id).cloned().ok_or(libc::EBADF)
}
fn write_bytes(task: &WriteTask, bytes: &[u8]) -> Result<i32, i32> {
    let mut at = 0;
    let deadline = Instant::now() + Duration::from_secs(2);
    while at < bytes.len() {
        task.io.active()?;
        if task.cancelled.load(Ordering::Acquire) {
            return Err(libc::ECANCELED);
        }
        if Instant::now() >= deadline {
            return Err(libc::ETIMEDOUT);
        }
        let n = unsafe {
            libc::write(
                task.io.fd.as_raw_fd(),
                bytes[at..].as_ptr().cast(),
                bytes.len() - at,
            )
        };
        if n > 0 {
            at += n as usize;
            continue;
        }
        if n == 0 {
            return Err(libc::EIO);
        }
        let error = errno();
        if error == libc::EINTR {
            continue;
        }
        if error == libc::EAGAIN || error == libc::EWOULDBLOCK {
            poll_io(
                &task.io,
                libc::POLLOUT,
                milliseconds(deadline).min(1000),
                Some(&task.wake),
            )?;
            continue;
        }
        return Err(error);
    }
    Ok(at as i32)
}
unsafe fn status(error: *mut i32, operation: impl FnOnce() -> Result<(), i32>) -> i32 {
    if error.is_null() {
        return ARGUMENT;
    }
    unsafe {
        error.write(0);
    }
    match catch_unwind(AssertUnwindSafe(operation)) {
        Ok(Ok(())) => OK,
        Ok(Err(e)) => {
            unsafe {
                error.write(e);
            }
            if e == libc::ECANCELED {
                CANCELLED
            } else if e == libc::EINVAL {
                ARGUMENT
            } else {
                IO
            }
        }
        Err(_) => PANIC,
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_pty_open(
    request: *const u8,
    len: usize,
    handle: *mut i32,
    pid: *mut i32,
    error: *mut i32,
) -> i32 {
    if request.is_null() || handle.is_null() || pid.is_null() || len == 0 || len > 8 * 1024 * 1024 {
        return ARGUMENT;
    }
    unsafe {
        handle.write(0);
        pid.write(0);
        status(error, || {
            let (h, p) = open(slice::from_raw_parts(request, len))?;
            handle.write(h);
            pid.write(p);
            Ok(())
        })
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_pty_read(
    handle: i32,
    max: i32,
    timeout: i32,
    output: *mut SearchBuffer,
    error: *mut i32,
) -> i32 {
    if handle <= 0
        || output.is_null()
        || !(1..=16384).contains(&max)
        || !(0..=10000).contains(&timeout)
    {
        return ARGUMENT;
    }
    unsafe {
        output.write(SearchBuffer {
            data: ptr::null_mut(),
            len: 0,
        });
    }
    let mut timed_out = false;
    let result = unsafe {
        status(error, || {
            let io = master(handle)?;
            if !poll_io(&io, libc::POLLIN, timeout, None)? {
                timed_out = true;
                return Ok(());
            }
            let mut bytes = vec![0u8; max as usize];
            let n =
                unsafe { libc::read(io.fd.as_raw_fd(), bytes.as_mut_ptr().cast(), bytes.len()) };
            if n < 0 {
                let e = errno();
                if e == libc::EAGAIN || e == libc::EWOULDBLOCK || e == libc::EINTR {
                    timed_out = true;
                    return Ok(());
                }
                if e == libc::EIO {
                    return Ok(());
                }
                return Err(e);
            }
            bytes.truncate(n as usize);
            if !bytes.is_empty() {
                let owned = bytes.into_boxed_slice();
                let len = owned.len();
                unsafe {
                    output.write(SearchBuffer {
                        data: Box::into_raw(owned) as *mut u8,
                        len,
                    });
                }
            }
            Ok(())
        })
    };
    if result == OK && timed_out {
        TIMEOUT
    } else {
        result
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_pty_begin_write(handle: i32, id: *mut u64, error: *mut i32) -> i32 {
    if handle <= 0 || id.is_null() {
        return ARGUMENT;
    }
    unsafe {
        id.write(0);
        status(error, || {
            let task = Arc::new(WriteTask {
                io: master(handle)?,
                wake: Wake::new()?,
                cancelled: AtomicBool::new(false),
            });
            let mut all = lock(writes());
            let next = all
                .next
                .checked_add(1)
                .filter(|n| *n <= i64::MAX as u64)
                .ok_or(libc::EMFILE)?;
            all.tasks.try_reserve(1).map_err(|_| libc::ENOMEM)?;
            all.next = next;
            all.tasks.insert(next, task);
            id.write(next);
            Ok(())
        })
    }
}
#[unsafe(no_mangle)]
pub extern "C" fn sx_pty_cancel_write(id: u64) {
    let _ = catch_unwind(|| {
        if let Ok(t) = write_task(id) {
            t.cancelled.store(true, Ordering::Release);
            t.wake.notify();
        }
    });
}
#[unsafe(no_mangle)]
pub extern "C" fn sx_pty_release_write(id: u64) {
    let _ = catch_unwind(|| {
        if let Some(t) = lock(writes()).tasks.remove(&id) {
            t.cancelled.store(true, Ordering::Release);
            t.wake.notify();
        }
    });
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_pty_write(
    id: u64,
    bytes: *const u8,
    len: usize,
    count: *mut i32,
    error: *mut i32,
) -> i32 {
    if id == 0
        || id > i64::MAX as u64
        || count.is_null()
        || (len > 0 && bytes.is_null())
        || len > 16384
    {
        return ARGUMENT;
    }
    unsafe {
        count.write(0);
        status(error, || {
            let t = write_task(id)?;
            let data = if len == 0 {
                &[]
            } else {
                slice::from_raw_parts(bytes, len)
            };
            count.write(write_bytes(&t, data)?);
            Ok(())
        })
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_pty_resize(
    handle: i32,
    columns: i32,
    rows: i32,
    error: *mut i32,
) -> i32 {
    if handle <= 0 || !(1..=1000).contains(&columns) || !(1..=1000).contains(&rows) {
        return ARGUMENT;
    }
    unsafe {
        status(error, || {
            let io = master(handle)?;
            io.active()?;
            let size = libc::winsize {
                ws_col: columns as u16,
                ws_row: rows as u16,
                ws_xpixel: 0,
                ws_ypixel: 0,
            };
            if libc::ioctl(io.fd.as_raw_fd(), libc::TIOCSWINSZ, &size) != 0 {
                Err(errno())
            } else {
                Ok(())
            }
        })
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_pty_signal(handle: i32, pid: i32, value: i32, error: *mut i32) -> i32 {
    if handle < 0 || pid <= 1 {
        return ARGUMENT;
    }
    unsafe {
        status(error, || {
            let p = if handle > 0 {
                owned_process(handle, pid)?
            } else {
                process_pid(pid)?
            };
            signal(&p, value)
        })
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_pty_wait(
    handle: i32,
    pid: i32,
    timeout: i32,
    code: *mut i32,
    error: *mut i32,
) -> i32 {
    if handle < 0 || pid <= 1 || code.is_null() || !(-1..=5000).contains(&timeout) {
        return ARGUMENT;
    }
    unsafe {
        code.write(-1);
        status(error, || {
            let p = if handle > 0 {
                owned_process(handle, pid)?
            } else {
                process_pid(pid)?
            };
            code.write(wait(&p, if timeout < 0 { None } else { Some(timeout) })?);
            Ok(())
        })
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn sx_pty_close(handle: i32, error: *mut i32) -> i32 {
    if handle <= 0 {
        return ARGUMENT;
    }
    unsafe { status(error, || close(handle)) }
}
