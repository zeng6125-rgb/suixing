//! Pure request, compaction and rewrite plans. The actor owns persistence and effects.
use crate::{
    chat::{ContextCheckpoint, ConversationMetadata},
    state::Message,
};
use std::collections::{BTreeMap, HashMap, HashSet};
use suixing_search::{data_plan, rust_protocol, tool_plan};

pub const KEEP_RECENT_MESSAGES: usize = 6;
pub const MIN_MESSAGES_TO_COMPACT: usize = 8;
pub const DEFAULT_CONTEXT_WINDOW: u64 = 256_000;
pub const DEFAULT_COMPRESS_PERCENT: u8 = 80;
pub const COMPRESS_PROMPT: &str = "Compress the following agent execution history into a concise summary. Keep: conclusions already reached, file paths and commands involved, decisions made and why, and anything still unfinished. Drop: pleasantries, repeated attempts, and intermediate steps that were later overturned. Output the summary body only — no preamble, no headings.";
const MAX_MESSAGES: usize = 131_072;
const MAX_CHECKPOINTS: usize = 4_096;
const MAX_WIRE_BYTES: usize = 32 * 1024 * 1024;
const MAX_SUMMARY_SOURCE_BYTES: usize = 4 * 1024 * 1024;
const MAX_SUMMARY_UNITS: usize = 32_000;
const TOOL_OUTPUT_UNITS: usize = 2_000;
const IMAGE_TOKENS: u64 = 1_000;

/// Borrow the owning snapshot; neither secrets nor an alternate provider catalog is needed.
#[derive(Clone, Copy)]
pub struct ContextInput<'a> {
    pub history: &'a [Message],
    pub active_runs: &'a BTreeMap<String, String>,
    pub checkpoints: &'a [ContextCheckpoint],
    pub system_prefix: &'a [rust_protocol::Message],
    pub pending_run: Option<&'a str>,
    pub exclude_message_id: Option<&'a str>,
    pub image_input: bool,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct PromptEstimate {
    pub tokens: u64,
    pub text_units: usize,
    pub image_messages: usize,
    pub image_count: usize,
}

/// Source indices refer to the complete owning transcript; ranges refer to wire entries.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WireSpan {
    pub source_index: usize,
    pub from: usize,
    pub to: usize,
}

pub struct BuiltContext {
    pub wire: Vec<rust_protocol::Message>,
    pub source_indices: Vec<usize>,
    pub spans: Vec<WireSpan>,
    pub checkpoint_index: Option<usize>,
    pub estimate: PromptEstimate,
}

pub enum ContextAssessment {
    Ready {
        estimate: PromptEstimate,
        wire_bytes: usize,
    },
    NeedsCompaction {
        estimate: PromptEstimate,
        wire_bytes: usize,
    },
    Uncompressible {
        estimate: PromptEstimate,
        reason: String,
    },
}

/// Assess borrowed source payloads before constructing or cloning any wire.
/// A pressure result must enter compaction before `build_context` is attempted.
pub fn assess_context(
    input: ContextInput<'_>,
    window: u64,
    cancelled: impl Fn() -> bool,
) -> Result<ContextAssessment, String> {
    let prepared = prepared_history(input, &cancelled)?;
    let estimate = measure_input(input, &prepared, &cancelled)?;
    let mut bytes = input.system_prefix.iter().map(wire_bytes).sum::<usize>();
    if let Some(checkpoint) = prepared.selected_checkpoint {
        bytes = bytes
            .saturating_add("system[上下文摘要] ".len())
            .saturating_add(input.checkpoints[checkpoint.index].summary.len());
    }
    for index in replay_indices(input, &prepared) {
        check(&cancelled)?;
        bytes = bytes.saturating_add(message_wire_bytes(&input.history[index], input.image_input));
    }
    if bytes <= MAX_WIRE_BYTES && (window == 0 || estimate.tokens < window) {
        return Ok(ContextAssessment::Ready {
            estimate,
            wire_bytes: bytes,
        });
    }
    // The latest ordinary question and original image payload remain mandatory
    // even if compaction folds every older complete atom into a checkpoint.
    let mut minimum_bytes = input.system_prefix.iter().map(wire_bytes).sum::<usize>();
    let mut minimum_units = 0usize;
    for prefix in input.system_prefix {
        add_units(&mut minimum_units, &prefix.text, &cancelled)?;
    }
    if let Some(&question) =
        prepared.indices.iter().rev().find(|&&index| {
            input.history[index].role == "user" && !input.history[index].metadata.steer
        })
    {
        minimum_bytes = minimum_bytes.saturating_add(message_wire_bytes(
            &input.history[question],
            input.image_input,
        ));
        add_units(
            &mut minimum_units,
            &input.history[question].text,
            &cancelled,
        )?;
        let image_tokens =
            if input.image_input && !input.history[question].metadata.all_images().is_empty() {
                IMAGE_TOKENS
            } else {
                0
            };
        if minimum_bytes > MAX_WIRE_BYTES
            || (window > 0 && minimum_units as u64 / 3 + image_tokens >= window)
        {
            return Ok(ContextAssessment::Uncompressible { estimate,
                reason: "系统前缀和最新用户问题（含启用的图片）已超出模型请求预算；原文保留，请缩小该问题或调整模型窗口".into() });
        }
    }
    Ok(ContextAssessment::NeedsCompaction {
        estimate,
        wire_bytes: bytes,
    })
}

pub struct CompactionPlan {
    pub transcript: String,
    pub covered_ids: Vec<String>,
    pub from_message_id: String,
    pub to_message_id: String,
    pub anchor_id: String,
    pub source_units: usize,
    pub prompt_estimate: PromptEstimate,
}

struct SummaryAtom {
    range: std::ops::Range<usize>,
    bytes: usize,
    units: usize,
}

pub struct ChunkedCompactionPlan<'a> {
    input: ContextInput<'a>,
    indices: Vec<usize>,
    atoms: Vec<SummaryAtom>,
    atom: usize,
    pieces: Vec<&'a str>,
    piece: usize,
    offset: usize,
    covered_from: usize,
    fresh_from: usize,
    covered_to: usize,
    source_units: usize,
    pub initial_summary: &'a str,
    pub prompt_estimate: PromptEstimate,
    pub source_fingerprint: String,
}
pub struct SummaryChunk {
    pub transcript: String,
    pub source_units: usize,
    /// False means source coverage ends within one message/tool batch. The
    /// resulting carry is staged only; it cannot become a reusable checkpoint.
    pub checkpoint_boundary: bool,
    pub completed_atoms: usize,
}

fn summary_pieces(message: &Message) -> Vec<&str> {
    let mut pieces = vec![message.role.as_str(), ": "];
    if message.metadata.steer {
        pieces.push("[任务进行中的用户补充] ");
    }
    pieces.push(if message.role == "tool" {
        tool_output(message)
    } else {
        &message.text
    });
    for call in &message.tool_calls {
        pieces.extend([
            "\n  [调用工具 ",
            call.name.as_str(),
            " ",
            call.arguments.as_str(),
            "]",
        ]);
    }
    if !message.metadata.all_images().is_empty() {
        pieces.push("\n  （含原始图片，摘要请求未发送图片；原文永久保留）");
    }
    pieces.push("\n");
    pieces
}

