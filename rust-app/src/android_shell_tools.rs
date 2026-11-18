//! Structured Android settings/app commands. The original tool call is the
//! approval source; each fixed primary/readback command shares one frozen
//! routing revision and UID. A successful exit is never system verification.
use crate::{
    execution_policy::RoutingOwner,
    network::CancelToken,
    shell_runner::{self, FrozenRequest, Outcome, Route},
    state::ToolCall,
};
use serde_json::{Value, json};
use std::sync::{
    Arc, Mutex, OnceLock, TryLockError, Weak,
    atomic::{AtomicBool, Ordering},
};
use suixing_search::rust_protocol::ToolDefinition;

#[derive(Clone)]
enum Verification {
    None,
    Setting {
        namespace: String,
        key: String,
        expected: String,
    },
    Package {
        package: String,
        expected_disabled: bool,
    },
    ReadSetting {
        namespace: String,
        key: String,
    },
}
#[derive(Clone)]
pub struct Prepared {
    owner: RoutingOwner,
    revision: u64,
    before: Option<FrozenRequest>,
    primary: FrozenRequest,
    after: Option<FrozenRequest>,
    verification: Verification,
    writes: bool,
    setting_lock: Option<Arc<Mutex<()>>>,
    started: Arc<AtomicBool>,
    pub summary: String,
}
impl Prepared {
    pub(crate) fn needs_serial_device(&self) -> bool {
        !matches!(
            &self.verification,
            Verification::Setting { .. } | Verification::ReadSetting { .. }
        )
    }
}
static SETTING_LOCKS: OnceLock<Mutex<std::collections::BTreeMap<String, Weak<Mutex<()>>>>> =
    OnceLock::new();
