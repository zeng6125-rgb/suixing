//! Actor-owned, explicit import. A candidate generation is validated before
//! publication; imported IDs never silently replace current records.
use super::*;
use crate::{
    legacy_migration::{self, GenerationFile, MigrationPlan},
    state::LegacyImportReport,
};

impl Store {
    pub(crate) fn first_run_legacy_data(&self) -> Result<bool, String> {
        if self.shared_files_root.is_some() || self.root != self.namespace_root {
            return Ok(false);
        }
        match fs::symlink_metadata(self.root.join("settings.json")) {
            Ok(_) => Ok(false),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => self.has_legacy_data(),
            Err(error) => Err(format!("无法检查新设置是否已保存：{error}")),
        }
    }

    pub(crate) fn has_legacy_data(&self) -> Result<bool, String> {
        self.with_legacy_roots(crate::legacy_import::has_legacy_data)
    }

    pub fn scan_legacy(&self) -> Result<crate::legacy_import::LegacySnapshot, String> {
        self.with_legacy_roots(crate::legacy_import::scan_legacy_with_roots)
    }

    fn with_legacy_roots<T>(
        &self,
        read: impl FnOnce(&Path, Option<&Path>, Option<&Path>) -> Result<T, String>,
    ) -> Result<T, String> {
        #[cfg(target_os = "android")]
        if let Some(paths) = crate::platform::android::paths() {
            if paths.package_name == "com.suixing.agent"
                && self.namespace_root != self.legacy_source_root
            {
                let files = fs::canonicalize(&paths.files_dir)
                    .map_err(|_| "无法确认旧应用的内部数据目录归属")?;
                if files == self.legacy_source_root {
                    return read(
                        &self.legacy_source_root,
                        Some(&paths.no_backup_dir),
                        paths.external_files_dir.as_deref(),
                    );
                }
            }
        }
        read(&self.legacy_source_root, None, None)
    }

    pub fn import_legacy(&mut self, expected: &str) -> Result<LegacyImportReport, String> {
        self.writable()?;
        if expected.is_empty() {
            return Err("请先预览旧数据清单".into());
        }
        let snapshot = self.scan_legacy()?;
        if legacy_migration::snapshot_fingerprint(&snapshot)? != expected {
            return Err("旧源已经改变，请重新预览后迁移".into());
        }
        if let Some(receipt) = legacy_migration::already_committed(&self.namespace_root, expected)?
        {
            return Ok(LegacyImportReport {
                transaction_id: receipt.id,
                conversations: snapshot.conversations.len(),
                source_fingerprint: receipt.source_fingerprint,
                durability_confirmed: receipt.durability_confirmed,
                source_stable_after_commit: receipt.source_stable_after_commit,
                warnings: receipt.warnings,
                retained_features: Vec::new(),
            });
        }
        let mut plan = legacy_migration::prepare(&snapshot, &SecretBox)?;
        // Register incoming native references before an authoritative pointer can
        // expose them. Original archive images are never collection candidates.
        crate::avatars::retain(
            &self.namespace_root,
            plan.settings
                .iter()
                .flat_map(|settings| settings.settings.assistants.iter())
                .map(|assistant| assistant.avatar_path.as_str()),
        )?;
        crate::avatars::retain(
            &self.namespace_root,
            plan.children
                .iter()
                .filter_map(|child| child.record.config.as_ref())
                .map(|config| config.assistant_avatar_path.as_str()),
        )?;
        let current_plugins = crate::plugin_assets::scan_tree(
            &self.root,
            Path::new("plugins"),
            true,
            &crate::network::CancelToken::new(),
        )?;
        plan.plugin_layout =
            crate::plugin_assets::merge_layout(&current_plugins.layout, &plan.plugin_layout)?;
        let owners = plan
            .conversations
            .iter()
            .map(|chat| (chat.conversation.id.clone(), chat.conversation.id.clone()))
            .collect();
        let processes = crate::legacy_processes::prepare(&snapshot, &owners)?;
        plan.warnings.extend(processes.warnings.clone());
        if !processes.jobs.is_empty() || !processes.terminals.is_empty() {
            plan.retained_features.push(format!(
                "可读取旧作业/终端归档：{} / {}条；未知进程状态保未知，不恢复PID或自动执行",
                processes.jobs.len(),
                processes.terminals.len()
            ));
        }
        let current_proof = self.current_migration_proof()?;
        let imported_profile = crate::profile::from_legacy(&snapshot, expected)?;
        let files = self.merged_generation(&plan, processes, &imported_profile)?;
        let staged = legacy_migration::stage(&self.namespace_root, &snapshot, &plan, &files)?;
        // Store::open validates every authoritative document and segment. It
        // changes recovery status only in memory and never rewrites source data.
        let candidate = Store::open(staged.state_root.clone())?;
        candidate.open_profile_repository()?;
        candidate.unconsumed_profile_turns(0)?;
        for child in &plan.children {
            candidate.conversation(&child.parent_chat)?;
            validate_messages(&child.record.messages)?;
            validate_saved_child_config(child.record.config.as_ref())?;
        }
        // Namespace assets and their ordinary permissions must exist before the
        // authoritative state pointer can expose their recorded aliases.
        crate::store::workspace::publish_workspace(&self.private_workspace(), &plan, &SecretBox)?;
        let namespace = self.namespace_root.clone();
        let legacy_root = self.legacy_source_root.clone();
        if self.current_migration_proof()? != current_proof {
            return Err("当前Rust状态在准备迁移期间改变；准备代保留，活动状态未切换".into());
        }
        let receipt = legacy_migration::commit(&namespace, &snapshot, &staged)?;
        // The pointer is already published: every subsequent error must state
        // that fact and prevent an older actor snapshot from writing over it.
        let mut next = match Store::open_with_shared_files(
            namespace,
            self.shared_files_root.clone(),
        ) {
            Ok(next) => next,
            Err(error) => {
                let message = format!(
                    "迁移指针已经切换，但重载新状态失败：{error}；已暂停写入，请重新打开应用核对"
                );
                self.write_blocked = Some(message.clone());
                return Err(message);
            }
        };
        next.set_legacy_source_root(legacy_root);
        next.jobs = self.jobs.clone();
        if !receipt.durability_confirmed || !receipt.source_stable_after_commit {
            next.write_blocked = Some(
                "迁移已经发布，但持久化或旧源稳定性尚未确认；请重新打开应用核对后再修改".into(),
            );
        }
        let report = LegacyImportReport {
            transaction_id: receipt.id,
            conversations: plan.conversations.len(),
            source_fingerprint: receipt.source_fingerprint,
            durability_confirmed: receipt.durability_confirmed,
            source_stable_after_commit: receipt.source_stable_after_commit,
            warnings: receipt.warnings,
            retained_features: plan.retained_features,
        };
        *self = next;
        Ok(report)
    }

