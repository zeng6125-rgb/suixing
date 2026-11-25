use super::*;
use crate::legacy_import::{LegacyBlob, LegacyDocumentKind as Kind, normalize_messages};
use crate::platform::secrets::StoredSecret;
use serde_json::{Map, Value};
use std::{collections::BTreeSet, path::Path};

const RAW_CHUNK_BYTES: usize = 32 * 1024;
const MAX_RAW_BYTES: u64 = 256 * 1024 * 1024;

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct SealedBlob {
    version: u32,
    encoding: String,
    bytes: u64,
    sha256: String,
    chunks: Vec<StoredSecret>,
}

pub fn prepare(
    snapshot: &LegacySnapshot,
    cipher: &dyn SecretCipher,
) -> Result<MigrationPlan, String> {
    let fingerprint = super::transaction::snapshot_fingerprint(snapshot)?;
    let mut plan = MigrationPlan {
        source_fingerprint: fingerprint,
        conversations: Vec::new(),
        settings: None,
        mcp: Vec::new(),
        memories: Vec::new(),
        goal: None,
        schedules: Vec::new(),
        skills: Vec::new(),
        children: Vec::new(),
        usage: ImportedUsage::default(),
        performance: None,
        conversation_preferences: None,
        reading_positions: crate::legacy_reading::from_legacy(snapshot)?,
        todos: Vec::new(),
        assets: Vec::new(),
        workspace_directories: Vec::new(),
        plugin_layout: Vec::new(),
        plugin_files: Vec::new(),
        warnings: snapshot
            .warnings
            .iter()
            .filter(|warning| {
                warning.as_str()
                    != "候选只读且未经迁移提交；enc1 密文保持原样，Keystore 凭据尚未接续"
            })
            .cloned()
            .collect(),
        retained_features: Vec::new(),
        archives: Vec::new(),
    };
    let mut roots = BTreeSet::new();
    let mut total = 0u64;
    for blob in super::source_blobs(snapshot) {
        if !roots.insert((blob.owning_root.clone(), blob.relative_path.clone())) {
            continue;
        }
        total = total
            .checked_add(blob.original_bytes.len() as u64)
            .ok_or("旧原文长度溢出")?;
        if total > MAX_RAW_BYTES {
            return Err("旧原文超过256 MiB密封归档预算，拒绝部分迁移".into());
        }
        let key = serde_json::to_vec(&(&blob.owning_root, &blob.relative_path))
            .map_err(|_| "旧原文身份编码失败")?;
        let archive_path = PathBuf::from(format!("archive/{}.sealed.json", digest(&key)));
        let bytes = seal(&blob.original_bytes, cipher)?;
        plan.assets.push(AssetMapping {
            owning_root: blob.owning_root.clone(),
            relative_path: blob.relative_path.clone(),
            sha256: blob.sha256.clone(),
            bytes: blob.original_bytes.len() as u64,
            archive_path: archive_path.clone(),
            workspace_path: None,
            workspace_mode: None,
        });
        plan.archives.push(GenerationFile {
            path: archive_path,
            bytes,
        });
    }
    let plugins = crate::plugin_assets::from_legacy(snapshot, cipher)?;
    plan.plugin_layout = plugins.layout;
    plan.plugin_files = plugins
        .files
        .into_iter()
        .map(|(path, bytes)| GenerationFile { path, bytes })
        .collect();
    plan.warnings.extend(plugins.warnings);
    super::workspace_assets::assign_workspace(snapshot, &mut plan)?;
    let mut unresolved_message_refs = 0usize;
    for source in &snapshot.conversations {
        let (conversation, unresolved) = conversation(source)?;
        unresolved_message_refs += unresolved;
        plan.conversations.push(conversation);
    }
    if unresolved_message_refs != 0 {
        plan.retained_features.push(format!(
            "旧历史中有{unresolved_message_refs}条候选选择或检查点无法确定重复消息的原位置；原引用已封存并随会话保留，未用于候选选择或模型上下文"
        ));
    }
    for blob in snapshot
        .assets
        .iter()
        .filter(|blob| blob.relative_path == "perf.json")
    {
        if plan.performance.is_some() {
            return Err("旧性能记录来源重复，未选择覆盖".into());
        }
        let value: Value = serde_json::from_slice(&blob.original_bytes)
            .map_err(|_| "旧性能记录无法解码，原文件未迁移为空账本")?;
        plan.performance = Some(crate::performance::Document::from_legacy(
            value,
            &blob.sha256,
        )?);
    }
    plan.conversation_preferences = crate::conversation_preferences::from_legacy(snapshot)?;
    for document in &snapshot.documents {
        match document.kind {
            Kind::Settings => {
                if plan.settings.is_some() {
                    return Err("发现多个权威旧设置，拒绝选择性覆盖".into());
                }
                plan.settings = Some(settings(&document.value, cipher, &mut plan.warnings)?);
            }
            Kind::Memory => {
                let entries = object(&document.value, "旧记忆")?;
                for (key, value) in entries {
                    let (text, at) = match value {
                        Value::String(value) => (value.clone(), 0),
                        Value::Object(entry) => (
                            string_field(entry, "value", "")?,
                            integer_field(entry, "updatedAt", 0)?,
                        ),
                        _ => return Err("旧记忆条目既不是字符串也不是对象".into()),
                    };
                    plan.memories.push(Memory {
                        id: format!("legacy-memory-{}", &digest(key.as_bytes())[..32]),
                        text: format!("{key}: {text}"),
                        updated_at: at,
                    });
                }
            }
            Kind::Goal => {
                let imported = goal(&document.value, &mut plan)?;
                plan.goal = imported;
            }
            Kind::Schedules => {
                for value in array(&document.value, "旧提醒")? {
                    let schedule: crate::schedules::Schedule =
                        serde_json::from_value(value.clone()).map_err(|_| "旧提醒字段无效")?;
                    schedule.validate()?;
                    plan.schedules.push(schedule);
                }
                retained(
                    &mut plan,
                    "旧提醒PendingIntent身份需在成功提交后由平台重设并清理，归档不代表系统闹钟已恢复",
                );
            }
            Kind::Mcp => {
                for (index, value) in array(&document.value, "旧MCP")?.iter().enumerate() {
                    let fields = object(value, "旧MCP服务器")?;
                    let mut config: crate::mcp::ServerConfig =
                        serde_json::from_value(value.clone()).map_err(|_| "旧MCP配置字段无效")?;
                    if config.id.is_empty() {
                        config.id = format!(
                            "legacy-mcp-{}",
                            &digest(format!("{}#{index}", config.name).as_bytes())[..32]
                        );
                    }
                    let env = secret(
                        &string_field(fields, "env", "")?,
                        cipher,
                        &mut plan.warnings,
                        "MCP环境变量",
                    )?;
                    let headers = secret(
                        &string_field(fields, "headers", "")?,
                        cipher,
                        &mut plan.warnings,
                        "MCP请求头",
                    )?;
                    // Metadata remains unchanged; Store blocks effective connects
                    // until any retained ciphertext can be decrypted.
                    crate::mcp::validate_config(&config)?;
                    plan.mcp.push(ImportedMcp {
                        config,
                        secrets: StoredMcpSecrets { env, headers },
                    });
                }
            }
            Kind::Todos => {
                for value in array(&document.value, "旧任务板")? {
                    let fields = object(value, "旧任务条目")?;
                    let id = string_field(fields, "id", "")?;
                    super::chat_archive::identity(&id)?;
                    let text = string_field(fields, "subject", "")?;
                    let status = string_field(fields, "status", "pending")?;
                    if text.trim().is_empty()
                        || !["pending", "in_progress", "completed"].contains(&status.as_str())
                    {
                        return Err("旧任务条目内容或状态无效".into());
                    }
                    let extra = fields
                        .iter()
                        .filter(|(k, _)| !["id", "subject", "status"].contains(&k.as_str()))
                        .map(|(k, v)| (k.clone(), v.clone()))
                        .collect();
                    plan.todos.push(ImportedTodo {
                        id,
                        text,
                        status,
                        extra,
                    });
                }
            }
            Kind::DeletedUsage => {
                let fields = object(&document.value, "已删除会话用量")?;
                let id = string_field(fields, "id", "")?;
                if id.is_empty() {
                    return Err("已删除用量缺少来源ID".into());
                }
                let usage: LegacyChatUsage = serde_json::from_value(
                    fields.get("usage").cloned().ok_or("已删除用量缺少usage")?,
                )
                .map_err(|_| "已删除用量Long字段无效")?;
                validate_usage(&usage)?;
                if usage.deleted_at <= 0 || plan.usage.deleted.insert(id, usage).is_some() {
                    return Err("已删除用量重复或缺少删除时间".into());
                }
            }
            Kind::UsageCache => {
                let fields = object(&document.value, "旧用量缓存")?;
                plan.usage.schema_version = integer_field(fields, "schemaVersion", 0)?;
                plan.usage.zone_id = string_field(fields, "zoneId", "")?;
                if let Some(chats) = fields.get("chats") {
                    for (id, value) in object(chats, "旧用量缓存chats")? {
                        let usage: LegacyChatUsage = serde_json::from_value(value.clone())
                            .map_err(|_| "旧用量缓存Long字段无效")?;
                        validate_usage(&usage)?;
                        plan.usage.cache.insert(id.clone(), usage);
                    }
                }
            }
            Kind::ChildRecord => {
                plan.children.push(child(&document.value)?);
            }
            Kind::ConversationMetadata | Kind::ConversationJson | Kind::LiveMessages => {}
            Kind::LiveDraft => {
                retained(
                    &mut plan,
                    "没有权威会话的独立恢复草稿已密封保留，需显式关联会话",
                );
            }
            Kind::PluginManifest | Kind::PluginState | Kind::PluginEnvironment => {
                retained(
                    &mut plan,
                    "旧插件完整清单、状态、环境、脚本和目录权限已恢复到活动代插件目录；原文另密封保留，缺失或坏清单/密文会明确阻止使用",
                );
            }
            Kind::ProfileState => {
                retained(
                    &mut plan,
                    "旧画像原文、备份与记账已密封保留；运行画像文档保留来源证明，供编辑、注入与整理使用",
                );
            }
            Kind::DrawerOrder => {}
            Kind::Viewport => {}
            Kind::TerminalSessions => {
                retained(
                    &mut plan,
                    "旧终端会话及日志已保留，旧PID和PTY只能显示为中断历史",
                );
            }
            Kind::SkillState | Kind::SkillOrigin => {}
        }
    }
    // Cache entries are historical evidence. Deleted summaries are authoritative
    // for deleted parents; never count a duplicate cache or fabricate requests.
    for id in plan.usage.deleted.keys() {
        plan.usage.cache.remove(id);
    }
    if !plan.usage.cache.is_empty() || !plan.usage.deleted.is_empty() {
        retained(
            &mut plan,
            "旧用量汇总已保留并接入用量统计；历史汇总与逐请求记录分别展示，不补造缺失的请求明细",
        );
    }
    skills(snapshot, &mut plan)?;
    if !snapshot.preference_stores.is_empty() {
        retained(
            &mut plan,
            "旧DataStore所有未知键与原始protobuf已密封保留；未映射的UI偏好尚待适配",
        );
    }
    crate::schedules::validate_board(&plan.schedules)?;
    crate::skills::validate_all(&plan.skills)?;
    let mut child_ids = BTreeSet::new();
    for child in &plan.children {
        if !child_ids.insert((&child.parent_chat, &child.parent_run, &child.record.id)) {
            return Err("旧子任务重复身份".into());
        }
    }
    let assigned_occurrences = plan
        .conversations
        .iter()
        .flat_map(|chat| {
            chat.messages
                .iter()
                .chain(chat.recovery.iter().flat_map(|r| &r.messages))
        })
        .chain(
            plan.children
                .iter()
                .flat_map(|child| &child.record.messages),
        )
        .filter(|message| message.metadata.legacy_original_id().is_some())
        .count();
    if assigned_occurrences != 0 {
        plan.warnings.push(format!(
            "为{assigned_occurrences}条旧同ID消息分配了各自的身份；消息顺序、原ID和原始文件已保留"
        ));
    }
    Ok(plan)
}

