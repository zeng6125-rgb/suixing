use super::{Command, Control, Phase, Shared, storage::RawLog};
#[cfg(target_os = "android")]
use crate::vt::{Input, VtEngine, encode_input};
use crate::{shell_environment::LaunchPlan, state::Event, vt::Geometry};
use std::{
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc::{Receiver, TryRecvError},
    },
    time::{Duration, Instant},
};
#[cfg(target_os = "android")]
use suixing_search::rust_terminal::{PtySession, ReadOutcome};

#[cfg(target_os = "android")]
fn write(
    pty: &PtySession,
    control: &Control,
    bytes: &[u8],
    authority: Option<(&super::Caller, u64)>,
    scope_cancel: Option<&AtomicBool>,
) -> Result<(), String> {
    if control.cancelled.load(Ordering::Acquire) {
        return Err("终端已请求停止，未执行输入".into());
    }
    if scope_cancel.is_some_and(|flag| flag.load(Ordering::Acquire)) {
        return Err("所属任务已取消或结束，终端输入未执行".into());
    }
    let token = pty
        .begin_write()
        .map_err(|error| format!("终端写入失败：{error}"))?;
    *control.write.lock().unwrap_or_else(|e| e.into_inner()) =
        Some((token.clone(), authority.is_some()));
    struct Registered<'a>(&'a Control);
    impl Drop for Registered<'_> {
        fn drop(&mut self) {
            self.0
                .write
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .take();
        }
    }
    let _registered = Registered(control);
    if control.cancelled.load(Ordering::Acquire) {
        token.cancel();
    }
    if authority.is_some_and(|(caller, revision)| !control.authorized(caller, Some(revision))) {
        token.cancel();
    }
    if scope_cancel.is_some_and(|flag| flag.load(Ordering::Acquire)) {
        token.cancel();
    }
    let count = match scope_cancel {
        Some(flag) => token.write_scoped(bytes, flag),
        None => token.write(bytes),
    }
    .map_err(|error| format!("终端输入失败：{error}；可能已有部分字节提交，未自动重发"))?;
    if count != bytes.len() {
        return Err("终端只写入部分字节，未自动重发".into());
    }
    Ok(())
}
#[cfg(target_os = "android")]
fn frame(shared: &Shared, control: &Control, engine: &VtEngine) {
    let frame = engine.frame();
    *control.frame.lock().unwrap_or_else(|e| e.into_inner()) = Some(frame.clone());
    shared.frame(&control.key, frame);
}
#[cfg(target_os = "android")]
fn persist_log(shared: &Shared, id: &str, log: &mut RawLog) -> Result<(), String> {
    log.sync()?;
    shared.update(id, |info| info.log = log.info.clone())?;
    let released = log.committed();
    shared.raw_reserved.fetch_sub(released, Ordering::AcqRel);
    Ok(())
}
#[cfg(target_os = "android")]
pub(super) fn run(
    shared: &Arc<Shared>,
    control: &Arc<Control>,
    id: &str,
    plan: LaunchPlan,
    geometry: Geometry,
    rx: Receiver<Command>,
) {
    if control.cancelled.load(Ordering::Acquire) || shared.stopped.load(Ordering::Acquire) {
        shared.finish(
            id,
            &control.key,
            None,
            Some("创建已取消，终端命令未启动".into()),
        );
        return;
    }
    let mut engine = match VtEngine::new(control.key.session_id, geometry) {
        Ok(engine) => engine,
        Err(error) => {
            shared.finish(id, &control.key, None, Some(error));
            return;
        }
    };
    let mut argv = vec![plan.executable.to_string_lossy().into_owned()];
    argv.extend(plan.arguments);
    let environment: Vec<_> = plan
        .environment
        .iter()
        .map(|(name, value)| format!("{name}={value}"))
        .collect();
    let foreground = match crate::platform::terminal_service::await_ready(&control.key, || {
        control.cancelled.load(Ordering::Acquire) || shared.stopped.load(Ordering::Acquire)
    }) {
        Ok(guard) => guard,
        Err(error) => {
            shared.finish(id, &control.key, None, Some(error));
            return;
        }
    };
    if !foreground.confirmed()
        || control.cancelled.load(Ordering::Acquire)
        || shared.stopped.load(Ordering::Acquire)
    {
        shared.finish(
            id,
            &control.key,
            None,
            Some("终端前台保护或创建身份已失效，PTY未启动".into()),
        );
        return;
    }
    let mut pty = match PtySession::spawn_argv(
        &plan.cwd,
        &argv,
        &environment,
        geometry.columns,
        geometry.rows,
    ) {
        Ok(pty) => pty,
        Err(error) => {
            shared.finish(
                id,
                &control.key,
                None,
                Some(format!("终端启动失败：{error}")),
            );
            return;
        }
    };
    let mut execution_lease =
        crate::shell_environment::lease::ExecutionLease::new(plan.root_use.clone());
    if let Err(error) = shared.update(id, |info| {
        info.phase = Phase::Running;
        info.started = true;
    }) {
        let exit = pty.stop().ok();
        if exit.is_some() {
            execution_lease.confirmed();
        }
        shared.finish(id, &control.key, exit, Some(error));
        return;
    }
    if !foreground.confirmed()
        || control.cancelled.load(Ordering::Acquire)
        || shared.stopped.load(Ordering::Acquire)
    {
        let exit = pty.stop().ok();
        if exit.is_some() {
            execution_lease.confirmed();
        }
        shared.finish(
            id,
            &control.key,
            exit,
            Some("终端在启动边界失去前台保护或被取消；已请求清理，不重放进程".into()),
        );
        return;
    }
    let info = shared
        .state
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .records
        .get(id)
        .expect("registered record")
        .log
        .clone();
    let mut log = RawLog::new(shared.directory.clone(), id.into(), info);
    let mut dirty = true;
    let mut last_frame = Instant::now() - Duration::from_millis(32);
    let mut last_log = Instant::now();
    let mut failure = None;
    'actor: loop {
        if !foreground.confirmed()
            || control.cancelled.load(Ordering::Acquire)
            || shared.stopped.load(Ordering::Acquire)
        {
            let _ = shared.update(id, |info| info.phase = Phase::Stopping);
            break;
        }
        for _ in 0..16 {
            match rx.try_recv() {
                Ok(Command::Input {
                    input,
                    caller,
                    revision,
                    scope_cancel,
                }) => {
                    if scope_cancel
                        .as_ref()
                        .is_some_and(|flag| flag.load(Ordering::Acquire))
                    {
                        (shared.emit)(Event::Error(
                            "所属任务已取消或结束，排队终端输入未执行".into(),
                        ));
                        continue;
                    }
                    if caller
                        .as_ref()
                        .is_some_and(|caller| !control.authorized(caller, Some(revision)))
                    {
                        (shared.emit)(Event::Error("终端输入授权已变化，排队输入未执行".into()));
                        continue;
                    }
                    if matches!(input, Input::Clear) {
                        engine.clear();
                        control
                            .output
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .clear();
                        dirty = true;
                    } else {
                        match encode_input(&input, engine.modes()).and_then(|bytes| {
                            write(
                                &pty,
                                control,
                                &bytes,
                                caller.as_ref().map(|caller| (caller, revision)),
                                scope_cancel.as_deref(),
                            )
                        }) {
                            Ok(()) => (),
                            Err(error) => (shared.emit)(Event::Error(error)),
                        }
                    }
                }
                Ok(Command::Resize(geometry)) => {
                    let previous = engine.clone();
                    let result = engine.resize(geometry).and_then(|()| {
                        pty.resize(geometry.columns, geometry.rows)
                            .map_err(|error| format!("终端窗口调整失败：{error}"))
                    });
                    if let Err(error) = result {
                        engine = previous;
                        (shared.emit)(Event::TerminalResizeRejected {
                            session_id: control.key.session_id,
                            geometry_version: geometry.version,
                            error,
                        });
                    }
                    dirty = true;
                }
                Ok(Command::Logging(enabled)) => {
                    if let Err(error) = log
                        .enabled(enabled)
                        .and_then(|()| persist_log(shared, id, &mut log))
                    {
                        log.info.enabled = false;
                        log.info.error = Some(error.clone());
                        let _ = shared.update(id, |info| info.log = log.info.clone());
                        (shared.emit)(Event::Error(error));
                    }
                }
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => break 'actor,
            }
            if control.cancelled.load(Ordering::Acquire) {
                break 'actor;
            }
        }
        match pty.read(super::MAX_INPUT_BYTES, 20) {
            Ok(ReadOutcome::Bytes(bytes)) => {
                control
                    .output
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .append(&bytes, false);
                let log_result = if log.info.enabled {
                    shared
                        .raw_reserved
                        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |used| {
                            used.checked_add(bytes.len() as u64)
                                .filter(|sum| *sum <= 18 * 1024 * 1024)
                        })
                        .map_err(|_| "原始终端日志达到全部18 MiB预算，已停止记录".to_string())
                        .and_then(|_| log.append(&bytes))
                } else {
                    Ok(())
                };
                if let Err(error) = log_result {
                    log.info.enabled = false;
                    log.info.error = Some(error.clone());
                    let _ = shared.update(id, |info| info.log = log.info.clone());
                    (shared.emit)(Event::Error(error));
                }
                let effects = engine.advance(&bytes);
                dirty |= effects.changed;
                if !effects.reply_bytes.is_empty()
                    && let Err(error) = write(&pty, control, &effects.reply_bytes, None, None)
                {
                    (shared.emit)(Event::Error(error));
                }
            }
            Ok(ReadOutcome::Timeout) => match pty.peek_exit() {
                Ok(true) => break,
                Ok(false) => (),
                Err(error) => {
                    failure = Some(format!("终端退出状态未确认：{error}"));
                    break;
                }
            },
            Ok(ReadOutcome::End) => break,
            Err(error) => {
                failure = Some(format!("终端读取失败：{error}"));
                break;
            }
        }
        if dirty && last_frame.elapsed() >= Duration::from_millis(32) {
            frame(shared, control, &engine);
            dirty = false;
            last_frame = Instant::now();
        }
        if last_log.elapsed() >= Duration::from_secs(1) {
            if let Err(error) = persist_log(shared, id, &mut log) {
                log.info.enabled = false;
                log.info.error = Some(error.clone());
                (shared.emit)(Event::Error(error));
            }
            last_log = Instant::now();
        }
    }
    engine.finish();
    control
        .output
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .append(&[], true);
    frame(shared, control, &engine);
    let exit = match pty.stop() {
        Ok(code) => {
            execution_lease.confirmed();
            Some(code)
        }
        Err(error) => {
            failure = Some(format!(
                "{}进程组清理未确认：{error}",
                failure
                    .as_deref()
                    .map_or(String::new(), |previous| format!("{previous}；"))
            ));
            None
        }
    };
    if let Err(error) = persist_log(shared, id, &mut log) {
        failure = Some(format!(
            "{}最终日志提交未确认：{error}",
            failure
                .as_deref()
                .map_or(String::new(), |previous| format!("{previous}；"))
        ));
    }
    shared.finish(id, &control.key, exit, failure);
}
#[cfg(not(target_os = "android"))]
pub(super) fn run(
    shared: &Arc<Shared>,
    control: &Arc<Control>,
    id: &str,
    _plan: LaunchPlan,
    _geometry: Geometry,
    _rx: Receiver<Command>,
) {
    shared.finish(
        id,
        &control.key,
        None,
        Some("当前平台未接入Android PTY".into()),
    );
}
