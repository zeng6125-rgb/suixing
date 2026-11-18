//! Original onboarding uses a local connection draft. Picking a new endpoint
//! never edits the previous provider; completion merges by endpoint once.
//! Probe/list jobs are explicit button actions and never persist credentials.
use crate::{
    configuration::Provider,
    network::{self, CancelToken},
    platform::secrets::SettingsSecretTarget,
    state::{Protocol, Settings},
};
use eframe::egui;
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    sync::{
        Arc,
        mpsc::{self, Receiver, TryRecvError},
    },
    time::Duration,
};

struct Preset {
    id: &'static str,
    name: &'static str,
    endpoint: &'static str,
    model: &'static str,
    protocol: Protocol,
    hint: &'static str,
}
// Values are ported from OnboardingPresets.kt. Availability is learned from the
// user's explicit model-list/probe request, never asserted from this catalog.
const PRESETS: &[Preset] = &[
    Preset {
        id: "deepseek",
        name: "DeepSeek",
        endpoint: "https://api.deepseek.com/v1",
        model: "deepseek-flash",
        protocol: Protocol::OpenAi,
        hint: "在platform.deepseek.com创建API Key",
    },
    Preset {
        id: "zhipu",
        name: "智谱 GLM",
        endpoint: "https://open.bigmodel.cn/api/paas/v4",
        model: "glm-5.3",
        protocol: Protocol::OpenAi,
        hint: "在bigmodel.cn创建API Key",
    },
    Preset {
        id: "openai-compatible",
        name: "OpenAI兼容（自填）",
        endpoint: "",
        model: "",
        protocol: Protocol::OpenAi,
        hint: "填写你的服务地址、模型和Key",
    },
    Preset {
        id: "ollama",
        name: "本机 Ollama",
        endpoint: "http://localhost:11434/v1",
        model: "qwen3",
        protocol: Protocol::OpenAi,
        hint: "本机可填写ollama；局域网服务请改为实际电脑地址",
    },
    Preset {
        id: "moonshot",
        name: "月之暗面 Kimi",
        endpoint: "https://api.moonshot.cn/v1",
        model: "kimi-k3",
        protocol: Protocol::OpenAi,
        hint: "在platform.moonshot.cn创建API Key",
    },
    Preset {
        id: "dashscope",
        name: "阿里百炼 · 千问",
        endpoint: "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model: "qwen-plus",
        protocol: Protocol::OpenAi,
        hint: "在百炼控制台创建API Key",
    },
    Preset {
        id: "siliconflow",
        name: "硅基流动",
        endpoint: "https://api.siliconflow.cn/v1",
        model: "Pro/deepseek-ai/DeepSeek-R1",
        protocol: Protocol::OpenAi,
        hint: "在cloud.siliconflow.cn创建API Key",
    },
    Preset {
        id: "anthropic",
        name: "Claude",
        endpoint: "https://api.anthropic.com/v1",
        model: "claude-sonnet-5-5",
        protocol: Protocol::Anthropic,
        hint: "在platform.claude.com创建API Key；可获取模型列表核对",
    },
];
#[derive(Clone)]
struct Draft {
    endpoint: String,
    key: String,
    model: String,
    protocol: Protocol,
    reasoning: String,
    name: String,
    preset: String,
    contexts: BTreeMap<String, i32>,
    source: String,
    key_edited: bool,
    touched: bool,
}
enum Kind {
    Probe,
    Models,
}
enum Value {
    Probe(String),
    Models(Vec<suixing_search::rust_protocol::Model>),
}
struct Requested {
    kind: Kind,
    fingerprint: String,
    settings: Settings,
}
struct Pending {
    fingerprint: String,
    cancel: Arc<CancelToken>,
    result: Receiver<Result<Value, String>>,
}
impl Drop for Pending {
    fn drop(&mut self) {
        self.cancel.cancel();
    }
}
#[derive(Default)]
pub(super) struct View {
    draft: Option<Draft>,
    requested: Option<Requested>,
    pending: Option<Pending>,
    models: Vec<String>,
    reported: BTreeMap<String, i32>,
    reveal: bool,
    cleared_key: bool,
    notice: Option<String>,
}
fn source_fingerprint(settings: &Settings) -> Result<String, String> {
    let route = settings.provider_connection(settings.active_provider_id.as_deref())?;
    let bytes = serde_json::to_vec(&(
        &settings.active_provider_id,
        route.endpoint,
        route.api_key,
        route.protocol,
        route.model,
        route.reasoning_effort,
    ))
    .map_err(|_| "引导连接身份无法编码".to_owned())?;
    Ok(format!("{:x}", Sha256::digest(bytes)))
}
impl View {
    fn seed(&mut self, settings: &Settings) {
        if self.draft.as_ref().is_some_and(|draft| draft.touched) {
            return;
        }
        let source = match source_fingerprint(settings) {
            Ok(source) => source,
            Err(error) => {
                self.notice = Some(error);
                return;
            }
        };
        if self
            .draft
            .as_ref()
            .is_some_and(|draft| draft.source == source)
        {
            return;
        }
        let connection = match settings.provider_connection(settings.active_provider_id.as_deref())
        {
            Ok(value) => value,
            Err(error) => {
                self.notice = Some(error);
                return;
            }
        };
        let preset = PRESETS.iter().find(|preset| {
            !preset.endpoint.is_empty()
                && network::same_connection_endpoint(preset.endpoint, connection.endpoint)
        });
        let provider = settings
            .active_provider_id
            .as_deref()
            .and_then(|id| settings.providers.iter().find(|provider| provider.id == id));
        self.models = provider
            .map(|provider| provider.models.clone())
            .unwrap_or_else(|| settings.models.clone());
        self.draft = Some(Draft {
            endpoint: connection.endpoint.into(),
            key: connection.api_key.into(),
            model: connection.model.into(),
            protocol: connection.protocol,
            reasoning: connection.reasoning_effort.into(),
            name: provider
                .map(|provider| provider.name.clone())
                .unwrap_or_default(),
            preset: preset.map(|preset| preset.id.into()).unwrap_or_default(),
            contexts: BTreeMap::new(),
            source,
            key_edited: false,
            touched: false,
        });
    }
    pub(super) fn reset(&mut self) {
        self.disconnected();
        self.draft = None;
        self.models.clear();
        self.reported.clear();
        self.reveal = false;
        self.cleared_key = false;
        self.notice = None;
    }
    pub(super) fn disconnected(&mut self) {
        self.requested = None;
        if let Some(pending) = &self.pending {
            pending.cancel.cancel();
        }
    }
    fn prepare(&self, settings: &Settings) -> Result<(Settings, String), String> {
        let draft = self.draft.as_ref().ok_or("引导草稿尚未就绪")?;
        let mut resolved = settings.provider_settings(settings.active_provider_id.as_deref())?;
        // A changed draft can still be tested explicitly. Only the original route
        // can use its persisted request headers.
        if !network::same_connection_endpoint(&resolved.endpoint, &draft.endpoint)
            || resolved.protocol != draft.protocol
            || resolved.api_key != draft.key
            || draft.key_edited && draft.key.is_empty()
        {
            resolved.request_headers.clear();
        }
        resolved.endpoint = draft.endpoint.clone();
        resolved.api_key = draft.key.clone();
        resolved.model = draft.model.clone();
        resolved.protocol = draft.protocol;
        resolved.reasoning_effort = draft.reasoning.clone();
        resolved.active_provider_id = None;
        resolved.behavior.retry_on_failure = false;
        let encoded = serde_json::to_vec(&(
            &resolved.endpoint,
            &resolved.api_key,
            &resolved.model,
            resolved.protocol,
            &resolved.reasoning_effort,
            resolved.temperature,
            resolved.top_p,
            resolved.max_tokens,
            &resolved.request_headers,
        ))
        .map_err(|_| "引导请求身份无法编码".to_owned())?;
        let fingerprint = format!("{:x}", Sha256::digest(&encoded));
        Ok((resolved, fingerprint))
    }
    fn request(&mut self, settings: &Settings, kind: Kind) {
        match self.prepare(settings) {
            Ok((settings, fingerprint)) => {
                self.notice = None;
                self.requested = Some(Requested {
                    kind,
                    fingerprint,
                    settings,
                });
            }
            Err(error) => self.notice = Some(error),
        }
    }
    pub(super) fn tick(&mut self, visible: bool, settings: &Settings, ctx: &egui::Context) {
        if !visible {
            self.disconnected();
        }
        let actual = if visible && (self.pending.is_some() || self.requested.is_some()) {
            self.prepare(settings)
                .ok()
                .map(|(_, fingerprint)| fingerprint)
        } else {
            None
        };
        if let Some(pending) = &self.pending {
            if actual.as_ref() != Some(&pending.fingerprint) {
                pending.cancel.cancel();
            }
            match pending.result.try_recv() {
                Ok(result) => {
                    if visible
                        && !pending.cancel.is_cancelled()
                        && actual.as_ref() == Some(&pending.fingerprint)
                    {
                        match result {
                            Ok(Value::Probe(text)) => self.notice = Some(text),
                            Ok(Value::Models(models)) => {
                                self.reported = models
                                    .iter()
                                    .filter_map(|model| {
                                        model
                                            .context_length
                                            .map(|context| (model.id.clone(), context))
                                    })
                                    .collect();
                                self.models = models.into_iter().map(|model| model.id).collect();
                                if let Some(draft) = &mut self.draft {
                                    if !draft.model.is_empty()
                                        && !self.models.contains(&draft.model)
                                        && self.models.len() < 1024
                                    {
                                        self.models.insert(0, draft.model.clone());
                                    }
                                }
                                self.notice =
                                    Some("已获取模型列表；选择后随引导完成一起保存".into());
                            }
                            Err(error) => self.notice = Some(error),
                        }
                    }
                    self.pending = None;
                    ctx.request_repaint();
                }
                Err(TryRecvError::Disconnected) => {
                    self.pending = None;
                    self.notice = Some("引导请求未完成，可重新尝试".into());
                }
                Err(TryRecvError::Empty) => ctx.request_repaint_after(Duration::from_millis(50)),
            }
        }
        if visible && self.pending.is_none() {
            if let Some(request) = self.requested.take() {
                if actual.as_ref() != Some(&request.fingerprint) {
                    self.notice = Some("输入已变化，旧草稿请求没有发送".into());
                    return;
                }
                let (sender, result) = mpsc::sync_channel(1);
                let cancel = Arc::new(CancelToken::default());
                let worker_cancel = cancel.clone();
                let fingerprint = request.fingerprint;
                let spawned = std::thread::Builder::new()
                    .name("rust-onboarding-request".into())
                    .spawn(move || {
                        let value = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                            let runtime = tokio::runtime::Builder::new_current_thread()
                                .enable_all()
                                .build()
                                .map_err(|_| "引导请求运行时不可用".to_owned())?;
                            runtime.block_on(async {
                                match request.kind {
                                    Kind::Models => {
                                        network::model_details(request.settings, worker_cancel)
                                            .await
                                            .map(Value::Models)
                                    }
                                    Kind::Probe => {
                                        use suixing_search::rust_protocol::Message;
                                        let mut secrets = request
                                            .settings
                                            .request_headers
                                            .values()
                                            .cloned()
                                            .collect::<Vec<_>>();
                                        secrets.push(request.settings.api_key.clone());
                                        let output_cancel = worker_cancel.clone();
                                        let mut output = 0usize;
                                        let probe = network::chat(
                                            request.settings,
                                            vec![Message::user("你好，请只回复两个字：可用")],
                                            &[],
                                            worker_cancel,
                                            |_, text| {
                                                output = output.saturating_add(text.len());
                                                if output > 32 * 1024 {
                                                    output_cancel.cancel();
                                                }
                                            },
                                        );
                                        let outcome =
                                            tokio::time::timeout(Duration::from_secs(30), probe)
                                                .await
                                                .map_err(|_| {
                                                    "测试超过30秒，连接已结束".to_owned()
                                                })?;
                                        if output > 32 * 1024 {
                                            return Err("测试回复超过32KiB，连接已结束".into());
                                        }
                                        if let Some(error) = outcome.error {
                                            return Err(error);
                                        }
                                        let mut text = outcome.turn.text;
                                        secrets.sort_unstable_by_key(|value| {
                                            std::cmp::Reverse(value.len())
                                        });
                                        for secret in secrets {
                                            if !secret.is_empty() {
                                                text = text.replace(&secret, "[已隐藏]");
                                            }
                                        }
                                        Ok(Value::Probe(format!(
                                            "连上了，模型回了：{}",
                                            text.trim().chars().take(40).collect::<String>()
                                        )))
                                    }
                                }
                            })
                        }))
                        .unwrap_or_else(|_| Err("引导请求异常".into()));
                        let _ = sender.try_send(value);
                    });
                match spawned {
                    Ok(_) => {
                        self.pending = Some(Pending {
                            fingerprint,
                            cancel,
                            result,
                        })
                    }
                    Err(_) => self.notice = Some("引导请求线程不可用".into()),
                }
            }
        }
    }
    pub(super) fn provider_page(&mut self, ui: &mut egui::Ui, settings: &Settings) {
        self.seed(settings);
        ui.heading("挑一家模型服务");
        ui.label("挑一家、填写地址与Key。可以跳过，已填写的选择会随完成引导保存。");
        let Some(draft) = &mut self.draft else {
            return;
        };
        egui::ComboBox::from_id_salt("onboarding_preset")
            .selected_text(
                PRESETS
                    .iter()
                    .find(|preset| preset.id == draft.preset)
                    .map(|preset| preset.name)
                    .unwrap_or("自己的连接"),
            )
            .show_ui(ui, |ui| {
                for preset in PRESETS {
                    if ui
                        .selectable_label(draft.preset == preset.id, preset.name)
                        .clicked()
                    {
                        let changed = !preset.endpoint.is_empty()
                            && (!network::same_connection_endpoint(
                                preset.endpoint,
                                &draft.endpoint,
                            ) || draft.protocol != preset.protocol);
                        let first = draft.key.is_empty() && !draft.touched;
                        draft.preset = preset.id.into();
                        draft.name = preset.name.into();
                        if !preset.endpoint.is_empty() {
                            draft.endpoint = preset.endpoint.into();
                            draft.protocol = preset.protocol;
                        }
                        if changed {
                            draft.key.clear();
                            draft.key_edited = true;
                            draft.contexts.clear();
                            self.cleared_key = true;
                            self.models.clear();
                            self.reported.clear();
                        }
                        if changed || first {
                            draft.model = preset.model.into();
                        }
                        draft.touched = true;
                        self.notice = None;
                    }
                }
            });
        ui.label("接口地址");
        let previous = draft.endpoint.clone();
        if ui
            .add(
                egui::TextEdit::singleline(&mut draft.endpoint)
                    .desired_width(f32::INFINITY)
                    .char_limit(4096),
            )
            .changed()
        {
            if !network::same_connection_endpoint(&previous, &draft.endpoint) {
                draft.key.clear();
                draft.key_edited = true;
                draft.contexts.clear();
                self.cleared_key = true;
                self.models.clear();
                self.reported.clear();
                if !PRESETS.iter().any(|preset| {
                    preset.id == draft.preset
                        && network::same_connection_endpoint(preset.endpoint, &draft.endpoint)
                }) {
                    draft.preset.clear();
                    draft.name.clear();
                }
            }
            draft.touched = true;
            self.notice = None;
        }
        ui.label("API Key");
        if self.cleared_key {
            ui.label("连接已切换，草稿中的旧 Key 已清除。之前保存的其他供应商保持原样。");
        }
        if ui
            .add(
                egui::TextEdit::singleline(&mut draft.key)
                    .password(!self.reveal)
                    .desired_width(f32::INFINITY)
                    .char_limit(16 * 1024),
            )
            .changed()
        {
            draft.key_edited = true;
            draft.touched = true;
            self.cleared_key = false;
            self.notice = None;
        }
        ui.checkbox(&mut self.reveal, "显示Key");
        if let Some(preset) = PRESETS.iter().find(|preset| preset.id == draft.preset) {
            ui.label(preset.hint);
        }
        egui::ComboBox::from_id_salt("onboarding_protocol")
            .selected_text(draft.protocol.label())
            .show_ui(ui, |ui| {
                for protocol in Protocol::ALL {
                    if ui
                        .selectable_value(&mut draft.protocol, protocol, protocol.label())
                        .changed()
                    {
                        draft.key.clear();
                        draft.key_edited = true;
                        draft.touched = true;
                        draft.contexts.clear();
                        self.cleared_key = true;
                        self.models.clear();
                        self.reported.clear();
                        self.notice = None;
                    }
                }
            });
        ui.label("测试连接会真的发出一次模型请求，只发送测试语句，不带聊天历史或工具。");
        let enabled = !draft.endpoint.trim().is_empty()
            && !draft.model.trim().is_empty()
            && self.pending.is_none()
            && self.requested.is_none();
        if ui
            .add_enabled(enabled, egui::Button::new("测试草稿连接"))
            .clicked()
        {
            self.request(settings, Kind::Probe);
        }
        self.status(ui);
    }
    pub(super) fn model_page(&mut self, ui: &mut egui::Ui, settings: &Settings) {
        self.seed(settings);
        let Some(draft) = &mut self.draft else {
            return;
        };
        ui.heading("选一个模型");
        if ui
            .add(
                egui::TextEdit::singleline(&mut draft.model)
                    .desired_width(f32::INFINITY)
                    .hint_text("模型ID")
                    .char_limit(1024),
            )
            .changed()
        {
            draft.touched = true;
        }
        if !self.models.is_empty() {
            egui::ComboBox::from_id_salt("onboarding_model")
                .selected_text(&draft.model)
                .show_ui(ui, |ui| {
                    for model in &self.models {
                        if ui
                            .selectable_value(&mut draft.model, model.clone(), model)
                            .changed()
                        {
                            draft.touched = true;
                            if !settings.model_contexts.contains_key(model) {
                                if let Some(context) = self.reported.get(model) {
                                    draft.contexts.insert(model.clone(), *context);
                                }
                            }
                        }
                    }
                });
        }
        egui::ComboBox::from_id_salt("onboarding_reasoning")
            .selected_text(if draft.reasoning.is_empty() {
                "服务默认"
            } else {
                &draft.reasoning
            })
            .show_ui(ui, |ui| {
                for level in ["", "none", "minimal", "low", "medium", "high", "xhigh"] {
                    if ui
                        .selectable_value(
                            &mut draft.reasoning,
                            level.into(),
                            if level.is_empty() {
                                "服务默认"
                            } else {
                                level
                            },
                        )
                        .changed()
                    {
                        draft.touched = true;
                    }
                }
            });
        let enabled =
            !draft.endpoint.trim().is_empty() && self.pending.is_none() && self.requested.is_none();
        if ui
            .add_enabled(enabled, egui::Button::new("获取草稿连接模型列表"))
            .clicked()
        {
            self.request(settings, Kind::Models);
        }
        self.status(ui);
    }
    fn status(&mut self, ui: &mut egui::Ui) {
        if let Some(pending) = &self.pending {
            ui.spinner();
            if ui.button("取消请求").clicked() {
                pending.cancel.cancel();
            }
        }
        if let Some(notice) = &self.notice {
            ui.add(egui::Label::new(notice).wrap());
        }
        if self.requested.is_some() {
            ui.ctx().request_repaint();
        }
    }
    pub(super) fn summary(&mut self, ui: &mut egui::Ui) {
        if let Some(draft) = &self.draft {
            let endpoint = reqwest::Url::parse(draft.endpoint.trim())
                .ok()
                .filter(|url| {
                    matches!(url.scheme(), "http" | "https")
                        && url.username().is_empty()
                        && url.password().is_none()
                })
                .map(|mut url| {
                    url.set_query(None);
                    url.set_fragment(None);
                    url.to_string()
                });
            ui.label(format!(
                "接口：{}",
                endpoint.as_deref().unwrap_or("未填写有效地址")
            ));
            ui.label(format!(
                "模型：{}",
                if draft.model.trim().is_empty() {
                    "未填写"
                } else {
                    &draft.model
                }
            ));
            ui.label(if draft.key.is_empty() {
                "Key 未填写"
            } else {
                "Key 已填写"
            });
        }
    }
    pub(super) fn finish(
        &mut self,
        latest: &Settings,
    ) -> Result<(Settings, Vec<SettingsSecretTarget>), String> {
        self.disconnected();
        let mut settings = latest.clone();
        settings.behavior.onboarding_seen = true;
        let Some(draft) = &self.draft else {
            return Ok((settings, Vec::new()));
        };
        if !draft.touched {
            return Ok((settings, Vec::new()));
        }
        if source_fingerprint(latest)? != draft.source {
            return Err("当前连接已由其他操作更新。可以重新读取引导草稿再修改。".into());
        }
        settings.reasoning_effort = draft.reasoning.clone();
        for (model, context) in &draft.contexts {
            settings
                .model_contexts
                .entry(model.clone())
                .or_insert(*context);
        }
        if draft.endpoint.trim().is_empty() {
            return Ok((settings, Vec::new()));
        }
        settings.seed_legacy_provider();
        let existing = settings
            .providers
            .iter()
            .find(|provider| {
                settings.active_provider_id.as_deref() == Some(provider.id.as_str())
                    && network::same_connection_endpoint(&provider.endpoint, &draft.endpoint)
            })
            .or_else(|| {
                settings.providers.iter().find(|provider| {
                    network::same_connection_endpoint(&provider.endpoint, &draft.endpoint)
                })
            });
        let id = if let Some(provider) = existing {
            provider.id.clone()
        } else {
            if settings.providers.len() >= 128 {
                return Err("供应商数量超过128个，引导草稿保持不变".into());
            }
            let base = format!(
                "onb-{}",
                if draft.preset.is_empty() {
                    "custom"
                } else {
                    &draft.preset
                }
            );
            let mut id = base.clone();
            let mut n = 2;
            while settings.providers.iter().any(|provider| provider.id == id) {
                id = format!("{base}-{n}");
                n += 1;
            }
            settings.providers.push(Provider {
                id: id.clone(),
                ..Default::default()
            });
            id
        };
        let provider = settings
            .providers
            .iter_mut()
            .find(|provider| provider.id == id)
            .ok_or("引导供应商归属不存在")?;
        if !draft.name.trim().is_empty() {
            provider.name = draft.name.clone();
        }
        provider.endpoint = draft.endpoint.clone();
        provider.api_key = draft.key.clone();
        provider.protocol = draft.protocol;
        provider.selected_model = draft.model.clone();
        if !draft.model.trim().is_empty() {
            let old = provider.models.clone();
            if !provider.models.contains(&draft.model) {
                provider.models.push(draft.model.clone());
            }
            let enabled = provider.enabled_models.get_or_insert(old);
            if !enabled.contains(&draft.model) {
                enabled.push(draft.model.clone());
            }
        }
        if !network::same_connection_endpoint(&settings.endpoint, &draft.endpoint) {
            settings.request_headers.clear();
            settings.models.clear();
            settings.enabled_models = Some(Vec::new());
        }
        settings.endpoint = draft.endpoint.clone();
        settings.api_key = draft.key.clone();
        settings.model = draft.model.clone();
        settings.protocol = draft.protocol;
        settings.active_provider_id = Some(id.clone());
        settings.behavior.providers_initialized = true;
        if !draft.model.is_empty() {
            let old = settings.models.clone();
            if !settings.models.contains(&draft.model) {
                settings.models.push(draft.model.clone());
            }
            let enabled = settings.enabled_models.get_or_insert(old);
            if !enabled.contains(&draft.model) {
                enabled.push(draft.model.clone());
            }
        }
        let mut clear = Vec::new();
        if draft.key.is_empty() {
            if draft.key_edited
                || !network::same_connection_endpoint(&latest.endpoint, &draft.endpoint)
                || latest.protocol != draft.protocol
                || !latest.api_key.is_empty()
            {
                clear.push(SettingsSecretTarget::ApiKey);
            }
            if draft.key_edited {
                clear.push(SettingsSecretTarget::ProviderApiKey(id));
            }
        }
        Ok((settings, clear))
    }
}