    fn merged_generation(
        &self,
        plan: &MigrationPlan,
        processes: crate::legacy_processes::Draft,
        imported_profile: &crate::profile::Document,
    ) -> Result<Vec<GenerationFile>, String> {
        let mut files = BTreeMap::new();
        snapshot_files(&self.root, &self.root, &mut files, &mut 0, 0)?;
        if !plan.reading_positions.is_empty() {
            let mut positions = self.reading_positions.clone();
            let mut sources = positions
                .iter()
                .map(|position| position.source.clone())
                .collect::<HashSet<_>>();
            for imported in &plan.reading_positions {
                if sources.insert(imported.source.clone()) {
                    positions.push(imported.clone());
                }
            }
            reading::validate(&positions)?;
            put_json(
                &mut files,
                "reading-positions.json",
                &reading::ReadingDocument { positions },
            )?;
        }
        for plugin in &plan.plugin_files {
            put_new(
                &mut files,
                GenerationFile {
                    path: plugin.path.clone(),
                    bytes: plugin.bytes.clone(),
                },
            )?;
        }
        if !imported_profile.sources.is_empty() {
            crate::profile::encode(imported_profile)?;
            if let Some(previous) = optional_json::<crate::profile::Document>(
                &self.root,
                &self.root.join("profile.json"),
                256 * 1024,
            )? {
                equal_record(&previous, imported_profile, "画像", "profile")?;
            } else {
                put_json(&mut files, "profile.json", imported_profile)?;
            }
        }
        for imported in &plan.conversations {
            let id = &imported.conversation.id;
            let document = ChatDocument {
                version: VERSION,
                conversation: imported.conversation.clone(),
                messages: imported.messages.clone(),
                queued_turns: imported.queued_turns.clone(),
            };
            validate_chat(&document, id)?;
            if self.conversations.iter().any(|item| &item.id == id) {
                let previous = self.read_chat(id)?;
                equal_record(&previous, &document, "会话", id)?;
                continue;
            }
            for file in legacy_migration::encode_chat_archive(
                &document.conversation,
                &document.messages,
                &document.queued_turns,
            )? {
                put_new(&mut files, file)?;
            }
            if let Some(recovery) = &imported.recovery {
                put_json(&mut files, format!("legacy-recovery/{id}.json"), recovery)?;
            }
        }
        if let Some(imported) = &plan.settings {
            validate_settings(&imported.settings)?;
            let document = SettingsDocument {
                version: VERSION,
                settings: imported.settings.clone(),
                secrets: imported.secrets.clone(),
            };
            if self.root.join("settings.json").exists() {
                equal_record(
                    &SettingsDocument {
                        version: VERSION,
                        settings: self.settings.clone(),
                        secrets: self.settings_secrets.clone(),
                    },
                    &document,
                    "设置",
                    "settings",
                )?;
            } else {
                put_json(&mut files, "settings.json", &document)?;
            }
        }
        if self.shared_files_root.is_none() {
            let mut memories = self.memories.clone();
            for memory in &plan.memories {
                if let Some(old) = memories.iter().find(|old| old.id == memory.id) {
                    equal_record(old, memory, "记忆", &memory.id)?;
                } else {
                    memories.push(memory.clone());
                }
            }
            validate_memories(&memories)?;
            put_json(
                &mut files,
                "memories.json",
                &MemoryDocument {
                    version: VERSION,
                    memories,
                },
            )?;
        }
        let mut skills = self.skills.clone();
        for skill in &plan.skills {
            if let Some(old) = skills.iter().find(|old| old.id == skill.id) {
                equal_record(old, skill, "技能", &skill.id)?;
            } else {
                skills.push(skill.clone());
            }
        }
        crate::skills::validate_all(&skills)?;
        let revision_clock = self
            .skills_revision
            .max(skills.iter().map(|s| s.revision).max().unwrap_or(0));
        put_json(
            &mut files,
            "skills.json",
            &SkillDocument {
                version: VERSION,
                skills,
                revision_clock,
            },
        )?;
        let mut schedules = self.schedules.clone();
        for schedule in &plan.schedules {
            if let Some(old) = schedules.iter().find(|old| old.id == schedule.id) {
                equal_record(old, schedule, "计划", &schedule.id)?;
            } else {
                schedules.push(schedule.clone());
            }
        }
        crate::schedules::validate_board(&schedules)?;
        put_json(
            &mut files,
            "schedules.json",
            &ScheduleDocument {
                version: VERSION,
                schedules,
                execution: self.schedule_execution.clone(),
            },
        )?;
        if let Some(goal) = &plan.goal {
            if !self.goal.text.is_empty() {
                equal_record(&self.goal, goal, "目标", &goal.id)?;
            } else {
                put_json(
                    &mut files,
                    "goal.json",
                    &GoalDocument {
                        version: VERSION,
                        goal: goal.clone(),
                    },
                )?;
            }
        }
        let mut servers = self.mcp_servers.clone();
        let mut secrets = self.mcp_secrets.clone();
        for imported in &plan.mcp {
            if let Some(old) = servers.iter().find(|old| old.id == imported.config.id) {
                equal_record(old, &imported.config, "MCP", &imported.config.id)?;
                if let Some(old_secrets) = secrets.get(&imported.config.id) {
                    equal_record(
                        old_secrets,
                        &imported.secrets,
                        "MCP凭据",
                        &imported.config.id,
                    )?;
                }
            } else {
                servers.push(imported.config.clone());
                secrets.insert(imported.config.id.clone(), imported.secrets.clone());
            }
        }
        let servers = crate::mcp::normalize_configs(servers)?;
        put_json(
            &mut files,
            "mcp.json",
            &McpDocument {
                version: VERSION,
                servers,
                secrets,
            },
        )?;
        for child in &plan.children {
            validate_id(&child.parent_chat)?;
            validate_id(&child.parent_run)?;
            validate_id(&child.record.id)?;
            validate_messages(&child.record.messages)?;
            let document = ChildDocument {
                version: VERSION,
                parent_chat: child.parent_chat.clone(),
                parent_run: child.parent_run.clone(),
                record: child.record.clone(),
            };
            let path = PathBuf::from(format!(
                "subagents/{}/{}.json",
                child.parent_chat, child.record.id
            ));
            let bytes = serde_json::to_vec(&document).map_err(|_| "子任务迁移编码失败")?;
            if bytes.len() as u64 > MAX_CHILD_BYTES {
                return Err("旧子任务超过当前独立存储预算，未部分迁移".into());
            }
            put_new(&mut files, GenerationFile { path, bytes })?;
        }
        // Legacy aggregate usage has no one-to-one request identity. Keep it
        // separate from the current request ledger to avoid invented requests.
        if let Some(imported) = &plan.conversation_preferences {
            let current_ids = self
                .conversations
                .iter()
                .map(|conversation| conversation.id.clone())
                .collect::<HashSet<_>>();
            let mut known = current_ids.clone();
            known.extend(
                plan.conversations
                    .iter()
                    .map(|chat| chat.conversation.id.clone()),
            );
            let preferences = crate::conversation_preferences::merge(
                &self.conversation_preferences,
                imported,
                &current_ids,
                &known,
            )?;
            put_json(&mut files, "conversation-preferences.json", &preferences)?;
        }
        put_new_json(&mut files, "legacy-usage.json", &plan.usage)?;
        put_new_json(&mut files, "legacy-todos.json", &plan.todos)?;
        if let Some(imported) = &plan.performance {
            let current = self.performance_document()?;
            let mut document = crate::performance::Document::new();
            document.extra = current.extra;
            for record in imported.records.iter().chain(current.records.iter()) {
                document.append(record.clone())?;
            }
            document.validate()?;
            put_json(&mut files, "perf.json", &document)?;
        }
        let mut items = self.todos.items.clone();
        let mut imported: Vec<_> = plan
            .todos
            .iter()
            .map(|todo| crate::todos::Item {
                id: todo.id.clone(),
                subject: todo.text.clone(),
                status: todo.status.clone(),
                extra: todo.extra.clone(),
            })
            .collect();
        crate::todos::normalize(&mut imported)?;
        let mut changed = false;
        for item in imported {
            if let Some(previous) = items.iter().find(|p| p.id == item.id) {
                equal_record(previous, &item, "任务清单", &item.id)?;
            } else {
                items.push(item);
                changed = true;
            }
        }
        let board = crate::todos::Board {
            version: 1,
            revision: if changed {
                self.todos
                    .revision
                    .checked_add(1)
                    .ok_or("迁移任务清单版本已耗尽")?
            } else {
                self.todos.revision
            },
            items,
        };
        crate::todos::validate(&board)?;
        put_json(&mut files, "todos.json", &board)?;
        let mut mappings = self.workspace_mappings.clone();
        for asset in plan.assets.iter().filter(|a| a.workspace_path.is_some()) {
            if let Some(previous) = mappings.assets.iter().find(|a| {
                a.owning_root == asset.owning_root && a.relative_path == asset.relative_path
            }) {
                equal_record(previous, asset, "工作区文件映射", &asset.relative_path)?;
            } else {
                mappings.assets.push(asset.clone());
            }
        }
        for directory in &plan.workspace_directories {
            if let Some(previous) = mappings.directories.iter().find(|d| {
                d.owning_root == directory.owning_root && d.relative_path == directory.relative_path
            }) {
                equal_record(
                    previous,
                    directory,
                    "工作区目录映射",
                    &directory.relative_path,
                )?;
            } else {
                mappings.directories.push(directory.clone());
            }
        }
        mappings.validate()?;
        put_json(&mut files, "workspace-mappings.json", &mappings)?;
        let ids = crate::legacy_processes::CurrentIds {
            jobs: self
                .jobs()
                .map(|jobs| jobs.list().into_iter().map(|job| job.key.id).collect())
                .unwrap_or_default(),
            // Native terminals live in the fixed namespace outside this candidate;
            // imported archive IDs are source-hashed l<64hex>, never t<session> IDs.
            terminals: Default::default(),
        };
        crate::legacy_processes::merge_into(&mut files, processes, &ids)?;
        Ok(files
            .into_iter()
            .map(|(path, bytes)| GenerationFile { path, bytes })
            .collect())
    }

