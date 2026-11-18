//! Observe Android's existing NativeActivity layout and inset dispatch. All
//! framework reads run on Android's main thread, outside the rendering loop.
use super::android;
use eframe::egui;
use jni::{Env, JValue, jni_sig, jni_str, objects::JObject, refs::Global};
use std::sync::{
    Arc, Mutex, Weak,
    atomic::{AtomicBool, Ordering},
};
use winit::platform::android::activity::{
    AndroidApp, ndk::configuration::UiModeNight, ndk::native_window::NativeWindow,
};

static VIEW: Mutex<Weak<Slot>> = Mutex::new(Weak::new());

struct Slot {
    app: AndroidApp,
    activity: Global<JObject<'static>>,
    context: egui::Context,
    queued: AtomicBool,
    sample: Mutex<Option<Sample>>,
    bars: Mutex<SystemBars>,
    wallpaper: Mutex<WallpaperCache>,
    motion_scale: Mutex<f32>,
    motion_dirty: AtomicBool,
}

#[derive(Default)]
struct WallpaperCache {
    requested: bool,
    generation: u64,
    captured_generation: Option<u64>,
    // Exactly two schemes; resource reads never run in the rendering loop.
    colors: Option<[[egui::Color32; 12]; 2]>,
}

#[derive(Default)]
struct SystemBars {
    requested_dark: Option<bool>,
    applied_dark: Option<bool>,
}

#[derive(PartialEq, Eq)]
struct Sample {
    surface: NativeWindow,
    size: [i32; 2],
    insets: [i32; 4],
}

pub(crate) struct View {
    slot: Arc<Slot>,
    last_surface: Option<(usize, [i32; 2])>,
}

impl View {
    pub(crate) fn new(app: AndroidApp, context: egui::Context) -> Option<Self> {
        let activity = android::invoke(|env| {
            let raw = app.activity_as_ptr() as jni::sys::jobject;
            // The AndroidApp retained below owns the lifetime of this borrowed
            // global; keep an independent global reference for Java callbacks.
            let activity = unsafe { env.as_cast_raw::<Global<JObject>>(&raw)? };
            env.new_global_ref(activity.as_ref())
        })
        .ok()?;
        let slot = Arc::new(Slot {
            app,
            activity,
            context,
            queued: AtomicBool::new(false),
            sample: Mutex::new(None),
            bars: Mutex::new(SystemBars::default()),
            wallpaper: Mutex::new(WallpaperCache::default()),
            motion_scale: Mutex::new(1.0),
            motion_dirty: AtomicBool::new(true),
        });
        *VIEW.lock().unwrap_or_else(|error| error.into_inner()) = Arc::downgrade(&slot);
        schedule(&slot);
        Some(Self {
            slot,
            last_surface: None,
        })
    }

    pub(crate) fn system_dark(&self) -> Option<bool> {
        match self.slot.app.config().ui_mode_night() {
            UiModeNight::Yes => Some(true),
            UiModeNight::No => Some(false),
            _ => None,
        }
    }

    /// Wallpaper roles in this order: foreground, outline, panel, surface,
    /// extreme background, faint background, primary/link, code background,
    /// selection background, selection foreground, inactive fill, hovered fill.
    /// The selection uses the old theme's primary at 32% opacity. Android below
    /// 12, missing OEM resources and the first pending sample use the original
    /// WALLPAPER scheme's green Brand fallback, not the JAgent warm-paper scheme.
    pub(crate) fn wallpaper_colors(&self, dark: bool) -> [egui::Color32; 12] {
        let mut cache = self
            .slot
            .wallpaper
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        cache.requested = true;
        let pending = cache.captured_generation != Some(cache.generation);
        let colors = cache
            .colors
            .as_ref()
            .map(|schemes| schemes[usize::from(dark)])
            .unwrap_or_else(|| wallpaper_fallback(dark));
        drop(cache);
        if pending {
            schedule(&self.slot);
        }
        colors
    }

    pub(crate) fn motion_scale(&self) -> f32 {
        *self
            .slot
            .motion_scale
            .lock()
            .unwrap_or_else(|error| error.into_inner())
    }

    pub(crate) fn set_system_bars(&self, dark: bool) {
        let mut bars = self
            .slot
            .bars
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if bars.requested_dark != Some(dark) {
            bars.requested_dark = Some(dark);
            drop(bars);
            schedule(&self.slot);
        }
    }

