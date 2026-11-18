use super::*;
pub(super) fn click_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let name = format!("{prefix}/NativeFloatClick");
    let mut class = Class::new(&name, "java/lang/Object")?;
    class
        .interfaces
        .push(class.pool.class("android/view/View$OnClickListener")?);
    for (name, descriptor) in [("handle", "J"), ("action", "I")] {
        let name = class.pool.utf8(name)?;
        let descriptor = class.pool.utf8(descriptor)?;
        class.fields.push((PRIVATE | FINAL, name, descriptor));
    }
    let handle = class.pool.member(&name, "handle", "J", true)?;
    let action = class.pool.member(&name, "action", "I", true)?;
    let parent = class
        .pool
        .member("java/lang/Object", "<init>", "()V", false)?;
    let native = class.pool.member(
        bridge,
        "nativeConsoleAccessibility",
        "(JLandroid/view/accessibility/AccessibilityNodeInfo;I)Z",
        false,
    )?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb7, parent);
    code.extend_from_slice(&[0x2a, 0x1f]);
    invoke(&mut code, 0xb5, handle);
    code.extend_from_slice(&[0x2a, 0x1d]);
    invoke(&mut code, 0xb5, action);
    code.push(0xb1);
    class.method(PUBLIC, "<init>", "(JI)V", 3, 4, code)?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb4, handle);
    code.extend_from_slice(&[0x01, 0x2a]);
    invoke(&mut code, 0xb4, action);
    invoke(&mut code, 0xb8, native);
    code.extend_from_slice(&[0x57, 0xb1]);
    class.method(PUBLIC, "onClick", "(Landroid/view/View;)V", 4, 2, code)?;
    Ok(class)
}
pub(super) fn service_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let parent = format!("{prefix}/RustService");
    let mut class = Class::new(&format!("{prefix}/RustFloatConsoleService"), &parent)?;
    class.constructor(&parent)?;
    let superclass = class.pool.member(
        &parent,
        "onConfigurationChanged",
        "(Landroid/content/res/Configuration;)V",
        false,
    )?;
    let native = class.pool.member(
        bridge,
        "nativeServiceEvent",
        "(ILandroid/app/Service;Landroid/content/Intent;II)I",
        false,
    )?;
    let mut code = vec![0x2a, 0x2b];
    invoke(&mut code, 0xb7, superclass);
    code.extend_from_slice(&[0x10, 0x06, 0x2a, 0x01, 0x03, 0x03]);
    invoke(&mut code, 0xb8, native);
    code.extend_from_slice(&[0x57, 0xb1]);
    class.method(
        PUBLIC,
        "onConfigurationChanged",
        "(Landroid/content/res/Configuration;)V",
        5,
        2,
        code,
    )?;
    Ok(class)
}
pub(super) fn class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let name = format!("{prefix}/NativeFloatConsole");
    let mut class = Class::new(&name, "android/widget/FrameLayout")?;
    let field_name = class.pool.utf8("handle")?;
    let descriptor = class.pool.utf8("J")?;
    class.fields.push((PRIVATE | FINAL, field_name, descriptor));
    let field = class.pool.member(&name, "handle", "J", true)?;
    let parent = class.pool.member(
        "android/widget/FrameLayout",
        "<init>",
        "(Landroid/content/Context;)V",
        false,
    )?;
    let mut code = vec![0x2a, 0x2b];
    invoke(&mut code, 0xb7, parent);
    code.extend_from_slice(&[0x2a, 0x20]);
    invoke(&mut code, 0xb5, field);
    code.push(0xb1);
    class.method(
        PUBLIC,
        "<init>",
        "(Landroid/content/Context;J)V",
        3,
        4,
        code,
    )?;
    for (method, native, descriptor, return_op) in [
        (
            "onDraw",
            "nativeConsoleDraw",
            "(Landroid/graphics/Canvas;)V",
            0xb1,
        ),
        (
            "onTouchEvent",
            "nativeConsoleTouch",
            "(Landroid/view/MotionEvent;)Z",
            0xac,
        ),
    ] {
        let native_descriptor = format!("(J{}", &descriptor[1..]);
        let native = class
            .pool
            .member(bridge, native, &native_descriptor, false)?;
        let mut code = vec![0x2a];
        invoke(&mut code, 0xb4, field);
        code.push(0x2b);
        invoke(&mut code, 0xb8, native);
        code.push(return_op);
        class.method(PUBLIC, method, descriptor, 3, 2, code)?;
    }
    let native = class.pool.member(
        bridge,
        "nativeConsoleAccessibility",
        "(JLandroid/view/accessibility/AccessibilityNodeInfo;I)Z",
        false,
    )?;
    let initialize = class.pool.member(
        "android/widget/FrameLayout",
        "onInitializeAccessibilityNodeInfo",
        "(Landroid/view/accessibility/AccessibilityNodeInfo;)V",
        false,
    )?;
    let mut code = vec![0x2a, 0x2b];
    invoke(&mut code, 0xb7, initialize);
    code.push(0x2a);
    invoke(&mut code, 0xb4, field);
    code.extend_from_slice(&[0x2b, 0x02]);
    invoke(&mut code, 0xb8, native);
    code.extend_from_slice(&[0x57, 0xb1]);
    class.method(
        PUBLIC,
        "onInitializeAccessibilityNodeInfo",
        "(Landroid/view/accessibility/AccessibilityNodeInfo;)V",
        4,
        2,
        code,
    )?;
    let action = class.pool.member(
        "android/widget/FrameLayout",
        "performAccessibilityAction",
        "(ILandroid/os/Bundle;)Z",
        false,
    )?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb4, field);
    code.extend_from_slice(&[0x01, 0x1b]);
    invoke(&mut code, 0xb8, native);
    // ifeq at byte 9 branches to byte 14; true returns at byte 13.
    code.extend_from_slice(&[0x99, 0x00, 0x05, 0x04, 0xac, 0x2a, 0x1b, 0x2c]);
    invoke(&mut code, 0xb7, action);
    code.push(0xac);
    class.method(
        PUBLIC,
        "performAccessibilityAction",
        "(ILandroid/os/Bundle;)Z",
        4,
        3,
        code,
    )?;
    class
        .methods
        .last_mut()
        .ok_or("missing float accessibility action")?
        .stack_map = Some(vec![0x00, 0x01, 0x0e]);
    let click = class
        .pool
        .member("android/widget/FrameLayout", "performClick", "()Z", false)?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb7, click);
    code.push(0x57);
    code.push(0x2a);
    invoke(&mut code, 0xb4, field);
    code.extend_from_slice(&[0x01, 0x10, 0x10]);
    invoke(&mut code, 0xb8, native);
    code.push(0xac);
    class.method(PUBLIC, "performClick", "()Z", 4, 1, code)?;
    Ok(class)
}
