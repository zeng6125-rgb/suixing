//! Concrete worker-side ApplySteering caller. The actor reply changes the
//! transcript before the worker can hand a direct tool to the normal batch.
use super::steering_operations::{Directive, Entry, Kind, Operation};
use super::*;

pub(super) fn apply_steering(
    run: &str,
    messages: &mut Vec<Message>,
    tx: &SyncSender<WorkerEvent>,
    cancel: &CancelToken,
) -> Result<Option<Directive>, String> {
    let (reply, receive) = mpsc::sync_channel(1);
    send(
        tx,
        WorkerEvent::ApplySteering {
            run: run.into(),
            messages: messages.clone(),
            reply,
        },
        cancel,
    )?;
    // Receive even when stop races a durable write, so final persistence retains
    // its actual accepted transcript. A disconnected reply performs no effect.
    let Some(applied) = receive.recv().map_err(|_| "插话保存确认中断")?? else {
        return Ok(None);
    };
    *messages = applied.messages;
    Ok(Some(applied.directive))
}

/// Called before the next model request, after any previous tool batch closed.
/// A returned direct call goes through the SAME metadata/checkpoint/prepare,
/// approval, execute, result and checkpoint block as model-generated calls.
pub(super) fn direct_at_boundary(
    run: &str,
    steering: &Steering,
    messages: &mut Vec<Message>,
    tx: &SyncSender<WorkerEvent>,
    cancel: &CancelToken,
) -> Result<Option<ToolCall>, String> {
    while steering.next().is_some() {
        match apply_steering(run, messages, tx, cancel)? {
            Some(Directive::DirectTool { call, assistant_id }) => {
                let last = messages
                    .last()
                    .filter(|message| {
                        message.id == assistant_id
                            && message.role == "assistant"
                            && message.tool_calls.len() == 1
                            && message.tool_calls[0].id == call.id
                    })
                    .ok_or("直接工具受理回执已变化，未执行")?;
                let _ = last;
                return Ok(Some(call));
            }
            Some(Directive::LocalGoal) => {
                // Continuing work needs a new assistant slot; no synthetic
                // network request or usage row is emitted for the local result.
                messages.push(assistant_placeholder());
            }
            Some(Directive::Text) | None => {}
        }
    }
    Ok(None)
}

/// When the model naturally finishes with no calls, settle a local-only prefix
/// before the ordinary return decision. A read/clear by itself does not cause
/// an otherwise completed run to issue another model request.
pub(super) fn finish_local_prefix(
    run: &str,
    steering: &Steering,
    messages: &mut Vec<Message>,
    tx: &SyncSender<WorkerEvent>,
    cancel: &CancelToken,
) -> Result<(), String> {
    while matches!(
        steering.next(),
        Some(Entry::Operation(Operation {
            kind: Kind::LocalGoal(_),
            ..
        }))
    ) {
        match apply_steering(run, messages, tx, cancel)? {
            Some(Directive::LocalGoal) | None => {}
            Some(_) => return Err("本地指令前缀已变化，未开始额外请求".into()),
        }
    }
    Ok(())
}
