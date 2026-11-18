//! An independent summary request. The caller confirms durable usage and checkpoints.
use std::sync::Arc;

use suixing_search::rust_protocol::{Message, UsageReport};
use tokio::runtime::Runtime;

use crate::{
    chat::ContextCheckpoint,
    context::{
        self, CompactionPlan, CompactionRetryGate, ContextInput, PromptEstimate, ReportedPrompt,
    },
    network::{self, CancelToken},
    state::{Settings, UsageRecord},
};

const MAX_SUMMARY_UNITS: usize = 32_000;

pub struct CompactionRequest<'a> {
    pub input: ContextInput<'a>,
    pub settings: &'a Settings,
    pub run: &'a str,
    pub runtime: &'a Runtime,
    pub parent_cancel: &'a Arc<CancelToken>,
    /// An external stop signal. HTTP owns a private token so a summary budget failure
    /// cannot cancel the caller's run or the following ordinary chat request.
    pub request_cancel: Arc<CancelToken>,
    pub manual: bool,
    pub auto: bool,
    pub estimate: PromptEstimate,
    /// Only the ordinary chat request establishes this basis. Summary usage never replaces it.
    pub basis: Option<ReportedPrompt>,
}

/// Final usage must be persisted even after cancellation. Implementations must not use a
/// cancellation-sensitive acknowledgement wait for `usage` with `pending == false`.
pub trait CompactionCallbacks {
    fn usage(&mut self, record: UsageRecord) -> Result<(), String>;
    fn checkpoint(
        &mut self,
        plan: &CompactionPlan,
        body: &str,
        usage: UsageReport,
    ) -> Result<ContextCheckpoint, String>;
    fn busy(&mut self, _busy: bool) {}
}

pub enum CompactionOutcome {
    Skipped,
    Rejected {
        reason: String,
        usage: UsageReport,
        requests: usize,
    },
    /// The checkpoint callback has confirmed storage; the caller can now rebuild the wire
    /// and clear its old ordinary-request prompt basis.
    Saved {
        checkpoint: ContextCheckpoint,
        usage: UsageReport,
        requests: usize,
    },
}

pub fn maybe_compact(
    request: CompactionRequest<'_>,
    retry: &mut CompactionRetryGate,
    callbacks: &mut impl CompactionCallbacks,
) -> Result<CompactionOutcome, String> {
    maybe_compact_with_budget(request, retry, callbacks, None)
}
pub(crate) fn maybe_compact_with_budget(
    request: CompactionRequest<'_>,
    retry: &mut CompactionRetryGate,
    callbacks: &mut impl CompactionCallbacks,
    request_budget: Option<Arc<network::RequestBudget>>,
) -> Result<CompactionOutcome, String> {
    let cancelled =
        || request.parent_cancel.is_cancelled() || request.request_cancel.is_cancelled();
    if cancelled() {
        return Err("上下文压缩已停止".into());
    }
    let window = request
        .settings
        .model_contexts
        .get(&request.settings.model)
        .copied()
        .filter(|&value| value > 0)
        .map_or(context::DEFAULT_CONTEXT_WINDOW, |value| value as u64);
    let percent = u8::try_from(request.settings.compress_at_percent)
        .ok()
        .filter(|value| (20..=95).contains(value))
        .unwrap_or(context::DEFAULT_COMPRESS_PERCENT);
    let assessment = context::assess_context(request.input, window, cancelled)?;
    let force = match assessment {
        context::ContextAssessment::Uncompressible { reason, .. } => return Err(reason),
        context::ContextAssessment::NeedsCompaction { .. } => true,
        context::ContextAssessment::Ready { .. } => false,
    };
    let actual = request
        .basis
        .map(|basis| context::estimate_after_report(request.estimate, Some(basis)));
    if !force
        && !context::should_compress(
            window,
            percent,
            request.auto,
            request.manual,
            request.estimate.tokens,
            actual,
        )
    {
        return Ok(CompactionOutcome::Skipped);
    }
    if force && !request.manual && !request.auto {
        return Err("上下文超过模型请求预算，请先手动压缩或开启自动压缩；原文保留".into());
    }
    let Some(mut plan) = context::plan_chunked_compaction(
        request.input,
        context::KEEP_RECENT_MESSAGES,
        context::MIN_MESSAGES_TO_COMPACT,
        force,
        cancelled,
    )?
    else {
        return Ok(CompactionOutcome::Skipped);
    };
    if !retry.allows(plan.covered_count(), plan.source_units(), request.manual) {
        return Ok(CompactionOutcome::Skipped);
    }
    callbacks.busy(true);
    let result = compact_chunks(
        &request,
        &mut plan,
        window,
        retry,
        callbacks,
        request_budget.as_ref(),
    );
    callbacks.busy(false);
    result
}

