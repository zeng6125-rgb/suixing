//! Run-owned child tasks with independent model context and durable lifecycle records.
mod parallel_runtime;
mod retry_runtime;
use crate::{
    agent::{self, WorkerEvent},
    network::{self, CancelToken},
    state::{Message, Settings, ToolCall, UsageRecord},
    subagent_configuration::{
        ApprovalMode, ChildRunConfig, FrozenChildInputs, SubagentLaunchOptions,
        SubagentModelSelection, is_child_tool, resolve_child,
    },
    tools,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, VecDeque},
    path::{Path, PathBuf},
    sync::{
        Arc, Condvar, Mutex, MutexGuard,
        mpsc::{self, SyncSender, TrySendError},
    },
    thread::{self, JoinHandle},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use suixing_search::rust_protocol::{Message as WireMessage, ToolDefinition};

const MAX_TASKS: usize = 6;
const MAX_RESULT_UNITS: usize = 64_000;
const MAX_CALLS: usize = 64;
pub(crate) const MAX_USAGE_RECORDS: usize = 25;
// Leave space for the storage envelope, the status detail, and interrupted tool results.
const RECORD_BUDGET: usize = crate::store::MAX_CHILD_BYTES as usize - 16 * 1024;
const PENDING_RESERVE: usize = 64 * 1024;
const ANSWER_RESERVE: usize = MAX_RESULT_UNITS * 12 + 2048;
const RESULT_TRUNCATED: &str = "\n[子任务结果超过64,000字符，后续内容未保留]";
const THINKING_TRUNCATED: &str = "\n[子任务思考超过64,000字符，后续内容未保留]";

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Status {
    Queued,
    Running,
    Completed,
    Failed,
    Cancelled,
    TimedOut,
}

