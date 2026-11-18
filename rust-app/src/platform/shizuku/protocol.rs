use super::*;
use crate::platform::android;
use jni::{Env, JValue, jni_sig, jni_str, objects::JObject, refs::Global};
use std::{
    collections::BTreeMap,
    fs::File,
    os::fd::FromRawFd,
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};

type Object = Arc<Global<JObject<'static>>>;
const SERVER: &str = "moe.shizuku.server.IShizukuService";
const CLIENT: &str = "moe.shizuku.server.IShizukuApplication";
const PROCESS: &str = "moe.shizuku.server.IRemoteProcess";
const MAX_REQUESTS: usize = 32;
static NEXT: AtomicU64 = AtomicU64::new(1);
static CONNECTING: AtomicBool = AtomicBool::new(false);
type BinderIngress = (Object, Object, i32);
static PENDING_BINDER: Mutex<Option<BinderIngress>> = Mutex::new(None);
static SUI_STARTED: AtomicBool = AtomicBool::new(false);
static PROCESSES: AtomicUsize = AtomicUsize::new(0);
struct Session {
    generation: u64,
    sender_uid: i32,
    server: Object,
    client: Object,
    death: Object,
    confirmed: AtomicBool,
}
struct Pending {
    operation: OperationKey,
    generation: u64,
    cancelled: Arc<AtomicBool>,
    finished: bool,
    response: Option<bool>,
    deadline: Instant,
}
#[derive(Default)]
struct Registry {
    session: Option<Arc<Session>>,
    requests: BTreeMap<i32, Pending>,
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
}
fn next() -> Result<u64, String> {
    NEXT.fetch_update(Ordering::AcqRel, Ordering::Acquire, |value| {
        value.checked_add(1).filter(|v| *v <= i64::MAX as u64)
    })
    .map_err(|_| "Shizuku连接代号已耗尽".into())
}
fn connection() -> Result<Arc<Session>, String> {
    registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .session
        .clone()
        .ok_or("Shizuku尚未接入，先启动并授权Shizuku".into())
}
fn emit(code: i32, granted: bool, error: Option<String>) {
    let result = {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        let Some(request) = state.requests.get_mut(&code) else {
            return;
        };
        if request.finished {
            return;
        }
        request.finished = true;
        PermissionResult {
            operation: request.operation.clone(),
            granted: granted && !request.cancelled.load(Ordering::Acquire),
            cancelled: request.cancelled.load(Ordering::Acquire),
            error,
        }
    };
    shared()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .results
        .push_back(result);
}
fn clear_generation(generation: u64, message: &str) {
    let codes = {
        let state = registry().lock().unwrap_or_else(|e| e.into_inner());
        state
            .requests
            .iter()
            .filter(|(_, r)| r.generation == generation)
            .map(|(code, _)| *code)
            .collect::<Vec<_>>()
    };
    for code in codes {
        emit(code, false, Some(message.into()));
    }
    let old = {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        state.requests.retain(|_, r| r.generation != generation);
        if state
            .session
            .as_ref()
            .is_some_and(|s| s.generation == generation)
        {
            state.session.take()
        } else {
            None
        }
    };
    if let Some(old) = old {
        let _ = android::invoke(|env| {
            env.call_method(
                old.server.as_ref(),
                jni_str!("unlinkToDeath"),
                jni_sig!("(Landroid/os/IBinder$DeathRecipient;I)Z"),
                &[JValue::Object(old.death.as_ref()), JValue::Int(0)],
            )?;
            Ok(())
        });
        let mut state = shared().lock().unwrap_or_else(|e| e.into_inner());
        state.status.connected = false;
        state.status.backend = None;
        state.status.granted = false;
        state.status.permission_pending = false;
        state.status.error = Some(message.into());
    }
}

