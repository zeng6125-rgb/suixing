//! Original bounded PerfMonitor/PerfRecord port, with explicit unknown values.
//! The ledger retains 200 recent runs; it is independent of billing totals.
pub mod frames;
pub mod metrics;
use crate::{platform::permissions::OperationKey, state::Message};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{collections::BTreeMap, sync::Arc};

pub const MAX_RECORDS: usize = 200;
pub const MAX_DOCUMENT_BYTES: usize = 512 * 1024;
#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Record {
    pub id: String,
    pub model: String,
    pub at: i64,
    #[serde(alias = "conversationId")]
    pub conversation_id: String,
    pub first_token_ms: Option<i64>,
    pub elapsed_ms: Option<i64>,
    pub prompt_tokens: Option<i64>,
    pub completion_tokens: Option<i64>,
    pub cached_tokens: Option<i64>,
    pub output_tokens_per_second: Option<f64>,
    pub cache_percent: Option<f64>,
    pub tool_rounds: u32,
    pub wire_chars: Option<u64>,
    #[serde(skip)]
    pub at_label: Option<String>,
    #[serde(default, flatten)]
    pub extra: BTreeMap<String, Value>,
}
impl Record {
    pub fn from_run(
        run_id: &str,
        conversation_id: &str,
        model: &str,
        at: i64,
        messages: &[Message],
        measured_total_ms: Option<i64>,
    ) -> Self {
        let own = messages
            .iter()
            .filter(|message| {
                message.role == "assistant" && message.metadata.run_id.as_deref() == Some(run_id)
            })
            .collect::<Vec<_>>();
        let latest = own
            .iter()
            .rev()
            .find(|message| message.metadata.prompt_tokens.is_some())
            .or_else(|| {
                own.iter()
                    .rev()
                    .find(|message| message.metadata.elapsed_ms > 0)
            });
        let valid = |value: Option<i64>| value.filter(|value| *value >= 0);
        let first_token_ms = latest
            .map(|message| message.metadata.first_token_ms)
            .filter(|value| *value > 0);
        // A last request's elapsed_ms is a different metric from the full run.
        // Only the worker's measured start-to-end duration populates total time.
        let elapsed_ms = measured_total_ms.filter(|value| *value >= 0);
        let prompt_tokens = latest.and_then(|message| valid(message.metadata.prompt_tokens));
        let completion_tokens =
            latest.and_then(|message| valid(message.metadata.completion_tokens));
        let cached_tokens = latest.and_then(|message| valid(message.metadata.cached_tokens));
        let cache_percent = prompt_tokens
            .zip(cached_tokens)
            .and_then(|(prompt, cached)| {
                (prompt > 0 && cached <= prompt).then(|| cached as f64 * 100.0 / prompt as f64)
            });
        let output_tokens_per_second =
            completion_tokens
                .zip(elapsed_ms)
                .and_then(|(tokens, elapsed)| {
                    (elapsed > 0).then(|| tokens as f64 * 1000.0 / elapsed as f64)
                });
        Self {
            id: run_id.into(),
            conversation_id: conversation_id.into(),
            model: model.into(),
            at,
            first_token_ms,
            elapsed_ms,
            prompt_tokens,
            completion_tokens,
            cached_tokens,
            output_tokens_per_second,
            cache_percent,
            tool_rounds: own
                .iter()
                .filter(|message| !message.tool_calls.is_empty())
                .count()
                .min(u32::MAX as usize) as u32,
            wire_chars: None,
            ..Default::default()
        }
    }
    pub fn validate(&self) -> Result<(), String> {
        if self.id.is_empty()
            || self.id.len() > 256
            || self.model.len() > 1024
            || self.conversation_id.len() > 128
            || self.at < 0
            || [
                self.first_token_ms,
                self.elapsed_ms,
                self.prompt_tokens,
                self.completion_tokens,
                self.cached_tokens,
            ]
            .into_iter()
            .flatten()
            .any(|v| v < 0)
            || self
                .output_tokens_per_second
                .is_some_and(|v| !v.is_finite() || v < 0.0)
            || self
                .cache_percent
                .is_some_and(|v| !v.is_finite() || !(0.0..=100.0).contains(&v))
        {
            return Err("性能记录身份或测量无效".into());
        }
        crate::composer::json_bytes(self, 8192)?;
        Ok(())
    }
}
#[derive(Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Document {
    pub version: u32,
    pub records: Vec<Record>,
    #[serde(flatten)]
    pub extra: BTreeMap<String, Value>,
}
impl Document {
    pub fn new() -> Self {
        Self {
            version: 1,
            ..Default::default()
        }
    }
    pub fn validate(&self) -> Result<(), String> {
        if self.version != 1 || self.records.len() > MAX_RECORDS {
            return Err("性能记录格式或预算无效".into());
        }
        let mut ids = std::collections::HashSet::new();
        for record in &self.records {
            record.validate()?;
            if !ids.insert(&record.id) {
                return Err("性能记录身份重复".into());
            }
        }
        crate::composer::json_bytes(self, MAX_DOCUMENT_BYTES)?;
        Ok(())
    }
    pub fn append(&mut self, record: Record) -> Result<bool, String> {
        record.validate()?;
        if let Some(previous) = self
            .records
            .iter()
            .find(|previous| previous.id == record.id)
        {
            let before = serde_json::to_value(previous).map_err(|_| "性能记录无法编码")?;
            let after = serde_json::to_value(&record).map_err(|_| "性能记录无法编码")?;
            if before != after {
                return Err("已有运行性能记录包含不同结果，未覆盖".into());
            }
            return Ok(false);
        }
        let mut next = self.clone();
        next.records.push(record);
        if next.records.len() > MAX_RECORDS {
            next.records.remove(0);
        }
        next.validate()?;
        *self = next;
        Ok(true)
    }
    pub fn recent(&self) -> Vec<Record> {
        self.records.iter().rev().take(20).cloned().collect()
    }
    /// Old zero tokens/times did not encode availability. Preserve the original
    /// JSON under provenance, and map those ambiguous zeros to unknown.
    pub fn from_legacy(value: Value, source_sha: &str) -> Result<Self, String> {
        let records = value.as_array().ok_or("旧性能记录不是列表")?;
        if records.len() > MAX_RECORDS
            || source_sha.len() != 64
            || !source_sha.bytes().all(|byte| byte.is_ascii_hexdigit())
        {
            return Err("旧性能记录超过预算或缺少来源指纹".into());
        }
        let mut document = Self::new();
        for (index, value) in records.iter().enumerate() {
            let object = value.as_object().ok_or("旧性能记录不是对象")?;
            let positive = |name: &str| {
                object
                    .get(name)
                    .and_then(Value::as_i64)
                    .filter(|value| *value > 0)
            };
            let mut record = Record {
                id: format!("legacy-{}-{index}", &source_sha[..16]),
                model: object
                    .get("model")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .into(),
                at: object.get("at").and_then(Value::as_i64).unwrap_or(0),
                conversation_id: object
                    .get("conversationId")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .into(),
                first_token_ms: positive("ttftMs"),
                elapsed_ms: positive("totalMs"),
                prompt_tokens: positive("promptTokens"),
                completion_tokens: positive("completionTokens"),
                cached_tokens: positive("cachedTokens"),
                wire_chars: object
                    .get("wireChars")
                    .and_then(Value::as_u64)
                    .filter(|value| *value > 0),
                tool_rounds: object
                    .get("toolRounds")
                    .and_then(Value::as_u64)
                    .unwrap_or(0)
                    .min(u32::MAX as u64) as u32,
                ..Default::default()
            };
            record.cache_percent = record
                .prompt_tokens
                .zip(record.cached_tokens)
                .and_then(|(p, c)| (c <= p).then(|| c as f64 * 100.0 / p as f64));
            record.output_tokens_per_second = record
                .completion_tokens
                .zip(record.elapsed_ms)
                .map(|(tokens, time)| tokens as f64 * 1000.0 / time as f64);
            record.extra.insert(
                "rustLegacyPerformanceV1".into(),
                serde_json::json!({"source_sha256":source_sha,"raw":value}),
            );
            document.records.push(record);
        }
        document.validate()?;
        Ok(document)
    }
}

