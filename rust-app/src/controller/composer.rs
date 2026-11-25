//! Actor-owned platform operations; UI receives bounded references and tickets.
use super::*;
use crate::{
    composer::{self, ComposerCommand, ComposerEvent, SpeechPhase, Submission},
    platform::{attachments, permissions::OperationKey, speech_service, voice},
};
use std::{
    collections::{HashMap, VecDeque},
    sync::atomic::{AtomicBool, Ordering},
};
mod files;
pub(super) use files::PreparedInput;
pub(super) fn encode_image_blob(bytes: &[u8]) -> String {
    files::encode_base64(bytes)
}

pub(super) fn prepare_queued(store: &Store, turn: &QueuedTurn) -> Result<PreparedInput, String> {
    crate::composer::validate_refs(&turn.attachments)?;
    files::prepare(&store.workspace(), &turn.text, &turn.attachments, || false)
}

pub(crate) fn prepare_queue_input(
    workspace: &std::path::Path,
    turn: &QueuedTurn,
) -> Result<PreparedInput, String> {
    crate::composer::validate_refs(&turn.attachments)?;
    files::prepare(workspace, &turn.text, &turn.attachments, || false)
}

enum Kind {
    Attachment,
    Preview,
    Recording {
        revision: u64,
        connection: voice::AsrConnection,
    },
    Transcribing {
        revision: u64,
    },
    Submission,
}
struct Pending {
    operation: OperationKey,
    kind: Kind,
    cancel: Arc<AtomicBool>,
    generation: String,
    started: Instant,
}
struct AudioTicket {
    operation: OperationKey,
    revision: u64,
    connection: voice::AsrConnection,
    wav: voice::RecordedWav,
    expires: Instant,
    generation: String,
}
enum Ready {
    Submission {
        operation: OperationKey,
        submission: Submission,
        input: Result<PreparedInput, String>,
    },
    Preview {
        operation: OperationKey,
        attachment_id: String,
        pixels: Result<Arc<attachments::PixelFrame>, String>,
    },
}
struct Speech {
    permit: crate::voice_tools::AudioPermit,
    cancelled: Arc<AtomicBool>,
    speaker: Option<voice::SpeakerKey>,
    generation: String,
    terminal_phase: Option<SpeechPhase>,
    operation: OperationKey,
    text: Option<String>,
    lease: speech_service::Lease,
    started: Instant,
    speaking: bool,
}
pub(super) struct ComposerRuntime {
    pending: HashMap<String, Pending>,
    audio: HashMap<String, AudioTicket>,
    retired: VecDeque<String>,
    ready_tx: SyncSender<Ready>,
    ready_rx: Receiver<Ready>,
    speech: Option<Speech>,
    gate: crate::voice_tools::AudioGate,
    last_recording_status: Instant,
}
impl ComposerRuntime {
    pub(super) fn new(gate: crate::voice_tools::AudioGate) -> Self {
        let (ready_tx, ready_rx) = mpsc::sync_channel(4);
        Self {
            pending: HashMap::new(),
            audio: HashMap::new(),
            retired: VecDeque::new(),
            ready_tx,
            ready_rx,
            speech: None,
            gate,
            last_recording_status: Instant::now(),
        }
    }
}
fn shortened(mut value: String) -> String {
    let mut end = value.len().min(2048);
    while !value.is_char_boundary(end) {
        end -= 1;
    }
    value.truncate(end);
    value.replace('\0', "�")
}
pub(super) fn emit(publish: &Publisher, event: ComposerEvent) {
    if let Err(error) = event.output_weight() {
        let operation = match &event {
            ComposerEvent::Attachment { operation, .. }
            | ComposerEvent::AttachmentPreview { operation, .. }
            | ComposerEvent::RecordingFinished { operation, .. }
            | ComposerEvent::TranscriptionFinished { operation, .. }
            | ComposerEvent::Accepted { operation, .. }
            | ComposerEvent::CommandFinished { operation, .. }
            | ComposerEvent::Rejected { operation, .. }
            | ComposerEvent::Cancelled { operation }
            | ComposerEvent::Speech { operation, .. } => operation.clone(),
            ComposerEvent::RecordingStatus(status) => status.operation.clone(),
        };
        publish.emit(Event::Composer(ComposerEvent::Rejected {
            operation,
            error: shortened(error),
        }));
    } else {
        publish.emit(Event::Composer(event));
    }
}
impl ComposerRuntime {
    fn retire(&mut self, id: String) {
        self.retired.push_back(id);
        while self.retired.len() > 512 {
            self.retired.pop_front();
        }
    }
    fn admit(
        &mut self,
        operation: OperationKey,
        kind: Kind,
        store: &Store,
    ) -> Result<Arc<AtomicBool>, String> {
        operation.validate()?;
        if let Some(id) = &operation.conversation_id {
            store.conversation(id)?;
        }
        if self.pending.len() >= 32
            || self.pending.contains_key(&operation.id)
            || self.retired.contains(&operation.id)
            || self
                .speech
                .as_ref()
                .is_some_and(|speech| speech.operation.id == operation.id)
        {
            return Err("操作正在执行、已经终结或队列已满".into());
        }
        if matches!(&kind, Kind::Preview | Kind::Submission)
            && self
                .pending
                .values()
                .filter(|pending| matches!(pending.kind, Kind::Preview | Kind::Submission))
                .count()
                >= 2
        {
            return Err("附件核验和预览工作线程已满".into());
        }
        let cancel = Arc::new(AtomicBool::new(false));
        self.pending.insert(
            operation.id.clone(),
            Pending {
                operation,
                kind,
                cancel: cancel.clone(),
                generation: store.history_generation_token(),
                started: Instant::now(),
            },
        );
        Ok(cancel)
    }
    fn take(&mut self, operation: &OperationKey, store: &Store) -> Option<Pending> {
        if !self
            .pending
            .get(&operation.id)
            .is_some_and(|pending| pending.operation == *operation)
        {
            return None;
        }
        let pending = self.pending.remove(&operation.id)?;
        self.retire(operation.id.clone());
        if pending.generation != store.history_generation_token()
            || operation
                .conversation_id
                .as_ref()
                .is_some_and(|id| store.conversation(id).is_err())
        {
            pending.cancel.store(true, Ordering::Release);
        }
        Some(pending)
    }
    pub(super) fn dispatch(
        &mut self,
        command: ComposerCommand,
        store: &Store,
        publish: &Publisher,
        runs: &Runs,
        history: &mut history::HistoryRuntime,
    ) {
        let operation = command.operation().clone();
        let already_pending = self.pending.contains_key(&operation.id);
        let result = (|| -> Result<(), String> {
            operation.validate()?;
            match command {
                ComposerCommand::PickAttachment { operation, kind } => {
                    if kind != attachments::AttachmentKind::File
                        && !store.settings.behavior.image_input
                    {
                        return Err("图片输入已禁用，请先在设置中启用".into());
                    }
                    self.admit(operation.clone(), Kind::Attachment, store)?;
                    attachments::pick(operation, kind, store.workspace())?;
                }
                ComposerCommand::PreviewAttachment {
                    operation,
                    attachment,
                } => {
                    let cancel = self.admit(operation.clone(), Kind::Preview, store)?;
                    let tx = self.ready_tx.clone();
                    let workspace = store.workspace();
                    let op = operation.clone();
                    thread::Builder::new()
                        .name("rust-composer-preview".into())
                        .spawn(move || {
                            let pixels =
                                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                                    if cancel.load(Ordering::Acquire) {
                                        Err("预览已取消".into())
                                    } else {
                                        files::preview(&workspace, &attachment)
                                    }
                                }))
                                .unwrap_or_else(|_| Err("附件预览异常".into()));
                            let _ = tx.send(Ready::Preview {
                                operation: op,
                                attachment_id: attachment.id,
                                pixels,
                            });
                        })
                        .map_err(|_| "附件预览线程无法启动")?;
                }
                ComposerCommand::StartRecording {
                    operation,
                    origin_text_revision,
                } => {
                    if !store.settings.behavior.voice_input {
                        return Err("语音输入已禁用，请先在设置中启用".into());
                    }
                    if origin_text_revision == 0 {
                        return Err("录音草稿代数无效".into());
                    }
                    let connection = store.asr_connection()?;
                    self.admit(
                        operation.clone(),
                        Kind::Recording {
                            revision: origin_text_revision,
                            connection,
                        },
                        store,
                    )?;
                    voice::record(operation)?;
                }
                ComposerCommand::FinishRecording(operation) => {
                    let pending = self
                        .pending
                        .get(&operation.id)
                        .filter(|p| {
                            p.operation == operation && matches!(p.kind, Kind::Recording { .. })
                        })
                        .ok_or("录音操作已结束或身份已变化")?;
                    voice::finish_recording(&pending.operation.id);
                }
                ComposerCommand::TranscribeRecording {
                    operation,
                    audio,
                    origin_text_revision,
                } => {
                    let ticket = self
                        .audio
                        .remove(&audio.token)
                        .ok_or("录音票据已消费、过期或不存在")?;
                    if ticket.expires <= Instant::now()
                        || ticket.operation.conversation_id != operation.conversation_id
                        || ticket.operation.generation != operation.generation
                        || ticket.revision != origin_text_revision
                        || ticket.wav.duration_ms != audio.duration_ms
                        || ticket.wav.samples != audio.samples
                    {
                        return Err("录音票据与原草稿不匹配".into());
                    }
                    self.admit(
                        operation.clone(),
                        Kind::Transcribing {
                            revision: origin_text_revision,
                        },
                        store,
                    )?;
                    voice::transcribe(operation, ticket.connection, ticket.wav)?;
                }
                ComposerCommand::Submit(submission) => {
                    submission.validate()?;
                    if !store.settings.behavior.image_input
                        && submission
                            .attachments
                            .iter()
                            .any(|attachment| attachment.image)
                    {
                        return Err("图片输入已禁用，请启用或移除草稿中的图片".into());
                    }
                    let cancel = self.admit(operation.clone(), Kind::Submission, store)?;
                    let tx = self.ready_tx.clone();
                    let workspace = store.workspace();
                    let op = operation.clone();
                    thread::Builder::new()
                        .name("rust-composer-submit".into())
                        .spawn(move || {
                            let input =
                                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                                    files::prepare(
                                        &workspace,
                                        &submission.text,
                                        &submission.attachments,
                                        || cancel.load(Ordering::Acquire),
                                    )
                                }))
                                .unwrap_or_else(|_| Err("附件核验异常，草稿保留".into()));
                            let _ = tx.send(Ready::Submission {
                                operation: op,
                                submission,
                                input,
                            });
                        })
                        .map_err(|_| "附件核验线程无法启动")?;
                }
                ComposerCommand::CancelPlatformOperation(operation) => {
                    let pending = self
                        .pending
                        .get(&operation.id)
                        .filter(|p| p.operation == operation)
                        .ok_or("操作已终结或归属已变化")?;
                    pending.cancel.store(true, Ordering::Release);
                    attachments::cancel(&operation.id);
                    voice::cancel(&operation.id);
                }
                ComposerCommand::SpeakReply { operation, body } => {
                    if !store.settings.speak_replies {
                        return Err("回答朗读尚未启用".into());
                    }
                    if self.speech.is_some() {
                        return Err("已有回答正在朗读或释放".into());
                    }
                    if operation.conversation_id.as_deref() != Some(body.source.conversation_id()) {
                        return Err("朗读正文不属于当前操作会话".into());
                    }
                    let text = history.speech_text(store, runs, &body)?;
                    if text.is_empty() || text.len() > 256 * 1024 {
                        return Err("朗读正文为空或超过256 KiB".into());
                    }
                    self.start_speech(operation, text, store, publish)?;
                }
                ComposerCommand::TestReplyAudio(operation) => {
                    if operation.conversation_id.is_some() {
                        return Err("设置试听不能冒用会话归属".into());
                    }
                    self.start_speech(
                        operation,
                        "你好，这是JAgent的语音试听。".into(),
                        store,
                        publish,
                    )?;
                }
                ComposerCommand::StopReplyAudio(operation) => {
                    let speech = self.speech.as_ref().ok_or("朗读已终结或未启动")?;
                    if speech.operation != operation {
                        return Err("朗读操作归属已变化".into());
                    }
                    self.stop_speech(publish, SpeechPhase::Finished);
                }
            }
            Ok(())
        })();
        if let Err(error) = result {
            if !already_pending && let Some(pending) = self.pending.remove(&operation.id) {
                pending.cancel.store(true, Ordering::Release);
                self.retire(operation.id.clone());
            }
            emit(
                publish,
                ComposerEvent::Rejected {
                    operation,
                    error: shortened(error),
                },
            );
        }
    }
    fn start_speech(
        &mut self,
        operation: OperationKey,
        text: String,
        store: &Store,
        publish: &Publisher,
    ) -> Result<(), String> {
        if !store.settings.speak_replies || self.speech.is_some() {
            return Err("用户未启用朗读或音频尚未释放".into());
        }
        if self.retired.contains(&operation.id) {
            return Err("朗读操作已终结，不重放".into());
        }
        let permit = self
            .gate
            .acquire(crate::voice_tools::AudioOwner::Composer(operation.clone()))?;
        let lease = speech_service::request(operation.clone(), true)?;
        self.speech = Some(Speech {
            operation: operation.clone(),
            text: Some(text),
            lease,
            started: Instant::now(),
            speaking: false,
            permit,
            cancelled: Arc::new(AtomicBool::new(false)),
            speaker: None,
            generation: store.history_generation_token(),
            terminal_phase: None,
        });
        emit(
            publish,
            ComposerEvent::Speech {
                operation,
                phase: SpeechPhase::RequestingService,
            },
        );
        Ok(())
    }
    fn stop_speech(&mut self, publish: &Publisher, phase: SpeechPhase) {
        let Some(speech) = self.speech.as_mut() else {
            return;
        };
        if speech.terminal_phase.is_some() {
            return;
        }
        if let Some(speaker) = &speech.speaker {
            speech.cancelled.store(true, Ordering::Release);
            voice::cancel_speaker(speaker);
            speech.terminal_phase = Some(phase);
            emit(
                publish,
                ComposerEvent::Speech {
                    operation: speech.operation.clone(),
                    phase: SpeechPhase::WaitingForCleanup,
                },
            );
        } else {
            let speech = self.speech.take().unwrap();
            self.retire(speech.operation.id.clone());
            emit(
                publish,
                ComposerEvent::Speech {
                    operation: speech.operation,
                    phase,
                },
            );
        }
    }
    fn scoped_speech_result(
        &mut self,
        operation: OperationKey,
        speaker: voice::SpeakerKey,
        result: Result<(), String>,
        cleanup_confirmed: bool,
        publish: &Publisher,
    ) {
        if !self.speech.as_ref().is_some_and(|speech| {
            speech.operation == operation && speech.speaker.as_ref() == Some(&speaker)
        }) {
            return;
        }
        let mut speech = self.speech.take().unwrap();
        speech.permit.cleanup_unknown = !cleanup_confirmed;
        let phase = if !cleanup_confirmed {
            SpeechPhase::Failed("朗读已终结但系统清理未确认；音频仍保留占用".into())
        } else if let Some(phase) = speech.terminal_phase.take() {
            phase
        } else {
            result.map_or_else(
                |error| SpeechPhase::Failed(shortened(error)),
                |()| SpeechPhase::Finished,
            )
        };
        self.retire(operation.id.clone());
        emit(publish, ComposerEvent::Speech { operation, phase });
    }

    pub(super) fn voice_result(
        &mut self,
        result: voice::VoiceResult,
        store: &mut Store,
        publish: &Publisher,
    ) {
        match result {
            voice::VoiceResult::RecordingFinished { operation, result } => {
                let Some(pending) = self.take(&operation, store) else {
                    return;
                };
                let Kind::Recording {
                    revision,
                    connection,
                } = pending.kind
                else {
                    return;
                };
                let result = if pending.cancel.load(Ordering::Acquire) {
                    Err("录音已取消".into())
                } else {
                    result
                };
                let result = result
                    .and_then(|wav| {
                        if self.audio.len() >= 8 {
                            return Err("录音暂存票据已满".into());
                        }
                        let token = unique_id();
                        let result = composer::RecordedAudio {
                            token: token.clone(),
                            duration_ms: wav.duration_ms,
                            samples: wav.samples,
                        };
                        self.audio.insert(
                            token,
                            AudioTicket {
                                operation: operation.clone(),
                                revision,
                                connection,
                                wav,
                                expires: Instant::now() + Duration::from_secs(300),
                                generation: store.history_generation_token(),
                            },
                        );
                        Ok(result)
                    })
                    .map_err(shortened);
                emit(
                    publish,
                    ComposerEvent::RecordingFinished {
                        operation,
                        origin_text_revision: revision,
                        result,
                    },
                );
            }
            voice::VoiceResult::TranscriptionFinished { operation, result } => {
                let Some(pending) = self.take(&operation, store) else {
                    return;
                };
                let Kind::Transcribing { revision } = pending.kind else {
                    return;
                };
                let result = if pending.cancel.load(Ordering::Acquire) {
                    Err("转写已取消".into())
                } else {
                    result
                }
                .map_err(shortened);
                emit(
                    publish,
                    ComposerEvent::TranscriptionFinished {
                        operation,
                        origin_text_revision: revision,
                        result,
                    },
                );
            }
            voice::VoiceResult::ScopedSpeechFinished {
                operation,
                speaker,
                result,
                cleanup_confirmed,
            } => {
                self.scoped_speech_result(operation, speaker, result, cleanup_confirmed, publish);
            }
            voice::VoiceResult::SpeechFinished { .. }
            | voice::VoiceResult::SpeechStopFinished { .. } => {}
        }
    }
    pub(super) fn service_event(
        &mut self,
        event: speech_service::SpeechServiceEvent,
        store: &Store,
        publish: &Publisher,
    ) {
        match event {
            speech_service::SpeechServiceEvent::Ready { operation, guard } => {
                if let Some(speech) = self
                    .speech
                    .as_mut()
                    .filter(|speech| speech.operation == operation && !speech.speaking)
                {
                    if !Arc::ptr_eq(&guard, &speech.lease.guard())
                        || !speech.permit.current()
                        || speech.cancelled.load(Ordering::Acquire)
                        || !guard.load(Ordering::Acquire)
                        || !speech.lease.confirmed()
                        || !store.settings.speak_replies
                        || speech.generation != store.history_generation_token()
                        || speech
                            .operation
                            .conversation_id
                            .as_ref()
                            .is_some_and(|id| store.conversation(id).is_err())
                    {
                        self.stop_speech(publish, SpeechPhase::Failed("音频服务保护已失效".into()));
                        return;
                    }
                    emit(
                        publish,
                        ComposerEvent::Speech {
                            operation: operation.clone(),
                            phase: SpeechPhase::ServiceReady,
                        },
                    );
                    let result = voice::speak_scoped(
                        operation.clone(),
                        speech.text.take().unwrap_or_default(),
                        voice::SpeechPolicy {
                            opted_in: true,
                            background_guard: Some(guard),
                        },
                        speech.cancelled.clone(),
                        &speech.lease,
                    );
                    if let Err(error) = &result {
                        self.stop_speech(publish, SpeechPhase::Failed(shortened(error.clone())));
                    } else {
                        speech.speaker = Some(result.unwrap());
                        speech.permit.cleanup_unknown = true;
                        speech.speaking = true;
                        emit(
                            publish,
                            ComposerEvent::Speech {
                                operation,
                                phase: SpeechPhase::Speaking,
                            },
                        );
                    }
                }
            }
            speech_service::SpeechServiceEvent::Failed { operation, error } => {
                if operation.is_none()
                    || self
                        .speech
                        .as_ref()
                        .is_some_and(|speech| Some(&speech.operation) == operation.as_ref())
                {
                    self.stop_speech(publish, SpeechPhase::Failed(shortened(error)));
                }
            }
            speech_service::SpeechServiceEvent::Released { operation } => {
                if self
                    .speech
                    .as_ref()
                    .is_some_and(|speech| speech.operation == operation)
                {
                    self.stop_speech(publish, SpeechPhase::Failed("音频服务已释放".into()));
                }
            }
            speech_service::SpeechServiceEvent::NotificationRestricted => {}
        }
    }
    pub(super) fn tick(
        &mut self,
        store: &mut Store,
        runs: &mut Runs,
        publish: &Publisher,
        tx: &SyncSender<WorkerEvent>,
        mcp: Option<&McpRuntime>,
    ) {
        self.audio.retain(|_, ticket| {
            ticket.expires > Instant::now()
                && ticket.generation == store.history_generation_token()
                && ticket
                    .operation
                    .conversation_id
                    .as_ref()
                    .is_none_or(|id| store.conversation(id).is_ok())
        });
        let expired: Vec<_> = self
            .pending
            .values()
            .filter(|pending| {
                pending.started.elapsed() > Duration::from_secs(300)
                    || pending.generation != store.history_generation_token()
            })
            .map(|pending| pending.operation.clone())
            .collect();
        for operation in expired {
            if let Some(pending) = self.pending.remove(&operation.id) {
                pending.cancel.store(true, Ordering::Release);
                attachments::cancel(&operation.id);
                voice::cancel(&operation.id);
                self.retire(operation.id.clone());
                emit(publish, ComposerEvent::Cancelled { operation });
            }
        }
        if !store.settings.speak_replies && self.speech.is_some() {
            self.stop_speech(publish, SpeechPhase::Finished);
        }
        for result in attachments::drain_results(4) {
            let Some(pending) = self.take(&result.operation, store) else {
                continue;
            };
            let cancelled = result.cancelled || pending.cancel.load(Ordering::Acquire);
            let converted = result
                .result
                .and_then(|drafts| {
                    let mut batch = composer::AttachmentBatch {
                        attachments: Vec::new(),
                        previews: Vec::new(),
                    };
                    let mut pixels = 0usize;
                    for draft in drafts {
                        let reference = composer::AttachmentRef::from_draft(&draft)?;
                        if let Some(preview) = draft.preview {
                            if batch.previews.len() < 8
                                && pixels.saturating_add(preview.rgba.len())
                                    <= composer::MAX_PREVIEW_BATCH_BYTES
                            {
                                pixels += preview.rgba.len();
                                batch.previews.push(composer::AttachmentPreview {
                                    attachment_id: reference.id.clone(),
                                    pixels: preview,
                                });
                            }
                        }
                        batch.attachments.push(reference);
                    }
                    batch.validate()?;
                    Ok(Arc::new(batch))
                })
                .map_err(shortened);
            emit(
                publish,
                ComposerEvent::Attachment {
                    operation: result.operation,
                    result: if cancelled {
                        Err("附件操作已取消".into())
                    } else {
                        converted
                    },
                    cancelled,
                },
            );
        }
        if self.last_recording_status.elapsed() >= Duration::from_millis(100) {
            self.last_recording_status = Instant::now();
            if let Some(status) = voice::recording_status() {
                if self
                    .pending
                    .get(&status.operation.id)
                    .is_some_and(|p| p.operation == status.operation)
                {
                    emit(publish, ComposerEvent::RecordingStatus(status));
                }
            }
        }

        if self.speech.as_ref().is_some_and(|speech| {
            speech.generation != store.history_generation_token()
                || speech
                    .operation
                    .conversation_id
                    .as_ref()
                    .is_some_and(|id| store.conversation(id).is_err())
                || speech.started.elapsed() > Duration::from_secs(120)
                || (!speech.speaking && speech.started.elapsed() > Duration::from_secs(15))
                || (speech.speaking && !speech.lease.confirmed())
        }) {
            self.stop_speech(
                publish,
                SpeechPhase::Failed("音频服务确认超时或保护已失效".into()),
            );
        }
        for _ in 0..4 {
            let Ok(ready) = self.ready_rx.try_recv() else {
                break;
            };
            match ready {
                Ready::Preview {
                    operation,
                    attachment_id,
                    pixels,
                } => {
                    let Some(pending) = self.take(&operation, store) else {
                        continue;
                    };
                    if pending.cancel.load(Ordering::Acquire) {
                        emit(publish, ComposerEvent::Cancelled { operation });
                    } else {
                        emit(
                            publish,
                            ComposerEvent::AttachmentPreview {
                                operation,
                                attachment_id,
                                pixels: pixels.map_err(shortened),
                            },
                        );
                    }
                }
                Ready::Submission {
                    operation,
                    submission,
                    input,
                } => {
                    let Some(pending) = self.take(&operation, store) else {
                        continue;
                    };
                    if pending.cancel.load(Ordering::Acquire) {
                        emit(publish, ComposerEvent::Cancelled { operation });
                        continue;
                    }
                    let result = input.and_then(|input| {
                        admit_submission(submission, input, store, runs, publish, tx, mcp)
                    });
                    if let Err(error) = result {
                        emit(
                            publish,
                            ComposerEvent::Rejected {
                                operation,
                                error: shortened(error),
                            },
                        );
                    }
                }
            }
        }
    }
}
impl Drop for ComposerRuntime {
    fn drop(&mut self) {
        for pending in self.pending.values() {
            pending.cancel.store(true, Ordering::Release);
            attachments::cancel(&pending.operation.id);
            voice::cancel(&pending.operation.id);
        }
        if let Some(speech) = &self.speech {
            speech.cancelled.store(true, Ordering::Release);
            if let Some(speaker) = &speech.speaker {
                voice::cancel_speaker(speaker);
            }
        }
    }
}

