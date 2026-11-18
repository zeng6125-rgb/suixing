//! One bounded Agent run. The actor acknowledges durable checkpoints before any tool
//! effect; per-call approval is never reused for another request or another run.
mod parallel_runtime;
pub(crate) mod profile_runtime;
pub(crate) mod retry_runtime;
mod round_budget;
pub(crate) mod run_ledger;
pub(crate) mod session_runtime;
mod steering_caller;
pub(crate) mod steering_operations;
use crate::{
    configuration::RunContext,
    goal::{self, Command as GoalCommand, Goal, Status as GoalStatus},
    network::{self, CancelToken},
    session_tools::ContextReading,
    state::{Message, Settings, ToolCall},
    subagents::{self, Pool},
    tools,
};
use serde_json::json;
use std::{
    collections::HashSet,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        mpsc::{self, RecvTimeoutError, SyncSender, TrySendError},
    },
    thread,
    time::{Duration, Instant},
};
pub use steering_operations::Steering;
use suixing_search::rust_protocol::{self, UsageReport};

pub const MAX_HISTORY_BYTES: usize = 512 * 1024 * 1024;
const CHILD_RESULT_RESERVE: usize = 5 * 1024 * 1024 + 32 * 1024;
const MAX_COMPACTION_REQUESTS: usize = 16;
const MAX_GOAL_ROUNDS: usize = 24;
const MAX_CALLS_PER_TURN: usize = 16;
const MAX_RUN_TIME: Duration = Duration::from_secs(30 * 60);
const MAX_GOAL_REPLIES_WITHOUT_PROGRESS: usize = 2;

pub struct ChildLaunchRequest {
    pub request_id: String,
    pub task: String,
    pub context: String,
    pub name: String,
    pub options: crate::subagent_configuration::SubagentLaunchOptions,
}

pub enum WorkerEvent {
    ProfileInjection {
        run: String,
        child_id: Option<String>,
        caller_cancel: Arc<CancelToken>,
        reply: SyncSender<Result<String, String>>,
    },
    PrepareProfile {
        run: String,
        child_id: Option<String>,
        call: ToolCall,
        caller_cancel: Arc<CancelToken>,
        reply: SyncSender<Result<crate::controller::profile_tools_runtime::Prepared, String>>,
    },
    ContextReading {
        run: String,
        reading: ContextReading,
    },
    ProfileTool {
        run: String,
        child_id: Option<String>,
        prepared: crate::controller::profile_tools_runtime::Prepared,
        reply: SyncSender<Result<serde_json::Value, String>>,
    },
    PrepareSession {
        run: String,
        child_id: Option<String>,
        call: ToolCall,
        resolved: Box<Settings>,
        context: crate::session_tools::ContextSnapshot,
        caller_cancel: Arc<CancelToken>,
        reply: SyncSender<Result<crate::controller::session_runtime::Prepared, String>>,
    },
    SessionTool {
        run: String,
        child_id: Option<String>,
        prepared: crate::controller::session_runtime::Prepared,
        reply: SyncSender<Result<serde_json::Value, String>>,
    },
    CompactionBusy {
        run: String,
        busy: bool,
    },
    SaveCompaction {
        run: String,
        checkpoint: crate::chat::ContextCheckpoint,
        covered_ids: Vec<String>,
        fingerprint: String,
        reply: SyncSender<Result<(), String>>,
    },
    StorageBudget {
        run: String,
        messages: Vec<Message>,
        reserve: usize,
        reply: SyncSender<Result<usize, String>>,
    },
    RequestUsage {
        run: String,
        record: crate::state::UsageRecord,
        reply: SyncSender<Result<(), String>>,
    },
    ChildLaunchResult {
        run: String,
        request_id: String,
        result: Result<String, String>,
    },
    ChildDelta {
        run: String,
        child_id: String,
        message_id: String,
        thinking: bool,
        text: String,
    },
    SaveChild {
        run: String,
        record: crate::subagents::Record,
        cancel: Option<Arc<CancelToken>>,
        reply: SyncSender<Result<(), String>>,
    },
    ApplySteering {
        run: String,
        messages: Vec<Message>,
        reply: SyncSender<Result<Option<steering_operations::Applied>, String>>,
    },
    Goal {
        run: String,
        command: GoalCommand,
        reply: SyncSender<Result<Option<Goal>, String>>,
    },
    Skill {
        run: String,
        child_id: Option<String>,
        operation: crate::skills::Operation,
        reply: SyncSender<serde_json::Value>,
    },
    Todo {
        run: String,
        child_id: Option<String>,
        operation: crate::todos::Operation,
        reply: SyncSender<serde_json::Value>,
    },
    Schedule {
        run: String,
        child_id: Option<String>,
        operation: crate::schedules::Operation,
        reply: SyncSender<serde_json::Value>,
    },
    Job {
        run: String,
        child_id: Option<String>,
        call_id: String,
        prepared: crate::jobs::Prepared,
        reply: SyncSender<serde_json::Value>,
    },
    SystemTool {
        run: String,
        child_id: Option<String>,
        prepared: crate::platform_tools::Prepared,
        reply: SyncSender<Result<crate::platform_tools::Outcome, String>>,
    },
    PrepareWeb {
        run: String,
        child_id: Option<String>,
        call: ToolCall,
        reply: SyncSender<Result<crate::web::Prepared, String>>,
    },
    PrepareTerminal {
        run: String,
        child_id: Option<String>,
        call: ToolCall,
        reply: SyncSender<Result<crate::controller::terminal_tools_runtime::Prepared, String>>,
    },
    TerminalTool {
        run: String,
        child_id: Option<String>,
        prepared: crate::controller::terminal_tools_runtime::Prepared,
        reply: SyncSender<Result<serde_json::Value, String>>,
    },
    Delta {
        run: String,
        message_id: String,
        thinking: bool,
        text: String,
    },
    Checkpoint {
        run: String,
        messages: Vec<Message>,
        usage: UsageReport,
        reply: SyncSender<Result<(), String>>,
    },
    Approval {
        run: String,
        child_id: Option<String>,
        approval_id: String,
        expires: Instant,
        call: ToolCall,
        summary: Option<String>,
        mode: crate::subagent_configuration::ApprovalMode,
        reply: SyncSender<bool>,
    },
    Ended {
        run: String,
        result: Outcome,
    },
}
pub struct Outcome {
    pub elapsed_ms: Option<i64>,
    pub messages: Vec<Message>,
    pub usage: UsageReport,
    pub requests: usize,
    pub error: Option<String>,
}

fn accumulate_request(outcome: &mut Outcome, usage: UsageReport) {
    outcome.usage = aggregate(outcome.usage, usage, outcome.requests == 0);
    outcome.requests += 1;
}

fn accumulate_summaries(outcome: &mut Outcome, usage: UsageReport, requests: usize) {
    if requests == 0 {
        return;
    }
    outcome.usage = aggregate(outcome.usage, usage, outcome.requests == 0);
    outcome.requests = outcome.requests.saturating_add(requests);
}

fn context_estimate(
    settings: &Settings,
    input: crate::context::ContextInput<'_>,
    cancel: &CancelToken,
) -> Result<crate::context::PromptEstimate, String> {
    let window = settings
        .model_contexts
        .get(&settings.model)
        .copied()
        .filter(|value| *value > 0)
        .map_or(crate::context::DEFAULT_CONTEXT_WINDOW, |value| value as u64);
    match crate::context::assess_context(input, window, || cancel.is_cancelled())? {
        crate::context::ContextAssessment::Ready { estimate, .. }
        | crate::context::ContextAssessment::NeedsCompaction { estimate, .. } => Ok(estimate),
        crate::context::ContextAssessment::Uncompressible { reason, .. } => Err(reason),
    }
}

impl WorkerEvent {
    pub fn run_id(&self) -> &str {
        match self {
            Self::ProfileInjection { run, .. }
            | Self::PrepareProfile { run, .. }
            | Self::ContextReading { run, .. }
            | Self::ProfileTool { run, .. }
            | Self::CompactionBusy { run, .. }
            | Self::SaveCompaction { run, .. }
            | Self::StorageBudget { run, .. }
            | Self::RequestUsage { run, .. }
            | Self::ChildLaunchResult { run, .. }
            | Self::ChildDelta { run, .. }
            | Self::SaveChild { run, .. }
            | Self::ApplySteering { run, .. }
            | Self::Goal { run, .. }
            | Self::Skill { run, .. }
            | Self::Todo { run, .. }
            | Self::Schedule { run, .. }
            | Self::Job { run, .. }
            | Self::SystemTool { run, .. }
            | Self::PrepareWeb { run, .. }
            | Self::PrepareSession { run, .. }
            | Self::SessionTool { run, .. }
            | Self::PrepareTerminal { run, .. }
            | Self::TerminalTool { run, .. }
            | Self::Delta { run, .. }
            | Self::Checkpoint { run, .. }
            | Self::Approval { run, .. }
            | Self::Ended { run, .. } => run,
        }
    }
}

fn send(
    tx: &SyncSender<WorkerEvent>,
    mut event: WorkerEvent,
    cancel: &CancelToken,
) -> Result<(), String> {
    loop {
        if cancel.is_cancelled() {
            return Err("任务已停止".into());
        }
        match tx.try_send(event) {
            Ok(()) => return Ok(()),
            Err(TrySendError::Disconnected(_)) => return Err("运行时已断开".into()),
            Err(TrySendError::Full(returned)) => {
                event = returned;
                thread::park_timeout(Duration::from_millis(2));
            }
        }
    }
}

