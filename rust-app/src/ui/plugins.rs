use super::*;
use crate::plugins::{
    PluginEntry, PluginEnvStatus, PluginManifest, Risk, Severity, registered_name,
};
use std::collections::BTreeMap;

const MAX_MANIFEST_BYTES: usize = 128 * 1024;

#[derive(Clone, Copy, PartialEq, Eq)]
enum ImportOperation {
    Preview,
    Install,
}

#[derive(Default)]
pub(super) struct PluginImport {
    source: String,
    preview: Option<PluginManifest>,
    error: Option<String>,
    pending: Option<ImportOperation>,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum EnvironmentOperation {
    Loading,
    Saving,
    Removing,
}

pub(super) struct PluginEnvironmentEditor {
    id: String,
    fields: Vec<PluginEnvStatus>,
    values: BTreeMap<String, String>,
    clear: BTreeMap<String, bool>,
    pending: Option<EnvironmentOperation>,
    remove: bool,
    wipe: bool,
    package_operation: Option<crate::platform::permissions::OperationKey>,
    error: Option<String>,
}

impl PluginEnvironmentEditor {
    fn new(id: String, remove: bool) -> Self {
        Self {
            id,
            fields: Vec::new(),
            values: BTreeMap::new(),
            clear: BTreeMap::new(),
            pending: (!remove).then_some(EnvironmentOperation::Loading),
            remove,
            wipe: false,
            package_operation: None,
            error: None,
        }
    }
}

enum EntryAction {
    Toggle(Action),
    Environment(String),
    Remove(String),
}

impl RustApplication {
    pub(super) fn plugin_environment_received(
        &mut self,
        id: String,
        result: Result<Vec<PluginEnvStatus>, String>,
    ) {
        if let Some(editor) = &mut self.plugin_environment_editor
            && editor.id == id
            && editor.pending == Some(EnvironmentOperation::Loading)
        {
            editor.pending = None;
            match result {
                Ok(fields) => editor.fields = fields,
                Err(error) => editor.error = Some(error),
            }
        }
    }

    pub(super) fn plugin_environment_saved(&mut self, id: String, result: Result<(), String>) {
        if let Some(editor) = &mut self.plugin_environment_editor
            && editor.id == id
            && editor.pending == Some(EnvironmentOperation::Saving)
        {
            editor.pending = None;
            match result {
                Ok(()) => {
                    self.plugin_environment_editor = None;
                    self.state.info("插件环境变量已更新，下一次运行生效。");
                }
                Err(error) => editor.error = Some(error),
            }
        }
    }

    pub(super) fn plugin_uninstalled(&mut self, id: String, result: Result<(), String>) {
        if let Some(editor) = &mut self.plugin_environment_editor
            && editor.id == id
            && editor.pending == Some(EnvironmentOperation::Removing)
        {
            editor.pending = None;
            match result {
                Ok(()) => {
                    self.plugin_environment_editor = None;
                    self.state.info("插件已移除。");
                }
                Err(error) => editor.error = Some(error),
            }
        }
    }

    pub(super) fn plugin_preview_received(&mut self, result: Result<PluginManifest, String>) {
        if let Some(draft) = &mut self.plugin_import
            && draft.pending == Some(ImportOperation::Preview)
        {
            draft.pending = None;
            match result {
                Ok(preview) => draft.preview = Some(preview),
                Err(error) => draft.error = Some(error),
            }
        }
    }

    pub(super) fn plugin_install_finished(&mut self, result: Result<(), String>) {
        if let Some(draft) = &mut self.plugin_import
            && draft.pending == Some(ImportOperation::Install)
        {
            draft.pending = None;
            match result {
                Ok(()) => {
                    self.plugin_import = None;
                    self.state
                        .info("插件已安装为停用状态。可在列表中审阅并启用。");
                }
                Err(error) => draft.error = Some(error),
            }
        }
    }

