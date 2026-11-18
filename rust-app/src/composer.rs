//! Shared composer payload. The owning actor reopens every referenced
//! inbox file and verifies the fingerprint before admitting a message or queue write.
use crate::platform::{attachments::AttachmentDraft, permissions::OperationKey};
use serde::{Deserialize, Serialize};
use std::{
    path::{Component, PathBuf},
    sync::Arc,
};
pub mod image_format;
pub mod metadata;
pub mod references;
pub mod slash;
pub mod transient;

pub const MAX_ATTACHMENTS: usize = 256;
pub const MAX_ATTACHMENT_BYTES: u64 = 256 * 1024 * 1024;
pub const MAX_IMAGE_WIRE_BYTES: usize = 16 * 1024 * 1024;
pub const MAX_TEXT_BYTES: usize = 1024 * 1024;
/// The existing archive accepts 20 MiB per Message. Reserve two MiB for
/// actor-added message fields/attachment path notes, and count escaped JSON.
pub const MAX_INPUT_JSON_BYTES: usize = 18 * 1024 * 1024;
pub const MAX_ATTACHMENT_METADATA_JSON: usize = 2 * 1024 * 1024;
pub const MAX_PREVIEW_BATCH_BYTES: usize = 2 * 1024 * 1024;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AttachmentRef {
    pub id: String,
    pub display_name: String,
    pub relative_path: PathBuf,
    pub sha256: String,
    pub bytes: u64,
    pub mime_type: String,
    pub image: bool,
    /// Only a budget hint; the actor recomputes this from verified JPEG bytes.
    #[serde(default)]
    pub image_wire_bytes: usize,
}
impl AttachmentRef {
    pub fn from_draft(draft: &AttachmentDraft) -> Result<Self, String> {
        let result = Self {
            id: draft.id.clone(),
            display_name: draft.display_name.clone(),
            relative_path: draft.relative_path.clone(),
            sha256: draft.sha256.clone(),
            bytes: draft.bytes,
            mime_type: draft.mime_type.clone(),
            image: draft.image_base64.is_some(),
            image_wire_bytes: draft.image_base64.as_ref().map_or(0, String::len),
        };
        result.validate()?;
        Ok(result)
    }
    pub fn validate(&self) -> Result<(), String> {
        let normal_inbox = self
            .relative_path
            .components()
            .all(|component| matches!(component, Component::Normal(_)))
            && self
                .relative_path
                .components()
                .next()
                .is_some_and(|component| component.as_os_str() == "inbox")
            && self.relative_path.components().nth(1).is_some();
        if self.id.is_empty()
            || self.id.len() > 128
            || self.id.contains('\0')
            || self.display_name.len() > 1024
            || self.display_name.contains('\0')
            || self.relative_path.as_os_str().len() > 4096
            || !normal_inbox
            || (self.image && self.bytes == 0)
            || self.bytes > 128 * 1024 * 1024
            || self.sha256.len() != 64
            || !self.sha256.bytes().all(|byte| byte.is_ascii_hexdigit())
            || self.mime_type.len() > 256
            || self.mime_type.contains(['\r', '\n', '\0'])
            || self.image_wire_bytes > 16 * 1024 * 1024
            || (self.image && (self.mime_type != "image/jpeg" || self.image_wire_bytes == 0))
            || (!self.image && self.image_wire_bytes != 0)
        {
            return Err("附件引用无效或超过预算".into());
        }
        Ok(())
    }
    pub fn metadata_bytes(&self) -> usize {
        256 + self.id.len()
            + self.display_name.len()
            + self.relative_path.as_os_str().len()
            + self.sha256.len()
            + self.mime_type.len()
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SubmitMode {
    Send,
    Queue,
    Steer,
    /// Explicit goal submission preserves the same attachment envelope.
    Goal,
}
#[derive(Clone)]
pub struct Submission {
    pub operation: OperationKey,
    pub draft_revision: u64,
    pub expected_run: Option<crate::runtime_host::RunKey>,
    pub text: String,
    pub attachments: Vec<AttachmentRef>,
    pub mode: SubmitMode,
}
impl std::fmt::Debug for Submission {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Submission")
            .field("operation", &self.operation)
            .field("expected_run", &self.expected_run)
            .field("draft_revision", &self.draft_revision)
            .field("text_bytes", &self.text.len())
            .field("attachments", &self.attachments.len())
            .field("mode", &self.mode)
            .finish_non_exhaustive()
    }
}
impl Submission {
    pub fn starts_run(&self) -> bool {
        match self.mode {
            SubmitMode::Send => slash::starts_run(&self.text),
            SubmitMode::Goal => true,
            SubmitMode::Queue | SubmitMode::Steer => false,
        }
    }
    pub fn validate(&self) -> Result<(), String> {
        self.operation.validate()?;
        if self.mode == SubmitMode::Steer {
            let expected = self
                .expected_run
                .as_ref()
                .ok_or("立即插话缺少点击时的实际运行身份")?;
            expected.validate()?;
            if self.operation.conversation_id.as_deref() != Some(expected.conversation_id.as_str())
            {
                return Err("立即插话运行与原草稿会话不一致".into());
            }
        } else if self.expected_run.is_some() {
            return Err("非插话提交不能携带运行归属".into());
        }
        if self.draft_revision == 0
            || self.text.len() > MAX_TEXT_BYTES
            || self.text.contains('\0')
            || self.attachments.len() > MAX_ATTACHMENTS
            || (self.text.trim().is_empty() && self.attachments.is_empty())
            || (self.mode != SubmitMode::Send
                && self.operation.conversation_id.is_none()
                && self.mode != SubmitMode::Goal)
            || (self.mode == SubmitMode::Goal
                && (self.text.trim().is_empty() || self.text.len() > 64 * 1024))
        {
            return Err("提交草稿为空、身份无效或超过预算".into());
        }
        if self.mode != SubmitMode::Goal
            && !self.attachments.is_empty()
            && slash::parse(&self.text).is_some()
        {
            return Err("斜杠指令不能带附件；请将文件引用写入指令参数".into());
        }
        validate_refs(&self.attachments)?;
        input_json_bytes(&self.text, &self.attachments)?;
        Ok(())
    }
}
/// Apply the same aggregate limits at submission, metadata, picker and queue boundaries.
pub fn validate_refs(refs: &[AttachmentRef]) -> Result<(), String> {
    if refs.len() > MAX_ATTACHMENTS {
        return Err("附件数量超过256".into());
    }
    let mut bytes = 0u64;
    let mut image_bytes = 0usize;
    let mut ids = std::collections::HashSet::new();
    for attachment in refs {
        attachment.validate()?;
        if !ids.insert(&attachment.id) {
            return Err("附件身份重复".into());
        }
        bytes = bytes.checked_add(attachment.bytes).ok_or("附件长度溢出")?;
        image_bytes = image_bytes
            .checked_add(attachment.image_wire_bytes)
            .ok_or("图片长度溢出")?;
    }
    if bytes > MAX_ATTACHMENT_BYTES || image_bytes > MAX_IMAGE_WIRE_BYTES {
        return Err("附件总量超过256 MiB或图片编码超过16 MiB".into());
    }
    json_bytes(refs, MAX_ATTACHMENT_METADATA_JSON)?;
    Ok(())
}
pub fn json_bytes<T: Serialize + ?Sized>(value: &T, limit: usize) -> Result<usize, String> {
    struct Counter {
        count: usize,
        limit: usize,
    }
    impl std::io::Write for Counter {
        fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
            let next = self
                .count
                .checked_add(bytes.len())
                .filter(|count| *count <= self.limit)
                .ok_or_else(|| std::io::Error::other("输入JSON预算"))?;
            self.count = next;
            Ok(bytes.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
    let mut counter = Counter { count: 0, limit };
    serde_json::to_writer(&mut counter, value).map_err(|_| "附件和正文的JSON超过预算")?;
    Ok(counter.count)
}
pub fn input_json_bytes(text: &str, attachments: &[AttachmentRef]) -> Result<usize, String> {
    #[derive(Serialize)]
    struct Fields<'a> {
        text: &'a str,
        attachments: &'a [AttachmentRef],
    }
    let fields = json_bytes(&Fields { text, attachments }, MAX_INPUT_JSON_BYTES)?;
    let images = attachments
        .iter()
        .try_fold(0usize, |count, attachment| {
            count.checked_add(attachment.image_wire_bytes)
        })
        .ok_or("图片编码长度溢出")?;
    let total = fields
        .checked_add(images)
        .and_then(|count| count.checked_add(attachments.len() * 8 + 64 * 1024))
        .ok_or("输入长度溢出")?;
    if total > MAX_INPUT_JSON_BYTES {
        return Err("图片、正文和附件说明超过18 MiB存储输入预算".into());
    }
    Ok(total)
}
/// The actor retains the WAV Arc and issues one opaque token bound to the
/// recording's exact OperationKey + origin_text_revision. No audio bytes enter UI.
#[derive(Clone, Debug)]
pub struct RecordedAudio {
    pub token: String,
    pub duration_ms: u64,
    pub samples: u64,
}
#[derive(Clone, Debug)]
pub struct AttachmentPreview {
    pub attachment_id: String,
    pub pixels: Arc<crate::platform::attachments::PixelFrame>,
}
#[derive(Clone, Debug)]
pub struct AttachmentBatch {
    pub attachments: Vec<AttachmentRef>,
    pub previews: Vec<AttachmentPreview>,
}
impl AttachmentBatch {
    pub fn validate(&self) -> Result<(), String> {
        validate_refs(&self.attachments)?;
        if self.attachments.len() > MAX_ATTACHMENTS
            || self.previews.len() > 8
            || self
                .attachments
                .iter()
                .map(AttachmentRef::metadata_bytes)
                .sum::<usize>()
                > MAX_ATTACHMENT_METADATA_JSON
        {
            return Err("附件结果超过发布预算".into());
        }
        json_bytes(&self.attachments, MAX_ATTACHMENT_METADATA_JSON)?;
        let mut preview_bytes = 0usize;
        let mut seen = std::collections::HashSet::new();
        for preview in &self.previews {
            let pixels = &preview.pixels;
            let expected = (pixels.width as usize)
                .checked_mul(pixels.height as usize)
                .and_then(|n| n.checked_mul(4));
            if pixels.width == 0
                || pixels.height == 0
                || pixels.width > 320
                || pixels.height > 320
                || expected != Some(pixels.rgba.len())
                || !seen.insert(&preview.attachment_id)
                || !self
                    .attachments
                    .iter()
                    .any(|attachment| attachment.id == preview.attachment_id && attachment.image)
            {
                return Err("附件预览身份、尺寸或像素长度无效".into());
            }
            preview_bytes = preview_bytes
                .checked_add(pixels.rgba.len())
                .ok_or("预览长度溢出")?;
        }
        if preview_bytes > MAX_PREVIEW_BATCH_BYTES {
            return Err("预览批次超过2 MiB发布预算".into());
        }
        for attachment in &self.attachments {
            attachment.validate()?;
        }
        Ok(())
    }
}

#[derive(Debug)]
pub enum ComposerCommand {
    PickAttachment {
        operation: OperationKey,
        kind: crate::platform::attachments::AttachmentKind,
    },
    CancelPlatformOperation(OperationKey),
    PreviewAttachment {
        operation: OperationKey,
        attachment: AttachmentRef,
    },
    /// Actor checks current saved ASR config/credentials before launching recording.
    StartRecording {
        operation: OperationKey,
        origin_text_revision: u64,
    },
    FinishRecording(OperationKey),
    /// Use a fresh operation ID after RecordingFinished was drained.
    TranscribeRecording {
        operation: OperationKey,
        audio: RecordedAudio,
        origin_text_revision: u64,
    },
    Submit(Submission),
    SpeakReply {
        operation: OperationKey,
        body: crate::display::BodyRef,
    },
    TestReplyAudio(OperationKey),
    StopReplyAudio(OperationKey),
}
impl ComposerCommand {
    pub fn operation(&self) -> &OperationKey {
        match self {
            Self::PickAttachment { operation, .. }
            | Self::PreviewAttachment { operation, .. }
            | Self::StartRecording { operation, .. }
            | Self::TranscribeRecording { operation, .. }
            | Self::SpeakReply { operation, .. } => operation,
            Self::CancelPlatformOperation(operation)
            | Self::FinishRecording(operation)
            | Self::TestReplyAudio(operation)
            | Self::StopReplyAudio(operation) => operation,
            Self::Submit(submission) => &submission.operation,
        }
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SpeechPhase {
    RequestingService,
    /// The actor emits this only after an exact SpeechServiceEvent::Ready whose
    /// guard is still true. UI status is never a substitute for that guard.
    ServiceReady,
    Speaking,
    WaitingForCleanup,
    Finished,
    Failed(String),
}
#[derive(Clone, Debug)]
pub enum ComposerEvent {
    /// Actor validates and strips AttachmentDraft.image_base64 before emitting.
    /// This payload: refs JSON <=2 MiB + pixels <=2 MiB + structure <=64 KiB.
    Attachment {
        operation: OperationKey,
        result: Result<Arc<AttachmentBatch>, String>,
        cancelled: bool,
    },
    AttachmentPreview {
        operation: OperationKey,
        attachment_id: String,
        pixels: Result<Arc<crate::platform::attachments::PixelFrame>, String>,
    },
    RecordingStatus(crate::platform::voice::RecordingStatus),
    RecordingFinished {
        operation: OperationKey,
        origin_text_revision: u64,
        result: Result<RecordedAudio, String>,
    },
    TranscriptionFinished {
        operation: OperationKey,
        origin_text_revision: u64,
        result: Result<String, String>,
    },
    Accepted {
        operation: OperationKey,
        conversation_id: String,
        turn_id: String,
    },
    /// Read/clear-goal acknowledgement has no invented conversation or turn.
    CommandFinished {
        operation: OperationKey,
        result: Result<(), String>,
    },
    Rejected {
        operation: OperationKey,
        error: String,
    },
    /// Terminal acknowledgement; sending a cancellation request is separate.
    Cancelled {
        operation: OperationKey,
    },
    Speech {
        operation: OperationKey,
        phase: SpeechPhase,
    },
}
impl ComposerEvent {
    /// Owning actor calls this BEFORE putting an event into its UI mailbox.
    /// The weight includes bounded payload and structural allowance. Pixels and
    /// WAVs must not be smuggled through an unweighted platform result wrapper.
    pub fn output_weight(&self) -> Result<usize, String> {
        let error_weight = |error: &str| {
            if error.len() > 2048 || error.contains('\0') {
                Err("平台错误正文超过发布预算".to_string())
            } else {
                Ok(error.len())
            }
        };
        let (operation, extra) = match self {
            Self::Attachment {
                operation, result, ..
            } => {
                let extra = match result {
                    Ok(batch) => {
                        batch.validate()?;
                        json_bytes(&batch.attachments, MAX_ATTACHMENT_METADATA_JSON)?
                            + batch
                                .previews
                                .iter()
                                .map(|preview| preview.pixels.rgba.len())
                                .sum::<usize>()
                            + 63 * 1024
                    }
                    Err(error) => error_weight(error)?,
                };
                (operation, extra)
            }
            Self::AttachmentPreview {
                operation,
                attachment_id,
                pixels,
            } => {
                if attachment_id.is_empty() || attachment_id.len() > 128 {
                    return Err("预览身份无效".into());
                }
                let extra = match pixels {
                    Ok(pixels) => {
                        let expected = (pixels.width as usize)
                            .checked_mul(pixels.height as usize)
                            .and_then(|count| count.checked_mul(4));
                        if pixels.width == 0
                            || pixels.height == 0
                            || pixels.width > 320
                            || pixels.height > 320
                            || expected != Some(pixels.rgba.len())
                        {
                            return Err("预览像素超过发布预算".into());
                        }
                        pixels.rgba.len() + 1024
                    }
                    Err(error) => error_weight(error)?,
                };
                (operation, extra)
            }
            Self::RecordingStatus(status) => (&status.operation, 256),
            Self::RecordingFinished {
                operation,
                origin_text_revision,
                result,
            } => {
                if *origin_text_revision == 0 {
                    return Err("录音草稿文本代数无效".into());
                }
                let extra = match result {
                    Ok(audio) => {
                        if audio.token.is_empty()
                            || audio.token.len() > 128
                            || audio.token.contains('\0')
                            || audio.samples == 0
                            || audio.samples > 960_000
                            || audio.duration_ms != audio.samples * 1000 / 16_000
                        {
                            return Err("录音票据无效".into());
                        }
                        256
                    }
                    Err(error) => error_weight(error)?,
                };
                (operation, extra)
            }
            Self::TranscriptionFinished {
                operation,
                origin_text_revision,
                result,
            } => {
                if *origin_text_revision == 0 {
                    return Err("转写草稿文本代数无效".into());
                }
                let extra = match result {
                    Ok(text) if text.len() <= 64 * 1024 && !text.contains('\0') => text.len(),
                    Ok(_) => return Err("转写正文超过64 KiB发布预算".into()),
                    Err(error) => error_weight(error)?,
                };
                (operation, extra)
            }
            Self::Accepted {
                operation,
                conversation_id,
                turn_id,
            } => {
                if [conversation_id, turn_id]
                    .iter()
                    .any(|id| id.is_empty() || id.len() > 128 || id.contains('\0'))
                {
                    return Err("接收确认身份无效".into());
                }
                (operation, 256)
            }
            Self::Rejected { operation, error } => (operation, error_weight(error)?),
            Self::CommandFinished { operation, result } => (
                operation,
                match result {
                    Ok(()) => 128,
                    Err(error) => error_weight(error)?,
                },
            ),
            Self::Cancelled { operation } => (operation, 128),
            Self::Speech { operation, phase } => (
                operation,
                match phase {
                    SpeechPhase::Failed(error) => error_weight(error)?,
                    _ => 128,
                },
            ),
        };
        operation.validate()?;
        Ok(extra + 1024)
    }
}
