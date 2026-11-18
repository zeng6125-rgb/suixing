use super::permissions::OperationKey;
use crate::network::CancelToken;
use std::sync::Arc;
#[derive(Clone)]
pub enum Action {
    Read,
    Write { text: String },
}
impl std::fmt::Debug for Action {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Read => "Read",
            Self::Write { .. } => "Write{[redacted]}",
        })
    }
}
pub struct ResultText {
    pub text: Option<String>,
    pub write_accepted: bool,
}
impl std::fmt::Debug for ResultText {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ResultText")
            .field("text", &"[redacted]")
            .field("write_accepted", &self.write_accepted)
            .finish()
    }
}
pub fn execute(
    operation: &OperationKey,
    action: Action,
    cancel: Arc<CancelToken>,
) -> Result<ResultText, String> {
    operation.validate()?;
    if let Action::Write { text } = &action {
        if text.len() > 256 * 1024 || text.contains('\0') {
            return Err("剪贴板文字超过256 KiB预算或含NUL".into());
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (action, cancel);
        Err("当前平台没有Android剪贴板适配".into())
    }
    #[cfg(target_os = "android")]
    {
        super::main_call::run(operation, cancel, move || device(action))
    }
}
#[cfg(target_os = "android")]
fn device(action: Action) -> Result<ResultText, String> {
    use crate::platform::android;
    use jni::{JValue, jni_sig, jni_str};
    if !android::activity_visible() {
        return Err("剪贴板操作需要JAgent当前可见页面；未读取或写入".into());
    }
    android::invoke(|env| {
        let context = android::application_context().map_err(|_| jni::errors::Error::NullPtr("clipboard context"))?;
        let name = env.new_string("clipboard")?;
        let manager = env.call_method(context.as_ref(), jni_str!("getSystemService"), jni_sig!("(Ljava/lang/String;)Ljava/lang/Object;"), &[JValue::Object(&name)])?.l()?;
        match action {
            Action::Read => {
                let clip = env.call_method(manager, jni_str!("getPrimaryClip"), jni_sig!("()Landroid/content/ClipData;"), &[])?.l()?;
                if clip.is_null() || env.call_method(&clip, jni_str!("getItemCount"), jni_sig!("()I"), &[])?.i()? < 1 {
                    return Err(jni::errors::Error::NullPtr("clipboard empty or framework denied access"));
                }
                let item = env.call_method(clip, jni_str!("getItemAt"), jni_sig!("(I)Landroid/content/ClipData$Item;"), &[JValue::Int(0)])?.l()?;
                let text = env.call_method(item, jni_str!("getText"), jni_sig!("()Ljava/lang/CharSequence;"), &[])?.l()?;
                if text.is_null() { return Err(jni::errors::Error::NullPtr("clipboard is not directly copied text")); }
                let length = env.call_method(&text, jni_str!("length"), jni_sig!("()I"), &[])?.i()?;
                if length < 0 || length > 256 * 1024 { return Err(jni::errors::Error::NullPtr("clipboard text budget")); }
                let text = env.call_method(text, jni_str!("toString"), jni_sig!("()Ljava/lang/String;"), &[])?.l()?;
                let text = android::string_value(env, text, 256 * 1024)?;
                Ok(ResultText { text: Some(text), write_accepted: false })
            }
            Action::Write { text } => {
                let label = env.new_string("JAgent")?;
                let text = env.new_string(text)?;
                let clip = env.call_static_method(jni_str!("android/content/ClipData"), jni_str!("newPlainText"),
                    jni_sig!("(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Landroid/content/ClipData;"), &[JValue::Object(&label), JValue::Object(&text)])?.l()?;
                let description = env.call_method(&clip, jni_str!("getDescription"), jni_sig!("()Landroid/content/ClipDescription;"), &[])?.l()?;
                let extras = env.new_object(jni_str!("android/os/PersistableBundle"), jni_sig!("()V"), &[])?;
                let sensitive = env.new_string("android.content.extra.IS_SENSITIVE")?;
                env.call_method(&extras, jni_str!("putBoolean"), jni_sig!("(Ljava/lang/String;Z)V"), &[JValue::Object(&sensitive), JValue::Bool(true)])?;
                env.call_method(description, jni_str!("setExtras"), jni_sig!("(Landroid/os/PersistableBundle;)V"), &[JValue::Object(&extras)])?;
                env.call_method(manager, jni_str!("setPrimaryClip"), jni_sig!("(Landroid/content/ClipData;)V"), &[JValue::Object(&clip)])?;
                Ok(ResultText { text: None, write_accepted: true })
            }
        }
    }).map_err(|_| "剪贴板操作未完成；可能为空、系统拒绝、不是直接文本或超过预算".into())
}
