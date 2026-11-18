//! Process-owned steering operations. Only a durable queue reference is public;
//! raw direct-tool arguments never implement Debug or Serialize.
use super::ChildLaunchRequest;
use crate::{
    network::CancelToken,
    runtime_host::RunKey,
    state::{Message, QueuedTurn, ToolCall},
};
use std::sync::{Arc, Mutex};

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum LocalGoal {
    Read,
    Clear,
}
#[derive(Clone)]
pub enum Kind {
    Text,
    DirectTool(ToolCall),
    LocalGoal(LocalGoal),
}
#[derive(Clone)]
pub struct Operation {
    pub scope: RunKey,
    pub expected: QueuedTurn,
    pub kind: Kind,
}
impl Operation {
    pub fn validate(&self) -> Result<(), String> {
        self.scope.validate()?;
        if self.scope.run_id.is_empty()
            || self.scope.conversation_id.is_empty()
            || self.expected.id.is_empty()
        {
            return Err("插话缺少运行或排队身份".into());
        }
        crate::composer::json_bytes(&self.expected, 1024 * 1024)?;
        if !matches!(self.kind, Kind::Text) && !self.expected.attachments.is_empty() {
            return Err("斜杠指令不能附带附件".into());
        }
        if let Kind::DirectTool(call) = &self.kind {
            if call.id.is_empty()
                || call.id.len() > 128
                || call.name.is_empty()
                || call.name.len() > 128
                || call.arguments.len() > 64 * 1024
                || call.arguments.contains('\0')
                || !serde_json::from_str::<serde_json::Value>(&call.arguments)
                    .is_ok_and(|value| value.is_object())
            {
                return Err("直接工具指令身份或参数无效".into());
            }
        }
        Ok(())
    }
    fn weight(&self) -> usize {
        self.expected.text.len()
            + self
                .expected
                .attachments
                .iter()
                .map(|item| item.metadata_bytes())
                .sum::<usize>()
            + match &self.kind {
                Kind::DirectTool(call) => call.arguments.len() + call.name.len() + call.id.len(),
                _ => 0,
            }
    }
}
#[derive(Clone)]
pub(crate) enum Entry {
    TextId(String),
    Operation(Operation),
}
impl Entry {
    pub(crate) fn id(&self) -> &str {
        match self {
            Self::TextId(id) => id,
            Self::Operation(operation) => &operation.expected.id,
        }
    }
    fn weight(&self) -> usize {
        match self {
            Self::TextId(id) => id.len(),
            Self::Operation(operation) => operation.weight(),
        }
    }
}
/// The actor acknowledges the already-persisted transcript together with the
/// original process-owned direct call. The worker never reconstructs a call
/// from the durable redacted assistant.tool_calls.
pub struct Applied {
    pub accepted_id: String,
    pub messages: Vec<Message>,
    pub remaining_turns: Vec<QueuedTurn>,
    pub directive: Directive,
}
pub enum Directive {
    Text,
    DirectTool {
        call: ToolCall,
        assistant_id: String,
    },
    LocalGoal,
}

