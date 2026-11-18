//! Linux management emits requests. Filesystem checks and downloads stay on the actor.
use crate::{
    controller::linux_runtime::{Request, Update},
    linux::{Distro, Key, Phase, Protection, Record, ServiceRow, Status},
    platform::permissions::OperationKey,
};
use eframe::egui::{self, RichText};
use std::collections::{HashMap, HashSet};

pub struct LinuxView {
    generation: u64,
    process_token: Option<String>,
    revision: u64,
    supported: bool,
    statuses: Vec<Status>,
    records: Vec<Record>,
    running: Vec<ServiceRow>,
    protection: Protection,
    pending: HashMap<String, OperationKey>,
    cancel_pending: HashMap<String, Key>,
    cancelling: HashSet<Key>,
    refresh_pending: Option<String>,
    requested_initial: bool,
    remove_confirm: Option<Distro>,
    notice: Option<String>,
    error: Option<String>,
}
impl Default for LinuxView {
    fn default() -> Self {
        Self {
            generation: 1,
            process_token: None,
            revision: 0,
            supported: false,
            statuses: Vec::new(),
            records: Vec::new(),
            running: Vec::new(),
            protection: Protection::Unavailable,
            pending: HashMap::new(),
            cancel_pending: HashMap::new(),
            cancelling: HashSet::new(),
            refresh_pending: None,
            requested_initial: false,
            remove_confirm: None,
            notice: None,
            error: None,
        }
    }
}
impl LinuxView {
    pub fn operation_pending(&self) -> bool {
        !self.pending.is_empty()
            || !self.cancel_pending.is_empty()
            || self.refresh_pending.is_some()
            || !self.running.is_empty()
    }
    pub fn refresh(&mut self) -> Option<Request> {
        if self.refresh_pending.is_some() {
            return None;
        }
        let request_id = crate::controller::unique_id();
        self.requested_initial = true;
        self.refresh_pending = Some(request_id.clone());
        Some(Request::Refresh { request_id })
    }
    pub fn disconnected(&mut self) {
        self.pending.clear();
        self.cancel_pending.clear();
        self.cancelling.clear();
        self.refresh_pending = None;
        self.requested_initial = false;
        self.process_token = None;
        self.revision = 0;
        self.remove_confirm = None;
        self.running.clear();
        self.error = Some("运行时连接已断开，Linux操作结果尚未确认".into());
    }
    pub fn dispatch_failed(&mut self, request: &Request, error: &str) {
        match request {
            Request::Install { operation, .. } | Request::Remove { operation, .. } => {
                if self.pending.get(&operation.id) == Some(operation) {
                    self.pending.remove(&operation.id);
                }
            }
            Request::Cancel { request_id, key } => {
                if self.cancel_pending.get(request_id) == Some(key) {
                    self.cancel_pending.remove(request_id);
                }
            }
            Request::Refresh { request_id } => {
                if self.refresh_pending.as_ref() == Some(request_id) {
                    self.refresh_pending = None;
                }
            }
        }
        self.error = Some(error.into());
    }
    fn record(&mut self, record: Record) {
        if let Some(previous) = self.records.iter_mut().find(|previous| {
            previous.operation.id == record.operation.id
                && previous.operation.generation == record.operation.generation
        }) {
            if record.revision < previous.revision
                || (!previous.phase.active() && record.phase.active())
            {
                return;
            }
            *previous = record;
        } else if self.records.len() < 128 {
            self.records.push(record);
        }
        self.cancelling.retain(|key| {
            self.records.iter().any(|record| {
                record.operation.id == key.id
                    && record.operation.generation == key.generation
                    && record.phase.active()
            })
        });
        self.records.sort_by(|a, b| {
            b.phase
                .active()
                .cmp(&a.phase.active())
                .then_with(|| b.created_at.cmp(&a.created_at))
                .then_with(|| a.operation.id.cmp(&b.operation.id))
                .then_with(|| a.operation.generation.cmp(&b.operation.generation))
        });
    }
    pub fn apply(&mut self, update: Update) {
        match update {
            Update::Snapshot {
                revision,
                process_token,
                supported,
                statuses,
                records,
                protection,
                running,
            } => {
                let same_process = self.process_token.as_ref() == Some(&process_token);
                if same_process && revision < self.revision {
                    return;
                }
                if statuses.len() > 2 || records.len() > 128 || running.len() > 1 {
                    self.error = Some("Linux状态超过显示预算".into());
                    return;
                }
                if !same_process {
                    self.pending.clear();
                    self.cancel_pending.clear();
                    self.cancelling.clear();
                    self.records.clear();
                    self.remove_confirm = None;
                }
                self.process_token = Some(process_token);
                self.revision = revision;
                self.supported = supported;
                self.statuses = statuses;
                self.running = running;
                self.protection = protection;
                for record in records {
                    self.record(record);
                }
            }
            Update::Accepted {
                operation,
                key,
                record,
            } => {
                if self.pending.get(&operation.id) != Some(&operation)
                    || key.id != operation.id
                    || key.generation != operation.generation
                    || self.process_token.as_ref() != Some(&key.process_token)
                {
                    return;
                }
                self.pending.remove(&operation.id);
                self.record(record);
                self.notice = Some("操作已接收，正在等待后台结果".into());
                self.error = None;
            }
            Update::Rejected { operation, error } => {
                if self.pending.get(&operation.id) == Some(&operation) {
                    self.pending.remove(&operation.id);
                    self.error = Some(error);
                }
            }
            Update::CancelFinished {
                request_id,
                key,
                accepted,
                error,
            } => {
                if self.cancel_pending.get(&request_id) != Some(&key) {
                    return;
                }
                self.cancel_pending.remove(&request_id);
                if let Some(error) = error {
                    self.error = Some(error);
                } else if accepted {
                    self.cancelling.insert(key);
                    self.notice =
                        Some("已请求取消，等待操作结果；已经发布的环境以最终状态为准".into());
                } else {
                    self.notice = Some("此操作已结束或当前无法取消，请刷新状态".into());
                }
            }
            Update::Refreshed { request_id } => {
                if self.refresh_pending.as_ref() == Some(&request_id) {
                    self.refresh_pending = None;
                }
            }
            Update::RefreshFailed { request_id, error } => {
                if self.refresh_pending.as_ref() == Some(&request_id) {
                    self.refresh_pending = None;
                    self.error = Some(error);
                }
            }
            Update::Progress { record } | Update::Finished { record } => self.record(record),
            Update::Recovery {
                distro,
                restored,
                error,
            } => {
                if let Some(error) = error {
                    self.error = Some(error);
                } else {
                    self.notice = Some(format!(
                        "{}：{}",
                        name(distro),
                        if restored {
                            "已恢复环境"
                        } else {
                            "恢复检查已结束"
                        }
                    ));
                }
            }
        }
    }
    fn begin(&mut self, distro: Distro, remove: bool) -> Option<Request> {
        if self.process_token.is_none() || !self.pending.is_empty() {
            return None;
        }
        let operation = OperationKey {
            id: crate::controller::unique_id(),
            conversation_id: None,
            generation: self.generation,
        };
        self.pending.insert(operation.id.clone(), operation.clone());
        self.error = None;
        Some(if remove {
            Request::Remove { operation, distro }
        } else {
            Request::Install { operation, distro }
        })
    }
    pub fn show(&mut self, ui: &mut egui::Ui, connected: bool) -> Vec<Request> {
        let mut requests = Vec::new();
        egui::CollapsingHeader::new("Linux 环境")
            .id_salt("linux_management")
            .show(ui, |ui| {
                if self.process_token.is_none()
                    && !self.requested_initial
                    && connected
                    && let Some(request) = self.refresh()
                {
                    requests.push(request);
                }
                if let Some(error) = &self.error {
                    ui.colored_label(ui.visuals().error_fg_color, error);
                }
                if let Some(notice) = &self.notice {
                    ui.label(RichText::new(notice).small().weak());
                }
                match &self.protection {
                    Protection::Unavailable => {
                        ui.label(RichText::new("后台安装保护尚未可用").small().weak());
                    }
                    Protection::Requested => {
                        ui.label("正在准备后台安装保护");
                    }
                    Protection::Confirmed => {
                        ui.label("后台安装保护已确认");
                    }
                    Protection::Failed(error) => {
                        ui.colored_label(ui.visuals().error_fg_color, error);
                    }
                }
                if self.process_token.is_some() && !self.supported {
                    ui.label("当前设备或随包组件无法安装Linux环境。");
                }
                let busy = !self.pending.is_empty()
                    || !self.running.is_empty()
                    || self.statuses.iter().any(|status| status.busy)
                    || self.records.iter().any(|record| record.phase.active());
                let editable = connected && self.process_token.is_some() && !busy;
                let mut install = None;
                let mut remove = None;
                for distro in [Distro::Alpine, Distro::Debian] {
                    let status = self.statuses.iter().find(|status| status.distro == distro);
                    let available = status.is_some_and(|status| status.available);
                    let spec = distro.spec();
                    ui.push_id(distro.id(), |ui| {
                        ui.label(format!(
                            "{} · {} · {}",
                            name(distro),
                            spec.version,
                            if available { "已安装" } else { "未安装" }
                        ));
                        if let Some(note) = status.and_then(|status| status.note.as_ref()) {
                            ui.label(note);
                        }
                        ui.label(
                            RichText::new(format!(
                                "下载约 {}，环境预留 {}",
                                super::byte_size(spec.download_bytes),
                                super::byte_size(spec.installed_bytes)
                            ))
                            .small()
                            .weak(),
                        );
                        ui.horizontal_wrapped(|ui| {
                            if ui
                                .add_enabled(
                                    editable && self.supported && !available,
                                    egui::Button::new("安装"),
                                )
                                .clicked()
                            {
                                install = Some(distro);
                            }
                            if ui
                                .add_enabled(editable && available, egui::Button::new("移除环境"))
                                .clicked()
                            {
                                remove = Some(distro);
                            }
                        });
                    });
                }
                if let Some(distro) = install
                    && let Some(request) = self.begin(distro, false)
                {
                    requests.push(request);
                }
                if let Some(distro) = remove {
                    self.remove_confirm = Some(distro);
                }
                if ui
                    .add_enabled(
                        connected && self.refresh_pending.is_none(),
                        egui::Button::new("刷新Linux状态"),
                    )
                    .clicked()
                    && let Some(request) = self.refresh()
                {
                    requests.push(request);
                }
                let mut cancel = None;
                egui::ScrollArea::vertical()
                    .id_salt("linux_operations")
                    .max_height(180.)
                    .show(ui, |ui| {
                        for record in self.records.iter().take(16) {
                            ui.push_id((&record.operation.id, record.operation.generation), |ui| {
                                ui.label(format!(
                                    "{} · {} · {}",
                                    name(record.distro),
                                    phase(record.phase),
                                    super::byte_size(record.bytes)
                                ));
                                if record.available {
                                    ui.label(RichText::new("此次操作记录：环境可用").small());
                                }
                                if record.published {
                                    ui.label(RichText::new("此次操作已发布环境").small());
                                }
                                if record.cancel_requested {
                                    ui.label("已请求取消");
                                }
                                if let Some(error) = &record.error {
                                    ui.colored_label(ui.visuals().error_fg_color, error);
                                }
                                if let Some(row) = self.running.iter().find(|row| {
                                    row.key.id == record.operation.id
                                        && row.key.generation == record.operation.generation
                                }) {
                                    let cancelling = self.cancelling.contains(&row.key)
                                        || self.cancel_pending.values().any(|key| key == &row.key);
                                    if ui
                                        .add_enabled(
                                            connected && record.phase.active() && !cancelling,
                                            egui::Button::new(if cancelling {
                                                "正在取消"
                                            } else {
                                                "取消操作"
                                            }),
                                        )
                                        .clicked()
                                    {
                                        cancel = Some(row.key.clone());
                                    }
                                }
                            });
                        }
                    });
                if let Some(key) = cancel {
                    let request_id = crate::controller::unique_id();
                    self.cancel_pending.insert(request_id.clone(), key.clone());
                    requests.push(Request::Cancel { request_id, key });
                }
            });
        if let Some(distro) = self.remove_confirm {
            let mut decision = None;
            egui::Window::new(format!("移除{}环境", name(distro)))
                .collapsible(false)
                .show(ui.ctx(), |ui| {
                    ui.label("这会删除该Linux环境中的文件。仍在使用它的终端或后台任务需要先结束。");
                    ui.horizontal(|ui| {
                        if ui.button("保留环境").clicked() {
                            decision = Some(false);
                        }
                        if ui
                            .add_enabled(
                                connected && self.pending.is_empty() && self.running.is_empty(),
                                egui::Button::new("确认移除"),
                            )
                            .clicked()
                        {
                            decision = Some(true);
                        }
                    });
                });
            if let Some(remove) = decision {
                self.remove_confirm = None;
                if remove && let Some(request) = self.begin(distro, true) {
                    requests.push(request);
                }
            }
        }
        requests
    }
}
fn name(distro: Distro) -> &'static str {
    match distro {
        Distro::Alpine => "Alpine",
        Distro::Debian => "Debian",
    }
}
fn phase(phase: Phase) -> &'static str {
    match phase {
        Phase::Claimed => "等待启动",
        Phase::Downloading => "正在下载",
        Phase::Verifying => "正在校验",
        Phase::Extracting => "正在解压",
        Phase::Checking => "正在检查环境",
        Phase::Publishing => "正在发布",
        Phase::Ready => "已就绪",
        Phase::Removing => "正在移除",
        Phase::Removed => "已移除",
        Phase::Failed => "失败",
        Phase::Cancelled => "已取消",
        Phase::Interrupted => "已中断",
    }
}
