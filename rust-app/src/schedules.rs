//! Reminder data and plans. The owner commits these mutations atomically and then
//! arms Android; a returned plan does not mean a system alarm or service exists.
use crate::state::ToolCall;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use sha2::{Digest, Sha256};
use suixing_search::rust_protocol::ToolDefinition;

pub const AT: &str = "at";
pub const EVERY_SECONDS: &str = "every_seconds";
pub const DAILY: &str = "daily";
const MAX_ITEMS: usize = 1024;
const MAX_BOARD_BYTES: usize = 8 * 1024 * 1024;

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct Schedule {
    pub id: String,
    pub title: String,
    pub prompt: String,
    pub kind: String,
    #[serde(default, rename = "atMillis")]
    pub at_millis: i64,
    #[serde(default, rename = "everySeconds")]
    pub every_seconds: i64,
    #[serde(default, rename = "timeOfDay")]
    pub time_of_day: String,
    #[serde(default, rename = "nextAt")]
    pub next_at: i64,
    #[serde(default = "enabled_default")]
    pub enabled: bool,
    #[serde(default, rename = "missedAt")]
    pub missed_at: i64,
}

fn enabled_default() -> bool {
    true
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Timing {
    At(String),
    EverySeconds(i64),
    Daily(String),
}

#[derive(Clone, Debug)]
pub enum Operation {
    List,
    Create {
        id: String,
        title: String,
        prompt: String,
        timing: Timing,
    },
    Update {
        id: String,
        title: Option<String>,
        prompt: Option<String>,
        timing: Option<Timing>,
    },
    SetEnabled {
        id: String,
        enabled: bool,
    },
    Delete {
        id: String,
    },
    ExpireMissed,
    /// The captured firing generation. Finishing never inserts a deleted reminder
    /// or overwrites timing edited while its conversation was running.
    FinishFire {
        fired: Schedule,
    },
}

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct Plan {
    pub schedule_id: String,
    pub at_millis: i64,
}

#[derive(Clone, Debug)]
pub struct Mutation {
    pub schedules: Vec<Schedule>,
    pub plan: Option<Plan>,
    pub selected: Option<Schedule>,
    pub changed: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd)]
pub struct CivilTime {
    pub year: i32,
    pub month: u32,
    pub day: u32,
    pub hour: u32,
    pub minute: u32,
    pub second: u32,
}

/// Resolves each civil date with the system timezone rules for that date. An
/// implementation must not extrapolate the current UTC offset across DST.
pub trait LocalClock {
    fn local(&self, epoch_millis: i64) -> Result<CivilTime, String>;
    fn resolve(&self, civil: CivilTime) -> Result<i64, String>;
}

pub struct SystemLocalClock;

impl LocalClock for SystemLocalClock {
    fn local(&self, epoch_millis: i64) -> Result<CivilTime, String> {
        #[cfg(unix)]
        {
            let _guard = unix_clock::LOCK.lock().map_err(|_| "本地时区锁不可用")?;
            unix_clock::refresh();
            unix_clock::local(epoch_millis)
        }
        #[cfg(not(unix))]
        {
            let _ = epoch_millis;
            Err("当前平台尚未提供系统本地时区，请接入 LocalClock".into())
        }
    }

    fn resolve(&self, civil: CivilTime) -> Result<i64, String> {
        validate_civil(civil)?;
        #[cfg(unix)]
        {
            let _guard = unix_clock::LOCK.lock().map_err(|_| "本地时区锁不可用")?;
            unix_clock::refresh();
            unix_clock::resolve(civil)
        }
        #[cfg(not(unix))]
        {
            Err("当前平台尚未提供系统本地时区，请接入 LocalClock".into())
        }
    }
}

