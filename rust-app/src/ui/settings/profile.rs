use crate::{
    platform::permissions::OperationKey,
    profile::{Request, Revision, Snapshot, Update},
    state::Settings,
};
use eframe::egui::{self, RichText, TextEdit};
use std::sync::Arc;

#[derive(Default)]
pub(super) struct ProfileView {
    initialized: bool,
    generation: u64,
    snapshot: Option<Arc<Snapshot>>,
    draft: String,
    base: Option<Revision>,
    edited: bool,
    reading: Option<OperationKey>,
    saving: Option<(OperationKey, String)>,
    notice: Option<String>,
    summary_initialized: bool,
    summary_inspect: Option<OperationKey>,
    summary_pending: Option<OperationKey>,
    summary_active: Option<crate::profile_summary::Key>,
    summary_billing_pending: bool,
    summary_cancel_sent: bool,
    summary_retry_sent: bool,
    summary_notice: Option<String>,
}
impl ProfileView {
    fn operation(&self) -> OperationKey {
        OperationKey {
            id: crate::controller::unique_id(),
            conversation_id: None,
            generation: self.generation.max(1),
        }
    }
    pub(super) fn disconnected(&mut self) {
        self.summary_initialized = false;
        self.summary_inspect = None;
        self.summary_pending = None;
        self.summary_active = None;
        self.summary_cancel_sent = false;
        self.summary_retry_sent = false;
        self.summary_billing_pending = false;
        self.reading = None;
        self.saving = None;
        self.initialized = false;
        self.generation = self.generation.saturating_add(1).max(1);
    }
    pub(super) fn summary_updated(&mut self, update: crate::controller::profile_work::Update) {
        use crate::controller::profile_work::Update as Summary;
        match update {
            Summary::Snapshot {
                operation,
                key,
                billing_pending,
            } if self.summary_inspect.as_ref() == Some(&operation) => {
                self.summary_inspect = None;
                self.summary_active = key;
                self.summary_billing_pending = billing_pending;
            }
            Summary::Started { key } => {
                if self.summary_pending.as_ref().is_some_and(|operation| {
                    operation.id == key.id && operation.generation == key.generation
                }) {
                    self.summary_pending = None;
                }
                self.summary_active = Some(key);
                self.summary_billing_pending = false;
                self.summary_cancel_sent = false;
                self.summary_retry_sent = false;
                self.summary_notice = Some("正在整理已保存的画像…".into());
            }
            Summary::Finished {
                key,
                result,
                billing_pending,
            } if self.summary_active.as_ref() == Some(&key) => {
                self.summary_billing_pending = billing_pending;
                self.summary_retry_sent = false;
                self.summary_cancel_sent = false;
                self.summary_notice = Some(match result {
                    Ok(message) => message,
                    Err(error) => error,
                });
                if !billing_pending {
                    self.summary_active = None;
                }
            }
            Summary::Rejected { operation, error } => {
                let pending = self.summary_pending.as_ref() == Some(&operation);
                let inspected = self.summary_inspect.as_ref() == Some(&operation);
                let retry = self.summary_active.as_ref().is_some_and(|key| {
                    operation.id == key.id
                        && operation.generation == key.generation
                        && operation.conversation_id.as_deref()
                            == Some(key.conversation_id.as_str())
                });
                if pending || inspected || retry {
                    if pending {
                        self.summary_pending = None;
                    }
                    if inspected {
                        self.summary_inspect = None;
                    }
                    self.summary_retry_sent = false;
                    self.summary_notice = Some(error);
                }
            }
            _ => {}
        }
    }
    pub(super) fn summary_dispatch_failed(
        &mut self,
        request: &crate::controller::profile_work::Request,
    ) {
        use crate::controller::profile_work::Request as Summary;
        match request {
            Summary::Inspect(_) => {
                self.summary_inspect = None;
                self.summary_initialized = false;
            }
            Summary::Manual { .. } => self.summary_pending = None,
            Summary::Cancel(_) => self.summary_cancel_sent = false,
            Summary::RetryBilling(_) => self.summary_retry_sent = false,
        }
        self.summary_notice = Some("整理操作未送达，现有画像和账单状态保留".into());
    }
    pub(super) fn show_summary(
        &mut self,
        ui: &mut egui::Ui,
        ready: bool,
        source: Option<(&str, &str)>,
    ) -> Vec<crate::controller::profile_work::Request> {
        use crate::controller::profile_work::Request as Summary;
        let mut requests = Vec::new();
        if ready && !self.summary_initialized {
            self.summary_initialized = true;
            let operation = self.operation();
            self.summary_inspect = Some(operation.clone());
            requests.push(Summary::Inspect(operation));
        }
        ui.separator();
        ui.strong("立即整理画像");
        ui.label("整理使用已保存的画像和以下来源会话，完成后保存符合长期画像规则的更新。");
        if let Some((_, title)) = source {
            ui.label(format!("来源会话：{title}"));
        } else {
            ui.label("请先选择作为来源的会话。");
        }
        if let Some(key) = &self.summary_active {
            ui.label(if key.automatic {
                "自动整理正在处理或结算"
            } else {
                "手动整理正在处理或结算"
            });
        }
        ui.horizontal_wrapped(|ui| {
            if ui
                .add_enabled(
                    ready
                        && source.is_some()
                        && self.summary_pending.is_none()
                        && self.summary_active.is_none()
                        && self.summary_inspect.is_none(),
                    egui::Button::new("立即整理"),
                )
                .clicked()
            {
                if let Some((conversation, _)) = source {
                    let mut operation = self.operation();
                    operation.conversation_id = Some(conversation.into());
                    self.summary_pending = Some(operation.clone());
                    requests.push(Summary::Manual {
                        operation,
                        conversation_id: conversation.into(),
                    });
                }
            }
            if ui
                .add_enabled(
                    ready
                        && self.summary_active.is_some()
                        && !self.summary_billing_pending
                        && !self.summary_cancel_sent,
                    egui::Button::new("取消整理"),
                )
                .clicked()
            {
                if let Some(key) = &self.summary_active {
                    self.summary_cancel_sent = true;
                    requests.push(Summary::Cancel(key.clone()));
                }
            }
            if ui
                .add_enabled(
                    ready && self.summary_billing_pending && !self.summary_retry_sent,
                    egui::Button::new("重试保存请求账单"),
                )
                .clicked()
            {
                if let Some(key) = &self.summary_active {
                    self.summary_retry_sent = true;
                    requests.push(Summary::RetryBilling(OperationKey {
                        id: key.id.clone(),
                        generation: key.generation,
                        conversation_id: Some(key.conversation_id.clone()),
                    }));
                }
            }
            if self.summary_pending.is_some()
                || self.summary_inspect.is_some()
                || self.summary_retry_sent
                || self.summary_active.is_some() && !self.summary_billing_pending
            {
                ui.spinner();
            }
        });
        if self.summary_billing_pending {
            ui.label("模型结果已保留，请求账单尚未确认保存；可重试保存这笔账单。");
        }
        if let Some(notice) = &self.summary_notice {
            ui.add(egui::Label::new(notice).wrap());
        }
        requests
    }
    pub(super) fn dispatch_failed(&mut self, key: &OperationKey) {
        if self.reading.as_ref() == Some(key) {
            self.reading = None;
        }
        if self
            .saving
            .as_ref()
            .is_some_and(|(operation, _)| operation == key)
        {
            self.saving = None;
        }
        self.notice = Some("画像操作未送达，草稿保留".into());
    }
    pub(super) fn apply(&mut self, update: Update) {
        match update {
            Update::Read {
                operation,
                snapshot,
            } if self.reading.as_ref() == Some(&operation) => {
                self.reading = None;
                if let Err(error) = snapshot.validate() {
                    self.notice = Some(error);
                    return;
                }
                if !self.edited {
                    self.draft = snapshot.text.clone();
                    self.base = Some(snapshot.revision.clone());
                }
                self.snapshot = Some(snapshot);
            }
            Update::Saved {
                operation,
                snapshot,
            } if self
                .saving
                .as_ref()
                .is_some_and(|(key, _)| key == &operation) =>
            {
                let (_, submitted) = self.saving.take().unwrap();
                if let Err(error) = snapshot.validate() {
                    self.notice = Some(error);
                    return;
                }
                if snapshot.text != submitted {
                    self.notice = Some("画像保存回执与原提交不同，草稿保留".into());
                    return;
                }
                if self.draft == submitted {
                    self.draft = snapshot.text.clone();
                    self.edited = false;
                    self.base = Some(snapshot.revision.clone());
                }
                self.snapshot = Some(snapshot);
                self.notice = Some("画像已保存".into());
            }
            Update::Failed { operation, error } => {
                let own = self.reading.as_ref() == Some(&operation)
                    || self
                        .saving
                        .as_ref()
                        .is_some_and(|(key, _)| key == &operation);
                if own {
                    if self.reading.as_ref() == Some(&operation) {
                        self.reading = None;
                    }
                    if self
                        .saving
                        .as_ref()
                        .is_some_and(|(key, _)| key == &operation)
                    {
                        self.saving = None;
                    }
                    self.notice = Some(error);
                }
            }
            Update::Changed { snapshot } => {
                if let Err(error) = snapshot.validate() {
                    self.notice = Some(error);
                    return;
                }
                let current = self.snapshot.as_ref();
                if current.is_some_and(|old| {
                    old.revision.generation == snapshot.revision.generation
                        && snapshot.revision.revision >= old.revision.revision
                }) {
                    if !self.edited {
                        self.draft = snapshot.text.clone();
                        self.base = Some(snapshot.revision.clone());
                    }
                    self.snapshot = Some(snapshot);
                }
            }
            _ => {}
        }
    }
    pub(super) fn show(
        &mut self,
        ui: &mut egui::Ui,
        ready: bool,
        settings: &mut Settings,
    ) -> Vec<Request> {
        let mut requests = Vec::new();
        if ready && !self.initialized {
            self.initialized = true;
            let key = self.operation();
            self.reading = Some(key.clone());
            requests.push(Request::Read(key));
        }
        if ui
            .add_enabled(
                ready && self.reading.is_none() && self.saving.is_none(),
                egui::Button::new("刷新画像"),
            )
            .clicked()
        {
            let key = self.operation();
            self.reading = Some(key.clone());
            requests.push(Request::Read(key));
        }
        if let Some(snapshot) = &self.snapshot {
            if snapshot.last_profile_at > 0 {
                ui.label(format!(
                    "最近整理：{} · 累计 {} 轮",
                    snapshot
                        .last_profile_label
                        .as_deref()
                        .unwrap_or("时间暂不可用"),
                    snapshot.profile_turn_mark
                ));
            } else {
                ui.label(format!(
                    "尚未自动整理 · 已累计 {} 轮",
                    snapshot.profile_turn_mark
                ));
            }
            for warning in &snapshot.warnings {
                ui.add(egui::Label::new(warning).wrap());
            }
            if self.edited && self.base.as_ref() != Some(&snapshot.revision) {
                ui.label("画像原文已经更新；保存会核对开始编辑时的版本。");
            }
        }
        let editable = ready && self.base.is_some() && self.saving.is_none();
        let response = ui.add_enabled(
            editable,
            TextEdit::multiline(&mut self.draft)
                .desired_rows(10)
                .desired_width(f32::INFINITY)
                .char_limit(8000),
        );
        if response.changed() {
            self.edited = true;
        }
        let length = self.draft.encode_utf16().count();
        ui.label(format!("{length} / 8000 字"));
        if length > 8000 {
            ui.colored_label(
                ui.visuals().error_fg_color,
                "超出画像长度限制，请精简后保存",
            );
        }
        ui.horizontal_wrapped(|ui| {
            if ui
                .add_enabled(
                    editable && self.edited && length <= 8000,
                    egui::Button::new("保存画像"),
                )
                .clicked()
            {
                let operation = self.operation();
                if let Some(expected) = &self.base {
                    self.saving = Some((operation.clone(), self.draft.clone()));
                    requests.push(Request::Save {
                        operation,
                        expected: expected.clone(),
                        text: self.draft.clone(),
                    });
                }
            }
            if ui
                .add_enabled(
                    self.edited && self.saving.is_none() && self.snapshot.is_some(),
                    egui::Button::new("放弃画像修改"),
                )
                .clicked()
            {
                if let Some(snapshot) = &self.snapshot {
                    self.draft = snapshot.text.clone();
                    self.base = Some(snapshot.revision.clone());
                    self.edited = false;
                }
            }
            if self.reading.is_some() || self.saving.is_some() {
                ui.spinner();
            }
        });
        if let Some(notice) = &self.notice {
            ui.add(egui::Label::new(notice).wrap());
        }
        ui.separator();
        ui.strong("自动整理");
        super::row(
            ui,
            &mut settings.behavior.auto_profile,
            "自动整理用户画像",
            "根据完成的运行累计轮数，定期整理关于用户的长期信息。",
        );
        ui.add_enabled_ui(settings.behavior.auto_profile, |ui| {
            ui.add(
                egui::Slider::new(&mut settings.behavior.profile_every_turns, 2..=40)
                    .step_by(2.0)
                    .text("画像整理间隔")
                    .suffix("轮"),
            );
        });
        ui.label(
            RichText::new("画像正文与自动整理设置分别等待各自的保存确认。")
                .small()
                .weak(),
        );
        requests
    }
}
pub(super) fn operation(request: &Request) -> &OperationKey {
    match request {
        Request::Read(key) => key,
        Request::Save { operation, .. } => operation,
    }
}
