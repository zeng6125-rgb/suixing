//! Compaction and checkpoint plans over message metadata.
use std::collections::HashMap;
use std::ops::Range;

const TYPED_MAX_KEY_BYTES: usize = 128 * 1024 * 1024;
const MAX_ROWS: usize = 131_072;
const MAX_CHECKPOINTS: usize = 65_536;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PlanningError {
    Budget,
    Cancelled,
    Invalid,
}

impl std::fmt::Display for PlanningError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Budget => "压缩规划超过预算",
            Self::Cancelled => "压缩规划已取消",
            Self::Invalid => "压缩规划参数无效",
        })
    }
}

#[derive(Clone, Copy, Debug)]
pub struct CompactionRow<'a> {
    pub id: &'a str,
    pub is_tool: bool,
}

#[derive(Clone, Copy, Debug)]
pub struct CheckpointRef<'a> {
    pub from: &'a str,
    pub to: &'a str,
    pub usable: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CheckpointPosition {
    pub index: usize,
    pub from: usize,
    pub to: usize,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CompactionCut {
    pub checkpoint: Option<CheckpointPosition>,
    pub covered: Range<usize>,
    pub fresh: Range<usize>,
    pub tail: Range<usize>,
}

pub fn checkpoint_position(
    rows: &[CompactionRow<'_>],
    checkpoints: &[CheckpointRef<'_>],
    cancelled: impl Fn() -> bool,
) -> Result<Option<CheckpointPosition>, PlanningError> {
    if rows.len() > MAX_ROWS || checkpoints.len() > MAX_CHECKPOINTS {
        return Err(PlanningError::Budget);
    }
    let mut bytes = 0usize;
    let mut positions = HashMap::new();
    positions
        .try_reserve(rows.len())
        .map_err(|_| PlanningError::Budget)?;
    for (index, row) in rows.iter().enumerate() {
        if cancelled() {
            return Err(PlanningError::Cancelled);
        }
        bytes = bytes
            .checked_add(row.id.len())
            .ok_or(PlanningError::Budget)?;
        if bytes > TYPED_MAX_KEY_BYTES {
            return Err(PlanningError::Budget);
        }
        // The existing ABI resolves duplicate IDs to their last source occurrence.
        positions.insert(row.id, index);
    }
    let mut selected = None;
    for (index, checkpoint) in checkpoints.iter().enumerate() {
        if cancelled() {
            return Err(PlanningError::Cancelled);
        }
        bytes = bytes
            .checked_add(checkpoint.from.len())
            .and_then(|n| n.checked_add(checkpoint.to.len()))
            .ok_or(PlanningError::Budget)?;
        if bytes > TYPED_MAX_KEY_BYTES {
            return Err(PlanningError::Budget);
        }
        if checkpoint.usable {
            if let Some((&from, &to)) = positions
                .get(checkpoint.from)
                .zip(positions.get(checkpoint.to))
            {
                if from <= to {
                    selected = Some(CheckpointPosition { index, from, to });
                }
            }
        }
    }
    if cancelled() {
        return Err(PlanningError::Cancelled);
    }
    Ok(selected)
}

pub fn plan_compaction(
    rows: &[CompactionRow<'_>],
    checkpoints: &[CheckpointRef<'_>],
    keep_recent: usize,
    min_messages: usize,
    cancelled: impl Fn() -> bool,
) -> Result<Option<CompactionCut>, PlanningError> {
    if keep_recent == 0 {
        return Err(PlanningError::Invalid);
    }
    let checkpoint = checkpoint_position(rows, checkpoints, &cancelled)?;
    if rows.len() <= min_messages || keep_recent >= rows.len() {
        return Ok(None);
    }
    let mut tools = Vec::new();
    tools
        .try_reserve(rows.len())
        .map_err(|_| PlanningError::Budget)?;
    for row in rows {
        if cancelled() {
            return Err(PlanningError::Cancelled);
        }
        tools.push(row.is_tool);
    }
    let Some(cut) = compaction_cut(
        &tools,
        checkpoint.map(|cp| cp.to),
        keep_recent,
        min_messages,
    ) else {
        return Ok(None);
    };
    let from = checkpoint.map_or(0, |cp| cp.from);
    let fresh = checkpoint.map_or(0, |cp| cp.to + 1);
    if from >= cut || fresh >= cut {
        return Ok(None);
    }
    if cancelled() {
        return Err(PlanningError::Cancelled);
    }
    Ok(Some(CompactionCut {
        checkpoint,
        covered: from..cut,
        fresh: fresh..cut,
        tail: cut..rows.len(),
    }))
}

fn compaction_cut(tools: &[bool], to: Option<usize>, keep: usize, min: usize) -> Option<usize> {
    if tools.len() <= min || keep >= tools.len() {
        return None;
    }
    let mut candidate = tools.len() - keep;
    while candidate > 0 && tools[candidate] {
        candidate -= 1;
    }
    (candidate > 0 && to.is_none_or(|to| to < candidate - 1)).then_some(candidate)
}
