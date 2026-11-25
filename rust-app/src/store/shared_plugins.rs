use super::*;

pub(super) fn directory(root: &Path, files: Option<&Path>) -> Result<PathBuf, String> {
    let Some(files) = files else {
        return owned_directory(root, "plugins");
    };
    #[cfg(target_os = "android")]
    if !files.join("plugins").exists() && root.join("plugins").is_dir() {
        use crate::{network::CancelToken, plugin_assets};

        let cancel = CancelToken::new();
        let tree = plugin_assets::scan_tree(root, Path::new("plugins"), true, &cancel)?;
        if tree.layout.len() > 1 {
            let stage = owned_directory(files, &fresh_id(".plugin-copy")?)?;
            plugin_assets::write_tree(&stage, &tree, &cancel)?;
            let target = files.join("plugins");
            fs::rename(stage.join("plugins"), &target)
                .map_err(|error| fs_error("发布旧 Rust 插件", &target, error))?;
            let _ = fs::remove_dir(&stage);
            sync_directory(files).map_err(|error| fs_error("同步共用插件目录", files, error))?;
        }
    }
    owned_directory(files, "plugins")
}
