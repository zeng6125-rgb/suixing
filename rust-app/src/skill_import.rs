//! User-originated skill imports. Documents and companion files are retained verbatim;
//! the catalogue is committed separately, in one actor-owned transaction, disabled.
use crate::plugin_assets::{self as files, Layout, Tree};
use crate::{network::CancelToken, platform::permissions::OperationKey, skills::Skill};
use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    fs::{self, File, OpenOptions},
    io::Read,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex, OnceLock,
        mpsc::{self, SyncSender},
    },
    time::{Duration, Instant},
};

pub(crate) mod device;
mod recovery;
mod workspace_scan;
pub(crate) use workspace_scan::{is_live_workspace_skill, verify_workspace_document};
const CAPACITY: usize = 4;
pub(crate) const DOCUMENT_BYTES: usize = 2 * 1024 * 1024;
const TOTAL_BYTES: u64 = 64 * 1024 * 1024;
const MAX_NODES: usize = 200;
const MAX_DEPTH: usize = 7;

#[derive(Clone, Debug)]
pub(crate) enum Command {
    ScanWorkspace,
    Recover,
    PickFile,
    PickTree,
    PreviewPath(PathBuf),
    PreviewUrl(String),
    ReadDocument {
        ticket: String,
        id: String,
        offset: usize,
    },
    Commit(String),
    Discard(String),
    Cancel,
}
#[derive(Clone, Debug)]
pub(crate) struct Request {
    pub operation: OperationKey,
    pub command: Command,
}
#[derive(Clone, Debug)]
pub(crate) struct Header {
    pub id: String,
    pub name: String,
    pub description: String,
    pub document_bytes: usize,
    pub resources: usize,
}
#[derive(Clone, Debug)]
pub(crate) struct Review {
    pub ticket: String,
    pub skills: Vec<Header>,
    pub files: usize,
    pub bytes: u64,
    pub warnings: Vec<String>,
}
#[derive(Clone, Debug)]
pub(crate) enum State {
    WorkspaceScanned {
        workspace: PathBuf,
        epoch: u64,
        entries: Vec<Skill>,
    },
    WorkspaceRefreshed(String),
    Recovered {
        reviews: Vec<Review>,
        warnings: Vec<String>,
    },
    Expired {
        ticket: String,
        notice: Option<String>,
    },
    Prepared(Review),
    Document {
        ticket: String,
        id: String,
        offset: usize,
        next: Option<usize>,
        text: String,
    },
    CommitReady {
        ticket: String,
        workspace: PathBuf,
        skills: Vec<Skill>,
    },
    Saved(Vec<String>),
    Discarded,
    Cancelled,
    Failed(String),
    WaitingForTerminal,
}
#[derive(Clone, Debug)]
pub(crate) struct Update {
    pub operation: OperationKey,
    pub state: State,
}
pub(crate) struct Control {
    pub cancel: Arc<CancelToken>,
    deadline: Instant,
    #[cfg(target_os = "android")]
    pub signal: Mutex<Option<device::Object>>,
}
impl Control {
    fn new() -> Self {
        Self {
            cancel: Arc::new(CancelToken::new()),
            deadline: Instant::now() + Duration::from_secs(120),
            #[cfg(target_os = "android")]
            signal: Mutex::new(None),
        }
    }
    pub(crate) fn check(&self) -> Result<(), String> {
        if Instant::now() >= self.deadline {
            self.cancel.cancel();
        }
        if self.cancel.is_cancelled() {
            Err("技能导入已取消或超过截止时间，未自动重试".into())
        } else {
            Ok(())
        }
    }
}
struct Pending {
    key: OperationKey,
    control: Arc<Control>,
    waiting_reported: bool,
}
struct Ticket {
    workspace: PathBuf,
    path: PathBuf,
    layout: Vec<Layout>,
    skills: Vec<Skill>,
    review: Review,
    expires: Instant,
    committing: bool,
    expiry_queued: bool,
}
#[derive(Default)]
struct Registry {
    pending: BTreeMap<String, Pending>,
    tickets: BTreeMap<String, Ticket>,
    results: VecDeque<Update>,
    protected: BTreeSet<String>,
    recovery_claims: BTreeSet<String>,
    metadata_epoch: u64,
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
static SOURCE_READERS: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
struct SourcePermit;
impl Drop for SourcePermit {
    fn drop(&mut self) {
        SOURCE_READERS.fetch_sub(1, std::sync::atomic::Ordering::AcqRel);
    }
}
fn source_permit(control: &Control) -> Result<SourcePermit, String> {
    loop {
        control.check()?;
        let count = SOURCE_READERS.load(std::sync::atomic::Ordering::Acquire);
        if count < 2
            && SOURCE_READERS
                .compare_exchange(
                    count,
                    count + 1,
                    std::sync::atomic::Ordering::AcqRel,
                    std::sync::atomic::Ordering::Acquire,
                )
                .is_ok()
        {
            return Ok(SourcePermit);
        }
        std::thread::park_timeout(Duration::from_millis(20));
    }
}

fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(Default::default)
}
enum Work {
    ScanWorkspace {
        key: OperationKey,
        workspace: PathBuf,
        epoch: u64,
        control: Arc<Control>,
    },
    Recover {
        key: OperationKey,
        workspace: PathBuf,
        referenced_paths: Vec<String>,
        epoch: u64,
        control: Arc<Control>,
    },
    Expire {
        key: OperationKey,
        ticket: String,
        control: Arc<Control>,
    },
    Preview {
        key: OperationKey,
        workspace: PathBuf,
        source: Source,
        control: Arc<Control>,
    },
    Read {
        key: OperationKey,
        ticket: String,
        id: String,
        offset: usize,
        control: Arc<Control>,
    },
    Commit {
        key: OperationKey,
        ticket: String,
        control: Arc<Control>,
    },
    Discard {
        key: OperationKey,
        ticket: String,
        control: Arc<Control>,
    },
}
pub(crate) enum Source {
    Path(PathBuf),
    Url(String),
    #[cfg(target_os = "android")]
    Uri {
        uri: device::Object,
        tree: bool,
    },
}
fn workers() -> Result<&'static SyncSender<Work>, String> {
    static WORKERS: OnceLock<Result<SyncSender<Work>, String>> = OnceLock::new();
    WORKERS
        .get_or_init(|| {
            let (tx, rx) = mpsc::sync_channel(CAPACITY);
            let rx = Arc::new(Mutex::new(rx));
            for index in 0..2 {
                let rx = rx.clone();
                std::thread::Builder::new()
                    .name(format!("rust-skill-import-{index}"))
                    .spawn(move || {
                        loop {
                            let work = rx.lock().unwrap_or_else(|e| e.into_inner()).recv();
                            let Ok(work) = work else { break };
                            let (key, control) = match &work {
                                Work::ScanWorkspace { key, control, .. }
                                | Work::Recover { key, control, .. }
                                | Work::Expire { key, control, .. }
                                | Work::Preview { key, control, .. }
                                | Work::Read { key, control, .. }
                                | Work::Commit { key, control, .. }
                                | Work::Discard { key, control, .. } => {
                                    (key.clone(), control.clone())
                                }
                            };
                            let result =
                                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                                    process(work)
                                }))
                                .unwrap_or_else(|_| {
                                    Err("技能导入线程异常；未自动提交或重试".into())
                                });
                            finish(&key, &control, result);
                        }
                    })
                    .map_err(|_| "技能导入线程无法创建".to_owned())?;
            }
            Ok(tx)
        })
        .as_ref()
        .map_err(Clone::clone)
}
pub(crate) fn busy() -> bool {
    let r = registry().lock().unwrap_or_else(|e| e.into_inner());
    !r.pending.is_empty()
        || !r.tickets.is_empty()
        || r.results
            .iter()
            .any(|update| matches!(&update.state, State::WorkspaceScanned { .. }))
}
pub(crate) fn dispatch(
    workspace: PathBuf,
    referenced_paths: Vec<String>,
    request: Request,
) -> Result<(), String> {
    request.operation.validate()?;
    if let Command::Discard(ticket) = &request.command {
        let prefix = PathBuf::from("skill-files").join(ticket);
        if referenced_paths
            .iter()
            .any(|p| Path::new(p).starts_with(&prefix))
        {
            return Err("这份副本已被技能库引用，未作为候选回收".into());
        }
    }

    if matches!(request.command, Command::Cancel) {
        cancel(&request.operation);
        return Ok(());
    }
    files::checked_root(&workspace)?;
    let control = Arc::new(Control::new());
    {
        let mut r = registry().lock().unwrap_or_else(|e| e.into_inner());
        if r.pending.len() + r.results.len() >= CAPACITY
            || r.pending.contains_key(&request.operation.id)
        {
            return Err("技能导入操作队列已满或身份重复".into());
        }
        r.pending.insert(
            request.operation.id.clone(),
            Pending {
                key: request.operation.clone(),
                control: control.clone(),
                waiting_reported: false,
            },
        );
    }
    let tree_picker = matches!(&request.command, Command::PickTree);
    let key = request.operation;
    let result = match request.command {
        Command::ScanWorkspace => queue(Work::ScanWorkspace {
            key: key.clone(),
            workspace,
            epoch: metadata_epoch(),
            control: control.clone(),
        }),
        Command::Recover => queue(Work::Recover {
            key: key.clone(),
            workspace,
            referenced_paths,
            epoch: registry()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .metadata_epoch,
            control: control.clone(),
        }),
        Command::PickFile | Command::PickTree => device::pick(
            key.clone(),
            workspace,
            control.clone(),
            matches!(request.command, Command::PickTree),
        ),
        Command::PreviewPath(path) => queue(Work::Preview {
            key: key.clone(),
            workspace,
            source: Source::Path(path),
            control: control.clone(),
        }),
        Command::PreviewUrl(url) => queue(Work::Preview {
            key: key.clone(),
            workspace,
            source: Source::Url(url),
            control: control.clone(),
        }),
        Command::ReadDocument { ticket, id, offset } => queue(Work::Read {
            key: key.clone(),
            ticket,
            id,
            offset,
            control: control.clone(),
        }),
        Command::Commit(ticket) => queue(Work::Commit {
            key: key.clone(),
            ticket,
            control: control.clone(),
        }),
        Command::Discard(ticket) => queue(Work::Discard {
            key: key.clone(),
            ticket,
            control: control.clone(),
        }),
        Command::Cancel => unreachable!(),
    };
    if let Err(error) = result {
        finish(&key, &control, Err(error));
    }
    Ok(())
}
fn queue(work: Work) -> Result<(), String> {
    workers()?
        .try_send(work)
        .map_err(|_| "技能导入线程队列已满，未执行".into())
}
pub(crate) fn cancel(key: &OperationKey) {
    let control = {
        let r = registry().lock().unwrap_or_else(|e| e.into_inner());
        r.pending
            .get(&key.id)
            .filter(|p| p.key == *key)
            .map(|p| p.control.clone())
    };
    if let Some(control) = control {
        control.cancel.cancel();
        device::cancel_signal(&control);
        device::cancel_picker(key, &control);
    }
}
fn finish(key: &OperationKey, control: &Arc<Control>, result: Result<State, String>) {
    let mut r = registry().lock().unwrap_or_else(|e| e.into_inner());
    if !r
        .pending
        .get(&key.id)
        .is_some_and(|p| p.key == *key && Arc::ptr_eq(&p.control, control))
    {
        return;
    }
    // Successful metadata publication is authoritative even if cancellation arrives later.
    let state = match result {
        Ok(State::CommitReady {
            ticket,
            workspace,
            skills,
        }) => State::CommitReady {
            ticket,
            workspace,
            skills,
        },
        Ok(State::WorkspaceScanned { .. }) if control.cancel.is_cancelled() => State::Cancelled,
        Ok(state) => state,
        Err(error) => State::Failed(error),
    };
    r.pending.remove(&key.id);
    r.results.push_back(Update {
        operation: key.clone(),
        state,
    });
}
pub(crate) fn poll(maximum: usize) -> Vec<Update> {
    queue_expiry();
    let mut cancel_keys = Vec::new();
    let mut waiting = Vec::new();
    {
        let mut r = registry().lock().unwrap_or_else(|e| e.into_inner());
        for p in r.pending.values_mut() {
            if p.control.check().is_err() {
                cancel_keys.push(p.key.clone());
                if !p.waiting_reported {
                    p.waiting_reported = true;
                    waiting.push(Update {
                        operation: p.key.clone(),
                        state: State::WaitingForTerminal,
                    });
                }
            }
        }
    }
    for key in cancel_keys {
        cancel(&key)
    }
    let mut r = registry().lock().unwrap_or_else(|e| e.into_inner());
    r.results.extend(waiting);
    (0..maximum.min(16))
        .filter_map(|_| r.results.pop_front())
        .collect()
}
fn queue_expiry() {
    let expiry = {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        if state.pending.len() + state.results.len() >= CAPACITY {
            return;
        }
        let ticket = state
            .tickets
            .iter()
            .find(|(_, t)| !t.committing && !t.expiry_queued && Instant::now() >= t.expires)
            .map(|(id, _)| id.clone());
        let Some(ticket) = ticket else { return };
        if let Some(value) = state.tickets.get_mut(&ticket) {
            value.expiry_queued = true;
        }
        let key = OperationKey {
            id: format!("skill-expire-{}", crate::controller::unique_id()),
            conversation_id: None,
            generation: 1,
        };
        let control = Arc::new(Control::new());
        state.pending.insert(
            key.id.clone(),
            Pending {
                key: key.clone(),
                control: control.clone(),
                waiting_reported: false,
            },
        );
        (key, ticket, control)
    };
    let (key, ticket, control) = expiry;
    if let Err(error) = queue(Work::Expire {
        key: key.clone(),
        ticket: ticket.clone(),
        control: control.clone(),
    }) {
        if let Some(value) = registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .tickets
            .get_mut(&ticket)
        {
            value.expiry_queued = false;
        }
        finish(&key, &control, Err(error));
    }
}
pub(crate) fn commit_uncertain(key: OperationKey, ticket: String, error: String) -> Update {
    let workspace = {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        state.tickets.get_mut(&ticket).map(|t| {
            t.committing = true;
            t.workspace.clone()
        })
    };
    if let Some(workspace) = workspace {
        let _ = recovery::uncertain(&workspace, &ticket);
    }
    Update {
        operation: key,
        state: State::Failed(format!(
            "技能库提交结果不确定：{error}；原文件已锁定保留，请重新打开应用核对。"
        )),
    }
}
pub(crate) fn metadata_epoch() -> u64 {
    registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .metadata_epoch
}

