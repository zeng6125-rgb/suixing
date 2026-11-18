//! Emits only JVM framework forwarding classes. D8 owns DEX generation.
mod file_provider_glue;
mod float_console_glue;
mod terminal_keys_glue;
use std::{
    collections::BTreeMap,
    env, fs,
    path::{Path, PathBuf},
    process,
};

const PUBLIC: u16 = 0x0001;
const PRIVATE: u16 = 0x0002;
const STATIC: u16 = 0x0008;
const FINAL: u16 = 0x0010;
const SUPER: u16 = 0x0020;
const NATIVE: u16 = 0x0100;

fn main() {
    if let Err(error) = generate() {
        eprintln!("Android glue generation failed: {error}");
        process::exit(1);
    }
}

fn generate() -> Result<(), String> {
    let mut output = None;
    let mut package = "com.suixing.agent.rust.platform".to_string();
    let mut library = "suixing_rust_app".to_string();
    let mut args = env::args().skip(1);
    while let Some(arg) = args.next() {
        let value = args.next().ok_or("each option requires a value")?;
        match arg.as_str() {
            "--output" => output = Some(PathBuf::from(value)),
            "--package" => package = value,
            "--library" => library = value,
            _ => return Err(format!("unknown option {arg}")),
        }
    }
    if !package.split('.').all(identifier) || !identifier(&library) {
        return Err("invalid package or library name".into());
    }
    let output = output.ok_or("--output is required")?;
    fs::create_dir_all(&output).map_err(|_| "cannot create output directory")?;
    let root = fs::canonicalize(&output).map_err(|_| "cannot resolve output directory")?;
    let prefix = package.replace('.', "/");
    let bridge = format!("{prefix}/Bridge");
    let mut classes = vec![
        bridge_class(&bridge, &library)?,
        application_class(&prefix, &bridge, &library)?,
        activity_class(&prefix, &bridge)?,
        runnable_class(&prefix, &bridge)?,
        service_class(&prefix, &bridge)?,
        agent_service_class(&prefix)?,
        receiver_class(&prefix, &bridge)?,
        simple_subclass(&prefix, "RustSchedulerService", "RustService")?,
        simple_subclass(&prefix, "RustTerminalService", "RustService")?,
        simple_subclass(&prefix, "RustJobService", "RustService")?,
        simple_subclass(&prefix, "RustSpeechService", "RustService")?,
        simple_subclass(&prefix, "RustLinuxService", "RustService")?,
        float_console_glue::service_class(&prefix, &bridge)?,
        simple_subclass(&prefix, "RustScheduleReceiver", "RustReceiver")?,
        simple_subclass(&prefix, "RustBootReceiver", "RustReceiver")?,
        init_listener_class(
            &prefix,
            &bridge,
            "NativeTtsInitListener",
            "android/speech/tts/TextToSpeech$OnInitListener",
            "onInit",
            "nativeTtsInit",
        )?,
        init_listener_class(
            &prefix,
            &bridge,
            "NativeAudioFocusListener",
            "android/media/AudioManager$OnAudioFocusChangeListener",
            "onAudioFocusChange",
            "nativeAudioFocusEvent",
        )?,
        tts_progress_class(&prefix, &bridge)?,
        accessibility_class(&prefix, &bridge)?,
        gesture_result_class(&prefix, &bridge)?,
        screenshot_result_class(&prefix, &bridge)?,
        overlay_view_class(&prefix, &bridge)?,
        float_console_glue::class(&prefix, &bridge)?,
        float_console_glue::click_class(&prefix, &bridge)?,
        window_insets_listener_class(&prefix, &bridge)?,
        shizuku_provider_class(&prefix, &bridge)?,
        file_provider_glue::class(&prefix, &bridge)?,
        shizuku_client_class(&prefix, &bridge)?,
        shizuku_death_class(&prefix, &bridge)?,
        binder_container_class()?,
        binder_creator_class()?,
        performance_frame_class(&prefix, &bridge)?,
        virtual_frame_class(&prefix, &bridge)?,
        virtual_display_class(&prefix, &bridge)?,
        virtual_preview_class(&prefix, &bridge)?,
        virtual_activity_class(&prefix, &bridge)?,
        ui_accessibility_view_class(&prefix, &bridge)?,
        ui_accessibility_provider_class(&prefix, &bridge)?,
    ];
    for (name, parent) in [
        (
            "com/suixing/agent/service/AgentForegroundService",
            "RustAgentService",
        ),
        (
            "com/suixing/agent/service/TerminalForegroundService",
            "RustTerminalService",
        ),
        (
            "com/suixing/agent/service/SchedulerService",
            "RustSchedulerService",
        ),
        (
            "com/suixing/agent/service/FloatConsoleService",
            "RustFloatConsoleService",
        ),
        (
            "com/suixing/agent/service/SuixingAccessibilityService",
            "RustAccessibilityService",
        ),
        ("com/suixing/agent/service/BootReceiver", "RustBootReceiver"),
        (
            "com/suixing/agent/virtualscreen/VirtualScreenActivity",
            "RustVirtualScreenActivity",
        ),
    ] {
        let parent = format!("{prefix}/{parent}");
        let mut class = Class::new(name, &parent)?;
        class.constructor(&parent)?;
        classes.push(class);
    }
    classes.sort_by(|a, b| a.name.cmp(&b.name));
    for class in classes {
        let package = class
            .name
            .rsplit_once('/')
            .ok_or("generated class has no package")?
            .0;
        let directory = checked_directory(&root, package)?;
        let name = class.name.rsplit('/').next().ok_or("invalid class name")?;
        let path = directory.join(format!("{name}.class"));
        if fs::symlink_metadata(&path).is_ok_and(|m| !m.is_file() || m.file_type().is_symlink()) {
            return Err("generated class path is not a regular file".into());
        }
        fs::write(&path, class.finish()?).map_err(|_| "cannot write generated class")?;
    }
    Ok(())
}

fn identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .enumerate()
            .all(|(i, b)| b == b'_' || b.is_ascii_alphabetic() || i > 0 && b.is_ascii_digit())
}
fn checked_directory(root: &Path, prefix: &str) -> Result<PathBuf, String> {
    let mut path = root.to_path_buf();
    for part in prefix.split('/') {
        path.push(part);
        if fs::symlink_metadata(&path).is_ok_and(|m| m.file_type().is_symlink() || !m.is_dir()) {
            return Err("generated package path redirects outside output".into());
        }
        fs::create_dir_all(&path).map_err(|_| "cannot create generated package directory")?;
    }
    let actual =
        fs::canonicalize(&path).map_err(|_| "cannot resolve generated package directory")?;
    if !actual.starts_with(root) {
        return Err("generated package escaped output root".into());
    }
    Ok(actual)
}

