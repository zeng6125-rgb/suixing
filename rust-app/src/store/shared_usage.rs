use super::*;
use crate::legacy_usage::{self, DayStamp, LegacyDocument};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{collections::BTreeSet, io::BufRead};

#[derive(Deserialize, Serialize)]
#[serde(default, rename_all = "camelCase")]
#[derive(Default)]
struct UsageMessage {
    role: String,
    model: Option<String>,
    timestamp: Option<i64>,
    #[serde(alias = "prompt_tokens")]
    prompt_tokens: Option<i64>,
    #[serde(alias = "completion_tokens")]
    completion_tokens: Option<i64>,
    #[serde(alias = "reasoning_tokens")]
    reasoning_tokens: Option<i64>,
    #[serde(alias = "cached_tokens")]
    cached_tokens: Option<i64>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ChildUsage {
    id: String,
    parent_chat_id: String,
    messages: Vec<UsageMessage>,
}

pub(super) fn read(
    files: &Path,
    conversations: &BTreeSet<String>,
    zone: &str,
    day_for: impl Fn(i64, &str) -> Result<DayStamp, String>,
) -> Result<LegacyDocument, String> {
    let mut result = legacy_usage::empty();
    result.zone_id = zone.into();
    result.schema_version = 3;
    result.warnings.clear();

    let cache_path = files.join("usage.json");
    let cache = match optional_json::<Value>(files, &cache_path, MAX_USAGE_BYTES) {
        Ok(cache) => cache.filter(|cache| cache["schemaVersion"] == 3),
        Err(_) => {
            result
                .warnings
                .push("用量缓存无法读取，按现有会话重新汇总。".into());
            None
        }
    };
    let cache_zone = cache
        .as_ref()
        .and_then(|cache| cache["zoneId"].as_str())
        .unwrap_or("");
    let cached_chats = cache.as_ref().and_then(|cache| cache["chats"].as_object());

    let archived = files.join("deleted-usage");
    for path in json_entries(&archived)? {
        match required_json::<Value>(files, &path, MAX_USAGE_BYTES)
            .and_then(|value| legacy_usage::deleted_chat(&value))
        {
            Ok(entry) => {
                if result
                    .chats
                    .get(&entry.id)
                    .is_none_or(|old| old.deleted_at < entry.deleted_at)
                {
                    result.chats.insert(entry.id.clone(), entry);
                }
            }
            Err(_) => result
                .warnings
                .push("部分已删除会话的用量记录无法读取。".into()),
        }
    }
    let mut live = conversations.clone();
    for id in conversations {
        let meta =
            required_json::<Value>(files, &shared_chat::meta(files, id), MAX_SETTINGS_BYTES)?;
        let cached = cached_chats.and_then(|chats| chats.get(id));
        let cache_current = cached.filter(|usage| {
            cache_zone == zone
                && usage["scannedGen"] == meta["gen"]
                && usage["scannedBytes"] == meta["logBytes"]
                && usage["scanned"] == meta["messageCount"]
        });
        let current = cache_current.and_then(|usage| legacy_usage::chat(id, usage, false).ok());
        if let Some(mut current) = current {
            current.zone_id = zone.into();
            current.deleted = false;
            current.deleted_at = 0;
            current.rebuilt_from_committed = true;
            result.chats.insert(id.clone(), current);
        } else {
            let rows = committed_usage(files, &meta)?;
            legacy_usage::rebuild_source(&mut result, id, &rows, zone, &|at| day_for(at, zone))?;
        }
        for (key, rows) in child_usage(files, id)? {
            live.insert(key.clone());
            legacy_usage::rebuild_source(&mut result, &key, &rows, zone, &|at| day_for(at, zone))?;
        }
    }

    if let Some(chats) = cached_chats {
        for (id, usage) in chats {
            if !live.contains(id) && !result.chats.contains_key(id) {
                if let Ok(mut chat) = legacy_usage::chat(id, usage, false) {
                    chat.zone_id = cache_zone.into();
                    result.chats.insert(id.clone(), chat);
                }
            }
        }
    }
    result.warnings.sort();
    result.warnings.dedup();
    legacy_usage::validate(&result)?;
    Ok(result)
}

fn child_usage(files: &Path, parent: &str) -> Result<Vec<(String, Vec<Value>)>, String> {
    let mut results = Vec::new();
    for path in json_entries(&super::shared_children::directory(files, parent))? {
        let child = required_json::<ChildUsage>(files, &path, MAX_CHILD_BYTES)?;
        if child.parent_chat_id != parent
            || path.file_stem().and_then(|stem| stem.to_str()) != Some(child.id.as_str())
        {
            return Err("共用子任务用量身份与文件不一致".into());
        }
        let rows = child
            .messages
            .into_iter()
            .filter(|row| row.role == "assistant")
            .map(|row| serde_json::to_value(row).map_err(|_| "共用子任务用量无法编码"))
            .collect::<Result<Vec<_>, _>>()?;
        results.push((format!("child/{parent}/{}", child.id), rows));
    }
    Ok(results)
}

pub(super) fn archive_conversation(files: &Path, id: &str) -> Result<(), String> {
    let meta = required_json::<Value>(files, &shared_chat::meta(files, id), MAX_SETTINGS_BYTES)?;
    let main = committed_usage(files, &meta)?;
    let children = child_usage(files, id)?;
    if main.is_empty() && children.iter().all(|(_, rows)| rows.is_empty()) {
        return Ok(());
    }
    let zone = crate::platform::calendar::zone_id()?;
    let day_for = |at| {
        let stamp = crate::platform::calendar::day_for(at, &zone)?;
        Ok(DayStamp {
            zone_id: stamp.zone_id,
            day: stamp.day,
        })
    };
    let mut summaries = legacy_usage::empty();
    if !main.is_empty() {
        legacy_usage::rebuild_source(&mut summaries, id, &main, &zone, &day_for)?;
    }
    for (key, rows) in children {
        if !rows.is_empty() {
            legacy_usage::rebuild_source(&mut summaries, &key, &rows, &zone, &day_for)?;
        }
    }
    let deleted_at = now()?;
    for chat in summaries.chats.values() {
        write_deleted(files, chat, deleted_at)?;
    }
    Ok(())
}

fn write_deleted(
    files: &Path,
    summary: &legacy_usage::LegacyChat,
    deleted_at: i64,
) -> Result<(), String> {
    let sums = |value: &legacy_usage::StoredSums| {
        json!({
            "prompt":value.prompt.unwrap_or(0), "completion":value.completion.unwrap_or(0),
            "reasoning":value.reasoning.unwrap_or(0), "cached":value.cached.unwrap_or(0),
        })
    };
    let days: BTreeMap<_, _> = summary
        .days
        .iter()
        .map(|(day, value)| (day, sums(value)))
        .collect();
    let models: BTreeMap<_, _> = summary
        .models
        .iter()
        .map(|(model, value)| (model, sums(value)))
        .collect();
    let daily: BTreeMap<_, _> = summary
        .daily_models
        .iter()
        .map(|(day, models)| {
            let values: BTreeMap<_, _> = models.iter().map(|(model, bucket)| (model, json!({
            "tokens":sums(&bucket.sums), "reported":bucket.reported_requests.unwrap_or(0),
            "unreported":bucket.unreported_requests.unwrap_or(0),
        }))).collect();
            (day, values)
        })
        .collect();
    let dir = owned_directory(files, "deleted-usage")?;
    let filename = format!("{:x}.json", Sha256::digest(summary.id.as_bytes()));
    atomic_json(
        files,
        &dir.join(filename),
        &json!({
            "id":summary.id,"zoneId":summary.zone_id,"usage":{
                "days":days,"models":models,"dailyModels":daily,"deletedAt":deleted_at,
            }
        }),
        MAX_USAGE_BYTES,
    )
    .map_err(|failure| failure.message)
}

fn json_entries(directory: &Path) -> Result<Vec<PathBuf>, String> {
    let entries = match fs::read_dir(directory) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(fs_error("读取共用用量目录", directory, error)),
    };
    let mut paths = Vec::new();
    for entry in entries {
        let path = entry
            .map_err(|error| fs_error("读取共用用量条目", directory, error))?
            .path();
        if path
            .extension()
            .is_some_and(|extension| extension == "json")
        {
            paths.push(path);
        }
    }
    paths.sort();
    Ok(paths)
}