pub(crate) fn assistant_placeholder() -> Message {
    Message {
        id: crate::controller::unique_id(),
        role: "assistant".into(),
        text: String::new(),
        thinking: String::new(),
        complete: false,
        tool_calls: Vec::new(),
        tool_call_id: None,
        metadata: crate::chat::MessageMetadata {
            timestamp: crate::controller::now_millis(),
            ..Default::default()
        },
    }
}

fn checkpoint(
    run: &str,
    messages: &[Message],
    usage: UsageReport,
    tx: &SyncSender<WorkerEvent>,
    cancel: &CancelToken,
) -> Result<(), String> {
    budget(messages)?;
    let (reply, receive) = mpsc::sync_channel(1);
    send(
        tx,
        WorkerEvent::Checkpoint {
            run: run.into(),
            messages: crate::transcript_redaction::messages_for_transcript(messages),
            usage,
            reply,
        },
        cancel,
    )?;
    loop {
        if cancel.is_cancelled() {
            return Err("任务已停止".into());
        }
        match receive.recv_timeout(Duration::from_millis(20)) {
            Ok(result) => return result,
            Err(RecvTimeoutError::Timeout) => (),
            Err(RecvTimeoutError::Disconnected) => {
                return Err("持久化确认连接中断，工具未继续执行".into());
            }
        }
    }
}

fn request_usage(
    run: &str,
    record: crate::state::UsageRecord,
    tx: &SyncSender<WorkerEvent>,
    cancel: &CancelToken,
) -> Result<(), String> {
    let pending = record.pending;
    let (reply, receive) = mpsc::sync_channel(1);
    let event = WorkerEvent::RequestUsage {
        run: run.into(),
        record,
        reply,
    };
    if pending {
        send(tx, event, cancel)?;
    } else {
        tx.send(event).map_err(|_| "请求用量保存连接中断")?;
    }
    receive.recv().map_err(|_| "请求用量确认中断")?
}

pub(crate) fn compaction_fingerprint(
    messages: &[Message],
    ids: &[String],
) -> Result<String, String> {
    crate::context::compaction_source_fingerprint(messages, ids, &|| false)
}

struct CompactionPersistence<'a> {
    run: &'a str,
    messages: &'a [Message],
    tx: &'a SyncSender<WorkerEvent>,
    cancel: &'a CancelToken,
}

impl crate::compaction_runtime::CompactionCallbacks for CompactionPersistence<'_> {
    fn busy(&mut self, busy: bool) {
        let _ = self.tx.send(WorkerEvent::CompactionBusy {
            run: self.run.into(),
            busy,
        });
    }
    fn usage(&mut self, record: crate::state::UsageRecord) -> Result<(), String> {
        request_usage(self.run, record, self.tx, self.cancel)
    }

    fn checkpoint(
        &mut self,
        plan: &crate::context::CompactionPlan,
        body: &str,
        usage: UsageReport,
    ) -> Result<crate::chat::ContextCheckpoint, String> {
        let checkpoint = crate::chat::ContextCheckpoint {
            id: crate::controller::unique_id(),
            at: crate::controller::now_millis(),
            from_message_id: plan.from_message_id.clone(),
            to_message_id: plan.to_message_id.clone(),
            anchor_id: plan.anchor_id.clone(),
            summary: body.into(),
            messages: plan.covered_ids.len(),
            prompt_tokens: plan.prompt_estimate.tokens.min(i64::MAX as u64) as i64,
            extra: std::collections::BTreeMap::from([
                (
                    "prompt_tokens_source".into(),
                    serde_json::Value::String("context_estimate".into()),
                ),
                (
                    "compression_request_prompt_tokens".into(),
                    serde_json::to_value(usage.prompt_tokens).unwrap_or(serde_json::Value::Null),
                ),
            ]),
        };
        let (reply, receive) = mpsc::sync_channel(1);
        send(
            self.tx,
            WorkerEvent::SaveCompaction {
                run: self.run.into(),
                checkpoint: checkpoint.clone(),
                covered_ids: plan.covered_ids.clone(),
                fingerprint: compaction_fingerprint(self.messages, &plan.covered_ids)?,
                reply,
            },
            self.cancel,
        )?;
        receive.recv().map_err(|_| "摘要保存确认中断")??;
        Ok(checkpoint)
    }
}

fn budget(messages: &[Message]) -> Result<(), String> {
    if messages.len() > 131_072 {
        return Err("会话消息数超过上限，任务已停止".into());
    }
    let mut bytes = 0usize;
    for message in messages {
        let amount = message
            .text
            .len()
            .saturating_add(message.thinking.len())
            .saturating_add(
                message
                    .tool_calls
                    .iter()
                    .map(|call| call.id.len() + call.name.len() + call.arguments.len())
                    .sum::<usize>(),
            );
        bytes = bytes.saturating_add(amount);
        if bytes > MAX_HISTORY_BYTES {
            return Err("会话达到 512 MiB 分段历史预算，任务已停止且原历史保留".into());
        }
    }
    Ok(())
}

fn storage_budget(
    run: &str,
    messages: &[Message],
    reserve: usize,
    tx: &SyncSender<WorkerEvent>,
    cancel: &CancelToken,
) -> Result<usize, String> {
    let (reply, receive) = mpsc::sync_channel(1);
    send(
        tx,
        WorkerEvent::StorageBudget {
            run: run.into(),
            messages: messages.to_vec(),
            reserve,
            reply,
        },
        cancel,
    )?;
    receive.recv().map_err(|_| "存储空间确认中断，未执行")?
}

fn history_bytes(messages: &[Message]) -> usize {
    messages
        .iter()
        .map(|message| {
            message.text.len()
                + message.thinking.len()
                + message
                    .tool_calls
                    .iter()
                    .map(|call| call.id.len() + call.name.len() + call.arguments.len())
                    .sum::<usize>()
        })
        .sum()
}

pub(crate) fn approve(
    run: &str,
    child_id: Option<&str>,
    call: &ToolCall,
    tx: &SyncSender<WorkerEvent>,
    cancel: &CancelToken,
) -> Result<bool, String> {
    approve_with_mode(
        run,
        child_id,
        call,
        crate::subagent_configuration::ApprovalMode::Ask,
        tx,
        cancel,
    )
}

pub(crate) fn approve_with_mode(
    run: &str,
    child_id: Option<&str>,
    call: &ToolCall,
    mode: crate::subagent_configuration::ApprovalMode,
    tx: &SyncSender<WorkerEvent>,
    cancel: &CancelToken,
) -> Result<bool, String> {
    approve_with_summary(run, child_id, call, None, mode, tx, cancel)
}
pub(crate) fn approve_with_summary(
    run: &str,
    child_id: Option<&str>,
    call: &ToolCall,
    summary: Option<String>,
    mode: crate::subagent_configuration::ApprovalMode,
    tx: &SyncSender<WorkerEvent>,
    cancel: &CancelToken,
) -> Result<bool, String> {
    let (reply, receive) = mpsc::sync_channel(1);
    let deadline = Instant::now() + Duration::from_secs(5 * 60);
    send(
        tx,
        WorkerEvent::Approval {
            run: run.into(),
            child_id: child_id.map(str::to_owned),
            approval_id: crate::controller::unique_id(),
            expires: deadline,
            call: call.clone(),
            summary,
            mode,
            reply,
        },
        cancel,
    )?;
    loop {
        if cancel.is_cancelled() {
            return Err("任务已停止，未执行待审批工具".into());
        }
        if Instant::now() >= deadline {
            return Ok(false);
        }
        match receive.recv_timeout(Duration::from_millis(20)) {
            Ok(approved) => return Ok(approved),
            Err(RecvTimeoutError::Timeout) => (),
            Err(RecvTimeoutError::Disconnected) => return Err("审批连接中断，未执行工具".into()),
        }
    }
}

fn execute_skill(
    run: &str,
    child_id: Option<&str>,
    operation: crate::skills::Operation,
    tx: &SyncSender<WorkerEvent>,
    cancel: &CancelToken,
) -> Result<serde_json::Value, String> {
    let (reply, receive) = mpsc::sync_channel(1);
    send(
        tx,
        WorkerEvent::Skill {
            run: run.into(),
            child_id: child_id.map(str::to_owned),
            operation,
            reply,
        },
        cancel,
    )?;
    // Do not abandon the acknowledgement after dispatch: a write may have committed.
    receive
        .recv()
        .map_err(|_| "技能操作确认连接中断，效果未知，请先检查再决定是否重试".into())
}

