//! Profile tools retain exact run/child cancellation and one prepared revision.
use super::{Runs, profile_runtime::ProfileRuntime};
use crate::{
    network::CancelToken,
    profile_tools,
    runtime_host::{Publisher, RunKey},
    state::ToolCall,
    store::Store,
};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
#[derive(Clone)]
pub(crate) struct Prepared {
    run: RunKey,
    child_id: Option<String>,
    parent_cancel: Arc<CancelToken>,
    cancel: Arc<CancelToken>,
    operation: profile_tools::Prepared,
    started: Arc<AtomicBool>,
}
impl Prepared {
    pub(crate) fn writes(&self) -> bool {
        matches!(&self.operation, profile_tools::Prepared::Write { .. })
    }
    pub(crate) fn summary(&self) -> String {
        match &self.operation {
            profile_tools::Prepared::Read { .. } => "读取当前用户画像与版本".into(),
            profile_tools::Prepared::Write { .. } => {
                "保存用户画像；同版本CAS、条目保护和持久化读回".into()
            }
        }
    }
    fn current(&self, runs: &Runs) -> bool {
        runs.by_id.get(&self.run.run_id).is_some_and(|run| {
            run.conversation == self.run.conversation_id
                && Arc::ptr_eq(&run.cancel, &self.parent_cancel)
                && run.worker_is_active(self.child_id.as_deref())
                && match &self.child_id {
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
    caller_cancel: Arc<CancelToken>,
    runs: &Runs,
    runtime: &mut ProfileRuntime,
    store: &Store,
) -> Result<Prepared, String> {
    let current = runs
        .by_id
        .get(run)
        .filter(|run| run.worker_is_active(child_id.as_deref()))
        .ok_or("画像工具来源运行已失效")?;
    let cancel = match &child_id {
        None => current.cancel.clone(),
        Some(id) => current
            .children
            .get(id)
            .ok_or("画像所属子任务已结束")?
            .cancel
            .clone(),
    };
    if !Arc::ptr_eq(&cancel, &caller_cancel) {
        return Err("画像工具取消令牌不属于当前运行".into());
    }
    runtime.prune_model_reads(runs);
    let owner = RunKey {
        run_id: run.into(),
        conversation_id: current.conversation.clone(),
    };
    if call.name == "profile_read" {
        runtime.refresh(store)?;
    } else {
        runtime.synchronize(store)?;
        runtime.require_model_read(&owner, child_id.as_deref(), runs)?;
    }
    let operation = profile_tools::prepare(call, runtime.repository())?;
    Ok(Prepared {
        run: RunKey {
            run_id: run.into(),
            conversation_id: current.conversation.clone(),
        },
        child_id,
        parent_cancel: current.cancel.clone(),
        cancel,
        operation,
        started: Arc::new(AtomicBool::new(false)),
    })
}
pub(crate) fn execute(
    run: &str,
    child_id: Option<&str>,
    prepared: Prepared,
    runs: &Runs,
    runtime: &mut ProfileRuntime,
    store: &Store,
    publish: &Publisher,
) -> Result<serde_json::Value, String> {
    if run != prepared.run.run_id
        || child_id != prepared.child_id.as_deref()
        || !prepared.current(runs)
        || !publish.worker_scope_live(&prepared.run, child_id)
    {
        return Err("画像工具审批后来源运行已失效".into());
    }
    if prepared.writes() {
        runtime.synchronize(store)?;
    } else {
        runtime.refresh(store)?;
    }
    if matches!(&prepared.operation, profile_tools::Prepared::Write { .. }) {
        store.profile_writable()?;
    }
    if prepared.started.swap(true, Ordering::AcqRel) {
        return Err("画像工具调用已执行，不重放".into());
    }
    let value = profile_tools::execute(&prepared.operation, runtime.repository_mut(), || {
        !prepared.cancel.is_cancelled()
    })?;
    if !prepared.writes() {
        runtime.remember_model_read(&prepared.run, child_id)?;
    }
    publish.emit(crate::state::Event::Profile(
        crate::profile::Update::Changed {
            snapshot: runtime.repository().snapshot(),
        },
    ));
    Ok(serde_json::json!({"ok":true,"profile":value}))
}