    pub(crate) fn points(&mut self, context: &egui::Context) -> [f32; 4] {
        if context.viewport_id() != egui::ViewportId::ROOT {
            return [0.0; 4];
        }
        let Some(surface) = self.slot.app.native_window() else {
            self.last_surface = None;
            return [0.0; 4];
        };
        let size = [surface.width(), surface.height()];
        let stamp = (surface.ptr().as_ptr() as usize, size);
        // winit renders a new size/surface even when Android's layout callback
        // fired before the native surface was available. Retry once per change.
        if self.last_surface.replace(stamp) != Some(stamp) {
            schedule(&self.slot);
        }
        let scale = context.pixels_per_point();
        let viewport = context.viewport_rect().size() * scale;
        if !scale.is_finite()
            || scale <= 0.0
            || (viewport.x - size[0] as f32).abs() > 1.0
            || (viewport.y - size[1] as f32).abs() > 1.0
        {
            return [0.0; 4];
        }
        self.slot
            .sample
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .as_ref()
            .filter(|sample| sample.surface == surface && sample.size == size)
            .map_or([0.0; 4], |sample| {
                sample.insets.map(|pixels| pixels as f32 / scale)
            })
    }
}

fn current_slot() -> Option<Arc<Slot>> {
    VIEW.lock()
        .unwrap_or_else(|error| error.into_inner())
        .upgrade()
}

pub(super) fn lifecycle(env: &mut Env, activity: &JObject, kind: i32) -> jni::errors::Result<()> {
    if let Some(slot) = current_slot()
        && env.is_same_object(slot.activity.as_ref(), activity)?
    {
        if matches!(kind, 3..=5) {
            publish(&slot, None);
            slot.bars
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .applied_dark = None;
        } else if matches!(kind, 1 | 2 | 7 | 8) {
            if matches!(kind, 1 | 2 | 8) {
                slot.motion_dirty.store(true, Ordering::Release);
                let mut cache = slot
                    .wallpaper
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                cache.generation = cache.generation.wrapping_add(1);
                cache.captured_generation = None;
            }
            schedule(&slot);
            if kind == 8 {
                slot.context.request_repaint();
            }
        }
    }
    if kind == 2 {
        // This is the NativeActivity-owned content FrameLayout. The listener
        // forwards View.onApplyWindowInsets before notifying us.
        let id = env
            .get_static_field(jni_str!("android/R$id"), jni_str!("content"), jni_sig!("I"))?
            .i()?;
        let content = env
            .call_method(
                activity,
                jni_str!("findViewById"),
                jni_sig!("(I)Landroid/view/View;"),
                &[JValue::Int(id)],
            )?
            .l()?;
        if !content.is_null() {
            let listener = android::generated_class("NativeInsetsListener")
                .map_err(|_| jni::errors::Error::NullPtr("insets listener class"))?;
            let instance = env.new_object(
                listener.as_ref(),
                jni_sig!("(Landroid/app/Activity;)V"),
                &[JValue::Object(activity)],
            )?;
            env.call_method(
                &content,
                jni_str!("setOnApplyWindowInsetsListener"),
                jni_sig!("(Landroid/view/View$OnApplyWindowInsetsListener;)V"),
                &[JValue::Object(&instance)],
            )?;
            env.call_method(
                &content,
                jni_str!("requestApplyInsets"),
                jni_sig!("()V"),
                &[],
            )?;
        }
    }
    Ok(())
}

fn publish(slot: &Slot, sample: Option<Sample>) {
    let mut old = slot
        .sample
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    if *old != sample {
        *old = sample;
        drop(old);
        slot.context.request_repaint();
    }
}