/// Source fingerprint matches the actor's length-prefixed canonical Message JSON,
/// using two streaming passes rather than allocating a 20 MiB encoded message.
pub fn compaction_source_fingerprint(
    history: &[Message],
    ids: &[String],
    cancelled: impl Fn() -> bool,
) -> Result<String, String> {
    use sha2::{Digest, Sha256};
    use std::io::Write;
    struct Count<'a, F> {
        bytes: u64,
        cancelled: &'a F,
    }
    impl<F: Fn() -> bool> Write for Count<'_, F> {
        fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
            if (self.cancelled)() {
                return Err(std::io::Error::other("cancelled"));
            }
            self.bytes += bytes.len() as u64;
            Ok(bytes.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
    struct Hash<'a, F> {
        digest: Sha256,
        cancelled: &'a F,
    }
    impl<F: Fn() -> bool> Write for Hash<'_, F> {
        fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
            for chunk in bytes.chunks(16 * 1024) {
                if (self.cancelled)() {
                    return Err(std::io::Error::other("cancelled"));
                }
                self.digest.update(chunk);
            }
            Ok(bytes.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
    let indexed: HashMap<_, _> = history
        .iter()
        .map(|message| (message.id.as_str(), message))
        .collect();
    let mut hash = Hash {
        digest: Sha256::new(),
        cancelled: &cancelled,
    };
    for id in ids {
        check(&cancelled)?;
        let message = indexed.get(id.as_str()).ok_or("摘要原消息已变化")?;
        let mut count = Count {
            bytes: 0,
            cancelled: &cancelled,
        };
        serde_json::to_writer(&mut count, message).map_err(|_| "摘要原消息编码失败")?;
        hash.digest.update(count.bytes.to_le_bytes());
        serde_json::to_writer(&mut hash, message).map_err(|_| "摘要原消息编码失败")?;
    }
    check(&cancelled)?;
    Ok(format!("{:x}", hash.digest.finalize()))
}

pub fn plan_chunked_compaction<'a>(
    input: ContextInput<'a>,
    keep_recent: usize,
    min_messages: usize,
    force: bool,
    cancelled: impl Fn() -> bool,
) -> Result<Option<ChunkedCompactionPlan<'a>>, String> {
    let prepared = prepared_history(input, &cancelled)?;
    let checkpoint = prepared.selected_checkpoint;
    let covered_from = checkpoint.map_or(0, |cp| cp.from);
    let fresh_from = checkpoint.map_or(0, |cp| cp.to + 1);
    let boundaries = batch_boundaries(input.history, &prepared.indices, &cancelled)?;
    let normal = tool_plan::plan_compaction(
        &prepared.rows,
        &prepared.checkpoints,
        keep_recent.max(1),
        min_messages,
        &cancelled,
    )
    .map_err(|error| error.to_string())?;
    // Pressure may fold the complete recent tool atom too. replay_indices still
    // restores the latest ordinary question with its original enabled images.
    let mut covered_to = if force {
        prepared.indices.len()
    } else {
        let Some(cut) = normal else {
            return Ok(None);
        };
        cut.covered.end
    };
    while covered_to > fresh_from && !boundaries[covered_to] {
        covered_to -= 1;
    }
    let initial_summary = checkpoint.map_or("", |cp| input.checkpoints[cp.index].summary.as_str());
    let carry_only = force && covered_to == fresh_from && !initial_summary.is_empty();
    if covered_to <= fresh_from && !carry_only {
        return Ok(None);
    }
    if force
        && prepared.indices[fresh_from..covered_to].len() == 1
        && input.history[prepared.indices[fresh_from]].role == "user"
        && !input.history[prepared.indices[fresh_from]].metadata.steer
    {
        return Err("最新用户问题本身超过预算，压缩无法缩小必须保留的该问题".into());
    }
    let mut source_units = 0usize;
    let mut atoms = Vec::new();
    let mut from = fresh_from;
    if carry_only {
        let bytes = "[此前接受的检查点摘要]\n\n".len() + initial_summary.len();
        let units = utf16_units("[此前接受的检查点摘要]\n\n", &cancelled)?
            + utf16_units(initial_summary, &cancelled)?;
        atoms.push(SummaryAtom {
            range: from..from,
            bytes,
            units,
        });
        source_units = units;
    }
    while from < covered_to {
        let mut to = from + 1;
        while to < covered_to && !boundaries[to] {
            to += 1;
        }
        let mut bytes = 0usize;
        let mut units = 0usize;
        for &index in &prepared.indices[from..to] {
            for piece in summary_pieces(&input.history[index]) {
                check(&cancelled)?;
                bytes = bytes.checked_add(piece.len()).ok_or("摘要来源大小溢出")?;
                units = units
                    .checked_add(utf16_units(piece, &cancelled)?)
                    .ok_or("摘要来源字符数溢出")?;
            }
        }
        if from == fresh_from && !initial_summary.is_empty() {
            bytes += "[此前接受的检查点摘要]\n\n".len() + initial_summary.len();
            units += utf16_units("[此前接受的检查点摘要]\n\n", &cancelled)?
                + utf16_units(initial_summary, &cancelled)?;
        }
        source_units = source_units
            .checked_add(units)
            .ok_or("摘要来源字符数溢出")?;
        atoms.push(SummaryAtom {
            range: from..to,
            bytes,
            units,
        });
        from = to;
    }
    let ids: Vec<_> = prepared.indices[covered_from..covered_to]
        .iter()
        .map(|&index| input.history[index].id.clone())
        .collect();
    let source_fingerprint = compaction_source_fingerprint(input.history, &ids, &cancelled)?;
    let prompt_estimate = measure_input(input, &prepared, &cancelled)?;
    Ok(Some(ChunkedCompactionPlan {
        input,
        indices: prepared.indices,
        atoms,
        atom: 0,
        pieces: Vec::new(),
        piece: 0,
        offset: 0,
        covered_from,
        fresh_from,
        covered_to,
        source_units,
        initial_summary,
        prompt_estimate,
        source_fingerprint,
    }))
}

