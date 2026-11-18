//! Picker results keep their originating conversation. URI reads and bitmap work
//! occur on bounded workers; only confirmed files/pixels reach the renderer.
use super::permissions::OperationKey;
use std::{
    collections::{BTreeMap, VecDeque},
    path::PathBuf,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, Ordering},
    },
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AttachmentKind {
    Images,
    Avatar,
    File,
    Camera,
}
pub struct PixelFrame {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}
impl std::fmt::Debug for PixelFrame {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PixelFrame")
            .field("width", &self.width)
            .field("height", &self.height)
            .field("bytes", &self.rgba.len())
            .finish()
    }
}
#[derive(Clone)]
pub struct AttachmentDraft {
    pub id: String,
    pub display_name: String,
    pub relative_path: PathBuf,
    pub sha256: String,
    pub bytes: u64,
    pub mime_type: String,
    pub image_base64: Option<String>,
    pub preview: Option<Arc<PixelFrame>>,
}
impl std::fmt::Debug for AttachmentDraft {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AttachmentDraft")
            .field("id", &self.id)
            .field("bytes", &self.bytes)
            .field("image", &self.image_base64.is_some())
            .finish()
    }
}
#[derive(Debug)]
pub struct AttachmentResult {
    pub operation: OperationKey,
    pub result: Result<Vec<AttachmentDraft>, String>,
    pub cancelled: bool,
}
struct Control {
    cancelled: AtomicBool,
    #[cfg(target_os = "android")]
    signal: Mutex<Option<Arc<jni::refs::Global<jni::objects::JObject<'static>>>>>,
}
impl Default for Control {
    fn default() -> Self {
        Self {
            cancelled: AtomicBool::new(false),
            #[cfg(target_os = "android")]
            signal: Mutex::new(None),
        }
    }
}
struct Pending {
    operation: OperationKey,
    kind: AttachmentKind,
    workspace: PathBuf,
    control: Arc<Control>,
    processing: bool,
}
#[derive(Default)]
struct Registry {
    requests: BTreeMap<i32, Pending>,
    active_picker: Option<i32>,
    results: VecDeque<AttachmentResult>,
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
const MAX_PENDING: usize = 8;
const MAX_PROCESSING: usize = 2;
fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
}

pub fn pick(
    operation: OperationKey,
    kind: AttachmentKind,
    workspace: PathBuf,
) -> Result<(), String> {
    operation.validate()?;
    #[cfg(not(target_os = "android"))]
    {
        let _ = (kind, workspace);
        return Err("当前平台没有Android附件选择入口".into());
    }
    #[cfg(target_os = "android")]
    {
        if !super::android::activity_visible() {
            return Err("附件选择需要可见的应用页面".into());
        }
        {
            let state = registry().lock().unwrap_or_else(|e| e.into_inner());
            if state.active_picker.is_some()
                || state.requests.len() + state.results.len() >= MAX_PENDING
                || state.requests.values().filter(|r| r.processing).count() >= MAX_PROCESSING
            {
                return Err("附件选择或读取正在进行，请等待当前操作".into());
            }
        }
        let code = super::permissions::allocate_request_code()?;
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        if state.active_picker.is_some()
            || state.requests.len() + state.results.len() >= MAX_PENDING
            || state.requests.values().filter(|r| r.processing).count() >= MAX_PROCESSING
            || state
                .requests
                .values()
                .any(|r| r.operation.id == operation.id)
            || state.results.iter().any(|r| r.operation.id == operation.id)
        {
            return Err("附件操作队列已满".into());
        }
        state.requests.insert(
            code,
            Pending {
                operation,
                kind,
                workspace,
                control: Arc::new(Control::default()),
                processing: false,
            },
        );
        state.active_picker = Some(code);
        drop(state);
        if let Err(error) = super::android::post_to_main(move || device::launch(code, kind)) {
            finish(code, Err(error));
        }
        Ok(())
    }
}
pub fn cancel(operation_id: &str) {
    let controls: Vec<_> = {
        let state = registry().lock().unwrap_or_else(|e| e.into_inner());
        state
            .requests
            .values()
            .filter(|r| r.operation.id == operation_id)
            .map(|r| r.control.clone())
            .collect()
    };
    for control in controls {
        if control.cancelled.swap(true, Ordering::AcqRel) {
            continue;
        }
        #[cfg(target_os = "android")]
        {
            let signal = control
                .signal
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone();
            if let Some(signal) = signal {
                let _ = std::thread::Builder::new()
                    .name("rust-uri-cancel".into())
                    .spawn(move || {
                        let _ = super::android::invoke(|env| {
                            env.call_method(
                                signal.as_ref(),
                                jni::jni_str!("cancel"),
                                jni::jni_sig!("()V"),
                                &[],
                            )?;
                            Ok(())
                        });
                    });
            }
        }
    }
}
pub fn drain_results(limit: usize) -> Vec<AttachmentResult> {
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    let count = limit.min(state.results.len());
    state.results.drain(..count).collect()
}
/// The process actor routes each result to its exact owner before Composer consumes the remainder.
pub(crate) fn drain_matching_results(
    limit: usize,
    matches: impl Fn(&OperationKey) -> bool,
) -> Vec<AttachmentResult> {
    let mut state = registry().lock().unwrap_or_else(|error| error.into_inner());
    let mut output = Vec::new();
    let mut index = 0;
    while index < state.results.len() && output.len() < limit {
        if matches(&state.results[index].operation) {
            if let Some(result) = state.results.remove(index) {
                output.push(result);
            }
        } else {
            index += 1;
        }
    }
    output
}
pub fn activity_destroyed() {
    let codes: Vec<_> = {
        let state = registry().lock().unwrap_or_else(|e| e.into_inner());
        state
            .requests
            .iter()
            .filter(|(_, r)| !r.processing)
            .map(|(code, _)| *code)
            .collect()
    };
    for code in codes {
        finish(code, Err("附件选择被Activity结束中断；草稿保留".into()));
    }
}
fn finish(code: i32, result: Result<Vec<AttachmentDraft>, String>) {
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    let Some(request) = state.requests.remove(&code) else {
        return;
    };
    if state.active_picker == Some(code) {
        state.active_picker = None;
    }
    let cancelled = request.control.cancelled.load(Ordering::Acquire);
    state.results.push_back(AttachmentResult {
        operation: request.operation,
        result: if cancelled {
            Err("附件操作已取消，未修改其它会话草稿".into())
        } else {
            result
        },
        cancelled,
    });
}
/// A JNI decoding/dispatch failure must release the exact pending request too.
pub fn on_result_error(code: i32, error: &str) {
    finish(code, Err(error.into()));
}
#[cfg(target_os = "android")]
pub fn on_activity_result(
    code: i32,
    result_code: i32,
    intent: Option<Arc<jni::refs::Global<jni::objects::JObject<'static>>>>,
) {
    let (operation, kind, workspace, control) = {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        let Some(request) = state.requests.get_mut(&code) else {
            return;
        };
        if request.processing {
            return;
        }
        request.processing = true;
        let values = (
            request.operation.clone(),
            request.kind,
            request.workspace.clone(),
            request.control.clone(),
        );
        if state.active_picker == Some(code) {
            state.active_picker = None;
        }
        values
    };
    if result_code != -1 {
        control.cancelled.store(true, Ordering::Release);
    }
    if result_code != -1 || intent.is_none() {
        finish(code, Err("附件选择已取消或没有返回内容".into()));
        return;
    }
    let intent = intent.unwrap();
    if let Err(error) = std::thread::Builder::new()
        .name("rust-attachment-import".into())
        .spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                device::import(code, &operation, kind, &workspace, &intent, &control)
            }))
            .unwrap_or_else(|_| Err("附件读取或解码异常，原文件保留".into()));
            finish(code, result);
        })
    {
        finish(code, Err(format!("附件读取线程无法启动：{error}")));
    }
}

