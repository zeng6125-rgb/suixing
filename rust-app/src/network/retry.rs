//! Typed attempt facts only. The caller must settle this attempt and preserve
//! its partial generation before requesting a retry; no tool is replayed here.
use super::CancelToken;
use crate::settings_policy::RetryPolicy;
use std::time::Duration;
#[derive(Clone, Copy, Debug)]
pub(crate) enum RetryAdvice {
    Transport,
    Cut,
    Http {
        status: u16,
        retry_after_seconds: Option<u32>,
    },
}
impl RetryAdvice {
    pub(crate) fn allows(self, policy: RetryPolicy, attempts: u32, cancelled: bool) -> bool {
        policy.enabled
            && !cancelled
            && attempts <= policy.max_attempts
            && match self {
                Self::Transport | Self::Cut => true,
                Self::Http { status, .. } => status == 429 || (500..=599).contains(&status),
            }
    }
    pub(crate) fn delay(self, policy: RetryPolicy) -> Duration {
        let seconds = match self {
            Self::Http {
                retry_after_seconds: Some(seconds),
                ..
            } => seconds,
            _ => policy.delay_seconds,
        };
        Duration::from_secs(seconds.clamp(1, 60) as u64)
    }
}
pub(crate) fn retry_after(value: Option<&str>) -> Option<u32> {
    value?
        .trim()
        .parse::<i32>()
        .ok()
        .filter(|seconds| *seconds >= 0)
        .map(|seconds| seconds.clamp(1, 60) as u32)
}
pub(crate) async fn wait(delay: Duration, parent: &CancelToken, request: &CancelToken) -> bool {
    if parent.is_cancelled() || request.is_cancelled() {
        return false;
    }
    tokio::select! { biased; _=parent.cancelled()=>false, _=request.cancelled()=>false, _=tokio::time::sleep(delay)=> !parent.is_cancelled() && !request.is_cancelled() }
}
