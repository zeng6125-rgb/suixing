use super::accessibility::{self, Object};
use super::*;
use crate::platform::android;
use jni::{Env, JValue, jni_sig, jni_str, objects::JObject};
use std::{
    collections::BTreeMap,
    sync::{
        Mutex, OnceLock,
        atomic::{AtomicU64, AtomicUsize, Ordering},
        mpsc::{self, SyncSender},
    },
    time::{Duration, Instant},
};

struct Buffer {
    buffer: Option<Object>,
    color: Object,
}
impl Buffer {
    fn close(&mut self) -> Result<(), String> {
        let Some(buffer) = self.buffer.as_ref() else {
            return Ok(());
        };
        android::invoke(|env| {
            env.call_method(buffer.as_ref(), jni_str!("close"), jni_sig!("()V"), &[])?;
            Ok(())
        })?;
        self.buffer = None;
        Ok(())
    }
}
impl Drop for Buffer {
    fn drop(&mut self) {
        if self.close().is_err() {
            accessibility::error("截图HardwareBuffer释放未确认");
        }
    }
}
struct Pending {
    target: Target,
    cancel: Arc<CancelToken>,
    sender: SyncSender<Result<Buffer, i32>>,
}
static REGISTRY: OnceLock<Mutex<BTreeMap<u64, Pending>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
static ACTIVE: AtomicUsize = AtomicUsize::new(0);
fn registry() -> &'static Mutex<BTreeMap<u64, Pending>> {
    REGISTRY.get_or_init(|| Mutex::new(BTreeMap::new()))
}
pub(super) fn capture(
    target: &Target,
    max_width: u32,
    quality: u8,
    cancel: &Arc<CancelToken>,
) -> Result<Screenshot, String> {
    if !android::paths().is_some_and(|p| p.sdk_int >= 30) {
        return Err("当前Android版本没有无障碍截图API，请选择已明确的特权截图路线".into());
    }
    if !(320..=2160).contains(&max_width) || !(1..=100).contains(&quality) {
        return Err("截图尺寸或质量参数超出预算".into());
    }
    if ACTIVE
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| {
            (n < 2).then_some(n + 1)
        })
        .is_err()
    {
        return Err("截图处理已达到两个工作槽预算".into());
    }
    struct Lease;
    impl Drop for Lease {
        fn drop(&mut self) {
            ACTIVE.fetch_sub(1, Ordering::AcqRel);
        }
    }
    let _lease = Lease;
    let service = accessibility::verify(target)?;
    if cancel.is_cancelled() {
        return Err("截图已取消，未读取屏幕".into());
    }
    let handle = NEXT
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |v| {
            v.checked_add(1).filter(|next| *next <= i64::MAX as u64)
        })
        .map_err(|_| "截图回调代号已耗尽")?;
    let (tx, rx) = mpsc::sync_channel(1);
    registry().lock().unwrap_or_else(|e| e.into_inner()).insert(
        handle,
        Pending {
            target: target.clone(),
            cancel: cancel.clone(),
            sender: tx,
        },
    );
    struct PendingLease(u64);
    impl Drop for PendingLease {
        fn drop(&mut self) {
            registry()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .remove(&self.0);
        }
    }
    let _pending = PendingLease(handle);
    android::invoke(|env| {
        let class = android::generated_class("NativeScreenshotResult")
            .map_err(|_| jni::errors::Error::NullPtr("screenshot callback class"))?;
        let callback = env.new_object(
            class.as_ref(),
            jni_sig!("(J)V"),
            &[JValue::Long(handle as i64)],
        )?;
        let context = android::application_context()
            .map_err(|_| jni::errors::Error::NullPtr("screenshot context"))?;
        let executor = env
            .call_method(
                context.as_ref(),
                jni_str!("getMainExecutor"),
                jni_sig!("()Ljava/util/concurrent/Executor;"),
                &[],
            )?
            .l()?;
        env.call_method(service.as_ref(),jni_str!("takeScreenshot"),jni_sig!("(ILjava/util/concurrent/Executor;Landroid/accessibilityservice/AccessibilityService$TakeScreenshotCallback;)V"),&[JValue::Int(target.display_id),JValue::Object(&executor),JValue::Object(&callback)])?;
        Ok(())
    })?;
    let deadline = Instant::now() + Duration::from_secs(5);
    let mut buffer = loop {
        if cancel.is_cancelled() {
            return Err("截图等待已经取消，迟到图像仍由回调释放".into());
        }
        if Instant::now() >= deadline {
            return Err("无障碍截图回调超时，未返回旧图像".into());
        }
        match rx.recv_timeout(Duration::from_millis(10)) {
            Ok(Ok(buffer)) => break buffer,
            Ok(Err(code)) => return Err(format!("无障碍截图失败，系统错误码{code}")),
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(_) => return Err("无障碍截图结果通道已经结束".into()),
        }
    };
    let bitmap = android::invoke(|env| {
        let hardware = buffer
            .buffer
            .as_ref()
            .ok_or(jni::errors::Error::NullPtr("screenshot buffer released"))?;
        let width = env
            .call_method(
                hardware.as_ref(),
                jni_str!("getWidth"),
                jni_sig!("()I"),
                &[],
            )?
            .i()?;
        let height = env
            .call_method(
                hardware.as_ref(),
                jni_str!("getHeight"),
                jni_sig!("()I"),
                &[],
            )?
            .i()?;
        if width as u32 != target.width
            || height as u32 != target.height
            || width <= 0
            || height <= 0
            || width as u64 * height as u64 > 16_777_216
        {
            return Err(jni::errors::Error::NullPtr(
                "screenshot source changed or pixel budget exceeded",
            ));
        }
        let wrapped=env.call_static_method(jni_str!("android/graphics/Bitmap"),jni_str!("wrapHardwareBuffer"),jni_sig!("(Landroid/hardware/HardwareBuffer;Landroid/graphics/ColorSpace;)Landroid/graphics/Bitmap;"),&[JValue::Object(hardware.as_ref()),JValue::Object(buffer.color.as_ref())])?.l()?;
        if wrapped.is_null() {
            return Err(jni::errors::Error::NullPtr(
                "screenshot hardware bitmap missing",
            ));
        }
        let config = env
            .get_static_field(
                jni_str!("android/graphics/Bitmap$Config"),
                jni_str!("ARGB_8888"),
                jni_sig!("Landroid/graphics/Bitmap$Config;"),
            )?
            .l()?;
        let copied = env.call_method(
            &wrapped,
            jni_str!("copy"),
            jni_sig!("(Landroid/graphics/Bitmap$Config;Z)Landroid/graphics/Bitmap;"),
            &[JValue::Object(&config), JValue::Bool(false)],
        );
        if env.exception_check() {
            env.exception_clear();
        }
        let recycled = env.call_method(wrapped, jni_str!("recycle"), jni_sig!("()V"), &[]);
        let bitmap = copied?.l()?;
        recycled?;
        if bitmap.is_null() {
            return Err(jni::errors::Error::NullPtr(
                "screenshot software bitmap missing",
            ));
        }
        Ok(Arc::new(env.new_global_ref(bitmap)?))
    })?;
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
            if self.release().is_err() {
                accessibility::error("截图软件位图释放未确认");
            }
        }
    }
    let mut bitmap_lease = BitmapLease(Some(bitmap.clone()));
    buffer
        .close()
        .map_err(|_| "截图已取得但原HardwareBuffer释放未确认")?;
    if cancel.is_cancelled() {
        return Err("截图在编码前已取消".into());
    }
    let (jpeg, width, height) =
        crate::platform::attachments::encode_android_bitmap(&bitmap, max_width, 32_768, quality)?;
    if cancel.is_cancelled() {
        return Err("截图在编码后已取消，未发送图像".into());
    }
    accessibility::verify(target)?;
    bitmap_lease
        .release()
        .map_err(|_| "截图软件位图释放未确认，未发布图像")?;
    Ok(Screenshot {
        jpeg,
        width,
        height,
        target: target.clone(),
    })
}