fn schedule(slot: &Arc<Slot>) {
    if slot.queued.swap(true, Ordering::AcqRel) {
        return;
    }
    let weak = Arc::downgrade(slot);
    if android::post_to_main(move || {
        let Some(slot) = weak.upgrade() else { return };
        slot.queued.store(false, Ordering::Release);
        if !current_slot().is_some_and(|current| Arc::ptr_eq(&slot, &current)) {
            return;
        }
        let sample = android::invoke(|env| {
            let Some(activity) = android::current_activity() else {
                return Ok(None);
            };
            if !android::activity_visible()
                || !env.is_same_object(slot.activity.as_ref(), activity.as_ref())?
            {
                return Ok(None);
            }
            let pending_dark = {
                let bars = slot.bars.lock().unwrap_or_else(|error| error.into_inner());
                bars.requested_dark
                    .filter(|dark| Some(*dark) != bars.applied_dark)
            };
            if let Some(dark) = pending_dark {
                match apply_system_bars(env, activity.as_ref(), dark) {
                    Ok(true) => {
                        slot.bars
                            .lock()
                            .unwrap_or_else(|error| error.into_inner())
                            .applied_dark = Some(dark);
                    }
                    Err(_) if env.exception_check() => env.exception_clear(),
                    _ => {}
                }
            }
            refresh_wallpaper(env, &slot);
            if slot.motion_dirty.swap(false, Ordering::AcqRel) {
                match read_motion_scale(env, activity.as_ref()) {
                    Ok(scale) => {
                        *slot
                            .motion_scale
                            .lock()
                            .unwrap_or_else(|error| error.into_inner()) = scale;
                        slot.context.request_repaint();
                    }
                    Err(_) if env.exception_check() => env.exception_clear(),
                    _ => {}
                }
            }
            capture(env, &slot)
        })
        .ok()
        .flatten();
        publish(&slot, sample);
    })
    .is_err()
    {
        slot.queued.store(false, Ordering::Release);
    }
}

fn refresh_wallpaper(env: &mut Env, slot: &Arc<Slot>) {
    let generation = {
        let cache = slot
            .wallpaper
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if !cache.requested || cache.captured_generation == Some(cache.generation) {
            return;
        }
        cache.generation
    };
    let colors = match read_wallpaper(env, slot.activity.as_ref()) {
        Ok(colors) => colors,
        Err(_) => {
            if env.exception_check() {
                env.exception_clear();
            }
            None
        }
    };
    let mut cache = slot
        .wallpaper
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    if cache.generation != generation {
        // A newer configuration was observed during capture. Retain its dirty
        // generation so this sample cannot suppress the newer resource read.
        drop(cache);
        schedule(slot);
        return;
    }
    let changed = cache.colors != colors;
    cache.colors = colors;
    cache.captured_generation = Some(generation);
    drop(cache);
    if changed {
        slot.context.request_repaint();
    }
}

fn read_wallpaper(
    env: &mut Env,
    activity: &JObject,
) -> jni::errors::Result<Option<[[egui::Color32; 12]; 2]>> {
    let sdk = android::paths().map_or(0, |paths| paths.sdk_int);
    if sdk < 31 {
        return Ok(None);
    }
    if sdk >= 34 {
        match read_wallpaper_schemes(env, activity, true) {
            Ok(colors) => return Ok(Some(colors)),
            Err(_) if env.exception_check() => env.exception_clear(),
            _ => {}
        }
    }
    // Android 12 and 13 expose wallpaper's public accent/neutral tonal ramps.
    // Android 14 adds the scheme roles above, including surface containers.
    read_wallpaper_schemes(env, activity, false).map(Some)
}

fn read_motion_scale(env: &mut Env, activity: &JObject) -> jni::errors::Result<f32> {
    let resolver = env
        .call_method(
            activity,
            jni_str!("getContentResolver"),
            jni_sig!("()Landroid/content/ContentResolver;"),
            &[],
        )?
        .l()?;
    let name = env.new_string("animator_duration_scale")?;
    env.call_static_method(
        jni_str!("android/provider/Settings$Global"),
        jni_str!("getFloat"),
        jni_sig!("(Landroid/content/ContentResolver;Ljava/lang/String;F)F"),
        &[
            JValue::Object(&resolver),
            JValue::Object(name.as_ref()),
            JValue::Float(1.0),
        ],
    )?
    .f()
}

