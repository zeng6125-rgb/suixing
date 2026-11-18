//! One Repository serves Settings, model tools and summary application.
use crate::{
    profile::{Repository, Request, Update},
    runtime_host::Publisher,
    state::Event,
    store::Store,
};

pub(crate) struct ProfileRuntime {
    namespace: String,
    repository: Repository,
    work: super::profile_work::Work,
    model_reads:
        std::collections::HashMap<(String, String, Option<String>), crate::profile::Revision>,
}
impl ProfileRuntime {
    pub(crate) fn boot(store: &Store) -> Result<Self, String> {
        let mut repository = store.open_profile_repository()?;
        super::profile_work::recover_counts(&mut repository, store)?;
        Ok(Self {
            namespace: store.active_namespace(),
            repository,
            work: super::profile_work::Work::new(),
            model_reads: std::collections::HashMap::new(),
        })
    }
    pub(crate) fn synchronize(&mut self, store: &Store) -> Result<bool, String> {
        if self.namespace == store.active_namespace() {
            return Ok(false);
        }
        if self.work.busy() {
            self.work.invalidate_namespace();
            return Err("画像请求或真实账单尚在结算，命名空间已变化".into());
        }
        let mut repository = store.open_profile_repository()?;
        super::profile_work::recover_counts(&mut repository, store)?;
        self.repository = repository;
        self.namespace = store.active_namespace();
        self.model_reads.clear();
        Ok(true)
    }
    pub(crate) fn repository(&self) -> &Repository {
        &self.repository
    }
    pub(crate) fn repository_mut(&mut self) -> &mut Repository {
        &mut self.repository
    }
    pub(crate) fn refresh(&mut self, store: &Store) -> Result<(), String> {
        self.synchronize(store)?;
        let mut repository = store.open_profile_repository()?;
        super::profile_work::recover_counts(&mut repository, store)?;
        self.repository = repository;
        Ok(())
    }
    pub(crate) fn prune_model_reads(&mut self, runs: &super::Runs) {
        self.model_reads.retain(|(chat, run, child), _| {
            runs.by_id.get(run).is_some_and(|active| {
                active.conversation == *chat && active.worker_is_active(child.as_deref())
            })
        });
    }
    pub(crate) fn require_model_read(
        &mut self,
        run: &crate::runtime_host::RunKey,
        child_id: Option<&str>,
        runs: &super::Runs,
    ) -> Result<(), String> {
        self.prune_model_reads(runs);
        let owner = (
            run.conversation_id.clone(),
            run.run_id.clone(),
            child_id.map(str::to_owned),
        );
        if self.model_reads.get(&owner) != Some(&self.repository.revision()) {
            return Err("请先由当前父/子运行profile_read读取最新画像，再提交profile_write".into());
        }
        Ok(())
    }
    pub(crate) fn remember_model_read(
        &mut self,
        run: &crate::runtime_host::RunKey,
        child_id: Option<&str>,
    ) -> Result<(), String> {
        let owner = (
            run.conversation_id.clone(),
            run.run_id.clone(),
            child_id.map(str::to_owned),
        );
        if self.model_reads.len() >= 64 && !self.model_reads.contains_key(&owner) {
            return Err("画像读取调用者缓存超过预算".into());
        }
        self.model_reads.insert(owner, self.repository.revision());
        Ok(())
    }
    pub(crate) fn dispatch(&mut self, request: Request, store: &Store, publish: &Publisher) {
        let operation = match &request {
            Request::Read(key) | Request::Save { operation: key, .. } => key.clone(),
        };
        let result = (|| {
            // An explicit Read reopens the actual document, including after a
            // stale CAS or uncertain write ended the prior repository epoch.
            if matches!(&request, Request::Read(_)) {
                let changed = self.namespace != store.active_namespace();
                if changed {
                    self.synchronize(store)?;
                } else {
                    let mut repository = store.open_profile_repository()?;
                    super::profile_work::recover_counts(&mut repository, store)?;
                    self.repository = repository;
                }
                if changed {
                    publish.emit(Event::Profile(Update::Changed {
                        snapshot: self.repository.snapshot(),
                    }));
                }
            }
            if self.synchronize(store)? {
                publish.emit(Event::Profile(Update::Changed {
                    snapshot: self.repository.snapshot(),
                }));
            }
            if matches!(&request, Request::Save { .. }) {
                store.profile_writable()?;
            }
            Ok(self
                .repository
                .dispatch(request, |key| key.conversation_id.is_none()))
        })();
        let update = match result {
            Ok(update) => update,
            Err(error) => Update::Failed { operation, error },
        };
        if matches!(&update, Update::Saved { .. }) {
            publish.emit(Event::Profile(Update::Changed {
                snapshot: self.repository.snapshot(),
            }));
        }
        publish.emit(Event::Profile(update));
    }
    pub(crate) fn busy(&self) -> bool {
        self.work.busy()
    }
    pub(crate) fn poll(&mut self, store: &mut Store, publish: &Publisher) {
        self.work.preferences_changed(&store.settings);
        self.work.poll(&mut self.repository, store, publish);
    }
    pub(crate) fn summary(
        &mut self,
        request: super::profile_work::Request,
        store: &mut Store,
        publish: &Publisher,
    ) {
        if let Err(error) = self.synchronize(store) {
            publish.emit(Event::Error(error));
            return;
        }
        self.work
            .dispatch(request, &mut self.repository, store, publish);
    }
    pub(crate) fn finished_turn(
        &mut self,
        source: crate::runtime_host::RunKey,
        resolved: crate::state::Settings,
        messages: &[crate::state::Message],
        other_runs: usize,
        store: &mut Store,
        publish: &Publisher,
    ) {
        let result = self.synchronize(store).and_then(|_| {
            self.work.after_finished(
                &mut self.repository,
                store,
                publish,
                source,
                resolved,
                messages,
                other_runs,
            )
        });
        if let Err(error) = result {
            publish.emit(Event::Error(format!("画像完成轮次或整理未确认：{error}")));
        }
    }
    pub(crate) fn context_prefix(&self, store: &Store) -> Result<String, String> {
        if self.namespace != store.active_namespace() {
            return Err("画像缓存不属于当前资料来源".into());
        }
        self.repository
            .injected(&super::profile_work::preferences(&store.settings))
    }

    pub(crate) fn injected(
        &mut self,
        run: &str,
        child_id: Option<&str>,
        caller_cancel: &std::sync::Arc<crate::network::CancelToken>,
        runs: &super::Runs,
        store: &Store,
    ) -> Result<String, String> {
        let current = runs
            .by_id
            .get(run)
            .filter(|run| run.worker_is_active(child_id))
            .ok_or("画像注入所属运行已结束")?;
        let actual = match child_id {
            None => &current.cancel,
            Some(id) => {
                &current
                    .children
                    .get(id)
                    .ok_or("画像注入所属子任务已结束")?
                    .cancel
            }
        };
        if !std::sync::Arc::ptr_eq(actual, caller_cancel) {
            return Err("画像注入调用身份不属于当前运行".into());
        }
        if child_id.is_none() && current.profile_turn {
            self.work.new_run_started();
        }
        self.synchronize(store)?;
        self.repository
            .injected(&super::profile_work::preferences(&store.settings))
    }
}