impl<'a> ChunkedCompactionPlan<'a> {
    pub fn source_units(&self) -> usize {
        self.source_units
    }
    pub fn covered_count(&self) -> usize {
        self.covered_to - self.covered_from
    }
    pub fn finished(&self) -> bool {
        self.atom >= self.atoms.len()
    }
    pub fn next_chunk(
        &mut self,
        carry: &str,
        max_bytes: usize,
        max_units: usize,
        cancelled: impl Fn() -> bool,
    ) -> Result<Option<SummaryChunk>, String> {
        if self.finished() {
            return Ok(None);
        }
        let max_bytes = max_bytes.min(MAX_SUMMARY_SOURCE_BYTES);
        let mut transcript = String::new();
        if !carry.is_empty() {
            transcript.push_str("[此前已经接受的摘要；与下面来源合并]\n");
            transcript.push_str(carry);
            transcript.push('\n');
        }
        transcript.push_str("[原始来源的下一部分；续段仍属于同一消息/工具批次]\n");
        let mut units = utf16_units(&transcript, &cancelled)?;
        if transcript.len() >= max_bytes || units >= max_units {
            return Err("摘要carry和指令超过该模型的分块预算".into());
        }
        let initial_atom = self.atom;
        let mut progressed = false;
        while self.atom < self.atoms.len() {
            check(&cancelled)?;
            if self.pieces.is_empty() {
                let atom = &self.atoms[self.atom];
                // Finish at a real boundary after completed atoms, instead of
                // leaking part of the next atom into a checkpoint's summary.
                if self.atom > initial_atom
                    && (atom.bytes > max_bytes - transcript.len() || atom.units > max_units - units)
                {
                    break;
                }
                self.pieces = self.indices[atom.range.clone()]
                    .iter()
                    .flat_map(|&index| summary_pieces(&self.input.history[index]))
                    .collect();
                if atom.range.start == self.fresh_from && !self.initial_summary.is_empty() {
                    self.pieces.splice(
                        0..0,
                        ["[此前接受的检查点摘要]\n", self.initial_summary, "\n"],
                    );
                }
                self.piece = 0;
                self.offset = 0;
            }
            while self.piece < self.pieces.len() {
                let text = self.pieces[self.piece];
                let remaining = &text[self.offset..];
                let mut end = 0usize;
                let mut added_units = 0usize;
                for (ordinal, (index, ch)) in remaining.char_indices().enumerate() {
                    if ordinal & 2047 == 0 {
                        check(&cancelled)?;
                    }
                    if index + ch.len_utf8() > max_bytes - transcript.len()
                        || added_units + ch.len_utf16() > max_units - units
                    {
                        break;
                    }
                    end = index + ch.len_utf8();
                    added_units += ch.len_utf16();
                }
                if end == 0 && !remaining.is_empty() {
                    if !progressed {
                        return Err("模型分块预算不足以容纳一个UTF-8字符".into());
                    }
                    return Ok(Some(SummaryChunk {
                        transcript,
                        source_units: units,
                        checkpoint_boundary: false,
                        completed_atoms: self.atom,
                    }));
                }
                transcript.push_str(&remaining[..end]);
                units += added_units;
                self.offset += end;
                progressed |= end > 0;
                if self.offset == text.len() {
                    self.piece += 1;
                    self.offset = 0;
                } else {
                    return Ok(Some(SummaryChunk {
                        transcript,
                        source_units: units,
                        checkpoint_boundary: false,
                        completed_atoms: self.atom,
                    }));
                }
            }
            self.pieces.clear();
            self.atom += 1;
        }
        Ok(Some(SummaryChunk {
            transcript,
            source_units: units,
            checkpoint_boundary: true,
            completed_atoms: self.atom,
        }))
    }
    pub fn checkpoint_plan(
        &self,
        completed_atoms: usize,
        cancelled: impl Fn() -> bool,
    ) -> Result<CompactionPlan, String> {
        check(&cancelled)?;
        let to = self
            .atoms
            .get(completed_atoms.checked_sub(1).ok_or("压缩没有完整原子")?)
            .ok_or("压缩原子边界无效")?
            .range
            .end;
        let covered_ids: Vec<_> = self.indices[self.covered_from..to]
            .iter()
            .map(|&index| self.input.history[index].id.clone())
            .collect();
        let mut source_units = 0usize;
        for atom in &self.atoms[..completed_atoms] {
            source_units += atom.units;
        }
        Ok(CompactionPlan {
            transcript: String::new(),
            from_message_id: covered_ids.first().cloned().ok_or("压缩来源为空")?,
            to_message_id: covered_ids.last().cloned().ok_or("压缩来源为空")?,
            covered_ids,
            anchor_id: self
                .input
                .history
                .last()
                .map_or(String::new(), |message| message.id.clone()),
            source_units,
            prompt_estimate: self.prompt_estimate,
        })
    }
    pub fn verify_source(&self, cancelled: impl Fn() -> bool) -> Result<(), String> {
        let ids: Vec<_> = self.indices[self.covered_from..self.covered_to]
            .iter()
            .map(|&index| self.input.history[index].id.clone())
            .collect();
        if compaction_source_fingerprint(self.input.history, &ids, cancelled)?
            != self.source_fingerprint
        {
            return Err("摘要来源指纹已变化，暂存分块未发布为检查点".into());
        }
        Ok(())
    }
}

struct PreparedHistory<'a> {
    indices: Vec<usize>,
    rows: Vec<tool_plan::CompactionRow<'a>>,
    checkpoints: Vec<tool_plan::CheckpointRef<'a>>,
    selected_checkpoint: Option<tool_plan::CheckpointPosition>,
}

fn check(cancelled: &impl Fn() -> bool) -> Result<(), String> {
    if cancelled() {
        Err("上下文规划已取消；原始记录保留".into())
    } else {
        Ok(())
    }
}

fn rows(history: &[Message]) -> Vec<data_plan::TranscriptRow<'_>> {
    history
        .iter()
        .map(|message| data_plan::TranscriptRow {
            id: &message.id,
            is_user: message.role == "user",
            steer: message.metadata.steer,
            run_id: message.metadata.run_id.as_deref(),
        })
        .collect()
}

fn prepared_history<'a>(
    input: ContextInput<'a>,
    cancelled: &impl Fn() -> bool,
) -> Result<PreparedHistory<'a>, String> {
    check(cancelled)?;
    if input.history.len() > MAX_MESSAGES
        || input.checkpoints.len() > MAX_CHECKPOINTS
        || input.system_prefix.len() > 128
    {
        return Err("上下文消息或检查点数量超过预算".into());
    }
    let mut prefix_bytes = 0usize;
    for prefix in input.system_prefix {
        check(cancelled)?;
        if prefix.role != "system"
            || !prefix.tool_calls.is_empty()
            || prefix.tool_call_id.is_some()
            || !prefix.images_base64.is_empty()
        {
            return Err("上下文前缀必须是完整的系统消息，未改写前缀角色".into());
        }
        prefix_bytes = prefix_bytes
            .checked_add(wire_bytes(prefix))
            .ok_or("上下文大小溢出")?;
        check_clone_budget(prefix_bytes, 0)?;
    }
    let mut ids = HashSet::new();
    for message in input.history {
        check(cancelled)?;
        if message.id.is_empty() || !ids.insert(message.id.as_str()) {
            return Err("上下文消息身份为空或重复，未选择部分历史".into());
        }
    }
    let metadata = rows(input.history);
    let projection =
        data_plan::project_transcript(&metadata, input.active_runs, input.pending_run, cancelled)
            .map_err(|error| error.to_string())?;
    let indices: Vec<_> = projection
        .visible_indices
        .into_iter()
        .filter(|&index| {
            let message = &input.history[index];
            input.exclude_message_id != Some(message.id.as_str())
                && !crate::agent::retry_runtime::failed(message)
                && !(message.role == "assistant"
                    && !message.complete
                    && message.text.is_empty()
                    && message.tool_calls.is_empty())
        })
        .collect();
    let boundaries = batch_boundaries(input.history, &indices, cancelled)?;
    let positions: HashMap<_, _> = indices
        .iter()
        .enumerate()
        .map(|(index, &source)| (input.history[source].id.as_str(), index))
        .collect();
    let mut checkpoints = Vec::new();
    for checkpoint in input.checkpoints {
        check(cancelled)?;
        let endpoints = positions
            .get(checkpoint.from_message_id.as_str())
            .zip(positions.get(checkpoint.to_message_id.as_str()));
        let usable = checkpoint
            .extra
            .get("invalidated")
            .is_none_or(|value| value == &serde_json::Value::Bool(false))
            && !checkpoint.summary.trim().is_empty()
            && checkpoint.summary.len() <= MAX_SUMMARY_UNITS * 4
            && endpoints
                .is_some_and(|(&from, &to)| from <= to && boundaries[from] && boundaries[to + 1]);
        let usable = usable && utf16_units(&checkpoint.summary, cancelled)? <= MAX_SUMMARY_UNITS;
        checkpoints.push(tool_plan::CheckpointRef {
            from: &checkpoint.from_message_id,
            to: &checkpoint.to_message_id,
            usable,
        });
    }
    check(cancelled)?;
    let rows: Vec<_> = indices
        .iter()
        .map(|&index| tool_plan::CompactionRow {
            id: input.history[index].id.as_str(),
            is_tool: input.history[index].role == "tool",
        })
        .collect();
    let selected_checkpoint = tool_plan::checkpoint_position(&rows, &checkpoints, cancelled)
        .map_err(|error| error.to_string())?;
    Ok(PreparedHistory {
        indices,
        rows,
        checkpoints,
        selected_checkpoint,
    })
}