pub(crate) fn metadata_changed() {
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    state.metadata_epoch = state.metadata_epoch.checked_add(1).unwrap_or(u64::MAX);
}
/// Metadata deletion is already durable. Only receipt-owned, unreferenced copies may follow.
pub(crate) fn delete_installed_copy(
    workspace: &Path,
    removed: &Skill,
    remaining: &[Skill],
) -> Result<bool, String> {
    let mut shared = false;
    let mut errors = Vec::new();
    if !removed.edited_document_path.is_empty() {
        let mut edit = removed.clone();
        edit.document_path = std::mem::take(&mut edit.edited_document_path);
        edit.files_path = Path::new(&edit.document_path)
            .parent()
            .ok_or("技能编辑文档目录缺失")?
            .to_string_lossy()
            .into_owned();
        match recovery::delete_installed_copy(workspace, &edit, remaining) {
            Ok(retained) => shared |= retained,
            Err(error) => errors.push(error),
        }
    }
    match recovery::delete_installed_copy(workspace, removed, remaining) {
        Ok(retained) => shared |= retained,
        Err(error) => errors.push(error),
    }
    if errors.is_empty() {
        Ok(shared)
    } else {
        Err(errors.join("；"))
    }
}

#[derive(Clone)]
pub(crate) struct DocumentCopy {
    pub ticket: String,
    pub path: String,
    pub directory: String,
}
/// Called by the actor for user/model-authored documents, never for unapproved downloads.
pub(crate) fn write_document_copy(
    workspace: &Path,
    skill: &Skill,
    raw: Option<&str>,
) -> Result<DocumentCopy, String> {
    skill.validate()?;
    files::checked_root(workspace)?;
    let composed;
    let raw = match raw {
        Some(raw) => raw,
        None => {
            composed = format!(
                "---\njagent_format: 1\nname: {}\ndescription: {}\n---\n{}",
                serde_json::to_string(&skill.name).map_err(|_| "技能名称编码失败")?,
                serde_json::to_string(&skill.description).map_err(|_| "技能简介编码失败")?,
                skill.content
            );
            &composed
        }
    };
    if raw.len() > DOCUMENT_BYTES {
        return Err("技能完整文档超过2MiB，未保存".into());
    }
    let relative_document = PathBuf::from(&skill.id).join("SKILL.md");
    let mut tree = one_file(relative_document.clone(), raw.as_bytes().to_vec())?;
    tree.layout.insert(
        0,
        Layout {
            path: PathBuf::from(&skill.id),
            directory: true,
            mode: 0o700,
            bytes: 0,
            sha256: None,
        },
    );
    validate_tree(&tree)?;
    let ticket = format!(
        "s{}-{}",
        crate::controller::now_millis(),
        crate::controller::unique_id()
    );
    let prefix = PathBuf::from("skill-files").join(&ticket);
    let library = workspace.join("skill-files");
    match fs::create_dir(&library) {
        Ok(()) => files::mode(&library, 0o700)?,
        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {
            files::checked_root(&library)?;
        }
        Err(_) => return Err("技能文档目录无法创建".into()),
    }
    {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        if state.tickets.len() + state.protected.len() >= CAPACITY {
            return Err("技能文档候选已满，请先完成现有导入".into());
        }
        state.protected.insert(ticket.clone());
    }
    let path = workspace.join(&prefix);
    let control = Control::new();
    let mut created = false;
    let result = (|| {
        fs::create_dir(&path).map_err(|_| "技能文档候选无法创建")?;
        created = true;
        files::mode(&path, 0o700)?;
        let mut origin = skill.clone();
        origin.document_path = relative_document.to_string_lossy().into_owned();
        origin.files_path = skill.id.clone();
        origin.edited_document_path.clear();
        recovery::begin(workspace, &ticket, &path, &tree, &[origin])?;
        files::write_tree(&path, &tree, &control.cancel)?;
        files::verify_layout(&path, &tree.layout)?;
        recovery::prepared(workspace, &ticket)?;
        Ok(DocumentCopy {
            ticket: ticket.clone(),
            path: prefix
                .join(&relative_document)
                .to_string_lossy()
                .into_owned(),
            directory: prefix.join(&skill.id).to_string_lossy().into_owned(),
        })
    })();
    if let Err(error) = result {
        let cleanup = if created {
            recovery::cleanup_failed(workspace, &ticket, &tree, &control)
                .or_else(|cleanup_error| fs::remove_dir(&path).map_err(|_| cleanup_error))
        } else {
            Ok(())
        };
        registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .protected
            .remove(&ticket);
        return Err(match cleanup {
            Ok(()) => error,
            Err(cleanup_error) => format!(
                "{error}；{cleanup_error}；剩余文档副本保留在{}",
                path.display()
            ),
        });
    }
    result
}
pub(crate) fn finish_document_copy(
    workspace: &Path,
    copy: &DocumentCopy,
    saved: bool,
    uncertain: bool,
) -> Result<(), String> {
    if saved {
        let result = recovery::saved(workspace, &copy.ticket);
        registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .protected
            .remove(&copy.ticket);
        return result;
    } else if uncertain {
        recovery::uncertain(workspace, &copy.ticket)?;
        return Ok(());
    } else {
        let result = recovery::cleanup(workspace, &copy.ticket, &Control::new());
        registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .protected
            .remove(&copy.ticket);
        return result;
    }
}

