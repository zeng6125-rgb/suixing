//! Transcript branches, tool pairing and profile change plans. Bodies and platform IO stay host.
use std::collections::{BTreeMap, HashMap, HashSet};

const NONE: u32 = u32::MAX;
// The planner carries metadata for the complete segmented transcript.
const TYPED_MAX_ROWS: usize = 131_072;
const TYPED_MAX_KEYS: usize = 4 * TYPED_MAX_ROWS + 2;
const TYPED_MAX_BYTES: usize = 128 * 1024 * 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PlanningError {
    Budget,
    Cancelled,
    Invalid,
}

impl std::fmt::Display for PlanningError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Budget => "转录规划超过预算",
            Self::Cancelled => "转录规划已取消",
            Self::Invalid => "转录规划参数无效",
        })
    }
}

#[derive(Clone, Copy, Debug)]
pub struct TranscriptRow<'a> {
    pub id: &'a str,
    pub is_user: bool,
    pub steer: bool,
    pub run_id: Option<&'a str>,
}

#[derive(Debug)]
pub struct CandidateGroup<'a> {
    pub turn_id: &'a str,
    pub runs: Vec<&'a str>,
}

#[derive(Debug)]
pub struct TranscriptProjection<'a> {
    pub turn_of: Vec<Option<&'a str>>,
    pub candidates: Vec<CandidateGroup<'a>>,
    pub chosen: Vec<(&'a str, &'a str)>,
    pub visible_indices: Vec<usize>,
}

struct BorrowedKeys<'a> {
    values: Vec<&'a str>,
    lookup: HashMap<&'a str, u32>,
    bytes: usize,
}

impl<'a> BorrowedKeys<'a> {
    fn new() -> Self {
        Self {
            values: vec![""],
            lookup: HashMap::from([("", 0)]),
            bytes: 0,
        }
    }
    fn key(&mut self, value: &'a str) -> Result<u32, PlanningError> {
        if let Some(key) = self.lookup.get(value) {
            return Ok(*key);
        }
        self.bytes = self
            .bytes
            .checked_add(value.len())
            .ok_or(PlanningError::Budget)?;
        if self.values.len() >= TYPED_MAX_KEYS || self.bytes > TYPED_MAX_BYTES {
            return Err(PlanningError::Budget);
        }
        self.values
            .try_reserve(1)
            .map_err(|_| PlanningError::Budget)?;
        self.lookup
            .try_reserve(1)
            .map_err(|_| PlanningError::Budget)?;
        let key = self.values.len() as u32;
        self.values.push(value);
        self.lookup.insert(value, key);
        Ok(key)
    }
    fn rows(
        &mut self,
        rows: &[TranscriptRow<'a>],
        cancelled: &impl Fn() -> bool,
    ) -> Result<Vec<Message>, PlanningError> {
        if rows.len() > TYPED_MAX_ROWS {
            return Err(PlanningError::Budget);
        }
        let mut result = Vec::new();
        result
            .try_reserve(rows.len())
            .map_err(|_| PlanningError::Budget)?;
        for row in rows {
            if cancelled() {
                return Err(PlanningError::Cancelled);
            }
            result.push(Message {
                id: self.key(row.id)?,
                flags: u32::from(row.is_user) | if row.steer { 4 } else { 0 },
                run: self.key(row.run_id.unwrap_or(""))?,
            });
        }
        Ok(result)
    }
}

/// Calls the existing token-based projection directly, without serializing an ABI request.
pub fn project_transcript<'a>(
    sources: &[TranscriptRow<'a>],
    active_runs: &'a BTreeMap<String, String>,
    pending_run: Option<&'a str>,
    cancelled: impl Fn() -> bool,
) -> Result<TranscriptProjection<'a>, PlanningError> {
    if active_runs.len() > TYPED_MAX_ROWS || cancelled() {
        return Err(if cancelled() {
            PlanningError::Cancelled
        } else {
            PlanningError::Budget
        });
    }
    let mut keys = BorrowedKeys::new();
    let rows = keys.rows(sources, &cancelled)?;
    let (_, turn_of) = turns(&rows);
    if cancelled() {
        return Err(PlanningError::Cancelled);
    }
    let runs = groups(
        rows.iter()
            .map(|r| (r.user(), turn_of.get(&r.id).copied().unwrap_or(0), r.run)),
    );
    let mut active = HashMap::new();
    for (turn, run) in active_runs {
        if cancelled() {
            return Err(PlanningError::Cancelled);
        }
        active.insert(keys.key(turn)?, keys.key(run)?);
    }
    let pending = pending_run
        .map(|value| keys.key(value))
        .transpose()?
        .unwrap_or(NONE);
    let mut chosen = Vec::new();
    let mut selected = HashMap::new();
    for (turn, list) in &runs {
        if cancelled() {
            return Err(PlanningError::Cancelled);
        }
        let run = choose(list, active.get(turn).copied().unwrap_or(NONE), pending)
            .ok_or(PlanningError::Invalid)?;
        chosen.push((keys.values[*turn as usize], keys.values[run as usize]));
        selected.insert(*turn, run);
    }
    let mut visible_indices = Vec::new();
    let mut mapped = Vec::new();
    for (index, row) in rows.iter().enumerate() {
        if cancelled() {
            return Err(PlanningError::Cancelled);
        }
        let turn = turn_of.get(&row.id).copied().unwrap_or(0);
        mapped.push((turn != 0).then(|| keys.values[turn as usize]));
        if row.user() || turn == 0 || selected.get(&turn).copied() == Some(row.run) {
            visible_indices.push(index);
        }
    }
    let candidates = runs
        .into_iter()
        .map(|(turn, runs)| CandidateGroup {
            turn_id: keys.values[turn as usize],
            runs: runs
                .into_iter()
                .map(|run| keys.values[run as usize])
                .collect(),
        })
        .collect();
    if cancelled() {
        return Err(PlanningError::Cancelled);
    }
    Ok(TranscriptProjection {
        turn_of: mapped,
        candidates,
        chosen,
        visible_indices,
    })
}