impl Status {
    pub fn terminal(self) -> bool {
        !matches!(self, Self::Queued | Self::Running)
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Queued => "排队中",
            Self::Running => "运行中",
            Self::Completed => "已完成",
            Self::Failed => "失败",
            Self::Cancelled => "已取消",
            Self::TimedOut => "超时",
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Record {
    pub id: String,
    pub name: String,
    pub status: Status,
    pub messages: Vec<Message>,
    pub detail: String,
    /// Every request keeps its own reported and unknown counts, rather than
    /// discarding known counts when another round omits its usage report.
    #[serde(default)]
    pub usage: Vec<UsageRecord>,
    /// The resolved choices are retained without runtime connection credentials.
    #[serde(default)]
    pub config: Option<ChildRunConfig>,
}

impl Record {
    fn result(&self) -> Value {
        let projected = crate::transcript_redaction::messages_for_transcript(&self.messages);
        let answer = projected.last().filter(|message| {
            self.status.terminal() && message.role == "assistant" && message.tool_calls.is_empty()
        });
        let mut tool_names = BTreeMap::new();
        let mut recent = VecDeque::with_capacity(4);
        let mut tool_count = 0usize;
        let mut effects_may_have_occurred = false;
        let mut committed = false;
        for message in &projected {
            if message.role != "tool" {
                tool_names.clear();
            }
            for call in &message.tool_calls {
                tool_names.insert(call.id.as_str(), call.name.as_str());
            }
            if message.role != "tool" {
                continue;
            }
            tool_count += 1;
            let reported = serde_json::from_str::<Value>(&message.text).ok();
            let is_committed = reported
                .as_ref()
                .and_then(|value| value.get("committed"))
                .and_then(Value::as_bool)
                == Some(true);
            // An ordinary successful read is not evidence of a side effect.
            // Only explicit outcome markers contribute to these summary flags.
            let possible_effect = is_committed
                || reported.as_ref().is_some_and(|value| {
                    [
                        "effect_unknown",
                        "effects_may_have_occurred",
                        "effects_unknown",
                    ]
                    .iter()
                    .any(|key| value.get(key).and_then(Value::as_bool) == Some(true))
                });
            committed |= is_committed;
            effects_may_have_occurred |= possible_effect;
            let name = message
                .tool_call_id
                .as_deref()
                .and_then(|id| tool_names.remove(id));
            if recent.len() == 4 {
                recent.pop_front();
            }
            recent.push_back((message, name, possible_effect, is_committed));
        }
        let recent_tool_results: Vec<Value> = recent
            .into_iter()
            .map(|(message, name, possible_effect, is_committed)| {
                let prefix = byte_prefix(&message.text, 2048);
                json!({
                    "tool_call_id":message.tool_call_id,"tool":name,
                    "text":prefix,"format":"tool_result_json_prefix",
                    "truncated":prefix.len()!=message.text.len(),
                    "effects_may_have_occurred":possible_effect,"committed":is_committed,
                })
            })
            .collect();
        json!({
            "id": self.id, "name": self.name, "status": self.status,
            "result": answer.map(|message| {
                if self.status == Status::Completed && is_blank(&message.text) {
                    "子任务结束，未返回文字结果。"
                } else {
                    message.text.as_str()
                }
            }).unwrap_or_default(),
            "detail": self.detail,
            "recent_tool_results":recent_tool_results,
            "recent_tool_results_truncated":tool_count>4,
            "effects_may_have_occurred":effects_may_have_occurred,
            "committed":committed,
            "config": self.config.as_ref().map(|config| json!({
                "profile_id":config.profile_id,"assistant":config.assistant_name,
                "model":config.model,"approval_mode":config.approval_mode,
                "timeout_seconds":config.timeout_seconds,
            })),
        })
    }
}

pub struct Task {
    task: String,
    context: String,
    name: String,
    options: SubagentLaunchOptions,
}

pub enum Command {
    Spawn(Task),
    Wait {
        ids: Option<Vec<String>>,
        timeout: Duration,
    },
    List,
    Cancel(String),
}

/// Only typed application input can supply an unsaved profile. The model-facing
/// parser below accepts declared choices and never constructs draft_profile.
pub fn typed_spawn(
    task: String,
    context: String,
    name: String,
    options: SubagentLaunchOptions,
) -> Result<Command, String> {
    if is_blank(&task)
        || task.encode_utf16().count() > 8_000
        || context.encode_utf16().count() > 64_000
        || name.len() > 1024 * 1024
    {
        return Err("子任务说明过长或为空，请只传递相关上下文".into());
    }
    Ok(Command::Spawn(Task {
        task,
        context,
        name,
        options,
    }))
}

pub fn definitions() -> Vec<ToolDefinition> {
    let definition = |name: &str, description: &str, properties: Value, required: &[&str]| {
        ToolDefinition {
            name: name.into(),
            description: description.into(),
            parameters: json!({"type":"object","properties":properties,"required":required,"additionalProperties":false}),
        }
    };
    vec![
        definition(
            "spawn_agent",
            "Start an independent child with only the supplied task and context, using a saved template or existing assistant/model. Child permissions may only restrict the parent. Nested delegation and goal tools are unavailable.",
            json!({
                "task":{"type":"string"}, "context":{"type":"string"}, "name":{"type":"string"},
                "profile_id":{"type":"string","description":"Saved child template ID. Omit for the configured default; empty follows the parent."},
                "assistant_id":{"type":"string","description":"Existing assistant ID. Empty follows the parent assistant."},
                "provider_id":{"type":"string","description":"Provider ID for an enabled model override. Supply with model."},
                "model":{"type":"string","description":"Enabled model ID. Supply with provider_id."},
                "timeout_seconds":{"type":"integer","minimum":10,"maximum":600},
                "tools":{"type":"array","maxItems":1024,"items":{"type":"string"},"description":"Restrict inherited tools. An empty list makes the child text-only."},
                "allowed_actions":{"type":"object","additionalProperties":{"type":"array","items":{"type":"string"}},"description":"Restrict action values for named tools; intersects the saved template."},
                "max_rounds":{"type":"integer","minimum":0,"description":"A positive tool-round ceiling cannot exceed the parent's. Zero adds no ceiling; shared request, storage and runtime budgets still apply."},
                "approval_mode":{"type":"string","enum":["auto","ask","deny"],"description":"May only restrict the parent policy. Deny keeps the catalogue visible but prevents all tool execution."}
            }),
            &["task"],
        ),
        definition(
            "wait_agents",
            "Wait for selected children. Wait timeout leaves child tasks running.",
            json!({
                "ids":{"type":"array","maxItems":6,"items":{"type":"string"}},
                "timeout_seconds":{"type":"integer","minimum":1,"maximum":120}
            }),
            &[],
        ),
        definition(
            "list_agents",
            "List this run's child tasks and their statuses.",
            json!({}),
            &[],
        ),
        definition(
            "cancel_agent",
            "Request cancellation of a child belonging to this run.",
            json!({"id":{"type":"string"}}),
            &["id"],
        ),
    ]
}

pub fn prepare(call: &ToolCall) -> Result<Command, String> {
    if call.arguments.len() > 1024 * 1024 {
        return Err("子任务参数超过上限".into());
    }
    let value: Value =
        serde_json::from_str(&call.arguments).map_err(|_| "子任务参数需要 JSON 对象")?;
    let args = value.as_object().ok_or("子任务参数需要 JSON 对象")?;
    let text = |key: &str, max: usize, required: bool| -> Result<String, String> {
        match args.get(key) {
            None if !required => Ok(String::new()),
            Some(value) => value
                .as_str()
                .filter(|text| (!required || !is_blank(text)) && text.encode_utf16().count() <= max)
                .map(str::to_owned)
                .ok_or_else(|| format!("无效的子任务参数 {key}")),
            None => Err(format!("缺少子任务参数 {key}")),
        }
    };
    let timeout = |default: u64, min: u64, max: u64| -> Result<Duration, String> {
        let seconds = match args.get("timeout_seconds") {
            None => default,
            Some(value) => value
                .as_u64()
                .filter(|seconds| (min..=max).contains(seconds))
                .ok_or("子任务超时时间超出允许范围")?,
        };
        Ok(Duration::from_secs(seconds))
    };
    let fields: &[&str] = match call.name.as_str() {
        "spawn_agent" => &[
            "task",
            "context",
            "name",
            "profile_id",
            "assistant_id",
            "provider_id",
            "model",
            "timeout_seconds",
            "tools",
            "allowed_actions",
            "max_rounds",
            "approval_mode",
        ],
        "wait_agents" => &["ids", "timeout_seconds"],
        "list_agents" => &[],
        "cancel_agent" => &["id"],
        _ => return Err("未知的子任务操作".into()),
    };
    if args.keys().any(|key| !fields.contains(&key.as_str())) {
        return Err("该子任务操作包含尚未支持的参数".into());
    }
    match call.name.as_str() {
        "spawn_agent" => {
            let allowed_tools = args
                .get("tools")
                .map(|value| {
                    value
                        .as_array()
                        .filter(|tools| tools.len() <= 1024)
                        .ok_or("无效的子任务工具列表")?
                        .iter()
                        .map(|name| {
                            name.as_str()
                                .filter(|name| {
                                    !name.is_empty() && name.len() <= 128 && !name.contains('\0')
                                })
                                .map(str::to_owned)
                                .ok_or("无效的子任务工具名称")
                        })
                        .collect::<Result<Vec<_>, _>>()
                })
                .transpose()?;
            let max_rounds = args
                .get("max_rounds")
                .map(|value| {
                    value
                        .as_u64()
                        .filter(|&rounds| rounds <= i32::MAX as u64)
                        .map(|rounds| rounds as u32)
                        .ok_or("子任务工具轮数必须为非负整数")
                })
                .transpose()?;
            let approval_mode = match args.get("approval_mode") {
                None => None,
                Some(mode) => match mode.as_str().map(str::to_ascii_lowercase).as_deref() {
                    Some("auto") => Some(ApprovalMode::Auto),
                    Some("ask") => Some(ApprovalMode::Ask),
                    Some("deny") => Some(ApprovalMode::Deny),
                    _ => return Err("approval_mode 应为 auto、ask 或 deny".into()),
                },
            };
            let optional_text = |key: &str, max: usize| -> Result<Option<String>, String> {
                if args.contains_key(key) {
                    text(key, max, false).map(Some)
                } else {
                    Ok(None)
                }
            };
            let provider = optional_text("provider_id", 128)?;
            let model = optional_text("model", 1024)?;
            let model = match (provider, model) {
                (Some(provider_id), Some(model)) => {
                    Some(SubagentModelSelection { provider_id, model })
                }
                (None, None) => None,
                _ => return Err("指定模型需要同时传入 provider_id 与 model".into()),
            };
            let allowed_actions = args
                .get("allowed_actions")
                .map(|value| {
                    serde_json::from_value::<BTreeMap<String, Vec<String>>>(value.clone())
                        .map_err(|_| "allowed_actions 必须为工具名到动作文本数组的对象")
                })
                .transpose()?;
            let timeout_seconds = args
                .get("timeout_seconds")
                .map(|_| timeout(180, 10, 600).map(|duration| duration.as_secs() as u32))
                .transpose()?;
            Ok(Command::Spawn(Task {
                task: text("task", 8_000, true)?,
                context: text("context", 64_000, false)?,
                name: text("name", usize::MAX, false)?,
                options: SubagentLaunchOptions {
                    profile_id: optional_text("profile_id", 128)?,
                    assistant_id: optional_text("assistant_id", 128)?,
                    model,
                    approval_mode,
                    allowed_tools,
                    allowed_actions,
                    max_tool_rounds: max_rounds,
                    timeout_seconds,
                    draft_profile: None,
                },
            }))
        }
        "wait_agents" => {
            let ids = args
                .get("ids")
                .map(|value| {
                    value
                        .as_array()
                        .filter(|ids| ids.len() <= MAX_TASKS)
                        .ok_or("无效的子任务 ID 列表")?
                        .iter()
                        .map(|id| {
                            id.as_str()
                                .filter(|id| !id.is_empty() && id.len() <= 128)
                                .map(str::to_owned)
                                .ok_or("无效的子任务 ID")
                        })
                        .collect::<Result<Vec<_>, _>>()
                })
                .transpose()?;
            Ok(Command::Wait {
                ids,
                timeout: timeout(30, 1, 120)?,
            })
        }
        "cancel_agent" => Ok(Command::Cancel(text("id", 128, true)?)),
        _ => Ok(Command::List),
    }
}

struct Entry {
    record: Record,
    cancel: Arc<CancelToken>,
}

#[derive(Default)]
struct Entries {
    children: Vec<Entry>,
    running: usize,
}

struct Shared {
    entries: Mutex<Entries>,
    changed: Condvar,
    parallelism: usize,
}

impl Shared {
    fn lock(&self) -> MutexGuard<'_, Entries> {
        self.entries
            .lock()
            .unwrap_or_else(|error| error.into_inner())
    }

    fn update(&self, record: Record, release_slot: bool) {
        let mut state = self.lock();
        if let Some(entry) = state
            .children
            .iter_mut()
            .find(|entry| entry.record.id == record.id)
        {
            entry.record = record;
        }
        if release_slot {
            state.running -= 1;
        }
        self.changed.notify_all();
    }

    fn acquire(&self, id: &str, cancel: &CancelToken) -> bool {
        let mut state = self.lock();
        loop {
            if cancel.is_cancelled() {
                return false;
            }
            let first = state.children.iter().find(|entry| {
                entry.record.status == Status::Queued && !entry.cancel.is_cancelled()
            });
            if state.running < self.parallelism && first.is_some_and(|entry| entry.record.id == id)
            {
                state.running += 1;
                return true;
            }
            (state, _) = self
                .changed
                .wait_timeout(state, Duration::from_millis(50))
                .unwrap_or_else(|error| error.into_inner());
        }
    }
}

pub struct Pool {
    run: String,
    tx: SyncSender<WorkerEvent>,
    workspace: PathBuf,
    frozen: FrozenChildInputs,
    shared: Arc<Shared>,
    threads: Vec<JoinHandle<()>>,
    reported: Vec<String>,
}

#[derive(Clone)]
struct ChildPolicy {
    workspace: PathBuf,
    definitions: Vec<ToolDefinition>,
    config: ChildRunConfig,
    plugins: Vec<crate::plugins::PluginEntry>,
    plugin_context: String,
    system_prompt: String,
    mcp_tools: Vec<crate::mcp::ToolBinding>,
    mcp: Option<crate::mcp::McpBridge>,
}

impl Pool {
    pub fn new(
        run: &str,
        tx: &SyncSender<WorkerEvent>,
        workspace: &Path,
        frozen: FrozenChildInputs,
    ) -> Self {
        let parallelism = frozen.catalog.subagent_parallelism.clamp(1, 3) as usize;
        Self {
            run: run.into(),
            tx: tx.clone(),
            workspace: workspace.to_path_buf(),
            frozen,
            shared: Arc::new(Shared {
                entries: Mutex::new(Entries::default()),
                changed: Condvar::new(),
                parallelism,
            }),
            threads: Vec::new(),
            reported: Vec::new(),
        }
    }

