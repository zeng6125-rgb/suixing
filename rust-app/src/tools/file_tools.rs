//! Original file discovery and delivery tools, using stable directory handles.
use crate::{network::CancelToken, state::ToolCall};
use serde_json::{Value, json};
use std::path::PathBuf;
use suixing_search::rust_protocol::ToolDefinition;

#[derive(Clone)]
pub struct Prepared {
    pub root: String,
    pub operation: Operation,
}
#[derive(Clone)]
pub enum Operation {
    Find {
        name: String,
        pattern: Option<String>,
        exclude: String,
        kind: String,
        depth: usize,
        limit: usize,
    },
    Grep {
        query: String,
        regex: bool,
        exclude: String,
        files_only: bool,
        context: usize,
        case_sensitive: bool,
        max_files: usize,
        limit: usize,
    },
    Deliver {
        description: String,
    },
}
pub fn glob(pattern: &str) -> Result<regex::Regex, String> {
    if pattern.len() > 4096 {
        return Err("文件glob超过4096字节".into());
    }
    let mut source = String::from("^");
    let mut chars = pattern.chars().peekable();
    while let Some(ch) = chars.next() {
        match ch {
            '*' => {
                if chars.peek() == Some(&'*') {
                    chars.next();
                    if chars.peek() == Some(&'/') {
                        chars.next();
                        source.push_str("(?:.*/)?");
                    } else {
                        source.push_str(".*");
                    }
                } else {
                    source.push_str("[^/]*");
                }
            }
            '?' => source.push_str("[^/]"),
            '[' => {
                source.push('[');
                if chars.peek() == Some(&'!') {
                    chars.next();
                    source.push('^');
                }
                let mut closed = false;
                for ch in chars.by_ref() {
                    if ch == ']' {
                        source.push(']');
                        closed = true;
                        break;
                    }
                    if matches!(ch, '\\' | '[') {
                        source.push('\\');
                    }
                    source.push(ch);
                }
                if !closed {
                    return Err("glob字符组缺少结束括号".into());
                }
            }
            '\\' => {
                let ch = chars.next().ok_or("glob末尾转义不完整")?;
                source.push_str(&regex::escape(&ch.to_string()));
            }
            ch => source.push_str(&regex::escape(&ch.to_string())),
        }
    }
    source.push('$');
    regex::RegexBuilder::new(&source)
        .size_limit(1024 * 1024)
        .build()
        .map_err(|_| "glob表达式无效或超过预算".into())
}
pub fn definitions() -> Vec<ToolDefinition> {
    let string = || json!({"type":"string"});
    let integer =
        |default, min, max| json!({"type":"integer","default":default,"minimum":min,"maximum":max});
    let def = |name: &str, description: &str, properties: Value, required: &[&str]| {
        ToolDefinition {
            name: name.into(),
            description: description.into(),
            parameters: json!({"type":"object","properties":properties,"required":required,"additionalProperties":false}),
        }
    };
    vec![
        def(
            "deliver_file",
            "Verify an existing readable ordinary file and deliver it as a user file card. Does not modify or upload the file. Absolute paths use Android App UID access; requires approval.",
            json!({"path":string(),"description":string()}),
            &["path"],
        ),
        def(
            "find_files",
            "Find files or directories by glob or case-insensitive name substring. Relative roots use the working directory; absolute roots use App UID access. Never follows symlinks. Reports scan limits and unreadable paths; requires approval.",
            json!({"root":string(),"name":string(),"pattern":string(),"exclude":string(),"type":{"type":"string","enum":["file","dir","any"],"default":"file"},"max_depth":integer(8,1,30),"limit":integer(60,1,500)}),
            &[],
        ),
        def(
            "grep_files",
            "Search readable UTF-8 text files with literal text or regex, optional grep-style context. Each file is bounded to 2 MiB; the traversal and output are bounded and skipped files are reported. Requires approval.",
            json!({"root":string(),"query":string(),"regex":{"type":"boolean","default":false},"exclude":string(),"files_only":{"type":"boolean","default":false},"context":integer(0,0,5),"case_sensitive":{"type":"boolean","default":false},"max_files":integer(400,1,5000),"limit":integer(60,1,500)}),
            &["query"],
        ),
    ]
}
pub fn prepare(call: &ToolCall) -> Result<Prepared, String> {
    if call.arguments.len() > 128 * 1024 {
        return Err("文件查询参数超过128 KiB".into());
    }
    let args: Value = serde_json::from_str(&call.arguments).map_err(|_| "文件查询参数不是JSON")?;
    let args = args.as_object().ok_or("文件查询参数必须是对象")?;
    let allowed: &[&str] = match call.name.as_str() {
        "deliver_file" => &["path", "description"],
        "find_files" => &[
            "root",
            "name",
            "pattern",
            "exclude",
            "type",
            "max_depth",
            "limit",
        ],
        "grep_files" => &[
            "root",
            "query",
            "regex",
            "exclude",
            "files_only",
            "context",
            "case_sensitive",
            "max_files",
            "limit",
        ],
        _ => return Err("未知文件查询".into()),
    };
    if args.keys().any(|key| !allowed.contains(&key.as_str())) {
        return Err("文件查询包含未知字段".into());
    }
    let text = |key: &str, default: &str| -> Result<String, String> {
        match args.get(key) {
            None => Ok(default.into()),
            Some(value) => value
                .as_str()
                .filter(|s| s.len() <= 4096 && !s.contains('\0'))
                .map(str::to_owned)
                .ok_or(format!("{key}必须是4096字节内文本")),
        }
    };
    let boolean = |key: &str| -> Result<bool, String> {
        args.get(key)
            .map(|value| value.as_bool().ok_or(format!("{key}必须是布尔值")))
            .transpose()
            .map(|v| v.unwrap_or(false))
    };
    let number = |key: &str, default: usize, min: usize, max: usize| -> Result<usize, String> {
        args.get(key)
            .map(|value| {
                value
                    .as_u64()
                    .filter(|n| (*n as u128) >= min as u128 && (*n as u128) <= max as u128)
                    .map(|n| n as usize)
                    .ok_or(format!("{key}超出范围"))
            })
            .transpose()
            .map(|v| v.unwrap_or(default))
    };
    let root = text(
        if call.name == "deliver_file" {
            "path"
        } else {
            "root"
        },
        if call.name == "deliver_file" { "" } else { "~" },
    )?;
    if root.trim().is_empty() {
        return Err("文件路径不能为空".into());
    }
    let operation = match call.name.as_str() {
        "deliver_file" => Operation::Deliver {
            description: text("description", "")?.trim().chars().take(500).collect(),
        },
        "find_files" => {
            let pattern = args
                .get("pattern")
                .map(|_| text("pattern", ""))
                .transpose()?;
            if let Some(pattern) = &pattern {
                glob(pattern)?;
            }
            let kind = text("type", "file")?;
            if !["file", "dir", "any"].contains(&kind.as_str()) {
                return Err("文件type无效".into());
            }
            Operation::Find {
                name: text("name", "")?.to_lowercase(),
                pattern,
                exclude: text("exclude", "")?,
                kind,
                depth: number("max_depth", 8, 1, 30)?,
                limit: number("limit", 60, 1, 500)?,
            }
        }
        "grep_files" => {
            let query = text("query", "")?;
            if query.is_empty() {
                return Err("搜索query不能为空".into());
            }
            let regex = boolean("regex")?;
            let case_sensitive = boolean("case_sensitive")?;
            search_regex(&query, regex, case_sensitive)?;
            Operation::Grep {
                query,
                regex,
                exclude: text("exclude", "")?,
                files_only: boolean("files_only")?,
                context: number("context", 0, 0, 5)?,
                case_sensitive,
                max_files: number("max_files", 400, 1, 5000)?,
                limit: number("limit", 60, 1, 500)?,
            }
        }
        _ => unreachable!(),
    };
    Ok(Prepared { root, operation })
}
fn search_regex(query: &str, is_regex: bool, case_sensitive: bool) -> Result<regex::Regex, String> {
    let escaped;
    if is_regex {
        escaped = query.to_owned();
    } else {
        escaped = regex::escape(query);
    }
    regex::RegexBuilder::new(&escaped)
        .case_insensitive(!case_sensitive)
        .size_limit(1024 * 1024)
        .dfa_size_limit(1024 * 1024)
        .build()
        .map_err(|_| "搜索正则无效或超过预算".into())
}
#[cfg(not(unix))]
pub(super) fn execute(
    _workspace: PathBuf,
    _prepared: &Prepared,
    _cancel: &CancelToken,
) -> Result<Value, String> {
    Err("当前平台未接入目录句柄文件查询".into())
}
#[cfg(unix)]
pub(super) fn execute(
    workspace: PathBuf,
    prepared: &Prepared,
    cancel: &CancelToken,
) -> Result<Value, String> {
    unix::execute(workspace, prepared, cancel)
}
#[cfg(unix)]
pub(crate) fn open_source(
    workspace: &std::path::Path,
    raw: &str,
    cancel: &CancelToken,
) -> Result<(PathBuf, std::fs::File), String> {
    unix::open_source(workspace, raw, cancel)
}

