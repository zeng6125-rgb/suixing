use super::*;
use crate::goal::Goal;
use serde_json::{Map, Value};

pub(super) const LIMIT: u64 = 256 * 1024;
const FIELDS: [(&str, &str); 3] = [
    ("chat_id", "chatId"),
    ("updated_at", "updatedAt"),
    ("wake_at", "wakeAt"),
];

pub(super) fn read(files: &Path) -> Result<Option<Goal>, String> {
    let Some(mut data) =
        optional_json::<Map<String, Value>>(files, &files.join("goal.json"), LIMIT)?
    else {
        return Ok(None);
    };
    for (rust, compose) in FIELDS {
        if let Some(value) = data.remove(compose) {
            data.insert(rust.into(), value);
        }
    }
    if let Some(status) = data.get("status").and_then(Value::as_str) {
        data.insert("status".into(), status.to_ascii_lowercase().into());
    }
    serde_json::from_value(Value::Object(data))
        .map(Some)
        .map_err(|error| format!("读取共用目标失败：{error}"))
}

pub(super) fn write(files: &Path, goal: &Goal) -> Result<(), CommitFailure> {
    let path = files.join("goal.json");
    let mut data = optional_json::<Map<String, Value>>(files, &path, LIMIT)?.unwrap_or_default();
    let mut fields = serde_json::to_value(goal).map_err(|error| error.to_string())?;
    let fields = fields
        .as_object_mut()
        .expect("Goal serializes as an object");
    for (rust, compose) in FIELDS {
        let value = fields.remove(rust).expect("Goal serializes every field");
        fields.insert(compose.into(), value);
    }
    let status = fields["status"].as_str().expect("Goal status is a string");
    fields.insert("status".into(), status.to_ascii_uppercase().into());
    data.extend(std::mem::take(fields));
    atomic_json(files, &path, &data, LIMIT)
}