#[derive(Clone, PartialEq, Eq, PartialOrd, Ord)]
enum Constant {
    Utf8(String),
    Class(u16),
    String(u16),
    NameAndType(u16, u16),
    Method(u16, u16),
    Field(u16, u16),
}
#[derive(Default)]
struct Pool {
    entries: Vec<Constant>,
    indexes: BTreeMap<Constant, u16>,
}
impl Pool {
    fn intern(&mut self, value: Constant) -> Result<u16, String> {
        if let Some(&index) = self.indexes.get(&value) {
            return Ok(index);
        }
        let index = u16::try_from(self.entries.len() + 1).map_err(|_| "constant pool overflow")?;
        if index == u16::MAX {
            return Err("constant pool overflow".into());
        }
        self.entries.push(value.clone());
        self.indexes.insert(value, index);
        Ok(index)
    }
    fn utf8(&mut self, value: &str) -> Result<u16, String> {
        self.intern(Constant::Utf8(value.into()))
    }
    fn class(&mut self, value: &str) -> Result<u16, String> {
        let name = self.utf8(value)?;
        self.intern(Constant::Class(name))
    }
    fn string(&mut self, value: &str) -> Result<u16, String> {
        let text = self.utf8(value)?;
        self.intern(Constant::String(text))
    }
    fn member(
        &mut self,
        owner: &str,
        name: &str,
        descriptor: &str,
        field: bool,
    ) -> Result<u16, String> {
        let class = self.class(owner)?;
        let name = self.utf8(name)?;
        let descriptor = self.utf8(descriptor)?;
        let nat = self.intern(Constant::NameAndType(name, descriptor))?;
        self.intern(if field {
            Constant::Field(class, nat)
        } else {
            Constant::Method(class, nat)
        })
    }
    fn write(self, bytes: &mut Vec<u8>) -> Result<(), String> {
        u16be(
            bytes,
            u16::try_from(self.entries.len() + 1).map_err(|_| "constant pool overflow")?,
        );
        for entry in self.entries {
            match entry {
                Constant::Utf8(s) => {
                    if !s.is_ascii() || s.contains('\0') {
                        return Err("generator constant is not supported ASCII".into());
                    }
                    bytes.push(1);
                    u16be(
                        bytes,
                        u16::try_from(s.len()).map_err(|_| "constant too long")?,
                    );
                    bytes.extend_from_slice(s.as_bytes());
                }
                Constant::Class(a) => {
                    bytes.push(7);
                    u16be(bytes, a);
                }
                Constant::String(a) => {
                    bytes.push(8);
                    u16be(bytes, a);
                }
                Constant::Field(a, b) => {
                    bytes.push(9);
                    u16be(bytes, a);
                    u16be(bytes, b);
                }
                Constant::Method(a, b) => {
                    bytes.push(10);
                    u16be(bytes, a);
                    u16be(bytes, b);
                }
                Constant::NameAndType(a, b) => {
                    bytes.push(12);
                    u16be(bytes, a);
                    u16be(bytes, b);
                }
            }
        }
        Ok(())
    }
}
struct Method {
    flags: u16,
    name: u16,
    descriptor: u16,
    code: Option<(u16, u16, Vec<u8>)>,
    stack_map: Option<Vec<u8>>,
}
struct Class {
    name: String,
    pool: Pool,
    this: u16,
    parent: u16,
    interfaces: Vec<u16>,
    fields: Vec<(u16, u16, u16)>,
    methods: Vec<Method>,
}
impl Class {
    fn new(name: &str, parent: &str) -> Result<Self, String> {
        let mut pool = Pool::default();
        let this = pool.class(name)?;
        let parent = pool.class(parent)?;
        Ok(Self {
            name: name.into(),
            pool,
            this,
            parent,
            interfaces: Vec::new(),
            fields: Vec::new(),
            methods: Vec::new(),
        })
    }
    fn method(
        &mut self,
        flags: u16,
        name: &str,
        descriptor: &str,
        stack: u16,
        locals: u16,
        code: Vec<u8>,
    ) -> Result<(), String> {
        let name = self.pool.utf8(name)?;
        let descriptor = self.pool.utf8(descriptor)?;
        self.methods.push(Method {
            flags,
            name,
            descriptor,
            code: Some((stack, locals, code)),
            stack_map: None,
        });
        Ok(())
    }
    fn native(&mut self, name: &str, descriptor: &str) -> Result<(), String> {
        let name = self.pool.utf8(name)?;
        let descriptor = self.pool.utf8(descriptor)?;
        self.methods.push(Method {
            flags: PUBLIC | STATIC | NATIVE,
            name,
            descriptor,
            code: None,
            stack_map: None,
        });
        Ok(())
    }
    fn constructor(&mut self, parent: &str) -> Result<(), String> {
        let method = self.pool.member(parent, "<init>", "()V", false)?;
        let mut code = vec![0x2a];
        invoke(&mut code, 0xb7, method);
        code.push(0xb1);
        self.method(PUBLIC, "<init>", "()V", 1, 1, code)
    }
    fn finish(mut self) -> Result<Vec<u8>, String> {
        let code_name = self.pool.utf8("Code")?;
        let stack_map_name = self.pool.utf8("StackMapTable")?;
        let mut bytes = vec![0xca, 0xfe, 0xba, 0xbe];
        u16be(&mut bytes, 0);
        u16be(&mut bytes, 52);
        self.pool.write(&mut bytes)?;
        u16be(&mut bytes, PUBLIC | SUPER);
        u16be(&mut bytes, self.this);
        u16be(&mut bytes, self.parent);
        u16be(&mut bytes, self.interfaces.len() as u16);
        for i in self.interfaces {
            u16be(&mut bytes, i);
        }
        u16be(&mut bytes, self.fields.len() as u16);
        for (flags, name, desc) in self.fields {
            u16be(&mut bytes, flags);
            u16be(&mut bytes, name);
            u16be(&mut bytes, desc);
            u16be(&mut bytes, 0);
        }
        u16be(&mut bytes, self.methods.len() as u16);
        for method in self.methods {
            u16be(&mut bytes, method.flags);
            u16be(&mut bytes, method.name);
            u16be(&mut bytes, method.descriptor);
            if let Some((stack, locals, code)) = method.code {
                u16be(&mut bytes, 1);
                u16be(&mut bytes, code_name);
                u32be(
                    &mut bytes,
                    u32::try_from(
                        code.len() + 12 + method.stack_map.as_ref().map_or(0, |map| 6 + map.len()),
                    )
                    .map_err(|_| "method code overflow")?,
                );
                u16be(&mut bytes, stack);
                u16be(&mut bytes, locals);
                u32be(&mut bytes, code.len() as u32);
                bytes.extend_from_slice(&code);
                u16be(&mut bytes, 0);
                u16be(&mut bytes, u16::from(method.stack_map.is_some()));
                if let Some(map) = method.stack_map {
                    u16be(&mut bytes, stack_map_name);
                    u32be(&mut bytes, map.len() as u32);
                    bytes.extend_from_slice(&map);
                }
            } else {
                u16be(&mut bytes, 0);
            }
        }
        u16be(&mut bytes, 0);
        Ok(bytes)
    }
}
fn u16be(bytes: &mut Vec<u8>, v: u16) {
    bytes.extend_from_slice(&v.to_be_bytes());
}
fn u32be(bytes: &mut Vec<u8>, v: u32) {
    bytes.extend_from_slice(&v.to_be_bytes());
}
fn invoke(code: &mut Vec<u8>, op: u8, index: u16) {
    code.push(op);
    u16be(code, index);
}

