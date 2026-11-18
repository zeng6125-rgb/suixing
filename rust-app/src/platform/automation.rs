//! Android UI automation. The caller prepares/approves an immutable target and
//! operation, then runs this blocking adapter on its bounded tool worker.
use super::permissions::OperationKey;
use crate::network::CancelToken;
use std::sync::Arc;

#[derive(Clone, Debug, Default)]
pub struct Capabilities {
    pub connected: bool,
    pub service_generation: u64,
    pub sdk_int: i32,
    pub error: Option<String>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Target {
    pub display_id: i32,
    pub service_generation: u64,
    pub width: u32,
    pub height: u32,
    pub display_generation: Option<u64>,
}
#[derive(Clone, Debug)]
pub enum Action {
    Dump {
        max_nodes: usize,
    },
    CurrentApp,
    Tap {
        x: f32,
        y: f32,
        duration_ms: u64,
    },
    Swipe {
        x1: f32,
        y1: f32,
        x2: f32,
        y2: f32,
        duration_ms: u64,
    },
    ClickText {
        text: String,
        exact: bool,
    },
    Type {
        text: String,
        append: bool,
    },
    TypeInto {
        label: String,
        text: String,
    },
    Global {
        action: GlobalAction,
    },
    Screenshot {
        max_width: u32,
        quality: u8,
    },
}
#[derive(Clone, Copy, Debug)]
pub enum GlobalAction {
    Back,
    Home,
    Recents,
    Notifications,
}
impl GlobalAction {
    fn code(self) -> i32 {
        match self {
            Self::Back => 1,
            Self::Home => 2,
            Self::Recents => 3,
            Self::Notifications => 4,
        }
    }
}
#[derive(Clone, Debug)]
pub struct Dump {
    pub text: String,
    pub nodes: usize,
    pub visits: usize,
    pub incomplete: bool,
    pub elapsed_ms: u64,
}
pub struct Screenshot {
    pub jpeg: Vec<u8>,
    pub width: u32,
    pub height: u32,
    pub target: Target,
}
impl std::fmt::Debug for Screenshot {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Screenshot")
            .field("bytes", &self.jpeg.len())
            .field("target", &self.target)
            .finish()
    }
}
#[derive(Debug)]
pub enum ActionResult {
    Dump(Dump),
    CurrentApp(String),
    Completed {
        description: String,
    },
    TextWritten {
        readback_verified: bool,
        description: String,
    },
    Screenshot(Screenshot),
}
#[cfg(target_os = "android")]
pub(crate) mod accessibility;
#[cfg(target_os = "android")]
mod gestures;
#[cfg(target_os = "android")]
mod nodes;
#[cfg(target_os = "android")]
mod screenshots;
#[cfg(target_os = "android")]
pub(crate) use gestures::on_result as on_gesture_result;
#[cfg(target_os = "android")]
pub(crate) use screenshots::on_result as on_screenshot_result;
#[cfg(target_os = "android")]
pub mod overlay;
#[cfg(target_os = "android")]
pub use accessibility::{capabilities, on_service_event, target};
#[cfg(not(target_os = "android"))]
pub fn capabilities() -> Capabilities {
    Capabilities {
        error: Some("当前平台没有Android无障碍服务".into()),
        ..Default::default()
    }
}
#[cfg(not(target_os = "android"))]
pub fn target(_display_id: i32) -> Result<Target, String> {
    Err("当前平台没有Android界面目标".into())
}
/// Preparation pins the currently selected own virtual display, or main display
/// while no virtual display runs. No later execution reselects a new display.
pub fn selected_target() -> Result<Target, String> {
    let screen = crate::platform::virtual_screen::status();
    if screen.closing {
        return Err("虚拟屏仍在关闭，未悄悄转向主屏".into());
    }
    target(screen.identity.map_or(0, |identity| identity.display_id))
}
/// Screen identity validation also serves fixed privileged routes; availability
/// of accessibility is checked separately by accessibility operations.
pub fn verify_target(target: &Target) -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        accessibility::verify_display(target)
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = target;
        Err("当前平台没有Android屏幕目标".into())
    }
}

