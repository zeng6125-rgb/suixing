//! UI-owned snapshots and the message boundary to the Rust runtime.
//!
//! Persistence and platform work belong to the runtime. Views change durable data only after
//! receiving a confirming event; sending an action never fabricates a conversation or output.

use std::{
    collections::HashMap,
    fmt,
    path::PathBuf,
    sync::{Arc, mpsc::Sender},
    time::{Duration, Instant},
};

use serde::{Deserialize, Serialize};

/// A process/namespace generation and monotonic successful Settings commit.
/// Neither request identity nor secrets participate in a serialized hash.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SettingsStamp {
    pub generation: String,
    pub revision: u64,
}
#[derive(Clone, Debug)]
pub struct SettingsSnapshot {
    pub stamp: SettingsStamp,
    pub settings: Settings,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SettingsResetScope {
    Appearance,
}
impl SettingsResetScope {
    pub fn apply(self, settings: &mut Settings) {
        let defaults = crate::settings_policy::BehaviorSettings::default();
        match self {
            Self::Appearance => {
                let appearance = &mut settings.behavior;
                appearance.theme_mode = defaults.theme_mode;
                appearance.palette_mode = defaults.palette_mode;
                appearance.font_scale = defaults.font_scale;
                appearance.bubble_width = defaults.bubble_width;
                appearance.corner_scale = defaults.corner_scale;
                appearance.banner_seconds = defaults.banner_seconds;
                appearance.typewriter = defaults.typewriter;
            }
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum Protocol {
    #[default]
    #[serde(alias = "", alias = "openai")]
    OpenAi,
    #[serde(alias = "anthropic")]
    Anthropic,
    #[serde(alias = "openai-responses")]
    Responses,
}

impl Protocol {
    pub const ALL: [Self; 3] = [Self::OpenAi, Self::Anthropic, Self::Responses];

    pub const fn label(self) -> &'static str {
        match self {
            Self::OpenAi => "OpenAI Chat Completions",
            Self::Anthropic => "Anthropic Messages",
            Self::Responses => "OpenAI Responses",
        }
    }
}

#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    #[serde(flatten)]
    pub behavior: crate::settings_policy::BehaviorSettings,
    #[serde(flatten)]
    pub execution: crate::settings_policy::ExecutionSettings,
    #[serde(alias = "baseUrl")]
    pub endpoint: String,
    pub model: String,
    pub models: Vec<String>,
    /// None preserves earlier Rust documents; Some([]) means the user disabled every model.
    #[serde(default)]
    pub enabled_models: Option<Vec<String>>,
    pub protocol: Protocol,
    /// Credentials are session-only until the Android Keystore integration is ready.
    #[serde(skip)]
    pub api_key: String,
    pub temperature: Option<f64>,
    pub stream: bool,
    pub asr_endpoint: String,
    pub asr_model: String,
    #[serde(alias = "asrPath")]
    pub asr_path: String,
    #[serde(alias = "speakReplies")]
    pub speak_replies: bool,
    #[serde(skip)]
    pub asr_key: String,
    #[serde(skip)]
    pub search_key: String,
    pub dark_mode: bool,
    pub providers: Vec<crate::configuration::Provider>,
    pub active_provider_id: Option<String>,
    pub assistants: Vec<crate::configuration::Assistant>,
    #[serde(alias = "activeAssistantId", deserialize_with = "empty_optional_id")]
    pub active_assistant_id: Option<String>,
    #[serde(alias = "systemPrompt")]
    pub system_prompt: String,
    /// Explicitly opted in: existing documents default to not transmitting stored memory.
    pub memory_context: bool,
    pub steer_immediately: bool,
    #[serde(alias = "subagentsEnabled")]
    pub subagents_enabled: bool,
    #[serde(alias = "subagentParallelism")]
    pub subagent_parallelism: u32,
    #[serde(alias = "subagentModel")]
    pub subagent_model: Option<crate::subagent_configuration::SubagentModelSelection>,
    #[serde(alias = "subagentProfiles")]
    pub subagent_profiles: Vec<crate::subagent_configuration::SubagentProfile>,
    #[serde(alias = "defaultSubagentProfileId")]
    pub default_subagent_profile_id: String,
    #[serde(alias = "approvalMode")]
    pub approval_mode: crate::subagent_configuration::ApprovalMode,
    #[serde(alias = "maxToolRounds")]
    pub max_tool_rounds: u32,
    #[serde(alias = "modelMaxTokens")]
    pub model_max_tokens: std::collections::BTreeMap<String, i32>,
    #[serde(alias = "modelContexts")]
    pub model_contexts: std::collections::BTreeMap<String, i32>,
    #[serde(alias = "compressAtPercent")]
    pub compress_at_percent: u32,
    /// Headers may contain credentials. Persist them only after the platform vault is connected.
    #[serde(skip)]
    pub request_headers: std::collections::BTreeMap<String, String>,
    #[serde(alias = "topP")]
    pub top_p: Option<f64>,
    #[serde(alias = "maxTokens")]
    pub max_tokens: i32,
    #[serde(alias = "reasoningEffort")]
    pub reasoning_effort: String,
}

pub(crate) fn empty_optional_id<'de, D: serde::Deserializer<'de>>(
    deserializer: D,
) -> Result<Option<String>, D::Error> {
    Ok(Option::<String>::deserialize(deserializer)?.filter(|value| !value.is_empty()))
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            behavior: Default::default(),
            execution: Default::default(),
            endpoint: String::new(),
            model: String::new(),
            models: Vec::new(),
            enabled_models: Some(Vec::new()),
            protocol: Protocol::default(),
            api_key: String::new(),
            temperature: None,
            stream: true,
            asr_endpoint: String::new(),
            asr_model: String::new(),
            asr_path: "/audio/transcriptions".into(),
            speak_replies: false,
            asr_key: String::new(),
            search_key: String::new(),
            dark_mode: true,
            providers: Vec::new(),
            active_provider_id: None,
            assistants: Vec::new(),
            active_assistant_id: None,
            system_prompt: String::new(),
            memory_context: false,
            steer_immediately: false,
            subagents_enabled: true,
            subagent_parallelism: 2,
            subagent_model: None,
            subagent_profiles: Vec::new(),
            default_subagent_profile_id: String::new(),
            approval_mode: Default::default(),
            max_tool_rounds: 0,
            model_max_tokens: Default::default(),
            model_contexts: Default::default(),
            compress_at_percent: 80,
            request_headers: Default::default(),
            top_p: None,
            max_tokens: 0,
            reasoning_effort: String::new(),
        }
    }
}

