//! File-backed plugin packages and legacy directory restoration. This module
//! never executes an installer/script while importing or restoring a plugin.
pub(crate) mod archive;
pub(crate) mod bundled;
pub(crate) mod runtime;
use crate::{
    network::CancelToken,
    platform::secrets::{SecretCipher, StoredSecret},
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Component, Path, PathBuf},
};

pub(crate) const MAX_BYTES: u64 = 256 * 1024 * 1024;
pub(crate) const MAX_NODES: usize = 4096;
pub(crate) const TRASH_DAYS_MS: i64 = 7 * 24 * 60 * 60 * 1000;
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub(crate) struct Layout {
    pub path: PathBuf,
    pub directory: bool,
    pub mode: u32,
    pub bytes: u64,
    pub sha256: Option<String>,
}
pub(crate) struct Tree {
    pub layout: Vec<Layout>,
    pub files: BTreeMap<PathBuf, Vec<u8>>,
    pub warnings: Vec<String>,
}
impl std::fmt::Debug for Tree {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PluginTree")
            .field("layout", &self.layout)
            .field("warnings", &self.warnings)
            .finish()
    }
}
pub(crate) fn hash(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}
pub(crate) fn relative(path: &Path) -> Result<(), String> {
    if path.as_os_str().is_empty()
        || path.as_os_str().to_string_lossy().len() > 4096
        || path.components().count() > 32
        || !path
            .components()
            .all(|part| matches!(part, Component::Normal(_)))
        || path.components().any(|part| match part {
            Component::Normal(value) => value
                .to_str()
                .is_none_or(|value| value.is_empty() || value.contains(['\0', '\\', ':'])),
            _ => true,
        })
    {
        return Err("插件包路径不是安全相对路径".into());
    }
    Ok(())
}
pub(crate) fn validate_layout(layout: &[Layout]) -> Result<(), String> {
    if layout.len() > MAX_NODES {
        return Err("插件文件与目录超过4096项预算".into());
    }
    let mut seen = BTreeSet::new();
    let mut total = 0u64;
    for node in layout {
        relative(&node.path)?;
        if !seen.insert(node.path.clone()) || node.mode & !0o777 != 0 {
            return Err("插件布局重名或权限含特殊位".into());
        }
        if node.directory {
            if node.bytes != 0 || node.sha256.is_some() {
                return Err("插件目录不能带文件内容".into());
            }
        } else {
            if node.bytes > MAX_BYTES
                || node.sha256.as_ref().is_none_or(|hash| {
                    hash.len() != 64 || !hash.bytes().all(|byte| byte.is_ascii_hexdigit())
                })
            {
                return Err("插件文件证明无效".into());
            }
            total = total
                .checked_add(node.bytes)
                .filter(|total| *total <= MAX_BYTES)
                .ok_or("插件文件合计超过256 MiB")?;
        }
    }
    for node in layout {
        for parent in node
            .path
            .ancestors()
            .skip(1)
            .filter(|path| !path.as_os_str().is_empty())
        {
            if layout
                .iter()
                .any(|candidate| candidate.path == parent && !candidate.directory)
            {
                return Err("插件文件同时被用作目录".into());
            }
        }
    }
    Ok(())
}
impl Tree {
    pub(crate) fn validate(&self) -> Result<(), String> {
        validate_layout(&self.layout)?;
        let expected: BTreeSet<_> = self
            .layout
            .iter()
            .filter(|node| !node.directory)
            .map(|node| node.path.clone())
            .collect();
        if expected != self.files.keys().cloned().collect() {
            return Err("插件文件与布局清单不一致".into());
        }
        for node in self.layout.iter().filter(|node| !node.directory) {
            let bytes = &self.files[&node.path];
            if bytes.len() as u64 != node.bytes || Some(hash(bytes)) != node.sha256 {
                return Err("插件文件内容与证明不一致".into());
            }
        }
        Ok(())
    }
    pub(crate) fn under(&self, prefix: &Path) -> Result<Self, String> {
        relative(prefix)?;
        let mut layout = vec![Layout {
            path: prefix.to_owned(),
            directory: true,
            mode: 0o700,
            bytes: 0,
            sha256: None,
        }];
        layout.extend(self.layout.iter().map(|node| {
            let mut node = node.clone();
            node.path = prefix.join(&node.path);
            node
        }));
        let result = Self {
            layout,
            files: self
                .files
                .iter()
                .map(|(path, bytes)| (prefix.join(path), bytes.clone()))
                .collect(),
            warnings: self.warnings.clone(),
        };
        result.validate()?;
        Ok(result)
    }
}

