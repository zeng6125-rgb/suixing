//! A single bounded worker samples only for live visible-page leases. The
//! actor supplies already-loaded ledger rows and matches epoch, record revision
//! and request identity before publishing a snapshot.
use crate::{
    performance::{self, Device, Record, Request, Snapshot, Update},
    platform::permissions::OperationKey,
};
use std::{
    collections::HashMap,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc::{self, Receiver, SyncSender, TryRecvError},
    },
    thread,
    time::{Duration, Instant},
};
enum Job {
    Sample {
        epoch: u64,
        ticket: u64,
        records_revision: u64,
        records: Vec<Record>,
        foreground: bool,
    },
    Suspend,
}
struct Reply {
    epoch: u64,
    ticket: u64,
    records_revision: u64,
    snapshot: Arc<Snapshot>,
}
pub(crate) struct PerformanceRuntime {
    namespace: String,
    epoch: u64,
    records_revision: u64,
    records: Vec<Record>,
    leases: HashMap<String, OperationKey>,
    refresh: HashMap<String, OperationKey>,
    jobs: SyncSender<Job>,
    replies: Receiver<Reply>,
    next_ticket: u64,
    in_flight: Option<u64>,
    last_sample: Instant,
    foreground: bool,
    cached: Option<Arc<Snapshot>>,
    visibility: Arc<AtomicBool>,
}
impl Drop for PerformanceRuntime {
    fn drop(&mut self) {
        self.visibility.store(false, Ordering::Release);
    }
}
impl PerformanceRuntime {
    pub(crate) fn boot(namespace: String, records: Vec<Record>) -> Result<Self, String> {
        let (jobs, receive) = mpsc::sync_channel(4);
        let (output, replies) = mpsc::sync_channel(2);
        let visibility = Arc::new(AtomicBool::new(false));
        let worker_visibility = visibility.clone();
        thread::Builder::new()
            .name("rust-performance".into())
            .spawn(move || {
                let mut sampler =
                    performance::metrics::Sampler::with_guard(worker_visibility.clone());
                while let Ok(job) = receive.recv() {
                    match job {
                        Job::Suspend => {
                            sampler =
                                performance::metrics::Sampler::with_guard(worker_visibility.clone())
                        }
                        Job::Sample {
                            epoch,
                            ticket,
                            records_revision,
                            mut records,
                            foreground,
                        } => {
                            let visible = foreground
                                && worker_visibility.load(Ordering::Acquire)
                                && platform_foreground();
                            let device = if visible {
                                sampler.sample()
                            } else {
                                sampler.suspend();
                                Device::default()
                            };
                            let zone = crate::platform::calendar::zone_id().ok();
                            for record in &mut records {
                                record.at_label = zone.as_deref().and_then(|zone| {
                                    crate::platform::calendar::time_label(record.at, zone).ok()
                                });
                            }
                            let snapshot = Arc::new(Snapshot::from_records(device, records));
                            if output
                                .send(Reply {
                                    epoch,
                                    ticket,
                                    records_revision,
                                    snapshot,
                                })
                                .is_err()
                            {
                                break;
                            }
                        }
                    }
                }
            })
            .map_err(|_| "性能采样线程无法启动")?;
        let snapshot = Snapshot::from_records(Device::default(), records.clone());
        snapshot.validate()?;
        Ok(Self {
            namespace,
            epoch: 1,
            records_revision: 1,
            records,
            leases: HashMap::new(),
            refresh: HashMap::new(),
            jobs,
            replies,
            next_ticket: 1,
            in_flight: None,
            last_sample: Instant::now() - Duration::from_secs(1),
            foreground: false,
            cached: None,
            visibility,
        })
    }
    pub(crate) fn saved_records(
        &mut self,
        namespace: String,
        records: Vec<Record>,
    ) -> Result<Vec<Update>, String> {
        Snapshot::from_records(Device::default(), records.clone()).validate()?;
        let mut updates = Vec::new();
        if self.namespace != namespace {
            self.namespace = namespace;
            self.epoch = self.epoch.checked_add(1).ok_or("性能来源代数用尽")?;
            for (_, operation) in self.refresh.drain() {
                updates.push(Update::Failed {
                    operation,
                    error: "性能记录来源已经切换，请重新读取".into(),
                });
            }
            self.cached = None;
            self.in_flight = None;
            let _ = self.jobs.try_send(Job::Suspend);
        }
        self.records = records;
        self.records_revision = self
            .records_revision
            .checked_add(1)
            .ok_or("性能记录版本用尽")?;
        self.last_sample = Instant::now() - Duration::from_secs(1);
        Ok(updates)
    }
    pub(crate) fn dispatch(&mut self, request: Request, foreground: bool) -> Vec<Update> {
        self.foreground = foreground;
        self.visibility
            .store(foreground && !self.leases.is_empty(), Ordering::Release);
        let mut updates = Vec::new();
        let operation = match &request {
            Request::Visible { operation, .. } => operation.clone(),
            Request::Refresh(operation) => operation.clone(),
        };
        if let Err(error) = operation.validate() {
            updates.push(Update::Failed { operation, error });
            return updates;
        }
        if operation.conversation_id.is_some() {
            updates.push(Update::Failed {
                operation,
                error: "性能页面操作不能绑定会话".into(),
            });
            return updates;
        }
        match request {
            Request::Visible {
                operation,
                visible: true,
            } => {
                if self.leases.len() >= 4 && !self.leases.contains_key(&operation.id) {
                    updates.push(Update::Failed {
                        operation,
                        error: "性能页面订阅已满".into(),
                    });
                    return updates;
                }
                self.leases.insert(operation.id.clone(), operation);
                self.last_sample = Instant::now() - Duration::from_secs(1);
            }
            Request::Visible {
                operation,
                visible: false,
            } => {
                if self.leases.get(&operation.id) == Some(&operation) {
                    self.leases.remove(&operation.id);
                }
                self.refresh.remove(&operation.id);
                if self.leases.is_empty() {
                    let _ = self.jobs.try_send(Job::Suspend);
                }
            }
            Request::Refresh(operation) => {
                if self.refresh.len() >= 4 {
                    updates.push(Update::Failed {
                        operation,
                        error: "性能读取请求已满".into(),
                    });
                    return updates;
                }
                self.refresh.insert(operation.id.clone(), operation);
                self.last_sample = Instant::now() - Duration::from_secs(1);
            }
        }
        updates.extend(self.tick(foreground));
        updates
    }
    pub(crate) fn tick(&mut self, foreground: bool) -> Vec<Update> {
        let mut updates = Vec::new();
        if self.foreground && !foreground {
            let _ = self.jobs.try_send(Job::Suspend);
        }
        self.foreground = foreground;
        self.visibility
            .store(foreground && !self.leases.is_empty(), Ordering::Release);
        for _ in 0..2 {
            match self.replies.try_recv() {
                Ok(reply) => {
                    if self.in_flight == Some(reply.ticket) {
                        self.in_flight = None;
                    }
                    if reply.epoch != self.epoch || reply.records_revision != self.records_revision
                    {
                        continue;
                    }
                    if let Err(error) = reply.snapshot.validate() {
                        for (_, operation) in self.refresh.drain() {
                            updates.push(Update::Failed {
                                operation,
                                error: error.clone(),
                            });
                        }
                        continue;
                    }
                    self.cached = Some(reply.snapshot.clone());
                    for (_, operation) in self.refresh.drain() {
                        updates.push(Update::Snapshot {
                            operation,
                            snapshot: reply.snapshot.clone(),
                        });
                    }
                    if foreground {
                        for operation in self.leases.values() {
                            updates.push(Update::Snapshot {
                                operation: operation.clone(),
                                snapshot: reply.snapshot.clone(),
                            });
                        }
                    }
                }
                Err(TryRecvError::Empty | TryRecvError::Disconnected) => break,
            }
        }
        let needed = (!self.leases.is_empty() && foreground) || !self.refresh.is_empty();
        if needed
            && self.in_flight.is_none()
            && self.last_sample.elapsed() >= Duration::from_secs(1)
        {
            let ticket = self.next_ticket;
            if let Some(next) = ticket.checked_add(1) {
                if self
                    .jobs
                    .try_send(Job::Sample {
                        epoch: self.epoch,
                        ticket,
                        records_revision: self.records_revision,
                        records: self.records.clone(),
                        foreground,
                    })
                    .is_ok()
                {
                    self.next_ticket = next;
                    self.in_flight = Some(ticket);
                    self.last_sample = Instant::now();
                }
            } else {
                for (_, operation) in self.refresh.drain() {
                    updates.push(Update::Failed {
                        operation,
                        error: "性能读取身份用尽".into(),
                    });
                }
            }
        }
        updates
    }
}
#[cfg(target_os = "android")]
pub(super) fn platform_foreground() -> bool {
    crate::platform::android::activity_visible()
}
#[cfg(not(target_os = "android"))]
pub(super) fn platform_foreground() -> bool {
    true
}
