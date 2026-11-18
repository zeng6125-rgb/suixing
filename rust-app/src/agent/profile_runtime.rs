use super::{WorkerEvent, send};
use crate::{network::CancelToken, state::ToolCall};
use std::sync::{
    Arc,
    mpsc::{self, SyncSender},
};
pub(crate) fn injected(
    run: &str,
    child_id: Option<&str>,
    tx: &SyncSender<WorkerEvent>,
    cancel: &Arc<CancelToken>,
) -> Result<String, String> {
    let (reply, receive) = mpsc::sync_channel(1);
    send(
        tx,
        WorkerEvent::ProfileInjection {
            run: run.into(),
            child_id: child_id.map(str::to_owned),
            caller_cancel: cancel.clone(),
            reply,
        },
        cancel,
    )?;
    receive
        .recv()
        .map_err(|_| "画像注入读取连接中断".to_owned())?
}
pub(crate) fn prepare(
    run: &str,
    child_id: Option<&str>,
    call: &ToolCall,
    tx: &SyncSender<WorkerEvent>,
    cancel: &Arc<CancelToken>,
) -> Result<crate::controller::profile_tools_runtime::Prepared, String> {
    let (reply, receive) = mpsc::sync_channel(1);
    send(
        tx,
        WorkerEvent::PrepareProfile {
            run: run.into(),
            child_id: child_id.map(str::to_owned),
            call: call.clone(),
            caller_cancel: cancel.clone(),
            reply,
        },
        cancel,
    )?;
    receive
        .recv()
        .map_err(|_| "画像准备连接中断，未修改".to_owned())?
}
pub(crate) fn execute(
    run: &str,
    child_id: Option<&str>,
    prepared: &crate::controller::profile_tools_runtime::Prepared,
    tx: &SyncSender<WorkerEvent>,
    cancel: &Arc<CancelToken>,
) -> Result<serde_json::Value, String> {
    let (reply, receive) = mpsc::sync_channel(1);
    send(
        tx,
        WorkerEvent::ProfileTool {
            run: run.into(),
            child_id: child_id.map(str::to_owned),
            prepared: prepared.clone(),
            reply,
        },
        cancel,
    )?;
    receive
        .recv()
        .map_err(|_| "画像执行确认连接中断，请先读取实际版本再重试".to_owned())?
}
