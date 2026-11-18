//! One performance ledger in the active Store namespace. The existing atomic
//! Store publication contract supplies replace/dirsync failure handling.
use super::Store;
use crate::performance::{Document, Record};
impl Store {
    pub(crate) fn performance_document(&self) -> Result<Document, String> {
        let path = self.root.join("perf.json");
        let document = super::optional_json::<Document>(
            &self.root,
            &path,
            crate::performance::MAX_DOCUMENT_BYTES as u64,
        )?
        .unwrap_or_else(Document::new);
        document.validate()?;
        Ok(document)
    }
    pub(crate) fn record_performance(&mut self, record: Record) -> Result<Vec<Record>, String> {
        let mut document = self.performance_document()?;
        if document.append(record)? {
            self.persist(
                &self.root.join("perf.json"),
                &document,
                crate::performance::MAX_DOCUMENT_BYTES as u64,
            )?;
        }
        Ok(document.recent())
    }
}