pub(crate) fn download_receipt(workspace: &Path, paths: &[String], saved: bool, uncertain: bool) {
    let mut tickets = BTreeSet::new();
    for path in paths {
        let mut parts = Path::new(path).components();
        if parts.next().is_some_and(|p| p.as_os_str() == "skill-files") {
            if let Some(name) = parts.next().and_then(|p| p.as_os_str().to_str()) {
                tickets.insert(name.to_owned());
            }
        }
    }
    for ticket in tickets {
        {
            let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
            if saved {
                state.protected.insert(ticket.clone());
                state.tickets.remove(&ticket);
            } else if let Some(t) = state.tickets.get_mut(&ticket) {
                t.committing = uncertain;
            }
        }
        if saved {
            if recovery::saved(workspace, &ticket).is_ok() {
                registry()
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .protected
                    .remove(&ticket);
            }
        } else if uncertain {
            let _ = recovery::uncertain(workspace, &ticket);
        }
    }
}
pub(crate) fn committed(
    key: OperationKey,
    ticket: String,
    result: Result<Vec<String>, String>,
) -> Update {
    let mut r = registry().lock().unwrap_or_else(|e| e.into_inner());
    let state = match result {
        Ok(ids) => {
            r.protected.insert(ticket.clone());
            let workspace = r.tickets.remove(&ticket).map(|t| t.workspace);
            drop(r);
            if let Some(workspace) = workspace {
                if recovery::saved(&workspace, &ticket).is_ok() {
                    registry()
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .protected
                        .remove(&ticket);
                }
            }
            return Update {
                operation: key,
                state: State::Saved(ids),
            };
        }
        Err(error) => {
            if let Some(t) = r.tickets.get_mut(&ticket) {
                t.committing = false;
            }
            State::Failed(format!(
                "技能库提交未确认：{error}；原导入文件保留，未自动重试"
            ))
        }
    };
    Update {
        operation: key,
        state,
    }
}

