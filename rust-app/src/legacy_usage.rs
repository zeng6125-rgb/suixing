//! Operational legacy/current usage projection. Legacy aggregate coverage cannot
//! become per-field request evidence. Original JSON remains in the typed record.
pub(crate) mod archives;
use crate::{
    legacy_import::{LegacyDocumentKind, LegacySnapshot},
    legacy_migration,
    state::UsageRecord,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct StoredSums {
    pub prompt: Option<u64>,
    pub completion: Option<u64>,
    pub reasoning: Option<u64>,
    pub cached: Option<u64>,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Bucket {
    pub sums: StoredSums,
    pub reported_requests: Option<u64>,
    pub unreported_requests: Option<u64>,
    /// Legacy known-request classification was prompt OR completion reported.
    /// It cannot tell whether cached/reasoning (or either individual core field)
    /// was reported. Never claim per-field zero/coverage from this counter.
    pub per_field_coverage_unknown: bool,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct LegacyChat {
    pub id: String,
    pub deleted: bool,
    pub deleted_at: i64,
    pub zone_id: String,
    pub rebuilt_from_committed: bool,
    pub days: BTreeMap<String, StoredSums>,
    pub models: BTreeMap<String, StoredSums>,
    pub daily_models: BTreeMap<String, BTreeMap<String, Bucket>>,
    pub undated_models: BTreeMap<String, Bucket>,
    pub source_document: Value,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct LegacyDocument {
    pub version: u32,
    pub source_fingerprint: String,
    pub schema_version: i64,
    pub zone_id: String,
    pub chats: BTreeMap<String, LegacyChat>,
    pub warnings: Vec<String>,
}

/// Explicit absence of imported evidence has no invented source fingerprint.
pub fn empty() -> LegacyDocument {
    LegacyDocument {
        version: 1,
        source_fingerprint: String::new(),
        schema_version: 0,
        zone_id: String::new(),
        chats: BTreeMap::new(),
        warnings: vec!["当前Store没有已导入旧用量来源；真实请求独立统计".into()],
    }
}
pub fn from_legacy(snapshot: &LegacySnapshot, fingerprint: &str) -> Result<LegacyDocument, String> {
    if legacy_migration::snapshot_fingerprint(snapshot)? != fingerprint {
        return Err("旧用量来源指纹变化".into());
    }
    from_documents(&snapshot.documents, fingerprint)
}
pub fn from_archived(
    documents: &[crate::legacy_import::LegacyDocument],
    fingerprint: &str,
) -> Result<LegacyDocument, String> {
    use sha2::{Digest, Sha256};
    if fingerprint.len() != 64 || !fingerprint.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err("旧用量归档指纹无效".into());
    }
    for document in documents {
        if !matches!(
            document.kind,
            LegacyDocumentKind::UsageCache | LegacyDocumentKind::DeletedUsage
        ) || format!("{:x}", Sha256::digest(&document.source.original_bytes))
            != document.source.sha256
        {
            return Err("旧用量归档种类或原字节证明无效".into());
        }
    }
    from_documents(documents, fingerprint)
}
fn from_documents(
    documents: &[crate::legacy_import::LegacyDocument],
    fingerprint: &str,
) -> Result<LegacyDocument, String> {
    let mut result = LegacyDocument {
        version: 1,
        source_fingerprint: fingerprint.into(),
        schema_version: 0,
        zone_id: String::new(),
        chats: BTreeMap::new(),
        warnings: Vec::new(),
    };
    let mut deleted = BTreeMap::new();
    for document in documents {
        match document.kind {
            LegacyDocumentKind::UsageCache => {
                let value = object(&document.value)?;
                result.schema_version = value
                    .get("schemaVersion")
                    .map(|value| value.as_i64().ok_or("旧用量schemaVersion类型无效"))
                    .transpose()?
                    .unwrap_or(0);
                result.zone_id = value
                    .get("zoneId")
                    .map(|value| value.as_str().ok_or("旧用量zoneId类型无效"))
                    .transpose()?
                    .unwrap_or("")
                    .into();
                if let Some(chats) = value.get("chats") {
                    for (id, value) in object(chats)? {
                        let mut chat = chat(id, value, false)?;
                        chat.zone_id = result.zone_id.clone();
                        if result.chats.insert(id.clone(), chat).is_some() {
                            return Err("旧用量来源重复".into());
                        }
                    }
                }
            }
            LegacyDocumentKind::DeletedUsage => {
                let chat = deleted_chat(&document.value)?;
                if deleted.insert(chat.id.clone(), chat).is_some() {
                    return Err("旧删除用量来源重复".into());
                }
            }
            _ => {}
        }
    }
    // Independent final deletion summaries replace, rather than add to, cache.
    for (id, chat) in deleted {
        result.chats.insert(id, chat);
    }
    if result.schema_version != 3 {
        result
            .warnings
            .push("旧缓存版本保留为历史证据，未假定其逐日/逐模型交叉汇总完整".into());
    }
    if result.zone_id.is_empty() {
        result
            .warnings
            .push("旧汇总日历时区未记录，日期保持原键且不重分时区".into());
    }
    validate(&result)?;
    Ok(result)
}
pub(crate) fn deleted_chat(value: &Value) -> Result<LegacyChat, String> {
    let fields = object(value)?;
    let id = fields
        .get("id")
        .and_then(Value::as_str)
        .ok_or("删除用量缺少 ID")?;
    let mut entry = chat(id, fields.get("usage").ok_or("删除用量缺少统计")?, true)?;
    entry.zone_id = fields
        .get("zoneId")
        .and_then(Value::as_str)
        .unwrap_or("")
        .into();
    Ok(entry)
}

pub(crate) fn chat(id: &str, value: &Value, deleted: bool) -> Result<LegacyChat, String> {
    if id.is_empty() || id.len() > 512 || id.contains('\0') {
        return Err("旧用量chat身份无效".into());
    }
    let fields = object(value)?;
    let deleted_at = fields
        .get("deletedAt")
        .map(|value| value.as_i64().ok_or("旧用量删除时间类型无效"))
        .transpose()?
        .unwrap_or(0);
    if deleted_at < 0 || deleted && deleted_at == 0 {
        return Err("旧删除用量缺少实际删除时间".into());
    }
    for key in ["scanned", "scannedUpTo", "scannedBytes"] {
        if fields
            .get(key)
            .is_some_and(|value| value.as_i64().is_none_or(|value| value < 0))
        {
            return Err("旧用量扫描水位无效".into());
        }
    }
    // -2 is the old application's real child-record source; -1 denotes its
    // single-file source. Preserve both instead of rejecting legitimate children.
    if fields
        .get("scannedGen")
        .is_some_and(|value| value.as_i64().is_none_or(|value| value < -2))
    {
        return Err("旧用量扫描generation无效".into());
    }
    let mut daily_models = BTreeMap::new();
    if let Some(days) = fields.get("dailyModels") {
        for (day, models) in object(days)? {
            day_valid(day)?;
            let mut row = BTreeMap::new();
            for (model, value) in object(models)? {
                key_valid(model)?;
                let fields = object(value)?;
                row.insert(
                    model.clone(),
                    Bucket {
                        sums: fields
                            .get("tokens")
                            .map(tally)
                            .transpose()?
                            .unwrap_or_default(),
                        reported_requests: number(fields.get("reported"))?,
                        unreported_requests: number(fields.get("unreported"))?,
                        per_field_coverage_unknown: true,
                    },
                );
            }
            daily_models.insert(day.clone(), row);
        }
    }
    Ok(LegacyChat {
        id: id.into(),
        deleted: deleted || deleted_at > 0,
        deleted_at,
        zone_id: String::new(),
        rebuilt_from_committed: false,
        days: tally_map(fields.get("days"), true)?,
        models: tally_map(fields.get("models"), false)?,
        daily_models,
        undated_models: BTreeMap::new(),
        source_document: value.clone(),
    })
}
fn tally_map(value: Option<&Value>, dates: bool) -> Result<BTreeMap<String, StoredSums>, String> {
    let mut result = BTreeMap::new();
    if let Some(value) = value {
        for (key, value) in object(value)? {
            if dates {
                day_valid(key)?;
            } else {
                key_valid(key)?;
            }
            result.insert(key.clone(), tally(value)?);
        }
    }
    Ok(result)
}
fn tally(value: &Value) -> Result<StoredSums, String> {
    let fields = object(value)?;
    Ok(StoredSums {
        prompt: number(fields.get("prompt"))?,
        completion: number(fields.get("completion"))?,
        reasoning: number(fields.get("reasoning"))?,
        cached: number(fields.get("cached"))?,
    })
}
fn number(value: Option<&Value>) -> Result<Option<u64>, String> {
    value
        .map(|value| {
            value
                .as_u64()
                .ok_or("旧用量计数必须是非负Long")
                .and_then(|value| {
                    if value <= i64::MAX as u64 {
                        Ok(value)
                    } else {
                        Err("旧用量计数超过Long")
                    }
                })
        })
        .transpose()
        .map_err(str::to_owned)
}
fn object(value: &Value) -> Result<&serde_json::Map<String, Value>, String> {
    value.as_object().ok_or_else(|| "旧用量文档不是对象".into())
}
fn key_valid(key: &str) -> Result<(), String> {
    if key.len() > 512 || key.contains('\0') {
        Err("用量模型键无效".into())
    } else {
        Ok(())
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Metric {
    pub known_sum: u128,
    pub known_rows: u64,
    pub unknown_rows: u64,
    pub invalid_rows: u64,
}
impl Metric {
    fn request(&mut self, value: Option<i32>) {
        match value {
            Some(value) if value >= 0 => {
                self.known_sum += value as u128;
                self.known_rows += 1
            }
            Some(_) => self.invalid_rows += 1,
            None => self.unknown_rows += 1,
        }
    }
    fn legacy(&mut self, value: Option<u64>) {
        match value {
            Some(value) => {
                self.known_sum += value as u128;
                self.known_rows += 1
            }
            None => self.unknown_rows += 1,
        }
    }
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Totals {
    pub prompt: Metric,
    pub completion: Metric,
    pub reasoning: Metric,
    pub cached: Metric,
    pub reported_requests: u128,
    pub unreported_requests: u128,
    pub coverage_unknown_buckets: u64,
    pub per_field_coverage_unknown: bool,
    pub pending_requests: u64,
}
impl Totals {
    fn legacy(&mut self, sums: &StoredSums) {
        self.prompt.legacy(sums.prompt);
        self.completion.legacy(sums.completion);
        self.reasoning.legacy(sums.reasoning);
        self.cached.legacy(sums.cached);
        self.per_field_coverage_unknown = true;
    }
    fn bucket(&mut self, bucket: &Bucket) {
        self.legacy(&bucket.sums);
        match (bucket.reported_requests, bucket.unreported_requests) {
            (Some(reported), Some(unreported)) => {
                self.reported_requests += reported as u128;
                self.unreported_requests += unreported as u128
            }
            _ => self.coverage_unknown_buckets += 1,
        }
    }
    fn request(&mut self, record: &UsageRecord) {
        self.prompt.request(record.prompt_tokens);
        self.completion.request(record.completion_tokens);
        self.reasoning.request(record.reasoning_tokens);
        self.cached.request(record.cached_tokens);
        if record.pending {
            self.pending_requests += 1;
        }
        if record.prompt_tokens.is_some_and(|value| value >= 0)
            || record.completion_tokens.is_some_and(|value| value >= 0)
        {
            self.reported_requests += 1
        } else {
            self.unreported_requests += 1
        }
    }
}
#[derive(Clone, Debug, Default)]
pub struct Filter {
    pub dates: Vec<String>,
    pub model: Option<String>,
    pub deleted: Option<bool>,
    pub chat_id: Option<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Report {
    pub source_fingerprint: String,
    pub shared_sources: bool,
    pub legacy_zone_id: String,
    pub request_zone_id: String,
    pub legacy_window: Totals,
    pub legacy_by_zone: BTreeMap<String, Totals>,
    pub legacy_undated: Totals,
    pub requests_window: Totals,
    pub legacy_by_day: BTreeMap<String, Totals>,
    pub legacy_by_model: BTreeMap<String, Totals>,
    pub requests_by_day: BTreeMap<String, Totals>,
    pub requests_by_model: BTreeMap<String, Totals>,
    pub legacy_all_time_by_day: BTreeMap<String, Totals>,
    pub legacy_all_time_by_model: BTreeMap<String, Totals>,
    pub legacy_matching_chats: usize,
    pub matching_requests: usize,
    pub warnings: Vec<String>,
}
/// Actual per-timestamp calendar resolver belongs to the platform/host, including
/// historical DST. Never replace an unknown zone with a fixed UTC offset.
#[derive(Clone)]
pub struct DayStamp {
    pub zone_id: String,
    pub day: String,
}
/// Deletion evidence is supplied by the owning Store's authoritative metadata.
/// None remains unknown; a missing UI cache entry cannot supply this field.
pub struct RequestSource {
    pub record: UsageRecord,
    pub deleted: Option<bool>,
}
pub fn report(
    legacy: &LegacyDocument,
    records: &[RequestSource],
    filter: &Filter,
    request_zone: &str,
    day_for: impl Fn(i64) -> Result<DayStamp, String>,
) -> Result<Report, String> {
    validate(legacy)?;
    if records.len() > 65_536
        || request_zone.is_empty()
        || request_zone.len() > 128
        || filter.dates.len() > 366
    {
        return Err("用量查询范围或日历时区无效".into());
    }
    let dates: BTreeSet<_> = filter.dates.iter().cloned().collect();
    if dates.len() != filter.dates.len() {
        return Err("用量日期范围重复".into());
    }
    for day in &dates {
        day_valid(day)?;
    }
    let mut result = Report {
        source_fingerprint: legacy.source_fingerprint.clone(),
        shared_sources: legacy.source_fingerprint.is_empty() && legacy.schema_version == 3,
        legacy_zone_id: legacy.zone_id.clone(),
        request_zone_id: request_zone.into(),
        legacy_window: Totals::default(),
        legacy_by_zone: BTreeMap::new(),
        legacy_undated: Totals::default(),
        requests_window: Totals::default(),
        legacy_by_day: BTreeMap::new(),
        legacy_by_model: BTreeMap::new(),
        requests_by_day: BTreeMap::new(),
        requests_by_model: BTreeMap::new(),
        legacy_all_time_by_day: BTreeMap::new(),
        legacy_all_time_by_model: BTreeMap::new(),
        legacy_matching_chats: 0,
        matching_requests: 0,
        warnings: legacy.warnings.clone(),
    };
    for chat in legacy.chats.values() {
        if filter.deleted.is_some() && chat.deleted_at == 0 && !chat.rebuilt_from_committed {
            result.warnings.push(
                "部分缓存来源的存续/删除状态未由已提交源或删除回执证明，未按零时间假定它们存续"
                    .into(),
            );
            continue;
        }
        if filter
            .deleted
            .is_some_and(|deleted| chat.deleted != deleted)
            || filter.chat_id.as_ref().is_some_and(|id| &chat.id != id)
        {
            continue;
        }
        result.legacy_matching_chats += 1;
        for (model, bucket) in &chat.undated_models {
            if filter
                .model
                .as_ref()
                .is_none_or(|selected| selected == model)
            {
                result.legacy_undated.bucket(bucket);
            }
        }
        // Old independent day/model maps remain independent all-time evidence.
        // They are never summed together or reconstructed from dailyModels.
        for (day, sums) in &chat.days {
            if filter.model.is_some() || !dates.is_empty() && !dates.contains(day) {
                continue;
            }
            result
                .legacy_all_time_by_day
                .entry(day.clone())
                .or_default()
                .legacy(sums);
        }
        for (model, sums) in &chat.models {
            if !dates.is_empty()
                || filter
                    .model
                    .as_ref()
                    .is_some_and(|selected| selected != model)
            {
                continue;
            }
            result
                .legacy_all_time_by_model
                .entry(model.clone())
                .or_default()
                .legacy(sums);
        }
        if chat.daily_models.is_empty() && (!chat.days.is_empty() || !chat.models.is_empty()) {
            result.warnings.push(format!(
                "来源{}无逐日模型交叉表，无法证明筛选后的交叉统计",
                chat.id
            ));
        }
        for (day, models) in &chat.daily_models {
            if !dates.is_empty() && !dates.contains(day) {
                continue;
            }
            for (model, bucket) in models {
                if filter
                    .model
                    .as_ref()
                    .is_some_and(|selected| selected != model)
                {
                    continue;
                }
                result.legacy_window.bucket(bucket);
                result
                    .legacy_by_zone
                    .entry(if chat.zone_id.is_empty() {
                        "时区未知".into()
                    } else {
                        chat.zone_id.clone()
                    })
                    .or_default()
                    .bucket(bucket);
                result
                    .legacy_by_day
                    .entry(day.clone())
                    .or_default()
                    .bucket(bucket);
                result
                    .legacy_by_model
                    .entry(model.clone())
                    .or_default()
                    .bucket(bucket);
            }
        }
    }
    let legacy_ids: BTreeSet<_> = legacy.chats.keys().cloned().collect();
    for source in records {
        let record = &source.record;
        if record.summary {
            continue;
        }
        if record.at < 0 {
            return Err("真实请求账单时间无效".into());
        }
        if filter
            .chat_id
            .as_ref()
            .is_some_and(|id| id != &record.conversation_id)
            || filter
                .model
                .as_ref()
                .is_some_and(|model| model != &record.model)
        {
            continue;
        }
        if let Some(selected) = filter.deleted {
            match source.deleted {
                Some(deleted) if deleted == selected => {}
                Some(_) => continue,
                None => {
                    result
                        .warnings
                        .push("部分真实请求的删除状态未知，未把它们判为已删除/存续".into());
                    continue;
                }
            }
        }
        let stamp = day_for(record.at)?;
        if stamp.zone_id != request_zone {
            return Err("请求日期resolver使用了另一时区".into());
        }
        day_valid(&stamp.day)?;
        if !dates.is_empty() && !dates.contains(&stamp.day) {
            continue;
        }
        result.matching_requests += 1;
        result.requests_window.request(record);
        result
            .requests_by_day
            .entry(stamp.day)
            .or_default()
            .request(record);
        result
            .requests_by_model
            .entry(record.model.clone())
            .or_default()
            .request(record);
        if legacy_ids.contains(&record.conversation_id) {
            result
                .warnings
                .push("会话汇总与请求账本可能记录同一次请求，两组总计分别展示。".into());
        }
    }
    result.warnings.sort();
    result.warnings.dedup();
    if result.warnings.len() > 64 {
        result.warnings.truncate(63);
        result
            .warnings
            .push("另有统计覆盖问题，请按单个来源筛选查看".into());
    }
    if !legacy.zone_id.is_empty() && legacy.zone_id != request_zone {
        result.warnings.push(
            "旧汇总保持其原日历时区，请求行按当前选定时区统计；旧日键无法无损重分时区".into(),
        );
    }
    if result.legacy_by_zone.len() > 1 {
        result.legacy_zone_id = "多来源日历".into();
        result.warnings.push(
            "历史来源有多个原日历时区；日期筛选分别作用于各来源原日历，按时区汇总独立显示".into(),
        );
    } else if let Some(zone) = result.legacy_by_zone.keys().next() {
        result.legacy_zone_id = zone.clone();
    }
    if serde_json::to_vec(&result)
        .map_err(|_| "用量结果序列化失败")?
        .len()
        > 2 * 1024 * 1024
    {
        return Err("用量统计结果超过2 MiB，请缩小查询范围".into());
    }
    Ok(result)
}
pub fn validate(document: &LegacyDocument) -> Result<(), String> {
    if document.version != 1
        || !(document.source_fingerprint.is_empty()
            || document.source_fingerprint.len() == 64
                && document
                    .source_fingerprint
                    .bytes()
                    .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)))
        || document.zone_id.len() > 128
        || document.chats.len() > 8192
    {
        return Err("用量操作化文档身份或来源预算无效".into());
    }
    let mut rows = 0;
    for (id, chat) in &document.chats {
        if id != &chat.id
            || chat.deleted_at < 0
            || chat.deleted && chat.deleted_at == 0
            || chat.zone_id.len() > 128
        {
            return Err("旧用量来源身份或删除证据不一致".into());
        }
        for (day, models) in &chat.daily_models {
            day_valid(day)?;
            for model in models.keys() {
                key_valid(model)?;
            }
        }
        for day in chat.days.keys() {
            day_valid(day)?;
        }
        for model in chat.models.keys() {
            key_valid(model)?;
        }
        rows += chat.days.len()
            + chat.models.len()
            + chat.undated_models.len()
            + chat.daily_models.values().map(BTreeMap::len).sum::<usize>();
        if rows > 262_144 {
            return Err("旧用量汇总超过262144行预算".into());
        }
    }
    if serde_json::to_vec(document)
        .map_err(|_| "用量文档序列化失败")?
        .len()
        > 32 * 1024 * 1024
    {
        return Err("旧用量操作化文档超过32 MiB".into());
    }
    Ok(())
}
pub fn encode(document: &LegacyDocument) -> Result<Vec<u8>, String> {
    validate(document)?;
    serde_json::to_vec(document).map_err(|_| "旧用量文档序列化失败".into())
}
pub fn day_valid(day: &str) -> Result<(), String> {
    if day.len() != 10
        || day.as_bytes()[4] != b'-'
        || day.as_bytes()[7] != b'-'
        || !day
            .bytes()
            .enumerate()
            .all(|(index, byte)| index == 4 || index == 7 || byte.is_ascii_digit())
    {
        return Err("用量日期键无效".into());
    }
    let year = day[..4].parse::<u32>().map_err(|_| "日期年份无效")?;
    let month = day[5..7].parse::<u32>().map_err(|_| "日期月份无效")?;
    let date = day[8..].parse::<u32>().map_err(|_| "日期日无效")?;
    let max = match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) => 29,
        2 => 28,
        _ => 0,
    };
    if year == 0 || date == 0 || date > max {
        return Err("用量日历日期无效".into());
    }
    Ok(())
}

/// Rebuild available live sources from authoritative committed metadata.
/// Replaces their cache contribution, with no draft read or invented request row.
/// The source's prior cache JSON and immutable snapshot proof remain preserved.
pub fn rebuild_live(
    snapshot: &LegacySnapshot,
    document: &mut LegacyDocument,
    zone: &str,
    day_for: impl Fn(i64) -> Result<DayStamp, String>,
) -> Result<(), String> {
    if legacy_migration::snapshot_fingerprint(snapshot)? != document.source_fingerprint
        || zone.is_empty()
        || zone.len() > 128
    {
        return Err("历史用量重扫的来源指纹或时区无效".into());
    }
    let parents: BTreeSet<_> = snapshot
        .conversations
        .iter()
        .map(|chat| chat.id.clone())
        .collect();
    let mut next = document.clone();
    for source in &snapshot.conversations {
        rebuild_source(
            &mut next,
            &source.id,
            &source.normalized_messages,
            zone,
            &day_for,
        )?;
    }
    for source in &snapshot.documents {
        if source.kind != LegacyDocumentKind::ChildRecord {
            continue;
        }
        let fields = object(&source.value)?;
        let parent = fields
            .get("parentChatId")
            .and_then(Value::as_str)
            .ok_or("旧子用量来源缺少parentChatId")?;
        if !parents.contains(parent) {
            continue;
        }
        let id = fields
            .get("id")
            .and_then(Value::as_str)
            .ok_or("旧子用量来源缺少id")?;
        let messages = fields
            .get("messages")
            .and_then(Value::as_array)
            .ok_or("旧子用量来源缺少messages")?;
        rebuild_source(
            &mut next,
            &format!("child/{parent}/{id}"),
            messages,
            zone,
            &day_for,
        )?;
    }
    next.warnings.push(
        "可读取的存续主/子来源已按已提交消息重新统计；旧零值不能证明端点报告零，逐字段覆盖保持未知"
            .into(),
    );
    validate(&next)?;
    *document = next;
    Ok(())
}
pub(crate) fn rebuild_source(
    document: &mut LegacyDocument,
    id: &str,
    messages: &[Value],
    zone: &str,
    day_for: &impl Fn(i64) -> Result<DayStamp, String>,
) -> Result<(), String> {
    if messages.len() > 131_072 {
        return Err("旧来源消息元数据超过预算".into());
    }
    // Do not mutate the previous source until the complete replacement is valid.
    let previous = document.chats.get(id);
    let mut chat = LegacyChat {
        id: id.into(),
        deleted: false,
        deleted_at: 0,
        zone_id: zone.into(),
        rebuilt_from_committed: true,
        days: BTreeMap::new(),
        models: BTreeMap::new(),
        daily_models: BTreeMap::new(),
        undated_models: BTreeMap::new(),
        source_document: previous.map_or(Value::Null, |chat| chat.source_document.clone()),
    };
    for message in messages {
        let fields = object(message)?;
        if fields.get("role").and_then(Value::as_str) != Some("assistant") {
            continue;
        }
        let model = fields
            .get("model")
            .and_then(Value::as_str)
            .filter(|model| !model.trim().is_empty())
            .unwrap_or("历史/未知");
        key_valid(model)?;
        let get = |old: &str, new: &str| -> Result<i64, String> {
            match fields.get(new).or_else(|| fields.get(old)) {
                None | Some(Value::Null) => Ok(0),
                Some(value) => value
                    .as_i64()
                    .filter(|value| *value >= 0)
                    .ok_or_else(|| "旧来源token元数据无效".into()),
            }
        };
        let values = [
            get("promptTokens", "prompt_tokens")?,
            get("completionTokens", "completion_tokens")?,
            get("reasoningTokens", "reasoning_tokens")?,
            get("cachedTokens", "cached_tokens")?,
        ];
        // The actual old usageKnown predicate is strictly >0. StoredMessage
        // defaults to 0, so old zero alone is ambiguous rather than Some(0).
        let known = values[0] > 0 || values[1] > 0;
        let sums = if known {
            StoredSums {
                prompt: Some(values[0] as u64),
                completion: Some(values[1] as u64),
                reasoning: Some(values[2] as u64),
                cached: Some(values[3] as u64),
            }
        } else {
            zero_sums()
        };
        let timestamp = fields
            .get("timestamp")
            .and_then(Value::as_i64)
            .filter(|at| *at > 0);
        let bucket = if let Some(at) = timestamp {
            let stamp = day_for(at)?;
            if stamp.zone_id != zone {
                return Err("历史来源日期使用另一时区".into());
            }
            day_valid(&stamp.day)?;
            if known {
                sum_stored(
                    chat.days.entry(stamp.day.clone()).or_insert_with(zero_sums),
                    &sums,
                )?;
            }
            chat.daily_models
                .entry(stamp.day)
                .or_default()
                .entry(model.into())
                .or_insert_with(zero_bucket)
        } else {
            chat.undated_models
                .entry(model.into())
                .or_insert_with(zero_bucket)
        };
        sum_stored(&mut bucket.sums, &sums)?;
        if known {
            bucket.reported_requests = Some(
                bucket
                    .reported_requests
                    .unwrap_or(0)
                    .checked_add(1)
                    .ok_or("历史报告数溢出")?,
            );
            sum_stored(
                chat.models.entry(model.into()).or_insert_with(zero_sums),
                &sums,
            )?;
        } else {
            bucket.unreported_requests = Some(
                bucket
                    .unreported_requests
                    .unwrap_or(0)
                    .checked_add(1)
                    .ok_or("历史未报告数溢出")?,
            );
        }
    }
    document.chats.insert(id.into(), chat);
    Ok(())
}
fn zero_sums() -> StoredSums {
    StoredSums {
        prompt: Some(0),
        completion: Some(0),
        reasoning: Some(0),
        cached: Some(0),
    }
}
fn zero_bucket() -> Bucket {
    Bucket {
        sums: zero_sums(),
        reported_requests: Some(0),
        unreported_requests: Some(0),
        per_field_coverage_unknown: true,
    }
}
fn sum_stored(target: &mut StoredSums, value: &StoredSums) -> Result<(), String> {
    for (left, right) in [
        (&mut target.prompt, value.prompt),
        (&mut target.completion, value.completion),
        (&mut target.reasoning, value.reasoning),
        (&mut target.cached, value.cached),
    ] {
        *left = match (*left, right) {
            (Some(left), Some(right)) => Some(
                left.checked_add(right)
                    .filter(|sum| *sum <= i64::MAX as u64)
                    .ok_or("历史Long累计溢出")?,
            ),
            _ => None,
        };
    }
    Ok(())
}

/// Replace a complete source contribution, preserving prior cache JSON. The
/// Store supplies only old membership proven by the pinned import archive.
pub struct CommittedSource {
    pub id: String,
    pub zone_id: String,
    pub rows: Vec<Value>,
}
pub fn replace_committed(
    document: &mut LegacyDocument,
    sources: &[CommittedSource],
    day_for: impl Fn(i64, &str) -> Result<DayStamp, String>,
) -> Result<(), String> {
    let mut next = document.clone();
    let mut ids = BTreeSet::new();
    for source in sources {
        if source.zone_id.is_empty() || source.zone_id.len() > 128 || !ids.insert(&source.id) {
            return Err("已提交旧来源身份或时区重复/无效".into());
        }
        rebuild_source(
            &mut next,
            &source.id,
            &source.rows,
            &source.zone_id,
            &|at| day_for(at, &source.zone_id),
        )?;
    }
    next.warnings.push("旧来源按已提交消息替换其缓存贡献；新的Rust请求只在真实请求账本统计，旧零值的逐字段覆盖仍未知".into());
    next.warnings.sort();
    next.warnings.dedup();
    validate(&next)?;
    *document = next;
    Ok(())
}
