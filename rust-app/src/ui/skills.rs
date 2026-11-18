use super::*;
use crate::skills::Skill;

impl RustApplication {
    pub(super) fn skills(&mut self, ui: &mut egui::Ui) {
        let editable = self.state.loaded && !self.state.skills_pending;
        ui.heading("技能");
        ui.label("启用技能的名称与简介进入模型上下文；正文通过逐次审批的 skill_read 按需读取。");
        ui.horizontal(|ui| {
            if ui
                .add_enabled(editable, egui::Button::new("新建技能"))
                .clicked()
            {
                self.skill_editor = Some(Skill {
                    enabled: true,
                    source: "created_by_user".into(),
                    ..Default::default()
                });
                self.skill_import_open = false;
            }
            if ui
                .add_enabled(editable, egui::Button::new("导入 SKILL.md 文本"))
                .clicked()
            {
                self.skill_import_open = true;
                self.skill_editor = None;
            }
            if ui
                .add_enabled(editable, egui::Button::new("从设备或链接导入"))
                .clicked()
            {
                self.skill_package.open = true;
            }
            if self.state.skills_pending {
                ui.spinner();
            }
        });
        self.skill_package_ui(ui, editable);
        egui::ScrollArea::vertical()
            .id_salt("skills")
            .show(ui, |ui| {
                if self.skill_import_open {
                    egui::Frame::group(ui.style()).show(ui, |ui| {
                        text_field(
                            ui,
                            "技能ID",
                            &mut self.skill_import_id,
                            "英文、数字、下划线或短横线",
                            false,
                        );
                        ui.add_enabled(
                            editable,
                            TextEdit::multiline(&mut self.skill_import_document)
                                .desired_rows(10)
                                .desired_width(f32::INFINITY)
                                .char_limit(2 * 1024 * 1024)
                                .hint_text("粘贴 SKILL.md 内容，可含 Markdown 与可选 frontmatter"),
                        );
                        ui.label("导入的外部内容默认关闭。请审阅后在技能列表中手动启用。");
                        if ui
                            .add_enabled(
                                editable
                                    && !self.skill_import_id.is_empty()
                                    && !self.skill_import_document.trim().is_empty(),
                                egui::Button::new("保存为未启用技能"),
                            )
                            .clicked()
                            && self.dispatch(Action::ImportSkill {
                                id: self.skill_import_id.clone(),
                                document: self.skill_import_document.clone(),
                            })
                        {
                            self.state.skills_pending = true;
                        }
                        if ui
                            .add_enabled(editable, egui::Button::new("取消导入"))
                            .clicked()
                        {
                            self.skill_import_open = false;
                        }
                    });
                }
                let mut save = None;
                let mut close = false;
                if let Some(editor) = &mut self.skill_editor {
                    egui::Frame::group(ui.style()).show(ui, |ui| {
                        ui.add_enabled_ui(editable, |ui| {
                            ui.add_enabled_ui(editor.revision == 0, |ui| {
                                text_field(
                                    ui,
                                    "技能ID",
                                    &mut editor.id,
                                    "英文、数字、下划线或短横线",
                                    false,
                                )
                            });
                            text_field(ui, "名称", &mut editor.name, "显示名称", false);
                            text_field(ui, "简介", &mut editor.description, "何时使用", false);
                            ui.label("完整指令（最多2 MiB）");
                            ui.add(
                                TextEdit::multiline(&mut editor.content)
                                    .desired_rows(12)
                                    .desired_width(f32::INFINITY)
                                    .char_limit(2 * 1024 * 1024),
                            );
                            ui.checkbox(&mut editor.enabled, "启用");
                            ui.horizontal(|ui| {
                                if ui.button("保存技能").clicked() {
                                    save = Some(editor.clone());
                                }
                                if ui.button("取消编辑").clicked() {
                                    close = true;
                                }
                            });
                        });
                    });
                }
                if close {
                    self.skill_editor = None;
                }
                if let Some(skill) = save {
                    if self.dispatch(Action::SaveSkill(skill)) {
                        self.state.skills_pending = true;
                    }
                }
                ui.separator();
                if self.state.skills.is_empty() {
                    ui.label("暂无技能。新建或导入后可在此审阅与启用。");
                }
                let mut action = None;
                for skill in &self.state.skills {
                    ui.push_id(&skill.id, |ui| {
                        egui::Frame::group(ui.style()).show(ui, |ui| {
                            ui.heading(&skill.name);
                            ui.label(format!(
                                "{} · 版本 {} · {}",
                                skill.id, skill.revision, skill.source
                            ));
                            ui.add(egui::Label::new(&skill.description).wrap());
                            let mut enabled = skill.enabled;
                            if ui
                                .add_enabled(editable, egui::Checkbox::new(&mut enabled, "启用"))
                                .changed()
                            {
                                action = Some(Action::SetSkillEnabled {
                                    id: skill.id.clone(),
                                    expected_revision: skill.revision,
                                    enabled,
                                });
                            }
                            egui::CollapsingHeader::new("审阅完整指令").show(ui, |ui| {
                                egui::ScrollArea::vertical()
                                    .max_height(240.0)
                                    .show(ui, |ui| {
                                        let page_id = egui::Id::new((
                                            "skill-body-page",
                                            &skill.id,
                                            skill.revision,
                                        ));
                                        let mut offset = ui
                                            .ctx()
                                            .data(|d| d.get_temp::<usize>(page_id))
                                            .unwrap_or(0)
                                            .min(skill.content.len());
                                        while offset > 0 && !skill.content.is_char_boundary(offset)
                                        {
                                            offset -= 1;
                                        }
                                        let mut end = offset
                                            .saturating_add(32 * 1024)
                                            .min(skill.content.len());
                                        while end > offset && !skill.content.is_char_boundary(end) {
                                            end -= 1;
                                        }
                                        ui.add(
                                            egui::Label::new(&skill.content[offset..end])
                                                .wrap()
                                                .selectable(true),
                                        );
                                        ui.horizontal(|ui| {
                                            ui.small(format!(
                                                "{}–{} / {} 字节",
                                                offset,
                                                end,
                                                skill.content.len()
                                            ));
                                            if offset > 0 && ui.button("回到开头").clicked() {
                                                ui.ctx()
                                                    .data_mut(|d| d.insert_temp(page_id, 0usize));
                                            }
                                            if end < skill.content.len()
                                                && ui.button("下一页指令").clicked()
                                            {
                                                ui.ctx().data_mut(|d| d.insert_temp(page_id, end));
                                            }
                                        });
                                        let document = skill.active_document_path();
                                        if !document.is_empty() {
                                            ui.small(format!("当前文档：{document}"));
                                        }
                                        if !skill.edited_document_path.is_empty() {
                                            ui.small(format!("原始文档：{}", skill.document_path));
                                        }
                                        if !skill.files_path.is_empty() {
                                            ui.small(format!("资源目录：{}", skill.files_path));
                                        }
                                    });
                            });
                            ui.horizontal(|ui| {
                                if ui
                                    .add_enabled(editable, egui::Button::new("编辑"))
                                    .clicked()
                                {
                                    self.skill_editor = Some(skill.clone());
                                    self.skill_import_open = false;
                                }
                                if ui
                                    .add_enabled(editable, egui::Button::new("删除"))
                                    .clicked()
                                {
                                    self.skill_delete = Some((skill.id.clone(), skill.revision));
                                }
                            });
                            if self
                                .skill_delete
                                .as_ref()
                                .is_some_and(|(id, _)| id == &skill.id)
                            {
                                if skill.source == "workspace_file" {
                                    ui.label("手动放入工作区的原文件会保留，可再次扫描导入。");
                                }
                                ui.label("确认删除这份技能？");
                                if ui
                                    .add_enabled(editable, egui::Button::new("确认删除"))
                                    .clicked()
                                {
                                    if let Some((id, expected_revision)) = &self.skill_delete {
                                        action = Some(Action::DeleteSkill {
                                            id: id.clone(),
                                            expected_revision: *expected_revision,
                                        });
                                    }
                                }
                                if ui.button("保留").clicked() {
                                    self.skill_delete = None;
                                }
                            }
                        });
                    });
                }
                if let Some(action) = action {
                    if self.dispatch(action) {
                        self.state.skills_pending = true;
                    }
                }
            });
    }
}