    pub(super) fn plugins(&mut self, ui: &mut egui::Ui) {
        ui.heading("插件");
        ui.add(egui::Label::new("安装的插件默认停用。启用或停用从下一次运行起生效。").wrap());
        let editable = self.runtime.is_some()
            && self.state.loaded
            && !self.state.plugins_pending
            && self
                .plugin_import
                .as_ref()
                .is_none_or(|draft| draft.pending.is_none())
            && self.plugin_environment_editor.is_none();
        ui.horizontal_wrapped(|ui| {
            if ui
                .add_enabled(
                    editable && self.package_import.pending.is_none(),
                    egui::Button::new("导入插件包"),
                )
                .clicked()
            {
                self.package_import.open = true;
            }
            if ui
                .add_enabled(
                    editable && self.plugin_import.is_none(),
                    egui::Button::new("导入插件清单"),
                )
                .clicked()
            {
                self.plugin_import = Some(PluginImport::default());
            }
            if ui
                .add_enabled(editable, egui::Button::new("刷新列表"))
                .clicked()
                && self.dispatch(Action::LoadPlugins)
            {
                self.state.plugins_pending = true;
            }
            if self.state.plugins_pending {
                ui.spinner();
            }
        });
        let editable = editable && !self.state.plugins_pending;
        egui::ScrollArea::vertical()
            .id_salt("plugins")
            .show(ui, |ui| {
                self.package_import_ui(ui, editable);
                self.plugin_import_ui(ui, editable);
                self.plugin_environment_ui(ui);
                ui.separator();
                if !self.state.plugins_loaded {
                    ui.label("等待读取插件列表。");
                } else if self.state.plugins.is_empty() {
                    ui.label("暂无插件。可粘贴插件清单进行导入。");
                }
                let mut action = None;
                for entry in &self.state.plugins {
                    ui.push_id(&entry.id, |ui| {
                        if let Some(toggle) = plugin_entry(ui, entry, editable) {
                            action = Some(toggle);
                        }
                    });
                }
                if let Some(action) = action {
                    match action {
                        EntryAction::Toggle(action) => {
                            if self.dispatch(action) {
                                self.state.plugins_pending = true;
                            }
                        }
                        EntryAction::Environment(id) => {
                            if self.dispatch(Action::LoadPluginEnvironment { id: id.clone() }) {
                                self.plugin_environment_editor =
                                    Some(PluginEnvironmentEditor::new(id, false));
                            }
                        }
                        EntryAction::Remove(id) => {
                            self.plugin_environment_editor =
                                Some(PluginEnvironmentEditor::new(id, true));
                        }
                    }
                }
            });
    }

    fn plugin_import_ui(&mut self, ui: &mut egui::Ui, editable: bool) {
        let Some(draft) = &mut self.plugin_import else {
            return;
        };
        let mut request = None;
        let mut close = false;
        let mut edit = false;
        egui::Frame::group(ui.style()).show(ui, |ui| {
            if let Some(preview) = &draft.preview {
                ui.heading("审阅待安装插件");
                plugin_manifest_review(ui, preview);
                ui.horizontal_wrapped(|ui| {
                    if ui
                        .add_enabled(editable, egui::Button::new("安装为停用插件"))
                        .clicked()
                    {
                        request = Some((
                            ImportOperation::Install,
                            Action::InstallPlugin {
                                manifest: draft.source.clone(),
                            },
                        ));
                    }
                    edit = ui
                        .add_enabled(editable, egui::Button::new("返回编辑"))
                        .clicked();
                });
            } else {
                ui.heading("导入插件清单");
                if ui
                    .add_enabled(
                        editable,
                        TextEdit::multiline(&mut draft.source)
                            .desired_rows(8)
                            .desired_width(f32::INFINITY)
                            .char_limit(MAX_MANIFEST_BYTES)
                            .hint_text("粘贴 plugin.json 或 manifest.json 内容"),
                    )
                    .changed()
                {
                    draft.error = None;
                }
                if draft.source.len() > MAX_MANIFEST_BYTES {
                    ui.label("清单超过 128 KiB。");
                }
                if ui
                    .add_enabled(
                        editable
                            && draft.source.len() <= MAX_MANIFEST_BYTES
                            && !draft.source.trim().is_empty(),
                        egui::Button::new("校验并审阅"),
                    )
                    .clicked()
                {
                    request = Some((
                        ImportOperation::Preview,
                        Action::PreviewPlugin {
                            manifest: draft.source.clone(),
                        },
                    ));
                }
            }
            if let Some(error) = &draft.error {
                ui.add(egui::Label::new(error).wrap());
            }
            ui.horizontal_wrapped(|ui| {
                close = ui
                    .add_enabled(editable, egui::Button::new("取消导入"))
                    .clicked();
                if draft.pending.is_some() {
                    ui.spinner();
                }
            });
        });
        if edit {
            draft.preview = None;
            draft.error = None;
        }
        if close {
            self.plugin_import = None;
        } else if let Some((operation, action)) = request
            && self.dispatch(action)
            && let Some(draft) = &mut self.plugin_import
        {
            draft.error = None;
            draft.pending = Some(operation);
        }
    }

