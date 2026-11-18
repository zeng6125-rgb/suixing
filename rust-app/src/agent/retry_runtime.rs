//! Retry one immutable generation request. Each physical attempt gets its own
//! Pending/Final ledger; partial output remains on disk before a new attempt.
use super::*;
pub(super) const FAILED_ATTEMPT: &str = "rustFailedModelAttempt";
pub(crate) fn failed(message: &Message) -> bool {
    message
        .metadata
        .extra
        .get(FAILED_ATTEMPT)
        .is_some_and(|value| value.as_bool().unwrap_or(!value.is_null()))
}
pub(crate) fn preserve_failed(
    message: &mut Message,
    response: &network::ChatOutcome,
    request_id: &str,
    attempt: u32,
) {
    message.metadata.trace_note = Some(format!(
        "模型第{attempt}次请求中断，部分内容与实际用量已保留；重新生成当前轮次。{}",
        response.error.as_deref().unwrap_or("请求中断")
    ));
    message.metadata.extra.insert(FAILED_ATTEMPT.into(), json!({"request_id":request_id,"attempt":attempt,"error":response.error,"unexecuted_tool_calls":response.turn.tool_calls.len()}));
    // These uncommitted calls never become executable transcript declarations.
    message.tool_calls.clear();
}
#[allow(clippy::too_many_arguments)]
pub(super) fn request(
    run: &str,
    settings: &Settings,
    wire: Vec<rust_protocol::Message>,
    definitions: &[rust_protocol::ToolDefinition],
    runtime: &tokio::runtime::Runtime,
    tx: &SyncSender<WorkerEvent>,
    parent: &Arc<CancelToken>,
    request_cancel: Arc<CancelToken>,
    request_budget: Arc<network::RequestBudget>,
    outcome: &mut Outcome,
    document_capacity: usize,
    child_reserve: usize,
) -> Result<network::ChatOutcome, String> {
    let prepared = network::prepare_chat(settings, &wire, definitions)?;
    drop(wire);
    let policy = settings.behavior.retry_policy();
    let mut attempt = 0u32;
    loop {
        attempt += 1;
        if parent.is_cancelled() {
            return Err("任务已停止".into());
        }
        if !request_budget.can_admit() {
            return Err("实际模型请求预算已耗尽，未继续重试".into());
        }
        let mut record = crate::state::UsageRecord {
            id: format!("{run}-request-{}", crate::controller::unique_id()),
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
        request_usage(run, record.clone(), tx, parent)?;
        let current = outcome.messages.last().ok_or("缺少助手消息")?.id.clone();
        let started = Instant::now();
        let mut remaining = MAX_HISTORY_BYTES
            .saturating_sub(128 * 1024)
            .saturating_sub(history_bytes(&outcome.messages))
            .saturating_sub(child_reserve)
            .min(document_capacity);
        let mut callback_error = None;
        let defer_sensitive =
            crate::transcript_redaction::sensitive_turn_for(&outcome.messages).is_sensitive();
        let stop = request_cancel.clone();
        let response=runtime.block_on(async {
            let request=network::chat_prepared(&prepared,request_cancel.clone(),|thinking,text| {
                if callback_error.is_some() {return;}
                if text.len()>remaining {callback_error=Some("会话达到存储上限，已保留收到内容".to_owned());parent.cancel();return;}
                remaining-=text.len();
                if let Some(message)=outcome.messages.last_mut() {
                    if message.metadata.first_token_ms==0 && !text.is_empty() {message.metadata.first_token_ms=started.elapsed().as_millis().max(1).min(i64::MAX as u128) as i64;}
                    if thinking {message.thinking.push_str(text);}else{message.text.push_str(text);}
                }
                if !defer_sensitive {
                    if let Err(error)=send(tx,WorkerEvent::Delta {run:run.into(),message_id:current.clone(),thinking,text:text.into()},parent) {callback_error=Some(error);parent.cancel();}
                }
            },Some(request_budget.clone()));
            tokio::pin!(request);
            tokio::select! {biased; _=parent.cancelled()=> {stop.cancel();request.await}, response=&mut request=>response}
        });
        let usage = response.turn.usage_report;
        accumulate_request(outcome, usage);
        record.pending = false;
        record.prompt_tokens = usage.prompt_tokens;
        record.completion_tokens = usage.completion_tokens;
        record.reasoning_tokens = usage.reasoning_tokens;
        record.cached_tokens = usage.cached_tokens;
        record.error = response
            .error
            .clone()
            .or_else(|| callback_error.clone())
            .or_else(|| {
                parent
                    .is_cancelled()
                    .then(|| "请求已停止，未报告用量保持未知".into())
            });
        if let Some(message) = outcome.messages.last_mut() {
            message.metadata.elapsed_ms =
                started.elapsed().as_millis().min(i64::MAX as u128) as i64;
            message.metadata.prompt_tokens = usage.prompt_tokens.map(i64::from);
            message.metadata.completion_tokens = usage.completion_tokens.map(i64::from);
            message.metadata.reasoning_tokens = usage.reasoning_tokens.map(i64::from);
            message.metadata.cached_tokens = usage.cached_tokens.map(i64::from);
        }
        request_usage(run, record.clone(), tx, parent)?;
        if let Some(error) = callback_error {
            return Err(error);
        }
        let retry = response.error.is_some()
            && response.retry.is_some_and(|cause| {
                cause.allows(
                    policy,
                    attempt,
                    parent.is_cancelled() || request_cancel.is_cancelled(),
                )
            })
            && request_budget.can_admit();
        if !retry {
            return Ok(response);
        }
        // While waiting, this is still the latest incomplete generation. Only
        // a successfully admitted successor supersedes it in model context.
        checkpoint(run, &outcome.messages, outcome.usage, tx, parent)?;
        let delay = response.retry.expect("typed retry").delay(policy);
        if !runtime.block_on(network::retry::wait(delay, parent, &request_cancel)) {
            return Ok(response);
        }
        if parent.is_cancelled() || request_cancel.is_cancelled() || !request_budget.can_admit() {
            return Ok(response);
        }
        preserve_failed(
            outcome.messages.last_mut().ok_or("缺少失败尝试消息")?,
            &response,
            &record.id,
            attempt,
        );
        let mut next = assistant_placeholder();
        next.metadata.run_id = Some(run.into());
        next.metadata.model = Some(settings.model.clone());
        outcome.messages.push(next);
        checkpoint(run, &outcome.messages, outcome.usage, tx, parent)?;
    }
}
