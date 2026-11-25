use super::*;
use crate::skills::Skill;
use std::collections::BTreeSet;

const STATE: &str = "skill-state.json";
const ORIGINS: &str = "skill-origin.json";
const STATE_BYTES: u64 = 4 * 1024 * 1024;
const ENTRIES: [&str; 3] = ["SKILL.md", "skill.md", "Skill.md"];

struct SharedState {
    disabled: Option<BTreeSet<String>>,
    origins: BTreeMap<String, serde_json::Value>,
}

impl SharedState {
    fn read(files: &Path) -> Self {
        Self {
            // Missing means enabled by default. An unreadable file must not enable skills.
            disabled: optional_json(files, &files.join(STATE), STATE_BYTES)
                .ok()
                .map(Option::unwrap_or_default),
            origins: optional_json(files, &files.join(ORIGINS), STATE_BYTES)
                .ok()
                .flatten()
                .unwrap_or_default(),
        }
    }

    fn set_enabled(&mut self, id: &str, enabled: bool, known: &[Skill]) {
        let disabled = self
            .disabled
            .get_or_insert_with(|| known.iter().map(|skill| skill.id.clone()).collect());
        if enabled {
            disabled.remove(id);
        } else {
            disabled.insert(id.to_owned());
        }
    }

    fn record_origin(&mut self, skill: &Skill) {
        self.origins.entry(skill.id.clone()).or_insert_with(|| {
            serde_json::json!({
                "source": compose_source(&skill.source),
                "url": skill.source_url,
                "at": skill.updated_at,
            })
        });
    }
}

fn compose_source(source: &str) -> &str {
    match source.trim().to_ascii_lowercase().as_str() {
        "downloaded" => "downloaded",
        "builtin" => "builtin",
        _ => "user",
    }
}

fn document_id(relative: &Path) -> Option<&str> {
    let name = relative.file_name()?.to_str()?;
    if name.eq_ignore_ascii_case("SKILL.md") {
        relative.parent()?.file_name()?.to_str()
    } else {
        relative.file_stem()?.to_str()
    }
}

fn read_document(workspace: &Path, path: &Path, id: &str) -> Result<Skill, String> {
    let relative = path
        .strip_prefix(workspace)
        .map_err(|_| "技能文档超出工作区")?;
    crate::plugin_assets::relative(relative)?;
    if contained_path(workspace, path)? != path {
        return Err("技能文档含重定向".into());
    }
    let maximum = crate::skill_import::DOCUMENT_BYTES as u64;
    let mut bytes = Vec::new();
    checked_file(workspace, path)?
        .take(maximum + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| fs_error("读取技能文档", path, error))?;
    let mut skill = crate::skills::import_document(
        id.to_owned(),
        String::from_utf8_lossy(&bytes).into_owned(),
    )?;
    skill.document_path = relative.to_string_lossy().into_owned();
    skill.files_path = relative
        .parent()
        .ok_or("技能资源目录缺失")?
        .to_string_lossy()
        .into_owned();
    Ok(skill)
}

