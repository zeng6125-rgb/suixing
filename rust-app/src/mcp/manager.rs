use super::{
    http::HttpTransport,
    protocol::{self, ServerConfig},
    stdio::StdioTransport,
};
use crate::network::CancelToken;
use serde_json::{Value, json};
use std::{
    collections::{HashMap, HashSet},
    sync::{
        Arc, Mutex,
        atomic::{AtomicU8, AtomicU64, Ordering},
        mpsc::{self, Receiver, SyncSender},
    },
    time::Duration,
};
use tokio::sync::mpsc as async_channel;

const TIMEOUT: Duration = Duration::from_secs(60);
const MAX_CALL_WAIT: Duration = Duration::from_secs(90);
const MAX_TEST_RESULTS: usize = 8;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(120);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Status {
    Disconnected,
    Connecting,
    Reconnecting,
    Ready,
    Failed,
}
impl Status {
    pub fn label(self) -> &'static str {
        match self {
            Self::Disconnected => "未连接",
            Self::Connecting => "连接中",
            Self::Reconnecting => "重连中",
            Self::Ready => "已就绪",
            Self::Failed => "连接失败",
        }
    }
}
#[derive(Clone, Debug)]
pub struct ServerState {
    pub config: ServerConfig,
    pub status: Status,
    pub tool_count: usize,
    pub server_name: String,
    pub server_version: String,
    pub protocol_version: String,
    pub error: Option<String>,
    pub attempt: usize,
    pub generation: u64,
}
#[derive(Clone)]
pub struct ToolBinding {
    pub server_id: String,
    pub generation: u64,
    pub registered_name: String,
    pub remote_name: String,
    pub description: String,
    pub input_schema: Value,
    configuration_fingerprint: [u8; 32],
    configuration_revision: u64,
}
impl std::fmt::Debug for ToolBinding {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ToolBinding")
            .field("server_id", &self.server_id)
            .field("generation", &self.generation)
            .field("registered_name", &self.registered_name)
            .field("remote_name", &self.remote_name)
            .finish_non_exhaustive()
    }
}
#[derive(Clone, Copy)]
struct DesiredConfiguration {
    fingerprint: [u8; 32],
    revision: u64,
}
type DesiredConfigurations = Arc<Mutex<HashMap<String, DesiredConfiguration>>>;
fn configuration_fingerprints(configs: &[ServerConfig]) -> HashMap<String, [u8; 32]> {
    configs
        .iter()
        .map(|config| (config.id.clone(), config.fingerprint()))
        .collect()
}
fn cancel_admission(state: &AtomicU8) -> bool {
    matches!(
        state.compare_exchange(0, 2, Ordering::AcqRel, Ordering::Acquire),
        Err(1)
    )
}
#[derive(Clone, Debug)]
pub struct TestReport {
    pub server_name: String,
    pub server_version: String,
    pub protocol_version: String,
    pub tools: Vec<String>,
}
#[derive(Clone, Debug)]
pub struct TestCompletion {
    pub request_id: String,
    pub result: Result<TestReport, String>,
}
#[derive(Clone, Debug, Default)]
pub struct Snapshot {
    pub revision: u64,
    pub servers: Vec<ServerState>,
    pub tools: Vec<ToolBinding>,
    pub test_results: Vec<TestCompletion>,
}
#[derive(Clone, Debug)]
pub enum ManagerEvent {
    Snapshot(Snapshot),
    TestFinished {
        request_id: String,
        result: Result<TestReport, String>,
    },
}

