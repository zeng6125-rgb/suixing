//! Legacy SlashCommand parsing/argument mapping. These helpers PREPARE owned
//! calls only. The actor enforces current tool/persona filters and per-call
//! approval and executes an initial tool inside the accepted run.
use crate::{goal::Slash, state::ToolCall};
use serde_json::{Map, Value};
use std::collections::HashSet;
use suixing_search::rust_protocol::ToolDefinition;
#[derive(Clone, Debug)]
pub struct SlashCommand<'a> {
    pub name: String,
    pub argument: &'a str,
    pub token: &'a str,
}
pub fn parse(text: &str) -> Option<SlashCommand<'_>> {
    let text = text.trim();
    let end = text.find(char::is_whitespace).unwrap_or(text.len());
    let token = &text[..end];
    let name = token.strip_prefix('/')?;
    let mut chars = name.chars();
    if !chars
        .next()
        .is_some_and(|first| first.is_ascii_alphabetic())
        || !chars.all(|character| character.is_ascii_alphanumeric() || character == '_')
    {
        return None;
    }
    Some(SlashCommand {
        name: name.to_ascii_lowercase(),
        argument: text[end..].trim(),
        token,
    })
}
pub fn starts_run(text: &str) -> bool {
    match parse(text) {
        Some(command) if command.name == "goal" => !matches!(
            Slash::parse(command.argument),
            Ok(Slash::Read | Slash::Clear)
        ),
        _ => true,
    }
}
pub enum Prepared {
    Plain,
    Goal {
        command: Slash,
        legacy_tool_name: &'static str,
    },
    Tool {
        call: ToolCall,
        transcript_text: String,
    },
}
pub fn arguments(spec: &ToolDefinition, argument: &str) -> Result<Value, String> {
    if argument.len() > 64 * 1024 {
        return Err("指令参数超过64 KiB".into());
    }
    if argument.is_empty() {
        return Ok(Value::Object(Map::new()));
    }
    if argument.starts_with('{') {
        let value: Value = serde_json::from_str(argument)
            .map_err(|_| "参数格式不对：需要JSON对象，或用一句文本")?;
        if !value.is_object() {
            return Err("指令参数需要JSON对象".into());
        }
        return Ok(value);
    }
    let properties = spec
        .parameters
        .get("properties")
        .and_then(Value::as_object)
        .ok_or("指令参数需要JSON对象")?;
    let string_property = |name: &str| {
        properties
            .get(name)
            .and_then(|value| value.get("type"))
            .and_then(Value::as_str)
            == Some("string")
    };
    let required = spec
        .parameters
        .get("required")
        .and_then(Value::as_array)
        .and_then(|values| {
            values
                .iter()
                .filter_map(Value::as_str)
                .find(|name| string_property(name))
        });
    let name = required
        .or_else(|| {
            properties
                .keys()
                .find(|name| string_property(name))
                .map(String::as_str)
        })
        .ok_or("此工具没有可接受文本的字符串参数，请填写JSON对象")?;
    let path_property = name == "path" || name.ends_with("_path") || name.ends_with("Path");
    let value = if path_property {
        super::references::only_path(argument).unwrap_or_else(|| argument.into())
    } else {
        argument.into()
    };
    Ok(Value::Object(
        [(name.into(), Value::String(value))].into_iter().collect(),
    ))
}
pub fn prepare(
    text: &str,
    tools: &[ToolDefinition],
    disabled: &HashSet<String>,
    has_attachments: bool,
) -> Result<Prepared, String> {
    let Some(command) = parse(text) else {
        return Ok(Prepared::Plain);
    };
    // Original image-bearing slash calls were rejected. Reject every attached
    // file too, so a merged file note cannot change "/goal clear" into goal text.
    // An explicit inline @"absolute path" remains a valid path argument.
    if has_attachments {
        return Err("斜杠指令不能带附件；可将文件引用写入指令参数".into());
    }
    if command.name == "goal" {
        let parsed = Slash::parse(command.argument)?;
        let legacy_tool_name = match parsed {
            Slash::Read => "goal_read",
            Slash::Resume => "goal_resume",
            Slash::Clear | Slash::Start(_) => "goal",
        };
        if disabled.contains(legacy_tool_name) {
            return Err(format!("当前助手禁用此工具：/{}", command.name));
        }
        return Ok(Prepared::Goal {
            command: parsed,
            legacy_tool_name,
        });
    }
    if disabled.contains(&command.name) {
        return Err(format!("当前助手禁用此工具：/{}", command.name));
    }
    let spec = tools
        .iter()
        .find(|tool| tool.name == command.name)
        .ok_or_else(|| format!("未知指令或工具：/{}", command.name))?;
    let value = arguments(spec, command.argument)?;
    let encoded = serde_json::to_string(&value).map_err(|_| "指令参数编码失败")?;
    if encoded.len() > 64 * 1024 {
        return Err("指令参数超过64 KiB".into());
    }
    let call = ToolCall {
        id: crate::controller::unique_id(),
        name: spec.name.clone(),
        arguments: encoded,
    };
    Ok(Prepared::Tool {
        transcript_text: transcript_text(text, &call.name),
        call,
    })
}
pub const REDACTED: &str = "〔敏感内容：仅本轮提供给模型，未写入会话记录〕";
pub fn sensitive_arguments(name: &str) -> bool {
    matches!(name, "ui_type" | "ui_type_into" | "clipboard_write")
}
pub fn transcript_text(text: &str, tool_name: &str) -> String {
    if !sensitive_arguments(tool_name) {
        return text.into();
    }
    let token = parse(text).map_or("", |command| command.token);
    format!("{token} {}", serde_json::json!({"redacted":REDACTED}))
}
pub fn edited_transcript_text(text: &str, user_message: bool) -> String {
    if !user_message {
        return text.into();
    }
    match parse(text) {
        Some(command) => transcript_text(text, &command.name),
        None => text.into(),
    }
}
// Actor invokes prepare at send AND dequeue/steer using the current tool/persona
// catalogue. Only after schema validation and tool approval does the accepted run
// execute the original call once. It stores transcript_text and a redacted tool
// arguments copy; raw sensitive arguments remain only on the worker.
// Regenerate does not automatically re-execute the stored command. Edit+resend
// prepares the new command; plain edit only redacts and never executes it.
// Required-property ordering is preserved by schema required[]. When there are
// no required strings, properties follows the owning ToolDefinition map order;
// preserving old arbitrary registration order requires ordered schema metadata.