    fn current_migration_proof(&self) -> Result<BTreeMap<PathBuf, String>, String> {
        let mut files = BTreeMap::new();
        snapshot_files(&self.root, &self.root, &mut files, &mut 0, 0)?;
        let plugin_layout = crate::plugin_assets::scan_tree(
            &self.root,
            Path::new("plugins"),
            true,
            &crate::network::CancelToken::new(),
        )?
        .layout;
        files.insert(
            PathBuf::from(".plugin-layout-proof"),
            serde_json::to_vec(&plugin_layout).map_err(|_| "插件布局迁移证明编码失败")?,
        );
        Ok(files
            .into_iter()
            .map(|(path, bytes)| (path, legacy_migration::digest(&bytes)))
            .collect())
    }
}

fn equal_record(
    first: &impl Serialize,
    second: &impl Serialize,
    label: &str,
    id: &str,
) -> Result<(), String> {
    if serde_json::to_value(first).map_err(|_| "迁移冲突核对编码失败")?
        != serde_json::to_value(second).map_err(|_| "迁移冲突核对编码失败")?
    {
        return Err(format!(
            "旧{label}与当前记录ID {id} 冲突；未替换，请先处理冲突"
        ));
    }
    Ok(())
}
fn put_json(
    files: &mut BTreeMap<PathBuf, Vec<u8>>,
    path: impl Into<PathBuf>,
    value: &impl Serialize,
) -> Result<(), String> {
    let bytes = serde_json::to_vec(value).map_err(|_| "迁移目标文档编码失败")?;
    files.insert(path.into(), bytes);
    Ok(())
}
fn put_new_json(
    files: &mut BTreeMap<PathBuf, Vec<u8>>,
    path: impl Into<PathBuf>,
    value: &impl Serialize,
) -> Result<(), String> {
    put_new(
        files,
        GenerationFile {
            path: path.into(),
            bytes: serde_json::to_vec(value).map_err(|_| "迁移文档编码失败")?,
        },
    )
}
fn put_new(files: &mut BTreeMap<PathBuf, Vec<u8>>, file: GenerationFile) -> Result<(), String> {
    if let Some(previous) = files.get(&file.path) {
        if previous != &file.bytes {
            return Err("迁移目标文件与当前数据冲突，未覆盖".into());
        }
    } else {
        files.insert(file.path, file.bytes);
    }
    Ok(())
}
fn snapshot_files(
    root: &Path,
    directory: &Path,
    files: &mut BTreeMap<PathBuf, Vec<u8>>,
    total: &mut u64,
    depth: usize,
) -> Result<(), String> {
    if depth > 32 {
        return Err("当前状态目录嵌套超过迁移预算".into());
    }
    for entry in fs::read_dir(directory).map_err(|_| "无法读取当前状态迁移清单")? {
        let entry = entry.map_err(|_| "当前状态目录项读取失败")?;
        let path = entry.path();
        let relative = path.strip_prefix(root).map_err(|_| "当前迁移路径越界")?;
        if relative.starts_with("workspace")
            || relative.starts_with("migrations")
            || relative == Path::new("active-generation.json")
        {
            continue;
        }
        let name = entry.file_name();
        let name = name.to_str().ok_or("当前状态文件名不是UTF-8")?;
        if (!relative.starts_with("plugins") && is_temporary_name(name))
            || name.starts_with(".active-") && name.ends_with(".tmp")
            || relative.parent() == Some(Path::new("plugins"))
                && (name.starts_with(".install-") || name.starts_with(".staging-"))
        {
            continue;
        }
        let metadata = fs::symlink_metadata(&path).map_err(|_| "无法检查当前状态迁移文件")?;
        if metadata.file_type().is_symlink() {
            return Err("当前状态包含链接，未自动跟随迁移".into());
        }
        if metadata.is_dir() {
            snapshot_files(root, &path, files, total, depth + 1)?;
        } else if metadata.is_file() {
            if files.len() >= 131_072 || metadata.len() > 512 * 1024 * 1024 {
                return Err("当前迁移状态超过单文件或数量预算".into());
            }
            *total = total
                .checked_add(metadata.len())
                .ok_or("当前状态迁移长度溢出")?;
            if *total > 512 * 1024 * 1024 {
                return Err("当前状态超过512 MiB迁移快照预算，拒绝部分迁移".into());
            }
            let mut opened = checked_file(root, &path)?;
            let mut bytes = Vec::new();
            Read::by_ref(&mut opened)
                .take(metadata.len() + 1)
                .read_to_end(&mut bytes)
                .map_err(|_| "当前迁移状态读取失败")?;
            let after = fs::symlink_metadata(&path).map_err(|_| "当前状态迁移文件已移动")?;
            if bytes.len() as u64 != metadata.len()
                || !same_file(&metadata, &after)
                || metadata.modified().ok() != after.modified().ok()
            {
                return Err("当前状态在快照期间改变，未迁移".into());
            }
            files.insert(relative.to_path_buf(), bytes);
        } else {
            return Err("当前状态包含特殊文件，未自动迁移".into());
        }
    }
    Ok(())
}
