use super::*;
use crate::{
    configuration::{Assistant, Provider},
    controller::unique_id,
};

impl RustApplication {
    fn fetch_models(&mut self, provider_id: Option<String>) {
        let request_id = unique_id();
        if self.dispatch(Action::FetchModels {
            request_id: request_id.clone(),
            provider_id,
        }) {
            self.model_fetch_id = Some(request_id);
        }
    }

    pub(super) fn models_fetched(
        &mut self,
        request_id: &str,
        provider_id: Option<&str>,
        fingerprint: &str,
        result: &Result<Vec<String>, String>,
    ) {
        if self.model_fetch_id.as_deref() != Some(request_id) {
            return;
        }
        self.model_fetch_id = None;
        let incoming = match result {
            Ok(models) => models,
            Err(error) => {
                self.state.error(error);
                return;
            }
        };
        if self
            .settings_draft
            .provider_fingerprint(provider_id)
            .as_deref()
            != Ok(fingerprint)
        {
            self.state
                .error("获取期间连接配置已变化，未把旧连接的模型写入新配置；请保存后重新获取");
            return;
        }
        let (models, selected, enabled) = match provider_id {
            Some(id) => match self
                .settings_draft
                .providers
                .iter_mut()
                .find(|item| item.id == id)
            {
                Some(provider) => (
                    &mut provider.models,
                    &mut provider.selected_model,
                    &mut provider.enabled_models,
                ),
                None => {
                    self.state.error("供应商已移除，未应用模型列表");
                    return;
                }
            },
            None => (
                &mut self.settings_draft.models,
                &mut self.settings_draft.model,
                &mut self.settings_draft.enabled_models,
            ),
        };
        let old_enabled = if enabled.is_none() {
            let mut previous = models.clone();
            if !selected.is_empty() && !previous.contains(selected) {
                previous.push(selected.clone());
            }
            previous
        } else {
            enabled.as_ref().cloned().unwrap_or_default()
        };
        let mut merged = models.clone();
        for model in incoming {
            if !merged.contains(model) {
                merged.push(model.clone());
            }
        }
        if !selected.trim().is_empty() && !merged.contains(selected) {
            merged.push(selected.clone());
        }
        if merged.len() > 1024 {
            self.state.error("合并后的模型列表超过1024个，未修改原列表");
            return;
        }
        *enabled = Some(old_enabled);
        *models = merged;
        self.state.info(format!(
            "已获取{}个模型并合并到配置草稿，请保存后使用",
            incoming.len()
        ));
    }

    pub(super) fn current_assistant_id(&self) -> Option<String> {
        match &self.state.selected_conversation {
            Some(id) => self
                .state
                .conversations
                .iter()
                .find(|item| &item.id == id)
                .and_then(|item| item.assistant_id.clone()),
            None => self.state.settings.active_assistant_id.clone(),
        }
    }

    pub(super) fn conversation_assistant(&mut self, ui: &mut egui::Ui) {
        let original = self.current_assistant_id();
        let mut selected = original.clone();
        let label = original
            .as_ref()
            .map(|id| {
                self.state
                    .settings
                    .assistants
                    .iter()
                    .find(|item| &item.id == id)
                    .map(|item| item.name.as_str())
                    .unwrap_or("助手已删除，请重新选择")
            })
            .unwrap_or("默认助手");
        ui.add_enabled_ui(
            self.state.loaded
                && !self.state.running
                && !self.state.conversation_pending
                && !self.state.settings_pending
                && self.settings_draft == self.state.settings,
            |ui| {
                egui::ComboBox::from_id_salt("conversation_assistant")
                    .selected_text(label)
                    .show_ui(ui, |ui| {
                        ui.selectable_value(&mut selected, None, "默认助手");
                        for item in &self.state.settings.assistants {
                            ui.selectable_value(&mut selected, Some(item.id.clone()), &item.name);
                        }
                    });
            },
        );
        if selected != original {
            if let Some(conversation_id) = &self.state.selected_conversation {
                self.dispatch(Action::SetConversationAssistant {
                    conversation_id: conversation_id.clone(),
                    assistant_id: selected,
                });
            } else {
                let mut settings = self.state.settings.clone();
                settings.active_assistant_id = selected;
                if self.settings_save_value(settings) {
                    self.state.settings_pending = true;
                }
            }
        }
    }

