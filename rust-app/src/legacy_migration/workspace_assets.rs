//! User workspace restoration is prepared before migration publication. The
//! owner prepublishes these files with no-overwrite/hash verification to the
//! fixed namespace workspace, then activates the typed mappings with its state.
use super::{AssetMapping, GenerationFile, MigrationPlan, WorkspaceDirectoryMapping, digest};
use crate::{
    legacy_import::{LegacySnapshot, LegacyWorkspaceSource},
    platform::secrets::SecretCipher,
};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Component, Path, PathBuf},
};

pub fn workspace_source_proofs(
    snapshot: &LegacySnapshot,
) -> Result<Vec<LegacyWorkspaceSource>, String> {
    let mut proofs = BTreeMap::new();
    for source in &snapshot.workspace_sources {
        if source.unix_mode.is_some_and(|mode| mode & !0o777 != 0) {
            return Err("旧工作区文件模式超出普通rwx位".into());
        }
        relative(Path::new(&source.relative_path))?;
        if source.owning_root != snapshot.source_root
            && Some(&source.owning_root) != snapshot.external_files_root.as_ref()
        {
            return Err("旧工作区来源不是明确提供的files或external根".into());
        }
        let key = (source.owning_root.clone(), source.relative_path.clone());
        if proofs.insert(key, source.clone()).is_some() {
            return Err("旧工作区清单包含重名项目".into());
        }
    }
    Ok(proofs.into_values().collect())
}

pub(super) fn assign_workspace(
    snapshot: &LegacySnapshot,
    plan: &mut MigrationPlan,
) -> Result<(), String> {
    let sources = workspace_source_proofs(snapshot)?;
    let mut assets = BTreeMap::new();
    for (index, asset) in plan.assets.iter().enumerate() {
        assets.insert(
            (asset.owning_root.clone(), asset.relative_path.clone()),
            index,
        );
    }
    if plan.source_fingerprint.len() != 64
        || !plan
            .source_fingerprint
            .bytes()
            .all(|b| b.is_ascii_hexdigit())
    {
        return Err("旧工作区迁移代指纹无效".into());
    }
    let mut owning_roots: BTreeSet<_> = sources.iter().map(|s| s.owning_root.clone()).collect();
    owning_roots.insert(
        snapshot
            .external_files_root
            .as_ref()
            .unwrap_or(&snapshot.source_root)
            .clone(),
    );
    for root in owning_roots {
        plan.workspace_directories.push(WorkspaceDirectoryMapping {
            owning_root: root.clone(),
            relative_path: String::new(),
            workspace_path: prefix(&plan.source_fingerprint, &root)?,
            unix_mode: Some(0o700),
        });
    }
    for source in sources {
        let workspace_path =
            prefix(&plan.source_fingerprint, &source.owning_root)?.join(&source.relative_path);
        relative(&workspace_path)?;
        if source.directory {
            plan.workspace_directories.push(WorkspaceDirectoryMapping {
                owning_root: source.owning_root,
                relative_path: source.relative_path,
                workspace_path,
                unix_mode: source.unix_mode,
            });
        } else {
            let index = assets
                .get(&(source.owning_root, source.relative_path))
                .ok_or("旧工作区文件缺少完整密封归档")?;
            plan.assets[*index].workspace_path = Some(workspace_path);
            plan.assets[*index].workspace_mode = source.unix_mode;
        }
    }
    Ok(())
}

fn prefix(fingerprint: &str, root: &Path) -> Result<PathBuf, String> {
    let bytes = serde_json::to_vec(root).map_err(|_| "旧工作区来源身份编码失败")?;
    Ok(PathBuf::from("legacy")
        .join(fingerprint)
        .join(digest(&bytes)))
}

