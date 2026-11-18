//! JNI/framework calls only. Durable state, service strategy, and rendering stay
//! with the Rust runtime. No local JNI reference or Env crosses a thread boundary.
use super::secrets::{SecretError, StoredSecret};
use jni::{
    Env, EnvUnowned, JValue, JavaVM, NativeMethod, Outcome, jni_sig, jni_str,
    objects::{JClass, JIntArray, JObject, JObjectArray, JString},
    refs::Global,
    sys::{jboolean, jint, jlong},
};
use std::{
    collections::{BTreeMap, VecDeque},
    ffi::c_void,
    panic::{AssertUnwindSafe, catch_unwind},
    path::PathBuf,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
};

const MAX_EVENTS: usize = 256;
const MAX_MAIN_CALLBACKS: usize = 256;
const KEY_ALIAS: &str = "suixing_secrets_v1";
static VM: OnceLock<JavaVM> = OnceLock::new();
static STATE: OnceLock<Mutex<State>> = OnceLock::new();
static NEXT_CALLBACK: AtomicU64 = AtomicU64::new(1);
static KEY_INIT: Mutex<()> = Mutex::new(());
static CONTEXT_INIT: Mutex<()> = Mutex::new(());
static SERVICE_HANDLER: OnceLock<fn(ServiceRequest) -> Result<i32, String>> = OnceLock::new();
static RECEIVER_HANDLER: OnceLock<fn(ReceiverRequest) -> Result<(), String>> = OnceLock::new();
type MainCallback = Box<dyn FnOnce() + Send + 'static>;

#[derive(Clone, Debug)]
pub struct PlatformPaths {
    pub files_dir: PathBuf,
    pub no_backup_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub external_files_dir: Option<PathBuf>,
    pub native_library_dir: PathBuf,
    pub package_name: String,
    pub sdk_int: i32,
}
#[derive(Clone, Debug, Default)]
pub struct IntentFacts {
    pub action: Option<String>,
    pub open_terminal: bool,
    pub run_id: Option<String>,
    pub conversation_id: Option<String>,
    pub component: Option<String>,
    pub data_uri: Option<String>,
    pub schedule_id: Option<String>,
    pub fire_token: Option<String>,
    pub alarm_generation: Option<String>,
    pub scheduled_at: Option<i64>,
    pub terminal_session_id: Option<String>,
    pub process_token: Option<String>,
    pub job_id: Option<String>,
}
#[derive(Clone, Debug)]
pub enum PlatformEvent {
    ApplicationReady(PlatformPaths),
    Activity {
        kind: i32,
        intent: IntentFacts,
    },
    Service {
        kind: i32,
        flags: i32,
        start_id: i32,
        intent: IntentFacts,
    },
    Receiver {
        intent: IntentFacts,
    },
    Error(String),
}
#[derive(Default)]
struct State {
    context: Option<Arc<Global<JObject<'static>>>>,
    handler: Option<Arc<Global<JObject<'static>>>>,
    runnable: Option<Arc<Global<JClass<'static>>>>,
    activity: Option<Arc<Global<JObject<'static>>>>,
    visible: bool,
    tts_init: Option<Arc<Global<JClass<'static>>>>,
    tts_progress: Option<Arc<Global<JClass<'static>>>>,
    audio_focus: Option<Arc<Global<JClass<'static>>>>,
    generated: BTreeMap<String, Arc<Global<JClass<'static>>>>,
    paths: Option<PlatformPaths>,
    events: VecDeque<PlatformEvent>,
    callbacks: BTreeMap<u64, MainCallback>,
    key: Option<Arc<Global<JObject<'static>>>>,
}
fn state() -> &'static Mutex<State> {
    STATE.get_or_init(|| Mutex::new(State::default()))
}
fn publish(event: PlatformEvent) {
    let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
    if s.events.len() == MAX_EVENTS {
        s.events.pop_front();
    }
    s.events.push_back(event);
}
pub fn paths() -> Option<PlatformPaths> {
    state()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .paths
        .clone()
}
pub fn drain_events(limit: usize) -> Vec<PlatformEvent> {
    let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
    let count = limit.min(MAX_EVENTS).min(s.events.len());
    s.events.drain(..count).collect()
}
pub fn application_context() -> Result<Arc<Global<JObject<'static>>>, String> {
    state()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .context
        .clone()
        .ok_or("Android applicationContext尚未初始化".into())
}
pub fn current_activity() -> Option<Arc<Global<JObject<'static>>>> {
    state()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .activity
        .clone()
}
pub fn set_service_handler(
    handler: fn(ServiceRequest) -> Result<i32, String>,
) -> Result<(), String> {
    SERVICE_HANDLER
        .set(handler)
        .map_err(|_| "Service处理器已初始化".into())
}
pub fn set_receiver_handler(
    handler: fn(ReceiverRequest) -> Result<(), String>,
) -> Result<(), String> {
    RECEIVER_HANDLER
        .set(handler)
        .map_err(|_| "Receiver处理器已初始化".into())
}

pub struct ServiceRequest {
    pub instance: Global<JObject<'static>>,
    pub component: String,
    pub kind: i32,
    pub intent: IntentFacts,
    pub flags: i32,
    pub start_id: i32,
}
pub struct ReceiverRequest {
    pub component: String,
    pub intent: IntentFacts,
    pub completion: ReceiverCompletion,
}
pub struct ReceiverCompletion {
    pending: Option<Global<JObject<'static>>>,
}
impl ReceiverCompletion {
    pub fn finish(mut self) -> Result<(), String> {
        self.finish_inner()
    }
    fn finish_inner(&mut self) -> Result<(), String> {
        let Some(pending) = self.pending.as_ref() else {
            return Ok(());
        };
        invoke(|env| {
            env.call_method(pending, jni_str!("finish"), jni_sig!("()V"), &[])?;
            Ok(())
        })
        .map_err(|_| "无法释放广播完成token".to_string())?;
        self.pending = None;
        Ok(())
    }
}
impl Drop for ReceiverCompletion {
    fn drop(&mut self) {
        if self.pending.is_some() {
            let _ = self.finish_inner();
        }
    }
}

pub fn with_env<T>(
    operation: impl FnOnce(&mut Env) -> jni::errors::Result<T>,
) -> jni::errors::Result<T> {
    VM.get()
        .ok_or(jni::errors::Error::NullPtr("JavaVM"))?
        .attach_current_thread(operation)
}
pub fn activity_visible() -> bool {
    state().lock().unwrap_or_else(|e| e.into_inner()).visible
}
#[derive(Clone, Copy)]
pub enum ListenerKind {
    TtsInit,
    TtsProgress,
    AudioFocus,
}
pub fn listener_class(kind: ListenerKind) -> Result<Arc<Global<JClass<'static>>>, String> {
    let state = state().lock().unwrap_or_else(|e| e.into_inner());
    match kind {
        ListenerKind::TtsInit => state.tts_init.clone(),
        ListenerKind::TtsProgress => state.tts_progress.clone(),
        ListenerKind::AudioFocus => state.audio_focus.clone(),
    }
    .ok_or("Android回调类尚未注册".into())
}
pub fn main_handler() -> Result<Arc<Global<JObject<'static>>>, String> {
    state()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .handler
        .clone()
        .ok_or("Android主线程尚未初始化".into())
}
pub fn generated_class(name: &str) -> Result<Arc<Global<JClass<'static>>>, String> {
    state()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .generated
        .get(name)
        .cloned()
        .ok_or("当前Android版本的框架转发类尚未注册".into())
}
pub fn string_value(env: &mut Env, value: JObject, max: usize) -> jni::errors::Result<String> {
    let string = env.cast_local::<JString>(value)?;
    let length = env
        .call_method(&string, jni_str!("length"), jni_sig!("()I"), &[])?
        .i()?;
    if length < 0 || length as usize > max {
        return Err(jni::errors::Error::NullPtr("SDK string budget"));
    }
    let string = string.try_to_string(env)?;
    if string.len() > max {
        return Err(jni::errors::Error::NullPtr("SDK UTF8 string budget"));
    }
    Ok(string)
}
pub fn invoke<T>(operation: impl FnOnce(&mut Env) -> jni::errors::Result<T>) -> Result<T, String> {
    let result = catch_unwind(AssertUnwindSafe(|| {
        with_env(|env| {
            if env.exception_check() {
                env.exception_clear();
            }
            let result = operation(env);
            if env.exception_check() {
                env.exception_clear();
            }
            result
        })
    }));
    match result {
        Ok(Ok(value)) => Ok(value),
        Ok(Err(_)) => Err("Android框架调用失败；权限、资源或返回类型未获确认".into()),
        Err(_) => {
            let _ = with_env(|env| {
                if env.exception_check() {
                    env.exception_clear();
                }
                Ok(())
            });
            Err("Android平台调用异常".into())
        }
    }
}

