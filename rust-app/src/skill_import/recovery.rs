//! Durable receipts belong only to private import copies. Recovery never downloads,
//! enables, or resumes model work. Referenced copies are never candidates for cleanup.
use super::*;
use serde::{Deserialize, Serialize};
use std::io::Write;
const RECEIPT_BYTES: u64 = 2 * 1024 * 1024;
const TTL_MS: i64 = 24 * 60 * 60 * 1000;
struct RecoveryClaim(String);
impl Drop for RecoveryClaim {
    fn drop(&mut self) {
        registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .recovery_claims
            .remove(&self.0);
    }
}
#[derive(Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
enum Phase {
    Writing,
    Prepared,
    Saved,
    Uncertain,
}
#[derive(Serialize, Deserialize)]
struct Origin {
    id: String,
    name: String,
    description: String,
    source: String,
    source_url: String,
    document: String,
    resources: String,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Receipt {
    version: u32,
    ticket: String,
    workspace: PathBuf,
    created_at: i64,
    expires_at: i64,
    phase: Phase,
    device: u64,
    inode: u64,
    layout: Vec<Layout>,
    skills: Vec<Origin>,
    #[serde(default)]
    partial: Vec<Layout>,
}
fn identity(path: &Path) -> Result<(u64, u64), String> {
    let metadata = fs::symlink_metadata(path).map_err(|_| "技能候选目录身份无法读取")?;
    if metadata.file_type().is_symlink()
        || !metadata.is_dir()
        || path.canonicalize().map_err(|_| "技能候选目录无法解析")? != path
    {
        return Err("技能候选目录已改变，未清理未知目录".into());
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        Ok((metadata.dev(), metadata.ino()))
    }
    #[cfg(not(unix))]
    {
        Ok((0, 0))
    }
}
fn marker(workspace: &Path, ticket: &str) -> Result<PathBuf, String> {
    let name = Path::new(ticket);
    files::relative(name)?;
    if name.components().count() != 1 || !ticket.starts_with('s') || ticket.len() > 128 {
        return Err("技能候选回执身份无效".into());
    }
    Ok(workspace
        .join("skill-files")
        .join(format!(".receipt-{ticket}.json")))
}
fn write(receipt: &Receipt) -> Result<(), String> {
    let target = marker(&receipt.workspace, &receipt.ticket)?;
    let directory = target.parent().ok_or("技能回执父目录无效")?;
    files::checked_root(directory)?;
    let bytes = serde_json::to_vec(receipt).map_err(|_| "技能回执无法编码")?;
    if bytes.len() as u64 > RECEIPT_BYTES {
        return Err("技能回执超过2MiB预算".into());
    }
    let temporary = directory.join(format!(".receipt-tmp-{}", crate::controller::unique_id()));
    let mut options = OpenOptions::new();
    options.create_new(true).write(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
    }
    let mut file = options
        .open(&temporary)
        .map_err(|_| "技能回执临时文件无法创建")?;
    let result = (|| {
        file.write_all(&bytes).map_err(|_| "技能回执写入失败")?;
        file.sync_all().map_err(|_| "技能回执同步失败")?;
        drop(file);
        match fs::symlink_metadata(&target) {
            Ok(m) if m.is_file() && !m.file_type().is_symlink() => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            _ => return Err("技能回执目标被改变，未覆盖".into()),
        }
        fs::rename(&temporary, &target).map_err(|_| "技能回执发布失败")?;
        files::sync_directory(directory)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}
fn read(workspace: &Path, ticket: &str, control: &Control) -> Result<Receipt, String> {
    let bytes = bounded_file(&marker(workspace, ticket)?, RECEIPT_BYTES, control)?;
    let value: Receipt =
        serde_json::from_slice(&bytes).map_err(|_| "技能回执格式不可读，原复制文件保留")?;
    if value.version != 1
        || value.workspace != workspace
        || value.ticket != ticket
        || value.created_at < 0
        || value.created_at.checked_add(TTL_MS) != Some(value.expires_at)
        || value.layout.len() > MAX_NODES
        || value.skills.len() > 256
    {
        return Err("技能回执版本、目录或预算不匹配，未恢复".into());
    }
    files::validate_layout(&value.layout)?;
    let mut origins = BTreeSet::new();
    for origin in &value.skills {
        let document = Path::new(&origin.document);
        files::relative(document)?;
        if !origins.insert(&origin.id)
            || document.parent().unwrap_or(Path::new("")) != Path::new(&origin.resources)
            || !value
                .layout
                .iter()
                .any(|node| !node.directory && node.path == document)
        {
            return Err("技能回执入口或资源目录不属于原文件清单".into());
        }
    }
    files::validate_layout(&value.partial)?;
    if value.partial.len() > value.layout.len()
        || value.partial.iter().any(|p| {
            !value
                .layout
                .iter()
                .any(|n| n.path == p.path && n.directory == p.directory && p.bytes <= n.bytes)
        })
    {
        return Err("技能部分复制证明越界".into());
    }

    if value
        .layout
        .iter()
        .any(|n| n.bytes > DOCUMENT_BYTES as u64 || n.path.components().count() > MAX_DEPTH)
        || value.layout.iter().map(|n| n.bytes).sum::<u64>() > TOTAL_BYTES
    {
        return Err("技能回执文件预算无效".into());
    }
    Ok(value)
}
pub(super) fn begin(
    workspace: &Path,
    ticket: &str,
    path: &Path,
    tree: &Tree,
    skills: &[Skill],
) -> Result<(), String> {
    let now = crate::controller::now_millis();
    let (device, inode) = identity(path)?;
    write(&Receipt {
        version: 1,
        ticket: ticket.into(),
        workspace: workspace.to_owned(),
        created_at: now,
        expires_at: now.checked_add(TTL_MS).ok_or("技能候选到期时间溢出")?,
        phase: Phase::Writing,
        device,
        inode,
        layout: tree.layout.clone(),
        partial: Vec::new(),
        skills: skills
            .iter()
            .map(|s| Origin {
                id: s.id.clone(),
                name: s.name.clone(),
                description: s.description.clone(),
                source: s.source.clone(),
                source_url: s.source_url.clone(),
                document: s.document_path.clone(),
                resources: s.files_path.clone(),
            })
            .collect(),
    })
}
fn change_phase(workspace: &Path, ticket: &str, phase: Phase) -> Result<(), String> {
    let control = Control::new();
    let mut receipt = read(workspace, ticket, &control)?;
    receipt.phase = phase;
    write(&receipt)
}
pub(super) fn prepared(workspace: &Path, ticket: &str) -> Result<(), String> {
    change_phase(workspace, ticket, Phase::Prepared)
}
pub(super) fn saved(workspace: &Path, ticket: &str) -> Result<(), String> {
    change_phase(workspace, ticket, Phase::Saved)
}
pub(super) fn uncertain(workspace: &Path, ticket: &str) -> Result<(), String> {
    change_phase(workspace, ticket, Phase::Uncertain)
}
fn referenced(ticket: &str, paths: &[String]) -> bool {
    let prefix = PathBuf::from("skill-files").join(ticket);
    paths.iter().any(|p| Path::new(p).starts_with(&prefix))
}

/// Return only nodes that still have their exact planned type/mode/bytes/hash.
/// Changed or added files are retained. Missing nodes permit cleanup-only recovery.
fn present(
    path: &Path,
    receipt: &Receipt,
    control: &Control,
) -> Result<(Vec<Layout>, bool), String> {
    if identity(path)? != (receipt.device, receipt.inode) {
        return Err("技能候选目录实例已改变，未读取或回收".into());
    }
    let expected: BTreeMap<_, _> = receipt.layout.iter().map(|n| (n.path.clone(), n)).collect();
    let mut actual = BTreeSet::new();
    let mut unknown = false;
    fn names(
        root: &Path,
        path: &Path,
        actual: &mut BTreeSet<PathBuf>,
        unknown: &mut bool,
        control: &Control,
    ) -> Result<(), String> {
        control.check()?;
        if actual.len() > MAX_NODES {
            return Err("技能候选目录条目超出预算，内容保留".into());
        }
        for entry in fs::read_dir(path).map_err(|_| "技能候选目录无法读取")? {
            let entry = entry.map_err(|_| "技能候选目录项无法读取")?;
            let relative = entry
                .path()
                .strip_prefix(root)
                .map_err(|_| "技能候选目录越界")?
                .to_owned();
            files::relative(&relative)?;
            if relative.components().count() > MAX_DEPTH || !actual.insert(relative.clone()) {
                return Err("技能候选目录层级或重名无效".into());
            }
            let metadata =
                fs::symlink_metadata(entry.path()).map_err(|_| "技能候选目录项已变化")?;
            if metadata.file_type().is_symlink() || !metadata.is_dir() && !metadata.is_file() {
                *unknown = true;
                continue;
            }
            if metadata.is_dir() {
                names(root, &entry.path(), actual, unknown, control)?;
            }
        }
        Ok(())
    }
    names(path, path, &mut actual, &mut unknown, control)?;
    if actual.iter().any(|p| !expected.contains_key(p)) {
        unknown = true;
    }
    let mut verified = Vec::new();
    for node in &receipt.layout {
        control.check()?;
        if !actual.contains(&node.path) {
            continue;
        }
        let target = path.join(&node.path);
        let metadata = fs::symlink_metadata(&target).map_err(|_| "技能候选节点已变化")?;
        if metadata.file_type().is_symlink()
            || metadata.is_dir() != node.directory
            || !node.directory && !metadata.is_file()
        {
            unknown = true;
            continue;
        }
        #[cfg(unix)]
        let mode = {
            use std::os::unix::fs::MetadataExt;
            metadata.mode() & 0o777
        };
        #[cfg(not(unix))]
        let mode = node.mode;
        let content = if node.directory {
            None
        } else {
            match bounded_file(&target, DOCUMENT_BYTES as u64, control) {
                Ok(bytes) => Some(bytes),
                Err(_) => {
                    unknown = true;
                    continue;
                }
            }
        };
        let matches = |proof: &Layout| {
            proof.mode == mode
                && match &content {
                    None => proof.directory,
                    Some(bytes) => {
                        !proof.directory
                            && bytes.len() as u64 == proof.bytes
                            && Some(files::hash(bytes)) == proof.sha256
                    }
                }
        };
        if matches(node) {
            verified.push(node.clone());
        } else if let Some(partial) = receipt
            .partial
            .iter()
            .find(|p| p.path == node.path && matches(p))
        {
            verified.push(partial.clone());
            unknown = true;
        } else {
            unknown = true;
        }
    }
    if identity(path)? != (receipt.device, receipt.inode) {
        return Err("技能候选目录在核对时被替换，未继续".into());
    }
    Ok((verified, unknown))
}
pub(super) fn cleanup_failed(
    workspace: &Path,
    ticket: &str,
    tree: &Tree,
    control: &Control,
) -> Result<(), String> {
    let mut receipt = read(workspace, ticket, control)?;
    let path = workspace.join("skill-files").join(ticket);
    if identity(&path)? != (receipt.device, receipt.inode) {
        return Err("技能失败候选目录已被替换，副本保留".into());
    }
    for node in &tree.layout {
        control.check()?;
        let target = path.join(&node.path);
        let metadata = match fs::symlink_metadata(&target) {
            Ok(m) => m,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue,
            Err(_) => return Err("技能失败副本状态无法读取".into()),
        };
        if metadata.file_type().is_symlink()
            || metadata.is_dir() != node.directory
            || !node.directory && !metadata.is_file()
        {
            continue;
        }
        #[cfg(unix)]
        let mode = {
            use std::os::unix::fs::MetadataExt;
            metadata.mode() & 0o777
        };
        #[cfg(not(unix))]
        let mode = if node.directory { 0o700 } else { 0o600 };
        if node.directory {
            if mode == 0o700 && mode != node.mode {
                let mut proof = node.clone();
                proof.mode = mode;
                receipt.partial.retain(|p| p.path != proof.path);
                receipt.partial.push(proof);
            }
        } else {
            let current = bounded_file(&target, DOCUMENT_BYTES as u64, control)?;
            let intended = tree.files.get(&node.path).ok_or("技能失败复制源证明缺失")?;
            if mode == 0o600 && current.len() <= intended.len() && intended.starts_with(&current) {
                let mut proof = node.clone();
                proof.bytes = current.len() as u64;
                proof.sha256 = Some(files::hash(&current));
                proof.mode = mode;
                if proof != *node {
                    receipt.partial.retain(|p| p.path != proof.path);
                    receipt.partial.push(proof);
                }
            }
        }
    }
    write(&receipt)?;
    cleanup(workspace, ticket, control)
}
pub(super) fn cleanup(workspace: &Path, ticket: &str, control: &Control) -> Result<(), String> {
    let receipt = read(workspace, ticket, control)?;
    if matches!(receipt.phase, Phase::Saved | Phase::Uncertain) {
        return Err("已提交或效果未确认的技能文件不能作为候选回收".into());
    }
    let path = workspace.join("skill-files").join(ticket);
    if !path.exists() {
        fs::remove_file(marker(workspace, ticket)?).map_err(|_| "技能空候选回执清理失败")?;
        return files::sync_directory(path.parent().ok_or("技能候选父目录无效")?);
    }
    let (mut nodes, unknown) = present(&path, &receipt, control)?;
    nodes.sort_by_key(|n| std::cmp::Reverse(n.path.components().count()));
    for node in nodes {
        control.check()?;
        if identity(&path)? != (receipt.device, receipt.inode) {
            return Err("技能候选目录被替换，剩余文件保留".into());
        }
        let target = path.join(&node.path);
        if node.directory {
            let _ = fs::remove_dir(&target);
        } else {
            files::verify_layout(&path, std::slice::from_ref(&node))?;
            fs::remove_file(&target).map_err(|_| "技能候选文件无法回收，剩余文件保留")?;
        }
    }
    let actual_remaining = fs::read_dir(&path)
        .map_err(|_| "技能候选回收后无法确认")?
        .next()
        .is_some();
    if unknown && actual_remaining {
        return Err("已回收匹配的候选复制文件；新增或改动内容保留在原候选目录".into());
    }
    fs::remove_dir(&path).map_err(|_| "技能候选目录仍有未知内容，未递归删除")?;
    fs::remove_file(marker(workspace, ticket)?)
        .map_err(|_| "技能候选文件已回收，但回执清理失败")?;
    files::sync_directory(path.parent().ok_or("技能候选父目录无效")?)
}
/// Delete through directory descriptors on Android so ancestor links cannot redirect cleanup.
#[cfg(unix)]
fn remove_owned_node(
    root: &Path,
    receipt: &Receipt,
    node: &Layout,
    control: &Control,
) -> Result<(), String> {
    use std::ffi::CString;
    use std::os::fd::{AsRawFd, FromRawFd};
    use std::os::unix::{ffi::OsStrExt, fs::MetadataExt};
    control.check()?;
    let mut parent = open_without_links(root)?;
    let metadata = parent.metadata().map_err(|_| "技能回收目录身份无法确认")?;
    if !metadata.is_dir() || (metadata.dev(), metadata.ino()) != (receipt.device, receipt.inode) {
        return Err("技能回收目录实例已变化，剩余副本保留".into());
    }
    for part in node.path.parent().unwrap_or(Path::new("")).components() {
        let std::path::Component::Normal(name) = part else {
            return Err("技能回收路径不是安全相对路径".into());
        };
        let name = CString::new(name.as_bytes()).map_err(|_| "技能回收目录名含NUL")?;
        let descriptor = unsafe {
            libc::openat(
                parent.as_raw_fd(),
                name.as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
            )
        };
        if descriptor < 0 {
            return Err("技能回收父目录无法安全打开，内容保留".into());
        }
        parent = unsafe { File::from_raw_fd(descriptor) };
    }
    let name = CString::new(
        node.path
            .file_name()
            .ok_or("技能回收节点没有文件名")?
            .as_bytes(),
    )
    .map_err(|_| "技能回收文件名含NUL")?;
    let mut stat = std::mem::MaybeUninit::<libc::stat>::uninit();
    if unsafe {
        libc::fstatat(
            parent.as_raw_fd(),
            name.as_ptr(),
            stat.as_mut_ptr(),
            libc::AT_SYMLINK_NOFOLLOW,
        )
    } != 0
    {
        return Err("技能回收节点在删除前发生变化，内容保留".into());
    }
    let stat = unsafe { stat.assume_init() };
    let wanted_type = if node.directory {
        libc::S_IFDIR
    } else {
        libc::S_IFREG
    };
    let mode = stat.st_mode as libc::mode_t;
    if mode & libc::S_IFMT != wanted_type || u32::from(mode & 0o777) != node.mode {
        return Err("技能回收节点类型或权限已变化，内容保留".into());
    }
    if !node.directory {
        let descriptor = unsafe {
            libc::openat(
                parent.as_raw_fd(),
                name.as_ptr(),
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_NONBLOCK | libc::O_CLOEXEC,
            )
        };
        if descriptor < 0 {
            return Err("技能回收文件无法安全打开，内容保留".into());
        }
        let mut file = unsafe { File::from_raw_fd(descriptor) };
        let opened = file.metadata().map_err(|_| "技能回收文件身份无法确认")?;
        if !opened.is_file()
            || opened.dev() != stat.st_dev
            || opened.ino() != stat.st_ino
            || opened.len() != node.bytes
            || opened.mode() & 0o777 != node.mode
        {
            return Err("技能回收文件在打开时发生变化，内容保留".into());
        }
        let mut bytes = Vec::new();
        let mut chunk = [0u8; 64 * 1024];
        loop {
            control.check()?;
            let count = file.read(&mut chunk).map_err(|_| "技能回收文件读取失败")?;
            if count == 0 {
                break;
            }
            if bytes.len().saturating_add(count) as u64 > node.bytes {
                return Err("技能回收文件长度已变化，内容保留".into());
            }
            bytes.extend_from_slice(&chunk[..count]);
        }
        if bytes.len() as u64 != node.bytes || Some(files::hash(&bytes)) != node.sha256 {
            return Err("技能回收文件内容已变化，副本保留".into());
        }
        let mut current = std::mem::MaybeUninit::<libc::stat>::uninit();
        if unsafe {
            libc::fstatat(
                parent.as_raw_fd(),
                name.as_ptr(),
                current.as_mut_ptr(),
                libc::AT_SYMLINK_NOFOLLOW,
            )
        } != 0
        {
            return Err("技能回收文件在删除前无法核对，副本保留".into());
        }
        let current = unsafe { current.assume_init() };
        if current.st_dev != stat.st_dev
            || current.st_ino != stat.st_ino
            || current.st_mode != stat.st_mode
            || current.st_size != stat.st_size
        {
            return Err("技能回收文件在删除前已替换，副本保留".into());
        }
    }
    control.check()?;
    let flags = if node.directory {
        libc::AT_REMOVEDIR
    } else {
        0
    };
    if unsafe { libc::unlinkat(parent.as_raw_fd(), name.as_ptr(), flags) } != 0 {
        return Err("技能原文或资源未能删除；非空目录未递归清理".into());
    }
    parent
        .sync_all()
        .map_err(|_| "技能文件已回收，但目录同步失败".into())
}
#[cfg(not(unix))]
fn remove_owned_node(
    root: &Path,
    receipt: &Receipt,
    node: &Layout,
    control: &Control,
) -> Result<(), String> {
    control.check()?;
    if identity(root)? != (receipt.device, receipt.inode) {
        return Err("技能回收目录实例已变化，内容保留".into());
    }
    let target = root.join(&node.path);
    for ancestor in target
        .parent()
        .ok_or("技能回收父目录无效")?
        .ancestors()
        .take_while(|p| p.starts_with(root))
    {
        files::checked_root(ancestor)?;
    }
    files::verify_layout(root, std::slice::from_ref(node))?;
    if node.directory {
        fs::remove_dir(target)
    } else {
        fs::remove_file(target)
    }
    .map_err(|_| "技能原文或资源删除失败，剩余内容保留".to_owned())
}

pub(super) fn delete_installed_copy(
    workspace: &Path,
    removed: &Skill,
    remaining: &[Skill],
) -> Result<bool, String> {
    if removed.document_path.is_empty() && removed.files_path.is_empty() {
        return Ok(false);
    }
    removed.validate()?;
    let document = Path::new(&removed.document_path);
    let mut parts = document.components();
    if parts.next().is_none_or(|p| p.as_os_str() != "skill-files") {
        return Err("原文件没有私有导入回执证明，已保留".into());
    }
    let ticket = parts
        .next()
        .and_then(|p| p.as_os_str().to_str())
        .ok_or("技能导入副本身份缺失")?
        .to_owned();
    marker(workspace, &ticket)?;
    let prefix = PathBuf::from("skill-files").join(&ticket);
    let document = document
        .strip_prefix(&prefix)
        .map_err(|_| "技能原文不属于导入副本")?;
    let resources = Path::new(&removed.files_path)
        .strip_prefix(&prefix)
        .map_err(|_| "技能资源不属于同一导入副本")?;
    let _claim = {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        if state.tickets.contains_key(&ticket)
            || state.protected.contains(&ticket)
            || !state.recovery_claims.insert(ticket.clone())
        {
            return Err("这份副本仍有活动导入或恢复操作，已保留".into());
        }
        RecoveryClaim(ticket.clone())
    };
    let control = Control::new();
    let receipt = read(workspace, &ticket, &control)?;
    if receipt.phase == Phase::Writing
        || !receipt.skills.iter().any(|origin| {
            Path::new(&origin.document) == document && Path::new(&origin.resources) == resources
        })
    {
        return Err("技能原文或资源与已完成导入回执不一致，已保留".into());
    }
    let mut references = Vec::new();
    for skill in remaining {
        skill.validate()?;
        for path in skill.file_paths() {
            if !path.is_empty() {
                references.push(PathBuf::from(path));
            }
        }
    }
    let shared = references
        .iter()
        .any(|p| p.starts_with(&prefix) || prefix.starts_with(p));
    let protected = |node: &Layout| {
        let absolute = prefix.join(&node.path);
        references
            .iter()
            .any(|p| absolute.starts_with(p) || node.directory && p.starts_with(&absolute))
    };
    let owned = |node: &Layout| {
        !shared
            || node.path == document
            || !resources.as_os_str().is_empty() && node.path.starts_with(resources)
    };
    let path = workspace.join(&prefix);
    let (present_nodes, _) = present(&path, &receipt, &control)?;
    let mut retained = false;
    let mut nodes = Vec::new();
    for node in &receipt.layout {
        if !owned(node) {
            continue;
        }
        if protected(node) {
            retained = true;
            continue;
        }
        match present_nodes
            .iter()
            .find(|p| p.path == node.path && *p == node)
        {
            Some(proof) => nodes.push(proof.clone()),
            None => match fs::symlink_metadata(path.join(&node.path)) {
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                _ => return Err("技能原文或资源已改变，未删除未证明的内容".into()),
            },
        }
    }
    nodes.sort_by_key(|node| std::cmp::Reverse(node.path.components().count()));
    for node in &nodes {
        remove_owned_node(&path, &receipt, node, &control)?;
    }
    if !shared {
        if fs::read_dir(&path)
            .map_err(|_| "技能副本清理后状态无法确认")?
            .next()
            .is_some()
        {
            return Err("匹配的原文与资源已回收；新增或改动内容仍保留在原副本目录".into());
        }
        if identity(&path)? != (receipt.device, receipt.inode) {
            return Err("技能副本目录已改变，回执与剩余内容保留".into());
        }
        fs::remove_dir(&path).map_err(|_| "技能副本目录仍有内容，未递归删除")?;
        fs::remove_file(marker(workspace, &ticket)?)
            .map_err(|_| "技能副本已回收，但回执删除失败")?;
        files::sync_directory(path.parent().ok_or("技能副本父目录缺失")?)?;
    } else {
        files::sync_directory(&path)?;
    }
    Ok(retained)
}

pub(super) fn recover(
    workspace: PathBuf,
    referenced_paths: Vec<String>,
    epoch: u64,
    control: &Control,
) -> Result<(Vec<Review>, Vec<String>), String> {
    let directory = workspace.join("skill-files");
    if !directory.exists() {
        return Ok((Vec::new(), Vec::new()));
    }
    files::checked_root(&directory)?;
    let mut markers = Vec::new();
    for entry in fs::read_dir(&directory).map_err(|_| "技能候选清单无法读取")? {
        control.check()?;
        let name = entry.map_err(|_| "技能候选清单项读取失败")?.file_name();
        let Some(name) = name.to_str() else { continue };
        if let Some(ticket) = name
            .strip_prefix(".receipt-")
            .and_then(|n| n.strip_suffix(".json"))
        {
            if markers.len() >= 4096 {
                return Err("技能回执清单超过4096项，需要先清理已弃用的文件".into());
            }
            markers.push(ticket.to_owned());
        }
    }
    markers.sort();
    let mut candidates = Vec::new();
    let mut claims = Vec::new();
    let mut warnings = Vec::new();
    for ticket in markers {
        control.check()?;
        if candidates.len() >= CAPACITY {
            warnings.push("仅恢复前4份候选，保存或丢弃后可再次读取。".into());
            break;
        }
        if referenced(&ticket, &referenced_paths) {
            continue;
        }
        {
            let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
            if state.metadata_epoch != epoch || epoch == u64::MAX {
                return Err(
                    "技能库在候选读取期间变化，请重新读取候选；已完成的候选回收不会重放".into(),
                );
            }
            if state.protected.contains(&ticket)
                || state.tickets.contains_key(&ticket)
                || !state.recovery_claims.insert(ticket.clone())
            {
                continue;
            }
        }
        let claim = RecoveryClaim(ticket.clone());

        let mut receipt = match read(&workspace, &ticket, control) {
            Ok(r) => r,
            Err(e) => {
                if warnings.len() < 32 {
                    warnings.push(e)
                }
                continue;
            }
        };
        if receipt.phase == Phase::Saved {
            continue;
        }
        if receipt.phase == Phase::Uncertain {
            receipt.phase = Phase::Writing;
            write(&receipt)?;
            if warnings.len() < 32 {
                warnings.push(
                    "当前已验证技能库未引用这份副本；仅恢复待审阅候选，未启用或重放任何任务。"
                        .into(),
                );
            }
        }
        if receipt.expires_at <= crate::controller::now_millis() {
            let claimed = {
                let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
                if state.metadata_epoch != epoch || epoch == u64::MAX {
                    return Err("技能库在候选回收前变化，未继续回收".into());
                }
                if state.protected.contains(&ticket)
                    || state
                        .tickets
                        .get(&ticket)
                        .is_some_and(|t| t.committing || t.expiry_queued)
                {
                    false
                } else {
                    if let Some(t) = state.tickets.get_mut(&ticket) {
                        t.committing = true;
                    }
                    true
                }
            };
            if !claimed {
                continue;
            }
            let cleanup_result = cleanup(&workspace, &ticket, control);
            registry()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .tickets
                .remove(&ticket);
            match cleanup_result {
                Ok(()) => {
                    if warnings.len() < 32 {
                        warnings.push("已回收一份过期且未被技能库引用的候选复制文件。".into())
                    }
                }
                Err(e) => {
                    if warnings.len() < 32 {
                        warnings.push(e)
                    }
                }
            }
            continue;
        }
        let path = directory.join(&ticket);
        let (layout, unknown) = match present(&path, &receipt, control) {
            Ok(v) => v,
            Err(e) => {
                if warnings.len() < 32 {
                    warnings.push(e)
                }
                continue;
            }
        };
        let complete = !unknown && layout.len() == receipt.layout.len();
        let mut skills = Vec::new();
        let mut headers = Vec::new();
        let mut local_warnings = Vec::new();
        if complete {
            for origin in &receipt.skills {
                let bytes =
                    bounded_file(&path.join(&origin.document), DOCUMENT_BYTES as u64, control)?;
                let raw = String::from_utf8(bytes).map_err(|_| "恢复技能原文不是UTF8")?;
                let mut skill = crate::skills::import_document(origin.id.clone(), raw)?;
                skill.name = origin.name.clone();
                skill.description = origin.description.clone();
                skill.source = origin.source.clone();
                skill.source_url = origin.source_url.clone();
                skill.enabled = false;
                let prefix = PathBuf::from("skill-files").join(&ticket);
                skill.document_path = prefix.join(&origin.document).to_string_lossy().into_owned();
                skill.files_path = prefix
                    .join(&origin.resources)
                    .to_string_lossy()
                    .into_owned();
                skill.validate()?;
                headers.push(Header {
                    id: skill.id.clone(),
                    name: skill.name.clone(),
                    description: skill.description.clone(),
                    document_bytes: skill.content.len(),
                    resources: layout
                        .iter()
                        .filter(|n| {
                            !n.directory
                                && n.path.starts_with(&origin.resources)
                                && n.path != Path::new(&origin.document)
                        })
                        .count(),
                });
                skills.push(skill);
            }
        } else {
            local_warnings.push(
                "这份候选写入未完成或原文件已改变；只能回收仍匹配的复制文件，不能提交技能库。"
                    .into(),
            );
        }
        let review = Review {
            ticket: ticket.clone(),
            skills: headers,
            files: layout.iter().filter(|n| !n.directory).count(),
            bytes: layout.iter().map(|n| n.bytes).sum(),
            warnings: local_warnings,
        };
        let expires = Duration::from_millis(
            receipt
                .expires_at
                .saturating_sub(crate::controller::now_millis())
                .clamp(1, TTL_MS) as u64,
        );
        candidates.push((
            ticket,
            Ticket {
                workspace: workspace.clone(),
                path,
                layout,
                skills,
                review: review.clone(),
                expires: Instant::now() + expires,
                committing: false,
                expiry_queued: false,
            },
            review,
        ));
        claims.push(claim);
    }
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    if state.metadata_epoch != epoch || epoch == u64::MAX {
        return Err("技能库在读取期间变化，未发布旧候选，请重新读取".into());
    }
    let mut reviews = Vec::new();
    for (ticket, candidate, review) in candidates {
        if state.protected.contains(&ticket)
            || state
                .tickets
                .get(&ticket)
                .is_some_and(|t| t.committing || t.expiry_queued)
        {
            continue;
        }
        if state.tickets.len() >= CAPACITY && !state.tickets.contains_key(&ticket) {
            warnings.push("当前候选已满，剩余磁盘回执保留。".into());
            break;
        }
        if state.tickets.contains_key(&ticket) {
            continue;
        }
        state.tickets.insert(ticket, candidate);
        reviews.push(review);
    }
    drop(state);
    drop(claims);
    Ok((reviews, warnings))
}
