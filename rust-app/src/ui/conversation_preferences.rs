use super::*;
use crate::conversation_preferences::{Request, Stamp};
use std::time::Instant;
#[derive(Default)]
pub(super) struct Viewing {
    last: Option<(
        u64,
        String,
        Option<String>,
        Option<crate::display::SourceStamp>,
    )>,
    pub(super) rendered: Option<(u64, String, crate::display::SourceStamp)>,
    sequence: u64,
    sent: Option<Instant>,
}
#[derive(Clone)]
pub(super) struct Drag {
    pub source: String,
    pub pinned: bool,
    pub expected: Stamp,
}
impl RustApplication {
    pub(super) fn move_conversation_order(
        &mut self,
        source: String,
        target: String,
        after: bool,
        expected: Stamp,
    ) {
        if self.conversation_order_pending.is_some() {
            return;
        }
        let request_id = crate::controller::unique_id();
        if self.dispatch(Action::ConversationPreference(Request::Move {
            request_id: request_id.clone(),
            expected,
            source,
            target,
            after,
        })) {
            self.conversation_order_pending = Some(request_id);
        }
    }
    pub(super) fn sync_conversation_viewing(&mut self, ctx: &egui::Context) {
        let Some(observer) = self.runtime.as_ref().map(|runtime| runtime.observer.id()) else {
            return;
        };
        #[cfg(target_os = "android")]
        let foreground = crate::platform::android::activity_visible()
            && ctx.input(|input| input.viewport().focused.unwrap_or(true));
        #[cfg(not(target_os = "android"))]
        let foreground = ctx.input(|input| input.viewport().focused.unwrap_or(false));
        let conversation = if foreground
            && self.state.loaded
            && self.state.page == Page::Chat
            && !self.drawer_open
            && self.child_viewer.is_none()
            && !self.state.conversation_pending
            && !self.avatar_view.modal_open()
            && !self.history_renderer.covering_dialog()
            && !self.todos_open
            && self.state.child_launch_editor.is_none()
        {
            self.state
                .selected_conversation
                .as_ref()
                .filter(|id| {
                    let source = crate::display::ViewSource::Main {
                        conversation_id: (*id).clone(),
                    };
                    self.history_views.view(&source).is_some_and(|view| {
                        view.stamp.is_some()
                            && !view.needs_window
                            && !view.rows.is_empty()
                            && self.conversation_view_lease.rendered.as_ref().is_some_and(
                                |(owner, chat, stamp)| {
                                    *owner == observer
                                        && chat == *id
                                        && view.stamp.as_ref() == Some(stamp)
                                },
                            )
                    })
                })
                .cloned()
        } else {
            None
        };
        let generation = self.state.conversation_preferences.stamp.generation.clone();
        let history_stamp = conversation.as_ref().and_then(|_| {
            self.conversation_view_lease
                .rendered
                .as_ref()
                .map(|(_, _, stamp)| stamp.clone())
        });
        let identity = (
            observer,
            generation.clone(),
            conversation.clone(),
            history_stamp.clone(),
        );
        let heartbeat = conversation.is_some()
            && self
                .conversation_view_lease
                .sent
                .is_none_or(|last| last.elapsed() >= Duration::from_millis(500));
        if self.conversation_view_lease.last.as_ref() != Some(&identity) || heartbeat {
            let Some(sequence) = self.conversation_view_lease.sequence.checked_add(1) else {
                return;
            };
            if self.dispatch(Action::ConversationPreference(Request::Viewing {
                observer_id: observer,
                sequence,
                generation,
                conversation_id: conversation.clone(),
                history_stamp,
            })) {
                self.conversation_view_lease.sequence = sequence;
                self.conversation_view_lease.last = Some(identity);
                self.conversation_view_lease.sent = Some(Instant::now());
            }
        }
        if conversation.is_some() {
            ctx.request_repaint_after(Duration::from_millis(500));
        }
    }
    pub(super) fn drawer_reorder_handle(
        &mut self,
        ui: &mut egui::Ui,
        conversation: &crate::display::ConversationSummary,
    ) {
        let ready = self.state.loaded
            && self.conversation_order_pending.is_none()
            && self.state.conversation_delete_pending.as_ref() != Some(&conversation.id);
        let response = ui
            .add_enabled(ready, egui::Button::new("↕").sense(egui::Sense::drag()))
            .on_hover_text("拖动调整同组内的会话顺序");
        response.dnd_set_drag_payload(Drag {
            source: conversation.id.clone(),
            pinned: conversation.pinned,
            expected: self.state.conversation_preferences.stamp.clone(),
        });
    }
    pub(super) fn conversation_drop_target(
        &mut self,
        response: &egui::Response,
        conversation: &crate::display::ConversationSummary,
    ) {
        if self.conversation_order_pending.is_some() {
            return;
        }
        if let Some(payload) = response.dnd_hover_payload::<Drag>() {
            if payload.source != conversation.id && payload.pinned == conversation.pinned {
                response.ctx.layer_painter(response.layer_id).rect_stroke(
                    response.rect,
                    0,
                    Stroke::new(
                        1.0,
                        response.ctx.global_style().visuals.selection.stroke.color,
                    ),
                    egui::StrokeKind::Inside,
                );
            }
        }
        if let Some(payload) = response.dnd_release_payload::<Drag>() {
            if payload.source != conversation.id && payload.pinned == conversation.pinned {
                let after = response.ctx.input(|input| {
                    input
                        .pointer
                        .hover_pos()
                        .is_some_and(|position| position.y > response.rect.center().y)
                });
                self.move_conversation_order(
                    payload.source.clone(),
                    conversation.id.clone(),
                    after,
                    payload.expected.clone(),
                );
            }
        }
    }
}
