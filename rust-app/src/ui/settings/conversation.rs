use super::{choices, integer_choices, row};
use crate::{state::Settings, subagent_configuration::ApprovalMode};
use eframe::egui::{self, RichText};

pub(super) fn draw(ui: &mut egui::Ui, settings: &mut Settings) {
    ui.heading("输入与运行");
    row(
        ui,
        &mut settings.behavior.enter_key_sends,
        "回车键发送",
        "开启时回车发送，Shift + 回车换行；运行中的发送沿用下面的插话方式。",
    );
    choices(
        ui,
        "插话方式",
        &mut settings.steer_immediately,
        &[(false, "排队"), (true, "立即")],
    );
    ui.label(
        RichText::new("排队等当前输出结束；立即插话会中断当前请求并让模型尽快看到新输入。")
            .small()
            .weak(),
    );
    ui.add(
        egui::Slider::new(&mut settings.max_tool_rounds, 0..=40).text("最大工具轮数（最左为无限）"),
    );
    ui.add(
        egui::Slider::new(&mut settings.behavior.tool_parallel, 1..=4)
            .text("工具并行数（1为串行）"),
    );
    ui.label(
        RichText::new("每次询问审批时按串行执行；自动运行时采用设置的并行数。")
            .small()
            .weak(),
    );
    ui.separator();
    ui.heading("审批与授权");
    ui.horizontal_wrapped(|ui| {
        ui.label("工具审批");
        for mode in ApprovalMode::ALL {
            ui.selectable_value(&mut settings.approval_mode, mode, mode.label());
        }
    });
    remembered_tools(
        ui,
        "总是允许的工具",
        &mut settings.execution.always_allowed_tools,
    );
    remembered_tools(
        ui,
        "总是拒绝的工具",
        &mut settings.execution.always_denied_tools,
    );
    ui.separator();
    ui.heading("上下文与重试");
    row(
        ui,
        &mut settings.behavior.auto_compress,
        "自动压缩上下文",
        "接近当前模型的窗口上限时，把较早的消息压成摘要。",
    );
    ui.add_enabled_ui(settings.behavior.auto_compress, |ui| {
        ui.add(
            egui::Slider::new(&mut settings.compress_at_percent, 20..=95)
                .suffix("%")
                .text("自动压缩阈值"),
        );
    });
    row(
        ui,
        &mut settings.memory_context,
        "注入记忆提示词",
        "后续请求附上你保存的长期记忆。",
    );
    row(
        ui,
        &mut settings.behavior.inject_profile,
        "注入用户画像提示词",
        "后续请求附上关于用户的画像。",
    );
    row(
        ui,
        &mut settings.behavior.retry_on_failure,
        "失败自动重试",
        "网络中断、超时、5xx与429可重试；中途断流会保留失败尝试再重新生成，认证或参数错误和停止不重试。",
    );
    ui.add_enabled_ui(settings.behavior.retry_on_failure, |ui| {
        ui.add(
            egui::Slider::new(&mut settings.behavior.retry_max_attempts, 0..=5)
                .text("最多重试次数（0不重试）"),
        );
        ui.add(
            egui::Slider::new(&mut settings.behavior.retry_delay_seconds, 1..=60)
                .suffix("秒")
                .text("重试间隔"),
        );
    });
    ui.separator();
    ui.heading("对话显示");
    row(
        ui,
        &mut settings.behavior.floating_console,
        "悬浮控制台常驻",
        "离开JAgent后保留悬浮控制台；在JAgent前台时自动隐藏。",
    );
    row(
        ui,
        &mut settings.behavior.console_while_running,
        "仅运行时显示悬浮控制台",
        "常驻关闭时，仅在存在实际运行任务并且JAgent处于后台时显示。",
    );
    ui.add(
        egui::Slider::new(&mut settings.behavior.console_width_dp, 0..=420)
            .text("控制台宽度（dp，0为默认268）"),
    );
    ui.add(
        egui::Slider::new(&mut settings.behavior.console_height_dp, 0..=640)
            .text("内容区高度（dp，0为随内容）"),
    );
    row(
        ui,
        &mut settings.behavior.show_reasoning,
        "显示思考过程",
        "控制思考内容的显示，模型推理强度仍使用模型设置。",
    );
    row(
        ui,
        &mut settings.behavior.expand_reasoning,
        "历史思考块展开",
        "历史思考块默认展开；仍可逐块收起或打开。",
    );
    integer_choices(
        ui,
        "思考块高度",
        &mut settings.behavior.reasoning_height,
        &[(180, "180 矮"), (240, "240 中"), (320, "320 高")],
    );
    row(
        ui,
        &mut settings.behavior.follow_reasoning,
        "思考块跟随最新",
        "流式输出时跟随最新内容，关闭后停在当前阅读位置。",
    );
    row(
        ui,
        &mut settings.behavior.auto_follow,
        "自动跟随新消息",
        "位于底部时跟随新回复，上滑阅读历史后暂停。",
    );
    row(
        ui,
        &mut settings.behavior.show_timestamps,
        "显示时间",
        "显示消息发送时间。",
    );
    row(
        ui,
        &mut settings.behavior.expand_tools,
        "工具调用默认展开",
        "控制历史工具卡片的默认展开状态。",
    );
}

fn remembered_tools(ui: &mut egui::Ui, title: &str, names: &mut Vec<String>) {
    egui::CollapsingHeader::new(format!("{title} · {}", names.len())).show(ui, |ui| {
        ui.label("删除记录后，该工具重新遵循默认审批策略。修改会在保存设置后生效。");
        if !names.is_empty() && ui.button("全部清除").clicked() {
            names.clear();
        }
        let mut remove = None;
        for (index, name) in names.iter().enumerate() {
            ui.push_id(index, |ui| {
                ui.horizontal(|ui| {
                    ui.add(egui::Label::new(name).selectable(true).wrap());
                    if ui.small_button("移除").clicked() {
                        remove = Some(index);
                    }
                });
            });
        }
        if let Some(index) = remove {
            names.remove(index);
        }
    });
}
