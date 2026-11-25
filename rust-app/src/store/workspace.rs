//! User workspace assets are materialized before generation publication.
use super::*;
use crate::legacy_migration::{AssetMapping, MigrationPlan, WorkspaceDirectoryMapping};

pub(super) fn select_workspace(
    private: PathBuf,
    shared_files: Option<&Path>,
) -> Result<PathBuf, String> {
    let Some(files) = shared_files else {
        return Ok(private);
    };
    #[cfg(target_os = "android")]
    if let Some(external) =
        crate::platform::android::paths().and_then(|paths| paths.external_files_dir)
    {
        return fs::canonicalize(&external)
            .map_err(|error| fs_error("解析共用工作区", &external, error));
    }
    Ok(files.to_owned())
}

pub(crate) fn private_workspace_for(workspace: &Path) -> PathBuf {
    #[cfg(target_os = "android")]
    if let Some(paths) =
        crate::platform::android::paths().filter(|paths| paths.package_name == "com.suixing.agent")
    {
        let shared = paths
            .external_files_dir
            .as_deref()
            .unwrap_or(&paths.files_dir);
        if let Ok(shared) = fs::canonicalize(shared)
            && shared == workspace
        {
            let files = fs::canonicalize(&paths.files_dir).unwrap_or(paths.files_dir);
            let private = files.join("rust-state/workspace");
            return if private.is_dir() {
                private
            } else {
                files.join("workspace")
            };
        }
    }
    workspace.to_owned()
}

#[cfg(target_os = "android")]
pub(crate) fn managed_file_path(files: &Path, path: &Path) -> bool {
    let Some(relative) = path.strip_prefix(files).ok() else {
        return files.parent().is_some_and(|app| {
            ["databases", "shared_prefs"]
                .iter()
                .any(|name| path.starts_with(app.join(name)))
        });
    };
    if relative.starts_with("rust-state") {
        return !relative.starts_with("rust-state/workspace");
    }
    [
        "chats",
        "datastore",
        "jobs",
        "plugins",
        "assistants",
        "linux",
        "subagent_records",
        "deleted-usage",
    ]
    .iter()
    .any(|name| relative.starts_with(name))
        || [
            "todos.json",
            "goal.json",
            "skill-state.json",
            "skill-origin.json",
            "schedules.json",
            "mcp.json",
            "memory.json",
            "profile.md",
            "profile.state.json",
            "usage.json",
            "perf.json",
            "drawer-order",
        ]
        .iter()
        .any(|name| relative == Path::new(name))
}

