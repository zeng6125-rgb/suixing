//! Images never enter Settings JSON or model context. One bounded worker owns codecs and files.
mod gc;
use crate::{
    network::CancelToken,
    platform::{
        attachments::{self, AttachmentDraft, AttachmentKind, PixelFrame},
        permissions::OperationKey,
    },
    store::Store,
};
pub(crate) use gc::retain;
use std::{
    collections::{HashMap, VecDeque},
    fs::{self, Metadata, OpenOptions},
    io::{Read, Write},
    path::{Component, Path, PathBuf},
    sync::{
        Arc,
        mpsc::{self, Receiver, SyncSender, TryRecvError},
    },
    time::{Duration, Instant},
};
const AVATAR_BYTES: usize = 2 * 1024 * 1024;
const MAX_SIDE: u32 = 1080;
pub fn validate_path(path: &str) -> Result<(), String> {
    if path.is_empty() {
        return Ok(());
    }
    let parts = Path::new(path).components().collect::<Vec<_>>();
    if path.len() > 1024
        || parts.len() != 2
        || parts[0] != Component::Normal(std::ffi::OsStr::new("assistants"))
        || !matches!(parts[1], Component::Normal(_))
        || !path.ends_with(".png")
        || path.contains('\\')
        || path.contains('\0')
    {
        return Err("助手图片路径必须是assistants目录内的相对PNG文件".into());
    }
    Ok(())
}
fn id_valid(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 128
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}
#[derive(Clone, Copy, Debug)]
pub struct Crop {
    pub left: u32,
    pub top: u32,
    pub side: u32,
}
pub enum Request {
    Read {
        request_id: String,
        path: String,
    },
    Pick {
        operation: OperationKey,
        assistant_id: String,
    },
    Crop {
        operation: OperationKey,
        selection_id: String,
        crop: Crop,
    },
    Cancel(OperationKey),
}
impl Request {
    pub fn failure(&self, error: impl Into<String>) -> Update {
        match self {
            Self::Read { request_id, path } => Update::Read {
                request_id: request_id.clone(),
                path: path.clone(),
                result: Err(error.into()),
            },
            Self::Pick { operation, .. }
            | Self::Crop { operation, .. }
            | Self::Cancel(operation) => Update::Failed {
                operation: operation.clone(),
                error: error.into(),
            },
        }
    }
}
#[derive(Clone, Debug)]
pub enum Update {
    Read {
        request_id: String,
        path: String,
        result: Result<Arc<PixelFrame>, String>,
    },
    Selected {
        operation: OperationKey,
        assistant_id: String,
        selection_id: String,
        pixels: Arc<PixelFrame>,
    },
    Saved {
        operation: OperationKey,
        assistant_id: String,
        path: String,
        pixels: Arc<PixelFrame>,
    },
    Failed {
        operation: OperationKey,
        error: String,
    },
    Cancelled {
        operation: OperationKey,
    },
}
impl Update {
    pub fn with_error(mut self, error: String) -> Self {
        match &mut self {
            Self::Read { result, .. } => *result = Err(error),
            Self::Failed { error: current, .. } => *current = error,
            _ => {}
        }
        self
    }
    pub fn bytes(&self) -> usize {
        match self {
            Self::Read { result, .. } => result.as_ref().map_or_else(
                |error| error.len() + 1024,
                |pixels| pixels.rgba.len() + 1024,
            ),
            Self::Selected { pixels, .. } | Self::Saved { pixels, .. } => pixels.rgba.len() + 2048,
            Self::Failed { error, .. } => error.len() + 1024,
            Self::Cancelled { .. } => 1024,
        }
    }
}
struct Picker {
    operation: OperationKey,
    assistant_id: String,
    generation: String,
    namespace: PathBuf,
    started: Instant,
}
struct Selected {
    operation: OperationKey,
    assistant_id: String,
    generation: String,
    namespace: PathBuf,
    selection_id: String,
    pixels: Arc<PixelFrame>,
    saving: bool,
    started: Instant,
}
struct ReadPending {
    request_id: String,
    generation: String,
    cancel: Arc<CancelToken>,
    started: Instant,
}
enum Work {
    Read {
        request_id: String,
        path: String,
        namespace: PathBuf,
    },
    Decode {
        operation: OperationKey,
        assistant_id: String,
        draft: AttachmentDraft,
        staging: PathBuf,
    },
    Cleanup {
        operation: OperationKey,
        namespace: PathBuf,
    },
    Maintain {
        namespace: PathBuf,
        root: PathBuf,
        active: std::collections::HashSet<String>,
    },
    Save {
        operation: OperationKey,
        assistant_id: String,
        namespace: PathBuf,
        pixels: Arc<PixelFrame>,
        crop: Crop,
    },
}
struct Job {
    work: Work,
    generation: String,
    cancel: Arc<CancelToken>,
}
enum Done {
    Read {
        request_id: String,
        path: String,
        result: Result<Arc<PixelFrame>, String>,
    },
    Decode {
        operation: OperationKey,
        assistant_id: String,
        result: Result<Arc<PixelFrame>, String>,
    },
    Cleanup {
        operation: OperationKey,
        result: Result<(), String>,
    },
    Maintain {
        result: Result<(), String>,
    },
    Save {
        operation: OperationKey,
        assistant_id: String,
        result: Result<(String, Arc<PixelFrame>), String>,
    },
}
struct Completion {
    done: Done,
    generation: String,
}
pub struct Runtime {
    send: SyncSender<Job>,
    receive: Receiver<Completion>,
    reads: HashMap<String, ReadPending>,
    picker: Option<Picker>,
    selected: Option<Selected>,
    retired: VecDeque<(OperationKey, PathBuf)>,
    active_cancel: Option<Arc<CancelToken>>,
    maintenance_pending: bool,
    maintenance_after: Instant,
}
impl Runtime {
    pub fn boot() -> Result<Self, String> {
        let (send, work) = mpsc::sync_channel::<Job>(16);
        let (complete, receive) = mpsc::channel();
        std::thread::Builder::new()
            .name("rust-avatar-images".into())
            .spawn(move || {
                while let Ok(job) = work.recv() {
                    let done = match job.work {
                        Work::Read {
                            request_id,
                            path,
                            namespace,
                        } => {
                            let result = load(&namespace, &path, &job.cancel).map(Arc::new);
                            Done::Read {
                                request_id,
                                path,
                                result,
                            }
                        }
                        Work::Decode {
                            operation,
                            assistant_id,
                            draft,
                            staging,
                        } => {
                            let result = if job.cancel.is_cancelled() {
                                Err("图片读取已取消".into())
                            } else {
                                draft
                                    .preview
                                    .ok_or_else(|| "选择器没有返回头像像素".to_owned())
                                    .and_then(|pixels| {
                                        check_pixels(&pixels)?;
                                        if job.cancel.is_cancelled() {
                                            Err("图片读取已取消".into())
                                        } else {
                                            Ok(pixels)
                                        }
                                    })
                            };
                            let cleanup = cleanup_staging(&staging);
                            let result = result.and_then(|pixels| cleanup.map(|_| pixels));
                            Done::Decode {
                                operation,
                                assistant_id,
                                result,
                            }
                        }
                        Work::Cleanup {
                            operation,
                            namespace,
                        } => {
                            let staging = namespace.join("avatar-inbox").join(&operation.id);
                            Done::Cleanup {
                                operation,
                                result: cleanup_staging(&staging),
                            }
                        }
                        Work::Maintain {
                            namespace,
                            root,
                            active,
                        } => Done::Maintain {
                            result: gc::collect(&namespace, &root, &active),
                        },
                        Work::Save {
                            operation,
                            assistant_id,
                            namespace,
                            pixels,
                            crop,
                        } => {
                            let result =
                                save(&namespace, &assistant_id, &pixels, crop, &job.cancel);
                            Done::Save {
                                operation,
                                assistant_id,
                                result,
                            }
                        }
                    };
                    if complete
                        .send(Completion {
                            done,
                            generation: job.generation,
                        })
                        .is_err()
                    {
                        break;
                    }
                }
            })
            .map_err(|_| "无法创建头像图像线程")?;
        Ok(Self {
            send,
            receive,
            reads: HashMap::new(),
            picker: None,
            selected: None,
            retired: VecDeque::new(),
            active_cancel: None,
            maintenance_pending: false,
            maintenance_after: Instant::now(),
        })
    }
    fn retire(&mut self, key: OperationKey, namespace: PathBuf) {
        self.retired.push_back((key, namespace));
        while self.retired.len() > 32 {
            self.retired.pop_front();
        }
    }
    fn cancel(&mut self, key: &OperationKey) {
        let owns = self.picker.as_ref().is_some_and(|p| &p.operation == key)
            || self.selected.as_ref().is_some_and(|p| &p.operation == key);
        if !owns {
            return;
        }
        if self.picker.as_ref().is_some_and(|p| &p.operation == key) {
            if let Some(p) = self.picker.take() {
                attachments::cancel(&p.operation.id);
                self.retire(p.operation, p.namespace);
            }
        }
        if self.selected.as_ref().is_some_and(|p| &p.operation == key) {
            self.selected = None;
        }
        if let Some(cancel) = self.active_cancel.take() {
            cancel.cancel();
        }
    }
    pub fn dispatch(&mut self, request: Request, store: &Store) -> Result<(), String> {
        let generation = store.history_generation_token();
        match request {
            Request::Read { request_id, path } => {
                validate_path(&path)?;
                if path.is_empty() || !id_valid(&request_id) {
                    return Err("头像读取身份无效".into());
                }
                if self.reads.len() >= 16 || self.reads.contains_key(&path) {
                    return Err("头像读取队列已满或该图片正在读取".into());
                }
                retain(&store.avatar_namespace(), [path.as_str()])?;
                let cancel = Arc::new(CancelToken::new());
                self.send
                    .try_send(Job {
                        work: Work::Read {
                            request_id: request_id.clone(),
                            path: path.clone(),
                            namespace: store.avatar_namespace(),
                        },
                        generation: generation.clone(),
                        cancel: cancel.clone(),
                    })
                    .map_err(|_| "头像线程正在处理其它图片，请稍后重试")?;
                self.reads.insert(
                    path,
                    ReadPending {
                        request_id,
                        generation,
                        cancel,
                        started: Instant::now(),
                    },
                );
                Ok(())
            }
            Request::Pick {
                operation,
                assistant_id,
            } => {
                store.admit_avatar_write()?;
                operation.validate()?;
                if operation.conversation_id.is_some()
                    || !id_valid(&assistant_id)
                    || !id_valid(&operation.id)
                {
                    return Err("助手头像选择身份无效".into());
                }
                if self.picker.is_some() || self.selected.is_some() || self.active_cancel.is_some()
                {
                    return Err("请先完成或取消当前头像操作".into());
                }
                let namespace = store.avatar_namespace();
                let inbox = directory(&namespace, "avatar-inbox")?;
                let staging = directory(&inbox, &operation.id)?;
                attachments::pick(operation.clone(), AttachmentKind::Avatar, staging)?;
                self.picker = Some(Picker {
                    operation,
                    assistant_id,
                    generation,
                    namespace,
                    started: Instant::now(),
                });
                Ok(())
            }
            Request::Crop {
                operation,
                selection_id,
                crop,
            } => {
                store.admit_avatar_write()?;
                operation.validate()?;
                let source = self
                    .selected
                    .as_ref()
                    .filter(|source| {
                        source.operation == operation
                            && source.selection_id == selection_id
                            && source.generation == generation
                            && !source.saving
                    })
                    .ok_or("裁剪票据已失效或正在保存")?;
                validate_crop(&source.pixels, crop)?;
                let cancel = Arc::new(CancelToken::new());
                let work = Work::Save {
                    operation,
                    assistant_id: source.assistant_id.clone(),
                    namespace: source.namespace.clone(),
                    pixels: source.pixels.clone(),
                    crop,
                };
                self.send
                    .try_send(Job {
                        work,
                        generation,
                        cancel: cancel.clone(),
                    })
                    .map_err(|_| "头像保存线程队列已满")?;
                if let Some(source) = self.selected.as_mut() {
                    source.saving = true;
                }
                self.active_cancel = Some(cancel);
                Ok(())
            }
            Request::Cancel(operation) => {
                operation.validate()?;
                self.cancel(&operation);
                Ok(())
            }
        }
    }
    pub fn tick(&mut self, store: &Store) -> Vec<Update> {
        let generation = store.history_generation_token();
        let mut output = Vec::new();
        let stale = self
            .picker
            .as_ref()
            .filter(|p| {
                p.generation != generation || p.started.elapsed() > Duration::from_secs(300)
            })
            .map(|p| p.operation.clone())
            .or_else(|| {
                self.selected
                    .as_ref()
                    .filter(|p| {
                        p.generation != generation || p.started.elapsed() > Duration::from_secs(300)
                    })
                    .map(|p| p.operation.clone())
            });
        if let Some(key) = stale {
            self.cancel(&key);
            output.push(Update::Cancelled { operation: key });
        }
        let expired = self
            .reads
            .iter()
            .filter(|(_, p)| {
                p.generation != generation || p.started.elapsed() > Duration::from_secs(60)
            })
            .map(|(path, _)| path.clone())
            .collect::<Vec<_>>();
        for path in expired {
            if let Some(p) = self.reads.remove(&path) {
                p.cancel.cancel();
                output.push(Update::Read {
                    request_id: p.request_id,
                    path,
                    result: Err("头像读取已取消或数据代已变化".into()),
                });
            }
        }
        let results = attachments::drain_matching_results(8, |key| {
            self.picker.as_ref().is_some_and(|p| p.operation == *key)
                || self.retired.iter().any(|(old, _)| old == key)
        });
        for result in results {
            if !self
                .picker
                .as_ref()
                .is_some_and(|p| p.operation == result.operation)
            {
                if let Some(index) = self
                    .retired
                    .iter()
                    .position(|(key, _)| key == &result.operation)
                {
                    if let Some((operation, namespace)) = self.retired.remove(index) {
                        let _ = self.send.try_send(Job {
                            work: Work::Cleanup {
                                operation,
                                namespace,
                            },
                            generation: generation.clone(),
                            cancel: Arc::new(CancelToken::new()),
                        });
                    }
                }
                continue;
            }
            let Some(picker) = self.picker.take() else {
                continue;
            };
            if result.cancelled {
                let _ = self.send.try_send(Job {
                    work: Work::Cleanup {
                        operation: picker.operation.clone(),
                        namespace: picker.namespace.clone(),
                    },
                    generation: picker.generation.clone(),
                    cancel: Arc::new(CancelToken::new()),
                });
                output.push(Update::Cancelled {
                    operation: picker.operation,
                });
                continue;
            }
            let operation = picker.operation.clone();
            let failure = || Update::Failed {
                operation: operation.clone(),
                error: "头像选择或读取没有完成".into(),
            };
            let mut drafts = match result.result {
                Ok(value) => value,
                Err(error) => {
                    let _ = self.send.try_send(Job {
                        work: Work::Cleanup {
                            operation: picker.operation.clone(),
                            namespace: picker.namespace.clone(),
                        },
                        generation: picker.generation.clone(),
                        cancel: Arc::new(CancelToken::new()),
                    });
                    output.push(failure().with_error(error));
                    continue;
                }
            };
            if drafts.len() != 1 {
                let _ = self.send.try_send(Job {
                    work: Work::Cleanup {
                        operation: picker.operation.clone(),
                        namespace: picker.namespace.clone(),
                    },
                    generation: picker.generation.clone(),
                    cancel: Arc::new(CancelToken::new()),
                });
                output.push(failure().with_error("请每次只选择一张头像图片".into()));
                continue;
            }
            let draft = drafts.remove(0);
            let cancel = Arc::new(CancelToken::new());
            let placeholder = Arc::new(PixelFrame {
                width: 0,
                height: 0,
                rgba: Vec::new(),
            });
            let staging = picker
                .namespace
                .join("avatar-inbox")
                .join(&picker.operation.id);
            let selected = Selected {
                operation: picker.operation.clone(),
                assistant_id: picker.assistant_id.clone(),
                generation: picker.generation.clone(),
                namespace: picker.namespace,
                selection_id: String::new(),
                pixels: placeholder,
                saving: false,
                started: Instant::now(),
            };
            let job = Job {
                work: Work::Decode {
                    operation: picker.operation,
                    staging,
                    assistant_id: picker.assistant_id,
                    draft,
                },
                generation: picker.generation,
                cancel: cancel.clone(),
            };
            if self.send.try_send(job).is_err() {
                output.push(failure().with_error("头像解码队列已满，请重试".into()));
                continue;
            }
            self.selected = Some(selected);
            self.active_cancel = Some(cancel);
        }
        for _ in 0..20 {
            let completion = match self.receive.try_recv() {
                Ok(value) => value,
                Err(TryRecvError::Empty | TryRecvError::Disconnected) => break,
            };
            match completion.done {
                Done::Maintain { result } => {
                    self.maintenance_pending = false;
                    self.maintenance_after = Instant::now()
                        + Duration::from_secs(if result.is_ok() { 1800 } else { 300 });
                }
                Done::Cleanup { operation, result } => {
                    if let Err(error) = result {
                        output.push(Update::Failed { operation, error });
                    }
                }
                Done::Read {
                    request_id,
                    path,
                    result,
                } => {
                    if !self
                        .reads
                        .get(&path)
                        .is_some_and(|p| p.request_id == request_id)
                    {
                        continue;
                    }
                    self.reads.remove(&path);
                    output.push(Update::Read {
                        request_id,
                        path,
                        result: if completion.generation == generation {
                            result
                        } else {
                            Err("头像来源代已切换".into())
                        },
                    });
                }
                Done::Decode {
                    operation,
                    assistant_id,
                    result,
                } => {
                    if !self.selected.as_ref().is_some_and(|p| {
                        p.operation == operation
                            && p.generation == generation
                            && completion.generation == generation
                    }) {
                        continue;
                    }
                    self.active_cancel = None;
                    match result {
                        Ok(pixels) => {
                            let selection_id = crate::controller::unique_id();
                            if let Some(selected) = self.selected.as_mut() {
                                selected.selection_id = selection_id.clone();
                                selected.pixels = pixels.clone();
                            }
                            output.push(Update::Selected {
                                operation,
                                assistant_id,
                                selection_id,
                                pixels,
                            });
                        }
                        Err(error) => {
                            self.selected = None;
                            output.push(Update::Failed { operation, error });
                        }
                    }
                }
                Done::Save {
                    operation,
                    assistant_id,
                    result,
                } => {
                    if !self.selected.as_ref().is_some_and(|p| {
                        p.operation == operation
                            && p.generation == generation
                            && completion.generation == generation
                    }) {
                        continue;
                    }
                    self.active_cancel = None;
                    match result {
                        Ok((path, pixels)) => {
                            self.selected = None;
                            output.push(Update::Saved {
                                operation,
                                assistant_id,
                                path,
                                pixels,
                            });
                        }
                        Err(error) => {
                            if let Some(selected) = self.selected.as_mut() {
                                selected.saving = false;
                            }
                            output.push(Update::Failed { operation, error });
                        }
                    }
                }
            }
        }
        if !self.maintenance_pending && Instant::now() >= self.maintenance_after {
            let refs = store
                .settings
                .assistants
                .iter()
                .map(|assistant| assistant.avatar_path.as_str());
            if retain(&store.avatar_namespace(), refs).is_ok() {
                let mut active = std::collections::HashSet::new();
                if let Some(p) = &self.picker {
                    active.insert(p.operation.id.clone());
                }
                if let Some(p) = &self.selected {
                    active.insert(p.operation.id.clone());
                }
                active.extend(
                    self.retired
                        .iter()
                        .map(|(operation, _)| operation.id.clone()),
                );
                let job = Job {
                    work: Work::Maintain {
                        namespace: store.avatar_namespace(),
                        root: store.avatar_document_root(),
                        active,
                    },
                    generation: generation.clone(),
                    cancel: Arc::new(CancelToken::new()),
                };
                if self.send.try_send(job).is_ok() {
                    self.maintenance_pending = true;
                }
            }
            if !self.maintenance_pending {
                self.maintenance_after = Instant::now() + Duration::from_secs(300);
            }
        }
        output
    }
}
impl Drop for Runtime {
    fn drop(&mut self) {
        if let Some(p) = self.picker.take() {
            attachments::cancel(&p.operation.id);
        }
        if let Some(cancel) = self.active_cancel.take() {
            cancel.cancel();
        }
        for p in self.reads.values() {
            p.cancel.cancel();
        }
    }
}
fn check_pixels(pixels: &PixelFrame) -> Result<(), String> {
    if pixels.width == 0
        || pixels.height == 0
        || pixels.width > MAX_SIDE
        || pixels.height > MAX_SIDE
        || pixels.rgba.len() != pixels.width as usize * pixels.height as usize * 4
    {
        Err("头像像素尺寸或内存预算无效".into())
    } else {
        Ok(())
    }
}
fn validate_crop(pixels: &PixelFrame, crop: Crop) -> Result<(), String> {
    check_pixels(pixels)?;
    if crop.side == 0
        || crop
            .left
            .checked_add(crop.side)
            .is_none_or(|right| right > pixels.width)
        || crop
            .top
            .checked_add(crop.side)
            .is_none_or(|bottom| bottom > pixels.height)
    {
        Err("头像裁剪必须完全位于图片内".into())
    } else {
        Ok(())
    }
}
fn same(a: &Metadata, b: &Metadata) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        a.dev() == b.dev()
            && a.ino() == b.ino()
            && a.size() == b.size()
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
fn directory(root: &Path, name: &str) -> Result<PathBuf, String> {
    let metadata = fs::symlink_metadata(root).map_err(|_| "头像存储根目录缺失")?;
    if !metadata.is_dir()
        || metadata.file_type().is_symlink()
        || fs::canonicalize(root).map_err(|_| "头像根目录解析失败")? != root
    {
        return Err("头像存储根目录身份无效".into());
    }
    let target = root.join(name);
    match fs::create_dir(&target) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
        Err(_) => return Err("头像目录创建失败".into()),
    }
    let metadata = fs::symlink_metadata(&target).map_err(|_| "头像目录读取失败")?;
    if !metadata.is_dir()
        || metadata.file_type().is_symlink()
        || fs::canonicalize(&target).map_err(|_| "头像目录解析失败")? != target
    {
        return Err("头像目录包含链接或发生替换".into());
    }
    Ok(target)
}
fn native_read(namespace: &Path, path: &str) -> Result<Vec<u8>, String> {
    validate_path(path)?;
    let parent = namespace.join("assistants");
    let metadata = fs::symlink_metadata(&parent).map_err(|_| "助手图片目录不存在")?;
    if !metadata.is_dir()
        || metadata.file_type().is_symlink()
        || fs::canonicalize(&parent).map_err(|_| "头像目录解析失败")? != parent
    {
        return Err("头像目录不是原普通目录".into());
    }
    let path = namespace.join(path);
    let named = fs::symlink_metadata(&path).map_err(|_| "助手图片文件不存在")?;
    if !named.is_file() || named.file_type().is_symlink() || named.len() > AVATAR_BYTES as u64 {
        return Err("助手图片类型或长度无效".into());
    }
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK);
    }
    let file = options.open(&path).map_err(|_| "助手图片无法打开")?;
    let opened = file.metadata().map_err(|_| "助手图片状态无法读取")?;
    if !same(&named, &opened) {
        return Err("头像在打开期间变化".into());
    }
    let mut bytes = Vec::new();
    (&file)
        .take(AVATAR_BYTES as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "助手图片读取失败")?;
    if bytes.len() as u64 != opened.len()
        || bytes.len() > AVATAR_BYTES
        || !same(&opened, &file.metadata().map_err(|_| "头像读取复核失败")?)
        || !same(
            &opened,
            &fs::symlink_metadata(&path).map_err(|_| "头像文件在读取期间消失")?,
        )
    {
        return Err("头像文件在读取期间变化".into());
    }
    Ok(bytes)
}
fn base64(bytes: &[u8]) -> String {
    const TABLE: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let a = chunk[0];
        let b = chunk.get(1).copied().unwrap_or(0);
        let c = chunk.get(2).copied().unwrap_or(0);
        out.push(TABLE[(a >> 2) as usize] as char);
        out.push(TABLE[(((a & 3) << 4) | (b >> 4)) as usize] as char);
        out.push(if chunk.len() > 1 {
            TABLE[(((b & 15) << 2) | (c >> 6)) as usize] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            TABLE[(c & 63) as usize] as char
        } else {
            '='
        });
    }
    out
}
fn load(namespace: &Path, path: &str, cancel: &CancelToken) -> Result<PixelFrame, String> {
    validate_path(path)?;
    if cancel.is_cancelled() {
        return Err("头像读取已取消".into());
    }
    let native = namespace.join(path);
    let bytes = match fs::symlink_metadata(&native) {
        Ok(_) => native_read(namespace, path)?,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            let catalog = crate::legacy_migration::archive_catalog(namespace)?
                .ok_or("没有可读取的旧头像归档")?;
            catalog.recheck()?;
            let mappings = catalog
                .assets
                .iter()
                .filter(|mapping| mapping.relative_path == path)
                .collect::<Vec<_>>();
            if mappings.len() != 1 {
                return Err("旧头像归档来源不存在或有歧义".into());
            }
            let mapping = mappings[0];
            if mapping.bytes > AVATAR_BYTES as u64 {
                return Err("旧头像原文件超过2 MiB预算".into());
            }
            if cancel.is_cancelled() {
                return Err("旧头像读取已取消".into());
            }
            let bytes = crate::legacy_migration::read_archived_blob_bounded(
                &catalog.generation_root,
                mapping,
                &crate::platform::secrets::SecretBox,
                AVATAR_BYTES as u64,
            )?;
            catalog.recheck()?;
            bytes
        }
        Err(_) => return Err("头像路径状态无法读取".into()),
    };
    if cancel.is_cancelled() {
        return Err("头像读取已取消".into());
    }
    let pixels = attachments::decode_base64_preview(&base64(&bytes), 256)?;
    check_pixels(&pixels)?;
    Ok(pixels)
}
fn save(
    namespace: &Path,
    assistant_id: &str,
    pixels: &PixelFrame,
    crop: Crop,
    cancel: &CancelToken,
) -> Result<(String, Arc<PixelFrame>), String> {
    validate_crop(pixels, crop)?;
    if !id_valid(assistant_id) {
        return Err("助手头像身份无效".into());
    }
    let side = crop.side.min(256);
    let mut rgba = vec![0u8; side as usize * side as usize * 4];
    for y in 0..side {
        if cancel.is_cancelled() {
            return Err("头像保存已取消，原头像保留".into());
        }
        for x in 0..side {
            let sx = crop.left as f64
                + ((x as f64 + 0.5) * crop.side as f64 / side as f64 - 0.5).max(0.0);
            let sy = crop.top as f64
                + ((y as f64 + 0.5) * crop.side as f64 / side as f64 - 0.5).max(0.0);
            let x0 = (sx.floor() as u32).min(crop.left + crop.side - 1);
            let y0 = (sy.floor() as u32).min(crop.top + crop.side - 1);
            let x1 = (x0 + 1).min(crop.left + crop.side - 1);
            let y1 = (y0 + 1).min(crop.top + crop.side - 1);
            let dx = (sx - x0 as f64).clamp(0.0, 1.0);
            let dy = (sy - y0 as f64).clamp(0.0, 1.0);
            let samples = [
                (x0, y0, (1.0 - dx) * (1.0 - dy)),
                (x1, y0, dx * (1.0 - dy)),
                (x0, y1, (1.0 - dx) * dy),
                (x1, y1, dx * dy),
            ];
            let pixel = |x: u32, y: u32, channel: usize| {
                pixels.rgba[(y as usize * pixels.width as usize + x as usize) * 4 + channel] as f64
            };
            let alpha = samples
                .iter()
                .map(|&(x, y, weight)| pixel(x, y, 3) * weight)
                .sum::<f64>();
            for channel in 0..3 {
                let value = if alpha > 0.0 {
                    samples
                        .iter()
                        .map(|&(x, y, weight)| pixel(x, y, channel) * pixel(x, y, 3) * weight)
                        .sum::<f64>()
                        / alpha
                } else {
                    0.0
                };
                rgba[(y as usize * side as usize + x as usize) * 4 + channel] =
                    value.round().clamp(0.0, 255.0) as u8;
            }
            rgba[(y as usize * side as usize + x as usize) * 4 + 3] =
                alpha.round().clamp(0.0, 255.0) as u8;
        }
    }
    let mut bytes = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut bytes, side, side);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        let mut writer = encoder.write_header().map_err(|_| "头像PNG编码失败")?;
        writer
            .write_image_data(&rgba)
            .map_err(|_| "头像PNG像素编码失败")?;
    }
    if bytes.len() > AVATAR_BYTES {
        return Err("裁剪后的头像超过存储预算".into());
    }
    if cancel.is_cancelled() {
        return Err("头像保存已取消，原头像保留".into());
    }
    let directory = directory(namespace, "assistants")?;
    let relative = format!(
        "assistants/{assistant_id}-{}.png",
        crate::controller::unique_id()
    );
    gc::admit_save(namespace, &relative, bytes.len())?;
    let path = namespace.join(&relative);
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK);
    }
    let mut file = options
        .open(&path)
        .map_err(|_| "头像新文件无法创建，原头像保留")?;
    file.write_all(&bytes)
        .map_err(|_| "头像写入失败，原头像保留")?;
    file.sync_all()
        .map_err(|_| "头像文件同步失败，原头像保留")?;
    drop(file);
    let verified = native_read(namespace, &relative)?;
    if verified != bytes {
        return Err("头像新文件读回不一致，原头像保留".into());
    }
    #[cfg(unix)]
    {
        std::fs::File::open(&directory)
            .and_then(|dir| dir.sync_all())
            .map_err(|_| "头像文件已写入，目录同步尚未确认；原头像配置保留")?;
    }
    #[cfg(not(unix))]
    let _ = directory;
    // Once all file commits are verified, a late cancellation cannot reinterpret
    // the already-created file as not written. Settings remain a separate CAS.
    Ok((
        relative,
        Arc::new(PixelFrame {
            width: side,
            height: side,
            rgba,
        }),
    ))
}

