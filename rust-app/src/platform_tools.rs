//! UI/system tool caller. Prepared holds the exact screen, connection and
//! component selected before approval. Execution never selects a replacement.
pub mod automation_page;
mod execution;
pub mod shizuku_executor;
mod ui_tree;
use crate::{
    network::CancelToken,
    platform::{
        self,
        automation::{Action, Target},
        permissions::OperationKey,
    },
    shell_runner::{FrozenRequest, PrivilegedExecutor, Route},
    state::ToolCall,
    transcript_redaction,
};
use serde_json::{Map, Value, json};
use std::{
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
};
use suixing_search::rust_protocol::ToolDefinition;
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RoutePolicy {
    AppUid,
    Auto,
    Accessibility,
    Shizuku,
    Root,
}
#[derive(Clone, Debug)]
pub struct SavedRef {
    pub reference: String,
    pub sha256: String,
}
pub trait ScreenshotRepository: Send + Sync {
    fn freeze(&self, reference: &str) -> Result<SavedRef, String>;
    fn read(&self, reference: &SavedRef, max_bytes: usize) -> Result<Vec<u8>, String>;
}
pub struct Context {
    pub route_policy: RoutePolicy,
    pub shizuku: platform::shizuku::Status,
    pub root_generation: Option<u64>,
    pub ceiling: crate::shell_runner::Ceiling,
    /// Explicit approved accessible cwd. It is never replaced with a different
    /// workspace or identity when Shizuku cannot read app-private files.
    pub privileged_cwd: Option<PathBuf>,
    pub privileged: Option<Arc<dyn PrivilegedExecutor>>,
    pub screenshots: Option<Arc<dyn ScreenshotRepository>>,
}
#[derive(Clone)]
enum Operation {
    DeviceInfo,
    OpenUrl(String),
    Accessibility(Action),
    Privileged {
        request: FrozenRequest,
        result: ProcessResult,
    },
    Virtual {
        expected: Option<platform::virtual_screen::Identity>,
        action: platform::virtual_screen::Action,
    },
    VirtualStatus,
    VirtualScreenshot {
        expected: platform::virtual_screen::Identity,
        width: u32,
        quality: u8,
    },
    Clipboard(platform::clipboard::Action),
    Applications {
        query: String,
        limit: usize,
    },
    Launch(platform::applications::Application),
    Saved {
        reference: SavedRef,
        width: u32,
        quality: u8,
    },
    Wait(u64),
}
#[derive(Clone)]
enum ProcessResult {
    Accepted,
    TextAccepted,
    Dump(usize),
    Click { text: String, exact: bool },
    CurrentApp,
    Png { width: u32, quality: u8 },
}
#[derive(Clone)]
pub struct Prepared {
    tool_name: String,
    call_id: String,
    operation_id: String,
    execution_started: Arc<AtomicBool>,
    target: Option<Target>,
    operation: Operation,
    pub approval_summary: String,
    pub transcript_call: ToolCall,
    privileged: Option<Arc<dyn PrivilegedExecutor>>,
    screenshots: Option<Arc<dyn ScreenshotRepository>>,
}
impl std::fmt::Debug for Prepared {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Prepared")
            .field("tool", &self.tool_name)
            .field("target", &self.target)
            .field("approval", &self.approval_summary)
            .field("call", &self.transcript_call)
            .finish()
    }
}
impl Prepared {
    pub fn operation_id(&self) -> &str {
        &self.operation_id
    }
    pub fn call_id(&self) -> &str {
        &self.call_id
    }
    pub(crate) fn virtual_completion_expected(&self) -> bool {
        matches!(self.operation, Operation::Virtual { .. })
    }
    pub(crate) fn is_read_only(&self) -> bool {
        matches!(
            &self.operation,
            Operation::DeviceInfo
                | Operation::Accessibility(
                    Action::Dump { .. } | Action::CurrentApp | Action::Screenshot { .. }
                )
                | Operation::VirtualStatus
                | Operation::VirtualScreenshot { .. }
                | Operation::Clipboard(platform::clipboard::Action::Read)
                | Operation::Applications { .. }
                | Operation::Saved { .. }
                | Operation::Wait(_)
                | Operation::Privileged {
                    result: ProcessResult::Dump(_)
                        | ProcessResult::CurrentApp
                        | ProcessResult::Png { .. },
                    ..
                }
        )
    }
    pub fn target(&self) -> Option<&Target> {
        self.target.as_ref()
    }
}
pub struct Image {
    pub jpeg: Vec<u8>,
    pub width: u32,
    pub height: u32,
}
pub struct Outcome {
    pub tool_name: String,
    /// Raw value belongs only to this model turn, not to an automatic receipt.
    pub model_value: Value,
    pub image: Option<Image>,
    pub transcript_call: ToolCall,
    /// The actor keeps this invocation pending and completes it from the shared
    /// virtual-screen outcome drain. This is never a final model tool result.
    pub platform_pending: Option<OperationKey>,
}
impl std::fmt::Debug for Outcome {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Outcome")
            .field("tool", &self.tool_name)
            .field(
                "value",
                &transcript_redaction::value_for_transcript(&self.tool_name, &self.model_value),
            )
            .field("image_bytes", &self.image.as_ref().map(|i| i.jpeg.len()))
            .finish()
    }
}
impl Outcome {
    pub fn transcript_value(&self, image_reference: Option<&str>) -> Value {
        let mut value =
            transcript_redaction::value_for_transcript(&self.tool_name, &self.model_value);
        if let Some(image) = &self.image {
            if let Some(object) = value.as_object_mut() {
                object.insert(
                    "image".into(),
                    json!({"reference":image_reference,"width":image.width,"height":image.height,
                    "mime":"image/jpeg","inline_payload_omitted":true}),
                );
            }
        }
        value
    }
}
pub fn definitions(saved_screenshots: bool) -> Vec<ToolDefinition> {
    let s = || json!({"type":"string"});
    let n = |default: u64, min: u64, max: u64| json!({"type":"integer","default":default,"minimum":min,"maximum":max});
    let d = |name: &str, description: &str, properties: Value, required: &[&str]| ToolDefinition {
        name: name.into(),
        description: description.into(),
        parameters: json!({"type":"object","properties":properties,"required":required,"additionalProperties":false}),
    };
    let mut shot = json!({"max_width":n(1080,320,2160),"quality":n(72,1,100)});
    if saved_screenshots {
        shot["path"] = s();
    }
    vec![
        d(
            "ui_dump",
            "Read interactive elements on the selected screen. The screen and route are fixed before approval. Private virtual-display nodes need accessibility; privileged main-screen XML reads use their prepared route.",
            json!({"max_nodes":n(140,10,500)}),
            &[],
        ),
        d(
            "ui_screenshot",
            "Capture the fixed selected screen and return its image. Virtual-screen capture uses its ImageReader without accessibility. Saved path is available only when a screenshot repository is registered.",
            shot,
            &[],
        ),
        d(
            "ui_tap",
            "Tap pixel coordinates on the fixed selected display; a privileged exit confirms acceptance, while accessibility waits for the gesture callback.",
            json!({"x":n(0,0,32767),"y":n(0,0,32767)}),
            &["x", "y"],
        ),
        d(
            "ui_long_press",
            "Long-press pixel coordinates for800ms on the fixed selected display.",
            json!({"x":n(0,0,32767),"y":n(0,0,32767)}),
            &["x", "y"],
        ),
        d(
            "ui_swipe",
            "Swipe on the fixed selected display. Duration60–5000ms, default300ms.",
            json!({"x1":n(0,0,32767),"y1":n(0,0,32767),"x2":n(0,0,32767),"y2":n(0,0,32767),"duration":n(300,60,5000)}),
            &["x1", "y1", "x2", "y2"],
        ),
        d(
            "ui_click_text",
            "Click a matching text, description or hint on the fixed display. Virtual-screen text location requires accessibility. A privileged main-screen lookup refuses ambiguous matches.",
            json!({"text":s(),"exact":{"type":"boolean","default":false}}),
            &["text"],
        ),
        d(
            "ui_type",
            "Write the fixed text to an editable field. Accessibility supports Unicode and reports readback verification separately. Privileged input supports printable ASCII and cannot append. Sensitive arguments are omitted from persistent history.",
            json!({"text":s(),"append":{"type":"boolean","default":false}}),
            &["text"],
        ),
        d(
            "ui_type_into",
            "Locate the labeled editable field and write text on the fixed display through accessibility. Accepted protected text is not reported as readback-verified. Sensitive arguments are omitted from persistent history.",
            json!({"label":s(),"text":s()}),
            &["label", "text"],
        ),
        d(
            "ui_key",
            "Send back, home, recents or notifications. Virtual keys require the fixed privileged input-d route; virtual notifications are unavailable.",
            json!({"key":{"type":"string","enum":["back","home","recents","notifications"]}}),
            &["key"],
        ),
        d(
            "ui_current_app",
            "Read the foreground package on the fixed display. Virtual display identification requires accessibility; unrecognized privileged display output fails explicitly.",
            json!({}),
            &[],
        ),
        d(
            "ui_wait",
            "Wait100–30000ms with cancellation checks, default1000ms.",
            json!({"ms":n(1000,100,30000)}),
            &[],
        ),
        d(
            "ui_virtual_screen",
            "Control the app-owned private display: status, open, preview, close, screenshot or open_app. Preview hiding keeps its surface and apps running. Identity is fixed before approval; private third-party launch requires an explicit root route.",
            json!({"action":{"type":"string","enum":["status","open","preview","close","screenshot","open_app"]},"preview":{"type":"boolean","default":true},"visible":{"type":"boolean"},"target":s(),"display_id":n(1,1,2147483647),"max_width":n(1080,320,2160),"quality":n(72,1,100)}),
            &["action"],
        ),
        d(
            "clipboard_read",
            "Read directly copied clipboard text while JAgent is foreground. The actual value reaches only this model turn and is omitted from persistent history.",
            json!({}),
            &[],
        ),
        d(
            "clipboard_write",
            "Write text to Android clipboard while JAgent is foreground and mark it sensitive. The original arguments are omitted from persistent history.",
            json!({"text":s()}),
            &["text"],
        ),
        d(
            "list_apps",
            "List launchable apps by label, package and component. Query filters results; maximum300. Android package visibility limits are reported without inventing apps.",
            json!({"query":s(),"limit":n(80,1,300)}),
            &[],
        ),
        d(
            "open_app",
            "Resolve a package, app label or package/Activity once, then open its exact component on the prepared display. Private third-party virtual launches need an explicit root route.",
            json!({"target":s()}),
            &["target"],
        ),
        d(
            "device_info",
            "Read actual Android device, display, local time, battery, storage and application version facts. Unavailable values remain unknown.",
            json!({}),
            &[],
        ),
        d(
            "open_url",
            "Hand the approved URL to Android's browser or matching app. Reports accepted launch only, without claiming the page loaded or changing to another URL.",
            json!({"url":s()}),
            &["url"],
        ),
    ]
}
fn text(
    args: &Map<String, Value>,
    key: &str,
    maximum: usize,
    empty: bool,
) -> Result<String, String> {
    args.get(key)
        .and_then(Value::as_str)
        .filter(|s| (empty || !s.trim().is_empty()) && s.len() <= maximum && !s.contains('\0'))
        .map(str::to_owned)
        .ok_or_else(|| format!("参数{key}为空、格式错误或超出预算"))
}
fn number(
    args: &Map<String, Value>,
    key: &str,
    default: u64,
    minimum: u64,
    maximum: u64,
) -> Result<u64, String> {
    args.get(key)
        .map(|v| {
            v.as_u64()
                .filter(|n| (*n >= minimum) && (*n <= maximum))
                .ok_or_else(|| format!("参数{key}超出范围"))
        })
        .transpose()
        .map(|v| v.unwrap_or(default))
}
fn boolean(args: &Map<String, Value>, key: &str, default: bool) -> Result<bool, String> {
    args.get(key)
        .map(|v| v.as_bool().ok_or_else(|| format!("参数{key}必须是布尔值")))
        .transpose()
        .map(|v| v.unwrap_or(default))
}
fn privileged_route(ctx: &Context) -> Result<Route, String> {
    if ctx.privileged.is_none() || ctx.privileged_cwd.is_none() {
        return Err("所选特权执行适配或可访问工作目录未接入".into());
    }
    let grants = crate::shell_runner::Grants {
        shizuku: if ctx.shizuku.connected && ctx.shizuku.granted {
            Some(ctx.shizuku.generation)
        } else {
            None
        },
        root: ctx.root_generation,
        ceiling: ctx.ceiling,
    };
    let mode = match ctx.route_policy {
        RoutePolicy::AppUid => "local",
        RoutePolicy::Accessibility => return Err("固定为无障碍；该操作没有无障碍实现".into()),
        RoutePolicy::Shizuku => "shizuku",
        RoutePolicy::Root => "root",
        RoutePolicy::Auto => "auto",
    };
    let route = crate::shell_runner::choose_route(mode, &grants)?;
    if matches!(route, Route::AppUid) {
        return Err("当前设置和授权没有允许特权UI身份".into());
    }
    Ok(route)
}
fn request(
    ctx: &Context,
    call: &ToolCall,
    argv: Vec<String>,
    maximum: usize,
) -> Result<FrozenRequest, String> {
    Ok(FrozenRequest {
        request_key: call.id.clone(),
        argv,
        environment: Vec::new(),
        cwd: ctx.privileged_cwd.clone().ok_or("特权工作目录未固定")?,
        route: privileged_route(ctx)?,
        timeout_ms: 10000,
        max_output_bytes: maximum,
        observe_paths: Vec::new(),
        root_use: None,
    })
}
fn input(target: &Target, args: Vec<String>) -> Vec<String> {
    let mut argv = vec![
        "/system/bin/input".into(),
        "-d".into(),
        target.display_id.to_string(),
    ];
    argv.extend(args);
    argv
}
fn can_accessibility(ctx: &Context, target: &Target) -> bool {
    matches!(
        ctx.route_policy,
        RoutePolicy::Auto | RoutePolicy::Accessibility
    ) && target.service_generation > 0
        && (target.display_id == 0 || platform::automation::capabilities().sdk_int >= 30)
}
pub fn prepare(call: &ToolCall, ctx: &Context) -> Result<Prepared, String> {
    OperationKey {
        id: call.id.clone(),
        conversation_id: None,
        generation: 1,
    }
    .validate()?;
    let operation_id = crate::controller::unique_id();
    // Provider call IDs pair model messages only. Android callback ownership and
    // privileged process keys use an application-created identity per preparation.
    let effect_call = ToolCall {
        id: operation_id.clone(),
        name: call.name.clone(),
        arguments: call.arguments.clone(),
    };
    if call.arguments.len() > 512 * 1024 {
        return Err("平台工具参数超过512KiB".into());
    }
    let value: Value = serde_json::from_str(&call.arguments).map_err(|_| "平台工具参数不是JSON")?;
    let args = value.as_object().ok_or("平台工具参数必须是对象")?;
    let allowed: &[&str] = match call.name.as_str() {
        "ui_dump" => &["max_nodes"],
        "ui_screenshot" => &["max_width", "quality", "path"],
        "ui_tap" | "ui_long_press" => &["x", "y"],
        "ui_swipe" => &["x1", "y1", "x2", "y2", "duration"],
        "ui_click_text" => &["text", "exact"],
        "ui_type" => &["text", "append"],
        "ui_type_into" => &["label", "text"],
        "ui_key" => &["key"],
        "ui_current_app" | "clipboard_read" | "device_info" => &[],
        "ui_wait" => &["ms"],
        "ui_virtual_screen" => &[
            "action",
            "preview",
            "visible",
            "target",
            "display_id",
            "max_width",
            "quality",
        ],
        "clipboard_write" => &["text"],
        "list_apps" => &["query", "limit"],
        "open_app" => &["target"],
        "open_url" => &["url"],
        _ => return Err("平台工具名未实现".into()),
    };
    if args.keys().any(|k| !allowed.contains(&k.as_str())) {
        return Err("平台工具含未支持参数".into());
    }
    let mut target = None;
    let operation = match call.name.as_str() {
        "device_info" => Operation::DeviceInfo,
        "open_url" => {
            let url = text(args, "url", 8192, false)?.trim().to_owned();
            reqwest::Url::parse(&url).map_err(|_| "打开地址必须包含有效URL协议")?;
            Operation::OpenUrl(url)
        }
        "ui_wait" => Operation::Wait(number(args, "ms", 1000, 100, 30000)?),
        "clipboard_read" => Operation::Clipboard(platform::clipboard::Action::Read),
        "clipboard_write" => Operation::Clipboard(platform::clipboard::Action::Write {
            text: text(args, "text", 256 * 1024, true)?,
        }),
        "list_apps" => Operation::Applications {
            query: if args.contains_key("query") {
                text(args, "query", 1024, true)?
            } else {
                String::new()
            },
            limit: number(args, "limit", 80, 1, 300)? as usize,
        },
        "ui_virtual_screen" => execution::prepare_virtual(&effect_call, args, ctx, &mut target)?,
        "open_app" => {
            let screen = platform::automation::selected_target()?;
            let app = platform::applications::resolve(&text(args, "target", 512, false)?)?;
            target = Some(screen.clone());
            execution::prepare_launch(&effect_call, ctx, &screen, app)?
        }
        "ui_screenshot" if args.contains_key("path") => {
            let repo = ctx
                .screenshots
                .as_ref()
                .ok_or("历史截图存储未接入，未读取其它路径")?;
            Operation::Saved {
                reference: repo.freeze(&text(args, "path", 4096, false)?)?,
                width: number(args, "max_width", 1080, 320, 2160)? as u32,
                quality: number(args, "quality", 72, 1, 100)? as u8,
            }
        }
        _ => {
            let screen = platform::automation::selected_target()?;
            target = Some(screen.clone());
            let coordinate = |key| {
                number(
                    args,
                    key,
                    u64::MAX,
                    0,
                    if key == "x" || key == "x1" || key == "x2" {
                        screen.width as u64 - 1
                    } else {
                        screen.height as u64 - 1
                    },
                )
                .and_then(|v| {
                    if v == u64::MAX {
                        Err(format!("缺少参数{key}"))
                    } else {
                        Ok(v as f32)
                    }
                })
            };
            let action = match call.name.as_str() {
                "ui_dump" => Action::Dump {
                    max_nodes: number(args, "max_nodes", 140, 10, 500)? as usize,
                },
                "ui_screenshot" => Action::Screenshot {
                    max_width: number(args, "max_width", 1080, 320, 2160)? as u32,
                    quality: number(args, "quality", 72, 1, 100)? as u8,
                },
                "ui_tap" | "ui_long_press" => Action::Tap {
                    x: coordinate("x")?,
                    y: coordinate("y")?,
                    duration_ms: if call.name == "ui_long_press" {
                        800
                    } else {
                        60
                    },
                },
                "ui_swipe" => Action::Swipe {
                    x1: coordinate("x1")?,
                    y1: coordinate("y1")?,
                    x2: coordinate("x2")?,
                    y2: coordinate("y2")?,
                    duration_ms: number(args, "duration", 300, 60, 5000)?,
                },
                "ui_click_text" => Action::ClickText {
                    text: text(args, "text", 4096, false)?,
                    exact: boolean(args, "exact", false)?,
                },
                "ui_type" => Action::Type {
                    text: text(args, "text", 256 * 1024, false)?,
                    append: boolean(args, "append", false)?,
                },
                "ui_type_into" => Action::TypeInto {
                    label: text(args, "label", 4096, false)?,
                    text: text(args, "text", 256 * 1024, false)?,
                },
                "ui_current_app" => Action::CurrentApp,
                "ui_key" => Action::Global {
                    action: match text(args, "key", 32, false)?.as_str() {
                        "back" => platform::automation::GlobalAction::Back,
                        "home" => platform::automation::GlobalAction::Home,
                        "recents" => platform::automation::GlobalAction::Recents,
                        "notifications" => platform::automation::GlobalAction::Notifications,
                        _ => return Err("按键名未支持".into()),
                    },
                },
                _ => return Err("平台工具动作未支持".into()),
            };
            execution::prepare_action(&effect_call, ctx, &screen, action)?
        }
    };
    let scope = target
        .as_ref()
        .map(|t| {
            format!(
                "display_id={}，屏幕代次={:?}，无障碍代次={}，{}×{}",
                t.display_id, t.display_generation, t.service_generation, t.width, t.height
            )
        })
        .unwrap_or("系统服务或等待".into());
    let route = match &operation {
        Operation::Privileged { request, .. } => format!("{:?}", request.route),
        Operation::Accessibility(_) => "无障碍".into(),
        Operation::Launch(_) => "应用UID框架启动".into(),
        _ => "固定平台适配".into(),
    };
    Ok(Prepared {
        tool_name: call.name.clone(),
        call_id: call.id.clone(),
        operation_id,
        execution_started: Arc::new(AtomicBool::new(false)),
        target,
        operation,
        approval_summary: format!("{} · {scope} · {route}", call.name),
        transcript_call: transcript_redaction::call_for_transcript(call),
        privileged: ctx.privileged.clone(),
        screenshots: ctx.screenshots.clone(),
    })
}
pub fn execute(prepared: &Prepared, operation: OperationKey, cancel: Arc<CancelToken>) -> Outcome {
    let result = if operation.id != prepared.operation_id {
        Err("平台工具操作与已批准调用不一致".into())
    } else if prepared
        .execution_started
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        Err("平台工具准备对象已执行或正在执行，未重复派发".into())
    } else {
        operation
            .validate()
            .and_then(|_| execution::execute(prepared, operation, cancel))
    };
    let (model_value, image, platform_pending) =
        result.unwrap_or_else(|error| (json!({"ok":false,"error":error}), None, None));
    Outcome {
        tool_name: prepared.tool_name.clone(),
        model_value,
        image,
        transcript_call: prepared.transcript_call.clone(),
        platform_pending,
    }
}
pub fn finish_virtual(
    prepared: &Prepared,
    expected: &OperationKey,
    outcome: platform::virtual_screen::Outcome,
) -> Result<Outcome, String> {
    if outcome.operation != *expected
        || expected.id != prepared.operation_id
        || !prepared.virtual_completion_expected()
    {
        return Err("虚拟屏终态不属于此工具调用代次".into());
    }
    let value = match outcome.result {
        Ok(status) if outcome.cancelled => {
            json!({"ok":false,"cancelled":true,"effect_unknown":true,"status":execution::virtual_status(&status),"error":"副屏操作已取消，请检查实际资源状态后决定后续动作"})
        }
        Ok(status) => {
            let current = platform::virtual_screen::status();
            if status.identity != current.identity || status.closing != current.closing {
                json!({"ok":false,"effect_unknown":true,"error":"副屏终态所属资源在回调后改变，未把新副屏当原操作结果"})
            } else {
                execution::virtual_status(&status)
            }
        }
        Err(error) => json!({"ok":false,"error":error,"cancelled":outcome.cancelled}),
    };
    Ok(Outcome {
        tool_name: prepared.tool_name.clone(),
        model_value: value,
        image: None,
        transcript_call: prepared.transcript_call.clone(),
        platform_pending: None,
    })
}