/// A checkpoint and a cut may only occur outside a complete contiguous tool batch.
fn batch_boundaries(
    history: &[Message],
    indices: &[usize],
    cancelled: &impl Fn() -> bool,
) -> Result<Vec<bool>, String> {
    let mut boundaries = vec![true];
    let mut pending = HashSet::new();
    for &index in indices {
        check(cancelled)?;
        let message = &history[index];
        if !matches!(
            message.role.as_str(),
            "system" | "user" | "assistant" | "tool"
        ) {
            return Err("历史含当前模型协议不支持的角色，记录保留但未发送".into());
        }
        if message.role != "tool" && !pending.is_empty() {
            return Err("历史中有未确认的工具结果；请先恢复执行记录，未构造部分工具批次".into());
        }
        if !message.tool_calls.is_empty() {
            if message.role != "assistant" || message.tool_calls.len() > 16 {
                return Err("历史工具声明不属于助手或超过批次上限".into());
            }
            for call in &message.tool_calls {
                if call.id.is_empty() || !pending.insert(call.id.as_str()) {
                    return Err("历史同一工具批次含重复或空调用身份".into());
                }
            }
        }
        if message.role == "tool" {
            if !message.complete {
                return Err("历史工具结果尚未确认完成，未发送部分工具批次".into());
            }
            let id = message
                .tool_call_id
                .as_deref()
                .ok_or("历史工具结果缺少调用身份")?;
            if !pending.remove(id) {
                return Err("历史工具结果没有对应的声明；未发送孤立工具记录".into());
            }
        } else if message.tool_call_id.is_some() {
            return Err("非工具消息带有工具结果身份".into());
        }
        boundaries.push(pending.is_empty());
    }
    if !pending.is_empty() {
        return Err("历史工具批次尚无全部确认结果，未构造模型上下文".into());
    }
    Ok(boundaries)
}

fn replay_indices(input: ContextInput<'_>, prepared: &PreparedHistory<'_>) -> Vec<usize> {
    let start = prepared
        .selected_checkpoint
        .map_or(0, |checkpoint| checkpoint.to + 1);
    let mut indices = prepared.indices[start..].to_vec();
    // Match the original ensureQuestionKept: a steer is not the question, and the
    // restored question is appended after the retained suffix without splitting a tool batch.
    if !indices
        .iter()
        .any(|&index| input.history[index].role == "user" && !input.history[index].metadata.steer)
    {
        if let Some(&question) = prepared.indices.iter().rev().find(|&&index| {
            input.history[index].role == "user" && !input.history[index].metadata.steer
        }) {
            indices.push(question);
        }
    }
    indices
}

pub fn build_context(
    input: ContextInput<'_>,
    cancelled: impl Fn() -> bool,
) -> Result<BuiltContext, String> {
    let prepared = prepared_history(input, &cancelled)?;
    let source_indices = replay_indices(input, &prepared);
    let mut wire = Vec::new();
    let mut budget = 0usize;
    for message in input.system_prefix {
        check(&cancelled)?;
        check_clone_budget(wire_bytes(message), budget)?;
        wire_push(&mut wire, message.clone(), &mut budget)?;
    }
    if let Some(checkpoint) = prepared.selected_checkpoint {
        wire_push(
            &mut wire,
            rust_protocol::Message::system(format!(
                "[上下文摘要] {}",
                input.checkpoints[checkpoint.index].summary
            )),
            &mut budget,
        )?;
    }
    let mut spans = Vec::new();
    for &index in &source_indices {
        check(&cancelled)?;
        let from = wire.len();
        append_message(
            &mut wire,
            &input.history[index],
            input.image_input,
            &mut budget,
        )?;
        spans.push(WireSpan {
            source_index: index,
            from,
            to: wire.len(),
        });
    }
    let estimate = measure_wire(&wire, &cancelled)?;
    check(&cancelled)?;
    Ok(BuiltContext {
        wire,
        source_indices,
        spans,
        checkpoint_index: prepared
            .selected_checkpoint
            .map(|checkpoint| checkpoint.index),
        estimate,
    })
}

fn wire_bytes(message: &rust_protocol::Message) -> usize {
    let mut bytes = message
        .role
        .len()
        .saturating_add(message.text.len())
        .saturating_add(message.tool_call_id.as_ref().map_or(0, String::len));
    for call in &message.tool_calls {
        bytes = bytes
            .saturating_add(call.id.len())
            .saturating_add(call.name.len())
            .saturating_add(call.arguments.len());
    }
    for image in &message.images_base64 {
        bytes = bytes.saturating_add(image.len());
    }
    bytes
}

fn check_clone_budget(bytes: usize, budget: usize) -> Result<(), String> {
    if bytes > MAX_WIRE_BYTES.saturating_sub(budget) {
        Err("模型上下文超过请求预算，原始记录保留".into())
    } else {
        Ok(())
    }
}

fn wire_push(
    wire: &mut Vec<rust_protocol::Message>,
    message: rust_protocol::Message,
    budget: &mut usize,
) -> Result<(), String> {
    let bytes = wire_bytes(&message);
    *budget = budget.checked_add(bytes).ok_or("上下文大小溢出")?;
    if *budget > MAX_WIRE_BYTES || wire.len() >= MAX_MESSAGES * 2 + 128 {
        return Err("模型上下文超过请求预算，原始记录保留".into());
    }
    wire.push(message);
    Ok(())
}

fn tool_output(message: &Message) -> &str {
    if message.text.is_empty() {
        message.metadata.tool_output.as_deref().unwrap_or("")
    } else {
        &message.text
    }
}

fn user_text(message: &Message, image_input: bool) -> String {
    let note = if !image_input && !message.metadata.all_images().is_empty() {
        "\n（图片已省略：当前未开启「模型支持图片输入」）"
    } else {
        ""
    };
    if message.metadata.steer {
        format!("（用户在任务进行中说：{}）{note}", message.text)
    } else {
        format!("{}{note}", message.text)
    }
}

fn screenshot_text(message: &Message, image_input: bool) -> String {
    let name = message.metadata.tool_name.as_deref().unwrap_or("");
    if image_input {
        format!("（这是上一步 {name} 返回的截图）")
    } else {
        format!("（上一步 {name} 返回了一张截图，但当前模型未开启图片输入，你看不到图像内容。）")
    }
}

fn append_message(
    wire: &mut Vec<rust_protocol::Message>,
    message: &Message,
    image_input: bool,
    budget: &mut usize,
) -> Result<(), String> {
    check_clone_budget(message_wire_bytes(message, image_input), *budget)?;
    append_message_checked(wire, message, image_input, budget)
}

