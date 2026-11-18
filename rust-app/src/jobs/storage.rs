use super::{MAX_READ_BYTES, Output, Record};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};
pub const MAX_RECORD_BYTES: u64 = 4 * 1024 * 1024;
pub const MAX_METADATA_BYTES: u64 = 64 * 1024 * 1024;
pub struct Failure {
    pub error: String,
    pub committed: bool,
    pub bytes: Option<usize>,
}
pub fn record_path(directory: &Path, id: &str) -> Result<PathBuf, String> {
    super::identifier(id)?;
    Ok(directory.join(format!("{id}.job.json")))
}
pub fn log_path(directory: &Path, id: &str) -> Result<PathBuf, String> {
    super::identifier(id)?;
    Ok(directory.join(format!("{id}.log")))
}
pub fn open_read(path: &Path) -> Result<File, String> {
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK);
    }
    let file = options.open(path).map_err(|_| "作业文件不可读")?;
    if !file.metadata().map_err(|_| "作业文件信息不可读")?.is_file() {
        return Err("作业文件不是普通文件".into());
    }
    Ok(file)
}
pub fn load(directory: &Path) -> Result<Vec<Record>, String> {
    let mut result = Vec::new();
    let mut total = 0u64;
    for entry in fs::read_dir(directory).map_err(|_| "作业目录不可读")? {
        let entry = entry.map_err(|_| "作业目录项不可读")?;
        let name = entry.file_name();
        let Some(name) = name.to_str() else {
            continue;
        };
        let Some(id) = name.strip_suffix(".job.json") else {
            continue;
        };
        super::identifier(id)?;
        if result.len() >= super::MAX_RECORDS {
            return Err("作业记录超过128条预算".into());
        }
        let mut file = open_read(&entry.path())?;
        let length = file.metadata().map_err(|_| "作业记录大小不可读")?.len();
        total = total.checked_add(length).ok_or("作业记录大小溢出")?;
        if length > MAX_RECORD_BYTES || total > MAX_METADATA_BYTES {
            return Err("作业元数据超过存储预算".into());
        }
        let mut bytes = Vec::new();
        (&mut file)
            .take(MAX_RECORD_BYTES + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| "作业记录读取失败")?;
        if bytes.len() as u64 > MAX_RECORD_BYTES {
            return Err("作业记录超过4 MiB".into());
        }
        let record: Record =
            serde_json::from_slice(&bytes).map_err(|_| "作业记录损坏，原文件已保留")?;
        validate(directory, &record)?;
        result.push(record);
    }
    result.sort_by_key(|record| record.info.started_at);
    Ok(result)
}
pub fn validate(directory: &Path, record: &Record) -> Result<(), String> {
    record.key.validate()?;
    if record.info.id != record.key.id
        || PathBuf::from(&record.info.log_path) != log_path(directory, &record.key.id)?
        || record.info.command.len() > 64 * 1024
        || record.info.command.contains('\0')
        || record.info.label.len() > 1024
        || record.info.started_at < 0
        || record.deadline_ms > 86_400_000
        || record.changes_consumed > record.file_changes.activities.len()
        || record.deliveries.len() > 128
        || record.file_changes.activities.len() > 1024
        || record
            .error
            .as_ref()
            .is_some_and(|error| error.len() > 8192)
        || record.file_changes.notice.len() > 8192
    {
        return Err("作业记录字段无效".into());
    }
    for delivery in &record.deliveries {
        if delivery.request_key.is_empty()
            || delivery.request_key.len() > 1024
            || delivery
                .from
                .checked_add(delivery.count)
                .is_none_or(|end| end > record.file_changes.activities.len())
        {
            return Err("作业文件变化收据无效".into());
        }
    }
    Ok(())
}
pub fn save(directory: &Path, record: &Record, remaining: u64) -> Result<usize, Failure> {
    validate(directory, record).map_err(|error| Failure {
        error,
        committed: false,
        bytes: None,
    })?;
    let bytes = serde_json::to_vec(record).map_err(|_| Failure {
        error: "作业记录编码失败".into(),
        committed: false,
        bytes: None,
    })?;
    if bytes.len() as u64 > MAX_RECORD_BYTES || bytes.len() as u64 > remaining {
        return Err(Failure {
            error: "作业记录超过单条4 MiB或全部64 MiB元数据预算".into(),
            committed: false,
            bytes: None,
        });
    }
    static NEXT: AtomicU64 = AtomicU64::new(1);
    let temporary = directory.join(format!(
        ".job-{}-{}-{}.tmp",
        record.key.id,
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    let target = record_path(directory, &record.key.id).map_err(|error| Failure {
        error,
        committed: false,
        bytes: None,
    })?;
    let result = (|| -> Result<(), String> {
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options
                .mode(0o600)
                .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
        }
        let mut file = options
            .open(&temporary)
            .map_err(|_| "无法创建作业原子临时文件")?;
        file.write_all(&bytes)
            .and_then(|()| file.sync_all())
            .map_err(|_| "作业临时文件写入或同步失败")?;
        fs::rename(&temporary, &target).map_err(|_| "作业记录原子替换失败")?;
        Ok(())
    })();
    if let Err(error) = result {
        let _ = fs::remove_file(&temporary);
        return Err(Failure {
            error,
            committed: false,
            bytes: None,
        });
    }
    #[cfg(unix)]
    {
        if File::open(directory)
            .and_then(|file| file.sync_all())
            .is_err()
        {
            return Err(Failure {
                error: "作业记录已替换，目录同步未确认".into(),
                committed: true,
                bytes: Some(bytes.len()),
            });
        }
    }
    Ok(bytes.len())
}
pub fn create_log(directory: &Path, id: &str) -> Result<File, String> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
    }
    options
        .open(log_path(directory, id)?)
        .map_err(|_| "无法创建作业日志，未覆盖已有文件".into())
}
pub fn output(
    directory: &Path,
    id: &str,
    tail: usize,
    since: Option<u64>,
) -> Result<Output, String> {
    let mut file = open_read(&log_path(directory, id)?)?;
    let length = file.metadata().map_err(|_| "作业日志大小不可读")?.len();
    if since.is_some_and(|offset| offset > length) {
        return Err("作业输出游标超过当前日志长度".into());
    }
    let from = since.unwrap_or_else(|| length.saturating_sub(MAX_READ_BYTES as u64));
    let count = (length - from).min(MAX_READ_BYTES as u64);
    file.seek(SeekFrom::Start(from))
        .map_err(|_| "无法定位作业日志游标")?;
    let mut bytes = Vec::with_capacity(count as usize);
    (&mut file)
        .take(count)
        .read_to_end(&mut bytes)
        .map_err(|_| "作业日志读取失败")?;
    let next_offset = from + bytes.len() as u64;
    let invalid_utf8 = std::str::from_utf8(&bytes).is_err();
    let mut text = String::from_utf8_lossy(&bytes).into_owned();
    if since.is_none() {
        if text.ends_with('\n') {
            text.pop();
            if text.ends_with('\r') {
                text.pop();
            }
        }
        let lines: Vec<_> = text.split('\n').collect();
        text = lines[lines.len().saturating_sub(tail.clamp(1, 2000))..].join("\n");
    }
    Ok(Output {
        text,
        from,
        next_offset,
        log_length: length,
        has_more: next_offset < length,
        window_truncated: since.is_none() && from > 0,
        invalid_utf8,
    })
}
pub fn last_line(directory: &Path, id: &str) -> Result<String, String> {
    let mut file = open_read(&log_path(directory, id)?)?;
    let length = file.metadata().map_err(|_| "作业日志信息不可读")?.len();
    let from = length.saturating_sub(8192);
    file.seek(SeekFrom::Start(from))
        .map_err(|_| "作业日志尾部定位失败")?;
    let mut bytes = Vec::new();
    (&mut file)
        .take(8192)
        .read_to_end(&mut bytes)
        .map_err(|_| "作业日志尾部读取失败")?;
    let text = String::from_utf8_lossy(&bytes);
    Ok(text
        .lines()
        .rev()
        .find(|line| !line.is_empty())
        .unwrap_or("")
        .chars()
        .take(160)
        .collect())
}