// Native lifecycle forwarding reports errors to Rust, so pending Java exceptions
// must not escape into Application/Activity after a caught failure or panic.
fn clear_pending_exception(unowned: &mut EnvUnowned) {
    let _ = unowned
        .with_env(|env| -> jni::errors::Result<()> {
            if env.exception_check() {
                env.exception_clear();
            }
            Ok(())
        })
        .into_outcome();
}

pub fn post_to_main(callback: impl FnOnce() + Send + 'static) -> Result<(), String> {
    let (handler, class, id) = {
        let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
        let handler = s.handler.clone().ok_or("Android主线程尚未初始化")?;
        let class = s.runnable.clone().ok_or("Android Runnable未注册")?;
        if s.callbacks.len() >= MAX_MAIN_CALLBACKS {
            return Err("Android主线程回调队列已满".into());
        }
        let id = NEXT_CALLBACK
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |v| v.checked_add(1))
            .map_err(|_| "Android回调ID已耗尽")?;
        s.callbacks.insert(id, Box::new(callback));
        (handler, class, id)
    };
    let result = with_env(|env| {
        let runnable =
            env.new_object(class.as_ref(), jni_sig!("(J)V"), &[JValue::Long(id as i64)])?;
        env.call_method(
            handler.as_ref(),
            jni_str!("post"),
            jni_sig!("(Ljava/lang/Runnable;)Z"),
            &[JValue::Object(&runnable)],
        )?
        .z()
    });
    if !matches!(result, Ok(true)) {
        state()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .callbacks
            .remove(&id);
        return Err("Android主线程投递被拒绝".into());
    }
    Ok(())
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn JNI_OnLoad(
    vm: *mut jni::sys::JavaVM,
    _reserved: *mut c_void,
) -> jint {
    if vm.is_null() {
        return jni::sys::JNI_ERR;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let vm = unsafe { JavaVM::from_raw(vm) };
        let _ = VM.set(vm.clone());
        vm.attach_current_thread(|env|->jni::errors::Result<()>{let bridge=env.find_class(jni_str!("com/suixing/agent/rust/platform/Bridge"))?;let methods=unsafe{[
        NativeMethod::from_raw_parts(jni_str!("nativeBootstrap"),jni_str!("(Landroid/content/Context;)V"),native_bootstrap as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeTerminalKey"),jni_str!("(Landroid/app/Activity;Landroid/view/KeyEvent;)Z"),native_terminal_key as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeActivityEvent"),jni_str!("(ILandroid/app/Activity;Landroid/content/Intent;II)V"),native_activity as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeServiceEvent"),jni_str!("(ILandroid/app/Service;Landroid/content/Intent;II)I"),native_service as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeReceiverEvent"),jni_str!("(Landroid/content/BroadcastReceiver;Landroid/content/Context;Landroid/content/Intent;)V"),native_receiver as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeFileProvider"),jni_str!("(Landroid/content/ContentProvider;ILandroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/Object;"),crate::file_provider::callback as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeRun"),jni_str!("(J)V"),native_run as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeActivityResult"),jni_str!("(Landroid/app/Activity;IILandroid/content/Intent;)V"),native_activity_result as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativePermissionsResult"),jni_str!("(Landroid/app/Activity;I[Ljava/lang/String;[I)V"),native_permissions_result as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeTtsInit"),jni_str!("(JI)V"),native_tts_init as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeTtsProgress"),jni_str!("(JLjava/lang/String;II)V"),native_tts_progress as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeAudioFocusEvent"),jni_str!("(JI)V"),native_audio_focus as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeAccessibilityEvent"),jni_str!("(Landroid/accessibilityservice/AccessibilityService;I)V"),native_accessibility as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeGestureResult"),jni_str!("(JZ)V"),native_gesture_result as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeScreenshotResult"),jni_str!("(JLjava/lang/Object;I)V"),native_screenshot_result as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeOverlayDraw"),jni_str!("(JLandroid/graphics/Canvas;)V"),native_overlay_draw as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeConsoleDraw"),jni_str!("(JLandroid/graphics/Canvas;)V"),native_console_draw as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeConsoleTouch"),jni_str!("(JLandroid/view/MotionEvent;)Z"),native_console_touch as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeConsoleAccessibility"),jni_str!("(JLandroid/view/accessibility/AccessibilityNodeInfo;I)Z"),native_console_accessibility as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeUiAccessibilityNode"),jni_str!("(JI)Landroid/view/accessibility/AccessibilityNodeInfo;"),native_ui_accessibility_node as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeUiAccessibilityFocus"),jni_str!("(JI)Landroid/view/accessibility/AccessibilityNodeInfo;"),native_ui_accessibility_focus as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeUiAccessibilityAction"),jni_str!("(JIILandroid/os/Bundle;)Z"),native_ui_accessibility_action as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeUiAccessibilityHover"),jni_str!("(JLandroid/view/MotionEvent;)Z"),native_ui_accessibility_hover as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeUiAccessibilitySearch"),jni_str!("(JLjava/lang/String;I)Ljava/util/List;"),native_ui_accessibility_search as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeShizukuProviderCall"),jni_str!("(Landroid/content/ContentProvider;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;"),native_shizuku_provider as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeShizukuClient"),jni_str!("(JILandroid/os/Parcel;Landroid/os/Parcel;I)Z"),native_shizuku_client as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeShizukuDeath"),jni_str!("(J)V"),native_shizuku_death as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeShizukuProviderInit"),jni_str!("(Landroid/content/ContentProvider;)V"),native_shizuku_provider_init as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativePerfFrame"), jni_str!("(JJ)V"), native_perf_frame as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeVirtualFrame"),jni_str!("(JLandroid/media/ImageReader;)V"),native_virtual_frame as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeVirtualDisplayEvent"),jni_str!("(JI)V"),native_virtual_display as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeVirtualPreviewDraw"),jni_str!("(JLandroid/graphics/Canvas;)V"),native_virtual_draw as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeVirtualPreviewTouch"),jni_str!("(JLandroid/view/MotionEvent;)Z"),native_virtual_touch as *const () as *mut c_void),
        NativeMethod::from_raw_parts(jni_str!("nativeVirtualHostEvent"),jni_str!("(Landroid/app/Activity;I)V"),native_virtual_host as *const () as *mut c_void),
    ]};unsafe{env.register_native_methods(&bridge,&methods)?;}
    let runnable=env.find_class(jni_str!("com/suixing/agent/rust/platform/NativeRunnable"))?;
    let init=env.find_class(jni_str!("com/suixing/agent/rust/platform/NativeTtsInitListener"))?;
    let progress=env.find_class(jni_str!("com/suixing/agent/rust/platform/NativeTtsProgressListener"))?;
    let focus=env.find_class(jni_str!("com/suixing/agent/rust/platform/NativeAudioFocusListener"))?;
    let sdk=env.get_static_field(jni_str!("android/os/Build$VERSION"),jni_str!("SDK_INT"),jni_sig!("I"))?.i()?;
    let mut generated=BTreeMap::new();
    for(short,name,minimum_sdk)in[
        ("NativeGestureResult","com/suixing/agent/rust/platform/NativeGestureResult",26),
        ("NativeScreenshotResult","com/suixing/agent/rust/platform/NativeScreenshotResult",30),
        ("NativeAutomationOverlay","com/suixing/agent/rust/platform/NativeAutomationOverlay",26),
        ("NativeFloatConsole","com/suixing/agent/rust/platform/NativeFloatConsole",26),
        ("NativeFloatClick","com/suixing/agent/rust/platform/NativeFloatClick",26),
        ("NativeUiAccessibility","com/suixing/agent/rust/platform/NativeUiAccessibility",26),
        ("NativeUiNodeProvider","com/suixing/agent/rust/platform/NativeUiNodeProvider",26),
        ("NativeInsetsListener","com/suixing/agent/rust/platform/NativeInsetsListener",26),
        ("NativeShizukuClient","com/suixing/agent/rust/platform/NativeShizukuClient",26),
        ("NativeShizukuDeath","com/suixing/agent/rust/platform/NativeShizukuDeath",26),
        ("ShizukuBinderContainer","moe/shizuku/api/BinderContainer",26),
        ("NativeVirtualFrameListener","com/suixing/agent/rust/platform/NativeVirtualFrameListener",26),
        ("NativeVirtualDisplayCallback","com/suixing/agent/rust/platform/NativeVirtualDisplayCallback",26),
        ("NativeVirtualPreview","com/suixing/agent/rust/platform/NativeVirtualPreview",26),
    ]{if sdk>=minimum_sdk{let class=env.find_class(jni::strings::JNIString::new(name))?;generated.insert(short.to_owned(),Arc::new(env.new_global_ref(class)?));}}
    let mut state=state().lock().unwrap_or_else(|e|e.into_inner());state.generated=generated;state.runnable=Some(Arc::new(env.new_global_ref(runnable)?));state.tts_init=Some(Arc::new(env.new_global_ref(init)?));state.tts_progress=Some(Arc::new(env.new_global_ref(progress)?));state.audio_focus=Some(Arc::new(env.new_global_ref(focus)?));Ok(())})
    }));
    if matches!(result, Ok(Ok(()))) {
        jni::sys::JNI_VERSION_1_6
    } else {
        jni::sys::JNI_ERR
    }
}

