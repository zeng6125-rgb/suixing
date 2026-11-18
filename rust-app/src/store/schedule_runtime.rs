//! Claims, alarm generations and run identity are committed with the latest board.
use super::*;
use crate::{
    platform::scheduler::{AlarmRequest, AlarmResult, FireIdentity, RearmReason},
    runtime_host::RunKey,
    schedules::{Plan, Schedule, SystemLocalClock},
};

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum FirePhase {
    Claimed,
    Deferred,
    Running,
    Completed,
    Cancelled,
    Interrupted,
    Failed,
}
impl FirePhase {
    pub fn terminal(self) -> bool {
        matches!(
            self,
            Self::Completed | Self::Cancelled | Self::Interrupted | Self::Failed
        )
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct FireRecord {
    pub identity: FireIdentity,
    pub fired: Schedule,
    pub phase: FirePhase,
    pub conversation_id: Option<String>,
    pub run_id: Option<String>,
    pub at: i64,
    pub error: Option<String>,
}
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct ScheduleExecution {
    pub alarm: Option<AlarmRequest>,
    pub alarm_result: Option<AlarmResult>,
    pub fires: Vec<FireRecord>,
}
#[derive(Clone, Debug, Serialize)]
pub struct FireSummary {
    pub identity: FireIdentity,
    pub title: String,
    pub phase: FirePhase,
    pub conversation_id: Option<String>,
    pub at: i64,
    pub error: Option<String>,
}
#[derive(Clone, Debug, Default, Serialize)]
pub struct ScheduleStatus {
    pub alarm: Option<AlarmRequest>,
    pub alarm_result: Option<AlarmResult>,
    pub fires: Vec<FireSummary>,
}
impl ScheduleExecution {
    pub(super) fn validate(&self) -> Result<(), String> {
        if self.fires.len() > 4096 {
            return Err("提醒执行记录超过4096条".into());
        }
        if let Some(alarm) = &self.alarm {
            alarm.validate()?;
        }
        let mut used = HashSet::new();
        for fire in &self.fires {
            fire.identity.validate()?;
            fire.fired.validate()?;
            if !used.insert(&fire.identity.fire_token)
                || fire.identity.schedule_id != fire.fired.id
                || fire.identity.scheduled_at != fire.fired.next_at
                || fire.at < 0
                || fire.error.as_ref().is_some_and(|v| v.len() > 8192)
                || (fire.phase == FirePhase::Running
                    && (fire.conversation_id.is_none() || fire.run_id.is_none()))
            {
                return Err("提醒执行记录身份或状态无效".into());
            }
            if let Some(id) = &fire.conversation_id {
                validate_id(id)?;
            }
            if let Some(id) = &fire.run_id {
                validate_id(id)?;
            }
        }
        Ok(())
    }
}
impl Store {
    pub fn schedule_execution(&self) -> &ScheduleExecution {
        &self.schedule_execution
    }
    pub fn schedule_status(&self) -> ScheduleStatus {
        ScheduleStatus {
            alarm: self.schedule_execution.alarm.clone(),
            alarm_result: self.schedule_execution.alarm_result.clone(),
            fires: self
                .schedule_execution
                .fires
                .iter()
                .rev()
                .take(128)
                .map(|r| FireSummary {
                    identity: r.identity.clone(),
                    title: r.fired.title.clone(),
                    phase: r.phase,
                    conversation_id: r.conversation_id.clone(),
                    at: r.at,
                    error: r.error.clone(),
                })
                .collect(),
        }
    }
    fn save_schedule_state(
        &mut self,
        schedules: Vec<Schedule>,
        execution: ScheduleExecution,
    ) -> Result<(), String> {
        self.writable()?;
        crate::schedules::validate_board(&schedules)?;
        execution.validate()?;
        let document = ScheduleDocument {
            version: VERSION,
            schedules,
            execution,
        };
        self.persist(
            &self.root.join("schedules.json"),
            &document,
            16 * 1024 * 1024,
        )?;
        self.schedules = document.schedules;
        self.schedule_execution = document.execution;
        Ok(())
    }
    /// Process death never replays a claimed or previously running model/tool request.
    pub fn recover_schedule_runs(&mut self) -> Result<(), String> {
        let mut execution = self.schedule_execution.clone();
        let mut changed = false;
        for record in &mut execution.fires {
            if matches!(record.phase, FirePhase::Claimed | FirePhase::Running) {
                record.phase = FirePhase::Interrupted;
                record.error = Some("上次进程中断；工具效果需要核对，未自动重放".into());
                record.at = now()?;
                changed = true;
            }
        }
        if changed {
            self.save_schedule_state(self.schedules.clone(), execution)?;
        }
        Ok(())
    }
    pub fn rearm_schedules(&mut self, reason: Option<RearmReason>) -> Result<AlarmRequest, String> {
        let time = now()?;
        // A foreground/open callback can precede an already-delivered alarm callback.
        // Keep that generation so the real receiver can atomically claim its batch.
        if matches!(
            reason,
            None | Some(RearmReason::Foreground | RearmReason::ExactPermissionChanged)
        ) {
            if let Some(alarm) = &self.schedule_execution.alarm {
                if alarm.plan.as_ref().is_some_and(|plan| {
                    plan.at_millis <= time
                        && (!matches!(reason, Some(RearmReason::Foreground))
                            || time - plan.at_millis <= 60_000)
                        && self
                            .schedules
                            .iter()
                            .any(|s| s.enabled && s.next_at == plan.at_millis)
                }) {
                    return Ok(alarm.clone());
                }
            }
        }
        let mut schedules = self.schedules.clone();
        let mut plan: Option<Plan> = None;
        for item in &mut schedules {
            if !item.enabled {
                continue;
            }
            if item.kind == crate::schedules::AT && item.at_millis <= time {
                item.enabled = false;
                item.missed_at = time;
                continue;
            }
            if item.next_at <= time
                || matches!(
                    reason,
                    Some(RearmReason::TimezoneChanged | RearmReason::ClockChanged)
                )
            {
                item.next_at = item
                    .next_trigger_after(time, &SystemLocalClock)?
                    .unwrap_or(0);
            }
            if item.next_at > time
                && plan.as_ref().is_none_or(|p| {
                    (item.next_at, item.id.as_str()) < (p.at_millis, p.schedule_id.as_str())
                })
            {
                plan = Some(Plan {
                    schedule_id: item.id.clone(),
                    at_millis: item.next_at,
                });
            }
        }
        let mut execution = self.schedule_execution.clone();
        let reuse = execution
            .alarm
            .as_ref()
            .filter(|old| old.plan == plan)
            .cloned();
        let alarm = reuse.unwrap_or_else(|| AlarmRequest {
            generation: crate::controller::unique_id(),
            plan,
        });
        execution.alarm = Some(alarm.clone());
        self.save_schedule_state(schedules, execution)?;
        Ok(alarm)
    }
    pub fn record_alarm_result(&mut self, result: AlarmResult) -> Result<(), String> {
        if self
            .schedule_execution
            .alarm
            .as_ref()
            .is_none_or(|a| a.generation != result.generation)
        {
            return Ok(());
        }
        let mut execution = self.schedule_execution.clone();
        execution.alarm_result = Some(result);
        self.save_schedule_state(self.schedules.clone(), execution)
    }
    pub fn claim_schedule_batch(
        &mut self,
        identity: &FireIdentity,
    ) -> Result<Vec<FireRecord>, String> {
        identity.validate()?;
        if self
            .schedule_execution
            .fires
            .iter()
            .any(|r| r.identity == *identity)
        {
            return Ok(Vec::new());
        }
        let alarm = self
            .schedule_execution
            .alarm
            .as_ref()
            .ok_or("没有已提交的提醒代次")?;
        let primary = alarm.plan.as_ref().ok_or("提醒计划已撤销")?;
        if FireIdentity::for_plan(alarm.generation.clone(), primary)? != *identity {
            return Err("提醒触发来自旧代次，未执行".into());
        }
        let time = now()?;
        if time < identity.scheduled_at {
            return Err("提醒尚未到时，未执行".into());
        }
        let mut schedules = self.schedules.clone();
        let mut execution = self.schedule_execution.clone();
        let mut batch = Vec::new();
        execution
            .fires
            .retain(|r| !r.phase.terminal() || r.at >= time.saturating_sub(90 * 86400000));
        for item in &mut schedules {
            if !item.enabled || item.next_at != identity.scheduled_at {
                continue;
            }
            let fire_identity = FireIdentity::for_plan(
                identity.generation.clone(),
                &Plan {
                    schedule_id: item.id.clone(),
                    at_millis: item.next_at,
                },
            )?;
            if execution.fires.iter().any(|r| r.identity == fire_identity) {
                continue;
            }
            let record = FireRecord {
                identity: fire_identity,
                fired: item.clone(),
                phase: FirePhase::Claimed,
                conversation_id: None,
                run_id: None,
                at: time,
                error: None,
            };
            batch.push(record.clone());
            execution.fires.push(record);
            if item.kind == crate::schedules::AT {
                item.enabled = false;
            } else {
                item.next_at = item
                    .next_trigger_after(time, &SystemLocalClock)?
                    .unwrap_or(0);
            }
        }
        if batch.is_empty() {
            return Err("这组提醒已被修改或取消，未执行".into());
        }
        execution.alarm = None;
        execution.alarm_result = None;
        self.save_schedule_state(schedules, execution)?;
        Ok(batch)
    }
    pub fn prepare_schedule_run(
        &mut self,
        identity: &FireIdentity,
        user_initiated: bool,
    ) -> Result<FireRecord, String> {
        let record = self
            .schedule_execution
            .fires
            .iter()
            .find(|r| r.identity == *identity)
            .ok_or("提醒没有持久化领取记录")?
            .clone();
        if record.phase != FirePhase::Claimed
            && !(record.phase == FirePhase::Deferred && user_initiated)
        {
            return Err("提醒已结束、运行中或等待用户恢复，未重放".into());
        }
        if record.conversation_id.is_some() {
            return Ok(record);
        }
        let conversation = self.new_conversation(&record.fired.title)?.id;
        let mut execution = self.schedule_execution.clone();
        let entry = execution
            .fires
            .iter_mut()
            .find(|r| r.identity == *identity)
            .ok_or("提醒领取丢失")?;
        entry.conversation_id = Some(conversation);
        let record = entry.clone();
        self.save_schedule_state(self.schedules.clone(), execution)?;
        Ok(record)
    }
    pub fn bind_schedule_run(
        &mut self,
        identity: &FireIdentity,
        key: &RunKey,
    ) -> Result<(), String> {
        key.validate()?;
        let mut execution = self.schedule_execution.clone();
        let record = execution
            .fires
            .iter_mut()
            .find(|r| r.identity == *identity)
            .ok_or("提醒未领取")?;
        if !matches!(record.phase, FirePhase::Claimed | FirePhase::Deferred)
            || record.conversation_id.as_deref() != Some(key.conversation_id.as_str())
        {
            return Err("提醒运行归属已变化".into());
        }
        record.phase = FirePhase::Running;
        record.run_id = Some(key.run_id.clone());
        record.at = now()?;
        self.save_schedule_state(self.schedules.clone(), execution)
    }
    pub fn finish_schedule_fire(
        &mut self,
        identity: &FireIdentity,
        phase: FirePhase,
        error: Option<String>,
    ) -> Result<(), String> {
        let mut execution = self.schedule_execution.clone();
        let record = execution
            .fires
            .iter_mut()
            .find(|r| r.identity == *identity)
            .ok_or("提醒未领取")?;
        if record.phase.terminal() {
            return Ok(());
        }
        if phase == FirePhase::Deferred && record.phase == FirePhase::Running {
            return Err("已经运行的提醒不能重新进入待启动状态".into());
        }
        record.phase = phase;
        record.at = now()?;
        record.error = error.map(|s| s.chars().take(2048).collect());
        let fired = record.fired.clone();
        let mut schedules = self.schedules.clone();
        if phase.terminal() && fired.kind == crate::schedules::AT {
            // Preserve a reminder edited or enabled again after the captured fire.
            schedules.retain(|s| {
                !(s.id == fired.id
                    && s.at_millis == fired.at_millis
                    && s.next_at == fired.next_at
                    && !s.enabled
                    && s.missed_at == 0)
            });
        }
        self.save_schedule_state(schedules, execution)
    }
}