impl fmt::Debug for Settings {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Settings")
            .field(
                "endpoint",
                &if self.endpoint.is_empty() {
                    ""
                } else {
                    "[configured]"
                },
            )
            .field("model", &self.model)
            .field("protocol", &self.protocol)
            .field("api_key", &"[redacted]")
            .field("temperature", &self.temperature)
            .field("stream", &self.stream)
            .field(
                "asr_endpoint",
                &if self.asr_endpoint.is_empty() {
                    ""
                } else {
                    "[configured]"
                },
            )
            .field("asr_model", &self.asr_model)
            .field("asr_key", &"[redacted]")
            .field("search_key", &"[redacted]")
            .field("dark_mode", &self.dark_mode)
            .finish()
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Conversation {
    pub id: String,
    pub title: String,
    pub updated_at: i64,
    #[serde(default)]
    pub assistant_id: Option<String>,
    #[serde(default, alias = "providerId")]
    pub provider_id: Option<String>,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default, flatten)]
    pub metadata: crate::chat::ConversationMetadata,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct ToolCall {
    pub id: String,
    pub name: String,
    #[serde(default = "default_tool_arguments")]
    pub arguments: String,
}

fn default_tool_arguments() -> String {
    "{}".into()
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Message {
    pub id: String,
    pub role: String,
    pub text: String,
    pub thinking: String,
    pub complete: bool,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub tool_calls: Vec<ToolCall>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_call_id: Option<String>,
    #[serde(default, flatten)]
    pub metadata: crate::chat::MessageMetadata,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct QueuedTurn {
    pub id: String,
    pub text: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub attachments: Vec<crate::composer::AttachmentRef>,
    #[serde(
        default,
        rename = "rustTransientCommandV1",
        skip_serializing_if = "Option::is_none"
    )]
    pub transient: Option<crate::composer::transient::TransientRef>,
}

#[derive(Debug)]
pub enum SteerInput {
    New(QueuedTurn),
    Queued(String),
}

