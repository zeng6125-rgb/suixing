//! Choreographer timestamps belong to an expiring, process-owned page lease.
//! JNI callbacks update only a bounded numeric window. No storage or sampling IO.
use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};
static NEXT: AtomicU64 = AtomicU64::new(1);
static WINDOWS: OnceLock<Mutex<HashMap<u64, Arc<Mutex<Window>>>>> = OnceLock::new();
fn windows() -> &'static Mutex<HashMap<u64, Arc<Mutex<Window>>>> {
    WINDOWS.get_or_init(|| Mutex::new(HashMap::new()))
}
#[derive(Clone, Copy)]
pub struct FrameStat {
    pub fps: f64,
    pub p90_ms: f64,
}
#[derive(Default)]
struct Window {
    first: Option<i64>,
    last: Option<i64>,
    deltas: Vec<i64>,
    latest: Option<(Instant, FrameStat)>,
    visibility: Option<Arc<AtomicBool>>,
}
pub struct FrameLease {
    token: u64,
}
impl FrameLease {
    pub fn start() -> Result<Self, String> {
        Self::start_with_guard(Arc::new(AtomicBool::new(true)))
    }
    pub fn start_with_guard(visibility: Arc<AtomicBool>) -> Result<Self, String> {
        if !cfg!(target_os = "android") {
            return Err("当前平台没有Android帧回调".into());
        }
        let token = NEXT
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |value| {
                value.checked_add(1)
            })
            .map_err(|_| "帧采样身份用尽")?;
        {
            let mut values = windows().lock().unwrap_or_else(|error| error.into_inner());
            if values.len() >= 4 {
                return Err("帧采样窗口已满".into());
            }
            values.insert(
                token,
                Arc::new(Mutex::new(Window {
                    visibility: Some(visibility),
                    ..Default::default()
                })),
            );
        }
        #[cfg(target_os = "android")]
        if let Err(error) = crate::platform::android::post_to_main(move || {
            let _ = android::install(token);
        }) {
            windows()
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .remove(&token);
            return Err(error);
        }
        Ok(Self { token })
    }
    pub fn latest(&self) -> Option<FrameStat> {
        let value = windows()
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .get(&self.token)
            .cloned()?;
        let window = value.lock().unwrap_or_else(|error| error.into_inner());
        if window
            .visibility
            .as_ref()
            .is_some_and(|guard| !guard.load(Ordering::Acquire))
        {
            return None;
        }
        window
            .latest
            .filter(|(at, _)| at.elapsed() <= Duration::from_secs(3))
            .map(|(_, stat)| stat)
    }
}
impl Drop for FrameLease {
    fn drop(&mut self) {
        windows()
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .remove(&self.token);
        #[cfg(target_os = "android")]
        {
            if let Some(pair) = android::take(self.token) {
                let _ = crate::platform::android::post_to_main(move || {
                    let _ = android::remove_handles(pair);
                });
            }
        }
    }
}
fn sample(token: u64, time: i64) -> bool {
    if time <= 0 {
        return false;
    }
    let value = windows()
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .get(&token)
        .cloned();
    let Some(value) = value else {
        return false;
    };
    let mut window = value.lock().unwrap_or_else(|error| error.into_inner());
    if window
        .visibility
        .as_ref()
        .is_some_and(|guard| !guard.load(Ordering::Acquire))
    {
        return false;
    }
    if window.last.is_some_and(|last| time <= last) {
        return true;
    }
    if let Some(last) = window.last {
        if window.deltas.len() < 120 {
            window.deltas.push(time - last);
        }
    }
    window.first.get_or_insert(time);
    window.last = Some(time);
    if window
        .first
        .is_some_and(|first| time - first >= 1_000_000_000)
        || window.deltas.len() >= 120
    {
        if !window.deltas.is_empty() {
            let span = window
                .deltas
                .iter()
                .map(|value| *value as u128)
                .sum::<u128>();
            let fps = window.deltas.len() as f64 * 1_000_000_000.0 / span as f64;
            window.deltas.sort_unstable();
            let rank = (window.deltas.len() * 90).div_ceil(100).saturating_sub(1);
            let p90_ms = window.deltas[rank] as f64 / 1_000_000.0;
            window.latest = Some((Instant::now(), FrameStat { fps, p90_ms }));
        }
        window.first = Some(time);
        window.deltas.clear();
    }
    true
}
#[cfg(target_os = "android")]
pub mod android {
    use super::*;
    use jni::{Env, JValue, jni_sig, jni_str, objects::JObject, refs::Global};
    type Handles = (Arc<Global<JObject<'static>>>, Arc<Global<JObject<'static>>>);
    static HANDLES: OnceLock<Mutex<HashMap<u64, Handles>>> = OnceLock::new();
    fn handles() -> &'static Mutex<HashMap<u64, Handles>> {
        HANDLES.get_or_init(|| Mutex::new(HashMap::new()))
    }
    pub(super) fn take(token: u64) -> Option<Handles> {
        handles()
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .remove(&token)
    }
    pub fn install(token: u64) -> Result<(), String> {
        if !crate::platform::android::activity_visible() {
            return Err("帧采样页面当前不可见".into());
        }
        if !windows()
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .contains_key(&token)
        {
            return Ok(());
        }
        crate::platform::android::invoke(|env| {
            let class = crate::platform::android::generated_class("NativePerfFrameCallback")
                .map_err(|_| jni::errors::Error::NullPtr("perf callback class"))?;
            let callback = env.new_object(
                class.as_ref(),
                jni_sig!("(J)V"),
                &[JValue::Long(token as i64)],
            )?;
            let choreographer = env
                .call_static_method(
                    jni_str!("android/view/Choreographer"),
                    jni_str!("getInstance"),
                    jni_sig!("()Landroid/view/Choreographer;"),
                    &[],
                )?
                .l()?;
            let pair = (
                Arc::new(env.new_global_ref(choreographer)?),
                Arc::new(env.new_global_ref(callback)?),
            );
            handles()
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .insert(token, pair);
            post(env, token)
        })
    }
    fn post(env: &mut Env, token: u64) -> jni::errors::Result<()> {
        let pair = handles()
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .get(&token)
            .cloned();
        if let Some((choreographer, callback)) = pair {
            env.call_method(
                choreographer.as_ref(),
                jni_str!("postFrameCallback"),
                jni_sig!("(Landroid/view/Choreographer$FrameCallback;)V"),
                &[JValue::Object(callback.as_ref())],
            )?;
        }
        Ok(())
    }
    /// Root JNI wrapper forwards its existing Env; no nested VM attachment.
    pub fn on_java_frame(env: &mut Env, token: u64, time: i64) -> jni::errors::Result<()> {
        if !crate::platform::android::activity_visible() {
            return Ok(());
        }
        if sample(token, time) {
            post(env, token)?;
        }
        Ok(())
    }
    pub(super) fn remove_handles(pair: Handles) -> Result<(), String> {
        let (choreographer, callback) = pair;
        crate::platform::android::invoke(|env| {
            env.call_method(
                choreographer.as_ref(),
                jni_str!("removeFrameCallback"),
                jni_sig!("(Landroid/view/Choreographer$FrameCallback;)V"),
                &[JValue::Object(callback.as_ref())],
            )?;
            Ok(())
        })
    }
}
