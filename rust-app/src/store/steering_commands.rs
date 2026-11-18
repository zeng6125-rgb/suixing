//! Admission consumes one exact queued operation and appends its user text and
//! direct-call ledger in the same transcript commit. No tool effect runs here.
use super::Store;
use crate::{
    agent::steering_operations::{Applied, Directive, Kind, Operation},
    state::Message,
};

impl Store {
    pub(crate) fn accept_steering_local(
        &mut self,
        operation: &Operation,
        mut messages: Vec<Message>,
        command: &str,
    ) -> Result<(super::queued_commands::LocalTicket, Vec<Message>), String> {
        operation.validate()?;
        if !matches!(&operation.kind, Kind::LocalGoal(_))
            || !operation.expected.attachments.is_empty()
            || operation.expected.transient.is_some()
        {
            return Err("本地目标指令附带无效参数".into());
        }
        let question_id = operation.expected.id.clone();
        let reply_id = crate::controller::unique_id();
        let timestamp = super::now()?;
        let document=self.update_transcript(&operation.scope.conversation_id,|document|{
            let index=document.queued_turns.iter().position(|turn|turn.id==question_id).ok_or("排队消息已移除")?;
            let current=&document.queued_turns[index];
            if current.text!=operation.expected.text || current.attachments!=operation.expected.attachments
                || current.transient!=operation.expected.transient {return Err("排队消息已变化，本地指令未执行".into());}
            if messages.iter().any(|message|message.id==question_id || message.id==reply_id){return Err("本地指令身份已存在，未重复执行".into());}
            if messages.last().is_some_and(|message|message.role=="assistant" && !message.complete
                && message.text.is_empty() && message.thinking.is_empty() && message.tool_calls.is_empty()){messages.pop();}
            messages.push(Message{id:question_id.clone(),role:"user".into(),text:current.text.clone(),thinking:String::new(),
                complete:true,tool_calls:Vec::new(),tool_call_id:None,metadata:crate::chat::MessageMetadata{
                    timestamp,steer:true,run_id:Some(operation.scope.run_id.clone()),..Default::default()}});
            let mut metadata=crate::chat::MessageMetadata{timestamp,trace_note:Some("本地目标指令".into()),..Default::default()};
            metadata.extra.insert("rustQueuedLocalCommandV1".into(),serde_json::json!({
                "version":1,"question_id":question_id,"command":command,"state":"accepted","scope_run_id":operation.scope.run_id}));
            messages.push(Message{id:reply_id.clone(),role:"assistant".into(),text:String::new(),thinking:String::new(),
                complete:false,tool_calls:Vec::new(),tool_call_id:None,metadata});
            crate::composer::json_bytes(&messages,crate::agent::MAX_HISTORY_BYTES-128*1024)?;
            document.messages=messages;document.queued_turns.remove(index);Ok(())
        })?;
        Ok((
            super::queued_commands::LocalTicket {
                conversation_id: operation.scope.conversation_id.clone(),
                question_id,
                reply_id,
                turns: document.queued_turns,
            },
            document.messages,
        ))
    }
    pub(crate) fn accept_steering_operation(
        &mut self,
        operation: &Operation,
        mut messages: Vec<Message>,
    ) -> Result<Applied, String> {
        operation.validate()?;
        let mut prepared_turn = operation.expected.clone();
        match &operation.kind {
            Kind::Text => {}
            Kind::DirectTool(call) => {
                prepared_turn.text =
                    crate::composer::slash::transcript_text(&prepared_turn.text, &call.name);
            }
            Kind::LocalGoal(_) => return Err("本地目标指令需要独立本地回执路径".into()),
        }
        let prepared = crate::controller::prepare_queue_input(&self.workspace(), &prepared_turn)?;
        let mut assistant = crate::agent::assistant_placeholder();
        assistant.metadata.run_id = Some(operation.scope.run_id.clone());
        let directive = match &operation.kind {
            Kind::DirectTool(call) => {
                assistant.complete = true;
                assistant.tool_calls = vec![crate::transcript_redaction::call_for_transcript(call)];
                assistant.metadata.extra.insert("rustImmediateSlashV1".into(),serde_json::json!({
                    "version":1,"queue_id":operation.expected.id,"run_id":operation.scope.run_id,"call_id":call.id,"state":"admitted"}));
                Directive::DirectTool {
                    call: call.clone(),
                    assistant_id: assistant.id.clone(),
                }
            }
            Kind::Text => Directive::Text,
            Kind::LocalGoal(_) => unreachable!(),
        };
        let run_id = operation.scope.run_id.clone();
        let timestamp = super::now()?;
        let document = self.update_transcript(&operation.scope.conversation_id, |document| {
            let index = document
                .queued_turns
                .iter()
                .position(|turn| turn.id == operation.expected.id)
                .ok_or("立即插话的排队记录已移除")?;
            let turn = &document.queued_turns[index];
            if turn.text != operation.expected.text
                || turn.attachments != operation.expected.attachments
                || turn.transient != operation.expected.transient
            {
                return Err("立即插话的排队记录已变化，指令未执行".into());
            }
            if messages
                .iter()
                .any(|message| message.id == turn.id || message.id == assistant.id)
            {
                return Err("插话消息身份已存在，未重复执行".into());
            }
            if let Some(call) = assistant.tool_calls.first() {
                if messages.iter().any(|message| {
                    message
                        .tool_calls
                        .iter()
                        .any(|existing| existing.id == call.id)
                }) {
                    return Err("直接工具调用身份已存在，未重复执行".into());
                }
            }
            if messages.last().is_some_and(|message| {
                message.role == "assistant"
                    && !message.complete
                    && message.text.is_empty()
                    && message.thinking.is_empty()
                    && message.tool_calls.is_empty()
            }) {
                messages.pop();
            }
            messages.push(Message {
                id: turn.id.clone(),
                role: "user".into(),
                text: prepared.text.clone(),
                thinking: String::new(),
                complete: true,
                tool_calls: Vec::new(),
                tool_call_id: None,
                metadata: crate::chat::MessageMetadata {
                    timestamp,
                    run_id: Some(run_id),
                    steer: true,
                    ..prepared.metadata.clone()
                },
            });
            messages.push(assistant);
            crate::composer::json_bytes(&messages, crate::agent::MAX_HISTORY_BYTES - 128 * 1024)?;
            document.messages = messages;
            document.queued_turns.remove(index);
            Ok(())
        })?;
        Ok(Applied {
            accepted_id: operation.expected.id.clone(),
            messages: document.messages,
            remaining_turns: document.queued_turns,
            directive,
        })
    }
}

