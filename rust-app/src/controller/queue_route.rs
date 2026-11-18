//! One queue dispatcher prepares plain input, direct tools and managed goals.
//! Local read/clear have an accepted transcript and a terminal queue receipt.
use super::*;
use crate::composer::slash::{self, Prepared};
use crate::state::ToolCall;

pub(super) struct QueuedPrepared {
    pub turn: QueuedTurn,
    pub input: composer::PreparedInput,
    pub goal: bool,
    pub resume: Option<String>,
    pub initial_tool: Option<ToolCall>,
}
fn disabled_for(
    store: &Store,
    conversation_id: &str,
) -> Result<std::collections::HashSet<String>, String> {
    let route = store.conversation(conversation_id)?;
    let selected = route
        .assistant_id
        .as_ref()
        .or(store.settings.active_assistant_id.as_ref());
    let Some(selected) = selected else {
        return Ok(Default::default());
    };
    let assistant = store
        .settings
        .assistants
        .iter()
        .find(|assistant| &assistant.id == selected)
        .ok_or("当前助手已移除")?;
    Ok(assistant.disabled_tools.iter().cloned().collect())
}
pub(super) fn send(
    conversation_id: String,
    id: String,
    automatic: bool,
    store: &mut Store,
    runs: &mut Runs,
    publish: &Publisher,
    tx: &SyncSender<WorkerEvent>,
    active: &mut Option<ActiveRun>,
    mcp: Option<&McpRuntime>,
) -> Result<(), String> {
    let turn = store
        .queued_turns(&conversation_id)?
        .into_iter()
        .next()
        .filter(|turn| turn.id == id)
        .ok_or("排队消息已变化")?;
    let identity = (conversation_id.clone(), id.clone());
    if automatic && !runs.authorized_queue.contains(&identity) {
        return Err("恢复的排队消息等待用户继续".into());
    }
    if active.is_some() {
        return Err("当前会话仍在运行，排队消息保留".into());
    }
    if !automatic {
        runs.authorized_queue.insert(identity.clone());
    }
    let text = runs
        .transient_queue
        .resolve(&conversation_id, &turn)?
        .to_owned();
    let disabled = disabled_for(store, &conversation_id)?;
    // Goal routing needs no provider or network configuration. Generic tools
    // use the current catalog; all run routes freeze it again in start_run.
    let prepared = if slash::parse(&text).is_none() {
        Prepared::Plain
    } else if crate::goal::slash_argument(&text).is_some() {
        slash::prepare(&text, &[], &disabled, !turn.attachments.is_empty())?
    } else {
        let plugins = store.plugins()?;
        let bindings = mcp
            .filter(|mcp| !mcp.configuration_pending)
            .map(|mcp| {
                mcp.bridge
                    .snapshot()
                    .tools
                    .into_iter()
                    .filter(|binding| mcp.bridge.binding_is_current(binding))
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        let tools = runtime_tool_definitions(&plugins, &bindings)?;
        slash::prepare(&text, &tools, &disabled, !turn.attachments.is_empty())?
    };
    let mut goal = false;
    let mut resume = None;
    let mut initial_tool = None;
    let mut input = match &prepared {
        Prepared::Goal {
            command: crate::goal::Slash::Read | crate::goal::Slash::Clear,
            ..
        } => {
            let goal_snapshot = store.goal().clone();
            if !goal_snapshot.text.is_empty() && goal_snapshot.chat_id != conversation_id {
                return Err("目标属于其它会话".into());
            }
            let clearing = matches!(
                &prepared,
                Prepared::Goal {
                    command: crate::goal::Slash::Clear,
                    ..
                }
            );
            if clearing && runs.by_conversation.contains_key(&goal_snapshot.chat_id) {
                return Err("请先停止目标运行，再清除目标".into());
            }
            // Admission is one chat write. Subsequent effect failure remains in
            // this real transcript, rather than putting an executed clear back.
            let ticket = store.accept_queued_local(
                &conversation_id,
                &turn,
                if clearing { "goal_clear" } else { "goal_read" },
            )?;
            publish.emit(Event::QueuedTurns {
                conversation_id: conversation_id.clone(),
                turns: ticket.turns.clone(),
            });
            publish.emit(history::changed(store, &conversation_id));
            runs.authorized_queue.remove(&identity);
            runs.transient_queue.prune(&conversation_id, &ticket.turns);
            let result = if clearing {
                if goal_snapshot.text.is_empty() {
                    Ok("当前会话没有目标".into())
                } else {
                    store
                        .clear_goal(&goal_snapshot.id)
                        .map(|_| "当前会话目标已清除".into())
                }
            } else if goal_snapshot.text.is_empty() {
                Ok("当前会话没有目标".into())
            } else {
                serde_json::to_string_pretty(&goal_snapshot).map_err(|_| "目标状态无法编码".into())
            };
            let persisted = store.finish_queued_local(&ticket, &result);
            publish.emit(Event::Goal(store.goal().clone()));
            publish.emit(history::changed(store, &conversation_id));
            let finished = match (result, persisted) {
                (Ok(_), Ok(())) => Ok(()),
                (Err(error), Ok(())) => Err(error),
                (Ok(_), Err(error)) => {
                    Err(format!("目标指令效果已提交，但聊天结果未确认：{error}"))
                }
                (Err(error), Err(save)) => Err(format!("{error}；聊天结果未确认：{save}")),
            };
            publish.emit(Event::QueuedCommandFinished {
                conversation_id,
                id,
                result: finished,
            });
            return Ok(());
        }
        _ => composer::prepare_queued(store, &turn)?,
    };
    match prepared {
        Prepared::Plain => {}
        Prepared::Tool {
            call,
            transcript_text,
        } => {
            input.text = transcript_text;
            initial_tool = Some(call);
        }
        Prepared::Goal {
            command: crate::goal::Slash::Start(text),
            ..
        } => {
            input.text = text;
            goal = true;
        }
        Prepared::Goal {
            command: crate::goal::Slash::Resume,
            ..
        } => {
            let saved = store.goal();
            if saved.text.is_empty() || saved.chat_id != conversation_id {
                return Err("当前会话没有可继续的目标".into());
            }
            resume = Some(saved.id.clone());
            input.text = "继续完成已保存的目标".into();
        }
        Prepared::Goal { .. } => unreachable!("local command returned"),
    }
    if runs.by_id.len() >= 8 {
        return Err("同时运行的会话已达到8个".into());
    }
    let result = start_run(
        Some(conversation_id.clone()),
        input.text.clone(),
        RunMode::QueuedPrepared(Box::new(QueuedPrepared {
            turn,
            input,
            goal,
            resume,
            initial_tool,
        })),
        store,
        publish,
        tx,
        active,
        mcp,
    );
    // The durable queue decides whether a pre-admission error retained this
    // item or a later failure left its accepted question in history.
    if let Ok(turns) = store.queued_turns(&conversation_id) {
        runs.transient_queue.prune(&conversation_id, &turns);
        if !turns.iter().any(|turn| turn.id == id) {
            runs.authorized_queue.remove(&identity);
        }
    }
    result
}

/// Stage secrets in memory, persist the redacted queue, then accept the staged
/// cache change. A failed edit leaves the previous process-owned raw intact.
pub(super) fn admit(
    store: &mut Store,
    runs: &mut Runs,
    conversation_id: &str,
    turn: QueuedTurn,
    editing: bool,
) -> Result<Vec<QueuedTurn>, String> {
    let existing = store.queued_turns(conversation_id)?;
    let original = if editing {
        Some(
            existing
                .iter()
                .find(|current| current.id == turn.id)
                .ok_or("排队消息已移除")?,
        )
    } else {
        None
    };
    let staged = runs
        .transient_queue
        .stage(conversation_id, original, turn)?;
    let accepted = staged.turn.clone();
    let turns = store.edit_queue(conversation_id, |turns| {
        if let Some(original) = original {
            let current = turns
                .iter_mut()
                .find(|current| current.id == original.id)
                .ok_or("排队消息已移除")?;
            if current.text != original.text
                || current.attachments != original.attachments
                || current.transient != original.transient
            {
                return Err("排队消息已变化，编辑未提交".into());
            }
            *current = accepted;
        } else {
            turns.push(accepted);
        }
        Ok(())
    })?;
    runs.transient_queue.commit(staged);
    runs.transient_queue.prune(conversation_id, &turns);
    Ok(turns)
}
