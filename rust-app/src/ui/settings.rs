//! The original Kotlin settings sections, over typed actor-owned contracts.
//! Form edits remain local until exact Store confirmation. No platform query,
//! decryption, network request or filesystem work happens while drawing.
mod about;
mod appearance;
mod audio;
mod capability;
pub(super) mod controls;
mod conversation;
mod data;
mod model;
mod network;
mod onboarding;
mod performance;
mod profile;

use super::*;
use crate::platform::secrets::SettingsSecretTarget as Target;

#[derive(Clone, Copy, Default, PartialEq, Eq)]
pub(super) enum Section {
    #[default]
    Root,
    Model,
    Conversation,
    Subagent,
    Appearance,
    Network,
    Data,
    Capability,
    Tools,
    Profile,
    Performance,
    Onboarding,
    About,
}
impl Section {
    fn title(self) -> &'static str {
        match self {
            Self::Root => "设置",
            Self::Model => "模型与生成",
            Self::Conversation => "对话配置",
            Self::Subagent => "子任务（Subagent）",
            Self::Appearance => "外观",
            Self::Network => "网络与语音",
            Self::Data => "数据",
            Self::Capability => "能力",
            Self::Tools => "工具",
            Self::Profile => "用户画像",
            Self::Performance => "性能",
            Self::Onboarding => "新手引导",
            Self::About => "关于",
        }
    }
}
#[derive(Default)]
pub(super) struct SettingsView {
    section: Section,
    draft_stamp: Option<crate::state::SettingsStamp>,
    pub(super) pending: Option<(String, crate::state::SettingsStamp, Settings)>,
    notice: Option<String>,
    capability: capability::CapabilityView,
    audio: audio::SettingsAudio,
    profile: profile::ProfileView,
    performance: performance::PerformanceView,
    tool_query: String,
    tool_tab: u8,
    pub(super) subagent_choosing_model: bool,
    new_header_name: String,
    new_header_value: String,
    onboarding_step: usize,
    onboarding_considered: bool,
    onboarding_active: bool,
    legacy_upgrade: Option<Result<(), String>>,
    about: about::View,
    onboarding: onboarding::View,
    onboarding_finishing: bool,
}
impl SettingsView {
    pub(super) fn disconnected(&mut self) {
        self.pending = None;
        self.draft_stamp = None;
        self.capability.disconnected();
        self.audio.disconnected();
        self.profile.disconnected();
        self.performance.disconnected();
        self.about.disconnected();
        self.onboarding.reset();
        self.onboarding_finishing = false;
    }
}

