//! Template editing and per-launch choices. Resolved previews come from the active run.
use super::*;
use crate::{
    controller::unique_id,
    subagent_configuration::{
        self, ApprovalMode, ChildLaunchDraft, SubagentModelSelection, SubagentProfile,
        SubagentProfileEditor,
    },
};
use suixing_search::rust_protocol::ToolDefinition;

enum TemplateIntent {
    Edit(SubagentProfile),
    Default(String),
    Delete(String),
}

impl RustApplication {
    pub(super) fn subagent_settings(&mut self, ui: &mut egui::Ui) {
        let editable = self.state.loaded && !self.state.settings_pending;
        use super::settings::controls;
        controls::first_section(ui, "任务委派");
        controls::row(
            ui,
            &mut self.settings_draft.subagents_enabled,
            "允许委派子任务",
            "助手可将复杂任务拆开，子任务继承父任务的工具权限，可能增加模型调用。",
        );
        if self.settings_draft.subagents_enabled {
            let title = format!("子任务并行数 {}", self.settings_draft.subagent_parallelism);
            controls::slider(
                ui,
                &mut self.settings_draft.subagent_parallelism,
                1..=3,
                &title,
                "每轮最多创建 6 个子任务。父任务正常结束前会等待子任务；停止父任务时一同取消。",
            );
        }
        controls::section(ui, "子任务模型");
        let mut choosing = self.settings_view.subagent_choosing_model
            || self.settings_draft.subagent_model.is_some();
        controls::choice_row(
            ui,
            "模型来源",
            "默认跟随当前会话的模型。选好供应商和模型后，从下一次运行起仅对子任务生效。",
            &mut choosing,
            &[(false, "跟随当前会话"), (true, "指定模型")],
        );
        self.settings_view.subagent_choosing_model = choosing;
        if choosing {
            model_selection(
                ui,
                "default_subagent_model",
                &mut self.settings_draft.subagent_model,
                &self.state.settings,
            );
        } else {
            self.settings_draft.subagent_model = None;
        }
        controls::section(ui, "子任务模板");
        let can_edit = editable && self.settings_draft == self.state.settings;
        let mut intent = None;
        controls::card(ui, 0).show(ui, |ui| {
            ui.set_width(ui.available_width());
            egui::Frame::NONE.inner_margin(16).show(ui, |ui| {
                controls::text(ui, "默认模板", 17.0, 24.0, ui.visuals().text_color());
                egui::ComboBox::from_id_salt("default_subagent_profile")
                    .width(ui.available_width())
                    .selected_text(
                        self.settings_draft
                            .subagent_profiles
                            .iter()
                            .find(|profile| {
                                profile.id == self.settings_draft.default_subagent_profile_id
                            })
                            .map(|profile| profile.name.as_str())
                            .unwrap_or("跟随父任务与子任务模型设置"),
                    )
                    .show_ui(ui, |ui| {
                        ui.selectable_value(
                            &mut self.settings_draft.default_subagent_profile_id,
                            String::new(),
                            "跟随父任务",
                        );
                        for profile in &self.settings_draft.subagent_profiles {
                            ui.selectable_value(
                                &mut self.settings_draft.default_subagent_profile_id,
                                profile.id.clone(),
                                &profile.name,
                            );
                        }
                    });
            });
            for profile in &self.state.settings.subagent_profiles {
                ui.push_id(&profile.id, |ui| {
                    egui::Frame::NONE.inner_margin(16).show(ui, |ui| {
                        controls::text(
                            ui,
                            &format!(
                                "{}{}",
                                profile.name,
                                if profile.id == self.state.settings.default_subagent_profile_id {
                                    " · 默认"
                                } else {
                                    ""
                                }
                            ),
                            17.0,
                            24.0,
                            ui.visuals().text_color(),
                        );
                        controls::description(
                            ui,
                            &format!(
                                "{} · {} · {}",
                                profile
                                    .model
                                    .as_ref()
                                    .map(|model| model.model.as_str())
                                    .unwrap_or("继承模型"),
                                profile
                                    .approval_mode
                                    .map(ApprovalMode::label)
                                    .unwrap_or("继承审批"),
                                profile
                                    .allowed_tools
                                    .as_ref()
                                    .map(|tools| if tools.is_empty() {
                                        "仅文字".into()
                                    } else {
                                        format!("{} 个工具", tools.len())
                                    })
                                    .unwrap_or_else(|| "继承父工具".into())
                            ),
                        );
                        ui.add_enabled_ui(can_edit, |ui| {
                            ui.menu_button("操作", |ui| {
                                if ui.button("编辑").clicked() {
                                    intent = Some(TemplateIntent::Edit(profile.clone()));
                                    ui.close();
                                }
                                if ui.button("复制").clicked() {
                                    let mut copy = profile.clone();
                                    copy.id = unique_id();
                                    copy.name = format!("{}副本", copy.name);
                                    intent = Some(TemplateIntent::Edit(copy));
                                    ui.close();
                                }
                                if ui.button("设为默认").clicked() {
                                    intent = Some(TemplateIntent::Default(profile.id.clone()));
                                    ui.close();
                                }
                                if ui.button("删除").clicked() {
                                    intent = Some(TemplateIntent::Delete(profile.id.clone()));
                                    ui.close();
                                }
                            });
                        });
                    });
                });
            }
            egui::Frame::NONE.inner_margin(16).show(ui, |ui| {
                if self.state.settings.subagent_profiles.is_empty() {
                    controls::description(ui, "还没有模板，可从新建模板开始。");
                }
                if ui
                    .add_enabled(can_edit, egui::Button::new("新建模板").frame(false))
                    .clicked()
                {
                    intent = Some(TemplateIntent::Edit(SubagentProfile {
                        id: unique_id(),
                        name: "新模板".into(),
                        ..Default::default()
                    }));
                }
            });
        });
        ui.add_space(24.0);
        if let Some(intent) = intent {
            match intent {
                TemplateIntent::Edit(draft) => {
                    self.state.subagent_profile_editor = Some(SubagentProfileEditor {
                        draft,
                        saving: false,
                        error: String::new(),
                    })
                }
                TemplateIntent::Delete(id) => self.state.subagent_profile_delete = Some(id),
                TemplateIntent::Default(id) => {
                    let mut next = self.state.settings.clone();
                    next.default_subagent_profile_id = id;
                    if self.dispatch(Action::SaveSettings(next)) {
                        self.state.settings_pending = true;
                    }
                }
            }
        }
    }