fn setting_lock(namespace: &str, key: &str) -> Result<Arc<Mutex<()>>, String> {
    let locks = SETTING_LOCKS.get_or_init(|| Mutex::new(std::collections::BTreeMap::new()));
    let mut locks = locks.lock().unwrap_or_else(|error| error.into_inner());
    locks.retain(|_, lock| lock.strong_count() > 0);
    let key = format!("{namespace}/{key}");
    if let Some(lock) = locks.get(&key).and_then(Weak::upgrade) {
        return Ok(lock);
    }
    if locks.len() >= 256 {
        return Err("系统设置并发键达到256个上限，本次操作未准备".into());
    }
    let lock = Arc::new(Mutex::new(()));
    locks.insert(key, Arc::downgrade(&lock));
    Ok(lock)
}
pub fn is_tool(name: &str) -> bool {
    matches!(name, "settings_get" | "settings_put" | "app_action")
}
pub fn definitions() -> Vec<ToolDefinition> {
    let ns = json!({"type":"string","enum":["system","secure","global"]});
    let string = || json!({"type":"string"});
    let definition = |name: &str, description: &str, properties: Value, required: &[&str]| {
        ToolDefinition {
            name: name.into(),
            description: description.into(),
            parameters: json!({"type":"object","properties":properties,"required":required,"additionalProperties":false}),
        }
    };
    vec![
        definition(
            "settings_get",
            "Read one Android system/secure/global setting using an approved frozen Root or Shizuku UID. Namespace/key are validated. Requires approval.",
            json!({"namespace":ns.clone(),"key":string()}),
            &["namespace", "key"],
        ),
        definition(
            "settings_put",
            "Write one Android setting and read it back as the same frozen Root or Shizuku UID. Report before/expected/actual/verified; exit zero alone does not prove the write took effect. Requires approval.",
            json!({"namespace":ns,"key":string(),"value":string()}),
            &["namespace", "key", "value"],
        ),
        definition(
            "app_action",
            "Perform a structured operation on an installed app as the approved frozen Root or Shizuku UID. enable/disable read back the disabled package list. Requires approval; install requires apk_path.",
            json!({"package":string(),"action":{"type":"string","enum":["start","force_stop","clear_data","uninstall","disable","enable","info","permissions","install"]},"apk_path":string()}),
            &["package", "action"],
        ),
    ]
}
fn quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}
fn request(owner: &RoutingOwner, command: String) -> Result<(FrozenRequest, u64), String> {
    // This synthetic run_shell object is a private transport adapter. It is
    // never advertised, persisted, approved, or reconstructed from model text.
    let transport = ToolCall {
        id: crate::controller::unique_id(),
        name: "run_shell".into(),
        arguments: json!({"command":command,"mode":"auto","shell":"auto"}).to_string(),
    };
    let prepared = owner.prepare_shell(
        &transport,
        format!("android-tool-{}", crate::controller::unique_id()),
    )?;
    if matches!(prepared.request.route, Route::AppUid) {
        return Err(
            "该操作需要已授权的Shizuku或Root；固定设置不允许特权时不执行，也不更换UID".into(),
        );
    }
    Ok((prepared.request, prepared.revision))
}
pub fn prepare(call: &ToolCall) -> Result<Prepared, String> {
    if call.arguments.len() > 128 * 1024 {
        return Err("Android命令参数超过128 KiB".into());
    }
    let value: Value =
        serde_json::from_str(&call.arguments).map_err(|_| "Android命令参数不是JSON")?;
    let args = value.as_object().ok_or("Android命令参数必须是对象")?;
    let allowed: &[&str] = match call.name.as_str() {
        "settings_get" => &["namespace", "key"],
        "settings_put" => &["namespace", "key", "value"],
        "app_action" => &["package", "action", "apk_path"],
        _ => return Err("未知结构化Android命令".into()),
    };
    if args.keys().any(|key| !allowed.contains(&key.as_str())) {
        return Err("Android命令包含未知参数".into());
    }
    let text = |key: &str| {
        args.get(key)
            .and_then(Value::as_str)
            .filter(|value| !value.contains('\0') && value.len() <= 32 * 1024)
            .map(str::to_owned)
            .ok_or_else(|| format!("参数{key}必须是32 KiB内无NUL文本"))
    };
    let (before, primary, after, verification, writes) = if call.name == "app_action" {
        let package = text("package")?.trim().to_owned();
        if package.is_empty()
            || !package
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'.' | b'_'))
        {
            return Err("包名只允许字母、数字、点和下划线".into());
        }
        let action = text("action")?.trim().to_ascii_lowercase();
        let command = match action.as_str() {
            "start" => format!("monkey -p {package} -c android.intent.category.LAUNCHER 1"),
            "force_stop" => format!("am force-stop {package}"),
            "clear_data" => format!("pm clear {package}"),
            "uninstall" => format!("pm uninstall {package}"),
            "disable" => format!("pm disable-user --user 0 {package}"),
            "enable" => format!("pm enable {package}"),
            "info" => format!("dumpsys package {package} | head -n 80"),
            "permissions" => format!(
                "dumpsys package {package} | sed -n '/requested permissions/,/install permissions/p'"
            ),
            "install" => {
                let apk = text("apk_path")?.trim().to_owned();
                if apk.is_empty() {
                    return Err("action=install需要apk_path".into());
                }
                format!("pm install -r {}", quote(&apk))
            }
            _ => return Err("不支持的app_action操作".into()),
        };
        let (after, verification) = match action.as_str() {
            "disable" | "enable" => (
                Some("pm list packages -d".to_owned()),
                Verification::Package {
                    package,
                    expected_disabled: action == "disable",
                },
            ),
            _ => (None, Verification::None),
        };
        (
            None,
            command,
            after,
            verification,
            !matches!(action.as_str(), "info" | "permissions"),
        )
    } else {
        let namespace = text("namespace")?.trim().to_ascii_lowercase();
        if !matches!(namespace.as_str(), "system" | "secure" | "global") {
            return Err("namespace必须是system / secure / global".into());
        }
        let key = text("key")?.trim().to_owned();
        if key.is_empty()
            || !key
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'_' | b'.' | b'-'))
        {
            return Err("key只允许字母、数字、下划线、点和短横线".into());
        }
        let read = format!("settings get {namespace} {key}");
        if call.name == "settings_get" {
            (
                None,
                read,
                None,
                Verification::ReadSetting { namespace, key },
                false,
            )
        } else {
            let expected = text("value")?;
            (
                Some(read.clone()),
                format!("settings put {namespace} {key} {}", quote(&expected)),
                Some(read),
                Verification::Setting {
                    namespace,
                    key,
                    expected,
                },
                true,
            )
        }
    };
    let owner = crate::controller::routing_owner()?;
    let (primary, revision) = request(&owner, primary)?;
    let freeze = |command: Option<String>| -> Result<Option<FrozenRequest>, String> {
        command
            .map(|command| {
                let (step, actual_revision) = request(&owner, command)?;
                if actual_revision != revision || step.route != primary.route {
                    return Err("命令与读回准备期间执行身份已变化，未派发".into());
                }
                Ok(step)
            })
            .transpose()
    };
    let before = freeze(before)?;
    let after = freeze(after)?;
    owner.validate_frozen(revision, primary.route)?;
    let summary = format!(
        "{}；固定身份 {:?}；目录 {}；{}",
        call.name,
        primary.route,
        primary.cwd.display(),
        if after.is_some() {
            "写后同身份读回"
        } else {
            "一次固定命令"
        }
    );
    let setting_lock = match &verification {
        Verification::Setting { namespace, key, .. } => Some(setting_lock(namespace, key)?),
        _ => None,
    };
    Ok(Prepared {
        owner,
        revision,
        before,
        primary,
        after,
        verification,
        writes,
        setting_lock,
        started: Arc::new(AtomicBool::new(false)),
        summary,
    })
}
fn setting_value(outcome: &Outcome) -> Option<String> {
    if !outcome.succeeded() || outcome.truncated {
        return None;
    }
    // Remove the command's line ending, retaining the setting's whitespace and
    // quote characters so verification compares the exact requested value.
    let value = outcome
        .stdout
        .strip_suffix("\r\n")
        .or_else(|| outcome.stdout.strip_suffix('\n'))
        .unwrap_or(&outcome.stdout);
    Some(value.to_owned())
}
pub fn execute(prepared: &Prepared, cancel: &Arc<CancelToken>) -> Value {
    if prepared.started.swap(true, Ordering::AcqRel) {
        return json!({"ok":false,"started":false,"error":"结构化Android命令已派发过，未重放"});
    }
    let _setting_guard = if let Some(lock) = &prepared.setting_lock {
        loop {
            if cancel.is_cancelled() {
                return json!({"ok":false,"started":false,"cancel_requested":true,"error":"系统设置写操作排队时已停止，未执行"});
            }
            match lock.try_lock() {
                Ok(guard) => break Some(guard),
                Err(TryLockError::Poisoned(error)) => break Some(error.into_inner()),
                Err(TryLockError::WouldBlock) => {
                    std::thread::sleep(std::time::Duration::from_millis(20))
                }
            }
        }
    } else {
        None
    };
    let run = |request: &FrozenRequest| {
        if cancel.is_cancelled() {
            return Outcome::not_started(request.route, "运行已停止，未派发下一命令");
        }
        if let Err(error) = prepared
            .owner
            .validate_frozen(prepared.revision, request.route)
        {
            return Outcome::not_started(request.route, error);
        }
        let owner = prepared.owner.clone();
        let revision = prepared.revision;
        let route = request.route;
        let flag = match crate::controller::cancellation::flag(
            cancel.clone(),
            Some(Arc::new(move || {
                owner.validate_frozen(revision, route).is_ok()
            })),
        ) {
            Ok(flag) => flag,
            Err(error) => return Outcome::not_started(request.route, error),
        };
        let executor = prepared.owner.context().privileged.clone();
        shell_runner::execute(request, flag, executor.as_deref())
    };
    let before = prepared.before.as_ref().map(&run);
    let primary = run(&prepared.primary);
    let after = if primary.succeeded() {
        prepared.after.as_ref().map(&run)
    } else {
        None
    };
    let mut result = json!({"ok":primary.succeeded(),"started":primary.started,"route":primary.route,"identity_confirmed":primary.identity_confirmed,"exit_code":primary.exit_code,"stdout":primary.stdout,"stderr":primary.stderr,"truncated":primary.truncated,"cancel_requested":primary.cancel_requested,"timed_out":primary.timed_out,"primary_exit_confirmed":primary.primary_exit_confirmed,"group_cleanup_confirmed":primary.group_cleanup_confirmed,"effects_may_have_occurred":prepared.writes&&primary.started,"error":primary.error,"file_activities":primary.file_changes.activities,"notice":primary.file_changes.notice});
    match &prepared.verification {
        Verification::None => (),
        Verification::ReadSetting { namespace, key } => {
            result["namespace"] = json!(namespace);
            result["key"] = json!(key);
            result["value"] = json!(setting_value(&primary));
        }
        Verification::Setting {
            namespace,
            key,
            expected,
        } => {
            let actual = after.as_ref().and_then(setting_value);
            let verified = actual
                .as_ref()
                .is_some_and(|value| value != "null" && value == expected);
            result["namespace"] = json!(namespace);
            result["key"] = json!(key);
            result["before"] = json!(before.as_ref().and_then(setting_value));
            result["expected"] = json!(expected);
            result["actual"] = json!(actual);
            result["verified"] = json!(verified);
            if !verified {
                result["verification_notice"] =
                    json!("写入未获同身份读回确认，请勿假设系统设置已修改");
            }
        }
        Verification::Package {
            package,
            expected_disabled,
        } => {
            let actual = after
                .as_ref()
                .filter(|outcome| outcome.succeeded() && !outcome.truncated)
                .map(|outcome| {
                    outcome
                        .stdout
                        .lines()
                        .any(|line| line.trim() == format!("package:{package}"))
                });
            result["package"] = json!(package);
            result["expected_disabled"] = json!(expected_disabled);
            result["actual_disabled"] = json!(actual);
            result["verified"] = json!(actual == Some(*expected_disabled));
            if actual != Some(*expected_disabled) {
                result["verification_notice"] =
                    json!("应用启停状态没有得到同身份读回确认，请勿假设修改已生效");
            }
        }
    }
    result["before_command"] = json!(before);
    result["readback_command"] = json!(after);
    result
}
