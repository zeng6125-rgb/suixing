use super::*;
use crate::{
    mcp::{ServerConfig, Status, TestReport},
    state::McpConfigSummary,
};
use std::collections::HashSet;

pub(super) struct McpEditor {
    config: ServerConfig,
    known_ids: HashSet<String>,
    env_configured: bool,
    headers_configured: bool,
    env_new: String,
    headers_new: String,
    env_clear: bool,
    headers_clear: bool,
    saving: bool,
    save_id: Option<String>,
    test_id: Option<String>,
    cancel_requested: bool,
    test_result: Option<Result<TestReport, String>>,
    error: Option<String>,
}
impl McpEditor {
    fn new(summary: Option<&McpConfigSummary>, configs: &[McpConfigSummary]) -> Self {
        Self {
            config: summary
                .map(|summary| summary.config.metadata())
                .unwrap_or_default(),
            known_ids: configs
                .iter()
                .map(|summary| summary.config.id.clone())
                .collect(),
            env_configured: summary.is_some_and(|summary| summary.env_configured),
            headers_configured: summary.is_some_and(|summary| summary.headers_configured),
            env_new: String::new(),
            headers_new: String::new(),
            env_clear: false,
            headers_clear: false,
            saving: false,
            save_id: None,
            test_id: None,
            cancel_requested: false,
            test_result: None,
            error: None,
        }
    }
    fn credentials_change(&self) -> (Option<String>, Option<String>) {
        let change = |clear: bool, text: &str| {
            if clear {
                Some(String::new())
            } else if text.is_empty() {
                None
            } else {
                Some(text.to_owned())
            }
        };
        (
            change(self.env_clear, &self.env_new),
            change(self.headers_clear, &self.headers_new),
        )
    }
    fn validate(&self) -> Result<(), String> {
        if self.env_new.len() > 64 * 1024 || self.headers_new.len() > 64 * 1024 {
            return Err("MCP 环境变量或请求头超过 64 KiB".into());
        }
        let mut config = self.config.metadata();
        let (env, headers) = self.credentials_change();
        config.env = env.unwrap_or_default();
        config.headers = headers.unwrap_or_default();
        crate::mcp::validate_config(&config)
    }
}
enum RowAction {
    Backend(Action),
    Edit(String),
    Delete(String),
}
enum EditAction {
    Save,
    Test,
    CancelTest,
    Close,
}

