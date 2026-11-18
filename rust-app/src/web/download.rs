//! Frozen App UID download targets and streamed atomic same-directory commits.
use crate::network::CancelToken;
use serde_json::{Value, json};
use std::{
    path::{Component, Path, PathBuf},
    sync::Arc,
};
pub(super) const MAX_BYTES: u64 = 256 * 1024 * 1024;

/// Shared with ordinary FileTools: map only fixed Android primary-storage aliases
/// through the real framework primary directory. User-created symlinks are not followed.
pub(crate) fn resolve_user_path(workspace: &Path, raw: &str) -> Result<PathBuf, String> {
    let raw = raw.trim();
    if raw.len() > 4096 || raw.contains('\0') || !workspace.is_absolute() {
        return Err("下载路径为空或无效".into());
    }
    let requested = Path::new(raw);
    if !requested.is_absolute()
        && requested
            .components()
            .any(|part| matches!(part, Component::ParentDir))
    {
        return Err("相对路径不能包含..；其他目录请使用绝对路径".into());
    }
    let alias = crate::store::workspace::alias_for_workspace(workspace, requested)?;
    let path = if let Some(alias) = alias {
        workspace.join(alias)
    } else if requested.is_absolute() {
        requested.to_owned()
    } else if raw == "~" || raw.is_empty() {
        workspace.to_owned()
    } else {
        workspace.join(raw.strip_prefix("~/").unwrap_or(raw))
    };
    let mut normalized = PathBuf::from("/");
    for part in path.components() {
        match part {
            Component::RootDir | Component::CurDir => (),
            Component::Normal(part) => normalized.push(part),
            Component::ParentDir => {
                normalized.pop();
            }
            _ => return Err("下载路径不是当前平台的绝对路径".into()),
        }
    }
    #[cfg(target_os = "android")]
    {
        for alias in [
            "/sdcard",
            "/mnt/sdcard",
            "/storage/self/primary",
            "/storage/emulated/legacy",
        ] {
            if let Ok(relative) = normalized.strip_prefix(alias) {
                let primary = primary_external_directory()?;
                normalized = primary.join(relative);
                break;
            }
        }
    }
    Ok(normalized)
}
#[cfg(target_os = "android")]
fn primary_external_directory() -> Result<PathBuf, String> {
    use jni::{jni_sig, jni_str};
    let path = crate::platform::android::invoke(|env| {
        let directory = env
            .call_static_method(
                jni_str!("android/os/Environment"),
                jni_str!("getExternalStorageDirectory"),
                jni_sig!("()Ljava/io/File;"),
                &[],
            )?
            .l()?;
        let value = env
            .call_method(
                &directory,
                jni_str!("getCanonicalPath"),
                jni_sig!("()Ljava/lang/String;"),
                &[],
            )?
            .l()?;
        crate::platform::android::string_value(env, value, 4096)
    })?;
    let path = PathBuf::from(path);
    if !path.is_absolute() {
        return Err("Android主存储真实路径无效".into());
    }
    Ok(path)
}