impl Schedule {
    pub fn validate(&self) -> Result<(), String> {
        bounded(&self.id, 128, "提醒 id")?;
        bounded(&self.title, 4096, "提醒标题")?;
        bounded(&self.prompt, 64 * 1024, "提醒内容")?;
        bounded(&self.kind, 32, "提醒类型")?;
        bounded(&self.time_of_day, 32, "每日提醒时间")?;
        if self.id.trim().is_empty()
            || self.title.trim().is_empty()
            || self.prompt.trim().is_empty()
        {
            return Err("提醒缺少 id、标题或内容".into());
        }
        if [
            self.at_millis,
            self.every_seconds,
            self.next_at,
            self.missed_at,
        ]
        .iter()
        .any(|v| *v < 0)
        {
            return Err("提醒时间字段不能为负数".into());
        }
        match self.kind.as_str() {
            AT if self.at_millis > 0 => Ok(()),
            EVERY_SECONDS
                if self.every_seconds >= 60 && self.every_seconds.checked_mul(1000).is_some() =>
            {
                Ok(())
            }
            DAILY => parse_time(&self.time_of_day).map(|_| ()),
            _ => Err("提醒时机类型或参数无效".into()),
        }
    }

    pub fn next_trigger_after(
        &self,
        now: i64,
        clock: &dyn LocalClock,
    ) -> Result<Option<i64>, String> {
        self.validate()?;
        if now < 0 {
            return Err("当前时间无效".into());
        }
        match self.kind.as_str() {
            AT => Ok((self.at_millis > now).then_some(self.at_millis)),
            EVERY_SECONDS => self
                .every_seconds
                .checked_mul(1000)
                .and_then(|v| now.checked_add(v))
                .map(Some)
                .ok_or_else(|| "提醒间隔或下一次时间超出范围".into()),
            DAILY => {
                let (hour, minute, second) = parse_time(&self.time_of_day)?;
                let local = clock.local(now)?;
                validate_civil(local)?;
                let mut candidate = CivilTime {
                    hour,
                    minute,
                    second,
                    ..local
                };
                // This comparison matches the previous java.time civil-date choice.
                // The resolver then applies the rules of the chosen date, including
                // an overlap's earlier instant and a gap's forward normalization.
                if candidate <= local {
                    candidate = next_day(candidate)?;
                }
                let resolved = clock.resolve(candidate)?;
                // During the second occurrence of an overlap, the earlier
                // offset can put today's selected civil time in the past.
                // A next-trigger plan must still be strictly in the future.
                let resolved = if resolved > now {
                    resolved
                } else {
                    clock.resolve(next_day(candidate)?)?
                };
                if resolved <= now {
                    return Err("系统时区未返回将来的每日触发时刻".into());
                }
                Ok(Some(resolved))
            }
            _ => Err("未知提醒时机类型".into()),
        }
    }
}

pub fn validate_board(board: &[Schedule]) -> Result<(), String> {
    if board.len() > MAX_ITEMS {
        return Err("提醒数量超过1024条".into());
    }
    let mut ids = std::collections::HashSet::new();
    let mut bytes = 0usize;
    for schedule in board {
        schedule.validate()?;
        if !ids.insert(schedule.id.as_str()) {
            return Err("提醒 id 重复".into());
        }
        bytes = bytes
            .checked_add(
                schedule.id.len()
                    + schedule.title.len()
                    + schedule.prompt.len()
                    + schedule.time_of_day.len()
                    + 256,
            )
            .ok_or("提醒列表超过存储预算")?;
        if bytes > MAX_BOARD_BYTES {
            return Err("提醒列表超过8 MiB预算".into());
        }
    }
    Ok(())
}

