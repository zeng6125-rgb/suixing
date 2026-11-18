//! Small framework adapters shared by typed Rust service policies. No runtime
//! state lock, Store, prompt, transcript, or tool is consulted by this module.
use crate::platform::android;
use jni::{
    Env, JValue, jni_sig, jni_str,
    objects::{JObject, JString},
    refs::Global,
};
use std::sync::Arc;

pub type Object = Arc<Global<JObject<'static>>>;
pub const IMMUTABLE_UPDATE: i32 = 67_108_864 | 134_217_728;
pub const SPECIAL_USE: i32 = 1_073_741_824;

pub fn invoke<T>(operation: impl FnOnce(&mut Env) -> jni::errors::Result<T>) -> Result<T, String> {
    let mut exception = None;
    let result = android::with_env(|env| {
        if env.exception_check() {
            env.exception_clear();
        }
        let result = operation(env);
        if result.is_err() {
            if let Some(thrown) = env.exception_occurred() {
                env.exception_clear();
                let class_name = (|| -> jni::errors::Result<String> {
                    let class = env
                        .call_method(
                            &thrown,
                            jni_str!("getClass"),
                            jni_sig!("()Ljava/lang/Class;"),
                            &[],
                        )?
                        .l()?;
                    let name = env
                        .call_method(
                            class,
                            jni_str!("getName"),
                            jni_sig!("()Ljava/lang/String;"),
                            &[],
                        )?
                        .l()?;
                    env.cast_local::<JString>(name)?.try_to_string(env)
                })();
                exception = class_name.ok().filter(|name| name.len() <= 256);
            }
        }
        if env.exception_check() {
            env.exception_clear();
        }
        result
    });
    result.map_err(|_| match exception.as_deref() {
        Some("java.lang.SecurityException") => "系统权限拒绝".into(),
        Some("android.app.ForegroundServiceStartNotAllowedException")
        | Some("android.app.BackgroundServiceStartNotAllowedException") => {
            "系统当前不允许后台启动服务".into()
        }
        Some("android.app.MissingForegroundServiceTypeException") => "前台服务类型未声明".into(),
        Some("android.app.InvalidForegroundServiceTypeException") => "前台服务类型无效".into(),
        Some("android.app.StartForegroundCalledOnStoppedServiceException") => {
            "服务已停止，前台提升被拒绝".into()
        }
        Some(name) => format!("Android框架异常：{name}"),
        None => "Android JNI/框架调用未成功".into(),
    })
}

pub fn system_service(
    env: &mut Env,
    context: &Global<JObject<'static>>,
    name: &str,
) -> jni::errors::Result<Global<JObject<'static>>> {
    let name = env.new_string(name)?;
    let object = env
        .call_method(
            context,
            jni_str!("getSystemService"),
            jni_sig!("(Ljava/lang/String;)Ljava/lang/Object;"),
            &[JValue::Object(&name)],
        )?
        .l()?;
    if object.is_null() {
        return Err(jni::errors::Error::NullPtr("system service"));
    }
    env.new_global_ref(object)
}