#[derive(Clone)]
pub(super) struct Target {
    pub path: PathBuf,
    #[cfg(unix)]
    frozen: Arc<unix::Frozen>,
}
impl Target {
    /// Preparation is read-only, including the exact existing destination hash.
    pub(super) fn prepare(
        workspace: &Path,
        raw: &str,
        protected: &[PathBuf],
        cancel: &CancelToken,
    ) -> Result<Self, String> {
        if raw.trim().is_empty() {
            return Err("下载路径不能为空".into());
        }
        let path = resolve_user_path(workspace, raw)?;
        if protected.iter().any(|root| {
            path.starts_with(root)
                && !(workspace.starts_with(root)
                    && workspace != root.as_path()
                    && path.starts_with(workspace))
        }) {
            return Err("下载不能覆盖应用内部状态路径".into());
        }
        #[cfg(target_os = "android")]
        {
            let paths = crate::platform::android::paths().ok_or("Android路径未初始化")?;
            let directories = ["chats", "datastore", "jobs", "plugins"];
            let files = [
                "todos.json",
                "schedules.json",
                "mcp.json",
                "memory.json",
                "profile.md",
                "profile.state.json",
                "usage.json",
                "perf.json",
            ];
            if directories
                .iter()
                .any(|name| path.starts_with(paths.files_dir.join(name)))
                || files.iter().any(|name| path == paths.files_dir.join(name))
                || paths.files_dir.parent().is_some_and(|data| {
                    ["databases", "shared_prefs"]
                        .iter()
                        .any(|name| path.starts_with(data.join(name)))
                })
            {
                return Err("下载不能覆盖原应用内部状态文件".into());
            }
        }
        #[cfg(unix)]
        {
            let frozen = Arc::new(unix::freeze(&path, cancel)?);
            Ok(Self { path, frozen })
        }
        #[cfg(not(unix))]
        {
            let _ = (path, cancel);
            Err("当前平台未接入Unix流式下载提交".into())
        }
    }
    #[cfg(unix)]
    pub(super) fn begin(&self, cancel: &CancelToken) -> Result<unix::Active, String> {
        unix::begin(&self.path, &self.frozen, cancel)
    }
}

