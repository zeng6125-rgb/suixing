//! Caller adapters for Page::Automations. The controller owns these commands,
//! persistence and the common permission/virtual-screen outcome drains.
use super::*;
use platform::{
    applications::Application,
    permissions::{PermissionKind, PermissionOutcome},
    virtual_screen::{Frame, Identity, ManualInput, Status},
};
use serde::{Deserialize, Serialize};
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Preferences {
    #[serde(default = "yes", rename = "edgeLight")]
    pub edge_enabled: bool,
    #[serde(default = "yes", rename = "edgeLightInForeground")]
    pub edge_in_foreground: bool,
    pub edge_color: u32,
}
fn yes() -> bool {
    true
}
impl Default for Preferences {
    fn default() -> Self {
        Self {
            edge_enabled: true,
            edge_in_foreground: true,
            edge_color: 0xff8fd6b6,
        }
    }
}
#[derive(Clone, Debug, Default)]
pub struct Indicator {
    pub window_owned: bool,
    pub first_draw: bool,
    pub driving_runs: usize,
    pub error: Option<String>,
}
#[derive(Clone, Debug)]
pub struct Snapshot {
    pub android: bool,
    pub foreground: bool,
    pub accessibility: platform::automation::Capabilities,
    pub shizuku: platform::shizuku::Status,
    pub root_generation: Option<u64>,
    pub virtual_screen: Status,
    pub indicator: Indicator,
    pub notifications: PermissionOutcome,
    pub microphone: PermissionOutcome,
    pub local_network: PermissionOutcome,
}
/// Actor/worker call, never a draw-time query.
pub fn snapshot(root_generation: Option<u64>) -> Snapshot {
    let _ = platform::shizuku::refresh();
    #[cfg(target_os = "android")]
    let (foreground, indicator) = {
        let status = platform::automation::overlay::status();
        (
            platform::android::activity_visible(),
            Indicator {
                window_owned: status.window_owned,
                first_draw: status.first_draw_received,
                driving_runs: status.driving_runs,
                error: status.error,
            },
        )
    };
    #[cfg(not(target_os = "android"))]
    let (foreground, indicator) = (false, Indicator::default());
    Snapshot {
        android: cfg!(target_os = "android"),
        foreground,
        accessibility: platform::automation::capabilities(),
        shizuku: platform::shizuku::status(),
        root_generation,
        virtual_screen: platform::virtual_screen::status(),
        indicator,
        notifications: platform::permissions::check(PermissionKind::Notifications),
        microphone: platform::permissions::check(PermissionKind::Microphone),
        local_network: platform::permissions::check(PermissionKind::LocalNetwork),
    }
}
#[derive(Clone, Debug)]
pub enum CommandKind {
    Refresh,
    Settings(platform::manual_settings::Page),
    RequestPermission(PermissionKind),
    RequestShizuku,
    Virtual(platform::virtual_screen::Action),
    ReadFrame(Identity),
    LoadApplications,
    LaunchApplication {
        expected: Identity,
        component: String,
    },
    Manual(ManualInput),
    SavePreferences(Preferences),
}
#[derive(Clone, Debug)]
pub struct Command {
    pub operation: OperationKey,
    pub kind: CommandKind,
}
pub enum ResultKind {
    Snapshot(Snapshot),
    PendingPlatform(OperationKey),
    Frame(Frame),
    Applications(Vec<Application>),
    PreferencesToSave(Preferences),
    Manual {
        accepted: bool,
        completion_observed: bool,
        message: String,
    },
    Accepted,
}
pub struct Result {
    pub operation: OperationKey,
    pub result: std::result::Result<ResultKind, String>,
}
pub fn apply_saved_preferences(preferences: &Preferences) -> std::result::Result<(), String> {
    #[cfg(target_os = "android")]
    {
        platform::automation::overlay::set_color(preferences.edge_color);
        platform::automation::overlay::configure(platform::automation::overlay::Policy {
            enabled: preferences.edge_enabled,
            show_in_foreground: preferences.edge_in_foreground,
        })
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = preferences;
        Ok(())
    }
}
pub fn execute(command: Command, ctx: &Context, cancel: Arc<CancelToken>) -> Result {
    let operation = command.operation;
    let result=operation.validate().and_then(|_|{
        if cancel.is_cancelled(){return Err("页面操作已取消".into());}
        match command.kind {
            CommandKind::Refresh=>Ok(ResultKind::Snapshot(snapshot(ctx.root_generation))),
            CommandKind::Settings(page)=>platform::manual_settings::open(&operation,page,cancel).map(|_|ResultKind::Accepted),
            CommandKind::RequestPermission(kind)=>platform::permissions::request(operation.clone(),vec![kind]).map(|_|ResultKind::PendingPlatform(operation.clone())),
            CommandKind::RequestShizuku=>platform::shizuku::request_permission(operation.clone()).map(|_|ResultKind::PendingPlatform(operation.clone())),
            CommandKind::Virtual(action)=>platform::virtual_screen::request(operation.clone(),action).map(|_|ResultKind::PendingPlatform(operation.clone())),
            CommandKind::ReadFrame(identity)=>platform::virtual_screen::latest_frame(&identity,true).map(ResultKind::Frame),
            CommandKind::LoadApplications=>platform::applications::list("",300).map(ResultKind::Applications),
            CommandKind::SavePreferences(prefs)=>Ok(ResultKind::PreferencesToSave(prefs)),
            CommandKind::LaunchApplication{expected,component}=>{
                platform::virtual_screen::verify(&expected)?;
                let call=ToolCall{id:operation.id.clone(),name:"ui_virtual_screen".into(),arguments:json!({"action":"open_app","target":component,"display_id":expected.display_id}).to_string()};
                let prepared=prepare(&call,ctx)?;
                verify_expected(&prepared,&expected)?;
                let effect_key=OperationKey {id:prepared.operation_id().into(),conversation_id:operation.conversation_id.clone(),generation:operation.generation};
                finish_manual(super::execute(&prepared,effect_key,cancel))
            }
            CommandKind::Manual(input)=>match input {
                ManualInput::Hide{identity}=>platform::virtual_screen::request(operation.clone(),platform::virtual_screen::Action::Preview{expected:identity,visible:false}).map(|_|ResultKind::PendingPlatform(operation.clone())),
                ManualInput::Close{identity}=>platform::virtual_screen::request(operation.clone(),platform::virtual_screen::Action::Close{expected:identity}).map(|_|ResultKind::PendingPlatform(operation.clone())),
                ManualInput::Tap{identity,x,y,duration_ms}=>{
                    execute_gesture(&operation,ctx,cancel,&identity,Action::Tap{x,y,duration_ms})
                }
                ManualInput::Swipe{identity,x1,y1,x2,y2,duration_ms}=>{
                    execute_gesture(&operation,ctx,cancel,&identity,Action::Swipe{x1,y1,x2,y2,duration_ms})
                }
            },
        }
    });
    Result { operation, result }
}
fn execute_gesture(
    key: &OperationKey,
    ctx: &Context,
    cancel: Arc<CancelToken>,
    expected: &Identity,
    action: Action,
) -> std::result::Result<ResultKind, String> {
    platform::virtual_screen::verify(expected)?;
    let valid_point = |x: f32, y: f32| {
        x.is_finite()
            && y.is_finite()
            && x >= 0.
            && y >= 0.
            && x < expected.width as f32
            && y < expected.height as f32
    };
    let (name, arguments) = match &action {
        Action::Tap { x, y, duration_ms }
            if valid_point(*x, *y) && (30..=5000).contains(duration_ms) =>
        {
            ("ui_tap", json!({"x":x,"y":y,"duration":duration_ms}))
        }
        Action::Swipe {
            x1,
            y1,
            x2,
            y2,
            duration_ms,
        } if valid_point(*x1, *y1)
            && valid_point(*x2, *y2)
            && (30..=5000).contains(duration_ms) =>
        {
            (
                "ui_swipe",
                json!({"x1":x1,"y1":y1,"x2":x2,"y2":y2,"duration":duration_ms}),
            )
        }
        _ => return Err("副屏手势坐标或持续时间无效，未派发输入".into()),
    };
    let target = platform::automation::target(expected.display_id)?;
    let call = ToolCall {
        id: key.id.clone(),
        name: name.into(),
        arguments: arguments.to_string(),
    };
    let mut operation = execution::prepare_action(&call, ctx, &target, action.clone())?;
    // Human-held presses retain their real duration even on the privileged
    // route; input tap has no duration parameter, so use a stationary swipe.
    if let (Action::Tap { x, y, duration_ms }, Operation::Privileged { request, .. }) =
        (&action, &mut operation)
    {
        request.argv = input(
            &target,
            vec![
                "swipe".into(),
                (*x as u32).to_string(),
                (*y as u32).to_string(),
                (*x as u32).to_string(),
                (*y as u32).to_string(),
                duration_ms.to_string(),
            ],
        );
    }
    let prepared = Prepared {
        tool_name: call.name.clone(),
        call_id: call.id.clone(),
        operation_id: call.id.clone(),
        execution_started: Arc::new(AtomicBool::new(false)),
        target: Some(target),
        operation,
        approval_summary: "用户直接操作副屏手势".into(),
        transcript_call: call,
        privileged: ctx.privileged.clone(),
        screenshots: ctx.screenshots.clone(),
    };
    verify_expected(&prepared, expected)?;
    platform::virtual_screen::verify(expected)?;
    finish_manual(super::execute(&prepared, key.clone(), cancel))
}
fn verify_expected(prepared: &Prepared, expected: &Identity) -> std::result::Result<(), String> {
    if !prepared.target().is_some_and(|t| {
        t.display_id == expected.display_id
            && t.display_generation == Some(expected.generation)
            && t.width == expected.width
            && t.height == expected.height
    }) {
        return Err("页面操作所属副屏在准备期间变更".into());
    }
    Ok(())
}
fn finish_manual(result: Outcome) -> std::result::Result<ResultKind, String> {
    if let Some(pending) = result.platform_pending {
        return Ok(ResultKind::PendingPlatform(pending));
    }
    if result.model_value.get("ok").and_then(Value::as_bool) != Some(true) {
        return Err(result
            .model_value
            .get("error")
            .and_then(Value::as_str)
            .unwrap_or("页面操作未完成")
            .into());
    }
    Ok(ResultKind::Manual {
        accepted: true,
        completion_observed: result
            .model_value
            .get("gesture_completion_observed")
            .and_then(Value::as_bool)
            == Some(true),
        message: result
            .model_value
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("系统已接受请求，请查看对应副屏结果")
            .into(),
    })
}
