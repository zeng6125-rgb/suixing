//! Native Rust views. No filesystem, network or process work is performed while drawing.
mod automation;
mod avatars;
mod composer_platform;
mod configuration;
mod context_readout;
mod conversation_manager;
mod conversation_preferences;
mod drawer;
mod file_transfers;
pub(crate) mod history;
mod history_runtime;
mod history_view;
mod jobs;
mod legacy_archive;
mod legacy_usage;
mod linux;
mod markdown;
mod mcp;
mod mobile_chat;
mod notice;
mod plugins;
mod schedules;
mod settings;
mod settings_leave;
mod skills;
mod subagent_configuration;
mod terminal;
mod terminal_workspace;
mod todos;
mod turn_navigation;
mod typewriter;

use std::{path::PathBuf, sync::mpsc::TryRecvError, time::Duration};

use eframe::egui::{self, Color32, RichText, Stroke, TextEdit};

use self::composer_platform::{ComposerIntent, ComposerUi};
use crate::composer::{ComposerCommand, ComposerEvent, SubmitMode};
use crate::platform::permissions::PermissionKind;
use crate::state::{
    Action, Event, Memory, Page, Protocol, QueuedTurn, RuntimeBridge, Settings, SteerInput,
    TokenUsage, ViewState,
};

const ROW_HEIGHT: f32 = 44.0;
const EVENT_BUDGET: usize = 128;

pub struct RustApplication {
    state: ViewState,
    #[cfg(target_os = "android")]
    window_insets: Option<crate::platform::window_insets::View>,
    data_dir: PathBuf,
    runtime: Option<RuntimeBridge>,
    settings_draft: Settings,
    settings_view: settings::SettingsView,
    settings_leave: settings_leave::LeaveState,
    composer_platform: composer_platform::ComposerPlatform,
    mobile_icons: mobile_chat::Icons,
    drawer_sorting: bool,
    composer_run_pending: Option<crate::platform::permissions::OperationKey>,
    rewrite_pending: Option<String>,
    history_mutations: std::collections::HashSet<crate::display::ViewSource>,
    history_views: history::HistoryViews,
    history_renderer: history_view::HistoryRenderer,
    turn_navigation: turn_navigation::Navigation,
    context_readings: std::collections::HashMap<
        String,
        (
            Option<crate::runtime_host::RunKey>,
            crate::session_tools::ContextReading,
        ),
    >,
    job_view: jobs::JobView,
    archive_view: legacy_archive::ArchiveView,
    file_transfers: file_transfers::Transfers,
    permission_pending: std::collections::BTreeMap<
        crate::platform::permissions::PermissionKind,
        crate::platform::permissions::OperationKey,
    >,
    permission_status:
        std::collections::BTreeMap<crate::platform::permissions::PermissionKind, String>,
    schedules: Vec<crate::schedules::Schedule>,
    schedule_status: crate::store::ScheduleStatus,
    schedule_editor: Option<schedules::Editor>,
    schedule_pending: Option<String>,
    legacy_preview: Option<Result<crate::migration::LegacyPreview, String>>,
    legacy_pending: bool,
    legacy_confirm: Option<String>,
    legacy_import_report: Option<Result<crate::state::LegacyImportReport, String>>,
    drawer_open: bool,
    drawer_motion: drawer::DrawerMotion,
    rendered_dark_mode: Option<(bool, i32, i32, i32, Option<[Color32; 12]>, f32)>,
    memory_editor: Option<Memory>,
    memory_delete: Option<String>,
    conversation_delete: Option<String>,
    conversation_manager: conversation_manager::View,
    conversation_view_lease: conversation_preferences::Viewing,
    conversation_order_pending: Option<String>,
    avatar_view: avatars::View,
    usage_selected: Option<String>,
    usage_view: legacy_usage::UsageView,
    terminal_workspace: terminal_workspace::TerminalWorkspace,
    automation_view: automation::AutomationView,
    linux_view: linux::LinuxView,
    backend: String,
    approval_reply_pending: Option<(String, String)>,
    skill_editor: Option<crate::skills::Skill>,
    skill_delete: Option<(String, u64)>,
    skill_package: skills::PackageImport,
    skill_import_open: bool,
    skill_import_id: String,
    skill_import_document: String,
    plugin_import: Option<plugins::PluginImport>,
    package_import: plugins::PackageImport,
    bundled_assets: crate::plugin_assets::bundled::View,
    plugin_environment_editor: Option<plugins::PluginEnvironmentEditor>,
    mcp_editor: Option<mcp::McpEditor>,
    pub(super) mcp_delete: Option<String>,
    pub(super) mcp_delete_pending: bool,
    model_fetch_id: Option<String>,
    model_choice_pending: Option<String>,
    queue_write: Option<QueueWrite>,
    queue_editor: Option<(String, QueuedTurn)>,
    queue_attempt: Option<(String, String)>,
    refused_queue_head: Option<(String, String)>,
    authorized_queue: std::collections::HashSet<(String, String)>,
    child_viewer: Option<(String, String)>,
    last_opened_child: Option<crate::display::ViewSource>,
    todos_open: bool,
    todo_pending: Option<String>,
    todo_delete: Option<(String, String, u64)>,
}

enum QueueWrite {
    Edit(String),
    Change(String),
    Promote {
        conversation_id: String,
        id: String,
        expected: crate::runtime_host::RunKey,
    },
}

impl QueueWrite {
    fn id(&self) -> &str {
        match self {
            Self::Edit(id) | Self::Change(id) | Self::Promote { id, .. } => id,
        }
    }
}

enum Navigation {
    Page(Page),
    NewConversation,
    Conversation(String),
    DeleteConversation(String),
}

impl RustApplication {
    fn composer_intents(&mut self, intents: Vec<ComposerIntent>) {
        for intent in intents {
            let (operation, action, starts_run) = match intent {
                ComposerIntent::MicrophonePermission(operation) => (
                    operation.clone(),
                    Action::RequestPlatformPermission {
                        operation,
                        kinds: vec![PermissionKind::Microphone],
                    },
                    false,
                ),
                ComposerIntent::Command(command) => {
                    let operation = command.operation().clone();
                    let starts_run = matches!(&command,ComposerCommand::Submit(submission)
                        if submission.starts_run());
                    (operation, Action::Composer(command), starts_run)
                }
            };
            if self.dispatch(action) {
                if starts_run {
                    self.composer_run_pending = Some(operation);
                    self.mark_run_pending();
                }
            } else {
                self.composer_platform
                    .dispatch_failed(&operation, "运行时连接已断开；草稿保留");
            }
        }
    }
    fn composer_event(&mut self, ctx: &egui::Context, event: &ComposerEvent) {
        match event {
            ComposerEvent::Attachment {
                operation,
                result,
                cancelled,
            } => {
                self.composer_platform
                    .attachment_result(ctx, operation, result, *cancelled);
            }
            ComposerEvent::AttachmentPreview {
                operation,
                attachment_id,
                pixels,
            } => {
                self.composer_platform.preview_result(
                    ctx,
                    operation,
                    attachment_id,
                    pixels
                        .as_ref()
                        .map(|pixels| pixels.as_ref())
                        .map_err(String::as_str),
                );
            }
            ComposerEvent::RecordingStatus(status) => {
                self.composer_platform.recording_status(status)
            }
            ComposerEvent::RecordingFinished { .. }
            | ComposerEvent::TranscriptionFinished { .. } => {
                let intents = self.composer_platform.voice_result(event);
                self.composer_intents(intents);
            }
            ComposerEvent::Accepted {
                operation,
                conversation_id,
                turn_id,
            } => {
                let mode = self.composer_platform.pending_mode(operation);
                let expected = self.composer_platform.pending_expected_run(operation);
                let accepted = self.composer_platform.accepted(operation, conversation_id);
                let scope_live = expected.as_ref().is_some_and(|key| {
                    self.state
                        .runs
                        .get(&key.conversation_id)
                        .is_some_and(|run| &run.key == key && !run.cancelling)
                });
                if (matches!(mode, Some(SubmitMode::Queue))
                    || (matches!(mode, Some(SubmitMode::Steer)) && scope_live))
                    && operation.conversation_id.as_deref() == Some(conversation_id)
                    && !turn_id.is_empty()
                {
                    self.authorized_queue
                        .insert((conversation_id.clone(), turn_id.clone()));
                }
                if let Some(created) = accepted.select_created
                    && self.state.page == Page::Chat
                    && self.child_viewer.is_none()
                {
                    if self.state.selected_conversation.as_ref() == Some(&created) {
                        if let Err(error) = self.composer_platform.activate(Some(created)) {
                            self.state.error(error);
                        }
                    } else if self.dispatch(Action::SelectConversation(created.clone())) {
                        if let Err(error) = self.composer_platform.activate(Some(created)) {
                            self.state.error(error);
                        }
                        self.state.conversation_pending = true;
                    }
                    self.state.page = Page::Chat;
                }
                self.advance_queue(false);
            }
            ComposerEvent::CommandFinished { operation, result } => {
                self.composer_platform.command_finished(operation, result)
            }
            ComposerEvent::Rejected { operation, error } => {
                self.composer_platform.rejected(operation, error);
                if self.composer_run_pending.as_ref() == Some(operation) {
                    self.composer_run_pending = None;
                    self.state.run_pending = false;
                    self.state.pending_run_conversation = None;
                    self.state.sync_current_run();
                }
            }
            ComposerEvent::Cancelled { operation } => {
                self.composer_platform.cancelled(operation);
                if self.composer_run_pending.as_ref() == Some(operation) {
                    self.composer_run_pending = None;
                    self.state.run_pending = false;
                    self.state.pending_run_conversation = None;
                    self.state.sync_current_run();
                }
            }
            ComposerEvent::Speech { operation, phase } => self
                .composer_platform
                .speech_status(operation, phase.clone()),
        }
        ctx.request_repaint();
    }
    fn composer_input(
        &mut self,
        ui: &mut egui::Ui,
        configured: bool,
        can_start_goal: bool,
        skin: Option<mobile_chat::Skin>,
    ) {
        let flags = ComposerUi {
            loaded: self.state.loaded && self.state.capabilities.chat,
            connected: self.runtime.is_some(),
            visible: ui.input(|input| input.focused),
            configured,
            running: self.state.running,
            cancelling: self.state.cancelling,
            immediately: self.state.steer_immediately,
            conversation_pending: self.state.conversation_pending
                || self.model_choice_pending.is_some()
                || self.queue_write.is_some()
                || self.queue_attempt.is_some()
                || self.rewrite_pending.is_some()
                || self
                    .state
                    .conversation_delete_pending
                    .as_ref()
                    .is_some_and(|id| self.state.selected_conversation.as_ref() == Some(id)),
            can_start_goal,
            image_input: self.state.settings.behavior.image_input,
            voice_input: self.state.settings.behavior.voice_input,
            enter_key_sends: self.state.settings.behavior.enter_key_sends,
            expected_run: self.state.current_run_key(),
        };
        let icons = self.mobile_icons.clone();
        let placeholder = if self.state.running {
            "插一句话…".to_owned()
        } else {
            let id = self.current_assistant_id();
            let name = self
                .state
                .settings
                .assistants
                .iter()
                .find(|item| id.as_ref() == Some(&item.id))
                .map(|item| item.name.as_str())
                .filter(|name| !name.is_empty())
                .unwrap_or("JAgent");
            format!("让{name}做点什么…")
        };
        let chrome = skin.map(|skin| mobile_chat::InputChrome {
            icons: &icons,
            skin,
            placeholder: &placeholder,
            model: None,
            context: None,
            permissions: false,
            stop: false,
        });
        let (intents, chrome) = self.composer_platform.show(ui, &flags, chrome);
        self.composer_intents(intents);
        if let Some(chrome) = chrome {
            if let Some(bounds) = chrome.model {
                self.composer_model_selector(ui, &chrome, bounds);
            }
            if let Some(bounds) = chrome.context {
                self.composer_context_ring(ui, &chrome, bounds);
            }
            if chrome.permissions {
                self.navigate(Navigation::Page(Page::Settings), true);
                self.open_chat_permissions();
            }
            if chrome.stop && self.stop_current_run() {
                self.state.cancelling = true;
            }
        }
        // Keep the existing CancelRun button and assistant/model, goals, todos,
        // queue and child controls around this input.
    }
    pub fn new(cc: &eframe::CreationContext<'_>, data_dir: PathBuf) -> Self {
        crate::platform::install_system_fonts(&cc.egui_ctx);
        crate::platform::accessibility::install(&cc.egui_ctx);
        let backend = cc
            .wgpu_render_state
            .as_ref()
            .map(|renderer| format!("{:?}", renderer.adapter.get_info().backend))
            .unwrap_or_else(|| "不可用".to_owned());
        Self {
            state: ViewState::new(data_dir.clone()),
            #[cfg(target_os = "android")]
            window_insets: None,
            data_dir,
            runtime: None,
            settings_draft: Settings::default(),
            settings_view: Default::default(),
            settings_leave: Default::default(),
            composer_platform: Default::default(),
            mobile_icons: mobile_chat::Icons::load(&cc.egui_ctx),
            drawer_sorting: false,
            composer_run_pending: None,
            rewrite_pending: None,
            history_mutations: Default::default(),
            history_views: Default::default(),
            history_renderer: Default::default(),
            turn_navigation: Default::default(),
            context_readings: Default::default(),
            job_view: Default::default(),
            archive_view: legacy_archive::ArchiveView::default(),
            file_transfers: file_transfers::Transfers::default(),
            permission_pending: Default::default(),
            permission_status: Default::default(),
            schedules: Vec::new(),
            schedule_status: crate::store::ScheduleStatus::default(),
            schedule_editor: None,
            schedule_pending: None,
            legacy_preview: None,
            legacy_pending: false,
            legacy_confirm: None,
            legacy_import_report: None,
            drawer_open: false,
            drawer_motion: drawer::DrawerMotion::default(),
            rendered_dark_mode: None,
            memory_editor: None,
            memory_delete: None,
            conversation_delete: None,
            conversation_manager: conversation_manager::View::default(),
            conversation_view_lease: Default::default(),
            conversation_order_pending: None,
            avatar_view: avatars::View::default(),
            usage_selected: None,
            usage_view: Default::default(),
            terminal_workspace: Default::default(),
            automation_view: Default::default(),
            linux_view: Default::default(),
            backend,
            approval_reply_pending: None,
            skill_editor: None,
            skill_delete: None,
            skill_package: Default::default(),
            skill_import_open: false,
            skill_import_id: String::new(),
            skill_import_document: String::new(),
            plugin_import: None,
            package_import: Default::default(),
            bundled_assets: Default::default(),
            plugin_environment_editor: None,
            mcp_editor: None,
            mcp_delete: None,
            mcp_delete_pending: false,
            model_fetch_id: None,
            model_choice_pending: None,
            queue_write: None,
            queue_editor: None,
            queue_attempt: None,
            refused_queue_head: None,
            authorized_queue: Default::default(),
            child_viewer: None,
            last_opened_child: None,
            todos_open: false,
            todo_pending: None,
            todo_delete: None,
        }
    }