enum Command {
    Configure(Vec<ServerConfig>),
    Connect(String),
    Disconnect(String),
    Refresh(String),
    Test {
        request_id: String,
        config: ServerConfig,
        cancel: Arc<CancelToken>,
    },
    Call {
        binding: ToolBinding,
        arguments: Value,
        cancel: Arc<CancelToken>,
        started: Arc<AtomicU8>,
        reply: SyncSender<Result<Value, String>>,
    },
    Close,
}
struct BridgeInner {
    tx: async_channel::Sender<Command>,
    cache: Arc<Mutex<Snapshot>>,
    revision: Arc<AtomicU64>,
    shutdown: Arc<CancelToken>,
    test_requests: Mutex<HashSet<String>>,
    desired: DesiredConfigurations,
    desired_revision: AtomicU64,
}
impl Drop for BridgeInner {
    fn drop(&mut self) {
        self.shutdown.cancel();
        let _ = self.tx.try_send(Command::Close);
    }
}
#[derive(Clone)]
pub struct McpBridge {
    inner: Arc<BridgeInner>,
}
impl std::fmt::Debug for McpBridge {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("McpBridge")
            .field("revision", &self.revision())
            .finish_non_exhaustive()
    }
}
impl McpBridge {
    pub fn spawn(
        configs: Vec<ServerConfig>,
        client_version: impl Into<String>,
    ) -> Result<(Self, Receiver<ManagerEvent>), String> {
        let configs = protocol::normalize_configs(configs)?;
        let version = client_version.into();
        if version.len() > 128 || version.chars().any(char::is_control) {
            return Err("MCP 客户端版本无效".into());
        }
        let (tx, rx) = async_channel::channel(64);
        let (events, event_rx) = mpsc::sync_channel(32);
        let cache = Arc::new(Mutex::new(Snapshot::default()));
        let revision = Arc::new(AtomicU64::new(0));
        let shutdown = Arc::new(CancelToken::new());
        let desired = Arc::new(Mutex::new(
            configuration_fingerprints(&configs)
                .into_iter()
                .map(|(id, fingerprint)| {
                    (
                        id,
                        DesiredConfiguration {
                            fingerprint,
                            revision: 1,
                        },
                    )
                })
                .collect(),
        ));
        let inner = Arc::new(BridgeInner {
            tx,
            cache: cache.clone(),
            revision: revision.clone(),
            shutdown: shutdown.clone(),
            test_requests: Mutex::new(HashSet::new()),
            desired: desired.clone(),
            desired_revision: AtomicU64::new(1),
        });
        std::thread::Builder::new()
            .name("mcp-manager".into())
            .spawn(move || {
                let runtime = match tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                {
                    Ok(runtime) => runtime,
                    Err(_) => {
                        let mut snapshot = Snapshot::default();
                        snapshot.revision = 1;
                        snapshot.servers = configs
                            .into_iter()
                            .map(|config| ServerState {
                                config: config.metadata(),
                                status: Status::Failed,
                                tool_count: 0,
                                server_name: String::new(),
                                server_version: String::new(),
                                protocol_version: String::new(),
                                error: Some("无法创建 MCP 运行时".into()),
                                attempt: 0,
                                generation: 0,
                            })
                            .collect();
                        if let Ok(mut state) = cache.lock() {
                            *state = snapshot.clone();
                        }
                        revision.store(1, Ordering::Release);
                        let _ = events.try_send(ManagerEvent::Snapshot(snapshot));
                        shutdown.cancel();
                        return;
                    }
                };
                runtime.block_on(
                    Manager::new(cache, revision, events, shutdown, version, desired)
                        .run(configs, rx),
                );
            })
            .map_err(|_| "无法创建 MCP 管理线程")?;
        Ok((Self { inner }, event_rx))
    }
    fn send(&self, command: Command) -> Result<(), String> {
        if self.inner.shutdown.is_cancelled() {
            return Err("MCP 管理器已关闭".into());
        }
        self.inner
            .tx
            .try_send(command)
            .map_err(|_| "MCP 管理队列已满或关闭".into())
    }
    pub fn configure(&self, configs: Vec<ServerConfig>) -> Result<(), String> {
        let configs = protocol::normalize_configs(configs)?;
        // Revoke old bindings immediately, including when the asynchronous queue is full.
        let fingerprints = configuration_fingerprints(&configs);
        {
            let mut desired = self
                .inner
                .desired
                .lock()
                .map_err(|_| "MCP 配置门禁不可用")?;
            if desired.len() != fingerprints.len()
                || fingerprints.iter().any(|(id, fingerprint)| {
                    desired
                        .get(id)
                        .is_none_or(|entry| &entry.fingerprint != fingerprint)
                })
            {
                let revision = self
                    .inner
                    .desired_revision
                    .fetch_update(Ordering::AcqRel, Ordering::Acquire, |revision| {
                        revision.checked_add(1)
                    })
                    .map_err(|_| "MCP 配置版本已耗尽")?
                    + 1;
                let next = fingerprints
                    .into_iter()
                    .map(|(id, fingerprint)| {
                        let entry = desired
                            .get(&id)
                            .copied()
                            .filter(|entry| entry.fingerprint == fingerprint)
                            .unwrap_or(DesiredConfiguration {
                                fingerprint,
                                revision,
                            });
                        (id, entry)
                    })
                    .collect();
                *desired = next;
            }
        }
        self.send(Command::Configure(configs))
    }
    pub fn connect(&self, id: &str) -> Result<(), String> {
        self.send(Command::Connect(id.into()))
    }
    pub fn disconnect(&self, id: &str) -> Result<(), String> {
        self.send(Command::Disconnect(id.into()))
    }
    pub fn refresh(&self, id: &str) -> Result<(), String> {
        self.send(Command::Refresh(id.into()))
    }
    pub fn test(
        &self,
        request_id: impl Into<String>,
        config: ServerConfig,
        cancel: Arc<CancelToken>,
    ) -> Result<(), String> {
        protocol::validate_config(&config)?;
        let request_id = request_id.into();
        if request_id.is_empty() || request_id.len() > 128 {
            return Err("MCP 测试请求身份无效".into());
        }
        {
            let mut requests = self
                .inner
                .test_requests
                .lock()
                .map_err(|_| "MCP 测试队列不可用")?;
            if requests.len() >= MAX_TEST_RESULTS || !requests.insert(request_id.clone()) {
                return Err("MCP 连接测试超过 8 个待处理结果或身份重复".into());
            }
        }
        let result = self.send(Command::Test {
            request_id: request_id.clone(),
            config,
            cancel,
        });
        if result.is_err() {
            if let Ok(mut requests) = self.inner.test_requests.lock() {
                requests.remove(&request_id);
            }
        }
        result
    }
    pub fn revision(&self) -> u64 {
        self.inner.revision.load(Ordering::Acquire)
    }
    pub fn snapshot(&self) -> Snapshot {
        self.inner
            .cache
            .lock()
            .map(|cache| cache.clone())
            .unwrap_or_default()
    }
    pub fn binding_is_current(&self, binding: &ToolBinding) -> bool {
        if self.inner.shutdown.is_cancelled() {
            return false;
        }
        let desired = self
            .inner
            .desired
            .lock()
            .map(|desired| {
                desired.get(&binding.server_id).is_some_and(|entry| {
                    entry.fingerprint == binding.configuration_fingerprint
                        && entry.revision == binding.configuration_revision
                })
            })
            .unwrap_or(false);
        desired
            && self
                .inner
                .cache
                .lock()
                .map(|snapshot| {
                    snapshot.servers.iter().any(|server| {
                        server.config.id == binding.server_id
                            && server.generation == binding.generation
                            && server.status == Status::Ready
                    })
                })
                .unwrap_or(false)
    }
    /// Test results remain in a bounded cache until consumed even if the event queue fills.
    pub fn take_test_result(&self, request_id: &str) -> Option<Result<TestReport, String>> {
        let mut cache = self.inner.cache.lock().ok()?;
        let index = cache
            .test_results
            .iter()
            .position(|result| result.request_id == request_id)?;
        let result = cache.test_results.remove(index).result;
        if let Ok(mut requests) = self.inner.test_requests.lock() {
            requests.remove(request_id);
        }
        Some(result)
    }
    /// Blocking waits belong on the Agent worker, never the UI or the durable Store actor.
    pub fn call(
        &self,
        binding: &ToolBinding,
        arguments: &str,
        cancel: Arc<CancelToken>,
    ) -> Result<Value, String> {
        if arguments.len() > protocol::MAX_ARGUMENTS {
            return Err("MCP 工具参数超过 128 KiB".into());
        }
        let arguments: Value =
            serde_json::from_str(arguments).map_err(|_| "MCP 工具参数不是 JSON 对象")?;
        if !arguments.is_object() {
            return Err("MCP 工具参数需要 JSON 对象".into());
        }
        if !self
            .inner
            .desired
            .lock()
            .map_err(|_| "MCP 配置门禁不可用")?
            .get(&binding.server_id)
            .is_some_and(|entry| {
                entry.fingerprint == binding.configuration_fingerprint
                    && entry.revision == binding.configuration_revision
            })
        {
            return Ok(
                json!({"ok":false,"error":"MCP 配置已变更，旧工具调用未发送","started":false,"effects_may_have_occurred":false}),
            );
        }
        if cancel.is_cancelled() {
            return Ok(
                json!({"ok":false,"cancelled":true,"started":false,"effects_may_have_occurred":false}),
            );
        }
        let (reply, rx) = mpsc::sync_channel(1);
        // 0 pending, 1 admitted, 2 cancelled before admission. One CAS establishes
        // whether cancellation won, rather than racing separate flag loads.
        let started = Arc::new(AtomicU8::new(0));
        let request_cancel = Arc::new(CancelToken::new());
        request_cancel.set_deadline(MAX_CALL_WAIT);
        self.send(Command::Call {
            binding: binding.clone(),
            arguments,
            cancel: request_cancel.clone(),
            started: started.clone(),
            reply,
        })?;
        let start = std::time::Instant::now();
        loop {
            match rx.recv_timeout(Duration::from_millis(25)) {
                Ok(result) => return result,
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    let admitted = cancel_admission(&started);
                    request_cancel.cancel();
                    return Ok(
                        json!({"ok":false,"error":"MCP 请求线程已退出","started":admitted,"effects_may_have_occurred":admitted}),
                    );
                }
                Err(mpsc::RecvTimeoutError::Timeout) => (),
            }
            if cancel.is_cancelled() || self.inner.shutdown.is_cancelled() {
                let admitted = cancel_admission(&started);
                request_cancel.cancel();
                return Ok(
                    json!({"ok":false,"cancelled":true,"started":admitted,"effects_may_have_occurred":admitted}),
                );
            }
            if start.elapsed() >= MAX_CALL_WAIT {
                let admitted = cancel_admission(&started);
                request_cancel.cancel();
                return Ok(
                    json!({"ok":false,"timed_out":true,"started":admitted,"effects_may_have_occurred":admitted}),
                );
            }
        }
    }
    pub fn close(&self) {
        self.inner.shutdown.cancel();
        let _ = self.inner.tx.try_send(Command::Close);
    }
}

