//! Runtime permission requests belong to their original operation. Results are
//! retained until drained; progress/lifecycle changes never imply permission.
use std::{
    collections::{BTreeMap, VecDeque},
    sync::{Mutex, OnceLock},
};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OperationKey {
    pub id: String,
    pub conversation_id: Option<String>,
    pub generation: u64,
}
impl OperationKey {
    pub fn validate(&self) -> Result<(), String> {
        if self.id.is_empty()
            || self.id.len() > 128
            || self.id.contains('\0')
            || self.generation == 0
            || self
                .conversation_id
                .as_ref()
                .is_some_and(|id| id.is_empty() || id.len() > 128 || id.contains('\0'))
        {
            return Err("平台操作身份无效".into());
        }
        Ok(())
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum PermissionKind {
    Microphone,
    Notifications,
    LocalNetwork,
    ReadExternalStorage,
    WriteExternalStorage,
}
impl PermissionKind {
    pub fn name(self) -> &'static str {
        match self {
            Self::Microphone => "android.permission.RECORD_AUDIO",
            Self::Notifications => "android.permission.POST_NOTIFICATIONS",
            Self::LocalNetwork => "android.permission.ACCESS_LOCAL_NETWORK",
            Self::ReadExternalStorage => "android.permission.READ_EXTERNAL_STORAGE",
            Self::WriteExternalStorage => "android.permission.WRITE_EXTERNAL_STORAGE",
        }
    }
    pub fn minimum_sdk(self) -> i32 {
        match self {
            Self::Microphone | Self::ReadExternalStorage | Self::WriteExternalStorage => 23,
            Self::Notifications => 33,
            Self::LocalNetwork => 37,
        }
    }
    pub fn maximum_sdk(self) -> Option<i32> {
        match self {
            Self::ReadExternalStorage | Self::WriteExternalStorage => Some(29),
            _ => None,
        }
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PermissionOutcome {
    Granted,
    Denied { settings_required: bool },
    Cancelled,
    Unavailable,
}
#[derive(Clone, Debug)]
pub struct PermissionResult {
    pub operation: OperationKey,
    pub permissions: Vec<(PermissionKind, PermissionOutcome)>,
    pub error: Option<String>,
}
struct Pending {
    operation: OperationKey,
    kinds: Vec<PermissionKind>,
    requested: Vec<PermissionKind>,
    cancelled: bool,
}
#[derive(Default)]
struct Registry {
    requests: BTreeMap<i32, Pending>,
    queue: VecDeque<i32>,
    active: Option<i32>,
    results: VecDeque<PermissionResult>,
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
static ALLOCATION: Mutex<()> = Mutex::new(());
const MAX_REQUESTS: usize = 32;
fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
}

#[cfg(target_os = "android")]
pub fn check(kind: PermissionKind) -> PermissionOutcome {
    let Some(paths) = super::android::paths() else {
        return PermissionOutcome::Unavailable;
    };
    if kind
        .maximum_sdk()
        .is_some_and(|maximum| paths.sdk_int > maximum)
    {
        return PermissionOutcome::Unavailable;
    }
    if paths.sdk_int < kind.minimum_sdk() {
        return PermissionOutcome::Granted;
    }
    match super::android::application_context().and_then(|context| {
        super::android::invoke(|env| {
            let name = env.new_string(kind.name())?;
            env.call_method(
                context.as_ref(),
                jni::jni_str!("checkSelfPermission"),
                jni::jni_sig!("(Ljava/lang/String;)I"),
                &[jni::JValue::Object(&name)],
            )?
            .i()
        })
    }) {
        Ok(0) => PermissionOutcome::Granted,
        Ok(_) => PermissionOutcome::Denied {
            settings_required: false,
        },
        Err(_) => PermissionOutcome::Unavailable,
    }
}
#[cfg(not(target_os = "android"))]
pub fn check(_kind: PermissionKind) -> PermissionOutcome {
    PermissionOutcome::Unavailable
}

/// Allocated durably before either a picker or permission launch. Never reuse a
/// request code after a crash; restored Android results must remain unowned.
#[cfg(target_os = "android")]
pub fn allocate_request_code() -> Result<i32, String> {
    use std::{
        fs::{self, OpenOptions},
        io::{Read, Write},
        os::unix::fs::OpenOptionsExt,
    };
    let _allocation = ALLOCATION.lock().unwrap_or_else(|e| e.into_inner());
    let root = super::android::paths()
        .ok_or("Android平台尚未初始化")?
        .no_backup_dir;
    let root = fs::canonicalize(root).map_err(|_| "无法解析平台请求序号目录")?;
    let path = root.join("rust-platform-request-sequence");
    let previous = match fs::symlink_metadata(&path) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => 0,
        Err(_) => return Err("无法检查平台请求序号，未重新使用旧编号".into()),
        Ok(metadata) => {
            if !metadata.is_file() || metadata.file_type().is_symlink() || metadata.len() > 32 {
                return Err("平台请求序号文件无效".into());
            }
            let mut file = OpenOptions::new()
                .read(true)
                .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
                .open(&path)
                .map_err(|_| "无法读取平台请求序号")?;
            let mut text = String::new();
            Read::by_ref(&mut file)
                .take(33)
                .read_to_string(&mut text)
                .map_err(|_| "平台请求序号不是文本")?;
            text.parse::<i32>()
                .map_err(|_| "平台请求序号损坏，未回落到零")?
        }
    };
    let next = previous
        .checked_add(1)
        .filter(|v| *v > 0)
        .ok_or("平台请求序号已耗尽")?;
    let temporary = root.join(format!(".rust-platform-request-{next}.tmp"));
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(&temporary)
        .map_err(|_| "平台请求序号暂存失败")?;
    file.write_all(next.to_string().as_bytes())
        .and_then(|_| file.sync_all())
        .map_err(|_| "平台请求序号同步失败")?;
    fs::rename(&temporary, &path).map_err(|_| "平台请求序号提交失败")?;
    fs::File::open(&root)
        .and_then(|directory| directory.sync_all())
        .map_err(|_| "平台请求序号目录同步失败")?;
    Ok(next)
}
#[cfg(not(target_os = "android"))]
pub fn allocate_request_code() -> Result<i32, String> {
    Err("当前平台没有Android操作返回入口".into())
}

pub fn request(operation: OperationKey, mut kinds: Vec<PermissionKind>) -> Result<(), String> {
    operation.validate()?;
    kinds.sort();
    kinds.dedup();
    if kinds.is_empty() || kinds.len() > 5 {
        return Err("权限请求集合无效".into());
    }
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    if state.requests.len() + state.results.len() >= MAX_REQUESTS {
        return Err("平台权限请求队列已满".into());
    }
    if state
        .requests
        .values()
        .any(|request| request.operation.id == operation.id)
        || state
            .results
            .iter()
            .any(|result| result.operation.id == operation.id)
    {
        return Err("平台权限操作已登记，未重复请求".into());
    }
    drop(state);
    let code = allocate_request_code()?;
    state = registry().lock().unwrap_or_else(|e| e.into_inner());
    if state.requests.len() + state.results.len() >= MAX_REQUESTS
        || state
            .requests
            .values()
            .any(|request| request.operation.id == operation.id)
        || state
            .results
            .iter()
            .any(|result| result.operation.id == operation.id)
    {
        return Err("平台权限请求队列已满".into());
    }
    state.requests.insert(
        code,
        Pending {
            operation,
            kinds,
            requested: Vec::new(),
            cancelled: false,
        },
    );
    state.queue.push_back(code);
    drop(state);
    launch_next();
    Ok(())
}
pub fn cancel(operation_id: &str) {
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    let codes: Vec<_> = state
        .requests
        .iter()
        .filter(|(_, request)| request.operation.id == operation_id)
        .map(|(code, _)| *code)
        .collect();
    for code in codes {
        if state.active == Some(code) {
            // The system dialog cannot be cancelled by dropping our operation.
            // Keep its slot until its result, but revoke every dependent effect.
            if let Some(request) = state.requests.get_mut(&code) {
                request.cancelled = true;
            }
        } else if let Some(request) = state.requests.remove(&code) {
            state.queue.retain(|queued| *queued != code);
            state.results.push_back(cancelled(request));
        }
    }
}
fn cancelled(request: Pending) -> PermissionResult {
    PermissionResult {
        operation: request.operation,
        permissions: request
            .kinds
            .into_iter()
            .map(|kind| (kind, PermissionOutcome::Cancelled))
            .collect(),
        error: None,
    }
}
pub fn drain_results(limit: usize) -> Vec<PermissionResult> {
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    let count = limit.min(state.results.len());
    state.results.drain(..count).collect()
}
pub fn activity_destroyed() {
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    let requests = std::mem::take(&mut state.requests);
    state.queue.clear();
    state.active = None;
    for (_, request) in requests {
        state.results.push_back(cancelled(request));
    }
}
fn finish(code: i32, outcomes: Vec<(PermissionKind, PermissionOutcome)>, error: Option<String>) {
    let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
    let Some(request) = state.requests.remove(&code) else {
        return;
    };
    if state.active == Some(code) {
        state.active = None;
    }
    state.queue.retain(|queued| *queued != code);
    let result = if request.cancelled {
        cancelled(request)
    } else {
        PermissionResult {
            operation: request.operation,
            permissions: outcomes,
            error,
        }
    };
    state.results.push_back(result);
    drop(state);
    launch_next();
}
#[cfg(target_os = "android")]
fn launch_next() {
    let code = {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        if state.active.is_some() {
            return;
        }
        let Some(code) = state.queue.pop_front() else {
            return;
        };
        state.active = Some(code);
        code
    };
    if let Err(error) = super::android::post_to_main(move || launch_on_main(code)) {
        finish(code, Vec::new(), Some(error));
    }
}
#[cfg(not(target_os = "android"))]
fn launch_next() {}
#[cfg(target_os = "android")]
fn launch_on_main(code: i32) {
    use jni::{JValue, jni_sig, jni_str};
    let (kinds, cancelled) = {
        let state = registry().lock().unwrap_or_else(|e| e.into_inner());
        let Some(request) = state.requests.get(&code) else {
            return;
        };
        (request.kinds.clone(), request.cancelled)
    };
    if cancelled {
        finish(code, Vec::new(), None);
        return;
    }
    let pending: Vec<_> = kinds
        .iter()
        .copied()
        .filter(|kind| matches!(check(*kind), PermissionOutcome::Denied { .. }))
        .collect();
    if pending.is_empty() {
        finish(
            code,
            kinds.into_iter().map(|kind| (kind, check(kind))).collect(),
            None,
        );
        return;
    }
    if !super::android::activity_visible() {
        finish(
            code,
            kinds
                .into_iter()
                .map(|kind| (kind, PermissionOutcome::Unavailable))
                .collect(),
            Some("权限请求需要可见的应用页面".into()),
        );
        return;
    }
    let Some(activity) = super::android::current_activity() else {
        finish(code, Vec::new(), Some("权限请求没有Activity".into()));
        return;
    };
    {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        if let Some(request) = state.requests.get_mut(&code) {
            request.requested = pending.clone();
        } else {
            return;
        }
    }
    let result = super::android::invoke(|env| {
        let array = env.new_object_array(
            pending.len() as i32,
            jni_str!("java/lang/String"),
            jni::objects::JObject::null(),
        )?;
        for (index, kind) in pending.iter().enumerate() {
            let name = env.new_string(kind.name())?;
            env.set_object_array_element(&array, index, &name)?;
        }
        env.call_method(
            activity.as_ref(),
            jni_str!("requestPermissions"),
            jni_sig!("([Ljava/lang/String;I)V"),
            &[JValue::Object(&array), JValue::Int(code)],
        )?;
        Ok(())
    });
    if let Err(error) = result {
        finish(code, Vec::new(), Some(error));
    }
}
/// Called only after the JNI ingress has decoded bounded arrays and released Env.
pub fn on_result(code: i32, names: Vec<String>, grants: Vec<i32>) {
    let (kinds, requested) = {
        let state = registry().lock().unwrap_or_else(|e| e.into_inner());
        let Some(request) = state.requests.get(&code) else {
            return;
        };
        (request.kinds.clone(), request.requested.clone())
    };
    if names.is_empty() && grants.is_empty() {
        finish(
            code,
            kinds
                .into_iter()
                .map(|kind| (kind, PermissionOutcome::Cancelled))
                .collect(),
            None,
        );
        return;
    }
    if names.len() != grants.len()
        || grants.iter().any(|grant| ![0, -1].contains(grant))
        || names.len() != requested.len()
        || names
            .iter()
            .zip(&requested)
            .any(|(name, kind)| name != kind.name())
    {
        finish(
            code,
            Vec::new(),
            Some("权限返回集合与原请求不一致，未恢复操作".into()),
        );
        return;
    }
    let outcomes = kinds
        .into_iter()
        .map(|kind| {
            let outcome = check(kind);
            let outcome = if matches!(outcome, PermissionOutcome::Denied { .. }) {
                PermissionOutcome::Denied {
                    settings_required: settings_required(kind),
                }
            } else {
                outcome
            };
            (kind, outcome)
        })
        .collect();
    finish(code, outcomes, None);
}
pub fn on_result_error(code: i32, error: &str) {
    finish(code, Vec::new(), Some(error.into()));
}
#[cfg(target_os = "android")]
fn settings_required(kind: PermissionKind) -> bool {
    use jni::{JValue, jni_sig, jni_str};
    let Some(activity) = super::android::current_activity() else {
        return false;
    };
    super::android::invoke(|env| {
        let name = env.new_string(kind.name())?;
        env.call_method(
            activity.as_ref(),
            jni_str!("shouldShowRequestPermissionRationale"),
            jni_sig!("(Ljava/lang/String;)Z"),
            &[JValue::Object(&name)],
        )?
        .z()
    })
    .is_ok_and(|show| !show)
}
#[cfg(not(target_os = "android"))]
fn settings_required(_kind: PermissionKind) -> bool {
    false
}

/// This is an address-shape preflight, not DNS resolution or permission proof.
pub fn local_target(endpoint: &str) -> bool {
    let Ok(url) = reqwest::Url::parse(endpoint.trim()) else {
        return false;
    };
    let Some(raw) = url.host_str() else {
        return false;
    };
    let host = raw
        .trim_start_matches('[')
        .trim_end_matches(']')
        .trim_end_matches('.')
        .to_ascii_lowercase();
    if host == "localhost" || host.ends_with(".local") || host.ends_with(".localhost") {
        return true;
    }
    if let Ok(ip) = host.parse::<std::net::IpAddr>() {
        return match ip {
            std::net::IpAddr::V4(ip) => {
                ip.is_private() || ip.is_loopback() || ip.is_link_local() || ip.is_unspecified()
            }
            std::net::IpAddr::V6(ip) => {
                ip.is_loopback()
                    || ip.is_unspecified()
                    || ip.is_unique_local()
                    || ip.is_unicast_link_local()
                    || ip
                        .to_ipv4_mapped()
                        .is_some_and(|ip| ip.is_private() || ip.is_loopback() || ip.is_link_local())
            }
        };
    }
    !host.contains('.')
}
pub fn network_permission(endpoint: &str) -> Result<(), PermissionKind> {
    if local_target(endpoint) && check(PermissionKind::LocalNetwork) != PermissionOutcome::Granted {
        Err(PermissionKind::LocalNetwork)
    } else {
        Ok(())
    }
}