    pub(super) fn conversation_model(&mut self, ui: &mut egui::Ui) {
        let conversation = self.state.selected_conversation.as_ref().and_then(|id| {
            self.state
                .conversations
                .iter()
                .find(|conversation| &conversation.id == id)
        });
        let current = conversation
            .and_then(|conversation| {
                conversation
                    .model
                    .as_ref()
                    .map(|model| (conversation.provider_id.clone(), model.clone()))
            })
            .unwrap_or_else(|| {
                let provider = self.state.settings.active_provider_id.clone();
                let model = provider
                    .as_ref()
                    .and_then(|id| {
                        self.state
                            .settings
                            .providers
                            .iter()
                            .find(|provider| &provider.id == id)
                    })
                    .map(|provider| provider.selected_model.clone())
                    .unwrap_or_else(|| self.state.settings.model.clone());
                (provider, model)
            });
        let mut choice = current.clone();
        let editable = self.state.loaded
            && !self.state.running
            && !self.state.conversation_pending
            && !self.state.settings_pending
            && self.settings_draft == self.state.settings
            && self.model_choice_pending.is_none();
        let label = current
            .0
            .as_ref()
            .and_then(|id| {
                self.state
                    .settings
                    .providers
                    .iter()
                    .find(|provider| &provider.id == id)
            })
            .map(|provider| format!("{} · {}", provider.name, provider.display_model(&current.1)))
            .unwrap_or_else(|| current.1.clone());
        ui.add_enabled_ui(editable, |ui| {
            egui::ComboBox::from_id_salt("conversation_model")
                .selected_text(if label.is_empty() {
                    "选择会话模型"
                } else {
                    &label
                })
                .show_ui(ui, |ui| {
                    for model in self
                        .state
                        .settings
                        .enabled_models
                        .as_deref()
                        .unwrap_or(&self.state.settings.models)
                    {
                        ui.selectable_value(
                            &mut choice,
                            (None, model.clone()),
                            format!("默认连接 · {model}"),
                        );
                    }
                    for provider in &self.state.settings.providers {
                        if !provider.enabled_models().is_empty() {
                            ui.label(RichText::new(&provider.name).strong());
                        }
                        for model in provider.enabled_models() {
                            ui.selectable_value(
                                &mut choice,
                                (Some(provider.id.clone()), model.clone()),
                                provider.display_model(model),
                            );
                        }
                    }
                });
        });
        if choice != current {
            let request_id = unique_id();
            if self.dispatch(Action::SetConversationModel {
                request_id: request_id.clone(),
                conversation_id: self.state.selected_conversation.clone(),
                provider_id: choice.0,
                model: choice.1,
            }) {
                self.model_choice_pending = Some(request_id);
            }
        }
    }

