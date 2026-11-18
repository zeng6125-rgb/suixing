use super::accessibility::{self, Object};
use super::*;
use crate::platform::android;
use jni::{Env, JValue, jni_sig, jni_str, objects::JObject};
use std::{
    collections::VecDeque,
    time::{Duration, Instant},
};

const MAX_VISITS: usize = 3000;
const MAX_DEPTH: usize = 30;
const MAX_TEXT: usize = 256 * 1024;
struct Node {
    object: Object,
    sdk: i32,
}
impl Drop for Node {
    fn drop(&mut self) {
        if self.sdk < 33 {
            let _ = android::invoke(|env| {
                env.call_method(
                    self.object.as_ref(),
                    jni_str!("recycle"),
                    jni_sig!("()V"),
                    &[],
                )?;
                Ok(())
            });
        }
    }
}
struct Info {
    text: String,
    description: String,
    hint: String,
    class: String,
    id: String,
    rect: [i32; 4],
    editable: bool,
    clickable: bool,
    scrollable: bool,
    checkable: bool,
    checked: bool,
    visible: bool,
    truncated: bool,
}
fn wrap(object: Object) -> Node {
    Node {
        object,
        sdk: android::paths().map_or(0, |p| p.sdk_int),
    }
}
fn root(target: &Target) -> Result<Node, String> {
    let service = accessibility::verify(target)?;
    let object = android::invoke(|env| {
        env.with_local_frame(64, |env| {
            let root = if android::paths().is_some_and(|p| p.sdk_int >= 30) {
                let displays = env
                    .call_method(
                        service.as_ref(),
                        jni_str!("getWindowsOnAllDisplays"),
                        jni_sig!("()Landroid/util/SparseArray;"),
                        &[],
                    )?
                    .l()?;
                let windows = env
                    .call_method(
                        displays,
                        jni_str!("get"),
                        jni_sig!("(I)Ljava/lang/Object;"),
                        &[JValue::Int(target.display_id)],
                    )?
                    .l()?;
                if windows.is_null() {
                    return Err(jni::errors::Error::NullPtr(
                        "target display has no accessibility windows",
                    ));
                }
                let count = env
                    .call_method(&windows, jni_str!("size"), jni_sig!("()I"), &[])?
                    .i()?;
                if !(0..=256).contains(&count) {
                    return Err(jni::errors::Error::NullPtr("accessibility window budget"));
                }
                let mut selected = None;
                let mut selected_score = -1;
                for index in 0..count {
                    let window = env
                        .call_method(
                            &windows,
                            jni_str!("get"),
                            jni_sig!("(I)Ljava/lang/Object;"),
                            &[JValue::Int(index)],
                        )?
                        .l()?;
                    let kind = env
                        .call_method(&window, jni_str!("getType"), jni_sig!("()I"), &[])?
                        .i()?;
                    let id = env
                        .call_method(&window, jni_str!("getDisplayId"), jni_sig!("()I"), &[])?
                        .i()?;
                    if id != target.display_id
                        || kind == 2
                        || kind == 4
                        || target.display_id != 0 && kind == 3
                    {
                        continue;
                    }
                    let active = env
                        .call_method(&window, jni_str!("isActive"), jni_sig!("()Z"), &[])?
                        .z()?;
                    let focused = env
                        .call_method(&window, jni_str!("isFocused"), jni_sig!("()Z"), &[])?
                        .z()?;
                    let score = i32::from(active) * 2 + i32::from(focused);
                    if score <= selected_score {
                        continue;
                    }
                    let node = env
                        .call_method(
                            &window,
                            jni_str!("getRoot"),
                            jni_sig!("()Landroid/view/accessibility/AccessibilityNodeInfo;"),
                            &[],
                        )?
                        .l()?;
                    if node.is_null() {
                        continue;
                    }
                    selected = Some(node);
                    selected_score = score;
                }
                selected.ok_or(jni::errors::Error::NullPtr(
                    "target accessibility root unavailable",
                ))?
            } else {
                env.call_method(
                    service.as_ref(),
                    jni_str!("getRootInActiveWindow"),
                    jni_sig!("()Landroid/view/accessibility/AccessibilityNodeInfo;"),
                    &[],
                )?
                .l()?
            };
            if root.is_null() {
                return Err(jni::errors::Error::NullPtr(
                    "accessibility root unavailable",
                ));
            }
            Ok(Arc::new(env.new_global_ref(root)?))
        })
    })?;
    Ok(wrap(object))
}
fn label(
    env: &mut Env,
    node: &JObject,
    getter: &str,
    max: usize,
    string_result: bool,
) -> jni::errors::Result<(String, bool)> {
    let descriptor = if string_result {
        jni_sig!("()Ljava/lang/String;")
    } else {
        jni_sig!("()Ljava/lang/CharSequence;")
    };
    let value = env
        .call_method(node, jni::strings::JNIString::new(getter), descriptor, &[])?
        .l()?;
    if value.is_null() {
        return Ok((String::new(), false));
    }
    let length = env
        .call_method(&value, jni_str!("length"), jni_sig!("()I"), &[])?
        .i()?;
    if length < 0 {
        return Err(jni::errors::Error::NullPtr(
            "negative accessibility text length",
        ));
    }
    let truncated = length as usize > max;
    let value = if truncated {
        env.call_method(
            value,
            jni_str!("subSequence"),
            jni_sig!("(II)Ljava/lang/CharSequence;"),
            &[JValue::Int(0), JValue::Int(max as i32)],
        )?
        .l()?
    } else {
        value
    };
    let text = env
        .call_method(
            value,
            jni_str!("toString"),
            jni_sig!("()Ljava/lang/String;"),
            &[],
        )?
        .l()?;
    // UTF8 may need four bytes per UTF16 code unit. Every label announces its cap.
    let text = android::string_value(env, text, max.saturating_mul(4))?;
    Ok((text, truncated))
}
fn boolean(env: &mut Env, node: &JObject, name: &str) -> jni::errors::Result<bool> {
    env.call_method(
        node,
        jni::strings::JNIString::new(name),
        jni_sig!("()Z"),
        &[],
    )?
    .z()
}
fn info(node: &Node) -> Result<Info, String> {
    android::invoke(|env| {
        env.with_local_frame(32, |env| {
            let object = node.object.as_ref();
            let password = boolean(env, object, "isPassword")?;
            let (text, text_cap) = if password {
                ("[password]".into(), false)
            } else {
                label(env, object, "getText", 512, false)?
            };
            let (description, desc_cap) = label(env, object, "getContentDescription", 512, false)?;
            let (hint, hint_cap) = label(env, object, "getHintText", 512, false)?;
            let (class, _) = label(env, object, "getClassName", 256, false)?;
            let (id, _) = label(env, object, "getViewIdResourceName", 256, true)?;
            let rect = env.new_object(jni_str!("android/graphics/Rect"), jni_sig!("()V"), &[])?;
            env.call_method(
                object,
                jni_str!("getBoundsInScreen"),
                jni_sig!("(Landroid/graphics/Rect;)V"),
                &[JValue::Object(&rect)],
            )?;
            let mut bounds = [0; 4];
            for (index, name) in [
                jni_str!("left"),
                jni_str!("top"),
                jni_str!("right"),
                jni_str!("bottom"),
            ]
            .into_iter()
            .enumerate()
            {
                bounds[index] = env.get_field(&rect, name, jni_sig!("I"))?.i()?;
            }
            Ok(Info {
                text,
                description,
                hint,
                class,
                id,
                rect: bounds,
                editable: boolean(env, object, "isEditable")?,
                clickable: boolean(env, object, "isClickable")?,
                scrollable: boolean(env, object, "isScrollable")?,
                checkable: boolean(env, object, "isCheckable")?,
                checked: boolean(env, object, "isChecked")?,
                visible: boolean(env, object, "isVisibleToUser")?,
                truncated: text_cap || desc_cap || hint_cap,
            })
        })
    })
}
fn children(node: &Node, remaining: usize) -> Result<(Vec<Node>, bool), String> {
    android::invoke(|env| {
        env.with_local_frame(32, |env| {
            let count = env
                .call_method(
                    node.object.as_ref(),
                    jni_str!("getChildCount"),
                    jni_sig!("()I"),
                    &[],
                )?
                .i()?;
            if count < 0 {
                return Err(jni::errors::Error::NullPtr(
                    "negative accessibility child count",
                ));
            }
            let take = (count as usize).min(remaining);
            let mut children = Vec::with_capacity(take);
            for index in 0..take {
                let child = env
                    .call_method(
                        node.object.as_ref(),
                        jni_str!("getChild"),
                        jni_sig!("(I)Landroid/view/accessibility/AccessibilityNodeInfo;"),
                        &[JValue::Int(index as i32)],
                    )?
                    .l()?;
                if !child.is_null() {
                    children.push(wrap(Arc::new(env.new_global_ref(&child)?)));
                    env.delete_local_ref(child);
                } else {
                    return Err(jni::errors::Error::NullPtr(
                        "accessibility child became unavailable",
                    ));
                }
            }
            Ok((children, count as usize > take))
        })
    })
}
pub(super) fn dump(
    target: &Target,
    max_nodes: usize,
    cancel: &CancelToken,
) -> Result<Dump, String> {
    if !(10..=500).contains(&max_nodes) {
        return Err("ui_dump节点预算必须在10..500之间".into());
    }
    let started = Instant::now();
    let mut queue = VecDeque::from([(root(target)?, 0usize)]);
    let mut text = String::new();
    let mut nodes = 0;
    let mut visits = 0;
    let mut incomplete = false;
    while let Some((node, depth)) = queue.pop_front() {
        if cancel.is_cancelled() {
            return Err("界面树读取已经取消".into());
        }
        if visits >= MAX_VISITS
            || started.elapsed() >= Duration::from_millis(800)
            || nodes >= max_nodes
        {
            incomplete = true;
            break;
        }
        if depth > MAX_DEPTH {
            incomplete = true;
            continue;
        }
        visits += 1;
        let item = info(&node)?;
        incomplete |= item.truncated;
        let interesting = !item.text.trim().is_empty()
            || !item.description.trim().is_empty()
            || !item.hint.trim().is_empty()
            || item.clickable
            || item.editable
            || item.scrollable
            || item.checkable;
        if interesting && item.visible && item.rect[2] > item.rect[0] && item.rect[3] > item.rect[1]
        {
            let cx = (item.rect[0] as i64 + item.rect[2] as i64) / 2;
            let cy = (item.rect[1] as i64 + item.rect[3] as i64) / 2;
            let mut flags = Vec::new();
            if item.clickable {
                flags.push("clickable");
            }
            if item.editable {
                flags.push("editable");
            }
            if item.scrollable {
                flags.push("scrollable");
            }
            if item.checkable {
                flags.push(if item.checked { "checked" } else { "unchecked" });
            }
            text.push_str(&format!(
                "[{nodes}] {} text={} description={} hint={} center=({cx},{cy}) bounds={:?} id={} {}{}\n",
                item.class,
                item.text.replace(['\n', '\r'], " "),
                item.description.replace(['\n', '\r'], " "),
                item.hint.replace(['\n', '\r'], " "),
                item.rect,
                item.id,
                flags.join(" "),
                if item.truncated {
                    " label_truncated"
                } else {
                    ""
                }
            ));
            nodes += 1;
            if text.len() > 1024 * 1024 {
                return Err("界面树文本超过1 MiB预算，未返回假完整树".into());
            }
        }
        let remaining = MAX_VISITS.saturating_sub(visits + queue.len());
        let (child_nodes, clipped) = children(&node, remaining)?;
        incomplete |= clipped;
        for child in child_nodes {
            queue.push_back((child, depth + 1));
        }
    }
    accessibility::verify(target)?;
    Ok(Dump {
        text,
        nodes,
        visits,
        incomplete,
        elapsed_ms: started.elapsed().as_millis() as u64,
    })
}
pub(super) fn current_app(target: &Target) -> Result<String, String> {
    let node = root(target)?;
    let package =
        android::invoke(|env| label(env, node.object.as_ref(), "getPackageName", 256, false))?.0;
    if package.is_empty() {
        return Err("指定屏幕没有返回当前应用包名".into());
    }
    accessibility::verify(target)?;
    Ok(package)
}
fn find(
    target: &Target,
    needle: &str,
    exact: bool,
    editable_only: bool,
    cancel: &CancelToken,
) -> Result<Node, String> {
    if needle.trim().is_empty() || needle.len() > 4096 {
        return Err("元素文字条件为空或超出预算".into());
    }
    let started = Instant::now();
    let mut queue = VecDeque::from([(root(target)?, 0usize)]);
    let mut visits = 0;
    let mut incomplete = false;
    while let Some((node, depth)) = queue.pop_front() {
        if cancel.is_cancelled() {
            return Err("元素查找已经取消".into());
        }
        if visits >= MAX_VISITS || started.elapsed() >= Duration::from_millis(800) {
            incomplete = true;
            break;
        }
        if depth > MAX_DEPTH {
            incomplete = true;
            continue;
        }
        visits += 1;
        let item = info(&node)?;
        incomplete |= item.truncated;
        let matches = |value: &str| {
            if exact {
                value.trim() == needle.trim()
            } else {
                value.contains(needle)
            }
        };
        if item.visible
            && (!editable_only || item.editable)
            && (matches(&item.text) || matches(&item.description) || matches(&item.hint))
        {
            return Ok(node);
        }
        let (child_nodes, clipped) =
            children(&node, MAX_VISITS.saturating_sub(visits + queue.len()))?;
        incomplete |= clipped;
        for child in child_nodes {
            queue.push_back((child, depth + 1));
        }
    }
    Err(if incomplete {
        "元素查找超过节点、文字或时间预算，未声明不存在"
    } else {
        "指定屏幕没有找到匹配元素"
    }
    .into())
}
fn parent(node: &Node) -> Result<Option<Node>, String> {
    android::invoke(|env| {
        let parent = env
            .call_method(
                node.object.as_ref(),
                jni_str!("getParent"),
                jni_sig!("()Landroid/view/accessibility/AccessibilityNodeInfo;"),
                &[],
            )?
            .l()?;
        if parent.is_null() {
            Ok(None)
        } else {
            Ok(Some(wrap(Arc::new(env.new_global_ref(parent)?))))
        }
    })
}
fn click_node(
    operation: &OperationKey,
    target: &Target,
    node: Node,
    cancel: &Arc<CancelToken>,
) -> Result<(), String> {
    let bounds = info(&node)?.rect;
    let mut current = Some(node);
    for _ in 0..=MAX_DEPTH {
        let Some(node) = current.take() else {
            break;
        };
        accessibility::verify(target)?;
        if cancel.is_cancelled() {
            return Err("元素点击已经取消".into());
        }
        if info(&node)?.clickable {
            let clicked = android::invoke(|env| {
                env.call_method(
                    node.object.as_ref(),
                    jni_str!("performAction"),
                    jni_sig!("(I)Z"),
                    &[JValue::Int(16)],
                )?
                .z()
            })?;
            if clicked {
                return Ok(());
            }
        }
        current = parent(&node)?;
    }
    let x = ((bounds[0] as i64 + bounds[2] as i64) / 2) as f32;
    let y = ((bounds[1] as i64 + bounds[3] as i64) / 2) as f32;
    super::gestures::perform(operation, target, &[(x, y)], 60, cancel)
}
pub(super) fn click_text(
    operation: &OperationKey,
    target: &Target,
    text: &str,
    exact: bool,
    cancel: &Arc<CancelToken>,
) -> Result<(), String> {
    let node = find(target, text, exact, false, cancel)?;
    click_node(operation, target, node, cancel)
}
fn focused(target: &Target, cancel: &CancelToken) -> Result<Node, String> {
    let node = root(target)?;
    let focus = android::invoke(|env| {
        let focus = env
            .call_method(
                node.object.as_ref(),
                jni_str!("findFocus"),
                jni_sig!("(I)Landroid/view/accessibility/AccessibilityNodeInfo;"),
                &[JValue::Int(1)],
            )?
            .l()?;
        if focus.is_null() {
            return Ok(None);
        }
        let same = env.is_same_object(node.object.as_ref(), &focus)?;
        if boolean(env, &focus, "isEditable")? {
            Ok(Some((same, Arc::new(env.new_global_ref(focus)?))))
        } else {
            if node.sdk < 33 && !same {
                env.call_method(focus, jni_str!("recycle"), jni_sig!("()V"), &[])?;
            }
            Ok(None)
        }
    })?;
    if let Some((same, focus)) = focus {
        if same {
            return Ok(node);
        }
        return Ok(wrap(focus));
    }
    let mut queue = VecDeque::from([(node, 0usize)]);
    let mut visits = 0;
    let started = Instant::now();
    while let Some((node, depth)) = queue.pop_front() {
        if cancel.is_cancelled() {
            return Err("输入框查找已经取消".into());
        }
        if visits >= MAX_VISITS
            || depth > MAX_DEPTH
            || started.elapsed() > Duration::from_millis(800)
        {
            return Err("输入框查找超出预算，未写入其它位置".into());
        }
        visits += 1;
        if info(&node)?.editable {
            return Ok(node);
        }
        let (children, clipped) = children(&node, MAX_VISITS.saturating_sub(visits + queue.len()))?;
        if clipped {
            return Err("输入框候选超出预算，未选择部分树".into());
        }
        for child in children {
            queue.push_back((child, depth + 1));
        }
    }
    Err("指定屏幕没有可编辑输入框".into())
}
pub(super) fn type_focused(
    target: &Target,
    text: &str,
    append: bool,
    cancel: &CancelToken,
) -> Result<bool, String> {
    if text.len() > MAX_TEXT || text.contains('\0') {
        return Err("输入文本超出256 KiB预算或含NUL".into());
    }
    let node = focused(target, cancel)?;
    let text = if append {
        if android::invoke(|env| boolean(env, node.object.as_ref(), "isPassword"))? {
            return Err("密码字段不能读取完整原文，未追加或重写遮蔽文本".into());
        }
        let (existing, clipped) =
            android::invoke(|env| label(env, node.object.as_ref(), "getText", MAX_TEXT, false))?;
        if clipped || existing.len().saturating_add(text.len()) > MAX_TEXT {
            return Err("追加输入超过完整字段预算，未截断原文本".into());
        }
        format!("{existing}{text}")
    } else {
        text.to_owned()
    };
    write_on(target, &node, &text, cancel)
}
fn write_on(
    target: &Target,
    node: &Node,
    text: &str,
    cancel: &CancelToken,
) -> Result<bool, String> {
    if text.len() > MAX_TEXT || text.contains('\0') {
        return Err("输入文本超出256 KiB预算或含NUL".into());
    }
    accessibility::verify(target)?;
    if cancel.is_cancelled() {
        return Err("文字输入已取消，未写入字段".into());
    }
    let written = android::invoke(|env| {
        let bundle = env.new_object(jni_str!("android/os/Bundle"), jni_sig!("()V"), &[])?;
        let key = env.new_string("ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE")?;
        let text = env.new_string(&text)?;
        env.call_method(
            &bundle,
            jni_str!("putCharSequence"),
            jni_sig!("(Ljava/lang/String;Ljava/lang/CharSequence;)V"),
            &[JValue::Object(&key), JValue::Object(&text)],
        )?;
        env.call_method(
            node.object.as_ref(),
            jni_str!("performAction"),
            jni_sig!("(ILandroid/os/Bundle;)Z"),
            &[JValue::Int(2_097_152), JValue::Object(&bundle)],
        )?
        .z()
    })?;
    if !written {
        return Err("指定输入框没有接受写入动作".into());
    }
    let verified = android::invoke(|env| {
        if boolean(env, node.object.as_ref(), "isPassword")? {
            return Ok(false);
        }
        if !env
            .call_method(
                node.object.as_ref(),
                jni_str!("refresh"),
                jni_sig!("()Z"),
                &[],
            )?
            .z()?
        {
            return Ok(false);
        }
        let (value, truncated) = label(env, node.object.as_ref(), "getText", MAX_TEXT, false)?;
        Ok(!truncated && value == text)
    })
    .unwrap_or(false);
    accessibility::verify(target)?;
    Ok(verified)
}
pub(super) fn type_into(
    operation: &OperationKey,
    target: &Target,
    label: &str,
    text: &str,
    cancel: &Arc<CancelToken>,
) -> Result<bool, String> {
    if text.len() > MAX_TEXT || text.contains('\0') {
        return Err("输入文本超出256 KiB预算或含NUL，未点击字段".into());
    }
    let matched = find(target, label, false, true, cancel)?;
    click_node(operation, target, matched, cancel)?;
    let deadline = Instant::now() + Duration::from_millis(250);
    loop {
        if cancel.is_cancelled() {
            return Err("表单写入已经取消；之前的定位点击可能已完成".into());
        }
        if let Ok(node) = find(target, label, false, true, cancel) {
            return write_on(target, &node, text, cancel);
        }
        if Instant::now() >= deadline {
            return Err("点击后没有确认目标屏幕输入框，未写入其它位置".into());
        }
        std::thread::sleep(Duration::from_millis(10));
    }
}