/// A second inventory supplements the original byte archives with empty dirs
/// and rwx modes. It is included in the owning legacy source fingerprint.
pub(crate) fn scan_source(
    snapshot: &mut crate::legacy_import::LegacySnapshot,
) -> Result<(), String> {
    let root = snapshot.source_root.clone();
    let tree = match fs::symlink_metadata(root.join("plugins")) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(_) => return Err("旧插件目录状态无法确认".into()),
        Ok(_) => scan_tree(&root, Path::new("plugins"), true, &CancelToken::new())?,
    };
    snapshot.plugin_sources = tree
        .layout
        .iter()
        .map(|node| crate::legacy_import::LegacyWorkspaceSource {
            owning_root: root.clone(),
            relative_path: node.path.to_string_lossy().into_owned(),
            directory: node.directory,
            unix_mode: Some(node.mode),
        })
        .collect();
    let mut blobs: BTreeSet<_> = snapshot
        .assets
        .iter()
        .map(|blob| blob.relative_path.clone())
        .collect();
    blobs.extend(
        snapshot
            .documents
            .iter()
            .map(|document| document.source.relative_path.clone()),
    );
    for (path, bytes) in tree.files {
        let path = path.to_string_lossy().into_owned();
        if !blobs.contains(&path) {
            snapshot.assets.push(crate::legacy_import::LegacyBlob {
                owning_root: root.clone(),
                relative_path: path,
                sha256: hash(&bytes),
                original_bytes: bytes,
            });
        }
    }
    Ok(())
}
pub(crate) fn source_proofs(
    snapshot: &crate::legacy_import::LegacySnapshot,
) -> Result<Vec<crate::legacy_import::LegacyWorkspaceSource>, String> {
    let mut proofs = BTreeMap::new();
    for source in &snapshot.plugin_sources {
        relative(Path::new(&source.relative_path))?;
        if source.owning_root != snapshot.source_root
            || !Path::new(&source.relative_path).starts_with("plugins")
            || source.unix_mode.is_none_or(|mode| mode & !0o777 != 0)
        {
            return Err("旧插件布局所属目录或权限无效".into());
        }
        if proofs
            .insert(source.relative_path.clone(), source.clone())
            .is_some()
        {
            return Err("旧插件布局包含重复路径".into());
        }
    }
    Ok(proofs.into_values().collect())
}
pub(crate) fn from_legacy(
    snapshot: &crate::legacy_import::LegacySnapshot,
    cipher: &dyn SecretCipher,
) -> Result<Tree, String> {
    let proofs = source_proofs(snapshot)?;
    let blobs: BTreeMap<_, _> = crate::legacy_migration::source_blobs(snapshot)
        .into_iter()
        .filter(|blob| blob.owning_root == snapshot.source_root)
        .map(|blob| (blob.relative_path.clone(), blob))
        .collect();
    let mut files = BTreeMap::new();
    let mut layout = Vec::new();
    let mut warnings = Vec::new();
    for source in proofs {
        let path = PathBuf::from(&source.relative_path);
        let mut node = Layout {
            path: path.clone(),
            directory: source.directory,
            mode: source.unix_mode.ok_or("插件mode缺失")?,
            bytes: 0,
            sha256: None,
        };
        if !source.directory {
            let blob = blobs
                .get(&source.relative_path)
                .ok_or("旧插件文件没有完整字节归档")?;
            if hash(&blob.original_bytes) != blob.sha256 {
                return Err("旧插件文件hash不匹配".into());
            }
            let mut bytes = blob.original_bytes.clone();
            let host_env = path.file_name().is_some_and(|name| name == "env.json")
                && (path.components().count() == 3
                    || path.components().count() == 4 && path.starts_with("plugins/.trash"));
            if host_env {
                match serde_json::from_slice::<BTreeMap<String, String>>(&bytes) {
                    Ok(mut values) => {
                        for (name, value) in &mut values {
                            if value.starts_with("enc1:") {
                                let valid = StoredSecret::preserve_ciphertext(value.clone())
                                    .and_then(|stored| cipher.decrypt(&stored));
                                if valid.is_err() {
                                    warnings.push(format!("旧插件 {} 的环境变量 {name} 无法解密；原密文保留，该插件需重新填写后使用", path.parent().unwrap_or(Path::new("plugins")).display()));
                                }
                            } else {
                                *value = cipher
                                    .encrypt(value)
                                    .map_err(|error| error.to_string())?
                                    .as_ciphertext()
                                    .to_owned();
                            }
                        }
                        bytes = serde_json::to_vec(&values).map_err(|_| "插件env迁移编码失败")?;
                    }
                    Err(_) => warnings.push(format!(
                        "旧插件 {} 的宿主env不是字符串字典；原文私有保留，该插件需修复后使用",
                        path.parent().unwrap_or(Path::new("plugins")).display()
                    )),
                }
                node.mode = 0o600;
            }
            node.bytes = bytes.len() as u64;
            node.sha256 = Some(hash(&bytes));
            files.insert(path, bytes);
        }
        layout.push(node);
    }
    let result = Tree {
        layout,
        files,
        warnings,
    };
    result.validate()?;
    Ok(result)
}