pub enum Extra<'a> {
    Text(&'a str, &'a str),
    Long(&'a str, i64),
    Bool(&'a str, bool),
}

pub fn intent(
    env: &mut Env,
    context: &Global<JObject<'static>>,
    component: &str,
    action: &str,
    data: Option<&str>,
    extras: &[Extra<'_>],
) -> jni::errors::Result<Global<JObject<'static>>> {
    let value = env.new_object(jni_str!("android/content/Intent"), jni_sig!("()V"), &[])?;
    let component = env.new_string(super::components::installed(component))?;
    env.call_method(
        &value,
        jni_str!("setClassName"),
        jni_sig!("(Landroid/content/Context;Ljava/lang/String;)Landroid/content/Intent;"),
        &[JValue::Object(context), JValue::Object(&component)],
    )?;
    let action = env.new_string(action)?;
    env.call_method(
        &value,
        jni_str!("setAction"),
        jni_sig!("(Ljava/lang/String;)Landroid/content/Intent;"),
        &[JValue::Object(&action)],
    )?;
    if let Some(data) = data {
        let data = env.new_string(data)?;
        let uri = env
            .call_static_method(
                jni_str!("android/net/Uri"),
                jni_str!("parse"),
                jni_sig!("(Ljava/lang/String;)Landroid/net/Uri;"),
                &[JValue::Object(&data)],
            )?
            .l()?;
        env.call_method(
            &value,
            jni_str!("setData"),
            jni_sig!("(Landroid/net/Uri;)Landroid/content/Intent;"),
            &[JValue::Object(&uri)],
        )?;
    }
    for extra in extras {
        match extra {
            Extra::Text(name, text) => {
                let name = env.new_string(name)?;
                let text = env.new_string(text)?;
                env.call_method(
                    &value,
                    jni_str!("putExtra"),
                    jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;"),
                    &[JValue::Object(&name), JValue::Object(&text)],
                )?;
            }
            Extra::Long(name, number) => {
                let name = env.new_string(name)?;
                env.call_method(
                    &value,
                    jni_str!("putExtra"),
                    jni_sig!("(Ljava/lang/String;J)Landroid/content/Intent;"),
                    &[JValue::Object(&name), JValue::Long(*number)],
                )?;
            }
            Extra::Bool(name, flag) => {
                let name = env.new_string(name)?;
                env.call_method(
                    &value,
                    jni_str!("putExtra"),
                    jni_sig!("(Ljava/lang/String;Z)Landroid/content/Intent;"),
                    &[JValue::Object(&name), JValue::Bool(*flag)],
                )?;
            }
        }
    }
    env.new_global_ref(value)
}

#[derive(Clone, Copy)]
pub enum PendingKind {
    Activity,
    Broadcast,
    ForegroundService,
}
pub fn pending(
    env: &mut Env,
    context: &Global<JObject<'static>>,
    request_code: i32,
    value: &Global<JObject<'static>>,
    kind: PendingKind,
) -> jni::errors::Result<Global<JObject<'static>>> {
    let method = match kind {
        PendingKind::Activity => jni_str!("getActivity"),
        PendingKind::Broadcast => jni_str!("getBroadcast"),
        PendingKind::ForegroundService => jni_str!("getForegroundService"),
    };
    if matches!(kind, PendingKind::Activity) {
        env.call_method(
            value,
            jni_str!("addFlags"),
            jni_sig!("(I)Landroid/content/Intent;"),
            &[JValue::Int(268_435_456 | 67_108_864 | 536_870_912)],
        )?;
    }
    let pending = env
        .call_static_method(
            jni_str!("android/app/PendingIntent"),
            method,
            jni_sig!(
                "(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;"
            ),
            &[
                JValue::Object(context),
                JValue::Int(request_code),
                JValue::Object(value),
                JValue::Int(IMMUTABLE_UPDATE),
            ],
        )?
        .l()?;
    env.new_global_ref(pending)
}

pub fn channel(
    env: &mut Env,
    manager: &Global<JObject<'static>>,
    channel_id: &str,
    title: &str,
    importance: i32,
) -> jni::errors::Result<bool> {
    let id = env.new_string(channel_id)?;
    let mut existing = env
        .call_method(
            manager,
            jni_str!("getNotificationChannel"),
            jni_sig!("(Ljava/lang/String;)Landroid/app/NotificationChannel;"),
            &[JValue::Object(&id)],
        )?
        .l()?;
    if existing.is_null() {
        let title = env.new_string(title)?;
        existing = env.new_object(
            jni_str!("android/app/NotificationChannel"),
            jni_sig!("(Ljava/lang/String;Ljava/lang/CharSequence;I)V"),
            &[
                JValue::Object(&id),
                JValue::Object(&title),
                JValue::Int(importance),
            ],
        )?;
        env.call_method(
            &existing,
            jni_str!("setShowBadge"),
            jni_sig!("(Z)V"),
            &[JValue::Bool(false)],
        )?;
        env.call_method(
            &existing,
            jni_str!("setSound"),
            jni_sig!("(Landroid/net/Uri;Landroid/media/AudioAttributes;)V"),
            &[
                JValue::Object(&JObject::null()),
                JValue::Object(&JObject::null()),
            ],
        )?;
        env.call_method(
            manager,
            jni_str!("createNotificationChannel"),
            jni_sig!("(Landroid/app/NotificationChannel;)V"),
            &[JValue::Object(&existing)],
        )?;
    }
    let channel_allowed = env
        .call_method(&existing, jni_str!("getImportance"), jni_sig!("()I"), &[])?
        .i()?
        > 0;
    let enabled = env
        .call_method(
            manager,
            jni_str!("areNotificationsEnabled"),
            jni_sig!("()Z"),
            &[],
        )?
        .z()?;
    Ok(channel_allowed && enabled)
}