    fn spawn(&mut self, task: Task) -> Result<Record, String> {
        if self.shared.lock().children.len() >= MAX_TASKS {
            return Err("本次运行的子任务数量已达到上限".into());
        }
        let resolved = resolve_child(&task.options, &self.frozen)?;
        let policy = ChildPolicy {
            workspace: self.workspace.clone(),
            definitions: self
                .frozen
                .available_tools
                .iter()
                .filter(|tool| {
                    is_child_tool(&tool.name) && resolved.config.allowed_tools.contains(&tool.name)
                })
                .cloned()
                .collect(),
            config: resolved.config.clone(),
            plugins: self.frozen.plugins.clone(),
            plugin_context: self.frozen.plugin_context.clone(),
            system_prompt: resolved.system_prompt,
            mcp_tools: self.frozen.mcp_tools.clone(),
            mcp: self.frozen.mcp.clone(),
        };
        let id = crate::controller::unique_id();
        let label = if is_blank(&task.name) {
            task.task
                .split(['\r', '\n'])
                .find(|line| !is_blank(line))
                .map(|line| utf16_prefix(line.trim_matches(kotlin_whitespace), 32).to_owned())
                .unwrap_or_else(|| "子任务".into())
        } else {
            task.name
        };
        let mut text = format!("Task:\n{}", task.task);
        if !is_blank(&task.context) {
            text.push_str("\n\nRelevant context:\n");
            text.push_str(&task.context);
        }
        if !is_blank(&self.frozen.parent_goal) {
            text.push_str("\n\nParent objective:\n");
            text.push_str(&self.frozen.parent_goal);
        }
        let record = Record {
            id: id.clone(),
            name: utf16_prefix(&label, 64).into(),
            status: Status::Queued,
            messages: vec![Message {
                id: format!("{id}-input"),
                role: "user".into(),
                text,
                thinking: String::new(),
                complete: true,
                tool_calls: Vec::new(),
                tool_call_id: None,
                metadata: Default::default(),
            }],
            detail: String::new(),
            usage: Vec::new(),
            config: Some(resolved.config.clone()),
        };
        let cancel = Arc::new(CancelToken::new());
        save(&self.run, &self.tx, &record, Some(cancel.clone()))?;
        self.shared.lock().children.push(Entry {
            record: record.clone(),
            cancel: cancel.clone(),
        });
        let shared = self.shared.clone();
        let run = self.run.clone();
        let tx = self.tx.clone();
        let settings = resolved.settings;
        let queued = record.clone();
        let timeout = Duration::from_secs(u64::from(resolved.config.timeout_seconds));
        match thread::Builder::new()
            .name("rust-subagent".into())
            .spawn(move || {
                ChildRun {
                    run: &run,
                    tx: &tx,
                    shared: &shared,
                    cancel: &cancel,
                }
                .execute(settings, policy, queued, timeout)
            }) {
            Ok(thread) => self.threads.push(thread),
            Err(_) => {
                let mut failed = record;
                failed.status = Status::Failed;
                failed.detail = "无法创建子任务执行线程".into();
                finish(&self.run, &self.tx, &self.shared, failed.clone(), false);
                return Ok(failed);
            }
        }
        Ok(record)
    }

