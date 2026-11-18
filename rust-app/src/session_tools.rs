//! Draft only. Freeze from the actual model worker's resolved route and request
//! context; the catalog, global selection and latest conversation are not inputs.
use crate::{
    context::PromptEstimate,
    runtime_host::RunKey,
    state::{Settings, ToolCall},
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use suixing_search::rust_protocol::ToolDefinition;

pub(crate) const MAIN_SYSTEM_PROMPT: &str = "You are JAgent. Use only advertised tools. File tool paths are inside the private workspace. The built-in run_shell uses Android sh as the app UID with workspace cwd, which is not a filesystem sandbox. Never claim unadvertised capabilities. Frozen application approval rules govern each tool call. Denial is not permission to retry an equivalent action under a different name. Report actual tool results; committed=true with verified=false or effects_may_have_occurred=true means an action may already have happened and must not be blindly repeated.";

pub(crate) fn approval_prompt(mode: crate::subagent_configuration::ApprovalMode) -> &'static str {
    match mode {
        crate::subagent_configuration::ApprovalMode::Auto => {
            "The user selected automatic tool execution for this run. Application hooks can still require confirmation or deny a call; obey every actual decision."
        }
        crate::subagent_configuration::ApprovalMode::Ask => {
            "Each tool call requires a separate user decision before dispatch. Wait for the recorded approval and result."
        }
        crate::subagent_configuration::ApprovalMode::Deny => {
            "The user disabled tool execution for this run. Tool calls will be denied. Use the supplied context and explain any resulting limits."
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Caller {
    pub run: RunKey,
    pub child_id: Option<String>,
}
impl Caller {
    pub fn validate(&self) -> Result<(), String> {
        self.run.validate()?;
        if let Some(child) = &self.child_id {
            id(child)?;
        }
        Ok(())
    }
}

/// Metadata sampled from this caller at the tool boundary. PromptEstimate must
/// come from the same ContextInput/system prefix/compaction snapshot used for
/// this caller's network request. Estimates never become billed token usage.
#[derive(Clone, Debug)]
pub struct ContextSnapshot {
    pub estimate: Option<PromptEstimate>,
    pub context_window: u64,
    pub checkpoint_count: usize,
    pub message_count: usize,
    /// For child calls, count the child transcript rather than the main chat.
    pub title: String,
    /// Optional global metadata count, never a substitute for this chat's size.
    pub conversation_count: Option<usize>,
}

#[derive(Clone, Debug)]
pub struct ContextReading {
    pub model: String,
    pub window: u64,
    pub used: u64,
    pub system: u64,
    pub tools: u64,
    pub history: u64,
    pub estimated: bool,
}

impl ContextReading {
    pub fn tool_tokens(definitions: &[ToolDefinition]) -> u64 {
        definitions
            .iter()
            .map(|definition| {
                (definition.name.encode_utf16().count()
                    + definition.description.encode_utf16().count()
                    + definition.parameters.to_string().encode_utf16().count())
                    as u64
            })
            .sum::<u64>()
            .div_ceil(3)
    }

    pub fn for_request(
        settings: &Settings,
        estimate: PromptEstimate,
        system_prefix: &[suixing_search::rust_protocol::Message],
        tool_tokens: u64,
    ) -> Self {
        let system = system_prefix
            .iter()
            .map(|message| message.text.encode_utf16().count() as u64)
            .sum::<u64>()
            .div_ceil(3);
        Self {
            model: settings.model.clone(),
            window: settings
                .model_contexts
                .get(&settings.model)
                .copied()
                .filter(|value| *value > 0)
                .map_or(crate::context::DEFAULT_CONTEXT_WINDOW, |value| value as u64),
            used: estimate.tokens.saturating_add(tool_tokens),
            system,
            tools: tool_tokens,
            history: estimate.tokens.saturating_sub(system),
            estimated: true,
        }
    }

    pub fn reported(mut self, prompt_tokens: u64) -> Self {
        self.used = prompt_tokens;
        self.estimated = false;
        self
    }

    pub fn for_history(
        settings: &Settings,
        model: &str,
        history: PromptEstimate,
        system_tokens: u64,
        tool_tokens: u64,
    ) -> Self {
        Self {
            model: model.into(),
            window: settings
                .model_contexts
                .get(model)
                .copied()
                .filter(|value| *value > 0)
                .map_or(crate::context::DEFAULT_CONTEXT_WINDOW, |value| value as u64),
            used: history
                .tokens
                .saturating_add(system_tokens)
                .saturating_add(tool_tokens),
            system: system_tokens,
            tools: tool_tokens,
            history: history.tokens,
            estimated: true,
        }
    }
}

/// Freeze labels from the actual route's owning catalog before it is cleared.
/// None means unavailable; do not infer a provider or persona by model name.
pub struct RouteLabels {
    pub provider_id: Option<String>,
    pub provider_name: Option<String>,
    pub assistant_id: Option<String>,
    pub assistant_name: Option<String>,
}

#[derive(Clone, Debug)]
pub struct FrozenStatus {
    caller: Caller,
    document: Value,
}
impl FrozenStatus {
    pub fn freeze(
        caller: Caller,
        resolved: &Settings,
        labels: RouteLabels,
        context: ContextSnapshot,
    ) -> Result<Self, String> {
        caller.validate()?;
        field(&resolved.model, 512)?;
        field(&context.title, 1024)?;
        for value in [
            &labels.provider_id,
            &labels.provider_name,
            &labels.assistant_id,
            &labels.assistant_name,
        ]
        .into_iter()
        .flatten()
        {
            field(value, 512)?;
        }
        if context.context_window == 0
            || context.checkpoint_count > 4096
            || context.message_count > 131_072
            || context
                .conversation_count
                .is_some_and(|count| count > 131_072)
        {
            return Err("会话状态元数据超过预算或上下文窗口未固定".into());
        }
        // Endpoint origin/path are copied without credentials, queries, fragments
        // or any request headers/API keys. No request or permission probe occurs.
        let endpoint = public_endpoint(&resolved.endpoint)?;
        let estimate = context.estimate.map(|estimate| {
            json!({
                "tokens":estimate.tokens,
                "text_units":estimate.text_units,
                "image_messages":estimate.image_messages,
                "image_count":estimate.image_count,
                "kind":"estimate_for_this_request",
            })
        });
        let document = json!({
            "run_id":caller.run.run_id,
            "conversation_id":caller.run.conversation_id,
            "child_id":caller.child_id,
            "model":resolved.model,
            "endpoint":endpoint,
            "protocol":resolved.protocol.label(),
            "provider":{"id":labels.provider_id,"name":labels.provider_name},
            "assistant":{"id":labels.assistant_id,"name":labels.assistant_name},
            "context_window_tokens":context.context_window,
            "prompt":estimate,
            "checkpoint_count":context.checkpoint_count,
            "conversation":{"title":context.title,"message_count":context.message_count},
            "conversation_count":context.conversation_count,
        });
        if serde_json::to_vec(&document)
            .map_err(|_| "会话状态序列化失败")?
            .len()
            > 16 * 1024
        {
            return Err("会话状态结果超过16 KiB".into());
        }
        Ok(Self { caller, document })
    }
    pub fn execute(&self, call: &ToolCall, actual_caller: &Caller) -> Result<Value, String> {
        actual_caller.validate()?;
        if &self.caller != actual_caller {
            return Err("会话状态不属于当前父/子运行".into());
        }
        if call.name != "get_session_status" || call.arguments.len() > 4096 {
            return Err("未知会话工具或参数超过预算".into());
        }
        let args: Value =
            serde_json::from_str(&call.arguments).map_err(|_| "会话状态参数不是JSON")?;
        if !args.as_object().is_some_and(|args| args.is_empty()) {
            return Err("get_session_status不接受参数".into());
        }
        Ok(self.document.clone())
    }
}

pub fn definition() -> ToolDefinition {
    ToolDefinition {
        name: "get_session_status".into(),
        description: "Read this exact parent or child run's frozen model, public endpoint, protocol, assistant and context metadata. Context tokens are estimates for this caller's request. Does not read chat bodies or use the latest conversation/global selection.".into(),
        parameters: json!({"type":"object","properties":{},"required":[],"additionalProperties":false}),
    }
}

fn public_endpoint(raw: &str) -> Result<String, String> {
    if raw.len() > 8192 || raw.contains('\0') {
        return Err("实际端点超过预算".into());
    }
    let mut endpoint = reqwest::Url::parse(raw).map_err(|_| "实际端点无效")?;
    if !matches!(endpoint.scheme(), "http" | "https") || endpoint.host_str().is_none() {
        return Err("实际端点不是HTTP服务".into());
    }
    endpoint
        .set_username("")
        .map_err(|_| "实际端点身份无法隐藏")?;
    endpoint
        .set_password(None)
        .map_err(|_| "实际端点身份无法隐藏")?;
    endpoint.set_query(None);
    endpoint.set_fragment(None);
    Ok(endpoint.as_str().to_owned())
}
fn field(value: &str, max: usize) -> Result<(), String> {
    if value.len() > max || value.contains('\0') {
        Err("会话状态字段超过预算或含NUL".into())
    } else {
        Ok(())
    }
}
pub(super) fn id(value: &str) -> Result<(), String> {
    if value.is_empty() || value.len() > 128 || value.contains('\0') {
        Err("调用身份无效".into())
    } else {
        Ok(())
    }
}
