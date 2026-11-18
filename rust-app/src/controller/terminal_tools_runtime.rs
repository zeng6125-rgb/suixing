//! Authenticated preparations and bounded execution for model terminal tools.
use super::{Runs, cancellation, terminal_runtime::TerminalRuntime};
use crate::{
    network::CancelToken,
    runtime_host::{Publisher, RunKey},
    state::ToolCall,
    terminal::{Caller, TerminalBridge},
    terminal_tools,
    vt::Geometry,
};
use serde_json::Value;
use std::{
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc::{self, SyncSender, TrySendError},
    },
    thread,
};

#[derive(Clone)]
pub(crate) struct Prepared {
    pub terminal: terminal_tools::Prepared,
    run: RunKey,
    child: Option<String>,
    parent_cancel: Arc<CancelToken>,
    cancel: Arc<CancelToken>,
    started: Arc<AtomicBool>,
}
impl Prepared {
    pub(crate) fn summary(&self) -> String {
        format!(
            "会话 {} 的独立终端；Android App UID；操作 {:?}",
            self.run.conversation_id, self.terminal.operation
        )
    }
    fn current(&self, runs: &Runs) -> bool {
        runs.by_id.get(&self.run.run_id).is_some_and(|run| {
            run.conversation == self.run.conversation_id
                && Arc::ptr_eq(&run.cancel, &self.parent_cancel)
                && run.worker_is_active(self.child.as_deref())
                && match &self.child {
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
    child: Option<String>,
    call: &ToolCall,
    runs: &Runs,
) -> Result<Prepared, String> {
    let current = runs
        .by_id
        .get(run)
        .filter(|run| run.worker_is_active(child.as_deref()))
        .ok_or("终端工具所属运行已结束")?;
    let cancel = match &child {
        None => current.cancel.clone(),
        Some(id) => current
            .children
            .get(id)
            .ok_or("子任务已结束")?
            .cancel
            .clone(),
    };
    let terminal = terminal_tools::prepare(
        call,
        Caller {
            chat_id: current.conversation.clone(),
            run_id: run.into(),
        },
        format!("terminal-tool-{}", super::unique_id()),
        Geometry::default(),
        false,
    )?;
    Ok(Prepared {
        terminal,
        run: RunKey {
            conversation_id: current.conversation.clone(),
            run_id: run.into(),
        },
        child,
        parent_cancel: current.cancel.clone(),
        cancel,
        started: Arc::new(AtomicBool::new(false)),
    })
}
struct Work {
    prepared: Prepared,
    flag: Arc<AtomicBool>,
    reply: SyncSender<Result<Value, String>>,
}
pub(crate) struct TerminalTools {
    sender: SyncSender<Work>,
}
impl TerminalTools {
    pub(crate) fn boot(terminal: &TerminalRuntime) -> Result<Self, String> {
        let bridge: Arc<TerminalBridge> = terminal.handle();
        let (sender, receiver) = mpsc::sync_channel::<Work>(16);
        thread::Builder::new()
            .name("rust-terminal-tools".into())
            .spawn(move || {
                while let Ok(work) = receiver.recv() {
                    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        terminal_tools::execute_scoped(&work.prepared.terminal, &bridge, work.flag)
                    }))
                    .map_err(|_| "终端工具异常结束，输入或会话可能已建立，请核对".to_owned())
                    .and_then(|value| value);
                    let _ = work.reply.try_send(result);
                }
            })
            .map_err(|_| "终端工具工作线程无法启动")?;
        Ok(Self { sender })
    }
    pub(crate) fn dispatch(
        &self,
        run: &str,
        child: Option<&str>,
        prepared: Prepared,
        reply: SyncSender<Result<Value, String>>,
        runs: &Runs,
        publish: &Publisher,
    ) {
        let result = (|| {
            if run != prepared.run.run_id
                || child != prepared.child.as_deref()
                || !prepared.current(runs)
            {
                return Err("终端工具运行身份已失效，未执行".to_owned());
            }
            if prepared.started.swap(true, Ordering::AcqRel) {
                return Err("终端工具已派发过，不重复执行".into());
            }
            let owner = publish.clone();
            let key = prepared.run.clone();
            let child = prepared.child.clone();
            let flag = cancellation::flag(
                prepared.cancel.clone(),
                Some(Arc::new(move || {
                    owner.worker_scope_live(&key, child.as_deref())
                })),
            )?;
            Ok(Work {
                prepared,
                flag,
                reply: reply.clone(),
            })
        })();
        match result {
            Err(error) => {
                let _ = reply.try_send(Err(error));
            }
            Ok(work) => match self.sender.try_send(work) {
                Ok(()) => (),
                Err(TrySendError::Full(work) | TrySendError::Disconnected(work)) => {
                    let _ = work
                        .reply
                        .try_send(Err("终端工具队列不可用，未执行".into()));
                }
            },
        }
    }
}