    pub(super) fn child_launch_controls(&mut self, ui: &mut egui::Ui) {
        if !self.state.running
            || self.state.running_conversation != self.state.selected_conversation
        {
            return;
        }
        let can_create = !self.state.cancelling
            && self.state.settings.subagents_enabled
            && self.state.active_run_id.is_some();
        if ui
            .add_enabled(can_create, egui::Button::new("创建子智能体"))
            .clicked()
        {
            let run_id = self
                .state
                .active_run_id
                .clone()
                .expect("confirmed active run");
            if let Some(editor) = &mut self.state.child_launch_editor {
                editor.run_id = run_id;
                editor.pending = false;
                editor.preview = None;
                editor.error.clear();
            } else {
                let profile_id = self.state.settings.default_subagent_profile_id.clone();
                let draft = self
                    .state
                    .settings
                    .subagent_profiles
                    .iter()
                    .find(|profile| profile.id == profile_id)
                    .cloned()
                    .unwrap_or_else(|| launch_default_profile(&self.state.settings));
                self.state.child_launch_editor = Some(ChildLaunchDraft {
                    run_id,
                    request_id: String::new(),
                    task: String::new(),
                    context: String::new(),
                    name: String::new(),
                    profile_id,
                    draft,
                    pending: false,
                    preview: None,
                    error: String::new(),
                });
            }
        }
    }

    pub(super) fn subagent_dialogs(&mut self, context: &egui::Context) {
        self.subagent_profile_dialog(context);
        self.subagent_delete_dialog(context);
        self.child_launch_dialog(context);
    }