    fn plugin_environment_ui(&mut self, ui: &mut egui::Ui) {
        let Some(editor) = &mut self.plugin_environment_editor else {
            return;
        };
        let mut close = false;
        let mut action = None;
        let editable = editor.pending.is_none() && !self.state.plugins_pending;
        egui::Frame::group(ui.style()).show(ui, |ui| {
            ui.heading(if editor.remove {
                "移除插件"
            } else {
                "插件环境变量"
            });
            ui.label(&editor.id);
            if editor.remove {
                ui.label("移除后插件进入回收目录保留七天。已经开始的命令由原运行管理。");
                ui.add_enabled(
                    editable,
                    egui::Checkbox::new(&mut editor.wipe, "永久删除插件文件与环境变量"),
                );
                if ui
                    .add_enabled(editable, egui::Button::new("确认移除"))
                    .clicked()
                {
                    action = Some((
                        EnvironmentOperation::Removing,
                        Action::PluginAssets(crate::plugin_assets::runtime::Request {
                            operation: package_key(),
                            command: crate::plugin_assets::runtime::Command::Remove {
                                id: editor.id.clone(),
                                wipe: editor.wipe,
                            },
                        }),
                    ));
                }
            } else {
                ui.add(
                    egui::Label::new(
                        "已保存值只显示设置状态。留空保留原值；填写新值或选择清除后保存。",
                    )
                    .wrap(),
                );
                if editor.pending == Some(EnvironmentOperation::Loading) {
                    ui.label("正在读取设置状态。");
                } else if editor.fields.is_empty() && editor.error.is_none() {
                    ui.label("这个插件没有声明环境变量。");
                }
                for field in &editor.fields {
                    ui.push_id(&field.name, |ui| {
                        ui.label(format!(
                            "{} · {} · {}",
                            field.name,
                            if field.required { "必填" } else { "可选" },
                            if field.unavailable {
                                "已保存但无法读取，请重新填写"
                            } else if field.uses_default {
                                "使用清单默认值"
                            } else if field.configured {
                                "已设置"
                            } else {
                                "未设置"
                            }
                        ));
                        if let Some(description) = &field.description {
                            ui.add(egui::Label::new(description).wrap());
                        }
                        let cleared = editor.clear.entry(field.name.clone()).or_default();
                        let value = editor.values.entry(field.name.clone()).or_default();
                        ui.add_enabled(
                            editable && !*cleared,
                            TextEdit::singleline(value)
                                .password(field.secret)
                                .char_limit(8192)
                                .hint_text("新的值"),
                        );
                        ui.add_enabled(editable, egui::Checkbox::new(cleared, "清除已填值"));
                        if field.secret {
                            ui.small("本次填写的敏感值仅保存在应用内存，退出后需重新填写。");
                        }
                    });
                }
                let changed = |field: &PluginEnvStatus| {
                    editor.clear.get(&field.name) == Some(&true)
                        || editor
                            .values
                            .get(&field.name)
                            .is_some_and(|value| !value.is_empty())
                };
                let has_changes = editor.fields.iter().any(changed);
                let bytes: usize = editor
                    .values
                    .iter()
                    .filter(|(name, _)| editor.clear.get(*name) != Some(&true))
                    .map(|(_, value)| value.len())
                    .sum();
                if bytes > 64 * 1024 {
                    ui.label("环境变量合计超过 64 KiB。");
                }
                if ui
                    .add_enabled(
                        editable && has_changes && bytes <= 64 * 1024,
                        egui::Button::new("保存环境变量"),
                    )
                    .clicked()
                {
                    let changes: BTreeMap<_, _> = editor
                        .fields
                        .iter()
                        .filter_map(|field| {
                            if editor.clear.get(&field.name) == Some(&true) {
                                Some((field.name.clone(), None))
                            } else {
                                editor
                                    .values
                                    .get(&field.name)
                                    .filter(|value| !value.is_empty())
                                    .map(|value| (field.name.clone(), Some(value.clone())))
                            }
                        })
                        .collect();
                    action = Some((
                        EnvironmentOperation::Saving,
                        Action::SavePluginEnvironment {
                            id: editor.id.clone(),
                            changes,
                        },
                    ));
                }
            }
            if let Some(error) = &editor.error {
                ui.add(egui::Label::new(error).wrap());
            }
            if editor.pending.is_some() {
                ui.spinner();
            }
            close = ui
                .add_enabled(editable, egui::Button::new("取消"))
                .clicked();
        });
        if close {
            self.plugin_environment_editor = None;
        } else if let Some((operation, action)) = action {
            let package_operation = if let Action::PluginAssets(request) = &action {
                Some(request.operation.clone())
            } else {
                None
            };
            if self.dispatch(action)
                && let Some(editor) = &mut self.plugin_environment_editor
            {
                editor.error = None;
                editor.pending = Some(operation);
                editor.package_operation = package_operation;
            }
        }
    }
}

fn plugin_entry(ui: &mut egui::Ui, entry: &PluginEntry, editable: bool) -> Option<EntryAction> {
    let valid = entry.is_valid();
    let enabled = entry.state.enabled;
    let name = entry
        .manifest
        .as_ref()
        .map(|manifest| manifest.display_name.zh.as_str())
        .filter(|name| !name.trim().is_empty())
        .unwrap_or(&entry.id);
    let mut action = None;
    egui::Frame::group(ui.style()).show(ui, |ui| {
        ui.heading(name);
        ui.label(match (valid, enabled) {
            (false, _) => "校验失败",
            (true, true) => "已启用",
            (true, false) => "已停用",
        });
        ui.small(format!("插件 ID {}", entry.id));
        if let Some(level) = entry.level {
            ui.small(format!("能力等级 {level:?}"));
        }
        for issue in &entry.issues {
            let level = match issue.severity {
                Severity::Error => "错误",
                Severity::Warning => "提示",
            };
            ui.add(egui::Label::new(format!("{level} · {}", issue.message)).wrap());
        }
        if valid
            && enabled
            && ui
                .add_enabled(editable, egui::Button::new("停用插件"))
                .clicked()
        {
            action = Some(EntryAction::Toggle(Action::SetPluginEnabled {
                id: entry.id.clone(),
                enabled: false,
            }));
        }
        if let Some(manifest) = &entry.manifest {
            egui::CollapsingHeader::new("审阅插件能力").show(ui, |ui| {
                plugin_manifest_review(ui, manifest);
                if valid
                    && !enabled
                    && ui
                        .add_enabled(editable, egui::Button::new("启用插件"))
                        .clicked()
                {
                    action = Some(EntryAction::Toggle(Action::SetPluginEnabled {
                        id: entry.id.clone(),
                        enabled: true,
                    }));
                }
            });
        }
        ui.horizontal_wrapped(|ui| {
            if valid
                && entry
                    .manifest
                    .as_ref()
                    .is_some_and(|manifest| !manifest.env.is_empty())
                && ui
                    .add_enabled(editable, egui::Button::new("环境变量"))
                    .clicked()
            {
                action = Some(EntryAction::Environment(entry.id.clone()));
            }
            if ui
                .add_enabled(editable, egui::Button::new("移除插件"))
                .clicked()
            {
                action = Some(EntryAction::Remove(entry.id.clone()));
            }
        });
    });
    action
}

fn plugin_manifest_review(ui: &mut egui::Ui, manifest: &PluginManifest) {
    ui.add(egui::Label::new(RichText::new(&manifest.display_name.zh).strong()).wrap());
    ui.small(format!("{} · 版本 {}", manifest.id, manifest.version));
    ui.add(egui::Label::new(&manifest.description.zh).wrap());
    egui::CollapsingHeader::new(format!("工具（{}）", manifest.tools.len()))
        .default_open(true)
        .show(ui, |ui| {
            for tool in &manifest.tools {
                let risk = match tool.risk() {
                    Risk::Low => "低风险",
                    Risk::Medium => "中风险",
                    Risk::High => "高风险",
                };
                ui.add(
                    egui::Label::new(format!(
                        "{} · {risk}",
                        registered_name(&manifest.id, &tool.name)
                    ))
                    .wrap(),
                );
                ui.add(egui::Label::new(&tool.description).wrap());
                let exec = &tool.exec;
                match exec.kind.as_str() {
                    "http" => {
                        let method = exec.method.as_deref().unwrap_or("GET");
                        let origin = exec
                            .url
                            .as_deref()
                            .and_then(|url| reqwest::Url::parse(url).ok())
                            .filter(|url| url.has_host())
                            .map_or_else(
                                || "模板地址".into(),
                                |url| url.origin().ascii_serialization(),
                            );
                        ui.add(egui::Label::new(format!("HTTP {method} · {origin}")).wrap());
                    }
                    "shell" => {
                        let shell = exec.shell.as_deref().unwrap_or("auto");
                        let privilege = if exec.needs_root {
                            "root"
                        } else {
                            "应用权限"
                        };
                        ui.add(egui::Label::new(format!("Shell {shell} · {privilege}")).wrap());
                    }
                    _ => {
                        ui.label("执行类型不可用");
                    }
                }
                ui.small(format!("超时 {} 秒", exec.timeout_seconds));
                egui::CollapsingHeader::new("查看完整执行模板")
                    .id_salt((&manifest.id, &tool.name))
                    .show(ui, |ui| {
                        for (label, template) in [
                            ("URL", exec.url.as_deref()),
                            ("命令", exec.command.as_deref()),
                            ("工作目录", exec.cwd.as_deref()),
                            ("请求正文", exec.body.as_deref()),
                        ] {
                            if let Some(template) = template {
                                ui.label(label);
                                ui.add(
                                    egui::Label::new(RichText::new(template).monospace()).wrap(),
                                );
                            }
                        }
                        for (name, value) in &exec.headers {
                            ui.add(
                                egui::Label::new(
                                    RichText::new(format!("{name}: {value}")).monospace(),
                                )
                                .wrap(),
                            );
                        }
                        if let Some(parameters) = &tool.parameters {
                            ui.label("参数定义");
                            ui.add(
                                egui::Label::new(
                                    RichText::new(
                                        serde_json::Value::Object(parameters.clone()).to_string(),
                                    )
                                    .monospace(),
                                )
                                .wrap(),
                            );
                        }
                        if let Some(response) = &exec.response {
                            ui.small(format!(
                                "输出格式 {} · 最多 {} 字符",
                                response.format, response.max_chars
                            ));
                            if let Some(pick) = &response.pick {
                                ui.add(egui::Label::new(format!("JSON 选取路径 {pick}")).wrap());
                            }
                        }
                    });
            }
        });
    if !manifest.hooks.is_empty() {
        egui::CollapsingHeader::new(format!("钩子（{}）", manifest.hooks.len())).show(ui, |ui| {
            for hook in &manifest.hooks {
                ui.add(egui::Label::new(format!("{} · {}", hook.on, hook.action)).wrap());
                if let Some(tool) = &hook.r#when.tool {
                    ui.add(egui::Label::new(format!("匹配工具 {tool}")).wrap());
                }
                if hook.r#when.regex.is_some() {
                    ui.label("按参数正则匹配");
                }
                if let Some(risk) = &hook.r#when.min_risk {
                    ui.add(egui::Label::new(format!("最低风险 {risk}")).wrap());
                }
            }
        });
    }
    if !manifest.context.is_empty() {
        egui::CollapsingHeader::new(format!("上下文（{}）", manifest.context.len())).show(
            ui,
            |ui| {
                for context in &manifest.context {
                    ui.add(
                        egui::Label::new(format!(
                            "{} · {} 字符",
                            context.source,
                            context.content.chars().count()
                        ))
                        .wrap(),
                    );
                    if let Some(battery) = context.r#when.as_ref().and_then(|rule| rule.min_battery)
                    {
                        ui.small(format!("最低电量 {battery}%"));
                    }
                }
            },
        );
    }
    if !manifest.deps.is_empty() {
        egui::CollapsingHeader::new(format!("依赖（{}）", manifest.deps.len())).show(ui, |ui| {
            for dependency in &manifest.deps {
                ui.add(egui::Label::new(&dependency.id).wrap());
                ui.add(egui::Label::new(format!("SHA-256 {}", dependency.sha256)).wrap());
                if let Some(bytes) = dependency.size_bytes {
                    ui.small(format!("{bytes} 字节"));
                }
            }
        });
    }
    if !manifest.env.is_empty() {
        egui::CollapsingHeader::new(format!("环境变量（{}）", manifest.env.len())).show(ui, |ui| {
            for variable in &manifest.env {
                let required = if variable.required {
                    "必填"
                } else {
                    "可选"
                };
                ui.add(egui::Label::new(format!("{} · {required}", variable.name)).wrap());
            }
        });
    }
}