#[cfg(all(test, not(target_os = "android")))]
#[test]
fn shared_file_assets_uses_compose_workspace_and_preserves_old_files() {
    let files =
        std::env::temp_dir().join(format!("jagent-files-{}", crate::controller::unique_id()));
    let state = files.join("rust-state");
    fs::create_dir_all(state.join("workspace")).unwrap();
    fs::write(files.join("shared.txt"), "Compose").unwrap();
    fs::write(state.join("workspace/old.txt"), "Rust").unwrap();

    let store = Store::open_with_shared_files(state, Some(files.clone())).unwrap();
    let root = fs::canonicalize(&files).unwrap();
    let (_, entries) = store.list_files(PathBuf::new()).unwrap();
    assert_eq!(store.workspace(), root);
    assert!(entries.iter().any(|entry| entry.name == "shared.txt"));
    assert!(
        entries
            .iter()
            .any(|entry| entry.path == store.private_workspace())
    );
    assert_eq!(
        store.preview_file("shared.txt".into()).unwrap().1,
        "Compose"
    );
    assert_eq!(
        store
            .preview_file(store.private_workspace().join("old.txt"))
            .unwrap()
            .1,
        "Rust"
    );
    assert!(!files.join("old.txt").exists());

    fs::remove_dir_all(files).unwrap();
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub(crate) struct WorkspaceMappings {
    pub assets: Vec<AssetMapping>,
    pub directories: Vec<WorkspaceDirectoryMapping>,
}
impl WorkspaceMappings {
    pub(crate) fn validate(&self) -> Result<(), String> {
        if self.assets.len().saturating_add(self.directories.len()) > 131072 {
            return Err("工作区映射数量超限".into());
        }
        let mut sources = HashSet::new();
        let mut targets = HashSet::new();
        for (root, relative, target) in self
            .assets
            .iter()
            .map(|a| (&a.owning_root, &a.relative_path, a.workspace_path.as_ref()))
            .chain(
                self.directories
                    .iter()
                    .map(|d| (&d.owning_root, &d.relative_path, Some(&d.workspace_path))),
            )
        {
            if !root.is_absolute()
                || relative.len() > 4096
                || relative.contains('\0')
                || !sources.insert((root, relative))
            {
                return Err("工作区来源映射无效或重复".into());
            }
            if !Path::new(relative).as_os_str().is_empty() {
                normal(Path::new(relative))?;
            }
            let target = target.ok_or("工作区映射没有目标")?;
            normal(target)?;
            if !target.starts_with("legacy") || !targets.insert(target) {
                return Err("工作区目标映射无效或重复".into());
            }
        }
        for asset in &self.assets {
            if asset.sha256.len() != 64
                || !asset.sha256.bytes().all(|c| c.is_ascii_hexdigit())
                || asset.bytes > 256 * 1024 * 1024
                || asset.workspace_mode.is_some_and(|m| m & !0o777 != 0)
            {
                return Err("工作区文件hash、长度或普通模式无效".into());
            }
        }
        Ok(())
    }
}
fn normal(path: &Path) -> Result<(), String> {
    if path.as_os_str().is_empty()
        || path
            .components()
            .any(|p| !matches!(p, std::path::Component::Normal(_)))
    {
        return Err("工作区映射必须是普通相对路径".into());
    }
    Ok(())
}
pub(crate) fn alias_for_workspace(
    workspace: &Path,
    requested: &Path,
) -> Result<Option<PathBuf>, String> {
    if requested.starts_with(workspace)
        || requested == Path::new(".")
        || requested == Path::new("~")
        || requested.as_os_str().is_empty()
    {
        return Ok(None);
    }
    let requested = requested.strip_prefix("~").unwrap_or(requested);
    if !requested.is_absolute() {
        match fs::symlink_metadata(workspace.join(requested)) {
            Ok(_) => return Ok(None),
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(_) => return Err("无法核对当前工作区路径".into()),
        }
    }
    let private = private_workspace_for(workspace);
    if private != workspace && requested.starts_with(&private) {
        return Ok(None);
    }
    let namespace = private.parent().ok_or("工作区namespace缺失")?;
    let active =
        crate::legacy_migration::resolve_active(namespace)?.unwrap_or_else(|| namespace.to_owned());
    let mapping = optional_json::<WorkspaceMappings>(
        &active,
        &active.join("workspace-mappings.json"),
        32 * 1024 * 1024,
    )?
    .unwrap_or_default();
    mapping.validate()?;
    Ok(crate::legacy_migration::resolve_workspace_path(
        &mapping.assets,
        &mapping.directories,
        requested,
    )?
    .map(|path| private.join(path)))
}
pub(crate) fn publish_workspace(
    workspace: &Path,
    plan: &MigrationPlan,
    cipher: &dyn SecretCipher,
) -> Result<(), String> {
    let files = crate::legacy_migration::prepare_workspace_assets(plan, cipher)?;
    #[cfg(unix)]
    {
        unix::publish(workspace, plan, &files)
    }
    #[cfg(not(unix))]
    {
        if !files.is_empty() || !plan.workspace_directories.is_empty() {
            return Err("当前平台未接入普通Unix工作区资产恢复".into());
        }
        let _ = workspace;
        Ok(())
    }
}
#[cfg(unix)]
mod unix {
    use super::*;
    use std::{
        ffi::CString,
        os::{
            fd::{AsRawFd, FromRawFd, OwnedFd},
            unix::ffi::OsStrExt,
        },
    };
    fn fd(raw: i32) -> Result<OwnedFd, String> {
        if raw < 0 {
            Err("旧工作区文件或目录访问失败，活动代尚未切换".into())
        } else {
            Ok(unsafe { OwnedFd::from_raw_fd(raw) })
        }
    }
    fn name(value: &std::ffi::OsStr) -> Result<CString, String> {
        CString::new(value.as_bytes()).map_err(|_| "工作区路径含NUL".into())
    }
    fn directory(root: &OwnedFd, path: &Path) -> Result<OwnedFd, String> {
        let mut current = fd(unsafe { libc::fcntl(root.as_raw_fd(), libc::F_DUPFD_CLOEXEC, 0) })?;
        for component in path.components() {
            let std::path::Component::Normal(part) = component else {
                return Err("工作区目录含越界片段".into());
            };
            let part = name(part)?;
            let created = unsafe { libc::mkdirat(current.as_raw_fd(), part.as_ptr(), 0o700) };
            if created != 0 && io::Error::last_os_error().raw_os_error() != Some(libc::EEXIST) {
                return Err("无法预建旧工作区目录，活动代尚未切换".into());
            }
            current = fd(unsafe {
                libc::openat(
                    current.as_raw_fd(),
                    part.as_ptr(),
                    libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
                )
            })?;
        }
        Ok(current)
    }
    fn sync(directory: &OwnedFd) -> Result<(), String> {
        if unsafe { libc::fsync(directory.as_raw_fd()) } != 0 {
            return Err("旧工作区目录同步未确认，活动代尚未切换".into());
        }
        Ok(())
    }
    pub fn publish(
        workspace: &Path,
        plan: &MigrationPlan,
        files: &[crate::legacy_migration::GenerationFile],
    ) -> Result<(), String> {
        let root_name = name(workspace.as_os_str())?;
        let root = fd(unsafe {
            libc::open(
                root_name.as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
            )
        })?;
        let mut restore = Vec::new();
        let mut directories = plan.workspace_directories.iter().collect::<Vec<_>>();
        directories.sort_by_key(|d| d.workspace_path.components().count());
        for entry in directories {
            normal(&entry.workspace_path)?;
            let target = directory(&root, &entry.workspace_path)?;
            // Hold this exact directory instance until its descendants are durable.
            let mode = entry.unix_mode.unwrap_or(0o700);
            if mode & !0o777 != 0 {
                return Err("旧目录模式含特殊位".into());
            }
            if unsafe { libc::fchmod(target.as_raw_fd(), 0o700) } != 0 {
                return Err("旧目录准备模式设置失败".into());
            }
            restore.push((entry.workspace_path.clone(), target, mode));
        }
        for entry in files {
            normal(&entry.path)?;
            let parent = directory(&root, entry.path.parent().ok_or("旧文件父目录缺失")?)?;
            let final_name = name(entry.path.file_name().ok_or("旧文件名缺失")?)?;
            let temporary_name = CString::new(format!(
                ".legacy-{}-{}.tmp",
                std::process::id(),
                crate::controller::unique_id()
            ))
            .map_err(|_| "旧文件暂存名无效")?;
            let mut temporary = File::from(fd(unsafe {
                libc::openat(
                    parent.as_raw_fd(),
                    temporary_name.as_ptr(),
                    libc::O_WRONLY
                        | libc::O_CREAT
                        | libc::O_EXCL
                        | libc::O_NOFOLLOW
                        | libc::O_CLOEXEC,
                    0o600,
                )
            })?);
            struct Temporary<'a> {
                parent: &'a OwnedFd,
                name: &'a CString,
            }
            impl Drop for Temporary<'_> {
                fn drop(&mut self) {
                    unsafe {
                        libc::unlinkat(self.parent.as_raw_fd(), self.name.as_ptr(), 0);
                    }
                }
            }
            let _cleanup = Temporary {
                parent: &parent,
                name: &temporary_name,
            };
            temporary
                .write_all(&entry.bytes)
                .and_then(|_| temporary.sync_all())
                .map_err(|_| "旧工作区暂存文件同步失败，活动代尚未切换")?;
            let linked = unsafe {
                libc::linkat(
                    parent.as_raw_fd(),
                    temporary_name.as_ptr(),
                    parent.as_raw_fd(),
                    final_name.as_ptr(),
                    0,
                )
            };
            let file = if linked < 0 {
                if io::Error::last_os_error().raw_os_error() != Some(libc::EEXIST) {
                    return Err("旧文件预落失败，活动代尚未切换".into());
                }
                let existing = fd(unsafe {
                    libc::openat(
                        parent.as_raw_fd(),
                        final_name.as_ptr(),
                        libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_NONBLOCK | libc::O_CLOEXEC,
                    )
                })?;
                let mut opened = File::from(existing);
                let metadata = opened.metadata().map_err(|_| "旧目标文件状态不可用")?;
                if !metadata.is_file() || metadata.len() != entry.bytes.len() as u64 {
                    return Err("旧目标文件冲突，未覆盖".into());
                }
                let mut bytes = Vec::new();
                Read::by_ref(&mut opened)
                    .take(metadata.len() + 1)
                    .read_to_end(&mut bytes)
                    .map_err(|_| "旧目标hash核对失败")?;
                if crate::legacy_migration::digest(&bytes)
                    != crate::legacy_migration::digest(&entry.bytes)
                {
                    return Err("旧目标hash冲突，未覆盖".into());
                }
                opened
            } else {
                temporary
            };
            let mode = plan
                .assets
                .iter()
                .find(|a| a.workspace_path.as_ref() == Some(&entry.path))
                .and_then(|a| a.workspace_mode)
                .unwrap_or(0o600);
            if mode & !0o777 != 0
                || unsafe { libc::fchmod(file.as_raw_fd(), mode as libc::mode_t) } != 0
            {
                return Err("旧文件普通模式恢复失败，活动代尚未切换".into());
            }
            file.sync_all().map_err(|_| "旧文件普通模式同步失败")?;
            sync(&parent)?;
        }
        restore.sort_by_key(|(path, _, _)| std::cmp::Reverse(path.components().count()));
        for (_, directory, mode) in restore {
            if unsafe { libc::fchmod(directory.as_raw_fd(), mode as libc::mode_t) } != 0 {
                return Err("旧目录普通模式恢复失败，活动代尚未切换".into());
            }
            sync(&directory)?;
        }
        sync(&root)
    }
}
