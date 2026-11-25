use super::controls;
use crate::{
    performance::{Request, Snapshot, Update},
    platform::permissions::OperationKey,
};
use eframe::egui::{self, RichText};
use std::sync::Arc;

#[derive(Default)]
pub(super) struct PerformanceView {
    generation: u64,
    lease: Option<OperationKey>,
    refresh: Option<OperationKey>,
    snapshot: Option<Arc<Snapshot>>,
    notice: Option<String>,
}
impl PerformanceView {
    fn operation(&self) -> OperationKey {
        OperationKey {
            id: crate::controller::unique_id(),
            conversation_id: None,
            generation: self.generation.max(1),
        }
    }
    pub(super) fn visibility(&mut self, visible: bool) -> Option<Request> {
        if visible && self.lease.is_none() {
            let operation = self.operation();
            self.lease = Some(operation.clone());
            Some(Request::Visible {
                operation,
                visible: true,
            })
        } else if !visible {
            self.refresh = None;
            self.lease.take().map(|operation| Request::Visible {
                operation,
                visible: false,
            })
        } else {
            None
        }
    }
    pub(super) fn disconnected(&mut self) {
        self.lease = None;
        self.refresh = None;
        self.snapshot = None;
        self.generation = self.generation.saturating_add(1).max(1);
    }
    pub(super) fn dispatch_failed(&mut self, operation: &OperationKey) {
        if self.lease.as_ref() == Some(operation) {
            self.lease = None;
        }
        if self.refresh.as_ref() == Some(operation) {
            self.refresh = None;
        }
        self.notice = Some("性能读取未送达".into());
    }
    pub(super) fn apply(&mut self, update: Update) {
        match update {
            Update::Snapshot {
                operation,
                snapshot,
            } if self.lease.as_ref() == Some(&operation)
                || self.refresh.as_ref() == Some(&operation) =>
            {
                if self.refresh.as_ref() == Some(&operation) {
                    self.refresh = None;
                }
                if let Err(error) = snapshot.validate() {
                    self.notice = Some(error);
                    return;
                }
                self.snapshot = Some(snapshot);
            }
            Update::Failed { operation, error }
                if self.lease.as_ref() == Some(&operation)
                    || self.refresh.as_ref() == Some(&operation) =>
            {
                if self.refresh.as_ref() == Some(&operation) {
                    self.refresh = None;
                }
                self.notice = Some(error);
            }
            _ => {}
        }
    }
    pub(super) fn show(&mut self, ui: &mut egui::Ui, ready: bool) -> Vec<Request> {
        let mut requests = Vec::new();
        if ready {
            if let Some(request) = self.visibility(true) {
                requests.push(request);
            }
        }
        if let Some(notice) = &self.notice {
            ui.add(egui::Label::new(notice).wrap());
        }
        if let Some(snapshot) = &self.snapshot {
            controls::first_section(ui, &format!("最近 {} 轮", snapshot.summary.count));
            egui::Grid::new("performance_summary")
                .min_col_width(145.0)
                .min_row_height(22.0)
                .spacing([12.0, 5.0])
                .show(ui, |ui| {
                    for (label, value) in [
                        (
                            "首字延迟（均值）",
                            snapshot.summary.avg_ttft_ms.map(|v| format!("{v:.0} ms")),
                        ),
                        (
                            "整轮耗时（均值）",
                            snapshot.summary.avg_total_ms.map(|v| format!("{v:.0} ms")),
                        ),
                        (
                            "缓存命中（均值）",
                            snapshot
                                .summary
                                .avg_cache_percent
                                .map(|v| format!("{v:.2}%")),
                        ),
                        (
                            "输出速度（均值）",
                            snapshot
                                .summary
                                .avg_tokens_per_second
                                .map(|v| format!("{v:.1} tok/s")),
                        ),
                    ] {
                        ui.label(RichText::new(label).weak());
                        ui.monospace(value.unwrap_or_else(|| "—".into()));
                        ui.end_row();
                    }
                });
        }
        let device = self.snapshot.as_ref().map(|snapshot| &snapshot.device);
        controls::section(ui, "设备实时");
        egui::Grid::new("device_performance")
            .min_col_width(145.0)
            .min_row_height(22.0)
            .spacing([12.0, 5.0])
            .show(ui, |ui| {
                for (label, value) in [
                    (
                        "CPU（本进程）",
                        device
                            .and_then(|v| v.process_cpu_percent)
                            .map(|v| format!("{v:.1}%")),
                    ),
                    (
                        "CPU（整机）",
                        device
                            .and_then(|v| v.system_cpu_percent)
                            .map(|v| format!("{v:.1}%")),
                    ),
                    (
                        "GPU",
                        device
                            .and_then(|v| v.gpu_percent)
                            .map(|v| format!("{v:.1}%")),
                    ),
                    (
                        "GPU 频率",
                        device
                            .and_then(|v| v.gpu_frequency_mhz)
                            .map(|v| format!("{v:.0} MHz")),
                    ),
                    (
                        "帧率",
                        device.and_then(|v| v.fps).map(|v| format!("{v:.1} fps")),
                    ),
                    (
                        "帧间隔p90",
                        device
                            .and_then(|v| v.p90_frame_ms)
                            .map(|v| format!("{v:.2} ms")),
                    ),
                    (
                        "内存（PSS）",
                        device.and_then(|v| v.pss_mb).map(|v| {
                            format!(
                                "{v:.1} MB · {}秒一采",
                                crate::performance::metrics::PSS_INTERVAL_SECONDS
                            )
                        }),
                    ),
                    (
                        "Java 堆",
                        device
                            .and_then(|v| v.java_heap_mb.zip(v.java_heap_max_mb))
                            .map(|(used, total)| format!("{used:.1} / {total:.1} MB")),
                    ),
                    (
                        "线程",
                        device.and_then(|v| v.threads).map(|v| v.to_string()),
                    ),
                ] {
                    ui.label(RichText::new(label).weak());
                    ui.monospace(value.unwrap_or_else(|| "—".into()));
                    ui.end_row();
                }
            });
        if let Some(snapshot) = &self.snapshot {
            let health = &snapshot.health;
            controls::section(ui, &format!("会话健康（最近 {} 轮）", health.runs));
            egui::Grid::new("performance_health")
                .min_col_width(145.0)
                .min_row_height(22.0)
                .spacing([12.0, 5.0])
                .show(ui, |ui| {
                    for (label, value) in [
                        ("首字延迟p50 / p90", pair(health.ttft_p50, health.ttft_p90)),
                        (
                            "整轮耗时p50 / p90",
                            pair(health.total_p50, health.total_p90),
                        ),
                        (
                            "抖动（p90/p50）",
                            health
                                .jitter
                                .map_or_else(|| "—".into(), |value| format!("{value:.2}×")),
                        ),
                        ("无 usage 回报", format!("{} 轮", health.no_usage_runs)),
                        ("慢轮（>60秒）", format!("{} 轮", health.slow_runs)),
                    ] {
                        ui.label(RichText::new(label).weak());
                        ui.monospace(value);
                        ui.end_row();
                    }
                });
        }
        controls::section(ui, "请求记录");
        if let Some(snapshot) = &self.snapshot {
            for record in &snapshot.records {
                ui.push_id(&record.id, |ui| {
                    ui.label(format!(
                        "{} · {}",
                        if record.model.is_empty() {
                            "旧记录"
                        } else {
                            &record.model
                        },
                        record.at_label.as_deref().unwrap_or("时间暂不可用")
                    ));
                    ui.horizontal_wrapped(|ui| {
                        ui.label(format!(
                            "首字 {}",
                            record
                                .first_token_ms
                                .map_or("—".into(), |v| format!("{v} ms"))
                        ));
                        ui.label(format!(
                            "整轮 {}",
                            record.elapsed_ms.map_or("—".into(), |v| format!("{v} ms"))
                        ));
                        ui.label(format!(
                            "输出 {}",
                            record
                                .output_tokens_per_second
                                .map_or("—".into(), |v| format!("{v:.2} tokens/s"))
                        ));
                        ui.label(format!(
                            "缓存 {}",
                            record
                                .cache_percent
                                .map_or("—".into(), |v| format!("{v:.2}%"))
                        ));
                    });
                });
            }
        }
        ui.add_space(10.0);
        if ui
            .add_enabled(
                ready && self.refresh.is_none(),
                egui::Button::new("刷新性能记录"),
            )
            .clicked()
        {
            let operation = self.operation();
            self.refresh = Some(operation.clone());
            requests.push(Request::Refresh(operation));
        }
        requests
    }
}
pub(super) fn operation(request: &Request) -> &OperationKey {
    match request {
        Request::Visible { operation, .. } => operation,
        Request::Refresh(key) => key,
    }
}
fn pair(p50: Option<i64>, p90: Option<i64>) -> String {
    format!(
        "{} / {}",
        p50.map_or_else(|| "—".into(), |value| format!("{value} ms")),
        p90.map_or_else(|| "—".into(), |value| format!("{value} ms"))
    )
}
