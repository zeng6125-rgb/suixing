//! Canvas owns its own Bitmaps; egui TextureHandles never cross this surface.
//! Asset decode remains on the shared fixed worker pool. Drawing polls finished
//! RGBA and copies at most one frame; it does not read disk or call a provider.
use super::*;
use crate::plugin_assets::bundled::{self, Payload, Pending, Query};
use std::collections::BTreeMap;

pub(super) struct Motion {
    group: String,
    names: BTreeMap<String, Vec<String>>,
    list: Option<(String, Pending)>,
    frame: Option<(String, Pending)>,
    cache: BTreeMap<String, Object>,
    recent: Vec<String>,
    started: Instant,
    running: bool,
    scale: f32,
    refreshed: Instant,
    press_from: f32,
    press_target: f32,
    press_started: Instant,
    press_duration: Duration,
    pressed: bool,
    error: Option<String>,
}
impl Default for Motion {
    fn default() -> Self {
        Self {
            group: "blink".into(),
            names: BTreeMap::new(),
            list: None,
            frame: None,
            cache: BTreeMap::new(),
            recent: Vec::new(),
            started: Instant::now(),
            running: false,
            scale: 1.0,
            refreshed: Instant::now() - Duration::from_secs(2),
            press_from: 1.0,
            press_target: 1.0,
            press_started: Instant::now(),
            press_duration: Duration::ZERO,
            pressed: false,
            error: None,
        }
    }
}
impl Motion {
    fn mode(&mut self, group: &str) {
        self.group = group.into();
        self.started = Instant::now();
        self.list = None;
        self.frame = None;
        self.error = None;
    }
    fn ambient(&self) -> &'static str {
        if self.running { "signal" } else { "blink" }
    }
    pub(super) fn refresh_due(&self) -> bool {
        self.refreshed.elapsed() >= Duration::from_secs(1)
    }
    pub(super) fn refresh(
        &mut self,
        env: &mut Env,
        context: &JObject,
        running: bool,
    ) -> jni::errors::Result<()> {
        if running != self.running {
            let started = running && !self.running;
            self.running = running;
            if started {
                self.mode("sparks");
            } else if matches!(self.group.as_str(), "signal" | "blink") {
                self.mode(self.ambient());
            }
        }
        if self.refresh_due() {
            let resolver = env
                .call_method(
                    context,
                    jni_str!("getContentResolver"),
                    jni_sig!("()Landroid/content/ContentResolver;"),
                    &[],
                )?
                .l()?;
            let name = env.new_string("animator_duration_scale")?;
            let scale = env
                .call_static_method(
                    jni_str!("android/provider/Settings$Global"),
                    jni_str!("getFloat"),
                    jni_sig!("(Landroid/content/ContentResolver;Ljava/lang/String;F)F"),
                    &[
                        JValue::Object(&resolver),
                        JValue::Object(&name),
                        JValue::Float(1.0),
                    ],
                )?
                .f()?;
            self.scale = if scale.is_finite() && scale >= 0.0 {
                scale
            } else {
                1.0
            };
            self.refreshed = Instant::now();
        }
        Ok(())
    }
    fn disc_scale(&self) -> f32 {
        if self.press_duration.is_zero() {
            return self.press_target;
        }
        let t = (self.press_started.elapsed().as_secs_f32() / self.press_duration.as_secs_f32())
            .clamp(0.0, 1.0);
        let eased = 1.0 - (1.0 - t) * (1.0 - t);
        self.press_from + (self.press_target - self.press_from) * eased
    }
    pub(super) fn press(&mut self) {
        self.pressed = true;
        self.transition(0.96, 90);
        self.mode(if self.group == "head" { "roll" } else { "head" });
    }
    pub(super) fn release(&mut self) {
        if self.pressed {
            self.pressed = false;
            self.transition(1.0, 160);
        }
    }
    fn transition(&mut self, target: f32, milliseconds: u64) {
        self.press_from = self.disc_scale();
        self.press_target = target;
        self.press_started = Instant::now();
        self.press_duration = if self.scale == 0.0 {
            Duration::ZERO
        } else {
            Duration::from_millis(milliseconds)
        };
    }
    fn bitmap(
        env: &mut Env,
        width: usize,
        height: usize,
        rgba: Vec<u8>,
    ) -> jni::errors::Result<Object> {
        if width == 0
            || height == 0
            || width > 512
            || height > 512
            || rgba.len() != width * height * 4
        {
            return Err(jni::errors::Error::NullPtr("floating bitmap budget"));
        }
        let pixels: Vec<i32> = rgba
            .chunks_exact(4)
            .map(|p| {
                ((u32::from(p[3]) << 24)
                    | (u32::from(p[0]) << 16)
                    | (u32::from(p[1]) << 8)
                    | u32::from(p[2])) as i32
            })
            .collect();
        let array = env.new_int_array(pixels.len())?;
        env.set_int_array_region(&array, 0, &pixels)?;
        let config = env
            .get_static_field(
                jni_str!("android/graphics/Bitmap$Config"),
                jni_str!("ARGB_8888"),
                jni_sig!("Landroid/graphics/Bitmap$Config;"),
            )?
            .l()?;
        let bitmap = env
            .call_static_method(
                jni_str!("android/graphics/Bitmap"),
                jni_str!("createBitmap"),
                jni_sig!("([IIILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"),
                &[
                    JValue::Object(&array),
                    JValue::Int(width as i32),
                    JValue::Int(height as i32),
                    JValue::Object(&config),
                ],
            )?
            .l()?;
        Ok(Arc::new(env.new_global_ref(bitmap)?))
    }
    pub(super) fn draw(
        &mut self,
        env: &mut Env,
        canvas: &JObject,
        view: &JObject,
        paint: &JObject,
        width: f32,
        height: f32,
        density: f32,
    ) -> jni::errors::Result<()> {
        if let Some((group, pending)) = &self.list {
            if let Some(result) = bundled::receive(pending) {
                let group = group.clone();
                self.list = None;
                match result {
                    Ok(Payload::Names(names)) if !names.is_empty() && names.len() <= 4096 => {
                        self.names.insert(group, names);
                        self.started = Instant::now();
                    }
                    Err(error) => self.error = Some(error),
                    _ => self.error = Some("悬浮动画帧列表无效".into()),
                }
            }
        }
        if let Some((name, pending)) = &self.frame {
            if let Some(result) = bundled::receive(pending) {
                let name = name.clone();
                self.frame = None;
                match result {
                    Ok(Payload::Pixels {
                        width,
                        height,
                        rgba,
                    }) => {
                        let bitmap = Self::bitmap(env, width, height, rgba)?;
                        self.cache.insert(name.clone(), bitmap);
                        if name != "@brand_mark_round" {
                            self.recent.retain(|old| old != &name);
                            self.recent.push(name);
                        }
                        while self.recent.len() > 7 {
                            let old = self.recent.remove(0);
                            self.cache.remove(&old);
                        }
                    }
                    Err(error) => self.error = Some(error),
                    _ => self.error = Some("悬浮动画图片无效".into()),
                }
            }
        }
        if self.list.is_none()
            && !self.names.contains_key(&self.group)
            && self.error.is_none()
            && self.scale > 0.0
        {
            match bundled::request(Query::Frames(self.group.clone())) {
                Ok(pending) => self.list = Some((self.group.clone(), pending)),
                Err(error) => self.error = Some(error),
            }
        }
        let mut selected = "@brand_mark_round".to_owned();
        let mut delay = 30u64;
        if self.scale > 0.0 {
            if let Some(names) = self.names.get(&self.group) {
                let total: usize = names.iter().map(|name| bundled::frame_duration(name)).sum();
                let elapsed =
                    (self.started.elapsed().as_secs_f64() * 1000.0 / f64::from(self.scale)) as u64;
                if !matches!(self.group.as_str(), "signal" | "blink") && elapsed >= total as u64 {
                    self.mode(self.ambient());
                } else {
                    let mut timeline = elapsed as usize % total.max(1);
                    for name in names {
                        let duration = bundled::frame_duration(name);
                        if timeline < duration {
                            selected = name.clone();
                            delay = (((duration - timeline) as f64 * f64::from(self.scale)) as u64)
                                .clamp(1, 1000);
                            break;
                        }
                        timeline -= duration;
                    }
                }
            }
        }
        // The static original round artwork is the initial and reduced-motion fallback.
        if !self.cache.contains_key("@brand_mark_round") && self.frame.is_none() {
            if let Ok(pending) = bundled::request(Query::Frame("@brand_mark_round".into())) {
                self.frame = Some(("@brand_mark_round".into(), pending));
            }
        } else if !self.cache.contains_key(&selected)
            && self.frame.is_none()
            && self.error.is_none()
        {
            match bundled::request(Query::Frame(selected.clone())) {
                Ok(pending) => self.frame = Some((selected.clone(), pending)),
                Err(error) => self.error = Some(error),
            }
        }
        let bitmap = self
            .cache
            .get(&selected)
            .or_else(|| {
                self.recent
                    .iter()
                    .rev()
                    .find_map(|name| self.cache.get(name))
            })
            .or_else(|| self.cache.get("@brand_mark_round"));
        if let Some(bitmap) = bitmap {
            env.call_method(
                paint,
                jni_str!("setFilterBitmap"),
                jni_sig!("(Z)V"),
                &[JValue::Bool(true)],
            )?;
            let side = 42.0 * density * self.disc_scale();
            let left = (width - side) / 2.0;
            let top = (height - side) / 2.0;
            let bounds = env.new_object(
                jni_str!("android/graphics/RectF"),
                jni_sig!("(FFFF)V"),
                &[
                    JValue::Float(left),
                    JValue::Float(top),
                    JValue::Float(left + side),
                    JValue::Float(top + side),
                ],
            )?;
            env.call_method(canvas, jni_str!("drawBitmap"), jni_sig!("(Landroid/graphics/Bitmap;Landroid/graphics/Rect;Landroid/graphics/RectF;Landroid/graphics/Paint;)V"), &[JValue::Object(bitmap.as_ref()), JValue::Object(&JObject::null()), JValue::Object(&bounds), JValue::Object(paint)])?;
        }
        if self.frame.is_some() || self.list.is_some() {
            delay = delay.min(30);
        }
        let feedback = self.press_started.elapsed() < self.press_duration;
        if feedback {
            delay = delay.min(16);
        }
        if self.scale > 0.0 && self.error.is_none()
            || self.frame.is_some()
            || self.list.is_some()
            || feedback
        {
            env.call_method(
                view,
                jni_str!("postInvalidateDelayed"),
                jni_sig!("(J)V"),
                &[JValue::Long(delay as i64)],
            )?;
        }
        Ok(())
    }
}
