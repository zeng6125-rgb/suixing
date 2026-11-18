//! The ordinary Rust UI exposes egui's real AccessKit tree to Android TalkBack.
//! JNI queries borrow one bounded visible snapshot. Actions return to egui's
//! own widget handlers; they never bypass the application's save/run/approval path.
use eframe::egui;

pub(crate) fn install(context: &egui::Context) {
    #[cfg(target_os = "android")]
    context.add_plugin(device::UiAccessibility::default());
    #[cfg(not(target_os = "android"))]
    let _ = context;
}

#[cfg(target_os = "android")]
pub(crate) use device::{action, focus, hover, node, search};

#[cfg(target_os = "android")]
mod device {
    use super::egui;
    use crate::platform::android;
    use egui::accesskit::{
        self, Action, ActionData, ActionRequest, Affine, Node, NodeId, Role, TreeId,
    };
    use jni::{Env, JValue, jni_sig, jni_str, objects::JObject, refs::Global};
    use std::{
        collections::{HashMap, HashSet, VecDeque},
        sync::{
            Arc, Mutex, OnceLock, Weak,
            atomic::{AtomicBool, AtomicU64, Ordering},
        },
    };

    const MAX_NODES: usize = 4096;
    const MAX_TEXT_BYTES: usize = 2 * 1024 * 1024;
    const MAX_ACTIONS: usize = 64;
    const HOST: i32 = -1;
    const NONE: i32 = i32::MIN;
    static NEXT: AtomicU64 = AtomicU64::new(1);
    static REGISTRY: OnceLock<Mutex<HashMap<u64, Registration>>> = OnceLock::new();

