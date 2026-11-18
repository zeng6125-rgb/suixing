use super::*;

enum Target {
    Navigate(Navigation, bool),
    SettingsRoot,
}

#[derive(Default)]
pub(super) struct LeaveState {
    target: Option<Target>,
    saving: Option<String>,
    ready: bool,
}

impl RustApplication {
    pub(super) fn settings_navigation(
        &mut self,
        navigation: Navigation,
        narrow: bool,
    ) -> Option<Navigation> {
        let leaves = !matches!(
            &navigation,
            Navigation::Page(Page::Settings) | Navigation::DeleteConversation(_)
        );
        if leaves && self.state.page == Page::Settings && self.settings_draft != self.state.settings
        {
            if self.settings_leave.target.is_none() {
                self.settings_leave.target = Some(Target::Navigate(navigation, narrow));
                self.settings_leave.saving = self
                    .settings_view
                    .pending
                    .as_ref()
                    .map(|(id, _, _)| id.clone());
            }
            None
        } else {
            Some(navigation)
        }
    }

    pub(super) fn settings_request_root(&mut self) {
        if self.settings_draft != self.state.settings {
            self.settings_leave.target = Some(Target::SettingsRoot);
            self.settings_leave.saving = self
                .settings_view
                .pending
                .as_ref()
                .map(|(id, _, _)| id.clone());
        } else {
            self.settings_show_root();
        }
    }

    pub(super) fn settings_leave_result(&mut self, request_id: &str, success: bool) {
        if self.settings_leave.saving.as_deref() == Some(request_id) {
            self.settings_leave.saving = None;
            self.settings_leave.ready = success;
        }
    }

    pub(super) fn settings_leave_disconnected(&mut self) {
        self.settings_leave.saving = None;
        self.settings_leave.ready = false;
    }

    fn finish_settings_leave(&mut self) {
        if let Some(target) = self.settings_leave.target.take() {
            match target {
                Target::Navigate(navigation, narrow) => self.navigate(navigation, narrow),
                Target::SettingsRoot => self.settings_show_root(),
            }
        }
    }

    pub(super) fn settings_leave_dialog(&mut self, ctx: &egui::Context) {
        if self.settings_leave.target.is_none() {
            return;
        }
        let waiting = self.settings_leave.saving.is_some();
        let mut save = false;
        let mut discard = false;
        let mut edit = false;
        let response = egui::Modal::new(egui::Id::new("settings-leave")).show(ctx, |ui| {
            ui.heading("保存修改？");
            ui.label("配置还有未保存的改动。");
            if let Some(error) = self.settings_save_error() {
                ui.colored_label(ui.visuals().error_fg_color, error);
            }
            if waiting {
                ui.spinner();
                ui.label("等待保存确认");
            }
            ui.horizontal(|ui| {
                save = ui
                    .add_enabled(
                        !waiting && self.runtime.is_some() && !self.state.settings_pending,
                        egui::Button::new("保存并离开"),
                    )
                    .clicked();
                discard = ui
                    .add_enabled(!waiting, egui::Button::new("放弃"))
                    .clicked();
                edit = ui
                    .add_enabled(!waiting, egui::Button::new("继续编辑"))
                    .clicked();
            });
        });
        if save && self.settings_save_value(self.settings_draft.clone()) {
            self.settings_leave.saving = self
                .settings_view
                .pending
                .as_ref()
                .map(|(id, _, _)| id.clone());
        } else if discard {
            self.settings_discard_draft();
            self.finish_settings_leave();
        } else if !waiting && (edit || response.should_close()) {
            self.settings_leave.target = None;
        }
    }

    pub(super) fn navigation_tick(&mut self, ctx: &egui::Context) {
        if std::mem::take(&mut self.settings_leave.ready) {
            // poll_events has applied the successful Store snapshot before this tick.
            self.finish_settings_leave();
        }
        let native = crate::platform::navigation::take_back();
        let escape = self.state.page != Page::Terminal
            && ctx.input_mut(|input| input.consume_key(egui::Modifiers::NONE, egui::Key::Escape));
        if native.is_none() && !escape {
            return;
        }
        if egui::Popup::is_any_open(ctx) {
            egui::Popup::close_all(ctx);
            return;
        }
        if self.settings_leave.target.is_some() {
            if self.settings_leave.saving.is_none() {
                self.settings_leave.target = None;
            }
            return;
        }
        if self.avatar_back() || self.close_navigation_editor() {
            return;
        }
        if let Some(request) = native.as_ref().filter(|request| request.keyboard_visible) {
            if let Err(error) = request.hide_keyboard() {
                self.state.error(error);
            }
            return;
        }
        if self.child_viewer.take().is_some() {
            self.last_opened_child = None;
            return;
        }
        if self.drawer_open || self.drawer_motion.active() {
            self.drawer_open = false;
            self.drawer_motion.cancel();
            return;
        }
        if self.settings_back() {
            return;
        }
        if self.state.page == Page::Files {
            let parent = self
                .state
                .directory
                .parent()
                .map(PathBuf::from)
                .filter(|path| path.starts_with(&self.state.workspace));
            if let Some(parent) = parent {
                if !self.state.files_pending && self.dispatch(Action::ListFiles(parent)) {
                    self.state.files_pending = true;
                }
                return;
            }
        }
        if self.state.page != Page::Chat {
            self.navigate(Navigation::Page(Page::Chat), false);
        } else if native.is_some() {
            if let Err(error) = crate::platform::navigation::background() {
                self.state.error(error);
            }
        }
    }

    fn close_navigation_editor(&mut self) -> bool {
        if self.queue_editor.take().is_some()
            || self.memory_editor.take().is_some()
            || self.skill_editor.take().is_some()
            || self.plugin_import.take().is_some()
            || self.mcp_editor.take().is_some()
            || self.plugin_environment_editor.take().is_some()
        {
            return true;
        }
        if self.skill_import_open {
            self.skill_import_open = false;
            return true;
        }
        if self.todos_open {
            self.todos_open = false;
            return true;
        }
        false
    }
}
