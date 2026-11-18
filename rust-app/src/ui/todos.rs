use super::*;
use crate::todos::Operation;

impl RustApplication {
    pub(super) fn todo_row(&mut self, ui: &mut egui::Ui) {
        let key = self
            .child_viewer
            .as_ref()
            .map(|(chat, child)| format!("{chat}\0{child}"))
            .or_else(|| self.state.selected_conversation.clone());
        let Some(board) = key.and_then(|key| self.state.todo_boards.get(&key)) else {
            return;
        };
        let current = board
            .items
            .iter()
            .find(|item| item.status == "in_progress")
            .or_else(|| board.items.iter().find(|item| item.status == "pending"));
        if let Some(item) = current {
            let done = board
                .items
                .iter()
                .filter(|item| item.status == "completed")
                .count();
            let label = format!(
                "{}  ·  {done}/{}",
                item.subject.chars().take(100).collect::<String>(),
                board.items.len()
            );
            if ui
                .add_sized([ui.available_width(), 28.0], egui::Button::new(label))
                .clicked()
            {
                self.todos_open = true;
                self.dispatch(Action::LoadTodos);
            }
        }
    }

    pub(super) fn todo_sheet(&mut self, ui: &mut egui::Ui) {
        if !self.todos_open {
            return;
        }
        let mut open = self.todos_open;
        let mut action = None;
        let mut ask_delete = None;
        let revision = self.state.todos.revision;
        egui::Window::new("任务清单").open(&mut open).default_width(480.0).show(ui.ctx(), |ui| {
            ui.horizontal(|ui| {
                ui.label(format!("{} 个步骤", self.state.todos.items.len()));
                if ui.add_enabled(self.todo_pending.is_none(), egui::Button::new("刷新")).clicked() {
                    action = Some(Action::LoadTodos);
                }
                if self.todo_pending.is_some() { ui.spinner(); }
            });
            if self.state.todos.items.is_empty() {
                ui.label("任务清单是空的。可以在对话里让助手记录多步任务，之后在这里修改状态或删除条目。");
            }
            egui::ScrollArea::vertical().max_height(500.0).show(ui, |ui| {
                for (status, title) in [("in_progress", "进行中"), ("pending", "待办"), ("completed", "已完成")] {
                    let count = self.state.todos.items.iter().filter(|item| item.status == status).count();
                    if count == 0 { continue; }
                    ui.label(RichText::new(format!("{title} · {count}")).strong());
                    for item in self.state.todos.items.iter().filter(|item| item.status == status) {
                        ui.push_id(&item.id, |ui| {
                            ui.horizontal_top(|ui| {
                                let next = match item.status.as_str() { "pending" => "in_progress", "in_progress" => "completed", _ => "pending" };
                                if ui.add_enabled(self.todo_pending.is_none(), egui::Button::new(title)).clicked() {
                                    action = Some(Action::MutateTodo { request_id: crate::controller::unique_id(),
                                        operation: Operation::SetStatus { id: item.id.clone(), status: next.into(), expected_revision: revision } });
                                }
                                ui.add(egui::Label::new(&item.subject).wrap());
                                if ui.add_enabled(self.todo_pending.is_none(), egui::Button::new("删除")).clicked() {
                                    ask_delete = Some((item.id.clone(), item.subject.clone(), revision));
                                }
                            });
                            ui.separator();
                        });
                    }
                }
            });
        });
        self.todos_open = open;
        if let Some(target) = ask_delete {
            self.todo_delete = Some(target);
        }
        if let Some((id, subject, revision)) = self.todo_delete.clone() {
            let mut choice = None;
            egui::Window::new("删除任务")
                .collapsible(false)
                .show(ui.ctx(), |ui| {
                    egui::ScrollArea::vertical()
                        .max_height(160.0)
                        .show(ui, |ui| {
                            ui.label(format!("确定删除「{subject}」？"));
                        });
                    ui.horizontal(|ui| {
                        if ui.button("取消").clicked() {
                            choice = Some(false);
                        }
                        if ui
                            .add_enabled(self.todo_pending.is_none(), egui::Button::new("删除"))
                            .clicked()
                        {
                            choice = Some(true);
                        }
                    });
                });
            if let Some(accepted) = choice {
                self.todo_delete = None;
                if accepted {
                    action = Some(Action::MutateTodo {
                        request_id: crate::controller::unique_id(),
                        operation: Operation::Delete {
                            id,
                            expected_revision: revision,
                        },
                    });
                }
            }
        }
        if let Some(action) = action {
            let request = match &action {
                Action::MutateTodo { request_id, .. } => Some(request_id.clone()),
                _ => None,
            };
            if self.dispatch(action) {
                self.todo_pending = request;
            }
        }
    }
}