    fn subagent_profile_dialog(&mut self, context: &egui::Context) {
        let Some(mut editor) = self.state.subagent_profile_editor.take() else {
            return;
        };
        if editor.saving && !self.state.settings_pending {
            if self
                .state
                .settings
                .subagent_profiles
                .iter()
                .any(|profile| profile == &editor.draft)
            {
                return;
            }
            editor.saving = false;
            editor.error = "模板未保存，请查看错误后重试".into();
        }
        let mut open = true;
        let mut save = false;
        egui::Window::new("子任务模板")
            .id(egui::Id::unique((
                "subagent_profile_editor",
                &editor.draft.id,
            )))
            .open(&mut open)
            .default_width(460.0)
            .show(context, |ui| {
                egui::ScrollArea::vertical()
                    .max_height(540.0)
                    .show(ui, |ui| {
                        ui.add_enabled_ui(!editor.saving, |ui| {
                            text_field(ui, "模板名称", &mut editor.draft.name, "模板名称", false);
                            profile_fields(
                                ui,
                                &mut editor.draft,
                                &self.state.settings,
                                &self.state.tool_catalog,
                            );
                        });
                    });
                if !editor.error.is_empty() {
                    ui.label(RichText::new(&editor.error).color(ui.visuals().error_fg_color));
                }
                if ui
                    .add_enabled(
                        !editor.saving && !self.state.settings_pending,
                        egui::Button::new("保存模板"),
                    )
                    .clicked()
                {
                    save = true;
                }
                if editor.saving {
                    ui.spinner();
                }
            });
        if save {
            editor.draft.name = editor.draft.name.trim().into();
            let mut next = self.state.settings.clone();
            if let Some(previous) = next
                .subagent_profiles
                .iter_mut()
                .find(|profile| profile.id == editor.draft.id)
            {
                *previous = editor.draft.clone();
            } else {
                next.subagent_profiles.push(editor.draft.clone());
            }
            match subagent_configuration::validate_profile(&editor.draft, &next)
                .and_then(|()| crate::configuration::validate(&next))
            {
                Ok(()) => {
                    if self.dispatch(Action::SaveSettings(next)) {
                        self.state.settings_pending = true;
                        editor.saving = true;
                        editor.error.clear();
                    }
                }
                Err(error) => editor.error = error,
            }
        }
        if open || editor.saving {
            self.state.subagent_profile_editor = Some(editor);
        }
    }

    fn subagent_delete_dialog(&mut self, context: &egui::Context) {
        let Some(id) = self.state.subagent_profile_delete.clone() else {
            return;
        };
        let Some(profile) = self
            .state
            .settings
            .subagent_profiles
            .iter()
            .find(|profile| profile.id == id)
        else {
            self.state.subagent_profile_delete = None;
            return;
        };
        let mut open = true;
        let mut remove = false;
        egui::Window::new("删除模板")
            .id(egui::Id::unique(("delete_subagent_profile", &id)))
            .open(&mut open)
            .show(context, |ui| {
                ui.label(format!(
                    "删除“{}”不会改变已经创建的子任务及其配置记录。",
                    profile.name
                ));
                remove = ui
                    .add_enabled(!self.state.settings_pending, egui::Button::new("删除模板"))
                    .clicked();
            });
        if remove {
            let mut next = self.state.settings.clone();
            next.subagent_profiles.retain(|profile| profile.id != id);
            if next.default_subagent_profile_id == id {
                next.default_subagent_profile_id.clear();
            }
            if self.dispatch(Action::SaveSettings(next)) {
                self.state.settings_pending = true;
                self.state.subagent_profile_delete = None;
            }
        }
        if !open {
            self.state.subagent_profile_delete = None;
        }
    }