unsafe extern "system" fn native_bootstrap(
    mut unowned: EnvUnowned,
    _class: JClass,
    context: JObject,
) {
    let outcome = unowned
        .with_env(|env| bootstrap_context(env, &context))
        .into_outcome();
    if !matches!(&outcome, Outcome::Ok(_)) {
        clear_pending_exception(&mut unowned);
    }
    match outcome {
        Outcome::Ok(paths) => {
            publish(PlatformEvent::ApplicationReady(paths.clone()));
            let result = catch_unwind(AssertUnwindSafe(|| {
                crate::platform::application_bootstrapped(paths)
            }));
            if !matches!(result, Ok(Ok(()))) {
                publish(PlatformEvent::Error("Android进程runtime启动失败".into()));
            }
        }
        _ => publish(PlatformEvent::Error("Android平台初始化失败".into())),
    }
}
/// Provider initialization creates only process framework facts and the main
/// Handler. It does not publish ApplicationReady, start runtime or start services.
pub(crate) fn early_provider_init(
    env: &mut Env,
    provider: &JObject,
) -> jni::errors::Result<PlatformPaths> {
    let context = env
        .call_method(
            provider,
            jni_str!("getContext"),
            jni_sig!("()Landroid/content/Context;"),
            &[],
        )?
        .l()?;
    if context.is_null() {
        return Err(jni::errors::Error::NullPtr("provider context unavailable"));
    }
    bootstrap_context(env, &context)
}
fn bootstrap_context(env: &mut Env, input: &JObject) -> jni::errors::Result<PlatformPaths> {
    let _guard = CONTEXT_INIT.lock().unwrap_or_else(|e| e.into_inner());
    let app = env
        .call_method(
            input,
            jni_str!("getApplicationContext"),
            jni_sig!("()Landroid/content/Context;"),
            &[],
        )?
        .l()?;
    if app.is_null() {
        return Err(jni::errors::Error::NullPtr(
            "application context unavailable",
        ));
    }
    let existing = {
        let s = state().lock().unwrap_or_else(|e| e.into_inner());
        s.context
            .clone()
            .zip(s.paths.clone())
            .filter(|_| s.handler.is_some())
    };
    if let Some((context, paths)) = existing {
        if env.is_same_object(context.as_ref(), &app)? {
            return Ok(paths);
        }
    }
    let context = Arc::new(env.new_global_ref(app)?);
    let paths = read_paths(env, context.as_ref())?;
    let looper = env
        .call_static_method(
            jni_str!("android/os/Looper"),
            jni_str!("getMainLooper"),
            jni_sig!("()Landroid/os/Looper;"),
            &[],
        )?
        .l()?;
    let handler = env.new_object(
        jni_str!("android/os/Handler"),
        jni_sig!("(Landroid/os/Looper;)V"),
        &[JValue::Object(&looper)],
    )?;
    let handler = Arc::new(env.new_global_ref(handler)?);
    let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
    s.context = Some(context);
    s.handler = Some(handler);
    s.paths = Some(paths.clone());
    Ok(paths)
}
unsafe extern "system" fn native_terminal_key(
    mut unowned: EnvUnowned,
    _class: JClass,
    activity: JObject,
    event: JObject,
) -> jboolean {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        unowned
            .with_env(|env| {
                if super::navigation::dispatch_key(env, &activity, &event)? {
                    Ok(true)
                } else {
                    super::terminal_keys::dispatch(env, &activity, &event)
                }
            })
            .into_outcome()
    }));
    match outcome {
        Ok(Outcome::Ok(true)) => true,
        _ => {
            clear_pending_exception(&mut unowned);
            false
        }
    }
}
unsafe extern "system" fn native_activity(
    mut unowned: EnvUnowned,
    _class: JClass,
    kind: jint,
    activity: JObject,
    intent: JObject,
    _flags: jint,
    _request: jint,
) {
    // Insets, layout and configuration notifications stay out of the lifecycle mailbox.
    if kind == 9 {
        let _ = unowned
            .with_env(|env| super::navigation::request(env, &activity))
            .into_outcome();
        clear_pending_exception(&mut unowned);
        return;
    }
    if matches!(kind, 7 | 8) {
        let _ = unowned
            .with_env(|env| super::window_insets::lifecycle(env, &activity, kind))
            .into_outcome();
        clear_pending_exception(&mut unowned);
        return;
    }
    if matches!(kind, 1 | 2 | 3 | 4 | 5) {
        super::terminal_keys::invalidate();
    }
    if kind == 2 {
        state().lock().unwrap_or_else(|e| e.into_inner()).visible = true;
    }
    if matches!(kind, 3 | 4 | 5) {
        state().lock().unwrap_or_else(|e| e.into_inner()).visible = false;
        super::voice::activity_visibility_changed(false);
    }
    if kind == 5 {
        super::permissions::activity_destroyed();
        super::attachments::activity_destroyed();
    }
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<IntentFacts> {
            if super::window_insets::lifecycle(env, &activity, kind).is_err()
                && env.exception_check()
            {
                env.exception_clear();
            }
            if kind == 5 {
                crate::file_transfer::activity_destroyed(env, &activity)?;
                crate::plugin_assets::runtime::activity_destroyed(env, &activity)?;
                crate::skill_import::device::activity_destroyed(env, &activity)?;
            }
            let facts = if intent.is_null() {
                let current = env
                    .call_method(
                        &activity,
                        jni_str!("getIntent"),
                        jni_sig!("()Landroid/content/Intent;"),
                        &[],
                    )?
                    .l()?;
                read_intent(env, &current)?
            } else {
                read_intent(env, &intent)?
            };
            let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
            if [1, 2, 6].contains(&kind) {
                s.activity = Some(Arc::new(env.new_global_ref(&activity)?));
            } else if kind == 5 {
                let same = match s.activity.as_ref() {
                    Some(old) => env.is_same_object(old.as_ref(), &activity)?,
                    None => false,
                };
                if same {
                    s.activity = None;
                }
            }
            Ok(facts)
        })
        .into_outcome();
    if !matches!(&outcome, Outcome::Ok(_)) {
        clear_pending_exception(&mut unowned);
    }
    match outcome {
        Outcome::Ok(intent) => publish(PlatformEvent::Activity { kind, intent }),
        _ => publish(PlatformEvent::Error("Activity事件读取失败".into())),
    }
}
unsafe extern "system" fn native_service(
    mut unowned: EnvUnowned,
    _class: JClass,
    kind: jint,
    service: JObject,
    intent: JObject,
    flags: jint,
    start_id: jint,
) -> jint {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<ServiceRequest> {
            let class = env
                .call_method(
                    &service,
                    jni_str!("getClass"),
                    jni_sig!("()Ljava/lang/Class;"),
                    &[],
                )?
                .l()?;
            let name = env
                .call_method(
                    class,
                    jni_str!("getName"),
                    jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?
                .l()?;
            let component = super::components::canonical(&to_string(env, name)?).to_owned();
            Ok(ServiceRequest {
                instance: env.new_global_ref(&service)?,
                component,
                kind,
                intent: read_intent(env, &intent)?,
                flags,
                start_id,
            })
        })
        .into_outcome();
    if !matches!(&outcome, Outcome::Ok(_)) {
        clear_pending_exception(&mut unowned);
    }
    match outcome {
        Outcome::Ok(request) => {
            publish(PlatformEvent::Service {
                kind,
                intent: request.intent.clone(),
                flags,
                start_id,
            });
            if let Some(handler) = SERVICE_HANDLER.get() {
                match catch_unwind(AssertUnwindSafe(|| handler(request))) {
                    Ok(Ok(value)) => value,
                    _ => {
                        if matches!(kind, 1 | 2 | 4 | 5) {
                            stop_failed_service(&mut unowned, &service, kind, start_id);
                        }
                        publish(PlatformEvent::Error("Service策略处理失败".into()));
                        2
                    }
                }
            } else {
                if matches!(kind, 1 | 2 | 4 | 5) {
                    stop_failed_service(&mut unowned, &service, kind, start_id);
                }
                publish(PlatformEvent::Error(
                    "Service业务尚未接入Rust runtime".into(),
                ));
                2
            }
        }
        _ => {
            if matches!(kind, 1 | 2 | 4 | 5) {
                stop_failed_service(&mut unowned, &service, kind, start_id);
            }
            publish(PlatformEvent::Error("Service事件读取失败".into()));
            2
        }
    }
}
fn stop_failed_service(unowned: &mut EnvUnowned, service: &JObject, kind: i32, start_id: i32) {
    clear_pending_exception(unowned);
    let _ = unowned
        .with_env(|env| -> jni::errors::Result<()> {
            if kind == 2 && start_id > 0 {
                env.call_method(
                    service,
                    jni_str!("stopSelf"),
                    jni_sig!("(I)V"),
                    &[JValue::Int(start_id)],
                )?;
            } else {
                env.call_method(service, jni_str!("stopSelf"), jni_sig!("()V"), &[])?;
            }
            Ok(())
        })
        .into_outcome();
    clear_pending_exception(unowned);
}
unsafe extern "system" fn native_receiver(
    mut unowned: EnvUnowned,
    _class: JClass,
    receiver: JObject,
    _context: JObject,
    intent: JObject,
) {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<ReceiverRequest> {
            let facts = read_intent(env, &intent)?;
            let class = env
                .call_method(
                    &receiver,
                    jni_str!("getClass"),
                    jni_sig!("()Ljava/lang/Class;"),
                    &[],
                )?
                .l()?;
            let name = env
                .call_method(
                    class,
                    jni_str!("getName"),
                    jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?
                .l()?;
            let component = super::components::canonical(&string_value(env, name, 512)?).to_owned();
            let pending = env
                .call_method(
                    &receiver,
                    jni_str!("goAsync"),
                    jni_sig!("()Landroid/content/BroadcastReceiver$PendingResult;"),
                    &[],
                )?
                .l()?;
            let global = match env.new_global_ref(&pending) {
                Ok(global) => global,
                Err(error) => {
                    if env.exception_check() {
                        env.exception_clear();
                    }
                    let _ = env.call_method(&pending, jni_str!("finish"), jni_sig!("()V"), &[]);
                    if env.exception_check() {
                        env.exception_clear();
                    }
                    return Err(error);
                }
            };
            Ok(ReceiverRequest {
                component,
                intent: facts,
                completion: ReceiverCompletion {
                    pending: Some(global),
                },
            })
        })
        .into_outcome();
    if !matches!(&outcome, Outcome::Ok(_)) {
        clear_pending_exception(&mut unowned);
    }
    match outcome {
        Outcome::Ok(request) => {
            publish(PlatformEvent::Receiver {
                intent: request.intent.clone(),
            });
            if let Some(handler) = RECEIVER_HANDLER.get() {
                if !matches!(
                    catch_unwind(AssertUnwindSafe(|| handler(request))),
                    Ok(Ok(()))
                ) {
                    publish(PlatformEvent::Error("Receiver策略处理失败".into()));
                }
            } else {
                drop(request);
                publish(PlatformEvent::Error(
                    "Receiver业务尚未接入Rust runtime".into(),
                ));
            }
        }
        _ => publish(PlatformEvent::Error("Receiver事件读取失败".into())),
    }
}
unsafe extern "system" fn native_run(mut unowned: EnvUnowned, _class: JClass, id: jlong) {
    let callback = state()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .callbacks
        .remove(&(id as u64));
    if let Some(callback) = callback {
        if catch_unwind(AssertUnwindSafe(callback)).is_err() {
            publish(PlatformEvent::Error("Android主线程效果失败".into()));
        }
    }
    clear_pending_exception(&mut unowned);
}