fn bridge_class(name: &str, library: &str) -> Result<Class, String> {
    let mut c = Class::new(name, "java/lang/Object")?;
    let library = c.pool.string(library)?;
    let load = c.pool.member(
        "java/lang/System",
        "loadLibrary",
        "(Ljava/lang/String;)V",
        false,
    )?;
    let mut code = vec![0x13];
    u16be(&mut code, library);
    invoke(&mut code, 0xb8, load);
    code.push(0xb1);
    c.method(PUBLIC | STATIC, "ensureLoaded", "()V", 1, 0, code)?;
    for (n, d) in [
        ("nativeBootstrap", "(Landroid/content/Context;)V"),
        (
            "nativeActivityEvent",
            "(ILandroid/app/Activity;Landroid/content/Intent;II)V",
        ),
        (
            "nativeServiceEvent",
            "(ILandroid/app/Service;Landroid/content/Intent;II)I",
        ),
        (
            "nativeReceiverEvent",
            "(Landroid/content/BroadcastReceiver;Landroid/content/Context;Landroid/content/Intent;)V",
        ),
        ("nativeRun", "(J)V"),
        ("nativeFileProvider", file_provider_glue::SIGNATURE),
        (
            "nativeActivityResult",
            "(Landroid/app/Activity;IILandroid/content/Intent;)V",
        ),
        (
            "nativePermissionsResult",
            "(Landroid/app/Activity;I[Ljava/lang/String;[I)V",
        ),
        ("nativeTtsInit", "(JI)V"),
        ("nativeTtsProgress", "(JLjava/lang/String;II)V"),
        ("nativeAudioFocusEvent", "(JI)V"),
        (
            "nativeUiAccessibilityNode",
            "(JI)Landroid/view/accessibility/AccessibilityNodeInfo;",
        ),
        (
            "nativeUiAccessibilityFocus",
            "(JI)Landroid/view/accessibility/AccessibilityNodeInfo;",
        ),
        ("nativeUiAccessibilityAction", "(JIILandroid/os/Bundle;)Z"),
        (
            "nativeUiAccessibilityHover",
            "(JLandroid/view/MotionEvent;)Z",
        ),
        (
            "nativeUiAccessibilitySearch",
            "(JLjava/lang/String;I)Ljava/util/List;",
        ),
        (
            "nativeAccessibilityEvent",
            "(Landroid/accessibilityservice/AccessibilityService;I)V",
        ),
        ("nativeGestureResult", "(JZ)V"),
        ("nativeScreenshotResult", "(JLjava/lang/Object;I)V"),
        ("nativeOverlayDraw", "(JLandroid/graphics/Canvas;)V"),
        ("nativeConsoleDraw", "(JLandroid/graphics/Canvas;)V"),
        ("nativeConsoleTouch", "(JLandroid/view/MotionEvent;)Z"),
        (
            "nativeConsoleAccessibility",
            "(JLandroid/view/accessibility/AccessibilityNodeInfo;I)Z",
        ),
        (
            "nativeShizukuProviderCall",
            "(Landroid/content/ContentProvider;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;",
        ),
        (
            "nativeShizukuClient",
            "(JILandroid/os/Parcel;Landroid/os/Parcel;I)Z",
        ),
        ("nativeShizukuDeath", "(J)V"),
        (
            "nativeShizukuProviderInit",
            "(Landroid/content/ContentProvider;)V",
        ),
        ("nativePerfFrame", "(JJ)V"),
        ("nativeVirtualFrame", "(JLandroid/media/ImageReader;)V"),
        ("nativeVirtualDisplayEvent", "(JI)V"),
        ("nativeVirtualPreviewDraw", "(JLandroid/graphics/Canvas;)V"),
        (
            "nativeVirtualPreviewTouch",
            "(JLandroid/view/MotionEvent;)Z",
        ),
        ("nativeVirtualHostEvent", "(Landroid/app/Activity;I)V"),
        (
            "nativeTerminalKey",
            "(Landroid/app/Activity;Landroid/view/KeyEvent;)Z",
        ),
    ] {
        c.native(n, d)?;
    }
    Ok(c)
}
/// A transparent, non-clickable host supplies virtual nodes without taking
/// ordinary touch or keyboard input away from NativeActivity's render surface.
fn ui_accessibility_view_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let name = format!("{prefix}/NativeUiAccessibility");
    let provider = format!("{prefix}/NativeUiNodeProvider");
    let provider_descriptor = format!("L{provider};");
    let mut c = Class::new(&name, "android/view/View")?;
    for (field, descriptor) in [("handle", "J"), ("provider", provider_descriptor.as_str())] {
        let field = c.pool.utf8(field)?;
        let descriptor = c.pool.utf8(descriptor)?;
        c.fields.push((PRIVATE | FINAL, field, descriptor));
    }
    let handle = c.pool.member(&name, "handle", "J", true)?;
    let stored_provider = c
        .pool
        .member(&name, "provider", &provider_descriptor, true)?;
    let parent = c.pool.member(
        "android/view/View",
        "<init>",
        "(Landroid/content/Context;)V",
        false,
    )?;
    let provider_type = c.pool.class(&provider)?;
    let provider_init = c.pool.member(&provider, "<init>", "(J)V", false)?;
    let mut code = vec![0x2a, 0x2b];
    invoke(&mut code, 0xb7, parent);
    code.extend_from_slice(&[0x2a, 0x20]);
    invoke(&mut code, 0xb5, handle);
    code.extend_from_slice(&[0x2a, 0xbb]);
    u16be(&mut code, provider_type);
    code.extend_from_slice(&[0x59, 0x20]);
    invoke(&mut code, 0xb7, provider_init);
    invoke(&mut code, 0xb5, stored_provider);
    code.push(0xb1);
    c.method(
        PUBLIC,
        "<init>",
        "(Landroid/content/Context;J)V",
        5,
        4,
        code,
    )?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb4, stored_provider);
    code.push(0xb0);
    c.method(
        PUBLIC,
        "getAccessibilityNodeProvider",
        "()Landroid/view/accessibility/AccessibilityNodeProvider;",
        1,
        1,
        code,
    )?;
    let native = c.pool.member(
        bridge,
        "nativeUiAccessibilityHover",
        "(JLandroid/view/MotionEvent;)Z",
        false,
    )?;
    let parent_hover = c.pool.member(
        "android/view/View",
        "dispatchHoverEvent",
        "(Landroid/view/MotionEvent;)Z",
        false,
    )?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb4, handle);
    code.push(0x2b);
    invoke(&mut code, 0xb8, native);
    // ifeq at byte 8 branches to byte 13, where the ordinary View path begins.
    code.extend_from_slice(&[0x99, 0x00, 0x05, 0x04, 0xac, 0x2a, 0x2b]);
    invoke(&mut code, 0xb7, parent_hover);
    code.push(0xac);
    c.method(
        PUBLIC,
        "dispatchHoverEvent",
        "(Landroid/view/MotionEvent;)Z",
        3,
        2,
        code,
    )?;
    c.methods
        .last_mut()
        .ok_or("missing accessibility hover method")?
        .stack_map = Some(vec![0x00, 0x01, 0x0d]);
    Ok(c)
}