    pub fn with_runtime(mut self, runtime: RuntimeBridge) -> Self {
        self.runtime = Some(runtime);
        self.dispatch(Action::Load);
        self
    }

    #[cfg(target_os = "android")]
    pub(crate) fn with_window_insets(
        mut self,
        app: winit::platform::android::activity::AndroidApp,
        context: egui::Context,
    ) -> Self {
        self.window_insets = crate::platform::window_insets::View::new(app, context.clone());
        crate::platform::navigation::install(context);
        self
    }

    fn refresh_context_reading(&mut self) {
        if let Some(conversation_id) = self.state.selected_conversation.clone() {
            self.dispatch(Action::HistoryContextReading(
                crate::display::ViewSource::Main { conversation_id },
            ));
        }
    }

    fn dispatch(&mut self, action: Action) -> bool {
        let Some(runtime) = &self.runtime else {
            self.state.error("运行时尚未连接，操作未执行");
            return false;
        };
        if let Action::CancelRun { key } = &action {
            runtime.cancel_run(key);
            self.state.mark_cancelling(key);
        }
        if let Action::CancelChild {
            conversation_id,
            id,
        } = &action
        {
            if let Some(run) = self.state.runs.get(conversation_id) {
                runtime.cancel_child(&run.key, id);
            }
        }
        if runtime.submit(action).is_err() {
            self.state.error("运行时连接已断开，操作未执行");
            return false;
        }
        true
    }

    fn stop_current_run(&mut self) -> bool {
        self.state
            .current_run_key()
            .is_some_and(|key| self.dispatch(Action::CancelRun { key }))
    }

    fn history_opened(
        &mut self,
        source: crate::display::ViewSource,
        position: Option<crate::display::ReadPosition>,
        stamp: crate::display::SourceStamp,
    ) {
        if let Some(evicted) = self.history_views.open(source.clone(), position.clone()) {
            self.dispatch(Action::SaveReadPosition(evicted));
        }
        match self.history_views.confirm_open(&source, position, stamp) {
            Ok(Some(request)) => {
                let id = request.request_id.clone();
                if !self.dispatch(Action::HistoryWindow(request)) {
                    self.history_renderer.request_failed(
                        &mut self.history_views,
                        &source,
                        &id,
                        "运行时连接已断开",
                    );
                }
            }
            Ok(None) | Err(crate::display::DisplayError::Stale) => {}
            Err(error) => self.state.error(&error.to_string()),
        }
    }
    fn history_intents(&mut self, intents: Vec<history_view::HistoryIntent>) {
        use history_view::HistoryIntent;
        for intent in intents {
            let mut request = None;
            let action = match intent {
                HistoryIntent::Window(value) => {
                    request = Some((value.source.clone(), value.request_id.clone()));
                    Action::HistoryWindow(value)
                }
                HistoryIntent::Body(value) => {
                    request = Some((value.body.source.clone(), value.request_id.clone()));
                    Action::HistoryBody(value)
                }
                HistoryIntent::Image(value) => {
                    request = Some((value.image.source.clone(), value.request_id.clone()));
                    Action::HistoryImage(value)
                }
                HistoryIntent::Footer(value) => {
                    request = Some((value.source.clone(), value.request_id.clone()));
                    Action::HistoryFooter(value)
                }
                HistoryIntent::ReadPosition(value) => Action::SaveReadPosition(value),
                HistoryIntent::SelectCandidate {
                    source,
                    stamp,
                    user_id,
                    run_id,
                } => {
                    if self.dispatch(Action::HistorySelectCandidate {
                        source: source.clone(),
                        stamp,
                        user_id,
                        run_id,
                    }) {
                        self.history_mutations.insert(source);
                    }
                    continue;
                }
                HistoryIntent::Regenerate {
                    source,
                    stamp,
                    question_id,
                } => {
                    if self.dispatch(Action::HistoryRegenerate {
                        source: source.clone(),
                        stamp,
                        question_id,
                    }) {
                        self.rewrite_pending = Some(source.conversation_id().into());
                        self.mark_run_pending();
                    }
                    continue;
                }
                HistoryIntent::EditResend {
                    body,
                    from_byte,
                    to_byte,
                    replacement,
                    resend,
                } => {
                    if self.dispatch(Action::HistoryEdit {
                        body: body.clone(),
                        from_byte,
                        to_byte,
                        replacement,
                        resend,
                    }) {
                        self.history_mutations.insert(body.source.clone());
                        if resend {
                            self.rewrite_pending = Some(body.source.conversation_id().into());
                            self.mark_run_pending();
                        }
                    } else {
                        self.history_renderer
                            .edit_finished(&body, Err("运行时连接已断开，操作未确认".into()));
                    }
                    continue;
                }
                HistoryIntent::CopyBody(body) => Action::HistoryCopy(body),
                HistoryIntent::SpeakReply(body) => {
                    match self
                        .composer_platform
                        .speak_reply(body, self.state.settings.speak_replies)
                    {
                        Ok(intent) => self.composer_intents(vec![intent]),
                        Err(error) => self.state.error(error),
                    }
                    continue;
                }
                HistoryIntent::TransferFile { source, path, kind } => {
                    self.begin_file_transfer(source, path, kind);
                    continue;
                }
                HistoryIntent::OpenFile { source, path } => {
                    Action::HistoryOpenFile { source, path }
                }
            };
            if !self.dispatch(action) {
                if let Some((source, id)) = request {
                    self.history_renderer.request_failed(
                        &mut self.history_views,
                        &source,
                        &id,
                        "运行时连接已断开",
                    );
                }
            }
        }
    }

