//! Additive preparation for the old shell parameter names and explicit packaged
//! environments. Root still owns approval, the one attempt and durable receipts.
use crate::{
    shell_environment::{Backend, ShellEnvironment},
    shell_runner::{FrozenRequest, Grants, Route, choose_route},
    state::ToolCall,
};
use serde_json::{Value, json};
use std::path::PathBuf;
use suixing_search::rust_protocol::ToolDefinition;

#[derive(Clone, Debug)]
pub struct PreparedShell {
    pub request: FrozenRequest,
    pub requested_backend: Backend,
    pub effective_backend: Backend,
    pub legacy_selector_ignored: bool,
    pub guest_root: bool,
    /// Show before approval. It describes the already frozen identity and
    /// compatibility interpretation, never a post-failure retry policy.
    pub notices: Vec<String>,
}
pub fn prepare_tool(
    call: &ToolCall,
    environment: &ShellEnvironment,
    grants: &Grants,
    request_key: String,
) -> Result<FrozenRequest, String> {
    Ok(prepare_tool_detailed(call, environment, grants, request_key)?.request)
}
pub fn prepare_tool_detailed(
    call: &ToolCall,
    environment: &ShellEnvironment,
    grants: &Grants,
    request_key: String,
) -> Result<PreparedShell, String> {
    if call.arguments.len() > 128 * 1024 {
        return Err("Shell工具参数超过128 KiB预算".into());
    }
    let args: Value = serde_json::from_str(&call.arguments).map_err(|_| "Shell参数不是JSON")?;
    let args = args.as_object().ok_or("Shell参数必须是对象")?;
    let bash = match call.name.as_str() {
        "bash" => true,
        "run_shell" => false,
        _ => return Err("未知Shell工具".into()),
    };
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
    // Keep command bytes intact. Normalize only the old enum parameters, as the
    // original ShellTools did; this does not alter a script or invoke a probe.
    let mode = args
        .get("mode")
        .map(|value| value.as_str().ok_or("Shell mode必须是字符串"))
        .transpose()?
        .unwrap_or("auto")
        .trim()
        .to_ascii_lowercase();
    let requested_backend = if bash {
        Backend::Bash
    } else {
        let shell = args
            .get("shell")
            .map(|value| value.as_str().ok_or("Shell选择必须是字符串"))
            .transpose()?
            .unwrap_or("auto")
            .trim()
            .to_ascii_lowercase();
        Backend::parse(&shell)?
    };
    let guest_root = matches!(requested_backend, Backend::Alpine | Backend::Debian);
    let mut notices = Vec::new();
    let route = if bash {
        Route::AppUid
    } else if guest_root {
        match mode.as_str(){
            "auto"|"local"=>{
                notices.push("所选Linux环境通过应用UID的PRoot运行；guest root不是Android Root，身份已在审批前固定为App UID".into());
                Route::AppUid
            }
            "root"|"shizuku"=>return Err("已安装的Linux rootfs固定属于App UID；不能将guest root冒充Android Root或Shizuku身份".into()),
            _=>return Err("未知Shell身份请求".into()),
        }
    } else {
        choose_route(&mode, grants)?
    };
    // In the original schema shell selected only the app tier; Shizuku always
    // used system sh even when an otherwise valid legacy shell=bash was supplied.
    // The new explicit BusyBox selector is effective at every supported tier,
    // without a permission probe or fallback if the remote UID cannot execute it.
    let legacy_selector_ignored = (matches!(route, Route::Shizuku { .. })
        && requested_backend == Backend::Bash)
        || (matches!(route, Route::Root { .. })
            && matches!(requested_backend, Backend::Sh | Backend::Bash));
    let resolve_backend = if matches!(route, Route::Root { .. })
        && matches!(
            requested_backend,
            Backend::Auto | Backend::Sh | Backend::Bash
        ) {
        Backend::Auto
    } else if matches!(route, Route::Shizuku { .. })
        && matches!(
            requested_backend,
            Backend::Auto | Backend::Bash | Backend::Sh
        )
    {
        Backend::Sh
    } else {
        requested_backend
    };
    if legacy_selector_ignored && matches!(route, Route::Shizuku { .. }) {
        notices.push("旧shell=bash参数限定app tier；当前冻结身份为Shizuku，沿旧合法参数语义使用系统sh，实际解释器在本审批中明确显示".into());
    }
    if legacy_selector_ignored && matches!(route, Route::Root { .. }) {
        notices.push("旧auto/bash/sh选择限定app tier；冻结Root身份沿旧行为优先已安装随包Bash，缺少时在命令启动前选择系统sh，实际解释器在本审批中显示".into());
    }
    let plan = environment.resolve(resolve_backend, command)?;
    let effective_backend = plan.backend;
    if requested_backend == Backend::Busybox {
        notices.push("解释器固定为随包BusyBox sh；是否能由冻结UID执行由本次启动结果报告，失败不改用其他Shell或身份".into());
    }
    let mut argv = vec![plan.executable.to_string_lossy().into_owned()];
    argv.extend(plan.arguments);
    let (cwd, variables) = if matches!(route, Route::Shizuku { .. }) {
        let mut variables = vec![
            "PATH=/system/bin:/system/xbin".into(),
            "HOME=/".into(),
            "TMPDIR=/data/local/tmp".into(),
            "TERM=dumb".into(),
            "LANG=C.UTF-8".into(),
        ];
        if effective_backend == Backend::Busybox
            && let Some(busybox) = plan.environment.get("BB")
        {
            variables.push(format!("BB={busybox}"));
        }
        (PathBuf::from("/"), variables)
    } else {
        (
            plan.cwd,
            plan.environment
                .iter()
                .map(|(name, value)| format!("{name}={value}"))
                .collect(),
        )
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
        _ => return Err("Shell观察路径必须是32项以内数组".into()),
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
    Ok(PreparedShell {
        request,
        requested_backend,
        effective_backend,
        legacy_selector_ignored,
        guest_root,
        notices,
    })
}
pub fn definitions() -> Vec<ToolDefinition> {
    vec![ToolDefinition {name:"run_shell".into(),description:"Run one frozen shell attempt with bounded output and a 45-second limit. mode requests only granted identities within Settings; selection is fixed before approval, with no privilege downgrade or command replay after failure. shell=auto/bash/sh preserves the original fields; busybox selects bundled BusyBox sh. alpine/debian (also linux:alpine/linux:debian) select already installed App-UID PRoot environments; their guest root is not Android Root. Linux accepts only mode=auto/local and never auto-installs. The legacy auto/bash/sh selector remains app-tier-only: Root prefers installed bundled Bash or chooses system sh before launch, and Shizuku uses system sh. The actual interpreter is disclosed before approval. Requires approval.".into(),parameters:json!({"type":"object","properties":{"command":{"type":"string"},"mode":{"type":"string","enum":["auto","local","shizuku","root"],"default":"auto"},"shell":{"type":"string","enum":["auto","bash","sh","busybox","alpine","debian","linux:alpine","linux:debian"],"default":"auto"},"observe_paths":{"type":"array","items":{"type":"string"},"maxItems":32}},"required":["command"],"additionalProperties":false})},
    ToolDefinition {name:"bash".into(),description:"One frozen bundled GNU Bash command as the app UID with a 45-second limit. No Root/Shizuku selection, interpreter fallback or command replay. Use terminal tools for interactive sessions. Requires approval.".into(),parameters:json!({"type":"object","properties":{"command":{"type":"string"},"observe_paths":{"type":"array","items":{"type":"string"},"maxItems":32}},"required":["command"],"additionalProperties":false})}]
}
