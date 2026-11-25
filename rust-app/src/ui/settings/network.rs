use super::{controls, row};
use crate::{platform::secrets::SettingsSecretTarget as Target, state::Settings};
use eframe::egui;

pub(super) fn draw(ui: &mut egui::Ui, settings: &mut Settings, can_clear: bool) -> Option<Target> {
    let mut clear = None;
    controls::first_section(ui, "联网搜索");
    ui.add_space(8.0);
    controls::field(
        ui,
        "搜索接口（可留空）",
        &mut settings.behavior.search_endpoint,
        "Tavily兼容搜索地址",
        false,
    );
    ui.add_space(8.0);
    controls::description(
        ui,
        "留空时用内置的 DuckDuckGo 抓取，容易被限流；填 Tavily 兼容接口更稳。",
    );
    ui.add_space(8.0);
    controls::field(
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
    controls::section(ui, "语音朗读");
    row(
        ui,
        &mut settings.speak_replies,
        "朗读回复",
        "用系统语音引擎读出来。输入框右侧的麦克风按钮负责语音输入。",
    );
    controls::section(ui, "语音转写");
    row(
        ui,
        &mut settings.behavior.voice_input,
        "语音输入",
        "开＝输入框右侧显示麦克风，用JAgent自己的录音发给你配置的转写接口；关＝完全不显示麦克风。",
    );
    ui.add_space(8.0);
    controls::field(
        ui,
        "语音转写接口（可留空）",
        &mut settings.asr_endpoint,
        "留空沿用当前聊天接口",
        false,
    );
    ui.add_space(8.0);
    controls::field(
        ui,
        "转写接口路径",
        &mut settings.asr_path,
        "/audio/transcriptions",
        false,
    );
    ui.add_space(8.0);
    controls::field(
        ui,
        "语音转写模型",
        &mut settings.asr_model,
        "语音服务支持的模型ID",
        false,
    );
    ui.add_space(8.0);
    controls::field(
        ui,
        "语音转写 Key（可留空）",
        &mut settings.asr_key,
        "留空保留已存凭据",
        true,
    );
    ui.add_space(8.0);
    controls::description(
        ui,
        "语音接口与聊天接口相同时可沿用聊天 Key；接口不同时请填写独立 Key。",
    );
    if ui
        .add_enabled(can_clear, egui::Button::new("清除已保存的转写 Key"))
        .clicked()
    {
        clear = Some(Target::AsrApiKey);
    }
    clear
}
