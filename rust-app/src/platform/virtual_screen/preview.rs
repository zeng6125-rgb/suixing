use super::{
    display::{self, Object},
    frames, *,
};
use crate::platform::android;
use jni::{Env, JValue, jni_sig, jni_str, objects::JObject};
use std::{
    collections::VecDeque,
    sync::{
        Mutex, OnceLock, Weak,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};

struct Bitmap(Object);
impl Drop for Bitmap {
    fn drop(&mut self) {
        let _ = frames::recycle(&self.0);
    }
}
struct Window {
    identity: Identity,
    view: Object,
    manager: Object,
    params: Object,
    paint: Object,
    bitmap: Option<Arc<Bitmap>>,
    drawn: bool,
    down: Option<(f32, f32, Instant)>,
    source_rect: Object,
    target_rect: Object,
    width: f32,
    height: f32,
    main_width: f32,
    main_height: f32,
    position: (i32, i32),
    drag: Option<(f32, f32, i32, i32)>,
    move_pending: bool,
}
#[derive(Default)]
struct Registry {
    window: Option<Window>,
    incoming: Option<(Identity, Arc<Bitmap>)>,
    publication_pending: bool,
    inputs: VecDeque<ManualInput>,
    error: Option<String>,
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
}
pub(super) fn status() -> (bool, bool, Option<String>) {
    let state = registry().lock().unwrap_or_else(|e| e.into_inner());
    (
        state.window.is_some(),
        state.window.as_ref().is_some_and(|w| w.drawn),
        state.error.clone(),
    )
}
pub fn drain_inputs() -> Vec<ManualInput> {
    registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .inputs
        .drain(..)
        .collect()
}
pub(super) fn show(identity: &Identity) -> Result<(), String> {
    display::verify(identity)?;
    {
        let state = registry().lock().unwrap_or_else(|e| e.into_inner());
        if let Some(window) = state.window.as_ref() {
            return if window.identity == *identity {
                Ok(())
            } else {
                Err("旧副屏预览仍占有窗口，需先关闭".into())
            };
        }
    }
    let (context, accessibility) = match crate::platform::automation::accessibility::service(None) {
        Ok(context) => (context, true),
        Err(_) => (android::application_context()?, false),
    };
    let window = android::invoke(|env| {
        if !accessibility
            && !env
                .call_static_method(
                    jni_str!("android/provider/Settings"),
                    jni_str!("canDrawOverlays"),
                    jni_sig!("(Landroid/content/Context;)Z"),
                    &[JValue::Object(context.as_ref())],
                )?
                .z()?
        {
            return Err(jni::errors::Error::NullPtr(
                "virtual preview overlay permission missing",
            ));
        }
        let service = env.new_string("window")?;
        let manager = env
            .call_method(
                context.as_ref(),
                jni_str!("getSystemService"),
                jni_sig!("(Ljava/lang/String;)Ljava/lang/Object;"),
                &[JValue::Object(&service)],
            )?
            .l()?;
        let class = android::generated_class("NativeVirtualPreview")
            .map_err(|_| jni::errors::Error::NullPtr("virtual preview class"))?;
        let view = env.new_object(
            class.as_ref(),
            jni_sig!("(Landroid/content/Context;J)V"),
            &[
                JValue::Object(context.as_ref()),
                JValue::Long(identity.generation as i64),
            ],
        )?;
        env.call_method(
            &view,
            jni_str!("setWillNotDraw"),
            jni_sig!("(Z)V"),
            &[JValue::Bool(false)],
        )?;
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
        let density = env
            .get_field(&metrics, jni_str!("density"), jni_sig!("F"))?
            .f()?;
        let main_width = env
            .get_field(&metrics, jni_str!("widthPixels"), jni_sig!("I"))?
            .i()?;
        let main_height = env
            .get_field(&metrics, jni_str!("heightPixels"), jni_sig!("I"))?
            .i()?;
        if !density.is_finite() || density <= 0. || main_width <= 0 || main_height <= 0 {
            return Err(jni::errors::Error::NullPtr(
                "virtual preview window metrics",
            ));
        }
        let width = (240. * density)
            .round()
            .min(main_width as f32 * 0.85)
            .max(1.) as i32;
        let height = (width as f32 * identity.height as f32 / identity.width as f32 + 48. * density)
            .round()
            .min(main_height as f32 * 0.75)
            .max(1.) as i32;
        let params = env.new_object(
            jni_str!("android/view/WindowManager$LayoutParams"),
            jni_sig!("(IIIII)V"),
            &[
                JValue::Int(width),
                JValue::Int(height),
                JValue::Int(if accessibility { 2032 } else { 2038 }),
                JValue::Int(0x08 | 0x20),
                JValue::Int(-3),
            ],
        )?;
        env.set_field(
            &params,
            jni_str!("gravity"),
            jni_sig!("I"),
            JValue::Int(0x33),
        )?;
        let initial_x = (main_width - width).max(0);
        let initial_y = ((48. * density) as i32).min((main_height - height).max(0));
        env.set_field(
            &params,
            jni_str!("x"),
            jni_sig!("I"),
            JValue::Int(initial_x),
        )?;
        env.set_field(
            &params,
            jni_str!("y"),
            jni_sig!("I"),
            JValue::Int(initial_y),
        )?;
        let paint = env.new_object(
            jni_str!("android/graphics/Paint"),
            jni_sig!("(I)V"),
            &[JValue::Int(3)],
        )?;
        let source_rect =
            env.new_object(jni_str!("android/graphics/Rect"), jni_sig!("()V"), &[])?;
        let target_rect =
            env.new_object(jni_str!("android/graphics/RectF"), jni_sig!("()V"), &[])?;
        let view = Arc::new(env.new_global_ref(view)?);
        let manager = Arc::new(env.new_global_ref(manager)?);
        let paint = Arc::new(env.new_global_ref(paint)?);
        let params = Arc::new(env.new_global_ref(params)?);
        let source_rect = Arc::new(env.new_global_ref(source_rect)?);
        let target_rect = Arc::new(env.new_global_ref(target_rect)?);
        env.call_method(
            manager.as_ref(),
            jni_str!("addView"),
            jni_sig!("(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V"),
            &[
                JValue::Object(view.as_ref()),
                JValue::Object(params.as_ref()),
            ],
        )?;
        Ok(Window {
            identity: identity.clone(),
            view,
            manager,
            params,
            paint,
            bitmap: None,
            drawn: false,
            down: None,
            source_rect,
            target_rect,
            width: width as f32,
            height: height as f32,
            main_width: main_width as f32,
            main_height: main_height as f32,
            position: (initial_x, initial_y),
            drag: None,
            move_pending: false,
        })
    });
    match window {
        Ok(window) => {
            let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
            state.window = Some(window);
            state.error = None;
            drop(state);
            // A static display may not draw a new frame when the preview opens.
            if let Ok(frame) = frames::latest_frame(identity, true) {
                let identity = identity.clone();
                std::thread::Builder::new()
                    .name("rust-virtual-preview-open".into())
                    .spawn(move || {
                        if let Err(message) = publish(&identity, frame.pixels) {
                            display::error(message);
                        }
                    })
                    .map_err(|_| "副屏预览取帧工作线程无法创建")?;
            }
            Ok(())
        }
        Err(message) => {
            registry().lock().unwrap_or_else(|e| e.into_inner()).error =
                Some("虚拟屏继续运行，但预览窗口未显示；请启用无障碍或悬浮窗权限".into());
            Err(message)
        }
    }
}
pub(super) fn hide() -> Result<(), String> {
    let (view, manager, generation) = {
        let state = registry().lock().unwrap_or_else(|e| e.into_inner());
        let Some(window) = state.window.as_ref() else {
            return Ok(());
        };
        (
            window.view.clone(),
            window.manager.clone(),
            window.identity.generation,
        )
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
    let detached = removed.is_ok()
        || android::invoke(|env| {
            env.call_method(
                view.as_ref(),
                jni_str!("isAttachedToWindow"),
                jni_sig!("()Z"),
                &[],
            )?
            .z()
        })
        .is_ok_and(|v| !v);
    if !detached {
        return Err("副屏预览窗口移除未确认，保留窗口和位图引用".into());
    }
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    if state
        .window
        .as_ref()
        .is_some_and(|w| w.identity.generation == generation)
    {
        state.window = None;
        state.incoming = None;
    }
    Ok(())
}
pub(super) fn publish(identity: &Identity, pixels: Arc<PixelFrame>) -> Result<(), String> {
    display::verify(identity)?;
    let eligible = {
        let state = display::registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        state
            .screen
            .as_ref()
            .is_some_and(|s| !s.closing && s.identity == *identity && s.preview_requested)
    };
    if !eligible {
        return Ok(());
    }
    let bitmap = Arc::new(Bitmap(frames::bitmap(&pixels)?));
    let post = {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        if !state
            .window
            .as_ref()
            .is_some_and(|w| w.identity == *identity)
        {
            return Ok(());
        }
        state.incoming = Some((identity.clone(), bitmap));
        if state.publication_pending {
            false
        } else {
            state.publication_pending = true;
            true
        }
    };
    if !post {
        return Ok(());
    }
    let posted = android::post_to_main(|| {
        let view = {
            let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
            state.publication_pending = false;
            let incoming = state.incoming.take();
            match (state.window.as_mut(), incoming) {
                (Some(window), Some((identity, bitmap))) if window.identity == identity => {
                    window.bitmap = Some(bitmap);
                    Some(window.view.clone())
                }
                _ => None,
            }
        };
        if let Some(view) = view {
            if android::invoke(|env| {
                env.call_method(view.as_ref(), jni_str!("invalidate"), jni_sig!("()V"), &[])?;
                Ok(())
            })
            .is_err()
            {
                display::error("副屏预览绘制投递失败".into());
            }
        }
    });
    if posted.is_err() {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        state.publication_pending = false;
        state.incoming = None;
    }
    posted
}
fn bounds(identity: &Identity, width: f32, height: f32) -> (f32, f32, f32, f32) {
    let bar = (height * 0.12).clamp(20., 80.).min(height);
    let scale = (width / identity.width as f32)
        .min((height - bar) / identity.height as f32)
        .max(0.);
    let rw = identity.width as f32 * scale;
    let rh = identity.height as f32 * scale;
    ((width - rw) / 2., bar + (height - bar - rh) / 2., rw, rh)
}
pub(crate) fn on_draw(env: &mut Env, generation: u64, canvas: &JObject) -> jni::errors::Result<()> {
    let width = env
        .call_method(canvas, jni_str!("getWidth"), jni_sig!("()I"), &[])?
        .i()? as f32;
    let height = env
        .call_method(canvas, jni_str!("getHeight"), jni_sig!("()I"), &[])?
        .i()? as f32;
    let (identity, bitmap, paint, src, dst) = {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        let Some(window) = state
            .window
            .as_mut()
            .filter(|w| w.identity.generation == generation)
        else {
            return Ok(());
        };
        window.width = width;
        window.height = height;
        window.drawn = true;
        (
            window.identity.clone(),
            window.bitmap.clone(),
            window.paint.clone(),
            window.source_rect.clone(),
            window.target_rect.clone(),
        )
    };
    env.call_method(
        canvas,
        jni_str!("drawColor"),
        jni_sig!("(I)V"),
        &[JValue::Int(0xff101010u32 as i32)],
    )?;
    let (left, top, rw, rh) = bounds(&identity, width, height);
    if let Some(bitmap) = bitmap {
        let bw = env
            .call_method(
                bitmap.0.as_ref(),
                jni_str!("getWidth"),
                jni_sig!("()I"),
                &[],
            )?
            .i()?;
        let bh = env
            .call_method(
                bitmap.0.as_ref(),
                jni_str!("getHeight"),
                jni_sig!("()I"),
                &[],
            )?
            .i()?;
        env.call_method(
            src.as_ref(),
            jni_str!("set"),
            jni_sig!("(IIII)V"),
            &[
                JValue::Int(0),
                JValue::Int(0),
                JValue::Int(bw),
                JValue::Int(bh),
            ],
        )?;
        env.call_method(
            dst.as_ref(),
            jni_str!("set"),
            jni_sig!("(FFFF)V"),
            &[
                JValue::Float(left),
                JValue::Float(top),
                JValue::Float(left + rw),
                JValue::Float(top + rh),
            ],
        )?;
        env.call_method(canvas, jni_str!("drawBitmap"), jni_sig!("(Landroid/graphics/Bitmap;Landroid/graphics/Rect;Landroid/graphics/RectF;Landroid/graphics/Paint;)V"),
            &[JValue::Object(bitmap.0.as_ref()), JValue::Object(src.as_ref()), JValue::Object(dst.as_ref()), JValue::Object(paint.as_ref())])?;
    }
    env.call_method(
        paint.as_ref(),
        jni_str!("setColor"),
        jni_sig!("(I)V"),
        &[JValue::Int(0xff8fd6b6u32 as i32)],
    )?;
    let bar = (height * 0.12).clamp(20., 80.).min(height);
    env.call_method(
        paint.as_ref(),
        jni_str!("setTextSize"),
        jni_sig!("(F)V"),
        &[JValue::Float((bar * 0.38).max(8.))],
    )?;
    let text = env.new_string(format!("副屏 {}   隐藏     关闭", identity.display_id))?;
    env.call_method(
        canvas,
        jni_str!("drawText"),
        jni_sig!("(Ljava/lang/String;FFLandroid/graphics/Paint;)V"),
        &[
            JValue::Object(&text),
            JValue::Float(8.),
            JValue::Float(bar * 0.65),
            JValue::Object(paint.as_ref()),
        ],
    )?;
    Ok(())
}
fn move_drag(generation: u64, raw_x: f32, raw_y: f32, ended: bool) -> bool {
    let post = {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        let Some(window) = state
            .window
            .as_mut()
            .filter(|w| w.identity.generation == generation)
        else {
            return false;
        };
        let Some((down_x, down_y, start_x, start_y)) = window.drag else {
            return false;
        };
        let x = (start_x as f32 + raw_x - down_x)
            .round()
            .clamp(0., (window.main_width - window.width).max(0.)) as i32;
        let y = (start_y as f32 + raw_y - down_y)
            .round()
            .clamp(0., (window.main_height - window.height).max(0.)) as i32;
        window.position = (x, y);
        if ended {
            window.drag = None;
            window.down = None;
        }
        if window.move_pending {
            false
        } else {
            window.move_pending = true;
            true
        }
    };
    if !post {
        return true;
    }
    let posted = android::post_to_main(move || {
        let data = {
            let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
            state
                .window
                .as_mut()
                .filter(|w| w.identity.generation == generation)
                .map(|w| {
                    w.move_pending = false;
                    (
                        w.view.clone(),
                        w.manager.clone(),
                        w.params.clone(),
                        w.position,
                    )
                })
        };
        if let Some((view, manager, params, (x, y))) = data {
            if android::invoke(|env| {
                env.set_field(
                    params.as_ref(),
                    jni_str!("x"),
                    jni_sig!("I"),
                    JValue::Int(x),
                )?;
                env.set_field(
                    params.as_ref(),
                    jni_str!("y"),
                    jni_sig!("I"),
                    JValue::Int(y),
                )?;
                env.call_method(
                    manager.as_ref(),
                    jni_str!("updateViewLayout"),
                    jni_sig!("(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V"),
                    &[
                        JValue::Object(view.as_ref()),
                        JValue::Object(params.as_ref()),
                    ],
                )?;
                Ok(())
            })
            .is_err()
            {
                display::error("副屏预览位置更新未确认".into());
            }
        }
    });
    if posted.is_err() {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        if let Some(w) = state
            .window
            .as_mut()
            .filter(|w| w.identity.generation == generation)
        {
            w.move_pending = false;
        }
        state.error = Some("副屏拖动位置未投递".into());
    }
    true
}
pub(crate) fn on_touch(
    env: &mut Env,
    generation: u64,
    event: &JObject,
) -> jni::errors::Result<bool> {
    let action = env
        .call_method(event, jni_str!("getActionMasked"), jni_sig!("()I"), &[])?
        .i()?;
    let pointers = env
        .call_method(event, jni_str!("getPointerCount"), jni_sig!("()I"), &[])?
        .i()?;
    let x = env
        .call_method(event, jni_str!("getX"), jni_sig!("()F"), &[])?
        .f()?;
    let y = env
        .call_method(event, jni_str!("getY"), jni_sig!("()F"), &[])?
        .f()?;
    let raw_x = env
        .call_method(event, jni_str!("getRawX"), jni_sig!("()F"), &[])?
        .f()?;
    let raw_y = env
        .call_method(event, jni_str!("getRawY"), jni_sig!("()F"), &[])?
        .f()?;
    if !x.is_finite() || !y.is_finite() || !raw_x.is_finite() || !raw_y.is_finite() {
        return Ok(false);
    }
    if pointers == 1 && matches!(action, 1 | 2) && move_drag(generation, raw_x, raw_y, action == 1)
    {
        return Ok(true);
    }
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    let input = {
        let Some(window) = state
            .window
            .as_mut()
            .filter(|w| w.identity.generation == generation)
        else {
            return Ok(false);
        };
        if action == 3 || pointers != 1 {
            window.down = None;
            window.drag = None;
            return Ok(true);
        }
        if action == 0 {
            window.down = Some((x, y, Instant::now()));
            let bar = (window.height * 0.12).clamp(20., 80.).min(window.height);
            if y < bar && x < window.width * 0.45 {
                window.drag = Some((raw_x, raw_y, window.position.0, window.position.1));
            }
            return Ok(true);
        }
        if action != 1 {
            return Ok(true);
        }
        let Some((x1, y1, started)) = window.down.take() else {
            return Ok(true);
        };
        let bar = (window.height * 0.12).clamp(20., 80.).min(window.height);
        if y1 < bar && y < bar {
            if x1 >= window.width * 0.75 && x >= window.width * 0.75 {
                Some(ManualInput::Close {
                    identity: window.identity.clone(),
                })
            } else if x1 >= window.width * 0.45 && x >= window.width * 0.45 {
                Some(ManualInput::Hide {
                    identity: window.identity.clone(),
                })
            } else {
                None
            }
        } else {
            let (left, top, rw, rh) = bounds(&window.identity, window.width, window.height);
            if rw <= 0.
                || rh <= 0.
                || x1 < left
                || x1 >= left + rw
                || y1 < top
                || y1 >= top + rh
                || x < left
                || x >= left + rw
                || y < top
                || y >= top + rh
            {
                return Ok(true);
            }
            let sx = |v: f32| (v - left) * window.identity.width as f32 / rw;
            let sy = |v: f32| (v - top) * window.identity.height as f32 / rh;
            let duration_ms = started.elapsed().as_millis().clamp(40, 5000) as u64;
            if (x - x1).hypot(y - y1) < 12. {
                Some(ManualInput::Tap {
                    identity: window.identity.clone(),
                    x: sx(x),
                    y: sy(y),
                    duration_ms,
                })
            } else {
                Some(ManualInput::Swipe {
                    identity: window.identity.clone(),
                    x1: sx(x1),
                    y1: sy(y1),
                    x2: sx(x),
                    y2: sy(y),
                    duration_ms,
                })
            }
        }
    };
    if let Some(input) = input {
        if state.inputs.len() >= 32 {
            state.error = Some("副屏手动输入队列达到32个预算，该输入未派发".into());
        } else {
            state.inputs.push_back(input);
        }
    }
    Ok(true)
}

struct Host {
    identity: Identity,
    activity: Object,
    view: Object,
    started: Instant,
    pending: bool,
}
static HOST: OnceLock<Mutex<Option<Host>>> = OnceLock::new();
static CLOCK_STARTED: AtomicBool = AtomicBool::new(false);
fn host() -> &'static Mutex<Option<Host>> {
    HOST.get_or_init(|| Mutex::new(None))
}
pub(super) fn stop_host(activity: &Object) -> Result<(), String> {
    let current = host()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .as_ref()
        .map(|h| h.activity.clone());
    if let Some(current) = current {
        if android::invoke(|env| env.is_same_object(current.as_ref(), activity.as_ref()))? {
            *host().lock().unwrap_or_else(|e| e.into_inner()) = None;
        }
    }
    Ok(())
}
pub(super) fn start_host_clock(
    identity: Identity,
    activity: Weak<jni::refs::Global<JObject<'static>>>,
    view: Object,
) -> Result<(), String> {
    let activity = activity.upgrade().ok_or("副屏宿主页已结束")?;
    *host().lock().unwrap_or_else(|e| e.into_inner()) = Some(Host {
        identity,
        activity,
        view,
        started: Instant::now(),
        pending: false,
    });
    if CLOCK_STARTED
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        return Ok(());
    }
    let started = std::thread::Builder::new()
        .name("rust-virtual-clock".into())
        .spawn(|| {
            loop {
                std::thread::sleep(Duration::from_secs(1));
                let identity = {
                    let mut slot = host().lock().unwrap_or_else(|e| e.into_inner());
                    if let Some(h) = slot.as_mut().filter(|h| !h.pending) {
                        h.pending = true;
                        Some(h.identity.clone())
                    } else {
                        None
                    }
                };
                let Some(identity) = identity else {
                    continue;
                };
                let expected = identity.clone();
                let posted = android::post_to_main(move || {
                    if display::verify(&expected).is_err() {
                        let mut slot = host().lock().unwrap_or_else(|e| e.into_inner());
                        if slot.as_ref().is_some_and(|h| h.identity == expected) {
                            *slot = None;
                        }
                        return;
                    }
                    let data = {
                        let mut slot = host().lock().unwrap_or_else(|e| e.into_inner());
                        slot.as_mut().filter(|h| h.identity == expected).map(|h| {
                            h.pending = false;
                            (h.view.clone(), h.started.elapsed().as_secs())
                        })
                    };
                    if let Some((view, seconds)) = data {
                        let _ = android::invoke(|env| {
                            let text = env.new_string(format!(
                                "JAgent 虚拟屏\n已运行 {seconds} 秒\ndisplayId={}",
                                expected.display_id
                            ))?;
                            env.call_method(
                                view.as_ref(),
                                jni_str!("setText"),
                                jni_sig!("(Ljava/lang/CharSequence;)V"),
                                &[JValue::Object(&text)],
                            )?;
                            Ok(())
                        });
                    }
                });
                if posted.is_err() {
                    if let Some(h) = host()
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .as_mut()
                        .filter(|h| h.identity == identity)
                    {
                        h.pending = false;
                    }
                }
            }
        });
    if started.is_err() {
        CLOCK_STARTED.store(false, Ordering::Release);
        return Err("副屏时钟工作线程无法创建".into());
    }
    Ok(())
}
