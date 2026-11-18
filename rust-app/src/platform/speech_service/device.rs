use super::{
    ACTION_START, ACTION_STOP, Lease, SERVICE_CLASS, SpeechServiceEvent as Event,
    SpeechServiceStatus, framework,
};
use crate::platform::{
    android::{self, ServiceRequest},
    permissions::OperationKey,
    service_framework as fw, voice,
};
use std::{
    collections::{BTreeMap, VecDeque},
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{
        Arc, Condvar, Mutex, OnceLock,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const MAX_LEASES: usize = 8;
const MAX_EVENTS: usize = 256;
const CONFIRM_LIMIT: Duration = Duration::from_secs(4);
struct Entry {
    operation: OperationKey,
    guard: Arc<AtomicBool>,
    requested: Instant,
}
struct State {
    status: SpeechServiceStatus,
    entries: BTreeMap<u64, Entry>,
    events: VecDeque<Event>,
    service: Option<fw::Object>,
    epoch: u64,
    next: u64,
    pending: Option<u64>,
    submitted: bool,
    revision: u64,
    rendered: Option<u64>,
    updating: bool,
    stopping: bool,
}
struct Manager {
    process: String,
    state: Mutex<State>,
    changed: Condvar,
}
static INSTANCE: OnceLock<Arc<Manager>> = OnceLock::new();
static INSTALL: Mutex<()> = Mutex::new(());
fn manager() -> Result<&'static Arc<Manager>, String> {
    let _install = INSTALL.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(manager) = INSTANCE.get() {
        if manager.status().initialized {
            return Ok(manager);
        }
        return Err("语音前台服务管理线程已结束".into());
    }
    if android::paths().ok_or("Android平台尚未初始化")?.sdk_int < 26 {
        return Err("语音前台服务需要Android API26或以上".into());
    }
    android::application_context()?;
    let process = format!(
        "s-{:x}-{:x}",
        std::process::id(),
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|_| "系统时间无效")?
            .as_nanos()
    );
    let manager = Arc::new(Manager {
        process,
        changed: Condvar::new(),
        state: Mutex::new(State {
            status: SpeechServiceStatus {
                initialized: true,
                ..Default::default()
            },
            entries: BTreeMap::new(),
            events: VecDeque::new(),
            service: None,
            epoch: 0,
            next: 1,
            pending: None,
            submitted: false,
            revision: 0,
            rendered: None,
            updating: false,
            stopping: false,
        }),
    });
    INSTANCE
        .set(manager.clone())
        .map_err(|_| "语音服务重复初始化")?;
    let worker = manager.clone();
    if thread::Builder::new()
        .name("rust-speech-foreground".into())
        .spawn(move || {
            if catch_unwind(AssertUnwindSafe(|| worker.run())).is_err() {
                worker.revoke("语音前台服务管理线程异常结束");
                worker
                    .state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .status
                    .initialized = false;
                let _ = android::post_to_main(move || {
                    let _ = fw::stop_component(SERVICE_CLASS, ACTION_START);
                });
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
        return Err("无法创建语音前台服务管理线程".into());
    }
    INSTANCE.get().ok_or("语音服务初始化失败".into())
}
pub fn request(operation: OperationKey, opted_in: bool) -> Result<Lease, String> {
    operation.validate()?;
    if !opted_in {
        return Err("用户尚未启用语音朗读".into());
    }
    let manager = manager()?;
    let mut state = manager.state.lock().unwrap_or_else(|e| e.into_inner());
    if !state.status.initialized || state.stopping {
        return Err("语音服务正在结束，请稍后重试".into());
    }
    let inherited = state.status.foreground
        && state
            .entries
            .values()
            .any(|entry| entry.guard.load(Ordering::Acquire));
    if !android::activity_visible() && !inherited {
        return Err("当前没有可启动朗读的可见界面或已确认音频保护".into());
    }
    if state.entries.len() >= MAX_LEASES || state.events.len() + MAX_LEASES * 2 >= MAX_EVENTS {
        return Err("语音服务操作或待取结果超过预算".into());
    }
    if state
        .entries
        .values()
        .any(|entry| entry.operation == operation)
    {
        return Err("此语音操作已拥有服务租约".into());
    }
    let token = state.next;
    state.next = state.next.checked_add(1).ok_or("语音服务操作序号已耗尽")?;
    let guard = Arc::new(AtomicBool::new(state.status.foreground));
    state.entries.insert(
        token,
        Entry {
            operation: operation.clone(),
            guard: guard.clone(),
            requested: Instant::now(),
        },
    );
    state.revision = state.revision.wrapping_add(1);
    if state.status.foreground {
        state.events.push_back(Event::Ready {
            operation: operation.clone(),
            guard: guard.clone(),
        });
    }
    drop(state);
    manager.changed.notify_one();
    Ok(Lease {
        operation,
        guard,
        token,
    })
}
pub(super) fn release(token: u64) {
    if let Some(manager) = INSTANCE.get() {
        let mut state = manager.state.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(entry) = state.entries.remove(&token) {
            entry.guard.store(false, Ordering::Release);
            state.revision = state.revision.wrapping_add(1);
            Manager::push(
                &mut state,
                Event::Released {
                    operation: entry.operation,
                },
            );
        }
        drop(state);
        manager.changed.notify_one();
    }
}
pub fn stop_all() {
    if let Some(manager) = INSTANCE.get() {
        manager.revoke("用户已停止语音朗读");
    }
}
pub fn status() -> SpeechServiceStatus {
    INSTANCE.get().map(|m| m.status()).unwrap_or_default()
}
pub fn drain_events(limit: usize) -> Vec<Event> {
    let Some(manager) = INSTANCE.get() else {
        return Vec::new();
    };
    let mut state = manager.state.lock().unwrap_or_else(|e| e.into_inner());
    let count = limit.min(MAX_EVENTS).min(state.events.len());
    state.events.drain(..count).collect()
}
pub fn handle_service(request: ServiceRequest) -> Result<i32, String> {
    if request.component != SERVICE_CLASS {
        return Err("Service组件未接入语音策略".into());
    }
    let manager = INSTANCE.get().ok_or("语音服务没有所属操作".to_string())?;
    let result = match request.kind {
        1 => manager.bind(&request).map(|_| ()),
        2 => manager.started(&request),
        3 => manager.destroyed(&request),
        4 | 5 => manager.timed_out(&request),
        _ => Err("未知语音Service生命周期".into()),
    };
    if result.is_err() && matches!(request.kind, 3 | 4 | 5) {
        manager.revoke("语音服务结束身份无法核对，音频保护已撤销");
    }
    result?;
    Ok(2)
}
impl Manager {
    fn push(state: &mut State, event: Event) {
        if state.events.len() == MAX_EVENTS {
            state.events.pop_front();
        }
        state.events.push_back(event);
    }
    fn status(&self) -> SpeechServiceStatus {
        let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let mut status = state.status.clone();
        status.active_leases = state.entries.len();
        status
    }
    fn revoke_locked(state: &mut State, error: &str) {
        let entries = std::mem::take(&mut state.entries);
        for (_, entry) in entries {
            entry.guard.store(false, Ordering::Release);
            voice::cancel_operation(&entry.operation);
            Self::push(
                state,
                Event::Failed {
                    operation: Some(entry.operation),
                    error: error.into(),
                },
            );
        }
        state.pending = None;
        state.revision = state.revision.wrapping_add(1);
        state.status.last_error = Some(error.into());
    }
    fn revoke(&self, error: &str) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        Self::revoke_locked(&mut state, error);
        drop(state);
        // Each actual lease guard and exact OperationKey were revoked above.
        self.changed.notify_one();
    }
    fn run(self: &Arc<Self>) {
        loop {
            self.reconcile();
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            let _ = self
                .changed
                .wait_timeout(state, Duration::from_millis(100))
                .unwrap_or_else(|e| e.into_inner());
        }
    }
    fn reconcile(self: &Arc<Self>) {
        let (start, update, stop, stop_component) = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            let expired: Vec<_> = state
                .entries
                .iter()
                .filter(|(_, entry)| {
                    !entry.guard.load(Ordering::Acquire)
                        && entry.requested.elapsed() >= CONFIRM_LIMIT
                })
                .map(|(token, _)| *token)
                .collect();
            for token in expired {
                if let Some(entry) = state.entries.remove(&token) {
                    entry.guard.store(false, Ordering::Release);
                    Self::push(
                        &mut state,
                        Event::Failed {
                            operation: Some(entry.operation),
                            error: "语音前台提升未在期限内确认，未允许后台朗读".into(),
                        },
                    );
                    state.revision = state.revision.wrapping_add(1);
                }
            }
            if state
                .pending
                .is_some_and(|token| !state.entries.contains_key(&token))
            {
                state.pending = None;
            }
            let start = if !state.entries.is_empty()
                && !state.status.foreground
                && state.pending.is_none()
                && !state.stopping
            {
                let token = *state.entries.keys().next().expect("nonempty entries");
                state.pending = Some(token);
                Some(token)
            } else {
                None
            };
            let update = if state.status.foreground
                && !state.entries.is_empty()
                && !state.updating
                && !state.stopping
                && state.rendered != Some(state.revision)
            {
                state.updating = true;
                Some(state.epoch)
            } else {
                None
            };
            let stop = if state.entries.is_empty() && state.service.is_some() && !state.stopping {
                state.stopping = true;
                Some(state.epoch)
            } else {
                None
            };
            let stop_component = state.entries.is_empty()
                && state.service.is_none()
                && state.submitted
                && !state.stopping;
            if stop_component {
                state.stopping = true;
            }
            (start, update, stop, stop_component)
        };
        if let Some(token) = start {
            let manager = self.clone();
            if let Err(error) = android::post_to_main(move || manager.request_start(token)) {
                self.revoke(&error);
            }
        }
        if let Some(epoch) = update {
            let manager = self.clone();
            if let Err(error) = android::post_to_main(move || manager.update(epoch)) {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                state.updating = false;
                state.status.last_error = Some(error.clone());
                Self::push(
                    &mut state,
                    Event::Failed {
                        operation: None,
                        error,
                    },
                );
            }
        }
        if let Some(epoch) = stop {
            let manager = self.clone();
            if let Err(error) = android::post_to_main(move || manager.stop(epoch)) {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                state.stopping = false;
                state.status.last_error = Some(error.clone());
                Self::push(
                    &mut state,
                    Event::Failed {
                        operation: None,
                        error,
                    },
                );
            }
        }
        if stop_component {
            let manager = self.clone();
            if let Err(error) = android::post_to_main(move || manager.stop_unbound()) {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                state.stopping = false;
                state.status.last_error = Some(error);
            }
        }
    }
    fn request_start(&self, token: u64) {
        {
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if state.pending != Some(token)
                || !state.entries.contains_key(&token)
                || state.status.foreground
                || state.stopping
            {
                return;
            }
        }
        if !android::activity_visible() {
            self.revoke("界面已离开前台，语音服务未启动");
            return;
        }
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .submitted = true;
        if let Err(error) = framework::request_start(&self.process, token) {
            self.revoke(&error);
        }
    }
    fn stop_unbound(&self) {
        {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if !state.entries.is_empty() || state.service.is_some() {
                state.stopping = false;
                return;
            }
        }
        let result = fw::stop_component(SERVICE_CLASS, ACTION_START);
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.submitted = false;
        state.stopping = false;
        if let Err(error) = result {
            state.status.last_error = Some(error.clone());
            Self::push(
                &mut state,
                Event::Failed {
                    operation: None,
                    error,
                },
            );
        }
        drop(state);
        self.changed.notify_one();
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
        let lost: Vec<_> = state
            .entries
            .iter()
            .filter(|(_, entry)| entry.guard.load(Ordering::Acquire))
            .map(|(token, _)| *token)
            .collect();
        for token in lost {
            if let Some(entry) = state.entries.remove(&token) {
                entry.guard.store(false, Ordering::Release);
                Self::push(
                    &mut state,
                    Event::Failed {
                        operation: Some(entry.operation),
                        error: "语音服务实例已变化，旧音频保护已撤销".into(),
                    },
                );
            }
        }
        state.epoch = state.epoch.checked_add(1).ok_or("语音Service代次已耗尽")?;
        state.service = Some(service);
        state.status.foreground = false;
        state.rendered = None;
        state.updating = false;
        // A fresh instance remains in stopping state if all leases were revoked.
        let epoch = state.epoch;
        drop(state);
        self.changed.notify_one();
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
                return Err("旧语音Service实例的启动已忽略".into());
            }
        }
        let epoch = self.bind(request)?;
        if request.intent.action.as_deref() == Some(ACTION_STOP) {
            let valid = request.intent.process_token.as_deref() == Some(&self.process)
                && request.intent.data_uri.as_deref()
                    == Some(framework::stop_uri(&self.process, epoch).as_str());
            if valid {
                self.revoke("用户已停止语音朗读");
                self.state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .stopping = true;
                self.stop(epoch);
            } else if !self.status().foreground {
                return fw::stop_instance(&request.instance);
            }
            return Ok(());
        }
        let (token, count, confirmed) = {
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            let token = state
                .pending
                .or_else(|| state.entries.keys().next().copied());
            let valid = token.is_some_and(|token| {
                request.intent.action.as_deref() == Some(ACTION_START)
                    && request.intent.process_token.as_deref() == Some(&self.process)
                    && request.intent.data_uri.as_deref()
                        == Some(framework::start_uri(&self.process, token).as_str())
                    && state.entries.contains_key(&token)
                    && !state.stopping
            });
            if !valid {
                // A stale start must not tear down an already confirmed instance.
                return if state.status.foreground {
                    Ok(())
                } else {
                    Err("语音服务启动没有匹配的有效操作".into())
                };
            }
            (
                token.expect("validated token"),
                state.entries.len(),
                state.status.foreground,
            )
        };
        if !confirmed && !android::activity_visible() {
            self.revoke("界面在语音前台提升前已隐藏，音频保护未授予");
            return Err("语音服务前台资格已失效".into());
        }
        let allowed = if confirmed {
            self.status().notifications_allowed.unwrap_or(true)
        } else {
            match framework::promote(&request.instance, &self.process, epoch, count) {
                Ok(allowed) => allowed,
                Err(error) => {
                    self.revoke(&error);
                    return Err(error);
                }
            }
        };
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.epoch != epoch || !state.entries.contains_key(&token) || state.stopping {
            drop(state);
            self.changed.notify_one();
            return Ok(());
        }
        state.status.foreground = true;
        state.submitted = false;
        state.status.notifications_allowed = Some(allowed);
        state.status.last_error = None;
        state.pending = None;
        let ready: Vec<_> = state
            .entries
            .values()
            .filter_map(|entry| {
                (!entry.guard.swap(true, Ordering::AcqRel)).then(|| Event::Ready {
                    operation: entry.operation.clone(),
                    guard: entry.guard.clone(),
                })
            })
            .collect();
        for event in ready {
            Self::push(&mut state, event);
        }
        if !allowed {
            Self::push(&mut state, Event::NotificationRestricted);
        }
        drop(state);
        self.changed.notify_one();
        Ok(())
    }
    fn update(&self, epoch: u64) {
        let (count, revision) = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if state.epoch != epoch {
                return;
            }
            if !state.status.foreground || state.entries.is_empty() || state.stopping {
                state.updating = false;
                return;
            }
            (state.entries.len(), state.revision)
        };
        let result = framework::update(&self.process, epoch, count);
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.epoch != epoch {
            return;
        }
        state.updating = false;
        state.rendered = Some(revision);
        match result {
            Ok(allowed) => state.status.notifications_allowed = Some(allowed),
            Err(error) => {
                // Notification failure alone does not revoke confirmed audio FGS.
                state.status.last_error = Some(error.clone());
                Self::push(
                    &mut state,
                    Event::Failed {
                        operation: None,
                        error,
                    },
                );
            }
        }
    }
    fn stop(&self, epoch: u64) {
        let service = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if state.epoch != epoch {
                return;
            }
            if !state.entries.is_empty() {
                state.stopping = false;
                return;
            }
            state.service.clone()
        };
        let Some(service) = service else {
            return;
        };
        let outcome = fw::stop(service.as_ref());
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.epoch != epoch {
            return;
        }
        if outcome.foreground_removed {
            state.status.foreground = false;
        }
        if !outcome.stop_requested {
            state.stopping = false;
        }
        if !outcome.errors.is_empty() {
            let error = outcome.errors.join("；");
            state.status.last_error = Some(error.clone());
            Self::push(
                &mut state,
                Event::Failed {
                    operation: None,
                    error,
                },
            );
        }
    }
    fn destroyed(&self, request: &ServiceRequest) -> Result<(), String> {
        let service = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .service
            .clone();
        let Some(service) = service else {
            return Ok(());
        };
        if !fw::same_instance(&service, &request.instance)? {
            return Ok(());
        }
        self.revoke("语音前台服务已销毁，音频保护已撤销");
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.service = None;
        state.submitted = false;
        state.status.foreground = false;
        state.stopping = false;
        state.updating = false;
        drop(state);
        self.changed.notify_one();
        Ok(())
    }
    fn timed_out(&self, request: &ServiceRequest) -> Result<(), String> {
        let service = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .service
            .clone();
        if let Some(service) = service {
            if !fw::same_instance(&service, &request.instance)? {
                return fw::stop_instance(&request.instance);
            }
        } else {
            return fw::stop_instance(&request.instance);
        }
        self.revoke("语音前台服务被系统限时结束，音频保护已撤销");
        let epoch = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            state.stopping = true;
            state.epoch
        };
        self.stop(epoch);
        Ok(())
    }
}