fn read_intent(env: &mut Env, intent: &JObject) -> jni::errors::Result<IntentFacts> {
    if intent.is_null() {
        return Ok(IntentFacts::default());
    }
    let action = env
        .call_method(
            intent,
            jni_str!("getAction"),
            jni_sig!("()Ljava/lang/String;"),
            &[],
        )?
        .l()?;
    let action = if action.is_null() {
        None
    } else {
        Some(to_string(env, action)?)
    };
    let key = env.new_string("open_terminal")?;
    let open_terminal = env
        .call_method(
            intent,
            jni_str!("getBooleanExtra"),
            jni_sig!("(Ljava/lang/String;Z)Z"),
            &[JValue::Object(&key), JValue::Bool(false)],
        )?
        .z()?;
    if open_terminal {
        env.call_method(
            intent,
            jni_str!("removeExtra"),
            jni_sig!("(Ljava/lang/String;)V"),
            &[JValue::Object(&key)],
        )?;
    }
    let run_id = intent_string_extra(env, intent, "run_id")?;
    let conversation_id = intent_string_extra(env, intent, "conversation_id")?;
    let schedule_id = intent_string_extra(env, intent, "schedule_id")?;
    let fire_token = intent_string_extra(env, intent, "fire_token")?;
    let alarm_generation = intent_string_extra(env, intent, "alarm_generation")?;
    let terminal_session_id = intent_string_extra(env, intent, "terminal_session_id")?;
    let process_token = intent_string_extra(env, intent, "process_token")?;
    let job_id = intent_string_extra(env, intent, "job_id")?;
    for value in [
        &run_id,
        &conversation_id,
        &fire_token,
        &alarm_generation,
        &terminal_session_id,
        &process_token,
        &job_id,
    ]
    .into_iter()
    .flatten()
    {
        if value.is_empty()
            || value.len() > 128
            || !value
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_'))
        {
            return Err(jni::errors::Error::NullPtr(
                "invalid run identity in Intent",
            ));
        }
    }
    if schedule_id
        .as_ref()
        .is_some_and(|value| value.is_empty() || value.len() > 128 || value.contains('\0'))
    {
        return Err(jni::errors::Error::NullPtr("invalid schedule identity"));
    }
    let scheduled_key = env.new_string("scheduled_at")?;
    let scheduled_at = if env
        .call_method(
            intent,
            jni_str!("hasExtra"),
            jni_sig!("(Ljava/lang/String;)Z"),
            &[JValue::Object(&scheduled_key)],
        )?
        .z()?
    {
        let value = env
            .call_method(
                intent,
                jni_str!("getLongExtra"),
                jni_sig!("(Ljava/lang/String;J)J"),
                &[JValue::Object(&scheduled_key), JValue::Long(0)],
            )?
            .j()?;
        if value <= 0 {
            return Err(jni::errors::Error::NullPtr("invalid schedule time"));
        }
        Some(value)
    } else {
        None
    };
    let component = env
        .call_method(
            intent,
            jni_str!("getComponent"),
            jni_sig!("()Landroid/content/ComponentName;"),
            &[],
        )?
        .l()?;
    let component = if component.is_null() {
        None
    } else {
        let name = env
            .call_method(
                component,
                jni_str!("getClassName"),
                jni_sig!("()Ljava/lang/String;"),
                &[],
            )?
            .l()?;
        Some(to_string(env, name)?)
    };
    let data = env
        .call_method(
            intent,
            jni_str!("getDataString"),
            jni_sig!("()Ljava/lang/String;"),
            &[],
        )?
        .l()?;
    let data_uri = if data.is_null() {
        None
    } else {
        Some(to_string(env, data)?)
    };
    Ok(IntentFacts {
        action,
        open_terminal,
        run_id,
        conversation_id,
        component,
        data_uri,
        schedule_id,
        fire_token,
        alarm_generation,
        scheduled_at,
        terminal_session_id,
        process_token,
        job_id,
    })
}
unsafe extern "system" fn native_activity_result(
    mut unowned: EnvUnowned,
    _class: JClass,
    _activity: JObject,
    code: jint,
    result: jint,
    intent: JObject,
) {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<_> {
            if intent.is_null() {
                Ok(None)
            } else {
                Ok(Some(Arc::new(env.new_global_ref(intent)?)))
            }
        })
        .into_outcome();
    match outcome {
        Outcome::Ok(intent) => {
            let result = catch_unwind(AssertUnwindSafe(|| {
                if !crate::file_transfer::on_activity_result(code, result, intent.clone())
                    && !crate::plugin_assets::runtime::on_activity_result(
                        code,
                        result,
                        intent.clone(),
                    )
                    && !crate::skill_import::device::on_activity_result(
                        code,
                        result,
                        intent.clone(),
                    )
                {
                    super::attachments::on_activity_result(code, result, intent);
                }
            }));
            if result.is_err() {
                if !crate::file_transfer::on_result_error(
                    code,
                    "文件保存返回处理异常；未自动开始或重复写入",
                ) && !crate::plugin_assets::runtime::on_result_error(
                    code,
                    "插件包返回处理异常，未自动安装",
                ) && !crate::skill_import::device::on_result_error(
                    code,
                    "技能导入返回处理异常，未自动提交",
                ) {
                    super::attachments::on_result_error(code, "附件返回处理异常，未修改会话草稿");
                }
                publish(PlatformEvent::Error("附件返回处理异常".into()));
            }
        }
        _ => {
            if !crate::file_transfer::on_result_error(code, "文件保存返回无法读取；未写入目标")
                && !crate::plugin_assets::runtime::on_result_error(
                    code,
                    "插件包返回无法读取，未自动安装",
                )
                && !crate::skill_import::device::on_result_error(
                    code,
                    "技能导入返回无法读取，未自动提交",
                )
            {
                super::attachments::on_result_error(code, "附件返回无法读取，未修改会话草稿");
            }
            publish(PlatformEvent::Error("附件返回无法读取".into()));
        }
    }
    clear_pending_exception(&mut unowned);
}
unsafe extern "system" fn native_permissions_result(
    mut unowned: EnvUnowned,
    _class: JClass,
    _activity: JObject,
    code: jint,
    names: JObjectArray,
    grants: JIntArray,
) {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<(Vec<String>, Vec<i32>)> {
            let count = env.get_array_length(&names)?;
            let granted = env.get_array_length(&grants)?;
            if !(0..=8).contains(&count) || !(0..=8).contains(&granted) {
                return Err(jni::errors::Error::NullPtr("permission result budget"));
            }
            let mut values = Vec::new();
            for index in 0..count as usize {
                let value = env.get_object_array_element(&names, index)?;
                values.push(string_value(env, value, 256)?);
            }
            let mut outcomes = vec![0; granted as usize];
            env.get_int_array_region(&grants, 0, &mut outcomes)?;
            Ok((values, outcomes))
        })
        .into_outcome();
    match outcome {
        Outcome::Ok((names, grants)) => {
            if catch_unwind(AssertUnwindSafe(|| {
                super::permissions::on_result(code, names, grants)
            }))
            .is_err()
            {
                super::permissions::on_result_error(code, "权限返回处理异常，未恢复操作");
                publish(PlatformEvent::Error("权限返回处理异常".into()));
            }
        }
        _ => {
            super::permissions::on_result(code, Vec::new(), vec![-1]);
            publish(PlatformEvent::Error("权限返回读取失败，未恢复操作".into()));
        }
    }
    clear_pending_exception(&mut unowned);
}
unsafe extern "system" fn native_tts_init(
    mut unowned: EnvUnowned,
    _class: JClass,
    generation: jlong,
    status: jint,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        super::voice::on_tts_init(generation as u64, status)
    }));
    clear_pending_exception(&mut unowned);
}
unsafe extern "system" fn native_tts_progress(
    mut unowned: EnvUnowned,
    _class: JClass,
    generation: jlong,
    id: JObject,
    kind: jint,
    code: jint,
) {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<String> {
            if id.is_null() {
                Ok(String::new())
            } else {
                string_value(env, id, 256)
            }
        })
        .into_outcome();
    if let Outcome::Ok(id) = outcome {
        let _ = catch_unwind(AssertUnwindSafe(|| {
            super::voice::on_tts_progress(generation as u64, id, kind, code)
        }));
    }
    clear_pending_exception(&mut unowned);
}
unsafe extern "system" fn native_audio_focus(
    mut unowned: EnvUnowned,
    _class: JClass,
    generation: jlong,
    change: jint,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        super::voice::on_audio_focus(generation as u64, change)
    }));
    clear_pending_exception(&mut unowned);
}
unsafe extern "system" fn native_accessibility(
    mut unowned: EnvUnowned,
    _class: JClass,
    instance: JObject,
    kind: jint,
) {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<_> { Ok(Arc::new(env.new_global_ref(instance)?)) })
        .into_outcome();
    match outcome {
        Outcome::Ok(instance) => {
            if !matches!(
                catch_unwind(AssertUnwindSafe(|| super::automation::on_service_event(
                    instance, kind
                ))),
                Ok(Ok(()))
            ) {
                publish(PlatformEvent::Error("无障碍生命周期处理失败".into()));
            }
        }
        _ => publish(PlatformEvent::Error("无障碍服务实例无法读取".into())),
    }
    clear_pending_exception(&mut unowned);
}
unsafe extern "system" fn native_gesture_result(
    mut unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    completed: jni::sys::jboolean,
) {
    if handle > 0
        && catch_unwind(AssertUnwindSafe(|| {
            super::automation::on_gesture_result(handle as u64, completed)
        }))
        .is_err()
    {
        publish(PlatformEvent::Error("手势终态处理异常".into()));
    }
    clear_pending_exception(&mut unowned);
}
unsafe extern "system" fn native_screenshot_result(
    mut unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    result: JObject,
    error: jint,
) {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<()> {
            super::automation::on_screenshot_result(env, handle.max(0) as u64, &result, error)
        })
        .into_outcome();
    if !matches!(outcome, Outcome::Ok(())) {
        publish(PlatformEvent::Error("截图回调处理失败，未发送图像".into()));
    }
    clear_pending_exception(&mut unowned);
}
unsafe extern "system" fn native_console_draw(
    mut unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    canvas: JObject,
) {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<()> {
            if handle <= 0 || canvas.is_null() {
                return Ok(());
            }
            super::float_console::draw(env, handle as u64, &canvas)
        })
        .into_outcome();
    if !matches!(outcome, Outcome::Ok(())) {
        publish(PlatformEvent::Error("悬浮控制台绘制失败".into()));
    }
    clear_pending_exception(&mut unowned);
}
unsafe extern "system" fn native_console_touch(
    mut unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    motion: JObject,
) -> jboolean {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<bool> {
            if handle <= 0 || motion.is_null() {
                return Ok(false);
            }
            super::float_console::touch(env, handle as u64, &motion)
        })
        .into_outcome();
    let accepted = match outcome {
        Outcome::Ok(accepted) => accepted,
        _ => {
            publish(PlatformEvent::Error("悬浮控制台输入事件失败".into()));
            false
        }
    };
    clear_pending_exception(&mut unowned);
    accepted
}
unsafe extern "system" fn native_console_accessibility(
    mut unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    node: JObject,
    action: jint,
) -> jboolean {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<bool> {
            if handle <= 0 {
                return Ok(false);
            }
            super::float_console::accessibility(env, handle as u64, &node, action)
        })
        .into_outcome();
    let accepted = match outcome {
        Outcome::Ok(value) => value,
        _ => {
            publish(PlatformEvent::Error("悬浮无障碍事件失败".into()));
            false
        }
    };
    clear_pending_exception(&mut unowned);
    accepted
}
// Native UI accessibility callbacks execute on Android's framework thread.
// Local references remain in this callback's Env; failures and panics return
// the platform's normal null/false answer after clearing pending exceptions.
unsafe extern "system" fn native_ui_accessibility_node<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    virtual_id: jint,
) -> JObject<'local> {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        unowned
            .with_env(|env| -> jni::errors::Result<JObject<'local>> {
                if handle <= 0 {
                    return Ok(JObject::null());
                }
                super::accessibility::node(env, handle as u64, virtual_id)
            })
            .into_outcome()
    }));
    let result = match outcome {
        Ok(Outcome::Ok(node)) => node,
        _ => JObject::null(),
    };
    clear_pending_exception(&mut unowned);
    result
}
unsafe extern "system" fn native_ui_accessibility_focus<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    focus_kind: jint,
) -> JObject<'local> {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        unowned
            .with_env(|env| -> jni::errors::Result<JObject<'local>> {
                if handle <= 0 || ![1, 2].contains(&focus_kind) {
                    return Ok(JObject::null());
                }
                super::accessibility::focus(env, handle as u64, focus_kind)
            })
            .into_outcome()
    }));
    let result = match outcome {
        Ok(Outcome::Ok(node)) => node,
        _ => JObject::null(),
    };
    clear_pending_exception(&mut unowned);
    result
}
unsafe extern "system" fn native_ui_accessibility_action<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    virtual_id: jint,
    action: jint,
    arguments: JObject<'local>,
) -> jboolean {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        unowned
            .with_env(|env| -> jni::errors::Result<bool> {
                if handle <= 0 {
                    return Ok(false);
                }
                super::accessibility::action(env, handle as u64, virtual_id, action, &arguments)
            })
            .into_outcome()
    }));
    clear_pending_exception(&mut unowned);
    matches!(outcome, Ok(Outcome::Ok(true)))
}
unsafe extern "system" fn native_ui_accessibility_hover<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    motion: JObject<'local>,
) -> jboolean {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        unowned
            .with_env(|env| -> jni::errors::Result<bool> {
                if handle <= 0 || motion.is_null() {
                    return Ok(false);
                }
                super::accessibility::hover(env, handle as u64, &motion)
            })
            .into_outcome()
    }));
    clear_pending_exception(&mut unowned);
    matches!(outcome, Ok(Outcome::Ok(true)))
}
unsafe extern "system" fn native_ui_accessibility_search<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    query: JObject<'local>,
    virtual_id: jint,
) -> JObject<'local> {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        unowned
            .with_env(|env| -> jni::errors::Result<JObject<'local>> {
                if handle <= 0 || query.is_null() {
                    return Ok(JObject::null());
                }
                super::accessibility::search(env, handle as u64, &query, virtual_id)
            })
            .into_outcome()
    }));
    let result = match outcome {
        Ok(Outcome::Ok(nodes)) => nodes,
        _ => JObject::null(),
    };
    clear_pending_exception(&mut unowned);
    result
}
unsafe extern "system" fn native_overlay_draw(
    mut unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    canvas: JObject,
) {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<()> {
            if handle <= 0 || canvas.is_null() {
                return Ok(());
            }
            super::automation::overlay::draw(env, handle as u64, &canvas)
        })
        .into_outcome();
    if !matches!(outcome, Outcome::Ok(())) {
        publish(PlatformEvent::Error("界面操作指示绘制失败".into()));
    }
    clear_pending_exception(&mut unowned);
}
unsafe extern "system" fn native_virtual_frame(
    mut unowned: EnvUnowned,
    _class: JClass,
    generation: jlong,
    reader: JObject,
) {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<()> {
            if generation <= 0 || reader.is_null() {
                return Ok(());
            }
            super::virtual_screen::on_image(env, generation as u64, &reader)
        })
        .into_outcome();
    if !matches!(outcome, Outcome::Ok(())) {
        publish(PlatformEvent::Error("虚拟屏帧读取失败，未发布图像".into()));
    }
    clear_pending_exception(&mut unowned);
}
unsafe extern "system" fn native_virtual_display(
    mut unowned: EnvUnowned,
    _class: JClass,
    generation: jlong,
    kind: jint,
) {
    if generation > 0
        && catch_unwind(AssertUnwindSafe(|| {
            super::virtual_screen::on_display_event(generation as u64, kind)
        }))
        .is_err()
    {
        publish(PlatformEvent::Error("虚拟屏生命周期回调失败".into()));
    }
    clear_pending_exception(&mut unowned);
}
unsafe extern "system" fn native_virtual_draw(
    mut unowned: EnvUnowned,
    _class: JClass,
    generation: jlong,
    canvas: JObject,
) {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<()> {
            if generation <= 0 || canvas.is_null() {
                return Ok(());
            }
            super::virtual_screen::on_preview_draw(env, generation as u64, &canvas)
        })
        .into_outcome();
    if !matches!(outcome, Outcome::Ok(())) {
        publish(PlatformEvent::Error("虚拟屏预览绘制失败".into()));
    }
    clear_pending_exception(&mut unowned);
}
unsafe extern "system" fn native_virtual_touch(
    mut unowned: EnvUnowned,
    _class: JClass,
    generation: jlong,
    event: JObject,
) -> jni::sys::jboolean {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<bool> {
            if generation <= 0 || event.is_null() {
                return Ok(false);
            }
            super::virtual_screen::on_preview_touch(env, generation as u64, &event)
        })
        .into_outcome();
    clear_pending_exception(&mut unowned);
    matches!(outcome, Outcome::Ok(true))
}
unsafe extern "system" fn native_virtual_host(
    mut unowned: EnvUnowned,
    _class: JClass,
    activity: JObject,
    kind: jint,
) {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<_> { Ok(Arc::new(env.new_global_ref(&activity)?)) })
        .into_outcome();
    if let Outcome::Ok(instance) = outcome {
        if !matches!(
            catch_unwind(AssertUnwindSafe(|| super::virtual_screen::on_host_event(
                instance, kind
            ))),
            Ok(Ok(()))
        ) {
            publish(PlatformEvent::Error("虚拟屏宿主页显示或关闭失败".into()));
            let _ = unowned
                .with_env(|env| -> jni::errors::Result<()> {
                    if env.exception_check() {
                        env.exception_clear();
                    }
                    env.call_method(activity, jni_str!("finish"), jni_sig!("()V"), &[])?;
                    Ok(())
                })
                .into_outcome();
        }
    }
    clear_pending_exception(&mut unowned);
}
unsafe extern "system" fn native_shizuku_provider_init(
    mut unowned: EnvUnowned,
    _class: JClass,
    provider: JObject,
) {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<_> {
            early_provider_init(env, &provider)?;
            let context = env
                .call_method(
                    provider,
                    jni_str!("getContext"),
                    jni_sig!("()Landroid/content/Context;"),
                    &[],
                )?
                .l()?;
            Ok(Arc::new(env.new_global_ref(context)?))
        })
        .into_outcome();
    if let Outcome::Ok(context) = outcome {
        let _ = catch_unwind(AssertUnwindSafe(|| {
            super::shizuku::provider_created(context)
        }));
    }
    clear_pending_exception(&mut unowned);
}
unsafe extern "system" fn native_shizuku_provider<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    provider: JObject<'local>,
    method: JObject<'local>,
    _argument: JObject<'local>,
    extras: JObject<'local>,
) -> JObject<'local> {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<JObject<'local>> {
            let uid = env
                .call_static_method(
                    jni_str!("android/os/Binder"),
                    jni_str!("getCallingUid"),
                    jni_sig!("()I"),
                    &[],
                )?
                .i()?;
            let own_uid = env
                .call_static_method(
                    jni_str!("android/os/Process"),
                    jni_str!("myUid"),
                    jni_sig!("()I"),
                    &[],
                )?
                .i()?;
            if uid == own_uid || ![0, 2000].contains(&uid) || method.is_null() || extras.is_null() {
                return Ok(JObject::null());
            }
            if string_value(env, method, 64)? != "sendBinder" {
                return Ok(JObject::null());
            }
            let class = generated_class("ShizukuBinderContainer")
                .map_err(|_| jni::errors::Error::NullPtr("shizuku parcelable class"))?;
            let loader = env
                .call_method(
                    class.as_ref(),
                    jni_str!("getClassLoader"),
                    jni_sig!("()Ljava/lang/ClassLoader;"),
                    &[],
                )?
                .l()?;
            env.call_method(
                &extras,
                jni_str!("setClassLoader"),
                jni_sig!("(Ljava/lang/ClassLoader;)V"),
                &[JValue::Object(&loader)],
            )?;
            let key = env.new_string("moe.shizuku.privileged.api.intent.extra.BINDER")?;
            let container = env
                .call_method(
                    extras,
                    jni_str!("getParcelable"),
                    jni_sig!("(Ljava/lang/String;)Landroid/os/Parcelable;"),
                    &[JValue::Object(&key)],
                )?
                .l()?;
            if container.is_null() || !env.is_instance_of(&container, class.as_ref())? {
                return Ok(JObject::null());
            }
            let binder = env
                .get_field(
                    container,
                    jni_str!("binder"),
                    jni_sig!("Landroid/os/IBinder;"),
                )?
                .l()?;
            if binder.is_null() {
                return Ok(JObject::null());
            }
            let context = env
                .call_method(
                    provider,
                    jni_str!("getContext"),
                    jni_sig!("()Landroid/content/Context;"),
                    &[],
                )?
                .l()?;
            let context = Arc::new(env.new_global_ref(context)?);
            let binder = Arc::new(env.new_global_ref(binder)?);
            super::shizuku::receive_binder(context, binder, uid)
                .map_err(|_| jni::errors::Error::NullPtr("shizuku binder admission failed"))?;
            env.new_object(jni_str!("android/os/Bundle"), jni_sig!("()V"), &[])
        })
        .into_outcome();
    let result = match outcome {
        Outcome::Ok(value) => value,
        _ => {
            publish(PlatformEvent::Error(
                "Shizuku Provider接入失败，连接尚未获确认".into(),
            ));
            JObject::null()
        }
    };
    clear_pending_exception(&mut unowned);
    result
}
unsafe extern "system" fn native_shizuku_client(
    mut unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
    code: jint,
    data: JObject,
    reply: JObject,
    _flags: jint,
) -> jni::sys::jboolean {
    let outcome = unowned
        .with_env(|env| -> jni::errors::Result<bool> {
            if handle <= 0 || data.is_null() {
                return Ok(false);
            }
            let caller = env
                .call_static_method(
                    jni_str!("android/os/Binder"),
                    jni_str!("getCallingUid"),
                    jni_sig!("()I"),
                    &[],
                )?
                .i()?;
            super::shizuku::on_client_transaction(env, handle as u64, code, &data, &reply, caller)
        })
        .into_outcome();
    let result = match outcome {
        Outcome::Ok(accepted) => accepted,
        _ => {
            publish(PlatformEvent::Error("Shizuku Binder回调解析失败".into()));
            false
        }
    };
    clear_pending_exception(&mut unowned);
    result
}
unsafe extern "system" fn native_shizuku_death(
    mut unowned: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    if handle > 0
        && catch_unwind(AssertUnwindSafe(|| {
            super::shizuku::on_binder_dead(handle as u64)
        }))
        .is_err()
    {
        publish(PlatformEvent::Error("Shizuku断开清理失败".into()));
    }
    clear_pending_exception(&mut unowned);
}
fn intent_string_extra(
    env: &mut Env,
    intent: &JObject,
    name: &str,
) -> jni::errors::Result<Option<String>> {
    let key = env.new_string(name)?;
    let value = env
        .call_method(
            intent,
            jni_str!("getStringExtra"),
            jni_sig!("(Ljava/lang/String;)Ljava/lang/String;"),
            &[JValue::Object(&key)],
        )?
        .l()?;
    if value.is_null() {
        Ok(None)
    } else {
        to_string(env, value).map(Some)
    }
}
fn to_string(env: &mut Env, value: JObject) -> jni::errors::Result<String> {
    let string = env.cast_local::<JString>(value)?;
    let length = env
        .call_method(&string, jni_str!("length"), jni_sig!("()I"), &[])?
        .i()?;
    if length < 0 || length > 131_077 {
        return Err(jni::errors::Error::NullPtr("SDK string budget exceeded"));
    }
    string.try_to_string(env)
}
fn file_path(
    env: &mut Env,
    context: &Global<JObject>,
    method: &jni::strings::JNIStr,
) -> jni::errors::Result<PathBuf> {
    let file = env
        .call_method(context, method, jni_sig!("()Ljava/io/File;"), &[])?
        .l()?;
    let text = env
        .call_method(
            file,
            jni_str!("getAbsolutePath"),
            jni_sig!("()Ljava/lang/String;"),
            &[],
        )?
        .l()?;
    Ok(PathBuf::from(to_string(env, text)?))
}
fn read_paths(env: &mut Env, context: &Global<JObject>) -> jni::errors::Result<PlatformPaths> {
    let files_dir = file_path(env, context, jni_str!("getFilesDir"))?;
    let no_backup_dir = file_path(env, context, jni_str!("getNoBackupFilesDir"))?;
    let cache_dir = file_path(env, context, jni_str!("getCacheDir"))?;
    let external = env
        .call_method(
            context,
            jni_str!("getExternalFilesDir"),
            jni_sig!("(Ljava/lang/String;)Ljava/io/File;"),
            &[JValue::Object(&JObject::null())],
        )?
        .l()?;
    let external_files_dir = if external.is_null() {
        None
    } else {
        let path = env
            .call_method(
                external,
                jni_str!("getAbsolutePath"),
                jni_sig!("()Ljava/lang/String;"),
                &[],
            )?
            .l()?;
        Some(PathBuf::from(to_string(env, path)?))
    };
    let name = env
        .call_method(
            context,
            jni_str!("getPackageName"),
            jni_sig!("()Ljava/lang/String;"),
            &[],
        )?
        .l()?;
    let package_name = to_string(env, name)?;
    let info = env
        .call_method(
            context,
            jni_str!("getApplicationInfo"),
            jni_sig!("()Landroid/content/pm/ApplicationInfo;"),
            &[],
        )?
        .l()?;
    let native = env
        .get_field(
            info,
            jni_str!("nativeLibraryDir"),
            jni_sig!("Ljava/lang/String;"),
        )?
        .l()?;
    let native_library_dir = PathBuf::from(to_string(env, native)?);
    let sdk_int = env
        .get_static_field(
            jni_str!("android/os/Build$VERSION"),
            jni_str!("SDK_INT"),
            jni_sig!("I"),
        )?
        .i()?;
    Ok(PlatformPaths {
        files_dir,
        no_backup_dir,
        cache_dir,
        external_files_dir,
        native_library_dir,
        package_name,
        sdk_int,
    })
}

