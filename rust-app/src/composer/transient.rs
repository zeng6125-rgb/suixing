//! Sensitive queued command arguments are process-owned and never serialized.
//! The durable reference allows a cold queue to request fresh user input.
use crate::state::QueuedTurn;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;

const MAX_RAW_BYTES: usize = 1024 * 1024;
const MAX_RAW_ENTRIES: usize = 32;
const MAX_RAW_COMMAND: usize = 64 * 1024 + 128;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct TransientRef {
    pub version: u32,
    /// Opaque nonce digest. It is deliberately NOT a digest of sensitive input,
    /// which would expose low-entropy arguments to an offline dictionary check.
    pub sha256: String,
    pub bytes: usize,
}
impl TransientRef {
    pub fn validate(&self) -> Result<(), String> {
        if self.version != 1
            || self.bytes == 0
            || self.bytes > MAX_RAW_COMMAND
            || self.sha256.len() != 64
            || !self.sha256.bytes().all(|byte| byte.is_ascii_hexdigit())
        {
            return Err("敏感排队指令引用无效".into());
        }
        Ok(())
    }
}
struct Raw {
    reference: TransientRef,
    text: String,
}
#[derive(Default)]
pub struct Cache {
    raw: HashMap<(String, String), Raw>,
}
pub struct Staged {
    pub turn: QueuedTurn,
    conversation_id: String,
    raw: Option<String>,
}
impl Cache {
    /// Existing references survive an unchanged edit. Edited text gets a fresh
    /// reference or clears the reference; the caller commits only after Store.
    pub fn stage(
        &self,
        conversation_id: &str,
        original: Option<&QueuedTurn>,
        mut edited: QueuedTurn,
    ) -> Result<Staged, String> {
        if conversation_id.is_empty() || edited.id.is_empty() {
            return Err("排队指令缺少身份".into());
        }
        if let Some(reference) = &edited.transient {
            reference.validate()?;
            if !edited.attachments.is_empty() {
                return Err("敏感斜杠指令不能带附件".into());
            }
        }
        if original.is_some_and(|original| original.id != edited.id) {
            return Err("编辑的排队指令身份已变化".into());
        }
        if let Some(original) = original
            && original.id == edited.id
            && original.text == edited.text
            && original.transient == edited.transient
            && (edited.transient.is_some()
                || !super::slash::parse(&edited.text)
                    .is_some_and(|command| super::slash::sensitive_arguments(&command.name)))
        {
            return Ok(Staged {
                turn: edited,
                conversation_id: conversation_id.into(),
                raw: None,
            });
        }
        if original.is_none() && edited.transient.is_some() {
            return Err("新指令不能引用另一次敏感输入".into());
        }
        edited.transient = None;
        let sensitive = super::slash::parse(&edited.text)
            .filter(|command| super::slash::sensitive_arguments(&command.name));
        let raw = if let Some(command) = sensitive {
            if !edited.attachments.is_empty() {
                return Err("斜杠指令不能带附件".into());
            }
            if edited.text.len() > MAX_RAW_COMMAND {
                return Err("敏感指令超过64 KiB参数预算".into());
            }
            let key = (conversation_id.to_owned(), edited.id.clone());
            let old = self.raw.get(&key).map_or(0, |raw| raw.text.len());
            let used: usize = self.raw.values().map(|raw| raw.text.len()).sum();
            if (!self.raw.contains_key(&key) && self.raw.len() >= MAX_RAW_ENTRIES)
                || used.saturating_sub(old).saturating_add(edited.text.len()) > MAX_RAW_BYTES
            {
                return Err("敏感指令内存队列已满，原输入保留".into());
            }
            let reference = TransientRef {
                version: 1,
                sha256: format!(
                    "{:x}",
                    Sha256::digest(crate::controller::unique_id().as_bytes())
                ),
                bytes: edited.text.len(),
            };
            let transcript = super::slash::transcript_text(&edited.text, &command.name);
            let raw = std::mem::replace(&mut edited.text, transcript);
            edited.transient = Some(reference);
            Some(raw)
        } else {
            None
        };
        Ok(Staged {
            turn: edited,
            conversation_id: conversation_id.into(),
            raw,
        })
    }
    pub fn commit(&mut self, staged: Staged) {
        let key = (staged.conversation_id, staged.turn.id);
        if let Some(text) = staged.raw {
            let reference = staged.turn.transient.expect("staged sensitive reference");
            self.raw.insert(key, Raw { reference, text });
        } else if staged.turn.transient.is_none() {
            self.raw.remove(&key);
        }
    }
    pub fn resolve<'a>(
        &'a self,
        conversation_id: &str,
        turn: &'a QueuedTurn,
    ) -> Result<&'a str, String> {
        let Some(reference) = &turn.transient else {
            return Ok(&turn.text);
        };
        reference.validate()?;
        if !turn.attachments.is_empty() {
            return Err("敏感斜杠指令不能带附件".into());
        }
        self.raw
            .get(&(conversation_id.into(), turn.id.clone()))
            .filter(|raw| {
                &raw.reference == reference
                    && raw.text.len() == reference.bytes
                    && super::slash::parse(&raw.text).is_some_and(|command| {
                        super::slash::sensitive_arguments(&command.name)
                            && super::slash::transcript_text(&raw.text, &command.name) == turn.text
                    })
            })
            .map(|raw| raw.text.as_str())
            .ok_or_else(|| "敏感指令已不在本次进程内，请编辑并重新输入指令；占位内容未执行".into())
    }
    pub fn prune(&mut self, conversation_id: &str, turns: &[QueuedTurn]) {
        self.raw.retain(|(owner, id), raw| {
            owner != conversation_id
                || turns
                    .iter()
                    .any(|turn| &turn.id == id && turn.transient.as_ref() == Some(&raw.reference))
        });
    }
    pub fn forget_conversation(&mut self, conversation_id: &str) {
        self.raw.retain(|(owner, _), _| owner != conversation_id);
    }
}

// Root adds pub mod transient to composer.rs, and this uniquely named queue field:
// #[serde(default, rename="rustTransientCommandV1", skip_serializing_if="Option::is_none")]
// pub transient: Option<crate::composer::transient::TransientRef>
// Existing QueuedTurn constructions use transient:None. Queue validation calls
// reference.validate, forbids attachments on transient commands, and counts the
// reference in the existing full escaped queue JSON budget. Queue consumption
// compares transient equality alongside text and attachment references.
