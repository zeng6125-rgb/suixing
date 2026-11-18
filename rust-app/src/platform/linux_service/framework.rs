use super::{
    ACTION_OPEN, ACTION_REFRESH, ACTION_STOP, SERVICE_CLASS, refresh_uri, stop_uri, tag,
    validate_key,
};
use crate::{
    linux::Key,
    platform::{android, service_framework as fw},
};
use fw::{Extra, PendingKind};
use jni::{JValue, jni_sig, jni_str, objects::JObject, refs::Global};
use std::collections::HashSet;

const CHANNEL: &str = "Suixing_linux";
const ID: i32 = 1510;
const SESSION_CARD: i32 = 1511;
const ACTIVITY: &str = "com.suixing.agent.rust.platform.RustNativeActivity";

fn build(keys: &[Key], single: Option<&Key>) -> Result<(Global<JObject<'static>>, bool), String> {
    let context = android::application_context()?;
    fw::invoke(|env| {
        let manager = fw::system_service(env, context.as_ref(), "notification")?;
        let allowed = fw::channel(env, &manager, CHANNEL, "Linux环境安装与移除", 2)?;
        let open = fw::intent(
            env,
            context.as_ref(),
            ACTIVITY,
            ACTION_OPEN,
            None,
            &[Extra::Bool("open_operations", true)],
        )?;
        let open = fw::pending(env, context.as_ref(), ID, &open, PendingKind::Activity)?;
        let stop = if let Some(key) = single {
            let value = fw::intent(
                env,
                context.as_ref(),
                SERVICE_CLASS,
                ACTION_STOP,
                Some(&stop_uri(key)),
                &[Extra::Text("process_token", &key.process_token)],
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
            "正在保护Linux环境安装、解压或移除 · 点击返回".into()
        } else {
            format!("{}个会话 · 点击返回Linux操作", keys.len())
        };
        let notification = fw::notification(
            env,
            context.as_ref(),
            CHANNEL,
            "JAgent · Linux环境安装与移除",
            &text,
            true,
            &open,
            stop.as_ref().map(|stop| ("停止Linux操作", stop)),
        )?;
        Ok((notification, allowed))
    })
}
pub fn request_start(key: &Key) -> Result<(), String> {
    validate_key(key)?;
    let context = android::application_context()?;
    let value = fw::invoke(|env| {
        fw::intent(
            env,
            context.as_ref(),
            SERVICE_CLASS,
            ACTION_REFRESH,
            Some(&refresh_uri(key)),
            &[Extra::Text("process_token", &key.process_token)],
        )
    })?;
    fw::start_component(&value)
}
pub fn promote(instance: &Global<JObject<'static>>, keys: &[Key]) -> Result<bool, String> {
    let single = (keys.len() == 1).then(|| &keys[0]);
    let (notification, allowed) = build(keys, single)?;
    fw::promote_type(instance, ID, &notification, 1, 29)?;
    Ok(allowed)
}
pub fn update(keys: &[Key], previous: &HashSet<String>) -> Result<(bool, HashSet<String>), String> {
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
pub fn prospective(keys: &[Key]) -> HashSet<String> {
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