fn key(env: &mut Env, create: bool) -> jni::errors::Result<Arc<Global<JObject<'static>>>> {
    // Concurrent first saves must not generate two different keys for one alias.
    let _creation = KEY_INIT.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(key) = state()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .key
        .clone()
    {
        return Ok(key);
    }
    let provider = env.new_string("AndroidKeyStore")?;
    let store = env
        .call_static_method(
            jni_str!("java/security/KeyStore"),
            jni_str!("getInstance"),
            jni_sig!("(Ljava/lang/String;)Ljava/security/KeyStore;"),
            &[JValue::Object(&provider)],
        )?
        .l()?;
    env.call_method(
        &store,
        jni_str!("load"),
        jni_sig!("(Ljava/io/InputStream;[C)V"),
        &[
            JValue::Object(&JObject::null()),
            JValue::Object(&JObject::null()),
        ],
    )?;
    let alias = env.new_string(KEY_ALIAS)?;
    let mut key = env
        .call_method(
            &store,
            jni_str!("getKey"),
            jni_sig!("(Ljava/lang/String;[C)Ljava/security/Key;"),
            &[JValue::Object(&alias), JValue::Object(&JObject::null())],
        )?
        .l()?;
    if key.is_null() {
        if !create {
            return Err(jni::errors::Error::NullPtr("Keystore key unavailable"));
        }
        let algorithm = env.new_string("AES")?;
        let generator = env
            .call_static_method(
                jni_str!("javax/crypto/KeyGenerator"),
                jni_str!("getInstance"),
                jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Ljavax/crypto/KeyGenerator;"),
                &[JValue::Object(&algorithm), JValue::Object(&provider)],
            )?
            .l()?;
        let builder = env.new_object(
            jni_str!("android/security/keystore/KeyGenParameterSpec$Builder"),
            jni_sig!("(Ljava/lang/String;I)V"),
            &[JValue::Object(&alias), JValue::Int(3)],
        )?;
        let gcm = env.new_string("GCM")?;
        let padding = env.new_string("NoPadding")?;
        let modes = env.new_object_array(1, jni_str!("java/lang/String"), &gcm)?;
        let paddings = env.new_object_array(1, jni_str!("java/lang/String"), &padding)?;
        env.call_method(
            &builder,
            jni_str!("setBlockModes"),
            jni_sig!(
                "([Ljava/lang/String;)Landroid/security/keystore/KeyGenParameterSpec$Builder;"
            ),
            &[JValue::Object(&modes)],
        )?;
        env.call_method(
            &builder,
            jni_str!("setEncryptionPaddings"),
            jni_sig!(
                "([Ljava/lang/String;)Landroid/security/keystore/KeyGenParameterSpec$Builder;"
            ),
            &[JValue::Object(&paddings)],
        )?;
        env.call_method(
            &builder,
            jni_str!("setKeySize"),
            jni_sig!("(I)Landroid/security/keystore/KeyGenParameterSpec$Builder;"),
            &[JValue::Int(256)],
        )?;
        env.call_method(
            &builder,
            jni_str!("setUserAuthenticationRequired"),
            jni_sig!("(Z)Landroid/security/keystore/KeyGenParameterSpec$Builder;"),
            &[JValue::Bool(false)],
        )?;
        let spec = env
            .call_method(
                builder,
                jni_str!("build"),
                jni_sig!("()Landroid/security/keystore/KeyGenParameterSpec;"),
                &[],
            )?
            .l()?;
        env.call_method(
            &generator,
            jni_str!("init"),
            jni_sig!("(Ljava/security/spec/AlgorithmParameterSpec;)V"),
            &[JValue::Object(&spec)],
        )?;
        key = env
            .call_method(
                generator,
                jni_str!("generateKey"),
                jni_sig!("()Ljavax/crypto/SecretKey;"),
                &[],
            )?
            .l()?;
    }
    let key = Arc::new(env.new_global_ref(key)?);
    state().lock().unwrap_or_else(|e| e.into_inner()).key = Some(key.clone());
    Ok(key)
}

