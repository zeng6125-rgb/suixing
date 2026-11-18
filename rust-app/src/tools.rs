//! The Rust frontend advertises only implemented tools. Every invocation is prepared once,
//! approved against that immutable request. File paths are confined to the private workspace;
//! app-UID shell execution has the OS permissions of the application and is not sandboxed by cwd.
use crate::{network::CancelToken, state::ToolCall};
use serde_json::{Value, json};
use std::path::PathBuf;
use suixing_search::rust_protocol::ToolDefinition;

const MAX_FILE: usize = 4 * 1024 * 1024;
pub(crate) mod file_tools;
const MAX_RESULT_TEXT: usize = 32 * 1024;

pub fn definitions() -> Vec<ToolDefinition> {
    let string = || json!({"type":"string"});
    let integer =
        |default, min, max| json!({"type":"integer","default":default,"minimum":min,"maximum":max});
    let definition = |name: &str, description: &str, properties: Value, required: &[&str]| {
        ToolDefinition {
            name: name.into(),
            description: description.into(),
            parameters: json!({"type":"object","properties":properties,"required":required,"additionalProperties":false}),
        }
    };
    let mut definitions = vec![
        crate::session_tools::definition(),
        definition(
            "list_dir",
            "List entries in the private workspace. Paths are workspace-relative; symlink traversal is refused. Requires user approval.",
            json!({"path":string(),"limit":integer(200,1,1000),"all":{"type":"boolean","default":false}}),
            &[],
        ),
        definition(
            "read_file",
            "Read a UTF-8 workspace file and its SHA-256 revision, optionally a 1-based line range. Files are limited to 1 MiB and returned text is bounded. Requires user approval.",
            json!({"path":string(),"offset":integer(1,1,1000000),"limit":integer(400,1,5000)}),
            &["path"],
        ),
        definition(
            "write_file",
            "Atomically write a UTF-8 workspace file, creating parent directories, optionally appending. Maximum resulting file size is 1 MiB. Requires user approval.",
            json!({"path":string(),"content":string(),"append":{"type":"boolean","default":false}}),
            &["path", "content"],
        ),
        definition(
            "edit_file",
            "Replace exact text in a workspace file. A non-unique old_string is refused unless replace_all=true. Supply expected_revision from read_file to reject stale content. Requires user approval.",
            json!({"path":string(),"old_string":string(),"new_string":string(),"replace_all":{"type":"boolean","default":false},"expected_revision":string()}),
            &["path", "old_string", "new_string"],
        ),
    ];
    definitions.extend(file_tools::definitions());
    if let Some(list) = definitions.iter_mut().find(|tool| tool.name == "list_dir") {
        list.parameters["properties"]["pattern"] = json!({"type":"string"});
    }
    for tool in definitions.iter_mut().filter(|tool| {
        matches!(
            tool.name.as_str(),
            "read_file" | "write_file" | "edit_file" | "list_dir"
        )
    }) {
        tool.description = tool
            .description
            .replace(
                "private workspace",
                "working directory (absolute paths use Android App UID access)",
            )
            .replace("1 MiB", "4 MiB");
    }
    definitions.extend(crate::skills::definitions());
    definitions.extend(crate::system_tools::definitions());
    definitions.extend(crate::todos::definitions());
    definitions.extend(crate::schedules::definitions());
    definitions.extend(crate::jobs::definitions());
    definitions.extend(crate::platform_tools::definitions(
        crate::controller::screenshots_available(),
    ));
    definitions.extend(crate::shell_compatibility::definitions());
    definitions.extend(crate::android_shell_tools::definitions());
    definitions.extend(crate::profile_tools::definitions());
    definitions.extend(crate::terminal_tools::definitions());
    definitions.extend(crate::web::definitions());
    definitions.extend(crate::voice_tools::definitions());
    definitions
}

