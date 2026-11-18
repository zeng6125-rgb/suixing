//! Prepare once at the model boundary; root routes execution through the owning
//! profile repository and caller scope after its normal tool policy decision.
use super::profile::{Repository, Revision, Writer};
use crate::state::ToolCall;
use serde_json::{Value, json};
use suixing_search::rust_protocol::ToolDefinition;
#[derive(Clone, Debug)]
pub enum Prepared {
    Read { expected: Revision },
    Write { expected: Revision, text: String },
}
pub fn prepare(call: &ToolCall, repository: &Repository) -> Result<Prepared, String> {
    if call.arguments.len() > 64 * 1024 {
        return Err("画像工具参数超过64 KiB".into());
    }
    let args: Value = serde_json::from_str(&call.arguments).map_err(|_| "画像工具参数不是JSON")?;
    let args = args.as_object().ok_or("画像工具参数必须是对象")?;
    let expected = repository.revision();
    match call.name.as_str() {
        "profile_read" if args.is_empty() => Ok(Prepared::Read { expected }),
        "profile_write" if args.len() == 1 && args.contains_key("text") => {
            let text = args
                .get("text")
                .and_then(Value::as_str)
                .ok_or("画像text必须是字符串")?;
            if text.encode_utf16().count() > super::profile::MAX_UNITS || text.contains('\0') {
                return Err("画像超过8000字或含NUL".into());
            }
            Ok(Prepared::Write {
                expected,
                text: text.into(),
            })
        }
        _ => Err("未知画像工具或参数字段".into()),
    }
}
pub fn execute(
    prepared: &Prepared,
    repository: &mut Repository,
    scope_live: impl Fn() -> bool,
) -> Result<Value, String> {
    if !scope_live() {
        return Err("画像工具所属父/子调用已结束".into());
    }
    match prepared {
        Prepared::Read { expected } => {
            if &repository.revision() != expected {
                return Err("画像已更新，请重新读取".into());
            }
            Ok(json!({"text":repository.document().text,"revision":repository.revision()}))
        }
        Prepared::Write { expected, text } => {
            let revision = repository.replace(expected, text.clone(), Writer::Model)?;
            Ok(
                json!({"outcome":"profile_updated","units":text.encode_utf16().count(),"revision":revision,"readback_verified":true}),
            )
        }
    }
}
pub fn definitions() -> Vec<ToolDefinition> {
    vec![
    ToolDefinition{name:"profile_read".into(),description:"Read the full user profile and its current revision. This exact content is used for profile injection when enabled.".into(),parameters:json!({"type":"object","properties":{},"required":[],"additionalProperties":false})},
    ToolDefinition{name:"profile_write".into(),description:"Replace the full user profile, at most 8000 UTF-16 units. Read it first to preserve valid long-term information. A newer revision or loss of old bullet entries rejects the write; success requires actual durable readback.".into(),parameters:json!({"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false})},
]
}