pub(crate) fn prepare_for_worker(
    run: &str,
    child_id: Option<&str>,
    call: &ToolCall,
    tx: &SyncSender<WorkerEvent>,
    cancel: &Arc<CancelToken>,
) -> Result<tools::Prepared, String> {
    if call.name == "skill_download" {
        let operation = crate::skills::prepare(call)?;
        let crate::skills::Operation::Download { url } = operation else {
            return Err("技能下载准备类型无效".into());
        };
        let nonce = crate::controller::unique_id();
        let result = execute_skill(
            run,
            child_id,
            crate::skills::Operation::PrepareDownload { url, nonce },
            tx,
            cancel,
        )?;
        if result.get("ok").and_then(serde_json::Value::as_bool) != Some(true) {
            return Err(result
                .get("error")
                .and_then(serde_json::Value::as_str)
                .unwrap_or("技能下载准备失败")
                .into());
        }
        let target: crate::skills::DownloadTarget = serde_json::from_value(
            result
                .get("prepared")
                .cloned()
                .ok_or("技能下载缺少冻结身份")?,
        )
        .map_err(|_| "技能下载冻结身份无效")?;
        return Ok(tools::Prepared {
            path: format!(
                "Download {} into {} (disabled)",
                target.url,
                target.workspace.display()
            ),
            operation: tools::Operation::Skill(crate::skills::Operation::FrozenDownload(
                crate::skills::FrozenDownload {
                    target,
                    started: Arc::new(std::sync::atomic::AtomicBool::new(false)),
                },
            )),
        });
    }
    if call.name.starts_with("web_") {
        let (reply, receive) = mpsc::sync_channel(1);
        send(
            tx,
            WorkerEvent::PrepareWeb {
                run: run.into(),
                child_id: child_id.map(str::to_owned),
                call: call.clone(),
                reply,
            },
            cancel,
        )?;
        let prepared = receive
            .recv()
            .map_err(|_| "网页准备连接中断，未发送请求".to_owned())??;
        return Ok(tools::Prepared {
            path: prepared.approval_summary.clone(),
            operation: tools::Operation::Web(prepared),
        });
    }
    if crate::terminal_tools::definitions()
        .iter()
        .any(|tool| tool.name == call.name)
    {
        let (reply, receive) = mpsc::sync_channel(1);
        send(
            tx,
            WorkerEvent::PrepareTerminal {
                run: run.into(),
                child_id: child_id.map(str::to_owned),
                call: call.clone(),
                reply,
            },
            cancel,
        )?;
        let prepared = receive
            .recv()
            .map_err(|_| "终端准备连接中断，未执行".to_owned())??;
        Ok(tools::Prepared {
            path: prepared.summary(),
            operation: tools::Operation::Terminal(prepared),
        })
    } else {
        tools::prepare(call)
    }
}

pub(crate) fn execute_prepared(
    run: &str,
    child_id: Option<&str>,
    prepared: &tools::Prepared,
    workspace: &Path,
    runtime: &tokio::runtime::Runtime,
    tx: &SyncSender<WorkerEvent>,
    cancel: &Arc<CancelToken>,
) -> Result<serde_json::Value, String> {
    let _shared_device = crate::tool_execution::operation_guard(&prepared.operation, cancel)?;
    match &prepared.operation {
        tools::Operation::AndroidSettings(prepared) => {
            Ok(crate::android_shell_tools::execute(prepared, cancel))
        }
        tools::Operation::Terminal(prepared) => {
            let (reply, receive) = mpsc::sync_channel(1);
            send(
                tx,
                WorkerEvent::TerminalTool {
                    run: run.into(),
                    child_id: child_id.map(str::to_owned),
                    prepared: prepared.clone(),
                    reply,
                },
                cancel,
            )?;
            receive
                .recv()
                .map_err(|_| "终端工具确认连接中断，输入或会话可能已建立".to_owned())?
        }
        tools::Operation::Shell(prepared) => {
            prepared
                .owner
                .validate_frozen(prepared.revision, prepared.request.route)?;
            use std::sync::atomic::Ordering;
            if prepared.started.swap(true, Ordering::AcqRel) {
                return Err("Shell工具已派发过，不重复执行".into());
            }
            let owner = prepared.owner.clone();
            let revision = prepared.revision;
            let route = prepared.request.route;
            let flag = crate::controller::cancellation::flag(
                cancel.clone(),
                Some(Arc::new(move || {
                    owner.validate_frozen(revision, route).is_ok()
                })),
            )?;
            let outcome =
                crate::shell_runner::execute(&prepared.request, flag, prepared.executor.as_deref());
            Ok(
                json!({"ok":outcome.succeeded(),"started":outcome.started,"route":outcome.route,
                "identity_confirmed":outcome.identity_confirmed,"primary_exit_confirmed":outcome.primary_exit_confirmed,
                "group_cleanup_confirmed":outcome.group_cleanup_confirmed,"exit_code":outcome.exit_code,
                "cancel_requested":outcome.cancel_requested,"timed_out":outcome.timed_out,
                "stdout":outcome.stdout,"stderr":outcome.stderr,"stdout_bytes":outcome.stdout_bytes,"stderr_bytes":outcome.stderr_bytes,
                "truncated":outcome.truncated,"error":outcome.error,"file_activities":outcome.file_changes.activities,"notice":outcome.file_changes.notice}),
            )
        }
        tools::Operation::Platform(prepared) => {
            let (reply, receive) = mpsc::sync_channel(1);
            send(
                tx,
                WorkerEvent::SystemTool {
                    run: run.into(),
                    child_id: child_id.map(str::to_owned),
                    prepared: prepared.clone(),
                    reply,
                },
                cancel,
            )?;
            let outcome = receive
                .recv()
                .map_err(|_| "系统工具确认连接中断，效果可能已发生".to_owned())??;
            crate::controller::complete_system_tool(outcome, child_id.is_some())
        }
        tools::Operation::Job { prepared, call_id } => {
            let (reply, receive) = mpsc::sync_channel(1);
            send(
                tx,
                WorkerEvent::Job {
                    run: run.into(),
                    child_id: child_id.map(str::to_owned),
                    call_id: call_id.clone(),
                    prepared: prepared.clone(),
                    reply,
                },
                cancel,
            )?;
            receive
                .recv()
                .map_err(|_| "作业操作确认连接中断，效果未知，请先核对".into())
        }
        tools::Operation::Schedule(operation) => {
            let (reply, receive) = mpsc::sync_channel(1);
            send(
                tx,
                WorkerEvent::Schedule {
                    run: run.into(),
                    child_id: child_id.map(str::to_owned),
                    operation: operation.clone(),
                    reply,
                },
                cancel,
            )?;
            receive
                .recv()
                .map_err(|_| "提醒操作确认连接中断，效果未知，请先核对".into())
        }
        tools::Operation::Skill(crate::skills::Operation::FrozenDownload(download)) => {
            if download
                .started
                .swap(true, std::sync::atomic::Ordering::AcqRel)
            {
                return Err("技能下载调用已派发，不重复执行".into());
            }
            if workspace != download.target.workspace {
                return Err("技能下载冻结目录与实际任务不一致，未连接目标".into());
            }
            let skills = crate::skill_import::download_for_tool(&download.target, cancel.clone())?;
            // The actor verifies actual main/child activity and namespace before one commit.
            let paths: Vec<_> = skills.iter().map(|s| s.document_path.clone()).collect();
            let acknowledgement = execute_skill(
                run,
                child_id,
                crate::skills::Operation::ImportDownloaded {
                    target: download.target.clone(),
                    skills,
                },
                tx,
                cancel,
            );
            match acknowledgement {
                Ok(mut value) => {
                    if value.get("ok").and_then(serde_json::Value::as_bool) != Some(true) {
                        crate::skill_import::download_receipt(
                            &download.target.workspace,
                            &paths,
                            false,
                            value
                                .get("effect_unknown")
                                .and_then(serde_json::Value::as_bool)
                                == Some(true),
                        );

                        value["retained_document_paths"] = json!(paths);
                        value["effects_may_have_occurred"] = json!(true);
                    }
                    Ok(value)
                }
                Err(error) => {
                    crate::skill_import::download_receipt(
                        &download.target.workspace,
                        &paths,
                        false,
                        true,
                    );
                    Ok(
                        json!({"ok":false,"error":error,"effects_may_have_occurred":true,"retained_document_paths":paths,"notice":"Downloaded originals remain; catalogue commit was not confirmed. Do not retry blindly."}),
                    )
                }
            }
        }
        tools::Operation::Skill(operation) => {
            execute_skill(run, child_id, operation.clone(), tx, cancel)
        }
        tools::Operation::Todo(operation) => {
            let (reply, receive) = mpsc::sync_channel(1);
            send(
                tx,
                WorkerEvent::Todo {
                    run: run.into(),
                    child_id: child_id.map(str::to_owned),
                    operation: operation.clone(),
                    reply,
                },
                cancel,
            )?;
            receive
                .recv()
                .map_err(|_| "任务清单确认连接中断，效果未知，请先核对".into())
        }
        tools::Operation::Web(operation) => {
            Ok(runtime.block_on(crate::web::execute(operation, cancel.clone())))
        }
        _ => Ok(tools::execute(workspace.to_path_buf(), prepared, cancel)),
    }
}

pub(crate) fn wire_message(message: &Message) -> rust_protocol::Message {
    rust_protocol::Message {
        role: message.role.clone(),
        text: message.text.clone(),
        tool_calls: message
            .tool_calls
            .iter()
            .map(|call| rust_protocol::ToolCall {
                id: call.id.clone(),
                name: call.name.clone(),
                arguments: call.arguments.clone(),
            })
            .collect(),
        tool_call_id: message.tool_call_id.clone(),
        images_base64: message.metadata.all_images().to_vec(),
    }
}

