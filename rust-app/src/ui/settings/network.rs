use super::row;
use crate::{platform::secrets::SettingsSecretTarget as Target, state::Settings};
use eframe::egui::{self, RichText};

pub(super) fn draw(ui: &mut egui::Ui, settings: &mut Settings, can_clear: bool) -> Option<Target> {
    let mut clear = None;
    ui.heading("联网搜索");
    super::super::text_field(
        ui,
        "搜索接口（可留空）",
        &mut settings.behavior.search_endpoint,
        "Tavily兼容搜索地址",
        false,
    );
    ui.label(
        RichText::new("留空使用内置搜索；填写Tavily兼容接口时可配置独立Key。")
            .small()
            .weak(),
    );
    super::super::text_field(
        ui,
        "搜索接口 Key（可留空）",
        &mut settings.search_key,
        "留空保留已存凭据",
        true,
    );
    if ui
        .add_enabled(can_clear, egui::Button::new("清除已保存的搜索 Key"))
        .clicked()
    {
        clear = Some(Target::SearchApiKey);
    }
    ui.separator();
    ui.heading("语音朗读");
    row(
        ui,
        &mut settings.speak_replies,
        "朗读回复",
        "启用系统语音引擎，完成的回答可从消息操作中朗读。",
    );
    ui.separator();
    ui.heading("语音转写");
    row(
        ui,
        &mut settings.behavior.voice_input,
        "语音输入",
        "在输入框显示麦克风，使用JAgent录音并交给你配置的转写接口。",
    );
    super::super::text_field(
        ui,
        "语音转写接口（可留空）",
        &mut settings.asr_endpoint,
        "留空沿用当前聊天接口",
        false,
    );
    super::super::text_field(
        ui,
        "转写接口路径",
        &mut settings.asr_path,
        "/audio/transcriptions",
        false,
    );
    super::super::text_field(
        ui,
        "语音转写模型",
        &mut settings.asr_model,
        "语音服务支持的模型ID",
        false,
    );
    super::super::text_field(
        ui,
        "语音转写 Key（可留空）",
        &mut settings.asr_key,
        "留空保留已存凭据",
        true,
    );
    ui.label(
        RichText::new("接口与聊天接口相同时可沿用聊天Key；独立接口请填写独立Key。")
            .small()
            .weak(),
    );
    if ui
        .add_enabled(can_clear, egui::Button::new("清除已保存的转写 Key"))
        .clicked()
    {
        clear = Some(Target::AsrApiKey);
    }
    clear
}
