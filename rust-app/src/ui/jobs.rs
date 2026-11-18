//! Job logs are read in pages; requesting a stop never fabricates a terminal state.
use super::*;
#[derive(Default)]
pub(super) struct JobView {
    pub records: Vec<crate::jobs::Status>,
    pub selected: Option<crate::jobs::JobKey>,
    pub output: Option<crate::jobs::Output>,
    pub pending: Option<String>,
    pub error: Option<String>,
}
impl RustApplication {
    pub(super) fn jobs_page(&mut self, ui: &mut egui::Ui) {
        let mut read = None;
        let mut stop = None;
        self.legacy_archive_panel(ui, crate::legacy_process_browser::Kind::Job);
        let mut refresh = false;
        ui.horizontal(|ui| {
            ui.heading("后台作业");
            if ui.button("刷新").clicked() {
                refresh = true;
            }
            if self.job_view.pending.is_some() {
                ui.spinner();
            }
        });
        if let Some(error) = &self.job_view.error {
            ui.colored_label(Color32::from_rgb(190, 80, 80), error);
        }
        egui::ScrollArea::vertical()
            .id_salt("jobs_list")
            .max_height(280.0)
            .show(ui, |ui| {
                for record in &self.job_view.records {
                    ui.push_id(&record.key.id, |ui| {
                        egui::Frame::group(ui.style()).show(ui, |ui| {
                            let label = if record.info.label.is_empty() {
                                &record.info.id
                            } else {
                                &record.info.label
                            };
                            ui.label(RichText::new(label).strong());
                            ui.label(format!(
                                "{:?} · {:.1}s · exit {:?}",
                                record.status,
                                record.elapsed_ms as f64 / 1000.0,
                                record.exit_code
                            ));
                            ui.add(
                                egui::Label::new(RichText::new(&record.last_line).monospace())
                                    .wrap()
                                    .selectable(true),
                            );
                            if let Some(error) = &record.error {
                                ui.label(RichText::new(error).small().weak());
                            }
                            ui.horizontal(|ui| {
                                if ui
                                    .add_enabled(
                                        self.job_view.pending.is_none(),
                                        egui::Button::new("查看输出"),
                                    )
                                    .clicked()
                                {
                                    read = Some((record.key.clone(), None));
                                }
                                if record.status.active() && ui.button("停止作业").clicked() {
                                    stop = Some(record.key.clone());
                                }
                            });
                        });
                    });
                }
                if self.job_view.records.is_empty() {
                    ui.label(
                        "还没有后台作业。模型可通过 job_start 创建作业，或在会话中要求启动命令。",
                    );
                }
            });
        if let Some(output) = &self.job_view.output {
            ui.separator();
            ui.horizontal(|ui| {
                ui.label(format!(
                    "字节 {}–{} / {}",
                    output.from, output.next_offset, output.log_length
                ));
                if ui.button("复制此页").clicked() {
                    ui.ctx().copy_text(output.text.clone());
                }
                if output.has_more
                    && self.job_view.pending.is_none()
                    && ui.button("下一页").clicked()
                {
                    if let Some(key) = &self.job_view.selected {
                        read = Some((key.clone(), Some(output.next_offset)));
                    }
                }
                if output.window_truncated || output.invalid_utf8 {
                    ui.label("此页按日志边界读取；无效UTF-8以替代字符显示");
                }
            });
            egui::ScrollArea::vertical()
                .id_salt("job_output")
                .show(ui, |ui| {
                    ui.add(
                        egui::Label::new(RichText::new(&output.text).monospace()).selectable(true),
                    );
                });
        }
        if refresh {
            self.dispatch(Action::LoadJobs);
        }
        if let Some(key) = stop {
            self.dispatch(Action::StopJob(key));
        }
        if let Some((key, since)) = read {
            let request_id = crate::controller::unique_id();
            if self.dispatch(Action::ReadJobOutput {
                request_id: request_id.clone(),
                key: key.clone(),
                since,
            }) {
                self.job_view.selected = Some(key);
                self.job_view.pending = Some(request_id);
                self.job_view.error = None;
            }
        }
    }
}