#[derive(Clone)]
pub enum Operation {
    AndroidSettings(crate::android_shell_tools::Prepared),
    FileQuery(file_tools::Prepared),
    Terminal(crate::controller::terminal_tools_runtime::Prepared),
    Platform(crate::platform_tools::Prepared),
    Web(crate::web::Prepared),
    Shell(FrozenShell),
    Skill(crate::skills::Operation),
    Todo(crate::todos::Operation),
    Schedule(crate::schedules::Operation),
    Job {
        prepared: crate::jobs::Prepared,
        call_id: String,
    },
    List {
        limit: usize,
        all: bool,
        pattern: Option<String>,
    },
    Read {
        offset: usize,
        limit: usize,
    },
    Write {
        content: String,
        append: bool,
    },
    Edit {
        old: String,
        new: String,
        all: bool,
        revision: Option<String>,
    },
}
#[derive(Clone)]
pub struct Prepared {
    pub path: String,
    pub operation: Operation,
}
#[derive(Clone)]
pub struct FrozenShell {
    pub owner: crate::execution_policy::RoutingOwner,
    pub revision: u64,
    pub request: crate::shell_runner::FrozenRequest,
    pub executor: Option<std::sync::Arc<dyn crate::shell_runner::PrivilegedExecutor>>,
    pub started: std::sync::Arc<std::sync::atomic::AtomicBool>,
}

pub fn prepare(call: &ToolCall) -> Result<Prepared, String> {
    if crate::android_shell_tools::is_tool(&call.name) {
        return crate::android_shell_tools::prepare(call).map(|operation| Prepared {
            path: operation.summary.clone(),
            operation: Operation::AndroidSettings(operation),
        });
    }
    if file_tools::definitions()
        .iter()
        .any(|tool| tool.name == call.name)
    {
        return file_tools::prepare(call).map(|query| Prepared {
            path: query.root.clone(),
            operation: Operation::FileQuery(query),
        });
    }
    if crate::platform_tools::definitions(crate::controller::screenshots_available())
        .iter()
        .any(|tool| tool.name == call.name)
    {
        return crate::platform_tools::prepare(call, &crate::controller::platform_context()).map(
            |prepared| Prepared {
                path: prepared.approval_summary.clone(),
                operation: Operation::Platform(prepared),
            },
        );
    }
    if call.name.starts_with("job_") {
        return crate::jobs::prepare(call).map(|prepared| Prepared {
            path: "后台作业".into(),
            operation: Operation::Job {
                prepared,
                call_id: call.id.clone(),
            },
        });
    }
    if call.name.starts_with("schedule_") {
        return crate::schedules::prepare(call).map(|operation| Prepared {
            path: "定时提醒".into(),
            operation: Operation::Schedule(operation),
        });
    }
    if call.name.starts_with("todo_") {
        return crate::todos::prepare(call).map(|operation| Prepared {
            path: "任务清单".into(),
            operation: Operation::Todo(operation),
        });
    }
    if call.name.starts_with("web_") {
        return Err("网页工具必须由当前运行的actor冻结凭据和目标".into());
    }
    if matches!(call.name.as_str(), "run_shell" | "bash") {
        let owner = crate::controller::routing_owner()?;
        let frozen = owner.prepare_shell(
            call,
            format!("shell-tool-{}", crate::controller::unique_id()),
        )?;
        let revision = frozen.revision;
        let request = frozen.request;
        let executor = if matches!(request.route, crate::shell_runner::Route::AppUid) {
            None
        } else {
            owner.context().privileged.clone()
        };
        let path = format!(
            "执行身份 {:?}；Shell {}；工作目录 {}",
            request.route,
            request.argv[0],
            request.cwd.display()
        );
        return Ok(Prepared {
            path,
            operation: Operation::Shell(FrozenShell {
                owner,
                revision,
                request,
                executor,
                started: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
            }),
        });
    }
    if call.name.starts_with("skill_") {
        return crate::skills::prepare(call).map(|operation| Prepared {
            path: "skills".into(),
            operation: Operation::Skill(operation),
        });
    }
    if call.arguments.len() > MAX_FILE {
        return Err("工具参数超过 1 MiB 上限".into());
    }
    let value: Value =
        serde_json::from_str(&call.arguments).map_err(|_| "工具参数不是有效 JSON")?;
    let args = value.as_object().ok_or("工具参数必须是 JSON 对象")?;
    let text = |key: &str| {
        args.get(key)
            .and_then(Value::as_str)
            .map(str::to_owned)
            .ok_or_else(|| format!("参数 {key} 必须是文本"))
    };
    let boolean = |key: &str| match args.get(key) {
        None => Ok(false),
        Some(Value::Bool(value)) => Ok(*value),
        _ => Err(format!("参数 {key} 必须是布尔值")),
    };
    let number = |key: &str, default: usize, max: usize| match args.get(key) {
        None => Ok(default),
        Some(value) => value
            .as_u64()
            .filter(|value| (1..=max as u64).contains(value))
            .map(|value| value as usize)
            .ok_or_else(|| format!("参数 {key} 超出范围")),
    };
    let allowed: &[&str] = match call.name.as_str() {
        "list_dir" => &["path", "limit", "all", "pattern"],
        "read_file" => &["path", "offset", "limit"],
        "write_file" => &["path", "content", "append"],
        "edit_file" => &[
            "path",
            "old_string",
            "new_string",
            "replace_all",
            "expected_revision",
        ],
        _ => return Err(format!("工具 {} 尚未接入，未执行任何操作", call.name)),
    };
    if args.keys().any(|key| !allowed.contains(&key.as_str())) {
        return Err("工具包含当前版本不支持的参数，未执行".into());
    }
    let path = if call.name == "list_dir" && !args.contains_key("path") {
        ".".into()
    } else {
        text("path")?
    };
    if path.len() > 4096 || path.contains('\0') {
        return Err("文件路径无效".into());
    }
    let operation = match call.name.as_str() {
        "list_dir" => Operation::List {
            limit: number("limit", 200, 1000)?,
            all: boolean("all")?,
            pattern: args
                .get("pattern")
                .map(|value| {
                    value
                        .as_str()
                        .filter(|text| text.len() <= 4096)
                        .map(str::to_owned)
                        .ok_or("pattern必须是4096字节内字符串")
                })
                .transpose()?,
        },
        "read_file" => Operation::Read {
            offset: number("offset", 1, 1000000)?,
            limit: number("limit", 400, 5000)?,
        },
        "write_file" => Operation::Write {
            content: text("content")?,
            append: boolean("append")?,
        },
        "edit_file" => {
            let old = text("old_string")?;
            if old.is_empty() {
                return Err("old_string 不能为空".into());
            }
            Operation::Edit {
                old,
                new: text("new_string")?,
                all: boolean("replace_all")?,
                revision: if args.contains_key("expected_revision") {
                    Some(text("expected_revision")?)
                } else {
                    None
                },
            }
        }
        _ => unreachable!(),
    };
    Ok(Prepared { path, operation })
}

