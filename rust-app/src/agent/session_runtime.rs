//! Exact model request metadata crosses the actor boundary separately from args.
use super::{WorkerEvent, send};
use crate::{
    network::CancelToken,
    session_tools::ContextSnapshot,
    state::{Settings, ToolCall},
};
use std::sync::{
    Arc,
    mpsc::{self, SyncSender},
};

pub(crate) fn snapshot(
    settings: &Settings,
    estimate: Option<crate::context::PromptEstimate>,
    checkpoint_count: usize,
    message_count: usize,
) -> ContextSnapshot {
    ContextSnapshot {
        estimate,
        context_window: settings
            .model_contexts
            .get(&settings.model)
            .copied()
            .filter(|value| *value > 0)
            .map_or(crate::context::DEFAULT_CONTEXT_WINDOW, |value| value as u64),
        checkpoint_count,
        message_count,
        title: String::new(),
        conversation_count: None,
    }
}
pub(crate) fn prepare(
    run: &str,
    child_id: Option<&str>,
    call: &ToolCall,
    resolved: &Settings,
    context: ContextSnapshot,
    tx: &SyncSender<WorkerEvent>,
    cancel: &Arc<CancelToken>,
) -> Result<crate::controller::session_runtime::Prepared, String> {
    let (reply, receive) = mpsc::sync_channel(1);
    send(
        tx,
        WorkerEvent::PrepareSession {
            run: run.into(),
            child_id: child_id.map(str::to_owned),
            call: call.clone(),
            resolved: Box::new(resolved.clone()),
            context,
            caller_cancel: cancel.clone(),
            reply,
        },
        cancel,
    )?;
    receive
        .recv()
        .map_err(|_| "会话状态准备连接中断，未读取".to_owned())?
}
pub(crate) fn execute(
    run: &str,
    child_id: Option<&str>,
    prepared: &crate::controller::session_runtime::Prepared,
    tx: &SyncSender<WorkerEvent>,
    cancel: &Arc<CancelToken>,
) -> Result<serde_json::Value, String> {
    let (reply, receive) = mpsc::sync_channel(1);
    send(
        tx,
        WorkerEvent::SessionTool {
            run: run.into(),
            child_id: child_id.map(str::to_owned),
            prepared: prepared.clone(),
            reply,
        },
        cancel,
    )?;
    receive
        .recv()
        .map_err(|_| "会话状态读取确认连接中断".to_owned())?
}