    fn poll_events(&mut self, context: &egui::Context) {
        for _ in 0..EVENT_BUDGET {
            let Some(result) = self
                .runtime
                .as_ref()
                .map(|runtime| runtime.event_rx.try_recv())
            else {
                return;
            };
            match result {
                Ok(event) => {
                    let event = match event {
                        Event::SteeringWithdrawn { scope, ids } => {
                            if self
                                .state
                                .runs
                                .get(&scope.conversation_id)
                                .is_none_or(|run| run.key == scope)
                            {
                                for id in ids {
                                    self.authorized_queue
                                        .remove(&(scope.conversation_id.clone(), id));
                                }
                            }
                            context.request_repaint();
                            continue;
                        }
                        Event::FileTransfer(event) => {
                            self.file_transfers.apply(event);
                            context.request_repaint();
                            continue;
                        }
                        Event::Capability(update) => {
                            self.settings_capability_updated(update);
                            context.request_repaint();
                            continue;
                        }
                        Event::UsageStatistics(update) => {
                            self.usage_view.apply(&update);
                            context.request_repaint();
                            continue;
                        }
                        Event::ProfileSummary(update) => {
                            self.settings_profile_summary_updated(update);
                            context.request_repaint();
                            continue;
                        }
                        Event::Profile(update) => {
                            self.settings_profile_updated(update);
                            context.request_repaint();
                            continue;
                        }
                        Event::Performance(update) => {
                            self.settings_performance_updated(update);
                            context.request_repaint();
                            continue;
                        }
                        Event::Linux(update) => {
                            self.linux_view.apply(update);
                            context.request_repaint();
                            continue;
                        }
                        Event::Automation(update) => {
                            if let Err(error) = self.automation_view.apply_update(update, context) {
                                self.state.error(error);
                            }
                            context.request_repaint();
                            continue;
                        }
                        Event::Terminal(update) => {
                            self.terminal_workspace.apply(update);
                            context.request_repaint();
                            continue;
                        }
                        Event::OpenConversation(id) => {
                            self.navigate(Navigation::Conversation(id), false);
                            context.request_repaint();
                            continue;
                        }
                        Event::Composer(event) => {
                            self.settings_audio_event(&event);
                            self.composer_event(context, &event);
                            continue;
                        }
                        Event::ConversationOpened {
                            id,
                            stamp,
                            position,
                        } => {
                            self.history_opened(
                                crate::display::ViewSource::Main {
                                    conversation_id: id.clone(),
                                },
                                position.clone(),
                                stamp.clone(),
                            );
                            // A background run also publishes its latest stamp.
                            // Only the explicit selected target may change the page.
                            // New-chat selection follows exact Composer::Accepted.
                            if !self.composer_platform.belongs_to(&Some(id.clone())) {
                                context.request_repaint();
                                continue;
                            }
                            if let Err(error) = self.composer_platform.conversation_opened(&id) {
                                self.state.error(error);
                            }
                            Event::ConversationOpened {
                                id,
                                stamp,
                                position,
                            }
                        }
                        Event::HistoryOpened {
                            source,
                            position,
                            stamp,
                        } => {
                            self.history_opened(source, position, stamp);
                            context.request_repaint();
                            continue;
                        }
                        Event::HistoryInvalidated { source, stamp } => {
                            self.history_mutations.remove(&source);
                            self.history_opened(source, None, stamp);
                            context.request_repaint();
                            continue;
                        }
                        Event::HistoryWindow(page) => {
                            self.apply_history_window(page, context);
                            continue;
                        }
                        Event::HistoryBody(page) => {
                            let source = page.body.source.clone();
                            let id = page.request_id.clone();
                            if let Err(error) =
                                self.history_renderer.on_body(&mut self.history_views, page)
                            {
                                if error != crate::display::DisplayError::Stale {
                                    self.history_renderer.request_failed(
                                        &mut self.history_views,
                                        &source,
                                        &id,
                                        &error.to_string(),
                                    );
                                }
                            }
                            context.request_repaint();
                            continue;
                        }
                        Event::HistoryFooter { request_id, page } => {
                            let source = page.source.clone();
                            if let Err(error) = self.history_renderer.on_footer(
                                &mut self.history_views,
                                &request_id,
                                page,
                            ) {
                                if error != crate::display::DisplayError::Stale {
                                    self.history_renderer.request_failed(
                                        &mut self.history_views,
                                        &source,
                                        &request_id,
                                        &error.to_string(),
                                    );
                                }
                            }
                            context.request_repaint();
                            continue;
                        }
                        Event::HistoryImage(pixels) => {
                            let source = pixels.image.source.clone();
                            let id = pixels.request_id.clone();
                            if let Err(error) = self.history_renderer.on_image(
                                context,
                                &mut self.history_views,
                                pixels,
                            ) {
                                if error != crate::display::DisplayError::Stale {
                                    self.history_renderer.request_failed(
                                        &mut self.history_views,
                                        &source,
                                        &id,
                                        &error.to_string(),
                                    );
                                }
                            }
                            context.request_repaint();
                            continue;
                        }
                        Event::HistoryRequestFailed {
                            source,
                            request_id,
                            error,
                            current_stamp,
                        } => {
                            self.history_read_failed(&source, &request_id, &error, current_stamp);
                            context.request_repaint();
                            continue;
                        }
                        Event::HistoryEditFinished { body, result } => {
                            self.history_mutations.remove(&body.source);
                            if result.is_err()
                                && self.rewrite_pending.as_deref()
                                    == Some(body.source.conversation_id())
                            {
                                self.rewrite_pending = None;
                                self.state.run_pending = false;
                            }
                            self.history_renderer.edit_finished(&body, result);
                            context.request_repaint();
                            continue;
                        }
                        Event::HistoryCopied { body, text } => {
                            if self
                                .history_views
                                .view(&body.source)
                                .and_then(|view| view.stamp.as_ref())
                                == Some(&body.stamp)
                                && text.len() as u64 == body.bytes
                            {
                                context.copy_text(text);
                            } else {
                                self.state.error("消息来源已变化，请重新复制。");
                            }
                            continue;
                        }
                        Event::SkillImport(update) => {
                            self.skill_import_received(update);
                            context.request_repaint();
                            continue;
                        }
                        Event::PluginAssets(update) => {
                            self.plugin_assets_received(update);
                            context.request_repaint();
                            continue;
                        }
                        Event::PluginPreview(result) => {
                            self.plugin_preview_received(result);
                            continue;
                        }
                        Event::PluginInstallResult(result) => {
                            self.plugin_install_finished(result);
                            continue;
                        }
                        event => event,
                    };
                    let loaded = matches!(&event, Event::Loaded { .. });
                    let advance_queue = matches!(
                        &event,
                        Event::RunEnded { .. }
                            | Event::RunStartRejected { .. }
                            | Event::ConversationOpened { .. }
                            | Event::QueuedTurns { .. }
                            | Event::QueueWrite { .. }
                            | Event::QueuedCommandFinished { .. }
                            | Event::ConversationModelSelected { .. }
                    );
                    match &event {
                        Event::OpenTerminal => {
                            self.child_viewer = None;
                            self.last_opened_child = None;
                            self.drawer_open = false;
                            if let Some(request) = self.terminal_workspace.refresh() {
                                self.terminal_requests(vec![request]);
                            }
                        }
                        Event::OpenJobs => {
                            self.child_viewer = None;
                            self.last_opened_child = None;
                            self.drawer_open = false;
                            self.dispatch(Action::LoadJobs);
                        }
                        Event::LegacyArchive(update) => self.archive_view.apply(update),
                        Event::Jobs(records) => self.job_view.records = records.clone(),
                        Event::JobOutput {
                            request_id,
                            key,
                            result,
                        } if self.job_view.pending.as_ref() == Some(request_id)
                            && self.job_view.selected.as_ref() == Some(key) =>
                        {
                            self.job_view.pending = None;
                            match result {
                                Ok(output) => {
                                    self.job_view.output = Some(output.clone());
                                    self.job_view.error = None;
                                }
                                Err(error) => self.job_view.error = Some(error.clone()),
                            }
                        }
                        Event::PlatformPermission(result) => {
                            use crate::platform::permissions::PermissionOutcome;
                            #[cfg(target_os = "android")]
                            let visible = crate::platform::android::activity_visible();
                            #[cfg(not(target_os = "android"))]
                            let visible = context.input(|input| input.focused);
                            let intents = self.composer_platform.permission_result(result, visible);
                            self.composer_intents(intents);
                            let owned: Vec<_> = self
                                .permission_pending
                                .iter()
                                .filter(|(_, operation)| **operation == result.operation)
                                .map(|(kind, _)| *kind)
                                .collect();
                            for kind in owned {
                                self.permission_pending.remove(&kind);
                                let outcome = result
                                    .permissions
                                    .iter()
                                    .find(|(returned, _)| *returned == kind);
                                let status = match outcome.map(|(_, outcome)| outcome) {
                                    Some(PermissionOutcome::Granted) => "已允许",
                                    Some(PermissionOutcome::Denied {
                                        settings_required: true,
                                    }) => "未允许，请在系统应用设置中调整",
                                    Some(PermissionOutcome::Denied {
                                        settings_required: false,
                                    }) => "未允许",
                                    Some(PermissionOutcome::Cancelled) => "已取消",
                                    Some(PermissionOutcome::Unavailable) => {
                                        "当前平台无法请求此权限"
                                    }
                                    None => "未收到明确的权限结果",
                                };
                                self.permission_status.insert(
                                    kind,
                                    match &result.error {
                                        Some(error) => format!("{status} · {error}"),
                                        None => status.into(),
                                    },
                                );
                            }
                        }
                        Event::Schedules(records) => self.schedules = records.clone(),
                        Event::ScheduleStatus(status) => self.schedule_status = status.clone(),
                        Event::ScheduleMutationFinished { request_id, result }
                            if self.schedule_pending.as_ref() == Some(request_id) =>
                        {
                            self.schedule_pending = None;
                            match result {
                                Ok(()) => self.schedule_editor = None,
                                Err(error) => self.state.error(error),
                            }
                        }
                        Event::LegacyPreview(result) => {
                            self.legacy_pending = false;
                            self.legacy_preview = Some(result.clone());
                        }
                        Event::LegacyImported(result) => {
                            self.legacy_pending = false;
                            self.legacy_preview = None;
                            self.legacy_import_report = Some(result.clone());
                        }
                        Event::TodoMutationFinished { request_id, result }
                            if self.todo_pending.as_ref() == Some(request_id) =>
                        {
                            self.todo_pending = None;
                            if let Err(error) = result {
                                self.state.error(error);
                            }
                        }
                        Event::ChatMutationFinished { request_id, result } => {
                            self.history_mutations.clear();
                            self.conversation_manager
                                .mutation_finished(request_id, result);
                        }
                        Event::ConversationInfo(update) => self.conversation_manager.apply(update),
                        Event::Avatar(update) => self.avatar_event(&context, update),
                        Event::ConversationPreferences(_) => self.conversation_manager.invalidate(),
                        Event::ConversationOrderFinished { request_id, result } => {
                            if self.conversation_order_pending.as_ref() == Some(request_id) {
                                self.conversation_order_pending = None;
                                if let Err(error) = result {
                                    self.state.info(error);
                                }
                            }
                        }
                        Event::Conversations(_) => self.conversation_manager.invalidate(),
                        Event::Loaded {
                            settings,
                            first_run_legacy_data,
                            ..
                        } => {
                            self.conversation_manager.reset();
                            self.avatar_view.reset();
                            self.settings_view.disconnected();
                            self.settings_draft = settings.clone();
                            self.settings_first_loaded(settings, first_run_legacy_data);
                        }
                        Event::SettingsSnapshot(snapshot) => {
                            self.settings_snapshot_updated(snapshot)
                        }
                        Event::SettingsMutationFinished {
                            request_id,
                            expected,
                            result,
                        } => {
                            self.settings_mutation_finished(request_id, expected, result);
                            if result.is_ok() {
                                self.refresh_context_reading();
                            }
                        }
                        Event::SettingsSaved(settings) => {
                            if self.settings_view.pending.is_none()
                                && self.settings_draft == self.state.settings
                            {
                                self.settings_draft = settings.clone();
                            }
                        }
                        Event::MemorySaved(_) => self.memory_editor = None,
                        Event::ModelsFetched {
                            request_id,
                            provider_id,
                            fingerprint,
                            result,
                        } => self.models_fetched(
                            request_id,
                            provider_id.as_deref(),
                            fingerprint,
                            result,
                        ),
                        Event::SkillSaved(_) => {
                            self.skill_editor = None;
                            self.skill_import_open = false;
                            self.skill_import_document.clear();
                            self.skill_import_id.clear();
                        }
                        Event::Skills(_) => self.skill_delete = None,
                        Event::HistoryTurns(page) => self.apply_turn_page(page.clone(), &context),
                        Event::ContextReading { key, reading } => {
                            self.context_readings.insert(
                                key.conversation_id.clone(),
                                (Some(key.clone()), reading.clone()),
                            );
                        }
                        Event::ConversationContextReading {
                            source,
                            stamp,
                            reading,
                        } => {
                            if let crate::display::ViewSource::Main { conversation_id } = source {
                                let current_request =
                                    self.state.runs.get(conversation_id).is_some_and(|run| {
                                        self.context_readings
                                            .get(conversation_id)
                                            .is_some_and(|(key, _)| key.as_ref() == Some(&run.key))
                                    });
                                if !current_request
                                    && self
                                        .history_views
                                        .view(source)
                                        .and_then(|view| view.stamp.as_ref())
                                        == Some(stamp)
                                {
                                    if let Some(reading) = reading {
                                        self.context_readings.insert(
                                            conversation_id.clone(),
                                            (None, reading.clone()),
                                        );
                                    } else {
                                        self.context_readings.remove(conversation_id);
                                    }
                                }
                            }
                        }
                        Event::McpSaved {
                            request_id,
                            id,
                            result,
                        } => self.mcp_saved(request_id.clone(), id.clone(), result.clone()),
                        Event::ConversationModelSelected {
                            request_id,
                            conversation_id,
                            result,
                        } => {
                            if self.model_choice_pending.as_deref() == Some(request_id.as_str()) {
                                self.model_choice_pending = None;
                                if result.is_ok() {
                                    self.refresh_context_reading();
                                }
                                if result.is_err() {
                                    if let Some(chat) = conversation_id.as_ref() {
                                        if let Some(head) = self
                                            .state
                                            .queued_turns
                                            .get(chat)
                                            .and_then(|turns| turns.first())
                                        {
                                            self.refused_queue_head =
                                                Some((chat.clone(), head.id.clone()));
                                        }
                                    }
                                }
                            }
                        }
                        Event::McpDeleted { id, result } => {
                            self.mcp_deleted(id.clone(), result.clone())
                        }
                        Event::McpTestFinished { request_id, result } => {
                            self.mcp_test_finished(request_id.clone(), result.clone())
                        }
                        Event::PluginEnvironment { id, result } => {
                            self.plugin_environment_received(id.clone(), result.clone());
                        }
                        Event::PluginEnvironmentSaved { id, result } => {
                            self.plugin_environment_saved(id.clone(), result.clone());
                        }
                        Event::PluginUninstalled { id, result } => {
                            self.plugin_uninstalled(id.clone(), result.clone());
                        }
                        Event::RunEnded { key, .. } => {
                            if self
                                .approval_reply_pending
                                .as_ref()
                                .is_some_and(|(run, _)| run == &key.run_id)
                            {
                                self.approval_reply_pending = None;
                            }
                        }
                        Event::ApprovalCleared {
                            run_id,
                            approval_id,
                        } => {
                            if self.approval_reply_pending.as_ref()
                                == Some(&(run_id.clone(), approval_id.clone()))
                            {
                                self.approval_reply_pending = None;
                            }
                        }
                        Event::Memories(_) => self.memory_delete = None,
                        Event::QueueWrite { id, result } => {
                            self.queue_write_finished(id, result.is_ok())
                        }
                        Event::QueuedTurns {
                            conversation_id,
                            turns,
                        } => {
                            self.authorized_queue.retain(|(owner, id)| {
                                owner != conversation_id || turns.iter().any(|turn| &turn.id == id)
                            });
                            // Keep exact attempt until RunStarted or terminal receipt.
                        }
                        Event::QueuedCommandFinished {
                            conversation_id,
                            id,
                            result,
                        } => {
                            let identity = (conversation_id.clone(), id.clone());
                            if self.queue_attempt.as_ref() == Some(&identity) {
                                self.queue_attempt = None;
                                if self.state.pending_run_conversation.as_ref()
                                    == Some(conversation_id)
                                {
                                    self.state.run_pending = false;
                                    self.state.pending_run_conversation = None;
                                }
                                let retained =
                                    self.state.queued_turns.get(conversation_id).is_some_and(
                                        |turns| turns.iter().any(|turn| &turn.id == id),
                                    );
                                self.refused_queue_head =
                                    (result.is_err() && retained).then_some(identity.clone());
                                if !retained {
                                    self.authorized_queue.remove(&identity);
                                }
                            }
                        }
                        Event::ConversationDeleted(id) => {
                            self.context_readings.remove(id);
                            self.authorized_queue.retain(|(owner, _)| owner != id);
                            if self.state.selected_conversation.as_ref() == Some(id) {
                                if let Err(error) = self.composer_platform.activate(None) {
                                    self.state.error(error);
                                }
                            }
                            if self.conversation_delete.as_ref() == Some(id) {
                                self.conversation_delete = None;
                            }
                        }
                        _ => {}
                    }
                    if let Event::RunStarted { key } = &event {
                        if self
                            .queue_attempt
                            .as_ref()
                            .is_some_and(|(owner, _)| owner == &key.conversation_id)
                        {
                            self.queue_attempt = None;
                        }
                        if self.rewrite_pending.as_ref() == Some(&key.conversation_id) {
                            self.rewrite_pending = None;
                        }
                        if self.composer_run_pending.as_ref().is_some_and(|operation| {
                            operation.conversation_id.is_none()
                                || operation.conversation_id.as_ref() == Some(&key.conversation_id)
                        }) {
                            self.composer_run_pending = None;
                        }
                    } else if let Event::RunStartRejected {
                        conversation_id, ..
                    } = &event
                    {
                        if self.rewrite_pending.as_ref() == conversation_id.as_ref() {
                            self.rewrite_pending = None;
                        }
                    }
                    let queue_owner = match &event {
                        Event::RunEnded { key, .. } => Some(key.conversation_id.as_str()),
                        Event::RunStartRejected {
                            conversation_id, ..
                        } => conversation_id.as_deref(),
                        _ => None,
                    };
                    if self
                        .queue_attempt
                        .as_ref()
                        .is_some_and(|(chat, _)| Some(chat.as_str()) == queue_owner)
                    {
                        if let Some(attempt) = self.queue_attempt.take() {
                            if self
                                .state
                                .queued_turns
                                .get(&attempt.0)
                                .and_then(|turns| turns.first())
                                .is_some_and(|turn| turn.id == attempt.1)
                            {
                                self.refused_queue_head = Some(attempt);
                            }
                        }
                    }
                    let previous_page = self.state.page;
                    self.state.apply(event);
                    if previous_page == Page::Conversations
                        && self.state.page != Page::Conversations
                    {
                        self.conversation_info_close();
                    }
                    self.automation_page_changed(previous_page);
                    if advance_queue {
                        self.advance_queue(false);
                    }
                    if loaded
                        && self.state.page == Page::Usage
                        && self.state.capabilities.usage
                        && !self.state.usage_pending
                        && self.dispatch(Action::LoadUsage)
                    {
                        self.state.usage_pending = true;
                    }
                    if loaded
                        && self.state.page == Page::Mcp
                        && !self.state.mcp_loaded
                        && !self.state.mcp_pending
                        && self.dispatch(Action::LoadMcp)
                    {
                        self.state.mcp_pending = true;
                    }
                }
                Err(TryRecvError::Empty) => return,
                Err(TryRecvError::Disconnected) => {
                    let request = self.automation_view.suspend();
                    self.dispatch(Action::Automation(request));
                    self.automation_view.disconnected();
                    self.linux_view.disconnected();
                    self.runtime = None;
                    self.composer_platform.disconnected();
                    self.terminal_workspace.disconnected();
                    self.composer_run_pending = None;
                    self.authorized_queue.clear();
                    self.last_opened_child = None;
                    self.history_views.disconnected();
                    self.history_renderer.disconnected();
                    self.settings_leave_disconnected();
                    self.turn_navigation.disconnected();
                    self.history_mutations.clear();
                    self.job_view.pending = None;
                    for kind in self.permission_pending.keys() {
                        self.permission_status
                            .insert(*kind, "运行时连接已断开，权限结果未确认".into());
                    }
                    self.permission_pending.clear();
                    self.schedule_pending = None;
                    self.legacy_pending = false;
                    self.state.capabilities = Default::default();
                    self.state.runs.clear();
                    self.state.approvals.clear();
                    self.state.run_pending = false;
                    self.state.running = false;
                    self.state.approval = None;
                    self.approval_reply_pending = None;
                    self.state.running_conversation = None;
                    self.queue_write = None;
                    self.queue_attempt = None;
                    self.state.conversation_pending = false;
                    self.state.conversation_delete_pending = None;
                    self.state.usage_pending = false;
                    self.state.cancelling = false;
                    self.state.settings_pending = false;
                    self.state.settings_request = None;
                    self.settings_view.disconnected();
                    self.state.memory_pending = false;
                    self.state.plugins_pending = false;
                    self.plugin_import = None;
                    self.plugin_environment_editor = None;
                    self.mcp_editor = None;
                    self.mcp_delete = None;
                    self.mcp_delete_pending = false;
                    self.model_choice_pending = None;
                    self.state.mcp_pending = false;
                    self.state.files_pending = false;
                    self.state.terminal_pending = false;
                    self.state.terminal_running = false;
                    match crate::controller::spawn(self.data_dir.clone(), context.clone()) {
                        Ok(runtime) => {
                            self.runtime = Some(runtime);
                            self.dispatch(Action::Load);
                        }
                        Err(error) => self.state.error(error),
                    }
                    return;
                }
            }
        }
        // Drain the rest on a later frame instead of monopolising input and painting.
        context.request_repaint();
    }