#[cfg(unix)]
pub(crate) fn download_write_lock(
    directory: &std::fs::File,
    target: &std::ffi::CString,
) -> Result<std::sync::Arc<std::sync::Mutex<()>>, String> {
    unix::write_lock(directory, target)
}

pub fn execute(workspace: PathBuf, prepared: &Prepared, cancel: &CancelToken) -> Value {
    match execute_inner(workspace, prepared, cancel) {
        Ok(value) if value.to_string().len() <= 256 * 1024 => value,
        Ok(_) => {
            json!({"ok":false,"error":"工具结果超过256 KiB输出上限；请缩小读取范围或目录数量"})
        }
        Err(error) => json!({"ok":false,"error":error}),
    }
}
fn check(cancel: &CancelToken) -> Result<(), String> {
    if cancel.is_cancelled() {
        Err("任务已停止，未继续执行工具".into())
    } else {
        Ok(())
    }
}

#[cfg(not(unix))]
fn execute_inner(
    _workspace: PathBuf,
    _prepared: &Prepared,
    _cancel: &CancelToken,
) -> Result<Value, String> {
    Err("当前平台尚未接入基于目录句柄的工具文件访问".into())
}

#[cfg(unix)]
fn execute_inner(
    workspace: PathBuf,
    prepared: &Prepared,
    cancel: &CancelToken,
) -> Result<Value, String> {
    unix::execute(workspace, prepared, cancel)
}

