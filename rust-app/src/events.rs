//! A bounded, nonblocking UI mailbox. Durable work must not wait for a paused renderer.
use crate::state::Event;
use std::{
    collections::VecDeque,
    sync::{
        Arc, Mutex,
        mpsc::{SendError, TryRecvError},
    },
};

const MAX_EVENTS: usize = 512;
const MAX_BYTES: usize = 64 * 1024 * 1024;
const TERMINAL_BYTES: usize = 256 * 1024;

struct Entry {
    event: Event,
    bytes: usize,
}
struct Shared {
    queue: VecDeque<Entry>,
    bytes: usize,
    senders: usize,
    receiver_alive: bool,
    failed: bool,
    error_delivered: bool,
}

pub struct Sender(Arc<Mutex<Shared>>);
pub struct Receiver(Arc<Mutex<Shared>>);

pub fn channel() -> (Sender, Receiver) {
    let shared = Arc::new(Mutex::new(Shared {
        queue: VecDeque::new(),
        bytes: 0,
        senders: 1,
        receiver_alive: true,
        failed: false,
        error_delivered: false,
    }));
    (Sender(shared.clone()), Receiver(shared))
}

impl Clone for Sender {
    fn clone(&self) -> Self {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).senders += 1;
        Self(self.0.clone())
    }
}

impl Drop for Sender {
    fn drop(&mut self) {
        let mut shared = self.0.lock().unwrap_or_else(|e| e.into_inner());
        shared.senders = shared.senders.saturating_sub(1);
    }
}

impl Sender {
    pub fn is_alive(&self) -> bool {
        let shared = self.0.lock().unwrap_or_else(|e| e.into_inner());
        shared.receiver_alive && !shared.failed
    }

    pub fn send(&self, mut event: Event) -> Result<(), SendError<Event>> {
        let mut shared = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if !shared.receiver_alive || shared.failed {
            return Err(SendError(event));
        }
        if let Event::TerminalFrame(next) = &event {
            if shared.queue.iter().any(|entry| matches!(&entry.event,
                Event::TerminalFrame(old) if old.session_id > next.session_id
                    || (old.session_id == next.session_id
                        && (old.geometry.version > next.geometry.version || old.revision > next.revision)))) {
                return Ok(());
            }
        }
        if let Event::TerminalState { revision, .. } = &event {
            if shared.queue.iter().any(|entry| {
                matches!(&entry.event,
                Event::TerminalState { revision: old, .. } if old > revision)
            }) {
                return Ok(());
            }
        }
        // A full transcript supersedes its pending fragments and placeholders. Remove
        // them before appending the snapshot, preserving its order relative to results.
        shared.queue.retain(|old| !superseded(&old.event, &event));
        let key = identity(&event);
        if let Some(key) = key
            && let Some(index) = shared
                .queue
                .iter()
                .position(|old| identity(&old.event).as_ref() == Some(&key))
        {
            let old = shared.queue.remove(index).expect("queued event");
            match (&mut event, old.event) {
                (
                    Event::TextDelta { text, .. },
                    Event::TextDelta {
                        text: mut prefix, ..
                    },
                )
                | (
                    Event::ThinkingDelta { text, .. },
                    Event::ThinkingDelta {
                        text: mut prefix, ..
                    },
                )
                | (
                    Event::ChildDelta { text, .. },
                    Event::ChildDelta {
                        text: mut prefix, ..
                    },
                ) => {
                    if prefix.len().saturating_add(text.len()) > MAX_BYTES {
                        shared.failed = true;
                        return Err(SendError(event));
                    }
                    prefix.push_str(text);
                    *text = prefix;
                }
                (Event::TerminalOutput(text), Event::TerminalOutput(mut prefix)) => {
                    prefix.push_str(text);
                    if prefix.len() > TERMINAL_BYTES {
                        let mut cut = prefix.len() - TERMINAL_BYTES;
                        while !prefix.is_char_boundary(cut) {
                            cut += 1;
                        }
                        *text = format!("\n[终端显示缓冲已保留最近输出]\n{}", &prefix[cut..]);
                    } else {
                        *text = prefix;
                    }
                }
                _ => {}
            }
        }
        shared.bytes = shared.queue.iter().map(|entry| entry.bytes).sum();
        let bytes = weight(&event);
        if shared.queue.len() >= MAX_EVENTS || shared.bytes.saturating_add(bytes) > MAX_BYTES {
            // Retire this observer rather than silently dropping updates. Runtime
            // persistence and Worker replies survive a display mailbox failure.
            shared.failed = true;
            return Err(SendError(event));
        }
        shared.bytes += bytes;
        shared.queue.push_back(Entry { event, bytes });
        Ok(())
    }
}

