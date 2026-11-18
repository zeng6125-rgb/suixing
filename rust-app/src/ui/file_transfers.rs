//! Outbound file UI tickets finish only on the exact actor terminal receipt.
use super::*;
use crate::{
    display::ViewSource,
    file_transfer::{Event as TransferEvent, Kind, Request},
    platform::permissions::OperationKey,
};
struct Pending {
    operation: OperationKey,
    kind: Kind,
    source: ViewSource,
    path: String,
    status: String,
    cancel_requested: bool,
}
#[derive(Default)]
pub(super) struct Transfers {
    pending: std::collections::BTreeMap<String, Pending>,
    recent: std::collections::VecDeque<(String, bool)>,
}
impl Transfers {
    pub(super) fn apply(&mut self, event: TransferEvent) {
        match event {
            TransferEvent::WaitingForTerminal {
                operation,
                kind,
                reason,
                target_open_requested,
                bytes_written,
            } => {
                let Some(pending) = self
                    .pending
                    .get_mut(&operation.id)
                    .filter(|pending| pending.operation == operation && pending.kind == kind)
                else {
                    return;
                };
                pending.status = if target_open_requested {
                    format!("{reason}；已写入{bytes_written}字节，等待操作与文件关闭结果")
                } else {
                    reason
                };
            }
            TransferEvent::Finished(event) => {
                if !self
                    .pending
                    .get(&event.operation.id)
                    .is_some_and(|pending| {
                        pending.operation == event.operation && pending.kind == event.kind
                    })
                {
                    return;
                }
                let pending = self
                    .pending
                    .remove(&event.operation.id)
                    .expect("exact transfer ticket");
                let successful = event.result.is_ok() && event.cleanup.is_ok();
                let mut message = match event.result {
                    Ok(message) => format!("{}：{message}", pending.path),
                    Err(error) => format!("{}：{error}", pending.path),
                };
                if event.cancelled {
                    message.push_str("；取消请求已结算");
                }
                if event.target_open_requested {
                    message.push_str(&format!("；本次实际写入{}字节", event.bytes_written));
                }
                if let Err(error) = event.cleanup {
                    message.push_str(&format!("；快照清理未确认：{error}"));
                }
                self.recent.push_front((message, successful));
                self.recent.truncate(4);
            }
        }
    }
}
impl RustApplication {
    pub(super) fn begin_conversation_export(&mut self, id: String, title: String) {
        if self.file_transfers.pending.len() >= 16 {
            self.state.error("文件交付队列已满，请等待当前操作结束");
            return;
        }
        let operation = OperationKey {
            id: crate::controller::unique_id(),
            conversation_id: Some(id.clone()),
            generation: 1,
        };
        if self.dispatch(Action::ExportConversation(operation.clone())) {
            self.file_transfers.pending.insert(
                operation.id.clone(),
                Pending {
                    operation,
                    kind: Kind::Save,
                    source: ViewSource::Main {
                        conversation_id: id,
                    },
                    path: format!("会话「{title}」"),
                    status: "正在准备完整会话JSON".into(),
                    cancel_requested: false,
                },
            );
        }
    }
    pub(super) fn begin_file_transfer(&mut self, source: ViewSource, path: String, kind: Kind) {
        if self.file_transfers.pending.len() >= 16 {
            self.state
                .error("文件交付正在等待16个实际操作结果，请等待或取消其中一项");
            return;
        }
        let operation = OperationKey {
            id: crate::controller::unique_id(),
            conversation_id: Some(source.conversation_id().into()),
            generation: 1,
        };
        let request = Request {
            operation: operation.clone(),
            kind,
            workspace: PathBuf::new(),
            path: path.clone(),
            history: None,
        };
        if self.dispatch(Action::FileTransfer(request)) {
            self.file_transfers.pending.insert(
                operation.id.clone(),
                Pending {
                    operation,
                    kind,
                    source,
                    path,
                    status: "正在准备文件快照".into(),
                    cancel_requested: false,
                },
            );
        }
    }
    pub(super) fn file_transfer_status(&mut self, ui: &mut egui::Ui) {
        if self.file_transfers.pending.is_empty() && self.file_transfers.recent.is_empty() {
            return;
        }
        let mut cancel = None;
        let mut clear = false;
        egui::CollapsingHeader::new("文件交付")
            .id_salt("file_transfers")
            .default_open(!self.file_transfers.pending.is_empty())
            .show(ui, |ui| {
                for pending in self.file_transfers.pending.values() {
                    ui.horizontal_wrapped(|ui| {
                        ui.spinner();
                        ui.label(format!("{} · {}", pending.path, pending.status));
                        if ui
                            .add_enabled(!pending.cancel_requested, egui::Button::new("取消"))
                            .clicked()
                        {
                            cancel = Some(pending.operation.clone());
                        }
                    });
                    ui.label(
                        RichText::new(format!("会话 {}", pending.source.conversation_id()))
                            .small()
                            .weak(),
                    );
                }
                for (message, successful) in &self.file_transfers.recent {
                    if *successful {
                        ui.label(message);
                    } else {
                        ui.colored_label(ui.visuals().error_fg_color, message);
                    }
                }
                if !self.file_transfers.recent.is_empty()
                    && ui.small_button("清除交付提示").clicked()
                {
                    clear = true;
                }
            });
        if let Some(operation) = cancel {
            if self.dispatch(Action::CancelFileTransfer(operation.clone())) {
                if let Some(pending) = self
                    .file_transfers
                    .pending
                    .get_mut(&operation.id)
                    .filter(|pending| pending.operation == operation)
                {
                    pending.cancel_requested = true;
                    pending.status = "已请求取消，等待实际关闭与清理结果".into();
                }
            }
        }
        if clear {
            self.file_transfers.recent.clear();
        }
    }
}
