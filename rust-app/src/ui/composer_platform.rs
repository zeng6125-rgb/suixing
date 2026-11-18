//! Composer state. Text, attachments and asynchronous ownership
//! live together; draw emits commands and never reads files or calls JNI.
use crate::{
    composer::{
        AttachmentBatch, AttachmentRef, ComposerCommand, ComposerEvent, MAX_ATTACHMENT_BYTES,
        MAX_ATTACHMENTS, MAX_IMAGE_WIRE_BYTES, MAX_TEXT_BYTES, RecordedAudio, SpeechPhase,
        Submission, SubmitMode,
    },
    platform::{
        attachments::{AttachmentKind, PixelFrame},
        permissions::{OperationKey, PermissionKind, PermissionOutcome, PermissionResult},
        voice::RecordingStatus,
    },
};
use eframe::egui::{self, RichText, TextureHandle};
use std::collections::{BTreeMap, HashMap};

const MAX_DRAFTS: usize = 128;
const MAX_DRAFT_BYTES: usize = 64 * 1024 * 1024;
const MAX_PENDING: usize = 32;
const MAX_SUBMISSIONS: usize = 8;
const MAX_TEXTURE_BYTES: usize = 20 * 1024 * 1024;
const MAX_TEXTURES: usize = 64;

#[derive(Debug)]
pub enum ComposerIntent {
    Command(ComposerCommand),
    MicrophonePermission(OperationKey),
}
// Commands deliberately have no Clone; large submissions must not be duplicated
// by rendering or intermediate event snapshots. Audio remains on the actor.

