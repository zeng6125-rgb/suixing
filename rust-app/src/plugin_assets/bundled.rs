//! APK assets have real consumers: decoded motion frames and browsable license
//! text. Asset listing/read/Bitmap JNI runs on two fixed workers, never in Draw.
use eframe::egui::{self, ColorImage, TextureHandle, TextureOptions};
use std::{
    collections::BTreeMap,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
        mpsc::{self, SyncSender},
    },
    time::{Duration, Instant},
};
const CAPACITY: usize = 32;
#[derive(Clone)]
pub(crate) enum Query {
    Frames(String),
    Frame(String),
    LicenseNames,
    LicenseText(String),
}
pub(crate) enum Payload {
    Names(Vec<String>),
    Pixels {
        width: usize,
        height: usize,
        rgba: Vec<u8>,
    },
    Text(String),
}
struct Slot {
    created: Instant,
    value: Option<Result<Payload, String>>,
}
static SLOTS: OnceLock<Mutex<BTreeMap<u64, Slot>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn slots() -> &'static Mutex<BTreeMap<u64, Slot>> {
    SLOTS.get_or_init(Default::default)
}
static WORKERS: OnceLock<Result<SyncSender<(u64, Query)>, String>> = OnceLock::new();
pub(crate) struct Pending(u64);
impl Drop for Pending {
    fn drop(&mut self) {
        slots()
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .remove(&self.0);
    }
}
pub(crate) fn request(query: Query) -> Result<Pending, String> {
    let worker = WORKERS
        .get_or_init(|| {
            let (sender, receive) = mpsc::sync_channel::<(u64, Query)>(CAPACITY);
            let receive = Arc::new(Mutex::new(receive));
            for index in 0..2 {
                let receive = receive.clone();
                std::thread::Builder::new()
                    .name(format!("rust-bundled-assets-{index}"))
                    .spawn(move || {
                        loop {
                            let work = receive
                                .lock()
                                .unwrap_or_else(|error| error.into_inner())
                                .recv();
                            let Ok((id, query)) = work else {
                                break;
                            };
                            let result =
                                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                                    load(query)
                                }))
                                .unwrap_or_else(|_| Err("随包资产读取异常".into()));
                            let mut state =
                                slots().lock().unwrap_or_else(|error| error.into_inner());
                            if let Some(slot) = state.get_mut(&id) {
                                slot.value = Some(result);
                            }
                        }
                    })
                    .map_err(|_| "随包资产工作线程不可用")?;
            }
            Ok(sender)
        })
        .as_ref()
        .map_err(Clone::clone)?;
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    let mut state = slots().lock().unwrap_or_else(|error| error.into_inner());
    state.retain(|_, slot| {
        slot.value.is_none() || slot.created.elapsed() < Duration::from_secs(600)
    });
    if state.len() >= CAPACITY {
        return Err("随包资产读取队列已满".into());
    }
    state.insert(
        id,
        Slot {
            created: Instant::now(),
            value: None,
        },
    );
    if worker.try_send((id, query)).is_err() {
        state.remove(&id);
        return Err("随包资产读取队列不可用".into());
    }
    Ok(Pending(id))
}
pub(crate) fn receive(pending: &Pending) -> Option<Result<Payload, String>> {
    slots()
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .get_mut(&pending.0)
        .and_then(|slot| slot.value.take())
}
#[cfg(not(target_os = "android"))]
fn load(_query: Query) -> Result<Payload, String> {
    Err("当前平台没有Android APK资产".into())
}
#[derive(Default)]
pub(crate) struct View {
    group: String,
    names: Vec<String>,
    frame_names: Option<Pending>,
    frame: Option<(String, Pending)>,
    textures: BTreeMap<String, TextureHandle>,
    order: Vec<String>,
    started: Option<Instant>,
    license_names: Vec<String>,
    license_started: bool,
    license_list: Option<Pending>,
    license_read: Option<Pending>,
    selected_license: String,
    license_text: String,
    error: Option<String>,
}
impl View {
    pub(crate) fn cached_icon(&self) -> Option<TextureHandle> {
        self.order
            .last()
            .and_then(|name| self.textures.get(name))
            .cloned()
    }

