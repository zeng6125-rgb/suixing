use eframe::egui;

#[cfg(target_os = "android")]
use super::android;
#[cfg(target_os = "android")]
use jni::{Env, JValue, jni_sig, jni_str, objects::JObject, refs::Global};
#[cfg(target_os = "android")]
use std::sync::{Arc, Mutex};

pub(crate) struct BackRequest {
    pub(crate) keyboard_visible: bool,
    #[cfg(target_os = "android")]
    activity: Arc<Global<JObject<'static>>>,
}

#[cfg(target_os = "android")]
static BACK: Mutex<(Option<egui::Context>, Option<BackRequest>)> = Mutex::new((None, None));

pub(crate) fn install(context: egui::Context) {
    #[cfg(target_os = "android")]
    {
        *BACK.lock().unwrap_or_else(|error| error.into_inner()) = (Some(context), None);
    }
    #[cfg(not(target_os = "android"))]
    let _ = context;
}

#[cfg(target_os = "android")]
pub(crate) fn dispatch_key(
    env: &mut Env,
    activity: &JObject,
    event: &JObject,
) -> jni::errors::Result<bool> {
    if event.is_null() {
        return Ok(false);
    }
    let key = env
        .call_method(event, jni_str!("getKeyCode"), jni_sig!("()I"), &[])?
        .i()?;
    if key != 4 {
        return Ok(false);
    }
    let action = env
        .call_method(event, jni_str!("getAction"), jni_sig!("()I"), &[])?
        .i()?;
    if action == 1
        && !env
            .call_method(event, jni_str!("isCanceled"), jni_sig!("()Z"), &[])?
            .z()?
    {
        request(env, activity)?;
    }
    Ok(true)
}

#[cfg(target_os = "android")]
pub(crate) fn request(env: &mut Env, activity: &JObject) -> jni::errors::Result<()> {
    let Some(current) = android::current_activity() else {
        return Ok(());
    };
    if !android::activity_visible() || !env.is_same_object(current.as_ref().as_ref(), activity)? {
        return Ok(());
    }
    let keyboard_visible = if android::paths().is_some_and(|paths| paths.sdk_int >= 30) {
        let decor = decor(env, activity)?;
        let insets = env
            .call_method(
                &decor,
                jni_str!("getRootWindowInsets"),
                jni_sig!("()Landroid/view/WindowInsets;"),
                &[],
            )?
            .l()?;
        if insets.is_null() {
            false
        } else {
            let ime = env
                .call_static_method(
                    jni_str!("android/view/WindowInsets$Type"),
                    jni_str!("ime"),
                    jni_sig!("()I"),
                    &[],
                )?
                .i()?;
            env.call_method(
                &insets,
                jni_str!("isVisible"),
                jni_sig!("(I)Z"),
                &[JValue::Int(ime)],
            )?
            .z()?
        }
    } else {
        // On older Android, the IME consumes Back before Activity.onBackPressed.
        false
    };
    let mut back = BACK.lock().unwrap_or_else(|error| error.into_inner());
    back.1 = Some(BackRequest {
        activity: current,
        keyboard_visible,
    });
    if let Some(context) = &back.0 {
        context.request_repaint();
    }
    Ok(())
}

pub(crate) fn take_back() -> Option<BackRequest> {
    #[cfg(target_os = "android")]
    {
        BACK.lock()
            .unwrap_or_else(|error| error.into_inner())
            .1
            .take()
            .filter(|request| {
                android::current_activity().is_some_and(|current| {
                    Arc::ptr_eq(&current, &request.activity) && android::activity_visible()
                })
            })
    }
    #[cfg(not(target_os = "android"))]
    {
        None
    }
}

impl BackRequest {
    pub(crate) fn hide_keyboard(&self) -> Result<(), String> {
        #[cfg(target_os = "android")]
        {
            let activity = self.activity.clone();
            android::post_to_main(move || {
                if !android::current_activity()
                    .is_some_and(|current| Arc::ptr_eq(&current, &activity))
                {
                    return;
                }
                let _ = android::invoke(|env| {
                    let decor = decor(env, activity.as_ref().as_ref())?;
                    let token = env
                        .call_method(
                            &decor,
                            jni_str!("getWindowToken"),
                            jni_sig!("()Landroid/os/IBinder;"),
                            &[],
                        )?
                        .l()?;
                    let name = env.new_string("input_method")?;
                    let manager = env
                        .call_method(
                            activity.as_ref().as_ref(),
                            jni_str!("getSystemService"),
                            jni_sig!("(Ljava/lang/String;)Ljava/lang/Object;"),
                            &[JValue::Object(&name)],
                        )?
                        .l()?;
                    env.call_method(
                        &manager,
                        jni_str!("hideSoftInputFromWindow"),
                        jni_sig!("(Landroid/os/IBinder;I)Z"),
                        &[JValue::Object(&token), JValue::Int(0)],
                    )?;
                    Ok(())
                });
            })
        }
        #[cfg(not(target_os = "android"))]
        {
            Ok(())
        }
    }
}

pub(crate) fn background() -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        let Some(activity) = android::current_activity() else {
            return Ok(());
        };
        android::post_to_main(move || {
            if !android::current_activity().is_some_and(|current| Arc::ptr_eq(&current, &activity))
            {
                return;
            }
            let _ = android::invoke(|env| {
                env.call_method(
                    activity.as_ref().as_ref(),
                    jni_str!("moveTaskToBack"),
                    jni_sig!("(Z)Z"),
                    &[JValue::Bool(true)],
                )?;
                Ok(())
            });
        })
    }
    #[cfg(not(target_os = "android"))]
    {
        Ok(())
    }
}

#[cfg(target_os = "android")]
fn decor<'local>(
    env: &mut Env<'local>,
    activity: &JObject,
) -> jni::errors::Result<JObject<'local>> {
    let window = env
        .call_method(
            activity,
            jni_str!("getWindow"),
            jni_sig!("()Landroid/view/Window;"),
            &[],
        )?
        .l()?;
    env.call_method(
        &window,
        jni_str!("getDecorView"),
        jni_sig!("()Landroid/view/View;"),
        &[],
    )?
    .l()
}