#[derive(Clone, Debug, PartialEq, Eq)]
enum PendingKind {
    Picker,
    Preview,
    Microphone,
    Recording,
    Transcription,
    Submission,
}
struct Pending {
    operation: OperationKey,
    kind: PendingKind,
    text_revision: u64,
}
#[derive(Clone)]
struct Submitted {
    expected_run: Option<crate::runtime_host::RunKey>,
    operation: OperationKey,
    draft_revision: u64,
    text: String,
    attachments: Vec<AttachmentRef>,
    mode: SubmitMode,
}
#[derive(Clone, Debug, PartialEq, Eq)]
enum VoicePhase {
    Permission,
    Starting,
    Recording,
    Finishing,
    Recorded,
    Transcribing,
}
struct VoiceDraft {
    operation: OperationKey,
    original_text_revision: u64,
    phase: VoicePhase,
    finish_and_transcribe: bool,
    cancel_requested: bool,
    elapsed_ms: u64,
    level: f32,
    audio: Option<RecordedAudio>,
}
struct Draft {
    generation: u64,
    revision: u64,
    text_revision: u64,
    text: String,
    attachments: Vec<AttachmentRef>,
    voice: Option<VoiceDraft>,
    /// An ASR result for an edited draft is shown only for explicit copying.
    transcript: Option<String>,
    error: Option<String>,
    notice: Option<String>,
}
impl Draft {
    fn new(generation: u64) -> Self {
        Self {
            generation,
            revision: 1,
            text_revision: 1,
            text: String::new(),
            attachments: Vec::new(),
            voice: None,
            transcript: None,
            error: None,
            notice: None,
        }
    }
    fn bytes(&self) -> usize {
        self.text.len()
            + self
                .attachments
                .iter()
                .map(AttachmentRef::metadata_bytes)
                .sum::<usize>()
            + self.transcript.as_ref().map_or(0, String::len)
    }
    fn mutate_text(&mut self) {
        self.revision = self.revision.saturating_add(1);
        self.text_revision = self.text_revision.saturating_add(1);
    }
    fn has_content(&self) -> bool {
        !self.text.trim().is_empty() || !self.attachments.is_empty()
    }
    fn eligible_asr(&self) -> bool {
        crate::composer::slash::parse(&self.text).is_none()
    }
}
struct Preview {
    texture: TextureHandle,
    bytes: usize,
    size: egui::Vec2,
    touched: u64,
}
struct SpeechDraft {
    operation: OperationKey,
    body: crate::display::BodyRef,
    phase: SpeechPhase,
}
pub struct ComposerPlatform {
    /// None is the current new-chat slot. Starting another new-chat draft assigns
    /// a fresh global generation, so older picker/ASR results cannot attach to it.
    selected: Option<String>,
    drafts: BTreeMap<Option<String>, Draft>,
    pending: HashMap<String, Pending>,
    submitted: HashMap<String, Submitted>,
    previews: HashMap<String, Preview>,
    preview_requests: HashMap<String, AttachmentRef>,
    speech: HashMap<String, SpeechDraft>,
    generation: u64,
    clock: u64,
}
impl Default for ComposerPlatform {
    fn default() -> Self {
        Self {
            selected: None,
            drafts: [(None, Draft::new(1))].into_iter().collect(),
            pending: HashMap::new(),
            submitted: HashMap::new(),
            previews: HashMap::new(),
            preview_requests: HashMap::new(),
            speech: HashMap::new(),
            generation: 1,
            clock: 0,
        }
    }
}
pub struct ComposerUi {
    pub loaded: bool,
    pub connected: bool,
    pub visible: bool,
    pub configured: bool,
    pub running: bool,
    pub cancelling: bool,
    pub immediately: bool,
    pub conversation_pending: bool,
    pub can_start_goal: bool,
    pub image_input: bool,
    pub voice_input: bool,
    pub enter_key_sends: bool,
    pub expected_run: Option<crate::runtime_host::RunKey>,
}
pub struct Acceptance {
    /// The caller selects the created conversation only while still on the exact
    /// original new-chat draft. Background acceptance never changes selection.
    pub select_created: Option<String>,
    pub cleared: bool,
}
impl ComposerPlatform {
    fn next_generation(&mut self) -> Result<u64, String> {
        self.generation = self.generation.checked_add(1).ok_or("草稿代数已耗尽")?;
        Ok(self.generation)
    }
    pub fn activate(&mut self, conversation_id: Option<String>) -> Result<(), String> {
        if !self.drafts.contains_key(&conversation_id) {
            if self.drafts.len() >= MAX_DRAFTS {
                let empty = self
                    .drafts
                    .iter()
                    .find(|(key, draft)| {
                        !draft.has_content()
                            && draft.voice.is_none()
                            && !self
                                .pending
                                .values()
                                .any(|p| &p.operation.conversation_id == *key)
                    })
                    .map(|(key, _)| key.clone());
                let Some(empty) = empty else {
                    return Err("已有128份未清空草稿，请先处理已有草稿".into());
                };
                self.drafts.remove(&empty);
            }
            let generation = self.next_generation()?;
            self.drafts
                .insert(conversation_id.clone(), Draft::new(generation));
        }
        self.selected = conversation_id;
        Ok(())
    }
    pub fn begin_new(&mut self) -> Result<(), String> {
        // Explicit new-chat reset is the only operation which abandons this slot.
        // Caller must retain/show the existing nonempty draft unless user clears it.
        if self.drafts.get(&None).is_some_and(|draft| {
            draft.has_content() || draft.voice.is_some() || draft.transcript.is_some()
        }) || self
            .pending
            .values()
            .any(|pending| pending.operation.conversation_id.is_none())
        {
            return self.activate(None);
        }
        self.activate(None)?;
        let generation = self.next_generation()?;
        self.drafts.insert(None, Draft::new(generation));
        self.selected = None;
        Ok(())
    }
    pub fn conversation_opened(&mut self, id: &str) -> Result<(), String> {
        // Send from a new-chat slot may publish ConversationOpened before the
        // admission acknowledgement. Keep the submitted slot until that exact
        // acknowledgement clears it; never visually drop the composer early.
        if self.selected.is_none()
            && self
                .submitted
                .values()
                .any(|pending| pending.operation.conversation_id.is_none())
        {
            return Ok(());
        }
        self.activate(Some(id.into()))
    }
    pub fn text(&self) -> &str {
        self.drafts
            .get(&self.selected)
            .map_or("", |draft| draft.text.as_str())
    }
    pub fn reference_file(&mut self, path: &str) -> Result<(), String> {
        if path.is_empty() || path.len() > 4096 || path.contains('\0') {
            return Err("文件引用路径无效".into());
        }
        if self
            .submitted
            .values()
            .any(|submission| submission.operation.conversation_id == self.selected)
        {
            return Err("正在提交当前草稿，请等待接收确认".into());
        }
        let draft = self
            .drafts
            .get_mut(&self.selected)
            .ok_or("当前草稿不存在")?;
        let text = crate::composer::references::append(&draft.text, path);
        if text.len() > MAX_TEXT_BYTES {
            return Err("添加引用后正文超过1 MiB".into());
        }
        crate::composer::input_json_bytes(&text, &draft.attachments)?;
        draft.text = text;
        draft.mutate_text();
        Ok(())
    }
    pub fn has_content(&self) -> bool {
        self.drafts
            .get(&self.selected)
            .is_some_and(Draft::has_content)
    }
    pub fn has_attachments(&self) -> bool {
        self.drafts
            .get(&self.selected)
            .is_some_and(|draft| !draft.attachments.is_empty())
    }
    pub fn operation_pending(&self) -> bool {
        !self.pending.is_empty()
            || self.speech.values().any(|speech| {
                !matches!(speech.phase, SpeechPhase::Finished | SpeechPhase::Failed(_))
            })
    }
    pub fn belongs_to(&self, conversation_id: &Option<String>) -> bool {
        &self.selected == conversation_id
    }
    fn operation(&self) -> Result<OperationKey, String> {
        let draft = self
            .drafts
            .get(&self.selected)
            .ok_or("当前会话草稿尚未打开")?;
        Ok(OperationKey {
            id: crate::controller::unique_id(),
            conversation_id: self.selected.clone(),
            generation: draft.generation,
        })
    }
    fn owns(&self, operation: &OperationKey) -> bool {
        self.drafts
            .get(&operation.conversation_id)
            .is_some_and(|draft| draft.generation == operation.generation)
    }
    fn admit(&mut self, kind: PendingKind) -> Result<OperationKey, String> {
        if self.pending.len() >= MAX_PENDING {
            return Err("草稿操作队列已满".into());
        }
        let operation = self.operation()?;
        let text_revision = self.drafts[&self.selected].text_revision;
        self.pending.insert(
            operation.id.clone(),
            Pending {
                operation: operation.clone(),
                kind,
                text_revision,
            },
        );
        Ok(operation)
    }
    fn take_pending(&mut self, operation: &OperationKey, kind: PendingKind) -> Option<Pending> {
        let matching = self
            .pending
            .get(&operation.id)
            .is_some_and(|pending| pending.operation == *operation && pending.kind == kind);
        if !matching {
            return None;
        }
        let pending = self.pending.remove(&operation.id)?;
        if self.owns(operation) {
            Some(pending)
        } else {
            self.submitted.remove(&operation.id);
            self.preview_requests.remove(&operation.id);
            None
        }
    }
    fn error(&mut self, operation: &OperationKey, error: String) {
        if self.owns(operation) {
            if let Some(draft) = self.drafts.get_mut(&operation.conversation_id) {
                draft.error = Some(bounded(&error, 2048));
            }
        }
    }
    pub fn dispatch_failed(&mut self, operation: &OperationKey, error: &str) {
        if let Some(speech) = self
            .speech
            .get_mut(&operation.id)
            .filter(|speech| speech.operation == *operation)
        {
            speech.phase = SpeechPhase::Failed(bounded(error, 2048));
        }
        if self
            .pending
            .get(&operation.id)
            .is_some_and(|pending| pending.operation == *operation)
        {
            self.pending.remove(&operation.id);
            self.submitted.remove(&operation.id);
            self.preview_requests.remove(&operation.id);
            if self.owns(operation) {
                let draft = self.drafts.get_mut(&operation.conversation_id).unwrap();
                if draft
                    .voice
                    .as_ref()
                    .is_some_and(|voice| voice.operation == *operation)
                {
                    draft.voice = None;
                }
                draft.error = Some(bounded(error, 2048));
            }
        }
    }
    pub fn pick(&mut self, kind: AttachmentKind) -> Result<ComposerIntent, String> {
        let draft = self.drafts.get(&self.selected).ok_or("草稿尚未打开")?;
        if draft.attachments.len() >= MAX_ATTACHMENTS {
            return Err("草稿已达到256个附件".into());
        }
        if self
            .pending
            .values()
            .any(|p| p.kind == PendingKind::Picker && p.operation.conversation_id == self.selected)
        {
            return Err("当前附件正在读取".into());
        }
        let operation = self.admit(PendingKind::Picker)?;
        Ok(ComposerIntent::Command(ComposerCommand::PickAttachment {
            operation,
            kind,
        }))
    }
    /// Event-drain only: all bitmap validation/upload occurs outside drawing.
    pub fn attachment_result(
        &mut self,
        ctx: &egui::Context,
        operation: &OperationKey,
        result: &Result<std::sync::Arc<AttachmentBatch>, String>,
        cancelled: bool,
    ) {
        if self.take_pending(operation, PendingKind::Picker).is_none() {
            return;
        }
        if cancelled {
            return;
        }
        let batch = match result {
            Ok(values) => values,
            Err(error) => {
                self.error(operation, error.clone());
                return;
            }
        };
        if let Err(error) = batch.validate() {
            self.error(operation, error);
            return;
        }
        let added = &batch.attachments;
        let draft = &self.drafts[&operation.conversation_id];
        let total_count = draft.attachments.len().saturating_add(added.len());
        let total_bytes = draft
            .attachments
            .iter()
            .chain(added)
            .try_fold(0u64, |total, value| total.checked_add(value.bytes));
        let image_bytes = draft
            .attachments
            .iter()
            .chain(added)
            .try_fold(0usize, |total, value| {
                total.checked_add(value.image_wire_bytes)
            });
        let metadata = self
            .drafts
            .values()
            .map(Draft::bytes)
            .sum::<usize>()
            .saturating_add(
                added
                    .iter()
                    .map(AttachmentRef::metadata_bytes)
                    .sum::<usize>(),
            );
        let mut ids = std::collections::HashSet::new();
        let duplicate = draft
            .attachments
            .iter()
            .chain(added)
            .any(|value| !ids.insert(&value.id));
        if total_count > MAX_ATTACHMENTS
            || total_bytes.is_none_or(|bytes| bytes > MAX_ATTACHMENT_BYTES)
            || image_bytes.is_none_or(|bytes| bytes > MAX_IMAGE_WIRE_BYTES)
            || metadata > MAX_DRAFT_BYTES
            || duplicate
        {
            self.error(operation, "这一批附件超过草稿总预算，未添加部分结果".into());
            return;
        }
        let combined: Vec<_> = draft.attachments.iter().chain(added).cloned().collect();
        if let Err(error) = crate::composer::input_json_bytes(&draft.text, &combined) {
            self.error(operation, error);
            return;
        }
        for value in &batch.previews {
            self.upload_preview(ctx, &value.attachment_id, &value.pixels);
        }
        let draft = self.drafts.get_mut(&operation.conversation_id).unwrap();
        draft.attachments.extend(added.iter().cloned());
        draft.revision = draft.revision.saturating_add(1);
        draft.error = None;
    }
    fn upload_preview(&mut self, ctx: &egui::Context, id: &str, pixels: &PixelFrame) {
        let count = (pixels.width as usize)
            .checked_mul(pixels.height as usize)
            .and_then(|n| n.checked_mul(4));
        if pixels.width == 0
            || pixels.height == 0
            || pixels.width > 320
            || pixels.height > 320
            || count != Some(pixels.rgba.len())
            || pixels.rgba.len() > MAX_TEXTURE_BYTES
        {
            return;
        }
        self.clock = self.clock.saturating_add(1);
        while self.previews.len() >= MAX_TEXTURES
            || self
                .previews
                .values()
                .map(|preview| preview.bytes)
                .sum::<usize>()
                .saturating_add(pixels.rgba.len())
                > MAX_TEXTURE_BYTES
        {
            let oldest = self
                .previews
                .iter()
                .min_by_key(|(_, preview)| preview.touched)
                .map(|(id, _)| id.clone());
            let Some(oldest) = oldest else {
                break;
            };
            self.previews.remove(&oldest);
        }
        let image = egui::ColorImage::from_rgba_unmultiplied(
            [pixels.width as usize, pixels.height as usize],
            &pixels.rgba,
        );
        let texture = ctx.load_texture(
            format!("composer-{id}"),
            image,
            egui::TextureOptions::LINEAR,
        );
        self.previews.insert(
            id.into(),
            Preview {
                texture,
                bytes: pixels.rgba.len(),
                size: egui::vec2(pixels.width as f32, pixels.height as f32),
                touched: self.clock,
            },
        );
    }
    pub fn remove_attachment(&mut self, id: &str) {
        if let Some(draft) = self.drafts.get_mut(&self.selected) {
            let before = draft.attachments.len();
            draft.attachments.retain(|value| value.id != id);
            if before != draft.attachments.len() {
                draft.revision = draft.revision.saturating_add(1);
            }
        }
        self.previews.remove(id);
    }
    pub fn request_preview(&mut self, id: &str) -> Result<ComposerIntent, String> {
        let attachment = self
            .drafts
            .get(&self.selected)
            .and_then(|draft| draft.attachments.iter().find(|value| value.id == id))
            .filter(|value| value.image)
            .cloned()
            .ok_or("图片附件已不存在")?;
        if self
            .preview_requests
            .values()
            .any(|pending| pending.id == id)
        {
            return Err("图片预览正在读取".into());
        }
        let operation = self.admit(PendingKind::Preview)?;
        self.preview_requests
            .insert(operation.id.clone(), attachment.clone());
        Ok(ComposerIntent::Command(
            ComposerCommand::PreviewAttachment {
                operation,
                attachment,
            },
        ))
    }
    pub fn preview_result(
        &mut self,
        ctx: &egui::Context,
        operation: &OperationKey,
        id: &str,
        pixels: Result<&PixelFrame, &str>,
    ) {
        if !self
            .preview_requests
            .get(&operation.id)
            .is_some_and(|attachment| attachment.id == id)
            || self.take_pending(operation, PendingKind::Preview).is_none()
        {
            return;
        }
        let Some(expected) = self.preview_requests.remove(&operation.id) else {
            return;
        };
        if !self
            .drafts
            .get(&operation.conversation_id)
            .is_some_and(|draft| {
                draft
                    .attachments
                    .iter()
                    .any(|attachment| attachment == &expected)
            })
        {
            return;
        }
        match pixels {
            Ok(pixels) => self.upload_preview(ctx, id, pixels),
            Err(error) => self.error(operation, error.into()),
        }
    }
    pub fn prepare_submit(
        &mut self,
        mode: SubmitMode,
        expected_run: Option<crate::runtime_host::RunKey>,
    ) -> Result<ComposerIntent, String> {
        if self.submitted.len() >= MAX_SUBMISSIONS
            || self
                .submitted
                .values()
                .any(|value| value.operation.conversation_id == self.selected)
        {
            return Err("草稿提交正在等待确认".into());
        }
        if self.drafts[&self.selected].voice.is_some() {
            return Err("请先完成或取消语音输入".into());
        }
        let operation = self.admit(PendingKind::Submission)?;
        let draft = &self.drafts[&self.selected];
        let submission = Submission {
            operation: operation.clone(),
            draft_revision: draft.revision,
            expected_run,
            text: draft.text.clone(),
            attachments: draft.attachments.clone(),
            mode,
        };
        if let Err(error) = submission.validate() {
            self.pending.remove(&operation.id);
            return Err(error);
        }
        self.submitted.insert(
            operation.id.clone(),
            Submitted {
                operation: operation.clone(),
                draft_revision: submission.draft_revision,
                expected_run: submission.expected_run.clone(),
                text: submission.text.clone(),
                attachments: submission.attachments.clone(),
                mode: submission.mode.clone(),
            },
        );
        Ok(ComposerIntent::Command(ComposerCommand::Submit(submission)))
    }
    pub fn pending_expected_run(
        &self,
        operation: &OperationKey,
    ) -> Option<crate::runtime_host::RunKey> {
        self.submitted
            .get(&operation.id)
            .filter(|submission| submission.operation == *operation && self.owns(operation))
            .and_then(|submission| submission.expected_run.clone())
    }
    pub fn pending_mode(&self, operation: &OperationKey) -> Option<SubmitMode> {
        self.submitted
            .get(&operation.id)
            .filter(|submission| submission.operation == *operation && self.owns(operation))
            .map(|submission| submission.mode.clone())
    }
    pub fn accepted(&mut self, operation: &OperationKey, conversation_id: &str) -> Acceptance {
        if operation
            .conversation_id
            .as_ref()
            .is_some_and(|id| id != conversation_id)
        {
            return Acceptance {
                select_created: None,
                cleared: false,
            };
        }
        self.confirm_submission(operation, Some(conversation_id))
    }
    pub fn command_finished(&mut self, operation: &OperationKey, result: &Result<(), String>) {
        if !self.submitted.get(&operation.id).is_some_and(|submitted| {
            submitted.operation == *operation
                && matches!(submitted.mode, SubmitMode::Send | SubmitMode::Steer)
                && !crate::composer::slash::starts_run(&submitted.text)
        }) {
            return;
        }
        match result {
            Ok(()) => {
                self.confirm_submission(operation, None);
            }
            Err(error) => self.rejected(operation, error),
        }
    }
    fn confirm_submission(
        &mut self,
        operation: &OperationKey,
        conversation_id: Option<&str>,
    ) -> Acceptance {
        let mut acceptance = Acceptance {
            select_created: None,
            cleared: false,
        };
        if self
            .take_pending(operation, PendingKind::Submission)
            .is_none()
        {
            return acceptance;
        }
        let Some(submitted) = self.submitted.remove(&operation.id) else {
            return acceptance;
        };
        let exact = self
            .drafts
            .get(&operation.conversation_id)
            .is_some_and(|draft| {
                draft.generation == operation.generation
                    && draft.revision == submitted.draft_revision
                    && draft.text == submitted.text
                    && draft.attachments == submitted.attachments
            });
        if !exact {
            if let Some(draft) = self.drafts.get_mut(&operation.conversation_id) {
                draft.notice = Some("已接收此前提交的内容；当前草稿有修改，已保留".into());
            }
            return acceptance;
        }
        let Ok(generation) = self.next_generation() else {
            return acceptance;
        };
        for attachment in &submitted.attachments {
            self.previews.remove(&attachment.id);
        }
        self.drafts
            .insert(operation.conversation_id.clone(), Draft::new(generation));
        acceptance.cleared = true;
        if operation.conversation_id.is_none() && self.selected.is_none() {
            acceptance.select_created = conversation_id.map(str::to_owned);
        }
        acceptance
    }
    pub fn rejected(&mut self, operation: &OperationKey, error: &str) {
        if !self.pending.get(&operation.id).is_some_and(|pending| {
            pending.operation == *operation && pending.kind == PendingKind::Submission
        }) {
            self.dispatch_failed(operation, error);
            return;
        }
        if self
            .take_pending(operation, PendingKind::Submission)
            .is_none()
        {
            return;
        }
        self.submitted.remove(&operation.id);
        self.error(operation, error.into());
    }
    pub fn start_microphone(&mut self) -> Result<ComposerIntent, String> {
        if self.drafts.values().any(|draft| draft.voice.is_some()) {
            return Err("已有语音输入正在进行".into());
        }
        if !self.drafts[&self.selected].eligible_asr() {
            return Err("斜杠指令请直接编辑，语音输入不会覆盖指令".into());
        }
        let operation = self.admit(PendingKind::Microphone)?;
        let draft = self.drafts.get_mut(&self.selected).unwrap();
        draft.voice = Some(VoiceDraft {
            operation: operation.clone(),
            original_text_revision: draft.text_revision,
            phase: VoicePhase::Permission,
            finish_and_transcribe: false,
            cancel_requested: false,
            elapsed_ms: 0,
            level: 0.,
            audio: None,
        });
        draft.error = None;
        Ok(ComposerIntent::MicrophonePermission(operation))
    }
    pub fn permission_result(
        &mut self,
        result: &PermissionResult,
        visible: bool,
    ) -> Vec<ComposerIntent> {
        let Some(pending) = self.take_pending(&result.operation, PendingKind::Microphone) else {
            return Vec::new();
        };
        let granted = result.error.is_none()
            && result.permissions.iter().any(|(kind, outcome)| {
                *kind == PermissionKind::Microphone && *outcome == PermissionOutcome::Granted
            });
        let cancelled = self
            .drafts
            .get(&result.operation.conversation_id)
            .and_then(|draft| draft.voice.as_ref())
            .is_some_and(|voice| voice.cancel_requested);
        let still_eligible = visible
            && self.selected == result.operation.conversation_id
            && self.drafts[&self.selected].text_revision == pending.text_revision
            && self.drafts[&self.selected].eligible_asr();
        if cancelled || !granted || !still_eligible {
            self.drafts
                .get_mut(&result.operation.conversation_id)
                .unwrap()
                .voice = None;
            self.error(
                &result.operation,
                if cancelled {
                    "语音输入已取消".into()
                } else if granted {
                    "当前草稿或页面已改变，录音未启动".into()
                } else {
                    result
                        .error
                        .clone()
                        .unwrap_or_else(|| "尚未获得麦克风权限".into())
                },
            );
            return Vec::new();
        }
        let operation = match self.admit(PendingKind::Recording) {
            Ok(operation) => operation,
            Err(error) => {
                self.drafts.get_mut(&self.selected).unwrap().voice = None;
                self.error(&result.operation, error);
                return Vec::new();
            }
        };
        let voice = self
            .drafts
            .get_mut(&self.selected)
            .unwrap()
            .voice
            .as_mut()
            .unwrap();
        voice.operation = operation.clone();
        voice.phase = VoicePhase::Starting;
        vec![ComposerIntent::Command(ComposerCommand::StartRecording {
            operation,
            origin_text_revision: voice.original_text_revision,
        })]
    }
    pub fn recording_status(&mut self, status: &RecordingStatus) {
        if !self.owns(&status.operation)
            || !self
                .pending
                .get(&status.operation.id)
                .is_some_and(|pending| {
                    pending.operation == status.operation && pending.kind == PendingKind::Recording
                })
        {
            return;
        }
        if let Some(voice) = self
            .drafts
            .get_mut(&status.operation.conversation_id)
            .and_then(|draft| draft.voice.as_mut())
        {
            if voice.operation != status.operation {
                return;
            }
            voice.elapsed_ms = status.elapsed_ms.min(60_000);
            voice.level = if status.level.is_finite() {
                status.level.clamp(0., 1.)
            } else {
                0.
            };
            voice.phase = if voice.cancel_requested || status.stopping {
                VoicePhase::Finishing
            } else if status.started {
                VoicePhase::Recording
            } else {
                VoicePhase::Starting
            };
        }
    }
    pub fn finish_recording(&mut self) -> Option<ComposerIntent> {
        let voice = self.drafts.get_mut(&self.selected)?.voice.as_mut()?;
        if !matches!(voice.phase, VoicePhase::Starting | VoicePhase::Recording) {
            return None;
        }
        voice.finish_and_transcribe = true;
        voice.phase = VoicePhase::Finishing;
        Some(ComposerIntent::Command(ComposerCommand::FinishRecording(
            voice.operation.clone(),
        )))
    }
    fn transcribe_for(&mut self, target: &Option<String>) -> Result<ComposerIntent, String> {
        // Use the original slot even if its chat is no longer selected.
        if self.pending.len() >= MAX_PENDING {
            return Err("语音操作队列已满".into());
        }
        let draft = self.drafts.get_mut(target).ok_or("原录音草稿不存在")?;
        let voice = draft.voice.as_mut().ok_or("没有完成的录音")?;
        let audio = voice.audio.take().ok_or("没有完成的录音")?;
        let operation = OperationKey {
            id: crate::controller::unique_id(),
            conversation_id: target.clone(),
            generation: draft.generation,
        };
        self.pending.insert(
            operation.id.clone(),
            Pending {
                operation: operation.clone(),
                kind: PendingKind::Transcription,
                text_revision: voice.original_text_revision,
            },
        );
        voice.operation = operation.clone();
        voice.phase = VoicePhase::Transcribing;
        Ok(ComposerIntent::Command(
            ComposerCommand::TranscribeRecording {
                operation,
                audio,
                origin_text_revision: voice.original_text_revision,
            },
        ))
    }
    pub fn voice_result(&mut self, result: &ComposerEvent) -> Vec<ComposerIntent> {
        match result {
            ComposerEvent::RecordingFinished {
                operation,
                origin_text_revision,
                result,
            } => {
                if self
                    .pending
                    .get(&operation.id)
                    .is_none_or(|pending| pending.text_revision != *origin_text_revision)
                {
                    return Vec::new();
                }
                if self
                    .take_pending(operation, PendingKind::Recording)
                    .is_none()
                {
                    return Vec::new();
                }
                let draft = self.drafts.get_mut(&operation.conversation_id).unwrap();
                let Some(voice) = draft
                    .voice
                    .as_mut()
                    .filter(|voice| voice.operation == *operation)
                else {
                    return Vec::new();
                };
                if voice.cancel_requested {
                    draft.voice = None;
                    draft.notice = Some("语音输入已取消".into());
                    return Vec::new();
                }
                match result {
                    Ok(audio)
                        if audio.token.len() <= 128
                            && !audio.token.is_empty()
                            && audio.duration_ms <= 60_000
                            && audio.samples > 0
                            && audio.samples <= 960_000
                            && audio.duration_ms == audio.samples * 1000 / 16_000 =>
                    {
                        voice.phase = VoicePhase::Recorded;
                        voice.elapsed_ms = audio.duration_ms;
                        voice.level = 0.;
                        voice.audio = Some(audio.clone());
                        if voice.finish_and_transcribe {
                            return match self.transcribe_for(&operation.conversation_id) {
                                Ok(intent) => vec![intent],
                                Err(error) => {
                                    self.error(operation, error);
                                    Vec::new()
                                }
                            };
                        }
                    }
                    Ok(_) => {
                        draft.voice = None;
                        draft.error = Some("录音票据无效".into());
                    }
                    Err(error) => {
                        draft.voice = None;
                        draft.error = Some(bounded(error, 2048));
                    }
                }
            }
            ComposerEvent::TranscriptionFinished {
                operation,
                origin_text_revision,
                result,
            } => {
                if self
                    .pending
                    .get(&operation.id)
                    .is_none_or(|pending| pending.text_revision != *origin_text_revision)
                {
                    return Vec::new();
                }
                let Some(pending) = self.take_pending(operation, PendingKind::Transcription) else {
                    return Vec::new();
                };
                let draft = self.drafts.get_mut(&operation.conversation_id).unwrap();
                if draft
                    .voice
                    .as_ref()
                    .is_none_or(|voice| voice.operation != *operation)
                {
                    return Vec::new();
                }
                let cancelled = draft
                    .voice
                    .as_ref()
                    .is_some_and(|voice| voice.cancel_requested);
                draft.voice = None;
                if cancelled {
                    draft.notice = Some("语音输入已取消".into());
                    return Vec::new();
                }
                match result {
                    Ok(text) if text.len() <= 64 * 1024 && !text.contains('\0') => {
                        let text = text.trim();
                        if draft.text_revision == pending.text_revision
                            && draft.eligible_asr()
                            && !self.submitted.values().any(|submitted| {
                                submitted.operation.conversation_id == operation.conversation_id
                            })
                            && draft
                                .text
                                .len()
                                .saturating_add(text.len())
                                .saturating_add(1)
                                <= MAX_TEXT_BYTES
                        {
                            if !draft.text.is_empty() && !text.is_empty() {
                                draft.text.push('\n');
                            }
                            draft.text.push_str(text);
                            draft.mutate_text();
                            draft.error = None;
                        } else {
                            draft.transcript = Some(text.into());
                            draft.notice = Some("草稿已修改，转写结果已保留供复制".into());
                        }
                    }
                    Ok(_) => draft.error = Some("转写结果超过大小上限或含无效内容".into()),
                    Err(error) => draft.error = Some(bounded(error, 2048)),
                }
            }
            _ => {}
        }
        Vec::new()
    }
    pub fn cancel_voice(&mut self) -> Option<ComposerIntent> {
        let voice = self.drafts.get_mut(&self.selected)?.voice.as_mut()?;
        if voice.cancel_requested {
            return None;
        }
        voice.cancel_requested = true;
        voice.finish_and_transcribe = false;
        voice.phase = VoicePhase::Finishing;
        Some(ComposerIntent::Command(
            ComposerCommand::CancelPlatformOperation(voice.operation.clone()),
        ))
    }
    pub fn cancelled(&mut self, operation: &OperationKey) {
        if !self.owns(operation) {
            return;
        }
        if self
            .pending
            .get(&operation.id)
            .is_some_and(|pending| pending.operation == *operation)
        {
            self.pending.remove(&operation.id);
            self.submitted.remove(&operation.id);
            self.preview_requests.remove(&operation.id);
        }
        let draft = self.drafts.get_mut(&operation.conversation_id).unwrap();
        if draft
            .voice
            .as_ref()
            .is_some_and(|voice| voice.operation == *operation)
        {
            draft.voice = None;
            draft.notice = Some("语音输入已取消".into());
        }
    }
    pub fn speak_reply(
        &mut self,
        body: crate::display::BodyRef,
        opted_in: bool,
    ) -> Result<ComposerIntent, String> {
        if !opted_in {
            return Err("请先启用回答朗读".into());
        }
        if body.bytes == 0 || body.bytes > 256 * 1024 {
            return Err("回答为空或过长".into());
        }
        self.speech.retain(|_, speech| {
            !matches!(speech.phase, SpeechPhase::Finished | SpeechPhase::Failed(_))
        });
        if self.speech.len() >= 8 {
            return Err("回答朗读队列已满".into());
        }
        // Reply speech belongs to the exact displayed source and has its own
        // lifetime; it does not select or mutate a main-conversation draft.
        let operation = OperationKey {
            id: crate::controller::unique_id(),
            conversation_id: Some(body.source.conversation_id().into()),
            generation: self.next_generation()?,
        };
        self.speech.insert(
            operation.id.clone(),
            SpeechDraft {
                operation: operation.clone(),
                body: body.clone(),
                phase: SpeechPhase::RequestingService,
            },
        );
        Ok(ComposerIntent::Command(ComposerCommand::SpeakReply {
            operation,
            body,
        }))
    }
    pub fn speech_status(&mut self, operation: &OperationKey, phase: SpeechPhase) {
        if let Some(speech) = self
            .speech
            .get_mut(&operation.id)
            .filter(|speech| speech.operation == *operation)
        {
            if matches!(speech.phase, SpeechPhase::Finished | SpeechPhase::Failed(_)) {
                return;
            }
            let valid = matches!(
                (&speech.phase, &phase),
                (_, SpeechPhase::Failed(_) | SpeechPhase::Finished)
                    | (SpeechPhase::RequestingService, SpeechPhase::ServiceReady)
                    | (SpeechPhase::ServiceReady, SpeechPhase::Speaking)
                    | (SpeechPhase::Speaking, SpeechPhase::Speaking)
                    | (_, SpeechPhase::WaitingForCleanup)
            );
            if valid {
                speech.phase = phase;
            }
        }
    }
    pub(super) fn fill_suggestion(&mut self, text: &str) {
        if let Some(draft) = self.drafts.get_mut(&self.selected) {
            draft.text = text.to_owned();
            draft.mutate_text();
        }
    }

