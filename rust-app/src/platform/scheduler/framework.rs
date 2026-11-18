use super::{
    ACTION_CANCEL, ACTION_EXECUTE, ACTION_FIRE, ACTION_RESUME, ALARM_URI, AlarmMode, AlarmRequest,
    FireIdentity, RECEIVER_CLASS, SERVICE_CLASS,
};
use crate::platform::{android, service_framework as fw};
use fw::{Extra, Object, PendingKind};
use jni::{JValue, jni_sig, jni_str, objects::JObject, refs::Global};

const CHANNEL: &str = "Suixing_schedule";
const FOREGROUND_ID: i32 = 1301;
const ALARM_REQUEST: i32 = 1302;
const FIRE_CARD: i32 = 1303;
const ACTIVITY: &str = "com.suixing.agent.rust.platform.RustNativeActivity";

fn extras(identity: &FireIdentity) -> [Extra<'_>; 4] {
    [
        Extra::Text("schedule_id", &identity.schedule_id),
        Extra::Text("fire_token", &identity.fire_token),
        Extra::Text("alarm_generation", &identity.generation),
        Extra::Long("scheduled_at", identity.scheduled_at),
    ]
}

pub fn arm(request: &AlarmRequest) -> Result<Option<AlarmMode>, String> {
    request.validate()?;
    let context = android::application_context()?;
    let sdk = android::paths().ok_or("Android平台尚未初始化")?.sdk_int;
    let identity = request
        .plan
        .as_ref()
        .map(|plan| FireIdentity::for_plan(request.generation.clone(), plan))
        .transpose()?;
    let (alarm, fire) = fw::invoke(|env| {
        let alarm = fw::system_service(env, context.as_ref(), "alarm")?;
        let fire = if let Some(identity) = &identity {
            fw::intent(
                env,
                context.as_ref(),
                RECEIVER_CLASS,
                ACTION_FIRE,
                Some(ALARM_URI),
                &extras(identity),
            )?
        } else {
            fw::intent(
                env,
                context.as_ref(),
                RECEIVER_CLASS,
                ACTION_FIRE,
                Some(ALARM_URI),
                &[],
            )?
        };
        let pending = fw::pending(
            env,
            context.as_ref(),
            ALARM_REQUEST,
            &fire,
            PendingKind::Broadcast,
        )?;
        Ok((alarm, pending))
    })?;
    // The PendingIntent filter identity is fixed across generations, so set
    // replaces the one system alarm. Generation remains validation data only.
    fw::invoke(|env| {
        env.call_method(
            &alarm,
            jni_str!("cancel"),
            jni_sig!("(Landroid/app/PendingIntent;)V"),
            &[JValue::Object(&fire)],
        )?;
        Ok(())
    })?;
    let Some(plan) = &request.plan else {
        return Ok(None);
    };
    let exact = sdk < 31
        || fw::invoke(|env| {
            env.call_method(
                &alarm,
                jni_str!("canScheduleExactAlarms"),
                jni_sig!("()Z"),
                &[],
            )?
            .z()
        })?;
    if exact {
        let result = fw::invoke(|env| {
            env.call_method(
                &alarm,
                jni_str!("setExactAndAllowWhileIdle"),
                jni_sig!("(IJLandroid/app/PendingIntent;)V"),
                &[
                    JValue::Int(0),
                    JValue::Long(plan.at_millis),
                    JValue::Object(&fire),
                ],
            )?;
            Ok(())
        });
        if result.is_ok() {
            return Ok(Some(AlarmMode::Exact));
        }
        // Permission can change between the query and set. The inexact result
        // is reported explicitly; an exact failure never claims precise arming.
    }
    fw::invoke(|env| {
        env.call_method(
            &alarm,
            jni_str!("setAndAllowWhileIdle"),
            jni_sig!("(IJLandroid/app/PendingIntent;)V"),
            &[
                JValue::Int(0),
                JValue::Long(plan.at_millis),
                JValue::Object(&fire),
            ],
        )?;
        Ok(())
    })?;
    Ok(Some(AlarmMode::Inexact))
}

pub fn request_start(identity: &FireIdentity) -> Result<(), String> {
    request_start_action(identity, ACTION_EXECUTE)
}
pub fn request_resume(identity: &FireIdentity) -> Result<(), String> {
    request_start_action(identity, ACTION_RESUME)
}
fn request_start_action(identity: &FireIdentity, action: &str) -> Result<(), String> {
    identity.validate()?;
    let context = android::application_context()?;
    let intent = fw::invoke(|env| {
        fw::intent(
            env,
            context.as_ref(),
            SERVICE_CLASS,
            action,
            Some(&identity.execution_uri()),
            &extras(identity),
        )
    })?;
    fw::start_component(&intent)
}