pub fn notification(
    env: &mut Env,
    context: &Global<JObject<'static>>,
    channel_id: &str,
    title: &str,
    text: &str,
    ongoing: bool,
    open: &Global<JObject<'static>>,
    stop: Option<(&str, &Global<JObject<'static>>)>,
) -> jni::errors::Result<Global<JObject<'static>>> {
    let channel = env.new_string(channel_id)?;
    let builder = env.new_object(
        jni_str!("android/app/Notification$Builder"),
        jni_sig!("(Landroid/content/Context;Ljava/lang/String;)V"),
        &[JValue::Object(context), JValue::Object(&channel)],
    )?;
    let icon = env
        .get_static_field(
            jni_str!("android/R$drawable"),
            jni_str!("stat_notify_chat"),
            jni_sig!("I"),
        )?
        .i()?;
    env.call_method(
        &builder,
        jni_str!("setSmallIcon"),
        jni_sig!("(I)Landroid/app/Notification$Builder;"),
        &[JValue::Int(icon)],
    )?;
    for (method, text) in [
        (jni_str!("setContentTitle"), title),
        (jni_str!("setContentText"), text),
    ] {
        let text = env.new_string(text)?;
        env.call_method(
            &builder,
            method,
            jni_sig!("(Ljava/lang/CharSequence;)Landroid/app/Notification$Builder;"),
            &[JValue::Object(&text)],
        )?;
    }
    for (method, flag) in [
        (jni_str!("setOngoing"), ongoing),
        (jni_str!("setOnlyAlertOnce"), true),
        (jni_str!("setAutoCancel"), !ongoing),
    ] {
        env.call_method(
            &builder,
            method,
            jni_sig!("(Z)Landroid/app/Notification$Builder;"),
            &[JValue::Bool(flag)],
        )?;
    }
    env.call_method(
        &builder,
        jni_str!("setVisibility"),
        jni_sig!("(I)Landroid/app/Notification$Builder;"),
        &[JValue::Int(0)],
    )?;
    let category = env.new_string("service")?;
    env.call_method(
        &builder,
        jni_str!("setCategory"),
        jni_sig!("(Ljava/lang/String;)Landroid/app/Notification$Builder;"),
        &[JValue::Object(&category)],
    )?;
    env.call_method(
        &builder,
        jni_str!("setContentIntent"),
        jni_sig!("(Landroid/app/PendingIntent;)Landroid/app/Notification$Builder;"),
        &[JValue::Object(open)],
    )?;
    if let Some((label, stop)) = stop {
        let label = env.new_string(label)?;
        env.call_method(&builder, jni_str!("addAction"), jni_sig!("(ILjava/lang/CharSequence;Landroid/app/PendingIntent;)Landroid/app/Notification$Builder;"), &[JValue::Int(icon), JValue::Object(&label), JValue::Object(stop)])?;
    }
    let notification = env
        .call_method(
            builder,
            jni_str!("build"),
            jni_sig!("()Landroid/app/Notification;"),
            &[],
        )?
        .l()?;
    env.new_global_ref(notification)
}

pub fn start_component(value: &Global<JObject<'static>>) -> Result<(), String> {
    let context = android::application_context()?;
    invoke(|env| {
        let component = env
            .call_method(
                context.as_ref(),
                jni_str!("startForegroundService"),
                jni_sig!("(Landroid/content/Intent;)Landroid/content/ComponentName;"),
                &[JValue::Object(value)],
            )?
            .l()?;
        if component.is_null() {
            return Err(jni::errors::Error::NullPtr("foreground component"));
        }
        Ok(())
    })
}

