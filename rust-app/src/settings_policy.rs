//! Missing ordinary AppSettings fields. Root flattens these two companions into
//! state::Settings; existing Provider/Assistant and encrypted secret owners stay.
use crate::{platform_tools::automation_page::Preferences, state::Settings};
use serde::{Deserialize, Serialize};
use serde_json::Value;
#[derive(Clone, PartialEq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct BehaviorSettings {
    pub send_temperature: bool,
    pub send_top_p: bool,
    /// Retain sliders while the corresponding request field is disabled.
    pub sampling_temperature: f64,
    pub sampling_top_p: f64,
    pub tool_parallel: i32,
    pub auto_compress: bool,
    pub inject_profile: bool,
    pub retry_on_failure: bool,
    pub retry_max_attempts: i32,
    pub retry_delay_seconds: i32,
    #[serde(default)]
    pub ui_theme: i32,
    pub palette_mode: i32,
    pub theme_mode: i32,
    pub keep_screen_on: bool,
    pub image_input: bool,
    pub allow_insecure_tls: bool,
    pub search_endpoint: String,
    pub voice_input: bool,
    pub show_reasoning: bool,
    pub banner_seconds: i32,
    pub providers_initialized: bool,
    pub auto_follow: bool,
    pub floating_console: bool,
    pub console_while_running: bool,
    pub console_width_dp: i32,
    pub console_height_dp: i32,
    pub font_preset: i32,
    pub font_scale: i32,
    pub bubble_width: i32,
    pub assistant_card: bool,
    pub flat_padding: i32,
    pub corner_scale: i32,
    pub show_timestamps: bool,
    pub edge_light: bool,
    pub edge_light_in_foreground: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub edge_color: Option<u32>,
    pub typewriter: bool,
    pub onboarding_seen: bool,
    pub expand_tools: bool,
    pub expand_reasoning: bool,
    pub reasoning_height: i32,
    pub auto_profile: bool,
    pub profile_every_turns: i32,
    pub follow_reasoning: bool,
    pub enter_key_sends: bool,
}
impl std::fmt::Debug for BehaviorSettings {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("BehaviorSettings")
            .field("send_temperature", &self.send_temperature)
            .field("send_top_p", &self.send_top_p)
            .field("tool_parallel", &self.tool_parallel)
            .field("search_endpoint", &"<redacted>")
            .finish_non_exhaustive()
    }
}
/// The legacy converter calls this with its original object after decoding the
/// core Settings. Its normalized document has already replaced disabled sliders
/// with null; only the original object can retain those values for later edits.
pub fn apply_legacy_companions(original: &Value, core: &mut Settings) -> Result<(), String> {
    let behavior = BehaviorSettings::from_legacy(original)?;
    let execution = ExecutionSettings::from_legacy(original)?;
    behavior.apply_sampling(core);
    core.behavior = behavior;
    core.execution = execution;
    Ok(())
}
impl Default for BehaviorSettings {
    fn default() -> Self {
        Self {
            send_temperature: false,
            send_top_p: false,
            sampling_temperature: 0.6,
            sampling_top_p: 1.0,
            tool_parallel: 1,
            auto_compress: true,
            inject_profile: true,
            retry_on_failure: true,
            retry_max_attempts: 2,
            retry_delay_seconds: 5,
            ui_theme: 0,
            palette_mode: 2,
            theme_mode: 0,
            keep_screen_on: false,
            image_input: false,
            allow_insecure_tls: false,
            search_endpoint: String::new(),
            voice_input: false,
            show_reasoning: true,
            banner_seconds: 10,
            providers_initialized: false,
            auto_follow: true,
            floating_console: false,
            console_while_running: true,
            console_width_dp: 0,
            console_height_dp: 0,
            font_preset: 1,
            font_scale: 1,
            bubble_width: 1,
            assistant_card: false,
            flat_padding: 0,
            corner_scale: 1,
            show_timestamps: true,
            edge_light: true,
            edge_light_in_foreground: true,
            edge_color: None,
            typewriter: true,
            onboarding_seen: false,
            expand_tools: false,
            expand_reasoning: false,
            reasoning_height: 180,
            auto_profile: true,
            profile_every_turns: 8,
            follow_reasoning: true,
            enter_key_sends: true,
        }
    }
}
impl BehaviorSettings {
    /// Earlier Rust documents stored Option sampling fields without the Kotlin
    /// switches. Infer only missing switches, retaining explicit false values.
    pub fn from_rust_document(value: &Value, core: &Settings) -> Result<Self, String> {
        let mut behavior: Self =
            serde_json::from_value(value.clone()).map_err(|_| "普通设置字段类型无法读取")?;
        if value.get("sendTemperature").is_none() {
            behavior.send_temperature = core.temperature.is_some();
            if let Some(temperature) = core.temperature {
                behavior.sampling_temperature = temperature;
            }
        }
        if value.get("sendTopP").is_none() {
            behavior.send_top_p = core.top_p.is_some();
            if let Some(top_p) = core.top_p {
                behavior.sampling_top_p = top_p;
            }
        }
        behavior.validate()?;
        Ok(behavior)
    }
    pub fn from_legacy(value: &Value) -> Result<Self, String> {
        if !value.is_object() {
            return Err("旧设置不是对象".into());
        }
        let mut settings: Self =
            serde_json::from_value(value.clone()).map_err(|_| "旧普通设置字段类型无法转换")?;
        if let Some(temperature) = value.get("temperature").and_then(Value::as_f64) {
            settings.sampling_temperature = temperature;
        }
        if let Some(top_p) = value.get("topP").and_then(Value::as_f64) {
            settings.sampling_top_p = top_p;
        }
        settings.validate()?;
        Ok(settings)
    }
    pub fn validate(&self) -> Result<(), String> {
        if !self.sampling_temperature.is_finite()
            || !self.sampling_top_p.is_finite()
            || self.search_endpoint.len() > 4096
            || self.search_endpoint.contains('\0')
        {
            return Err("普通设置包含无效采样值或搜索地址".into());
        }
        Ok(())
    }
    /// Called by the actor after edits; provider ownSampling remains in its
    /// existing configuration owner and cannot be reset by a global checkbox.
    pub fn apply_sampling(&self, settings: &mut Settings) {
        settings.temperature = self.send_temperature.then_some(self.sampling_temperature);
        settings.top_p = self.send_top_p.then_some(self.sampling_top_p);
    }
    pub fn sync_sampling_from_core(&mut self, settings: &Settings) {
        self.send_temperature = settings.temperature.is_some();
        self.send_top_p = settings.top_p.is_some();
        if let Some(value) = settings.temperature {
            self.sampling_temperature = value;
        }
        if let Some(value) = settings.top_p {
            self.sampling_top_p = value;
        }
    }
    pub fn automation_preferences(&self, color: u32) -> Preferences {
        Preferences {
            edge_enabled: self.edge_light,
            edge_in_foreground: self.edge_light_in_foreground,
            edge_color: color,
        }
    }
    pub fn apply_automation_preferences(&mut self, preferences: &Preferences) {
        self.edge_light = preferences.edge_enabled;
        self.edge_light_in_foreground = preferences.edge_in_foreground;
        self.edge_color = Some(preferences.edge_color);
    }
    pub fn tool_parallelism(&self) -> usize {
        self.tool_parallel.clamp(1, 4) as usize
    }
    pub fn tool_parallelism_for(&self, mode: crate::subagent_configuration::ApprovalMode) -> usize {
        if matches!(mode, crate::subagent_configuration::ApprovalMode::Ask) {
            1
        } else {
            self.tool_parallelism()
        }
    }
    pub fn profile_interval(&self) -> u32 {
        if (2..=40).contains(&self.profile_every_turns) {
            self.profile_every_turns as u32
        } else {
            8
        }
    }
    pub fn bubble_fraction(&self) -> f32 {
        match self.bubble_width {
            0 => 0.82,
            2 => 1.0,
            _ => 0.94,
        }
    }
    pub fn flat_padding_dp(&self) -> u32 {
        match self.flat_padding {
            0 => 2,
            2 => 10,
            _ => 6,
        }
    }
    pub fn reasoning_height_dp(&self) -> u32 {
        match self.reasoning_height {
            240 => 240,
            320 => 320,
            _ => 180,
        }
    }
    pub fn retry_policy(&self) -> RetryPolicy {
        RetryPolicy {
            enabled: self.retry_on_failure,
            max_attempts: self.retry_max_attempts.clamp(0, 5) as u32,
            delay_seconds: self.retry_delay_seconds.clamp(1, 60) as u32,
        }
    }
}
#[derive(Clone, Copy, Debug)]
pub struct RetryPolicy {
    pub enabled: bool,
    pub max_attempts: u32,
    pub delay_seconds: u32,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, Default)]