fn open(
    env: &mut jni::Env,
    context: &Global<JObject<'static>>,
    identity: Option<&FireIdentity>,
) -> jni::errors::Result<Global<JObject<'static>>> {
    let intent = if let Some(identity) = identity {
        fw::intent(
            env,
            context,
            ACTIVITY,
            super::ACTION_OPEN,
            Some(&identity.open_uri()),
            &extras(identity),
        )?
    } else {
        fw::intent(env, context, ACTIVITY, super::ACTION_OPEN, None, &[])?
    };
    fw::pending(env, context, FOREGROUND_ID, &intent, PendingKind::Activity)
}

fn build_summary(count: usize) -> Result<(Global<JObject<'static>>, bool), String> {
    let context = android::application_context()?;
    fw::invoke(|env| {
        let manager = fw::system_service(env, context.as_ref(), "notification")?;
        let allowed = fw::channel(env, &manager, CHANNEL, "定时提醒", 3)?;
        let open = open(env, context.as_ref(), None)?;
        let value = fw::notification(
            env,
            context.as_ref(),
            CHANNEL,
            "JAgent · 定时提醒",
            &format!("{count}项提醒正在执行"),
            true,
            &open,
            None,
        )?;
        Ok((value, allowed))
    })
}

pub fn promote(instance: &Global<JObject<'static>>, count: usize) -> Result<bool, String> {
    let (value, allowed) = build_summary(count)?;
    fw::promote_special(instance, FOREGROUND_ID, &value)?;
    Ok(allowed)
}
pub fn update(identities: &[FireIdentity], previous: &[FireIdentity]) -> Result<bool, String> {
    let (value, allowed) = build_summary(identities.len())?;
    fw::notify(FOREGROUND_ID, None, &value)?;
    for identity in identities {
        publish_fire(identity, false)?;
    }
    for identity in previous.iter().filter(|old| {
        !identities
            .iter()
            .any(|now| old.fire_token == now.fire_token)
    }) {
        clear_fire(identity)?;
    }
    Ok(allowed)
}
pub fn publish_fire(identity: &FireIdentity, deferred: bool) -> Result<bool, String> {
    identity.validate()?;
    let context = android::application_context()?;
    let (value, allowed) = fw::invoke(|env| {
        let manager = fw::system_service(env, context.as_ref(), "notification")?;
        let allowed = fw::channel(env, &manager, CHANNEL, "定时提醒", 3)?;
        let (open, stop) = if deferred {
            let intent = fw::intent(
                env,
                context.as_ref(),
                SERVICE_CLASS,
                ACTION_RESUME,
                Some(&identity.execution_uri()),
                &extras(identity),
            )?;
            (
                fw::pending(
                    env,
                    context.as_ref(),
                    FIRE_CARD,
                    &intent,
                    PendingKind::ForegroundService,
                )?,
                None,
            )
        } else {
            let open = open(env, context.as_ref(), Some(identity))?;
            let stop = fw::intent(
                env,
                context.as_ref(),
                SERVICE_CLASS,
                ACTION_CANCEL,
                Some(&identity.cancel_uri()),
                &extras(identity),
            )?;
            (
                open,
                Some(fw::pending(
                    env,
                    context.as_ref(),
                    FIRE_CARD,
                    &stop,
                    PendingKind::ForegroundService,
                )?),
            )
        };
        let text = if deferred {
            "后台启动未完成 · 点击执行这条提醒"
        } else {
            "正在执行 · 点击查看提醒会话"
        };
        let notification = fw::notification(
            env,
            context.as_ref(),
            CHANNEL,
            "JAgent · 定时提醒",
            text,
            !deferred,
            &open,
            stop.as_ref().map(|stop| ("停止提醒", stop)),
        )?;
        Ok((notification, allowed))
    })?;
    fw::notify(FIRE_CARD, Some(&identity.fire_token), &value)?;
    Ok(allowed)
}
pub fn clear_fire(identity: &FireIdentity) -> Result<(), String> {
    let context = android::application_context()?;
    fw::invoke(|env| {
        let manager = fw::system_service(env, context.as_ref(), "notification")?;
        let tag = env.new_string(&identity.fire_token)?;
        env.call_method(
            &manager,
            jni_str!("cancel"),
            jni_sig!("(Ljava/lang/String;I)V"),
            &[JValue::Object(&tag), JValue::Int(FIRE_CARD)],
        )?;
        Ok(())
    })
}
pub fn retain(instance: &Global<JObject<'static>>) -> Result<Object, String> {
    fw::retain(instance)
}