    pub fn execute(&mut self, command: Command, cancel: &CancelToken) -> Result<Value, String> {
        let records = match command {
            Command::Spawn(task) => vec![self.spawn(task)?],
            Command::List => self.snapshots(),
            Command::Cancel(id) => {
                let state = self.shared.lock();
                let entry = state
                    .children
                    .iter()
                    .find(|entry| entry.record.id == id)
                    .ok_or("本次运行没有该子任务")?;
                entry.cancel.cancel();
                self.shared.changed.notify_all();
                let record = entry.record.clone();
                let requested = !record.status.terminal();
                drop(state);
                self.mark_reported(std::slice::from_ref(&record));
                let mut result = result_json(std::slice::from_ref(&record));
                result["cancel_requested"] = json!(requested);
                return Ok(result);
            }
            Command::Wait { ids, timeout } => {
                let records = self.wait(ids.as_deref(), Some(timeout), cancel)?;
                records
            }
        };
        // list/cancel can return a terminal result as well as wait. Once the
        // parent has received it, automatic integration must not report it twice.
        self.mark_reported(&records);
        Ok(result_json(&records))
    }

    pub fn wait_all(&self, cancel: &CancelToken) -> Result<(), String> {
        self.wait(None, None, cancel).map(|_| ())
    }

    /// Freeze the selected set before waiting. Newly admitted UI children do not
    /// silently extend an existing wait-all request or change its result order.
    pub fn selected_ids(&self, ids: Option<&[String]>) -> Result<Vec<String>, String> {
        let state = self.shared.lock();
        match ids {
            None => Ok(state
                .children
                .iter()
                .map(|entry| entry.record.id.clone())
                .collect()),
            Some(ids) => ids
                .iter()
                .map(|id| {
                    state
                        .children
                        .iter()
                        .any(|entry| &entry.record.id == id)
                        .then(|| id.clone())
                        .ok_or_else(|| "本次运行没有该子任务".to_owned())
                })
                .collect(),
        }
    }

    /// Observe only the frozen selection. Reporting and user-launch admission
    /// belong to the parent; this short wait changes no execution state.
    pub fn wait_selected_step(
        &self,
        ids: &[String],
        timeout: Duration,
        cancel: &CancelToken,
    ) -> Result<(bool, Vec<Record>), String> {
        if cancel.is_cancelled() {
            return Err("任务已停止".into());
        }
        let mut state = self.shared.lock();
        let indices = ids
            .iter()
            .map(|id| {
                state
                    .children
                    .iter()
                    .position(|entry| &entry.record.id == id)
                    .ok_or_else(|| "本次运行没有该子任务".to_owned())
            })
            .collect::<Result<Vec<_>, _>>()?;
        if !timeout.is_zero()
            && indices
                .iter()
                .any(|&index| !state.children[index].record.status.terminal())
        {
            (state, _) = self
                .shared
                .changed
                .wait_timeout(state, timeout.min(Duration::from_millis(50)))
                .unwrap_or_else(|error| error.into_inner());
        }
        if cancel.is_cancelled() {
            return Err("任务已停止".into());
        }
        let active = indices
            .iter()
            .any(|&index| !state.children[index].record.status.terminal());
        let records = indices
            .iter()
            .map(|&index| state.children[index].record.clone())
            .collect();
        Ok((active, records))
    }

