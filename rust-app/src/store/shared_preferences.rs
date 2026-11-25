use super::*;
use crate::legacy_import::{self, LegacyBlob, LegacyPreferenceValue, LegacyPreferences};

pub(super) const READS: &str = "datastore/Suixing_conversation_reads.preferences_pb";
pub(super) const VIEWPORTS: &str = "datastore/Suixing_conversation_viewports.preferences_pb";
const LIMIT: u64 = crate::legacy_reading::DOCUMENT_BYTES;

fn bytes(files: &Path, name: &str) -> Result<Option<Vec<u8>>, String> {
    let path = files.join(name);
    let file = match checked_file(files, &path) {
        Ok(file) => file,
        Err(_) if !path.exists() => return Ok(None),
        Err(error) => return Err(error),
    };
    let mut data = Vec::new();
    file.take(LIMIT + 1)
        .read_to_end(&mut data)
        .map_err(|error| fs_error("读取会话偏好", &path, error))?;
    if data.len() as u64 > LIMIT {
        return Err("会话偏好超过存储预算".into());
    }
    Ok(Some(data))
}

pub(super) fn read(files: &Path, name: &str) -> Result<Option<LegacyPreferences>, String> {
    bytes(files, name)?
        .map(|original_bytes| {
            legacy_import::decode_preferences(LegacyBlob {
                owning_root: files.to_owned(),
                relative_path: name.into(),
                original_bytes,
                sha256: String::new(),
            })
        })
        .transpose()
}

pub(super) fn save(
    files: &Path,
    name: &str,
    key: &str,
    value: Option<&LegacyPreferenceValue>,
) -> Result<(), String> {
    let original = bytes(files, name)?.unwrap_or_default();
    let data = legacy_import::edit_preference(&original, key, value)?;
    if data == original {
        return Ok(());
    }
    owned_directory(files, "datastore")?;
    atomic_document(files, &files.join(name), LIMIT, |writer| {
        writer.write_all(&data).map_err(|error| error.to_string())
    })
    .map_err(|failure| failure.message)
}