#[derive(Default)]
pub(super) struct PackageImport {
    open: bool,
    url: String,
    path: String,
    pending: Option<crate::platform::permissions::OperationKey>,
    review: Option<crate::skill_import::Review>,
    recovered: Vec<crate::skill_import::Review>,
    document: Option<(String, usize, Option<usize>, String)>,
    error: Option<String>,
}
fn key() -> crate::platform::permissions::OperationKey {
    crate::platform::permissions::OperationKey {
        id: format!("skill-import-{}", crate::controller::unique_id()),
        conversation_id: None,
        generation: 1,
    }
}
impl RustApplication {
    pub(super) fn skill_import_received(&mut self, update: crate::skill_import::Update) {
        use crate::skill_import::State;
        if let State::Expired { ticket, notice } = &update.state {
            self.skill_package.recovered.retain(|r| r.ticket != *ticket);
            if self
                .skill_package
                .review
                .as_ref()
                .is_some_and(|r| r.ticket == *ticket)
            {
                self.skill_package.review = None;
                self.skill_package.document = None;
                self.skill_package.error = Some(
                    notice
                        .clone()
                        .unwrap_or_else(|| "未保存候选已到期，匹配的私有复制文件已回收。".into()),
                );
            }
            return;
        }
        if self.skill_package.pending.as_ref() != Some(&update.operation) {
            return;
        }
        let terminal = !matches!(&update.state, State::WaitingForTerminal);
        match update.state {
            State::Recovered { reviews, warnings } => {
                self.skill_package.recovered = reviews;
                self.skill_package.document = None;
                self.skill_package.error = if warnings.is_empty() {
                    None
                } else {
                    Some(warnings.join("\n"))
                };
            }
            State::Expired { .. } => {}
            State::Prepared(review) => {
                self.skill_package.review = Some(review);
                self.skill_package.document = None;
                self.skill_package.error = None;
            }
            State::Document {
                ticket,
                id,
                offset,
                next,
                text,
            } => {
                if self
                    .skill_package
                    .review
                    .as_ref()
                    .is_some_and(|r| r.ticket == ticket)
                {
                    self.skill_package.document = Some((id, offset, next, text));
                }
            }
            State::Saved(ids) => {
                if let Some(review) = &self.skill_package.review {
                    self.skill_package
                        .recovered
                        .retain(|r| r.ticket != review.ticket);
                }
                self.skill_package.review = None;
                self.skill_package.document = None;
                self.skill_package.error = Some(format!(
                    "已保存 {} 份技能，全部保持未启用。请审阅后在列表中手动启用。",
                    ids.len()
                ));
            }
            State::WorkspaceRefreshed(notice) => self.skill_package.error = Some(notice),
            State::Discarded => {
                if let Some(review) = &self.skill_package.review {
                    self.skill_package
                        .recovered
                        .retain(|r| r.ticket != review.ticket);
                }
                self.skill_package.review = None;
                self.skill_package.document = None;
                self.skill_package.error = None;
            }
            State::Cancelled => {
                self.skill_package.error = Some("技能导入已取消，未自动重试。".into())
            }
            State::Failed(error) => self.skill_package.error = Some(error),
            State::WaitingForTerminal => {
                self.skill_package.error = Some("正在等待已取消的读取返回并释放资源。".into())
            }
            State::CommitReady { .. } | State::WorkspaceScanned { .. } => {}
        }
        if terminal {
            self.skill_package.pending = None;
        }
    }
    fn skill_package_ui(&mut self, ui: &mut egui::Ui, editable: bool) {
        if !self.skill_package.open {
            return;
        }
        use crate::skill_import::Command;
        let mut command = None;
        let mut cancel = None;
        egui::Frame::group(ui.style()).show(ui, |ui| {
            let idle = self.skill_package.pending.is_none();
            let source_editable = editable && idle && self.skill_package.review.is_none();
            ui.label(
                "选择 SKILL.md、技能文件夹或 ZIP 包；链接下载保留原文件，所有外部技能默认关闭。",
            );
            if ui
                .add_enabled(source_editable, egui::Button::new("读取未保存的候选"))
                .clicked()
            {
                command = Some(Command::Recover);
            }
            if ui
                .add_enabled(source_editable, egui::Button::new("重扫工作区技能"))
                .on_hover_text("读取工作区 skills/ 下的 .md 或技能目录，变更须重新审阅。")
                .clicked()
            {
                command = Some(Command::ScanWorkspace);
            }
            if self.skill_package.review.is_none() {
                let mut selected = None;
                for review in &self.skill_package.recovered {
                    ui.horizontal(|ui| {
                        ui.label(format!(
                            "{} · {} 份技能 · {} 个文件",
                            review.ticket,
                            review.skills.len(),
                            review.files
                        ));
                        if ui
                            .add_enabled(idle, egui::Button::new("审阅候选"))
                            .clicked()
                        {
                            selected = Some(review.clone());
                        }
                    });
                }
                if let Some(review) = selected {
                    self.skill_package.review = Some(review);
                    self.skill_package.document = None;
                }
            }
            ui.horizontal(|ui| {
                if ui
                    .add_enabled(
                        source_editable,
                        egui::Button::new("选择 Markdown / ZIP 文件"),
                    )
                    .clicked()
                {
                    command = Some(Command::PickFile);
                }
                if ui
                    .add_enabled(source_editable, egui::Button::new("选择技能文件夹"))
                    .clicked()
                {
                    command = Some(Command::PickTree);
                }
            });
            ui.add_enabled(
                source_editable,
                TextEdit::singleline(&mut self.skill_package.url)
                    .hint_text("HTTP 或 HTTPS 的 SKILL.md 链接"),
            );
            if ui
                .add_enabled(
                    source_editable && !self.skill_package.url.trim().is_empty(),
                    egui::Button::new("下载并检查"),
                )
                .clicked()
            {
                command = Some(Command::PreviewUrl(self.skill_package.url.clone()));
            }
            ui.add_enabled(
                source_editable,
                TextEdit::singleline(&mut self.skill_package.path)
                    .hint_text("设备绝对路径或工作区相对文件/文件夹"),
            );
            if ui
                .add_enabled(
                    source_editable && !self.skill_package.path.trim().is_empty(),
                    egui::Button::new("检查本地内容"),
                )
                .clicked()
            {
                command = Some(Command::PreviewPath(self.skill_package.path.clone().into()));
            }
            if let Some(review) = self.skill_package.review.as_ref() {
                ui.label(format!(
                    "{} 份技能 · {} 个文件 · {} 字节",
                    review.skills.len(),
                    review.files,
                    review.bytes
                ));
                for warning in &review.warnings {
                    ui.add(egui::Label::new(warning).wrap());
                }
                for skill in &review.skills {
                    ui.push_id(&skill.id, |ui| {
                        ui.heading(&skill.name);
                        ui.label(format!(
                            "{} · {} 字节 · {} 份资源",
                            skill.id, skill.document_bytes, skill.resources
                        ));
                        ui.add(egui::Label::new(&skill.description).wrap());
                        if ui
                            .add_enabled(idle, egui::Button::new("审阅原文（分页）"))
                            .clicked()
                        {
                            command = Some(Command::ReadDocument {
                                ticket: review.ticket.clone(),
                                id: skill.id.clone(),
                                offset: 0,
                            });
                        }
                        if let Some((id, offset, next, text)) = &self.skill_package.document {
                            if id == &skill.id {
                                ui.small(format!("原文位置 {offset}"));
                                egui::ScrollArea::vertical()
                                    .id_salt((&skill.id, "import-original"))
                                    .max_height(240.0)
                                    .show(ui, |ui| {
                                        ui.add(egui::Label::new(text).wrap().selectable(true));
                                    });
                                if let Some(offset) = next {
                                    if ui
                                        .add_enabled(idle, egui::Button::new("下一页原文"))
                                        .clicked()
                                    {
                                        command = Some(Command::ReadDocument {
                                            ticket: review.ticket.clone(),
                                            id: skill.id.clone(),
                                            offset: *offset,
                                        });
                                    }
                                }
                            }
                        }
                    });
                }
                ui.horizontal(|ui| {
                    if ui
                        .add_enabled(
                            editable && idle && !review.skills.is_empty(),
                            egui::Button::new("保存全部为未启用技能"),
                        )
                        .clicked()
                    {
                        command = Some(Command::Commit(review.ticket.clone()));
                    }
                    if ui
                        .add_enabled(idle, egui::Button::new("丢弃导入候选"))
                        .clicked()
                    {
                        command = Some(Command::Discard(review.ticket.clone()));
                    }
                });
            }
            if let Some(error) = &self.skill_package.error {
                ui.add(egui::Label::new(error).wrap());
            }
            if let Some(operation) = &self.skill_package.pending {
                ui.spinner();
                if ui.button("取消当前读取").clicked() {
                    cancel = Some(operation.clone());
                }
            }
            if idle && self.skill_package.review.is_none() && ui.button("关闭导入").clicked() {
                self.skill_package.open = false;
            }
        });
        if let Some(operation) = cancel {
            let _ = self.dispatch(Action::SkillImport(crate::skill_import::Request {
                operation,
                command: Command::Cancel,
            }));
        }
        if let Some(command) = command {
            let operation = key();
            if self.dispatch(Action::SkillImport(crate::skill_import::Request {
                operation: operation.clone(),
                command,
            })) {
                self.skill_package.pending = Some(operation);
                self.skill_package.error = None;
            }
        }
    }
}