#[cfg(unix)]
pub(super) mod unix {
    use super::*;
    use crate::chat::{FileActivity, FileActivityKind};
    use sha2::{Digest, Sha256};
    use std::{
        ffi::{CStr, CString},
        fs::{File, Metadata},
        io::{Read, Seek, SeekFrom, Write},
        os::{
            fd::{AsRawFd, FromRawFd},
            unix::{ffi::OsStrExt, fs::MetadataExt},
        },
        sync::{
            Mutex,
            atomic::{AtomicU64, Ordering},
        },
    };
    fn error(error: std::io::Error) -> String {
        format!("下载文件访问失败：{error}")
    }
    fn check(cancel: &CancelToken) -> Result<(), String> {
        if cancel.is_cancelled() {
            Err("下载已取消，完整目标未提交".into())
        } else {
            Ok(())
        }
    }
    fn owned(fd: i32) -> Result<File, String> {
        if fd < 0 {
            Err(error(std::io::Error::last_os_error()))
        } else {
            Ok(unsafe { File::from_raw_fd(fd) })
        }
    }
    fn name(value: &std::ffi::OsStr) -> Result<CString, String> {
        CString::new(value.as_bytes()).map_err(|_| "下载文件名包含NUL".into())
    }
    fn open_dir(parent: &File, name: &CStr) -> Result<File, String> {
        owned(unsafe {
            libc::openat(
                parent.as_raw_fd(),
                name.as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
            )
        })
    }
    fn root() -> Result<File, String> {
        owned(unsafe {
            libc::open(
                c"/".as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
            )
        })
    }
    fn open_path(path: &Path, cancel: &CancelToken) -> Result<File, String> {
        let mut directory = root()?;
        for part in path.components() {
            check(cancel)?;
            match part {
                Component::RootDir => (),
                Component::Normal(part) => directory = open_dir(&directory, &name(part)?)?,
                _ => return Err("下载父路径未正规化".into()),
            }
        }
        Ok(directory)
    }
    fn identity(a: &Metadata, b: &Metadata) -> bool {
        a.dev() == b.dev()
            && a.ino() == b.ino()
            && a.is_dir() == b.is_dir()
            && a.is_file() == b.is_file()
    }
    #[derive(Clone, Debug, PartialEq, Eq)]
    struct Stamp {
        device: u64,
        inode: u64,
        length: u64,
        mtime: i64,
        mtime_ns: i64,
        ctime: i64,
        ctime_ns: i64,
        mode: u32,
        uid: u32,
        gid: u32,
        hash: [u8; 32],
    }
    impl Stamp {
        fn new(metadata: &Metadata, hash: [u8; 32]) -> Self {
            Self {
                device: metadata.dev(),
                inode: metadata.ino(),
                length: metadata.len(),
                mtime: metadata.mtime(),
                mtime_ns: metadata.mtime_nsec(),
                ctime: metadata.ctime(),
                ctime_ns: metadata.ctime_nsec(),
                mode: metadata.mode(),
                uid: metadata.uid(),
                gid: metadata.gid(),
                hash,
            }
        }
    }
    fn fingerprint(file: &mut File, cancel: &CancelToken) -> Result<Stamp, String> {
        check(cancel)?;
        let before = file.metadata().map_err(error)?;
        if !before.is_file() || before.len() > MAX_BYTES {
            return Err("下载目标不是普通文件或超过256MiB比较预算".into());
        }
        file.seek(SeekFrom::Start(0)).map_err(error)?;
        let mut hash = Sha256::new();
        let mut read = 0u64;
        let mut buffer = [0u8; 16 * 1024];
        loop {
            check(cancel)?;
            let count = file.read(&mut buffer).map_err(error)?;
            if count == 0 {
                break;
            }
            read = read.checked_add(count as u64).ok_or("文件长度溢出")?;
            if read > MAX_BYTES {
                return Err("下载目标读取期间超过256MiB预算".into());
            }
            hash.update(&buffer[..count]);
        }
        let hash: [u8; 32] = hash.finalize().into();
        let after = file.metadata().map_err(error)?;
        if read != before.len() || Stamp::new(&before, hash) != Stamp::new(&after, hash) {
            return Err("文件在指纹读取期间变化，拒绝覆盖".into());
        }
        Ok(Stamp::new(&after, hash))
    }
    fn target_snapshot(
        parent: &File,
        target: &CStr,
        cancel: &CancelToken,
    ) -> Result<Option<Stamp>, String> {
        let fd = unsafe {
            libc::openat(
                parent.as_raw_fd(),
                target.as_ptr(),
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK,
            )
        };
        if fd < 0 && std::io::Error::last_os_error().kind() == std::io::ErrorKind::NotFound {
            return Ok(None);
        }
        let mut file = owned(fd)?;
        let snapshot = fingerprint(&mut file, cancel)?;
        let named = unsafe {
            libc::openat(
                parent.as_raw_fd(),
                target.as_ptr(),
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK,
            )
        };
        let named = owned(named)?.metadata().map_err(error)?;
        if !named.is_file() || Stamp::new(&named, snapshot.hash) != snapshot {
            return Err("下载目标路径读取期间被替换".into());
        }
        Ok(Some(snapshot))
    }
    pub(super) struct Frozen {
        ancestor: File,
        ancestor_path: PathBuf,
        ancestor_metadata: Metadata,
        missing: Vec<CString>,
        source: Option<Stamp>,
    }
    pub(super) fn freeze(path: &Path, cancel: &CancelToken) -> Result<Frozen, String> {
        let parent = path.parent().ok_or("下载目标没有父目录")?;
        let target = name(path.file_name().ok_or("下载路径不是文件名")?)?;
        let mut ancestor = root()?;
        let mut ancestor_path = PathBuf::from("/");
        let mut missing = Vec::new();
        for part in parent.components() {
            check(cancel)?;
            match part {
                Component::RootDir => (),
                Component::Normal(part) => {
                    let part_name = name(part)?;
                    if !missing.is_empty() {
                        missing.push(part_name);
                        continue;
                    }
                    let fd = unsafe {
                        libc::openat(
                            ancestor.as_raw_fd(),
                            part_name.as_ptr(),
                            libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
                        )
                    };
                    if fd < 0 {
                        let cause = std::io::Error::last_os_error();
                        if cause.kind() == std::io::ErrorKind::NotFound {
                            missing.push(part_name);
                        } else {
                            return Err(error(cause));
                        }
                    } else {
                        ancestor = owned(fd)?;
                        ancestor_path.push(part);
                    }
                }
                _ => return Err("下载父目录路径无效".into()),
            }
        }
        let source = if missing.is_empty() {
            target_snapshot(&ancestor, &target, cancel)?
        } else {
            None
        };
        let ancestor_metadata = ancestor.metadata().map_err(error)?;
        Ok(Frozen {
            ancestor,
            ancestor_path,
            ancestor_metadata,
            missing,
            source,
        })
    }
    pub(crate) struct Active {
        path: PathBuf,
        parent: File,
        target: CString,
        frozen: Arc<Frozen>,
        part_name: Option<CString>,
        part: Option<File>,
        hash: Sha256,
        written: u64,
        committed: bool,
    }
    pub(super) fn begin(
        path: &Path,
        frozen: &Arc<Frozen>,
        cancel: &CancelToken,
    ) -> Result<Active, String> {
        check(cancel)?;
        let named = open_path(&frozen.ancestor_path, cancel)?
            .metadata()
            .map_err(error)?;
        if !identity(&frozen.ancestor_metadata, &named) {
            return Err("审批后的下载父目录身份变化".into());
        }
        let mut parent = frozen.ancestor.try_clone().map_err(error)?;
        for part in &frozen.missing {
            check(cancel)?;
            let created = unsafe { libc::mkdirat(parent.as_raw_fd(), part.as_ptr(), 0o700) };
            if created != 0 && std::io::Error::last_os_error().raw_os_error() != Some(libc::EEXIST)
            {
                return Err(error(std::io::Error::last_os_error()));
            }
            parent = open_dir(&parent, part)?;
        }
        let target = name(path.file_name().ok_or("下载目标不是文件名")?)?;
        Ok(Active {
            path: path.to_owned(),
            parent,
            target,
            frozen: frozen.clone(),
            part_name: None,
            part: None,
            hash: Sha256::new(),
            written: 0,
            committed: false,
        })
    }
    impl Drop for Active {
        fn drop(&mut self) {
            if !self.committed {
                if let (Some(name), Some(part)) = (&self.part_name, &self.part) {
                    let mut named = std::mem::MaybeUninit::<libc::stat>::uninit();
                    if unsafe {
                        libc::fstatat(
                            self.parent.as_raw_fd(),
                            name.as_ptr(),
                            named.as_mut_ptr(),
                            libc::AT_SYMLINK_NOFOLLOW,
                        )
                    } == 0
                    {
                        let named = unsafe { named.assume_init() };
                        if part.metadata().is_ok_and(|held| {
                            held.dev() == named.st_dev as u64 && held.ino() == named.st_ino as u64
                        }) {
                            unsafe {
                                libc::unlinkat(self.parent.as_raw_fd(), name.as_ptr(), 0);
                            }
                        }
                    }
                }
            }
        }
    }
    impl Active {
        pub(crate) fn writer(&self) -> Result<Arc<Mutex<()>>, String> {
            // Parent exports this one registry; ordinary write_file/edit_file and
            // downloads must share exactly the same dev/inode/name exclusion.
            crate::tools::download_write_lock(&self.parent, &self.target)
        }
        fn check_parent(&self, cancel: &CancelToken) -> Result<(), String> {
            let named = open_path(self.path.parent().ok_or("下载父目录缺失")?, cancel)?
                .metadata()
                .map_err(error)?;
            let held = self.parent.metadata().map_err(error)?;
            if !identity(&held, &named) {
                return Err("下载父目录路径已移动或被替换".into());
            }
            Ok(())
        }
        fn check_source(&self, cancel: &CancelToken) -> Result<(), String> {
            self.check_parent(cancel)?;
            let current = target_snapshot(&self.parent, &self.target, cancel)?;
            if current != self.frozen.source {
                return Err("目标内容、长度、mtime或inode已变化，未覆盖".into());
            }
            Ok(())
        }
        pub(crate) fn start(
            &mut self,
            operation_id: &str,
            cancel: &CancelToken,
        ) -> Result<(), String> {
            self.check_source(cancel)?;
            static NEXT: AtomicU64 = AtomicU64::new(1);
            let next = NEXT
                .fetch_update(Ordering::AcqRel, Ordering::Acquire, |value| {
                    value.checked_add(1)
                })
                .map_err(|_| "下载暂存代次耗尽")?;
            let id = format!("{:x}", Sha256::digest(operation_id.as_bytes()));
            let part = CString::new(format!(
                ".jagent-download-{}-{}-{next}.part",
                std::process::id(),
                &id[..16]
            ))
            .map_err(|_| "下载暂存名无效")?;
            let file = owned(unsafe {
                libc::openat(
                    self.parent.as_raw_fd(),
                    part.as_ptr(),
                    libc::O_RDWR
                        | libc::O_CREAT
                        | libc::O_EXCL
                        | libc::O_NOFOLLOW
                        | libc::O_CLOEXEC,
                    0o600,
                )
            })?;
            self.part_name = Some(part);
            self.part = Some(file);
            Ok(())
        }
        pub(crate) fn write(&mut self, bytes: &[u8], cancel: &CancelToken) -> Result<(), String> {
            check(cancel)?;
            if bytes.len() as u64 > MAX_BYTES.saturating_sub(self.written) {
                return Err("下载超过256MiB上限，未提交目标".into());
            }
            self.part
                .as_mut()
                .ok_or("下载暂存未创建")?
                .write_all(bytes)
                .map_err(error)?;
            self.hash.update(bytes);
            self.written += bytes.len() as u64;
            Ok(())
        }
        pub(crate) fn commit(&mut self, cancel: &CancelToken) -> Result<Value, String> {
            check(cancel)?;
            let expected: [u8; 32] = self.hash.clone().finalize().into();
            let part = self.part.as_mut().ok_or("下载暂存文件缺失")?;
            if let Some(source) = &self.frozen.source {
                if unsafe { libc::fchmod(part.as_raw_fd(), (source.mode & 0o777) as libc::mode_t) }
                    != 0
                {
                    return Err(error(std::io::Error::last_os_error()));
                }
            }
            part.sync_all().map_err(error)?;
            let actual = fingerprint(part, cancel)?;
            if actual.length != self.written || actual.hash != expected {
                return Err("下载暂存长度或hash复核失败，未提交".into());
            }
            self.check_source(cancel)?;
            let part_name = self.part_name.as_ref().ok_or("下载暂存名称缺失")?;
            // Confirm our temp pathname still points to our descriptor before rename.
            let named =
                target_snapshot(&self.parent, part_name, cancel)?.ok_or("下载暂存路径已移动")?;
            if named != actual {
                return Err("下载暂存路径身份变化，未提交".into());
            }
            check(cancel)?;
            if unsafe {
                libc::renameat(
                    self.parent.as_raw_fd(),
                    part_name.as_ptr(),
                    self.parent.as_raw_fd(),
                    self.target.as_ptr(),
                )
            } != 0
            {
                return Err(error(std::io::Error::last_os_error()));
            }
            self.committed = true;
            // Never mask the actual committed effect with a later cancellation.
            let durability_error = self.parent.sync_all().err().map(error);
            let after = target_snapshot(&self.parent, &self.target, &CancelToken::new());
            let verified = matches!(&after,Ok(Some(stamp)) if stamp.device==actual.device&&stamp.inode==actual.inode&&stamp.length==self.written&&stamp.hash==expected);
            let parent_confirmed = self.check_parent(&CancelToken::new()).is_ok();
            let verified = verified && parent_confirmed;
            let activity = FileActivity {
                path: self.path.to_string_lossy().into_owned(),
                kind: FileActivityKind::Downloaded,
                added_lines: None,
                removed_lines: None,
                size_bytes: Some(self.written as i64),
                description: if verified {
                    String::new()
                } else {
                    "下载已提交，但最终路径或读回内容未确认；请核对".into()
                },
                previous_path: None,
                extra: std::collections::BTreeMap::from([
                    ("committed".into(), json!(true)),
                    ("verified".into(), json!(verified)),
                ]),
            };
            let text = if verified {
                format!("Saved to {} ({} bytes)", self.path.display(), self.written)
            } else {
                format!(
                    "下载已提交，但最终目标文件尚未核对：{}",
                    self.path.display()
                )
            };
            Ok(
                json!({"ok":verified&&durability_error.is_none(),"committed":true,"verified":verified,"path":self.path,"bytes":self.written,"text":text,"revision":format!("{:x}",self.hash.clone().finalize()),"durability_error":durability_error,"file_activities":[activity],"error":if verified{None::<String>}else{Some("下载已提交，但最终文件未核对一致；勿自动重试覆盖".to_owned())}}),
            )
        }
    }
}