/// Returned paths are relative to namespace workspace, NOT migration state.
/// All files are verified before returning a complete batch; no filesystem I/O
/// or namespace publication happens here. Caller must also precreate every
/// plan.workspace_directories entry, including empty directories.
pub fn prepare_workspace_assets(
    plan: &MigrationPlan,
    cipher: &dyn SecretCipher,
) -> Result<Vec<GenerationFile>, String> {
    let mut paths = BTreeSet::new();
    let mut total = 0u64;
    let mut files = Vec::new();
    let archives: BTreeMap<_, _> = plan
        .archives
        .iter()
        .map(|file| (&file.path, &file.bytes))
        .collect();
    for asset in &plan.assets {
        let Some(path) = &asset.workspace_path else {
            continue;
        };
        validate_target(plan, path)?;
        if !paths.insert(path.clone()) {
            return Err("旧工作区目标重名，未返回部分文件".into());
        }
        let archive = archives
            .get(&asset.archive_path)
            .ok_or("旧工作区密封原文缺失")?;
        let bytes = super::conversion::decode_archived_blob(archive, asset, cipher)?;
        total = total
            .checked_add(bytes.len() as u64)
            .ok_or("旧工作区长度溢出")?;
        if total > 256 * 1024 * 1024 {
            return Err("旧工作区超过256 MiB准备预算，未返回部分文件".into());
        }
        files.push(GenerationFile {
            path: path.clone(),
            bytes,
        });
    }
    for directory in &plan.workspace_directories {
        validate_target(plan, &directory.workspace_path)?;
        if !paths.insert(directory.workspace_path.clone()) {
            return Err("旧工作区文件与目录目标冲突".into());
        }
    }
    Ok(files)
}

fn validate_target(plan: &MigrationPlan, path: &Path) -> Result<(), String> {
    relative(path)?;
    if !path.starts_with(PathBuf::from("legacy").join(&plan.source_fingerprint)) {
        return Err("旧工作区目标不属于当前迁移代".into());
    }
    Ok(())
}

/// Map one recorded old absolute path, or one unambiguous old workspace-relative
/// path. Directory matches are exact: a root mapping never authorizes an
/// unrecorded protected old file. Callers join the result to their owned workspace.
pub fn resolve_workspace_path(
    assets: &[AssetMapping],
    directories: &[WorkspaceDirectoryMapping],
    requested: &Path,
) -> Result<Option<PathBuf>, String> {
    let requested = if requested == Path::new("~") || requested.as_os_str().is_empty() {
        PathBuf::new()
    } else if requested.to_str().is_some_and(|p| p.starts_with("~/")) {
        PathBuf::from(requested.to_str().unwrap().strip_prefix("~/").unwrap())
    } else {
        requested.to_owned()
    };
    if !requested.is_absolute() && !requested.as_os_str().is_empty() {
        relative(&requested)?;
    }
    if requested
        .components()
        .any(|c| matches!(c, Component::ParentDir))
    {
        return Err("旧工作区路径含越界片段".into());
    }
    let mut found = None;
    for asset in assets {
        let Some(target) = &asset.workspace_path else {
            continue;
        };
        if matches_source(&asset.owning_root, &asset.relative_path, &requested) {
            resolve_one(&mut found, target)?;
        }
    }
    for directory in directories {
        if matches_source(&directory.owning_root, &directory.relative_path, &requested) {
            resolve_one(&mut found, &directory.workspace_path)?;
        }
    }
    Ok(found)
}
fn matches_source(root: &Path, relative: &str, requested: &Path) -> bool {
    if requested.is_absolute() {
        root.join(relative) == requested
    } else {
        Path::new(relative) == requested
    }
}
fn resolve_one(found: &mut Option<PathBuf>, target: &Path) -> Result<(), String> {
    relative(target)?;
    if !target.starts_with("legacy") {
        return Err("旧工作区映射目标无效".into());
    }
    if found.as_ref().is_some_and(|previous| previous != target) {
        return Err("旧相对路径匹配多个明确来源，请使用完整旧路径".into());
    }
    *found = Some(target.to_owned());
    Ok(())
}
fn relative(path: &Path) -> Result<(), String> {
    if path.as_os_str().is_empty()
        || path
            .components()
            .any(|c| !matches!(c, Component::Normal(_)))
        || path.to_str().is_none_or(|p| p.contains(['\\', '\0']))
    {
        return Err("旧工作区映射不是安全相对路径".into());
    }
    Ok(())
}
