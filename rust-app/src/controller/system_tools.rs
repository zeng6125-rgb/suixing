//! Actor caller for already-approved, frozen platform preparations. Provider
//! tool-call IDs remain model pairing IDs; callback ownership uses operation_id.
//! Raw values and JPEG bytes travel only through the requesting worker's reply.
use super::Runs;
use crate::{
    network::CancelToken,
    platform::{permissions::OperationKey, virtual_screen},
    platform_tools::{self, Outcome, Prepared},
    runtime_host::RunKey,
    state::ToolCall,
};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver, SyncSender, TryRecvError, TrySendError},
    },
    thread,
    time::{Duration, Instant},
};
const MAX_PENDING: usize = 32;
const DEADLINE: Duration = Duration::from_secs(60);
#[derive(Clone)]
struct Guard {
    run: RunKey,
    child_id: Option<String>,
    parent_cancel: Arc<CancelToken>,
    child_cancel: Option<Arc<CancelToken>>,
    operation_cancel: Arc<CancelToken>,
}
impl Guard {
    fn tokens_active(&self) -> bool {
        !self.parent_cancel.is_cancelled()
            && !self.operation_cancel.is_cancelled()
            && self
                .child_cancel
                .as_ref()
                .is_none_or(|cancel| !cancel.is_cancelled())
    }
    fn current(&self, runs: &Runs) -> bool {
        self.tokens_active()
            && runs.by_id.get(&self.run.run_id).is_some_and(|current| {
                current.conversation == self.run.conversation_id
                    && Arc::ptr_eq(&current.cancel, &self.parent_cancel)
                    && current.worker_is_active(self.child_id.as_deref())
                    && match (&self.child_id, &self.child_cancel) {
                        (None, None) => true,
                        (Some(id), Some(cancel)) => current
                            .children
                            .get(id)
                            .is_some_and(|child| Arc::ptr_eq(&child.cancel, cancel)),
                        _ => false,
                    }
            })
    }
}
struct Work {
    key: OperationKey,
    guard: Guard,
    prepared: Prepared,
}
struct Finished {
    key: OperationKey,
    result: Result<Outcome, String>,
}
struct Pending {
    key: OperationKey,
    guard: Guard,
    prepared: Prepared,
    reply: SyncSender<Result<Outcome, String>>,
    deadline: Instant,
    waiting_virtual: bool,
    early_virtual: Option<virtual_screen::Outcome>,
    pending_image: Option<platform_tools::Image>,
    result: Option<Result<Outcome, String>>,
    receipt: Option<Receipt>,
}
/// Optional authoritative receipt for root's UI/checkpoint integration. It is a
/// redacted projection and carries image metadata, never inline image payloads.
#[derive(Clone, Debug)]
pub(crate) struct Receipt {
    pub operation: OperationKey,
    pub run: RunKey,
    pub child_id: Option<String>,
    pub call: ToolCall,
    pub value: Value,
    pub reply_delivered: bool,
}
pub(crate) struct SystemTools {
    reader: SyncSender<Work>,
    effects: SyncSender<Work>,
    finished: Receiver<Finished>,
    pending: HashMap<String, Pending>,
    monitors: Arc<Mutex<HashMap<String, Guard>>>,
    alive: Arc<AtomicBool>,
}
impl Drop for SystemTools {
    fn drop(&mut self) {
        self.alive.store(false, Ordering::Release);
        for pending in self.pending.values() {
            pending.guard.operation_cancel.cancel();
            virtual_screen::cancel(&pending.key.id);
            let _ = pending
                .reply
                .try_send(Err("系统工具运行时已结束，操作效果尚未确认".into()));
        }
        self.monitors
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clear();
    }
}
fn worker(name: &str, finished: SyncSender<Finished>) -> Result<SyncSender<Work>, String> {
    let (sender, receiver) = mpsc::sync_channel::<Work>(16);
    thread::Builder::new()
        .name(name.into())
        .spawn(move || {
            while let Ok(work) = receiver.recv() {
                let result = if !work.guard.tokens_active() {
                    Err("所属任务已停止或工具等待超时，未派发新动作".into())
                } else {
                    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        platform_tools::execute(
                            &work.prepared,
                            work.key.clone(),
                            work.guard.operation_cancel.clone(),
                        )
                    }))
                    .map_err(|_| "系统工具线程异常终止，操作可能已有作用，请先核对".to_string())
                };
                if finished
                    .send(Finished {
                        key: work.key,
                        result,
                    })
                    .is_err()
                {
                    break;
                }
            }
        })
        .map_err(|_| "无法启动系统工具工作线程".to_string())?;
    Ok(sender)
}
impl SystemTools {
    pub(crate) fn boot() -> Result<Self, String> {
        let (finished_sender, finished) = mpsc::sync_channel(32);
        let reader = worker("rust-system-tool-read", finished_sender.clone())?;
        let effects = worker("rust-system-tool-effects", finished_sender)?;
        let monitors = Arc::new(Mutex::new(HashMap::<String, Guard>::new()));
        let alive = Arc::new(AtomicBool::new(true));
        let watched = monitors.clone();
        let running = alive.clone();
        thread::Builder::new()
            .name("rust-system-tool-cancel".into())
            .spawn(move || {
                while running.load(Ordering::Acquire) {
                    let guards = watched
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .values()
                        .cloned()
                        .collect::<Vec<_>>();
                    for guard in guards {
                        if !guard.tokens_active() {
                            guard.operation_cancel.cancel();
                        }
                    }
                    thread::park_timeout(Duration::from_millis(20));
                }
            })
            .map_err(|_| "无法启动系统工具取消管理".to_string())?;
        Ok(Self {
            reader,
            effects,
            finished,
            pending: HashMap::new(),
            monitors,
            alive,
        })
    }
    /// Root calls before taking the run out of Runs for another worker event.
    /// The approval event carries the original ToolCall; this accepts only its
    /// already-frozen Prepared and never prepares or selects another route.
    pub(crate) fn dispatch(
        &mut self,
        run: String,
        child_id: Option<String>,
        prepared: Prepared,
        reply: SyncSender<Result<Outcome, String>>,
        runs: &Runs,
    ) {
        let result = (|| {
            if self.pending.len() >= MAX_PENDING {
                return Err("系统工具待确认调用达到32个预算".to_string());
            }
            if self.pending.contains_key(prepared.operation_id()) {
                return Err("系统工具操作身份已在运行，未重复派发".into());
            }
            let current = runs
                .by_id
                .get(&run)
                .filter(|current| current.worker_is_active(child_id.as_deref()))
                .ok_or("系统工具所属父任务或子任务已经结束")?;
            let parent_cancel = current.cancel.clone();
            let child_cancel = child_id
                .as_ref()
                .map(|id| {
                    current
                        .children
                        .get(id)
                        .map(|child| child.cancel.clone())
                        .ok_or("系统工具所属子任务不存在")
                })
                .transpose()?;
            let run = RunKey {
                run_id: run.clone(),
                conversation_id: current.conversation.clone(),
            };
            run.validate()?;
            let key = OperationKey {
                id: prepared.operation_id().into(),
                conversation_id: Some(run.conversation_id.clone()),
                generation: 1,
            };
            key.validate()?;
            let operation_cancel = Arc::new(CancelToken::new());
            operation_cancel.set_deadline(DEADLINE);
            let guard = Guard {
                run,
                child_id,
                parent_cancel,
                child_cancel,
                operation_cancel,
            };
            if !guard.current(runs) {
                return Err("系统工具所属运行代次已改变".into());
            }
            Ok((key, guard))
        })();
        let (key, guard) = match result {
            Ok(value) => value,
            Err(error) => {
                let _ = reply.try_send(Err(error));
                return;
            }
        };
        let reader = prepared.is_read_only();
        let sender = if reader { &self.reader } else { &self.effects };
        let work = Work {
            key: key.clone(),
            guard: guard.clone(),
            prepared: prepared.clone(),
        };
        if sender.try_send(work).is_err() {
            guard.operation_cancel.cancel();
            let _ = reply.try_send(Err("系统工具工作队列繁忙，未派发新动作".into()));
            return;
        }
        self.monitors
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(key.id.clone(), guard.clone());
        self.pending.insert(
            key.id.clone(),
            Pending {
                key,
                guard,
                prepared,
                reply,
                deadline: Instant::now() + DEADLINE,
                waiting_virtual: false,
                early_virtual: None,
                pending_image: None,
                result: None,
                receipt: None,
            },
        );
    }
    pub(crate) fn tick(&mut self, runs: &Runs) -> Vec<Receipt> {
        for _ in 0..64 {
            match self.finished.try_recv() {
                Ok(finished) => self.finish_worker(finished),
                Err(TryRecvError::Empty | TryRecvError::Disconnected) => break,
            }
        }
        let keys = self.pending.keys().cloned().collect::<Vec<_>>();
        let mut receipts = Vec::new();
        for id in keys {
            let Some(mut pending) = self.pending.remove(&id) else {
                continue;
            };
            if !pending.guard.current(runs) || Instant::now() >= pending.deadline {
                pending.guard.operation_cancel.cancel();
                virtual_screen::cancel(&pending.key.id);
                pending.result = Some(Err(
                    "系统工具所属任务已结束或操作超时；效果可能已发生，请先核对再决定后续动作"
                        .into(),
                ));
                pending.receipt = None;
                pending.early_virtual = None;
                pending.pending_image = None;
            }
            if let Some(result) = pending.result.take() {
                let receipt = pending
                    .receipt
                    .take()
                    .unwrap_or_else(|| receipt(&pending, &result));
                match pending.reply.try_send(result) {
                    Ok(()) => {
                        let mut receipt = receipt;
                        receipt.reply_delivered = true;
                        receipts.push(receipt);
                        self.monitors
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .remove(&id);
                    }
                    Err(TrySendError::Disconnected(_)) => {
                        receipts.push(receipt);
                        pending.guard.operation_cancel.cancel();
                        virtual_screen::cancel(&pending.key.id);
                        self.monitors
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .remove(&id);
                    }
                    Err(TrySendError::Full(result)) => {
                        // Actor never blocks a renderer or another run on a bad
                        // reply consumer. The bounded pending slot retains it.
                        if Instant::now() >= pending.deadline {
                            receipts.push(receipt);
                            self.monitors
                                .lock()
                                .unwrap_or_else(|e| e.into_inner())
                                .remove(&id);
                        } else {
                            pending.result = Some(result);
                            pending.receipt = Some(receipt);
                            self.pending.insert(id, pending);
                        }
                    }
                }
            } else {
                self.pending.insert(id, pending);
            }
        }
        receipts
    }
    fn finish_worker(&mut self, finished: Finished) {
        let Some(pending) = self
            .pending
            .get_mut(&finished.key.id)
            .filter(|p| p.key == finished.key)
        else {
            return;
        };
        if pending.result.is_some() {
            return;
        }
        match finished.result {
            Err(error) => pending.result = Some(Err(error)),
            Ok(mut outcome) => {
                if outcome.transcript_call.id != pending.prepared.call_id()
                    || outcome.tool_name != pending.prepared.transcript_call.name
                {
                    pending.guard.operation_cancel.cancel();
                    virtual_screen::cancel(&pending.key.id);
                    pending.result =
                        Some(Err("系统工具结果与原调用身份不一致，未交给其它任务".into()));
                    return;
                }
                if let Some(waiting) = outcome.platform_pending.take() {
                    if waiting != pending.key || !pending.prepared.virtual_completion_expected() {
                        pending.guard.operation_cancel.cancel();
                        virtual_screen::cancel(&pending.key.id);
                        pending.result =
                            Some(Err("系统工具平台等待身份不一致，未转交其它调用".into()));
                        return;
                    }
                    pending.waiting_virtual = true;
                    pending.pending_image = outcome.image.take();
                    if let Some(early) = pending.early_virtual.take() {
                        pending.result = Some(finish_virtual(pending, early));
                    }
                } else {
                    pending.result = Some(Ok(outcome));
                }
            }
        }
    }
    /// None means this invocation owns and retained the result. Some returns an
    /// unclaimed result unchanged to root's automation/page or other dispatcher.
    pub(crate) fn route_virtual(
        &mut self,
        outcome: virtual_screen::Outcome,
        runs: &Runs,
    ) -> Option<virtual_screen::Outcome> {
        let Some(pending) = self
            .pending
            .get_mut(&outcome.operation.id)
            .filter(|pending| {
                pending.key == outcome.operation && pending.prepared.virtual_completion_expected()
            })
        else {
            return Some(outcome);
        };
        if pending.result.is_some() {
            return None;
        }
        if !pending.guard.current(runs) {
            pending.guard.operation_cancel.cancel();
            virtual_screen::cancel(&pending.key.id);
            pending.result = Some(Err("副屏结果所属任务已经结束，未交给其它任务".into()));
        } else if pending.waiting_virtual {
            pending.result = Some(finish_virtual(pending, outcome));
        } else if pending.early_virtual.is_none() {
            // A main-thread callback can arrive before the effect worker puts
            // its pending acknowledgement in our channel. Keep that exact key.
            pending.early_virtual = Some(outcome);
        }
        None
    }
}
fn finish_virtual(
    pending: &mut Pending,
    outcome: virtual_screen::Outcome,
) -> Result<Outcome, String> {
    let mut finished = platform_tools::finish_virtual(&pending.prepared, &pending.key, outcome)?;
    if finished.image.is_none() {
        finished.image = pending.pending_image.take();
    }
    Ok(finished)
}
fn receipt(pending: &Pending, result: &Result<Outcome, String>) -> Receipt {
    let value = match result {
        Ok(outcome) => outcome.transcript_value(None),
        Err(error) => json!({"ok":false,"error":error,"effect_unknown":true}),
    };
    Receipt {
        operation: pending.key.clone(),
        run: pending.guard.run.clone(),
        child_id: pending.guard.child_id.clone(),
        call: pending.prepared.transcript_call.clone(),
        value,
        reply_delivered: false,
    }
}