    fn apply_theme(&mut self, context: &egui::Context) {
        let motion_scale = self.motion_scale();
        let behavior = &self.state.settings.behavior;
        let system_dark = context
            .system_theme()
            .map(|theme| theme == egui::Theme::Dark);
        #[cfg(target_os = "android")]
        let system_dark = self
            .window_insets
            .as_ref()
            .and_then(|view| view.system_dark())
            .or(system_dark)
            .unwrap_or(false);
        #[cfg(not(target_os = "android"))]
        let system_dark = system_dark.unwrap_or_else(|| context.theme() == egui::Theme::Dark);
        let dark = match behavior.theme_mode {
            1 => false,
            2 => true,
            _ => system_dark,
        };
        #[cfg(target_os = "android")]
        if let Some(view) = &self.window_insets {
            view.set_system_bars(dark);
        }
        let palette = match behavior.palette_mode {
            1..=3 => behavior.palette_mode,
            _ => 2,
        };
        #[cfg(target_os = "android")]
        let wallpaper = (palette == 1).then(|| {
            self.window_insets
                .as_ref()
                .map(|view| view.wallpaper_colors(dark))
                .unwrap_or_else(|| crate::platform::window_insets::wallpaper_fallback(dark))
        });
        #[cfg(not(target_os = "android"))]
        let wallpaper: Option<[Color32; 12]> = None;
        let appearance = (
            dark,
            palette,
            behavior.font_scale,
            behavior.corner_scale,
            wallpaper,
            motion_scale,
        );
        if self.rendered_dark_mode == Some(appearance) {
            return;
        }
        let theme = if dark {
            egui::Theme::Dark
        } else {
            egui::Theme::Light
        };
        context.set_theme(theme);
        let font_scale = match behavior.font_scale {
            0 => 0.88,
            2 => 1.12,
            3 => 1.26,
            _ => 1.0,
        };
        let corner = match behavior.corner_scale {
            0 => 5,
            2 => 12,
            _ => 8,
        };
        context.style_mut_of(theme, |style| {
            let foreground = gray(if dark { 232 } else { 32 });
            let border = gray(if dark { 65 } else { 220 });
            let surface = gray(if dark { 28 } else { 250 });
            style.visuals = if dark {
                egui::Visuals::dark()
            } else {
                egui::Visuals::light()
            };
            style.visuals.override_text_color = Some(foreground);
            style.visuals.panel_fill = gray(if dark { 21 } else { 255 });
            style.visuals.window_fill = surface;
            style.visuals.extreme_bg_color = gray(if dark { 18 } else { 248 });
            style.visuals.faint_bg_color = gray(if dark { 32 } else { 246 });
            style.visuals.hyperlink_color = foreground;
            style.visuals.code_bg_color = gray(if dark { 34 } else { 243 });
            if palette == 2 {
                style.visuals.panel_fill = if dark {
                    Color32::from_rgb(38, 38, 36)
                } else {
                    Color32::from_rgb(250, 249, 245)
                };
                style.visuals.override_text_color = Some(if dark {
                    Color32::from_rgb(240, 238, 231)
                } else {
                    Color32::from_rgb(34, 33, 30)
                });
                style.visuals.hyperlink_color = if dark {
                    Color32::from_rgb(231, 141, 112)
                } else {
                    Color32::from_rgb(169, 71, 40)
                };
            }
            style.visuals.window_stroke = Stroke::new(1.0, border);
            style.visuals.selection.bg_fill = gray(if dark { 76 } else { 208 });
            style.visuals.selection.stroke = Stroke::new(1.0, foreground);
            for widget in [
                &mut style.visuals.widgets.noninteractive,
                &mut style.visuals.widgets.inactive,
                &mut style.visuals.widgets.hovered,
                &mut style.visuals.widgets.active,
                &mut style.visuals.widgets.open,
            ] {
                widget.fg_stroke = Stroke::new(1.0, foreground);
                widget.bg_stroke = Stroke::new(1.0, border);
                widget.corner_radius = egui::CornerRadius::same(corner);
            }
            style.visuals.widgets.noninteractive.bg_fill = surface;
            style.visuals.widgets.inactive.bg_fill = gray(if dark { 36 } else { 245 });
            style.visuals.widgets.inactive.weak_bg_fill = gray(if dark { 30 } else { 250 });
            style.visuals.widgets.hovered.bg_fill = gray(if dark { 48 } else { 231 });
            style.visuals.widgets.hovered.weak_bg_fill = gray(if dark { 44 } else { 238 });
            style.visuals.widgets.active.bg_fill = gray(if dark { 62 } else { 220 });
            style.visuals.widgets.active.weak_bg_fill = gray(if dark { 58 } else { 223 });
            style.visuals.widgets.open.bg_fill = gray(if dark { 44 } else { 238 });
            style.visuals.widgets.open.weak_bg_fill = gray(if dark { 44 } else { 238 });
            if let Some(colors) = wallpaper {
                let [
                    foreground,
                    border,
                    panel,
                    surface,
                    extreme,
                    faint,
                    primary,
                    code,
                    selection,
                    selection_text,
                    inactive,
                    hovered,
                ] = colors;
                style.visuals.override_text_color = Some(foreground);
                style.visuals.panel_fill = panel;
                style.visuals.window_fill = surface;
                style.visuals.extreme_bg_color = extreme;
                style.visuals.faint_bg_color = faint;
                style.visuals.hyperlink_color = primary;
                style.visuals.code_bg_color = code;
                style.visuals.window_stroke = Stroke::new(1.0, border);
                style.visuals.selection.bg_fill = selection;
                style.visuals.selection.stroke = Stroke::new(1.0, selection_text);
                for widget in [
                    &mut style.visuals.widgets.noninteractive,
                    &mut style.visuals.widgets.inactive,
                    &mut style.visuals.widgets.hovered,
                    &mut style.visuals.widgets.active,
                    &mut style.visuals.widgets.open,
                ] {
                    widget.fg_stroke = Stroke::new(1.0, foreground);
                    widget.bg_stroke = Stroke::new(1.0, border);
                }
                style.visuals.widgets.noninteractive.bg_fill = surface;
                style.visuals.widgets.inactive.bg_fill = inactive;
                style.visuals.widgets.inactive.weak_bg_fill = surface;
                style.visuals.widgets.hovered.bg_fill = hovered;
                style.visuals.widgets.hovered.weak_bg_fill = hovered;
                style.visuals.widgets.active.bg_fill = selection;
                style.visuals.widgets.active.weak_bg_fill = selection;
                style.visuals.widgets.open.bg_fill = hovered;
                style.visuals.widgets.open.weak_bg_fill = hovered;
            }
            style.spacing.item_spacing = egui::vec2(8.0, 8.0);
            style.spacing.button_padding = egui::vec2(12.0, 10.0);
            style.spacing.interact_size = egui::vec2(44.0, ROW_HEIGHT);
            style.animation_time = 0.16 * motion_scale;
            style.text_styles.insert(
                egui::TextStyle::Body,
                egui::FontId::proportional(16.0 * font_scale),
            );
            style.text_styles.insert(
                egui::TextStyle::Button,
                egui::FontId::proportional(15.0 * font_scale),
            );
            style.text_styles.insert(
                egui::TextStyle::Heading,
                egui::FontId::proportional(22.0 * font_scale),
            );
            style.text_styles.insert(
                egui::TextStyle::Small,
                egui::FontId::proportional(12.0 * font_scale),
            );
        });
        self.rendered_dark_mode = Some(appearance);
        context.request_repaint();
    }

    fn motion_scale(&self) -> f32 {
        #[cfg(target_os = "android")]
        return self
            .window_insets
            .as_ref()
            .map_or(1.0, |view| view.motion_scale());
        #[cfg(not(target_os = "android"))]
        1.0
    }

    fn draw_drawer(&mut self, ui: &mut egui::Ui) -> Option<Navigation> {
        let mut navigation = None;
        ui.label(RichText::new("JAgent").size(22.0).strong());
        if let Some(assistant_id) = self.current_assistant_id() {
            if let Some(descriptor) = self
                .state
                .settings
                .assistants
                .iter()
                .find(|item| item.id == assistant_id)
                .map(avatars::Descriptor::from)
            {
                let mut requests = Vec::new();
                ui.horizontal(|ui| {
                    self.avatar_view
                        .face(&descriptor, &mut requests)
                        .draw(ui, 30.0);
                    ui.label(&descriptor.name);
                });
                self.avatar_requests(requests);
            }
        }
        ui.add_space(6.0);
        if full_button(
            ui,
            "新聊天",
            self.state.loaded && self.state.capabilities.chat && !self.state.conversation_pending,
        )
        .clicked()
        {
            navigation = Some(Navigation::NewConversation);
        }
        let navigation_height = (Page::ALL.len() as f32
            * (ROW_HEIGHT + ui.spacing().item_spacing.y))
            .min(ui.available_height() * 0.63);
        egui::ScrollArea::vertical()
            .id_salt("navigation")
            .max_height(navigation_height)
            .show(ui, |ui| {
                for page in Page::ALL {
                    if full_selectable(ui, page.label(), self.state.page == page).clicked() {
                        navigation = Some(Navigation::Page(page));
                    }
                }
            });
        ui.separator();
        ui.label(RichText::new("会话").small().weak());
        self.drawer_delete_confirmation(ui, &mut navigation);
        let drawer_conversations = self
            .state
            .conversation_preferences
            .order
            .indices(&self.state.conversations)
            .into_iter()
            .filter_map(|index| self.state.conversations.get(index).cloned())
            .collect::<Vec<_>>();
        if drawer_conversations.is_empty() {
            ui.label(RichText::new("暂无活动会话").weak());
        } else {
            egui::ScrollArea::vertical()
                .id_salt("conversations")
                .auto_shrink([false, false])
                .show_rows(ui, ROW_HEIGHT, drawer_conversations.len(), |ui, rows| {
                    for index in rows {
                        let conversation = drawer_conversations[index].clone();
                        let selected =
                            self.state.selected_conversation.as_ref() == Some(&conversation.id);
                        let title = if conversation.title.is_empty() {
                            "未命名会话"
                        } else {
                            &conversation.title
                        };
                        ui.push_id(&conversation.id, |ui| {
                            ui.horizontal(|ui| {
                                let title = if conversation.pinned {
                                    format!("★ {title}")
                                } else {
                                    title.to_owned()
                                };
                                self.drawer_reorder_handle(ui, &conversation);
                                let title = if self
                                    .state
                                    .conversation_preferences
                                    .completed_unread
                                    .contains(&conversation.id)
                                    && !self.state.runs.contains_key(&conversation.id)
                                {
                                    format!("● {title}")
                                } else {
                                    title
                                };
                                let title_width = (ui.available_width()
                                    - 3.0 * ROW_HEIGHT
                                    - 3.0 * ui.spacing().item_spacing.x)
                                    .max(0.0);
                                ui.allocate_ui_with_layout(
                                    egui::vec2(title_width, ROW_HEIGHT),
                                    egui::Layout::top_down(egui::Align::Min),
                                    |ui| {
                                        ui.set_width(title_width);
                                        let selectable = !self.state.conversation_pending
                                            && self.state.conversation_delete_pending.as_ref()
                                                != Some(&conversation.id);
                                        ui.add_enabled_ui(selectable, |ui| {
                                            let response = full_selectable(ui, &title, selected);
                                            self.conversation_drop_target(&response, &conversation);
                                            if response.clicked() {
                                                navigation = Some(Navigation::Conversation(
                                                    conversation.id.clone(),
                                                ));
                                            }
                                        });
                                    },
                                );
                                if ui
                                    .add_sized([ROW_HEIGHT, ROW_HEIGHT], egui::Button::new("↓"))
                                    .on_hover_text("导出完整会话JSON")
                                    .clicked()
                                {
                                    self.begin_conversation_export(
                                        conversation.id.clone(),
                                        conversation.title.clone(),
                                    );
                                }
                                let allowed = self.can_delete_conversation(&conversation.id);
                                ui.add_enabled_ui(allowed, |ui| {
                                    if ui
                                        .add_sized([ROW_HEIGHT, ROW_HEIGHT], egui::Button::new("×"))
                                        .on_hover_text("删除会话")
                                        .clicked()
                                    {
                                        self.conversation_delete = Some(conversation.id.clone());
                                    }
                                });
                            });
                        });
                    }
                });
        }
        navigation
    }

    fn drawer_delete_confirmation(
        &mut self,
        ui: &mut egui::Ui,
        navigation: &mut Option<Navigation>,
    ) {
        if let Some(id) = self.conversation_delete.clone() {
            if let Some(conversation) = self
                .state
                .conversations
                .iter()
                .find(|conversation| conversation.id == id)
            {
                ui.add(egui::Label::new(format!("删除“{}”？", conversation.title)).wrap());
                ui.label(
                    RichText::new("聊天内容将删除，用量记录会保留。")
                        .small()
                        .weak(),
                );
                let allowed = self.can_delete_conversation(&id);
                if self.state.running && self.state.running_conversation.as_ref() == Some(&id) {
                    ui.label(
                        RichText::new("此会话正在执行，请先停止或等待结束。")
                            .small()
                            .weak(),
                    );
                }
                ui.horizontal(|ui| {
                    if ui
                        .add_enabled(allowed, egui::Button::new("确认删除"))
                        .clicked()
                    {
                        *navigation = Some(Navigation::DeleteConversation(id.clone()));
                    }
                    if ui
                        .add_enabled(
                            self.state.conversation_delete_pending.is_none(),
                            egui::Button::new("保留"),
                        )
                        .clicked()
                    {
                        self.conversation_delete = None;
                    }
                    if self.state.conversation_delete_pending.as_ref() == Some(&id) {
                        ui.spinner();
                    }
                });
            } else if self.state.conversation_delete_pending.is_none() {
                self.conversation_delete = None;
            }
        }
    }

