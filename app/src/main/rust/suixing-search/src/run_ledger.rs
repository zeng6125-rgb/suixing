//! Bounded run-local repetition and progress state. Only tool tokens and SHA-256 bytes enter.
use crate::STATUS_REQUEST_ERROR;
use std::collections::{BTreeMap, BTreeSet, HashMap, VecDeque};

#[derive(Clone, Copy)]
struct Entry {
    step: i32,
    tool: u64,
    ok: bool,
    #[allow(dead_code)]
    args: [u8; 32],
    #[allow(dead_code)]
    result: [u8; 32],
    #[allow(dead_code)]
    at: i64,
}
type Key = (u64, [u8; 32]);
struct Repeated {
    result: [u8; 32],
    count: i32,
    notified: i32,
    order: u128,
}
#[derive(Clone, Copy)]
struct Reminder {
    tool: u64,
    level: i32,
    count: i32,
}
struct Ledger {
    limit: usize,
    threshold: i32,
    entries: VecDeque<Entry>,
    repeated: HashMap<Key, Repeated>,
    order: BTreeMap<u128, Key>,
    polls: HashMap<u64, [u8; 32]>,
    poll_order: VecDeque<u64>,
    reminders: VecDeque<Reminder>,
    sequence: u128,
    total: i32,
    failed: i32,
}
impl Ledger {
    fn new(threshold: i32, limit: usize) -> Self {
        Self {
            limit,
            threshold,
            entries: VecDeque::new(),
            repeated: HashMap::new(),
            order: BTreeMap::new(),
            polls: HashMap::new(),
            poll_order: VecDeque::new(),
            reminders: VecDeque::new(),
            sequence: 0,
            total: 0,
            failed: 0,
        }
    }
    fn progress(&mut self) {
        for entry in self.repeated.values_mut() {
            entry.count = 0;
            entry.notified = 0;
        }
        self.reminders.clear();
    }
    fn record(&mut self, entry: Entry, polling: bool) -> Result<(), i32> {
        // Reserve a never-reused order before changing state. Counters intentionally wrap as JVM Int.
        let sequence = self.sequence.checked_add(1).ok_or(STATUS_REQUEST_ERROR)?;
        self.entries.push_back(entry);
        while self.entries.len() > self.limit {
            self.entries.pop_front();
        }
        self.total = self.total.wrapping_add(1);
        if !entry.ok {
            self.failed = self.failed.wrapping_add(1);
        }
        if polling {
            let previous = self.polls.insert(entry.tool, entry.result);
            if previous.is_none() {
                self.poll_order.push_back(entry.tool);
            }
            if previous.is_some_and(|previous| previous != entry.result) {
                self.progress();
            }
            while self.polls.len() > self.limit {
                if let Some(tool) = self.poll_order.pop_front() {
                    self.polls.remove(&tool);
                }
            }
            return Ok(());
        }
        self.sequence = sequence;
        let key = (entry.tool, entry.args);
        let previous = self.repeated.remove(&key);
        if let Some(ref previous) = previous {
            self.order.remove(&previous.order);
        }
        let mut value = match previous {
            Some(previous) if previous.result == entry.result => Repeated {
                count: previous.count.wrapping_add(1),
                order: sequence,
                ..previous
            },
            _ => {
                self.progress();
                Repeated {
                    result: entry.result,
                    count: 1,
                    notified: 0,
                    order: sequence,
                }
            }
        };
        let level = if i64::from(value.count) >= i64::from(self.threshold) * 2 {
            2
        } else if value.count >= self.threshold {
            1
        } else {
            0
        };
        if level > value.notified {
            value.notified = level;
            self.reminders.push_back(Reminder {
                tool: entry.tool,
                level,
                count: value.count,
            });
            while self.reminders.len() > 2 {
                self.reminders.pop_front();
            }
        }
        self.repeated.insert(key, value);
        self.order.insert(sequence, key);
        while self.repeated.len() > self.limit {
            if let Some((_, oldest)) = self.order.pop_first() {
                self.repeated.remove(&oldest);
            }
        }
        Ok(())
    }
    fn retained(&self) -> BTreeSet<u64> {
        self.entries
            .iter()
            .map(|e| e.tool)
            .chain(self.repeated.keys().map(|k| k.0))
            .chain(self.polls.keys().copied())
            .chain(self.reminders.iter().map(|r| r.tool))
            .collect()
    }
}

/// Progress and repetition state for one agent run.
pub struct RunLedger(Ledger);

impl RunLedger {
    pub fn new(max_rounds: u32) -> Self {
        let threshold = if max_rounds == 0 {
            5
        } else {
            (max_rounds / 10).max(3) as i32
        };
        Self(Ledger::new(threshold, 96))
    }

    pub fn record(
        &mut self,
        tool: u64,
        args: [u8; 32],
        result: [u8; 32],
        ok: bool,
        polling: bool,
    ) -> Result<(), i32> {
        self.0.record(
            Entry {
                step: self.0.total.wrapping_add(1),
                tool,
                ok,
                args,
                result,
                at: 0,
            },
            polling,
        )
    }

    pub fn warning(&mut self) -> Option<(u64, i32, i32)> {
        self.0
            .reminders
            .pop_front()
            .map(|value| (value.tool, value.level, value.count))
    }

    pub fn totals(&self) -> (i32, i32) {
        (self.0.total, self.0.failed)
    }

    pub fn recent(&self) -> impl Iterator<Item = (i32, u64, bool)> + '_ {
        self.0
            .entries
            .iter()
            .skip(self.0.entries.len().saturating_sub(4))
            .map(|entry| (entry.step, entry.tool, entry.ok))
    }

    pub fn retained_tools(&self) -> BTreeSet<u64> {
        self.0.retained()
    }
}