fn message_wire_bytes(message: &Message, image_input: bool) -> usize {
    // Account for the body and image clones before constructing their owned wire form.
    // Wrappers are included explicitly so a valid message at the budget boundary remains valid.
    let mut clone_bytes = match message.role.as_str() {
        "tool" => "tool"
            .len()
            .saturating_add(tool_output(message).len())
            .saturating_add(message.tool_call_id.as_ref().map_or(0, String::len)),
        _ => message.role.len().saturating_add(message.text.len()),
    };
    for call in &message.tool_calls {
        clone_bytes = clone_bytes
            .saturating_add(call.id.len())
            .saturating_add(call.name.len())
            .saturating_add(call.arguments.len());
    }
    if message.role == "user" {
        if message.metadata.steer {
            clone_bytes = clone_bytes.saturating_add("（用户在任务进行中说：）".len());
        }
        if !image_input && !message.metadata.all_images().is_empty() {
            clone_bytes = clone_bytes
                .saturating_add("\n（图片已省略：当前未开启「模型支持图片输入」）".len());
        }
    }
    if message.role == "tool" && !message.metadata.all_images().is_empty() {
        let wrapper = if image_input {
            "（这是上一步  返回的截图）"
        } else {
            "（上一步  返回了一张截图，但当前模型未开启图片输入，你看不到图像内容。）"
        };
        clone_bytes = clone_bytes
            .saturating_add("user".len())
            .saturating_add(wrapper.len())
            .saturating_add(message.metadata.tool_name.as_ref().map_or(0, String::len));
    }
    if image_input && matches!(message.role.as_str(), "user" | "tool") {
        for image in message.metadata.all_images() {
            clone_bytes = clone_bytes.saturating_add(image.len());
        }
    }
    clone_bytes
}

fn append_message_checked(
    wire: &mut Vec<rust_protocol::Message>,
    message: &Message,
    image_input: bool,
    budget: &mut usize,
) -> Result<(), String> {
    match message.role.as_str() {
        "system" => wire_push(
            wire,
            rust_protocol::Message::system(message.text.clone()),
            budget,
        ),
        "user" => {
            let mut user = rust_protocol::Message::user(user_text(message, image_input));
            if image_input {
                user.images_base64 = message.metadata.all_images().to_vec();
            }
            wire_push(wire, user, budget)
        }
        "assistant" => wire_push(
            wire,
            rust_protocol::Message::assistant(
                message.text.clone(),
                message
                    .tool_calls
                    .iter()
                    .map(|call| rust_protocol::ToolCall {
                        id: call.id.clone(),
                        name: call.name.clone(),
                        arguments: call.arguments.clone(),
                    })
                    .collect(),
            ),
            budget,
        ),
        "tool" => {
            wire_push(
                wire,
                rust_protocol::Message::tool(
                    message.tool_call_id.as_deref().ok_or("工具结果缺少身份")?,
                    tool_output(message),
                ),
                budget,
            )?;
            if !message.metadata.all_images().is_empty() {
                let mut screenshot =
                    rust_protocol::Message::user(screenshot_text(message, image_input));
                if image_input {
                    screenshot.images_base64 = message.metadata.all_images().to_vec();
                }
                wire_push(wire, screenshot, budget)?;
            }
            Ok(())
        }
        _ => Err("历史含当前模型协议不支持的角色，记录保留但未发送".into()),
    }
}

fn utf16_units(text: &str, cancelled: &impl Fn() -> bool) -> Result<usize, String> {
    let mut units = 0usize;
    for (index, ch) in text.chars().enumerate() {
        if index & 2047 == 0 {
            check(cancelled)?;
        }
        units = units
            .checked_add(ch.len_utf16())
            .ok_or("上下文字符数溢出")?;
    }
    check(cancelled)?;
    Ok(units)
}

fn add_units(total: &mut usize, text: &str, cancelled: &impl Fn() -> bool) -> Result<(), String> {
    *total = total
        .checked_add(utf16_units(text, cancelled)?)
        .ok_or("上下文字符数溢出")?;
    Ok(())
}

fn finish_estimate(mut estimate: PromptEstimate) -> Result<PromptEstimate, String> {
    estimate.tokens = (estimate.text_units as u64 / 3)
        .checked_add(
            (estimate.image_messages as u64)
                .checked_mul(IMAGE_TOKENS)
                .ok_or("图片估算溢出")?,
        )
        .ok_or("上下文估算溢出")?;
    Ok(estimate)
}

/// Read the complete selected transcript's already-cached text metrics. Opening
/// or switching a chat does not decode its bodies again just to paint a meter.
pub fn estimate_cached_history(
    headers: &[crate::display::MessageHeader],
    selected: &[usize],
    checkpoints: &[ContextCheckpoint],
    image_input: bool,
    model: &str,
    cancelled: impl Fn() -> bool,
) -> Result<(PromptEstimate, Option<u64>), String> {
    let indices: Vec<_> = selected
        .iter()
        .copied()
        .filter(|&index| {
            let header = &headers[index];
            !header.failed_model_attempt
                && !(header.role == "assistant"
                    && !header.complete
                    && header.text.bytes == 0
                    && header.tool_calls.is_empty())
        })
        .collect();
    let mut positions = HashMap::new();
    let mut pending = HashSet::new();
    let mut boundaries = vec![true];
    for (position, &index) in indices.iter().enumerate() {
        check(&cancelled)?;
        let header = &headers[index];
        positions.insert(header.id.as_str(), position);
        for call in &header.tool_calls {
            pending.insert(call.id.as_str());
        }
        if let Some(call) = header.tool_call_id.as_deref() {
            pending.remove(call);
        }
        boundaries.push(pending.is_empty());
    }
    let rows: Vec<_> = indices
        .iter()
        .map(|&index| tool_plan::CompactionRow {
            id: headers[index].id.as_str(),
            is_tool: headers[index].role == "tool",
        })
        .collect();
    let mut checkpoint_refs = Vec::with_capacity(checkpoints.len());
    for checkpoint in checkpoints {
        check(&cancelled)?;
        let endpoints = positions
            .get(checkpoint.from_message_id.as_str())
            .zip(positions.get(checkpoint.to_message_id.as_str()));
        let usable = checkpoint
            .extra
            .get("invalidated")
            .is_none_or(|value| value == &serde_json::Value::Bool(false))
            && !checkpoint.summary.trim().is_empty()
            && checkpoint.summary.len() <= MAX_SUMMARY_UNITS * 4
            && endpoints
                .is_some_and(|(&from, &to)| from <= to && boundaries[from] && boundaries[to + 1]);
        let usable = usable && utf16_units(&checkpoint.summary, &cancelled)? <= MAX_SUMMARY_UNITS;
        checkpoint_refs.push(tool_plan::CheckpointRef {
            from: &checkpoint.from_message_id,
            to: &checkpoint.to_message_id,
            usable,
        });
    }
    let checkpoint = tool_plan::checkpoint_position(&rows, &checkpoint_refs, &cancelled)
        .map_err(|error| error.to_string())?;
    let mut replay = indices[checkpoint.map_or(0, |checkpoint| checkpoint.to + 1)..].to_vec();
    if !replay
        .iter()
        .any(|&index| headers[index].role == "user" && !headers[index].steer)
        && let Some(&question) = indices
            .iter()
            .rev()
            .find(|&&index| headers[index].role == "user" && !headers[index].steer)
    {
        replay.push(question);
    }
    let mut estimate = PromptEstimate::default();
    if let Some(checkpoint) = checkpoint {
        add_units(&mut estimate.text_units, "[上下文摘要] ", &cancelled)?;
        add_units(
            &mut estimate.text_units,
            &checkpoints[checkpoint.index].summary,
            &cancelled,
        )?;
    }
    for index in replay {
        check(&cancelled)?;
        let header = &headers[index];
        let text_units = if header.role == "tool" && header.text.bytes == 0 {
            header.tool_output.utf16_units
        } else {
            header.text.utf16_units
        };
        estimate.text_units = estimate.text_units.saturating_add(text_units);
        if header.role == "user" && header.steer {
            add_units(
                &mut estimate.text_units,
                "（用户在任务进行中说：）",
                &cancelled,
            )?;
        }
        let images = header.image_count();
        if images > 0 {
            if header.role == "user" && !image_input {
                add_units(
                    &mut estimate.text_units,
                    "\n（图片已省略：当前未开启「模型支持图片输入」）",
                    &cancelled,
                )?;
            } else if header.role == "tool" {
                let wrapper = if image_input {
                    "（这是上一步  返回的截图）"
                } else {
                    "（上一步  返回了一张截图，但当前模型未开启图片输入，你看不到图像内容。）"
                };
                add_units(&mut estimate.text_units, wrapper, &cancelled)?;
                add_units(
                    &mut estimate.text_units,
                    header.tool_name.as_deref().unwrap_or(""),
                    &cancelled,
                )?;
            }
            if image_input && matches!(header.role.as_str(), "user" | "tool") {
                estimate.image_messages += 1;
                estimate.image_count += images;
            }
        }
        for call in &header.tool_calls {
            add_units(&mut estimate.text_units, &call.name, &cancelled)?;
            estimate.text_units = estimate
                .text_units
                .saturating_add(call.arguments.utf16_units);
        }
    }
    let active_checkpoint = checkpoint.map(|position| &checkpoints[position.index]);
    let after_anchor = active_checkpoint.and_then(|checkpoint| {
        indices
            .iter()
            .rposition(|&index| headers[index].id == checkpoint.anchor_id)
    });
    // Rust compression is prepared while the next assistant is an empty
    // placeholder. That existing checkpoint provenance distinguishes it from
    // legacy anchors, which identify an already completed pre-compression row.
    let request_placeholder_anchor = active_checkpoint.is_some_and(|checkpoint| {
        checkpoint
            .extra
            .get("prompt_tokens_source")
            .and_then(serde_json::Value::as_str)
            == Some("context_estimate")
            && after_anchor.is_some_and(|position| headers[indices[position]].role == "assistant")
    });
    let newest_prompt = indices
        .iter()
        .enumerate()
        .rev()
        .find_map(|(position, &index)| {
            let header = &headers[index];
            if after_anchor.is_some_and(|anchor| {
                position < anchor || (position == anchor && !request_placeholder_anchor)
            }) || (after_anchor.is_none()
                && active_checkpoint.is_some_and(|checkpoint| header.timestamp <= checkpoint.at))
                || header.role != "assistant"
                || header
                    .model
                    .as_deref()
                    .is_some_and(|source_model| source_model != model)
            {
                return None;
            }
            header
                .prompt_tokens
                .filter(|tokens| *tokens >= 0)
                .map(|tokens| tokens as u64)
        });
    Ok((finish_estimate(estimate)?, newest_prompt))
}

