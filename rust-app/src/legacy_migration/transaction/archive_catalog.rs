//! This module is included by transaction.rs and uses its existing nofollow
//! regular-file reader. Only a pinned active manifest supplies asset authority.
use super::*;
pub struct ArchiveCatalog {
    namespace: PathBuf,
    pointer_sha256: String,
    manifest_sha256: String,
    pub generation_root: PathBuf,
    pub source_fingerprint: String,
    pub assets: Vec<AssetMapping>,
}
impl ArchiveCatalog {
    pub fn recheck(&self) -> Result<(), String> {
        let pointer = pointer_bytes(&self.namespace)?.ok_or("用量归档的活动指针已消失")?;
        if digest(&pointer) != self.pointer_sha256 {
            return Err("用量归档的活动代已切换".into());
        }
        let manifest = read_regular(&self.generation_root, Path::new("complete.json"), MAX_FILE)?;
        if digest(&manifest) != self.manifest_sha256 {
            return Err("用量归档完成标记已变化".into());
        }
        Ok(())
    }
}
pub fn archive_catalog(namespace: &Path) -> Result<Option<ArchiveCatalog>, String> {
    let namespace = checked_root(namespace)?;
    let Some(pointer_bytes) = pointer_bytes(&namespace)? else {
        return Ok(None);
    };
    let pointer: ActivePointer =
        serde_json::from_slice(&pointer_bytes).map_err(|_| "用量归档活动指针损坏")?;
    if pointer.version != 1 {
        return Err("用量归档指针版本无效".into());
    }
    crate::legacy_migration::chat_archive::identity(&pointer.id)?;
    let generation = existing_directory(&namespace, &PathBuf::from(GENERATIONS).join(&pointer.id))?;
    let bytes = read_regular(&generation, Path::new("complete.json"), MAX_FILE)?;
    if digest(&bytes) != pointer.manifest_sha256 {
        return Err("用量归档完成标记hash失配".into());
    }
    let manifest: Manifest = serde_json::from_slice(&bytes).map_err(|_| "用量归档完成标记损坏")?;
    if manifest.version != 1
        || manifest.id != pointer.id
        || manifest.assets.len() > MAX_FILES
        || manifest.source_fingerprint.len() != 64
        || !manifest
            .source_fingerprint
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit())
    {
        return Err("用量归档身份、指纹或预算无效".into());
    }
    let result = ArchiveCatalog {
        namespace,
        pointer_sha256: digest(&pointer_bytes),
        manifest_sha256: pointer.manifest_sha256,
        generation_root: generation,
        source_fingerprint: manifest.source_fingerprint,
        assets: manifest.assets,
    };
    result.recheck()?;
    Ok(Some(result))
}
