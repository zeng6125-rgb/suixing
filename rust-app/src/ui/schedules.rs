use super::*;
use crate::schedules::{Operation, Timing};

pub(super) struct Editor {
    id: Option<String>,
    title: String,
    prompt: String,
    timing_kind: u8,
    at: String,
    every_seconds: i64,
    daily: String,
    change_timing: bool,
}

impl Editor {
    fn new() -> Self {
        Self {
            id: None,
            title: String::new(),
            prompt: String::new(),
            timing_kind: 2,
            at: String::new(),
            every_seconds: 60,
            daily: "07:30".into(),
            change_timing: true,
        }
    }
    fn existing(schedule: &crate::schedules::Schedule) -> Self {
        Self {
            id: Some(schedule.id.clone()),
            title: schedule.title.clone(),
            prompt: schedule.prompt.clone(),
            timing_kind: match schedule.kind.as_str() {
                crate::schedules::AT => 0,
                crate::schedules::EVERY_SECONDS => 1,
                _ => 2,
            },
            at: String::new(),
            every_seconds: schedule.every_seconds.max(60),
            daily: schedule.time_of_day.clone(),
            change_timing: false,
        }
    }
    fn timing(&self) -> Timing {
        match self.timing_kind {
            0 => Timing::At(self.at.clone()),
            1 => Timing::EverySeconds(self.every_seconds),
            _ => Timing::Daily(self.daily.clone()),
        }
    }
}

impl RustApplication {
    pub(super) fn schedules_page(&mut self, ui: &mut egui::Ui) {
        let mut operation = None;
        let mut resume = None;
        let mut open_conversation = None;
        let busy = self.schedule_pending.is_some();
        egui::ScrollArea::vertical()
            .id_salt("schedules")
            .show(ui, |ui| {
                ui.heading("定时提醒");
                if cfg!(target_os = "android") {
                    ui.label("到时将启动独立会话；后台启动受限时会保留提醒，点击通知或在此恢复。");
                } else {
                    ui.label("此平台可管理计划，自动执行需要Android提醒服务。");
                }
                if let Some(result) = &self.schedule_status.alarm_result {
                    match &result.outcome {
                        Ok(Some(crate::platform::scheduler::AlarmMode::Exact)) => {
                            ui.label("下一条提醒已使用精确闹钟排程");
                        }
                        Ok(Some(crate::platform::scheduler::AlarmMode::Inexact)) => {
                            ui.label(
                                "下一条提醒使用系统非精确排程，可能延后；可在系统设置允许精确闹钟",
                            );
                        }
                        Ok(None) => {
                            ui.label("当前没有待触发的提醒");
                        }
                        Err(error) => {
                            ui.colored_label(Color32::from_rgb(190, 80, 80), error);
                        }
                    }
                }
                ui.horizontal(|ui| {
                    if ui
                        .add_enabled(!busy, egui::Button::new("新建计划"))
                        .clicked()
                    {
                        self.schedule_editor = Some(Editor::new());
                    }
                    if ui.add_enabled(!busy, egui::Button::new("刷新")).clicked() {
                        self.dispatch(Action::LoadSchedules);
                    }
                });
                if self.schedules.is_empty() {
                    ui.label(RichText::new("还没有计划").weak());
                }
                for schedule in &self.schedules {
                    ui.push_id(&schedule.id, |ui| {
                        egui::Frame::group(ui.style()).show(ui, |ui| {
                            ui.label(RichText::new(&schedule.title).strong());
                            ui.add(egui::Label::new(&schedule.prompt).wrap().selectable(true));
                            match schedule.kind.as_str() {
                                crate::schedules::AT => {
                                    ui.label(format!("一次性计划 · {}", schedule.id));
                                }
                                crate::schedules::EVERY_SECONDS => {
                                    ui.label(format!(
                                        "每 {} 秒 · {}",
                                        schedule.every_seconds, schedule.id
                                    ));
                                }
                                _ => {
                                    ui.label(format!(
                                        "每日 {} · {}",
                                        schedule.time_of_day, schedule.id
                                    ));
                                }
                            }
                            if schedule.missed_at > 0 {
                                ui.label("已过期；调整到未来时刻后可重新保存");
                            } else if !schedule.enabled {
                                ui.label("计划已暂停");
                            }
                            ui.horizontal(|ui| {
                                if ui.add_enabled(!busy, egui::Button::new("修改")).clicked() {
                                    self.schedule_editor = Some(Editor::existing(schedule));
                                }
                                if ui
                                    .add_enabled(
                                        !busy && schedule.missed_at == 0,
                                        egui::Button::new(if schedule.enabled {
                                            "暂停计划"
                                        } else {
                                            "恢复计划"
                                        }),
                                    )
                                    .clicked()
                                {
                                    operation = Some(Operation::SetEnabled {
                                        id: schedule.id.clone(),
                                        enabled: !schedule.enabled,
                                    });
                                }
                                if ui.add_enabled(!busy, egui::Button::new("删除")).clicked() {
                                    operation = Some(Operation::Delete {
                                        id: schedule.id.clone(),
                                    });
                                }
                            });
                        });
                    });
                }
                if !self.schedule_status.fires.is_empty() {
                    ui.separator();
                    ui.label(RichText::new("最近的提醒执行").strong());
                }
                for fire in &self.schedule_status.fires {
                    ui.push_id(&fire.identity.fire_token, |ui| {
                        ui.label(format!("{} · {:?}", fire.title, fire.phase));
                        if let Some(error) = &fire.error {
                            ui.label(RichText::new(error).small().weak());
                        }
                        ui.horizontal(|ui| {
                            if fire.phase == crate::store::FirePhase::Deferred
                                && ui.button("恢复执行").clicked()
                            {
                                resume = Some(fire.identity.clone());
                            }
                            if let Some(id) = &fire.conversation_id {
                                if ui.button("查看会话").clicked() {
                                    open_conversation = Some(id.clone());
                                }
                            }
                        });
                    });
                }
            });
        if let Some(operation) = operation {
            self.schedule_operation(operation);
        }
        if let Some(identity) = resume {
            self.dispatch(Action::ResumeSchedule(identity));
        }
        if let Some(id) = open_conversation {
            self.dispatch(Action::SelectConversation(id));
        }
    }

