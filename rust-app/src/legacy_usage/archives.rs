//! Recover original cache/DeletedUsage and committed main/child membership from
//! pinned encrypted archives. Live drafts, tail bytes and duplicate JSON copies
//! never become committed usage rows.
use crate::{
    legacy_import::{
        LegacyBlob, LegacyDocument, LegacyDocumentKind as Kind, message_occurrence_ids,
    },
    legacy_migration::{self, ArchiveCatalog, AssetMapping},
    platform::secrets::SecretBox,
};
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::Path,
};
pub struct Source {
    pub id: String,
    pub parent: String,
    pub child: Option<String>,
    pub members: BTreeSet<String>,
    pub rows: Vec<Value>,
}
pub struct Inputs {
    pub documents: Vec<LegacyDocument>,
    pub sources: Vec<Source>,
}
pub fn load(catalog: &ArchiveCatalog, owning_root: &Path) -> Result<Inputs, String> {
    catalog.recheck()?;
    let mut assets = BTreeMap::new();
    for mapping in catalog
        .assets
        .iter()
        .filter(|mapping| mapping.owning_root == owning_root)
    {
        if assets
            .insert(mapping.relative_path.as_str(), mapping)
            .is_some()
        {
            return Err("归档用量来源路径重复".into());
        }
    }
    let mut total = 0u64;
    let mut documents = Vec::new();
    let mut mains = BTreeMap::new();
    let mut children = Vec::new();
    for (path, mapping) in &assets {
        if *path == "usage.json" || path.starts_with("deleted-usage/") && path.ends_with(".json") {
            let (raw, value) = read(catalog, mapping, &mut total)?;
            documents.push(LegacyDocument {
                kind: if *path == "usage.json" {
                    Kind::UsageCache
                } else {
                    Kind::DeletedUsage
                },
                value,
                source: LegacyBlob {
                    owning_root: mapping.owning_root.clone(),
                    relative_path: mapping.relative_path.clone(),
                    original_bytes: raw,
                    sha256: mapping.sha256.clone(),
                },
            });
        } else if path.starts_with("chats/store/") && path.ends_with(".meta") {
            let (_, metadata) = read(catalog, mapping, &mut total)?;
            let id = string(&metadata, "id")?;
            if *path != format!("chats/store/{id}.meta") {
                return Err("归档主来源metadata路径与ID冲突".into());
            }
            let generation = number(&metadata, "gen")?;
            let committed = number(&metadata, "logBytes")?;
            let log = format!("chats/store/{id}.{generation}.jsonl");
            let mapping = assets
                .get(log.as_str())
                .ok_or("归档主来源缺少meta指定已提交日志")?;
            if mapping.bytes != committed {
                return Err("归档主来源日志不是meta确认的提交边界".into());
            }
            let raw = blob(catalog, mapping, &mut total)?;
            let mut messages = Vec::new();
            for line in raw
                .split(|byte| *byte == b'\n')
                .filter(|line| !line.is_empty())
            {
                if line.len() > 20 * 1024 * 1024 || messages.len() >= 131_072 {
                    return Err("归档主来源消息超过预算".into());
                }
                messages.push(
                    serde_json::from_slice::<Value>(line).map_err(|_| "已提交归档日志行损坏")?,
                );
            }
            if messages.len() as u64 != number(&metadata, "messageCount")? {
                return Err("归档主来源提交消息数不符".into());
            }
            if let Some(last) = messages.last() {
                if last.get("id").and_then(Value::as_str)
                    != Some(string(&metadata, "lastMessageId")?.as_str())
                {
                    return Err("归档主来源末消息身份不符".into());
                }
            }
            let source = source(id.clone(), id.clone(), None, &messages)?;
            if mains.insert(id, source).is_some() {
                return Err("归档主来源重复".into());
            }
        } else if path.starts_with("subagent_records/") && path.ends_with(".json") {
            let (_, value) = read(catalog, mapping, &mut total)?;
            let id = string(&value, "id")?;
            let parent = string(&value, "parentChatId")?;
            let messages = value
                .get("messages")
                .and_then(Value::as_array)
                .ok_or("归档子来源messages不是数组")?;
            children.push(source(
                format!("child/{parent}/{id}"),
                parent,
                Some(id),
                messages,
            )?);
        }
    }
    // The original importer chooses store metadata/log over a duplicate old JSON.
    for (path, mapping) in &assets {
        if !path.starts_with("chats/")
            || path[6..].contains('/')
            || !path.ends_with(".json")
            || path.ends_with(".corrupt.json")
            || path.ends_with(".live.json")
        {
            continue;
        }
        let (_, value) = read(catalog, mapping, &mut total)?;
        let id = string(&value, "id")?;
        if *path != format!("chats/{id}.json") {
            return Err("归档旧JSON来源文件名与身份冲突".into());
        }
        if mains.contains_key(&id) {
            continue;
        }
        let messages = value
            .get("messages")
            .and_then(Value::as_array)
            .ok_or("归档旧JSON来源messages不是数组")?;
        mains.insert(id.clone(), source(id.clone(), id, None, messages)?);
    }
    let mut sources: Vec<_> = mains.into_values().collect();
    sources.extend(children);
    let unique: BTreeSet<_> = sources.iter().map(|source| &source.id).collect();
    if unique.len() != sources.len() {
        return Err("归档用量来源身份重复".into());
    }
    let count: usize = sources.iter().map(|source| source.rows.len()).sum();
    if sources.len() > 8192 || count > 262_144 {
        return Err("归档用量来源或消息元数据超过预算，未截断".into());
    }
    catalog.recheck()?;
    Ok(Inputs { documents, sources })
}
fn source(
    id: String,
    parent: String,
    child: Option<String>,
    messages: &[Value],
) -> Result<Source, String> {
    let mut members = BTreeSet::new();
    let mut rows = Vec::new();
    for (message, id) in messages.iter().zip(message_occurrence_ids(messages)?) {
        let id = id.into_owned();
        members.insert(id.clone());
        let fields = message.as_object().ok_or("归档来源消息不是对象")?;
        let mut row = serde_json::Map::new();
        row.insert("id".into(), Value::String(id));
        for key in [
            "role",
            "model",
            "timestamp",
            "promptTokens",
            "completionTokens",
            "reasoningTokens",
            "cachedTokens",
            "prompt_tokens",
            "completion_tokens",
            "reasoning_tokens",
            "cached_tokens",
        ] {
            if let Some(value) = fields.get(key) {
                row.insert(key.into(), value.clone());
            }
        }
        rows.push(Value::Object(row));
    }
    Ok(Source {
        id,
        parent,
        child,
        members,
        rows,
    })
}
fn blob(
    catalog: &ArchiveCatalog,
    mapping: &AssetMapping,
    total: &mut u64,
) -> Result<Vec<u8>, String> {
    if mapping.bytes > 128 * 1024 * 1024 {
        return Err("归档用量单文件超过预算".into());
    }
    *total = total.checked_add(mapping.bytes).ok_or("归档用量字节溢出")?;
    if *total > 256 * 1024 * 1024 {
        return Err("归档用量读取超过256MiB预算".into());
    }
    legacy_migration::read_archived_blob(&catalog.generation_root, mapping, &SecretBox)
}
fn read(
    catalog: &ArchiveCatalog,
    mapping: &AssetMapping,
    total: &mut u64,
) -> Result<(Vec<u8>, Value), String> {
    let bytes = blob(catalog, mapping, total)?;
    let value = serde_json::from_slice(&bytes).map_err(|_| "归档用量JSON损坏")?;
    Ok((bytes, value))
}
fn string(value: &Value, key: &str) -> Result<String, String> {
    value
        .get(key)
        .and_then(Value::as_str)
        .filter(|value| {
            !value.is_empty()
                && value.len() <= 512
                && !value
                    .chars()
                    .any(|character| matches!(character, '\0' | '/' | '\\'))
        })
        .map(str::to_owned)
        .ok_or_else(|| format!("归档来源字段{key}身份无效"))
}
fn number(value: &Value, key: &str) -> Result<u64, String> {
    value
        .get(key)
        .map(|value| {
            value
                .as_u64()
                .ok_or_else(|| format!("归档来源字段{key}类型无效"))
        })
        .transpose()
        .map(|value| value.unwrap_or(0))
}
pub fn current_row(message: &crate::state::Message) -> Value {
    json!({"id":message.id,"role":message.role,"model":message.metadata.model,"timestamp":message.metadata.timestamp,
        "prompt_tokens":message.metadata.prompt_tokens,"completion_tokens":message.metadata.completion_tokens,
        "reasoning_tokens":message.metadata.reasoning_tokens,"cached_tokens":message.metadata.cached_tokens})
}
