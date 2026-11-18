//! The durable app-wide task board. The actor serializes all reads and commits.
use crate::state::ToolCall;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::{BTreeMap, HashSet};
use suixing_search::rust_protocol::ToolDefinition;

const MAX_ITEMS: usize = 1024;
const MAX_BYTES: usize = 2 * 1024 * 1024;
const RESULT_BYTES: usize = 32 * 1024;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Item {
    #[serde(default)]
    pub id: String,
    #[serde(alias = "text")]
    pub subject: String,
    #[serde(default = "pending")]
    pub status: String,
    #[serde(flatten)]
    pub extra: BTreeMap<String, Value>,
}
fn pending() -> String {
    "pending".into()
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Board {
    pub version: u32,
    pub revision: u64,
    pub items: Vec<Item>,
}
impl Default for Board {
    fn default() -> Self {
        Self {
            version: 1,
            revision: 0,
            items: Vec::new(),
        }
    }
}

#[derive(Clone, Debug)]
pub enum Operation {
    Read {
        offset: usize,
        limit: usize,
    },
    Write {
        items: Vec<Item>,
        expected_revision: Option<u64>,
    },
    SetStatus {
        id: String,
        status: String,
        expected_revision: u64,
    },
    Delete {
        id: String,
        expected_revision: u64,
    },
}

pub fn definitions() -> Vec<ToolDefinition> {
    vec![ToolDefinition {
        name: "todo_read".into(),
        description: "Read the durable app-wide task list and its revision. Use offset/limit to read every page when next_offset is present. The board survives context compression and application restart.".into(),
        parameters: json!({"type":"object","properties":{
            "offset":{"type":"integer","minimum":0,"default":0},
            "limit":{"type":"integer","minimum":1,"maximum":200,"default":200}},"additionalProperties":false}),
    }, ToolDefinition {
        name: "todo_write".into(),
        description: "Replace the entire app-wide task list in execution order, never append. Use an empty items array to clear it. Read the board first and pass expected_revision to reject concurrent changes. Empty IDs are numbered t1, t2, and so on.".into(),
        parameters: json!({"type":"object","properties":{
            "items":{"type":"array","maxItems":1024,"items":{"type":"object","properties":{
                "id":{"type":"string"},"subject":{"type":"string"},
                "status":{"type":"string","enum":["pending","in_progress","completed"],"default":"pending"}},
                "required":["subject"],"additionalProperties":false}},
            "expected_revision":{"type":"integer","minimum":0}},"required":["items"],"additionalProperties":false}),
    }]
}

pub fn prepare(call: &ToolCall) -> Result<Operation, String> {
    if call.arguments.len() > MAX_BYTES {
        return Err("任务清单工具参数超过2 MiB预算".into());
    }
    let value: Value =
        serde_json::from_str(&call.arguments).map_err(|_| "任务清单参数不是有效JSON")?;
    let object = value.as_object().ok_or("任务清单参数必须是对象")?;
    match call.name.as_str() {
        "todo_read" => {
            if object
                .keys()
                .any(|key| !["offset", "limit"].contains(&key.as_str()))
            {
                return Err("读取任务清单包含未知参数".into());
            }
            let offset = number(object.get("offset"), 0, MAX_ITEMS)?;
            let limit = number(object.get("limit"), 200, 200)?;
            if limit == 0 {
                return Err("读取任务清单limit必须大于0".into());
            }
            Ok(Operation::Read { offset, limit })
        }
        "todo_write" => {
            if object
                .keys()
                .any(|key| !["items", "expected_revision"].contains(&key.as_str()))
            {
                return Err("写入任务清单包含未知参数".into());
            }
            let array = object
                .get("items")
                .and_then(Value::as_array)
                .ok_or("请传完整items数组，空数组会清空清单")?;
            if array.len() > MAX_ITEMS {
                return Err("任务清单超过1024项预算".into());
            }
            let mut items = Vec::with_capacity(array.len());
            for entry in array {
                let row = entry.as_object().ok_or("任务项必须是对象")?;
                if row
                    .keys()
                    .any(|key| !["id", "subject", "status"].contains(&key.as_str()))
                {
                    return Err("任务项包含未知参数".into());
                }
                let mut item: Item =
                    serde_json::from_value(entry.clone()).map_err(|_| "任务项字段无效")?;
                item.subject = item.subject.trim().into();
                item.id = item.id.trim().into();
                item.status = item.status.trim().to_ascii_lowercase();
                if item.status.is_empty() {
                    item.status = pending();
                }
                items.push(item);
            }
            normalize(&mut items)?;
            let expected_revision = object
                .get("expected_revision")
                .map(|v| v.as_u64().ok_or("清单版本必须为非负整数"))
                .transpose()?;
            Ok(Operation::Write {
                items,
                expected_revision,
            })
        }
        _ => Err("未知任务清单工具".into()),
    }
}
fn number(value: Option<&Value>, default: usize, maximum: usize) -> Result<usize, String> {
    value
        .map(|value| {
            value
                .as_u64()
                .filter(|value| *value <= maximum as u64)
                .map(|value| value as usize)
                .ok_or_else(|| "任务清单分页参数超出范围".into())
        })
        .unwrap_or(Ok(default))
}

pub fn normalize(items: &mut [Item]) -> Result<(), String> {
    for (index, item) in items.iter_mut().enumerate() {
        if item.id.is_empty() {
            item.id = format!("t{}", index + 1);
        }
    }
    validate_items(items)
}
pub fn validate(board: &Board) -> Result<(), String> {
    if board.version != 1 {
        return Err("任务清单版本不受支持，原文保留".into());
    }
    validate_items(&board.items)
}
fn validate_items(items: &[Item]) -> Result<(), String> {
    if items.len() > MAX_ITEMS {
        return Err("任务清单超过1024项预算".into());
    }
    let mut ids = HashSet::new();
    let mut bytes = 0usize;
    for item in items {
        if item.id.is_empty()
            || item.id.len() > 128
            || item.id.contains('\0')
            || !ids.insert(&item.id)
            || item.subject.trim().is_empty()
            || item.subject.len() > 8192
            || item.subject.contains('\0')
            || !["pending", "in_progress", "completed"].contains(&item.status.as_str())
        {
            return Err("任务项ID、正文或状态无效，旧清单保留".into());
        }
        bytes = bytes
            .checked_add(
                serde_json::to_vec(item)
                    .map_err(|_| "任务清单编码失败")?
                    .len(),
            )
            .ok_or("任务清单长度溢出")?;
        if bytes > MAX_BYTES {
            return Err("任务清单超过2 MiB预算，旧清单保留".into());
        }
    }
    Ok(())
}

pub fn apply(board: &Board, operation: Operation) -> Result<Board, String> {
    validate(board)?;
    let mut next = board.clone();
    match operation {
        Operation::Read { .. } => return Ok(next),
        Operation::Write {
            items,
            expected_revision,
        } => {
            if expected_revision.is_some_and(|expected| expected != board.revision) {
                return Err("任务清单已经改变，请重新读取后再写入".into());
            }
            next.items = items;
        }
        Operation::SetStatus {
            id,
            status,
            expected_revision,
        } => {
            if expected_revision != board.revision {
                return Err("任务清单已经改变，请重新读取后再修改".into());
            }
            let item = next
                .items
                .iter_mut()
                .find(|item| item.id == id)
                .ok_or("任务项不存在")?;
            item.status = status;
        }
        Operation::Delete {
            id,
            expected_revision,
        } => {
            if expected_revision != board.revision {
                return Err("任务清单已经改变，请重新读取后再删除".into());
            }
            if !next.items.iter().any(|item| item.id == id) {
                return Err("任务项已不存在".into());
            }
            next.items.retain(|item| item.id != id);
        }
    }
    normalize(&mut next.items)?;
    next.revision = board
        .revision
        .checked_add(1)
        .ok_or("任务清单版本溢出，未写入")?;
    validate(&next)?;
    Ok(next)
}

pub fn result(board: &Board, offset: usize, limit: usize, committed: bool) -> Value {
    let from = offset.min(board.items.len());
    let mut next = from;
    let mut items = Vec::new();
    let mut bytes = 0usize;
    for item in board.items.iter().skip(from).take(limit) {
        let visible = json!({"id":item.id,"subject":item.subject,"status":item.status});
        let size = serde_json::to_vec(&visible).map_or(RESULT_BYTES + 1, |bytes| bytes.len());
        if bytes.saturating_add(size) > RESULT_BYTES && !items.is_empty() {
            break;
        }
        bytes = bytes.saturating_add(size);
        items.push(visible);
        next += 1;
    }
    json!({"ok":true,"committed":committed,"revision":board.revision,"total":board.items.len(),
        "offset":from,"items":items,"next_offset":(next < board.items.len()).then_some(next)})
}