fn compact_chunks(
    request: &CompactionRequest<'_>,
    plan: &mut context::ChunkedCompactionPlan<'_>,
    window: u64,
    retry: &mut CompactionRetryGate,
    callbacks: &mut impl CompactionCallbacks,
    request_budget: Option<&Arc<network::RequestBudget>>,
) -> Result<CompactionOutcome, String> {
    let cancelled =
        || request.parent_cancel.is_cancelled() || request.request_cancel.is_cancelled();
    let configured = request
        .settings
        .model_max_tokens
        .get(&request.settings.model)
        .copied()
        .filter(|&value| value > 0)
        .or_else(|| (request.settings.max_tokens > 0).then_some(request.settings.max_tokens));
    let output_tokens = (window / 4)
        .clamp(1, 10_000)
        .min(configured.map_or(u64::MAX, |value| value as u64));
    let output_units = (output_tokens.saturating_mul(3) as usize).min(MAX_SUMMARY_UNITS);
    let prompt_units = context::COMPRESS_PROMPT
        .chars()
        .map(char::len_utf16)
        .sum::<usize>();
    let input_units = window
        .saturating_sub(output_tokens)
        .saturating_mul(3)
        .min(usize::MAX as u64) as usize;
    let input_units = input_units.saturating_sub(prompt_units + 256);
    if input_units < 256 {
        return Err("模型窗口不足以执行分块摘要；原文保留".into());
    }
    let mut carry = String::new();
    let mut saved = None;
    let mut requests = 0usize;
    let mut usage = UsageReport::default();
    while !plan.finished() {
        if cancelled() {
            return Err("上下文压缩已停止；已确认检查点保留，部分原子摘要未发布".into());
        }
        let Some(chunk) = plan.next_chunk(&carry, 4 * 1024 * 1024, input_units, cancelled)? else {
            break;
        };
        let step = summarize_once(
            request,
            &chunk.transcript,
            chunk.source_units,
            output_tokens,
            output_units,
            callbacks,
            request_budget,
        )?;
        if step.requests > 0 {
            usage = add_usage(usage, step.usage, requests == 0);
            requests += step.requests;
        }
        let Some(body) = step.body else {
            let reason = step.reason.unwrap_or_else(|| "分块摘要未接受".into());
            retry.rejected(plan.covered_count(), plan.source_units());
            return Ok(CompactionOutcome::Rejected {
                reason,
                usage,
                requests,
            });
        };
        carry = body;
        // Keep accepted carries staged until this planned coverage is complete.
        // A failure retains the previous durable checkpoint and all original data.
        // This also avoids repeatedly hashing an ever-growing 512 MiB prefix.
        if chunk.checkpoint_boundary && plan.finished() {
            plan.verify_source(cancelled)?;
            let coverage = plan.checkpoint_plan(chunk.completed_atoms, cancelled)?;
            let checkpoint = callbacks.checkpoint(&coverage, &carry, usage)?;
            saved = Some(checkpoint);
        }
    }
    let checkpoint = saved.ok_or("压缩未完成任何完整消息或工具批次，暂存摘要未发布")?;
    retry.reset();
    Ok(CompactionOutcome::Saved {
        checkpoint,
        usage,
        requests,
    })
}

fn add_usage(previous: UsageReport, current: UsageReport, first: bool) -> UsageReport {
    if first {
        return current;
    }
    let add = |a: Option<i32>, b: Option<i32>| a.zip(b).and_then(|(a, b)| a.checked_add(b));
    UsageReport {
        prompt_tokens: add(previous.prompt_tokens, current.prompt_tokens),
        completion_tokens: add(previous.completion_tokens, current.completion_tokens),
        reasoning_tokens: add(previous.reasoning_tokens, current.reasoning_tokens),
        cached_tokens: add(previous.cached_tokens, current.cached_tokens),
    }
}
struct SummaryStep {
    body: Option<String>,
    reason: Option<String>,
    usage: UsageReport,
    requests: usize,
}

