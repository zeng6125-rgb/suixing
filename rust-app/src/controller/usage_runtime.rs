//! Exact page/query identity and one bounded statistics worker. The actor
//! captures the owning Store inputs; JNI calendar and archive IO run off draw.
use crate::{
    legacy_usage::{Filter, Report},
    store::{Store, usage::UsageInput},
};
use std::{
    sync::{
        Arc,
        mpsc::{self, Receiver, SyncSender, TryRecvError},
    },
    thread,
    time::{SystemTime, UNIX_EPOCH},
};
#[derive(Clone, Debug)]
pub enum Request {
    Open {
        request_id: String,
        ui_token: String,
    },
    Query {
        request_id: String,
        ui_token: String,
        zone_id: String,
        filter: Filter,
    },
}
impl Request {
    pub fn identity(&self) -> (&str, &str) {
        match self {
            Self::Open {
                request_id,
                ui_token,
            }
            | Self::Query {
                request_id,
                ui_token,
                ..
            } => (request_id, ui_token),
        }
    }
}
#[derive(Clone, Debug)]
pub enum Update {
    Ready {
        request_id: String,
        ui_token: String,
        today: String,
        zone_id: String,
    },
    Report {
        request_id: String,
        ui_token: String,
        report: Arc<Report>,
    },
    Failed {
        request_id: String,
        ui_token: String,
        error: String,
    },
}
impl Update {
    pub fn identity(&self) -> (&str, &str) {
        match self {
            Self::Ready {
                request_id,
                ui_token,
                ..
            }
            | Self::Report {
                request_id,
                ui_token,
                ..
            }
            | Self::Failed {
                request_id,
                ui_token,
                ..
            } => (request_id, ui_token),
        }
    }
    pub(crate) fn bytes(&self) -> usize {
        let (id, token) = self.identity();
        id.len()
            + token.len()
            + match self {
                Self::Ready { today, zone_id, .. } => today.len() + zone_id.len(),
                Self::Report { report, .. } => {
                    serde_json::to_vec(report.as_ref()).map_or(2 * 1024 * 1024, |bytes| bytes.len())
                }
                Self::Failed { error, .. } => error.len(),
            }
    }
}
struct Job {
    request: Request,
    input: UsageInput,
}
struct Reply {
    namespace: std::path::PathBuf,
    update: Update,
}
pub(crate) struct UsageRuntime {
    jobs: SyncSender<Job>,
    replies: Receiver<Reply>,
}
impl UsageRuntime {
    pub(crate) fn boot() -> Result<Self, String> {
        let (jobs, receive) = mpsc::sync_channel::<Job>(1);
        let (output, replies) = mpsc::sync_channel::<Reply>(2);
        thread::Builder::new()
            .name("rust-usage".into())
            .spawn(move || {
                while let Ok(job) = receive.recv() {
                    let namespace = job.input.namespace().to_owned();
                    let (request, token) = job.request.identity();
                    let identity = (request.to_owned(), token.to_owned());
                    let result = execute(job);
                    let update = result.unwrap_or_else(|error| Update::Failed {
                        request_id: identity.0,
                        ui_token: identity.1,
                        error,
                    });
                    if output.send(Reply { namespace, update }).is_err() {
                        break;
                    }
                }
            })
            .map_err(|_| "用量统计线程无法启动")?;
        Ok(Self { jobs, replies })
    }
    pub(crate) fn dispatch(&self, request: Request, store: &Store) -> Result<(), String> {
        let (id, token) = request.identity();
        if id.is_empty()
            || id.len() > 512
            || token.is_empty()
            || token.len() > 256
            || id.contains('\0')
            || token.contains('\0')
        {
            return Err("用量页面请求身份无效".into());
        }
        self.jobs
            .try_send(Job {
                request,
                input: store.usage_input(),
            })
            .map_err(|_| "已有用量查询，请稍后重试".into())
    }
    pub(crate) fn poll(&self, store: &Store) -> Vec<Update> {
        let mut updates = Vec::new();
        for _ in 0..2 {
            match self.replies.try_recv() {
                Ok(reply) => {
                    if reply.namespace.to_string_lossy() == store.active_namespace() {
                        updates.push(reply.update);
                    } else {
                        let (id, token) = reply.update.identity();
                        updates.push(Update::Failed {
                            request_id: id.into(),
                            ui_token: token.into(),
                            error: "用量活动存储已切换，请重新查询".into(),
                        });
                    }
                }
                Err(TryRecvError::Empty | TryRecvError::Disconnected) => break,
            }
        }
        updates
    }
}
fn execute(job: Job) -> Result<Update, String> {
    match job.request {
        Request::Open {
            request_id,
            ui_token,
        } => {
            let zone_id = crate::platform::calendar::zone_id()?;
            let at = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_err(|_| "系统日期早于epoch")?
                .as_millis();
            let stamp = crate::platform::calendar::day_for(
                i64::try_from(at).map_err(|_| "系统日期超范围")?,
                &zone_id,
            )?;
            Ok(Update::Ready {
                request_id,
                ui_token,
                today: stamp.day,
                zone_id,
            })
        }
        Request::Query {
            request_id,
            ui_token,
            zone_id,
            filter,
        } => {
            if filter.dates.len() > 366
                || filter
                    .model
                    .as_ref()
                    .is_some_and(|value| value.len() > 512 || value.contains('\0'))
                || filter
                    .chat_id
                    .as_ref()
                    .is_some_and(|value| value.len() > 512 || value.contains('\0'))
            {
                return Err("用量日期或筛选条件超过预算".into());
            }
            for date in &filter.dates {
                crate::legacy_usage::day_valid(date)?;
            }
            // Validate the exact requested real ZoneId even for an empty ledger.
            crate::platform::calendar::day_for(0, &zone_id)?;
            let projection = job.input.project(&zone_id)?;
            let mut cache = std::collections::BTreeMap::new();
            // Per-timestamp java.time conversion is cached without substituting
            // a fixed offset; duplicate timestamps share the real historical result.
            for request in &projection.requests {
                if !request.record.summary && !cache.contains_key(&request.record.at) {
                    let stamp = crate::platform::calendar::day_for(request.record.at, &zone_id)?;
                    cache.insert(
                        request.record.at,
                        crate::legacy_usage::DayStamp {
                            zone_id: stamp.zone_id,
                            day: stamp.day,
                        },
                    );
                }
            }
            let mut report = crate::legacy_usage::report(
                &projection.legacy,
                &projection.requests,
                &filter,
                &zone_id,
                |at| {
                    cache
                        .get(&at)
                        .cloned()
                        .ok_or_else(|| "请求时间未经过实际日历转换".into())
                },
            )?;
            report
                .warnings
                .push("真实请求采用查询受理时的持久账本快照；之后完成的请求请重新查询".into());
            if serde_json::to_vec(&report)
                .map_err(|_| "用量结果无法编码")?
                .len()
                > 2 * 1024 * 1024
            {
                return Err("用量结果超过2 MiB，请缩小范围".into());
            }
            Ok(Update::Report {
                request_id,
                ui_token,
                report: Arc::new(report),
            })
        }
    }
}
