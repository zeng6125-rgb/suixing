//! Bootstrap isolates new durable state from the original application's formats.
use std::{
    fs::{self, OpenOptions},
    io::Read,
    path::{Path, PathBuf},
};

pub struct StatePaths {
    pub state_root: PathBuf,
    pub legacy_root: PathBuf,
}

/// Classify a bounded prefix. Old large/corrupt caches belong to the legacy
/// reader, not to format selection during application bootstrap.
fn marker(path: &Path) -> Result<Option<u64>, String> {
    let original = match fs::symlink_metadata(path) {
        Ok(value) => value,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(_) => return Err("无法检查已有状态文件".into()),
    };
    if !original.is_file() || original.file_type().is_symlink() {
        return Err("已有状态不是普通文件；原文件保留".into());
    }
    let mut options = OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK);
    }
    let file = options.open(path).map_err(|_| "无法打开已有状态文件")?;
    let opened = file.metadata().map_err(|_| "无法检查已打开状态文件")?;
    if !opened.is_file() || !same_file(&original, &opened) {
        return Err("状态文件读取前已被替换，未切换存储位置".into());
    }
    let mut prefix = Vec::new();
    (&file)
        .take(64 * 1024)
        .read_to_end(&mut prefix)
        .map_err(|_| "无法读取状态格式标识")?;
    let after = file.metadata().map_err(|_| "无法复核状态文件")?;
    let named = fs::symlink_metadata(path).map_err(|_| "状态文件读取期间已移动")?;
    if !after.is_file()
        || !same_file(&opened, &after)
        || !same_file(&after, &named)
        || opened.len() != after.len()
        || opened.modified().ok() != after.modified().ok()
    {
        return Err("状态文件读取期间变化，未切换存储位置".into());
    }
    // Rust's writer emits version first. Small edited envelopes are also recognized.
    if let Ok(text) = std::str::from_utf8(&prefix) {
        let header = regex::Regex::new(r#"^\s*\{\s*"version"\s*:\s*([0-9]+)\s*[,}]"#)
            .map_err(|_| "状态格式规则无效")?;
        if let Some(captures) = header.captures(text) {
            return captures[1]
                .parse()
                .map(Some)
                .map_err(|_| "状态版本号溢出".into());
        }
    }
    if opened.len() <= prefix.len() as u64 {
        if let Ok(value) = serde_json::from_slice::<serde_json::Value>(&prefix) {
            if let Some(version) = value.get("version") {
                return version
                    .as_u64()
                    .map(Some)
                    .ok_or_else(|| "已有状态版本标识无效；原文件保留".into());
            }
            return Ok(None);
        }
    }
    Ok(None)
}

fn same_file(a: &fs::Metadata, b: &fs::Metadata) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        a.dev() == b.dev() && a.ino() == b.ino()
    }
    #[cfg(not(unix))]
    {
        a.is_file() == b.is_file() && a.len() == b.len() && a.modified().ok() == b.modified().ok()
    }
}

pub fn state_paths(files: &Path) -> Result<StatePaths, String> {
    fs::create_dir_all(files).map_err(|_| "无法创建应用数据目录")?;
    let root = fs::canonicalize(files).map_err(|_| "无法解析应用数据目录")?;
    let mut experimental = false;
    let mut incompatible = false;
    for name in [
        "settings.json",
        "memories.json",
        "skills.json",
        "goal.json",
        "mcp.json",
        "usage.json",
        "schedules.json",
    ] {
        let path = root.join(name);
        match marker(&path)? {
            Some(1) => experimental = true,
            Some(_) => return Err("已有 Rust 状态版本不受支持；未创建替代的空目录".into()),
            None if path.exists() => incompatible = true,
            None => {}
        }
    }
    let conversation_root = root.join("conversations");
    if conversation_root.is_dir() {
        let mut observed = false;
        for (index, entry) in fs::read_dir(&conversation_root)
            .map_err(|_| "无法检查已有会话目录")?
            .enumerate()
        {
            if index >= 8192 {
                return Err("已有会话目录超过预算；未切换状态位置".into());
            }
            let entry = entry.map_err(|_| "无法读取已有会话目录项")?;
            if entry.file_name().to_string_lossy().ends_with(".tmp") {
                continue;
            }
            observed = true;
            if entry
                .path()
                .extension()
                .is_none_or(|extension| extension != "json")
            {
                incompatible = true;
                continue;
            }
            match marker(&entry.path())? {
                Some(1) => experimental = true,
                Some(_) => return Err("已有会话版本不受支持；原文件保留".into()),
                None => incompatible = true,
            }
        }
        if observed && !experimental {
            return Err("已有会话但无法确认存储格式；未切换到空状态目录".into());
        }
    }
    if experimental {
        if incompatible {
            return Err("检测到实验 Rust 状态与其它格式混用；未覆盖或隐藏原数据".into());
        }
        return Ok(StatePaths {
            state_root: root.clone(),
            legacy_root: root,
        });
    }
    let target = root.join("rust-state");
    fs::create_dir_all(&target).map_err(|_| "无法创建 Rust 状态目录")?;
    let actual = fs::canonicalize(&target).map_err(|_| "无法解析 Rust 状态目录")?;
    if actual != target {
        return Err("Rust 状态目录跳转到其它位置，未开始写入".into());
    }
    Ok(StatePaths {
        state_root: actual,
        legacy_root: root,
    })
}

#[derive(Clone, Debug, Default)]
pub struct LegacyPreview {
    pub available: bool,
    pub source_fingerprint: String,
    pub conversations: usize,
    pub documents: usize,
    pub preference_stores: usize,
    pub workspace_entries: usize,
    pub original_assets: usize,
    pub warnings: Vec<String>,
}
