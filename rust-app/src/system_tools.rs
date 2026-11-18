//! Shared memory and assistant tools freeze their exact mutation before approval.
use crate::{
    configuration::Assistant,
    state::{Memory, SettingsStamp, ToolCall},
    store::Store,
};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use suixing_search::rust_protocol::ToolDefinition;

#[derive(Clone)]
pub(crate) enum Prepared {
    Read {
        namespace: String,
        result: Value,
    },
    WriteMemory {
        namespace: String,
        key: String,
        text: String,
        before: Option<Memory>,
    },
    CreateAssistant {
        stamp: SettingsStamp,
        assistant: Assistant,
    },
}
pub(crate) fn is_tool(name: &str) -> bool {
    matches!(name, "memory_read" | "memory_write" | "create_assistant")
}
pub(crate) fn definitions() -> Vec<ToolDefinition> {
    let def = |name: &str, description: &str, properties: Value, required: &[&str]| {
        ToolDefinition {
            name: name.into(),
            description: description.into(),
            parameters: json!({"type":"object","properties":properties,"required":required,"additionalProperties":false}),
        }
    };
    vec![
        def(
            "memory_read",
            "Read local long-term notes. An optional key selects one exact note. Requires approval; values are not automatically sent in model context.",
            json!({"key":{"type":"string"}}),
            &[],
        ),
        def(
            "memory_write",
            "Save a local long-term note by key, up to 4000 UTF-16 units. The approved old value must still match; writes use the shared memory store and return its actual saved value.",
            json!({"key":{"type":"string"},"value":{"type":"string"}}),
            &["key", "value"],
        ),
        def(
            "create_assistant",
            "Create a persona derived from this conversation and save it in the same assistant table as the editor. It becomes the default for new chats; the current run keeps its frozen persona. Requires approval.",
            json!({"name":{"type":"string"},"avatar":{"type":"string"},"description":{"type":"string"},"system_prompt":{"type":"string"}}),
            &["name", "system_prompt"],
        ),
    ]
}
pub(crate) fn memory_id(key: &str) -> String {
    let digest = Sha256::digest(key.as_bytes());
    let hex: String = digest[..16]
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();
    // Existing imported notes already use this exact ID and key prefix.
    format!("legacy-memory-{hex}")
}
fn memory(store: &Store, key: &str) -> Option<Memory> {
    let id = memory_id(key);
    store.memories().into_iter().find(|note| note.id == id)
}
pub(crate) fn prepare(call: &ToolCall, store: &Store) -> Result<Prepared, String> {
    if call.arguments.len() > 128 * 1024 {
        return Err("系统工具参数超过128 KiB".into());
    }
    let value: Value = serde_json::from_str(&call.arguments).map_err(|_| "系统工具参数不是JSON")?;
    let args = value.as_object().ok_or("系统工具参数必须是对象")?;
    let allowed: &[&str] = match call.name.as_str() {
        "memory_read" => &["key"],
        "memory_write" => &["key", "value"],
        "create_assistant" => &["name", "avatar", "description", "system_prompt"],
        _ => return Err("未知系统文档工具".into()),
    };
    if args.keys().any(|key| !allowed.contains(&key.as_str())) {
        return Err("系统工具包含未知参数".into());
    }
    let text = |key: &str, maximum: usize, default: Option<&str>| -> Result<String, String> {
        let value = match args.get(key) {
            Some(value) => value.as_str().ok_or_else(|| format!("{key}必须是文本"))?,
            None => default.ok_or_else(|| format!("缺少参数{key}"))?,
        };
        if value.len() > maximum || value.contains('\0') {
            return Err(format!("参数{key}超过预算或含NUL"));
        }
        Ok(value.to_owned())
    };
    let namespace = store.active_namespace();
    match call.name.as_str() {
        "memory_read" => {
            let key = text("key", 8192, Some(""))?.trim().to_owned();
            let result = if key.is_empty() {
                json!({"ok":true,"notes":store.memories()})
            } else {
                let note = memory(store, &key);
                let value = note.as_ref().map(|note| {
                    note.text
                        .strip_prefix(&format!("{key}: "))
                        .unwrap_or(&note.text)
                });
                json!({"ok":true,"key":key,"found":note.is_some(),"value":value})
            };
            if serde_json::to_vec(&result)
                .map_err(|_| "记忆结果无法编码")?
                .len()
                > 512 * 1024
            {
                return Err("全部记忆结果超过512 KiB，请指定key读取单条；未截断原记忆".into());
            }
            Ok(Prepared::Read { namespace, result })
        }
        "memory_write" => {
            let key = text("key", 8192, None)?.trim().to_owned();
            let value = text("value", 16 * 1024, None)?;
            if key.is_empty() || value.encode_utf16().count() > 4000 {
                return Err("记忆key不能为空，value不能超过4000个UTF-16单位".into());
            }
            Ok(Prepared::WriteMemory {
                namespace,
                before: memory(store, &key),
                text: format!("{key}: {value}"),
                key,
            })
        }
        "create_assistant" => {
            let name = text("name", 1024, None)?.trim().to_owned();
            let system_prompt = text("system_prompt", 64 * 1024, None)?.trim().to_owned();
            if name.is_empty() || system_prompt.is_empty() {
                return Err("缺少助手name或system_prompt".into());
            }
            let avatar = text("avatar", 128, Some("🪶"))?.trim().to_owned();
            let assistant = Assistant {
                id: crate::controller::unique_id(),
                name,
                avatar: if avatar.is_empty() {
                    "🪶".into()
                } else {
                    avatar
                },
                description: text("description", 4096, Some(""))?.trim().to_owned(),
                system_prompt,
                created_at: crate::controller::now_millis(),
                ..Default::default()
            };
            let mut settings = store.settings.clone();
            settings.assistants.push(assistant.clone());
            settings.active_assistant_id = Some(assistant.id.clone());
            crate::configuration::validate(&settings)?;
            Ok(Prepared::CreateAssistant {
                stamp: store.settings_snapshot().stamp,
                assistant,
            })
        }
        _ => unreachable!(),
    }
}
impl Prepared {
    pub(crate) fn writes(&self) -> bool {
        !matches!(self, Self::Read { .. })
    }
    pub(crate) fn summary(&self) -> String {
        match self {
            Self::Read { .. } => "读取本机记忆；只返回本次审批前冻结的笔记".into(),
            Self::WriteMemory { key, .. } => format!("保存本机记忆「{key}」；核对审批前版本"),
            Self::CreateAssistant { assistant, .. } => format!(
                "创建助手「{}」并设为新会话默认助手；当前会话保留原人设",
                assistant.name
            ),
        }
    }
    pub(crate) fn execute(self, call: &ToolCall, store: &mut Store) -> Result<Value, String> {
        match self {
            Self::Read { namespace, result } => {
                if namespace != store.active_namespace() {
                    return Err("记忆所在数据代已变化，请重新读取".into());
                }
                Ok(result)
            }
            Self::WriteMemory {
                namespace,
                key,
                text,
                before,
            } => {
                if namespace != store.active_namespace() {
                    return Err("记忆所在数据代已变化，未写入".into());
                }
                let current = memory(store, &key);
                let equal = match (&before, &current) {
                    (None, None) => true,
                    (Some(a), Some(b)) => {
                        a.id == b.id && a.text == b.text && a.updated_at == b.updated_at
                    }
                    _ => false,
                };
                if !equal {
                    return Err("记忆在审批期间已变化，未覆盖新值".into());
                }
                let saved = store.save_keyed_memory(memory_id(&key), text.clone())?;
                Ok(
                    json!({"ok":true,"key":key,"saved":saved.text==text,"id":saved.id,"updated_at":saved.updated_at}),
                )
            }
            Self::CreateAssistant { stamp, assistant } => {
                let mut settings = store.settings.clone();
                settings.assistants.push(assistant.clone());
                settings.active_assistant_id = Some(assistant.id.clone());
                store.save_settings_for(&call.id, &stamp, settings)?;
                Ok(
                    json!({"ok":true,"assistant_id":assistant.id,"name":assistant.name,"default_for_new_chats":true,"current_run_persona_changed":false}),
                )
            }
        }
    }
}
