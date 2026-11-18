//! Old bookmarks become owned native reading positions; exact original row-key
//! fallback order remains separate from the destination layout measurement.
use crate::{
    display::{BodyKind, DisplayError, HistorySource, ReadPosition, ViewSource},
    legacy_import::{LegacyDocumentKind, LegacySnapshot},
};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, HashMap, HashSet},
    path::Path,
};

pub(crate) const MAX_POSITIONS: usize = 8192;
pub(crate) const DOCUMENT_BYTES: u64 = 4 * 1024 * 1024;
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Bookmark {
    pub conversation_id: String,
    #[serde(default)]
    pub row_key: Option<String>,
    #[serde(default)]
    pub message_id: Option<String>,
    #[serde(default)]
    pub run_id: Option<String>,
    #[serde(default)]
    pub nearby_message_ids: Vec<String>,
    #[serde(default)]
    pub offset: i32,
    #[serde(default = "yes")]
    pub follow_latest: bool,
    #[serde(default = "twelve")]
    pub shown_turns: u32,
    #[serde(default)]
    pub total_turns: u32,
    #[serde(default)]
    pub width: u32,
    #[serde(default = "one")]
    pub font_scale: f32,
    #[serde(default)]
    pub top_anchored: bool,
    /// Destination group extent, resolved against the selected source headers.
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "rustRowEndId"
    )]
    pub native_end_id: Option<String>,
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}
fn yes() -> bool {
    true
}
fn twelve() -> u32 {
    12
}
fn one() -> f32 {
    1.0
}
impl Bookmark {
    pub fn validate(&self) -> Result<(), String> {
        if self.conversation_id.is_empty()
            || self.conversation_id.len() > 128
            || self.row_key.as_ref().is_some_and(|key| key.len() > 512)
            || self
                .message_id
                .iter()
                .chain(self.run_id.iter())
                .chain(self.native_end_id.iter())
                .any(|id| id.len() > 128)
            || self.nearby_message_ids.len() > 8
            || self.nearby_message_ids.iter().any(|id| id.len() > 128)
            || self.offset.unsigned_abs() > 10_000_000
            || self.width > 131072
            || !self.font_scale.is_finite()
            || self.font_scale <= 0.0
            || self.font_scale > 256.0
            || self.shown_turns > 131072
            || self.total_turns > 131072
        {
            return Err("旧阅读书签身份或几何预算无效".into());
        }
        Ok(())
    }
    fn position(self) -> Result<ReadPosition, String> {
        self.validate()?;
        Ok(ReadPosition {
            source: ViewSource::Main {
                conversation_id: self.conversation_id.clone(),
            },
            message_id: self.message_id.clone().unwrap_or_default(),
            body_message_id: None,
            footer_from: None,
            body_kind: BodyKind::RowHeader,
            byte_offset: 0,
            pixel_offset: 0.0,
            follow_tail: self.follow_latest,
            legacy: Some(Box::new(self)),
        })
    }
}
pub(crate) fn from_legacy(snapshot: &LegacySnapshot) -> Result<Vec<ReadPosition>, String> {
    let mut result = Vec::new();
    let mut owners = HashSet::new();
    for document in snapshot
        .documents
        .iter()
        .filter(|document| document.kind == LegacyDocumentKind::Viewport)
    {
        if result.len() >= MAX_POSITIONS {
            return Err("旧阅读书签超过8192条，拒绝截断".into());
        }
        let bookmark: Bookmark =
            serde_json::from_value(document.value.clone()).map_err(|_| "旧阅读书签字段无法转换")?;
        let expected = document
            .source
            .relative_path
            .rsplit_once("#position/")
            .map(|(_, id)| id)
            .ok_or("旧阅读书签缺少偏好键拥有者")?;
        if expected != bookmark.conversation_id || !owners.insert(bookmark.conversation_id.clone())
        {
            return Err("旧阅读书签偏好键归属不一致或重复".into());
        }
        result.push(bookmark.position()?);
    }
    Ok(result)
}
pub(crate) fn from_archived(namespace: &Path) -> Result<Vec<ReadPosition>, String> {
    let Some(catalog) = crate::legacy_migration::archive_catalog(namespace)? else {
        return Ok(Vec::new());
    };
    let mappings = catalog
        .assets
        .iter()
        .filter(|mapping| {
            mapping.relative_path == "datastore/Suixing_conversation_viewports.preferences_pb"
        })
        .collect::<Vec<_>>();
    if mappings.len() > 1 {
        return Err("旧阅读书签归档来源有歧义".into());
    }
    let Some(mapping) = mappings.first() else {
        return Ok(Vec::new());
    };
    let bytes = crate::legacy_migration::read_archived_blob_bounded(
        &catalog.generation_root,
        mapping,
        &crate::platform::secrets::SecretBox,
        DOCUMENT_BYTES,
    )?;
    let preferences = crate::legacy_import::decode_preferences(crate::legacy_import::LegacyBlob {
        owning_root: mapping.owning_root.clone(),
        relative_path: mapping.relative_path.clone(),
        original_bytes: bytes,
        sha256: mapping.sha256.clone(),
    })?;
    let mut result = Vec::new();
    for (key, text) in preferences
        .strings
        .iter()
        .filter(|(key, _)| key.starts_with("position/"))
    {
        if result.len() >= MAX_POSITIONS {
            return Err("旧阅读书签归档超过8192条，拒绝截断".into());
        }
        let bookmark: Bookmark =
            serde_json::from_str(text).map_err(|_| "旧阅读书签归档字段无法转换")?;
        if key.strip_prefix("position/") != Some(bookmark.conversation_id.as_str()) {
            return Err("旧阅读书签归档键归属不一致".into());
        }
        result.push(bookmark.position()?);
    }
    if preferences.entries.iter().any(|entry| {
        entry.key.starts_with("position/") && !preferences.strings.contains_key(&entry.key)
    }) {
        return Err("旧阅读书签归档包含非字符串位置，拒绝部分转换".into());
    }
    catalog.recheck()?;
    Ok(result)
}

