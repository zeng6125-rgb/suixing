//! Operational usage view. Fresh attachment tokens and exact query IDs reject
//! late results. Statistics are computed by the owning actor before rendering.
use crate::legacy_usage::{Filter, Metric, Report, Totals};
use eframe::egui::{self, RichText};
use std::sync::atomic::{AtomicU64, Ordering};
static NEXT: AtomicU64 = AtomicU64::new(1);
fn request_id(token: &str) -> Result<String, String> {
    let id = NEXT
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |value| {
            value.checked_add(1)
        })
        .map_err(|_| "页面请求身份已耗尽")?;
    Ok(format!("{token}-{id}"))
}
use crate::controller::usage_runtime::{Request, Update};
use std::sync::Arc;

pub struct UsageView {
    token: String,
    opening: Option<String>,
    attached: bool,
    zone: Option<String>,
    pending: Option<(String, Filter)>,
    report: Option<(Filter, Arc<Report>)>,
    from: String,
    to: String,
    model: String,
    chat: String,
    deleted: Option<bool>,
    error: Option<String>,
}
impl Default for UsageView {
    fn default() -> Self {
        Self::new(request_id("usage-view").unwrap_or_default())
    }
}
impl UsageView {
    pub fn new(token: String) -> Self {
        Self {
            token,
            opening: None,
            attached: false,
            zone: None,
            pending: None,
            report: None,
            from: String::new(),
            to: String::new(),
            model: String::new(),
            chat: String::new(),
            deleted: None,
            error: None,
        }
    }
    pub fn apply(&mut self, update: &Update) -> bool {
        let (request, token) = update.identity();
        if token != self.token {
            return false;
        }
        if self.opening.as_deref() == Some(request) {
            match update {
                Update::Ready { today, zone_id, .. } => {
                    if self.from.is_empty() {
                        self.from = today.clone();
                    }
                    if self.to.is_empty() {
                        self.to = today.clone();
                    }
                    self.zone = Some(zone_id.clone());
                    self.error = None;
                }
                Update::Failed { error, .. } => {
                    self.error = Some(error.clone());
                }
                Update::Report { .. } => return false,
            }
            self.opening = None;
            return true;
        }
        if !self.pending.as_ref().is_some_and(|(id, _)| id == request) {
            return false;
        }
        match update {
            Update::Report { report, .. } => {
                let (_, filter) = self.pending.take().expect("matched usage query");
                self.report = Some((filter, report.clone()));
                self.error = None;
            }
            Update::Failed { error, .. } => {
                self.pending = None;
                self.error = Some(error.clone());
            }
            Update::Ready { .. } => return false,
        }
        true
    }
    fn open(&mut self) -> Option<Request> {
        self.attached = true;
        match request_id(&self.token) {
            Ok(id) if !self.token.is_empty() => {
                self.opening = Some(id.clone());
                Some(Request::Open {
                    request_id: id,
                    ui_token: self.token.clone(),
                })
            }
            _ => {
                self.error = Some("用量页面身份已耗尽，请重新打开页面".into());
                None
            }
        }
    }
    pub fn attach(&mut self) {
        self.token = request_id("usage-view").unwrap_or_default();
        self.pending = None;
        self.opening = None;
        self.attached = false;
        self.zone = None;
    }
    pub fn show(&mut self, ui: &mut egui::Ui) -> Vec<Request> {
        let mut requests = Vec::new();
        if !self.attached {
            if let Some(request) = self.open() {
                requests.push(request);
            }
        }
        if self.zone.is_none() {
            ui.label("正在从实际系统日历读取日期与时区");
            if self.opening.is_some() {
                ui.spinner();
            }
            if let Some(error) = &self.error {
                ui.colored_label(ui.visuals().error_fg_color, error);
            }
            if self.opening.is_none() && ui.button("重新读取日历").clicked() {
                if let Some(request) = self.open() {
                    requests.push(request);
                }
            }
            return requests;
        }
        ui.label(format!(
            "真实请求日历时区：{}",
            self.zone.as_deref().unwrap_or("未知")
        ));
        ui.label(RichText::new("用量统计").strong());
        ui.horizontal(|ui| {
            ui.label("开始日期");
            ui.text_edit_singleline(&mut self.from);
            ui.label("结束日期");
            ui.text_edit_singleline(&mut self.to);
        });
        ui.horizontal(|ui| {
            ui.label("模型");
            ui.text_edit_singleline(&mut self.model);
            ui.label("会话ID");
            ui.text_edit_singleline(&mut self.chat);
        });
        ui.horizontal(|ui| {
            ui.selectable_value(&mut self.deleted, None, "所有来源");
            ui.selectable_value(&mut self.deleted, Some(false), "存续来源");
            ui.selectable_value(&mut self.deleted, Some(true), "已删除来源");
        });
        if ui
            .add_enabled(self.pending.is_none(), egui::Button::new("查询"))
            .clicked()
        {
            let query = (|| {
                let dates = calendar_range(&self.from, &self.to)?;
                let id = request_id(&self.token)?;
                Ok::<_, String>((
                    id,
                    Filter {
                        dates,
                        model: (!self.model.trim().is_empty()).then(|| self.model.trim().into()),
                        deleted: self.deleted,
                        chat_id: (!self.chat.trim().is_empty()).then(|| self.chat.trim().into()),
                    },
                ))
            })();
            match query {
                Ok((id, filter)) => {
                    requests.push(Request::Query {
                        request_id: id.clone(),
                        ui_token: self.token.clone(),
                        filter: filter.clone(),
                        zone_id: self.zone.clone().expect("ready actual calendar"),
                    });
                    self.pending = Some((id, filter));
                }
                Err(error) => self.error = Some(error),
            }
        }
        if self.pending.is_some() {
            ui.spinner();
        }
        if let Some(error) = &self.error {
            ui.colored_label(ui.visuals().error_fg_color, error);
        }
        if let Some((filter, report)) = &self.report {
            ui.label(format!(
                "已查询：{} 至 {}；模型 {}",
                filter.dates.first().map_or("全部", String::as_str),
                filter.dates.last().map_or("全部", String::as_str),
                filter.model.as_deref().unwrap_or("全部")
            ));
            ui.separator();
            ui.label(format!(
                "旧汇总：{}个来源；日历时区 {}",
                report.legacy_matching_chats,
                if report.legacy_zone_id.is_empty() {
                    "未知"
                } else {
                    &report.legacy_zone_id
                }
            ));
            totals(ui, &report.legacy_window, true);
            if !report.legacy_by_zone.is_empty() {
                egui::CollapsingHeader::new("按历史来源时区查看").show(ui, |ui| {
                    for (zone, total) in &report.legacy_by_zone {
                        ui.label(zone);
                        totals(ui, total, true);
                    }
                });
            }
            if report.legacy_undated.reported_requests > 0
                || report.legacy_undated.unreported_requests > 0
                || report.legacy_undated.coverage_unknown_buckets > 0
            {
                egui::CollapsingHeader::new("日期未知的历史用量").show(ui, |ui| {
                    ui.label("这些来源缺少真实日期，未加入日期筛选结果。");
                    totals(ui, &report.legacy_undated, true);
                });
            }
            ui.separator();
            ui.label(format!(
                "真实请求：{}笔；日历时区 {}",
                report.matching_requests, report.request_zone_id
            ));
            totals(ui, &report.requests_window, false);
            for warning in &report.warnings {
                ui.label(RichText::new(warning).small().weak());
            }
            egui::CollapsingHeader::new("按日查看").show(ui, |ui| {
                for (day, total) in &report.legacy_by_day {
                    ui.label(format!(
                        "{day} · 旧汇总 · 输入{}；输出{}",
                        metric(&total.prompt),
                        metric(&total.completion)
                    ));
                }
                for (day, total) in &report.requests_by_day {
                    ui.label(format!(
                        "{day} · 真实请求 · 输入{}；输出{}",
                        metric(&total.prompt),
                        metric(&total.completion)
                    ));
                }
            });
            egui::CollapsingHeader::new("按模型查看").show(ui, |ui| {
                for (model, total) in &report.legacy_by_model {
                    ui.label(format!(
                        "{model} · 旧汇总 · 输入{}；输出{}",
                        metric(&total.prompt),
                        metric(&total.completion)
                    ));
                }
                for (model, total) in &report.requests_by_model {
                    ui.label(format!(
                        "{model} · 真实请求 · 输入{}；输出{}",
                        metric(&total.prompt),
                        metric(&total.completion)
                    ));
                }
            });
            egui::CollapsingHeader::new("旧文件中的独立汇总").show(ui, |ui| {
                ui.label("这些旧日汇总或模型汇总没有交叉表证据时，保留其能证明的筛选范围。");
                for (day, total) in &report.legacy_all_time_by_day {
                    ui.label(format!(
                        "{day} · 输入{}；输出{}",
                        metric(&total.prompt),
                        metric(&total.completion)
                    ));
                }
                for (model, total) in &report.legacy_all_time_by_model {
                    ui.label(format!(
                        "{model} · 输入{}；输出{}",
                        metric(&total.prompt),
                        metric(&total.completion)
                    ));
                }
            });
        }
        requests
    }
}
fn totals(ui: &mut egui::Ui, value: &Totals, legacy: bool) {
    ui.label(format!(
        "输入{}；输出{}；推理{}；缓存{}",
        metric(&value.prompt),
        metric(&value.completion),
        metric(&value.reasoning),
        metric(&value.cached)
    ));
    ui.label(format!(
        "已记录有报告{}；已记录未报告{}；待结算{}",
        value.reported_requests, value.unreported_requests, value.pending_requests
    ));
    if legacy {
        ui.label("以上是旧文件保存的累计数；各字段的请求覆盖情况未知，不能把缺失报告视为零。");
    }
    if value.coverage_unknown_buckets > 0 {
        ui.label(format!(
            "{}个汇总桶没有完整请求覆盖计数",
            value.coverage_unknown_buckets
        ));
    }
}
fn metric(metric: &Metric) -> String {
    if metric.known_rows == 0 && metric.unknown_rows == 0 && metric.invalid_rows == 0 {
        "暂无记录".into()
    } else if metric.known_rows == 0 {
        format!(
            "未报告（{}项）；异常{}项",
            metric.unknown_rows, metric.invalid_rows
        )
    } else {
        format!(
            "已知累计{}（缺失{}项；异常{}项）",
            metric.known_sum, metric.unknown_rows, metric.invalid_rows
        )
    }
}
fn calendar_range(from: &str, to: &str) -> Result<Vec<String>, String> {
    crate::legacy_usage::day_valid(from)?;
    crate::legacy_usage::day_valid(to)?;
    if from > to {
        return Err("开始日期晚于结束日期".into());
    }
    let mut days = Vec::new();
    let mut day = from.to_owned();
    loop {
        days.push(day.clone());
        if day == to {
            break;
        }
        if days.len() >= 366 {
            return Err("最多查询366个日历日".into());
        }
        let mut year = day[..4].parse::<u32>().map_err(|_| "年份无效")?;
        let mut month = day[5..7].parse::<u32>().map_err(|_| "月份无效")?;
        let mut date = day[8..].parse::<u32>().map_err(|_| "日期无效")?;
        date += 1;
        let tentative = format!("{year:04}-{month:02}-{date:02}");
        if crate::legacy_usage::day_valid(&tentative).is_err() {
            date = 1;
            month += 1;
            if month > 12 {
                month = 1;
                year += 1;
            }
            if year > 9999 {
                return Err("日期范围溢出".into());
            }
        }
        day = format!("{year:04}-{month:02}-{date:02}");
    }
    Ok(days)
}
