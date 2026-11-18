//! One separate real request. The owning actor schedules, bills and applies the
//! result with profile CAS; no main-run wire or synthetic request is produced.
use super::profile::{self, Preferences, Repository, Revision};
use crate::{
    network::{self, CancelToken},
    runtime_host::RunKey,
    state::{Message, Settings, UsageRecord},
};
use serde::{Deserialize, Serialize};
use std::{sync::Arc, time::Duration};
use suixing_search::rust_protocol;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Key {
    pub id: String,
    pub generation: u64,
    pub conversation_id: String,
    pub source_run: Option<RunKey>,
    pub automatic: bool,
}
impl Key {
    fn validate(&self) -> Result<(), String> {
        if self.id.is_empty()
            || self.id.len() > 96
            || self.id.contains('\0')
            || self.generation == 0
            || self.conversation_id.is_empty()
            || self.conversation_id.len() > 128
        {
            return Err("画像总结操作身份无效".into());
        }
        if let Some(run) = &self.source_run {
            run.validate()?;
            if run.conversation_id != self.conversation_id {
                return Err("画像来源运行会话不一致".into());
            }
        }
        Ok(())
    }
}
pub struct Eligibility {
    pub background_scope_available: bool,
    pub other_active_runs: usize,
    pub is_child: bool,
    pub finished_turn_ordinal: u64,
}
/// The host owns an independent summary lifetime. For automatic summaries, a
/// new ordinary run, opt-out, namespace switch or host shutdown invalidates it.
/// Do not borrow the ended main-run cancellation token and instantly retire it.
pub struct Scope {
    pub cancel: Arc<CancelToken>,
    pub valid: Arc<dyn Fn(&Key) -> bool + Send + Sync>,
}
pub struct Plan {
    key: Key,
    expected: Revision,
    settings: Settings,
    wire: Vec<rust_protocol::Message>,
    scope: Scope,
}
pub enum Schedule {
    Skipped,
    Ready(Plan),
}
/// Root keeps one coordinator across UI and automatic entry points. Claim before
/// launching; release only on the exact worker final. A new chat can cancel an
/// automatic summary without waiting for it or hijacking its billing identity.
#[derive(Default)]
pub struct Coordinator {
    active: Option<(Key, Arc<CancelToken>)>,
}
impl Coordinator {
    pub fn claim(&mut self, plan: Plan) -> Result<Plan, String> {
        if self.active.is_some() {
            return Err("已有画像整理正在请求或结算".into());
        }
        self.active = Some((plan.key.clone(), plan.scope.cancel.clone()));
        Ok(plan)
    }
    pub fn active(&self) -> Option<&Key> {
        self.active.as_ref().map(|(key, _)| key)
    }
    pub fn cancel(&self, key: &Key) -> bool {
        if let Some((actual, cancel)) = &self.active {
            if actual == key {
                cancel.cancel();
                return true;
            }
        }
        false
    }
    pub fn new_run_started(&self) {
        if let Some((key, cancel)) = &self.active {
            if key.automatic {
                cancel.cancel();
            }
        }
    }
    pub fn preferences_changed(&self, prefs: &Preferences) {
        if !prefs.auto_profile {
            if let Some((key, cancel)) = &self.active {
                if key.automatic {
                    cancel.cancel();
                }
            }
        }
    }
    pub fn finished(&mut self, key: &Key) -> bool {
        if self
            .active
            .as_ref()
            .is_some_and(|(actual, _)| actual == key)
        {
            self.active.take();
            true
        } else {
            false
        }
    }
}
pub trait Ledger {
    /// Persist/admit and wait for the Store's durable acknowledgement. The same
    /// ID moves Pending -> terminal; a terminal callback survives scope cancel.
    fn save(&mut self, record: &UsageRecord) -> Result<(), String>;
}
#[derive(Clone, Debug)]
pub struct ResultRecord {
    pub key: Key,
    pub expected: Revision,
    pub operations: String,
    pub request_error: Option<String>,
    pub billing: UsageRecord,
    pub billing_error: Option<String>,
}