    pub(super) fn provider_settings(&mut self, ui: &mut egui::Ui) {
        ui.heading("供应商与模型");
        let can_fetch = self.model_fetch_id.is_none() && self.settings_draft == self.state.settings;
        let mut fetch = None;
        let settings = &mut self.settings_draft;
        let selected = settings
            .active_provider_id
            .as_ref()
            .and_then(|id| settings.providers.iter().find(|item| &item.id == id))
            .map(|item| item.name.as_str())
            .unwrap_or("默认连接");
        egui::ComboBox::from_id_salt("active_provider")
            .selected_text(selected)
            .show_ui(ui, |ui| {
                ui.selectable_value(&mut settings.active_provider_id, None, "默认连接");
                for provider in &settings.providers {
                    ui.selectable_value(
                        &mut settings.active_provider_id,
                        Some(provider.id.clone()),
                        &provider.name,
                    );
                }
            });
        let mut delete = None;
        for (index, provider) in settings.providers.iter_mut().enumerate() {
            egui::CollapsingHeader::new(&provider.name)
                .id_salt(("provider", &provider.id))
                .show(ui, |ui| {
                    text_field(ui, "名称", &mut provider.name, "供应商名称", false);
                    text_field(ui, "接口地址", &mut provider.endpoint, "https://…", false);
                    text_field(
                        ui,
                        "API Key",
                        &mut provider.api_key,
                        "使用本机 Keystore 加密保存",
                        true,
                    );
                    egui::ComboBox::from_id_salt(("provider_protocol", &provider.id))
                        .selected_text(provider.protocol.label())
                        .show_ui(ui, |ui| {
                            for protocol in Protocol::ALL {
                                ui.selectable_value(
                                    &mut provider.protocol,
                                    protocol,
                                    protocol.label(),
                                );
                            }
                        });
                    ui.label("模型 ID 列表");
                    if ui
                        .add_enabled(can_fetch, egui::Button::new("获取此供应商模型列表"))
                        .clicked()
                    {
                        fetch = Some(Some(provider.id.clone()));
                    }
                    let mut remove_model = None;
                    ui.checkbox(&mut provider.own_sampling, "使用供应商独立采样参数");
                    if provider.own_sampling {
                        super::settings::provider_sampling(
                            ui,
                            &provider.id,
                            &mut provider.temperature,
                            &mut provider.top_p,
                            &mut settings.behavior,
                        );
                        let mut reasoning = provider.reasoning_effort.is_some();
                        if ui.checkbox(&mut reasoning, "覆盖推理强度").changed() {
                            provider.reasoning_effort = reasoning.then(String::new);
                        }
                        if let Some(value) = &mut provider.reasoning_effort {
                            egui::ComboBox::from_id_salt(("provider_reasoning", &provider.id))
                                .selected_text(if value.is_empty() {
                                    "服务端默认"
                                } else {
                                    value.as_str()
                                })
                                .show_ui(ui, |ui| {
                                    for option in
                                        ["", "none", "minimal", "low", "medium", "high", "xhigh"]
                                    {
                                        ui.selectable_value(
                                            value,
                                            option.into(),
                                            if option.is_empty() {
                                                "服务端默认"
                                            } else {
                                                option
                                            },
                                        );
                                    }
                                });
                        }
                    }
                    let mut initial_models = provider.models.clone();
                    for (model_index, model) in provider.models.iter_mut().enumerate() {
                        ui.push_id(model_index, |ui| {
                            ui.horizontal(|ui| {
                                let previous = model.clone();
                                let changed = ui
                                    .add(
                                        TextEdit::singleline(model)
                                            .desired_width(180.0)
                                            .char_limit(1024),
                                    )
                                    .changed();
                                if changed && provider.selected_model == previous {
                                    provider.selected_model = model.clone();
                                }
                                if changed {
                                    for value in initial_models
                                        .iter_mut()
                                        .filter(|value| **value == previous)
                                    {
                                        *value = model.clone();
                                    }
                                    if let Some(enabled) = &mut provider.enabled_models {
                                        for value in
                                            enabled.iter_mut().filter(|value| **value == previous)
                                        {
                                            *value = model.clone();
                                        }
                                    }
                                    if let Some(alias) = provider.aliases.remove(&previous) {
                                        provider.aliases.insert(model.clone(), alias);
                                    }
                                }
                                let mut enabled = provider
                                    .enabled_models
                                    .as_ref()
                                    .map_or(true, |models| models.contains(model));
                                if ui.checkbox(&mut enabled, "启用").changed() {
                                    let values = provider
                                        .enabled_models
                                        .get_or_insert_with(|| initial_models.clone());
                                    values.retain(|value| value != model);
                                    if enabled {
                                        values.push(model.clone());
                                    }
                                    if !enabled && provider.selected_model == *model {
                                        provider.selected_model.clear();
                                    }
                                }
                                if ui.button("移除").clicked() {
                                    remove_model = Some(model_index);
                                }
                            });
                            if !model.is_empty() {
                                let mut alias =
                                    provider.aliases.get(model).cloned().unwrap_or_default();
                                if ui
                                    .add(
                                        TextEdit::singleline(&mut alias)
                                            .hint_text("模型显示名称（可选）")
                                            .char_limit(1024),
                                    )
                                    .changed()
                                {
                                    if alias.trim().is_empty() {
                                        provider.aliases.remove(model);
                                    } else {
                                        provider.aliases.insert(model.clone(), alias);
                                    }
                                }
                            }
                        });
                    }
                    if let Some(index) = remove_model {
                        let removed = provider.models.remove(index);
                        if provider.selected_model == removed {
                            provider.selected_model.clear();
                        }
                        if let Some(enabled) = &mut provider.enabled_models {
                            enabled.retain(|model| model != &removed);
                        }
                        provider.aliases.remove(&removed);
                    }
                    let draft_id = egui::Id::unique(("provider_new_model", &provider.id));
                    let mut candidate = ui
                        .ctx()
                        .data_mut(|data| data.get_temp::<String>(draft_id))
                        .unwrap_or_default();
                    ui.add(
                        TextEdit::singleline(&mut candidate)
                            .hint_text("手动添加的模型 ID")
                            .char_limit(1024),
                    );
                    if ui
                        .add_enabled(
                            !candidate.trim().is_empty()
                                && (provider.models.len() < 1024
                                    || provider
                                        .models
                                        .iter()
                                        .any(|model| model == candidate.trim())),
                            egui::Button::new("添加并启用模型"),
                        )
                        .clicked()
                    {
                        let model = candidate.trim().to_owned();
                        let old = provider.models.clone();
                        if !provider.models.contains(&model) {
                            provider.models.push(model.clone());
                        }
                        let enabled = provider.enabled_models.get_or_insert(old);
                        if !enabled.contains(&model) {
                            enabled.push(model.clone());
                        }
                        provider.selected_model = model;
                        candidate.clear();
                    }
                    ui.ctx()
                        .data_mut(|data| data.insert_temp(draft_id, candidate));
                    egui::ComboBox::from_id_salt(("provider_model", &provider.id))
                        .selected_text(if provider.selected_model.is_empty() {
                            "选择当前模型"
                        } else {
                            &provider.selected_model
                        })
                        .show_ui(ui, |ui| {
                            let models = provider
                                .enabled_models
                                .as_deref()
                                .unwrap_or(&provider.models);
                            ui.selectable_value(
                                &mut provider.selected_model,
                                String::new(),
                                "暂不选择模型",
                            );
                            for model in models {
                                ui.selectable_value(
                                    &mut provider.selected_model,
                                    model.clone(),
                                    model,
                                );
                            }
                        });
                    let referenced = settings
                        .assistants
                        .iter()
                        .any(|assistant| assistant.provider_id.as_ref() == Some(&provider.id))
                        || self.state.conversations.iter().any(|conversation| {
                            conversation.provider_id.as_ref() == Some(&provider.id)
                        });
                    if ui
                        .add_enabled(!referenced, egui::Button::new("删除供应商"))
                        .clicked()
                    {
                        delete = Some(index);
                    }
                    if referenced {
                        ui.label("会话或助手仍引用该供应商，请先调整对应选择。");
                    }
                });
        }
        if let Some(index) = delete {
            let removed = settings.providers.remove(index);
            settings.behavior.providers_initialized = true;
            if settings.active_provider_id.as_ref() == Some(&removed.id) {
                settings.active_provider_id = None;
            }
        }
        if ui
            .add_enabled(
                settings.providers.len() < 128,
                egui::Button::new("添加供应商"),
            )
            .clicked()
        {
            settings.providers.push(Provider {
                id: unique_id(),
                name: "新供应商".into(),
                enabled_models: Some(Vec::new()),
                ..Default::default()
            });
        }
        ui.separator();
        ui.label("默认连接模型列表");
        let draft_id = egui::Id::unique("default_new_model");
        let mut candidate = ui
            .ctx()
            .data_mut(|data| data.get_temp::<String>(draft_id))
            .unwrap_or_default();
        ui.add(
            TextEdit::singleline(&mut candidate)
                .hint_text("手动添加的模型 ID")
                .char_limit(1024),
        );
        if ui
            .add_enabled(
                !candidate.trim().is_empty()
                    && (settings.models.len() < 1024
                        || settings
                            .models
                            .iter()
                            .any(|model| model == candidate.trim())),
                egui::Button::new("添加并启用默认连接模型"),
            )
            .clicked()
        {
            let model = candidate.trim().to_owned();
            let previous = settings.models.clone();
            if !settings.models.contains(&model) {
                settings.models.push(model.clone());
            }
            let enabled = settings.enabled_models.get_or_insert(previous);
            if !enabled.contains(&model) {
                enabled.push(model.clone());
            }
            settings.model = model;
            candidate.clear();
        }
        ui.ctx()
            .data_mut(|data| data.insert_temp(draft_id, candidate));
        if !settings.models.is_empty() {
            egui::ComboBox::from_id_salt("default_model_list")
                .selected_text(if settings.model.is_empty() {
                    "选择模型"
                } else {
                    &settings.model
                })
                .show_ui(ui, |ui| {
                    for model in settings
                        .enabled_models
                        .as_deref()
                        .unwrap_or(&settings.models)
                    {
                        ui.selectable_value(&mut settings.model, model.clone(), model);
                    }
                });
            if ui.button("清空默认连接模型列表").clicked() {
                settings.models.clear();
                settings.enabled_models = Some(Vec::new());
                settings.model.clear();
            }
            let initial = settings.models.clone();
            for model in &settings.models {
                let mut enabled = settings
                    .enabled_models
                    .as_ref()
                    .map_or(true, |values| values.contains(model));
                if ui.checkbox(&mut enabled, model).changed() {
                    let values = settings
                        .enabled_models
                        .get_or_insert_with(|| initial.clone());
                    values.retain(|value| value != model);
                    if enabled {
                        values.push(model.clone());
                    } else if settings.model == *model {
                        settings.model.clear();
                    }
                }
            }
        }
        if ui
            .add_enabled(can_fetch, egui::Button::new("获取默认连接模型列表"))
            .clicked()
        {
            fetch = Some(None);
        }
        ui.label(
            RichText::new("供应商、模型及选择在本页底部保存。API Key 由本机 Keystore 加密持久化。")
                .small()
                .weak(),
        );
        if let Some(provider_id) = fetch {
            self.fetch_models(provider_id);
        }
        if let Some(request_id) = self.model_fetch_id.clone() {
            ui.horizontal(|ui| {
                ui.spinner();
                ui.label("正在获取模型列表");
                if ui.button("取消获取").clicked() {
                    self.dispatch(Action::CancelModels { request_id });
                }
            });
        } else if !can_fetch {
            ui.label(
                RichText::new("请先保存连接配置，再获取模型列表。")
                    .small()
                    .weak(),
            );
        }
        ui.separator();
    }