    fn navigate(&mut self, navigation: Navigation, narrow: bool) {
        if self.onboarding_blocks_navigation() {
            return;
        }
        let Some(navigation) = self.settings_navigation(navigation, narrow) else {
            return;
        };
        let previous_page = self.state.page;
        let close_drawer = !matches!(&navigation, Navigation::DeleteConversation(_));
        if close_drawer {
            self.queue_editor = None;
            self.refused_queue_head = None;
            self.child_viewer = None;
            self.last_opened_child = None;
        }
        match navigation {
            Navigation::Page(page) => {
                self.state.page = page;
                if page == Page::Usage && previous_page != Page::Usage {
                    self.usage_view.attach();
                }
                if page == Page::Terminal
                    && let Some(request) = self.terminal_workspace.refresh()
                {
                    self.terminal_requests(vec![request]);
                }
                if page == Page::Schedules {
                    self.dispatch(Action::LoadSchedules);
                }
                if page == Page::Jobs {
                    self.dispatch(Action::LoadJobs);
                }
                if page == Page::Files && self.state.capabilities.files && !self.state.files_pending
                {
                    if self.dispatch(Action::ListFiles(self.state.directory.clone())) {
                        self.state.files_pending = true;
                    }
                }
                if page == Page::Usage && self.state.capabilities.usage && !self.state.usage_pending
                {
                    if self.dispatch(Action::LoadUsage) {
                        self.state.usage_pending = true;
                    }
                }
                if page == Page::Mcp
                    && !self.state.mcp_loaded
                    && !self.state.mcp_pending
                    && self.dispatch(Action::LoadMcp)
                {
                    self.state.mcp_pending = true;
                }
            }
            Navigation::NewConversation => match self.composer_platform.begin_new() {
                Ok(()) => {
                    self.state.selected_conversation = None;
                    self.state.page = Page::Chat;
                    self.state.conversation_pending = false;
                    self.state.sync_current_run();
                }
                Err(error) => self.state.error(error),
            },
            Navigation::Conversation(id) => {
                if self.dispatch(Action::SelectConversation(id.clone())) {
                    if let Err(error) = self.composer_platform.activate(Some(id)) {
                        self.state.error(error);
                    }
                    self.state.page = Page::Chat;
                    self.state.conversation_pending = true;
                }
            }
            Navigation::DeleteConversation(id) => {
                if self.can_delete_conversation(&id)
                    && self.dispatch(Action::DeleteConversation(id.clone()))
                {
                    self.state.conversation_delete_pending = Some(id);
                }
            }
        }
        self.automation_page_changed(previous_page);
        if narrow && close_drawer {
            self.drawer_open = false;
        }
        if close_drawer {
            self.advance_queue(false);
        }
    }

    fn can_delete_conversation(&self, id: &str) -> bool {
        self.state.loaded
            && self.state.capabilities.chat
            && !self.state.conversation_pending
            && self.state.conversation_delete_pending.is_none()
            && self.state.goal.chat_id != id
            && !(self.state.running && self.state.running_conversation.as_deref() == Some(id))
    }

    fn goal_controls(&mut self, ui: &mut egui::Ui, configured: bool) {
        use crate::goal::Status;

        let goal = self.state.goal.clone();
        if goal.text.is_empty() {
            return;
        }
        let owns_goal = self.state.selected_conversation.as_deref() == Some(&goal.chat_id);
        ui.collapsing(format!("持续目标 · {}", goal.status.label()), |ui| {
            ui.label(text_preview(&goal.text, 500));
            if !goal.progress.is_empty() {
                ui.label(text_preview(&goal.progress, 500));
            }
            if !goal.detail.is_empty() {
                ui.label(text_preview(&goal.detail, 500));
            }
            let idle = self.state.loaded
                && !self.state.running
                && !self.state.conversation_pending
                && self.model_choice_pending.is_none();
            ui.horizontal_wrapped(|ui| {
                if !owns_goal {
                    if ui
                        .add_enabled(idle, egui::Button::new("打开目标会话"))
                        .clicked()
                    {
                        self.navigate(Navigation::Conversation(goal.chat_id.clone()), false);
                    }
                    return;
                }
                if matches!(goal.status, Status::Paused | Status::Blocked)
                    && ui
                        .add_enabled(idle && configured, egui::Button::new("继续目标"))
                        .clicked()
                    && self.dispatch(Action::ResumeGoal {
                        goal_id: goal.id.clone(),
                    })
                {
                    self.mark_run_pending();
                }
                if ui
                    .add_enabled(idle && !self.state.running, egui::Button::new("清除目标"))
                    .clicked()
                {
                    self.dispatch(Action::ClearGoal { goal_id: goal.id });
                }
            });
        });
    }

    fn mark_run_pending(&mut self) {
        self.state.run_pending = true;
        self.state.pending_run_conversation = self.state.selected_conversation.clone();
        self.state.sync_current_run();
        self.state.cancelling = false;
        self.state.notice = None;
    }

    fn queue_write_finished(&mut self, id: &str, succeeded: bool) {
        if self
            .queue_write
            .as_ref()
            .is_none_or(|write| write.id() != id)
        {
            return;
        }
        let write = self.queue_write.take();
        if !succeeded {
            return;
        }
        match write {
            Some(QueueWrite::Edit(id)) => {
                if let Some((chat, _)) = &self.queue_editor {
                    self.authorized_queue.remove(&(chat.clone(), id));
                }
                self.queue_editor = None;
                self.refused_queue_head = None;
            }
            Some(QueueWrite::Promote {
                conversation_id,
                id,
                expected,
            }) => {
                if self
                    .state
                    .runs
                    .get(&conversation_id)
                    .is_some_and(|run| run.key == expected && !run.cancelling)
                {
                    self.authorized_queue.insert((conversation_id, id));
                }
                self.refused_queue_head = None;
            }
            _ => {}
        }
    }

    fn advance_queue(&mut self, manual: bool) {
        if self.state.page != Page::Chat
            || self.child_viewer.is_some()
            || self.runtime.is_none()
            || !self.state.loaded
            || self.state.running
            || self.state.run_pending
            || self.queue_attempt.is_some()
            || self.state.conversation_pending
            || self.state.conversation_delete_pending.is_some()
            || self.queue_write.is_some()
            || self.queue_editor.is_some()
            || self.model_choice_pending.is_some()
        {
            return;
        }
        let Some(chat) = self.state.selected_conversation.as_ref() else {
            return;
        };
        let Some(head) = self
            .state
            .queued_turns
            .get(chat)
            .and_then(|turns| turns.first())
        else {
            return;
        };
        let attempt = (chat.clone(), head.id.clone());
        if !manual
            && (self.refused_queue_head.as_ref() == Some(&attempt)
                || !self.authorized_queue.contains(&attempt))
        {
            return;
        }
        if self.dispatch(Action::SendQueuedTurn {
            conversation_id: attempt.0.clone(),
            id: attempt.1.clone(),
            automatic: !manual,
        }) {
            if manual {
                self.authorized_queue.insert(attempt.clone());
            }
            self.queue_attempt = Some(attempt);
            self.refused_queue_head = None;
            self.mark_run_pending();
        }
    }

    fn queued_turns(&mut self, ui: &mut egui::Ui) {
        let Some(chat) = self.state.selected_conversation.clone() else {
            return;
        };
        let Some(turns) = self.state.queued_turns.get(&chat).cloned() else {
            return;
        };
        let editable = self.queue_write.is_none()
            && !self.state.conversation_pending
            && self.queue_attempt.is_none()
            && self.model_choice_pending.is_none();
        egui::CollapsingHeader::new(format!("排队消息 · {}", turns.len()))
            .default_open(true)
            .show(ui, |ui| {
                egui::ScrollArea::vertical()
                    .max_height(144.0)
                    .show(ui, |ui| {
                        for (index, turn) in turns.into_iter().enumerate() {
                            ui.push_id(&turn.id, |ui| {
                                if let Some((_, draft)) = self
                                    .queue_editor
                                    .as_mut()
                                    .filter(|(owner, draft)| owner == &chat && draft.id == turn.id)
                                {
                                    ui.add_enabled(
                                        editable,
                                        TextEdit::multiline(&mut draft.text).desired_rows(2),
                                    );
                                    let mut remove = None;
                                    for (index, attachment) in draft.attachments.iter().enumerate()
                                    {
                                        ui.horizontal_wrapped(|ui| {
                                            ui.label(format!(
                                                "附件：{} · {} · {}",
                                                attachment.display_name,
                                                attachment.mime_type,
                                                byte_size(attachment.bytes)
                                            ));
                                            if ui
                                                .add_enabled(
                                                    editable,
                                                    egui::Button::new("移除附件").small(),
                                                )
                                                .clicked()
                                            {
                                                remove = Some(index);
                                            }
                                        });
                                    }
                                    if let Some(index) = remove {
                                        draft.attachments.remove(index);
                                    }
                                    let draft = draft.clone();
                                    ui.horizontal(|ui| {
                                        if ui
                                            .add_enabled(
                                                editable
                                                    && (!draft.text.trim().is_empty()
                                                        || !draft.attachments.is_empty())
                                                    && draft.text.len()
                                                        <= crate::composer::MAX_TEXT_BYTES,
                                                egui::Button::new("保存"),
                                            )
                                            .clicked()
                                            && self.dispatch(Action::EditQueuedTurn {
                                                conversation_id: chat.clone(),
                                                turn: draft.clone(),
                                            })
                                        {
                                            self.queue_write = Some(QueueWrite::Edit(draft.id));
                                        }
                                        if ui
                                            .add_enabled(editable, egui::Button::new("取消编辑"))
                                            .clicked()
                                        {
                                            self.queue_editor = None;
                                            self.advance_queue(false);
                                        }
                                    });
                                    return;
                                }
                                if !turn.text.trim().is_empty() {
                                    ui.label(text_preview(&turn.text, 160));
                                }
                                for attachment in &turn.attachments {
                                    ui.label(
                                        RichText::new(format!(
                                            "附件：{} · {} · {}",
                                            attachment.display_name,
                                            attachment.mime_type,
                                            byte_size(attachment.bytes)
                                        ))
                                        .small(),
                                    );
                                }
                                if index == 0
                                    && !self.state.running
                                    && !self
                                        .authorized_queue
                                        .contains(&(chat.clone(), turn.id.clone()))
                                {
                                    ui.label(
                                        RichText::new("排队消息等待你继续发送").small().weak(),
                                    );
                                }
                                ui.horizontal_wrapped(|ui| {
                                    if self.state.running_conversation.as_deref()
                                        == Some(chat.as_str())
                                        && ui
                                            .add_enabled(
                                                editable
                                                    && !self.state.cancelling
                                                    && self.state.current_run_key().is_some(),
                                                egui::Button::new("立即插入"),
                                            )
                                            .clicked()
                                        && self.dispatch(Action::SteerRun {
                                            conversation_id: chat.clone(),
                                            expected: self.state.current_run_key().unwrap(),
                                            input: SteerInput::Queued(turn.id.clone()),
                                        })
                                    {
                                        self.queue_write = Some(QueueWrite::Promote {
                                            conversation_id: chat.clone(),
                                            expected: self.state.current_run_key().unwrap(),
                                            id: turn.id.clone(),
                                        });
                                    }
                                    if ui
                                        .add_enabled(editable, egui::Button::new("编辑"))
                                        .clicked()
                                    {
                                        self.queue_editor = Some((chat.clone(), turn.clone()));
                                    }
                                    if ui
                                        .add_enabled(editable, egui::Button::new("删除"))
                                        .clicked()
                                        && self.dispatch(Action::RemoveQueuedTurn {
                                            conversation_id: chat.clone(),
                                            id: turn.id.clone(),
                                        })
                                    {
                                        self.queue_write =
                                            Some(QueueWrite::Change(turn.id.clone()));
                                    }
                                    if index == 0 && !self.state.running {
                                        let retry = self.refused_queue_head.as_ref()
                                            == Some(&(chat.clone(), turn.id.clone()));
                                        if ui
                                            .add_enabled(
                                                editable && !self.state.run_pending,
                                                egui::Button::new(if retry {
                                                    "重试发送"
                                                } else {
                                                    "发送下一条"
                                                }),
                                            )
                                            .clicked()
                                        {
                                            self.advance_queue(true);
                                        }
                                    }
                                });
                            });
                        }
                    });
            });
    }

    /// Compose's UnifiedSelector: the model id, the reasoning badge and the caret.
    fn composer_model_selector(
        &mut self,
        ui: &mut egui::Ui,
        chrome: &mobile_chat::InputChrome,
        bounds: egui::Rect,
    ) {
        let skin = chrome.skin;
        let caption = self.conversation_model_caption();
        let model_font = skin.font(13.0);
        let badge_font = skin.font(11.0);
        let mode = self.state.settings.reasoning_effort.trim();
        let mode = if mode.is_empty() { "Default" } else { mode };
        let badge_text = ui
            .painter()
            .layout_no_wrap(mode.to_owned(), badge_font.clone(), skin.ink)
            .size()
            .x;
        let badge_width = badge_text + 12.0;
        let model_width = ui
            .painter()
            .layout_no_wrap(caption.clone(), model_font.clone(), skin.ink)
            .size()
            .x
            .min(160.0);
        let right = bounds.right();
        let caret = egui::Rect::from_min_max(
            egui::pos2(right - 16.0, bounds.center().y - 8.0),
            egui::pos2(right, bounds.center().y + 8.0),
        );
        let badge_right = caret.left() - 4.0;
        let badge = egui::Rect::from_min_max(
            egui::pos2(badge_right - badge_width, bounds.center().y - 9.5),
            egui::pos2(badge_right, bounds.center().y + 9.5),
        );
        let model_right = badge.left() - 4.0;
        let model = egui::Rect::from_min_max(
            egui::pos2((model_right - model_width).max(bounds.left()), bounds.top()),
            egui::pos2(model_right, bounds.bottom()),
        );
        mobile_chat::single_line(ui, &caption, model, model_font, skin.ink);
        ui.painter().rect_filled(badge, 4.0, skin.primary_container);
        mobile_chat::single_line(
            ui,
            mode,
            badge.shrink2(egui::vec2(6.0, 2.0)),
            badge_font,
            skin.on_primary_container,
        );
        let painter = ui.painter();
        let tip = egui::pos2(caret.center().x, caret.center().y + 2.5);
        painter.line_segment(
            [
                egui::pos2(caret.center().x - 5.0, caret.center().y - 2.0),
                tip,
            ],
            egui::Stroke::new(1.6, skin.ink),
        );
        painter.line_segment(
            [
                tip,
                egui::pos2(caret.center().x + 5.0, caret.center().y - 2.0),
            ],
            egui::Stroke::new(1.6, skin.ink),
        );
        if self.conversation_model_editable() {
            let response = ui.interact(
                bounds,
                ui.make_persistent_id("composer model"),
                egui::Sense::click(),
            );
            egui::Popup::menu(&response).show(|ui| self.conversation_model_menu(ui));
        }
    }

    /// Compose's context ring: a 22dp circle with the used context in the middle.
    fn composer_context_ring(
        &mut self,
        ui: &mut egui::Ui,
        chrome: &mobile_chat::InputChrome,
        bounds: egui::Rect,
    ) {
        let skin = chrome.skin;
        let used = self
            .state
            .selected_conversation
            .as_ref()
            .and_then(|id| self.context_readings.get(id))
            .map_or(0, |(_, reading)| reading.used);
        let center = bounds.center();
        let painter = ui.painter();
        painter.circle_stroke(center, 11.0, Stroke::new(2.0, skin.divider));
        let label = if used >= 1000 {
            format!("{}k", used / 1000)
        } else {
            used.to_string()
        };
        painter.text(
            center,
            egui::Align2::CENTER_CENTER,
            label,
            skin.font(9.0),
            skin.accent,
        );
    }

