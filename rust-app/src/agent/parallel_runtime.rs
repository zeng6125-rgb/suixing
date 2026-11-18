//! Prepare and approve one independent cohort, then execute on bounded scoped
//! workers. The caller alone commits the complete ordered receipt vector.
use super::*;
use serde_json::Value;
pub(super) struct Receipt {
    pub value: Value,
    pub error: Option<String>,
}
#[allow(clippy::too_many_arguments)]
pub(super) fn execute(
    run: &str,
    calls: &[ToolCall],
    settings: &Settings,
    context: &RunContext,
    definitions: &[rust_protocol::ToolDefinition],
    session: &crate::session_tools::ContextSnapshot,
    workspace: &Path,
    messages: &[Message],
    pending_children: bool,
    tx: &SyncSender<WorkerEvent>,
    cancel: &Arc<CancelToken>,
) -> Result<Vec<Receipt>, String> {
    let mut prepared = Vec::with_capacity(calls.len());
    let mut reserve = if pending_children {
        CHILD_RESULT_RESERVE
    } else {
        0
    };
    for call in calls {
        if cancel.is_cancelled() {
            return Err("运行已停止，本并行组未派发".into());
        }
        let item = if !definitions
            .iter()
            .any(|definition| definition.name == call.name)
        {
            Err("该工具未在本次运行目录中开放，未执行".into())
        } else if context.disabled_tools.contains(&call.name) {
            Err("当前助手已禁用该工具，未执行".into())
        } else if matches!(call.name.as_str(), "profile_read" | "profile_write") {
            profile_runtime::prepare(run, None, call, tx, cancel).map(Prepared::Profile)
        } else if crate::controller::session_runtime::is_tool(&call.name) {
            session_runtime::prepare(run, None, call, settings, session.clone(), tx, cancel)
                .map(Prepared::Session)
        } else if let Some((entry, tool)) =
            crate::plugin_runtime::resolve(&context.plugins, &call.name)
        {
            crate::plugin_runtime::prepare(entry, tool, call, cancel).map(Prepared::Plugin)
        } else if let Some((bridge, binding)) =
            context.child_inputs.mcp.as_ref().and_then(|bridge| {
                context
                    .child_inputs
                    .mcp_tools
                    .iter()
                    .find(|binding| binding.registered_name == call.name)
                    .map(|binding| (bridge, binding))
            })
        {
            Ok(Prepared::Mcp(bridge, binding))
        } else {
            prepare_for_worker(run, None, call, tx, cancel).map(Prepared::Tool)
        };
        if let Ok(item) = &item {
            reserve = reserve
                .checked_add(match item {
                    Prepared::Tool(tools::Prepared {
                        operation: tools::Operation::Platform(_),
                        ..
                    }) => 18 * 1024 * 1024,
                    Prepared::Tool(tools::Prepared {
                        operation: tools::Operation::Skill(crate::skills::Operation::List),
                        ..
                    }) => 5 * 1024 * 1024,
                    Prepared::Tool(tools::Prepared {
                        operation: tools::Operation::Web(_),
                        ..
                    })
                    | Prepared::Mcp(_, _)
                    | Prepared::Plugin(_) => 2 * 1024 * 1024,
                    _ => 768 * 1024,
                })
                .ok_or("并行工具结果预算溢出")?;
        }
        prepared.push(item);
    }
    if history_bytes(messages) > MAX_HISTORY_BYTES.saturating_sub(reserve) {
        return Err("会话没有足够空间保留整个并行组结果，未执行本组".into());
    }
    storage_budget(run, messages, reserve, tx, cancel)?;
    // Resolve every approval before launching a worker. A plugin hook or a
    // later actor mode change may require actual confirmation even in AUTO.
    let mut approved = Vec::with_capacity(calls.len());
    for (call, item) in calls.iter().zip(prepared) {
        let item = match item {
            Err(error) => Err(json!({"ok":false,"started":false,"error":error})),
            Ok(item) => {
                let summary = match &item {
                    Prepared::Profile(item) => Some(item.summary()),
                    Prepared::Session(item) => Some(item.summary()),
                    Prepared::Plugin(item) => Some(item.approval_summary.clone()),
                    Prepared::Tool(item) => Some(item.path.clone()),
                    _ => None,
                };
                if approve_with_summary(
                    run,
                    None,
                    call,
                    summary,
                    settings.approval_mode,
                    tx,
                    cancel,
                )? {
                    Ok(item)
                } else {
                    Err(
                        json!({"ok":false,"started":false,"denied":true,"error":"用户拒绝或审批超时；该操作未执行"}),
                    )
                }
            }
        };
        approved.push(item);
    }
    if cancel.is_cancelled() {
        return Err("运行已停止，本并行组未派发".into());
    }
    let receipts = thread::scope(|scope| {
        let mut workers = Vec::with_capacity(calls.len());
        for (call, item) in calls.iter().zip(approved) {
            match item {
                Err(value) => workers.push(Err(Receipt { value, error: None })),
                Ok(item) => {
                    let worker=thread::Builder::new().name("jagent-parallel-tool".into()).spawn_scoped(scope,move||{
                        std::panic::catch_unwind(std::panic::AssertUnwindSafe(||dispatch(run,call,item,workspace,tx,cancel))).unwrap_or_else(|_|Receipt{
                            value:json!({"ok":false,"started":true,"effects_may_have_occurred":true,"error":"工具worker异常，效果未知，请先核对"}),
                            error:Some("工具worker异常，效果未知，请先核对".into()),
                        })
                    });
                    workers.push(match worker{
                        Ok(worker)=>Ok(worker),
                        Err(_)=>Err(Receipt{value:json!({"ok":false,"started":false,"not_started":true,"error":"无法创建工具worker，未执行本调用"}),error:Some("无法创建工具worker，未执行本调用".into())}),
                    });
                }
            }
        }
        workers.into_iter().map(|worker|match worker{
            Err(receipt)=>receipt,
            Ok(worker)=>worker.join().unwrap_or_else(|_|Receipt{value:json!({"ok":false,"started":true,"effects_may_have_occurred":true,"error":"工具worker终止，效果未知"}),error:Some("工具worker终止，效果未知".into())}),
        }).collect()
    });
    Ok(receipts)
}
fn dispatch(
    run: &str,
    call: &ToolCall,
    prepared: Prepared<'_>,
    workspace: &Path,
    tx: &SyncSender<WorkerEvent>,
    cancel: &Arc<CancelToken>,
) -> Receipt {
    if cancel.is_cancelled() {
        return Receipt {
            value: json!({"ok":false,"started":false,"not_started":true,"error":"运行已停止，未执行本调用"}),
            error: None,
        };
    }
    let runtime = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(runtime) => runtime,
        Err(_) => {
            return Receipt {
                value: json!({"ok":false,"started":false,"not_started":true,"error":"无法创建工具网络运行时，未执行"}),
                error: Some("无法创建工具网络运行时".into()),
            };
        }
    };
    let _tool_activity = crate::tool_execution::activity(run, &call.name);
    let result = match prepared {
        Prepared::Profile(prepared) => Ok(profile_runtime::execute(
            run, None, &prepared, tx, cancel,
        )
        .unwrap_or_else(
            |error| json!({"ok":false,"effects_may_have_occurred":prepared.writes(),"error":error}),
        )),
        Prepared::Session(prepared) => Ok(session_runtime::execute(
            run, None, &prepared, tx, cancel,
        )
        .unwrap_or_else(
            |error| json!({"ok":false,"effects_may_have_occurred":prepared.writes(),"error":error}),
        )),
        Prepared::Tool(prepared) => {
            execute_prepared(run, None, &prepared, workspace, &runtime, tx, cancel)
                .map_err(|error| (error, true))
        }
        Prepared::Plugin(prepared) => Ok(runtime.block_on(
            crate::plugin_runtime::execute_prepared(&prepared, cancel.clone()),
        )),
        Prepared::Mcp(bridge, binding) => Ok(bridge
            .call(binding, &call.arguments, cancel.clone())
            .unwrap_or_else(|error| json!({"ok":false,"not_started":true,"error":error}))),
        Prepared::Goal(_) | Prepared::Child(_) => {
            Err(("本地主/子控制调用不能进入并行worker".into(), false))
        }
    };
    match result {
        Ok(value) => Receipt { value, error: None },
        Err((error, dispatched)) => Receipt {
            value: json!({"ok":false,"started":dispatched,"effects_may_have_occurred":dispatched,"error":error}),
            error: Some(error),
        },
    }
}