fn conversation(
    source: &crate::legacy_import::LegacyConversation,
) -> Result<(ImportedConversation, usize), String> {
    let mut value = source.metadata.value.clone();
    let fields = value.as_object_mut().ok_or("旧会话metadata不是对象")?;
    rename(fields, "updatedAt", "updated_at")?;
    rename(fields, "assistantId", "assistant_id")?;
    for key in ["assistant_id", "providerId", "provider_id", "model"] {
        if fields.get(key).and_then(Value::as_str) == Some("") {
            fields.insert(key.into(), Value::Null);
        }
    }
    fields.entry("updated_at").or_insert(Value::from(0));
    fields.remove("messages");
    let mut conversation: Conversation =
        serde_json::from_value(value).map_err(|_| "旧会话metadata无法转换，原文保留")?;
    if conversation.id != source.id
        || conversation.title.trim().is_empty()
        || conversation.title.len() > 1024
        || conversation.updated_at < 0
    {
        return Err("旧会话身份、标题或时间无效".into());
    }
    super::chat_archive::identity(&conversation.id)?;
    let messages = decode_messages(&source.normalized_messages)?;
    crate::chat::validate_conversation(&conversation.metadata, &messages)?;
    let unresolved = remap_conversation_references(&mut conversation.metadata, &messages)?;
    let recovery = source
        .live_draft
        .as_ref()
        .map(|live| {
            let fields = object(&live.manifest.value, "旧恢复草稿")?;
            let run_id = string_field(fields, "runId", "")?;
            super::chat_archive::identity(&run_id)?;
            Ok::<_, String>(ImportedRecovery {
                run_id,
                messages: decode_messages(&normalize_messages(&live.messages)?)?,
                live_text: string_field(fields, "liveText", "")?,
                live_reasoning: string_field(fields, "liveReasoning", "")?,
                interrupted: true,
            })
        })
        .transpose()?;
    Ok((
        ImportedConversation {
            conversation,
            messages,
            queued_turns: Vec::new(),
            recovery,
        },
        unresolved,
    ))
}