fn process(work: Work) -> Result<State, String> {
    match work {
        Work::ScanWorkspace {
            workspace,
            epoch,
            control,
            ..
        } => {
            let _permit = source_permit(&control)?;
            let entries = workspace_scan::scan(&workspace, &control)?;
            control.check()?;
            Ok(State::WorkspaceScanned {
                workspace,
                epoch,
                entries,
            })
        }
        Work::Recover {
            workspace,
            referenced_paths,
            epoch,
            control,
            ..
        } => {
            let (reviews, warnings) =
                recovery::recover(workspace, referenced_paths, epoch, &control)?;
            Ok(State::Recovered { reviews, warnings })
        }
        Work::Expire {
            ticket, control, ..
        } => {
            let workspace = {
                let r = registry().lock().unwrap_or_else(|e| e.into_inner());
                let t = r.tickets.get(&ticket).ok_or("技能候选已不存在")?;
                if t.committing {
                    return Err("技能候选正在提交，未过期回收".into());
                }
                t.workspace.clone()
            };
            let notice = recovery::cleanup(&workspace, &ticket, &control).err();
            registry()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .tickets
                .remove(&ticket);
            Ok(State::Expired { ticket, notice })
        }
        Work::Preview {
            key,
            workspace,
            source,
            control,
        } => prepare(&key, workspace, source, &control),
        Work::Read {
            ticket,
            id,
            offset,
            control,
            ..
        } => {
            control.check()?;
            let (path, layout, document) = {
                let r = registry().lock().unwrap_or_else(|e| e.into_inner());
                let t = r.tickets.get(&ticket).ok_or("导入候选已不存在")?;
                if t.committing || t.expiry_queued || Instant::now() > t.expires {
                    return Err("导入候选正在提交或已过期".into());
                }
                let skill = t
                    .skills
                    .iter()
                    .find(|s| s.id == id)
                    .ok_or("候选技能已不存在")?;
                (
                    t.path.clone(),
                    t.layout.clone(),
                    skill.document_path.clone(),
                )
            };
            files::verify_layout(&path, &layout)?;
            let relative_document = Path::new(&document)
                .strip_prefix(Path::new("skill-files").join(&ticket))
                .map_err(|_| "候选文档前缀无效")?;
            let node = layout
                .iter()
                .find(|n| !n.directory && n.path == relative_document)
                .ok_or("候选文档路径不一致")?;
            let bytes = bounded_file(&path.join(&node.path), DOCUMENT_BYTES as u64, &control)?;
            let text = String::from_utf8(bytes).map_err(|_| "技能文档不是UTF8")?;
            if offset > text.len() || !text.is_char_boundary(offset) {
                return Err("文档分页位置无效".into());
            }
            let mut end = offset.saturating_add(32 * 1024).min(text.len());
            while end > offset && !text.is_char_boundary(end) {
                end -= 1;
            }
            Ok(State::Document {
                ticket,
                id,
                offset,
                next: (end < text.len()).then_some(end),
                text: text[offset..end].into(),
            })
        }
        Work::Commit {
            ticket, control, ..
        } => {
            control.check()?;
            let mut r = registry().lock().unwrap_or_else(|e| e.into_inner());
            let t = r.tickets.get_mut(&ticket).ok_or("导入候选已不存在")?;
            if t.committing || t.expiry_queued || Instant::now() > t.expires {
                return Err("导入候选正在提交或已过期".into());
            }
            t.committing = true;
            let (path, layout, workspace, skills) = (
                t.path.clone(),
                t.layout.clone(),
                t.workspace.clone(),
                t.skills.clone(),
            );
            drop(r);
            let verify = files::verify_layout(&path, &layout).and_then(|_| control.check());
            if let Err(error) = verify {
                if let Some(t) = registry()
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .tickets
                    .get_mut(&ticket)
                {
                    t.committing = false;
                }
                return Err(error);
            }
            Ok(State::CommitReady {
                ticket,
                workspace,
                skills,
            })
        }
        Work::Discard {
            ticket, control, ..
        } => {
            control.check()?;
            let (path, layout) = {
                let mut r = registry().lock().unwrap_or_else(|e| e.into_inner());
                let t = r.tickets.get_mut(&ticket).ok_or("导入候选已不存在")?;
                if t.committing || t.expiry_queued {
                    return Err("正在提交的技能候选不能丢弃".into());
                }
                t.committing = true;
                (t.path.clone(), t.layout.clone())
            };
            let workspace = path
                .parent()
                .and_then(Path::parent)
                .ok_or("技能候选工作区无效")?;
            let _ = layout;
            if let Err(error) = recovery::cleanup(workspace, &ticket, &control) {
                if let Some(t) = registry()
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .tickets
                    .get_mut(&ticket)
                {
                    t.committing = false;
                }
                return Err(error);
            }
            registry()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .tickets
                .remove(&ticket);
            Ok(State::Discarded)
        }
    }
}

