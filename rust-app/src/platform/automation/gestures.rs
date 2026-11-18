use super::accessibility::{self, Object};
use super::*;
use crate::platform::android;
use jni::{JValue, jni_sig, jni_str};
use std::{
    sync::atomic::{AtomicU64, Ordering},
    sync::{
        Condvar, Mutex, OnceLock,
        mpsc::{self, SyncSender},
    },
    time::{Duration, Instant},
};

#[derive(Clone, Copy, PartialEq, Eq)]
enum Phase {
    Reserved,
    Launching,
    Accepted,
}
struct Pending {
    handle: u64,
    target: Target,
    phase: Phase,
    cancel: Arc<CancelToken>,
    sender: SyncSender<Result<(), String>>,
    deadline: Instant,
    timed_out: bool,
}
#[derive(Default)]
struct Registry {
    pending: Option<Pending>,
}
static REGISTRY: OnceLock<(Mutex<Registry>, Condvar)> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
static START: Mutex<bool> = Mutex::new(false);
fn registry() -> &'static (Mutex<Registry>, Condvar) {
    REGISTRY.get_or_init(|| (Mutex::new(Registry::default()), Condvar::new()))
}
fn start_watchdog() -> Result<(), String> {
    let mut started = START.lock().unwrap_or_else(|e| e.into_inner());
    if *started {
        return Ok(());
    }
    std::thread::Builder::new().name("rust-gesture-watchdog".into()).spawn(||{let(lock,wake)=registry();let mut state=lock.lock().unwrap_or_else(|e|e.into_inner());loop{
    let Some(pending)=state.pending.as_mut().filter(|p|!p.timed_out)else{state=wake.wait(state).unwrap_or_else(|e|e.into_inner());continue;};
    let remaining=pending.deadline.saturating_duration_since(Instant::now());if !remaining.is_zero(){state=wake.wait_timeout(state,remaining).unwrap_or_else(|e|e.into_inner()).0;continue;}
    pending.timed_out=true;let _=pending.sender.try_send(Err("手势终态超时，未确认触摸是否已完成；已接受的手势保留占用直到系统回调或服务结束".into()));
    if pending.phase==Phase::Reserved{state.pending=None;}
}}).map_err(|_|"手势终态管理线程无法启动")?;
    *started = true;
    Ok(())
}

