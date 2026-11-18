//! Actor-owned identity, frozen route labels and approval-bound session status.
use super::Runs;
use crate::{
    network::CancelToken,
    runtime_host::{Publisher, RunKey},
    session_tools::{Caller, ContextSnapshot, FrozenStatus, RouteLabels},
    state::{Settings, ToolCall},
    store::Store,
};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};

#[derive(Clone)]
pub(crate) struct Prepared {
    caller: Caller,
    parent_cancel: Arc<CancelToken>,
    cancel: Arc<CancelToken>,
    status: FrozenStatus,
    document: Option<crate::system_tools::Prepared>,
    call: ToolCall,
    started: Arc<AtomicBool>,
}
impl Prepared {
    pub(crate) fn writes(&self) -> bool {
        self.document
            .as_ref()
            .is_some_and(|document| document.writes())
    }
    pub(crate) fn is_voice(&self) -> bool {
        crate::voice_tools::is_tool(&self.call.name)
    }
    pub(crate) fn into_voice(
        self,
        run: &str,
        child_id: Option<&str>,
        generation: u64,
        runs: &Runs,
        publish: &Publisher,
    ) -> Result<(crate::voice_tools::Scope, ToolCall), String> {
        if !self.is_voice()
            || run != self.caller.run.run_id
            || child_id != self.caller.child_id.as_deref()
            || !self.current(runs)
            || !publish.worker_scope_live(&self.caller.run, self.caller.child_id.as_deref())
        {
            return Err("语音审批后真实父/子调用已失效，未提交音频".into());
        }
        if self.started.swap(true, Ordering::AcqRel) {
            return Err("语音审批票据已执行，不重放".into());
        }
        let binding = crate::voice_tools::Binding {
            caller: self.caller.clone(),
            call_id: self.call.id.clone(),
            operation: crate::platform::permissions::OperationKey {
                id: crate::controller::unique_id(),
                conversation_id: Some(self.caller.run.conversation_id.clone()),
                generation,
            },
        };
        let publish = publish.clone();
        let parent = self.parent_cancel.clone();
        let child = self.cancel.clone();
        let expected = self.caller;
        let scope = crate::voice_tools::Scope {
            binding,
            cancelled: child.cancellation_flag(),
            live: Arc::new(move |caller| {
                caller == &expected
                    && !parent.is_cancelled()
                    && !child.is_cancelled()
                    && publish.worker_scope_live(&caller.run, caller.child_id.as_deref())
            }),
        };
        Ok((scope, self.call))
    }
    pub(crate) fn summary(&self) -> String {
        if let Some(document) = &self.document {
            return document.summary();
        }
        if self.is_voice() {
            return if self.call.name == "speak" {
                "通过当前父/子任务已审批文本朗读，最多500字符"
            } else {
                "请求停止当前父/子任务自己拥有的朗读"
            }
            .into();
        }
        format!(
            "读取会话 {} 当前{}运行的模型与上下文状态；公开端点不含凭证",
            self.caller.run.conversation_id,
            if self.caller.child_id.is_some() {
                "子任务"
            } else {
                "主任务"
            }
        )
    }
    fn current(&self, runs: &Runs) -> bool {
        runs.by_id.get(&self.caller.run.run_id).is_some_and(|run| {
            run.conversation == self.caller.run.conversation_id
                && Arc::ptr_eq(&run.cancel, &self.parent_cancel)
                && run.worker_is_active(self.caller.child_id.as_deref())
                && match &self.caller.child_id {
                    None => Arc::ptr_eq(&run.cancel, &self.cancel),
                    Some(id) => run
                        .children
                        .get(id)
                        .is_some_and(|child| Arc::ptr_eq(&child.cancel, &self.cancel)),
                }
        })
    }
}
pub(crate) fn prepare(
    run: &str,
    child_id: Option<String>,
    call: &ToolCall,
    resolved: &Settings,
    mut context: ContextSnapshot,
    caller_cancel: Arc<CancelToken>,
    runs: &Runs,
    store: &Store,
) -> Result<Prepared, String> {
    let current = runs
        .by_id
        .get(run)
        .filter(|run| run.worker_is_active(child_id.as_deref()))
        .ok_or("会话状态来源运行已失效")?;
    let catalog = &current.child_inputs.catalog;
    let (cancel, provider_id, assistant_id, assistant_name, title) = match &child_id {
        None => {
            let actual = &current.child_inputs.parent_connection;
            if resolved.model != actual.model
                || resolved.endpoint != actual.endpoint
                || resolved.protocol != actual.protocol
                || resolved.active_provider_id != actual.active_provider_id
                || resolved.active_assistant_id != actual.active_assistant_id
            {
                return Err("会话状态不是该主任务已解析的模型路由".into());
            }
            let assistant_id = actual.active_assistant_id.clone();
            let name = assistant_id
                .as_deref()
                .and_then(|id| catalog.assistants.iter().find(|item| item.id == id))
                .map(|item| item.name.clone());
            (
                current.cancel.clone(),
                actual.active_provider_id.clone(),
                assistant_id,
                name,
                store.conversation(&current.conversation)?.title.clone(),
            )
        }
        Some(id) => {
            let child = current.children.get(id).ok_or("会话状态所属子任务已结束")?;
            let config = child
                .record
                .config
                .as_ref()
                .ok_or("子任务实际模型配置未保留")?;
            if config.model != resolved.model
                || nonempty(&config.provider_id) != resolved.active_provider_id
            {
                return Err("会话状态不是该子任务实际使用的模型路由".into());
            }
            (
                child.cancel.clone(),
                nonempty(&config.provider_id),
                nonempty(&config.assistant_id),
                nonempty(&config.assistant_name),
                child.title.clone(),
            )
        }
    };
    if !Arc::ptr_eq(&cancel, &caller_cancel) {
        return Err("会话状态调用取消令牌不属于该运行".into());
    }
    context.title = title;
    // Unavailable rather than cloning all conversations to invent a count.
    context.conversation_count = None;
    let provider_name = provider_id
        .as_deref()
        .and_then(|id| catalog.providers.iter().find(|item| item.id == id))
        .map(|item| item.name.clone());
    let caller = Caller {
        run: RunKey {
            run_id: run.into(),
            conversation_id: current.conversation.clone(),
        },
        child_id,
    };
    let status = FrozenStatus::freeze(
        caller.clone(),
        resolved,
        RouteLabels {
            provider_id,
            provider_name,
            assistant_id,
            assistant_name,
        },
        context,
    )?;
    // Validate args before approval; the exact call is retained through execute.
    let document = if crate::system_tools::is_tool(&call.name) {
        if call.name == "create_assistant" && caller.child_id.is_some() {
            return Err("子任务不能创建或修改主任务默认助手".into());
        }
        Some(crate::system_tools::prepare(call, store)?)
    } else {
        None
    };
    if crate::voice_tools::is_tool(&call.name) {
        crate::voice_tools::validate_call(call)?;
    } else if document.is_none() {
        status.execute(call, &caller)?;
    }
    Ok(Prepared {
        caller,
        parent_cancel: current.cancel.clone(),
        cancel,
        status,
        document,
        call: call.clone(),
        started: Arc::new(AtomicBool::new(false)),
    })
}
pub(crate) fn execute(
    run: &str,
    child_id: Option<&str>,
    prepared: Prepared,
    runs: &Runs,
    publish: &Publisher,
    store: &mut Store,
) -> Result<serde_json::Value, String> {
    if run != prepared.caller.run.run_id
        || child_id != prepared.caller.child_id.as_deref()
        || !prepared.current(runs)
        || !publish.worker_scope_live(&prepared.caller.run, prepared.caller.child_id.as_deref())
    {
        return Err("会话状态审批后所属运行已失效，未读取".into());
    }
    if prepared.started.swap(true, Ordering::AcqRel) {
        return Err("该会话状态调用已执行，不重放".into());
    }
    if let Some(document) = prepared.document {
        return document.execute(&prepared.call, store);
    }
    prepared
        .status
        .execute(&prepared.call, &prepared.caller)
        .map(|status| serde_json::json!({"ok":true,"status":status}))
}
pub(crate) fn is_tool(name: &str) -> bool {
    name == "get_session_status"
        || crate::voice_tools::is_tool(name)
        || crate::system_tools::is_tool(name)
}
fn nonempty(value: &str) -> Option<String> {
    (!value.is_empty()).then(|| value.to_owned())
}
