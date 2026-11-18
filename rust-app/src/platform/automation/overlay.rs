//! Visible UI-driving indicator. Windows are created/removed on main; a single
//! sleeping timer keeps at most one invalidation queued. Removal failure keeps
//! the original host/view owned until framework detachment is confirmed.
use super::accessibility::{self, Object};
use super::*;
use crate::platform::android;
use jni::{Env, JValue, jni_sig, jni_str, objects::JObject};
use std::{
    collections::BTreeMap,
    sync::{
        Condvar, Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};

#[derive(Clone, Debug, Default)]
pub struct Status {
    pub window_owned: bool,
    pub first_draw_received: bool,
    pub driving_runs: usize,
    pub accessibility_host: bool,
    pub error: Option<String>,
}
#[derive(Clone, Copy, Debug)]
pub struct Policy {
    pub enabled: bool,
    pub show_in_foreground: bool,
}
impl Default for Policy {
    fn default() -> Self {
        Self {
            enabled: true,
            show_in_foreground: true,
        }
    }
}
struct Window {
    handle: u64,
    view: Object,
    manager: Object,
    paint: Object,
    accessibility_host: bool,
    invalidation_pending: bool,
    drawn: bool,
}
#[derive(Clone)]
struct Flash {
    target: Target,
    points: Vec<(f32, f32)>,
    started: Instant,
    duration: Duration,
}
struct Registry {
    window: Option<Window>,
    drivers: BTreeMap<String, OperationKey>,
    flash: Option<Flash>,
    epoch: Instant,
    color: u32,
    error: Option<String>,
    show_pending: bool,
    removal_pending: bool,
    policy: Policy,
}
impl Default for Registry {
    fn default() -> Self {
        Self {
            window: None,
            drivers: BTreeMap::new(),
            flash: None,
            epoch: Instant::now(),
            color: 0xff8fd6b6,
            error: None,
            show_pending: false,
            removal_pending: false,
            policy: Policy::default(),
        }
    }
}
static REGISTRY: OnceLock<(Mutex<Registry>, Condvar)> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
static START: Mutex<bool> = Mutex::new(false);
fn registry() -> &'static (Mutex<Registry>, Condvar) {
    REGISTRY.get_or_init(|| (Mutex::new(Registry::default()), Condvar::new()))
}
pub fn status() -> Status {
    let (lock, _) = registry();
    let state = lock.lock().unwrap_or_else(|e| e.into_inner());
    Status {
        window_owned: state.window.is_some(),
        first_draw_received: state.window.as_ref().is_some_and(|w| w.drawn),
        driving_runs: state.drivers.len(),
        accessibility_host: state.window.as_ref().is_some_and(|w| w.accessibility_host),
        error: state.error.clone(),
    }
}
fn error(message: &str) {
    registry().0.lock().unwrap_or_else(|e| e.into_inner()).error = Some(message.into());
}
pub fn set_color(color: u32) {
    registry().0.lock().unwrap_or_else(|e| e.into_inner()).color = color;
    registry().1.notify_one();
}
/// The controller applies this only after saving its user preference. Drawing
/// state follows the confirmed policy, including foreground eligibility.
pub fn configure(policy: Policy) -> Result<(), String> {
    {
        let mut state = registry().0.lock().unwrap_or_else(|e| e.into_inner());
        state.policy = policy;
        if !policy.enabled {
            state.flash = None;
        }
        registry().1.notify_one();
    }
    if !policy.enabled {
        hide()
    } else {
        start_timer()?;
        post_show()
    }
}
pub fn set_driving(operation: OperationKey, active: bool) -> Result<(), String> {
    operation.validate()?;
    let (lock, wake) = registry();
    {
        let mut state = lock.lock().unwrap_or_else(|e| e.into_inner());
        if active {
            if state.drivers.len() >= 32 && !state.drivers.contains_key(&operation.id) {
                return Err("界面操作指示队列超过32个预算".into());
            }
            state.drivers.insert(operation.id.clone(), operation);
        } else {
            state.drivers.remove(&operation.id);
        }
        wake.notify_one();
    }
    if active {
        start_timer()?;
        post_show()?;
    }
    Ok(())
}
pub(in crate::platform) fn flash(
    operation: OperationKey,
    target: Target,
    points: Vec<(f32, f32)>,
    duration_ms: u64,
) {
    if target.display_id != 0 {
        return;
    }
    if operation.validate().is_err() {
        return;
    }
    let (lock, wake) = registry();
    {
        let mut state = lock.lock().unwrap_or_else(|e| e.into_inner());
        state.flash = Some(Flash {
            target,
            points,
            started: Instant::now(),
            duration: Duration::from_millis(duration_ms.saturating_add(800).min(5800)),
        });
        wake.notify_one();
    }
    if let Err(message) = start_timer().and_then(|_| post_show()) {
        error(&message);
    }
}
fn post_show() -> Result<(), String> {
    {
        let mut state = registry().0.lock().unwrap_or_else(|e| e.into_inner());
        if state.show_pending || state.window.is_some() {
            return Ok(());
        }
        state.show_pending = true;
    }
    let posted = android::post_to_main(|| {
        let result = show();
        let mut state = registry().0.lock().unwrap_or_else(|e| e.into_inner());
        state.show_pending = false;
        if let Err(message) = result {
            state.error = Some(message);
        }
        registry().1.notify_one();
    });
    if posted.is_err() {
        registry()
            .0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .show_pending = false;
    }
    posted
}
fn show() -> Result<(), String> {
    {
        let (lock, _) = registry();
        let state = lock.lock().unwrap_or_else(|e| e.into_inner());
        if state.window.is_some()
            || (state.drivers.is_empty() && state.flash.is_none())
            || !state.policy.enabled
            || (!state.policy.show_in_foreground && android::activity_visible())
        {
            return Ok(());
        }
    }
    let (context, accessibility_host) = match accessibility::service(None) {
        Ok(service) => (service, true),
        Err(_) => (android::application_context()?, false),
    };
    let handle = NEXT
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |v| {
            v.checked_add(1).filter(|next| *next <= i64::MAX as u64)
        })
        .map_err(|_| "悬浮指示窗口代号已耗尽")?;
    let window = android::invoke(|env| {
        if !accessibility_host
            && !env
                .call_static_method(
                    jni_str!("android/provider/Settings"),
                    jni_str!("canDrawOverlays"),
                    jni_sig!("(Landroid/content/Context;)Z"),
                    &[JValue::Object(context.as_ref())],
                )?
                .z()?
        {
            return Err(jni::errors::Error::NullPtr("overlay grant missing"));
        }
        let name = env.new_string("window")?;
        let manager = env
            .call_method(
                context.as_ref(),
                jni_str!("getSystemService"),
                jni_sig!("(Ljava/lang/String;)Ljava/lang/Object;"),
                &[JValue::Object(&name)],
            )?
            .l()?;
        let class = android::generated_class("NativeAutomationOverlay")
            .map_err(|_| jni::errors::Error::NullPtr("overlay view class"))?;
        let view = env.new_object(
            class.as_ref(),
            jni_sig!("(Landroid/content/Context;J)V"),
            &[
                JValue::Object(context.as_ref()),
                JValue::Long(handle as i64),
            ],
        )?;
        env.call_method(
            &view,
            jni_str!("setWillNotDraw"),
            jni_sig!("(Z)V"),
            &[JValue::Bool(false)],
        )?;
        let flags = 0x08 | 0x10 | 0x20 | 0x100 | 0x200 | 0x01000000;
        let params = env.new_object(
            jni_str!("android/view/WindowManager$LayoutParams"),
            jni_sig!("(IIIII)V"),
            &[
                JValue::Int(-1),
                JValue::Int(-1),
                JValue::Int(if accessibility_host { 2032 } else { 2038 }),
                JValue::Int(flags),
                JValue::Int(-3),
            ],
        )?;
        env.set_field(
            &params,
            jni_str!("gravity"),
            jni_sig!("I"),
            JValue::Int(0x33),
        )?;
        if android::paths().is_some_and(|p| p.sdk_int >= 28) {
            env.set_field(
                &params,
                jni_str!("layoutInDisplayCutoutMode"),
                jni_sig!("I"),
                JValue::Int(1),
            )?;
        }
        let paint = env.new_object(
            jni_str!("android/graphics/Paint"),
            jni_sig!("(I)V"),
            &[JValue::Int(1)],
        )?;
        // Globals exist before addView; an accepted window never loses ownership
        // because a later global allocation or panic failed.
        let view = Arc::new(env.new_global_ref(view)?);
        let manager = Arc::new(env.new_global_ref(manager)?);
        let paint = Arc::new(env.new_global_ref(paint)?);
        env.call_method(
            manager.as_ref(),
            jni_str!("addView"),
            jni_sig!("(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V"),
            &[JValue::Object(view.as_ref()), JValue::Object(&params)],
        )?;
        Ok(Window {
            handle,
            view,
            manager,
            paint,
            accessibility_host,
            invalidation_pending: false,
            drawn: false,
        })
    })?;
    let (lock, wake) = registry();
    let mut state = lock.lock().unwrap_or_else(|e| e.into_inner());
    state.window = Some(window);
    state.error = None;
    wake.notify_one();
    Ok(())
}
pub fn hide() -> Result<(), String> {
    {
        let mut state = registry().0.lock().unwrap_or_else(|e| e.into_inner());
        if state.removal_pending || (state.window.is_none() && !state.show_pending) {
            return Ok(());
        }
        state.removal_pending = true;
    }
    let posted = android::post_to_main(|| {
        let result = remove();
        let mut state = registry().0.lock().unwrap_or_else(|e| e.into_inner());
        state.removal_pending = false;
        if let Err(message) = result {
            state.error = Some(message);
        }
        registry().1.notify_one();
    });
    if posted.is_err() {
        registry()
            .0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .removal_pending = false;
    }
    posted
}
pub(super) fn disconnect() {
    let (lock, wake) = registry();
    {
        let mut state = lock.lock().unwrap_or_else(|e| e.into_inner());
        state.drivers.clear();
        state.flash = None;
        wake.notify_one();
    }
    if let Err(message) = hide() {
        error(&message);
    }
}
fn remove() -> Result<(), String> {
    let (manager, view, handle) = {
        let (lock, _) = registry();
        let state = lock.lock().unwrap_or_else(|e| e.into_inner());
        let Some(window) = state.window.as_ref() else {
            return Ok(());
        };
        (window.manager.clone(), window.view.clone(), window.handle)
    };
    let removed = android::invoke(|env| {
        env.call_method(
            manager.as_ref(),
            jni_str!("removeViewImmediate"),
            jni_sig!("(Landroid/view/View;)V"),
            &[JValue::Object(view.as_ref())],
        )?;
        Ok(())
    });
    let detached = if removed.is_ok() {
        true
    } else {
        android::invoke(|env| {
            env.call_method(
                view.as_ref(),
                jni_str!("isAttachedToWindow"),
                jni_sig!("()Z"),
                &[],
            )?
            .z()
        })
        .is_ok_and(|attached| !attached)
    };
    if !detached {
        return Err("悬浮指示窗口移除未确认，仍保留原窗口引用".into());
    }
    let (lock, _) = registry();
    let mut state = lock.lock().unwrap_or_else(|e| e.into_inner());
    if state.window.as_ref().is_some_and(|w| w.handle == handle) {
        state.window = None;
    }
    Ok(())
}
fn start_timer() -> Result<(), String> {
    let mut started = START.lock().unwrap_or_else(|e| e.into_inner());
    if *started {
        return Ok(());
    }
    std::thread::Builder::new()
        .name("rust-ui-indicator-timer".into())
        .spawn(|| {
            let (lock, wake) = registry();
            let mut state = lock.lock().unwrap_or_else(|e| e.into_inner());
            loop {
                if state
                    .flash
                    .as_ref()
                    .is_some_and(|flash| flash.started.elapsed() >= flash.duration)
                {
                    state.flash = None;
                }
                let active = (!state.drivers.is_empty() || state.flash.is_some())
                    && state.policy.enabled
                    && (state.policy.show_in_foreground || !android::activity_visible());
                if !active {
                    if state.window.is_some() {
                        drop(state);
                        let _ = hide();
                        state = lock.lock().unwrap_or_else(|e| e.into_inner());
                    }
                    state = wake
                        .wait_timeout(state, Duration::from_secs(1))
                        .unwrap_or_else(|e| e.into_inner())
                        .0;
                    continue;
                }
                if state.window.is_none() {
                    if !state.show_pending && state.error.is_none() {
                        drop(state);
                        if let Err(message) = post_show() {
                            error(&message);
                        }
                        state = lock.lock().unwrap_or_else(|e| e.into_inner());
                    }
                    state = wake
                        .wait_timeout(state, Duration::from_secs(1))
                        .unwrap_or_else(|e| e.into_inner())
                        .0;
                    continue;
                }
                let task = state.window.as_mut().and_then(|window| {
                    if window.invalidation_pending {
                        None
                    } else {
                        window.invalidation_pending = true;
                        Some((window.handle, window.view.clone()))
                    }
                });
                drop(state);
                if let Some((handle, view)) = task {
                    let posted = android::post_to_main(move || {
                        let valid = {
                            let (lock, _) = registry();
                            let mut state = lock.lock().unwrap_or_else(|e| e.into_inner());
                            if let Some(window) =
                                state.window.as_mut().filter(|w| w.handle == handle)
                            {
                                window.invalidation_pending = false;
                                true
                            } else {
                                false
                            }
                        };
                        if valid {
                            if android::invoke(|env| {
                                env.call_method(
                                    view.as_ref(),
                                    jni_str!("invalidate"),
                                    jni_sig!("()V"),
                                    &[],
                                )?;
                                Ok(())
                            })
                            .is_err()
                            {
                                error("悬浮指示绘制请求未确认");
                            }
                        }
                    });
                    if posted.is_err() {
                        let mut state = lock.lock().unwrap_or_else(|e| e.into_inner());
                        if let Some(window) = state.window.as_mut().filter(|w| w.handle == handle) {
                            window.invalidation_pending = false;
                        }
                        state.error = Some("悬浮指示主线程投递失败".into());
                    }
                }
                state = lock.lock().unwrap_or_else(|e| e.into_inner());
                state = wake
                    .wait_timeout(state, Duration::from_millis(16))
                    .unwrap_or_else(|e| e.into_inner())
                    .0;
            }
        })
        .map_err(|_| "界面指示动画线程无法启动")?;
    *started = true;
    Ok(())
}

