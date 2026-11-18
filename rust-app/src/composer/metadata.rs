//! New Rust attachment references live in existing unknown metadata storage.
//! Older arbitrary fields named "attachments" remain untouched and readable.
use super::{
    AttachmentRef, MAX_ATTACHMENT_METADATA_JSON, MAX_ATTACHMENTS, json_bytes, validate_refs,
};
use serde::Serialize;
use serde_json::Value;
use std::collections::BTreeMap;
pub const KEY: &str = "rustComposerAttachmentsV1";
pub fn insert(extra: &mut BTreeMap<String, Value>, refs: &[AttachmentRef]) -> Result<(), String> {
    if refs.is_empty() {
        return Ok(());
    }
    #[derive(Serialize)]
    struct Envelope<'a> {
        version: u32,
        refs: &'a [AttachmentRef],
    }
    validate_refs(refs)?;
    let envelope = Envelope { version: 1, refs };
    json_bytes(&envelope, MAX_ATTACHMENT_METADATA_JSON)?;
    let value = serde_json::to_value(envelope).map_err(|_| "附件元数据无法编码")?;
    // Caller creates a fresh Message. Do not replace a historical unknown
    // envelope silently during edit/resend or conversion.
    if extra.get(KEY).is_some_and(|previous| previous != &value) {
        return Err("附件元数据键已有不同原文，未覆盖".into());
    }
    extra.insert(KEY.into(), value);
    Ok(())
}
pub fn known_refs(extra: &BTreeMap<String, Value>) -> Result<Option<Vec<AttachmentRef>>, String> {
    let Some(value) = extra.get(KEY) else {
        return Ok(None);
    };
    if value.get("version").and_then(Value::as_u64) != Some(1)
        || !value.get("refs").is_some_and(Value::is_array)
    {
        return Ok(None); // Unknown historical/future envelope remains raw.
    }
    if value["refs"]
        .as_array()
        .is_some_and(|refs| refs.len() > MAX_ATTACHMENTS)
    {
        return Err("Rust附件引用数量超过256".into());
    }
    json_bytes(value, MAX_ATTACHMENT_METADATA_JSON)?;
    let refs: Vec<AttachmentRef> =
        serde_json::from_value(value["refs"].clone()).map_err(|_| "Rust附件引用损坏")?;
    validate_refs(&refs)?;
    Ok(Some(refs))
}
// Legacy StoredMessage imagesBase64/imageBase64 and all unknown extra fields
// are independent of this envelope. Regenerate/plain edit/range edit/resend copy
// the original metadata; they do not require original pictures to have JPEG
// inbox refs or a new filename. Unknown keys and legacy absolute path notes are
// preserved by conversion; loss of an old file is an access error, not permission
// to silently delete its reference from the recorded question.