fn cleanup_staging(staging: &Path) -> Result<(), String> {
    let inbox = staging.parent().ok_or("头像临时目录缺少拥有者")?;
    if inbox.file_name() != Some(std::ffi::OsStr::new("avatar-inbox"))
        || !staging
            .file_name()
            .and_then(|name| name.to_str())
            .is_some_and(id_valid)
    {
        return Err("头像临时目录身份无效，未清理".into());
    }
    let root = fs::canonicalize(inbox).map_err(|_| "头像临时根目录解析失败")?;
    if root != inbox
        || fs::symlink_metadata(inbox)
            .map_err(|_| "头像临时根目录状态缺失")?
            .file_type()
            .is_symlink()
    {
        return Err("头像临时目录拥有者已变化，未清理".into());
    }
    match fs::symlink_metadata(staging) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(_) => return Err("头像临时目录无法读取".into()),
        Ok(metadata) => {
            if !metadata.is_dir()
                || metadata.file_type().is_symlink()
                || fs::canonicalize(staging).map_err(|_| "头像临时目录解析失败")? != staging
            {
                return Err("头像临时目录包含链接，未清理".into());
            }
        }
    }
    let child = staging.join("inbox");
    match fs::symlink_metadata(&child) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(_) => return Err("头像临时内容目录无法读取".into()),
        Ok(metadata) => {
            if !metadata.is_dir()
                || metadata.file_type().is_symlink()
                || fs::canonicalize(&child).map_err(|_| "头像临时内容目录解析失败")? != child
            {
                return Err("头像临时内容目录被替换，未清理".into());
            }
            let mut paths = Vec::new();
            for entry in fs::read_dir(&child).map_err(|_| "头像临时内容目录读取失败")? {
                let entry = entry.map_err(|_| "头像临时文件列表读取失败")?;
                let metadata =
                    fs::symlink_metadata(entry.path()).map_err(|_| "头像临时文件状态读取失败")?;
                if paths.len() >= 16 || !metadata.is_file() || metadata.file_type().is_symlink() {
                    return Err("头像临时内容超出拥有者预算，未清理".into());
                }
                paths.push(entry.path());
            }
            for path in paths {
                if fs::canonicalize(&child).map_err(|_| "头像临时拥有者复核失败")? != child
                {
                    return Err("头像临时拥有者变化，停止清理".into());
                }
                fs::remove_file(&path).map_err(|_| "头像临时文件清理未确认")?;
            }
            fs::remove_dir(&child).map_err(|_| "头像临时内容目录清理未确认")?;
        }
    }
    fs::remove_dir(staging).map_err(|_| "头像临时操作目录清理未确认".into())
}