    pub(crate) fn show_icon(&mut self, ui: &mut egui::Ui, running: bool, foreground: bool) {
        if !foreground {
            ui.allocate_space(egui::vec2(34.0, 34.0));
            return;
        }
        let group = if running { "roll" } else { "blink" };
        if self.group != group {
            self.group = group.into();
            self.names.clear();
            self.frame_names = request(Query::Frames(group.into())).ok();
            self.started = Some(Instant::now());
        }
        if let Some(pending) = self.frame_names.as_ref() {
            if let Some(result) = receive(pending) {
                self.frame_names = None;
                match result {
                    Ok(Payload::Names(names)) => self.names = names,
                    Err(error) => self.error = Some(error),
                    _ => self.error = Some("随包帧列表返回类型错误".into()),
                }
            }
        }
        if let Some((name, pending)) = self.frame.as_ref() {
            if let Some(result) = receive(pending) {
                let name = name.clone();
                self.frame = None;
                match result {
                    Ok(Payload::Pixels {
                        width,
                        height,
                        rgba,
                    }) => {
                        let image = ColorImage::from_rgba_unmultiplied([width, height], &rgba);
                        let texture = ui.ctx().load_texture(&name, image, TextureOptions::LINEAR);
                        self.textures.insert(name.clone(), texture);
                        self.order.retain(|old| old != &name);
                        self.order.push(name);
                        while self.order.len() > 8 {
                            let old = self.order.remove(0);
                            self.textures.remove(&old);
                        }
                    }
                    Err(error) => self.error = Some(error),
                    _ => self.error = Some("随包图片返回类型错误".into()),
                }
            }
        }
        let mut selected = self.names.first().cloned();
        if foreground && !self.names.is_empty() {
            let duration: Vec<_> = self.names.iter().map(|name| frame_duration(name)).collect();
            let total: usize = duration.iter().sum();
            let mut time = self
                .started
                .map(|start| start.elapsed().as_millis() as usize % total.max(1))
                .unwrap_or(0);
            for (name, duration) in self.names.iter().zip(duration) {
                if time < duration {
                    selected = Some(name.clone());
                    break;
                }
                time -= duration;
            }
            ui.ctx().request_repaint_after(Duration::from_millis(30));
        }
        if let Some(name) = selected {
            if let Some(texture) = self.textures.get(&name) {
                ui.add(egui::Image::new((texture.id(), egui::vec2(34.0, 34.0))));
            } else {
                if let Some(texture) = self.order.last().and_then(|old| self.textures.get(old)) {
                    ui.add(egui::Image::new((texture.id(), egui::vec2(34.0, 34.0))));
                } else {
                    ui.allocate_space(egui::vec2(34.0, 34.0));
                }
                if self.frame.is_none() {
                    self.frame = request(Query::Frame(name.clone()))
                        .ok()
                        .map(|pending| (name, pending));
                }
            }
        }
        if self.frame.is_some() || self.frame_names.is_some() {
            ui.ctx().request_repaint_after(Duration::from_millis(30));
        }
    }
    pub(crate) fn show_licenses(&mut self, ui: &mut egui::Ui) {
        if self.license_names.is_empty() && !self.license_started {
            self.license_started = true;
            self.license_list = request(Query::LicenseNames).ok();
        }
        if let Some(pending) = self.license_list.as_ref() {
            if let Some(result) = receive(pending) {
                self.license_list = None;
                match result {
                    Ok(Payload::Names(names)) => self.license_names = names,
                    Err(error) => self.error = Some(error),
                    _ => self.error = Some("许可文件列表返回类型错误".into()),
                }
            }
        }
        if let Some(pending) = self.license_read.as_ref() {
            if let Some(result) = receive(pending) {
                self.license_read = None;
                match result {
                    Ok(Payload::Text(text)) => self.license_text = text,
                    Err(error) => self.error = Some(error),
                    _ => self.error = Some("许可文本返回类型错误".into()),
                }
            }
        }
        let old = self.selected_license.clone();
        egui::ComboBox::from_id_salt("bundled-license")
            .selected_text(if self.selected_license.is_empty() {
                "选择许可文件"
            } else {
                &self.selected_license
            })
            .show_ui(ui, |ui| {
                for path in &self.license_names {
                    ui.selectable_value(&mut self.selected_license, path.clone(), path);
                }
            });
        if old != self.selected_license {
            self.license_text.clear();
            self.license_read = request(Query::LicenseText(self.selected_license.clone())).ok();
        }
        if let Some(error) = self.error.as_ref() {
            ui.label(error);
            if ui.button("重试读取许可文件").clicked() {
                self.license_started = false;
            }
        }
        if self.license_list.is_some() || self.license_read.is_some() {
            ui.spinner();
            ui.ctx().request_repaint_after(Duration::from_millis(30));
        }
        egui::ScrollArea::vertical()
            .max_height(350.0)
            .show(ui, |ui| {
                ui.add(egui::Label::new(&self.license_text).wrap().selectable(true));
            });
    }
}
pub(crate) fn frame_duration(name: &str) -> usize {
    name.rsplit('/')
        .next()
        .and_then(|name| name.strip_suffix(".webp"))
        .and_then(|name| name.rsplit_once('_'))
        .and_then(|(_, value)| value.parse::<usize>().ok())
        .unwrap_or(30)
        .clamp(1, 5000)
}