pub(crate) fn parse_tool_calls(
    calls: Vec<rust_protocol::ToolCall>,
) -> Result<Vec<ToolCall>, String> {
    if calls.len() > MAX_CALLS_PER_TURN {
        return Err("本轮工具调用超过 16 个，未执行本批工具".into());
    }
    let mut used_ids = HashSet::new();
    let mut parsed = Vec::with_capacity(calls.len());
    for call in calls {
        if call.id.is_empty()
            || call.id.len() > 512
            || call.name.is_empty()
            || call.name.len() > 128
            || call.arguments.len() > 1024 * 1024
            || !used_ids.insert(call.id.clone())
        {
            return Err("模型返回了无效或重复的工具调用标识，未执行本批工具".into());
        }
        parsed.push(ToolCall {
            id: call.id,
            name: call.name,
            arguments: call.arguments,
        });
    }
    Ok(parsed)
}

fn goal_command(
    run: &str,
    command: GoalCommand,
    tx: &SyncSender<WorkerEvent>,
    cancel: &CancelToken,
) -> Result<Option<Goal>, String> {
    let (reply, receive) = mpsc::sync_channel(1);
    send(
        tx,
        WorkerEvent::Goal {
            run: run.into(),
            command,
            reply,
        },
        cancel,
    )?;
    receive
        .recv()
        .map_err(|_| "目标操作确认中断，请先检查已保存状态".to_owned())?
}

fn wait_for_goal(
    run: &str,
    goal: &mut Goal,
    tx: &SyncSender<WorkerEvent>,
    cancel: &CancelToken,
    steering: &Steering,
) -> Result<(), String> {
    if goal.wake_at == 0 {
        return Ok(());
    }
    let now = crate::controller::now_millis();
    let delay = goal.wake_at.saturating_sub(now).clamp(0, 60_000) as u64;
    let deadline = Instant::now() + Duration::from_millis(delay);
    while let Some(remaining) = deadline.checked_duration_since(Instant::now()) {
        if cancel.is_cancelled() {
            return Err("任务已停止".into());
        }
        if steering.has_pending() {
            break;
        }
        thread::sleep(remaining.min(Duration::from_millis(50)));
    }
    *goal = goal_command(
        run,
        GoalCommand::ConsumeWake {
            wake_at: goal.wake_at,
        },
        tx,
        cancel,
    )?
    .ok_or("缺少运行目标")?;
    Ok(())
}

fn aggregate(previous: UsageReport, current: UsageReport, first: bool) -> UsageReport {
    if first {
        return current;
    }
    let add = |a: Option<i32>, b: Option<i32>| a.zip(b).and_then(|(a, b)| a.checked_add(b));
    UsageReport {
        prompt_tokens: add(previous.prompt_tokens, current.prompt_tokens),
        completion_tokens: add(previous.completion_tokens, current.completion_tokens),
        reasoning_tokens: add(previous.reasoning_tokens, current.reasoning_tokens),
        cached_tokens: add(previous.cached_tokens, current.cached_tokens),
    }
}

pub fn spawn(
    run: String,
    settings: Settings,
    messages: Vec<Message>,
    workspace: PathBuf,
    context: RunContext,
    cancel: Arc<CancelToken>,
    steering: Arc<Steering>,
    tx: SyncSender<WorkerEvent>,
) -> Result<(), String> {
    thread::Builder::new()
        .name("rust-agent-run".into())
        .spawn(move || {
            let worker_started = Instant::now();
            let mut outcome = Outcome {
                elapsed_ms: None,
                messages,
                usage: UsageReport::default(),
                requests: 0,
                error: None,
            };
            let mut ledger = run_ledger::RunLedger::new(settings.max_tool_rounds);
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                run_loop(
                    &run,
                    settings,
                    workspace,
                    context,
                    &cancel,
                    &steering,
                    &tx,
                    &mut outcome,
                    &mut ledger,
                )
            }));
            outcome.error = match result {
                Ok(Ok(())) => None,
                Ok(Err(error)) => Some(error),
                Err(_) => Some("Agent 执行线程异常终止，已保留最近检查点".into()),
            };
            if outcome.error.is_some() {
                close_pending_tools(&mut outcome.messages);
                ledger.attach_summary(&mut outcome.messages, &run);
            }
            crate::transcript_redaction::redact_messages(&mut outcome.messages);
            // A final event is delivered after network/file resources are released. The receiver
            // owns shutdown; disconnecting it unblocks this bounded send.
            outcome.elapsed_ms = i64::try_from(worker_started.elapsed().as_millis()).ok();
            let _ = tx.send(WorkerEvent::Ended {
                run,
                result: outcome,
            });
        })
        .map(|_| ())
        .map_err(|_| "无法创建 Agent 执行线程".into())
}

