//! Serialize an actual pinned committed history, one message at a time.
use crate::{display::HistorySource, network::CancelToken, store::CommittedHistory};
use std::{io::Write, sync::Arc};

#[derive(Clone)]
pub(crate) struct Source(pub Arc<CommittedHistory>);
impl std::fmt::Debug for Source {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ConversationExport")
            .field("conversation", &self.0.conversation.id)
            .field("message_count", &self.0.headers().len())
            .finish()
    }
}
impl Source {
    pub(crate) fn filename(&self) -> String {
        let title: String = self
            .0
            .conversation
            .title
            .chars()
            .take(200)
            .map(|ch| {
                if ch.is_whitespace()
                    || matches!(
                        ch,
                        '/' | '\\' | '\0' | ':' | '*' | '?' | '"' | '<' | '>' | '|'
                    )
                {
                    '_'
                } else {
                    ch
                }
            })
            .collect();
        format!(
            "{}.json",
            if title.is_empty() {
                "conversation"
            } else {
                &title
            }
        )
    }
    pub(crate) fn write_json(
        &self,
        out: &mut impl Write,
        cancel: &CancelToken,
    ) -> Result<(), String> {
        let mut metadata =
            serde_json::to_value(&self.0.conversation).map_err(|_| "会话信息无法编码")?;
        let fields = metadata.as_object_mut().ok_or("会话信息不是JSON对象")?;
        // These are written from the pinned reader, never from an old UI page.
        fields.remove("messages");
        fields.remove("rustQueuedTurnsV1");
        out.write_all(b"{").map_err(|e| e.to_string())?;
        let mut comma = false;
        for (name, value) in fields.iter() {
            if cancel.is_cancelled() {
                return Err("会话导出已取消".into());
            }
            if comma {
                out.write_all(b",").map_err(|e| e.to_string())?
            }
            serde_json::to_writer(&mut *out, name).map_err(|e| e.to_string())?;
            out.write_all(b":").map_err(|e| e.to_string())?;
            serde_json::to_writer(&mut *out, value).map_err(|e| e.to_string())?;
            comma = true;
        }
        if comma {
            out.write_all(b",").map_err(|e| e.to_string())?
        }
        out.write_all(b"\"messages\":[")
            .map_err(|e| e.to_string())?;
        for index in 0..self.0.headers().len() {
            if index > 0 {
                out.write_all(b",").map_err(|e| e.to_string())?
            }
            self.0
                .with_message(index, &|| cancel.is_cancelled(), |message| {
                    serde_json::to_writer(&mut *out, message)
                        .map_err(|e| crate::display::DisplayError::Invalid(e.to_string()))
                })
                .map_err(|e| format!("会话导出来源读取失败：{e:?}"))?;
        }
        out.write_all(b"]").map_err(|e| e.to_string())?;
        if !self.0.queued_turns.is_empty() {
            out.write_all(b",\"rustQueuedTurnsV1\":")
                .map_err(|e| e.to_string())?;
            serde_json::to_writer(&mut *out, &self.0.queued_turns).map_err(|e| e.to_string())?;
        }
        out.write_all(b"}").map_err(|e| e.to_string())?;
        Ok(())
    }
}
