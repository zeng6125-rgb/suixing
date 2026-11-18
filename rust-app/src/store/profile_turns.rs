//! Successful main turns have a durable replay-safe ordinal. Child terminals,
//! request counts, error outcomes and UI/latest-conversation counts are not turns.
use super::*;
use crate::runtime_host::RunKey;
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub(crate) struct FinishedTurn {
    pub run: RunKey,
    pub ordinal: u64,
    pub eligible: bool,
}
#[derive(Clone, PartialEq, Serialize, Deserialize)]
struct Clock {
    version: u32,
    next: u64,
    turns: BTreeMap<String, FinishedTurn>,
}
impl Default for Clock {
    fn default() -> Self {
        Self {
            version: 1,
            next: 1,
            turns: BTreeMap::new(),
        }
    }
}
fn validate(clock: &Clock) -> Result<(), String> {
    if clock.version != 1 || clock.turns.len() > 65_536 || clock.next == 0 {
        return Err("画像完成轮次账本无效或超过预算".into());
    }
    let mut seen = HashSet::new();
    for (id, turn) in &clock.turns {
        turn.run.validate()?;
        if id != &turn.run.run_id
            || turn.ordinal == 0
            || turn.ordinal >= clock.next
            || !seen.insert(turn.ordinal)
        {
            return Err("画像完成轮次身份或序号冲突".into());
        }
    }
    if clock.next - 1 != clock.turns.len() as u64
        || clock.next
            != clock
                .turns
                .values()
                .map(|turn| turn.ordinal)
                .max()
                .unwrap_or(0)
                .checked_add(1)
                .ok_or("画像轮次已耗尽")?
    {
        return Err("画像完成轮次序号不连续".into());
    }
    Ok(())
}
impl Store {
    fn profile_clock(&self) -> Result<Clock, String> {
        let clock = optional_json::<Clock>(
            &self.root,
            &self.root.join("profile-turns.json"),
            16 * 1024 * 1024,
        )?
        .unwrap_or_default();
        validate(&clock)?;
        Ok(clock)
    }
    pub(crate) fn record_finished_profile_turn(
        &mut self,
        run: RunKey,
        eligible: bool,
    ) -> Result<FinishedTurn, String> {
        self.writable()?;
        run.validate()?;
        let mut clock = self.profile_clock()?;
        if let Some(existing) = clock.turns.get(&run.run_id) {
            if existing.run != run {
                return Err("已保存完成轮次属于另一个会话".into());
            }
            return Ok(existing.clone());
        }
        if clock.turns.len() >= 65_536 {
            return Err("画像完成轮次达到预算，原记录未淘汰".into());
        }
        let turn = FinishedTurn {
            run,
            ordinal: clock.next,
            eligible,
        };
        clock.next = clock.next.checked_add(1).ok_or("画像轮次已耗尽")?;
        clock.turns.insert(turn.run.run_id.clone(), turn.clone());
        validate(&clock)?;
        self.persist(
            &self.root.join("profile-turns.json"),
            &clock,
            16 * 1024 * 1024,
        )?;
        let actual = match self.profile_clock() {
            Ok(actual) => actual,
            Err(error) => {
                self.write_blocked = Some(error.clone());
                return Err(error);
            }
        };
        if actual != clock {
            let error = "画像完成轮次实际读回不同，已暂停写入".to_owned();
            self.write_blocked = Some(error.clone());
            return Err(error);
        }
        Ok(turn)
    }
    pub(crate) fn unconsumed_profile_turns(&self, after: u64) -> Result<Vec<FinishedTurn>, String> {
        let mut turns: Vec<_> = self
            .profile_clock()?
            .turns
            .into_values()
            .filter(|turn| turn.ordinal > after && turn.eligible)
            .collect();
        turns.sort_by_key(|turn| turn.ordinal);
        Ok(turns)
    }
}