#[derive(Default)]
pub struct Steering {
    state: Mutex<State>,
}
#[derive(Default)]
pub(super) struct State {
    pub(super) request: Option<Arc<CancelToken>>,
    pub(super) pending: Vec<Entry>,
    pub(super) child_launches: Vec<ChildLaunchRequest>,
    pub(super) compact_requested: bool,
}
impl Steering {
    pub(super) fn state(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|error| error.into_inner())
    }
    /// Compatibility for existing ordinary text steering callers. New slash
    /// callers use offer_operation with the exact RunKey and queue snapshot.
    pub fn offer(&self, id: String) {
        let mut state = self.state();
        if !state.pending.iter().any(|entry| entry.id() == id) {
            state.pending.push(Entry::TextId(id));
            if let Some(request) = &state.request {
                request.cancel();
            }
        }
    }
    /// Caller first stages the operation, saves the durable queue, then commits
    /// this in-memory reservation. No worker can dispatch before that commit.
    pub fn reserve_operation(&self, operation: Operation) -> Result<Reserved, String> {
        operation.validate()?;
        let state = self.state();
        if state
            .pending
            .iter()
            .any(|entry| entry.id() == operation.expected.id)
            || state.pending.len() >= 32
            || state
                .pending
                .iter()
                .map(Entry::weight)
                .sum::<usize>()
                .saturating_add(operation.weight())
                > 2 * 1024 * 1024
        {
            return Err("插话已在队列或超过内存预算，原输入保留".into());
        }
        // Only the owning actor reserves/commits operations. Worker code can
        // consume an earlier prefix but cannot add a competing operation here.
        Ok(Reserved(operation))
    }
    pub fn commit_operation(&self, reserved: Reserved) {
        let operation = reserved.0;
        let interrupt = !matches!(&operation.kind, Kind::LocalGoal(_));
        let mut state = self.state();
        state.pending.push(Entry::Operation(operation));
        if interrupt {
            if let Some(request) = &state.request {
                request.cancel();
            }
        }
    }
    pub(crate) fn next(&self) -> Option<Entry> {
        self.state().pending.first().cloned()
    }
    pub fn pending_ids(&self) -> Vec<String> {
        self.state()
            .pending
            .iter()
            .map(|entry| entry.id().into())
            .collect()
    }
    pub fn forget(&self, ids: &[String]) {
        self.state()
            .pending
            .retain(|entry| !ids.iter().any(|id| id == entry.id()));
    }
    pub(super) fn has_pending(&self) -> bool {
        let state = self.state();
        !state.pending.is_empty() || !state.child_launches.is_empty()
    }
    pub(super) fn has_interrupting_pending(&self) -> bool {
        let state = self.state();
        !state.child_launches.is_empty()
            || state.pending.iter().any(|entry| {
                !matches!(
                    entry,
                    Entry::Operation(Operation {
                        kind: Kind::LocalGoal(_),
                        ..
                    })
                )
            })
    }
    pub fn request_compaction(&self) {
        self.state().compact_requested = true;
    }
    pub(super) fn take_compaction(&self) -> bool {
        std::mem::take(&mut self.state().compact_requested)
    }
    pub fn offer_child(&self, request: ChildLaunchRequest) -> Result<(), String> {
        let mut state = self.state();
        if state
            .child_launches
            .iter()
            .any(|pending| pending.request_id == request.request_id)
        {
            return Err("子任务创建请求已在处理中".into());
        }
        if state.child_launches.len() >= 6 {
            return Err("待创建子任务已达到本次运行上限".into());
        }
        state.child_launches.push(request);
        if let Some(request) = &state.request {
            request.cancel();
        }
        Ok(())
    }
    pub(super) fn take_child_launches(&self) -> Vec<ChildLaunchRequest> {
        std::mem::take(&mut self.state().child_launches)
    }
    pub(super) fn begin_request(&self) -> Option<Arc<CancelToken>> {
        let mut state = self.state();
        if !state.pending.is_empty() || !state.child_launches.is_empty() {
            return None;
        }
        let request = Arc::new(CancelToken::new());
        state.request = Some(request.clone());
        Some(request)
    }
    pub(super) fn replace_request(&self, next: Arc<CancelToken>) -> Option<Arc<CancelToken>> {
        let mut state = self.state();
        if !state.child_launches.is_empty()
            || state.pending.iter().any(|entry| {
                !matches!(
                    entry,
                    Entry::Operation(Operation {
                        kind: Kind::LocalGoal(_),
                        ..
                    })
                )
            })
        {
            next.cancel();
        }
        state.request.replace(next)
    }
    pub(super) fn restore_request(&self, previous: Option<Arc<CancelToken>>) {
        let mut state = self.state();
        if !state.child_launches.is_empty()
            || state.pending.iter().any(|entry| {
                !matches!(
                    entry,
                    Entry::Operation(Operation {
                        kind: Kind::LocalGoal(_),
                        ..
                    })
                )
            })
        {
            if let Some(request) = &previous {
                request.cancel();
            }
        }
        state.request = previous;
    }
    pub(super) fn end_request(&self) -> bool {
        self.state()
            .request
            .take()
            .is_some_and(|request| request.is_cancelled())
    }
}
pub struct Reserved(Operation);
