use super::permissions::OperationKey;
use crate::network::CancelToken;
use std::sync::Arc;
#[derive(Clone, Copy, Debug)]
pub enum Page {
    Accessibility,
    Overlay,
    AppPermissions,
    Shizuku,
    AllFilesAccess,
    BatteryOptimization,
}
pub fn open(operation: &OperationKey, page: Page, cancel: Arc<CancelToken>) -> Result<(), String> {
    operation.validate()?;
    #[cfg(not(target_os = "android"))]
    {
        let _ = (page, cancel);
        Err("当前平台没有Android系统设置入口".into())
    }
    #[cfg(target_os = "android")]
    {
        super::main_call::run(operation, cancel, move || device(page))
    }
}
#[cfg(target_os = "android")]
fn device(page: Page) -> Result<(), String> {
    use crate::platform::android;
    use jni::{JValue, jni_sig, jni_str};
    if !android::activity_visible() {
        return Err("系统设置需要由JAgent当前可见页面打开".into());
    }
    if matches!(page, Page::AllFilesAccess)
        && !android::paths().is_some_and(|paths| paths.sdk_int >= 30)
    {
        return Err("所有文件访问设置仅适用于Android 11及以上，请申请当前版本的存储权限".into());
    }
    android::invoke(|env| {
        let context = android::application_context()
            .map_err(|_| jni::errors::Error::NullPtr("settings context"))?;
        let pm = env
            .call_method(
                context.as_ref(),
                jni_str!("getPackageManager"),
                jni_sig!("()Landroid/content/pm/PackageManager;"),
                &[],
            )?
            .l()?;
        let mut candidates = Vec::new();
        if matches!(page, Page::Shizuku) {
            let package = env.new_string(crate::platform::shizuku::MANAGER_PACKAGE)?;
            candidates.push(
                env.call_method(
                    &pm,
                    jni_str!("getLaunchIntentForPackage"),
                    jni_sig!("(Ljava/lang/String;)Landroid/content/Intent;"),
                    &[JValue::Object(&package)],
                )?
                .l()?,
            );
        } else {
            let actions: &[(&str, bool)] = match page {
                Page::Accessibility => &[("android.settings.ACCESSIBILITY_SETTINGS", false)],
                Page::Overlay => &[("android.settings.action.MANAGE_OVERLAY_PERMISSION", true)],
                Page::AppPermissions => &[("android.settings.APPLICATION_DETAILS_SETTINGS", true)],
                Page::AllFilesAccess => &[
                    (
                        "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
                        true,
                    ),
                    ("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION", false),
                ],
                Page::BatteryOptimization => &[
                    (
                        "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                        true,
                    ),
                    (
                        "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
                        false,
                    ),
                ],
                Page::Shizuku => unreachable!(),
            };
            for (action, app_specific) in actions {
                let action = env.new_string(*action)?;
                let intent = env.new_object(
                    jni_str!("android/content/Intent"),
                    jni_sig!("(Ljava/lang/String;)V"),
                    &[JValue::Object(&action)],
                )?;
                if *app_specific {
                    let package = env
                        .call_method(
                            context.as_ref(),
                            jni_str!("getPackageName"),
                            jni_sig!("()Ljava/lang/String;"),
                            &[],
                        )?
                        .l()?;
                    let package = android::string_value(env, package, 256)?;
                    let uri = env.new_string(format!("package:{package}"))?;
                    let uri = env
                        .call_static_method(
                            jni_str!("android/net/Uri"),
                            jni_str!("parse"),
                            jni_sig!("(Ljava/lang/String;)Landroid/net/Uri;"),
                            &[JValue::Object(&uri)],
                        )?
                        .l()?;
                    env.call_method(
                        &intent,
                        jni_str!("setData"),
                        jni_sig!("(Landroid/net/Uri;)Landroid/content/Intent;"),
                        &[JValue::Object(&uri)],
                    )?;
                }
                candidates.push(intent);
            }
        }
        // Resolve all fallbacks before any startActivity effect. A launch error
        // may already have started an activity and must never trigger a retry.
        let mut selected = None;
        for intent in candidates {
            if intent.is_null() {
                continue;
            }
            let handler = env
                .call_method(
                    &intent,
                    jni_str!("resolveActivity"),
                    jni_sig!(
                        "(Landroid/content/pm/PackageManager;)Landroid/content/ComponentName;"
                    ),
                    &[JValue::Object(&pm)],
                )?
                .l()?;
            if !handler.is_null() {
                selected = Some(intent);
                break;
            }
        }
        let intent = selected.ok_or(jni::errors::Error::NullPtr("settings handler unavailable"))?;
        env.call_method(
            &intent,
            jni_str!("addFlags"),
            jni_sig!("(I)Landroid/content/Intent;"),
            &[JValue::Int(0x10000000)],
        )?;
        env.call_method(
            context.as_ref(),
            jni_str!("startActivity"),
            jni_sig!("(Landroid/content/Intent;)V"),
            &[JValue::Object(&intent)],
        )?;
        Ok(())
    })
}