    pub(super) fn show<'a>(
        &mut self,
        ui: &mut egui::Ui,
        flags: &ComposerUi,
        mut chrome: Option<super::mobile_chat::InputChrome<'a>>,
    ) -> (
        Vec<ComposerIntent>,
        Option<super::mobile_chat::InputChrome<'a>>,
    ) {
        use super::mobile_chat::{Icon, mix};
        let mut intents = Vec::new();
        if !self.drafts.contains_key(&self.selected) {
            ui.label("当前草稿尚未打开");
            return (intents, chrome);
        }
        let editable = flags.loaded
            && flags.connected
            && !flags.conversation_pending
            && !self
                .submitted
                .values()
                .any(|submission| submission.operation.conversation_id == self.selected);
        let current = self.selected.clone();
        let other_bytes = self
            .drafts
            .iter()
            .filter(|(target, _)| **target != current)
            .map(|(_, draft)| draft.bytes())
            .sum::<usize>();
        let draft = self.drafts.get_mut(&current).unwrap();
        let text_budget = MAX_TEXT_BYTES.min(
            MAX_DRAFT_BYTES
                .saturating_sub(other_bytes)
                .saturating_sub(
                    draft
                        .attachments
                        .iter()
                        .map(AttachmentRef::metadata_bytes)
                        .sum::<usize>(),
                )
                .saturating_sub(draft.transcript.as_ref().map_or(0, String::len)),
        );
        let keyboard_send = editable
            && flags.enter_key_sends
            && ui
                .ctx()
                .memory(|memory| memory.has_focus(ui.id().with(("composer", &current))))
            && ui.input_mut(|input| {
                !input.modifiers.shift
                    && !input.modifiers.ctrl
                    && !input.modifiers.alt
                    && !input.modifiers.command
                    && input.consume_key(egui::Modifiers::NONE, egui::Key::Enter)
            });
        let field = egui::TextEdit::multiline(&mut draft.text)
            .id_salt(("composer", &current))
            .desired_width(f32::INFINITY)
            .char_limit(MAX_TEXT_BYTES);
        let field = if let Some(chrome) = chrome.as_ref() {
            field
                .hint_text(
                    RichText::new(chrome.placeholder)
                        .font(chrome.skin.font(16.0))
                        .color(chrome.skin.ink),
                )
                .font(chrome.skin.font(16.0))
                .frame(egui::Frame::NONE)
                .margin(egui::Margin {
                    left: 20,
                    right: 20,
                    top: 14,
                    bottom: 4,
                })
                .desired_rows(1)
                .min_size(egui::vec2(0.0, 30.0))
        } else {
            field
                .hint_text("输入消息，也可以只发送图片")
                .desired_rows(3)
        };
        let response = ui.add_enabled(editable, field);
        if response.changed() {
            if draft.text.len() > text_budget {
                let mut end = text_budget;
                while !draft.text.is_char_boundary(end) {
                    end -= 1;
                }
                draft.text.truncate(end);
                draft.error = Some("正文达到草稿大小上限，超出部分未加入".into());
            }
            draft.mutate_text();
        }
        if draft.text.len() > MAX_TEXT_BYTES {
            ui.colored_label(ui.visuals().error_fg_color, "正文超过1 MiB，请缩短后提交");
        }
        if let Some(error) = &draft.error {
            ui.colored_label(ui.visuals().error_fg_color, error);
        }
        if let Some(notice) = &draft.notice {
            ui.label(RichText::new(notice).small().weak());
        }
        if let Some(transcript) = &draft.transcript {
            egui::CollapsingHeader::new("保留的转写结果")
                .id_salt("unapplied-asr")
                .show(ui, |ui| {
                    ui.add(egui::Label::new(transcript).wrap().selectable(true));
                    if ui.small_button("复制转写").clicked() {
                        ui.ctx().copy_text(transcript.clone());
                    }
                });
        }
        let mut remove = None;
        let mut preview = None;
        if !draft.attachments.is_empty() {
            egui::ScrollArea::horizontal()
                .id_salt(("composer-attachments", &current))
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        for attachment in &draft.attachments {
                            ui.push_id(&attachment.id, |ui| {
                                egui::Frame::group(ui.style()).show(ui, |ui| {
                                    ui.set_max_width(200.);
                                    if let Some(preview) = self.previews.get_mut(&attachment.id) {
                                        self.clock = self.clock.saturating_add(1);
                                        preview.touched = self.clock;
                                        let scale = (128. / preview.size.x.max(1.)).min(1.);
                                        ui.add(egui::Image::new((
                                            preview.texture.id(),
                                            preview.size * scale,
                                        )));
                                    } else if attachment.image
                                        && ui.small_button("读取预览").clicked()
                                    {
                                        preview = Some(attachment.id.clone());
                                    }
                                    ui.add(egui::Label::new(&attachment.display_name).wrap());
                                    ui.label(
                                        RichText::new(format!("{} 字节", attachment.bytes))
                                            .small()
                                            .weak(),
                                    );
                                    if ui
                                        .add_enabled(editable, egui::Button::new("移除").small())
                                        .clicked()
                                    {
                                        remove = Some(attachment.id.clone());
                                    }
                                });
                            });
                        }
                    });
                });
        }
        if let Some(id) = remove {
            self.remove_attachment(&id);
        }
        if let Some(id) = preview {
            match self.request_preview(&id) {
                Ok(intent) => intents.push(intent),
                Err(error) => self.drafts.get_mut(&current).unwrap().error = Some(error),
            }
        }
        let mut pick = None;
        let mut microphone = false;
        let mut finish = false;
        let mut transcribe = false;
        let mut cancel = false;
        let mut goal_requested = false;
        let picking = self.pending.values().any(|pending| {
            pending.kind == PendingKind::Picker && pending.operation.conversation_id == current
        });
        let toolbar = chrome.as_mut().map(|chrome| {
            let (full, _) = ui
                .allocate_exact_size(egui::vec2(ui.available_width(), 48.0), egui::Sense::hover());
            // Compose's row: 10dp on the left, 12dp on the right, 48dp tall.
            let row = egui::Rect::from_min_max(
                egui::pos2(full.left() + 10.0, full.top()),
                egui::pos2(full.right() - 12.0, full.bottom()),
            );
            let plus = egui::Rect::from_min_size(row.min, egui::Vec2::splat(36.0));
            let permissions = plus.translate(egui::vec2(36.0, 0.0));
            let response = chrome.icons.button(
                ui,
                plus,
                Icon::Plus,
                "添加附件",
                22.0,
                chrome.skin.ink,
                editable && flags.visible && !picking,
            );
            egui::Popup::menu(&response).show(|ui| {
                for (kind, label) in [
                    (AttachmentKind::Images, "图片"),
                    (AttachmentKind::File, "文件"),
                    (AttachmentKind::Camera, "拍照"),
                ] {
                    if ui
                        .add_enabled(
                            kind == AttachmentKind::File || flags.image_input,
                            egui::Button::new(label),
                        )
                        .clicked()
                    {
                        pick = Some(kind);
                        ui.close();
                    }
                }
                if !flags.running
                    && flags.configured
                    && flags.can_start_goal
                    && !self.text().trim().is_empty()
                    && self.text().len() <= 64 * 1024
                    && ui.button("启动持续目标").clicked()
                {
                    goal_requested = true;
                    ui.close();
                }
            });
            chrome.permissions = chrome
                .icons
                .button(
                    ui,
                    permissions,
                    Icon::Permissions,
                    "权限设置",
                    22.0,
                    chrome.skin.ink,
                    true,
                )
                .clicked();
            let submit = egui::Rect::from_min_size(
                egui::pos2(row.right() - 40.0, row.top()),
                egui::Vec2::splat(40.0),
            );
            // Compose: the context ring sits left of a 4dp gap and the mic, then the button.
            let mic_width = if flags.voice_input { 30.0 } else { 0.0 };
            let ring = egui::Rect::from_min_size(
                egui::pos2(submit.left() - 4.0 - mic_width - 26.0, row.center().y - 13.0),
                egui::Vec2::splat(26.0),
            );
            if flags.voice_input && self.drafts[&current].voice.is_none() {
                let mic = egui::Rect::from_min_size(
                    egui::pos2(ring.right() + 4.0, row.center().y - 15.0),
                    egui::Vec2::splat(30.0),
                );
                microphone = chrome
                    .icons
                    .button(
                        ui,
                        mic,
                        Icon::Mic,
                        "语音输入",
                        18.0,
                        chrome.skin.ink,
                        editable && flags.visible,
                    )
                    .clicked();
            }
            chrome.model = Some(egui::Rect::from_min_max(
                egui::pos2(permissions.right(), row.top()),
                egui::pos2(ring.left(), row.bottom()),
            ));
            chrome.context = Some(ring);
            submit
        });
        if toolbar.is_none() || picking || self.drafts[&current].voice.is_some() {
            ui.horizontal_wrapped(|ui| {
                for (kind, label) in [
                    (AttachmentKind::Images, "图片"),
                    (AttachmentKind::File, "文件"),
                    (AttachmentKind::Camera, "拍照"),
                ] {
                    if toolbar.is_none()
                        && ui
                            .add_enabled(
                                editable
                                    && flags.visible
                                    && !picking
                                    && (kind == AttachmentKind::File || flags.image_input),
                                egui::Button::new(label),
                            )
                            .clicked()
                    {
                        pick = Some(kind);
                    }
                }
                if picking {
                    ui.spinner();
                    ui.label("读取附件中");
                }
                let voice = self.drafts[&current].voice.as_ref();
                if let Some(voice) = voice {
                    match voice.phase {
                        VoicePhase::Permission => {
                            ui.spinner();
                            ui.label("等待麦克风权限");
                        }
                        VoicePhase::Starting => {
                            ui.spinner();
                            ui.label("正在启动录音");
                        }
                        VoicePhase::Recording => {
                            ui.label(format!("录音 {:.1} 秒", voice.elapsed_ms as f64 / 1000.));
                            ui.add(egui::ProgressBar::new(voice.level).desired_width(72.));
                            if ui.button("完成并转写").clicked() {
                                finish = true;
                            }
                        }
                        VoicePhase::Finishing => {
                            ui.spinner();
                            ui.label(if voice.cancel_requested {
                                "正在取消语音输入"
                            } else {
                                "正在结束录音"
                            });
                        }
                        VoicePhase::Recorded => {
                            ui.label(format!(
                                "录音完成 · {:.1} 秒",
                                voice.elapsed_ms as f64 / 1000.
                            ));
                            if ui.button("开始转写").clicked() {
                                transcribe = true;
                            }
                        }
                        VoicePhase::Transcribing => {
                            ui.spinner();
                            ui.label("正在转写");
                        }
                    }
                    if ui
                        .add_enabled(!voice.cancel_requested, egui::Button::new("取消语音输入"))
                        .clicked()
                    {
                        cancel = true;
                    }
                } else if toolbar.is_none()
                    && ui
                        .add_enabled(
                            editable && flags.visible && flags.voice_input,
                            egui::Button::new("语音输入"),
                        )
                        .clicked()
                {
                    microphone = true;
                }
            });
        }
        if let Some(kind) = pick {
            match self.pick(kind) {
                Ok(intent) => intents.push(intent),
                Err(error) => self.drafts.get_mut(&current).unwrap().error = Some(error),
            }
        }
        if microphone {
            match self.start_microphone() {
                Ok(intent) => intents.push(intent),
                Err(error) => self.drafts.get_mut(&current).unwrap().error = Some(error),
            }
        }
        if finish {
            if let Some(intent) = self.finish_recording() {
                intents.push(intent);
            }
        }
        if transcribe {
            match self.transcribe_for(&current) {
                Ok(intent) => intents.push(intent),
                Err(error) => self.drafts.get_mut(&current).unwrap().error = Some(error),
            }
        }
        if cancel {
            if let Some(intent) = self.cancel_voice() {
                intents.push(intent);
            }
        }
        let can_send = editable
            && self.has_content()
            && self.drafts[&current].voice.is_none()
            && !self.pending.values().any(|pending| {
                pending.kind == PendingKind::Picker && pending.operation.conversation_id == current
            })
            && !self
                .submitted
                .values()
                .any(|submission| submission.operation.conversation_id == current);
        let mut send = if keyboard_send && can_send {
            if flags.running && flags.configured && current.is_some() && !flags.cancelling {
                Some(if flags.immediately {
                    SubmitMode::Steer
                } else {
                    SubmitMode::Queue
                })
            } else if !flags.running
                && (flags.configured || crate::goal::slash_argument(self.text()).is_some())
            {
                Some(SubmitMode::Send)
            } else {
                None
            }
        } else {
            None
        };
        if let (Some(chrome), Some(button)) = (chrome.as_mut(), toolbar) {
            let can_stop = flags.running && !self.has_content() && !flags.cancelling;
            let mode = if flags.running {
                if flags.immediately {
                    SubmitMode::Steer
                } else {
                    SubmitMode::Queue
                }
            } else {
                SubmitMode::Send
            };
            let allowed = can_send
                && if flags.running {
                    flags.configured
                        && current.is_some()
                        && (!flags.immediately || !flags.cancelling)
                } else {
                    flags.configured || crate::goal::slash_argument(self.text()).is_some()
                };
            let active = allowed || can_stop;
            let disc = button.shrink(2.0);
            let fill = if active {
                chrome.skin.accent
            } else {
                mix(chrome.skin.input, chrome.skin.ink, 0.10)
            };
            ui.painter()
                .circle_filled(disc.center(), disc.width() * 0.5, fill);
            let response = chrome.icons.button(
                ui,
                button,
                if can_stop { Icon::Stop } else { Icon::Send },
                if can_stop { "停止生成" } else { "发送" },
                22.0,
                if active {
                    chrome.skin.surface
                } else {
                    chrome.skin.ink
                },
                active,
            );
            if response.clicked() {
                if can_stop {
                    chrome.stop = true;
                } else {
                    send = Some(mode);
                }
            }
            if goal_requested && can_send {
                send = Some(SubmitMode::Goal);
            }
        } else {
            ui.horizontal_wrapped(|ui| {
                if flags.running {
                    let mode = if flags.immediately {
                        SubmitMode::Steer
                    } else {
                        SubmitMode::Queue
                    };
                    let label = if flags.immediately {
                        "立即插话"
                    } else {
                        "排到下一轮"
                    };
                    if ui
                        .add_enabled(
                            can_send
                                && flags.configured
                                && current.is_some()
                                && (!flags.immediately || !flags.cancelling),
                            egui::Button::new(label),
                        )
                        .clicked()
                    {
                        send = Some(mode);
                    }
                } else {
                    if ui
                        .add_enabled(
                            can_send
                                && (flags.configured
                                    || crate::goal::slash_argument(self.text()).is_some()),
                            egui::Button::new("发送"),
                        )
                        .clicked()
                    {
                        send = Some(SubmitMode::Send);
                    }
                    if ui
                        .add_enabled(
                            can_send
                                && flags.configured
                                && flags.can_start_goal
                                && !self.text().trim().is_empty()
                                && self.text().len() <= 64 * 1024,
                            egui::Button::new("启动持续目标"),
                        )
                        .clicked()
                    {
                        send = Some(SubmitMode::Goal);
                    }
                }
                if self
                    .submitted
                    .values()
                    .any(|value| value.operation.conversation_id == current)
                {
                    ui.spinner();
                    ui.label("等待接收确认");
                }
            });
        }
        if let Some(mode) = send {
            let expected = if mode == SubmitMode::Steer {
                flags.expected_run.clone()
            } else {
                None
            };
            match self.prepare_submit(mode, expected) {
                Ok(intent) => intents.push(intent),
                Err(error) => self.drafts.get_mut(&current).unwrap().error = Some(error),
            }
        }
        self.show_speech(ui, &mut intents, None);
        (intents, chrome)
    }
    pub fn reply_audio_controls(
        &mut self,
        ui: &mut egui::Ui,
        source: &crate::display::ViewSource,
    ) -> Vec<ComposerIntent> {
        let mut intents = Vec::new();
        self.show_speech(ui, &mut intents, Some(source));
        intents
    }
    fn show_speech(
        &mut self,
        ui: &mut egui::Ui,
        intents: &mut Vec<ComposerIntent>,
        source: Option<&crate::display::ViewSource>,
    ) {
        let mut stop = None;
        for speech in self.speech.values().filter(|speech| match source {
            Some(source) => &speech.body.source == source,
            None => {
                speech.operation.conversation_id == self.selected
                    && matches!(speech.body.source, crate::display::ViewSource::Main { .. })
            }
        }) {
            let label = match &speech.phase {
                SpeechPhase::RequestingService => "正在准备回答朗读",
                SpeechPhase::ServiceReady => "前台音频已就绪",
                SpeechPhase::Speaking => "正在朗读回答",
                SpeechPhase::WaitingForCleanup => "正在停止朗读，等待系统清理确认",
                SpeechPhase::Finished => "朗读已结束",
                SpeechPhase::Failed(error) => error,
            };
            ui.horizontal_wrapped(|ui| {
                ui.label(label);
                if !matches!(speech.phase, SpeechPhase::Finished | SpeechPhase::Failed(_))
                    && ui.button("停止朗读").clicked()
                {
                    stop = Some(speech.operation.clone());
                }
            });
        }
        if let Some(operation) = stop {
            intents.push(ComposerIntent::Command(ComposerCommand::StopReplyAudio(
                operation,
            )));
        }
    }
    pub fn disconnected(&mut self) {
        self.pending.clear();
        self.submitted.clear();
        self.preview_requests.clear();
        for draft in self.drafts.values_mut() {
            draft.voice = None;
            draft.error = Some("运行时连接已断开；草稿和附件已保留".into());
        }
        for speech in self.speech.values_mut() {
            speech.phase = SpeechPhase::Failed("运行时连接已断开，朗读状态未确认".into());
        }
    }
}
fn bounded(text: &str, limit: usize) -> String {
    let mut end = text.len().min(limit);
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    text[..end].into()
}