/// Pure read/modify/plan over the latest board. The caller persists only a
/// successful result, never a UI's old whole-list snapshot.
pub fn apply(
    board: &[Schedule],
    operation: &Operation,
    now: i64,
    clock: &dyn LocalClock,
) -> Result<Mutation, String> {
    if now < 0 {
        return Err("当前时间无效".into());
    }
    validate_board(board)?;
    let mut schedules = board.to_vec();
    let mut selected = None;
    match operation {
        Operation::List | Operation::ExpireMissed => {}
        Operation::Create {
            id,
            title,
            prompt,
            timing,
        } => {
            let mut item = Schedule {
                id: id.clone(),
                title: title.trim().into(),
                prompt: prompt.trim().into(),
                kind: String::new(),
                at_millis: 0,
                every_seconds: 0,
                time_of_day: String::new(),
                next_at: 0,
                enabled: true,
                missed_at: 0,
            };
            set_timing(&mut item, timing, clock)?;
            item.validate()?;
            if let Some(existing) = schedules.iter().find(|s| s.id == item.id) {
                // A repeated prepared creation is a no-op only when its contents
                // match; never overwrite an unrelated reminder using a reused ID.
                if existing.title != item.title
                    || existing.prompt != item.prompt
                    || !same_timing(existing, &item)
                {
                    return Err("提醒 id 已存在且内容不同".into());
                }
                selected = Some(existing.clone());
            } else {
                item.next_at = item
                    .next_trigger_after(now, clock)?
                    .ok_or("这个提醒时间已经过去，请给一个将来的时刻")?;
                selected = Some(item.clone());
                schedules.push(item);
            }
        }
        Operation::Update {
            id,
            title,
            prompt,
            timing,
        } => {
            let item = schedules
                .iter_mut()
                .find(|s| s.id == *id)
                .ok_or("没有这个提醒，请先查看提醒列表")?;
            if let Some(title) = title {
                if !title.trim().is_empty() {
                    item.title = title.trim().into();
                }
            }
            if let Some(prompt) = prompt {
                if !prompt.trim().is_empty() {
                    item.prompt = prompt.trim().into();
                }
            }
            if let Some(timing) = timing {
                set_timing(item, timing, clock)?;
            }
            item.validate()?;
            let next = item.next_trigger_after(now, clock)?;
            if timing.is_some() && next.is_none() {
                return Err("这个时间已经过去了，提醒没有改动".into());
            }
            item.next_at = next.unwrap_or(0);
            if timing.is_some() && item.missed_at > 0 {
                item.enabled = true;
                item.missed_at = 0;
            }
            selected = Some(item.clone());
        }
        Operation::SetEnabled { id, enabled } => {
            let item = schedules
                .iter_mut()
                .find(|s| s.id == *id)
                .ok_or("没有这个提醒，请先查看提醒列表")?;
            if *enabled && (item.missed_at > 0 || (item.kind == AT && item.at_millis <= now)) {
                return Err("这条提醒已经过期，请先修改到将来的时刻".into());
            }
            item.enabled = *enabled;
            if *enabled {
                item.next_at = item.next_trigger_after(now, clock)?.unwrap_or(0);
            }
            selected = Some(item.clone());
        }
        Operation::Delete { id } => {
            let index = schedules
                .iter()
                .position(|s| s.id == *id)
                .ok_or("没有这个提醒，请先查看提醒列表")?;
            selected = Some(schedules.remove(index));
        }
        Operation::FinishFire { fired } => {
            fired.validate()?;
            if let Some(index) = schedules.iter().position(|s| s.id == fired.id) {
                if same_timing(&schedules[index], fired) {
                    if fired.kind == AT {
                        selected = Some(schedules.remove(index));
                    } else if schedules[index].enabled {
                        schedules[index].next_at = schedules[index]
                            .next_trigger_after(now, clock)?
                            .unwrap_or(0);
                        selected = Some(schedules[index].clone());
                    }
                }
            }
        }
    }
    // Sweeping belongs to the same mutation as rearming. One-shots missed while
    // the device was off are disabled rather than delivered hours late.
    for item in &mut schedules {
        if matches!(operation, Operation::ExpireMissed)
            && item.enabled
            && item.kind == AT
            && item.at_millis <= now
        {
            item.enabled = false;
            item.missed_at = now;
        }
    }
    validate_board(&schedules)?;
    let mut plan: Option<Plan> = None;
    for item in schedules.iter().filter(|s| s.enabled) {
        let next = if item.next_at > now {
            Some(item.next_at)
        } else {
            item.next_trigger_after(now, clock)?
        };
        if let Some(at_millis) = next {
            if plan.as_ref().is_none_or(|p| at_millis < p.at_millis) {
                plan = Some(Plan {
                    schedule_id: item.id.clone(),
                    at_millis,
                });
            }
        }
    }
    if let Some(item) = selected.as_ref() {
        if let Some(current) = schedules.iter().find(|s| s.id == item.id) {
            selected = Some(current.clone());
        }
    }
    Ok(Mutation {
        changed: schedules.as_slice() != board,
        schedules,
        plan,
        selected,
    })
}