    fn child_launch_dialog(&mut self, context: &egui::Context) {
        let Some(mut editor) = self.state.child_launch_editor.take() else {
            return;
        };
        let active = self.state.active_run_id.as_deref() == Some(editor.run_id.as_str())
            && !self.state.cancelling;
        let mut open = true;
        let mut preview = false;
        let mut launch = false;
        let previous = editor.draft.clone();
        let old_profile = editor.profile_id.clone();
        egui::Window::new("创建独立子智能体")
            .id(egui::Id::unique(("child_launch", &editor.run_id)))
            .open(&mut open)
            .default_width(480.0)
            .show(context, |ui| {
                egui::ScrollArea::vertical()
                    .max_height(540.0)
                    .show(ui, |ui| {
                        ui.add_enabled_ui(active && !editor.pending, |ui| {
                            text_field(ui, "名称（可选）", &mut editor.name, "子任务名称", false);
                            ui.label("任务");
                            ui.add(
                                TextEdit::multiline(&mut editor.task)
                                    .desired_rows(3)
                                    .desired_width(f32::INFINITY)
                                    .char_limit(8000),
                            );
                            ui.label("明确交给子智能体的上下文");
                            ui.add(
                                TextEdit::multiline(&mut editor.context)
                                    .desired_rows(3)
                                    .desired_width(f32::INFINITY)
                                    .char_limit(64000),
                            );
                            egui::ComboBox::from_id_salt("child_launch_profile")
                                .selected_text(
                                    self.state
                                        .settings
                                        .subagent_profiles
                                        .iter()
                                        .find(|profile| profile.id == editor.profile_id)
                                        .map(|profile| profile.name.as_str())
                                        .unwrap_or("本次配置 / 跟随父任务"),
                                )
                                .show_ui(ui, |ui| {
                                    ui.selectable_value(
                                        &mut editor.profile_id,
                                        String::new(),
                                        "本次配置 / 跟随父任务",
                                    );
                                    for profile in &self.state.settings.subagent_profiles {
                                        ui.selectable_value(
                                            &mut editor.profile_id,
                                            profile.id.clone(),
                                            &profile.name,
                                        );
                                    }
                                });
                            if editor.profile_id != old_profile {
                                editor.draft = self
                                    .state
                                    .settings
                                    .subagent_profiles
                                    .iter()
                                    .find(|profile| profile.id == editor.profile_id)
                                    .cloned()
                                    .unwrap_or_else(|| {
                                        launch_default_profile(&self.state.settings)
                                    });
                            }
                            profile_fields(
                                ui,
                                &mut editor.draft,
                                &self.state.settings,
                                &self.state.tool_catalog,
                            );
                        });
                        ui.label(
                            RichText::new(
                                "子任务只接收指定上下文。预览按主任务启动时的配置和权限快照计算。",
                            )
                            .small()
                            .weak(),
                        );
                        if let Some(config) = &editor.preview {
                            ui.label(RichText::new("本次实际配置").strong());
                            ui.label(format!(
                                "{} · {} · {}",
                                config.assistant_name,
                                config.model,
                                config.approval_mode.label()
                            ));
                            ui.label(format!(
                                "{} 个工具 · 最长 {} 秒 · 工具轮数 {}",
                                config.allowed_tools.len(),
                                config.timeout_seconds,
                                if config.max_tool_rounds == 0 {
                                    "不主动限制".into()
                                } else {
                                    config.max_tool_rounds.to_string()
                                }
                            ));
                            ui.label(config.allowed_tools.join("、"));
                        }
                    });
                if !editor.error.is_empty() {
                    ui.label(RichText::new(&editor.error).color(ui.visuals().error_fg_color));
                }
                ui.horizontal(|ui| {
                    preview = ui
                        .add_enabled(active && !editor.pending, egui::Button::new("预览实际配置"))
                        .clicked();
                    launch = ui
                        .add_enabled(
                            active
                                && !editor.pending
                                && !editor.task.trim().is_empty()
                                && editor.preview.is_some()
                                && editor.draft == previous,
                            egui::Button::new("创建子智能体"),
                        )
                        .clicked();
                    if editor.pending {
                        ui.spinner();
                    }
                });
                if !active {
                    ui.label("主任务已结束，草稿保留。启动主任务后可重新打开创建。");
                }
            });
        if editor.draft != previous {
            editor.preview = None;
            editor.error.clear();
        }
        let action = if preview {
            editor.request_id = unique_id();
            Some(Action::PreviewChildConfig {
                run_id: editor.run_id.clone(),
                request_id: editor.request_id.clone(),
                options: editor.options(),
            })
        } else if launch {
            editor.request_id = unique_id();
            Some(Action::SpawnChild {
                run_id: editor.run_id.clone(),
                request_id: editor.request_id.clone(),
                task: editor.task.clone(),
                context: editor.context.clone(),
                name: editor.name.clone(),
                options: editor.options(),
            })
        } else {
            None
        };
        if let Some(action) = action {
            if self.dispatch(action) {
                editor.pending = true;
                editor.error.clear();
            }
        }
        if open || editor.pending {
            self.state.child_launch_editor = Some(editor);
        }
    }
}