pub(crate) fn on_result(
    env: &mut Env,
    handle: u64,
    result: &JObject,
    error: i32,
) -> jni::errors::Result<()> {
    let pending = registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .remove(&handle);
    if error != 0 || result.is_null() {
        if let Some(pending) = pending {
            let _ = pending
                .sender
                .try_send(Err(if error == 0 { -1 } else { error }));
        }
        return Ok(());
    }
    let hardware = env
        .call_method(
            result,
            jni_str!("getHardwareBuffer"),
            jni_sig!("()Landroid/hardware/HardwareBuffer;"),
            &[],
        )?
        .l()?;
    if hardware.is_null() {
        if let Some(pending) = pending {
            let _ = pending.sender.try_send(Err(-2));
        }
        return Ok(());
    }
    let adopted = (|| {
        let color = env
            .call_method(
                result,
                jni_str!("getColorSpace"),
                jni_sig!("()Landroid/graphics/ColorSpace;"),
                &[],
            )?
            .l()?;
        if color.is_null() {
            return Err(jni::errors::Error::NullPtr(
                "screenshot color space missing",
            ));
        }
        Ok(Buffer {
            buffer: Some(Arc::new(env.new_global_ref(&hardware)?)),
            color: Arc::new(env.new_global_ref(color)?),
        })
    })();
    let buffer = match adopted {
        Ok(buffer) => buffer,
        Err(error) => {
            if env.exception_check() {
                env.exception_clear();
            }
            let _ = env.call_method(hardware, jni_str!("close"), jni_sig!("()V"), &[]);
            if let Some(pending) = pending {
                let _ = pending.sender.try_send(Err(-3));
            }
            return Err(error);
        }
    };
    if let Some(pending) = pending {
        if pending.cancel.is_cancelled()
            || pending.target.service_generation != accessibility::capabilities().service_generation
        {
            let _ = pending.sender.try_send(Err(-4));
        } else {
            let _ = pending.sender.try_send(Ok(buffer));
            return Ok(());
        }
    }
    drop(buffer);
    Ok(())
}
pub(super) fn disconnect(generation: u64) {
    let removed = {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        let handles = state
            .iter()
            .filter(|(_, p)| p.target.service_generation == generation)
            .map(|(id, _)| *id)
            .collect::<Vec<_>>();
        handles
            .into_iter()
            .filter_map(|id| state.remove(&id))
            .collect::<Vec<_>>()
    };
    for pending in removed {
        let _ = pending.sender.try_send(Err(-5));
    }
}
