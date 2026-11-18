use crate::{
    composer::{ComposerEvent, SpeechPhase},
    platform::permissions::OperationKey,
};
use eframe::egui;

pub(super) enum Request {
    Test(OperationKey),
    Stop(OperationKey),
}
#[derive(Default)]
pub(super) struct SettingsAudio {
    operation: Option<OperationKey>,
    notice: Option<String>,
    generation: u64,
}
impl SettingsAudio {
    pub(super) fn disconnected(&mut self) {
        if self.operation.take().is_some() {
            self.notice = Some("试听连接已中断，播放状态尚未确认".into());
        }
        self.generation = self.generation.saturating_add(1).max(1);
    }
    pub(super) fn receive(&mut self, event: &ComposerEvent) {
        match event {
            ComposerEvent::Speech { operation, phase }
                if self.operation.as_ref() == Some(operation) =>
            {
                self.notice = Some(match phase {
                    SpeechPhase::RequestingService => "正在准备朗读服务".into(),
                    SpeechPhase::ServiceReady => "朗读服务已确认".into(),
                    SpeechPhase::Speaking => "正在试听".into(),
                    SpeechPhase::WaitingForCleanup => "正在停止试听，等待系统清理确认".into(),
                    SpeechPhase::Finished => "试听已结束".into(),
                    SpeechPhase::Failed(error) => format!("试听失败：{error}"),
                });
                if matches!(phase, SpeechPhase::Finished | SpeechPhase::Failed(_)) {
                    self.operation = None;
                }
            }
            ComposerEvent::Rejected { operation, error }
                if self.operation.as_ref() == Some(operation) =>
            {
                self.operation = None;
                self.notice = Some(error.clone());
            }
            ComposerEvent::Cancelled { operation }
                if self.operation.as_ref() == Some(operation) =>
            {
                self.operation = None;
                self.notice = Some("试听已取消".into());
            }
            _ => {}
        }
    }
    pub(super) fn dispatch_failed(&mut self) {
        self.operation = None;
        self.notice = Some("试听操作未送达".into());
    }
    pub(super) fn show(&mut self, ui: &mut egui::Ui, ready: bool, enabled: bool) -> Vec<Request> {
        let mut commands = Vec::new();
        ui.horizontal(|ui| {
            if ui
                .add_enabled(
                    ready && enabled && self.operation.is_none(),
                    egui::Button::new("试听"),
                )
                .clicked()
            {
                let operation = OperationKey {
                    id: crate::controller::unique_id(),
                    conversation_id: None,
                    generation: self.generation.max(1),
                };
                self.operation = Some(operation.clone());
                commands.push(Request::Test(operation));
            }
            if ui
                .add_enabled(
                    ready && self.operation.is_some(),
                    egui::Button::new("停止朗读"),
                )
                .clicked()
            {
                if let Some(operation) = &self.operation {
                    commands.push(Request::Stop(operation.clone()));
                }
            }
        });
        if let Some(notice) = &self.notice {
            ui.add(egui::Label::new(notice).wrap());
        }
        commands
    }
}
