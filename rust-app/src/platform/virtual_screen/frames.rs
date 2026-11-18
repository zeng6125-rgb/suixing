use super::{
    display::{self, Object},
    *,
};
use crate::platform::android;
use jni::{
    Env, JValue, jni_sig, jni_str,
    objects::{JByteBuffer, JObject, JObjectArray},
};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
static CAPTURES: AtomicUsize = AtomicUsize::new(0);

pub(crate) fn on_image(
    env: &mut Env,
    generation: u64,
    callback_reader: &JObject,
) -> jni::errors::Result<()> {
    let (identity, reader) = {
        let state = display::registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let Some(screen) = state
            .screen
            .as_ref()
            .filter(|s| !s.closing && s.identity.generation == generation)
        else {
            // Listener removal and ImageReader.close own buffers after disconnect.
            return Ok(());
        };
        (screen.identity.clone(), screen.reader.clone())
    };
    let reader_guard = reader.lock().unwrap_or_else(|e| e.into_inner());
    let Some(reader) = reader_guard.as_ref() else {
        return Ok(());
    };
    if !env.is_same_object(reader.as_ref(), callback_reader)? {
        return Ok(());
    }
    let image = env
        .call_method(
            reader.as_ref(),
            jni_str!("acquireLatestImage"),
            jni_sig!("()Landroid/media/Image;"),
            &[],
        )?
        .l()?;
    if image.is_null() {
        return Ok(());
    }
    let copied = copy_image(env, &image, &identity);
    if env.exception_check() {
        env.exception_clear();
    }
    let closed = env.call_method(&image, jni_str!("close"), jni_sig!("()V"), &[]);
    drop(reader_guard);
    let pixels = copied?;
    closed?;
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |n| n.as_millis().min(u64::MAX as u128) as u64);
    let pixels = Arc::new(pixels);
    let preview = {
        let mut state = display::registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let Some(screen) = state
            .screen
            .as_mut()
            .filter(|s| !s.closing && s.identity == identity)
        else {
            return Ok(());
        };
        screen.frame = Some(pixels.clone());
        screen.last_frame_at_ms = Some(now);
        if screen.preview_requested && screen.last_preview.elapsed() >= Duration::from_millis(160) {
            let preview = Arc::new(scale_preview(&pixels));
            screen.preview = Some(preview.clone());
            screen.last_preview = Instant::now();
            Some(preview)
        } else {
            None
        }
    };
    if let Some(pixels) = preview {
        if let Err(message) = super::preview::publish(&identity, pixels) {
            display::error(message);
        }
    }
    Ok(())
}
fn copy_image(
    env: &mut Env,
    image: &JObject,
    identity: &Identity,
) -> jni::errors::Result<PixelFrame> {
    let width = env
        .call_method(image, jni_str!("getWidth"), jni_sig!("()I"), &[])?
        .i()?;
    let height = env
        .call_method(image, jni_str!("getHeight"), jni_sig!("()I"), &[])?
        .i()?;
    let format = env
        .call_method(image, jni_str!("getFormat"), jni_sig!("()I"), &[])?
        .i()?;
    if width <= 0
        || height <= 0
        || width as u32 != identity.width
        || height as u32 != identity.height
        || width as u64 * height as u64 > 4_194_304
        || format != 1
    {
        return Err(jni::errors::Error::NullPtr(
            "virtual frame dimensions or format changed",
        ));
    }
    let crop = env
        .call_method(
            image,
            jni_str!("getCropRect"),
            jni_sig!("()Landroid/graphics/Rect;"),
            &[],
        )?
        .l()?;
    for (name, expected) in [
        ("left", 0),
        ("top", 0),
        ("right", width),
        ("bottom", height),
    ] {
        let value = env
            .get_field(&crop, jni::strings::JNIString::new(name), jni_sig!("I"))?
            .i()?;
        if value != expected {
            return Err(jni::errors::Error::NullPtr("virtual frame crop changed"));
        }
    }
    let planes = env
        .call_method(
            image,
            jni_str!("getPlanes"),
            jni_sig!("()[Landroid/media/Image$Plane;"),
            &[],
        )?
        .l()?;
    let planes = env.cast_local::<JObjectArray>(planes)?;
    if env.get_array_length(&planes)? != 1 {
        return Err(jni::errors::Error::NullPtr("virtual RGBA plane count"));
    }
    let plane = env.get_object_array_element(&planes, 0)?;
    let pixel_stride = env
        .call_method(&plane, jni_str!("getPixelStride"), jni_sig!("()I"), &[])?
        .i()?;
    let row_stride = env
        .call_method(&plane, jni_str!("getRowStride"), jni_sig!("()I"), &[])?
        .i()?;
    if pixel_stride != 4 || row_stride < width * 4 || row_stride > 262_144 {
        return Err(jni::errors::Error::NullPtr("virtual RGBA stride budget"));
    }
    let buffer = env
        .call_method(
            plane,
            jni_str!("getBuffer"),
            jni_sig!("()Ljava/nio/ByteBuffer;"),
            &[],
        )?
        .l()?;
    let buffer = env.cast_local::<JByteBuffer>(buffer)?;
    let position = env
        .call_method(&buffer, jni_str!("position"), jni_sig!("()I"), &[])?
        .i()?;
    let limit = env
        .call_method(&buffer, jni_str!("limit"), jni_sig!("()I"), &[])?
        .i()?;
    let capacity = env.get_direct_buffer_capacity(&buffer)?;
    let address = env.get_direct_buffer_address(&buffer)?;
    let required = (height as usize - 1)
        .checked_mul(row_stride as usize)
        .and_then(|n| n.checked_add(width as usize * 4))
        .and_then(|n| n.checked_add(position.max(0) as usize))
        .ok_or(jni::errors::Error::NullPtr("virtual RGBA offset overflow"))?;
    if position < 0
        || limit < position
        || limit as usize > capacity
        || required > limit as usize
        || address.is_null()
        || capacity > 64 * 1024 * 1024
    {
        return Err(jni::errors::Error::NullPtr("virtual RGBA buffer bounds"));
    }
    // The Image remains acquired and ImageReader.close is excluded by the reader
    // lock. PixelFormat.RGBA_8888 defines channel order; rows omit only padding.
    let source = unsafe { std::slice::from_raw_parts(address, required) };
    let mut rgba = Vec::with_capacity(width as usize * height as usize * 4);
    for y in 0..height as usize {
        let offset = position as usize + y * row_stride as usize;
        rgba.extend_from_slice(&source[offset..offset + width as usize * 4]);
    }
    // Surface RGBA pixels are premultiplied; PixelFrame consumers receive
    // straight channels. No guessed R/B channel swaps are applied.
    for pixel in rgba.chunks_exact_mut(4) {
        let alpha = pixel[3] as u32;
        if alpha == 0 {
            pixel[..3].fill(0);
        } else if alpha < 255 {
            for channel in &mut pixel[..3] {
                *channel = ((*channel as u32 * 255 + alpha / 2) / alpha).min(255) as u8;
            }
        }
    }
    Ok(PixelFrame {
        width: width as u32,
        height: height as u32,
        rgba,
    })
}
pub(super) fn scale_preview(frame: &PixelFrame) -> PixelFrame {
    let height = frame.height.min(480);
    let width = (frame.width as u64 * height as u64 / frame.height as u64).max(1) as u32;
    let mut rgba = Vec::with_capacity(width as usize * height as usize * 4);
    for y in 0..height {
        for x in 0..width {
            let sx = x as u64 * frame.width as u64 / width as u64;
            let sy = y as u64 * frame.height as u64 / height as u64;
            let offset = (sy * frame.width as u64 + sx) as usize * 4;
            rgba.extend_from_slice(&frame.rgba[offset..offset + 4]);
        }
    }
    PixelFrame {
        width,
        height,
        rgba,
    }
}
pub fn latest_frame(identity: &Identity, preview: bool) -> Result<Frame, String> {
    display::verify(identity)?;
    let state = display::registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    let screen = state
        .screen
        .as_ref()
        .filter(|s| !s.closing && s.identity == *identity)
        .ok_or("虚拟屏已变更")?;
    let source = screen.frame.as_ref().ok_or("虚拟屏尚未收到可用画面")?;
    let pixels = if preview {
        screen
            .preview
            .clone()
            .unwrap_or_else(|| Arc::new(scale_preview(source)))
    } else {
        source.clone()
    };
    Ok(Frame {
        identity: identity.clone(),
        pixels,
        received_at_ms: screen.last_frame_at_ms.ok_or("虚拟屏没有帧时间")?,
    })
}
pub(super) fn bitmap(frame: &PixelFrame) -> Result<Object, String> {
    if frame.width == 0
        || frame.height == 0
        || frame.width as u64 * frame.height as u64 > 4_194_304
        || frame.rgba.len() != frame.width as usize * frame.height as usize * 4
    {
        return Err("虚拟屏位图像素预算无效".into());
    }
    let argb: Vec<i32> = frame
        .rgba
        .chunks_exact(4)
        .map(|p| {
            ((p[3] as u32) << 24 | (p[0] as u32) << 16 | (p[1] as u32) << 8 | p[2] as u32) as i32
        })
        .collect();
    android::invoke(|env| {
        let pixels = env.new_int_array(argb.len())?;
        env.set_int_array_region(&pixels, 0, &argb)?;
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
                    JValue::Object(&pixels),
                    JValue::Int(frame.width as i32),
                    JValue::Int(frame.height as i32),
                    JValue::Object(&config),
                ],
            )?
            .l()?;
        Ok(Arc::new(env.new_global_ref(bitmap)?))
    })
}
pub(super) fn recycle(bitmap: &Object) -> Result<(), String> {
    android::invoke(|env| {
        env.call_method(bitmap.as_ref(), jni_str!("recycle"), jni_sig!("()V"), &[])?;
        Ok(())
    })
}
pub fn screenshot(
    identity: &Identity,
    max_width: u32,
    quality: u8,
    cancel: &CancelToken,
) -> Result<Screenshot, String> {
    if CAPTURES
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| {
            (n < 2).then_some(n + 1)
        })
        .is_err()
    {
        return Err("副屏截图处理达到两个工作槽预算".into());
    }
    struct Permit;
    impl Drop for Permit {
        fn drop(&mut self) {
            CAPTURES.fetch_sub(1, Ordering::AcqRel);
        }
    }
    let _permit = Permit;
    if !(320..=2160).contains(&max_width) || !(1..=100).contains(&quality) {
        return Err("副屏截图尺寸或质量参数无效".into());
    }
    if cancel.is_cancelled() {
        return Err("副屏截图已取消".into());
    }
    let frame = latest_frame(identity, false)?;
    let bitmap = bitmap(&frame.pixels)?;
    struct Lease(Option<Object>);
    impl Drop for Lease {
        fn drop(&mut self) {
            if let Some(bitmap) = self.0.as_ref() {
                let _ = recycle(bitmap);
            }
        }
    }
    let mut lease = Lease(Some(bitmap.clone()));
    let (jpeg, width, height) =
        crate::platform::attachments::encode_android_bitmap(&bitmap, max_width, 32_768, quality)?;
    if cancel.is_cancelled() {
        return Err("副屏截图编码期间已取消，未发布图像".into());
    }
    display::verify(identity)?;
    recycle(&bitmap).map_err(|_| "副屏截图位图释放未确认")?;
    lease.0 = None;
    Ok(Screenshot {
        identity: identity.clone(),
        jpeg,
        width,
        height,
        received_at_ms: frame.received_at_ms,
    })
}