pub fn promote_special(
    instance: &Global<JObject<'static>>,
    id: i32,
    notification: &Global<JObject<'static>>,
) -> Result<(), String> {
    promote_type(instance, id, notification, SPECIAL_USE, 34)
}

/// Confirmation belongs to this service instance and its declared type.
pub fn promote_type(
    instance: &Global<JObject<'static>>,
    id: i32,
    notification: &Global<JObject<'static>>,
    kind: i32,
    minimum_typed_api: i32,
) -> Result<(), String> {
    let sdk = android::paths().ok_or("Android平台尚未初始化")?.sdk_int;
    invoke(|env| {
        if sdk >= minimum_typed_api {
            env.call_method(
                instance,
                jni_str!("startForeground"),
                jni_sig!("(ILandroid/app/Notification;I)V"),
                &[
                    JValue::Int(id),
                    JValue::Object(notification),
                    JValue::Int(kind),
                ],
            )?;
            let actual = env
                .call_method(
                    instance,
                    jni_str!("getForegroundServiceType"),
                    jni_sig!("()I"),
                    &[],
                )?
                .i()?;
            if actual & kind != kind {
                return Err(jni::errors::Error::NullPtr("foreground type not confirmed"));
            }
        } else {
            env.call_method(
                instance,
                jni_str!("startForeground"),
                jni_sig!("(ILandroid/app/Notification;)V"),
                &[JValue::Int(id), JValue::Object(notification)],
            )?;
        }
        Ok(())
    })
}

pub fn notify(id: i32, tag: Option<&str>, value: &Global<JObject<'static>>) -> Result<(), String> {
    let context = android::application_context()?;
    invoke(|env| {
        let manager = system_service(env, context.as_ref(), "notification")?;
        if let Some(tag) = tag {
            let tag = env.new_string(tag)?;
            env.call_method(
                &manager,
                jni_str!("notify"),
                jni_sig!("(Ljava/lang/String;ILandroid/app/Notification;)V"),
                &[JValue::Object(&tag), JValue::Int(id), JValue::Object(value)],
            )?;
        } else {
            env.call_method(
                &manager,
                jni_str!("notify"),
                jni_sig!("(ILandroid/app/Notification;)V"),
                &[JValue::Int(id), JValue::Object(value)],
            )?;
        }
        Ok(())
    })
}

pub struct StopOutcome {
    pub foreground_removed: bool,
    pub stop_requested: bool,
    pub errors: Vec<String>,
}
pub fn stop(instance: &Global<JObject<'static>>) -> StopOutcome {
    let foreground = invoke(|env| {
        env.call_method(
            instance,
            jni_str!("stopForeground"),
            jni_sig!("(I)V"),
            &[JValue::Int(1)],
        )?;
        Ok(())
    });
    let stopped = invoke(|env| {
        env.call_method(instance, jni_str!("stopSelf"), jni_sig!("()V"), &[])?;
        Ok(())
    });
    StopOutcome {
        foreground_removed: foreground.is_ok(),
        stop_requested: stopped.is_ok(),
        errors: foreground.err().into_iter().chain(stopped.err()).collect(),
    }
}
pub fn stop_instance(instance: &Global<JObject<'static>>) -> Result<(), String> {
    let outcome = stop(instance);
    if outcome.errors.is_empty() {
        Ok(())
    } else {
        Err(outcome.errors.join("；"))
    }
}
pub fn stop_component(component: &str, action: &str) -> Result<bool, String> {
    let context = android::application_context()?;
    invoke(|env| {
        let value = intent(env, context.as_ref(), component, action, None, &[])?;
        env.call_method(
            context.as_ref(),
            jni_str!("stopService"),
            jni_sig!("(Landroid/content/Intent;)Z"),
            &[JValue::Object(&value)],
        )?
        .z()
    })
}
pub fn retain(instance: &Global<JObject<'static>>) -> Result<Object, String> {
    invoke(|env| env.new_global_ref(instance).map(Arc::new))
}
pub fn same_instance(
    left: &Global<JObject<'static>>,
    right: &Global<JObject<'static>>,
) -> Result<bool, String> {
    invoke(|env| env.is_same_object(left, right))
}