#[derive(Clone, Default)]
pub struct Device {
    pub process_cpu_percent: Option<f64>,
    pub system_cpu_percent: Option<f64>,
    pub gpu_percent: Option<f64>,
    pub gpu_frequency_mhz: Option<f64>,
    pub fps: Option<f64>,
    pub p90_frame_ms: Option<f64>,
    pub pss_mb: Option<f64>,
    pub java_heap_mb: Option<f64>,
    pub java_heap_max_mb: Option<f64>,
    pub threads: Option<u64>,
}
#[derive(Clone, Default)]
pub struct Summary {
    pub count: usize,
    pub avg_ttft_ms: Option<f64>,
    pub avg_total_ms: Option<f64>,
    pub avg_cache_percent: Option<f64>,
    pub avg_tokens_per_second: Option<f64>,
}
#[derive(Clone, Default)]
pub struct Health {
    pub runs: usize,
    pub ttft_p50: Option<i64>,
    pub ttft_p90: Option<i64>,
    pub total_p50: Option<i64>,
    pub total_p90: Option<i64>,
    pub jitter: Option<f64>,
    pub no_usage_runs: usize,
    pub slow_runs: usize,
}
#[derive(Clone, Default)]
pub struct Snapshot {
    pub device: Device,
    pub records: Vec<Record>,
    pub summary: Summary,
    pub health: Health,
}
pub enum Request {
    Visible {
        operation: OperationKey,
        visible: bool,
    },
    Refresh(OperationKey),
}
#[derive(Clone)]
pub enum Update {
    Snapshot {
        operation: OperationKey,
        snapshot: Arc<Snapshot>,
    },
    Failed {
        operation: OperationKey,
        error: String,
    },
}
impl std::fmt::Debug for Update {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Snapshot {
                operation,
                snapshot,
            } => formatter
                .debug_struct("PerformanceSnapshot")
                .field("operation", operation)
                .field("records", &snapshot.records.len())
                .finish(),
            Self::Failed { operation, .. } => formatter
                .debug_tuple("PerformanceFailed")
                .field(operation)
                .finish(),
        }
    }
}
impl Snapshot {
    pub fn from_records(device: Device, records: Vec<Record>) -> Self {
        let average = |values: Vec<f64>| {
            (!values.is_empty()).then(|| values.iter().sum::<f64>() / values.len() as f64)
        };
        let summary = Summary {
            count: records.len(),
            avg_ttft_ms: average(
                records
                    .iter()
                    .filter_map(|record| record.first_token_ms.map(|value| value as f64))
                    .collect(),
            ),
            avg_total_ms: average(
                records
                    .iter()
                    .filter_map(|record| record.elapsed_ms.map(|value| value as f64))
                    .collect(),
            ),
            avg_cache_percent: average(
                records
                    .iter()
                    .filter_map(|record| record.cache_percent)
                    .collect(),
            ),
            avg_tokens_per_second: average(
                records
                    .iter()
                    .filter_map(|record| record.output_tokens_per_second)
                    .collect(),
            ),
        };
        let mut ttft = records
            .iter()
            .filter_map(|record| record.first_token_ms)
            .collect::<Vec<_>>();
        ttft.sort_unstable();
        let mut total = records
            .iter()
            .filter_map(|record| record.elapsed_ms)
            .collect::<Vec<_>>();
        total.sort_unstable();
        let percentile = |values: &[i64], numerator: usize| {
            if values.is_empty() {
                None
            } else {
                Some(values[((values.len() * numerator).div_ceil(100)).saturating_sub(1)])
            }
        };
        let p50 = percentile(&total, 50);
        let p90 = percentile(&total, 90);
        let health = Health {
            runs: records.len(),
            ttft_p50: percentile(&ttft, 50),
            ttft_p90: percentile(&ttft, 90),
            total_p50: p50,
            total_p90: p90,
            jitter: p50
                .zip(p90)
                .and_then(|(p50, p90)| (p50 > 0).then(|| p90 as f64 / p50 as f64)),
            no_usage_runs: records
                .iter()
                .filter(|record| record.prompt_tokens.is_none())
                .count(),
            slow_runs: records
                .iter()
                .filter(|record| record.elapsed_ms.is_some_and(|ms| ms > 60_000))
                .count(),
        };
        Self {
            device,
            records,
            summary,
            health,
        }
    }
    pub fn validate(&self) -> Result<(), String> {
        if self.records.len() > 20 {
            return Err("性能显示超过最近20条预算".into());
        }
        for record in &self.records {
            record.validate()?;
        }
        for value in [
            self.device.process_cpu_percent,
            self.device.system_cpu_percent,
            self.device.gpu_percent,
            self.device.gpu_frequency_mhz,
            self.device.fps,
            self.device.p90_frame_ms,
            self.device.pss_mb,
            self.device.java_heap_mb,
            self.device.java_heap_max_mb,
        ]
        .into_iter()
        .flatten()
        {
            if !value.is_finite() || value < 0.0 {
                return Err("设备快照包含无效测量".into());
            }
        }
        Ok(())
    }
}
