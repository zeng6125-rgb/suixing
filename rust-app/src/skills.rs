//! Skill documents are user-controlled instructions. Only enabled catalogue entries are
//! resident in prompts; bodies require an approved skill_read call.
use crate::state::ToolCall;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::HashSet;
use suixing_search::rust_protocol::ToolDefinition;

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Skill {
    pub id: String,
    pub name: String,
    pub description: String,
    pub content: String,
    pub enabled: bool,
    pub source: String,
    pub revision: u64,
    pub updated_at: i64,
    /// Immutable imported original and companion files, relative to workspace.
    pub document_path: String,
    pub files_path: String,
    pub source_url: String,
    /// Authored edits are separate files; imported originals/resources remain intact.
    pub edited_document_path: String,
}

impl Skill {
    pub fn validate(&self) -> Result<(), String> {
        if !valid_id(&self.id) {
            return Err(
                "技能ID须以字母或数字开头，只包含字母、数字、点、下划线与短横线，最多96字节".into(),
            );
        }
        for (value, max) in [
            (&self.name, 256),
            (&self.description, 2048),
            (&self.content, 2 * 1024 * 1024),
            (&self.source, 2048),
            (&self.document_path, 4096),
            (&self.files_path, 4096),
            (&self.source_url, 4096),
            (&self.edited_document_path, 4096),
        ] {
            if value.len() > max || value.contains('\0') {
                return Err("技能字段超过预算或包含NUL，正文最多2 MiB".into());
            }
        }
        if self.name.trim().is_empty() || self.content.trim().is_empty() || self.updated_at < 0 {
            return Err("技能名称、正文或更新时间无效".into());
        }
        for path in [
            &self.document_path,
            &self.files_path,
            &self.edited_document_path,
        ] {
            if !path.is_empty() {
                crate::plugin_assets::relative(std::path::Path::new(path))?;
            }
        }
        Ok(())
    }
    pub fn active_document_path(&self) -> &str {
        if self.edited_document_path.is_empty() {
            &self.document_path
        } else {
            &self.edited_document_path
        }
    }
    pub fn file_paths(&self) -> impl Iterator<Item = &str> {
        [
            self.document_path.as_str(),
            self.files_path.as_str(),
            self.edited_document_path.as_str(),
        ]
        .into_iter()
        .filter(|p| !p.is_empty())
    }
}

pub(crate) fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 96
        && id
            .chars()
            .enumerate()
            .all(|(index, ch)| ch.is_alphanumeric() || (index > 0 && matches!(ch, '_' | '-' | '.')))
}

pub fn validate_all(skills: &[Skill]) -> Result<(), String> {
    if skills.len() > 256 {
        return Err("技能数量超过256个上限".into());
    }
    let mut ids = HashSet::new();
    let mut bytes = 0usize;
    for skill in skills {
        skill.validate()?;
        bytes += skill.content.len() + skill.description.len() + skill.name.len();
        if !ids.insert(&skill.id) || skill.revision == 0 || bytes > 64 * 1024 * 1024 {
            return Err("技能ID重复、版本无效或库大小超过64 MiB".into());
        }
    }
    Ok(())
}

