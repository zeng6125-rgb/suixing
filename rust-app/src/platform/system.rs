//! Framework facts and a single approved URL handoff; no tool policy lives here.
use crate::{network::CancelToken, platform::permissions::OperationKey};
use serde_json::Value;
use std::sync::Arc;

pub(crate) fn device_info(cancel: &CancelToken) -> Result<Value, String> {
    if cancel.is_cancelled() {
        return Err("设备信息读取已取消".into());
    }
    #[cfg(not(target_os = "android"))]
    {
        Err("当前平台不是Android设备，未推测手机信息".into())
    }
    #[cfg(target_os = "android")]
    {
        use super::android;
        use jni::{JValue, jni_sig, jni_str};
        let paths = android::paths().ok_or("Android路径未初始化")?;
        let context = android::application_context()?;
        let result = android::invoke(|env| {
            let manufacturer = env
                .get_static_field(
                    jni_str!("android/os/Build"),
                    jni_str!("MANUFACTURER"),
                    jni_sig!("Ljava/lang/String;"),
                )?
                .l()?;
            let manufacturer = android::string_value(env, manufacturer, 1024)?;
            let model = env
                .get_static_field(
                    jni_str!("android/os/Build"),
                    jni_str!("MODEL"),
                    jni_sig!("Ljava/lang/String;"),
                )?
                .l()?;
            let model = android::string_value(env, model, 1024)?;
            let release = env
                .get_static_field(
                    jni_str!("android/os/Build$VERSION"),
                    jni_str!("RELEASE"),
                    jni_sig!("Ljava/lang/String;"),
                )?
                .l()?;
            let release = android::string_value(env, release, 1024)?;
            let resources = env
                .call_method(
                    context.as_ref(),
                    jni_str!("getResources"),
                    jni_sig!("()Landroid/content/res/Resources;"),
                    &[],
                )?
                .l()?;
            let metrics = env
                .call_method(
                    &resources,
                    jni_str!("getDisplayMetrics"),
                    jni_sig!("()Landroid/util/DisplayMetrics;"),
                    &[],
                )?
                .l()?;
            let width = env
                .get_field(&metrics, jni_str!("widthPixels"), jni_sig!("I"))?
                .i()?;
            let height = env
                .get_field(&metrics, jni_str!("heightPixels"), jni_sig!("I"))?
                .i()?;
            let density = env
                .get_field(&metrics, jni_str!("densityDpi"), jni_sig!("I"))?
                .i()?;
            let manager = env
                .call_method(
                    context.as_ref(),
                    jni_str!("getPackageManager"),
                    jni_sig!("()Landroid/content/pm/PackageManager;"),
                    &[],
                )?
                .l()?;
            let package = env.new_string(&paths.package_name)?;
            let info = env
                .call_method(
                    &manager,
                    jni_str!("getPackageInfo"),
                    jni_sig!("(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;"),
                    &[JValue::Object(&package), JValue::Int(0)],
                )?
                .l()?;
            let version = env
                .get_field(
                    &info,
                    jni_str!("versionName"),
                    jni_sig!("Ljava/lang/String;"),
                )?
                .l()?;
            let version = if version.is_null() {
                None
            } else {
                Some(android::string_value(env, version, 256)?)
            };
            let directory = env.new_string(paths.files_dir.to_string_lossy())?;
            let stat = env.new_object(
                jni_str!("android/os/StatFs"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[JValue::Object(&directory)],
            )?;
            let available = env
                .call_method(&stat, jni_str!("getAvailableBytes"), jni_sig!("()J"), &[])?
                .j()?;
            let total = env
                .call_method(&stat, jni_str!("getTotalBytes"), jni_sig!("()J"), &[])?
                .j()?;
            let battery_name = env.new_string("batterymanager")?;
            let battery = env
                .call_method(
                    context.as_ref(),
                    jni_str!("getSystemService"),
                    jni_sig!("(Ljava/lang/String;)Ljava/lang/Object;"),
                    &[JValue::Object(&battery_name)],
                )?
                .l()?;
            let capacity = if battery.is_null() {
                None
            } else {
                let capacity = env
                    .call_method(
                        &battery,
                        jni_str!("getIntProperty"),
                        jni_sig!("(I)I"),
                        &[JValue::Int(4)],
                    )?
                    .i()?;
                (0..=100).contains(&capacity).then_some(capacity)
            };
            let time = env.new_object(jni_str!("java/util/Date"), jni_sig!("()V"), &[])?;
            let pattern = env.new_string("yyyy-MM-dd HH:mm:ss EEEE")?;
            let format = env.new_object(
                jni_str!("java/text/SimpleDateFormat"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[JValue::Object(&pattern)],
            )?;
            let local_time = env
                .call_method(
                    &format,
                    jni_str!("format"),
                    jni_sig!("(Ljava/util/Date;)Ljava/lang/String;"),
                    &[JValue::Object(&time)],
                )?
                .l()?;
            let local_time = android::string_value(env, local_time, 256)?;
            Ok(
                serde_json::json!({"ok":true,"manufacturer":manufacturer,"model":model,"android_release":release,"api_level":paths.sdk_int,"screen":{"width":width,"height":height,"density_dpi":density},"local_time":local_time,"battery_percent":capacity,"storage":{"available_bytes":(available>=0).then_some(available),"total_bytes":(total>=0).then_some(total)},"application":{"package":paths.package_name,"version":version}}),
            )
        })?;
        if cancel.is_cancelled() {
            return Err("设备信息读取已取消，未发布旧结果".into());
        }
        Ok(result)
    }
}
pub(crate) fn open_url(
    operation: &OperationKey,
    url: &str,
    cancel: Arc<CancelToken>,
) -> Result<(), String> {
    operation.validate()?;
    #[cfg(not(target_os = "android"))]
    {
        let _ = (url, cancel);
        Err("当前平台没有Android网址处理器".into())
    }
    #[cfg(target_os = "android")]
    {
        let url = url.to_owned();
        super::main_call::run(operation, cancel, move || {
            use super::android;
            use jni::{JValue, jni_sig, jni_str};
            let context = android::application_context()?;
            android::invoke(|env| {
                let value = env.new_string(&url)?;
                let uri = env
                    .call_static_method(
                        jni_str!("android/net/Uri"),
                        jni_str!("parse"),
                        jni_sig!("(Ljava/lang/String;)Landroid/net/Uri;"),
                        &[JValue::Object(&value)],
                    )?
                    .l()?;
                let action = env.new_string("android.intent.action.VIEW")?;
                let intent = env.new_object(
                    jni_str!("android/content/Intent"),
                    jni_sig!("(Ljava/lang/String;Landroid/net/Uri;)V"),
                    &[JValue::Object(&action), JValue::Object(&uri)],
                )?;
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
        })
    }
}
