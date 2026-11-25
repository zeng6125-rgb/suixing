//! Operational usage view. Fresh attachment tokens and exact query IDs reject
//! late results. Statistics are computed by the owning actor before rendering.
use super::settings::controls;
use crate::legacy_usage::{Filter, Metric, Report, Totals};
use eframe::egui::{self, RichText};
use std::sync::atomic::{AtomicU64, Ordering};
use std::{cmp::Reverse, collections::BTreeMap};
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
    today: String,
    preset: Option<u32>,
    auto_query: bool,
    pending: Option<(String, Filter)>,
    report: Option<(Filter, Arc<Report>)>,
    selected_day: usize,
    models_expanded: bool,
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
            today: String::new(),
            preset: Some(30),
            auto_query: false,
            pending: None,
            report: None,
            selected_day: 0,
            models_expanded: false,
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
                    self.today.clone_from(today);
                    self.zone = Some(zone_id.clone());
                    self.auto_query = true;
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
                self.selected_day = filter.dates.len().saturating_sub(1);
                self.models_expanded = false;
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
        self.auto_query = false;
    }

    fn query(&mut self) -> Result<Request, String> {
        if let Some(days) = self.preset {
            self.from = preset_start(&self.today, days)?;
            self.to.clone_from(&self.today);
            self.model.clear();
            self.chat.clear();
            self.deleted = None;
        }
        let filter = Filter {
            dates: calendar_range(&self.from, &self.to)?,
            model: (!self.model.trim().is_empty()).then(|| self.model.trim().into()),
            deleted: self.deleted,
            chat_id: (!self.chat.trim().is_empty()).then(|| self.chat.trim().into()),
        };
        let request_id = request_id(&self.token)?;
        self.pending = Some((request_id.clone(), filter.clone()));
        self.error = None;
        Ok(Request::Query {
            request_id,
            ui_token: self.token.clone(),
            filter,
            zone_id: self.zone.clone().expect("ready actual calendar"),
        })
    }

    fn custom_filter(&mut self, ui: &mut egui::Ui) -> bool {
        egui::CollapsingHeader::new("自定义筛选")
            .show(ui, |ui| {
                controls::card(ui, 12)
                    .show(ui, |ui| {
                        ui.columns(2, |columns| {
                            controls::field(
                                &mut columns[0],
                                "开始日期",
                                &mut self.from,
                                "YYYY-MM-DD",
                                false,
                            );
                            controls::field(
                                &mut columns[1],
                                "结束日期",
                                &mut self.to,
                                "YYYY-MM-DD",
                                false,
                            );
                        });
                        ui.add_space(10.0);
                        ui.columns(2, |columns| {
                            controls::field(&mut columns[0], "模型", &mut self.model, "", false);
                            controls::field(&mut columns[1], "会话 ID", &mut self.chat, "", false);
                        });
                        ui.horizontal_wrapped(|ui| {
                            ui.selectable_value(&mut self.deleted, None, "所有来源");
                            ui.selectable_value(&mut self.deleted, Some(false), "存续来源");
                            ui.selectable_value(&mut self.deleted, Some(true), "已删除来源");
                        });
                        ui.button("应用筛选").clicked()
                    })
                    .inner
            })
            .body_returned
            .unwrap_or(false)
    }

    pub fn show(&mut self, ui: &mut egui::Ui) -> Vec<Request> {
        let mut requests = Vec::new();
        if !self.attached {
            if let Some(request) = self.open() {
                requests.push(request);
            }
        }
        if self.zone.is_none() {
            if self.opening.is_some() {
                ui.horizontal(|ui| {
                    ui.spinner();
                    ui.label("正在读取日期和时区…");
                });
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
        let mut query = std::mem::take(&mut self.auto_query);
        ui.add_enabled_ui(self.pending.is_none(), |ui| {
            ui.horizontal_wrapped(|ui| {
                for days in [7, 30, 90] {
                    if ui
                        .selectable_label(self.preset == Some(days), format!("近 {days} 天"))
                        .clicked()
                    {
                        self.preset = Some(days);
                        query = true;
                    }
                }
                query |= ui.button("刷新用量").clicked();
            });
        });
        if query {
            match self.query() {
                Ok(request) => requests.push(request),
                Err(error) => self.error = Some(error),
            }
        }
        if self.pending.is_some() {
            ui.horizontal(|ui| {
                ui.spinner();
                ui.label("正在汇总用量…");
            });
        }
        if let Some(error) = &self.error {
            ui.colored_label(ui.visuals().error_fg_color, error);
        }
        if let Some((filter, report)) = &self.report {
            dashboard(
                ui,
                filter,
                report,
                &mut self.selected_day,
                &mut self.models_expanded,
            );
        }
        if ui
            .add_enabled_ui(self.pending.is_none(), |ui| self.custom_filter(ui))
            .inner
        {
            self.preset = None;
            match self.query() {
                Ok(request) => requests.push(request),
                Err(error) => self.error = Some(error),
            }
        }
        if let Some((_, report)) = &self.report {
            egui::CollapsingHeader::new("统计来源和明细").show(ui, |ui| {
                report_details(ui, report);
            });
        }
        requests
    }
}

/// Compose's presets are inclusive: 近 7 天 means today plus the six days before it.
fn preset_start(today: &str, days: u32) -> Result<String, String> {
    crate::legacy_usage::day_valid(today)?;
    if !(1..=366).contains(&days) {
        return Err("预设天数无效".into());
    }
    let mut year = today[..4].parse::<u32>().map_err(|_| "年份无效")?;
    let mut month = today[5..7].parse::<u32>().map_err(|_| "月份无效")?;
    let mut date = today[8..].parse::<u32>().map_err(|_| "日期无效")?;
    for _ in 1..days {
        if date > 1 {
            date -= 1;
            continue;
        }
        if month > 1 {
            month -= 1;
        } else {
            month = 12;
            year = year.checked_sub(1).ok_or("日期范围溢出")?;
        }
        date = days_in_month(year, month);
    }
    Ok(format!("{year:04}-{month:02}-{date:02}"))
}

fn days_in_month(year: u32, month: u32) -> u32 {
    match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        _ if year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) => 29,
        _ => 28,
    }
}

