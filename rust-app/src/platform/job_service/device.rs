use super::{
    ACTION_REFRESH, ACTION_STOP, JobServiceEvent as Event, JobServiceStatus, SERVICE_CLASS,
    framework, stop_uri, validate_key,
};
use crate::{
    jobs::{
        JobKey, Manager as JobManager, Protection, Snapshot as JobSnapshot,
        Subscription as JobSubscription,
    },
    platform::{
        android::{self, ServiceRequest},
        service_framework as fw,
    },
};
use std::{
    collections::{HashSet, VecDeque},
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{Arc, Condvar, Mutex, OnceLock},
    thread,
    time::{Duration, Instant},
};

const MAX_EVENTS: usize = 256;
const CONFIRM_LIMIT: Duration = Duration::from_secs(4);
struct State {
    desired: JobSnapshot,
    status: JobServiceStatus,
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
}
struct Manager {
    jobs: Arc<JobManager>,
    state: Mutex<State>,
    subscription: Mutex<Option<JobSubscription>>,
    changed: Condvar,
}
static INSTANCE: OnceLock<Arc<Manager>> = OnceLock::new();
static INSTALL: Mutex<()> = Mutex::new(());
pub fn start(jobs: Arc<JobManager>) -> Result<(), String> {
    let _install = INSTALL.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(manager) = INSTANCE.get() {
        return if Arc::ptr_eq(&manager.jobs, &jobs) && manager.status().initialized {
            Ok(())
        } else {
            Err("作业服务管理器已有不同宿主或已停止".into())
        };
    }
    if android::paths().ok_or("Android平台尚未初始化")?.sdk_int < 26 {
        return Err("后台作业前台服务需要 Android API26 或以上".into());
    }
    android::application_context()?;
    let manager = Arc::new(Manager {
        jobs: jobs.clone(),
        subscription: Mutex::new(None),
        changed: Condvar::new(),
        state: Mutex::new(State {
            desired: jobs.snapshot(),
            status: JobServiceStatus {
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
        }),
    });
    let weak = Arc::downgrade(&manager);
    let subscription = jobs.subscribe(move || {
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
        .map_err(|_| "作业服务管理器重复初始化")?;
    manager.emit(Event::Initialized);
    let worker = manager.clone();
    if thread::Builder::new()
        .name("rust-job-background".into())
        .spawn(move || {
            if catch_unwind(AssertUnwindSafe(|| worker.run())).is_err() {
                worker.fail("作业服务管理线程异常结束".into());
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
        return Err("无法创建作业服务管理线程".into());
    }
    Ok(())
}
fn manager() -> Result<&'static Arc<Manager>, String> {
    INSTANCE.get().ok_or("作业服务管理器尚未初始化".into())
}
fn revoke_unknown(manager: &Manager, error: &str) {
    let mut state = manager.state.lock().unwrap_or_else(|e| e.into_inner());
    state.status.foreground = false;
    state.blocked = true;
    manager
        .jobs
        .set_protection_state(Protection::Failed(error.into()));
    drop(state);
    manager.jobs.notify_protection();
}
pub fn status() -> JobServiceStatus {
    INSTANCE
        .get()
        .map(|manager| manager.status())
        .unwrap_or_default()
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
        let confirmed = state.status.foreground;
        manager.jobs.set_protection_state(if confirmed {
            Protection::Confirmed
        } else {
            Protection::Requested
        });
        drop(state);
        manager.jobs.notify_protection();
        manager.changed.notify_one();
    }
}
pub fn handle_service(request: ServiceRequest) -> Result<i32, String> {
    if request.component != SERVICE_CLASS {
        return Err("Service组件未接入后台作业策略".into());
    }
    let manager = manager()?;
    let result = match request.kind {
        1 => manager.bind(&request).map(|_| ()),
        2 => manager.started(&request),
        3 => manager.destroyed(&request),
        4 | 5 => manager.timed_out(&request),
        _ => Err("未知后台作业Service生命周期".into()),
    };
    if let Err(error) = &result {
        manager.fail(format!("后台作业服务回调失败：{error}"));
    }
    result.map(|_| 2)
}

impl Manager {
    fn status(&self) -> JobServiceStatus {
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
        let confirmed = state.status.foreground;
        if !confirmed {
            self.jobs
                .set_protection_state(Protection::Failed(error.clone()));
        }
        drop(state);
        self.jobs.notify_protection();
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
                    .any(|row| validate_key(&row.key).is_err())
            {
                self.fail("后台作业服务摘要身份无效或超过16个预算".into());
                return;
            }
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if snapshot.revision >= state.desired.revision {
                state.desired = snapshot;
                if state.desired.running.is_empty() {
                    state.blocked = false;
                }
            }
        }
    }
    fn run(self: &Arc<Self>) {
        loop {
            self.sync_snapshot();
            self.reconcile();
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if !state.desired.alive
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
            let wanted = state.desired.alive && !state.desired.running.is_empty();
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
                let active = framework::prospective(
                    &state
                        .desired
                        .running
                        .iter()
                        .map(|row| row.key.clone())
                        .collect::<Vec<_>>(),
                );
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
            if !state.desired.alive
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
                self.fail(format!("作业服务后台启动被拒绝：{error}"));
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
            Ok(true) => self.fail("未确认后台作业前台提升，系统已接受停止请求".into()),
            Ok(false) => self.fail("未确认后台作业前台提升，系统没有可停止的组件".into()),
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
        }
        let service = fw::retain(&request.instance)?;
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.epoch = state
            .epoch
            .checked_add(1)
            .ok_or("后台作业Service代次已耗尽")?;
        state.service = Some(service);
        state.status.foreground = false;
        state.stop_pending = false;
        state.update_pending = false;
        state.rendered = None;
        self.jobs.set_protection_state(Protection::Unavailable);
        let epoch = state.epoch;
        drop(state);
        self.jobs.notify_protection();
        Ok(epoch)
    }
    fn started(&self, request: &ServiceRequest) -> Result<(), String> {
        let current = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .service
            .clone();
        if let Some(current) = current {
            if !fw::same_instance(&current, &request.instance)? {
                return Err("旧作业Service实例的启动已忽略".into());
            }
        }
        let epoch = self.bind(request)?;
        self.sync_snapshot();
        let keys = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .desired
            .running
            .iter()
            .map(|row| row.key.clone())
            .collect::<Vec<_>>();
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
                if state.epoch != epoch {
                    return Ok(());
                }
                state.status.foreground = true;
                state.status.notifications_allowed = Some(allowed);
                state.start_pending = None;
                state.stop_pending = false;
                state.status.last_error = None;
                self.jobs.set_protection_state(Protection::Confirmed);
                drop(state);
                self.jobs.notify_protection();
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
        match request.intent.action.as_deref() {
            Some(ACTION_STOP) => {
                let key = JobKey {
                    process_token: request
                        .intent
                        .process_token
                        .clone()
                        .ok_or("停止后台作业缺少进程令牌")?,
                    id: request.intent.job_id.clone().ok_or("停止作业缺少id")?,
                };
                validate_key(&key)?;
                if request.intent.data_uri.as_deref() != Some(stop_uri(&key).as_str()) {
                    return Err("后台作业停止身份URI不匹配".into());
                }
                let accepted = self.jobs.cancel_key(&key);
                self.emit(Event::CancelRequested { key, accepted });
            }
            Some(ACTION_REFRESH) | None => {}
            _ => return Err("未知后台作业Service动作".into()),
        }
        if keys.is_empty() {
            self.stop(epoch, false);
        }
        self.changed.notify_one();
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
            let keys = state
                .desired
                .running
                .iter()
                .map(|row| row.key.clone())
                .collect::<Vec<_>>();
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
            self.fail(format!("后台作业通知更新失败：{error}"));
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
            if !force && state.desired.alive && !state.desired.running.is_empty() {
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
                    self.jobs.set_protection_state(Protection::Unavailable);
                }
                state.stop_pending = outcome.stop_requested;
                let cards = state.notified.clone();
                state.cleanup.extend(cards);
                state.cleanup_at = Instant::now();
            }
            drop(state);
            self.jobs.notify_protection();
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
        match fw::same_instance(&current, &request.instance) {
            Ok(true) => {}
            Ok(false) => return Ok(()),
            Err(error) => {
                revoke_unknown(self, "作业服务销毁身份未确认，保护已撤销");
                return Err(error);
            }
        }
        self.sync_snapshot();
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let unexpected = !state.stop_pending && !state.desired.running.is_empty();
        let keys = state
            .desired
            .running
            .iter()
            .map(|row| row.key.clone())
            .collect::<Vec<_>>();
        let cards = std::mem::take(&mut state.notified);
        state.cleanup.extend(cards);
        state.cleanup_at = Instant::now();
        state.service = None;
        state.status.foreground = false;
        state.start_pending = None;
        state.update_pending = false;
        state.stop_pending = false;
        state.rendered = None;
        if unexpected {
            state.blocked = true;
        }
        self.jobs
            .set_protection_state(Protection::Failed("作业前台服务已销毁".into()));
        drop(state);
        self.jobs.notify_protection();
        self.emit(Event::Stopped);
        if unexpected {
            self.emit(Event::ProtectionLost { keys });
            self.fail("后台作业前台保护已丢失，作业进程仍由后台作业线程管理".into());
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
                    revoke_unknown(self, "作业服务超时身份未确认，保护已撤销");
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
            state.blocked = true;
            state.status.foreground = false;
            self.jobs
                .set_protection_state(Protection::Failed("作业前台服务已被系统限时结束".into()));
            state
                .desired
                .running
                .iter()
                .map(|row| row.key.clone())
                .collect::<Vec<_>>()
        };
        self.jobs.notify_protection();
        for key in keys {
            let accepted = self.jobs.cancel_key(&key);
            self.emit(Event::CancelRequested { key, accepted });
        }
        self.fail(format!(
            "后台作业服务超时（类型{}），已请求终止所属会话",
            request.flags
        ));
        self.stop(epoch, true);
        self.changed.notify_one();
        Ok(())
    }
    fn cleanup(&self, cards: HashSet<String>) {
        self.sync_snapshot();
        let keys = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .desired
            .running
            .iter()
            .map(|row| row.key.clone())
            .collect::<Vec<_>>();
        let active = framework::prospective(&keys);
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
