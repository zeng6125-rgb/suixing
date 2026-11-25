use super::*;
use serde_json::{Value, json};
use std::io::{BufRead, Seek, SeekFrom};

fn directory(files: &Path) -> PathBuf {
    files.join("chats/store")
}

pub(crate) fn log_tail(
    files: &Path,
    id: &str,
    limit: u64,
) -> Result<(PathBuf, u64, Vec<u8>), String> {
    validate_id(id)?;
    let value: Value = required_json(files, &meta(files, id), MAX_SETTINGS_BYTES)?;
    let generation = value["gen"].as_u64().unwrap_or(0);
    let boundary = value["logBytes"].as_u64().unwrap_or(0);
    let path = directory(files).join(format!("{id}.{generation}.jsonl"));
    let mut file = checked_file(files, &path)?;
    if file
        .metadata()
        .map_err(|error| fs_error("读取共用日志长度", &path, error))?
        .len()
        < boundary
    {
        return Err("共用会话日志短于已提交长度".into());
    }
    let from = boundary.saturating_sub(limit);
    file.seek(SeekFrom::Start(from))
        .map_err(|error| fs_error("定位共用日志", &path, error))?;
    let mut bytes = Vec::new();
    file.take(boundary - from)
        .read_to_end(&mut bytes)
        .map_err(|error| fs_error("读取共用日志尾部", &path, error))?;
    Ok((path, from, bytes))
}

pub(super) fn meta(files: &Path, id: &str) -> PathBuf {
    directory(files).join(format!("{id}.meta"))
}

pub(super) fn rename(value: &mut Value, names: &[(&str, &str)]) {
    if let Some(fields) = value.as_object_mut() {
        for &(from, to) in names {
            if let Some(value) = fields.remove(from) {
                fields.insert(to.into(), value);
            }
        }
    }
}

pub(super) fn conversation(mut value: Value) -> Result<Conversation, String> {
    rename(
        &mut value,
        &[("updatedAt", "updated_at"), ("assistantId", "assistant_id")],
    );
    for name in ["assistant_id", "providerId", "model"] {
        if value[name] == "" {
            value[name] = Value::Null;
        }
    }
    let mut chat: Conversation =
        serde_json::from_value(value).map_err(|_| "无法读取共用会话元数据")?;
    for name in [
        "gen",
        "logBytes",
        "messageCount",
        "turnCount",
        "lastMessageId",
        "totals",
        "totalsAt",
        "rustQueuedTurns",
    ] {
        chat.metadata.extra.remove(name);
    }
    Ok(chat)
}

pub(super) fn list(files: &Path) -> Result<Vec<Conversation>, String> {
    let dir = directory(files);
    if !dir.exists() {
        return Ok(Vec::new());
    }
    let mut chats = Vec::new();
    for entry in fs::read_dir(&dir).map_err(|error| fs_error("读取共用会话目录", &dir, error))?
    {
        let path = entry
            .map_err(|error| fs_error("读取共用会话条目", &dir, error))?
            .path();
        if path.extension().is_some_and(|ext| ext == "meta")
            && let Ok(chat) = required_json(files, &path, MAX_SETTINGS_BYTES).and_then(conversation)
            && validate_id(&chat.id).is_ok()
            && path == meta(files, &chat.id)
        {
            chats.push(chat);
        }
    }
    Ok(chats)
}

pub(super) fn committed_log(files: &Path, value: &Value) -> Result<(File, u64), String> {
    let id = value["id"].as_str().ok_or("共用会话缺少 ID")?;
    validate_id(id)?;
    let generation = value["gen"].as_u64().unwrap_or(0);
    let boundary = value["logBytes"].as_u64().unwrap_or(0);
    let path = directory(files).join(format!("{id}.{generation}.jsonl"));
    let file = checked_file(files, &path)?;
    if file
        .metadata()
        .map_err(|error| fs_error("读取共用日志长度", &path, error))?
        .len()
        < boundary
    {
        return Err("共用会话日志短于已提交长度".into());
    }
    Ok((file, boundary))
}