impl RustApplication {
    pub(super) fn mcp_saved(
        &mut self,
        request_id: Option<String>,
        id: String,
        result: Result<(), String>,
    ) {
        let Some(editor) = &mut self.mcp_editor else {
            return;
        };
        let matches = editor.config.id == id
            || editor.config.id.is_empty() && (id.is_empty() || !editor.known_ids.contains(&id));
        if !editor.saving || !matches || request_id.is_none() || request_id != editor.save_id {
            return;
        }
        editor.saving = false;
        editor.save_id = None;
        match result {
            Ok(()) => {
                self.mcp_editor = None;
                self.state.info("MCP 服务器配置已保存。启用的连接会更新。");
            }
            Err(error) => editor.error = Some(error),
        }
    }
    pub(super) fn mcp_deleted(&mut self, id: String, result: Result<(), String>) {
        if self.mcp_delete.as_deref() != Some(id.as_str()) {
            return;
        }
        self.mcp_delete_pending = false;
        if result.is_ok() {
            self.mcp_delete = None;
            self.state.info("MCP 服务器已删除，连接会关闭。");
        }
    }
    pub(super) fn mcp_test_finished(
        &mut self,
        request_id: String,
        result: Result<TestReport, String>,
    ) {
        if let Some(editor) = &mut self.mcp_editor
            && editor.test_id.as_deref() == Some(request_id.as_str())
        {
            editor.test_id = None;
            editor.cancel_requested = false;
            editor.test_result = Some(result);
        }
    }
    pub(super) fn mcp(&mut self, ui: &mut egui::Ui) {
        ui.heading("MCP");
        ui.add(
            egui::Label::new("通过 stdio 子进程或 HTTP 连接外部工具。实际工具调用仍需逐次审批。")
                .wrap(),
        );
        let available = self.runtime.is_some()
            && self.state.loaded
            && !self.state.mcp_pending
            && !self.mcp_delete_pending;
        let free = available && self.mcp_editor.is_none() && self.mcp_delete.is_none();
        ui.horizontal_wrapped(|ui| {
            if ui
                .add_enabled(free, egui::Button::new("添加服务器"))
                .clicked()
            {
                self.mcp_editor = Some(McpEditor::new(None, &self.state.mcp_configs));
            }
            if ui
                .add_enabled(free, egui::Button::new("重新加载配置"))
                .clicked()
                && self.dispatch(Action::LoadMcp)
            {
                self.state.mcp_pending = true;
            }
            if self.state.mcp_pending {
                ui.spinner();
            }
        });
        let editable = free && self.state.mcp_loaded && self.mcp_editor.is_none();
        egui::ScrollArea::vertical()
            .id_salt("mcp_page")
            .show(ui, |ui| {
                self.mcp_editor_ui(ui);
                self.mcp_delete_ui(ui);
                if !self.state.mcp_loaded {
                    ui.label("正在读取 MCP 配置。");
                } else if self.state.mcp_configs.is_empty() {
                    ui.label("暂无 MCP 服务器。添加一条启动命令或服务器地址即可配置。");
                }
                let mut action = None;
                for summary in &self.state.mcp_configs {
                    let server = self
                        .state
                        .mcp_snapshot
                        .servers
                        .iter()
                        .find(|server| server.config.id == summary.config.id);
                    ui.push_id(&summary.config.id, |ui| {
                        egui::Frame::group(ui.style()).show(ui, |ui| {
                            ui.horizontal_wrapped(|ui| {
                                ui.heading(&summary.config.name);
                                let mut enabled = summary.config.enabled;
                                if ui
                                    .add_enabled(
                                        editable,
                                        egui::Checkbox::new(&mut enabled, "启用"),
                                    )
                                    .changed()
                                {
                                    action = Some(RowAction::Backend(Action::SetMcpEnabled {
                                        id: summary.config.id.clone(),
                                        enabled,
                                    }));
                                }
                            });
                            ui.small(if summary.config.transport == "http" {
                                "HTTP"
                            } else {
                                "stdio · 应用子进程"
                            });
                            let target = if summary.config.transport == "http" {
                                &summary.config.url
                            } else {
                                &summary.config.command
                            };
                            ui.add(egui::Label::new(RichText::new(target).monospace()).wrap());
                            let status = server
                                .map(|server| server.status)
                                .unwrap_or(Status::Disconnected);
                            if let Some(server) = server {
                                let label = match status {
                                    Status::Ready => format!(
                                        "已就绪 · {} 个工具 · {} {}",
                                        server.tool_count,
                                        server.server_name,
                                        server.server_version
                                    ),
                                    Status::Reconnecting => {
                                        format!("正在重连（{}/5）", server.attempt)
                                    }
                                    Status::Disconnected if !summary.config.enabled => {
                                        "已停用".into()
                                    }
                                    _ => status.label().into(),
                                };
                                ui.add(egui::Label::new(label).wrap());
                                if let Some(error) = &server.error {
                                    ui.add(egui::Label::new(error).wrap());
                                }
                                if status == Status::Ready && !server.protocol_version.is_empty() {
                                    ui.small(format!("协议 {}", server.protocol_version));
                                }
                            } else {
                                ui.label(if summary.config.enabled {
                                    "等待读取连接状态"
                                } else {
                                    "已停用"
                                });
                            }
                            ui.horizontal_wrapped(|ui| {
                                if status == Status::Ready {
                                    if ui
                                        .add_enabled(editable, egui::Button::new("断开"))
                                        .clicked()
                                    {
                                        action = Some(RowAction::Backend(Action::McpDisconnect {
                                            id: summary.config.id.clone(),
                                        }));
                                    }
                                    if ui
                                        .add_enabled(editable, egui::Button::new("刷新工具"))
                                        .clicked()
                                    {
                                        action = Some(RowAction::Backend(Action::McpRefresh {
                                            id: summary.config.id.clone(),
                                        }));
                                    }
                                } else if status == Status::Connecting {
                                    ui.add_enabled(false, egui::Button::new("连接中"));
                                    if ui
                                        .add_enabled(editable, egui::Button::new("断开"))
                                        .clicked()
                                    {
                                        action = Some(RowAction::Backend(Action::McpDisconnect {
                                            id: summary.config.id.clone(),
                                        }));
                                    }
                                } else {
                                    let label = if matches!(
                                        status,
                                        Status::Failed | Status::Reconnecting
                                    ) {
                                        "重连"
                                    } else {
                                        "连接"
                                    };
                                    if ui.add_enabled(editable, egui::Button::new(label)).clicked()
                                    {
                                        action = Some(RowAction::Backend(Action::McpConnect {
                                            id: summary.config.id.clone(),
                                        }));
                                    }
                                    if status == Status::Reconnecting
                                        && ui
                                            .add_enabled(editable, egui::Button::new("停止重连"))
                                            .clicked()
                                    {
                                        action = Some(RowAction::Backend(Action::McpDisconnect {
                                            id: summary.config.id.clone(),
                                        }));
                                    }
                                }
                                if ui
                                    .add_enabled(editable, egui::Button::new("编辑"))
                                    .clicked()
                                {
                                    action = Some(RowAction::Edit(summary.config.id.clone()));
                                }
                                if ui
                                    .add_enabled(editable, egui::Button::new("删除"))
                                    .clicked()
                                {
                                    action = Some(RowAction::Delete(summary.config.id.clone()));
                                }
                            });
                            let tools = &self.state.mcp_snapshot.tools;
                            if status == Status::Ready {
                                egui::CollapsingHeader::new("查看已发现工具").show(ui, |ui| {
                                    for tool in tools
                                        .iter()
                                        .filter(|tool| tool.server_id == summary.config.id)
                                    {
                                        ui.add(
                                            egui::Label::new(
                                                RichText::new(&tool.registered_name).monospace(),
                                            )
                                            .wrap(),
                                        );
                                        if !tool.description.is_empty() {
                                            ui.add(egui::Label::new(&tool.description).wrap());
                                        }
                                    }
                                });
                            }
                        });
                    });
                }
                if let Some(action) = action {
                    match action {
                        RowAction::Backend(action) => {
                            if self.dispatch(action) {
                                self.state.mcp_pending = true;
                            }
                        }
                        RowAction::Edit(id) => {
                            let summary = self
                                .state
                                .mcp_configs
                                .iter()
                                .find(|summary| summary.config.id == id);
                            self.mcp_editor =
                                Some(McpEditor::new(summary, &self.state.mcp_configs));
                        }
                        RowAction::Delete(id) => {
                            self.mcp_delete = Some(id);
                            self.mcp_delete_pending = false;
                        }
                    }
                }
            });
    }