#[derive(Default)]
pub(super) struct PackageImport {
    open: bool,
    source: String,
    pending: Option<crate::platform::permissions::OperationKey>,
    review: Option<crate::plugin_assets::runtime::Review>,
    review_operation: Option<crate::platform::permissions::OperationKey>,
    error: Option<String>,
}
fn package_key() -> crate::platform::permissions::OperationKey {
    crate::platform::permissions::OperationKey {
        id: crate::controller::unique_id(),
        conversation_id: None,
        generation: crate::controller::now_millis().max(1) as u64,
    }
}
impl RustApplication {
    pub(super) fn plugin_assets_received(&mut self, update: crate::plugin_assets::runtime::Update) {
        use crate::plugin_assets::runtime::State;
        if let Some(editor) = self.plugin_environment_editor.as_mut() {
            if editor.package_operation.as_ref() == Some(&update.operation) {
                match update.state {
                    State::WaitingForTerminal(reason) => editor.error = Some(reason),
                    State::Removed(_) => {
                        self.plugin_environment_editor = None;
                        self.state.plugins_pending = false;
                    }
                    State::Failed(error) | State::Uncertain { message: error, .. } => {
                        editor.pending = None;
                        editor.package_operation = None;
                        editor.error = Some(error);
                    }
                    State::Cancelled => {
                        editor.pending = None;
                        editor.package_operation = None;
                    }
                    _ => {}
                }
                return;
            }
        }
        if self.package_import.pending.as_ref() != Some(&update.operation) {
            if self.package_import.review_operation.as_ref() == Some(&update.operation) {
                if let State::Failed(error) = update.state {
                    self.package_import.review = None;
                    self.package_import.review_operation = None;
                    self.package_import.error = Some(error);
                }
            }
            return;
        }
        match update.state {
            State::WaitingForTerminal(reason) => self.package_import.error = Some(reason),
            State::Prepared(review) => {
                self.package_import.pending = None;
                self.package_import.review = Some(review);
                self.package_import.review_operation = Some(update.operation);
                self.package_import.error = None;
            }
            State::Installed(id) => {
                self.package_import.pending = None;
                self.package_import.review = None;
                self.package_import.review_operation = None;
                self.package_import.error =
                    Some(format!("{id} 已安装，仍处于停用状态；可在能力页启用。"));
            }
            State::Cancelled => {
                self.package_import.pending = None;
                self.package_import.review = None;
                self.package_import.review_operation = None;
                self.package_import.error = Some("已取消插件包操作。".into());
            }
            State::Failed(error) | State::Uncertain { message: error, .. } => {
                self.package_import.pending = None;
                self.package_import.review = None;
                self.package_import.review_operation = None;
                self.package_import.error = Some(error);
            }
            State::Removed(_) => {}
        }
    }
    fn package_import_ui(&mut self, ui: &mut egui::Ui, editable: bool) {
        if !self.package_import.open {
            return;
        }
        let mut command = None;
        egui::Frame::group(ui.style()).show(ui, |ui| {
            ui.heading("导入插件包");
            ui.label("选择 .sapkg 或 ZIP，检查后审阅清单和文件，再确认安装。安装不会运行脚本或启用插件。");
            let idle = self.package_import.pending.is_none();
            if ui.add_enabled(editable && idle && self.package_import.review.is_none(), egui::Button::new("选择设备上的插件包")).clicked() { command = Some(crate::plugin_assets::runtime::Command::Pick); }
            ui.add_enabled(editable && idle, TextEdit::singleline(&mut self.package_import.source).hint_text("插件包的绝对路径"));
            if ui.add_enabled(editable && idle && self.package_import.review.is_none() && !self.package_import.source.is_empty(), egui::Button::new("检查本地插件包")).clicked() { command = Some(crate::plugin_assets::runtime::Command::PreviewPath(PathBuf::from(&self.package_import.source))); }
            if let Some(review) = self.package_import.review.as_ref() {
                ui.label(format!("{} · {}个文件 · {}个目录 · {}字节", review.id, review.files, review.directories, review.bytes));
                plugin_manifest_review(ui, &review.manifest);
                for warning in &review.warnings { ui.add(egui::Label::new(warning).wrap()); }
                if ui.add_enabled(editable && idle, egui::Button::new("确认安装并保持停用")).clicked() { command = Some(crate::plugin_assets::runtime::Command::Install(review.ticket.clone())); }
                if ui.add_enabled(editable && idle, egui::Button::new("取消候选并回收文件")).clicked() { command = Some(crate::plugin_assets::runtime::Command::Discard(review.ticket.clone())); }
            }
            if let Some(error) = self.package_import.error.as_ref() { ui.add(egui::Label::new(error).wrap()); }
            if let Some(operation) = self.package_import.pending.as_ref() {
                ui.spinner();
                if ui.button("请求取消").clicked() { let request = crate::plugin_assets::runtime::Request { operation: operation.clone(), command: crate::plugin_assets::runtime::Command::Cancel }; self.dispatch(Action::PluginAssets(request)); }
            }
        });
        if let Some(command) = command {
            let key = package_key();
            let request = crate::plugin_assets::runtime::Request {
                operation: key.clone(),
                command,
            };
            if self.dispatch(Action::PluginAssets(request)) {
                self.package_import.pending = Some(key);
                self.package_import.error = None;
            }
        }
    }
}
