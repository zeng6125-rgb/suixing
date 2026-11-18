//! Read-only Android AtomicFile selection; .new is never a committed source.
use crate::legacy_import::LegacyBlob;
use sha2::{Digest, Sha256};
use std::{
    fs::{self, Metadata, OpenOptions},
    io::Read,
    path::{Path, PathBuf},
};
pub struct AtomicCandidate {
    pub active: Option<LegacyBlob>,
    pub variants: Vec<LegacyBlob>,
    pub warnings: Vec<String>,
}
pub fn scan_terminal_metadata(no_backup: &Path) -> Result<AtomicCandidate, String> {
    if !no_backup.is_absolute() {
        return Err("终端AtomicFile需要明确绝对noBackup根".into());
    }
    let root = fs::canonicalize(no_backup).map_err(|_| "旧终端noBackup根不可读")?;
    let mut variants = Vec::new();
    for name in [
        "terminal-sessions.json",
        "terminal-sessions.json.bak",
        "terminal-sessions.json.new",
    ] {
        let path = root.join(name);
        let first = match fs::symlink_metadata(&path) {
            Ok(first) => first,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
            Err(_) => return Err("旧终端AtomicFile候选不可读".into()),
        };
        if !first.is_file() || first.file_type().is_symlink() || first.len() > 512 * 1024 {
            return Err("旧终端AtomicFile候选不是有界普通文件".into());
        }
        let mut options = OpenOptions::new();
        options.read(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK);
        }
        let mut file = options
            .open(&path)
            .map_err(|_| "旧终端AtomicFile候选打开失败")?;
        let opened = file
            .metadata()
            .map_err(|_| "旧终端AtomicFile打开信息不可读")?;
        if !same(&first, &opened) {
            return Err("旧终端AtomicFile在打开期间变化".into());
        }
        let mut bytes = Vec::new();
        (&mut file)
            .take(first.len() + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| "旧终端AtomicFile读取失败")?;
        let after = fs::symlink_metadata(&path).map_err(|_| "旧终端AtomicFile读取后不可读")?;
        if bytes.len() as u64 != first.len()
            || !same(&first, &after)
            || !same(&first, &file.metadata().map_err(|_| "旧终端打开文件变化")?)
        {
            return Err("旧终端AtomicFile来源边界在读取期间变化".into());
        }
        variants.push(LegacyBlob {
            owning_root: root.clone(),
            relative_path: name.into(),
            sha256: format!("{:x}", Sha256::digest(&bytes)),
            original_bytes: bytes,
        });
    }
    let active = variants
        .iter()
        .find(|blob| blob.relative_path.ends_with(".bak"))
        .or_else(|| {
            variants
                .iter()
                .find(|blob| blob.relative_path == "terminal-sessions.json")
        })
        .cloned();
    let mut warnings = Vec::new();
    if variants
        .iter()
        .any(|blob| blob.relative_path.ends_with(".new"))
    {
        warnings
            .push("发现未提交的terminal-sessions.json.new，仅保留原文，不将其作为活动记录".into());
    }
    if active
        .as_ref()
        .is_some_and(|blob| blob.relative_path.ends_with(".bak"))
    {
        warnings.push(
            "AtomicFile存在.bak，按旧读取语义选择备份候选；没有修复、重命名或删除旧文件".into(),
        );
    }
    Ok(AtomicCandidate {
        active,
        variants,
        warnings,
    })
}
fn same(a: &Metadata, b: &Metadata) -> bool {
    let ordinary = a.is_file()
        && b.is_file()
        && !a.file_type().is_symlink()
        && !b.file_type().is_symlink()
        && a.len() == b.len()
        && a.modified().ok() == b.modified().ok();
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        ordinary && a.dev() == b.dev() && a.ino() == b.ino()
    }
    #[cfg(not(unix))]
    {
        ordinary
    }
}
