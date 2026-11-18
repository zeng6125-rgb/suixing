use super::{
    ACTION_CANCEL, ACTION_EXECUTE, ACTION_FIRE, ACTION_RESUME, ALARM_URI, AlarmRequest,
    AlarmResult, BOOT_RECEIVER_CLASS, FireIdentity, RECEIVER_CLASS, RearmReason, SERVICE_CLASS,
    SchedulerEvent, SchedulerStatus, framework,
};
use crate::{
    platform::{
        android::{self, IntentFacts, ReceiverRequest, ServiceRequest},
        service_framework as fw,
    },
    runtime_host::{RunKey, RuntimeHost},
};
use std::{
    collections::{HashMap, VecDeque},
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{Arc, Condvar, Mutex, OnceLock},
    thread,
    time::{Duration, Instant},
};

const MAX_FIRES: usize = 128;
const MAX_EVENTS: usize = 512;
const CONFIRM_LIMIT: Duration = Duration::from_secs(4);
struct Fire {
    identity: FireIdentity,
    run: Option<RunKey>,
    ready: bool,
    ready_at: Option<Instant>,
    requested: Instant,
}
struct State {
    status: SchedulerStatus,
    events: VecDeque<SchedulerEvent>,
    arm: Option<AlarmRequest>,
    fires: HashMap<String, Fire>,
    service: Option<fw::Object>,
    epoch: u64,
    revision: u64,
    rendered: Option<u64>,
    update_pending: bool,
    stop_pending: bool,
    notified: Vec<FireIdentity>,
    cleanup: VecDeque<FireIdentity>,
    cleanup_at: Instant,
    deferred: VecDeque<FireIdentity>,
}
struct Manager {
    host: Arc<RuntimeHost>,
    state: Mutex<State>,
    changed: Condvar,
}
static INSTANCE: OnceLock<Arc<Manager>> = OnceLock::new();
static INSTALL: Mutex<()> = Mutex::new(());

