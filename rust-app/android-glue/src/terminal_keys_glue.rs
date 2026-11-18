//! Native key dispatch requires a real JVM branch and its version-52 stack map.
//! All unclaimed keys continue through NativeActivity.dispatchKeyEvent.
use super::*;
pub(super) fn attach(class: &mut Class, bridge: &str) -> Result<(), String> {
    let parent = "android/app/NativeActivity";
    let native = class.pool.member(
        bridge,
        "nativeTerminalKey",
        "(Landroid/app/Activity;Landroid/view/KeyEvent;)Z",
        false,
    )?;
    let super_key = class.pool.member(
        parent,
        "dispatchKeyEvent",
        "(Landroid/view/KeyEvent;)Z",
        false,
    )?;
    let mut code = vec![0x2a, 0x2b];
    invoke(&mut code, 0xb8, native);
    // ifeq at byte 5 branches to byte 10; true returns at byte 9.
    code.extend_from_slice(&[0x99, 0x00, 0x05, 0x04, 0xac, 0x2a, 0x2b]);
    invoke(&mut code, 0xb7, super_key);
    code.push(0xac);
    class.method(
        PUBLIC,
        "dispatchKeyEvent",
        "(Landroid/view/KeyEvent;)Z",
        2,
        2,
        code,
    )?;
    class
        .methods
        .last_mut()
        .ok_or("missing key method")?
        .stack_map = Some(vec![0x00, 0x01, 0x0a]);
    let super_focus = class
        .pool
        .member(parent, "onWindowFocusChanged", "(Z)V", false)?;
    let mut code = vec![0x2a, 0x1b];
    invoke(&mut code, 0xb7, super_focus);
    code.extend_from_slice(&[0x2a, 0x01]);
    invoke(&mut code, 0xb8, native);
    code.extend_from_slice(&[0x57, 0xb1]);
    class.method(PUBLIC, "onWindowFocusChanged", "(Z)V", 2, 2, code)
}