fn run_loop(
    run: &str,
    settings: Settings,
    workspace: PathBuf,
    mut context: RunContext,
    cancel: &Arc<CancelToken>,
    steering: &Steering,
    tx: &SyncSender<WorkerEvent>,
    outcome: &mut Outcome,
    ledger: &mut run_ledger::RunLedger,
) -> Result<(), String> {
    cancel.set_deadline(MAX_RUN_TIME);
    let max_requests = if context.managed_goal {
        MAX_GOAL_ROUNDS
    } else if context.compaction_only {
        MAX_COMPACTION_REQUESTS
    } else {
        usize::MAX
    };
    let request_budget = Arc::new(network::RequestBudget::new(max_requests));
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|_| "无法创建网络运行时")?;
    let profile_prefix = profile_runtime::injected(run, None, tx, cancel)?;
    if !profile_prefix.is_empty() {
        context.system_prompt.push_str("\n\n");
        context.system_prompt.push_str(&profile_prefix);
    }
    if context.compaction_only {
        let prefix = if context.system_prompt.is_empty() {
            Vec::new()
        } else {
            vec![rust_protocol::Message::system(
                context.system_prompt.clone(),
            )]
        };
        let input = crate::context::ContextInput {
            history: &outcome.messages,
            active_runs: &context.active_runs,
            checkpoints: &context.checkpoints,
            system_prefix: &prefix,
            pending_run: None,
            exclude_message_id: None,
            image_input: settings.behavior.image_input,
        };
        let estimate = context_estimate(&settings, input, cancel)?;
        let compacted = crate::compaction_runtime::maybe_compact_with_budget(
            crate::compaction_runtime::CompactionRequest {
                input,
                settings: &settings,
                run,
                runtime: &runtime,
                parent_cancel: cancel,
                request_cancel: Arc::new(CancelToken::new()),
                manual: true,
                auto: false,
                estimate,
                basis: None,
            },
            &mut crate::context::CompactionRetryGate::default(),
            &mut CompactionPersistence {
                run,
                messages: &outcome.messages,
                tx,
                cancel,
            },
            Some(request_budget.clone()),
        )?;
        return match compacted {
            crate::compaction_runtime::CompactionOutcome::Saved {
                usage, requests, ..
            } => {
                accumulate_summaries(outcome, usage, requests);
                Ok(())
            }
            crate::compaction_runtime::CompactionOutcome::Rejected {
                reason,
                usage,
                requests,
            } => {
                accumulate_summaries(outcome, usage, requests);
                Err(reason)
            }
            crate::compaction_runtime::CompactionOutcome::Skipped => {
                Err("No complete history segment is available to compress".into())
            }
        };
    }
    let mut goal = if context.managed_goal {
        Some(goal_command(run, GoalCommand::OwnedSnapshot, tx, cancel)?.ok_or("缺少运行目标")?)
    } else {
        None
    };
    let goal_tools = if goal.is_some() {
        goal::definitions()
    } else {
        Vec::new()
    };
    let child_tools = if settings.subagents_enabled {
        subagents::definitions()
    } else {
        Vec::new()
    };
    let definitions: Vec<_> =
        tools::definitions()
            .into_iter()
            .chain(goal_tools)
            .chain(child_tools)
            .chain(crate::plugin_runtime::definitions(&context.plugins))
            .chain(context.child_inputs.mcp_tools.iter().map(|binding| {
                rust_protocol::ToolDefinition {
                    name: binding.registered_name.clone(),
                    description: binding.description.clone(),
                    parameters: binding.input_schema.clone(),
                }
            }))
            .filter(|tool| !context.disabled_tools.contains(&tool.name))
            .collect();
    let tool_tokens = ContextReading::tool_tokens(&definitions);
    let mut child_inputs = context.child_inputs.clone();
    child_inputs.available_tools = definitions
        .iter()
        .filter(|tool| crate::subagent_configuration::is_child_tool(&tool.name))
        .cloned()
        .collect();
    child_inputs.disabled_tools = context.disabled_tools.clone();
    child_inputs.plugins = context.plugins.clone();
    child_inputs.plugin_context = context.plugin_context.clone();
    child_inputs.parent_goal = goal
        .as_ref()
        .map(|goal| goal.text.clone())
        .unwrap_or_default();
    let mut children = settings
        .subagents_enabled
        .then(|| Pool::new(run, tx, &workspace, child_inputs));
    let started = Instant::now();
    let mut tool_rounds = round_budget::ToolRoundBudget::new(settings.max_tool_rounds);
    let mut replies_without_progress = 0;
    let mut compaction_retry = crate::context::CompactionRetryGate::default();
    let mut prompt_basis: Option<crate::context::ReportedPrompt> = None;
    let mut initial_batch = true;
    'turns: loop {
        if let Some(message) = outcome
            .messages
            .last_mut()
            .filter(|message| message.role == "assistant" && !message.complete)
        {
            message.metadata.run_id = Some(run.into());
            message.metadata.model = Some(settings.model.clone());
        }
        if cancel.is_cancelled() {
            return Err("任务已停止".into());
        }
        if started.elapsed() >= MAX_RUN_TIME {
            return Err("本次任务达到 30 分钟上限，已保留历史".into());
        }
        drain_child_launches(run, &mut children, steering, outcome, tx, cancel)?;
        let mut session_context = session_runtime::snapshot(
            &settings,
            None,
            context.checkpoints.len(),
            outcome.messages.len(),
        );
        let tool_limit_reached = tool_rounds.exhausted();
        if tool_limit_reached
            && let Some(goal) = goal
                .as_mut()
                .filter(|goal| goal.status == GoalStatus::Active)
        {
            *goal = goal_command(
                run,
                GoalCommand::Block {
                    detail: format!(
                        "已达到设置中的最大工具轮数（{}），可手动继续",
                        settings.max_tool_rounds
                    ),
                },
                tx,
                cancel,
            )?
            .ok_or("缺少运行目标")?;
        }
        let calls = 'calls: {
            if !tool_limit_reached {
                if initial_batch {
                    initial_batch = false;
                    if let Some(initial) = context.initial_tool.take() {
                        break 'calls vec![initial];
                    }
                }
                if let Some(call) = steering_caller::direct_at_boundary(
                    run,
                    steering,
                    &mut outcome.messages,
                    tx,
                    cancel,
                )? {
                    break 'calls vec![call];
                }
            }
            if request_budget.used() >= max_requests {
                return Err("本次运行的实际模型/压缩请求预算已耗尽".into());
            }
            let final_request = request_budget.used().saturating_add(1) == max_requests;
            let child_summary = if (tool_limit_reached || final_request) && goal.is_none() {
                if children.as_ref().is_some_and(|pool| pool.has_children()) {
                    wait_for_child_results(run, &mut children, steering, outcome, tx, cancel)?;
                    true
                } else {
                    false
                }
            } else {
                false
            };
            if tool_limit_reached && goal.is_none() && !child_summary {
                return Err(format!(
                    "已达到设置中的最大工具轮数（{}），已保留工具结果，可继续",
                    settings.max_tool_rounds
                ));
            }
            if final_request && goal.is_some() {
                match goal.as_mut() {
                    Some(goal) if goal.status == GoalStatus::Active => {
                        *goal = goal_command(
                            run,
                            GoalCommand::Block {
                                detail: "本次剩余请求预算用于结果汇总，可手动继续".into(),
                            },
                            tx,
                            cancel,
                        )?
                        .ok_or("缺少运行目标")?;
                    }
                    _ => {}
                }
            }
            let summary = goal
                .as_ref()
                .filter(|goal| matches!(goal.status, GoalStatus::Completed | GoalStatus::Blocked));
            if summary.is_some() {
                wait_for_child_results(run, &mut children, steering, outcome, tx, cancel)?;
            }
            if let Some(pool) = children.as_mut() {
                if append_child_results(pool, &mut outcome.messages, ledger)? {
                    checkpoint(run, &outcome.messages, outcome.usage, tx, cancel)?;
                }
            }
            let summary_only = summary.is_some() || child_summary;
            let request_cancel = if summary_only {
                cancel.clone()
            } else {
                loop {
                    drain_child_launches(run, &mut children, steering, outcome, tx, cancel)?;
                    if let Some(request) = steering.begin_request() {
                        break request;
                    }
                    if let Some(call) = steering_caller::direct_at_boundary(
                        run,
                        steering,
                        &mut outcome.messages,
                        tx,
                        cancel,
                    )? {
                        break 'calls vec![call];
                    }
                }
            };
            let current = outcome.messages.last().ok_or("缺少助手消息")?.id.clone();
            let mut wire = vec![rust_protocol::Message::system(
                crate::session_tools::MAIN_SYSTEM_PROMPT,
            )];
            wire.push(rust_protocol::Message::system(
                crate::session_tools::approval_prompt(settings.approval_mode),
            ));
            if !context.system_prompt.is_empty() {
                wire.push(rust_protocol::Message::system(
                    context.system_prompt.clone(),
                ));
            }
            if let Some(goal) = goal
                .as_ref()
                .filter(|goal| goal.status == GoalStatus::Active)
            {
                wire.push(rust_protocol::Message::system(goal.prompt()?));
            }
            let estimate = context_estimate(
                &settings,
                crate::context::ContextInput {
                    history: &outcome.messages,
                    active_runs: &context.active_runs,
                    checkpoints: &context.checkpoints,
                    system_prefix: &wire,
                    pending_run: Some(run),
                    exclude_message_id: Some(&current),
                    image_input: settings.behavior.image_input,
                },
                cancel,
            )?;
            checkpoint(run, &outcome.messages, outcome.usage, tx, cancel)?;
            let summary_cancel = Arc::new(CancelToken::new());
            let previous_request = if summary_only {
                None
            } else {
                steering.replace_request(summary_cancel.clone())
            };
            let compressed = crate::compaction_runtime::maybe_compact_with_budget(
                crate::compaction_runtime::CompactionRequest {
                    input: crate::context::ContextInput {
                        history: &outcome.messages,
                        active_runs: &context.active_runs,
                        checkpoints: &context.checkpoints,
                        system_prefix: &wire,
                        pending_run: Some(run),
                        exclude_message_id: Some(&current),
                        image_input: settings.behavior.image_input,
                    },
                    settings: &settings,
                    run,
                    runtime: &runtime,
                    parent_cancel: cancel,
                    request_cancel: summary_cancel,
                    manual: steering.take_compaction(),
                    auto: settings.behavior.auto_compress,
                    estimate,
                    basis: prompt_basis,
                },
                &mut compaction_retry,
                &mut CompactionPersistence {
                    run,
                    messages: &outcome.messages,
                    tx,
                    cancel,
                },
                Some(Arc::new(request_budget.reserve_last())),
            );
            if !summary_only {
                steering.restore_request(previous_request);
            }
            let compressed = match compressed {
                Ok(compressed) => compressed,
                Err(_) if !summary_only && !cancel.is_cancelled() && steering.has_pending() => {
                    steering.end_request();
                    continue 'turns;
                }
                Err(error) => return Err(error),
            };
            match compressed {
                crate::compaction_runtime::CompactionOutcome::Saved {
                    checkpoint: saved,
                    usage,
                    requests,
                } => {
                    accumulate_summaries(outcome, usage, requests);
                    context.checkpoints.push(saved);
                    prompt_basis = None;
                }
                crate::compaction_runtime::CompactionOutcome::Rejected {
                    usage, requests, ..
                } => {
                    accumulate_summaries(outcome, usage, requests);
                }
                crate::compaction_runtime::CompactionOutcome::Skipped => {}
            }
            if !summary_only && steering.has_pending() {
                steering.end_request();
                continue 'turns;
            }
            // Compression shares the real send counter. Re-enter the boundary
            // if it just consumed the penultimate slot, so the final request
            // carries the actual goal/child summary rather than ordinary tools.
            if !summary_only
                && request_budget.used().saturating_add(1) >= max_requests
                && (goal.is_some() || children.as_ref().is_some_and(|pool| pool.has_children()))
            {
                steering.end_request();
                continue 'turns;
            }
            let built = crate::context::build_context(
                crate::context::ContextInput {
                    history: &outcome.messages,
                    active_runs: &context.active_runs,
                    checkpoints: &context.checkpoints,
                    system_prefix: &wire,
                    pending_run: Some(run),
                    exclude_message_id: Some(&current),
                    image_input: settings.behavior.image_input,
                },
                || cancel.is_cancelled(),
            )?;
            let request_estimate = built.estimate;
            session_context = session_runtime::snapshot(
                &settings,
                Some(request_estimate),
                context.checkpoints.len(),
                outcome.messages.len(),
            );
            let mut reading = ContextReading::for_request(
                &settings,
                request_estimate,
                &wire,
                if summary_only { 0 } else { tool_tokens },
            );
            wire = built.wire;
            if let Some(goal) = summary {
                wire.push(rust_protocol::Message::user(goal.summary_prompt()));
            } else if child_summary {
                wire.push(rust_protocol::Message::user(
                "Summarize the recorded subagent outcomes for the user, including incomplete or failed work. The work budget is exhausted. Do not call tools.",
            ));
            } else if goal.is_some()
                && wire
                    .last()
                    .is_some_and(|message| message.role == "assistant")
            {
                wire.push(rust_protocol::Message::user(
                "Continue the saved objective using the latest progress and report the outcome.",
            ));
            }
            checkpoint(run, &outcome.messages, outcome.usage, tx, cancel)?;
            let child_reserve = if children
                .as_ref()
                .is_some_and(|pool| pool.has_outstanding_results())
            {
                CHILD_RESULT_RESERVE
            } else {
                0
            };
            let document_capacity =
                storage_budget(run, &outcome.messages, child_reserve, tx, cancel)?;
            let physical_budget = if !summary_only
                && (goal.is_some() || children.as_ref().is_some_and(|pool| pool.has_children()))
            {
                Arc::new(request_budget.reserve_last())
            } else {
                request_budget.clone()
            };
            // One logical model round owns all of its physical retries.
            // Compression and the reserved result-only summary do not spend
            // the user's tool-round setting.
            if !summary_only && !tool_rounds.begin_model_round() {
                steering.end_request();
                continue 'turns;
            }
            if !summary_only && let Some(note) = ledger.take_note() {
                let units = note.encode_utf16().count();
                let tokens = (units as u64).div_ceil(3);
                reading.used = reading.used.saturating_add(tokens);
                reading.history = reading.history.saturating_add(tokens);
                if let Some(estimate) = session_context.estimate.as_mut() {
                    estimate.tokens = estimate.tokens.saturating_add(tokens);
                    estimate.text_units = estimate.text_units.saturating_add(units);
                }
                wire.push(rust_protocol::Message::user(note));
            }
            send(
                tx,
                WorkerEvent::ContextReading {
                    run: run.into(),
                    reading: reading.clone(),
                },
                cancel,
            )?;
            let response = retry_runtime::request(
                run,
                &settings,
                wire,
                if summary_only { &[] } else { &definitions },
                &runtime,
                tx,
                cancel,
                request_cancel,
                physical_budget,
                outcome,
                document_capacity,
                child_reserve,
            )?;
            let interrupted = !summary_only && steering.end_request();
            prompt_basis = response
                .turn
                .usage_report
                .prompt_tokens
                .filter(|tokens| *tokens >= 0)
                .map(|tokens| crate::context::ReportedPrompt {
                    tokens: tokens as u64,
                    text_units: request_estimate.text_units,
                    image_messages: request_estimate.image_messages,
                });
            if let Some(tokens) = response
                .turn
                .usage_report
                .prompt_tokens
                .filter(|tokens| *tokens >= 0)
            {
                send(
                    tx,
                    WorkerEvent::ContextReading {
                        run: run.into(),
                        reading: reading.reported(tokens as u64),
                    },
                    cancel,
                )?;
            }
            if cancel.is_cancelled() {
                return Err("任务已停止".into());
            }
            if interrupted {
                // No tool call from this request has been checkpointed or dispatched.
                outcome.messages.push(assistant_placeholder());
                checkpoint(run, &outcome.messages, outcome.usage, tx, cancel)?;
                continue 'turns;
            }
            if let Some(error) = response.error {
                return Err(error);
            }
            if summary_only {
                let message = outcome.messages.last_mut().ok_or("缺少目标汇总消息")?;
                if message.text.trim().is_empty() || !response.turn.tool_calls.is_empty() {
                    return Err("运行结果已保存，模型未返回有效的无工具汇总".into());
                }
                message.complete = true;
                checkpoint(run, &outcome.messages, outcome.usage, tx, cancel)?;
                steering_caller::finish_local_prefix(
                    run,
                    steering,
                    &mut outcome.messages,
                    tx,
                    cancel,
                )?;
                return checkpoint(run, &outcome.messages, outcome.usage, tx, cancel);
            }
            if response.turn.tool_calls.len() > MAX_CALLS_PER_TURN {
                return Err("本轮工具调用超过16个，未执行本批工具".into());
            }
            break 'calls parse_tool_calls(response.turn.tool_calls)?;
        };
        if calls.len() > MAX_CALLS_PER_TURN {
            if calls.iter().all(|call| {
                outcome.messages.iter().any(|message| {
                    message
                        .tool_calls
                        .iter()
                        .any(|declared| declared.id == call.id)
                })
            }) {
                append_not_started(
                    &mut outcome.messages,
                    &calls,
                    "本批工具调用超过16个，未执行",
                );
            }
            return Err("本批工具调用超过16个，未执行".into());
        }
        let metadata_bytes: usize = calls
            .iter()
            .map(|call: &ToolCall| call.id.len() + call.name.len() + call.arguments.len())
            .sum();
        if history_bytes(&outcome.messages).saturating_add(metadata_bytes)
            > MAX_HISTORY_BYTES - 128 * 1024
        {
            if calls.iter().all(|call| {
                outcome.messages.iter().any(|message| {
                    message
                        .tool_calls
                        .iter()
                        .any(|declared| declared.id == call.id)
                })
            }) {
                append_not_started(
                    &mut outcome.messages,
                    &calls,
                    "会话没有足够空间保存工具调用，未执行本批工具",
                );
            }
            return Err("会话没有足够空间保存工具调用，未执行本批工具".into());
        }
        let message = outcome.messages.last_mut().ok_or("缺少助手消息")?;
        message.complete = true;
        message.tool_calls = calls.clone();
        if let Err(error) = checkpoint(run, &outcome.messages, outcome.usage, tx, cancel) {
            append_not_started(&mut outcome.messages, &calls, &error);
            return Err(error);
        }
        if calls.is_empty() && goal.is_none() {
            let pending_results =
                wait_for_child_results(run, &mut children, steering, outcome, tx, cancel)?;
            steering_caller::finish_local_prefix(run, steering, &mut outcome.messages, tx, cancel)?;
            if !pending_results && !steering.has_pending() {
                return Ok(());
            }
        }
        let had_calls = !calls.is_empty();
        let previous_revision = goal.as_ref().map(|goal| goal.revision);
        let parallel_groups = crate::tool_execution::groups(
            &calls,
            settings.behavior.tool_parallelism(),
            settings.approval_mode,
        );
        let mut committed_parallel_until = 0;
        for (index, call) in calls.iter().enumerate() {
            if index < committed_parallel_until {
                continue;
            }
            if let Some(group) = parallel_groups
                .iter()
                .find(|group| group.start == index && group.len() > 1)
                .filter(|_| {
                    goal.as_ref()
                        .is_none_or(|goal| goal.status == GoalStatus::Active)
                })
            {
                let receipts = match parallel_runtime::execute(
                    run,
                    &calls[group.clone()],
                    &settings,
                    &context,
                    &definitions,
                    &session_context,
                    &workspace,
                    &outcome.messages,
                    children
                        .as_ref()
                        .is_some_and(|pool| pool.has_outstanding_results()),
                    tx,
                    cancel,
                ) {
                    Ok(receipts) => receipts,
                    Err(error) => {
                        append_not_started(&mut outcome.messages, &calls[index..], &error);
                        return Err(error);
                    }
                };
                let mut terminal_error = None;
                // All workers are joined. Preserve every sibling's real effect
                // before a cancellation or failed checkpoint can end the run.
                for (call, receipt) in calls[group.clone()].iter().zip(receipts) {
                    ledger.record(call, &receipt.value)?;
                    let mut message = tool_message(call.id.clone(), receipt.value);
                    message.metadata.run_id = Some(run.into());
                    message.metadata.tool_name = Some(call.name.clone());
                    outcome.messages.push(message);
                    terminal_error = terminal_error.or(receipt.error);
                }
                committed_parallel_until = group.end;
                if let Err(error) = checkpoint(run, &outcome.messages, outcome.usage, tx, cancel) {
                    append_not_started(&mut outcome.messages, &calls[group.end..], &error);
                    return Err(error);
                }
                if let Some(error) = terminal_error {
                    append_not_started(&mut outcome.messages, &calls[group.end..], &error);
                    return Err(error);
                }
                continue;
            }
            let goal_call = matches!(call.name.as_str(), "goal_read" | "goal_update");
            let prepared = if goal
                .as_ref()
                .is_some_and(|goal| goal.status != GoalStatus::Active)
            {
                Err("目标已结束，未执行此工具".to_owned())
            } else if !definitions
                .iter()
                .any(|definition| definition.name == call.name)
            {
                Err("该工具未在本次运行目录中开放，未执行".into())
            } else if context.disabled_tools.contains(&call.name) {
                Err("当前助手已禁用该工具，未执行".to_owned())
            } else if matches!(call.name.as_str(), "profile_read" | "profile_write") {
                profile_runtime::prepare(run, None, call, tx, cancel).map(Prepared::Profile)
            } else if (crate::controller::session_runtime::is_tool(&call.name)) {
                session_runtime::prepare(
                    run,
                    None,
                    call,
                    &settings,
                    session_context.clone(),
                    tx,
                    cancel,
                )
                .map(Prepared::Session)
            } else if goal_call && goal.is_some() {
                goal::prepare(&call).map(Prepared::Goal)
            } else if children.is_some()
                && matches!(
                    call.name.as_str(),
                    "spawn_agent" | "wait_agents" | "list_agents" | "cancel_agent"
                )
            {
                subagents::prepare(&call).map(Prepared::Child)
            } else if let Some((entry, tool)) =
                crate::plugin_runtime::resolve(&context.plugins, &call.name)
            {
                crate::plugin_runtime::prepare(entry, tool, &call, cancel).map(Prepared::Plugin)
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
                prepare_for_worker(run, None, &call, tx, cancel).map(Prepared::Tool)
            };
            let result = match prepared {
                Err(error) => json!({"ok":false,"error":error}),
                Ok(prepared) => {
                    let reserve = match &prepared {
                        Prepared::Child(_) => CHILD_RESULT_RESERVE,
                        Prepared::Tool(tools::Prepared {
                            operation: tools::Operation::Platform(_),
                            ..
                        }) => 18 * 1024 * 1024,
                        Prepared::Mcp(_, _) => 2 * 1024 * 1024,
                        Prepared::Tool(tools::Prepared {
                            operation: tools::Operation::Skill(crate::skills::Operation::List),
                            ..
                        }) => 5 * 1024 * 1024,
                        Prepared::Tool(tools::Prepared {
                            operation: tools::Operation::Web(_),
                            ..
                        }) => 2 * 1024 * 1024,
                        _ => 768 * 1024,
                    };
                    let pending_children = children
                        .as_ref()
                        .is_some_and(|pool| pool.has_outstanding_results());
                    let reserve = reserve
                        + if pending_children && !matches!(prepared, Prepared::Child(_)) {
                            CHILD_RESULT_RESERVE
                        } else {
                            0
                        };
                    if history_bytes(&outcome.messages) > MAX_HISTORY_BYTES.saturating_sub(reserve)
                    {
                        let error = "会话没有足够空间保存工具结果，未执行工具";
                        append_not_started(&mut outcome.messages, &calls[index..], error);
                        return Err(error.into());
                    }
                    if let Err(error) = storage_budget(run, &outcome.messages, reserve, tx, cancel)
                    {
                        append_not_started(&mut outcome.messages, &calls[index..], &error);
                        return Err(error);
                    }
                    let approved = match approve_with_summary(
                        run,
                        None,
                        call,
                        match &prepared {
                            Prepared::Profile(prepared) => Some(prepared.summary()),
                            Prepared::Session(prepared) => Some(prepared.summary()),
                            Prepared::Plugin(prepared) => Some(prepared.approval_summary.clone()),
                            Prepared::Tool(prepared) => Some(prepared.path.clone()),
                            _ => None,
                        },
                        settings.approval_mode,
                        tx,
                        cancel,
                    ) {
                        Ok(approved) => approved,
                        Err(error) => {
                            append_not_started(&mut outcome.messages, &calls[index..], &error);
                            return Err(error);
                        }
                    };
                    if approved {
                        if cancel.is_cancelled() {
                            let error = "任务已停止，未执行工具";
                            append_not_started(&mut outcome.messages, &calls[index..], error);
                            return Err(error.into());
                        }
                        let _tool_activity = crate::tool_execution::activity(run, &call.name);
                        match prepared {
                            Prepared::Profile(prepared) => {
                                match profile_runtime::execute(run, None, &prepared, tx, cancel) {
                                    Ok(value) => value,
                                    Err(error) => {
                                        json!({"ok":false,"effects_may_have_occurred":prepared.writes(),"error":error})
                                    }
                                }
                            }
                            Prepared::Session(prepared) => {
                                match session_runtime::execute(run, None, &prepared, tx, cancel) {
                                    Ok(value) => value,
                                    Err(error) => {
                                        json!({"ok":false,"effects_may_have_occurred":prepared.writes(),"error":error})
                                    }
                                }
                            }
                            Prepared::Child(command) => match execute_child_command(
                                run,
                                &mut children,
                                command,
                                steering,
                                outcome,
                                tx,
                                cancel,
                            ) {
                                Ok(result) => result,
                                Err(error) => json!({"ok":false,"error":error}),
                            },
                            Prepared::Goal(command) => match goal_command(run, command, tx, cancel)
                            {
                                Ok(Some(snapshot)) => {
                                    let result = json!({"ok":true,"goal":snapshot});
                                    goal = Some(snapshot);
                                    result
                                }
                                Ok(None) => json!({"ok":false,"error":"当前会话没有目标"}),
                                Err(error) => json!({"ok":false,"error":error}),
                            },
                            Prepared::Tool(prepared) => match execute_prepared(
                                run, None, &prepared, &workspace, &runtime, tx, cancel,
                            ) {
                                Ok(result) => result,
                                Err(error) => {
                                    let result = json!({
                                        "ok":false,"effects_may_have_occurred":true,"error":error,
                                    });
                                    ledger.record(call, &result)?;
                                    let mut message = tool_message(call.id.clone(), result);
                                    message.metadata.run_id = Some(run.into());
                                    message.metadata.tool_name = Some(call.name.clone());
                                    outcome.messages.push(message);
                                    append_not_started(
                                        &mut outcome.messages,
                                        &calls[index + 1..],
                                        "前一工具确认中断，本调用未执行",
                                    );
                                    return Err(error);
                                }
                            },
                            Prepared::Plugin(prepared) => runtime.block_on(
                                crate::plugin_runtime::execute_prepared(&prepared, cancel.clone()),
                            ),
                            Prepared::Mcp(bridge, binding) => {
                                match bridge.call(binding, &call.arguments, cancel.clone()) {
                                    Ok(value) => value,
                                    Err(error) => {
                                        json!({"ok":false,"not_started":true,"error":error})
                                    }
                                }
                            }
                        }
                    } else {
                        json!({"ok":false,"denied":true,"error":"用户拒绝或审批超时；该操作未执行"})
                    }
                }
            };
            // Once an operation commits, retain its result even if cancellation races the
            // return. The final event persists this snapshot without needing another approval.
            ledger.record(call, &result)?;
            let mut message = tool_message(call.id.clone(), result);
            message.metadata.run_id = Some(run.into());
            message.metadata.tool_name = Some(call.name.clone());
            outcome.messages.push(message);
            if let Err(error) = checkpoint(run, &outcome.messages, outcome.usage, tx, cancel) {
                append_not_started(&mut outcome.messages, &calls[index + 1..], &error);
                return Err(error);
            }
        }
        if let Some(goal) = goal
            .as_mut()
            .filter(|goal| goal.status == GoalStatus::Active)
        {
            if Some(goal.revision) != previous_revision {
                replies_without_progress = 0;
            } else if !had_calls {
                replies_without_progress += 1;
            }
            let blocker = if replies_without_progress >= MAX_GOAL_REPLIES_WITHOUT_PROGRESS {
                Some("连续两次回复未报告目标进展，可手动继续")
            } else if request_budget.used() >= max_requests {
                Some("已达到本次目标的 24 轮请求上限，可手动继续")
            } else {
                None
            };
            if let Some(detail) = blocker {
                *goal = goal_command(
                    run,
                    GoalCommand::Block {
                        detail: detail.into(),
                    },
                    tx,
                    cancel,
                )?
                .ok_or("缺少运行目标")?;
            } else {
                wait_for_goal(run, goal, tx, cancel, steering)?;
            }
        }
        outcome.messages.push(assistant_placeholder());
        checkpoint(run, &outcome.messages, outcome.usage, tx, cancel)?;
    }
}