fn prepare(
    key: &OperationKey,
    workspace: PathBuf,
    source: Source,
    control: &Arc<Control>,
) -> Result<State, String> {
    control.check()?;
    files::checked_root(&workspace)?;
    if registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .tickets
        .len()
        >= CAPACITY
    {
        return Err("技能导入候选已达4份，请保存或丢弃现有候选".into());
    }
    let _permit = source_permit(control)?;
    let source_url = match &source {
        Source::Url(url) => Some(url.clone()),
        _ => None,
    };
    let mut tree = match source {
        Source::Path(path) => local_tree(&workspace, &path, control)?,
        Source::Url(url) => url_tree(&url, control)?,
        #[cfg(target_os = "android")]
        Source::Uri { uri, tree } => device::read_uri(&uri, tree, control)?,
    };
    validate_tree(&tree)?;
    let mut skills = discover(&tree)?;
    if skills.is_empty() {
        return Err("选定内容没有SKILL.md入口或顶层Markdown文档".into());
    }
    let nonce = crate::controller::now_millis();
    let ticket = format!(
        "s{}-{}",
        nonce,
        files::hash(format!("{}:{}", key.id, key.generation).as_bytes())
    );
    let relative = PathBuf::from("skill-files").join(&ticket);
    let path = workspace.join(&relative);
    let library = workspace.join("skill-files");
    match fs::create_dir(&library) {
        Ok(()) => files::mode(&library, 0o700)?,
        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {
            files::checked_root(&library)?;
        }
        Err(_) => return Err("技能文件目录创建失败".into()),
    }
    fs::create_dir(&path).map_err(|_| "技能候选目录已存在或不能创建")?;
    files::mode(&path, 0o700)?;
    for skill in &mut skills {
        if let Some(url) = &source_url {
            skill.source = "downloaded".into();
            skill.source_url = url.clone();
        }
    }
    if let Err(error) = recovery::begin(&workspace, &ticket, &path, &tree, &skills) {
        let cleanup = fs::remove_dir(&path);
        return Err(match cleanup {
            Ok(()) => error,
            Err(_) => format!("{error}；未写入的候选目录仍保留在{}", path.display()),
        });
    }
    // Companion resources never run during import. Preserve original names, bytes and rwx.
    let copied = files::write_tree(&path, &tree, &control.cancel)
        .and_then(|_| files::verify_layout(&path, &tree.layout))
        .and_then(|_| control.check())
        .and_then(|_| recovery::prepared(&workspace, &ticket));
    if let Err(error) = copied {
        let cleanup = Control::new();
        let outcome = recovery::cleanup_failed(&workspace, &ticket, &tree, &cleanup);
        return Err(match outcome {
            Ok(()) => format!("{error}；匹配的临时复制文件已回收，源文件未改变"),
            Err(cleanup_error) => format!(
                "{error}；{cleanup_error}；剩余复制文件保留在{}",
                path.display()
            ),
        });
    }
    for skill in &mut skills {
        if let Some(url) = &source_url {
            skill.source = "downloaded".into();
            skill.source_url = url.clone();
        }
        skill.document_path = relative
            .join(&skill.document_path)
            .to_string_lossy()
            .into_owned();
        skill.files_path = relative
            .join(&skill.files_path)
            .to_string_lossy()
            .into_owned();
        skill.enabled = false;
    }
    let review = Review {
        ticket: ticket.clone(),
        skills: skills
            .iter()
            .map(|s| Header {
                id: s.id.clone(),
                name: s.name.clone(),
                description: s.description.clone(),
                document_bytes: s.content.len(),
                resources: tree
                    .layout
                    .iter()
                    .filter(|n| !n.directory)
                    .count()
                    .saturating_sub(1),
            })
            .collect(),
        files: tree.files.len(),
        bytes: tree.layout.iter().map(|n| n.bytes).sum(),
        warnings: std::mem::take(&mut tree.warnings),
    };
    let mut r = registry().lock().unwrap_or_else(|e| e.into_inner());
    if r.tickets.len() >= CAPACITY {
        return Err("技能候选队列已满；已读取文件保留但未提交技能库".into());
    }
    r.tickets.insert(
        ticket,
        Ticket {
            workspace,
            path,
            layout: tree.layout,
            skills,
            review: review.clone(),
            expires: Instant::now() + Duration::from_secs(24 * 60 * 60),
            committing: false,
            expiry_queued: false,
        },
    );
    Ok(State::Prepared(review))
}
fn validate_tree(tree: &Tree) -> Result<(), String> {
    tree.validate()?;
    if tree.layout.len() > MAX_NODES
        || tree
            .layout
            .iter()
            .any(|n| n.path.components().count() > MAX_DEPTH || n.bytes > DOCUMENT_BYTES as u64)
        || tree.layout.iter().map(|n| n.bytes).sum::<u64>() > TOTAL_BYTES
    {
        return Err("技能导入超过200文件/目录、深度6、单文件2MiB或总量64MiB预算".into());
    }
    Ok(())
}
fn discover(tree: &Tree) -> Result<Vec<Skill>, String> {
    let entries: Vec<_> = tree
        .files
        .keys()
        .filter(|path| {
            path.file_name()
                .and_then(|s| s.to_str())
                .is_some_and(|name| name.eq_ignore_ascii_case("SKILL.md"))
                || path.components().count() == 1
                    && path
                        .extension()
                        .and_then(|s| s.to_str())
                        .is_some_and(|s| s.eq_ignore_ascii_case("md"))
        })
        .collect();
    let mut ids = BTreeSet::new();
    let mut skills = Vec::new();
    for path in entries {
        let name = path
            .file_name()
            .and_then(|s| s.to_str())
            .ok_or("技能入口文件名无效")?;
        let id = if name.eq_ignore_ascii_case("SKILL.md") {
            path.parent()
                .filter(|p| !p.as_os_str().is_empty())
                .and_then(Path::file_name)
                .and_then(|s| s.to_str())
                .unwrap_or("skill")
        } else {
            path.file_stem().and_then(|s| s.to_str()).unwrap_or("skill")
        };
        let id = crate::skills::sanitize_import_id(id);
        if !ids.insert(id.clone()) {
            return Err("技能导入包含重复ID，请拆分或修改文件夹名称".into());
        }
        let document =
            String::from_utf8(tree.files[path].clone()).map_err(|_| "技能文档不是UTF8")?;
        let mut skill = crate::skills::import_document(id, document)?;
        skill.document_path = path.to_string_lossy().into_owned();
        skill.files_path = path
            .parent()
            .unwrap_or(Path::new(""))
            .to_string_lossy()
            .into_owned();
        skills.push(skill);
    }
    Ok(skills)
}
fn bounded_file(path: &Path, maximum: u64, control: &Control) -> Result<Vec<u8>, String> {
    control.check()?;
    let before = fs::symlink_metadata(path).map_err(|_| "技能文件无法读取")?;
    if !before.is_file() || before.file_type().is_symlink() || before.len() > maximum {
        return Err("技能来源不是普通文件或超过预算".into());
    }
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK);
    }
    #[cfg(unix)]
    let mut input = open_without_links(path)?;
    #[cfg(not(unix))]
    let mut input = options.open(path).map_err(|_| "技能文件无法打开")?;
    let opened = input.metadata().map_err(|_| "技能文件身份读取失败")?;
    if !same(&before, &opened) {
        return Err("技能文件身份在打开时发生变化".into());
    }
    let mut bytes = Vec::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        control.check()?;
        let count = input.read(&mut buffer).map_err(|_| "技能文件读取失败")?;
        if count == 0 {
            break;
        };
        if bytes
            .len()
            .checked_add(count)
            .is_none_or(|n| n as u64 > maximum)
        {
            return Err("技能文件在读取时超过预算".into());
        }
        bytes.extend_from_slice(&buffer[..count]);
    }
    let after = input.metadata().map_err(|_| "技能文件身份无法确认")?;
    if !same(&opened, &after) || bytes.len() as u64 != opened.len() {
        return Err("技能文件读取期间变化，未导入".into());
    }
    Ok(bytes)
}
fn same(a: &fs::Metadata, b: &fs::Metadata) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        a.dev() == b.dev()
            && a.ino() == b.ino()
            && a.len() == b.len()
            && a.mtime() == b.mtime()
            && a.mtime_nsec() == b.mtime_nsec()
            && a.ctime() == b.ctime()
            && a.ctime_nsec() == b.ctime_nsec()
    }
    #[cfg(not(unix))]
    {
        a.len() == b.len() && a.modified().ok() == b.modified().ok()
    }
}
fn local_tree(workspace: &Path, path: &Path, control: &Control) -> Result<Tree, String> {
    let path = if path.is_absolute() {
        path.to_owned()
    } else {
        files::relative(path)?;
        workspace.join(path)
    };
    if path.canonicalize().map_err(|_| "技能来源路径无法解析")? != path {
        return Err("技能来源路径含链接或重定向，未导入".into());
    }
    let metadata = fs::symlink_metadata(&path).map_err(|_| "技能来源状态无法读取")?;
    if metadata.is_file() {
        let bytes = bounded_file(
            &path,
            if path
                .extension()
                .and_then(|s| s.to_str())
                .is_some_and(|s| s.eq_ignore_ascii_case("zip"))
            {
                64 * 1024 * 1024
            } else {
                DOCUMENT_BYTES as u64
            },
            control,
        )?;
        if bytes.starts_with(b"PK\x03\x04") {
            return crate::plugin_assets::archive::read_tree(
                &bytes,
                &control.cancel,
                MAX_NODES,
                TOTAL_BYTES,
            );
        }
        let name = path.file_name().ok_or("技能文件名无效")?;
        return one_file(PathBuf::from(name), bytes);
    }
    if !metadata.is_dir() {
        return Err("技能来源不是文件或目录".into());
    }
    let mut tree = Tree {
        layout: Vec::new(),
        files: BTreeMap::new(),
        warnings: Vec::new(),
    };
    fn visit(root: &Path, path: &Path, tree: &mut Tree, control: &Control) -> Result<(), String> {
        control.check()?;
        let relative = path.strip_prefix(root).map_err(|_| "技能目录扫描越界")?;
        files::relative(relative)?;
        if tree.layout.len() >= MAX_NODES || relative.components().count() > MAX_DEPTH {
            return Err("技能目录超出文件或深度预算".into());
        }
        let metadata = fs::symlink_metadata(path).map_err(|_| "技能目录状态变化")?;
        if metadata.file_type().is_symlink()
            || !metadata.is_dir() && !metadata.is_file()
            || path.canonicalize().map_err(|_| "技能路径无法解析")? != path
        {
            return Err("技能目录包含链接或特殊文件".into());
        }
        #[cfg(unix)]
        let mode = {
            use std::os::unix::fs::MetadataExt;
            metadata.mode() & 0o777
        };
        #[cfg(not(unix))]
        let mode = if metadata.is_dir() { 0o700 } else { 0o600 };
        let mut node = Layout {
            path: relative.to_owned(),
            directory: metadata.is_dir(),
            mode,
            bytes: 0,
            sha256: None,
        };
        if metadata.is_dir() {
            tree.layout.push(node);
            let mut entries = Vec::new();
            for entry in fs::read_dir(path).map_err(|_| "技能目录无法读取")? {
                if entries.len() >= MAX_NODES {
                    return Err("技能目录条目超过预算".into());
                }
                entries.push(entry.map_err(|_| "技能目录项读取失败")?);
            }
            entries.sort_by_key(|e| e.file_name());
            for entry in entries {
                visit(root, &entry.path(), tree, control)?;
            }
        } else {
            let total: u64 = tree.layout.iter().map(|n| n.bytes).sum();
            if total
                .checked_add(metadata.len())
                .is_none_or(|n| n > TOTAL_BYTES)
            {
                return Err("技能导入总量超过64MiB".into());
            }
            let bytes = bounded_file(path, DOCUMENT_BYTES as u64, control)?;
            node.bytes = bytes.len() as u64;
            node.sha256 = Some(files::hash(&bytes));
            tree.files.insert(relative.into(), bytes);
            tree.layout.push(node);
        }
        Ok(())
    }
    let root = if fs::read_dir(&path)
        .map_err(|_| "技能目录无法读取")?
        .filter_map(Result::ok)
        .any(|e| {
            e.file_name()
                .to_str()
                .is_some_and(|s| s.eq_ignore_ascii_case("SKILL.md"))
        }) {
        path.parent().ok_or("技能根目录没有父目录")?.to_owned()
    } else {
        path.clone()
    };
    if root != path {
        visit(&root, &path, &mut tree, control)?;
    } else {
        for entry in fs::read_dir(&path).map_err(|_| "技能目录无法读取")? {
            visit(
                &root,
                &entry.map_err(|_| "技能目录项读取失败")?.path(),
                &mut tree,
                control,
            )?;
        }
    }
    Ok(tree)
}
fn one_file(path: PathBuf, bytes: Vec<u8>) -> Result<Tree, String> {
    files::relative(&path)?;
    let layout = vec![Layout {
        path: path.clone(),
        directory: false,
        mode: 0o600,
        bytes: bytes.len() as u64,
        sha256: Some(files::hash(&bytes)),
    }];
    Ok(Tree {
        layout,
        files: BTreeMap::from([(path, bytes)]),
        warnings: Vec::new(),
    })
}
fn url_tree(url: &str, control: &Arc<Control>) -> Result<Tree, String> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|_| "技能下载调度器无法创建")?;
    runtime.block_on(async{
        tokio::time::timeout(control.deadline.saturating_duration_since(Instant::now()),async {
        let mut url=reqwest::Url::parse(url.trim()).map_err(|_|"技能链接不是有效URL")?;
        let initial=url.to_string();
        for _ in 0..9 {control.check()?;
            if !matches!(url.scheme(),"http"|"https")||!url.username().is_empty()||url.password().is_some(){return Err("技能链接必须为不含内嵌凭据的HTTP或HTTPS地址".into())}
            let client=crate::web::client_for(&url,&control.cancel).await?;
            let response=tokio::select!{biased;_=control.cancel.cancelled()=>return Err("技能下载已取消".into()),r=client.get(url.clone()).header(reqwest::header::USER_AGENT,"JAgent/skill-import").header(reqwest::header::ACCEPT,"text/markdown,text/plain,*/*").send()=>r.map_err(|_|"技能下载连接失败")?};
            if response.status().is_redirection(){let location=response.headers().get(reqwest::header::LOCATION).and_then(|v|v.to_str().ok()).ok_or("技能下载重定向缺少有效地址")?;url=url.join(location).map_err(|_|"技能下载重定向地址无效")?;continue}
            if !response.status().is_success(){return Err(format!("技能下载失败：HTTP {}",response.status().as_u16()))}
            if response.content_length().is_some_and(|n|n>DOCUMENT_BYTES as u64){return Err("技能文档超过2MiB".into())}
            let mut response=response;let mut bytes=Vec::new();loop{control.check()?;let chunk=tokio::select!{biased;_=control.cancel.cancelled()=>return Err("技能下载已取消".into()),r=response.chunk()=>r.map_err(|_|"技能下载正文读取失败")?};let Some(chunk)=chunk else{break};if bytes.len().checked_add(chunk.len()).is_none_or(|n|n>DOCUMENT_BYTES){return Err("技能下载实际正文超过2MiB".into())}bytes.extend_from_slice(&chunk);}
            let original=reqwest::Url::parse(&initial).map_err(|_|"技能源地址无效")?;let raw=original.path_segments().and_then(|s|s.filter(|p|!p.is_empty()).next_back()).unwrap_or("");
            let id=if raw.is_empty()||raw.eq_ignore_ascii_case("SKILL.md"){original.host_str().unwrap_or("skill")}else{raw.strip_suffix(".md").unwrap_or(raw)};
            let id=crate::skills::sanitize_import_id(id);let mut tree=one_file(PathBuf::from(id).join("SKILL.md"),bytes)?;tree.layout.insert(0,Layout{path:tree.files.keys().next().and_then(|p|p.parent()).unwrap().to_owned(),directory:true,mode:0o700,bytes:0,sha256:None});
            tree.warnings.push(format!("网络下载来源：{initial}"));return Ok(tree)
        }Err("技能下载重定向超过8次，未保存".into())
        }).await.map_err(|_|"技能下载超过120秒截止时间，未自动重试".to_owned())?
    })
}