#[cfg(target_os = "android")]
pub fn decode_base64_preview(base64: &str, max_side: u32) -> Result<PixelFrame, String> {
    device::decode_base64(base64, max_side)
}
/// The bitmap is already owned by an Android callback. Encoding runs only on a
/// bounded worker. Width/height are independent limits and the pixel budget is
/// shared with attachment previews; no Activity or Canvas receives codec work.
#[cfg(target_os = "android")]
pub(crate) fn encode_android_bitmap(
    bitmap: &Arc<jni::refs::Global<jni::objects::JObject<'static>>>,
    max_width: u32,
    max_height: u32,
    quality: u8,
) -> Result<(Vec<u8>, u32, u32), String> {
    device::encode_bitmap(bitmap, max_width, max_height, quality)
}
pub struct CaptureImage {
    pub jpeg: Vec<u8>,
    pub width: u32,
    pub height: u32,
    pub source_width: u32,
    pub source_height: u32,
}
pub fn normalize_capture(
    bytes: &[u8],
    expected: Option<(u32, u32)>,
    max_width: u32,
    quality: u8,
) -> Result<CaptureImage, String> {
    if bytes.len() > 12 * 1024 * 1024
        || !(320..=2160).contains(&max_width)
        || !(1..=100).contains(&quality)
    {
        return Err("截屏压缩文件、尺寸或质量超出预算".into());
    }
    #[cfg(target_os = "android")]
    {
        device::normalize_capture(bytes, expected, max_width, quality)
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = expected;
        Err("当前平台没有截屏编解码后端".into())
    }
}
#[cfg(not(target_os = "android"))]
pub fn decode_base64_preview(_base64: &str, _max_side: u32) -> Result<PixelFrame, String> {
    Err("当前平台没有图片解码后端".into())
}