    fn schedule_operation(&mut self, operation: Operation) {
        let request_id = crate::controller::unique_id();
        if self.dispatch(Action::MutateSchedule {
            request_id: request_id.clone(),
            operation,
        }) {
            self.schedule_pending = Some(request_id);
        }
    }

    pub(super) fn schedule_dialog(&mut self, context: &egui::Context) {
        if self.schedule_editor.is_none() {
            return;
        }
        let busy = self.schedule_pending.is_some();
        let mut operation = None;
        let mut close = false;
        egui::Window::new("保存计划")
            .id(egui::Id::unique("schedule_editor"))
            .default_width(480.0)
            .show(context, |ui| {
                let Some(editor) = self.schedule_editor.as_mut() else {
                    return;
                };
                ui.add_enabled_ui(!busy, |ui| {
                    ui.label("名称");
                    ui.text_edit_singleline(&mut editor.title);
                    ui.label("到时要执行的任务");
                    ui.add(
                        egui::TextEdit::multiline(&mut editor.prompt)
                            .desired_width(f32::INFINITY)
                            .desired_rows(5),
                    );
                    if editor.id.is_some() {
                        ui.checkbox(&mut editor.change_timing, "修改时间安排");
                    }
                    if editor.change_timing {
                        ui.horizontal(|ui| {
                            ui.selectable_value(&mut editor.timing_kind, 0, "一次性");
                            ui.selectable_value(&mut editor.timing_kind, 1, "间隔");
                            ui.selectable_value(&mut editor.timing_kind, 2, "每日");
                        });
                        match editor.timing_kind {
                            0 => {
                                ui.add(
                                    egui::TextEdit::singleline(&mut editor.at)
                                        .hint_text("yyyy-MM-dd HH:mm[:ss]"),
                                );
                            }
                            1 => {
                                ui.horizontal(|ui| {
                                    ui.label("间隔秒数");
                                    ui.add(
                                        egui::DragValue::new(&mut editor.every_seconds)
                                            .range(60..=31_536_000),
                                    );
                                });
                            }
                            _ => {
                                ui.add(
                                    egui::TextEdit::singleline(&mut editor.daily)
                                        .hint_text("HH:mm[:ss]"),
                                );
                            }
                        }
                        ui.label(RichText::new("按设备的本地时区计算时间。").small().weak());
                    }
                    ui.horizontal(|ui| {
                        if ui
                            .add_enabled(
                                !editor.title.trim().is_empty() && !editor.prompt.trim().is_empty(),
                                egui::Button::new("保存计划"),
                            )
                            .clicked()
                        {
                            operation = Some(match &editor.id {
                                Some(id) => Operation::Update {
                                    id: id.clone(),
                                    title: Some(editor.title.clone()),
                                    prompt: Some(editor.prompt.clone()),
                                    timing: editor.change_timing.then(|| editor.timing()),
                                },
                                None => Operation::Create {
                                    id: crate::controller::unique_id(),
                                    title: editor.title.clone(),
                                    prompt: editor.prompt.clone(),
                                    timing: editor.timing(),
                                },
                            });
                        }
                        if ui.button("取消").clicked() {
                            close = true;
                        }
                    });
                });
                if busy {
                    ui.spinner();
                }
            });
        if close {
            self.schedule_editor = None;
        }
        if let Some(operation) = operation {
            self.schedule_operation(operation);
        }
    }
}
