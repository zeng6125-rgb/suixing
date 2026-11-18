//! PackageManager resolution runs on bounded workers. Execution receives the
//! resolved component and prepared display; no second lookup changes the target.
use super::{automation::Target, permissions::OperationKey};
use crate::network::CancelToken;
use serde::Serialize;
use std::sync::Arc;
#[derive(Clone, Debug, Serialize)]
pub struct Application {
    pub package: String,
    pub label: String,
    pub component: String,
}
pub fn own_package() -> Result<String, String> {
    #[cfg(target_os = "android")]
    {
        crate::platform::android::invoke(|env| {
            use jni::{jni_sig, jni_str};
            let context = crate::platform::android::application_context()
                .map_err(|_| jni::errors::Error::NullPtr("own package context"))?;
            let name = env
                .call_method(
                    context.as_ref(),
                    jni_str!("getPackageName"),
                    jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?
                .l()?;
            crate::platform::android::string_value(env, name, 256)
        })
    }
    #[cfg(not(target_os = "android"))]
    {
        Err("当前平台没有Android应用包名".into())
    }
}
pub fn list(query: &str, limit: usize) -> Result<Vec<Application>, String> {
    if query.len() > 1024 || query.contains('\0') || !(1..=300).contains(&limit) {
        return Err("应用列表查询参数无效".into());
    }
    #[cfg(not(target_os = "android"))]
    {
        Err("当前平台没有Android应用目录".into())
    }
    #[cfg(target_os = "android")]
    {
        device::list(query, limit)
    }
}
pub fn resolve(target: &str) -> Result<Application, String> {
    if target.trim().is_empty() || target.len() > 512 || target.contains('\0') {
        return Err("应用目标为空或超出预算".into());
    }
    #[cfg(not(target_os = "android"))]
    {
        Err("当前平台没有Android应用解析器".into())
    }
    #[cfg(target_os = "android")]
    {
        device::resolve(target)
    }
}
pub fn launch(
    operation: &OperationKey,
    target: Target,
    app: Application,
    cancel: Arc<CancelToken>,
) -> Result<(), String> {
    operation.validate()?;
    super::automation::verify_target(&target)?;
    #[cfg(not(target_os = "android"))]
    {
        let _ = (app, cancel);
        Err("当前平台没有Android应用启动器".into())
    }
    #[cfg(target_os = "android")]
    {
        super::main_call::run(operation, cancel, move || device::launch(&target, &app))
    }
}
#[cfg(target_os = "android")]
mod device {
    use super::*;
    use crate::platform::android;
    use jni::{Env, JValue, jni_sig, jni_str, objects::JObject};
    fn manager<'local>(env: &mut Env<'local>) -> jni::errors::Result<JObject<'local>> {
        let context = android::application_context()
            .map_err(|_| jni::errors::Error::NullPtr("applications context"))?;
        env.call_method(
            context.as_ref(),
            jni_str!("getPackageManager"),
            jni_sig!("()Landroid/content/pm/PackageManager;"),
            &[],
        )?
        .l()
    }
    fn app(env: &mut Env, pm: &JObject, info: &JObject) -> jni::errors::Result<Application> {
        let package = env
            .get_field(
                info,
                jni_str!("packageName"),
                jni_sig!("Ljava/lang/String;"),
            )?
            .l()?;
        let name = env
            .get_field(info, jni_str!("name"), jni_sig!("Ljava/lang/String;"))?
            .l()?;
        let package = android::string_value(env, package, 256)?;
        let name = android::string_value(env, name, 512)?;
        let component = format!("{package}/{name}");
        let label = env
            .call_method(
                info,
                jni_str!("loadLabel"),
                jni_sig!("(Landroid/content/pm/PackageManager;)Ljava/lang/CharSequence;"),
                &[JValue::Object(pm)],
            )?
            .l()?;
        let label = env
            .call_method(
                label,
                jni_str!("toString"),
                jni_sig!("()Ljava/lang/String;"),
                &[],
            )?
            .l()?;
        let label = android::string_value(env, label, 1024)?;
        Ok(Application {
            package,
            label,
            component,
        })
    }
    pub(super) fn list(query: &str, limit: usize) -> Result<Vec<Application>, String> {
        android::invoke(|env| {
            let pm = manager(env)?;
            let action = env.new_string("android.intent.action.MAIN")?;
            let launcher = env.new_object(
                jni_str!("android/content/Intent"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[JValue::Object(&action)],
            )?;
            let category = env.new_string("android.intent.category.LAUNCHER")?;
            env.call_method(
                &launcher,
                jni_str!("addCategory"),
                jni_sig!("(Ljava/lang/String;)Landroid/content/Intent;"),
                &[JValue::Object(&category)],
            )?;
            let choices = env
                .call_method(
                    &pm,
                    jni_str!("queryIntentActivities"),
                    jni_sig!("(Landroid/content/Intent;I)Ljava/util/List;"),
                    &[JValue::Object(&launcher), JValue::Int(0)],
                )?
                .l()?;
            let count = env
                .call_method(&choices, jni_str!("size"), jni_sig!("()I"), &[])?
                .i()?;
            if !(0..=2000).contains(&count) {
                return Err(jni::errors::Error::NullPtr("application inventory budget"));
            }
            let query = query.to_lowercase();
            let mut result = Vec::new();
            for index in 0..count {
                let choice = env.with_local_frame(16, |env| {
                    let choice = env
                        .call_method(
                            &choices,
                            jni_str!("get"),
                            jni_sig!("(I)Ljava/lang/Object;"),
                            &[JValue::Int(index)],
                        )?
                        .l()?;
                    let info = env
                        .get_field(
                            choice,
                            jni_str!("activityInfo"),
                            jni_sig!("Landroid/content/pm/ActivityInfo;"),
                        )?
                        .l()?;
                    app(env, &pm, &info)
                })?;
                if (query.is_empty()
                    || choice.label.to_lowercase().contains(&query)
                    || choice.package.to_lowercase().contains(&query))
                    && !result
                        .iter()
                        .any(|a: &Application| a.package == choice.package)
                {
                    result.push(choice);
                }
            }
            result.sort_by(|a, b| a.label.cmp(&b.label).then(a.package.cmp(&b.package)));
            result.truncate(limit);
            Ok(result)
        })
    }
    pub(super) fn resolve(target: &str) -> Result<Application, String> {
        let is_component = target.contains('/');
        let direct = android::invoke(|env| {
            let pm = manager(env)?;
            let target = env.new_string(target)?;
            let component = if is_component {
                env.call_static_method(
                    jni_str!("android/content/ComponentName"),
                    jni_str!("unflattenFromString"),
                    jni_sig!("(Ljava/lang/String;)Landroid/content/ComponentName;"),
                    &[JValue::Object(&target)],
                )?
                .l()?
            } else {
                let intent = env
                    .call_method(
                        &pm,
                        jni_str!("getLaunchIntentForPackage"),
                        jni_sig!("(Ljava/lang/String;)Landroid/content/Intent;"),
                        &[JValue::Object(&target)],
                    )?
                    .l()?;
                if intent.is_null() {
                    return Ok(None);
                }
                env.call_method(
                    intent,
                    jni_str!("getComponent"),
                    jni_sig!("()Landroid/content/ComponentName;"),
                    &[],
                )?
                .l()?
            };
            if component.is_null() {
                return Ok(None);
            }
            let info = env
                .call_method(
                    &pm,
                    jni_str!("getActivityInfo"),
                    jni_sig!("(Landroid/content/ComponentName;I)Landroid/content/pm/ActivityInfo;"),
                    &[JValue::Object(&component), JValue::Int(0)],
                )?
                .l()?;
            app(env, &pm, &info).map(Some)
        })?;
        if let Some(app) = direct {
            return Ok(app);
        }
        let choices = list(target, 300)?;
        let exact = choices
            .iter()
            .filter(|a| a.label.eq_ignore_ascii_case(target) || a.package == target)
            .collect::<Vec<_>>();
        if exact.len() == 1 {
            return Ok(exact[0].clone());
        }
        if exact.len() > 1 || choices.len() > 1 {
            return Err("多个应用匹配，请用完整包名或组件固定目标".into());
        }
        choices
            .into_iter()
            .next()
            .ok_or("没有找到可启动应用，请先用list_apps查询".into())
    }
    pub(super) fn launch(target: &Target, app: &Application) -> Result<(), String> {
        super::super::automation::verify_target(target)?;
        if !android::activity_visible() {
            return Err("应用启动需要JAgent当前可见页面".into());
        }
        android::invoke(|env| {
            let context = android::application_context()
                .map_err(|_| jni::errors::Error::NullPtr("launch context"))?;
            let package = env
                .call_method(
                    context.as_ref(),
                    jni_str!("getPackageName"),
                    jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?
                .l()?;
            let package = android::string_value(env, package, 256)?;
            if target.display_id != 0 && app.package != package {
                return Err(jni::errors::Error::NullPtr(
                    "third-party private display launch requires explicit root route",
                ));
            }
            let name = env.new_string(&app.component)?;
            let component = env
                .call_static_method(
                    jni_str!("android/content/ComponentName"),
                    jni_str!("unflattenFromString"),
                    jni_sig!("(Ljava/lang/String;)Landroid/content/ComponentName;"),
                    &[JValue::Object(&name)],
                )?
                .l()?;
            if component.is_null() {
                return Err(jni::errors::Error::NullPtr(
                    "prepared application component invalid",
                ));
            }
            let intent =
                env.new_object(jni_str!("android/content/Intent"), jni_sig!("()V"), &[])?;
            env.call_method(
                &intent,
                jni_str!("setComponent"),
                jni_sig!("(Landroid/content/ComponentName;)Landroid/content/Intent;"),
                &[JValue::Object(&component)],
            )?;
            env.call_method(
                &intent,
                jni_str!("addFlags"),
                jni_sig!("(I)Landroid/content/Intent;"),
                &[JValue::Int(
                    0x10000000
                        | if target.display_id != 0 {
                            0x08000000
                        } else {
                            0
                        },
                )],
            )?;
            let options = env
                .call_static_method(
                    jni_str!("android/app/ActivityOptions"),
                    jni_str!("makeBasic"),
                    jni_sig!("()Landroid/app/ActivityOptions;"),
                    &[],
                )?
                .l()?;
            env.call_method(
                &options,
                jni_str!("setLaunchDisplayId"),
                jni_sig!("(I)Landroid/app/ActivityOptions;"),
                &[JValue::Int(target.display_id)],
            )?;
            let options = env
                .call_method(
                    options,
                    jni_str!("toBundle"),
                    jni_sig!("()Landroid/os/Bundle;"),
                    &[],
                )?
                .l()?;
            env.call_method(
                context.as_ref(),
                jni_str!("startActivity"),
                jni_sig!("(Landroid/content/Intent;Landroid/os/Bundle;)V"),
                &[JValue::Object(&intent), JValue::Object(&options)],
            )?;
            Ok(())
        })?;
        super::super::automation::verify_target(target)
    }
}
