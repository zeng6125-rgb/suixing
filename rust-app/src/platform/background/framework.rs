//! Framework calls only; no Rust manager lock is held while calling JNI.
use super::{ACTION_CANCEL, ACTION_OPEN, ACTION_REFRESH, SERVICE_CLASS};
use crate::{
    platform::android,
    runtime_host::{RunKey, ServiceRunState, ServiceSnapshot},
};
use jni::{
    Env, JValue, jni_sig, jni_str,
    objects::{JObject, JString},
    refs::Global,
};
use std::{collections::HashSet, sync::Arc};

const CHANNEL: &str = "Suixing_agent_rust";
const FOREGROUND_ID: i32 = 1201;
const RUN_ID: i32 = 1202;
const FLAGS: i32 = 67_108_864 | 134_217_728; // IMMUTABLE | UPDATE_CURRENT
type Object = Arc<Global<JObject<'static>>>;

fn invoke<T>(operation: impl FnOnce(&mut Env) -> jni::errors::Result<T>) -> Result<T, String> {
    let mut exception = None;
    let result = android::with_env(|env| {
        if env.exception_check() {
            env.exception_clear();
        }
        let result = operation(env);
        if result.is_err() {
            if let Some(thrown) = env.exception_occurred() {
                env.exception_clear();
                let name = (|| -> jni::errors::Result<String> {
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
                exception = name.ok().filter(|name| name.len() <= 256);
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

fn service(
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

fn intent(
    env: &mut Env,
    context: &Global<JObject<'static>>,
    component: &str,
    action: &str,
    key: Option<&RunKey>,
    data: Option<String>,
) -> jni::errors::Result<Global<JObject<'static>>> {
    let intent = env.new_object(jni_str!("android/content/Intent"), jni_sig!("()V"), &[])?;
    let component = env.new_string(crate::platform::components::installed(component))?;
    env.call_method(
        &intent,
        jni_str!("setClassName"),
        jni_sig!("(Landroid/content/Context;Ljava/lang/String;)Landroid/content/Intent;"),
        &[JValue::Object(context), JValue::Object(&component)],
    )?;
    let action = env.new_string(action)?;
    env.call_method(
        &intent,
        jni_str!("setAction"),
        jni_sig!("(Ljava/lang/String;)Landroid/content/Intent;"),
        &[JValue::Object(&action)],
    )?;
    if let Some(key) = key {
        for (name, value) in [
            ("run_id", key.run_id.as_str()),
            ("conversation_id", key.conversation_id.as_str()),
        ] {
            let name = env.new_string(name)?;
            let value = env.new_string(value)?;
            env.call_method(
                &intent,
                jni_str!("putExtra"),
                jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;"),
                &[JValue::Object(&name), JValue::Object(&value)],
            )?;
        }
    }
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
            &intent,
            jni_str!("setData"),
            jni_sig!("(Landroid/net/Uri;)Landroid/content/Intent;"),
            &[JValue::Object(&uri)],
        )?;
    }
    env.new_global_ref(intent)
}

fn pending(
    env: &mut Env,
    context: &Global<JObject<'static>>,
    key: Option<&RunKey>,
    cancel: bool,
) -> jni::errors::Result<Global<JObject<'static>>> {
    let target = if cancel {
        SERVICE_CLASS
    } else {
        "com.suixing.agent.rust.platform.RustNativeActivity"
    };
    let action = if cancel { ACTION_CANCEL } else { ACTION_OPEN };
    let data = key.map(|key| {
        if cancel {
            super::cancel_uri(key)
        } else {
            super::cancel_uri(key).replacen("//cancel/", "//open/", 1)
        }
    });
    let intent = intent(env, context, target, action, key, data)?;
    if !cancel {
        env.call_method(
            &intent,
            jni_str!("addFlags"),
            jni_sig!("(I)Landroid/content/Intent;"),
            &[JValue::Int(268_435_456 | 67_108_864 | 536_870_912)],
        )?;
    }
    let value = if cancel {
        env.call_static_method(
            jni_str!("android/app/PendingIntent"),
            jni_str!("getForegroundService"),
            jni_sig!(
                "(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;"
            ),
            &[
                JValue::Object(context),
                JValue::Int(1204),
                JValue::Object(&intent),
                JValue::Int(FLAGS),
            ],
        )?
        .l()?
    } else {
        env.call_static_method(
            jni_str!("android/app/PendingIntent"),
            jni_str!("getActivity"),
            jni_sig!(
                "(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;"
            ),
            &[
                JValue::Object(context),
                JValue::Int(1205),
                JValue::Object(&intent),
                JValue::Int(FLAGS),
            ],
        )?
        .l()?
    };
    env.new_global_ref(value)
}

fn channel(env: &mut Env, manager: &Global<JObject<'static>>) -> jni::errors::Result<bool> {
    let id = env.new_string(CHANNEL)?;
    let mut existing = env
        .call_method(
            manager,
            jni_str!("getNotificationChannel"),
            jni_sig!("(Ljava/lang/String;)Landroid/app/NotificationChannel;"),
            &[JValue::Object(&id)],
        )?
        .l()?;
    if existing.is_null() {
        let title = env.new_string("任务执行")?;
        existing = env.new_object(
            jni_str!("android/app/NotificationChannel"),
            jni_sig!("(Ljava/lang/String;Ljava/lang/CharSequence;I)V"),
            &[JValue::Object(&id), JValue::Object(&title), JValue::Int(2)],
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
    Ok(env
        .call_method(&existing, jni_str!("getImportance"), jni_sig!("()I"), &[])?
        .i()?
        > 0)
}

fn notification(
    env: &mut Env,
    context: &Global<JObject<'static>>,
    title: &str,
    text: &str,
    key: Option<&RunKey>,
) -> jni::errors::Result<Global<JObject<'static>>> {
    let id = env.new_string(CHANNEL)?;
    let builder = env.new_object(
        jni_str!("android/app/Notification$Builder"),
        jni_sig!("(Landroid/content/Context;Ljava/lang/String;)V"),
        &[JValue::Object(context), JValue::Object(&id)],
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
    for (method, value) in [
        (jni_str!("setContentTitle"), title),
        (jni_str!("setContentText"), text),
    ] {
        let value = env.new_string(value)?;
        env.call_method(
            &builder,
            method,
            jni_sig!("(Ljava/lang/CharSequence;)Landroid/app/Notification$Builder;"),
            &[JValue::Object(&value)],
        )?;
    }
    for method in [jni_str!("setOngoing"), jni_str!("setOnlyAlertOnce")] {
        env.call_method(
            &builder,
            method,
            jni_sig!("(Z)Landroid/app/Notification$Builder;"),
            &[JValue::Bool(true)],
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
    let open = pending(env, context, key, false)?;
    env.call_method(
        &builder,
        jni_str!("setContentIntent"),
        jni_sig!("(Landroid/app/PendingIntent;)Landroid/app/Notification$Builder;"),
        &[JValue::Object(&open)],
    )?;
    if let Some(key) = key {
        let cancel = pending(env, context, Some(key), true)?;
        let label = env.new_string("停止任务")?;
        env.call_method(&builder,jni_str!("addAction"),jni_sig!("(ILjava/lang/CharSequence;Landroid/app/PendingIntent;)Landroid/app/Notification$Builder;"),&[JValue::Int(icon),JValue::Object(&label),JValue::Object(&cancel)])?;
    }
    let value = env
        .call_method(
            builder,
            jni_str!("build"),
            jni_sig!("()Landroid/app/Notification;"),
            &[],
        )?
        .l()?;
    env.new_global_ref(value)
}

fn primary(snapshot: &ServiceSnapshot) -> (String, String) {
    let waiting: usize = snapshot.runs.iter().map(|run| run.pending_approvals).sum();
    (
        format!("JAgent · {}项任务", snapshot.runs.len()),
        if waiting > 0 {
            format!("{waiting}项请求等待批准")
        } else {
            "正在执行任务".into()
        },
    )
}

pub fn request_start() -> Result<(), String> {
    let context = android::application_context()?;
    invoke(|env| {
        let intent = intent(
            env,
            context.as_ref(),
            SERVICE_CLASS,
            ACTION_REFRESH,
            None,
            None,
        )?;
        let component = env
            .call_method(
                context.as_ref(),
                jni_str!("startForegroundService"),
                jni_sig!("(Landroid/content/Intent;)Landroid/content/ComponentName;"),
                &[JValue::Object(&intent)],
            )?
            .l()?;
        if component.is_null() {
            return Err(jni::errors::Error::NullPtr("foreground service component"));
        }
        Ok(())
    })
}

pub fn promote(
    instance: &Global<JObject<'static>>,
    snapshot: &ServiceSnapshot,
) -> Result<bool, String> {
    let context = android::application_context()?;
    let sdk = android::paths().ok_or("Android平台路径尚未初始化")?.sdk_int;
    invoke(|env| {
        let manager = service(env, context.as_ref(), "notification")?;
        let channel_allowed = channel(env, &manager)?;
        let (title, text) = primary(snapshot);
        let notification = notification(env, context.as_ref(), &title, &text, None)?;
        if sdk >= 29 {
            env.call_method(
                instance,
                jni_str!("startForeground"),
                jni_sig!("(ILandroid/app/Notification;I)V"),
                &[
                    JValue::Int(FOREGROUND_ID),
                    JValue::Object(&notification),
                    JValue::Int(1),
                ],
            )?;
            let kind = env
                .call_method(
                    instance,
                    jni_str!("getForegroundServiceType"),
                    jni_sig!("()I"),
                    &[],
                )?
                .i()?;
            if kind & 1 == 0 {
                return Err(jni::errors::Error::NullPtr(
                    "dataSync promotion not confirmed",
                ));
            }
        } else {
            env.call_method(
                instance,
                jni_str!("startForeground"),
                jni_sig!("(ILandroid/app/Notification;)V"),
                &[JValue::Int(FOREGROUND_ID), JValue::Object(&notification)],
            )?;
        }
        Ok(channel_allowed
            && env
                .call_method(
                    &manager,
                    jni_str!("areNotificationsEnabled"),
                    jni_sig!("()Z"),
                    &[],
                )?
                .z()?)
    })
}

pub fn update(
    instance: &Global<JObject<'static>>,
    snapshot: &ServiceSnapshot,
    previous: &HashSet<String>,
) -> Result<bool, String> {
    let context = android::application_context()?;
    invoke(|env| {
        let manager = service(env, context.as_ref(), "notification")?;
        let allowed = channel(env, &manager)?
            && env
                .call_method(
                    &manager,
                    jni_str!("areNotificationsEnabled"),
                    jni_sig!("()Z"),
                    &[],
                )?
                .z()?;
        let (title, text) = primary(snapshot);
        let summary = notification(env, context.as_ref(), &title, &text, None)?;
        env.call_method(
            &manager,
            jni_str!("notify"),
            jni_sig!("(ILandroid/app/Notification;)V"),
            &[JValue::Int(FOREGROUND_ID), JValue::Object(&summary)],
        )?;
        let ids: HashSet<_> = snapshot
            .runs
            .iter()
            .map(|run| super::notification_tag(&run.key))
            .collect();
        for id in previous.iter().filter(|id| !ids.contains(*id)) {
            cancel_notification(env, &manager, id)?;
        }
        for run in &snapshot.runs {
            // Chat titles may have been derived from a prompt. Never expose
            // them through the service notification data channel.
            let id = &run.key.run_id[run.key.run_id.len().saturating_sub(8)..];
            let title = format!("JAgent · 任务{id}");
            let text = match run.state {
                ServiceRunState::WaitingApproval => {
                    format!("{}项请求等待批准", run.pending_approvals)
                }
                ServiceRunState::WaitingStorage => "等待保存请求账单".into(),
                ServiceRunState::Cancelling => "正在停止".into(),
                ServiceRunState::Running => format!("执行中 · {}项子任务", run.active_children),
            };
            let notification = notification(env, context.as_ref(), &title, &text, Some(&run.key))?;
            let tag = env.new_string(super::notification_tag(&run.key))?;
            env.call_method(
                &manager,
                jni_str!("notify"),
                jni_sig!("(Ljava/lang/String;ILandroid/app/Notification;)V"),
                &[
                    JValue::Object(&tag),
                    JValue::Int(RUN_ID),
                    JValue::Object(&notification),
                ],
            )?;
        }
        let _ = instance;
        Ok(allowed)
    })
}

fn cancel_notification(
    env: &mut Env,
    manager: &Global<JObject<'static>>,
    id: &str,
) -> jni::errors::Result<()> {
    let tag = env.new_string(id)?;
    env.call_method(
        manager,
        jni_str!("cancel"),
        jni_sig!("(Ljava/lang/String;I)V"),
        &[JValue::Object(&tag), JValue::Int(RUN_ID)],
    )?;
    Ok(())
}

pub fn clear_notifications(ids: &HashSet<String>) -> Result<(), String> {
    let context = android::application_context()?;
    let manager = invoke(|env| service(env, context.as_ref(), "notification"))?;
    let mut first_error = None;
    for id in ids {
        if let Err(error) = invoke(|env| cancel_notification(env, &manager, id)) {
            first_error.get_or_insert(error);
        }
    }
    // The primary notification belongs to Service.stopForeground; worker
    // cleanup must not remove the primary card of a newly promoted service.
    first_error.map_or(Ok(()), Err)
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
    // A foreground removal error must never skip the stopSelf deadline path.
    let requested = invoke(|env| {
        env.call_method(instance, jni_str!("stopSelf"), jni_sig!("()V"), &[])?;
        Ok(())
    });
    StopOutcome {
        foreground_removed: foreground.is_ok(),
        stop_requested: requested.is_ok(),
        errors: foreground
            .err()
            .into_iter()
            .map(|error| format!("前台移除：{error}"))
            .chain(
                requested
                    .err()
                    .into_iter()
                    .map(|error| format!("停止请求：{error}")),
            )
            .collect(),
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

pub fn stop_component() -> Result<bool, String> {
    let context = android::application_context()?;
    invoke(|env| {
        let intent = intent(
            env,
            context.as_ref(),
            SERVICE_CLASS,
            ACTION_REFRESH,
            None,
            None,
        )?;
        env.call_method(
            context.as_ref(),
            jni_str!("stopService"),
            jni_sig!("(Landroid/content/Intent;)Z"),
            &[JValue::Object(&intent)],
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

pub fn create_wake() -> Result<Object, String> {
    let context = android::application_context()?;
    invoke(|env| {
        let power = service(env, context.as_ref(), "power")?;
        let tag = env.new_string("jagent-rust:agent-run")?;
        let lock = env
            .call_method(
                power,
                jni_str!("newWakeLock"),
                jni_sig!("(ILjava/lang/String;)Landroid/os/PowerManager$WakeLock;"),
                &[JValue::Int(1), JValue::Object(&tag)],
            )?
            .l()?;
        env.new_global_ref(lock).map(Arc::new)
    })
}

pub fn acquire_wake(lock: &Global<JObject<'static>>) -> Result<(), String> {
    invoke(|env| {
        env.call_method(
            lock,
            jni_str!("setReferenceCounted"),
            jni_sig!("(Z)V"),
            &[JValue::Bool(false)],
        )?;
        env.call_method(
            lock,
            jni_str!("acquire"),
            jni_sig!("(J)V"),
            &[JValue::Long(60_000)],
        )?;
        if !env
            .call_method(lock, jni_str!("isHeld"), jni_sig!("()Z"), &[])?
            .z()?
        {
            return Err(jni::errors::Error::NullPtr("wake lease not held"));
        }
        Ok(())
    })
}

pub fn release_wake(lock: &Global<JObject<'static>>) -> Result<(), String> {
    invoke(|env| {
        if env
            .call_method(lock, jni_str!("isHeld"), jni_sig!("()Z"), &[])?
            .z()?
        {
            env.call_method(lock, jni_str!("release"), jni_sig!("()V"), &[])?;
        }
        Ok(())
    })
}
