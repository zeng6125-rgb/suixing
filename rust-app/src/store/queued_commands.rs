//! Local command admission consumes an exact queue head with its real transcript.
//! Goal mutation is a subsequent effect; a crash never automatically replays it.
use super::Store;
use crate::state::{Message, QueuedTurn};

pub(crate) struct LocalTicket {
    pub conversation_id: String,
    pub question_id: String,
    pub reply_id: String,
    pub turns: Vec<QueuedTurn>,
}
impl Store {
    /// Used only before the exact queue admission commits. Once its user turn
    /// exists, failures pause the admitted goal and keep the real transcript.
    pub(crate) fn rollback_queued_goal(
        &mut self,
        run_id: &str,
        previous: crate::goal::Goal,
    ) -> Result<(), String> {
        if self.goal().id != run_id {
            return Err("目标已变化，未覆盖较新的目标状态".into());
        }
        self.save_goal(previous)
    }
    pub(crate) fn accept_queued_local(
        &mut self,
        conversation_id: &str,
        expected: &QueuedTurn,
        command: &str,
    ) -> Result<LocalTicket, String> {
        if !expected.attachments.is_empty() || expected.transient.is_some() {
            return Err("本地目标指令不能附带附件或敏感指令引用".into());
        }
        let timestamp = super::now()?;
        let reply_id = crate::controller::unique_id();
        let question_id = expected.id.clone();
        let turns = self.update_transcript(conversation_id, |document| {
            let head = document.queued_turns.first().ok_or("排队消息已移除")?;
            if head.id != expected.id || head.text != expected.text || head.attachments != expected.attachments
                || head.transient != expected.transient { return Err("排队消息已变化，指令未执行".into()); }
            if document.messages.iter().any(|message| message.id == question_id || message.id == reply_id) {
                return Err("排队指令消息身份已存在，未重复执行".into());
            }
            let question = Message {
                id: question_id.clone(), role: "user".into(), text: expected.text.clone(),
                thinking: String::new(), complete: true, tool_calls: Vec::new(), tool_call_id: None,
                metadata: crate::chat::MessageMetadata { timestamp, ..Default::default() },
            };
            let mut metadata = crate::chat::MessageMetadata { timestamp, trace_note:Some("本地目标指令".into()), ..Default::default() };
            metadata.extra.insert("rustQueuedLocalCommandV1".into(), serde_json::json!({"version":1,"question_id":question_id,"command":command,"state":"accepted"}));
            let reply = Message {
                id: reply_id.clone(), role: "assistant".into(), text: String::new(), thinking: String::new(),
                complete: false, tool_calls: Vec::new(), tool_call_id: None, metadata,
            };
            document.messages.push(question); document.messages.push(reply); document.queued_turns.remove(0);
            Ok(())
        })?.queued_turns;
        Ok(LocalTicket {
            conversation_id: conversation_id.into(),
            question_id,
            reply_id,
            turns,
        })
    }
    pub(crate) fn finish_queued_local(
        &mut self,
        ticket: &LocalTicket,
        result: &Result<String, String>,
    ) -> Result<(), String> {
        self.update_transcript(&ticket.conversation_id, |document| {
            let question = document
                .messages
                .iter()
                .position(|message| message.id == ticket.question_id)
                .ok_or("本地指令提问已变化")?;
            let reply = document
                .messages
                .get_mut(question + 1)
                .filter(|message| message.id == ticket.reply_id)
                .ok_or("本地指令回应已变化")?;
            let envelope = reply
                .metadata
                .extra
                .get_mut("rustQueuedLocalCommandV1")
                .and_then(serde_json::Value::as_object_mut)
                .ok_or("本地指令回执身份已变化")?;
            if envelope
                .get("question_id")
                .and_then(serde_json::Value::as_str)
                != Some(ticket.question_id.as_str())
            {
                return Err("本地指令回执不属于原提问".into());
            }
            reply.text = match result {
                Ok(text) => text.clone(),
                Err(error) => format!("目标指令未完成：{error}"),
            };
            reply.complete = true;
            envelope.insert(
                "state".into(),
                serde_json::Value::String(
                    if result.is_ok() {
                        "completed"
                    } else {
                        "failed"
                    }
                    .into(),
                ),
            );
            Ok(())
        })
        .map(|_| ())
    }
}
