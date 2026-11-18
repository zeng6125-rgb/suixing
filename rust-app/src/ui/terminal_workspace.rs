//! Independent terminal tabs. Rendering only emits typed actor requests.
use super::terminal::TerminalRenderer;
use crate::{
    controller::terminal_runtime::{Request, Update},
    runtime_host::TerminalKey,
    shell_environment::Backend,
    terminal::{Phase, RawWindow, SessionInfo},
    vt::{Frame, Geometry, Input},
};
use eframe::egui::{self, RichText};
use std::{collections::HashMap, sync::Arc};

const MAX_PENDING: usize = 64;
const MAX_TABS: usize = 132;
const MAX_COMMAND_BYTES: usize = 16 * 1024 - 1;
const MAX_FRAME_BYTES: usize = 32 * 1024 * 1024;
const RAW_BYTES: usize = 32 * 1024;

enum PendingKind {
    Create,
    Select,
    Rename,
    Input(Option<String>),
    Resize(u64),
    Stop,
    Logging,
    Policy,
    Refresh,
    Raw {
        cursor: u64,
        navigation: RawNavigation,
    },
}
struct Pending {
    key: Option<TerminalKey>,
    kind: PendingKind,
}
#[derive(Clone, Copy)]
enum RawNavigation {
    Refresh,
    First,
    Next,
    Previous,
}
struct RawPage {
    window: RawWindow,
    text: String,
}
struct Tab {
    renderer: TerminalRenderer,
    frame: Option<Arc<Frame>>,
    frame_bytes: usize,
    touched: u64,
    command: String,
    rename: String,
    renaming: bool,
    raw_visible: bool,
    raw: Option<RawPage>,
    raw_back: Vec<u64>,
    notice: Option<String>,
    error: Option<String>,
}
impl Default for Tab {
    fn default() -> Self {
        Self {
            renderer: TerminalRenderer::new(),
            frame: None,
            frame_bytes: 0,
            touched: 0,
            command: String::new(),
            rename: String::new(),
            renaming: false,
            raw_visible: false,
            raw: None,
            raw_back: Vec::new(),
            notice: None,
            error: None,
        }
    }
}
pub struct TerminalWorkspace {
    sessions: Vec<SessionInfo>,
    selected: Option<TerminalKey>,
    desired: Option<TerminalKey>,
    revision: u64,
    tabs: HashMap<TerminalKey, Tab>,
    pending: HashMap<String, Pending>,
    paste: Option<(TerminalKey, String)>,
    backend: Backend,
    name: String,
    logging: bool,
    columns: u16,
    rows: u16,
    creating: bool,
    clock: u64,
    error: Option<String>,
}
impl Default for TerminalWorkspace {
    fn default() -> Self {
        Self {
            sessions: Vec::new(),
            selected: None,
            desired: None,
            revision: 0,
            tabs: HashMap::new(),
            pending: HashMap::new(),
            paste: None,
            backend: Backend::Auto,
            name: String::new(),
            logging: false,
            columns: 80,
            rows: 24,
            creating: false,
            clock: 0,
            error: None,
        }
    }
}
impl TerminalWorkspace {
    fn begin(&mut self, key: Option<TerminalKey>, kind: PendingKind) -> Option<String> {
        if self.pending.len() >= MAX_PENDING {
            self.error = Some("终端操作队列已满，请等待已提交的操作".into());
            return None;
        }
        let request_id = crate::controller::unique_id();
        self.pending
            .insert(request_id.clone(), Pending { key, kind });
        Some(request_id)
    }
    fn control_pending(&self) -> bool {
        self.pending.values().any(|pending| {
            !matches!(
                pending.kind,
                PendingKind::Input(_) | PendingKind::Resize(_) | PendingKind::Raw { .. }
            )
        })
    }
    fn raw_pending(&self, key: &TerminalKey) -> bool {
        self.pending.values().any(|pending| {
            pending.key.as_ref() == Some(key) && matches!(pending.kind, PendingKind::Raw { .. })
        })
    }
    pub fn operation_pending(&self) -> bool {
        !self.pending.is_empty()
    }
    pub fn refresh(&mut self) -> Option<Request> {
        let request_id = self.begin(None, PendingKind::Refresh)?;
        Some(Request::Refresh { request_id })
    }
    fn raw_request(
        &mut self,
        key: &TerminalKey,
        cursor: u64,
        navigation: RawNavigation,
    ) -> Option<Request> {
        if self.raw_pending(key) {
            return None;
        }
        let request_id = self.begin(Some(key.clone()), PendingKind::Raw { cursor, navigation })?;
        Some(Request::ReadRaw {
            request_id,
            key: key.clone(),
            cursor,
            max_bytes: RAW_BYTES,
        })
    }
    pub fn dispatch_failed(&mut self, request_id: &str, error: &str) {
        let Some(pending) = self.pending.remove(request_id) else {
            return;
        };
        if let Some(key) = pending.key {
            let tab = self.tabs.entry(key).or_default();
            if let PendingKind::Resize(version) = pending.kind {
                tab.renderer.resize_rejected(version);
            }
            tab.error = Some(error.into());
        } else {
            self.error = Some(error.into());
        }
    }
    pub fn disconnected(&mut self) {
        self.pending.clear();
        self.desired = None;
        self.revision = 0;
        self.paste = None;
        self.error = Some("运行时连接已断开，终端操作结果尚未确认；命令草稿已保留".into());
        for tab in self.tabs.values_mut() {
            tab.renderer.resize_failed();
            tab.frame = None;
            tab.frame_bytes = 0;
        }
    }
    pub fn apply(&mut self, update: Update) {
        match update {
            Update::Snapshot {
                revision,
                sessions,
                selected,
            } => {
                if revision < self.revision || sessions.len() > crate::terminal::MAX_RECORDS {
                    return;
                }
                self.revision = revision;
                self.sessions = sessions;
                if let Some(desired) = self.desired.as_ref()
                    && self.sessions.iter().any(|session| &session.key == desired)
                {
                    self.selected = self.desired.take();
                }
                if self
                    .selected
                    .as_ref()
                    .is_none_or(|key| !self.sessions.iter().any(|session| &session.key == key))
                {
                    self.selected = selected
                        .filter(|key| self.sessions.iter().any(|session| &session.key == key));
                }
                self.tabs.retain(|key, tab| {
                    self.sessions.iter().any(|session| &session.key == key)
                        || !tab.command.is_empty()
                        || self
                            .pending
                            .values()
                            .any(|pending| pending.key.as_ref() == Some(key))
                });
                if let Some(key) = &self.selected {
                    if self.tabs.contains_key(key) || self.tabs.len() < MAX_TABS {
                        self.tabs.entry(key.clone()).or_default();
                    } else {
                        self.selected = None;
                        self.error = Some("终端草稿缓存已满，请先处理未关联的命令草稿".into());
                    }
                }
            }
            Update::Frame { key, frame } => {
                if frame.session_id != key.session_id || key.validate().is_err() {
                    return;
                }
                let bytes = frame.estimated_bytes();
                if bytes > MAX_FRAME_BYTES {
                    self.error = Some("终端屏幕超过显示缓存预算".into());
                    return;
                }
                self.clock = self.clock.saturating_add(1);
                if !self.tabs.contains_key(&key) && self.tabs.len() >= MAX_TABS {
                    self.error = Some("终端阅读缓存已满，请先处理未关联的命令草稿".into());
                    return;
                }
                let tab = self.tabs.entry(key).or_default();
                if tab.frame.as_ref().is_some_and(|old| {
                    frame.geometry.version < old.geometry.version
                        || (frame.geometry.version == old.geometry.version
                            && frame.revision < old.revision)
                }) {
                    return;
                }
                tab.frame = Some(frame);
                tab.frame_bytes = bytes;
                tab.touched = self.clock;
                while self.tabs.values().map(|tab| tab.frame_bytes).sum::<usize>() > MAX_FRAME_BYTES
                    || self.tabs.values().filter(|tab| tab.frame.is_some()).count() > 8
                {
                    let oldest = self
                        .tabs
                        .iter()
                        .filter(|(key, tab)| {
                            self.selected.as_ref() != Some(*key) && tab.frame.is_some()
                        })
                        .min_by_key(|(_, tab)| tab.touched)
                        .map(|(key, _)| key.clone());
                    let Some(oldest) = oldest else {
                        break;
                    };
                    let tab = self.tabs.get_mut(&oldest).unwrap();
                    tab.frame = None;
                    tab.frame_bytes = 0;
                }
            }
            Update::ResizeRejected {
                key,
                geometry_version,
                error,
            } => {
                if let Some(tab) = self.tabs.get_mut(&key) {
                    if tab.renderer.resize_rejected(geometry_version) {
                        tab.error = Some(error);
                    }
                }
            }
            Update::Finished {
                request_id,
                key,
                result,
                queued,
            } => {
                let Some(pending) = self.pending.get(&request_id) else {
                    return;
                };
                if pending.key.is_some() && pending.key != key {
                    return;
                }
                if matches!(pending.kind, PendingKind::Raw { .. }) && result.is_ok() {
                    return;
                }
                let pending = self.pending.remove(&request_id).unwrap();
                if let Err(error) = result {
                    if let Some(key) = pending.key {
                        let tab = self.tabs.entry(key).or_default();
                        if let PendingKind::Resize(version) = pending.kind {
                            tab.renderer.resize_rejected(version);
                        }
                        tab.error = Some(error);
                    } else {
                        self.error = Some(error);
                    }
                    return;
                }
                match pending.kind {
                    PendingKind::Create | PendingKind::Select => {
                        if let Some(key) = key {
                            self.tabs.entry(key.clone()).or_default();
                            self.desired = Some(key.clone());
                            if self.sessions.iter().any(|session| session.key == key) {
                                self.selected = Some(key);
                                self.desired = None;
                            }
                            self.creating = false;
                        } else {
                            self.error = Some("终端创建或选择没有返回真实身份".into());
                        }
                    }
                    PendingKind::Input(text) => {
                        if let Some(key) = key {
                            let tab = self.tabs.entry(key).or_default();
                            if text.as_ref().is_some_and(|text| text == &tab.command) {
                                tab.command.clear();
                            }
                            tab.notice = Some(
                                if queued {
                                    "输入已交给终端；执行结果请查看屏幕"
                                } else {
                                    "终端已接收输入"
                                }
                                .into(),
                            );
                        }
                    }
                    PendingKind::Rename => {
                        if let Some(key) = key {
                            self.tabs.entry(key).or_default().renaming = false;
                        }
                    }
                    PendingKind::Logging => {
                        if let Some(key) = key {
                            self.tabs.entry(key).or_default().notice =
                                Some("日志设置已提交，实际状态以终端列表为准".into());
                        }
                    }
                    PendingKind::Stop => {
                        if let Some(key) = key {
                            self.tabs.entry(key).or_default().notice =
                                Some("已请求停止，正在等待终端退出".into());
                        }
                    }
                    PendingKind::Resize(_)
                    | PendingKind::Policy
                    | PendingKind::Refresh
                    | PendingKind::Raw { .. } => {}
                }
            }
            Update::Raw {
                request_id,
                key,
                result,
            } => {
                let Some(pending) = self.pending.get(&request_id) else {
                    return;
                };
                if pending.key.as_ref() != Some(&key)
                    || !matches!(pending.kind, PendingKind::Raw { .. })
                {
                    return;
                }
                let pending = self.pending.remove(&request_id).unwrap();
                let tab = self.tabs.entry(key).or_default();
                match result {
                    Ok(window) => {
                        let PendingKind::Raw { cursor, navigation } = pending.kind else {
                            return;
                        };
                        if window.bytes.len() > RAW_BYTES
                            || window.next_offset.checked_sub(window.from)
                                != Some(window.bytes.len() as u64)
                            || window.retained_from > window.from
                            || window.from > window.next_offset
                            || window.next_offset > window.end
                        {
                            tab.error = Some("终端日志分页结果无效".into());
                            return;
                        }
                        match navigation {
                            RawNavigation::First => tab.raw_back.clear(),
                            RawNavigation::Next => {
                                if let Some(previous) = &tab.raw {
                                    if tab.raw_back.len() >= 128 {
                                        tab.raw_back.remove(0);
                                    }
                                    tab.raw_back.push(previous.window.from);
                                }
                            }
                            RawNavigation::Previous => {
                                tab.raw_back.pop();
                            }
                            RawNavigation::Refresh => {}
                        }
                        if cursor < window.retained_from {
                            tab.notice = Some("较早的日志已不在保留范围，显示当前最早片段".into());
                        }
                        let text = String::from_utf8_lossy(&window.bytes).into_owned();
                        tab.raw = Some(RawPage { window, text });
                        tab.error = None;
                    }
                    Err(error) => tab.error = Some(error),
                }
            }
        }
    }
    pub fn show(&mut self, ui: &mut egui::Ui, connected: bool) -> Vec<Request> {
        let mut requests = Vec::new();
        let controls = connected && !self.control_pending();
        if let Some(error) = &self.error {
            ui.colored_label(ui.visuals().error_fg_color, error);
        }
        ui.horizontal_wrapped(|ui| {
            if ui
                .add_enabled(
                    controls
                        && self.tabs.len() < MAX_TABS
                        && self
                            .sessions
                            .iter()
                            .filter(|session| session.phase.active())
                            .count()
                            < crate::terminal::MAX_SESSIONS,
                    egui::Button::new("新建终端"),
                )
                .clicked()
            {
                self.creating = true;
            }
            if ui
                .add_enabled(controls, egui::Button::new("刷新列表"))
                .clicked()
                && let Some(request) = self.refresh()
            {
                requests.push(request);
            }
            if self.operation_pending() {
                ui.spinner();
            }
        });
        let orphaned: Vec<_> = self
            .tabs
            .iter()
            .filter(|(key, tab)| {
                !tab.command.is_empty() && !self.sessions.iter().any(|session| &session.key == *key)
            })
            .map(|(key, _)| key.clone())
            .collect();
        if !orphaned.is_empty() {
            let mut discard = None;
            egui::CollapsingHeader::new("未关联终端的命令草稿").show(ui, |ui| {
                for key in &orphaned {
                    ui.push_id(key, |ui| {
                        let tab = &self.tabs[key];
                        ui.add(egui::Label::new(&tab.command).wrap().selectable(true));
                        ui.horizontal(|ui| {
                            if ui.small_button("复制草稿").clicked() {
                                ui.ctx().copy_text(tab.command.clone());
                            }
                            if ui.small_button("丢弃草稿").clicked() {
                                discard = Some(key.clone());
                            }
                        });
                    });
                }
            });
            if let Some(key) = discard {
                self.tabs.remove(&key);
            }
        }
        let mut choose = None;
        egui::ScrollArea::horizontal()
            .id_salt("terminal_tabs")
            .show(ui, |ui| {
                ui.horizontal(|ui| {
                    for session in self
                        .sessions
                        .iter()
                        .filter(|session| session.phase.active())
                    {
                        if ui
                            .add_enabled(
                                controls
                                    && (self.tabs.contains_key(&session.key)
                                        || self.tabs.len() < MAX_TABS),
                                egui::Button::new(format!(
                                    "{} · {}",
                                    session.name,
                                    phase(session.phase)
                                ))
                                .selected(self.selected.as_ref() == Some(&session.key)),
                            )
                            .clicked()
                        {
                            choose = Some(session.key.clone());
                        }
                    }
                });
            });
        egui::CollapsingHeader::new("已结束的终端")
            .id_salt("terminal_archived")
            .show(ui, |ui| {
                egui::ScrollArea::vertical()
                    .max_height(120.)
                    .show(ui, |ui| {
                        for session in self
                            .sessions
                            .iter()
                            .filter(|session| !session.phase.active())
                        {
                            if ui
                                .add_enabled(
                                    controls
                                        && (self.tabs.contains_key(&session.key)
                                            || self.tabs.len() < MAX_TABS),
                                    egui::Button::new(format!(
                                        "{} · {}",
                                        session.name,
                                        phase(session.phase)
                                    ))
                                    .selected(self.selected.as_ref() == Some(&session.key)),
                                )
                                .clicked()
                            {
                                choose = Some(session.key.clone());
                            }
                        }
                    });
            });
        if let Some(key) = choose
            && let Some(request_id) = self.begin(Some(key.clone()), PendingKind::Select)
        {
            requests.push(Request::Select { request_id, key });
        }
        self.create_dialog(ui, controls, &mut requests);
        let Some(key) = self.selected.clone() else {
            ui.label("新建一个终端即可开始输入。每个终端独立保留命令和阅读位置。");
            return requests;
        };
        let Some(session) = self
            .sessions
            .iter()
            .find(|session| session.key == key)
            .cloned()
        else {
            ui.spinner();
            return requests;
        };
        self.clock = self.clock.saturating_add(1);
        let tab = self.tabs.entry(key.clone()).or_default();
        tab.touched = self.clock;
        if let Some(error) = &tab.error {
            ui.colored_label(ui.visuals().error_fg_color, error);
        }
        if let Some(notice) = &tab.notice {
            ui.label(RichText::new(notice).small().weak());
        }
        if let Some(note) = &session.note {
            ui.label(note);
        }
        if let Some(error) = &session.log.error {
            ui.colored_label(ui.visuals().error_fg_color, error);
        }
        ui.label(
            RichText::new(format!(
                "{} · {}",
                backend(session.backend),
                phase(session.phase)
            ))
            .small(),
        );
        let running = connected && session.started && session.phase == Phase::Running;
        let mut rename = false;
        let mut stop = false;
        let mut logging = None;
        let mut policy = None;
        ui.horizontal_wrapped(|ui| {
            if ui
                .add_enabled(controls, egui::Button::new("重命名"))
                .clicked()
            {
                tab.rename = session.name.clone();
                tab.renaming = true;
            }
            let mut enabled = session.log.enabled;
            if ui
                .add_enabled_ui(controls && session.phase.active(), |ui| {
                    ui.checkbox(&mut enabled, "保存原始日志")
                })
                .inner
                .changed()
            {
                logging = Some(enabled);
            }
            if ui
                .add_enabled(
                    controls && session.phase.active() && session.phase != Phase::Stopping,
                    egui::Button::new("停止终端"),
                )
                .clicked()
            {
                stop = true;
            }
            if ui
                .add_enabled(
                    controls && session.phase.active(),
                    egui::Button::new("接管输入"),
                )
                .on_hover_text("撤销智能体对这个终端的输入授权")
                .clicked()
            {
                policy = Some(false);
            }
            if ui
                .add_enabled(
                    controls && session.phase.active(),
                    egui::Button::new("允许当前任务使用"),
                )
                .clicked()
            {
                policy = Some(true);
            }
            if ui.selectable_label(!tab.raw_visible, "屏幕").clicked() {
                tab.raw_visible = false;
            }
            if ui.selectable_label(tab.raw_visible, "原始日志").clicked() {
                tab.raw_visible = true;
            }
        });
        if tab.renaming {
            ui.add_enabled(
                controls,
                egui::TextEdit::singleline(&mut tab.rename).char_limit(128),
            );
            ui.horizontal(|ui| {
                if ui
                    .add_enabled(
                        controls && !tab.rename.trim().is_empty() && tab.rename.len() <= 256,
                        egui::Button::new("保存名称"),
                    )
                    .clicked()
                {
                    rename = true;
                }
                if ui
                    .add_enabled(controls, egui::Button::new("取消"))
                    .clicked()
                {
                    tab.renaming = false;
                }
            });
        }
        if rename {
            let name = self.tabs[&key].rename.clone();
            if let Some(request_id) = self.begin(Some(key.clone()), PendingKind::Rename) {
                requests.push(Request::Rename {
                    request_id,
                    key: key.clone(),
                    name,
                });
            }
        }
        if stop && let Some(request_id) = self.begin(Some(key.clone()), PendingKind::Stop) {
            requests.push(Request::Stop {
                request_id,
                key: key.clone(),
            });
        }
        if let Some(enabled) = logging
            && let Some(request_id) = self.begin(Some(key.clone()), PendingKind::Logging)
        {
            requests.push(Request::SetLogging {
                request_id,
                key: key.clone(),
                enabled,
            });
        }
        if let Some(resume) = policy
            && let Some(request_id) = self.begin(Some(key.clone()), PendingKind::Policy)
        {
            requests.push(if resume {
                Request::Resume {
                    request_id,
                    key: key.clone(),
                }
            } else {
                Request::TakeOver {
                    request_id,
                    key: key.clone(),
                }
            });
        }
        if self.tabs[&key].raw_visible {
            self.raw_view(ui, &key, controls, &mut requests);
        } else {
            self.screen(ui, &key, running, &mut requests);
        }
        self.paste_dialog(ui, connected, &mut requests);
        requests
    }
    fn create_dialog(&mut self, ui: &mut egui::Ui, controls: bool, requests: &mut Vec<Request>) {
        if !self.creating {
            return;
        }
        let mut create = false;
        let mut close = false;
        egui::Window::new("新建终端")
            .collapsible(false)
            .show(ui.ctx(), |ui| {
                ui.add_enabled(
                    controls,
                    egui::TextEdit::singleline(&mut self.name)
                        .hint_text("终端名称（可留空）")
                        .char_limit(128),
                );
                egui::ComboBox::from_id_salt("terminal_backend")
                    .selected_text(backend(self.backend))
                    .show_ui(ui, |ui| {
                        for value in [
                            Backend::Auto,
                            Backend::Sh,
                            Backend::Bash,
                            Backend::Busybox,
                            Backend::Alpine,
                            Backend::Debian,
                        ] {
                            ui.selectable_value(&mut self.backend, value, backend(value));
                        }
                    });
                ui.checkbox(&mut self.logging, "保存原始日志");
                ui.horizontal(|ui| {
                    ui.label("初始列");
                    ui.add(egui::DragValue::new(&mut self.columns).range(1..=1000));
                    ui.label("行");
                    ui.add(egui::DragValue::new(&mut self.rows).range(1..=1000));
                });
                ui.horizontal(|ui| {
                    if ui
                        .add_enabled(controls, egui::Button::new("创建"))
                        .clicked()
                    {
                        create = true;
                    }
                    if ui
                        .add_enabled(controls, egui::Button::new("取消"))
                        .clicked()
                    {
                        close = true;
                    }
                });
            });
        if close {
            self.creating = false;
        }
        if create {
            let geometry = Geometry {
                columns: self.columns,
                rows: self.rows,
                version: 0,
            };
            if let Err(error) = geometry.validate() {
                self.error = Some(error);
                return;
            }
            if let Some(request_id) = self.begin(None, PendingKind::Create) {
                requests.push(Request::Create {
                    request_id,
                    backend: self.backend,
                    name: self.name.clone(),
                    logging: self.logging,
                    geometry,
                });
            }
        }
    }
    fn screen(
        &mut self,
        ui: &mut egui::Ui,
        key: &TerminalKey,
        running: bool,
        requests: &mut Vec<Request>,
    ) {
        let command_pending = self.pending.values().any(|pending| {
            pending.key.as_ref() == Some(key) && matches!(pending.kind, PendingKind::Input(Some(_)))
        });
        let ready = running && self.pending.len() < MAX_PENDING;
        let mut send = false;
        let mut interrupt = false;
        let tab = self.tabs.get_mut(key).unwrap();
        egui::Panel::bottom(("terminal_input", key)).show(ui, |ui| {
            let response = ui.add_enabled(
                ready && !command_pending,
                egui::TextEdit::singleline(&mut tab.command)
                    .id_salt(("terminal_command", key))
                    .hint_text("发送到这个终端的命令")
                    .desired_width(f32::INFINITY)
                    .char_limit(MAX_COMMAND_BYTES),
            );
            if response.changed() && tab.command.len() > MAX_COMMAND_BYTES {
                let mut end = MAX_COMMAND_BYTES;
                while !tab.command.is_char_boundary(end) {
                    end -= 1;
                }
                tab.command.truncate(end);
                tab.error = Some("终端命令最多16 KiB，超出部分未加入".into());
            }
            ui.horizontal(|ui| {
                send = ui
                    .add_enabled(
                        ready && !command_pending && !tab.command.is_empty(),
                        egui::Button::new("发送命令"),
                    )
                    .clicked();
                interrupt = ui
                    .add_enabled(ready, egui::Button::new("中断"))
                    .on_hover_text("发送Ctrl-C，保留这个终端")
                    .clicked();
            });
        });
        if send {
            let text = self.tabs[key].command.clone();
            if let Some(request_id) =
                self.begin(Some(key.clone()), PendingKind::Input(Some(text.clone())))
            {
                requests.push(Request::Input {
                    request_id,
                    key: key.clone(),
                    input: Input::Text {
                        text: format!("{text}\n"),
                        modifiers: Default::default(),
                    },
                });
            }
        }
        if interrupt
            && let Some(request_id) = self.begin(Some(key.clone()), PendingKind::Input(None))
        {
            requests.push(Request::Input {
                request_id,
                key: key.clone(),
                input: Input::Text {
                    text: "\u{3}".into(),
                    modifiers: Default::default(),
                },
            });
        }
        let frame = self.tabs[key].frame.clone();
        if let Some(frame) = frame {
            let input_budget = MAX_PENDING.saturating_sub(self.pending.len());
            let response = self
                .tabs
                .get_mut(key)
                .unwrap()
                .renderer
                .show_key_with_budget(ui, &frame, running, key, input_budget);
            if let Some(geometry) = response.resize {
                if let Some(request_id) =
                    self.begin(Some(key.clone()), PendingKind::Resize(geometry.version))
                {
                    requests.push(Request::Resize {
                        request_id,
                        key: key.clone(),
                        geometry,
                    });
                } else {
                    self.tabs
                        .get_mut(key)
                        .unwrap()
                        .renderer
                        .resize_rejected(geometry.version);
                }
            }
            for input in response.inputs {
                if let Some(request_id) = self.begin(Some(key.clone()), PendingKind::Input(None)) {
                    requests.push(Request::Input {
                        request_id,
                        key: key.clone(),
                        input,
                    });
                } else {
                    self.error = Some("终端输入队列已满，剩余按键未发送".into());
                    break;
                }
            }
            if let Some(text) = response.paste_confirmation {
                self.paste = Some((key.clone(), text));
            }
        } else {
            ui.label("暂无终端屏幕，可以刷新列表或查看已保存的原始日志。");
        }
    }
    fn raw_view(
        &mut self,
        ui: &mut egui::Ui,
        key: &TerminalKey,
        connected: bool,
        requests: &mut Vec<Request>,
    ) {
        let ready = connected && !self.raw_pending(key);
        let tab = &self.tabs[key];
        let mut ask = None;
        ui.horizontal_wrapped(|ui| {
            if ui
                .add_enabled(ready, egui::Button::new("最早片段"))
                .clicked()
            {
                ask = Some((0, RawNavigation::First));
            }
            if ui
                .add_enabled(
                    ready && !tab.raw_back.is_empty(),
                    egui::Button::new("上一页"),
                )
                .clicked()
                && let Some(cursor) = tab.raw_back.last()
            {
                ask = Some((*cursor, RawNavigation::Previous));
            }
            if ui
                .add_enabled(
                    ready && tab.raw.as_ref().is_some_and(|page| page.window.has_more),
                    egui::Button::new("下一页"),
                )
                .clicked()
                && let Some(page) = &tab.raw
            {
                ask = Some((page.window.next_offset, RawNavigation::Next));
            }
            if ui
                .add_enabled(ready, egui::Button::new("刷新日志"))
                .clicked()
            {
                ask = Some((
                    tab.raw.as_ref().map_or(0, |page| page.window.from),
                    RawNavigation::Refresh,
                ));
            }
        });
        if let Some(page) = &tab.raw {
            ui.label(format!(
                "字节 {}–{} / {}",
                page.window.from, page.window.next_offset, page.window.end
            ));
            if !page.window.logging {
                ui.label("此终端当前未保存原始日志，较早输出可能已经丢失。");
            }
            if let Some(error) = &page.window.error {
                ui.colored_label(ui.visuals().error_fg_color, error);
            }
            egui::ScrollArea::both()
                .id_salt(("terminal_raw", key, page.window.from))
                .show(ui, |ui| {
                    ui.add(
                        egui::Label::new(RichText::new(&page.text).monospace()).selectable(true),
                    );
                });
        } else {
            ui.label("点击最早片段或刷新日志读取。日志按字节分页，历史屏幕位置独立保留。");
        }
        if let Some((cursor, navigation)) = ask
            && let Some(request) = self.raw_request(key, cursor, navigation)
        {
            requests.push(request);
        }
    }
    fn paste_dialog(&mut self, ui: &mut egui::Ui, connected: bool, requests: &mut Vec<Request>) {
        let Some((key, text)) = self.paste.clone() else {
            return;
        };
        let ready = connected
            && self.sessions.iter().any(|session| {
                session.key == key && session.started && session.phase == Phase::Running
            });
        let mut decision = None;
        egui::Window::new("确认粘贴到终端")
            .collapsible(false)
            .show(ui.ctx(), |ui| {
                let name = self
                    .sessions
                    .iter()
                    .find(|session| session.key == key)
                    .map_or("原终端", |session| session.name.as_str());
                ui.label(format!("目标：{name}。多行或控制字符可能立即执行命令。"));
                egui::ScrollArea::vertical()
                    .max_height(180.)
                    .show(ui, |ui| {
                        ui.label(RichText::new(&text).monospace());
                    });
                ui.horizontal(|ui| {
                    if ui.button("取消").clicked() {
                        decision = Some(false);
                    }
                    if ui
                        .add_enabled(
                            ready && text.len() <= 16 * 1024 && self.pending.len() < MAX_PENDING,
                            egui::Button::new("粘贴到这个终端"),
                        )
                        .clicked()
                    {
                        decision = Some(true);
                    }
                });
            });
        if let Some(accepted) = decision {
            self.paste = None;
            if accepted
                && let Some(request_id) = self.begin(Some(key.clone()), PendingKind::Input(None))
            {
                requests.push(Request::Input {
                    request_id,
                    key,
                    input: Input::Paste(text),
                });
            }
        }
    }
}
fn phase(value: Phase) -> &'static str {
    match value {
        Phase::Claimed => "等待启动",
        Phase::Running => "运行中",
        Phase::Stopping => "正在停止",
        Phase::Exited => "已结束",
        Phase::Failed => "失败",
        Phase::Interrupted => "已中断",
    }
}
fn backend(value: Backend) -> &'static str {
    match value {
        Backend::Auto => "自动",
        Backend::Sh => "系统sh",
        Backend::Bash => "Bash",
        Backend::Busybox => "Busybox",
        Backend::Alpine => "Alpine",
        Backend::Debian => "Debian",
    }
}
