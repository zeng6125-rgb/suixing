//! The owning Store creates the only profile sink for its active namespace.
//! CAS reads actual disk bytes, then uses the Store's fsync/rename/fsync path,
//! reopens the committed file and returns the document actually decoded there.
use super::*;
use crate::profile::{Document, Repository, Revision};
use sha2::{Digest, Sha256};

mod shared;

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
        if let Some(files) = &self.shared_files_root {
            return shared::open(self, files.clone(), generation, root_metadata);
        }
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{profile::Writer, runtime_host::RunKey};
    use serde_json::{Value, json};

    #[test]
    fn shared_profile_survives_compose_writes_without_replaying_rust_turns() {
        let files = std::env::temp_dir().join(fresh_id("shared-profile").unwrap());
        fs::create_dir_all(&files).unwrap();
        let text = files.join("profile.md");
        let backup = files.join("profile.md.bak");
        let state = files.join("profile.state.json");
        fs::write(&text, "用户画像").unwrap();
        fs::write(&backup, "上一版").unwrap();
        fs::write(
            &state,
            json!({"lastProfileAt":30,"profileTurnMark":7,"futureField":{"keep":true}}).to_string(),
        )
        .unwrap();

        let mut store =
            Store::open_with_shared_files(files.join("rust"), Some(files.clone())).unwrap();
        let mut profile = store.open_profile_repository().unwrap();
        assert_eq!(profile.document().text, "用户画像");
        assert_eq!(profile.document().previous_text.as_deref(), Some("上一版"));
        assert_eq!(profile.document().state.last_profile_at, 30);
        let turn = store
            .record_finished_profile_turn(
                RunKey {
                    run_id: "profile-run".into(),
                    conversation_id: "profile-chat".into(),
                },
                true,
            )
            .unwrap();
        assert_eq!(
            profile
                .bump_finished_turn(turn.ordinal, true, false)
                .unwrap(),
            8
        );
        let written: Value = serde_json::from_slice(&fs::read(&state).unwrap()).unwrap();
        assert_eq!(written["profileTurnMark"], 8);
        assert_eq!(written["futureField"], json!({"keep":true}));

        // Recover an interruption after the shared state rename, before the private checkpoint.
        fs::write(
            files.join("rust/profile-progress.json"),
            r#"{"revision":0,"completed_turn_ordinal":0}"#,
        )
        .unwrap();
        let mut profile = store.open_profile_repository().unwrap();
        assert_eq!(
            profile
                .bump_finished_turn(turn.ordinal, true, false)
                .unwrap(),
            8
        );
        profile
            .replace(&profile.revision(), "Rust 画像".into(), Writer::User)
            .unwrap();
        assert_eq!(fs::read_to_string(&text).unwrap(), "Rust 画像");
        assert_eq!(fs::read_to_string(&backup).unwrap(), "用户画像");
        assert!(matches!(
            profile
                .apply_summary(&profile.revision(), "+ 喜欢中文", 40)
                .unwrap(),
            crate::profile::SummaryOutcome::Updated
        ));
        let written: Value = serde_json::from_slice(&fs::read(&state).unwrap()).unwrap();
        assert_eq!(written["lastProfileAt"], 40);
        assert_eq!(written["profileTurnMark"], 0);
        assert_eq!(written["futureField"], json!({"keep":true}));

        // Compose 0.9.10 rewrites the state with the two fields it understands.
        fs::write(&text, "Compose 画像").unwrap();
        fs::write(&state, r#"{"lastProfileAt":50,"profileTurnMark":1}"#).unwrap();
        assert!(
            profile
                .replace(&profile.revision(), "过期编辑".into(), Writer::User)
                .is_err()
        );
        let mut reopened = store.open_profile_repository().unwrap();
        assert_eq!(reopened.document().text, "Compose 画像");
        assert_eq!(reopened.document().state.last_profile_at, 50);
        assert_eq!(
            reopened
                .bump_finished_turn(turn.ordinal, true, false)
                .unwrap(),
            1
        );
        assert_eq!(fs::read_to_string(&text).unwrap(), "Compose 画像");
    }

    #[test]
    fn shared_profile_publishes_rust_only_data_once() {
        let files = std::env::temp_dir().join(fresh_id("shared-profile").unwrap());
        let root = files.join("rust");
        let stand_alone = Store::open(root.clone()).unwrap();
        let mut profile = stand_alone.open_profile_repository().unwrap();
        profile
            .replace(&profile.revision(), "Rust 原画像".into(), Writer::User)
            .unwrap();
        profile.bump_finished_turn(1, true, false).unwrap();

        let store = Store::open_with_shared_files(root.clone(), Some(files.clone())).unwrap();
        let profile = store.open_profile_repository().unwrap();
        let text = files.join("profile.md");
        assert_eq!(fs::read_to_string(&text).unwrap(), "Rust 原画像");
        assert_eq!(profile.document().state.profile_turn_mark, 1);
        assert_eq!(profile.document().completed_turn_ordinal, 1);
        fs::remove_file(&text).unwrap();
        assert_eq!(store.open_profile_repository().unwrap().document().text, "");
        assert!(root.join("profile.json").exists());
    }
}
