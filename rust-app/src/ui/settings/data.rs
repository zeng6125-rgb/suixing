use super::*;
impl RustApplication {
    pub(super) fn settings_data(&mut self, ui: &mut egui::Ui) {
        for (title, rows) in [
            (
                "记忆",
                &[
                    (Some(Page::Memory), "用户记忆", "模型自己写下的长期备忘"),
                    (
                        None,
                        "用户画像",
                        "模型自己维护的长期自述：称呼、偏好与习惯，也可手改",
                    ),
                ][..],
            ),
            (
                "助手与文件",
                &[
                    (
                        Some(Page::Assistants),
                        "助手设置",
                        "每个助手的名字、提示词、模型与工具",
                    ),
                    (
                        Some(Page::Conversations),
                        "会话管理",
                        "打开、导出或删除聊天记录（不是工作区文件）",
                    ),
                    (
                        Some(Page::Files),
                        "工作区文件",
                        "agent 在会话里读写的工作区文件，与聊天记录无关",
                    ),
                ][..],
            ),
        ] {
            controls::group_header(ui, title);
            let mut selected = None;
            controls::card(ui, 0).show(ui, |ui| {
                ui.set_width(ui.available_width());
                for &(page, label, description) in rows {
                    ui.push_id(label, |ui| {
                        if controls::link(ui, label, description) {
                            selected = Some(page);
                        }
                    });
                }
            });
            if let Some(page) = selected {
                if let Some(page) = page {
                    self.navigate(Navigation::Page(page), false);
                } else {
                    self.settings_view.section = Section::Profile;
                }
            }
        }
        if self.settings_view.legacy_upgrade.is_some() || self.legacy_preview.is_some() {
            ui.add_space(12.0);
            ui.collapsing("旧版资料", |ui| self.settings_legacy_migration(ui));
        }
        ui.add_space(24.0);
    }

    pub(super) fn settings_legacy_upgrade(&mut self, ui: &mut egui::Ui) {
        let imported = self
            .legacy_import_report
            .as_ref()
            .is_some_and(Result::is_ok);
        egui::ScrollArea::vertical()
            .id_salt("first_run_legacy")
            .show(ui, |ui| {
                match &self.settings_view.legacy_upgrade {
                    Some(Err(error)) => {
                        ui.label(format!("暂时无法检查旧版数据：{error}"));
                    }
                    _ => {
                        ui.label("发现此应用原有的数据。你可以先查看迁移清单，再决定是否导入。");
                    }
                }
                self.settings_legacy_migration(ui);
                ui.separator();
                if !imported {
                    ui.label("先保存新设置可能与旧设置发生冲突，导致之后的整批导入停止。");
                }
                let label = if imported {
                    "继续使用"
                } else {
                    "暂不迁移，继续设置"
                };
                if ui
                    .add_enabled(!self.legacy_pending, egui::Button::new(label))
                    .clicked()
                {
                    self.settings_view.legacy_upgrade = None;
                    self.settings_view.section = Section::Root;
                    self.state.page = Page::Chat;
                    self.settings_begin_onboarding(self.state.settings.behavior.onboarding_seen);
                }
            });
        self.settings_legacy_confirmation(ui);
    }

    fn settings_legacy_migration(&mut self, ui: &mut egui::Ui) {
        if ui
            .add_enabled(
                !self.legacy_pending && !self.state.running,
                egui::Button::new("查看旧数据迁移清单"),
            )
            .clicked()
            && self.dispatch(Action::PreviewLegacyData)
        {
            self.legacy_pending = true;
        }
        if self.legacy_pending {
            ui.spinner();
        }
        if let Some(result) = &self.legacy_preview {
            match result {
                Ok(preview) if preview.available => {
                    ui.label(format!(
                        "发现 {} 个会话、{} 份文档、{} 份偏好存储",
                        preview.conversations, preview.documents, preview.preference_stores
                    ));
                    if preview.workspace_entries != 0 || preview.original_assets != 0 {
                        ui.label(format!(
                            "工作区项目 {} 个，原始文件 {} 份（包含待迁入的工作区文件）",
                            preview.workspace_entries, preview.original_assets
                        ));
                    }
                    ui.label(
                        "清单已经读取，迁移会保留原文件并校验来源。相同ID存在差异时会停止迁移。",
                    );
                    if ui
                        .add_enabled(
                            !self.legacy_pending && self.state.runs.is_empty(),
                            egui::Button::new("迁移这些旧数据"),
                        )
                        .clicked()
                    {
                        self.legacy_confirm = Some(preview.source_fingerprint.clone());
                    }
                    for warning in &preview.warnings {
                        ui.add(egui::Label::new(warning).wrap());
                    }
                }
                Ok(_) => {
                    ui.label("当前应用目录内没有发现原版数据。");
                }
                Err(error) => {
                    ui.add(egui::Label::new(error).wrap().selectable(true));
                }
            }
        }
        if let Some(result) = &self.legacy_import_report {
            match result {
                Ok(report) => {
                    ui.label(format!(
                        "已迁移 {} 个会话，事务：{}",
                        report.conversations, report.transaction_id
                    ));
                    if !report.durability_confirmed || !report.source_stable_after_commit {
                        ui.label(
                            "新状态已经发布，持久化或旧源稳定性尚未确认；请重新打开应用核对。",
                        );
                    }
                    for warning in &report.warnings {
                        ui.add(egui::Label::new(warning).wrap());
                    }
                    for feature in &report.retained_features {
                        ui.label(format!("已归档保留，仍需功能接入：{feature}"));
                    }
                }
                Err(error) => {
                    ui.add(egui::Label::new(error).wrap().selectable(true));
                }
            }
        }
    }
    pub(super) fn settings_legacy_confirmation(&mut self, ui: &mut egui::Ui) {
        if let Some(fingerprint) = self.legacy_confirm.clone() {
            let mut decision = None;
            egui::Window::new("确认迁移旧数据").collapsible(false).show(ui.ctx(), |ui| {
                ui.label("会将这份清单中的数据合并到新的状态代。原源保留，检测到ID冲突或来源变化时停止。");
                ui.horizontal(|ui| {
                    if ui.button("取消").clicked() { decision = Some(false); }
                    if ui.button("开始迁移").clicked() { decision = Some(true); }
                });
            });
            if let Some(accepted) = decision {
                self.legacy_confirm = None;
                if accepted
                    && self.dispatch(Action::ImportLegacyData {
                        source_fingerprint: fingerprint,
                    })
                {
                    self.legacy_pending = true;
                    self.legacy_import_report = None;
                }
            }
        }
    }
}