pub fn execute(
    operation: OperationKey,
    target: Target,
    action: Action,
    cancel: Arc<CancelToken>,
) -> Result<ActionResult, String> {
    operation.validate()?;
    if cancel.is_cancelled() {
        return Err("界面操作已经取消，未派发输入".into());
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (target, action);
        Err("当前平台没有Android界面操作后端".into())
    }
    #[cfg(target_os = "android")]
    {
        if let Action::Screenshot { max_width, quality } = &action {
            if let Some(generation) = target.display_generation {
                let identity = crate::platform::virtual_screen::status()
                    .identity
                    .filter(|i| {
                        i.display_id == target.display_id
                            && i.generation == generation
                            && i.width == target.width
                            && i.height == target.height
                    })
                    .ok_or("虚拟屏截图目标已经变更，未截取其它屏幕")?;
                let shot = crate::platform::virtual_screen::screenshot(
                    &identity, *max_width, *quality, &cancel,
                )?;
                return Ok(ActionResult::Screenshot(Screenshot {
                    jpeg: shot.jpeg,
                    width: shot.width,
                    height: shot.height,
                    target,
                }));
            }
        }
        accessibility::verify(&target)?;
        match action {
            Action::Dump { max_nodes } => {
                let dump = nodes::dump(&target, max_nodes, &cancel)?;
                accessibility::verify(&target)?;
                if cancel.is_cancelled() {
                    return Err("元素读取完成时操作已取消".into());
                }
                Ok(ActionResult::Dump(dump))
            }
            Action::CurrentApp => {
                let package = nodes::current_app(&target)?;
                accessibility::verify(&target)?;
                if cancel.is_cancelled() {
                    return Err("前台识别完成时操作已取消".into());
                }
                Ok(ActionResult::CurrentApp(package))
            }
            Action::Tap { x, y, duration_ms } => {
                gestures::perform(&operation, &target, &[(x, y)], duration_ms, &cancel).map(|_| {
                    ActionResult::Completed {
                        description: "系统已完成指定屏幕上的点击手势".into(),
                    }
                })
            }
            Action::Swipe {
                x1,
                y1,
                x2,
                y2,
                duration_ms,
            } => gestures::perform(
                &operation,
                &target,
                &[(x1, y1), (x2, y2)],
                duration_ms,
                &cancel,
            )
            .map(|_| ActionResult::Completed {
                description: "系统已完成指定屏幕上的滑动手势".into(),
            }),
            Action::ClickText { text, exact } => {
                nodes::click_text(&operation, &target, &text, exact, &cancel).map(|_| {
                    ActionResult::Completed {
                        description: "已在指定屏幕执行匹配元素的点击".into(),
                    }
                })
            }
            Action::Type { text, append } => nodes::type_focused(&target, &text, append, &cancel)
                .map(|verified| ActionResult::TextWritten {
                    readback_verified: verified,
                    description: if verified { "已在指定屏幕输入框写入并核对文本" }
                        else { "字段已接受写入；密码保护、过滤或读取限制使原文未核对，请读屏确认，勿重复追加" }.into(),
                }),
            Action::TypeInto { label, text } => {
                nodes::type_into(&operation, &target, &label, &text, &cancel).map(|verified| {
                    ActionResult::TextWritten {
                        readback_verified: verified,
                        description: if verified { "已定位指定屏幕输入框并核对写入文本" }
                            else { "指定字段已接受写入；原文未核对，请读屏确认，勿重复追加" }.into(),
                    }
                })
            }
            Action::Global { action } => {
                if target.display_id != 0 {
                    return Err(
                        "无障碍全局按键没有副屏参数；请选择固定的Shizuku/root input -d路线".into(),
                    );
                }
                accessibility::global(&target, action, &cancel)?;
                Ok(ActionResult::Completed {
                    description: "系统已接受指定全局按键".into(),
                })
            }
            Action::Screenshot { max_width, quality } => {
                screenshots::capture(&target, max_width, quality, &cancel)
                    .map(ActionResult::Screenshot)
            }
        }
    }
}