enum Prepared<'a> {
    Profile(crate::controller::profile_tools_runtime::Prepared),
    Session(crate::controller::session_runtime::Prepared),
    Child(subagents::Command),
    Goal(GoalCommand),
    Tool(tools::Prepared),
    Mcp(&'a crate::mcp::McpBridge, &'a crate::mcp::ToolBinding),
    Plugin(crate::plugin_runtime::Prepared<'a>),
}

pub(crate) fn tool_message(call_id: String, mut result: serde_json::Value) -> Message {
    let images = result
        .as_object_mut()
        .and_then(|value| value.remove(crate::controller::TOOL_IMAGE_PAYLOAD))
        .and_then(|value| serde_json::from_value::<Vec<String>>(value).ok())
        .unwrap_or_default();
    let activities = result
        .get("file_activities")
        .cloned()
        .and_then(|value| serde_json::from_value(value).ok())
        .unwrap_or_default();
    Message {
        id: crate::controller::unique_id(),
        role: "tool".into(),
        text: result.to_string(),
        thinking: String::new(),
        complete: true,
        tool_calls: Vec::new(),
        tool_call_id: Some(call_id),
        metadata: crate::chat::MessageMetadata {
            timestamp: crate::controller::now_millis(),
            file_activities: activities,
            images_base64: images,
            ..Default::default()
        },
    }
}

