use super::*;
use crate::state::Protocol;
use serde_json::{Map, Value, json};

const FILE: &str = "datastore/Suixing_settings.preferences_pb";
const LIMIT: u64 = 2 * MAX_SETTINGS_BYTES;

fn bytes(files: &Path) -> Result<Vec<u8>, String> {
    let path = files.join(FILE);
    match checked_file(files, &path) {
        Ok(file) => {
            let mut data = Vec::new();
            file.take(LIMIT + 1)
                .read_to_end(&mut data)
                .map_err(|error| fs_error("读取共用设置", &path, error))?;
            if data.len() as u64 > LIMIT {
                return Err("共用设置超出存储预算".into());
            }
            Ok(data)
        }
        Err(_) if !path.exists() => Ok(Vec::new()),
        Err(error) => Err(error),
    }
}

pub(super) fn read(files: &Path) -> Result<(Option<String>, Option<Value>), String> {
    let (compose, rust) = crate::legacy_import::settings_preferences(&bytes(files)?)?;
    let rust = rust
        .map(|text| {
            serde_json::from_str(&text).map_err(|_| "共用设置中的 Rust 配置无法读取".to_owned())
        })
        .transpose()?;
    Ok((compose, rust))
}

pub(super) fn apply(
    raw: &str,
    settings: &mut Settings,
    secrets: &mut StoredSettingsSecrets,
) -> Result<(), String> {
    let value = serde_json::from_str(raw).map_err(|_| "无法读取共用设置")?;
    let mut imported =
        crate::legacy_migration::decode_shared_settings(&value, &SecretBox, &mut Vec::new())?;
    imported.settings.models.clone_from(&settings.models);
    imported
        .settings
        .enabled_models
        .clone_from(&settings.enabled_models);
    imported.settings.dark_mode = settings.dark_mode;
    *settings = imported.settings;
    *secrets = imported.secrets;
    for provider in &mut settings.providers {
        let enabled = provider.enabled_models.get_or_insert_with(Vec::new);
        if enabled.contains(&settings.model) {
            provider.selected_model.clone_from(&settings.model);
        } else {
            provider.selected_model = enabled.first().cloned().unwrap_or_default();
        }
    }
    settings.active_provider_id = settings
        .providers
        .iter()
        .find(|provider| {
            provider.endpoint.trim().trim_end_matches('/')
                == settings.endpoint.trim().trim_end_matches('/')
                && !settings.model.is_empty()
                && provider.model_is_enabled(&settings.model)
        })
        .map(|provider| provider.id.clone());
    Ok(())
}

fn protocol(value: Protocol) -> &'static str {
    match value {
        Protocol::OpenAi => "",
        Protocol::Anthropic => "anthropic",
        Protocol::Responses => "openai-responses",
    }
}

