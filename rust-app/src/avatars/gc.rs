//! Only Rust-created images become collection candidates. Cold child configuration,
//! live drafts and concurrent new references all prevent removal.
use super::{AVATAR_BYTES, cleanup_staging, id_valid, same, validate_path};
use serde::Deserialize;
use std::{
    collections::HashSet,
    fs,
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
    time::{Duration, SystemTime},
};

const FILE_LIMIT: usize = 256;
const BYTE_LIMIT: u64 = 64 * 1024 * 1024;
const ENTRY_LIMIT: usize = 1024;
const REFERENCE_LIMIT: usize = 4096;
const COLD_RECORD_LIMIT: usize = 32768;
const COLD_BYTE_LIMIT: u64 = 128 * 1024 * 1024;
const ORPHAN_AGE: Duration = Duration::from_secs(24 * 60 * 60);
const STAGING_AGE: Duration = Duration::from_secs(60 * 60);

#[derive(Default)]
struct References {
    retained: HashSet<(PathBuf, String)>,
    removed: HashSet<(PathBuf, String)>,
    disabled: bool,
}
fn references() -> &'static Mutex<References> {
    static REFERENCES: OnceLock<Mutex<References>> = OnceLock::new();
    REFERENCES.get_or_init(|| Mutex::new(References::default()))
}

/// A live process keeps any image given to a draft or a new committed reference.
/// The next process can reclaim abandoned drafts only after the orphan grace.
pub(crate) fn retain<'a>(
    namespace: &Path,
    paths: impl IntoIterator<Item = &'a str>,
) -> Result<(), String> {
    let paths = paths
        .into_iter()
        .filter(|path| native_path(path))
        .map(|path| (namespace.to_path_buf(), path.to_owned()))
        .collect::<Vec<_>>();
    if paths.is_empty() {
        return Ok(());
    }
    let mut refs = references().lock().map_err(|_| "头像引用拥有者不可用")?;
    if paths.iter().any(|path| refs.removed.contains(path)) {
        return Err("该头像已作为过期未引用图片清理，请重新选择图片".into());
    }
    for path in paths {
        if refs.retained.len() >= REFERENCE_LIMIT && !refs.retained.contains(&path) {
            // No reference is evicted to make a future deletion appear safe.
            refs.disabled = true;
            break;
        }
        refs.retained.insert(path);
    }
    Ok(())
}

fn native_path(path: &str) -> bool {
    if validate_path(path).is_err() {
        return false;
    }
    let Some(stem) = path
        .strip_prefix("assistants/")
        .and_then(|name| name.strip_suffix(".png"))
    else {
        return false;
    };
    let mut pieces = stem.rsplitn(3, '-');
    let (Some(counter), Some(stamp), Some(assistant)) =
        (pieces.next(), pieces.next(), pieces.next())
    else {
        return false;
    };
    // Rust's unique_id uses hexadecimal epoch nanoseconds and a counter. The
    // old Android avatar filenames use one decimal millisecond suffix instead.
    id_valid(assistant)
        && !counter.is_empty()
        && counter.len() <= 16
        && counter.bytes().all(|b| b.is_ascii_hexdigit())
        && !stamp.is_empty()
        && stamp.len() <= 32
        && stamp.bytes().all(|b| b.is_ascii_hexdigit())
        && u128::from_str_radix(stamp, 16).is_ok_and(|stamp| stamp >= 1_500_000_000_000_000_000)
}

fn owned_directory(path: &Path) -> Result<(), String> {
    let metadata = fs::symlink_metadata(path).map_err(|_| "头像清理目录无法读取")?;
    if !metadata.is_dir()
        || metadata.file_type().is_symlink()
        || fs::canonicalize(path).map_err(|_| "头像清理目录无法解析")? != path
    {
        return Err("头像清理目录身份发生变化，未清理".into());
    }
    Ok(())
}

fn images(namespace: &Path) -> Result<Vec<(String, fs::Metadata)>, String> {
    owned_directory(namespace)?;
    let directory = namespace.join("assistants");
    match fs::symlink_metadata(&directory) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        _ => owned_directory(&directory)?,
    }
    let mut files = Vec::new();
    for (index, entry) in fs::read_dir(&directory)
        .map_err(|_| "头像目录文件列表无法读取")?
        .enumerate()
    {
        if index >= ENTRY_LIMIT {
            return Err("头像目录超过读取预算，未清理".into());
        }
        let entry = entry.map_err(|_| "头像目录文件列表不完整")?;
        let metadata = fs::symlink_metadata(entry.path()).map_err(|_| "头像文件状态缺失")?;
        if !metadata.is_file() || metadata.file_type().is_symlink() {
            return Err("头像目录有非普通文件，未清理".into());
        }
        let name = entry.file_name();
        let name = name.to_str().ok_or("头像文件名不是UTF-8，未清理")?;
        files.push((format!("assistants/{name}"), metadata));
    }
    Ok(files)
}