#[cfg(test)]
mod cached_history_tests {
    use super::*;
    use crate::display::{MessageHeader, ProjectionIndex};

    fn message(id: &str, role: &str, text: &str, prompt: Option<i64>) -> Message {
        Message {
            id: id.into(),
            role: role.into(),
            text: text.into(),
            thinking: String::new(),
            complete: true,
            tool_calls: Vec::new(),
            tool_call_id: None,
            metadata: crate::chat::MessageMetadata {
                model: Some("model".into()),
                prompt_tokens: prompt,
                timestamp: 1,
                ..Default::default()
            },
        }
    }

    fn headers(messages: &[Message]) -> Vec<MessageHeader> {
        messages
            .iter()
            .map(|message| MessageHeader::from_message(message, String::new()))
            .collect()
    }

    #[test]
    fn full_cached_transcript_matches_request_estimate_beyond_visible_window() {
        let mut messages = Vec::new();
        for index in 0..300 {
            messages.push(message(&format!("q{index}"), "user", "你好🙂", None));
            messages.push(message(&format!("a{index}"), "assistant", "ok", None));
        }
        let headers = headers(&messages);
        let choices = BTreeMap::new();
        let projection = ProjectionIndex::new(&headers, &choices, None, || false).unwrap();
        assert_eq!(projection.selected_indices().len(), 600);
        let (cached, prompt) = estimate_cached_history(
            &headers,
            projection.selected_indices(),
            &[],
            false,
            "model",
            || false,
        )
        .unwrap();
        let actual = assess_context(
            ContextInput {
                history: &messages,
                active_runs: &choices,
                checkpoints: &[],
                system_prefix: &[],
                pending_run: None,
                exclude_message_id: None,
                image_input: false,
            },
            DEFAULT_CONTEXT_WINDOW,
            || false,
        )
        .unwrap();
        let actual = match actual {
            ContextAssessment::Ready { estimate, .. }
            | ContextAssessment::NeedsCompaction { estimate, .. }
            | ContextAssessment::Uncompressible { estimate, .. } => estimate,
        };
        assert_eq!(cached, actual);
        assert_eq!(cached.text_units, 1800);
        assert_eq!(prompt, None);
    }

    #[test]
    fn compression_report_distinguishes_rust_placeholder_from_legacy_anchor() {
        let messages = vec![
            message("q0", "user", "old", None),
            message("a0", "assistant", "old response", Some(9000)),
            message("q1", "user", "new", None),
            message(
                "pending-at-compression",
                "assistant",
                "new response",
                Some(1200),
            ),
        ];
        let headers = headers(&messages);
        let choices = BTreeMap::new();
        let projection = ProjectionIndex::new(&headers, &choices, None, || false).unwrap();
        let mut checkpoint = ContextCheckpoint {
            from_message_id: "q0".into(),
            to_message_id: "a0".into(),
            anchor_id: "pending-at-compression".into(),
            summary: "saved summary".into(),
            at: 10,
            ..Default::default()
        };
        let (_, legacy) = estimate_cached_history(
            &headers,
            projection.selected_indices(),
            &[checkpoint.clone()],
            false,
            "model",
            || false,
        )
        .unwrap();
        assert_eq!(legacy, None);
        checkpoint.extra.insert(
            "prompt_tokens_source".into(),
            serde_json::Value::String("context_estimate".into()),
        );
        let (_, native) = estimate_cached_history(
            &headers,
            projection.selected_indices(),
            &[checkpoint],
            false,
            "model",
            || false,
        )
        .unwrap();
        assert_eq!(native, Some(1200));
    }
}

fn measure_wire(
    wire: &[rust_protocol::Message],
    cancelled: &impl Fn() -> bool,
) -> Result<PromptEstimate, String> {
    let mut estimate = PromptEstimate::default();
    for message in wire {
        check(cancelled)?;
        add_units(&mut estimate.text_units, &message.text, cancelled)?;
        for call in &message.tool_calls {
            add_units(&mut estimate.text_units, &call.name, cancelled)?;
            add_units(&mut estimate.text_units, &call.arguments, cancelled)?;
        }
        if !message.images_base64.is_empty() {
            estimate.image_messages += 1;
            estimate.image_count += message.images_base64.len();
        }
    }
    finish_estimate(estimate)
}

