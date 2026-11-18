use super::*;
use crate::{
    platform::scheduler::{self, RearmReason},
    store::FirePhase,
};

pub(super) fn arm_schedules(
    store: &mut Store,
    publish: &Publisher,
    reason: Option<RearmReason>,
) -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        let request = store.rearm_schedules(reason)?;
        if let Err(error) = scheduler::request_arm(request.clone()) {
            store.record_alarm_result(scheduler::AlarmResult {
                generation: request.generation,
                outcome: Err(error.clone()),
            })?;
            publish.emit(Event::ScheduleStatus(store.schedule_status()));
            return Err(format!("计划已经保存，系统排程未确认：{error}"));
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = reason;
    }
    publish.emit(Event::Schedules(store.schedules().to_vec()));
    publish.emit(Event::ScheduleStatus(store.schedule_status()));
    Ok(())
}

#[cfg(target_os = "android")]
pub(super) fn scheduler_tick(
    store: &mut Store,
    runs: &mut Runs,
    publish: &Publisher,
    tx: &SyncSender<WorkerEvent>,
    mcp: Option<&McpRuntime>,
) {
    use scheduler::SchedulerEvent;
    let mut events = scheduler::drain_events(32);
    // A delivered fire claims the same-time batch before any foreground/missed sweep.
    events.sort_by_key(|e| {
        if matches!(e, SchedulerEvent::FireReceived { .. }) {
            0
        } else {
            1
        }
    });
    for event in events {
        let result: Result<(), String> = (|| {
            match event {
                SchedulerEvent::RearmRequested { reason, completion } => {
                    let result = arm_schedules(store, publish, Some(reason));
                    if let Some(completion) = completion {
                        completion.finish()?;
                    }
                    result?;
                }
                SchedulerEvent::FireReceived {
                    identity,
                    completion,
                } => {
                    let claim = store.claim_schedule_batch(&identity);
                    completion.finish()?;
                    match claim {
                        Ok(batch) => {
                            for record in batch {
                                if let Err(error) =
                                    scheduler::request_execution(record.identity.clone())
                                {
                                    store.finish_schedule_fire(
                                        &record.identity,
                                        FirePhase::Deferred,
                                        Some(error),
                                    )?;
                                }
                            }
                            arm_schedules(store, publish, None)?;
                        }
                        Err(error) => return Err(error),
                    }
                }
                SchedulerEvent::AlarmFinished(result) => {
                    store.record_alarm_result(result)?;
                }
                SchedulerEvent::Ready {
                    identity,
                    user_initiated,
                } => {
                    if store
                        .schedule_execution()
                        .fires
                        .iter()
                        .any(|r| r.identity == identity && r.phase == FirePhase::Running)
                    {
                        return Ok(());
                    }
                    let record = match store.prepare_schedule_run(&identity, user_initiated) {
                        Ok(record) => record,
                        Err(error) => {
                            let _ = scheduler::execution_finished(&identity);
                            return Err(error);
                        }
                    };
                    if runs.by_id.len() >= 8 {
                        store.finish_schedule_fire(
                            &identity,
                            FirePhase::Deferred,
                            Some("同时运行会话已达到8个，请稍后恢复提醒".into()),
                        )?;
                        return Ok(());
                    }
                    let mut active = None;
                    let result = start_run(
                        record.conversation_id,
                        record.fired.prompt,
                        RunMode::Schedule(identity.clone()),
                        store,
                        publish,
                        tx,
                        &mut active,
                        mcp,
                    );
                    if let Some(run) = active {
                        runs.insert(run);
                    }
                    if let Err(error) = result {
                        store.finish_schedule_fire(
                            &identity,
                            FirePhase::Failed,
                            Some(error.clone()),
                        )?;
                        scheduler::execution_finished(&identity)?;
                        arm_schedules(store, publish, None)?;
                        return Err(format!("提醒已领取，但任务启动失败：{error}"));
                    }
                }
                SchedulerEvent::Deferred { identity, error } => {
                    store.finish_schedule_fire(&identity, FirePhase::Deferred, Some(error))?;
                }
                SchedulerEvent::CancelRequested(identity) => {
                    let record = store
                        .schedule_execution()
                        .fires
                        .iter()
                        .find(|r| r.identity == identity)
                        .cloned();
                    if let Some(record) = record {
                        if let Some(run) =
                            record.run_id.as_ref().and_then(|id| runs.by_id.get_mut(id))
                        {
                            run.cancel.cancel();
                            run.refresh_approvals(publish);
                        } else {
                            store.finish_schedule_fire(
                                &identity,
                                FirePhase::Cancelled,
                                Some("用户取消提醒".into()),
                            )?;
                            scheduler::execution_finished(&identity)?;
                            arm_schedules(store, publish, None)?;
                        }
                    }
                }
                SchedulerEvent::ForegroundLost { identities, error } => {
                    for identity in identities {
                        if let Some(record) = store
                            .schedule_execution()
                            .fires
                            .iter()
                            .find(|r| r.identity == identity)
                        {
                            if let Some(run) =
                                record.run_id.as_ref().and_then(|id| runs.by_id.get_mut(id))
                            {
                                run.cancel.cancel();
                                run.refresh_approvals(publish);
                            } else if !record.phase.terminal() {
                                store.finish_schedule_fire(
                                    &identity,
                                    FirePhase::Deferred,
                                    Some(error.clone()),
                                )?;
                            }
                        }
                    }
                    return Err(error);
                }
                SchedulerEvent::NotificationRestricted => {
                    publish.emit(Event::Info("提醒通知权限受限，请在系统设置允许通知".into()))
                }
                SchedulerEvent::Failed(error) => return Err(error),
            }
            Ok(())
        })();
        if let Err(error) = result {
            publish.emit(Event::Error(error));
        }
        publish.emit(Event::ScheduleStatus(store.schedule_status()));
    }
}