enum Transport {
    Http(HttpTransport),
    Stdio(StdioTransport),
}
impl Transport {
    async fn open(config: &ServerConfig, cancel: &CancelToken) -> Result<Self, String> {
        match config.transport.as_str() {
            "http" => Ok(Self::Http(HttpTransport::new(config)?)),
            "stdio" => Ok(Self::Stdio(StdioTransport::start(config, cancel).await?)),
            _ => Err("MCP 传输类型无效".into()),
        }
    }
    async fn exchange(&self, message: Value, cancel: &CancelToken) -> Result<Value, String> {
        match self {
            Self::Http(transport) => transport.exchange(message, cancel, TIMEOUT).await,
            Self::Stdio(transport) => transport.exchange(message, cancel, TIMEOUT).await,
        }
    }
    async fn notify(&self, message: Value, cancel: &CancelToken) -> Result<(), String> {
        match self {
            Self::Http(transport) => transport.notify(message, cancel, TIMEOUT).await,
            Self::Stdio(transport) => transport.notify(message, cancel, TIMEOUT).await,
        }
    }
    fn close(&self) {
        match self {
            Self::Http(transport) => transport.close(),
            Self::Stdio(transport) => transport.close(),
        }
    }
    fn set_version(&self, version: &str) -> Result<(), String> {
        match self {
            Self::Http(transport) => transport.set_version(version),
            Self::Stdio(_) => Ok(()),
        }
    }
    fn closed_signal(&self) -> Option<Arc<CancelToken>> {
        match self {
            Self::Stdio(transport) => Some(transport.closed_signal()),
            Self::Http(_) => None,
        }
    }
}
impl Drop for Transport {
    fn drop(&mut self) {
        self.close();
    }
}
struct Client {
    transport: Transport,
    next_id: AtomicU64,
    filter: protocol::SecretFilter,
    name: String,
    version: String,
    protocol: String,
    tools: Vec<protocol::RemoteTool>,
}
impl Client {
    fn id(&self) -> Result<u64, String> {
        self.next_id
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |id| id.checked_add(1))
            .map_err(|_| "MCP 请求身份已耗尽".into())
    }
    async fn open(
        config: &ServerConfig,
        version: &str,
        cancel: &CancelToken,
    ) -> Result<Arc<Self>, String> {
        let transport = Transport::open(config, cancel).await?;
        let filter = protocol::SecretFilter::new(&config.secrets());
        let result = protocol::result_of(
            transport
                .exchange(protocol::initialize(1, version), cancel)
                .await?,
            &filter,
        )?;
        let protocol_version = result
            .get("protocolVersion")
            .and_then(Value::as_str)
            .unwrap_or(protocol::PROTOCOL_VERSION)
            .to_owned();
        if protocol_version.len() > 128 || protocol_version.chars().any(char::is_control) {
            return Err("MCP 返回无效协议版本".into());
        }
        let info = result.get("serverInfo");
        let name = protocol::clip(
            &filter.text(
                info.and_then(|info| info.get("name"))
                    .and_then(Value::as_str)
                    .unwrap_or("mcp")
                    .to_owned(),
            ),
            256,
        );
        let server_version = protocol::clip(
            &filter.text(
                info.and_then(|info| info.get("version"))
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_owned(),
            ),
            128,
        );
        transport.set_version(&protocol_version)?;
        transport.notify(protocol::initialized(), cancel).await?;
        let mut client = Self {
            transport,
            next_id: AtomicU64::new(2),
            filter,
            name,
            version: server_version,
            protocol: protocol_version,
            tools: Vec::new(),
        };
        let mut cursor = None;
        let mut cursors = HashSet::new();
        let mut names = HashSet::new();
        let mut bytes = 0usize;
        for page in 0..10 {
            let result = protocol::result_of(
                client
                    .transport
                    .exchange(
                        protocol::list_tools(client.id()?, cursor.as_deref()),
                        cancel,
                    )
                    .await?,
                &client.filter,
            )?;
            let (tools, next) = protocol::parse_tools(&result)?;
            for tool in tools {
                if !names.insert(tool.name.clone()) {
                    return Err("MCP 多页目录出现重复工具名".into());
                }
                bytes = bytes
                    .saturating_add(tool.schema.to_string().len())
                    .saturating_add(tool.name.len())
                    .saturating_add(tool.description.len());
                if client.tools.len() == protocol::MAX_TOOLS || bytes > protocol::MAX_CATALOG {
                    return Err("MCP 完整工具目录超过 256 个工具或 1 MiB".into());
                }
                client.tools.push(tool);
            }
            match next {
                None => return Ok(Arc::new(client)),
                Some(next) => {
                    if !cursors.insert(next.clone()) || page == 9 {
                        return Err("MCP 工具分页循环或超过 10 页".into());
                    }
                    cursor = Some(next);
                }
            }
        }
        Err("MCP 工具发现未结束".into())
    }
    async fn call(
        &self,
        name: &str,
        arguments: Value,
        cancel: &CancelToken,
    ) -> Result<Value, String> {
        let message = protocol::call_tool(self.id()?, name, arguments);
        let response = self.transport.exchange(message, cancel).await?;
        if let Some(error) = response.get("error") {
            let error = error
                .get("message")
                .and_then(Value::as_str)
                .unwrap_or("MCP JSON-RPC 工具调用失败");
            return Ok(
                json!({"ok":false,"error":protocol::clip(&self.filter.text(error.to_owned()),4096),"effects_may_have_occurred":true}),
            );
        }
        Ok(protocol::parse_call_result(
            response.get("result").unwrap_or(&Value::Null),
            &self.filter,
        ))
    }
    fn close(&self) {
        self.transport.close();
    }
}