fn ui_accessibility_provider_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let name = format!("{prefix}/NativeUiNodeProvider");
    let parent = "android/view/accessibility/AccessibilityNodeProvider";
    let mut c = Class::new(&name, parent)?;
    let field = c.pool.utf8("handle")?;
    let descriptor = c.pool.utf8("J")?;
    c.fields.push((PRIVATE | FINAL, field, descriptor));
    let handle = c.pool.member(&name, "handle", "J", true)?;
    let super_init = c.pool.member(parent, "<init>", "()V", false)?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb7, super_init);
    code.extend_from_slice(&[0x2a, 0x1f]);
    invoke(&mut code, 0xb5, handle);
    code.push(0xb1);
    c.method(PUBLIC, "<init>", "(J)V", 3, 3, code)?;
    for (method, native) in [
        ("createAccessibilityNodeInfo", "nativeUiAccessibilityNode"),
        ("findFocus", "nativeUiAccessibilityFocus"),
    ] {
        let member = c.pool.member(
            bridge,
            native,
            "(JI)Landroid/view/accessibility/AccessibilityNodeInfo;",
            false,
        )?;
        let mut code = vec![0x2a];
        invoke(&mut code, 0xb4, handle);
        code.push(0x1b);
        invoke(&mut code, 0xb8, member);
        code.push(0xb0);
        c.method(
            PUBLIC,
            method,
            "(I)Landroid/view/accessibility/AccessibilityNodeInfo;",
            3,
            2,
            code,
        )?;
    }
    let member = c.pool.member(
        bridge,
        "nativeUiAccessibilityAction",
        "(JIILandroid/os/Bundle;)Z",
        false,
    )?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb4, handle);
    code.extend_from_slice(&[0x1b, 0x1c, 0x2d]);
    invoke(&mut code, 0xb8, member);
    code.push(0xac);
    c.method(
        PUBLIC,
        "performAction",
        "(IILandroid/os/Bundle;)Z",
        5,
        4,
        code,
    )?;
    let member = c.pool.member(
        bridge,
        "nativeUiAccessibilitySearch",
        "(JLjava/lang/String;I)Ljava/util/List;",
        false,
    )?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb4, handle);
    code.extend_from_slice(&[0x2b, 0x1c]);
    invoke(&mut code, 0xb8, member);
    code.push(0xb0);
    c.method(
        PUBLIC,
        "findAccessibilityNodeInfosByText",
        "(Ljava/lang/String;I)Ljava/util/List;",
        4,
        3,
        code,
    )?;
    Ok(c)
}

