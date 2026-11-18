//! Private host receipts let cold recovery retire an owned unpublished stage.
//! Unknown receipts and changed payloads are preserved, never inferred installed.
use super::*;
const RECEIPT_BUDGET: u64 = 8 * 1024 * 1024;
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
struct Identity {
    device: u64,
    inode: u64,
}
fn identity(path: &Path) -> Result<Identity, String> {
    let metadata = fs::symlink_metadata(path).map_err(|_| "插件拥有项身份无法读取")?;
    if metadata.file_type().is_symlink() || !metadata.is_file() && !metadata.is_dir() {
        return Err("插件拥有项成为链接或特殊文件".into());
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        Ok(Identity {
            device: metadata.dev(),
            inode: metadata.ino(),
        })
    }
    #[cfg(not(unix))]
    {
        Ok(Identity {
            device: 0,
            inode: 0,
        })
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Receipt {
    version: u32,
    stage: String,
    plugin_id: String,
    operation_id: String,
    conversation_id: Option<String>,
    generation: u64,
    created_at: i64,
    root: Identity,
    owner: Identity,
    ready: bool,
    layout: Vec<Layout>,
    items: BTreeMap<PathBuf, Identity>,
}
impl Receipt {
    pub(super) fn create(
        root: &RootKey,
        stage: &str,
        id: &str,
        operation: &OperationKey,
        layout: Vec<Layout>,
    ) -> Result<Self, String> {
        root.validate()?;
        let receipt = Self {
            version: 1,
            stage: stage.into(),
            plugin_id: id.into(),
            operation_id: operation.id.clone(),
            conversation_id: operation.conversation_id.clone(),
            generation: operation.generation,
            created_at: crate::controller::now_millis(),
            root: identity(&root.path)?,
            owner: identity(&root.path.join(stage))?,
            ready: false,
            layout,
            items: BTreeMap::new(),
        };
        receipt.persist(root)?;
        Ok(receipt)
    }
    fn path(&self, root: &RootKey) -> PathBuf {
        root.path.join(format!("{}.receipt.json", self.stage))
    }
    pub(super) fn persist(&self, root: &RootKey) -> Result<(), String> {
        self.validate(root)?;
        let bytes = serde_json::to_vec(self).map_err(|_| "插件宿主回执编码失败")?;
        if bytes.len() as u64 > RECEIPT_BUDGET {
            return Err("插件宿主回执超过8 MiB".into());
        }
        let temp = root.path.join(format!(
            "{}.receipt-{}.tmp",
            self.stage,
            crate::controller::unique_id()
        ));
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options
                .mode(0o600)
                .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC);
        }
        let result = (|| {
            let mut file = options.open(&temp).map_err(|_| "插件宿主回执创建失败")?;
            file.write_all(&bytes).map_err(|_| "插件宿主回执写入失败")?;
            file.sync_all().map_err(|_| "插件宿主回执同步失败")?;
            fs::rename(&temp, self.path(root)).map_err(|_| "插件宿主回执提交失败")?;
            sync_directory(&root.path)
        })();
        if result.is_err() {
            let _ = fs::remove_file(&temp);
        }
        result
    }
    fn validate(&self, root: &RootKey) -> Result<(), String> {
        root.validate()?;
        relative(Path::new(&self.stage))?;
        OperationKey {
            id: self.operation_id.clone(),
            conversation_id: self.conversation_id.clone(),
            generation: self.generation,
        }
        .validate()?;
        if self.version != 1
            || !self.stage.starts_with(".install-")
            || self.stage.contains(['/', '\\'])
            || self.stage.len() > 160
            || !crate::plugins::valid_id(&self.plugin_id)
            || self.created_at < 0
            || self.root != identity(&root.path)?
            || self.operation_id.len() > 128
            || self.operation_id.is_empty()
            || self.generation == 0
        {
            return Err("插件宿主回执身份或所属根目录无效".into());
        }
        validate_layout(&self.layout)?;
        if self
            .items
            .keys()
            .any(|path| !self.layout.iter().any(|node| &node.path == path))
        {
            return Err("插件宿主回执含未知身份项".into());
        }
        Ok(())
    }
    pub(super) fn seal(&mut self, root: &RootKey) -> Result<(), String> {
        self.validate(root)?;
        let stage = root.path.join(&self.stage);
        if identity(&stage)? != self.owner {
            return Err("插件准备目录身份已变化".into());
        }
        verify_layout(&stage, &self.layout)?;
        self.items.clear();
        for node in &self.layout {
            self.items
                .insert(node.path.clone(), identity(&stage.join(&node.path))?);
        }
        self.ready = true;
        self.persist(root)
    }
    pub(super) fn replace_state(&mut self, root: &RootKey, bytes: &[u8]) -> Result<(), String> {
        let node = self
            .layout
            .iter_mut()
            .find(|node| node.path == Path::new("state.json"))
            .ok_or("插件宿主state证明缺失")?;
        node.bytes = bytes.len() as u64;
        node.sha256 = Some(hash(bytes));
        self.seal(root)
    }
    fn verify_at(&self, root: &RootKey, path: &Path) -> Result<(), String> {
        self.validate(root)?;
        if !self.ready || identity(path)? != self.owner {
            return Err("插件回执尚未就绪或目录身份改变，原文件保留".into());
        }
        verify_layout(path, &self.layout)?;
        let actual = scan_tree(
            path.parent().ok_or("插件候选缺父目录")?,
            Path::new(path.file_name().ok_or("插件候选缺目录名")?),
            true,
            &CancelToken::new(),
        )?;
        let prefix = Path::new(path.file_name().ok_or("插件候选缺目录名")?);
        let actual: BTreeSet<_> = actual
            .layout
            .into_iter()
            .filter_map(|node| {
                node.path
                    .strip_prefix(prefix)
                    .ok()
                    .filter(|path| !path.as_os_str().is_empty())
                    .map(Path::to_owned)
            })
            .collect();
        let expected: BTreeSet<_> = self.layout.iter().map(|node| node.path.clone()).collect();
        if actual != expected || self.items.len() != expected.len() {
            return Err("插件候选出现未知文件或缺少自有证明，原目录保留".into());
        }
        for (path_key, expected_identity) in &self.items {
            if identity(&path.join(path_key))? != *expected_identity {
                return Err("插件候选项inode已改变，原目录保留".into());
            }
        }
        Ok(())
    }
    pub(super) fn verify(&self, root: &RootKey) -> Result<(), String> {
        self.verify_at(root, &root.path.join(&self.stage))
    }
    pub(super) fn published(&self, root: &RootKey) -> Result<(), String> {
        self.verify_at(root, &root.path.join(&self.plugin_id))
    }
    fn forget(&self, root: &RootKey) -> Result<(), String> {
        let path = self.path(root);
        let stored = read(root, &path)?;
        if stored.operation_id != self.operation_id
            || stored.generation != self.generation
            || stored.owner != self.owner
        {
            return Err("插件回执已换代，未删除".into());
        }
        fs::remove_file(path).map_err(|_| "插件宿主回执回收失败")?;
        sync_directory(&root.path)
    }
    pub(super) fn finish_publication(&self, root: &RootKey) -> Result<(), String> {
        self.published(root)?;
        self.forget(root)
    }
    /// Retire only a known directory identity. Unknown or partial payloads move
    /// intact to recovery trash and are never automatically wiped.
    pub(super) fn retire(&self, root: &RootKey) -> Result<(), String> {
        self.validate(root)?;
        let source = root.path.join(&self.stage);
        match fs::symlink_metadata(&source) {
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return self.forget(root),
            Err(_) => return Err("插件准备目录状态无法确认".into()),
            Ok(_) => {}
        }
        if identity(&source)? != self.owner {
            return Err("插件准备目录inode不属于此回执，未回收".into());
        }
        if self.verify(root).is_ok() {
            remove_owned(self, root)?;
            return self.forget(root);
        }
        let trash = root.path.join(".trash");
        if !trash.exists() {
            fs::create_dir(&trash).map_err(|_| "插件恢复回收目录创建失败")?;
        }
        checked_root(&trash)?;
        mode(&trash, 0o700)?;
        let destination = trash.join(format!(
            "host-stage-{}-{}",
            self.stage.trim_start_matches(".install-"),
            crate::controller::now_millis()
        ));
        rename_noreplace(&source, &destination)?;
        sync_directory(&trash)?;
        sync_directory(&root.path)?;
        // Preserve the receipt with the incomplete content. Generic trash purge
        // skips host-stage slots, so an unknown added file can never be wiped.
        let receipt_destination = trash.join(format!(
            "{}.receipt.json",
            destination
                .file_name()
                .ok_or("插件恢复目录名缺失")?
                .to_string_lossy()
        ));
        rename_noreplace(&self.path(root), &receipt_destination)?;
        sync_directory(&trash)?;
        sync_directory(&root.path)
    }
}
#[cfg(unix)]
fn remove_owned(receipt: &Receipt, root: &RootKey) -> Result<(), String> {
    use std::{
        ffi::CString,
        os::fd::{AsRawFd, FromRawFd},
        os::unix::ffi::OsStrExt,
    };
    fn opened_identity(file: &File) -> Result<Identity, String> {
        use std::os::unix::fs::MetadataExt;
        let metadata = file.metadata().map_err(|_| "插件清理目录FD身份无法确认")?;
        Ok(Identity {
            device: metadata.dev(),
            inode: metadata.ino(),
        })
    }
    fn c_name(name: &std::ffi::OsStr) -> Result<CString, String> {
        CString::new(name.as_bytes()).map_err(|_| "插件清理名称含NUL".into())
    }
    fn open_child(parent: &File, name: &std::ffi::OsStr) -> Result<File, String> {
        let name = c_name(name)?;
        let fd = unsafe {
            libc::openat(
                parent.as_raw_fd(),
                name.as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
            )
        };
        if fd < 0 {
            return Err("插件清理目录无法无链接打开".into());
        }
        Ok(unsafe { File::from_raw_fd(fd) })
    }
    fn remove(
        parent: &File,
        name: &std::ffi::OsStr,
        expected: &Identity,
        node: Option<&Layout>,
        directory: bool,
    ) -> Result<(), String> {
        let name = c_name(name)?;
        let mut metadata = std::mem::MaybeUninit::<libc::stat>::uninit();
        if unsafe {
            libc::fstatat(
                parent.as_raw_fd(),
                name.as_ptr(),
                metadata.as_mut_ptr(),
                libc::AT_SYMLINK_NOFOLLOW,
            )
        } != 0
        {
            return Err("插件清理项身份无法确认".into());
        }
        let metadata = unsafe { metadata.assume_init() };
        if metadata.st_dev as u64 != expected.device
            || metadata.st_ino as u64 != expected.inode
            || metadata.st_mode as u32 & libc::S_IFMT as u32
                != if directory {
                    libc::S_IFDIR as u32
                } else {
                    libc::S_IFREG as u32
                }
            || node.is_some_and(|node| {
                metadata.st_mode as u32 & 0o777 != node.mode
                    || !directory && metadata.st_size as u64 != node.bytes
            })
        {
            return Err("插件清理项inode/类型/mode已变化，未删除".into());
        }
        if unsafe {
            libc::unlinkat(
                parent.as_raw_fd(),
                name.as_ptr(),
                if directory { libc::AT_REMOVEDIR } else { 0 },
            )
        } != 0
        {
            return Err("插件清理目录出现未知内容或删除失败，剩余内容保留".into());
        }
        Ok(())
    }
    receipt.verify(root)?;
    let mut options = OpenOptions::new();
    options.read(true);
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC);
    }
    let root_fd = options
        .open(&root.path)
        .map_err(|_| "插件清理拥有根FD打开失败")?;
    if opened_identity(&root_fd)? != receipt.root {
        return Err("插件清理拥有根FD换代".into());
    }
    let stage_fd = open_child(&root_fd, std::ffi::OsStr::new(&receipt.stage))?;
    if opened_identity(&stage_fd)? != receipt.owner {
        return Err("插件清理准备目录FD换代".into());
    }
    let mut nodes: Vec<_> = receipt.layout.iter().collect();
    nodes.sort_by_key(|node| std::cmp::Reverse(node.path.components().count()));
    for node in nodes {
        let mut parent = stage_fd.try_clone().map_err(|_| "插件清理目录FD复制失败")?;
        let mut path = PathBuf::new();
        if let Some(parent_path) = node.path.parent() {
            for part in parent_path.components() {
                let Component::Normal(name) = part else {
                    return Err("插件清理父路径无效".into());
                };
                path.push(name);
                let child = open_child(&parent, name)?;
                if receipt.items.get(&path) != Some(&opened_identity(&child)?) {
                    return Err("插件清理父目录inode换代，未跟随".into());
                }
                parent = child;
            }
        }
        let expected = receipt
            .items
            .get(&node.path)
            .ok_or("插件清理项缺自有inode证明")?;
        remove(
            &parent,
            node.path.file_name().ok_or("插件清理项名称缺失")?,
            expected,
            Some(node),
            node.directory,
        )?;
    }
    remove(
        &root_fd,
        std::ffi::OsStr::new(&receipt.stage),
        &receipt.owner,
        None,
        true,
    )?;
    root_fd
        .sync_all()
        .map_err(|_| "插件清理拥有根FD同步失败".into())
}
#[cfg(not(unix))]
fn remove_owned(_receipt: &Receipt, _root: &RootKey) -> Result<(), String> {
    Err("当前平台未提供经过验证的插件dirfd身份清理".into())
}
fn read(root: &RootKey, path: &Path) -> Result<Receipt, String> {
    root.validate()?;
    let metadata = fs::symlink_metadata(path).map_err(|_| "插件回执无法读取")?;
    if !metadata.is_file() || metadata.file_type().is_symlink() || metadata.len() > RECEIPT_BUDGET {
        return Err("插件回执不是有界普通文件".into());
    }
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK | libc::O_CLOEXEC);
    }
    let file = options.open(path).map_err(|_| "插件回执打开失败")?;
    let before = file.metadata().map_err(|_| "插件回执身份无法读取")?;
    let mut bytes = Vec::new();
    (&file)
        .take(RECEIPT_BUDGET + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "插件回执读取失败")?;
    if bytes.len() as u64 != metadata.len()
        || !super::super::same(&metadata, &before)
        || !super::super::same(
            &before,
            &file.metadata().map_err(|_| "插件回执终态无法读取")?,
        )
    {
        return Err("插件回执读取期间变化".into());
    }
    let receipt: Receipt =
        serde_json::from_slice(&bytes).map_err(|_| "插件回执格式损坏，原目录保留")?;
    receipt.validate(root)?;
    if receipt.path(root) != path {
        return Err("插件回执名称不匹配，原目录保留".into());
    }
    Ok(receipt)
}
pub(super) fn recover(root: &RootKey, control: &Control) -> Result<Vec<String>, String> {
    root.validate()?;
    let live: BTreeSet<_> = {
        let state = registry().lock().unwrap_or_else(|error| error.into_inner());
        state
            .tickets
            .values()
            .filter(|ticket| ticket.root.path == root.path)
            .map(|ticket| ticket.stage.clone())
            .chain(state.claimed_stages.iter().cloned())
            .collect()
    };
    let mut warnings = Vec::new();
    let mut seen = 0;
    for entry in fs::read_dir(&root.path).map_err(|_| "插件冷恢复目录读取失败")? {
        control.check()?;
        let entry = entry.map_err(|_| "插件冷恢复目录项无法读取")?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if !name.starts_with(".install-") || !name.ends_with(".receipt.json") {
            continue;
        }
        seen += 1;
        if seen > 32 {
            return Err("插件冷恢复回执超过32项，请检查保留目录".into());
        }
        match read(root, &entry.path()) {
            Ok(receipt) if !live.contains(&receipt.stage) => {
                let result = if !root.path.join(&receipt.stage).exists()
                    && root.path.join(&receipt.plugin_id).exists()
                {
                    receipt.finish_publication(root)
                } else {
                    receipt.retire(root)
                };
                match result {
                    Ok(()) => warnings.push("已回收上次进程留下的自有插件候选；未自动安装".into()),
                    Err(error) => warnings.push(error),
                }
            }
            Ok(_) => {}
            Err(error) => warnings.push(error),
        }
    }
    Ok(warnings)
}
pub(super) fn rename_noreplace(source: &Path, target: &Path) -> Result<(), String> {
    #[cfg(any(target_os = "android", target_os = "linux"))]
    {
        use std::{ffi::CString, os::unix::ffi::OsStrExt};
        let source = CString::new(source.as_os_str().as_bytes()).map_err(|_| "插件回收源含NUL")?;
        let target =
            CString::new(target.as_os_str().as_bytes()).map_err(|_| "插件回收目标含NUL")?;
        if unsafe {
            libc::syscall(
                libc::SYS_renameat2,
                libc::AT_FDCWD,
                source.as_ptr(),
                libc::AT_FDCWD,
                target.as_ptr(),
                libc::RENAME_NOREPLACE,
            )
        } != 0
        {
            return Err("插件目录无覆盖原子移动失败，未回退复制".into());
        }
        Ok(())
    }
    #[cfg(not(any(target_os = "android", target_os = "linux")))]
    {
        let _ = (source, target);
        Err("当前平台没有经过验证的无覆盖原子目录移动".into())
    }
}
pub(super) struct Guard {
    root: RootKey,
    receipt: Option<Receipt>,
    control: Arc<Control>,
}
impl Guard {
    pub(super) fn new(root: &RootKey, receipt: Receipt, control: &Arc<Control>) -> Self {
        Self {
            root: root.clone(),
            receipt: Some(receipt),
            control: control.clone(),
        }
    }
    pub(super) fn update(&mut self, receipt: &Receipt) {
        self.receipt = Some(receipt.clone());
    }
    pub(super) fn release(&mut self) {
        self.receipt = None;
    }
}
impl Drop for Guard {
    fn drop(&mut self) {
        if let Some(receipt) = self.receipt.take() {
            if let Err(error) = receipt.retire(&self.root) {
                *self
                    .control
                    .cleanup_error
                    .lock()
                    .unwrap_or_else(|error| error.into_inner()) = Some(error);
            }
        }
    }
}