/// Candidate selections identify a question and a run. A repeated question ID
/// is safe to move only when one physical turn owns that selection. Checkpoint
/// endpoints carry no occurrence, so an ambiguous range cannot be replayed.
pub(crate) fn remap_conversation_references(
    metadata: &mut crate::chat::ConversationMetadata,
    messages: &[Message],
) -> Result<usize, String> {
    let (assigned, repeated): (BTreeSet<_>, BTreeSet<_>) = messages
        .iter()
        .filter_map(|message| {
            message
                .metadata
                .legacy_original_id()
                .map(|original| (message.id.as_str(), original))
        })
        .unzip();
    if repeated.is_empty() {
        return Ok(0);
    }

    let mut turns = BTreeMap::<&str, Vec<(&str, &[Message])>>::new();
    let mut starts = messages
        .iter()
        .enumerate()
        .filter(|(_, message)| message.role == "user" && !message.metadata.steer)
        .peekable();
    while let Some((start, question)) = starts.next() {
        let end = starts.peek().map_or(messages.len(), |(index, _)| *index);
        let original = question
            .metadata
            .legacy_original_id()
            .unwrap_or(&question.id);
        if repeated.contains(original) {
            turns
                .entry(original)
                .or_default()
                .push((&question.id, &messages[start + 1..end]));
        }
    }

    let mut unresolved_choices = BTreeMap::new();
    for (id, run) in std::mem::take(&mut metadata.active_runs) {
        if assigned.contains(id.as_str()) {
            // A stale old choice must not come alive by coinciding with a newly
            // allocated message ID.
            unresolved_choices.insert(id, run);
            continue;
        }
        if !repeated.contains(id.as_str()) {
            metadata.active_runs.insert(id, run);
            continue;
        }
        let candidates = turns.get(id.as_str()).map_or(&[][..], Vec::as_slice);
        let target = if let [(only, _)] = candidates {
            Some(*only)
        } else {
            let mut matching = candidates.iter().filter(|(_, body)| {
                body.iter().any(|message| {
                    message.role != "user"
                        && message.metadata.run_id.as_deref().unwrap_or_default() == run.as_str()
                })
            });
            matching
                .next()
                .map(|(id, _)| *id)
                .filter(|_| matching.next().is_none())
        };
        if let Some(target) = target {
            if metadata
                .active_runs
                .insert(target.to_owned(), run)
                .is_some()
            {
                return Err("旧候选选择映射冲突，原会话未覆盖".into());
            }
        } else {
            unresolved_choices.insert(id, run);
        }
    }

    let (unresolved_checkpoints, resolved): (Vec<_>, Vec<_>) =
        std::mem::take(&mut metadata.checkpoints)
            .into_iter()
            .partition(|checkpoint| {
                [
                    &checkpoint.from_message_id,
                    &checkpoint.to_message_id,
                    &checkpoint.anchor_id,
                ]
                .into_iter()
                .any(|id| repeated.contains(id.as_str()) || assigned.contains(id.as_str()))
            });
    metadata.checkpoints = resolved;
    let unresolved = unresolved_choices.len() + unresolved_checkpoints.len();
    if unresolved != 0 {
        let entry = metadata
            .extra
            .entry("rustLegacyAmbiguousMessageReferences".into());
        let std::collections::btree_map::Entry::Vacant(entry) = entry else {
            return Err("旧会话使用了迁移引用保留字段，原会话未覆盖".into());
        };
        entry.insert(serde_json::json!({
            "activeRuns": unresolved_choices,
            "checkpoints": unresolved_checkpoints,
        }));
    }
    Ok(unresolved)
}

