//! The owning Store creates the only profile sink for its active namespace.
//! CAS reads actual disk bytes, then uses the Store's fsync/rename/fsync path,
//! reopens the committed file and returns the document actually decoded there.
use super::*;
use crate::profile::{Document, Repository, Revision};
use sha2::{Digest, Sha256};

impl Store {
    pub(crate) fn profile_writable(&self) -> Result<(), String> {
        self.writable()
    }
    pub(crate) fn open_profile_repository(&self) -> Result<Repository, String> {
        let namespace = self.namespace_root.clone();
        let root = self.root.clone();
        let generation = format!("{:x}", Sha256::digest(root.to_string_lossy().as_bytes()));
        let root_metadata = fs::symlink_metadata(&root)
            .map_err(|error| fs_error("检查画像命名空间", &root, error))?;
        let target = root.join("profile.json");
        let document = optional_json::<Document>(&root, &target, 256 * 1024)?;
        let bytes = document.as_ref().map(crate::profile::encode).transpose()?;
        let sink_generation = generation.clone();
        let mut blocked = false;
        let sink = Box::new(move |next: &Document, expected: &Revision| {
            if blocked {
                return Err("画像此前提交或读回尚未确认，请重新打开状态核对".into());
            }
            let result = (|| {
                validate_namespace(&namespace, &root, &root_metadata)?;
                if expected.generation != sink_generation {
                    return Err("画像命名空间已改变".into());
                }
                let previous =
                    optional_json::<Document>(&root, &target, 256 * 1024)?.unwrap_or_default();
                crate::profile::encode(&previous)?;
                if previous.revision != expected.revision
                    || format!("{:x}", Sha256::digest(previous.text.as_bytes()))
                        != expected.text_sha256
                {
                    return Err("画像磁盘版本已变化，请重新读取；未覆盖原文".into());
                }
                if next.revision != previous.revision.checked_add(1).ok_or("画像版本已耗尽")?
                {
                    return Err("画像提交版本不连续".into());
                }
                let bytes = crate::profile::encode(next)?;
                atomic_bytes(&root, &target, &bytes).map_err(|failure| {
                    if failure.replaced {
                        format!("{}；画像文件已替换但目录同步未确认", failure.message)
                    } else {
                        failure.message
                    }
                })?;
                validate_namespace(&namespace, &root, &root_metadata)?;
                let actual = optional_json::<Document>(&root, &target, 256 * 1024)?
                    .ok_or("画像发布后实际文件缺失")?;
                crate::profile::encode(&actual)?;
                if &actual != next {
                    return Err("画像发布后实际读回与提交不一致".into());
                }
                validate_namespace(&namespace, &root, &root_metadata)?;
                Ok(actual)
            })();
            // Any stale CAS/readback error ends this repository's write epoch.
            // Reads still expose the old verified snapshot; reopening is explicit.
            if result.is_err() {
                blocked = true;
            }
            result
        });
        Repository::open(generation, bytes.as_deref(), sink)
    }
}
fn validate_namespace(namespace: &Path, root: &Path, expected: &Metadata) -> Result<(), String> {
    let actual_root =
        crate::legacy_migration::resolve_active(namespace)?.unwrap_or_else(|| namespace.to_owned());
    if actual_root != root {
        return Err("画像活动命名空间已切换".into());
    }
    let actual = fs::symlink_metadata(root)
        .map_err(|error| fs_error("重新检查画像命名空间", root, error))?;
    if !actual.is_dir() || actual.file_type().is_symlink() {
        return Err("画像命名空间目录身份已变化".into());
    }
    #[cfg(unix)]
    if !same_file(expected, &actual) {
        return Err("画像命名空间inode已变化".into());
    }
    // Store's non-Unix file comparator uses timestamps, which cannot identify
    // a directory because a legitimate atomic write changes those timestamps.
    #[cfg(not(unix))]
    {
        let _ = expected;
        if fs::canonicalize(root).map_err(|_| "画像目录解析失败")? != root {
            return Err("画像目录解析路径已变化".into());
        }
    }
    Ok(())
}
