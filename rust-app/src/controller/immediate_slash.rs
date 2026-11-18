//! The actor prepares slash steers against the existing run's frozen catalog.
//! A new operation always names the UI-observed RunKey; same-chat replacement
//! runs cannot inherit an older user gesture.
use super::*;
use crate::agent::steering_operations::{Applied, Directive, Entry, Kind, LocalGoal, Operation};

fn scope_for(run: &ActiveRun) -> RunKey {
    RunKey {
        conversation_id: run.conversation.clone(),
        run_id: run.id.clone(),
    }
}
fn prepare(run: &ActiveRun, scope: &RunKey, turn: &QueuedTurn, text: &str) -> Result<Kind, String> {
    scope.validate()?;
    if scope_for(run) != *scope || !run.worker_is_active(None) || run.persistence_error.is_some() {
        return Err("原运行已结束，插话未交给较新的运行".into());
    }
    if !run.child_inputs.parent_connection.behavior.image_input
        && turn.attachments.iter().any(|attachment| attachment.image)
    {
        return Err("当前运行未启用图片输入，原附件与队列保留".into());
    }
    let definitions = runtime_tool_definitions(&run.plugins, &run.child_inputs.mcp_tools)?;
    match crate::composer::slash::prepare(
        text,
        &definitions,
        &run.child_inputs.disabled_tools,
        !turn.attachments.is_empty(),
    )? {
        crate::composer::slash::Prepared::Plain => Ok(Kind::Text),
        crate::composer::slash::Prepared::Tool { call, .. } => Ok(Kind::DirectTool(call)),
        crate::composer::slash::Prepared::Goal {
            command: crate::goal::Slash::Read,
            ..
        } => Ok(Kind::LocalGoal(LocalGoal::Read)),
        crate::composer::slash::Prepared::Goal {
            command: crate::goal::Slash::Clear,
            ..
        } => Ok(Kind::LocalGoal(LocalGoal::Clear)),
        crate::composer::slash::Prepared::Goal { .. } => {
            Err("开始或继续目标请排队，等待当前运行结束后执行".into())
        }
    }
}

/// Called by ComposerRuntime for SubmitMode::Steer after refs were verified.
/// The caller emits Composer::Accepted only after this returns; a local goal
/// command instead emits CommandFinished and never fabricates a new RunStarted.
pub(super) fn admit_new(
    store: &mut Store,
    runs: &mut Runs,
    scope: RunKey,
    turn: QueuedTurn,
) -> Result<(Vec<QueuedTurn>, String), String> {
    let run = runs
        .by_id
        .get(&scope.run_id)
        .ok_or("原运行已结束，草稿保留")?;
    let kind = prepare(run, &scope, &turn, &turn.text)?;
    if matches!(kind, Kind::LocalGoal(_)) {
        return Err("本地目标指令使用execute_local_now调用方，无需入队".into());
    }
    let staged = runs
        .transient_queue
        .stage(&scope.conversation_id, None, turn)?;
    let operation = Operation {
        scope: scope.clone(),
        expected: staged.turn.clone(),
        kind,
    };
    let reservation = run.steering.reserve_operation(operation)?;
    let accepted = staged.turn.clone();
    let id = accepted.id.clone();
    let turns = store.edit_queue(&scope.conversation_id, |turns| {
        turns.push(accepted);
        Ok(())
    })?;
    runs.transient_queue.commit(staged);
    runs.authorized_queue
        .insert((scope.conversation_id, id.clone()));
    run.steering.commit_operation(reservation);
    Ok((turns, id))
}

/// Promotion uses the observed RunKey and the full durable queue snapshot.
/// Original call_id remains in Operation until that exact item is consumed.
pub(super) fn promote(
    store: &Store,
    runs: &mut Runs,
    run: &ActiveRun,
    scope: RunKey,
    id: &str,
) -> Result<Vec<QueuedTurn>, String> {
    let turns = store.queued_turns(&scope.conversation_id)?;
    let turn = turns
        .iter()
        .find(|turn| turn.id == id)
        .ok_or("排队消息已移除")?;
    let text = runs
        .transient_queue
        .resolve(&scope.conversation_id, turn)?
        .to_owned();
    let kind = prepare(run, &scope, turn, &text)?;
    if matches!(kind, Kind::LocalGoal(LocalGoal::Clear)) {
        check_local(store, &scope, LocalGoal::Clear)?;
    }
    let reservation = run.steering.reserve_operation(Operation {
        scope: scope.clone(),
        expected: turn.clone(),
        kind,
    })?;
    runs.authorized_queue
        .insert((scope.conversation_id, id.into()));
    run.steering.commit_operation(reservation);
    Ok(turns)
}

fn check_local(store: &Store, scope: &RunKey, command: LocalGoal) -> Result<(), String> {
    let goal = store.goal();
    if !goal.text.is_empty() && goal.chat_id != scope.conversation_id {
        return Err("目标属于其它会话".into());
    }
    if command == LocalGoal::Clear && goal.status == crate::goal::Status::Active {
        return Err("请先停止目标运行，再清除目标".into());
    }
    Ok(())
}
fn local_effect(store: &mut Store, scope: &RunKey, command: LocalGoal) -> Result<String, String> {
    check_local(store, scope, command)?;
    if store.goal().text.is_empty() {
        return Ok("当前会话没有目标".into());
    }
    if command == LocalGoal::Clear {
        let id = store.goal().id.clone();
        store.clear_goal(&id)?;
        Ok("当前会话目标已清除".into())
    } else {
        serde_json::to_string_pretty(store.goal()).map_err(|_| "目标状态无法编码".into())
    }
}
pub(super) fn execute_local_now(
    store: &mut Store,
    runs: &Runs,
    scope: &RunKey,
    text: &str,
    publish: &Publisher,
) -> Result<(), String> {
    let run = runs
        .by_id
        .get(&scope.run_id)
        .ok_or("原运行已结束，指令未交给较新的运行")?;
    let turn = QueuedTurn {
        id: unique_id(),
        text: text.into(),
        attachments: Vec::new(),
        transient: None,
    };
    let Kind::LocalGoal(command) = prepare(run, scope, &turn, text)? else {
        return Err("不是本地目标指令".into());
    };
    local_effect(store, scope, command)?;
    publish.emit(Event::Goal(store.goal().clone()));
    Ok(())
}

