//! One operation-bound main-thread effect, invoked only by a bounded worker.
//! Timeout/cancel before admission prevent effects; after admission callers get
//! an honest uncertain result, never an instruction to repeat automatically.
use crate::{network::CancelToken, platform::permissions::OperationKey};
use std::sync::Arc;
#[cfg(target_os = "android")]
pub(crate) fn run<T: Send + 'static>(
    operation: &OperationKey,
    cancel: Arc<CancelToken>,
    effect: impl FnOnce() -> Result<T, String> + Send + 'static,
) -> Result<T, String> {
    use std::{
        sync::{
            atomic::{AtomicU8, Ordering},
            mpsc,
        },
        time::{Duration, Instant},
    };
    operation.validate()?;
    let (sender, receiver) = mpsc::sync_channel(1);
    let phase = Arc::new(AtomicU8::new(0));
    let admitted = phase.clone();
    let callback_cancel = cancel.clone();
    let deadline = Instant::now() + Duration::from_secs(10);
    crate::platform::android::post_to_main(move || {
        if admitted
            .compare_exchange(0, 1, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            return;
        }
        let result = if callback_cancel.is_cancelled() || Instant::now() >= deadline {
            Err("平台操作在主线程派发前已取消或超时".into())
        } else {
            std::panic::catch_unwind(std::panic::AssertUnwindSafe(effect))
                .unwrap_or_else(|_| Err("平台操作异常；已开始的操作可能已有作用".into()))
        };
        admitted.store(2, Ordering::Release);
        let _ = sender.try_send(result);
    })?;
    loop {
        match receiver.recv_timeout(Duration::from_millis(10)) {
            Ok(result) => return result,
            Err(mpsc::RecvTimeoutError::Disconnected) => return Err("平台操作结果未确认".into()),
            Err(mpsc::RecvTimeoutError::Timeout) => {}
        }
        if cancel.is_cancelled() || Instant::now() >= deadline {
            let queued = phase
                .compare_exchange(0, 3, Ordering::AcqRel, Ordering::Acquire)
                .is_ok();
            return Err(if queued {
                "平台操作已取消或排队超时，未派发"
            } else {
                "平台操作已开始但结果尚未确认；可能已有作用，请核对状态，勿自动重复"
            }
            .into());
        }
    }
}