impl Receiver {
    pub fn try_recv(&self) -> Result<Event, TryRecvError> {
        let mut shared = match self.0.try_lock() {
            Ok(shared) => shared,
            Err(std::sync::TryLockError::WouldBlock) => return Err(TryRecvError::Empty),
            Err(std::sync::TryLockError::Poisoned(error)) => error.into_inner(),
        };
        if let Some(entry) = shared.queue.pop_front() {
            shared.bytes = shared.bytes.saturating_sub(entry.bytes);
            return Ok(entry.event);
        }
        if shared.failed {
            if !shared.error_delivered {
                shared.error_delivered = true;
                return Ok(Event::Error(
                    "界面订阅超过缓冲预算，需要重新同步；后台运行不受此订阅影响".into(),
                ));
            }
            return Err(TryRecvError::Disconnected);
        }
        if shared.senders == 0 {
            Err(TryRecvError::Disconnected)
        } else {
            Err(TryRecvError::Empty)
        }
    }
}

impl Drop for Receiver {
    fn drop(&mut self) {
        let mut shared = self.0.lock().unwrap_or_else(|e| e.into_inner());
        shared.receiver_alive = false;
        shared.queue.clear();
        shared.bytes = 0;
    }
}

fn superseded(old: &Event, new: &Event) -> bool {
    match new {
        Event::MessagesUpdated {
            conversation_id: id,
            ..
        }
        | Event::Conversation { id, .. } => match old {
            Event::MessagesUpdated {
                conversation_id, ..
            }
            | Event::MessageAdded {
                conversation_id, ..
            }
            | Event::TextDelta {
                conversation_id, ..
            }
            | Event::ThinkingDelta {
                conversation_id, ..
            } => conversation_id == id,
            Event::Conversation { id: previous, .. } => {
                previous == id && matches!(new, Event::Conversation { .. })
            }
            _ => false,
        },
        Event::ChildSaved {
            conversation_id,
            record,
        } => match old {
            Event::ChildDelta {
                conversation_id: previous,
                child_id,
                ..
            } => previous == conversation_id && child_id == &record.id,
            _ => false,
        },
        Event::Children {
            conversation_id,
            records,
        } => match old {
            Event::ChildDelta {
                conversation_id: previous,
                child_id,
                ..
            } => previous == conversation_id && records.iter().any(|record| &record.id == child_id),
            Event::ChildSaved {
                conversation_id: previous,
                record,
            } => previous == conversation_id && records.iter().any(|next| next.id == record.id),
            _ => false,
        },
        Event::ApprovalCleared {
            run_id,
            approval_id,
        } => matches!(old,
            Event::ApprovalRequested(request) if &request.run_id == run_id && &request.id == approval_id),
        _ => false,
    }
}

