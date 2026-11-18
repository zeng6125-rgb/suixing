//! Include this in runtime_host.rs. A real independent job uses its own RunKey
//! and cancellation token, without impersonating an ordinary chat worker.
use super::*;
pub struct BackgroundWorkLease {
    owner: Publisher,
    key: RunKey,
    cancel: Arc<CancelToken>,
}
impl BackgroundWorkLease {
    pub fn set_state(&self, state: ServiceRunState) -> Result<(), String> {
        let mut scopes = self
            .owner
            .scopes
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        let scope = scopes
            .get_mut(&self.key)
            .filter(|scope| Arc::ptr_eq(&scope.parent, &self.cancel))
            .ok_or("独立后台工作的宿主lease已失效")?;
        let background = scope.background.as_mut().ok_or("该scope不是独立后台工作")?;
        background.state = state;
        Ok(())
    }
}
impl Drop for BackgroundWorkLease {
    fn drop(&mut self) {
        let mut scopes = self
            .owner
            .scopes
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if scopes.get(&self.key).is_some_and(|scope| {
            scope.background.is_some() && Arc::ptr_eq(&scope.parent, &self.cancel)
        }) {
            scopes.remove(&self.key);
        }
    }
}
impl Publisher {
    pub fn register_background_work(
        &self,
        key: RunKey,
        title: String,
        origin: ServiceRunOrigin,
        cancel: Arc<CancelToken>,
    ) -> Result<BackgroundWorkLease, String> {
        key.validate()?;
        if self.stopped.load(Ordering::Acquire)
            || cancel.is_cancelled()
            || title.len() > 1024
            || title.contains('\0')
        {
            return Err("独立后台工作身份、标题或宿主状态无效".into());
        }
        let mut scopes = self
            .scopes
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if self.stopped.load(Ordering::Acquire) || cancel.is_cancelled() {
            return Err("独立后台工作准入前已停止".into());
        }
        if scopes.len() >= MAX_SERVICE_RUNS
            || scopes.keys().any(|actual| actual.run_id == key.run_id)
        {
            return Err("独立后台工作身份冲突或达到宿主预算".into());
        }
        scopes.insert(
            key.clone(),
            CancellationScope {
                parent: cancel.clone(),
                children: HashMap::new(),
                background: Some(ServiceRunSummary {
                    key: key.clone(),
                    title,
                    origin,
                    state: ServiceRunState::Running,
                    pending_approvals: 0,
                    active_children: 0,
                }),
            },
        );
        Ok(BackgroundWorkLease {
            owner: self.clone(),
            key,
            cancel,
        })
    }
    pub(super) fn background_summaries(&self) -> Vec<ServiceRunSummary> {
        self.scopes
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .values()
            .filter_map(|scope| {
                scope.background.as_ref().map(|summary| {
                    let mut summary = summary.clone();
                    if summary.state != ServiceRunState::WaitingStorage
                        && scope.parent.is_cancelled()
                    {
                        summary.state = ServiceRunState::Cancelling;
                    }
                    summary
                })
            })
            .collect()
    }
}
