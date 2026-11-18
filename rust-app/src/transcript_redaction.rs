//! Persistence boundary matching the original TranscriptRedaction.kt. Values
//! needed by the current model turn stay in memory; storage receives a copy.
use crate::state::{Message, ToolCall};
use serde_json::{Value, json};
use std::collections::HashMap;
pub const PLACEHOLDER: &str = "〔敏感内容：仅本轮提供给模型，未写入会话记录〕";
pub fn sensitive_arguments(tool: &str) -> bool {
    matches!(tool, "ui_type" | "ui_type_into" | "clipboard_write")
}
pub fn sensitive_result(tool: &str) -> bool {
    tool == "clipboard_read"
}
pub fn is_redacted(tool: &str) -> bool {
    sensitive_arguments(tool) || sensitive_result(tool)
}
fn is_placeholder_value(value: &Value) -> bool {
    value.as_str() == Some(PLACEHOLDER)
        || value.as_object().is_some_and(|object| {
            object.len() == 1 && object.get("redacted").and_then(Value::as_str) == Some(PLACEHOLDER)
        })
}
pub fn arguments_for_transcript(tool: &str, arguments: &str) -> String {
    if sensitive_arguments(tool) {
        json!({"redacted":PLACEHOLDER}).to_string()
    } else {
        arguments.to_owned()
    }
}
pub fn output_for_transcript(tool: &str, output: &str) -> String {
    if sensitive_result(tool) {
        PLACEHOLDER.into()
    } else {
        output.into()
    }
}
pub fn value_for_transcript(tool: &str, output: &Value) -> Value {
    if sensitive_result(tool) {
        json!({"redacted":PLACEHOLDER})
    } else {
        output.clone()
    }
}
pub fn command_for_transcript(text: &str, tool: &str, arguments: &str) -> String {
    if !sensitive_arguments(tool) {
        return text.into();
    }
    let end = text.find(char::is_whitespace).unwrap_or(text.len());
    format!(
        "{} {}",
        &text[..end],
        arguments_for_transcript(tool, arguments)
    )
}
pub fn call_for_transcript(call: &ToolCall) -> ToolCall {
    ToolCall {
        id: call.id.clone(),
        name: call.name.clone(),
        arguments: arguments_for_transcript(&call.name, &call.arguments),
    }
}
/// Use for a durable assistant message's tool_calls. Never mutate the live
/// model function calls or execute a reconstructed, redacted call.
pub fn calls_for_transcript(calls: &[ToolCall]) -> Vec<ToolCall> {
    calls.iter().map(call_for_transcript).collect()
}
/// Redact a detached persistence/display projection. The accepted worker's
/// original messages and calls must remain available to its current model turn.
/// ID resolution belongs to the current assistant batch, never a global map in
/// which a later reused ID could change an earlier output's classification.
pub fn redact_messages(messages: &mut [Message]) {
    let mut names: HashMap<String, String> = HashMap::new();
    let mut sensitive = SensitiveTurn::default();
    let mut active_run: Option<String> = None;
    for message in messages {
        if let Some(run_id) = &message.metadata.run_id {
            if active_run
                .as_ref()
                .is_some_and(|previous| previous != run_id)
            {
                names.clear();
                sensitive = SensitiveTurn::default();
            }
            active_run = Some(run_id.clone());
        }
        if message.role == "user" {
            // Steer messages carry their current accepted run ID. They do not
            // erase secrets already supplied during that same model turn.
            if message.metadata.run_id.is_none() {
                names.clear();
                sensitive = SensitiveTurn::default();
                active_run = None;
            }
            if let Some(command) = crate::composer::slash::parse(&message.text) {
                sensitive.register_call(&ToolCall {
                    id: message.id.clone(),
                    name: command.name,
                    arguments: command.argument.into(),
                });
            }
            message.text = crate::composer::slash::edited_transcript_text(&message.text, true);
        }
        if message.role == "assistant" && !message.tool_calls.is_empty() {
            names.clear();
            for call in &message.tool_calls {
                names.insert(call.id.clone(), call.name.clone());
                sensitive.register_call(call);
            }
        }
        let named = message.metadata.tool_name.as_deref().unwrap_or("");
        let mapped = message
            .tool_call_id
            .as_ref()
            .and_then(|id| names.get(id))
            .map(String::as_str)
            .unwrap_or("");
        let argument_tool = if sensitive_arguments(named) {
            named
        } else {
            mapped
        };
        let output_tool = if sensitive_result(named) {
            named
        } else {
            mapped
        };
        if let Some(arguments) = &message.metadata.tool_args {
            sensitive.register_call(&ToolCall {
                id: message.id.clone(),
                name: argument_tool.into(),
                arguments: arguments.clone(),
            });
        }
        if message.role == "tool" {
            sensitive.register_output(output_tool, &message.text);
            message.text = output_for_transcript(output_tool, &message.text);
        }
        if let Some(output) = &mut message.metadata.tool_output {
            sensitive.register_output(output_tool, output);
            *output = output_for_transcript(output_tool, output);
        }
        if let Some(arguments) = &mut message.metadata.tool_args {
            *arguments = arguments_for_transcript(argument_tool, arguments);
        }
        for call in &mut message.tool_calls {
            // A partial streaming name cannot establish whether its arguments
            // are sensitive. Only the detached unfinished projection is hidden.
            call.arguments = if !message.complete || call.name.is_empty() {
                json!({"redacted":PLACEHOLDER}).to_string()
            } else {
                arguments_for_transcript(&call.name, &call.arguments)
            };
        }
        if message.role == "assistant" {
            message.text = sensitive.scrub_summary(&message.text);
            message.thinking = sensitive.scrub_summary(&message.thinking);
        }
        if let Some(note) = &mut message.metadata.trace_note {
            *note = sensitive.scrub_summary(note);
        }
    }
}
pub fn messages_for_transcript(messages: &[Message]) -> Vec<Message> {
    let mut projected = messages.to_vec();
    redact_messages(&mut projected);
    projected
}
/// Inspect the raw accepted run projection without mutating it. Historical
/// placeholder-only calls are ignored by the registry. Once this is sensitive,
/// the worker buffers display deltas until a complete scrubbed checkpoint; that
/// avoids matching a secret separately against chunks that split its bytes.
pub fn sensitive_turn_for(messages: &[Message]) -> SensitiveTurn {
    let mut sensitive = SensitiveTurn::default();
    let mut names: HashMap<String, String> = HashMap::new();
    for message in messages {
        if message.role == "user" {
            if let Some(command) = crate::composer::slash::parse(&message.text) {
                sensitive.register_call(&ToolCall {
                    id: message.id.clone(),
                    name: command.name,
                    arguments: command.argument.into(),
                });
            }
        }
        if message.role == "assistant" && !message.tool_calls.is_empty() {
            names.clear();
            for call in &message.tool_calls {
                names.insert(call.id.clone(), call.name.clone());
                sensitive.register_call(call);
            }
        }
        let named = message.metadata.tool_name.as_deref().unwrap_or("");
        let mapped = message
            .tool_call_id
            .as_ref()
            .and_then(|id| names.get(id))
            .map(String::as_str)
            .unwrap_or("");
        let argument_tool = if sensitive_arguments(named) {
            named
        } else {
            mapped
        };
        let output_tool = if sensitive_result(named) {
            named
        } else {
            mapped
        };
        if let Some(arguments) = &message.metadata.tool_args {
            sensitive.register_call(&ToolCall {
                id: message.id.clone(),
                name: argument_tool.into(),
                arguments: arguments.clone(),
            });
        }
        if message.role == "tool" {
            sensitive.register_output(output_tool, &message.text);
        }
        if let Some(output) = &message.metadata.tool_output {
            sensitive.register_output(output_tool, output);
        }
    }
    sensitive
}
/// Per-turn memory only. Use the scrubbed copy for generated child summaries or
/// checkpoint text that may echo known sensitive payloads. Never serialize this
/// registry, store its hashes, or use the scrubbed value as a model tool result.
#[derive(Default)]
pub struct SensitiveTurn {
    values: Vec<String>,
    bytes: usize,
    saturated: bool,
}
impl std::fmt::Debug for SensitiveTurn {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SensitiveTurn")
            .field("values", &"[redacted]")
            .field("saturated", &self.saturated)
            .finish()
    }
}
impl SensitiveTurn {
    pub fn is_sensitive(&self) -> bool {
        self.saturated || !self.values.is_empty()
    }
    pub fn register_call(&mut self, call: &ToolCall) {
        if !sensitive_arguments(&call.name) {
            return;
        }
        if serde_json::from_str::<Value>(&call.arguments)
            .is_ok_and(|value| is_placeholder_value(&value))
        {
            return;
        }
        self.remember(&call.arguments);
        if let Ok(value) = serde_json::from_str::<Value>(&call.arguments) {
            if let Some(text) = value.get("text").and_then(Value::as_str) {
                self.remember(text);
            }
        }
    }
    pub fn register_output(&mut self, tool: &str, output: &str) {
        if !sensitive_result(tool) {
            return;
        }
        if output == PLACEHOLDER
            || serde_json::from_str::<Value>(output).is_ok_and(|value| is_placeholder_value(&value))
        {
            return;
        }
        self.remember(output);
        if let Ok(value) = serde_json::from_str::<Value>(output) {
            if let Some(text) = value
                .get("text")
                .and_then(Value::as_str)
                .or_else(|| value.as_str())
            {
                self.remember(text);
            }
        }
    }
    fn remember(&mut self, value: &str) {
        if value.is_empty() || value == PLACEHOLDER || self.saturated {
            return;
        }
        let encoded = serde_json::to_string(value).unwrap_or_default();
        for candidate in [value, encoded.as_str()] {
            if candidate.is_empty() || self.values.iter().any(|v| v == candidate) {
                continue;
            }
            if self.values.len() >= 128
                || self.bytes.saturating_add(candidate.len()) > 2 * 1024 * 1024
            {
                self.saturated = true;
                return;
            }
            self.bytes += candidate.len();
            self.values.push(candidate.into());
        }
        self.values.sort_by_key(|v| std::cmp::Reverse(v.len()));
    }
    pub fn scrub_summary(&self, text: &str) -> String {
        if self.values.is_empty() && !self.saturated {
            return text.into();
        }
        if self.saturated || text.len() > 1024 * 1024 {
            return PLACEHOLDER.into();
        }
        let pattern = self
            .values
            .iter()
            .map(|v| regex::escape(v))
            .collect::<Vec<_>>()
            .join("|");
        match regex::RegexBuilder::new(&pattern)
            .size_limit(8 * 1024 * 1024)
            .dfa_size_limit(8 * 1024 * 1024)
            .build()
        {
            // Replacement text is never searched again, including short payloads
            // that also happen to occur inside the explanatory placeholder.
            Ok(matcher) => {
                let mut output = String::new();
                let mut previous = 0;
                for found in matcher.find_iter(text) {
                    if output
                        .len()
                        .saturating_add(found.start() - previous)
                        .saturating_add(PLACEHOLDER.len())
                        > 1024 * 1024
                    {
                        return PLACEHOLDER.into();
                    }
                    output.push_str(&text[previous..found.start()]);
                    output.push_str(PLACEHOLDER);
                    previous = found.end();
                }
                if output.len().saturating_add(text.len() - previous) > 1024 * 1024 {
                    return PLACEHOLDER.into();
                }
                output.push_str(&text[previous..]);
                output
            }
            Err(_) => PLACEHOLDER.into(),
        }
    }
}