/// Sum a value that is only meaningful when every row reported it.
fn reported(value: &Metric) -> u128 {
    if value.unknown_rows == 0 && value.invalid_rows == 0 {
        value.known_sum
    } else {
        0
    }
}

fn tokens(value: &Totals) -> u128 {
    reported(&value.prompt) + reported(&value.completion)
}

fn compact(value: u128) -> String {
    match value {
        0..=9_999 => value.to_string(),
        10_000..=999_999 => {
            let tenths = value as f64 / 100_000.0;
            format!("{tenths:.1}万")
        }
        _ => format!("{:.1}M", value as f64 / 1_000_000.0),
    }
}

/// Compose's overview: the window total, the daily bars and the model ranking.
fn dashboard(
    ui: &mut egui::Ui,
    filter: &Filter,
    report: &Report,
    selected_day: &mut usize,
    models_expanded: &mut bool,
) {
    let days = &filter.dates;
    let window = &report.requests_window;
    let total = tokens(window);
    let active = days
        .iter()
        .filter(|day| {
            report
                .requests_by_day
                .get(*day)
                .is_some_and(|value| value.reported_requests + value.unreported_requests > 0)
        })
        .count();
    controls::card(ui, 14).show(ui, |ui| {
        ui.horizontal(|ui| {
            controls::description(ui, &format!("近 {} 天已报告用量", days.len()));
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                controls::description(ui, &format!("{active} 天有活动"));
            });
        });
        controls::text(ui, &compact(total), 30.0, 38.0, ui.visuals().text_color());
        controls::description(ui, &format!("{total} tokens"));
        ui.add_space(12.0);
        ui.columns(3, |columns| {
            for (index, (title, value)) in [
                ("输入", reported(&window.prompt)),
                ("输出", reported(&window.completion)),
                ("已记录响应", window.reported_requests),
            ]
            .into_iter()
            .enumerate()
            {
                controls::card(&columns[index], 10).show(&mut columns[index], |ui| {
                    controls::description(ui, title);
                    controls::text(ui, &compact(value), 18.0, 24.0, ui.visuals().text_color());
                });
            }
        });
        if window.unreported_requests > 0 {
            ui.add_space(10.0);
            controls::description(
                ui,
                &format!(
                    "其中 {} 次响应未报告用量，以上 tokens 仅为已报告部分。",
                    window.unreported_requests
                ),
            );
        }
        ui.add_space(10.0);
        for (label, value) in [
            ("缓存输入（单列）", reported(&window.cached)),
            ("推理（单列）", reported(&window.reasoning)),
            ("已报告用量", window.reported_requests),
            ("未报告用量", window.unreported_requests),
        ] {
            ui.horizontal(|ui| {
                controls::description(ui, label);
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    controls::description(ui, &value.to_string());
                });
            });
        }
    });
    ui.add_space(10.0);
    if days.is_empty() {
        return;
    }
    *selected_day = (*selected_day).min(days.len() - 1);
    daily_chart(ui, report, selected_day);
    ui.add_space(10.0);
    models(ui, report, models_expanded);
}