    fn chat(&mut self, ui: &mut egui::Ui, skin: Option<mobile_chat::Skin>) {
        if self.child_viewer.is_some() {
            self.child_transcript(ui);
            return;
        }
        if skin.is_none()
            && let Some(conversation_id) = self.state.selected_conversation.clone()
        {
            if full_button(
                ui,
                "压缩上下文",
                self.state.loaded && !self.state.cancelling && !self.state.run_pending,
            )
            .clicked()
            {
                let already_running = self.state.running;
                if self.dispatch(Action::CompactConversation { conversation_id })
                    && !already_running
                {
                    self.mark_run_pending();
                }
            }
        }
        let composer_margin = if skin.is_some() {
            egui::Margin {
                left: 16,
                right: 16,
                top: 6,
                bottom: 12,
            }
        } else {
            egui::Margin::same(12)
        };
        let mut chat_configured = false;
        egui::Panel::bottom("composer")
            .show_separator_line(skin.is_none())
            .frame(egui::Frame::NONE.inner_margin(composer_margin))
            .show(ui, |ui| {
                if skin.is_none() {
                    self.conversation_assistant(ui);
                    self.conversation_model(ui);
                }
                if self.model_choice_pending.is_some() {
                    ui.horizontal(|ui| {
                        ui.spinner();
                        ui.label("正在保存模型选择");
                    });
                }
                let assistant_id = self.current_assistant_id();
                let conversation = self.state.selected_conversation.as_ref().and_then(|id| {
                    self.state
                        .conversations
                        .iter()
                        .find(|conversation| &conversation.id == id)
                });
                let resolved = self.state.settings.connection_for(
                    assistant_id.as_deref(),
                    conversation.and_then(|conversation| conversation.provider_id.as_deref()),
                    conversation.and_then(|conversation| conversation.model.as_deref()),
                );
                let configured = resolved.as_ref().is_ok_and(|settings| {
                    !settings.endpoint.trim().is_empty() && !settings.model.trim().is_empty()
                });
                chat_configured = configured;
                if !self.state.capabilities.chat {
                    unavailable(ui, "聊天运行时尚未接入");
                } else if !configured && skin.is_none() {
                    ui.label(RichText::new("请先在设置中填写接口地址和模型并保存").weak());
                } else if skin.is_none() {
                    if let Ok(settings) = &resolved {
                        ui.label(RichText::new(settings.model).small().weak());
                        let reading = self
                            .state
                            .selected_conversation
                            .as_ref()
                            .and_then(|id| self.context_readings.get(id))
                            .map(|(_, reading)| reading)
                            .filter(|reading| reading.model == settings.model);
                        let window = self
                            .state
                            .settings
                            .model_contexts
                            .get(settings.model)
                            .copied()
                            .filter(|value| *value > 0)
                            .map_or(crate::context::DEFAULT_CONTEXT_WINDOW, |value| value as u64);
                        context_readout::show(ui, reading, settings.model, window);
                    }
                }
                self.goal_controls(ui, configured);
                self.todo_row(ui);
                self.queued_turns(ui);
                self.child_launch_controls(ui);
                self.child_tasks(ui);
                let can_start_goal = crate::goal::slash_argument(self.composer_platform.text())
                    .is_none()
                    && (self.state.goal.text.is_empty()
                        || self.state.goal.status == crate::goal::Status::Completed
                        || self.state.selected_conversation.as_deref()
                            == Some(self.state.goal.chat_id.as_str()));
                if let Some(skin) = skin {
                    mobile_chat::composer_frame(skin).show(ui, |ui| {
                        self.composer_input(ui, configured, can_start_goal, Some(skin));
                    });
                } else {
                    self.composer_input(ui, configured, can_start_goal, None);
                }
                if self.state.running && skin.is_none() {
                    ui.horizontal(|ui| {
                        ui.spinner();
                        if self
                            .state
                            .selected_conversation
                            .as_ref()
                            .and_then(|id| self.state.runs.get(id))
                            .is_some_and(|run| run.compressing)
                        {
                            ui.label("正在压缩上下文");
                        }
                        let text = if self.state.cancelling {
                            "正在停止"
                        } else {
                            "停止生成"
                        };
                        if ui
                            .add_enabled(!self.state.cancelling, egui::Button::new(text))
                            .clicked()
                            && self.stop_current_run()
                        {
                            self.state.cancelling = true;
                        }
                    });
                }
            });
        let mut main = egui::CentralPanel::default();
        if skin.is_some() {
            main = main.frame(egui::Frame::NONE.inner_margin(egui::Margin::symmetric(16, 0)));
        }
        main.show(ui, |ui| {
            if self.state.conversation_pending {
                ui.spinner();
                ui.label("正在读取会话…");
                return;
            }
            let Some(conversation_id) = self.state.selected_conversation.clone() else {
                if let Some(skin) = skin {
                    self.mobile_empty_chat(ui, skin, chat_configured);
                    return;
                }
                ui.add_space(24.0);
                ui.heading("开始一段对话");
                ui.label("保存模型配置后即可发送消息。");
                ui.label(
                    RichText::new("可使用文件、系统和扩展工具；工具操作按当前权限与审批设置执行。")
                        .small()
                        .weak(),
                );
                return;
            };
            let source = crate::display::ViewSource::Main {
                conversation_id: conversation_id.clone(),
            };
            if let Some(position) = self.history_views.open(source.clone(), None) {
                self.dispatch(Action::SaveReadPosition(position));
            }
            let running = self.state.runs.contains_key(&conversation_id)
                || (self.state.run_pending
                    && self.state.pending_run_conversation.as_ref() == Some(&conversation_id));
            let editable = !running && !self.history_mutations.contains(&source);
            if skin.is_some() {
                self.avatar_view
                    .set_brand(Some(self.mobile_icons.brand_texture()));
            }
            self.main_history_face(&source);
            self.history_renderer.set_reply_audio_enabled(
                self.state.settings.speak_replies && self.runtime.is_some(),
            );
            self.history_renderer
                .set_navigation_interacting(&source, self.turn_navigation.interacting());
            let chrome = skin.map(|skin| (skin, &self.mobile_icons));
            let intents = self.history_renderer.show(
                ui,
                &mut self.history_views,
                &source,
                running,
                editable,
                chrome,
            );
            if let (Some(observer), Some(stamp)) = (
                self.runtime.as_ref().map(|runtime| runtime.observer_id()),
                self.history_renderer.presented_stamp(&source).cloned(),
            ) {
                self.conversation_view_lease.rendered =
                    Some((observer, conversation_id.clone(), stamp));
            }
            self.history_intents(intents);
            let viewport = self.history_renderer.navigation_viewport(&source);
            let presented = self.history_renderer.presented_stamp(&source).cloned();
            let reading = self.history_renderer.navigation_reading(&source);
            let intents = self.turn_navigation.show(
                ui,
                &self.history_views,
                &source,
                viewport,
                presented.as_ref(),
                reading,
            );
            self.turn_navigation_intents(intents);
        });
    }

    fn child_tasks(&mut self, ui: &mut egui::Ui) {
        let Some(chat_id) = self.state.selected_conversation.as_ref() else {
            return;
        };
        if !self.state.running || self.state.running_conversation.as_ref() != Some(chat_id) {
            return;
        }
        let Some(records) = self
            .state
            .children
            .get(chat_id)
            .filter(|items| !items.is_empty())
        else {
            return;
        };
        let running = records
            .iter()
            .filter(|child| !child.status.terminal())
            .count();
        if running == 0 {
            return;
        }
        egui::CollapsingHeader::new(format!("子任务 · {running} 进行中"))
            .id_salt(("child_tasks", chat_id))
            .default_open(running > 0)
            .show(ui, |ui| {
                egui::ScrollArea::vertical()
                    .max_height(120.0)
                    .show(ui, |ui| {
                        for child in records.iter().filter(|child| !child.status.terminal()) {
                            if full_button(
                                ui,
                                &format!("{} · {}", child.name, child.status.label()),
                                true,
                            )
                            .clicked()
                            {
                                self.child_viewer = Some((chat_id.clone(), child.id().to_owned()));
                            }
                        }
                    });
            });
    }

    fn child_transcript(&mut self, ui: &mut egui::Ui) {
        let Some((chat_id, id)) = self.child_viewer.clone() else {
            return;
        };
        let Some(record) = self
            .state
            .children
            .get(&chat_id)
            .and_then(|records| records.iter().find(|record| record.id() == id))
            .cloned()
        else {
            self.child_viewer = None;
            self.last_opened_child = None;
            return;
        };
        let mut back = false;
        let mut cancel = false;
        egui::Panel::bottom("child_controls")
            .frame(egui::Frame::NONE.inner_margin(12))
            .show(ui, |ui| {
                ui.horizontal(|ui| {
                    back = ui.button("返回主对话").clicked();
                    ui.label(record.status.label());
                    if !record.status.terminal() && ui.button("取消子任务").clicked() {
                        cancel = true;
                    }
                });
                ui.label(RichText::new(&record.name).strong());
                if let Some(config) = &record.config {
                    egui::CollapsingHeader::new("本次子任务配置")
                        .id_salt(("child_config", &id))
                        .show(ui, |ui| {
                            ui.label(format!(
                                "{} · {}",
                                config.profile_name, config.assistant_name
                            ));
                            ui.label(format!(
                                "模型：{} · 审批：{}",
                                config.model,
                                config.approval_mode.label()
                            ));
                            ui.label(format!(
                                "最长{}秒 · 工具轮数{}",
                                config.timeout_seconds,
                                if config.max_tool_rounds == 0 {
                                    "不主动限制".into()
                                } else {
                                    config.max_tool_rounds.to_string()
                                }
                            ));
                            ui.label(config.allowed_tools.join("、"));
                            for (name, actions) in &config.allowed_actions {
                                ui.label(format!("{name} 的动作：{}", actions.join("、")));
                            }
                        });
                } else {
                    ui.label(RichText::new("此历史记录未保存配置快照").small().weak());
                }
                if let Some(config) = &record.config {
                    if config.truncated {
                        ui.label("配置显示已省略部分较长条目，原配置仍保留。");
                    }
                }
                if !record.detail.is_empty() {
                    ui.label(&record.detail);
                }
                let intents = self
                    .composer_platform
                    .reply_audio_controls(ui, &record.source);
                self.composer_intents(intents);
            });
        let source = record.source.clone();
        let portrait = record
            .config
            .as_ref()
            .map(|config| avatars::Descriptor {
                name: config.assistant_name.clone(),
                emoji: if config.assistant_avatar == "🪶" {
                    String::new()
                } else {
                    config.assistant_avatar.clone()
                },
                path: config.assistant_avatar_path.clone(),
            })
            .unwrap_or_else(|| avatars::Descriptor {
                name: "子智能体".into(),
                ..Default::default()
            });
        let mut portrait_requests = Vec::new();
        let face = self.avatar_view.face(&portrait, &mut portrait_requests);
        self.history_renderer
            .set_assistant_face(source.clone(), face);
        self.avatar_requests(portrait_requests);
        let running = !record.status.terminal();
        if let Some(position) = self.history_views.open(source.clone(), None) {
            self.dispatch(Action::SaveReadPosition(position));
        }
        self.refresh_child_history(&source);
        self.history_renderer
            .set_reply_audio_enabled(self.state.settings.speak_replies && self.runtime.is_some());
        egui::CentralPanel::default().show(ui, |ui| {
            let intents = self.history_renderer.show(
                ui,
                &mut self.history_views,
                &source,
                running,
                false,
                None,
            );
            self.history_intents(intents);
        });
        if cancel {
            self.dispatch(Action::CancelChild {
                conversation_id: chat_id.clone(),
                id: id.clone(),
            });
        }
        if back {
            self.child_viewer = None;
            self.last_opened_child = None;
        }
    }

    fn memory(&mut self, ui: &mut egui::Ui) {
        if !self.state.capabilities.memory {
            unavailable(ui, "记忆存储尚未接入");
            return;
        }
        ui.horizontal(|ui| {
            if ui
                .add_enabled(!self.state.memory_pending, egui::Button::new("新增记忆"))
                .clicked()
            {
                self.memory_editor = Some(Memory {
                    id: String::new(),
                    text: String::new(),
                    updated_at: 0,
                });
                self.memory_delete = None;
            }
            if self.state.memory_pending {
                ui.spinner();
                ui.label("等待存储确认");
            }
        });
        egui::ScrollArea::vertical()
            .id_salt("memories")
            .show(ui, |ui| {
                let mut save = None;
                let mut cancel_editor = false;
                if let Some(editor) = &mut self.memory_editor {
                    egui::Frame::group(ui.style())
                        .inner_margin(12)
                        .show(ui, |ui| {
                            ui.label(
                                RichText::new(if editor.id.is_empty() {
                                    "新增记忆"
                                } else {
                                    "编辑记忆"
                                })
                                .strong(),
                            );
                            ui.add_enabled(
                                !self.state.memory_pending,
                                TextEdit::multiline(&mut editor.text)
                                    .desired_width(f32::INFINITY)
                                    .desired_rows(5)
                                    .char_limit(64 * 1024),
                            );
                            ui.horizontal(|ui| {
                                if ui
                                    .add_enabled(
                                        !self.state.memory_pending
                                            && !editor.text.trim().is_empty(),
                                        egui::Button::new("保存"),
                                    )
                                    .clicked()
                                {
                                    save = Some(editor.clone());
                                }
                                if ui
                                    .add_enabled(
                                        !self.state.memory_pending,
                                        egui::Button::new("取消"),
                                    )
                                    .clicked()
                                {
                                    cancel_editor = true;
                                }
                            });
                        });
                }
                if cancel_editor {
                    self.memory_editor = None;
                }
                if let Some(memory) = save {
                    if self.dispatch(Action::SaveMemory(memory)) {
                        self.state.memory_pending = true;
                    }
                }
                if self.state.memories.is_empty() {
                    ui.label(RichText::new("暂无记忆，保存后会显示在这里").weak());
                }
                let mut delete = None;
                for memory in &self.state.memories {
                    ui.push_id(&memory.id, |ui| {
                        egui::Frame::group(ui.style())
                            .inner_margin(12)
                            .show(ui, |ui| {
                                ui.set_min_width(ui.available_width());
                                ui.spacing_mut().item_spacing.y = 4.0;
                                let title = memory
                                    .text
                                    .lines()
                                    .find(|line| !line.trim().is_empty())
                                    .unwrap_or("记忆");
                                ui.add(egui::Label::new(RichText::new(title).strong()).truncate());
                                if let Some((_, body)) = memory.text.split_once('\n') {
                                    if !body.trim().is_empty() {
                                        let preview = text_preview(body, 260);
                                        ui.add(egui::Label::new(preview).wrap().selectable(true));
                                    }
                                }
                                ui.horizontal(|ui| {
                                    if ui
                                        .add_enabled(
                                            !self.state.memory_pending,
                                            egui::Button::new("编辑"),
                                        )
                                        .clicked()
                                    {
                                        self.memory_editor = Some(memory.clone());
                                        self.memory_delete = None;
                                    }
                                    if ui
                                        .add_enabled(
                                            !self.state.memory_pending,
                                            egui::Button::new("删除"),
                                        )
                                        .clicked()
                                    {
                                        self.memory_delete = Some(memory.id.clone());
                                    }
                                });
                                if self.memory_delete.as_ref() == Some(&memory.id) {
                                    ui.label("确认删除这条记忆？");
                                    ui.horizontal(|ui| {
                                        if ui
                                            .add_enabled(
                                                !self.state.memory_pending,
                                                egui::Button::new("确认删除"),
                                            )
                                            .clicked()
                                        {
                                            delete = Some(memory.id.clone());
                                        }
                                        if ui
                                            .add_enabled(
                                                !self.state.memory_pending,
                                                egui::Button::new("保留"),
                                            )
                                            .clicked()
                                        {
                                            self.memory_delete = None;
                                        }
                                    });
                                }
                            });
                    });
                }
                if let Some(id) = delete {
                    if self.dispatch(Action::DeleteMemory(id)) {
                        self.state.memory_pending = true;
                    }
                }
            });
    }

