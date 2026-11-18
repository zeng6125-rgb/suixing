use super::*;
pub(super) struct Node {
    text: String,
    description: String,
    class: String,
    package: String,
    id: String,
    bounds: [i32; 4],
    clickable: bool,
    password: bool,
}
pub(super) struct Tree {
    nodes: Vec<Node>,
}
impl Tree {
    pub fn dump(&self, maximum: usize) -> Value {
        let mut text = String::new();
        for (index, node) in self.nodes.iter().take(maximum).enumerate() {
            text.push_str(&format!(
                "[{index}] {} text={} description={} bounds={:?} id={} clickable={}\n",
                node.class,
                if node.password {
                    "[password]"
                } else {
                    &node.text
                },
                node.description,
                node.bounds,
                node.id,
                node.clickable
            ));
        }
        json!({"ok":true,"text":text,"nodes":self.nodes.len().min(maximum),"incomplete":self.nodes.len()>maximum})
    }
    pub fn find(&self, text: &str, exact: bool) -> Result<(u32, u32), String> {
        let matching = |value: &str| {
            if exact {
                value == text
            } else {
                value.contains(text)
            }
        };
        let hits = self
            .nodes
            .iter()
            .filter(|n| !n.password && (matching(&n.text) || matching(&n.description)))
            .filter(|n| {
                n.bounds[0] >= 0
                    && n.bounds[1] >= 0
                    && n.bounds[2] > n.bounds[0]
                    && n.bounds[3] > n.bounds[1]
            })
            .collect::<Vec<_>>();
        let clickable = hits
            .iter()
            .copied()
            .filter(|n| n.clickable)
            .collect::<Vec<_>>();
        let hit = if clickable.len() == 1 {
            clickable[0]
        } else if clickable.is_empty() && hits.len() == 1 {
            hits[0]
        } else {
            return Err("匹配元素为空或不唯一，未猜测点击位置".into());
        };
        Ok((
            ((hit.bounds[0] as i64 + hit.bounds[2] as i64) / 2) as u32,
            ((hit.bounds[1] as i64 + hit.bounds[3] as i64) / 2) as u32,
        ))
    }
}
pub(super) fn parse(source: &str) -> Result<Tree, String> {
    if source.len() > 512 * 1024 || source.contains("<!DOCTYPE") || source.contains("<!ENTITY") {
        return Err("界面XML超过预算或含外部声明".into());
    }
    let begin = source.find("<hierarchy").ok_or("未返回可识别的界面XML")?;
    let end = source.rfind("</hierarchy>").ok_or("界面XML不完整")? + "</hierarchy>".len();
    if begin >= end {
        return Err("界面XML范围无效".into());
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (begin, end);
        Err("当前平台没有Android界面XML解析器".into())
    }
    #[cfg(target_os = "android")]
    {
        device::parse(&source[begin..end])
    }
}
#[cfg(target_os = "android")]
mod device {
    use super::*;
    use crate::platform::android;
    use jni::{Env, JValue, jni_sig, jni_str, objects::JObject};
    fn attribute(env: &mut Env, parser: &JObject, name: &str) -> jni::errors::Result<String> {
        let name = env.new_string(name)?;
        let value = env
            .call_method(
                parser,
                jni_str!("getAttributeValue"),
                jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
                &[JValue::Object(&JObject::null()), JValue::Object(&name)],
            )?
            .l()?;
        if value.is_null() {
            Ok(String::new())
        } else {
            android::string_value(env, value, 2048)
        }
    }
    pub(super) fn parse(source: &str) -> Result<Tree, String> {
        android::invoke(|env| {
            let parser = env
                .call_static_method(
                    jni_str!("android/util/Xml"),
                    jni_str!("newPullParser"),
                    jni_sig!("()Lorg/xmlpull/v1/XmlPullParser;"),
                    &[],
                )?
                .l()?;
            let source = env.new_string(source)?;
            let reader = env.new_object(
                jni_str!("java/io/StringReader"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[JValue::Object(&source)],
            )?;
            env.call_method(
                &parser,
                jni_str!("setInput"),
                jni_sig!("(Ljava/io/Reader;)V"),
                &[JValue::Object(&reader)],
            )?;
            let mut nodes = Vec::new();
            let bounds = regex::Regex::new(r"^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$")
                .map_err(|_| jni::errors::Error::NullPtr("bounds parser"))?;
            loop {
                let event = env
                    .call_method(&parser, jni_str!("next"), jni_sig!("()I"), &[])?
                    .i()?;
                if event == 1 {
                    break;
                }
                if event != 2 {
                    continue;
                }
                let node =
                    env.with_local_frame(32, |env| -> jni::errors::Result<Option<Node>> {
                        let name = env
                            .call_method(
                                &parser,
                                jni_str!("getName"),
                                jni_sig!("()Ljava/lang/String;"),
                                &[],
                            )?
                            .l()?;
                        if android::string_value(env, name, 32)? != "node" {
                            return Ok(None);
                        }
                        let raw = attribute(env, &parser, "bounds")?;
                        let captures = bounds
                            .captures(&raw)
                            .ok_or(jni::errors::Error::NullPtr("node bounds invalid"))?;
                        let mut rectangle = [0i32; 4];
                        for (index, item) in rectangle.iter_mut().enumerate() {
                            *item = captures[index + 1]
                                .parse()
                                .map_err(|_| jni::errors::Error::NullPtr("node bounds overflow"))?;
                        }
                        Ok(Some(Node {
                            text: attribute(env, &parser, "text")?,
                            description: attribute(env, &parser, "content-desc")?,
                            class: attribute(env, &parser, "class")?,
                            package: attribute(env, &parser, "package")?,
                            id: attribute(env, &parser, "resource-id")?,
                            bounds: rectangle,
                            clickable: attribute(env, &parser, "clickable")? == "true",
                            password: attribute(env, &parser, "password")? == "true",
                        }))
                    })?;
                if let Some(node) = node {
                    nodes.push(node);
                    if nodes.len() > 3000 {
                        return Err(jni::errors::Error::NullPtr("node visit budget"));
                    }
                }
            }
            Ok(Tree { nodes })
        })
    }
}
pub(super) fn current_package(source: &str, display_id: i32) -> Result<String, String> {
    if source.len() > 512 * 1024 {
        return Err("窗口列表超过预算".into());
    }
    let displays = regex::Regex::new(r"(?m)^\s*Display:.*(?:mDisplayId|displayId)=(\d+)")
        .map_err(|_| "屏幕段解析失败")?;
    let focus = regex::Regex::new(r"mCurrentFocus=.*?\bu\d+\s+([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+")
        .map_err(|_| "窗口焦点解析失败")?;
    let sections = displays.captures_iter(source).collect::<Vec<_>>();
    for (index, section) in sections.iter().enumerate() {
        if section[1].parse::<i32>().ok() != Some(display_id) {
            continue;
        }
        let start = section.get(0).ok_or("屏幕段缺失")?.end();
        let end = sections
            .get(index + 1)
            .and_then(|c| c.get(0))
            .map_or(source.len(), |m| m.start());
        if let Some(package) = focus.captures(&source[start..end]).and_then(|c| c.get(1)) {
            return Ok(package.as_str().into());
        }
    }
    Err("当前ROM窗口输出未提供可核对的目标屏幕焦点，未返回其它屏幕应用".into())
}