fn read_messages(files: &Path, value: &Value) -> Result<Vec<Message>, String> {
    let (file, boundary) = committed_log(files, value)?;
    let mut raw = Vec::new();
    for line in BufReader::new(file.take(boundary)).split(b'\n') {
        let line = line.map_err(|error| format!("读取共用会话日志失败：{error}"))?;
        if !line.is_empty() {
            raw.push(serde_json::from_slice(&line).map_err(|_| "共用会话消息无法读取")?);
        }
    }
    if raw.len() as u64 != value["messageCount"].as_u64().unwrap_or(0) {
        return Err("共用会话消息数量与提交记录不一致".into());
    }
    crate::legacy_import::normalize_messages(&raw)?
        .into_iter()
        .map(|message| serde_json::from_value(message).map_err(|_| "共用消息字段无法读取".into()))
        .collect()
}

pub(super) fn read(files: &Path, id: &str) -> Result<ChatDocument, String> {
    validate_id(id)?;
    let value: Value = required_json(files, &meta(files, id), MAX_SETTINGS_BYTES)?;
    let messages = read_messages(files, &value)?;
    let queued_turns = value
        .get("rustQueuedTurns")
        .cloned()
        .map(serde_json::from_value)
        .transpose()
        .map_err(|_| "共用会话排队内容无法读取")?
        .unwrap_or_default();
    let mut conversation = conversation(value)?;
    crate::legacy_migration::remap_conversation_references(&mut conversation.metadata, &messages)?;
    Ok(ChatDocument {
        version: VERSION,
        conversation,
        messages,
        queued_turns,
    })
}

pub(super) fn encode_message(message: &Message) -> Result<Value, String> {
    let mut value = serde_json::to_value(message).map_err(|_| "共用消息编码失败")?;
    rename(
        &mut value,
        &[
            ("thinking", "reasoning"),
            ("tool_calls", "toolCalls"),
            ("tool_call_id", "toolCallId"),
            ("run_id", "runId"),
            ("tool_name", "toolName"),
            ("tool_args", "toolArgs"),
            ("tool_output", "toolOutput"),
            ("tool_ok", "toolOk"),
            ("image_base64", "imageBase64"),
            ("images_base64", "imagesBase64"),
            ("prompt_tokens", "promptTokens"),
            ("completion_tokens", "completionTokens"),
            ("reasoning_tokens", "reasoningTokens"),
            ("cached_tokens", "cachedTokens"),
            ("elapsed_ms", "elapsedMs"),
            ("first_token_ms", "firstTokenMs"),
            ("trace_note", "traceNote"),
            ("file_activities", "fileActivities"),
        ],
    );
    value
        .as_object_mut()
        .unwrap()
        .remove(crate::chat::LEGACY_ORIGINAL_ID);
    if value.get("model").is_some_and(Value::is_null) {
        value.as_object_mut().unwrap().remove("model");
    }
    if let Some(Value::Array(activities)) = value.get_mut("fileActivities") {
        for activity in activities {
            rename(
                activity,
                &[
                    ("added_lines", "addedLines"),
                    ("removed_lines", "removedLines"),
                    ("size_bytes", "sizeBytes"),
                    ("previous_path", "previousPath"),
                ],
            );
        }
    }
    Ok(value)
}

