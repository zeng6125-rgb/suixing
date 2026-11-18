use super::{ACTION_OPEN, ACTION_START, ACTION_STOP, SERVICE_CLASS};
use crate::platform::{android, service_framework as fw};
use fw::{Extra, PendingKind};
use jni::{objects::JObject, refs::Global};

const CHANNEL: &str = "Suixing_speech";
const ID: i32 = 1410;
const ACTIVITY: &str = "com.suixing.agent.rust.platform.RustNativeActivity";
const MEDIA_PLAYBACK: i32 = 2;

pub fn start_uri(process: &str, token: u64) -> String {
    format!("jagent-speech://start/{process}/{token}")
}
pub fn stop_uri(process: &str, epoch: u64) -> String {
    format!("jagent-speech://stop/{process}/{epoch}")
}
pub fn request_start(process: &str, token: u64) -> Result<(), String> {
    let context = android::application_context()?;
    let intent = fw::invoke(|env| {
        fw::intent(
            env,
            context.as_ref(),
            SERVICE_CLASS,
            ACTION_START,
            Some(&start_uri(process, token)),
            &[Extra::Text("process_token", process)],
        )
    })?;
    fw::start_component(&intent)
}
fn build(
    process: &str,
    epoch: u64,
    count: usize,
) -> Result<(Global<JObject<'static>>, bool), String> {
    let context = android::application_context()?;
    fw::invoke(|env| {
        let manager = fw::system_service(env, context.as_ref(), "notification")?;
        let allowed = fw::channel(env, &manager, CHANNEL, "语音朗读", 2)?;
        let open = fw::intent(env, context.as_ref(), ACTIVITY, ACTION_OPEN, None, &[])?;
        let open = fw::pending(env, context.as_ref(), ID, &open, PendingKind::Activity)?;
        let stop = fw::intent(
            env,
            context.as_ref(),
            SERVICE_CLASS,
            ACTION_STOP,
            Some(&stop_uri(process, epoch)),
            &[Extra::Text("process_token", process)],
        )?;
        let stop = fw::pending(
            env,
            context.as_ref(),
            ID,
            &stop,
            PendingKind::ForegroundService,
        )?;
        let text = format!("{count}项语音朗读 · 点击返回JAgent");
        let notification = fw::notification(
            env,
            context.as_ref(),
            CHANNEL,
            "JAgent · 语音朗读",
            &text,
            true,
            &open,
            Some(("停止朗读", &stop)),
        )?;
        Ok((notification, allowed))
    })
}
pub fn promote(
    instance: &Global<JObject<'static>>,
    process: &str,
    epoch: u64,
    count: usize,
) -> Result<bool, String> {
    let (notification, allowed) = build(process, epoch, count)?;
    fw::promote_type(instance, ID, &notification, MEDIA_PLAYBACK, 29)?;
    Ok(allowed)
}
pub fn update(process: &str, epoch: u64, count: usize) -> Result<bool, String> {
    let (notification, allowed) = build(process, epoch, count)?;
    fw::notify(ID, None, &notification)?;
    Ok(allowed)
}