struct Row {
    first: usize,
    last: usize,
    display: usize,
    rounds: Vec<usize>,
    run: String,
}
/// Recreate the original grouping only for identifying its reading row. Native
/// rendering still owns the destination content and the measured pixel offset.
pub(crate) fn resolve(
    position: &mut ReadPosition,
    source: &impl HistorySource,
    selected: &[usize],
) -> Result<(), DisplayError> {
    let Some(bookmark) = position.legacy.as_mut() else {
        return Ok(());
    };
    if bookmark.follow_latest {
        position.follow_tail = true;
        position.legacy = None;
        return Ok(());
    }
    let headers = source.headers();
    let original_id = |index: usize| {
        let header = &headers[index];
        header.legacy_original_id.as_deref().unwrap_or(&header.id)
    };
    let mut rows: Vec<Row> = Vec::new();
    let mut open: Option<usize> = None;
    for &index in selected {
        let header = headers.get(index).ok_or(DisplayError::Stale)?;
        let visible = match header.role.as_str() {
            "assistant" => header.text.non_blank || header.thinking.non_blank,
            "user" => {
                header.text.non_blank || header.image_base64.present || header.image_count() > 0
            }
            "tool" => true,
            "system" => header.text.non_blank,
            _ => false,
        };
        let run = header.run_id.clone().unwrap_or_default();
        match header.role.as_str() {
            "tool" => {
                if let Some(open) = open {
                    rows[open].last = index;
                } else {
                    rows.push(Row {
                        first: index,
                        last: index,
                        display: index,
                        rounds: vec![index],
                        run,
                    });
                    open = Some(rows.len() - 1);
                }
            }
            "assistant" if visible => {
                if let Some(open) = open.filter(|&open| rows[open].run == run) {
                    let row = &mut rows[open];
                    row.last = index;
                    row.display = index;
                    row.rounds.push(index);
                } else {
                    rows.push(Row {
                        first: index,
                        last: index,
                        display: index,
                        rounds: vec![index],
                        run,
                    });
                    open = Some(rows.len() - 1);
                }
            }
            "assistant" => {}
            "user" if header.steer => {
                if let Some(open) = open {
                    rows[open].last = index;
                } else {
                    rows.push(Row {
                        first: index,
                        last: index,
                        display: index,
                        rounds: vec![index],
                        run,
                    });
                }
            }
            _ if visible => {
                rows.push(Row {
                    first: index,
                    last: index,
                    display: index,
                    rounds: vec![index],
                    run,
                });
                if header.role != "system" {
                    open = None;
                }
            }
            _ => {}
        }
    }
    let mut last_by_run = HashMap::new();
    for (index, row) in rows.iter().enumerate() {
        let header = &headers[row.display];
        if header.role == "assistant" && !row.run.is_empty() {
            last_by_run.insert(row.run.as_str(), index);
        }
    }
    let mut keys = rows
        .iter()
        .enumerate()
        .map(|(index, row)| {
            if !row.run.is_empty() && last_by_run.get(row.run.as_str()) == Some(&index) {
                format!("run:{}:{}", bookmark.conversation_id, row.run)
            } else {
                format!(
                    "message:{}:{}",
                    bookmark.conversation_id,
                    original_id(row.first)
                )
            }
        })
        .collect::<Vec<_>>();
    let mut occurrences = HashMap::new();
    for key in keys.iter_mut().rev() {
        let count = occurrences.entry(key.clone()).or_insert(0usize);
        if *count > 0 {
            *key = format!("duplicate:{count}:{key}");
        }
        *count += 1;
    }
    let row = bookmark
        .row_key
        .as_ref()
        .and_then(|key| keys.iter().position(|candidate| candidate == key))
        .or_else(|| {
            rows.iter().rposition(|row| {
                bookmark.message_id.as_ref().is_some_and(|id| {
                    original_id(row.display) == id.as_str()
                        || row.rounds.iter().any(|&at| original_id(at) == id.as_str())
                }) || bookmark
                    .run_id
                    .as_ref()
                    .is_some_and(|run| headers[row.display].run_id.as_ref() == Some(run))
            })
        })
        .or_else(|| {
            bookmark.nearby_message_ids.iter().find_map(|id| {
                rows.iter().position(|row| {
                    original_id(row.display) == id.as_str()
                        || row.rounds.iter().any(|&at| original_id(at) == id.as_str())
                })
            })
        });
    if let Some(index) = row {
        let row = &rows[index];
        position.message_id = headers[row.first].id.clone();
        bookmark.native_end_id = Some(headers[row.last].id.clone());
        position.body_kind = BodyKind::RowHeader;
        position.follow_tail = false;
    } else {
        position.follow_tail = true;
        position.message_id = String::new();
        position.legacy = None;
    }
    Ok(())
}

/// Original geometry compatibility and legacy bottom-edge conversion, now in
/// destination points. A changed width/font or missing extent restores the row
/// boundary, which is also the original reader's geometry-change fallback.
pub(crate) fn offset(
    bookmark: &Bookmark,
    pixels_per_point: f32,
    width_points: f32,
    font_points: f32,
    row_points: f32,
    reading_points: f32,
    extent_known: bool,
) -> f32 {
    let ppp = pixels_per_point.max(0.1);
    let compatible = (bookmark.width == 0
        || (bookmark.width as f32 - width_points * ppp).abs() <= 2.0)
        && (bookmark.font_scale - font_points).abs() < 0.01;
    if !compatible {
        return 0.0;
    }
    if bookmark.top_anchored {
        bookmark.offset as f32 / ppp
    } else if extent_known {
        row_points - reading_points - bookmark.offset as f32 / ppp
    } else {
        0.0
    }
}