pub fn catalogue(skills: &[Skill]) -> Result<String, String> {
    let entries: Vec<_> = skills.iter().filter(|skill| skill.enabled).map(|skill| json!({"id":skill.id,"name":skill.name,"description":skill.description.lines().next().unwrap_or("").chars().take(120).collect::<String>()})).collect();
    if entries.is_empty() {
        return Ok(String::new());
    }
    let entries = serde_json::to_string(&entries).map_err(|_| "技能目录编码失败")?;
    if entries.len() > 64 * 1024 {
        return Err("启用技能的目录超过64 KiB，请减少启用数量或缩短名称和简介".into());
    }
    Ok(format!(
        "\nEnabled user skills (JSON catalogue): {entries}\nBefore using a skill, call skill_read to read its complete instructions. Skills do not authorize tool effects; every tool still needs per-call approval.\n"
    ))
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct DownloadTarget {
    pub url: String,
    pub workspace: std::path::PathBuf,
    pub namespace: String,
    pub nonce: String,
}
#[derive(Clone, Debug)]
pub struct FrozenDownload {
    pub target: DownloadTarget,
    pub started: std::sync::Arc<std::sync::atomic::AtomicBool>,
}
#[derive(Clone, Debug)]
pub enum Operation {
    Download {
        url: String,
    },
    PrepareDownload {
        url: String,
        nonce: String,
    },
    FrozenDownload(FrozenDownload),
    ImportDownloaded {
        target: DownloadTarget,
        skills: Vec<Skill>,
    },
    List,
    Read {
        name: String,
        offset: usize,
        max_bytes: usize,
    },
    Save {
        name: String,
        description: String,
        content: String,
        expected_revision: Option<u64>,
    },
}

pub fn definitions() -> Vec<ToolDefinition> {
    let definition = |name: &str, description: &str, properties: Value, required: &[&str]| {
        ToolDefinition {
            name: name.into(),
            description: description.into(),
            parameters: json!({"type":"object","properties":properties,"required":required,"additionalProperties":false}),
        }
    };
    vec![
        definition(
            "skill_download",
            "Download a SKILL.md from HTTP/HTTPS and save it disabled. Requires per-call approval. The user must review and enable it in Skills; no scripts or companion code run during download.",
            json!({"url":{"type":"string"}}),
            &["url"],
        ),
        definition(
            "skill_list",
            "List enabled skills and their revisions. Requires per-call approval.",
            json!({}),
            &[],
        ),
        definition(
            "skill_read",
            "Read an enabled skill by name or ID. Large instructions are paged; follow next_offset until absent. Returned resource_path locates companion files. Requires per-call approval.",
            json!({"name":{"type":"string"},"offset":{"type":"integer","minimum":0},"max_bytes":{"type":"integer","minimum":1,"maximum":32768}}),
            &["name"],
        ),
        definition(
            "skill_save",
            "Save a skill written in this conversation, enabled immediately. Updating an existing skill requires expected_revision from skill_read; a new skill must omit it. Content maximum 32 KiB. Requires per-call approval.",
            json!({"name":{"type":"string"},"description":{"type":"string"},"content":{"type":"string"},"expected_revision":{"type":"integer","minimum":1}}),
            &["name", "description", "content"],
        ),
    ]
}

pub fn prepare(call: &ToolCall) -> Result<Operation, String> {
    if call.arguments.len() > 256 * 1024 {
        return Err("技能参数超过256 KiB".into());
    }
    let value: Value = serde_json::from_str(&call.arguments).map_err(|_| "技能参数不是JSON")?;
    let args = value.as_object().ok_or("技能参数必须是对象")?;
    let allowed: &[&str] = match call.name.as_str() {
        "skill_download" => &["url"],
        "skill_list" => &[],
        "skill_read" => &["name", "offset", "max_bytes"],
        "skill_save" => &["name", "description", "content", "expected_revision"],
        _ => return Err("技能工具未接入".into()),
    };
    if args.keys().any(|key| !allowed.contains(&key.as_str())) {
        return Err("不支持的技能参数".into());
    }
    let string = |key: &str, max: usize| {
        args.get(key)
            .and_then(Value::as_str)
            .filter(|v| !v.trim().is_empty() && v.len() <= max && !v.contains('\0'))
            .map(str::to_owned)
            .ok_or_else(|| format!("技能参数{key}为空、类型无效或超过预算"))
    };
    Ok(match call.name.as_str() {
        "skill_download" => Operation::Download {
            url: string("url", 4096)?,
        },
        "skill_list" => Operation::List,
        "skill_read" => Operation::Read {
            name: string("name", 256)?,
            offset: args
                .get("offset")
                .map(|v| {
                    v.as_u64()
                        .and_then(|n| usize::try_from(n).ok())
                        .ok_or("offset须为非负整数")
                })
                .transpose()?
                .unwrap_or(0),
            max_bytes: args
                .get("max_bytes")
                .map(|v| {
                    v.as_u64()
                        .filter(|n| *n > 0 && *n <= 32768)
                        .ok_or("max_bytes须在1–32768之间")
                })
                .transpose()?
                .unwrap_or(32768) as usize,
        },
        "skill_save" => {
            let name = string("name", 96)?;
            let description = string("description", 2048)?;
            let content = string("content", 32 * 1024)?;
            Skill {
                id: name.clone(),
                name: name.clone(),
                description: description.clone(),
                content: content.clone(),
                ..Default::default()
            }
            .validate()?;
            let expected_revision = args
                .get("expected_revision")
                .map(|v| {
                    v.as_u64()
                        .filter(|v| *v > 0)
                        .ok_or("expected_revision必须为正整数")
                })
                .transpose()?;
            Operation::Save {
                name,
                description,
                content,
                expected_revision,
            }
        }
        _ => unreachable!(),
    })
}

/// Preserve the original app's permissive flat frontmatter. Unknown fields do not
/// prevent an external document from being retained and reviewed.
pub fn import_document(id: String, document: String) -> Result<Skill, String> {
    import_document_with_fallback(id, document, None)
}

pub(crate) fn import_document_with_fallback(
    id: String,
    document: String,
    fallback_name: Option<&str>,
) -> Result<Skill, String> {
    if document.len() > 2 * 1024 * 1024 {
        return Err("导入文档超过2 MiB".into());
    }
    let trimmed = document.trim_start();
    let mut body = document.trim();
    let mut name = None;
    let mut description = None;
    if trimmed.starts_with("---") {
        if let Some(end) = trimmed[3..].find("\n---").map(|i| i + 3) {
            let header = trimmed[3..end].trim();
            let authored = header.lines().next() == Some("jagent_format: 1");
            let after_header = &trimmed[end + 4..];
            body = if authored {
                after_header
                    .strip_prefix("\r\n")
                    .or_else(|| after_header.strip_prefix("\n"))
                    .unwrap_or(after_header)
            } else {
                after_header.trim_start_matches(['\n', '\r', ' ', '\t'])
            };
            for line in header.lines() {
                let line = line.trim();
                if line.is_empty() || line.starts_with('#') {
                    continue;
                }
                if let Some((key, value)) = line.split_once(':') {
                    let value = value.trim();
                    let decoded = if authored {
                        serde_json::from_str::<String>(value).ok()
                    } else {
                        let legacy = value.trim_matches(['"', '\'', ' ']);
                        (!legacy.trim().is_empty()).then(|| legacy.to_owned())
                    };
                    match key.trim() {
                        "name" if decoded.is_some() => name = decoded,
                        "description" if decoded.is_some() => description = decoded,
                        _ => {}
                    }
                }
            }
        }
    }
    let description = description.unwrap_or_else(|| {
        body.lines()
            .find(|l| !l.trim().is_empty())
            .unwrap_or("")
            .chars()
            .take(120)
            .collect()
    });
    let skill = Skill {
        id: id.clone(),
        name: name.unwrap_or_else(|| fallback_name.map(str::to_owned).unwrap_or(id)),
        description,
        content: body.to_owned(),
        enabled: false,
        source: "imported".into(),
        ..Default::default()
    };
    skill.validate()?;
    Ok(skill)
}
pub(crate) fn sanitize_import_id(raw: &str) -> String {
    let mut value = String::new();
    for ch in raw.trim().chars() {
        let ch = if ch.is_alphanumeric() || matches!(ch, '_' | '-' | '.') {
            ch
        } else {
            '-'
        };
        if value.len() + ch.len_utf8() > 96 {
            break;
        }
        value.push(ch);
    }
    let value = value.trim_matches(['.', '-', '_']).to_owned();
    if value.is_empty() {
        "skill".into()
    } else {
        value
    }
}
