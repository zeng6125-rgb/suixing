use crate::state::{Message, ToolCall};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::collections::HashMap;

pub(crate) struct RunLedger {
    core: suixing_search::run_ledger::RunLedger,
    tokens: HashMap<String, u64>,
    names: HashMap<u64, String>,
    next_token: u64,
}

impl RunLedger {
    pub(crate) fn new(max_rounds: u32) -> Self {
        Self {
            core: suixing_search::run_ledger::RunLedger::new(max_rounds),
            tokens: HashMap::new(),
            names: HashMap::new(),
            next_token: 0,
        }
    }

    pub(crate) fn record(&mut self, call: &ToolCall, result: &Value) -> Result<(), String> {
        let token = if let Some(token) = self.tokens.get(&call.name) {
            *token
        } else {
            self.next_token += 1;
            let token = self.next_token;
            self.names.insert(token, call.name.clone());
            self.tokens.insert(call.name.clone(), token);
            token
        };
        let args = serde_json::from_str::<Value>(&call.arguments);
        let mut args_hash = Sha256::new();
        match &args {
            Ok(value) => fingerprint(&mut args_hash, value, Root::Arguments),
            Err(_) => string(&mut args_hash, &call.arguments),
        }
        let mut result_hash = Sha256::new();
        fingerprint(
            &mut result_hash,
            result,
            if call.name == "job_list" {
                Root::JobList
            } else {
                Root::Result
            },
        );
        let ok = result.get("ok").and_then(Value::as_bool).unwrap_or(false);
        let polling = matches!(
            call.name.as_str(),
            "wait_agents"
                | "list_agents"
                | "ui_dump"
                | "ui_current_app"
                | "ui_screenshot"
                | "terminal_read"
                | "todo_read"
                | "goal_read"
                | "goal_status"
        ) || call.name.starts_with("list_")
            || call.name.ends_with("_status")
            || (call.name == "ui_virtual_screen"
                && args
                    .as_ref()
                    .ok()
                    .and_then(|args| args.get("action"))
                    .and_then(Value::as_str)
                    .is_some_and(|action| matches!(action, "status" | "screenshot")));
        self.core
            .record(
                token,
                args_hash.finalize().into(),
                result_hash.finalize().into(),
                ok,
                polling,
            )
            .map_err(|_| "工具执行记录更新失败".to_owned())?;
        let retained = self.core.retained_tools();
        self.names.retain(|token, name| {
            if retained.contains(token) {
                true
            } else {
                self.tokens.remove(name);
                false
            }
        });
        Ok(())
    }

    pub(crate) fn take_note(&mut self) -> Option<String> {
        let (tool, level, count) = self.core.warning()?;
        let tool = &self.names[&tool];
        let warning = if level == 1 {
            format!(
                "重复调用提醒：{tool} 已出现 {count} 次相同参数和相同结果。请核对是否有进展，考虑换一种方法；本提醒不会停止任务。"
            )
        } else {
            format!(
                "重复调用提醒升级：{tool} 已出现 {count} 次相同参数和相同结果。请避免继续重复；若无法推进，请说明阻塞点和已尝试的方法。任务仍可继续。"
            )
        };
        let (total, failed) = self.core.totals();
        let recent = self
            .core
            .recent()
            .map(|(step, tool, ok)| {
                format!(
                    "{step}. {}：{}",
                    self.names[&tool],
                    if ok { "成功" } else { "失败" }
                )
            })
            .collect::<Vec<_>>()
            .join("；");
        Some(format!(
            "[工具执行记录]\n已尝试 {total} 次工具调用，失败 {failed} 次。最近记录：{recent}。\n{warning}"
        ))
    }

    pub(crate) fn attach_summary(&self, messages: &mut Vec<Message>, run: &str) {
        let Some((step, tool, ok)) = self.core.recent().last() else {
            return;
        };
        let (total, failed) = self.core.totals();
        let summary = format!(
            "本次尝试 {total} 次工具调用，其中 {} 次成功、{failed} 次失败；最后一次为第 {step} 步的 {}（{}）。",
            total.wrapping_sub(failed),
            self.names[&tool],
            if ok { "成功" } else { "失败" }
        );
        if let Some(message) = messages.last_mut().filter(|message| {
            message.role == "assistant" && !message.complete && message.tool_calls.is_empty()
        }) {
            if message.text.is_empty() && message.thinking.is_empty() {
                message.text = summary;
            } else {
                message.metadata.trace_note = Some(summary);
            }
        } else {
            let mut message = super::assistant_placeholder();
            message.text = summary;
            message.metadata.run_id = Some(run.into());
            messages.push(message);
        }
    }
}

#[derive(Clone, Copy)]
enum Root {
    Arguments,
    Result,
    JobList,
    None,
}

