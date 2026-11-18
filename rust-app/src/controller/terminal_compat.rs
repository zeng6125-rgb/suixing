//! Compatibility actions resolve the selected process identity before dispatch.
use super::{
    terminal_runtime::{Request, TerminalRuntime},
    *,
};
pub(super) fn dispatch(runtime: &TerminalRuntime, action: Action) -> Result<(), String> {
    let request_id = unique_id();
    let selected = || runtime.selected().ok_or_else(|| "尚未选择终端".to_owned());
    let request = match action {
        Action::StartTerminal => Request::Create {
            request_id,
            backend: crate::shell_environment::Backend::Auto,
            name: "终端".into(),
            logging: false,
            geometry: Default::default(),
        },
        Action::TerminalInput { session_id, input } => Request::Input {
            request_id,
            key: runtime.legacy_key(session_id)?,
            input,
        },
        Action::TerminalWrite(text) => Request::Input {
            request_id,
            key: selected()?,
            input: crate::vt::Input::Text {
                text,
                modifiers: Default::default(),
            },
        },
        Action::ResizeTerminalFrame {
            session_id,
            geometry,
        } => Request::Resize {
            request_id,
            key: runtime.legacy_key(session_id)?,
            geometry,
        },
        Action::ResizeTerminal { columns, rows } => {
            let mut geometry = runtime
                .snapshot()
                .map(|frame| frame.geometry)
                .unwrap_or_default();
            geometry.columns = columns;
            geometry.rows = rows;
            geometry.version = geometry.version.checked_add(1).ok_or("终端尺寸版本耗尽")?;
            Request::Resize {
                request_id,
                key: selected()?,
                geometry,
            }
        }
        Action::StopTerminal => Request::Stop {
            request_id,
            key: selected()?,
        },
        _ => return Err("不是终端兼容动作".into()),
    };
    match runtime.dispatch(request, None) {
        super::terminal_runtime::Update::Finished { result, .. } => result,
        _ => Ok(()),
    }
}