#[cfg(target_os = "android")]
mod device {
    use super::*;
    use crate::platform::android;
    use jni::{
        Env, JValue, jni_sig, jni_str,
        objects::{JByteArray, JObject},
        refs::Global,
    };
    use std::{
        fs::{self, OpenOptions},
        io::{Read, Seek, SeekFrom, Write},
        os::fd::{AsRawFd, FromRawFd},
        os::unix::fs::OpenOptionsExt,
        path::Path,
        time::{Duration, Instant},
    };
    type Object = Arc<Global<JObject<'static>>>;
    struct BitmapLease(Option<Object>);
    impl BitmapLease {
        fn release(&mut self) -> Result<(), String> {
            if let Some(bitmap) = self.0.as_ref() {
                android::invoke(|env| {
                    env.call_method(bitmap.as_ref(), jni_str!("recycle"), jni_sig!("()V"), &[])?;
                    Ok(())
                })?;
                self.0 = None;
            }
            Ok(())
        }
    }
    impl Drop for BitmapLease {
        fn drop(&mut self) {
            let _ = self.release();
        }
    }
    const MAX_FILE: u64 = 128 * 1024 * 1024;
    const MAX_IMAGE_SOURCE: u64 = 64 * 1024 * 1024;
    const MAX_BASE64: usize = 16 * 1024 * 1024;
    const MAX_IMAGE_COMPRESSED: usize = 12 * 1024 * 1024;
    const MAX_PIXELS: u64 = 4_194_304;
    pub(super) fn launch(code: i32, kind: AttachmentKind) {
        let cancelled = {
            let state = registry().lock().unwrap_or_else(|e| e.into_inner());
            state
                .requests
                .get(&code)
                .is_none_or(|r| r.control.cancelled.load(Ordering::Acquire))
        };
        if cancelled {
            finish(code, Err("附件选择已取消".into()));
            return;
        }
        let Some(activity) = android::current_activity() else {
            finish(code, Err("附件选择没有Activity".into()));
            return;
        };
        let result = android::invoke(|env| {
            let sdk = android::paths().map_or(0, |p| p.sdk_int);
            let action = match kind {
                AttachmentKind::Camera => "android.media.action.IMAGE_CAPTURE",
                AttachmentKind::Images | AttachmentKind::Avatar if sdk >= 33 => {
                    "android.provider.action.PICK_IMAGES"
                }
                _ => "android.intent.action.OPEN_DOCUMENT",
            };
            let action = env.new_string(action)?;
            let intent = env.new_object(
                jni_str!("android/content/Intent"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[JValue::Object(&action)],
            )?;
            if kind != AttachmentKind::Camera {
                let mime = env.new_string(
                    if matches!(kind, AttachmentKind::Images | AttachmentKind::Avatar) {
                        "image/*"
                    } else {
                        "*/*"
                    },
                )?;
                env.call_method(
                    &intent,
                    jni_str!("setType"),
                    jni_sig!("(Ljava/lang/String;)Landroid/content/Intent;"),
                    &[JValue::Object(&mime)],
                )?;
                if sdk >= 33 && kind == AttachmentKind::Images {
                    let maximum = env
                        .call_static_method(
                            jni_str!("android/provider/MediaStore"),
                            jni_str!("getPickImagesMaxLimit"),
                            jni_sig!("()I"),
                            &[],
                        )?
                        .i()?;
                    let key = env.new_string("android.provider.extra.PICK_IMAGES_MAX")?;
                    env.call_method(
                        &intent,
                        jni_str!("putExtra"),
                        jni_sig!("(Ljava/lang/String;I)Landroid/content/Intent;"),
                        &[JValue::Object(&key), JValue::Int(maximum.min(256))],
                    )?;
                } else {
                    let category = env.new_string("android.intent.category.OPENABLE")?;
                    env.call_method(
                        &intent,
                        jni_str!("addCategory"),
                        jni_sig!("(Ljava/lang/String;)Landroid/content/Intent;"),
                        &[JValue::Object(&category)],
                    )?;
                    let multiple = env.new_string("android.intent.extra.ALLOW_MULTIPLE")?;
                    env.call_method(
                        &intent,
                        jni_str!("putExtra"),
                        jni_sig!("(Ljava/lang/String;Z)Landroid/content/Intent;"),
                        &[
                            JValue::Object(&multiple),
                            JValue::Bool(kind == AttachmentKind::Images),
                        ],
                    )?;
                }
            }
            env.call_method(
                activity.as_ref(),
                jni_str!("startActivityForResult"),
                jni_sig!("(Landroid/content/Intent;I)V"),
                &[JValue::Object(&intent), JValue::Int(code)],
            )?;
            Ok(())
        });
        if let Err(error) = result {
            finish(code, Err(error));
        }
    }
    pub(super) fn import(
        code: i32,
        _operation: &OperationKey,
        kind: AttachmentKind,
        workspace: &Path,
        intent: &Object,
        control: &Arc<Control>,
    ) -> Result<Vec<AttachmentDraft>, String> {
        let workspace = workspace_root(workspace)?;
        if kind == AttachmentKind::Camera {
            let bitmap = android::invoke(|env| {
                let key = env.new_string("data")?;
                let bitmap = env
                    .call_method(
                        intent.as_ref(),
                        jni_str!("getParcelableExtra"),
                        jni_sig!("(Ljava/lang/String;)Landroid/os/Parcelable;"),
                        &[JValue::Object(&key)],
                    )?
                    .l()?;
                if bitmap.is_null() {
                    return Err(jni::errors::Error::NullPtr("camera bitmap"));
                }
                let class = env.find_class(jni_str!("android/graphics/Bitmap"))?;
                if !env.is_instance_of(&bitmap, class)? {
                    return Err(jni::errors::Error::NullPtr("camera result type"));
                }
                Ok(Arc::new(env.new_global_ref(bitmap)?))
            })?;
            let (base64, preview, bytes) = image_from_bitmap(&bitmap)?;
            check(control)?;
            let path = PathBuf::from(format!("inbox/{code}-camera.jpg"));
            publish_bytes(&workspace, &path, &bytes)?;
            return Ok(vec![draft(
                code,
                0,
                "照片".into(),
                path,
                bytes.len() as u64,
                hash(&bytes),
                "image/jpeg".into(),
                Some(base64),
                Some(Arc::new(preview)),
            )]);
        }
        let uris = android::invoke(|env| {
            let mut uris = Vec::new();
            let clip = env
                .call_method(
                    intent.as_ref(),
                    jni_str!("getClipData"),
                    jni_sig!("()Landroid/content/ClipData;"),
                    &[],
                )?
                .l()?;
            if !clip.is_null() {
                let count = env
                    .call_method(&clip, jni_str!("getItemCount"), jni_sig!("()I"), &[])?
                    .i()?;
                if count < 0 || count > 256 {
                    return Err(jni::errors::Error::NullPtr("picker item budget"));
                }
                for index in 0..count {
                    let item = env
                        .call_method(
                            &clip,
                            jni_str!("getItemAt"),
                            jni_sig!("(I)Landroid/content/ClipData$Item;"),
                            &[JValue::Int(index)],
                        )?
                        .l()?;
                    let uri = env
                        .call_method(
                            item,
                            jni_str!("getUri"),
                            jni_sig!("()Landroid/net/Uri;"),
                            &[],
                        )?
                        .l()?;
                    if uri.is_null() {
                        return Err(jni::errors::Error::NullPtr("picker uri"));
                    }
                    uris.push(Arc::new(env.new_global_ref(uri)?));
                }
            } else {
                let uri = env
                    .call_method(
                        intent.as_ref(),
                        jni_str!("getData"),
                        jni_sig!("()Landroid/net/Uri;"),
                        &[],
                    )?
                    .l()?;
                if !uri.is_null() {
                    uris.push(Arc::new(env.new_global_ref(uri)?));
                }
            }
            Ok(uris)
        })?;
        if uris.is_empty() {
            return Err("选择器没有返回可读取内容".into());
        }
        let mut output = Vec::new();
        let mut total = 0u64;
        for (index, uri) in uris.iter().enumerate() {
            check(control)?;
            android::invoke(|env| {
                let scheme = env
                    .call_method(
                        uri.as_ref(),
                        jni_str!("getScheme"),
                        jni_sig!("()Ljava/lang/String;"),
                        &[],
                    )?
                    .l()?;
                if scheme.is_null() || android::string_value(env, scheme, 32)? != "content" {
                    return Err(jni::errors::Error::NullPtr("picker requires content uri"));
                }
                Ok(())
            })?;
            let (name, mime) = uri_metadata(uri, control)?;
            let path = if matches!(kind, AttachmentKind::Images | AttachmentKind::Avatar) {
                PathBuf::from(format!("inbox/.source-{code}-{index}"))
            } else {
                PathBuf::from(format!("inbox/{code}-{index}-{}", safe_name(&name)))
            };
            let maximum = if matches!(kind, AttachmentKind::Images | AttachmentKind::Avatar) {
                MAX_IMAGE_SOURCE
            } else {
                MAX_FILE
            };
            let (size, digest) = copy_uri(&workspace, &path, uri, maximum, control)?;
            total = total.checked_add(size).ok_or("附件总长度溢出")?;
            if total > 256 * 1024 * 1024 {
                return Err("本次附件总长度超过256 MiB预算，未返回部分成功".into());
            }
            if matches!(kind, AttachmentKind::Images | AttachmentKind::Avatar) {
                let decoded = (|| {
                    let bytes =
                        fs::read(workspace.join(&path)).map_err(|_| "无法读取已复制图片")?;
                    let bitmap = decode_bytes_global(
                        &bytes,
                        if kind == AttachmentKind::Avatar {
                            1080
                        } else {
                            1280
                        },
                    )?;
                    check(control)?;
                    let mut lease = BitmapLease(Some(bitmap.clone()));
                    let result = if kind == AttachmentKind::Avatar {
                        avatar_from_bitmap(&bitmap)?
                    } else {
                        image_from_bitmap(&bitmap)?
                    };
                    lease.release().map_err(|_| "图片工作位图释放未确认")?;
                    check(control)?;
                    Ok::<_, String>(result)
                })();
                let _ = fs::remove_file(workspace.join(&path));
                let (base64, preview, jpeg) = decoded?;
                let image_path = PathBuf::from(if kind == AttachmentKind::Avatar {
                    format!("inbox/{code}-{index}-avatar.png")
                } else {
                    format!("inbox/{code}-{index}-image.jpg")
                });
                check(control)?;
                publish_bytes(&workspace, &image_path, &jpeg)?;
                output.push(draft(
                    code,
                    index,
                    name,
                    image_path,
                    jpeg.len() as u64,
                    hash(&jpeg),
                    if kind == AttachmentKind::Avatar {
                        "image/png"
                    } else {
                        "image/jpeg"
                    }
                    .into(),
                    (kind != AttachmentKind::Avatar).then_some(base64),
                    Some(Arc::new(preview)),
                ));
            } else {
                output.push(draft(
                    code, index, name, path, size, digest, mime, None, None,
                ));
            }
        }
        check(control)?;
        Ok(output)
    }
    fn draft(
        code: i32,
        index: usize,
        display_name: String,
        relative_path: PathBuf,
        bytes: u64,
        sha256: String,
        mime_type: String,
        image_base64: Option<String>,
        preview: Option<Arc<PixelFrame>>,
    ) -> AttachmentDraft {
        AttachmentDraft {
            id: format!("attachment-{code}-{index}"),
            display_name,
            relative_path,
            sha256,
            bytes,
            mime_type,
            image_base64,
            preview,
        }
    }
    fn uri_metadata(uri: &Object, control: &Arc<Control>) -> Result<(String, String), String> {
        let context = android::application_context()?;
        android::invoke(|env| {
            let resolver = env
                .call_method(
                    context.as_ref(),
                    jni_str!("getContentResolver"),
                    jni_sig!("()Landroid/content/ContentResolver;"),
                    &[],
                )?
                .l()?;
            let mime = env
                .call_method(
                    &resolver,
                    jni_str!("getType"),
                    jni_sig!("(Landroid/net/Uri;)Ljava/lang/String;"),
                    &[JValue::Object(uri.as_ref())],
                )?
                .l()?;
            let mime = if mime.is_null() {
                "application/octet-stream".into()
            } else {
                android::string_value(env, mime, 256)?
            };
            let signal = env.new_object(
                jni_str!("android/os/CancellationSignal"),
                jni_sig!("()V"),
                &[],
            )?;
            *control.signal.lock().unwrap_or_else(|e| e.into_inner()) =
                Some(Arc::new(env.new_global_ref(&signal)?));
            if control.cancelled.load(Ordering::Acquire) {
                return Err(jni::errors::Error::NullPtr("cancelled uri metadata"));
            }
            let projection =
                env.new_object_array(1, jni_str!("java/lang/String"), JObject::null())?;
            let column = env.new_string("_display_name")?;
            env.set_object_array_element(&projection, 0, &column)?;
            let cursor=env.call_method(&resolver,jni_str!("query"),jni_sig!("(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/database/Cursor;"),&[JValue::Object(uri.as_ref()),JValue::Object(&projection),JValue::Object(&JObject::null()),JValue::Object(&JObject::null()),JValue::Object(&JObject::null()),JValue::Object(&signal)])?.l()?;
            let mut name = "文件".to_string();
            if !cursor.is_null() {
                let result = (|| -> jni::errors::Result<()> {
                    let column = env.new_string("_display_name")?;
                    let index = env
                        .call_method(
                            &cursor,
                            jni_str!("getColumnIndex"),
                            jni_sig!("(Ljava/lang/String;)I"),
                            &[JValue::Object(&column)],
                        )?
                        .i()?;
                    if index >= 0
                        && env
                            .call_method(&cursor, jni_str!("moveToFirst"), jni_sig!("()Z"), &[])?
                            .z()?
                    {
                        let value = env
                            .call_method(
                                &cursor,
                                jni_str!("getString"),
                                jni_sig!("(I)Ljava/lang/String;"),
                                &[JValue::Int(index)],
                            )?
                            .l()?;
                        if !value.is_null() {
                            name = android::string_value(env, value, 1024)?;
                        }
                    }
                    Ok(())
                })();
                if env.exception_check() {
                    env.exception_clear();
                }
                let close = env.call_method(&cursor, jni_str!("close"), jni_sig!("()V"), &[]);
                result?;
                close?;
            }
            Ok((name, mime))
        })
    }
    fn copy_uri(
        workspace: &Path,
        path: &Path,
        uri: &Object,
        maximum: u64,
        control: &Arc<Control>,
    ) -> Result<(u64, String), String> {
        let context = android::application_context()?;
        let (mut input, declared) = android::invoke(|env| {
            let signal = env.new_object(
                jni_str!("android/os/CancellationSignal"),
                jni_sig!("()V"),
                &[],
            )?;
            *control.signal.lock().unwrap_or_else(|e| e.into_inner()) =
                Some(Arc::new(env.new_global_ref(&signal)?));
            if control.cancelled.load(Ordering::Acquire) {
                return Err(jni::errors::Error::NullPtr("cancelled uri request"));
            }
            let resolver = env
                .call_method(
                    context.as_ref(),
                    jni_str!("getContentResolver"),
                    jni_sig!("()Landroid/content/ContentResolver;"),
                    &[],
                )?
                .l()?;
            let mode = env.new_string("r")?;
            let asset=env.call_method(resolver,jni_str!("openAssetFileDescriptor"),jni_sig!("(Landroid/net/Uri;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/content/res/AssetFileDescriptor;"),&[JValue::Object(uri.as_ref()),JValue::Object(&mode),JValue::Object(&signal)])?.l()?;
            if asset.is_null() {
                return Err(jni::errors::Error::NullPtr("uri asset"));
            }
            let result = (|| -> jni::errors::Result<(fs::File, i64)> {
                let pfd = env
                    .call_method(
                        &asset,
                        jni_str!("getParcelFileDescriptor"),
                        jni_sig!("()Landroid/os/ParcelFileDescriptor;"),
                        &[],
                    )?
                    .l()?;
                let fd = env
                    .call_method(pfd, jni_str!("getFd"), jni_sig!("()I"), &[])?
                    .i()?;
                let offset = env
                    .call_method(&asset, jni_str!("getStartOffset"), jni_sig!("()J"), &[])?
                    .j()?;
                let declared = env
                    .call_method(&asset, jni_str!("getDeclaredLength"), jni_sig!("()J"), &[])?
                    .j()?;
                let duplicate = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 3) };
                if duplicate < 0 {
                    return Err(jni::errors::Error::NullPtr("uri fd duplication"));
                }
                let mut file = unsafe { fs::File::from_raw_fd(duplicate) };
                if offset < 0 || declared < -1 || declared > maximum as i64 {
                    return Err(jni::errors::Error::NullPtr("uri length budget"));
                }
                if offset > 0 {
                    file.seek(SeekFrom::Start(offset as u64))
                        .map_err(|_| jni::errors::Error::NullPtr("uri offset"))?;
                }
                let flags = unsafe { libc::fcntl(duplicate, libc::F_GETFL) };
                if flags < 0
                    || unsafe { libc::fcntl(duplicate, libc::F_SETFL, flags | libc::O_NONBLOCK) }
                        < 0
                {
                    return Err(jni::errors::Error::NullPtr("uri nonblocking"));
                }
                Ok((file, declared))
            })();
            if env.exception_check() {
                env.exception_clear();
            }
            let close = env.call_method(&asset, jni_str!("close"), jni_sig!("()V"), &[]);
            let result = result?;
            close?;
            Ok(result)
        })?;
        let temporary = workspace.join(path).with_extension("importing");
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&temporary)
            .map_err(|_| "无法创建附件暂存文件")?;
        let started = Instant::now();
        let mut length = 0u64;
        let mut digest = sha2::Sha256::new();
        use sha2::Digest;
        let result = (|| -> Result<(), String> {
            let mut buffer = [0u8; 32 * 1024];
            loop {
                check(control)?;
                if started.elapsed() > Duration::from_secs(60) {
                    return Err("附件读取超过60秒，未返回截断文件".into());
                }
                if declared >= 0 && length == declared as u64 {
                    break;
                }
                let remaining = if declared >= 0 {
                    (declared as u64 - length).min(buffer.len() as u64) as usize
                } else {
                    buffer.len()
                };
                match input.read(&mut buffer[..remaining]) {
                    Ok(0) => {
                        if declared >= 0 && length != declared as u64 {
                            return Err("附件短于提供方声明长度".into());
                        }
                        break;
                    }
                    Ok(read) => {
                        length = length.checked_add(read as u64).ok_or("附件长度溢出")?;
                        if length > maximum {
                            return Err("附件超过读取预算，未返回截断内容".into());
                        }
                        output
                            .write_all(&buffer[..read])
                            .map_err(|_| "附件暂存写入失败")?;
                        digest.update(&buffer[..read]);
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        let mut descriptor = libc::pollfd {
                            fd: input.as_raw_fd(),
                            events: libc::POLLIN,
                            revents: 0,
                        };
                        let result = unsafe { libc::poll(&mut descriptor, 1, 10) };
                        if result < 0
                            && std::io::Error::last_os_error().kind()
                                != std::io::ErrorKind::Interrupted
                        {
                            return Err("附件描述符等待失败".into());
                        }
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::Interrupted => {}
                    Err(_) => return Err("附件提供方读取失败".into()),
                }
            }
            output.sync_all().map_err(|_| "附件文件同步失败".into())
        })();
        *control.signal.lock().unwrap_or_else(|e| e.into_inner()) = None;
        result?;
        check(control)?;
        fs::hard_link(&temporary, workspace.join(path))
            .map_err(|_| "附件目标已存在或无法发布，未覆盖已有文件")?;
        fs::remove_file(&temporary).map_err(|_| "附件已发布但临时文件清理失败")?;
        fs::File::open(workspace.join("inbox"))
            .and_then(|directory| directory.sync_all())
            .map_err(|_| "附件已发布但目录同步未确认")?;
        Ok((length, format!("{:x}", digest.finalize())))
    }
    fn workspace_root(workspace: &Path) -> Result<PathBuf, String> {
        let metadata = fs::symlink_metadata(workspace).map_err(|_| "附件工作区不存在")?;
        if !metadata.is_dir() || metadata.file_type().is_symlink() {
            return Err("附件工作区类型无效".into());
        }
        let root = fs::canonicalize(workspace).map_err(|_| "无法解析附件工作区")?;
        let inbox = root.join("inbox");
        match fs::create_dir(&inbox) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(_) => return Err("无法创建附件目录".into()),
        }
        let metadata = fs::symlink_metadata(&inbox).map_err(|_| "无法复核附件目录")?;
        if !metadata.is_dir()
            || metadata.file_type().is_symlink()
            || fs::canonicalize(&inbox).map_err(|_| "附件目录解析失败")? != inbox
        {
            return Err("附件目录包含路径跳转".into());
        }
        Ok(root)
    }
    fn publish_bytes(root: &Path, path: &Path, bytes: &[u8]) -> Result<(), String> {
        let temporary = root.join(path).with_extension("importing");
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&temporary)
            .map_err(|_| "图片暂存失败")?;
        file.write_all(bytes)
            .and_then(|_| file.sync_all())
            .map_err(|_| "图片同步失败")?;
        fs::hard_link(&temporary, root.join(path)).map_err(|_| "图片目标已存在，未覆盖")?;
        fs::remove_file(temporary).map_err(|_| "图片已发布但暂存清理失败")?;
        fs::File::open(root.join("inbox"))
            .and_then(|d| d.sync_all())
            .map_err(|_| "图片目录同步未确认".to_string())
    }
    fn check(control: &Control) -> Result<(), String> {
        if control.cancelled.load(Ordering::Acquire) {
            Err("附件操作已取消".into())
        } else {
            Ok(())
        }
    }
    fn hash(bytes: &[u8]) -> String {
        use sha2::Digest;
        format!("{:x}", sha2::Sha256::digest(bytes))
    }
    fn safe_name(name: &str) -> String {
        let name: String = name
            .chars()
            .filter(|c| {
                !c.is_control()
                    && !matches!(c, '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|')
            })
            .take(80)
            .collect();
        if name.is_empty() || name == "." || name == ".." {
            "file".into()
        } else {
            name
        }
    }
    pub(super) fn decode_base64(base64: &str, max_side: u32) -> Result<PixelFrame, String> {
        if base64.len() > MAX_BASE64 {
            return Err("图片Base64超过16 MiB预算".into());
        }
        let bitmap = android::invoke(|env| {
            let encoded = env.new_string(base64)?;
            let bytes = env
                .call_static_method(
                    jni_str!("android/util/Base64"),
                    jni_str!("decode"),
                    jni_sig!("(Ljava/lang/String;I)[B"),
                    &[JValue::Object(&encoded), JValue::Int(0)],
                )?
                .l()?;
            let bytes = env.cast_local::<JByteArray>(bytes)?;
            let length = env.get_array_length(&bytes)?;
            if length < 0 || length as usize > MAX_IMAGE_COMPRESSED {
                return Err(jni::errors::Error::NullPtr("compressed image budget"));
            }
            decode_array(env, &bytes, length, max_side)
        })?;
        let mut lease = BitmapLease(Some(bitmap.clone()));
        let pixels = pixels(&bitmap)?;
        lease.release().map_err(|_| "图片预览临时位图释放未确认")?;
        Ok(pixels)
    }
    pub(super) fn normalize_capture(
        bytes: &[u8],
        expected: Option<(u32, u32)>,
        max_width: u32,
        quality: u8,
    ) -> Result<CaptureImage, String> {
        let (bitmap, width, height) = android::invoke(|env| {
            let array = env.byte_array_from_slice(bytes)?;
            let options = env.new_object(
                jni_str!("android/graphics/BitmapFactory$Options"),
                jni_sig!("()V"),
                &[],
            )?;
            env.set_field(
                &options,
                jni_str!("inJustDecodeBounds"),
                jni_sig!("Z"),
                JValue::Bool(true),
            )?;
            env.call_static_method(
                jni_str!("android/graphics/BitmapFactory"),
                jni_str!("decodeByteArray"),
                jni_sig!("([BIILandroid/graphics/BitmapFactory$Options;)Landroid/graphics/Bitmap;"),
                &[
                    JValue::Object(&array),
                    JValue::Int(0),
                    JValue::Int(bytes.len() as i32),
                    JValue::Object(&options),
                ],
            )?;
            let width = env
                .get_field(&options, jni_str!("outWidth"), jni_sig!("I"))?
                .i()?;
            let height = env
                .get_field(&options, jni_str!("outHeight"), jni_sig!("I"))?
                .i()?;
            if width <= 0
                || height <= 0
                || width as u64 * height as u64 > 16_777_216
                || expected.is_some_and(|p| p != (width as u32, height as u32))
            {
                return Err(jni::errors::Error::NullPtr(
                    "capture source dimensions changed or budget exceeded",
                ));
            }
            let bitmap = decode_array(env, &array, bytes.len() as i32, 2160)?;
            Ok((bitmap, width as u32, height as u32))
        })?;
        let mut lease = BitmapLease(Some(bitmap.clone()));
        let (jpeg, out_width, out_height) = encode_bitmap(&bitmap, max_width, 32768, quality)?;
        lease.release().map_err(|_| "截屏解码位图释放未确认")?;
        Ok(CaptureImage {
            jpeg,
            width: out_width,
            height: out_height,
            source_width: width,
            source_height: height,
        })
    }
    fn decode_bytes_global(bytes: &[u8], max_side: u32) -> Result<Object, String> {
        if bytes.len() as u64 > MAX_IMAGE_SOURCE {
            return Err("图片原文件超过64 MiB预算".into());
        }
        android::invoke(|env| {
            let array = env.byte_array_from_slice(bytes)?;
            decode_array(env, &array, bytes.len() as i32, max_side)
        })
    }
    fn decode_array(
        env: &mut Env,
        array: &JByteArray,
        length: i32,
        max_side: u32,
    ) -> jni::errors::Result<Object> {
        let options = env.new_object(
            jni_str!("android/graphics/BitmapFactory$Options"),
            jni_sig!("()V"),
            &[],
        )?;
        env.set_field(
            &options,
            jni_str!("inJustDecodeBounds"),
            jni_sig!("Z"),
            JValue::Bool(true),
        )?;
        let args = [
            JValue::Object(array),
            JValue::Int(0),
            JValue::Int(length),
            JValue::Object(&options),
        ];
        env.call_static_method(
            jni_str!("android/graphics/BitmapFactory"),
            jni_str!("decodeByteArray"),
            jni_sig!("([BIILandroid/graphics/BitmapFactory$Options;)Landroid/graphics/Bitmap;"),
            &args,
        )?;
        let width = env
            .get_field(&options, jni_str!("outWidth"), jni_sig!("I"))?
            .i()?;
        let height = env
            .get_field(&options, jni_str!("outHeight"), jni_sig!("I"))?
            .i()?;
        if width <= 0 || height <= 0 {
            return Err(jni::errors::Error::NullPtr("invalid image dimensions"));
        }
        let maximum = max_side.clamp(64, 2160) as u64;
        let mut sample = 1i32;
        while (width as u64).div_ceil(sample as u64) > maximum
            || (height as u64).div_ceil(sample as u64) > maximum
            || (width as u64).div_ceil(sample as u64) * (height as u64).div_ceil(sample as u64)
                > MAX_PIXELS
        {
            sample = sample
                .checked_mul(2)
                .ok_or(jni::errors::Error::NullPtr("image sample overflow"))?;
        }
        env.set_field(
            &options,
            jni_str!("inJustDecodeBounds"),
            jni_sig!("Z"),
            JValue::Bool(false),
        )?;
        env.set_field(
            &options,
            jni_str!("inSampleSize"),
            jni_sig!("I"),
            JValue::Int(sample),
        )?;
        let bitmap = env
            .call_static_method(
                jni_str!("android/graphics/BitmapFactory"),
                jni_str!("decodeByteArray"),
                jni_sig!("([BIILandroid/graphics/BitmapFactory$Options;)Landroid/graphics/Bitmap;"),
                &args,
            )?
            .l()?;
        if bitmap.is_null() {
            return Err(jni::errors::Error::NullPtr("image decode failed"));
        }
        Ok(Arc::new(env.new_global_ref(bitmap)?))
    }
    fn pixels(bitmap: &Object) -> Result<PixelFrame, String> {
        android::invoke(|env| {
            let width = env
                .call_method(bitmap.as_ref(), jni_str!("getWidth"), jni_sig!("()I"), &[])?
                .i()?;
            let height = env
                .call_method(bitmap.as_ref(), jni_str!("getHeight"), jni_sig!("()I"), &[])?
                .i()?;
            if width <= 0 || height <= 0 || width as u64 * height as u64 > MAX_PIXELS {
                return Err(jni::errors::Error::NullPtr("decoded image pixel budget"));
            }
            let count = width as usize * height as usize;
            let array = env.new_int_array(count)?;
            env.call_method(
                bitmap.as_ref(),
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
            let mut argb = vec![0i32; count];
            env.get_int_array_region(&array, 0, &mut argb)?;
            let mut rgba = Vec::with_capacity(count * 4);
            for value in argb {
                let value = value as u32;
                rgba.extend_from_slice(&[
                    (value >> 16) as u8,
                    (value >> 8) as u8,
                    value as u8,
                    (value >> 24) as u8,
                ]);
            }
            Ok(PixelFrame {
                width: width as u32,
                height: height as u32,
                rgba,
            })
        })
    }
    fn avatar_from_bitmap(bitmap: &Object) -> Result<(String, PixelFrame, Vec<u8>), String> {
        let preview = pixels(bitmap)?;
        if preview.width > 1080 || preview.height > 1080 {
            return Err("头像工作像素超过1080px预算".into());
        }
        let mut bytes = Vec::new();
        {
            let mut encoder = png::Encoder::new(&mut bytes, preview.width, preview.height);
            encoder.set_color(png::ColorType::Rgba);
            encoder.set_depth(png::BitDepth::Eight);
            let mut writer = encoder.write_header().map_err(|_| "头像工作PNG编码失败")?;
            writer
                .write_image_data(&preview.rgba)
                .map_err(|_| "头像工作PNG写入失败")?;
        }
        Ok((String::new(), preview, bytes))
    }
    fn image_from_bitmap(bitmap: &Object) -> Result<(String, PixelFrame, Vec<u8>), String> {
        let (bytes, _, _) = encode_bitmap(bitmap, 1280, 1280, 82)?;
        let base64 = android::invoke(|env| {
            let bytes = env.byte_array_from_slice(&bytes)?;
            let encoded = env
                .call_static_method(
                    jni_str!("android/util/Base64"),
                    jni_str!("encodeToString"),
                    jni_sig!("([BI)Ljava/lang/String;"),
                    &[JValue::Object(&bytes), JValue::Int(2)],
                )?
                .l()?;
            android::string_value(env, encoded, MAX_BASE64)
        })?;
        let thumbnail = decode_bytes_global(&bytes, 320)?;
        let mut lease = BitmapLease(Some(thumbnail.clone()));
        let preview = pixels(&thumbnail)?;
        lease
            .release()
            .map_err(|_| "图片缩略图临时位图释放未确认")?;
        Ok((base64, preview, bytes))
    }
    pub(super) fn encode_bitmap(
        bitmap: &Object,
        max_width: u32,
        max_height: u32,
        quality: u8,
    ) -> Result<(Vec<u8>, u32, u32), String> {
        if !(1..=100).contains(&quality)
            || max_width == 0
            || max_width > 32_768
            || max_height == 0
            || max_height > 32_768
        {
            return Err("图片编码尺寸或质量参数无效".into());
        }
        let (scaled, owns_scaled) = android::invoke(|env| {
            let width = env
                .call_method(bitmap.as_ref(), jni_str!("getWidth"), jni_sig!("()I"), &[])?
                .i()?;
            let height = env
                .call_method(bitmap.as_ref(), jni_str!("getHeight"), jni_sig!("()I"), &[])?
                .i()?;
            if width <= 0 || height <= 0 || width as u64 * height as u64 > 16_777_216 {
                return Err(jni::errors::Error::NullPtr("camera dimensions"));
            }
            if width as u32 <= max_width
                && height as u32 <= max_height
                && width as u64 * height as u64 <= MAX_PIXELS
            {
                return Ok((bitmap.clone(), false));
            }
            let scale = (max_width as f64 / width as f64)
                .min(max_height as f64 / height as f64)
                .min((MAX_PIXELS as f64 / (width as f64 * height as f64)).sqrt())
                .min(1.);
            let width = (width as f64 * scale).floor().max(1.) as i32;
            let height = (height as f64 * scale).floor().max(1.) as i32;
            let result = env
                .call_static_method(
                    jni_str!("android/graphics/Bitmap"),
                    jni_str!("createScaledBitmap"),
                    jni_sig!("(Landroid/graphics/Bitmap;IIZ)Landroid/graphics/Bitmap;"),
                    &[
                        JValue::Object(bitmap.as_ref()),
                        JValue::Int(width),
                        JValue::Int(height),
                        JValue::Bool(true),
                    ],
                )?
                .l()?;
            let owns = !env.is_same_object(bitmap.as_ref(), &result)?;
            Ok((Arc::new(env.new_global_ref(result)?), owns))
        })?;
        let mut lease = BitmapLease(owns_scaled.then(|| scaled.clone()));
        let bytes = android::invoke(|env| {
            let output = env.new_object(
                jni_str!("java/io/ByteArrayOutputStream"),
                jni_sig!("()V"),
                &[],
            )?;
            let format = env
                .get_static_field(
                    jni_str!("android/graphics/Bitmap$CompressFormat"),
                    jni_str!("JPEG"),
                    jni_sig!("Landroid/graphics/Bitmap$CompressFormat;"),
                )?
                .l()?;
            if !env
                .call_method(
                    scaled.as_ref(),
                    jni_str!("compress"),
                    jni_sig!("(Landroid/graphics/Bitmap$CompressFormat;ILjava/io/OutputStream;)Z"),
                    &[
                        JValue::Object(&format),
                        JValue::Int(quality as i32),
                        JValue::Object(&output),
                    ],
                )?
                .z()?
            {
                return Err(jni::errors::Error::NullPtr("JPEG compression"));
            }
            let length = env
                .call_method(&output, jni_str!("size"), jni_sig!("()I"), &[])?
                .i()?;
            if length < 0 || length as usize > MAX_IMAGE_COMPRESSED {
                return Err(jni::errors::Error::NullPtr("JPEG budget"));
            }
            let bytes = env
                .call_method(output, jni_str!("toByteArray"), jni_sig!("()[B"), &[])?
                .l()?;
            let bytes = env.cast_local::<JByteArray>(bytes)?;
            env.convert_byte_array(bytes)
        })?;
        let (width, height) = android::invoke(|env| {
            Ok((
                env.call_method(scaled.as_ref(), jni_str!("getWidth"), jni_sig!("()I"), &[])?
                    .i()? as u32,
                env.call_method(scaled.as_ref(), jni_str!("getHeight"), jni_sig!("()I"), &[])?
                    .i()? as u32,
            ))
        })?;
        lease.release().map_err(|_| "图片编码临时位图释放未确认")?;
        Ok((bytes, width, height))
    }
}