    pub(super) fn advanced_generation(&mut self, ui: &mut egui::Ui) {
        egui::CollapsingHeader::new("高级生成参数").show(ui, |ui| {
            let model = self
                .settings_draft
                .active_provider_id
                .as_ref()
                .and_then(|id| {
                    self.settings_draft
                        .providers
                        .iter()
                        .find(|provider| &provider.id == id)
                })
                .map(|provider| provider.selected_model.clone())
                .unwrap_or_else(|| self.settings_draft.model.clone());
            if !model.is_empty() {
                let mut value = self
                    .settings_draft
                    .model_max_tokens
                    .get(&model)
                    .copied()
                    .unwrap_or(0);
                ui.horizontal(|ui| {
                    ui.label(format!("{model} 最大输出 tokens（0 使用默认）"));
                    if ui
                        .add(egui::DragValue::new(&mut value).range(0..=1_000_000))
                        .changed()
                    {
                        self.settings_draft
                            .model_max_tokens
                            .insert(model.clone(), value);
                    }
                });
            } else {
                ui.label("选择模型后可设置该模型的最大输出。");
            }
            egui::ComboBox::from_id_salt("reasoning_effort")
                .selected_text(if self.settings_draft.reasoning_effort.is_empty() {
                    "推理强度：服务端默认"
                } else {
                    &self.settings_draft.reasoning_effort
                })
                .show_ui(ui, |ui| {
                    for value in ["", "none", "minimal", "low", "medium", "high", "xhigh"] {
                        ui.selectable_value(
                            &mut self.settings_draft.reasoning_effort,
                            value.into(),
                            if value.is_empty() {
                                "服务端默认"
                            } else {
                                value
                            },
                        );
                    }
                });
        });
    }

