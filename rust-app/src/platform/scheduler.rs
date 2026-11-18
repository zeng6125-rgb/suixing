//! System alarms and reminder foreground leases. Durable claims and execution
//! stay with the runtime actor; a Ready event means foreground was confirmed.
use crate::{
    runtime_host::{RunKey, RuntimeHost},
    schedules::Plan,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::sync::Arc;

pub const SERVICE_CLASS: &str = "com.suixing.agent.rust.platform.RustSchedulerService";
pub const RECEIVER_CLASS: &str = "com.suixing.agent.rust.platform.RustScheduleReceiver";
pub const BOOT_RECEIVER_CLASS: &str = "com.suixing.agent.rust.platform.RustBootReceiver";
pub const ACTION_FIRE: &str = "com.suixing.agent.rust.action.SCHEDULE_FIRE";
pub const ACTION_EXECUTE: &str = "com.suixing.agent.rust.action.SCHEDULE_EXECUTE";
pub const ACTION_RESUME: &str = "com.suixing.agent.rust.action.SCHEDULE_RESUME";
pub const ACTION_CANCEL: &str = "com.suixing.agent.rust.action.SCHEDULE_CANCEL";
pub const ACTION_OPEN: &str = "com.suixing.agent.rust.action.SCHEDULE_OPEN";
pub const ALARM_URI: &str = "jagent-schedule://alarm/current";

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct AlarmRequest {
    pub generation: String,
    pub plan: Option<Plan>,
}
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum AlarmMode {
    Exact,
    Inexact,
}
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct AlarmResult {
    pub generation: String,
    pub outcome: Result<Option<AlarmMode>, String>,
}
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub struct FireIdentity {
    pub generation: String,
    pub fire_token: String,
    pub schedule_id: String,
    pub scheduled_at: i64,
}

pub(crate) fn token(value: &str) -> Result<(), String> {
    if value.is_empty()
        || value.len() > 128
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
    {
        Err("提醒令牌无效".into())
    } else {
        Ok(())
    }
}
impl FireIdentity {
    pub fn for_plan(generation: String, plan: &Plan) -> Result<Self, String> {
        let mut value = Self {
            generation,
            fire_token: String::new(),
            schedule_id: plan.schedule_id.clone(),
            scheduled_at: plan.at_millis,
        };
        value.fire_token = value.expected_token();
        value.validate()?;
        Ok(value)
    }
    fn expected_token(&self) -> String {
        let mut digest = Sha256::new();
        digest.update(self.generation.as_bytes());
        digest.update([0]);
        digest.update(self.schedule_id.as_bytes());
        digest.update([0]);
        digest.update(self.scheduled_at.to_be_bytes());
        format!("{:x}", digest.finalize())
    }
    pub fn validate(&self) -> Result<(), String> {
        token(&self.generation)?;
        token(&self.fire_token)?;
        if self.schedule_id.is_empty()
            || self.schedule_id.len() > 128
            || self.schedule_id.contains('\0')
            || self.scheduled_at <= 0
            || self.fire_token != self.expected_token()
        {
            return Err("提醒触发身份无效".into());
        }
        Ok(())
    }
    pub fn execution_uri(&self) -> String {
        format!("jagent-schedule://execute/{}", self.fire_token)
    }
    pub fn cancel_uri(&self) -> String {
        format!("jagent-schedule://cancel/{}", self.fire_token)
    }
    pub fn open_uri(&self) -> String {
        format!("jagent-schedule://open/{}", self.fire_token)
    }
}
impl AlarmRequest {
    pub fn validate(&self) -> Result<(), String> {
        token(&self.generation)?;
        if let Some(plan) = &self.plan {
            FireIdentity::for_plan(self.generation.clone(), plan)?;
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RearmReason {
    Foreground,
    Boot,
    ClockChanged,
    TimezoneChanged,
    PackageReplaced,
    ExactPermissionChanged,
}

#[cfg(target_os = "android")]
pub enum SchedulerEvent {
    RearmRequested {
        reason: RearmReason,
        completion: Option<crate::platform::android::ReceiverCompletion>,
    },
    FireReceived {
        identity: FireIdentity,
        completion: crate::platform::android::ReceiverCompletion,
    },
    AlarmFinished(AlarmResult),
    Ready {
        identity: FireIdentity,
        user_initiated: bool,
    },
    Deferred {
        identity: FireIdentity,
        error: String,
    },
    CancelRequested(FireIdentity),
    ForegroundLost {
        identities: Vec<FireIdentity>,
        error: String,
    },
    NotificationRestricted,
    Failed(String),
}
#[derive(Clone, Debug, Default)]
pub struct SchedulerStatus {
    pub initialized: bool,
    pub foreground: bool,
    pub notifications_allowed: Option<bool>,
    pub pending_fires: usize,
    pub alarm: Option<AlarmResult>,
    pub last_error: Option<String>,
}

#[cfg(target_os = "android")]
mod device;
#[cfg(target_os = "android")]
mod framework;
#[cfg(target_os = "android")]
pub use device::{
    bind_run, drain_events, execution_finished, handle_receiver, handle_service, request_arm,
    request_execution, request_rearm, request_resume, start, status,
};
#[cfg(not(target_os = "android"))]
pub fn start(_host: Arc<RuntimeHost>) -> Result<(), String> {
    Err("当前平台没有 Android 提醒服务".into())
}
