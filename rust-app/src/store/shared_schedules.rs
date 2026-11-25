use super::*;
use crate::schedules::{Schedule, validate_board};
use serde_json::Value;

const BOARD_LIMIT: u64 = 16 * 1024 * 1024;
const EXECUTION_LIMIT: u64 = 2 * BOARD_LIMIT;
const BOARD: &str = "schedules.json";
const EXECUTION: &str = "schedule-execution.json";

#[derive(Deserialize, Serialize, PartialEq)]
struct Row {
    #[serde(flatten)]
    schedule: Schedule,
    #[serde(flatten)]
    extra: BTreeMap<String, Value>,
}

#[derive(Deserialize, Serialize)]
struct Pending {
    id: String,
    previous: Option<Vec<Row>>,
}

#[derive(Deserialize, Serialize)]
struct Sidecar<T> {
    version: u32,
    execution: T,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pending: Option<Pending>,
}

fn staged(root: &Path, id: &str) -> PathBuf {
    root.join(format!(".schedule-{id}.json"))
}

fn publish(root: &Path, staged: &Path, files: &Path) -> Result<(), String> {
    let path = files.join(BOARD);
    fs::rename(staged, &path).map_err(|error| fs_error("替换共用提醒列表", &path, error))?;
    sync_directory(files).map_err(|error| fs_error("同步共用提醒目录", files, error))?;
    sync_directory(root).map_err(|error| fs_error("同步提醒事务目录", root, error))
}

pub(super) fn load(
    root: &Path,
    files: &Path,
) -> Result<(Vec<Schedule>, ScheduleExecution), String> {
    let mut rows: Option<Vec<Row>> = optional_json(files, &files.join(BOARD), BOARD_LIMIT)?;
    let state: Option<Sidecar<ScheduleExecution>> =
        optional_json(root, &root.join(EXECUTION), EXECUTION_LIMIT)?;
    let execution = if let Some(state) = state {
        version(state.version)?;
        state.execution.validate()?;
        if let Some(pending) = state.pending {
            validate_id(&pending.id)?;
            let source = staged(root, &pending.id);
            if let Some(next) = optional_json::<Vec<Row>>(root, &source, BOARD_LIMIT)? {
                if rows == pending.previous {
                    let schedules: Vec<_> = next.iter().map(|row| row.schedule.clone()).collect();
                    validate_board(&schedules)?;
                    publish(root, &source, files)?;
                    rows = Some(next);
                } else {
                    // Compose wrote a newer board while this publication was pending.
                    fs::remove_file(&source)
                        .map_err(|error| fs_error("移除已过期的提醒暂存", &source, error))?;
                    sync_directory(root)
                        .map_err(|error| fs_error("同步提醒事务目录", root, error))?;
                }
            }
        }
        state.execution
    } else {
        let (legacy, execution) = super::schedule_runtime::load_private(root)?;
        if rows.is_none() && !legacy.is_empty() {
            save(root, files, &[], &legacy, &execution).map_err(|failure| failure.message)?;
            return Ok((legacy, execution));
        }
        execution
    };
    let schedules: Vec<_> = rows
        .unwrap_or_default()
        .into_iter()
        .map(|row| row.schedule)
        .collect();
    validate_board(&schedules)?;
    Ok((schedules, execution))
}

pub(super) fn save(
    root: &Path,
    files: &Path,
    previous: &[Schedule],
    schedules: &[Schedule],
    execution: &ScheduleExecution,
) -> Result<(), CommitFailure> {
    let mut state = Sidecar {
        version: VERSION,
        execution,
        pending: None,
    };
    let path = root.join(EXECUTION);
    if schedules == previous {
        return atomic_json(root, &path, &state, EXECUTION_LIMIT);
    }
    let rows: Option<Vec<Row>> = optional_json(files, &files.join(BOARD), BOARD_LIMIT)?;
    let old = rows.as_deref().unwrap_or_default();
    if !old.iter().map(|row| &row.schedule).eq(previous) {
        return Err("共用提醒列表已经变化，请重新打开应用后再修改"
            .to_owned()
            .into());
    }
    let extras: HashMap<_, _> = old
        .iter()
        .map(|row| (&row.schedule.id, &row.extra))
        .collect();
    let next: Vec<_> = schedules
        .iter()
        .map(|schedule| Row {
            schedule: schedule.clone(),
            extra: extras
                .get(&schedule.id)
                .map_or_else(BTreeMap::new, |extra| (*extra).clone()),
        })
        .collect();
    let id = fresh_id("reminder")?;
    let source = staged(root, &id);
    atomic_json(root, &source, &next, BOARD_LIMIT)?;
    state.pending = Some(Pending { id, previous: rows });
    if let Err(failure) = atomic_json(root, &path, &state, EXECUTION_LIMIT) {
        if !failure.replaced {
            let _ = fs::remove_file(&source);
        }
        return Err(failure);
    }
    // Moving the staged list also clears the recovery marker. Only the sidecar
    // contains runtime claims and a claim cannot run until the move succeeds.
    publish(root, &source, files).map_err(|message| CommitFailure {
        message,
        replaced: true,
    })
}