fn append_not_started(messages: &mut Vec<Message>, calls: &[ToolCall], error: &str) {
    let run_id = messages
        .iter()
        .rev()
        .find_map(|message| message.metadata.run_id.clone());
    messages.extend(calls.iter().map(|call| {
        let mut message = tool_message(
            call.id.clone(),
            json!({
                "ok":false,"not_started":true,"error":error,
            }),
        );
        message.metadata.run_id = run_id.clone();
        message.metadata.tool_name = Some(call.name.clone());
        message
    }));
}

fn wait_for_child_results(
    run: &str,
    pool: &mut Option<Pool>,
    steering: &Steering,
    outcome: &mut Outcome,
    tx: &SyncSender<WorkerEvent>,
    cancel: &CancelToken,
) -> Result<bool, String> {
    loop {
        drain_child_launches(run, pool, steering, outcome, tx, cancel)?;
        let Some(current) = pool.as_ref() else {
            return Ok(false);
        };
        if !current.wait_step(cancel)? {
            if !steering.state().child_launches.is_empty() {
                continue;
            }
            return Ok(!current.unreported().is_empty());
        }
    }
}

fn drain_child_launches(
    run: &str,
    pool: &mut Option<Pool>,
    steering: &Steering,
    outcome: &mut Outcome,
    tx: &SyncSender<WorkerEvent>,
    cancel: &CancelToken,
) -> Result<(), String> {
    let mut launches = steering.take_child_launches().into_iter();
    while let Some(launch) = launches.next() {
        let result = if history_bytes(&outcome.messages).saturating_add(CHILD_RESULT_RESERVE + 4096)
            > MAX_HISTORY_BYTES
        {
            Err("主会话没有空间保存子任务创建记录和结果，未创建".into())
        } else if cancel.is_cancelled() {
            Err("父任务已停止，子任务未创建".into())
        } else if let Err(error) = storage_budget(
            run,
            &outcome.messages,
            CHILD_RESULT_RESERVE + 4096,
            tx,
            cancel,
        ) {
            Err(error)
        } else if let Some(pool) = pool.as_mut() {
            subagents::typed_spawn(launch.task, launch.context, launch.name, launch.options)
                .and_then(|command| pool.execute(command, cancel))
                .and_then(|result| {
                    result
                        .get("children")
                        .and_then(serde_json::Value::as_array)
                        .and_then(|children| children.first())
                        .and_then(|child| child.get("id"))
                        .and_then(serde_json::Value::as_str)
                        .map(str::to_owned)
                        .ok_or_else(|| "子任务未返回已保存的身份".into())
                })
        } else {
            Err("当前运行禁用了子任务".into())
        };
        let created = result.as_ref().ok().cloned();
        // A saved child identity must reach the actor even if a subsequent parent
        // checkpoint or cancellation fails. This is sent before its explanatory note.
        tx.send(WorkerEvent::ChildLaunchResult {
            run: run.into(),
            request_id: launch.request_id,
            result,
        })
        .map_err(|_| "运行时已断开，子任务身份回执未送达")?;
        if let Some(id) = created {
            let note = Message {
                id: crate::controller::unique_id(),
                role: "user".into(),
                text: format!(
                    "用户在本次运行中创建了独立子任务 {id}。请接收其持久化结果并继续汇总。"
                ),
                thinking: String::new(),
                complete: true,
                tool_calls: Vec::new(),
                tool_call_id: None,
                metadata: crate::chat::MessageMetadata {
                    timestamp: crate::controller::now_millis(),
                    run_id: Some(run.into()),
                    steer: true,
                    ..Default::default()
                },
            };
            let placeholder = outcome
                .messages
                .last()
                .filter(|message| {
                    message.role == "assistant"
                        && !message.complete
                        && message.text.is_empty()
                        && message.thinking.is_empty()
                        && message.tool_calls.is_empty()
                })
                .map(|_| outcome.messages.len() - 1);
            if let Some(index) = placeholder {
                outcome.messages.insert(index, note);
            } else if let Some(index) = outcome
                .messages
                .iter()
                .rposition(|message| message.role == "assistant" && !message.tool_calls.is_empty())
                .filter(|&index| {
                    outcome.messages[index + 1..]
                        .iter()
                        .all(|message| message.role == "tool")
                })
            {
                // A typed launch can arrive during wait_agents inside an open
                // tool batch. Preserve the contiguous call/result sequence.
                outcome.messages.insert(index, note);
            } else {
                outcome.messages.push(note);
                outcome.messages.push(assistant_placeholder());
            }
            if let Err(error) = checkpoint(run, &outcome.messages, outcome.usage, tx, cancel) {
                for pending in launches {
                    let _ = tx.send(WorkerEvent::ChildLaunchResult {
                        run: run.into(),
                        request_id: pending.request_id,
                        result: Err("前一子任务已创建，但主会话保存中断；本项未创建".into()),
                    });
                }
                return Err(format!(
                    "子任务 {id} 已创建，主会话创建说明保存失败：{error}"
                ));
            }
        }
    }
    Ok(())
}