fn read_wallpaper_schemes(
    env: &mut Env,
    activity: &JObject,
    roles: bool,
) -> jni::errors::Result<[[egui::Color32; 12]; 2]> {
    let mut schemes = [[egui::Color32::TRANSPARENT; 12]; 2];
    for (dark, colors) in schemes.iter_mut().enumerate() {
        let names = match (roles, dark == 1) {
            (true, false) => [
                jni_str!("system_on_surface_light"),
                jni_str!("system_outline_light"),
                jni_str!("system_surface_light"),
                jni_str!("system_surface_container_low_light"),
                jni_str!("system_surface_container_lowest_light"),
                jni_str!("system_surface_container_light"),
                jni_str!("system_primary_light"),
                jni_str!("system_surface_container_high_light"),
                jni_str!("system_primary_light"),
                jni_str!("system_on_surface_light"),
                jni_str!("system_surface_container_high_light"),
                jni_str!("system_surface_container_highest_light"),
            ],
            (true, true) => [
                jni_str!("system_on_surface_dark"),
                jni_str!("system_outline_dark"),
                jni_str!("system_surface_dark"),
                jni_str!("system_surface_container_low_dark"),
                jni_str!("system_surface_container_lowest_dark"),
                jni_str!("system_surface_container_dark"),
                jni_str!("system_primary_dark"),
                jni_str!("system_surface_container_high_dark"),
                jni_str!("system_primary_dark"),
                jni_str!("system_on_surface_dark"),
                jni_str!("system_surface_container_high_dark"),
                jni_str!("system_surface_container_highest_dark"),
            ],
            (false, false) => [
                jni_str!("system_neutral1_900"),
                jni_str!("system_neutral2_500"),
                jni_str!("system_neutral1_10"),
                jni_str!("system_neutral1_50"),
                jni_str!("system_neutral1_0"),
                jni_str!("system_neutral1_100"),
                jni_str!("system_accent1_600"),
                jni_str!("system_neutral2_100"),
                jni_str!("system_accent1_600"),
                jni_str!("system_neutral1_900"),
                jni_str!("system_neutral2_100"),
                jni_str!("system_neutral2_200"),
            ],
            (false, true) => [
                jni_str!("system_neutral1_100"),
                jni_str!("system_neutral2_500"),
                jni_str!("system_neutral1_900"),
                jni_str!("system_neutral1_800"),
                jni_str!("system_neutral1_1000"),
                jni_str!("system_neutral1_800"),
                jni_str!("system_accent1_200"),
                jni_str!("system_neutral2_800"),
                jni_str!("system_accent1_200"),
                jni_str!("system_neutral1_100"),
                jni_str!("system_neutral2_800"),
                jni_str!("system_neutral2_700"),
            ],
        };
        for (index, name) in names.into_iter().enumerate() {
            let id = env
                .get_static_field(jni_str!("android/R$color"), name, jni_sig!("I"))?
                .i()?;
            let argb = env
                .call_method(
                    activity,
                    jni_str!("getColor"),
                    jni_sig!("(I)I"),
                    &[JValue::Int(id)],
                )?
                .i()? as u32;
            colors[index] = egui::Color32::from_rgba_unmultiplied(
                (argb >> 16) as u8,
                (argb >> 8) as u8,
                argb as u8,
                (argb >> 24) as u8,
            );
        }
        colors[8] = selection_fill(colors[6]);
    }
    Ok(schemes)
}

fn selection_fill(primary: egui::Color32) -> egui::Color32 {
    egui::Color32::from_rgba_unmultiplied(primary.r(), primary.g(), primary.b(), 82)
}

pub(crate) fn wallpaper_fallback(dark: bool) -> [egui::Color32; 12] {
    let rgb = if dark {
        [
            0xe8efea, 0x47514b, 0x0f1412, 0x171e1a, 0x0a0f0d, 0x121815, 0x8fd6b6, 0x1e2621,
            0x8fd6b6, 0xe8efea, 0x171e1a, 0x1e2621,
        ]
    } else {
        [
            0x161d19, 0x848a86, 0xffffff, 0xf1f5f2, 0xffffff, 0xf7faf8, 0x1f6b4c, 0xe9efeb,
            0x1f6b4c, 0x161d19, 0xf1f5f2, 0xe9efeb,
        ]
    };
    let mut colors =
        rgb.map(|rgb| egui::Color32::from_rgb((rgb >> 16) as u8, (rgb >> 8) as u8, rgb as u8));
    colors[8] = selection_fill(colors[6]);
    colors
}