#[cfg(unix)]
mod unix {
    use super::*;
    use std::{
        ffi::{CStr, CString},
        fs::{File, Metadata},
        io::Read,
        os::{
            fd::{AsRawFd, FromRawFd},
            unix::{ffi::OsStrExt, fs::MetadataExt},
        },
        path::{Component, Path},
        time::{Duration, Instant},
    };
    fn error(error: std::io::Error) -> String {
        format!("文件访问失败：{error}")
    }
    fn check(cancel: &CancelToken) -> Result<(), String> {
        if cancel.is_cancelled() {
            Err("文件操作已取消".into())
        } else {
            Ok(())
        }
    }
    fn resolve(workspace: &Path, raw: &str) -> Result<PathBuf, String> {
        crate::web::download::resolve_user_path(workspace, raw)
    }

    fn directory(path: &Path, cancel: &CancelToken) -> Result<File, String> {
        let mut directory = super::super::unix::owned(unsafe {
            libc::open(
                c"/".as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC,
            )
        })?;
        for component in path.components() {
            check(cancel)?;
            match component {
                Component::RootDir => (),
                Component::Normal(name) => {
                    let name = CString::new(name.as_bytes()).map_err(|_| "路径包含NUL")?;
                    directory = super::super::unix::open_dir(&directory, &name)?;
                }
                _ => return Err("文件路径未正规化".into()),
            }
        }
        Ok(directory)
    }
    fn regular(parent: &File, name: &CStr, max: u64) -> Result<File, String> {
        let file = super::super::unix::owned(unsafe {
            libc::openat(
                parent.as_raw_fd(),
                name.as_ptr(),
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK,
            )
        })?;
        let metadata = file.metadata().map_err(error)?;
        if !metadata.is_file() || metadata.len() > max {
            return Err("文件不是普通文件或超过本次读取预算".into());
        }
        Ok(file)
    }
    fn same(a: &Metadata, b: &Metadata) -> bool {
        a.dev() == b.dev()
            && a.ino() == b.ino()
            && a.len() == b.len()
            && a.mtime() == b.mtime()
            && a.mtime_nsec() == b.mtime_nsec()
            && a.ctime() == b.ctime()
            && a.ctime_nsec() == b.ctime_nsec()
    }
    pub(super) fn open_source(
        workspace: &Path,
        raw: &str,
        cancel: &CancelToken,
    ) -> Result<(PathBuf, File), String> {
        let path = resolve(workspace, raw)?;
        let parent = directory(path.parent().ok_or("路径没有文件父目录")?, cancel)?;
        let name = CString::new(path.file_name().ok_or("路径不是文件")?.as_bytes())
            .map_err(|_| "文件名含NUL")?;
        let file = regular(&parent, &name, u64::MAX)?;
        Ok((path, file))
    }
    struct Entry {
        name: String,
        is_dir: bool,
        is_file: bool,
    }
    fn entries(directory: &File, cancel: &CancelToken) -> Result<(Vec<Entry>, bool), String> {
        let fd = unsafe { libc::fcntl(directory.as_raw_fd(), libc::F_DUPFD_CLOEXEC, 0) };
        if fd < 0 {
            return Err(error(std::io::Error::last_os_error()));
        }
        let ptr = unsafe { libc::fdopendir(fd) };
        if ptr.is_null() {
            let error = std::io::Error::last_os_error();
            unsafe {
                libc::close(fd);
            }
            return Err(self::error(error));
        }
        struct Stream(*mut libc::DIR);
        impl Drop for Stream {
            fn drop(&mut self) {
                unsafe {
                    libc::closedir(self.0);
                }
            }
        }
        let stream = Stream(ptr);
        let mut result = Vec::new();
        let mut truncated = false;
        loop {
            check(cancel)?;
            #[cfg(target_os = "android")]
            unsafe {
                *libc::__errno() = 0;
            }
            #[cfg(target_os = "linux")]
            unsafe {
                *libc::__errno_location() = 0;
            }
            let entry = unsafe { libc::readdir(stream.0) };
            if entry.is_null() {
                #[cfg(target_os = "android")]
                let errno = unsafe { *libc::__errno() };
                #[cfg(target_os = "linux")]
                let errno = unsafe { *libc::__errno_location() };
                #[cfg(not(any(target_os = "android", target_os = "linux")))]
                let errno = 0;
                if errno != 0 {
                    return Err(error(std::io::Error::from_raw_os_error(errno)));
                }
                break;
            }
            let name = unsafe { CStr::from_ptr((*entry).d_name.as_ptr()) };
            if name == c"." || name == c".." {
                continue;
            }
            if result.len() >= 8192 {
                truncated = true;
                break;
            }
            let mut stat = std::mem::MaybeUninit::<libc::stat>::uninit();
            if unsafe {
                libc::fstatat(
                    directory.as_raw_fd(),
                    name.as_ptr(),
                    stat.as_mut_ptr(),
                    libc::AT_SYMLINK_NOFOLLOW,
                )
            } != 0
            {
                continue;
            }
            let stat = unsafe { stat.assume_init() };
            let kind = (stat.st_mode as libc::mode_t) & libc::S_IFMT;
            let Some(name) = name.to_str().ok() else {
                truncated = true;
                continue;
            };
            result.push(Entry {
                name: name.into(),
                is_dir: kind == libc::S_IFDIR,
                is_file: kind == libc::S_IFREG,
            });
        }
        result.sort_by(|a, b| b.is_dir.cmp(&a.is_dir).then_with(|| a.name.cmp(&b.name)));
        Ok((result, truncated))
    }
    pub(super) fn execute(
        workspace: PathBuf,
        prepared: &Prepared,
        cancel: &CancelToken,
    ) -> Result<Value, String> {
        check(cancel)?;
        let root = resolve(&workspace, &prepared.root)?;
        if let Operation::Deliver { description } = &prepared.operation {
            let parent = directory(root.parent().ok_or("交付路径不是文件")?, cancel)?;
            let name = CString::new(root.file_name().ok_or("文件名缺失")?.as_bytes())
                .map_err(|_| "文件名包含NUL")?;
            let file = regular(&parent, &name, u64::MAX)?;
            let metadata = file.metadata().map_err(error)?;
            check(cancel)?;
            let path = root.to_str().ok_or("路径不是UTF8")?;
            let activity = crate::chat::FileActivity {
                path: path.into(),
                kind: crate::chat::FileActivityKind::Delivered,
                added_lines: None,
                removed_lines: None,
                size_bytes: i64::try_from(metadata.len()).ok(),
                description: description.clone(),
                previous_path: None,
                extra: Default::default(),
            };
            return Ok(
                json!({"ok":true,"path":path,"size_bytes":metadata.len(),"description":description,"file_activities":[activity]}),
            );
        }
        let (depth, limit, max_files, exclude) = match &prepared.operation {
            Operation::Find {
                depth,
                limit,
                exclude,
                ..
            } => (*depth, *limit, usize::MAX, exclude),
            Operation::Grep {
                limit,
                max_files,
                exclude,
                ..
            } => (30, *limit, *max_files, exclude),
            _ => unreachable!(),
        };
        let matcher = match &prepared.operation {
            Operation::Find {
                pattern: Some(pattern),
                ..
            } => Some(glob(pattern)?),
            Operation::Grep {
                query,
                regex,
                case_sensitive,
                ..
            } => Some(search_regex(query, *regex, *case_sensitive)?),
            _ => None,
        };
        let mut stack = vec![(root.clone(), 0usize)];
        let started = Instant::now();
        let mut scanned = 0usize;
        let mut scanned_files = 0usize;
        let mut skipped = 0usize;
        let mut truncated = false;
        let mut output = Vec::new();
        let mut output_bytes = 0usize;
        'walk: while let Some((path, level)) = stack.pop() {
            check(cancel)?;
            if started.elapsed() > Duration::from_secs(10) || scanned >= 100_000 {
                truncated = true;
                break;
            }
            let directory = match directory(&path, cancel) {
                Ok(directory) => directory,
                Err(error) if path == root => return Err(error),
                Err(_) => {
                    skipped += 1;
                    continue;
                }
            };
            let (rows, cut) = entries(&directory, cancel)?;
            truncated |= cut;
            for entry in rows {
                check(cancel)?;
                scanned += 1;
                let path = path.join(&entry.name);
                let relative = path.strip_prefix(&root).unwrap_or(&path).to_string_lossy();
                if !exclude.is_empty() && relative.contains(exclude) {
                    continue;
                }
                if entry.is_dir && level + 1 < depth {
                    if stack.len() < 512 {
                        stack.push((path.clone(), level + 1));
                    } else {
                        truncated = true;
                    }
                }
                match &prepared.operation {
                    Operation::Find {
                        name,
                        pattern,
                        kind,
                        ..
                    } => {
                        let candidate = if pattern
                            .as_ref()
                            .is_some_and(|pattern| pattern.contains('/'))
                        {
                            relative.as_ref()
                        } else {
                            &entry.name
                        };
                        let matches = matcher.as_ref().map_or_else(
                            || entry.name.to_lowercase().contains(name),
                            |matcher| matcher.is_match(candidate),
                        );
                        if matches
                            && (kind == "any" && (entry.is_dir || entry.is_file)
                                || kind == "dir" && entry.is_dir
                                || kind == "file" && entry.is_file)
                        {
                            let value =
                                json!({"path":path,"type":if entry.is_dir {"dir"} else {"file"}});
                            output_bytes += value.to_string().len();
                            output.push(value);
                        }
                    }
                    Operation::Grep {
                        files_only,
                        context,
                        ..
                    } if entry.is_file => {
                        if scanned_files >= max_files {
                            truncated = true;
                            break 'walk;
                        }
                        scanned_files += 1;
                        let name =
                            CString::new(entry.name.as_bytes()).map_err(|_| "文件名包含NUL")?;
                        let mut file = match regular(&directory, &name, 2 * 1024 * 1024) {
                            Ok(file) => file,
                            Err(_) => {
                                skipped += 1;
                                continue;
                            }
                        };
                        let before = file.metadata().map_err(error)?;
                        let mut bytes =
                            Vec::with_capacity(before.len().min(2 * 1024 * 1024) as usize);
                        let mut buffer = [0u8; 8192];
                        loop {
                            check(cancel)?;
                            let count = file.read(&mut buffer).map_err(error)?;
                            if count == 0 {
                                break;
                            }
                            if bytes.len() + count > 2 * 1024 * 1024 {
                                skipped += 1;
                                bytes.clear();
                                break;
                            }
                            bytes.extend_from_slice(&buffer[..count]);
                        }
                        if bytes.contains(&0) || !same(&before, &file.metadata().map_err(error)?) {
                            skipped += 1;
                            continue;
                        }
                        let text = match std::str::from_utf8(&bytes) {
                            Ok(text) => text,
                            Err(_) => {
                                skipped += 1;
                                continue;
                            }
                        };
                        let lines: Vec<_> = text.lines().collect();
                        let matcher = matcher.as_ref().ok_or("搜索匹配器缺失")?;
                        for (index, line) in lines.iter().enumerate() {
                            check(cancel)?;
                            if !matcher.is_match(line) {
                                continue;
                            }
                            let value = if *files_only {
                                json!({"path":path})
                            } else {
                                let from = index.saturating_sub(*context);
                                let to = (index + context + 1).min(lines.len());
                                let surrounding:Vec<_>=(from..to).map(|at|json!({"line":at+1,"match":at==index,"text":lines[at].chars().take(500).collect::<String>(),"text_truncated":lines[at].chars().count()>500})).collect();
                                json!({"path":path,"line":index+1,"context":surrounding})
                            };
                            output_bytes += value.to_string().len();
                            output.push(value);
                            if output.len() >= limit || output_bytes >= 128 * 1024 {
                                truncated = true;
                                break 'walk;
                            }
                            if *files_only {
                                break;
                            }
                        }
                    }
                    _ => (),
                }
                if output.len() >= limit || output_bytes >= 128 * 1024 {
                    truncated = true;
                    break 'walk;
                }
            }
        }
        Ok(
            json!({"ok":true,"root":root,"matches":output,"scanned_entries":scanned,"scanned_files":scanned_files,"skipped_unreadable_binary_changed_or_oversize":skipped,"truncated":truncated,"notice":if truncated {"扫描或结果达到预算；请缩小root、提高筛选精度或分批查询"} else {""}}),
        )
    }
}