pub(super) fn read(files: &Path, workspace: &Path) -> Result<Vec<Skill>, String> {
    let root = workspace.join("skills");
    let mut paths = match fs::read_dir(&root) {
        Ok(entries) => entries.collect::<Result<Vec<_>, _>>(),
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(fs_error("读取技能目录", &root, error)),
    }
    .map_err(|error| fs_error("读取技能目录", &root, error))?;
    if contained_path(workspace, &root)? != root {
        return Err("技能目录含重定向".into());
    }
    paths.sort_by_key(|entry| entry.file_name().to_string_lossy().to_lowercase());
    let state = SharedState::read(files);
    let mut skills = Vec::new();
    let mut ids = HashSet::new();
    for entry in paths {
        let kind = entry
            .file_type()
            .map_err(|error| fs_error("读取技能目录项", &entry.path(), error))?;
        let path = if kind.is_dir() {
            let Some(path) = ENTRIES
                .iter()
                .map(|name| entry.path().join(name))
                .find(|path| path.is_file())
            else {
                continue;
            };
            path
        } else if kind.is_file()
            && entry
                .path()
                .extension()
                .is_some_and(|extension| extension.eq_ignore_ascii_case("md"))
        {
            entry.path()
        } else {
            continue;
        };
        let Some(id) = document_id(&path) else {
            continue;
        };
        let Ok(mut skill) = read_document(workspace, &path, id) else {
            continue;
        };
        if !ids.insert(skill.id.clone()) {
            continue;
        }
        let origin = state.origins.get(id);
        skill.enabled = state
            .disabled
            .as_ref()
            .is_some_and(|disabled| !disabled.contains(id));
        skill.source = compose_source(
            origin
                .and_then(|value| value["source"].as_str())
                .unwrap_or_default(),
        )
        .into();
        skill.source_url = origin
            .and_then(|value| value["url"].as_str())
            .unwrap_or_default()
            .to_owned();
        skill.updated_at = origin
            .and_then(|value| value["at"].as_i64())
            .unwrap_or_default()
            .max(0);
        skills.push(skill);
    }
    Ok(skills)
}

fn same_document(old: &Skill, skill: &Skill) -> bool {
    old.name == skill.name && old.description == skill.description && old.content == skill.content
}

fn compose_document(skill: &Skill) -> Vec<u8> {
    // Compose parses flat frontmatter and only treats the first line as metadata.
    let name = skill.name.lines().next().unwrap_or_default().trim();
    let description = skill.description.lines().next().unwrap_or_default().trim();
    format!(
        "---\nname: {name}\ndescription: {description}\n---\n\n{}",
        skill.content
    )
    .into_bytes()
}

fn new_document(id: &str) -> PathBuf {
    Path::new("skills").join(id).join("SKILL.md")
}

pub(super) fn is_common(skill: &Skill) -> bool {
    Path::new(&skill.document_path).starts_with("skills") && skill.edited_document_path.is_empty()
}

impl Store {
    fn write_shared_skill_state(
        &mut self,
        files: &Path,
        state: &SharedState,
    ) -> Result<(), String> {
        if let Some(disabled) = &state.disabled {
            let commit = atomic_json(files, &files.join(STATE), disabled, STATE_BYTES);
            self.finish_shared_skill_write(commit)?;
        }
        if !state.origins.is_empty() || files.join(ORIGINS).is_file() {
            let commit = atomic_json(files, &files.join(ORIGINS), &state.origins, STATE_BYTES);
            self.finish_shared_skill_write(commit)?;
        }
        Ok(())
    }

    fn finish_shared_skill_write(
        &mut self,
        result: Result<(), CommitFailure>,
    ) -> Result<(), String> {
        result.map_err(|failure| {
            if failure.replaced {
                self.write_blocked = Some(failure.message.clone());
            }
            failure.message
        })
    }

    pub(super) fn verify_shared_skill(&self, skill: &Skill) -> Result<(), String> {
        if !read_document(
            &self.workspace,
            &self.workspace.join(&skill.document_path),
            &skill.id,
        )
        .is_ok_and(|current| same_document(skill, &current))
        {
            return Err("技能文档在工作区中已变更，请重扫技能列表".into());
        }
        Ok(())
    }

    pub(super) fn refresh_shared_skills(&mut self, files: &Path) -> Result<String, String> {
        let mut skills = read(files, &self.workspace)?;
        let mut revision = self.skills_revision;
        for skill in &mut skills {
            if let Some(old) = self.skills.iter().find(|old| {
                old.id == skill.id
                    && old.enabled == skill.enabled
                    && old.document_path == skill.document_path
                    && old.source == skill.source
                    && old.source_url == skill.source_url
                    && same_document(old, skill)
            }) {
                skill.revision = old.revision;
            } else {
                revision = revision.checked_add(1).ok_or("技能版本已耗尽")?;
                skill.revision = revision;
            }
        }
        let private: Vec<_> = self
            .skills
            .iter()
            .filter(|old| !is_common(old) && !skills.iter().any(|shared| shared.id == old.id))
            .cloned()
            .collect();
        skills.extend(private);
        crate::skills::validate_all(&skills)?;
        self.skills_revision = revision;
        self.skills = skills;
        crate::skill_import::metadata_changed();
        Ok(format!("工作区技能已刷新（{} 份）。", self.skills.len()))
    }