fn identity(event: &Event) -> Option<String> {
    Some(match event {
        Event::Conversations(_) => "conversations".into(),
        Event::Usage(_) => "usage".into(),
        Event::Memories(_) => "memories".into(),
        Event::Skills(_) => "skills".into(),
        Event::Schedules(_) => "schedules".into(),
        Event::Jobs(_) => "jobs".into(),
        Event::JobOutput { request_id, .. } => format!("job_output:{request_id}"),
        Event::ChildSummaries {
            conversation_id, ..
        } => format!("child_summaries:{conversation_id}"),
        Event::ChildSummarySaved {
            conversation_id,
            record,
        } => format!("child_summary:{conversation_id}:{}", record.id()),
        Event::ConversationOpened { id, .. } => format!("conversation_opened:{id}"),
        Event::HistoryOpened { source, .. } => format!("history_opened:{source:?}"),
        Event::HistoryInvalidated { source, .. } => format!("history_invalidated:{source:?}"),
        Event::HistoryWindow(page) => format!("history_window:{}", page.request_id),
        Event::HistoryTurns(page) => format!("history_turns:{}", page.request_id),
        Event::HistoryBody(page) => format!("history_body:{}", page.request_id),
        Event::HistoryFooter { request_id, .. } => format!("history_footer:{request_id}"),
        Event::HistoryImage(page) => format!("history_image:{}", page.request_id),
        Event::HistoryRequestFailed { request_id, .. } => format!("history_failed:{request_id}"),
        Event::ScheduleStatus(_) => "schedule_status".into(),
        Event::Todos {
            conversation_id,
            child_id,
            ..
        } => format!("todos:{conversation_id:?}:{child_id:?}"),
        Event::Plugins(_) => "plugins".into(),
        Event::McpConfigs(_) => "mcp_configs".into(),
        Event::McpSnapshot(_) => "mcp_snapshot".into(),
        Event::ToolCatalog(_) => "tool_catalog".into(),
        Event::ConversationPreferences(_) => "conversation_preferences".into(),
        Event::Goal(_) => "goal".into(),
        Event::Capabilities(_) => "capabilities".into(),
        Event::RunSteeringMode { key, .. } => {
            format!("steering_mode:{}:{}", key.conversation_id, key.run_id)
        }
        Event::ContextReading { key, .. } => {
            format!("context_reading:{}:{}", key.conversation_id, key.run_id)
        }
        Event::ConversationContextReading { source, .. } => {
            format!("conversation_context_reading:{source:?}")
        }
        Event::TerminalState { .. } => "terminal_state".into(),
        Event::Terminal(crate::controller::terminal_runtime::Update::Frame { key, .. }) => {
            format!("terminal_frame:{}:{}", key.process_token, key.session_id)
        }
        Event::Terminal(crate::controller::terminal_runtime::Update::Snapshot { .. }) => {
            "terminal_sessions".into()
        }
        Event::TerminalFrame(_) => "terminal_frame".into(),
        Event::TerminalOutput(_) => "terminal_output".into(),
        Event::QueuedTurns {
            conversation_id, ..
        } => format!("queue:{conversation_id}"),
        Event::Children {
            conversation_id, ..
        } => format!("children:{conversation_id}"),
        Event::ChildSaved {
            conversation_id,
            record,
        } => format!("child:{conversation_id}:{}", record.id),
        Event::TextDelta {
            conversation_id,
            message_id,
            ..
        } => format!("text:{conversation_id}:{message_id}"),
        Event::ThinkingDelta {
            conversation_id,
            message_id,
            ..
        } => format!("thinking:{conversation_id}:{message_id}"),
        Event::ChildDelta {
            conversation_id,
            child_id,
            message_id,
            thinking,
            ..
        } => format!("child_delta:{conversation_id}:{child_id}:{message_id}:{thinking}"),
        _ => return None,
    })
}

fn messages_weight(messages: &[crate::state::Message]) -> usize {
    serialized_size(&messages)
}

