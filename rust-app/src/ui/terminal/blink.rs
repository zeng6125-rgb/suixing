//! Cursor-only clock. Repaint the exact next phase boundary while a visible,
//! focused live terminal needs it; no worker, status event or permanent timer.
use eframe::egui;
use std::time::Duration;

#[derive(Default)]
pub(super) struct CursorBlink {
    identity: Option<(u64, u64, usize, usize, u8, bool)>,
    origin: f64,
}
impl CursorBlink {
    pub(super) fn visible(
        &mut self,
        context: &egui::Context,
        frame: &crate::vt::Frame,
        active: bool,
        input: bool,
    ) -> bool {
        if !active || !frame.modes.cursor_blink {
            self.identity = None;
            return true;
        }
        let now = context.input(|input| input.time);
        let identity = (
            frame.session_id,
            frame.revision,
            frame.cursor.row,
            frame.cursor.column,
            frame.cursor.shape,
            frame.modes.cursor_blink,
        );
        if self.identity != Some(identity) || input || now < self.origin {
            self.identity = Some(identity);
            self.origin = now;
        }
        let elapsed = (now - self.origin).max(0.0);
        let half_period = 0.5;
        let until_next = half_period - elapsed % half_period;
        context.request_repaint_after(Duration::from_secs_f64(until_next.max(0.001)));
        (elapsed / half_period).floor() as u64 % 2 == 0
    }
}
