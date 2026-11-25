use super::controls;
use crate::state::Settings;
use eframe::egui;

pub(super) fn draw(ui: &mut egui::Ui, settings: &mut Settings, can_reset: bool) -> bool {
    let a = &mut settings.behavior;
    controls::first_section(ui, "主题与配色");
    controls::choice_row(
        ui,
        "主题",
        "「默认」使用 Material 3 组件；「MIUIX」在安卓主界面启用第三方 Miuix（HyperOS 风格）组件与图标，两者均支持下方配色与字体。",
        &mut a.ui_theme,
        &[(0, "默认"), (1, "MIUIX")],
    );
    controls::choice_row(
        ui,
        "深浅模式",
        "",
        &mut a.theme_mode,
        &[(0, "跟随系统"), (1, "浅色"), (2, "深色")],
    );
    controls::choice_row(
        ui,
        "配色",
        "「跟随壁纸」需要安卓 12 以上，会改用系统动态取色；默认与 MIUIX 主题均同步生效，「MIUIX 默认」即官方 HyperOS 蓝白/纯黑配色。",
        &mut a.palette_mode,
        &[(1, "跟随壁纸"), (2, "JAgent 配色"), (4, "MIUIX 默认")],
    );
    controls::section(ui, "文字与布局");
    controls::choice_row(
        ui,
        "字体",
        "「系统字体」使用系统默认无衬线体；「JAgent 字体」使用衬线正文字体（默认）。",
        &mut a.font_preset,
        &[(0, "系统字体"), (1, "JAgent 字体")],
    );
    controls::choice_row(
        ui,
        "字号",
        "正文字号，立即生效。",
        &mut a.font_scale,
        &[(0, "小"), (1, "标准"), (2, "大"), (3, "特大")],
    );
    controls::choice_row(
        ui,
        "圆角",
        "卡片、按钮与输入框的圆角大小。",
        &mut a.corner_scale,
        &[(0, "方正"), (1, "标准"), (2, "圆润")],
    );
    controls::choice_row(
        ui,
        "助手回复样式",
        "「卡片」保留圆角、底色与描边；「扁平」只留头像和名称，正文直接铺在页面上。",
        &mut a.assistant_card,
        &[(true, "卡片"), (false, "扁平")],
    );
    controls::choice_row(
        ui,
        "扁平正文留白",
        "「扁平」样式下正文的左右留白：2 / 6 / 10dp。列表本身还留 16dp，所以正文距屏幕边缘是 18 / 22 / 26dp。",
        &mut a.flat_padding,
        &[(0, "紧凑"), (1, "标准"), (2, "宽松")],
    );
    controls::choice_row(
        ui,
        "气泡宽度",
        "助手回复最多占屏幕的宽度比例。",
        &mut a.bubble_width,
        &[(0, "窄"), (1, "标准"), (2, "宽")],
    );
    controls::section(ui, "动效与提示");
    controls::choice_row(
        ui,
        "打字机效果",
        "正在生成的正文与思考逐字显示；关掉则一收到就整块出现。已结束的回答不受影响。",
        &mut a.typewriter,
        &[(true, "开"), (false, "关")],
    );
    controls::choice_row(
        ui,
        "提示条自动消失",
        "屏幕上方那条提示（例如连接中断）几秒后自己消失；「不消失」只能手点 × 关掉。回答卡片里的原因不受这里影响。",
        &mut a.banner_seconds,
        &[(5, "5 秒"), (10, "10 秒"), (30, "30 秒"), (0, "不消失")],
    );
    ui.add_space(12.0);
    ui.add_enabled(can_reset, egui::Button::new("恢复默认外观"))
        .clicked()
}
