//! Bounded filesystem evidence. Overlapping registered observations never
//! attribute the same filesystem mutation to independent jobs.
use super::ObservedChanges;
use crate::chat::{FileActivity, FileActivityKind};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, HashMap, HashSet},
    fs::{self, Metadata, OpenOptions},
    io::Read,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};
#[derive(Clone)]
struct Stamp {
    size: u64,
    digest: Option<String>,
    text_lines: Option<i64>,
    identity: Option<(u64, u64)>,
}
struct Snapshot {
    files: BTreeMap<PathBuf, Stamp>,
    complete: HashSet<PathBuf>,
    limited: bool,
}
struct Active {
    roots: Vec<PathBuf>,
    overlapping: Arc<AtomicBool>,
}
static ACTIVE: OnceLock<Mutex<HashMap<u64, Active>>> = OnceLock::new();
pub(crate) struct Observation {
    id: u64,
    roots: Vec<PathBuf>,
    before: Snapshot,
    overlapping: Arc<AtomicBool>,
}
impl Observation {
    pub(crate) fn begin(mut roots: Vec<PathBuf>) -> Self {
        let mut uncertain = false;
        roots = roots
            .into_iter()
            .filter_map(|path| match canonical_root(&path) {
                Some(path) => Some(path),
                None => {
                    uncertain = true;
                    None
                }
            })
            .collect();
        roots.sort();
        roots.dedup();
        roots.truncate(33);
        static NEXT: AtomicU64 = AtomicU64::new(1);
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        let overlapping = Arc::new(AtomicBool::new(uncertain));
        let mut active = ACTIVE
            .get_or_init(Default::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        for other in active.values() {
            if roots.iter().any(|path| {
                other
                    .roots
                    .iter()
                    .any(|other| path.starts_with(other) || other.starts_with(path))
            }) {
                overlapping.store(true, Ordering::Release);
                other.overlapping.store(true, Ordering::Release);
            }
        }
        active.insert(
            id,
            Active {
                roots: roots.clone(),
                overlapping: overlapping.clone(),
            },
        );
        drop(active);
        let before = snapshot(&roots);
        Self {
            id,
            roots,
            before,
            overlapping,
        }
    }
    pub(crate) fn finish(self) -> ObservedChanges {
        let after = snapshot(&self.roots);
        if self.overlapping.load(Ordering::Acquire) {
            return ObservedChanges {
                activities: Vec::new(),
                notice: "文件变更归属未知：观察期间存在重叠作业或文件操作".into(),
            };
        }
        let complete = |path: &Path, state: &Snapshot| {
            self.roots.iter().any(|root| {
                state.complete.contains(root) && (path == root || path.starts_with(root))
            })
        };
        let paths: HashSet<_> = self
            .before
            .files
            .keys()
            .chain(after.files.keys())
            .cloned()
            .collect();
        let mut activities = Vec::new();
        for path in paths {
            let first = self.before.files.get(&path);
            let last = after.files.get(&path);
            let kind = match (first, last) {
                (None, Some(_)) if complete(&path, &self.before) => Some(FileActivityKind::Created),
                (Some(_), None) if complete(&path, &after) => Some(FileActivityKind::Deleted),
                (Some(first), Some(last))
                    if first.size != last.size
                        || (first.digest.is_some()
                            && last.digest.is_some()
                            && first.digest != last.digest) =>
                {
                    Some(FileActivityKind::Modified)
                }
                _ => None,
            };
            let Some(kind) = kind else {
                continue;
            };
            let added_lines = if kind == FileActivityKind::Created {
                last.and_then(|s| s.text_lines)
            } else {
                None
            };
            let removed_lines = if kind == FileActivityKind::Deleted {
                first.and_then(|s| s.text_lines)
            } else {
                None
            };
            activities.push(FileActivity {
                path: path.to_string_lossy().into_owned(),
                kind,
                added_lines,
                removed_lines,
                size_bytes: last.and_then(|s| i64::try_from(s.size).ok()),
                previous_path: None,
                description: "作业观察窗口内的文件变化".into(),
                extra: Default::default(),
            });
        }
        let deleted: Vec<_> = activities
            .iter()
            .filter(|a| a.kind == FileActivityKind::Deleted)
            .cloned()
            .collect();
        let created: Vec<_> = activities
            .iter()
            .filter(|a| a.kind == FileActivityKind::Created)
            .cloned()
            .collect();
        for source in deleted {
            let Some(first) = self.before.files.get(Path::new(&source.path)) else {
                continue;
            };
            if first.identity.is_none() || first.digest.is_none() {
                continue;
            }
            let candidates: Vec<_> = created
                .iter()
                .filter(|target| {
                    after
                        .files
                        .get(Path::new(&target.path))
                        .is_some_and(|last| {
                            first.identity == last.identity && first.digest == last.digest
                        })
                })
                .collect();
            if candidates.len() != 1
                || self
                    .before
                    .files
                    .values()
                    .filter(|stamp| stamp.identity == first.identity)
                    .count()
                    != 1
            {
                continue;
            }
            let target = candidates[0];
            if !activities.iter().any(|a| a.path == source.path)
                || !activities.iter().any(|a| a.path == target.path)
            {
                continue;
            }
            activities.retain(|a| a.path != source.path && a.path != target.path);
            let mut moved = (*target).clone();
            moved.kind = FileActivityKind::Moved;
            moved.previous_path = Some(source.path);
            moved.added_lines = None;
            moved.removed_lines = None;
            activities.push(moved);
        }
        activities.sort_by(|a, b| a.path.cmp(&b.path));
        let activity_limit = activities.len() > 1024;
        activities.truncate(1024);
        // Long escaped names must also fit the durable per-record byte budget.
        let mut retained = 0;
        let mut bytes = 0usize;
        for activity in &activities {
            let Ok(encoded) = serde_json::to_vec(activity) else {
                break;
            };
            if bytes.saturating_add(encoded.len()) > 2 * 1024 * 1024 {
                break;
            }
            bytes += encoded.len();
            retained += 1;
        }
        let byte_limit = retained < activities.len();
        activities.truncate(retained);
        let changes = ObservedChanges {
            activities,
            notice: if self.before.limited || after.limited || activity_limit || byte_limit {
                "文件变更统计不完整：扫描范围、读取或内容预算受限".into()
            } else {
                String::new()
            },
        };
        // Seal attribution while registration still excludes a concurrent
        // observer. A late overlap must not escape the earlier snapshot check.
        let mut active = ACTIVE
            .get()
            .expect("observation registry initialized")
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        active.remove(&self.id);
        let overlapping = self.overlapping.load(Ordering::Acquire);
        drop(active);
        if overlapping {
            ObservedChanges {
                activities: Vec::new(),
                notice: "文件变更归属未知：观察范围无法核对或期间存在重叠操作".into(),
            }
        } else {
            changes
        }
    }
}
fn canonical_root(path: &Path) -> Option<PathBuf> {
    if !path.is_absolute() {
        return None;
    }
    let mut prefix = path;
    let mut suffix = Vec::new();
    loop {
        if let Ok(mut resolved) = fs::canonicalize(prefix) {
            for component in suffix.iter().rev() {
                resolved.push(component);
            }
            return Some(resolved);
        }
        // Resolve the existing ancestor so /sdcard aliases and parent segments
        // share one observation identity, even when the final file is new.
        let name = prefix.file_name()?.to_os_string();
        suffix.push(name);
        prefix = prefix.parent()?;
    }
}
impl Drop for Observation {
    fn drop(&mut self) {
        if let Some(active) = ACTIVE.get() {
            active
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .remove(&self.id);
        }
    }
}
fn identity(metadata: &Metadata) -> Option<(u64, u64)> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        Some((metadata.dev(), metadata.ino()))
    }
    #[cfg(not(unix))]
    {
        let _ = metadata;
        None
    }
}
fn snapshot(roots: &[PathBuf]) -> Snapshot {
    struct Budget {
        entries: usize,
        hash_bytes: u64,
        deadline: Instant,
    }
    fn scan(path: &Path, depth: usize, output: &mut Snapshot, budget: &mut Budget) -> bool {
        budget.entries += 1;
        if depth > 12
            || budget.entries > 1024
            || Instant::now() >= budget.deadline
            || path.as_os_str().to_string_lossy().len() > 4096
        {
            output.limited = true;
            return false;
        }
        let metadata = match fs::symlink_metadata(path) {
            Ok(value) => value,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return true,
            Err(_) => {
                output.limited = true;
                return false;
            }
        };
        if metadata.file_type().is_symlink() {
            output.limited = true;
            return false;
        }
        if metadata.is_dir() {
            let entries = match fs::read_dir(path) {
                Ok(entries) => entries,
                Err(_) => {
                    output.limited = true;
                    return false;
                }
            };
            let mut complete = true;
            for entry in entries {
                let entry = match entry {
                    Ok(entry) => entry,
                    Err(_) => {
                        complete = false;
                        continue;
                    }
                };
                if !scan(&entry.path(), depth + 1, output, budget) {
                    complete = false;
                }
                if budget.entries >= 1024 || Instant::now() >= budget.deadline {
                    output.limited = true;
                    complete = false;
                    break;
                }
            }
            return complete;
        }
        if !metadata.is_file() {
            output.limited = true;
            return false;
        }
        let mut digest = None;
        let mut text_lines = None;
        if metadata.len() <= 4 * 1024 * 1024 && metadata.len() <= budget.hash_bytes {
            let mut options = OpenOptions::new();
            options.read(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK);
            }
            if let Ok(mut file) = options.open(path) {
                if !file.metadata().ok().is_some_and(|opened| {
                    opened.is_file() && identity(&opened) == identity(&metadata)
                }) {
                    output.limited = true;
                    return false;
                }
                let mut hash = Sha256::new();
                let mut bytes = Vec::new();
                let mut total = 0u64;
                let mut block = [0u8; 8192];
                let mut valid = true;
                loop {
                    if Instant::now() >= budget.deadline {
                        valid = false;
                        break;
                    }
                    match file.read(&mut block) {
                        Ok(0) => break,
                        Ok(count) => {
                            total += count as u64;
                            if total > metadata.len() || total > budget.hash_bytes {
                                valid = false;
                                break;
                            }
                            hash.update(&block[..count]);
                            if metadata.len() <= 128 * 1024 {
                                bytes.extend_from_slice(&block[..count]);
                            }
                        }
                        Err(_) => {
                            valid = false;
                            break;
                        }
                    }
                }
                budget.hash_bytes = budget.hash_bytes.saturating_sub(total);
                if valid
                    && total == metadata.len()
                    && file.metadata().ok().is_some_and(|end| {
                        end.len() == metadata.len()
                            && end.modified().ok() == metadata.modified().ok()
                    })
                {
                    digest = Some(format!("{:x}", hash.finalize()));
                    if metadata.len() <= 128 * 1024 {
                        text_lines = std::str::from_utf8(&bytes)
                            .ok()
                            .map(|text| text.lines().count() as i64);
                    }
                } else {
                    output.limited = true;
                }
            } else {
                output.limited = true;
            }
        } else {
            output.limited = true;
        }
        output.files.insert(
            path.to_owned(),
            Stamp {
                size: metadata.len(),
                digest,
                text_lines,
                identity: identity(&metadata),
            },
        );
        true
    }
    let mut output = Snapshot {
        files: BTreeMap::new(),
        complete: HashSet::new(),
        limited: false,
    };
    let mut budget = Budget {
        entries: 0,
        hash_bytes: 16 * 1024 * 1024,
        deadline: Instant::now() + Duration::from_millis(300),
    };
    for root in roots {
        if scan(root, 0, &mut output, &mut budget) {
            output.complete.insert(root.clone());
        }
    }
    output
}