fn same_timing(left: &Schedule, right: &Schedule) -> bool {
    left.kind == right.kind
        && left.at_millis == right.at_millis
        && left.every_seconds == right.every_seconds
        && left.time_of_day == right.time_of_day
}

fn set_timing(item: &mut Schedule, timing: &Timing, clock: &dyn LocalClock) -> Result<(), String> {
    item.at_millis = 0;
    item.every_seconds = 0;
    item.time_of_day.clear();
    match timing {
        Timing::At(text) => {
            item.kind = AT.into();
            item.at_millis = clock.resolve(parse_moment(text)?)?;
        }
        Timing::EverySeconds(seconds) => {
            if *seconds < 60 {
                return Err("提醒间隔至少60秒".into());
            }
            item.kind = EVERY_SECONDS.into();
            item.every_seconds = *seconds;
        }
        Timing::Daily(text) => {
            parse_time(text)?;
            item.kind = DAILY.into();
            item.time_of_day = text.trim().into();
        }
    }
    Ok(())
}

pub fn definitions() -> Vec<ToolDefinition> {
    let timing = json!({"at":{"type":"string","description":"One-shot local yyyy-MM-dd HH:mm[:ss]"},
        "every_seconds":{"type":"integer","minimum":60},"daily":{"type":"string","description":"Daily local HH:mm[:ss]"}});
    let mut create = timing.as_object().unwrap().clone();
    create.insert("title".into(), json!({"type":"string"}));
    create.insert("prompt".into(), json!({"type":"string"}));
    let mut update = create.clone();
    update.insert("id".into(), json!({"type":"string"}));
    let definition = |name: &str, description: &str, properties: Value, required: &[&str]| {
        ToolDefinition {
            name: name.into(),
            description: description.into(),
            parameters: json!({"type":"object","properties":properties,"required":required,"additionalProperties":false}),
        }
    };
    vec![
        definition(
            "schedule_create",
            "Save a reminder and compute a plan. Supply exactly one timing argument. Android background alarms/services may be unavailable; a saved plan does not establish that the reminder will execute. Requires approval.",
            Value::Object(create),
            &["title", "prompt"],
        ),
        definition(
            "schedule_list",
            "List saved reminders, timing, enabled/missed state and stored next time. Background execution capability is separate. Requires approval.",
            json!({}),
            &[],
        ),
        definition(
            "schedule_update",
            "Update reminder title/prompt or exactly one timing argument. No timing preserves the existing kind. A new future timing revives a missed reminder, but keeps an explicitly paused one paused. Requires approval.",
            Value::Object(update),
            &["id"],
        ),
        definition(
            "schedule_delete",
            "Delete a saved reminder; the owner must rearm or cancel its system alarm if that integration exists. Requires approval.",
            json!({"id":{"type":"string"}}),
            &["id"],
        ),
    ]
}