pub(super) fn perform(
    operation: &OperationKey,
    target: &Target,
    points: &[(f32, f32)],
    duration_ms: u64,
    cancel: &Arc<CancelToken>,
) -> Result<(), String> {
    operation.validate()?;
    if points.is_empty()
        || points.len() > 2
        || !(30..=5000).contains(&duration_ms)
        || points.iter().any(|(x, y)| {
            !x.is_finite()
                || !y.is_finite()
                || *x < 0.
                || *y < 0.
                || *x >= target.width as f32
                || *y >= target.height as f32
        })
    {
        return Err("手势坐标或时长无效，未派发输入".into());
    }
    let service = accessibility::verify(target)?;
    if cancel.is_cancelled() {
        return Err("手势已经取消，未派发输入".into());
    }
    start_watchdog()?;
    let handle = NEXT
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |v| {
            v.checked_add(1).filter(|next| *next <= i64::MAX as u64)
        })
        .map_err(|_| "手势代号已耗尽")?;
    let (tx, rx) = mpsc::sync_channel(1);
    {
        let (lock, wake) = registry();
        let mut state = lock.lock().unwrap_or_else(|e| e.into_inner());
        if state.pending.is_some() {
            return Err("上一手势尚未收到系统终态，未并行注入触摸".into());
        }
        state.pending = Some(Pending {
            handle,
            target: target.clone(),
            phase: Phase::Reserved,
            cancel: cancel.clone(),
            sender: tx,
            deadline: Instant::now() + Duration::from_millis(duration_ms + 2000),
            timed_out: false,
        });
        wake.notify_one();
    }
    if cancel.is_cancelled() {
        finish(handle, Err("手势在派发前已取消".into()));
    } else {
        let allowed = {
            let (lock, _) = registry();
            let mut state = lock.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(pending) = state
                .pending
                .as_mut()
                .filter(|p| p.handle == handle && !p.timed_out && !p.cancel.is_cancelled())
            {
                pending.phase = Phase::Launching;
                true
            } else {
                false
            }
        };
        if allowed {
            super::overlay::flash(
                operation.clone(),
                target.clone(),
                points.to_vec(),
                duration_ms,
            );
            let dispatched = dispatch(&service, handle, target, points, duration_ms);
            match dispatched {
                Ok(true) => {
                    let (lock, _) = registry();
                    if let Some(pending) = lock
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .pending
                        .as_mut()
                        .filter(|p| p.handle == handle)
                    {
                        pending.phase = Phase::Accepted;
                    }
                }
                Ok(false) => finish(handle, Err("系统拒绝当前手势，未确认完成".into())),
                Err(error) => finish(handle, Err(error)),
            }
        }
    }
    loop {
        if cancel.is_cancelled() {
            return Err("手势等待已经取消；已派发的触摸仍由系统收尾，未提前释放输入占用".into());
        }
        match rx.recv_timeout(Duration::from_millis(10)) {
            Ok(result) => {
                result?;
                accessibility::verify(target)?;
                return Ok(());
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(_) => return Err("手势结果通道已经结束，完成状态未确认".into()),
        }
    }
}
fn dispatch(
    service: &Object,
    handle: u64,
    target: &Target,
    points: &[(f32, f32)],
    duration: u64,
) -> Result<bool, String> {
    accessibility::verify(target)?;
    android::invoke(|env| {
        let path = env.new_object(jni_str!("android/graphics/Path"), jni_sig!("()V"), &[])?;
        env.call_method(
            &path,
            jni_str!("moveTo"),
            jni_sig!("(FF)V"),
            &[JValue::Float(points[0].0), JValue::Float(points[0].1)],
        )?;
        if points.len() > 1 {
            env.call_method(
                &path,
                jni_str!("lineTo"),
                jni_sig!("(FF)V"),
                &[JValue::Float(points[1].0), JValue::Float(points[1].1)],
            )?;
        }
        let stroke = env.new_object(
            jni_str!("android/accessibilityservice/GestureDescription$StrokeDescription"),
            jni_sig!("(Landroid/graphics/Path;JJ)V"),
            &[
                JValue::Object(&path),
                JValue::Long(0),
                JValue::Long(duration as i64),
            ],
        )?;
        let builder = env.new_object(
            jni_str!("android/accessibilityservice/GestureDescription$Builder"),
            jni_sig!("()V"),
            &[],
        )?;
        env.call_method(&builder,jni_str!("addStroke"),jni_sig!("(Landroid/accessibilityservice/GestureDescription$StrokeDescription;)Landroid/accessibilityservice/GestureDescription$Builder;"),&[JValue::Object(&stroke)])?;
        if target.display_id != 0 {
            env.call_method(
                &builder,
                jni_str!("setDisplayId"),
                jni_sig!("(I)Landroid/accessibilityservice/GestureDescription$Builder;"),
                &[JValue::Int(target.display_id)],
            )?;
        }
        let description = env
            .call_method(
                builder,
                jni_str!("build"),
                jni_sig!("()Landroid/accessibilityservice/GestureDescription;"),
                &[],
            )?
            .l()?;
        let class = android::generated_class("NativeGestureResult")
            .map_err(|_| jni::errors::Error::NullPtr("gesture callback class"))?;
        let callback = env.new_object(
            class.as_ref(),
            jni_sig!("(J)V"),
            &[JValue::Long(handle as i64)],
        )?;
        let handler = android::main_handler()
            .map_err(|_| jni::errors::Error::NullPtr("gesture callback handler"))?;
        env.call_method(service.as_ref(),jni_str!("dispatchGesture"),jni_sig!("(Landroid/accessibilityservice/GestureDescription;Landroid/accessibilityservice/AccessibilityService$GestureResultCallback;Landroid/os/Handler;)Z"),&[JValue::Object(&description),JValue::Object(&callback),JValue::Object(handler.as_ref())])?.z()
    })
}
fn finish(handle: u64, result: Result<(), String>) {
    let (lock, wake) = registry();
    let pending = {
        let mut state = lock.lock().unwrap_or_else(|e| e.into_inner());
        if state.pending.as_ref().is_some_and(|p| p.handle == handle) {
            state.pending.take()
        } else {
            None
        }
    };
    if let Some(pending) = pending {
        let result = if pending.cancel.is_cancelled() {
            Err("手势已取消，系统终态已接回".into())
        } else {
            result
        };
        let _ = pending.sender.try_send(result);
    }
    wake.notify_one();
}
pub(crate) fn on_result(handle: u64, completed: bool) {
    finish(
        handle,
        if completed {
            Ok(())
        } else {
            Err("系统取消了手势，未报告完成".into())
        },
    );
}
pub(super) fn disconnect(generation: u64) {
    let handle = {
        let (lock, _) = registry();
        lock.lock()
            .unwrap_or_else(|e| e.into_inner())
            .pending
            .as_ref()
            .filter(|p| p.target.service_generation == generation)
            .map(|p| p.handle)
    };
    if let Some(handle) = handle {
        finish(handle, Err("无障碍服务已结束，手势完成状态未确认".into()));
    }
}