fn model_json(
    raw: Option<&str>,
    settings: &Settings,
    secrets: &StoredSettingsSecrets,
) -> Result<Value, String> {
    let mut object: Map<String, Value> = raw
        .map(serde_json::from_str)
        .transpose()
        .map_err(|_| "无法读取待更新的共用设置")?
        .unwrap_or_default();
    let mut common = serde_json::to_value(settings).map_err(|_| "共用设置编码失败")?;
    let fields = common.as_object_mut().unwrap();
    for key in [
        "providers",
        "endpoint",
        "model",
        "protocol",
        "temperature",
        "top_p",
        "models",
        "enabled_models",
        "active_provider_id",
        "dark_mode",
    ] {
        fields.remove(key);
    }
    for (from, to) in [
        ("asr_endpoint", "asrBaseUrl"),
        ("asr_model", "asrModel"),
        ("asr_path", "asrPath"),
        ("speak_replies", "speakReplies"),
        ("active_assistant_id", "activeAssistantId"),
        ("system_prompt", "systemPrompt"),
        ("memory_context", "injectMemory"),
        ("subagents_enabled", "subagentsEnabled"),
        ("subagent_parallelism", "subagentParallelism"),
        ("subagent_model", "subagentModel"),
        ("subagent_profiles", "subagentProfiles"),
        ("default_subagent_profile_id", "defaultSubagentProfileId"),
        ("approval_mode", "approvalMode"),
        ("max_tool_rounds", "maxToolRounds"),
        ("compress_at_percent", "compressAtPercent"),
        ("model_contexts", "modelContexts"),
        ("model_max_tokens", "modelMaxTokens"),
        ("max_tokens", "maxTokens"),
        ("reasoning_effort", "reasoningEffort"),
    ] {
        if let Some(value) = fields.remove(from) {
            fields.insert(to.into(), value);
        }
    }
    fields.remove("steer_immediately");
    fields.insert(
        "steerMode".into(),
        json!(i32::from(settings.steer_immediately)),
    );
    for item in fields.values_mut() {
        compose_names(item);
    }
    if common["activeAssistantId"].is_null() {
        common["activeAssistantId"] = json!("");
    }
    common["approvalMode"] = json!(match settings.approval_mode {
        crate::subagent_configuration::ApprovalMode::Auto => "AUTO",
        crate::subagent_configuration::ApprovalMode::Ask => "ASK",
        crate::subagent_configuration::ApprovalMode::Deny => "DENY",
    });
    object.extend(common.as_object().unwrap().clone());
    let provider = settings.active_provider_id.as_ref().and_then(|id| {
        settings
            .providers
            .iter()
            .find(|provider| &provider.id == id)
    });
    let (endpoint, model, wire, key) = match provider {
        Some(provider) => (
            &provider.endpoint,
            &provider.selected_model,
            provider.protocol,
            secrets
                .provider_keys
                .get(&provider.id)
                .map_or("", StoredSecret::as_ciphertext),
        ),
        None => (
            &settings.endpoint,
            &settings.model,
            settings.protocol,
            secrets.api_key.as_ciphertext(),
        ),
    };
    let previous = object.get("providers").and_then(Value::as_array);
    let providers: Vec<_> = settings.providers.iter().map(|provider| {
        let mut fields = previous.and_then(|providers| providers.iter().find(|old| old["id"].as_str() == Some(&provider.id)))
            .and_then(Value::as_object).cloned().unwrap_or_default();
        let known = json!({
            "id": provider.id, "name": provider.name, "baseUrl": provider.endpoint,
            "apiKey": secrets.provider_keys.get(&provider.id).map_or("", StoredSecret::as_ciphertext),
            "protocol": protocol(provider.protocol), "models": provider.models,
            "enabled": provider.enabled_models(), "aliases": provider.aliases,
            "ownSampling": provider.own_sampling, "temperature": provider.temperature,
            "topP": provider.top_p, "maxTokens": provider.max_tokens, "reasoningEffort": provider.reasoning_effort,
        });
        fields.extend(known.as_object().unwrap().clone());
        Value::Object(fields)
    }).collect();
    let known = json!({
        "baseUrl": endpoint, "model": model, "protocol": protocol(wire), "apiKey": key,
        "providers": providers, "providersInitialized": settings.behavior.providers_initialized,
        "temperature": settings.behavior.sampling_temperature, "topP": settings.behavior.sampling_top_p,
        "sendTemperature": settings.behavior.send_temperature, "sendTopP": settings.behavior.send_top_p,
        "modelContexts": settings.model_contexts, "modelMaxTokens": settings.model_max_tokens,
        "maxTokens": settings.max_tokens, "reasoningEffort": settings.reasoning_effort,
        "requestHeaders": secrets.request_headers,
        "onboardingSeen": settings.behavior.onboarding_seen,
        "asrApiKey": secrets.asr_api_key, "searchApiKey": secrets.search_api_key,
    });
    object.extend(known.as_object().unwrap().clone());
    Ok(Value::Object(object))
}

fn compose_names(value: &mut Value) {
    match value {
        Value::Array(items) => items.iter_mut().for_each(compose_names),
        Value::Object(fields) => {
            for (from, to) in [
                ("avatar_path", "avatarPath"),
                ("created_at", "createdAt"),
                ("system_prompt", "systemPrompt"),
                ("provider_id", "providerId"),
                ("disabled_tools", "disabledTools"),
                ("assistant_id", "assistantId"),
                ("approval_mode", "approvalMode"),
                ("allowed_tools", "allowedTools"),
                ("allowed_actions", "allowedActions"),
                ("max_tool_rounds", "maxToolRounds"),
                ("timeout_seconds", "timeoutSeconds"),
                ("extra_instructions", "extraInstructions"),
            ] {
                if let Some(value) = fields.remove(from) {
                    fields.insert(to.into(), value);
                }
            }
            if let Some(Value::String(mode)) = fields.get_mut("approvalMode") {
                *mode = mode.to_uppercase();
            }
            if fields.contains_key("disabledTools")
                && fields.get("providerId") == Some(&Value::Null)
            {
                fields.insert("providerId".into(), json!(""));
            }
            for value in fields.values_mut() {
                compose_names(value);
            }
        }
        _ => {}
    }
}

pub(super) fn save(
    files: &Path,
    document: SettingsDocument,
    previous: (&Settings, &StoredSettingsSecrets),
) -> Result<(), CommitFailure> {
    let original = bytes(files)?;
    let (compose, _) = crate::legacy_import::settings_preferences(&original)?;
    let next = model_json(None, &document.settings, &document.secrets)?;
    let compose = match compose {
        Some(raw) if model_json(None, previous.0, previous.1)? == next => raw,
        raw => model_json(raw.as_deref(), &document.settings, &document.secrets)?.to_string(),
    };

    let rust = json!({"version": document.version, "settings": {
        "models": document.settings.models, "enabled_models": document.settings.enabled_models,
        "dark_mode": document.settings.dark_mode,
    }});
    let data =
        crate::legacy_import::set_settings_preferences(&original, &compose, &rust.to_string())?;
    owned_directory(files, "datastore")?;
    atomic_document(files, &files.join(FILE), LIMIT, |writer| {
        writer
            .write_all(&data)
            .map_err(|error| format!("写入共用设置失败：{error}"))
    })
}
