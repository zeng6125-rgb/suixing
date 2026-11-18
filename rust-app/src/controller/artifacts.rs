//! Content-addressed screenshot artifacts. References name only this repository's
//! shots/<sha256>.jpg domain; old opaque strings never authorize external paths.
use crate::platform_tools;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, Metadata, OpenOptions},
    io::{Read, Write},
    path::PathBuf,
    sync::{
        Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{SystemTime, UNIX_EPOCH},
};
pub const MAX_JPEG_BYTES: usize = 12 * 1024 * 1024;
const MAX_PIXELS: u64 = 8 * 1024 * 1024;
const MAX_DIMENSION: u32 = 8192;
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct SavedScreenshot {
    pub reference: String,
    pub sha256: String,
    pub bytes: u64,
    pub width: u32,
    pub height: u32,
    pub mime_type: String,
}
impl SavedScreenshot {
    pub fn frozen(&self) -> platform_tools::SavedRef {
        platform_tools::SavedRef {
            reference: self.reference.clone(),
            sha256: self.sha256.clone(),
        }
    }
}
pub struct ScreenshotRepository {
    workspace: PathBuf,
    root: File,
    shots: File,
    commit: Mutex<()>,
}
impl ScreenshotRepository {
    pub fn open(workspace: PathBuf) -> Result<Self, String> {
        #[cfg(unix)]
        {
            use std::{
                ffi::CString,
                os::{
                    fd::{AsRawFd, FromRawFd},
                    unix::fs::OpenOptionsExt,
                },
            };
            if !workspace.is_absolute() || workspace.as_os_str().to_string_lossy().contains('\0') {
                return Err("截图仓库需要明确绝对workspace目录".into());
            }
            let first = fs::symlink_metadata(&workspace).map_err(|_| "截图workspace不可读")?;
            if !first.is_dir() || first.file_type().is_symlink() {
                return Err("截图workspace不是普通目录".into());
            }
            let workspace = fs::canonicalize(&workspace).map_err(|_| "截图workspace无法解析")?;
            let root = OpenOptions::new()
                .read(true)
                .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC)
                .open(&workspace)
                .map_err(|_| "截图workspace目录不能安全打开")?;
            if !same_identity(
                &first,
                &root.metadata().map_err(|_| "截图workspace信息不可读")?,
            ) {
                return Err("截图workspace在打开期间变化".into());
            }
            let name = CString::new("shots").expect("fixed directory name");
            if unsafe { libc::mkdirat(root.as_raw_fd(), name.as_ptr(), 0o700) } < 0
                && std::io::Error::last_os_error().raw_os_error() != Some(libc::EEXIST)
            {
                return Err("截图shots目录创建失败".into());
            }
            let fd = unsafe {
                libc::openat(
                    root.as_raw_fd(),
                    name.as_ptr(),
                    libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
                )
            };
            if fd < 0 {
                return Err("截图shots目录包含链接或不能安全打开".into());
            }
            let shots = unsafe { File::from_raw_fd(fd) };
            let result = Self {
                workspace,
                root,
                shots,
                commit: Mutex::new(()),
            };
            result.check_roots()?;
            result
                .root
                .sync_all()
                .map_err(|_| "截图shots目录创建同步未确认")?;
            Ok(result)
        }
        #[cfg(not(unix))]
        {
            let _ = workspace;
            Err("当前平台没有截图仓库所需的不跟随链接目录操作".into())
        }
    }
    pub fn store_outcome(
        &self,
        outcome: &platform_tools::Outcome,
    ) -> Result<Option<SavedScreenshot>, String> {
        match &outcome.image {
            None => Ok(None),
            Some(_) if outcome.platform_pending.is_some() => {
                Err("截图操作尚未完成，未保存为最终历史图片".into())
            }
            Some(image) => self.store_image(image).map(Some),
        }
    }
    pub fn store_image(&self, image: &platform_tools::Image) -> Result<SavedScreenshot, String> {
        self.store_jpeg(&image.jpeg, image.width, image.height)
    }
    pub fn store_jpeg(
        &self,
        jpeg: &[u8],
        width: u32,
        height: u32,
    ) -> Result<SavedScreenshot, String> {
        let actual = jpeg_dimensions(jpeg)?;
        if actual != (width, height) {
            return Err("截图JPEG尺寸与实际捕获回执不一致".into());
        }
        let hash = format!("{:x}", Sha256::digest(jpeg));
        let reference = format!("shots/{hash}.jpg");
        let final_name = format!("{hash}.jpg");
        let _commit = self
            .commit
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        self.check_roots()?;
        let exists = match fs::symlink_metadata(self.workspace.join("shots").join(&final_name)) {
            Ok(metadata) if metadata.is_file() && !metadata.file_type().is_symlink() => true,
            Ok(_) => return Err("已有截图成员不是普通文件，未跟随链接或覆盖".into()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => false,
            Err(_) => return Err("已有截图成员状态无法核对，未覆盖".into()),
        };
        if exists {
            let existing = self.open_member(&final_name, false)?;
            let saved = self.inspect_open(existing, &reference, MAX_JPEG_BYTES)?.0;
            if saved.sha256 != hash
                || saved.bytes != jpeg.len() as u64
                || saved.width != width
                || saved.height != height
            {
                return Err("已有截图名称或内容冲突，未覆盖".into());
            }
            return Ok(saved);
        }
        static NEXT: AtomicU64 = AtomicU64::new(1);
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|_| "截图暂存身份时间无效")?
            .as_nanos();
        let temp_name = format!(
            ".shot-{}-{stamp}-{}.tmp",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::AcqRel)
        );
        let mut temporary = self.open_member(&temp_name, true)?;
        let temp_identity = temporary.metadata().map_err(|_| "截图暂存文件信息不可读")?;
        let result = (|| -> Result<SavedScreenshot, String> {
            temporary
                .write_all(jpeg)
                .and_then(|()| temporary.sync_all())
                .map_err(|_| "截图暂存写入或同步失败")?;
            self.check_roots()?;
            self.publish(&temp_name, &final_name)?;
            // Publication may have raced another writer of the same hash. Read
            // and verify the published member before returning any reference.
            let saved = self
                .inspect_open(
                    self.open_member(&final_name, false)?,
                    &reference,
                    MAX_JPEG_BYTES,
                )?
                .0;
            if saved.sha256 != hash
                || saved.bytes != jpeg.len() as u64
                || saved.width != width
                || saved.height != height
            {
                return Err("截图已发布成员与捕获内容不一致，未返回可复用引用".into());
            }
            self.shots
                .sync_all()
                .map_err(|_| "截图已发布，但shots目录同步未确认")?;
            self.check_roots()?;
            Ok(saved)
        })();
        self.cleanup_owned(&temp_name, &temp_identity);
        result
    }
    pub fn freeze_reference(&self, reference: &str) -> Result<platform_tools::SavedRef, String> {
        let name = member(reference)?;
        let saved = self
            .inspect_open(self.open_member(name, false)?, reference, MAX_JPEG_BYTES)?
            .0;
        Ok(saved.frozen())
    }
    pub fn read_reference(
        &self,
        reference: &platform_tools::SavedRef,
        max_bytes: usize,
    ) -> Result<Vec<u8>, String> {
        let name = member(&reference.reference)?;
        let expected = &name[..64];
        if reference.sha256 != expected {
            return Err("冻结截图哈希与仓库引用不一致".into());
        }
        let (_, bytes) = self.inspect_open(
            self.open_member(name, false)?,
            &reference.reference,
            max_bytes,
        )?;
        Ok(bytes)
    }
    pub fn read_saved(&self, saved: &SavedScreenshot, max_bytes: usize) -> Result<Vec<u8>, String> {
        if saved.mime_type != "image/jpeg" {
            return Err("截图历史引用MIME无效".into());
        }
        let name = member(&saved.reference)?;
        let (actual, bytes) =
            self.inspect_open(self.open_member(name, false)?, &saved.reference, max_bytes)?;
        if actual != *saved {
            return Err("截图历史引用元数据与实际文件不一致".into());
        }
        Ok(bytes)
    }
    fn check_roots(&self) -> Result<(), String> {
        let root = fs::symlink_metadata(&self.workspace).map_err(|_| "截图workspace路径已失效")?;
        let shots = fs::symlink_metadata(self.workspace.join("shots"))
            .map_err(|_| "截图shots路径已失效")?;
        if !root.is_dir()
            || root.file_type().is_symlink()
            || !shots.is_dir()
            || shots.file_type().is_symlink()
            || !same_identity(
                &root,
                &self
                    .root
                    .metadata()
                    .map_err(|_| "截图workspace句柄不可读")?,
            )
            || !same_identity(
                &shots,
                &self.shots.metadata().map_err(|_| "截图shots句柄不可读")?,
            )
        {
            return Err("截图仓库目录身份已变化，未跟随新链接或目录".into());
        }
        Ok(())
    }
    fn inspect_open(
        &self,
        mut file: File,
        reference: &str,
        max_bytes: usize,
    ) -> Result<(SavedScreenshot, Vec<u8>), String> {
        self.check_roots()?;
        let name = member(reference)?;
        let before = file.metadata().map_err(|_| "截图文件信息不可读")?;
        let limit = max_bytes.min(MAX_JPEG_BYTES);
        if limit == 0 || !before.is_file() || before.len() == 0 || before.len() > limit as u64 {
            return Err("截图不是有界非空普通文件".into());
        }
        let mut bytes = Vec::with_capacity(before.len() as usize);
        (&mut file)
            .take(before.len() + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| "截图文件读取失败")?;
        let after = file.metadata().map_err(|_| "截图文件读取后信息不可读")?;
        let current = self
            .open_member(name, false)?
            .metadata()
            .map_err(|_| "截图引用读取后信息不可读")?;
        if bytes.len() as u64 != before.len()
            || !same_file(&before, &after)
            || !same_file(&before, &current)
        {
            return Err("截图内容或文件身份在读取期间变化".into());
        }
        let hash = format!("{:x}", Sha256::digest(&bytes));
        if hash != name[..64] {
            return Err("截图文件哈希与引用不一致".into());
        }
        let (width, height) = jpeg_dimensions(&bytes)?;
        self.check_roots()?;
        Ok((
            SavedScreenshot {
                reference: reference.into(),
                sha256: hash,
                bytes: bytes.len() as u64,
                width,
                height,
                mime_type: "image/jpeg".into(),
            },
            bytes,
        ))
    }
    fn open_member(&self, name: &str, create: bool) -> Result<File, String> {
        #[cfg(unix)]
        {
            use std::{
                ffi::CString,
                os::fd::{AsRawFd, FromRawFd},
            };
            if name.is_empty() || name.contains(['/', '\\', '\0']) || name == "." || name == ".." {
                return Err("截图成员名称无效".into());
            }
            let name = CString::new(name).map_err(|_| "截图成员名称包含NUL")?;
            let flags = if create {
                libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL
            } else {
                libc::O_RDONLY
            };
            let fd = unsafe {
                libc::openat(
                    self.shots.as_raw_fd(),
                    name.as_ptr(),
                    flags | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK,
                    0o600,
                )
            };
            if fd < 0 {
                return Err("截图成员缺失、为链接或不能安全打开".into());
            }
            let file = unsafe { File::from_raw_fd(fd) };
            if !file.metadata().map_err(|_| "截图成员信息不可读")?.is_file() {
                return Err("截图成员不是普通文件".into());
            }
            Ok(file)
        }
        #[cfg(not(unix))]
        {
            let _ = (name, create);
            Err("当前平台没有截图成员安全打开操作".into())
        }
    }
    fn publish(&self, temp: &str, target: &str) -> Result<(), String> {
        #[cfg(unix)]
        {
            use std::{ffi::CString, os::fd::AsRawFd};
            let temp = CString::new(temp).map_err(|_| "截图暂存名称无效")?;
            let target = CString::new(target).map_err(|_| "截图目标名称无效")?;
            if unsafe {
                libc::linkat(
                    self.shots.as_raw_fd(),
                    temp.as_ptr(),
                    self.shots.as_raw_fd(),
                    target.as_ptr(),
                    0,
                )
            } < 0
                && std::io::Error::last_os_error().raw_os_error() != Some(libc::EEXIST)
            {
                return Err("截图原子发布失败，已有目标未覆盖".into());
            }
            Ok(())
        }
        #[cfg(not(unix))]
        {
            let _ = (temp, target);
            Err("当前平台没有截图原子发布操作".into())
        }
    }
    fn cleanup_owned(&self, name: &str, owned: &Metadata) {
        #[cfg(unix)]
        {
            use std::{ffi::CString, os::fd::AsRawFd};
            if self
                .open_member(name, false)
                .ok()
                .and_then(|file| file.metadata().ok())
                .is_some_and(|current| same_identity(owned, &current))
                && let Ok(name) = CString::new(name)
            {
                unsafe {
                    libc::unlinkat(self.shots.as_raw_fd(), name.as_ptr(), 0);
                }
            }
        }
        #[cfg(not(unix))]
        let _ = (name, owned);
    }
}
impl platform_tools::ScreenshotRepository for ScreenshotRepository {
    fn freeze(&self, reference: &str) -> Result<platform_tools::SavedRef, String> {
        self.freeze_reference(reference)
    }
    fn read(
        &self,
        reference: &platform_tools::SavedRef,
        max_bytes: usize,
    ) -> Result<Vec<u8>, String> {
        self.read_reference(reference, max_bytes)
    }
}
fn member(reference: &str) -> Result<&str, String> {
    let name = reference
        .strip_prefix("shots/")
        .ok_or("截图引用不属于shots内容哈希域；旧opaque引用未转换成外部路径")?;
    if name.len() != 68
        || !name.ends_with(".jpg")
        || !name.as_bytes()[..64]
            .iter()
            .all(|byte| matches!(*byte,b'0'..=b'9'|b'a'..=b'f'))
    {
        return Err("截图引用名称无效".into());
    }
    Ok(name)
}
fn same_identity(a: &Metadata, b: &Metadata) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        a.dev() == b.dev() && a.ino() == b.ino()
    }
    #[cfg(not(unix))]
    {
        let _ = (a, b);
        false
    }
}
fn same_file(a: &Metadata, b: &Metadata) -> bool {
    let same = a.is_file()
        && b.is_file()
        && same_identity(a, b)
        && a.len() == b.len()
        && a.modified().ok() == b.modified().ok();
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        same && a.ctime() == b.ctime() && a.ctime_nsec() == b.ctime_nsec() && a.mode() == b.mode()
    }
    #[cfg(not(unix))]
    {
        same
    }
}
/// Inspect the JPEG frame header before any consumer decodes pixels. The actual
/// image decoder still owns bitstream acceptance; metadata cannot enlarge limits.
fn jpeg_dimensions(bytes: &[u8]) -> Result<(u32, u32), String> {
    if bytes.len() < 16
        || bytes.len() > MAX_JPEG_BYTES
        || !bytes.starts_with(&[0xff, 0xd8])
        || !bytes.ends_with(&[0xff, 0xd9])
    {
        return Err("截图JPEG格式或字节长度无效".into());
    }
    let mut at = 2usize;
    let mut dimensions = None;
    let mut segments = 0usize;
    while at < bytes.len() {
        if bytes[at] != 0xff {
            return Err("截图JPEG段边界无效".into());
        }
        while at < bytes.len() && bytes[at] == 0xff {
            at += 1;
        }
        let marker = *bytes.get(at).ok_or("截图JPEG标记截断")?;
        at += 1;
        if marker == 0xda {
            return dimensions.ok_or_else(|| "截图JPEG扫描前缺少尺寸".into());
        }
        if marker == 0xd9 {
            break;
        }
        if marker == 0x01 {
            continue;
        }
        if matches!(marker, 0x00 | 0xd0..=0xd8) {
            return Err("截图JPEG头部标记无效".into());
        }
        segments += 1;
        if segments > 4096 {
            return Err("截图JPEG头部段数超过预算".into());
        }
        let length_bytes = bytes.get(at..at + 2).ok_or("截图JPEG段长度截断")?;
        let length = u16::from_be_bytes([length_bytes[0], length_bytes[1]]) as usize;
        let end = at
            .checked_add(length)
            .filter(|end| length >= 2 && *end <= bytes.len())
            .ok_or("截图JPEG段长度无效")?;
        if matches!(marker,0xc0..=0xc3|0xc5..=0xc7|0xc9..=0xcb|0xcd..=0xcf) {
            if length < 8 || bytes[at + 2] != 8 || dimensions.is_some() {
                return Err("截图JPEG尺寸段无效".into());
            }
            let height = u16::from_be_bytes([bytes[at + 3], bytes[at + 4]]) as u32;
            let width = u16::from_be_bytes([bytes[at + 5], bytes[at + 6]]) as u32;
            let components = bytes[at + 7] as usize;
            if !matches!(components, 1 | 3 | 4)
                || length != 8 + 3 * components
                || width == 0
                || height == 0
                || width > MAX_DIMENSION
                || height > MAX_DIMENSION
                || u64::from(width) * u64::from(height) > MAX_PIXELS
            {
                return Err("截图JPEG尺寸或像素数超过预算".into());
            }
            dimensions = Some((width, height));
        }
        at = end;
    }
    Err("截图JPEG缺少有效扫描数据".into())
}