impl Store {
    pub(crate) fn undelivered_immediate(
        &mut self,
        conversation_id: &str,
        run_id: &str,
        assistant_id: &str,
        call: &crate::state::ToolCall,
    ) -> Result<Vec<Message>, String> {
        let document = self.update_transcript(conversation_id, |document| {
            let assistant = document
                .messages
                .iter_mut()
                .find(|message| message.id == assistant_id)
                .ok_or("直接指令受理消息已变化")?;
            let marker = assistant
                .metadata
                .extra
                .get_mut("rustImmediateSlashV1")
                .and_then(serde_json::Value::as_object_mut)
                .ok_or("直接指令缺少受理记录")?;
            if marker.get("run_id").and_then(serde_json::Value::as_str) != Some(run_id)
                || marker.get("call_id").and_then(serde_json::Value::as_str)
                    != Some(call.id.as_str())
                || assistant.tool_calls.len() != 1
                || assistant.tool_calls[0].id != call.id
            {
                return Err("直接指令受理身份不一致，未猜测结果".into());
            }
            if marker.get("state").and_then(serde_json::Value::as_str) == Some("not_started") {
                return Ok(());
            }
            if marker.get("state").and_then(serde_json::Value::as_str) != Some("admitted") {
                return Err("直接指令状态已变化，未重复终结".into());
            }
            marker.insert("state".into(), serde_json::json!("not_started"));
            assistant.complete = true;
            if !document
                .messages
                .iter()
                .any(|message| message.tool_call_id.as_deref() == Some(call.id.as_str()))
            {
                let mut message = crate::agent::tool_message(
                    call.id.clone(),
                    serde_json::json!({
                    "ok":false,"started":false,"not_started":true,
                    "error":"指令已原子受理，但携带原始参数的回执未交给工作线程；工具未执行"}),
                );
                message.metadata.run_id = Some(run_id.into());
                message.metadata.tool_name = Some(call.name.clone());
                document.messages.push(message);
            }
            Ok(())
        })?;
        Ok(document.messages)
    }
}
