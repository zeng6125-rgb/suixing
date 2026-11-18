//! Saved child choices and immutable launch-time authorization. Credentials remain runtime-only.
use crate::{configuration::Provider, state::Settings};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashSet};
use suixing_search::rust_protocol::ToolDefinition;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum ApprovalMode {
    #[serde(rename = "auto", alias = "AUTO")]
    Auto,
    #[default]
    #[serde(rename = "ask", alias = "ASK")]
    Ask,
    #[serde(rename = "deny", alias = "DENY")]
    Deny,
}

impl ApprovalMode {
    pub const ALL: [Self; 3] = [Self::Ask, Self::Auto, Self::Deny];
    pub fn label(self) -> &'static str {
        match self {
            Self::Auto => "自动运行",
            Self::Ask => "每次询问",
            Self::Deny => "禁止工具",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct SubagentModelSelection {
    #[serde(alias = "providerId")]
    pub provider_id: String,
    pub model: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct SubagentProfile {
    pub id: String,
    pub name: String,
    #[serde(alias = "assistantId")]
    pub assistant_id: String,
    pub model: Option<SubagentModelSelection>,
    #[serde(alias = "approvalMode")]
    pub approval_mode: Option<ApprovalMode>,
    #[serde(alias = "allowedTools")]
    pub allowed_tools: Option<Vec<String>>,
    #[serde(alias = "allowedActions")]
    pub allowed_actions: BTreeMap<String, Vec<String>>,
    #[serde(alias = "maxToolRounds")]
    pub max_tool_rounds: Option<u32>,
    #[serde(alias = "timeoutSeconds")]
    pub timeout_seconds: u32,
    #[serde(alias = "extraInstructions")]
    pub extra_instructions: String,
}

impl Default for SubagentProfile {
    fn default() -> Self {
        Self {
            id: String::new(),
            name: "子任务模板".into(),
            assistant_id: String::new(),
            model: None,
            approval_mode: None,
            allowed_tools: None,
            allowed_actions: BTreeMap::new(),
            max_tool_rounds: None,
            timeout_seconds: 180,
            extra_instructions: String::new(),
        }
    }
}

/// Model arguments construct only declared choices; an unsaved profile is typed UI input.
#[derive(Clone, Debug, Default)]
pub struct SubagentLaunchOptions {
    pub profile_id: Option<String>,
    pub assistant_id: Option<String>,
    pub model: Option<SubagentModelSelection>,
    pub approval_mode: Option<ApprovalMode>,
    pub allowed_tools: Option<Vec<String>>,
    pub allowed_actions: Option<BTreeMap<String, Vec<String>>>,
    pub max_tool_rounds: Option<u32>,
    pub timeout_seconds: Option<u32>,
    pub draft_profile: Option<SubagentProfile>,
}

#[derive(Clone, Debug)]
pub struct ChildLaunchDraft {
    pub run_id: String,
    pub request_id: String,
    pub task: String,
    pub context: String,
    pub name: String,
    pub profile_id: String,
    pub draft: SubagentProfile,
    pub pending: bool,
    pub preview: Option<ChildRunConfig>,
    pub error: String,
}

#[derive(Clone, Debug)]
pub struct SubagentProfileEditor {
    pub draft: SubagentProfile,
    pub saving: bool,
    pub error: String,
}

impl ChildLaunchDraft {
    pub fn options(&self) -> SubagentLaunchOptions {
        SubagentLaunchOptions {
            profile_id: Some(self.profile_id.clone()),
            draft_profile: Some(self.draft.clone()),
            ..Default::default()
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct ChildRunConfig {
    #[serde(alias = "profileId")]
    pub profile_id: String,
    #[serde(alias = "profileName")]
    pub profile_name: String,
    #[serde(alias = "assistantId")]
    pub assistant_id: String,
    #[serde(alias = "assistantName")]
    pub assistant_name: String,
    #[serde(alias = "assistantAvatar")]
    pub assistant_avatar: String,
    #[serde(alias = "assistantAvatarPath")]
    pub assistant_avatar_path: String,
    #[serde(alias = "providerId")]
    pub provider_id: String,
    pub model: String,
    #[serde(alias = "approvalMode")]
    pub approval_mode: ApprovalMode,
    #[serde(alias = "allowedTools")]
    pub allowed_tools: Vec<String>,
    #[serde(alias = "allowedActions")]
    pub allowed_actions: BTreeMap<String, Vec<String>>,
    /// Zero imposes no round limit; other resource and timeout limits still apply.
    #[serde(alias = "maxToolRounds")]
    pub max_tool_rounds: u32,
    #[serde(alias = "timeoutSeconds")]
    pub timeout_seconds: u32,
    #[serde(alias = "extraInstructions")]
    pub extra_instructions: String,
}

impl Default for ChildRunConfig {
    fn default() -> Self {
        Self {
            profile_id: String::new(),
            profile_name: "跟随父任务".into(),
            assistant_id: String::new(),
            assistant_name: "跟随父助手".into(),
            assistant_avatar: String::new(),
            assistant_avatar_path: String::new(),
            provider_id: String::new(),
            model: String::new(),
            approval_mode: ApprovalMode::Ask,
            allowed_tools: Vec::new(),
            allowed_actions: BTreeMap::new(),
            max_tool_rounds: 0,
            timeout_seconds: 180,
            extra_instructions: String::new(),
        }
    }
}

impl ChildRunConfig {
    /// Called before approval or effect dispatch, including plugin and MCP tools.
    pub fn denial_reason(&self, name: &str, args: &serde_json::Value) -> Option<String> {
        if !self.allowed_tools.iter().any(|allowed| allowed == name) {
            return Some(format!("子任务配置不允许工具：{name}"));
        }
        let actions = self.allowed_actions.get(name)?;
        // Kotlin JsonPrimitive.content accepts primitive numbers and booleans as well as strings.
        let action = args.get("action").and_then(|value| match value {
            serde_json::Value::String(text) => Some(text.clone()),
            serde_json::Value::Number(_) | serde_json::Value::Bool(_) => Some(value.to_string()),
            _ => None,
        });
        if action
            .as_ref()
            .is_some_and(|action| actions.contains(action))
        {
            None
        } else {
            let value = action.as_deref().unwrap_or("未指定");
            let mut display = value.chars().take(128).collect::<String>();
            if display.len() < value.len() {
                display.push('…');
            }
            Some(format!("子任务配置不允许 {name} 的动作：{display}"))
        }
    }
}

/// Never serialize or derive Debug: catalog and connection can contain credentials.
#[derive(Clone)]
pub struct FrozenChildInputs {
    pub catalog: Settings,
    pub parent_connection: Settings,
    pub parent_assistant_id: Option<String>,
    pub parent_persona_prompt: String,
    pub available_tools: Vec<ToolDefinition>,
    pub disabled_tools: HashSet<String>,
    pub plugins: Vec<crate::plugins::PluginEntry>,
    pub plugin_context: String,
    pub parent_goal: String,
    pub mcp_tools: Vec<crate::mcp::ToolBinding>,
    pub mcp: Option<crate::mcp::McpBridge>,
}

pub struct ResolvedChildConfig {
    pub config: ChildRunConfig,
    pub settings: Settings,
    pub system_prompt: String,
}

pub fn is_child_tool(name: &str) -> bool {
    !matches!(
        name,
        "spawn_agent"
            | "wait_agents"
            | "list_agents"
            | "cancel_agent"
            | "todo_read"
            | "todo_write"
            | "create_assistant"
    ) && !name.starts_with("goal_")
        && name != "goal"
}

pub fn resolve_child(
    options: &SubagentLaunchOptions,
    frozen: &FrozenChildInputs,
) -> Result<ResolvedChildConfig, String> {
    let settings = &frozen.catalog;
    let selected_id = options
        .profile_id
        .as_deref()
        .unwrap_or(&settings.default_subagent_profile_id);
    let saved = if options.draft_profile.is_none() && !selected_id.is_empty() {
        Some(
            settings
                .subagent_profiles
                .iter()
                .find(|profile| profile.id == selected_id)
                .ok_or("子任务模板已不存在，请重新选择")?,
        )
    } else {
        None
    };
    let profile = options.draft_profile.as_ref().or(saved);
    if let Some(profile) = profile {
        validate_profile_structure(profile)?;
    }
    let assistant_id = options
        .assistant_id
        .as_deref()
        .unwrap_or_else(|| profile.map_or("", |profile| profile.assistant_id.as_str()));
    let chosen = if assistant_id.is_empty() {
        None
    } else {
        Some(
            settings
                .assistants
                .iter()
                .find(|assistant| assistant.id == assistant_id)
                .ok_or("指定的子任务助手已不存在")?,
        )
    };
    let mut connection = frozen.parent_connection.clone();
    let mut prompt = frozen.parent_persona_prompt.clone();
    if let Some(assistant) = chosen {
        connection = settings.child_assistant_settings(&connection, assistant)?;
        if !assistant.system_prompt.trim().is_empty() {
            prompt = assistant.system_prompt.clone();
        }
    } else if profile.is_none() && options.profile_id.is_none() {
        // The old optional global child model may be stale; only explicit overrides fail closed.
        if let Some(selection) = &settings.subagent_model {
            if let Ok(provider) = selected_provider(settings, selection) {
                apply_child_model(&mut connection, settings, provider, &selection.model);
            }
        }
    }
    if let Some(selection) = options
        .model
        .as_ref()
        .or_else(|| profile.and_then(|profile| profile.model.as_ref()))
    {
        let provider = selected_provider(settings, selection)?;
        apply_child_model(&mut connection, settings, provider, &selection.model);
    }
    let approval_mode = [
        Some(frozen.parent_connection.approval_mode),
        profile.and_then(|profile| profile.approval_mode),
        options.approval_mode,
    ]
    .into_iter()
    .flatten()
    .max()
    .unwrap_or_default();
    let profile_tools = profile.and_then(|profile| profile.allowed_tools.as_ref());
    let requested_tools = options.allowed_tools.as_ref();
    for names in [profile_tools, requested_tools].into_iter().flatten() {
        validate_names(names)?;
    }
    let mut allowed_tools = frozen
        .available_tools
        .iter()
        .map(|tool| tool.name.as_str())
        .filter(|name| is_child_tool(name) && !frozen.disabled_tools.contains(*name))
        .filter(|name| {
            chosen.is_none_or(|assistant| {
                !assistant
                    .disabled_tools
                    .iter()
                    .any(|denied| denied.as_str() == *name)
            })
        })
        .filter(|name| {
            profile_tools.is_none_or(|names| names.iter().any(|allowed| allowed.as_str() == *name))
        })
        .filter(|name| {
            requested_tools
                .is_none_or(|names| names.iter().any(|allowed| allowed.as_str() == *name))
        })
        .map(str::to_owned)
        .collect::<Vec<_>>();
    allowed_tools.sort_by(|left, right| left.encode_utf16().cmp(right.encode_utf16()));
    allowed_tools.dedup();
    let inherited = profile.map(|profile| &profile.allowed_actions);
    let requested = options.allowed_actions.as_ref();
    if let Some(actions) = requested {
        validate_actions(actions, requested_tools)?;
    }
    let mut allowed_actions = BTreeMap::new();
    for name in inherited
        .into_iter()
        .flat_map(|actions| actions.keys())
        .chain(requested.into_iter().flat_map(|actions| actions.keys()))
    {
        if allowed_actions.contains_key(name) || !allowed_tools.contains(name) {
            continue;
        }
        let first = inherited.and_then(|actions| actions.get(name));
        let second = requested.and_then(|actions| actions.get(name));
        let mut values = match (first, second) {
            (Some(first), Some(second)) => first
                .iter()
                .filter(|value| second.contains(value))
                .cloned()
                .collect(),
            (Some(values), None) | (None, Some(values)) => values.clone(),
            _ => Vec::new(),
        };
        let mut seen = HashSet::new();
        values.retain(|value| seen.insert(value.clone()));
        allowed_actions.insert(name.clone(), values);
    }
    let requested_rounds = options
        .max_tool_rounds
        .or_else(|| profile.and_then(|profile| profile.max_tool_rounds));
    let max_tool_rounds = [
        Some(frozen.parent_connection.max_tool_rounds),
        requested_rounds,
    ]
    .into_iter()
    .flatten()
    .filter(|&value| value > 0)
    .min()
    .unwrap_or(0);
    let timeout_seconds = options
        .timeout_seconds
        .unwrap_or_else(|| profile.map_or(180, |profile| profile.timeout_seconds));
    if !(10..=600).contains(&timeout_seconds) {
        return Err("子任务时长必须为10到600秒".into());
    }
    let extra_instructions = profile
        .map(|profile| profile.extra_instructions.clone())
        .unwrap_or_default();
    if !extra_instructions.trim().is_empty() {
        prompt.push_str("\n\n");
        prompt.push_str(&extra_instructions);
    }
    connection.approval_mode = approval_mode;
    connection.max_tool_rounds = max_tool_rounds;
    connection.subagents_enabled = false;
    connection.max_tokens = settings
        .model_max_tokens
        .get(&connection.model)
        .copied()
        .unwrap_or(0);
    // A model switch changes the header trust boundary, even if only the address changes.
    let same_connection = frozen.parent_connection.endpoint.trim_end_matches('/')
        == connection.endpoint.trim_end_matches('/')
        && frozen.parent_connection.api_key == connection.api_key
        && frozen.parent_connection.protocol == connection.protocol;
    if !same_connection {
        connection.request_headers.clear();
    }
    let config = ChildRunConfig {
        profile_id: if selected_id.is_empty() {
            profile
                .map(|profile| profile.id.clone())
                .unwrap_or_default()
        } else {
            selected_id.into()
        },
        profile_name: profile
            .map(|profile| profile.name.clone())
            .unwrap_or_else(|| "跟随父任务".into()),
        assistant_id: chosen
            .map(|assistant| assistant.id.clone())
            .or_else(|| frozen.parent_assistant_id.clone())
            .unwrap_or_default(),
        assistant_name: chosen
            .or_else(|| {
                frozen.parent_assistant_id.as_ref().and_then(|id| {
                    settings
                        .assistants
                        .iter()
                        .find(|assistant| &assistant.id == id)
                })
            })
            .map(|assistant| assistant.name.clone())
            .unwrap_or_else(|| "跟随父助手".into()),
        assistant_avatar: chosen
            .or_else(|| {
                frozen.parent_assistant_id.as_ref().and_then(|id| {
                    settings
                        .assistants
                        .iter()
                        .find(|assistant| &assistant.id == id)
                })
            })
            .map(|assistant| assistant.avatar.clone())
            .unwrap_or_default(),
        assistant_avatar_path: chosen
            .or_else(|| {
                frozen.parent_assistant_id.as_ref().and_then(|id| {
                    settings
                        .assistants
                        .iter()
                        .find(|assistant| &assistant.id == id)
                })
            })
            .map(|assistant| assistant.avatar_path.clone())
            .unwrap_or_default(),
        provider_id: connection.active_provider_id.clone().unwrap_or_default(),
        model: connection.model.clone(),
        approval_mode,
        allowed_tools,
        allowed_actions,
        max_tool_rounds,
        timeout_seconds,
        extra_instructions,
    };
    Ok(ResolvedChildConfig {
        config,
        settings: connection,
        system_prompt: prompt,
    })
}

fn selected_provider<'a>(
    settings: &'a Settings,
    selection: &SubagentModelSelection,
) -> Result<&'a Provider, String> {
    if selection.provider_id.len() > 128
        || selection.model.len() > 1024
        || selection.provider_id.contains('\0')
        || selection.model.contains('\0')
    {
        return Err("子任务供应商或模型ID无效".into());
    }
    settings
        .providers
        .iter()
        .find(|provider| {
            provider.id == selection.provider_id
                && !provider.endpoint.trim().is_empty()
                && !selection.model.trim().is_empty()
                && provider.model_is_enabled(&selection.model)
        })
        .ok_or_else(|| "子任务模型不可用，请选择已启用的模型".into())
}

fn apply_child_model(
    connection: &mut Settings,
    catalog: &Settings,
    provider: &Provider,
    model: &str,
) {
    connection.endpoint = provider.endpoint.clone();
    connection.api_key = provider.api_key.clone();
    connection.protocol = provider.protocol;
    connection.model = model.into();
    connection.active_provider_id = Some(provider.id.clone());
    connection.max_tokens = catalog.model_max_tokens.get(model).copied().unwrap_or(0);
    if provider.own_sampling {
        connection.temperature = provider.temperature.or(connection.temperature);
        connection.top_p = provider.top_p.or(connection.top_p);
        connection.reasoning_effort = provider.reasoning_effort.clone().unwrap_or_default();
    } else {
        connection.reasoning_effort.clear();
    }
}

pub fn validate_profiles(settings: &Settings) -> Result<(), String> {
    if settings.subagent_profiles.len() > 256 || !(1..=3).contains(&settings.subagent_parallelism) {
        return Err("子任务模板或并发配置超出范围".into());
    }
    let mut ids = HashSet::new();
    for profile in &settings.subagent_profiles {
        validate_profile_structure(profile)?;
        if !ids.insert(&profile.id) {
            return Err("子任务模板ID重复".into());
        }
    }
    if !settings.default_subagent_profile_id.is_empty()
        && !ids.contains(&settings.default_subagent_profile_id)
    {
        return Err("默认子任务模板已不存在".into());
    }
    Ok(())
}

pub fn validate_profile(profile: &SubagentProfile, settings: &Settings) -> Result<(), String> {
    validate_profile_structure(profile)?;
    if !profile.assistant_id.is_empty()
        && !settings
            .assistants
            .iter()
            .any(|assistant| assistant.id == profile.assistant_id)
    {
        return Err("模板指定的助手已不可用".into());
    }
    if let Some(selection) = &profile.model {
        selected_provider(settings, selection)?;
    }
    Ok(())
}
fn validate_profile_structure(profile: &SubagentProfile) -> Result<(), String> {
    if profile.id.is_empty()
        || profile.id.len() > 128
        || profile.id.contains('\0')
        || profile.name.trim().is_empty()
        || profile.name.len() > 1024
        || profile.name.contains('\0')
        || profile.assistant_id.len() > 128
        || profile.assistant_id.contains('\0')
        || profile.extra_instructions.len() > 64 * 1024
        || profile.extra_instructions.contains('\0')
        || !(10..=600).contains(&profile.timeout_seconds)
    {
        return Err("子任务模板名称、补充要求或时长无效".into());
    }
    if let Some(names) = &profile.allowed_tools {
        validate_names(names)?;
    }
    validate_actions(&profile.allowed_actions, profile.allowed_tools.as_ref())
}

fn validate_names(names: &[String]) -> Result<(), String> {
    if names.len() > 1024
        || names
            .iter()
            .any(|name| name.is_empty() || name.len() > 128 || name.contains('\0'))
    {
        return Err("子任务工具名称超过预算或无效".into());
    }
    Ok(())
}
fn validate_actions(
    actions: &BTreeMap<String, Vec<String>>,
    tools: Option<&Vec<String>>,
) -> Result<(), String> {
    if actions.len() > 1024 {
        return Err("子任务动作列表超过预算".into());
    }
    let mut bytes = 0usize;
    for (name, values) in actions {
        if name.is_empty()
            || name.len() > 128
            || name.contains('\0')
            || tools.is_some_and(|tools| !tools.contains(name))
        {
            return Err("动作限制必须属于允许的工具".into());
        }
        validate_names(values)?;
        bytes = bytes.saturating_add(name.len() + values.iter().map(String::len).sum::<usize>());
    }
    if bytes > 128 * 1024 {
        return Err("子任务动作限制超过128 KiB".into());
    }
    Ok(())
}