/// Refuse a new image at the physical limit. Protected images are never evicted.
pub(super) fn admit_save(namespace: &Path, relative: &str, bytes: usize) -> Result<(), String> {
    let files = images(namespace)?;
    let used = files.iter().try_fold(0u64, |sum, (_, metadata)| {
        sum.checked_add(metadata.len()).ok_or("头像目录体积溢出")
    })?;
    if files.len() >= FILE_LIMIT
        || used
            .checked_add(bytes as u64)
            .is_none_or(|size| size > BYTE_LIMIT)
    {
        return Err("头像存储已达到256张或64 MiB上限；已有头像和仍被引用的历史图片保留".into());
    }
    retain(namespace, [relative])
}

#[derive(Deserialize)]
struct SettingsReferences {
    version: u32,
    settings: AssistantReferences,
}
#[derive(Deserialize)]
struct AssistantReferences {
    #[serde(default)]
    assistants: Vec<AssistantReference>,
}
#[derive(Deserialize)]
struct AssistantReference {
    #[serde(default, alias = "avatarPath")]
    avatar_path: String,
}
#[derive(Deserialize)]
struct ChildReferences {
    version: u32,
    parent_chat: String,
    record: ChildReference,
}
#[derive(Deserialize)]
struct ChildReference {
    id: String,
    #[serde(default)]
    config: Option<ChildConfigReference>,
}
#[derive(Deserialize)]
struct ChildConfigReference {
    #[serde(default)]
    assistant_avatar_path: String,
}
fn add_reference(references: &mut HashSet<String>, path: String) -> Result<(), String> {
    validate_path(&path)?;
    if native_path(&path) {
        if references.len() >= REFERENCE_LIMIT && !references.contains(&path) {
            return Err("冷头像引用超过清理预算，未清理".into());
        }
        references.insert(path);
    }
    Ok(())
}

/// Every cold child record is scanned. The visible last-32 summaries cannot
/// establish that an older frozen configuration no longer references an image.
fn cold_references(namespace: &Path, root: &Path) -> Result<HashSet<String>, String> {
    owned_directory(root)?;
    let active = crate::legacy_migration::resolve_active(namespace)?
        .unwrap_or_else(|| namespace.to_path_buf());
    if active != root {
        return Err("头像清理源代已变化，未清理".into());
    }
    let mut refs = HashSet::new();
    let settings = root.join("settings.json");
    match fs::symlink_metadata(&settings) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(_) => return Err("设置头像引用无法读取，未清理".into()),
        Ok(_) => {
            let bytes = crate::legacy_migration::read_owned_regular(
                root,
                Path::new("settings.json"),
                256 * 1024,
            )?;
            let document: SettingsReferences =
                serde_json::from_slice(&bytes).map_err(|_| "设置头像引用文档无效，未清理")?;
            if document.version != 1 {
                return Err("设置头像引用版本未知，未清理".into());
            }
            for assistant in document.settings.assistants {
                add_reference(&mut refs, assistant.avatar_path)?;
            }
        }
    }
    let children = root.join("subagents");
    owned_directory(&children)?;
    let mut records = 0usize;
    let mut bytes_read = 0u64;
    for (index, parent) in fs::read_dir(&children)
        .map_err(|_| "冷子任务列表无法读取，未清理")?
        .enumerate()
    {
        if index >= 8192 {
            return Err("冷子任务父目录超过预算，未清理".into());
        }
        let parent = parent.map_err(|_| "冷子任务父目录列表不完整，未清理")?;
        let name = parent.file_name();
        let parent_id = name.to_str().ok_or("冷子任务父目录名无效，未清理")?;
        if !id_valid(parent_id) {
            return Err("冷子任务父目录身份无效，未清理".into());
        }
        owned_directory(&parent.path())?;
        for (index, child) in fs::read_dir(parent.path())
            .map_err(|_| "冷子任务记录列表无法读取，未清理")?
            .enumerate()
        {
            if index >= 8192 || records >= COLD_RECORD_LIMIT {
                return Err("冷子任务记录超过清理预算，未清理".into());
            }
            let child = child.map_err(|_| "冷子任务记录列表不完整，未清理")?;
            let name = child.file_name();
            let name = name.to_str().ok_or("冷子任务记录名无效，未清理")?;
            let Some(id) = name.strip_suffix(".json") else {
                // Unknown/temp files could contain another uncommitted owner.
                return Err("冷子任务有未知记录，未清理".into());
            };
            if !id_valid(id) {
                return Err("冷子任务记录身份无效，未清理".into());
            }
            let metadata = fs::symlink_metadata(child.path())
                .map_err(|_| "冷子任务记录状态无法读取，未清理")?;
            bytes_read = bytes_read
                .checked_add(metadata.len())
                .ok_or("冷记录体积溢出")?;
            if bytes_read > COLD_BYTE_LIMIT {
                return Err("冷子任务文档超过128 MiB清理读取预算，未清理".into());
            }
            let relative = Path::new("subagents").join(parent_id).join(name);
            let bytes = crate::legacy_migration::read_owned_regular(
                root,
                &relative,
                crate::store::MAX_CHILD_BYTES,
            )?;
            let document: ChildReferences =
                serde_json::from_slice(&bytes).map_err(|_| "冷子任务头像引用文档无效，未清理")?;
            if document.version != 1
                || document.parent_chat != parent_id
                || document.record.id != id
            {
                return Err("冷子任务头像引用归属不一致，未清理".into());
            }
            if let Some(config) = document.record.config {
                add_reference(&mut refs, config.assistant_avatar_path)?;
            }
            records += 1;
        }
    }
    let active = crate::legacy_migration::resolve_active(namespace)?
        .unwrap_or_else(|| namespace.to_path_buf());
    if active != root {
        return Err("冷引用扫描期间源代发生变化，未清理".into());
    }
    Ok(refs)
}