pub(crate) fn draw(env: &mut Env, handle: u64, canvas: &JObject) -> jni::errors::Result<()> {
    let (paint, color, driving, epoch, flash) = {
        let (lock, _) = registry();
        let mut state = lock.lock().unwrap_or_else(|e| e.into_inner());
        let Some(window) = state.window.as_mut().filter(|w| w.handle == handle) else {
            return Ok(());
        };
        window.drawn = true;
        let paint = window.paint.clone();
        (
            paint,
            state.color,
            !state.drivers.is_empty()
                || state
                    .flash
                    .as_ref()
                    .is_some_and(|flash| flash.points.is_empty()),
            state.epoch,
            state.flash.clone(),
        )
    };
    let width = env
        .call_method(canvas, jni_str!("getWidth"), jni_sig!("()I"), &[])?
        .i()? as f32;
    let height = env
        .call_method(canvas, jni_str!("getHeight"), jni_sig!("()I"), &[])?
        .i()? as f32;
    let style = env
        .get_static_field(
            jni_str!("android/graphics/Paint$Style"),
            jni_str!("STROKE"),
            jni_sig!("Landroid/graphics/Paint$Style;"),
        )?
        .l()?;
    env.call_method(
        paint.as_ref(),
        jni_str!("setStyle"),
        jni_sig!("(Landroid/graphics/Paint$Style;)V"),
        &[JValue::Object(&style)],
    )?;
    env.call_method(
        paint.as_ref(),
        jni_str!("setColor"),
        jni_sig!("(I)V"),
        &[JValue::Int(color as i32)],
    )?;
    env.call_method(
        paint.as_ref(),
        jni_str!("setStrokeWidth"),
        jni_sig!("(F)V"),
        &[JValue::Float(5.)],
    )?;
    if driving {
        let alpha = ((epoch.elapsed().as_secs_f32() * 2.).sin() * 0.20 + 0.45) * 255.;
        env.call_method(
            paint.as_ref(),
            jni_str!("setAlpha"),
            jni_sig!("(I)V"),
            &[JValue::Int(alpha as i32)],
        )?;
        env.call_method(
            canvas,
            jni_str!("drawRect"),
            jni_sig!("(FFFFLandroid/graphics/Paint;)V"),
            &[
                JValue::Float(3.),
                JValue::Float(3.),
                JValue::Float((width - 3.).max(3.)),
                JValue::Float((height - 3.).max(3.)),
                JValue::Object(paint.as_ref()),
            ],
        )?;
    }
    if let Some(flash) = flash {
        if flash.target.display_id == 0 {
            let elapsed = flash.started.elapsed().as_secs_f32();
            let progress = (elapsed / flash.duration.as_secs_f32()).clamp(0., 1.);
            env.call_method(
                paint.as_ref(),
                jni_str!("setAlpha"),
                jni_sig!("(I)V"),
                &[JValue::Int(((1. - progress) * 255.) as i32)],
            )?;
            if let Some(&(x, y)) = flash.points.first() {
                env.call_method(
                    canvas,
                    jni_str!("drawCircle"),
                    jni_sig!("(FFFLandroid/graphics/Paint;)V"),
                    &[
                        JValue::Float(x),
                        JValue::Float(y),
                        JValue::Float(18. + progress * 34.),
                        JValue::Object(paint.as_ref()),
                    ],
                )?;
            }
            if flash.points.len() > 1 {
                let (x1, y1) = flash.points[0];
                let (x2, y2) = flash.points[1];
                env.call_method(
                    canvas,
                    jni_str!("drawLine"),
                    jni_sig!("(FFFFLandroid/graphics/Paint;)V"),
                    &[
                        JValue::Float(x1),
                        JValue::Float(y1),
                        JValue::Float(x2),
                        JValue::Float(y2),
                        JValue::Object(paint.as_ref()),
                    ],
                )?;
            }
        }
    }
    Ok(())
}
