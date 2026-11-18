use super::*;
use crate::state::{SettingsResetScope, SettingsSnapshot, SettingsStamp};
impl Store {
    pub(crate) fn settings_stamp(&self) -> SettingsStamp {
        SettingsStamp {
            generation: self.history_generation_token(),
            revision: self.settings_revision,
        }
    }
    pub fn settings_snapshot(&self) -> SettingsSnapshot {
        SettingsSnapshot {
            stamp: SettingsStamp {
                generation: self.history_generation_token(),
                revision: self.settings_revision,
            },
            settings: self.settings.clone(),
        }
    }
    fn admit_settings(&self, request_id: &str, expected: &SettingsStamp) -> Result<(), String> {
        if request_id.is_empty() || request_id.len() > 128 || request_id.contains('\0') {
            return Err("设置请求标识无效".into());
        }
        if expected != &self.settings_stamp() {
            return Err(
                "设置已由另一操作更新或数据代次已变化；草稿已保留，请放弃修改后重新编辑".into(),
            );
        }
        self.writable()
    }
    pub fn save_settings_for(
        &mut self,
        request_id: &str,
        expected: &SettingsStamp,
        settings: Settings,
    ) -> Result<SettingsSnapshot, String> {
        self.admit_settings(request_id, expected)?;
        // UI sliders explicitly synchronize Option knobs; provider ownSampling
        // remains owned by each Provider. Never infer a global edit from them.
        let mut settings = settings;
        let behavior = settings.behavior.clone();
        behavior.apply_sampling(&mut settings);
        self.save_settings(settings)?;
        Ok(self.settings_snapshot())
    }
    pub fn reset_settings_for(
        &mut self,
        request_id: &str,
        expected: &SettingsStamp,
        scope: SettingsResetScope,
    ) -> Result<SettingsSnapshot, String> {
        self.admit_settings(request_id, expected)?;
        let mut settings = self.settings.clone();
        scope.apply(&mut settings);
        // Keep every secret including unreadable ciphertext in the one owner.
        self.save_settings_with_secret_edits(settings, SettingsSecretEdits::default())?;
        Ok(self.settings_snapshot())
    }
    pub fn finish_onboarding_for(
        &mut self,
        request_id: &str,
        expected: &SettingsStamp,
        mut settings: Settings,
        clear_secrets: &[crate::platform::secrets::SettingsSecretTarget],
    ) -> Result<SettingsSnapshot, String> {
        self.admit_settings(request_id, expected)?;
        if !settings.behavior.onboarding_seen {
            return Err("引导完成请求缺少完成标记".into());
        }
        let behavior = settings.behavior.clone();
        behavior.apply_sampling(&mut settings);
        let clear_connection_headers =
            !crate::network::same_connection_endpoint(&self.settings.endpoint, &settings.endpoint)
                || self.settings.protocol != settings.protocol
                || self.settings.api_key != settings.api_key
                || clear_secrets
                    .iter()
                    .any(|target| matches!(target, SettingsSecretTarget::ApiKey));
        self.save_settings_clearing(settings, clear_secrets, clear_connection_headers)?;
        Ok(self.settings_snapshot())
    }
}
