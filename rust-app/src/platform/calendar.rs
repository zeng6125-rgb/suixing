//! Android's actual java.time calendar, including the selected zone's historical
//! DST. No fixed-offset or UTC substitute is supplied for an unknown platform.
pub struct Stamp {
    pub zone_id: String,
    pub day: String,
}
pub fn zone_id() -> Result<String, String> {
    #[cfg(target_os = "android")]
    {
        use jni::{jni_sig, jni_str, objects::JString};
        super::android::invoke(|env| {
            let zone = env
                .call_static_method(
                    jni_str!("java/time/ZoneId"),
                    jni_str!("systemDefault"),
                    jni_sig!("()Ljava/time/ZoneId;"),
                    &[],
                )?
                .l()?;
            let id = env
                .call_method(
                    &zone,
                    jni_str!("getId"),
                    jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?
                .l()?;
            env.cast_local::<JString>(id)?.try_to_string(env)
        })
    }
    #[cfg(not(target_os = "android"))]
    {
        Err("当前平台未接入实际日历时区".into())
    }
}
pub fn day_for(epoch_ms: i64, zone_id: &str) -> Result<Stamp, String> {
    validate(epoch_ms, zone_id)?;
    #[cfg(target_os = "android")]
    {
        use jni::{JValue, jni_sig, jni_str, objects::JString};
        let day = super::android::invoke(|env| {
            let name = env.new_string(zone_id)?;
            let zone = env
                .call_static_method(
                    jni_str!("java/time/ZoneId"),
                    jni_str!("of"),
                    jni_sig!("(Ljava/lang/String;)Ljava/time/ZoneId;"),
                    &[JValue::Object(&name)],
                )?
                .l()?;
            let instant = env
                .call_static_method(
                    jni_str!("java/time/Instant"),
                    jni_str!("ofEpochMilli"),
                    jni_sig!("(J)Ljava/time/Instant;"),
                    &[JValue::Long(epoch_ms)],
                )?
                .l()?;
            let zoned = env
                .call_method(
                    &instant,
                    jni_str!("atZone"),
                    jni_sig!("(Ljava/time/ZoneId;)Ljava/time/ZonedDateTime;"),
                    &[JValue::Object(&zone)],
                )?
                .l()?;
            let date = env
                .call_method(
                    &zoned,
                    jni_str!("toLocalDate"),
                    jni_sig!("()Ljava/time/LocalDate;"),
                    &[],
                )?
                .l()?;
            let text = env
                .call_method(
                    &date,
                    jni_str!("toString"),
                    jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?
                .l()?;
            env.cast_local::<JString>(text)?.try_to_string(env)
        })?;
        Ok(Stamp {
            zone_id: zone_id.into(),
            day,
        })
    }
    #[cfg(not(target_os = "android"))]
    {
        Err("当前平台未接入按实际时区转换请求日期".into())
    }
}
pub fn time_label(epoch_ms: i64, zone_id: &str) -> Result<String, String> {
    validate(epoch_ms, zone_id)?;
    if epoch_ms == 0 {
        return Ok("从未整理".into());
    }
    #[cfg(target_os = "android")]
    {
        use jni::{JValue, jni_sig, jni_str, objects::JString};
        super::android::invoke(|env| {
            let name = env.new_string(zone_id)?;
            let zone = env
                .call_static_method(
                    jni_str!("java/time/ZoneId"),
                    jni_str!("of"),
                    jni_sig!("(Ljava/lang/String;)Ljava/time/ZoneId;"),
                    &[JValue::Object(&name)],
                )?
                .l()?;
            let instant = env
                .call_static_method(
                    jni_str!("java/time/Instant"),
                    jni_str!("ofEpochMilli"),
                    jni_sig!("(J)Ljava/time/Instant;"),
                    &[JValue::Long(epoch_ms)],
                )?
                .l()?;
            let zoned = env
                .call_method(
                    &instant,
                    jni_str!("atZone"),
                    jni_sig!("(Ljava/time/ZoneId;)Ljava/time/ZonedDateTime;"),
                    &[JValue::Object(&zone)],
                )?
                .l()?;
            let pattern = env.new_string("uuuu-MM-dd HH:mm:ss VV")?;
            let formatter = env
                .call_static_method(
                    jni_str!("java/time/format/DateTimeFormatter"),
                    jni_str!("ofPattern"),
                    jni_sig!("(Ljava/lang/String;)Ljava/time/format/DateTimeFormatter;"),
                    &[JValue::Object(&pattern)],
                )?
                .l()?;
            let text = env
                .call_method(
                    &zoned,
                    jni_str!("format"),
                    jni_sig!("(Ljava/time/format/DateTimeFormatter;)Ljava/lang/String;"),
                    &[JValue::Object(&formatter)],
                )?
                .l()?;
            env.cast_local::<JString>(text)?.try_to_string(env)
        })
    }
    #[cfg(not(target_os = "android"))]
    {
        Err("当前平台未接入实际本地时间显示".into())
    }
}
fn validate(epoch_ms: i64, zone: &str) -> Result<(), String> {
    if epoch_ms < 0 || zone.is_empty() || zone.len() > 128 || zone.contains('\0') {
        return Err("实际日历时间或时区无效".into());
    }
    #[cfg(target_os = "android")]
    if super::android::paths().is_none_or(|paths| paths.sdk_int < 26) {
        return Err("当前Android API不支持应用所需java.time日历".into());
    }
    Ok(())
}