#[derive(Clone, Debug)]
pub struct ApprovalRequest {
    pub id: String,
    pub run_id: String,
    pub conversation_id: String,
    pub child_id: Option<String>,
    pub child_title: Option<String>,
    pub call: ToolCall,
    pub path: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Memory {
    pub id: String,
    pub text: String,
    pub updated_at: i64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct UsageRecord {
    pub id: String,
    pub conversation_id: String,
    pub model: String,
    /// Milliseconds since the Unix epoch, matching the store's other record timestamps.
    pub at: i64,
    pub prompt_tokens: Option<i32>,
    pub completion_tokens: Option<i32>,
    pub reasoning_tokens: Option<i32>,
    pub cached_tokens: Option<i32>,
    pub error: Option<String>,
    /// Kept across restarts until the runtime confirms a final outcome.
    #[serde(default)]
    pub pending: bool,
    /// A runtime status row is excluded from request billing totals.
    #[serde(default)]
    pub summary: bool,
}

#[derive(Default)]
pub struct TokenUsage {
    pub known_total: i64,
    pub reported: usize,
    pub unreported: usize,
    pub invalid: usize,
}

impl TokenUsage {
    fn add(&mut self, value: Option<i32>) {
        match value {
            Some(value) if value >= 0 => {
                self.known_total += i64::from(value);
                self.reported += 1;
            }
            Some(_) => self.invalid += 1,
            None => self.unreported += 1,
        }
    }
}

/// Rebuilt when the ledger changes, rather than walking all records during every paint.
#[derive(Default)]
pub struct UsageSummary {
    pub prompt: TokenUsage,
    pub completion: TokenUsage,
    pub reasoning: TokenUsage,
    pub cached: TokenUsage,
    pub pending: usize,
}

impl UsageSummary {
    pub fn from_records(records: &[UsageRecord]) -> Self {
        let mut summary = Self::default();
        for record in records {
            if record.pending {
                summary.pending += 1;
            }
            summary.prompt.add(record.prompt_tokens);
            summary.completion.add(record.completion_tokens);
            summary.reasoning.add(record.reasoning_tokens);
            summary.cached.add(record.cached_tokens);
        }
        summary
    }
}

#[derive(Clone, Debug)]
pub struct FileEntry {
    pub path: PathBuf,
    pub name: String,
    pub is_dir: bool,
    pub size: u64,
}

#[derive(Clone, Debug)]
pub struct McpConfigSummary {
    pub config: crate::mcp::ServerConfig,
    pub env_configured: bool,
    pub headers_configured: bool,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Capabilities {
    pub chat: bool,
    pub memory: bool,
    pub usage: bool,
    pub files: bool,
    pub terminal: bool,
    pub settings: bool,
    pub secure_keys: bool,
    pub voice_input: bool,
    pub voice_output: bool,
}

pub struct RuntimeBridge {
    pub action_tx: Sender<Action>,
    pub event_rx: crate::events::Receiver,
    pub(crate) host: Arc<crate::runtime_host::RuntimeHost>,
    pub(crate) observer: crate::runtime_host::ObserverLease,
}

impl RuntimeBridge {
    pub fn cancel_run(&self, key: &crate::runtime_host::RunKey) -> bool {
        self.host.cancel_run(key)
    }

    pub fn cancel_child(&self, key: &crate::runtime_host::RunKey, child_id: &str) -> bool {
        self.host.cancel_child(key, child_id)
    }

    pub fn observer_id(&self) -> u64 {
        self.observer.id()
    }

    pub fn is_attached(&self) -> bool {
        self.observer.is_alive()
    }

    pub fn submit(&self, action: Action) -> Result<(), String> {
        self.host.submit(action)
    }
}
impl Drop for RuntimeBridge {
    fn drop(&mut self) {
        self.observer.detach();
    }
}

pub enum Action {
    ConversationPreference(crate::conversation_preferences::Request),
    SkillImport(crate::skill_import::Request),
    Avatar(crate::avatars::Request),
    ConversationInfo(crate::conversation_manager::Request),
    PluginAssets(crate::plugin_assets::runtime::Request),
    UsageStatistics(crate::controller::usage_runtime::Request),
    ExportConversation(crate::platform::permissions::OperationKey),
    FileTransfer(crate::file_transfer::Request),
    CancelFileTransfer(crate::platform::permissions::OperationKey),
    Profile(crate::profile::Request),
    ProfileSummary(crate::controller::profile_work::Request),
    LegacyArchive(crate::controller::legacy_archive::Request),
    Performance(crate::performance::Request),
    Capability(crate::controller::capability_runtime::Request),
    Linux(crate::controller::linux_runtime::Request),
    Automation(crate::controller::automation_runtime::Request),
    Terminal(crate::controller::terminal_runtime::Request),
    Composer(crate::composer::ComposerCommand),
    Load,
    HistoryOpen(crate::display::ViewSource),
    HistoryContextReading(crate::display::ViewSource),
    LoadJobs,
    ReadJobOutput {
        request_id: String,
        key: crate::jobs::JobKey,
        since: Option<u64>,
    },
    StopJob(crate::jobs::JobKey),
    RequestPlatformPermission {
        operation: crate::platform::permissions::OperationKey,
        kinds: Vec<crate::platform::permissions::PermissionKind>,
    },
    HistoryWindow(crate::display::WindowRequest),
    HistoryTurns(crate::display::turn_navigation::TurnRequest),
    HistoryBody(crate::display::BodyPageRequest),
    HistoryFooter(crate::display::FooterRequest),
    HistoryImage(crate::display::ImageRequest),
    SaveReadPosition(crate::display::ReadPosition),
    HistorySelectCandidate {
        source: crate::display::ViewSource,
        stamp: crate::display::SourceStamp,
        user_id: String,
        run_id: String,
    },
    HistoryRegenerate {
        source: crate::display::ViewSource,
        stamp: crate::display::SourceStamp,
        question_id: String,
    },
    HistoryEdit {
        body: crate::display::BodyRef,
        from_byte: u64,
        to_byte: u64,
        replacement: String,
        resend: bool,
    },
    HistoryCopy(crate::display::BodyRef),
    HistoryOpenFile {
        source: crate::display::ViewSource,
        path: String,
    },
    LoadSchedules,
    ResumeSchedule(crate::platform::scheduler::FireIdentity),
    LoadTodos,
    MutateTodo {
        request_id: String,
        operation: crate::todos::Operation,
    },
    PreviewLegacyData,
    ImportLegacyData {
        source_fingerprint: String,
    },
    MutateSchedule {
        request_id: String,
        operation: crate::schedules::Operation,
    },
    MutateChat {
        request_id: String,
        conversation_id: String,
        mutation: crate::chat::Mutation,
    },
    NewConversation,
    SelectConversation(String),
    DeleteConversation(String),
    SetConversationAssistant {
        conversation_id: String,
        assistant_id: Option<String>,
    },
    SetConversationApproval {
        conversation_id: String,
        mode: crate::subagent_configuration::ApprovalMode,
    },
    SetConversationModel {
        request_id: String,
        conversation_id: Option<String>,
        provider_id: Option<String>,
        model: String,
    },
    PreviewChildConfig {
        run_id: String,
        request_id: String,
        options: crate::subagent_configuration::SubagentLaunchOptions,
    },
    SpawnChild {
        run_id: String,
        request_id: String,
        task: String,
        context: String,
        name: String,
        options: crate::subagent_configuration::SubagentLaunchOptions,
    },
    LoadUsage,
    SendMessage {
        conversation_id: Option<String>,
        text: String,
    },
    RegenerateAnswer {
        conversation_id: String,
        question_id: Option<String>,
    },
    CompactConversation {
        conversation_id: String,
    },
    EditAndResend {
        conversation_id: String,
        message_id: String,
        text: String,
    },
    QueueTurn {
        conversation_id: String,
        turn: QueuedTurn,
    },
    SteerRun {
        conversation_id: String,
        expected: crate::runtime_host::RunKey,
        input: SteerInput,
    },
    EditQueuedTurn {
        conversation_id: String,
        turn: QueuedTurn,
    },
    RemoveQueuedTurn {
        conversation_id: String,
        id: String,
    },
    SendQueuedTurn {
        conversation_id: String,
        id: String,
        automatic: bool,
    },
    StartGoal {
        conversation_id: Option<String>,
        text: String,
    },
    ResumeGoal {
        goal_id: String,
        conversation_id: Option<String>,
    },
    ClearGoal {
        goal_id: String,
    },
    ReadGoal {
        conversation_id: Option<String>,
    },
    CancelRun {
        key: crate::runtime_host::RunKey,
    },
    CancelChild {
        conversation_id: String,
        id: String,
    },
    ResolveApproval {
        run_id: String,
        child_id: Option<String>,
        approval_id: String,
        approved: bool,
    },
    SaveSettings(Settings),
    SaveSettingsFor {
        request_id: String,
        expected: SettingsStamp,
        settings: Settings,
    },
    FinishOnboardingFor {
        request_id: String,
        expected: SettingsStamp,
        settings: Settings,
        clear_secrets: Vec<crate::platform::secrets::SettingsSecretTarget>,
    },
    ResetSettingsFor {
        request_id: String,
        expected: SettingsStamp,
        scope: SettingsResetScope,
    },
    ClearSettingsSecret {
        target: crate::platform::secrets::SettingsSecretTarget,
    },
    FetchModels {
        request_id: String,
        provider_id: Option<String>,
    },
    CancelModels {
        request_id: String,
    },
    SaveMemory(Memory),
    DeleteMemory(String),
    SaveSkill(crate::skills::Skill),
    ImportSkill {
        id: String,
        document: String,
    },
    DeleteSkill {
        id: String,
        expected_revision: u64,
    },
    SetSkillEnabled {
        id: String,
        expected_revision: u64,
        enabled: bool,
    },
    LoadPlugins,
    LoadMcp,
    SaveMcpServer {
        request_id: String,
        config: crate::mcp::ServerConfig,
        env_change: Option<String>,
        headers_change: Option<String>,
    },
    DeleteMcpServer {
        id: String,
    },
    SetMcpEnabled {
        id: String,
        enabled: bool,
    },
    McpConnect {
        id: String,
    },
    McpDisconnect {
        id: String,
    },
    McpRefresh {
        id: String,
    },
    McpTest {
        request_id: String,
        config: crate::mcp::ServerConfig,
        env_change: Option<String>,
        headers_change: Option<String>,
    },
    CancelMcpTest {
        request_id: String,
    },
    PreviewPlugin {
        manifest: String,
    },
    InstallPlugin {
        manifest: String,
    },
    SetPluginEnabled {
        id: String,
        enabled: bool,
    },
    LoadPluginEnvironment {
        id: String,
    },
    SavePluginEnvironment {
        id: String,
        changes: std::collections::BTreeMap<String, Option<String>>,
    },
    UninstallPlugin {
        id: String,
    },
    ListFiles(PathBuf),
    PreviewFile(PathBuf),
    StartTerminal,
    TerminalWrite(String),
    TerminalInput {
        session_id: u64,
        input: crate::vt::Input,
    },
    ResizeTerminalFrame {
        session_id: u64,
        geometry: crate::vt::Geometry,
    },
    ResizeTerminal {
        columns: u16,
        rows: u16,
    },
    StopTerminal,
}

impl fmt::Debug for Action {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Action")
            .field("kind", &std::mem::discriminant(self))
            .finish_non_exhaustive()
    }
}

#[derive(Clone, Debug)]
pub enum Event {
    ConversationPreferences(crate::conversation_preferences::Snapshot),
    ConversationOrderFinished {
        request_id: String,
        result: Result<(), String>,
    },
    SkillImport(crate::skill_import::Update),
    Avatar(crate::avatars::Update),
    ConversationInfo(crate::conversation_manager::Update),
    PluginAssets(crate::plugin_assets::runtime::Update),
    UsageStatistics(crate::controller::usage_runtime::Update),
    FileTransfer(crate::file_transfer::Event),
    Profile(crate::profile::Update),
    ProfileSummary(crate::controller::profile_work::Update),
    LegacyArchive(crate::controller::legacy_archive::Update),
    Performance(crate::performance::Update),
    Capability(crate::controller::capability_runtime::Update),
    Linux(crate::controller::linux_runtime::Update),
    Automation(crate::controller::automation_runtime::Update),
    Terminal(crate::controller::terminal_runtime::Update),
    Composer(crate::composer::ComposerEvent),
    OpenConversation(String),
    OpenJobs,
    Jobs(Vec<crate::jobs::Status>),
    JobOutput {
        request_id: String,
        key: crate::jobs::JobKey,
        result: Result<crate::jobs::Output, String>,
    },
    PlatformPermission(crate::platform::permissions::PermissionResult),
    ConversationOpened {
        id: String,
        stamp: crate::display::SourceStamp,
        position: Option<crate::display::ReadPosition>,
    },
    HistoryOpened {
        source: crate::display::ViewSource,
        stamp: crate::display::SourceStamp,
        position: Option<crate::display::ReadPosition>,
    },
    HistoryInvalidated {
        source: crate::display::ViewSource,
        stamp: crate::display::SourceStamp,
    },
    HistoryWindow(crate::display::DisplayWindow),
    HistoryTurns(crate::display::turn_navigation::TurnPage),
    HistoryBody(crate::display::BodyPage),
    HistoryFooter {
        request_id: String,
        page: crate::display::FooterPage,
    },
    HistoryImage(Arc<crate::ui::history::ImagePixels>),
    HistoryRequestFailed {
        source: crate::display::ViewSource,
        request_id: String,
        error: crate::display::DisplayError,
        current_stamp: Option<crate::display::SourceStamp>,
    },
    HistoryEditFinished {
        body: crate::display::BodyRef,
        result: Result<(), String>,
    },
    HistoryCopied {
        body: crate::display::BodyRef,
        text: String,
    },
    ChildSummaries {
        conversation_id: String,
        records: Vec<crate::display::ChildSummary>,
    },
    ChildSummarySaved {
        conversation_id: String,
        record: crate::display::ChildSummary,
    },
    OpenTerminal,
    CompactionBusy {
        key: crate::runtime_host::RunKey,
        busy: bool,
    },
    ContextReading {
        key: crate::runtime_host::RunKey,
        reading: crate::session_tools::ContextReading,
    },
    ConversationContextReading {
        source: crate::display::ViewSource,
        stamp: crate::display::SourceStamp,
        reading: Option<crate::session_tools::ContextReading>,
    },
    Schedules(Vec<crate::schedules::Schedule>),
    ScheduleStatus(crate::store::ScheduleStatus),
    LegacyPreview(Result<crate::migration::LegacyPreview, String>),
    LegacyImported(Result<LegacyImportReport, String>),
    Todos {
        conversation_id: Option<String>,
        child_id: Option<String>,
        board: crate::todos::Board,
    },
    TodoMutationFinished {
        request_id: String,
        result: Result<(), String>,
    },
    ScheduleMutationFinished {
        request_id: String,
        result: Result<(), String>,
    },
    ChatMutationFinished {
        request_id: String,
        result: Result<(), String>,
    },
    Loaded {
        settings: Settings,
        conversations: Vec<crate::display::ConversationSummary>,
        memories: Vec<Memory>,
        workspace: PathBuf,
        capabilities: Capabilities,
        first_run_legacy_data: Result<bool, String>,
    },
    Conversations(Vec<crate::display::ConversationSummary>),
    ConversationDeleted(String),
    Usage(Vec<UsageRecord>),
    Conversation {
        id: String,
        messages: Vec<Message>,
    },
    MessageAdded {
        conversation_id: String,
        message: Message,
    },
    TextDelta {
        conversation_id: String,
        message_id: String,
        text: String,
    },
    ThinkingDelta {
        conversation_id: String,
        message_id: String,
        text: String,
    },
    RunEnded {
        key: crate::runtime_host::RunKey,
        error: Option<String>,
    },
    RunStarted {
        key: crate::runtime_host::RunKey,
    },
    RunStartRejected {
        conversation_id: Option<String>,
        error: String,
    },
    SteeringWithdrawn {
        scope: crate::runtime_host::RunKey,
        ids: Vec<String>,
    },
    RunSteeringMode {
        key: crate::runtime_host::RunKey,
        immediately: bool,
    },
    QueuedTurns {
        conversation_id: String,
        turns: Vec<QueuedTurn>,
    },
    QueuedCommandFinished {
        conversation_id: String,
        id: String,
        result: Result<(), String>,
    },
    QueueWrite {
        id: String,
        result: Result<(), String>,
    },
    Children {
        conversation_id: String,
        records: Vec<crate::subagents::Record>,
    },
    ChildSaved {
        conversation_id: String,
        record: crate::subagents::Record,
    },
    ChildConfigPreview {
        run_id: String,
        request_id: String,
        result: Result<crate::subagent_configuration::ChildRunConfig, String>,
    },
    ChildLaunchResult {
        run_id: String,
        request_id: String,
        result: Result<String, String>,
    },
    ToolCatalog(Vec<suixing_search::rust_protocol::ToolDefinition>),
    ChildDelta {
        conversation_id: String,
        child_id: String,
        message_id: String,
        thinking: bool,
        text: String,
    },
    MessagesUpdated {
        conversation_id: String,
        messages: Vec<Message>,
    },
    ApprovalRequested(ApprovalRequest),
    ApprovalCleared {
        run_id: String,
        approval_id: String,
    },
    Memories(Vec<Memory>),
    MemorySaved(Memory),
    SettingsSaved(Settings),
    SettingsSnapshot(SettingsSnapshot),
    SettingsMutationFinished {
        request_id: String,
        expected: SettingsStamp,
        result: Result<SettingsSnapshot, String>,
    },
    Goal(crate::goal::Goal),
    ModelsFetched {
        request_id: String,
        provider_id: Option<String>,
        fingerprint: String,
        result: Result<Vec<String>, String>,
    },
    Skills(Vec<crate::skills::Skill>),
    SkillSaved(crate::skills::Skill),
    Plugins(Vec<crate::plugins::PluginEntry>),
    McpConfigs(Vec<McpConfigSummary>),
    McpSnapshot(crate::mcp::Snapshot),
    McpSaved {
        request_id: Option<String>,
        id: String,
        result: Result<(), String>,
    },
    McpDeleted {
        id: String,
        result: Result<(), String>,
    },
    McpTestFinished {
        request_id: String,
        result: Result<crate::mcp::TestReport, String>,
    },
    ConversationModelSelected {
        request_id: String,
        conversation_id: Option<String>,
        result: Result<(), String>,
    },
    PluginPreview(Result<crate::plugins::PluginManifest, String>),
    PluginInstallResult(Result<(), String>),
    PluginEnvironment {
        id: String,
        result: Result<Vec<crate::plugins::PluginEnvStatus>, String>,
    },
    PluginEnvironmentSaved {
        id: String,
        result: Result<(), String>,
    },
    PluginUninstalled {
        id: String,
        result: Result<(), String>,
    },
    Files {
        directory: PathBuf,
        entries: Vec<FileEntry>,
    },
    FilePreview {
        path: PathBuf,
        text: String,
        truncated: bool,
    },
    TerminalOutput(String),
    TerminalFrame(Arc<crate::vt::Frame>),
    TerminalResizeRejected {
        session_id: u64,
        geometry_version: u64,
        error: String,
    },
    TerminalState {
        session_id: u64,
        revision: u64,
        running: bool,
        status: String,
    },
    Error(String),
    Info(String),
    Capabilities(Capabilities),
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Page {
    #[default]
    Chat,
    Conversations,
    Memory,
    Files,
    Terminal,
    Settings,
    Usage,
    Skills,
    Plugins,
    Mcp,
    Assistants,
    Automations,
    Schedules,
    Jobs,
}

impl Page {
    pub const ALL: [Self; 14] = [
        Self::Chat,
        Self::Conversations,
        Self::Memory,
        Self::Files,
        Self::Terminal,
        Self::Usage,
        Self::Skills,
        Self::Plugins,
        Self::Mcp,
        Self::Assistants,
        Self::Automations,
        Self::Schedules,
        Self::Jobs,
        Self::Settings,
    ];

    pub const fn label(self) -> &'static str {
        match self {
            Self::Chat => "聊天",
            Self::Conversations => "会话管理",
            Self::Memory => "记忆",
            Self::Files => "文件",
            Self::Terminal => "终端",
            Self::Settings => "设置",
            Self::Usage => "用量",
            Self::Skills => "技能",
            Self::Plugins => "插件",
            Self::Mcp => "MCP",
            Self::Assistants => "助手",
            Self::Automations => "UI自动化",
            Self::Schedules => "定时提醒",
            Self::Jobs => "后台作业",
        }
    }
}

pub struct FilePreview {
    pub path: PathBuf,
    pub text: String,
    pub truncated: bool,
}

pub struct Notice {
    pub text: String,
    pub error: bool,
    // Repeated identical text still creates a new notice with a fresh clock.
    created_at: Instant,
}

impl Notice {
    fn remaining(&self, now: Instant, seconds: i32, pinned: bool) -> Option<Duration> {
        let seconds = match seconds {
            0 => 0,
            5 | 10 | 30 => seconds as u64,
            _ => 10,
        };
        if pinned || seconds == 0 {
            return None;
        }
        Some(
            Duration::from_secs(seconds)
                .saturating_sub(now.saturating_duration_since(self.created_at)),
        )
    }
}

#[derive(Clone, Debug)]
pub struct LegacyImportReport {
    pub transaction_id: String,
    pub conversations: usize,
    pub source_fingerprint: String,
    pub durability_confirmed: bool,
    pub source_stable_after_commit: bool,
    pub warnings: Vec<String>,
    pub retained_features: Vec<String>,
}

/// Only the UI thread owns this snapshot. Background workers communicate through `Event`.
#[derive(Clone, Debug)]
pub struct RunView {
    pub key: crate::runtime_host::RunKey,
    pub immediately: bool,
    pub cancelling: bool,
    pub compressing: bool,
}

pub struct ViewState {
    pub page: Page,
    pub loaded: bool,
    pub settings: Settings,
    pub settings_stamp: Option<SettingsStamp>,
    pub settings_request: Option<(String, SettingsStamp)>,
    pub capabilities: Capabilities,
    pub conversations: Vec<crate::display::ConversationSummary>,
    pub conversation_preferences: crate::conversation_preferences::Snapshot,
    pub selected_conversation: Option<String>,
    pub conversation_pending: bool,
    pub conversation_delete_pending: Option<String>,
    pub messages: HashMap<String, Vec<Message>>,
    pub queued_turns: HashMap<String, Vec<QueuedTurn>>,
    pub children: HashMap<String, Vec<crate::display::ChildSummary>>,
    pub child_launch_editor: Option<crate::subagent_configuration::ChildLaunchDraft>,
    pub active_run_id: Option<String>,
    pub runs: HashMap<String, RunView>,
    pub approvals: std::collections::BTreeMap<String, ApprovalRequest>,
    pub run_pending: bool,
    pub pending_run_conversation: Option<String>,
    pub tool_catalog: Vec<suixing_search::rust_protocol::ToolDefinition>,
    pub subagent_profile_editor: Option<crate::subagent_configuration::SubagentProfileEditor>,
    pub subagent_profile_delete: Option<String>,
    pub memories: Vec<Memory>,
    pub todos: crate::todos::Board,
    pub todo_boards: HashMap<String, crate::todos::Board>,
    pub skills: Vec<crate::skills::Skill>,
    pub skills_pending: bool,
    pub plugins: Vec<crate::plugins::PluginEntry>,
    pub plugins_loaded: bool,
    pub mcp_configs: Vec<McpConfigSummary>,
    pub mcp_snapshot: crate::mcp::Snapshot,
    pub mcp_loaded: bool,
    pub mcp_pending: bool,
    pub plugins_pending: bool,
    pub goal: crate::goal::Goal,
    pub usage: Vec<UsageRecord>,
    pub usage_loaded: bool,
    pub usage_pending: bool,
    pub usage_summary: UsageSummary,
    pub workspace: PathBuf,
    pub directory: PathBuf,
    pub files: Vec<FileEntry>,
    pub preview: Option<FilePreview>,
    pub running: bool,
    pub running_conversation: Option<String>,
    pub steer_immediately: bool,
    pub cancelling: bool,
    pub approval: Option<ApprovalRequest>,
    pub settings_pending: bool,
    pub memory_pending: bool,
    pub files_pending: bool,
    pub terminal_pending: bool,
    pub terminal_running: bool,
    pub terminal_session_id: u64,
    pub terminal_revision: u64,
    pub terminal_frame: Option<Arc<crate::vt::Frame>>,
    pub terminal_status: String,
    pub terminal_output: String,
    pub terminal_trimmed: bool,
    pub notice: Option<Notice>,
}

impl ViewState {
    pub fn new(data_dir: PathBuf) -> Self {
        Self {
            page: Page::default(),
            loaded: false,
            settings: Settings::default(),
            settings_stamp: None,
            settings_request: None,
            capabilities: Capabilities::default(),
            conversations: Vec::new(),
            conversation_preferences: Default::default(),
            selected_conversation: None,
            conversation_pending: false,
            conversation_delete_pending: None,
            messages: HashMap::new(),
            queued_turns: HashMap::new(),
            children: HashMap::new(),
            child_launch_editor: None,
            active_run_id: None,
            runs: HashMap::new(),
            approvals: Default::default(),
            run_pending: false,
            pending_run_conversation: None,
            tool_catalog: Vec::new(),
            subagent_profile_editor: None,
            subagent_profile_delete: None,
            memories: Vec::new(),
            todos: Default::default(),
            todo_boards: HashMap::new(),
            skills: Vec::new(),
            skills_pending: false,
            plugins: Vec::new(),
            plugins_loaded: false,
            mcp_configs: Vec::new(),
            mcp_snapshot: Default::default(),
            mcp_loaded: false,
            mcp_pending: false,
            plugins_pending: false,
            goal: crate::goal::Goal::default(),
            usage: Vec::new(),
            usage_loaded: false,
            usage_pending: false,
            usage_summary: UsageSummary::default(),
            workspace: data_dir.clone(),
            directory: data_dir,
            files: Vec::new(),
            preview: None,
            running: false,
            running_conversation: None,
            steer_immediately: false,
            cancelling: false,
            approval: None,
            settings_pending: false,
            memory_pending: false,
            files_pending: false,
            terminal_pending: false,
            terminal_running: false,
            terminal_session_id: 0,
            terminal_revision: 0,
            terminal_frame: None,
            terminal_status: String::new(),
            terminal_output: String::new(),
            terminal_trimmed: false,
            notice: None,
        }
    }

    pub fn info(&mut self, text: impl Into<String>) {
        self.notice = Some(Notice {
            text: text.into(),
            error: false,
            created_at: Instant::now(),
        });
    }

    pub fn error(&mut self, text: impl Into<String>) {
        self.notice = Some(Notice {
            text: text.into(),
            error: true,
            created_at: Instant::now(),
        });
    }

    /// Advance only the current transient banner. Approvals, transcript receipts and
    /// page-owned errors keep their own lifetimes. A load failure keeps its retry entry.
    pub(crate) fn notice_repaint_after(&mut self, now: Instant) -> Option<Duration> {
        let notice = self.notice.as_ref()?;
        let pinned = notice.error && !self.loaded;
        let remaining = notice.remaining(now, self.settings.behavior.banner_seconds, pinned);
        if remaining == Some(Duration::ZERO) {
            self.notice = None;
            None
        } else {
            remaining
        }
    }

    pub fn apply(&mut self, event: Event) {
        match event {
            Event::OpenTerminal => self.page = Page::Terminal,
            Event::OpenJobs => self.page = Page::Jobs,
            Event::CompactionBusy { key, busy } => {
                if let Some(view) = self
                    .runs
                    .get_mut(&key.conversation_id)
                    .filter(|view| view.key == key)
                {
                    view.compressing = busy;
                }
            }
            Event::Avatar(_)
            | Event::ConversationInfo(_)
            | Event::Schedules(_)
            | Event::Jobs(_)
            | Event::JobOutput { .. }
            | Event::OpenConversation(_)
            | Event::SkillImport(_)
            | Event::PluginAssets(_)
            | Event::FileTransfer(_)
            | Event::Composer(_)
            | Event::Terminal(_)
            | Event::Automation(_)
            | Event::LegacyArchive(_)
            | Event::Profile(_)
            | Event::ProfileSummary(_)
            | Event::ContextReading { .. }
            | Event::ConversationContextReading { .. }
            | Event::UsageStatistics(_)
            | Event::Performance(_)
            | Event::Capability(_)
            | Event::Linux(_)
            | Event::PlatformPermission(_)
            | Event::HistoryOpened { .. }
            | Event::HistoryInvalidated { .. }
            | Event::HistoryWindow(_)
            | Event::HistoryTurns(_)
            | Event::HistoryBody(_)
            | Event::HistoryFooter { .. }
            | Event::HistoryImage(_)
            | Event::HistoryRequestFailed { .. }
            | Event::HistoryEditFinished { .. }
            | Event::HistoryCopied { .. }
            | Event::ScheduleStatus(_)
            | Event::ScheduleMutationFinished { .. }
            | Event::LegacyPreview(_) => {}
            Event::ConversationOpened { id, .. } => {
                self.selected_conversation = Some(id);
                self.page = Page::Chat;
                self.conversation_pending = false;
            }
            Event::LegacyImported(_) => {}
            Event::TodoMutationFinished { result, .. } => {
                if let Err(error) = result {
                    self.error(error);
                }
            }
            Event::Todos {
                conversation_id,
                child_id,
                board,
            } => {
                if board.revision >= self.todos.revision {
                    self.todos = board.clone();
                }
                if let Some(conversation_id) = conversation_id {
                    let key = child_id.map_or(conversation_id.clone(), |child| {
                        format!("{conversation_id}\0{child}")
                    });
                    if self
                        .todo_boards
                        .get(&key)
                        .is_none_or(|old| board.revision >= old.revision)
                    {
                        self.todo_boards.insert(key, board);
                    }
                }
            }
            Event::ChatMutationFinished { result, .. } => {
                if let Err(error) = result {
                    self.error(error);
                }
            }
            Event::Loaded {
                settings,
                conversations,
                memories,
                workspace,
                capabilities,
                ..
            } => {
                self.settings = settings;
                self.settings_stamp = None;
                self.settings_request = None;
                if self.settings_request.is_none() {
                    self.settings_pending = false;
                }
                self.conversations = conversations;
                self.memories = memories;
                self.directory = workspace.clone();
                self.workspace = workspace;
                self.capabilities = capabilities;
                self.loaded = true;
            }
            Event::Conversations(conversations) => self.conversations = conversations,
            Event::ConversationPreferences(snapshot) => self.conversation_preferences = snapshot,
            Event::ConversationOrderFinished { .. } => {}
            Event::ConversationDeleted(id) => {
                self.conversations
                    .retain(|conversation| conversation.id != id);
                self.messages.remove(&id);
                self.queued_turns.remove(&id);
                self.children.remove(&id);
                if self.selected_conversation.as_ref() == Some(&id) {
                    self.selected_conversation = None;
                }
                if self.conversation_delete_pending.as_ref() == Some(&id) {
                    self.conversation_delete_pending = None;
                }
                // Usage is intentionally independent of the conversation's lifetime.
                self.info("会话已删除，已有用量记录仍然保留");
            }
            Event::Usage(mut records) => {
                records.sort_by(|left, right| {
                    right.at.cmp(&left.at).then_with(|| left.id.cmp(&right.id))
                });
                self.usage_summary = UsageSummary::from_records(&records);
                self.usage = records;
                self.usage_loaded = true;
                self.usage_pending = false;
            }
            Event::Conversation { id, messages } => {
                self.messages.insert(id.clone(), messages);
                self.selected_conversation = Some(id);
                self.conversation_pending = false;
            }
            Event::MessageAdded {
                conversation_id,
                message,
            } => {
                if self.selected_conversation.is_none() {
                    self.selected_conversation = Some(conversation_id.clone());
                }
                let messages = self.messages.entry(conversation_id).or_default();
                if let Some(previous) = messages.iter_mut().find(|item| item.id == message.id) {
                    *previous = message;
                } else {
                    messages.push(message);
                }
            }
            Event::TextDelta {
                conversation_id,
                message_id,
                text,
            } => self.delta(conversation_id, message_id, text, false),
            Event::ThinkingDelta {
                conversation_id,
                message_id,
                text,
            } => self.delta(conversation_id, message_id, text, true),
            Event::RunSteeringMode { key, immediately } => {
                let view = self
                    .runs
                    .entry(key.conversation_id.clone())
                    .or_insert_with(|| RunView {
                        key: key.clone(),
                        immediately,
                        cancelling: false,
                        compressing: false,
                    });
                if view.key == key {
                    view.immediately = immediately;
                }
            }
            Event::RunEnded { key, error } => {
                if self
                    .runs
                    .get(&key.conversation_id)
                    .is_some_and(|run| run.key == key)
                {
                    self.runs.remove(&key.conversation_id);
                }
                self.approvals.remove(&key.run_id);
                if let Some(editor) = &mut self.child_launch_editor {
                    if editor.run_id == key.run_id {
                        editor.pending = false;
                        editor.preview = None;
                        editor.error = "父任务已结束；草稿已保留，可在新运行中继续创建".into();
                    }
                }
                if let Some(error) = error {
                    self.error(error);
                }
            }
            Event::RunStarted { key } => {
                if self
                    .runs
                    .get(&key.conversation_id)
                    .is_none_or(|previous| previous.key != key)
                {
                    self.todo_boards.remove(&key.conversation_id);
                }
                if self.run_pending
                    && (self.pending_run_conversation.is_none()
                        || self.pending_run_conversation.as_ref() == Some(&key.conversation_id))
                {
                    self.run_pending = false;
                    self.pending_run_conversation = None;
                }
                self.runs
                    .entry(key.conversation_id.clone())
                    .or_insert_with(|| RunView {
                        key,
                        immediately: false,
                        cancelling: false,
                        compressing: false,
                    });
            }
            Event::RunStartRejected {
                conversation_id,
                error,
            } => {
                if self.run_pending && self.pending_run_conversation == conversation_id {
                    self.run_pending = false;
                    self.pending_run_conversation = None;
                }
                self.error(error);
            }
            Event::MessagesUpdated {
                conversation_id,
                messages,
            } => {
                self.messages.insert(conversation_id, messages);
            }
            Event::QueuedTurns {
                conversation_id,
                turns,
            } => {
                if turns.is_empty() {
                    self.queued_turns.remove(&conversation_id);
                } else {
                    self.queued_turns.insert(conversation_id, turns);
                }
            }
            Event::QueueWrite {
                result: Err(error), ..
            } => self.error(error),
            Event::QueueWrite { result: Ok(()), .. } => {}
            Event::QueuedCommandFinished {
                result: Err(error), ..
            } => self.error(error),
            Event::QueuedCommandFinished { result: Ok(()), .. } => {}
            Event::ChildSummaries {
                conversation_id,
                records,
            } => {
                self.children.insert(conversation_id, records);
            }
            Event::ChildSummarySaved {
                conversation_id,
                record,
            } => {
                let children = self.children.entry(conversation_id).or_default();
                if let Some(previous) = children.iter_mut().find(|child| child.id() == record.id())
                {
                    *previous = record;
                } else {
                    children.insert(0, record);
                    children.truncate(32);
                }
            }
            Event::Children { .. } | Event::ChildSaved { .. } => {}
            Event::ChildConfigPreview {
                run_id,
                request_id,
                result,
            } => {
                if let Some(editor) = self
                    .child_launch_editor
                    .as_mut()
                    .filter(|editor| editor.run_id == run_id && editor.request_id == request_id)
                {
                    editor.pending = false;
                    match result {
                        Ok(config) => {
                            editor.preview = Some(config);
                            editor.error.clear();
                        }
                        Err(error) => {
                            editor.preview = None;
                            editor.error = error;
                        }
                    }
                }
            }
            Event::SteeringWithdrawn { .. } => {}
            Event::ToolCatalog(tools) => self.tool_catalog = tools,
            Event::ChildLaunchResult {
                run_id,
                request_id,
                result,
            } => {
                if self.child_launch_editor.as_ref().is_some_and(|editor| {
                    editor.run_id == run_id && editor.request_id == request_id
                }) {
                    match result {
                        Ok(_) => {
                            self.child_launch_editor = None;
                            self.info("子智能体已创建");
                        }
                        Err(error) => {
                            if let Some(editor) = &mut self.child_launch_editor {
                                editor.pending = false;
                                editor.error = error;
                            }
                        }
                    }
                }
            }
            Event::ChildDelta { .. } => {}
            Event::ApprovalRequested(request) => {
                self.approvals.insert(request.run_id.clone(), request);
            }
            Event::ApprovalCleared {
                run_id,
                approval_id,
            } => {
                if self
                    .approvals
                    .get(&run_id)
                    .is_some_and(|request| request.id == approval_id)
                {
                    self.approvals.remove(&run_id);
                }
            }
            Event::Memories(memories) => {
                self.memories = memories;
                self.memory_pending = false;
            }
            Event::MemorySaved(memory) => {
                if let Some(previous) = self.memories.iter_mut().find(|item| item.id == memory.id) {
                    *previous = memory;
                } else {
                    self.memories.insert(0, memory);
                }
                self.memory_pending = false;
                self.info("记忆已保存");
            }
            Event::SettingsSnapshot(snapshot) => {
                if self.settings_stamp.as_ref().is_none_or(|stamp| {
                    stamp.generation == snapshot.stamp.generation
                        && stamp.revision <= snapshot.stamp.revision
                }) {
                    self.settings = snapshot.settings;
                    self.settings_stamp = Some(snapshot.stamp);
                }
            }
            Event::SettingsMutationFinished {
                request_id,
                expected,
                result,
            } => {
                if self.settings_request.as_ref() == Some(&(request_id, expected)) {
                    self.settings_request = None;
                    if self.settings_request.is_none() {
                        self.settings_pending = false;
                    }
                    match result {
                        Ok(snapshot) => {
                            self.settings = snapshot.settings;
                            self.settings_stamp = Some(snapshot.stamp);
                            self.info("配置已保存");
                        }
                        Err(error) => self.error(error),
                    }
                }
            }
            Event::SettingsSaved(settings) => {
                self.settings = settings;
                if self.settings_request.is_none() {
                    self.settings_pending = false;
                }
                self.info("配置已保存");
            }
            Event::Goal(goal) => self.goal = goal,
            Event::ModelsFetched { .. } => {}
            Event::Skills(skills) => {
                self.skills = skills;
                self.skills_pending = false;
            }
            Event::SkillSaved(skill) => {
                if let Some(previous) = self.skills.iter_mut().find(|item| item.id == skill.id) {
                    *previous = skill;
                } else {
                    self.skills.push(skill);
                }
                self.skills_pending = false;
                self.info("技能已保存");
            }
            Event::Plugins(plugins) => {
                self.plugins = plugins;
                self.plugins_loaded = true;
                self.plugins_pending = false;
            }
            Event::McpConfigs(configs) => {
                self.mcp_configs = configs;
                self.mcp_loaded = true;
                self.mcp_pending = false;
            }
            Event::McpSnapshot(snapshot) => {
                if snapshot.revision >= self.mcp_snapshot.revision {
                    self.mcp_snapshot = snapshot;
                }
                self.mcp_pending = false;
            }
            Event::McpSaved { result, .. } | Event::McpDeleted { result, .. } => {
                self.mcp_pending = false;
                if let Err(error) = result {
                    self.error(error);
                }
            }
            Event::McpTestFinished { result, .. } => {
                if let Err(error) = result {
                    self.error(error);
                }
            }
            Event::ConversationModelSelected { result, .. } => {
                if let Err(error) = result {
                    self.error(error);
                }
            }
            Event::PluginPreview(_) | Event::PluginInstallResult(_) => {}
            Event::PluginEnvironment { .. }
            | Event::PluginEnvironmentSaved { .. }
            | Event::PluginUninstalled { .. } => {}
            Event::Files { directory, entries } => {
                self.directory = directory;
                self.files = entries;
                self.files_pending = false;
            }
            Event::FilePreview {
                path,
                text,
                truncated,
            } => {
                self.preview = Some(FilePreview {
                    path,
                    text,
                    truncated,
                });
                self.files_pending = false;
            }
            Event::TerminalOutput(text) => {
                self.terminal_output.push_str(&text);
                const MAX_OUTPUT_BYTES: usize = 256 * 1024;
                if self.terminal_output.len() > MAX_OUTPUT_BYTES {
                    let mut start = self.terminal_output.len() - MAX_OUTPUT_BYTES;
                    while !self.terminal_output.is_char_boundary(start) {
                        start += 1;
                    }
                    self.terminal_output.drain(..start);
                    self.terminal_trimmed = true;
                }
            }
            Event::TerminalResizeRejected { error, .. } => self.error(error),
            Event::TerminalFrame(frame) => {
                if frame.session_id >= self.terminal_session_id
                    && self.terminal_frame.as_ref().is_none_or(|old| {
                        frame.session_id > old.session_id
                            || (frame.session_id == old.session_id
                                && frame.geometry.version >= old.geometry.version
                                && frame.revision >= old.revision)
                    })
                {
                    self.terminal_session_id = frame.session_id;
                    self.terminal_frame = Some(frame);
                }
            }
            Event::TerminalState {
                session_id,
                revision,
                running,
                status,
            } => {
                if session_id < self.terminal_session_id || revision < self.terminal_revision {
                    return;
                }
                if session_id > self.terminal_session_id {
                    self.terminal_frame = None;
                }
                self.terminal_session_id = session_id;
                self.terminal_revision = revision;
                self.terminal_running = running;
                self.terminal_status = status;
                self.terminal_pending = false;
            }
            Event::Error(error) => {
                self.conversation_pending = false;
                self.conversation_delete_pending = None;
                self.usage_pending = false;
                if self.settings_request.is_none() {
                    self.settings_pending = false;
                }
                self.memory_pending = false;
                self.skills_pending = false;
                self.plugins_pending = false;
                self.files_pending = false;
                self.terminal_pending = false;
                self.error(error);
            }
            Event::Info(info) => self.info(info),
            Event::Capabilities(capabilities) => self.capabilities = capabilities,
        }
        self.sync_current_run();
    }

    pub fn sync_current_run(&mut self) {
        let current = self
            .selected_conversation
            .as_ref()
            .and_then(|id| self.runs.get(id));
        let pending_here = self.run_pending
            && (self.pending_run_conversation.is_none()
                || self.pending_run_conversation == self.selected_conversation);
        self.running = current.is_some() || pending_here;
        self.running_conversation = if self.running {
            self.selected_conversation.clone()
        } else {
            None
        };
        self.active_run_id = current.map(|run| run.key.run_id.clone());
        self.steer_immediately = current.is_some_and(|run| run.immediately);
        self.cancelling = current.is_some_and(|run| run.cancelling);
        self.approval = self
            .active_run_id
            .as_ref()
            .and_then(|id| self.approvals.get(id))
            .or_else(|| self.approvals.values().next())
            .cloned();
    }

    pub fn current_run_key(&self) -> Option<crate::runtime_host::RunKey> {
        self.selected_conversation
            .as_ref()
            .and_then(|id| self.runs.get(id))
            .map(|run| run.key.clone())
    }

    pub fn mark_cancelling(&mut self, key: &crate::runtime_host::RunKey) {
        if let Some(view) = self
            .runs
            .get_mut(&key.conversation_id)
            .filter(|run| run.key == *key)
        {
            view.cancelling = true;
        }
        self.sync_current_run();
    }

    fn delta(&mut self, conversation_id: String, message_id: String, text: String, thinking: bool) {
        let messages = self.messages.entry(conversation_id).or_default();
        let index = match messages.iter().position(|item| item.id == message_id) {
            Some(index) => index,
            None => {
                messages.push(Message {
                    id: message_id,
                    role: "assistant".to_owned(),
                    text: String::new(),
                    thinking: String::new(),
                    complete: false,
                    tool_calls: Vec::new(),
                    tool_call_id: None,
                    metadata: Default::default(),
                });
                messages.len() - 1
            }
        };
        if thinking {
            messages[index].thinking.push_str(&text);
        } else {
            messages[index].text.push_str(&text);
        }
    }
}