fn committed_usage(files: &Path, meta: &Value) -> Result<Vec<Value>, String> {
    let (file, boundary) = shared_chat::committed_log(files, meta)?;
    let mut log = std::io::BufReader::new(file.take(boundary));
    let mut line = Vec::new();
    let mut count = 0;
    let mut rows = Vec::new();
    while log
        .read_until(b'\n', &mut line)
        .map_err(|_| "共用会话用量读取失败")?
        > 0
    {
        let row: UsageMessage =
            serde_json::from_slice(&line).map_err(|_| "共用会话用量解析失败")?;
        if row.role == "assistant" {
            rows.push(serde_json::to_value(row).map_err(|_| "共用会话用量无法编码")?);
        }
        count += 1;
        line.clear();
    }
    if Some(count) != meta["messageCount"].as_u64() {
        return Err("共用会话用量与提交记录不一致".into());
    }
    Ok(rows)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn shared_usage_replaces_stale_cache_and_counts_each_source_once() {
        let files =
            std::env::temp_dir().join(format!("shared-usage-{}", crate::controller::unique_id()));
        let store = files.join("chats/store");
        let deleted = files.join("deleted-usage");
        fs::create_dir_all(&store).unwrap();
        fs::create_dir_all(&deleted).unwrap();
        let files = fs::canonicalize(files).unwrap();
        let row = json!({"role":"assistant","timestamp":100,"model":"model","promptTokens":12,"completionTokens":4});
        let log = format!("{row}\n");
        fs::write(store.join("live.2.jsonl"), format!("{log}uncommitted tail")).unwrap();
        fs::write(
            store.join("live.meta"),
            json!({"id":"live","gen":2,"logBytes":log.len(),"messageCount":1}).to_string(),
        )
        .unwrap();
        fs::write(
            files.join("usage.json"),
            json!({"schemaVersion":3,"zoneId":"UTC","chats":{
                "live":{"scannedGen":1,"days":{"2026-11-19":{"prompt":99}}},
                "deleted":{"days":{"2026-11-19":{"prompt":98}}}
            }})
            .to_string(),
        )
        .unwrap();
        fs::write(deleted.join("old-name.json"), json!({"id":"deleted","zoneId":"UTC","usage":{
            "deletedAt":200,"dailyModels":{"2026-11-19":{"model":{"tokens":{"prompt":40,"completion":8},"reported":1,"unreported":0}}}
        }}).to_string()).unwrap();
        fs::write(deleted.join("live-prepared.json"), json!({"id":"live","zoneId":"UTC","usage":{
            "deletedAt":190,"dailyModels":{"2026-11-19":{"model":{"tokens":{"prompt":97},"reported":1,"unreported":0}}}
        }}).to_string()).unwrap();
        let input = read(
            &files,
            &BTreeSet::from(["live".into()]),
            "UTC",
            |_, zone| {
                Ok(DayStamp {
                    day: "2026-11-19".into(),
                    zone_id: zone.into(),
                })
            },
        )
        .unwrap();
        let filter = legacy_usage::Filter {
            dates: vec!["2026-11-19".into()],
            ..Default::default()
        };
        let report = legacy_usage::report(&input, &[], &filter, "UTC", |_| unreachable!()).unwrap();
        assert!(report.shared_sources);
        assert_eq!(report.legacy_window.prompt.known_sum, 52);
        assert_eq!(report.legacy_window.completion.known_sum, 12);
        assert_eq!(report.legacy_window.reported_requests, 2);
        assert!(!input.chats["live"].deleted);
        assert!(input.chats["deleted"].deleted);
        write_deleted(&files, &input.chats["live"], 300).unwrap();
        let filename = format!("{:x}.json", Sha256::digest(b"live"));
        let written: Value = required_json(
            &files,
            &files.join("deleted-usage").join(filename),
            MAX_USAGE_BYTES,
        )
        .unwrap();
        let deleted = legacy_usage::deleted_chat(&written).unwrap();
        assert_eq!(
            deleted.daily_models["2026-11-19"]["model"].sums.prompt,
            Some(12)
        );
    }
}