fn weight(event: &Event) -> usize {
    1024 + match event {
        Event::Conversation { messages, .. } | Event::MessagesUpdated { messages, .. } => {
            messages_weight(messages)
        }
        Event::MessageAdded { message, .. } => messages_weight(std::slice::from_ref(message)),
        Event::TextDelta { text, .. }
        | Event::ThinkingDelta { text, .. }
        | Event::ChildDelta { text, .. }
        | Event::TerminalOutput(text)
        | Event::Error(text)
        | Event::Info(text) => text.len(),
        Event::SteeringWithdrawn { scope, ids } => {
            scope.run_id.len()
                + scope.conversation_id.len()
                + ids.iter().map(String::len).sum::<usize>()
        }
        Event::RunStarted { key } | Event::RunSteeringMode { key, .. } => {
            key.run_id.len() + key.conversation_id.len()
        }
        Event::ContextReading { key, reading } => {
            key.run_id.len()
                + key.conversation_id.len()
                + reading.model.len()
                + std::mem::size_of::<crate::session_tools::ContextReading>()
        }
        Event::ConversationContextReading {
            source,
            stamp,
            reading,
        } => {
            source.estimated_bytes()
                + text_size(&stamp.generation)
                + reading.as_ref().map_or(0, |reading| {
                    reading.model.len()
                        + std::mem::size_of::<crate::session_tools::ContextReading>()
                })
        }
        Event::RunEnded { key, error } => {
            key.run_id.len() + key.conversation_id.len() + error.as_ref().map_or(0, String::len)
        }
        Event::RunStartRejected {
            conversation_id,
            error,
        } => conversation_id.as_ref().map_or(0, String::len) + error.len(),
        Event::ChildSaved { record, .. } => serialized_size(record),
        Event::Children { records, .. } => records.iter().map(serialized_size).sum(),
        Event::Usage(records) => records
            .iter()
            .map(|r| 256 + r.model.len() + r.error.as_ref().map_or(0, String::len))
            .sum(),
        Event::Memories(records) => records.iter().map(|r| 128 + r.text.len()).sum(),
        Event::MemorySaved(record) => 128 + record.text.len(),
        Event::Skills(records) => records
            .iter()
            .map(|r| 512 + r.content.len() + r.name.len() + r.description.len())
            .sum(),
        Event::Schedules(records) => serialized_size(records),
        Event::Jobs(records) => serialized_size(records),
        Event::Composer(event) => event.output_weight().unwrap_or(MAX_BYTES),
        Event::Automation(event) => event.bytes(),
        Event::Terminal(update) => match update {
            crate::controller::terminal_runtime::Update::Frame { frame, .. } => {
                frame.estimated_bytes() + 1024
            }
            crate::controller::terminal_runtime::Update::Snapshot { sessions, .. } => {
                serialized_size(sessions) + 1024
            }
            crate::controller::terminal_runtime::Update::Raw { result, .. } => result
                .as_ref()
                .map_or(4096, |window| window.bytes.len() + 4096),
            crate::controller::terminal_runtime::Update::Finished { result, .. } => result
                .as_ref()
                .err()
                .map_or(1024, |error| error.len() + 1024),
            crate::controller::terminal_runtime::Update::ResizeRejected { error, .. } => {
                error.len() + 1024
            }
        },
        Event::JobOutput { result, .. } => result.as_ref().map_or(8192, |v| v.text.len() + 1024),
        Event::ChildSummaries { records, .. } => records.len() * 24 * 1024,
        Event::ChildSummarySaved { .. } => 24 * 1024,
        Event::ConversationOpened {
            id,
            stamp,
            position,
        } => 256 + text_size(id) + text_size(&stamp.generation) + serialized_size(position),
        Event::HistoryOpened {
            source,
            stamp,
            position,
        } => {
            256 + source.estimated_bytes()
                + text_size(&stamp.generation)
                + serialized_size(position)
        }
        Event::HistoryInvalidated { source, stamp } => {
            256 + source.estimated_bytes() + text_size(&stamp.generation)
        }
        Event::HistoryWindow(page) => page.estimated_bytes(),
        Event::HistoryTurns(page) => page.estimated_bytes(),
        Event::HistoryBody(page) => page.estimated_bytes(),
        Event::HistoryFooter { page, .. } => page.estimated_bytes() + 256,
        Event::HistoryImage(page) => page.rgba.len() + page.image.estimated_bytes() + 256,
        Event::HistoryCopied { body, text } => body.estimated_bytes() + text.len(),
        Event::ScheduleStatus(status) => serialized_size(status),
        Event::Todos { board, .. } => serialized_size(board),
        Event::LegacyPreview(result) => result.as_ref().map_or_else(
            |error| error.len(),
            |preview| 512 + preview.warnings.iter().map(String::len).sum::<usize>(),
        ),
        Event::LegacyImported(result) => result.as_ref().map_or_else(
            |error| error.len(),
            |report| {
                report.transaction_id.len()
                    + report.source_fingerprint.len()
                    + report.warnings.iter().map(String::len).sum::<usize>()
                    + report
                        .retained_features
                        .iter()
                        .map(String::len)
                        .sum::<usize>()
                    + 256
            },
        ),
        Event::FilePreview { text, .. } => text.len(),
        Event::SkillSaved(record) => serialized_size(record),
        Event::TerminalFrame(frame) => frame.estimated_bytes(),
        Event::ModelsFetched { result, .. } => result.as_ref().map_or(1024, serialized_size),
        Event::Goal(goal) => serialized_size(goal),
        Event::ApprovalRequested(request) => request.call.arguments.len() + 1024,
        Event::QueuedTurns { turns, .. } => serialized_size(turns),
        Event::Loaded {
            settings,
            conversations,
            memories,
            ..
        } => settings_weight(settings) + serialized_size(conversations) + serialized_size(memories),
        Event::FileTransfer(event) => match event {
            crate::file_transfer::Event::WaitingForTerminal { reason, .. } => reason.len() + 1024,
            crate::file_transfer::Event::Finished(event) => {
                event
                    .result
                    .as_ref()
                    .map_or_else(|message| message.len(), |message| message.len())
                    + event.snapshot.as_ref().map_or(0, serialized_size)
                    + 8192
            }
        },
        Event::SettingsSaved(settings) => settings_weight(settings),
        Event::UsageStatistics(update) => update.bytes().saturating_add(512),
        Event::Profile(update) => match update {
            crate::profile::Update::Read { snapshot, .. }
            | crate::profile::Update::Saved { snapshot, .. }
            | crate::profile::Update::Changed { snapshot } => {
                snapshot.text.len().saturating_add(65536)
            }
            crate::profile::Update::Failed { error, .. } => error.len() + 1024,
        },
        Event::SettingsSnapshot(snapshot) => {
            snapshot.stamp.generation.len() + settings_weight(&snapshot.settings)
        }
        Event::SettingsMutationFinished {
            request_id,
            expected,
            result,
        } => {
            request_id.len()
                + expected.generation.len()
                + match result {
                    Ok(snapshot) => {
                        snapshot.stamp.generation.len() + settings_weight(&snapshot.settings)
                    }
                    Err(error) => error.len(),
                }
        }
        Event::Performance(crate::performance::Update::Snapshot { snapshot, .. }) => snapshot
            .records
            .len()
            .saturating_mul(8192)
            .saturating_add(8192),
        Event::Performance(crate::performance::Update::Failed { error, .. }) => error.len() + 512,
        Event::LegacyArchive(update) => {
            use crate::{
                controller::legacy_archive::Update, legacy_process_browser::Update as Read,
            };
            match update {
                Update::Opened { result, .. } => result.as_ref().map_or(8192, |_| 1024),
                Update::Failed { error, .. } => error.len() + 512,
                Update::Read(Read::List { result, .. }) => {
                    result.as_ref().map_or(8192, serialized_size)
                }
                Update::Read(Read::Detail { result, .. }) => {
                    result.as_ref().map_or(8192, serialized_size)
                }
                Update::Read(Read::Log { result, .. }) => result
                    .as_ref()
                    .map_or(8192, |window| window.bytes.len() + 1024),
            }
        }
        Event::Plugins(entries) => entries
            .iter()
            .map(|entry| {
                1024 + serialized_size(&entry.manifest)
                    + entry.environment.values().map(String::len).sum::<usize>()
            })
            .sum(),
        Event::PluginPreview(result) => result.as_ref().map_or(1024, serialized_size),
        Event::Avatar(update) => update.bytes(),
        Event::ConversationInfo(update) => update.bytes(),
        Event::ConversationPreferences(snapshot) => serialized_size(snapshot),
        Event::Conversations(records) => serialized_size(records),
        Event::Files { entries, .. } => entries
            .iter()
            .map(|entry| 256 + entry.name.len() + entry.path.as_os_str().len())
            .sum(),
        Event::McpConfigs(configs) => configs
            .iter()
            .map(|entry| serialized_size(&entry.config) + 128)
            .sum(),
        Event::McpSnapshot(snapshot) => {
            snapshot
                .tools
                .iter()
                .map(|tool| {
                    256 + tool.registered_name.len()
                        + tool.description.len()
                        + serialized_size(&tool.input_schema)
                })
                .sum::<usize>()
                + snapshot
                    .servers
                    .iter()
                    .map(|server| {
                        512 + serialized_size(&server.config)
                            + server.error.as_ref().map_or(0, String::len)
                    })
                    .sum::<usize>()
                + snapshot
                    .test_results
                    .iter()
                    .map(|entry| match &entry.result {
                        Ok(report) => report.tools.iter().map(String::len).sum::<usize>() + 512,
                        Err(error) => error.len() + 128,
                    })
                    .sum::<usize>()
        }
        Event::ToolCatalog(tools) => tools
            .iter()
            .map(|tool| {
                128 + tool.name.len() + tool.description.len() + serialized_size(&tool.parameters)
            })
            .sum(),
        Event::ChildConfigPreview { result, .. } => result.as_ref().map_or(1024, serialized_size),
        _ => 16 * 1024,
    }
}
fn text_size(text: &str) -> usize {
    text.len().saturating_mul(6)
}

fn settings_weight(settings: &crate::state::Settings) -> usize {
    serialized_size(settings)
        + settings
            .request_headers
            .values()
            .map(String::len)
            .sum::<usize>()
        + settings.api_key.len()
        + settings.asr_key.len()
        + settings
            .providers
            .iter()
            .map(|provider| provider.api_key.len())
            .sum::<usize>()
}

fn serialized_size(value: &impl serde::Serialize) -> usize {
    struct Counter(usize);
    impl std::io::Write for Counter {
        fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
            self.0 = self.0.saturating_add(bytes.len());
            Ok(bytes.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
    let mut counter = Counter(0);
    if serde_json::to_writer(&mut counter, value).is_err() {
        MAX_BYTES
    } else {
        counter.0
    }
}
