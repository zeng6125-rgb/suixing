//! Explicit Linux UI operations and their real Manager/service receipts.
//! Boot may recover a preserved transaction; it never downloads or runs a probe.
use crate::{
    linux::{self, Distro, Key, Manager, Protection, Record, ServiceRow, Status},
    platform::permissions::OperationKey,
    shell_environment::ShellEnvironment,
};
use std::{collections::BTreeMap, path::PathBuf, sync::Arc};

#[derive(Clone, Debug)]
pub enum Request {
    Install {
        operation: OperationKey,
        distro: Distro,
    },
    Remove {
        operation: OperationKey,
        distro: Distro,
    },
    Cancel {
        request_id: String,
        key: Key,
    },
    Refresh {
        request_id: String,
    },
}
#[derive(Clone, Debug)]
pub enum Update {
    Snapshot {
        revision: u64,
        process_token: String,
        supported: bool,
        statuses: Vec<Status>,
        records: Vec<Record>,
        protection: Protection,
        running: Vec<ServiceRow>,
    },
    Accepted {
        operation: OperationKey,
        key: Key,
        record: Record,
    },
    Rejected {
        operation: OperationKey,
        error: String,
    },
    CancelFinished {
        request_id: String,
        key: Key,
        accepted: bool,
        error: Option<String>,
    },
    Refreshed {
        request_id: String,
    },
    RefreshFailed {
        request_id: String,
        error: String,
    },
    Progress {
        record: Record,
    },
    Finished {
        record: Record,
    },
    Recovery {
        distro: Distro,
        restored: bool,
        error: Option<String>,
    },
}
pub struct LinuxRuntime {
    manager: Arc<Manager>,
    seen: Option<u64>,
    dirty: bool,
    /// Manager keeps at most128 durable operation identities. Keep the same
    /// bound and reject changed semantics instead of treating a claim as replay.
    requests: BTreeMap<String, (OperationKey, Distro, bool)>,
}
impl LinuxRuntime {
    pub fn unavailable(request: Request, error: String) -> Update {
        match request {
            Request::Install { operation, .. } | Request::Remove { operation, .. } => {
                Update::Rejected { operation, error }
            }
            Request::Cancel { request_id, key } => Update::CancelFinished {
                request_id,
                key,
                accepted: false,
                error: Some(error),
            },
            Request::Refresh { request_id } => Update::RefreshFailed { request_id, error },
        }
    }
    pub fn boot(
        environment: ShellEnvironment,
        operations_directory: PathBuf,
    ) -> Result<Self, String> {
        let manager = Manager::open(linux::Config::new(environment, operations_directory))?;
        Ok(Self {
            manager,
            seen: None,
            dirty: true,
            requests: BTreeMap::new(),
        })
    }
    pub fn manager(&self) -> Arc<Manager> {
        self.manager.clone()
    }
    pub fn snapshot(&self) -> Update {
        let snapshot = self.manager.snapshot();
        Update::Snapshot {
            revision: snapshot.revision,
            process_token: snapshot.process_token,
            supported: self.manager.supported(),
            statuses: self.manager.status(),
            records: self.manager.records(),
            protection: self.manager.protection(),
            running: snapshot.running,
        }
    }
    fn signature(operation: &OperationKey) -> String {
        format!(
            "{}:{}:{}:{}",
            operation.id.len(),
            operation.id,
            operation.generation,
            operation.conversation_id.as_deref().unwrap_or("")
        )
    }
    /// UI must disclose installation/removal and supply its trusted OperationKey.
    /// Accepted means the durable claim/worker was accepted. Only Finished and
    /// the exact record report publication, interruption and cleanup uncertainty.
    pub fn dispatch(&mut self, request: Request) -> Update {
        self.dirty = true;
        match request {
            Request::Install { operation, distro } => self.start(operation, distro, false),
            Request::Remove { operation, distro } => self.start(operation, distro, true),
            Request::Cancel { request_id, key } => {
                let error =
                    if request_id.is_empty() || request_id.len() > 128 || request_id.contains('\0')
                    {
                        Some("Linux取消请求身份无效".into())
                    } else if let Err(error) = key.validate() {
                        Some(error)
                    } else if key.process_token != self.manager.snapshot().process_token {
                        Some("Linux取消属于旧进程，未给新操作发送取消".into())
                    } else {
                        None
                    };
                let accepted = error.is_none() && self.manager.cancel_key(&key);
                Update::CancelFinished {
                    request_id,
                    key,
                    accepted,
                    error,
                }
            }
            Request::Refresh { request_id } => Update::Refreshed { request_id },
        }
    }
    fn start(&mut self, operation: OperationKey, distro: Distro, remove: bool) -> Update {
        let result = (|| -> Result<Record, String> {
            operation.validate()?;
            let signature = Self::signature(&operation);
            if let Some((old, old_distro, old_remove)) = self.requests.get(&signature) {
                if old != &operation || *old_distro != distro || *old_remove != remove {
                    return Err("Linux操作身份已用于其他请求，未更改或重放".into());
                }
            } else {
                if self.manager.record(&operation).is_some() {
                    return Err(
                        "此Linux操作已有旧持久记录；结果可查看，但未将其重放为新的安装或移除"
                            .into(),
                    );
                }
                if self.requests.len() >= 128 {
                    return Err("Linux请求身份达到128条保留预算，操作未执行".into());
                }
                self.requests
                    .insert(signature, (operation.clone(), distro, remove));
            }
            let record = if remove {
                self.manager.remove(operation.clone(), distro)
            } else {
                self.manager.install(operation.clone(), distro)
            }?;
            if record.operation.key() != operation || record.distro != distro {
                return Err("Linux管理器回执与冻结操作身份不一致".into());
            }
            Ok(record)
        })();
        match result {
            Ok(record) => Update::Accepted {
                key: Key {
                    process_token: self.manager.snapshot().process_token,
                    id: operation.id.clone(),
                    generation: operation.generation,
                },
                operation,
                record,
            },
            Err(error) => Update::Rejected { operation, error },
        }
    }
    pub fn poll(&mut self, max: usize) -> Vec<Update> {
        let max = max.clamp(1, 64);
        let snapshot = self.manager.snapshot();
        let mut updates = Vec::new();
        if self.dirty || self.seen != Some(snapshot.revision) {
            self.dirty = false;
            self.seen = Some(snapshot.revision);
            updates.push(self.snapshot());
        }
        if updates.len() < max {
            updates.extend(
                self.manager
                    .drain_events(max - updates.len())
                    .into_iter()
                    .map(|event| match event {
                        linux::Event::Progress(record) => Update::Progress { record },
                        linux::Event::Finished(record) => Update::Finished { record },
                        linux::Event::Recovery {
                            distro,
                            restored,
                            error,
                        } => Update::Recovery {
                            distro,
                            restored,
                            error,
                        },
                    }),
            );
        }
        updates
    }
    pub fn shutdown(&self) {
        self.manager.shutdown();
    }
}