    // The registry's one Context is released by on_exit before renderer teardown.
    // The Plugin/Slot itself never holds a Context, so its owner has no self-cycle.
    struct Registration {
        slot: Weak<Slot>,
        context: egui::Context,
    }
    fn registry() -> &'static Mutex<HashMap<u64, Registration>> {
        REGISTRY.get_or_init(Default::default)
    }
    struct Entry {
        source: NodeId,
        node: Node,
        parent: i32,
        children: Vec<i32>,
        bounds: [f64; 4],
    }
    struct Snapshot {
        nodes: HashMap<i32, Entry>,
        input_focus: i32,
        error: Option<&'static str>,
    }
    impl Default for Snapshot {
        fn default() -> Self {
            Self {
                nodes: HashMap::new(),
                input_focus: NONE,
                error: None,
            }
        }
    }
    struct Host {
        activity: Arc<Global<JObject<'static>>>,
        view: Arc<Global<JObject<'static>>>,
    }
    #[derive(Default)]
    struct Data {
        snapshot: Snapshot,
        ids: HashMap<NodeId, i32>,
        next_id: i32,
        accessibility_focus: i32,
        hovered: i32,
        notified_focus: i32,
        actions: VecDeque<(NodeId, Node, ActionRequest)>,
        host: Option<Host>,
    }
    struct Slot {
        handle: AtomicU64,
        data: Mutex<Data>,
        posted: AtomicBool,
    }
    impl Default for Slot {
        fn default() -> Self {
            let mut data = Data::default();
            data.accessibility_focus = NONE;
            data.hovered = NONE;
            data.notified_focus = NONE;
            Self {
                handle: AtomicU64::new(0),
                data: Mutex::new(data),
                posted: AtomicBool::new(false),
            }
        }
    }
    #[derive(Default)]
    pub(crate) struct UiAccessibility {
        slot: Arc<Slot>,
    }
    impl egui::Plugin for UiAccessibility {
        fn debug_name(&self) -> &'static str {
            "Android UI accessibility"
        }
        fn setup(&mut self, context: &egui::Context) {
            context.enable_accesskit();
            let handle = NEXT.fetch_add(1, Ordering::Relaxed);
            self.slot.handle.store(handle, Ordering::Release);
            registry().lock().unwrap_or_else(|e| e.into_inner()).insert(
                handle,
                Registration {
                    slot: Arc::downgrade(&self.slot),
                    context: context.clone(),
                },
            );
        }
        fn input_hook(&mut self, _context: &egui::Context, input: &mut egui::RawInput) {
            if input.viewport_id != egui::ViewportId::ROOT {
                return;
            }
            let mut data = self.slot.data.lock().unwrap_or_else(|e| e.into_inner());
            if !current(&data) {
                data.actions.clear();
                return;
            }
            let requests: Vec<_> = data.actions.drain(..).collect();
            for (source, expected, request) in requests {
                if data
                    .snapshot
                    .nodes
                    .values()
                    .any(|entry| entry.source == source && entry.node == expected)
                {
                    input
                        .events
                        .push(egui::Event::AccessKitActionRequest(request));
                }
            }
        }
        fn output_hook(&mut self, context: &egui::Context, output: &mut egui::FullOutput) {
            if context.viewport_id() != egui::ViewportId::ROOT {
                return;
            }
            let Some(update) = output.platform_output.accesskit_update.as_ref() else {
                return;
            };
            if update.tree_id != TreeId::ROOT {
                return;
            }
            let mut data = self.slot.data.lock().unwrap_or_else(|e| e.into_inner());
            let old_focus = data.snapshot.input_focus;
            let changed = data.snapshot.nodes.len() != update.nodes.len()
                || update.nodes.iter().any(|(id, node)| {
                    data.ids
                        .get(id)
                        .and_then(|id| data.snapshot.nodes.get(id))
                        .is_none_or(|entry| entry.node != *node)
                });
            let next = {
                let Data { ids, next_id, .. } = &mut *data;
                snapshot(update, ids, next_id)
            };
            data.snapshot = next;
            if !data.snapshot.nodes.contains_key(&data.accessibility_focus) {
                data.accessibility_focus = NONE;
            }
            if !data.snapshot.nodes.contains_key(&data.hovered) {
                data.hovered = NONE;
            }
            let needs_host = data.host.as_ref().is_none_or(|host| {
                android::current_activity()
                    .is_none_or(|activity| !Arc::ptr_eq(&activity, &host.activity))
            });
            let changed = changed || old_focus != data.snapshot.input_focus || needs_host;
            drop(data);
            if changed {
                schedule(&self.slot);
            }
        }
        fn on_exit(&mut self, _context: &egui::Context) {
            registry()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .remove(&self.slot.handle.load(Ordering::Acquire));
            let slot = self.slot.clone();
            let _ = android::post_to_main(move || {
                let host = slot
                    .data
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .host
                    .take();
                if let Some(host) = host {
                    let _ = remove(&host.view);
                }
            });
        }
    }
    fn snapshot(
        update: &accesskit::TreeUpdate,
        ids: &mut HashMap<NodeId, i32>,
        next_id: &mut i32,
    ) -> Snapshot {
        let Some(tree) = &update.tree else {
            return Snapshot::default();
        };
        let bytes: usize = update
            .nodes
            .iter()
            .map(|(_, node)| {
                node.label().map_or(0, str::len)
                    + node.value().map_or(0, str::len)
                    + node.description().map_or(0, str::len)
            })
            .sum();
        if update.nodes.len() > MAX_NODES || bytes > MAX_TEXT_BYTES {
            ids.clear();
            return Snapshot {
                error: Some("当前界面超出无障碍节点预算，请收起部分内容后重试"),
                ..Default::default()
            };
        }
        let raw: HashMap<_, _> = update.nodes.iter().map(|(id, node)| (*id, node)).collect();
        let mut result = Snapshot::default();
        let mut seen = HashSet::new();
        let mut order = Vec::new();
        let mut pending = vec![(tree.root, HOST, Affine::IDENTITY, 0usize)];
        while let Some((source, parent, transform, depth)) = pending.pop() {
            if depth > 96 || !seen.insert(source) {
                continue;
            }
            let Some(node) = raw.get(&source).filter(|node| !node.is_hidden()) else {
                continue;
            };
            let id = if source == tree.root {
                HOST
            } else {
                match ids.get(&source) {
                    Some(id) => *id,
                    None => {
                        let Some(next) = next_id.checked_add(1).filter(|id| *id > 0) else {
                            continue;
                        };
                        *next_id = next;
                        ids.insert(source, next);
                        next
                    }
                }
            };
            let transform = transform * node.transform().copied().unwrap_or(Affine::IDENTITY);
            let bounds = node
                .bounds()
                .map(|rect| transform.transform_rect_bbox(rect));
            let bounds = bounds
                .filter(|rect| {
                    [rect.x0, rect.y0, rect.x1, rect.y1]
                        .iter()
                        .all(|n| n.is_finite())
                })
                .map_or([0.0; 4], |rect| [rect.x0, rect.y0, rect.x1, rect.y1]);
            result.nodes.insert(
                id,
                Entry {
                    source,
                    node: (*node).clone(),
                    parent,
                    children: Vec::new(),
                    bounds,
                },
            );
            order.push(id);
            if let Some(entry) = result.nodes.get_mut(&parent).filter(|_| id != HOST) {
                entry.children.push(id);
            }
            for child in node.children().iter().rev() {
                pending.push((*child, id, transform, depth + 1));
            }
            if source == update.focus {
                result.input_focus = id;
            }
        }
        ids.retain(|source, _| seen.contains(source));
        // Text/group nodes often omit their own bounds; their child extent is the real hit area.
        for id in order.into_iter().rev() {
            if id == HOST {
                continue;
            }
            let Some(entry) = result.nodes.get(&id) else {
                continue;
            };
            let (parent, bounds) = (entry.parent, entry.bounds);
            if let Some(parent) = result.nodes.get_mut(&parent)
                && parent.node.bounds().is_none()
                && bounds[2] > bounds[0]
            {
                parent.bounds = if parent.bounds[2] <= parent.bounds[0] {
                    bounds
                } else {
                    [
                        parent.bounds[0].min(bounds[0]),
                        parent.bounds[1].min(bounds[1]),
                        parent.bounds[2].max(bounds[2]),
                        parent.bounds[3].max(bounds[3]),
                    ]
                };
            }
        }
        result
    }
    fn slot(handle: u64) -> Option<Arc<Slot>> {
        registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&handle)?
            .slot
            .upgrade()
    }
    fn wake(handle: u64) {
        let context = registry()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&handle)
            .map(|entry| entry.context.clone());
        if let Some(context) = context {
            context.request_repaint();
        }
    }
    fn current(data: &Data) -> bool {
        android::activity_visible()
            && data.host.as_ref().is_some_and(|host| {
                android::current_activity()
                    .is_some_and(|activity| Arc::ptr_eq(&activity, &host.activity))
            })
    }
    fn remove(view: &Global<JObject<'static>>) -> Result<(), String> {
        android::invoke(|env| {
            let parent = env
                .call_method(
                    view,
                    jni_str!("getParent"),
                    jni_sig!("()Landroid/view/ViewParent;"),
                    &[],
                )?
                .l()?;
            if !parent.is_null() {
                env.call_method(
                    &parent,
                    jni_str!("removeView"),
                    jni_sig!("(Landroid/view/View;)V"),
                    &[JValue::Object(view)],
                )?;
            }
            Ok(())
        })
    }
    fn schedule(slot: &Arc<Slot>) {
        if slot.posted.swap(true, Ordering::AcqRel) {
            return;
        }
        let copied = slot.clone();
        if android::post_to_main(move || {
            let _ = update_host(&copied);
            copied.posted.store(false, Ordering::Release);
        })
        .is_err()
        {
            slot.posted.store(false, Ordering::Release);
        }
    }
    fn update_host(slot: &Arc<Slot>) -> Result<(), String> {
        let Some(activity) = android::current_activity().filter(|_| android::activity_visible())
        else {
            let old = slot
                .data
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .host
                .take();
            if let Some(old) = old {
                remove(&old.view)?;
            }
            return Ok(());
        };
        let (attach, old) = {
            let mut data = slot.data.lock().unwrap_or_else(|e| e.into_inner());
            let attach = data
                .host
                .as_ref()
                .is_none_or(|host| !Arc::ptr_eq(&activity, &host.activity));
            let old = if attach { data.host.take() } else { None };
            (attach, old)
        };
        if let Some(old) = old {
            remove(&old.view)?;
        }
        if attach {
            let class = android::generated_class("NativeUiAccessibility")?;
            let handle = slot.handle.load(Ordering::Acquire);
            let view = android::invoke(|env| {
                let root = env
                    .call_method(
                        activity.as_ref(),
                        jni_str!("findViewById"),
                        jni_sig!("(I)Landroid/view/View;"),
                        &[JValue::Int(0x01020002)],
                    )?
                    .l()?;
                if root.is_null() {
                    return Err(jni::errors::Error::NullPtr("native content view"));
                }
                let view = env.new_object(
                    class.as_ref(),
                    jni_sig!("(Landroid/content/Context;J)V"),
                    &[JValue::Object(&activity), JValue::Long(handle as i64)],
                )?;
                env.call_method(
                    &view,
                    jni_str!("setImportantForAccessibility"),
                    jni_sig!("(I)V"),
                    &[JValue::Int(1)],
                )?;
                env.call_method(
                    &view,
                    jni_str!("setFocusable"),
                    jni_sig!("(Z)V"),
                    &[JValue::Bool(false)],
                )?;
                env.call_method(
                    &view,
                    jni_str!("setClickable"),
                    jni_sig!("(Z)V"),
                    &[JValue::Bool(false)],
                )?;
                env.call_method(
                    &view,
                    jni_str!("setWillNotDraw"),
                    jni_sig!("(Z)V"),
                    &[JValue::Bool(true)],
                )?;
                let layout = env.new_object(
                    jni_str!("android/widget/FrameLayout$LayoutParams"),
                    jni_sig!("(II)V"),
                    &[JValue::Int(-1), JValue::Int(-1)],
                )?;
                env.call_method(
                    &root,
                    jni_str!("addView"),
                    jni_sig!("(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V"),
                    &[JValue::Object(&view), JValue::Object(&layout)],
                )?;
                env.new_global_ref(&view)
            })?;
            // add/removeView can synchronously ask the provider for nodes. Never
            // hold the provider's snapshot mutex across these framework calls.
            let mut data = slot.data.lock().unwrap_or_else(|e| e.into_inner());
            data.host = Some(Host {
                activity,
                view: Arc::new(view),
            });
            data.accessibility_focus = NONE;
            data.hovered = NONE;
            data.notified_focus = NONE;
            data.actions.clear();
        }
        let mut data = slot.data.lock().unwrap_or_else(|e| e.into_inner());
        let view = data.host.as_ref().map(|host| host.view.clone());
        let focus = data.snapshot.input_focus;
        let focus_changed = focus != data.notified_focus;
        data.notified_focus = focus;
        drop(data);
        if let Some(view) = view {
            android::invoke(|env| {
                emit(env, &view, HOST, 2048)?;
                if focus_changed && focus != NONE && focus != HOST {
                    emit(env, &view, focus, 8)?;
                }
                Ok(())
            })?;
        }
        Ok(())
    }
    fn emit(
        env: &mut Env<'_>,
        view: &Global<JObject<'static>>,
        id: i32,
        kind: i32,
    ) -> jni::errors::Result<()> {
        let event = env
            .call_static_method(
                jni_str!("android/view/accessibility/AccessibilityEvent"),
                jni_str!("obtain"),
                jni_sig!("(I)Landroid/view/accessibility/AccessibilityEvent;"),
                &[JValue::Int(kind)],
            )?
            .l()?;
        env.call_method(
            &event,
            jni_str!("setSource"),
            jni_sig!("(Landroid/view/View;I)V"),
            &[JValue::Object(view), JValue::Int(id)],
        )?;
        let package =
            android::paths().map_or_else(|| "com.suixing.agent".into(), |paths| paths.package_name);
        let package = env.new_string(package)?;
        env.call_method(
            &event,
            jni_str!("setPackageName"),
            jni_sig!("(Ljava/lang/CharSequence;)V"),
            &[JValue::Object(&package)],
        )?;
        if kind == 2048 {
            env.call_method(
                &event,
                jni_str!("setContentChangeTypes"),
                jni_sig!("(I)V"),
                &[JValue::Int(1)],
            )?;
        }
        let parent = env
            .call_method(
                view,
                jni_str!("getParent"),
                jni_sig!("()Landroid/view/ViewParent;"),
                &[],
            )?
            .l()?;
        if !parent.is_null() {
            env.call_method(
                &parent,
                jni_str!("requestSendAccessibilityEvent"),
                jni_sig!("(Landroid/view/View;Landroid/view/accessibility/AccessibilityEvent;)Z"),
                &[JValue::Object(view), JValue::Object(&event)],
            )?;
        }
        Ok(())
    }
    fn text(
        env: &mut Env<'_>,
        info: &JObject<'_>,
        method: &'static jni::strings::JNIStr,
        value: &str,
    ) -> jni::errors::Result<()> {
        let text = env.new_string(value)?;
        env.call_method(
            info,
            method,
            jni_sig!("(Ljava/lang/CharSequence;)V"),
            &[JValue::Object(&text)],
        )?;
        Ok(())
    }
    fn flag(
        env: &mut Env<'_>,
        info: &JObject<'_>,
        method: &'static jni::strings::JNIStr,
        value: bool,
    ) -> jni::errors::Result<()> {
        env.call_method(info, method, jni_sig!("(Z)V"), &[JValue::Bool(value)])?;
        Ok(())
    }
    fn class_name(role: Role) -> &'static str {
        match role {
            Role::Button | Role::DefaultButton => "android.widget.Button",
            Role::CheckBox => "android.widget.CheckBox",
            Role::RadioButton => "android.widget.RadioButton",
            Role::Switch => "android.widget.Switch",
            Role::TextInput
            | Role::MultilineTextInput
            | Role::PasswordInput
            | Role::EditableComboBox => "android.widget.EditText",
            Role::Slider | Role::SpinButton => "android.widget.SeekBar",
            Role::ScrollView => "android.widget.ScrollView",
            Role::ComboBox => "android.widget.Spinner",
            Role::Label | Role::Paragraph | Role::TextRun => "android.widget.TextView",
            _ => "android.view.View",
        }
    }
    fn editable(node: &Node) -> bool {
        matches!(
            node.role(),
            Role::TextInput
                | Role::MultilineTextInput
                | Role::PasswordInput
                | Role::EditableComboBox
        ) && !node.is_read_only()
            && !node.is_disabled()
    }
    fn supported(node: &Node, action: Action) -> bool {
        node.supports_action(action) || node.child_supports_action(action)
    }
    fn protected(snapshot: &Snapshot, mut id: i32) -> bool {
        for _ in 0..=96 {
            let Some(entry) = snapshot.nodes.get(&id) else {
                return false;
            };
            if entry.node.role() == Role::PasswordInput {
                return true;
            }
            if id == HOST {
                return false;
            }
            id = entry.parent;
        }
        true
    }
    /// Android selections use UTF-16 offsets. AccessKit uses each text run's
    /// declared selectable character boundaries, including its hard line break.
    /// Accept only exact boundaries; never split a surrogate or grapheme.
    fn text_positions(
        snapshot: &Snapshot,
        id: i32,
    ) -> Option<Vec<(usize, accesskit::TextPosition)>> {
        let mut pending = vec![id];
        let mut positions = Vec::new();
        let mut units = 0usize;
        let mut seen = HashSet::new();
        while let Some(id) = pending.pop() {
            if !seen.insert(id) || seen.len() > MAX_NODES {
                return None;
            }
            let entry = snapshot.nodes.get(&id)?;
            if entry.node.role() == Role::TextRun {
                let value = entry.node.value()?;
                let mut byte = 0usize;
                for (index, length) in entry.node.character_lengths().iter().enumerate() {
                    positions.push((
                        units,
                        accesskit::TextPosition {
                            node: entry.source,
                            character_index: index,
                        },
                    ));
                    let end = byte.checked_add(*length as usize)?;
                    units = units.checked_add(value.get(byte..end)?.encode_utf16().count())?;
                    if units > 65536 {
                        return None;
                    }
                    byte = end;
                }
                if byte != value.len() {
                    return None;
                }
                positions.push((
                    units,
                    accesskit::TextPosition {
                        node: entry.source,
                        character_index: entry.node.character_lengths().len(),
                    },
                ));
            } else {
                pending.extend(entry.children.iter().rev());
            }
        }
        (!positions.is_empty()).then_some(positions)
    }
    fn inspect_node<'local>(
        env: &mut Env<'local>,
        data: &Data,
        id: i32,
    ) -> jni::errors::Result<JObject<'local>> {
        let Some(host) = &data.host else {
            return Ok(JObject::null());
        };
        let entry = data.snapshot.nodes.get(&id);
        if entry.is_none() && id != HOST {
            return Ok(JObject::null());
        }
        let info = env
            .call_static_method(
                jni_str!("android/view/accessibility/AccessibilityNodeInfo"),
                jni_str!("obtain"),
                jni_sig!("()Landroid/view/accessibility/AccessibilityNodeInfo;"),
                &[],
            )?
            .l()?;
        env.call_method(
            &info,
            jni_str!("setSource"),
            jni_sig!("(Landroid/view/View;I)V"),
            &[JValue::Object(&host.view), JValue::Int(id)],
        )?;
        if id == HOST {
            text(env, &info, jni_str!("setClassName"), "android.view.View")?;
            flag(env, &info, jni_str!("setEnabled"), true)?;
            let parent = env
                .call_method(
                    host.view.as_ref(),
                    jni_str!("getParent"),
                    jni_sig!("()Landroid/view/ViewParent;"),
                    &[],
                )?
                .l()?;
            if !parent.is_null() && env.is_instance_of(&parent, jni_str!("android/view/View"))? {
                env.call_method(
                    &info,
                    jni_str!("setParent"),
                    jni_sig!("(Landroid/view/View;)V"),
                    &[JValue::Object(&parent)],
                )?;
            }
        }
        let package =
            android::paths().map_or_else(|| "com.suixing.agent".into(), |paths| paths.package_name);
        text(env, &info, jni_str!("setPackageName"), &package)?;
        let location = env.new_int_array(2)?;
        env.call_method(
            host.view.as_ref(),
            jni_str!("getLocationOnScreen"),
            jni_sig!("([I)V"),
            &[JValue::Object(&location)],
        )?;
        let mut origin = [0; 2];
        env.get_int_array_region(&location, 0, &mut origin)?;
        let width = env
            .call_method(
                host.view.as_ref(),
                jni_str!("getWidth"),
                jni_sig!("()I"),
                &[],
            )?
            .i()?
            .max(0);
        let height = env
            .call_method(
                host.view.as_ref(),
                jni_str!("getHeight"),
                jni_sig!("()I"),
                &[],
            )?
            .i()?
            .max(0);
        let bounds = if id == HOST {
            [0.0, 0.0, width as f64, height as f64]
        } else {
            entry.map_or([0.0; 4], |entry| entry.bounds)
        };
        let visible = bounds[2] > bounds[0]
            && bounds[3] > bounds[1]
            && bounds[2] > 0.0
            && bounds[3] > 0.0
            && bounds[0] < width as f64
            && bounds[1] < height as f64;
        flag(env, &info, jni_str!("setVisibleToUser"), visible)?;
        let parent = entry.map_or(HOST, |entry| entry.parent);
        if id != HOST {
            env.call_method(
                &info,
                jni_str!("setParent"),
                jni_sig!("(Landroid/view/View;I)V"),
                &[JValue::Object(&host.view), JValue::Int(parent)],
            )?;
        }
        let parent_origin = if parent == HOST {
            [0.0, 0.0]
        } else {
            data.snapshot
                .nodes
                .get(&parent)
                .map_or([0.0, 0.0], |entry| [entry.bounds[0], entry.bounds[1]])
        };
        for (method, offsets) in [
            (
                jni_str!("setBoundsInParent"),
                [-parent_origin[0], -parent_origin[1]],
            ),
            (
                jni_str!("setBoundsInScreen"),
                [origin[0] as f64, origin[1] as f64],
            ),
        ] {
            let rect = env.new_object(
                jni_str!("android/graphics/Rect"),
                jni_sig!("(IIII)V"),
                &[
                    JValue::Int((bounds[0] + offsets[0]).floor() as i32),
                    JValue::Int((bounds[1] + offsets[1]).floor() as i32),
                    JValue::Int((bounds[2] + offsets[0]).ceil() as i32),
                    JValue::Int((bounds[3] + offsets[1]).ceil() as i32),
                ],
            )?;
            env.call_method(
                &info,
                method,
                jni_sig!("(Landroid/graphics/Rect;)V"),
                &[JValue::Object(&rect)],
            )?;
        }
        if let Some(entry) = entry {
            let node = &entry.node;
            let password = protected(&data.snapshot, id);
            text(
                env,
                &info,
                jni_str!("setClassName"),
                class_name(node.role()),
            )?;
            if let Some(label) = node.label().or(node.description()) {
                text(env, &info, jni_str!("setContentDescription"), label)?;
            }
            if let Some(value) = node.value() {
                text(
                    env,
                    &info,
                    jni_str!("setText"),
                    if password { "••••" } else { value },
                )?;
            }
            flag(env, &info, jni_str!("setPassword"), password)?;
            flag(env, &info, jni_str!("setEnabled"), !node.is_disabled())?;
            flag(env, &info, jni_str!("setEditable"), editable(node))?;
            flag(
                env,
                &info,
                jni_str!("setClickable"),
                supported(node, Action::Click),
            )?;
            flag(
                env,
                &info,
                jni_str!("setLongClickable"),
                supported(node, Action::ShowContextMenu),
            )?;
            flag(
                env,
                &info,
                jni_str!("setFocusable"),
                id != HOST
                    && (node.label().is_some()
                        || node.value().is_some()
                        || node.supports_action(Action::Focus)),
            )?;
            flag(
                env,
                &info,
                jni_str!("setFocused"),
                data.snapshot.input_focus == id,
            )?;
            flag(
                env,
                &info,
                jni_str!("setAccessibilityFocused"),
                data.accessibility_focus == id,
            )?;
            flag(
                env,
                &info,
                jni_str!("setCheckable"),
                node.toggled().is_some(),
            )?;
            flag(
                env,
                &info,
                jni_str!("setChecked"),
                matches!(node.toggled(), Some(accesskit::Toggled::True)),
            )?;
            flag(
                env,
                &info,
                jni_str!("setSelected"),
                node.is_selected().unwrap_or(false),
            )?;
            flag(
                env,
                &info,
                jni_str!("setMultiLine"),
                node.role() == Role::MultilineTextInput,
            )?;
            if supported(node, Action::SetTextSelection)
                && let Some(positions) = text_positions(&data.snapshot, id)
            {
                env.call_method(
                    &info,
                    jni_str!("addAction"),
                    jni_sig!("(I)V"),
                    &[JValue::Int(131072)],
                )?;
                if let Some(selection) = node.text_selection() {
                    let start = positions
                        .iter()
                        .find(|(_, position)| *position == selection.anchor)
                        .map(|(offset, _)| *offset);
                    let end = positions
                        .iter()
                        .find(|(_, position)| *position == selection.focus)
                        .map(|(offset, _)| *offset);
                    if let (Some(start), Some(end)) = (start, end) {
                        env.call_method(
                            &info,
                            jni_str!("setTextSelection"),
                            jni_sig!("(II)V"),
                            &[
                                JValue::Int(start.min(end) as i32),
                                JValue::Int(start.max(end) as i32),
                            ],
                        )?;
                    }
                }
            }
            if let (Some(minimum), Some(maximum), Some(value)) = (
                node.min_numeric_value(),
                node.max_numeric_value(),
                node.numeric_value(),
            ) && [minimum, maximum, value]
                .iter()
                .all(|value| value.is_finite())
                && minimum <= maximum
            {
                let range = env
                    .call_static_method(
                        jni_str!("android/view/accessibility/AccessibilityNodeInfo$RangeInfo"),
                        jni_str!("obtain"),
                        jni_sig!(
                            "(IFFF)Landroid/view/accessibility/AccessibilityNodeInfo$RangeInfo;"
                        ),
                        &[
                            JValue::Int(1),
                            JValue::Float(minimum as f32),
                            JValue::Float(maximum as f32),
                            JValue::Float(value as f32),
                        ],
                    )?
                    .l()?;
                env.call_method(
                    &info,
                    jni_str!("setRangeInfo"),
                    jni_sig!("(Landroid/view/accessibility/AccessibilityNodeInfo$RangeInfo;)V"),
                    &[JValue::Object(&range)],
                )?;
                if supported(node, Action::SetValue) {
                    env.call_method(
                        &info,
                        jni_str!("addAction"),
                        jni_sig!("(I)V"),
                        &[JValue::Int(16908349)],
                    )?;
                }
            }
            let scrolling = [
                Action::ScrollDown,
                Action::ScrollUp,
                Action::ScrollLeft,
                Action::ScrollRight,
            ]
            .into_iter()
            .any(|action| supported(node, action));
            flag(env, &info, jni_str!("setScrollable"), scrolling)?;
            for (android_action, action) in [
                (1, Action::Focus),
                (2, Action::Blur),
                (16, Action::Click),
                (32, Action::ShowContextMenu),
                (4096, Action::ScrollDown),
                (8192, Action::ScrollUp),
                (2097152, Action::SetValue),
                (16908344, Action::ScrollUp),
                (16908345, Action::ScrollLeft),
                (16908346, Action::ScrollDown),
                (16908347, Action::ScrollRight),
                (262144, Action::Expand),
                (524288, Action::Collapse),
                (16908342, Action::ScrollIntoView),
            ] {
                if supported(node, action) && (android_action != 2097152 || editable(node)) {
                    env.call_method(
                        &info,
                        jni_str!("addAction"),
                        jni_sig!("(I)V"),
                        &[JValue::Int(android_action)],
                    )?;
                }
            }
            if supported(node, Action::Increment) {
                env.call_method(
                    &info,
                    jni_str!("addAction"),
                    jni_sig!("(I)V"),
                    &[JValue::Int(4096)],
                )?;
            }
            if supported(node, Action::Decrement) {
                env.call_method(
                    &info,
                    jni_str!("addAction"),
                    jni_sig!("(I)V"),
                    &[JValue::Int(8192)],
                )?;
            }
            if id != HOST {
                env.call_method(
                    &info,
                    jni_str!("addAction"),
                    jni_sig!("(I)V"),
                    &[JValue::Int(if data.accessibility_focus == id {
                        128
                    } else {
                        64
                    })],
                )?;
            }
            for child in &entry.children {
                env.call_method(
                    &info,
                    jni_str!("addChild"),
                    jni_sig!("(Landroid/view/View;I)V"),
                    &[JValue::Object(&host.view), JValue::Int(*child)],
                )?;
            }
        } else if let Some(error) = data.snapshot.error {
            text(env, &info, jni_str!("setContentDescription"), error)?;
            flag(env, &info, jni_str!("setEnabled"), true)?;
        }
        Ok(info)
    }
    pub(crate) fn node<'local>(
        env: &mut Env<'local>,
        handle: u64,
        id: i32,
    ) -> jni::errors::Result<JObject<'local>> {
        let Some(slot) = slot(handle) else {
            return Ok(JObject::null());
        };
        let data = slot.data.lock().unwrap_or_else(|e| e.into_inner());
        if !current(&data) {
            return Ok(JObject::null());
        }
        inspect_node(env, &data, id)
    }
    pub(crate) fn focus<'local>(
        env: &mut Env<'local>,
        handle: u64,
        kind: i32,
    ) -> jni::errors::Result<JObject<'local>> {
        let Some(slot) = slot(handle) else {
            return Ok(JObject::null());
        };
        let data = slot.data.lock().unwrap_or_else(|e| e.into_inner());
        if !current(&data) {
            return Ok(JObject::null());
        }
        let id = if kind == 2 {
            data.accessibility_focus
        } else {
            data.snapshot.input_focus
        };
        if id == NONE {
            return Ok(JObject::null());
        }
        inspect_node(env, &data, id)
    }
    pub(crate) fn search<'local>(
        env: &mut Env<'local>,
        handle: u64,
        query: &JObject<'local>,
        start: i32,
    ) -> jni::errors::Result<JObject<'local>> {
        let result = env.new_object(jni_str!("java/util/ArrayList"), jni_sig!("()V"), &[])?;
        if query.is_null() {
            return Ok(result);
        }
        let Some(slot) = slot(handle) else {
            return Ok(result);
        };
        let query = env.new_local_ref(query)?;
        let query = env
            .cast_local::<jni::objects::JString>(query)?
            .try_to_string(env)?;
        if query.is_empty() || query.len() > 8192 {
            return Ok(result);
        }
        let data = slot.data.lock().unwrap_or_else(|e| e.into_inner());
        if !current(&data) {
            return Ok(result);
        }
        let mut descendants = HashSet::new();
        let mut pending = vec![start];
        while let Some(id) = pending.pop() {
            if !descendants.insert(id) {
                continue;
            }
            if let Some(entry) = data.snapshot.nodes.get(&id) {
                pending.extend(&entry.children);
            }
        }
        for (id, entry) in data
            .snapshot
            .nodes
            .iter()
            .filter(|(id, entry)| {
                descendants.contains(id)
                    && !protected(&data.snapshot, **id)
                    && [entry.node.label(), entry.node.value()]
                        .into_iter()
                        .flatten()
                        .any(|text| text.contains(&query))
            })
            .take(64)
        {
            let _ = entry;
            let node = inspect_node(env, &data, *id)?;
            env.call_method(
                &result,
                jni_str!("add"),
                jni_sig!("(Ljava/lang/Object;)Z"),
                &[JValue::Object(&node)],
            )?;
        }
        Ok(result)
    }
    pub(crate) fn action(
        env: &mut Env<'_>,
        handle: u64,
        id: i32,
        android_action: i32,
        args: &JObject<'_>,
    ) -> jni::errors::Result<bool> {
        let Some(slot) = slot(handle) else {
            return Ok(false);
        };
        let mut data = slot.data.lock().unwrap_or_else(|e| e.into_inner());
        if !current(&data) {
            return Ok(false);
        }
        let Some(entry) = data.snapshot.nodes.get(&id) else {
            return Ok(false);
        };
        if entry.node.is_disabled() {
            return Ok(false);
        }
        let view = data.host.as_ref().unwrap().view.clone();
        if android_action == 64 || android_action == 128 {
            if android_action == 64 {
                if data.accessibility_focus == id {
                    return Ok(false);
                }
                let old = std::mem::replace(&mut data.accessibility_focus, id);
                drop(data);
                if old != NONE {
                    emit(env, &view, old, 65536)?;
                }
                emit(env, &view, id, 32768)?;
            } else {
                if data.accessibility_focus != id {
                    return Ok(false);
                }
                data.accessibility_focus = NONE;
                drop(data);
                emit(env, &view, id, 65536)?;
            }
            return Ok(true);
        }
        let action = match android_action {
            1 => Action::Focus,
            2 => Action::Blur,
            16 => Action::Click,
            32 => Action::ShowContextMenu,
            4096 if supported(&entry.node, Action::Increment) => Action::Increment,
            8192 if supported(&entry.node, Action::Decrement) => Action::Decrement,
            4096 | 16908346 => Action::ScrollDown,
            8192 | 16908344 => Action::ScrollUp,
            16908345 => Action::ScrollLeft,
            16908347 => Action::ScrollRight,
            2097152 | 16908349 => Action::SetValue,
            131072 => Action::SetTextSelection,
            262144 => Action::Expand,
            524288 => Action::Collapse,
            16908342 => Action::ScrollIntoView,
            _ => return Ok(false),
        };
        if !supported(&entry.node, action) || data.actions.len() >= MAX_ACTIONS {
            return Ok(false);
        }
        let payload = if action == Action::SetTextSelection {
            if args.is_null() {
                return Ok(false);
            }
            let Some(positions) = text_positions(&data.snapshot, id) else {
                return Ok(false);
            };
            let mut offsets = [0; 2];
            for (index, name) in [
                "ACTION_ARGUMENT_SELECTION_START_INT",
                "ACTION_ARGUMENT_SELECTION_END_INT",
            ]
            .into_iter()
            .enumerate()
            {
                let name = env.new_string(name)?;
                offsets[index] = env
                    .call_method(
                        args,
                        jni_str!("getInt"),
                        jni_sig!("(Ljava/lang/String;)I"),
                        &[JValue::Object(&name)],
                    )?
                    .i()?;
            }
            let at = |offset: i32| {
                positions
                    .iter()
                    .rev()
                    .find(|(units, _)| offset >= 0 && *units == offset as usize)
                    .map(|(_, position)| *position)
            };
            let (Some(anchor), Some(focus)) = (at(offsets[0]), at(offsets[1])) else {
                return Ok(false);
            };
            Some(ActionData::SetTextSelection(accesskit::TextSelection {
                anchor,
                focus,
            }))
        } else if android_action == 16908349 {
            if args.is_null() || entry.node.numeric_value().is_none() {
                return Ok(false);
            }
            let name =
                env.new_string("android.view.accessibility.action.ARGUMENT_PROGRESS_VALUE")?;
            let value = env
                .call_method(
                    args,
                    jni_str!("getFloat"),
                    jni_sig!("(Ljava/lang/String;)F"),
                    &[JValue::Object(&name)],
                )?
                .f()?;
            if !value.is_finite() {
                return Ok(false);
            }
            Some(ActionData::NumericValue(value as f64))
        } else if action == Action::SetValue {
            if args.is_null() || !editable(&entry.node) {
                return Ok(false);
            }
            let name = env.new_string("ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE")?;
            let value = env
                .call_method(
                    args,
                    jni_str!("getCharSequence"),
                    jni_sig!("(Ljava/lang/String;)Ljava/lang/CharSequence;"),
                    &[JValue::Object(&name)],
                )?
                .l()?;
            if value.is_null() {
                return Ok(false);
            }
            let length = env
                .call_method(&value, jni_str!("length"), jni_sig!("()I"), &[])?
                .i()?;
            if !(0..=65536).contains(&length) {
                return Ok(false);
            }
            let value = env
                .call_method(
                    &value,
                    jni_str!("toString"),
                    jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?
                .l()?;
            let value = env
                .cast_local::<jni::objects::JString>(value)?
                .try_to_string(env)?;
            Some(ActionData::Value(value.into_boxed_str()))
        } else {
            None
        };
        let source = entry.source;
        let expected = entry.node.clone();
        data.actions.push_back((
            source,
            expected,
            ActionRequest {
                action,
                target_tree: TreeId::ROOT,
                target_node: source,
                data: payload,
            },
        ));
        drop(data);
        wake(handle);
        Ok(true)
    }
    pub(crate) fn hover(
        env: &mut Env<'_>,
        handle: u64,
        motion: &JObject<'_>,
    ) -> jni::errors::Result<bool> {
        let Some(slot) = slot(handle) else {
            return Ok(false);
        };
        let mut data = slot.data.lock().unwrap_or_else(|e| e.into_inner());
        if !current(&data) {
            return Ok(false);
        }
        let host = &data.host.as_ref().unwrap().view;
        let context = env
            .call_method(
                host.as_ref(),
                jni_str!("getContext"),
                jni_sig!("()Landroid/content/Context;"),
                &[],
            )?
            .l()?;
        let name = env.new_string("accessibility")?;
        let manager = env
            .call_method(
                &context,
                jni_str!("getSystemService"),
                jni_sig!("(Ljava/lang/String;)Ljava/lang/Object;"),
                &[JValue::Object(&name)],
            )?
            .l()?;
        if manager.is_null()
            || !env
                .call_method(
                    &manager,
                    jni_str!("isTouchExplorationEnabled"),
                    jni_sig!("()Z"),
                    &[],
                )?
                .z()?
        {
            return Ok(false);
        }
        let kind = env
            .call_method(motion, jni_str!("getActionMasked"), jni_sig!("()I"), &[])?
            .i()?;
        if ![7, 9, 10].contains(&kind) {
            return Ok(false);
        }
        let x = env
            .call_method(motion, jni_str!("getX"), jni_sig!("()F"), &[])?
            .f()? as f64;
        let y = env
            .call_method(motion, jni_str!("getY"), jni_sig!("()F"), &[])?
            .f()? as f64;
        let hit = if kind == 10 {
            NONE
        } else {
            data.snapshot
                .nodes
                .iter()
                .filter(|(id, entry)| {
                    **id != HOST
                        && entry.node.role() != Role::TextRun
                        && (entry.node.label().is_some()
                            || entry.node.value().is_some()
                            || supported(&entry.node, Action::Focus))
                        && entry.bounds[0] <= x
                        && x < entry.bounds[2]
                        && entry.bounds[1] <= y
                        && y < entry.bounds[3]
                })
                .min_by(|(_, a), (_, b)| {
                    let size = |entry: &Entry| {
                        (entry.bounds[2] - entry.bounds[0]) * (entry.bounds[3] - entry.bounds[1])
                    };
                    size(a).total_cmp(&size(b))
                })
                .map_or(NONE, |(id, _)| *id)
        };
        if hit != data.hovered {
            let old = std::mem::replace(&mut data.hovered, hit);
            let view = data.host.as_ref().unwrap().view.clone();
            drop(data);
            if hit != NONE {
                emit(env, &view, hit, 128)?;
            }
            if old != NONE {
                emit(env, &view, old, 256)?;
            }
        }
        Ok(true)
    }
}