#[serde(default, rename_all = "camelCase")]
pub struct ExecutionSettings {
    pub preferred_capability: String,
    /// Historical facts only. A fresh runtime generation must confirm UID0.
    pub root_available: bool,
    pub root_checked_at: i64,
    pub always_allowed_tools: Vec<String>,
    pub always_denied_tools: Vec<String>,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Preference {
    Automatic,
    AppUid,
    Accessibility,
    Shizuku,
    Root,
}
impl Preference {
    pub fn parse(value: &str) -> Self {
        match value {
            "none" => Self::AppUid,
            "accessibility" => Self::Accessibility,
            "shell" => Self::Shizuku,
            "root" => Self::Root,
            _ => Self::Automatic,
        }
    }
    pub fn legacy_key(self) -> &'static str {
        match self {
            Self::Automatic => "",
            Self::AppUid => "none",
            Self::Accessibility => "accessibility",
            Self::Shizuku => "shell",
            Self::Root => "root",
        }
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ApprovalOverride {
    Inherit,
    Auto,
    Deny,
}
impl ExecutionSettings {
    pub fn from_legacy(value: &Value) -> Result<Self, String> {
        if !value.is_object() {
            return Err("旧执行设置不是对象".into());
        }
        let settings: Self =
            serde_json::from_value(value.clone()).map_err(|_| "旧执行设置字段类型无法转换")?;
        settings.validate()?;
        Ok(settings)
    }
    pub fn validate(&self) -> Result<(), String> {
        if self.preferred_capability.len() > 128
            || self.preferred_capability.contains('\0')
            || self.root_checked_at < 0
        {
            return Err("执行设置档位或历史检测时间无效".into());
        }
        for tools in [&self.always_allowed_tools, &self.always_denied_tools] {
            if tools.len() > 1024
                || tools
                    .iter()
                    .any(|name| name.is_empty() || name.len() > 128 || name.contains('\0'))
            {
                return Err("工具允许或拒绝清单超出预算".into());
            }
        }
        Ok(())
    }
    pub fn preference(&self) -> Preference {
        Preference::parse(&self.preferred_capability)
    }
    pub fn approval_for(&self, name: &str) -> ApprovalOverride {
        if self.always_denied_tools.iter().any(|tool| tool == name) {
            ApprovalOverride::Deny
        } else if self.always_allowed_tools.iter().any(|tool| tool == name) {
            ApprovalOverride::Auto
        } else {
            ApprovalOverride::Inherit
        }
    }
}
