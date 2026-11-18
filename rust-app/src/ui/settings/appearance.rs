use super::{choices, integer_choices};
use crate::state::Settings;
use eframe::egui::{self, RichText};

pub(super) fn draw(ui: &mut egui::Ui, settings: &mut Settings, can_reset: bool) -> bool {
    let appearance = &mut settings.behavior;
    ui.heading("主题与配色");
    integer_choices(
        ui,
        "主题",
        &mut appearance.theme_mode,
        &[(0, "跟随系统"), (1, "浅色"), (2, "深色")],
    );
    integer_choices(
        ui,
        "配色",
        &mut appearance.palette_mode,
        &[(1, "跟随壁纸"), (2, "JAgent 配色"), (3, "Codex")],
    );
    ui.label(
        RichText::new("跟随壁纸在Android12以上采用系统动态取色。")
            .small()
            .weak(),
    );
    ui.separator();
    ui.heading("文字与布局");
    integer_choices(
        ui,
        "字号",
        &mut appearance.font_scale,
        &[(0, "小"), (1, "标准"), (2, "大"), (3, "特大")],
    );
    integer_choices(
        ui,
        "圆角",
        &mut appearance.corner_scale,
        &[(0, "方正"), (1, "标准"), (2, "圆润")],
    );
    choices(
        ui,
        "助手回复样式",
        &mut appearance.assistant_card,
        &[(true, "卡片"), (false, "扁平")],
    );
    ui.add_enabled_ui(!appearance.assistant_card, |ui| {
        integer_choices(
            ui,
            "扁平正文留白",
            &mut appearance.flat_padding,
            &[(0, "紧凑"), (1, "标准"), (2, "宽松")],
        );
    });
    integer_choices(
        ui,
        "气泡宽度",
        &mut appearance.bubble_width,
        &[(0, "窄"), (1, "标准"), (2, "宽")],
    );
    ui.separator();
    ui.heading("动效与提示");
    choices(
        ui,
        "打字机效果",
        &mut appearance.typewriter,
        &[(true, "开"), (false, "关")],
    );
    integer_choices(
        ui,
        "提示条自动消失",
        &mut appearance.banner_seconds,
        &[(5, "5秒"), (10, "10秒"), (30, "30秒"), (0, "不消失")],
    );
    ui.add_enabled(can_reset, egui::Button::new("恢复默认外观"))
        .clicked()
}
