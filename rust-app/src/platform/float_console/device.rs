use super::*;
use crate::{
    composer::{ComposerCommand, ComposerEvent, Submission, SubmitMode},
    platform::{android, service_framework as framework},
    state::Action,
};
use jni::{Env, JValue, jni_sig, jni_str, objects::JObject, refs::Global};
use std::{
    collections::VecDeque,
    sync::{
        Mutex, OnceLock,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};
type Object = Arc<Global<JObject<'static>>>;
mod ball;
mod buttons;
static STATE: OnceLock<Mutex<State>> = OnceLock::new();
static POSTED: AtomicBool = AtomicBool::new(false);
static NEXT: AtomicU64 = AtomicU64::new(1);
static NEXT_ACCESSIBILITY: AtomicU64 = AtomicU64::new(0x4000_0000);
const CHANNEL: &str = "jagent_rust_float_console";
const NOTIFICATION: i32 = 1241;
// Existing Settings stores the scrollable content height, excluding header,
// input and actions. The Canvas preview spans [92dp, window_height - 133dp].
const BALL_PANEL_GAP_DP: f32 = 56.0;
const CONTENT_CHROME_DP: f32 = BALL_PANEL_GAP_DP + 225.0;
#[derive(Clone, PartialEq, Eq)]
enum Control {
    Expand,
    Collapse,
    Next,
    New,
    Send(Option<RunKey>, Option<String>, bool),
    Stop(RunKey),
    Open(Option<RunKey>, Option<String>),
    Resize,
    Scroll(bool),
}
#[derive(Clone, PartialEq, Eq)]
struct AccessibilityBinding {
    handle: u64,
    expanded: bool,
    selected: Option<RunKey>,
    conversation: Option<String>,
    pending: bool,
    immediately: bool,
    runs: Vec<RunKey>,
}
#[derive(Clone)]
struct AccessibleControl {
    id: i32,
    label: &'static str,
    control: Control,
}
#[derive(Clone)]
struct Hit {
    rect: [f32; 4],
    control: Control,
}
#[derive(Clone)]
struct Pointer {
    raw: [f32; 2],
    x: i32,
    y: i32,
    width: i32,
    height: i32,
    control: Option<Control>,
    moved: bool,
    resize: bool,
    scroll: bool,
    scroll_offset: f32,
    stamp: SettingsStamp,
}
#[derive(Clone)]
struct Window {
    handle: u64,
    view: Object,
    input: Object,
    manager: Object,
    params: Object,
    paint: Object,
    host: usize,
    expanded: bool,
    width: i32,
    height: i32,
    x: i32,
    y: i32,
    density: f32,
    screen: [i32; 2],
    drawn: bool,
    hits: Vec<Hit>,
    pointer: Option<Pointer>,
    input_key: String,
    scroll_offset: f32,
    text_layout: Option<((Arc<String>, i32, i32, i32), Object)>,
    ball: Arc<Mutex<ball::Motion>>,
    content_fixed: bool,
    widgets: Option<buttons::Widgets>,
}
#[derive(Default)]
struct State {
    host: Option<Arc<RuntimeHost>>,
    snapshot: Option<Arc<Snapshot>>,
    service: Option<Object>,
    confirmed: bool,
    start_pending: Option<Instant>,
    blocked: bool,
    window: Option<Window>,
    manual: Option<Option<RunKey>>,
    conversation: Option<String>,
    pending: Option<crate::platform::permissions::OperationKey>,
    resize_pending: Option<Resize>,
    resizes: VecDeque<Resize>,
    clear_input: Option<(u64, String)>,
    notice: String,
    errors: VecDeque<String>,
    facts: Option<(bool, u64)>,
    cleanup_retry: bool,
    drafts: std::collections::BTreeMap<String, String>,
    stop_requested: bool,
    accessible: Option<(AccessibilityBinding, Vec<AccessibleControl>)>,
    button_request: Option<AccessibilityBinding>,
}
fn state() -> &'static Mutex<State> {
    STATE.get_or_init(|| Mutex::new(State::default()))
}
fn report(message: String) {
    let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
    if s.errors.back() != Some(&message) {
        if s.errors.len() == 16 {
            s.errors.pop_front();
        }
        s.errors.push_back(message.clone());
    }
    s.notice = message;
}
pub(crate) fn take_errors() -> Vec<String> {
    state()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .errors
        .drain(..)
        .collect()
}
pub(crate) fn take_resizes() -> Vec<Resize> {
    state()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .resizes
        .drain(..)
        .collect()
}
pub(crate) fn selection() -> Option<Option<RunKey>> {
    state()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .manual
        .clone()
}
pub(crate) fn conversation() -> Option<String> {
    state()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .conversation
        .clone()
}
pub(crate) fn start(host: Arc<RuntimeHost>) -> Result<(), String> {
    let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
    if s.host
        .as_ref()
        .is_some_and(|actual| !Arc::ptr_eq(actual, &host))
    {
        return Err("悬浮控制台已有不同运行宿主".into());
    }
    s.host = Some(host);
    Ok(())
}
pub(crate) fn configure(snapshot: Snapshot) -> Result<(), String> {
    let capabilities = crate::platform::automation::capabilities();
    let facts = (android::activity_visible(), capabilities.service_generation);
    let changed = {
        let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
        let changed = s.snapshot.as_deref() != Some(&snapshot);
        if changed {
            if s.snapshot.as_ref().is_some_and(|old| {
                old.resident != snapshot.resident || old.while_running != snapshot.while_running
            }) {
                s.blocked = false;
            }
            s.snapshot = Some(Arc::new(snapshot));
        }
        // Foreground and accessibility attachment are actual platform facts,
        // not Settings flags, and can change without a transcript update.
        let platform_changed = s.facts != Some(facts);
        if platform_changed && facts.0 {
            s.blocked = false;
        }
        s.facts = Some(facts);
        changed
            || platform_changed
            || s.start_pending.is_some()
            || s.cleanup_retry
            || s.window.as_ref().is_some_and(|window| {
                window
                    .ball
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .refresh_due()
            })
    };
    if changed {
        post()?;
    }
    Ok(())
}
fn post() -> Result<(), String> {
    if POSTED.swap(true, Ordering::AcqRel) {
        return Ok(());
    }
    if let Err(error) = android::post_to_main(|| {
        POSTED.store(false, Ordering::Release);
        if let Err(error) = reconcile() {
            report(error);
        }
    }) {
        POSTED.store(false, Ordering::Release);
        return Err(error);
    }
    Ok(())
}
pub(crate) fn event(event: &Event) {
    let mut changed = false;
    {
        let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
        match event {
            Event::Composer(ComposerEvent::Accepted {
                operation,
                conversation_id,
                ..
            }) if s.pending.as_ref() == Some(operation) => {
                s.pending = None;
                s.conversation = Some(conversation_id.clone());
                s.manual = None;
                s.clear_input = Some((
                    operation.generation,
                    input_key(operation.conversation_id.as_deref()),
                ));
                s.drafts
                    .remove(&input_key(operation.conversation_id.as_deref()));
                s.notice = "输入已由运行线程持久受理".into();
                changed = true;
            }
            Event::Composer(ComposerEvent::CommandFinished { operation, result })
                if s.pending.as_ref() == Some(operation) =>
            {
                s.pending = None;
                if result.is_ok() {
                    s.clear_input = Some((
                        operation.generation,
                        input_key(operation.conversation_id.as_deref()),
                    ));
                    s.drafts
                        .remove(&input_key(operation.conversation_id.as_deref()));
                }
                s.notice = result
                    .as_ref()
                    .map(|_| "本地命令已完成".into())
                    .unwrap_or_else(|error| error.clone());
                changed = true;
            }
            Event::Composer(ComposerEvent::Rejected { operation, error })
                if s.pending.as_ref() == Some(operation) =>
            {
                s.pending = None;
                s.notice = error.clone();
                changed = true;
            }
            Event::Composer(ComposerEvent::Cancelled { operation })
                if s.pending.as_ref() == Some(operation) =>
            {
                s.pending = None;
                s.notice = "输入已取消，草稿保留".into();
                changed = true;
            }
            Event::SettingsMutationFinished {
                request_id,
                expected,
                result,
            } if s.resize_pending.as_ref().is_some_and(|resize| {
                &resize.request_id == request_id && &resize.expected == expected
            }) =>
            {
                s.resize_pending = None;
                s.notice = result
                    .as_ref()
                    .map(|_| "悬浮尺寸已保存".into())
                    .unwrap_or_else(|error| error.clone());
                changed = true;
            }
            _ => (),
        }
    }
    if changed {
        let _ = post();
    }
}
fn desired(snapshot: &Snapshot) -> bool {
    snapshot.resident || snapshot.while_running && !snapshot.runs.is_empty()
}
fn input_key(conversation: Option<&str>) -> String {
    conversation.unwrap_or("__new__").to_owned()
}
fn save_draft(window: &Window) -> Result<(), String> {
    let text = android::invoke(|env| {
        let editable = env
            .call_method(
                window.input.as_ref(),
                jni_str!("getText"),
                jni_sig!("()Landroid/text/Editable;"),
                &[],
            )?
            .l()?;
        let string = env
            .call_method(
                &editable,
                jni_str!("toString"),
                jni_sig!("()Ljava/lang/String;"),
                &[],
            )?
            .l()?;
        android::string_value(env, string, crate::composer::MAX_TEXT_BYTES)
    })?;
    let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
    if text.is_empty() {
        s.drafts.remove(&window.input_key);
    } else {
        if s.drafts.len() >= 32 && !s.drafts.contains_key(&window.input_key) {
            return Err("悬浮草稿已达到32个上限；先发送或清空输入再切换".into());
        }
        s.drafts.insert(window.input_key.clone(), text);
    }
    Ok(())
}
fn context() -> Result<(Object, usize), String> {
    let context = crate::platform::automation::accessibility::service(None)
        .or_else(|_| android::application_context())?;
    let identity = Arc::as_ptr(&context) as usize;
    // Global-ref Arc pointers may be replaced by a harmless retain operation;
    // the accessibility generation and application choice are stable facts.
    let capabilities = crate::platform::automation::capabilities();
    let host = if capabilities.connected {
        capabilities.service_generation as usize | 1
    } else {
        0
    };
    let _ = identity;
    Ok((context, host))
}
fn reconcile() -> Result<(), String> {
    let (snapshot, service, confirmed, blocked, start_pending, window, clear) = {
        let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
        (
            s.snapshot.clone(),
            s.service.clone(),
            s.confirmed,
            s.blocked,
            s.start_pending,
            s.window.clone(),
            s.clear_input.take(),
        )
    };
    let Some(snapshot) = snapshot else {
        return Ok(());
    };
    if state()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .cleanup_retry
    {
        remove()?;
    }
    let wanted = desired(&snapshot);
    if let (Some((handle, key)), Some(window)) = (clear, window.as_ref()) {
        if window.handle == handle && window.input_key == key {
            android::invoke(|env| {
                let empty = env.new_string("")?;
                env.call_method(
                    window.input.as_ref(),
                    jni_str!("setText"),
                    jni_sig!("(Ljava/lang/CharSequence;)V"),
                    &[JValue::Object(&empty)],
                )?;
                hide_keyboard(env, window)?;
                Ok(())
            })?;
        }
    }
    if !wanted {
        remove()?;
        if confirmed && let Some(service) = service {
            state()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .stop_requested = true;
            framework::stop_instance(service.as_ref())?;
        }
        return Ok(());
    }
    if service.is_none() && !blocked && start_pending.is_none() {
        let intent = framework::invoke(|env| {
            let context = android::application_context()
                .map_err(|_| jni::errors::Error::NullPtr("console context"))?;
            framework::intent(
                env,
                context.as_ref(),
                SERVICE_CLASS,
                ACTION_REFRESH,
                None,
                &[],
            )
        })?;
        state()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .start_pending = Some(Instant::now());
        if let Err(error) = framework::start_component(&intent) {
            let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
            s.start_pending = None;
            s.blocked = true;
            return Err(error);
        }
        return Ok(());
    }
    if start_pending.is_some_and(|start| start.elapsed() > Duration::from_secs(4)) && !confirmed {
        let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
        s.start_pending = None;
        s.blocked = true;
        drop(s);
        remove()?;
        return Err("悬浮服务未确认进入前台，窗口未展示；请在权限页检查后重新启用".into());
    }
    if android::activity_visible() || !confirmed {
        remove()?;
        return Ok(());
    }
    let (context, host) = context()?;
    if window.as_ref().is_some_and(|window| window.host != host) {
        remove()?;
    }
    if state()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .window
        .is_none()
    {
        create(context, host, &snapshot)?;
    }
    update(&snapshot)
}
pub(crate) fn handle_service(request: android::ServiceRequest) -> Result<i32, String> {
    match request.kind {
        1 => {
            let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
            s.service = Some(Arc::new(request.instance));
            s.confirmed = false;
        }
        2 => {
            let service = Arc::new(request.instance);
            // Complete the foreground start before reconcile can stop a finished run's service.
            let notification = framework::invoke(|env| {
                let context = android::application_context()
                    .map_err(|_| jni::errors::Error::NullPtr("console context"))?;
                let manager = framework::system_service(env, context.as_ref(), "notification")?;
                framework::channel(env, &manager, CHANNEL, "JAgent 悬浮控制台", 2)?;
                let intent = framework::intent(
                    env,
                    context.as_ref(),
                    "com.suixing.agent.rust.platform.RustNativeActivity",
                    "android.intent.action.MAIN",
                    None,
                    &[],
                )?;
                let open = framework::pending(
                    env,
                    context.as_ref(),
                    1242,
                    &intent,
                    framework::PendingKind::Activity,
                )?;
                framework::notification(
                    env,
                    context.as_ref(),
                    CHANNEL,
                    "JAgent 悬浮控制台",
                    "可查看任务、停止或提交输入",
                    true,
                    &open,
                    None,
                )
            })?;
            framework::promote_special(service.as_ref(), NOTIFICATION, &notification)?;
            let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
            s.service = Some(service);
            s.confirmed = true;
            s.start_pending = None;
            s.blocked = false;
            drop(s);
            post()?;
        }
        3 | 4 | 5 => {
            let service = state()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .service
                .clone();
            let owns = service.as_ref().is_some_and(|service| {
                framework::same_instance(service.as_ref(), &request.instance).unwrap_or(false)
            });
            if owns {
                {
                    let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
                    s.service = None;
                    s.confirmed = false;
                    s.start_pending = None;
                    s.blocked = !s.stop_requested;
                    s.stop_requested = false;
                }
                remove()?;
                if request.kind != 3 {
                    framework::stop_instance(&request.instance)?;
                }
            }
        }
        6 => {
            post()?;
        }
        _ => (),
    }
    Ok(2)
}
fn dimensions(env: &mut Env, context: &Object) -> jni::errors::Result<(f32, [i32; 2])> {
    let resources = env
        .call_method(
            context.as_ref(),
            jni_str!("getResources"),
            jni_sig!("()Landroid/content/res/Resources;"),
            &[],
        )?
        .l()?;
    let metrics = env
        .call_method(
            &resources,
            jni_str!("getDisplayMetrics"),
            jni_sig!("()Landroid/util/DisplayMetrics;"),
            &[],
        )?
        .l()?;
    let density = env
        .get_field(&metrics, jni_str!("density"), jni_sig!("F"))?
        .f()?
        .clamp(0.5, 8.0);
    let width = env
        .get_field(&metrics, jni_str!("widthPixels"), jni_sig!("I"))?
        .i()?
        .max(1);
    let height = env
        .get_field(&metrics, jni_str!("heightPixels"), jni_sig!("I"))?
        .i()?
        .max(1);
    Ok((density, [width, height]))
}
fn create(context: Object, host: usize, snapshot: &Snapshot) -> Result<(), String> {
    let handle = NEXT
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |value| {
            value
                .checked_add(1)
                .filter(|value| *value <= i64::MAX as u64)
        })
        .map_err(|_| "悬浮窗口身份耗尽")?;
    let window = android::invoke(|env| {
        if host == 0
            && !env
                .call_static_method(
                    jni_str!("android/provider/Settings"),
                    jni_str!("canDrawOverlays"),
                    jni_sig!("(Landroid/content/Context;)Z"),
                    &[JValue::Object(context.as_ref())],
                )?
                .z()?
        {
            return Err(jni::errors::Error::NullPtr("console overlay permission"));
        }
        let (density, screen) = dimensions(env, &context)?;
        let size = (48.0 * density) as i32;
        let class = android::generated_class("NativeFloatConsole")
            .map_err(|_| jni::errors::Error::NullPtr("console view class"))?;
        let view = env.new_object(
            class.as_ref(),
            jni_sig!("(Landroid/content/Context;J)V"),
            &[
                JValue::Object(context.as_ref()),
                JValue::Long(handle as i64),
            ],
        )?;
        env.call_method(
            &view,
            jni_str!("setWillNotDraw"),
            jni_sig!("(Z)V"),
            &[JValue::Bool(false)],
        )?;
        let input = env.new_object(
            jni_str!("android/widget/EditText"),
            jni_sig!("(Landroid/content/Context;)V"),
            &[JValue::Object(context.as_ref())],
        )?;
        let filter = env.new_object(
            jni_str!("android/text/InputFilter$LengthFilter"),
            jni_sig!("(I)V"),
            &[JValue::Int((crate::composer::MAX_TEXT_BYTES / 3) as i32)],
        )?;
        let filters =
            env.new_object_array(1, jni_str!("android/text/InputFilter"), &JObject::null())?;
        env.set_object_array_element(&filters, 0, &filter)?;
        env.call_method(
            &input,
            jni_str!("setFilters"),
            jni_sig!("([Landroid/text/InputFilter;)V"),
            &[JValue::Object(&filters)],
        )?;
        let key = input_key(snapshot.conversation.as_deref());
        let draft = state()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .drafts
            .get(&key)
            .cloned()
            .unwrap_or_default();
        let draft = env.new_string(draft)?;
        env.call_method(
            &input,
            jni_str!("setText"),
            jni_sig!("(Ljava/lang/CharSequence;)V"),
            &[JValue::Object(&draft)],
        )?;
        env.call_method(
            &input,
            jni_str!("setInputType"),
            jni_sig!("(I)V"),
            &[JValue::Int(1 | 0x20000 | 0x4000)],
        )?;
        env.call_method(
            &input,
            jni_str!("setSingleLine"),
            jni_sig!("(Z)V"),
            &[JValue::Bool(false)],
        )?;
        env.call_method(
            &input,
            jni_str!("setMaxLines"),
            jni_sig!("(I)V"),
            &[JValue::Int(3)],
        )?;
        let hint = env.new_string("输入任务或插话…")?;
        env.call_method(
            &input,
            jni_str!("setHint"),
            jni_sig!("(Ljava/lang/CharSequence;)V"),
            &[JValue::Object(&hint)],
        )?;
        env.call_method(
            &input,
            jni_str!("setVisibility"),
            jni_sig!("(I)V"),
            &[JValue::Int(8)],
        )?;
        let input_params = env.new_object(
            jni_str!("android/widget/FrameLayout$LayoutParams"),
            jni_sig!("(III)V"),
            &[
                JValue::Int(-1),
                JValue::Int((60.0 * density) as i32),
                JValue::Int(0x50),
            ],
        )?;
        for (name, value) in [
            (jni_str!("leftMargin"), (12.0 * density) as i32),
            (jni_str!("rightMargin"), (76.0 * density) as i32),
            (jni_str!("bottomMargin"), (12.0 * density) as i32),
        ] {
            env.set_field(&input_params, name, jni_sig!("I"), JValue::Int(value))?;
        }
        env.call_method(
            &view,
            jni_str!("addView"),
            jni_sig!("(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V"),
            &[JValue::Object(&input), JValue::Object(&input_params)],
        )?;
        let params = env.new_object(
            jni_str!("android/view/WindowManager$LayoutParams"),
            jni_sig!("(IIIII)V"),
            &[
                JValue::Int(size),
                JValue::Int(size),
                JValue::Int(if host == 0 { 2038 } else { 2032 }),
                JValue::Int(0x08 | 0x20 | 0x100),
                JValue::Int(-3),
            ],
        )?;
        env.set_field(
            &params,
            jni_str!("gravity"),
            jni_sig!("I"),
            JValue::Int(0x33),
        )?;
        env.set_field(
            &params,
            jni_str!("softInputMode"),
            jni_sig!("I"),
            JValue::Int(0x10),
        )?;
        env.set_field(
            &params,
            jni_str!("x"),
            jni_sig!("I"),
            JValue::Int((screen[0] - size - (12.0 * density) as i32).max(0)),
        )?;
        env.set_field(
            &params,
            jni_str!("y"),
            jni_sig!("I"),
            JValue::Int((screen[1] / 3).max(0)),
        )?;
        let manager = framework::system_service(env, context.as_ref(), "window")?;
        let paint = env.new_object(
            jni_str!("android/text/TextPaint"),
            jni_sig!("(I)V"),
            &[JValue::Int(1)],
        )?;
        let view = Arc::new(env.new_global_ref(view)?);
        let input = Arc::new(env.new_global_ref(input)?);
        let manager = Arc::new(manager);
        let params = Arc::new(env.new_global_ref(params)?);
        let paint = Arc::new(env.new_global_ref(paint)?);
        let window = Window {
            handle,
            view: view.clone(),
            input,
            manager: manager.clone(),
            params: params.clone(),
            paint,
            host,
            expanded: false,
            width: size,
            height: size,
            x: (screen[0] - size - (12.0 * density) as i32).max(0),
            y: screen[1] / 3,
            density,
            screen,
            drawn: false,
            hits: Vec::new(),
            pointer: None,
            input_key: key,
            scroll_offset: 0.0,
            text_layout: None,
            ball: Arc::new(Mutex::new(ball::Motion::default())),
            content_fixed: false,
            widgets: None,
        };
        // Retained globals precede addView; failed subsequent calls cannot lose
        // a view which Android may already have attached.
        {
            state().lock().unwrap_or_else(|e| e.into_inner()).window = Some(window.clone());
        }
        env.call_method(
            manager.as_ref(),
            jni_str!("addView"),
            jni_sig!("(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V"),
            &[
                JValue::Object(view.as_ref()),
                JValue::Object(params.as_ref()),
            ],
        )?;
        Ok(window)
    });
    match window {
        Ok(_) => update(snapshot),
        Err(error) => {
            let _ = remove();
            Err(error)
        }
    }
}
fn remove() -> Result<(), String> {
    let window = state()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .window
        .clone();
    let Some(window) = window else {
        return Ok(());
    };
    if let Err(error) = save_draft(&window) {
        state()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .cleanup_retry = true;
        return Err(error);
    }
    let removed = android::invoke(|env| {
        env.call_method(
            window.manager.as_ref(),
            jni_str!("removeViewImmediate"),
            jni_sig!("(Landroid/view/View;)V"),
            &[JValue::Object(window.view.as_ref())],
        )?;
        Ok(())
    });
    let detached = removed.is_ok()
        || android::invoke(|env| {
            env.call_method(
                window.view.as_ref(),
                jni_str!("isAttachedToWindow"),
                jni_sig!("()Z"),
                &[],
            )?
            .z()
        })
        .is_ok_and(|attached| !attached);
    if detached {
        let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
        if s.window
            .as_ref()
            .is_some_and(|actual| actual.handle == window.handle)
        {
            s.window = None;
        }
        s.cleanup_retry = false;
        Ok(())
    } else {
        state()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .cleanup_retry = true;
        Err("悬浮窗口移除未确认；保留原宿主和View引用，稍后继续清理".into())
    }
}
fn update(snapshot: &Snapshot) -> Result<(), String> {
    let (mut window, resize_pending, pending) = {
        let s = state().lock().unwrap_or_else(|e| e.into_inner());
        (
            s.window.clone(),
            s.resize_pending.is_some(),
            s.pending.is_some(),
        )
    };
    let Some(mut window) = window.take() else {
        return Ok(());
    };
    let (context, _) = context()?;
    android::invoke(|env| {
        let description = env.new_string(accessibility_description(snapshot, window.expanded))?;
        env.call_method(
            window.view.as_ref(),
            jni_str!("setContentDescription"),
            jni_sig!("(Ljava/lang/CharSequence;)V"),
            &[JValue::Object(&description)],
        )?;
        for method in [jni_str!("setClickable"), jni_str!("setFocusable")] {
            env.call_method(
                window.view.as_ref(),
                method,
                jni_sig!("(Z)V"),
                &[JValue::Bool(true)],
            )?;
        }
        env.call_method(
            window.view.as_ref(),
            jni_str!("setImportantForAccessibility"),
            jni_sig!("(I)V"),
            &[JValue::Int(1)],
        )?;
        window
            .ball
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .refresh(env, context.as_ref(), !snapshot.runs.is_empty())
    })?;
    let (density, screen) = android::invoke(|env| dimensions(env, &context))?;
    window.density = density;
    window.screen = screen;
    let key = input_key(snapshot.conversation.as_deref());
    if window.input_key != key {
        save_draft(&window)?;
        let text = state()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .drafts
            .get(&key)
            .cloned()
            .unwrap_or_default();
        android::invoke(|env| {
            let text = env.new_string(text)?;
            env.call_method(
                window.input.as_ref(),
                jni_str!("setText"),
                jni_sig!("(Ljava/lang/CharSequence;)V"),
                &[JValue::Object(&text)],
            )?;
            Ok(())
        })?;
        window.input_key = key;
        window.scroll_offset = 0.0;
    }
    if window.pointer.is_none() && !resize_pending {
        if window.expanded {
            window.width = ((if snapshot.width_dp > 0 {
                snapshot.width_dp
            } else {
                268
            }) as f32
                * window.density) as i32;
            window.width = panel_width(&window, window.width);
            window.content_fixed = snapshot.height_dp > 0;
            let content = if window.content_fixed {
                snapshot.height_dp.clamp(160, 640) as f32 * window.density
            } else {
                android::invoke(|env| preview_height(env, &window, snapshot))?
                    .clamp(24.0 * window.density, 640.0 * window.density)
            };
            window.height = (content + CONTENT_CHROME_DP * window.density).round() as i32;
        } else {
            window.width = (48.0 * window.density) as i32;
            window.height = window.width;
        }
    }
    geometry(&mut window)?;
    android::invoke(|env| {
        env.call_method(
            window.input.as_ref(),
            jni_str!("setVisibility"),
            jni_sig!("(I)V"),
            &[JValue::Int(if window.expanded { 0 } else { 8 })],
        )?;
        env.call_method(
            window.input.as_ref(),
            jni_str!("setEnabled"),
            jni_sig!("(Z)V"),
            &[JValue::Bool(!pending)],
        )?;
        env.call_method(
            window.view.as_ref(),
            jni_str!("invalidate"),
            jni_sig!("()V"),
            &[],
        )?;
        Ok(())
    })?;
    let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
    if s.window
        .as_ref()
        .is_some_and(|actual| actual.handle == window.handle)
    {
        s.window = Some(window);
    }
    Ok(())
}
fn panel_width(window: &Window, requested: i32) -> i32 {
    let margin = (24.0 * window.density) as i32;
    let available = (window.screen[0] - margin).max(1);
    let min = ((200.0 * window.density) as i32).min(available);
    let max = ((420.0 * window.density) as i32).min(available).max(min);
    requested.clamp(min, max)
}
fn content_height_dp(window: &Window) -> i32 {
    (window.height as f32 / window.density - CONTENT_CHROME_DP)
        .round()
        .clamp(160.0, 640.0) as i32
}
fn hide_keyboard(env: &mut Env, window: &Window) -> jni::errors::Result<()> {
    let context = android::application_context()
        .map_err(|_| jni::errors::Error::NullPtr("console IME context"))?;
    let name = env.new_string("input_method")?;
    let manager = env
        .call_method(
            context.as_ref(),
            jni_str!("getSystemService"),
            jni_sig!("(Ljava/lang/String;)Ljava/lang/Object;"),
            &[JValue::Object(&name)],
        )?
        .l()?;
    let token = env
        .call_method(
            window.input.as_ref(),
            jni_str!("getWindowToken"),
            jni_sig!("()Landroid/os/IBinder;"),
            &[],
        )?
        .l()?;
    if !manager.is_null() && !token.is_null() {
        env.call_method(
            &manager,
            jni_str!("hideSoftInputFromWindow"),
            jni_sig!("(Landroid/os/IBinder;I)Z"),
            &[JValue::Object(&token), JValue::Int(0)],
        )?;
    }
    Ok(())
}
fn preview_height(env: &mut Env, window: &Window, snapshot: &Snapshot) -> jni::errors::Result<f32> {
    if let Some(((preview, width, font_scale, _), layout)) = &window.text_layout {
        if preview == &snapshot.preview
            && *width == window.width
            && *font_scale == snapshot.font_scale
        {
            return Ok(env
                .call_method(layout.as_ref(), jni_str!("getHeight"), jni_sig!("()I"), &[])?
                .i()? as f32);
        }
    }
    let preview = if snapshot.preview.is_empty() {
        "可输入任务，或切换查看正在运行的任务。"
    } else {
        &snapshot.preview
    };
    let text = env.new_string(preview)?;
    let paint = env.new_object(
        jni_str!("android/text/TextPaint"),
        jni_sig!("(I)V"),
        &[JValue::Int(1)],
    )?;
    let font = match snapshot.font_scale {
        0 => 12.0,
        2 => 16.0,
        3 => 18.0,
        _ => 14.0,
    };
    env.call_method(
        &paint,
        jni_str!("setTextSize"),
        jni_sig!("(F)V"),
        &[JValue::Float(font * window.density)],
    )?;
    let length = env
        .call_method(&text, jni_str!("length"), jni_sig!("()I"), &[])?
        .i()?;
    let builder = env.call_static_method(jni_str!("android/text/StaticLayout$Builder"), jni_str!("obtain"), jni_sig!("(Ljava/lang/CharSequence;IILandroid/text/TextPaint;I)Landroid/text/StaticLayout$Builder;"), &[JValue::Object(&text), JValue::Int(0), JValue::Int(length), JValue::Object(&paint), JValue::Int((window.width as f32 - 24.0 * window.density).max(1.0) as i32)])?.l()?;
    let layout = env
        .call_method(
            &builder,
            jni_str!("build"),
            jni_sig!("()Landroid/text/StaticLayout;"),
            &[],
        )?
        .l()?;
    Ok(env
        .call_method(&layout, jni_str!("getHeight"), jni_sig!("()I"), &[])?
        .i()? as f32)
}
fn geometry(window: &mut Window) -> Result<(), String> {
    let margin = (24.0 * window.density) as i32;
    let min_width = if window.expanded {
        (200.0 * window.density) as i32
    } else {
        (48.0 * window.density) as i32
    };
    let min_height = if window.expanded {
        ((if window.content_fixed { 160.0 } else { 24.0 } + CONTENT_CHROME_DP) * window.density)
            as i32
    } else {
        min_width
    };
    window.width = window.width.clamp(
        min_width.min(window.screen[0]),
        (window.screen[0] - margin).max(min_width.min(window.screen[0])),
    );
    if window.expanded {
        window.width = panel_width(window, window.width);
    }
    window.height = window.height.clamp(
        min_height.min(window.screen[1]),
        (window.screen[1] - margin)
            .min(if window.expanded {
                ((640.0 + CONTENT_CHROME_DP) * window.density) as i32
            } else {
                window.screen[1]
            })
            .max(min_height.min(window.screen[1])),
    );
    window.x = window.x.clamp(0, (window.screen[0] - window.width).max(0));
    window.y = window.y.clamp(0, (window.screen[1] - window.height).max(0));
    android::invoke(|env| {
        for (name, value) in [
            (jni_str!("width"), window.width),
            (jni_str!("height"), window.height),
            (jni_str!("x"), window.x),
            (jni_str!("y"), window.y),
            (
                jni_str!("flags"),
                0x20 | 0x100 | if window.expanded { 0 } else { 0x08 },
            ),
        ] {
            env.set_field(
                window.params.as_ref(),
                name,
                jni_sig!("I"),
                JValue::Int(value),
            )?;
        }
        env.call_method(
            window.manager.as_ref(),
            jni_str!("updateViewLayout"),
            jni_sig!("(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V"),
            &[
                JValue::Object(window.view.as_ref()),
                JValue::Object(window.params.as_ref()),
            ],
        )?;
        Ok(())
    })
}
pub(crate) fn draw(env: &mut Env, handle: u64, canvas: &JObject) -> jni::errors::Result<()> {
    let (window, snapshot, notice) = {
        let s = state().lock().unwrap_or_else(|e| e.into_inner());
        (s.window.clone(), s.snapshot.clone(), s.notice.clone())
    };
    let (Some(window), Some(snapshot)) = (window, snapshot) else {
        return Ok(());
    };
    if window.handle != handle {
        return Ok(());
    }
    let d = window.density;
    let width = window.width as f32;
    let panel_offset = if window.expanded {
        BALL_PANEL_GAP_DP * d
    } else {
        0.0
    };
    let height = window.height as f32 - panel_offset;
    let paint = window.paint.as_ref();
    if window.expanded {
        env.call_method(canvas, jni_str!("save"), jni_sig!("()I"), &[])?;
        env.call_method(
            canvas,
            jni_str!("translate"),
            jni_sig!("(FF)V"),
            &[JValue::Float(width - 48.0 * d), JValue::Float(0.0)],
        )?;
        window.ball.lock().unwrap_or_else(|e| e.into_inner()).draw(
            env,
            canvas,
            window.view.as_ref(),
            paint,
            48.0 * d,
            48.0 * d,
            d,
        )?;
        env.call_method(canvas, jni_str!("restore"), jni_sig!("()V"), &[])?;
        env.call_method(canvas, jni_str!("save"), jni_sig!("()I"), &[])?;
        env.call_method(
            canvas,
            jni_str!("translate"),
            jni_sig!("(FF)V"),
            &[JValue::Float(0.0), JValue::Float(panel_offset)],
        )?;
    }
    let dark = if snapshot.theme == 0 {
        let context = android::application_context()
            .map_err(|_| jni::errors::Error::NullPtr("console context"))?;
        let resources = env
            .call_method(
                context.as_ref(),
                jni_str!("getResources"),
                jni_sig!("()Landroid/content/res/Resources;"),
                &[],
            )?
            .l()?;
        let configuration = env
            .call_method(
                &resources,
                jni_str!("getConfiguration"),
                jni_sig!("()Landroid/content/res/Configuration;"),
                &[],
            )?
            .l()?;
        env.get_field(&configuration, jni_str!("uiMode"), jni_sig!("I"))?
            .i()?
            & 0x30
            == 0x20
    } else {
        snapshot.theme == 2
    };
    let foreground = if dark { 0xffeeeeeeu32 } else { 0xff202020 };
    let background = if dark { 0xf01b2328u32 } else { 0xf0f6f9fa };
    env.call_method(
        paint,
        jni_str!("setColor"),
        jni_sig!("(I)V"),
        &[JValue::Int(background as i32)],
    )?;
    let bounds = env.new_object(
        jni_str!("android/graphics/RectF"),
        jni_sig!("(FFFF)V"),
        &[
            JValue::Float(0.0),
            JValue::Float(0.0),
            JValue::Float(width),
            JValue::Float(height),
        ],
    )?;
    if window.expanded {
        env.call_method(
            canvas,
            jni_str!("drawRoundRect"),
            jni_sig!("(Landroid/graphics/RectF;FFLandroid/graphics/Paint;)V"),
            &[
                JValue::Object(&bounds),
                JValue::Float(16.0 * d),
                JValue::Float(16.0 * d),
                JValue::Object(paint),
            ],
        )?;
    }
    env.call_method(
        paint,
        jni_str!("setColor"),
        jni_sig!("(I)V"),
        &[JValue::Int(foreground as i32)],
    )?;
    let label = |env: &mut Env, text: &str, x: f32, y: f32, size: f32| -> jni::errors::Result<()> {
        let text = env.new_string(text)?;
        env.call_method(
            paint,
            jni_str!("setTextSize"),
            jni_sig!("(F)V"),
            &[JValue::Float(size * d)],
        )?;
        env.call_method(
            canvas,
            jni_str!("drawText"),
            jni_sig!("(Ljava/lang/String;FFLandroid/graphics/Paint;)V"),
            &[
                JValue::Object(&text),
                JValue::Float(x * d),
                JValue::Float(y * d),
                JValue::Object(paint),
            ],
        )?;
        Ok(())
    };
    let mut hits = Vec::new();
    if !window.expanded {
        window.ball.lock().unwrap_or_else(|e| e.into_inner()).draw(
            env,
            canvas,
            window.view.as_ref(),
            paint,
            width,
            height,
            d,
        )?;
        hits.push(Hit {
            rect: [0.0, 0.0, width, height],
            control: Control::Expand,
        });
    } else {
        let title = snapshot
            .selected
            .as_ref()
            .and_then(|key| snapshot.runs.iter().find(|run| &run.key == key))
            .map(|run| run.title.chars().take(14).collect::<String>())
            .unwrap_or_else(|| snapshot.title.chars().take(14).collect());
        label(env, &title, 12.0, 25.0, 15.0)?;
        label(env, "－", width / d - 30.0, 25.0, 20.0)?;
        hits.push(Hit {
            rect: [width - 44.0 * d, 0.0, width, 40.0 * d],
            control: Control::Collapse,
        });
        label(
            env,
            &format!("任务 {} · 切换", snapshot.runs.len()),
            12.0,
            55.0,
            12.0,
        )?;
        hits.push(Hit {
            rect: [0.0, 36.0 * d, 142.0 * d, 66.0 * d],
            control: Control::Next,
        });
        label(env, "新建", 150.0, 55.0, 12.0)?;
        hits.push(Hit {
            rect: [145.0 * d, 36.0 * d, 198.0 * d, 66.0 * d],
            control: Control::New,
        });
        let status = snapshot
            .selected
            .as_ref()
            .and_then(|key| snapshot.runs.iter().find(|run| &run.key == key))
            .map(|run| run.state.as_str())
            .unwrap_or("空闲");
        label(env, status, 12.0, 80.0, 12.0)?;
        let preview = if snapshot.preview.is_empty() {
            "可输入任务，或切换查看正在运行的任务。"
        } else {
            &snapshot.preview
        };
        let layout_key = (
            snapshot.preview.clone(),
            window.width,
            snapshot.font_scale,
            foreground as i32,
        );
        let layout = if let Some((key, layout)) = &window.text_layout {
            if *key == layout_key {
                Some(layout.clone())
            } else {
                None
            }
        } else {
            None
        };
        let layout = match layout {
            Some(layout) => layout,
            None => {
                let text = env.new_string(preview)?;
                let text_paint = env.new_object(
                    jni_str!("android/text/TextPaint"),
                    jni_sig!("(I)V"),
                    &[JValue::Int(1)],
                )?;
                let font = match snapshot.font_scale {
                    0 => 12.0,
                    2 => 16.0,
                    3 => 18.0,
                    _ => 14.0,
                };
                env.call_method(
                    &text_paint,
                    jni_str!("setTextSize"),
                    jni_sig!("(F)V"),
                    &[JValue::Float(font * d)],
                )?;
                env.call_method(
                    &text_paint,
                    jni_str!("setColor"),
                    jni_sig!("(I)V"),
                    &[JValue::Int(foreground as i32)],
                )?;
                let length = env
                    .call_method(&text, jni_str!("length"), jni_sig!("()I"), &[])?
                    .i()?;
                let builder=env.call_static_method(jni_str!("android/text/StaticLayout$Builder"),jni_str!("obtain"),jni_sig!("(Ljava/lang/CharSequence;IILandroid/text/TextPaint;I)Landroid/text/StaticLayout$Builder;"),&[JValue::Object(&text),JValue::Int(0),JValue::Int(length),JValue::Object(&text_paint),JValue::Int((width-24.0*d).max(1.0) as i32)])?.l()?;
                let layout = env
                    .call_method(
                        &builder,
                        jni_str!("build"),
                        jni_sig!("()Landroid/text/StaticLayout;"),
                        &[],
                    )?
                    .l()?;
                let layout = Arc::new(env.new_global_ref(layout)?);
                let mut state = state().lock().unwrap_or_else(|e| e.into_inner());
                if let Some(actual) = state
                    .window
                    .as_mut()
                    .filter(|actual| actual.handle == handle)
                {
                    actual.text_layout = Some((layout_key, layout.clone()));
                }
                layout
            }
        };

        env.call_method(canvas, jni_str!("save"), jni_sig!("()I"), &[])?;
        env.call_method(
            canvas,
            jni_str!("clipRect"),
            jni_sig!("(FFFF)Z"),
            &[
                JValue::Float(12.0 * d),
                JValue::Float(92.0 * d),
                JValue::Float(width - 12.0 * d),
                JValue::Float(height - 133.0 * d),
            ],
        )?;
        let layout_height = env
            .call_method(layout.as_ref(), jni_str!("getHeight"), jni_sig!("()I"), &[])?
            .i()? as f32;
        let tail = (height - 133.0 * d - layout_height).min(92.0 * d);
        let top = (tail + window.scroll_offset).min(92.0 * d);
        env.call_method(
            canvas,
            jni_str!("translate"),
            jni_sig!("(FF)V"),
            &[JValue::Float(12.0 * d), JValue::Float(top)],
        )?;
        env.call_method(
            layout.as_ref(),
            jni_str!("draw"),
            jni_sig!("(Landroid/graphics/Canvas;)V"),
            &[JValue::Object(canvas)],
        )?;
        env.call_method(canvas, jni_str!("restore"), jni_sig!("()V"), &[])?;
        label(env, "打开对话", 12.0, height / d - 105.0, 12.0)?;
        hits.push(Hit {
            rect: [0.0, height - 130.0 * d, 102.0 * d, height - 95.0 * d],
            control: Control::Open(snapshot.selected.clone(), snapshot.conversation.clone()),
        });
        if let Some(key) = &snapshot.selected {
            label(env, "停止", 128.0, height / d - 105.0, 12.0)?;
            hits.push(Hit {
                rect: [110.0 * d, height - 130.0 * d, 176.0 * d, height - 95.0 * d],
                control: Control::Stop(key.clone()),
            });
        }
        let notice = notice.chars().take(42).collect::<String>();
        label(env, &notice, 12.0, height / d - 82.0, 10.0)?;
        label(env, "发送", width / d - 57.0, height / d - 34.0, 13.0)?;
        hits.push(Hit {
            rect: [
                width - 74.0 * d,
                height - 72.0 * d,
                width,
                height - 15.0 * d,
            ],
            control: Control::Send(
                snapshot.selected.clone(),
                snapshot.conversation.clone(),
                snapshot.immediately,
            ),
        });
        label(env, "◢", width / d - 15.0, height / d - 2.0, 12.0)?;
        hits.push(Hit {
            rect: [width - 22.0 * d, height - 18.0 * d, width, height],
            control: Control::Resize,
        });
    }
    if window.expanded {
        env.call_method(canvas, jni_str!("restore"), jni_sig!("()V"), &[])?;
        for hit in &mut hits {
            hit.rect[1] += panel_offset;
            hit.rect[3] += panel_offset;
        }
        hits.push(Hit {
            rect: [width - 48.0 * d, 0.0, width, 48.0 * d],
            control: Control::Collapse,
        });
    }
    let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
    let binding = buttons::binding(&window, &snapshot, s.pending.is_some());
    if let Some(actual) = s.window.as_mut().filter(|actual| actual.handle == handle) {
        actual.drawn = true;
        actual.hits = hits;
        if buttons::needs_sync(actual, &binding) {
            s.button_request = Some(binding);
            drop(s);
            buttons::post();
        }
    }
    Ok(())
}
fn contains(rect: [f32; 4], x: f32, y: f32) -> bool {
    x >= rect[0] && x <= rect[2] && y >= rect[1] && y <= rect[3]
}
fn accessibility_description(snapshot: &Snapshot, expanded: bool) -> String {
    if !expanded {
        return if snapshot.runs.is_empty() {
            "JAgent悬浮球，双击展开控制台"
        } else {
            "JAgent任务正在运行，双击展开控制台"
        }
        .into();
    }
    let mut text = format!(
        "JAgent悬浮控制台，{}，运行任务{}个。",
        snapshot.title.chars().take(128).collect::<String>(),
        snapshot.runs.len()
    );
    if let Some(run) = snapshot
        .selected
        .as_ref()
        .and_then(|key| snapshot.runs.iter().find(|run| &run.key == key))
    {
        text.push_str(&run.state);
        text.push('。');
    }
    text.extend(snapshot.preview.chars().take(8192));
    text
}
pub(crate) fn accessibility(
    env: &mut Env,
    handle: u64,
    node: &JObject,
    action: i32,
) -> jni::errors::Result<bool> {
    let (window, snapshot, pending) = {
        let s = state().lock().unwrap_or_else(|e| e.into_inner());
        (s.window.clone(), s.snapshot.clone(), s.pending.is_some())
    };
    let (Some(window), Some(snapshot)) = (window, snapshot) else {
        return Ok(false);
    };
    if window.handle != handle {
        return Ok(false);
    }
    let binding = AccessibilityBinding {
        handle,
        expanded: window.expanded,
        selected: snapshot.selected.clone(),
        conversation: snapshot.conversation.clone(),
        pending,
        immediately: snapshot.immediately,
        runs: snapshot.runs.iter().map(|run| run.key.clone()).collect(),
    };
    if !node.is_null() {
        let actions = {
            let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
            if s.accessible.as_ref().is_none_or(|(old, _)| old != &binding) {
                let mut controls = if window.expanded {
                    vec![
                        ("收起控制台", Control::Collapse),
                        ("新建任务", Control::New),
                        (
                            "打开当前会话",
                            Control::Open(snapshot.selected.clone(), snapshot.conversation.clone()),
                        ),
                        ("向上滚动消息", Control::Scroll(false)),
                        ("向下滚动消息", Control::Scroll(true)),
                    ]
                } else {
                    vec![("展开控制台", Control::Expand)]
                };
                if window.expanded && snapshot.runs.len() > 1 {
                    controls.push(("切换运行任务", Control::Next));
                }
                if window.expanded {
                    if let Some(key) = &snapshot.selected {
                        controls.push(("停止当前任务", Control::Stop(key.clone())));
                    }
                    if !pending {
                        controls.push((
                            "发送当前输入",
                            Control::Send(
                                snapshot.selected.clone(),
                                snapshot.conversation.clone(),
                                snapshot.immediately,
                            ),
                        ));
                    }
                }
                let mut actions = Vec::new();
                for (label, control) in controls {
                    let id = NEXT_ACCESSIBILITY
                        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |id| {
                            (id < 0x7fff_ffff).then_some(id + 1)
                        })
                        .map_err(|_| {
                            jni::errors::Error::NullPtr("float accessibility IDs exhausted")
                        })?;
                    actions.push(AccessibleControl {
                        id: id as i32,
                        label,
                        control,
                    });
                }
                s.accessible = Some((binding.clone(), actions));
            }
            s.accessible
                .as_ref()
                .map(|(_, actions)| actions.clone())
                .unwrap_or_default()
        };
        let description = env.new_string(accessibility_description(&snapshot, window.expanded))?;
        env.call_method(
            node,
            jni_str!("setContentDescription"),
            jni_sig!("(Ljava/lang/CharSequence;)V"),
            &[JValue::Object(&description)],
        )?;
        let class = env.new_string(if window.expanded {
            "android.widget.FrameLayout"
        } else {
            "android.widget.Button"
        })?;
        env.call_method(
            node,
            jni_str!("setClassName"),
            jni_sig!("(Ljava/lang/CharSequence;)V"),
            &[JValue::Object(&class)],
        )?;
        for method in [
            jni_str!("setClickable"),
            jni_str!("setFocusable"),
            jni_str!("setEnabled"),
        ] {
            env.call_method(node, method, jni_sig!("(Z)V"), &[JValue::Bool(true)])?;
        }
        for control in actions {
            let label = env.new_string(control.label)?;
            let action = env.new_object(
                jni_str!("android/view/accessibility/AccessibilityNodeInfo$AccessibilityAction"),
                jni_sig!("(ILjava/lang/CharSequence;)V"),
                &[JValue::Int(control.id), JValue::Object(&label)],
            )?;
            env.call_method(
                node,
                jni_str!("addAction"),
                jni_sig!(
                    "(Landroid/view/accessibility/AccessibilityNodeInfo$AccessibilityAction;)V"
                ),
                &[JValue::Object(&action)],
            )?;
        }
        return Ok(true);
    }
    let control = if action == 16 {
        Some(if window.expanded {
            Control::Collapse
        } else {
            Control::Expand
        })
    } else {
        let s = state().lock().unwrap_or_else(|e| e.into_inner());
        s.accessible
            .as_ref()
            .filter(|(actual, _)| actual == &binding)
            .and_then(|(_, controls)| controls.iter().find(|control| control.id == action))
            .map(|control| control.control.clone())
    };
    let Some(control) = control else {
        return Ok(false);
    };
    match activate(env, handle, control) {
        Ok(()) => Ok(true),
        Err(error) => {
            report(error);
            let _ = post();
            Ok(false)
        }
    }
}
pub(crate) fn touch(env: &mut Env, handle: u64, motion: &JObject) -> jni::errors::Result<bool> {
    let action = env
        .call_method(motion, jni_str!("getActionMasked"), jni_sig!("()I"), &[])?
        .i()?;
    let count = env
        .call_method(motion, jni_str!("getPointerCount"), jni_sig!("()I"), &[])?
        .i()?;
    let x = env
        .call_method(motion, jni_str!("getX"), jni_sig!("()F"), &[])?
        .f()?;
    let y = env
        .call_method(motion, jni_str!("getY"), jni_sig!("()F"), &[])?
        .f()?;
    let raw = [
        env.call_method(motion, jni_str!("getRawX"), jni_sig!("()F"), &[])?
            .f()?,
        env.call_method(motion, jni_str!("getRawY"), jni_sig!("()F"), &[])?
            .f()?,
    ];
    if !x.is_finite() || !y.is_finite() || raw.iter().any(|v| !v.is_finite()) {
        return Ok(false);
    }
    let (window, snapshot) = {
        let s = state().lock().unwrap_or_else(|e| e.into_inner());
        (s.window.clone(), s.snapshot.clone())
    };
    let (Some(mut window), Some(snapshot)) = (window, snapshot) else {
        return Ok(false);
    };
    if window.handle != handle {
        return Ok(false);
    }
    let mut control = None;
    let mut save = None;
    if count != 1 || action == 3 {
        window.pointer = None;
        window
            .ball
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .release();
    } else {
        match action {
            0 => {
                if !window.expanded
                    || x >= window.width as f32 - 48.0 * window.density
                        && y <= 48.0 * window.density
                {
                    window
                        .ball
                        .lock()
                        .unwrap_or_else(|e| e.into_inner())
                        .press();
                }
                let chosen = window
                    .hits
                    .iter()
                    .rev()
                    .find(|hit| contains(hit.rect, x, y))
                    .map(|hit| hit.control.clone());
                let resize = matches!(chosen, Some(Control::Resize));
                let scroll = chosen.is_none()
                    && window.expanded
                    && y > (92.0 + BALL_PANEL_GAP_DP) * window.density
                    && y < (window.height as f32 - 133.0 * window.density);
                window.pointer = Some(Pointer {
                    raw,
                    x: window.x,
                    y: window.y,
                    width: window.width,
                    height: window.height,
                    control: chosen,
                    moved: false,
                    resize,
                    scroll,
                    scroll_offset: window.scroll_offset,
                    stamp: snapshot.stamp.clone(),
                });
            }
            2 => {
                if let Some(pointer) = window.pointer.as_mut() {
                    let dx = raw[0] - pointer.raw[0];
                    let dy = raw[1] - pointer.raw[1];
                    if dx.abs() + dy.abs() > 8.0 * window.density {
                        pointer.moved = true;
                        window
                            .ball
                            .lock()
                            .unwrap_or_else(|e| e.into_inner())
                            .release();
                    }
                    if pointer.moved {
                        if pointer.resize {
                            window.content_fixed = true;
                            window.width = pointer.width + dx as i32;
                            window.height = pointer.height + dy as i32;
                        } else if pointer.scroll {
                            window.scroll_offset = (pointer.scroll_offset + dy).max(0.0);
                        } else {
                            window.x = pointer.x + dx as i32;
                            window.y = pointer.y + dy as i32;
                        }
                    }
                }
            }
            1 => {
                window
                    .ball
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .release();
                if let Some(pointer) = window.pointer.take() {
                    if pointer.moved && pointer.resize {
                        save = Some(Resize {
                            request_id: crate::controller::unique_id(),
                            expected: pointer.stamp,
                            width_dp: (window.width as f32 / window.density) as i32,
                            height_dp: content_height_dp(&window),
                        });
                    } else if !pointer.moved {
                        control = pointer.control;
                    }
                }
            }
            _ => (),
        }
    }
    if let Err(error) = geometry(&mut window) {
        report(error);
        save = None;
        let original = {
            state()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .window
                .clone()
        };
        if let Some(original) = original {
            if original.handle == handle {
                window = original;
                let _ = geometry(&mut window);
            }
        }
    }
    if let Some(resize) = save.as_mut() {
        resize.width_dp = (window.width as f32 / window.density) as i32;
        resize.height_dp = content_height_dp(&window);
    }
    {
        let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
        if s.window
            .as_ref()
            .is_some_and(|actual| actual.handle == handle)
        {
            s.window = Some(window);
        }
        if let Some(resize) = save {
            if s.resize_pending.is_none() && s.resizes.len() < 4 {
                s.resize_pending = Some(resize.clone());
                s.resizes.push_back(resize);
            }
        }
    }
    if let Some(control) = control {
        if let Err(error) = activate(env, handle, control) {
            report(error);
        }
    }
    let _ = post();
    Ok(true)
}
fn activate(env: &mut Env, handle: u64, control: Control) -> Result<(), String> {
    let (host, window, snapshot, pending) = {
        let s = state().lock().unwrap_or_else(|e| e.into_inner());
        (
            s.host.clone(),
            s.window.clone(),
            s.snapshot.clone(),
            s.pending.is_some(),
        )
    };
    let (Some(host), Some(window), Some(snapshot)) = (host, window, snapshot) else {
        return Err("悬浮运行宿主不可用".into());
    };
    if window.handle != handle {
        return Err("悬浮窗口身份已变，旧点击未执行".into());
    }
    match control {
        Control::Expand | Control::Collapse => {
            if matches!(control, Control::Collapse) {
                hide_keyboard(env, &window).map_err(|_| "收起悬浮输入键盘失败")?;
            }
            let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
            if let Some(window) = s.window.as_mut().filter(|window| window.handle == handle) {
                window.expanded = matches!(control, Control::Expand);
                window.pointer = None;
            }
        }
        Control::New => {
            let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
            s.manual = Some(None);
            s.conversation = None;
        }
        Control::Next => {
            let selected = snapshot
                .selected
                .as_ref()
                .and_then(|key| snapshot.runs.iter().position(|run| &run.key == key));
            let next = if snapshot.runs.is_empty() {
                None
            } else {
                Some(
                    snapshot.runs[selected.map_or(0, |index| (index + 1) % snapshot.runs.len())]
                        .key
                        .clone(),
                )
            };
            let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
            s.conversation = next.as_ref().map(|key| key.conversation_id.clone());
            s.manual = Some(next);
        }
        Control::Stop(key) => {
            host.cancel_run(&key);
            host.submit(Action::CancelRun { key })?;
        }
        Control::Open(key, conversation) => {
            android::invoke(|env| {
                let context = android::application_context()
                    .map_err(|_| jni::errors::Error::NullPtr("console context"))?;
                let extras = key
                    .as_ref()
                    .map(|key| {
                        vec![
                            framework::Extra::Text("run_id", &key.run_id),
                            framework::Extra::Text("conversation_id", &key.conversation_id),
                        ]
                    })
                    .unwrap_or_else(|| {
                        conversation
                            .as_deref()
                            .map(|id| vec![framework::Extra::Text("conversation_id", id)])
                            .unwrap_or_default()
                    });
                let intent = framework::intent(
                    env,
                    context.as_ref(),
                    "com.suixing.agent.rust.platform.RustNativeActivity",
                    crate::platform::background::ACTION_OPEN,
                    None,
                    &extras,
                )?;
                env.call_method(
                    &intent,
                    jni_str!("addFlags"),
                    jni_sig!("(I)Landroid/content/Intent;"),
                    &[JValue::Int(0x10000000 | 0x04000000)],
                )?;
                env.call_method(
                    context.as_ref(),
                    jni_str!("startActivity"),
                    jni_sig!("(Landroid/content/Intent;)V"),
                    &[JValue::Object(&intent)],
                )?;
                Ok(())
            })?;
        }
        Control::Send(expected, conversation, immediately) => {
            if pending {
                return Err("前次输入尚待真实回执，草稿保留".into());
            }
            if window.input_key != input_key(conversation.as_deref()) {
                return Err("切换任务后的旧发送点击已失效，输入保持在所属会话".into());
            }
            let object = env
                .call_method(
                    window.input.as_ref(),
                    jni_str!("getText"),
                    jni_sig!("()Landroid/text/Editable;"),
                    &[],
                )
                .map_err(|_| "悬浮输入不可读")?
                .l()
                .map_err(|_| "悬浮输入不可读")?;
            let object = env
                .call_method(
                    &object,
                    jni_str!("toString"),
                    jni_sig!("()Ljava/lang/String;"),
                    &[],
                )
                .map_err(|_| "悬浮输入不可读")?
                .l()
                .map_err(|_| "悬浮输入不可读")?;
            let text = android::string_value(env, object, crate::composer::MAX_TEXT_BYTES)
                .map_err(|_| "悬浮输入超过正文预算")?;
            if text.trim().is_empty() {
                return Err("请先输入内容".into());
            }
            let operation = crate::platform::permissions::OperationKey {
                id: crate::controller::unique_id(),
                conversation_id: conversation,
                generation: handle,
            };
            let mode = if expected.is_some() {
                if immediately {
                    SubmitMode::Steer
                } else {
                    SubmitMode::Queue
                }
            } else {
                SubmitMode::Send
            };
            let submission = Submission {
                operation: operation.clone(),
                draft_revision: handle,
                expected_run: if mode == SubmitMode::Steer {
                    expected
                } else {
                    None
                },
                text,
                attachments: Vec::new(),
                mode,
            };
            submission.validate()?;
            {
                let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
                s.pending = Some(operation.clone());
                s.notice = "等待运行线程受理，草稿保留".into();
            }
            if let Err(error) = host.submit(Action::Composer(ComposerCommand::Submit(submission))) {
                state().lock().unwrap_or_else(|e| e.into_inner()).pending = None;
                return Err(error);
            }
        }
        Control::Resize => (),
        Control::Scroll(forward) => {
            let layout_height = if let Some((_, layout)) = &window.text_layout {
                env.call_method(layout.as_ref(), jni_str!("getHeight"), jni_sig!("()I"), &[])
                    .map_err(|_| "悬浮预览高度不可读")?
                    .i()
                    .map_err(|_| "悬浮预览高度无效")? as f32
            } else {
                0.0
            };
            let mut s = state().lock().unwrap_or_else(|e| e.into_inner());
            if let Some(window) = s.window.as_mut().filter(|window| window.handle == handle) {
                let delta = (window.height as f32 - CONTENT_CHROME_DP * window.density)
                    .max(64.0 * window.density);
                let max_offset = (layout_height
                    - (window.height as f32 - CONTENT_CHROME_DP * window.density).max(0.0))
                .max(0.0);
                window.scroll_offset = if forward {
                    window.scroll_offset - delta
                } else {
                    window.scroll_offset + delta
                }
                .clamp(0.0, max_offset);
            }
        }
    }
    post()
}
