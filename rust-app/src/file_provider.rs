//! The exported URI domain names immutable private snapshots, never source paths.
#[cfg(target_os = "android")]
mod device {
    use crate::platform::android;
    use jni::{
        Env, EnvUnowned, JValue, Outcome, jni_sig, jni_str,
        objects::{JClass, JObject, JObjectArray},
        sys::{jint, jobject},
    };
    use std::os::fd::{AsRawFd, IntoRawFd};
    pub(crate) unsafe extern "system" fn callback<'local>(
        mut unowned: EnvUnowned<'local>,
        _class: JClass<'local>,
        provider: JObject<'local>,
        action: jint,
        uri: JObject<'local>,
        mode: JObject<'local>,
        projection: JObjectArray<'local>,
    ) -> jobject {
        let result = unowned
            .with_env(|env| -> jni::errors::Result<_> {
                let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    provider_call(env, provider, action, uri, mode, projection)
                }));
                match result {
                    Ok(Ok(value)) => Ok(value.into_raw()),
                    Ok(Err(error)) => {
                        if env.exception_check() {
                            env.exception_clear();
                        }
                        env.throw_new(
                            if action == 0 {
                                jni_str!("java/lang/IllegalStateException")
                            } else {
                                jni_str!("java/io/FileNotFoundException")
                            },
                            jni::strings::JNIString::new(error),
                        )?;
                        Ok(std::ptr::null_mut())
                    }
                    Err(_) => {
                        if env.exception_check() {
                            env.exception_clear();
                        }
                        env.throw_new(
                            if action == 0 {
                                jni_str!("java/lang/IllegalStateException")
                            } else {
                                jni_str!("java/io/FileNotFoundException")
                            },
                            jni_str!("File snapshot operation failed"),
                        )?;
                        Ok(std::ptr::null_mut())
                    }
                }
            })
            .into_outcome();
        match result {
            Outcome::Ok(value) => value,
            _ => std::ptr::null_mut(),
        }
    }
    fn provider_call<'local>(
        env: &mut Env<'local>,
        provider: JObject<'local>,
        action: i32,
        uri: JObject<'local>,
        mode: JObject<'local>,
        projection: JObjectArray<'local>,
    ) -> Result<JObject<'local>, String> {
        if action == 0 {
            android::early_provider_init(env, &provider)
                .map_err(|_| "Provider context initialization failed")?;
            return Ok(JObject::null());
        }
        if uri.is_null() || ![1, 2, 3].contains(&action) {
            return Err("Unsupported snapshot provider request".into());
        }
        let paths = android::paths().ok_or("Snapshot provider paths unavailable")?;
        let (token, name, legacy) = (|| -> jni::errors::Result<_> {
            let context = env
                .call_method(
                    &provider,
                    jni_str!("getContext"),
                    jni_sig!("()Landroid/content/Context;"),
                    &[],
                )?
                .l()?;
            let uid = env
                .call_static_method(
                    jni_str!("android/os/Binder"),
                    jni_str!("getCallingUid"),
                    jni_sig!("()I"),
                    &[],
                )?
                .i()?;
            let pid = env
                .call_static_method(
                    jni_str!("android/os/Binder"),
                    jni_str!("getCallingPid"),
                    jni_sig!("()I"),
                    &[],
                )?
                .i()?;
            let mine = env
                .call_static_method(
                    jni_str!("android/os/Process"),
                    jni_str!("myUid"),
                    jni_sig!("()I"),
                    &[],
                )?
                .i()?;
            if uid != mine {
                let granted = env
                    .call_method(
                        &context,
                        jni_str!("checkUriPermission"),
                        jni_sig!("(Landroid/net/Uri;III)I"),
                        &[
                            JValue::Object(&uri),
                            JValue::Int(pid),
                            JValue::Int(uid),
                            JValue::Int(1),
                        ],
                    )?
                    .i()?;
                if granted != 0 {
                    return Err(jni::errors::Error::NullPtr("read URI permission denied"));
                }
            }
            let scheme = env
                .call_method(
                    &uri,
                    jni_str!("getScheme"),
                    jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?
                .l()?;
            let authority = env
                .call_method(
                    &uri,
                    jni_str!("getAuthority"),
                    jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?
                .l()?;
            if scheme.is_null() || authority.is_null() {
                return Err(jni::errors::Error::NullPtr("missing snapshot URI domain"));
            }
            let authority = android::string_value(env, authority, 256)?;
            let legacy = authority == format!("{}.filedelivery", paths.package_name);
            if android::string_value(env, scheme, 32)? != "content"
                || (!legacy && authority != format!("{}.rustfiles", paths.package_name))
            {
                return Err(jni::errors::Error::NullPtr("wrong snapshot URI domain"));
            }
            let list = env
                .call_method(
                    &uri,
                    jni_str!("getPathSegments"),
                    jni_sig!("()Ljava/util/List;"),
                    &[],
                )?
                .l()?;
            if env
                .call_method(&list, jni_str!("size"), jni_sig!("()I"), &[])?
                .i()?
                != 2
            {
                return Err(jni::errors::Error::NullPtr("snapshot URI path"));
            }
            let token = env
                .call_method(
                    &list,
                    jni_str!("get"),
                    jni_sig!("(I)Ljava/lang/Object;"),
                    &[JValue::Int(0)],
                )?
                .l()?;
            let name = env
                .call_method(
                    &list,
                    jni_str!("get"),
                    jni_sig!("(I)Ljava/lang/Object;"),
                    &[JValue::Int(1)],
                )?
                .l()?;
            Ok((
                android::string_value(env, token, 64)?,
                android::string_value(env, name, 1024)?,
                legacy,
            ))
        })()
        .map_err(|_| "Snapshot URI or permission is invalid")?;
        let (display_name, mime_type, file_bytes, file) = if legacy {
            if token != "delivery" {
                return Err("旧分享URI路径无效".into());
            }
            let (mime, bytes, file) = crate::file_export::legacy_delivery(&paths.cache_dir, &name)?;
            (name, mime, bytes, file)
        } else {
            let (snapshot, file) = crate::file_export::load(&paths.cache_dir, &token)?;
            if snapshot.name != name {
                return Err("Snapshot URI filename does not match its receipt".into());
            }
            (snapshot.name, snapshot.mime, snapshot.bytes, file)
        };
        (|| -> jni::errors::Result<JObject> {
            match action {
                1 => {
                    // Null projection uses OpenableColumns. Explicit columns
                    // preserve order and duplicates; unknown columns are null.
                    let requested = if projection.is_null() {
                        vec!["_display_name".to_owned(), "_size".to_owned()]
                    } else {
                        let count = env.get_array_length(&projection)?;
                        if !(0..=64).contains(&count) {
                            return Err(jni::errors::Error::NullPtr(
                                "projection exceeds 64 columns",
                            ));
                        }
                        let mut requested = Vec::with_capacity(count as usize);
                        for index in 0..count {
                            let column = projection.get_element(env, index as usize)?;
                            if column.is_null() {
                                return Err(jni::errors::Error::NullPtr("null projection column"));
                            }
                            let name = android::string_value(env, column, 128)?;
                            if name.is_empty() || name.contains('\0') {
                                return Err(jni::errors::Error::NullPtr(
                                    "invalid projection column",
                                ));
                            }
                            requested.push(name);
                        }
                        requested
                    };
                    let columns = env.new_object_array(
                        requested.len() as i32,
                        jni_str!("java/lang/String"),
                        JObject::null(),
                    )?;
                    for (index, name) in requested.iter().enumerate() {
                        let name = env.new_string(name)?;
                        columns.set_element(env, index, &name)?;
                    }
                    let cursor = env.new_object(
                        jni_str!("android/database/MatrixCursor"),
                        jni_sig!("([Ljava/lang/String;)V"),
                        &[JValue::Object(&columns)],
                    )?;
                    let row = env.new_object_array(
                        requested.len() as i32,
                        jni_str!("java/lang/Object"),
                        JObject::null(),
                    )?;
                    let name = env.new_string(&display_name)?;
                    let mime = env.new_string(&mime_type)?;
                    let bytes = env
                        .call_static_method(
                            jni_str!("java/lang/Long"),
                            jni_str!("valueOf"),
                            jni_sig!("(J)Ljava/lang/Long;"),
                            &[JValue::Long(file_bytes as i64)],
                        )?
                        .l()?;
                    for (index, column) in requested.iter().enumerate() {
                        match column.as_str() {
                            "_display_name" => row.set_element(env, index, &name)?,
                            "_size" => row.set_element(env, index, &bytes)?,
                            "mime_type" => row.set_element(env, index, &mime)?,
                            _ => {} // The initialized row cell stays null.
                        }
                    }
                    env.call_method(
                        &cursor,
                        jni_str!("addRow"),
                        jni_sig!("([Ljava/lang/Object;)V"),
                        &[JValue::Object(&row)],
                    )?;
                    Ok(cursor)
                }
                2 => env.new_string(mime_type).map(Into::into),
                3 => {
                    if mode.is_null() || android::string_value(env, mode, 16)? != "r" {
                        return Err(jni::errors::Error::NullPtr("read-only snapshot mode"));
                    }
                    let descriptor = env
                        .call_static_method(
                            jni_str!("android/os/ParcelFileDescriptor"),
                            jni_str!("adoptFd"),
                            jni_sig!("(I)Landroid/os/ParcelFileDescriptor;"),
                            &[JValue::Int(file.as_raw_fd())],
                        )?
                        .l()?;
                    if descriptor.is_null() {
                        return Err(jni::errors::Error::NullPtr("snapshot descriptor"));
                    }
                    let _ = file.into_raw_fd();
                    Ok(descriptor)
                }
                _ => unreachable!(),
            }
        })()
        .map_err(|_| "Snapshot provider result could not be produced".into())
    }
}
#[cfg(target_os = "android")]
pub(crate) use device::callback;