#[cfg(unix)]
mod unix {
    use super::*;
    use sha2::{Digest, Sha256};
    use std::{
        ffi::{CStr, CString},
        fs::File,
        io::{Read, Write},
        os::{
            fd::{AsRawFd, FromRawFd},
            unix::ffi::OsStrExt,
        },
        path::{Component, Path},
        sync::{
            Arc, Mutex, OnceLock, Weak,
            atomic::{AtomicU64, Ordering},
        },
    };

    fn error(error: std::io::Error) -> String {
        format!("文件操作失败：{error}")
    }

    type WriteKey = (u64, u64, Vec<u8>);
    type WriteLocks = Mutex<std::collections::HashMap<WriteKey, Weak<Mutex<()>>>>;

    pub(super) fn write_lock(directory: &File, target: &CString) -> Result<Arc<Mutex<()>>, String> {
        use std::os::unix::fs::MetadataExt;
        static LOCKS: OnceLock<WriteLocks> = OnceLock::new();
        let metadata = directory.metadata().map_err(error)?;
        // Descriptor identity also merges lexical aliases of the same parent directory.
        let key = (metadata.dev(), metadata.ino(), target.as_bytes().to_vec());
        let mut locks = LOCKS
            .get_or_init(Default::default)
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        locks.retain(|_, lock| lock.strong_count() > 0);
        if let Some(lock) = locks.get(&key).and_then(Weak::upgrade) {
            return Ok(lock);
        }
        let lock = Arc::new(Mutex::new(()));
        locks.insert(key, Arc::downgrade(&lock));
        Ok(lock)
    }
    unsafe fn errno() -> *mut libc::c_int {
        #[cfg(target_os = "android")]
        {
            unsafe { libc::__errno() }
        }
        #[cfg(target_os = "linux")]
        {
            unsafe { libc::__errno_location() }
        }
        #[cfg(not(any(target_os = "android", target_os = "linux")))]
        {
            unsafe { libc::__error() }
        }
    }
    pub(super) fn name(value: &str) -> Result<CString, String> {
        CString::new(value).map_err(|_| "文件名包含 NUL".into())
    }
    pub(super) fn owned(fd: libc::c_int) -> Result<File, String> {
        if fd < 0 {
            Err(error(std::io::Error::last_os_error()))
        }
        // SAFETY: each successful open/dup returns a new descriptor owned by this File.
        else {
            Ok(unsafe { File::from_raw_fd(fd) })
        }
    }
    pub(super) fn open_dir(parent: &File, component: &CString) -> Result<File, String> {
        // SAFETY: the parent descriptor and NUL-terminated component are live for this call.
        owned(unsafe {
            libc::openat(
                parent.as_raw_fd(),
                component.as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
            )
        })
    }
    fn attach_file_activity(
        result: &mut Value,
        path: &str,
        previous: Option<&[u8]>,
        current: &[u8],
    ) {
        if result.get("verified").and_then(Value::as_bool) != Some(true) {
            return;
        }
        let counts = std::str::from_utf8(previous.unwrap_or_default())
            .ok()
            .zip(std::str::from_utf8(current).ok())
            .map(|(old, new)| {
                let before: Vec<_> = old.lines().collect();
                let after: Vec<_> = new.lines().collect();
                let prefix = before
                    .iter()
                    .zip(&after)
                    .take_while(|(a, b)| a == b)
                    .count();
                let suffix = before[prefix..]
                    .iter()
                    .rev()
                    .zip(after[prefix..].iter().rev())
                    .take_while(|(a, b)| a == b)
                    .count();
                (
                    (after.len() - prefix - suffix) as i64,
                    (before.len() - prefix - suffix) as i64,
                )
            });
        let activity = crate::chat::FileActivity {
            path: path.into(),
            kind: if previous.is_some() {
                crate::chat::FileActivityKind::Modified
            } else {
                crate::chat::FileActivityKind::Created
            },
            added_lines: counts.map(|(added, _)| added),
            removed_lines: counts.map(|(_, removed)| removed),
            size_bytes: Some(current.len() as i64),
            description: String::new(),
            previous_path: None,
            extra: Default::default(),
        };
        result["file_activities"] = json!([activity]);
    }