pub fn prepare(call: &ToolCall) -> Result<Operation, String> {
    if call.arguments.len() > 128 * 1024 {
        return Err("提醒参数超过128 KiB".into());
    }
    let value: Value = serde_json::from_str(&call.arguments).map_err(|_| "提醒参数不是有效JSON")?;
    let args = value.as_object().ok_or("提醒参数必须是对象")?;
    let allowed: &[&str] = match call.name.as_str() {
        "schedule_create" => &["title", "prompt", "at", "every_seconds", "daily"],
        "schedule_update" => &["id", "title", "prompt", "at", "every_seconds", "daily"],
        "schedule_list" => &[],
        "schedule_delete" => &["id"],
        _ => return Err("未知提醒工具".into()),
    };
    if args.keys().any(|k| !allowed.contains(&k.as_str())) {
        return Err("不支持的提醒参数".into());
    }
    match call.name.as_str() {
        "schedule_list" => Ok(Operation::List),
        "schedule_delete" => Ok(Operation::Delete {
            id: required_text(args, "id", 128)?,
        }),
        "schedule_create" => {
            bounded(&call.id, 1024, "工具调用 id")?;
            if call.id.is_empty() {
                return Err("提醒创建缺少工具调用身份".into());
            }
            let mut digest = Sha256::new();
            digest.update(call.id.as_bytes());
            digest.update([0]);
            digest.update(call.arguments.as_bytes());
            let id = format!("r{:x}", digest.finalize());
            Ok(Operation::Create {
                id,
                title: required_text(args, "title", 4096)?,
                prompt: required_text(args, "prompt", 64 * 1024)?,
                timing: read_timing(args)?
                    .ok_or("缺少时机参数，请选择at、every_seconds或daily中的一项")?,
            })
        }
        _ => Ok(Operation::Update {
            id: required_text(args, "id", 128)?,
            title: optional_text(args, "title", 4096)?,
            prompt: optional_text(args, "prompt", 64 * 1024)?,
            timing: read_timing(args)?,
        }),
    }
}

fn read_timing(args: &Map<String, Value>) -> Result<Option<Timing>, String> {
    let supplied: Vec<&str> = ["at", "every_seconds", "daily"]
        .into_iter()
        .filter(|k| args.contains_key(*k))
        .collect();
    if supplied.len() > 1 {
        return Err("时机参数只能选择一项".into());
    }
    match supplied.first().copied() {
        None => Ok(None),
        Some("every_seconds") => Ok(Some(Timing::EverySeconds(
            args["every_seconds"]
                .as_i64()
                .filter(|v| *v >= 60 && v.checked_mul(1000).is_some())
                .ok_or("提醒间隔必须是至少60秒的整数")?,
        ))),
        Some("at") => {
            let text = required_text(args, "at", 32)?;
            parse_moment(&text)?;
            Ok(Some(Timing::At(text)))
        }
        Some(_) => {
            let text = required_text(args, "daily", 16)?;
            parse_time(&text)?;
            Ok(Some(Timing::Daily(text)))
        }
    }
}

fn required_text(args: &Map<String, Value>, name: &str, max: usize) -> Result<String, String> {
    optional_text(args, name, max)?
        .filter(|v| !v.is_empty())
        .ok_or_else(|| format!("缺少参数 {name}"))
}

fn optional_text(
    args: &Map<String, Value>,
    name: &str,
    max: usize,
) -> Result<Option<String>, String> {
    args.get(name)
        .map(|value| {
            let text = value
                .as_str()
                .ok_or_else(|| format!("参数{name}必须是字符串"))?;
            bounded(text, max, name)?;
            Ok(text.trim().to_owned())
        })
        .transpose()
}

fn bounded(text: &str, max: usize, name: &str) -> Result<(), String> {
    if text.len() > max || text.contains('\0') {
        return Err(format!("{name}超过预算或包含NUL"));
    }
    Ok(())
}

fn parse_time(text: &str) -> Result<(u32, u32, u32), String> {
    let text = text.trim().as_bytes();
    if !matches!(text.len(), 5 | 8) || text[2] != b':' || (text.len() == 8 && text[5] != b':') {
        return Err("时间格式应为HH:mm或HH:mm:ss".into());
    }
    let hour = digits(&text[..2])?;
    let minute = digits(&text[3..5])?;
    let second = if text.len() == 8 {
        digits(&text[6..8])?
    } else {
        0
    };
    if hour > 24 || minute > 59 || second > 59 || (hour == 24 && (minute != 0 || second != 0)) {
        return Err("时间超出有效范围".into());
    }
    // The previous DateTimeFormatter uses SMART resolution: 24:00 is
    // midnight. LocalTime discards the excess day; LocalDateTime retains it.
    Ok((hour % 24, minute, second))
}

