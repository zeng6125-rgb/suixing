use super::*;
use crate::display::{DisplayError, SourceStamp, ViewSource};
impl RustApplication {
    pub(super) fn apply_turn_page(
        &mut self,
        page: crate::display::turn_navigation::TurnPage,
        context: &egui::Context,
    ) {
        let stamp = self
            .history_views
            .view(&page.source)
            .and_then(|view| view.stamp.as_ref());
        self.turn_navigation.apply(page, stamp);
        context.request_repaint();
    }
    pub(super) fn turn_navigation_intents(&mut self, intents: Vec<super::turn_navigation::Intent>) {
        for intent in intents {
            match intent {
                super::turn_navigation::Intent::Read(request) => {
                    let source = request.source.clone();
                    let id = request.request_id.clone();
                    if !self.dispatch(Action::HistoryTurns(request)) {
                        self.turn_navigation.failed(&source, &id);
                    }
                }
                super::turn_navigation::Intent::Jump(target) => {
                    let source = target.source.clone();
                    match self
                        .history_renderer
                        .navigate_turn(&mut self.history_views, target)
                    {
                        Ok(intents) => self.history_intents(intents),
                        Err(DisplayError::Stale) => self.turn_navigation.turn_changed(&source),
                        Err(error) => self.state.error(error.to_string()),
                    }
                }
            }
        }
    }
    pub(super) fn refresh_child_history(&mut self, source: &ViewSource) {
        if self.last_opened_child.as_ref() == Some(source) {
            return;
        }
        if !self.dispatch(Action::HistoryOpen(source.clone())) {
            return;
        }
        self.last_opened_child = Some(source.clone());
        match self.history_views.reload_at_position(source) {
            Ok(request) => {
                let id = request.request_id.clone();
                if !self.dispatch(Action::HistoryWindow(request)) {
                    self.history_renderer.request_failed(
                        &mut self.history_views,
                        source,
                        &id,
                        "运行时连接已断开",
                    );
                }
            }
            Err(error) => self.state.error(&error.to_string()),
        }
    }
    pub(super) fn history_read_failed(
        &mut self,
        source: &ViewSource,
        request_id: &str,
        error: &DisplayError,
        current_stamp: Option<SourceStamp>,
    ) {
        if matches!(error, DisplayError::Stale | DisplayError::Missing)
            && self
                .history_renderer
                .navigation_request_pending(source, request_id)
        {
            self.turn_navigation.turn_changed(source);
        }
        if self.turn_navigation.failed(source, request_id) {
            if *error == DisplayError::Stale {
                match self.history_views.reload_stale(source, current_stamp) {
                    Ok(request) => self
                        .history_intents(vec![super::history_view::HistoryIntent::Window(request)]),
                    Err(error) => self.state.error(error.to_string()),
                }
            } else {
                self.state.error(error.to_string());
            }
            return;
        }
        match self.history_renderer.read_failed(
            &mut self.history_views,
            source,
            request_id,
            error,
            current_stamp,
        ) {
            Ok(intents) => self.history_intents(intents),
            Err(DisplayError::Stale) => {}
            Err(error) => self.state.error(&error.to_string()),
        }
    }
    pub(super) fn apply_history_window(
        &mut self,
        page: crate::display::DisplayWindow,
        context: &egui::Context,
    ) {
        let source = page.source.clone();
        let request_id = page.request_id.clone();
        match self.history_views.apply_window(page) {
            Ok(applied) => self.history_renderer.on_window(&source, applied),
            Err(DisplayError::Stale) => self.history_views.request_failed(&source, &request_id),
            Err(error) => self.history_read_failed(&source, &request_id, &error, None),
        }
        context.request_repaint();
    }
}
