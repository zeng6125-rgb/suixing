use super::{LogInfo, MAX_RECORDS, RawWindow, Segment, SessionInfo};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};
const SEGMENT_BYTES: u64 = 1024 * 1024;
pub(super) fn directory(path: &Path) -> Result<PathBuf, String> {
    if !path.is_absolute()
        || fs::symlink_metadata(path)
            .ok()
            .is_some_and(|m| m.file_type().is_symlink())
    {
        return Err("终端持久化目录必须是绝对非链接路径".into());
    }
    fs::create_dir_all(path).map_err(|_| "无法准备终端目录")?;
    fs::canonicalize(path).map_err(|_| "无法核对终端目录".into())
}
fn validate_id(id: &str) -> Result<(), String> {
    if id.len() != 65 || !id.starts_with('t') || !id[1..].bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("终端存储身份无效".into());
    }
    Ok(())
}
fn record_path(root: &Path, id: &str) -> Result<PathBuf, String> {
    validate_id(id)?;
    Ok(root.join(format!("{id}.session.json")))
}
fn raw_path(root: &Path, id: &str, index: u64) -> Result<PathBuf, String> {
    validate_id(id)?;
    Ok(root.join(format!("{id}.{index}.raw")))
}
fn options(write: bool) -> OpenOptions {
    let mut options = OpenOptions::new();
    if write {
        options.write(true).create_new(true);
    } else {
        options.read(true);
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK);
    }
    options
}
fn read_regular(path: &Path) -> Result<File, String> {
    let file = options(false).open(path).map_err(|_| "终端文件不可读")?;
    if !file.metadata().map_err(|_| "终端文件信息不可读")?.is_file() {
        return Err("终端文件不是普通文件".into());
    }
    Ok(file)
}
pub(super) fn load(root: &Path) -> Result<Vec<SessionInfo>, String> {
    let mut records = Vec::new();
    for entry in fs::read_dir(root).map_err(|_| "终端目录不可读")? {
        let entry = entry.map_err(|_| "终端目录项不可读")?;
        let name = entry.file_name();
        let Some(name) = name.to_str() else {
            continue;
        };
        let Some(id) = name.strip_suffix(".session.json") else {
            continue;
        };
        validate_id(id)?;
        if records.len() >= MAX_RECORDS {
            return Err("终端记录超过保留预算".into());
        }
        let mut bytes = Vec::new();
        read_regular(&entry.path())?
            .take(128 * 1024 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| "终端记录读取失败")?;
        if bytes.len() > 128 * 1024 {
            return Err("终端记录超过128 KiB".into());
        }
        let info: SessionInfo =
            serde_json::from_slice(&bytes).map_err(|_| "终端记录损坏，原文件已保留")?;
        validate(&info)?;
        if info.id != id {
            return Err("终端文件名与记录身份不一致".into());
        }
        records.push(info);
    }
    Ok(records)
}
pub(super) fn log_usage(root: &Path) -> Result<u64, String> {
    let mut bytes = 0u64;
    for entry in fs::read_dir(root).map_err(|_| "终端日志目录不可读")? {
        let entry = entry.map_err(|_| "终端日志目录项不可读")?;
        let name = entry.file_name();
        if !name.to_str().is_some_and(|name| name.ends_with(".raw")) {
            continue;
        }
        let metadata = fs::symlink_metadata(entry.path()).map_err(|_| "终端日志长度不可读")?;
        if metadata.is_file() && !metadata.file_type().is_symlink() {
            bytes = bytes
                .checked_add(metadata.len())
                .ok_or("终端日志容量溢出")?;
        }
    }
    Ok(bytes)
}
fn validate(info: &SessionInfo) -> Result<(), String> {
    validate_id(&info.id)?;
    info.key.validate()?;
    if info.name.len() > 256
        || info.request_key.len() > 1024
        || info.request_key.is_empty()
        || info.log.segments.len() > 2
        || info.log.retained_from > info.log.end
        || info.note.as_ref().is_some_and(|note| note.len() > 8192)
    {
        return Err("终端记录字段超出预算".into());
    }
    let mut from = info.log.retained_from;
    for segment in &info.log.segments {
        if segment.from != from || segment.bytes > SEGMENT_BYTES {
            return Err("终端日志游标不连续".into());
        }
        from = from.checked_add(segment.bytes).ok_or("终端日志游标溢出")?;
    }
    if from != info.log.end {
        return Err("终端日志末尾游标不一致".into());
    }
    Ok(())
}
pub(super) fn save(root: &Path, info: &SessionInfo) -> Result<(), String> {
    validate(info)?;
    let bytes = serde_json::to_vec(info).map_err(|_| "终端记录编码失败")?;
    if bytes.len() > 128 * 1024 {
        return Err("终端记录超过128 KiB".into());
    }
    static NEXT: AtomicU64 = AtomicU64::new(1);
    let temporary = root.join(format!(
        ".terminal-{}-{}.tmp",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    let result = (|| {
        let mut file = options(true)
            .open(&temporary)
            .map_err(|_| "终端原子临时文件创建失败")?;
        file.write_all(&bytes)
            .and_then(|()| file.sync_all())
            .map_err(|_| "终端元数据同步失败")?;
        fs::rename(&temporary, record_path(root, &info.id)?)
            .map_err(|_| "终端元数据原子提交失败")?;
        sync_directory(root)
    })();
    if result.is_err() {
        let _ = fs::remove_file(temporary);
    }
    result
}
fn sync_directory(root: &Path) -> Result<(), String> {
    #[cfg(unix)]
    {
        File::open(root)
            .and_then(|file| file.sync_all())
            .map_err(|_| "终端目录同步未确认")?;
    }
    #[cfg(not(unix))]
    let _ = root;
    Ok(())
}
pub(super) fn output(
    root: &Path,
    info: &SessionInfo,
    since: u64,
    max: usize,
) -> Result<RawWindow, String> {
    if !(1..=256 * 1024).contains(&max) {
        return Err("终端日志窗口必须在1–256 KiB内".into());
    }
    validate(info)?;
    if since < info.log.retained_from {
        return Err(format!(
            "日志游标已过期，最早保留字节为{}",
            info.log.retained_from
        ));
    }
    if since > info.log.end {
        return Err("日志游标超过已提交末尾".into());
    }
    let mut bytes = Vec::new();
    let mut cursor = since;
    for segment in &info.log.segments {
        if cursor >= segment.from + segment.bytes {
            continue;
        }
        let mut file = read_regular(&raw_path(root, &info.id, segment.index)?)?;
        if file.metadata().map_err(|_| "终端日志长度不可读")?.len() < segment.bytes {
            return Err("终端日志短于已提交游标".into());
        }
        file.seek(SeekFrom::Start(cursor - segment.from))
            .map_err(|_| "终端日志定位失败")?;
        let count = (segment.from + segment.bytes - cursor).min((max - bytes.len()) as u64);
        let before = bytes.len();
        file.take(count)
            .read_to_end(&mut bytes)
            .map_err(|_| "终端日志读取失败")?;
        if bytes.len() - before != count as usize {
            return Err("终端日志在读取期间变化".into());
        }
        cursor += count;
        if bytes.len() == max {
            break;
        }
    }
    Ok(RawWindow {
        bytes,
        from: since,
        next_offset: cursor,
        retained_from: info.log.retained_from,
        end: info.log.end,
        has_more: cursor < info.log.end,
        logging: info.log.enabled,
        error: info.log.error.clone(),
    })
}
pub(super) struct RawLog {
    root: PathBuf,
    id: String,
    pub info: LogInfo,
    file: Option<File>,
    stale: Vec<Segment>,
}
impl RawLog {
    pub fn new(root: PathBuf, id: String, info: LogInfo) -> Self {
        Self {
            root,
            id,
            info,
            file: None,
            stale: Vec::new(),
        }
    }
    pub fn append(&mut self, bytes: &[u8]) -> Result<(), String> {
        if !self.info.enabled {
            return Ok(());
        }
        let mut offset = 0;
        while offset < bytes.len() {
            let rotate = self
                .info
                .segments
                .last()
                .is_none_or(|segment| segment.bytes == SEGMENT_BYTES);
            if rotate {
                self.sync()?;
                let index = self.info.segments.last().map_or(Ok(0), |segment| {
                    segment.index.checked_add(1).ok_or("终端日志段序号溢出")
                })?;
                self.file = Some(
                    options(true)
                        .open(raw_path(&self.root, &self.id, index)?)
                        .map_err(|_| "终端新日志段创建失败")?,
                );
                self.info.segments.push(Segment {
                    index,
                    from: self.info.end,
                    bytes: 0,
                });
                if self.info.segments.len() > 2 {
                    let old = self.info.segments.remove(0);
                    self.stale.push(old);
                    self.info.retained_from = self.info.segments[0].from;
                }
            }
            let segment = self.info.segments.last_mut().ok_or("终端日志段缺失")?;
            let count = (SEGMENT_BYTES - segment.bytes).min((bytes.len() - offset) as u64) as usize;
            let file = self.file.as_mut().ok_or("终端日志流已关闭")?;
            file.write_all(&bytes[offset..offset + count])
                .map_err(|_| "终端日志写入失败，部分字节可能已保留")?;
            segment.bytes += count as u64;
            self.info.end = self
                .info
                .end
                .checked_add(count as u64)
                .ok_or("终端日志游标溢出")?;
            offset += count;
        }
        Ok(())
    }
    pub fn sync(&mut self) -> Result<(), String> {
        if let Some(file) = &self.file {
            file.sync_all().map_err(|_| "终端原始日志同步失败")?;
        }
        Ok(())
    }
    pub fn committed(&mut self) -> u64 {
        let mut removed = 0;
        for segment in self.stale.drain(..) {
            if let Ok(path) = raw_path(&self.root, &self.id, segment.index) {
                if fs::remove_file(path).is_ok() {
                    removed += segment.bytes;
                }
            }
        }
        removed
    }
    pub fn enabled(&mut self, value: bool) -> Result<(), String> {
        if value && self.info.error.is_some() {
            return Err("原始日志曾失败，游标未重新使用；请新建会话开启日志".into());
        }
        self.sync()?;
        // The stream remains owned while logging is disabled; there is no reopen
        // by filename that could attach a writer to replaced or old data.
        self.info.enabled = value;
        Ok(())
    }
}
