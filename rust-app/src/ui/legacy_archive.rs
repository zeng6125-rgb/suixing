use super::*;
use crate::{
    controller::legacy_archive::{Request, Update},
    legacy_process_browser::{self as browser, Kind},
};
#[derive(Default)]
pub(super) struct ArchiveView {
    token: String,
    generation: Option<browser::Generation>,
    kind: Option<Kind>,
    pending: Option<String>,
    opened: bool,
    page: Option<browser::Page>,
    selected: Option<browser::Key>,
    detail: Option<String>,
    window: Option<browser::Window>,
    error: Option<String>,
}
impl ArchiveView {
    pub(super) fn apply(&mut self, update: &Update) {
        match update {
            Update::Opened {
                request_id,
                ui_token,
                result,
            } if self.pending.as_ref() == Some(request_id) && ui_token == &self.token => {
                self.pending = None;
                match result {
                    Ok(generation) => {
                        self.generation = Some(generation.clone());
                        self.error = None;
                    }
                    Err(error) => self.error = Some(error.clone()),
                }
            }
            Update::Read(browser::Update::List {
                request_id,
                generation,
                kind,
                result,
            }) if self.pending.as_ref() == Some(request_id)
                && self.generation.as_ref() == Some(generation)
                && self.kind == Some(*kind) =>
            {
                self.pending = None;
                match result {
                    Ok(page) => {
                        self.page = Some(page.clone());
                        self.error = None;
                    }
                    Err(error) => self.error = Some(error.clone()),
                }
            }
            Update::Read(browser::Update::Detail {
                request_id,
                key,
                result,
            }) if self.pending.as_ref() == Some(request_id)
                && self.selected.as_ref() == Some(key) =>
            {
                self.pending = None;
                match result {
                    Ok(detail) => {
                        self.detail = Some(
                            serde_json::to_string_pretty(&detail.document)
                                .unwrap_or_else(|_| "归档详情无法显示".into()),
                        );
                        self.error = None;
                    }
                    Err(error) => self.error = Some(error.clone()),
                }
            }
            Update::Read(browser::Update::Log {
                request_id,
                key,
                result,
            }) if self.pending.as_ref() == Some(request_id)
                && self.selected.as_ref() == Some(key) =>
            {
                self.pending = None;
                match result {
                    Ok(window) => {
                        self.window = Some(window.clone());
                        self.error = None;
                    }
                    Err(error) => self.error = Some(error.clone()),
                }
            }
            Update::Failed { request_id, error } if self.pending.as_ref() == Some(request_id) => {
                self.pending = None;
                self.error = Some(error.clone());
            }
            _ => {}
        }
    }
}
impl RustApplication {
    pub(super) fn legacy_archive_panel(&mut self, ui: &mut egui::Ui, kind: Kind) {
        if self.archive_view.kind != Some(kind) {
            self.archive_view = ArchiveView {
                token: format!("archive-{}", crate::controller::unique_id()),
                kind: Some(kind),
                ..Default::default()
            };
        }
        let mut request = None;
        let request_id = || crate::controller::unique_id();
        let view = &mut self.archive_view;
        egui::CollapsingHeader::new("迁移前的作业与终端记录")
            .id_salt(("legacy_archives", kind as u8))
            .show(ui, |ui| {
                ui.label("查看原状态和日志；迁移前的运行已中断，记录不恢复进程。");
                if view.pending.is_some() {
                    ui.spinner();
                }
                if let Some(error) = &view.error {
                    ui.label(RichText::new(error).color(Color32::from_rgb(190, 80, 80)));
                }
                if view.pending.is_none() && (!view.opened || ui.button("重新读取归档").clicked())
                {
                    view.opened = true;
                    view.generation = None;
                    view.page = None;
                    view.detail = None;
                    view.window = None;
                    request = Some(Request::Open {
                        request_id: request_id(),
                        ui_token: view.token.clone(),
                    });
                }
                if view.pending.is_none()
                    && request.is_none()
                    && view.page.is_none()
                    && let Some(generation) = &view.generation
                {
                    request = Some(Request::Read(browser::Request::List {
                        request_id: request_id(),
                        generation: generation.clone(),
                        kind,
                        cursor: None,
                        max_rows: 16,
                    }));
                }
                if let Some(page) = &view.page {
                    ui.label(format!("归档记录 {} 条", page.total));
                    for row in &page.rows {
                        ui.horizontal(|ui| {
                            ui.label(format!(
                                "{} · {:?} · exit {:?}",
                                row.title, row.phase, row.exit_code
                            ));
                            if ui
                                .add_enabled(view.pending.is_none(), egui::Button::new("详情"))
                                .clicked()
                            {
                                view.selected = Some(row.key.clone());
                                view.detail = None;
                                view.window = None;
                                request = Some(Request::Read(browser::Request::Detail {
                                    request_id: request_id(),
                                    key: row.key.clone(),
                                }));
                            }
                            if row.has_log
                                && ui
                                    .add_enabled(view.pending.is_none(), egui::Button::new("日志"))
                                    .clicked()
                            {
                                view.selected = Some(row.key.clone());
                                view.detail = None;
                                view.window = None;
                                request = Some(Request::Read(browser::Request::Log {
                                    request_id: request_id(),
                                    key: row.key.clone(),
                                    cursor_domain: row.cursor_domain.clone(),
                                    since: row.retained_from,
                                    max_bytes: 32768,
                                }));
                            }
                        });
                    }
                    if view.pending.is_none()
                        && let Some(next) = &page.next
                        && ui.button("下一页记录").clicked()
                    {
                        request = Some(Request::Read(browser::Request::List {
                            request_id: request_id(),
                            generation: next.generation.clone(),
                            kind,
                            cursor: Some(next.clone()),
                            max_rows: 16,
                        }));
                    }
                }
                if let Some(detail) = &view.detail {
                    egui::ScrollArea::vertical()
                        .id_salt("archive_detail")
                        .max_height(240.0)
                        .show(ui, |ui| {
                            ui.add(
                                egui::Label::new(RichText::new(detail).monospace())
                                    .selectable(true),
                            );
                        });
                }
                if let Some(window) = &view.window {
                    ui.label(format!(
                        "日志字节 {}–{} / {}",
                        window.from, window.next_offset, window.end
                    ));
                    egui::ScrollArea::vertical()
                        .id_salt("archive_log")
                        .max_height(240.0)
                        .show(ui, |ui| {
                            ui.add(
                                egui::Label::new(
                                    RichText::new(String::from_utf8_lossy(&window.bytes))
                                        .monospace(),
                                )
                                .selectable(true),
                            );
                        });
                    if view.pending.is_none()
                        && window.has_more
                        && ui.button("下一段日志").clicked()
                        && let Some(key) = &view.selected
                    {
                        request = Some(Request::Read(browser::Request::Log {
                            request_id: request_id(),
                            key: key.clone(),
                            cursor_domain: window.cursor_domain.clone(),
                            since: window.next_offset,
                            max_bytes: 32768,
                        }));
                    }
                }
            });
        if let Some(request) = request {
            let id = request.id().to_owned();
            if self.dispatch(Action::LegacyArchive(request)) {
                self.archive_view.pending = Some(id);
            }
        }
    }
}