    fn files(&mut self, ui: &mut egui::Ui) {
        if !self.state.capabilities.files {
            unavailable(ui, "文件访问尚未接入");
            return;
        }
        ui.label(RichText::new("应用私有工作区").small().weak());
        ui.add(
            egui::Label::new(self.state.directory.display().to_string())
                .wrap()
                .selectable(true),
        );
        ui.horizontal(|ui| {
            let parent = self
                .state
                .directory
                .parent()
                .map(PathBuf::from)
                .filter(|path| path.starts_with(&self.state.workspace));
            if ui
                .add_enabled(
                    parent.is_some() && !self.state.files_pending,
                    egui::Button::new("上一级"),
                )
                .clicked()
            {
                if let Some(parent) = parent {
                    if self.dispatch(Action::ListFiles(parent)) {
                        self.state.files_pending = true;
                    }
                }
            }
            if ui
                .add_enabled(!self.state.files_pending, egui::Button::new("刷新"))
                .clicked()
                && self.dispatch(Action::ListFiles(self.state.directory.clone()))
            {
                self.state.files_pending = true;
            }
            if self.state.files_pending {
                ui.spinner();
            }
        });
        if let Some(preview) = &self.state.preview {
            let mut close = false;
            let mut reference = None;
            ui.horizontal(|ui| {
                ui.add(egui::Label::new(preview.path.display().to_string()).truncate());
                if ui
                    .add_enabled(
                        !self.state.conversation_pending,
                        egui::Button::new("引用到对话"),
                    )
                    .clicked()
                {
                    reference = Some(preview.path.clone());
                }
                close = ui.button("关闭预览").clicked();
            });
            if preview.truncated {
                ui.label(RichText::new("预览已截断，文件本身未修改").small().weak());
            }
            egui::ScrollArea::both()
                .id_salt("file_preview")
                .show(ui, |ui| {
                    ui.add(
                        egui::Label::new(RichText::new(&preview.text).monospace()).selectable(true),
                    );
                });
            if close {
                self.state.preview = None;
            }
            if let Some(path) = reference {
                self.reference_workspace_file(path);
            }
            return;
        }
        if self.state.files.is_empty() && !self.state.files_pending {
            ui.label(RichText::new("此目录为空").weak());
        }
        let mut action = None;
        let mut reference = None;
        egui::ScrollArea::vertical()
            .id_salt(("file_entries", &self.state.directory))
            .auto_shrink([false, false])
            .show_rows(ui, ROW_HEIGHT, self.state.files.len(), |ui, rows| {
                for index in rows {
                    let entry = &self.state.files[index];
                    let label = if entry.is_dir {
                        format!("{} /", entry.name)
                    } else {
                        format!("{}    {}", entry.name, byte_size(entry.size))
                    };
                    ui.push_id(&entry.path, |ui| {
                        ui.horizontal(|ui| {
                            if !entry.is_dir
                                && ui
                                    .add_enabled(
                                        !self.state.conversation_pending,
                                        egui::Button::new("引用").small(),
                                    )
                                    .clicked()
                            {
                                reference = Some(entry.path.clone());
                            }
                            if full_button(ui, label, !self.state.files_pending).clicked() {
                                action = Some(if entry.is_dir {
                                    Action::ListFiles(entry.path.clone())
                                } else {
                                    Action::PreviewFile(entry.path.clone())
                                });
                            }
                        });
                    });
                }
            });
        if let Some(action) = action {
            if self.dispatch(action) {
                self.state.files_pending = true;
            }
        }
        if let Some(path) = reference {
            self.reference_workspace_file(path);
        }
    }

    fn reference_workspace_file(&mut self, path: PathBuf) {
        let Some(path) = path.to_str() else {
            self.state.error("文件路径不能转换为可引用的文字");
            return;
        };
        match self.composer_platform.reference_file(path) {
            Ok(()) => {
                self.state.page = Page::Chat;
                self.child_viewer = None;
                self.last_opened_child = None;
                self.drawer_open = false;
            }
            Err(error) => self.state.error(error),
        }
    }

    fn automation_requests(
        &mut self,
        requests: Vec<crate::controller::automation_runtime::Request>,
    ) {
        use crate::controller::automation_runtime::Request;
        for request in requests {
            let operation = match &request {
                Request::Execute(command) => Some(command.operation.clone()),
                Request::Cancel(operation) => Some(operation.clone()),
                Request::PageVisible(_) => None,
            };
            if !self.dispatch(Action::Automation(request)) {
                let error = "运行时连接已断开，自动化操作未被接收";
                if let Some(operation) = operation {
                    self.automation_view.failed(&operation, error.into());
                } else {
                    self.automation_view.suspend();
                }
                self.state.error(error);
            }
        }
    }
    fn automation_page_changed(&mut self, previous: Page) {
        if previous == Page::Automations && self.state.page != Page::Automations {
            let request = self.automation_view.suspend();
            self.automation_requests(vec![request]);
        }
    }
    fn automation_page(&mut self, ui: &mut egui::Ui) {
        let ready = self.runtime.is_some() && self.state.loaded;
        if !ready {
            unavailable(ui, "自动化运行时正在连接");
            return;
        }
        let requests = self.automation_view.show(ui, ready);
        self.automation_requests(requests);
    }
    fn linux_requests(&mut self, requests: Vec<crate::controller::linux_runtime::Request>) {
        for request in requests {
            let failed = request.clone();
            if !self.dispatch(Action::Linux(request)) {
                self.linux_view
                    .dispatch_failed(&failed, "运行时连接已断开，Linux操作未被接收");
            }
        }
    }
    fn terminal_requests(&mut self, requests: Vec<crate::controller::terminal_runtime::Request>) {
        for request in requests {
            let request_id = request.request_id().to_owned();
            if !self.dispatch(Action::Terminal(request)) {
                self.terminal_workspace.dispatch_failed(
                    &request_id,
                    "运行时连接已断开，终端操作未被接收；命令草稿已保留",
                );
            }
        }
    }
    fn terminal(&mut self, ui: &mut egui::Ui) {
        let requests = self
            .linux_view
            .show(ui, self.runtime.is_some() && self.state.loaded);
        self.linux_requests(requests);
        if !self.state.capabilities.terminal {
            unavailable(ui, "终端运行时尚未接入");
            return;
        }
        let requests = self
            .terminal_workspace
            .show(ui, self.runtime.is_some() && self.state.loaded);
        self.terminal_requests(requests);
    }

    fn usage(&mut self, ui: &mut egui::Ui) {
        if !self.state.capabilities.usage {
            unavailable(ui, "用量账本尚未接入");
            return;
        }
        let requests = self.usage_view.show(ui);
        for request in requests {
            let (id, token) = request.identity();
            let identity = (id.to_owned(), token.to_owned());
            if !self.dispatch(Action::UsageStatistics(request)) {
                self.usage_view
                    .apply(&crate::controller::usage_runtime::Update::Failed {
                        request_id: identity.0,
                        ui_token: identity.1,
                        error: "统计请求未受理，请重试".into(),
                    });
            }
        }
        ui.separator();
        ui.label(RichText::new("逐请求账本详情").strong());
        ui.horizontal(|ui| {
            ui.label(format!("{} 笔用量记录", self.state.usage.len()));
            if ui
                .add_enabled(!self.state.usage_pending, egui::Button::new("刷新"))
                .clicked()
                && self.dispatch(Action::LoadUsage)
            {
                self.state.usage_pending = true;
            }
            if self.state.usage_pending {
                ui.spinner();
            }
        });
        ui.label(
            RichText::new("删除会话后，用量记录仍保留；未上报项不会计为零。")
                .small()
                .weak(),
        );
        if self.state.usage_summary.pending > 0 {
            ui.add(
                egui::Label::new(
                    RichText::new(format!(
                        "{} 笔运行记录未结算（可能仍在运行或被中断）",
                        self.state.usage_summary.pending
                    ))
                    .weak(),
                )
                .wrap(),
            );
        }
        if !self.state.usage_loaded {
            ui.label(if self.state.usage_pending {
                "正在读取用量记录…"
            } else {
                "用量记录尚未读取，请刷新。"
            });
            return;
        }
        if let Some(selected_id) = self.usage_selected.clone() {
            if let Some(record) = self
                .state
                .usage
                .iter()
                .find(|record| record.id == selected_id)
            {
                if ui.button("返回记录列表").clicked() {
                    self.usage_selected = None;
                }
                let conversation = self
                    .state
                    .conversations
                    .iter()
                    .find(|conversation| conversation.id == record.conversation_id);
                egui::ScrollArea::vertical()
                    .id_salt(("usage_detail", &record.id))
                    .show(ui, |ui| {
                        ui.heading(if record.model.is_empty() {
                            "未记录模型"
                        } else {
                            &record.model
                        });
                        match conversation {
                            Some(conversation) => {
                                ui.add(
                                    egui::Label::new(format!("会话：{}", conversation.title))
                                        .wrap(),
                                );
                            }
                            None => {
                                ui.label(
                                    RichText::new("会话当前不可用 · 删除状态请以统计来源证明为准")
                                        .strong(),
                                );
                            }
                        }
                        ui.add(
                            egui::Label::new(format!("会话 ID：{}", record.conversation_id))
                                .wrap()
                                .selectable(true),
                        );
                        ui.add(
                            egui::Label::new(format!("记录 ID：{}", record.id))
                                .wrap()
                                .selectable(true),
                        );
                        ui.label(format!("记录时间：{}（Unix 毫秒）", record.at));
                        ui.separator();
                        egui::Grid::new("usage_tokens")
                            .num_columns(2)
                            .spacing(egui::vec2(16.0, 12.0))
                            .show(ui, |ui| {
                                for (label, value) in [
                                    ("输入 token", record.prompt_tokens),
                                    ("输出 token", record.completion_tokens),
                                    ("思考 token", record.reasoning_tokens),
                                    ("缓存 token", record.cached_tokens),
                                ] {
                                    ui.label(label);
                                    ui.label(token_value(value));
                                    ui.end_row();
                                }
                            });
                        ui.separator();
                        if record.pending {
                            ui.add(
                                egui::Label::new(
                                    RichText::new("运行记录未结算（可能仍在运行或被中断）")
                                        .strong(),
                                )
                                .wrap(),
                            );
                        }
                        match &record.error {
                            Some(error) => {
                                ui.label(RichText::new("本次请求记录了错误").strong());
                                ui.add(
                                    egui::Label::new(if error.is_empty() {
                                        "未提供错误详情"
                                    } else {
                                        error
                                    })
                                    .wrap()
                                    .selectable(true),
                                );
                            }
                            None if !record.pending => {
                                ui.label(RichText::new("本次请求未记录错误").weak());
                            }
                            None => {}
                        }
                    });
                return;
            }
            self.usage_selected = None;
        }
        for (label, summary) in [
            ("输入", &self.state.usage_summary.prompt),
            ("输出", &self.state.usage_summary.completion),
            ("思考", &self.state.usage_summary.reasoning),
            ("缓存", &self.state.usage_summary.cached),
        ] {
            ui.add(egui::Label::new(usage_total(label, summary)).wrap());
        }
        ui.separator();
        if self.state.usage.is_empty() {
            ui.label(RichText::new("暂无用量记录").weak());
            return;
        }
        egui::ScrollArea::vertical()
            .id_salt("usage_records")
            .auto_shrink([false, false])
            .show_rows(ui, ROW_HEIGHT, self.state.usage.len(), |ui, rows| {
                for index in rows {
                    let record = &self.state.usage[index];
                    let deleted = !self
                        .state
                        .conversations
                        .iter()
                        .any(|conversation| conversation.id == record.conversation_id);
                    let model = if record.model.is_empty() {
                        "未记录模型"
                    } else {
                        &record.model
                    };
                    let marker = if deleted {
                        " · 会话当前不可用"
                    } else {
                        ""
                    };
                    let pending = if record.pending { " · 未结算" } else { "" };
                    let error = if record.error.is_some() {
                        " · 有错误"
                    } else {
                        ""
                    };
                    let label = format!(
                        "{model}{pending}{marker}{error} · 输入 {} / 输出 {}",
                        token_value(record.prompt_tokens),
                        token_value(record.completion_tokens)
                    );
                    ui.push_id(&record.id, |ui| {
                        if full_button(ui, label, true)
                            .on_hover_text("查看完整用量记录")
                            .clicked()
                        {
                            self.usage_selected = Some(record.id.clone());
                        }
                    });
                }
            });
    }