fn daily_chart(ui: &mut egui::Ui, report: &Report, selected_day: &mut usize) {
    let days: Vec<(&String, u128, u128)> = report
        .requests_by_day
        .iter()
        .map(|(day, value)| (day, reported(&value.prompt), reported(&value.completion)))
        .collect();
    let peak = days
        .iter()
        .map(|(_, prompt, completion)| prompt + completion)
        .max()
        .unwrap_or(0);
    let primary = ui.visuals().hyperlink_color;
    let secondary = ui.visuals().text_color().gamma_multiply(0.55);
    controls::card(ui, 14).show(ui, |ui| {
        ui.horizontal(|ui| {
            controls::description(ui, "每日用量");
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                controls::description(ui, &format!("峰值 {} tokens", peak));
            });
        });
        let (rect, response) = ui.allocate_exact_size(
            egui::vec2(ui.available_width(), 148.0),
            egui::Sense::click(),
        );
        let painter = ui.painter();
        for step in 1..3 {
            let y = rect.top() + rect.height() * step as f32 / 3.0;
            painter.line_segment(
                [egui::pos2(rect.left(), y), egui::pos2(rect.right(), y)],
                egui::Stroke::new(1.0, ui.visuals().weak_text_color().gamma_multiply(0.3)),
            );
        }
        if peak > 0 && !days.is_empty() {
            let slot = rect.width() / days.len() as f32;
            let width = (slot * 0.68).max(1.0);
            for (index, (_, prompt, completion)) in days.iter().enumerate() {
                let total = prompt + completion;
                let height = (total as f64 / peak as f64) as f32 * rect.height();
                let input = if total == 0 {
                    0.0
                } else {
                    (*prompt as f64 / total as f64) as f32 * height
                };
                let x = rect.left() + slot * index as f32 + (slot - width) * 0.5;
                if height > 0.0 {
                    painter.rect_filled(
                        egui::Rect::from_min_size(
                            egui::pos2(x, rect.bottom() - input),
                            egui::vec2(width, input),
                        ),
                        0.0,
                        primary,
                    );
                    painter.rect_filled(
                        egui::Rect::from_min_size(
                            egui::pos2(x, rect.bottom() - height),
                            egui::vec2(width, height - input),
                        ),
                        0.0,
                        secondary,
                    );
                }
            }
            if *selected_day < days.len() {
                let x = rect.left() + (slot * (*selected_day as f32 + 0.5));
                painter.line_segment(
                    [egui::pos2(x, rect.top()), egui::pos2(x, rect.bottom())],
                    egui::Stroke::new(1.0, ui.visuals().text_color().gamma_multiply(0.35)),
                );
            }
            if response.clicked()
                && let Some(pointer) = response.interact_pointer_pos()
            {
                let index = ((pointer.x - rect.left()) / slot) as usize;
                *selected_day = index.min(days.len() - 1);
            }
        }
        if let Some((day, prompt, completion)) = days.get(*selected_day) {
            ui.add_space(8.0);
            controls::text(ui, day, 13.0, 18.0, ui.visuals().text_color());
            controls::description(ui, &format!("{} tokens", prompt + completion));
            controls::description(ui, &format!("输入 {prompt} · 输出 {completion}"));
            ui.horizontal(|ui| {
                if ui
                    .add_enabled(*selected_day > 0, egui::Button::new("前一天"))
                    .clicked()
                {
                    *selected_day -= 1;
                }
                if ui
                    .add_enabled(*selected_day + 1 < days.len(), egui::Button::new("后一天"))
                    .clicked()
                {
                    *selected_day += 1;
                }
            });
        }
    });
}

