use super::*;
use crate::subagents::{Record, Status};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

pub(super) fn directory(files: &Path, parent: &str) -> PathBuf {
    files.join("subagent_records").join(parent_key(parent))
}

fn parent_key(parent: &str) -> String {
    format!("{:x}", Sha256::digest(parent.as_bytes()))
}

pub(super) fn delete(files: &Path, parent: &str) -> Result<(), String> {
    let path = directory(files, parent);
    if path.exists() {
        if contained_path(files, &path)? != path {
            return Err("共用子任务目录发生变化".into());
        }
        fs::remove_dir_all(&path).map_err(|error| fs_error("删除共用子任务目录", &path, error))?;
    }
    Ok(())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ComposeChild {
    id: String,
    name: String,
    parent_chat_id: String,
    parent_run_id: String,
    status: String,
    config: crate::subagent_configuration::ChildRunConfig,
    #[serde(default)]
    messages: Vec<Value>,
    #[serde(default)]
    result: String,
    #[serde(default)]
    status_line: String,
    rust_usage: Option<Vec<crate::state::UsageRecord>>,
    #[serde(default)]
    rust_timed_out: bool,
    #[serde(default)]
    rust_missing_config: bool,
}

pub(super) fn read(files: &Path, path: &Path) -> Result<Option<(ChildDocument, bool)>, String> {
    optional_json::<ComposeChild>(files, path, MAX_CHILD_BYTES)?
        .map(decode_row)
        .transpose()
}

pub(super) fn decode(bytes: &[u8]) -> Result<ChildDocument, String> {
    let row = serde_json::from_slice(bytes).map_err(|_| "共用子任务记录无法读取")?;
    decode_row(row).map(|(document, _)| document)
}

fn decode_row(row: ComposeChild) -> Result<(ChildDocument, bool), String> {
    let status = match row.status.as_str() {
        "QUEUED" => Status::Queued,
        "RUNNING" => Status::Running,
        "COMPLETED" => Status::Completed,
        "FAILED" if row.rust_timed_out => Status::TimedOut,
        "FAILED" => Status::Failed,
        "CANCELLED" => Status::Cancelled,
        _ => return Err("共用子任务状态无法读取".into()),
    };
    let messages = crate::legacy_import::normalize_messages(&row.messages)?
        .into_iter()
        .map(|message| serde_json::from_value(message).map_err(|_| "共用子任务消息无法读取"))
        .collect::<Result<_, _>>()?;
    let owned = row.rust_usage.is_some();
    Ok((
        ChildDocument {
            version: VERSION,
            parent_chat: row.parent_chat_id,
            parent_run: row.parent_run_id,
            record: Record {
                id: row.id,
                name: row.name,
                status,
                messages,
                detail: if owned || row.result.is_empty() {
                    row.status_line
                } else {
                    row.result
                },
                usage: row.rust_usage.unwrap_or_default(),
                config: (!row.rust_missing_config).then_some(row.config),
            },
        },
        owned,
    ))
}

pub(super) fn write(files: &Path, document: &ChildDocument) -> Result<(), CommitFailure> {
    let root = owned_directory(files, "subagent_records")?;
    let directory = owned_directory(&root, &parent_key(&document.parent_chat))?;
    let record = &document.record;
    let path = directory.join(format!("{}.json", record.id));
    let updated = now()?;
    let created = record
        .messages
        .first()
        .map(|message| message.metadata.timestamp)
        .filter(|at| *at > 0)
        .unwrap_or(updated);
    let finished = record.status.terminal().then_some(updated);
    let status = match record.status {
        Status::Queued => "QUEUED",
        Status::Running => "RUNNING",
        Status::Completed => "COMPLETED",
        Status::Failed | Status::TimedOut => "FAILED",
        Status::Cancelled => "CANCELLED",
    };
    let mut config = serde_json::to_value(record.config.clone().unwrap_or_default())
        .map_err(|_| "共用子任务配置编码失败".to_owned())?;
    shared_chat::rename(
        &mut config,
        &[
            ("profile_id", "profileId"),
            ("profile_name", "profileName"),
            ("assistant_id", "assistantId"),
            ("assistant_name", "assistantName"),
            ("assistant_avatar", "assistantAvatar"),
            ("assistant_avatar_path", "assistantAvatarPath"),
            ("provider_id", "providerId"),
            ("approval_mode", "approvalMode"),
            ("allowed_tools", "allowedTools"),
            ("allowed_actions", "allowedActions"),
            ("max_tool_rounds", "maxToolRounds"),
            ("timeout_seconds", "timeoutSeconds"),
            ("extra_instructions", "extraInstructions"),
        ],
    );
    config["approvalMode"] = config["approvalMode"]
        .as_str()
        .unwrap_or("ask")
        .to_ascii_uppercase()
        .into();
    let messages = record
        .messages
        .iter()
        .map(shared_chat::encode_message)
        .collect::<Result<Vec<_>, _>>()?;
    let input = record
        .messages
        .first()
        .and_then(|message| message.text.strip_prefix("Task:\n"))
        .unwrap_or_default();
    let (task, context) = input
        .split_once("\n\nRelevant context:\n")
        .unwrap_or((input, ""));
    let task = task.split("\n\nParent objective:\n").next().unwrap_or(task);
    let context = context
        .split("\n\nParent objective:\n")
        .next()
        .unwrap_or(context);
    let result = record
        .messages
        .last()
        .filter(|message| message.role == "assistant" && record.status == Status::Completed)
        .map_or(record.detail.as_str(), |message| message.text.as_str());
    let wire = json!({
        "id":record.id, "parentChatId":document.parent_chat, "parentRunId":document.parent_run,
        "name":record.name, "task":task, "context":context, "config":config, "status":status,
        "messages":messages, "statusLine":record.detail, "result":result,
        "createdAt":created, "updatedAt":updated, "finishedAt":finished,
        "rustUsage":record.usage, "rustTimedOut":record.status == Status::TimedOut,
        "rustMissingConfig":record.config.is_none(),
    });
    atomic_json(files, &path, &wire, MAX_CHILD_BYTES)
}
