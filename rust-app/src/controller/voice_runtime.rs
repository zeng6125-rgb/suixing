//! One root-owned model voice actor; Composer receives this same AudioGate.
use super::*;
use crate::voice_tools::{self, AudioGate, Binding, Update};
pub(super) struct VoiceRuntime {
    gate: AudioGate,
    actor: voice_tools::Actor,
    epoch: u64,
    pending: Vec<(Binding, SyncSender<Result<serde_json::Value, String>>)>,
}
impl VoiceRuntime {
    pub fn new(gate: AudioGate, enabled: bool) -> Self {
        Self {
            actor: voice_tools::Actor::new(gate.clone(), enabled),
            gate,
            epoch: 1,
            pending: Vec::new(),
        }
    }
    pub fn submit(
        &mut self,
        run: &str,
        child: Option<&str>,
        prepared: session_runtime::Prepared,
        reply: SyncSender<Result<serde_json::Value, String>>,
        runs: &Runs,
        publish: &Publisher,
        enabled: bool,
    ) {
        self.actor.set_opted_in(enabled);
        if !self.actor.identity_budget_available()
            && !self.actor.busy()
            && self.pending.is_empty()
            && self.gate.owner().is_none()
        {
            if let Some(epoch) = self.epoch.checked_add(1) {
                self.epoch = epoch;
                self.actor = voice_tools::Actor::new(self.gate.clone(), enabled);
            }
        }
        let result = prepared.into_voice(run, child, self.epoch, runs, publish);
        let (scope, call) = match result {
            Ok(value) => value,
            Err(error) => {
                let _ = reply.try_send(Err(error));
                return;
            }
        };
        if self.pending.len() >= 8 {
            let _ = reply.try_send(Err("语音等待回执预算已满".into()));
            return;
        }
        let binding = scope.binding.clone();
        self.pending.push((binding.clone(), reply));
        let result = match call.name.as_str() {
            "speak" => self.actor.speak(scope, &call),
            "stop_speaking" => self.actor.stop_speaking(scope, &call),
            _ => Err("未知语音工具".into()),
        };
        if let Err(error) = result {
            self.finish(&binding, Err(error));
        }
        self.poll();
    }
    fn finish(&mut self, binding: &Binding, result: Result<serde_json::Value, String>) {
        if let Some(index) = self.pending.iter().position(|(key, _)| key == binding) {
            let (_, reply) = self.pending.remove(index);
            let _ = reply.try_send(result);
        }
    }
    fn poll(&mut self) {
        for update in self.actor.drain_updates(8) {
            if let Update::Finished { binding, result } = update {
                // A post-admission failure must never become the common
                // session tool's started=false / not_dispatched wrapper.
                let value = match result {
                    Ok(value) => serde_json::json!({"ok":true,"started":true,"voice":value}),
                    Err(error) => {
                        serde_json::json!({"ok":false,"started":true,"effects_may_have_occurred":true,"error":error})
                    }
                };
                self.finish(&binding, Ok(value));
            }
        }
    }
    pub fn service_event(
        &mut self,
        event: &crate::platform::speech_service::SpeechServiceEvent,
    ) -> bool {
        let consumed = self.actor.service_event(event);
        self.poll();
        consumed
    }
    pub fn voice_result(&mut self, result: &crate::platform::voice::VoiceResult) -> bool {
        let consumed = self.actor.voice_result(result);
        self.poll();
        consumed
    }
    pub fn tick(&mut self, enabled: bool) {
        self.actor.set_opted_in(enabled);
        self.actor.tick();
        self.poll();
    }
}
impl Drop for VoiceRuntime {
    fn drop(&mut self) {
        for (_, reply) in self.pending.drain(..) {
            let _ = reply.try_send(Ok(serde_json::json!({"ok":false,"started":true,"effects_may_have_occurred":true,"error":"语音宿主正在关闭，系统清理尚未确认"})));
        }
        // The one voice Actor's Drop revokes the exact speaker and lease; an
        // unconfirmed permit keeps its old owner instead of enabling reuse.
    }
}
