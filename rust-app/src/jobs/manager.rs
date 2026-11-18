use super::{
    Caller, Delivery, Info, JobKey, ObservedChanges, Output, Phase, Prepared, Protection, Record,
    ServiceRow, Snapshot, StartSpec, Status, observation,
    process::{self, Control, Final},
    storage,
};
use crate::shell_environment::ShellEnvironment;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, HashMap},
    fs,
    path::{Path, PathBuf},
    sync::{
        Arc, Condvar, Mutex, Weak,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

pub struct Config {
    pub directory: PathBuf,
    pub environment: ShellEnvironment,
    pub require_foreground: bool,
}
impl Config {
    pub fn new(directory: PathBuf, environment: ShellEnvironment) -> Self {
        Self {
            directory,
            environment,
            require_foreground: cfg!(target_os = "android"),
        }
    }
}
type Wake = Arc<dyn Fn() + Send + Sync + 'static>;
struct State {
    records: BTreeMap<String, Record>,
    persisted_bytes: BTreeMap<String, u64>,
    live: HashMap<JobKey, Arc<Control>>,
    observers: HashMap<u64, Wake>,
    revision: u64,
    alive: bool,
    protection: Protection,
}
pub struct Manager {
    directory: PathBuf,
    environment: ShellEnvironment,
    require_foreground: bool,
    process_token: String,
    state: Mutex<State>,
    io: Mutex<()>,
    changed: Condvar,
    next_observer: AtomicU64,
}
pub struct Subscription {
    manager: Weak<Manager>,
    id: u64,
    seen: Option<u64>,
    alive: AtomicBool,
}
impl Subscription {
    pub fn poll(&mut self) -> Option<Snapshot> {
        if !self.alive.load(Ordering::Acquire) {
            return None;
        }
        let manager = self.manager.upgrade()?;
        let snapshot = manager.snapshot();
        if self.seen == Some(snapshot.revision) {
            return None;
        }
        self.seen = Some(snapshot.revision);
        Some(snapshot)
    }
}
impl Drop for Subscription {
    fn drop(&mut self) {
        self.alive.store(false, Ordering::Release);
        if let Some(manager) = self.manager.upgrade() {
            manager
                .state
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .observers
                .remove(&self.id);
        }
    }
}
fn now() -> Result<i64, String> {
    i64::try_from(
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|_| "系统时间早于Unix纪元")?
            .as_millis(),
    )
    .map_err(|_| "系统时间超出范围".into())
}
fn append_error(target: &mut Option<String>, error: &str) {
    if target
        .as_ref()
        .is_some_and(|previous| previous.contains(error))
    {
        return;
    }
    let mut combined = target.take().unwrap_or_default();
    if !combined.is_empty() {
        combined.push('；');
    }
    combined.push_str(error);
    if combined.len() > 8192 {
        let suffix = "；其它错误信息已截断";
        let mut end = 8192 - suffix.len();
        while !combined.is_char_boundary(end) {
            end -= 1;
        }
        combined.truncate(end);
        combined.push_str(suffix);
    }
    *target = Some(combined);
}
impl Manager {
    pub fn open(config: Config) -> Result<Arc<Self>, String> {
        if !config.directory.is_absolute() {
            return Err("作业目录必须为绝对平台路径".into());
        }
        if fs::symlink_metadata(&config.directory)
            .ok()
            .is_some_and(|m| m.file_type().is_symlink())
        {
            return Err("作业目录不能是符号链接".into());
        }
        fs::create_dir_all(&config.directory).map_err(|_| "无法创建作业目录")?;
        let directory = fs::canonicalize(&config.directory).map_err(|_| "无法核对作业目录")?;
        config.environment.prepare_directories()?;
        let loaded = storage::load(&directory)?;
        let mut persisted_bytes = BTreeMap::new();
        for record in &loaded {
            let size = fs::metadata(storage::record_path(&directory, &record.key.id)?)
                .map_err(|_| "作业元数据大小未确认")?
                .len();
            persisted_bytes.insert(record.key.id.clone(), size);
        }
        let mut records = BTreeMap::new();
        for mut record in loaded {
            if record.phase.active() {
                record.phase = Phase::Interrupted;
                record.pid = None;
                record.ended_at = Some(now()?);
                record.error = Some("应用进程已结束；旧PID未恢复，命令未自动重放".into());
                let others: u64 = persisted_bytes
                    .iter()
                    .filter(|(id, _)| **id != record.key.id)
                    .map(|(_, size)| *size)
                    .sum();
                let bytes = storage::save(
                    &directory,
                    &record,
                    storage::MAX_METADATA_BYTES.saturating_sub(others),
                )
                .map_err(|error| error.error)?;
                persisted_bytes.insert(record.key.id.clone(), bytes as u64);
            }
            record.pid = None;
            records.insert(record.key.id.clone(), record);
        }
        let process_token = format!(
            "p-{:x}-{:x}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_err(|_| "系统时间无效")?
                .as_nanos()
        );
        let manager = Arc::new(Self {
            directory,
            environment: config.environment,
            require_foreground: config.require_foreground,
            process_token,
            state: Mutex::new(State {
                records,
                persisted_bytes,
                live: HashMap::new(),
                observers: HashMap::new(),
                revision: 0,
                alive: true,
                protection: Protection::Unavailable,
            }),
            io: Mutex::new(()),
            changed: Condvar::new(),
            next_observer: AtomicU64::new(1),
        });
        #[cfg(target_os = "android")]
        if config.require_foreground {
            crate::platform::job_service::start(manager.clone())?;
        }
        Ok(manager)
    }
    pub(crate) fn directory(&self) -> &Path {
        &self.directory
    }
    pub fn process_token(&self) -> &str {
        &self.process_token
    }
    pub fn subscribe(
        self: &Arc<Self>,
        wake: impl Fn() + Send + Sync + 'static,
    ) -> Result<Subscription, String> {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.observers.len() >= 8 {
            return Err("作业状态订阅超过8个预算".into());
        }
        let id = self
            .next_observer
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |id| id.checked_add(1))
            .map_err(|_| "作业订阅身份耗尽")?;
        state.observers.insert(id, Arc::new(wake));
        Ok(Subscription {
            manager: Arc::downgrade(self),
            id,
            seen: None,
            alive: AtomicBool::new(true),
        })
    }
    pub fn snapshot(&self) -> Snapshot {
        let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let mut running: Vec<_> = state
            .live
            .keys()
            .filter_map(|key| {
                state.records.get(&key.id).map(|record| ServiceRow {
                    key: key.clone(),
                    phase: record.phase,
                })
            })
            .collect();
        running.sort_by(|a, b| a.key.id.cmp(&b.key.id));
        Snapshot {
            revision: state.revision,
            process_token: self.process_token.clone(),
            running,
            alive: state.alive,
        }
    }
    fn wake(&self) {
        let wakes = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            state.revision = state.revision.saturating_add(1);
            state.observers.values().cloned().collect::<Vec<_>>()
        };
        self.changed.notify_all();
        for wake in wakes {
            let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| wake()));
        }
    }
    pub fn protection(&self) -> Protection {
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .protection
            .clone()
    }
    pub fn set_protection(&self, protection: Protection) {
        if self.set_protection_state(protection) {
            self.wake();
        }
    }
    /// Service state may change atomically under its own lock; callbacks are
    /// deliberately deferred until that lock is released.
    pub(crate) fn set_protection_state(&self, protection: Protection) -> bool {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.protection == protection {
            return false;
        }
        state.protection = protection;
        true
    }
    pub(crate) fn notify_protection(&self) {
        self.wake();
    }
    pub(crate) fn await_protection(&self, control: &Control) -> Result<(), String> {
        if !self.require_foreground {
            return Ok(());
        }
        let deadline = Instant::now() + Duration::from_secs(5);
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        loop {
            if control.cancelled() || !state.alive {
                return Err("作业已请求停止，未启动命令".into());
            }
            match &state.protection {
                Protection::Confirmed => return Ok(()),
                Protection::Failed(error) => return Err(format!("作业前台保护未确认：{error}")),
                _ => {}
            }
            if Instant::now() >= deadline {
                return Err("作业前台保护未在期限内确认，未启动命令".into());
            }
            state = self
                .changed
                .wait_timeout(state, Duration::from_millis(100))
                .unwrap_or_else(|e| e.into_inner())
                .0;
        }
    }
    fn put_locked(&self, record: Record, observed: bool) -> Result<(), String> {
        let remaining = {
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            let other_bytes: u64 = state
                .persisted_bytes
                .iter()
                .filter(|(id, _)| **id != record.key.id)
                .map(|(_, size)| *size)
                .sum();
            storage::MAX_METADATA_BYTES.saturating_sub(other_bytes)
        };
        let result = storage::save(&self.directory, &record, remaining);
        match result {
            Ok(bytes) => {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                state
                    .persisted_bytes
                    .insert(record.key.id.clone(), bytes as u64);
                state.records.insert(record.key.id.clone(), record);
                Ok(())
            }
            Err(failure) => {
                if failure.committed || observed {
                    let mut record = record;
                    append_error(
                        &mut record.error,
                        &format!("作业元数据提交未完全确认：{}", failure.error),
                    );
                    let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                    if let Some(bytes) = failure.bytes {
                        state
                            .persisted_bytes
                            .insert(record.key.id.clone(), bytes as u64);
                    }
                    state.records.insert(record.key.id.clone(), record);
                }
                Err(failure.error)
            }
        }
    }
    fn update(&self, key: &JobKey, edit: impl FnOnce(&mut Record)) -> Result<(), String> {
        let _io = self.io.lock().unwrap_or_else(|e| e.into_inner());
        let mut record = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .records
            .get(&key.id)
            .filter(|record| record.key == *key)
            .cloned()
            .ok_or("作业记录不存在或身份变化")?;
        edit(&mut record);
        let result = self.put_locked(record, true);
        drop(_io);
        self.wake();
        result
    }
    pub fn start(self: &Arc<Self>, spec: &StartSpec, caller: &Caller) -> Result<Record, String> {
        caller.validate()?;
        if spec.command.trim().is_empty()
            || spec.command.len() > 64 * 1024
            || spec.command.contains('\0')
            || spec.label.len() > 1024
            || spec.observe_paths.len() > 32
            || !(1000..=86_400_000).contains(&spec.deadline_ms)
        {
            return Err("作业创建参数无效".into());
        }
        let mut digest = Sha256::new();
        digest.update(caller.request_key.as_bytes());
        digest.update([0]);
        digest.update(spec.prepared_id.as_bytes());
        digest.update([0]);
        digest.update(spec.command.as_bytes());
        digest.update([0]);
        digest.update(spec.label.as_bytes());
        digest.update(spec.deadline_ms.to_be_bytes());
        digest.update(serde_json::to_vec(&spec.backend).map_err(|_| "Shell选择编码失败")?);
        for path in &spec.observe_paths {
            if !path.is_absolute() || path.as_os_str().to_string_lossy().contains('\0') {
                return Err("作业观察路径必须为绝对路径".into());
            }
            digest.update([0]);
            digest.update(path.as_os_str().to_string_lossy().as_bytes());
        }
        let id = format!("j{:x}", digest.finalize());
        let _io = self.io.lock().unwrap_or_else(|e| e.into_inner());
        {
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(record) = state.records.get(&id) {
                return Ok(record.clone());
            }
            if !state.alive {
                return Err("作业宿主已结束".into());
            }
            if state.live.len() >= super::MAX_ACTIVE {
                return Err("同时运行的后台作业超过8个预算".into());
            }
            if state.records.len() >= super::MAX_RECORDS {
                return Err(
                    "作业记录达到128条保留预算；新增命令未启动，旧记录与消费收据已保留".into(),
                );
            }
        }
        let plan = self.environment.resolve(spec.backend, &spec.command)?;
        let key = JobKey {
            process_token: self.process_token.clone(),
            id: id.clone(),
        };
        let record = Record {
            key: key.clone(),
            info: Info {
                id: id.clone(),
                label: if spec.label.trim().is_empty() {
                    spec.command.chars().take(60).collect()
                } else {
                    spec.label.trim().into()
                },
                command: spec.command.clone(),
                started_at: now()?,
                log_path: storage::log_path(&self.directory, &id)?
                    .to_string_lossy()
                    .into_owned(),
            },
            phase: Phase::Claimed,
            started: false,
            pid: None,
            ended_at: None,
            elapsed_ms: 0,
            exit_code: None,
            signal: None,
            error: None,
            deadline_ms: spec.deadline_ms,
            backend: plan.backend,
            log_bytes: 0,
            output_truncated: false,
            owner_chat_id: caller.chat_id.clone(),
            file_changes: ObservedChanges::default(),
            changes_consumed: 0,
            deliveries: Vec::new(),
        };
        self.put_locked(record.clone(), false)?;
        let control = Control::new();
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .live
            .insert(key.clone(), control.clone());
        drop(_io);
        #[cfg(target_os = "android")]
        if self.require_foreground {
            crate::platform::job_service::request_refresh();
        }
        self.wake();
        let mut roots = vec![self.environment.paths().workspace.clone()];
        roots.extend(spec.observe_paths.clone());
        if let Err(error) = process::spawn(
            self.clone(),
            key.clone(),
            plan,
            roots,
            spec.deadline_ms,
            control,
        ) {
            self.finish(
                &key,
                Final {
                    phase: Phase::Failed,
                    started: false,
                    elapsed_ms: 0,
                    exit_code: None,
                    signal: None,
                    error: Some(error.clone()),
                    log_bytes: 0,
                    truncated: false,
                    changes: ObservedChanges::default(),
                },
            );
            return Err(error);
        }
        Ok(record)
    }
    pub fn record(&self, id: &str) -> Option<Record> {
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .records
            .get(id)
            .cloned()
    }
    pub fn records(&self) -> Vec<Record> {
        let mut records: Vec<_> = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .records
            .values()
            .cloned()
            .collect();
        records.sort_by_key(|record| std::cmp::Reverse(record.info.started_at));
        records
    }
    pub fn list(&self) -> Vec<Status> {
        self.records()
            .into_iter()
            .map(|record| self.status_of(record))
            .collect()
    }
    fn status_of(&self, record: Record) -> Status {
        let elapsed_ms = if record.phase.active() {
            now()
                .ok()
                .and_then(|now| u64::try_from(now.saturating_sub(record.info.started_at)).ok())
                .unwrap_or(record.elapsed_ms)
        } else {
            record.elapsed_ms
        };
        let (last_line, log_error) = if !record.started && record.log_bytes == 0 {
            (String::new(), None)
        } else {
            match storage::last_line(&self.directory, &record.key.id) {
                Ok(line) => (line, None),
                Err(error) => (String::new(), Some(error)),
            }
        };
        Status {
            info: record.info,
            key: record.key.clone(),
            status: record.phase,
            elapsed_ms,
            exit_code: record.exit_code,
            signal: record.signal,
            last_line,
            started: record.started,
            output_truncated: record.output_truncated,
            error: record.error,
            log_error,
        }
    }
    pub fn output(&self, id: &str, tail: usize, since: Option<u64>) -> Result<Output, String> {
        let record = self.record(id).ok_or("没有这个作业记录")?;
        if !record.started && !storage::log_path(&self.directory, id)?.exists() {
            if since.is_some_and(|offset| offset > 0) {
                return Err("作业尚无日志，游标必须为0".into());
            }
            return Ok(Output {
                text: String::new(),
                from: 0,
                next_offset: 0,
                log_length: 0,
                has_more: false,
                window_truncated: false,
                invalid_utf8: false,
            });
        }
        storage::output(&self.directory, id, tail, since)
    }
    pub fn cancel_key(&self, key: &JobKey) -> bool {
        if key.validate().is_err() || key.process_token != self.process_token {
            return false;
        }
        let control = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .live
            .get(key)
            .cloned();
        let Some(control) = control else {
            return false;
        };
        control.cancel();
        self.wake();
        true
    }
    pub fn shutdown(&self) {
        let controls = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            state.alive = false;
            state.live.values().cloned().collect::<Vec<_>>()
        };
        for control in controls {
            control.cancel();
        }
        self.wake();
    }
    pub(crate) fn mark_running(&self, key: &JobKey, pid: u32) -> Result<(), String> {
        self.update(key, |record| {
            record.phase = Phase::Running;
            record.started = true;
            record.pid = Some(pid);
            record.info.started_at = now().unwrap_or(record.info.started_at);
        })
    }
    pub(crate) fn mark_cancelling(&self, key: &JobKey) {
        let _ = self.update(key, |record| {
            if record.phase.active() {
                record.phase = Phase::Cancelling;
            }
        });
    }
    pub(crate) fn progress(&self, key: &JobKey, bytes: u64, truncated: bool) {
        let _ = self.update(key, |record| {
            record.log_bytes = bytes;
            record.output_truncated |= truncated;
            record.elapsed_ms = now()
                .ok()
                .and_then(|now| u64::try_from(now.saturating_sub(record.info.started_at)).ok())
                .unwrap_or(record.elapsed_ms);
        });
    }
    pub(crate) fn finish(&self, key: &JobKey, result: Final) {
        let _ = self.update(key, |record| {
            record.phase = result.phase;
            record.started |= result.started;
            record.pid = None;
            record.ended_at = now().ok();
            record.elapsed_ms = result.elapsed_ms;
            record.exit_code = result.exit_code;
            record.signal = result.signal;
            if let Some(error) = result.error {
                append_error(&mut record.error, &error);
            }
            record.log_bytes = result.log_bytes;
            record.output_truncated |= result.truncated;
            record.file_changes = result.changes;
        });
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .live
            .remove(key);
        self.wake();
    }
    pub fn take_changes(&self, ids: &[String], caller: &Caller) -> Result<ObservedChanges, String> {
        caller.validate()?;
        let _io = self.io.lock().unwrap_or_else(|e| e.into_inner());
        let mut result = ObservedChanges::default();
        let mut remaining = 64 * 1024usize;
        let mut seen = std::collections::HashSet::new();
        for id in ids.iter().take(super::MAX_RECORDS) {
            if !seen.insert(id) {
                continue;
            }
            let Some(mut record) = self.record(id) else {
                continue;
            };
            if record.phase.active() || record.owner_chat_id != caller.chat_id {
                continue;
            }
            let delivery = if let Some(existing) = record
                .deliveries
                .iter()
                .find(|delivery| delivery.request_key == caller.request_key)
            {
                for activity in
                    &record.file_changes.activities[existing.from..existing.from + existing.count]
                {
                    let bytes = serde_json::to_vec(activity)
                        .map_err(|_| "文件变化编码失败")?
                        .len();
                    remaining = remaining
                        .checked_sub(bytes)
                        .ok_or("重试文件变化结果超过64 KiB预算；消费收据已保留")?;
                }
                existing.clone()
            } else {
                let from = record.changes_consumed;
                let mut count = 0;
                for activity in record.file_changes.activities.iter().skip(from) {
                    let bytes = serde_json::to_vec(activity)
                        .map_err(|_| "文件变化编码失败")?
                        .len();
                    if bytes > remaining {
                        break;
                    }
                    remaining -= bytes;
                    count += 1;
                    if count >= 128 {
                        break;
                    }
                }
                if count == 0 {
                    if record.file_changes.activities.is_empty()
                        && !record.file_changes.notice.is_empty()
                    {
                        result.notice = record.file_changes.notice.clone();
                    }
                    continue;
                }
                if record.deliveries.len() >= 128 {
                    return Err("作业文件变化消费收据达到128条预算".into());
                }
                let delivery = Delivery {
                    request_key: caller.request_key.clone(),
                    from,
                    count,
                };
                record.changes_consumed += count;
                record.deliveries.push(delivery.clone());
                self.put_locked(record.clone(), false)?;
                delivery
            };
            result.activities.extend_from_slice(
                &record.file_changes.activities[delivery.from..delivery.from + delivery.count],
            );
            if !record.file_changes.notice.is_empty()
                && !result.notice.contains(&record.file_changes.notice)
            {
                if !result.notice.is_empty() {
                    result.notice.push('；');
                }
                result.notice.push_str(&record.file_changes.notice);
            }
        }
        Ok(result)
    }
    pub fn execute(self: &Arc<Self>, prepared: &Prepared, caller: &Caller) -> Value {
        let outcome = (|| -> Result<Value, String> {
            caller.validate()?;
            match prepared {
                Prepared::Start(spec) => {
                    let record = self.start(spec, caller)?;
                    Ok(
                        json!({"ok":true,"job_id":record.key.id,"key":record.key,"status":record.phase,"started":record.started,"claimed":true,"label":record.info.label,"background_confirmed":self.protection()==Protection::Confirmed}),
                    )
                }
                Prepared::List => {
                    let all = self.list();
                    let ids: Vec<_> = all.iter().take(20).map(|row| row.key.id.clone()).collect();
                    let changes = self.take_changes(&ids, caller)?;
                    let rows:Vec<_>=all.iter().take(20).map(|row|json!({"id":row.key.id,"key":row.key,"label":row.info.label,"status":row.status,"elapsed_ms":row.elapsed_ms,"exit_code":row.exit_code,"last_line":row.last_line,"started":row.started,"output_truncated":row.output_truncated,"error":row.error,"log_error":row.log_error})).collect();
                    Ok(
                        json!({"ok":true,"jobs":rows,"total":all.len(),"list_truncated":all.len()>20,"file_activities":changes.activities,"notice":changes.notice}),
                    )
                }
                Prepared::Output { id, tail, since } => {
                    let output = self.output(id, *tail, *since)?;
                    let changes = self.take_changes(std::slice::from_ref(id), caller)?;
                    let record = self.record(id).ok_or("作业已被移除")?;
                    Ok(
                        json!({"ok":true,"job_id":id,"status":record.phase,"exit_code":record.exit_code,"error":record.error,"output":output,"output_truncated":record.output_truncated,"file_activities":changes.activities,"notice":changes.notice}),
                    )
                }
                Prepared::Kill { id } => {
                    let record = self.record(id).ok_or("没有这个作业记录")?;
                    let accepted = self.cancel_key(&record.key);
                    Ok(
                        json!({"ok":true,"job_id":id,"cancel_requested":accepted,"already_finished":!record.phase.active(),"status":record.phase,"stopped_confirmed":false}),
                    )
                }
            }
        })();
        outcome.unwrap_or_else(|error| json!({"ok":false,"error":error}))
    }
}
