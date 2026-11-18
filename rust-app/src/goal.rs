//! Persistent goal ownership. Only an explicit user action starts/resumes a goal;
//! reports carry the current run identity and cannot revive an old generation.
use crate::state::ToolCall;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use suixing_search::rust_protocol::ToolDefinition;

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Status {
    Active,
    #[default]
    Paused,
    Blocked,
    Completed,
}

impl Status {
    pub fn label(self) -> &'static str {
        match self {
            Self::Active => "进行中",
            Self::Paused => "已暂停",
            Self::Blocked => "受阻",
            Self::Completed => "已报告完成",
        }
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Goal {
    pub id: String,
    pub chat_id: String,
    pub text: String,
    pub status: Status,
    pub progress: String,
    pub detail: String,
    pub revision: u64,
    pub wake_at: i64,
    pub updated_at: i64,
}

impl Goal {
    pub fn validate(&self) -> Result<(), String> {
        for (text, max) in [
            (&self.id, 128),
            (&self.chat_id, 128),
            (&self.text, 64 * 1024),
            (&self.progress, 8192),
            (&self.detail, 8192),
        ] {
            if text.len() > max || text.contains('\0') {
                return Err("目标字段超过预算或包含NUL".into());
            }
        }
        if self.updated_at < 0 || self.wake_at < 0 {
            return Err("目标时间字段无效".into());
        }
        if !self.text.is_empty()
            && (self.text.trim().is_empty() || self.id.is_empty() || self.chat_id.is_empty())
        {
            return Err("目标缺少内容或所属身份".into());
        }
        if self.text.is_empty()
            && (!self.id.is_empty() || !self.chat_id.is_empty() || self.status == Status::Active)
        {
            return Err("空目标包含执行身份".into());
        }
        if self.status != Status::Active && self.wake_at != 0 {
            return Err("非运行目标不能保留唤醒时间".into());
        }
        Ok(())
    }

    pub fn prompt(&self) -> Result<String, String> {
        let goal = serde_json::to_string(self).map_err(|_| "目标上下文编码失败")?;
        Ok(format!(
            "\nThe user explicitly started this persistent objective (JSON): {goal}\nContinue working toward it across model turns. Use goal_update with concrete progress, completion evidence, or a blocker. An ordinary final reply does not complete the objective. Do not claim completion while required work remains. Goal ownership does not grant tool permission.\n"
        ))
    }

    pub fn summary_prompt(&self) -> String {
        let outcome = if self.detail.is_empty() {
            &self.progress
        } else {
            &self.detail
        };
        format!(
            "Write the final reply to the user in Chinese. Summarize the recorded outcome and available evidence. If blocked, explain what is needed to continue. Do not start another task or use tools.\nStatus: {}\nObjective: {}\nOutcome: {outcome}",
            self.status.label(),
            self.text,
        )
    }
}

#[derive(Clone, Debug)]
pub enum Command {
    Read,
    OwnedSnapshot,
    Report {
        status: Status,
        note: String,
        wait_seconds: u64,
    },
    ConsumeWake {
        wake_at: i64,
    },
    Block {
        detail: String,
    },
}

pub enum Slash {
    Read,
    Clear,
    Resume,
    Start(String),
}

pub fn slash_argument(text: &str) -> Option<&str> {
    let text = text.trim();
    let end = text.find(char::is_whitespace).unwrap_or(text.len());
    let (name, argument) = text.split_at(end);
    name.eq_ignore_ascii_case("/goal").then(|| argument.trim())
}

impl Slash {
    pub fn parse(argument: &str) -> Result<Self, String> {
        if argument.is_empty() {
            return Ok(Self::Read);
        }
        if argument.eq_ignore_ascii_case("resume") {
            return Ok(Self::Resume);
        }
        if argument.eq_ignore_ascii_case("clear") {
            return Ok(Self::Clear);
        }
        if argument.starts_with('{') {
            let value: Value =
                serde_json::from_str(argument).map_err(|_| "目标参数需要 JSON 对象或文本")?;
            let text = value
                .get("text")
                .and_then(Value::as_str)
                .ok_or("目标 JSON 参数需要字符串 text")?
                .trim();
            return Ok(if text.is_empty() {
                Self::Clear
            } else {
                Self::Start(text.into())
            });
        }
        Ok(Self::Start(argument.into()))
    }
}

pub fn definitions() -> Vec<ToolDefinition> {
    vec![
        ToolDefinition{name:"goal_read".into(),description:"Read the persistent objective and progress belonging to this conversation. Requires per-call approval.".into(),parameters:json!({"type":"object","properties":{},"additionalProperties":false})},
        ToolDefinition{name:"goal_update".into(),description:"Report concrete progress, verified completion evidence or a blocker for the user-started goal owned by this run. Does not start or resume a goal. Requires per-call approval.".into(),parameters:json!({"type":"object","properties":{"status":{"type":"string","enum":["progress","completed","blocked"]},"note":{"type":"string"},"wait_seconds":{"type":"integer","minimum":0,"maximum":60,"default":0}},"required":["status","note"],"additionalProperties":false})},
    ]
}

pub fn prepare(call: &ToolCall) -> Result<Command, String> {
    if call.arguments.len() > 64 * 1024 {
        return Err("目标参数超过64 KiB".into());
    }
    let value: Value = serde_json::from_str(&call.arguments).map_err(|_| "目标参数不是JSON")?;
    let args = value.as_object().ok_or("目标参数必须是对象")?;
    let allowed: &[&str] = match call.name.as_str() {
        "goal_read" => &[],
        "goal_update" => &["status", "note", "wait_seconds"],
        _ => return Err("目标的启动与继续只接受用户操作".into()),
    };
    if args.keys().any(|key| !allowed.contains(&key.as_str())) {
        return Err("不支持的目标参数".into());
    }
    if call.name == "goal_read" {
        return Ok(Command::Read);
    }
    let status = match args.get("status").and_then(Value::as_str) {
        Some("progress") => Status::Active,
        Some("completed") => Status::Completed,
        Some("blocked") => Status::Blocked,
        _ => return Err("目标状态必须是progress/completed/blocked".into()),
    };
    let note = args
        .get("note")
        .and_then(Value::as_str)
        .filter(|note| !note.trim().is_empty() && note.len() <= 8192 && !note.contains('\0'))
        .ok_or("目标报告必须有明确说明，最多8 KiB")?
        .to_owned();
    let wait_seconds = args
        .get("wait_seconds")
        .map(|value| {
            value
                .as_u64()
                .filter(|value| *value <= 60)
                .ok_or("等待时间必须是0到60秒")
        })
        .transpose()?
        .unwrap_or(0);
    if wait_seconds > 0 && status != Status::Active {
        return Err("只有进度报告可以请求等待".into());
    }
    Ok(Command::Report {
        status,
        note,
        wait_seconds,
    })
}