/// WorkerEvent::ApplySteering calls this once per boundary. Validation refusals
/// remove only the stale live instruction and preserve its durable queue item;
/// Store failures propagate so the existing persistence-error cancellation wins.
pub(super) fn apply(
    store: &mut Store,
    run: &mut ActiveRun,
    messages: Vec<Message>,
    cache: &mut crate::composer::transient::Cache,
    publish: &Publisher,
) -> Result<Option<Applied>, String> {
    let Some(entry) = run.steering.next() else {
        return Ok(None);
    };
    let id = entry.id().to_owned();
    let operation = match entry {
        Entry::TextId(id) => {
            let turns = store.queued_turns(&run.conversation)?;
            let Some(turn) = turns.into_iter().find(|turn| turn.id == id) else {
                run.steering.forget(&[id]);
                return Ok(None);
            };
            if turn.transient.is_some() || crate::composer::slash::parse(&turn.text).is_some() {
                run.steering.forget(&[id]);
                publish.emit(Event::Info(
                    "排队项已改为指令，等待重新提升或当前运行结束".into(),
                ));
                return Ok(None);
            }
            Operation {
                scope: scope_for(run),
                expected: turn,
                kind: Kind::Text,
            }
        }
        Entry::Operation(operation) => operation,
    };
    let turns = store.queued_turns(&run.conversation)?;
    let current = turns.iter().find(|turn| turn.id == operation.expected.id);
    let validation = (|| -> Result<(), String> {
        if operation.scope != scope_for(run) || !run.worker_is_active(None) {
            return Err("原运行已结束，指令未执行".into());
        }
        let current = current.ok_or("排队消息已移除")?;
        if current.text != operation.expected.text
            || current.attachments != operation.expected.attachments
            || current.transient != operation.expected.transient
        {
            return Err("排队消息已编辑，原指令未执行".into());
        }
        let text = cache.resolve(&run.conversation, current)?;
        let prepared = prepare(run, &operation.scope, current, text)?;
        match (&operation.kind, prepared) {
            (Kind::Text, Kind::Text) => Ok(()),
            (Kind::DirectTool(expected), Kind::DirectTool(actual))
                if expected.name == actual.name && expected.arguments == actual.arguments =>
            {
                Ok(())
            }
            (Kind::LocalGoal(expected), Kind::LocalGoal(actual)) if *expected == actual => {
                check_local(store, &operation.scope, actual)
            }
            _ => Err("指令或运行工具范围已变化，未执行原指令".into()),
        }
    })();
    if let Err(error) = validation {
        run.steering.forget(&[id.clone()]);
        publish.emit(Event::QueueWrite {
            id,
            result: Err(error),
        });
        return Ok(None);
    }
    let applied = if let Kind::LocalGoal(command) = &operation.kind {
        let (ticket, accepted_messages) = store.accept_steering_local(
            &operation,
            messages,
            if *command == LocalGoal::Read {
                "goal_read"
            } else {
                "goal_clear"
            },
        )?;
        let prior_retention = run.retain_actor_steering;
        run.retain_actor_steering = true;
        install_messages(run, accepted_messages);
        run.steering.forget(&[id.clone()]);
        // Admission precedes the possible clear effect. Once admitted, a failure
        // is retained in this local response and is never requeued for replay.
        let result = local_effect(store, &operation.scope, *command);
        let saved = store.finish_queued_local(&ticket, &result);
        publish.emit(Event::Goal(store.goal().clone()));
        let messages = match store.messages(&run.conversation) {
            Ok(messages) => messages,
            Err(error) => {
                publish.emit(history::changed(store, &run.conversation));
                return Err(format!("本地目标指令已受理，最新结果无法读取：{error}"));
            }
        };
        let applied = Applied {
            accepted_id: operation.expected.id.clone(),
            messages,
            remaining_turns: ticket.turns,
            directive: Directive::LocalGoal,
        };
        if let Err(error) = saved {
            run.retain_actor_steering = true;
            install_messages(run, applied.messages.clone());
            publish.emit(history::changed(store, &run.conversation));
            return Err(format!("本地目标指令已受理，结果保存未确认：{error}"));
        }
        run.retain_actor_steering = prior_retention;
        applied
    } else {
        store.accept_steering_operation(&operation, messages)?
    };
    run.steering.forget(&[id]);
    cache.prune(&run.conversation, &applied.remaining_turns);
    install_messages(run, applied.messages.clone());
    publish.emit(Event::QueuedTurns {
        conversation_id: run.conversation.clone(),
        turns: applied.remaining_turns.clone(),
    });
    publish.emit(Event::HistoryInvalidated {
        source: crate::display::ViewSource::Main {
            conversation_id: run.conversation.clone(),
        },
        stamp: history::live_stamp(run),
    });
    Ok(Some(applied))
}
