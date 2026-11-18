//! The workspace's manually managed skills are references to user files.
use super::{Control, DOCUMENT_BYTES, Skill, TOTAL_BYTES, bounded_file, files};
use std::{
    collections::BTreeSet,
    fs,
    path::{Path, PathBuf},
};

const SOURCE: &str = "workspace_file";
const MAX_SCAN_ENTRIES: usize = 4096;
const MAX_SKILLS: usize = 256;

pub(crate) fn is_live_workspace_skill(skill: &Skill) -> bool {
    skill.source == SOURCE && skill.edited_document_path.is_empty()
}

fn read_document(
    workspace: &Path,
    relative: &Path,
    id: String,
    limit: u64,
    control: &Control,
) -> Result<(Skill, usize), String> {
    files::relative(relative)?;
    if !relative.starts_with("skills") {
        return Err("工作区技能文档超出 skills/ 目录".into());
    }
    let path = workspace.join(relative);
    if path
        .canonicalize()
        .map_err(|_| "工作区技能文档已移动或不存在")?
        != path
    {
        return Err("工作区技能文档含重定向，未读取".into());
    }
    let bytes = bounded_file(&path, limit.min(DOCUMENT_BYTES as u64), control)?;
    let len = bytes.len();
    let raw = String::from_utf8(bytes).map_err(|_| "工作区技能文档不是 UTF-8")?;
    let mut skill = crate::skills::import_document(id, raw)?;
    skill.source = SOURCE.into();
    skill.document_path = relative.to_str().ok_or("技能文档路径不是 UTF-8")?.into();
    skill.files_path = relative
        .parent()
        .and_then(Path::to_str)
        .ok_or("技能资源目录缺失")?
        .into();
    skill.validate()?;
    Ok((skill, len))
}

pub(crate) fn verify_workspace_document(workspace: &Path, expected: &Skill) -> Result<(), String> {
    if !is_live_workspace_skill(expected) {
        return Ok(());
    }
    let current = read_document(
        workspace,
        Path::new(&expected.document_path),
        expected.id.clone(),
        DOCUMENT_BYTES as u64,
        &Control::new(),
    );
    if !current.is_ok_and(|(skill, _)| {
        skill.name == expected.name
            && skill.description == expected.description
            && skill.content == expected.content
    }) {
        return Err("工作区技能文档已变化或无法读取；请重扫并审阅后再启用".into());
    }
    Ok(())
}

fn entries(
    directory: &Path,
    remaining: &mut usize,
    control: &Control,
) -> Result<Vec<fs::DirEntry>, String> {
    files::checked_root(directory)?;
    let mut children = Vec::new();
    for entry in fs::read_dir(directory).map_err(|_| "工作区技能目录无法读取")? {
        control.check()?;
        *remaining = remaining
            .checked_sub(1)
            .ok_or("工作区技能目录超过 4096 个扫描项")?;
        children.push(entry.map_err(|_| "工作区技能目录项无法读取")?);
    }
    children.sort_by_key(fs::DirEntry::file_name);
    Ok(children)
}

fn is_markdown(path: &Path) -> bool {
    path.extension()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name.eq_ignore_ascii_case("md"))
}

fn skill_entry(
    folder: &Path,
    remaining: &mut usize,
    control: &Control,
) -> Result<Option<PathBuf>, String> {
    let mut selected = None;
    let mut rank = usize::MAX;
    for entry in entries(folder, remaining, control)? {
        let name = entry.file_name();
        let Some(name) = name.to_str() else { continue };
        if !name.eq_ignore_ascii_case("SKILL.md") {
            continue;
        }
        if !entry
            .file_type()
            .map_err(|_| "技能入口状态无法确认")?
            .is_file()
        {
            continue;
        }
        let priority = ["SKILL.md", "skill.md", "Skill.md"]
            .iter()
            .position(|candidate| *candidate == name)
            .unwrap_or(3);
        if priority < rank {
            rank = priority;
            selected = Some(entry.path());
        }
    }
    Ok(selected)
}

pub(super) fn scan(workspace: &Path, control: &Control) -> Result<Vec<Skill>, String> {
    let root = workspace.join("skills");
    match fs::symlink_metadata(&root) {
        Ok(metadata) if metadata.is_dir() && !metadata.file_type().is_symlink() => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        _ => return Err("工作区 skills/ 不是可安全读取的目录".into()),
    }
    let mut remaining_entries = MAX_SCAN_ENTRIES;
    let mut remaining_bytes = TOTAL_BYTES;
    let mut results = Vec::new();
    let mut ids = BTreeSet::new();
    for entry in entries(&root, &mut remaining_entries, control)? {
        let name = entry.file_name();
        let kind = entry
            .file_type()
            .map_err(|_| "工作区技能入口状态无法确认")?;
        let path = entry.path();
        let (document, id) = if kind.is_dir() {
            let Some(document) = skill_entry(&path, &mut remaining_entries, control)? else {
                continue;
            };
            (
                document,
                name.to_str().ok_or("技能目录名不是 UTF-8")?.to_owned(),
            )
        } else if kind.is_file() && is_markdown(&path) {
            let id = path
                .file_stem()
                .and_then(|name| name.to_str())
                .ok_or("技能文件名不是 UTF-8")?
                .to_owned();
            (path, id)
        } else {
            continue;
        };
        let id = crate::skills::sanitize_import_id(&id);
        if !ids.insert(id.clone()) {
            return Err(format!("工作区技能 ID {id} 重复，请调整冲突文件或目录名称"));
        }
        if results.len() >= MAX_SKILLS {
            return Err("工作区技能超过 256 份，未应用部分扫描结果".into());
        }
        let relative = document
            .strip_prefix(workspace)
            .map_err(|_| "技能文档越出工作区")?;
        let (skill, bytes) = read_document(workspace, relative, id, remaining_bytes, control)
            .map_err(|error| format!("{}：{error}", relative.display()))?;
        remaining_bytes -= bytes as u64;
        results.push(skill);
    }
    Ok(results)
}
