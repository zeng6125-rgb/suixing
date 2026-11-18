use super::{ACTION_OPEN, ACTION_REFRESH, ACTION_STOP, SERVICE_CLASS, stop_uri, tag, validate_key};
use crate::{
    jobs::JobKey,
    platform::{android, service_framework as fw},
};
use fw::{Extra, PendingKind};
use jni::{JValue, jni_sig, jni_str, objects::JObject, refs::Global};
use std::collections::HashSet;

const CHANNEL: &str = "Suixing_jobs";
const ID: i32 = 1280;
const SESSION_CARD: i32 = 1281;
const ACTIVITY: &str = "com.suixing.agent.rust.platform.RustNativeActivity";

fn build(
    keys: &[JobKey],
    single: Option<&JobKey>,
) -> Result<(Global<JObject<'static>>, bool), String> {
    let context = android::application_context()?;
    fw::invoke(|env| {
        let manager = fw::system_service(env, context.as_ref(), "notification")?;
        let allowed = fw::channel(env, &manager, CHANNEL, "交互式后台作业", 2)?;
        let open = fw::intent(
            env,
            context.as_ref(),
            ACTIVITY,
            ACTION_OPEN,
            None,
            &[Extra::Bool("open_jobs", true)],
        )?;
        let open = fw::pending(env, context.as_ref(), ID, &open, PendingKind::Activity)?;
        let stop = if let Some(key) = single {
            let job_id = &key.id;
            let value = fw::intent(
                env,
                context.as_ref(),
                SERVICE_CLASS,
                ACTION_STOP,
                Some(&stop_uri(key)),
                &[
                    Extra::Text("process_token", &key.process_token),
                    Extra::Text("job_id", job_id),
                ],
            )?;
            Some(fw::pending(
                env,
                context.as_ref(),
                SESSION_CARD,
                &value,
                PendingKind::ForegroundService,
            )?)
        } else {
            None
        };
        let text = if single.is_some() {
            "后台进程运行中 · 点击返回后台作业".into()
        } else {
            format!("{}个会话 · 点击返回后台作业", keys.len())
        };
        let notification = fw::notification(
            env,
            context.as_ref(),
            CHANNEL,
            "JAgent · 后台作业运行中",
            &text,
            true,
            &open,
            stop.as_ref().map(|stop| ("停止后台作业", stop)),
        )?;
        Ok((notification, allowed))
    })
}
pub fn request_start() -> Result<(), String> {
    let context = android::application_context()?;
    let value = fw::invoke(|env| {
        fw::intent(
            env,
            context.as_ref(),
            SERVICE_CLASS,
            ACTION_REFRESH,
            None,
            &[],
        )
    })?;
    fw::start_component(&value)
}
pub fn promote(instance: &Global<JObject<'static>>, keys: &[JobKey]) -> Result<bool, String> {
    let single = (keys.len() == 1).then(|| &keys[0]);
    let (notification, allowed) = build(keys, single)?;
    fw::promote_special(instance, ID, &notification)?;
    Ok(allowed)
}
pub fn update(
    keys: &[JobKey],
    previous: &HashSet<String>,
) -> Result<(bool, HashSet<String>), String> {
    for key in keys {
        validate_key(key)?;
    }
    let single = (keys.len() == 1).then(|| &keys[0]);
    let (notification, allowed) = build(keys, single)?;
    fw::notify(ID, None, &notification)?;
    let mut current = HashSet::new();
    if keys.len() > 1 {
        for key in keys {
            let (value, _) = build(keys, Some(key))?;
            let tag = tag(key);
            current.insert(tag.clone());
            fw::notify(SESSION_CARD, Some(&tag), &value)?;
        }
    }
    for tag in previous.iter().filter(|tag| !current.contains(*tag)) {
        clear_card(tag)?;
    }
    Ok((allowed, current))
}
pub fn prospective(keys: &[JobKey]) -> HashSet<String> {
    if keys.len() > 1 {
        keys.iter().map(tag).collect()
    } else {
        HashSet::new()
    }
}
pub fn clear_card(tag: &str) -> Result<(), String> {
    let context = android::application_context()?;
    fw::invoke(|env| {
        let manager = fw::system_service(env, context.as_ref(), "notification")?;
        let tag = env.new_string(tag)?;
        env.call_method(
            &manager,
            jni_str!("cancel"),
            jni_sig!("(Ljava/lang/String;I)V"),
            &[JValue::Object(&tag), JValue::Int(SESSION_CARD)],
        )?;
        Ok(())
    })
}