fn totals(messages: &[Message]) -> Value {
    let mut result = json!({"turns":0,"steps":0,"promptTokens":0,"completionTokens":0,"cachedTokens":0,
        "elapsedMs":0,"firstTokenMs":0,"contextTokens":0,"longestTurnTokens":0});
    let (mut run, mut prompt, mut completion, mut longest) = ("", 0, 0, 0);
    let (mut wall_run, mut requests, mut wall, mut completed) = ("", 0, 0, None);
    for message in messages {
        let data = &message.metadata;
        let id = data.run_id.as_deref().unwrap_or("");
        if id != wall_run || (message.role == "user" && !data.steer) {
            (wall_run, requests, completed) = (id, 0, None);
        }
        let previous = completed.unwrap_or(requests);
        requests += data.elapsed_ms;
        if let Some(elapsed) = data.extra.get("runElapsedMs").and_then(Value::as_i64) {
            completed = Some(elapsed);
        }
        wall += completed.unwrap_or(requests) - previous;
        for (name, amount) in [
            ("turns", i64::from(message.role == "user" && !data.steer)),
            ("steps", i64::from(message.role == "tool")),
            ("promptTokens", data.prompt_tokens.unwrap_or(0)),
            ("completionTokens", data.completion_tokens.unwrap_or(0)),
            ("cachedTokens", data.cached_tokens.unwrap_or(0)),
            ("elapsedMs", data.elapsed_ms),
            ("firstTokenMs", data.first_token_ms),
        ] {
            result[name] = json!(result[name].as_i64().unwrap() + amount);
        }
        if id != run {
            if !run.is_empty() {
                longest = longest.max(prompt + completion);
            }
            (run, prompt, completion) = (id, 0, 0);
        }
        if data.prompt_tokens.unwrap_or(0) > 0 {
            prompt = data.prompt_tokens.unwrap();
            result["contextTokens"] = json!(prompt);
        }
        completion += data.completion_tokens.unwrap_or(0);
    }
    if !run.is_empty() {
        longest = longest.max(prompt + completion);
    }
    result["longestTurnTokens"] = json!(longest);
    result["trailingRunId"] = json!(run);
    result["trailingRunPromptTokens"] = json!(prompt);
    result["trailingRunCompletionTokens"] = json!(completion);
    result["wallElapsedMs"] = json!(wall);
    result["trailingRunRequestMs"] = json!(requests);
    result["trailingRunWallMs"] = json!(completed);
    result
}

pub(super) fn write(files: &Path, document: &ChatDocument) -> Result<(), CommitFailure> {
    let id = &document.conversation.id;
    let chats = owned_directory(files, "chats")?;
    let dir = owned_directory(&chats, "store")?;
    let previous: Option<Value> = optional_json(files, &meta(files, id), MAX_SETTINGS_BYTES)?;
    let saved = previous
        .as_ref()
        .map(|value| read_messages(files, value))
        .transpose()?
        .unwrap_or_default();
    let prefix =
        document.messages.len() >= saved.len() && document.messages[..saved.len()] == saved;
    let mut generation = previous
        .as_ref()
        .and_then(|value| value["gen"].as_u64())
        .unwrap_or(0);
    let mut boundary = previous
        .as_ref()
        .and_then(|value| value["logBytes"].as_u64())
        .unwrap_or(0);
    if previous.is_some() && !prefix {
        generation += 1;
    }
    let log = dir.join(format!("{id}.{generation}.jsonl"));
    let mut encoded = Vec::new();
    for message in &document.messages[if prefix { saved.len() } else { 0 }..] {
        serde_json::to_writer(&mut encoded, &encode_message(message)?)
            .map_err(|_| "共用消息编码失败".to_owned())?;
        encoded.push(b'\n');
    }
    if previous.is_none() || !prefix {
        atomic_bytes(files, &log, &encoded)?;
        boundary = encoded.len() as u64;
    } else if !encoded.is_empty() {
        let mut file = OpenOptions::new()
            .write(true)
            .open(&log)
            .map_err(|error| fs_error("打开共用日志", &log, error))?;
        file.set_len(boundary)
            .map_err(|error| fs_error("截取未提交日志", &log, error))?;
        file.seek(SeekFrom::Start(boundary))
            .map_err(|error| fs_error("定位共用日志", &log, error))?;
        file.write_all(&encoded)
            .and_then(|()| file.sync_all())
            .map_err(|error| fs_error("追加共用日志", &log, error))?;
        boundary += encoded.len() as u64;
    }
    let mut value =
        serde_json::to_value(&document.conversation).map_err(|_| "共用会话编码失败".to_owned())?;
    rename(
        &mut value,
        &[
            ("updated_at", "updatedAt"),
            ("assistant_id", "assistantId"),
            ("provider_id", "providerId"),
            ("created_at", "createdAt"),
            ("active_runs", "activeRuns"),
            ("reasoning_effort", "reasoningEffort"),
            ("approval_mode", "approvalMode"),
        ],
    );
    if value["assistantId"].is_null() {
        value["assistantId"] = json!("");
    }
    if let Some(mode) = value["approvalMode"].as_str() {
        value["approvalMode"] = json!(mode.to_uppercase());
    }
    for checkpoint in value["checkpoints"].as_array_mut().into_iter().flatten() {
        rename(
            checkpoint,
            &[
                ("from_message_id", "fromMessageId"),
                ("to_message_id", "toMessageId"),
                ("prompt_tokens", "promptTokens"),
                ("anchor_id", "anchorId"),
            ],
        );
    }
    let fields = value.as_object_mut().unwrap();
    fields.insert("gen".into(), json!(generation));
    fields.insert("logBytes".into(), json!(boundary));
    fields.insert("messageCount".into(), json!(document.messages.len()));
    fields.insert(
        "turnCount".into(),
        json!(
            document
                .messages
                .iter()
                .filter(|message| message.role == "user" && !message.metadata.steer)
                .count()
        ),
    );
    fields.insert(
        "lastMessageId".into(),
        json!(
            document
                .messages
                .last()
                .map_or("", |message| message.id.as_str())
        ),
    );
    fields.insert("totals".into(), totals(&document.messages));
    fields.insert("totalsAt".into(), json!(document.messages.len()));
    fields.insert(
        "rustQueuedTurns".into(),
        serde_json::to_value(&document.queued_turns).map_err(|_| "排队消息编码失败".to_owned())?,
    );
    atomic_json(files, &meta(files, id), &value, MAX_SETTINGS_BYTES)
}

