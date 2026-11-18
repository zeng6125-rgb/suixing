use super::protocol::{self, MAX_MESSAGE, ServerConfig};
use crate::network::CancelToken;
use serde_json::Value;
use std::{
    sync::{
        Arc,
        mpsc::{self, SyncSender},
    },
    time::Duration,
};
use tokio::sync::oneshot;

struct WriteRequest {
    message: Value,
    reply: oneshot::Sender<Result<Value, String>>,
}
struct Inner {
    tx: SyncSender<WriteRequest>,
    closed: Arc<CancelToken>,
    #[cfg(any(target_os = "android", target_os = "linux"))]
    wake: Arc<std::fs::File>,
}
impl Drop for Inner {
    fn drop(&mut self) {
        self.closed.cancel();
        self.wake();
    }
}
impl Inner {
    fn wake(&self) {
        #[cfg(any(target_os = "android", target_os = "linux"))]
        wake(&self.wake);
    }
}
pub(super) struct StdioTransport {
    inner: Arc<Inner>,
}
impl StdioTransport {
    pub async fn start(config: &ServerConfig, cancel: &CancelToken) -> Result<Self, String> {
        #[cfg(not(any(target_os = "android", target_os = "linux")))]
        {
            let _ = (config, cancel);
            Err("当前平台尚未接入 MCP stdio 子进程".into())
        }
        #[cfg(any(target_os = "android", target_os = "linux"))]
        {
            use std::os::fd::FromRawFd;
            if cancel.is_cancelled() {
                return Err("MCP 连接已取消".into());
            }
            let command = protocol::split_command(&config.command)?;
            let env = protocol::parse_env(&config.env)?;
            // SAFETY: eventfd returns a new descriptor, exclusively owned by this File.
            let fd = unsafe { libc::eventfd(0, libc::EFD_CLOEXEC | libc::EFD_NONBLOCK) };
            if fd < 0 {
                return Err("无法建立 MCP stdio 唤醒通道".into());
            }
            let wake = Arc::new(unsafe { std::fs::File::from_raw_fd(fd) });
            let (tx, rx) = mpsc::sync_channel(32);
            let closed = Arc::new(CancelToken::new());
            let inner = Arc::new(Inner {
                tx,
                closed: closed.clone(),
                wake: wake.clone(),
            });
            let (ready_tx, ready_rx) = oneshot::channel();
            std::thread::Builder::new()
                .name("mcp-stdio-io".into())
                .spawn(move || run(command, env, rx, closed, wake, ready_tx))
                .map_err(|_| "无法创建 MCP stdio IO 线程")?;
            let transport = Self { inner };
            let result = tokio::select! {biased;
                _=cancel.cancelled()=>Err("MCP 连接已取消".into()),
                result=ready_rx=>result.unwrap_or_else(|_|Err("MCP 子进程启动线程已退出".into())),
            };
            if let Err(error) = result {
                transport.close();
                return Err(error);
            }
            Ok(transport)
        }
    }
    pub fn close(&self) {
        self.inner.closed.cancel();
        self.inner.wake();
    }
    pub fn closed_signal(&self) -> Arc<CancelToken> {
        self.inner.closed.clone()
    }
    pub async fn exchange(
        &self,
        message: Value,
        cancel: &CancelToken,
        timeout: Duration,
    ) -> Result<Value, String> {
        self.send(message, cancel, timeout).await
    }
    pub async fn notify(
        &self,
        message: Value,
        cancel: &CancelToken,
        timeout: Duration,
    ) -> Result<(), String> {
        self.send(message, cancel, timeout).await.map(|_| ())
    }
    async fn send(
        &self,
        message: Value,
        cancel: &CancelToken,
        timeout: Duration,
    ) -> Result<Value, String> {
        if cancel.is_cancelled() || self.inner.closed.is_cancelled() {
            return Err("MCP 请求已取消".into());
        }
        let (tx, rx) = oneshot::channel();
        self.inner
            .tx
            .try_send(WriteRequest { message, reply: tx })
            .map_err(|_| "MCP stdio 发送队列已满或关闭")?;
        self.inner.wake();
        let result = tokio::select! {biased;
            _=cancel.cancelled()=>Err("MCP 请求已取消".into()),
            _=self.inner.closed.cancelled()=>Err("MCP 连接已关闭".into()),
            result=tokio::time::timeout(timeout,rx)=>match result{
                Ok(Ok(value))=>value,Ok(Err(_))=>Err("MCP stdio 请求通道已关闭".into()),Err(_)=>Err("MCP 响应超时".into()),
            },
        };
        // Wake the IO owner after dropping a cancelled response receiver. It can remove
        // an unsent request, while preserving framing for any partially written message.
        self.inner.wake();
        result
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn wake(file: &std::fs::File) {
    use std::os::fd::AsRawFd;
    let value = 1u64;
    // SAFETY: File owns the live eventfd and value is an initialized eight-byte integer.
    unsafe {
        libc::write(file.as_raw_fd(), (&value as *const u64).cast(), 8);
    }
}

#[cfg(any(target_os = "android", target_os = "linux"))]
fn run(
    command: Vec<String>,
    env: std::collections::BTreeMap<String, String>,
    rx: mpsc::Receiver<WriteRequest>,
    closed: Arc<CancelToken>,
    wake_file: Arc<std::fs::File>,
    ready: oneshot::Sender<Result<(), String>>,
) {
    use std::{
        collections::{HashMap, VecDeque},
        io::{Read, Write},
        os::{fd::AsRawFd, unix::process::CommandExt},
        process::{Child, Command, Stdio},
        time::Instant,
    };
    type Reply = oneshot::Sender<Result<Value, String>>;
    struct WriteItem {
        bytes: Vec<u8>,
        offset: usize,
        id: Option<u64>,
        notify: Option<Reply>,
    }
    struct Group(Option<Child>);
    impl Group {
        fn exited(&self) -> Result<bool, String> {
            let child = self.0.as_ref().ok_or("MCP 子进程句柄已释放")?;
            let mut info: libc::siginfo_t = unsafe { std::mem::zeroed() };
            let result = unsafe {
                libc::waitid(
                    libc::P_PID,
                    child.id(),
                    &mut info,
                    libc::WEXITED | libc::WNOHANG | libc::WNOWAIT,
                )
            };
            if result < 0 {
                return Err("无法读取 MCP 子进程状态".into());
            }
            Ok(unsafe { info.si_pid() } != 0)
        }
        fn finish(&mut self) {
            if let Some(mut child) = self.0.take() {
                let pid = child.id() as libc::pid_t;
                // Keep the unreaped leader identity until the entire ordinary process
                // group has received TERM/KILL. It cannot be reused for another group.
                unsafe {
                    libc::kill(-pid, libc::SIGTERM);
                }
                let start = Instant::now();
                while start.elapsed() < Duration::from_millis(300) {
                    let mut info: libc::siginfo_t = unsafe { std::mem::zeroed() };
                    let status = unsafe {
                        libc::waitid(
                            libc::P_PID,
                            child.id(),
                            &mut info,
                            libc::WEXITED | libc::WNOHANG | libc::WNOWAIT,
                        )
                    };
                    if status != 0 || unsafe { info.si_pid() } != 0 {
                        break;
                    }
                    std::thread::sleep(Duration::from_millis(10));
                }
                unsafe {
                    libc::kill(-pid, libc::SIGKILL);
                }
                let _ = child.wait();
            }
        }
    }
    impl Drop for Group {
        fn drop(&mut self) {
            self.finish();
        }
    }
    fn nonblocking(fd: libc::c_int) -> Result<(), String> {
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
        if flags < 0 || unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
            Err("无法设置 MCP 非阻塞管道".into())
        } else {
            Ok(())
        }
    }
    fn fail(pending: &mut HashMap<u64, Reply>, queue: &mut VecDeque<WriteItem>, reason: &str) {
        for (_, reply) in pending.drain() {
            let _ = reply.send(Err(reason.into()));
        }
        for item in queue.drain(..) {
            if let Some(reply) = item.notify {
                let _ = reply.send(Err(reason.into()));
            }
        }
    }
    fn handle_line(
        line: &[u8],
        pending: &mut HashMap<u64, Reply>,
        queue: &mut VecDeque<WriteItem>,
    ) -> Result<(), String> {
        let line = std::str::from_utf8(line)
            .map_err(|_| "MCP stdio 返回无效 UTF-8")?
            .trim();
        if line.is_empty() {
            return Ok(());
        }
        let message: Value = match serde_json::from_str(line) {
            Ok(message) => message,
            Err(_) if !line.starts_with('{') => return Ok(()),
            Err(_) => return Err("MCP stdio 返回了无效 JSON 对象".into()),
        };
        if !message.is_object() {
            return Ok(());
        }
        if message.get("method").is_some() {
            if let Some(id) = message.get("id") {
                let response = serde_json::json!({"jsonrpc":"2.0","id":id,"error":{"code":-32601,"message":"Client method not supported"}});
                let mut bytes =
                    serde_json::to_vec(&response).map_err(|_| "MCP 请求错误回复无法编码")?;
                bytes.push(b'\n');
                if queue.len() == 32
                    || queue
                        .iter()
                        .map(|item| item.bytes.len())
                        .sum::<usize>()
                        .saturating_add(bytes.len())
                        > 2 * MAX_MESSAGE
                {
                    return Err("MCP 服务器请求超过队列预算".into());
                }
                queue.push_back(WriteItem {
                    bytes,
                    offset: 0,
                    id: None,
                    notify: None,
                });
            }
            return Ok(());
        }
        let Some(id) = message.get("id").and_then(Value::as_u64) else {
            return Ok(());
        };
        if pending.contains_key(&id) {
            if !protocol::response_for(&message, &Value::from(id)) {
                return Err("MCP stdio 响应 JSON-RPC 结构无效".into());
            }
            if let Some(reply) = pending.remove(&id) {
                let _ = reply.send(Ok(message));
            }
        }
        Ok(())
    }
    let mut pending = HashMap::<u64, Reply>::new();
    let mut queue = VecDeque::<WriteItem>::new();
    let started = (|| -> Result<_, String> {
        let mut process = Command::new(&command[0]);
        process
            .args(&command[1..])
            .envs(env)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        // SAFETY: setsid is async-signal-safe and does not allocate or take locks.
        unsafe {
            process.pre_exec(|| {
                if libc::setsid() < 0 {
                    Err(std::io::Error::last_os_error())
                } else {
                    Ok(())
                }
            });
        }
        let child = process.spawn().map_err(|_| "无法启动 MCP stdio 子进程")?;
        let mut group = Group(Some(child));
        let child = group.0.as_mut().ok_or("MCP 子进程句柄缺失")?;
        let input = child.stdin.take().ok_or("MCP 输入管道缺失")?;
        let output = child.stdout.take().ok_or("MCP 输出管道缺失")?;
        let error = child.stderr.take().ok_or("MCP 错误管道缺失")?;
        nonblocking(input.as_raw_fd())?;
        nonblocking(output.as_raw_fd())?;
        nonblocking(error.as_raw_fd())?;
        Ok((group, input, output, error))
    })();
    let (mut group, mut input, mut output, mut error) = match started {
        Ok(value) => {
            let _ = ready.send(Ok(()));
            value
        }
        Err(error) => {
            let _ = ready.send(Err(error));
            closed.cancel();
            return;
        }
    };
    let mut line = Vec::new();
    let mut buffer = [0u8; 8192];
    let mut stderr_open = true;
    let mut input_closed = false;
    let result = (|| -> Result<(), String> {
        loop {
            if closed.is_cancelled() {
                return Err("MCP 连接已关闭".into());
            }
            for _ in 0..64 {
                match rx.try_recv() {
                    Ok(request) => {
                        if request.reply.is_closed() {
                            continue;
                        }
                        let mut bytes = match serde_json::to_vec(&request.message) {
                            Ok(bytes) => bytes,
                            Err(_) => {
                                let _ = request.reply.send(Err("MCP 请求无法编码".into()));
                                continue;
                            }
                        };
                        if bytes.len() > MAX_MESSAGE
                            || queue.len() >= 32
                            || queue
                                .iter()
                                .map(|item| item.bytes.len())
                                .sum::<usize>()
                                .saturating_add(bytes.len())
                                > 2 * MAX_MESSAGE
                        {
                            let _ = request.reply.send(Err("MCP stdio 请求超过发送预算".into()));
                            continue;
                        }
                        bytes.push(b'\n');
                        let id = request.message.get("id").and_then(Value::as_u64);
                        if request.message.get("id").is_some() && id.is_none() {
                            let _ = request.reply.send(Err("MCP 请求需要数字 id".into()));
                            continue;
                        }
                        if let Some(id) = id {
                            if pending.len() >= 16 || pending.contains_key(&id) {
                                let _ = request
                                    .reply
                                    .send(Err("MCP stdio 并发请求超过预算或 id 重复".into()));
                                continue;
                            }
                            pending.insert(id, request.reply);
                            queue.push_back(WriteItem {
                                bytes,
                                offset: 0,
                                id: Some(id),
                                notify: None,
                            });
                        } else {
                            queue.push_back(WriteItem {
                                bytes,
                                offset: 0,
                                id: None,
                                notify: Some(request.reply),
                            });
                        }
                    }
                    Err(mpsc::TryRecvError::Empty) => break,
                    Err(mpsc::TryRecvError::Disconnected) => {
                        return Err("MCP stdio 控制通道已关闭".into());
                    }
                }
            }
            pending.retain(|_, reply| !reply.is_closed());
            queue.retain(|item| {
                item.offset != 0
                    || item.id.is_none_or(|id| pending.contains_key(&id))
                        && item.notify.as_ref().is_none_or(|reply| !reply.is_closed())
            });
            for _ in 0..16 {
                if input_closed {
                    break;
                }
                let Some(item) = queue.front_mut() else { break };
                match input.write(&item.bytes[item.offset..]) {
                    Ok(0) => return Err("MCP stdio 输入已关闭".into()),
                    Ok(count) => {
                        item.offset += count;
                        if item.offset == item.bytes.len() {
                            let item = queue.pop_front().expect("queued write");
                            if let Some(reply) = item.notify {
                                let _ = reply.send(Ok(Value::Null));
                            }
                        }
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => break,
                    Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
                    Err(_) => return Err("MCP stdio 写入失败".into()),
                }
            }
            // Read any final replies already in the pipe before reporting a closed input.
            for _ in 0..if input_closed { 128 } else { 16 } {
                match output.read(&mut buffer) {
                    Ok(0) => {
                        if !line.is_empty() {
                            handle_line(&line, &mut pending, &mut queue)?;
                        }
                        return Err("MCP stdio 输出已关闭".into());
                    }
                    Ok(count) => {
                        for &byte in &buffer[..count] {
                            if byte == b'\n' {
                                handle_line(&line, &mut pending, &mut queue)?;
                                line.clear();
                            } else {
                                if line.len() == MAX_MESSAGE {
                                    return Err("MCP stdio 响应行超过 1 MiB".into());
                                }
                                line.push(byte);
                            }
                        }
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => break,
                    Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
                    Err(_) => return Err("MCP stdio 读取失败".into()),
                }
            }
            // Drain stderr without retaining raw credential-bearing diagnostics.
            for _ in 0..8 {
                if !stderr_open {
                    break;
                }
                match error.read(&mut buffer) {
                    Ok(0) => {
                        stderr_open = false;
                        break;
                    }
                    Ok(_) => (),
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => break,
                    Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
                    Err(_) => {
                        stderr_open = false;
                        break;
                    }
                }
            }
            if input_closed {
                return Err("MCP stdio 服务器已关闭输入".into());
            }
            if group.exited()? {
                return Err("MCP stdio 子进程已退出".into());
            }
            let mut poll = [
                libc::pollfd {
                    fd: output.as_raw_fd(),
                    events: libc::POLLIN,
                    revents: 0,
                },
                libc::pollfd {
                    fd: if stderr_open { error.as_raw_fd() } else { -1 },
                    events: libc::POLLIN,
                    revents: 0,
                },
                libc::pollfd {
                    fd: input.as_raw_fd(),
                    events: if queue.is_empty() { 0 } else { libc::POLLOUT },
                    revents: 0,
                },
                libc::pollfd {
                    fd: wake_file.as_raw_fd(),
                    events: libc::POLLIN,
                    revents: 0,
                },
            ];
            let status = unsafe { libc::poll(poll.as_mut_ptr(), poll.len() as libc::nfds_t, 1000) };
            if status < 0
                && std::io::Error::last_os_error().kind() != std::io::ErrorKind::Interrupted
            {
                return Err("MCP stdio 管道等待失败".into());
            }
            if poll[2].revents & (libc::POLLHUP | libc::POLLERR | libc::POLLNVAL) != 0 {
                input_closed = true;
            }
            if poll[3].revents & libc::POLLIN != 0 {
                let mut counter = 0u64;
                unsafe {
                    libc::read(wake_file.as_raw_fd(), (&mut counter as *mut u64).cast(), 8);
                }
            }
        }
    })();
    fail(
        &mut pending,
        &mut queue,
        result
            .as_ref()
            .err()
            .map(String::as_str)
            .unwrap_or("MCP stdio 已停止"),
    );
    closed.cancel();
    group.finish();
}
