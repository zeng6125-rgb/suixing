use super::*;
impl Store {
    pub(crate) fn admit_avatar_write(&self) -> Result<(), String> {
        self.writable()
    }
    pub(crate) fn avatar_document_root(&self) -> PathBuf {
        self.root.clone()
    }
    pub(crate) fn avatar_namespace(&self) -> PathBuf {
        self.namespace_root.clone()
    }
    pub(crate) fn shared_avatar_root(&self) -> Option<&Path> {
        self.shared_files_root.as_deref()
    }

    pub(super) fn publish_assistant_avatars(&self, settings: &Settings) -> Result<(), String> {
        let Some(files) = self
            .shared_avatar_root()
            .filter(|files| *files != self.namespace_root)
        else {
            return Ok(());
        };
        for path in settings
            .assistants
            .iter()
            .map(|assistant| &assistant.avatar_path)
        {
            if path.is_empty()
                || files.join(path).exists()
                || !self.namespace_root.join(path).exists()
            {
                continue;
            }
            let bytes = crate::avatars::native_read(&self.namespace_root, path)?;
            owned_directory(files, "assistants")?;
            atomic_bytes(files, &files.join(path), &bytes).map_err(|failure| failure.message)?;
        }
        Ok(())
    }
}

#[cfg(test)]
#[test]
fn shared_file_assets_publishes_only_referenced_private_avatar() {
    let files =
        std::env::temp_dir().join(format!("jagent-avatars-{}", crate::controller::unique_id()));
    let state = files.join("rust-state");
    fs::create_dir_all(state.join("assistants")).unwrap();
    let path = "assistants/user-avatar.png";
    let mut png = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut png, 1, 1);
        encoder.set_color(png::ColorType::Rgba);
        encoder
            .write_header()
            .unwrap()
            .write_image_data(&[0, 0, 0, 255])
            .unwrap();
    }
    fs::write(state.join(path), &png).unwrap();
    fs::write(state.join("assistants/unused.png"), &png).unwrap();
    let store = Store::open_with_shared_files(state, Some(files.clone())).unwrap();
    let mut settings = store.settings.clone();
    settings.assistants.push(crate::configuration::Assistant {
        avatar_path: path.into(),
        ..Default::default()
    });

    store.publish_assistant_avatars(&settings).unwrap();
    assert_eq!(fs::read(files.join(path)).unwrap(), png);
    assert!(!files.join("assistants/unused.png").exists());
    fs::write(files.join(path), b"edited by Compose").unwrap();
    store.publish_assistant_avatars(&settings).unwrap();
    assert_eq!(fs::read(files.join(path)).unwrap(), b"edited by Compose");

    fs::remove_dir_all(files).unwrap();
}
