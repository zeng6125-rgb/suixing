//! Tool advertisements and caller-frozen terminal operations. Caller, request
//! identity, geometry and raw-log preference come only from the trusted runtime.
//! Root still owns approval, durable tool claims, service readiness and receipts.
use crate::{
    shell_environment::Backend,
    state::ToolCall,
    terminal::{Caller, SessionInfo, StartSpec, TerminalBridge},
    vt::{Geometry, Input, Modifiers},
};
use serde_json::{Map, Value, json};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use suixing_search::rust_protocol::ToolDefinition;

#[derive(Clone, Debug)]
pub enum Operation {
    Open {
        backend: Backend,
        geometry: Geometry,
        logging: bool,
    },
    List,
    Read {
        session_id: String,
        cursor: Option<u64>,
        max_bytes: usize,
    },
    Write {
        session_id: String,
        text: String,
    },
    Interrupt {
        session_id: String,
    },
    Close {
        session_id: String,
    },
}
#[derive(Clone, Debug)]
pub struct Prepared {
    pub operation: Operation,
    caller: Caller,
    request_key: String,
}
impl Prepared {
    pub fn caller(&self) -> &Caller {
        &self.caller
    }
    pub fn request_key(&self) -> &str {
        &self.request_key
    }
    pub fn opens_session(&self) -> bool {
        matches!(self.operation, Operation::Open { .. })
    }
}
fn string(args: &Map<String, Value>, name: &str) -> Result<String, String> {
    args.get(name)
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty() && value.len() <= 128 && !value.contains('\0'))
        .map(str::to_owned)
        .ok_or_else(|| format!("终端参数{name}缺失或无效"))
}
/// Freeze only a caller the parent runtime has already authenticated. Models
/// cannot select ownership, run ID, process token, request key or log preference.
pub fn prepare(
    call: &ToolCall,
    caller: Caller,
    request_key: String,
    geometry: Geometry,
    logging: bool,
) -> Result<Prepared, String> {
    if [&caller.chat_id, &caller.run_id, &request_key]
        .iter()
        .any(|value| value.is_empty() || value.len() > 1024 || value.contains('\0'))
    {
        return Err("终端工具缺少可信会话、运行或调用身份".into());
    }
    if call.arguments.len() > 128 * 1024 {
        return Err("终端工具参数超过预算".into());
    }
    let args: Value = serde_json::from_str(&call.arguments).map_err(|_| "终端参数不是JSON")?;
    let args = args.as_object().ok_or("终端参数必须是对象")?;
    let allowed: &[&str] = match call.name.as_str() {
        "terminal_open" => &["environment"],
        "terminal_list" => &[],
        "terminal_read" => &["session_id", "cursor", "max_bytes"],
        "terminal_write" => &["session_id", "text", "append_newline"],
        "terminal_interrupt" | "terminal_close" => &["session_id"],
        _ => return Err("未知终端工具".into()),
    };
    if args.keys().any(|key| !allowed.contains(&key.as_str())) {
        return Err("终端参数包含未知字段，调用身份不能由模型参数提供".into());
    }
    let operation = match call.name.as_str() {
        "terminal_open" => {
            let environment = args
                .get("environment")
                .map(|value| value.as_str().ok_or("终端environment必须是字符串"))
                .transpose()?
                .unwrap_or("android");
            let backend = match environment {
                "android" => Backend::Auto,
                "alpine" => Backend::Alpine,
                "debian" => Backend::Debian,
                _ => return Err("未知交互式终端环境".into()),
            };
            geometry.validate()?;
            Operation::Open {
                backend,
                geometry,
                logging,
            }
        }
        "terminal_list" => Operation::List,
        "terminal_read" => {
            let cursor = args
                .get("cursor")
                .map(|value| value.as_u64().ok_or("终端cursor必须是非负整数"))
                .transpose()?;
            let max = args
                .get("max_bytes")
                .map(|value| value.as_u64().ok_or("终端max_bytes必须是整数"))
                .transpose()?
                .unwrap_or(16 * 1024);
            if !(1..=16 * 1024).contains(&max) {
                return Err("终端读取必须在1至16 KiB内".into());
            }
            Operation::Read {
                session_id: string(args, "session_id")?,
                cursor,
                max_bytes: max as usize,
            }
        }
        "terminal_write" => {
            let text = args
                .get("text")
                .and_then(Value::as_str)
                .ok_or("缺少终端text")?;
            let append = args
                .get("append_newline")
                .map(|value| value.as_bool().ok_or("终端append_newline必须是布尔值"))
                .transpose()?
                .unwrap_or(false);
            if text
                .len()
                .checked_add(usize::from(append))
                .is_none_or(|size| size > 16 * 1024)
            {
                return Err("终端输入包含追加回车最多16 KiB".into());
            }
            let mut text = text.to_owned();
            if append {
                text.push('\r');
            }
            Operation::Write {
                session_id: string(args, "session_id")?,
                text,
            }
        }
        "terminal_interrupt" => Operation::Interrupt {
            session_id: string(args, "session_id")?,
        },
        "terminal_close" => Operation::Close {
            session_id: string(args, "session_id")?,
        },
        _ => unreachable!("checked tool name"),
    };
    Ok(Prepared {
        operation,
        caller,
        request_key,
    })
}
fn environment(backend: Backend) -> &'static str {
    match backend {
        Backend::Alpine => "alpine",
        Backend::Debian => "debian",
        _ => "android",
    }
}
fn entry(info: &SessionInfo) -> Value {
    json!({"session_id":info.id,"environment":environment(info.backend),"name":info.name,
        "shell":info.shell,"cwd":info.cwd,"identity":"Android App UID","state":info.phase,
        "started":info.started,"exit_code":info.exit_code,"note":info.note})
}
fn authorized(bridge: &TerminalBridge, caller: &Caller, id: &str) -> Result<SessionInfo, String> {
    bridge
        .sessions_for(caller)
        .into_iter()
        .find(|info| info.id == id)
        .ok_or_else(|| "终端未授权当前任务、已被用户接管或读取缓存不再保留".into())
}
/// Compatibility caller with a borrowed cancellation flag. New runtime callers
/// must use execute_scoped so cancellation remains attached to queued inputs.
pub fn execute(
    prepared: &Prepared,
    bridge: &TerminalBridge,
    cancel: &AtomicBool,
) -> Result<Value, String> {
    execute_inner(prepared, bridge, cancel, None)
}
/// The parent runtime sets this flag only when the real parent/child scope
/// ends, is cancelled or becomes invalid. Tool return does not cancel a PTY or
/// retire the queued-input flag. The PTY actor owns the real FGS readiness gate.
pub fn execute_scoped(
    prepared: &Prepared,
    bridge: &TerminalBridge,
    cancel: Arc<AtomicBool>,
) -> Result<Value, String> {
    execute_inner(prepared, bridge, cancel.as_ref(), Some(cancel.clone()))
}
fn execute_inner(
    prepared: &Prepared,
    bridge: &TerminalBridge,
    cancel: &AtomicBool,
    scope: Option<Arc<AtomicBool>>,
) -> Result<Value, String> {
    if cancel.load(Ordering::Acquire) {
        return Err("终端工具已取消，操作未派发".into());
    }
    let caller = &prepared.caller;
    match &prepared.operation {
        Operation::Open {
            backend,
            geometry,
            logging,
        } => {
            let key = bridge.start_session(StartSpec {
                name: String::new(),
                backend: *backend,
                geometry: *geometry,
                request_key: prepared.request_key.clone(),
                owner_chat_id: Some(caller.chat_id.clone()),
                logging: *logging,
            })?;
            let info = bridge
                .sessions_for(caller)
                .into_iter()
                .find(|info| info.key == key)
                .ok_or("此调用已有终端记录，但当前任务没有运行或读取授权，未重放新进程")?;
            Ok(entry(&info))
        }
        Operation::List => Ok(Value::Array(
            bridge.sessions_for(caller).iter().map(entry).collect(),
        )),
        Operation::Read {
            session_id,
            cursor,
            max_bytes,
        } => {
            let info = authorized(bridge, caller, session_id)?;
            let read = bridge.read_output(&info.key, *cursor, *max_bytes, Some(caller))?;
            Ok(
                json!({"session_id":session_id,"new_cursor":read.cursor,"truncated":read.truncated,
                "has_more":read.has_more,"output":read.output,"state":info.phase,
                "exit_code":info.exit_code,"ended":info.note}),
            )
        }
        Operation::Write { session_id, text } => {
            let info = authorized(bridge, caller, session_id)?;
            let input = Input::Text {
                text: text.clone(),
                modifiers: Modifiers::default(),
            };
            match &scope {
                Some(flag) => bridge.input_authorized_scoped(
                    &info.key,
                    input,
                    caller.clone(),
                    flag.clone(),
                )?,
                None => bridge.input_authorized(&info.key, input, caller.clone())?,
            }
            Ok(
                json!({"session_id":session_id,"queued":true,"execution_confirmed":false,
                "notice":"输入已排队，终端执行前仍核对当前任务授权；排队回执不表示命令已执行"}),
            )
        }
        Operation::Interrupt { session_id } => {
            let info = authorized(bridge, caller, session_id)?;
            let input = Input::Text {
                text: "\u{3}".into(),
                modifiers: Modifiers::default(),
            };
            match &scope {
                Some(flag) => bridge.input_authorized_scoped(
                    &info.key,
                    input,
                    caller.clone(),
                    flag.clone(),
                )?,
                None => bridge.input_authorized(&info.key, input, caller.clone())?,
            }
            Ok(
                json!({"session_id":session_id,"interrupt_queued":true,"command_exit_confirmed":false}),
            )
        }
        Operation::Close { session_id } => {
            let info = authorized(bridge, caller, session_id)?;
            bridge.stop_authorized(&info.key, caller)?;
            Ok(
                json!({"session_id":session_id,"stop_requested":true,"session_exit_confirmed":false}),
            )
        }
    }
}
pub fn definitions() -> Vec<ToolDefinition> {
    let session = json!({"type":"string"});
    let session_schema = || {
        json!({"type":"object","properties":{"session_id":session.clone()},
        "required":["session_id"],"additionalProperties":false})
    };
    vec![
        ToolDefinition { name:"terminal_open".into(), description:"Open an interactive App-UID PTY owned by this chat, in Android or an already installed Linux environment. App UID can access this app's private files; Linux guest root is not Android root or a security sandbox. Requires approval.".into(), parameters:json!({"type":"object","properties":{"environment":{"type":"string","enum":["android","alpine","debian"],"default":"android"}},"additionalProperties":false}) },
        ToolDefinition { name:"terminal_list".into(), description:"List only this chat's PTYs and manual sessions explicitly granted to this task. Requires approval.".into(), parameters:json!({"type":"object","properties":{},"additionalProperties":false}) },
        ToolDefinition { name:"terminal_read".into(), description:"Read bounded incremental UTF-8 PTY output; reuse the returned opaque UTF-16 cursor. Reports lost history. Live output cursors are separate from archived raw-log byte cursors. Requires approval.".into(), parameters:json!({"type":"object","properties":{"session_id":session,"cursor":{"type":"integer","minimum":0},"max_bytes":{"type":"integer","minimum":1,"maximum":16384,"default":16384}},"required":["session_id"],"additionalProperties":false}) },
        ToolDefinition { name:"terminal_write".into(), description:"Queue exact input to an authorized PTY, at most 16 KiB including an optional carriage return. Newline may execute commands. Human takeover revokes model input and the actor rechecks the grant before writing. Requires approval.".into(), parameters:json!({"type":"object","properties":{"session_id":{"type":"string"},"text":{"type":"string"},"append_newline":{"type":"boolean","default":false}},"required":["session_id","text"],"additionalProperties":false}) },
        ToolDefinition { name:"terminal_interrupt".into(), description:"Queue Ctrl-C to an authorized terminal's foreground command. A queue receipt does not confirm command exit. Requires approval.".into(), parameters:session_schema() },
        ToolDefinition { name:"terminal_close".into(), description:"Request an authorized PTY and its session processes to stop. Observe terminal state to confirm exit and cleanup. Requires approval.".into(), parameters:session_schema() },
    ]
}