fn string(hash: &mut Sha256, value: &str) {
    hash.update(b"s");
    hash.update((value.len() as u64).to_le_bytes());
    hash.update(value.as_bytes());
}

fn fingerprint(hash: &mut Sha256, value: &Value, root: Root) {
    match value {
        Value::Object(values) => {
            hash.update(b"{");
            let mut keys: Vec<_> = values
                .keys()
                .filter(|key| match root {
                    Root::Arguments => !matches!(
                        key.as_str(),
                        "timeout" | "timeout_ms" | "timeout_seconds" | "deadline_ms" | "wait_ms"
                    ),
                    Root::Result => key.as_str() != "file_activities",
                    Root::JobList => !matches!(key.as_str(), "file_activities" | "elapsed_ms"),
                    Root::None => true,
                })
                .collect();
            keys.sort_unstable();
            for key in keys {
                string(hash, key);
                fingerprint(
                    hash,
                    &values[key],
                    if matches!(root, Root::JobList) {
                        Root::JobList
                    } else {
                        Root::None
                    },
                );
            }
            hash.update(b"}");
        }
        Value::Array(values) => {
            hash.update(b"[");
            for value in values {
                fingerprint(
                    hash,
                    value,
                    if matches!(root, Root::JobList) {
                        Root::JobList
                    } else {
                        Root::None
                    },
                );
            }
            hash.update(b"]");
        }
        Value::String(value) => string(hash, value),
        Value::Number(value) => {
            hash.update(b"p");
            string(hash, &number(&value.to_string()));
        }
        Value::Bool(value) => hash.update(if *value {
            b"true".as_slice()
        } else {
            b"false".as_slice()
        }),
        Value::Null => hash.update(b"null"),
    }
}

fn number(raw: &str) -> String {
    let (mantissa, exponent) = raw.split_once(['e', 'E']).unwrap_or((raw, "0"));
    let Ok(exponent) = exponent.parse::<i64>() else {
        return raw.into();
    };
    let fractional = mantissa.split_once('.').map_or(0, |(_, tail)| tail.len());
    let negative = mantissa.starts_with('-');
    let digits: String = mantissa
        .chars()
        .filter(|character| character.is_ascii_digit())
        .collect();
    let digits = digits.trim_start_matches('0');
    if digits.is_empty() {
        return "0".into();
    }
    let significant = digits.trim_end_matches('0');
    let Some(exponent) = exponent
        .checked_sub(fractional as i64)
        .and_then(|exponent| exponent.checked_add((digits.len() - significant.len()) as i64))
    else {
        return raw.into();
    };
    format!(
        "{}{significant}e{exponent}",
        if negative { "-" } else { "" }
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn list_result(elapsed: u64, last_line: &str) -> Value {
        json!({"ok":true,"jobs":[{"id":"job1","status":"running",
            "elapsed_ms":elapsed,"exit_code":null,"last_line":last_line}],
            "total":1,"list_truncated":false,"file_activities":[],"notice":""})
    }

    fn list_call() -> ToolCall {
        ToolCall {
            id: "call1".into(),
            name: "job_list".into(),
            arguments: "{}".into(),
        }
    }

    #[test]
    fn job_list_elapsed_time_does_not_hide_unchanged_results() {
        let mut ledger = RunLedger::new(0);
        let call = list_call();
        for elapsed in 1..=5 {
            ledger
                .record(&call, &list_result(elapsed * 1000, ""))
                .unwrap();
        }
        assert!(ledger.take_note().is_some());
        ledger.record(&call, &list_result(6000, "")).unwrap();
        assert!(ledger.take_note().is_none());
    }

    #[test]
    fn job_list_output_progress_clears_the_previous_reminder() {
        let mut ledger = RunLedger::new(0);
        let call = list_call();
        for _ in 0..5 {
            ledger.record(&call, &list_result(1000, "")).unwrap();
        }
        ledger
            .record(&call, &list_result(2000, "download 1%"))
            .unwrap();
        assert!(ledger.take_note().is_none());
        for _ in 0..4 {
            ledger
                .record(&call, &list_result(2000, "download 1%"))
                .unwrap();
        }
        assert!(ledger.take_note().is_some());
    }

    #[test]
    fn job_list_completion_clears_the_running_reminder() {
        let mut ledger = RunLedger::new(0);
        let call = list_call();
        for _ in 0..5 {
            ledger.record(&call, &list_result(1000, "")).unwrap();
        }
        let mut completed = list_result(2000, "");
        completed["jobs"][0]["status"] = json!("done");
        completed["jobs"][0]["exit_code"] = json!(0);
        ledger.record(&call, &completed).unwrap();
        assert!(ledger.take_note().is_none());
    }
}