    fn mcp_editor_ui(&mut self, ui: &mut egui::Ui) {
        let Some(editor) = &mut self.mcp_editor else {
            return;
        };
        let editable = self.runtime.is_some()
            && self.state.loaded
            && !editor.saving
            && editor.test_id.is_none();
        let mut action = None;
        let mut changed = false;
        egui::Frame::group(ui.style()).show(ui,|ui|{
            ui.heading(if editor.config.id.is_empty(){"添加 MCP 服务器"}else{"编辑 MCP 服务器"});
            ui.horizontal_wrapped(|ui|{
                changed|=ui.add_enabled(editable,egui::RadioButton::new(editor.config.transport=="stdio","stdio")).clicked();
                if changed{editor.config.transport="stdio".into();}
                if ui.add_enabled(editable,egui::RadioButton::new(editor.config.transport=="http","HTTP")).clicked(){editor.config.transport="http".into();changed=true;}
            });
            ui.label("名称");
            changed|=ui.add_enabled(editable,TextEdit::singleline(&mut editor.config.name).char_limit(128).hint_text("服务器名称")).changed();
            if editor.config.transport=="http"{
                ui.label("服务器地址");
                changed|=ui.add_enabled(editable,TextEdit::singleline(&mut editor.config.url).char_limit(8192).hint_text("http://127.0.0.1:3000/mcp")).changed();
                credential_field(ui,editable,"请求头（每行 Name: value）",editor.headers_configured,&mut editor.headers_new,&mut editor.headers_clear,&mut changed);
            }else{
                ui.label("启动命令");
                changed|=ui.add_enabled(editable,TextEdit::multiline(&mut editor.config.command).desired_rows(2).desired_width(f32::INFINITY).char_limit(64*1024).hint_text("python /sdcard/mcp/server.py")).changed();
                ui.add(egui::Label::new("命令按引号和转义拆分为进程参数；管道、重定向等语法需要显式使用 shell 命令。").wrap());
                credential_field(ui,editable,"环境变量（每行 KEY=VALUE）",editor.env_configured,&mut editor.env_new,&mut editor.env_clear,&mut changed);
            }
            changed|=ui.add_enabled(editable,egui::Checkbox::new(&mut editor.config.enabled,"启用并自动连接")).changed();
            if changed{editor.test_result=None;editor.error=None;}
            if let Some(result)=&editor.test_result{match result{
                Ok(report)=>{
                    ui.add(egui::Label::new(format!("连接成功 · {} {} · {} 个工具",report.server_name,report.server_version,report.tools.len())).wrap());
                    ui.small(format!("协议 {}",report.protocol_version));
                    egui::CollapsingHeader::new("测试发现的工具").show(ui,|ui|{for name in &report.tools{ui.add(egui::Label::new(name).wrap());}});
                }
                Err(error)=>{ui.add(egui::Label::new(error).wrap());}
            }}
            if let Some(error)=&editor.error{ui.add(egui::Label::new(error).wrap());}
            if editor.saving{ui.horizontal(|ui|{ui.spinner();ui.label("正在保存配置");});}
            if editor.test_id.is_some(){ui.horizontal(|ui|{ui.spinner();ui.label(if editor.cancel_requested{"正在取消连接测试"}else{"正在测试草稿连接"});});}
            ui.horizontal_wrapped(|ui|{
                if ui.add_enabled(editable,egui::Button::new("保存")).clicked(){action=Some(EditAction::Save);}
                if ui.add_enabled(editable,egui::Button::new("测试草稿连接")).clicked(){action=Some(EditAction::Test);}
                if editor.test_id.is_some()&&ui.add_enabled(!editor.cancel_requested&&!editor.saving,egui::Button::new("取消连接测试")).clicked(){action=Some(EditAction::CancelTest);}
                if ui.add_enabled(!editor.saving,egui::Button::new("关闭编辑")).clicked(){action=Some(EditAction::Close);}
            });
        });
        let Some(action) = action else { return };
        match action {
            EditAction::Save | EditAction::Test => {
                if let Err(error) = editor.validate() {
                    editor.error = Some(error);
                    return;
                }
                let config = editor.config.metadata();
                let (env_change, headers_change) = editor.credentials_change();
                let testing = matches!(action, EditAction::Test);
                let request_id = crate::controller::unique_id();
                let command = if testing {
                    Action::McpTest {
                        request_id: request_id.clone(),
                        config,
                        env_change,
                        headers_change,
                    }
                } else {
                    Action::SaveMcpServer {
                        request_id: request_id.clone(),
                        config,
                        env_change,
                        headers_change,
                    }
                };
                if self.dispatch(command)
                    && let Some(editor) = &mut self.mcp_editor
                {
                    editor.error = None;
                    editor.test_result = None;
                    if testing {
                        editor.test_id = Some(request_id);
                        editor.cancel_requested = false;
                    } else {
                        editor.saving = true;
                        editor.save_id = Some(request_id);
                        self.state.mcp_pending = true;
                    }
                }
            }
            EditAction::CancelTest => {
                let Some(request_id) = editor.test_id.clone() else {
                    return;
                };
                if self.dispatch(Action::CancelMcpTest { request_id })
                    && let Some(editor) = &mut self.mcp_editor
                {
                    editor.cancel_requested = true;
                }
            }
            EditAction::Close => {
                let request = editor.test_id.clone().filter(|_| !editor.cancel_requested);
                if request.is_none()
                    || self.dispatch(Action::CancelMcpTest {
                        request_id: request.unwrap_or_default(),
                    })
                {
                    self.mcp_editor = None;
                }
            }
        }
    }