fn apply_system_bars(env: &mut Env, activity: &JObject, dark: bool) -> jni::errors::Result<bool> {
    let Some(sdk) = android::paths().map(|paths| paths.sdk_int) else {
        return Ok(false);
    };
    let window = env
        .call_method(
            activity,
            jni_str!("getWindow"),
            jni_sig!("()Landroid/view/Window;"),
            &[],
        )?
        .l()?;
    if sdk >= 30 {
        env.call_method(
            &window,
            jni_str!("setDecorFitsSystemWindows"),
            jni_sig!("(Z)V"),
            &[JValue::Bool(false)],
        )?;
        let controller = env
            .call_method(
                &window,
                jni_str!("getInsetsController"),
                jni_sig!("()Landroid/view/WindowInsetsController;"),
                &[],
            )?
            .l()?;
        if controller.is_null() {
            return Ok(false);
        }
        const LIGHT_BARS: i32 = 8 | 16;
        env.call_method(
            &controller,
            jni_str!("setSystemBarsAppearance"),
            jni_sig!("(II)V"),
            &[
                JValue::Int(if dark { 0 } else { LIGHT_BARS }),
                JValue::Int(LIGHT_BARS),
            ],
        )?;
    } else {
        let decor = env
            .call_method(
                &window,
                jni_str!("getDecorView"),
                jni_sig!("()Landroid/view/View;"),
                &[],
            )?
            .l()?;
        let current = env
            .call_method(
                &decor,
                jni_str!("getSystemUiVisibility"),
                jni_sig!("()I"),
                &[],
            )?
            .i()?;
        const EDGE_TO_EDGE_LAYOUT: i32 = 256 | 512 | 1024;
        const LIGHT_BARS: i32 = 8192 | 16;
        let flags =
            (current | EDGE_TO_EDGE_LAYOUT) & !LIGHT_BARS | if dark { 0 } else { LIGHT_BARS };
        env.call_method(
            &decor,
            jni_str!("setSystemUiVisibility"),
            jni_sig!("(I)V"),
            &[JValue::Int(flags)],
        )?;
    }
    if sdk < 35 {
        for method in [
            jni_str!("setStatusBarColor"),
            jni_str!("setNavigationBarColor"),
        ] {
            env.call_method(&window, method, jni_sig!("(I)V"), &[JValue::Int(0)])?;
        }
    }
    if sdk >= 29 {
        for method in [
            jni_str!("setStatusBarContrastEnforced"),
            jni_str!("setNavigationBarContrastEnforced"),
        ] {
            env.call_method(&window, method, jni_sig!("(Z)V"), &[JValue::Bool(false)])?;
        }
    }
    Ok(true)
}

