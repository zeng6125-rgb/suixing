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
}