    /// Yield regularly so the parent can admit typed UI launches while it is
    /// waiting for existing children. true means at least one child is active.
    pub fn wait_step(&self, cancel: &CancelToken) -> Result<bool, String> {
        if cancel.is_cancelled() {
            return Err("任务已停止".into());
        }
        let state = self.shared.lock();
        if state
            .children
            .iter()
            .all(|entry| entry.record.status.terminal())
        {
            return Ok(false);
        }
        let (state, _) = self
            .shared
            .changed
            .wait_timeout(state, Duration::from_millis(50))
            .unwrap_or_else(|error| error.into_inner());
        if cancel.is_cancelled() {
            return Err("任务已停止".into());
        }
        Ok(state
            .children
            .iter()
            .any(|entry| !entry.record.status.terminal()))
    }

    pub fn has_children(&self) -> bool {
        !self.shared.lock().children.is_empty()
    }

    pub fn has_outstanding_results(&self) -> bool {
        self.shared.lock().children.iter().any(|entry| {
            !entry.record.status.terminal() || !self.reported.contains(&entry.record.id)
        })
    }

    fn wait(
        &self,
        ids: Option<&[String]>,
        timeout: Option<Duration>,
        cancel: &CancelToken,
    ) -> Result<Vec<Record>, String> {
        let deadline = timeout.map(|timeout| Instant::now() + timeout);
        let mut state = self.shared.lock();
        let indices: Vec<usize> = match ids {
            None => (0..state.children.len()).collect(),
            Some(ids) => ids
                .iter()
                .map(|id| {
                    state
                        .children
                        .iter()
                        .position(|entry| &entry.record.id == id)
                        .ok_or("本次运行没有该子任务")
                })
                .collect::<Result<_, _>>()?,
        };
        loop {
            if cancel.is_cancelled() {
                return Err("任务已停止".into());
            }
            if indices
                .iter()
                .all(|&index| state.children[index].record.status.terminal())
                || deadline.is_some_and(|deadline| Instant::now() >= deadline)
            {
                return Ok(indices
                    .iter()
                    .map(|&index| state.children[index].record.clone())
                    .collect());
            }
            (state, _) = self
                .shared
                .changed
                .wait_timeout(state, Duration::from_millis(25))
                .unwrap_or_else(|error| error.into_inner());
        }
    }

    fn snapshots(&self) -> Vec<Record> {
        self.shared
            .lock()
            .children
            .iter()
            .map(|entry| entry.record.clone())
            .collect()
    }

    pub fn unreported(&self) -> Vec<Record> {
        self.snapshots()
            .into_iter()
            .filter(|record| record.status.terminal() && !self.reported.contains(&record.id))
            .collect()
    }

    pub fn mark_reported(&mut self, records: &[Record]) {
        for record in records.iter().filter(|record| record.status.terminal()) {
            if !self.reported.contains(&record.id) {
                self.reported.push(record.id.clone());
            }
        }
    }
}

impl Drop for Pool {
    fn drop(&mut self) {
        {
            let entries = self.shared.lock();
            for entry in &entries.children {
                entry.cancel.cancel();
            }
            self.shared.changed.notify_all();
        }
        for child in self.threads.drain(..) {
            let _ = child.join();
        }
    }
}

pub fn result_json(records: &[Record]) -> Value {
    json!({"ok":true,"children":records.iter().map(Record::result).collect::<Vec<_>>()})
}

fn save(
    run: &str,
    tx: &SyncSender<WorkerEvent>,
    record: &Record,
    cancel: Option<Arc<CancelToken>>,
) -> Result<(), String> {
    let (reply, receive) = mpsc::sync_channel(1);
    let mut projected = record.clone();
    projected.messages = crate::transcript_redaction::messages_for_transcript(&record.messages);
    tx.send(WorkerEvent::SaveChild {
        run: run.into(),
        record: projected,
        cancel,
        reply,
    })
    .map_err(|_| "子任务记录写入连接已中断")?;
    receive.recv().map_err(|_| "未收到子任务记录保存确认")?
}

fn finish(
    run: &str,
    tx: &SyncSender<WorkerEvent>,
    shared: &Shared,
    mut record: Record,
    release_slot: bool,
) {
    if let Err(error) = save(run, tx, &record, None) {
        record.status = Status::Failed;
        record.detail = format!("子任务结果保存失败：{error}");
    }
    shared.update(record, release_slot);
}

struct ChildRun<'a> {
    run: &'a str,
    tx: &'a SyncSender<WorkerEvent>,
    shared: &'a Shared,
    cancel: &'a Arc<CancelToken>,
}