pub fn start(host: Arc<RuntimeHost>) -> Result<(), String> {
    let _install = INSTALL.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(manager) = INSTANCE.get() {
        return if Arc::ptr_eq(&manager.host, &host) && manager.status().initialized {
            Ok(())
        } else {
            Err("提醒管理器已有不同宿主或已停止".into())
        };
    }
    if android::paths().ok_or("Android平台尚未初始化")?.sdk_int < 26 {
        return Err("提醒服务需要 Android API26 或以上".into());
    }
    android::application_context()?;
    let manager = Arc::new(Manager {
        host,
        changed: Condvar::new(),
        state: Mutex::new(State {
            status: SchedulerStatus {
                initialized: true,
                ..Default::default()
            },
            events: VecDeque::new(),
            arm: None,
            fires: HashMap::new(),
            service: None,
            epoch: 0,
            revision: 0,
            rendered: None,
            update_pending: false,
            stop_pending: false,
            notified: Vec::new(),
            cleanup: VecDeque::new(),
            cleanup_at: Instant::now(),
            deferred: VecDeque::new(),
        }),
    });
    let weak = Arc::downgrade(&manager);
    let lifetime = manager.host.attach_service(move || {
        if let Some(manager) = weak.upgrade() {
            manager.changed.notify_one();
        }
    })?;
    INSTANCE
        .set(manager.clone())
        .map_err(|_| "提醒管理器重复初始化")?;
    let worker = manager.clone();
    if thread::Builder::new()
        .name("rust-reminder-platform".into())
        .spawn(move || {
            if catch_unwind(AssertUnwindSafe(|| worker.run(lifetime))).is_err() {
                worker.fail("提醒平台线程异常结束".into());
                worker
                    .state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .status
                    .initialized = false;
                let _ = fw::stop_component(SERVICE_CLASS, ACTION_EXECUTE);
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
        return Err("无法创建提醒平台线程".into());
    }
    Ok(())
}
fn manager() -> Result<&'static Arc<Manager>, String> {
    INSTANCE.get().ok_or("提醒管理器尚未初始化".into())
}
pub fn status() -> SchedulerStatus {
    INSTANCE
        .get()
        .map(|manager| manager.status())
        .unwrap_or_default()
}
pub fn drain_events(limit: usize) -> Vec<SchedulerEvent> {
    let Some(manager) = INSTANCE.get() else {
        return Vec::new();
    };
    let mut state = manager.state.lock().unwrap_or_else(|e| e.into_inner());
    let count = limit.min(MAX_EVENTS).min(state.events.len());
    state.events.drain(..count).collect()
}
pub fn request_arm(request: AlarmRequest) -> Result<(), String> {
    request.validate()?;
    let manager = manager()?;
    let mut state = manager.state.lock().unwrap_or_else(|e| e.into_inner());
    if !state.status.initialized {
        return Err("提醒平台线程未运行".into());
    }
    state.arm = Some(request);
    drop(state);
    manager.changed.notify_one();
    Ok(())
}
pub fn request_rearm(reason: RearmReason) -> Result<(), String> {
    manager()?.emit(
        SchedulerEvent::RearmRequested {
            reason,
            completion: None,
        },
        false,
    )
}
pub fn request_execution(identity: FireIdentity) -> Result<(), String> {
    request_execution_inner(identity, false)
}
pub fn request_resume(identity: FireIdentity) -> Result<(), String> {
    request_execution_inner(identity, true)
}
fn request_execution_inner(identity: FireIdentity, user_initiated: bool) -> Result<(), String> {
    identity.validate()?;
    let manager = manager()?;
    if !manager.add_fire(identity.clone())? {
        return Ok(());
    }
    let retained_identity = identity.clone();
    let worker = manager.clone();
    if let Err(error) = android::post_to_main(move || {
        // A completion/cancel can arrive before this main-thread effect.
        if !worker.has_fire(&identity) {
            return;
        }
        let started = if user_initiated {
            framework::request_resume(&identity)
        } else {
            framework::request_start(&identity)
        };
        if let Err(error) = started {
            worker.defer(identity, format!("提醒后台启动被拒绝：{error}"));
        }
    }) {
        manager.defer(
            retained_identity,
            format!("提醒启动无法投递主线程：{error}"),
        );
        return Err(error);
    }
    manager.changed.notify_one();
    Ok(())
}
pub fn bind_run(identity: &FireIdentity, key: RunKey) -> Result<(), String> {
    identity.validate()?;
    key.validate()?;
    let manager = manager()?;
    let mut state = manager.state.lock().unwrap_or_else(|e| e.into_inner());
    let fire = state
        .fires
        .get_mut(&identity.fire_token)
        .filter(|fire| fire.identity == *identity && fire.ready)
        .ok_or("提醒尚未确认前台执行")?;
    if fire.run.as_ref().is_some_and(|existing| existing != &key) {
        return Err("提醒已绑定其它运行".into());
    }
    fire.run = Some(key);
    Ok(())
}
pub fn execution_finished(identity: &FireIdentity) -> Result<(), String> {
    identity.validate()?;
    let manager = manager()?;
    let mut state = manager.state.lock().unwrap_or_else(|e| e.into_inner());
    if state
        .fires
        .get(&identity.fire_token)
        .is_some_and(|fire| fire.identity != *identity)
    {
        return Err("提醒结束身份不匹配".into());
    }
    state.fires.remove(&identity.fire_token);
    state
        .deferred
        .retain(|current| current.fire_token != identity.fire_token);
    state.cleanup.push_back(identity.clone());
    state.cleanup_at = Instant::now();
    state.revision = state.revision.saturating_add(1);
    state.status.pending_fires = state.fires.len();
    drop(state);
    manager.changed.notify_one();
    Ok(())
}
pub fn handle_receiver(request: ReceiverRequest) -> Result<(), String> {
    let manager = manager()?;
    if !manager.status().initialized {
        return Err("提醒平台线程已停止，未接受广播任务".into());
    }
    if request.component == RECEIVER_CLASS {
        if request.intent.action.as_deref() != Some(ACTION_FIRE)
            || request.intent.data_uri.as_deref() != Some(ALARM_URI)
        {
            return Err("提醒广播动作或身份URI不匹配".into());
        }
        let identity = read_identity(&request.intent)?;
        manager.emit(
            SchedulerEvent::FireReceived {
                identity,
                completion: request.completion,
            },
            false,
        )
    } else if request.component == BOOT_RECEIVER_CLASS {
        let reason = match request.intent.action.as_deref() {
            Some("android.intent.action.BOOT_COMPLETED") => RearmReason::Boot,
            Some("android.intent.action.TIME_SET") => RearmReason::ClockChanged,
            Some("android.intent.action.TIMEZONE_CHANGED") => RearmReason::TimezoneChanged,
            Some("android.intent.action.MY_PACKAGE_REPLACED") => RearmReason::PackageReplaced,
            Some("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED") => {
                RearmReason::ExactPermissionChanged
            }
            _ => return Err("BootReceiver拒绝非系统重排动作".into()),
        };
        manager.emit(
            SchedulerEvent::RearmRequested {
                reason,
                completion: Some(request.completion),
            },
            false,
        )
    } else {
        Err("Receiver组件未接入提醒策略".into())
    }
}
fn read_identity(intent: &IntentFacts) -> Result<FireIdentity, String> {
    let identity = FireIdentity {
        generation: intent
            .alarm_generation
            .clone()
            .ok_or("提醒缺少闹钟generation")?,
        fire_token: intent.fire_token.clone().ok_or("提醒缺少触发令牌")?,
        schedule_id: intent.schedule_id.clone().ok_or("提醒缺少id")?,
        scheduled_at: intent.scheduled_at.ok_or("提醒缺少触发时刻")?,
    };
    identity.validate()?;
    Ok(identity)
}
pub fn handle_service(request: ServiceRequest) -> Result<i32, String> {
    if request.component != SERVICE_CLASS {
        return Err("Service组件未接入提醒策略".into());
    }
    let manager = manager()?;
    let result = match request.kind {
        1 => manager.bind(&request).map(|_| ()),
        2 => manager.started(&request),
        3 => manager.destroyed(&request),
        4 | 5 => manager.timed_out(&request),
        _ => Err("未知提醒Service生命周期".into()),
    };
    if let Err(error) = &result {
        manager.fail(format!("提醒服务回调失败：{error}"));
    }
    result.map(|_| 2)
}

impl Manager {
    fn status(&self) -> SchedulerStatus {
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .status
            .clone()
    }
    fn emit(&self, event: SchedulerEvent, critical: bool) -> Result<(), String> {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if let SchedulerEvent::AlarmFinished(result) = &event {
            state.events.retain(|event| !matches!(event, SchedulerEvent::AlarmFinished(old) if old.generation == result.generation));
        }
        let budget = if critical {
            MAX_EVENTS
        } else {
            MAX_EVENTS - MAX_FIRES
        };
        if state.events.len() >= budget {
            return Err("提醒事件队列已满，未接受新请求".into());
        }
        state.events.push_back(event);
        Ok(())
    }
    fn fail(&self, error: String) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.status.last_error.as_ref() == Some(&error) {
            return;
        }
        state.status.last_error = Some(error.clone());
        drop(state);
        let _ = self.emit(SchedulerEvent::Failed(error), true);
    }
    fn add_fire(&self, identity: FireIdentity) -> Result<bool, String> {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if !state.status.initialized {
            return Err("提醒平台线程未运行".into());
        }
        if let Some(fire) = state.fires.get(&identity.fire_token) {
            return if fire.identity == identity {
                Ok(false)
            } else {
                Err("提醒触发令牌已复用".into())
            };
        }
        if state.fires.len() >= MAX_FIRES {
            return Err("同时执行的提醒超过128条预算".into());
        }
        state
            .deferred
            .retain(|current| current.fire_token != identity.fire_token);
        state.fires.insert(
            identity.fire_token.clone(),
            Fire {
                identity,
                run: None,
                ready: false,
                ready_at: None,
                requested: Instant::now(),
            },
        );
        state.revision = state.revision.saturating_add(1);
        state.status.pending_fires = state.fires.len();
        Ok(true)
    }
    fn has_fire(&self, identity: &FireIdentity) -> bool {
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .fires
            .get(&identity.fire_token)
            .is_some_and(|fire| fire.identity == *identity)
    }
    fn is_deferred(&self, identity: &FireIdentity) -> bool {
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .deferred
            .iter()
            .any(|current| current == identity)
    }
    fn defer(&self, identity: FireIdentity, error: String) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if !state.fires.contains_key(&identity.fire_token) {
            return;
        }
        if state
            .fires
            .get(&identity.fire_token)
            .is_some_and(|fire| fire.identity == identity && fire.ready && fire.run.is_some())
        {
            return;
        }
        state.fires.remove(&identity.fire_token);
        // A deferred card uses the same tag as the running card, but it must
        // survive an idle stop and any old posted notification cleanup.
        state
            .notified
            .retain(|current| current.fire_token != identity.fire_token);
        state
            .cleanup
            .retain(|current| current.fire_token != identity.fire_token);
        if !state.deferred.iter().any(|current| current == &identity) {
            state.deferred.push_back(identity.clone());
        }
        // Bound process-local notification protection. Durable FireRecords
        // remain with Store, so evicted cards can still be resumed in the UI.
        if state.deferred.len() > MAX_EVENTS {
            if let Some(evicted) = state.deferred.pop_front() {
                state.cleanup.push_back(evicted);
                state.cleanup_at = Instant::now();
            }
        }
        state.revision = state.revision.saturating_add(1);
        state.status.pending_fires = state.fires.len();
        drop(state);
        let _ = self.emit(
            SchedulerEvent::Deferred {
                identity: identity.clone(),
                error: error.clone(),
            },
            true,
        );
        self.fail(error);
        let _ = android::post_to_main(move || {
            if let Some(manager) = INSTANCE.get() {
                match framework::publish_fire(&identity, true) {
                    Ok(false) => {
                        let _ = manager.emit(SchedulerEvent::NotificationRestricted, true);
                    }
                    Ok(true) => {}
                    Err(error) => manager.fail(format!("提醒等待执行通知未确认：{error}")),
                }
            }
        });
        self.changed.notify_one();
    }
    fn run(self: &Arc<Self>, mut lifetime: crate::runtime_host::ServiceSubscription) {
        loop {
            if lifetime
                .poll()
                .is_some_and(|snapshot| !snapshot.runtime_alive)
            {
                let events = {
                    let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                    state.status.initialized = false;
                    state.arm = None;
                    std::mem::take(&mut state.events)
                };
                // ReceiverCompletion drops can call JNI; never drop them while
                // the State mutex is held or wait for an unavailable actor.
                drop(events);
                let _ = fw::stop_component(SERVICE_CLASS, ACTION_EXECUTE);
                return;
            }
            let request = self
                .state
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .arm
                .take();
            if let Some(request) = request {
                let result = AlarmResult {
                    generation: request.generation.clone(),
                    outcome: framework::arm(&request),
                };
                self.state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .status
                    .alarm = Some(result.clone());
                if let Err(error) = self.emit(SchedulerEvent::AlarmFinished(result), true) {
                    self.fail(error);
                }
            }
            let expired: Vec<_> = self
                .state
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .fires
                .values()
                .filter(|fire| {
                    (!fire.ready && fire.requested.elapsed() >= CONFIRM_LIMIT)
                        || (fire.run.is_none()
                            && fire
                                .ready_at
                                .is_some_and(|ready| ready.elapsed() >= Duration::from_secs(30)))
                })
                .map(|fire| fire.identity.clone())
                .collect();
            for identity in expired {
                self.defer(identity, "提醒前台提升或运行绑定未在期限内确认".into());
            }
            self.reconcile();
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            let wait = if state.fires.is_empty()
                && state.service.is_none()
                && state.arm.is_none()
                && state.cleanup.is_empty()
            {
                Duration::from_secs(20)
            } else {
                Duration::from_millis(250)
            };
            let _ = self
                .changed
                .wait_timeout(state, wait)
                .unwrap_or_else(|e| e.into_inner());
        }
    }
    fn reconcile(self: &Arc<Self>) {
        let (update, stop, cleanup) = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            let stop = if state.service.is_some() && state.fires.is_empty() && !state.stop_pending {
                state.stop_pending = true;
                Some(state.epoch)
            } else {
                None
            };
            let update = if state.status.foreground
                && !state.fires.is_empty()
                && !state.update_pending
                && state.rendered != Some(state.revision)
                && !state.stop_pending
            {
                state.update_pending = true;
                Some(state.epoch)
            } else {
                None
            };
            let count = if Instant::now() >= state.cleanup_at {
                state.cleanup.len().min(16)
            } else {
                0
            };
            if count > 0 {
                state.cleanup_at = Instant::now() + Duration::from_secs(20);
            }
            let cleanup: Vec<_> = state.cleanup.drain(..count).collect();
            (update, stop, cleanup)
        };
        if let Some(epoch) = update {
            let manager = self.clone();
            if let Err(error) = android::post_to_main(move || manager.update(epoch)) {
                self.state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .update_pending = false;
                self.fail(error);
            }
        }
        if let Some(epoch) = stop {
            let manager = self.clone();
            if let Err(error) = android::post_to_main(move || manager.stop(epoch, false)) {
                self.state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .stop_pending = false;
                self.fail(error);
            }
        }
        if !cleanup.is_empty() {
            let retained = cleanup.clone();
            let manager = self.clone();
            if let Err(error) = android::post_to_main(move || {
                for identity in cleanup {
                    if manager.has_fire(&identity) || manager.is_deferred(&identity) {
                        continue;
                    }
                    if let Err(error) = framework::clear_fire(&identity) {
                        manager.fail(error);
                        manager
                            .state
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .cleanup
                            .push_back(identity);
                    } else {
                        manager
                            .state
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .cleanup_at = Instant::now();
                    }
                }
            }) {
                self.state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .cleanup
                    .extend(retained);
                self.fail(error);
            }
        }
    }
    fn bind(&self, request: &ServiceRequest) -> Result<u64, String> {
        let current = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .service
            .clone();
        if let Some(current) = current {
            if fw::same_instance(&current, &request.instance)? {
                return Ok(self.state.lock().unwrap_or_else(|e| e.into_inner()).epoch);
            }
        }
        let service = framework::retain(&request.instance)?;
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.epoch = state.epoch.checked_add(1).ok_or("提醒服务代次已耗尽")?;
        state.service = Some(service);
        state.status.foreground = false;
        state.stop_pending = false;
        state.rendered = None;
        Ok(state.epoch)
    }
    fn started(&self, request: &ServiceRequest) -> Result<(), String> {
        let epoch = self.bind(request)?;
        let count = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .fires
            .len()
            .max(1);
        let confirmed = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .status
            .foreground;
        let promotion = if confirmed {
            Ok(self.status().notifications_allowed.unwrap_or(true))
        } else {
            framework::promote(&request.instance, count)
        };
        match promotion {
            Ok(allowed) => {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                state.status.foreground = true;
                state.status.notifications_allowed = Some(allowed);
                state.stop_pending = false;
                drop(state);
                if !allowed {
                    let _ = self.emit(SchedulerEvent::NotificationRestricted, true);
                }
            }
            Err(error) => {
                self.stop(epoch, true);
                if let Ok(identity) = read_identity(&request.intent) {
                    self.defer(identity, format!("提醒前台提升失败：{error}"));
                }
                return Err(error);
            }
        }
        let identity = read_identity(&request.intent)?;
        match request.intent.action.as_deref() {
            Some(ACTION_EXECUTE) | Some(ACTION_RESUME) => {
                if request.intent.data_uri.as_deref() != Some(identity.execution_uri().as_str()) {
                    return Err("提醒执行身份URI不匹配".into());
                }
                let user_initiated = request.intent.action.as_deref() == Some(ACTION_RESUME);
                if !user_initiated && !self.has_fire(&identity) {
                    self.stop(epoch, false);
                    // A delayed start is an acknowledged no-op. Returning an
                    // error would invoke JNI stopSelf and disturb other fires.
                    return Ok(());
                }
                if user_initiated {
                    self.add_fire(identity.clone())?;
                }
                let already = {
                    let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                    let fire = state
                        .fires
                        .get_mut(&identity.fire_token)
                        .ok_or("提醒已结束")?;
                    let already = fire.ready;
                    fire.ready = true;
                    if !already {
                        fire.ready_at = Some(Instant::now());
                    }
                    state.revision = state.revision.saturating_add(1);
                    already
                };
                if !already {
                    let published = self.emit(
                        SchedulerEvent::Ready {
                            identity: identity.clone(),
                            user_initiated,
                        },
                        true,
                    );
                    if let Err(error) = published {
                        self.defer(identity, error.clone());
                        return Err(error);
                    }
                }
            }
            Some(ACTION_CANCEL) => {
                if request.intent.data_uri.as_deref() != Some(identity.cancel_uri().as_str()) {
                    return Err("提醒停止身份URI不匹配".into());
                }
                let run = self
                    .state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .fires
                    .get(&identity.fire_token)
                    .filter(|fire| fire.identity == identity)
                    .and_then(|fire| fire.run.clone());
                if let Some(run) = run {
                    self.host.cancel_run(&run);
                }
                self.emit(SchedulerEvent::CancelRequested(identity), true)?;
            }
            _ => return Err("未知提醒Service动作".into()),
        }
        self.changed.notify_one();
        Ok(())
    }
    fn update(&self, epoch: u64) {
        let (identities, previous, revision) = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            state.update_pending = false;
            if state.epoch != epoch || !state.status.foreground || state.stop_pending {
                return;
            }
            let identities: Vec<_> = state
                .fires
                .values()
                .filter(|fire| fire.ready)
                .map(|fire| fire.identity.clone())
                .collect();
            let previous = state.notified.clone();
            for identity in &identities {
                if !state.notified.iter().any(|old| old == identity) {
                    state.notified.push(identity.clone());
                }
            }
            (identities, previous, state.revision)
        };
        if identities.is_empty() {
            return;
        }
        let result = framework::update(&identities, &previous);
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.epoch == epoch {
            state.rendered = Some(revision);
            if let Ok(allowed) = &result {
                state.notified = identities;
                state.status.notifications_allowed = Some(*allowed);
            }
        }
        drop(state);
        if let Err(error) = result {
            self.fail(format!("提醒通知更新失败：{error}"));
        }
    }
    fn stop(&self, epoch: u64, force: bool) {
        let service = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if state.epoch != epoch {
                return;
            }
            if !force && !state.fires.is_empty() {
                state.stop_pending = false;
                return;
            }
            state.service.clone()
        };
        if let Some(service) = service {
            let outcome = fw::stop(&service);
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if state.epoch == epoch {
                if outcome.foreground_removed {
                    state.status.foreground = false;
                }
                state.stop_pending = outcome.stop_requested;
            }
            drop(state);
            for error in outcome.errors {
                self.fail(format!("提醒服务停止未完全确认：{error}"));
            }
        }
    }
    fn destroyed(&self, request: &ServiceRequest) -> Result<(), String> {
        let current = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .service
            .clone();
        let Some(current) = current else {
            return Ok(());
        };
        if !fw::same_instance(&current, &request.instance)? {
            return Ok(());
        }
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let unexpected = !state.stop_pending && !state.fires.is_empty();
        let identities = state
            .fires
            .values()
            .map(|fire| fire.identity.clone())
            .collect();
        let cards = std::mem::take(&mut state.notified);
        state.cleanup.extend(cards);
        state.cleanup_at = Instant::now();
        state.service = None;
        state.status.foreground = false;
        state.stop_pending = false;
        state.update_pending = false;
        state.rendered = None;
        drop(state);
        if unexpected {
            self.emit(
                SchedulerEvent::ForegroundLost {
                    identities,
                    error: "提醒服务已销毁；运行和Agent后台保护需核对".into(),
                },
                true,
            )?;
        }
        self.changed.notify_one();
        Ok(())
    }
    fn timed_out(&self, request: &ServiceRequest) -> Result<(), String> {
        let (current, epoch) = {
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            (state.service.clone(), state.epoch)
        };
        if let Some(current) = current {
            match fw::same_instance(&current, &request.instance) {
                Ok(true) => {}
                Ok(false) => return fw::stop_instance(&request.instance),
                Err(error) => {
                    let _ = fw::stop_instance(&request.instance);
                    return Err(error);
                }
            }
        } else {
            return fw::stop_instance(&request.instance);
        }
        let fires: Vec<_> = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .fires
            .values()
            .map(|fire| (fire.identity.clone(), fire.run.clone()))
            .collect();
        for (identity, run) in fires {
            if let Some(run) = run {
                self.host.cancel_run(&run);
            }
            let _ = self.emit(SchedulerEvent::CancelRequested(identity), true);
        }
        self.fail(format!(
            "提醒服务超时（类型{}），已请求停止所属运行",
            request.flags
        ));
        self.stop(epoch, true);
        self.changed.notify_one();
        Ok(())
    }
}