fn admit_submission(
    submission: Submission,
    mut input: PreparedInput,
    store: &mut Store,
    runs: &mut Runs,
    publish: &Publisher,
    tx: &SyncSender<WorkerEvent>,
    mcp: Option<&McpRuntime>,
) -> Result<(), String> {
    use composer::{SubmitMode, slash::Prepared};
    let operation = submission.operation.clone();
    let conversation = operation.conversation_id.clone();
    if submission.mode == SubmitMode::Steer {
        let expected = submission
            .expected_run
            .clone()
            .ok_or("立即插话缺少原运行")?;
        if let Some(command) = crate::goal::slash_argument(&submission.text) {
            let parsed = crate::goal::Slash::parse(command)?;
            if matches!(parsed, crate::goal::Slash::Read | crate::goal::Slash::Clear) {
                if !submission.attachments.is_empty() {
                    return Err("本地目标指令不能附带附件，原草稿与附件保留".into());
                }
                let result = super::immediate_slash::execute_local_now(
                    store,
                    runs,
                    &expected,
                    &submission.text,
                    publish,
                );
                emit(
                    publish,
                    ComposerEvent::CommandFinished { operation, result },
                );
                return Ok(());
            }
        }
        let turn = QueuedTurn {
            id: unique_id(),
            text: submission.text,
            attachments: submission.attachments,
            transient: None,
        };
        let (turns, id) = super::immediate_slash::admit_new(store, runs, expected.clone(), turn)?;
        publish.emit(Event::QueuedTurns {
            conversation_id: expected.conversation_id.clone(),
            turns,
        });
        emit(
            publish,
            ComposerEvent::Accepted {
                operation,
                conversation_id: expected.conversation_id,
                turn_id: id,
            },
        );
        return Ok(());
    }
    if submission.mode == SubmitMode::Queue {
        let id = conversation.clone().ok_or("排队消息缺少原会话")?;
        let turn = QueuedTurn {
            id: unique_id(),
            text: submission.text,
            attachments: submission.attachments,
            transient: None,
        };
        let turn_id = turn.id.clone();
        let turns = super::queue_route::admit(store, runs, &id, turn, false)?;
        runs.authorized_queue.insert((id.clone(), turn_id.clone()));
        publish.emit(Event::QueuedTurns {
            conversation_id: id.clone(),
            turns,
        });
        emit(
            publish,
            ComposerEvent::Accepted {
                operation,
                conversation_id: id,
                turn_id,
            },
        );
        return Ok(());
    }

    let route = conversation
        .as_ref()
        .map(|id| store.conversation(id))
        .transpose()?;
    let assistant = route
        .and_then(|route| route.assistant_id.as_deref())
        .or(store.settings.active_assistant_id.as_deref());
    let (_, context) = store.settings.resolved_for(
        assistant,
        route.and_then(|route| route.provider_id.as_deref()),
        route.and_then(|route| route.model.as_deref()),
    )?;
    let plugins = store.plugins()?;
    let bindings = mcp
        .filter(|mcp| !mcp.configuration_pending)
        .map(|mcp| mcp.bridge.snapshot().tools)
        .unwrap_or_default();
    let tools = runtime_tool_definitions(&plugins, &bindings)?;
    let prepared = if submission.mode == SubmitMode::Goal {
        Prepared::Goal {
            command: crate::goal::Slash::Start(submission.text.clone()),
            legacy_tool_name: "goal",
        }
    } else {
        composer::slash::prepare(
            &submission.text,
            &tools,
            &context.disabled_tools,
            !submission.attachments.is_empty(),
        )?
    };
    let mut goal = false;
    let mut resume = None;
    let mut initial_tool = None;
    match prepared {
        Prepared::Plain => {}
        Prepared::Tool {
            call,
            transcript_text,
        } => {
            input.text = transcript_text;
            initial_tool = Some(call);
        }
        Prepared::Goal { command, .. } => match command {
            crate::goal::Slash::Read => {
                if conversation
                    .as_deref()
                    .is_none_or(|id| store.goal().chat_id.is_empty() || store.goal().chat_id == id)
                {
                    publish.emit(Event::Goal(store.goal().clone()));
                } else {
                    return Err("目标属于其它会话".into());
                }
                emit(
                    publish,
                    ComposerEvent::CommandFinished {
                        operation,
                        result: Ok(()),
                    },
                );
                return Ok(());
            }
            crate::goal::Slash::Clear => {
                if conversation
                    .as_deref()
                    .is_some_and(|id| !store.goal().chat_id.is_empty() && store.goal().chat_id != id)
                {
                    return Err("目标属于其它会话".into());
                }
                if runs.by_conversation.contains_key(&store.goal().chat_id) {
                    return Err("请先停止目标的当前运行".into());
                }
                let goal_id = store.goal().id.clone();
                store.clear_goal(&goal_id)?;
                publish.emit(Event::Goal(store.goal().clone()));
                emit(
                    publish,
                    ComposerEvent::CommandFinished {
                        operation,
                        result: Ok(()),
                    },
                );
                return Ok(());
            }
            crate::goal::Slash::Resume => {
                if !store.goal().visible_in(conversation.as_deref()) {
                    return Err("继续目标需要原会话".into());
                }
                input.text = "继续完成已保存的目标".into();
                resume = Some(store.goal().id.clone());
            }
            crate::goal::Slash::Start(text) => {
                if submission.mode != SubmitMode::Goal {
                    input.text = text;
                }
                goal = true;
            }
        },
    }
    if conversation
        .as_ref()
        .is_some_and(|id| runs.by_conversation.contains_key(id))
    {
        return Err("当前会话仍有运行，请等待结束或停止".into());
    }
    if runs.by_id.len() >= 8 {
        return Err("同时运行的会话已达到8个".into());
    }
    let mut active = None;
    let mode = if let Some(expected) = resume {
        RunMode::ResumeGoal(expected)
    } else {
        RunMode::Compose {
            input: Box::new(input),
            goal,
            initial_tool,
        }
    };
    let text = match &mode {
        RunMode::Compose { input, .. } => input.text.clone(),
        _ => "继续完成已保存的目标".into(),
    };
    let result = start_run(
        conversation,
        text,
        mode,
        store,
        publish,
        tx,
        &mut active,
        mcp,
    );
    let accepted = active.as_ref().map(|run| {
        (
            run.conversation.clone(),
            run.messages
                .iter()
                .rev()
                .find(|message| message.role == "user")
                .map(|message| message.id.clone())
                .unwrap_or_else(|| run.message_id.clone()),
        )
    });
    if let Some(active) = active {
        runs.insert(active);
    }
    result?;
    let (conversation_id, turn_id) = accepted.ok_or("运行接收身份不存在")?;
    emit(
        publish,
        ComposerEvent::Accepted {
            operation,
            conversation_id,
            turn_id,
        },
    );
    Ok(())
}