/// Called at the real ContentProvider IPC ingress. Context and Binder are copied
/// before returning; only actual root/shell Binder calling UIDs are accepted.
pub fn receive_binder(context: Object, binder: Object, calling_uid: i32) -> Result<(), String> {
    if ![0, 2000].contains(&calling_uid) {
        return Err("Shizuku Provider调用者身份不受支持".into());
    }
    if status().backend == Some(Backend::Sui) {
        return Err("当前已接入Sui；未把已有身份替换成另一个Shizuku服务".into());
    }
    if let Ok(current) = connection() {
        if current.sender_uid == calling_uid
            && current.confirmed.load(Ordering::Acquire)
            && android::invoke(|env| env.is_same_object(current.server.as_ref(), binder.as_ref()))?
        {
            return Ok(());
        }
    }
    spawn_connection(context, Some(binder), calling_uid, Backend::Shizuku)
}
/// The old provider enabled Sui initialization. Probe its pinned framework
/// ActivityManager bridge on one worker; root UID is then checked against the
/// real bindApplication callback UID, rather than invented Provider extras.
pub fn provider_created(context: Object) -> Result<(), String> {
    if SUI_STARTED.swap(true, Ordering::AcqRel) {
        return Ok(());
    }
    let result = spawn_connection(context, None, 0, Backend::Sui);
    if result.is_err() {
        SUI_STARTED.store(false, Ordering::Release);
    }
    result
}
fn spawn_connection(
    context: Object,
    binder: Option<Object>,
    calling_uid: i32,
    backend: Backend,
) -> Result<(), String> {
    let mut pending = PENDING_BINDER.lock().unwrap_or_else(|e| e.into_inner());
    if CONNECTING
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        if backend == Backend::Shizuku {
            if let Some(binder) = binder {
                *pending = Some((context, binder, calling_uid));
                return Ok(());
            }
        }
        return Err("Shizuku连接正在核对".into());
    }
    drop(pending);
    shared()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .status
        .connecting = true;
    let spawn = std::thread::Builder::new()
        .name("rust-shizuku-connect".into())
        .spawn(move || {
            struct Permit;
            impl Drop for Permit {
                fn drop(&mut self) {
                    finish_connection();
                }
            }
            let _permit = Permit;
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                let binder = match binder {
                    Some(binder) => binder,
                    None => sui_binder()?,
                };
                attach(context, binder, calling_uid, backend)
            }))
            .unwrap_or_else(|_| Err("Shizuku连接异常".into()));
            if let Err(error) = result {
                let mut state = shared().lock().unwrap_or_else(|e| e.into_inner());
                if backend == Backend::Sui {
                    state.status.sui_probe_error = Some(error);
                } else {
                    state.status.error = Some(error);
                }
            }
        });
    if spawn.is_err() {
        finish_connection();
        return Err("Shizuku连接线程无法启动".into());
    }
    Ok(())
}
fn finish_connection() {
    let pending = {
        let mut pending = PENDING_BINDER.lock().unwrap_or_else(|e| e.into_inner());
        CONNECTING.store(false, Ordering::Release);
        shared()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .status
            .connecting = false;
        pending.take()
    };
    if let Some((context, binder, calling_uid)) = pending {
        // A successful Sui connection deliberately ignores a separate Shizuku
        // source; otherwise the admitted, authenticated ingress is retained.
        let _ = receive_binder(context, binder, calling_uid);
    }
}
fn sui_binder() -> Result<Object, String> {
    let manager = android::invoke(|env| {
        let name = env.new_string("activity")?;
        let binder = env
            .call_static_method(
                jni_str!("android/os/ServiceManager"),
                jni_str!("getService"),
                jni_sig!("(Ljava/lang/String;)Landroid/os/IBinder;"),
                &[JValue::Object(&name)],
            )?
            .l()?;
        if binder.is_null() {
            return Err(jni::errors::Error::NullPtr(
                "Sui ActivityManager bridge unavailable",
            ));
        }
        Ok(Arc::new(env.new_global_ref(binder)?))
    })?;
    rpc(
        &manager,
        "android.app.IActivityManager",
        1_599_296_841,
        |env, data| write_int(env, data, 2),
        |env, reply| {
            let binder = env
                .call_method(
                    reply,
                    jni_str!("readStrongBinder"),
                    jni_sig!("()Landroid/os/IBinder;"),
                    &[],
                )?
                .l()?;
            if binder.is_null() {
                return Err(jni::errors::Error::NullPtr("Sui bridge returned no binder"));
            }
            Ok(Arc::new(env.new_global_ref(binder)?))
        },
    )
}
fn attach(
    context: Object,
    server: Object,
    sender_uid: i32,
    backend: Backend,
) -> Result<(), String> {
    let generation = next()?;
    let version = rpc_int(&server, SERVER, 3)?;
    let uid = rpc_int(&server, SERVER, 4)?;
    if version != 13 || uid != sender_uid {
        return Err("Shizuku协议版本或实际调用UID与接入来源不一致".into());
    }
    let (client, death, package) = android::invoke(|env| {
        let descriptor = env
            .call_method(
                server.as_ref(),
                jni_str!("getInterfaceDescriptor"),
                jni_sig!("()Ljava/lang/String;"),
                &[],
            )?
            .l()?;
        if android::string_value(env, descriptor, 128)? != SERVER {
            return Err(jni::errors::Error::NullPtr("shizuku descriptor mismatch"));
        }
        let client_class = android::generated_class("NativeShizukuClient")
            .map_err(|_| jni::errors::Error::NullPtr("shizuku client class"))?;
        let client = env.new_object(
            client_class.as_ref(),
            jni_sig!("(J)V"),
            &[JValue::Long(generation as i64)],
        )?;
        let token = env.new_string(CLIENT)?;
        env.call_method(
            &client,
            jni_str!("attachInterface"),
            jni_sig!("(Landroid/os/IInterface;Ljava/lang/String;)V"),
            &[JValue::Object(&JObject::null()), JValue::Object(&token)],
        )?;
        let death_class = android::generated_class("NativeShizukuDeath")
            .map_err(|_| jni::errors::Error::NullPtr("shizuku death class"))?;
        let death = env.new_object(
            death_class.as_ref(),
            jni_sig!("(J)V"),
            &[JValue::Long(generation as i64)],
        )?;
        let package = env
            .call_method(
                context.as_ref(),
                jni_str!("getPackageName"),
                jni_sig!("()Ljava/lang/String;"),
                &[],
            )?
            .l()?;
        let package = android::string_value(env, package, 256)?;
        Ok((
            Arc::new(env.new_global_ref(client)?),
            Arc::new(env.new_global_ref(death)?),
            package,
        ))
    })?;
    if let Ok(old) = connection() {
        clear_generation(old.generation, "Shizuku连接已经更换，旧请求未复用");
    }
    let session = Arc::new(Session {
        generation,
        sender_uid,
        server: server.clone(),
        client: client.clone(),
        death: death.clone(),
        confirmed: AtomicBool::new(false),
    });
    registry().lock().unwrap_or_else(|e| e.into_inner()).session = Some(session.clone());
    let result = android::invoke(|env| {
        env.call_method(
            server.as_ref(),
            jni_str!("linkToDeath"),
            jni_sig!("(Landroid/os/IBinder$DeathRecipient;I)V"),
            &[JValue::Object(death.as_ref()), JValue::Int(0)],
        )?;
        Ok(())
    })
    .and_then(|_| {
        rpc(
            &server,
            SERVER,
            18,
            |env, data| {
                env.call_method(
                    data,
                    jni_str!("writeStrongBinder"),
                    jni_sig!("(Landroid/os/IBinder;)V"),
                    &[JValue::Object(client.as_ref())],
                )?;
                let bundle = env.new_object(jni_str!("android/os/Bundle"), jni_sig!("()V"), &[])?;
                let key = env.new_string("shizuku:attach-api-version")?;
                env.call_method(
                    &bundle,
                    jni_str!("putInt"),
                    jni_sig!("(Ljava/lang/String;I)V"),
                    &[JValue::Object(&key), JValue::Int(13)],
                )?;
                let key = env.new_string("shizuku:attach-package-name")?;
                let package = env.new_string(&package)?;
                env.call_method(
                    &bundle,
                    jni_str!("putString"),
                    jni_sig!("(Ljava/lang/String;Ljava/lang/String;)V"),
                    &[JValue::Object(&key), JValue::Object(&package)],
                )?;
                write_int(env, data, 1)?;
                env.call_method(
                    bundle,
                    jni_str!("writeToParcel"),
                    jni_sig!("(Landroid/os/Parcel;I)V"),
                    &[JValue::Object(data), JValue::Int(0)],
                )?;
                Ok(())
            },
            |_, _| Ok(()),
        )
    });
    if let Err(error) = result {
        clear_generation(generation, "Shizuku attach请求失败");
        return Err(error);
    }
    let deadline = Instant::now() + Duration::from_secs(15);
    while !session.confirmed.load(Ordering::Acquire) {
        if Instant::now() >= deadline {
            clear_generation(generation, "Shizuku attach回调超时，未确认授权连接");
            return Err("Shizuku attach回调超时".into());
        }
        if !registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .session
            .as_ref()
            .is_some_and(|s| s.generation == generation)
        {
            return Err("Shizuku连接在接入期间失效".into());
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    let current = registry().lock().unwrap_or_else(|e| e.into_inner());
    if !current
        .session
        .as_ref()
        .is_some_and(|s| s.generation == generation)
    {
        return Err("Shizuku连接在attach完成前已经死亡或更换".into());
    }
    let mut state = shared().lock().unwrap_or_else(|e| e.into_inner());
    state.status.generation = generation;
    state.status.server_uid = Some(uid);
    state.status.server_version = Some(version);
    state.status.connected = true;
    state.status.backend = Some(backend);
    if backend == Backend::Sui {
        state.status.sui_probe_error = None;
    }
    state.status.error = None;
    drop(state);
    drop(current);
    refresh()?;
    Ok(())
}

pub fn refresh() -> Result<Status, String> {
    let installed = android::invoke(|env| {
        let context = android::application_context()
            .map_err(|_| jni::errors::Error::NullPtr("shizuku context"))?;
        let pm = env
            .call_method(
                context.as_ref(),
                jni_str!("getPackageManager"),
                jni_sig!("()Landroid/content/pm/PackageManager;"),
                &[],
            )?
            .l()?;
        let package = env.new_string(MANAGER_PACKAGE)?;
        env.call_method(
            pm,
            jni_str!("getPackageInfo"),
            jni_sig!("(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;"),
            &[JValue::Object(&package), JValue::Int(0)],
        )?;
        Ok(())
    })
    .is_ok();
    shared()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .status
        .installed = installed;
    let session = match connection() {
        Ok(session) => session,
        Err(_) => {
            let mut state = shared().lock().unwrap_or_else(|e| e.into_inner());
            state.status.connected = false;
            state.status.granted = false;
            return Ok(state.status.clone());
        }
    };
    if !session.confirmed.load(Ordering::Acquire) {
        return Ok(status());
    }
    let result = android::invoke(|env| {
        env.call_method(
            session.server.as_ref(),
            jni_str!("pingBinder"),
            jni_sig!("()Z"),
            &[],
        )?
        .z()
    })
    .and_then(|alive| {
        if alive {
            Ok(())
        } else {
            Err("Shizuku Binder已经死亡".into())
        }
    });
    if result.is_err() {
        clear_generation(session.generation, "Shizuku Binder不可用");
        return Err("Shizuku Binder不可用".into());
    }
    let queried = rpc_int(&session.server, SERVER, 16).and_then(|grant| {
        rpc_int(&session.server, SERVER, 17).map(|rationale| (grant == 0, rationale != 0))
    });
    let (granted, rationale) = match queried {
        Ok(result) => result,
        Err(error) => {
            clear_generation(session.generation, "Shizuku权限查询失败，旧授权已清除");
            return Err(error);
        }
    };
    let current = registry().lock().unwrap_or_else(|e| e.into_inner());
    if !current
        .session
        .as_ref()
        .is_some_and(|s| s.generation == session.generation)
    {
        return Err("Shizuku连接在权限查询期间变更".into());
    }
    let mut state = shared().lock().unwrap_or_else(|e| e.into_inner());
    state.status.connected = true;
    state.status.granted = granted;
    state.status.settings_required = !granted && !rationale;
    Ok(state.status.clone())
}

pub fn request_permission(operation: OperationKey) -> Result<(), String> {
    operation.validate()?;
    if !android::activity_visible() {
        return Err("Shizuku授权需要用户当前可见页面".into());
    }
    let session = connection()?;
    if !session.confirmed.load(Ordering::Acquire) {
        return Err("Shizuku连接尚未确认".into());
    }
    let permission = refresh()?;
    if permission.generation != session.generation {
        return Err("Shizuku连接在准备授权时变更".into());
    }
    let code = crate::platform::permissions::allocate_request_code()?;
    let cancelled = Arc::new(AtomicBool::new(false));
    {
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        let mut result_state = shared().lock().unwrap_or_else(|e| e.into_inner());
        if !state
            .session
            .as_ref()
            .is_some_and(|s| s.generation == session.generation)
        {
            return Err("Shizuku连接在授权登记前变更".into());
        }
        if state
            .requests
            .values()
            .any(|p| p.operation.id == operation.id)
            || result_state
                .results
                .iter()
                .any(|p| p.operation.id == operation.id)
        {
            return Err("Shizuku授权操作代号已经登记或等待领取结果".into());
        }
        if !state.requests.is_empty()
            || state.requests.len() + result_state.results.len() >= MAX_REQUESTS
        {
            return Err("已有Shizuku授权请求尚未结束，未重复显示授权".into());
        }
        if permission.granted {
            result_state.results.push_back(PermissionResult {
                operation,
                granted: true,
                cancelled: false,
                error: None,
            });
            return Ok(());
        }
        result_state.status.permission_pending = true;
        state.requests.insert(
            code,
            Pending {
                operation,
                generation: session.generation,
                cancelled: cancelled.clone(),
                finished: false,
                response: None,
                deadline: Instant::now() + Duration::from_secs(120),
            },
        );
    }
    shared()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .status
        .permission_pending = true;
    let worker = std::thread::Builder::new()
        .name("rust-shizuku-permission".into())
        .spawn(move || {
            let sent = rpc(
                &session.server,
                SERVER,
                15,
                |env, data| write_int(env, data, code),
                |_, _| Ok(()),
            );
            if sent.is_err() {
                emit(code, false, Some("Shizuku授权请求发送失败".into()));
                registry()
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .requests
                    .remove(&code);
                shared()
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .status
                    .permission_pending = false;
                return;
            }
            loop {
                let (response, deadline) = {
                    let state = registry().lock().unwrap_or_else(|e| e.into_inner());
                    let Some(request) = state.requests.get(&code) else {
                        return;
                    };
                    (request.response, request.deadline)
                };
                if cancelled.load(Ordering::Acquire) {
                    emit(
                        code,
                        false,
                        Some("Shizuku授权操作已取消；系统授权窗口仍可能等待用户处理".into()),
                    );
                }
                if let Some(response) = response {
                    let grant = response && refresh().is_ok_and(|status| status.granted);
                    emit(
                        code,
                        grant,
                        (!grant).then(|| "Shizuku未授予当前操作权限".into()),
                    );
                    registry()
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .requests
                        .remove(&code);
                    shared()
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .status
                        .permission_pending = false;
                    return;
                }
                if Instant::now() >= deadline {
                    emit(
                        code,
                        false,
                        Some("Shizuku授权回调超时；未重用仍可能显示的系统请求".into()),
                    );
                    return;
                }
                std::thread::sleep(Duration::from_millis(10));
            }
        });
    if worker.is_err() {
        registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .requests
            .remove(&code);
        shared()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .status
            .permission_pending = false;
        return Err("Shizuku授权线程无法启动".into());
    }
    Ok(())
}
pub fn cancel_permission(id: &str) {
    let state = registry().lock().unwrap_or_else(|e| e.into_inner());
    for request in state.requests.values().filter(|r| r.operation.id == id) {
        request.cancelled.store(true, Ordering::Release);
    }
}
pub fn on_binder_dead(generation: u64) {
    clear_generation(
        generation,
        "Shizuku Binder已经死亡，授权与旧进程句柄不再复用",
    );
}

/// Native Binder.onTransact preserves the calling UID/Parcel thread. No effects
/// are issued here. The owner reads permission again on its worker afterwards.
pub fn on_client_transaction(
    env: &mut Env,
    generation: u64,
    code: i32,
    data: &JObject,
    reply: &JObject,
    calling_uid: i32,
) -> jni::errors::Result<bool> {
    let Some(session) = registry()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .session
        .clone()
    else {
        return Ok(false);
    };
    if generation != session.generation
        || calling_uid != session.sender_uid
        || ![0, 2000].contains(&calling_uid)
    {
        return Ok(false);
    }
    if code == 0x5f4e5446 {
        let token = env.new_string(CLIENT)?;
        env.call_method(
            reply,
            jni_str!("writeString"),
            jni_sig!("(Ljava/lang/String;)V"),
            &[JValue::Object(&token)],
        )?;
        return Ok(true);
    }
    if ![2, 3].contains(&code) {
        return Ok(false);
    }
    let token = env.new_string(CLIENT)?;
    env.call_method(
        data,
        jni_str!("enforceInterface"),
        jni_sig!("(Ljava/lang/String;)V"),
        &[JValue::Object(&token)],
    )?;
    let request_code = if code == 3 {
        Some(read_int(env, data)?)
    } else {
        None
    };
    if read_int(env, data)? != 1 {
        return Err(jni::errors::Error::NullPtr(
            "shizuku callback bundle missing",
        ));
    }
    let bundle = read_parcelable(env, data, "android/os/Bundle")?;
    if code == 2 {
        let uid = bundle_int(env, &bundle, "shizuku:attach-reply-uid", -1)?;
        let version = bundle_int(env, &bundle, "shizuku:attach-reply-version", -1)?;
        if uid != session.sender_uid || version != 13 {
            return Err(jni::errors::Error::NullPtr(
                "shizuku bind identity mismatch",
            ));
        }
        session.confirmed.store(true, Ordering::Release);
    } else {
        let key = env.new_string("shizuku:request-permission-reply-allowed")?;
        let allowed = env
            .call_method(
                &bundle,
                jni_str!("getBoolean"),
                jni_sig!("(Ljava/lang/String;Z)Z"),
                &[JValue::Object(&key), JValue::Bool(false)],
            )?
            .z()?;
        let mut state = registry().lock().unwrap_or_else(|e| e.into_inner());
        if let Some(request) = state.requests.get_mut(&request_code.unwrap()) {
            if request.generation == generation {
                request.response = Some(allowed);
                if request.finished {
                    state.requests.remove(&request_code.unwrap());
                    shared()
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .status
                        .permission_pending = false;
                }
            }
        }
    }
    Ok(true)
}

fn rpc<T>(
    binder: &Object,
    descriptor: &str,
    code: i32,
    write: impl FnOnce(&mut Env, &JObject) -> jni::errors::Result<()>,
    read: impl FnOnce(&mut Env, &JObject) -> jni::errors::Result<T>,
) -> Result<T, String> {
    android::invoke(|env| {
        let data = env
            .call_static_method(
                jni_str!("android/os/Parcel"),
                jni_str!("obtain"),
                jni_sig!("()Landroid/os/Parcel;"),
                &[],
            )?
            .l()?;
        let reply = match env.call_static_method(
            jni_str!("android/os/Parcel"),
            jni_str!("obtain"),
            jni_sig!("()Landroid/os/Parcel;"),
            &[],
        ) {
            Ok(reply) => reply.l()?,
            Err(error) => {
                if env.exception_check() {
                    env.exception_clear();
                }
                let _ = env.call_method(&data, jni_str!("recycle"), jni_sig!("()V"), &[]);
                return Err(error);
            }
        };
        let result = (|| {
            let token = env.new_string(descriptor)?;
            env.call_method(
                &data,
                jni_str!("writeInterfaceToken"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[JValue::Object(&token)],
            )?;
            write(env, &data)?;
            if !env
                .call_method(
                    binder.as_ref(),
                    jni_str!("transact"),
                    jni_sig!("(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z"),
                    &[
                        JValue::Int(code),
                        JValue::Object(&data),
                        JValue::Object(&reply),
                        JValue::Int(0),
                    ],
                )?
                .z()?
            {
                return Err(jni::errors::Error::NullPtr("shizuku transaction refused"));
            }
            env.call_method(&reply, jni_str!("readException"), jni_sig!("()V"), &[])?;
            read(env, &reply)
        })();
        if env.exception_check() {
            env.exception_clear();
        }
        let first = env.call_method(&reply, jni_str!("recycle"), jni_sig!("()V"), &[]);
        if env.exception_check() {
            env.exception_clear();
        }
        let second = env.call_method(&data, jni_str!("recycle"), jni_sig!("()V"), &[]);
        let value = result?;
        first?;
        second?;
        Ok(value)
    })
}
fn rpc_int(binder: &Object, descriptor: &str, code: i32) -> Result<i32, String> {
    rpc(binder, descriptor, code, |_, _| Ok(()), read_int)
}
fn read_int(env: &mut Env, parcel: &JObject) -> jni::errors::Result<i32> {
    env.call_method(parcel, jni_str!("readInt"), jni_sig!("()I"), &[])?
        .i()
}
fn write_int(env: &mut Env, parcel: &JObject, value: i32) -> jni::errors::Result<()> {
    env.call_method(
        parcel,
        jni_str!("writeInt"),
        jni_sig!("(I)V"),
        &[JValue::Int(value)],
    )?;
    Ok(())
}
fn bundle_int(
    env: &mut Env,
    bundle: &JObject,
    name: &str,
    default: i32,
) -> jni::errors::Result<i32> {
    let name = env.new_string(name)?;
    env.call_method(
        bundle,
        jni_str!("getInt"),
        jni_sig!("(Ljava/lang/String;I)I"),
        &[JValue::Object(&name), JValue::Int(default)],
    )?
    .i()
}
fn read_parcelable<'local>(
    env: &mut Env<'local>,
    data: &JObject,
    class: &str,
) -> jni::errors::Result<JObject<'local>> {
    let class = env.find_class(jni::strings::JNIString::new(class))?;
    let creator = env
        .get_static_field(
            class,
            jni_str!("CREATOR"),
            jni_sig!("Landroid/os/Parcelable$Creator;"),
        )?
        .l()?;
    env.call_method(
        creator,
        jni_str!("createFromParcel"),
        jni_sig!("(Landroid/os/Parcel;)Ljava/lang/Object;"),
        &[JValue::Object(data)],
    )?
    .l()
}

/// A remote process has no app-UID fallback. The owner must explicitly destroy
/// it and retain this handle until termination is confirmed. Pipe FD ownership
/// is transferred exactly once; no JNI/private file descriptor reflection.
pub struct RemoteProcess {
    session: Arc<Session>,
    process: Object,
    claimed: AtomicUsize,
    terminated: AtomicBool,
}
impl std::fmt::Debug for RemoteProcess {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RemoteProcess")
            .field("generation", &self.session.generation)
            .finish()
    }
}
pub fn spawn(argv: &[String], environment: &[String], cwd: &str) -> Result<RemoteProcess, String> {
    spawn_checked(None, argv, environment, cwd)
}
/// Callers with an approved frozen Route must pin its generation inside process
/// creation, before any RPC can have an effect. A later server is never selected.
pub fn spawn_frozen(
    generation: u64,
    argv: &[String],
    environment: &[String],
    cwd: &str,
) -> Result<RemoteProcess, String> {
    if generation == 0 {
        return Err("冻结Shizuku连接代次无效".into());
    }
    spawn_checked(Some(generation), argv, environment, cwd)
}
fn spawn_checked(
    expected: Option<u64>,
    argv: &[String],
    environment: &[String],
    cwd: &str,
) -> Result<RemoteProcess, String> {
    if argv.is_empty()
        || argv.len() > 256
        || environment.len() > 256
        || cwd.len() > 4096
        || cwd.contains('\0')
        || argv
            .iter()
            .chain(environment)
            .any(|s| s.len() > 64 * 1024 || s.contains('\0'))
        || argv
            .iter()
            .chain(environment)
            .try_fold(0usize, |total, value| total.checked_add(value.len()))
            .is_none_or(|total| total > 512 * 1024)
    {
        return Err("Shizuku进程参数无效或超出预算".into());
    }
    let status = refresh()?;
    if !status.connected || !status.granted {
        return Err("Shizuku当前没有授权，不会改用其它身份".into());
    }
    if expected.is_some_and(|generation| generation != status.generation) {
        return Err("冻结的Shizuku连接已经变更，未向其它代次创建进程".into());
    }
    let session = connection()?;
    if session.generation != status.generation
        || expected.is_some_and(|generation| generation != session.generation)
        || !session.confirmed.load(Ordering::Acquire)
    {
        return Err("Shizuku连接已经变更，未使用旧授权创建进程".into());
    }
    if PROCESSES
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| {
            (n < 32).then_some(n + 1)
        })
        .is_err()
    {
        return Err("Shizuku远程进程句柄达到32个预算".into());
    }
    let created = rpc(
        &session.server,
        SERVER,
        8,
        |env, data| {
            for strings in [argv, environment] {
                let array = env.new_object_array(
                    strings.len() as i32,
                    jni_str!("java/lang/String"),
                    JObject::null(),
                )?;
                for (index, value) in strings.iter().enumerate() {
                    let value = env.new_string(value)?;
                    env.set_object_array_element(&array, index, &value)?;
                }
                env.call_method(
                    data,
                    jni_str!("writeStringArray"),
                    jni_sig!("([Ljava/lang/String;)V"),
                    &[JValue::Object(&array)],
                )?;
            }
            let cwd = env.new_string(cwd)?;
            env.call_method(
                data,
                jni_str!("writeString"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[JValue::Object(&cwd)],
            )?;
            Ok(())
        },
        |env, reply| {
            let process = env
                .call_method(
                    reply,
                    jni_str!("readStrongBinder"),
                    jni_sig!("()Landroid/os/IBinder;"),
                    &[],
                )?
                .l()?;
            if process.is_null() {
                return Err(jni::errors::Error::NullPtr("shizuku process missing"));
            }
            Ok(Arc::new(env.new_global_ref(process)?))
        },
    );
    match created {
        Ok(process) => Ok(RemoteProcess {
            session,
            process,
            claimed: AtomicUsize::new(0),
            terminated: AtomicBool::new(false),
        }),
        Err(error) => {
            PROCESSES.fetch_sub(1, Ordering::AcqRel);
            Err(error)
        }
    }
}
impl RemoteProcess {
    pub fn generation(&self) -> u64 {
        self.session.generation
    }
    pub fn is_alive(&self) -> Result<bool, String> {
        Ok(rpc_int(&self.process, PROCESS, 7)? != 0)
    }
    pub fn try_wait(&self) -> Result<Option<i32>, String> {
        if self.is_alive()? {
            Ok(None)
        } else {
            let exit = rpc_int(&self.process, PROCESS, 5)?;
            self.terminated.store(true, Ordering::Release);
            Ok(Some(exit))
        }
    }
    pub fn destroy(&self) -> Result<(), String> {
        rpc(&self.process, PROCESS, 6, |_, _| Ok(()), |_, _| Ok(()))
    }
    pub fn stdin(&self) -> Result<File, String> {
        self.pipe(1)
    }
    pub fn stdout(&self) -> Result<File, String> {
        self.pipe(2)
    }
    pub fn stderr(&self) -> Result<File, String> {
        self.pipe(3)
    }
    fn pipe(&self, code: i32) -> Result<File, String> {
        let grant = refresh()?;
        if !grant.granted || !grant.connected || grant.generation != self.session.generation {
            return Err("Shizuku连接或授权已经失效，未读取旧进程管道".into());
        }
        let mask = 1usize << code;
        if self
            .claimed
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |flags| {
                (flags & mask == 0).then_some(flags | mask)
            })
            .is_err()
        {
            return Err("Shizuku进程管道已经转移，未重复读取".into());
        }
        let result = rpc(
            &self.process,
            PROCESS,
            code,
            |_, _| Ok(()),
            |env, reply| {
                if read_int(env, reply)? != 1 {
                    return Err(jni::errors::Error::NullPtr("shizuku pipe missing"));
                }
                let pfd = read_parcelable(env, reply, "android/os/ParcelFileDescriptor")?;
                let result = (|| {
                    let fd = env
                        .call_method(&pfd, jni_str!("getFd"), jni_sig!("()I"), &[])?
                        .i()?;
                    let duplicate = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 3) };
                    if duplicate < 0 {
                        return Err(jni::errors::Error::NullPtr("shizuku pipe duplicate"));
                    }
                    Ok(unsafe { File::from_raw_fd(duplicate) })
                })();
                if env.exception_check() {
                    env.exception_clear();
                }
                let closed = env.call_method(&pfd, jni_str!("close"), jni_sig!("()V"), &[]);
                let file = result?;
                closed?;
                Ok(file)
            },
        );
        if result.is_err() {
            self.claimed.fetch_and(!mask, Ordering::AcqRel);
        }
        result
    }
}
impl Drop for RemoteProcess {
    fn drop(&mut self) {
        if self.terminated.load(Ordering::Acquire) {
            PROCESSES.fetch_sub(1, Ordering::AcqRel);
            return;
        }
        let process = self.process.clone();
        let fallback = process.clone();
        if std::thread::Builder::new()
            .name("rust-shizuku-process-cleanup".into())
            .spawn(move || {
                let stopped = rpc(&process, PROCESS, 6, |_, _| Ok(()), |_, _| Ok(()));
                let deadline = Instant::now() + Duration::from_secs(5);
                if stopped.is_ok() {
                    while Instant::now() < deadline {
                        if rpc_int(&process, PROCESS, 7).is_ok_and(|alive| alive == 0) {
                            PROCESSES.fetch_sub(1, Ordering::AcqRel);
                            return;
                        }
                        std::thread::sleep(Duration::from_millis(50));
                    }
                }
                quarantine(process);
            })
            .is_err()
        {
            quarantine(fallback);
        }
    }
}
static UNCLEARED: OnceLock<Mutex<Vec<Object>>> = OnceLock::new();
static RETRYING: AtomicBool = AtomicBool::new(false);
/// Run on the caller's bounded worker. Cleanup and observation remain allowed
/// after revocation and never create a new process or use a different identity.
pub fn retry_cleanup() -> Result<(usize, usize), String> {
    if RETRYING
        .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
        .is_err()
    {
        return Err("Shizuku远程进程清理复查已经在运行".into());
    }
    struct Permit;
    impl Drop for Permit {
        fn drop(&mut self) {
            RETRYING.store(false, Ordering::Release);
        }
    }
    let _permit = Permit;
    let pending = {
        let mut slot = UNCLEARED
            .get_or_init(|| Mutex::new(Vec::new()))
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        std::mem::take(&mut *slot)
    };
    let examined = pending.len();
    let mut released = 0;
    for process in pending {
        let already_stopped = rpc_int(&process, PROCESS, 7).is_ok_and(|alive| alive == 0);
        let stopped =
            already_stopped || rpc(&process, PROCESS, 6, |_, _| Ok(()), |_, _| Ok(())).is_ok();
        let deadline = Instant::now() + Duration::from_secs(5);
        let mut confirmed = already_stopped;
        while stopped && !confirmed && Instant::now() < deadline {
            confirmed = rpc_int(&process, PROCESS, 7).is_ok_and(|alive| alive == 0);
            if !confirmed {
                std::thread::sleep(Duration::from_millis(50));
            }
        }
        if confirmed {
            PROCESSES.fetch_sub(1, Ordering::AcqRel);
            released += 1;
        } else {
            quarantine(process);
        }
    }
    Ok((examined, released))
}
fn quarantine(process: Object) {
    UNCLEARED
        .get_or_init(|| Mutex::new(Vec::new()))
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .push(process);
    shared()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .status
        .error = Some("Shizuku远程进程清理未确认，相关句柄预算仍保留".into());
}
