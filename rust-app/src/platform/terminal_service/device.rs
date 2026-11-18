use super::{
    ACTION_REFRESH, ACTION_STOP, ForegroundGuard, SERVICE_CLASS, TerminalServiceEvent as Event,
    TerminalServiceStatus, framework, stop_uri, validate_key,
};
use crate::{
    platform::{
        android::{self, ServiceRequest},
        service_framework as fw,
    },
    runtime_host::{RuntimeHost, TerminalKey, TerminalSnapshot, TerminalSubscription},
};
use std::{
    collections::{HashMap, HashSet, VecDeque},
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{
        Arc, Condvar, Mutex, OnceLock, Weak,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::{Duration, Instant},
};

const MAX_EVENTS: usize = 256;
const CONFIRM_LIMIT: Duration = Duration::from_secs(4);
struct State {
    desired: TerminalSnapshot,
    status: TerminalServiceStatus,
    service: Option<fw::Object>,
    epoch: u64,
    start_pending: Option<Instant>,
    update_pending: bool,
    stop_pending: bool,
    blocked: bool,
    rendered: Option<u64>,
    notified: HashSet<String>,
    cleanup: HashSet<String>,
    cleanup_at: Instant,
    events: VecDeque<Event>,
    guards: HashMap<TerminalKey, Weak<AtomicBool>>,
}
struct Manager {
    host: Arc<RuntimeHost>,
    state: Mutex<State>,
    subscription: Mutex<Option<TerminalSubscription>>,
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
            Err("终端后台管理器已有不同宿主或已停止".into())
        };
    }
    if android::paths().ok_or("Android平台尚未初始化")?.sdk_int < 26 {
        return Err("终端前台服务需要 Android API26 或以上".into());
    }
    android::application_context()?;
    let manager = Arc::new(Manager {
        host: host.clone(),
        subscription: Mutex::new(None),
        changed: Condvar::new(),
        state: Mutex::new(State {
            desired: TerminalSnapshot::default(),
            status: TerminalServiceStatus {
                initialized: true,
                ..Default::default()
            },
            service: None,
            epoch: 0,
            start_pending: None,
            update_pending: false,
            stop_pending: false,
            blocked: false,
            rendered: None,
            notified: HashSet::new(),
            cleanup: HashSet::new(),
            cleanup_at: Instant::now(),
            events: VecDeque::new(),
            guards: HashMap::new(),
        }),
    });
    let weak = Arc::downgrade(&manager);
    let subscription = host.attach_terminal_service(move || {
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
        .map_err(|_| "终端后台管理器重复初始化")?;
    manager.emit(Event::Initialized);
    let worker = manager.clone();
    if thread::Builder::new()
        .name("rust-terminal-background".into())
        .spawn(move || {
            if catch_unwind(AssertUnwindSafe(|| worker.run())).is_err() {
                worker.invalidate_protection();
                worker.fail("终端后台管理线程异常结束".into());
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
                let _ = fw::stop_component(SERVICE_CLASS, ACTION_REFRESH);
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
        manager
            .subscription
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take();
        return Err("无法创建终端后台管理线程".into());
    }
    Ok(())
}
fn manager() -> Result<&'static Arc<Manager>, String> {
    INSTANCE.get().ok_or("终端后台管理器尚未初始化".into())
}
pub fn status() -> TerminalServiceStatus {
    INSTANCE
        .get()
        .map(|manager| manager.status())
        .unwrap_or_default()
}
pub fn await_ready(
    key: &TerminalKey,
    cancel: impl Fn() -> bool,
) -> Result<ForegroundGuard, String> {
    validate_key(key)?;
    let manager = manager()?;
    manager.changed.notify_all();
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        if cancel() {
            return Err("终端创建已取消，PTY未启动".into());
        }
        manager.sync_snapshot();
        let mut state = manager
            .state
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if !state.status.initialized || !state.desired.runtime_alive {
            return Err("终端服务宿主已停止，PTY未启动".into());
        }
        if !state.desired.running.iter().any(|current| current == key) {
            return Err("终端待启动身份没有当前服务claim，PTY未启动".into());
        }
        if state.blocked {
            return Err(state
                .status
                .last_error
                .clone()
                .unwrap_or_else(|| "终端前台保护已失败，PTY未启动".into()));
        }
        if state.status.foreground && state.service.is_some() && !state.stop_pending {
            let valid = state
                .guards
                .get(key)
                .and_then(Weak::upgrade)
                .filter(|valid| valid.load(Ordering::Acquire))
                .unwrap_or_else(|| Arc::new(AtomicBool::new(true)));
            state.guards.insert(key.clone(), Arc::downgrade(&valid));
            return Ok(ForegroundGuard {
                key: key.clone(),
                epoch: state.epoch,
                valid,
            });
        }
        if Instant::now() >= deadline {
            return Err("终端真实前台保护确认超时，PTY未启动".into());
        }
        let _ = manager
            .changed
            .wait_timeout(state, Duration::from_millis(40))
            .unwrap_or_else(|error| error.into_inner());
    }
}
pub fn drain_events(limit: usize) -> Vec<Event> {
    let Some(manager) = INSTANCE.get() else {
        return Vec::new();
    };
    let mut state = manager.state.lock().unwrap_or_else(|e| e.into_inner());
    let count = limit.min(MAX_EVENTS).min(state.events.len());
    state.events.drain(..count).collect()
}
pub fn request_refresh() {
    if let Some(manager) = INSTANCE.get() {
        let mut state = manager.state.lock().unwrap_or_else(|e| e.into_inner());
        state.blocked = false;
        state.rendered = None;
        drop(state);
        manager.changed.notify_one();
    }
}
pub fn handle_service(request: ServiceRequest) -> Result<i32, String> {
    if request.component != SERVICE_CLASS {
        return Err("Service组件未接入终端策略".into());
    }
    let manager = manager()?;
    let result = match request.kind {
        1 => manager.bind(&request).map(|_| ()),
        2 => manager.started(&request),
        3 => manager.destroyed(&request),
        4 | 5 => manager.timed_out(&request),
        _ => Err("未知终端Service生命周期".into()),
    };
    if let Err(error) = &result {
        manager.fail(format!("终端服务回调失败：{error}"));
    }
    result.map(|_| 2)
}

impl Manager {
    fn revoke_locked(state: &mut State) -> Vec<TerminalKey> {
        let guards = std::mem::take(&mut state.guards);
        guards
            .into_iter()
            .filter_map(|(key, weak)| {
                weak.upgrade().map(|valid| {
                    valid.store(false, Ordering::Release);
                    key
                })
            })
            .collect()
    }
    fn cancel_keys(&self, keys: Vec<TerminalKey>) {
        for key in keys {
            let accepted = self.host.cancel_terminal(&key);
            self.emit(Event::CancelRequested { key, accepted });
        }
        self.changed.notify_all();
    }
    fn invalidate_protection(&self) {
        let mut state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        state.status.foreground = false;
        state.blocked = true;
        let keys = Self::revoke_locked(&mut state);
        drop(state);
        self.cancel_keys(keys);
    }
    fn status(&self) -> TerminalServiceStatus {
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .status
            .clone()
    }
    fn emit(&self, event: Event) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.events.len() == MAX_EVENTS {
            state.events.pop_front();
        }
        state.events.push_back(event);
    }
    fn fail(&self, error: String) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.status.last_error.as_ref() == Some(&error) {
            return;
        }
        state.status.last_error = Some(error.clone());
        drop(state);
        self.emit(Event::Failed(error));
    }
    fn sync_snapshot(&self) {
        let snapshot = self
            .subscription
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_mut()
            .and_then(|subscription| subscription.poll());
        if let Some(snapshot) = snapshot {
            if snapshot.running.len() > 16
                || snapshot
                    .running
                    .iter()
                    .any(|key| validate_key(key).is_err())
            {
                self.fail("终端服务摘要身份无效或超过16个预算".into());
                return;
            }
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if snapshot.revision >= state.desired.revision {
                state.desired = snapshot;
                let running: HashSet<_> = state.desired.running.iter().cloned().collect();
                state.guards.retain(|key, weak| {
                    if running.contains(key) {
                        weak.strong_count() > 0
                    } else {
                        if let Some(valid) = weak.upgrade() {
                            valid.store(false, Ordering::Release);
                        }
                        false
                    }
                });
                if state.desired.running.is_empty() {
                    state.blocked = false;
                }
            }
            drop(state);
            self.changed.notify_all();
        }
    }
    fn run(self: &Arc<Self>) {
        loop {
            self.sync_snapshot();
            self.reconcile();
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if !state.desired.runtime_alive
                && state.service.is_none()
                && state.start_pending.is_none()
                && state.cleanup.is_empty()
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
        let (start, update, stop, expired, cleanup) = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            let wanted = state.desired.runtime_alive && !state.desired.running.is_empty();
            let expired = state
                .start_pending
                .is_some_and(|instant| instant.elapsed() >= CONFIRM_LIMIT)
                && !state.status.foreground;
            if expired {
                state.start_pending = None;
                state.blocked = true;
            }
            let start = wanted
                && !state.status.foreground
                && state.start_pending.is_none()
                && !state.blocked
                && !state.stop_pending;
            if start {
                state.start_pending = Some(Instant::now());
            }
            let update = if wanted
                && state.status.foreground
                && !state.update_pending
                && !state.stop_pending
                && state.rendered != Some(state.desired.revision)
            {
                state.update_pending = true;
                Some(state.epoch)
            } else {
                None
            };
            let stop = if !wanted && state.service.is_some() && !state.stop_pending {
                state.stop_pending = true;
                Some(state.epoch)
            } else {
                None
            };
            let cleanup = if !state.cleanup.is_empty() && Instant::now() >= state.cleanup_at {
                state.cleanup_at = Instant::now() + Duration::from_secs(20);
                let active = framework::prospective(&state.desired.running);
                state
                    .cleanup
                    .iter()
                    .filter(|tag| !active.contains(*tag))
                    .take(16)
                    .cloned()
                    .collect::<HashSet<_>>()
            } else {
                HashSet::new()
            };
            (start, update, stop, expired, cleanup)
        };
        if start {
            let manager = self.clone();
            if let Err(error) = android::post_to_main(move || manager.request_start()) {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                state.start_pending = None;
                state.blocked = true;
                drop(state);
                self.fail(error);
            }
        }
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
        if expired {
            let manager = self.clone();
            if let Err(error) = android::post_to_main(move || manager.stop_unconfirmed()) {
                self.fail(error);
            }
        }
        if !cleanup.is_empty() {
            let manager = self.clone();
            if let Err(error) = android::post_to_main(move || manager.cleanup(cleanup)) {
                self.fail(error);
            }
        }
    }
    fn request_start(&self) {
        self.sync_snapshot();
        {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if !state.desired.runtime_alive
                || state.desired.running.is_empty()
                || state.status.foreground
                || state.blocked
                || state.stop_pending
            {
                state.start_pending = None;
                return;
            }
        }
        match framework::request_start() {
            Ok(()) => self.emit(Event::ForegroundRequested),
            Err(error) => {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                state.start_pending = None;
                state.blocked = true;
                drop(state);
                self.fail(format!("终端后台启动被拒绝：{error}"));
            }
        }
    }
    fn stop_unconfirmed(&self) {
        {
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if state.status.foreground || state.start_pending.is_some() || !state.blocked {
                return;
            }
        }
        match fw::stop_component(SERVICE_CLASS, ACTION_REFRESH) {
            Ok(true) => self.fail("未确认终端前台提升，系统已接受停止请求".into()),
            Ok(false) => self.fail("未确认终端前台提升，系统没有可停止的组件".into()),
            Err(error) => self.fail(error),
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
            if request.kind != 1 {
                let _ = fw::stop_instance(&request.instance);
                return Err("迟到的终端Service启动回调不属于当前实例".into());
            }
        }
        let service = fw::retain(&request.instance)?;
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.epoch = state.epoch.checked_add(1).ok_or("终端Service代次已耗尽")?;
        state.service = Some(service);
        state.status.foreground = false;
        state.stop_pending = false;
        state.update_pending = false;
        state.rendered = None;
        let keys = Self::revoke_locked(&mut state);
        let epoch = state.epoch;
        drop(state);
        self.cancel_keys(keys);
        Ok(epoch)
    }
    fn started(&self, request: &ServiceRequest) -> Result<(), String> {
        let epoch = self.bind(request)?;
        // Apply an exact stop intent before making a newly claimed PTY ready.
        self.apply_action(request)?;
        self.sync_snapshot();
        let keys = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .desired
            .running
            .clone();
        let confirmed = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .status
            .foreground;
        let promotion = if confirmed {
            Ok(self.status().notifications_allowed.unwrap_or(true))
        } else {
            framework::promote(&request.instance, &keys)
        };
        match promotion {
            Ok(allowed) => {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                if state.epoch != epoch || state.service.is_none() || state.blocked {
                    drop(state);
                    let _ = fw::stop_instance(&request.instance);
                    return Err("终端前台提升结束时实例已变化，未确认旧实例保护".into());
                }
                state.status.foreground = true;
                state.status.notifications_allowed = Some(allowed);
                state.start_pending = None;
                state.stop_pending = false;
                state.status.last_error = None;
                drop(state);
                self.emit(Event::ForegroundConfirmed);
                if !allowed {
                    self.emit(Event::NotificationRestricted);
                }
            }
            Err(error) => {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                state.start_pending = None;
                state.blocked = true;
                drop(state);
                self.stop(epoch, true);
                return Err(error);
            }
        }
        if keys.is_empty() {
            self.stop(epoch, false);
        }
        self.changed.notify_all();
        Ok(())
    }
    fn apply_action(&self, request: &ServiceRequest) -> Result<(), String> {
        match request.intent.action.as_deref() {
            Some(ACTION_STOP) => {
                let key = TerminalKey {
                    process_token: request
                        .intent
                        .process_token
                        .clone()
                        .ok_or("停止终端缺少进程令牌")?,
                    session_id: request
                        .intent
                        .terminal_session_id
                        .as_deref()
                        .ok_or("停止终端缺少会话id")?
                        .parse::<u64>()
                        .map_err(|_| "终端会话id无效")?,
                };
                validate_key(&key)?;
                if request.intent.data_uri.as_deref() != Some(stop_uri(&key).as_str()) {
                    return Err("终端停止身份URI不匹配".into());
                }
                let accepted = self.host.cancel_terminal(&key);
                self.emit(Event::CancelRequested { key, accepted });
            }
            Some(ACTION_REFRESH) | None => {}
            _ => return Err("未知终端Service动作".into()),
        }
        Ok(())
    }
    fn update(&self, epoch: u64) {
        self.sync_snapshot();
        let (keys, previous, revision) = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            state.update_pending = false;
            if state.epoch != epoch || !state.status.foreground || state.stop_pending {
                return;
            }
            let keys = state.desired.running.clone();
            let previous = state.notified.clone();
            state.notified.extend(framework::prospective(&keys));
            (keys, previous, state.desired.revision)
        };
        if keys.is_empty() {
            self.stop(epoch, false);
            return;
        }
        let result = framework::update(&keys, &previous);
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.epoch == epoch {
            state.rendered = Some(revision);
            if let Ok((allowed, current)) = &result {
                state.status.notifications_allowed = Some(*allowed);
                state.notified = current.clone();
            }
        }
        drop(state);
        if let Err(error) = result {
            self.fail(format!("终端通知更新失败：{error}"));
        }
    }
    fn stop(&self, epoch: u64, force: bool) {
        self.sync_snapshot();
        let service = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if state.epoch != epoch {
                return;
            }
            state.stop_pending = false;
            if !force && state.desired.runtime_alive && !state.desired.running.is_empty() {
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
                let cards = state.notified.clone();
                state.cleanup.extend(cards);
                state.cleanup_at = Instant::now();
            }
            let keys = if state.epoch == epoch && (outcome.foreground_removed || force) {
                Self::revoke_locked(&mut state)
            } else {
                Vec::new()
            };
            drop(state);
            self.cancel_keys(keys);
            if outcome.stop_requested {
                self.emit(Event::StopRequested);
            }
            for error in outcome.errors {
                self.fail(error);
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
        self.sync_snapshot();
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let unexpected = !state.stop_pending && !state.desired.running.is_empty();
        let keys = state.desired.running.clone();
        let cards = std::mem::take(&mut state.notified);
        state.cleanup.extend(cards);
        state.cleanup_at = Instant::now();
        state.service = None;
        state.status.foreground = false;
        state.start_pending = None;
        state.update_pending = false;
        state.stop_pending = false;
        state.rendered = None;
        let revoked = Self::revoke_locked(&mut state);
        if unexpected {
            state.blocked = true;
        }
        drop(state);
        self.cancel_keys(revoked);
        self.emit(Event::Stopped);
        if unexpected {
            self.emit(Event::ProtectionLost { keys });
            self.fail("终端前台保护已丢失，PTY仍由终端线程管理".into());
        }
        self.changed.notify_all();
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
        self.sync_snapshot();
        let keys = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if state.epoch != epoch {
                return Ok(());
            }
            state.blocked = true;
            Self::revoke_locked(&mut state);
            state.desired.running.clone()
        };
        for key in keys {
            let accepted = self.host.cancel_terminal(&key);
            self.emit(Event::CancelRequested { key, accepted });
        }
        self.fail(format!(
            "终端服务超时（类型{}），已请求终止所属会话",
            request.flags
        ));
        self.stop(epoch, true);
        self.changed.notify_one();
        Ok(())
    }
    fn cleanup(&self, cards: HashSet<String>) {
        self.sync_snapshot();
        let active = framework::prospective(
            &self
                .state
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .desired
                .running,
        );
        for card in cards {
            if active.contains(&card) {
                continue;
            }
            match framework::clear_card(&card) {
                Ok(()) => {
                    let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                    state.cleanup.remove(&card);
                    state.cleanup_at = Instant::now();
                }
                Err(error) => self.fail(error),
            }
        }
    }
}