pub(crate) fn merge_layout(current: &[Layout], incoming: &[Layout]) -> Result<Vec<Layout>, String> {
    let mut result: BTreeMap<_, _> = current
        .iter()
        .map(|node| (node.path.clone(), node.clone()))
        .collect();
    for node in incoming {
        if let Some(previous) = result.get(&node.path) {
            if node.path == Path::new("plugins") && previous.directory && node.directory {
                continue;
            }
            if previous != node {
                return Err(format!(
                    "当前插件布局与旧路径{}冲突，未覆盖",
                    node.path.display()
                ));
            }
        } else {
            result.insert(node.path.clone(), node.clone());
        }
    }
    let result: Vec<_> = result.into_values().collect();
    validate_layout(&result)?;
    Ok(result)
}
pub(crate) fn scan_tree(
    root: &Path,
    relative_root: &Path,
    include_trash: bool,
    cancel: &CancelToken,
) -> Result<Tree, String> {
    relative(relative_root)?;
    let anchor = checked_root(root)?;
    let mut tree = Tree {
        layout: Vec::new(),
        files: BTreeMap::new(),
        warnings: Vec::new(),
    };
    fn visit(
        root: &Path,
        path: &Path,
        tree: &mut Tree,
        include_trash: bool,
        cancel: &CancelToken,
    ) -> Result<(), String> {
        if cancel.is_cancelled() {
            return Err("插件文件扫描已取消".into());
        }
        if tree.layout.len() >= MAX_NODES {
            return Err("插件扫描超过4096项".into());
        }
        let relative_path = path.strip_prefix(root).map_err(|_| "插件扫描越界")?;
        relative(relative_path)?;
        let metadata = fs::symlink_metadata(path).map_err(|_| "插件扫描文件状态变化")?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() && !metadata.is_file() {
            return Err("插件扫描拒绝链接或特殊文件".into());
        }
        if path.canonicalize().map_err(|_| "插件路径无法解析")? != path {
            return Err("插件扫描路径被重定向".into());
        }
        #[cfg(unix)]
        let mode = {
            use std::os::unix::fs::MetadataExt;
            metadata.mode() & 0o777
        };
        #[cfg(not(unix))]
        let mode = if metadata.is_dir() { 0o700 } else { 0o600 };
        let mut node = Layout {
            path: relative_path.to_owned(),
            directory: metadata.is_dir(),
            mode,
            bytes: 0,
            sha256: None,
        };
        if metadata.is_dir() {
            tree.layout.push(node);
            let mut entries: Vec<_> = fs::read_dir(path)
                .map_err(|_| "插件目录读取失败")?
                .collect::<Result<_, _>>()
                .map_err(|_| "插件目录项读取失败")?;
            entries.sort_by_key(|entry| entry.file_name());
            for entry in entries {
                let name = entry.file_name();
                let name = name.to_str().ok_or("插件文件名不是UTF8")?;
                if relative_path == Path::new("plugins")
                    && (name.starts_with(".staging-") || name.starts_with(".install-"))
                {
                    continue;
                }
                if !include_trash && name == ".trash" {
                    continue;
                }
                visit(root, &entry.path(), tree, include_trash, cancel)?;
            }
        } else {
            if metadata.len() > MAX_BYTES {
                return Err("插件文件超过256 MiB".into());
            }
            let previous = tree
                .layout
                .iter()
                .filter(|node| !node.directory)
                .try_fold(0u64, |total, node| {
                    total.checked_add(node.bytes).ok_or("插件扫描长度溢出")
                })?;
            if previous
                .checked_add(metadata.len())
                .is_none_or(|total| total > MAX_BYTES)
            {
                return Err("插件扫描合计超过256 MiB".into());
            }
            let mut options = OpenOptions::new();
            options.read(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK);
            }
            let mut file = options.open(path).map_err(|_| "插件文件无法打开")?;
            let mut bytes = Vec::new();
            Read::by_ref(&mut file)
                .take(metadata.len() + 1)
                .read_to_end(&mut bytes)
                .map_err(|_| "插件文件读取失败")?;
            let after = file.metadata().map_err(|_| "插件文件身份无法确认")?;
            if bytes.len() as u64 != metadata.len() || !same(&metadata, &after) {
                return Err("插件文件在扫描期间变化".into());
            }
            node.bytes = bytes.len() as u64;
            node.sha256 = Some(hash(&bytes));
            tree.files.insert(relative_path.to_owned(), bytes);
            tree.layout.push(node);
        }
        Ok(())
    }
    if !anchor.join(relative_root).exists() {
        return Ok(tree);
    }
    visit(
        &anchor,
        &anchor.join(relative_root),
        &mut tree,
        include_trash,
        cancel,
    )?;
    tree.validate()?;
    Ok(tree)
}
pub(crate) fn checked_root(root: &Path) -> Result<PathBuf, String> {
    if !root.is_absolute() {
        return Err("插件拥有目录必须为绝对路径".into());
    }
    let canonical = root.canonicalize().map_err(|_| "插件拥有目录无法解析")?;
    if canonical != root
        || fs::symlink_metadata(root)
            .map_err(|_| "插件拥有目录状态无法确认")?
            .file_type()
            .is_symlink()
    {
        return Err("插件拥有目录不得被重定向".into());
    }
    Ok(canonical)
}
fn same(first: &fs::Metadata, second: &fs::Metadata) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        first.dev() == second.dev()
            && first.ino() == second.ino()
            && first.len() == second.len()
            && first.mtime() == second.mtime()
            && first.mtime_nsec() == second.mtime_nsec()
            && first.ctime() == second.ctime()
            && first.ctime_nsec() == second.ctime_nsec()
    }
    #[cfg(not(unix))]
    {
        first.len() == second.len() && first.modified().ok() == second.modified().ok()
    }
}
/// Install/restore into a private unpublished stage. Known payloads only;
/// hashes, ordinary modes and empty directories are applied before publication.
pub(crate) fn write_tree(root: &Path, tree: &Tree, cancel: &CancelToken) -> Result<(), String> {
    tree.validate()?;
    checked_root(root)?;
    let mut directories = BTreeSet::new();
    for node in &tree.layout {
        for parent in node
            .path
            .ancestors()
            .skip(if node.directory { 0 } else { 1 })
            .filter(|path| !path.as_os_str().is_empty())
        {
            directories.insert(parent.to_owned());
        }
    }
    let mut directories: Vec<_> = directories.into_iter().collect();
    directories.sort_by_key(|path| path.components().count());
    for path in &directories {
        if cancel.is_cancelled() {
            return Err("插件目录安装已取消".into());
        }
        let target = root.join(path);
        match fs::create_dir(&target) {
            Ok(()) => (),
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                if !fs::symlink_metadata(&target)
                    .map_err(|_| "插件安装目录状态无法确认")?
                    .is_dir()
                    || target.canonicalize().map_err(|_| "插件安装目录解析失败")? != target
                {
                    return Err("插件安装目录已变化".into());
                }
            }
            Err(_) => return Err("插件安装目录创建失败".into()),
        }
        mode(&target, 0o700)?;
    }
    for node in tree.layout.iter().filter(|node| !node.directory) {
        if cancel.is_cancelled() {
            return Err("插件文件安装已取消".into());
        }
        let target = root.join(&node.path);
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
            .open(&target)
            .map_err(|_| "插件文件已存在或不能创建，未覆盖")?;
        for chunk in tree.files[&node.path].chunks(64 * 1024) {
            if cancel.is_cancelled() {
                return Err("插件文件安装已取消".into());
            }
            file.write_all(chunk).map_err(|_| "插件文件写入失败")?;
        }
        file.sync_all().map_err(|_| "插件文件同步失败")?;
        mode(&target, node.mode)?;
    }
    for node in tree.layout.iter().filter(|node| node.directory).rev() {
        mode(&root.join(&node.path), node.mode)?;
        sync_directory(&root.join(&node.path))?;
    }
    for path in directories.iter().rev() {
        sync_directory(&root.join(path))?;
    }
    sync_directory(root)
}
pub(crate) fn mode(path: &Path, permissions: u32) -> Result<(), String> {
    if permissions & !0o777 != 0 {
        return Err("插件权限只能包含普通rwx位".into());
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(permissions))
            .map_err(|_| "插件权限应用失败")?;
    }
    #[cfg(not(unix))]
    if permissions & 0o111 != 0 {
        return Err("当前平台无法确认插件执行权限".into());
    }
    Ok(())
}
pub(crate) fn sync_directory(path: &Path) -> Result<(), String> {
    #[cfg(unix)]
    {
        File::open(path)
            .and_then(|file| file.sync_all())
            .map_err(|_| "插件目录同步失败".to_owned())
    }
    #[cfg(not(unix))]
    {
        let _ = path;
        Err("当前平台无法确认插件目录持久化".into())
    }
}
pub(crate) fn apply_layout(root: &Path, layout: &[Layout]) -> Result<(), String> {
    validate_layout(layout)?;
    checked_root(root)?;
    let mut dirs: Vec<_> = layout.iter().filter(|node| node.directory).collect();
    dirs.sort_by_key(|node| node.path.components().count());
    for node in &dirs {
        let target = root.join(&node.path);
        if !target.exists() {
            fs::create_dir(&target).map_err(|_| "迁移插件空目录创建失败")?;
        }
        if target.canonicalize().map_err(|_| "插件迁移目录无法解析")? != target {
            return Err("插件迁移目录被重定向".into());
        }
    }
    for node in layout.iter().filter(|node| !node.directory) {
        mode(&root.join(&node.path), node.mode)?;
    }
    for node in dirs.into_iter().rev() {
        mode(&root.join(&node.path), node.mode)?;
        sync_directory(&root.join(&node.path))?;
    }
    verify_layout(root, layout)
}
pub(crate) fn verify_layout(root: &Path, layout: &[Layout]) -> Result<(), String> {
    validate_layout(layout)?;
    for node in layout {
        let target = root.join(&node.path);
        let metadata = fs::symlink_metadata(&target).map_err(|_| "插件布局目标缺失")?;
        if metadata.file_type().is_symlink()
            || metadata.is_dir() != node.directory
            || !node.directory && !metadata.is_file()
        {
            return Err("插件布局目标类型不匹配".into());
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::MetadataExt;
            if metadata.mode() & 0o777 != node.mode {
                return Err("插件布局目标mode不匹配".into());
            }
        }
        if !node.directory {
            if metadata.len() != node.bytes {
                return Err("插件布局目标长度已变化".into());
            }
            let mut options = OpenOptions::new();
            options.read(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK | libc::O_CLOEXEC);
            }
            let file = options.open(&target).map_err(|_| "插件布局文件无法打开")?;
            let mut bytes = Vec::new();
            file.take(node.bytes + 1)
                .read_to_end(&mut bytes)
                .map_err(|_| "插件布局目标无法核对")?;
            if bytes.len() as u64 != node.bytes || Some(hash(&bytes)) != node.sha256 {
                return Err("插件布局目标内容不匹配".into());
            }
        }
    }
    Ok(())
}