    fn platform_permissions(&mut self, ui: &mut egui::Ui) {
        use crate::platform::permissions::{OperationKey, PermissionKind};
        ui.heading("系统权限");
        for (kind, label) in [
            (PermissionKind::Notifications, "通知"),
            (PermissionKind::Microphone, "麦克风"),
            (PermissionKind::LocalNetwork, "局域网"),
        ] {
            ui.horizontal_wrapped(|ui| {
                let pending = self.permission_pending.contains_key(&kind);
                if ui
                    .add_enabled(
                        !pending && self.runtime.is_some(),
                        egui::Button::new(format!("请求{label}权限")),
                    )
                    .clicked()
                {
                    let operation = OperationKey {
                        id: crate::controller::unique_id(),
                        conversation_id: None,
                        generation: 1,
                    };
                    if self.dispatch(Action::RequestPlatformPermission {
                        operation: operation.clone(),
                        kinds: vec![kind],
                    }) {
                        self.permission_pending.insert(kind, operation);
                    }
                }
                if pending {
                    ui.spinner();
                    ui.label("等待系统返回");
                } else if let Some(status) = self.permission_status.get(&kind) {
                    ui.label(status);
                }
            });
        }
    }
}

impl eframe::App for RustApplication {
    fn logic(&mut self, context: &egui::Context, _frame: &mut eframe::Frame) {
        self.poll_events(context);
        self.settings_about_tick(context);
        self.settings_onboarding_tick(context);
        self.navigation_tick(context);
    }

    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        self.conversation_view_lease.rendered = None;
        self.todo_sheet(ui);
        self.file_transfer_status(ui);
        let context = ui.ctx().clone();
        self.settings_leave_dialog(&context);
        let motion_scale = self.motion_scale();
        self.history_renderer.set_motion_scale(motion_scale);
        self.turn_navigation.set_motion_scale(motion_scale);
        self.settings_performance_visibility();
        self.history_renderer
            .set_behavior(&self.state.settings.behavior);
        self.apply_theme(&context);
        let frame_bounds = ui.available_rect_before_wrap();
        let mobile = frame_bounds.width() < 720.0
            && self.state.page == Page::Chat
            && self.child_viewer.is_none();
        #[cfg(target_os = "android")]
        if let Some(view) = &mut self.window_insets {
            let [left, top, right, bottom] = view.points(&context);
            for (size, panel) in [
                (left, egui::Panel::left("android_safe_left")),
                (right, egui::Panel::right("android_safe_right")),
                (top, egui::Panel::top("android_safe_top")),
                (bottom, egui::Panel::bottom("android_safe_bottom")),
            ] {
                if size > 0.0 {
                    panel
                        .exact_size(size)
                        .resizable(false)
                        .show_separator_line(!mobile)
                        .frame(egui::Frame::NONE.fill(ui.visuals().panel_fill))
                        .show(ui, |_| {});
                }
            }
        }
        let mut approval_decision = None;
        if let Some(request) = &self.state.approval {
            egui::Panel::bottom("tool_approval").show(ui, |ui| {
                ui.heading(format!("工具审批：{}", request.call.name));
                let title = self
                    .state
                    .conversations
                    .iter()
                    .find(|conversation| conversation.id == request.conversation_id)
                    .map(|conversation| conversation.title.as_str())
                    .unwrap_or("当前任务");
                ui.label(format!("会话：{title} · 只批准本次调用"));
                if let Some(title) = &request.child_title {
                    ui.add(egui::Label::new(format!("子任务：{title}")).wrap());
                }
                if let Some(path) = &request.path {
                    ui.add(egui::Label::new(format!("路径：{path}")).wrap());
                }
                egui::CollapsingHeader::new(format!(
                    "查看完整参数（{} 字节）",
                    request.call.arguments.len()
                ))
                .id_salt(("approval_arguments", &request.run_id, &request.id))
                .show(ui, |ui| {
                    egui::ScrollArea::vertical()
                        .max_height(180.0)
                        .show(ui, |ui| {
                            ui.add(
                                egui::Label::new(&request.call.arguments)
                                    .wrap()
                                    .selectable(true),
                            );
                        });
                });
                ui.push_id(("approval_buttons", &request.run_id, &request.id), |ui| {
                    ui.horizontal(|ui| {
                        for (label, approved) in [("允许本次", true), ("拒绝本次", false)] {
                            if ui
                                .add_enabled(
                                    self.approval_reply_pending
                                        .as_ref()
                                        .is_none_or(|(run, id)| {
                                            run != &request.run_id || id != &request.id
                                        })
                                        && !self.state.runs.values().any(|run| {
                                            run.key.run_id == request.run_id && run.cancelling
                                        }),
                                    egui::Button::new(label),
                                )
                                .clicked()
                            {
                                approval_decision = Some(Action::ResolveApproval {
                                    run_id: request.run_id.clone(),
                                    child_id: request.child_id.clone(),
                                    approval_id: request.id.clone(),
                                    approved,
                                });
                            }
                        }
                        if self.approval_reply_pending.is_some() {
                            ui.spinner();
                        }
                        ui.label(RichText::new("审批自发出起 5 分钟后过期").small().weak());
                    });
                });
            });
        }
        if let Some(action) = approval_decision {
            let identity = match &action {
                Action::ResolveApproval {
                    run_id,
                    approval_id,
                    ..
                } => Some((run_id.clone(), approval_id.clone())),
                _ => None,
            };
            if self.dispatch(action) {
                self.approval_reply_pending = identity;
            }
        }
        let narrow = ui.available_width() < 720.0;
        let mobile = narrow && mobile;
        let skin = mobile_chat::Skin::from_app(self, ui);
        let viewport = ui.available_rect_before_wrap();
        let mut shell = viewport;
        shell.min.y = frame_bounds.min.y;
        if self.state.approval.is_none() {
            shell.max.y = frame_bounds.max.y;
        }
        let mut home = narrow.then(|| {
            self.drawer_motion.update(
                &context,
                viewport,
                self.state.page == Page::Chat,
                motion_scale,
                &mut self.drawer_open,
            );
            drawer::home_layer(
                ui,
                viewport,
                shell,
                self.drawer_motion.progress(),
                skin.drawer,
            )
        });
        if !narrow {
            self.drawer_motion = drawer::DrawerMotion::default();
        }
        let ui = home.as_mut().unwrap_or(ui);
        if mobile {
            skin.background(ui, shell);
            egui::Panel::top("mobile_chat_header")
                .exact_size(60.0)
                .resizable(false)
                .show_separator_line(false)
                .frame(egui::Frame::NONE)
                .show(ui, |ui| self.mobile_header(ui, skin));
        } else {
            egui::Panel::top("app_header")
                .frame(
                    egui::Frame::NONE
                        .fill(ui.visuals().panel_fill)
                        .inner_margin(12),
                )
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        if narrow && ui.button("菜单").clicked() {
                            self.drawer_open = !self.drawer_open;
                            context.request_repaint();
                        }
                        #[cfg(target_os = "android")]
                        let foreground = crate::platform::android::activity_visible();
                        #[cfg(not(target_os = "android"))]
                        let foreground = true;
                        if self.state.page != Page::Chat || !self.chat_portrait(ui, 34.0) {
                            self.bundled_assets
                                .show_icon(ui, self.state.running, foreground);
                        }
                        self.avatar_view
                            .set_brand(self.bundled_assets.cached_icon());
                        ui.label(RichText::new(self.state.page.label()).size(20.0).strong());
                        ui.menu_button("开源许可", |ui| self.bundled_assets.show_licenses(ui));
                        if !self.state.loaded {
                            if self.runtime.is_some() {
                                if self
                                    .state
                                    .notice
                                    .as_ref()
                                    .is_some_and(|notice| notice.error)
                                {
                                    if ui.button("重试读取").clicked() {
                                        self.state.notice = None;
                                        self.dispatch(Action::Load);
                                    }
                                } else {
                                    ui.spinner();
                                    ui.label(RichText::new("正在读取数据").small().weak());
                                }
                            } else {
                                ui.label(RichText::new("运行时未连接").small().weak());
                            }
                        }
                        if self.state.page == Page::Chat && !self.state.running {
                            ui.with_layout(
                                egui::Layout::right_to_left(egui::Align::Center),
                                |ui| {
                                    ui.menu_button("更多", |ui| {
                                        if ui.button("任务清单").clicked() {
                                            self.todos_open = true;
                                            ui.close();
                                        }
                                        ui.label(RichText::new("查看子智能体").strong());
                                        let records =
                                            self.state.selected_conversation.as_ref().and_then(
                                                |id| {
                                                    self.state
                                                        .children
                                                        .get(id)
                                                        .map(|records| (id, records))
                                                },
                                            );
                                        if let Some((chat_id, records)) =
                                            records.filter(|(_, records)| !records.is_empty())
                                        {
                                            egui::ScrollArea::vertical().max_height(280.0).show(
                                                ui,
                                                |ui| {
                                                    for child in records {
                                                        if full_button(
                                                            ui,
                                                            &format!(
                                                                "{} · {}",
                                                                child.name,
                                                                child.status.label()
                                                            ),
                                                            true,
                                                        )
                                                        .clicked()
                                                        {
                                                            self.child_viewer = Some((
                                                                chat_id.clone(),
                                                                child.id().to_owned(),
                                                            ));
                                                            ui.close();
                                                        }
                                                    }
                                                },
                                            );
                                        } else {
                                            ui.label(
                                                RichText::new("当前会话还没有子智能体").weak(),
                                            );
                                        }
                                    });
                                },
                            );
                        }
                    });
                });
        }
        notice::show(ui, &mut self.state);
        let navigation = if narrow {
            let progress = self.drawer_motion.progress();
            drawer::overlay(&context, viewport, progress, skin.drawer, |ui| {
                self.mobile_drawer(ui, skin)
            })
        } else {
            egui::Panel::left("app_drawer")
                .exact_size(248.0)
                .resizable(false)
                .frame(drawer::sheet_frame(ui.visuals()))
                .show(ui, |ui| self.draw_drawer(ui))
                .inner
        };
        if let Some(navigation) = navigation {
            self.navigate(navigation, narrow);
        }
        if self.state.page != Page::Terminal {
            crate::platform::terminal_keys::invalidate();
        }
        let mut content = egui::CentralPanel::default();
        if mobile {
            content = content.frame(egui::Frame::NONE);
        }
        content.show(ui, |ui| match self.state.page {
            Page::Chat => self.chat(ui, mobile.then_some(skin)),
            Page::Conversations => self.conversations_page(ui),
            Page::Memory => self.memory(ui),
            Page::Files => self.files(ui),
            Page::Terminal => {
                self.legacy_archive_panel(ui, crate::legacy_process_browser::Kind::Terminal);
                self.terminal(ui);
            }
            Page::Settings => self.settings(ui),
            Page::Usage => self.usage(ui),
            Page::Assistants => self.assistants(ui),
            Page::Skills => self.skills(ui),
            Page::Plugins => self.plugins(ui),
            Page::Mcp => self.mcp(ui),
            Page::Automations => self.automation_page(ui),
            Page::Schedules => self.schedules_page(ui),
            Page::Jobs => self.jobs_page(ui),
        });
        if narrow {
            drawer::paint_home_corners(ui, shell, self.drawer_motion.progress(), skin.drawer);
        }
        self.avatar_dialog(&context);
        self.subagent_dialogs(&context);
        self.schedule_dialog(&context);
        self.sync_conversation_viewing(&context);
        if !self.state.runs.is_empty()
            || self.history_views.has_pending()
            || self.job_view.pending.is_some()
            || !self.permission_pending.is_empty()
            || self.composer_platform.operation_pending()
            || self.terminal_workspace.operation_pending()
            || self.automation_view.operation_pending()
            || self.linux_view.operation_pending()
            || (self.state.page == Page::Jobs
                && self
                    .job_view
                    .records
                    .iter()
                    .any(|record| record.status.active()))
            || self.state.running
            || self.state.settings_pending
            || self.state.memory_pending
            || self.state.skills_pending
            || self.state.plugins_pending
            || self.state.mcp_pending
            || self.model_fetch_id.is_some()
            || self.state.files_pending
            || self.state.terminal_pending
            || self.state.usage_pending
            || self.state.conversation_delete_pending.is_some()
        {
            context.request_repaint_after(Duration::from_millis(100));
        }
    }
}

fn gray(value: u8) -> Color32 {
    Color32::from_gray(value)
}

fn full_button(ui: &mut egui::Ui, text: impl Into<String>, enabled: bool) -> egui::Response {
    let width = ui.available_width();
    ui.add_enabled(
        enabled,
        egui::Button::new(text.into())
            .truncate()
            .min_size(egui::vec2(width, ROW_HEIGHT)),
    )
}

fn full_selectable(ui: &mut egui::Ui, text: impl Into<String>, selected: bool) -> egui::Response {
    let width = ui.available_width();
    ui.add_sized(
        [width, ROW_HEIGHT],
        egui::Button::new(text.into()).truncate().selected(selected),
    )
}

fn text_field(ui: &mut egui::Ui, label: &str, text: &mut String, hint: &str, password: bool) {
    ui.label(label);
    ui.push_id(label, |ui| {
        ui.add_sized(
            [ui.available_width(), ROW_HEIGHT],
            TextEdit::singleline(text)
                .hint_text(hint)
                .password(password)
                .char_limit(8192),
        );
    });
}

fn unavailable(ui: &mut egui::Ui, text: &str) {
    ui.add(egui::Label::new(RichText::new(text).weak()).wrap());
}

fn text_preview(text: &str, limit: usize) -> String {
    let mut characters = text.chars();
    let mut preview: String = characters.by_ref().take(limit).collect();
    if characters.next().is_some() {
        preview.push('…');
    }
    preview
}

fn byte_size(bytes: u64) -> String {
    if bytes < 1024 {
        format!("{bytes} B")
    } else if bytes < 1024 * 1024 {
        format!("{:.1} KiB", bytes as f64 / 1024.0)
    } else if bytes < 1024 * 1024 * 1024 {
        format!("{:.1} MiB", bytes as f64 / (1024.0 * 1024.0))
    } else {
        format!("{:.1} GiB", bytes as f64 / (1024.0 * 1024.0 * 1024.0))
    }
}

fn token_value(value: Option<i32>) -> String {
    match value {
        Some(value) if value >= 0 => value.to_string(),
        Some(value) => format!("{value}（无效上报）"),
        None => "未上报".to_owned(),
    }
}

fn usage_total(label: &str, summary: &TokenUsage) -> String {
    let mut text = if summary.reported == 0 {
        format!("{label}：无已上报合计")
    } else {
        format!(
            "{label}：已上报合计 {} token（{} 笔）",
            summary.known_total, summary.reported
        )
    };
    if summary.unreported > 0 {
        text.push_str(&format!(" · {} 笔未上报", summary.unreported));
    }
    if summary.invalid > 0 {
        text.push_str(&format!(" · {} 笔无效上报", summary.invalid));
    }
    text
}
