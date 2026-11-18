//! Rendering consumes actor snapshots. Every user action carries an exact key;
//! opening a system settings screen never changes a displayed grant.
use crate::{
    controller::capability_runtime::{Request, Snapshot, Update},
    platform::{
        manual_settings::Page,
        permissions::{OperationKey, PermissionKind, PermissionOutcome},
        root_capability::{Phase, Status as RootStatus},
    },
    settings_policy::{ExecutionSettings, Preference},
    shell_runner::Route,
};
use eframe::egui::{self, RichText};
use std::{collections::HashMap, sync::Arc};

#[derive(Clone, Copy, PartialEq, Eq)]
enum PendingKind {
    Refresh,
    Root,
    Preference,
    Settings,
    Permissions,
    Shizuku,
}
struct Pending {
    operation: OperationKey,
    kind: PendingKind,
}
pub(super) struct CapabilityView {
    generation: u64,
    initialized: bool,
    snapshot: Option<Arc<Snapshot>>,
    root_progress: Option<RootStatus>,
    pending: HashMap<String, Pending>,
    preference: Preference,
    notice: Option<String>,
}
impl Default for CapabilityView {
    fn default() -> Self {
        Self {
            generation: 1,
            initialized: false,
            snapshot: None,
            root_progress: None,
            pending: HashMap::new(),
            preference: Preference::Automatic,
            notice: None,
        }
    }
}
impl CapabilityView {
    pub(super) fn disconnected(&mut self) {
        self.pending.clear();
        self.initialized = false;
        self.snapshot = None;
        self.root_progress = None;
        self.generation = self.generation.saturating_add(1);
    }
    fn key(&mut self, kind: PendingKind) -> OperationKey {
        let operation = OperationKey {
            id: crate::controller::unique_id(),
            conversation_id: None,
            generation: self.generation,
        };
        self.pending.insert(
            operation.id.clone(),
            Pending {
                operation: operation.clone(),
                kind,
            },
        );
        operation
    }
    fn settle(&mut self, operation: &OperationKey, kind: PendingKind) -> bool {
        if self
            .pending
            .get(&operation.id)
            .is_some_and(|p| p.operation == *operation && p.kind == kind)
        {
            self.pending.remove(&operation.id);
            true
        } else {
            false
        }
    }
    fn busy(&self, kind: PendingKind) -> bool {
        self.pending.values().any(|p| p.kind == kind)
    }
    pub(super) fn dispatch_failed(&mut self, operation: &OperationKey) {
        if self
            .pending
            .get(&operation.id)
            .is_some_and(|p| p.operation == *operation)
        {
            self.pending.remove(&operation.id);
            self.notice = Some("操作未送达，请重试".into());
        }
    }
    pub(super) fn apply(&mut self, update: Update) -> Option<ExecutionSettings> {
        match update {
            Update::Snapshot(snapshot) => {
                if self
                    .snapshot
                    .as_ref()
                    .is_none_or(|old| snapshot.routing.revision >= old.routing.revision)
                {
                    if !self.busy(PendingKind::Preference) {
                        self.preference = snapshot.routing.preference;
                    }
                    self.snapshot = Some(snapshot);
                }
            }
            Update::Refreshed {
                operation,
                snapshot,
            } => {
                if self.settle(&operation, PendingKind::Refresh) {
                    self.initialized = true;
                    self.preference = snapshot.routing.preference;
                    self.snapshot = Some(snapshot);
                }
            }
            Update::RootStatus { operation, status } => {
                if self
                    .pending
                    .get(&operation.id)
                    .is_some_and(|p| p.operation == operation && p.kind == PendingKind::Root)
                {
                    if status.phase != Phase::Checking {
                        self.settle(&operation, PendingKind::Root);
                    }
                    self.notice = status.error.clone();
                    if let Some(snapshot) = &mut self.snapshot {
                        Arc::make_mut(snapshot).routing.root = status.clone();
                    }
                    self.root_progress = Some(status);
                }
            }
            Update::PreferenceConfirmed {
                operation,
                settings,
            } => {
                if self.settle(&operation, PendingKind::Preference) {
                    self.preference = settings.preference();
                    self.notice = Some("执行方式已保存，下次操作按新的设置准备".into());
                    return Some(settings);
                }
            }
            Update::SettingsOpened { operation, .. } => {
                if self.settle(&operation, PendingKind::Settings) {
                    self.notice = Some("系统设置已打开，返回后刷新查看授权状态".into());
                }
            }
            Update::PermissionFinished {
                operation,
                permissions,
                error,
            } => {
                if self.settle(&operation, PendingKind::Permissions) {
                    if let Some(snapshot) = &mut self.snapshot {
                        let snapshot = Arc::make_mut(snapshot);
                        for (kind, outcome) in permissions {
                            snapshot
                                .permissions
                                .retain(|(existing, _)| *existing != kind);
                            snapshot.permissions.push((kind, outcome));
                        }
                    }
                    self.notice = error;
                }
            }
            Update::ShizukuPermissionFinished {
                operation,
                granted,
                cancelled,
                error,
            } => {
                if self.settle(&operation, PendingKind::Shizuku) {
                    if let Some(snapshot) = &mut self.snapshot {
                        Arc::make_mut(snapshot).routing.shizuku.granted =
                            granted && !cancelled && error.is_none();
                    }
                    self.notice = error.or_else(|| {
                        Some(
                            if cancelled {
                                "Shizuku授权已取消"
                            } else if granted {
                                "Shizuku授权已确认"
                            } else {
                                "Shizuku尚未授权"
                            }
                            .into(),
                        )
                    });
                }
            }
            Update::Failed { operation, message } => {
                if self
                    .pending
                    .get(&operation.id)
                    .is_some_and(|pending| pending.operation == operation)
                {
                    self.pending.remove(&operation.id);
                    self.notice = Some(message);
                }
            }
            Update::WaitingForTerminal { operation, message } => {
                if self
                    .pending
                    .get(&operation.id)
                    .is_some_and(|pending| pending.operation == operation)
                {
                    self.notice = Some(message);
                }
            }
        }
        None
    }
    pub(super) fn show(
        &mut self,
        ui: &mut egui::Ui,
        ready: bool,
        settings_clean: bool,
    ) -> Vec<Request> {
        let mut requests = Vec::new();
        if ready && !self.initialized && !self.busy(PendingKind::Refresh) {
            // Initial refresh is attempted once. Failure needs explicit retry.
            self.initialized = true;
            let key = self.key(PendingKind::Refresh);
            requests.push(Request::Refresh(key));
        }
        let snapshot = self.snapshot.clone();
        ui.heading("权限与执行方式");
        if let Some(message) = &self.notice {
            ui.add(egui::Label::new(message).wrap());
        }
        if ui
            .add_enabled(
                ready && !self.busy(PendingKind::Refresh),
                egui::Button::new("刷新能力状态"),
            )
            .clicked()
        {
            requests.push(Request::Refresh(self.key(PendingKind::Refresh)));
        }
        let selectable = ready && settings_clean && !self.busy(PendingKind::Preference);
        ui.add_enabled_ui(selectable, |ui| {
            super::choices(
                ui,
                "执行方式",
                &mut self.preference,
                &[
                    (Preference::Automatic, "自动"),
                    (Preference::AppUid, "仅应用权限"),
                    (Preference::Accessibility, "无障碍"),
                    (Preference::Shizuku, "Shizuku"),
                    (Preference::Root, "Root"),
                ],
            );
        });
        let saved = snapshot.as_ref().map_or(Preference::Automatic, |snapshot| {
            snapshot.routing.preference
        });
        if ui
            .add_enabled(
                selectable && self.preference != saved,
                egui::Button::new("保存执行方式"),
            )
            .clicked()
        {
            let operation = self.key(PendingKind::Preference);
            requests.push(Request::SelectPreference {
                operation,
                preference: self.preference,
            });
        }
        if !settings_clean {
            ui.label("请先保存或放弃其它设置修改，再单独调整执行方式。");
        }
        ui.label(
            RichText::new("执行方式限定可用权限；保存Root偏好不会触发检测或授权。")
                .small()
                .weak(),
        );
        let Some(snapshot) = snapshot else {
            ui.label("能力状态尚未读取");
            return requests;
        };
        ui.label(format!(
            "当前Shell路线：{}",
            match snapshot.routing.selected_shell {
                Route::AppUid => "应用权限",
                Route::Shizuku { .. } => "Shizuku",
                Route::Root { .. } => "Root",
            }
        ));
        ui.separator();
        let android = ready && snapshot.routing.android;
        ui.heading("无障碍");
        ui.label(if snapshot.routing.accessibility.connected {
            "已连接"
        } else {
            "未连接"
        });
        self.settings_button(
            ui,
            android,
            "打开无障碍设置",
            Page::Accessibility,
            &mut requests,
        );
        ui.heading("Shizuku（ADB级）");
        ui.label(
            if snapshot.routing.shizuku.connected && snapshot.routing.shizuku.granted {
                "服务已连接且已授权"
            } else if snapshot.routing.shizuku.connected {
                "服务已连接，等待授权"
            } else if snapshot.routing.shizuku.installed {
                "已安装，服务尚未连接"
            } else {
                "尚未安装"
            },
        );
        self.settings_button(ui, android, "打开Shizuku", Page::Shizuku, &mut requests);
        if ui
            .add_enabled(
                android && snapshot.routing.shizuku.connected && !self.busy(PendingKind::Shizuku),
                egui::Button::new("请求Shizuku授权"),
            )
            .clicked()
        {
            requests.push(Request::ShizukuPermission(self.key(PendingKind::Shizuku)));
        }
        ui.heading("Root");
        let root = self
            .root_progress
            .as_ref()
            .unwrap_or(&snapshot.routing.root);
        ui.label(match root.phase {
            Phase::NotChecked => "本次进程尚未检测",
            Phase::Checking => "检测中",
            Phase::Ready => "本次进程已确认UID0",
            Phase::Unavailable => "未获得Root权限",
            Phase::Cancelled => "检测已取消",
            Phase::Uncertain => "检测或清理尚未确认",
        });
        if root.remembered_checked_at > 0 {
            ui.label(format!(
                "上次保存：{} · 时间戳 {}（历史记录）",
                if root.remembered_available {
                    "可用"
                } else {
                    "不可用"
                },
                root.remembered_checked_at
            ));
        }
        if ui
            .add_enabled(
                android && !self.busy(PendingKind::Root) && !root.quarantined,
                egui::Button::new("检测Root"),
            )
            .clicked()
        {
            requests.push(Request::ProbeRoot(self.key(PendingKind::Root)));
        }
        if let Some(operation) = self
            .pending
            .values()
            .find(|p| p.kind == PendingKind::Root)
            .map(|p| p.operation.clone())
        {
            if ui.button("取消Root检测").clicked() {
                requests.push(Request::Cancel(operation));
            }
        }
        ui.separator();
        ui.heading("悬浮窗控制台");
        ui.label(if snapshot.overlay_allowed {
            "系统已授权"
        } else {
            "系统未授权"
        });
        self.settings_button(ui, android, "打开悬浮窗设置", Page::Overlay, &mut requests);
        ui.heading("后台运行");
        ui.label(if snapshot.battery_optimization_exempt {
            "已豁免电池优化"
        } else {
            "尚未豁免电池优化"
        });
        self.settings_button(
            ui,
            android,
            "允许后台运行",
            Page::BatteryOptimization,
            &mut requests,
        );
        ui.heading("文件访问");
        let sdk = snapshot.routing.accessibility.sdk_int;
        if sdk >= 30 {
            ui.label(if snapshot.all_files_access {
                "已获得所有文件访问权限"
            } else {
                "未获得所有文件访问权限"
            });
            self.settings_button(
                ui,
                android,
                "打开所有文件访问设置",
                Page::AllFilesAccess,
                &mut requests,
            );
        } else if (26..=29).contains(&sdk) {
            self.permission_row(
                ui,
                android,
                "读取外部存储",
                PermissionKind::ReadExternalStorage,
                &snapshot,
                &mut requests,
            );
            self.permission_row(
                ui,
                android,
                "写入外部存储",
                PermissionKind::WriteExternalStorage,
                &snapshot,
                &mut requests,
            );
        }
        ui.heading("通知、麦克风与局域网");
        for (kind, label) in [
            (PermissionKind::Notifications, "通知"),
            (PermissionKind::Microphone, "麦克风"),
            (PermissionKind::LocalNetwork, "局域网"),
        ] {
            self.permission_row(ui, android, label, kind, &snapshot, &mut requests);
        }
        requests
    }
    fn settings_button(
        &mut self,
        ui: &mut egui::Ui,
        ready: bool,
        label: &str,
        page: Page,
        requests: &mut Vec<Request>,
    ) {
        if ui
            .add_enabled(
                ready && !self.busy(PendingKind::Settings),
                egui::Button::new(label),
            )
            .clicked()
        {
            requests.push(Request::OpenSettings {
                operation: self.key(PendingKind::Settings),
                page,
            });
        }
    }
    fn permission_row(
        &mut self,
        ui: &mut egui::Ui,
        ready: bool,
        label: &str,
        kind: PermissionKind,
        snapshot: &Snapshot,
        requests: &mut Vec<Request>,
    ) {
        ui.horizontal_wrapped(|ui| {
            let state = snapshot
                .permissions
                .iter()
                .find(|(existing, _)| *existing == kind)
                .map(|(_, value)| value);
            ui.label(format!(
                "{label}：{}",
                match state {
                    Some(PermissionOutcome::Granted) => "已授权",
                    Some(PermissionOutcome::Denied { .. }) => "未授权",
                    Some(PermissionOutcome::Cancelled) => "已取消",
                    _ => "当前系统不提供",
                }
            ));
            if ui
                .add_enabled(
                    ready
                        && !self.busy(PendingKind::Permissions)
                        && !matches!(
                            state,
                            Some(PermissionOutcome::Granted | PermissionOutcome::Unavailable)
                        ),
                    egui::Button::new("请求授权"),
                )
                .clicked()
            {
                requests.push(Request::Permissions {
                    operation: self.key(PendingKind::Permissions),
                    kinds: vec![kind],
                });
            }
        });
    }
}

pub(super) fn operation(request: &Request) -> &OperationKey {
    match request {
        Request::Refresh(key)
        | Request::ProbeRoot(key)
        | Request::Cancel(key)
        | Request::ShizukuPermission(key) => key,
        Request::SelectPreference { operation, .. }
        | Request::OpenSettings { operation, .. }
        | Request::Permissions { operation, .. } => operation,
    }
}
