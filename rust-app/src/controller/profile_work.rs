//! A separate summary worker, with actor-acknowledged real Pending/Final bills.
//! It survives source-run retirement; cancellation never discards final usage.
use crate::{
    network::CancelToken,
    platform::permissions::OperationKey,
    profile::{Preferences, Repository},
    profile_summary::{self, Coordinator, Key, Ledger, Plan, ResultRecord, Schedule, Scope},
    runtime_host::{Publisher, RunKey},
    state::{Event, Message, Settings, UsageRecord},
    store::Store,
};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
    mpsc::{self, Receiver, SyncSender, TryRecvError},
};

#[derive(Clone, Debug)]
pub(crate) enum Request {
    Inspect(OperationKey),
    Manual {
        operation: OperationKey,
        conversation_id: String,
    },
    Cancel(Key),
    RetryBilling(OperationKey),
}
#[derive(Clone, Debug)]
pub(crate) enum Update {
    Snapshot {
        operation: OperationKey,
        key: Option<Key>,
        billing_pending: bool,
    },
    Started {
        key: Key,
    },
    Finished {
        key: Key,
        result: Result<String, String>,
        billing_pending: bool,
    },
    Rejected {
        operation: OperationKey,
        error: String,
    },
}
enum WorkerMessage {
    Bill {
        key: Key,
        record: UsageRecord,
        reply: SyncSender<Result<(), String>>,
    },
    Finished {
        key: Key,
        result: Result<ResultRecord, String>,
    },
}
struct ActorLedger {
    key: Key,
    sender: SyncSender<WorkerMessage>,
}
impl Ledger for ActorLedger {
    fn save(&mut self, record: &UsageRecord) -> Result<(), String> {
        let (reply, receive) = mpsc::sync_channel(1);
        self.sender
            .send(WorkerMessage::Bill {
                key: self.key.clone(),
                record: record.clone(),
                reply,
            })
            .map_err(|_| "画像账单actor已退出，真实结果尚未保存".to_owned())?;
        receive
            .recv()
            .map_err(|_| "画像账单确认连接中断，须核对相同账单ID".to_owned())?
    }
}
struct Active {
    key: Key,
    namespace: String,
    cancel: Arc<CancelToken>,
    live: Arc<AtomicBool>,
    pending: Option<UsageRecord>,
    finalized: std::collections::BTreeMap<String, UsageRecord>,
    result: Option<ResultRecord>,
    lease: crate::runtime_host::BackgroundWorkLease,
}
pub(crate) struct Work {
    coordinator: Coordinator,
    active: Option<Active>,
    sender: SyncSender<WorkerMessage>,
    receiver: Receiver<WorkerMessage>,
}
impl Work {
    pub(crate) fn new() -> Self {
        let (sender, receiver) = mpsc::sync_channel(4);
        Self {
            coordinator: Coordinator::default(),
            active: None,
            sender,
            receiver,
        }
    }
    pub(crate) fn busy(&self) -> bool {
        self.active.is_some()
    }
    pub(crate) fn invalidate_namespace(&self) {
        if let Some(active) = &self.active {
            active.live.store(false, Ordering::Release);
            active.cancel.cancel();
        }
    }
    pub(crate) fn new_run_started(&self) {
        self.coordinator.new_run_started();
    }
    pub(crate) fn preferences_changed(&self, settings: &Settings) {
        self.coordinator.preferences_changed(&preferences(settings));
    }
    fn scope(&self, key: &Key) -> (Scope, Arc<AtomicBool>, Arc<CancelToken>) {
        let cancel = Arc::new(CancelToken::new());
        let live = Arc::new(AtomicBool::new(true));
        let actual_key = key.clone();
        let flag = live.clone();
        (
            Scope {
                cancel: cancel.clone(),
                valid: Arc::new(move |candidate| {
                    candidate == &actual_key && flag.load(Ordering::Acquire)
                }),
            },
            live,
            cancel,
        )
    }
    fn launch(
        &mut self,
        plan: Plan,
        namespace: String,
        live: Arc<AtomicBool>,
        cancel: Arc<CancelToken>,
        publish: &Publisher,
    ) -> Result<(), String> {
        let key = plan.key().clone();
        let lease = publish.register_background_work(
            RunKey {
                run_id: format!("profile-{}-{}", key.id, key.generation),
                conversation_id: key.conversation_id.clone(),
            },
            "整理用户画像".into(),
            crate::runtime_host::ServiceRunOrigin::ProfileSummary,
            cancel.clone(),
        )?;
        let worker_cancel = cancel.clone();
        let plan = self.coordinator.claim(plan)?;
        let sender = self.sender.clone();
        let worker_key = key.clone();
        self.active = Some(Active {
            key: key.clone(),
            namespace,
            cancel,
            live,
            pending: None,
            finalized: std::collections::BTreeMap::new(),
            result: None,
            lease,
        });
        let result = std::thread::Builder::new()
            .name("rust-profile-summary".into())
            .spawn(move || {
                let mut ledger = ActorLedger {
                    key: worker_key.clone(),
                    sender: sender.clone(),
                };
                let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    let runtime = tokio::runtime::Builder::new_current_thread()
                        .enable_all()
                        .build()
                        .map_err(|_| "画像请求运行时无法启动".to_owned())?;
                    runtime.block_on(async {
                        wait_for_protection(&worker_cancel).await?;
                        plan.execute(&mut ledger, super::now_millis).await
                    })
                }))
                .map_err(|_| "画像请求线程异常结束，账单保持实际已知状态".to_owned())
                .and_then(|result| result);
                let _ = sender.send(WorkerMessage::Finished {
                    key: worker_key,
                    result,
                });
            });
        if result.is_err() {
            self.release(&key);
            return Err("画像请求工作线程无法创建，未发送请求".into());
        }
        publish.emit(Event::ProfileSummary(Update::Started { key }));
        Ok(())
    }
    fn release(&mut self, key: &Key) {
        if self
            .active
            .as_ref()
            .is_some_and(|actual| &actual.key == key)
        {
            if let Some(actual) = self.active.take() {
                actual.live.store(false, Ordering::Release);
            }
            self.coordinator.finished(key);
        }
    }
    pub(crate) fn after_finished(
        &mut self,
        repository: &mut Repository,
        store: &mut Store,
        publish: &Publisher,
        source: RunKey,
        resolved: Settings,
        messages: &[Message],
        other_active_runs: usize,
    ) -> Result<(), String> {
        let prefs = preferences(&store.settings);
        let turn = store.record_finished_profile_turn(source.clone(), prefs.auto_profile)?;
        if !turn.eligible {
            return Ok(());
        }
        // Persist each missed eligible ordinal separately. A crash between the
        // clock and profile publication must not lose a counted finished turn.
        recover_counts(repository, store)?;
        if self.busy() {
            return Ok(());
        }
        let selected = &store
            .conversation(&source.conversation_id)?
            .metadata
            .active_runs;
        let messages: Vec<_> = crate::chat::projected_indices(messages, selected)
            .into_iter()
            .filter_map(|index| messages.get(index))
            .filter(|message| message.complete)
            .cloned()
            .collect();
        let key = Key {
            id: super::unique_id(),
            generation: turn.ordinal,
            conversation_id: source.conversation_id.clone(),
            source_run: Some(source),
            automatic: true,
        };
        let (scope, live, cancel) = self.scope(&key);
        let scheduled = profile_summary::after_finished_turn(
            repository,
            &prefs,
            key,
            resolved,
            &messages,
            scope,
            profile_summary::Eligibility {
                background_scope_available: true,
                other_active_runs,
                is_child: false,
                finished_turn_ordinal: turn.ordinal,
            },
        )?;
        if let Schedule::Ready(plan) = scheduled {
            self.launch(plan, store.active_namespace(), live, cancel, publish)?;
        }
        publish.emit(Event::Profile(crate::profile::Update::Changed {
            snapshot: repository.snapshot(),
        }));
        Ok(())
    }
    pub(crate) fn dispatch(
        &mut self,
        request: Request,
        repository: &mut Repository,
        store: &mut Store,
        publish: &Publisher,
    ) {
        match request {
            Request::Inspect(operation) => {
                if let Err(error) = operation.validate() {
                    publish.emit(Event::ProfileSummary(Update::Rejected { operation, error }));
                    return;
                }
                if operation.conversation_id.is_some() {
                    publish.emit(Event::ProfileSummary(Update::Rejected {
                        operation,
                        error: "画像整理状态检查属于全局设置页面".into(),
                    }));
                    return;
                }
                publish.emit(Event::ProfileSummary(Update::Snapshot {
                    operation,
                    key: self.active.as_ref().map(|active| active.key.clone()),
                    billing_pending: self
                        .active
                        .as_ref()
                        .is_some_and(|active| active.result.is_some()),
                }));
            }
            Request::Cancel(key) => {
                self.coordinator.cancel(&key);
            }
            Request::RetryBilling(operation) => {
                if let Err(error) = operation.validate() {
                    publish.emit(Event::ProfileSummary(Update::Rejected { operation, error }));
                    return;
                }
                if self.active.as_ref().is_none_or(|active| {
                    operation.id != active.key.id
                        || operation.generation != active.key.generation
                        || operation.conversation_id.as_deref()
                            != Some(active.key.conversation_id.as_str())
                }) {
                    publish.emit(Event::ProfileSummary(Update::Rejected {
                        operation,
                        error: "画像账单重试不属于当前精确整理操作".into(),
                    }));
                    return;
                }
                self.retry_billing(repository, store, publish);
            }
            Request::Manual {
                operation,
                conversation_id,
            } => {
                let result = (|| {
                    operation.validate()?;
                    if operation
                        .conversation_id
                        .as_deref()
                        .is_some_and(|id| id != conversation_id)
                    {
                        return Err("手动画像来源会话身份不一致".into());
                    }
                    if self.busy() {
                        return Err("画像请求或真实账单仍在结算".into());
                    }
                    let chat = store.conversation(&conversation_id)?.clone();
                    let (resolved, _) = store.settings.resolved_for(
                        chat.assistant_id.as_deref(),
                        chat.provider_id.as_deref(),
                        chat.model.as_deref(),
                    )?;
                    let raw_messages = store.messages(&conversation_id)?;
                    let messages: Vec<_> =
                        crate::chat::projected_indices(&raw_messages, &chat.metadata.active_runs)
                            .into_iter()
                            .filter_map(|index| raw_messages.get(index))
                            .filter(|message| message.complete)
                            .cloned()
                            .collect();
                    let key = Key {
                        id: operation.id.clone(),
                        generation: operation.generation,
                        conversation_id,
                        source_run: None,
                        automatic: false,
                    };
                    let (scope, live, cancel) = self.scope(&key);
                    let plan =
                        profile_summary::manual(repository, key, resolved, &messages, scope)?;
                    self.launch(plan, store.active_namespace(), live, cancel, publish)
                })();
                if let Err(error) = result {
                    publish.emit(Event::ProfileSummary(Update::Rejected { operation, error }));
                }
            }
        }
    }
    pub(crate) fn poll(
        &mut self,
        repository: &mut Repository,
        store: &mut Store,
        publish: &Publisher,
    ) {
        if let Some(active) = &self.active {
            if store.conversation(&active.key.conversation_id).is_err() {
                active.live.store(false, Ordering::Release);
                active.cancel.cancel();
            }
        }
        for _ in 0..4 {
            let event = match self.receiver.try_recv() {
                Ok(event) => event,
                Err(TryRecvError::Empty | TryRecvError::Disconnected) => break,
            };
            match event {
                WorkerMessage::Bill { key, record, reply } => {
                    let result = self.save_bill(&key, &record, store);
                    let _ = reply.try_send(result);
                }
                WorkerMessage::Finished { key, result } => {
                    if self.active.as_ref().is_none_or(|actual| actual.key != key) {
                        continue;
                    }
                    match result {
                        Ok(result) if result.billing_error.is_some() => {
                            let error = result.billing_error.clone().unwrap();
                            if let Some(active) = &mut self.active {
                                let _ = active.lease.set_state(
                                    crate::runtime_host::ServiceRunState::WaitingStorage,
                                );
                                active.result = Some(result);
                            }
                            publish.emit(Event::ProfileSummary(Update::Finished {
                                key,
                                result: Err(error),
                                billing_pending: true,
                            }));
                        }
                        Ok(result) => self.apply_result(repository, store, publish, result),
                        Err(error) => {
                            let pending = self
                                .active
                                .as_ref()
                                .and_then(|actual| actual.pending.clone());
                            if let Some(mut billing) = pending {
                                billing.pending = false;
                                billing.error = Some(error.clone());
                                if let Err(billing_error) = self.save_bill(&key, &billing, store) {
                                    // Retain the exact terminal bill for an explicit
                                    // storage-only retry, never another model request.
                                    let result = ResultRecord {
                                        key: key.clone(),
                                        expected: repository.revision(),
                                        operations: String::new(),
                                        request_error: Some(error),
                                        billing,
                                        billing_error: Some(billing_error.clone()),
                                    };
                                    if let Some(active) = &mut self.active {
                                        let _ = active.lease.set_state(
                                            crate::runtime_host::ServiceRunState::WaitingStorage,
                                        );
                                        active.result = Some(result);
                                    }
                                    publish.emit(Event::ProfileSummary(Update::Finished {
                                        key,
                                        result: Err(billing_error),
                                        billing_pending: true,
                                    }));
                                    continue;
                                }
                            }
                            self.release(&key);
                            publish.emit(Event::ProfileSummary(Update::Finished {
                                key,
                                result: Err(error),
                                billing_pending: false,
                            }));
                        }
                    }
                }
            }
        }
    }
    fn save_bill(
        &mut self,
        key: &Key,
        record: &UsageRecord,
        store: &mut Store,
    ) -> Result<(), String> {
        let actual = self
            .active
            .as_mut()
            .filter(|actual| &actual.key == key)
            .ok_or("画像账单操作身份已经过期")?;
        if actual.namespace != store.active_namespace()
            || record.conversation_id != key.conversation_id
            || record.summary
        {
            return Err("画像账单命名空间、ID或来源身份不一致".into());
        }
        if record.pending {
            if actual.cancel.is_cancelled() || !actual.live.load(Ordering::Acquire) {
                return Err("画像请求在账单准入前取消，未发送请求".into());
            }
            if actual.pending.is_some() {
                return Err("画像请求已经准入，不重复创建Pending账单".into());
            }
            let next = actual.finalized.len() + 1;
            if next > 6 {
                return Err("画像实际请求达到6次绝对上限，未再次发送".into());
            }
            let base = format!("profile-{}-{}", key.id, key.generation);
            let expected = if next == 1 {
                base
            } else {
                format!("{base}-r{next}")
            };
            if record.id != expected {
                return Err("画像Pending不是下一次实际请求的精确ID".into());
            }
            // Claim before IO so an uncertain persisted Pending can still be
            // finalized as unsent; the worker requires an actual save ack.
            actual.pending = Some(record.clone());
        } else {
            if let Some(finalized) = actual.finalized.get(&record.id) {
                if same_bill(finalized, record) {
                    return Ok(());
                }
                return Err("已结算画像请求的重复Final改变了真实账单字段".into());
            }
            let pending = actual
                .pending
                .as_ref()
                .ok_or("画像Final没有同ID的实际Pending准入")?;
            if pending.id != record.id || pending.at != record.at || pending.model != record.model {
                return Err("画像Final改变了Pending请求身份".into());
            }
        }
        // Final persists even if cancel/live changed after Pending admission.
        store.save_usage(record.clone())?;
        if !record.pending {
            actual.finalized.insert(record.id.clone(), record.clone());
            actual.pending = None;
        }
        Ok(())
    }
    fn apply_result(
        &mut self,
        repository: &mut Repository,
        store: &Store,
        publish: &Publisher,
        result: ResultRecord,
    ) {
        let scope_live = self.active.as_ref().is_some_and(|actual| {
            actual.key == result.key
                && actual.namespace == store.active_namespace()
                && actual.live.load(Ordering::Acquire)
                && !actual.cancel.is_cancelled()
        });
        let outcome = store
            .profile_writable()
            .and_then(|_| {
                profile_summary::apply(
                    repository,
                    &result,
                    &result.key,
                    scope_live,
                    super::now_millis(),
                )
            })
            .map(|outcome| match outcome {
                crate::profile::SummaryOutcome::Updated => "画像已更新".to_owned(),
                crate::profile::SummaryOutcome::Unchanged => {
                    "没有新的长期画像信息，当前画像保留".to_owned()
                }
            });
        self.release(&result.key);
        publish.emit(Event::Usage(store.usage()));
        publish.emit(Event::Profile(crate::profile::Update::Changed {
            snapshot: repository.snapshot(),
        }));
        publish.emit(Event::ProfileSummary(Update::Finished {
            key: result.key,
            result: outcome,
            billing_pending: false,
        }));
    }
    fn retry_billing(
        &mut self,
        repository: &mut Repository,
        store: &mut Store,
        publish: &Publisher,
    ) {
        let Some(mut result) = self
            .active
            .as_ref()
            .and_then(|active| active.result.clone())
        else {
            return;
        };
        match self.save_bill(&result.key, &result.billing, store) {
            Ok(()) => {
                result.billing_error = None;
                self.apply_result(repository, store, publish, result);
            }
            Err(error) => publish.emit(Event::ProfileSummary(Update::Finished {
                key: result.key,
                result: Err(error),
                billing_pending: true,
            })),
        }
    }
}
fn same_bill(a: &UsageRecord, b: &UsageRecord) -> bool {
    a.id == b.id
        && a.conversation_id == b.conversation_id
        && a.model == b.model
        && a.at == b.at
        && a.prompt_tokens == b.prompt_tokens
        && a.completion_tokens == b.completion_tokens
        && a.reasoning_tokens == b.reasoning_tokens
        && a.cached_tokens == b.cached_tokens
        && a.error == b.error
        && a.pending == b.pending
        && a.summary == b.summary
}
impl Drop for Work {
    fn drop(&mut self) {
        if let Some(active) = &self.active {
            active.live.store(false, Ordering::Release);
            active.cancel.cancel();
        }
    }
}
pub(crate) fn preferences(settings: &Settings) -> Preferences {
    Preferences {
        auto_profile: settings.behavior.auto_profile,
        profile_every_turns: i64::from(settings.behavior.profile_every_turns),
        inject_profile: settings.behavior.inject_profile,
    }
}
pub(crate) fn recover_counts(repository: &mut Repository, store: &Store) -> Result<(), String> {
    let turns = store.unconsumed_profile_turns(repository.document().completed_turn_ordinal)?;
    if !turns.is_empty() {
        store.profile_writable()?;
    }
    for turn in turns {
        repository.bump_finished_turn(turn.ordinal, true, false)?;
    }
    Ok(())
}

async fn wait_for_protection(cancel: &Arc<CancelToken>) -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        let started = std::time::Instant::now();
        loop {
            if cancel.is_cancelled() {
                return Err("画像请求在后台保护确认前取消，未发送请求".into());
            }
            let status = crate::platform::background::status();
            if status.foreground && status.wake_lock_held && !status.wake_lock_uncertain {
                return Ok(());
            }
            if let Some(error) = status.last_error {
                return Err(format!("画像后台保护尚未确认，未发送请求：{error}"));
            }
            if started.elapsed() >= std::time::Duration::from_secs(5) {
                return Err("画像后台前台服务/CPU租约未在5秒内实际确认，未发送请求".into());
            }
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        // The process-owned host lease applies here; no Android FGS/WakeLock
        // capability is inferred from a successful desktop worker launch.
        if cancel.is_cancelled() {
            return Err("画像请求在宿主准入前取消".into());
        }
        Ok(())
    }
}
