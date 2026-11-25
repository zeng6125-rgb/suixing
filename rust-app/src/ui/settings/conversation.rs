use super::controls;
use crate::{state::Settings, subagent_configuration::ApprovalMode};
use eframe::egui;

pub(super) fn draw(ui: &mut egui::Ui, settings: &mut Settings) {
    controls::first_section(ui, "输入与插话");
    controls::row(
        ui,
        &mut settings.behavior.enter_key_sends,
        "回车键发送",
        "开：按回车直接发送（运行中则当作插话送进去）；关：回车换行，用发送键提交。",
    );
    controls::choice_row(
        ui,
        "插话方式",
        "你干活时插的话落在哪里。排队：等这一轮说完再送进去，模型把它当成中途听到的话；立即：打断在途的回复，模型马上看到，代价是这一轮输出作废。",
        &mut settings.steer_immediately,
        &[(false, "排队"), (true, "立即")],
    );
    controls::section(ui, "工具执行");
    let title = if settings.max_tool_rounds == 0 {
        "最大工具轮数 无限".into()
    } else {
        format!("最大工具轮数 {}", settings.max_tool_rounds)
    };
    controls::slider(
        ui,
        &mut settings.max_tool_rounds,
        0..=40,
        &title,
        "一次任务里最多连续调用多少次工具；0＝不限轮数（显示为「无限」），拖到最左就是不限制。",
    );
    let title = if settings.behavior.tool_parallel <= 1 {
        "工具并行数 串行".into()
    } else {
        format!("工具并行数 {}", settings.behavior.tool_parallel)
    };
    controls::slider(
        ui,
        &mut settings.behavior.tool_parallel,
        1..=4,
        &title,
        "模型一轮里请求的多个工具，最多几条同时跑。1＝逐个串行（默认，最稳）；调大可加快多工具任务，但 shell 类工具并发可能互相踩（同时写一个文件）。审批档为「每次询问」时强制串行（授权弹窗一次只挂一个）。",
    );
    controls::section(ui, "审批与授权");
    controls::choice_row(
        ui,
        "工具审批",
        "全局默认；未单独设置审批的会话跟随此项。",
        &mut settings.approval_mode,
        &[
            (ApprovalMode::Auto, "自动运行"),
            (ApprovalMode::Ask, "每次询问"),
            (ApprovalMode::Deny, "禁止"),
        ],
    );
    controls::description(
        ui,
        match settings.approval_mode {
            ApprovalMode::Auto => "模型可以连续调用工具，不再打断你。",
            ApprovalMode::Ask => "每次调用工具前都会问你一次，可以选「总是允许」。",
            ApprovalMode::Deny => "禁止一切工具调用，只能聊天。",
        },
    );
    if settings.approval_mode == ApprovalMode::Ask {
        remembered_tools(
            ui,
            "总是允许的工具",
            &mut settings.execution.always_allowed_tools,
            "还没有。审批时选「总是允许」，这里会记住它，重启也在。",
        );
        remembered_tools(
            ui,
            "总是拒绝的工具",
            &mut settings.execution.always_denied_tools,
            "还没有。审批时选「总是拒绝」，它不会再问第二次。",
        );
    }
    controls::section(ui, "上下文管理");
    controls::row(
        ui,
        &mut settings.behavior.auto_compress,
        "自动压缩上下文",
        "上下文接近窗口上限时把旧消息压成摘要。超窗是接口直接报错，压缩最坏只是丢些细节；只在「模型与生成」里设了上下文条数时才可能触发。",
    );
    ui.add_enabled_ui(settings.behavior.auto_compress, |ui| {
        ui.add_space(12.0);
        controls::text(ui, "自动压缩阈值", 17.0, 24.0, ui.visuals().text_color());
        controls::description(ui, "上下文用到窗口的这个比例时，自动把较早的对话压成摘要；调高更省钱但更容易触顶，调低更保险但会提前丢细节。");
        ui.horizontal(|ui| {
            if ui.button("−").clicked() { settings.compress_at_percent = settings.compress_at_percent.saturating_sub(5).max(20); }
            ui.label(format!("{}%", settings.compress_at_percent));
            if ui.button("+").clicked() { settings.compress_at_percent = (settings.compress_at_percent + 5).min(95); }
        });
        ui.add_space(12.0);
    });
    controls::section(ui, "记忆与画像");
    controls::row(
        ui,
        &mut settings.memory_context,
        "注入记忆提示词",
        "开：每次请求都把「你记得的事」（记忆条目）拼进系统提示词；关：这一段完全不出现，模型不知道你记过什么，但省下这部分上下文。",
    );
    controls::row(
        ui,
        &mut settings.behavior.inject_profile,
        "注入用户画像提示词",
        "开：把「关于用户（画像）」拼进系统提示词，模型先知道「这是谁」；关：整段不出现（画像本身不会被删，只是这次不发给模型）。",
    );
    controls::section(ui, "失败重试");
    controls::row(
        ui,
        &mut settings.behavior.retry_on_failure,
        "失败自动重试",
        "网络中断、超时、5xx 与 429 可重试；中途断流会保留失败尝试再重新生成，认证或参数错误和停止不重试。",
    );
    let title = if settings.behavior.retry_max_attempts == 0 {
        "最多重试次数 不重试".into()
    } else {
        format!("最多重试次数 {}", settings.behavior.retry_max_attempts)
    };
    controls::slider(
        ui,
        &mut settings.behavior.retry_max_attempts,
        0..=5,
        &title,
        "一次请求最多重试几次；0＝不重试。只在上面那个开关打开时生效。",
    );
    let title = format!("重试间隔 {} 秒", settings.behavior.retry_delay_seconds);
    controls::slider(
        ui,
        &mut settings.behavior.retry_delay_seconds,
        1..=60,
        &title,
        "两次重试之间等多久。等得久一点更可能躲过对方的限流。",
    );
    controls::section(ui, "思考过程");
    controls::row(
        ui,
        &mut settings.behavior.show_reasoning,
        "显示思考过程",
        "把模型的思考流式画在回复上方。关掉只是不显示，思考强度照旧生效。",
    );
    controls::row(
        ui,
        &mut settings.behavior.expand_reasoning,
        "历史思考块展开",
        "正在思考的那一块总是展开。开＝历史思考块也展开；关＝历史块收起成一行标题，点它仍可逐块展开。",
    );
    controls::choice_row(
        ui,
        "思考块高度",
        "展开的思考过程最多长这么高，超出部分在块内滚动。",
        &mut settings.behavior.reasoning_height,
        &[(180, "180 矮"), (240, "240 中"), (320, "320 高")],
    );
    controls::row(
        ui,
        &mut settings.behavior.follow_reasoning,
        "思考块跟随最新",
        "思考过程流式输出时自动滚到底部；关掉后停在当前位置",
    );
    controls::section(ui, "消息显示");
    controls::row(
        ui,
        &mut settings.behavior.auto_follow,
        "自动跟随新消息",
        "在底部时新回复自动滚到底；上滑看历史就暂停跟随。",
    );
    controls::row(
        ui,
        &mut settings.behavior.show_timestamps,
        "显示时间",
        "在每条消息下面显示发送时间。",
    );
    controls::row(
        ui,
        &mut settings.behavior.expand_tools,
        "工具调用默认展开",
        "关掉时工具卡片折叠成一行，点开才看参数与结果。",
    );
    controls::section(ui, "悬浮控制台");
    controls::row(
        ui,
        &mut settings.behavior.floating_console,
        "悬浮控制台常驻",
        "离开JAgent后保留悬浮控制台；在JAgent前台时自动隐藏。",
    );
    controls::row(
        ui,
        &mut settings.behavior.console_while_running,
        "仅运行时显示悬浮控制台",
        "常驻关闭时，仅在存在实际运行任务并且JAgent处于后台时显示。",
    );
    let title = format!("控制台宽度 {} dp", settings.behavior.console_width_dp);
    controls::slider(
        ui,
        &mut settings.behavior.console_width_dp,
        0..=420,
        &title,
        "0 为默认 268dp。",
    );
    let title = format!("内容区高度 {} dp", settings.behavior.console_height_dp);
    controls::slider(
        ui,
        &mut settings.behavior.console_height_dp,
        0..=640,
        &title,
        "0 为随内容。",
    );
    ui.add_space(24.0);
}

fn remembered_tools(ui: &mut egui::Ui, title: &str, names: &mut Vec<String>, empty: &str) {
    ui.add_space(12.0);
    ui.horizontal(|ui| {
        controls::text(ui, title, 14.0, 20.0, ui.visuals().text_color());
        if !names.is_empty() && ui.button("全部清除").clicked() {
            names.clear();
        }
    });
    if names.is_empty() {
        controls::description(ui, empty);
    } else {
        controls::description(ui, &format!("{} 个，重启后依然有效", names.len()));
    }
    let mut remove = None;
    for (index, name) in names.iter().enumerate() {
        ui.push_id(index, |ui| {
            ui.horizontal(|ui| {
                ui.add(egui::Label::new(name).selectable(true).wrap());
                if ui.button("×").clicked() {
                    remove = Some(index);
                }
            });
        });
    }
    if let Some(index) = remove {
        names.remove(index);
    }
}