fn parse_moment(text: &str) -> Result<CivilTime, String> {
    let text = text.trim();
    let bytes = text.as_bytes();
    if !matches!(bytes.len(), 16 | 19)
        || bytes[4] != b'-'
        || bytes[7] != b'-'
        || bytes[10] != b' '
        || !text.is_ascii()
    {
        return Err("时刻格式应为yyyy-MM-dd HH:mm或yyyy-MM-dd HH:mm:ss".into());
    }
    let (hour, minute, second) = parse_time(&text[11..])?;
    let mut civil = CivilTime {
        year: digits(&bytes[..4])? as i32,
        month: digits(&bytes[5..7])?,
        day: digits(&bytes[8..10])?,
        hour,
        minute,
        second,
    };
    if !(1..=31).contains(&civil.day) {
        return Err("本地日期无效".into());
    }
    // SMART yyyy-MM-dd clamps e.g. February 30 to the final valid day;
    // preserve this parsing behavior without permitting invalid month/day 0.
    civil.day = civil.day.min(days_in_month(civil.year, civil.month));
    validate_civil(civil)?;
    if &bytes[11..13] == b"24" {
        civil = next_day(civil)?;
    }
    Ok(civil)
}

fn digits(bytes: &[u8]) -> Result<u32, String> {
    bytes.iter().try_fold(0u32, |value, b| {
        if b.is_ascii_digit() {
            Ok(value * 10 + u32::from(b - b'0'))
        } else {
            Err("日期时间必须使用数字".into())
        }
    })
}

fn days_in_month(year: i32, month: u32) -> u32 {
    match month {
        4 | 6 | 9 | 11 => 30,
        2 if year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) => 29,
        2 => 28,
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        _ => 0,
    }
}

fn validate_civil(civil: CivilTime) -> Result<(), String> {
    if !(1970..=9999).contains(&civil.year)
        || !(1..=12).contains(&civil.month)
        || civil.day == 0
        || civil.day > days_in_month(civil.year, civil.month)
        || civil.hour > 23
        || civil.minute > 59
        || civil.second > 59
    {
        return Err("本地日期时间无效或超出1970至9999年范围".into());
    }
    Ok(())
}

fn next_day(mut civil: CivilTime) -> Result<CivilTime, String> {
    civil.day += 1;
    if civil.day > days_in_month(civil.year, civil.month) {
        civil.day = 1;
        civil.month += 1;
        if civil.month > 12 {
            civil.month = 1;
            civil.year += 1;
        }
    }
    validate_civil(civil)?;
    Ok(civil)
}