/// The same estimate as request construction, without cloning body/image payloads for a plan.
fn measure_input(
    input: ContextInput<'_>,
    prepared: &PreparedHistory<'_>,
    cancelled: &impl Fn() -> bool,
) -> Result<PromptEstimate, String> {
    let mut estimate = PromptEstimate::default();
    for prefix in input.system_prefix {
        add_units(&mut estimate.text_units, &prefix.text, cancelled)?;
    }
    if let Some(checkpoint) = prepared.selected_checkpoint {
        add_units(&mut estimate.text_units, "[上下文摘要] ", cancelled)?;
        add_units(
            &mut estimate.text_units,
            &input.checkpoints[checkpoint.index].summary,
            cancelled,
        )?;
    }
    for index in replay_indices(input, prepared) {
        check(cancelled)?;
        let message = &input.history[index];
        match message.role.as_str() {
            "user" => {
                if message.metadata.steer {
                    add_units(
                        &mut estimate.text_units,
                        "（用户在任务进行中说：）",
                        cancelled,
                    )?;
                }
                add_units(&mut estimate.text_units, &message.text, cancelled)?;
                if !input.image_input && !message.metadata.all_images().is_empty() {
                    add_units(
                        &mut estimate.text_units,
                        "\n（图片已省略：当前未开启「模型支持图片输入」）",
                        cancelled,
                    )?;
                }
                if input.image_input && !message.metadata.all_images().is_empty() {
                    estimate.image_messages += 1;
                    estimate.image_count += message.metadata.all_images().len();
                }
            }
            "tool" => {
                add_units(&mut estimate.text_units, tool_output(message), cancelled)?;
                if !message.metadata.all_images().is_empty() {
                    let wrapper = if input.image_input {
                        "（这是上一步  返回的截图）"
                    } else {
                        "（上一步  返回了一张截图，但当前模型未开启图片输入，你看不到图像内容。）"
                    };
                    add_units(&mut estimate.text_units, wrapper, cancelled)?;
                    add_units(
                        &mut estimate.text_units,
                        message.metadata.tool_name.as_deref().unwrap_or(""),
                        cancelled,
                    )?;
                    if input.image_input {
                        estimate.image_messages += 1;
                        estimate.image_count += message.metadata.all_images().len();
                    }
                }
            }
            _ => add_units(&mut estimate.text_units, &message.text, cancelled)?,
        }
        for call in &message.tool_calls {
            add_units(&mut estimate.text_units, &call.name, cancelled)?;
            add_units(&mut estimate.text_units, &call.arguments, cancelled)?;
        }
    }
    finish_estimate(estimate)
}

pub fn plan_compaction(
    input: ContextInput<'_>,
    keep_recent: usize,
    min_messages: usize,
    cancelled: impl Fn() -> bool,
) -> Result<Option<CompactionPlan>, String> {
    let prepared = prepared_history(input, &cancelled)?;
    let Some(cut) = tool_plan::plan_compaction(
        &prepared.rows,
        &prepared.checkpoints,
        keep_recent,
        min_messages,
        &cancelled,
    )
    .map_err(|error| error.to_string())?
    else {
        return Ok(None);
    };
    let mut transcript = String::new();
    if let Some(checkpoint) = cut.checkpoint {
        summary_push(&mut transcript, "system: [上下文摘要] ", &cancelled)?;
        summary_push(
            &mut transcript,
            &input.checkpoints[checkpoint.index].summary,
            &cancelled,
        )?;
    }
    for &index in &prepared.indices[cut.fresh.clone()] {
        if !transcript.is_empty() {
            summary_push(&mut transcript, "\n", &cancelled)?;
        }
        render_summary_message(&mut transcript, &input.history[index], &cancelled)?;
    }
    let source_units = utf16_units(&transcript, &cancelled)?;
    if transcript.trim().is_empty() {
        return Ok(None);
    }
    let covered_ids: Vec<_> = prepared.indices[cut.covered]
        .iter()
        .map(|&index| input.history[index].id.clone())
        .collect();
    let from_message_id = covered_ids
        .first()
        .cloned()
        .ok_or("压缩没有完整的来源消息")?;
    let to_message_id = covered_ids
        .last()
        .cloned()
        .ok_or("压缩没有完整的来源消息")?;
    let anchor_id = input
        .history
        .last()
        .map(|message| message.id.clone())
        .unwrap_or_default();
    let prompt_estimate = measure_input(input, &prepared, &cancelled)?;
    check(&cancelled)?;
    Ok(Some(CompactionPlan {
        transcript,
        covered_ids,
        from_message_id,
        to_message_id,
        anchor_id,
        source_units,
        prompt_estimate,
    }))
}

fn summary_push(
    output: &mut String,
    text: &str,
    cancelled: &impl Fn() -> bool,
) -> Result<(), String> {
    check(cancelled)?;
    if output
        .len()
        .checked_add(text.len())
        .is_none_or(|bytes| bytes > MAX_SUMMARY_SOURCE_BYTES)
    {
        return Err("摘要来源超过请求预算；原始上下文保留".into());
    }
    output.push_str(text);
    Ok(())
}

fn utf16_prefix(text: &str, max_units: usize) -> &str {
    let mut units = 0usize;
    let mut end = 0;
    for (index, ch) in text.char_indices() {
        if units + ch.len_utf16() > max_units {
            break;
        }
        units += ch.len_utf16();
        end = index + ch.len_utf8();
    }
    &text[..end]
}

fn render_summary_message(
    output: &mut String,
    message: &Message,
    cancelled: &impl Fn() -> bool,
) -> Result<(), String> {
    summary_push(output, &message.role, cancelled)?;
    summary_push(output, ": ", cancelled)?;
    if message.role == "tool" {
        let text = tool_output(message);
        let prefix = utf16_prefix(text, TOOL_OUTPUT_UNITS);
        summary_push(output, prefix, cancelled)?;
        if prefix.len() < text.len() {
            summary_push(
                output,
                &format!(" …（共 {} 字符）", utf16_units(text, cancelled)?),
                cancelled,
            )?;
        }
    } else {
        summary_push(output, &message.text, cancelled)?;
    }
    for call in &message.tool_calls {
        summary_push(output, "\n  [调用工具 ", cancelled)?;
        summary_push(output, &call.name, cancelled)?;
        summary_push(output, " ", cancelled)?;
        summary_push(output, &call.arguments, cancelled)?;
        summary_push(output, "]", cancelled)?;
    }
    if !message.metadata.all_images().is_empty() {
        summary_push(output, "\n  （含截图，已省略）", cancelled)?;
    }
    Ok(())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SummaryDecision<'a> {
    pub accepted: bool,
    pub body: &'a str,
    pub reason: &'static str,
}

pub fn checked_summary<'a>(
    text: &'a str,
    finish_reason: Option<&str>,
    source_units: usize,
) -> SummaryDecision<'a> {
    let body = text.trim();
    // Once the limit is crossed the exact oversized length is unnecessary.
    let units = body
        .chars()
        .take(MAX_SUMMARY_UNITS + 1)
        .map(char::len_utf16)
        .sum::<usize>();
    let reason = if body.is_empty() {
        "模型没有返回摘要"
    } else if matches!(
        finish_reason,
        Some("cut" | "length" | "max_tokens" | "max_output_tokens" | "content_filter")
    ) {
        "摘要被截断或未完整返回"
    } else if units > MAX_SUMMARY_UNITS || (source_units < 2000 && units > 2000) {
        "摘要过长"
    } else if source_units >= 2000 && (units as u128) * 2 >= source_units as u128 {
        "摘要没有比原文更小"
    } else {
        ""
    };
    SummaryDecision {
        accepted: reason.is_empty(),
        body,
        reason,
    }
}

#[derive(Default)]
pub struct CompactionRetryGate {
    rejected: Option<(usize, usize)>,
}

