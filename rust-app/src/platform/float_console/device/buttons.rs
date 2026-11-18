//! Real native Button children expose distinct focus rectangles. Canvas keeps
//! the same labels; only TextView's paint is transparent, never the View alpha.
//! Listeners retain an immutable accessibility action ID bound to its run.
use super::*;
#[derive(Clone)]
struct Button {
    view: Object,
    rect: [i32; 4],
    control: Control,
}
#[derive(Clone)]
pub(super) struct Widgets {
    binding: Option<AccessibilityBinding>,
    buttons: Vec<Button>,
}
fn rect(hit: &Hit) -> [i32; 4] {
    hit.rect.map(|value| value.round() as i32)
}
fn positions(env: &mut Env, button: &Object, rect: [i32; 4]) -> jni::errors::Result<()> {
    let layout = env.new_object(
        jni_str!("android/widget/FrameLayout$LayoutParams"),
        jni_sig!("(II)V"),
        &[
            JValue::Int((rect[2] - rect[0]).max(1)),
            JValue::Int((rect[3] - rect[1]).max(1)),
        ],
    )?;
    env.set_field(
        &layout,
        jni_str!("leftMargin"),
        jni_sig!("I"),
        JValue::Int(rect[0]),
    )?;
    env.set_field(
        &layout,
        jni_str!("topMargin"),
        jni_sig!("I"),
        JValue::Int(rect[1]),
    )?;
    env.call_method(
        button.as_ref(),
        jni_str!("setLayoutParams"),
        jni_sig!("(Landroid/view/ViewGroup$LayoutParams;)V"),
        &[JValue::Object(&layout)],
    )?;
    Ok(())
}
pub(super) fn binding(window: &Window, snapshot: &Snapshot, pending: bool) -> AccessibilityBinding {
    AccessibilityBinding {
        handle: window.handle,
        expanded: window.expanded,
        selected: snapshot.selected.clone(),
        conversation: snapshot.conversation.clone(),
        pending,
        immediately: snapshot.immediately,
        runs: snapshot.runs.iter().map(|run| run.key.clone()).collect(),
    }
}
pub(super) fn needs_sync(window: &Window, binding: &AccessibilityBinding) -> bool {
    window.widgets.as_ref().is_none_or(|widgets| {
        widgets.binding.as_ref() != Some(binding)
            || widgets.buttons.iter().any(|button| {
                window
                    .hits
                    .iter()
                    .find(|hit| {
                        hit.control == button.control
                            && hit.rect[1] >= BALL_PANEL_GAP_DP * window.density
                    })
                    .is_none_or(|hit| rect(hit) != button.rect)
            })
    })
}
static POSTED: AtomicBool = AtomicBool::new(false);
pub(super) fn post() {
    if POSTED.swap(true, Ordering::AcqRel) {
        return;
    }
    // ViewGroup children cannot be added or removed during onDraw. The posted
    // callback sees only the most recent geometry and checks its exact binding.
    if let Err(error) = android::post_to_main(|| {
        POSTED.store(false, Ordering::Release);
        let (mut window, request) = {
            let mut s = state().lock().unwrap_or_else(|error| error.into_inner());
            let Some(request) = s.button_request.take() else {
                return;
            };
            let (Some(window), Some(snapshot)) = (&s.window, &s.snapshot) else {
                return;
            };
            if request != binding(window, snapshot, s.pending.is_some()) {
                return;
            }
            (window.clone(), request)
        };
        let result = android::invoke(|env| sync(env, &mut window, request));
        // Even an incomplete apply retains every view it attempted to attach.
        let mut s = state().lock().unwrap_or_else(|error| error.into_inner());
        if let Some(actual) = s
            .window
            .as_mut()
            .filter(|actual| actual.handle == window.handle)
        {
            actual.widgets = window.widgets;
        }
        drop(s);
        if let Err(error) = result {
            report(format!("悬浮控制台按钮未同步：{error}"));
        }
    }) {
        POSTED.store(false, Ordering::Release);
        report(format!("悬浮控制台按钮未投递：{error}"));
    }
}
fn sync(
    env: &mut Env,
    window: &mut Window,
    binding: AccessibilityBinding,
) -> jni::errors::Result<()> {
    let changed = window.widgets.as_ref().is_none_or(|widgets| {
        widgets.binding.as_ref() != Some(&binding)
            || widgets.buttons.iter().any(|button| {
                !window.hits.iter().any(|hit| {
                    hit.control == button.control
                        && hit.rect[1] >= BALL_PANEL_GAP_DP * window.density
                })
            })
    });
    if changed {
        let node = env
            .call_static_method(
                jni_str!("android/view/accessibility/AccessibilityNodeInfo"),
                jni_str!("obtain"),
                jni_sig!("()Landroid/view/accessibility/AccessibilityNodeInfo;"),
                &[],
            )?
            .l()?;
        accessibility(env, window.handle, &node, -1)?;
        env.call_method(&node, jni_str!("recycle"), jni_sig!("()V"), &[])?;
        let controls = state()
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .accessible
            .as_ref()
            .filter(|(actual, _)| actual == &binding)
            .map(|(_, controls)| controls.clone())
            .unwrap_or_default();
        let listener = android::generated_class("NativeFloatClick")
            .map_err(|_| jni::errors::Error::NullPtr("float click class"))?;
        let context = env
            .call_method(
                window.view.as_ref(),
                jni_str!("getContext"),
                jni_sig!("()Landroid/content/Context;"),
                &[],
            )?
            .l()?;
        let mut buttons = Vec::new();
        if window.expanded {
            for hit in &window.hits {
                // The ball remains the drag surface. Resize retains its real
                // pointer gesture; scroll commands remain in the action menu.
                if hit.rect[1] < BALL_PANEL_GAP_DP * window.density
                    || matches!(hit.control, Control::Resize | Control::Scroll(_))
                {
                    continue;
                }
                let Some(control) = controls
                    .iter()
                    .find(|control| control.control == hit.control)
                else {
                    continue;
                };
                let button = env.new_object(
                    jni_str!("android/widget/Button"),
                    jni_sig!("(Landroid/content/Context;)V"),
                    &[JValue::Object(&context)],
                )?;
                let text = env.new_string(control.label)?;
                for method in [jni_str!("setText"), jni_str!("setContentDescription")] {
                    env.call_method(
                        &button,
                        method,
                        jni_sig!("(Ljava/lang/CharSequence;)V"),
                        &[JValue::Object(&text)],
                    )?;
                }
                env.call_method(
                    &button,
                    jni_str!("setTextColor"),
                    jni_sig!("(I)V"),
                    &[JValue::Int(0)],
                )?;
                env.call_method(
                    &button,
                    jni_str!("setBackgroundColor"),
                    jni_sig!("(I)V"),
                    &[JValue::Int(0)],
                )?;
                env.call_method(
                    &button,
                    jni_str!("setElevation"),
                    jni_sig!("(F)V"),
                    &[JValue::Float(0.0)],
                )?;
                env.call_method(
                    &button,
                    jni_str!("setStateListAnimator"),
                    jni_sig!("(Landroid/animation/StateListAnimator;)V"),
                    &[JValue::Object(&JObject::null())],
                )?;
                for method in [
                    jni_str!("setMinHeight"),
                    jni_str!("setMinWidth"),
                    jni_str!("setMinimumHeight"),
                    jni_str!("setMinimumWidth"),
                ] {
                    env.call_method(&button, method, jni_sig!("(I)V"), &[JValue::Int(0)])?;
                }
                env.call_method(
                    &button,
                    jni_str!("setPadding"),
                    jni_sig!("(IIII)V"),
                    &[
                        JValue::Int(0),
                        JValue::Int(0),
                        JValue::Int(0),
                        JValue::Int(0),
                    ],
                )?;
                env.call_method(
                    &button,
                    jni_str!("setImportantForAccessibility"),
                    jni_sig!("(I)V"),
                    &[JValue::Int(1)],
                )?;
                let on_click = env.new_object(
                    listener.as_ref(),
                    jni_sig!("(JI)V"),
                    &[JValue::Long(window.handle as i64), JValue::Int(control.id)],
                )?;
                env.call_method(
                    &button,
                    jni_str!("setOnClickListener"),
                    jni_sig!("(Landroid/view/View$OnClickListener;)V"),
                    &[JValue::Object(&on_click)],
                )?;
                let view = Arc::new(env.new_global_ref(button)?);
                let geometry = rect(hit);
                positions(env, &view, geometry)?;
                buttons.push(Button {
                    view,
                    rect: geometry,
                    control: hit.control.clone(),
                });
            }
        }
        let widgets = window.widgets.get_or_insert_with(|| Widgets {
            binding: None,
            buttons: Vec::new(),
        });
        widgets.binding = None;
        // Build every replacement offscreen before touching the existing tree.
        // A failed JNI mutation keeps all attached or unconfirmed child handles
        // for the next synchronization. No retries add another copy.
        while let Some(button) = widgets.buttons.last() {
            env.call_method(
                window.view.as_ref(),
                jni_str!("removeView"),
                jni_sig!("(Landroid/view/View;)V"),
                &[JValue::Object(button.view.as_ref())],
            )?;
            widgets.buttons.pop();
        }
        for button in buttons {
            let view = button.view.clone();
            widgets.buttons.push(button);
            env.call_method(
                window.view.as_ref(),
                jni_str!("addView"),
                jni_sig!("(Landroid/view/View;)V"),
                &[JValue::Object(view.as_ref())],
            )?;
        }
        widgets.binding = Some(binding);
    } else if let Some(widgets) = &mut window.widgets {
        for button in &mut widgets.buttons {
            let Some(hit) = window.hits.iter().find(|hit| {
                hit.control == button.control && hit.rect[1] >= BALL_PANEL_GAP_DP * window.density
            }) else {
                continue;
            };
            let geometry = rect(hit);
            if button.rect != geometry {
                positions(env, &button.view, geometry)?;
                button.rect = geometry;
            }
        }
    }
    Ok(())
}
