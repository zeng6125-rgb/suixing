use super::*;

#[cfg(not(target_os = "android"))]
pub(crate) fn pick(_: OperationKey, _: PathBuf, _: Arc<Control>, _: bool) -> Result<(), String> {
    Err("当前平台没有Android技能文件选择器".into())
}
#[cfg(not(target_os = "android"))]
pub(crate) fn cancel_signal(_: &Arc<Control>) {}
#[cfg(not(target_os = "android"))]
pub(crate) fn cancel_picker(_: &OperationKey, _: &Arc<Control>) {}

#[cfg(target_os = "android")]
mod android {
    use super::*;
    use crate::platform::android;
    use jni::{JValue, jni_sig, jni_str};
    use std::os::fd::{AsRawFd, FromRawFd};
    pub(crate) type Object = Arc<jni::refs::Global<jni::objects::JObject<'static>>>;
    struct Picker {
        key: OperationKey,
        workspace: PathBuf,
        control: Arc<Control>,
        tree: bool,
        activity: Option<Object>,
        started: bool,
        cancelled: bool,
    }
    fn pickers() -> &'static Mutex<BTreeMap<i32, Picker>> {
        static P: OnceLock<Mutex<BTreeMap<i32, Picker>>> = OnceLock::new();
        P.get_or_init(Default::default)
    }
    pub(crate) fn cancel_signal(control: &Arc<Control>) {
        let signal = control
            .signal
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        let Some(signal) = signal else { return };
        static C: OnceLock<Option<SyncSender<Object>>> = OnceLock::new();
        if let Some(sender) = C.get_or_init(|| {
            let (tx, rx) = mpsc::sync_channel::<Object>(CAPACITY);
            std::thread::Builder::new()
                .name("rust-skill-saf-cancel".into())
                .spawn(move || {
                    while let Ok(signal) = rx.recv() {
                        let _ = android::invoke(|env| {
                            env.call_method(
                                signal.as_ref(),
                                jni_str!("cancel"),
                                jni_sig!("()V"),
                                &[],
                            )?;
                            Ok(())
                        });
                    }
                })
                .ok()?;
            Some(tx)
        }) {
            let _ = sender.try_send(signal);
        }
    }
    fn signal(control: &Arc<Control>) -> Result<Object, String> {
        control.check()?;
        let signal = android::invoke(|env| {
            let value = env.new_object(
                jni_str!("android/os/CancellationSignal"),
                jni_sig!("()V"),
                &[],
            )?;
            Ok(Arc::new(env.new_global_ref(value)?))
        })?;
        *control.signal.lock().unwrap_or_else(|e| e.into_inner()) = Some(signal.clone());
        if control.check().is_err() {
            cancel_signal(control);
            return Err("技能读取已取消".into());
        }
        Ok(signal)
    }
    pub(crate) fn pick(
        key: OperationKey,
        workspace: PathBuf,
        control: Arc<Control>,
        tree: bool,
    ) -> Result<(), String> {
        if !android::activity_visible() {
            return Err("技能导入需要可见的应用页面".into());
        }
        let code = crate::platform::permissions::allocate_request_code()?;
        {
            let mut p = pickers().lock().unwrap_or_else(|e| e.into_inner());
            if !p.is_empty() {
                return Err("已有技能文件选择器尚未返回".into());
            }
            p.insert(
                code,
                Picker {
                    key: key.clone(),
                    workspace,
                    control: control.clone(),
                    tree,
                    activity: None,
                    started: false,
                    cancelled: false,
                },
            );
        }
        let callback_key = key.clone();
        let callback_control = control.clone();
        let posted = android::post_to_main(move || {
            let result =
                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<(), String> {
                    callback_control.check()?;
                    let activity =
                        android::current_activity().ok_or("技能选择需要当前可用的Activity")?;
                    android::invoke(|env| {
                        let action = env.new_string(if tree {
                            "android.intent.action.OPEN_DOCUMENT_TREE"
                        } else {
                            "android.intent.action.OPEN_DOCUMENT"
                        })?;
                        let intent = env.new_object(
                            jni_str!("android/content/Intent"),
                            jni_sig!("(Ljava/lang/String;)V"),
                            &[JValue::Object(&action)],
                        )?;
                        if !tree {
                            let category = env.new_string("android.intent.category.OPENABLE")?;
                            env.call_method(
                                &intent,
                                jni_str!("addCategory"),
                                jni_sig!("(Ljava/lang/String;)Landroid/content/Intent;"),
                                &[JValue::Object(&category)],
                            )?;
                            let mime = env.new_string("*/*")?;
                            env.call_method(
                                &intent,
                                jni_str!("setType"),
                                jni_sig!("(Ljava/lang/String;)Landroid/content/Intent;"),
                                &[JValue::Object(&mime)],
                            )?;
                        }
                        {
                            let mut p = pickers().lock().unwrap_or_else(|e| e.into_inner());
                            let pending = p
                                .get_mut(&code)
                                .ok_or(jni::errors::Error::NullPtr("skill picker retired"))?;
                            if pending.cancelled || pending.control.cancel.is_cancelled() {
                                return Err(jni::errors::Error::NullPtr("skill picker cancelled"));
                            }
                            pending.activity = Some(activity.clone());
                            pending.started = true;
                        }
                        env.call_method(
                            activity.as_ref(),
                            jni_str!("startActivityForResult"),
                            jni_sig!("(Landroid/content/Intent;I)V"),
                            &[JValue::Object(&intent), JValue::Int(code)],
                        )?;
                        Ok(())
                    })
                }))
                .unwrap_or_else(|_| Err("技能选择器主线程回调异常，未自动重试".into()));
            if let Err(error) = result {
                let mut p = pickers().lock().unwrap_or_else(|e| e.into_inner());
                if p.get(&code).is_some_and(|v| !v.started) {
                    p.remove(&code);
                }
                drop(p);
                finish(&callback_key, &callback_control, Err(error));
            }
        });
        if posted.is_err() {
            pickers()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .remove(&code);
        }
        posted
    }
    pub(crate) fn cancel_picker(key: &OperationKey, control: &Arc<Control>) {
        let mut p = pickers().lock().unwrap_or_else(|e| e.into_inner());
        let mut finished = false;
        for picker in p.values_mut() {
            if picker.key == *key && Arc::ptr_eq(&picker.control, control) {
                picker.cancelled = true;
                finished = true;
            }
        }
        drop(p);
        if finished {
            finish(key, control, Ok(State::Cancelled));
        }
    }
    pub(crate) fn on_activity_result(code: i32, result: i32, intent: Option<Object>) -> bool {
        let picker = pickers()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&code);
        let Some(picker) = picker else { return false };
        if picker.cancelled {
            return true;
        }
        if result != -1 {
            finish(&picker.key, &picker.control, Ok(State::Cancelled));
            return true;
        }
        let result = (|| -> Result<(), String> {
            let intent = intent.ok_or("技能选择器没有返回内容")?;
            let uri = android::invoke(|env| {
                let uri = env
                    .call_method(
                        intent.as_ref(),
                        jni_str!("getData"),
                        jni_sig!("()Landroid/net/Uri;"),
                        &[],
                    )?
                    .l()?;
                if uri.is_null() {
                    return Err(jni::errors::Error::NullPtr("skill URI"));
                }
                Ok(Arc::new(env.new_global_ref(uri)?))
            })?;
            picker.control.check()?;
            queue(Work::Preview {
                key: picker.key.clone(),
                workspace: picker.workspace,
                source: Source::Uri {
                    uri,
                    tree: picker.tree,
                },
                control: picker.control.clone(),
            })
        })();
        if let Err(error) = result {
            finish(&picker.key, &picker.control, Err(error));
        }
        true
    }
    pub(crate) fn on_result_error(code: i32, error: &str) -> bool {
        let picker = pickers()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&code);
        let Some(picker) = picker else { return false };
        finish(&picker.key, &picker.control, Err(error.into()));
        true
    }
    pub(crate) fn activity_destroyed(
        env: &mut jni::Env<'_>,
        activity: &jni::objects::JObject<'_>,
    ) -> jni::errors::Result<()> {
        let mut p = pickers().lock().unwrap_or_else(|e| e.into_inner());
        let mut ended = Vec::new();
        for picker in p.values_mut() {
            if let Some(owner) = &picker.activity {
                if env.is_same_object(owner.as_ref(), activity)? {
                    picker.cancelled = true;
                    picker.control.cancel.cancel();
                    ended.push((picker.key.clone(), picker.control.clone()));
                }
            }
        }
        drop(p);
        for (key, control) in ended {
            finish(&key, &control, Ok(State::Cancelled))
        }
        Ok(())
    }
    struct Parcel(Option<Object>);
    impl Drop for Parcel {
        fn drop(&mut self) {
            if let Some(pfd) = self.0.take() {
                let _ = android::invoke(|env| {
                    env.call_method(pfd.as_ref(), jni_str!("close"), jni_sig!("()V"), &[])?;
                    Ok(())
                });
            }
        }
    }
    fn read_file(uri: &Object, control: &Arc<Control>, maximum: u64) -> Result<Vec<u8>, String> {
        let context = android::application_context()?;
        let cancellation = signal(control)?;
        let pfd = android::invoke(|env| {
            let resolver = env
                .call_method(
                    context.as_ref(),
                    jni_str!("getContentResolver"),
                    jni_sig!("()Landroid/content/ContentResolver;"),
                    &[],
                )?
                .l()?;
            let mode = env.new_string("r")?;
            let pfd=env.call_method(&resolver,jni_str!("openFileDescriptor"),jni_sig!("(Landroid/net/Uri;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/os/ParcelFileDescriptor;"),&[JValue::Object(uri.as_ref()),JValue::Object(&mode),JValue::Object(cancellation.as_ref())])?.l()?;
            if pfd.is_null() {
                return Err(jni::errors::Error::NullPtr("skill descriptor"));
            }
            match env.new_global_ref(&pfd) {
                Ok(global) => Ok(Arc::new(global)),
                Err(error) => {
                    if env.exception_check() {
                        env.exception_clear();
                    }
                    let _ = env.call_method(&pfd, jni_str!("close"), jni_sig!("()V"), &[]);
                    if env.exception_check() {
                        env.exception_clear();
                    }
                    Err(error)
                }
            }
        })?;
        let mut parcel = Parcel(Some(pfd.clone()));
        let bytes = (|| {
            let raw = android::invoke(|env| {
                env.call_method(pfd.as_ref(), jni_str!("getFd"), jni_sig!("()I"), &[])?
                    .i()
            })?;
            let raw = unsafe { libc::fcntl(raw, libc::F_DUPFD_CLOEXEC, 0) };
            if raw < 0 {
                return Err("技能源文件描述符复制失败".into());
            }
            let file = unsafe { File::from_raw_fd(raw) };
            let flags = unsafe { libc::fcntl(raw, libc::F_GETFL) };
            if flags < 0 || unsafe { libc::fcntl(raw, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0
            {
                return Err("技能源文件不能非阻塞读取".into());
            }
            let mut bytes = Vec::new();
            let mut buffer = [0u8; 64 * 1024];
            loop {
                control.check()?;
                let n = unsafe {
                    libc::read(file.as_raw_fd(), buffer.as_mut_ptr().cast(), buffer.len())
                };
                if n > 0 {
                    if bytes
                        .len()
                        .checked_add(n as usize)
                        .is_none_or(|n| n as u64 > maximum)
                    {
                        return Err("技能源实际字节超过预算".into());
                    }
                    bytes.extend_from_slice(&buffer[..n as usize]);
                } else if n == 0 {
                    break;
                } else {
                    let error = std::io::Error::last_os_error();
                    if error.kind() == std::io::ErrorKind::Interrupted {
                        continue;
                    }
                    if error.kind() != std::io::ErrorKind::WouldBlock {
                        return Err("技能源文件读取失败".into());
                    }
                    let mut p = libc::pollfd {
                        fd: file.as_raw_fd(),
                        events: libc::POLLIN,
                        revents: 0,
                    };
                    unsafe {
                        libc::poll(&mut p, 1, 40);
                    }
                }
            }
            Ok(bytes)
        })();
        let closed = android::invoke(|env| {
            env.call_method(pfd.as_ref(), jni_str!("close"), jni_sig!("()V"), &[])?;
            Ok(())
        });
        closed?;
        parcel.0 = None;
        *control.signal.lock().unwrap_or_else(|e| e.into_inner()) = None;
        bytes
    }
    struct Row {
        id: String,
        name: String,
        mime: String,
        bytes: Option<u64>,
    }
    fn query(uri: &Object, children: bool, control: &Arc<Control>) -> Result<Vec<Row>, String> {
        control.check()?;
        let context = android::application_context()?;
        let cancellation = signal(control)?;
        let rows = android::invoke(|env| {
            let resolver = env
                .call_method(
                    context.as_ref(),
                    jni_str!("getContentResolver"),
                    jni_sig!("()Landroid/content/ContentResolver;"),
                    &[],
                )?
                .l()?;
            let projection = env.new_object_array(
                4,
                jni_str!("java/lang/String"),
                jni::objects::JObject::null(),
            )?;
            for (index, column) in ["document_id", "_display_name", "mime_type", "_size"]
                .iter()
                .enumerate()
            {
                let value = env.new_string(column)?;
                env.set_object_array_element(&projection, index, &value)?;
            }
            let cursor=env.call_method(&resolver,jni_str!("query"),jni_sig!("(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/database/Cursor;"),&[JValue::Object(uri.as_ref()),JValue::Object(&projection),JValue::Object(&jni::objects::JObject::null()),JValue::Object(&jni::objects::JObject::null()),JValue::Object(&jni::objects::JObject::null()),JValue::Object(cancellation.as_ref())])?.l()?;
            if cursor.is_null() {
                return Err(jni::errors::Error::NullPtr("skill document cursor"));
            }
            let result = (|| -> jni::errors::Result<Vec<Row>> {
                let mut rows = Vec::new();
                while env
                    .call_method(&cursor, jni_str!("moveToNext"), jni_sig!("()Z"), &[])?
                    .z()?
                {
                    if rows.len() >= MAX_NODES || control.check().is_err() {
                        return Err(jni::errors::Error::NullPtr(
                            "skill directory budget or cancellation",
                        ));
                    }
                    let mut values = Vec::new();
                    for index in 0..4 {
                        let value = env
                            .call_method(
                                &cursor,
                                jni_str!("getString"),
                                jni_sig!("(I)Ljava/lang/String;"),
                                &[JValue::Int(index)],
                            )?
                            .l()?;
                        values.push(if value.is_null() {
                            String::new()
                        } else {
                            android::string_value(env, value, 4096)?
                        });
                    }
                    if !children && !rows.is_empty() {
                        return Err(jni::errors::Error::NullPtr(
                            "skill single URI returned multiple rows",
                        ));
                    }
                    rows.push(Row {
                        id: values[0].clone(),
                        name: values[1].clone(),
                        mime: values[2].clone(),
                        bytes: values[3].parse().ok(),
                    });
                }
                Ok(rows)
            })();
            if env.exception_check() {
                env.exception_clear();
            }
            let closed = env.call_method(&cursor, jni_str!("close"), jni_sig!("()V"), &[]);
            if result.is_err() {
                if env.exception_check() {
                    env.exception_clear();
                }
                return result;
            }
            closed?;
            result
        });
        *control.signal.lock().unwrap_or_else(|e| e.into_inner()) = None;
        rows
    }
    fn document_uri(tree: &Object, id: &str, children: bool) -> Result<Object, String> {
        android::invoke(|env| {
            let id = env.new_string(id)?;
            let value = if children {
                env.call_static_method(
                    jni_str!("android/provider/DocumentsContract"),
                    jni_str!("buildChildDocumentsUriUsingTree"),
                    jni_sig!("(Landroid/net/Uri;Ljava/lang/String;)Landroid/net/Uri;"),
                    &[JValue::Object(tree.as_ref()), JValue::Object(&id)],
                )?
                .l()?
            } else {
                env.call_static_method(
                    jni_str!("android/provider/DocumentsContract"),
                    jni_str!("buildDocumentUriUsingTree"),
                    jni_sig!("(Landroid/net/Uri;Ljava/lang/String;)Landroid/net/Uri;"),
                    &[JValue::Object(tree.as_ref()), JValue::Object(&id)],
                )?
                .l()?
            };
            Ok(Arc::new(env.new_global_ref(value)?))
        })
    }
    pub(crate) fn read_uri(
        uri: &Object,
        tree: bool,
        control: &Arc<Control>,
    ) -> Result<Tree, String> {
        android::invoke(|env| {
            let scheme = env
                .call_method(
                    uri.as_ref(),
                    jni_str!("getScheme"),
                    jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?
                .l()?;
            if scheme.is_null() || android::string_value(env, scheme, 32)? != "content" {
                return Err(jni::errors::Error::NullPtr(
                    "skill import requires content URI",
                ));
            }
            Ok(())
        })?;
        if !tree {
            let mut rows = query(uri, false, control)?;
            let row = rows.pop().ok_or("技能文件没有可读取元数据")?;
            let is_zip = row.name.to_ascii_lowercase().ends_with(".zip");
            let bytes = read_file(
                uri,
                control,
                if is_zip {
                    64 * 1024 * 1024
                } else {
                    DOCUMENT_BYTES as u64
                },
            )?;
            if bytes.starts_with(b"PK\x03\x04") {
                return crate::plugin_assets::archive::read_tree(
                    &bytes,
                    &control.cancel,
                    MAX_NODES,
                    TOTAL_BYTES,
                );
            }
            if !row.name.to_ascii_lowercase().ends_with(".md") {
                return Err("技能入口必须为Markdown文件或ZIP技能包".into());
            }
            return one_file(PathBuf::from(row.name), bytes);
        }
        let id = android::invoke(|env| {
            let id = env
                .call_static_method(
                    jni_str!("android/provider/DocumentsContract"),
                    jni_str!("getTreeDocumentId"),
                    jni_sig!("(Landroid/net/Uri;)Ljava/lang/String;"),
                    &[JValue::Object(uri.as_ref())],
                )?
                .l()?;
            android::string_value(env, id, 4096)
        })?;
        let mut result = Tree {
            layout: Vec::new(),
            files: BTreeMap::new(),
            warnings: Vec::new(),
        };
        let mut seen = BTreeSet::new();
        fn visit(
            tree: &Object,
            id: &str,
            prefix: &Path,
            output: &mut Tree,
            seen: &mut BTreeSet<String>,
            control: &Arc<Control>,
        ) -> Result<(), String> {
            if prefix.components().count() > MAX_DEPTH || !seen.insert(id.into()) {
                return Err("技能文件夹深度超限或包含循环文档ID".into());
            }
            let children = document_uri(tree, id, true)?;
            let rows = query(&children, true, control)?;
            for row in rows {
                control.check()?;
                if output.layout.len() >= MAX_NODES {
                    return Err("技能目录超过200项".into());
                }
                let name = Path::new(&row.name);
                files::relative(name)?;
                if name.components().count() != 1 {
                    return Err("技能目录提供者返回非单项文件名".into());
                }
                let path = prefix.join(name);
                files::relative(&path)?;
                if row.mime == "vnd.android.document/directory" {
                    output.layout.push(Layout {
                        path: path.clone(),
                        directory: true,
                        mode: 0o700,
                        bytes: 0,
                        sha256: None,
                    });
                    visit(tree, &row.id, &path, output, seen, control)?;
                } else {
                    let total: u64 = output.layout.iter().map(|n| n.bytes).sum();
                    if row.bytes.is_some_and(|n| {
                        n > DOCUMENT_BYTES as u64
                            || total.checked_add(n).is_none_or(|n| n > TOTAL_BYTES)
                    }) {
                        return Err("技能目录文件超过预算".into());
                    }
                    let child = document_uri(tree, &row.id, false)?;
                    let bytes = read_file(&child, control, DOCUMENT_BYTES as u64)?;
                    if total
                        .checked_add(bytes.len() as u64)
                        .is_none_or(|n| n > TOTAL_BYTES)
                    {
                        return Err("技能目录总量超过64MiB".into());
                    }
                    output.layout.push(Layout {
                        path: path.clone(),
                        directory: false,
                        mode: 0o600,
                        bytes: bytes.len() as u64,
                        sha256: Some(files::hash(&bytes)),
                    });
                    if output.files.insert(path, bytes).is_some() {
                        return Err("技能目录包含重名文件".into());
                    }
                }
            }
            Ok(())
        }
        visit(uri, &id, Path::new(""), &mut result, &mut seen, control)?;
        // A selected single-skill root needs its provider name as the skill ID.
        if result.files.keys().any(|p| {
            p.components().count() == 1
                && p.file_name()
                    .and_then(|s| s.to_str())
                    .is_some_and(|n| n.eq_ignore_ascii_case("SKILL.md"))
        }) {
            let document = document_uri(uri, &id, false)?;
            let row = query(&document, false, control)?
                .pop()
                .ok_or("技能根目录元数据缺失")?;
            let prefix = crate::skills::sanitize_import_id(&row.name);
            result = result.under(Path::new(&prefix))?;
        }
        Ok(result)
    }
}
#[cfg(target_os = "android")]
pub(crate) use android::*;