#[cfg(target_os = "android")]
fn load(query: Query) -> Result<Payload, String> {
    use crate::platform::android;
    use jni::{
        JValue, jni_sig, jni_str,
        objects::{JObject, JObjectArray},
    };
    let context = android::application_context()?;
    android::invoke(|env| {
        let assets = env
            .call_method(
                context.as_ref(),
                jni_str!("getAssets"),
                jni_sig!("()Landroid/content/res/AssetManager;"),
                &[],
            )?
            .l()?;
        fn list(
            env: &mut jni::Env,
            assets: &JObject,
            path: &str,
        ) -> jni::errors::Result<Vec<String>> {
            let path = env.new_string(path)?;
            let array = env
                .call_method(
                    assets,
                    jni_str!("list"),
                    jni_sig!("(Ljava/lang/String;)[Ljava/lang/String;"),
                    &[JValue::Object(&path)],
                )?
                .l()?;
            let array = env.cast_local::<JObjectArray>(array)?;
            let length = env.get_array_length(&array)?;
            if !(0..=256).contains(&length) {
                return Err(jni::errors::Error::NullPtr(
                    "APK asset directory exceeds budget",
                ));
            }
            let mut names = Vec::new();
            for index in 0..length as usize {
                let name = env.get_object_array_element(&array, index)?;
                names.push(android::string_value(env, name, 1024)?);
            }
            names.sort();
            Ok(names)
        }
        fn read(env: &mut jni::Env, assets: &JObject, path: &str) -> jni::errors::Result<Vec<u8>> {
            let path = env.new_string(path)?;
            let input = env
                .call_method(
                    assets,
                    jni_str!("open"),
                    jni_sig!("(Ljava/lang/String;)Ljava/io/InputStream;"),
                    &[JValue::Object(&path)],
                )?
                .l()?;
            let result = (|| {
                let buffer = env.new_byte_array(32 * 1024)?;
                let mut bytes = Vec::new();
                loop {
                    let count = env
                        .call_method(
                            &input,
                            jni_str!("read"),
                            jni_sig!("([B)I"),
                            &[JValue::Object(&buffer)],
                        )?
                        .i()?;
                    if count < 0 {
                        break;
                    }
                    if count == 0
                        || bytes
                            .len()
                            .checked_add(count as usize)
                            .is_none_or(|length| length > 256 * 1024)
                    {
                        return Err(jni::errors::Error::NullPtr("APK asset exceeds budget"));
                    }
                    let mut chunk = vec![0i8; count as usize];
                    env.get_byte_array_region(&buffer, 0, &mut chunk)?;
                    bytes.extend(chunk.into_iter().map(|byte| byte as u8));
                }
                Ok(bytes)
            })();
            if env.exception_check() {
                env.exception_clear();
            }
            let closed = env.call_method(&input, jni_str!("close"), jni_sig!("()V"), &[]);
            closed?;
            result
        }
        match query {
            Query::Frames(group) => {
                if !matches!(
                    group.as_str(),
                    "roll" | "blink" | "head" | "signal" | "sparks"
                ) {
                    return Err(jni::errors::Error::NullPtr("unknown motion group"));
                }
                let path = format!("ball_motion/{group}");
                let names = list(env, &assets, &path)?
                    .into_iter()
                    .filter(|name| {
                        name.ends_with(".webp")
                            && name
                                .bytes()
                                .all(|byte| byte.is_ascii_alphanumeric() || b"_.".contains(&byte))
                    })
                    .map(|name| format!("{path}/{name}"))
                    .collect();
                Ok(Payload::Names(names))
            }
            Query::Frame(path) => {
                let parts: Vec<_> = path.split('/').collect();
                if path != "@brand_mark_round"
                    && (parts.len() != 3
                        || parts[0] != "ball_motion"
                        || !matches!(parts[1], "roll" | "blink" | "head" | "signal" | "sparks")
                        || !parts[2]
                            .bytes()
                            .all(|byte| byte.is_ascii_alphanumeric() || b"_.".contains(&byte)))
                {
                    return Err(jni::errors::Error::NullPtr("motion frame path invalid"));
                }
                let bytes = if path == "@brand_mark_round" {
                    include_bytes!(
                        "../../../app/src/main/res/drawable-xxxhdpi/brand_mark_round.png"
                    )
                    .to_vec()
                } else {
                    read(env, &assets, &path)?
                };
                let data = env.byte_array_from_slice(&bytes)?;
                let bitmap = env
                    .call_static_method(
                        jni_str!("android/graphics/BitmapFactory"),
                        jni_str!("decodeByteArray"),
                        jni_sig!("([BII)Landroid/graphics/Bitmap;"),
                        &[
                            JValue::Object(&data),
                            JValue::Int(0),
                            JValue::Int(bytes.len() as i32),
                        ],
                    )?
                    .l()?;
                if bitmap.is_null() {
                    return Err(jni::errors::Error::NullPtr("motion bitmap unavailable"));
                }
                let width = env
                    .call_method(&bitmap, jni_str!("getWidth"), jni_sig!("()I"), &[])?
                    .i()?;
                let height = env
                    .call_method(&bitmap, jni_str!("getHeight"), jni_sig!("()I"), &[])?
                    .i()?;
                if !(1..=512).contains(&width) || !(1..=512).contains(&height) {
                    return Err(jni::errors::Error::NullPtr(
                        "motion bitmap geometry exceeds budget",
                    ));
                }
                let pixel_count = width as usize * height as usize;
                let array = env.new_int_array(pixel_count)?;
                env.call_method(
                    &bitmap,
                    jni_str!("getPixels"),
                    jni_sig!("([IIIIIII)V"),
                    &[
                        JValue::Object(&array),
                        JValue::Int(0),
                        JValue::Int(width),
                        JValue::Int(0),
                        JValue::Int(0),
                        JValue::Int(width),
                        JValue::Int(height),
                    ],
                )?;
                let mut argb = vec![0i32; pixel_count];
                env.get_int_array_region(&array, 0, &mut argb)?;
                let mut rgba = Vec::with_capacity(argb.len() * 4);
                for pixel in argb {
                    let pixel = pixel as u32;
                    rgba.extend_from_slice(&[
                        (pixel >> 16) as u8,
                        (pixel >> 8) as u8,
                        pixel as u8,
                        (pixel >> 24) as u8,
                    ]);
                }
                env.call_method(&bitmap, jni_str!("recycle"), jni_sig!("()V"), &[])?;
                Ok(Payload::Pixels {
                    width: width as usize,
                    height: height as usize,
                    rgba,
                })
            }
            Query::LicenseNames => {
                let mut queue = vec!["licenses".to_owned()];
                let mut names = Vec::new();
                while let Some(path) = queue.pop() {
                    if path.split('/').count() > 8 || queue.len() + names.len() > 512 {
                        return Err(jni::errors::Error::NullPtr(
                            "license inventory exceeds budget",
                        ));
                    }
                    let children = list(env, &assets, &path)?;
                    if children.is_empty() {
                        names.push(path);
                    } else {
                        for child in children {
                            if child.contains(['\0', '/', '\\']) || child == ".." || child == "." {
                                return Err(jni::errors::Error::NullPtr(
                                    "license asset name invalid",
                                ));
                            }
                            queue.push(format!("{path}/{child}"));
                        }
                    }
                }
                names.sort();
                Ok(Payload::Names(names))
            }
            Query::LicenseText(path) => {
                if !path.starts_with("licenses/")
                    || path
                        .split('/')
                        .any(|part| part.is_empty() || part == ".." || part == ".")
                {
                    return Err(jni::errors::Error::NullPtr("license asset path invalid"));
                }
                let bytes = read(env, &assets, &path)?;
                let text = String::from_utf8(bytes)
                    .map_err(|_| jni::errors::Error::NullPtr("license text is not UTF8"))?;
                Ok(Payload::Text(text))
            }
        }
    })
}