pub(crate) fn remove_tree(
    root: &Path,
    relative_root: &Path,
    cancel: &CancelToken,
) -> Result<(), String> {
    let tree = scan_tree(root, relative_root, true, cancel)?;
    verify_layout(root, &tree.layout)?;
    let mut nodes = tree.layout;
    nodes.sort_by_key(|node| std::cmp::Reverse(node.path.components().count()));
    for node in nodes {
        if cancel.is_cancelled() {
            return Err("插件回收已取消，剩余文件保留".into());
        }
        let target = root.join(node.path);
        if node.directory {
            fs::remove_dir(&target)
                .map_err(|_| "插件回收目录非空或发生变化，未递归删除未知内容")?;
        } else {
            fs::remove_file(&target).map_err(|_| "插件回收文件发生变化")?;
        }
    }
    sync_directory(root)
}
pub(crate) fn trash(root: &Path, id: &str, wipe: bool, cancel: &CancelToken) -> Result<(), String> {
    if !crate::plugins::valid_id(id) {
        return Err("待移除插件ID无效".into());
    }
    checked_root(root)?;
    if wipe {
        remove_tree(root, Path::new(id), cancel)?;
    } else {
        let source = root.join(id);
        checked_root(&source)?;
        let trash = root.join(".trash");
        if !trash.exists() {
            fs::create_dir(&trash).map_err(|_| "插件回收目录创建失败")?;
        }
        checked_root(&trash)?;
        mode(&trash, 0o700)?;
        let now = crate::controller::now_millis();
        let target = trash.join(format!("{id}-{now}"));
        if target.exists() {
            return Err("插件回收槽位重名，未覆盖".into());
        }
        if cancel.is_cancelled() {
            return Err("插件移除已取消".into());
        }
        fs::rename(&source, &target).map_err(|_| "插件移入回收目录失败")?;
        sync_directory(root)?;
        sync_directory(&trash)?;
    }
    purge(root, cancel)?;
    let mut ids = Vec::new();
    for entry in fs::read_dir(root).map_err(|_| "插件已移除但目录清单读取失败")? {
        let entry = entry.map_err(|_| "插件清单读取失败")?;
        if entry
            .file_type()
            .map_err(|_| "插件清单类型无法确认")?
            .is_dir()
            && !entry.file_name().to_string_lossy().starts_with('.')
        {
            ids.push(entry.file_name().to_string_lossy().into_owned());
        }
    }
    ids.sort();
    let bytes = serde_json::to_vec(
        &serde_json::json!({"ids":ids,"updated_at":crate::controller::now_millis()}),
    )
    .map_err(|_| "插件差量清单编码失败")?;
    let temp = root.join(format!(".known-{}.tmp", crate::controller::unique_id()));
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temp)
        .map_err(|_| "插件已移除但差量清单无法创建")?;
    file.write_all(&bytes).map_err(|_| "插件差量清单写入失败")?;
    file.sync_all().map_err(|_| "插件差量清单同步失败")?;
    mode(&temp, 0o600)?;
    fs::rename(&temp, root.join(".known_plugins.json"))
        .map_err(|_| "插件已移除但差量清单发布失败")?;
    sync_directory(root)
}
pub(crate) fn purge(root: &Path, cancel: &CancelToken) -> Result<(), String> {
    let trash = root.join(".trash");
    if !trash.exists() {
        return Ok(());
    }
    checked_root(&trash)?;
    let now = crate::controller::now_millis();
    for entry in fs::read_dir(&trash).map_err(|_| "插件回收目录读取失败")? {
        let entry = entry.map_err(|_| "插件回收项读取失败")?;
        let name = entry
            .file_name()
            .into_string()
            .map_err(|_| "插件回收项不是UTF8")?;
        let Some((id, at)) = name.rsplit_once('-') else {
            continue;
        };
        let Ok(at) = at.parse::<i64>() else { continue };
        if !name.starts_with("host-stage-")
            && crate::plugins::valid_id(id)
            && at >= 0
            && now.saturating_sub(at) >= TRASH_DAYS_MS
        {
            remove_tree(root, &Path::new(".trash").join(name), cancel)?;
        }
    }
    sync_directory(&trash)
}