pub(super) fn delete(files: &Path, id: &str) -> Result<(), String> {
    super::shared_usage::archive_conversation(files, id)?;
    fs::remove_file(meta(files, id))
        .map_err(|error| fs_error("删除共用会话", &meta(files, id), error))?;
    for entry in fs::read_dir(directory(files))
        .map_err(|error| fs_error("读取共用会话目录", &directory(files), error))?
    {
        let path = entry
            .map_err(|error| fs_error("读取共用日志条目", &directory(files), error))?
            .path();
        if path
            .file_name()
            .and_then(|name| name.to_str())
            .is_some_and(|name| name.starts_with(&format!("{id}.")) && name.ends_with(".jsonl"))
        {
            fs::remove_file(&path).map_err(|error| fs_error("删除共用日志", &path, error))?;
        }
    }
    let chats = files.join("chats");
    for entry in
        fs::read_dir(&chats).map_err(|error| fs_error("读取会话草稿目录", &chats, error))?
    {
        let path = entry
            .map_err(|error| fs_error("读取会话草稿条目", &chats, error))?
            .path();
        let name = path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("");
        if name == format!("{id}.json")
            || name == format!("{id}.live.json")
            || (name.starts_with(&format!("{id}.live-")) && name.ends_with(".messages"))
            || (name.starts_with(&format!("{id}.live.json.")) && name.ends_with(".corrupt"))
        {
            fs::remove_file(&path).map_err(|error| fs_error("删除会话恢复草稿", &path, error))?;
        }
    }
    let marker = chats.join("last-opened");
    if marker.is_file()
        && fs::read_to_string(&marker)
            .map_err(|error| fs_error("读取最近会话", &marker, error))?
            .trim()
            == id
    {
        fs::remove_file(&marker).map_err(|error| fs_error("删除最近会话标记", &marker, error))?;
    }
    sync_directory(&directory(files))
        .map_err(|error| fs_error("同步共用会话删除", &directory(files), error))
}
