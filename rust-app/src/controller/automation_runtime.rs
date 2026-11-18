//! Actor-owned automation page adapter. All blocking platform queries and effects
//! run on bounded workers; the actor matches complete operation identities.
//! Root owns shared platform outcome drains and routes each outcome here once.
use crate::{
    network::CancelToken,
    platform::{
        self,
        permissions::OperationKey,
        virtual_screen::{Frame, Identity, ManualInput},
    },
    platform_tools::{
        Context, RoutePolicy,
        automation_page::{self as page, Command, CommandKind, Preferences, ResultKind, Snapshot},
    },
};
use std::{
    collections::HashMap,
    sync::{
        Arc,
        mpsc::{self, Receiver, SyncSender, TryRecvError},
    },
    thread,
    time::{Duration, Instant},
};

#[derive(Clone, Debug)]
pub(crate) enum Request {
    Execute(Command),
    Cancel(OperationKey),
    PageVisible(bool),
}
#[derive(Clone)]
pub(crate) enum Completion {
    Snapshot(Arc<Snapshot>),
    Frame(Arc<Frame>),
    Applications(Arc<Vec<platform::applications::Application>>),
    Accepted,
    Manual {
        accepted: bool,
        completion_observed: bool,
        message: String,
    },
}
#[derive(Clone)]
pub(crate) enum Update {
    Snapshot(Arc<Snapshot>),
    Pending(OperationKey),
    Completed {
        operation: OperationKey,
        completion: Completion,
    },
    Failed {
        operation: OperationKey,
        message: String,
    },
    PreferencesConfirmed {
        operation: Option<OperationKey>,
        preferences: Preferences,
    },
    ManualAck {
        operation: OperationKey,
        accepted: bool,
        completion_observed: bool,
        message: String,
    },
    Notice(String),
}
impl std::fmt::Debug for Update {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Snapshot(_) => f.write_str("AutomationSnapshot"),
            Self::Pending(key) => f.debug_tuple("AutomationPending").field(key).finish(),
            Self::Completed { operation, .. } => f
                .debug_tuple("AutomationCompleted")
                .field(operation)
                .finish(),
            Self::Failed { operation, .. } => {
                f.debug_tuple("AutomationFailed").field(operation).finish()
            }
            Self::PreferencesConfirmed { operation, .. } => f
                .debug_tuple("AutomationPreferencesConfirmed")
                .field(operation)
                .finish(),
            Self::ManualAck {
                operation,
                accepted,
                completion_observed,
                ..
            } => f
                .debug_struct("AutomationManualAck")
                .field("operation", operation)
                .field("accepted", accepted)
                .field("completion_observed", completion_observed)
                .finish(),
            Self::Notice(_) => f.write_str("AutomationNotice"),
        }
    }
}
impl Update {
    /// Root events mailbox must charge decoded frames to its existing byte cap.
    pub(crate) fn bytes(&self) -> usize {
        match self {
            Self::Completed {
                completion: Completion::Frame(frame),
                ..
            } => frame.pixels.rgba.len().saturating_add(1024),
            Self::Completed {
                completion: Completion::Applications(apps),
                ..
            } => apps
                .iter()
                .map(|a| a.package.len() + a.label.len() + a.component.len() + 128)
                .sum(),
            Self::Failed { message, .. }
            | Self::ManualAck { message, .. }
            | Self::Notice(message) => message.len().saturating_add(1024),
            _ => 8192,
        }
    }
}
pub(crate) struct PreferenceWrite {
    pub operation: OperationKey,
    pub preferences: Preferences,
}
#[derive(Default)]
pub(crate) struct Batch {
    pub updates: Vec<Update>,
    pub preferences_to_save: Vec<PreferenceWrite>,
}
pub(crate) enum PlatformResult {
    Permission(platform::permissions::PermissionResult),
    Shizuku(platform::shizuku::PermissionResult),
    Virtual(platform::virtual_screen::Outcome),
}
impl PlatformResult {
    fn key(&self) -> &OperationKey {
        match self {
            Self::Permission(result) => &result.operation,
            Self::Shizuku(result) => &result.operation,
            Self::Virtual(result) => &result.operation,
        }
    }
}
#[derive(Clone, Copy, PartialEq, Eq)]
enum Source {
    Page,
    Native,
    Poll,
}
struct Pending {
    key: OperationKey,
    kind: CommandKind,
    source: Source,
    cancel: Arc<CancelToken>,
    started: Instant,
    platform_pending: bool,
    save_pending: bool,
}
struct Work {
    command: Command,
    context: Arc<Context>,
    cancel: Arc<CancelToken>,
    frozen_target: Option<platform::automation::Target>,
}
enum Job {
    Command(Work),
    ApplyPreferences(Preferences),
}
enum Reply {
    Command(page::Result),
    PreferencesApplied(Result<(), String>),
}
pub(crate) struct AutomationRuntime {
    generation: u64,
    read: SyncSender<Job>,
    effect: SyncSender<Job>,
    replies: Receiver<Reply>,
    pending: HashMap<String, Pending>,
    preferences: Preferences,
    snapshot: Option<Arc<Snapshot>>,
    snapshot_fingerprint: String,
    last_poll: Instant,
    page_visible: bool,
    preferences_announced: bool,
    driving_runs: usize,
    indicator_keys: Vec<OperationKey>,
}
impl Drop for AutomationRuntime {
    fn drop(&mut self) {
        for pending in self.pending.values() {
            pending.cancel.cancel();
            cancel_platform(&pending.key);
        }
        #[cfg(target_os = "android")]
        for key in &self.indicator_keys {
            let _ = platform::automation::overlay::set_driving(key.clone(), false);
        }
    }
}
fn worker(name: &str, output: SyncSender<Reply>) -> Result<SyncSender<Job>, String> {
    let (sender, receiver) = mpsc::sync_channel::<Job>(8);
    thread::Builder::new()
        .name(name.into())
        .spawn(move || {
            while let Ok(job) = receiver.recv() {
                let reply = match job {
                    Job::Command(work) => {
                        let operation = work.command.operation.clone();
                        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                            if let Some(target) = &work.frozen_target {
                                if let Err(error) = verify_frozen(target, &work.context) {
                                    return page::Result {
                                        operation: operation.clone(),
                                        result: Err(error),
                                    };
                                }
                            }
                            let mut reply = page::execute(work.command, &work.context, work.cancel);
                            if reply.result.is_ok() {
                                if let Some(target) = &work.frozen_target {
                                    if let Err(error) = verify_frozen(target, &work.context) {
                                        reply.result = Err(format!(
                                            "系统可能已接受操作，但完成时目标已改变：{error}"
                                        ));
                                    }
                                }
                            }
                            reply
                        }))
                        .unwrap_or_else(|_| page::Result {
                            operation,
                            result: Err("自动化操作线程异常终止，结果尚未确认".into()),
                        });
                        Reply::Command(result)
                    }
                    Job::ApplyPreferences(preferences) => Reply::PreferencesApplied(
                        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                            page::apply_saved_preferences(&preferences)
                        }))
                        .unwrap_or_else(|_| Err("已保存偏好的系统应用结果尚未确认".into())),
                    ),
                };
                if output.send(reply).is_err() {
                    break;
                }
            }
        })
        .map_err(|_| "无法启动自动化操作线程".to_string())?;
    Ok(sender)
}
impl AutomationRuntime {
    pub(crate) fn boot(generation: u64, preferences: Preferences) -> Result<Self, String> {
        if generation == 0 {
            return Err("自动化运行代次无效".into());
        }
        let (output, replies) = mpsc::sync_channel(32);
        let read = worker("rust-automation-read", output.clone())?;
        let effect = worker("rust-automation-effects", output)?;
        effect
            .try_send(Job::ApplyPreferences(preferences.clone()))
            .map_err(|_| "无法派发自动化偏好初始化".to_string())?;
        Ok(Self {
            generation,
            read,
            effect,
            replies,
            pending: HashMap::new(),
            preferences,
            snapshot: None,
            snapshot_fingerprint: String::new(),
            last_poll: Instant::now() - Duration::from_secs(1),
            page_visible: false,
            preferences_announced: false,
            driving_runs: 0,
            indicator_keys: Vec::new(),
        })
    }
    pub(crate) fn replay(&self) -> Vec<Update> {
        let mut updates = vec![Update::PreferencesConfirmed {
            operation: None,
            preferences: self.preferences.clone(),
        }];
        if let Some(snapshot) = &self.snapshot {
            updates.push(Update::Snapshot(snapshot.clone()));
        }
        updates
    }
    pub(crate) fn dispatch(&mut self, request: Request, context: Arc<Context>) -> Batch {
        let mut batch = Batch::default();
        match request {
            Request::Execute(command) => self.submit(command, Source::Page, context, &mut batch),
            Request::Cancel(key) => {
                if self.pending.get(&key.id).is_some_and(|p| p.key == key) {
                    self.fail(
                        &key,
                        "页面操作已取消；已接受的系统效果仍需查看实际结果".into(),
                        &mut batch,
                    );
                }
            }
            Request::PageVisible(visible) => {
                self.page_visible = visible;
                if !visible {
                    let keys = self
                        .pending
                        .values()
                        .filter(|p| matches!(p.kind, CommandKind::ReadFrame(_)))
                        .map(|p| p.key.clone())
                        .collect::<Vec<_>>();
                    for key in keys {
                        self.fail(&key, "页面预览已暂停".into(), &mut batch);
                    }
                } else {
                    self.last_poll = Instant::now() - Duration::from_secs(1);
                }
            }
        }
        batch
    }
    fn submit(
        &mut self,
        command: Command,
        source: Source,
        context: Arc<Context>,
        batch: &mut Batch,
    ) {
        let key = command.operation.clone();
        if let Err(error) = key.validate() {
            batch.updates.push(Update::Failed {
                operation: key,
                message: error,
            });
            return;
        }
        if key.generation != self.generation || key.conversation_id.is_some() {
            batch.updates.push(Update::Failed {
                operation: key,
                message: "页面操作不属于当前自动化运行代次".into(),
            });
            return;
        }
        if self.pending.contains_key(&key.id) {
            batch.updates.push(Update::Failed {
                operation: key,
                message: "页面操作身份重复，未重新执行".into(),
            });
            return;
        }
        if matches!(
            command.kind,
            CommandKind::Virtual(platform::virtual_screen::Action::Open { .. })
        ) {
            batch.updates.push(Update::Failed {
                operation: key,
                message: "页面开启副屏必须携带预期资源身份".into(),
            });
            return;
        }
        if self.pending.len() >= 16 {
            reject(source, key, "自动化操作队列繁忙".into(), batch);
            return;
        }
        if matches!(command.kind, CommandKind::ReadFrame(_))
            && (!self.page_visible
                || self
                    .pending
                    .values()
                    .any(|p| matches!(p.kind, CommandKind::ReadFrame(_))))
        {
            batch.updates.push(Update::Failed {
                operation: key,
                message: "预览已暂停或上一帧仍在读取".into(),
            });
            return;
        }
        // Actor checks complete native identity before admitting the action. The
        // worker repeats physical-display and service checks before each effect.
        if let Some(expected) = expected_identity(&command.kind) {
            let state = platform::virtual_screen::status();
            let closing_allowed = matches!(
                command.kind,
                CommandKind::Virtual(platform::virtual_screen::Action::Close { .. })
                    | CommandKind::Manual(ManualInput::Close { .. })
            );
            if state.identity.as_ref() != Some(expected) || (state.closing && !closing_allowed) {
                let message = "操作所属虚拟屏已变更，未转向主屏".into();
                if source == Source::Native {
                    batch.updates.push(Update::ManualAck {
                        operation: key,
                        accepted: false,
                        completion_observed: false,
                        message,
                    });
                } else {
                    batch.updates.push(Update::Failed {
                        operation: key,
                        message,
                    });
                }
                return;
            }
        }
        let cancel = Arc::new(CancelToken::new());
        let save = matches!(command.kind, CommandKind::SavePreferences(_));
        let pending = Pending {
            key: key.clone(),
            kind: command.kind.clone(),
            source,
            cancel: cancel.clone(),
            started: Instant::now(),
            platform_pending: false,
            save_pending: save,
        };
        if let CommandKind::SavePreferences(preferences) = &command.kind {
            if self.pending.values().any(|p| p.save_pending) {
                batch.updates.push(Update::Failed {
                    operation: key,
                    message: "上一偏好仍在保存".into(),
                });
                return;
            }
            batch.preferences_to_save.push(PreferenceWrite {
                operation: key.clone(),
                preferences: preferences.clone(),
            });
            self.pending.insert(key.id.clone(), pending);
            batch.updates.push(Update::Pending(key));
            return;
        }
        let reader = matches!(
            command.kind,
            CommandKind::Refresh | CommandKind::ReadFrame(_) | CommandKind::LoadApplications
        );
        let frozen_target = if matches!(
            command.kind,
            CommandKind::Manual(ManualInput::Tap { .. } | ManualInput::Swipe { .. })
        ) {
            expected_identity(&command.kind).map(|identity| {
                let capabilities = platform::automation::capabilities();
                platform::automation::Target {
                    display_id: identity.display_id,
                    service_generation: if capabilities.connected {
                        capabilities.service_generation
                    } else {
                        0
                    },
                    width: identity.width,
                    height: identity.height,
                    display_generation: Some(identity.generation),
                }
            })
        } else {
            None
        };
        let sender = if reader { &self.read } else { &self.effect };
        if sender
            .try_send(Job::Command(Work {
                command,
                context,
                cancel,
                frozen_target,
            }))
            .is_err()
        {
            reject(source, key, "自动化工作队列繁忙，未派发操作".into(), batch);
            return;
        }
        self.pending.insert(key.id.clone(), pending);
        if source == Source::Page {
            batch.updates.push(Update::Pending(key));
        }
    }
    pub(crate) fn tick(&mut self, context: Arc<Context>, driving_runs: usize) -> Batch {
        let mut batch = Batch::default();
        if !self.preferences_announced {
            self.preferences_announced = true;
            batch.updates.extend(self.replay());
        }
        #[cfg(target_os = "android")]
        if self.driving_runs != driving_runs {
            while self.indicator_keys.len() > driving_runs.min(32) {
                if let Some(key) = self.indicator_keys.pop() {
                    if let Err(error) = platform::automation::overlay::set_driving(key, false) {
                        batch.updates.push(Update::Notice(error));
                    }
                }
            }
            while self.indicator_keys.len() < driving_runs.min(32) {
                let key = OperationKey {
                    id: super::unique_id(),
                    conversation_id: None,
                    generation: self.generation,
                };
                if let Err(error) = platform::automation::overlay::set_driving(key.clone(), true) {
                    batch.updates.push(Update::Notice(error));
                    break;
                }
                self.indicator_keys.push(key);
            }
        }
        self.driving_runs = driving_runs;
        for _ in 0..64 {
            match self.replies.try_recv() {
                Ok(Reply::PreferencesApplied(Err(error))) => {
                    batch.updates.push(Update::Notice(error))
                }
                Ok(Reply::PreferencesApplied(Ok(()))) => {}
                Ok(Reply::Command(reply)) => self.complete(reply, &mut batch),
                Err(TryRecvError::Empty | TryRecvError::Disconnected) => break,
            }
        }
        let state = platform::virtual_screen::status();
        let stale = self
            .pending
            .values()
            .filter(|pending| {
                let timeout = if pending.platform_pending {
                    Duration::from_secs(120)
                } else {
                    Duration::from_secs(30)
                };
                let closing = matches!(
                    pending.kind,
                    CommandKind::Virtual(platform::virtual_screen::Action::Close { .. })
                        | CommandKind::Manual(ManualInput::Close { .. })
                );
                pending.started.elapsed() > timeout
                    || expected_identity(&pending.kind).is_some_and(|expected| {
                        state.identity.as_ref() != Some(expected)
                            && !(closing && state.identity.is_none())
                    })
            })
            .map(|pending| pending.key.clone())
            .collect::<Vec<_>>();
        for key in stale {
            self.fail(
                &key,
                "系统结果等待超时或所属屏幕已改变；未切换到其它屏幕".into(),
                &mut batch,
            );
        }
        for input in platform::virtual_screen::drain_inputs() {
            let key = OperationKey {
                id: super::unique_id(),
                conversation_id: None,
                generation: self.generation,
            };
            self.submit(
                Command {
                    operation: key,
                    kind: CommandKind::Manual(input),
                },
                Source::Native,
                context.clone(),
                &mut batch,
            );
        }
        let poll_interval = if self.page_visible {
            Duration::from_millis(500)
        } else {
            Duration::from_secs(2)
        };
        if self.last_poll.elapsed() >= poll_interval
            && !self
                .pending
                .values()
                .any(|p| matches!(p.kind, CommandKind::Refresh))
        {
            self.last_poll = Instant::now();
            self.submit(
                Command {
                    operation: OperationKey {
                        id: super::unique_id(),
                        conversation_id: None,
                        generation: self.generation,
                    },
                    kind: CommandKind::Refresh,
                },
                Source::Poll,
                context,
                &mut batch,
            );
        }
        batch
    }
    fn complete(&mut self, reply: page::Result, batch: &mut Batch) {
        let Some(pending) = self
            .pending
            .get(&reply.operation.id)
            .filter(|p| p.key == reply.operation)
        else {
            return;
        };
        if pending.cancel.is_cancelled() {
            self.fail(&reply.operation, "自动化操作已取消".into(), batch);
            return;
        }
        if let Some(expected) = expected_identity(&pending.kind) {
            let closed = matches!(
                pending.kind,
                CommandKind::Virtual(platform::virtual_screen::Action::Close { .. })
                    | CommandKind::Manual(ManualInput::Close { .. })
            );
            if !closed && platform::virtual_screen::status().identity.as_ref() != Some(expected) {
                self.fail(
                    &reply.operation,
                    "结果所属副屏已变更，未发布过期结果".into(),
                    batch,
                );
                return;
            }
        }
        match reply.result {
            Err(error) => self.fail(&reply.operation, error, batch),
            Ok(ResultKind::PendingPlatform(key)) => {
                if key != reply.operation {
                    self.fail(&reply.operation, "平台等待操作身份不一致".into(), batch);
                    return;
                }
                if let Some(pending) = self.pending.get_mut(&key.id) {
                    pending.platform_pending = true;
                }
            }
            Ok(ResultKind::PreferencesToSave(_)) => self.fail(
                &reply.operation,
                "偏好写入应由actor保存并确认".into(),
                batch,
            ),
            Ok(result) => {
                let pending = self
                    .pending
                    .remove(&reply.operation.id)
                    .expect("validated automation result");
                let completion = match result {
                    ResultKind::Snapshot(snapshot) => {
                        let snapshot = Arc::new(snapshot);
                        self.record_snapshot(snapshot.clone(), batch);
                        Completion::Snapshot(snapshot)
                    }
                    ResultKind::Frame(frame) => Completion::Frame(Arc::new(frame)),
                    ResultKind::Applications(apps) => Completion::Applications(Arc::new(apps)),
                    ResultKind::Manual {
                        accepted,
                        completion_observed,
                        message,
                    } => Completion::Manual {
                        accepted,
                        completion_observed,
                        message,
                    },
                    ResultKind::Accepted => Completion::Accepted,
                    _ => return,
                };
                self.finish(pending, completion, batch);
            }
        }
    }
    fn record_snapshot(&mut self, snapshot: Arc<Snapshot>, batch: &mut Batch) {
        let fingerprint = format!("{snapshot:?}");
        if fingerprint != self.snapshot_fingerprint {
            self.snapshot_fingerprint = fingerprint;
            self.snapshot = Some(snapshot.clone());
            batch.updates.push(Update::Snapshot(snapshot));
        }
    }
    fn finish(&self, pending: Pending, completion: Completion, batch: &mut Batch) {
        match pending.source {
            Source::Page => batch.updates.push(Update::Completed {
                operation: pending.key,
                completion,
            }),
            Source::Poll => {}
            Source::Native => {
                let (accepted, completion_observed, message) = match completion {
                    Completion::Manual {
                        accepted,
                        completion_observed,
                        message,
                    } => (accepted, completion_observed, message),
                    _ => (true, false, "副屏已接受用户操作，请查看实际画面".into()),
                };
                batch.updates.push(Update::ManualAck {
                    operation: pending.key,
                    accepted,
                    completion_observed,
                    message,
                });
            }
        }
    }
    fn fail(&mut self, key: &OperationKey, message: String, batch: &mut Batch) {
        let Some(pending) = self.pending.remove(&key.id) else {
            return;
        };
        if pending.key != *key {
            self.pending.insert(pending.key.id.clone(), pending);
            return;
        }
        pending.cancel.cancel();
        cancel_platform(key);
        match pending.source {
            Source::Native => batch.updates.push(Update::ManualAck {
                operation: pending.key,
                accepted: false,
                completion_observed: false,
                message,
            }),
            Source::Page => batch.updates.push(Update::Failed {
                operation: pending.key,
                message,
            }),
            Source::Poll => batch.updates.push(Update::Notice(message)),
        }
    }
    /// Unclaimed results are returned intact. Tool invocations may share the same
    /// Android callback registries; this page must never consume their outcomes.
    pub(crate) fn route_platform(
        &mut self,
        result: PlatformResult,
    ) -> (Batch, Option<PlatformResult>) {
        let key = result.key().clone();
        let mut batch = Batch::default();
        let Some(pending) = self.pending.get(&key.id).filter(|p| p.key == key) else {
            return (batch, Some(result));
        };
        let correct_kind = matches!(
            (&pending.kind, &result),
            (
                CommandKind::RequestPermission(_),
                PlatformResult::Permission(_)
            ) | (CommandKind::RequestShizuku, PlatformResult::Shizuku(_))
                | (CommandKind::Virtual(_), PlatformResult::Virtual(_))
                | (
                    CommandKind::Manual(ManualInput::Hide { .. } | ManualInput::Close { .. }),
                    PlatformResult::Virtual(_)
                )
        );
        if !correct_kind {
            return (batch, Some(result));
        }
        let response =
            match result {
                PlatformResult::Permission(result) => {
                    if let Some(error) = result.error {
                        Err(error)
                    } else if result.permissions.iter().any(|(_, p)| {
                        matches!(p, platform::permissions::PermissionOutcome::Cancelled)
                    }) {
                        Err("权限请求已取消".into())
                    } else if result.permissions.iter().all(|(_, p)| {
                        matches!(p, platform::permissions::PermissionOutcome::Granted)
                    }) {
                        Ok(ResultKind::Accepted)
                    } else {
                        Err("权限未授予，请查看系统权限状态".into())
                    }
                }
                PlatformResult::Shizuku(result) => {
                    if result.cancelled {
                        Err("Shizuku授权请求已取消".into())
                    } else if let Some(error) = result.error {
                        Err(error)
                    } else if result.granted {
                        Ok(ResultKind::Accepted)
                    } else {
                        Err("Shizuku权限未授予".into())
                    }
                }
                PlatformResult::Virtual(result) => {
                    if result.cancelled {
                        Err("副屏请求已取消；已创建或关闭的资源需查看最新状态".into())
                    } else {
                        result.result.and_then(|completed| {
                            let current = platform::virtual_screen::status();
                            if completed.identity != current.identity
                                || completed.closing != current.closing
                            {
                                Err("副屏结果对应资源在回调后已经改变".into())
                            } else {
                                Ok(ResultKind::Accepted)
                            }
                        })
                    }
                }
            };
        self.complete(
            page::Result {
                operation: key,
                result: response,
            },
            &mut batch,
        );
        self.last_poll = Instant::now() - Duration::from_secs(1);
        (batch, None)
    }
    pub(crate) fn preferences_saved(
        &mut self,
        key: &OperationKey,
        result: Result<(), String>,
    ) -> Batch {
        let mut batch = Batch::default();
        let Some(pending) = self
            .pending
            .get(&key.id)
            .filter(|p| p.key == *key && p.save_pending)
        else {
            return batch;
        };
        if let Err(error) = result {
            self.fail(key, error, &mut batch);
            return batch;
        }
        let CommandKind::SavePreferences(preferences) = &pending.kind else {
            return batch;
        };
        self.preferences = preferences.clone();
        if self
            .effect
            .try_send(Job::ApplyPreferences(preferences.clone()))
            .is_err()
        {
            batch.updates.push(Update::Notice(
                "偏好已保存，系统应用队列繁忙，请刷新后重试".into(),
            ));
        }
        self.pending.remove(&key.id);
        batch.updates.push(Update::PreferencesConfirmed {
            operation: Some(key.clone()),
            preferences: self.preferences.clone(),
        });
        batch
    }
}
fn cancel_platform(key: &OperationKey) {
    platform::permissions::cancel(&key.id);
    platform::shizuku::cancel_permission(&key.id);
    platform::virtual_screen::cancel(&key.id);
}
fn reject(source: Source, operation: OperationKey, message: String, batch: &mut Batch) {
    batch.updates.push(match source {
        Source::Page => Update::Failed { operation, message },
        Source::Native => Update::ManualAck {
            operation,
            accepted: false,
            completion_observed: false,
            message,
        },
        Source::Poll => Update::Notice(message),
    });
}
fn verify_frozen(target: &platform::automation::Target, context: &Context) -> Result<(), String> {
    platform::automation::verify_target(target)?;
    if matches!(
        context.route_policy,
        RoutePolicy::Auto | RoutePolicy::Accessibility
    ) {
        let capabilities = platform::automation::capabilities();
        let generation = if capabilities.connected {
            capabilities.service_generation
        } else {
            0
        };
        if generation != target.service_generation {
            return Err("用户手势等待期间无障碍连接代次改变，请查看对应屏幕后重新操作".into());
        }
    }
    Ok(())
}
fn expected_identity(kind: &CommandKind) -> Option<&Identity> {
    match kind {
        CommandKind::ReadFrame(identity) => Some(identity),
        CommandKind::LaunchApplication { expected, .. } => Some(expected),
        CommandKind::Manual(
            ManualInput::Tap { identity, .. }
            | ManualInput::Swipe { identity, .. }
            | ManualInput::Hide { identity }
            | ManualInput::Close { identity },
        ) => Some(identity),
        CommandKind::Virtual(
            platform::virtual_screen::Action::Close { expected }
            | platform::virtual_screen::Action::Preview { expected, .. }
            | platform::virtual_screen::Action::LaunchOwnHost { expected }
            | platform::virtual_screen::Action::LaunchOwn { expected, .. },
        ) => Some(expected),
        CommandKind::Virtual(platform::virtual_screen::Action::OpenChecked {
            expected, ..
        }) => expected.as_ref(),
        _ => None,
    }
}