fn decode_messages(values: &[Value]) -> Result<Vec<Message>, String> {
    let mut messages = Vec::new();
    let mut ids = BTreeSet::new();
    let mut calls = BTreeSet::new();
    for value in values {
        let message: Message =
            serde_json::from_value(value.clone()).map_err(|_| "旧消息或未知元数据类型无法转换")?;
        super::chat_archive::identity(&message.id)?;
        crate::chat::validate_message(&message.metadata)?;
        if !ids.insert(message.id.clone()) {
            return Err("旧消息ID重复".into());
        }
        if message.role.is_empty()
            || message.role.len() > 32
            || !message
                .role
                .bytes()
                .all(|b| b.is_ascii_alphabetic() || matches!(b, b'_' | b'-'))
        {
            return Err("旧消息角色无效".into());
        }
        if message.role != "tool" {
            calls.clear();
        }
        if message.tool_calls.len() > 16
            || (!message.tool_calls.is_empty() && message.role != "assistant")
            || (message.tool_call_id.is_some() && message.role != "tool")
        {
            return Err("旧工具消息结构无法安全恢复".into());
        }
        for call in &message.tool_calls {
            if call.id.is_empty()
                || call.id.len() > 512
                || call.name.is_empty()
                || call.name.len() > 128
                || call.arguments.len() > 1024 * 1024
                || !calls.insert(call.id.clone())
            {
                return Err("旧工具调用字段或配对身份无效".into());
            }
        }
        if message.role == "tool" {
            let call = message
                .tool_call_id
                .as_ref()
                .ok_or("旧工具结果缺少调用ID；拒绝转为可重放调用")?;
            if !calls.remove(call) {
                return Err("旧工具结果没有对应调用，未虚构工具执行".into());
            }
        }
        messages.push(message);
    }
    Ok(messages)
}
pub(crate) fn settings(
    value: &Value,
    cipher: &dyn SecretCipher,
    warnings: &mut Vec<String>,
) -> Result<ImportedSettings, String> {
    let original = object(value, "旧设置")?;
    let mut normalized = original.clone();
    for (old, new) in [
        ("asrBaseUrl", "asr_endpoint"),
        ("asrModel", "asr_model"),
        ("injectMemory", "memory_context"),
    ] {
        rename(&mut normalized, old, new)?;
    }
    normalized.insert(
        "steer_immediately".into(),
        Value::Bool(integer_field(original, "steerMode", 0)? == 1),
    );
    if !boolean_field(original, "sendTemperature", false)? {
        normalized.insert("temperature".into(), Value::Null);
    }
    if !boolean_field(original, "sendTopP", false)? {
        normalized.insert("topP".into(), Value::Null);
    }
    let mut settings: Settings = serde_json::from_value(Value::Object(normalized))
        .map_err(|_| "旧设置、供应商或子任务模板字段无法转换")?;
    crate::settings_policy::apply_legacy_companions(value, &mut settings)?;
    for provider in &settings.providers {
        if let Some(limit) = provider.max_tokens.filter(|value| *value > 0) {
            for model in provider
                .enabled_models()
                .iter()
                .filter(|model| !model.trim().is_empty())
            {
                settings
                    .model_max_tokens
                    .entry(model.clone())
                    .or_insert(limit);
            }
        }
    }
    let mut stored = StoredSettingsSecrets {
        api_key: secret(
            &string_field(original, "apiKey", "")?,
            cipher,
            warnings,
            "默认API凭据",
        )?,
        asr_api_key: secret(
            &string_field(original, "asrApiKey", "")?,
            cipher,
            warnings,
            "语音API凭据",
        )?,
        search_api_key: secret(
            &string_field(original, "searchApiKey", "")?,
            cipher,
            warnings,
            "搜索API凭据",
        )?,
        ..Default::default()
    };
    if let Some(providers) = original.get("providers") {
        for (fields, provider) in array(providers, "旧供应商")?
            .iter()
            .zip(&mut settings.providers)
        {
            let fields = object(fields, "旧供应商")?;
            if provider.name.trim().is_empty() {
                provider.name = if provider.endpoint.is_empty() {
                    "未命名供应商".into()
                } else {
                    provider.endpoint.clone()
                };
            }
            if provider.selected_model.is_empty()
                && provider.enabled_models().contains(&settings.model)
            {
                provider.selected_model = settings.model.clone();
            }
            stored.provider_keys.insert(
                provider.id.clone(),
                secret(
                    &string_field(fields, "apiKey", "")?,
                    cipher,
                    warnings,
                    "供应商凭据",
                )?,
            );
        }
    }
    if let Some(headers) = original.get("requestHeaders") {
        for (name, value) in object(headers, "旧请求头")? {
            let text = value.as_str().ok_or("旧请求头值不是字符串")?;
            stored
                .request_headers
                .insert(name.clone(), secret(text, cipher, warnings, "请求头凭据")?);
        }
    }
    settings.api_key.clear();
    settings.asr_key.clear();
    settings.request_headers.clear();
    for provider in &mut settings.providers {
        provider.api_key.clear();
    }
    crate::configuration::validate(&settings)?;
    crate::subagent_configuration::validate_profiles(&settings)?;
    Ok(ImportedSettings {
        settings,
        secrets: stored,
    })
}
fn secret(
    value: &str,
    cipher: &dyn SecretCipher,
    warnings: &mut Vec<String>,
    label: &str,
) -> Result<StoredSecret, String> {
    if value.starts_with("enc1:") {
        let stored =
            StoredSecret::preserve_ciphertext(value.to_owned()).map_err(|e| e.to_string())?;
        if cipher.decrypt(&stored).is_err() {
            warnings.push(format!(
                "{label}旧密文无法在当前Keystore命名空间解密，原密文保留，需要重新填写"
            ));
        }
        Ok(stored)
    } else {
        cipher
            .encrypt(value)
            .map_err(|e| format!("{label}迁移加密失败：{e}；未写入新状态"))
    }
}
fn goal(value: &Value, plan: &mut MigrationPlan) -> Result<Option<crate::goal::Goal>, String> {
    let original = object(value, "旧目标")?;
    let mut fields = original.clone();
    for (old, new) in [
        ("updatedAt", "updated_at"),
        ("chatId", "chat_id"),
        ("wakeAt", "wake_at"),
    ] {
        rename(&mut fields, old, new)?;
    }
    if let Some(Value::String(status)) = fields.get_mut("status") {
        *status = status.to_ascii_lowercase();
    }
    let mut goal: crate::goal::Goal =
        serde_json::from_value(Value::Object(fields)).map_err(|_| "旧目标字段无法转换")?;
    if !goal.text.is_empty() && (goal.id.is_empty() || goal.chat_id.is_empty()) {
        retained(
            plan,
            "早期无执行身份的全局目标已密封保留，需用户关联会话后继续",
        );
        return Ok(None);
    }
    if goal.status == crate::goal::Status::Active {
        goal.status = crate::goal::Status::Paused;
        goal.detail = if goal.detail.is_empty() {
            "迁移前执行已中断，请明确继续".into()
        } else {
            format!("{}\n迁移前执行已中断，请明确继续", goal.detail)
        };
    }
    goal.wake_at = 0;
    goal.validate()?;
    Ok(Some(goal))
}
fn child(value: &Value) -> Result<ImportedChild, String> {
    let fields = object(value, "旧子任务")?;
    let parent_chat = string_field(fields, "parentChatId", "")?;
    let parent_run = string_field(fields, "parentRunId", "")?;
    let id = string_field(fields, "id", "")?;
    for id in [&parent_chat, &parent_run, &id] {
        super::chat_archive::identity(id)?;
    }
    let status = string_field(fields, "status", "QUEUED")?.to_ascii_lowercase();
    let (status, interrupted) = match status.as_str() {
        "queued" | "running" => (crate::subagents::Status::Cancelled, true),
        "completed" => (crate::subagents::Status::Completed, false),
        "failed" => (crate::subagents::Status::Failed, false),
        "cancelled" | "canceled" => (crate::subagents::Status::Cancelled, false),
        "timed_out" | "timeout" => (crate::subagents::Status::TimedOut, false),
        _ => return Err("旧子任务状态不受支持，原文保留".into()),
    };
    let source_messages = fields
        .get("messages")
        .map(|v| array(v, "旧子任务消息"))
        .transpose()?
        .cloned()
        .unwrap_or_default();
    let messages = decode_messages(&normalize_messages(&source_messages)?)?;
    let config = fields
        .get("config")
        .map(|v| serde_json::from_value(v.clone()).map_err(|_| "旧子任务冻结配置无法转换"))
        .transpose()?;
    let detail = if interrupted {
        "迁移前子任务已中断；原始任务、上下文、结果和流式草稿均已密封保留".into()
    } else {
        string_field(fields, "result", "")?
    };
    Ok(ImportedChild {
        parent_chat,
        parent_run,
        record: crate::subagents::Record {
            id,
            name: string_field(fields, "name", "子任务")?,
            status,
            messages,
            detail,
            usage: Vec::new(),
            config,
        },
    })
}
fn skills(snapshot: &LegacySnapshot, plan: &mut MigrationPlan) -> Result<(), String> {
    let mut disabled = BTreeSet::new();
    let mut origins = Map::new();
    for document in &snapshot.documents {
        if document.kind == Kind::SkillState {
            for value in array(&document.value, "旧技能禁用列表")? {
                disabled.insert(value.as_str().ok_or("旧技能ID不是字符串")?.to_owned());
            }
        }
        if document.kind == Kind::SkillOrigin {
            origins = object(&document.value, "旧技能来源")?.clone();
        }
    }
    // Match the original scanner: immediate skill folders and loose Markdown only,
    // with SKILL.md/skill.md/Skill.md priority. Internal fallback is authoritative
    // only when the original application had no externalFilesDir.
    let root = snapshot
        .external_files_root
        .as_ref()
        .unwrap_or(&snapshot.source_root);
    let mut entries = std::collections::BTreeMap::new();
    for asset in snapshot
        .assets
        .iter()
        .filter(|asset| &asset.owning_root == root)
    {
        let path = Path::new(&asset.relative_path);
        if !path.starts_with("skills") {
            continue;
        }
        let Some(name) = path.file_name().and_then(|s| s.to_str()) else {
            continue;
        };
        let (key, priority) = if path.components().count() == 3 {
            let Some(priority) = ["SKILL.md", "skill.md", "Skill.md"]
                .iter()
                .position(|candidate| *candidate == name)
                .or_else(|| name.eq_ignore_ascii_case("SKILL.md").then_some(3))
            else {
                continue;
            };
            (path.parent().ok_or("旧技能目录缺失")?.to_owned(), priority)
        } else if path.components().count() == 2
            && path
                .extension()
                .and_then(|s| s.to_str())
                .is_some_and(|s| s.eq_ignore_ascii_case("md"))
        {
            (path.to_owned(), 0)
        } else {
            continue;
        };
        if entries
            .get(&key)
            .is_none_or(|(_, previous)| (priority, name) < *previous)
        {
            entries.insert(key, (asset, (priority, name)));
        }
    }
    let entries = entries
        .into_values()
        .map(|(asset, _)| legacy_skill_id(&asset.relative_path).map(|id| (asset, id)))
        .collect::<Result<Vec<_>, _>>()?;
    let reserved: BTreeSet<_> = entries
        .iter()
        .map(|(_, id)| *id)
        .filter(|id| crate::skills::valid_id(id))
        .collect();
    let mut assigned = BTreeSet::new();
    for (asset, id) in entries {
        let preferred = if crate::skills::valid_id(id) {
            id.to_owned()
        } else {
            crate::skills::sanitize_import_id(id)
        };
        let imported_id = if (preferred == id || !reserved.contains(preferred.as_str()))
            && assigned.insert(preferred.clone())
        {
            preferred
        } else {
            let hash = digest(asset.relative_path.as_bytes());
            // The selected skill root is fixed. The relative document path identifies
            // duplicate old IDs without depending on file contents or absolute device paths.
            (0..=reserved.len() + assigned.len())
                .map(|index| {
                    if index == 0 {
                        format!("legacy-skill-{hash}")
                    } else {
                        format!("legacy-skill-{hash}-{index}")
                    }
                })
                .find(|candidate| {
                    !reserved.contains(candidate.as_str()) && assigned.insert(candidate.clone())
                })
                .ok_or("旧技能无法分配唯一ID")?
        };
        if imported_id != id {
            plan.warnings.push(format!(
                "旧技能ID {id} 已映射为安全ID {imported_id}；原文件名保留。"
            ));
        }
        let destination = plan
            .assets
            .iter()
            .find(|mapping| {
                mapping.owning_root == asset.owning_root
                    && mapping.relative_path == asset.relative_path
            })
            .and_then(|mapping| mapping.workspace_path.as_ref())
            .ok_or("旧技能原文缺少明确工作区副本映射，未发布无资源路径的技能")?;
        let document_path = destination.to_string_lossy().into_owned();
        let files_path = destination
            .parent()
            .ok_or("旧技能资源目录缺失")?
            .to_string_lossy()
            .into_owned();
        let document = String::from_utf8_lossy(&asset.original_bytes);
        if matches!(&document, std::borrow::Cow::Owned(_)) {
            plan.warnings.push(format!(
                "旧技能文档 {} 含无效UTF-8；目录正文使用替代字符，归档及工作区原始字节保留。",
                asset.relative_path
            ));
        }
        match crate::skills::import_document_with_fallback(
            imported_id,
            document.into_owned(),
            Some(id),
        ) {
            Ok(mut skill) => {
                skill.document_path = document_path;
                skill.files_path = files_path;
                skill.enabled = !disabled.contains(id);
                skill.revision = 1;
                if let Some(origin) = origins.get(id) {
                    let origin = object(origin, "旧技能来源")?;
                    skill.source = match string_field(origin, "source", "user")?
                        .trim()
                        .to_ascii_lowercase()
                        .as_str()
                    {
                        "downloaded" => "downloaded",
                        "builtin" => "builtin",
                        _ => "user",
                    }
                    .into();
                    skill.source_url = string_field(origin, "url", "")?;
                    skill.updated_at = integer_field(origin, "at", 0)?;
                } else {
                    skill.source = "user".into();
                }
                skill.validate()?;
                plan.skills.push(skill);
            }
            Err(error) => retained(
                plan,
                &format!("旧技能{id}完整文档已归档，当前技能解析器不兼容：{error}"),
            ),
        };
    }
    Ok(())
}
fn legacy_skill_id(relative: &str) -> Result<&str, String> {
    let path = Path::new(relative);
    if path
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name.eq_ignore_ascii_case("SKILL.md"))
    {
        path.parent().and_then(Path::file_name)
    } else {
        path.file_stem()
    }
    .and_then(|name| name.to_str())
    .ok_or_else(|| "旧技能目录或文件ID无效".into())
}

