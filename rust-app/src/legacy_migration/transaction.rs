use super::{
    AssetMapping, GenerationFile, MigrationPlan, WorkspaceDirectoryMapping, digest, source_blobs,
};
use crate::legacy_import::{LegacySnapshot, scan_legacy_with_roots};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    io::{Read, Write},
    path::{Component, Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

mod archive_catalog;
pub use archive_catalog::{ArchiveCatalog, archive_catalog};
const MAX_FILE: u64 = 512 * 1024 * 1024;
const MAX_TOTAL: u64 = 1024 * 1024 * 1024;
const MAX_FILES: usize = 262_144;
const POINTER: &str = "active-generation.json";
const GENERATIONS: &str = "migrations/generations";

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct FileProof {
    path: PathBuf,
    bytes: u64,
    sha256: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Manifest {
    version: u32,
    id: String,
    source_fingerprint: String,
    previous_pointer_hash: Option<String>,
    files: Vec<FileProof>,
    assets: Vec<AssetMapping>,
    #[serde(default)]
    workspace_directories: Vec<WorkspaceDirectoryMapping>,
    #[serde(default)]
    plugin_layout: Vec<crate::plugin_assets::Layout>,
    retained_features: Vec<String>,
    warnings: Vec<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct ActivePointer {
    version: u32,
    id: String,
    manifest_sha256: String,
}
pub struct StagedMigration {
    pub id: String,
    /// Useful for the owner's read-only format/cross-reference validation.
    pub state_root: PathBuf,
    manifest_hash: String,
}
#[derive(Clone, Debug)]
pub struct CommitReceipt {
    pub id: String,
    pub state_root: PathBuf,
    pub source_fingerprint: String,
    pub durability_confirmed: bool,
    pub source_stable_after_commit: bool,
    pub warnings: Vec<String>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RecoveryState {
    Incomplete,
    Prepared,
    Committed,
    Active,
    Damaged,
}
#[derive(Clone, Debug)]
pub struct RecoveryEntry {
    pub id: String,
    pub state: RecoveryState,
    pub detail: String,
}

pub fn snapshot_fingerprint(snapshot: &LegacySnapshot) -> Result<String, String> {
    let mut proofs = BTreeMap::new();
    for blob in source_blobs(snapshot) {
        if digest(&blob.original_bytes) != blob.sha256 {
            return Err("旧数据候选hash失配".into());
        }
        let key = (blob.owning_root.clone(), blob.relative_path.clone());
        let proof = (blob.original_bytes.len() as u64, blob.sha256.clone());
        if proofs
            .insert(key, proof.clone())
            .is_some_and(|previous| previous != proof)
        {
            return Err("旧数据候选的同一来源出现冲突字节".into());
        }
        if blob.owning_root != snapshot.source_root
            && Some(&blob.owning_root) != snapshot.no_backup_root.as_ref()
            && Some(&blob.owning_root) != snapshot.external_files_root.as_ref()
        {
            return Err("旧数据候选包含未明确提供的拥有目录".into());
        }
    }
    let boundaries: Vec<_> = snapshot
        .conversations
        .iter()
        .map(|c| {
            (
                &c.id,
                c.generation,
                c.committed_log_bytes,
                c.observed_log_bytes,
            )
        })
        .collect();
    let proof = serde_json::to_vec(&(
        &snapshot.source_root,
        &snapshot.no_backup_root,
        &snapshot.external_files_root,
        proofs.into_iter().collect::<Vec<_>>(),
        super::workspace_source_proofs(snapshot)?,
        crate::plugin_assets::source_proofs(snapshot)?,
        boundaries,
    ))
    .map_err(|_| "旧数据来源清单编码失败")?;
    Ok(digest(&proof))
}
pub fn verify_snapshot(snapshot: &LegacySnapshot) -> Result<(), String> {
    let current = scan_legacy_with_roots(
        &snapshot.source_root,
        snapshot.no_backup_root.as_deref(),
        snapshot.external_files_root.as_deref(),
    )?;
    if snapshot_fingerprint(snapshot)? != snapshot_fingerprint(&current)? {
        return Err("旧数据在迁移期间改变；未覆盖原源，请重新预览和迁移".into());
    }
    Ok(())
}

/// The caller holds the actor migration lock and supplies the fully merged state.
/// No automatic merge/overwrite is performed by this filesystem transaction.
pub fn stage(
    state_root: &Path,
    snapshot: &LegacySnapshot,
    plan: &MigrationPlan,
    merged_files: &[GenerationFile],
) -> Result<StagedMigration, String> {
    if plan.source_fingerprint != snapshot_fingerprint(snapshot)? {
        return Err("迁移计划与只读候选不是同一代".into());
    }
    verify_snapshot(snapshot)?;
    let root = checked_root(state_root)?;
    let previous_pointer_hash = pointer_bytes(&root)?.map(|bytes| digest(&bytes));
    let mut all = Vec::new();
    for file in merged_files {
        relative(&file.path)?;
        if file.path.starts_with("migrations") || file.path == Path::new(POINTER) {
            return Err("代目录内禁止包含迁移控制文件".into());
        }
        all.push((
            PathBuf::from("state").join(&file.path),
            file.bytes.as_slice(),
        ));
    }
    for file in &plan.archives {
        relative(&file.path)?;
        if !file.path.starts_with("archive") {
            return Err("保密原文归档路径无效".into());
        }
        all.push((file.path.clone(), file.bytes.as_slice()));
    }
    if all.len() > MAX_FILES {
        return Err("迁移新代文件数量超过预算".into());
    }
    let mut seen = BTreeSet::new();
    let mut total = 0u64;
    for (path, bytes) in &all {
        if !seen.insert(path.clone()) {
            return Err("迁移新代包含重名文件，未开始写入".into());
        }
        if bytes.len() as u64 > MAX_FILE {
            return Err("迁移新代单文件超过512 MiB预算".into());
        }
        total = total
            .checked_add(bytes.len() as u64)
            .ok_or("迁移新代长度溢出")?;
        if total > MAX_TOTAL {
            return Err("迁移新代超过1 GiB总预算，拒绝部分提交".into());
        }
    }
    let generations = create_directory(&root, Path::new(GENERATIONS))?;
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "迁移时钟早于Unix纪元")?
        .as_nanos();
    let id = format!(
        "legacy-{}-{stamp}-{}",
        &plan.source_fingerprint[..16],
        std::process::id()
    );
    super::chat_archive::identity(&id)?;
    let generation = generations.join(&id);
    fs::create_dir(&generation).map_err(|_| "无法创建唯一迁移代目录")?;
    create_directory(&generation, Path::new("state"))?;
    let mut files = Vec::new();
    let mut directories = BTreeSet::new();
    directories.insert(generation.clone());
    for (path, bytes) in all {
        let parent = path.parent().ok_or("迁移文件没有父目录")?;
        let directory = create_directory(&generation, parent)?;
        directories.insert(directory);
        write_new(&generation, &path, bytes)?;
        files.push(FileProof {
            path,
            bytes: bytes.len() as u64,
            sha256: digest(bytes),
        });
    }
    crate::plugin_assets::apply_layout(&generation.join("state"), &plan.plugin_layout)?;
    let manifest = Manifest {
        version: 1,
        id: id.clone(),
        source_fingerprint: plan.source_fingerprint.clone(),
        previous_pointer_hash,
        files,
        assets: plan.assets.clone(),
        workspace_directories: plan.workspace_directories.clone(),
        plugin_layout: plan.plugin_layout.clone(),
        retained_features: plan.retained_features.clone(),
        warnings: plan.warnings.clone(),
    };
    let bytes = serde_json::to_vec(&manifest).map_err(|_| "迁移完成清单编码失败")?;
    if bytes.len() as u64 > MAX_FILE {
        return Err("迁移清单超过预算".into());
    }
    write_new(&generation, Path::new("prepared.json"), &bytes)?;
    sync_tree_directories(&generation, &directories)?;
    sync_dir(&generations)?;
    Ok(StagedMigration {
        id,
        state_root: generation.join("state"),
        manifest_hash: digest(&bytes),
    })
}

pub fn commit(
    state_root: &Path,
    snapshot: &LegacySnapshot,
    staged: &StagedMigration,
) -> Result<CommitReceipt, String> {
    let root = checked_root(state_root)?;
    super::chat_archive::identity(&staged.id)?;
    let generation = existing_directory(&root, &PathBuf::from(GENERATIONS).join(&staged.id))?;
    if staged.state_root != generation.join("state") {
        return Err("迁移提交目录与新代身份不一致".into());
    }
    let manifest_bytes = read_regular(&generation, Path::new("prepared.json"), MAX_FILE)?;
    if digest(&manifest_bytes) != staged.manifest_hash {
        return Err("迁移准备清单被改变".into());
    }
    let manifest: Manifest =
        serde_json::from_slice(&manifest_bytes).map_err(|_| "迁移准备清单损坏")?;
    if manifest.version != 1
        || manifest.id != staged.id
        || manifest.source_fingerprint != snapshot_fingerprint(snapshot)?
    {
        return Err("迁移提交身份或来源hash不一致".into());
    }
    validate_files(&generation, &manifest)?;
    verify_snapshot(snapshot)?;
    let current = pointer_bytes(&root)?.map(|bytes| digest(&bytes));
    if current != manifest.previous_pointer_hash {
        return Err("迁移期间已有新代提交，拒绝覆盖活动指针".into());
    }
    let completion = generation.join("complete.json");
    if completion.exists() {
        if read_regular(&generation, Path::new("complete.json"), MAX_FILE)? != manifest_bytes {
            return Err("迁移完成标记冲突".into());
        }
    } else {
        write_new(&generation, Path::new("complete.json"), &manifest_bytes)?;
    }
    sync_dir(&generation)?;
    // A complete but inactive generation is harmless after a crash at this boundary.
    verify_snapshot(snapshot)?;
    let pointer = ActivePointer {
        version: 1,
        id: staged.id.clone(),
        manifest_sha256: digest(&manifest_bytes),
    };
    let encoded = serde_json::to_vec(&pointer).map_err(|_| "迁移活动指针编码失败")?;
    let temp_name = PathBuf::from(format!(".active-{}.tmp", staged.id));
    write_new(&root, &temp_name, &encoded)?;
    if pointer_bytes(&root)?.map(|bytes| digest(&bytes)) != manifest.previous_pointer_hash {
        return Err("活动指针在提交边界改变，准备代保留".into());
    }
    replace(&root.join(&temp_name), &root.join(POINTER))?;
    // Publication already occurred. Errors after this point return a visible
    // receipt with warnings; they must never be described as an unchanged store.
    let mut warnings = manifest.warnings;
    let durability_confirmed = match sync_dir(&root) {
        Ok(()) => cfg!(unix),
        Err(error) => {
            warnings.push(format!("迁移指针已发布，但目录同步未确认：{error}"));
            false
        }
    };
    let source_stable_after_commit = match verify_snapshot(snapshot) {
        Ok(()) => true,
        Err(error) => {
            warnings.push(format!(
                "迁移已提交后发现旧源变化：{error}；原旧源保留，请检查后续新数据"
            ));
            false
        }
    };
    Ok(CommitReceipt {
        id: staged.id.clone(),
        state_root: generation.join("state"),
        source_fingerprint: manifest.source_fingerprint,
        durability_confirmed,
        source_stable_after_commit,
        warnings,
    })
}
pub fn resolve_active(state_root: &Path) -> Result<Option<PathBuf>, String> {
    let root = checked_root(state_root)?;
    let Some(bytes) = pointer_bytes(&root)? else {
        return Ok(None);
    };
    let pointer: ActivePointer =
        serde_json::from_slice(&bytes).map_err(|_| "迁移活动指针损坏；未回落到空状态")?;
    if pointer.version != 1 {
        return Err("迁移活动指针版本不受支持".into());
    }
    super::chat_archive::identity(&pointer.id)?;
    let generation = existing_directory(&root, &PathBuf::from(GENERATIONS).join(&pointer.id))?;
    let bytes = read_regular(&generation, Path::new("complete.json"), MAX_FILE)?;
    if digest(&bytes) != pointer.manifest_sha256 {
        return Err("迁移完成标记hash失配；未隐藏已有数据".into());
    }
    let manifest: Manifest = serde_json::from_slice(&bytes).map_err(|_| "迁移完成标记损坏")?;
    if manifest.version != 1 || manifest.id != pointer.id {
        return Err("迁移活动代身份不一致".into());
    }
    // State documents may legitimately change after activation. Manifest hashes
    // describe the import commit, not later user edits; Store validates live data.
    Ok(Some(existing_directory(&generation, Path::new("state"))?))
}
/// Repeating an import of the unchanged source is a no-op, even after the user
/// edits the new Rust state. Fresh encryption must not create false conflicts.
pub fn already_committed(
    state_root: &Path,
    source_fingerprint: &str,
) -> Result<Option<CommitReceipt>, String> {
    let root = checked_root(state_root)?;
    let Some(active) = resolve_active(&root)? else {
        return Ok(None);
    };
    let generation = active.parent().ok_or("活动迁移代缺少父目录")?;
    let bytes = read_regular(generation, Path::new("complete.json"), MAX_FILE)?;
    let manifest: Manifest = serde_json::from_slice(&bytes).map_err(|_| "活动迁移完成标记损坏")?;
    if manifest.source_fingerprint != source_fingerprint {
        return Ok(None);
    }
    let mut warnings = manifest.warnings;
    let durability_confirmed = match sync_dir(&root) {
        Ok(()) => cfg!(unix),
        Err(error) => {
            warnings.push(format!("已有迁移代活动目录同步未确认：{error}"));
            false
        }
    };
    Ok(Some(CommitReceipt {
        id: manifest.id,
        state_root: active,
        source_fingerprint: manifest.source_fingerprint,
        durability_confirmed,
        source_stable_after_commit: true,
        warnings,
    }))
}
pub fn recover(state_root: &Path) -> Result<Vec<RecoveryEntry>, String> {
    let root = checked_root(state_root)?;
    let active = resolve_active(&root)?;
    if !root.join(GENERATIONS).exists() {
        return Ok(Vec::new());
    }
    let generations = existing_directory(&root, Path::new(GENERATIONS))?;
    let mut entries = Vec::new();
    for (index, entry) in fs::read_dir(&generations)
        .map_err(|_| "无法枚举迁移恢复目录")?
        .enumerate()
    {
        if index >= 1024 {
            return Err("迁移恢复代数量超过预算".into());
        }
        let entry = entry.map_err(|_| "迁移恢复目录项损坏")?;
        let id = entry
            .file_name()
            .into_string()
            .map_err(|_| "迁移代文件名不是UTF8")?;
        super::chat_archive::identity(&id)?;
        let generation = existing_directory(&generations, Path::new(&id))?;
        let state = if active.as_ref() == Some(&generation.join("state")) {
            RecoveryState::Active
        } else if generation.join("complete.json").exists() {
            RecoveryState::Committed
        } else if generation.join("prepared.json").exists() {
            RecoveryState::Prepared
        } else {
            RecoveryState::Incomplete
        };
        let (state, detail) = if matches!(state, RecoveryState::Prepared | RecoveryState::Committed)
        {
            let marker = if state == RecoveryState::Committed {
                "complete.json"
            } else {
                "prepared.json"
            };
            let result = read_regular(&generation, Path::new(marker), MAX_FILE).and_then(|bytes| {
                let manifest: Manifest =
                    serde_json::from_slice(&bytes).map_err(|_| "迁移恢复清单损坏")?;
                if manifest.version != 1 || manifest.id != id {
                    return Err("迁移恢复身份不一致".into());
                }
                if state == RecoveryState::Prepared {
                    validate_files(&generation, &manifest)?;
                }
                Ok(())
            });
            match result {
                Ok(()) => (state, "保留供显式检查；不会自动激活或删除".into()),
                Err(error) => (RecoveryState::Damaged, error),
            }
        } else {
            (state, "原数据与迁移代均保留".into())
        };
        entries.push(RecoveryEntry { id, state, detail });
    }
    entries.sort_by(|a, b| a.id.cmp(&b.id));
    Ok(entries)
}
fn validate_files(generation: &Path, manifest: &Manifest) -> Result<(), String> {
    crate::plugin_assets::verify_layout(&generation.join("state"), &manifest.plugin_layout)?;
    if manifest.files.len() > MAX_FILES {
        return Err("迁移完成清单文件数量超过预算".into());
    }
    let mut seen = BTreeSet::new();
    let mut total = 0u64;
    for proof in &manifest.files {
        relative(&proof.path)?;
        if !proof.path.starts_with("state") && !proof.path.starts_with("archive") {
            return Err("迁移完成清单文件越界".into());
        }
        if !seen.insert(&proof.path) || proof.bytes > MAX_FILE {
            return Err("迁移完成清单重复或超过单文件预算".into());
        }
        let bytes = read_regular(generation, &proof.path, MAX_FILE)?;
        total = total
            .checked_add(bytes.len() as u64)
            .ok_or("迁移验证长度溢出")?;
        if total > MAX_TOTAL || bytes.len() as u64 != proof.bytes || digest(&bytes) != proof.sha256
        {
            return Err("迁移目标hash或长度验证失败".into());
        }
    }
    let mut actual = BTreeSet::new();
    for directory in ["state", "archive"] {
        if generation.join(directory).exists() {
            inventory(generation, Path::new(directory), 0, &mut actual)?;
        }
    }
    let expected: BTreeSet<_> = manifest
        .files
        .iter()
        .map(|proof| proof.path.clone())
        .collect();
    if actual != expected {
        return Err("迁移目标出现未列入完成清单的文件或缺少文件".into());
    }
    Ok(())
}
fn inventory(
    root: &Path,
    directory: &Path,
    depth: usize,
    files: &mut BTreeSet<PathBuf>,
) -> Result<(), String> {
    if depth > 64 {
        return Err("迁移目标目录深度超过预算".into());
    }
    let path = existing_directory(root, directory)?;
    for entry in fs::read_dir(path).map_err(|_| "无法枚举迁移目标文件")? {
        let entry = entry.map_err(|_| "迁移目标目录项无效")?;
        let relative_path = directory.join(entry.file_name());
        let metadata = fs::symlink_metadata(entry.path()).map_err(|_| "无法复核迁移目标类型")?;
        if metadata.file_type().is_symlink() {
            return Err("迁移目标存在符号链接".into());
        }
        if metadata.is_dir() {
            inventory(root, &relative_path, depth + 1, files)?;
        } else if metadata.is_file() {
            if files.len() >= MAX_FILES {
                return Err("迁移目标文件数超过预算".into());
            }
            files.insert(relative_path);
        } else {
            return Err("迁移目标存在非普通文件".into());
        }
    }
    Ok(())
}
fn pointer_bytes(root: &Path) -> Result<Option<Vec<u8>>, String> {
    match fs::symlink_metadata(root.join(POINTER)) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(_) => Err("无法检查迁移活动指针".into()),
        Ok(_) => read_regular(root, Path::new(POINTER), 64 * 1024).map(Some),
    }
}
pub(crate) fn relative(path: &Path) -> Result<(), String> {
    if path.as_os_str().is_empty()
        || path
            .components()
            .any(|c| !matches!(c, Component::Normal(_)))
    {
        return Err("迁移路径必须是正常相对路径".into());
    }
    Ok(())
}
fn checked_root(path: &Path) -> Result<PathBuf, String> {
    let metadata = fs::symlink_metadata(path).map_err(|_| "无法检查迁移状态根目录")?;
    if !metadata.is_dir() || metadata.file_type().is_symlink() {
        return Err("迁移状态根目录不是普通目录".into());
    }
    fs::canonicalize(path).map_err(|_| "无法解析迁移状态根目录".into())
}
fn existing_directory(root: &Path, relative_path: &Path) -> Result<PathBuf, String> {
    relative(relative_path)?;
    let mut path = root.to_owned();
    for component in relative_path.components() {
        path.push(component);
        let metadata = fs::symlink_metadata(&path).map_err(|_| "迁移目录缺失")?;
        if !metadata.is_dir()
            || metadata.file_type().is_symlink()
            || fs::canonicalize(&path).map_err(|_| "无法复核迁移目录")? != path
        {
            return Err("迁移目录包含符号链接或类型冲突".into());
        }
    }
    Ok(path)
}
fn create_directory(root: &Path, relative_path: &Path) -> Result<PathBuf, String> {
    if relative_path.as_os_str().is_empty() {
        return Ok(root.to_owned());
    }
    relative(relative_path)?;
    let mut path = root.to_owned();
    for component in relative_path.components() {
        path.push(component);
        match fs::create_dir(&path) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(_) => return Err("无法创建迁移子目录".into()),
        }
        let metadata = fs::symlink_metadata(&path).map_err(|_| "无法复核迁移子目录")?;
        if !metadata.is_dir()
            || metadata.file_type().is_symlink()
            || fs::canonicalize(&path).map_err(|_| "迁移子目录解析失败")? != path
        {
            return Err("迁移子目录跳转到其它位置".into());
        }
    }
    Ok(path)
}
fn write_new(root: &Path, relative_path: &Path, bytes: &[u8]) -> Result<(), String> {
    relative(relative_path)?;
    if let Some(parent) = relative_path.parent().filter(|p| !p.as_os_str().is_empty()) {
        existing_directory(root, parent)?;
    }
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
        .open(root.join(relative_path))
        .map_err(|_| "无法创建迁移独立文件")?;
    file.write_all(bytes)
        .map_err(|_| "迁移文件写入失败，未切换活动代")?;
    file.sync_all()
        .map_err(|_| "迁移文件同步失败，未切换活动代".into())
}
pub(crate) fn read_regular(root: &Path, relative_path: &Path, max: u64) -> Result<Vec<u8>, String> {
    relative(relative_path)?;
    let root = checked_root(root)?;
    if let Some(parent) = relative_path.parent().filter(|p| !p.as_os_str().is_empty()) {
        existing_directory(&root, parent)?;
    }
    let path = root.join(relative_path);
    let named = fs::symlink_metadata(&path).map_err(|_| "迁移文件缺失")?;
    if !named.is_file() || named.file_type().is_symlink() || named.len() > max {
        return Err("迁移文件类型或长度预算无效".into());
    }
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK);
    }
    let file = options.open(&path).map_err(|_| "迁移文件打开失败")?;
    let opened = file.metadata().map_err(|_| "迁移文件状态读取失败")?;
    if !same_file(&named, &opened) || !opened.is_file() {
        return Err("迁移文件在打开期间变化".into());
    }
    let mut bytes = Vec::new();
    (&file)
        .take(max + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "迁移文件读取失败")?;
    let after = file.metadata().map_err(|_| "迁移文件状态复核失败")?;
    let named_after = fs::symlink_metadata(&path).map_err(|_| "迁移文件读取期间消失")?;
    if bytes.len() as u64 > max
        || bytes.len() as u64 != opened.len()
        || !same_file(&opened, &after)
        || !same_file(&after, &named_after)
    {
        return Err("迁移文件在读取期间变化".into());
    }
    Ok(bytes)
}
fn same_file(a: &fs::Metadata, b: &fs::Metadata) -> bool {
    if b.file_type().is_symlink()
        || !b.is_file()
        || a.len() != b.len()
        || a.modified().ok() != b.modified().ok()
    {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        a.dev() == b.dev() && a.ino() == b.ino()
    }
    #[cfg(not(unix))]
    {
        true
    }
}
fn sync_tree_directories(root: &Path, leaves: &BTreeSet<PathBuf>) -> Result<(), String> {
    let mut paths = BTreeSet::new();
    for leaf in leaves {
        let mut path = leaf.as_path();
        loop {
            paths.insert(path.to_owned());
            if path == root {
                break;
            }
            path = path.parent().ok_or("迁移同步目录越界")?;
        }
    }
    let mut paths: Vec<_> = paths.into_iter().collect();
    paths.sort_by_key(|p| std::cmp::Reverse(p.components().count()));
    for path in paths {
        sync_dir(&path)?;
    }
    Ok(())
}
fn sync_dir(path: &Path) -> Result<(), String> {
    #[cfg(unix)]
    {
        fs::File::open(path)
            .and_then(|file| file.sync_all())
            .map_err(|_| "迁移目录同步失败".into())
    }
    #[cfg(not(unix))]
    {
        let _ = path;
        Ok(())
    }
}
fn replace(from: &Path, to: &Path) -> Result<(), String> {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        #[link(name = "kernel32")]
        unsafe extern "system" {
            fn MoveFileExW(from: *const u16, to: *const u16, flags: u32) -> i32;
        }
        let from: Vec<_> = from.as_os_str().encode_wide().chain(Some(0)).collect();
        let to: Vec<_> = to.as_os_str().encode_wide().chain(Some(0)).collect();
        if unsafe { MoveFileExW(from.as_ptr(), to.as_ptr(), 0x1 | 0x8) } == 0 {
            Err("迁移活动指针原子替换失败".into())
        } else {
            Ok(())
        }
    }
    #[cfg(not(windows))]
    {
        fs::rename(from, to).map_err(|_| "迁移活动指针原子替换失败".into())
    }
}