pub fn after_finished_turn(
    repository: &mut Repository,
    prefs: &Preferences,
    key: Key,
    resolved: Settings,
    messages: &[Message],
    scope: Scope,
    eligibility: Eligibility,
) -> Result<Schedule, String> {
    if eligibility.is_child || !prefs.auto_profile || !eligibility.background_scope_available {
        return Ok(Schedule::Skipped);
    }
    let turns = repository.bump_finished_turn(eligibility.finished_turn_ordinal, true, false)?;
    if turns < prefs.effective_turns() as u64 || eligibility.other_active_runs != 0 {
        return Ok(Schedule::Skipped);
    }
    if !key.automatic {
        return Err("自动画像路径缺少自动操作身份".into());
    }
    Ok(Schedule::Ready(prepare(
        repository, key, resolved, messages, scope,
    )?))
}
pub fn manual(
    repository: &Repository,
    key: Key,
    resolved: Settings,
    messages: &[Message],
    scope: Scope,
) -> Result<Plan, String> {
    if key.automatic {
        return Err("手动画像路径不能冒充自动触发".into());
    }
    // User action bypasses automatic preference and turn threshold.
    prepare(repository, key, resolved, messages, scope)
}
fn prepare(
    repository: &Repository,
    key: Key,
    mut resolved: Settings,
    messages: &[Message],
    scope: Scope,
) -> Result<Plan, String> {
    key.validate()?;
    if scope.cancel.is_cancelled() || !(scope.valid)(&key) {
        return Err("画像总结所属操作已取消".into());
    }
    if repository.document().text.encode_utf16().count() > profile::MAX_UNITS {
        return Err("现有画像超过8000字，自动总结未截断原画像".into());
    }
    let conversation = profile::render_recent(messages)?;
    let old = repository.document().text.trim();
    let payload = format!(
        "【旧画像】\n{}\n\n【最近的对话】\n{conversation}",
        if old.is_empty() {
            "（还没有画像）"
        } else {
            old
        }
    );
    resolved.reasoning_effort.clear();
    resolved.max_tokens = 2000;
    // Keep the actual endpoint/model/protocol/headers frozen from the originating
    // resolved route. No re-resolution from global or a different latest chat.
    let wire = vec![
        rust_protocol::Message::system(SYSTEM_PROMPT),
        rust_protocol::Message::user(payload),
    ];
    scope.cancel.set_deadline(Duration::from_secs(120));
    Ok(Plan {
        key,
        expected: repository.revision(),
        settings: resolved,
        wire,
        scope,
    })
}
impl Plan {
    pub fn key(&self) -> &Key {
        &self.key
    }
    pub fn cancel(&self) {
        self.scope.cancel.cancel();
    }
    /// Every physical generation receives its own admitted Pending/Final ID.
    /// A failed bill can be retried only in storage, never through HTTP.
    pub async fn execute(
        self,
        ledger: &mut impl Ledger,
        now: impl Fn() -> i64,
    ) -> Result<ResultRecord, String> {
        if self.scope.cancel.is_cancelled() || !(self.scope.valid)(&self.key) {
            return Err("画像总结在请求前已取消".into());
        }
        let prepared = network::prepare_chat(&self.settings, &self.wire, &[])?;
        let policy = self.settings.behavior.retry_policy();
        let budget = Arc::new(network::RequestBudget::new(
            policy.max_attempts as usize + 1,
        ));
        let mut attempt = 0u32;
        let mut tick = tokio::time::interval(Duration::from_millis(20));
        loop {
            if self.scope.cancel.is_cancelled() || !(self.scope.valid)(&self.key) {
                return Err("画像总结在后继请求前已停止".into());
            }
            attempt += 1;
            let at = now();
            if at < 0 {
                return Err("画像请求时间无效".into());
            }
            let base = format!("profile-{}-{}", self.key.id, self.key.generation);
            let id = if attempt == 1 {
                base
            } else {
                format!("{base}-r{attempt}")
            };
            if id.len() > 128 {
                return Err("画像实际请求账单身份超过预算".into());
            }
            let mut billing = UsageRecord {
                id,
                conversation_id: self.key.conversation_id.clone(),
                model: self.settings.model.clone(),
                at,
                prompt_tokens: None,
                completion_tokens: None,
                reasoning_tokens: None,
                cached_tokens: None,
                error: None,
                pending: true,
                summary: false,
            };
            if let Err(error) = ledger.save(&billing) {
                billing.pending = false;
                billing.error = Some("画像Pending账单没有持久确认，本次请求未发送".into());
                let billing_error = ledger.save(&billing).err();
                return Ok(ResultRecord {
                    key: self.key,
                    expected: self.expected,
                    operations: String::new(),
                    request_error: Some(error),
                    billing,
                    billing_error,
                });
            }
            let cancel = Arc::new(CancelToken::new());
            let mut text_bytes = 0usize;
            let mut thinking_bytes = 0usize;
            let mut output_exceeded = false;
            let before = budget.used();
            let mut request = Box::pin(network::chat_prepared(
                &prepared,
                cancel.clone(),
                |thinking, text| {
                    let bytes = if thinking {
                        &mut thinking_bytes
                    } else {
                        &mut text_bytes
                    };
                    *bytes = bytes.saturating_add(text.len());
                    if *bytes > 32 * 1024 {
                        output_exceeded = true;
                        cancel.cancel();
                    }
                },
                Some(budget.clone()),
            ));
            let response = loop {
                tokio::select! {biased;
                    result=&mut request=>break result,
                    _=self.scope.cancel.cancelled()=>{cancel.cancel();break request.as_mut().await;},
                    _=tick.tick()=>{if !(self.scope.valid)(&self.key){self.scope.cancel.cancel();cancel.cancel();}}
                }
            };
            drop(request);
            let sent = budget.used() > before;
            let usage = response.turn.usage_report;
            billing.prompt_tokens = usage.prompt_tokens;
            billing.completion_tokens = usage.completion_tokens;
            billing.reasoning_tokens = usage.reasoning_tokens;
            billing.cached_tokens = usage.cached_tokens;
            billing.pending = false;
            let request_error = if output_exceeded || response.turn.text.len() > 32 * 1024 {
                Some("画像操作输出超过32 KiB，结果未应用".into())
            } else if self.scope.cancel.is_cancelled() || !(self.scope.valid)(&self.key) {
                Some("画像总结的真实请求已停止，结果未应用".into())
            } else if !response.turn.tool_calls.is_empty() {
                Some("画像返回了未提供的工具调用，未执行或应用".into())
            } else {
                response.error.clone()
            };
            billing.error = request_error.clone();
            let billing_error = ledger.save(&billing).err();
            let retry = sent
                && billing_error.is_none()
                && !output_exceeded
                && response.error.is_some()
                && budget.can_admit()
                && response.retry.is_some_and(|cause| {
                    cause.allows(
                        policy,
                        attempt,
                        self.scope.cancel.is_cancelled()
                            || cancel.is_cancelled()
                            || !(self.scope.valid)(&self.key),
                    )
                });
            if !retry {
                let operations = if request_error.is_none() {
                    response.turn.text
                } else {
                    String::new()
                };
                return Ok(ResultRecord {
                    key: self.key,
                    expected: self.expected,
                    operations,
                    request_error,
                    billing,
                    billing_error,
                });
            }
            let delay = response.retry.expect("typed profile retry").delay(policy);
            let wait = network::retry::wait(delay, &self.scope.cancel, &cancel);
            tokio::pin!(wait);
            let resumed = loop {
                tokio::select! {biased;
                    ready=&mut wait=>break ready,
                    _=tick.tick()=>{if !(self.scope.valid)(&self.key){self.scope.cancel.cancel();}},
                }
            };
            if !resumed {
                return Ok(ResultRecord {
                    key: self.key,
                    expected: self.expected,
                    operations: String::new(),
                    request_error: Some("画像重试等待已停止，未再发送请求".into()),
                    billing,
                    billing_error: None,
                });
            }
        }
    }
}
pub fn apply(
    repository: &mut Repository,
    result: &ResultRecord,
    actual_key: &Key,
    scope_live: bool,
    at: i64,
) -> Result<profile::SummaryOutcome, String> {
    if &result.key != actual_key || !scope_live {
        return Err("画像结果的操作身份或取消范围已过期".into());
    }
    if let Some(error) = &result.billing_error {
        return Err(format!("画像请求账单尚未持久化：{error}"));
    }
    if let Some(error) = &result.request_error {
        return Err(error.clone());
    }
    repository.apply_summary(&result.expected, &result.operations, at)
}

const SYSTEM_PROMPT: &str = "你是用户画像整理器。目标是一份了解这个人的长期画像，不是他的操作记录。只输出变更行，现有画像由程序保管，不要重写整篇画像。每行一条：+ 文本；+ [小节名] 文本；- 要删除的逐字匹配条目；~ 旧文本 => 新文本。按沟通风格与语气偏好、性格特点、长期目标、兴趣爱好、工作与生活背景、明确偏好与禁忌组织新增内容。不要写一次任务的细节、临时安排、工具调用或结果、琐事和单次问答。中文，每条一句话，全篇不超过600字，最多12条。没有关于用户的新信息时输出空回复。";
