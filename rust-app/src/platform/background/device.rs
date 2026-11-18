use super::{
    ACTION_CANCEL, ACTION_REFRESH, BackgroundEvent, BackgroundEventKind as Kind, BackgroundStatus,
    SERVICE_CLASS, framework,
};
use crate::{
    platform::android::{self, ServiceRequest},
    runtime_host::{RuntimeHost, ServiceRunState, ServiceSnapshot},
};
use jni::{objects::JObject, refs::Global};
use std::{
    collections::{HashSet, VecDeque},
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{Arc, Condvar, Mutex, OnceLock},
    thread,
    time::{Duration, Instant},
};

const MAX_EVENTS: usize = 256;
const RENEW_INTERVAL: Duration = Duration::from_secs(20);
const START_CONFIRMATION_LIMIT: Duration = Duration::from_secs(4);
const MAX_CLEANUP_BATCH: usize = 16;
type Object = Arc<Global<JObject<'static>>>;

struct State {
    desired: ServiceSnapshot,
    status: BackgroundStatus,
    service: Option<Object>,
    epoch: u64,
    wake_lock: Option<Object>,
    renew_at: Instant,
    release_retry_at: Instant,
    start_pending: Option<Instant>,
    update_pending: bool,
    stop_pending: bool,
    rendered_revision: Option<u64>,
    notified: HashSet<String>,
    pending_cleanup: HashSet<String>,
    cleanup_at: Instant,
    blocked: bool,
    events: VecDeque<BackgroundEvent>,
}

struct Manager {
    host: Arc<RuntimeHost>,
    state: Mutex<State>,
    subscription: Mutex<Option<crate::runtime_host::ServiceSubscription>>,
    // Serializes lease changes without holding the runtime state across JNI.
    wake_io: Mutex<()>,
    notification_io: Mutex<()>,
    changed: Condvar,
}

static INSTANCE: OnceLock<Arc<Manager>> = OnceLock::new();
static INSTALL: Mutex<()> = Mutex::new(());

pub fn start(host: Arc<RuntimeHost>) -> Result<(), String> {
    let _install = INSTALL.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(manager) = INSTANCE.get() {
        if !Arc::ptr_eq(&manager.host, &host) {
            return Err("后台管理器已有不同的运行宿主".into());
        }
        return if manager
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .status
            .initialized
        {
            Ok(())
        } else {
            Err("后台管理线程未能初始化，请重新打开应用进程".into())
        };
    }
    let paths = android::paths().ok_or("Android 平台尚未初始化")?;
    if paths.sdk_int < 26 {
        return Err("前台服务要求 Android API 26 或以上".into());
    }
    android::application_context()?;
    let manager = Arc::new(Manager {
        host: host.clone(),
        changed: Condvar::new(),
        wake_io: Mutex::new(()),
        notification_io: Mutex::new(()),
        subscription: Mutex::new(None),
        state: Mutex::new(State {
            desired: ServiceSnapshot::default(),
            status: BackgroundStatus::default(),
            service: None,
            epoch: 0,
            wake_lock: None,
            renew_at: Instant::now(),
            release_retry_at: Instant::now(),
            start_pending: None,
            update_pending: false,
            stop_pending: false,
            rendered_revision: None,
            notified: HashSet::new(),
            pending_cleanup: HashSet::new(),
            cleanup_at: Instant::now(),
            blocked: false,
            events: VecDeque::new(),
        }),
    });
    let weak = Arc::downgrade(&manager);
    let subscription = host.attach_service(move || {
        if let Some(manager) = weak.upgrade() {
            manager.changed.notify_one();
        }
    })?;
    *manager
        .subscription
        .lock()
        .unwrap_or_else(|e| e.into_inner()) = Some(subscription);
    INSTANCE
        .set(manager.clone())
        .map_err(|_| "后台管理器重复初始化")?;
    manager
        .state
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .status
        .initialized = true;
    manager.report(
        Kind::Initialized,
        None,
        "后台服务管理器已初始化；尚未确认前台服务启动".into(),
    );
    let worker = manager.clone();
    if thread::Builder::new()
        .name("rust-agent-background".into())
        .spawn(move || {
            let result = catch_unwind(AssertUnwindSafe(|| worker.run()));
            if result.is_err() {
                worker.fail("后台管理线程异常结束，前台保护状态需要重新核对".into());
                worker.release_wake();
                let _ = framework::stop_component();
                worker
                    .state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .status
                    .initialized = false;
                worker
                    .subscription
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .take();
            }
        })
        .is_err()
    {
        manager
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .status
            .initialized = false;
        manager.fail("无法创建后台服务管理线程".into());
        manager
            .subscription
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take();
        return Err("无法创建后台服务管理线程".into());
    }
    Ok(())
}