    pub(super) fn assistants(&mut self, ui: &mut egui::Ui) {
        let mut avatar_requests = Vec::new();
        let mut avatar_pick = None;
        let editable = self.state.loaded
            && !self.state.settings_pending
            && self.model_choice_pending.is_none();
        egui::ScrollArea::vertical().id_salt("assistants").show(ui, |ui| {
            ui.heading("助手");
            ui.label("助手设置提示词、温度和工具范围。供应商与模型由会话独立选择，子任务模板可指定自己的模型。");
            ui.add_enabled_ui(editable, |ui| {
                let avatars=&mut self.avatar_view;
                let settings = &mut self.settings_draft;
                let active = settings.active_assistant_id.as_ref().and_then(|id| settings.assistants.iter().find(|item| &item.id == id)).map(|item| item.name.as_str()).unwrap_or("默认助手");
                egui::ComboBox::from_id_salt("default_assistant").selected_text(active).show_ui(ui, |ui| {
                    ui.selectable_value(&mut settings.active_assistant_id, None, "默认助手");
                    for item in &settings.assistants { ui.selectable_value(&mut settings.active_assistant_id, Some(item.id.clone()), &item.name); }
                });
                let mut delete = None;
                for (index, assistant) in settings.assistants.iter_mut().enumerate() {
                    egui::CollapsingHeader::new(&assistant.name).id_salt(("assistant", &assistant.id)).show(ui, |ui| {
                        text_field(ui, "名称", &mut assistant.name, "助手名称", false);
                        avatars.show(ui, assistant, 48.0, &mut avatar_requests);
                        ui.horizontal(|ui| {
                            if ui.button(if assistant.avatar_path.is_empty(){"上传图片"}else{"换一张"}).clicked(){avatar_pick=Some((assistant.id.clone(),assistant.avatar_path.clone()));}
                            if !assistant.avatar_path.is_empty() && ui.button("移除图片").clicked(){assistant.avatar_path.clear();}
                        });
                        if text_field_avatar(ui, &mut assistant.avatar) {assistant.avatar_path.clear();}
                        text_field(ui, "简介", &mut assistant.description, "用途说明", false);
                        ui.label("系统提示词");
                        ui.add(TextEdit::multiline(&mut assistant.system_prompt).desired_rows(6).desired_width(f32::INFINITY).char_limit(64 * 1024));
                        let mut custom_temperature = assistant.temperature.is_some();
                        if ui.checkbox(&mut custom_temperature, "覆盖温度").changed() { assistant.temperature = custom_temperature.then_some(1.0); }
                        if let Some(value) = &mut assistant.temperature { ui.add(egui::DragValue::new(value).range(0.0..=2.0).speed(0.05)); }
                        ui.label("禁用工具");
                        for tool in crate::tools::definitions() {
                            let mut disabled = assistant.disabled_tools.contains(&tool.name);
                            if ui.checkbox(&mut disabled, &tool.name).changed() {
                                assistant.disabled_tools.retain(|name| name != &tool.name);
                                if disabled { assistant.disabled_tools.push(tool.name); }
                            }
                        }
                        let referenced = self.state.conversations.iter().any(|conversation| conversation.assistant_id.as_ref() == Some(&assistant.id));
                        if ui.add_enabled(!referenced, egui::Button::new("删除助手")).clicked() { delete = Some(index); }
                        if referenced { ui.label("已有会话使用此助手，切换这些会话的助手后可删除。"); }
                    });
                }
                if let Some(index) = delete {
                    let assistant = settings.assistants.remove(index);
                    if settings.active_assistant_id.as_ref() == Some(&assistant.id) { settings.active_assistant_id = None; }
                }
                if ui.add_enabled(settings.assistants.len() < 256, egui::Button::new("新建助手")).clicked() {
                    settings.assistants.push(Assistant { id: unique_id(), name: "新助手".into(), ..Default::default() });
                }
            });
            ui.separator();
            let dirty = self.settings_draft != self.state.settings;
            if ui.add_enabled(editable && dirty, egui::Button::new("保存配置与助手")).clicked() && self.settings_save_value(self.settings_draft.clone()) { self.state.settings_pending = true; }
            if ui.add_enabled(editable && dirty, egui::Button::new("放弃修改")).clicked() { self.settings_draft = self.state.settings.clone(); }
            if self.state.settings_pending { ui.spinner(); }
            ui.label(RichText::new("已有子任务保留启动时的助手和模板快照。图片裁剪后随助手配置保存；原图片在保存失败时保留。").small().weak());
        });
        self.avatar_requests(avatar_requests);
        if let Some((assistant_id, previous_path)) = avatar_pick {
            self.avatar_pick(assistant_id, previous_path);
        }
    }
}

fn text_field_avatar(ui: &mut egui::Ui, avatar: &mut String) -> bool {
    ui.horizontal(|ui| {
        ui.label("形象");
        ui.add(
            TextEdit::singleline(avatar)
                .hint_text("emoji（可选）")
                .char_limit(128),
        )
        .changed()
    })
    .inner
}
