//! The global transient banner. Its deadline is owned by the current notice, so an
//! earlier repaint cannot dismiss a replacement that happens to contain the same text.
use crate::state::ViewState;
use eframe::egui;
use std::time::Instant;

pub(super) fn show(ui: &mut egui::Ui, state: &mut ViewState) {
    if let Some(remaining) = state.notice_repaint_after(Instant::now()) {
        ui.ctx().request_repaint_after(remaining);
    }
    if state.notice.is_none() {
        return;
    }
    let mut dismiss = false;
    egui::Panel::bottom("status_notice")
        .frame(egui::Frame::NONE.inner_margin(12))
        .show(ui, |ui| {
            if let Some(notice) = &state.notice {
                ui.horizontal_wrapped(|ui| {
                    let text = if notice.error {
                        format!("操作失败：{}", notice.text)
                    } else {
                        notice.text.clone()
                    };
                    egui::ScrollArea::vertical()
                        .id_salt("status_notice_text")
                        .max_height(120.0)
                        .show(ui, |ui| ui.add(egui::Label::new(text).wrap()));
                    dismiss = ui.button("关闭").clicked();
                });
            }
        });
    if dismiss {
        state.notice = None;
    }
}