fn old(metadata: &fs::Metadata, age: Duration) -> bool {
    metadata
        .modified()
        .ok()
        .and_then(|modified| SystemTime::now().duration_since(modified).ok())
        .is_some_and(|elapsed| elapsed >= age)
}

fn expired_staging(namespace: &Path, active: &HashSet<String>) -> Result<(), String> {
    let inbox = namespace.join("avatar-inbox");
    match fs::symlink_metadata(&inbox) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        _ => owned_directory(&inbox)?,
    }
    let mut expired = Vec::new();
    for (index, entry) in fs::read_dir(&inbox)
        .map_err(|_| "头像临时操作列表无法读取")?
        .enumerate()
    {
        if index >= ENTRY_LIMIT {
            return Err("头像临时操作列表超过预算，未清理".into());
        }
        let entry = entry.map_err(|_| "头像临时操作列表不完整")?;
        let name = entry.file_name();
        let Some(id) = name.to_str().filter(|id| id_valid(id)) else {
            continue;
        };
        let metadata = fs::symlink_metadata(entry.path()).map_err(|_| "头像临时操作状态缺失")?;
        if !active.contains(id)
            && metadata.is_dir()
            && !metadata.file_type().is_symlink()
            && old(&metadata, STAGING_AGE)
            && expired.len() < 16
        {
            expired.push((entry.path(), metadata));
        }
    }
    for (path, original) in expired {
        owned_directory(&inbox)?;
        let current = fs::symlink_metadata(&path).map_err(|_| "头像临时操作复核失败")?;
        if same(&original, &current) && old(&current, STAGING_AGE) {
            // This removes only the known operation/inbox layout and at most
            // 16 regular files. An extra directory or link prevents deletion.
            cleanup_staging(&path)?;
        }
    }
    Ok(())
}

pub(super) fn collect(
    namespace: &Path,
    root: &Path,
    active: &HashSet<String>,
) -> Result<(), String> {
    expired_staging(namespace, active)?;
    let mut candidates = images(namespace)?;
    candidates.retain(|(path, metadata)| {
        native_path(path) && metadata.len() <= AVATAR_BYTES as u64 && old(metadata, ORPHAN_AGE)
    });
    if candidates.is_empty() {
        return Ok(());
    }
    let cold = cold_references(namespace, root)?;
    let mut refs = references()
        .lock()
        .map_err(|_| "头像引用拥有者不可用，未清理")?;
    if refs.disabled {
        return Ok(());
    }
    // New references register before their Settings/ChildDocument commit and
    // share this deletion lock. A collected path cannot become a new reference.
    let mut removed = 0usize;
    for (path, original) in candidates {
        let key = (namespace.to_path_buf(), path.clone());
        if cold.contains(&path) || refs.retained.contains(&key) {
            continue;
        }
        if removed >= 32 || refs.removed.len() >= REFERENCE_LIMIT {
            if refs.removed.len() >= REFERENCE_LIMIT {
                refs.disabled = true;
            }
            break;
        }
        owned_directory(&namespace.join("assistants"))?;
        let file = namespace.join(&path);
        let current = fs::symlink_metadata(&file).map_err(|_| "头像清理前状态无法复核")?;
        if !current.is_file()
            || current.file_type().is_symlink()
            || !same(&original, &current)
            || !old(&current, ORPHAN_AGE)
        {
            continue;
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::MetadataExt;
            if current.nlink() != 1 {
                continue;
            }
        }
        fs::remove_file(&file).map_err(|_| "头像孤儿文件删除未确认")?;
        refs.removed.insert(key);
        removed += 1;
    }
    #[cfg(unix)]
    if removed > 0 {
        fs::File::open(namespace.join("assistants"))
            .and_then(|dir| dir.sync_all())
            .map_err(|_| "头像孤儿文件已删除，目录同步未确认")?;
    }
    Ok(())
}
