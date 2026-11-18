//! Frozen shell identities and bounded execution. Privilege routing belongs to
//! root's settings/approval owner; executors never downgrade or replay commands.
use crate::{
    jobs::{ObservedChanges, observation::Observation},
    shell_environment::{Backend, ShellEnvironment},
    state::ToolCall,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
};
use suixing_search::rust_protocol::ToolDefinition;
mod process;
mod root;
pub use root::RootSuExecutor;
#[derive(Clone, Copy, Debug, Serialize, Deserialize, Eq, PartialEq)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Route {
    AppUid,
    Shizuku { generation: u64 },
    Root { generation: u64 },
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct FrozenRequest {
    pub request_key: String,
    pub argv: Vec<String>,
    pub environment: Vec<String>,
    pub cwd: PathBuf,
    pub route: Route,
    pub timeout_ms: u64,
    pub max_output_bytes: usize,
    pub observe_paths: Vec<PathBuf>,
    #[serde(skip)]
    pub(crate) root_use: Option<Arc<crate::shell_environment::lease::UseLease>>,
}
impl FrozenRequest {
    pub fn validate(&self) -> Result<(), String> {
        if self.request_key.is_empty()
            || self.request_key.len() > 1024
            || self.request_key.contains('\0')
            || self.argv.is_empty()
            || self.argv.len() > 128
            || self.environment.len() > 128
            || !PathBuf::from(&self.argv[0]).is_absolute()
            || !self.cwd.is_absolute()
            || self.cwd.as_os_str().to_string_lossy().contains('\0')
            || !(1000..=600_000).contains(&self.timeout_ms)
            || !(1..=16 * 1024 * 1024).contains(&self.max_output_bytes)
            || self.observe_paths.len() > 33
        {
            return Err("冻结Shell请求身份或预算无效".into());
        }
        let bytes =
            self.argv
                .iter()
                .chain(self.environment.iter())
                .try_fold(0usize, |total, value| {
                    if value.contains('\0') {
                        return Err("Shell字段包含NUL");
                    }
                    total
                        .checked_add(value.len())
                        .filter(|total| *total <= 1024 * 1024)
                        .ok_or("Shell请求超过1 MiB")
                })?;
        let _ = bytes;
        if self.environment.iter().any(|value| {
            value.split_once('=').is_none_or(|(name, _)| {
                name.is_empty()
                    || !name
                        .bytes()
                        .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
            })
        }) || self
            .observe_paths
            .iter()
            .any(|path| !path.is_absolute() || path.as_os_str().to_string_lossy().contains('\0'))
        {
            return Err("冻结Shell环境或观察路径无效".into());
        }
        if matches!(
            self.route,
            Route::Root { generation: 0 } | Route::Shizuku { generation: 0 }
        ) {
            return Err("特权Shell缺少已冻结的后端代次".into());
        }
        Ok(())
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Outcome {
    pub started: bool,
    pub identity_confirmed: bool,
    pub route: Route,
    pub exit_code: Option<i32>,
    pub signal: Option<i32>,
    pub stdout: String,
    pub stderr: String,
    pub stdout_bytes: u64,
    pub stderr_bytes: u64,
    pub truncated: bool,
    pub cancel_requested: bool,
    pub timed_out: bool,
    pub primary_exit_confirmed: bool,
    pub group_cleanup_confirmed: bool,
    pub error: Option<String>,
    pub file_changes: ObservedChanges,
}
impl Outcome {
    pub fn not_started(route: Route, error: impl Into<String>) -> Self {
        Self {
            started: false,
            identity_confirmed: false,
            route,
            exit_code: None,
            signal: None,
            stdout: String::new(),
            stderr: String::new(),
            stdout_bytes: 0,
            stderr_bytes: 0,
            truncated: false,
            cancel_requested: false,
            timed_out: false,
            primary_exit_confirmed: false,
            group_cleanup_confirmed: false,
            error: Some(error.into()),
            file_changes: ObservedChanges::default(),
        }
    }
    pub fn succeeded(&self) -> bool {
        self.started
            && self.identity_confirmed
            && self.primary_exit_confirmed
            && self.exit_code == Some(0)
            && !self.timed_out
            && !self.cancel_requested
            && self.error.is_none()
    }
}
pub trait PrivilegedExecutor: Send + Sync {
    fn execute(&self, request: &FrozenRequest, cancel: Arc<AtomicBool>) -> Outcome;
}
#[derive(Default)]
pub struct Grants {
    pub shizuku: Option<u64>,
    pub root: Option<u64>,
    pub ceiling: Ceiling,
}
#[derive(Clone, Copy, Default)]
pub enum Ceiling {
    #[default]
    AppUid,
    Shizuku,
    Root,
}
pub fn choose_route(mode: &str, grants: &Grants) -> Result<Route, String> {
    let shizuku = grants
        .shizuku
        .filter(|generation| *generation > 0)
        .filter(|_| !matches!(grants.ceiling, Ceiling::AppUid));
    let root = grants
        .root
        .filter(|generation| *generation > 0)
        .filter(|_| matches!(grants.ceiling, Ceiling::Root));
    match mode {
        "local" => Ok(Route::AppUid),
        "shizuku" => shizuku
            .map(|generation| Route::Shizuku { generation })
            .ok_or("当前设置与授权未允许Shizuku身份".into()),
        "root" => root
            .map(|generation| Route::Root { generation })
            .ok_or("当前设置与授权未允许Root身份".into()),
        "auto" => Ok(if let Some(generation) = root {
            Route::Root { generation }
        } else if let Some(generation) = shizuku {
            Route::Shizuku { generation }
        } else {
            Route::AppUid
        }),
        _ => Err("未知Shell身份请求".into()),
    }
}
pub fn prepare_tool(
    call: &ToolCall,
    environment: &ShellEnvironment,
    grants: &Grants,
    request_key: String,
) -> Result<FrozenRequest, String> {
    if call.arguments.len() > 128 * 1024 {
        return Err("Shell工具参数超过预算".into());
    }
    let args: Value = serde_json::from_str(&call.arguments).map_err(|_| "Shell参数不是JSON")?;
    let args = args.as_object().ok_or("Shell参数必须是对象")?;
    let bash = call.name == "bash";
    if !bash && call.name != "run_shell" {
        return Err("未知Shell工具".into());
    }
    let allowed: &[&str] = if bash {
        &["command", "observe_paths"]
    } else {
        &["command", "observe_paths", "mode", "shell"]
    };
    if args.keys().any(|key| !allowed.contains(&key.as_str())) {
        return Err("Shell参数包含未知字段".into());
    }
    let command = args
        .get("command")
        .and_then(Value::as_str)
        .filter(|value| {
            !value.trim().is_empty() && value.len() <= 64 * 1024 && !value.contains('\0')
        })
        .ok_or("Shell命令为空或超过64 KiB")?;
    let mode = args
        .get("mode")
        .map(|value| value.as_str().ok_or("Shell mode必须是字符串"))
        .transpose()?
        .unwrap_or("auto");
    let route = if bash {
        Route::AppUid
    } else {
        choose_route(mode, grants)?
    };
    let backend = if bash {
        Backend::Bash
    } else {
        Backend::parse(
            args.get("shell")
                .map(|value| value.as_str().ok_or("Shell选择必须是字符串"))
                .transpose()?
                .unwrap_or("auto"),
        )?
    };
    if !matches!(backend, Backend::Auto | Backend::Bash | Backend::Sh) {
        return Err("run_shell仅接受auto/bash/sh；交互Linux使用terminal_open".into());
    }
    if matches!(route, Route::Shizuku { .. }) && backend == Backend::Bash {
        return Err("Shizuku身份固定使用系统sh，不能静默执行应用Bash".into());
    }
    let plan = environment.resolve(
        if matches!(route, Route::Shizuku { .. }) {
            Backend::Sh
        } else {
            backend
        },
        command,
    )?;
    let mut argv = vec![plan.executable.to_string_lossy().into_owned()];
    argv.extend(plan.arguments);
    let mut variables: Vec<_> = plan
        .environment
        .iter()
        .map(|(name, value)| format!("{name}={value}"))
        .collect();
    let cwd = if matches!(route, Route::Shizuku { .. }) {
        variables = vec![
            "PATH=/system/bin:/system/xbin".into(),
            "HOME=/".into(),
            "TMPDIR=/data/local/tmp".into(),
            "TERM=dumb".into(),
            "LANG=C.UTF-8".into(),
        ];
        PathBuf::from("/")
    } else {
        plan.cwd
    };
    let mut observe_paths = vec![environment.paths().workspace.clone()];
    match args.get("observe_paths") {
        None => (),
        Some(Value::Array(values)) if values.len() <= 32 => {
            for value in values {
                let path = value
                    .as_str()
                    .filter(|path| path.len() <= 4096 && !path.contains('\0'))
                    .ok_or("Shell观察路径无效")?;
                let path = PathBuf::from(path);
                if !path.is_absolute() {
                    return Err("Shell观察路径必须为绝对路径".into());
                }
                observe_paths.push(path);
            }
        }
        _ => return Err("Shell观察路径不是32项以内数组".into()),
    }
    let request = FrozenRequest {
        request_key,
        argv,
        environment: variables,
        cwd,
        route,
        timeout_ms: 45_000,
        max_output_bytes: 128 * 1024,
        observe_paths,
        root_use: plan.root_use,
    };
    request.validate()?;
    Ok(request)
}
/// Root must perform its ordinary approval/claim before calling this function.
/// It performs one attempt; retry policy and durable run receipts remain root-owned.
pub fn execute(
    request: &FrozenRequest,
    cancel: Arc<AtomicBool>,
    privileged: Option<&dyn PrivilegedExecutor>,
) -> Outcome {
    if let Err(error) = request.validate() {
        return Outcome::not_started(request.route.clone(), error);
    }
    if cancel.load(Ordering::Acquire) {
        let mut result = Outcome::not_started(request.route.clone(), "Shell请求已取消，命令未启动");
        result.cancel_requested = true;
        return result;
    }
    let _feedback = if matches!(request.route, Route::AppUid) || privileged.is_some() {
        Some(crate::platform::input_feedback::begin(request, None))
    } else {
        None
    };
    let observation = Observation::begin(request.observe_paths.clone());
    let mut result = match request.route {
        Route::AppUid => process::execute(request, cancel),
        _ => match privileged {
            Some(executor) => executor.execute(request, cancel),
            None => Outcome::not_started(
                request.route.clone(),
                "冻结的特权身份没有可用执行器，未降级或执行命令",
            ),
        },
    };
    if result.route != request.route {
        result.identity_confirmed = false;
        result.error = Some("执行器返回身份与审批冻结身份不一致".into());
    }
    if result.started {
        result.file_changes = observation.finish();
    } else {
        drop(observation);
    }
    result
}
pub fn definitions() -> Vec<ToolDefinition> {
    vec![ToolDefinition{name:"run_shell".into(),description:"Run one frozen shell attempt with bounded output and a45-second limit. mode requests only granted identities within Settings; auto chooses the highest allowed tier before approval. The response states the actual route and identity confirmation. No privilege downgrade or command replay after Bash failure. Requires approval.".into(),parameters:json!({"type":"object","properties":{"command":{"type":"string"},"mode":{"type":"string","enum":["auto","local","shizuku","root"],"default":"auto"},"shell":{"type":"string","enum":["auto","bash","sh"],"default":"auto"},"observe_paths":{"type":"array","items":{"type":"string"},"maxItems":32}},"required":["command"],"additionalProperties":false})},
        ToolDefinition{name:"bash".into(),description:"One frozen bundled GNU Bash command as the app UID, with a45-second limit. Requires an installed executable and never selects Root/Shizuku or replays through sh. Requires approval.".into(),parameters:json!({"type":"object","properties":{"command":{"type":"string"},"observe_paths":{"type":"array","items":{"type":"string"},"maxItems":32}},"required":["command"],"additionalProperties":false})}]
}