impl ChildRun<'_> {
    fn execute(
        &self,
        settings: Settings,
        policy: ChildPolicy,
        mut record: Record,
        timeout: Duration,
    ) {
        if !self.shared.acquire(&record.id, self.cancel) {
            record.status = Status::Cancelled;
            record.detail = "子任务在排队期间取消".into();
            finish(self.run, self.tx, self.shared, record, false);
            return;
        }
        let deadline = Instant::now() + timeout;
        self.cancel.set_deadline(timeout);
        let mut ledger = agent::run_ledger::RunLedger::new(policy.config.max_tool_rounds);
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            record.status = Status::Running;
            self.checkpoint(&record)?;
            self.request(&settings, &policy, &mut record, &mut ledger)
        }));
        (record.status, record.detail) = match result {
            Ok(Ok(())) => (Status::Completed, String::new()),
            Ok(Err(_)) if Instant::now() >= deadline => {
                (Status::TimedOut, "子任务超过运行时限".into())
            }
            Ok(Err(_)) if self.cancel.is_cancelled() => (Status::Cancelled, "子任务已取消".into()),
            Ok(Err(error)) => (Status::Failed, error),
            Err(_) => (Status::Failed, "子任务执行线程异常".into()),
        };
        if record.status != Status::Completed {
            ledger.attach_summary(&mut record.messages, &record.id);
        }
        if record.messages.last().is_some_and(|message| {
            message.role == "assistant"
                && !message.complete
                && message.text.is_empty()
                && message.thinking.is_empty()
                && message.tool_calls.is_empty()
        }) {
            record.messages.pop();
        }
        agent::reconcile_history(&mut record.messages);
        for usage in record.usage.iter_mut().filter(|usage| usage.pending) {
            usage.pending = false;
            usage.error = Some("子请求中断，未收到最终用量确认；未知计数保留为未知".into());
        }
        finish(self.run, self.tx, self.shared, record, true);
    }

    fn checkpoint(&self, record: &Record) -> Result<(), String> {
        save(self.run, self.tx, record, None)?;
        self.shared.update(record.clone(), false);
        Ok(())
    }

    fn request(
        &self,
        settings: &Settings,
        policy: &ChildPolicy,
        record: &mut Record,
        ledger: &mut agent::run_ledger::RunLedger,
    ) -> Result<(), String> {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|_| "无法创建子任务网络运行时")?;
        let profile_prefix =
            agent::profile_runtime::injected(self.run, Some(&record.id), self.tx, self.cancel)?;
        let mut total_calls = 0;
        // Zero is not a text-only request. A positive configured ceiling applies
        // in addition to the explicit request/storage/tool/time resource limits.
        let tool_rounds = if policy.config.max_tool_rounds == 0 {
            MAX_USAGE_RECORDS - 1
        } else {
            (policy.config.max_tool_rounds as usize).min(MAX_USAGE_RECORDS - 1)
        };
        let request_budget = Arc::new(network::RequestBudget::new(MAX_USAGE_RECORDS));
        for round in 0..=tool_rounds {
            self.check_running()?;
            if record_size(record)? > RECORD_BUDGET - ANSWER_RESERVE {
                return Err("子任务记录达到存储上限，已保留现有结果".into());
            }
            let definitions = if round == tool_rounds || total_calls >= MAX_CALLS {
                &[][..]
            } else {
                &policy.definitions
            };
            let approval_rule = match policy.config.approval_mode {
                ApprovalMode::Auto => {
                    "The effective policy is AUTO: only the advertised tools and permitted actions may run under the inherited authorization. Hooks can still require user confirmation or deny an operation; follow those decisions."
                }
                ApprovalMode::Ask => {
                    "The effective policy is ASK: every tool call requires a separate explicit user decision. An earlier approval never authorizes another invocation."
                }
                ApprovalMode::Deny => {
                    "The effective policy is DENY: the visible tool catalogue grants no execution permission. None of its tools may execute; use the supplied context and existing evidence without attempting equivalent actions under another name."
                }
            };
            let instructions = if definitions.is_empty() {
                format!(
                    "You are an independent JAgent subagent. Return a concise result with evidence from the supplied task, context and recorded outcomes. Do not call tools in this reply. {approval_rule}"
                )
            } else {
                format!(
                    "You are an independent JAgent subagent. Use only the advertised tools and permitted actions. {approval_rule} A denial does not authorize an equivalent action. Preserve the actual tool outcomes, and verify any possible effect before retrying. Return a concise result with evidence. This run has a 25-request and 64-tool resource ceiling, storage/output budgets, and its configured timeout, even when the configured tool-round ceiling is zero. Nested delegation, goal management, todos and assistant creation are unavailable. The advertised session-status read is limited to this child run."
                )
            };
            let mut wire = Vec::new();
            if !profile_prefix.is_empty() {
                wire.push(WireMessage::system(&profile_prefix));
            }
            if !is_blank(&policy.system_prompt) {
                wire.push(WireMessage::system(&policy.system_prompt));
            }
            wire.push(WireMessage::system(instructions));
            if !policy.plugin_context.is_empty() {
                wire.push(WireMessage::system(&policy.plugin_context));
            }
            wire.extend(
                record
                    .messages
                    .iter()
                    .filter(|message| !agent::retry_runtime::failed(message))
                    .map(agent::wire_message),
            );
            if record.usage.len() >= MAX_USAGE_RECORDS {
                return Err("子任务请求数超过 25 个，未发送下一请求".into());
            }
            if !definitions.is_empty()
                && let Some(note) = ledger.take_note()
            {
                wire.push(WireMessage::user(note));
            }
            let response = self.generation(
                record,
                settings,
                wire,
                definitions,
                &runtime,
                request_budget.clone(),
            )?;
            if let Some(error) = response.error {
                return Err(error);
            }
            let answer = record.messages.last_mut().expect("child assistant reply");
            if response.turn.tool_calls.is_empty() {
                answer.complete = true;
                return Ok(());
            }
            if definitions.is_empty() {
                return Err("子任务没有返回有效的无工具结果".into());
            }
            let calls = agent::parse_tool_calls(response.turn.tool_calls)?;
            total_calls += calls.len();
            if total_calls > MAX_CALLS {
                return Err("子任务工具调用超过 64 个，未执行本批工具".into());
            }
            answer.tool_calls = calls.clone();
            if record_size(record)? > RECORD_BUDGET - ANSWER_RESERVE - PENDING_RESERVE {
                record
                    .messages
                    .last_mut()
                    .expect("child assistant reply")
                    .tool_calls
                    .clear();
                return Err("子任务记录没有空间保存调用，未执行本批工具".into());
            }
            record
                .messages
                .last_mut()
                .expect("child assistant reply")
                .complete = true;
            if let Err(error) = self.checkpoint(record) {
                append_not_dispatched(record, calls.into_iter(), &error);
                return Err(error);
            }
            self.execute_tool_batch(settings, policy, &runtime, record, &calls, ledger)?;
        }
        unreachable!("the final child round returns a result or an error")
    }

    fn check_running(&self) -> Result<(), String> {
        if self.cancel.is_cancelled() {
            Err("子任务已停止".into())
        } else {
            Ok(())
        }
    }

    fn call_tool(
        &self,
        settings: &Settings,
        policy: &ChildPolicy,
        runtime: &tokio::runtime::Runtime,
        record: &Record,
        call: &ToolCall,
    ) -> Result<Value, ToolFailure> {
        if !is_child_tool(&call.name) {
            return Ok(
                json!({"ok":false,"started":false,"error":"子任务不能调用主任务控制或会话管理工具"}),
            );
        }
        if !policy.definitions.iter().any(|tool| tool.name == call.name) {
            return Ok(json!({"ok":false,"error":"该工具不在子任务允许列表，未执行"}));
        }
        let arguments: Value = match serde_json::from_str(&call.arguments) {
            Ok(Value::Object(args)) => Value::Object(args),
            _ => {
                return Ok(
                    json!({"ok":false,"started":false,"error":"子任务工具参数必须为 JSON 对象"}),
                );
            }
        };
        if let Some(error) = policy.config.denial_reason(&call.name, &arguments) {
            return Ok(json!({"ok":false,"started":false,"denied":true,"error":error}));
        }
        if policy.config.approval_mode == ApprovalMode::Deny {
            return Ok(
                json!({"ok":false,"started":false,"denied":true,"approval_mode":"deny","error":"子任务配置禁止执行工具；目录保留不表示授权"}),
            );
        }
        let prepared = if matches!(call.name.as_str(), "profile_read" | "profile_write") {
            match agent::profile_runtime::prepare(
                self.run,
                Some(&record.id),
                call,
                self.tx,
                self.cancel,
            ) {
                Ok(prepared) => PreparedCall::Profile(prepared),
                Err(error) => return Ok(json!({"ok":false,"started":false,"error":error})),
            }
        } else if crate::controller::session_runtime::is_tool(&call.name) {
            // This worker sends the exact child wire directly and has no built
            // ContextInput estimate. Keep that measurement unavailable.
            let context =
                agent::session_runtime::snapshot(settings, None, 0, record.messages.len());
            match agent::session_runtime::prepare(
                self.run,
                Some(&record.id),
                call,
                settings,
                context,
                self.tx,
                self.cancel,
            ) {
                Ok(prepared) => PreparedCall::Session(prepared),
                Err(error) => return Ok(json!({"ok":false,"started":false,"error":error})),
            }
        } else if let Some(binding) = policy
            .mcp_tools
            .iter()
            .find(|binding| binding.registered_name == call.name)
        {
            let Some(bridge) = policy.mcp.as_ref() else {
                return Ok(
                    json!({"ok":false,"started":false,"effects_may_have_occurred":false,"error":"MCP 运行时已不可用，未执行子任务工具"}),
                );
            };
            PreparedCall::Mcp(bridge, binding)
        } else if let Some((entry, tool)) =
            crate::plugin_runtime::resolve(&policy.plugins, &call.name)
        {
            match crate::plugin_runtime::prepare(entry, tool, call, self.cancel) {
                Ok(prepared) => PreparedCall::Plugin(prepared),
                Err(error) => return Ok(json!({"ok":false,"started":false,"error":error})),
            }
        } else {
            match agent::prepare_for_worker(self.run, Some(&record.id), call, self.tx, self.cancel)
            {
                Ok(prepared) => PreparedCall::Builtin(prepared),
                Err(error) => return Ok(json!({"ok":false,"error":error})),
            }
        };
        // Include escaping the tool JSON a second time as message text. Skill lists
        // can include 256 descriptions, and HTTP results can include long headers.
        let reserve = match &prepared {
            PreparedCall::Builtin(tools::Prepared {
                operation: tools::Operation::Platform(_),
                ..
            }) => 5 * 1024 * 1024,
            PreparedCall::Builtin(tools::Prepared {
                operation: tools::Operation::Skill(crate::skills::Operation::List),
                ..
            }) => 5 * 1024 * 1024,
            PreparedCall::Builtin(tools::Prepared {
                operation: tools::Operation::Web(_),
                ..
            })
            | PreparedCall::Plugin(_)
            | PreparedCall::Mcp(_, _) => 2 * 1024 * 1024,
            _ => 768 * 1024,
        };
        if record_size(record).map_err(ToolFailure::not_dispatched)?
            > RECORD_BUDGET - ANSWER_RESERVE - PENDING_RESERVE - reserve
        {
            return Ok(
                json!({"ok":false,"error":"子任务记录没有足够空间保存工具结果，未执行工具"}),
            );
        }
        let child_id = Some(record.id.as_str());
        let approved = agent::approve_with_summary(
            self.run,
            child_id,
            call,
            match &prepared {
                PreparedCall::Profile(prepared) => Some(prepared.summary()),
                PreparedCall::Session(prepared) => Some(prepared.summary()),
                PreparedCall::Plugin(prepared) => Some(prepared.approval_summary.clone()),
                PreparedCall::Builtin(prepared) => Some(prepared.path.clone()),
                _ => None,
            },
            policy.config.approval_mode,
            self.tx,
            self.cancel,
        );
        if self.cancel.is_cancelled() {
            return Ok(cancelled_before_dispatch());
        }
        if !approved.map_err(ToolFailure::not_dispatched)? {
            return Ok(
                json!({"ok":false,"denied":true,"error":"用户拒绝或审批超时；该操作未执行"}),
            );
        }
        let _tool_activity = crate::tool_execution::activity(self.run, &call.name);
        match prepared {
            PreparedCall::Profile(prepared) => agent::profile_runtime::execute(self.run, child_id, &prepared, self.tx, self.cancel)
                .map_err(|error| ToolFailure { error, dispatched: prepared.writes() }),
            PreparedCall::Session(prepared) => agent::session_runtime::execute(self.run, child_id, &prepared, self.tx, self.cancel).map_err(|error| ToolFailure { error, dispatched: prepared.writes() }),
            PreparedCall::Builtin(prepared) => agent::execute_prepared(
                self.run,
                child_id,
                &prepared,
                &policy.workspace,
                runtime,
                self.tx,
                self.cancel,
            )
            .map_err(|error| ToolFailure {
                error,
                dispatched: true,
            }),
            PreparedCall::Plugin(prepared) => Ok(runtime.block_on(crate::plugin_runtime::execute_prepared(&prepared, self.cancel.clone()))),
            PreparedCall::Mcp(bridge, binding) => Ok(bridge
                .call(binding, &call.arguments, self.cancel.clone())
                .unwrap_or_else(|error| {
                    json!({"ok":false,"started":false,"effects_may_have_occurred":false,"error":error})
                })),
        }
    }
}

