//! Draft for the owning actor. Never drain the global platform queues here:
//! ComposerRuntime and model tools must route the same events in one actor.
//! A shared AudioGate must also cover composer readback before integration.
use super::session_tools::{Caller, id};
use crate::{
    platform::{permissions::OperationKey, speech_service, voice},
    state::ToolCall,
};
use serde_json::{Value, json};
use std::{
    collections::{BTreeSet, VecDeque},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};
use suixing_search::rust_protocol::ToolDefinition;

const SERVICE_WAIT: Duration = Duration::from_secs(15);
const SPEECH_WAIT: Duration = Duration::from_secs(120);
const STOP_WAIT: Duration = Duration::from_secs(15);
const MAX_UPDATES: usize = 16;
const MAX_OPERATION_IDENTITIES: usize = 128;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Binding {
    pub caller: Caller,
    pub call_id: String,
    pub operation: OperationKey,
}
impl Binding {
    fn validate(&self) -> Result<(), String> {
        self.caller.validate()?;
        id(&self.call_id)?;
        self.operation.validate()?;
        if self.operation.conversation_id.as_deref() != Some(&self.caller.run.conversation_id) {
            return Err("语音操作不属于实际调用会话".into());
        }
        Ok(())
    }
}

/// The host supplies its real parent/child flag and an authoritative Publisher
/// validator. Keep both until SpeechFinished; returning a tool result does not
/// retire the underlying run scope. No weak cancellation token is invented here.
#[derive(Clone)]
pub struct Scope {
    pub binding: Binding,
    pub cancelled: Arc<AtomicBool>,
    pub live: Arc<dyn Fn(&Caller) -> bool + Send + Sync>,
}
impl Scope {
    fn valid(&self) -> bool {
        !self.cancelled.load(Ordering::Acquire) && (self.live)(&self.binding.caller)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AudioOwner {
    Composer(OperationKey),
    Model(Binding),
}
#[derive(Clone, Default)]
pub struct AudioGate(Arc<Mutex<Option<AudioOwner>>>);
impl AudioGate {
    pub fn acquire(&self, owner: AudioOwner) -> Result<AudioPermit, String> {
        let mut active = self.0.lock().unwrap_or_else(|error| error.into_inner());
        if active.is_some() {
            return Err("已有回答/模型朗读正在使用或释放音频".into());
        }
        *active = Some(owner.clone());
        Ok(AudioPermit {
            gate: self.clone(),
            owner,
            cleanup_unknown: false,
        })
    }
    pub fn owner(&self) -> Option<AudioOwner> {
        self.0
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .clone()
    }
}
pub struct AudioPermit {
    gate: AudioGate,
    owner: AudioOwner,
    // An admitted platform task can outlive a caller timeout or Actor drop.
    // Only an exact typed cleanup confirmation clears this flag. On unknown
    // cleanup Drop preserves the gate owner to prevent unsafe engine reuse.
    pub(crate) cleanup_unknown: bool,
}
impl AudioPermit {
    pub(crate) fn current(&self) -> bool {
        self.gate
            .0
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .as_ref()
            == Some(&self.owner)
    }
}
impl Drop for AudioPermit {
    fn drop(&mut self) {
        let mut active = self
            .gate
            .0
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if !self.cleanup_unknown && active.as_ref() == Some(&self.owner) {
            *active = None;
        }
    }
}

#[derive(Clone, Debug)]
pub enum Update {
    Phase {
        binding: Binding,
        phase: Phase,
    },
    Finished {
        binding: Binding,
        result: Result<Value, String>,
    },
}
#[derive(Clone, Copy, Debug)]
pub enum Phase {
    WaitingForSpeechService,
    ServiceReady,
    SubmittedToSpeechEngine,
    StopRequested,
    WaitingForCleanup,
}
struct Speech {
    scope: Scope,
    text: Option<String>,
    lease: speech_service::Lease,
    permit: AudioPermit,
    requested: Instant,
    submitted_at: Option<Instant>,
    speaker: Option<voice::SpeakerKey>,
    cleanup_requested: Option<Instant>,
    reported: bool,
}
struct Stop {
    scope: Scope,
    target: Binding,
    expected: voice::SpeakerKey,
    requested: Instant,
    reported: bool,
}
pub struct Actor {
    gate: AudioGate,
    opted_in: bool,
    speech: Option<Speech>,
    stop: Option<Stop>,
    updates: VecDeque<Update>,
    /// Fail closed when full. Recreate only at a new host operation epoch whose
    /// events cannot alias these identities; do not evict to allow stale reuse.
    used: BTreeSet<(String, u64)>,
}
impl Actor {
    pub fn new(gate: AudioGate, opted_in: bool) -> Self {
        Self {
            gate,
            opted_in,
            speech: None,
            stop: None,
            updates: VecDeque::new(),
            used: BTreeSet::new(),
        }
    }
    /// Call when the actual preference changes; the next actor tick cancels any
    /// active model speech. The caller cannot opt itself in through tool args.
    pub fn set_opted_in(&mut self, enabled: bool) {
        self.opted_in = enabled;
    }
    pub fn drain_updates(&mut self, max: usize) -> Vec<Update> {
        self.updates
            .drain(..max.min(8).min(self.updates.len()))
            .collect()
    }
    pub fn identity_budget_available(&self) -> bool {
        self.used.len() < MAX_OPERATION_IDENTITIES
    }
    pub fn busy(&self) -> bool {
        self.speech.is_some()
    }
    fn admit(&mut self, scope: &Scope, call: &ToolCall) -> Result<(), String> {
        scope.binding.validate()?;
        if scope.binding.call_id != call.id || !scope.valid() {
            return Err("语音调用已取消或调用身份不一致".into());
        }
        if self.updates.len() > MAX_UPDATES - 8 || call.arguments.len() > 4096 {
            return Err("语音待取结果或参数超过预算".into());
        }
        let identity = (
            scope.binding.operation.id.clone(),
            scope.binding.operation.generation,
        );
        if self.used.contains(&identity) || self.used.len() >= MAX_OPERATION_IDENTITIES {
            return Err("语音操作身份重复或本宿主epoch预算已耗尽".into());
        }
        self.used.insert(identity);
        Ok(())
    }
    pub fn speak(&mut self, scope: Scope, call: &ToolCall) -> Result<(), String> {
        self.admit(&scope, call)?;
        if call.name != "speak" || !self.opted_in || self.speech.is_some() {
            return Err("未知朗读工具、用户未启用或当前朗读尚未释放".into());
        }
        let args: Value = serde_json::from_str(&call.arguments).map_err(|_| "语音参数不是JSON")?;
        let args = args.as_object().ok_or("语音参数必须是对象")?;
        if args.len() != 1 || !args.contains_key("text") {
            return Err("speak只接受text参数".into());
        }
        let text = args
            .get("text")
            .and_then(Value::as_str)
            .ok_or("text必须是字符串")?
            .trim();
        if text.is_empty() || text.len() > 2000 || text.contains('\0') || text.chars().count() > 500
        {
            return Err("朗读文本为空、含NUL或超过500字符/2000字节".into());
        }
        let permit = self
            .gate
            .acquire(AudioOwner::Model(scope.binding.clone()))?;
        if !scope.valid() {
            return Err("朗读在服务申请前已取消".into());
        }
        let lease = speech_service::request(scope.binding.operation.clone(), self.opted_in)?;
        let binding = scope.binding.clone();
        self.speech = Some(Speech {
            scope,
            text: Some(text.into()),
            lease,
            permit,
            requested: Instant::now(),
            submitted_at: None,
            speaker: None,
            cleanup_requested: None,
            reported: false,
        });
        self.phase(binding, Phase::WaitingForSpeechService);
        Ok(())
    }
    pub fn stop_speaking(&mut self, scope: Scope, call: &ToolCall) -> Result<(), String> {
        self.admit(&scope, call)?;
        if call.name != "stop_speaking" || self.stop.is_some() {
            return Err("未知停止工具或已有停止请求等待回执".into());
        }
        let args: Value = serde_json::from_str(&call.arguments).map_err(|_| "停止参数不是JSON")?;
        if !args.as_object().is_some_and(|args| args.is_empty()) {
            return Err("stop_speaking不接受参数".into());
        }
        let Some(speech) = self.speech.as_ref() else {
            self.finish(
                scope.binding,
                Ok(json!({"outcome":"no_owned_speech","stop_requested":false})),
            );
            return Ok(());
        };
        if speech.scope.binding.caller != scope.binding.caller || !speech.permit.current() {
            return Err("当前音频不属于此次父/子调用；未停止其他朗读".into());
        }
        if speech.submitted_at.is_none() {
            let binding = scope.binding.clone();
            let target = speech.scope.binding.operation.clone();
            self.cancel_speech("模型撤回尚未提交系统的朗读".into());
            self.finish(binding, Ok(json!({"outcome":"retracted_before_tts_submission","target_operation":target.id,"system_stop_requested":false})));
            return Ok(());
        }
        let expected = speech
            .speaker
            .clone()
            .ok_or("已提交朗读缺少平台speaker key")?;
        voice::stop_request(
            scope.binding.operation.clone(),
            expected.clone(),
            scope.cancelled.clone(),
        )?;
        let binding = scope.binding.clone();
        self.stop = Some(Stop {
            scope,
            target: speech.scope.binding.clone(),
            expected,
            requested: Instant::now(),
            reported: false,
        });
        self.phase(binding, Phase::StopRequested);
        let speech = self.speech.as_mut().expect("submitted speech");
        speech.cleanup_requested = Some(Instant::now());
        if !speech.reported {
            speech.reported = true;
            let target = speech.scope.binding.clone();
            self.finish(
                target,
                Err("模型请求停止当前朗读，正在等待精确系统回执".into()),
            );
        }
        Ok(())
    }
    /// Called by the single platform-event owner. Returns whether this model
    /// actor consumed the event; unowned events remain available to the composer.
    pub fn service_event(&mut self, event: &speech_service::SpeechServiceEvent) -> bool {
        let Some(speech) = self.speech.as_ref() else {
            return false;
        };
        match event {
            speech_service::SpeechServiceEvent::Ready { operation, guard } => {
                if operation != &speech.scope.binding.operation {
                    return false;
                }
                if speech.submitted_at.is_some() || speech.reported {
                    return true;
                }
                if !Arc::ptr_eq(guard, &speech.lease.guard())
                    || !guard.load(Ordering::Acquire)
                    || !speech.lease.confirmed()
                    || !speech.scope.valid()
                    || !self.opted_in
                    || !speech.permit.current()
                    || speech.requested.elapsed() >= SERVICE_WAIT
                {
                    self.cancel_speech("语音服务Ready身份、保护或调用范围已失效".into());
                    return true;
                }
                let binding = speech.scope.binding.clone();
                self.phase(binding.clone(), Phase::ServiceReady);
                let speech = self.speech.as_mut().expect("actor-owned speech");
                // This same actor must never route platform results concurrently.
                let result = voice::speak_scoped(
                    operation.clone(),
                    speech.text.take().unwrap_or_default(),
                    voice::SpeechPolicy {
                        opted_in: self.opted_in,
                        background_guard: Some(guard.clone()),
                    },
                    speech.scope.cancelled.clone(),
                    &speech.lease,
                );
                match result {
                    Err(error) => self.cancel_speech(error),
                    Ok(speaker) => {
                        let speech = self.speech.as_mut().expect("actor-owned speech");
                        speech.submitted_at = Some(Instant::now());
                        speech.speaker = Some(speaker);
                        speech.permit.cleanup_unknown = true;
                        self.phase(binding, Phase::SubmittedToSpeechEngine);
                        self.tick();
                    }
                }
                true
            }
            speech_service::SpeechServiceEvent::Failed { operation, error } => {
                if operation
                    .as_ref()
                    .is_some_and(|key| key != &speech.scope.binding.operation)
                {
                    return false;
                }
                self.cancel_speech(error.clone());
                true
            }
            speech_service::SpeechServiceEvent::Released { operation } => {
                if operation != &speech.scope.binding.operation {
                    return false;
                }
                self.cancel_speech("语音服务租约已释放".into());
                true
            }
            speech_service::SpeechServiceEvent::NotificationRestricted => false,
        }
    }
    pub fn voice_result(&mut self, result: &voice::VoiceResult) -> bool {
        if let voice::VoiceResult::SpeechStopFinished {
            operation,
            expected,
            result,
        } = result
        {
            let Some(stop) = self.stop.as_ref().filter(|stop| {
                &stop.scope.binding.operation == operation
                    && &stop.expected == expected
                    && stop.target.operation == expected.operation
                    && stop.target.caller == stop.scope.binding.caller
            }) else {
                return false;
            };
            let result = if !stop.scope.valid() {
                Err("停止回执所属父/子调用已终结".into())
            } else {
                result.clone().map(|confirmation| match confirmation {
                    voice::StopConfirmation::RetractedBeforePlayback { speaker } => json!({"outcome":"retracted_before_playback","target_operation":speaker.operation.id,"speaker_instance":speaker.instance}),
                    voice::StopConfirmation::FrameworkStopped { speaker, engine_generation, audio_focus_released } => json!({"outcome":"framework_stop_confirmed","target_operation":speaker.operation.id,"speaker_instance":speaker.instance,"engine_generation":engine_generation,"audio_focus_released":audio_focus_released}),
                })
            };
            let stop = self.stop.take().expect("matched stop result");
            if !stop.reported {
                self.finish(stop.scope.binding, result);
            }
            return true;
        }
        let voice::VoiceResult::ScopedSpeechFinished {
            operation,
            speaker,
            result,
            cleanup_confirmed,
        } = result
        else {
            return false;
        };
        let Some(speech) = self.speech.as_ref() else {
            return false;
        };
        if operation != &speech.scope.binding.operation || speech.speaker.as_ref() != Some(speaker)
        {
            return false;
        }
        let mut speech = self.speech.take().expect("matched model speech");
        speech.permit.cleanup_unknown = !*cleanup_confirmed;
        let binding = speech.scope.binding.clone();
        if !speech.reported {
            let result = if !*cleanup_confirmed
                || !speech.scope.valid()
                || !self.opted_in
                || !speech.lease.confirmed()
                || speech.submitted_at.is_none()
            {
                Err("朗读终结时服务保护或实际父/子调用范围已失效".into())
            } else {
                result.clone().map(|()| json!({"outcome":"read_aloud_completed","operation":operation.id,"generation":operation.generation}))
            };
            self.finish(binding.clone(), result);
        }
        // Keep the service lease and audio permit until the exact platform final
        // result. A timeout reply alone never releases ownership for new audio.
        drop(speech);
        true
    }
    pub fn tick(&mut self) {
        if let Some(speech) = &self.speech {
            let error = if !speech.scope.valid() || !self.opted_in {
                Some("朗读所属父/子运行已停止或用户关闭朗读")
            } else if speech.submitted_at.is_some() && !speech.lease.confirmed() {
                Some("朗读失去已确认的语音服务保护")
            } else if speech.submitted_at.is_none() && speech.requested.elapsed() >= SERVICE_WAIT {
                Some("语音服务15秒内没有Ready确认；未开始朗读")
            } else if speech
                .submitted_at
                .is_some_and(|started| started.elapsed() >= SPEECH_WAIT)
            {
                Some("朗读超过120秒预算，正在请求停止")
            } else {
                None
            };
            if let Some(error) = error {
                self.cancel_speech(error.into());
            }
        }
        if self.stop.as_ref().is_some_and(|stop| {
            !stop.reported && (!stop.scope.valid() || stop.requested.elapsed() >= STOP_WAIT)
        }) {
            let stop = self.stop.as_mut().expect("expired stop");
            stop.reported = true;
            voice::cancel_operation(&stop.scope.binding.operation);
            let binding = stop.scope.binding.clone();
            self.finish(
                binding,
                Err("停止请求的调用已结束或15秒内未收到目标终结回执；系统停止仍未确认".into()),
            );
        }
    }
    fn cancel_speech(&mut self, error: String) {
        let Some(speech) = self.speech.as_mut() else {
            return;
        };
        let binding = speech.scope.binding.clone();
        let submitted = speech.submitted_at.is_some();
        if submitted && speech.cleanup_requested.is_none() {
            if let Some(key) = &speech.speaker {
                voice::cancel_speaker(key);
            }
            speech.cleanup_requested = Some(Instant::now());
        }
        let report = !speech.reported;
        speech.reported = true;
        if report {
            self.finish(binding.clone(), Err(error));
        }
        if submitted {
            if report {
                self.phase(binding, Phase::WaitingForCleanup);
            }
        } else {
            self.speech.take();
        }
    }
    fn phase(&mut self, binding: Binding, phase: Phase) {
        debug_assert!(self.updates.len() < MAX_UPDATES);
        self.updates.push_back(Update::Phase { binding, phase });
    }
    fn finish(&mut self, binding: Binding, result: Result<Value, String>) {
        let result = result.map_err(|error| {
            let mut end = error.len().min(4096);
            while !error.is_char_boundary(end) {
                end -= 1;
            }
            error[..end].into()
        });
        debug_assert!(self.updates.len() < MAX_UPDATES);
        self.updates.push_back(Update::Finished { binding, result });
    }
}

impl Drop for Actor {
    fn drop(&mut self) {
        if let Some(speech) = &self.speech {
            if let Some(speaker) = &speech.speaker {
                voice::cancel_speaker(speaker);
            }
        }
        if let Some(stop) = &self.stop {
            voice::cancel_operation(&stop.scope.binding.operation);
        }
        // An admitted task leaves its gate held when no exact final cleanup was
        // received. Dropping its service lease revokes the mandatory Ready guard.
    }
}

pub fn definitions() -> Vec<ToolDefinition> {
    vec![
        ToolDefinition { name:"speak".into(), description:"Read up to 500 characters through the enabled system speech engine. The exact caller must receive an actual SpeechService Ready lease; a queued request is not playback success. Completion waits for the exact operation's speech-engine result.".into(), parameters:json!({"type":"object","properties":{"text":{"type":"string","maxLength":500}},"required":["text"],"additionalProperties":false}) },
        ToolDefinition { name:"stop_speaking".into(), description:"Request stopping speech owned by this exact parent or child run. Other readback is not stopped. A submitted stop with no independently confirmed platform result is reported as unconfirmed.".into(), parameters:json!({"type":"object","properties":{},"required":[],"additionalProperties":false}) },
    ]
}

pub(crate) fn is_tool(name: &str) -> bool {
    matches!(name, "speak" | "stop_speaking")
}
pub(crate) fn validate_call(call: &ToolCall) -> Result<(), String> {
    if call.arguments.len() > 4096 {
        return Err("语音参数超过预算".into());
    }
    let value: Value = serde_json::from_str(&call.arguments).map_err(|_| "语音参数不是JSON")?;
    let object = value.as_object().ok_or("语音参数必须是对象")?;
    match call.name.as_str() {
        "stop_speaking" if object.is_empty() => Ok(()),
        "speak" if object.len() == 1 => {
            let text = object
                .get("text")
                .and_then(Value::as_str)
                .ok_or("text必须是字符串")?
                .trim();
            if text.is_empty()
                || text.len() > 2000
                || text.chars().count() > 500
                || text.contains('\0')
            {
                Err("朗读文本为空、含NUL或超过500字符/2000字节".into())
            } else {
                Ok(())
            }
        }
        _ => Err("未知语音工具或参数字段".into()),
    }
}
