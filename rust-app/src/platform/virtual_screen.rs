//! Private app-owned display. Creation, close and window work are queued on
//! Android main; frame copying and JPEG encoding never run on that thread.
use super::{attachments::PixelFrame, permissions::OperationKey};
use crate::network::CancelToken;
use std::sync::Arc;

pub const HOST_ACTIVITY: &str = "com.suixing.agent.rust.platform.RustVirtualScreenActivity";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Identity {
    pub display_id: i32,
    pub generation: u64,
    pub width: u32,
    pub height: u32,
    pub density: u32,
}
#[derive(Clone, Debug, Default)]
pub struct Status {
    pub identity: Option<Identity>,
    pub closing: bool,
    pub pending_operations: usize,
    pub has_frame: bool,
    pub last_frame_at_ms: Option<u64>,
    pub preview_requested: bool,
    pub preview_window_owned: bool,
    pub preview_first_draw_received: bool,
    pub error: Option<String>,
}
#[derive(Clone, Debug)]
pub enum Action {
    Open {
        preview: bool,
    },
    OpenChecked {
        preview: bool,
        expected: Option<Identity>,
    },
    Close {
        expected: Identity,
    },
    Preview {
        expected: Identity,
        visible: bool,
    },
    LaunchOwnHost {
        expected: Identity,
    },
    LaunchOwn {
        expected: Identity,
        component: String,
    },
}
#[derive(Clone, Debug)]
pub struct Outcome {
    pub operation: OperationKey,
    pub result: Result<Status, String>,
    pub cancelled: bool,
}
pub struct Frame {
    pub identity: Identity,
    pub pixels: Arc<PixelFrame>,
    pub received_at_ms: u64,
}
pub struct Screenshot {
    pub identity: Identity,
    pub jpeg: Vec<u8>,
    pub width: u32,
    pub height: u32,
    pub received_at_ms: u64,
}
/// Privileged effects remain owned by the caller's approved shell route.
/// This plan pins the display and carries argv instead of shell interpolation.
#[derive(Clone, Debug)]
pub struct PrivilegedPlan {
    pub identity: Identity,
    pub argv: Vec<String>,
}
#[derive(Clone, Debug)]
pub enum ManualInput {
    Tap {
        identity: Identity,
        x: f32,
        y: f32,
        duration_ms: u64,
    },
    Swipe {
        identity: Identity,
        x1: f32,
        y1: f32,
        x2: f32,
        y2: f32,
        duration_ms: u64,
    },
    Hide {
        identity: Identity,
    },
    Close {
        identity: Identity,
    },
}
pub fn launch_plan(expected: Identity, component: &str) -> Result<PrivilegedPlan, String> {
    validate_component(component)?;
    verify(&expected)?;
    Ok(PrivilegedPlan {
        argv: vec![
            "am".into(),
            "start".into(),
            "--display".into(),
            expected.display_id.to_string(),
            "-n".into(),
            component.into(),
        ],
        identity: expected,
    })
}
pub fn key_plan(expected: Identity, key_code: i32) -> Result<PrivilegedPlan, String> {
    if !(0..=400).contains(&key_code) {
        return Err("副屏按键代码无效".into());
    }
    verify(&expected)?;
    Ok(PrivilegedPlan {
        argv: vec![
            "input".into(),
            "-d".into(),
            expected.display_id.to_string(),
            "keyevent".into(),
            key_code.to_string(),
        ],
        identity: expected,
    })
}
fn validate_component(component: &str) -> Result<(), String> {
    if component.len() > 512 || component.contains('\0') {
        return Err("副屏组件参数超出预算".into());
    }
    let (package, activity) = component
        .split_once('/')
        .ok_or("组件必须为package/Activity")?;
    if package.is_empty()
        || activity.is_empty()
        || activity.contains('/')
        || !package
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_')
        || !activity
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_' || b == b'$')
    {
        return Err("副屏组件名称无效".into());
    }
    Ok(())
}

#[cfg(target_os = "android")]
mod display;
#[cfg(target_os = "android")]
mod frames;
#[cfg(target_os = "android")]
mod preview;
#[cfg(target_os = "android")]
pub use display::{cancel, drain_results, request, status, verify};
#[cfg(target_os = "android")]
pub(crate) use display::{on_display_event, on_host_event};
#[cfg(target_os = "android")]
pub(crate) fn on_accessibility_disconnect() {
    let _ = crate::platform::android::post_to_main(|| {
        {
            let mut state = display::registry()
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            if let Some(screen) = state.screen.as_mut() {
                screen.preview_requested = false;
                screen.preview = None;
            }
        }
        if let Err(message) = preview::hide() {
            display::error(message);
        }
    });
}
#[cfg(target_os = "android")]
pub(crate) use frames::on_image;
#[cfg(target_os = "android")]
pub use frames::{latest_frame, screenshot};
#[cfg(target_os = "android")]
pub use preview::drain_inputs;
#[cfg(target_os = "android")]
pub(crate) use preview::{on_draw as on_preview_draw, on_touch as on_preview_touch};

#[cfg(not(target_os = "android"))]
pub fn status() -> Status {
    Status {
        error: Some("当前平台没有Android虚拟屏后端".into()),
        ..Default::default()
    }
}
#[cfg(not(target_os = "android"))]
pub fn request(operation: OperationKey, _action: Action) -> Result<(), String> {
    operation.validate()?;
    Err("当前平台没有Android虚拟屏后端".into())
}
#[cfg(not(target_os = "android"))]
pub fn verify(_identity: &Identity) -> Result<(), String> {
    Err("当前平台没有Android虚拟屏后端".into())
}
#[cfg(not(target_os = "android"))]
pub fn cancel(_id: &str) {}
#[cfg(not(target_os = "android"))]
pub fn drain_results() -> Vec<Outcome> {
    Vec::new()
}
#[cfg(not(target_os = "android"))]
pub fn drain_inputs() -> Vec<ManualInput> {
    Vec::new()
}
#[cfg(not(target_os = "android"))]
pub fn latest_frame(_identity: &Identity, _preview: bool) -> Result<Frame, String> {
    Err("当前平台没有Android虚拟屏帧后端".into())
}
#[cfg(not(target_os = "android"))]
pub fn screenshot(
    _identity: &Identity,
    _max_width: u32,
    _quality: u8,
    _cancel: &CancelToken,
) -> Result<Screenshot, String> {
    Err("当前平台没有Android虚拟屏帧后端".into())
}
