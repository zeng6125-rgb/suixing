//! Shared main/child tool grouping. Each cohort finishes and retains all
//! receipts before the next barrier; shared device/shell effects remain serial.
use crate::{network::CancelToken, state::ToolCall, subagent_configuration::ApprovalMode, tools};
use std::{
    ops::Range,
    sync::{Mutex, MutexGuard, TryLockError},
    time::Duration,
};
static DEVICE: Mutex<()> = Mutex::new(());
static ACTIVE: std::sync::OnceLock<Mutex<std::collections::BTreeMap<u64, (String, String)>>> =
    std::sync::OnceLock::new();
static NEXT_ACTIVITY: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
/// A real admitted tool invocation owns this lease until its receipt returns,
/// including unwind/cancellation. It stores names only, never arguments.
pub(crate) struct Activity(u64);
impl Drop for Activity {
    fn drop(&mut self) {
        if let Some(active) = ACTIVE.get() {
            active
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .remove(&self.0);
        }
    }
}
pub(crate) fn activity(run: &str, name: &str) -> Option<Activity> {
    if run.len() > 128 || name.len() > 128 {
        return None;
    }
    let id = NEXT_ACTIVITY
        .fetch_update(
            std::sync::atomic::Ordering::Relaxed,
            std::sync::atomic::Ordering::Relaxed,
            |id| id.checked_add(1),
        )
        .ok()?;
    let mut active = ACTIVE
        .get_or_init(Default::default)
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    if active.len() >= 4096 {
        return None;
    }
    active.insert(id, (run.into(), name.into()));
    Some(Activity(id))
}
pub(crate) fn live_line(run: &str) -> Option<String> {
    let active = ACTIVE
        .get()?
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    let mut names = active
        .iter()
        .rev()
        .filter(|(_, (owner, _))| owner == run)
        .map(|(_, (_, name))| name.as_str())
        .collect::<Vec<_>>();
    if names.is_empty() {
        return None;
    }
    names.sort_by_key(|name| !name.starts_with("ui_"));
    let driving = names.iter().any(|name| name.starts_with("ui_"));
    let count = names.len();
    let names = names.into_iter().take(2).collect::<Vec<_>>().join("、");
    let suffix = if count > 2 {
        format!("等{count}个调用")
    } else {
        String::new()
    };
    let text = if driving {
        format!("正在操作手机（{names}{suffix}）")
    } else {
        format!("正在执行：{names}{suffix}")
    };
    let mut preview = text.chars().take(48).collect::<String>();
    if text.chars().take(49).count() > 48 {
        preview.push('…');
    }
    Some(preview)
}
fn shared_device(name: &str) -> bool {
    matches!(
        name,
        "run_shell"
            | "bash"
            | "open_app"
            | "open_url"
            | "app_action"
            | "settings_get"
            | "settings_put"
    ) || name.starts_with("ui_") && !matches!(name, "ui_dump" | "ui_screenshot")
}
fn owner_barrier(name: &str) -> bool {
    name == "goal"
        || name.starts_with("goal_")
        || matches!(
            name,
            "spawn_agent" | "wait_agents" | "list_agents" | "cancel_agent"
        )
}
pub(crate) fn groups(calls: &[ToolCall], width: usize, mode: ApprovalMode) -> Vec<Range<usize>> {
    let width = if mode != ApprovalMode::Auto
        || calls
            .iter()
            .any(|call| call.name == "goal" || call.name.starts_with("goal_"))
    {
        1
    } else {
        width.clamp(1, 4)
    };
    let mut groups = Vec::new();
    let mut start = 0;
    for (index, call) in calls.iter().enumerate() {
        if shared_device(&call.name) || owner_barrier(&call.name) {
            if start < index {
                groups.push(start..index);
            }
            groups.push(index..index + 1);
            start = index + 1;
        } else if index + 1 - start == width {
            groups.push(start..index + 1);
            start = index + 1;
        }
    }
    if start < calls.len() {
        groups.push(start..calls.len());
    }
    groups
}
pub(crate) fn guard(
    serial: bool,
    cancel: &CancelToken,
) -> Result<Option<MutexGuard<'static, ()>>, String> {
    if !serial {
        return Ok(None);
    }
    loop {
        if cancel.is_cancelled() {
            return Err("运行已停止，未执行排队中的共享设备操作".into());
        }
        match DEVICE.try_lock() {
            Ok(guard) => return Ok(Some(guard)),
            Err(TryLockError::Poisoned(error)) => return Ok(Some(error.into_inner())),
            Err(TryLockError::WouldBlock) => std::thread::sleep(Duration::from_millis(20)),
        }
    }
}
pub(crate) fn operation_guard(
    operation: &tools::Operation,
    cancel: &CancelToken,
) -> Result<Option<MutexGuard<'static, ()>>, String> {
    guard(
        match operation {
            tools::Operation::Shell(_) => true,
            tools::Operation::AndroidSettings(prepared) => prepared.needs_serial_device(),
            tools::Operation::Platform(prepared) => !prepared.is_read_only(),
            _ => false,
        },
        cancel,
    )
}
