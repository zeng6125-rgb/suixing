use super::*;
use crate::{
    chat::Mutation,
    conversation_manager::{Info, Request, Update},
};

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
enum Filter {
    #[default]
    Active,
    Pinned,
    Archived,
}
#[derive(Default)]
pub(super) struct View {
    query: String,
    filter: Filter,
    cached_query: String,
    cached_filter: Option<Filter>,
    indices: Vec<usize>,
    active_order: Option<Vec<usize>>,
    archived_order: Option<Vec<usize>>,
    search_titles: Option<Vec<String>>,
    pending: Option<String>,
    rename: Option<(String, String)>,
    info_id: Option<String>,
    info_request: Option<String>,
    info: Option<Result<Info, String>>,
}
impl View {
    pub(super) fn reset(&mut self) {
        self.pending = None;
        self.info_id = None;
        self.info_request = None;
        self.info = None;
        self.invalidate();
    }
    pub(super) fn invalidate(&mut self) {
        self.active_order = None;
        self.archived_order = None;
        self.search_titles = None;
        self.cached_filter = None;
    }
    pub(super) fn invalidate_order(&mut self) {
        self.active_order = None;
        self.cached_filter = None;
    }
    pub(super) fn active_order(&mut self, state: &ViewState) -> &[usize] {
        self.active_order.get_or_insert_with(|| {
            state
                .conversation_preferences
                .order
                .indices(&state.conversations)
        })
    }
    pub(super) fn apply(&mut self, update: &Update) {
        match update {
            Update::Ready { request_id, info }
                if self.info_request.as_ref() == Some(request_id)
                    && self.info_id.as_deref() == Some(&info.conversation_id) =>
            {
                self.info = Some(Ok(info.clone()));
                self.info_request = None;
            }
            Update::Failed { request_id, error }
                if self.info_request.as_ref() == Some(request_id) =>
            {
                self.info = Some(Err(error.clone()));
                self.info_request = None;
            }
            _ => {}
        }
    }
    pub(super) fn mutation_finished(&mut self, id: &str, result: &Result<(), String>) {
        if self.pending.as_deref() == Some(id) {
            self.pending = None;
            if result.is_ok() {
                self.rename = None;
            }
        }
    }
    fn refresh(&mut self, state: &ViewState) {
        if self.cached_query == self.query && self.cached_filter == Some(self.filter) {
            return;
        }
        let conversations = &state.conversations;
        let query = self.query.trim().to_lowercase();
        let order = if self.filter == Filter::Archived {
            self.archived_order.get_or_insert_with(|| {
                let mut indices: Vec<_> = conversations
                    .iter()
                    .enumerate()
                    .filter_map(|(index, item)| item.archived.then_some(index))
                    .collect();
                indices.sort_unstable_by(|&a, &b| {
                    conversations[b]
                        .updated_at
                        .cmp(&conversations[a].updated_at)
                        .then_with(|| conversations[a].id.cmp(&conversations[b].id))
                });
                indices
            })
        } else {
            self.active_order
                .get_or_insert_with(|| state.conversation_preferences.order.indices(conversations))
        };
        let titles = (!query.is_empty()).then(|| {
            self.search_titles.get_or_insert_with(|| {
                conversations
                    .iter()
                    .map(|item| item.title.to_lowercase())
                    .collect()
            })
        });
        self.indices.clear();
        self.indices.extend(order.iter().copied().filter(|&index| {
            let item = &conversations[index];
            (self.filter != Filter::Pinned || item.pinned)
                && titles
                    .as_ref()
                    .is_none_or(|titles| titles[index].contains(&query) || item.id.contains(&query))
        }));
        self.cached_query.clone_from(&self.query);
        self.cached_filter = Some(self.filter);
    }
}
fn bytes_label(bytes: u64) -> String {
    if bytes >= 1024 * 1024 {
        format!("{:.2} MiB", bytes as f64 / (1024.0 * 1024.0))
    } else if bytes >= 1024 {
        format!("{:.1} KiB", bytes as f64 / 1024.0)
    } else {
        format!("{bytes} 字节")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::display::ConversationSummary;

    #[test]
    fn searches_and_filters_reuse_drawer_order_across_large_conversation_lists() {
        let mut state = ViewState::new(Default::default());
        state.conversations = (0..8192)
            .map(|index| ConversationSummary {
                id: format!("chat-{index:04}"),
                title: match index {
                    1 | 2 | 3 | 4 | 5 => "会话 BETA".into(),
                    _ => "一般会话".into(),
                },
                updated_at: if index == 5 { 4 } else { index },
                assistant_id: None,
                provider_id: None,
                model: None,
                approval_mode: None,
                pinned: matches!(index, 1 | 2),
                archived: matches!(index, 4 | 5),
                checkpoint_count: 0,
            })
            .collect();
        state.conversation_preferences.order.pinned = vec!["chat-0001".into()];
        let mut view = View::default();
        assert_eq!(&view.active_order(&state)[..2], &[1, 2]);
        view.refresh(&state);
        assert_eq!(view.indices.len(), 8190);
        assert!(view.search_titles.is_none());
        assert!(view.archived_order.is_none());

        view.query = " BETA ".into();
        view.refresh(&state);
        assert_eq!(view.indices, [1, 2, 3]);
        view.filter = Filter::Pinned;
        view.refresh(&state);
        assert_eq!(view.indices, [1, 2]);

        state.conversation_preferences.order.pinned = vec!["chat-0002".into()];
        view.invalidate_order();
        view.refresh(&state);
        assert_eq!(view.indices, [2, 1]);
        assert_eq!(&view.active_order(&state)[..2], &[2, 1]);

        view.filter = Filter::Archived;
        view.refresh(&state);
        assert_eq!(view.indices, [4, 5]);
        view.query = "CHAT-0005".into();
        view.refresh(&state);
        assert_eq!(view.indices, [5]);

        state.conversations[4].title = "新的 Gamma".into();
        state.conversations.remove(5);
        view.invalidate();
        view.refresh(&state);
        assert!(view.indices.is_empty());
        view.query = "GAMMA".into();
        view.refresh(&state);
        assert_eq!(view.indices, [4]);
    }
}

impl RustApplication {
    fn conversation_info_begin(&mut self, id: String) {
        if let Some(request_id) = self.conversation_manager.info_request.take() {
            self.dispatch(Action::ConversationInfo(Request::Cancel { request_id }));
        }
        self.conversation_manager.info = None;
        self.conversation_manager.info_id = Some(id.clone());
        let request_id = crate::controller::unique_id();
        if self.dispatch(Action::ConversationInfo(Request::Open {
            request_id: request_id.clone(),
            conversation_id: id,
        })) {
            self.conversation_manager.info_request = Some(request_id);
        } else {
            self.conversation_manager.info = Some(Err("会话详情请求未受理，请重试".into()));
        }
    }
    pub(super) fn conversation_info_close(&mut self) {
        if let Some(request_id) = self.conversation_manager.info_request.take() {
            self.dispatch(Action::ConversationInfo(Request::Cancel { request_id }));
        }
        self.conversation_manager.info_id = None;
        self.conversation_manager.info = None;
    }
    pub(super) fn conversations_page(&mut self, ui: &mut egui::Ui) {
        ui.heading("会话管理");
        ui.horizontal(|ui| {
            ui.add(
                TextEdit::singleline(&mut self.conversation_manager.query)
                    .hint_text("搜索标题或会话ID")
                    .char_limit(1024)
                    .desired_width(220.0),
            );
            if ui.button("清除").clicked() {
                self.conversation_manager.query.clear();
            }
        });
        ui.horizontal_wrapped(|ui| {
            for (filter, label) in [
                (Filter::Active, "活动会话"),
                (Filter::Pinned, "已置顶"),
                (Filter::Archived, "已归档"),
            ] {
                ui.selectable_value(&mut self.conversation_manager.filter, filter, label);
            }
        });
        self.conversation_manager.refresh(&self.state);
        ui.label(format!(
            "{} 个会话",
            self.conversation_manager.indices.len()
        ));
        if let Some(id) = self.conversation_delete.clone() {
            if let Some(conversation) = self
                .state
                .conversations
                .iter()
                .find(|item| item.id == id)
                .cloned()
            {
                ui.group(|ui| {
                    ui.label(format!("删除「{}」？", conversation.title));
                    ui.label("聊天记录将永久删除，用量记录和工作区文件保留。");
                    let allowed = self.can_delete_conversation(&id);
                    ui.horizontal(|ui| {
                        if ui
                            .add_enabled(allowed, egui::Button::new("确认删除"))
                            .clicked()
                        {
                            self.navigate(Navigation::DeleteConversation(id.clone()), false);
                        }
                        if ui
                            .add_enabled(
                                self.state.conversation_delete_pending.is_none(),
                                egui::Button::new("保留"),
                            )
                            .clicked()
                        {
                            self.conversation_delete = None;
                        }
                    });
                });
            }
        }
        let mut mutation = None;
        let mut open = None;
        let mut export = None;
        let mut info = None;
        if let Some((id, title)) = &mut self.conversation_manager.rename {
            ui.group(|ui| {
                ui.label("重命名会话");
                ui.add(
                    TextEdit::singleline(title)
                        .char_limit(1024)
                        .desired_width(f32::INFINITY),
                );
                let ready = self.conversation_manager.pending.is_none()
                    && !self.state.runs.contains_key(id)
                    && !title.trim().is_empty();
                if ui
                    .add_enabled(ready, egui::Button::new("保存标题"))
                    .clicked()
                {
                    mutation = Some((id.clone(), Mutation::Rename(title.clone())));
                }
            });
            if ui
                .add_enabled(
                    self.conversation_manager.pending.is_none(),
                    egui::Button::new("取消重命名"),
                )
                .clicked()
            {
                self.conversation_manager.rename = None;
            }
        }
        if self.conversation_manager.pending.is_some() {
            ui.spinner();
        }
        if self.conversation_manager.indices.is_empty() {
            ui.label("没有符合条件的会话。");
        }
        let row_height = 144.0;
        egui::ScrollArea::vertical()
            .id_salt("conversation-manager-list")
            .auto_shrink([false, false])
            .show_rows(
                ui,
                row_height,
                self.conversation_manager.indices.len(),
                |ui, rows| {
                    for slot in rows {
                        let Some(index) = self.conversation_manager.indices.get(slot).copied()
                        else {
                            continue;
                        };
                        let Some(conversation) = self.state.conversations.get(index).cloned()
                        else {
                            continue;
                        };
                        let running = self.state.runs.contains_key(&conversation.id);
                        let ready = self.state.loaded
                            && !running
                            && self.conversation_manager.pending.is_none()
                            && self.state.conversation_delete_pending.as_ref()
                                != Some(&conversation.id);
                        ui.push_id(&conversation.id, |ui| {
                            ui.group(|ui| {
                                ui.set_min_height(row_height - 12.0);
                                let title = if conversation.pinned {
                                    format!("★ {}", conversation.title)
                                } else {
                                    conversation.title.clone()
                                };
                                let title = if self
                                    .state
                                    .conversation_preferences
                                    .completed_unread
                                    .contains(&conversation.id)
                                    && !running
                                {
                                    format!("● {title}")
                                } else {
                                    title
                                };
                                if ui
                                    .add_enabled(
                                        !self.state.conversation_pending,
                                        egui::Button::new(RichText::new(title).strong())
                                            .truncate()
                                            .min_size(egui::vec2(ui.available_width(), 44.0)),
                                    )
                                    .clicked()
                                {
                                    open = Some(conversation.id.clone());
                                }
                                ui.add(
                                    egui::Label::new(
                                        RichText::new(format!(
                                            "{} · 更新于 {}{}",
                                            conversation.id,
                                            conversation.updated_at,
                                            if running { " · 正在执行" } else { "" }
                                        ))
                                        .small()
                                        .weak(),
                                    )
                                    .truncate(),
                                );
                                ui.menu_button("操作", |ui| {
                                    if !conversation.archived {
                                        let up = slot
                                            .checked_sub(1)
                                            .and_then(|prior| {
                                                self.conversation_manager.indices.get(prior)
                                            })
                                            .and_then(|index| self.state.conversations.get(*index))
                                            .filter(|chat| chat.pinned == conversation.pinned)
                                            .map(|chat| chat.id.clone());
                                        let down = self
                                            .conversation_manager
                                            .indices
                                            .get(slot + 1)
                                            .and_then(|index| self.state.conversations.get(*index))
                                            .filter(|chat| chat.pinned == conversation.pinned)
                                            .map(|chat| chat.id.clone());
                                        if ui
                                            .add_enabled(
                                                up.is_some()
                                                    && self.conversation_order_pending.is_none(),
                                                egui::Button::new("↑"),
                                            )
                                            .on_hover_text("在同组内上移")
                                            .clicked()
                                        {
                                            if let Some(target) = up {
                                                self.move_conversation_order(
                                                    conversation.id.clone(),
                                                    target,
                                                    false,
                                                    self.state
                                                        .conversation_preferences
                                                        .stamp
                                                        .clone(),
                                                );
                                            }
                                        }
                                        if ui
                                            .add_enabled(
                                                down.is_some()
                                                    && self.conversation_order_pending.is_none(),
                                                egui::Button::new("↓"),
                                            )
                                            .on_hover_text("在同组内下移")
                                            .clicked()
                                        {
                                            if let Some(target) = down {
                                                self.move_conversation_order(
                                                    conversation.id.clone(),
                                                    target,
                                                    true,
                                                    self.state
                                                        .conversation_preferences
                                                        .stamp
                                                        .clone(),
                                                );
                                            }
                                        }
                                    }
                                    if ui.add_enabled(ready, egui::Button::new("重命名")).clicked()
                                    {
                                        self.conversation_manager.rename = Some((
                                            conversation.id.clone(),
                                            conversation.title.clone(),
                                        ));
                                    }
                                    if ui
                                        .add_enabled(
                                            ready,
                                            egui::Button::new(if conversation.pinned {
                                                "取消置顶"
                                            } else {
                                                "置顶"
                                            }),
                                        )
                                        .clicked()
                                    {
                                        mutation = Some((
                                            conversation.id.clone(),
                                            Mutation::Pin(!conversation.pinned),
                                        ));
                                    }
                                    if ui
                                        .add_enabled(
                                            ready,
                                            egui::Button::new(if conversation.archived {
                                                "取消归档"
                                            } else {
                                                "归档"
                                            }),
                                        )
                                        .clicked()
                                    {
                                        mutation = Some((
                                            conversation.id.clone(),
                                            Mutation::Archive(!conversation.archived),
                                        ));
                                    }
                                    if ui.button("详情").clicked() {
                                        info = Some(conversation.id.clone());
                                    }
                                    if ui.button("导出JSON").clicked() {
                                        export = Some((
                                            conversation.id.clone(),
                                            conversation.title.clone(),
                                        ));
                                    }
                                    if ui
                                        .add_enabled(
                                            self.can_delete_conversation(&conversation.id),
                                            egui::Button::new("删除"),
                                        )
                                        .clicked()
                                    {
                                        self.conversation_delete = Some(conversation.id.clone());
                                    }
                                });
                            });
                        });
                    }
                },
            );
        if let Some((conversation_id, mutation)) = mutation {
            let request_id = crate::controller::unique_id();
            if self.dispatch(Action::MutateChat {
                request_id: request_id.clone(),
                conversation_id,
                mutation,
            }) {
                self.conversation_manager.pending = Some(request_id);
            }
        }
        if let Some(id) = info {
            self.conversation_info_begin(id);
        }
        if let Some((id, title)) = export {
            self.begin_conversation_export(id, title);
        }
        if let Some(id) = open {
            self.navigate(Navigation::Conversation(id), true);
        }
        self.conversation_info_dialog(ui.ctx());
    }
    fn conversation_info_dialog(&mut self, ctx: &egui::Context) {
        let Some(id) = self.conversation_manager.info_id.clone() else {
            return;
        };
        let mut open = true;
        let mut refresh = false;
        egui::Window::new("会话文件详情")
            .id(egui::Id::new("conversation-file-details"))
            .open(&mut open)
            .default_width(620.0)
            .vscroll(true)
            .show(ctx, |ui| {
                ui.label(format!("会话ID：{id}"));
                if self.conversation_manager.info_request.is_some() {
                    ui.spinner();
                    ui.label("正在读取已提交的会话文件…");
                }
                if let Some(result) = &self.conversation_manager.info {
                    match result {
                        Err(error) => {
                            ui.label(RichText::new(error).color(ui.visuals().error_fg_color));
                        }
                        Ok(info) => {
                            ui.label(format!(
                                "{} 条消息 · {}",
                                info.message_count,
                                bytes_label(info.total_bytes)
                            ));
                            ui.label(if info.segmented {
                                format!(
                                    "分段历史，{} 个正文段；体积含提交索引。",
                                    info.segment_count
                                )
                            } else {
                                "单文件JSON历史。".into()
                            });
                            ui.add(
                                egui::Label::new(format!("提交文件：{}", info.index.path))
                                    .selectable(true)
                                    .wrap(),
                            );
                            ui.label(format!("提交文件大小：{}", bytes_label(info.index.bytes)));
                            if let Some(modified) = info.index.modified_at {
                                ui.label(format!("文件修改时间：{modified}（Unix 毫秒）"));
                            }
                            if ui.button("复制文件路径").clicked() {
                                ctx.copy_text(info.index.path.clone());
                            }
                            ui.separator();
                            ui.add(
                                egui::Label::new(format!(
                                    "日志末尾：{} · 起始字节 {}",
                                    info.log_path, info.log_from
                                ))
                                .selectable(true)
                                .wrap(),
                            );
                            ui.label("最多显示最后32 KiB原始JSON或JSONL。完整JSON使用会话导出。");
                            ui.add(
                                egui::Label::new(RichText::new(&info.log).monospace())
                                    .selectable(true)
                                    .wrap(),
                            );
                        }
                    }
                }
                if ui
                    .add_enabled(
                        self.conversation_manager.info_request.is_none(),
                        egui::Button::new("重新读取"),
                    )
                    .clicked()
                {
                    refresh = true;
                }
            });
        if !open {
            self.conversation_info_close();
        } else if refresh {
            self.conversation_info_begin(id);
        }
    }
}