pub fn without_last_answer(
    sources: &[TranscriptRow<'_>],
    cancelled: impl Fn() -> bool,
) -> Result<Vec<usize>, PlanningError> {
    let mut keys = BorrowedKeys::new();
    let rows = keys.rows(sources, &cancelled)?;
    let (_, turn_of) = turns(&rows);
    let last = rows
        .iter()
        .rev()
        .find(|row| row.user() && row.flags & 4 == 0)
        .map(|row| row.id);
    let mut result = Vec::new();
    for (index, row) in rows.iter().enumerate() {
        if cancelled() {
            return Err(PlanningError::Cancelled);
        }
        if last.is_none() || row.user() || turn_of.get(&row.id).copied() != last {
            result.push(index);
        }
    }
    if cancelled() {
        return Err(PlanningError::Cancelled);
    }
    Ok(result)
}

#[derive(Clone, Copy)]
struct Message {
    id: u32,
    flags: u32,
    run: u32,
}
impl Message {
    fn user(self) -> bool {
        self.flags & 3 == 1
    }
}
// LinkedHashMap assignment preserves the first insertion slot, including duplicate message IDs.
fn turns(rows: &[Message]) -> (Vec<(u32, u32)>, HashMap<u32, u32>) {
    let mut values = Vec::new();
    let mut positions = HashMap::new();
    let mut current = 0;
    for row in rows {
        if row.user() && row.flags & 4 == 0 {
            current = row.id;
        }
        match positions.get(&row.id).copied() {
            Some(index) => values[index] = (row.id, current),
            None => {
                positions.insert(row.id, values.len());
                values.push((row.id, current));
            }
        }
    }
    let lookup = values.iter().copied().collect();
    (values, lookup)
}
fn groups(rows: impl Iterator<Item = (bool, u32, u32)>) -> Vec<(u32, Vec<u32>)> {
    let mut out = Vec::<(u32, Vec<u32>)>::new();
    let mut slots = HashMap::new();
    let mut seen = HashSet::new();
    for (user, turn, run) in rows {
        if user || turn == 0 || !seen.insert((turn, run)) {
            continue;
        }
        let slot = match slots.get(&turn) {
            Some(&slot) => slot,
            None => {
                let slot = out.len();
                slots.insert(turn, slot);
                out.push((turn, Vec::new()));
                slot
            }
        };
        out[slot].1.push(run);
    }
    out
}
fn choose(list: &[u32], wanted: u32, pending: u32) -> Option<u32> {
    if wanted != NONE && (list.contains(&wanted) || (pending != NONE && wanted == pending)) {
        return Some(wanted);
    }
    list.iter()
        .rev()
        .find(|&&run| run != 0)
        .or_else(|| list.last())
        .copied()
}