enum PreparedCall<'a> {
    Profile(crate::controller::profile_tools_runtime::Prepared),
    Session(crate::controller::session_runtime::Prepared),
    Builtin(tools::Prepared),
    Plugin(crate::plugin_runtime::Prepared<'a>),
    Mcp(&'a crate::mcp::McpBridge, &'a crate::mcp::ToolBinding),
}

struct ToolFailure {
    error: String,
    dispatched: bool,
}

impl ToolFailure {
    fn not_dispatched(error: String) -> Self {
        Self {
            error,
            dispatched: false,
        }
    }
}

fn append_not_dispatched(record: &mut Record, calls: impl Iterator<Item = ToolCall>, reason: &str) {
    record.messages.extend(calls.map(|call| {
        tool_result(
            call.id,
            json!({"ok":false,"started":false,"error":format!("前序步骤中断，未执行此工具：{reason}")}),
        )
    }));
}

/// Keep a bounded unsent suffix when the actor queue is busy. The final record
/// checkpoint always carries the complete response, including any unsent delta.
struct ChildDeltas<'a> {
    run: &'a str,
    child: &'a str,
    message: &'a str,
    tx: &'a SyncSender<WorkerEvent>,
    thinking: String,
    text: String,
    last: Instant,
}

impl<'a> ChildDeltas<'a> {
    fn new(
        run: &'a str,
        child: &'a str,
        message: &'a str,
        tx: &'a SyncSender<WorkerEvent>,
    ) -> Self {
        Self {
            run,
            child,
            message,
            tx,
            thinking: String::new(),
            text: String::new(),
            last: Instant::now(),
        }
    }