pub(super) fn encrypt_secret(plain: &str) -> Result<StoredSecret, SecretError> {
    if VM.get().is_none() {
        return Err(SecretError::Unavailable);
    }
    let result = with_env(|env| {
        let key = key(env, true)?;
        let algorithm = env.new_string("AES/GCM/NoPadding")?;
        let cipher = env
            .call_static_method(
                jni_str!("javax/crypto/Cipher"),
                jni_str!("getInstance"),
                jni_sig!("(Ljava/lang/String;)Ljavax/crypto/Cipher;"),
                &[JValue::Object(&algorithm)],
            )?
            .l()?;
        env.call_method(
            &cipher,
            jni_str!("init"),
            jni_sig!("(ILjava/security/Key;)V"),
            &[JValue::Int(1), JValue::Object(&key)],
        )?;
        let input = env.byte_array_from_slice(plain.as_bytes())?;
        let out = env
            .call_method(
                &cipher,
                jni_str!("doFinal"),
                jni_sig!("([B)[B"),
                &[JValue::Object(&input)],
            )?
            .l()?;
        let out = env.cast_local::<jni::objects::JByteArray>(out)?;
        let out = env.convert_byte_array(out)?;
        let iv = env
            .call_method(cipher, jni_str!("getIV"), jni_sig!("()[B"), &[])?
            .l()?;
        let iv = env.cast_local::<jni::objects::JByteArray>(iv)?;
        let mut bytes = env.convert_byte_array(iv)?;
        if bytes.len() != 12 {
            return Err(jni::errors::Error::NullPtr("Unexpected GCM IV"));
        }
        bytes.extend_from_slice(&out);
        let bytes = env.byte_array_from_slice(&bytes)?;
        let encoded = env
            .call_static_method(
                jni_str!("android/util/Base64"),
                jni_str!("encodeToString"),
                jni_sig!("([BI)Ljava/lang/String;"),
                &[JValue::Object(&bytes), JValue::Int(2)],
            )?
            .l()?;
        to_string(env, encoded)
    })
    .map_err(|_| SecretError::EncryptionFailed)?;
    StoredSecret::from_ciphertext(format!("enc1:{result}"))
}
pub(super) fn decrypt_secret(stored: &StoredSecret) -> Result<String, SecretError> {
    if VM.get().is_none() {
        return Err(SecretError::Unavailable);
    }
    with_env(|env| {
        let key = key(env, false)?;
        let encoded = env.new_string(&stored.as_ciphertext()[5..])?;
        let raw = env
            .call_static_method(
                jni_str!("android/util/Base64"),
                jni_str!("decode"),
                jni_sig!("(Ljava/lang/String;I)[B"),
                &[JValue::Object(&encoded), JValue::Int(2)],
            )?
            .l()?;
        let raw = env.cast_local::<jni::objects::JByteArray>(raw)?;
        let raw = env.convert_byte_array(raw)?;
        if raw.len() < 28 {
            return Err(jni::errors::Error::NullPtr("Invalid GCM envelope"));
        }
        let iv = env.byte_array_from_slice(&raw[..12])?;
        let params = env.new_object(
            jni_str!("javax/crypto/spec/GCMParameterSpec"),
            jni_sig!("(I[B)V"),
            &[JValue::Int(128), JValue::Object(&iv)],
        )?;
        let algorithm = env.new_string("AES/GCM/NoPadding")?;
        let cipher = env
            .call_static_method(
                jni_str!("javax/crypto/Cipher"),
                jni_str!("getInstance"),
                jni_sig!("(Ljava/lang/String;)Ljavax/crypto/Cipher;"),
                &[JValue::Object(&algorithm)],
            )?
            .l()?;
        env.call_method(
            &cipher,
            jni_str!("init"),
            jni_sig!("(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V"),
            &[
                JValue::Int(2),
                JValue::Object(&key),
                JValue::Object(&params),
            ],
        )?;
        let input = env.byte_array_from_slice(&raw[12..])?;
        let plain = env
            .call_method(
                cipher,
                jni_str!("doFinal"),
                jni_sig!("([B)[B"),
                &[JValue::Object(&input)],
            )?
            .l()?;
        let plain = env.cast_local::<jni::objects::JByteArray>(plain)?;
        let plain = env.convert_byte_array(plain)?;
        Ok(String::from_utf8_lossy(&plain).into_owned())
    })
    .map_err(|_| SecretError::DecryptionFailed)
}

unsafe extern "system" fn native_perf_frame(
    mut unowned: EnvUnowned,
    _class: JClass,
    token: jlong,
    frame_time: jlong,
) {
    let _ = unowned.with_env(|env| -> jni::errors::Result<()> {
        if token > 0 {
            crate::performance::frames::android::on_java_frame(env, token as u64, frame_time)?;
        }
        if env.exception_check() {
            env.exception_clear();
        }
        Ok(())
    });
}