fn application_class(prefix: &str, bridge: &str, library: &str) -> Result<Class, String> {
    let mut c = Class::new(
        &format!("{prefix}/RustApplication"),
        "android/app/Application",
    )?;
    c.constructor("android/app/Application")?;
    let s = c.pool.string(library)?;
    let load = c.pool.member(
        "java/lang/System",
        "loadLibrary",
        "(Ljava/lang/String;)V",
        false,
    )?;
    let mut code = vec![0x13];
    u16be(&mut code, s);
    invoke(&mut code, 0xb8, load);
    code.push(0xb1);
    c.method(STATIC, "<clinit>", "()V", 1, 0, code)?;
    let sup = c
        .pool
        .member("android/app/Application", "onCreate", "()V", false)?;
    let native = c.pool.member(
        bridge,
        "nativeBootstrap",
        "(Landroid/content/Context;)V",
        false,
    )?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb7, sup);
    code.push(0x2a);
    invoke(&mut code, 0xb8, native);
    code.push(0xb1);
    c.method(PUBLIC, "onCreate", "()V", 1, 1, code)?;
    Ok(c)
}
fn activity_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let mut c = Class::new(
        &format!("{prefix}/RustNativeActivity"),
        "android/app/NativeActivity",
    )?;
    c.constructor("android/app/NativeActivity")?;
    terminal_keys_glue::attach(&mut c, bridge)?;
    let call = c.pool.member(
        bridge,
        "nativeActivityEvent",
        "(ILandroid/app/Activity;Landroid/content/Intent;II)V",
        false,
    )?;
    // Android Back enters the Rust navigation policy instead of finishing the
    // Activity while a popup, keyboard or unsaved settings draft is still open.
    let mut back = vec![0x10, 9, 0x2a, 0x01, 0x03, 0x03];
    invoke(&mut back, 0xb8, call);
    back.push(0xb1);
    c.method(PUBLIC, "onBackPressed", "()V", 5, 1, back)?;
    for (name, event) in [
        ("onStart", 1u8),
        ("onResume", 2),
        ("onPause", 3),
        ("onStop", 4),
        ("onDestroy", 5),
    ] {
        let sup = c
            .pool
            .member("android/app/NativeActivity", name, "()V", false)?;
        let mut code = vec![0x2a];
        invoke(&mut code, 0xb7, sup);
        code.extend_from_slice(&[0x03 + event, 0x2a, 0x01, 0x03, 0x03]);
        invoke(&mut code, 0xb8, call);
        code.push(0xb1);
        c.method(PUBLIC, name, "()V", 5, 1, code)?;
    }
    let global_layout =
        c.pool
            .member("android/app/NativeActivity", "onGlobalLayout", "()V", false)?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb7, global_layout);
    code.extend_from_slice(&[0x10, 7, 0x2a, 0x01, 0x03, 0x03]);
    invoke(&mut code, 0xb8, call);
    code.push(0xb1);
    c.method(PUBLIC, "onGlobalLayout", "()V", 5, 1, code)?;
    let configuration = "(Landroid/content/res/Configuration;)V";
    let config_changed = c.pool.member(
        "android/app/NativeActivity",
        "onConfigurationChanged",
        configuration,
        false,
    )?;
    let mut code = vec![0x2a, 0x2b];
    invoke(&mut code, 0xb7, config_changed);
    code.extend_from_slice(&[0x10, 8, 0x2a, 0x01, 0x03, 0x03]);
    invoke(&mut code, 0xb8, call);
    code.push(0xb1);
    c.method(PUBLIC, "onConfigurationChanged", configuration, 5, 2, code)?;
    let sup = c.pool.member(
        "android/app/NativeActivity",
        "onNewIntent",
        "(Landroid/content/Intent;)V",
        false,
    )?;
    let set = c.pool.member(
        "android/app/Activity",
        "setIntent",
        "(Landroid/content/Intent;)V",
        false,
    )?;
    let mut code = vec![0x2a, 0x2b];
    invoke(&mut code, 0xb7, sup);
    code.extend_from_slice(&[0x2a, 0x2b]);
    invoke(&mut code, 0xb6, set);
    code.extend_from_slice(&[0x10, 6, 0x2a, 0x2b, 0x03, 0x03]);
    invoke(&mut code, 0xb8, call);
    code.push(0xb1);
    c.method(
        PUBLIC,
        "onNewIntent",
        "(Landroid/content/Intent;)V",
        5,
        2,
        code,
    )?;
    for (name, descriptor, native_descriptor, native_name) in [
        (
            "onActivityResult",
            "(IILandroid/content/Intent;)V",
            "(Landroid/app/Activity;IILandroid/content/Intent;)V",
            "nativeActivityResult",
        ),
        (
            "onRequestPermissionsResult",
            "(I[Ljava/lang/String;[I)V",
            "(Landroid/app/Activity;I[Ljava/lang/String;[I)V",
            "nativePermissionsResult",
        ),
    ] {
        let parent = c
            .pool
            .member("android/app/NativeActivity", name, descriptor, false)?;
        let native = c
            .pool
            .member(bridge, native_name, native_descriptor, false)?;
        let loads = if name == "onActivityResult" {
            vec![0x2a, 0x1b, 0x1c, 0x2d]
        } else {
            vec![0x2a, 0x1b, 0x2c, 0x2d]
        };
        let mut code = loads.clone();
        invoke(&mut code, 0xb7, parent);
        code.extend_from_slice(&loads);
        invoke(&mut code, 0xb8, native);
        code.push(0xb1);
        c.method(PUBLIC, name, descriptor, 4, 4, code)?;
    }
    Ok(c)
}
fn window_insets_listener_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let name = format!("{prefix}/NativeInsetsListener");
    let mut class = Class::new(&name, "java/lang/Object")?;
    let interface = class
        .pool
        .class("android/view/View$OnApplyWindowInsetsListener")?;
    class.interfaces.push(interface);
    let field = class.pool.utf8("activity")?;
    let activity_desc = class.pool.utf8("Landroid/app/Activity;")?;
    class.fields.push((PRIVATE | FINAL, field, activity_desc));
    let activity = class
        .pool
        .member(&name, "activity", "Landroid/app/Activity;", true)?;
    let parent = class
        .pool
        .member("java/lang/Object", "<init>", "()V", false)?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb7, parent);
    code.extend_from_slice(&[0x2a, 0x2b]);
    invoke(&mut code, 0xb5, activity);
    code.push(0xb1);
    class.method(PUBLIC, "<init>", "(Landroid/app/Activity;)V", 2, 2, code)?;

    let default_insets = class.pool.member(
        "android/view/View",
        "onApplyWindowInsets",
        "(Landroid/view/WindowInsets;)Landroid/view/WindowInsets;",
        false,
    )?;
    let notify = class.pool.member(
        bridge,
        "nativeActivityEvent",
        "(ILandroid/app/Activity;Landroid/content/Intent;II)V",
        false,
    )?;
    // Return the normal View response. The listener only observes dispatch.
    let mut code = vec![0x2b, 0x2c];
    invoke(&mut code, 0xb6, default_insets);
    code.extend_from_slice(&[0x4e, 0x10, 7, 0x2a]);
    invoke(&mut code, 0xb4, activity);
    code.extend_from_slice(&[0x01, 0x03, 0x03]);
    invoke(&mut code, 0xb8, notify);
    code.extend_from_slice(&[0x2d, 0xb0]);
    class.method(
        PUBLIC,
        "onApplyWindowInsets",
        "(Landroid/view/View;Landroid/view/WindowInsets;)Landroid/view/WindowInsets;",
        5,
        4,
        code,
    )?;
    Ok(class)
}
fn simple_subclass(prefix: &str, name: &str, parent: &str) -> Result<Class, String> {
    let parent = format!("{prefix}/{parent}");
    let mut class = Class::new(&format!("{prefix}/{name}"), &parent)?;
    class.constructor(&parent)?;
    Ok(class)
}
fn accessibility_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let parent = "android/accessibilityservice/AccessibilityService";
    let mut class = Class::new(&format!("{prefix}/RustAccessibilityService"), parent)?;
    class.constructor(parent)?;
    let event = class.pool.member(
        bridge,
        "nativeAccessibilityEvent",
        "(Landroid/accessibilityservice/AccessibilityService;I)V",
        false,
    )?;
    for (name, kind) in [("onServiceConnected", 1u8), ("onDestroy", 3)] {
        let superclass = class.pool.member(parent, name, "()V", false)?;
        let mut code = Vec::new();
        if kind == 1 {
            code.push(0x2a);
            invoke(&mut code, 0xb7, superclass);
        }
        code.extend_from_slice(&[0x2a, 0x03 + kind]);
        invoke(&mut code, 0xb8, event);
        if kind == 3 {
            code.push(0x2a);
            invoke(&mut code, 0xb7, superclass);
        }
        code.push(0xb1);
        class.method(PUBLIC, name, "()V", 2, 1, code)?;
    }
    let unbind = class
        .pool
        .member(parent, "onUnbind", "(Landroid/content/Intent;)Z", false)?;
    let mut code = vec![0x2a, 0x05];
    invoke(&mut code, 0xb8, event);
    code.extend_from_slice(&[0x2a, 0x2b]);
    invoke(&mut code, 0xb7, unbind);
    code.push(0xac);
    class.method(
        PUBLIC,
        "onUnbind",
        "(Landroid/content/Intent;)Z",
        2,
        2,
        code,
    )?;
    let mut code = vec![0x2a, 0x07];
    invoke(&mut code, 0xb8, event);
    code.push(0xb1);
    class.method(PUBLIC, "onInterrupt", "()V", 2, 1, code)?;
    class.method(
        PUBLIC,
        "onAccessibilityEvent",
        "(Landroid/view/accessibility/AccessibilityEvent;)V",
        0,
        2,
        vec![0xb1],
    )?;
    Ok(class)
}
fn gesture_result_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let (mut class, field) = handle_class(
        &format!("{prefix}/NativeGestureResult"),
        "android/accessibilityservice/AccessibilityService$GestureResultCallback",
    )?;
    let native = class
        .pool
        .member(bridge, "nativeGestureResult", "(JZ)V", false)?;
    for (name, completed) in [("onCompleted", 0x04), ("onCancelled", 0x03)] {
        let mut code = vec![0x2a];
        invoke(&mut code, 0xb4, field);
        code.push(completed);
        invoke(&mut code, 0xb8, native);
        code.push(0xb1);
        class.method(
            PUBLIC,
            name,
            "(Landroid/accessibilityservice/GestureDescription;)V",
            3,
            2,
            code,
        )?;
    }
    Ok(class)
}
fn screenshot_result_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let (mut class, field) = handle_class(
        &format!("{prefix}/NativeScreenshotResult"),
        "java/lang/Object",
    )?;
    let interface = class
        .pool
        .class("android/accessibilityservice/AccessibilityService$TakeScreenshotCallback")?;
    class.interfaces.push(interface);
    let native = class.pool.member(
        bridge,
        "nativeScreenshotResult",
        "(JLjava/lang/Object;I)V",
        false,
    )?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb4, field);
    code.extend_from_slice(&[0x2b, 0x03]);
    invoke(&mut code, 0xb8, native);
    code.push(0xb1);
    class.method(
        PUBLIC,
        "onSuccess",
        "(Landroid/accessibilityservice/AccessibilityService$ScreenshotResult;)V",
        4,
        2,
        code,
    )?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb4, field);
    code.extend_from_slice(&[0x01, 0x1b]);
    invoke(&mut code, 0xb8, native);
    code.push(0xb1);
    class.method(PUBLIC, "onFailure", "(I)V", 4, 2, code)?;
    Ok(class)
}
fn overlay_view_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    draw_view_class(
        prefix,
        bridge,
        "NativeAutomationOverlay",
        "nativeOverlayDraw",
    )
}
fn draw_view_class(
    prefix: &str,
    bridge: &str,
    short: &str,
    native_name: &str,
) -> Result<Class, String> {
    let name = format!("{prefix}/{short}");
    let mut class = Class::new(&name, "android/view/View")?;
    let field_name = class.pool.utf8("handle")?;
    let descriptor = class.pool.utf8("J")?;
    class.fields.push((PRIVATE | FINAL, field_name, descriptor));
    let field = class.pool.member(&name, "handle", "J", true)?;
    let parent = class.pool.member(
        "android/view/View",
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
    let native = class
        .pool
        .member(bridge, native_name, "(JLandroid/graphics/Canvas;)V", false)?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb4, field);
    code.push(0x2b);
    invoke(&mut code, 0xb8, native);
    code.push(0xb1);
    class.method(PUBLIC, "onDraw", "(Landroid/graphics/Canvas;)V", 3, 2, code)?;
    Ok(class)
}
fn virtual_preview_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let mut class = draw_view_class(
        prefix,
        bridge,
        "NativeVirtualPreview",
        "nativeVirtualPreviewDraw",
    )?;
    let field = class.pool.member(
        &format!("{prefix}/NativeVirtualPreview"),
        "handle",
        "J",
        true,
    )?;
    let native = class.pool.member(
        bridge,
        "nativeVirtualPreviewTouch",
        "(JLandroid/view/MotionEvent;)Z",
        false,
    )?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb4, field);
    code.push(0x2b);
    invoke(&mut code, 0xb8, native);
    code.push(0xac);
    class.method(
        PUBLIC,
        "onTouchEvent",
        "(Landroid/view/MotionEvent;)Z",
        3,
        2,
        code,
    )?;
    Ok(class)
}
fn virtual_frame_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let (mut class, field) = handle_class(
        &format!("{prefix}/NativeVirtualFrameListener"),
        "java/lang/Object",
    )?;
    let interface = class
        .pool
        .class("android/media/ImageReader$OnImageAvailableListener")?;
    class.interfaces.push(interface);
    let native = class.pool.member(
        bridge,
        "nativeVirtualFrame",
        "(JLandroid/media/ImageReader;)V",
        false,
    )?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb4, field);
    code.push(0x2b);
    invoke(&mut code, 0xb8, native);
    code.push(0xb1);
    class.method(
        PUBLIC,
        "onImageAvailable",
        "(Landroid/media/ImageReader;)V",
        3,
        2,
        code,
    )?;
    Ok(class)
}
fn virtual_display_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let (mut class, field) = handle_class(
        &format!("{prefix}/NativeVirtualDisplayCallback"),
        "android/hardware/display/VirtualDisplay$Callback",
    )?;
    let native = class
        .pool
        .member(bridge, "nativeVirtualDisplayEvent", "(JI)V", false)?;
    for (name, kind) in [("onPaused", 1u8), ("onResumed", 2), ("onStopped", 3)] {
        let mut code = vec![0x2a];
        invoke(&mut code, 0xb4, field);
        code.push(0x03 + kind);
        invoke(&mut code, 0xb8, native);
        code.push(0xb1);
        class.method(PUBLIC, name, "()V", 3, 1, code)?;
    }
    Ok(class)
}
fn virtual_activity_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let parent = "android/app/Activity";
    let mut class = Class::new(&format!("{prefix}/RustVirtualScreenActivity"), parent)?;
    class.constructor(parent)?;
    let native = class.pool.member(
        bridge,
        "nativeVirtualHostEvent",
        "(Landroid/app/Activity;I)V",
        false,
    )?;
    let superclass = class
        .pool
        .member(parent, "onCreate", "(Landroid/os/Bundle;)V", false)?;
    let mut code = vec![0x2a, 0x2b];
    invoke(&mut code, 0xb7, superclass);
    code.extend_from_slice(&[0x2a, 0x04]);
    invoke(&mut code, 0xb8, native);
    code.push(0xb1);
    class.method(PUBLIC, "onCreate", "(Landroid/os/Bundle;)V", 2, 2, code)?;
    let superclass = class.pool.member(parent, "onDestroy", "()V", false)?;
    let mut code = vec![0x2a, 0x06];
    invoke(&mut code, 0xb8, native);
    code.push(0x2a);
    invoke(&mut code, 0xb7, superclass);
    code.push(0xb1);
    class.method(PUBLIC, "onDestroy", "()V", 2, 1, code)?;
    Ok(class)
}
fn shizuku_provider_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let mut class = Class::new(
        &format!("{prefix}/RustShizukuProvider"),
        "android/content/ContentProvider",
    )?;
    class.constructor("android/content/ContentProvider")?;
    let init_code = provider_on_create_code(&mut class, bridge, "nativeShizukuProviderInit")?;
    class.method(PUBLIC, "onCreate", "()Z", 1, 1, init_code)?;
    let native=class.pool.member(bridge,"nativeShizukuProviderCall","(Landroid/content/ContentProvider;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;",false)?;
    let mut code = vec![0x2a, 0x2b, 0x2c, 0x2d];
    invoke(&mut code, 0xb8, native);
    code.push(0xb0);
    class.method(
        PUBLIC,
        "call",
        "(Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;",
        4,
        4,
        code,
    )?;
    for (name, descriptor, locals, code) in [
        (
            "query",
            "(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
            6,
            vec![0x01, 0xb0],
        ),
        (
            "getType",
            "(Landroid/net/Uri;)Ljava/lang/String;",
            2,
            vec![0x01, 0xb0],
        ),
        (
            "insert",
            "(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;",
            3,
            vec![0x01, 0xb0],
        ),
        (
            "delete",
            "(Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)I",
            4,
            vec![0x03, 0xac],
        ),
        (
            "update",
            "(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I",
            5,
            vec![0x03, 0xac],
        ),
    ] {
        class.method(PUBLIC, name, descriptor, 1, locals, code)?;
    }
    Ok(class)
}
fn provider_on_create_code(
    class: &mut Class,
    bridge: &str,
    native_init: &str,
) -> Result<Vec<u8>, String> {
    let loaded = class.pool.member(bridge, "ensureLoaded", "()V", false)?;
    let init = class.pool.member(
        bridge,
        native_init,
        "(Landroid/content/ContentProvider;)V",
        false,
    )?;
    let mut code = Vec::new();
    invoke(&mut code, 0xb8, loaded);
    code.push(0x2a);
    invoke(&mut code, 0xb8, init);
    code.extend_from_slice(&[0x04, 0xac]);
    Ok(code)
}
fn shizuku_client_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let (mut class, field) = handle_class(
        &format!("{prefix}/NativeShizukuClient"),
        "android/os/Binder",
    )?;
    let native = class.pool.member(
        bridge,
        "nativeShizukuClient",
        "(JILandroid/os/Parcel;Landroid/os/Parcel;I)Z",
        false,
    )?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb4, field);
    code.extend_from_slice(&[0x1b, 0x2c, 0x2d, 0x15, 0x04]);
    invoke(&mut code, 0xb8, native);
    code.push(0xac);
    class.method(
        PUBLIC,
        "onTransact",
        "(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z",
        6,
        5,
        code,
    )?;
    Ok(class)
}
fn shizuku_death_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let (mut class, field) =
        handle_class(&format!("{prefix}/NativeShizukuDeath"), "java/lang/Object")?;
    let interface = class.pool.class("android/os/IBinder$DeathRecipient")?;
    class.interfaces.push(interface);
    let native = class
        .pool
        .member(bridge, "nativeShizukuDeath", "(J)V", false)?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb4, field);
    invoke(&mut code, 0xb8, native);
    code.push(0xb1);
    class.method(PUBLIC, "binderDied", "()V", 2, 1, code)?;
    Ok(class)
}
fn binder_container_class() -> Result<Class, String> {
    let name = "moe/shizuku/api/BinderContainer";
    let creator_name = "moe/shizuku/api/BinderContainerCreator";
    let mut class = Class::new(name, "java/lang/Object")?;
    let interface = class.pool.class("android/os/Parcelable")?;
    class.interfaces.push(interface);
    let binder_name = class.pool.utf8("binder")?;
    let binder_descriptor = class.pool.utf8("Landroid/os/IBinder;")?;
    class
        .fields
        .push((PUBLIC | FINAL, binder_name, binder_descriptor));
    let creator_field_name = class.pool.utf8("CREATOR")?;
    let creator_descriptor = class.pool.utf8("Landroid/os/Parcelable$Creator;")?;
    class.fields.push((
        PUBLIC | STATIC | FINAL,
        creator_field_name,
        creator_descriptor,
    ));
    let binder_field = class
        .pool
        .member(name, "binder", "Landroid/os/IBinder;", true)?;
    let parent = class
        .pool
        .member("java/lang/Object", "<init>", "()V", false)?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb7, parent);
    code.extend_from_slice(&[0x2a, 0x2b]);
    invoke(&mut code, 0xb5, binder_field);
    code.push(0xb1);
    class.method(PUBLIC, "<init>", "(Landroid/os/IBinder;)V", 2, 2, code)?;
    let read = class.pool.member(
        "android/os/Parcel",
        "readStrongBinder",
        "()Landroid/os/IBinder;",
        false,
    )?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb7, parent);
    code.extend_from_slice(&[0x2a, 0x2b]);
    invoke(&mut code, 0xb6, read);
    invoke(&mut code, 0xb5, binder_field);
    code.push(0xb1);
    class.method(PUBLIC, "<init>", "(Landroid/os/Parcel;)V", 2, 2, code)?;
    let write = class.pool.member(
        "android/os/Parcel",
        "writeStrongBinder",
        "(Landroid/os/IBinder;)V",
        false,
    )?;
    let mut code = vec![0x2b, 0x2a];
    invoke(&mut code, 0xb4, binder_field);
    invoke(&mut code, 0xb6, write);
    code.push(0xb1);
    class.method(
        PUBLIC,
        "writeToParcel",
        "(Landroid/os/Parcel;I)V",
        2,
        3,
        code,
    )?;
    class.method(PUBLIC, "describeContents", "()I", 1, 1, vec![0x03, 0xac])?;
    let creator = class.pool.class(creator_name)?;
    let constructor = class.pool.member(creator_name, "<init>", "()V", false)?;
    let field = class
        .pool
        .member(name, "CREATOR", "Landroid/os/Parcelable$Creator;", true)?;
    let mut code = vec![0xbb];
    u16be(&mut code, creator);
    code.push(0x59);
    invoke(&mut code, 0xb7, constructor);
    invoke(&mut code, 0xb3, field);
    code.push(0xb1);
    class.method(STATIC, "<clinit>", "()V", 2, 0, code)?;
    Ok(class)
}
fn binder_creator_class() -> Result<Class, String> {
    let mut class = Class::new("moe/shizuku/api/BinderContainerCreator", "java/lang/Object")?;
    class.constructor("java/lang/Object")?;
    let interface = class.pool.class("android/os/Parcelable$Creator")?;
    class.interfaces.push(interface);
    let container = class.pool.class("moe/shizuku/api/BinderContainer")?;
    let constructor = class.pool.member(
        "moe/shizuku/api/BinderContainer",
        "<init>",
        "(Landroid/os/Parcel;)V",
        false,
    )?;
    let mut code = vec![0xbb];
    u16be(&mut code, container);
    code.extend_from_slice(&[0x59, 0x2b]);
    invoke(&mut code, 0xb7, constructor);
    code.push(0xb0);
    class.method(
        PUBLIC,
        "createFromParcel",
        "(Landroid/os/Parcel;)Ljava/lang/Object;",
        3,
        2,
        code,
    )?;
    let mut code = vec![0x1b, 0xbd];
    u16be(&mut code, container);
    code.push(0xb0);
    class.method(PUBLIC, "newArray", "(I)[Ljava/lang/Object;", 1, 2, code)?;
    Ok(class)
}
fn handle_class(name: &str, parent: &str) -> Result<(Class, u16), String> {
    let mut class = Class::new(name, parent)?;
    let field_name = class.pool.utf8("handle")?;
    let field_descriptor = class.pool.utf8("J")?;
    class
        .fields
        .push((PRIVATE | FINAL, field_name, field_descriptor));
    let field = class.pool.member(name, "handle", "J", true)?;
    let parent = class.pool.member(parent, "<init>", "()V", false)?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb7, parent);
    code.extend_from_slice(&[0x2a, 0x1f]);
    invoke(&mut code, 0xb5, field);
    code.push(0xb1);
    class.method(PUBLIC, "<init>", "(J)V", 3, 3, code)?;
    Ok((class, field))
}
fn init_listener_class(
    prefix: &str,
    bridge: &str,
    name: &str,
    interface: &str,
    method: &str,
    native: &str,
) -> Result<Class, String> {
    let (class, field) = handle_class(&format!("{prefix}/{name}"), "java/lang/Object")?;
    let mut class = class;
    let interface = class.pool.class(interface)?;
    class.interfaces.push(interface);
    let native = class.pool.member(bridge, native, "(JI)V", false)?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb4, field);
    code.push(0x1b);
    invoke(&mut code, 0xb8, native);
    code.push(0xb1);
    class.method(PUBLIC, method, "(I)V", 3, 2, code)?;
    Ok(class)
}
fn tts_progress_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let (mut class, field) = handle_class(
        &format!("{prefix}/NativeTtsProgressListener"),
        "android/speech/tts/UtteranceProgressListener",
    )?;
    let native = class.pool.member(
        bridge,
        "nativeTtsProgress",
        "(JLjava/lang/String;II)V",
        false,
    )?;
    for (name, descriptor, kind, code_load, locals) in [
        ("onStart", "(Ljava/lang/String;)V", 1u8, 0x03, 2),
        ("onDone", "(Ljava/lang/String;)V", 2, 0x03, 2),
        ("onError", "(Ljava/lang/String;)V", 3, 0x02, 2),
        ("onError", "(Ljava/lang/String;I)V", 3, 0x1c, 3),
        ("onStop", "(Ljava/lang/String;Z)V", 4, 0x1c, 3),
    ] {
        let mut code = vec![0x2a];
        invoke(&mut code, 0xb4, field);
        code.extend_from_slice(&[0x2b, 0x03 + kind, code_load]);
        invoke(&mut code, 0xb8, native);
        code.push(0xb1);
        class.method(PUBLIC, name, descriptor, 5, locals, code)?;
    }
    Ok(class)
}
fn runnable_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let name = format!("{prefix}/NativeRunnable");
    let mut c = Class::new(&name, "java/lang/Object")?;
    let interface = c.pool.class("java/lang/Runnable")?;
    c.interfaces.push(interface);
    let field_name = c.pool.utf8("handle")?;
    let desc = c.pool.utf8("J")?;
    c.fields.push((PRIVATE | FINAL, field_name, desc));
    let field = c.pool.member(&name, "handle", "J", true)?;
    let sup = c.pool.member("java/lang/Object", "<init>", "()V", false)?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb7, sup);
    code.extend_from_slice(&[0x2a, 0x1f]);
    invoke(&mut code, 0xb5, field);
    code.push(0xb1);
    c.method(PUBLIC, "<init>", "(J)V", 3, 3, code)?;
    let native = c.pool.member(bridge, "nativeRun", "(J)V", false)?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb4, field);
    invoke(&mut code, 0xb8, native);
    code.push(0xb1);
    c.method(PUBLIC, "run", "()V", 2, 1, code)?;
    Ok(c)
}
fn service_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let mut c = Class::new(&format!("{prefix}/RustService"), "android/app/Service")?;
    c.constructor("android/app/Service")?;
    let call = c.pool.member(
        bridge,
        "nativeServiceEvent",
        "(ILandroid/app/Service;Landroid/content/Intent;II)I",
        false,
    )?;
    for (name, event) in [("onCreate", 1u8), ("onDestroy", 3)] {
        let sup = c.pool.member("android/app/Service", name, "()V", false)?;
        let mut code = vec![0x2a];
        invoke(&mut code, 0xb7, sup);
        code.extend_from_slice(&[0x03 + event, 0x2a, 0x01, 0x03, 0x03]);
        invoke(&mut code, 0xb8, call);
        code.extend_from_slice(&[0x57, 0xb1]);
        c.method(PUBLIC, name, "()V", 5, 1, code)?;
    }
    let mut code = vec![0x05, 0x2a, 0x2b, 0x1c, 0x1d];
    invoke(&mut code, 0xb8, call);
    code.push(0xac);
    c.method(
        PUBLIC,
        "onStartCommand",
        "(Landroid/content/Intent;II)I",
        5,
        4,
        code,
    )?;
    c.method(
        PUBLIC,
        "onBind",
        "(Landroid/content/Intent;)Landroid/os/IBinder;",
        1,
        2,
        vec![0x01, 0xb0],
    )?;
    // These overloads were added after minSdk. Never call an absent superclass
    // method when the generated class is loaded on an older Android version.
    let mut code = vec![0x07, 0x2a, 0x01, 0x03, 0x1b];
    invoke(&mut code, 0xb8, call);
    code.extend_from_slice(&[0x57, 0xb1]);
    c.method(PUBLIC, "onTimeout", "(I)V", 5, 2, code)?;
    let mut code = vec![0x08, 0x2a, 0x01, 0x1c, 0x1b];
    invoke(&mut code, 0xb8, call);
    code.extend_from_slice(&[0x57, 0xb1]);
    c.method(PUBLIC, "onTimeout", "(II)V", 5, 3, code)?;
    Ok(c)
}
fn agent_service_class(prefix: &str) -> Result<Class, String> {
    let parent = format!("{prefix}/RustService");
    let mut class = Class::new(&format!("{prefix}/RustAgentService"), &parent)?;
    class.constructor(&parent)?;
    Ok(class)
}
fn receiver_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let mut c = Class::new(
        &format!("{prefix}/RustReceiver"),
        "android/content/BroadcastReceiver",
    )?;
    c.constructor("android/content/BroadcastReceiver")?;
    let call = c.pool.member(
        bridge,
        "nativeReceiverEvent",
        "(Landroid/content/BroadcastReceiver;Landroid/content/Context;Landroid/content/Intent;)V",
        false,
    )?;
    let mut code = vec![0x2a, 0x2b, 0x2c];
    invoke(&mut code, 0xb8, call);
    code.push(0xb1);
    c.method(
        PUBLIC,
        "onReceive",
        "(Landroid/content/Context;Landroid/content/Intent;)V",
        3,
        3,
        code,
    )?;
    Ok(c)
}

// Root appends this function in the existing Rust classfile generator, then
// adds performance_frame_class(prefix,bridge)? to its normal class list.
fn performance_frame_class(prefix: &str, bridge: &str) -> Result<Class, String> {
    let (mut class, field) = handle_class(
        &format!("{prefix}/NativePerfFrameCallback"),
        "java/lang/Object",
    )?;
    let interface = class
        .pool
        .class("android/view/Choreographer$FrameCallback")?;
    class.interfaces.push(interface);
    let native = class
        .pool
        .member(bridge, "nativePerfFrame", "(JJ)V", false)?;
    let mut code = vec![0x2a];
    invoke(&mut code, 0xb4, field);
    code.push(0x1f);
    invoke(&mut code, 0xb8, native);
    code.push(0xb1);
    class.method(PUBLIC, "doFrame", "(J)V", 4, 3, code)?;
    Ok(class)
}