/// Called only after the immutable model invocation has been approved. Its existing
/// main/child cancellation token owns the request; no credentials are forwarded.
pub(crate) fn download_for_tool(
    target: &crate::skills::DownloadTarget,
    cancel: Arc<CancelToken>,
) -> Result<Vec<Skill>, String> {
    if cancel.is_cancelled() {
        return Err("任务已停止，未下载技能".into());
    }
    let control = Arc::new(Control {
        cancel,
        deadline: Instant::now() + Duration::from_secs(120),
        #[cfg(target_os = "android")]
        signal: Mutex::new(None),
    });
    let key = OperationKey {
        id: format!("skill-download-{}", target.nonce),
        conversation_id: None,
        generation: 1,
    };
    key.validate()?;
    let State::Prepared(review) = prepare(
        &key,
        target.workspace.clone(),
        Source::Url(target.url.clone()),
        &control,
    )?
    else {
        return Err("技能下载候选类型无效".into());
    };
    let (path, layout, skills) = {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        let ticket = state
            .tickets
            .get_mut(&review.ticket)
            .ok_or("技能下载候选已不存在")?;
        ticket.committing = true;
        (
            ticket.path.clone(),
            ticket.layout.clone(),
            ticket.skills.clone(),
        )
    };
    if let Err(error) = control
        .check()
        .and_then(|_| files::verify_layout(&path, &layout))
    {
        if let Some(t) = registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .tickets
            .get_mut(&review.ticket)
        {
            t.committing = false;
        }
        return Err(error);
    }
    Ok(skills)
}