impl RustApplication {
    pub(super) fn settings_autosaves(&self) -> bool {
        !matches!(
            self.settings_view.section,
            Section::Root | Section::Model | Section::Onboarding
        )
    }
    pub(super) fn settings_title(&self) -> &'static str {
        if self.settings_view.legacy_upgrade.is_some() {
            "迁移旧版数据"
        } else {
            self.settings_view.section.title()
        }
    }

    pub(super) fn settings_first_loaded(
        &mut self,
        settings: &Settings,
        first_run_legacy_data: &Result<bool, String>,
    ) {
        if self.settings_view.onboarding_considered {
            return;
        }
        self.settings_view.onboarding_considered = true;
        self.settings_view.legacy_upgrade = match first_run_legacy_data {
            Ok(true) => Some(Ok(())),
            Ok(false) => None,
            Err(error) => Some(Err(error.clone())),
        };
        if self.settings_view.legacy_upgrade.is_some() {
            self.state.page = Page::Settings;
        } else {
            self.settings_begin_onboarding(settings.behavior.onboarding_seen);
        }
    }

    fn settings_begin_onboarding(&mut self, seen: bool) {
        if !seen {
            self.settings_view.onboarding_active = true;
            self.settings_view.section = Section::Onboarding;
            self.settings_view.onboarding_step = 0;
            self.state.page = Page::Settings;
        }
    }

    pub(super) fn settings(&mut self, ui: &mut egui::Ui) {
        if self.settings_view.legacy_upgrade.is_some() {
            self.settings_legacy_upgrade(ui);
            return;
        }
        let editable = self.state.loaded
            && self.state.capabilities.settings
            && !self.state.settings_pending
            && self.model_choice_pending.is_none();
        if !self.state.capabilities.settings {
            unavailable(ui, "设置存储尚未就绪");
        }
        let section = self.settings_view.section;
        ui.spacing_mut().item_spacing.y = 0.0;
        let clean = editable && self.settings_draft == self.state.settings;
        let mut clear_target = None;
        egui::ScrollArea::vertical()
            .id_salt(("settings_section", section as usize))
            .max_height(if section == Section::Model {
                (ui.available_height() - 64.0).max(80.0)
            } else {
                ui.available_height()
            })
            .show(ui, |ui| match section {
                Section::Root => self.settings_root(ui),
                Section::Model => {
                    ui.add_enabled_ui(editable, |ui| {
                        clear_target = self.settings_model(ui, clean);
                    });
                }
                Section::Conversation => {
                    ui.add_enabled_ui(editable, |ui| {
                        conversation::draw(ui, &mut self.settings_draft)
                    });
                }
                Section::Subagent => {
                    ui.add_enabled_ui(editable, |ui| {
                        self.subagent_settings(ui);
                    });
                }
                Section::Appearance => {
                    ui.add_enabled_ui(editable, |ui| {
                        if appearance::draw(ui, &mut self.settings_draft, clean) {
                            self.settings_reset_appearance();
                        }
                    });
                }
                Section::Network => {
                    ui.add_enabled_ui(editable, |ui| {
                        clear_target = network::draw(ui, &mut self.settings_draft, clean);
                    });
                    let requests = self.settings_view.audio.show(
                        ui,
                        self.runtime.is_some(),
                        clean && self.state.settings.speak_replies,
                    );
                    for request in requests {
                        // Parent installs one AudioGate/scoped Voice router.
                        self.settings_audio_request(request);
                    }
                }
                Section::Data => self.settings_data(ui),
                Section::Capability => self.settings_capabilities(ui, clean, editable),
                Section::Tools => self.settings_tools(ui),
                Section::Profile => self.settings_profile(ui, editable),
                Section::Performance => self.settings_performance(ui),
                Section::Onboarding => self.settings_onboarding(ui, editable, clean),
                Section::About => self.settings_about(ui),
            });
        if section == Section::Model {
            self.settings_save_bar(ui, editable);
        } else if self.state.settings_pending {
            ui.spinner();
            ui.label("等待存储确认");
        }
        if let Some(notice) = &self.settings_view.notice {
            ui.colored_label(ui.visuals().error_fg_color, notice);
            if section == Section::Onboarding
                && ui
                    .add_enabled(editable, egui::Button::new("重新读取引导草稿"))
                    .clicked()
            {
                self.settings_view.onboarding.reset();
                self.settings_view.notice = None;
            }
        }
        if let Some(target) = clear_target {
            if self.dispatch(Action::ClearSettingsSecret { target }) {
                self.state.settings_pending = true;
            }
        }
        self.settings_legacy_confirmation(ui);
        if !matches!(
            section,
            Section::Root | Section::Model | Section::Onboarding
        ) && editable
            && self.settings_draft != self.state.settings
            && !ui.input(|input| input.pointer.any_down())
            && self.settings_view.pending.is_none()
        {
            self.settings_save_draft();
        }
    }
    fn settings_root(&mut self, ui: &mut egui::Ui) {
        if let Some(request) = self
            .settings_view
            .capability
            .initial_request(self.runtime.is_some())
        {
            let operation = capability::operation(&request).clone();
            if !self.dispatch(Action::Capability(request)) {
                self.settings_view.capability.dispatch_failed(&operation);
            }
        }
        let skin = mobile_chat::Skin::from_app(self, ui);
        let provider = self
            .state
            .settings
            .active_provider_id
            .as_ref()
            .and_then(|id| {
                self.state
                    .settings
                    .providers
                    .iter()
                    .find(|provider| &provider.id == id)
            });
        let model = provider
            .map(|provider| provider.selected_model.as_str())
            .unwrap_or(&self.state.settings.model);
        let (tier, checks) = self.settings_view.capability.summary();
        controls::card(ui, 16).show(ui, |ui| {
            ui.set_width(ui.available_width());
            let start = ui.cursor().min;
            let width = ui.available_width();
            let mut child = ui.new_child(
                egui::UiBuilder::new()
                    .max_rect(egui::Rect::from_min_size(
                        start + egui::vec2(50.0, 0.0),
                        egui::vec2(width - 128.0, 1000.0),
                    ))
                    .layout(egui::Layout::top_down(egui::Align::Min)),
            );
            child.spacing_mut().item_spacing.y = 0.0;
            controls::text(&mut child, "当前能力", 18.0, 25.0, skin.text);
            controls::description(&mut child, &checks);
            let (header, _) = ui.allocate_exact_size(
                egui::vec2(width, child.min_rect().height().max(38.0)),
                egui::Sense::hover(),
            );
            let icon = egui::Rect::from_center_size(
                header.left_center() + egui::vec2(19.0, 0.0),
                egui::vec2(38.0, 38.0),
            );
            ui.painter()
                .rect_filled(icon, 12, skin.accent.gamma_multiply(0.14));
            self.mobile_icons.draw(
                ui,
                icon.center(),
                20.0,
                mobile_chat::Icon::ApprovalAsk,
                skin.accent,
            );
            let badge = egui::Rect::from_center_size(
                header.right_center() - egui::vec2(35.0, 0.0),
                egui::vec2(70.0, 26.0),
            );
            ui.painter()
                .rect_filled(badge, 13, skin.ink.gamma_multiply(0.15));
            ui.painter()
                .circle_filled(badge.left_center() + egui::vec2(13.0, 0.0), 3.0, skin.ink);
            mobile_chat::single_line(
                ui,
                &tier,
                egui::Rect::from_min_max(badge.min + egui::vec2(20.0, 0.0), badge.max),
                skin.font(11.0),
                skin.ink,
            );
            ui.add_space(8.0);
            controls::description(
                ui,
                &if model.is_empty() {
                    "模型：还没配置接口与密钥".into()
                } else {
                    format!("模型：{model}")
                },
            );
        });
        for (title, rows) in [
            (
                "模型与对话",
                &[
                    (Section::Model, None, "模型与生成", "接口、模型与采样"),
                    (
                        Section::Conversation,
                        None,
                        "对话配置",
                        "输入、运行、失败重试与显示",
                    ),
                    (
                        Section::Subagent,
                        None,
                        "子任务（Subagent）",
                        "模板、助手、模型、工具与任务委派",
                    ),
                    (Section::Appearance, None, "外观", "主题、配色与排版"),
                ][..],
            ),
            (
                "能力与连接",
                &[
                    (
                        Section::Root,
                        Some(Page::Automations),
                        "UI自动化",
                        "虚拟屏、预览与边缘光",
                    ),
                    (
                        Section::Capability,
                        None,
                        "权限",
                        "无障碍、Shizuku、root 与悬浮窗",
                    ),
                    (Section::Tools, None, "工具", "工具、Skill 与 MCP 的管理"),
                    (
                        Section::Network,
                        None,
                        "网络与语音",
                        "搜索走哪里、回复读不读出来",
                    ),
                ][..],
            ),
            (
                "数据与关于",
                &[
                    (Section::Data, None, "数据", "记忆、助手与会话文件"),
                    (
                        Section::Root,
                        Some(Page::Usage),
                        "用量",
                        "tokens · 30 日柱状 · 按模型",
                    ),
                    (
                        Section::Performance,
                        None,
                        "性能",
                        "首字延迟 · 整轮耗时 · 缓存命中 · 输出速度",
                    ),
                    (
                        Section::Onboarding,
                        None,
                        "新手引导",
                        "再走一遍：供应商 · 模型 · 权限",
                    ),
                    (Section::About, None, "关于", "版本、隐私与开源许可"),
                ][..],
            ),
        ] {
            controls::group_header(ui, title);
            let mut target = None;
            controls::card(ui, 0).show(ui, |ui| {
                ui.set_width(ui.available_width());
                for &(section, page, label, summary) in rows {
                    ui.push_id(label, |ui| {
                        if controls::link(ui, label, summary) {
                            target = Some((section, page));
                        }
                    });
                }
            });
            if let Some((section, page)) = target {
                if let Some(page) = page {
                    self.navigate(Navigation::Page(page), false);
                } else {
                    self.settings_view.section = section;
                    if section == Section::Onboarding {
                        self.settings_view.onboarding_step = 0;
                        self.settings_view.onboarding_active = true;
                        self.settings_view.onboarding.reset();
                    }
                }
            }
        }
        ui.add_space(24.0);
    }
    fn settings_model(&mut self, ui: &mut egui::Ui, can_clear: bool) -> Option<Target> {
        let selected = self
            .settings_draft
            .active_provider_id
            .as_ref()
            .and_then(|id| {
                self.settings_draft
                    .providers
                    .iter()
                    .find(|provider| &provider.id == id)
            });
        let selected_label = selected.map_or("默认连接", |provider| provider.name.as_str());
        egui::ComboBox::from_id_salt("active_provider")
            .width(ui.available_width())
            .selected_text(selected_label)
            .show_ui(ui, |ui| {
                let settings = &mut self.settings_draft;
                ui.selectable_value(&mut settings.active_provider_id, None, "默认连接");
                for provider in &settings.providers {
                    ui.selectable_value(
                        &mut settings.active_provider_id,
                        Some(provider.id.clone()),
                        &provider.name,
                    );
                }
            });
        controls::section(ui, "接口配置");
        egui::ComboBox::from_id_salt("settings_default_protocol")
            .width(ui.available_width())
            .selected_text(self.settings_draft.protocol.label())
            .show_ui(ui, |ui| {
                for protocol in Protocol::ALL {
                    ui.selectable_value(
                        &mut self.settings_draft.protocol,
                        protocol,
                        protocol.label(),
                    );
                }
            });
        ui.add_space(12.0);
        controls::field(
            ui,
            "默认接口地址",
            &mut self.settings_draft.endpoint,
            "https://api.deepseek.com/v1",
            false,
        );
        ui.add_space(8.0);
        controls::description(ui, "例如 https://api.deepseek.com/v1");
        row(
            ui,
            &mut self.settings_draft.behavior.allow_insecure_tls,
            "允许自签名证书",
            "本机和局域网的自建 HTTPS 接口可使用此选项。",
        );
        controls::field(
            ui,
            "API Key",
            &mut self.settings_draft.api_key,
            "留空保留已存凭据",
            true,
        );
        ui.add_space(8.0);
        controls::description(ui, "Key 由系统 Keystore 加密后保存在这台手机上。");
        let mut clear = None;
        if ui
            .add_enabled(can_clear, egui::Button::new("清除已保存的默认 Key"))
            .clicked()
        {
            clear = Some(Target::ApiKey);
        }
        self.provider_settings(ui);
        model::context_and_behavior(ui, &mut self.settings_draft);
        ui.collapsing("采样参数", |ui| {
            model::sampling(ui, &mut self.settings_draft)
        });
        self.advanced_generation(ui);
        ui.collapsing("默认系统提示词", |ui| {
            ui.add(
                TextEdit::multiline(&mut self.settings_draft.system_prompt)
                    .desired_rows(6)
                    .desired_width(f32::INFINITY)
                    .char_limit(64 * 1024),
            );
        });
        self.settings_headers(ui, can_clear, &mut clear);
        ui.collapsing("清除供应商凭据", |ui| {
            for provider in &self.state.settings.providers {
                if full_button(ui, format!("清除 {} 的Key", provider.name), can_clear).clicked()
                {
                    clear = Some(Target::ProviderApiKey(provider.id.clone()));
                }
            }
        });
        clear
    }
    fn settings_headers(&mut self, ui: &mut egui::Ui, can_clear: bool, clear: &mut Option<Target>) {
        ui.collapsing("自定义请求头", |ui| {
            ui.label("随模型请求和模型列表请求发送。名称和值分别填写。");
            for (name, value) in &mut self.settings_draft.request_headers {
                ui.push_id(name, |ui| {
                    ui.label(name);
                    ui.add(
                        TextEdit::singleline(value)
                            .password(true)
                            .desired_width(f32::INFINITY)
                            .char_limit(8192),
                    );
                    if ui
                        .add_enabled(can_clear, egui::Button::new("清除此请求头"))
                        .clicked()
                    {
                        *clear = Some(Target::RequestHeader(name.clone()));
                    }
                });
            }
            text_field(
                ui,
                "新请求头名称",
                &mut self.settings_view.new_header_name,
                "X-Custom-Header",
                false,
            );
            text_field(
                ui,
                "新请求头值",
                &mut self.settings_view.new_header_value,
                "value",
                true,
            );
            let name = self.settings_view.new_header_name.trim();
            let valid = !name.is_empty()
                && name.len() <= 256
                && name
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&b))
                && !self
                    .settings_view
                    .new_header_value
                    .contains(['\r', '\n', '\0']);
            if ui
                .add_enabled(
                    valid && self.settings_draft.request_headers.len() < 64,
                    egui::Button::new("添加请求头"),
                )
                .clicked()
            {
                self.settings_draft.request_headers.insert(
                    name.into(),
                    std::mem::take(&mut self.settings_view.new_header_value),
                );
                self.settings_view.new_header_name.clear();
            }
        });
    }
    fn settings_save_bar(&mut self, ui: &mut egui::Ui, editable: bool) {
        let dirty = self.settings_draft != self.state.settings;
        ui.add_space(8.0);
        ui.separator();
        ui.add_space(6.0);
        ui.horizontal(|ui| {
            if self.state.settings_pending {
                ui.spinner();
            }
            controls::description(
                ui,
                if dirty {
                    "有未保存的修改"
                } else {
                    "已保存"
                },
            );
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let accent = ui.visuals().hyperlink_color;
                let save = egui::Button::new(RichText::new("保存").color(Color32::WHITE))
                    .fill(accent)
                    .min_size(egui::vec2(70.0, 44.0));
                if ui.add_enabled(editable && dirty, save).clicked() {
                    self.settings_save_draft();
                }
                if dirty
                    && ui
                        .add_enabled(editable, egui::Button::new("放弃修改"))
                        .clicked()
                {
                    self.settings_discard_draft();
                }
            });
        });
    }
    pub(super) fn settings_save_draft(&mut self) {
        self.settings_save_value(self.settings_draft.clone());
    }
    pub(super) fn settings_discard_draft(&mut self) {
        self.settings_draft = self.state.settings.clone();
        self.settings_view.draft_stamp = self.state.settings_stamp.clone();
        self.settings_view.notice = None;
    }
    pub(super) fn settings_show_parent(&mut self) {
        self.settings_view.section = match self.settings_view.section {
            Section::Profile => Section::Data,
            _ => Section::Root,
        };
    }
    pub(super) fn settings_save_error(&self) -> Option<&str> {
        self.settings_view.notice.as_deref()
    }
    pub(super) fn settings_back(&mut self) -> bool {
        if self.state.page != Page::Settings {
            return false;
        }
        if self.settings_view.legacy_upgrade.is_some() {
            if self.legacy_pending {
                return true;
            }
            self.settings_view.legacy_upgrade = None;
            self.settings_view.section = Section::Root;
            return false;
        }
        if self.settings_view.onboarding_active && self.settings_view.section == Section::Onboarding
        {
            if self.settings_view.onboarding_step > 0 {
                self.settings_view.onboarding_step -= 1;
            } else if !self.state.settings_pending {
                self.settings_view.onboarding_active = false;
                self.settings_view.onboarding.reset();
                self.settings_show_parent();
            }
            return true;
        }
        if self.settings_view.section != Section::Root {
            self.settings_request_parent();
            return true;
        }
        false
    }
    pub(super) fn settings_save_value(&mut self, settings: Settings) -> bool {
        if self.settings_view.pending.is_some() || self.state.settings_pending {
            return false;
        }
        let Some(expected) = self.settings_view.draft_stamp.clone() else {
            self.state.error("设置版本尚未确认，请等待加载完成");
            return false;
        };
        let request_id = crate::controller::unique_id();
        if !self.dispatch(Action::SaveSettingsFor {
            request_id: request_id.clone(),
            expected: expected.clone(),
            settings: settings.clone(),
        }) {
            return false;
        }
        self.state.settings_request = Some((request_id.clone(), expected.clone()));
        self.state.settings_pending = true;
        self.settings_view.pending = Some((request_id, expected, self.settings_draft.clone()));
        true
    }
    fn settings_reset_appearance(&mut self) {
        let Some(expected) = self.settings_view.draft_stamp.clone() else {
            return;
        };
        let request_id = crate::controller::unique_id();
        if self.dispatch(Action::ResetSettingsFor {
            request_id: request_id.clone(),
            expected: expected.clone(),
            scope: crate::state::SettingsResetScope::Appearance,
        }) {
            self.state.settings_request = Some((request_id.clone(), expected.clone()));
            self.state.settings_pending = true;
            self.settings_view.pending = Some((request_id, expected, self.settings_draft.clone()));
        }
    }
    pub(super) fn settings_snapshot_updated(&mut self, snapshot: &crate::state::SettingsSnapshot) {
        if self.state.settings_stamp.as_ref().is_some_and(|stamp| {
            stamp.generation != snapshot.stamp.generation
                || stamp.revision > snapshot.stamp.revision
        }) {
            return;
        }
        if self.settings_view.pending.is_none() && self.settings_draft == self.state.settings {
            self.settings_draft = snapshot.settings.clone();
            self.settings_view.draft_stamp = Some(snapshot.stamp.clone());
        }
    }
    pub(super) fn settings_mutation_finished(
        &mut self,
        request_id: &str,
        expected: &crate::state::SettingsStamp,
        result: &Result<crate::state::SettingsSnapshot, String>,
    ) {
        if !self
            .settings_view
            .pending
            .as_ref()
            .is_some_and(|(id, stamp, _)| id == request_id && stamp == expected)
        {
            return;
        }
        let (_, _, submitted) = self.settings_view.pending.take().unwrap();
        let onboarding_finishing = std::mem::take(&mut self.settings_view.onboarding_finishing);
        match result {
            Ok(snapshot) => {
                if onboarding_finishing && snapshot.settings.behavior.onboarding_seen {
                    self.settings_view.onboarding_active = false;
                    self.settings_view.onboarding.reset();
                    self.settings_view.section = Section::Root;
                    self.state.page = Page::Chat;
                }
                if self.settings_draft == submitted {
                    self.settings_draft = snapshot.settings.clone();
                    self.settings_view.draft_stamp = Some(snapshot.stamp.clone());
                }
                self.settings_view.notice = None;
            }
            Err(error) => {
                self.settings_view.notice = Some(error.clone());
                if self.settings_draft == self.state.settings {
                    self.settings_view.draft_stamp = self.state.settings_stamp.clone();
                }
            }
        }
        self.settings_leave_result(request_id, result.is_ok());
    }
    pub(super) fn settings_audio_request(&mut self, request: audio::Request) {
        let command = match request {
            audio::Request::Test(operation) => ComposerCommand::TestReplyAudio(operation),
            audio::Request::Stop(operation) => ComposerCommand::StopReplyAudio(operation),
        };
        if !self.dispatch(Action::Composer(command)) {
            self.settings_view.audio.dispatch_failed();
        }
    }

    pub(super) fn settings_capability_updated(
        &mut self,
        update: crate::controller::capability_runtime::Update,
    ) {
        if let Some(execution) = self.settings_view.capability.apply(update) {
            if self.settings_draft.execution == self.state.settings.execution {
                self.settings_draft.execution = execution.clone();
            }
            self.state.settings.execution = execution;
        }
    }
    pub(super) fn settings_audio_event(&mut self, event: &ComposerEvent) {
        self.settings_view.audio.receive(event);
    }
    pub(super) fn settings_profile_summary_updated(
        &mut self,
        update: crate::controller::profile_work::Update,
    ) {
        self.settings_view.profile.summary_updated(update);
    }
    pub(super) fn settings_profile_updated(&mut self, update: crate::profile::Update) {
        self.settings_view.profile.apply(update);
    }
    pub(super) fn settings_performance_updated(&mut self, update: crate::performance::Update) {
        self.settings_view.performance.apply(update);
    }
    pub(super) fn settings_performance_visibility(&mut self) {
        let visible = self.runtime.is_some()
            && self.state.page == Page::Settings
            && self.settings_view.section == Section::Performance;
        if let Some(request) = self.settings_view.performance.visibility(visible) {
            let operation = performance::operation(&request).clone();
            if !self.dispatch(Action::Performance(request)) {
                self.settings_view.performance.dispatch_failed(&operation);
            }
        }
    }
    fn settings_capabilities(&mut self, ui: &mut egui::Ui, clean: bool, editable: bool) {
        let requests = self
            .settings_view
            .capability
            .show(ui, self.runtime.is_some(), clean);
        for request in requests {
            let operation = capability::operation(&request).clone();
            if !self.dispatch(Action::Capability(request)) {
                self.settings_view.capability.dispatch_failed(&operation);
            }
        }
        ui.separator();
        ui.add_enabled_ui(editable, |ui| {
            row(
                ui,
                &mut self.settings_draft.behavior.console_while_running,
                "任务运行时显示控制台",
                "运行任务时提供悬浮停止和插话入口。",
            );
            row(
                ui,
                &mut self.settings_draft.behavior.keep_screen_on,
                "前台保持屏幕常亮",
                "仅在JAgent前台时保持屏幕开启。",
            );
        });
    }
    fn settings_tools(&mut self, ui: &mut egui::Ui) {
        controls::choices(
            ui,
            &mut self.settings_view.tool_tab,
            &[(0, "工具"), (1, "技能"), (2, "MCP"), (3, "插件")],
        );
        ui.add_space(16.0);
        match self.settings_view.tool_tab {
            1 => {
                self.skills(ui);
                return;
            }
            2 => {
                self.mcp(ui);
                return;
            }
            3 => {
                self.plugins(ui);
                return;
            }
            _ => {}
        }
        controls::field(
            ui,
            "搜索工具名",
            &mut self.settings_view.tool_query,
            "",
            false,
        );
        ui.add_space(8.0);
        controls::description(ui, &format!("共 {} 个工具", self.state.tool_catalog.len()));
        controls::description(
            ui,
            "全局工具默认不可修改：这里只能看名称、用途和它需要的权限。要按助手关掉某些工具，去那个助手的编辑页。",
        );
        let query = self.settings_view.tool_query.trim().to_ascii_lowercase();
        for tool in &self.state.tool_catalog {
            if !query.is_empty() && !tool.name.to_ascii_lowercase().contains(&query) {
                continue;
            }
            ui.add_space(12.0);
            controls::card(ui, 16).show(ui, |ui| {
                ui.set_width(ui.available_width());
                controls::text(ui, &tool.name, 18.0, 25.0, ui.visuals().text_color());
                controls::description(ui, &text_preview(&tool.description, 96));
                ui.collapsing("参数", |ui| {
                    if let Ok(parameters) = serde_json::to_string_pretty(&tool.parameters) {
                        ui.add(
                            egui::Label::new(RichText::new(parameters).monospace())
                                .wrap()
                                .selectable(true),
                        );
                    }
                });
            });
        }
        ui.add_space(24.0);
    }
    fn settings_profile(&mut self, ui: &mut egui::Ui, editable: bool) {
        let mut requests = Vec::new();
        ui.add_enabled_ui(editable, |ui| {
            requests = self.settings_view.profile.show(
                ui,
                self.runtime.is_some(),
                &mut self.settings_draft,
            );
        });
        for request in requests {
            let operation = profile::operation(&request).clone();
            if !self.dispatch(Action::Profile(request)) {
                self.settings_view.profile.dispatch_failed(&operation);
            }
        }
        let source = self
            .state
            .selected_conversation
            .as_ref()
            .and_then(|id| self.state.conversations.iter().find(|chat| &chat.id == id))
            .map(|chat| (chat.id.clone(), chat.title.clone()));
        let summary_requests = self.settings_view.profile.show_summary(
            ui,
            self.runtime.is_some() && self.state.loaded,
            source
                .as_ref()
                .map(|(id, title)| (id.as_str(), title.as_str())),
        );
        for request in summary_requests {
            if !self.dispatch(Action::ProfileSummary(request.clone())) {
                self.settings_view.profile.summary_dispatch_failed(&request);
            }
        }
    }
    fn settings_performance(&mut self, ui: &mut egui::Ui) {
        let requests = self
            .settings_view
            .performance
            .show(ui, self.runtime.is_some());
        for request in requests {
            let operation = performance::operation(&request).clone();
            if !self.dispatch(Action::Performance(request)) {
                self.settings_view.performance.dispatch_failed(&operation);
            }
        }
        if ui.button("查看已记录的请求用量").clicked() {
            self.navigate(Navigation::Page(Page::Usage), false);
        }
    }
    fn settings_onboarding(&mut self, ui: &mut egui::Ui, editable: bool, clean: bool) {
        let step = self.settings_view.onboarding_step.min(4);
        ui.label(format!("第 {} 步 / 共 5 步", step + 1));
        match step {
            0 => {
                ui.heading("JAgent");
                ui.label("你自己的AI助手，住在你自己的手机里。");
                ui.label("配置模型接口后开始对话，工具能力由你授权。聊天和文件保存在本机，Key由系统Keystore加密保存。");
            }
            1 => {
                ui.add_enabled_ui(editable, |ui| {
                    self.settings_view
                        .onboarding
                        .provider_page(ui, &self.state.settings)
                });
            }
            2 => {
                ui.add_enabled_ui(editable, |ui| {
                    self.settings_view
                        .onboarding
                        .model_page(ui, &self.state.settings);
                });
            }
            3 => self.settings_capabilities(ui, clean, editable),
            _ => {
                ui.heading("准备开始");
                ui.label("保存选择后，可以从聊天页发送第一条消息。");
                self.settings_view.onboarding.summary(ui);
            }
        }
        ui.horizontal(|ui| {
            if ui
                .add_enabled(step > 0, egui::Button::new("上一步"))
                .clicked()
            {
                self.settings_view.onboarding_step -= 1;
            }
            if step < 4
                && ui
                    .add_enabled(editable, egui::Button::new("下一步"))
                    .clicked()
            {
                self.settings_view.onboarding_step += 1;
            }
            if ui
                .add_enabled(
                    editable,
                    egui::Button::new(if step == 4 { "开始使用" } else { "跳过" }),
                )
                .clicked()
            {
                self.settings_finish_onboarding();
                // The ordinary save receipt owns success. No synthetic settings
                // state or conversation is created when this button is clicked.
            }
        });
    }
    fn settings_finish_onboarding(&mut self) {
        if self.settings_view.pending.is_some() || self.state.settings_pending {
            return;
        }
        let Some(expected) = self.state.settings_stamp.clone() else {
            self.state.error("设置版本尚未确认，请等待加载完成");
            return;
        };
        let (settings, clear_secrets) =
            match self.settings_view.onboarding.finish(&self.state.settings) {
                Ok(value) => value,
                Err(error) => {
                    self.settings_view.notice = Some(error);
                    return;
                }
            };
        let request_id = crate::controller::unique_id();
        if self.dispatch(Action::FinishOnboardingFor {
            request_id: request_id.clone(),
            expected: expected.clone(),
            settings: settings.clone(),
            clear_secrets,
        }) {
            self.state.settings_request = Some((request_id.clone(), expected.clone()));
            self.state.settings_pending = true;
            self.settings_view.pending = Some((request_id, expected, self.settings_draft.clone()));
            self.settings_view.onboarding_finishing = true;
        }
    }
    pub(super) fn onboarding_blocks_navigation(&self) -> bool {
        self.state.page == Page::Settings
            && (self.settings_view.legacy_upgrade.is_some()
                || (self.settings_view.onboarding_active
                    && self.settings_view.section == Section::Onboarding))
    }
    pub(super) fn settings_onboarding_tick(&mut self, ctx: &egui::Context) {
        let visible =
            self.state.page == Page::Settings && self.settings_view.section == Section::Onboarding;
        self.settings_view
            .onboarding
            .tick(visible, &self.state.settings, ctx);
    }
    fn settings_about(&mut self, ui: &mut egui::Ui) {
        self.settings_view.about.show(
            ui,
            self.mobile_icons.brand_texture(),
            &mut self.bundled_assets,
        );
    }
    pub(super) fn settings_about_tick(&mut self, ctx: &egui::Context) {
        self.settings_view.about.tick(
            self.state.page == Page::Settings && self.settings_view.section == Section::About,
            ctx,
        );
    }
}

fn row(ui: &mut egui::Ui, value: &mut bool, title: &str, description: &str) {
    controls::row(ui, value, title, description);
}
fn choices<T: Copy + PartialEq>(
    ui: &mut egui::Ui,
    label: &str,
    value: &mut T,
    choices: &[(T, &str)],
) {
    controls::choice_row(ui, label, "", value, choices);
}
fn integer_choices(ui: &mut egui::Ui, label: &str, value: &mut i32, choices: &[(i32, &str)]) {
    self::choices(ui, label, value, choices);
}
pub(super) fn provider_sampling(
    ui: &mut egui::Ui,
    id: &str,
    temperature: &mut Option<f64>,
    top_p: &mut Option<f64>,
    behavior: &mut crate::settings_policy::BehaviorSettings,
) {
    model::optional_sampling(ui, id, temperature, top_p, behavior);
}