    fn append(&mut self, thinking: bool, text: &str) {
        if thinking {
            self.thinking.push_str(text);
        } else {
            self.text.push_str(text);
        }
        self.flush(false);
    }

    fn flush(&mut self, force: bool) {
        if !force && self.last.elapsed() < Duration::from_millis(32) {
            return;
        }
        self.last = Instant::now();
        for thinking in [true, false] {
            let pending = if thinking {
                &mut self.thinking
            } else {
                &mut self.text
            };
            if pending.is_empty() {
                continue;
            }
            let event = WorkerEvent::ChildDelta {
                run: self.run.into(),
                child_id: self.child.into(),
                message_id: self.message.into(),
                thinking,
                text: std::mem::take(pending),
            };
            if let Err(TrySendError::Full(WorkerEvent::ChildDelta { text, .. })) =
                self.tx.try_send(event)
            {
                *pending = text;
            }
        }
    }
}

fn tool_result(call_id: String, result: Value) -> Message {
    agent::tool_message(call_id, result)
}

fn cancelled_before_dispatch() -> Value {
    json!({"ok":false,"cancelled":true,"error":"子任务已停止，未执行工具"})
}

fn record_size(record: &Record) -> Result<usize, String> {
    serde_json::to_vec(record)
        .map(|data| data.len())
        .map_err(|_| "无法编码子任务记录".into())
}

/// Kotlin's valid-Unicode whitespace set, including the Java separator controls
/// that Rust's generic trim does not include. Task/context are otherwise retained.
fn kotlin_whitespace(ch: char) -> bool {
    matches!(
        ch,
        '\u{0009}'..='\u{000d}'
            | '\u{001c}'..='\u{0020}'
            | '\u{00a0}'
            | '\u{1680}'
            | '\u{2000}'..='\u{200a}'
            | '\u{2028}'
            | '\u{2029}'
            | '\u{202f}'
            | '\u{205f}'
            | '\u{3000}'
    )
}

fn is_blank(text: &str) -> bool {
    text.chars().all(kotlin_whitespace)
}

/// Preserve a Unicode scalar boundary while measuring the old UTF-16 budget.
/// A supplementary character that straddles the ceiling is omitted in full.
fn utf16_prefix(text: &str, maximum: usize) -> &str {
    let mut units = 0;
    for (index, ch) in text.char_indices() {
        if units + ch.len_utf16() > maximum {
            return &text[..index];
        }
        units += ch.len_utf16();
    }
    text
}

fn byte_prefix(text: &str, maximum: usize) -> &str {
    let mut end = maximum.min(text.len());
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}
