//! Immutable snapshots back the outbound provider and document-save requests.
use crate::network::CancelToken;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::File,
    path::{Path, PathBuf},
};
const MAX_FILE: u64 = 256 * 1024 * 1024;
const MAX_CONVERSATION: u64 = 520 * 1024 * 1024;
const MAX_SNAPSHOTS: usize = 16;
const MAX_CACHE: u64 = 1024 * 1024 * 1024;
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub(crate) struct Snapshot {
    pub version: u32,
    pub token: String,
    pub name: String,
    pub mime: String,
    pub bytes: u64,
    pub sha256: String,
    pub expires_at_ms: i64,
    pub identity: FileIdentity,
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub(crate) struct FileIdentity {
    pub device: u64,
    pub inode: u64,
    pub mtime: i64,
    pub mtime_ns: i64,
    pub ctime: i64,
    pub ctime_ns: i64,
}
#[cfg(unix)]
impl FileIdentity {
    fn from_metadata(metadata: &std::fs::Metadata) -> Self {
        use std::os::unix::fs::MetadataExt;
        Self {
            device: metadata.dev(),
            inode: metadata.ino(),
            mtime: metadata.mtime(),
            mtime_ns: metadata.mtime_nsec(),
            ctime: metadata.ctime(),
            ctime_ns: metadata.ctime_nsec(),
        }
    }
}
impl Snapshot {
    pub(crate) fn validate(&self) -> Result<(), String> {
        self.validate_shape()?;
        if self.expires_at_ms <= now() {
            return Err("文件分享快照已过期".into());
        }
        Ok(())
    }
    fn validate_shape(&self) -> Result<(), String> {
        if self.version != 1
            || self.token.len() != 64
            || !self.token.bytes().all(|b| b.is_ascii_hexdigit())
            || self.sha256.len() != 64
            || !self.sha256.bytes().all(|b| b.is_ascii_hexdigit())
            || self.name.is_empty()
            || self.name.len() > 1024
            || self.name.contains(['\0', '/', '\\'])
            || self.mime.len() > 128
            || self.mime.contains(['\0', '\n', '\r'])
            || self.bytes > MAX_CONVERSATION
        {
            return Err("文件分享快照无效或已过期".into());
        }
        Ok(())
    }
}
fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|v| v.as_millis().min(i64::MAX as u128) as i64)
        .unwrap_or(0)
}
fn mime(name: &str) -> String {
    match name
        .rsplit('.')
        .next()
        .unwrap_or("")
        .to_ascii_lowercase()
        .as_str()
    {
        "txt" | "log" => "text/plain",
        "md" => "text/markdown",
        "csv" => "text/csv",
        "html" | "htm" => "text/html",
        "json" => "application/json",
        "pdf" => "application/pdf",
        "docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" => "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" => "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "mp3" => "audio/mpeg",
        "wav" => "audio/wav",
        "mp4" => "video/mp4",
        "zip" => "application/zip",
        "apk" => "application/vnd.android.package-archive",
        _ => "application/octet-stream",
    }
    .into()
}
/// Previously granted FileProvider URIs name files already in the original
/// delivery cache. Resolve every component through directory descriptors.
#[cfg(target_os = "android")]
pub(crate) fn legacy_delivery(cache: &Path, name: &str) -> Result<(String, u64, File), String> {
    use std::{
        ffi::CString,
        os::{
            fd::{AsRawFd, FromRawFd},
            unix::ffi::OsStrExt,
        },
        path::Component,
    };
    if !cache.is_absolute()
        || name.is_empty()
        || name.len() > 1024
        || name == "."
        || name == ".."
        || name.contains(['\0', '/', '\\'])
    {
        return Err("旧分享文件路径无效".into());
    }
    let owned = |fd| -> Result<File, String> {
        if fd < 0 {
            Err("旧分享文件已不存在或无法读取".into())
        } else {
            Ok(unsafe { File::from_raw_fd(fd) })
        }
    };
    let mut directory = owned(unsafe {
        libc::open(
            c"/".as_ptr(),
            libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC,
        )
    })?;
    for part in cache
        .components()
        .chain(Path::new("chat_delivery").components())
    {
        match part {
            Component::RootDir => {}
            Component::Normal(part) => {
                let part = CString::new(part.as_bytes()).map_err(|_| "旧分享路径含NUL")?;
                directory = owned(unsafe {
                    libc::openat(
                        directory.as_raw_fd(),
                        part.as_ptr(),
                        libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
                    )
                })?;
            }
            _ => return Err("旧分享缓存路径无效".into()),
        }
    }
    let filename = CString::new(name).map_err(|_| "旧分享文件名无效")?;
    let file = owned(unsafe {
        libc::openat(
            directory.as_raw_fd(),
            filename.as_ptr(),
            libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK,
        )
    })?;
    let metadata = file.metadata().map_err(|_| "旧分享文件状态无法读取")?;
    if !metadata.is_file() || metadata.len() > MAX_CONVERSATION {
        return Err("旧分享文件类型或大小无效".into());
    }
    Ok((mime(name), metadata.len(), file))
}
#[cfg(unix)]
mod unix {
    use super::*;
    use std::{
        ffi::{CStr, CString},
        io::{Read, Write},
        os::{
            fd::{AsRawFd, FromRawFd},
            unix::{ffi::OsStrExt, fs::MetadataExt},
        },
        path::Component,
        sync::{Mutex, OnceLock},
        time::{Duration, Instant},
    };
    static EXPORT_LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    fn error(error: std::io::Error) -> String {
        format!("文件快照失败：{error}")
    }
    fn check(cancel: &CancelToken) -> Result<(), String> {
        if cancel.is_cancelled() {
            Err("文件导出已取消".into())
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
    fn component(parent: &File, name: &CStr, create: bool) -> Result<File, String> {
        if create
            && unsafe { libc::mkdirat(parent.as_raw_fd(), name.as_ptr(), 0o700) } != 0
            && std::io::Error::last_os_error().kind() != std::io::ErrorKind::AlreadyExists
        {
            return Err(error(std::io::Error::last_os_error()));
        }
        owned(unsafe {
            libc::openat(
                parent.as_raw_fd(),
                name.as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
            )
        })
    }
    fn root(cache: &Path, create: bool) -> Result<File, String> {
        if !cache.is_absolute() {
            return Err("分享缓存路径必须为绝对路径".into());
        }
        let mut directory = owned(unsafe {
            libc::open(
                c"/".as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC,
            )
        })?;
        for part in cache.components() {
            match part {
                Component::RootDir => (),
                Component::Normal(part) => {
                    let part = CString::new(part.as_bytes()).map_err(|_| "缓存路径含NUL")?;
                    directory = component(&directory, &part, false)?;
                }
                _ => return Err("缓存路径无效".into()),
            }
        }
        component(&directory, c"rust-exports", create)
    }
    fn read_record(directory: &File) -> Result<Snapshot, String> {
        let mut file = owned(unsafe {
            libc::openat(
                directory.as_raw_fd(),
                c"receipt.json".as_ptr(),
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK,
            )
        })?;
        let meta = file.metadata().map_err(error)?;
        if !meta.is_file() || meta.len() > 8192 {
            return Err("分享快照记录超过预算".into());
        }
        let mut bytes = Vec::new();
        file.take(8193).read_to_end(&mut bytes).map_err(error)?;
        if bytes.len() > 8192 {
            return Err("分享记录在读取时增长".into());
        }
        serde_json::from_slice(&bytes).map_err(|_| "分享记录格式无效".into())
    }
    fn tokens(cache: &Path) -> Result<Vec<String>, String> {
        let dir = root(cache, true)?;
        // read_dir uses a descriptor pathname while the anchor is kept live.
        let path = PathBuf::from(format!("/proc/self/fd/{}", dir.as_raw_fd()));
        let mut tokens = Vec::new();
        for entry in std::fs::read_dir(path).map_err(error)? {
            let entry = entry.map_err(error)?;
            let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
                continue;
            };
            if name.len() == 64 && name.bytes().all(|b| b.is_ascii_hexdigit()) {
                tokens.push(name);
                if tokens.len() > MAX_SNAPSHOTS + 1 {
                    return Err("分享缓存记录超出16份预算".into());
                }
            }
        }
        Ok(tokens)
    }
    fn cleanup(cache: &Path) -> Result<(), String> {
        let root = root(cache, true)?;
        for token in tokens(cache)? {
            let name = CString::new(token).map_err(|_| "快照token无效")?;
            let directory = component(&root, &name, false)?;
            let owned = owned(unsafe {
                libc::openat(
                    directory.as_raw_fd(),
                    c"owned".as_ptr(),
                    libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK,
                )
            })?;
            if !owned.metadata().map_err(error)?.is_file() {
                return Err("导出缓存缺少普通文件所有权标记".into());
            }
            let mut marker = Vec::new();
            owned.take(65).read_to_end(&mut marker).map_err(error)?;
            if marker != name.to_bytes() {
                return Err("导出缓存所有权标记不匹配".into());
            }
            let record = read_record(&directory).ok();
            if record
                .as_ref()
                .is_some_and(|record| record.expires_at_ms > now())
            {
                continue;
            }
            // Delete only the two owned files; unexpected user files keep the folder.
            for file in [c"content", c"receipt.json", c"owned"] {
                if unsafe { libc::unlinkat(directory.as_raw_fd(), file.as_ptr(), 0) } != 0
                    && std::io::Error::last_os_error().kind() != std::io::ErrorKind::NotFound
                {
                    return Err(error(std::io::Error::last_os_error()));
                }
            }
            unsafe {
                libc::unlinkat(root.as_raw_fd(), name.as_ptr(), libc::AT_REMOVEDIR);
            }
        }
        root.sync_all().map_err(error)
    }
    pub(super) fn prepare(
        cache: &Path,
        workspace: &Path,
        source: &str,
        cancel: &CancelToken,
    ) -> Result<Snapshot, String> {
        prepare_input(cache, workspace, source, cancel, None)
    }
    pub(super) fn prepare_history(
        cache: &Path,
        history: &crate::conversation_export::Source,
        cancel: &CancelToken,
    ) -> Result<Snapshot, String> {
        prepare_input(cache, Path::new(""), "", cancel, Some(history))
    }
    fn prepare_input(
        cache: &Path,
        workspace: &Path,
        source: &str,
        cancel: &CancelToken,
        history: Option<&crate::conversation_export::Source>,
    ) -> Result<Snapshot, String> {
        let lock = EXPORT_LOCK.get_or_init(Default::default);
        let _guard = loop {
            check(cancel)?;
            match lock.try_lock() {
                Ok(guard) => break guard,
                Err(std::sync::TryLockError::Poisoned(e)) => break e.into_inner(),
                Err(std::sync::TryLockError::WouldBlock) => {
                    std::thread::sleep(Duration::from_millis(20))
                }
            }
        };
        cleanup(cache)?;
        let root = root(cache, true)?;
        let mut total = 0u64;
        let existing = tokens(cache)?;
        for token in &existing {
            let name = CString::new(token.as_bytes()).map_err(|_| "缓存token无效")?;
            let directory = component(&root, &name, false)?;
            total = total
                .checked_add(read_record(&directory)?.bytes)
                .ok_or("缓存长度溢出")?;
        }
        if existing.len() >= MAX_SNAPSHOTS {
            return Err("文件分享已有16份待过期快照，请稍后再导出".into());
        }
        let (name, mut source_file) = if let Some(history) = history {
            (history.filename(), None)
        } else {
            let (path, source) = crate::tools::file_tools::open_source(workspace, source, cancel)?;
            let before = source.metadata().map_err(error)?;
            if before.len() > MAX_FILE
                || total
                    .checked_add(before.len())
                    .is_none_or(|n| n > MAX_CACHE)
            {
                return Err("文件超过256 MiB或分享缓存超过1 GiB".into());
            }
            let name = path
                .file_name()
                .and_then(|name| name.to_str())
                .ok_or("文件名不是UTF8")?
                .chars()
                .take(240)
                .collect();
            (name, Some((source, before)))
        };
        let token = format!(
            "{:x}",
            Sha256::digest(
                format!("export:{}:{}", crate::controller::unique_id(), now()).as_bytes()
            )
        );
        let dirname = CString::new(token.as_bytes()).map_err(|_| "分享token无效")?;
        if unsafe { libc::mkdirat(root.as_raw_fd(), dirname.as_ptr(), 0o700) } != 0 {
            return Err(error(std::io::Error::last_os_error()));
        }
        let directory = component(&root, &dirname, false)?;
        struct Cleanup<'a> {
            root: &'a File,
            directory: &'a File,
            name: &'a CStr,
            armed: bool,
        }
        impl Drop for Cleanup<'_> {
            fn drop(&mut self) {
                if self.armed {
                    unsafe {
                        libc::unlinkat(self.directory.as_raw_fd(), c"content".as_ptr(), 0);
                        libc::unlinkat(self.directory.as_raw_fd(), c"receipt.json".as_ptr(), 0);
                        libc::unlinkat(self.directory.as_raw_fd(), c"owned".as_ptr(), 0);
                        libc::unlinkat(
                            self.root.as_raw_fd(),
                            self.name.as_ptr(),
                            libc::AT_REMOVEDIR,
                        );
                    }
                }
            }
        }
        let mut cleanup = Cleanup {
            root: &root,
            directory: &directory,
            name: &dirname,
            armed: true,
        };
        let mut marker = owned(unsafe {
            libc::openat(
                directory.as_raw_fd(),
                c"owned".as_ptr(),
                libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL | libc::O_NOFOLLOW | libc::O_CLOEXEC,
                0o600,
            )
        })?;
        marker.write_all(dirname.to_bytes()).map_err(error)?;
        marker.sync_all().map_err(error)?;
        directory.sync_all().map_err(error)?;
        root.sync_all().map_err(error)?;
        let mut output = owned(unsafe {
            libc::openat(
                directory.as_raw_fd(),
                c"content".as_ptr(),
                libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL | libc::O_CLOEXEC | libc::O_NOFOLLOW,
                0o600,
            )
        })?;
        let started = Instant::now();
        let mut size = 0u64;
        let mut digest = Sha256::new();
        if let Some((source, before)) = source_file.as_mut() {
            let mut buffer = [0u8; 64 * 1024];
            loop {
                check(cancel)?;
                if started.elapsed() > Duration::from_secs(120) {
                    return Err("快照复制超过120秒，未打开分享或保存页面".into());
                }
                let count = source.read(&mut buffer).map_err(error)?;
                if count == 0 {
                    break;
                }
                size = size
                    .checked_add(count as u64)
                    .filter(|n| *n <= before.len())
                    .ok_or("源文件复制期间长度变化")?;
                output.write_all(&buffer[..count]).map_err(error)?;
                digest.update(&buffer[..count]);
            }
            let after = source.metadata().map_err(error)?;
            if size != before.len()
                || before.dev() != after.dev()
                || before.ino() != after.ino()
                || before.len() != after.len()
                || before.mtime() != after.mtime()
                || before.mtime_nsec() != after.mtime_nsec()
                || before.ctime() != after.ctime()
                || before.ctime_nsec() != after.ctime_nsec()
            {
                return Err("文件复制期间已变化，未交付旧内容".into());
            }
        } else if let Some(history) = history {
            struct Writer<'a> {
                file: &'a mut File,
                digest: &'a mut Sha256,
                size: &'a mut u64,
                limit: u64,
                cancel: &'a CancelToken,
                started: Instant,
            }
            impl Write for Writer<'_> {
                fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
                    if self.cancel.is_cancelled() {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::Interrupted,
                            "会话导出取消",
                        ));
                    }
                    if self.started.elapsed() > Duration::from_secs(120) {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::TimedOut,
                            "会话导出超过120秒",
                        ));
                    }
                    if bytes.len() as u64 > self.limit.saturating_sub(*self.size) {
                        return Err(std::io::Error::other("会话导出或分享缓存超过预算"));
                    }
                    let count = self.file.write(bytes)?;
                    *self.size += count as u64;
                    self.digest.update(&bytes[..count]);
                    Ok(count)
                }
                fn flush(&mut self) -> std::io::Result<()> {
                    self.file.flush()
                }
            }
            let mut writer = Writer {
                file: &mut output,
                digest: &mut digest,
                size: &mut size,
                limit: MAX_CONVERSATION.min(MAX_CACHE.saturating_sub(total)),
                cancel,
                started,
            };
            let mut buffered = std::io::BufWriter::with_capacity(64 * 1024, &mut writer);
            history.write_json(&mut buffered, cancel)?;
            buffered.flush().map_err(error)?;
        }
        check(cancel)?;
        output.sync_all().map_err(error)?;
        let snapshot = Snapshot {
            version: 1,
            token,
            name: name.clone(),
            mime: mime(&name),
            bytes: size,
            sha256: format!("{:x}", digest.finalize()),
            expires_at_ms: now().saturating_add(24 * 60 * 60 * 1000),
            identity: FileIdentity::from_metadata(&output.metadata().map_err(error)?),
        };
        snapshot.validate()?;
        let bytes = serde_json::to_vec(&snapshot).map_err(|_| "分享回执无法编码")?;
        let mut receipt = owned(unsafe {
            libc::openat(
                directory.as_raw_fd(),
                c"receipt.json".as_ptr(),
                libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL | libc::O_NOFOLLOW | libc::O_CLOEXEC,
                0o600,
            )
        })?;
        receipt.write_all(&bytes).map_err(error)?;
        receipt.sync_all().map_err(error)?;
        directory.sync_all().map_err(error)?;
        root.sync_all().map_err(error)?;
        cleanup.armed = false;
        Ok(snapshot)
    }

    /// The operation's own receipt can be expired; expiry must not prevent
    /// authenticated cleanup. Only three known private files are unlinked.
    pub(super) fn revoke(cache: &Path, expected: &Snapshot) -> Result<(), String> {
        expected.validate_shape()?;
        let _guard = EXPORT_LOCK
            .get_or_init(Default::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let root = root(cache, false)?;
        let name = CString::new(expected.token.as_bytes()).map_err(|_| "清理token含NUL")?;
        let fd = unsafe {
            libc::openat(
                root.as_raw_fd(),
                name.as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
            )
        };
        if fd < 0 && std::io::Error::last_os_error().kind() == std::io::ErrorKind::NotFound {
            return Ok(());
        }
        let directory = owned(fd)?;
        let folder_identity = directory.metadata().map_err(error)?;
        let mut known = Vec::new();
        for entry in
            std::fs::read_dir(format!("/proc/self/fd/{}", directory.as_raw_fd())).map_err(error)?
        {
            let name = entry.map_err(error)?.file_name();
            if !["content", "receipt.json", "owned"]
                .iter()
                .any(|allowed| name == *allowed)
            {
                return Err("本操作快照目录含未知内容，未删除任何文件".into());
            }
            known.push(name);
            if known.len() > 3 {
                return Err("本操作快照目录超出预期，未删除".into());
            }
        }
        let mut opened = Vec::<(&CStr, File)>::new();
        for file_name in [c"content", c"receipt.json", c"owned"] {
            let fd = unsafe {
                libc::openat(
                    directory.as_raw_fd(),
                    file_name.as_ptr(),
                    libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK,
                )
            };
            if fd < 0 && std::io::Error::last_os_error().kind() == std::io::ErrorKind::NotFound {
                continue;
            }
            let file = owned(fd)?;
            if !file.metadata().map_err(error)?.is_file() {
                return Err("本操作快照文件类型变化，未删除".into());
            }
            opened.push((file_name, file));
        }
        let marker = opened
            .iter()
            .find(|(name, _)| *name == c"owned")
            .ok_or("本操作快照缺少所有权标记，未删除")?;
        if marker.1.metadata().map_err(error)?.len() != 64 {
            return Err("本操作快照所有权标记长度变化，未删除".into());
        }
        let mut marker_bytes = Vec::new();
        (&marker.1)
            .take(65)
            .read_to_end(&mut marker_bytes)
            .map_err(error)?;
        if marker_bytes != expected.token.as_bytes() {
            return Err("本操作快照所有权标记不匹配，未删除".into());
        }
        if opened.iter().any(|(name, _)| *name == c"receipt.json") {
            if read_record(&directory)? != *expected {
                return Err("本操作快照回执不匹配，未删除".into());
            }
        } else if opened.iter().any(|(name, _)| *name == c"content") {
            return Err("本操作快照有内容但回执缺失，未删除".into());
        }
        if let Some((_, content)) = opened.iter().find(|(name, _)| *name == c"content") {
            let metadata = content.metadata().map_err(error)?;
            if metadata.len() != expected.bytes
                || FileIdentity::from_metadata(&metadata) != expected.identity
            {
                return Err("本操作快照内容身份变化，未删除".into());
            }
        }
        // Verify each exact name still identifies the opened regular inode.
        // The lock also serializes this code against snapshot preparation/sweep.
        for (file_name, file) in &opened {
            let mut metadata: libc::stat = unsafe { std::mem::zeroed() };
            if unsafe {
                libc::fstatat(
                    directory.as_raw_fd(),
                    file_name.as_ptr(),
                    &mut metadata,
                    libc::AT_SYMLINK_NOFOLLOW,
                )
            } != 0
            {
                return Err(error(std::io::Error::last_os_error()));
            }
            let opened_metadata = file.metadata().map_err(error)?;
            if metadata.st_dev as u64 != opened_metadata.dev()
                || metadata.st_ino as u64 != opened_metadata.ino()
                || (metadata.st_mode as libc::mode_t) & libc::S_IFMT != libc::S_IFREG
            {
                return Err("本操作快照命名在清理前变化，未删除".into());
            }
        }
        for (file_name, _) in &opened {
            if unsafe { libc::unlinkat(directory.as_raw_fd(), file_name.as_ptr(), 0) } != 0 {
                return Err(error(std::io::Error::last_os_error()));
            }
        }
        directory.sync_all().map_err(error)?;
        let mut current: libc::stat = unsafe { std::mem::zeroed() };
        if unsafe {
            libc::fstatat(
                root.as_raw_fd(),
                name.as_ptr(),
                &mut current,
                libc::AT_SYMLINK_NOFOLLOW,
            )
        } != 0
        {
            return Err(error(std::io::Error::last_os_error()));
        }
        if current.st_dev as u64 != folder_identity.dev()
            || current.st_ino as u64 != folder_identity.ino()
        {
            return Err("本操作快照目录身份变化，未移除目录".into());
        }
        if unsafe { libc::unlinkat(root.as_raw_fd(), name.as_ptr(), libc::AT_REMOVEDIR) } != 0 {
            return Err(error(std::io::Error::last_os_error()));
        }
        root.sync_all().map_err(error)
    }
    pub(super) fn load(cache: &Path, token: &str) -> Result<(Snapshot, File), String> {
        if token.len() != 64 || !token.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err("分享token无效".into());
        }
        let root = root(cache, false)?;
        let name = CString::new(token).map_err(|_| "分享token包含NUL")?;
        let directory = component(&root, &name, false)?;
        let snapshot = read_record(&directory)?;
        snapshot.validate()?;
        if snapshot.token != token {
            return Err("分享记录身份不匹配".into());
        }
        let file = owned(unsafe {
            libc::openat(
                directory.as_raw_fd(),
                c"content".as_ptr(),
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK,
            )
        })?;
        let metadata = file.metadata().map_err(error)?;
        if !metadata.is_file()
            || metadata.len() != snapshot.bytes
            || FileIdentity::from_metadata(&metadata) != snapshot.identity
        {
            return Err("分享快照内容已变化".into());
        }
        Ok((snapshot, file))
    }
}
#[cfg(unix)]
pub(crate) fn prepare(
    cache: &Path,
    workspace: &Path,
    source: &str,
    cancel: &CancelToken,
) -> Result<Snapshot, String> {
    unix::prepare(cache, workspace, source, cancel)
}
#[cfg(unix)]
pub(crate) fn prepare_history(
    cache: &Path,
    history: &crate::conversation_export::Source,
    cancel: &CancelToken,
) -> Result<Snapshot, String> {
    unix::prepare_history(cache, history, cancel)
}
#[cfg(not(unix))]
pub(crate) fn prepare_history(
    _cache: &Path,
    _history: &crate::conversation_export::Source,
    _cancel: &CancelToken,
) -> Result<Snapshot, String> {
    Err("当前平台未接入会话快照导出".into())
}
#[cfg(unix)]
pub(crate) fn load(cache: &Path, token: &str) -> Result<(Snapshot, File), String> {
    unix::load(cache, token)
}
#[cfg(not(unix))]
pub(crate) fn prepare(
    _cache: &Path,
    _workspace: &Path,
    _source: &str,
    _cancel: &CancelToken,
) -> Result<Snapshot, String> {
    Err("当前平台未接入文件快照导出".into())
}
#[cfg(not(unix))]
pub(crate) fn load(_cache: &Path, _token: &str) -> Result<(Snapshot, File), String> {
    Err("当前平台未接入文件快照读取".into())
}

#[cfg(unix)]
pub(crate) fn revoke(cache: &Path, expected: &Snapshot) -> Result<(), String> {
    unix::revoke(cache, expected)
}
#[cfg(not(unix))]
pub(crate) fn revoke(_cache: &Path, _expected: &Snapshot) -> Result<(), String> {
    Err("当前平台未接入文件快照回收".into())
}