fn capture(env: &mut Env, slot: &Slot) -> jni::errors::Result<Option<Sample>> {
    let Some(surface) = slot.app.native_window() else {
        return Ok(None);
    };
    let size = [surface.width(), surface.height()];
    let window = env
        .call_method(
            slot.activity.as_ref(),
            jni_str!("getWindow"),
            jni_sig!("()Landroid/view/Window;"),
            &[],
        )?
        .l()?;
    let decor = env
        .call_method(
            &window,
            jni_str!("getDecorView"),
            jni_sig!("()Landroid/view/View;"),
            &[],
        )?
        .l()?;
    if !env
        .call_method(&decor, jni_str!("isAttachedToWindow"), jni_sig!("()Z"), &[])?
        .z()?
    {
        return Ok(None);
    }
    let root = [
        env.call_method(&decor, jni_str!("getWidth"), jni_sig!("()I"), &[])?
            .i()?,
        env.call_method(&decor, jni_str!("getHeight"), jni_sig!("()I"), &[])?
            .i()?,
    ];
    let sdk = env
        .get_static_field(
            jni_str!("android/os/Build$VERSION"),
            jni_str!("SDK_INT"),
            jni_sig!("I"),
        )?
        .i()?;
    let position = env.new_int_array(2)?;
    let mut origin = [0, 0];
    if sdk >= 29 {
        env.call_method(
            &decor,
            jni_str!("getLocationInSurface"),
            jni_sig!("([I)V"),
            &[JValue::Object(&position)],
        )?;
        env.get_int_array_region(&position, 0, &mut origin)?;
    } else if root != size {
        // Older APIs lack a public Surface-origin query. Only accept a decor
        // view that matches the native window buffer on both axes.
        return Ok(None);
    }
    if (0..2).any(|axis| {
        root[axis] <= 0 || origin[axis] < 0 || origin[axis].saturating_add(root[axis]) > size[axis]
    }) {
        return Ok(None);
    }
    let root_insets = env
        .call_method(
            &decor,
            jni_str!("getRootWindowInsets"),
            jni_sig!("()Landroid/view/WindowInsets;"),
            &[],
        )?
        .l()?;
    let mut safe = [0, 0, root[0], root[1]];
    let typed = if sdk >= 30 && !root_insets.is_null() {
        if sdk >= 35 {
            let frame = env
                .call_method(
                    &root_insets,
                    jni_str!("getFrame"),
                    jni_sig!("()Landroid/util/Size;"),
                    &[],
                )?
                .l()?;
            let measured = [
                env.call_method(&frame, jni_str!("getWidth"), jni_sig!("()I"), &[])?
                    .i()?,
                env.call_method(&frame, jni_str!("getHeight"), jni_sig!("()I"), &[])?
                    .i()?,
            ];
            // Reject insets whose assumed frame has not caught up with the
            // layout pass. The measured visible frame is the fallback.
            measured == root
        } else {
            true
        }
    } else {
        false
    };
    if typed {
        let mut types = 0;
        for method in [
            jni_str!("systemBars"),
            jni_str!("displayCutout"),
            jni_str!("ime"),
        ] {
            types |= env
                .call_static_method(
                    jni_str!("android/view/WindowInsets$Type"),
                    method,
                    jni_sig!("()I"),
                    &[],
                )?
                .i()?;
        }
        let value = env
            .call_method(
                &root_insets,
                jni_str!("getInsets"),
                jni_sig!("(I)Landroid/graphics/Insets;"),
                &[JValue::Int(types)],
            )?
            .l()?;
        // Insets are relative to the already-laid-out window frame. The type
        // union supplies a single obstruction per edge, including residual IME.
        safe[0] = env
            .get_field(&value, jni_str!("left"), jni_sig!("I"))?
            .i()?;
        safe[1] = env.get_field(&value, jni_str!("top"), jni_sig!("I"))?.i()?;
        safe[2] -= env
            .get_field(&value, jni_str!("right"), jni_sig!("I"))?
            .i()?;
        safe[3] -= env
            .get_field(&value, jni_str!("bottom"), jni_sig!("I"))?
            .i()?;
    } else {
        // Legacy getSystemWindowInsetBottom can include the IME. Project the
        // absolute visible rect into the laid-out window to avoid adding it twice.
        env.call_method(
            &decor,
            jni_str!("getLocationOnScreen"),
            jni_sig!("([I)V"),
            &[JValue::Object(&position)],
        )?;
        let mut screen = [0, 0];
        env.get_int_array_region(&position, 0, &mut screen)?;
        let visible = env.new_object(jni_str!("android/graphics/Rect"), jni_sig!("()V"), &[])?;
        env.call_method(
            &decor,
            jni_str!("getWindowVisibleDisplayFrame"),
            jni_sig!("(Landroid/graphics/Rect;)V"),
            &[JValue::Object(&visible)],
        )?;
        for (i, field) in [
            jni_str!("left"),
            jni_str!("top"),
            jni_str!("right"),
            jni_str!("bottom"),
        ]
        .into_iter()
        .enumerate()
        {
            let edge = env
                .get_field(&visible, field, jni_sig!("I"))?
                .i()?
                .saturating_sub(screen[i % 2]);
            safe[i] = if i < 2 {
                safe[i].max(edge)
            } else {
                safe[i].min(edge)
            };
        }
        if sdk >= 28 && !root_insets.is_null() {
            let cutout = env
                .call_method(
                    &root_insets,
                    jni_str!("getDisplayCutout"),
                    jni_sig!("()Landroid/view/DisplayCutout;"),
                    &[],
                )?
                .l()?;
            if !cutout.is_null() {
                for (i, method) in [
                    jni_str!("getSafeInsetLeft"),
                    jni_str!("getSafeInsetTop"),
                    jni_str!("getSafeInsetRight"),
                    jni_str!("getSafeInsetBottom"),
                ]
                .into_iter()
                .enumerate()
                {
                    let inset = env
                        .call_method(&cutout, method, jni_sig!("()I"), &[])?
                        .i()?;
                    safe[i] = if i < 2 {
                        safe[i].max(inset)
                    } else {
                        safe[i].min(root[i % 2] - inset)
                    };
                }
            }
        }
    }
    if safe[0] < 0
        || safe[1] < 0
        || safe[0] >= safe[2]
        || safe[1] >= safe[3]
        || safe[2] > root[0]
        || safe[3] > root[1]
        || size != [surface.width(), surface.height()]
    {
        return Ok(None);
    }
    Ok(Some(Sample {
        surface,
        size,
        insets: [
            origin[0] + safe[0],
            origin[1] + safe[1],
            size[0] - origin[0] - safe[2],
            size[1] - origin[1] - safe[3],
        ],
    }))
}