struct Slot {
    config: ServerConfig,
    generation: u64,
    stop: Arc<CancelToken>,
    client: Option<Arc<Client>>,
    status: Status,
    error: Option<String>,
    attempt: usize,
    bindings: Vec<ToolBinding>,
    active_calls: usize,
    configuration_revision: u64,
}
enum Completion {
    Connected {
        id: String,
        generation: u64,
        result: Result<Arc<Client>, String>,
    },
    Retry {
        id: String,
        generation: u64,
        attempt: usize,
    },
    Call {
        id: String,
        generation: u64,
        transport_failed: bool,
    },
    Test {
        request_id: String,
        result: Result<TestReport, String>,
    },
    TransportExited {
        id: String,
        generation: u64,
    },
}
struct Manager {
    slots: HashMap<String, Slot>,
    generation: u64,
    cache: Arc<Mutex<Snapshot>>,
    revision: Arc<AtomicU64>,
    events: SyncSender<ManagerEvent>,
    shutdown: Arc<CancelToken>,
    version: String,
    completed_tx: async_channel::Sender<Completion>,
    completed_rx: async_channel::Receiver<Completion>,
    tests: HashMap<String, Arc<CancelToken>>,
    active_calls: usize,
    desired: DesiredConfigurations,
}
impl Manager {
    fn new(
        cache: Arc<Mutex<Snapshot>>,
        revision: Arc<AtomicU64>,
        events: SyncSender<ManagerEvent>,
        shutdown: Arc<CancelToken>,
        version: String,
        desired: DesiredConfigurations,
    ) -> Self {
        let (tx, rx) = async_channel::channel(64);
        Self {
            slots: HashMap::new(),
            generation: 0,
            cache,
            revision,
            events,
            shutdown,
            version,
            completed_tx: tx,
            completed_rx: rx,
            tests: HashMap::new(),
            active_calls: 0,
            desired,
        }
    }
    fn generation(&mut self) -> Result<u64, String> {
        self.generation = self.generation.checked_add(1).ok_or("MCP 连接身份已耗尽")?;
        Ok(self.generation)
    }
    fn publish(&self) {
        let mut snapshot = Snapshot::default();
        snapshot.servers = self
            .slots
            .values()
            .map(|slot| ServerState {
                config: slot.config.metadata(),
                status: slot.status,
                tool_count: slot.bindings.len(),
                server_name: slot
                    .client
                    .as_ref()
                    .map(|client| client.name.clone())
                    .unwrap_or_default(),
                server_version: slot
                    .client
                    .as_ref()
                    .map(|client| client.version.clone())
                    .unwrap_or_default(),
                protocol_version: slot
                    .client
                    .as_ref()
                    .map(|client| client.protocol.clone())
                    .unwrap_or_default(),
                error: slot.error.clone(),
                attempt: slot.attempt,
                generation: slot.generation,
            })
            .collect();
        snapshot
            .servers
            .sort_by(|left, right| left.config.name.cmp(&right.config.name));
        snapshot.tools = self
            .slots
            .values()
            .filter(|slot| slot.status == Status::Ready)
            .flat_map(|slot| slot.bindings.clone())
            .collect();
        snapshot
            .tools
            .sort_by(|left, right| left.registered_name.cmp(&right.registered_name));
        snapshot.revision = self
            .revision
            .fetch_add(1, Ordering::AcqRel)
            .saturating_add(1);
        if let Ok(mut cache) = self.cache.lock() {
            snapshot.test_results = cache.test_results.clone();
            *cache = snapshot.clone();
        }
        let _ = self.events.try_send(ManagerEvent::Snapshot(snapshot));
    }
    fn configure(&mut self, configs: Vec<ServerConfig>) {
        let fingerprints = configuration_fingerprints(&configs);
        let versions: Option<HashMap<String, u64>> = self
            .desired
            .lock()
            .map(|desired| {
                if desired.len() != fingerprints.len()
                    || fingerprints.iter().any(|(id, fingerprint)| {
                        desired
                            .get(id)
                            .is_none_or(|entry| &entry.fingerprint != fingerprint)
                    })
                {
                    None
                } else {
                    Some(
                        desired
                            .iter()
                            .map(|(id, entry)| (id.clone(), entry.revision))
                            .collect(),
                    )
                }
            })
            .unwrap_or(None);
        let Some(versions) = versions else { return };
        let keep: HashSet<_> = configs.iter().map(|config| config.id.clone()).collect();
        self.slots.retain(|id, slot| {
            let keep = keep.contains(id);
            if !keep {
                slot.stop.cancel();
                if let Some(client) = slot.client.take() {
                    client.close();
                }
            }
            keep
        });
        let mut connect = Vec::new();
        for config in configs {
            let configuration_revision = versions[&config.id];
            if self.slots.get(&config.id).is_some_and(|slot| {
                slot.config.fingerprint() == config.fingerprint()
                    && slot.configuration_revision == configuration_revision
            }) {
                continue;
            }
            if let Some(slot) = self.slots.remove(&config.id) {
                slot.stop.cancel();
                if let Some(client) = slot.client {
                    client.close();
                }
            }
            let generation = match self.generation() {
                Ok(generation) => generation,
                Err(_) => {
                    self.shutdown.cancel();
                    return;
                }
            };
            let id = config.id.clone();
            let enabled = config.enabled;
            self.slots.insert(
                id.clone(),
                Slot {
                    config,
                    generation,
                    stop: Arc::new(CancelToken::new()),
                    client: None,
                    status: Status::Disconnected,
                    error: None,
                    attempt: 0,
                    bindings: Vec::new(),
                    active_calls: 0,
                    configuration_revision,
                },
            );
            if enabled {
                connect.push(id);
            }
        }
        self.publish();
        for id in connect {
            self.connect(&id, true);
        }
    }
    fn connect(&mut self, id: &str, manual: bool) {
        if !self.slots.get(id).is_some_and(|slot| {
            self.desired
                .lock()
                .map(|desired| {
                    desired.get(id).is_some_and(|entry| {
                        entry.fingerprint == slot.config.fingerprint()
                            && entry.revision == slot.configuration_revision
                    })
                })
                .unwrap_or(false)
        }) {
            return;
        }
        let generation = match self.generation() {
            Ok(generation) => generation,
            Err(_) => {
                self.shutdown.cancel();
                return;
            }
        };
        let Some(slot) = self.slots.get_mut(id) else {
            return;
        };
        slot.stop.cancel();
        if let Some(client) = slot.client.take() {
            client.close();
        }
        slot.stop = Arc::new(CancelToken::new());
        slot.generation = generation;
        slot.bindings.clear();
        slot.active_calls = 0;
        slot.status = Status::Connecting;
        slot.error = None;
        if manual {
            slot.attempt = 0;
        }
        let config = slot.config.clone();
        let cancel = slot.stop.clone();
        let shutdown = self.shutdown.clone();
        let tx = self.completed_tx.clone();
        let version = self.version.clone();
        let id = id.to_owned();
        let desired = self.desired.clone();
        let fingerprint = config.fingerprint();
        let configuration_revision = slot.configuration_revision;
        tokio::spawn(async move {
            let valid = desired
                .lock()
                .map(|desired| {
                    desired.get(&id).is_some_and(|entry| {
                        entry.fingerprint == fingerprint && entry.revision == configuration_revision
                    })
                })
                .unwrap_or(false);
            let result = if !valid {
                Err("MCP 配置已变更".into())
            } else {
                tokio::select! {biased;_=shutdown.cancelled()=>Err("MCP 管理器已关闭".into()),_=cancel.cancelled()=>Err("MCP 连接已取消".into()),result=tokio::time::timeout(CONNECT_TIMEOUT,Client::open(&config,&version,&cancel))=>result.unwrap_or_else(|_|Err("MCP 连接与工具发现超过 120 秒".into()))}
            };
            let _ = tx
                .send(Completion::Connected {
                    id,
                    generation,
                    result,
                })
                .await;
        });
        self.publish();
    }
    fn disconnect(&mut self, id: &str) {
        if let Some(slot) = self.slots.get_mut(id) {
            slot.stop.cancel();
            if let Some(client) = slot.client.take() {
                client.close();
            }
            slot.status = Status::Disconnected;
            slot.bindings.clear();
            slot.active_calls = 0;
            slot.error = None;
            slot.attempt = 0;
        }
        self.publish();
    }
    fn retry(&mut self, id: &str) {
        let Some(slot) = self.slots.get_mut(id) else {
            return;
        };
        if !slot.config.enabled || slot.attempt >= 5 {
            slot.status = if slot.config.enabled {
                Status::Failed
            } else {
                Status::Disconnected
            };
            self.publish();
            return;
        }
        slot.attempt += 1;
        slot.status = Status::Reconnecting;
        let attempt = slot.attempt;
        let generation = slot.generation;
        let cancel = slot.stop.clone();
        let shutdown = self.shutdown.clone();
        let tx = self.completed_tx.clone();
        let id = id.to_owned();
        let delay = [2, 4, 8, 16, 30][attempt - 1];
        tokio::spawn(async move {
            tokio::select! {biased;_=cancel.cancelled()=>(),_=shutdown.cancelled()=>(),_=tokio::time::sleep(Duration::from_secs(delay))=>{let _=tx.send(Completion::Retry{id,generation,attempt}).await;}}
        });
        self.publish();
    }
    fn watch_closed(
        &self,
        id: String,
        generation: u64,
        closed: Arc<CancelToken>,
        stop: Arc<CancelToken>,
    ) {
        let tx = self.completed_tx.clone();
        let shutdown = self.shutdown.clone();
        tokio::spawn(async move {
            tokio::select! {biased;_=stop.cancelled()=>(),_=shutdown.cancelled()=>(),_=closed.cancelled()=>{
                let _=tx.send(Completion::TransportExited{id,generation}).await;
            }}
        });
    }
    fn bindings(&self, id: &str, client: &Client) -> Result<Vec<ToolBinding>, String> {
        let slot = self.slots.get(id).ok_or("MCP 配置已移除")?;
        let configuration_fingerprint = slot.config.fingerprint();
        let mut used: HashSet<_> = self
            .slots
            .iter()
            .filter(|(other, _)| other.as_str() != id)
            .flat_map(|(_, slot)| {
                slot.bindings
                    .iter()
                    .map(|binding| binding.registered_name.clone())
            })
            .collect();
        let mut output = Vec::new();
        let mut total = self
            .slots
            .iter()
            .filter(|(other, _)| other.as_str() != id)
            .flat_map(|(_, slot)| slot.bindings.iter())
            .map(|binding| {
                binding.input_schema.to_string().len()
                    + binding.description.len()
                    + binding.registered_name.len()
            })
            .sum::<usize>();
        for tool in &client.tools {
            let registered_name = protocol::registered_name(&slot.config.name, &tool.name);
            if !used.insert(registered_name.clone()) {
                return Err("MCP 注册工具名发生冲突，请调整服务器或工具名称".into());
            }
            total = total
                .saturating_add(tool.schema.to_string().len())
                .saturating_add(tool.description.len())
                .saturating_add(registered_name.len());
            if used.len() > protocol::MAX_TOOLS || total > protocol::MAX_CATALOG {
                return Err("完整 MCP 目录超过 256 个工具或 1 MiB".into());
            }
            output.push(ToolBinding {
                server_id: id.into(),
                generation: slot.generation,
                registered_name,
                remote_name: tool.name.clone(),
                description: tool.description.clone(),
                input_schema: tool.schema.clone(),
                configuration_fingerprint,
                configuration_revision: slot.configuration_revision,
            });
        }
        Ok(output)
    }
    fn test(&mut self, request_id: String, config: ServerConfig, cancel: Arc<CancelToken>) {
        let queued = self
            .cache
            .lock()
            .map(|cache| cache.test_results.len())
            .unwrap_or(MAX_TEST_RESULTS);
        if self.tests.contains_key(&request_id) || self.tests.len() + queued >= MAX_TEST_RESULTS {
            let result = Err("MCP 连接测试队列已满或身份重复".into());
            self.test_result(request_id, result);
            return;
        }
        self.tests.insert(request_id.clone(), cancel.clone());
        let tx = self.completed_tx.clone();
        let version = self.version.clone();
        let shutdown = self.shutdown.clone();
        tokio::spawn(async move {
            let result = tokio::select! {biased;_=cancel.cancelled()=>Err("MCP 连接测试已取消".into()),_=shutdown.cancelled()=>Err("MCP 管理器已关闭".into()),result=tokio::time::timeout(CONNECT_TIMEOUT,Client::open(&config,&version,&cancel))=>result.unwrap_or_else(|_|Err("MCP 连接测试超过 120 秒".into()))};
            let result = result.map(|client| {
                let report = TestReport {
                    server_name: client.name.clone(),
                    server_version: client.version.clone(),
                    protocol_version: client.protocol.clone(),
                    tools: client.tools.iter().map(|tool| tool.name.clone()).collect(),
                };
                client.close();
                report
            });
            let _ = tx.send(Completion::Test { request_id, result }).await;
        });
    }
    fn test_result(&mut self, request_id: String, result: Result<TestReport, String>) {
        self.tests.remove(&request_id);
        if let Ok(mut cache) = self.cache.lock() {
            if let Some(previous) = cache
                .test_results
                .iter_mut()
                .find(|item| item.request_id == request_id)
            {
                previous.result = result.clone();
            } else if cache.test_results.len() < MAX_TEST_RESULTS {
                cache.test_results.push(TestCompletion {
                    request_id: request_id.clone(),
                    result: result.clone(),
                });
            }
        }
        let _ = self
            .events
            .try_send(ManagerEvent::TestFinished { request_id, result });
        self.publish();
    }
    fn call(
        &mut self,
        binding: ToolBinding,
        arguments: Value,
        cancel: Arc<CancelToken>,
        started: Arc<AtomicU8>,
        reply: SyncSender<Result<Value, String>>,
    ) {
        let slot = self.slots.get(&binding.server_id);
        let client = slot
            .filter(|slot| {
                slot.generation == binding.generation
                    && slot.status == Status::Ready
                    && slot.bindings.iter().any(|current| {
                        current.registered_name == binding.registered_name
                            && current.remote_name == binding.remote_name
                            && current.input_schema == binding.input_schema
                    })
            })
            .and_then(|slot| slot.client.clone());
        let Some(client) = client else {
            let _=reply.try_send(Ok(json!({"ok":false,"error":"MCP 连接或工具定义已变更，请重新加载工具目录","started":false,"effects_may_have_occurred":false})));
            return;
        };
        if self.active_calls >= 32
            || self
                .slots
                .get(&binding.server_id)
                .is_some_and(|slot| slot.active_calls >= 16)
        {
            let _=reply.try_send(Ok(json!({"ok":false,"error":"MCP 并发调用超过全局 32 个或单服务器 16 个","started":false,"effects_may_have_occurred":false})));
            return;
        }
        if cancel.is_cancelled() {
            let _=reply.try_send(Ok(json!({"ok":false,"cancelled":true,"started":false,"effects_may_have_occurred":false})));
            return;
        }
        self.active_calls += 1;
        if let Some(slot) = self.slots.get_mut(&binding.server_id) {
            slot.active_calls += 1;
        }
        let tx = self.completed_tx.clone();
        let shutdown = self.shutdown.clone();
        let desired = self.desired.clone();
        tokio::spawn(async move {
            let admitted = !cancel.is_cancelled()
                && desired
                    .lock()
                    .map(|desired| {
                        desired.get(&binding.server_id).is_some_and(|entry| {
                            entry.fingerprint == binding.configuration_fingerprint
                                && entry.revision == binding.configuration_revision
                        }) && started
                            .compare_exchange(0, 1, Ordering::AcqRel, Ordering::Acquire)
                            .is_ok()
                    })
                    .unwrap_or(false);
            let result = if !admitted {
                Ok(
                    json!({"ok":false,"error":"MCP 调用已取消或配置已变更，调用未发送","cancelled":cancel.is_cancelled()||started.load(Ordering::Acquire)==2,"started":false,"effects_may_have_occurred":false}),
                )
            } else {
                tokio::select! {biased;_=shutdown.cancelled()=>Err("MCP 管理器已关闭".into()),result=client.call(&binding.remote_name,arguments,&cancel)=>result}
            };
            let transport_failed =
                result.is_err() && !cancel.is_cancelled() && !shutdown.is_cancelled();
            let result=result.map_err(|error|client.filter.text(error)).unwrap_or_else(|error|json!({"ok":false,"error":error,"cancelled":cancel.is_cancelled(),"started":started.load(Ordering::Acquire)==1,"effects_may_have_occurred":started.load(Ordering::Acquire)==1}));
            let _ = reply.try_send(Ok(result));
            let _ = tx
                .send(Completion::Call {
                    id: binding.server_id,
                    generation: binding.generation,
                    transport_failed,
                })
                .await;
        });
    }
    async fn run(
        mut self,
        configs: Vec<ServerConfig>,
        mut commands: async_channel::Receiver<Command>,
    ) {
        self.configure(configs);
        loop {
            tokio::select! {biased;
                _=self.shutdown.cancelled()=>break,
                completed=self.completed_rx.recv()=>{let Some(completed)=completed else{break};match completed{
                    Completion::Connected{id,generation,result}=>{
                        if !self.slots.get(&id).is_some_and(|slot|slot.generation==generation&&slot.status==Status::Connecting){if let Ok(client)=result{client.close();}continue;}
                        match result{
                            Ok(client)=>match self.bindings(&id,&client){Ok(bindings)=>{let closed=client.transport.closed_signal();let slot=self.slots.get_mut(&id).expect("current slot");let stop=slot.stop.clone();slot.client=Some(client);slot.bindings=bindings;slot.status=Status::Ready;slot.error=None;slot.attempt=0;if let Some(closed)=closed{self.watch_closed(id.clone(),generation,closed,stop);}self.publish();},Err(error)=>{client.close();let slot=self.slots.get_mut(&id).expect("current slot");slot.error=Some(error);slot.status=Status::Failed;self.publish();}},
                            Err(error)=>{if let Some(slot)=self.slots.get_mut(&id){slot.error=Some(error);slot.client=None;slot.bindings.clear();}self.retry(&id);}
                        }
                    }
                    Completion::Retry{id,generation,attempt}=>{if self.slots.get(&id).is_some_and(|slot|slot.generation==generation&&slot.status==Status::Reconnecting&&slot.attempt==attempt&&slot.config.enabled){self.connect(&id,false);}},
                    Completion::Call{id,generation,transport_failed}=>{self.active_calls=self.active_calls.saturating_sub(1);if let Some(slot)=self.slots.get_mut(&id).filter(|slot|slot.generation==generation){slot.active_calls=slot.active_calls.saturating_sub(1);}if transport_failed&&self.slots.get(&id).is_some_and(|slot|slot.generation==generation&&slot.status==Status::Ready){if let Some(slot)=self.slots.get_mut(&id){if let Some(client)=slot.client.take(){client.close();}slot.bindings.clear();slot.error=Some("MCP 工具连接中断，调用未自动重放".into());slot.attempt=0;}self.retry(&id);}},
                    Completion::Test{request_id,result}=>self.test_result(request_id,result),
                    Completion::TransportExited{id,generation}=>{
                        if self.slots.get(&id).is_some_and(|slot|slot.generation==generation&&slot.status==Status::Ready){
                            if let Some(slot)=self.slots.get_mut(&id){if let Some(client)=slot.client.take(){client.close();}slot.bindings.clear();slot.error=Some("MCP stdio 进程已退出，正在恢复连接".into());slot.attempt=0;}
                            self.retry(&id);
                        }
                    },
                }},
                command=commands.recv()=>{let Some(command)=command else{break};match command{
                    Command::Configure(configs)=>self.configure(configs),Command::Connect(id)|Command::Refresh(id)=>self.connect(&id,true),Command::Disconnect(id)=>self.disconnect(&id),
                    Command::Test{request_id,config,cancel}=>self.test(request_id,config,cancel),
                    Command::Call{binding,arguments,cancel,started,reply}=>self.call(binding,arguments,cancel,started,reply),Command::Close=>break,
                }},
            }
        }
        self.shutdown.cancel();
        for (_, mut slot) in self.slots.drain() {
            slot.stop.cancel();
            if let Some(client) = slot.client.take() {
                client.close();
            }
        }
        for (_, cancel) in self.tests.drain() {
            cancel.cancel();
        }
        if let Ok(mut cache) = self.cache.lock() {
            for server in &mut cache.servers {
                server.status = Status::Disconnected;
                server.tool_count = 0;
            }
            cache.tools.clear();
            cache.revision = cache.revision.saturating_add(1);
            self.revision.store(cache.revision, Ordering::Release);
            let _ = self.events.try_send(ManagerEvent::Snapshot(cache.clone()));
        }
    }
}
