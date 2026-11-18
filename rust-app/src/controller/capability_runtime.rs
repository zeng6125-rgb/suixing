//! Explicit capability requests and bounded workers. Store remains the settings
//! owner; su runs only for ProbeRoot. Shared callbacks are routed by the actor.
use crate::{
    execution_policy::{RoutingOwner, Snapshot as RoutingSnapshot},
    network::CancelToken,
    platform::{
        self,
        manual_settings::Page,
        permissions::{OperationKey, PermissionKind, PermissionOutcome, PermissionResult},
        root_capability::{self, Status as RootStatus},
    },
    settings_policy::{ExecutionSettings, Preference},
};
use std::{
    collections::HashMap,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver, SyncSender, TryRecvError},
    },
    thread,
    time::{Duration, Instant},
};
#[derive(Clone, Debug)]
pub(crate) enum Request {
    Refresh(OperationKey),
    ProbeRoot(OperationKey),
    Cancel(OperationKey),
    ShizukuPermission(OperationKey),
    Permissions {
        operation: OperationKey,
        kinds: Vec<PermissionKind>,
    },
    OpenSettings {
        operation: OperationKey,
        page: Page,
    },
    SelectPreference {
        operation: OperationKey,
        preference: Preference,
    },
}
#[derive(Clone, Debug)]
pub(crate) struct Snapshot {
    pub routing: RoutingSnapshot,
    pub permissions: Vec<(PermissionKind, PermissionOutcome)>,
    pub overlay_allowed: bool,
    /// Only Environment.isExternalStorageManager on API30+ grants this field.
    /// API26-29 READ/WRITE outcomes are separately exposed in permissions.
    pub all_files_access: bool,
    pub battery_optimization_exempt: bool,
    pub privileged_file_routes_available: bool,
    pub virtual_screen: platform::virtual_screen::Status,
}
#[derive(Clone, Debug)]
pub(crate) enum Update {
    Snapshot(Arc<Snapshot>),
    Refreshed {
        operation: OperationKey,
        snapshot: Arc<Snapshot>,
    },
    RootStatus {
        operation: OperationKey,
        status: RootStatus,
    },
    PreferenceConfirmed {
        operation: OperationKey,
        settings: ExecutionSettings,
    },
    SettingsOpened {
        operation: OperationKey,
        page: Page,
    },
    PermissionFinished {
        operation: OperationKey,
        permissions: Vec<(PermissionKind, PermissionOutcome)>,
        error: Option<String>,
    },
    ShizukuPermissionFinished {
        operation: OperationKey,
        granted: bool,
        cancelled: bool,
        error: Option<String>,
    },
    /// This keeps the original UI pending entry until its actual receipt.
    WaitingForTerminal {
        operation: OperationKey,
        message: String,
    },
    Failed {
        operation: OperationKey,
        message: String,
    },
}
pub(crate) struct SettingsWrite {
    pub operation: OperationKey,
    pub settings: ExecutionSettings,
}
pub(crate) struct RootFactWrite {
    pub operation: OperationKey,
    pub available: bool,
    pub checked_at: i64,
}
#[derive(Default)]
pub(crate) struct Batch {
    pub updates: Vec<Update>,
    pub settings_to_save: Vec<SettingsWrite>,
    pub root_facts_to_save: Vec<RootFactWrite>,
}
enum Job {
    Snapshot(OperationKey, RoutingOwner),
    Root(root_capability::ProbeTask),
    Permissions(OperationKey, Vec<PermissionKind>, Arc<AtomicBool>),
    ShizukuPermission(OperationKey, Arc<AtomicBool>),
    OpenSettings(OperationKey, Page, Arc<CancelToken>),
}
enum Reply {
    Snapshot(OperationKey, Snapshot),
    Root(root_capability::ProbeResult),
    PermissionAdmission(OperationKey, Result<(), String>),
    ShizukuAdmission(OperationKey, Result<(), String>),
    SettingsOpened(OperationKey, Page, Result<(), String>),
}
#[derive(Clone, Copy, PartialEq, Eq)]
enum Kind {
    Query,
    Root,
    Permissions,
    ShizukuPermission,
    Settings,
    Preference,
}
struct Pending {
    key: OperationKey,
    started: Instant,
    timeout: Duration,
    cancel: Arc<AtomicBool>,
    main_cancel: Arc<CancelToken>,
    write: Option<ExecutionSettings>,
    kind: Kind,
}
pub(crate) struct CapabilityRuntime {
    owner: RoutingOwner,
    query: SyncSender<Job>,
    effects: SyncSender<Job>,
    replies: Receiver<Reply>,
    pending: HashMap<String, Pending>,
    snapshot: Option<Arc<Snapshot>>,
}
impl Drop for CapabilityRuntime {
    fn drop(&mut self) {
        for pending in self.pending.values() {
            self.revoke(pending);
        }
    }
}
fn worker(name: &str, output: SyncSender<Reply>) -> Result<SyncSender<Job>, String> {
    let (sender, receiver) = mpsc::sync_channel(2);
    thread::Builder::new()
        .name(name.into())
        .spawn(move || {
            while let Ok(job) = receiver.recv() {
                let reply = match job {
                    Job::Snapshot(key, owner) => Reply::Snapshot(key, query(&owner)),
                    Job::Root(task) => Reply::Root(root_capability::perform(task)),
                    Job::Permissions(key, kinds, cancel) => {
                        let result = if cancel.load(Ordering::Acquire) {
                            Err("权限操作在登记前已取消".into())
                        } else {
                            platform::permissions::request(key.clone(), kinds)
                        };
                        // Cancellation may race the durable request-code allocation.
                        if cancel.load(Ordering::Acquire) {
                            platform::permissions::cancel(&key.id);
                        }
                        Reply::PermissionAdmission(key, result)
                    }
                    Job::ShizukuPermission(key, cancel) => {
                        let result = if cancel.load(Ordering::Acquire) {
                            Err("Shizuku授权在登记前已取消".into())
                        } else {
                            platform::shizuku::request_permission(key.clone())
                        };
                        if cancel.load(Ordering::Acquire) {
                            platform::shizuku::cancel_permission(&key.id);
                        }
                        Reply::ShizukuAdmission(key, result)
                    }
                    Job::OpenSettings(key, page, cancel) => {
                        let result = platform::manual_settings::open(&key, page, cancel);
                        Reply::SettingsOpened(key, page, result)
                    }
                };
                if output.send(reply).is_err() {
                    break;
                }
            }
        })
        .map_err(|_| "能力检测线程无法启动")?;
    Ok(sender)
}
impl CapabilityRuntime {
    pub(crate) fn boot(owner: RoutingOwner) -> Result<Self, String> {
        let (output, replies) = mpsc::sync_channel(8);
        let query = worker("rust-capability-query", output.clone())?;
        let effects = worker("rust-capability-effects", output)?;
        Ok(Self {
            owner,
            query,
            effects,
            replies,
            pending: HashMap::new(),
            snapshot: None,
        })
    }
    pub(crate) fn owner(&self) -> &RoutingOwner {
        &self.owner
    }
    pub(crate) fn replay(&self) -> Vec<Update> {
        self.snapshot
            .as_ref()
            .map(|snapshot| vec![Update::Snapshot(snapshot.clone())])
            .unwrap_or_default()
    }
    fn revoke(&self, pending: &Pending) {
        pending.cancel.store(true, Ordering::Release);
        pending.main_cancel.cancel();
        match pending.kind {
            Kind::Root => {
                self.owner.root().cancel(&pending.key);
            }
            Kind::Permissions => platform::permissions::cancel(&pending.key.id),
            Kind::ShizukuPermission => platform::shizuku::cancel_permission(&pending.key.id),
            _ => (),
        }
    }
    fn matches(&self, key: &OperationKey, kind: Kind) -> bool {
        self.pending
            .get(&key.id)
            .is_some_and(|pending| pending.key == *key && pending.kind == kind)
    }
    pub(crate) fn dispatch(&mut self, request: Request) -> Batch {
        let mut batch = Batch::default();
        if let Request::Cancel(key) = request {
            if let Some(pending) = self.pending.get(&key.id).filter(|p| p.key == key) {
                self.revoke(pending);
                batch.updates.push(Update::WaitingForTerminal {
                    operation: key,
                    message: "已请求取消，等待实际操作回执；系统权限窗口可能仍需用户处理".into(),
                });
            }
            return batch;
        }
        let (key, kind) = match &request {
            Request::Refresh(key) => (key.clone(), Kind::Query),
            Request::ProbeRoot(key) => (key.clone(), Kind::Root),
            Request::ShizukuPermission(key) => (key.clone(), Kind::ShizukuPermission),
            Request::Permissions { operation, .. } => (operation.clone(), Kind::Permissions),
            Request::OpenSettings { operation, .. } => (operation.clone(), Kind::Settings),
            Request::SelectPreference { operation, .. } => (operation.clone(), Kind::Preference),
            Request::Cancel(_) => unreachable!(),
        };
        if let Err(error) = key.validate() {
            batch.updates.push(Update::Failed {
                operation: key,
                message: error,
            });
            return batch;
        }
        if key.conversation_id.is_some()
            || key.generation != 1
            || self.pending.contains_key(&key.id)
            || self.pending.len() >= 8
        {
            batch.updates.push(Update::Failed {
                operation: key,
                message: "能力操作身份过期、重复或队列繁忙".into(),
            });
            return batch;
        }
        if matches!(
            kind,
            Kind::Query | Kind::Preference | Kind::Permissions | Kind::ShizukuPermission
        ) && self.pending.values().any(|p| p.kind == kind)
        {
            batch.updates.push(Update::Failed {
                operation: key,
                message: "同类能力操作尚未结束".into(),
            });
            return batch;
        }
        let cancel = Arc::new(AtomicBool::new(false));
        let main_cancel = Arc::new(CancelToken::new());
        let timeout = Duration::from_secs(
            if matches!(kind, Kind::Permissions | Kind::ShizukuPermission) {
                180
            } else {
                20
            },
        );
        let mut pending = Pending {
            key: key.clone(),
            started: Instant::now(),
            timeout,
            cancel: cancel.clone(),
            main_cancel: main_cancel.clone(),
            write: None,
            kind,
        };
        let job = match request {
            Request::SelectPreference { preference, .. } => {
                let mut settings = self.owner.saved_settings();
                settings.preferred_capability = preference.legacy_key().into();
                pending.write = Some(settings.clone());
                self.pending.insert(key.id.clone(), pending);
                batch.settings_to_save.push(SettingsWrite {
                    operation: key,
                    settings,
                });
                return batch;
            }
            Request::Refresh(_) => Job::Snapshot(key.clone(), self.owner.clone()),
            Request::ProbeRoot(_) => {
                match self
                    .owner
                    .root()
                    .begin(key.clone(), self.owner.environment().clone(), cancel)
                {
                    Err(error) => {
                        batch.updates.push(Update::Failed {
                            operation: key,
                            message: error,
                        });
                        return batch;
                    }
                    Ok(task) => Job::Root(task),
                }
            }
            Request::Permissions { kinds, .. } => Job::Permissions(key.clone(), kinds, cancel),
            Request::ShizukuPermission(_) => Job::ShizukuPermission(key.clone(), cancel),
            Request::OpenSettings { page, .. } => Job::OpenSettings(key.clone(), page, main_cancel),
            Request::Cancel(_) => unreachable!(),
        };
        let sender = if kind == Kind::Query {
            &self.query
        } else {
            &self.effects
        };
        if sender.try_send(job).is_err() {
            if kind == Kind::Root {
                self.owner.root().abort_undispatched(&key);
            }
            batch.updates.push(Update::Failed {
                operation: key,
                message: "能力操作队列不可用，未派发".into(),
            });
            return batch;
        }
        self.pending.insert(key.id.clone(), pending);
        if kind == Kind::Root {
            batch.updates.push(Update::RootStatus {
                operation: key,
                status: self.owner.root().status(),
            });
        }
        batch
    }
    pub(crate) fn tick(&mut self) -> Batch {
        let mut batch = Batch::default();
        for _ in 0..16 {
            match self.replies.try_recv() {
                Ok(Reply::Snapshot(key, snapshot)) => {
                    if self.matches(&key, Kind::Query) {
                        let pending = self
                            .pending
                            .remove(&key.id)
                            .expect("matched capability query");
                        if pending.cancel.load(Ordering::Acquire) {
                            batch.updates.push(Update::Failed {
                                operation: key,
                                message: "能力查询已取消，实际读取已结束".into(),
                            });
                        } else {
                            let snapshot = Arc::new(snapshot);
                            self.snapshot = Some(snapshot.clone());
                            batch.updates.push(Update::Refreshed {
                                operation: key,
                                snapshot,
                            });
                        }
                    }
                }
                Ok(Reply::Root(result)) => {
                    let key = result.operation.clone();
                    if self.matches(&key, Kind::Root) {
                        let pending = self
                            .pending
                            .remove(&key.id)
                            .expect("matched capability Root probe");
                        let cancelled =
                            result.was_cancelled() || pending.cancel.load(Ordering::Acquire);
                        match self.owner.root().finish(result) {
                            Err(error) => batch.updates.push(Update::Failed {
                                operation: key,
                                message: error,
                            }),
                            Ok(status) => {
                                if !cancelled && status.remembered_checked_at > 0 {
                                    batch.root_facts_to_save.push(RootFactWrite {
                                        operation: key.clone(),
                                        available: status.remembered_available,
                                        checked_at: status.remembered_checked_at,
                                    });
                                }
                                batch.updates.push(Update::RootStatus {
                                    operation: key,
                                    status,
                                });
                            }
                        }
                    }
                }
                Ok(Reply::PermissionAdmission(key, result)) => {
                    self.admission(&key, Kind::Permissions, result, &mut batch)
                }
                Ok(Reply::ShizukuAdmission(key, result)) => {
                    self.admission(&key, Kind::ShizukuPermission, result, &mut batch)
                }
                Ok(Reply::SettingsOpened(key, page, result)) => {
                    if self.matches(&key, Kind::Settings) {
                        self.pending.remove(&key.id);
                        match result {
                            Ok(()) => batch.updates.push(Update::SettingsOpened {
                                operation: key,
                                page,
                            }),
                            Err(error) => batch.updates.push(Update::Failed {
                                operation: key,
                                message: error,
                            }),
                        }
                    }
                }
                Err(TryRecvError::Empty | TryRecvError::Disconnected) => break,
            }
        }
        for pending in self.pending.values() {
            if pending.started.elapsed() > pending.timeout
                && !pending.cancel.load(Ordering::Acquire)
            {
                self.revoke(pending);
                batch.updates.push(Update::WaitingForTerminal {
                    operation: pending.key.clone(),
                    message: "能力操作等待超时，已请求取消；占用保留到实际回执".into(),
                });
            }
        }
        batch
    }
    fn admission(
        &mut self,
        key: &OperationKey,
        kind: Kind,
        result: Result<(), String>,
        batch: &mut Batch,
    ) {
        // Ok confirms registration only. Callback may already have been routed,
        // so an unmatched admission must never remove another pending operation.
        if let Err(error) = result {
            if self.matches(key, kind) {
                self.pending.remove(&key.id);
                batch.updates.push(Update::Failed {
                    operation: key.clone(),
                    message: error,
                });
            }
        }
    }
    /// Root performs the sole common permission drain, routing unclaimed values
    /// onward unchanged to the original tools/voice/network owners.
    pub(crate) fn route_permissions(
        &mut self,
        results: Vec<PermissionResult>,
    ) -> (Batch, Vec<PermissionResult>) {
        let mut batch = Batch::default();
        let mut unclaimed = Vec::new();
        for result in results {
            if !self.matches(&result.operation, Kind::Permissions) {
                unclaimed.push(result);
                continue;
            }
            let pending = self
                .pending
                .remove(&result.operation.id)
                .expect("matched capability permissions");
            let permissions = if pending.cancel.load(Ordering::Acquire) {
                result
                    .permissions
                    .into_iter()
                    .map(|(kind, _)| (kind, PermissionOutcome::Cancelled))
                    .collect()
            } else {
                result.permissions
            };
            batch.updates.push(Update::PermissionFinished {
                operation: result.operation,
                permissions,
                error: result.error,
            });
        }
        (batch, unclaimed)
    }
    pub(crate) fn route_shizuku(
        &mut self,
        results: Vec<platform::shizuku::PermissionResult>,
    ) -> (Batch, Vec<platform::shizuku::PermissionResult>) {
        let mut batch = Batch::default();
        let mut unclaimed = Vec::new();
        for result in results {
            if !self.matches(&result.operation, Kind::ShizukuPermission) {
                unclaimed.push(result);
                continue;
            }
            let pending = self
                .pending
                .remove(&result.operation.id)
                .expect("matched capability Shizuku permission");
            let cancelled = result.cancelled || pending.cancel.load(Ordering::Acquire);
            batch.updates.push(Update::ShizukuPermissionFinished {
                operation: result.operation,
                granted: result.granted && !cancelled,
                cancelled,
                error: result.error,
            });
        }
        (batch, unclaimed)
    }
    /// Parent supplies the canonical Settings.execution after Store commits, so
    /// a concurrent actual Root fact cannot be replaced by the earlier UI copy.
    pub(crate) fn settings_saved(
        &mut self,
        key: &OperationKey,
        result: Result<ExecutionSettings, String>,
    ) -> Batch {
        let mut batch = Batch::default();
        let Some(pending) = self
            .pending
            .get(&key.id)
            .filter(|p| p.key == *key && p.kind == Kind::Preference && p.write.is_some())
        else {
            return batch;
        };
        let requested = pending.write.clone().expect("validated preference write");
        self.pending.remove(&key.id);
        let result = result.and_then(|settings| {
            if settings.preferred_capability != requested.preferred_capability {
                return Err("执行档次保存回执与原请求不一致".into());
            }
            self.owner.publish_saved(settings.clone())?;
            Ok(settings)
        });
        match result {
            Ok(settings) => batch.updates.push(Update::PreferenceConfirmed {
                operation: key.clone(),
                settings,
            }),
            Err(error) => batch.updates.push(Update::Failed {
                operation: key.clone(),
                message: error,
            }),
        }
        batch
    }
}
fn query(owner: &RoutingOwner) -> Snapshot {
    let _ = platform::shizuku::refresh();
    let routing = owner.snapshot();
    let permissions = [
        PermissionKind::Notifications,
        PermissionKind::Microphone,
        PermissionKind::LocalNetwork,
        PermissionKind::ReadExternalStorage,
        PermissionKind::WriteExternalStorage,
    ]
    .into_iter()
    .map(|kind| (kind, platform::permissions::check(kind)))
    .collect();
    #[cfg(target_os = "android")]
    let (overlay_allowed, all_files_access, battery_optimization_exempt) = android_permissions();
    #[cfg(not(target_os = "android"))]
    let (overlay_allowed, all_files_access, battery_optimization_exempt) = (false, false, false);
    let privileged_file_routes_available =
        !matches!(routing.selected_shell, crate::shell_runner::Route::AppUid);
    Snapshot {
        routing,
        permissions,
        overlay_allowed,
        all_files_access,
        battery_optimization_exempt,
        privileged_file_routes_available,
        virtual_screen: platform::virtual_screen::status(),
    }
}
#[cfg(target_os = "android")]
fn android_permissions() -> (bool, bool, bool) {
    use jni::{JValue, jni_sig, jni_str};
    let overlay = platform::android::invoke(|env| {
        let context = platform::android::application_context()
            .map_err(|_| jni::errors::Error::NullPtr("capability context"))?;
        env.call_static_method(
            jni_str!("android/provider/Settings"),
            jni_str!("canDrawOverlays"),
            jni_sig!("(Landroid/content/Context;)Z"),
            &[JValue::Object(context.as_ref())],
        )?
        .z()
    })
    .unwrap_or(false);
    let files = if platform::android::paths().is_some_and(|p| p.sdk_int >= 30) {
        platform::android::invoke(|env| {
            env.call_static_method(
                jni_str!("android/os/Environment"),
                jni_str!("isExternalStorageManager"),
                jni_sig!("()Z"),
                &[],
            )?
            .z()
        })
        .unwrap_or(false)
    } else {
        false
    };
    let battery = platform::android::invoke(|env| {
        let context = platform::android::application_context()
            .map_err(|_| jni::errors::Error::NullPtr("capability context"))?;
        let name = env.new_string("power")?;
        let power = env
            .call_method(
                context.as_ref(),
                jni_str!("getSystemService"),
                jni_sig!("(Ljava/lang/String;)Ljava/lang/Object;"),
                &[JValue::Object(&name)],
            )?
            .l()?;
        if power.is_null() {
            return Ok(false);
        }
        let package = env
            .call_method(
                context.as_ref(),
                jni_str!("getPackageName"),
                jni_sig!("()Ljava/lang/String;"),
                &[],
            )?
            .l()?;
        env.call_method(
            &power,
            jni_str!("isIgnoringBatteryOptimizations"),
            jni_sig!("(Ljava/lang/String;)Z"),
            &[JValue::Object(&package)],
        )?
        .z()
    })
    .unwrap_or(false);
    (overlay, files, battery)
}