fn model_selection(
    ui: &mut egui::Ui,
    id: &str,
    selection: &mut Option<SubagentModelSelection>,
    settings: &Settings,
) {
    ui.push_id(id, |ui| {
        let mut explicit = selection.is_some();
        if ui
            .checkbox(&mut explicit, "指定子模型（关闭时继承）")
            .changed()
        {
            *selection = explicit.then(|| SubagentModelSelection {
                provider_id: String::new(),
                model: String::new(),
            });
        }
        if let Some(selected) = selection {
            let before = selected.provider_id.clone();
            egui::ComboBox::from_id_salt("provider")
                .selected_text(
                    settings
                        .providers
                        .iter()
                        .find(|provider| provider.id == selected.provider_id)
                        .map(|provider| provider.name.as_str())
                        .unwrap_or("选择供应商"),
                )
                .show_ui(ui, |ui| {
                    for provider in settings.providers.iter().filter(|provider| {
                        !provider.endpoint.trim().is_empty()
                            && !provider.enabled_models().is_empty()
                    }) {
                        ui.selectable_value(
                            &mut selected.provider_id,
                            provider.id.clone(),
                            &provider.name,
                        );
                    }
                });
            if before != selected.provider_id {
                selected.model.clear();
            }
            if let Some(provider) = settings
                .providers
                .iter()
                .find(|provider| provider.id == selected.provider_id)
            {
                egui::ComboBox::from_id_salt("model")
                    .selected_text(if selected.model.is_empty() {
                        "选择已启用模型"
                    } else {
                        provider.display_model(&selected.model)
                    })
                    .show_ui(ui, |ui| {
                        for model in provider.enabled_models() {
                            ui.selectable_value(
                                &mut selected.model,
                                model.clone(),
                                provider.display_model(model),
                            );
                        }
                    });
            }
        }
    });
}

fn launch_default_profile(settings: &Settings) -> SubagentProfile {
    let model = settings
        .subagent_model
        .as_ref()
        .filter(|selection| {
            settings.providers.iter().any(|provider| {
                provider.id == selection.provider_id && provider.model_is_enabled(&selection.model)
            })
        })
        .cloned();
    SubagentProfile {
        id: unique_id(),
        name: "本次配置".into(),
        model,
        ..Default::default()
    }
}

