//! Operational profile draft. The Store supplies an atomic, revision-checked
//! sink; a successful disk commit precedes publishing the new actor snapshot.
use crate::platform::permissions::OperationKey;
use crate::{legacy_import::LegacySnapshot, legacy_migration, state::Message};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::sync::Arc;

pub const MAX_UNITS: usize = 8000;
const MAX_BYTES: usize = 256 * 1024;
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Preferences {
    #[serde(alias = "autoProfile")]
    pub auto_profile: bool,
    #[serde(alias = "profileEveryTurns")]
    pub profile_every_turns: i64,
    #[serde(alias = "injectProfile")]
    pub inject_profile: bool,
}
impl Default for Preferences {
    fn default() -> Self {
        Self {
            auto_profile: true,
            profile_every_turns: 8,
            inject_profile: true,
        }
    }
}
impl Preferences {
    pub fn effective_turns(&self) -> u32 {
        if (2..=40).contains(&self.profile_every_turns) {
            self.profile_every_turns as u32
        } else {
            8
        }
    }
    pub fn parse_legacy(value: &Value) -> Result<Self, String> {
        let fields = value.as_object().ok_or("旧画像设置不是对象")?;
        let mut selected = serde_json::Map::new();
        for (old, new) in [
            ("autoProfile", "auto_profile"),
            ("profileEveryTurns", "profile_every_turns"),
            ("injectProfile", "inject_profile"),
        ] {
            if let (Some(a), Some(b)) = (fields.get(old), fields.get(new)) {
                if a != b {
                    return Err("旧画像设置别名冲突".into());
                }
            }
            if let Some(value) = fields.get(new).or_else(|| fields.get(old)) {
                selected.insert(new.into(), value.clone());
            }
        }
        serde_json::from_value(Value::Object(selected))
            .map_err(|_| "旧画像开关或轮数类型无效".into())
    }
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Bookkeeping {
    #[serde(rename = "lastProfileAt")]
    pub last_profile_at: i64,
    #[serde(rename = "profileTurnMark")]
    pub profile_turn_mark: u64,
    #[serde(flatten)]
    pub extra: BTreeMap<String, Value>,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Proof {
    pub path: String,
    pub sha256: String,
    pub bytes: u64,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Document {
    pub version: u32,
    pub revision: u64,
    pub text: String,
    pub previous_text: Option<String>,
    pub state: Bookkeeping,
    pub source_fingerprint: Option<String>,
    pub sources: Vec<Proof>,
    pub warnings: Vec<String>,
    /// The owning Store supplies a durable monotonically increasing finished
    /// turn ordinal. Duplicate/lower ordinals cannot bump counters on replay.
    pub completed_turn_ordinal: u64,
}
impl Default for Document {
    fn default() -> Self {
        Self {
            version: 1,
            revision: 0,
            text: String::new(),
            previous_text: None,
            state: Bookkeeping::default(),
            source_fingerprint: None,
            sources: Vec::new(),
            warnings: Vec::new(),
            completed_turn_ordinal: 0,
        }
    }
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Revision {
    pub generation: String,
    pub revision: u64,
    pub text_sha256: String,
}
#[derive(Clone, Debug)]
pub struct Snapshot {
    pub revision: Revision,
    pub text: String,
    pub last_profile_at: i64,
    pub last_profile_label: Option<String>,
    pub profile_turn_mark: u64,
    pub warnings: Vec<String>,
}
impl Snapshot {
    pub fn validate(&self) -> Result<(), String> {
        if self.revision.generation.is_empty()
            || self.revision.generation.len() > 128
            || !hex(&self.revision.text_sha256)
            || self.revision.text_sha256 != hash(self.text.as_bytes())
            || self.text.len() > MAX_BYTES
            || self.last_profile_at < 0
            || self
                .last_profile_label
                .as_ref()
                .is_some_and(|label| label.len() > 128 || label.contains('\0'))
            || self.warnings.len() > 16
        {
            return Err("画像快照身份、hash或预算无效".into());
        }
        Ok(())
    }
}
#[derive(Clone, Debug)]
pub enum Request {
    Read(OperationKey),
    Save {
        operation: OperationKey,
        expected: Revision,
        text: String,
    },
}
#[derive(Clone, Debug)]
pub enum Update {
    Read {
        operation: OperationKey,
        snapshot: Arc<Snapshot>,
    },
    Saved {
        operation: OperationKey,
        snapshot: Arc<Snapshot>,
    },
    Failed {
        operation: OperationKey,
        error: String,
    },
    Changed {
        snapshot: Arc<Snapshot>,
    },
}
#[derive(Clone, Copy)]
pub enum Writer {
    User,
    Model,
}
/// Return the document actually re-read after atomic publication and directory
/// fsync. Returning the input clone would not establish a readback confirmation.
pub type Persist = Box<dyn FnMut(&Document, &Revision) -> Result<Document, String> + Send>;
pub struct Repository {
    generation: String,
    document: Document,
    persist: Persist,
    write_blocked: bool,
}
impl Repository {
    pub fn snapshot(&self) -> Arc<Snapshot> {
        let last_profile_label = if self.document.state.last_profile_at == 0 {
            Some("从未整理".into())
        } else {
            crate::platform::calendar::zone_id()
                .and_then(|zone| {
                    crate::platform::calendar::time_label(
                        self.document.state.last_profile_at,
                        &zone,
                    )
                })
                .ok()
        };
        Arc::new(Snapshot {
            revision: self.revision(),
            text: self.document.text.clone(),
            last_profile_at: self.document.state.last_profile_at,
            last_profile_label,
            profile_turn_mark: self.document.state.profile_turn_mark,
            warnings: self.document.warnings.clone(),
        })
    }
    /// Root handles these on the same actor/repository used by tools and summary.
    /// UI OperationKey and persistent Revision have separate identity domains.
    pub fn dispatch(
        &mut self,
        request: Request,
        ui_live: impl Fn(&OperationKey) -> bool,
    ) -> Update {
        let operation = match &request {
            Request::Read(operation) | Request::Save { operation, .. } => operation.clone(),
        };
        if let Err(error) = operation.validate() {
            return Update::Failed { operation, error };
        }
        if !ui_live(&operation) {
            return Update::Failed {
                operation,
                error: "画像页面请求已失效，草稿未写入".into(),
            };
        }
        match request {
            Request::Read(operation) => Update::Read {
                operation,
                snapshot: self.snapshot(),
            },
            Request::Save {
                operation,
                expected,
                text,
            } => match self.replace(&expected, text, Writer::User) {
                Ok(_) => Update::Saved {
                    operation,
                    snapshot: self.snapshot(),
                },
                Err(error) => Update::Failed { operation, error },
            },
        }
    }
    pub fn open(
        generation: String,
        bytes: Option<&[u8]>,
        persist: Persist,
    ) -> Result<Self, String> {
        if generation.is_empty() || generation.len() > 128 {
            return Err("画像存储generation无效".into());
        }
        let document = match bytes {
            Some(bytes) if bytes.len() <= MAX_BYTES => {
                serde_json::from_slice(bytes).map_err(|_| "画像文档损坏，未当作空画像")?
            }
            Some(_) => return Err("画像文档超过预算".into()),
            None => Document::default(),
        };
        validate(&document)?;
        Ok(Self {
            generation,
            document,
            persist,
            write_blocked: false,
        })
    }
    pub fn document(&self) -> &Document {
        &self.document
    }
    pub fn revision(&self) -> Revision {
        Revision {
            generation: self.generation.clone(),
            revision: self.document.revision,
            text_sha256: hash(self.document.text.as_bytes()),
        }
    }
    fn commit(&mut self, mut next: Document, expected: &Revision) -> Result<Revision, String> {
        if self.write_blocked {
            return Err("画像写入或读回有未确认结果，请由Store重新读取恢复".into());
        }
        if &self.revision() != expected {
            return Err("画像已被更新，请重新读取后保存".into());
        }
        next.revision = next.revision.checked_add(1).ok_or("画像版本已耗尽")?;
        validate(&next)?;
        let committed = match (self.persist)(&next, expected) {
            Ok(committed) => committed,
            Err(error) => {
                self.write_blocked = true;
                return Err(error);
            }
        };
        if committed != next {
            self.write_blocked = true;
            return Err("画像实际读回与提交不一致，未发布本地成功状态".into());
        }
        self.document = committed;
        Ok(self.revision())
    }
    /// User editing does not use the model loss guard or reset summary counters.
    pub fn replace(
        &mut self,
        expected: &Revision,
        text: String,
        writer: Writer,
    ) -> Result<Revision, String> {
        writable(&text)?;
        if let Writer::Model = writer {
            loss_guard(&self.document.text, &text)?;
        }
        let mut next = self.document.clone();
        if next.text == text {
            if &self.revision() != expected {
                return Err("画像已更新".into());
            }
            return Ok(self.revision());
        }
        next.previous_text = Some(next.text);
        next.text = text;
        self.commit(next, expected)
    }
    pub fn bump_finished_turn(
        &mut self,
        ordinal: u64,
        automatic_enabled: bool,
        is_child: bool,
    ) -> Result<u64, String> {
        if !automatic_enabled || is_child || ordinal <= self.document.completed_turn_ordinal {
            return Ok(self.document.state.profile_turn_mark);
        }
        let expected = self.revision();
        let mut next = self.document.clone();
        next.completed_turn_ordinal = ordinal;
        next.state.profile_turn_mark = next
            .state
            .profile_turn_mark
            .checked_add(1)
            .ok_or("画像轮数已耗尽")?;
        self.commit(next, &expected)?;
        Ok(self.document.state.profile_turn_mark)
    }
    /// Newer content or newer bookkeeping wins. A stale summarizer never writes
    /// its old snapshot over a user's edit or resets newer completed turns.
    pub fn apply_summary(
        &mut self,
        expected: &Revision,
        raw: &str,
        at: i64,
    ) -> Result<SummaryOutcome, String> {
        if &self.revision() != expected {
            return Err("总结期间画像/轮数已更新，本次结果未覆盖新版本".into());
        }
        if at < 0 {
            return Err("画像总结时间无效".into());
        }
        let ops = parse_ops(raw)?;
        if ops.is_empty() {
            return Ok(SummaryOutcome::Unchanged);
        }
        let text = apply_ops(&self.document.text, &ops)?;
        if text == self.document.text {
            return Ok(SummaryOutcome::Unchanged);
        }
        loss_guard(&self.document.text, &text)?;
        let mut next = self.document.clone();
        next.previous_text = Some(next.text);
        next.text = text;
        next.state.last_profile_at = at;
        next.state.profile_turn_mark = 0;
        self.commit(next, expected)?;
        Ok(SummaryOutcome::Updated)
    }
    pub fn injected(&self, prefs: &Preferences) -> Result<String, String> {
        if !prefs.inject_profile || self.document.text.trim().is_empty() {
            return Ok(String::new());
        }
        writable(&self.document.text)?;
        Ok(format!(
            "## 关于用户（画像）\n{}",
            self.document.text.trim()
        ))
    }
}
#[derive(Clone, Debug)]
pub enum SummaryOutcome {
    Updated,
    Unchanged,
}

/// Produces a typed live candidate before the owning migration seals/commits the
/// source. Original profile/state/backup bytes and unknown fields stay archived.
pub fn from_legacy(
    snapshot: &LegacySnapshot,
    expected_fingerprint: &str,
) -> Result<Document, String> {
    if legacy_migration::snapshot_fingerprint(snapshot)? != expected_fingerprint {
        return Err("画像来源快照指纹已变化".into());
    }
    let mut document = Document {
        source_fingerprint: Some(expected_fingerprint.into()),
        ..Document::default()
    };
    for blob in snapshot
        .assets
        .iter()
        .chain(snapshot.documents.iter().map(|document| &document.source))
    {
        if !matches!(
            blob.relative_path.as_str(),
            "profile.md" | "profile.md.bak" | "profile.state.json"
        ) {
            continue;
        }
        if hash(&blob.original_bytes) != blob.sha256 || blob.original_bytes.len() > MAX_BYTES {
            return Err("旧画像来源hash或预算无效".into());
        }
        if document
            .sources
            .iter()
            .any(|source| source.path == blob.relative_path)
        {
            return Err("旧画像来源路径重复".into());
        }
        document.sources.push(Proof {
            path: blob.relative_path.clone(),
            sha256: blob.sha256.clone(),
            bytes: blob.original_bytes.len() as u64,
        });
        match blob.relative_path.as_str() {
            "profile.md" => {
                document.text = String::from_utf8(blob.original_bytes.clone())
                    .map_err(|_| "旧画像不是UTF-8，原文封存且未作为空画像")?
            }
            "profile.md.bak" => {
                document.previous_text = Some(
                    String::from_utf8(blob.original_bytes.clone())
                        .map_err(|_| "旧画像备份不是UTF-8，未丢弃原文")?,
                )
            }
            _ => match serde_json::from_slice::<Bookkeeping>(&blob.original_bytes) {
                Ok(state) if state.last_profile_at >= 0 => document.state = state,
                _ => document.warnings.push(
                    "旧画像记账无法解码，沿旧策略从零记账；原始状态字节与指纹保持封存".into(),
                ),
            },
        }
    }
    if document.text.encode_utf16().count() > MAX_UNITS {
        document
            .warnings
            .push("旧画像超过8000字，完整保留供编辑；自动总结/注入须先缩减".into());
    }
    validate(&document)?;
    Ok(document)
}

#[derive(Clone, Debug)]
pub enum Op {
    Add {
        text: String,
        section: Option<String>,
    },
    Remove(String),
    Replace {
        from: String,
        to: String,
    },
}
pub fn parse_ops(raw: &str) -> Result<Vec<Op>, String> {
    if raw.len() > 32 * 1024 || raw.contains('\0') {
        return Err("画像操作输出超过32 KiB或含NUL".into());
    }
    let mut ops = Vec::new();
    for line in raw.lines() {
        let line = line.trim();
        if let Some(body) = line.strip_prefix("+ ") {
            let mut body = body.trim();
            let mut section = None;
            if body.starts_with('[') {
                if let Some(close) = body.find(']').filter(|close| *close > 1) {
                    section = Some(body[1..close].trim().to_owned())
                        .filter(|section| !section.is_empty());
                    body = body[close + 1..].trim();
                }
            }
            if !body.is_empty() {
                ops.push(Op::Add {
                    text: body.into(),
                    section,
                });
            }
        } else if let Some(body) = line.strip_prefix("- ") {
            if !body.trim().is_empty() {
                ops.push(Op::Remove(body.trim().into()));
            }
        } else if let Some(body) = line.strip_prefix("~ ") {
            if let Some((from, to)) = body.split_once("=>") {
                if !from.trim().is_empty() && !to.trim().is_empty() {
                    ops.push(Op::Replace {
                        from: from.trim().into(),
                        to: to.trim().into(),
                    });
                }
            }
        }
        if ops.len() > 128 {
            return Err("画像操作超过128条".into());
        }
    }
    Ok(ops)
}
/// Preserve every raw line, including prose and unfamiliar headings. Only an
/// exact, unique existing bullet can be removed/replaced. Failure rejects all.
pub fn apply_ops(old: &str, ops: &[Op]) -> Result<String, String> {
    let mut lines: Vec<String> = old.lines().map(str::to_owned).collect();
    let trailing = old.ends_with('\n');
    for op in ops {
        match op {
            Op::Remove(text) | Op::Replace { from: text, .. } => {
                let hits: Vec<_> = lines
                    .iter()
                    .enumerate()
                    .filter(|(_, line)| {
                        line.trim()
                            .strip_prefix("- ")
                            .is_some_and(|body| body.trim() == text)
                    })
                    .map(|(index, _)| index)
                    .collect();
                if hits.len() != 1 {
                    return Err("要删除/修正的画像条目不能唯一匹配；整批未保存".into());
                }
                let index = hits[0];
                match op {
                    Op::Remove(_) => {
                        lines.remove(index);
                    }
                    Op::Replace { to, .. } => {
                        let leading = lines[index].len() - lines[index].trim_start().len();
                        lines[index] = format!("{}- {to}", &lines[index][..leading]);
                    }
                    _ => unreachable!(),
                }
            }
            Op::Add { text, section } => {
                let section = section.clone().or_else(|| {
                    lines
                        .iter()
                        .find_map(|line| line.trim().strip_prefix("### ").map(str::to_owned))
                });
                let target = section.as_deref().and_then(|section| {
                    lines.iter().position(|line| {
                        line.trim()
                            .strip_prefix("### ")
                            .is_some_and(|name| name.trim() == section)
                    })
                });
                let start = target.map_or(0, |index| index + 1);
                let end = lines
                    .iter()
                    .enumerate()
                    .skip(start)
                    .find(|(_, line)| line.trim().starts_with("### "))
                    .map_or(lines.len(), |(index, _)| index);
                if lines[start..end].iter().any(|line| {
                    line.trim()
                        .strip_prefix("- ")
                        .is_some_and(|body| body.trim() == text)
                }) {
                    continue;
                }
                if section.is_some() && target.is_none() {
                    if lines.last().is_some_and(|line| !line.is_empty()) {
                        lines.push(String::new());
                    }
                    lines.push(format!("### {}", section.as_deref().unwrap_or("其它")));
                    lines.push(format!("- {text}"));
                } else {
                    lines.insert(end, format!("- {text}"));
                }
            }
        }
    }
    let mut text = lines.join("\n");
    if trailing && !text.is_empty() {
        text.push('\n');
    }
    writable(&text)?;
    Ok(text)
}
fn loss_guard(old: &str, next: &str) -> Result<(), String> {
    let bullets: Vec<_> = old
        .lines()
        .filter_map(|line| line.trim().strip_prefix("- "))
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .collect();
    let normalized = next.replace([' ', '\u{3000}'], "");
    let lost = bullets
        .iter()
        .filter(|bullet| !normalized.contains(&bullet.replace([' ', '\u{3000}'], "")))
        .count();
    if bullets.len() >= 3 && lost >= 3 && lost * 100 / bullets.len() > 30 {
        return Err(format!(
            "本次画像会丢失{lost}/{}条旧信息，未保存",
            bullets.len()
        ));
    }
    Ok(())
}
pub fn render_recent(messages: &[Message]) -> Result<String, String> {
    let mut lines = Vec::new();
    let mut units = 0;
    for message in messages.iter().rev() {
        let role = match message.role.as_str() {
            "user" => "用户",
            "assistant" => "助手",
            _ => continue,
        };
        let text = message.text.trim();
        if text.is_empty() {
            continue;
        }
        let available = 6000usize.saturating_sub(units + role.encode_utf16().count() + 1);
        if available == 0 {
            break;
        }
        let line = format!("{role}：{}", tail_units(text, available));
        units += line.encode_utf16().count() + 1;
        lines.push(line);
        if units >= 6000 {
            break;
        }
    }
    lines.reverse();
    let text = lines.join("\n");
    if text.is_empty() {
        return Err("没有可用于画像总结的用户/助手对话".into());
    }
    Ok(text)
}
fn tail_units(text: &str, max: usize) -> &str {
    let mut used = 0;
    let mut start = text.len();
    for (index, char) in text.char_indices().rev() {
        if used + char.len_utf16() > max {
            break;
        }
        used += char.len_utf16();
        start = index;
    }
    &text[start..]
}
fn writable(text: &str) -> Result<(), String> {
    if text.encode_utf16().count() > MAX_UNITS || text.contains('\0') {
        Err("画像超过8000字或含NUL，未截断保存".into())
    } else {
        Ok(())
    }
}
fn validate(document: &Document) -> Result<(), String> {
    if document.version != 1
        || document.text.len() > MAX_BYTES
        || document
            .previous_text
            .as_ref()
            .is_some_and(|text| text.len() > MAX_BYTES)
        || document.state.last_profile_at < 0
        || document.sources.len() > 3
        || document.warnings.len() > 16
    {
        return Err("画像文档身份或预算无效".into());
    }
    if document
        .source_fingerprint
        .as_ref()
        .is_some_and(|value| !hex(value))
        || document.sources.iter().any(|source| {
            !matches!(
                source.path.as_str(),
                "profile.md" | "profile.md.bak" | "profile.state.json"
            ) || !hex(&source.sha256)
                || source.bytes > MAX_BYTES as u64
        })
    {
        return Err("画像来源证明无效".into());
    }
    if serde_json::to_vec(document)
        .map_err(|_| "画像文档序列化失败")?
        .len()
        > MAX_BYTES
    {
        return Err("画像文档超过256 KiB".into());
    }
    Ok(())
}
pub fn encode(document: &Document) -> Result<Vec<u8>, String> {
    validate(document)?;
    serde_json::to_vec(document).map_err(|_| "画像文档序列化失败".into())
}
fn hash(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}
fn hex(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}
pub fn snapshot_json(repository: &Repository, prefs: &Preferences) -> Value {
    json!({"revision":repository.revision(),"profile":repository.document(),"preferences":prefs,"effective_profile_every_turns":prefs.effective_turns()})
}
