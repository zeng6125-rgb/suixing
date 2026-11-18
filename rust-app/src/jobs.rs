//! Process-owned background jobs. Metadata precedes process launch, logs have
//! byte cursors, and process death never grants permission to replay a command.
mod manager;
pub(crate) mod observation;
mod process;
mod storage;
use crate::{chat::FileActivity, shell_environment::Backend, state::ToolCall};
pub use manager::{Config, Manager, Subscription};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use suixing_search::rust_protocol::ToolDefinition;

pub const MAX_ACTIVE: usize = 8;
pub const MAX_RECORDS: usize = 128;
pub const MAX_LOG_BYTES: u64 = 32 * 1024 * 1024;
pub const MAX_READ_BYTES: usize = 256 * 1024;
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct JobKey {
    pub process_token: String,
    pub id: String,
}
pub(crate) fn identifier(value: &str) -> Result<(), String> {
    if value.is_empty()
        || value.len() > 128
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
    {
        Err("作业身份无效".into())
    } else {
        Ok(())
    }
}
impl JobKey {
    pub fn validate(&self) -> Result<(), String> {
        identifier(&self.process_token)?;
        identifier(&self.id)
    }
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    Claimed,
    Running,
    Cancelling,
    Done,
    Failed,
    Cancelled,
    TimedOut,
    Interrupted,
}
impl Phase {
    pub fn active(self) -> bool {
        matches!(self, Self::Claimed | Self::Running | Self::Cancelling)
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Info {
    pub id: String,
    pub label: String,
    pub command: String,
    #[serde(rename = "startedAt", alias = "started_at")]
    pub started_at: i64,
    #[serde(rename = "logPath", alias = "log_path")]
    pub log_path: String,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct ObservedChanges {
    pub activities: Vec<FileActivity>,
    pub notice: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Delivery {
    pub request_key: String,
    pub from: usize,
    pub count: usize,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Record {
    pub key: JobKey,
    pub info: Info,
    pub phase: Phase,
    pub started: bool,
    pub pid: Option<u32>,
    pub ended_at: Option<i64>,
    pub elapsed_ms: u64,
    pub exit_code: Option<i32>,
    pub signal: Option<i32>,
    pub error: Option<String>,
    pub deadline_ms: u64,
    pub backend: Backend,
    pub log_bytes: u64,
    pub output_truncated: bool,
    pub owner_chat_id: Option<String>,
    #[serde(default)]
    pub file_changes: ObservedChanges,
    #[serde(default)]
    pub changes_consumed: usize,
    #[serde(default)]
    pub deliveries: Vec<Delivery>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Status {
    pub info: Info,
    pub key: JobKey,
    pub status: Phase,
    pub elapsed_ms: u64,
    pub exit_code: Option<i32>,
    pub signal: Option<i32>,
    pub last_line: String,
    pub started: bool,
    pub output_truncated: bool,
    pub error: Option<String>,
    pub log_error: Option<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Output {
    pub text: String,
    pub from: u64,
    pub next_offset: u64,
    pub log_length: u64,
    pub has_more: bool,
    pub window_truncated: bool,
    pub invalid_utf8: bool,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ServiceRow {
    pub key: JobKey,
    pub phase: Phase,
}
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Snapshot {
    pub revision: u64,
    pub process_token: String,
    pub running: Vec<ServiceRow>,
    pub alive: bool,
}
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub enum Protection {
    #[default]
    Unavailable,
    Requested,
    Confirmed,
    Failed(String),
}
#[derive(Clone, Debug)]
pub struct Caller {
    pub request_key: String,
    pub chat_id: Option<String>,
}
impl Caller {
    pub fn validate(&self) -> Result<(), String> {
        if self.request_key.is_empty()
            || self.request_key.len() > 1024
            || self.request_key.contains('\0')
        {
            return Err("作业请求缺少稳定身份".into());
        }
        if let Some(chat) = &self.chat_id {
            identifier(chat)?;
        }
        Ok(())
    }
}
#[derive(Clone, Debug)]
pub struct StartSpec {
    pub command: String,
    pub label: String,
    pub observe_paths: Vec<std::path::PathBuf>,
    pub deadline_ms: u64,
    pub backend: Backend,
    pub prepared_id: String,
}
#[derive(Clone, Debug)]
pub enum Prepared {
    Start(StartSpec),
    List,
    Output {
        id: String,
        tail: usize,
        since: Option<u64>,
    },
    Kill {
        id: String,
    },
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
            "job_start",
            "Claim and start an independent app-UID background command. It outlives this model turn; poll job_output or cancel job_kill. A claimed record/start request is not a confirmed process or Android foreground lease. Interrupted attempts must not be blindly repeated. Requires approval.",
            json!({"command":{"type":"string"},"label":{"type":"string"},"observe_paths":{"type":"array","items":{"type":"string"},"maxItems":32},"deadline_ms":{"type":"integer","minimum":1000,"maximum":86400000,"default":3600000},"shell":{"type":"string","enum":["auto","sh","bash","busybox","alpine","debian"],"default":"auto"}}),
            &["command"],
        ),
        definition(
            "job_list",
            "List durable background job records and bounded last log lines. Recovered jobs are interrupted; old PIDs are never restored. Completed file observations are consumed once by their owning chat.",
            json!({}),
            &[],
        ),
        definition(
            "job_output",
            "Read a job log. Without since, return up to tail trailing lines. With since, read sequential bytes from that exact offset, up to256 KiB, and return next_offset/has_more. A cursor does not skip an unread burst. Requires approval.",
            json!({"job_id":{"type":"string"},"tail":{"type":"integer","minimum":1,"maximum":2000,"default":50},"since":{"type":"integer","minimum":0}}),
            &["job_id"],
        ),
        definition(
            "job_kill",
            "Request cancellation of the exact live job identity. The worker signals its still-owned process group with TERM, then KILL after2 seconds; accepted cancellation is not confirmed exit. Daemon processes that leave the group cannot be claimed stopped. Requires approval.",
            json!({"job_id":{"type":"string"}}),
            &["job_id"],
        ),
    ]
}
pub fn prepare(call: &ToolCall) -> Result<Prepared, String> {
    if call.arguments.len() > 128 * 1024 {
        return Err("作业参数超过128 KiB".into());
    }
    let value: Value = serde_json::from_str(&call.arguments).map_err(|_| "作业参数不是JSON")?;
    let args = value.as_object().ok_or("作业参数必须是对象")?;
    let allowed: &[&str] = match call.name.as_str() {
        "job_start" => &["command", "label", "observe_paths", "deadline_ms", "shell"],
        "job_list" => &[],
        "job_output" => &["job_id", "tail", "since"],
        "job_kill" => &["job_id"],
        _ => return Err("未知作业工具".into()),
    };
    if args.keys().any(|key| !allowed.contains(&key.as_str())) {
        return Err("作业参数包含未知字段".into());
    }
    let id = || -> Result<String, String> {
        let id = args
            .get("job_id")
            .and_then(Value::as_str)
            .ok_or("作业缺少job_id")?;
        identifier(id)?;
        Ok(id.into())
    };
    Ok(match call.name.as_str() {
        "job_list" => Prepared::List,
        "job_kill" => Prepared::Kill { id: id()? },
        "job_output" => Prepared::Output {
            id: id()?,
            tail: args
                .get("tail")
                .map(|v| {
                    v.as_u64()
                        .filter(|v| (1..=2000).contains(v))
                        .ok_or("作业tail无效")
                })
                .transpose()?
                .unwrap_or(50) as usize,
            since: args
                .get("since")
                .map(|v| v.as_u64().ok_or("作业since无效"))
                .transpose()?,
        },
        _ => {
            let command = args
                .get("command")
                .and_then(Value::as_str)
                .filter(|s| !s.trim().is_empty() && s.len() <= 64 * 1024 && !s.contains('\0'))
                .ok_or("作业命令为空或超过64 KiB")?
                .to_owned();
            let label = args
                .get("label")
                .map(|v| {
                    v.as_str()
                        .filter(|s| s.len() <= 1024 && !s.contains('\0'))
                        .ok_or("作业label无效")
                })
                .transpose()?
                .unwrap_or("")
                .trim()
                .to_owned();
            let observe_paths = match args.get("observe_paths") {
                None => Vec::new(),
                Some(Value::Array(values)) if values.len() <= 32 => values
                    .iter()
                    .map(|value| {
                        let value = value
                            .as_str()
                            .filter(|s| s.len() <= 4096 && !s.contains('\0'))
                            .ok_or("作业观察路径无效")?;
                        let path = std::path::PathBuf::from(value);
                        if !path.is_absolute() {
                            return Err("作业观察路径必须为绝对路径".into());
                        }
                        Ok(path)
                    })
                    .collect::<Result<Vec<_>, String>>()?,
                _ => return Err("作业观察路径超过32项或不是数组".into()),
            };
            let deadline_ms = args
                .get("deadline_ms")
                .map(|v| {
                    v.as_u64()
                        .filter(|v| (1000..=86_400_000).contains(v))
                        .ok_or("作业deadline无效")
                })
                .transpose()?
                .unwrap_or(3_600_000);
            let backend = Backend::parse(
                args.get("shell")
                    .map(|v| v.as_str().ok_or("作业shell无效"))
                    .transpose()?
                    .unwrap_or("auto"),
            )?;
            if call.id.is_empty() || call.id.len() > 1024 {
                return Err("作业创建缺少工具调用身份".into());
            }
            let mut digest = Sha256::new();
            digest.update(call.id.as_bytes());
            digest.update([0]);
            digest.update(call.arguments.as_bytes());
            Prepared::Start(StartSpec {
                command,
                label,
                observe_paths,
                deadline_ms,
                backend,
                prepared_id: format!("{:x}", digest.finalize()),
            })
        }
    })
}