fn profile_fields(
    ui: &mut egui::Ui,
    profile: &mut SubagentProfile,
    settings: &Settings,
    tools: &[ToolDefinition],
) {
    egui::ComboBox::from_id_salt(("child_assistant", &profile.id))
        .selected_text(
            settings
                .assistants
                .iter()
                .find(|assistant| assistant.id == profile.assistant_id)
                .map(|assistant| assistant.name.as_str())
                .unwrap_or("跟随父助手"),
        )
        .show_ui(ui, |ui| {
            ui.selectable_value(&mut profile.assistant_id, String::new(), "跟随父助手");
            for assistant in &settings.assistants {
                ui.selectable_value(
                    &mut profile.assistant_id,
                    assistant.id.clone(),
                    &assistant.name,
                );
            }
        });
    model_selection(ui, "profile_child_model", &mut profile.model, settings);
    egui::ComboBox::from_id_salt(("child_approval", &profile.id))
        .selected_text(
            profile
                .approval_mode
                .map(ApprovalMode::label)
                .unwrap_or("继承审批"),
        )
        .show_ui(ui, |ui| {
            ui.selectable_value(&mut profile.approval_mode, None, "继承审批");
            for mode in ApprovalMode::ALL {
                ui.selectable_value(&mut profile.approval_mode, Some(mode), mode.label());
            }
        });
    let mut inherit = profile.allowed_tools.is_none();
    if ui.checkbox(&mut inherit, "继承父工具目录").changed() {
        profile.allowed_tools = if inherit { None } else { Some(Vec::new()) };
        if !inherit {
            profile.allowed_actions.clear();
        }
    }
    if let Some(allowed) = &mut profile.allowed_tools {
        ui.label(if allowed.is_empty() {
            "当前为仅文字子任务"
        } else {
            "实际工具仍会与父任务权限取交集"
        });
        egui::CollapsingHeader::new(format!("选择工具 · {} 个", allowed.len())).show(ui, |ui| {
            let mut names = tools
                .iter()
                .filter(|tool| subagent_configuration::is_child_tool(&tool.name))
                .map(|tool| tool.name.clone())
                .chain(allowed.iter().cloned())
                .collect::<Vec<_>>();
            names.sort();
            names.dedup();
            let query_id = egui::Id::unique(("child_tool_query", &profile.id));
            let mut query = ui
                .ctx()
                .data_mut(|data| data.get_temp::<String>(query_id))
                .unwrap_or_default();
            ui.add(
                TextEdit::singleline(&mut query)
                    .hint_text("搜索工具名称")
                    .char_limit(128),
            );
            ui.ctx()
                .data_mut(|data| data.insert_temp(query_id, query.clone()));
            let query = query.trim().to_lowercase();
            names.retain(|name| name.to_lowercase().contains(&query));
            if ui.button("全选当前目录").clicked() {
                *allowed = names
                    .iter()
                    .filter(|name| tools.iter().any(|tool| &tool.name == *name))
                    .cloned()
                    .collect();
            }
            if ui.button("清空选择").clicked() {
                allowed.clear();
                profile.allowed_actions.clear();
            }
            egui::ScrollArea::vertical()
                .id_salt(("child_allowed_tools", &profile.id))
                .max_height(180.0)
                .show(ui, |ui| {
                    for name in names {
                        let mut selected = allowed.contains(&name);
                        if ui.checkbox(&mut selected, &name).changed() {
                            allowed.retain(|current| current != &name);
                            if selected {
                                allowed.push(name.clone());
                            } else {
                                profile.allowed_actions.remove(&name);
                            }
                        }
                    }
                });
        });
        profile
            .allowed_actions
            .retain(|name, _| allowed.contains(name));
    }
    egui::CollapsingHeader::new("动作限制").show(ui, |ui| {
        for tool in tools.iter().filter(|tool| {
            subagent_configuration::is_child_tool(&tool.name)
                && profile
                    .allowed_tools
                    .as_ref()
                    .is_none_or(|allowed| allowed.contains(&tool.name))
        }) {
            let Some(values) = tool
                .parameters
                .get("properties")
                .and_then(|value| value.get("action"))
                .and_then(|value| value.get("enum"))
                .and_then(serde_json::Value::as_array)
            else {
                continue;
            };
            let mut restricted = profile.allowed_actions.contains_key(&tool.name);
            if ui
                .checkbox(&mut restricted, format!("限制 {} 的动作", tool.name))
                .changed()
            {
                if restricted {
                    profile
                        .allowed_actions
                        .insert(tool.name.clone(), Vec::new());
                } else {
                    profile.allowed_actions.remove(&tool.name);
                }
            }
            if let Some(allowed) = profile.allowed_actions.get_mut(&tool.name) {
                for value in values {
                    let Some(action) = value.as_str() else {
                        continue;
                    };
                    let mut selected = allowed.iter().any(|value| value == action);
                    if ui.checkbox(&mut selected, action).changed() {
                        allowed.retain(|value| value != action);
                        if selected {
                            allowed.push(action.into());
                        }
                    }
                }
            }
        }
    });
    let mut round_mode = match profile.max_tool_rounds {
        None => 0,
        Some(0) => 1,
        Some(_) => 2,
    };
    let original = round_mode;
    ui.horizontal(|ui| {
        ui.selectable_value(&mut round_mode, 0, "继承轮数");
        ui.selectable_value(&mut round_mode, 1, "不主动限制");
        ui.selectable_value(&mut round_mode, 2, "指定轮数");
    });
    if round_mode != original {
        profile.max_tool_rounds = match round_mode {
            0 => None,
            1 => Some(0),
            _ => Some(20),
        };
    }
    if let Some(rounds) = profile
        .max_tool_rounds
        .as_mut()
        .filter(|rounds| **rounds > 0)
    {
        ui.add(egui::DragValue::new(rounds).range(1..=u32::MAX));
    }
    ui.horizontal(|ui| {
        ui.label("最长运行时间（秒）");
        ui.add(egui::DragValue::new(&mut profile.timeout_seconds).range(10..=600));
    });
    ui.label("补充要求");
    ui.add(
        TextEdit::multiline(&mut profile.extra_instructions)
            .desired_rows(3)
            .desired_width(f32::INFINITY)
            .char_limit(64000),
    );
}
