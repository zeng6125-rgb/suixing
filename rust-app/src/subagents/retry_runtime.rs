use super::*;
impl ChildRun<'_> {
    pub(super) fn generation(
        &self,
        record: &mut Record,
        settings: &Settings,
        wire: Vec<WireMessage>,
        definitions: &[ToolDefinition],
        runtime: &tokio::runtime::Runtime,
        request_budget: Arc<network::RequestBudget>,
    ) -> Result<network::ChatOutcome, String> {
        let prepared = network::prepare_chat(settings, &wire, definitions)?;
        drop(wire);
        let policy = settings.behavior.retry_policy();
        let request_cancel = Arc::new(CancelToken::new());
        let mut attempt = 0u32;
        let mut previous: Option<(network::ChatOutcome, String, u32)> = None;
        loop {
            self.check_running()?;
            if record_size(record)?
                > RECORD_BUDGET
                    .saturating_sub(ANSWER_RESERVE)
                    .saturating_sub(PENDING_RESERVE)
            {
                return Err("子任务记录没有空间保留另一次生成，未重试".into());
            }
            if !request_budget.can_admit() || record.usage.len() >= MAX_USAGE_RECORDS {
                return Err("子任务实际请求预算已用完，未继续重试".into());
            }
            attempt += 1;
            // Supersession and the successor are committed in one checkpoint.
            // A stop or exhausted capacity during the delay keeps the latest
            // partial generation eligible for later context as before.
            if let Some((response, id, failed_attempt)) = previous.take() {
                agent::retry_runtime::preserve_failed(
                    record.messages.last_mut().ok_or("缺少子任务失败尝试消息")?,
                    &response,
                    &id,
                    failed_attempt,
                );
            }
            let request_id = format!("{}-usage-{}", record.id, record.usage.len());
            record.usage.push(UsageRecord {
                id: request_id.clone(),
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
            });
            let mut answer = agent::assistant_placeholder();
            answer.metadata.run_id = Some(record.id.clone());
            answer.metadata.model = Some(settings.model.clone());
            record.messages.push(answer);
            if let Err(error) = self.checkpoint(record) {
                let pending = record.usage.last_mut().expect("child unsent request");
                pending.pending = false;
                pending.error = Some("请求前持久化失败，子请求未发送".into());
                return Err(error);
            }
            let defer_sensitive =
                crate::transcript_redaction::sensitive_turn_for(&record.messages).is_sensitive();
            let message_id = record.messages.last().expect("child reply").id.clone();
            let mut deltas = ChildDeltas::new(self.run, &record.id, &message_id, self.tx);
            let answer = record.messages.last_mut().expect("child reply");
            let started = Instant::now();
            let mut text_units = 0usize;
            let mut thinking_units = 0usize;
            let mut text_truncated = false;
            let mut thinking_truncated = false;
            let response=runtime.block_on(async {
                let pending=network::chat_prepared(&prepared,request_cancel.clone(),|thinking,text| {
                    if answer.metadata.first_token_ms==0 && !text.is_empty() {answer.metadata.first_token_ms=started.elapsed().as_millis().max(1).min(i64::MAX as u128) as i64;}
                    let (body,units,truncated)=if thinking {(&mut answer.thinking,&mut thinking_units,&mut thinking_truncated)}else{(&mut answer.text,&mut text_units,&mut text_truncated)};
                    if *truncated{return;}
                    let prefix=utf16_prefix(text,MAX_RESULT_UNITS.saturating_sub(*units));*units+=prefix.encode_utf16().count();*truncated=prefix.len()!=text.len();body.push_str(prefix);
                    if !defer_sensitive {deltas.append(thinking,prefix);}
                    if *truncated{request_cancel.cancel();}
                },Some(request_budget.clone()));
                tokio::pin!(pending);
                tokio::select! {biased; _=self.cancel.cancelled()=>{request_cancel.cancel();pending.await},response=&mut pending=>response}
            });
            if text_truncated {
                answer.text.push_str(RESULT_TRUNCATED);
                deltas.append(false, RESULT_TRUNCATED);
            }
            if thinking_truncated {
                answer.thinking.push_str(THINKING_TRUNCATED);
                deltas.append(true, THINKING_TRUNCATED);
            }
            deltas.flush(true);
            drop(deltas);
            let usage = response.turn.usage_report;
            answer.metadata.elapsed_ms = started.elapsed().as_millis().min(i64::MAX as u128) as i64;
            answer.metadata.prompt_tokens = usage.prompt_tokens.map(i64::from);
            answer.metadata.completion_tokens = usage.completion_tokens.map(i64::from);
            answer.metadata.reasoning_tokens = usage.reasoning_tokens.map(i64::from);
            answer.metadata.cached_tokens = usage.cached_tokens.map(i64::from);
            let ledger = record.usage.last_mut().expect("child request");
            ledger.pending = false;
            ledger.prompt_tokens = usage.prompt_tokens;
            ledger.completion_tokens = usage.completion_tokens;
            ledger.reasoning_tokens = usage.reasoning_tokens;
            ledger.cached_tokens = usage.cached_tokens;
            ledger.error = response.error.clone();
            self.checkpoint(record)?;
            self.check_running()?;
            let retry = response.error.is_some()
                && response.retry.is_some_and(|cause| {
                    cause.allows(
                        policy,
                        attempt,
                        self.cancel.is_cancelled() || request_cancel.is_cancelled(),
                    )
                })
                && request_budget.can_admit()
                && record.usage.len() < MAX_USAGE_RECORDS;
            if !retry {
                return Ok(response);
            }
            self.checkpoint(record)?;
            if !runtime.block_on(network::retry::wait(
                response.retry.expect("typed retry").delay(policy),
                self.cancel,
                &request_cancel,
            )) {
                return Ok(response);
            }
            previous = Some((response, request_id, attempt));
        }
    }
}
