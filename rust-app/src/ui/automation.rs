//! Page renderer. The actor supplies snapshots, outcomes and decoded pixels;
//! rendering only uploads a matched texture and emits nonblocking commands.
use super::settings::controls;
use crate::controller::automation_runtime::{Completion, Request, Update};
use crate::platform::{
    applications::Application,
    manual_settings::Page,
    permissions::{OperationKey, PermissionKind, PermissionOutcome},
    virtual_screen::{Frame, Identity, ManualInput},
};
use crate::platform_tools::automation_page::{
    Command, CommandKind, Preferences, ResultKind, Snapshot,
};
use eframe::egui::{self, Color32, Sense, TextureHandle, Vec2};
use std::{
    collections::BTreeMap,
    time::{Duration, Instant},
};
pub struct ManualAck {
    pub operation: OperationKey,
    pub accepted: bool,
    pub completion_observed: bool,
    pub message: String,
}
pub struct AutomationView {
    pending: BTreeMap<String, OperationKey>,
    preferences: Preferences,
    initialized: bool,
    visible: bool,
    generation: u64,
    snapshot: Option<Snapshot>,
    texture: Option<TextureHandle>,
    frame_identity: Option<Identity>,
    frame_size: (u32, u32),
    last_frame: Instant,
    frame_pending: Option<OperationKey>,
    inline_preview: bool,
    down: Option<(Identity, egui::Pos2, Instant)>,
    apps: Vec<Application>,
    app_query: String,
    choose_app: bool,
    notice: Option<String>,
    acknowledgements: Vec<ManualAck>,
}
impl Default for AutomationView {
    fn default() -> Self {
        Self {
            pending: BTreeMap::new(),
            preferences: Preferences::default(),
            initialized: false,
            visible: false,
            generation: 1,
            snapshot: None,
            texture: None,
            frame_identity: None,
            frame_size: (0, 0),
            last_frame: Instant::now() - Duration::from_secs(1),
            frame_pending: None,
            inline_preview: true,
            down: None,
            apps: Vec::new(),
            app_query: String::new(),
            choose_app: false,
            notice: None,
            acknowledgements: Vec::new(),
        }
    }
}
impl AutomationView {
    pub fn operation_pending(&self) -> bool {
        !self.pending.is_empty()
    }
    pub fn disconnected(&mut self) {
        self.pending.clear();
        self.clear_preview();
        self.visible = false;
        self.initialized = false;
        self.snapshot = None;
    }
    fn clear_preview(&mut self) {
        self.texture = None;
        self.frame_identity = None;
        self.down = None;
        if let Some(pending) = self.frame_pending.take() {
            self.pending.remove(&pending.id);
        }
    }
    /// Root dispatches this request when Page::Automations becomes hidden.
    pub fn suspend(&mut self) -> Request {
        self.visible = false;
        self.clear_preview();
        Request::PageVisible(false)
    }
    pub fn set_generation(&mut self, generation: u64) {
        if generation != self.generation && generation > 0 {
            self.pending.clear();
            self.clear_preview();
            self.generation = generation;
        }
    }
    pub fn apply_update(&mut self, update: Update, ctx: &egui::Context) -> Result<(), String> {
        match update {
            Update::Snapshot(snapshot) => self.update_snapshot((*snapshot).clone()),
            Update::PreferencesConfirmed {
                operation,
                preferences,
            } => {
                self.preferences = preferences;
                if let Some(operation) = operation {
                    if self.pending.get(&operation.id) == Some(&operation) {
                        self.pending.remove(&operation.id);
                    }
                }
            }
            Update::Pending(_) => {}
            Update::Failed { operation, message } => self.failed(&operation, message),
            Update::Notice(message) => self.notice = Some(message),
            Update::ManualAck {
                operation,
                accepted,
                completion_observed,
                message,
            } => {
                self.acknowledgements.push(ManualAck {
                    operation,
                    accepted,
                    completion_observed,
                    message,
                });
                if self.acknowledgements.len() > 32 {
                    self.acknowledgements.remove(0);
                }
            }
            Update::Completed {
                operation,
                completion,
            } => {
                // A paused page or replaced request discards its old results.
                if self.pending.get(&operation.id) != Some(&operation) {
                    return Ok(());
                }
                let result = match completion {
                    Completion::Snapshot(snapshot) => ResultKind::Snapshot((*snapshot).clone()),
                    Completion::Frame(frame) => ResultKind::Frame(Frame {
                        identity: frame.identity.clone(),
                        pixels: frame.pixels.clone(),
                        received_at_ms: frame.received_at_ms,
                    }),
                    Completion::Applications(apps) => ResultKind::Applications((*apps).clone()),
                    Completion::Accepted => ResultKind::Accepted,
                    Completion::Manual {
                        accepted,
                        completion_observed,
                        message,
                    } => ResultKind::Manual {
                        accepted,
                        completion_observed,
                        message,
                    },
                };
                if let Err(error) = self.complete(&operation, result, ctx) {
                    self.failed(&operation, error.clone());
                    return Err(error);
                }
            }
        }
        Ok(())
    }
    pub fn confirmed_preferences(&mut self, prefs: Preferences) {
        self.preferences = prefs;
    }
    pub fn update_snapshot(&mut self, snapshot: Snapshot) {
        let identity = snapshot.virtual_screen.identity.clone();
        if self
            .snapshot
            .as_ref()
            .and_then(|s| s.virtual_screen.identity.as_ref())
            != identity.as_ref()
        {
            self.clear_preview();
            self.apps.clear();
            self.choose_app = false;
            self.app_query.clear();
        }
        if !snapshot.foreground {
            self.clear_preview();
        }
        self.snapshot = Some(snapshot);
        self.initialized = true;
    }
    pub fn failed(&mut self, operation: &OperationKey, message: String) {
        if self.pending.get(&operation.id) == Some(operation) {
            self.pending.remove(&operation.id);
            self.notice = Some(message);
        }
        if self.frame_pending.as_ref() == Some(operation) {
            self.frame_pending = None;
        }
    }
    pub fn complete(
        &mut self,
        operation: &OperationKey,
        result: ResultKind,
        ctx: &egui::Context,
    ) -> Result<(), String> {
        if self.pending.get(&operation.id) != Some(operation) {
            return Err("页面结果不属于当前操作代次".into());
        }
        match result {
            ResultKind::PendingPlatform(_) => return Ok(()),
            ResultKind::Frame(frame) => self.apply_frame(operation, frame, ctx)?,
            ResultKind::Snapshot(snapshot) => self.update_snapshot(snapshot),
            ResultKind::Applications(apps) => self.apps = apps,
            ResultKind::Manual {
                accepted,
                completion_observed,
                message,
            } => {
                self.acknowledgements.push(ManualAck {
                    operation: operation.clone(),
                    accepted,
                    completion_observed,
                    message,
                });
                if self.acknowledgements.len() > 32 {
                    self.acknowledgements.remove(0);
                }
            }
            ResultKind::Accepted => self.notice = Some("系统已接受请求".into()),
            ResultKind::PreferencesToSave(_) => {
                return Err("偏好尚未保存，应由actor保存后确认".into());
            }
        }
        self.pending.remove(&operation.id);
        Ok(())
    }
    fn apply_frame(
        &mut self,
        operation: &OperationKey,
        frame: Frame,
        ctx: &egui::Context,
    ) -> Result<(), String> {
        if self.frame_pending.as_ref() != Some(operation) {
            return Err("预览帧请求已过期".into());
        }
        self.frame_pending = None;
        let Some(state) = self.snapshot.as_ref() else {
            return Err("页面状态尚未连接".into());
        };
        if !state.foreground
            || !self.inline_preview
            || state.virtual_screen.closing
            || state.virtual_screen.identity.as_ref() != Some(&frame.identity)
        {
            return Err("预览帧所属屏幕已变更".into());
        }
        let pixels = &frame.pixels;
        if pixels.width == 0
            || pixels.height == 0
            || pixels.width as u64 * pixels.height as u64 > 4_194_304
            || pixels.rgba.len() != pixels.width as usize * pixels.height as usize * 4
        {
            return Err("预览帧像素预算无效".into());
        }
        let color = egui::ColorImage::from_rgba_unmultiplied(
            [pixels.width as usize, pixels.height as usize],
            &pixels.rgba,
        );
        if let Some(texture) = self.texture.as_mut() {
            texture.set(color, egui::TextureOptions::LINEAR);
        } else {
            self.texture = Some(ctx.load_texture(
                "automation-virtual-preview",
                color,
                egui::TextureOptions::LINEAR,
            ));
        }
        self.frame_identity = Some(frame.identity);
        self.frame_size = (pixels.width, pixels.height);
        self.last_frame = Instant::now();
        Ok(())
    }
    fn emit(&mut self, kind: CommandKind, commands: &mut Vec<Command>) {
        if self.pending.len() >= 16 {
            self.notice = Some("页面操作队列繁忙".into());
            return;
        }
        let operation = OperationKey {
            id: crate::controller::unique_id(),
            conversation_id: None,
            generation: self.generation,
        };
        if matches!(kind, CommandKind::ReadFrame(_)) {
            self.frame_pending = Some(operation.clone());
        }
        self.pending.insert(operation.id.clone(), operation.clone());
        commands.push(Command { operation, kind });
    }
    pub fn show(&mut self, ui: &mut egui::Ui, runtime_ready: bool) -> Vec<Request> {
        let mut requests = Vec::new();
        if !self.visible {
            self.visible = true;
            requests.push(Request::PageVisible(true));
        }
        requests.extend(
            self.commands(ui, runtime_ready)
                .into_iter()
                .map(Request::Execute),
        );
        requests
    }
    fn commands(&mut self, ui: &mut egui::Ui, runtime_ready: bool) -> Vec<Command> {
        let mut commands = Vec::new();
        if !self.initialized && runtime_ready && self.pending.is_empty() {
            self.emit(CommandKind::Refresh, &mut commands);
        }
        let Some(snapshot) = self.snapshot.clone() else {
            ui.spinner();
            ui.label("正在读取自动化状态");
            return commands;
        };
        if !snapshot.android {
            ui.label("当前平台没有Android自动化服务");
            return commands;
        }
        let busy = self.pending.keys().any(|id| {
            self.frame_pending
                .as_ref()
                .is_none_or(|frame| frame.id != *id)
        });
        let ready = runtime_ready && !busy && self.pending.len() < 16;
        controls::card(ui, 12).show(ui, |ui| {
            ui.set_width(ui.available_width());
            controls::text(ui, "虚拟屏", 17.0, 24.0, ui.visuals().text_color());
            controls::description(ui, "让 Agent 在独立屏幕里操作应用。");
            ui.add_space(6.0);
            let screen = &snapshot.virtual_screen;
            if let Some(identity) = screen.identity.as_ref() {
                let virtual_enabled = ready && !screen.closing;
                ui.label(format!(
                    "屏幕{} · {}×{} · {}",
                    identity.display_id,
                    identity.width,
                    identity.height,
                    if screen.closing {
                        "正在关闭"
                    } else if screen.has_frame {
                        "画面已就绪"
                    } else {
                        "等待应用画面"
                    }
                ));
                ui.horizontal_wrapped(|ui| {
                    let enabled = virtual_enabled;
                    if ui
                        .add_enabled(
                            enabled,
                            egui::Button::new(if screen.preview_requested {
                                "隐藏悬浮预览"
                            } else {
                                "显示悬浮预览"
                            }),
                        )
                        .clicked()
                    {
                        self.emit(
                            CommandKind::Virtual(
                                crate::platform::virtual_screen::Action::Preview {
                                    expected: identity.clone(),
                                    visible: !screen.preview_requested,
                                },
                            ),
                            &mut commands,
                        );
                    }
                    if ui
                        .add_enabled(enabled, egui::Button::new("打开应用"))
                        .clicked()
                    {
                        self.choose_app = true;
                        if self.apps.is_empty() {
                            self.emit(CommandKind::LoadApplications, &mut commands);
                        }
                    }
                    if ui
                        .add_enabled(
                            ready,
                            egui::Button::new(if screen.closing {
                                "重试关闭"
                            } else {
                                "关闭虚拟屏"
                            }),
                        )
                        .clicked()
                    {
                        self.emit(
                            CommandKind::Virtual(crate::platform::virtual_screen::Action::Close {
                                expected: identity.clone(),
                            }),
                            &mut commands,
                        );
                    }
                });
                ui.label("隐藏预览后，屏内应用继续运行。关闭后，新的操作才恢复选择主屏。");
                if self.choose_app {
                    ui.text_edit_singleline(&mut self.app_query);
                    let query = self.app_query.to_lowercase();
                    let apps = self
                        .apps
                        .iter()
                        .filter(|a| {
                            a.label.to_lowercase().contains(&query)
                                || a.package.to_lowercase().contains(&query)
                        })
                        .cloned()
                        .collect::<Vec<_>>();
                    egui::ScrollArea::vertical()
                        .max_height(220.)
                        .id_salt("automation-app-picker")
                        .show(ui, |ui| {
                            for app in apps {
                                if ui
                                    .add_enabled(
                                        virtual_enabled,
                                        egui::Button::new(format!(
                                            "{} · {}",
                                            app.label, app.package
                                        )),
                                    )
                                    .clicked()
                                {
                                    self.emit(
                                        CommandKind::LaunchApplication {
                                            expected: identity.clone(),
                                            component: app.component,
                                        },
                                        &mut commands,
                                    );
                                    self.choose_app = false;
                                }
                            }
                        });
                    if ui.button("收起应用列表").clicked() {
                        self.choose_app = false;
                    }
                }
                if ui
                    .checkbox(&mut self.inline_preview, "在此页预览并手动控制")
                    .changed()
                    && !self.inline_preview
                {
                    self.clear_preview();
                }
                if self.inline_preview && snapshot.foreground && !screen.closing {
                    if self.frame_pending.is_none()
                        && self.last_frame.elapsed() >= Duration::from_millis(160)
                        && ready
                    {
                        self.emit(CommandKind::ReadFrame(identity.clone()), &mut commands);
                    }
                    if self.frame_identity.as_ref() == Some(identity) {
                        if let Some(texture) = self.texture.as_ref() {
                            let width = ui.available_width().min(400.);
                            let size = Vec2::new(
                                width,
                                width * self.frame_size.1 as f32 / self.frame_size.0 as f32,
                            );
                            let response = ui.add(
                                egui::Image::new((texture.id(), size))
                                    .sense(Sense::click_and_drag()),
                            );
                            if response.is_pointer_button_down_on() {
                                if self.down.is_none() {
                                    if let Some(pos) = response.interact_pointer_pos() {
                                        self.down = Some((identity.clone(), pos, Instant::now()));
                                    }
                                }
                            }
                            if response.clicked() || response.drag_stopped() {
                                if let Some((expected, start, at)) = self.down.take() {
                                    if let Some(end) = response.interact_pointer_pos() {
                                        if expected == *identity
                                            && response.rect.contains(start)
                                            && response.rect.contains(end)
                                        {
                                            let point = |p: egui::Pos2| {
                                                (
                                                    (p.x - response.rect.left())
                                                        / response.rect.width()
                                                        * identity.width as f32,
                                                    (p.y - response.rect.top())
                                                        / response.rect.height()
                                                        * identity.height as f32,
                                                )
                                            };
                                            let (x1, y1) = point(start);
                                            let (x2, y2) = point(end);
                                            let duration_ms =
                                                at.elapsed().as_millis().clamp(60, 5000) as u64;
                                            let input = if start.distance(end) < 12. {
                                                ManualInput::Tap {
                                                    identity: expected,
                                                    x: x2,
                                                    y: y2,
                                                    duration_ms,
                                                }
                                            } else {
                                                ManualInput::Swipe {
                                                    identity: expected,
                                                    x1,
                                                    y1,
                                                    x2,
                                                    y2,
                                                    duration_ms,
                                                }
                                            };
                                            self.emit(CommandKind::Manual(input), &mut commands);
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        ui.label("等待预览画面");
                    }
                    ui.ctx().request_repaint_after(Duration::from_millis(160));
                }
            } else if ui
                .add_enabled(ready && !screen.closing, egui::Button::new("开启虚拟屏"))
                .clicked()
            {
                self.emit(
                    CommandKind::Virtual(crate::platform::virtual_screen::Action::OpenChecked {
                        expected: None,
                        preview: true,
                    }),
                    &mut commands,
                );
            }
            if let Some(error) = screen.error.as_ref() {
                ui.colored_label(ui.visuals().error_fg_color, error);
            }
        });
        ui.add_space(8.0);
        controls::card(ui, 12).show(ui, |ui| {
            ui.set_width(ui.available_width());
            controls::text(ui, "边缘光", 17.0, 24.0, ui.visuals().text_color());
            controls::description(ui, "Agent 操作手机时的边缘提示。");
            ui.add_space(6.0);
            let mut enabled = self.preferences.edge_enabled;
            let mut foreground = self.preferences.edge_in_foreground;
            ui.add_enabled_ui(ready, |ui| {
                controls::row(ui, &mut enabled, "显示边缘光", "");
                ui.add_enabled_ui(enabled, |ui| {
                    controls::row(ui, &mut foreground, "在 JAgent 前台显示", "");
                });
            });
            let mut changed = enabled != self.preferences.edge_enabled
                || foreground != self.preferences.edge_in_foreground;
            let mut prefs = self.preferences.clone();
            prefs.edge_enabled = enabled;
            prefs.edge_in_foreground = foreground;
            let color = self.preferences.edge_color.to_be_bytes();
            let mut color = Color32::from_rgba_unmultiplied(color[1], color[2], color[3], color[0]);
            if ui
                .add_enabled_ui(ready, |ui| ui.color_edit_button_srgba(&mut color))
                .inner
                .changed()
            {
                prefs.edge_color = u32::from_be_bytes([color.a(), color.r(), color.g(), color.b()]);
                changed = true;
            }
            if changed && ready {
                self.emit(CommandKind::SavePreferences(prefs), &mut commands);
            }
            if let Some(error) = snapshot.indicator.error.as_ref() {
                ui.colored_label(ui.visuals().error_fg_color, error);
            }
        });
        ui.add_space(8.0);
        controls::card(ui, 12).show(ui, |ui| {
            ui.set_width(ui.available_width());
            controls::text(ui, "自动化权限", 17.0, 24.0, ui.visuals().text_color());
            controls::description(ui, "查看各项系统授权。");
            ui.add_space(6.0);
            ui.horizontal_wrapped(|ui| {
                ui.label(if snapshot.accessibility.connected {
                    "无障碍已连接"
                } else {
                    "无障碍未连接"
                });
                if ui
                    .add_enabled(ready, egui::Button::new("无障碍设置"))
                    .clicked()
                {
                    self.emit(CommandKind::Settings(Page::Accessibility), &mut commands);
                }
                if ui
                    .add_enabled(ready, egui::Button::new("刷新状态"))
                    .clicked()
                {
                    self.emit(CommandKind::Refresh, &mut commands);
                }
            });
            ui.label("文字定位需要无障碍服务；副屏无障碍输入需要Android11及以上。");
            ui.horizontal_wrapped(|ui| {
                let status = &snapshot.shizuku;
                ui.label(if status.connected && status.granted {
                    "Shizuku / Sui已授权"
                } else if status.connected {
                    "Shizuku / Sui已连接，尚未授权"
                } else {
                    "Shizuku / Sui未连接"
                });
                if ui
                    .add_enabled(
                        ready && status.connected && !status.granted && !status.permission_pending,
                        egui::Button::new("请求授权"),
                    )
                    .clicked()
                {
                    self.emit(CommandKind::RequestShizuku, &mut commands);
                }
                if ui
                    .add_enabled(ready && status.installed, egui::Button::new("打开Shizuku"))
                    .clicked()
                {
                    self.emit(CommandKind::Settings(Page::Shizuku), &mut commands);
                }
            });
            ui.label(if snapshot.root_generation.is_some() {
                "Root连接已确认"
            } else {
                "Root尚未确认"
            });
            ui.horizontal_wrapped(|ui| {
                if ui
                    .add_enabled(ready, egui::Button::new("悬浮窗设置"))
                    .clicked()
                {
                    self.emit(CommandKind::Settings(Page::Overlay), &mut commands);
                }
                if ui
                    .add_enabled(ready, egui::Button::new("应用权限设置"))
                    .clicked()
                {
                    self.emit(CommandKind::Settings(Page::AppPermissions), &mut commands);
                }
            });
            for (kind, label, outcome) in [
                (
                    PermissionKind::Notifications,
                    "通知",
                    &snapshot.notifications,
                ),
                (PermissionKind::Microphone, "麦克风", &snapshot.microphone),
                (
                    PermissionKind::LocalNetwork,
                    "局域网",
                    &snapshot.local_network,
                ),
            ] {
                ui.horizontal(|ui| {
                    ui.label(format!(
                        "{label}：{}",
                        match outcome {
                            PermissionOutcome::Granted => "已授权",
                            PermissionOutcome::Denied { .. } => "未授权",
                            PermissionOutcome::Cancelled => "已取消",
                            PermissionOutcome::Unavailable => "不可用",
                        }
                    ));
                    if ui
                        .add_enabled(
                            ready && !matches!(outcome, PermissionOutcome::Granted),
                            egui::Button::new("请求权限"),
                        )
                        .clicked()
                    {
                        self.emit(CommandKind::RequestPermission(kind), &mut commands);
                    }
                });
            }
        });
        if let Some(notice) = self.notice.as_ref() {
            ui.label(notice);
        }
        if !self.pending.is_empty() {
            ui.horizontal(|ui| {
                ui.spinner();
                ui.label("正在等待系统结果");
            });
        }
        for ack in self.acknowledgements.iter().rev().take(4) {
            ui.label(format!(
                "{} · {}",
                if ack.completion_observed {
                    "手势已完成"
                } else if ack.accepted {
                    "输入已接受"
                } else {
                    "输入失败"
                },
                ack.message
            ));
        }
        commands
    }
}