#[cfg(unix)]
fn open_without_links(path: &Path) -> Result<File, String> {
    use std::ffi::CString;
    use std::os::fd::{AsRawFd, FromRawFd};
    use std::path::Component;
    if !path.is_absolute() {
        return Err("技能源文件必须为绝对路径".into());
    }
    let root = CString::new("/").unwrap();
    let fd = unsafe {
        libc::open(
            root.as_ptr(),
            libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
        )
    };
    if fd < 0 {
        return Err("技能源目录无法打开".into());
    }
    let mut parent = unsafe { File::from_raw_fd(fd) };
    let parts: Vec<_> = path
        .components()
        .filter(|p| !matches!(p, Component::RootDir))
        .collect();
    for (index, part) in parts.iter().enumerate() {
        let Component::Normal(name) = part else {
            return Err("技能源路径含非普通组件".into());
        };
        use std::os::unix::ffi::OsStrExt;
        let name = CString::new(name.as_bytes()).map_err(|_| "技能源路径含NUL")?;
        let flags = libc::O_RDONLY
            | libc::O_NOFOLLOW
            | libc::O_CLOEXEC
            | if index + 1 == parts.len() {
                libc::O_NONBLOCK
            } else {
                libc::O_DIRECTORY
            };
        let next = unsafe { libc::openat(parent.as_raw_fd(), name.as_ptr(), flags) };
        if next < 0 {
            return Err("技能源路径不能安全打开，未跟随符号链接".into());
        }
        parent = unsafe { File::from_raw_fd(next) };
    }
    Ok(parent)
}