fn execute_child_command(
    run: &str,
    pool: &mut Option<Pool>,
    command: subagents::Command,
    steering: &Steering,
    outcome: &mut Outcome,
    tx: &SyncSender<WorkerEvent>,
    cancel: &CancelToken,
) -> Result<serde_json::Value, String> {
    let subagents::Command::Wait { ids, timeout } = command else {
        return pool
            .as_mut()
            .ok_or("当前运行没有子任务池")?
            .execute(command, cancel);
    };
    let selected = pool
        .as_ref()
        .ok_or("当前运行没有子任务池")?
        .selected_ids(ids.as_deref())?;
    let deadline = Instant::now() + timeout;
    loop {
        drain_child_launches(run, pool, steering, outcome, tx, cancel)?;
        let current = pool.as_mut().ok_or("当前运行没有子任务池")?;
        let remaining = deadline.saturating_duration_since(Instant::now());
        let (running, records) = current.wait_selected_step(
            &selected,
            remaining.min(Duration::from_millis(50)),
            cancel,
        )?;
        if !running || Instant::now() >= deadline {
            current.mark_reported(&records);
            return Ok(subagents::result_json(&records));
        }
    }
}

fn append_child_results(
    pool: &mut Pool,
    messages: &mut Vec<Message>,
    ledger: &mut run_ledger::RunLedger,
) -> Result<bool, String> {
    let records = pool.unreported();
    if records.is_empty() {
        return Ok(false);
    }
    let Some(placeholder) = messages.pop() else {
        return Ok(false);
    };
    let call_id = crate::controller::unique_id();
    let mut announcement = assistant_placeholder();
    announcement.metadata.run_id = placeholder.metadata.run_id.clone();
    announcement.complete = true;
    let call = ToolCall {
        id: call_id.clone(),
        name: "wait_agents".into(),
        arguments: json!({"ids":records.iter().map(|record| &record.id).collect::<Vec<_>>()})
            .to_string(),
    };
    let result = subagents::result_json(&records);
    ledger.record(&call, &result)?;
    announcement.tool_calls.push(call);
    messages.push(announcement);
    messages.push(Message {
        id: crate::controller::unique_id(),
        role: "tool".into(),
        text: result.to_string(),
        thinking: String::new(),
        complete: true,
        tool_calls: Vec::new(),
        tool_call_id: Some(call_id),
        metadata: crate::chat::MessageMetadata {
            timestamp: crate::controller::now_millis(),
            run_id: placeholder.metadata.run_id.clone(),
            tool_name: Some("wait_agents".into()),
            ..Default::default()
        },
    });
    messages.push(placeholder);
    pool.mark_reported(&records);
    Ok(true)
}

fn close_pending_tools(messages: &mut Vec<Message>) {
    reconcile_history(messages);
    if messages
        .last()
        .is_some_and(|message| message.role != "assistant" || !message.tool_calls.is_empty())
    {
        let run_id = messages
            .iter()
            .rev()
            .find_map(|message| message.metadata.run_id.clone());
        let mut ending = assistant_placeholder();
        ending.metadata.run_id = run_id;
        ending.metadata.trace_note = Some("本地运行结束记录".into());
        ending.text = "本轮已结束，已接收的内容和工具结果保留在记录中。".into();
        messages.push(ending);
    }
    if let Some(message) = messages
        .last_mut()
        .filter(|message| message.role == "assistant")
    {
        message.complete = false;
    }
}

/// Repair an interrupted persisted batch before adding a new user turn. A missing
/// result is an unknown effect, so the model must inspect state instead of retrying.
pub fn reconcile_history(messages: &mut Vec<Message>) {
    let mut repaired = Vec::with_capacity(messages.len());
    let mut pending = Vec::<(String, Option<String>, String)>::new();
    let close = |pending: &mut Vec<(String, Option<String>, String)>,
                 repaired: &mut Vec<Message>| {
        for (id, run_id, tool_name) in pending.drain(..) {
            repaired.push(Message {id:crate::controller::unique_id(),role:"tool".into(),text:json!({"ok":false,"interrupted":true,"effect_unknown":true,"error":"上一轮中断，没有已确认的持久化执行结果；操作可能已发生，请先核对，不要自动重试"}).to_string(),thinking:String::new(),complete:true,tool_calls:Vec::new(),tool_call_id:Some(id),metadata:crate::chat::MessageMetadata {run_id,tool_name:Some(tool_name),timestamp:crate::controller::now_millis(),..Default::default()}});
        }
    };
    for mut message in messages.drain(..) {
        if message.role != "tool" {
            close(&mut pending, &mut repaired);
        }
        if let Some(id) = &message.tool_call_id {
            if let Some((_, run, name)) = pending.iter().find(|(pending, _, _)| pending == id) {
                if message.metadata.run_id.is_none() {
                    message.metadata.run_id = run.clone();
                }
                if message.metadata.tool_name.is_none() {
                    message.metadata.tool_name = Some(name.clone());
                }
            }
            pending.retain(|(pending, _, _)| pending != id);
        }
        pending.extend(message.tool_calls.iter().map(|call| {
            (
                call.id.clone(),
                message.metadata.run_id.clone(),
                call.name.clone(),
            )
        }));
        if message.role == "assistant" && !message.complete {
            if let Some(envelope) = message
                .metadata
                .extra
                .get_mut("rustQueuedLocalCommandV1")
                .and_then(serde_json::Value::as_object_mut)
                .filter(|envelope| {
                    envelope.get("state").and_then(serde_json::Value::as_str) == Some("accepted")
                })
            {
                message.text =
                    "本地目标指令在受理后中断，效果可能已经发生，请核对当前目标状态；未自动重试。"
                        .into();
                message.complete = true;
                envelope.insert(
                    "state".into(),
                    serde_json::Value::String("interrupted".into()),
                );
            }
        }
        repaired.push(message);
    }
    close(&mut pending, &mut repaired);
    *messages = repaired;
}