fn validate_usage(usage: &LegacyChatUsage) -> Result<(), String> {
    if usage.scanned < 0
        || usage.scanned_up_to < 0
        || usage.scanned_bytes < 0
        || usage.scanned_gen < -2
        || usage.deleted_at < 0
    {
        return Err("旧用量水位或时间为负".into());
    }
    let tokens = |v: &LegacyTokens| -> Result<(), String> {
        if [v.prompt, v.completion, v.reasoning, v.cached]
            .iter()
            .any(|v| *v < 0)
        {
            Err("旧用量Long计数为负".into())
        } else {
            Ok(())
        }
    };
    for value in usage.days.values().chain(usage.models.values()) {
        tokens(value)?;
    }
    for day in usage.daily_models.values() {
        for value in day.values() {
            tokens(&value.tokens)?;
            if value.reported < 0 || value.unreported < 0 {
                return Err("旧用量请求汇总为负".into());
            }
        }
    }
    Ok(())
}
fn seal(bytes: &[u8], cipher: &dyn SecretCipher) -> Result<Vec<u8>, String> {
    let mut chunks = Vec::new();
    for chunk in bytes.chunks(RAW_CHUNK_BYTES) {
        let mut hex = String::with_capacity(chunk.len() * 2);
        const DIGITS: &[u8] = b"0123456789abcdef";
        for byte in chunk {
            hex.push(DIGITS[(byte >> 4) as usize] as char);
            hex.push(DIGITS[(byte & 15) as usize] as char);
        }
        chunks.push(
            cipher
                .encrypt(&hex)
                .map_err(|e| format!("旧原文保密归档加密失败：{e}；原源保留，未提交"))?,
        );
    }
    serde_json::to_vec(&SealedBlob {
        version: 1,
        encoding: "hex".into(),
        bytes: bytes.len() as u64,
        sha256: digest(bytes),
        chunks,
    })
    .map_err(|_| "旧原文归档编码失败".into())
}
pub fn read_archived_blob(
    generation_root: &Path,
    mapping: &AssetMapping,
    cipher: &dyn SecretCipher,
) -> Result<Vec<u8>, String> {
    if !mapping.archive_path.starts_with("archive") || mapping.bytes > 128 * 1024 * 1024 {
        return Err("旧原文归档路径或预算无效".into());
    }
    let bytes = super::transaction::read_regular(
        generation_root,
        &mapping.archive_path,
        512 * 1024 * 1024,
    )?;
    decode_archived_blob(&bytes, mapping, cipher)
}
/// The bytes used by this decode come from the one bounded, identity-checked
/// read. A separate pre-read cannot constrain a later wider archive read.
pub(crate) fn read_archived_blob_bounded(
    generation_root: &Path,
    mapping: &AssetMapping,
    cipher: &dyn SecretCipher,
    raw_limit: u64,
) -> Result<Vec<u8>, String> {
    if raw_limit > 128 * 1024 * 1024
        || mapping.bytes > raw_limit
        || !mapping.archive_path.starts_with("archive")
    {
        return Err("旧原文归档超出调用方读取预算".into());
    }
    // Plaintext is hexadecimal inside chunked encrypted JSON. This allowance
    // bounds container overhead while keeping the small caller budget effective.
    let container_limit = mapping
        .bytes
        .saturating_mul(8)
        .saturating_add(65536)
        .min(512 * 1024 * 1024);
    let bytes =
        super::transaction::read_regular(generation_root, &mapping.archive_path, container_limit)?;
    decode_archived_blob(&bytes, mapping, cipher)
}
pub(super) fn decode_archived_blob(
    bytes: &[u8],
    mapping: &AssetMapping,
    cipher: &dyn SecretCipher,
) -> Result<Vec<u8>, String> {
    if mapping.bytes > 128 * 1024 * 1024 || bytes.len() > 512 * 1024 * 1024 {
        return Err("旧原文归档超过解码预算".into());
    }
    let sealed: SealedBlob = serde_json::from_slice(bytes).map_err(|_| "旧原文归档损坏")?;
    if sealed.version != 1
        || sealed.encoding != "hex"
        || sealed.bytes != mapping.bytes
        || sealed.sha256 != mapping.sha256
        || sealed.chunks.len() > 8192
    {
        return Err("旧原文归档身份或预算不一致".into());
    }
    let mut result = Vec::new();
    for chunk in sealed.chunks {
        let text = cipher
            .decrypt(&chunk)
            .map_err(|e| format!("旧原文归档无法解密：{e}"))?;
        if text.len() > RAW_CHUNK_BYTES * 2 || text.len() % 2 != 0 {
            return Err("旧原文归档分块长度无效".into());
        }
        for pair in text.as_bytes().chunks_exact(2) {
            let high = hex_digit(pair[0]).ok_or("旧原文归档分块编码无效")?;
            let low = hex_digit(pair[1]).ok_or("旧原文归档分块编码无效")?;
            result.push(high * 16 + low);
            if result.len() as u64 > mapping.bytes {
                return Err("旧原文解密长度超过原始预算".into());
            }
        }
    }
    if result.len() as u64 != mapping.bytes || digest(&result) != mapping.sha256 {
        return Err("旧原文解密后hash或长度校验失败".into());
    }
    Ok(result)
}
fn hex_digit(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        _ => None,
    }
}
fn object<'a>(value: &'a Value, label: &str) -> Result<&'a Map<String, Value>, String> {
    value.as_object().ok_or_else(|| format!("{label}不是对象"))
}
fn array<'a>(value: &'a Value, label: &str) -> Result<&'a Vec<Value>, String> {
    value.as_array().ok_or_else(|| format!("{label}不是列表"))
}
fn string_field(fields: &Map<String, Value>, key: &str, default: &str) -> Result<String, String> {
    match fields.get(key) {
        None => Ok(default.into()),
        Some(Value::String(value)) => Ok(value.clone()),
        _ => Err(format!("旧字段{key}不是字符串")),
    }
}
fn integer_field(fields: &Map<String, Value>, key: &str, default: i64) -> Result<i64, String> {
    fields.get(key).map_or(Ok(default), |v| {
        v.as_i64().ok_or_else(|| format!("旧字段{key}不是Long整数"))
    })
}
fn boolean_field(fields: &Map<String, Value>, key: &str, default: bool) -> Result<bool, String> {
    fields.get(key).map_or(Ok(default), |v| {
        v.as_bool().ok_or_else(|| format!("旧字段{key}不是布尔值"))
    })
}
fn rename(fields: &mut Map<String, Value>, old: &str, new: &str) -> Result<(), String> {
    if fields.contains_key(old) && fields.contains_key(new) {
        return Err(format!("旧字段{old}/{new}存在冲突拼写"));
    }
    if let Some(value) = fields.remove(old) {
        fields.insert(new.into(), value);
    }
    Ok(())
}
fn retained(plan: &mut MigrationPlan, feature: &str) {
    if !plan.retained_features.iter().any(|v| v == feature) {
        plan.retained_features.push(feature.into());
        plan.warnings
            .push(format!("完整原文已密封保留，功能尚未全部接续：{feature}"));
    }
}
