//! Coalesced actor-owned Activity flag updates; no window/JNI operation occurs
//! while drawing. A stale queued callback cannot change a replacement Activity.
#[derive(Default)]
pub(crate) struct WindowPolicy {
    #[cfg(target_os = "android")]
    last: Option<(usize, bool, bool)>,
    #[cfg(target_os = "android")]
    generation: std::sync::Arc<std::sync::atomic::AtomicU64>,
}
impl WindowPolicy {
    pub(crate) fn tick(&mut self, keep_screen_on: bool) -> Result<(), String> {
        #[cfg(not(target_os = "android"))]
        {
            let _ = keep_screen_on;
            Ok(())
        }
        #[cfg(target_os = "android")]
        {
            use super::android;
            use std::sync::{Arc, atomic::Ordering};
            let Some(activity) = android::current_activity() else {
                self.last = None;
                return Ok(());
            };
            let visible = android::activity_visible();
            let identity = Arc::as_ptr(&activity) as usize;
            let wanted = (identity, visible, keep_screen_on);
            if self.last == Some(wanted) {
                return Ok(());
            }
            self.last = Some(wanted);
            let generation = self
                .generation
                .fetch_update(Ordering::AcqRel, Ordering::Acquire, |value| {
                    value.checked_add(1)
                })
                .map_err(|_| "屏幕设置代次已耗尽")?
                + 1;
            let current = self.generation.clone();
            android::post_to_main(move || {
                if current.load(Ordering::Acquire) != generation
                    || !android::current_activity()
                        .is_some_and(|actual| Arc::ptr_eq(&actual, &activity))
                {
                    return;
                }
                let _ = android::invoke(|env| {
                    use jni::{JValue, jni_sig, jni_str};
                    let window = env
                        .call_method(
                            activity.as_ref(),
                            jni_str!("getWindow"),
                            jni_sig!("()Landroid/view/Window;"),
                            &[],
                        )?
                        .l()?;
                    // The original MainActivity uses the actual KEEP_SCREEN_ON
                    // window flag (128); it is not a CPU/background WakeLock.
                    env.call_method(
                        &window,
                        if keep_screen_on && android::activity_visible() {
                            jni_str!("addFlags")
                        } else {
                            jni_str!("clearFlags")
                        },
                        jni_sig!("(I)V"),
                        &[JValue::Int(128)],
                    )?;
                    Ok(())
                });
            })
        }
    }
}