fn models(ui: &mut egui::Ui, report: &Report, expanded: &mut bool) {
    const COLLAPSED: usize = 4;
    let total: u128 = report.requests_by_model.values().map(tokens).sum();
    let mut rows: Vec<(&String, u128)> = report
        .requests_by_model
        .iter()
        .map(|(model, value)| (model, tokens(value)))
        .collect();
    rows.sort_by_key(|(_, value)| Reverse(*value));
    controls::card(ui, 14).show(ui, |ui| {
        ui.horizontal(|ui| {
            controls::description(ui, "模型用量");
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                controls::description(ui, &format!("{} 个模型", rows.len()));
            });
        });
        if rows.is_empty() {
            controls::description(ui, "这段时间没有已记录的模型响应。");
            return;
        }
        let shown = if *expanded {
            rows.len()
        } else {
            rows.len().min(COLLAPSED)
        };
        for (model, value) in &rows[..shown] {
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                controls::text(ui, model, 14.0, 20.0, ui.visuals().text_color());
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    controls::description(ui, &compact(*value));
                });
            });
            let (bar, _) =
                ui.allocate_exact_size(egui::vec2(ui.available_width(), 5.0), egui::Sense::hover());
            ui.painter().rect_filled(
                bar,
                3.0,
                ui.visuals().weak_text_color().gamma_multiply(0.25),
            );
            let fraction = if total == 0 {
                0.0
            } else {
                (*value as f64 / total as f64) as f32
            };
            if fraction > 0.0 {
                ui.painter().rect_filled(
                    egui::Rect::from_min_size(
                        bar.min,
                        egui::vec2(bar.width() * fraction, bar.height()),
                    ),
                    3.0,
                    ui.visuals().hyperlink_color,
                );
            }
            controls::description(ui, &format!("{value} tokens · {:.1}%", fraction * 100.0));
        }
        if rows.len() > COLLAPSED {
            ui.add_space(8.0);
            let label = if *expanded {
                "收起".to_owned()
            } else {
                format!("展开全部 {} 个模型", rows.len())
            };
            if ui.button(label).clicked() {
                *expanded = !*expanded;
            }
        }
    });
}

fn report_details(ui: &mut egui::Ui, report: &Report) {
    if !report.shared_sources && !report.legacy_by_zone.is_empty() {
        ui.label(format!(
            "历史汇总 {} 个来源，时区 {}",
            report.legacy_matching_chats,
            if report.legacy_zone_id.is_empty() {
                "未知"
            } else {
                &report.legacy_zone_id
            }
        ));
        totals(ui, &report.legacy_window, true);
        egui::CollapsingHeader::new("历史来源时区").show(ui, |ui| {
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
        "逐请求账本 {} 笔，时区 {}",
        report.matching_requests, report.request_zone_id
    ));
    totals(ui, &report.requests_window, false);
    egui::CollapsingHeader::new("按日查看").show(ui, |ui| {
        for (day, total) in &report.requests_by_day {
            ui.label(format!(
                "{day} · 真实请求 · 输入{}；输出{}",
                metric(&total.prompt),
                metric(&total.completion)
            ));
        }
    });
    egui::CollapsingHeader::new("按模型查看").show(ui, |ui| {
        for (model, total) in &report.requests_by_model {
            ui.label(format!(
                "{model} · 真实请求 · 输入{}；输出{}",
                metric(&total.prompt),
                metric(&total.completion)
            ));
        }
    });
    if !report.legacy_all_time_by_day.is_empty() || !report.legacy_all_time_by_model.is_empty() {
        egui::CollapsingHeader::new("历史文件中的独立汇总").show(ui, |ui| {
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn usage_presets_count_civil_days_including_today() {
        for (today, days, expected) in [
            ("2024-03-02", 7, "2024-02-25"),
            ("2025-03-02", 7, "2025-02-24"),
            ("2026-01-10", 30, "2025-12-12"),
            ("2026-01-10", 90, "2025-10-13"),
        ] {
            let from = preset_start(today, days).unwrap();
            assert_eq!(from, expected);
            assert_eq!(calendar_range(&from, today).unwrap().len(), days as usize);
        }
    }
}
