use super::{
    Config, Control, Distro, Event, Key, Phase, Protection, Record, ServiceRow, Snapshot, Status,
    archive, process, shared_control,
};
use crate::{
    platform::permissions::OperationKey,
    shell_environment::{Backend, ShellEnvironment},
};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, VecDeque},
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{
        Arc, Condvar, Mutex, Weak,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::Duration,
};
const MAX_EVENTS: usize = 256;
const MAX_RECORDS: usize = 128;
struct State {
    records: BTreeMap<String, Record>,
    live: Option<(String, Arc<Control>)>,
    events: VecDeque<Event>,
    observers: BTreeMap<u64, Arc<dyn Fn() + Send + Sync>>,
    revision: u64,
    alive: bool,
    protection: Protection,
    protection_key: Option<Key>,
}
pub struct Manager {
    environment: ShellEnvironment,
    directory: PathBuf,
    operations: PathBuf,
    state: Mutex<State>,
    io: Mutex<()>,
    process_token: String,
    require_foreground: bool,
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
fn token(key: &OperationKey) -> String {
    let mut hash = Sha256::new();
    hash.update(key.id.as_bytes());
    hash.update([0]);
    hash.update(key.generation.to_be_bytes());
    if let Some(chat) = &key.conversation_id {
        hash.update(chat.as_bytes());
    }
    format!("o{:x}", hash.finalize())
}
impl Manager {
    pub fn open(config: Config) -> Result<Arc<Self>, String> {
        let directory = owned_directory(&config.environment.paths().linux_directory)?;
        let operations = owned_directory(&config.operations_directory)?;
        let mut records = BTreeMap::new();
        for entry in fs::read_dir(&operations).map_err(|_| "Linux操作目录不可读")? {
            let entry = entry.map_err(|_| "Linux操作目录项不可读")?;
            let name = entry.file_name();
            let Some(name) = name.to_str() else {
                continue;
            };
            let Some(id) = name.strip_suffix(".linux.json") else {
                continue;
            };
            validate_token(id)?;
            if records.len() >= MAX_RECORDS {
                return Err("Linux操作记录超过保留预算".into());
            }
            let mut bytes = Vec::new();
            read_regular(&entry.path())?
                .take(64 * 1024 + 1)
                .read_to_end(&mut bytes)
                .map_err(|_| "Linux操作记录读取失败")?;
            if bytes.len() > 64 * 1024 {
                return Err("Linux操作记录超过64 KiB".into());
            }
            let mut record: Record =
                serde_json::from_slice(&bytes).map_err(|_| "Linux操作记录损坏，原文件已保留")?;
            record.operation.key().validate()?;
            if token(&record.operation.key()) != id {
                return Err("Linux操作记录身份不匹配".into());
            }
            if record.phase.active() {
                record.phase = Phase::Interrupted;
                record.error = Some("进程已结束；旧下载、解压与执行未自动重放".into());
                record.revision = record.revision.saturating_add(1);
                save(&operations, id, &record)?;
            }
            records.insert(id.into(), record);
        }
        let manager = Arc::new(Self {
            environment: config.environment,
            directory,
            operations,
            state: Mutex::new(State {
                records,
                live: None,
                events: VecDeque::new(),
                observers: BTreeMap::new(),
                revision: 0,
                alive: true,
                protection: Protection::Unavailable,
                protection_key: None,
            }),
            io: Mutex::new(()),
            process_token: format!(
                "l-{:x}-{:x}",
                std::process::id(),
                std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map_err(|_| "系统时间无效")?
                    .as_nanos()
            ),
            require_foreground: config.require_foreground,
            changed: Condvar::new(),
            next_observer: AtomicU64::new(1),
        });
        for distro in [Distro::Alpine, Distro::Debian] {
            manager.recover(distro);
        }
        #[cfg(target_os = "android")]
        if config.require_foreground {
            crate::platform::linux_service::start(manager.clone())?;
        }
        Ok(manager)
    }
    pub fn supported(&self) -> bool {
        cfg!(all(target_os = "android", target_arch = "aarch64"))
            && self.environment.available().contains(&Backend::Busybox)
            && [
                "libproot.so",
                "libproot-loader.so",
                "libproot-loader32.so",
                "libtalloc.so",
                "libandroid-shmem.so",
            ]
            .iter()
            .all(|name| asset(&self.environment, name).is_ok())
    }
    pub fn snapshot(&self) -> Snapshot {
        let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let running = state
            .live
            .as_ref()
            .and_then(|(id, _)| state.records.get(id))
            .map(|record| {
                vec![ServiceRow {
                    key: Key {
                        process_token: self.process_token.clone(),
                        id: record.operation.id.clone(),
                        generation: record.operation.generation,
                    },
                    phase: record.phase,
                }]
            })
            .unwrap_or_default();
        Snapshot {
            revision: state.revision,
            process_token: self.process_token.clone(),
            running,
            alive: state.alive,
        }
    }
    pub fn subscribe(
        self: &Arc<Self>,
        wake: impl Fn() + Send + Sync + 'static,
    ) -> Result<Subscription, String> {
        let id = self
            .next_observer
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |id| id.checked_add(1))
            .map_err(|_| "Linux订阅序号耗尽")?;
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.observers.len() >= 8 {
            return Err("Linux服务订阅达到预算".into());
        }
        state.observers.insert(id, Arc::new(wake));
        Ok(Subscription {
            manager: Arc::downgrade(self),
            id,
            seen: None,
            alive: AtomicBool::new(true),
        })
    }
    fn wake(&self) {
        self.changed.notify_all();
        let observers: Vec<_> = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .observers
            .values()
            .cloned()
            .collect();
        for observer in observers {
            let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| observer()));
        }
    }
    pub(crate) fn set_protection_state(&self, protection: Protection) -> bool {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let key = state
            .live
            .as_ref()
            .and_then(|(id, _)| state.records.get(id))
            .map(|record| Key {
                process_token: self.process_token.clone(),
                id: record.operation.id.clone(),
                generation: record.operation.generation,
            });
        let changed = state.protection != protection || state.protection_key != key;
        state.protection = protection;
        state.protection_key = key;
        if changed {
            state.revision = state.revision.wrapping_add(1);
        }
        changed
    }
    pub(crate) fn notify_protection(&self) {
        self.wake();
    }
    pub fn protection(&self) -> Protection {
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .protection
            .clone()
    }
    pub fn cancel_key(&self, key: &Key) -> bool {
        if key.validate().is_err() || key.process_token != self.process_token {
            return false;
        }
        let control = {
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            state.live.as_ref().and_then(|(id, control)| {
                state
                    .records
                    .get(id)
                    .filter(|record| {
                        record.operation.id == key.id
                            && record.operation.generation == key.generation
                    })
                    .map(|_| control.clone())
            })
        };
        if let Some(control) = control {
            control.cancelled.store(true, Ordering::Release);
            self.wake();
            true
        } else {
            false
        }
    }
    fn await_protection(&self, id: &str, control: &Control) -> Result<(), String> {
        if !self.require_foreground {
            return control.check();
        }
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let record = state.records.get(id).ok_or("Linux保护操作身份缺失")?;
        let key = Key {
            process_token: self.process_token.clone(),
            id: record.operation.id.clone(),
            generation: record.operation.generation,
        };
        loop {
            control.check()?;
            if !state.alive {
                return Err("Linux运行宿主已结束".into());
            }
            if state.protection_key.as_ref() == Some(&key) {
                match &state.protection {
                    Protection::Confirmed => return Ok(()),
                    Protection::Failed(error) => {
                        return Err(format!("Linux前台保护未确认：{error}"));
                    }
                    _ => (),
                }
            }
            if std::time::Instant::now() >= deadline {
                return Err("Linux前台保护未在期限内确认，文件操作未启动".into());
            }
            state = self
                .changed
                .wait_timeout(state, Duration::from_millis(100))
                .unwrap_or_else(|e| e.into_inner())
                .0;
        }
    }
    pub fn shutdown(&self) {
        let control = {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            state.alive = false;
            state.revision = state.revision.wrapping_add(1);
            state.live.as_ref().map(|(_, control)| control.clone())
        };
        if let Some(control) = control {
            control.cancelled.store(true, Ordering::Release);
        }
        self.wake();
    }
    pub fn status(&self) -> Vec<Status> {
        let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        [Distro::Alpine, Distro::Debian]
            .into_iter()
            .map(|distro| {
                let available = self.environment.resolve(distro.backend(), "exit 0").is_ok();
                let record = state
                    .records
                    .values()
                    .filter(|r| r.distro == distro)
                    .max_by_key(|r| (r.created_at, r.revision));
                Status {
                    distro,
                    available,
                    busy: state.live.as_ref().is_some_and(|(id, _)| {
                        state.records.get(id).is_some_and(|r| r.distro == distro)
                    }),
                    note: record.and_then(|r| r.error.clone()),
                }
            })
            .collect()
    }
    pub fn drain_events(&self, limit: usize) -> Vec<Event> {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let count = limit.min(MAX_EVENTS).min(state.events.len());
        state.events.drain(..count).collect()
    }
    fn emit(&self, event: Event) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.events.len() == MAX_EVENTS {
            state.events.pop_front();
        }
        state.events.push_back(event);
    }
    pub fn record(&self, key: &OperationKey) -> Option<Record> {
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .records
            .get(&token(key))
            .cloned()
    }
    pub fn records(&self) -> Vec<Record> {
        let mut records: Vec<_> = self
            .state
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .records
            .values()
            .cloned()
            .collect();
        records.sort_by(|a, b| {
            b.created_at
                .cmp(&a.created_at)
                .then_with(|| a.operation.id.cmp(&b.operation.id))
                .then_with(|| a.operation.generation.cmp(&b.operation.generation))
        });
        records
    }
    pub fn cancel(&self, key: &OperationKey) -> bool {
        let control = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .live
            .as_ref()
            .filter(|(id, _)| *id == token(key))
            .map(|(_, c)| c.clone());
        if let Some(c) = control {
            c.cancelled.store(true, Ordering::Release);
            true
        } else {
            false
        }
    }
    pub fn install(
        self: &Arc<Self>,
        operation: OperationKey,
        distro: Distro,
    ) -> Result<Record, String> {
        self.start(operation, distro, false)
    }
    pub fn remove(
        self: &Arc<Self>,
        operation: OperationKey,
        distro: Distro,
    ) -> Result<Record, String> {
        self.start(operation, distro, true)
    }
    fn start(
        self: &Arc<Self>,
        operation: OperationKey,
        distro: Distro,
        remove: bool,
    ) -> Result<Record, String> {
        operation.validate()?;
        let id = token(&operation);
        let _io = self.io.lock().unwrap_or_else(|e| e.into_inner());
        {
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(record) = state.records.get(&id) {
                return Ok(record.clone());
            }
            if state.live.is_some() {
                return Err("请先完成或取消当前Linux操作".into());
            }
            if !state.alive {
                return Err("Linux宿主已结束，操作未启动".into());
            }
            if state.records.len() >= MAX_RECORDS {
                return Err("Linux操作记录达到保留预算，操作未执行".into());
            }
        }
        if !remove && !self.supported() {
            return Err("当前ABI或随包PRoot/BusyBox依赖不完整，Linux安装不可用".into());
        }
        let available = self.environment.resolve(distro.backend(), "exit 0").is_ok();
        let record = Record {
            operation: operation.into(),
            distro,
            phase: Phase::Claimed,
            bytes: 0,
            available,
            error: None,
            published: false,
            cancel_requested: false,
            revision: 1,
            created_at: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_err(|_| "系统时间无效")?
                .as_millis()
                .min(i64::MAX as u128) as i64,
        };
        save(&self.operations, &id, &record)?;
        let control = shared_control();
        {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            state.records.insert(id.clone(), record.clone());
            state.live = Some((id.clone(), control.clone()));
            state.revision = state.revision.wrapping_add(1);
        }
        drop(_io);
        #[cfg(target_os = "android")]
        if self.require_foreground {
            crate::platform::linux_service::request_refresh();
        }
        self.wake();
        let worker = self.clone();
        let worker_id = id.clone();
        if std::thread::Builder::new()
            .name("rust-linux-install".into())
            .spawn(move || {
                let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    worker.await_protection(&worker_id, &control)?;
                    if remove {
                        worker.run_remove(&worker_id, distro, &control)
                    } else {
                        worker.run_install(&worker_id, distro, &control)
                    }
                }));
                match result {
                    Ok(Ok(())) => (),
                    Ok(Err(error)) => worker.finish(
                        &worker_id,
                        if control.cancelled.load(Ordering::Acquire) {
                            Phase::Cancelled
                        } else {
                            Phase::Failed
                        },
                        Some(error),
                    ),
                    Err(_) => worker.finish(
                        &worker_id,
                        Phase::Failed,
                        Some("Linux操作线程异常结束，未自动重放".into()),
                    ),
                }
                {
                    let mut state = worker.state.lock().unwrap_or_else(|e| e.into_inner());
                    state.live = None;
                    state.revision = state.revision.wrapping_add(1);
                }
                worker.wake();
            })
            .is_err()
        {
            self.state.lock().unwrap_or_else(|e| e.into_inner()).live = None;
            self.finish(
                &id,
                Phase::Failed,
                Some("Linux操作线程未创建，操作未启动".into()),
            );
            return Err("无法创建Linux操作线程".into());
        }
        Ok(record)
    }
    fn update(&self, id: &str, phase: Phase, bytes: Option<u64>) -> Result<(), String> {
        let _io = self.io.lock().unwrap_or_else(|e| e.into_inner());
        let mut record = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .records
            .get(id)
            .cloned()
            .ok_or("Linux操作记录缺失")?;
        record.phase = phase;
        if let Some(bytes) = bytes {
            record.bytes = bytes;
        }
        record.revision = record.revision.saturating_add(1);
        let result = save(&self.operations, id, &record);
        if let Err(error) = &result {
            record.error = Some(format!("Linux操作元数据提交未确认：{error}"));
        }
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .records
            .insert(id.into(), record.clone());
        drop(_io);
        self.emit(Event::Progress(record));
        {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            state.revision = state.revision.wrapping_add(1);
        }
        self.wake();
        result
    }
    fn finish(&self, id: &str, phase: Phase, error: Option<String>) {
        let _io = self.io.lock().unwrap_or_else(|e| e.into_inner());
        let mut record = match self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .records
            .get(id)
            .cloned()
        {
            Some(record) => record,
            None => return,
        };
        record.phase = phase;
        record.cancel_requested |= self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .live
            .as_ref()
            .filter(|(active, _)| active == id)
            .is_some_and(|(_, control)| control.cancelled.load(Ordering::Acquire));
        record.error = error;
        record.available = self
            .environment
            .resolve(record.distro.backend(), "exit 0")
            .is_ok();
        record.revision = record.revision.saturating_add(1);
        if let Err(error) = save(&self.operations, id, &record) {
            record.error = Some(format!(
                "{}元数据提交未确认：{error}",
                record
                    .error
                    .as_deref()
                    .map_or(String::new(), |old| format!("{old}；"))
            ));
        }
        self.state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .records
            .insert(id.into(), record.clone());
        drop(_io);
        self.emit(Event::Finished(record));
        {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            state.revision = state.revision.wrapping_add(1);
        }
        self.wake();
    }
    fn recover(&self, distro: Distro) {
        let writer = match crate::shell_environment::lease::write(self.directory.join(distro.id()))
        {
            Ok(writer) => writer,
            Err(error) => {
                self.emit(Event::Recovery {
                    distro,
                    restored: false,
                    error: Some(error),
                });
                return;
            }
        };
        let target = self.directory.join(distro.id());
        match fs::symlink_metadata(&target) {
            Ok(_) => return,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => (),
            Err(error) => {
                self.emit(Event::Recovery {
                    distro,
                    restored: false,
                    error: Some(format!("Linux目标状态无法核对，未覆盖恢复：{error}")),
                });
                return;
            }
        }
        // Once deletion starts, interrupted cleanup must not resurrect an old
        // rootfs. The marker is cleared only after a later explicit install is
        // actually published and synchronized; old backups remain available.
        match fs::symlink_metadata(removal_marker(&self.directory, distro)) {
            Ok(_) => {
                self.emit(Event::Recovery {
                    distro,
                    restored: false,
                    error: Some(
                        "Linux移除意图已提交；旧备份保留，但未自动恢复已请求移除的环境".into(),
                    ),
                });
                return;
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => (),
            Err(error) => {
                self.emit(Event::Recovery {
                    distro,
                    restored: false,
                    error: Some(format!("Linux移除意图无法核对，未自动恢复：{error}")),
                });
                return;
            }
        }
        let mut backups = vec![self.directory.join(format!(".{}-old", distro.id()))];
        let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        for (id, record) in &state.records {
            if record.distro == distro && !matches!(record.phase, Phase::Removing | Phase::Removed)
            {
                backups.push(self.directory.join(format!(".{}-backup-{id}", distro.id())));
            }
        }
        drop(state);
        backups.retain(|path| {
            fs::symlink_metadata(path)
                .ok()
                .is_some_and(|m| m.is_dir() && !m.file_type().is_symlink())
        });
        if backups.len() > 1 {
            self.emit(Event::Recovery {
                distro,
                restored: false,
                error: Some("存在多个Linux恢复候选，原内容已保留".into()),
            });
            return;
        }
        let Some(backup) = backups.pop() else {
            return;
        };
        if fs::symlink_metadata(&backup)
            .ok()
            .is_some_and(|m| m.is_dir() && !m.file_type().is_symlink())
        {
            let result = rename_new(&backup, &target)
                .map_err(|error| format!("旧Linux备份恢复失败，已有目标未覆盖：{error}"))
                .and_then(|()| sync_dir(&self.directory));
            self.emit(Event::Recovery {
                distro,
                restored: result.is_ok(),
                error: result.err(),
            });
        }
        drop(writer);
    }
    fn run_install(&self, id: &str, distro: Distro, control: &Arc<Control>) -> Result<(), String> {
        control.check()?;
        let stage = self
            .directory
            .join(format!(".{}-install-{id}", distro.id()));
        if fs::symlink_metadata(&stage).is_ok() {
            return Err("此安装暂存目录已存在，旧内容已保留".into());
        }
        fs::create_dir(&stage).map_err(|_| "Linux安装暂存目录创建失败")?;
        let mut completion_error = None;
        let result = (|| {
            let compressed = stage.join("rootfs.archive");
            self.download(id, distro, &compressed, control)?;
            control.check()?;
            let candidate = stage.join(distro.id());
            fs::create_dir(&candidate).map_err(|_| "Linux候选目录创建失败")?;
            let root = candidate.join("rootfs");
            self.update(id, Phase::Extracting, None)?;
            let busybox = asset(&self.environment, "libbusybox.so")?;
            let source = read_regular(&compressed)?;
            let mut decoder = process::Decoder::open(
                &busybox,
                if distro.spec().xz { "unxz" } else { "gunzip" },
                source,
                control.clone(),
            )?;
            archive::extract(
                &root,
                distro.spec().prefix,
                &mut decoder,
                &control.cancelled,
                |_| {},
            )?;
            decoder.finish()?;
            configure(&root)?;
            write_new(&candidate.join("ready"), distro.spec().sha256.as_bytes())?;
            self.update(id, Phase::Checking, None)?;
            control.check()?;
            let mut paths = self.environment.paths().clone();
            paths.linux_directory = stage.clone();
            let candidate_environment = ShellEnvironment::new(paths)?;
            let command = format!(
                "printf 'suixing-linux-ok\\n'; uname -m; {}",
                if distro == Distro::Alpine {
                    "apk --version"
                } else {
                    "apt-get --version"
                }
            );
            process::probe(
                candidate_environment.resolve(distro.backend(), &command)?,
                control,
            )?;
            control.check()?;
            self.update(id, Phase::Publishing, None)?;
            let writer = crate::shell_environment::lease::write(self.directory.join(distro.id()))?;
            // Publication is a short, non-replayed transaction. Cancellation is
            // honored before the first rename; afterwards recovery owns the result.
            let target = self.directory.join(distro.id());
            let backup = self.directory.join(format!(".{}-backup-{id}", distro.id()));
            match fs::symlink_metadata(&backup) {
                Ok(_) => return Err("旧Linux备份尚未处理，原环境与新候选均已保留".into()),
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => (),
                Err(_) => return Err("旧Linux备份状态不可读，未覆盖".into()),
            }
            match fs::symlink_metadata(&target) {
                Ok(metadata) => {
                    if !metadata.is_dir() || metadata.file_type().is_symlink() {
                        return Err("现有Linux目标不是普通目录".into());
                    }
                    rename_new(&target, &backup)
                        .map_err(|_| "旧Linux环境备份失败，已有备份未覆盖")?;
                    sync_dir(&self.directory)?;
                }
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => (),
                Err(_) => return Err("现有Linux目标状态不可读，未发布覆盖".into()),
            }
            if rename_new(&candidate, &target).is_err() {
                if fs::symlink_metadata(&backup).is_ok() {
                    let _ = rename_new(&backup, &target);
                }
                return Err("Linux候选发布失败，旧环境已保留".into());
            }
            {
                let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                if let Some(record) = state.records.get_mut(id) {
                    record.published = true;
                }
            }
            if let Err(error) = sync_dir(&self.directory) {
                drop(writer);
                completion_error = Some(format!("Linux环境已发布，但目录同步未确认：{error}"));
                return Ok(());
            }
            if let Err(error) = clear_removal_marker(&self.directory, distro) {
                completion_error = Some(format!("Linux环境已发布，但移除意图清理未确认：{error}"));
            }
            drop(writer);
            // Retain the previous rootfs as explicit backup instead of deleting
            // possibly valuable guest files while clients may still be using it.
            Ok(())
        })();
        if result.is_ok() {
            if let Err(error) = remove_owned_tree(&self.directory, &stage) {
                completion_error = Some(format!(
                    "{}安装暂存清理未确认：{error}",
                    completion_error
                        .as_deref()
                        .map_or(String::new(), |old| format!("{old}；"))
                ));
            }
            self.finish(
                id,
                if completion_error.is_some() {
                    Phase::Failed
                } else {
                    Phase::Ready
                },
                completion_error,
            );
        }
        result
    }
    fn download(
        &self,
        id: &str,
        distro: Distro,
        path: &Path,
        control: &Arc<Control>,
    ) -> Result<(), String> {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|_| "Linux下载运行时创建失败")?;
        runtime.block_on(async{
            let client=reqwest::Client::builder().https_only(true).connect_timeout(Duration::from_secs(30)).timeout(Duration::from_secs(15*60))
                .redirect(reqwest::redirect::Policy::limited(5)).build().map_err(|_|"Linux下载客户端创建失败")?;
            self.update(id,Phase::Downloading,Some(0))?;
            let download=async{
                let mut response=client.get(distro.spec().url).send().await.map_err(|_|"Linux固定来源下载失败")?;
                if !response.status().is_success()||response.url().scheme()!="https"{return Err("Linux来源HTTP响应无效".to_string());}
                if response.content_length().is_some_and(|bytes|bytes!=distro.spec().download_bytes){return Err("Linux归档响应长度不匹配".into());}
                let mut file=open_new(path)?;let mut hash=Sha256::new();let mut count=0u64;let mut progress=0u64;
                while let Some(bytes)=response.chunk().await.map_err(|_|"Linux归档传输失败")?{
                    control.check()?;count=count.checked_add(bytes.len()as u64).filter(|count|*count<=distro.spec().download_bytes).ok_or("Linux归档超过固定长度")?;
                    file.write_all(&bytes).map_err(|_|"Linux归档写入失败")?;hash.update(&bytes);
                    if count-progress>=1024*1024{self.update(id,Phase::Downloading,Some(count))?;progress=count;}
                }
                file.sync_all().map_err(|_|"Linux归档同步失败")?;self.update(id,Phase::Verifying,Some(count))?;
                if count!=distro.spec().download_bytes||format!("{:x}",hash.finalize())!=distro.spec().sha256{return Err("Linux归档SHA256或长度不匹配，未解压执行".into());}Ok(())
            };
            tokio::pin!(download);
            loop{tokio::select!{result=&mut download=>return result,_=tokio::time::sleep(Duration::from_millis(50))=>control.check()?}}
        })
    }
    fn run_remove(&self, id: &str, distro: Distro, control: &Arc<Control>) -> Result<(), String> {
        control.check()?;
        let writer = crate::shell_environment::lease::write(self.directory.join(distro.id()))?;
        self.update(id, Phase::Removing, None)?;
        control.check()?;
        mark_removal(&self.directory, distro)?;
        let target = self.directory.join(distro.id());
        let quarantine = self.directory.join(format!(".{}-remove-{id}", distro.id()));
        if fs::symlink_metadata(&quarantine).is_ok() {
            return Err("Linux移除暂存已存在，未重复移除".into());
        }
        if fs::symlink_metadata(&target).is_ok() {
            rename_new(&target, &quarantine).map_err(|_| "Linux移除原子隔离失败")?;
            sync_dir(&self.directory)?;
            // After the first quarantine rename the filesystem transaction
            // owns completion; cancellation remains a request until cleanup ends.
            remove_owned_tree(&self.directory, &quarantine)?;
        }
        let legacy = self.directory.join(format!(".{}-old", distro.id()));
        if fs::symlink_metadata(&legacy).is_ok() {
            let quarantine = self
                .directory
                .join(format!(".{}-remove-{id}-old", distro.id()));
            rename_new(&legacy, &quarantine).map_err(|_| "旧Linux备份移除隔离失败")?;
            sync_dir(&self.directory)?;
            remove_owned_tree(&self.directory, &quarantine)?;
        }
        let backup_ids: Vec<_> = self
            .state
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .records
            .iter()
            .filter(|(_, record)| record.distro == distro)
            .map(|(id, _)| id.clone())
            .collect();
        for backup_id in backup_ids {
            remove_owned_tree(
                &self.directory,
                &self
                    .directory
                    .join(format!(".{}-backup-{backup_id}", distro.id())),
            )?;
        }
        drop(writer);
        self.finish(id, Phase::Removed, None);
        Ok(())
    }
}
fn removal_marker(directory: &Path, distro: Distro) -> PathBuf {
    directory.join(format!(".{}-remove-intent", distro.id()))
}
fn mark_removal(directory: &Path, distro: Distro) -> Result<(), String> {
    let path = removal_marker(directory, distro);
    match fs::symlink_metadata(&path) {
        Ok(metadata)
            if metadata.is_file()
                && !metadata.file_type().is_symlink()
                && metadata.len() <= 128 =>
        {
            read_regular(&path)?
                .sync_all()
                .map_err(|_| "Linux已有移除意图同步失败")?;
        }
        Ok(_) => return Err("Linux移除意图不是有界普通文件，原内容已保留".into()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            write_new(&path, b"no_auto_recovery\n")?;
        }
        Err(_) => return Err("Linux移除意图状态不可读".into()),
    }
    sync_dir(directory)
}
fn clear_removal_marker(directory: &Path, distro: Distro) -> Result<(), String> {
    let path = removal_marker(directory, distro);
    match fs::symlink_metadata(&path) {
        Ok(metadata) if metadata.is_file() && !metadata.file_type().is_symlink() => {
            fs::remove_file(&path).map_err(|_| "Linux移除意图删除失败")?;
            sync_dir(directory)
        }
        Ok(_) => Err("Linux移除意图类型已变化，原内容已保留".into()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(_) => Err("Linux移除意图状态不可读".into()),
    }
}
fn rename_new(source: &Path, target: &Path) -> Result<(), String> {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        use std::{ffi::CString, os::unix::ffi::OsStrExt};
        let source =
            CString::new(source.as_os_str().as_bytes()).map_err(|_| "Linux源路径包含NUL")?;
        let target =
            CString::new(target.as_os_str().as_bytes()).map_err(|_| "Linux目标路径包含NUL")?;
        // Linux renameat2 RENAME_NOREPLACE is the commit boundary. If an older
        // kernel lacks it, preserve both paths and report the missing guarantee.
        if unsafe {
            libc::syscall(
                libc::SYS_renameat2,
                libc::AT_FDCWD,
                source.as_ptr(),
                libc::AT_FDCWD,
                target.as_ptr(),
                1u32,
            )
        } < 0
        {
            return Err(format!(
                "Linux不覆盖重命名未完成：{}",
                std::io::Error::last_os_error()
            ));
        }
        Ok(())
    }
    #[cfg(not(any(target_os = "android", target_os = "linux")))]
    {
        let _ = (source, target);
        Err("当前平台没有Linux不覆盖重命名保证".into())
    }
}
fn owned_directory(path: &Path) -> Result<PathBuf, String> {
    if !path.is_absolute()
        || fs::symlink_metadata(path)
            .ok()
            .is_some_and(|m| m.file_type().is_symlink())
    {
        return Err("Linux目录不是绝对普通路径".into());
    }
    fs::create_dir_all(path).map_err(|_| "Linux目录创建失败")?;
    fs::canonicalize(path).map_err(|_| "Linux目录核对失败".into())
}
fn validate_token(id: &str) -> Result<(), String> {
    if id.len() != 65 || !id.starts_with('o') || !id[1..].bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("Linux操作文件身份无效".into());
    }
    Ok(())
}
fn open_new(path: &Path) -> Result<File, String> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK);
    }
    options
        .open(path)
        .map_err(|_| "Linux普通文件创建失败".into())
}
fn read_regular(path: &Path) -> Result<File, String> {
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK);
    }
    let file = options.open(path).map_err(|_| "Linux文件不可读")?;
    if !file
        .metadata()
        .map_err(|_| "Linux文件信息不可读")?
        .is_file()
    {
        return Err("Linux文件不是普通文件".into());
    }
    Ok(file)
}
fn write_new(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let mut file = open_new(path)?;
    file.write_all(bytes)
        .and_then(|()| file.sync_all())
        .map_err(|_| "Linux文件写入或同步失败".into())
}
fn save(root: &Path, id: &str, record: &Record) -> Result<(), String> {
    validate_token(id)?;
    let bytes = serde_json::to_vec(record).map_err(|_| "Linux操作记录编码失败")?;
    if bytes.len() > 64 * 1024 {
        return Err("Linux记录超过64 KiB".into());
    }
    static NEXT: AtomicU64 = AtomicU64::new(1);
    let temporary = root.join(format!(
        ".linux-{}-{}.tmp",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    let result = (|| {
        write_new(&temporary, &bytes)?;
        fs::rename(&temporary, root.join(format!("{id}.linux.json")))
            .map_err(|_| "Linux记录原子替换失败")?;
        sync_dir(root)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}
fn sync_dir(path: &Path) -> Result<(), String> {
    #[cfg(unix)]
    File::open(path)
        .and_then(|file| file.sync_all())
        .map_err(|_| "Linux目录同步未确认")?;
    #[cfg(not(unix))]
    let _ = path;
    Ok(())
}
fn asset(environment: &ShellEnvironment, name: &str) -> Result<PathBuf, String> {
    let owner = fs::canonicalize(&environment.paths().native_library_dir)
        .map_err(|_| "随包程序目录不可用")?;
    let path = fs::canonicalize(owner.join(name)).map_err(|_| "随包PRoot依赖不完整")?;
    if !path.starts_with(&owner) || !fs::metadata(&path).ok().is_some_and(|m| m.is_file()) {
        return Err("随包PRoot程序越界或不是普通文件".into());
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if fs::metadata(&path)
            .map_err(|_| "程序权限不可读")?
            .permissions()
            .mode()
            & 0o111
            == 0
        {
            return Err("随包程序不可执行".into());
        }
    }
    Ok(path)
}
fn configure(root: &Path) -> Result<(), String> {
    for name in ["workspace", "tmp", "root"] {
        let path = root.join(name);
        if fs::symlink_metadata(&path).is_err() {
            fs::create_dir(&path).map_err(|_| "Linux工作目录配置失败")?;
        }
        let actual = fs::canonicalize(&path).map_err(|_| "Linux工作目录不可读")?;
        if !actual.starts_with(root) || !actual.is_dir() {
            return Err("Linux工作目录越界或不是目录".into());
        }
    }
    let etc = fs::canonicalize(root.join("etc")).map_err(|_| "Linux配置目录不可读")?;
    if !etc.starts_with(root) || !etc.is_dir() {
        return Err("Linux配置目录越界".into());
    }
    let resolver = etc.join("resolv.conf");
    if fs::symlink_metadata(&resolver).is_ok() {
        fs::remove_file(&resolver).map_err(|_| "LinuxDNS文件替换失败")?;
    }
    write_new(&resolver, b"nameserver 1.1.1.1\nnameserver 8.8.8.8\n")?;
    let apt = etc.join("apt");
    fn rewrite(
        directory: &Path,
        depth: usize,
        count: &mut usize,
        budget: &mut u64,
    ) -> Result<(), String> {
        if depth > 8 {
            return Err("Linux apt配置目录超过深度预算".into());
        }
        let metadata = match fs::symlink_metadata(directory) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
            Err(_) => return Err("Linux apt配置不可读".into()),
        };
        if metadata.file_type().is_symlink() {
            return Err("Linux apt配置含未核对链接".into());
        }
        if metadata.is_dir() {
            for entry in fs::read_dir(directory).map_err(|_| "Linux apt配置目录不可读")? {
                *count += 1;
                if *count > 128 {
                    return Err("Linux apt配置超过128个条目".into());
                }
                rewrite(
                    &entry.map_err(|_| "Linux apt配置项不可读")?.path(),
                    depth + 1,
                    count,
                    budget,
                )?;
            }
        } else if metadata.is_file()
            && matches!(
                directory.extension().and_then(|ext| ext.to_str()),
                Some("list" | "sources")
            )
        {
            if metadata.len() > *budget {
                return Err("Linux apt配置超过512 KiB预算".into());
            }
            *budget -= metadata.len();
            let mut bytes = Vec::new();
            read_regular(directory)?
                .take(metadata.len() + 1)
                .read_to_end(&mut bytes)
                .map_err(|_| "Linux apt配置读取失败")?;
            if bytes.len() as u64 > metadata.len() {
                return Err("Linux apt配置在读取期间变化".into());
            }
            let content = String::from_utf8(bytes).map_err(|_| "Linux apt配置不是UTF-8")?;
            let updated = content
                .replace("http://deb.debian.org", "https://deb.debian.org")
                .replace("http://security.debian.org", "https://security.debian.org");
            if updated != content {
                fs::remove_file(directory).map_err(|_| "Linux apt候选配置替换失败")?;
                write_new(directory, updated.as_bytes())?;
            }
        }
        Ok(())
    }
    rewrite(&apt, 0, &mut 0, &mut (512 * 1024))
}
fn remove_owned_tree(owner: &Path, path: &Path) -> Result<(), String> {
    if path.parent() != Some(owner) || path == owner {
        return Err("Linux移除路径越界".into());
    }
    fn remove(path: &Path, depth: usize, entries: &mut usize) -> Result<(), String> {
        if depth > 64 {
            return Err("Linux移除目录超过深度预算，剩余内容已保留".into());
        }
        *entries += 1;
        if *entries > 150_000 {
            return Err("Linux移除条目超过预算，剩余内容已保留".into());
        }
        let metadata = match fs::symlink_metadata(path) {
            Ok(m) => m,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
            Err(_) => return Err("Linux移除路径不可读".into()),
        };
        if metadata.is_dir() && !metadata.file_type().is_symlink() {
            for entry in fs::read_dir(path).map_err(|_| "Linux移除目录不可读")? {
                remove(
                    &entry.map_err(|_| "Linux移除条目不可读")?.path(),
                    depth + 1,
                    entries,
                )?;
            }
            fs::remove_dir(path).map_err(|_| "Linux目录移除失败".into())
        } else {
            fs::remove_file(path).map_err(|_| "Linux文件移除失败".into())
        }
    }
    remove(path, 0, &mut 0)
}
