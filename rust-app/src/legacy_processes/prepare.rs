use super::*;
pub fn prepare(snapshot: &LegacySnapshot, owners: &OwnerMap) -> Result<Draft, String> {
    let mut draft = Draft {
        jobs: Vec::new(),
        terminals: Vec::new(),
        files: Vec::new(),
        warnings: Vec::new(),
        archive_bytes: 0,
    };
    let mut jobs = BTreeMap::new();
    let mut terminal_logs: BTreeMap<String, (Option<&LegacyBlob>, Option<&LegacyBlob>)> =
        BTreeMap::new();
    for blob in &snapshot.assets {
        if blob.owning_root == snapshot.source_root {
            if let Some(name) = blob.relative_path.strip_prefix("jobs/") {
                if let Some(id) = name.strip_suffix(".log") {
                    if id.len() == 8 && id.bytes().all(|byte| byte.is_ascii_hexdigit()) {
                        if jobs.insert(id.to_owned(), blob).is_some() {
                            return Err("旧作业日志存在重复来源".into());
                        }
                    }
                }
            }
        }
        if Some(&blob.owning_root) == snapshot.no_backup_root.as_ref() {
            if let Some(name) = blob.relative_path.strip_prefix("terminal-logs/") {
                let (id, previous) = if let Some(id) = name.strip_suffix(".previous.log") {
                    (id, true)
                } else if let Some(id) = name.strip_suffix(".log") {
                    (id, false)
                } else {
                    continue;
                };
                old_id(id)?;
                let slots = terminal_logs.entry(id.into()).or_default();
                let slot = if previous { &mut slots.0 } else { &mut slots.1 };
                if slot.replace(blob).is_some() {
                    return Err("旧终端日志存在重复来源".into());
                }
            }
        }
    }
    if jobs.len() > MAX_ROWS || terminal_logs.len() > MAX_ROWS {
        return Err("旧进程归档行数超过128条预算，原源未改".into());
    }
    for (source_id, blob) in jobs {
        let id = identity("job", &blob.owning_root, &source_id);
        let from = blob.original_bytes.len().saturating_sub(MAX_FILE_BYTES);
        let destination = PathBuf::from(format!("legacy-jobs/{id}.log"));
        let source = add_blob(
            &mut draft,
            blob,
            from,
            blob.original_bytes.len(),
            destination.clone(),
        )?;
        let log = LogArchive {
            cursor_domain: format!("job-archive-{id}"),
            retained_from: from as u64,
            end: blob.original_bytes.len() as u64,
            segments: vec![LogSegment {
                path: destination,
                from: from as u64,
                bytes: (blob.original_bytes.len() - from) as u64,
                source,
            }],
            original_cursor_reusable: false,
            earlier_history_lost: Some(from > 0),
            notice: if from > 0 {
                format!("运行读取保留原文件末尾32 MiB；字节0–{from}仍由原源与密封资产归档保留")
            } else {
                String::new()
            },
        };
        let artifact = JobArtifact {
            id: id.clone(),
            source_id,
            phase: Phase::InterruptedUnknown,
            command: None,
            started: None,
            exit_code: None,
            owner_chat_id: None,
            log,
            notice: "旧JobStore只持久化日志；命令、启动、退出和所属会话均未知，未恢复PID或执行命令"
                .into(),
        };
        add_json(
            &mut draft,
            PathBuf::from(format!("legacy-jobs/{id}.json")),
            &artifact,
        )?;
        draft.jobs.push(artifact);
    }
    let mut metadata: BTreeMap<String, (&LegacyBlob, &Value)> = BTreeMap::new();
    for document in snapshot
        .documents
        .iter()
        .filter(|document| document.kind == LegacyDocumentKind::TerminalSessions)
    {
        if Some(&document.source.owning_root) != snapshot.no_backup_root.as_ref() {
            return Err("旧终端元数据不属于明确提供的noBackup根".into());
        }
        if !matches!(
            document.source.relative_path.as_str(),
            "terminal-sessions.json" | "terminal-sessions.json.bak"
        ) {
            return Err("旧终端元数据不是已提交AtomicFile候选；.new不能成为活动记录".into());
        }
        let rows = document.value.as_array().ok_or("旧终端元数据不是数组")?;
        if rows.len() > MAX_ROWS {
            return Err("旧终端元数据超过128条预算".into());
        }
        proof(&document.source, 0, document.source.original_bytes.len())?;
        for row in rows {
            let id = row
                .get("id")
                .and_then(Value::as_str)
                .ok_or("旧终端行缺少ID")?;
            old_id(id)?;
            if metadata
                .insert(id.into(), (&document.source, row))
                .is_some()
            {
                return Err("旧终端元数据包含重复ID或多个活动AtomicFile候选".into());
            }
        }
    }
    let ids: BTreeSet<_> = metadata
        .keys()
        .chain(terminal_logs.keys())
        .cloned()
        .collect();
    if ids.len() > MAX_ROWS {
        return Err("旧终端记录与孤立日志合计超过128条预算".into());
    }
    let no_backup = snapshot.no_backup_root.as_ref();
    for source_id in ids {
        let owner = no_backup.ok_or("旧终端没有明确noBackup来源根")?;
        let id = identity("terminal", owner, &source_id);
        let metadata = metadata.get(&source_id).copied();
        let row = metadata.map(|(_, row)| row);
        let string = |name: &str| -> Result<Option<String>, String> {
            match row.and_then(|row| row.get(name)) {
                None | Some(Value::Null) => Ok(None),
                Some(Value::String(value)) if value.len() <= 8192 && !value.contains('\0') => {
                    Ok(Some(value.clone()))
                }
                _ => Err(format!("旧终端{name}字段无效或超过预算")),
            }
        };
        let original_phase = string("phase")?;
        let exit_code = match row.and_then(|row| row.get("exitCode")) {
            None | Some(Value::Null) => None,
            Some(value) => Some(
                value
                    .as_i64()
                    .and_then(|value| i32::try_from(value).ok())
                    .ok_or("旧终端退出码无效")?,
            ),
        };
        let phase = match original_phase.as_deref() {
            Some("starting" | "running" | "stopping") => Phase::InterruptedUnknown,
            Some("exited") => Phase::Exited,
            Some("failed") => Phase::Failed,
            _ => Phase::Unknown,
        };
        let source_owner_chat_id = string("ownerChatId")?;
        let owner_chat_id = source_owner_chat_id
            .as_ref()
            .and_then(|source| owners.get(source))
            .cloned();
        if let Some(owner) = &owner_chat_id {
            crate::jobs::identifier(owner)?;
        }
        let slots = terminal_logs.get(&source_id).copied().unwrap_or_default();
        let mut segments = Vec::new();
        let mut end = 0u64;
        for (index, blob) in [slots.0, slots.1].into_iter().enumerate() {
            let Some(blob) = blob else {
                continue;
            };
            if blob.original_bytes.len() > 1024 * 1024 {
                return Err("旧终端日志段超过原1 MiB预算，未静默截断复制；原源未改".into());
            }
            let destination = PathBuf::from(format!("legacy-terminals/{id}.{index}.raw"));
            let source = add_blob(
                &mut draft,
                blob,
                0,
                blob.original_bytes.len(),
                destination.clone(),
            )?;
            segments.push(LogSegment {
                path: destination,
                from: end,
                bytes: blob.original_bytes.len() as u64,
                source,
            });
            end += blob.original_bytes.len() as u64;
        }
        let log=LogArchive{cursor_domain:format!("terminal-archive-{id}"),retained_from:0,end,segments,
            original_cursor_reusable:false,earlier_history_lost:None,
            notice:"旧原始日志未保存绝对游标或轮换次数；仅在独立归档域内按previous/current顺序读取，不复用旧实时UTF-16游标".into()};
        let artifact = TerminalArtifact {
            id: id.clone(),
            source_id,
            source_row: row.cloned(),
            metadata_source: metadata
                .map(|(blob, _)| proof(blob, 0, blob.original_bytes.len()))
                .transpose()?,
            name: string("name")?,
            kind: string("kind")?,
            shell_label: string("shell")?,
            cwd: string("cwd")?,
            phase,
            original_phase,
            exit_code,
            source_owner_chat_id,
            owner_chat_id,
            pid_restored: false,
            log,
            notice: if row.is_some() {
                "只恢复旧记录和日志；原PID、PTY、输入队列、临时授权与进程均未恢复".into()
            } else {
                "日志没有对应元数据；名称、状态、退出码与所属会话未知".into()
            },
        };
        add_json(
            &mut draft,
            PathBuf::from(format!("legacy-terminals/{id}.json")),
            &artifact,
        )?;
        draft.terminals.push(artifact);
    }
    if no_backup.is_none() {
        draft
            .warnings
            .push("未提供原noBackupFilesDir；终端元数据与日志未进行跨根推断".into());
    }
    draft.warnings.push(
        "JobStore未持久化JobInfo或状态；所有旧作业日志保留未知状态，不从输出推断成功、失败或命令"
            .into(),
    );
    Ok(draft)
}
fn old_id(id: &str) -> Result<(), String> {
    if id.is_empty()
        || id.len() > 128
        || !id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
    {
        return Err("旧终端ID不能作为安全日志文件名".into());
    }
    Ok(())
}
fn add_blob(
    draft: &mut Draft,
    blob: &LegacyBlob,
    from: usize,
    to: usize,
    path: PathBuf,
) -> Result<SourceProof, String> {
    let source = proof(blob, from, to)?;
    let bytes = blob.original_bytes[from..to].to_vec();
    add_file(draft, GenerationFile { path, bytes })?;
    Ok(source)
}
fn add_json(draft: &mut Draft, path: PathBuf, value: &impl Serialize) -> Result<(), String> {
    let bytes = serde_json::to_vec(value).map_err(|_| "旧进程适配记录编码失败")?;
    if bytes.len() > 512 * 1024 {
        return Err("旧进程适配单条记录超过512 KiB".into());
    }
    add_file(draft, GenerationFile { path, bytes })
}
fn add_file(draft: &mut Draft, file: GenerationFile) -> Result<(), String> {
    relative(&file.path)?;
    draft.archive_bytes = draft
        .archive_bytes
        .checked_add(file.bytes.len())
        .filter(|bytes| *bytes <= MAX_ARCHIVE_BYTES)
        .ok_or("旧进程运行归档超过128 MiB预算；未覆盖当前代，原源保留")?;
    draft.files.push(file);
    Ok(())
}