fn summarize_once(
    request: &CompactionRequest<'_>,
    transcript: &str,
    source_units: usize,
    output_tokens: u64,
    output_units: usize,
    callbacks: &mut impl CompactionCallbacks,
    request_budget: Option<&Arc<network::RequestBudget>>,
) -> Result<SummaryStep, String> {
    let mut settings = request.settings.clone();
    settings.max_tokens = output_tokens.min(i32::MAX as u64) as i32;
    settings
        .model_max_tokens
        .insert(settings.model.clone(), settings.max_tokens);
    let wire = vec![
        Message::system(context::COMPRESS_PROMPT),
        Message::user(transcript),
    ];
    let prepared = network::prepare_chat(&settings, &wire, &[])?;
    drop(wire);
    let policy = settings.behavior.retry_policy();
    let budget = request_budget.cloned().unwrap_or_else(|| {
        Arc::new(network::RequestBudget::new(
            policy.max_attempts as usize + 1,
        ))
    });
    let start_count = budget.used();
    let mut usage = UsageReport::default();
    let mut attempt = 0u32;
    loop {
        if request.parent_cancel.is_cancelled() || request.request_cancel.is_cancelled() {
            return Err("上下文压缩已停止；已结算的真实请求账单保留".into());
        }
        if !budget.can_admit() {
            return Ok(SummaryStep {
                body: None,
                reason: Some("实际模型请求预算已耗尽，未再发送分块摘要".into()),
                usage,
                requests: budget.used().saturating_sub(start_count),
            });
        }
        attempt += 1;
        let mut record = UsageRecord {
            id: format!(
                "{}-compression-{}",
                request.run,
                crate::controller::unique_id()
            ),
            conversation_id: String::new(),
            model: settings.model.clone(),
            at: crate::controller::now_millis(),
            prompt_tokens: None,
            completion_tokens: None,
            reasoning_tokens: None,
            cached_tokens: None,
            error: None,
            pending: true,
            summary: false,
        };
        if let Err(error) = callbacks.usage(record.clone()) {
            record.pending = false;
            record.error = Some("压缩Pending账单没有得到持久确认，未发送本请求".into());
            let finalized = callbacks.usage(record);
            return finalized.and(Err(error));
        }
        let mut text_units = 0usize;
        let mut reasoning_units = 0usize;
        let mut over_budget = false;
        let transport_cancel = Arc::new(CancelToken::new());
        let before = budget.used();
        let response = request.runtime.block_on(async {
            let pending = network::chat_prepared(
                &prepared,
                transport_cancel.clone(),
                |thinking, text| {
                    let units = if thinking {
                        &mut reasoning_units
                    } else {
                        &mut text_units
                    };
                    for ch in text.chars() {
                        *units = units.saturating_add(ch.len_utf16());
                        if *units > output_units {
                            over_budget = true;
                            transport_cancel.cancel();
                            break;
                        }
                    }
                },
                Some(budget.clone()),
            );
            tokio::pin!(pending);
            tokio::select! {biased;
                _=request.parent_cancel.cancelled()=>{transport_cancel.cancel();pending.await},
                _=request.request_cancel.cancelled()=>{transport_cancel.cancel();pending.await},
                response=&mut pending=>response,
            }
        });
        let sent = budget.used() > before;
        if sent {
            usage = add_usage(usage, response.turn.usage_report, before == start_count);
        }
        let decision = context::checked_summary(
            &response.turn.text,
            response.turn.finish_reason.as_deref(),
            source_units,
        );
        let reason = if over_budget {
            Some("分块摘要超过该模型输出预算，原文与先前摘要检查点保留".to_owned())
        } else if request.parent_cancel.is_cancelled() || request.request_cancel.is_cancelled() {
            Some("上下文压缩已停止，未报告的用量保持未知".to_owned())
        } else if let Some(error) = &response.error {
            Some(error.clone())
        } else if !response.turn.tool_calls.is_empty()
            || matches!(
                response.turn.finish_reason.as_deref(),
                Some("tool_calls" | "tool_use" | "function_call")
            )
        {
            Some("摘要返回了未提供的工具调用，未执行或提交摘要".to_owned())
        } else if !decision.accepted {
            Some(decision.reason.to_owned())
        } else {
            None
        };
        record.pending = false;
        record.prompt_tokens = response.turn.usage_report.prompt_tokens;
        record.completion_tokens = response.turn.usage_report.completion_tokens;
        record.reasoning_tokens = response.turn.usage_report.reasoning_tokens;
        record.cached_tokens = response.turn.usage_report.cached_tokens;
        record.error = reason.clone();
        // Final is unconditional after Pending; a storage failure stops here,
        // never causing a second HTTP request or a synthetic aggregate bill.
        callbacks.usage(record)?;
        if request.parent_cancel.is_cancelled() {
            return Err("主任务已停止；摘要最终账单已保存".into());
        }
        let retry = sent
            && !over_budget
            && response.error.is_some()
            && budget.can_admit()
            && response.retry.is_some_and(|cause| {
                cause.allows(
                    policy,
                    attempt,
                    request.parent_cancel.is_cancelled()
                        || request.request_cancel.is_cancelled()
                        || transport_cancel.is_cancelled(),
                )
            });
        if !retry {
            return Ok(SummaryStep {
                body: if reason.is_none() {
                    Some(decision.body.to_owned())
                } else {
                    None
                },
                reason,
                usage,
                requests: budget.used().saturating_sub(start_count),
            });
        }
        let delay = response.retry.expect("typed summary retry").delay(policy);
        // Partial summary prose never advances carry/coverage. Only the
        // identical frozen chunk request is regenerated after its Final bill.
        if !request.runtime.block_on(network::retry::wait(
            delay,
            request.parent_cancel,
            &request.request_cancel,
        )) {
            return Ok(SummaryStep {
                body: None,
                reason: Some("摘要重试等待已停止，未再次发送请求".into()),
                usage,
                requests: budget.used().saturating_sub(start_count),
            });
        }
    }
}
