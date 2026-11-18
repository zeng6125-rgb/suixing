//! JVM method forwarding for the read-only snapshot provider.
use super::*;
pub(super) const SIGNATURE: &str = "(Landroid/content/ContentProvider;ILandroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Object;";
pub(super) fn class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let parent = "android/content/ContentProvider";
    let mut class = Class::new(&format!("{prefix}/RustFileProvider"), parent)?;
    class.constructor(parent)?;
    let loaded = class.pool.member(bridge, "ensureLoaded", "()V", false)?;
    let native = class
        .pool
        .member(bridge, "nativeFileProvider", SIGNATURE, false)?;
    let mut init = Vec::new();
    invoke(&mut init, 0xb8, loaded);
    init.extend_from_slice(&[0x2a, 0x03, 0x01, 0x01, 0x01]);
    invoke(&mut init, 0xb8, native);
    init.extend_from_slice(&[0x57, 0x04, 0xac]);
    class.method(PUBLIC, "onCreate", "()Z", 5, 1, init)?;
    for (name, descriptor, action, locals, target, mode, projection) in [
        (
            "query",
            "(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
            1,
            6,
            "android/database/Cursor",
            0x01,
            0x2c,
        ),
        (
            "getType",
            "(Landroid/net/Uri;)Ljava/lang/String;",
            2,
            2,
            "java/lang/String",
            0x01,
            0x01,
        ),
        (
            "openFile",
            "(Landroid/net/Uri;Ljava/lang/String;)Landroid/os/ParcelFileDescriptor;",
            3,
            3,
            "android/os/ParcelFileDescriptor",
            0x2c,
            0x01,
        ),
    ] {
        let cast = class.pool.class(target)?;
        let mut code = vec![0x2a, 0x03 + action, 0x2b, mode, projection];
        invoke(&mut code, 0xb8, native);
        code.push(0xc0);
        u16be(&mut code, cast);
        code.push(0xb0);
        class.method(PUBLIC, name, descriptor, 5, locals, code)?;
    }
    let exception = class
        .pool
        .class("java/lang/UnsupportedOperationException")?;
    let ctor = class.pool.member(
        "java/lang/UnsupportedOperationException",
        "<init>",
        "()V",
        false,
    )?;
    for (name, descriptor, locals) in [
        (
            "insert",
            "(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;",
            3,
        ),
        (
            "delete",
            "(Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)I",
            4,
        ),
        (
            "update",
            "(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I",
            5,
        ),
    ] {
        let mut code = vec![0xbb];
        u16be(&mut code, exception);
        code.push(0x59);
        invoke(&mut code, 0xb7, ctor);
        code.push(0xbf);
        class.method(PUBLIC, name, descriptor, 2, locals, code)?;
    }
    Ok(class)
}