impl CompactionRetryGate {
    pub fn allows(&self, rows: usize, units: usize, manual: bool) -> bool {
        manual
            || self.rejected.is_none_or(|(before_rows, before_units)| {
                rows >= before_rows.saturating_add(4)
                    || units >= before_units.saturating_add(2048.max(before_units / 10))
            })
    }
    pub fn rejected(&mut self, rows: usize, units: usize) {
        self.rejected = Some((rows, units));
    }
    pub fn reset(&mut self) {
        self.rejected = None;
    }
}

/// A reported zero is a real measurement. None alone selects the estimate fallback.
pub fn should_compress(
    window: u64,
    percent: u8,
    auto: bool,
    manual: bool,
    estimate: u64,
    actual_prompt: Option<u64>,
) -> bool {
    if manual {
        return true;
    }
    if !auto || window == 0 {
        return false;
    }
    let percent = if (20..=95).contains(&percent) {
        percent
    } else {
        DEFAULT_COMPRESS_PERCENT
    };
    (actual_prompt.unwrap_or(estimate) as u128) * 100 > (window as u128) * percent as u128
}

#[derive(Clone, Copy, Debug)]
pub struct ReportedPrompt {
    pub tokens: u64,
    pub text_units: usize,
    pub image_messages: usize,
}

pub fn estimate_after_report(current: PromptEstimate, basis: Option<ReportedPrompt>) -> u64 {
    basis.map_or(current.tokens, |basis| {
        basis
            .tokens
            .saturating_add(current.text_units.saturating_sub(basis.text_units) as u64 / 4)
            .saturating_add(
                (current.image_messages.saturating_sub(basis.image_messages) as u64)
                    .saturating_mul(IMAGE_TOKENS),
            )
    })
}

pub enum RewriteKind<'a> {
    Regenerate { question_id: Option<&'a str> },
    EditAndResend { message_id: &'a str, text: &'a str },
}

pub struct RewriteInput<'a> {
    pub history: &'a [Message],
    pub metadata: &'a ConversationMetadata,
    pub next_assistant: &'a Message,
    pub kind: RewriteKind<'a>,
}

pub struct RewritePlan {
    pub messages: Vec<Message>,
    pub active_runs: BTreeMap<String, String>,
    pub checkpoints: Vec<ContextCheckpoint>,
    pub question_id: String,
    pub run_id: String,
    pub previous_run: Option<String>,
    pub destructive: bool,
}

pub fn plan_rewrite(
    input: RewriteInput<'_>,
    cancelled: impl Fn() -> bool,
) -> Result<RewritePlan, String> {
    check(&cancelled)?;
    if input.history.len() >= MAX_MESSAGES || input.metadata.checkpoints.len() > MAX_CHECKPOINTS {
        return Err("重答来源超过记录预算".into());
    }
    let next = input.next_assistant;
    let run_id = next
        .metadata
        .run_id
        .as_deref()
        .filter(|id| !id.is_empty() && id.len() <= 128)
        .ok_or("重答占位缺少新运行身份")?;
    if next.role != "assistant"
        || next.complete
        || !next.text.is_empty()
        || !next.thinking.is_empty()
        || !next.tool_calls.is_empty()
        || next.tool_call_id.is_some()
        || next.id.is_empty()
        || next.id.len() > 128
        || input.history.iter().any(|message| {
            message.id == next.id || message.metadata.run_id.as_deref() == Some(run_id)
        })
    {
        return Err("重答需要全新的空助手占位，未复用旧运行".into());
    }
    let mut ids = HashSet::new();
    for message in input.history {
        check(&cancelled)?;
        if message.id.is_empty() || !ids.insert(message.id.as_str()) {
            return Err("重答来源包含空或重复身份".into());
        }
    }
    let metadata_rows = rows(input.history);
    let projection = data_plan::project_transcript(
        &metadata_rows,
        &input.metadata.active_runs,
        None,
        &cancelled,
    )
    .map_err(|error| error.to_string())?;
    batch_boundaries(input.history, &projection.visible_indices, &cancelled)?;
    let (question_index, edited) = match input.kind {
        RewriteKind::Regenerate { question_id } => {
            let latest = input
                .history
                .iter()
                .rposition(|message| message.role == "user" && !message.metadata.steer)
                .ok_or("没有可重新回答的提问")?;
            if question_id.is_some_and(|id| id != input.history[latest].id) {
                return Err("只能对最后一轮提问重新回答".into());
            }
            (latest, None)
        }
        RewriteKind::EditAndResend { message_id, text } => {
            let body = text.trim();
            if body.is_empty() || body.len() > 1024 * 1024 {
                return Err("编辑提问为空或超过1 MiB".into());
            }
            let index = input
                .history
                .iter()
                .position(|message| {
                    message.id == message_id && message.role == "user" && !message.metadata.steer
                })
                .ok_or("编辑重发的原提问不存在")?;
            (index, Some(body))
        }
    };
    let question_id = input.history[question_index].id.clone();
    let previous_run = projection
        .chosen
        .iter()
        .find(|(turn, _)| *turn == question_id)
        .map(|(_, run)| (*run).to_owned());
    let mut messages = Vec::new();
    let retained = if edited.is_some() {
        question_index + 1
    } else {
        input.history.len()
    };
    for message in &input.history[..retained] {
        check(&cancelled)?;
        messages.push(message.clone());
    }
    if let Some(body) = edited {
        messages[question_index].text = body.to_owned();
        messages[question_index].metadata.edited = true;
    }
    let mut active_runs = input.metadata.active_runs.clone();
    if edited.is_some() {
        let questions: HashSet<_> = messages
            .iter()
            .filter(|message| message.role == "user" && !message.metadata.steer)
            .map(|message| message.id.as_str())
            .collect();
        active_runs.retain(|id, _| questions.contains(id.as_str()));
    }
    active_runs.insert(question_id.clone(), run_id.to_owned());
    let checkpoints = if edited.is_some() {
        invalidated_checkpoints(
            input.history,
            &input.metadata.checkpoints,
            question_index,
            &cancelled,
        )?
    } else {
        input.metadata.checkpoints.clone()
    };
    messages.push(next.clone());
    check(&cancelled)?;
    Ok(RewritePlan {
        messages,
        active_runs,
        checkpoints,
        question_id,
        run_id: run_id.to_owned(),
        previous_run,
        destructive: edited.is_some(),
    })
}

/// A later checkpoint can contain an earlier summary; all subsequent dependencies are stale.
pub fn invalidated_checkpoints(
    history: &[Message],
    checkpoints: &[ContextCheckpoint],
    edited_index: usize,
    cancelled: impl Fn() -> bool,
) -> Result<Vec<ContextCheckpoint>, String> {
    if edited_index >= history.len()
        || history.len() > MAX_MESSAGES
        || checkpoints.len() > MAX_CHECKPOINTS
    {
        return Err("检查点失效来源无效或超过预算".into());
    }
    let positions: HashMap<_, _> = history
        .iter()
        .enumerate()
        .map(|(index, message)| (message.id.as_str(), index))
        .collect();
    let mut result = Vec::new();
    for checkpoint in checkpoints {
        check(&cancelled)?;
        let mut next = checkpoint.clone();
        if positions
            .get(checkpoint.to_message_id.as_str())
            .is_none_or(|&to| to >= edited_index)
        {
            next.extra
                .insert("invalidated".into(), serde_json::Value::Bool(true));
        }
        result.push(next);
    }
    Ok(result)
}
