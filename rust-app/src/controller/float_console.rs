//! The actor alone projects live transcripts and reconstructs a resize save
//! from the current Settings plus the exact version shown at pointer-down.
use super::*;
#[derive(Clone, PartialEq, Eq)]
enum PreviewKey {
    Live(String, u64),
    Idle(String, crate::display::SourceStamp),
    Empty,
}
struct TailPending {
    key: PreviewKey,
    cancel: Arc<crate::network::CancelToken>,
    result: Receiver<Result<String, String>>,
}
#[derive(Default)]
pub(super) struct Runtime {
    host: Option<Arc<crate::runtime_host::RuntimeHost>>,
    last_preview: Option<PreviewKey>,
    preview: Arc<String>,
    tail: Option<TailPending>,
    tail_attempted: Option<PreviewKey>,
}
impl Runtime {
    pub(super) fn tick(&mut self, data: &PathBuf, store: &Store, runs: &Runs, publish: &Publisher) {
        if !cfg!(target_os = "android") {
            return;
        }
        if self.host.is_none() {
            match boot(data.clone()) {
                Ok(host) => {
                    if let Err(error) = crate::platform::float_console::start(host.clone()) {
                        publish.emit(Event::Error(error));
                        return;
                    }
                    self.host = Some(host);
                }
                Err(error) => {
                    publish.emit(Event::Error(error));
                    return;
                }
            }
        }
        let mut ordered = runs.by_id.values().collect::<Vec<_>>();
        ordered.sort_by(|a, b| {
            b.started_at
                .cmp(&a.started_at)
                .then_with(|| a.id.cmp(&b.id))
        });
        let manual = crate::platform::float_console::selection();
        let selected = match manual.as_ref() {
            Some(Some(key)) => ordered
                .iter()
                .copied()
                .find(|run| run.id == key.run_id && run.conversation == key.conversation_id),
            Some(None) => None,
            None => ordered.first().copied(),
        };
        let conversation = selected.map(|run| run.conversation.clone()).or_else(|| {
            if matches!(manual, Some(None)) {
                return None;
            }
            let remembered = match &manual {
                Some(Some(key)) => Some(key.conversation_id.clone()),
                _ => crate::platform::float_console::conversation(),
            };
            remembered
                .filter(|id| store.conversation(id).is_ok())
                .or_else(|| {
                    store
                        .latest_console_conversation()
                        .map(|conversation| conversation.id.clone())
                })
        });
        let preview_key = if let Some(run) = selected {
            PreviewKey::Live(run.id.clone(), run.display_revision)
        } else if store.settings.behavior.floating_console {
            conversation
                .as_ref()
                .map(|id| {
                    PreviewKey::Idle(
                        id.clone(),
                        store.history_stamp_for(&crate::display::ViewSource::Main {
                            conversation_id: id.clone(),
                        }),
                    )
                })
                .unwrap_or(PreviewKey::Empty)
        } else {
            PreviewKey::Empty
        };
        if self.last_preview.as_ref() != Some(&preview_key) {
            if let Some(tail) = &self.tail {
                tail.cancel.cancel();
            }
            self.tail_attempted = None;
            self.last_preview = Some(preview_key.clone());
            self.preview = Arc::new(selected.map_or_else(String::new, |run| {
                let mut recent = run
                    .messages
                    .iter()
                    .rev()
                    .filter(|message| !message.text.trim().is_empty())
                    .take(6)
                    .collect::<Vec<_>>();
                recent.reverse();
                let mut text = String::new();
                for message in recent {
                    let body = if crate::transcript_redaction::sensitive_result(
                        message.metadata.tool_name.as_deref().unwrap_or(""),
                    ) {
                        crate::transcript_redaction::PLACEHOLDER
                    } else {
                        message.text.as_str()
                    };
                    text.push_str(match message.role.as_str() {
                        "user" => "你：",
                        "tool" => "工具：",
                        _ => "JAgent：",
                    });
                    for c in body.trim().chars().take(1024) {
                        text.push(if c == '\n' || c == '\r' { ' ' } else { c });
                    }
                    if body.chars().take(1025).count() > 1024 {
                        text.push('…');
                    }
                    text.push_str("\n\n");
                }
                text
            }));
        }
        if let Some(tail) = &self.tail {
            match tail.result.try_recv() {
                Ok(result) => {
                    if tail.key == preview_key && !tail.cancel.is_cancelled() {
                        match result {
                            Ok(text) => self.preview = Arc::new(text),
                            Err(error) => {
                                self.preview =
                                    Arc::new("会话预览暂不可读，可打开会话查看。".into());
                                publish.emit(Event::Error(error));
                            }
                        }
                    }
                    self.tail = None;
                }
                Err(TryRecvError::Disconnected) => {
                    if tail.key == preview_key {
                        self.preview = Arc::new("会话预览读取未完成，可打开会话查看。".into());
                    }
                    self.tail = None;
                }
                Err(TryRecvError::Empty) => (),
            }
        }
        // Cancel obsolete work but keep its receiver until it actually settles;
        // fast conversation changes cannot multiply background reader threads.
        if self.tail.is_none() && self.tail_attempted.as_ref() != Some(&preview_key) {
            if let PreviewKey::Idle(id, _) = &preview_key {
                self.tail_attempted = Some(preview_key.clone());
                match store.console_tail_request(id) {
                    Ok(request) => {
                        let (sender, result) = mpsc::sync_channel(1);
                        let cancel = Arc::new(crate::network::CancelToken::default());
                        let worker_cancel = cancel.clone();
                        let spawned = thread::Builder::new()
                            .name("rust-float-history-tail".into())
                            .spawn(move || {
                                let value =
                                    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                                        request.read(|| worker_cancel.is_cancelled())
                                    }))
                                    .map_err(|_| "悬浮历史读取异常".to_owned())
                                    .and_then(|result| result.map_err(|error| error.to_string()));
                                let _ = sender.try_send(value);
                            });
                        match spawned {
                            Ok(_) => {
                                self.tail = Some(TailPending {
                                    key: preview_key.clone(),
                                    cancel,
                                    result,
                                })
                            }
                            Err(error) => {
                                publish.emit(Event::Error(format!("悬浮历史线程不可用：{error}")))
                            }
                        }
                    }
                    Err(error) => publish.emit(Event::Error(error.to_string())),
                }
            }
        }
        let snapshot = crate::platform::float_console::Snapshot {
            stamp: store.settings_stamp(),
            resident: store.settings.behavior.floating_console,
            while_running: store.settings.behavior.console_while_running,
            width_dp: store.settings.behavior.console_width_dp,
            height_dp: store.settings.behavior.console_height_dp,
            theme: store.settings.behavior.theme_mode,
            font_scale: store.settings.behavior.font_scale,
            immediately: store.settings.steer_immediately,
            runs: ordered
                .iter()
                .take(128)
                .map(|run| crate::platform::float_console::Run {
                    key: RunKey {
                        run_id: run.id.clone(),
                        conversation_id: run.conversation.clone(),
                    },
                    title: store
                        .conversation(&run.conversation)
                        .map(|conversation| conversation.title.clone())
                        .unwrap_or_else(|_| "会话".into()),
                    state: if run.cancel.is_cancelled() {
                        "正在停止".into()
                    } else if run.approval.is_some() {
                        "等待审批".into()
                    } else {
                        crate::tool_execution::live_line(&run.id).unwrap_or_else(|| "运行中".into())
                    },
                    revision: run.display_revision,
                })
                .collect(),
            selected: selected.map(|run| RunKey {
                run_id: run.id.clone(),
                conversation_id: run.conversation.clone(),
            }),
            title: conversation
                .as_deref()
                .and_then(|id| store.conversation(id).ok())
                .map(|conversation| conversation.title.clone())
                .unwrap_or_else(|| "新任务".into()),
            conversation,
            preview: self.preview.clone(),
        };
        if let Err(error) = crate::platform::float_console::configure(snapshot) {
            publish.emit(Event::Error(error));
        }
        for resize in crate::platform::float_console::take_resizes() {
            if resize.expected != store.settings_stamp() {
                publish.emit(Event::SettingsMutationFinished {
                    request_id: resize.request_id,
                    expected: resize.expected,
                    result: Err("拖动期间设置版本已变，悬浮尺寸未覆盖新设置".into()),
                });
                continue;
            }
            let mut settings = store.settings.clone();
            settings.behavior.console_width_dp = resize.width_dp;
            settings.behavior.console_height_dp = resize.height_dp;
            if let Some(host) = &self.host {
                if let Err(error) = host.submit(Action::SaveSettingsFor {
                    request_id: resize.request_id.clone(),
                    expected: resize.expected.clone(),
                    settings,
                }) {
                    publish.emit(Event::SettingsMutationFinished {
                        request_id: resize.request_id,
                        expected: resize.expected,
                        result: Err(error),
                    });
                }
            }
        }
        for error in crate::platform::float_console::take_errors() {
            publish.emit(Event::Error(error));
        }
    }
}