    pub(super) fn save_shared_skill(
        &mut self,
        files: &Path,
        mut skill: Skill,
        raw: Option<&str>,
        existing: Option<usize>,
    ) -> Result<Skill, String> {
        let mut state = SharedState::read(files);
        let relative = if let Some(index) = existing {
            let previous = &self.skills[index];
            self.verify_shared_skill(previous)?;
            PathBuf::from(&previous.document_path)
        } else {
            new_document(&skill.id)
        };
        let target = self.workspace.join(&relative);
        let modified = existing.is_none_or(|index| !same_document(&self.skills[index], &skill));
        if modified {
            let bytes = raw
                .map(|raw| raw.as_bytes().to_vec())
                .unwrap_or_else(|| compose_document(&skill));
            let parsed = crate::skills::import_document(
                skill.id.clone(),
                String::from_utf8_lossy(&bytes).into_owned(),
            )?;
            skill.name = parsed.name;
            skill.description = parsed.description;
            skill.content = parsed.content;
            let root = owned_directory(&self.workspace, "skills")?;
            if existing.is_none() {
                let folder = root.join(&skill.id);
                if !folder.exists() {
                    owned_directory(&root, &skill.id)?;
                }
                if target.exists()
                    || ENTRIES.iter().any(|name| folder.join(name).is_file())
                    || root.join(format!("{}.md", skill.id)).is_file()
                {
                    return Err("技能文件已存在，请重扫技能列表".into());
                }
            }
            // Publish new documents only after registering their requested switch state.
            if existing.is_none() {
                state.set_enabled(&skill.id, skill.enabled, &self.skills);
                state.record_origin(&skill);
                self.write_shared_skill_state(files, &state)?;
            }
            let commit = atomic_bytes(&self.workspace, &target, &bytes);
            self.finish_shared_skill_write(commit)?;
        }
        if existing.is_some() {
            state.set_enabled(&skill.id, skill.enabled, &self.skills);
            self.write_shared_skill_state(files, &state)?;
        }
        skill.document_path = relative.to_string_lossy().into_owned();
        skill.files_path = relative
            .parent()
            .ok_or("技能资源目录缺失")?
            .to_string_lossy()
            .into_owned();
        skill.edited_document_path.clear();
        if let Some(origin) = state.origins.get(&skill.id) {
            skill.updated_at = origin["at"].as_i64().unwrap_or_default().max(0);
        }
        skill.source = compose_source(&skill.source).into();
        if let Some(index) = existing {
            self.skills[index] = skill.clone();
        } else {
            self.skills.push(skill.clone());
        }
        self.skills_revision = skill.revision;
        crate::skill_import::metadata_changed();
        Ok(skill)
    }

    pub(super) fn delete_shared_skill(
        &mut self,
        files: &Path,
        index: usize,
    ) -> Result<Option<String>, String> {
        let skill = &self.skills[index];
        self.verify_shared_skill(skill)?;
        let relative = Path::new(&skill.document_path);
        let target = self.workspace.join(relative);
        let folder = relative.components().count() == 3;
        let removed = if folder {
            fs::remove_dir_all(target.parent().ok_or("技能目录缺失")?)
        } else {
            fs::remove_file(&target)
        };
        removed.map_err(|error| fs_error("删除技能", &target, error))?;
        let skill = self.skills.remove(index);
        crate::skill_import::metadata_changed();
        let mut state = SharedState::read(files);
        if let Some(disabled) = &mut state.disabled {
            disabled.remove(&skill.id);
        }
        state.origins.remove(&skill.id);
        Ok(self.write_shared_skill_state(files, &state).err())
    }
}
