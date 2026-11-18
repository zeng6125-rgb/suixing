use super::*;
use crate::platform::android;
use jni::{JValue, jni_sig, jni_str, objects::JObject, refs::Global};
use std::{
    collections::{BTreeMap, VecDeque},
    sync::{
        Mutex, OnceLock,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};
pub(super) type Object = Arc<Global<JObject<'static>>>;
pub(super) struct Screen {
    pub identity: Identity,
    pub reader: Arc<Mutex<Option<Object>>>,
    display: Option<Object>,
    thread: Option<Object>,
    pub frame: Option<Arc<PixelFrame>>,
    pub preview: Option<Arc<PixelFrame>>,
    pub last_frame_at_ms: Option<u64>,
    pub last_preview: Instant,
    pub closing: bool,
    pub preview_requested: bool,
}
impl Screen {
    fn release(&mut self) -> Result<(), String> {
        let mut errors = Vec::new();
        self.closing = true;
        self.frame = None;
        self.preview = None;
        if let Some(display) = self.display.as_ref() {
            let released = android::invoke(|env| {
                env.call_method(display.as_ref(), jni_str!("release"), jni_sig!("()V"), &[])?;
                Ok(())
            });
            if released.is_ok() {
                self.display = None;
            } else {
                errors.push("VirtualDisplay释放未确认");
            }
        }
        {
            let mut slot = self.reader.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(reader) = slot.as_ref() {
                let detached = android::invoke(|env| {
                    env.call_method(reader.as_ref(), jni_str!("setOnImageAvailableListener"),
                        jni_sig!("(Landroid/media/ImageReader$OnImageAvailableListener;Landroid/os/Handler;)V"),
                        &[JValue::Object(&JObject::null()), JValue::Object(&JObject::null())])?;
                    Ok(())
                });
                let closed = android::invoke(|env| {
                    env.call_method(reader.as_ref(), jni_str!("close"), jni_sig!("()V"), &[])?;
                    Ok(())
                });
                if closed.is_ok() {
                    *slot = None;
                } else {
                    errors.push("ImageReader关闭未确认");
                }
                if detached.is_err() {
                    errors.push("ImageReader监听移除未确认");
                }
            }
        }
        if let Some(thread) = self.thread.as_ref() {
            let stopped = android::invoke(|env| {
                let accepted = env
                    .call_method(
                        thread.as_ref(),
                        jni_str!("quitSafely"),
                        jni_sig!("()Z"),
                        &[],
                    )?
                    .z()?;
                let alive = env
                    .call_method(thread.as_ref(), jni_str!("isAlive"), jni_sig!("()Z"), &[])?
                    .z()?;
                Ok(accepted || !alive)
            });
            if stopped == Ok(true) {
                self.thread = None;
            } else {
                errors.push("虚拟屏HandlerThread关闭请求未确认");
            }
        }
        if errors.is_empty() {
            Ok(())
        } else {
            Err(errors.join("；"))
        }
    }
}
impl Drop for Screen {
    fn drop(&mut self) {
        let _ = self.release();
    }
}
struct Pending {
    operation: OperationKey,
    cancelled: Arc<AtomicBool>,
    deadline: Instant,
}
#[derive(Default)]
pub(super) struct Registry {
    pub screen: Option<Screen>,
    requests: BTreeMap<String, Pending>,
    results: VecDeque<Outcome>,
    pub error: Option<String>,
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
pub(super) fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
}
pub(super) fn error(message: String) {
    registry().lock().unwrap_or_else(|e| e.into_inner()).error = Some(message);
}
pub fn status() -> Status {
    let state = registry().lock().unwrap_or_else(|e| e.into_inner());
    let screen = state.screen.as_ref();
    let preview = super::preview::status();
    Status {
        identity: screen.map(|s| s.identity.clone()),
        closing: screen.is_some_and(|s| s.closing),
        pending_operations: state.requests.len(),
        has_frame: screen.is_some_and(|s| !s.closing && s.frame.is_some()),
        last_frame_at_ms: screen.and_then(|s| s.last_frame_at_ms),
        preview_requested: screen.is_some_and(|s| s.preview_requested),
        preview_window_owned: preview.0,
        preview_first_draw_received: preview.1,
        error: state.error.clone().or(preview.2),
    }
}
pub fn verify(identity: &Identity) -> Result<(), String> {
    let state = registry().lock().unwrap_or_else(|e| e.into_inner());
    if !state
        .screen
        .as_ref()
        .is_some_and(|s| !s.closing && s.identity == *identity && s.display.is_some())
    {
        return Err("虚拟屏已关闭或代次变更，请重新获取完整display身份".into());
    }
    Ok(())
}
pub fn request(operation: OperationKey, action: Action) -> Result<(), String> {
    operation.validate()?;
    if let Action::LaunchOwn { component, .. } = &action {
        validate_component(component)?;
    }
    let id = operation.id.clone();
    let cancelled = Arc::new(AtomicBool::new(false));
    {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        if state.requests.contains_key(&id) || state.results.iter().any(|r| r.operation.id == id) {
            return Err("虚拟屏操作代号已经登记或等待领取结果".into());
        }
        if state.requests.len() + state.results.len() >= 8 {
            return Err("虚拟屏操作和结果达到8个预算".into());
        }
        state.requests.insert(
            id.clone(),
            Pending {
                operation,
                cancelled: cancelled.clone(),
                deadline: Instant::now() + Duration::from_secs(30),
            },
        );
    }
    let callback_id = id.clone();
    let posted = android::post_to_main(move || {
        let expired = registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .requests
            .get(&callback_id)
            .is_none_or(|p| Instant::now() >= p.deadline);
        let result = if cancelled.load(Ordering::Acquire) || expired {
            Err("虚拟屏操作已取消或主线程排队超时，未派发新动作".into())
        } else {
            match action {
                Action::Open { preview } => open(preview, &cancelled),
                Action::OpenChecked { preview, expected } => {
                    let actual = registry()
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .screen
                        .as_ref()
                        .map(|s| s.identity.clone());
                    if actual != expected {
                        Err("虚拟屏在审批或排队期间变更，未创建或改动其它屏幕".into())
                    } else {
                        open(preview, &cancelled)
                    }
                }
                Action::Close { expected } => close(&expected),
                Action::Preview { expected, visible } => set_preview(&expected, visible),
                Action::LaunchOwnHost { expected } => launch_own(&expected, HOST_ACTIVITY),
                Action::LaunchOwn {
                    expected,
                    component,
                } => launch_own(&expected, &component),
            }
        };
        let snapshot = result.map(|_| {
            let mut snapshot = status();
            snapshot.pending_operations = snapshot.pending_operations.saturating_sub(1);
            snapshot
        });
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        if let Some(pending) = state.requests.remove(&callback_id) {
            let cancelled = pending.cancelled.load(Ordering::Acquire);
            state.results.push_back(Outcome {
                operation: pending.operation,
                result: snapshot,
                cancelled,
            });
        }
    });
    if posted.is_err() {
        registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .requests
            .remove(&id);
    }
    posted
}
pub fn cancel(id: &str) {
    if let Some(pending) = registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .requests
        .get(id)
    {
        pending.cancelled.store(true, Ordering::Release);
    }
}
pub fn drain_results() -> Vec<Outcome> {
    registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .results
        .drain(..)
        .collect()
}
fn open(preview: bool, cancelled: &AtomicBool) -> Result<(), String> {
    let existing = {
        let state = registry().lock().unwrap_or_else(|e| e.into_inner());
        if state.screen.as_ref().is_some_and(|s| s.closing) {
            return Err("原虚拟屏资源仍在清理，未创建另一个屏幕".into());
        }
        state.screen.as_ref().map(|s| s.identity.clone())
    };
    if let Some(identity) = existing {
        return set_preview(&identity, preview);
    }
    let generation = NEXT
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| {
            n.checked_add(1).filter(|v| *v <= i64::MAX as u64)
        })
        .map_err(|_| "虚拟屏代次已耗尽")?;
    let context = android::application_context()?;
    let (width, height, density) = android::invoke(|env| {
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
                resources,
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
        if width <= 0 || height <= 0 || density <= 0 || width > 32_768 || height > 32_768 {
            return Err(jni::errors::Error::NullPtr("virtual source dimensions"));
        }
        let width = (width as u32 * 45 / 100).max(320);
        let height = (height as u32 * 45 / 100).max(320);
        if width as u64 * height as u64 > 4_194_304 {
            return Err(jni::errors::Error::NullPtr("virtual pixel budget"));
        }
        Ok((width, height, (density as u32 * 45 / 100).max(120)))
    })?;
    let mut screen = Screen {
        identity: Identity {
            display_id: -1,
            generation,
            width,
            height,
            density,
        },
        reader: Arc::new(Mutex::new(None)),
        display: None,
        thread: None,
        frame: None,
        preview: None,
        last_frame_at_ms: None,
        last_preview: Instant::now() - Duration::from_secs(1),
        closing: false,
        preview_requested: preview,
    };
    let created: Result<(), String> = (|| {
        let thread = android::invoke(|env| {
            let name = env.new_string("rust-virtual-frames")?;
            let thread = env.new_object(
                jni_str!("android/os/HandlerThread"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[JValue::Object(&name)],
            )?;
            Ok(Arc::new(env.new_global_ref(thread)?))
        })?;
        screen.thread = Some(thread.clone());
        android::invoke(|env| {
            env.call_method(thread.as_ref(), jni_str!("start"), jni_sig!("()V"), &[])?;
            Ok(())
        })?;
        let reader = android::invoke(|env| {
            let reader = env
                .call_static_method(
                    jni_str!("android/media/ImageReader"),
                    jni_str!("newInstance"),
                    jni_sig!("(IIII)Landroid/media/ImageReader;"),
                    &[
                        JValue::Int(width as i32),
                        JValue::Int(height as i32),
                        JValue::Int(1),
                        JValue::Int(3),
                    ],
                )?
                .l()?;
            Ok(Arc::new(env.new_global_ref(reader)?))
        })?;
        *screen.reader.lock().unwrap_or_else(|e| e.into_inner()) = Some(reader.clone());
        let (handler, surface, manager, callback) = android::invoke(|env| {
            let looper = env
                .call_method(
                    thread.as_ref(),
                    jni_str!("getLooper"),
                    jni_sig!("()Landroid/os/Looper;"),
                    &[],
                )?
                .l()?;
            let handler = env.new_object(
                jni_str!("android/os/Handler"),
                jni_sig!("(Landroid/os/Looper;)V"),
                &[JValue::Object(&looper)],
            )?;
            let class = android::generated_class("NativeVirtualFrameListener")
                .map_err(|_| jni::errors::Error::NullPtr("virtual image callback class"))?;
            let listener = env.new_object(
                class.as_ref(),
                jni_sig!("(J)V"),
                &[JValue::Long(generation as i64)],
            )?;
            env.call_method(
                reader.as_ref(),
                jni_str!("setOnImageAvailableListener"),
                jni_sig!(
                    "(Landroid/media/ImageReader$OnImageAvailableListener;Landroid/os/Handler;)V"
                ),
                &[JValue::Object(&listener), JValue::Object(&handler)],
            )?;
            let surface = env
                .call_method(
                    reader.as_ref(),
                    jni_str!("getSurface"),
                    jni_sig!("()Landroid/view/Surface;"),
                    &[],
                )?
                .l()?;
            let service = env.new_string("display")?;
            let manager = env
                .call_method(
                    context.as_ref(),
                    jni_str!("getSystemService"),
                    jni_sig!("(Ljava/lang/String;)Ljava/lang/Object;"),
                    &[JValue::Object(&service)],
                )?
                .l()?;
            let class = android::generated_class("NativeVirtualDisplayCallback")
                .map_err(|_| jni::errors::Error::NullPtr("virtual display callback class"))?;
            let callback = env.new_object(
                class.as_ref(),
                jni_sig!("(J)V"),
                &[JValue::Long(generation as i64)],
            )?;
            Ok((
                Arc::new(env.new_global_ref(handler)?),
                Arc::new(env.new_global_ref(surface)?),
                Arc::new(env.new_global_ref(manager)?),
                Arc::new(env.new_global_ref(callback)?),
            ))
        })?;
        let display = android::invoke(|env| {
            let name = env.new_string("JAgent Virtual Screen")?;
            let display = env.call_method(manager.as_ref(), jni_str!("createVirtualDisplay"),
                jni_sig!("(Ljava/lang/String;IIILandroid/view/Surface;ILandroid/hardware/display/VirtualDisplay$Callback;Landroid/os/Handler;)Landroid/hardware/display/VirtualDisplay;"),
                &[JValue::Object(&name), JValue::Int(width as i32), JValue::Int(height as i32), JValue::Int(density as i32),
                    JValue::Object(surface.as_ref()), JValue::Int(8), JValue::Object(callback.as_ref()), JValue::Object(handler.as_ref())])?.l()?;
            if display.is_null() {
                return Err(jni::errors::Error::NullPtr("virtual display missing"));
            }
            Ok(Arc::new(env.new_global_ref(display)?))
        })?;
        screen.display = Some(display.clone());
        let id = android::invoke(|env| {
            let display = env
                .call_method(
                    display.as_ref(),
                    jni_str!("getDisplay"),
                    jni_sig!("()Landroid/view/Display;"),
                    &[],
                )?
                .l()?;
            env.call_method(display, jni_str!("getDisplayId"), jni_sig!("()I"), &[])?
                .i()
        })?;
        if id <= 0 {
            return Err("系统未返回独立副屏代号".into());
        }
        screen.identity.display_id = id;
        if cancelled.load(Ordering::Acquire) {
            return Err("虚拟屏开启期间已取消，正在释放新屏幕".into());
        }
        Ok(())
    })();
    if let Err(cause) = created {
        let cleanup = screen.release();
        if let Err(cleanup) = cleanup {
            let message = format!("{cause}；{cleanup}");
            let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
            state.error = Some(message.clone());
            state.screen = Some(screen);
            return Err(message);
        }
        return Err(cause);
    }
    let identity = screen.identity.clone();
    {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        state.screen = Some(screen);
        state.error = None;
    }
    if let Err(message) = launch_own(&identity, HOST_ACTIVITY) {
        error(format!("虚拟屏已创建，但宿主页尚未启动：{message}"));
    }
    if preview {
        if let Err(message) = set_preview(&identity, true) {
            error(format!("虚拟屏已开启，但预览窗口未显示：{message}"));
        }
    }
    Ok(())
}
fn close(expected: &Identity) -> Result<(), String> {
    {
        let state = registry().lock().unwrap_or_else(|e| e.into_inner());
        if !state
            .screen
            .as_ref()
            .is_some_and(|s| s.identity == *expected)
        {
            return Err("虚拟屏关闭目标已变更".into());
        }
    }
    super::preview::hide()?;
    let mut screen = registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .screen
        .take()
        .ok_or("虚拟屏已关闭")?;
    let released = screen.release();
    if let Err(message) = &released {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        state.error = Some(message.clone());
        state.screen = Some(screen);
    }
    released
}
fn set_preview(expected: &Identity, visible: bool) -> Result<(), String> {
    verify(expected)?;
    {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        let screen = state.screen.as_mut().ok_or("虚拟屏已关闭")?;
        screen.preview_requested = visible;
        if !visible {
            screen.preview = None;
        }
    }
    if visible {
        super::preview::show(expected)
    } else {
        super::preview::hide()
    }
}
fn launch_own(expected: &Identity, component: &str) -> Result<(), String> {
    verify(expected)?;
    if !android::activity_visible() {
        return Err("在副屏启动应用需要当前可见的用户页面".into());
    }
    let context = android::application_context()?;
    android::invoke(|env| {
        let package = env
            .call_method(
                context.as_ref(),
                jni_str!("getPackageName"),
                jni_sig!("()Ljava/lang/String;"),
                &[],
            )?
            .l()?;
        let package = android::string_value(env, package, 256)?;
        let (owner, activity) = component.split_once('/').unwrap_or((&package, component));
        if owner != package {
            return Err(jni::errors::Error::NullPtr(
                "third-party virtual launch needs explicit privileged route",
            ));
        }
        let owner = env.new_string(owner)?;
        let activity = env.new_string(crate::platform::components::installed(activity))?;
        let component = env.new_object(
            jni_str!("android/content/ComponentName"),
            jni_sig!("(Ljava/lang/String;Ljava/lang/String;)V"),
            &[JValue::Object(&owner), JValue::Object(&activity)],
        )?;
        let intent = env.new_object(jni_str!("android/content/Intent"), jni_sig!("()V"), &[])?;
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
            &[JValue::Int(0x10000000 | 0x08000000)],
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
            &[JValue::Int(expected.display_id)],
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
    verify(expected)
}
pub(crate) fn on_display_event(generation: u64, kind: i32) {
    if kind != 3 {
        return;
    }
    let identity = registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .screen
        .as_ref()
        .filter(|s| s.identity.generation == generation && !s.closing)
        .map(|s| s.identity.clone());
    if let Some(identity) = identity {
        let _ = android::post_to_main(move || {
            if let Err(message) = close(&identity) {
                error(message);
            }
        });
    }
}

pub(crate) fn on_host_event(instance: Object, kind: i32) -> Result<(), String> {
    if kind == 3 {
        return super::preview::stop_host(&instance);
    }
    if kind != 1 {
        return Ok(());
    }
    let identity = status().identity.ok_or("虚拟屏宿主页没有所属屏幕")?;
    android::invoke(|env| {
        let display = if android::paths().is_some_and(|p| p.sdk_int >= 30) {
            env.call_method(
                instance.as_ref(),
                jni_str!("getDisplay"),
                jni_sig!("()Landroid/view/Display;"),
                &[],
            )?
            .l()?
        } else {
            let manager = env
                .call_method(
                    instance.as_ref(),
                    jni_str!("getWindowManager"),
                    jni_sig!("()Landroid/view/WindowManager;"),
                    &[],
                )?
                .l()?;
            env.call_method(
                manager,
                jni_str!("getDefaultDisplay"),
                jni_sig!("()Landroid/view/Display;"),
                &[],
            )?
            .l()?
        };
        let id = env
            .call_method(display, jni_str!("getDisplayId"), jni_sig!("()I"), &[])?
            .i()?;
        if id != identity.display_id {
            return Err(jni::errors::Error::NullPtr(
                "virtual host is on unexpected display",
            ));
        }
        let view = env.new_object(
            jni_str!("android/widget/TextView"),
            jni_sig!("(Landroid/content/Context;)V"),
            &[JValue::Object(instance.as_ref())],
        )?;
        env.call_method(
            &view,
            jni_str!("setTextColor"),
            jni_sig!("(I)V"),
            &[JValue::Int(0xff8fd6b6u32 as i32)],
        )?;
        env.call_method(
            &view,
            jni_str!("setBackgroundColor"),
            jni_sig!("(I)V"),
            &[JValue::Int(0xff101010u32 as i32)],
        )?;
        env.call_method(
            &view,
            jni_str!("setTextSize"),
            jni_sig!("(F)V"),
            &[JValue::Float(24.)],
        )?;
        env.call_method(
            &view,
            jni_str!("setGravity"),
            jni_sig!("(I)V"),
            &[JValue::Int(17)],
        )?;
        let text = env.new_string(format!(
            "JAgent 虚拟屏\ndisplayId={}\n画面已连接",
            identity.display_id
        ))?;
        env.call_method(
            &view,
            jni_str!("setText"),
            jni_sig!("(Ljava/lang/CharSequence;)V"),
            &[JValue::Object(&text)],
        )?;
        env.call_method(
            instance.as_ref(),
            jni_str!("setContentView"),
            jni_sig!("(Landroid/view/View;)V"),
            &[JValue::Object(&view)],
        )?;
        let view = Arc::new(env.new_global_ref(view)?);
        super::preview::start_host_clock(identity.clone(), Arc::downgrade(&instance), view)
            .map_err(|_| jni::errors::Error::NullPtr("virtual clock unavailable"))?;
        Ok(())
    })
}
