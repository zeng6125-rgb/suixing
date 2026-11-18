use super::*;
use crate::platform::android;
use jni::{JValue, jni_sig, jni_str, objects::JObject, refs::Global};
use std::sync::{
    Mutex, OnceLock,
    atomic::{AtomicU64, Ordering},
};
pub(super) type Object = Arc<Global<JObject<'static>>>;
#[derive(Default)]
struct Registry {
    service: Option<Object>,
    generation: u64,
    error: Option<String>,
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
}
pub fn capabilities() -> Capabilities {
    let state = registry().lock().unwrap_or_else(|e| e.into_inner());
    Capabilities {
        connected: state.service.is_some(),
        service_generation: state.generation,
        sdk_int: android::paths().map_or(0, |p| p.sdk_int),
        error: state.error.clone(),
    }
}
pub(super) fn error(message: &str) {
    registry().lock().unwrap_or_else(|e| e.into_inner()).error = Some(message.into());
}
pub fn on_service_event(instance: Object, kind: i32) -> Result<(), String> {
    match kind {
        1 => {
            let old = capabilities().service_generation;
            super::gestures::disconnect(old);
            super::screenshots::disconnect(old);
            if old > 0 {
                super::overlay::disconnect();
            }
            let generation = NEXT
                .fetch_update(Ordering::AcqRel, Ordering::Acquire, |v| {
                    v.checked_add(1).filter(|next| *next <= i64::MAX as u64)
                })
                .map_err(|_| "无障碍服务代号已耗尽")?;
            {
                let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
                state.service = Some(instance);
                state.generation = generation;
                state.error = None;
            }
            Ok(())
        }
        2 | 3 => {
            let current = match service(None) {
                Ok(service) => service,
                Err(_) => return Ok(()),
            };
            let same =
                android::invoke(|env| env.is_same_object(current.as_ref(), instance.as_ref()))?;
            if !same {
                return Ok(());
            }
            let generation = {
                let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
                state.service = None;
                state.error = Some("无障碍服务断开；当前操作未复用旧连接".into());
                state.generation
            };
            super::gestures::disconnect(generation);
            super::screenshots::disconnect(generation);
            super::overlay::disconnect();
            crate::platform::virtual_screen::on_accessibility_disconnect();
            Ok(())
        }
        4 => {
            let current = match service(None) {
                Ok(service) => service,
                Err(_) => return Ok(()),
            };
            if !android::invoke(|env| env.is_same_object(current.as_ref(), instance.as_ref()))? {
                return Ok(());
            }
            let generation = capabilities().service_generation;
            super::gestures::disconnect(generation);
            super::screenshots::disconnect(generation);
            Ok(())
        }
        _ => Ok(()),
    }
}
pub(crate) fn service(generation: Option<u64>) -> Result<Object, String> {
    let state = registry().lock().unwrap_or_else(|e| e.into_inner());
    if generation.is_some_and(|g| g != state.generation) {
        return Err("无障碍目标所属连接已更换".into());
    }
    state
        .service
        .clone()
        .ok_or("无障碍服务没有连接；请由用户在系统设置中启用".into())
}
pub fn target(display_id: i32) -> Result<Target, String> {
    if display_id < 0 {
        return Err("目标display_id不能为负".into());
    }
    let capabilities = capabilities();
    let owned = crate::platform::virtual_screen::status()
        .identity
        .filter(|i| i.display_id == display_id);
    if let Some(identity) = owned {
        crate::platform::virtual_screen::verify(&identity)?;
        return Ok(Target {
            display_id,
            service_generation: if capabilities.connected {
                capabilities.service_generation
            } else {
                0
            },
            width: identity.width,
            height: identity.height,
            display_generation: Some(identity.generation),
        });
    }
    let (width, height) = dimensions(display_id)?;
    Ok(Target {
        display_id,
        service_generation: if capabilities.connected {
            capabilities.service_generation
        } else {
            0
        },
        width,
        height,
        display_generation: None,
    })
}
pub(super) fn verify(target: &Target) -> Result<Object, String> {
    if target.display_id != 0 && !android::paths().is_some_and(|p| p.sdk_int >= 30) {
        return Err("当前Android版本不支持无障碍副屏定位或手势".into());
    }
    let service = service(Some(target.service_generation))?;
    verify_display(target)?;
    Ok(service)
}
pub(crate) fn verify_display(target: &Target) -> Result<(), String> {
    if target.width == 0
        || target.height == 0
        || dimensions(target.display_id) != (Ok((target.width, target.height)))
    {
        return Err("目标屏幕已消失或尺寸改变，请重新读取屏幕再准备操作".into());
    }
    if let Some(generation) = target.display_generation {
        let owned = crate::platform::virtual_screen::status()
            .identity
            .filter(|i| {
                i.display_id == target.display_id
                    && i.generation == generation
                    && i.width == target.width
                    && i.height == target.height
            })
            .ok_or("虚拟屏所属代次已经变更")?;
        crate::platform::virtual_screen::verify(&owned)?;
    } else if crate::platform::virtual_screen::status()
        .identity
        .is_some_and(|i| i.display_id == target.display_id)
    {
        return Err("自有虚拟屏操作缺少所属代次".into());
    }
    Ok(())
}
fn dimensions(display_id: i32) -> Result<(u32, u32), String> {
    android::invoke(|env| {
        let context = android::application_context()
            .map_err(|_| jni::errors::Error::NullPtr("display context"))?;
        let name = env.new_string("display")?;
        let manager = env
            .call_method(
                context.as_ref(),
                jni_str!("getSystemService"),
                jni_sig!("(Ljava/lang/String;)Ljava/lang/Object;"),
                &[JValue::Object(&name)],
            )?
            .l()?;
        let display = env
            .call_method(
                manager,
                jni_str!("getDisplay"),
                jni_sig!("(I)Landroid/view/Display;"),
                &[JValue::Int(display_id)],
            )?
            .l()?;
        if display.is_null() {
            return Err(jni::errors::Error::NullPtr("display not found"));
        }
        let point = env.new_object(jni_str!("android/graphics/Point"), jni_sig!("()V"), &[])?;
        env.call_method(
            display,
            jni_str!("getRealSize"),
            jni_sig!("(Landroid/graphics/Point;)V"),
            &[JValue::Object(&point)],
        )?;
        let width = env.get_field(&point, jni_str!("x"), jni_sig!("I"))?.i()?;
        let height = env.get_field(&point, jni_str!("y"), jni_sig!("I"))?.i()?;
        if width <= 0 || height <= 0 || width > 32_768 || height > 32_768 {
            return Err(jni::errors::Error::NullPtr("display dimension budget"));
        }
        Ok((width as u32, height as u32))
    })
}
pub(super) fn global(
    target: &Target,
    action: GlobalAction,
    cancel: &CancelToken,
) -> Result<(), String> {
    let service = verify(target)?;
    if cancel.is_cancelled() {
        return Err("全局按键已经取消".into());
    }
    let accepted = android::invoke(|env| {
        env.call_method(
            service.as_ref(),
            jni_str!("performGlobalAction"),
            jni_sig!("(I)Z"),
            &[JValue::Int(action.code())],
        )?
        .z()
    })?;
    if accepted {
        Ok(())
    } else {
        Err("系统没有接受全局按键".into())
    }
}