#[cfg(unix)]
mod unix_clock {
    use super::*;
    pub static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    unsafe extern "C" {
        fn tzset();
    }
    // LP32 bionic time_t is 32-bit; its explicit 64-bit API keeps reminders
    // beyond 2038 representable. LP64 uses the ordinary 64-bit time_t APIs.
    #[cfg(all(target_os = "android", target_pointer_width = "32"))]
    unsafe extern "C" {
        fn localtime64_r(time: *const i64, result: *mut libc::tm) -> *mut libc::tm;
        fn mktime64(time: *const libc::tm) -> i64;
    }
    pub fn refresh() {
        unsafe {
            tzset();
        }
    }
    pub fn local(epoch_millis: i64) -> Result<CivilTime, String> {
        if epoch_millis < 0 {
            return Err("提醒时刻不能早于Unix纪元".into());
        }
        let seconds = epoch_millis / 1000;
        let mut tm: libc::tm = unsafe { std::mem::zeroed() };
        #[cfg(all(target_os = "android", target_pointer_width = "32"))]
        let result = unsafe { localtime64_r(&seconds, &mut tm) };
        #[cfg(not(all(target_os = "android", target_pointer_width = "32")))]
        let result = {
            let seconds: libc::time_t = seconds
                .try_into()
                .map_err(|_| "系统时间类型不能表示提醒时刻")?;
            unsafe { libc::localtime_r(&seconds, &mut tm) }
        };
        if result.is_null() {
            return Err("系统本地时区转换失败".into());
        }
        let civil = CivilTime {
            year: tm.tm_year.checked_add(1900).ok_or("本地年份溢出")?,
            month: (tm.tm_mon + 1).try_into().map_err(|_| "本地月份无效")?,
            day: tm.tm_mday.try_into().map_err(|_| "本地日期无效")?,
            hour: tm.tm_hour.try_into().map_err(|_| "本地小时无效")?,
            minute: tm.tm_min.try_into().map_err(|_| "本地分钟无效")?,
            second: tm.tm_sec.try_into().map_err(|_| "本地秒数无效")?,
        };
        validate_civil(civil)?;
        Ok(civil)
    }
    pub fn resolve(civil: CivilTime) -> Result<i64, String> {
        let mut exact = Vec::new();
        let mut forward = Vec::new();
        let mut seeds = Vec::new();
        for is_dst in [-1, 0, 1] {
            let mut tm: libc::tm = unsafe { std::mem::zeroed() };
            tm.tm_year = civil.year - 1900;
            tm.tm_mon = civil.month as i32 - 1;
            tm.tm_mday = civil.day as i32;
            tm.tm_hour = civil.hour as i32;
            tm.tm_min = civil.minute as i32;
            tm.tm_sec = civil.second as i32;
            tm.tm_isdst = is_dst;
            #[cfg(all(target_os = "android", target_pointer_width = "32"))]
            let seconds = unsafe { mktime64(&tm) };
            #[cfg(not(all(target_os = "android", target_pointer_width = "32")))]
            let seconds = unsafe { libc::mktime(&mut tm) } as i64;
            if seconds < 0 {
                continue;
            }
            let Some(millis) = seconds.checked_mul(1000) else {
                continue;
            };
            seeds.push(millis);
            let Ok(normalized) = local(millis) else {
                continue;
            };
            if normalized == civil {
                exact.push(millis);
            } else if normalized > civil {
                forward.push((normalized, millis));
            }
        }
        // Historical timezone changes can create an overlap without changing
        // tm_isdst. Discover offsets on both sides of each library candidate,
        // and accept only candidates that round-trip through the actual zone.
        // This does not extrapolate the current device offset to another date.
        for seed in seeds {
            for hours in [-48i64, -24, -6, 6, 24, 48] {
                let Some(sample) = seed.checked_add(hours * 3_600_000) else {
                    continue;
                };
                let Ok(local_sample) = local(sample) else {
                    continue;
                };
                let delta = civil_seconds(civil) - civil_seconds(local_sample);
                let Some(candidate) = delta.checked_mul(1000).and_then(|d| sample.checked_add(d))
                else {
                    continue;
                };
                let Ok(normalized) = local(candidate) else {
                    continue;
                };
                if normalized == civil {
                    exact.push(candidate);
                } else if normalized > civil {
                    forward.push((normalized, candidate));
                }
            }
        }
        // Java's atZone selects the earlier overlap instant. During a gap it
        // moves forward by the gap, rather than applying today's fixed offset.
        if let Some(millis) = exact.into_iter().min() {
            return Ok(millis);
        }
        forward
            .into_iter()
            .min()
            .map(|(_, millis)| millis)
            .ok_or_else(|| "系统时区无法解析该本地时刻".into())
    }

    fn civil_seconds(civil: CivilTime) -> i64 {
        let before_year = i64::from(civil.year) - 1;
        let before_month: u32 = (1..civil.month)
            .map(|month| days_in_month(civil.year, month))
            .sum();
        let days = before_year * 365 + before_year / 4 - before_year / 100
            + before_year / 400
            + i64::from(before_month)
            + i64::from(civil.day)
            - 1;
        days * 86_400
            + i64::from(civil.hour) * 3600
            + i64::from(civil.minute) * 60
            + i64::from(civil.second)
    }
}