pub fn request_refresh() {
    if let Some(manager) = INSTANCE.get() {
        let mut state = manager.state.lock().unwrap_or_else(|e| e.into_inner());
        state.blocked = false;
        state.rendered_revision = None;
        state.renew_at = Instant::now();
        drop(state);
        manager.changed.notify_one();
    }
}

pub fn status() -> BackgroundStatus {
    INSTANCE
        .get()
        .map(|manager| {
            manager
                .state
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .status
                .clone()
        })
        .unwrap_or_default()
}

pub fn drain_events(limit: usize) -> Vec<BackgroundEvent> {
    let Some(manager) = INSTANCE.get() else {
        return Vec::new();
    };
    let mut state = manager.state.lock().unwrap_or_else(|e| e.into_inner());
    let count = limit.min(MAX_EVENTS).min(state.events.len());
    state.events.drain(..count).collect()
}

impl Manager {
    fn report(&self, kind: Kind, key: Option<crate::runtime_host::RunKey>, message: String) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state
            .events
            .back()
            .is_some_and(|event| event.kind == kind && event.key == key && event.message == message)
        {
            return;
        }
        if state.events.len() == MAX_EVENTS {
            state.events.pop_front();
        }
        state
            .events
            .push_back(BackgroundEvent { kind, key, message });
    }

    fn fail(&self, message: String) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let changed = state.status.last_error.as_ref() != Some(&message);
        state.status.last_error = Some(message.clone());
        drop(state);
        if changed {
            self.report(Kind::Failed, None, message);
        }
    }

    fn sync_snapshot(&self) {
        let snapshot = self
            .subscription
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_mut()
            .and_then(|subscription| subscription.poll());
        if let Some(snapshot) = snapshot {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if snapshot.revision >= state.desired.revision {
                state.desired = snapshot;
                if state.desired.runs.is_empty() {
                    state.blocked = false;
                }
            }
        }
    }

    fn run(self: &Arc<Self>) {
        loop {
            self.sync_snapshot();
            self.cleanup_notifications();
            self.reconcile();
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if !state.desired.runtime_alive
                && state.service.is_none()
                && state.start_pending.is_none()
                && state.pending_cleanup.is_empty()
                && state.wake_lock.is_none()
            {
                drop(state);
                self.state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .status
                    .initialized = false;
                self.subscription
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .take();
                return;
            }
            let _ = self
                .changed
                .wait_timeout(state, Duration::from_millis(250))
                .unwrap_or_else(|e| e.into_inner());
        }
    }

    fn reconcile(self: &Arc<Self>) {
        let mut queue_start = false;
        let mut queue_update = None;
        let mut queue_stop = None;
        let mut missing_confirmation = false;
        {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            let wanted = state.desired.runtime_alive && !state.desired.runs.is_empty();
            if state
                .start_pending
                .is_some_and(|started| started.elapsed() >= START_CONFIRMATION_LIMIT)
                && !state.status.foreground
            {
                state.start_pending = None;
                state.blocked = true;
                missing_confirmation = true;
            }
            if !wanted {
                if state.service.is_some() && !state.stop_pending {
                    state.stop_pending = true;
                    queue_stop = Some(state.epoch);
                }
            } else if !state.status.foreground
                && state.start_pending.is_none()
                && !state.blocked
                && !state.stop_pending
            {
                state.start_pending = Some(Instant::now());
                queue_start = true;
            } else if state.status.foreground
                && !state.stop_pending
                && state.rendered_revision != Some(state.desired.revision)
                && !state.update_pending
            {
                state.update_pending = true;
                queue_update = Some(state.epoch);
            }
        }
        if missing_confirmation {
            let manager = self.clone();
            if let Err(error) = android::post_to_main(move || manager.stop_unconfirmed()) {
                self.fail(format!("未确认服务的停止请求无法投递主线程：{error}"));
            }
        }
        if queue_start {
            let manager = self.clone();
            if let Err(error) = android::post_to_main(move || manager.request_start()) {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                state.start_pending = None;
                state.blocked = true;
                drop(state);
                self.fail(format!("后台启动无法投递主线程：{error}"));
            }
        }
        if let Some(epoch) = queue_update {
            let manager = self.clone();
            if let Err(error) = android::post_to_main(move || manager.update(epoch)) {
                self.state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .update_pending = false;
                self.fail(format!("通知更新无法投递主线程：{error}"));
            }
        }
        if let Some(epoch) = queue_stop {
            let manager = self.clone();
            if let Err(error) = android::post_to_main(move || manager.stop(epoch, false)) {
                self.state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .stop_pending = false;
                self.fail(format!("服务停止无法投递主线程：{error}"));
                self.release_wake();
            }
        }
        self.renew_wake();
    }

    fn stop_unconfirmed(&self) {
        {
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if state.status.foreground || state.start_pending.is_some() || !state.blocked {
                return;
            }
        }
        match framework::stop_component() {
            Ok(true) => {
                self.fail("未收到前台提升确认；系统已接受服务停止请求，等待销毁回调".into())
            }
            Ok(false) => self.fail("未收到前台提升确认；系统当前没有可停止的服务组件".into()),
            Err(error) => self.fail(format!("停止未确认服务失败：{error}")),
        }
    }

    fn request_start(&self) {
        self.sync_snapshot();
        {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if !state.desired.runtime_alive
                || state.desired.runs.is_empty()
                || state.blocked
                || state.status.foreground
                || state.stop_pending
            {
                state.start_pending = None;
                return;
            }
        }
        match framework::request_start() {
            Ok(()) => self.report(
                Kind::ForegroundRequested,
                None,
                "系统已接受后台服务启动请求，等待实际前台提升".into(),
            ),
            Err(error) => {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                state.start_pending = None;
                state.blocked = true;
                drop(state);
                self.fail(format!("后台服务启动被拒绝：{error}"));
            }
        }
        self.changed.notify_one();
    }

    fn update(&self, epoch: u64) {
        self.sync_snapshot();
        let input = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            state.update_pending = false;
            if state.epoch != epoch || !state.status.foreground {
                return;
            }
            let service = state.service.clone();
            let snapshot = state.desired.clone();
            let previous = state.notified.clone();
            // Track prospective cards before JNI: a partial post must still
            // have a later cleanup path even when update returns an error.
            state.notified.extend(
                snapshot
                    .runs
                    .iter()
                    .map(|run| super::notification_tag(&run.key)),
            );
            service.map(|service| (service, snapshot, previous))
        };
        let Some((service, snapshot, previous)) = input else {
            return;
        };
        if snapshot.runs.is_empty() || !snapshot.runtime_alive {
            self.stop(epoch, false);
            return;
        }
        let _notifications = self
            .notification_io
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        match framework::update(&service, &snapshot, &previous) {
            Ok(policy) => {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                if state.epoch != epoch {
                    return;
                }
                state.rendered_revision = Some(snapshot.revision);
                state.notified = snapshot
                    .runs
                    .iter()
                    .map(|run| super::notification_tag(&run.key))
                    .collect();
                let restricted = state.status.notifications_allowed != Some(false) && !policy;
                state.status.notifications_allowed = Some(policy);
                drop(state);
                if restricted {
                    self.report(
                        Kind::NotificationRestricted,
                        None,
                        "系统通知权限或通知渠道已关闭，通知取消入口可能无法显示".into(),
                    );
                }
            }
            Err(error) => {
                // Avoid retrying the same failed notification every frame; an
                // actual status change or explicit refresh may attempt again.
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                if state.epoch == epoch {
                    state.rendered_revision = Some(snapshot.revision);
                }
                drop(state);
                self.fail(format!("后台通知更新失败：{error}"));
            }
        }
    }

    fn stop(&self, epoch: u64, force: bool) {
        // Poll again on the actual callback, rather than trusting the idle
        // snapshot from when this main-thread effect was enqueued.
        self.sync_snapshot();
        let input = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if state.epoch != epoch {
                return;
            }
            state.stop_pending = false;
            if !force && state.desired.runtime_alive && !state.desired.runs.is_empty() {
                return;
            }
            state
                .service
                .clone()
                .map(|service| (service, state.notified.clone()))
        };
        self.release_wake();
        if let Some((service, notified)) = input {
            let _notifications = self
                .notification_io
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            let outcome = framework::stop(&service);
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if state.epoch == epoch {
                if outcome.foreground_removed {
                    state.status.foreground = false;
                }
                state.stop_pending = outcome.stop_requested;
                state.pending_cleanup.extend(notified);
                state.cleanup_at = Instant::now();
            }
            drop(state);
            if outcome.stop_requested {
                self.report(
                    Kind::StopRequested,
                    None,
                    if outcome.foreground_removed {
                        "已移除前台通知并请求停止服务，等待销毁回调"
                    } else {
                        "已请求停止服务，前台移除结果和销毁回调仍需核对"
                    }
                    .into(),
                );
            }
            for error in outcome.errors {
                self.fail(format!("后台服务停止未完全确认：{error}"));
            }
            self.changed.notify_one();
        }
    }

    fn cleanup_notifications(&self) {
        let _notifications = self
            .notification_io
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let ids = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if state.pending_cleanup.is_empty() || Instant::now() < state.cleanup_at {
                return;
            }
            state.cleanup_at = Instant::now() + RENEW_INTERVAL;
            let active: HashSet<_> = if state.status.foreground {
                state
                    .desired
                    .runs
                    .iter()
                    .map(|run| super::notification_tag(&run.key))
                    .collect()
            } else {
                HashSet::new()
            };
            state
                .pending_cleanup
                .iter()
                .filter(|id| !active.contains(*id))
                .cloned()
                .take(MAX_CLEANUP_BATCH)
                .collect::<HashSet<_>>()
        };
        if ids.is_empty() {
            return;
        }
        match framework::clear_notifications(&ids) {
            Ok(()) => {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                state.pending_cleanup.retain(|id| !ids.contains(id));
                state.cleanup_at = Instant::now();
            }
            Err(error) => self.fail(format!("旧运行通知移除未确认：{error}")),
        }
    }

    fn renew_wake(&self) {
        let _lease = self.wake_io.lock().unwrap_or_else(|e| e.into_inner());
        let input = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            let working = state.desired.runtime_alive
                && state.desired.runs.iter().any(|run| {
                    !matches!(
                        run.state,
                        ServiceRunState::WaitingApproval | ServiceRunState::WaitingStorage
                    ) || run.active_children > 0
                });
            if !working || !state.status.foreground {
                drop(state);
                self.release_wake_inner(false);
                return;
            }
            if Instant::now() < state.renew_at && state.wake_lock.is_some() {
                return;
            }
            state.renew_at = Instant::now() + RENEW_INTERVAL;
            (state.epoch, state.wake_lock.clone())
        };
        let lock = if let Some(lock) = input.1 {
            lock
        } else {
            match framework::create_wake() {
                Ok(lock) => {
                    // Retain before acquire so an acquire/isHeld exception
                    // leaves an object available for bounded cleanup retry.
                    self.state
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .wake_lock = Some(lock.clone());
                    lock
                }
                Err(error) => {
                    self.fail(format!("CPU 唤醒租约创建失败：{error}"));
                    return;
                }
            }
        };
        match framework::acquire_wake(&lock) {
            Ok(()) => {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                let working = state.desired.runtime_alive
                    && state.desired.runs.iter().any(|run| {
                        !matches!(
                            run.state,
                            ServiceRunState::WaitingApproval | ServiceRunState::WaitingStorage
                        ) || run.active_children > 0
                    });
                if state.epoch != input.0 || !state.status.foreground || !working {
                    drop(state);
                    self.release_wake_inner(true);
                    return;
                }
                let acquired = !state.status.wake_lock_held;
                state.wake_lock = Some(lock);
                state.status.wake_lock_held = true;
                state.status.wake_lock_uncertain = false;
                state.release_retry_at = Instant::now();
                drop(state);
                if acquired {
                    self.report(
                        Kind::WakeLockAcquired,
                        None,
                        "已取得60秒 CPU 唤醒租约，运行时按需续租".into(),
                    );
                }
            }
            Err(error) => {
                self.state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .status
                    .wake_lock_uncertain = true;
                self.fail(format!("CPU 唤醒租约取得未确认，保留对象以便清理：{error}"));
            }
        }
    }

    fn release_wake(&self) {
        let _lease = self.wake_io.lock().unwrap_or_else(|e| e.into_inner());
        self.release_wake_inner(true);
    }

    // Caller holds wake_io only; no State lock is held across a framework call.
    fn release_wake_inner(&self, force: bool) {
        let lock = {
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if !force && Instant::now() < state.release_retry_at {
                return;
            }
            state.wake_lock.clone()
        };
        if let Some(lock) = lock {
            match framework::release_wake(&lock) {
                Ok(()) => {
                    let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                    if state
                        .wake_lock
                        .as_ref()
                        .is_some_and(|current| Arc::ptr_eq(current, &lock))
                    {
                        state.wake_lock = None;
                        state.status.wake_lock_held = false;
                        state.status.wake_lock_uncertain = false;
                    }
                    drop(state);
                    self.report(Kind::WakeLockReleased, None, "CPU 唤醒租约已释放".into());
                }
                Err(error) => {
                    let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                    state.status.wake_lock_uncertain = true;
                    state.release_retry_at = Instant::now() + RENEW_INTERVAL;
                    drop(state);
                    self.fail(format!("CPU 唤醒租约释放未确认，60秒租约仍有上限：{error}"))
                }
            }
        }
    }

    fn bind_service(&self, request: &ServiceRequest) -> Result<u64, String> {
        let existing = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .service
            .clone();
        if let Some(existing) = existing {
            if framework::same_instance(&existing, &request.instance)? {
                return Ok(self.state.lock().unwrap_or_else(|e| e.into_inner()).epoch);
            }
        }
        let service = framework::retain(&request.instance)?;
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.epoch = state.epoch.checked_add(1).ok_or("Service代次已耗尽")?;
        state.service = Some(service);
        state.status.foreground = false;
        state.stop_pending = false;
        state.update_pending = false;
        state.rendered_revision = None;
        let epoch = state.epoch;
        drop(state);
        self.release_wake();
        Ok(epoch)
    }

    fn started(&self, request: &ServiceRequest) -> Result<(), String> {
        let epoch = self.bind_service(request)?;
        self.sync_snapshot();
        let snapshot = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .desired
            .clone();
        let promoted = {
            let _notifications = self
                .notification_io
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            framework::promote(&request.instance, &snapshot)
        };
        match promoted {
            Ok(policy) => {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                if state.epoch != epoch {
                    return Err("服务代次已经变化".into());
                }
                state.status.foreground = true;
                state.status.notifications_allowed = Some(policy);
                state.status.last_error = None;
                state.start_pending = None;
                state.stop_pending = false;
                state.renew_at = Instant::now();
                drop(state);
                self.report(
                    Kind::ForegroundConfirmed,
                    None,
                    "Android 已确认 dataSync 前台提升".into(),
                );
                if !policy {
                    self.report(
                        Kind::NotificationRestricted,
                        None,
                        "前台提升已完成，但系统通知权限或通知渠道未开放".into(),
                    );
                }
            }
            Err(error) => {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                state.start_pending = None;
                state.blocked = true;
                drop(state);
                self.fail(format!("实际前台提升失败：{error}"));
                self.stop(epoch, true);
                return Err(error);
            }
        }
        if request.intent.action.as_deref() == Some(ACTION_CANCEL) {
            let key = crate::runtime_host::RunKey {
                run_id: request.intent.run_id.clone().ok_or("停止动作缺少运行ID")?,
                conversation_id: request
                    .intent
                    .conversation_id
                    .clone()
                    .ok_or("停止动作缺少会话ID")?,
            };
            key.validate()?;
            if request.intent.data_uri.as_deref() != Some(super::cancel_uri(&key).as_str()) {
                return Err("停止动作身份URI不匹配".into());
            }
            let cancelled = self.host.cancel_run(&key);
            self.report(
                Kind::CancelRequested,
                Some(key),
                if cancelled {
                    "已按运行身份请求停止任务"
                } else {
                    "该运行已结束，未停止其它任务"
                }
                .into(),
            );
        } else if request
            .intent
            .action
            .as_deref()
            .is_some_and(|action| action != ACTION_REFRESH)
        {
            return Err("未知后台服务动作，未执行任务".into());
        }
        if snapshot.runs.is_empty() || !snapshot.runtime_alive {
            self.stop(epoch, false);
        }
        self.changed.notify_one();
        Ok(())
    }

    fn destroyed(&self, request: &ServiceRequest) -> Result<(), String> {
        let existing = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .service
            .clone();
        let Some(existing) = existing else {
            return Ok(());
        };
        if !framework::same_instance(&existing, &request.instance)? {
            return Ok(());
        }
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let unexpected = !state.stop_pending && !state.desired.runs.is_empty();
        let notified = std::mem::take(&mut state.notified);
        state.pending_cleanup.extend(notified);
        state.cleanup_at = Instant::now();
        state.service = None;
        state.status.foreground = false;
        state.start_pending = None;
        state.stop_pending = false;
        state.update_pending = false;
        state.rendered_revision = None;
        if unexpected {
            state.blocked = true;
        }
        drop(state);
        self.release_wake();
        self.report(
            Kind::Stopped,
            None,
            "已收到 Android 服务销毁回调，前台保护已结束".into(),
        );
        if unexpected {
            self.fail("系统结束了仍有任务的前台服务，请返回应用核对后台保护".into());
        }
        self.changed.notify_one();
        Ok(())
    }

    fn timed_out(&self, request: &ServiceRequest) -> Result<(), String> {
        let (existing, epoch) = {
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            (state.service.clone(), state.epoch)
        };
        if let Some(existing) = existing {
            match framework::same_instance(&existing, &request.instance) {
                Ok(true) => {}
                Ok(false) => {
                    // A timeout on an old instance cannot replace or cancel
                    // the current instance; honor its own stop deadline.
                    return framework::stop_instance(&request.instance);
                }
                Err(error) => {
                    let stopped = framework::stop_instance(&request.instance);
                    return Err(match stopped {
                        Ok(()) => format!("超时服务身份未确认，已独立请求停止：{error}"),
                        Err(stopping) => format!("超时身份核对：{error}；停止请求：{stopping}"),
                    });
                }
            }
        } else {
            return framework::stop_instance(&request.instance);
        }
        // Registry-only lookup includes runs registered after the observer's
        // last snapshot, without entering Store or any tool/network path.
        for key in self.host.publisher().run_keys() {
            self.host.cancel_run(&key);
        }
        self.state.lock().unwrap_or_else(|e| e.into_inner()).blocked = true;
        self.fail(format!(
            "Android 前台服务超时（类型{}），已请求停止相关运行",
            request.flags
        ));
        self.stop(epoch, true);
        self.changed.notify_one();
        Ok(())
    }
}

pub fn handle_service(request: ServiceRequest) -> Result<i32, String> {
    if request.component != SERVICE_CLASS {
        return Err("该 Service 组件没有接入 Agent 后台策略".into());
    }
    let manager = INSTANCE.get().ok_or("后台管理器尚未初始化")?;
    let result = match request.kind {
        1 => manager.bind_service(&request).map(|_| ()),
        2 => manager.started(&request),
        3 => manager.destroyed(&request),
        4 | 5 => manager.timed_out(&request),
        _ => Err("未知 Service 生命周期事件".into()),
    };
    if let Err(error) = &result {
        manager.fail(format!("后台服务回调失败：{error}"));
    }
    result.map(|_| 2) // START_NOT_STICKY: process interruption never resumes a goal implicitly.
}