    fn revision(bytes: &[u8]) -> String {
        format!("{:x}", Sha256::digest(bytes))
    }
    pub(super) fn read(
        parent: &File,
        file_name: &CString,
        cancel: &CancelToken,
    ) -> Result<Option<Vec<u8>>, String> {
        check(cancel)?;
        // O_NONBLOCK ensures a replaced FIFO cannot block before the regular-file check.
        let fd = unsafe {
            libc::openat(
                parent.as_raw_fd(),
                file_name.as_ptr(),
                libc::O_RDONLY | libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK,
            )
        };
        if fd < 0 && std::io::Error::last_os_error().kind() == std::io::ErrorKind::NotFound {
            return Ok(None);
        }
        let mut file = owned(fd)?;
        let metadata = file.metadata().map_err(error)?;
        if !metadata.is_file() || metadata.len() > MAX_FILE as u64 {
            return Err("工具只支持 1 MiB 以内的普通文件".into());
        }
        let mut bytes = Vec::new();
        let mut buffer = [0u8; 8192];
        loop {
            check(cancel)?;
            let count = file.read(&mut buffer).map_err(error)?;
            if count == 0 {
                break;
            }
            if count > MAX_FILE.saturating_sub(bytes.len()) {
                return Err("文件读取期间超过 1 MiB 上限".into());
            }
            bytes.extend_from_slice(&buffer[..count]);
        }
        Ok(Some(bytes))
    }
    fn text(bytes: &[u8]) -> Result<&str, String> {
        if bytes.contains(&0) {
            return Err("文件含 NUL，拒绝按文本处理".into());
        }
        std::str::from_utf8(bytes).map_err(|_| "文件不是有效 UTF-8 文本".into())
    }
    fn atomic_write(
        parent: &File,
        target: &CString,
        bytes: &[u8],
        previous: Option<&[u8]>,
        cancel: &CancelToken,
    ) -> Result<Value, String> {
        static NEXT: AtomicU64 = AtomicU64::new(1);
        let temporary = name(&format!(
            ".jagent-tool-{}-{}.tmp",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ))?;
        let mut file = owned(unsafe {
            libc::openat(
                parent.as_raw_fd(),
                temporary.as_ptr(),
                libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL | libc::O_NOFOLLOW | libc::O_CLOEXEC,
                0o600,
            )
        })?;
        struct Temporary<'a> {
            parent: &'a File,
            name: &'a CString,
            armed: bool,
        }
        impl Drop for Temporary<'_> {
            fn drop(&mut self) {
                if self.armed {
                    unsafe {
                        libc::unlinkat(self.parent.as_raw_fd(), self.name.as_ptr(), 0);
                    }
                }
            }
        }
        let mut temporary_cleanup = Temporary {
            parent,
            name: &temporary,
            armed: true,
        };
        for chunk in bytes.chunks(8192) {
            check(cancel)?;
            file.write_all(chunk).map_err(error)?;
        }
        file.sync_all().map_err(error)?;
        // Recheck the source before commit. No in-place write can modify a hard-linked inode.
        let current = read(parent, target, cancel)?;
        if current.as_deref() != previous {
            return Err("文件在操作期间已变化，未覆盖当前内容".into());
        }
        if previous.is_some() {
            let mut metadata = std::mem::MaybeUninit::<libc::stat>::uninit();
            if unsafe {
                libc::fstatat(
                    parent.as_raw_fd(),
                    target.as_ptr(),
                    metadata.as_mut_ptr(),
                    libc::AT_SYMLINK_NOFOLLOW,
                )
            } != 0
            {
                return Err(error(std::io::Error::last_os_error()));
            }
            let metadata = unsafe { metadata.assume_init() };
            if (metadata.st_mode as libc::mode_t) & libc::S_IFMT != libc::S_IFREG {
                return Err("目标文件类型已变化，未执行写入".into());
            }
            if unsafe { libc::fchmod(file.as_raw_fd(), (metadata.st_mode & 0o777) as libc::mode_t) }
                != 0
            {
                return Err(error(std::io::Error::last_os_error()));
            }
            file.sync_all().map_err(error)?;
        }
        check(cancel)?;
        if unsafe {
            libc::renameat(
                parent.as_raw_fd(),
                temporary.as_ptr(),
                parent.as_raw_fd(),
                target.as_ptr(),
            )
        } != 0
        {
            return Err(error(std::io::Error::last_os_error()));
        }
        temporary_cleanup.armed = false;
        // After rename, report the actual committed effect even if cancellation/sync fails.
        let durability_error = parent.sync_all().err().map(error);
        let actual = read(parent, target, &CancelToken::new());
        let verified = matches!(&actual,Ok(Some(current)) if current == bytes);
        Ok(
            json!({"ok":verified && durability_error.is_none(),"committed":true,"verified":verified,"bytes":bytes.len(),"revision":revision(bytes),"durability_error":durability_error,
            "error":if verified { None::<String> } else { Some("写入已提交，但读回内容未核对一致；不要自动重试写入".into()) }}),
        )
    }
    pub fn execute(
        workspace: PathBuf,
        prepared: &Prepared,
        cancel: &CancelToken,
    ) -> Result<Value, String> {
        check(cancel)?;
        if let Operation::FileQuery(query) = &prepared.operation {
            return super::file_tools::execute(workspace, query, cancel);
        }
        let resolved = crate::web::download::resolve_user_path(&workspace, &prepared.path)?;
        let path = resolved.as_path();
        let alias = crate::store::workspace::alias_for_workspace(&workspace, path)?;
        let external = alias.is_none() && path.is_absolute() && !path.starts_with(&workspace);
        let anchor = if external {
            Path::new("/")
        } else {
            workspace.as_path()
        };
        let relative = if let Some(alias) = alias.as_ref() {
            alias.as_path()
        } else if path.is_absolute() {
            path.strip_prefix(anchor)
                .map_err(|_| "路径不在工具工作区内")?
        } else {
            Path::new(
                prepared
                    .path
                    .strip_prefix("~/")
                    .unwrap_or(if prepared.path == "~" {
                        "."
                    } else {
                        &prepared.path
                    }),
            )
        };
        let mut parts = Vec::new();
        for component in relative.components() {
            match component {
                Component::Normal(value) => {
                    parts.push(name(value.to_str().ok_or("文件路径不是 UTF-8")?)?)
                }
                Component::CurDir => (),
                _ => return Err("路径不能越出工具工作区".into()),
            }
        }
        let root_name =
            CString::new(anchor.as_os_str().as_bytes()).map_err(|_| "工作区路径无效")?;
        let mut directory = owned(unsafe {
            libc::open(
                root_name.as_ptr(),
                libc::O_RDONLY | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC,
            )
        })?;
        let listing = matches!(prepared.operation, Operation::List { .. });
        let parent_count = if listing {
            parts.len()
        } else {
            parts.len().checked_sub(1).ok_or("文件路径不能为空")?
        };
        for component in &parts[..parent_count] {
            check(cancel)?;
            if matches!(prepared.operation, Operation::Write { .. }) {
                if unsafe { libc::mkdirat(directory.as_raw_fd(), component.as_ptr(), 0o700) } != 0
                    && std::io::Error::last_os_error().kind() != std::io::ErrorKind::AlreadyExists
                {
                    return Err(error(std::io::Error::last_os_error()));
                }
            }
            directory = open_dir(&directory, component)?;
        }
        if let Operation::List {
            limit,
            all,
            pattern,
        } = &prepared.operation
        {
            let matcher = pattern
                .as_deref()
                .map(super::file_tools::glob)
                .transpose()?;
            let duplicate = unsafe { libc::fcntl(directory.as_raw_fd(), libc::F_DUPFD_CLOEXEC, 0) };
            if duplicate < 0 {
                return Err(error(std::io::Error::last_os_error()));
            }
            let stream = unsafe { libc::fdopendir(duplicate) };
            if stream.is_null() {
                let failure = std::io::Error::last_os_error();
                unsafe {
                    libc::close(duplicate);
                }
                return Err(error(failure));
            }
            struct Directory(*mut libc::DIR);
            impl Drop for Directory {
                fn drop(&mut self) {
                    unsafe {
                        libc::closedir(self.0);
                    }
                }
            }
            let stream = Directory(stream);
            let mut entries = Vec::new();
            let mut scanned = 0usize;
            let mut truncated = false;
            loop {
                check(cancel)?;
                unsafe {
                    *errno() = 0;
                }
                let entry = unsafe { libc::readdir(stream.0) };
                if entry.is_null() {
                    if unsafe { *errno() } != 0 {
                        return Err(error(std::io::Error::last_os_error()));
                    }
                    break;
                }
                let entry_name = unsafe { CStr::from_ptr((*entry).d_name.as_ptr()) };
                let display = entry_name.to_str().map_err(|_| "目录包含非 UTF-8 文件名")?;
                if display == "." || display == ".." {
                    continue;
                }
                scanned += 1;
                if scanned > 8192 {
                    truncated = true;
                    break;
                }
                if (!*all && display.starts_with('.'))
                    || matcher
                        .as_ref()
                        .is_some_and(|matcher| !matcher.is_match(display))
                {
                    continue;
                }
                let mut stat = std::mem::MaybeUninit::<libc::stat>::uninit();
                if unsafe {
                    libc::fstatat(
                        directory.as_raw_fd(),
                        entry_name.as_ptr(),
                        stat.as_mut_ptr(),
                        libc::AT_SYMLINK_NOFOLLOW,
                    )
                } != 0
                {
                    return Err(error(std::io::Error::last_os_error()));
                }
                let stat = unsafe { stat.assume_init() };
                let kind = (stat.st_mode as libc::mode_t) & libc::S_IFMT;
                entries.push((
                    kind == libc::S_IFDIR,
                    display.to_owned(),
                    stat.st_size.max(0) as u64,
                    kind == libc::S_IFLNK,
                ));
            }
            entries.sort_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
            truncated |= entries.len() > *limit;
            let entries: Vec<_> = entries.into_iter().take(*limit).map(|(is_dir,name,size,is_symlink)| json!({"name":name,"is_dir":is_dir,"size":size,"is_symlink":is_symlink})).collect();
            return Ok(
                json!({"ok":true,"path":prepared.path,"entries":entries,"truncated":truncated}),
            );
        }
        let target = parts.last().ok_or("文件名不能为空")?;
        let writer = if matches!(
            prepared.operation,
            Operation::Write { .. } | Operation::Edit { .. }
        ) {
            Some(write_lock(&directory, target)?)
        } else {
            None
        };
        let _write_guard = if let Some(lock) = &writer {
            Some(loop {
                check(cancel)?;
                match lock.try_lock() {
                    Ok(guard) => break guard,
                    Err(std::sync::TryLockError::Poisoned(error)) => break error.into_inner(),
                    Err(std::sync::TryLockError::WouldBlock) => {
                        std::thread::sleep(std::time::Duration::from_millis(16));
                    }
                }
            })
        } else {
            None
        };
        // All builtin writers of this pathname hold the lock from the initial read
        // through rename and verification. External processes still require the
        // precommit comparison and are outside this process-local exclusion.
        let previous = read(&directory, target, cancel)?;
        match &prepared.operation {
            Operation::Read { offset, limit } => {
                let bytes = previous.as_deref().ok_or("文件不存在")?;
                let source = text(bytes)?;
                let mut lines = Vec::new();
                let mut begin = 0;
                let mut index = 0;
                while index < bytes.len() {
                    if index % 8192 == 0 {
                        check(cancel)?;
                    }
                    if matches!(bytes[index], b'\r' | b'\n') {
                        if bytes[index] == b'\r' && bytes.get(index + 1) == Some(&b'\n') {
                            index += 1;
                        }
                        index += 1;
                        lines.push(&source[begin..index]);
                        begin = index;
                    } else {
                        index += 1;
                    }
                }
                if begin < bytes.len() {
                    lines.push(&source[begin..]);
                }
                let start = offset.saturating_sub(1).min(lines.len());
                let mut output = String::new();
                let mut count = 0;
                let mut partial_line = false;
                for line in lines.iter().skip(start).take(*limit) {
                    check(cancel)?;
                    if output.len() + line.len() > MAX_RESULT_TEXT {
                        if count == 0 {
                            let mut end = MAX_RESULT_TEXT.min(line.len());
                            while !line.is_char_boundary(end) {
                                end -= 1;
                            }
                            output.push_str(&line[..end]);
                            count = 1;
                            partial_line = true;
                        }
                        break;
                    }
                    output.push_str(line);
                    count += 1;
                }
                Ok(
                    json!({"ok":true,"path":prepared.path,"text":output,"offset":offset,"returned_lines":count,"total_lines":lines.len(),"partial_line":partial_line,"truncated":partial_line || start+count<lines.len(),"revision":revision(bytes)}),
                )
            }
            Operation::Write { content, append } => {
                let mut bytes = if *append {
                    previous.clone().unwrap_or_default()
                } else {
                    Vec::new()
                };
                if *append {
                    text(&bytes)?;
                }
                if content.len() > MAX_FILE.saturating_sub(bytes.len()) {
                    return Err("写入结果超过 1 MiB 上限".into());
                }
                bytes.extend_from_slice(content.as_bytes());
                text(&bytes)?;
                let mut result =
                    atomic_write(&directory, target, &bytes, previous.as_deref(), cancel)?;
                attach_file_activity(&mut result, &prepared.path, previous.as_deref(), &bytes);
                Ok(result)
            }
            Operation::Edit {
                old,
                new,
                all,
                revision: expected,
            } => {
                let bytes = previous.as_deref().ok_or("文件不存在")?;
                if expected
                    .as_ref()
                    .is_some_and(|expected| *expected != revision(bytes))
                {
                    return Err("文件版本已变化，未执行编辑".into());
                }
                let source = text(bytes)?;
                let count = source.match_indices(old).count();
                let overlapping = if !all {
                    source.find(old).is_some_and(|start| {
                        let next = start
                            + source[start..]
                                .chars()
                                .next()
                                .map(char::len_utf8)
                                .unwrap_or(0);
                        source[next..].contains(old)
                    })
                } else {
                    false
                };
                if count == 0 || (!all && (count != 1 || overlapping)) {
                    return Err(format!(
                        "old_string 匹配不唯一或不存在（非重叠匹配 {count} 处），未执行编辑；请提供唯一文本或明确 replace_all"
                    ));
                }
                let resulting_size = source
                    .len()
                    .checked_sub(old.len() * count)
                    .and_then(|size| {
                        new.len()
                            .checked_mul(count)
                            .and_then(|added| size.checked_add(added))
                    })
                    .ok_or("编辑结果长度溢出")?;
                if resulting_size > MAX_FILE {
                    return Err("编辑结果超过 1 MiB 上限".into());
                }
                let result = source.replace(old, new);
                let mut written =
                    atomic_write(&directory, target, result.as_bytes(), Some(bytes), cancel)?;
                attach_file_activity(&mut written, &prepared.path, Some(bytes), result.as_bytes());
                Ok(written)
            }
            Operation::List { .. } => unreachable!(),
            Operation::Skill(_) => Err("技能工具必须通过应用运行线程执行".into()),
            Operation::Todo(_) => Err("任务清单工具必须通过应用运行线程执行".into()),
            Operation::Shell(_) => Err("Shell工具必须通过子进程执行器执行".into()),
            Operation::Web(_) => Err("网页工具必须通过网络运行时执行".into()),
            Operation::FileQuery(_) => {
                unreachable!("file query handled before workspace traversal")
            }
            Operation::Schedule(_)
            | Operation::Job { .. }
            | Operation::Platform(_)
            | Operation::Terminal(_)
            | Operation::AndroidSettings(_) => {
                Err("提醒、作业与结构化Android工具必须通过所属运行actor执行".into())
            }
        }
    }
}