    fn mcp_delete_ui(&mut self, ui: &mut egui::Ui) {
        let Some(id) = self.mcp_delete.as_ref() else {
            return;
        };
        let id = id.clone();
        let name = self
            .state
            .mcp_configs
            .iter()
            .find(|summary| summary.config.id == id)
            .map(|summary| summary.config.name.as_str())
            .unwrap_or("这个服务器");
        let mut confirm = false;
        let mut cancel = false;
        egui::Frame::group(ui.style()).show(ui, |ui| {
            ui.heading("删除 MCP 服务器");
            ui.add(
                egui::Label::new(format!(
                    "确定删除「{name}」？对应连接将关闭，之后的调用无法继续使用它。"
                ))
                .wrap(),
            );
            ui.horizontal_wrapped(|ui| {
                confirm = ui
                    .add_enabled(!self.mcp_delete_pending, egui::Button::new("确认删除"))
                    .clicked();
                cancel = ui
                    .add_enabled(!self.mcp_delete_pending, egui::Button::new("取消"))
                    .clicked();
                if self.mcp_delete_pending {
                    ui.spinner();
                }
            });
        });
        if cancel {
            self.mcp_delete = None;
        } else if confirm && self.dispatch(Action::DeleteMcpServer { id }) {
            self.mcp_delete_pending = true;
            self.state.mcp_pending = true;
        }
    }
}

fn credential_field(
    ui: &mut egui::Ui,
    editable: bool,
    label: &str,
    configured: bool,
    new: &mut String,
    clear: &mut bool,
    changed: &mut bool,
) {
    ui.label(label);
    ui.small(if configured {
        "已填写，留空保留现有值"
    } else {
        "尚未填写，可留空"
    });
    *changed |= ui
        .add_enabled(
            editable && !*clear,
            TextEdit::multiline(new)
                .password(true)
                .desired_rows(3)
                .desired_width(f32::INFINITY)
                .char_limit(64 * 1024)
                .hint_text("填写新值"),
        )
        .changed();
    *changed |= ui
        .add_enabled(editable, egui::Checkbox::new(clear, "清除已填写值"))
        .changed();
    ui.add(egui::Label::new("本次填写的环境变量和请求头保存在应用内存，退出后需重新填写。").wrap());
}
